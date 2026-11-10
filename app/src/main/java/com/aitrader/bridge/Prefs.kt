package com.aitrader.bridge

import android.content.Context

/** Persiste a sessão do LS Broker e o último ativo escolhido neste aparelho. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("ai_trader_bridge_iq_v2", Context.MODE_PRIVATE)
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(value) { sp.edit().putString("token", value).apply() }
    var selectedAssetId: Int
        get() = sp.getInt("selected_asset_id", 0)
        set(value) { sp.edit().putInt("selected_asset_id", value).apply() }
}
