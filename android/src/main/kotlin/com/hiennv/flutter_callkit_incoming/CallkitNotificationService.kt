package com.hiennv.flutter_callkit_incoming

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

class CallkitNotificationService : Service() {

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var incomingTimeoutRunnable: Runnable? = null

    companion object {

        private val ActionForeground = listOf(
            CallkitConstants.ACTION_CALL_INCOMING,
            CallkitConstants.ACTION_CALL_START,
            CallkitConstants.ACTION_CALL_ACCEPT
        )


        fun startServiceWithAction(context: Context, action: String, data: Bundle?) {
            val intent = Intent(context, CallkitNotificationService::class.java).apply {
                this.action = action
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && intent.action in ActionForeground) {
                if (intent.action == CallkitConstants.ACTION_CALL_INCOMING) {
                    // Incoming ring must always run as a foreground service: on
                    // Android 14+ a CallStyle notification is only valid when it is
                    // attached to a foreground service or carries a fullScreenIntent.
                    // Apps without USE_FULL_SCREEN_INTENT otherwise get
                    // IllegalArgumentException ("CallStyle notifications must be for
                    // a foreground service...") and NO ring UI at all.
                    // Background start is permitted here because the incoming call
                    // was just registered with Telecom (phoneCall FGS exemption).
                    ContextCompat.startForegroundService(context, intent)
                    return
                }
                data?.let {
                    if(it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                        ContextCompat.startForegroundService(context, intent)
                    }else {
                        context.startService(intent)
                    }
                }
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CallkitNotificationService::class.java)
            context.stopService(intent)
        }

    }

    // Get notification manager dynamically to handle plugin lifecycle properly
    private fun getCallkitNotificationManager(): CallkitNotificationManager? {
        return FlutterCallkitIncomingPlugin.getInstance()?.getCallkitNotificationManager()
    }


    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action === CallkitConstants.ACTION_CALL_INCOMING) {
            intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                ?.let {
                    showIncomingCallNotification(it)
                } ?: stopSelf()
        }
        if (intent?.action === CallkitConstants.ACTION_CALL_START) {
            intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                ?.let {
                    if(it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                        getCallkitNotificationManager()?.createNotificationChanel(it)
                        showOngoingCallNotification(it)
                    }else {
                        stopSelf()
                    }
                }
        }
        if (intent?.action === CallkitConstants.ACTION_CALL_ACCEPT) {
            cancelIncomingTimeout()
            intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                ?.let {
                    getCallkitNotificationManager()?.clearIncomingNotification(it, true)
                    if (it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                        showOngoingCallNotification(it)
                    }else {
                        stopSelf()
                    }
                }
        }
        return START_STICKY
    }

    /**
     * Shows the incoming (ringing) notification as THIS service's foreground
     * notification. Android 14+ only accepts a CallStyle notification when it is
     * attached to a foreground service or has a fullScreenIntent; routing the ring
     * through the existing phoneCall-typed service satisfies the first branch and
     * works for apps that had to drop USE_FULL_SCREEN_INTENT for Play policy.
     *
     * Only FOREGROUND_SERVICE_TYPE_PHONE_CALL is used while ringing: microphone /
     * camera types are while-in-use restricted and cannot be taken from a
     * background (FCM) start; nothing is recorded during ring anyway. The accept
     * path (showOngoingCallNotification) upgrades the types once the user acts.
     */
    @SuppressLint("MissingPermission")
    private fun showIncomingCallNotification(bundle: Bundle) {
        val callkitNotification = getCallkitNotificationManager()?.getIncomingNotification(bundle)
        if (callkitNotification != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    callkitNotification.id,
                    callkitNotification.notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else {
                startForeground(callkitNotification.id, callkitNotification.notification)
            }
            scheduleIncomingTimeout(bundle)
        } else {
            stopSelf()
        }
    }

    /**
     * A foreground-service notification is non-dismissable, so the builder's
     * setTimeoutAfter/deleteIntent pair (which normally fires ACTION_CALL_TIMEOUT
     * when the notification times out) never triggers. Re-create that contract
     * here: after EXTRA_CALLKIT_DURATION ms, send the exact same timeout broadcast
     * the deleteIntent would have sent, so missed-call handling stays identical.
     */
    private fun scheduleIncomingTimeout(bundle: Bundle) {
        cancelIncomingTimeout()
        val duration = bundle.getLong(CallkitConstants.EXTRA_CALLKIT_DURATION, 0L)
        if (duration <= 0L) return
        incomingTimeoutRunnable = Runnable {
            incomingTimeoutRunnable = null
            applicationContext.sendBroadcast(
                CallkitIncomingBroadcastReceiver.getIntentTimeout(applicationContext, bundle)
            )
        }.also { timeoutHandler.postDelayed(it, duration) }
    }

    private fun cancelIncomingTimeout() {
        incomingTimeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        incomingTimeoutRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun showOngoingCallNotification(bundle: Bundle) {

        val callkitNotification =
            getCallkitNotificationManager()?.getOnGoingCallNotification(bundle, false)
        if (callkitNotification != null) {
            val typeCall = bundle.getInt(CallkitConstants.EXTRA_CALLKIT_TYPE, -1)
            startForeground(
                callkitNotification.id,
                callkitNotification.notification,
                typeCall > 0
            )
        }
    }

    private fun startForeground(notificationId: Int, notification: Notification, isVideo: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (isVideo) {
                    mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
            startForeground(notificationId, notification, mask)
        } else {
            startForeground(notificationId, notification)
        }
    }


    override fun onDestroy() {
        cancelIncomingTimeout()
        super.onDestroy()
        // Don't destroy the notification manager here as it's shared across the app
        // The plugin will handle cleanup when all engines are detached
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }


    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Don't kill the FGS. The app might be closed by user but the call is still ongoing
    }
}
