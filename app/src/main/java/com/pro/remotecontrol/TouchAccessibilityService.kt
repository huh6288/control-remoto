package com.pro.remotecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent

/**
 * Convierte TouchCommand (coordenadas normalizadas 0f..1f) en gestos reales
 * sobre la pantalla de ESTE dispositivo. Solo puede activarse manualmente
 * en Ajustes > Accesibilidad — Android no permite hacerlo por código,
 * y eso es intencional: evita que una app active esto sin que el dueño
 * del teléfono lo sepa.
 */
class TouchAccessibilityService : AccessibilityService() {

    companion object {
        // La app guarda aquí la referencia viva para poder llamar dispatchTouch()
        // desde WebRtcClient sin necesidad de bindService.
        var instance: TouchAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun dispatchTouch(cmd: TouchCommand) {
        val metrics = resources.displayMetrics
        val x = cmd.x * metrics.widthPixels
        val y = cmd.y * metrics.heightPixels

        val path = Path().apply { moveTo(x, y) }
        val duration = if (cmd.action == "tap") 50L else 16L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
