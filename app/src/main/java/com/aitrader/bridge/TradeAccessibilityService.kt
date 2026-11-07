package com.aitrader.bridge

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/** Execução integrada no próprio APK. Só aceita ordem quando a tela confirma conta de prática/demo. */
class TradeAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: TradeAccessibilityService? = null
        @Volatile private var pendingDirection: String? = null
        @Volatile private var pendingSignalKey: String? = null
        @Volatile var lastMessage: String = "Aguardando ativação da acessibilidade."
            private set
        private var lastClickAt = 0L
        private var lastDirection = ""
        private var lastRequestedSignalKey = ""
        private var sessionTrades = 0
        private var pendingSince = 0L
        private const val COOLDOWN_MS = 60_000L
        private const val MAX_SESSION_TRADES = 5
        private const val MIN_CONFIDENCE = 0.78

        fun requestTrade(direction: String, confidence: Double, signalKey: String) {
            val normalized = direction.uppercase(Locale.ROOT)
            if (normalized !in setOf("COMPRA", "VENDA")) return
            val service = instance
            if (service == null) { lastMessage = "Ative a permissão de acessibilidade do AITraderBridge."; return }
            if (signalKey == lastRequestedSignalKey) return
            val prefs = Prefs(service)
            if (!prefs.autoTradeEnabled) { lastMessage = "Execução automática desligada."; return }
            if (confidence < MIN_CONFIDENCE) { lastMessage = "Sinal abaixo de 78%: aguardando."; return }
            if (sessionTrades >= MAX_SESSION_TRADES) { lastMessage = "Limite de 5 operações nesta sessão atingido."; return }
            val now = System.currentTimeMillis()
            if (now - lastClickAt < COOLDOWN_MS) { lastMessage = "Proteção ativa: aguardando intervalo de 60 segundos."; return }
            lastRequestedSignalKey = signalKey
            pendingDirection = normalized
            pendingSignalKey = signalKey
            pendingSince = now
            lastMessage = "Candle fechado: sinal $normalized aprovado. Verificando conta DEMO e botão da corretora…"
            service.performPendingTrade()
        }

        fun resetSession() { sessionTrades = 0; lastDirection = ""; lastClickAt = 0L; lastRequestedSignalKey = ""; pendingDirection = null; pendingSignalKey = null; lastMessage = "Limites da sessão reiniciados." }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lastMessage = "Acessibilidade conectada ao AITraderBridge."
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (pendingDirection != null) performPendingTrade()
    }

    override fun onInterrupt() { lastMessage = "Acessibilidade interrompida." }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun performPendingTrade() {
        val direction = pendingDirection ?: return
        if (System.currentTimeMillis() - pendingSince > 20_000L) {
            pendingDirection = null
            pendingSignalKey = null
            lastMessage = "Sinal expirou antes da confirmação visual. Nenhum toque executado."
            return
        }
        val root = rootInActiveWindow ?: run { lastMessage = "Não consegui ler a tela da corretora."; return }
        try {
            val screenText = normalizeText(collectText(root))
            // IQ Option pode exibir o saldo de prática em vez do rótulo literal "Conta DEMO".
            val demoVisible = listOf("CONTA DE PRATICA", "CONTA DEMO", "DEMO ACCOUNT", "PRACTICE ACCOUNT", "SALDO DE PRATICA", "MODO DE PRATICA").any { it in screenText }
            if (!demoVisible) {
                lastMessage = "TRAVA DE SEGURANÇA: conta DEMO não confirmada. Nenhum toque executado."
                pendingDirection = null
                pendingSignalKey = null
                return
            }
            val labels = if (direction == "COMPRA") listOf("COMPRAR", "MAIS ALTO", "ACIMA", "CALL", "BUY") else listOf("VENDER", "MAIS BAIXO", "ABAIXO", "PUT", "SELL")
            val node = findActionNode(root, labels)
            if (node == null) {
                lastMessage = "Conta DEMO reconhecida, mas botão ${labels.first()} não encontrado."
                return
            }
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                lastClickAt = System.currentTimeMillis()
                lastDirection = direction
                sessionTrades += 1
                lastMessage = "Toque $direction enviado na conta DEMO. Confira na IQ Option se a ordem abriu. ($sessionTrades/$MAX_SESSION_TRADES)"
                pendingDirection = null
                pendingSignalKey = null
            } else {
                lastMessage = "Botão encontrado, mas a corretora não aceitou o toque."
            }
        } catch (e: Exception) {
            lastMessage = "Falha segura na execução: ${e.message ?: "erro desconhecido"}"
        }
    }

    private fun normalizeText(value: String): String = java.text.Normalizer
        .normalize(value.uppercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

    private fun collectText(node: AccessibilityNodeInfo): String {
        val out = StringBuilder()
        fun walk(n: AccessibilityNodeInfo) {
            n.text?.let { out.append(it).append(' ') }
            n.contentDescription?.let { out.append(it).append(' ') }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(node)
        return out.toString()
    }

    private fun findActionNode(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val wanted = labels.map { it.uppercase(Locale.ROOT) }
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val label = normalizeText((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: ""))
            if (wanted.any { label.trim() == it || label.contains(it) }) {
                var candidate: AccessibilityNodeInfo? = node
                while (candidate != null && !candidate.isClickable) candidate = candidate.parent
                if (candidate != null) return candidate
            }
            for (i in 0 until node.childCount) {
                val found = node.getChild(i)?.let { walk(it) }
                if (found != null) return found
            }
            return null
        }
        return walk(root)
    }
}
