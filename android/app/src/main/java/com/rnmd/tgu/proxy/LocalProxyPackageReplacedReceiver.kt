package com.rnmd.tgu.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restores a user-enabled local proxy after an in-place APK update.
 * The persisted MTProto secret and Android Keystore key are retained by an
 * in-place package replacement, so Telegram's existing local proxy profile
 * remains valid and does not need to be applied again.
 */
class LocalProxyPackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val settings = LocalProxySettingsStore(context.applicationContext)
        if (!settings.shouldRestoreAfterUpdate()) return

        val serviceIntent = Intent(context, LocalProxyService::class.java)
            .setAction(LocalProxyService.ACTION_START)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "Restoring local proxy after app update.")
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not restore local proxy after app update: ${error.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "TGU-PackageUpdate"
    }
}
