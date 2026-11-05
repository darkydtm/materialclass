package org.materialclass

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.materialclass.security.StorageLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class UiActorBarrierExperimentTest {
	private fun await(latch: CountDownLatch) = assertTrue("Timed out", latch.await(10, TimeUnit.SECONDS))

	private class Actor(private val dispatch: (() -> Unit) -> Boolean) {
		private val admissionLock = Any()
		private val generation = AtomicLong(0)
		private val controllers = mutableListOf<Controller>()
		var subscriber: () -> Unit = {}
		var onSet: (String?) -> Unit = {}

		inner class Controller {
			var value: String? = null
			fun set(value: String?) {
				check(Looper.myLooper() == Looper.getMainLooper())
				check(!Thread.holdsLock(admissionLock) && !Thread.holdsLock(StorageLock))
				this.value = value
				onSet(value)
			}
		}

		fun controller(): Controller = Controller().also { controllers.add(it) }
		fun ticket(): Long = generation.get()
		fun cancel(): Long = synchronized(admissionLock) { generation.incrementAndGet() }
		fun publish(ticket: Long, controller: Controller, value: String, beforeSet: () -> Unit = {}) {
			check(dispatch {
				if (generation.get() == ticket) {
					beforeSet()
					controller.set(value)
				}
			})
		}
		fun revoke(ticket: Long, ack: CountDownLatch): Boolean = dispatch {
			if (generation.get() == ticket) {
				controllers.forEach { it.set(null) }
				check(!Thread.holdsLock(admissionLock) && !Thread.holdsLock(StorageLock))
				subscriber()
				ack.countDown()
			}
		}
	}

	@Test fun runningSetterCanPublishAfterCancellationButBarrierClearsBoth() {
		val handler = Handler(Looper.getMainLooper())
		val actor = Actor { handler.post(it) }
		val first = actor.controller()
		val second = actor.controller()
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val published = CountDownLatch(1)
		val acknowledged = CountDownLatch(1)
		val subscriberEntered = CountDownLatch(1)
		val subscriberRelease = CountDownLatch(1)
		val old = actor.ticket()
		try {
			actor.publish(old, first, "old-running") { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
			await(entered)
			actor.publish(old, second, "old-queued")
			val cancelled = CountDownLatch(1)
			var revokeTicket = -1L
			val cancelThread = thread { revokeTicket = actor.cancel(); cancelled.countDown() }
			await(cancelled)
			cancelThread.join()
			actor.onSet = { if (it == "old-running") published.countDown() }
			actor.subscriber = {
				assertEquals(0L, published.count)
				assertNull(first.value)
				assertNull(second.value)
				subscriberEntered.countDown()
				check(subscriberRelease.await(10, TimeUnit.SECONDS))
			}
			assertTrue(actor.revoke(revokeTicket, acknowledged))
			assertEquals(1L, acknowledged.count)
			release.countDown()
			await(subscriberEntered)
			assertEquals(1L, acknowledged.count)
			subscriberRelease.countDown()
			await(acknowledged)
			await(published)
			assertNull(first.value)
			assertNull(second.value)
		} finally { release.countDown(); subscriberRelease.countDown() }
	}

	@Test fun failedDispatchAndRepeatedCancellationRequireLatestBarrier() {
		val handler = Handler(Looper.getMainLooper())
		var reject = true
		val actor = Actor { if (reject) false else handler.post(it) }
		val first = actor.controller()
		val second = actor.controller()
		val failed = CountDownLatch(1)
		val old = actor.cancel()
		assertFalse(actor.revoke(old, failed))
		assertEquals(1L, failed.count)
		reject = false
		val blocked = CountDownLatch(1)
		val release = CountDownLatch(1)
		val staleAck = CountDownLatch(1)
		val currentAck = CountDownLatch(1)
		try {
			assertTrue(handler.post { blocked.countDown(); check(release.await(10, TimeUnit.SECONDS)) })
			await(blocked)
			assertTrue(actor.revoke(old, staleAck))
			val current = actor.cancel()
			assertTrue(actor.revoke(current, currentAck))
			release.countDown()
			await(currentAck)
			assertEquals(1L, staleAck.count)
			assertEquals(1L, failed.count)
			assertNull(first.value)
			assertNull(second.value)
			val newPublished = CountDownLatch(1)
			actor.publish(actor.ticket(), first, "new-session")
			assertTrue(handler.post { newPublished.countDown() })
			await(newPublished)
			assertEquals("new-session", first.value)
		} finally { release.countDown() }
	}
}
