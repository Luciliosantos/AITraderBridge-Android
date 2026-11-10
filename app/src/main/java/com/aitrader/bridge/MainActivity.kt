package com.aitrader.bridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val apiBase = "https://aitrader.lsnet.shop/broker-api"
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var brokerStatus: TextView
    private lateinit var iqStatus: TextView
    private lateinit var assetSpinner: Spinner
    private lateinit var brokerEmail: EditText
    private lateinit var brokerPassword: EditText
    private lateinit var iqEmail: EditText
    private lateinit var iqPassword: EditText
    private lateinit var stakeInput: EditText
    private lateinit var lossInput: EditText
    private lateinit var profitInput: EditText
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var overlay: FloatingOverlay? = null
    private var botActive = false
    private var assets: List<Asset> = emptyList()
    private var lastDirection = "WAIT"
    private var lastConfidence = 0.0

    private data class Asset(val id: Int, val name: String, val market: String) {
        override fun toString() = name
    }

    private val monitor = object : Runnable {
        override fun run() {
            if (prefs.token.isNotBlank() && botActive) {
                io.execute {
                    val response = BridgeApi.get(apiBase, "/api/v1/trading/scan-logs", prefs.token)
                    val json = response.json()
                    val robot = json.optJSONObject("robot") ?: JSONObject()
                    val events = json.optJSONArray("items") ?: JSONArray()
                    var direction = "WAIT"
                    var confidence = 0.0
                    var meta = "Monitorando ${robot.optInt("scannedAssets")} ativo(s)"
                    var reason = robot.optString("lastError")
                    for (i in 0 until events.length()) {
                        val row = events.optJSONObject(i) ?: continue
                        if (row.optString("event") == "trading_auto_analysis") {
                            val detail = row.optJSONObject("detail") ?: JSONObject()
                            direction = detail.optString("direction", "WAIT")
                            confidence = detail.optDouble("confidence", 0.0)
                            meta = detail.optString("symbol", "IQ Option") + " · " + robot.optString("timeframe", "M1")
                            reason = detail.optString("strategy", "Mestre das Confluências")
                            break
                        }
                    }
                    main.post {
                        if (robot.optBoolean("active")) {
                            lastDirection = direction; lastConfidence = confidence
                            overlay?.update(direction, confidence, reason.ifBlank { "Aguardando confirmação" }, meta)
                            status.text = "BOT ATIVO · ${robot.optString("environment", "DEMO").uppercase()}\nAtivo: ${robot.optString("lastAsset", "aguardando")} · Leituras: ${robot.optInt("scannedAssets")}\n${robot.optString("lastError", "Escaneando candles em tempo real")}" 
                        } else if (botActive) {
                            botActive = false; removeOverlay()
                            status.text = "Bot parado pela API. ${robot.optString("lastError", "Confira a conexão e os ativos selecionados.")}"
                        }
                    }
                }
            }
            if (botActive) main.postDelayed(this, 4000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        status = findViewById(R.id.textStatus)
        brokerStatus = findViewById(R.id.brokerStatus)
        iqStatus = findViewById(R.id.iqStatus)
        assetSpinner = findViewById(R.id.assetSpinner)
        brokerEmail = findViewById(R.id.brokerEmail)
        brokerPassword = findViewById(R.id.brokerPassword)
        iqEmail = findViewById(R.id.iqEmail)
        iqPassword = findViewById(R.id.iqPassword)
        stakeInput = findViewById(R.id.stakeInput)
        lossInput = findViewById(R.id.lossInput)
        profitInput = findViewById(R.id.profitInput)
        findViewById<RadioGroup>(R.id.timeframeGroup).check(R.id.tfM1)

        findViewById<Button>(R.id.buttonLogin).setOnClickListener { loginBroker() }
        findViewById<Button>(R.id.buttonConnectIq).setOnClickListener { connectIq() }
        findViewById<Button>(R.id.buttonStart).setOnClickListener { startBot() }
        findViewById<Button>(R.id.buttonStop).setOnClickListener { stopBot() }
        if (prefs.token.isNotBlank()) {
            brokerStatus.text = "Sessão LS Broker salva neste aparelho"
            refreshIqStatus()
        }
        status.text = "Entre no LS Broker e conecte sua IQ Option para começar."
    }

    private fun loginBroker() {
        val email = brokerEmail.text.toString().trim()
        val password = brokerPassword.text.toString()
        if (email.isBlank() || password.isBlank()) { toast("Informe e-mail e senha do LS Broker."); return }
        status.text = "Autenticando no LS Broker…"
        io.execute {
            val response = BridgeApi.post(apiBase, "/api/v1/auth/login", null, JSONObject().put("email", email).put("password", password))
            val json = response.json()
            main.post {
                if (response.status in 200..299 && json.optString("token").isNotBlank()) {
                    prefs.token = json.optString("token")
                    brokerStatus.text = "Conectado ao LS Broker"
                    brokerPassword.text.clear()
                    status.text = "Login confirmado. Agora conecte a conta IQ Option."
                    toast("Login realizado com sucesso.")
                } else status.text = "Falha no login: ${response.errorMessage()}"
            }
        }
    }

    private fun connectIq() {
        if (prefs.token.isBlank()) { toast("Entre primeiro no LS Broker."); return }
        val email = iqEmail.text.toString().trim()
        val password = iqPassword.text.toString()
        if (email.isBlank() || password.isBlank()) { toast("Informe e-mail e senha da IQ Option."); return }
        status.text = "Conectando à IQ Option…"
        io.execute {
            val response = BridgeApi.post(apiBase, "/api/v1/connectors/iq-option/connect", prefs.token,
                JSONObject().put("email", email).put("password", password))
            val json = response.json()
            main.post {
                if (response.status in 200..299 && json.optString("status") == "online") {
                    iqPassword.text.clear()
                    iqStatus.text = "IQ Option conectada · DEMO"
                    status.text = "Conexão confirmada. Carregando ativos disponíveis…"
                    loadAssets()
                    toast("IQ Option conectada.")
                } else {
                    iqStatus.text = "IQ Option desconectada"
                    status.text = "Falha na conexão: ${response.errorMessage()}"
                }
            }
        }
    }

    private fun refreshIqStatus() {
        if (prefs.token.isBlank()) return
        io.execute {
            val r = BridgeApi.get(apiBase, "/api/v1/connectors/iq-option/status", prefs.token)
            val j = r.json()
            main.post {
                val ready = j.optBoolean("ready")
                iqStatus.text = if (ready) "IQ Option conectada · ${j.optString("environment", "DEMO").uppercase()}" else "IQ Option desconectada"
                if (ready && assets.isEmpty()) loadAssets()
            }
        }
    }

    private fun loadAssets() {
        io.execute {
            val response = BridgeApi.get(apiBase, "/api/v1/connectors/iq-option/assets", prefs.token)
            val json = response.json()
            val list = mutableListOf<Asset>()
            for (key in listOf("normal", "otc")) {
                val rows = json.optJSONArray(key) ?: continue
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val id = row.optInt("id")
                    val name = row.optString("name")
                    if (id > 0 && name.isNotBlank()) list.add(Asset(id, name, row.optString("market", key)))
                }
            }
            main.post {
                if (response.status in 200..299 && list.isNotEmpty()) {
                    assets = list
                    assetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, assets)
                    status.text = "${assets.size} ativos carregados. Escolha o ativo e configure os limites."
                } else status.text = "Não foi possível carregar os ativos: ${response.errorMessage()}"
            }
        }
    }

    private fun timeframe(): String = when (findViewById<RadioGroup>(R.id.timeframeGroup).checkedRadioButtonId) {
        R.id.tfM5 -> "M5"; R.id.tfM15 -> "M15"; else -> "M1"
    }

    private fun selectedPatterns(): JSONObject {
        val keys = listOf(
            R.id.patternEngulf to listOf("BULLISH_ENGULFING", "BEARISH_ENGULFING"),
            R.id.patternMorning to listOf("MORNING_STAR"), R.id.patternEvening to listOf("EVENING_STAR"),
            R.id.patternSoldiers to listOf("THREE_WHITE_SOLDIERS"), R.id.patternCrows to listOf("THREE_BLACK_CROWS"),
            R.id.patternHammer to listOf("HAMMER", "INVERTED_HAMMER"), R.id.patternStar to listOf("SHOOTING_STAR", "HANGING_MAN"),
            R.id.patternHarami to listOf("BULLISH_HARAMI", "BEARISH_HARAMI"), R.id.patternPiercing to listOf("PIERCING_LINE"),
            R.id.patternCloud to listOf("DARK_CLOUD_COVER")
        )
        val obj = JSONObject()
        keys.forEach { (id, names) -> names.forEach { obj.put(it, findViewById<CheckBox>(id).isChecked) } }
        obj.put("DOJI", findViewById<CheckBox>(R.id.patternDoji).isChecked)
        return obj
    }

    private fun startBot() {
        if (prefs.token.isBlank()) { toast("Entre primeiro no LS Broker."); return }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            toast("Autorize o painel flutuante e toque em Iniciar bot novamente.")
            return
        }
        if (assets.isEmpty()) { toast("Conecte a IQ Option e carregue os ativos primeiro."); return }
        val asset = assets.getOrNull(assetSpinner.selectedItemPosition) ?: assets.first()
        val stake = stakeInput.text.toString().replace(',', '.').toDoubleOrNull()
        val loss = lossInput.text.toString().replace(',', '.').toDoubleOrNull()
        val profit = profitInput.text.toString().replace(',', '.').toDoubleOrNull()
        if (stake == null || loss == null || profit == null || stake < 1 || stake > 10 || loss < 1 || loss > 50 || profit < 1 || profit > 500) {
            toast("Na configuração atual da API DEMO: entrada R$1–10, perda R$1–50 e ganho R$1–500."); return
        }
        val selectedTimeframe = timeframe()
        val patterns = selectedPatterns()
        val strategyConfig = JSONObject().put("MESTRE_CONFLUENCIAS", true)
            .put("EMA_9_21", findViewById<CheckBox>(R.id.strategyEma).isChecked)
            .put("RSI_14", findViewById<CheckBox>(R.id.strategyRsi).isChecked)
            .put("MACD", findViewById<CheckBox>(R.id.strategyMacd).isChecked)
            .put("SUPPORT_RESISTANCE", findViewById<CheckBox>(R.id.strategySr).isChecked)
            .put("CANDLE_PATTERNS", findViewById<CheckBox>(R.id.strategyCandle).isChecked)
        if (!listOf(R.id.strategyEma,R.id.strategyRsi,R.id.strategyMacd,R.id.strategySr,R.id.strategyCandle).any { findViewById<CheckBox>(it).isChecked }) { toast("Ative pelo menos uma estratégia."); return }
        status.text = "Salvando configurações e validando o mercado…"
        io.execute {
            val config = JSONObject().put("environment", "demo").put("timeframe", selectedTimeframe).put("stake", stake)
                .put("maxDailyLoss", loss).put("maxDailyProfit", profit).put("minConfidence", 0.70)
                .put("strategies", strategyConfig)
                .put("candlePatterns", patterns)
                .put("enabledAssets", JSONArray().put(JSONObject().put("id", asset.id).put("name", asset.name).put("market", asset.market)))
            val saved = BridgeApi.put(apiBase, "/api/v1/trading/config", prefs.token, config)
            if (saved.status !in 200..299) { main.post { status.text = "Configuração rejeitada: ${saved.errorMessage()}" }; return@execute }
            val started = BridgeApi.post(apiBase, "/api/v1/trading/auto/start", prefs.token, JSONObject())
            main.post {
                if (started.status in 200..299 && started.json().optBoolean("autoEnabled")) {
                    botActive = true
                    showOverlay()
                    overlay?.update("WAIT", 0.0, "Aguardando candle fechado", "${asset.name} · ${timeframe()}")
                    status.text = "BOT INICIADO · DEMO\nEscaneando ${asset.name} em ${timeframe()}. O painel flutuante está ativo."
                    main.removeCallbacks(monitor); main.postDelayed(monitor, 1500L)
                } else status.text = "Não foi possível iniciar: ${started.errorMessage()}"
            }
        }
    }

    private fun stopBot() {
        if (prefs.token.isBlank()) { botActive = false; removeOverlay(); return }
        io.execute {
            val response = BridgeApi.post(apiBase, "/api/v1/trading/auto/stop", prefs.token, JSONObject())
            main.post {
                botActive = false; main.removeCallbacks(monitor); removeOverlay()
                status.text = if (response.status in 200..299) "Bot parado. Nenhuma nova ordem será iniciada." else "Falha ao parar: ${response.errorMessage()}"
            }
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        overlay = FloatingOverlay(this, onTimeframe = { tf ->
            val id = when (tf) { "M5" -> R.id.tfM5; "M15" -> R.id.tfM15; else -> R.id.tfM1 }
            findViewById<RadioGroup>(R.id.timeframeGroup).check(id)
            toast("Para aplicar o novo período, pare e inicie o bot novamente.")
        }, onClose = { stopBot() })
        overlay?.show()
    }

    private fun removeOverlay() { overlay?.remove(); overlay = null }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        if (::iqStatus.isInitialized && prefs.token.isNotBlank()) refreshIqStatus()
    }

    @Deprecated("Deprecated in Android API; retained for the app's background trading flow")
    override fun onBackPressed() {
        if (botActive) {
            moveTaskToBack(true)
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        main.removeCallbacks(monitor)
        removeOverlay()
        io.shutdown()
        super.onDestroy()
    }
}
