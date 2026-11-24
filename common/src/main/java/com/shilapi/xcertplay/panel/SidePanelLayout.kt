package com.shilapi.xcertplay.panel

/**
 * A row of an iOS-style tile grid. Tiles flow left to right from a cursor; a tile's rowSpan reserves the
 * same columns in the rows it spans. No Android types, so packing is unit-tested on narrow and wide panels.
 */
object SidePanelLayout {
    /**
     * Columns a panel of this width shows. Tiles aim for a readable ~160–280 dp: 1 column for a sliver,
     * up to 4 for a wide two-thirds panel. A portrait screen's bottom strip is full width, so it gets the
     * most columns too; the narrow side band on a portrait screen gets one.
     */
    fun columns(widthDp: Int): Int = when {
        widthDp >= 600 -> 4
        widthDp >= 420 -> 3
        widthDp >= 260 -> 2
        else -> 1
    }

    /**
     * Place [widgets] in order on a [cols]-wide grid. Returns only the tiles that fit; the last tiles of a
     * long list drop off when the panel is narrow, which the editor surfaces by scrolling the panel instead.
     */
    fun pack(widgets: List<SidePanelWidget>, cols: Int): List<Placed> {
        val occupied = mutableMapOf<Int, MutableSet<Int>>() // row -> taken columns
        val placements = ArrayList<Placed>(widgets.size)
        var cursor = 0
        for (widget in widgets) {
            val span = minOf(widget.size.colSpan, cols)
            val height = widget.size.rowSpan
            var row = cursor
            while (true) {
                val start = firstRun(row, span, height, occupied, cols)
                if (start >= 0) {
                    for (r in row until row + height) {
                        val line = occupied.getOrPut(r) { mutableSetOf() }
                        for (c in start until start + span) line += c
                    }
                    cursor = row
                    placements += Placed(widget, start, row, span, height)
                    break
                }
                row++
                if (row > MAX_SCAN_ROWS) break
            }
        }
        return placements
    }

    /** The first column in [row] with a free run of [span] cells across the [height] rows it would span. */
    private fun firstRun(
        row: Int,
        span: Int,
        height: Int,
        occupied: Map<Int, Set<Int>>,
        cols: Int,
    ): Int {
        for (start in 0..cols - span) {
            var fits = true
            for (r in row until row + height) {
                val line = occupied[r] ?: continue
                for (c in start until start + span) {
                    if (c in line) { fits = false; break }
                }
                if (!fits) break
            }
            if (fits) return start
        }
        return -1
    }

    private const val MAX_SCAN_ROWS = 64

    /** The hero card and the grid beneath it: [hero] renders full-width on its own, [rest] packs normally. */
    data class Packed(val hero: Placed?, val rest: List<Placed>)

    /** [widgets]'s first widget is the hero (full-width, rendered alone); the rest pack on the grid. */
    fun packWithHero(widgets: List<SidePanelWidget>, cols: Int): Packed =
        if (widgets.isEmpty()) Packed(null, emptyList())
        else Packed(Placed(widgets[0], 0, 0, cols, 1), pack(widgets.drop(1), cols))

    data class Placed(
        val widget: SidePanelWidget,
        val col: Int,
        val row: Int,
        val colSpan: Int,
        val rowSpan: Int,
    ) {
        val size: TileSize get() = widget.size
    }
}
