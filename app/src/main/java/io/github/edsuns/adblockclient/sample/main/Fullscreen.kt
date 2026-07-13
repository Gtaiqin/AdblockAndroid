package io.github.edsuns.adblockclient.sample.main

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient.CustomViewCallback
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.lang.ref.WeakReference


/**
 * Created by Edsuns@qq.com on 2021/4/4.
 */
object Fullscreen {

    private var customView: WeakReference<View>? = null
    private var viewCallback: CustomViewCallback? = null

    fun onShowCustomView(context: Context, view: View, callback: CustomViewCallback) {
        if (customView?.get() != null) {
            callback.onCustomViewHidden()
            return
        }
        val activity = getActivity(context) ?: return
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        customView = WeakReference(view)
        viewCallback = callback
        setImmersiveMode(activity, true)
        view.setBackgroundColor(Color.BLACK)
        activity.addContentView(
            view, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun onHideCustomView() {
        val view = customView?.get() ?: return
        val activity = getActivity(view.context) ?: return
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setImmersiveMode(activity, false)
        val viewGroup = view.parent as ViewGroup
        viewGroup.removeView(view)
        viewCallback?.onCustomViewHidden()
        viewCallback = null
        customView = null
    }

    private fun getActivity(context: Context?): Activity? {
        if (context == null) return null
        if (context is Activity) return context
        return if (context is ContextWrapper) getActivity(context.baseContext) else null
    }

    private fun setImmersiveMode(activity: Activity, enable: Boolean) {
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        if (enable) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}