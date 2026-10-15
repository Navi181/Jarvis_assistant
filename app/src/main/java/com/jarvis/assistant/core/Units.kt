package com.jarvis.assistant.core

/** Offline unit conversion and currency-name lookup. */
object Units {
    private data class U(val cat: String, val factor: Double, val one: String, val many: String)

    private val table = HashMap<String, U>()

    private fun add(cat: String, factor: Double, one: String, many: String, vararg names: String) {
        val u = U(cat, factor, one, many)
        names.forEach { table[it] = u }
    }

    init {
        add("len", 1000.0, "kilometer", "kilometers", "km", "kms", "kilometer", "kilometers", "kilometre", "kilometres")
        add("len", 1.0, "meter", "meters", "m", "meter", "meters", "metre", "metres")
        add("len", 0.01, "centimeter", "centimeters", "cm", "centimeter", "centimeters", "centimetre", "centimetres")
        add("len", 0.001, "millimeter", "millimeters", "mm", "millimeter", "millimeters")
        add("len", 1609.344, "mile", "miles", "mile", "miles", "mi")
        add("len", 0.3048, "foot", "feet", "ft", "foot", "feet")
        add("len", 0.0254, "inch", "inches", "inch", "inches")
        add("len", 0.9144, "yard", "yards", "yard", "yards", "yd", "yds")
        add("mass", 1.0, "kilogram", "kilograms", "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms")
        add("mass", 0.001, "gram", "grams", "g", "gram", "grams")
        add("mass", 0.45359237, "pound", "pounds", "lb", "lbs", "pound", "pounds")
        add("mass", 0.028349523, "ounce", "ounces", "oz", "ounce", "ounces")
        add("vol", 1.0, "liter", "liters", "l", "liter", "liters", "litre", "litres")
        add("vol", 0.001, "milliliter", "milliliters", "ml", "milliliter", "milliliters", "millilitre", "millilitres")
        add("vol", 3.785411784, "gallon", "gallons", "gallon", "gallons")
        add("temp", 0.0, "degree Celsius", "degrees Celsius", "c", "celsius", "centigrade")
        add("temp", 0.0, "degree Fahrenheit", "degrees Fahrenheit", "f", "fahrenheit")
        add("temp", 0.0, "kelvin", "kelvin", "k", "kelvin")
    }

    fun isUnit(word: String) = table.containsKey(word.lowercase())

    /** "10 miles is 16.09 kilometers." or null if the units are unknown / incompatible. */
    fun convertSpeech(amount: Double, from: String, to: String): String? {
        val f = table[from.lowercase()] ?: return null
        val t = table[to.lowercase()] ?: return null
        if (f.cat != t.cat) return null
        val result = if (f.cat == "temp") temp(amount, f.one, t.one) else amount * f.factor / t.factor
        val fromName = if (amount == 1.0) f.one else f.many
        val toName = if (result == 1.0) t.one else t.many
        return "${Calc.format(amount, 2)} $fromName is ${Calc.format(result, 2)} $toName."
    }

    private fun temp(v: Double, from: String, to: String): Double {
        val c = when {
            from.contains("Celsius") -> v
            from.contains("Fahrenheit") -> (v - 32.0) * 5.0 / 9.0
            else -> v - 273.15
        }
        return when {
            to.contains("Celsius") -> c
            to.contains("Fahrenheit") -> c * 9.0 / 5.0 + 32.0
            else -> c + 273.15
        }
    }

    private val currencies = mapOf(
        "dollar" to "USD", "dollars" to "USD", "usd" to "USD", "bucks" to "USD",
        "rupee" to "INR", "rupees" to "INR", "inr" to "INR", "rs" to "INR",
        "euro" to "EUR", "euros" to "EUR", "eur" to "EUR",
        "pound" to "GBP", "pounds" to "GBP", "gbp" to "GBP", "sterling" to "GBP",
        "yen" to "JPY", "jpy" to "JPY",
        "dirham" to "AED", "dirhams" to "AED", "aed" to "AED",
        "yuan" to "CNY", "rmb" to "CNY", "cny" to "CNY",
        "aud" to "AUD", "cad" to "CAD", "sgd" to "SGD", "chf" to "CHF"
    )

    fun currencyCode(word: String): String? = currencies[word.lowercase()]
}
