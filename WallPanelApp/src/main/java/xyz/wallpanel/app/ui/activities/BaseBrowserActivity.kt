/*
 * Copyright (c) 2022 WallPanel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.wallpanel.app.ui.activities

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dagger.android.support.DaggerAppCompatActivity
import timber.log.Timber
import xyz.wallpanel.app.WallPanelDeviceAdminReceiver
import xyz.wallpanel.app.network.MQTTOptions
import xyz.wallpanel.app.network.WallPanelService
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_ALERT_MESSAGE
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_CLEAR_ALERT_MESSAGE
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_EVENT_SCREEN_TOUCH
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_SCREEN_BRIGHTNESS_CHANGE
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_SCREEN_WAKE
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_SCREEN_WAKE_OFF
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_SCREEN_WAKE_ON
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_SERVICE_STARTED
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_TOAST_MESSAGE
import xyz.wallpanel.app.persistence.Configuration
import xyz.wallpanel.app.utils.DialogUtils
import xyz.wallpanel.app.utils.ScreenUtils
import javax.inject.Inject


abstract class BaseBrowserActivity : DaggerAppCompatActivity() {

    @Inject
    lateinit var dialogUtils: DialogUtils

    @Inject
    lateinit var configuration: Configuration

    @Inject
    lateinit var mqttOptions: MQTTOptions

    @Inject
    lateinit var screenUtils: ScreenUtils

    var mOnScrollChangedListener: ViewTreeObserver.OnScrollChangedListener? = null
    var wallPanelService: Intent? = null
    private var decorView: View? = null
    private val inactivityHandler: Handler = Handler(Looper.getMainLooper())
    private val temporaryScreenWakeHandler = Handler(Looper.getMainLooper())
    private var userPresent: Boolean = false
    private var hasWakeScreen = false
    private var temporaryScreenWakeUntil = 0L
    private var kioskOwnerWarningLogged = false
    var displayProgress = true
    var zoomLevel = 1.0f

    // handler for received data from service
    private val mBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BROADCAST_ACTION_LOAD_URL == intent.action) {
                val url = intent.getStringExtra(BROADCAST_ACTION_LOAD_URL)
                url?.let {
                    loadWebViewUrl(url)
                    stopDisconnectTimer()
                }
            } else if (BROADCAST_ACTION_JS_EXEC == intent.action) {
                val js = intent.getStringExtra(BROADCAST_ACTION_JS_EXEC)
                js?.let {
                    stopDisconnectTimer()
                    evaluateJavascript(js)
                }
            } else if (BROADCAST_ACTION_CLEAR_BROWSER_CACHE == intent.action) {
                Timber.d("Clearing browser cache")
                clearCache()
            } else if (BROADCAST_ACTION_RELOAD_PAGE == intent.action) {
                Timber.d("Browser page reloading.")
                stopDisconnectTimer()
                reload()
            } else if (BROADCAST_ACTION_OPEN_SETTINGS == intent.action) {
                Timber.d("Browser open settings.")
                openSettings()
            } else if (BROADCAST_TOAST_MESSAGE == intent.action && !isFinishing) {
                val message = intent.getStringExtra(BROADCAST_TOAST_MESSAGE)
                stopDisconnectTimer()
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            } else if (BROADCAST_ALERT_MESSAGE == intent.action && !isFinishing) {
                val message = intent.getStringExtra(BROADCAST_ALERT_MESSAGE)
                stopDisconnectTimer()
                message?.let {
                    dialogUtils.showAlertDialog(this@BaseBrowserActivity, message)
                }
            } else if (BROADCAST_CLEAR_ALERT_MESSAGE == intent.action && !isFinishing) {
                dialogUtils.clearDialogs()
                if (hasWakeScreen.not()) {
                    resetInactivityTimer()
                    resetScreenBrightness(false)
                }
            } else if (BROADCAST_SCREEN_WAKE == intent.action && !isFinishing) {
                stopDisconnectTimer()
            } else if (BROADCAST_SCREEN_WAKE_ON == intent.action && !isFinishing) {
                hasWakeScreen = true
                resetScreenBrightness(false)
                clearInactivityTimer()
            } else if (BROADCAST_SCREEN_WAKE_OFF == intent.action && !isFinishing) {
                forceWakeScreenOff()
            } else if (BROADCAST_ACTION_RELOAD_PAGE == intent.action && !isFinishing) {
                hideScreenSaver()
            } else if (BROADCAST_SERVICE_STARTED == intent.action && !isFinishing) {
                //firstLoadUrl() // load the url after service started
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        displayProgress = configuration.appShowActivity
        zoomLevel = configuration.testZoomLevel

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        decorView = window.decorView

        lifecycle.addObserver(dialogUtils)

        handleWakeIntent(intent)

        onUserInteraction()

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
        intent.getStringExtra(EXTRA_LOAD_URL)?.let { loadWebViewUrl(it) }
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_TURN_SCREEN_ON, false) == true) {
            val keepAwake = intent.getBooleanExtra(EXTRA_KEEP_AWAKE, false)
            val wakeDuration = intent.getLongExtra(EXTRA_WAKE_DURATION, 0L)
            if (keepAwake) {
                hasWakeScreen = true
                clearInactivityTimer()
            }
            if (wakeDuration > 0L) {
                enableTemporaryScreenWake(wakeDuration)
            }
            resetScreenBrightness(false)
        }
    }

    private fun enableTemporaryScreenWake(duration: Long) {
        temporaryScreenWakeUntil = System.currentTimeMillis() + duration
        enforceScreenSleepPolicy()
        temporaryScreenWakeHandler.removeCallbacksAndMessages(null)
        temporaryScreenWakeHandler.postDelayed({
            temporaryScreenWakeUntil = 0L
            enforceScreenSleepPolicy()
        }, duration)
    }

    private fun clearWakeScreenFlags() {
        enforceScreenSleepPolicy()
    }

    private fun forceWakeScreenOff() {
        hasWakeScreen = false
        clearWakeScreenFlags()
        clearInactivityTimer()
        userPresent = false
        resetScreenBrightness(true)
        showScreenSaver()

        try {
            val admin = deviceAdminComponent()
            val policyManager = devicePolicyManager()
            if (policyManager.isAdminActive(admin)) {
                policyManager.lockNow()
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to lock screen after wakeTime")
        }
    }

    override fun onResume() {
        super.onResume()
        enforceScreenSleepPolicy()
        val filter = IntentFilter()
        filter.addAction(BROADCAST_ACTION_LOAD_URL)
        filter.addAction(BROADCAST_ACTION_JS_EXEC)
        filter.addAction(BROADCAST_ACTION_CLEAR_BROWSER_CACHE)
        filter.addAction(BROADCAST_ACTION_RELOAD_PAGE)
        filter.addAction(BROADCAST_ACTION_OPEN_SETTINGS)
        filter.addAction(BROADCAST_SCREEN_BRIGHTNESS_CHANGE)
        filter.addAction(BROADCAST_CLEAR_ALERT_MESSAGE)
        filter.addAction(BROADCAST_ALERT_MESSAGE)
        filter.addAction(BROADCAST_TOAST_MESSAGE)
        filter.addAction(BROADCAST_SCREEN_WAKE)
        filter.addAction(BROADCAST_SCREEN_WAKE_ON)
        filter.addAction(BROADCAST_SCREEN_WAKE_OFF)
        filter.addAction(BROADCAST_SERVICE_STARTED)
        val bm = LocalBroadcastManager.getInstance(this)
        bm.registerReceiver(mBroadcastReceiver, filter)
        dismissKeyguardIfNeeded()
        applyKioskMode()
        if (hasWakeScreen) {
            clearInactivityTimer()
        } else {
            resetInactivityTimer()
        }
    }

    override fun onPause() {
        super.onPause()
        val bm = LocalBroadcastManager.getInstance(this)
        bm.unregisterReceiver(mBroadcastReceiver)
    }

    override fun onStart() {
        super.onStart()
        if (configuration.hardwareAccelerated && Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
        }
        enforceScreenSleepPolicy()
        wallPanelService = Intent(this, WallPanelService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(wallPanelService)
        } else {
            startService(wallPanelService)
        }
        dismissKeyguardIfNeeded()
        resetScreenBrightness(false)
        applyKioskMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityHandler.removeCallbacks(inactivityCallback)
        window.clearFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onUserInteraction() {
        onWindowFocusChanged(true)
        dismissKeyguardIfNeeded()
        Timber.d("onUserInteraction")
        if (!userPresent) {
            userPresent = true
            resetScreenBrightness(false)
            val intent = Intent(BROADCAST_EVENT_SCREEN_TOUCH)
            intent.putExtra(BROADCAST_EVENT_SCREEN_TOUCH, true)
            val bm = LocalBroadcastManager.getInstance(applicationContext)
            bm.sendBroadcast(intent)
        }
        if (hasWakeScreen.not()) {
            resetInactivityTimer()
        }
    }

    fun setDarkTheme() {
        val nightMode = AppCompatDelegate.getDefaultNightMode()
        if (nightMode == AppCompatDelegate.MODE_NIGHT_NO || nightMode == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    fun setLightTheme() {
        val nightMode = AppCompatDelegate.getDefaultNightMode()
        if (nightMode == AppCompatDelegate.MODE_NIGHT_YES || nightMode == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    private val inactivityCallback = Runnable {
        Timber.d("inactivityCallback")
        dialogUtils.clearDialogs()
        userPresent = false
        if (!configuration.appPreventSleep) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            decorView?.keepScreenOn = false
        }
        showScreenSaver()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applySystemUiVisibility()
            applyKioskMode()
        }
        enforceScreenSleepPolicy()
    }

    override fun onWindowAttributesChanged(attributes: WindowManager.LayoutParams) {
        if (!shouldKeepScreenOn()) {
            attributes.flags = attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        super.onWindowAttributesChanged(attributes)
    }

    protected open fun enforceScreenSleepPolicy() {
        if (shouldKeepScreenOn()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            decorView?.keepScreenOn = true
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            decorView?.keepScreenOn = false
        }
    }

    protected fun shouldKeepScreenOn(): Boolean {
        return ::configuration.isInitialized
                && (configuration.appPreventSleep
                || hasWakeScreen
                || System.currentTimeMillis() < temporaryScreenWakeUntil)
    }

    private fun dismissKeyguardIfNeeded() {
        try {
            val keyguardManager = getSystemService(KeyguardManager::class.java)
            if (keyguardManager?.isKeyguardLocked == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    keyguardManager.requestDismissKeyguard(this, null)
                } else {
                    @Suppress("DEPRECATION")
                    window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to dismiss keyguard automatically")
        }
    }

    private fun applySystemUiVisibility() {
        val shouldHideBars = configuration.fullScreen || configuration.kioskMode
        val visibility = if (shouldHideBars) {
            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_VISIBLE
        }
        decorView?.systemUiVisibility = visibility

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (shouldHideBars) {
                controller?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            } else {
                controller?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        }
    }

    private fun applyKioskMode() {
        applySystemUiVisibility()
        if (!configuration.kioskMode) {
            restoreKioskPolicies()
            return
        }
        if (isInLockTaskMode()) {
            return
        }

        if (!isDeviceOwner()) {
            if (!kioskOwnerWarningLogged) {
                Timber.i("Using fallback kiosk mode because WallPanel is not Device Owner")
                kioskOwnerWarningLogged = true
            }
            return
        }

        try {
            val policyManager = devicePolicyManager()
            policyManager.setLockTaskPackages(deviceAdminComponent(), arrayOf(packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                policyManager.setStatusBarDisabled(deviceAdminComponent(), true)
                policyManager.setKeyguardDisabled(deviceAdminComponent(), true)
            }
            startLockTask()
            Timber.i("Kiosk LockTask mode started")
        } catch (e: Exception) {
            Timber.e(e, "Unable to start kiosk LockTask mode")
        }
    }

    protected fun stopKioskLockTaskForAdmin() {
        try {
            if (isInLockTaskMode()) {
                stopLockTask()
                Timber.i("Kiosk LockTask mode stopped for admin access")
            }
            restoreKioskPolicies()
        } catch (e: Exception) {
            Timber.e(e, "Unable to stop kiosk LockTask mode")
        }
    }

    private fun restoreKioskPolicies() {
        if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val policyManager = devicePolicyManager()
            policyManager.setStatusBarDisabled(deviceAdminComponent(), false)
            policyManager.setKeyguardDisabled(deviceAdminComponent(), false)
        }
    }

    protected fun isKioskModeActive(): Boolean {
        return configuration.kioskMode && isInLockTaskMode()
    }

    private fun isInLockTaskMode(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun isDeviceOwner(): Boolean {
        return devicePolicyManager().isDeviceOwnerApp(packageName)
    }

    private fun devicePolicyManager(): DevicePolicyManager {
        return getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private fun deviceAdminComponent(): ComponentName {
        return ComponentName(this, WallPanelDeviceAdminReceiver::class.java)
    }

    internal fun resetScreen() {
        Timber.d("resetScreen Called")
        val intent = Intent(WallPanelService.BROADCAST_EVENT_SCREEN_TOUCH)
        intent.putExtra(WallPanelService.BROADCAST_EVENT_SCREEN_TOUCH, true)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    fun pageLoadComplete(url: String) {
        Timber.d("pageLoadComplete currentUrl $url")
        val intent = Intent(WallPanelService.BROADCAST_EVENT_URL_CHANGE)
        intent.putExtra(WallPanelService.BROADCAST_EVENT_URL_CHANGE, url)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
        complete()
    }

    private fun resetInactivityTimer() {
        hideScreenSaver()
        inactivityHandler.removeCallbacks(inactivityCallback)
        inactivityHandler.postDelayed(inactivityCallback, configuration.inactivityTime)
    }

    private fun clearInactivityTimer() {
        hideScreenSaver()
        inactivityHandler.removeCallbacks(inactivityCallback)
    }

    fun stopDisconnectTimer() {
        Timber.d("stopDisconnectTimer")
        if (userPresent.not()) {
            userPresent = true
            resetScreenBrightness(false)
        }
        if (hasWakeScreen.not()) {
            resetInactivityTimer()
        }
    }

    open fun hideScreenSaver() {
        Timber.d("hideScreenSaver")
        val isScreenSaver = dialogUtils.hideScreenSaverDialog()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (isScreenSaver) {
            resetScreenBrightness(false)
        }
    }

    /**
     * Show the screen saver only if the alarm isn't triggered. This shouldn't be an issue
     * with the alarm disabled because the disable time will be longer than this.
     */
    open fun showScreenSaver() {
        if (configuration.hasDimScreenSaver) {
            inactivityHandler.removeCallbacks(inactivityCallback)
            resetScreenBrightness(true)
        } else if ((configuration.hasClockScreenSaver
                    || configuration.webScreenSaver
                    || configuration.hasScreenSaverWallpaper
                    || configuration.hasDimScreenSaver)
            && !isFinishing
        ) {
            inactivityHandler.removeCallbacks(inactivityCallback)
            dialogUtils.showScreenSaver(
                this@BaseBrowserActivity,
                {
                    dialogUtils.hideScreenSaverDialog()
                    resetScreenBrightness(false)
                    resetInactivityTimer()
                },
                configuration.webScreenSaver,
                configuration.webScreenSaverUrl,
                configuration.hasScreenSaverWallpaper,
                configuration.hasClockScreenSaver,
                configuration.imageRotation.toLong(),
                configuration.appPreventSleep
            )
            resetScreenBrightness(true)
        }
    }

    open fun resetScreenBrightness(screenSaver: Boolean = false) {
        screenUtils.resetScreenBrightness(screenSaver)
    }

    protected abstract fun configureWebSettings(userAgent: String)
    protected abstract fun loadWebViewUrl(url: String)
    protected abstract fun evaluateJavascript(js: String)
    protected abstract fun clearCache()
    protected abstract fun reload()
    protected abstract fun complete()
    protected abstract fun openSettings()

    companion object {
        const val BROADCAST_ACTION_LOAD_URL = "BROADCAST_ACTION_LOAD_URL"
        const val BROADCAST_ACTION_JS_EXEC = "BROADCAST_ACTION_JS_EXEC"
        const val BROADCAST_ACTION_CLEAR_BROWSER_CACHE = "BROADCAST_ACTION_CLEAR_BROWSER_CACHE"
        const val BROADCAST_ACTION_RELOAD_PAGE = "BROADCAST_ACTION_RELOAD_PAGE"
        const val BROADCAST_ACTION_OPEN_SETTINGS = "BROADCAST_ACTION_OPEN_SETTINGS"
        const val EXTRA_TURN_SCREEN_ON = "EXTRA_TURN_SCREEN_ON"
        const val EXTRA_KEEP_AWAKE = "EXTRA_KEEP_AWAKE"
        const val EXTRA_WAKE_DURATION = "EXTRA_WAKE_DURATION"
        const val EXTRA_LOAD_URL = "EXTRA_LOAD_URL"
        const val REQUEST_CODE_PERMISSION_AUDIO = 12
        const val REQUEST_CODE_PERMISSION_CAMERA = 13
    }
}
