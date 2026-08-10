package dev.foldcode.ide

import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView

/**
 * Android WebView only handles desktop wheel scrolling reliably while focused.
 * DeX routes the wheel to the previously focused native pane, so focus must
 * follow pointer entry before the first wheel event is emitted.
 */
internal class WheelFocusWebView(context: Context) : WebView(context) {
    /** URL requested by the workspace model, distinct from in-page navigation. */
    var workspaceUrl: String? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DesktopPointerFocusRouter.register(this)
    }

    override fun onDetachedFromWindow() {
        DesktopPointerFocusRouter.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (
            (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                event.actionMasked == MotionEvent.ACTION_SCROLL) &&
            !hasFocus()
        ) {
            requestFocus()
        }
        return super.onGenericMotionEvent(event)
    }
}
