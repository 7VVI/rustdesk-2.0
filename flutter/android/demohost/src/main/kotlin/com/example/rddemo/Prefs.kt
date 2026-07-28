package com.example.rddemo

import android.content.Context

/**
 * Tiny SharedPreferences-backed store for:
 *  - the device (机号) list shown on the home screen
 *  - the self-hosted server config (idServer / relayServer / key)
 */
object Prefs {
    private const val FILE = "rddemo_prefs"
    private const val KEY_DEVICES = "devices"
    private const val KEY_ID_SERVER = "idServer"
    private const val KEY_RELAY = "relayServer"
    private const val KEY_SERVER_KEY = "serverKey"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- device list ---
    fun getDevices(ctx: Context): MutableList<String> {
        val raw = sp(ctx).getString(KEY_DEVICES, "") ?: ""
        return if (raw.isBlank()) mutableListOf()
        else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    }

    private fun saveDevices(ctx: Context, list: List<String>) {
        sp(ctx).edit().putString(KEY_DEVICES, list.joinToString(",")).apply()
    }

    /** @return false if the id already exists. */
    fun addDevice(ctx: Context, id: String): Boolean {
        val list = getDevices(ctx)
        if (list.contains(id)) return false
        list.add(id)
        saveDevices(ctx, list)
        return true
    }

    fun removeDevice(ctx: Context, id: String) {
        val list = getDevices(ctx)
        list.remove(id)
        saveDevices(ctx, list)
    }

    // --- server config ---
    // Built-in defaults (pre-populated on first launch).
    const val DEFAULT_ID_SERVER = "control.zhdgps.com:64981"
    const val DEFAULT_RELAY = "control.zhdgps.com:64982"
    const val DEFAULT_KEY = "yvs2RoiYpIFdB8rrLe5GPaN3uJH6sJbiS9wbLdIevm4="

    /**
     * Pre-populate the server config with built-in defaults if the user has
     * not set anything yet. Called once on app launch.
     */
    fun ensureDefaults(ctx: Context) {
        val prefs = sp(ctx)
        if (prefs.getString(KEY_ID_SERVER, null) == null) {
            prefs.edit()
                .putString(KEY_ID_SERVER, DEFAULT_ID_SERVER)
                .putString(KEY_RELAY, DEFAULT_RELAY)
                .putString(KEY_SERVER_KEY, DEFAULT_KEY)
                .apply()
        }
    }

    fun getIdServer(ctx: Context) = sp(ctx).getString(KEY_ID_SERVER, DEFAULT_ID_SERVER) ?: DEFAULT_ID_SERVER
    fun getRelayServer(ctx: Context) = sp(ctx).getString(KEY_RELAY, DEFAULT_RELAY) ?: DEFAULT_RELAY
    fun getServerKey(ctx: Context) = sp(ctx).getString(KEY_SERVER_KEY, DEFAULT_KEY) ?: DEFAULT_KEY

    fun saveServer(ctx: Context, idServer: String, relay: String, key: String) {
        sp(ctx).edit()
            .putString(KEY_ID_SERVER, idServer)
            .putString(KEY_RELAY, relay)
            .putString(KEY_SERVER_KEY, key)
            .apply()
    }
}
