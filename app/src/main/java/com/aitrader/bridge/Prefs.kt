package com.aitrader.bridge

import android.content.Context
import android.content.SharedPreferences

/** Estado persistido do aparelho: pareamento, ativo e calibração. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("ai_trader_bridge", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", "") ?: ""
        set(value) {
            sp.edit().putString("base_url", value.trim().trimEnd('/')).apply()
        }

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(value) {
            sp.edit().putString("token", value).apply()
        }

    var deviceId: String
        get() = sp.getString("device_id", "") ?: ""
        set(value) {
            sp.edit().putString("device_id", value).apply()
        }

    var deviceName: String
        get() = sp.getString("device_name", "") ?: ""
        set(value) {
            sp.edit().putString("device_name", value).apply()
        }

    var asset: String
        get() = sp.getString("asset", "") ?: ""
        set(value) {
            sp.edit().putString("asset", value.trim()).apply()
        }

    var timeframe: String
        get() = sp.getString("timeframe", "M1") ?: "M1"
        set(value) {
            sp.edit().putString("timeframe", value).apply()
        }

    /** Sequência monotônica exigida pela ponte (409 se repetir). */
    var sequence: Long
        get() = sp.getLong("sequence", 0L)
        set(value) {
            sp.edit().putLong("sequence", value).apply()
        }

    var calibrationJson: String
        get() = sp.getString("calibration", "") ?: ""
        set(value) {
            sp.edit().putString("calibration", value).apply()
        }

    fun isPaired(): Boolean = token.isNotEmpty() && baseUrl.isNotEmpty()

    fun calibration(): Calibration? = Calibration.fromJson(calibrationJson)
}