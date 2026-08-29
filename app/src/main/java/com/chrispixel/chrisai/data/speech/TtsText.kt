package com.chrispixel.chrisai.data.speech

/**
 * v0.7 text preprocessing pipeline for TextToSpeech.
 *
 * The pipeline is pure Kotlin (no Android dependencies) so it runs in unit
 * tests on the JVM. It cleans ONLY the text sent to the TTS engine; the text
 * shown in the UI is never modified.
 *
 * "AI response → TTS preprocessing → Android TTS"
 */
object TtsText {

    private val EMOJI_RANGES = listOf(
        0x1F300..0x1F5FF,  // Miscellaneous Symbols and Pictographs
        0x1F600..0x1F64F,  // Emoticons
        0x1F680..0x1F6FF,  // Transport and Map Symbols
        0x1F900..0x1F9FF,  // Supplemental Symbols and Pictographs
        0x1FA70..0x1FAFF,  // Symbols and Pictographs Extended-A
        0x2600..0x27BF,    // Misc Symbols + Dingbats
        0xFE00..0xFE0F,    // Variation Selectors
        0x200D..0x200D,       // Zero Width Joiner
        0x20E0..0x20FF,    // Combining Enclosed Keys
        0x1F1E6..0x1F1FF   // Regional Indicators
    )

    private val MARKDOWN_BLOCK = Regex(
        """^#{1,6}\s*|^\s*[-*+]\s+|^\s*\d+[.)]\s+|^\s*>\s*|^---+$|^\s*\*+\s*$""",
        RegexOption.MULTILINE
    )
    private val MARKDOWN_INLINE = Regex(
        """\*\*([^*]+)\*\*|\*([^*]+)\*|__([^_]+)__|_([^_]+)_|~~([^~]+)~~|`([^`]+)`"""
    )
    private val LINK = Regex("""\[([^\]]+)\]\([^)]+\)""")
    private val URL = Regex(
        """\b(?:https?://|www\.)[^\s<>"')\]]+""",
        RegexOption.IGNORE_CASE
    )
    private val MULTIPLE_SPACES = Regex("""\s+""")
    private val MULTIPLE_NEWLINES = Regex("""\n{2,}""")
    private val PUNCTUATION_NO_SPACE = Regex("""\s+([.,;:!?])""")
    private val CODE_FENCE = Regex("""```[^\n]*|~~~[^\n]*|^[\t ]*(?=`)""", RegexOption.MULTILINE)
    private val TOOLS_BLOCK = Regex("""\[TOOLS\][\s\S]*?\[/TOOLS\]|\[TOOLS\]""")

    private val ABBREVIATIONS = mapOf(
        "p.ej." to "por ejemplo",
        "p. ej." to "por ejemplo",
        "etc." to "etcétera",
        "sr." to "señor",
        "sra." to "señora",
        "sres." to "señores",
        "aprox." to "aproximadamente",
        "aprox" to "aproximadamente",
        "tel." to "teléfono",
        "dpto." to "departamento",
        "dr." to "doctor",
        "dra." to "doctora",
        "núm." to "número",
        "nº" to "número",
        "no." to "número",
        "sq." to "cuadrado",
        "kg." to "kilogramos",
        "km." to "kilómetros",
    )

    private val NUMBER_WORDS = mapOf(
        0 to "cero", 1 to "uno", 2 to "dos", 3 to "tres", 4 to "cuatro", 5 to "cinco",
        6 to "seis", 7 to "siete", 8 to "ocho", 9 to "nueve", 10 to "diez",
        11 to "once", 12 to "doce", 13 to "trece", 14 to "catorce", 15 to "quince",
        16 to "dieciséis", 17 to "diecisiete", 18 to "dieciocho", 19 to "diecinueve",
        20 to "veinte", 21 to "veintiuno", 22 to "veintidós", 23 to "veintitrés",
        24 to "veinticuatro", 25 to "veinticinco", 26 to "veintiséis", 27 to "veintisiete",
        28 to "veintiocho", 29 to "veintinueve",
        30 to "treinta", 40 to "cuarenta", 50 to "cincuenta", 60 to "sesenta",
        70 to "setenta", 80 to "ochenta", 90 to "noventa",
        100 to "cien", 200 to "doscientos", 300 to "trescientos", 400 to "cuatrocientos",
        500 to "quinientos", 600 to "seiscientos", 700 to "setecientos",
        800 to "ochocientos", 900 to "novecientos"
    )

