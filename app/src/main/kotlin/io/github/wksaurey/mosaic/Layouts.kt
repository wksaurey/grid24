package io.github.wksaurey.mosaic

/**
 * Layout data — direct port of the prototype's LAYOUTS / NUMGRID tables
 * (reference/grid24-proto.html, the executable spec). Data only; the gesture
 * grammar that interprets it lives in the engine.
 */
data class KeyDef(val pri: String, val sec: String? = null) // sec = merged-key hold secondary

object Layouts {
    // Build-time layout selection (v1 mechanism per CLAUDE.md; runtime switching = M6).
    const val DEFAULT = "optimal"

    private fun k(p: String, s: String? = null) = KeyDef(p, s)

    val ALPHA: Map<String, List<List<KeyDef>>> = mapOf(
        "qwerty" to listOf(
            listOf(k("q"), k("w"), k("e"), k("r"), k("t"), k("y")),
            listOf(k("x", "z"), k("c"), k("u", "v"), k("i"), k("o"), k("p")),
            listOf(k("a"), k("s"), k("d"), k("j"), k("k"), k("l")),
            listOf(k("f"), k("g"), k("h"), k("b"), k("n"), k("m")),
        ),
        "optimal" to listOf(
            listOf(k("x", "z"), k("p"), k("j"), k("v"), k("m"), k("c")),
            listOf(k("k", "q"), k("t"), k("y"), k("b"), k("r"), k("h")),
            listOf(k("g"), k("o"), k("e"), k("l"), k("u"), k("s")),
            listOf(k("d"), k("i"), k("a"), k("f"), k("n"), k("w")),
        ),
        "vowels" to listOf(
            listOf(k("x", "z"), k("v"), k("s"), k("u"), k("p"), k("j")),
            listOf(k("k", "q"), k("m"), k("i"), k("l"), k("c"), k("g")),
            listOf(k("b"), k("h"), k("o"), k("e"), k("t"), k("w")),
            listOf(k("d"), k("r"), k("a"), k("n"), k("f"), k("y")),
        ),
        "vbottom" to listOf(
            listOf(k("x", "z"), k("c"), k("r"), k("y"), k("p"), k("j")),
            listOf(k("g"), k("m"), k("t"), k("h"), k("d"), k("v")),
            listOf(k("w"), k("l"), k("a"), k("o"), k("n"), k("s")),
            listOf(k("f"), k("u"), k("e"), k("k", "q"), k("i"), k("b")),
        ),
    )

    /**
     * Alpha-layer positional holds, by GRID POSITION independent of which letter
     * sits there: digits in a phone-keypad shape on the right half, punctuation
     * on the bottom row (2026-07-17 addition — the prototype carried digits only).
     * Merged-key secondaries beat positional holds (sec || hold), so a merged key
     * sitting on a hold position shadows it: vbottom's k⁄q at row3-col3 eats the
     * comma there. Acceptable while vbottom isn't the daily layout — relocate the
     * comma if that changes.
     */
    val ALPHA_HOLDS: List<List<String?>> = listOf(
        listOf(null, null, null, "1", "2", "3"),
        listOf(null, null, null, "4", "5", "6"),
        listOf(null, null, null, "7", "8", "9"),
        listOf(null, "?", "'", ",", "0", "."),
    )

    /** Symbol layer (6×4): 24 primaries + 8 holds = every common symbol,
     *  nothing left over. Verified complete; don't add or move glyphs. */
    val SYM: List<List<KeyDef>> = listOf(
        listOf(k("!"), k("@"), k("#"), k("$"), k("%"), k("^", "&")),
        listOf(k("("), k(")"), k("["), k("]"), k("{"), k("}")),
        listOf(k("'", "`"), k("\"", "~"), k(";"), k(":"), k("-"), k("_")),
        listOf(k(",", "<"), k(".", ">"), k("?"), k("/", "\\"), k("=", "|"), k("+", "*")),
    )

    /** Number layer: 4-column calculator, operator column left, keypad right. */
    val NUM: List<List<KeyDef>> = listOf(
        listOf(k("/", "("), k("7"), k("8"), k("9")),
        listOf(k("*", ")"), k("4"), k("5"), k("6")),
        listOf(k("-", "%"), k("1"), k("2"), k("3")),
        listOf(k("+", "^"), k(".", ":"), k("0", ","), k("=", "$")),
    )
}
