package com.aitrader.bridge

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Análise local: não precisa de internet, conta ou servidor. Sinais são informativos. */
object StrategyEngine {
    data class Result(val direction: String, val confidence: Double, val reason: String, val count: Int)

    fun analyze(input: List<DetectedCandle>, enabled: Set<String>): Result {
        val c = input.filter { it.isClosed == "true" && it.confidence >= 0.45 && it.high > it.low }
        if (c.size < 26) return Result("WAIT", 0.0, "Lendo candles: ${c.size}/26 fechados", c.size)
        val close = c.map { it.close }
        val last = c.last(); val prev = c[c.lastIndex - 1]
        var buy = 0.0; var sell = 0.0; val why = mutableListOf<String>()
        fun ema(v: List<Double>, p: Int): Double? {
            if (v.size < p) return null
            var e = v.take(p).average(); val a = 2.0 / (p + 1.0)
            for (x in v.drop(p)) e = x * a + e * (1 - a)
            return e
        }
        if ("EMA" in enabled) {
            val e9=ema(close,9); val e21=ema(close,21)
            if (e9 != null && e21 != null) if (e9 > e21) { buy+=2; why += "EMA 9 acima da 21" } else if (e9 < e21) { sell+=2; why += "EMA 9 abaixo da 21" }
        }
        if ("RSI" in enabled) {
            val d=close.takeLast(15).zipWithNext().map { it.second-it.first }
            val gains=d.map { max(0.0,it) }.average(); val losses=d.map { max(0.0,-it) }.average()
            val rsi=if(losses==0.0) 100.0 else 100.0-100.0/(1.0+gains/losses)
            if(rsi in 52.0..70.0){buy+=1.2;why+="RSI ${rsi.toInt()} comprador"} else if(rsi in 30.0..48.0){sell+=1.2;why+="RSI ${rsi.toInt()} vendedor"}
        }
        if ("MACD" in enabled) {
            val e12=ema(close,12); val e26=ema(close,26)
            if(e12!=null&&e26!=null) if(e12>e26){buy+=1.2;why+="MACD positivo"}else if(e12<e26){sell+=1.2;why+="MACD negativo"}
        }
        if ("CANDLE" in enabled) {
            val bull=last.close>last.open; val bear=last.close<last.open
            if(bull&&prev.close<prev.open&&last.close>prev.open&&last.open<prev.close){buy+=2;why+="Engolfo de alta"}
            else if(bear&&prev.close>prev.open&&last.close<prev.open&&last.open>prev.close){sell+=2;why+="Engolfo de baixa"}
            else { val range=max(last.high-last.low,1e-12); val body=abs(last.close-last.open)/range
                if(bull&&body>0.65){buy+=0.8;why+="Candle forte de alta"} else if(bear&&body>0.65){sell+=0.8;why+="Candle forte de baixa"} }
        }
        if ("SR" in enabled) {
            val recent=c.takeLast(20); val support=recent.minOf { it.low }; val resistance=recent.maxOf { it.high }
            val range=max(last.high-last.low,1e-12)
            if(last.low<=support+range*0.2&&last.close>last.open&&(last.close-last.low)/range>0.6){buy+=1.0;why+="Rejeição do suporte"}
            if(last.high>=resistance-range*0.2&&last.close<last.open&&(last.high-last.close)/range>0.6){sell+=1.0;why+="Rejeição da resistência"}
        }
        if(enabled.isEmpty()) return Result("WAIT",0.0,"Ative ao menos uma estratégia",c.size)
        val diff=abs(buy-sell); val total=max(buy,sell); val confidence=min(0.95,0.50+diff/10.0+min(total,5.0)/25.0)
        if(diff<2.3||total<2.8||confidence<0.65) return Result("WAIT",min(confidence,0.64),"Sem confluência suficiente. ${why.takeLast(3).joinToString(" · ")}",c.size)
        return Result(if(buy>sell) "COMPRA" else "VENDA",confidence,"${if(buy>sell) "Compra" else "Venda"}: ${why.takeLast(4).joinToString(" · ")}",c.size)
    }
}
