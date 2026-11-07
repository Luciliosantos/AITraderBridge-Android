package com.aitrader.bridge

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Estratégias locais calculadas sobre candles detectados na tela do aparelho. */
object StrategyEngine {
    data class Result(val direction: String, val confidence: Double, val reason: String, val count: Int)

    fun analyze(input: List<DetectedCandle>, enabled: Set<String>): Result {
        val c = input.filter { it.isClosed == "true" && it.confidence >= 0.45 && it.high > it.low }
        if (c.size < 26) return Result("WAIT", 0.0, "Lendo candles: ${c.size}/26 fechados", c.size)
        val close = c.map { it.close }
        val last = c.last(); val prev = c[c.lastIndex - 1]
        val p2 = c[c.lastIndex - 2]; val p3 = c[c.lastIndex - 3]
        var buy = 0.0; var sell = 0.0; val why = mutableListOf<String>()
        fun ema(v: List<Double>, p: Int): Double? {
            if (v.size < p) return null
            var e = v.take(p).average(); val a = 2.0 / (p + 1.0)
            for (x in v.drop(p)) e = x * a + e * (1 - a)
            return e
        }
        fun add(key: String, bullish: Boolean, bearish: Boolean, label: String, weight: Double = 1.5) {
            if (key !in enabled) return
            if (bullish) { buy += weight; why += "$label (alta)" }
            if (bearish) { sell += weight; why += "$label (baixa)" }
        }
        fun body(x: DetectedCandle) = abs(x.close - x.open)
        fun range(x: DetectedCandle) = max(x.high - x.low, 1e-12)
        fun bull(x: DetectedCandle) = x.close > x.open
        fun bear(x: DetectedCandle) = x.close < x.open
        fun upper(x: DetectedCandle) = x.high - max(x.open, x.close)
        fun lower(x: DetectedCandle) = min(x.open, x.close) - x.low
        val b = body(last); val r = range(last)
        if ("EMA" in enabled) { val e9=ema(close,9); val e21=ema(close,21); if(e9!=null&&e21!=null) if(e9>e21){buy+=2;why+="EMA 9 acima da 21"}else if(e9<e21){sell+=2;why+="EMA 9 abaixo da 21"} }
        if ("RSI" in enabled) { val d=close.takeLast(15).zipWithNext().map { it.second-it.first }; val gains=d.map { max(0.0,it) }.average(); val losses=d.map { max(0.0,-it) }.average(); val rsi=if(losses==0.0)100.0 else 100.0-100.0/(1.0+gains/losses); if(rsi in 52.0..70.0){buy+=1.2;why+="RSI ${rsi.toInt()} comprador"}else if(rsi in 30.0..48.0){sell+=1.2;why+="RSI ${rsi.toInt()} vendedor"} }
        if ("MACD" in enabled) { val e12=ema(close,12); val e26=ema(close,26); if(e12!=null&&e26!=null) if(e12>e26){buy+=1.2;why+="MACD positivo"}else if(e12<e26){sell+=1.2;why+="MACD negativo"} }
        if ("CANDLE" in enabled) { if(bull(last)&&bear(prev)&&last.close>prev.open&&last.open<prev.close){buy+=2;why+="Engolfo de alta"}else if(bear(last)&&bull(prev)&&last.close<prev.open&&last.open>prev.close){sell+=2;why+="Engolfo de baixa"}else if(bull(last)&&b/r>0.65){buy+=0.8;why+="Candle forte de alta"}else if(bear(last)&&b/r>0.65){sell+=0.8;why+="Candle forte de baixa"} }
        if ("SR" in enabled) { val recent=c.takeLast(20); val support=recent.minOf{it.low}; val resistance=recent.maxOf{it.high}; if(last.low<=support+r*0.2&&bull(last)&&(last.close-last.low)/r>0.6){buy+=1.0;why+="Rejeição do suporte"}; if(last.high>=resistance-r*0.2&&bear(last)&&(last.high-last.close)/r>0.6){sell+=1.0;why+="Rejeição da resistência"} }
        add("DOJI", b/r <= 0.10, b/r <= 0.10, "Doji", 0.0)
        val hammerBull = lower(last) >= b*2.0 && upper(last) <= max(b*0.6,r*0.12) && b/r <= 0.38
        val hammerBear = upper(last) >= b*2.0 && lower(last) <= max(b*0.6,r*0.12) && b/r <= 0.38
        add("HAMMER", hammerBull, hammerBear, "Martelo / invertido")
        add("SHOOTING_STAR", hammerBear, hammerBull, "Estrela cadente / enforcado")
        val prevMid=(p2.open+p2.close)/2.0
        val morning = bear(p2)&&body(p2)/range(p2)>0.45&&body(prev)/range(prev)<0.35&&bull(last)&&last.close>prevMid
        val evening = bull(p2)&&body(p2)/range(p2)>0.45&&body(prev)/range(prev)<0.35&&bear(last)&&last.close<prevMid
        add("MORNING_STAR",morning,false,"Estrela da manhã",2.0); add("EVENING_STAR",false,evening,"Estrela da noite",2.0)
        val haramiBull=bear(prev)&&bull(last)&&last.open>prev.close&&last.close<prev.open
        val haramiBear=bull(prev)&&bear(last)&&last.open<prev.close&&last.close>prev.open
        add("HARAMI",haramiBull,haramiBear,"Harami")
        val midpoint=(prev.open+prev.close)/2.0
        val piercing=bear(prev)&&bull(last)&&last.open<prev.low&&last.close>midpoint&&last.close<prev.open
        val darkCloud=bull(prev)&&bear(last)&&last.open>prev.high&&last.close<midpoint&&last.close>prev.open
        add("PIERCING",piercing,false,"Linha de perfuração",2.0); add("DARK_CLOUD",false,darkCloud,"Nuvem negra",2.0)
        val threeBull=bull(p3)&&bull(p2)&&bull(last)&&p2.close>p3.close&&last.close>p2.close&&body(p2)>=body(p3)*0.45&&body(last)>=body(p2)*0.45
        val threeBear=bear(p3)&&bear(p2)&&bear(last)&&p2.close<p3.close&&last.close<p2.close&&body(p2)>=body(p3)*0.45&&body(last)>=body(p2)*0.45
        add("THREE_SOLDIERS",threeBull,false,"Três soldados",2.0); add("THREE_CROWS",false,threeBear,"Três corvos",2.0)
        val tolerance=r*0.12
        add("TWEEZERS",abs(last.low-prev.low)<=tolerance&&bull(last)&&bear(prev),abs(last.high-prev.high)<=tolerance&&bear(last)&&bull(prev),"Pinça",1.7)
        add("MARUBOZU",bull(last)&&b/r>=0.88,bear(last)&&b/r>=0.88,"Marubozu",1.3)
        if (enabled.isEmpty()) return Result("WAIT",0.0,"Ative ao menos uma estratégia",c.size)
        val diff=abs(buy-sell); val total=max(buy,sell); val confidence=min(0.95,0.50+diff/10.0+min(total,5.0)/25.0)
        if(diff<2.3||total<2.8||confidence<0.65) return Result("WAIT",min(confidence,0.64),"Sem confluência suficiente. ${why.takeLast(4).joinToString(" · ")}",c.size)
        return Result(if(buy>sell)"COMPRA" else "VENDA",confidence,"${if(buy>sell)"Compra" else "Venda"}: ${why.takeLast(5).joinToString(" · ")}",c.size)
    }
}
