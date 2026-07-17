package io.github.wksaurey.grid24

/**
 * Layout data — direct port of the prototype's LAYOUTS / NUMGRID tables
 * (reference/grid24-proto.html, the executable spec). Data only; the gesture
 * grammar that interprets it lives in the engine.
 */
data class KeyDef(val pri: String, val sec: String? = null) // sec = merged-key hold secondary

object Layouts {
    const val DEFAULT = "qwerty"

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
     * Alpha-layer positional digits: right-half keys (cols 3-5) carry hold-digits
     * in a phone-keypad shape by GRID POSITION, independent of which letter sits
     * there. Merged keys are always left-half in all four layouts precisely so
     * sec-vs-num never conflicts — preserve that invariant when editing layouts.
     */
    val NUMGRID: List<List<String?>> = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(null, "0", "."),
    )

    // SYM_ROWS / NUM_ROWS (symbol + calculator layers) land at M5.
}
