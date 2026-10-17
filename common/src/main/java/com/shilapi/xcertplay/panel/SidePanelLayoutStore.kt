package com.shilapi.xcertplay.panel

import android.content.Context

/**
 * The panel's widget order and sizes, stored as one line: `id[SIZE];id[SIZE];…`. Only ids are kept, so a saved
 * layout stays valid as the catalog changes; an id the app no longer knows is dropped when it is read back.
 * Empty or unreadable falls back to the default (clock, battery, tyres) so the panel is never blank.
 */
object SidePanelLayoutStore {
    private const val PREFS = "diplay_side_panel"
    private const val KEY_LAYOUT = "widget_layout"

    fun load(context: Context): List<SidePanelWidget> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAYOUT, null) ?: return TileCatalog.defaultLayout
        val widgets = parse(raw)
        return widgets.ifEmpty { TileCatalog.defaultLayout }
    }

    fun save(context: Context, widgets: List<SidePanelWidget>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAYOUT, encode(widgets)).apply()
    }

    internal fun parse(raw: String): List<SidePanelWidget> = raw.split(';').mapNotNull { entry ->
        val sep = entry.indexOf('[')
        if (sep <= 0 || !entry.endsWith("]")) return@mapNotNull null
        val kind = entry.substring(0, sep)
        if (TileCatalog.byId[kind] == null) return@mapNotNull null
        val size = TileSize.entries.firstOrNull { it.name == entry.substring(sep + 1, entry.length - 1) }
            ?: TileCatalog.byId.getValue(kind).defaultSize
        SidePanelWidget(kind, size)
    }.distinctBy { it.kind }

    internal fun encode(widgets: List<SidePanelWidget>): String =
        widgets.joinToString(";") { "${it.kind}[${it.size.name}]" }
}
