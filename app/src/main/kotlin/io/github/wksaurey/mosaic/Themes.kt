package io.github.wksaurey.mosaic

/**
 * Theme data — pure Kotlin (ARGB ints, no android.graphics) so engines stay
 * platform-free. Selected live via the settings lab; "slate" is the prototype's
 * palette and the default. Mockups these were tuned against: 2026-07-19
 * theme-lab renders (Slate / Terracotta / Azulejo).
 */
private fun c(hex: String): Int = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()

/** Per-key tile override (mosaic themes), keyed by alpha-layer key index. */
data class Tile(val fill: Int, val ink: Int, val holdAccent: Int)

data class Theme(
    val bg: Int,
    val surface: Int,        // plain key
    val surface2: Int,       // merged key / fn row default
    val line: Int,
    val ink: Int,
    val inkDim: Int,         // fn labels, merged secondaries
    val accent: Int,         // press flash / shift-armed fill
    val pressInk: Int,       // glyph color on accent fill
    val holdAccent: Int,     // positional digit/punct holds
    val tiles: Map<Int, Tile> = emptyMap(),
    /** Per-slot fn colors for the 4-key row; ignored when sizes mismatch. */
    val fnFills: List<Int>? = null,
    val fnInks: List<Int>? = null,
)

object Themes {
    const val DEFAULT = "slate"

    val ALL: Map<String, Theme> = linkedMapOf(
        "slate" to Theme(
            bg = c("191c22"), surface = c("22262e"), surface2 = c("2a2f39"),
            line = c("363c48"), ink = c("ece7d9"), inkDim = c("9a958a"),
            accent = c("e8873a"), pressInk = c("1a1408"), holdAccent = c("e8873a"),
        ),
        "terracotta" to Theme(
            bg = c("241812"), surface = c("a85b42"), surface2 = c("874634"),
            line = c("5f2f20"), ink = c("f8ecdc"), inkDim = c("f0d5bd"),
            accent = c("f0b95c"), pressInk = c("2b1409"), holdAccent = c("ffd986"),
        ),
        // "porcelain" (blue-on-white delft) tried and killed 2026-07-19 — didn't
        // land on glass. Light-theme nav plumbing kept for future attempts.
        "azulejo" to Theme(
            bg = c("0c3a40"), surface = c("2ec4b6"), surface2 = c("189e92"),
            line = c("082e30"), ink = c("06332e"), inkDim = c("d9fbf6"),
            accent = c("ffd166"), pressInk = c("4a3305"), holdAccent = c("fff4d6"),
            tiles = mapOf(
                1 to Tile(c("ffc53d"), c("4a3305"), c("7a4a08")),
                5 to Tile(c("ff6b35"), c("fff4e6"), c("ffe0b3")),
                8 to Tile(c("ff6b35"), c("fff4e6"), c("ffe0b3")),
                10 to Tile(c("ffc53d"), c("4a3305"), c("7a4a08")),
                14 to Tile(c("ffc53d"), c("4a3305"), c("7a4a08")),
                19 to Tile(c("ff6b35"), c("fff4e6"), c("ffe0b3")),
                21 to Tile(c("ffc53d"), c("4a3305"), c("7a4a08")),
            ),
            fnFills = listOf(c("ffc53d"), c("128078"), c("2ec4b6"), c("ff6b35")),
            fnInks = listOf(c("4a3305"), c("c9f5ef"), c("06332e"), c("fff4e6")),
        ),
    )
}
