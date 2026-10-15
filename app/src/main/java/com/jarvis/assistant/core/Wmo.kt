package com.jarvis.assistant.core

/** WMO weather codes (used by Open-Meteo) in plain words. */
object Wmo {
    fun describe(code: Int): String = when (code) {
        0 -> "clear skies"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        in 51..57 -> "drizzle"
        in 61..67 -> "rain"
        in 71..77 -> "snow"
        in 80..82 -> "rain showers"
        85, 86 -> "snow showers"
        95, 96, 99 -> "thunderstorms"
        else -> "mixed weather"
    }
}