    /** "80%" -> "ochenta por ciento"; plain numbers -> words; 3.5 -> "tres coma cinco". */
    private val PERCENT = Regex("""(\d+(?:[.,]\d+)?)\s*%""")

    private val CURRENCY = Regex("""(\d+(?:[.,]\d+)?)\s*(€|EUR|euros?|USD|dólares?|\bUSD\b)""", RegexOption.IGNORE_CASE)

    private val NUMBER = Regex("""(?<![A-Za-zÀ-ÿ0-9])(\d+(?:[.,]\d+)?)(?![A-Za-zÀ-ÿ0-9])""")
    private val NUMBER_ORDINAL_PATTERN = Regex(
        """(?<![A-Za-zÀ-ÿ0-9])1[.º°]ª?\b|(?<![A-Za-zÀ-ÿ0-9])(\d+)[.º°]ª?\b""",
        RegexOption.IGNORE_CASE
    )

    /** Prepares [raw] for speech synthesis. Never touches the displayed text. */
    fun prepare(raw: String): String {
        if (raw.isBlank()) return ""
        var out = raw
        out = stripCodeFences(out)
        out = LINK.replace(out) { match -> match.groupValues[1] }
        out = URL.replace(out) { match -> pronounceUrl(match.value) }
        out = MARKDOWN_INLINE.replace(out) { match ->
            (match.groupValues.drop(1).firstOrNull { it.isNotBlank() }) ?: match.value
        }
        out = MARKDOWN_BLOCK.replace(out, "")
        out = TOOLS_BLOCK.replace(out, "")
        out = clearEmojis(out)
        out = replaceAbbreviations(out)
        out = CURRENCY.replace(out) { match ->
            pronounceCurrency(match.groupValues[1], match.groupValues[2])
        }
        out = replacePercentages(out)
        out = out.replace("&", " y ")
        out = out.replace("+", " más ")
        out = out.replace("<3", " te quiero ")
        out = replaceNumbers(out)
        out = out.replace("...", "…")
        out = out.replace("…", " puntos suspensivos. ")
        out = out.replace("°C", " grados Celsius ")
        out = out.replace("º C", " grados Celsius ")
        out = out.replace("°", " grados ")
        out = out.replace("=", " igual ")
        out = out.replace("≠", " no es igual a ")
        out = out.replace("->", " conduce a ")
        out = out.replace("→", " conduce a ")
        out = out.replace("<", " menor que ")
        out = out.replace(">", " mayor que ")
        out = NUMBER_ORDINAL_PATTERN.replace(out) { match ->
            val n = match.groupValues[2].ifBlank { match.groupValues[2] }
            if (n.isBlank()) match.value else numberToWords(n).let { if (it.trim().isBlank()) match.value else it }
        }
        out = MULTIPLE_NEWLINES.replace(out, "\n")
        out = MULTIPLE_SPACES.replace(out, " ")
        out = PUNCTUATION_NO_SPACE.replace(out) { " ${it.groupValues[1]}" }
        return out.trim()
    }

    private fun stripCodeFences(text: String): String =
        CODE_FENCE.replace(text, "")

