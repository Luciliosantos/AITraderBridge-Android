package com.aitrader.bridge

import android.content.Context

/** Guarda somente a sessão LS Broker no dispositivo. Senhas da corretora não são persistidas. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("ai_trader_bridge_iq_v2", Context.MODE_PRIVATE)
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(value) { sp.edit().putString("token", value).apply() }
}
