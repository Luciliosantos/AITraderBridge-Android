package com.aitrader.bridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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
    private lateinit var brokerLoginPanel: LinearLayout
    private lateinit var brokerSessionPanel: LinearLayout
    private lateinit var brokerSessionStatus: TextView
    private lateinit var brokerEmail: EditText
    private lateinit var brokerPassword: EditText
    private lateinit var iqStatus: TextView
    private lateinit var assetStatus: TextView
    private lateinit var assetSpinner: Spinner
    private lateinit var iqEmail: EditText
    private lateinit var iqPassword: EditText
    private lateinit var stakeInput: EditText
    private lateinit var lossInput: EditText
    private lateinit var profitInput: EditText
    private lateinit var buttonLogin: Button
    private lateinit var buttonConnectIq: Button
    private lateinit var buttonStart: Button
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var overlay: FloatingOverlay? = null
    private var botActive = false
    private var brokerSessionValidated = false
    private var iqConnected = false
    private var iqStatusLoading = false
    private var assetsLoading = false
    private var assets: List<Asset> = emptyList()

    private data class Asset(val id: Int, val name: String, val market: String)
    private data class AssetSpec(val symbol: String, val market: String)

    private val selected15Assets = listOf(
        AssetSpec("EUR/USD", "normal"), AssetSpec("GBP/USD", "normal"),
        AssetSpec("USD/JPY", "normal"), AssetSpec("AUD/USD", "normal"),
        AssetSpec("USD/CAD", "normal"), AssetSpec("USD/CHF", "normal"),
        AssetSpec("NZD/USD", "normal"), AssetSpec("EUR/GBP", "normal"),
        AssetSpec("EUR/JPY", "normal"), AssetSpec("GBP/JPY", "normal"),
        AssetSpec("EUR/USD", "otc"), AssetSpec("GBP/USD", "otc"),
        AssetSpec("USD/JPY", "otc"), AssetSpec("AUD/USD", "otc"),
        AssetSpec("EUR/GBP", "otc")
    )

    private val monitor = object : Runnable {
        override fun run() {
            if (prefs.token.isNotBlank() && botActive) io.execute {
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
                        overlay?.update(direction, confidence, reason.ifBlank { "Aguardando confirmação" }, meta)
                        status.text = "BOT ATIVO · ${robot.optString("environment", "DEMO").uppercase()}\nAtivo: ${robot.optString("lastAsset", "aguardando")} · Leituras: ${robot.optInt("scannedAssets")}\n${robot.optString("lastError", "Escaneando candles em tempo real")}"
                    } else if (botActive) {
                        botActive = false
                        removeOverlay()
                        status.text = "Bot parado pela API. ${robot.optString("lastError", "Confira a conexão e o ativo selecionado.")}"
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
        brokerLoginPanel = findViewById(R.id.brokerLoginPanel)
        brokerSessionPanel = findViewById(R.id.brokerSessionPanel)
        brokerSessionStatus = findViewById(R.id.brokerSessionStatus)
        brokerEmail = findViewById(R.id.brokerEmail)
        brokerPassword = findViewById(R.id.brokerPassword)
        iqStatus = findViewById(R.id.iqStatus)
        assetStatus = findViewById(R.id.assetStatus)
        assetSpinner = findViewById(R.id.assetSpinner)
        iqEmail = findViewById(R.id.iqEmail)
        iqPassword = findViewById(R.id.iqPassword)
        stakeInput = findViewById(R.id.stakeInput)
        lossInput = findViewById(R.id.lossInput)
        profitInput = findViewById(R.id.profitInput)
        buttonLogin = findViewById(R.id.buttonLogin)
        buttonConnectIq = findViewById(R.id.buttonConnectIq)
        buttonStart = findViewById(R.id.buttonStart)

        findViewById<RadioGroup>(R.id.timeframeGroup).check(R.id.tfM1)
        setAssetPlaceholder("Faça login e conecte a IQ Option para carregar os ativos.")
        buttonLogin.setOnClickListener { loginBroker() }
        buttonConnectIq.setOnClickListener { connectIq() }
        buttonStart.setOnClickListener { startBot() }
        findViewById<Button>(R.id.buttonStop).setOnClickListener { stopBot() }
        assetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) { }
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = assets.getOrNull(position - 1)
                if (selected != null) prefs.selectedAssetId = selected.id
            }
        }

        if (prefs.token.isBlank()) showBrokerLogin() else validateBrokerSession()
        status.text = "Verificando sua sessão e a conexão com a IQ Option…"
    }

    private fun showBrokerLogin() {
        brokerSessionValidated = false
        brokerSessionPanel.visibility = View.GONE
        brokerLoginPanel.visibility = View.VISIBLE
    }

    private fun showBrokerSession(email: String) {
        brokerSessionValidated = true
        brokerLoginPanel.visibility = View.GONE
        brokerSessionPanel.visibility = View.VISIBLE
        brokerSessionStatus.text = if (email.isBlank()) "Sessão LS Broker ativa neste aparelho" else "Conta LS Broker · $email"
    }

    private fun validateBrokerSession() {
        io.execute {
            val response = BridgeApi.get(apiBase, "/api/v1/me", prefs.token)
            val email = response.json().optJSONObject("user")?.optString("email").orEmpty()
            main.post {
                if (response.status in 200..299) {
                    showBrokerSession(email)
                    status.text = "Sessão validada. Verificando IQ Option…"
                    refreshIqStatus()
                } else {
                    prefs.token = ""
                    showBrokerLogin()
                    status.text = "Sua sessão expirou. Entre uma vez para continuar."
                }
            }
        }
    }

    private fun loginBroker() {
        val email = brokerEmail.text.toString().trim()
        val password = brokerPassword.text.toString()
        if (email.isBlank() || password.isBlank()) { toast("Informe o e-mail e a senha da sua conta."); return }
        buttonLogin.isEnabled = false
        status.text = "Validando sua conta…"
        io.execute {
            val response = BridgeApi.post(apiBase, "/api/v1/auth/login", null,
                JSONObject().put("email", email).put("password", password))
            val json = response.json()
            main.post {
                buttonLogin.isEnabled = true
                if (response.status in 200..299 && json.optString("token").isNotBlank()) {
                    prefs.token = json.optString("token")
                    brokerPassword.text.clear()
                    showBrokerSession(json.optJSONObject("user")?.optString("email").orEmpty())
                    status.text = "Acesso confirmado. Agora conecte sua IQ Option."
                    refreshIqStatus()
                    toast("Acesso ao aplicativo confirmado.")
                } else status.text = "Não foi possível entrar: ${response.errorMessage()}"
            }
        }
    }

    private fun connectIq() {
        if (!brokerSessionValidated || prefs.token.isBlank()) { toast("A sessão do aplicativo precisa ser validada primeiro."); return }
        val email = iqEmail.text.toString().trim()
        val password = iqPassword.text.toString()
        if (email.isBlank() || password.isBlank()) { toast("Informe o e-mail e a senha da IQ Option."); return }
        buttonConnectIq.isEnabled = false
        iqConnected = false
        iqStatus.text = "Conectando e validando a sessão IQ Option…"
        status.text = "Aguardando autenticação da IQ Option…"
        io.execute {
            val response = BridgeApi.post(apiBase, "/api/v1/connectors/iq-option/connect", prefs.token,
                JSONObject().put("email", email).put("password", password))
            val json = response.json()
            main.post {
                buttonConnectIq.isEnabled = true
                iqPassword.text.clear()
                // Uma checagem iniciada ao voltar para a tela não pode bloquear a validação após o login.
                iqStatusLoading = false
                if (response.status in 200..299 && json.optString("status") == "online") {
                    iqStatus.text = "Conexão autenticada. Conferindo saldo e ativos…"
                    status.text = "Conexão inicial confirmada; verificando se está pronta para operar…"
                    refreshIqStatus()
                    toast("IQ Option autenticada. Validando o canal de dados.")
                } else {
                    iqConnected = false
                    iqStatus.text = "IQ Option não conectada"
                    status.text = "Falha na conexão: ${response.errorMessage()}"
                }
            }
        }
    }

    /** A UI só mostra conectada depois de validar status E consultar saldos pela sessão ativa. */
    private fun refreshIqStatus() {
        if (!brokerSessionValidated || prefs.token.isBlank() || iqStatusLoading) return
        iqStatusLoading = true
        io.execute {
            val statusResponse = BridgeApi.get(apiBase, "/api/v1/connectors/iq-option/status", prefs.token)
            val statusJson = statusResponse.json()
            var ready = statusResponse.status in 200..299 && statusJson.optBoolean("ready")
            var detail = statusJson.optString("message")
            if (ready) {
                val balances = BridgeApi.get(apiBase, "/api/v1/connectors/iq-option/balances", prefs.token)
                val items = balances.json().optJSONArray("items")
                ready = balances.status in 200..299 && items != null && items.length() > 0
                if (!ready) detail = balances.errorMessage()
            }
            main.post {
                iqStatusLoading = false
                iqConnected = ready
                if (ready) {
                    iqStatus.text = "IQ Option conectada · DEMO · sessão validada"
                    status.text = "Conta IQ Option pronta. Carregando a lista de ativos…"
                    loadAssets()
                } else {
                    iqStatus.text = "IQ Option desconectada ou sessão expirada"
                    assetStatus.text = "Reconecte a IQ Option para carregar os 15 ativos."
                    setAssetPlaceholder("Aguardando conexão válida com a IQ Option")
                    status.text = "A conexão não está pronta para operar. ${detail.ifBlank { "Conecte novamente a IQ Option." }}"
                }
            }
        }
    }

    private fun normalizeSymbol(value: String): String = value.uppercase()
        .replace("OTC", "").filter { it.isLetterOrDigit() }

    private fun setAssetPlaceholder(label: String) {
        assetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(label))
        assetSpinner.isEnabled = false
    }

    private fun loadAssets() {
        if (!iqConnected || assetsLoading || prefs.token.isBlank()) return
        assetsLoading = true
        assetStatus.text = "Consultando catálogo da IQ Option…"
        setAssetPlaceholder("Carregando ativos disponíveis…")
        io.execute {
            val response = BridgeApi.get(apiBase, "/api/v1/connectors/iq-option/assets", prefs.token)
            val json = response.json()
            val rowsByKey = mutableMapOf<String, Asset>()
            for (group in listOf("normal", "otc")) {
                val rows = json.optJSONArray(group) ?: continue
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val id = row.optInt("id")
                    val name = row.optString("name")
                    val market = row.optString("market", group).lowercase().let { if (it == "otc" || group == "otc") "otc" else "normal" }
                    if (id > 0 && name.isNotBlank()) {
                        val symbol = normalizeSymbol(row.optString("code").ifBlank { name })
                        val display = if (market == "otc") "${name.replace(Regex("\\s*OTC$", RegexOption.IGNORE_CASE), "")} OTC" else name.replace(Regex("\\s*OTC$", RegexOption.IGNORE_CASE), "")
                        rowsByKey["$symbol|$market"] = Asset(id, display, market)
                    }
                }
            }
            val found = selected15Assets.mapNotNull { spec -> rowsByKey["${normalizeSymbol(spec.symbol)}|${spec.market}"] }
            main.post {
                assetsLoading = false
                if (response.status in 200..299 && found.isNotEmpty()) {
                    assets = found
                    val names = listOf("Selecione o ativo que o robô vai operar") + assets.map { it.name }
                    assetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
                    assetSpinner.isEnabled = true
                    val savedIndex = assets.indexOfFirst { it.id == prefs.selectedAssetId }
                    assetSpinner.setSelection(if (savedIndex >= 0) savedIndex + 1 else 0)
                    assetStatus.text = "${assets.size} de 15 ativos encontrados. O robô operará somente o ativo selecionado."
                    status.text = if (assets.size == 15) "Os 15 ativos foram carregados. Escolha um para operar." else "Catálogo carregado parcialmente: ${assets.size} de 15 ativos retornados pela IQ Option."
                } else {
                    assets = emptyList()
                    setAssetPlaceholder("Não foi possível carregar os ativos")
                    assetStatus.text = "Falha ao carregar o catálogo: ${response.errorMessage()}"
                    status.text = "A IQ Option respondeu, mas não entregou a lista de ativos. Tente atualizar a conexão."
                }
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
        if (!brokerSessionValidated || prefs.token.isBlank()) { toast("A sessão do aplicativo expirou. Entre novamente."); return }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            toast("Autorize o painel flutuante e toque em Iniciar bot novamente.")
            return
        }
        val asset = assets.getOrNull(assetSpinner.selectedItemPosition - 1)
        if (asset == null) { toast("Escolha um dos 15 ativos antes de iniciar."); return }
        val stake = stakeInput.text.toString().replace(',', '.').toDoubleOrNull()
        val loss = lossInput.text.toString().replace(',', '.').toDoubleOrNull()
        val profit = profitInput.text.toString().replace(',', '.').toDoubleOrNull()
        if (stake == null || loss == null || profit == null || stake < 1 || stake > 10 || loss < 1 || loss > 50 || profit < 1 || profit > 500) {
            toast("Entrada: R$1–10 · Stop Loss: R$1–50 · Meta: R$1–500.")
            return
        }
        val selectedTimeframe = timeframe()
        val patterns = selectedPatterns()
        val strategyConfig = JSONObject().put("MESTRE_CONFLUENCIAS", true)
            .put("EMA_9_21", findViewById<CheckBox>(R.id.strategyEma).isChecked)
            .put("RSI_14", findViewById<CheckBox>(R.id.strategyRsi).isChecked)
            .put("MACD", findViewById<CheckBox>(R.id.strategyMacd).isChecked)
            .put("SUPPORT_RESISTANCE", findViewById<CheckBox>(R.id.strategySr).isChecked)
            .put("CANDLE_PATTERNS", findViewById<CheckBox>(R.id.strategyCandle).isChecked)
        if (!listOf(R.id.strategyEma, R.id.strategyRsi, R.id.strategyMacd, R.id.strategySr, R.id.strategyCandle).any { findViewById<CheckBox>(it).isChecked }) {
            toast("Ative pelo menos uma estratégia.")
            return
        }
        buttonStart.isEnabled = false
        status.text = "Conferindo a sessão da IQ Option antes de iniciar…"
        io.execute {
            val liveStatus = BridgeApi.get(apiBase, "/api/v1/connectors/iq-option/status", prefs.token)
            val liveJson = liveStatus.json()
            if (liveStatus.status !in 200..299 || !liveJson.optBoolean("ready")) {
                main.post {
                    buttonStart.isEnabled = true
                    iqConnected = false
                    iqStatus.text = "IQ Option desconectada ou sessão expirada"
                    status.text = "A sessão da IQ Option expirou. Conecte novamente e depois inicie o robô."
                }
                return@execute
            }
            val balanceResponse = BridgeApi.get(apiBase, "/api/v1/connectors/iq-option/balances", prefs.token)
            val balanceItems = balanceResponse.json().optJSONArray("items")
            if (balanceResponse.status !in 200..299 || balanceItems == null || balanceItems.length() == 0) {
                main.post {
                    buttonStart.isEnabled = true
                    iqConnected = false
                    iqStatus.text = "Sessão IQ Option sem saldo validado"
                    status.text = "Não foi possível confirmar o saldo da conta. Reconecte a IQ Option."
                }
                return@execute
            }
            val config = JSONObject().put("environment", "demo").put("timeframe", selectedTimeframe).put("stake", stake)
                .put("maxDailyLoss", loss).put("maxDailyProfit", profit).put("minConfidence", 0.70)
                .put("strategies", strategyConfig).put("candlePatterns", patterns)
                .put("enabledAssets", JSONArray().put(JSONObject().put("id", asset.id).put("name", asset.name).put("market", asset.market)))
            val saved = BridgeApi.put(apiBase, "/api/v1/trading/config", prefs.token, config)
            if (saved.status !in 200..299) {
                main.post { buttonStart.isEnabled = true; status.text = "Configuração rejeitada: ${saved.errorMessage()}" }
                return@execute
            }
            val started = BridgeApi.post(apiBase, "/api/v1/trading/auto/start", prefs.token, JSONObject())
            main.post {
                buttonStart.isEnabled = true
                if (started.status in 200..299 && started.json().optBoolean("autoEnabled")) {
                    botActive = true
                    showOverlay()
                    overlay?.update("WAIT", 0.0, "Aguardando candle fechado", "${asset.name} · $selectedTimeframe")
                    status.text = "BOT INICIADO · DEMO\nAtivo exclusivo: ${asset.name} · $selectedTimeframe\nEscaneando somente o ativo selecionado."
                    main.removeCallbacks(monitor)
                    main.postDelayed(monitor, 1500L)
                } else {
                    val message = started.errorMessage()
                    if (started.json().optString("error") == "iq_session_expired") {
                        iqConnected = false
                        iqStatus.text = "IQ Option desconectada ou sessão expirada"
                    }
                    status.text = "Não foi possível iniciar: $message"
                }
            }
        }
    }

    private fun stopBot() {
        if (prefs.token.isBlank()) { botActive = false; removeOverlay(); return }
        io.execute {
            val response = BridgeApi.post(apiBase, "/api/v1/trading/auto/stop", prefs.token, JSONObject())
            main.post {
                botActive = false
                main.removeCallbacks(monitor)
                removeOverlay()
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
        if (::iqStatus.isInitialized && brokerSessionValidated && prefs.token.isNotBlank()) refreshIqStatus()
    }

    @Deprecated("Deprecated in Android API; retained for the app's background trading flow")
    override fun onBackPressed() {
        if (botActive) { moveTaskToBack(true); return }
        super.onBackPressed()
    }

    override fun onDestroy() {
        main.removeCallbacks(monitor)
        removeOverlay()
        io.shutdown()
        super.onDestroy()
    }
}