    /** Keeps emojis visible in the UI; only the TTS string loses them. */
    fun clearEmojis(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isEmojiCodePoint(cp)) {
                sb.append(' ')
            } else {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Detects single Unicode emojis, including astral pairs. */
    fun containsEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isEmojiCodePoint(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun isEmojiCodePoint(cp: Int): Boolean =
        EMOJI_RANGES.any { cp in it } || cp == 0xFE0F

    private fun replaceAbbreviations(text: String): String {
        var out = text
        // Leading \b avoids inner matches; no trailing \b because these
        // abbreviations often end in a dot followed by a space (which is not
        // a word boundary).
        for ((abbr, replacement) in ABBREVIATIONS) {
            out = Regex("(?i)\\b${Regex.escape(abbr)}").replace(out, replacement)
        }
        return out
    }

    private fun replacePercentages(text: String): String =
        PERCENT.replace(text) { match ->
            val num = match.groupValues[1]
            "${numberToWords(num)} por ciento"
        }

    private fun pronounceCurrency(value: String, currency: String): String {
        val amount = numberToWords(value.trim())
        return when {
            currency.startsWith("€") || currency.startsWith("EUR", true) -> "$amount euros"
            currency.contains("USD", true) || currency.contains("dólar", true) -> "$amount dólares"
            else -> "$amount euros"
        }
    }

    private fun pronounceUrl(url: String): String {
        val clean = url
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("www.")
            .substringBefore("/").substringBefore("?")
        return clean
            .replace("-", " ")
            .replace("_", " ")
            .replace(".", " punto ")
            .trim()
            .let { "web $it" }
    }

    private fun replaceNumbers(text: String): String =
        NUMBER.replace(text) { match ->
            val raw = match.groupValues[1]
            numberToWords(raw)
        }

    /** Converts a numeric string (may contain a decimal comma/dot) to Spanish words. */
    fun numberToWords(value: String): String {
        val normalized = value.trim().replace(',', '.')
        val parts = normalized.split('.')
        val integer = parts[0]
        val words = if (integer.isNotEmpty() && integer.all { it.isDigit() }) {
            integerToWords(integer.toLongOrNull() ?: 0L)
        } else {
            value
        }
        if (parts.size > 1) {
            val decimals = parts[1]
            if (decimals.isNotEmpty() && decimals.all { it.isDigit() }) {
                val decimalWords = decimals.take(6).map { NUMBER_WORDS[it.digitToInt()] ?: it.toString() }
                    .joinToString(" ")
                return "$words coma $decimalWords"
            }
        }
        return words
    }

    private fun integerToWords(n: Long): String {
        if (n < 0) return "menos ${integerToWords(-n)}"
        if (n < 100) return smallToWords(n)
        if (n < 1000) {
            val hundreds = (n / 100) * 100
            val rest = n % 100
            val base = when (hundreds) {
                100L -> if (n == 100L) "cien" else "ciento"
                else -> NUMBER_WORDS[hundreds.toInt()] ?: ""
            }
            return if (rest == 0L) base else "$base ${smallToWords(rest)}"
        }
        if (n < 1_000_000) {
            val thousands = n / 1000
            val rest = n % 1000
            val prefix = if (thousands == 1L) "mil" else "${integerToWords(thousands)} mil"
            return if (rest == 0L) prefix else "$prefix ${integerToWords(rest)}"
        }
        if (n < 1_000_000_000) {
            val millions = n / 1_000_000
            val rest = n % 1_000_000
            val prefix = if (millions == 1L) "un millón" else "${integerToWords(millions)} millones"
            return if (rest == 0L) prefix else "$prefix ${integerToWords(rest)}"
        }
        // Avoid extremely long readings; fall back to digit-by-digit.
        return n.toString()
    }

    private fun smallToWords(n: Long): String {
        if (n in 0..29) return NUMBER_WORDS[n.toInt()] ?: n.toString()
        if (n < 100) {
            val tens = (n / 10) * 10
            val unit = n % 10
            if (unit == 0L) return NUMBER_WORDS[tens.toInt()] ?: n.toString()
            if (tens == 30L) return "treinta y ${NUMBER_WORDS[unit.toInt()]}"
            return "${NUMBER_WORDS[tens.toInt()]} y ${NUMBER_WORDS[unit.toInt()]}"
        }
        return integerToWords(n)
    }
}