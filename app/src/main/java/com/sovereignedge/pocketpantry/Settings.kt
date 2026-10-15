package com.sovereignedge.pocketpantry

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SettingsSnapshot(
    val model: String,
    val effort: String,
    val currency: String,
    val dietaryNotes: String,
    val household: String,
    val activeStoreId: Long,
    val defaultLocationId: Long,
    val hasApiKey: Boolean,
    /** "system", "light" or "dark". */
    val themeMode: String,
    val connectorEnabled: Boolean,
    /** "vpn" (Tailscale/VPN address only) or "loopback" (USB via adb forward). */
    val connectorBind: String,
    val connectorPort: Int,
    val connectorAllowDeletes: Boolean,
)

/** Non-secret preferences, with a StateFlow so Compose screens recompose on change. */
class Settings(context: Context, private val secrets: SecretStore) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<SettingsSnapshot> = _state
    val current: SettingsSnapshot get() = _state.value

    fun setModel(value: String) = edit { putString(K_MODEL, value) }
    fun setEffort(value: String) = edit { putString(K_EFFORT, value) }
    fun setCurrency(value: String) = edit { putString(K_CURRENCY, value.trim().ifBlank { "$" }) }
    fun setDietaryNotes(value: String) = edit { putString(K_DIET, value) }
    fun setHousehold(value: String) = edit { putString(K_HOUSEHOLD, value) }
    fun setActiveStore(id: Long) = edit { putLong(K_STORE, id) }
    fun setDefaultLocation(id: Long) = edit { putLong(K_LOCATION, id) }
    fun setThemeMode(value: String) = edit { putString(K_THEME, value) }
    fun setConnectorEnabled(on: Boolean) = edit { putBoolean(K_CONN_ON, on) }
    fun setConnectorBind(value: String) = edit { putString(K_CONN_BIND, value) }
    fun setConnectorPort(value: Int) = edit { putInt(K_CONN_PORT, value) }
    fun setConnectorAllowDeletes(on: Boolean) = edit { putBoolean(K_CONN_DELETES, on) }

    fun setApiKey(key: String) {
        secrets.putApiKey(key)
        refresh()
    }

    fun clearApiKey() {
        secrets.clearApiKey()
        refresh()
    }

    fun apiKey(): String? = secrets.apiKey()

    fun wipe() {
        prefs.edit().clear().apply()
        secrets.clearApiKey()
        refresh()
    }

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        refresh()
    }

    private fun refresh() {
        _state.value = snapshot()
    }

    private fun snapshot() = SettingsSnapshot(
        model = prefs.getString(K_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
        effort = prefs.getString(K_EFFORT, "medium") ?: "medium",
        currency = prefs.getString(K_CURRENCY, "$") ?: "$",
        dietaryNotes = prefs.getString(K_DIET, "") ?: "",
        household = prefs.getString(K_HOUSEHOLD, "") ?: "",
        activeStoreId = prefs.getLong(K_STORE, 0L),
        defaultLocationId = prefs.getLong(K_LOCATION, 0L),
        hasApiKey = secrets.hasApiKey(),
        themeMode = prefs.getString(K_THEME, "system") ?: "system",
        connectorEnabled = prefs.getBoolean(K_CONN_ON, false),
        connectorBind = prefs.getString(K_CONN_BIND, "vpn") ?: "vpn",
        connectorPort = prefs.getInt(K_CONN_PORT, DEFAULT_CONNECTOR_PORT),
        connectorAllowDeletes = prefs.getBoolean(K_CONN_DELETES, false),
    )

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val MODELS = listOf(
            "claude-opus-5-5" to "Claude Opus 5.5 (recommended)",
            "claude-sonnet-5-5" to "Claude Sonnet 5.5 (faster, cheaper)",
            "claude-haiku-5-5" to "Claude Haiku 5.5 (cheapest)",
        )
        val EFFORTS = listOf("low", "medium", "high")
        val THEME_MODES = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
        private const val K_MODEL = "model"
        private const val K_EFFORT = "effort"
        private const val K_CURRENCY = "currency"
        private const val K_DIET = "dietary_notes"
        private const val K_HOUSEHOLD = "household"
        private const val K_STORE = "active_store"
        private const val K_LOCATION = "default_location"
        private const val K_THEME = "theme_mode"
        private const val K_CONN_ON = "connector_enabled"
        private const val K_CONN_BIND = "connector_bind"
        private const val K_CONN_PORT = "connector_port"
        private const val K_CONN_DELETES = "connector_allow_deletes"
        const val DEFAULT_CONNECTOR_PORT = 47634
    }
}
