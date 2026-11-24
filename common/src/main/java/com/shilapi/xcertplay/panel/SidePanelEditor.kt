package com.shilapi.xcertplay.panel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shilapi.xcertplay.host.R

/**
 * The panel's edit mode over the same hero-and-grid surface the viewer shows: a hint bar with the
 * drawer entry and Done, per-tile remove and size chips, and the add drawer grouped by what the
 * driver cares about. Reordering is a long-press drag (SidePanelDrag), so tiles carry no tap-swap.
 * Split from SidePanelCompose so the surface keeps one concern per file.
 */
@Composable
internal fun SidePanelEditor(
    model: SidePanelModel,
    palette: TilePalette,
    packed: SidePanelLayout.Packed,
    columns: Int,
    drawerOpen: Boolean,
    drag: DragState,
    scroll: ScrollState,
    edgePx: Float,
    stepPx: Float,
    setDrawerOpen: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(string(R.string.panel_edit_hint), color = palette.secondary, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 2)
            Text("+", color = palette.accent, fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier
                .clip(CircleShape).padding(horizontal = 10.dp, vertical = 4.dp)
                .combinedClickable(onClick = { setDrawerOpen(true) }))
            Text(string(R.string.panel_done), color = palette.accent, fontSize = 14.sp, modifier = Modifier
                .clip(RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
                .background(palette.tile).combinedClickable(onClick = { setDrawerOpen(false); model.stopEditing() }))
        }
        packed.hero?.let { hero ->
            val kind = TileCatalog.byId[hero.widget.kind] ?: return@let
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(PANEL_HERO_H_DP.dp)
                    .heroTile(palette)
                    .tileDrag(kind.id, model, drag, scroll, edgePx, stepPx),
            ) { EditTileControls(kind, hero.size, palette, model) }
        }
        packed.rest.groupBy { it.row }.forEach { (_, row) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp)) {
                row.sortedBy { it.col }.forEach { item ->
                    val kind = TileCatalog.byId[item.widget.kind]
                    if (kind != null) {
                        Box(
                            Modifier
                                .weight(item.colSpan.toFloat())
                                .height(if (item.rowSpan > 1) PANEL_TALL_H_DP.dp else PANEL_ROW_H_DP.dp)
                                .glassTile(palette)
                                .tileDrag(kind.id, model, drag, scroll, edgePx, stepPx),
                        ) { EditTileControls(kind, item.size, palette, model) }
                    }
                }
                val used = row.sumOf { it.colSpan }
                if (used < columns) Spacer(Modifier.weight((columns - used).toFloat()))
            }
        }
    }
}

/** One tile's edit affordances: identity row with remove, and the size chip beneath. */
@Composable
private fun EditTileControls(kind: TileKind, size: TileSize, palette: TilePalette, model: SidePanelModel) {
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painterResource(kind.group.iconRes()),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(palette.secondary),
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(string(kind.titleRes), color = palette.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("×", color = palette.warn, fontSize = 16.sp, modifier = Modifier
                .clip(CircleShape).padding(4.dp).combinedClickable(onClick = { model.remove(kind.id) }))
        }
        Spacer(Modifier.height(6.dp))
        Text(sizeLabel(size), color = palette.accent, fontSize = 12.sp, modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(BorderStroke(1.dp, palette.tileBorder), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .combinedClickable(onClick = { model.cycleSize(kind.id) }))
    }
}

@Composable
internal fun AddDrawer(model: SidePanelModel, palette: TilePalette, onDismiss: () -> Unit) {
    val placedIds = model.widgets.map { it.kind }.toSet()
    Column(Modifier.fillMaxSize().background(palette.tileEdit).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(string(R.string.panel_add_tiles), color = palette.primary, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("×", color = palette.secondary, fontSize = 22.sp, modifier = Modifier
                .clip(CircleShape).padding(6.dp).combinedClickable(onClick = onDismiss))
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            TileCatalog.grouped().forEach { (group, kinds) ->
                if (kinds.isEmpty()) return@forEach
                Spacer(Modifier.height(12.dp))
                Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource(group.iconRes()),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(palette.secondary),
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(groupName(group), color = palette.secondary, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    kinds.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { kind ->
                                val added = kind.id in placedIds
                                Box(Modifier
                                    .glassTile(palette, selected = added)
                                    .combinedClickable(onClick = { if (added) model.remove(kind.id) else model.add(kind.id) })
                                    .padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(string(kind.titleRes), color = if (added) palette.secondary else palette.primary, fontSize = 13.sp)
                                }
                            }
                            repeat((4 - row.size).coerceAtLeast(0)) { Spacer(Modifier) }
                        }
                    }
                }
            }
        }
    }
}

internal fun sizeLabel(size: TileSize): String = when (size) {
    TileSize.SMALL -> "S"; TileSize.WIDE -> "W"; TileSize.TALL -> "T"; TileSize.LARGE -> "L"
}
