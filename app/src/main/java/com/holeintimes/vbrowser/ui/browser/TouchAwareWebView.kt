package com.holeintimes.vbrowser.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView

/**
 * WebView that keeps vertical/horizontal scroll inside the page instead of letting
 * parent Compose containers or the navigation drawer steal the gesture.
 */
class TouchAwareWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> requestParentsDisallowIntercept(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> requestParentsDisallowIntercept(false)
        }
        return super.onTouchEvent(event)
    }

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        // At scroll edge, allow parent (e.g. drawer) to handle overscroll if needed.
        if (clampedX || clampedY) {
            requestParentsDisallowIntercept(false)
        }
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
    }

    private fun requestParentsDisallowIntercept(disallow: Boolean) {
        var parent = parent
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow)
            parent = parent.parent
        }
    }
}
