# Cleanup durability limits

Cleanup records the old cache namespace before removing its cache or session. Journal writes sync the temporary file and destination directory; cleanup syncs the cache and session directories. These calls depend on the filesystem and storage hardware honoring sync and rename. They do not prove survival of power loss or Android Keystore deletion durability.

If journal creation fails after its existing contents have been checked, cleanup attempts to delete the session key but retains the encrypted session file and the in-process cleanup ticket. The failure remains visible and retryable. If the process dies before a journal is durable, the namespace may be unavailable after restart, especially if the session key was deleted; cleanup cannot guarantee recovery of that namespace. If journal reading fails or identifies a different pending namespace, cleanup does not delete the session key because it cannot establish ownership safely.
