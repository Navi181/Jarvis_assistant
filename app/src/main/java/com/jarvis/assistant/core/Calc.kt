package com.jarvis.assistant.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Spoken maths: "15 percent of 2400", "12 times 7 plus 3", "square root of 144". */
object Calc {

    fun format(d: Double, decimals: Int = 4): String {
        if (d.isNaN() || d.isInfinite()) return "undefined"
        if (abs(d) < 1e15 && abs(d - d.roundToLong()) < 1e-9) return d.roundToLong().toString()
        return String.format(Locale.US, "%.${decimals}f", d).trimEnd('0').trimEnd('.')
    }

    /** Returns null when the text is not a (valid) calculation. */
    fun fromSpeech(input: String): Double? {
        var s = Num.digitize(input.lowercase().trim()).trimEnd('.', '?', '!', ' ')
        s = s.replace(Regex("(?<=\\d),(?=\\d{3})"), "")
        s = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s*of\\s*(\\d+(?:\\.\\d+)?)")
            .replace(s) { "(${it.groupValues[1]}/100)*${it.groupValues[2]}" }
        s = Regex("(?:square )?root of (\\d+(?:\\.\\d+)?)").replace(s) { "sqrt(${it.groupValues[1]})" }
        s = s.replace(" to the power of ", "^").replace(" power ", "^")
        s = s.replace(" squared", "^2").replace(" cubed", "^3")
        s = s.replace(" plus ", "+").replace(" minus ", "-")
            .replace(" multiplied by ", "*").replace(" times ", "*").replace(" into ", "*").replace(" x ", "*")
            .replace(" divided by ", "/").replace(" over ", "/")
            .replace("×", "*").replace("÷", "/")
        if (!s.any { it.isDigit() }) return null
        if (!Regex("[+\\-*/^]|sqrt").containsMatchIn(s)) return null
        return Parser(s).parse()
    }

    private class Parser(val s: String) {
        var i = 0

        fun parse(): Double? = try {
            val v = expr()
            ws()
            if (i != s.length || v.isNaN() || v.isInfinite()) null else v
        } catch (e: IllegalArgumentException) {
            null
        }

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun expr(): Double {
            var v = term()
            while (true) {
                ws()
                if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                    val op = s[i++]
                    val r = term()
                    v = if (op == '+') v + r else v - r
                } else return v
            }
        }

        fun term(): Double {
            var v = power()
            while (true) {
                ws()
                if (i < s.length && (s[i] == '*' || s[i] == '/')) {
                    val op = s[i++]
                    val r = power()
                    if (op == '/') {
                        require(r != 0.0) { "div0" }
                        v /= r
                    } else v *= r
                } else return v
            }
        }

        fun power(): Double {
            val b = unary()
            ws()
            if (i < s.length && s[i] == '^') {
                i++
                return b.pow(power())
            }
            return b
        }

        fun unary(): Double {
            ws()
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            return primary()
        }

        fun primary(): Double {
            ws()
            require(i < s.length) { "eof" }
            if (s.startsWith("sqrt", i)) {
                i += 4
                ws()
                require(i < s.length && s[i] == '(') { "sqrt(" }
                i++
                val v = expr()
                ws()
                require(i < s.length && s[i] == ')') { ")" }
                i++
                require(v >= 0) { "neg sqrt" }
                return sqrt(v)
            }
            if (s[i] == '(') {
                i++
                val v = expr()
                ws()
                require(i < s.length && s[i] == ')') { ")" }
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start) { "num" }
            return s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("num")
        }
    }
}
