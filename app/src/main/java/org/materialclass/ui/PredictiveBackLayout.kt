package org.materialclass.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.core.graphics.toColorInt
import androidx.core.view.doOnPreDraw
import androidx.customview.widget.ViewDragHelper
import kotlin.math.abs
import kotlin.math.roundToInt

internal class PredictiveBackLayout(
	context: Context,
) : ViewGroup(context) {
	private val backgroundLayer = View(context)
	private val shadowLayer = View(context)
	private var previousLayer = ComposeLayer(context)
	private var contentLayer = ComposeLayer(context)
	private val dragHelper = ViewDragHelper.create(this, 1f, DragCallback())
	private val edgeSize = (24 * resources.displayMetrics.density).roundToInt()
	private val shadowWidth = (24 * resources.displayMetrics.density).roundToInt()
	private var edgeGesture = false
	private var recovering = false
	private var closeDispatched = false
	private var currentRoute: AppRoute? = null
	private var onClose: (() -> Unit)? = null
	private var gestureEnabled = false
	private var lastLaidOutLeft = 0
	private var currentWidth = 0
	private var awaitingContentSwap = false
	private var contentSwapPending = false
	private var swapPreviousContent: (@Composable () -> Unit)? = null
	private var openingAnimator: ValueAnimator? = null

	init {
		setWillNotDraw(false)
		dragHelper.setEdgeTrackingEnabled(ViewDragHelper.EDGE_LEFT)
		backgroundLayer.isClickable = false
		shadowLayer.isClickable = false
		shadowLayer.background = GradientDrawable(
			GradientDrawable.Orientation.LEFT_RIGHT,
			intArrayOf(Color.TRANSPARENT, "#99000000".toColorInt()),
		)
		addView(backgroundLayer)
		addView(previousLayer)
		addView(shadowLayer)
		addView(contentLayer)
	}

	fun updateLayers(
		backgroundColor: Int,
		gestureEnabled: Boolean,
		previousRoute: AppRoute?,
		currentRoute: AppRoute,
		previousContent: (@Composable () -> Unit)?,
		currentContent: @Composable () -> Unit,
		onClose: () -> Unit,
	) {
		this.onClose = onClose
		this.gestureEnabled = gestureEnabled
		backgroundLayer.setBackgroundColor(backgroundColor)
		if (this.currentRoute != currentRoute) {
			val opening = this.currentRoute != null && previousRoute == this.currentRoute && gestureEnabled && !awaitingContentSwap && width > 0
			openingAnimator?.cancel()
			openingAnimator = null
			val swapAfterClose = awaitingContentSwap
			awaitingContentSwap = false
			contentSwapPending = false
			this.currentRoute = currentRoute
			closeDispatched = false
			recovering = false
			lastLaidOutLeft = 0
			contentLayer.offsetLeftAndRight(-contentLayer.left)
			backgroundLayer.alpha = 1f
			positionShadow(0)
			if (swapAfterClose) {
				val oldContent = contentLayer
				contentLayer = previousLayer
				previousLayer = oldContent
				previousLayer.bringToFront()
				shadowLayer.bringToFront()
				contentLayer.bringToFront()
				contentLayer.updateContent(currentContent)
				previousLayer.updateContent(previousContent)
				previousLayer.visibility = if (previousContent == null) INVISIBLE else VISIBLE
				contentLayer.visibility = VISIBLE
			} else {
				if (opening) {
					val oldContent = contentLayer
					contentLayer = previousLayer
					previousLayer = oldContent
					previousLayer.bringToFront()
					shadowLayer.bringToFront()
					contentLayer.bringToFront()
				} else {
					previousLayer.updateContent(previousContent)
				}
				contentLayer.updateContent(currentContent)
				previousLayer.visibility = if (previousContent == null) INVISIBLE else VISIBLE
				contentLayer.visibility = VISIBLE
				if (opening) {
					setPredictiveProgress(1f)
					openingAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
						duration = 300L
						addUpdateListener { setPredictiveProgress(it.animatedValue as Float) }
						start()
					}
				}
			}
		} else {
			contentSwapPending = false
			swapPreviousContent = null
			previousLayer.updateContent(previousContent)
			contentLayer.updateContent(currentContent)
			previousLayer.visibility = if (previousContent == null) INVISIBLE else VISIBLE
			contentLayer.visibility = VISIBLE
		}
		if (previousRoute == null) {
			previousLayer.visibility = INVISIBLE
		}
		requestLayout()
		postInvalidateOnAnimation()
	}

	fun setPredictiveProgress(progress: Float) {
		if (!gestureEnabled || width == 0) return
		val value = progress.coerceIn(0f, 1f)
		val left = (width * value).roundToInt()
		lastLaidOutLeft = left
		contentLayer.offsetLeftAndRight(left - contentLayer.left)
		backgroundLayer.alpha = 1f - value
		positionShadow(left)
		postInvalidateOnAnimation()
	}

	fun commitPredictiveBack() {
		if (!gestureEnabled) return
		setPredictiveProgress(1f)
		if (!closeDispatched) {
			closeDispatched = true
			awaitingContentSwap = true
			onClose?.invoke()
		}
	}

	fun cancelPredictiveBack() {
		if (!gestureEnabled) return
		closeDispatched = false
		lastLaidOutLeft = 0
		contentLayer.offsetLeftAndRight(-contentLayer.left)
		backgroundLayer.alpha = 1f
		positionShadow(0)
		postInvalidateOnAnimation()
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val width = MeasureSpec.getSize(widthMeasureSpec)
		val height = MeasureSpec.getSize(heightMeasureSpec)
		currentWidth = width
		setMeasuredDimension(width, height)
		val childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
		val childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
		previousLayer.measure(childWidth, childHeight)
		backgroundLayer.measure(childWidth, childHeight)
		shadowLayer.measure(MeasureSpec.makeMeasureSpec(shadowWidth, MeasureSpec.EXACTLY), childHeight)
		contentLayer.measure(childWidth, childHeight)
	}

	override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
		super.onSizeChanged(width, height, oldWidth, oldHeight)
		if (oldWidth != 0 && width != oldWidth) {
			lastLaidOutLeft = 0
			contentLayer.offsetLeftAndRight(-contentLayer.left)
			backgroundLayer.alpha = 1f
			positionShadow(0)
		}
	}

	override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

	override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
		val contentLeft = lastLaidOutLeft.coerceIn(0, width)
		previousLayer.layout(0, 0, width, height)
		backgroundLayer.layout(0, 0, width, height)
		shadowLayer.layout(contentLeft - shadowWidth, 0, contentLeft, height)
		contentLayer.layout(contentLeft, 0, contentLeft + width, height)
	}

	override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
		return try {
			if (!gestureEnabled) return false
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					edgeGesture = event.x <= edgeSize
					if (edgeGesture) {
						parent?.requestDisallowInterceptTouchEvent(true)
					}
				}
			}
			val intercepted = dragHelper.shouldInterceptTouchEvent(event)
			resetEdgeGesture(event)
			if (event.actionMasked == MotionEvent.ACTION_DOWN) false else intercepted
		} catch (_: RuntimeException) {
			handleTouchFailure(event)
			false
		}
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		return try {
			if (!gestureEnabled) return false
			dragHelper.processTouchEvent(event)
			resetEdgeGesture(event)
			true
		} catch (_: RuntimeException) {
			handleTouchFailure(event)
			true
		}
	}

	override fun computeScroll() {
		if (dragHelper.continueSettling(true)) {
			postInvalidateOnAnimation()
		} else if (recovering && contentLayer.left == 0) {
			recovering = false
			backgroundLayer.alpha = 1f
			positionShadow(0)
		}
	}

	private fun handleTouchFailure(event: MotionEvent) {
		if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
			dragHelper.abort()
			if (contentLayer.left > 0) {
				recoverToStart()
			} else {
				backgroundLayer.alpha = 1f
				positionShadow(0)
			}
		}
		edgeGesture = false
		parent?.requestDisallowInterceptTouchEvent(false)
	}

	private fun resetEdgeGesture(event: MotionEvent) {
		if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
			parent?.requestDisallowInterceptTouchEvent(false)
			edgeGesture = false
		}
	}

	private fun positionShadow(contentLeft: Int) {
		lastLaidOutLeft = contentLeft
		shadowLayer.offsetLeftAndRight(contentLeft - shadowWidth - shadowLayer.left)
	}

	private fun recoverToStart() {
		if (recovering || contentLayer.left == 0) return
		recovering = true
		if (!dragHelper.smoothSlideViewTo(contentLayer, 0, 0)) {
			lastLaidOutLeft = 0
			contentLayer.offsetLeftAndRight(-contentLayer.left)
			backgroundLayer.alpha = 1f
			positionShadow(0)
			recovering = false
			invalidate()
		} else {
			postInvalidateOnAnimation()
		}
	}

	private inner class DragCallback : ViewDragHelper.Callback() {
		override fun tryCaptureView(child: View, pointerId: Int): Boolean = gestureEnabled && edgeGesture && child === contentLayer

		override fun getViewHorizontalDragRange(child: View): Int = width

		override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int = left.coerceIn(0, width)

		override fun onViewCaptured(capturedChild: View, activePointerId: Int) {
			recovering = false
		}

		override fun onEdgeDragStarted(edgeFlags: Int, pointerId: Int) {
			if (gestureEnabled && edgeGesture && edgeFlags and ViewDragHelper.EDGE_LEFT != 0 && contentLayer.left == 0) {
				dragHelper.captureChildView(contentLayer, pointerId)
			}
		}

		override fun onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) {
			val progress = if (width == 0) 1f else (1f - left.toFloat() / width).coerceIn(0f, 1f)
			backgroundLayer.alpha = progress
			positionShadow(left)
			postInvalidateOnAnimation()
		}

		override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
			val fastHorizontalSwipe = xvel >= minFlingVelocity() && xvel > abs(yvel)
			val target = if (releasedChild.left >= currentWidth * 0.35f || fastHorizontalSwipe) currentWidth else 0
			if (!dragHelper.settleCapturedViewAt(target, 0)) {
				lastLaidOutLeft = target
				releasedChild.offsetLeftAndRight(target - releasedChild.left)
				onViewPositionChanged(releasedChild, target, 0, 0, 0)
			}
			postInvalidateOnAnimation()
		}

		private fun minFlingVelocity(): Float = 400f * resources.displayMetrics.density

		override fun onViewDragStateChanged(state: Int) {
			if (state != ViewDragHelper.STATE_IDLE) return
			if (contentLayer.left >= currentWidth || backgroundLayer.alpha <= 0f) {
				if (!closeDispatched) {
					closeDispatched = true
					awaitingContentSwap = true
					onClose?.invoke()
				}
			} else if (contentLayer.left > 0) {
				recoverToStart()
			} else {
				recovering = false
			}
		}
	}

	private class ComposeLayer(context: Context) : AbstractComposeView(context) {
		private var content: (@Composable () -> Unit)? by mutableStateOf(null)

		@Composable
		override fun Content() {
			content?.invoke()
		}

		fun updateContent(value: (@Composable () -> Unit)?) {
			content = value
		}
	}
}
