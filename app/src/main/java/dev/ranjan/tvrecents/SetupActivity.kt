package dev.ranjan.tvrecents

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView

/** Shows setup instructions, service status and warnings about other key filters. */
class SetupActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var keyFilterWarning: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        status = findViewById(R.id.service_status)
        keyFilterWarning = findViewById(R.id.key_filter_warning)
        findViewById<Button>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        status.setText(if (isServiceEnabled()) R.string.service_enabled else R.string.service_not_enabled)
        val warnings = keyFilterWarnings()
        keyFilterWarning.text = warnings
        keyFilterWarning.visibility = if (warnings.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun isServiceEnabled(): Boolean {
        val service = ComponentName(this, RecentsService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return enabled.orEmpty().splitToSequence(':').any { ComponentName.unflattenFromString(it) == service }
    }

    private fun keyFilterWarnings(): String {
        val manager = getSystemService(AccessibilityManager::class.java)
        val ownService = ComponentName(this, RecentsService::class.java)
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.filter { info ->
            val service = info.resolveInfo.serviceInfo
            ComponentName(service.packageName, service.name) != ownService &&
                info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
        }.joinToString("\n") { info ->
            getString(R.string.key_filter_warning, info.resolveInfo.loadLabel(packageManager))
        }
    }
}
