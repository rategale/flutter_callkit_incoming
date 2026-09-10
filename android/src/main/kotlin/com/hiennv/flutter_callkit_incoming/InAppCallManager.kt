package com.hiennv.flutter_callkit_incoming

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.M)
class InAppCallManager(private val context: Context) {

    companion object {
        private const val ACCOUNT_ID = "flutter_callkit_incoming_in_app_call_account"
        private const val TAG = "InAppCallManager"
    }

    fun registerPhoneAccount() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val componentName = ComponentName(context, CallkitConnectionService::class.java)
        val handle = PhoneAccountHandle(componentName, ACCOUNT_ID)

        // Video capability is declared here, not only on the Connection: Telecom
        // reads the ACCOUNT's capabilities when it decides whether an incoming
        // call may carry a video state. Without them
        // `EXTRA_INCOMING_VIDEO_STATE` is clamped to audio and every video call
        // is registered with the OS as an audio call — the system call UI, the
        // CallStyle notification icon and Bluetooth/car surfaces all show the
        // wrong kind, and another app taking over the call cannot know video
        // was involved.
        //
        // Both flags are set on purpose. CAPABILITY_SUPPORTS_VIDEO_CALLING says
        // "this account can do video at all"; CAPABILITY_VIDEO_CALLING says
        // "it can right now". A self-managed VoIP app that ships video has no
        // carrier gate between the two, so splitting them would only produce a
        // state the app can never be in.
        val phoneAccount = PhoneAccount.builder(handle, "Callkit Incoming In-App Call")
            .setCapabilities(
                PhoneAccount.CAPABILITY_SELF_MANAGED or
                    PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING or
                    PhoneAccount.CAPABILITY_VIDEO_CALLING,
            )
            .build()

        telecomManager.registerPhoneAccount(phoneAccount)
        Log.d(TAG, "PhoneAccount registered.")
    }

    fun unregisterPhoneAccount() {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val componentName = ComponentName(context, CallkitConnectionService::class.java)
        val handle = PhoneAccountHandle(componentName, ACCOUNT_ID)

        telecomManager.unregisterPhoneAccount(handle)
        Log.d(TAG, "PhoneAccount unregistered.")
    }

    fun getPhoneAccountHandle(): PhoneAccountHandle {
        return PhoneAccountHandle(
            ComponentName(context, CallkitConnectionService::class.java),
            ACCOUNT_ID
        )
    }
}
