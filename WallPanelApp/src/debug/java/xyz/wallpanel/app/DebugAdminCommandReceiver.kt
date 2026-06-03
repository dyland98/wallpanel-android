package xyz.wallpanel.app

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import timber.log.Timber

class DebugAdminCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMOVE_DEVICE_ADMIN) {
            return
        }

        val policyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, WallPanelDeviceAdminReceiver::class.java)
        if (policyManager.isAdminActive(admin)) {
            policyManager.removeActiveAdmin(admin)
            Timber.i("Debug command removed WallPanel active admin")
        }
    }

    companion object {
        const val ACTION_REMOVE_DEVICE_ADMIN = "xyz.wallpanel.app.DEBUG_REMOVE_DEVICE_ADMIN"
    }
}
