package com.hiennv.flutter_callkit_incoming

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Measures whether the full-screen ringing UI actually appears on the lock screen.
 *
 * WHY THIS EXISTS
 *
 * "The lock screen shows a heads-up card instead of the full-screen call" has more
 * causes than an app can enumerate. On Android 14+ the app can at least ASK the
 * platform ([android.app.NotificationManager.canUseFullScreenIntent]). Below API 34
 * the permission is granted unconditionally, so that question always answers "yes" —
 * and yet the ring still fails to go full screen on many OEM builds, because
 * MIUI / ColorOS / EMUI gate the activity launch behind their own private
 * permissions ("display pop-up windows while running in the background", "show on
 * lock screen"). Those switches have no public API: they cannot be queried, cannot
 * be requested, and their AppOps codes are vendor-internal and version-specific.
 * Reflecting into them is guesswork that rots with every OEM release.
 *
 * So this class does not ask WHY. It observes the OUTCOME:
 *
 *  1. When the incoming notification is posted, record whether the device was
 *     locked or the screen was off — the only states in which the platform is
 *     supposed to launch the full-screen intent. A ring that arrives while the
 *     phone is IN USE is deliberately not recorded: a heads-up is the correct
 *     behaviour there, and counting it would report a defect that does not exist.
 *  2. When [CallkitIncomingActivity] starts, record that the full-screen UI did
 *     appear. That is the definitive proof that full-screen ringing works on this
 *     device, and it is recorded the moment it happens.
 *  3. When the ring ends (accepted / declined / timed out / cancelled), a ring that
 *     was expected to go full screen but never did is recorded as blocked.
 *
 * The verdict is a fact about this device, gathered from real calls, and it is what
 * the app shows the user instead of a guess. No timer and no timeout constant is
 * involved: both verdicts are reached by an event that has already happened.
 */
object FullScreenRingProbe {

    private const val TAG = "FullScreenRingProbe"

    /** No lock-screen ring has been observed yet on this install. */
    const val OBSERVED_UNKNOWN = "unknown"

    /** A lock-screen ring DID open the full-screen UI. */
    const val OBSERVED_WORKS = "works"

    /** A lock-screen ring did NOT open the full-screen UI. */
    const val OBSERVED_BLOCKED = "blocked"

    /** Call id of a ring posted while the device was locked, still undecided. */
    private const val KEY_PENDING = "FSI_PROBE_PENDING"

    /** Latest verdict. */
    private const val KEY_OBSERVED = "FSI_PROBE_OBSERVED"

    /**
     * Manufacturers whose ROMs are known to gate background activity starts (and
     * therefore full-screen intents) behind their own permission screens.
     *
     * This list only decides whether the app OFFERS help before it has measured
     * anything; it never overrides a measurement. Being on it is not a claim that
     * the gate is closed — that cannot be queried — only that such a gate exists
     * and the user may need to open it.
     */
    private val GATED_MANUFACTURERS = setOf(
        "xiaomi", "redmi", "poco",          // MIUI / HyperOS
        "oppo", "realme", "oneplus",        // ColorOS
        "vivo", "iqoo",                     // Funtouch / OriginOS
        "huawei", "honor",                  // EMUI / MagicOS
        "meizu",                            // Flyme
    )

    /** Does this device's ROM have a vendor gate of the kind described above? */
    fun isManufacturerGated(): Boolean =
        Build.MANUFACTURER.lowercase() in GATED_MANUFACTURERS ||
            Build.BRAND.lowercase() in GATED_MANUFACTURERS

    /** The latest verdict, or [OBSERVED_UNKNOWN] if no lock-screen ring happened yet. */
    fun observed(context: Context?): String =
        getString(context, KEY_OBSERVED, OBSERVED_UNKNOWN) ?: OBSERVED_UNKNOWN

    /**
     * An incoming notification carrying a full-screen intent was just posted.
     *
     * Only rings posted while the device is locked or the screen is off can tell us
     * anything, so anything else clears the pending marker rather than leaving a
     * stale one behind that a later ring would wrongly resolve.
     */
    fun onIncomingPosted(context: Context?, callId: String?) {
        if (context == null || callId.isNullOrEmpty()) return
        if (!isDeviceLocked(context)) {
            remove(context, KEY_PENDING)
            return
        }
        putString(context, KEY_PENDING, callId)
        Log.i(TAG, "lock-screen ring posted, awaiting full-screen UI: $callId")
    }

    /** The full-screen ringing activity started — full screen works on this device. */
    fun onFullScreenShown(context: Context?, callId: String?) {
        if (context == null) return
        remove(context, KEY_PENDING)
        if (observed(context) == OBSERVED_WORKS) return
        putString(context, KEY_OBSERVED, OBSERVED_WORKS)
        Log.i(TAG, "full-screen ring shown ($callId) → observed=works")
    }

    /**
     * The ring reached a terminal state. A ring that was expected to go full screen
     * and never did is the measurement we are after.
     */
    fun onRingFinished(context: Context?, callId: String?) {
        if (context == null || callId.isNullOrEmpty()) return
        val pending = getString(context, KEY_PENDING, "")
        if (pending != callId) return
        remove(context, KEY_PENDING)
        putString(context, KEY_OBSERVED, OBSERVED_BLOCKED)
        Log.w(TAG, "lock-screen ring never went full screen ($callId) → observed=blocked")
    }

    /**
     * Locked, or screen off. Both are states in which the platform launches a
     * notification's full-screen intent instead of showing a heads-up.
     */
    private fun isDeviceLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isKeyguardLocked == true) return true
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return !power.isInteractive
    }
}
