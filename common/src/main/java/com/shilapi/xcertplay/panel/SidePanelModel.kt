package com.shilapi.xcertplay.panel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shilapi.xcertplay.hud.BydPanelFields
import com.shilapi.xcertplay.hud.BydPanelRepository
import com.shilapi.xcertplay.hud.BydTier

/**
 * The side panel's widget state and actions, shared between the panel's ComposeView and the host activity.
 * [widgets] drives the grid; every mutation is saved, so the layout survives a restart. [revision] bumps
 * each panel refresh tick so the composition re-reads the values the repository cached; the repository
 * only polls fields a placed widget needs, on that field's tier.
 */
class SidePanelModel(initial: List<SidePanelWidget>) {
    val widgets = mutableStateListOf<SidePanelWidget>().apply { addAll(initial) }
    var editing by mutableStateOf(false)
        private set
    var revision by mutableStateOf(0)
        private set

    /** The activity sets this so actions can persist; the model holds no Context beyond this reference. */
    var context: Context? = null

    fun startEditing() {
        editing = true
    }

    fun stopEditing() {
        editing = false
    }

    /** Called by the host on its refresh tick while the panel is open. */
    fun refresh(context: Context) {
        this.context = context.applicationContext
        BydPanelRepository.subscribe(this.context!!, neededTiers())
        BydPanelRepository.unsubscribeUnused()
        revision++
    }

    /** Called when day/night changes so the tiles repaint with the new palette. */
    fun refreshTheme() {
        revision++
    }

    fun closed() {
        BydPanelRepository.stopAll()
    }

    fun remove(kind: String) {
        widgets.removeAll { it.kind == kind }
        persist()
    }

    /** Move the widget at [from] to [to] in queue order (index 0 is the hero) and persist. */
    fun move(from: Int, to: Int) {
        if (from == to || from !in widgets.indices) return
        val moved = widgets.removeAt(from)
        widgets.add(to.coerceIn(0, widgets.size), moved)
        persist()
    }

    fun add(kind: String) {
        if (widgets.any { it.kind == kind }) return
        val size = TileCatalog.byId[kind]?.defaultSize ?: TileSize.SMALL
        widgets.add(SidePanelWidget(kind, size))
        persist()
    }

    fun cycleSize(kind: String) {
        val widget = widgets.firstOrNull { it.kind == kind } ?: return
        val order = TileSize.entries
        widget.size = order[(order.indexOf(widget.size) + 1) % order.size]
        persist()
    }

    private fun persist() {
        context?.let { SidePanelLayoutStore.save(it, widgets.toList()) }
    }

    /** Field id → tier for every BYD signal a placed widget reads; the battery card also needs SOC and range. */
    private fun neededTiers(): Map<String, BydTier> {
        val needed = LinkedHashMap<String, BydTier>()
        fun want(id: String, tier: BydTier) {
            val current = needed[id]
            needed[id] = if (current == null || tier < current) tier else current
        }
        for (widget in widgets) {
            TileCatalog.byId[widget.kind]?.fields?.forEach { id ->
                BydPanelFields.byId[id]?.let { signal -> want(id, signal.tier) }
            }
        }
        if (widgets.any { it.kind == "battery" }) {
            BydPanelFields.byId["soc"]?.let { want("soc", it.tier) }
            BydPanelFields.byId["evRange"]?.let { want("evRange", it.tier) }
        }
        return needed
    }
}
