package dev.foldcode.ide

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import java.util.WeakHashMap

/**
 * Gives a desktop wheel event to the native pane underneath the pointer.
 *
 * Android normally sends generic motion to the currently focused view. That is
 * correct for keyboard-like devices, but on DeX it means a WebView cannot scroll
 * after the editor was clicked (and vice versa) until the user clicks again.
 * Registered views are weakly held and selected using screen coordinates. The
 * event is dispatched directly because changing focus before Activity's normal
 * dispatch is not sufficient on DeX: the first wheel event can still reach the
 * previously focused native view.
 */
internal object DesktopPointerFocusRouter {
    private val targets = WeakHashMap<View, Unit>()

    fun register(view: View) {
        targets[view] = Unit
    }

    fun unregister(view: View) {
        targets.remove(view)
    }

    /** Clear native AndroidView focus before Compose removes a desktop pane. */
    fun clearFocusedTarget() {
        targets.keys.toList().firstOrNull { it.isAttachedToWindow && it.hasFocus() }?.clearFocus()
    }

    fun routeScrollToTarget(event: MotionEvent): Boolean {
        if (
            event.actionMasked != MotionEvent.ACTION_SCROLL ||
            !event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        ) {
            return false
        }

        val rawX = event.rawX
        val rawY = event.rawY
        val location = IntArray(2)
        var bestTarget: View? = null
        var bestArea = Long.MAX_VALUE

        // A copy also makes iteration safe if requesting focus causes a detached
        // Compose AndroidView to unregister itself synchronously.
        targets.keys.toList().forEach { view ->
            if (!view.isAttachedToWindow || !view.isShown || view.width <= 0 || view.height <= 0) {
                return@forEach
            }
            view.getLocationOnScreen(location)
            val left = location[0].toFloat()
            val top = location[1].toFloat()
            if (rawX < left || rawX >= left + view.width || rawY < top || rawY >= top + view.height) {
                return@forEach
            }
            val area = view.width.toLong() * view.height.toLong()
            if (area < bestArea) {
                bestArea = area
                bestTarget = view
            }
        }

        val target = bestTarget ?: return false
        target.takeUnless(View::hasFocus)?.requestFocus()

        target.getLocationOnScreen(location)
        val routed = MotionEvent.obtain(event)
        routed.setLocation(rawX - location[0], rawY - location[1])
        try {
            target.dispatchGenericMotionEvent(routed)
        } finally {
            routed.recycle()
        }
        return true
    }
}
