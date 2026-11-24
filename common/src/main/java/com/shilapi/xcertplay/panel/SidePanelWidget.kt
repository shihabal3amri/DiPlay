package com.shilapi.xcertplay.panel

/** How wide and tall a tile is on the grid, in cells. */
enum class TileSize(val colSpan: Int, val rowSpan: Int) {
    SMALL(1, 1),
    WIDE(2, 1),
    TALL(1, 2),
    LARGE(2, 2),
}

/** How a tile draws: one of the panel's original cards, the head unit's own load, or BYD signals the repository reads. */
enum class TileFormat { CLOCK, BATTERY, TYRES, SYSTEM, VALUE, MULTI }

/** Group headings of the add drawer, so tiles are picked by what the driver cares about. */
enum class TileGroup { CLOCK, ENERGY, DRIVE, CLIMATE, HYBRID, TECH, BODY, LIGHTS, SAFETY, SYSTEM }

/**
 * One widget the panel knows: a stable id, how it is drawn, which BYD signals it needs (empty for a
 * built-in card), its default size and its place in the add drawer. A placed widget stores only this id,
 * so the saved layout stays valid as the catalog grows.
 */
data class TileKind(
    val id: String,
    val titleRes: Int,
    val format: TileFormat,
    /** Field ids, primary first. A VALUE tile shows the primary big and any second as a subline. */
    val fields: List<String> = emptyList(),
    val defaultSize: TileSize = TileSize.SMALL,
    val group: TileGroup = TileGroup.TECH,
    val composite: Boolean = false,
    /** For a MULTI tile, the short label under each field's value (position codes like FL/RL, not localised). */
    val labels: List<String> = emptyList(),
)

/** One widget placed on the panel: which kind, and how big the driver made it. */
data class SidePanelWidget(val kind: String, var size: TileSize)
