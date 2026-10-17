package com.shilapi.xcertplay.panel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydPanelFields
import com.shilapi.xcertplay.hud.BydPanelRepository
import com.shilapi.xcertplay.hud.BydTier
import com.shilapi.xcertplay.hud.BydTyreReading
import com.shilapi.xcertplay.hud.BydTyreStatus
import java.util.Calendar
import kotlin.math.roundToInt

/** Colours the host passes in, so tiles follow the app theme without reading resources here. */
data class TilePalette(
    val secondary: Color,
    val primary: Color,
    val accent: Color,
    val warn: Color,
    val good: Color,
    /** A tile's translucent glass fill; the card behind it shows through faintly. */
    val tile: Color,
    /** A selected tile's fill, brighter than [tile]. */
    val tileEdit: Color,
    /** The inner hairline that gives a tile its frosted edge, light on dark and dark on light. */
    val tileBorder: Color,
)

/**
 * The side panel's surface: the tile grid the driver laid out, an editor over the same tiles, and the add
 * drawer. It reads [SidePanelModel]'s snapshot state and re-composes on a slow tick, or a fast one when a
 * live tile (pedals, speed) is placed, so the values update without the host rebuilding views. Column count
 * follows the panel's own width, so 1/3, 1/2 and 2/3 layouts differ; a long list scrolls rather than clipping.
 */
/** Grid metrics: one place for the panel's rhythm so density stays consistent. */
internal const val PANEL_ROW_H_DP = 60
internal const val PANEL_TALL_H_DP = 126
internal const val PANEL_HERO_H_DP = 118
internal const val PANEL_GAP_DP = 6

@Composable
fun SidePanelPanel(model: SidePanelModel, palette: TilePalette) {
    var drawerOpen by remember { mutableStateOf(false) }
    val drag = remember { DragState() }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { drag.panelBounds = it.boundsInWindow() },
    ) {
        val columns = SidePanelLayout.columns(maxWidth.value.roundToInt())
        val live = remember(model.widgets.toList()) { model.widgets.any { TileCatalog.byId[it.kind]?.tier() == BydTier.FAST } }
        val frame = remember { mutableIntStateOf(0) }
        LaunchedEffect(live) {
            while (true) {
                kotlinx.coroutines.delay(if (live) 500L else 5_000L)
                frame.intValue++
            }
        }
        val packed = remember(model.widgets.toList(), columns, frame.intValue, model.revision) {
            SidePanelLayout.packWithHero(model.widgets.toList(), columns)
        }
        val edgePx = with(density) { 60.dp.toPx() }
        val stepPx = with(density) { 24.dp.toPx() }
        if (model.editing) {
            SidePanelEditor(model, palette, packed, columns, drawerOpen, drag, scroll, edgePx, stepPx) { drawerOpen = it }
        } else {
            SidePanelGrid(model, palette, packed, columns, drag, scroll, edgePx, stepPx)
        }
        DropSlot(model, drag, palette)
        if (drawerOpen) AddDrawer(model, palette) { drawerOpen = false }
    }
}

/**
 * A tile's frosted surface: one continuous large corner, a translucent fill the card shows through,
 * and a hairline edge highlight. Drawn with plain shapes so it works on Android 10, where a real
 * backdrop blur (RenderEffect, API 31) is unavailable.
 */
internal fun Modifier.glassTile(palette: TilePalette, selected: Boolean = false): Modifier {
    val shape = RoundedCornerShape(22.dp)
    return this
        .clip(shape)
        .background(if (selected) palette.tileEdit else palette.tile)
        .border(BorderStroke(1.dp, palette.tileBorder), shape)
}

/** A hero card's surface: brighter fill and an accent edge so the queue's first tile reads as the focus. */
internal fun Modifier.heroTile(palette: TilePalette): Modifier {
    val shape = RoundedCornerShape(22.dp)
    return this
        .clip(shape)
        .background(palette.tileEdit)
        .border(BorderStroke(1.5.dp, palette.accent), shape)
}

@Composable
private fun SidePanelGrid(
    model: SidePanelModel,
    palette: TilePalette,
    packed: SidePanelLayout.Packed,
    columns: Int,
    drag: DragState,
    scroll: ScrollState,
    edgePx: Float,
    stepPx: Float,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
    ) {
        packed.hero?.let { hero ->
            val kind = TileCatalog.byId[hero.widget.kind]
            if (kind != null) Box(
                Modifier
                    .fillMaxWidth()
                    .height(PANEL_HERO_H_DP.dp)
                    .heroTile(palette)
                    .tileDrag(kind.id, model, drag, scroll, edgePx, stepPx),
            ) {
                if (kind.format == TileFormat.VALUE || kind.format == TileFormat.BATTERY || kind.format == TileFormat.CLOCK) {
                    HeroValueTile(kind, palette)
                } else {
                    TileContent(kind, hero, palette)
                }
                if (drag.liftedKind != null) DragAffordances(palette, hero.size)
            }
        }
        packed.rest.groupBy { it.row }.forEach { (_, row) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp)) {
                row.sortedBy { it.col }.forEach { item ->
                    val kind = TileCatalog.byId[item.widget.kind]
                    if (kind != null) Box(
                        Modifier
                            .weight(item.colSpan.toFloat())
                            .height(if (item.rowSpan > 1) PANEL_TALL_H_DP.dp else PANEL_ROW_H_DP.dp)
                            .glassTile(palette)
                            .tileDrag(kind.id, model, drag, scroll, edgePx, stepPx),
                    ) {
                        TileContent(kind, item, palette)
                        if (drag.liftedKind != null) DragAffordances(palette, item.size)
                    }
                }
                val used = row.sumOf { it.colSpan }
                if (used < columns) Spacer(Modifier.weight((columns - used).toFloat()))
            }
        }
    }
}

/**
 * Decorative edit affordances on every tile while a lift is live, so the browse surface shows the
 * same × and size chip the frame calls for; the real editor opens when the drop lands.
 */
@Composable
private fun BoxScope.DragAffordances(palette: TilePalette, size: TileSize) {
    Text(
        "×",
        color = palette.warn,
        fontSize = 15.sp,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 5.dp, end = 7.dp)
            .clip(CircleShape),
    )
    Text(
        sizeLabel(size),
        color = palette.accent,
        fontSize = 11.sp,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 7.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(999.dp))
            .border(BorderStroke(1.dp, palette.tileBorder), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The hero's content for single-value tiles: label line, oversized value with inline unit, subline and arc. */
@Composable
private fun HeroValueTile(kind: TileKind, palette: TilePalette) {
    val readout = readoutFor(kind)
    val accent = accentColor(readout, palette)
    val strip = readout.present && !readout.stale && readout.accent != TileAccent.NONE
    Row(Modifier.fillMaxSize()) {
        if (strip) {
            Box(
                Modifier.width(3.dp).fillMaxHeight().padding(vertical = 22.dp)
                    .clip(RoundedCornerShape(3.dp)).background(accent),
            )
        }
        Column(Modifier.fillMaxSize().padding(start = if (strip) 14.dp else 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp)) {
            Text(string(kind.titleRes), color = palette.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            val primary = readout.primaryRes
            val primaryText = if (primary != null) string(primary) else readout.primary
            val unitColor = if (readout.accent == TileAccent.NONE) palette.secondary else accent
            Text(
                valueWithUnit(primaryText, readout.unit, unitColor),
                color = accent,
                fontSize = 36.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            readout.secondary?.let { Text(it, color = palette.secondary, fontSize = 13.sp, maxLines = 1) }
            readout.fraction?.let { fraction ->
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(palette.secondary.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(accent))
                }
            }
        }
    }
}

/** The group icons, reused by tile headers and the drawer. */
internal fun TileGroup.iconRes(): Int = when (this) {
    TileGroup.CLOCK -> R.drawable.ic_dp_clock
    TileGroup.ENERGY -> R.drawable.ic_dp_automation
    TileGroup.DRIVE -> R.drawable.ic_dp_dashboard
    TileGroup.CLIMATE -> R.drawable.ic_dp_controls
    TileGroup.HYBRID -> R.drawable.ic_dp_vehicle
    TileGroup.TECH -> R.drawable.ic_dp_advanced
    TileGroup.LIGHTS -> R.drawable.ic_dp_day
    TileGroup.SAFETY -> R.drawable.ic_dp_permissions
    TileGroup.SYSTEM -> R.drawable.ic_dp_display
    TileGroup.BODY -> R.drawable.ic_dp_car
}

@Composable
private fun TileContent(kind: TileKind, item: SidePanelLayout.Placed, palette: TilePalette) {
    if (kind.format == TileFormat.TYRES) { TyresTile(kind, item, palette); return }
    if (kind.format == TileFormat.SYSTEM) { SystemTile(kind, item, palette); return }
    if (kind.format == TileFormat.MULTI) { MultiTile(kind, item, palette); return }
    ValueTile(kind, item, palette)
}

/**
 * A single-value tile: label and value share one line in a short row; a tall tile stacks a 10 sp
 * label over a 24 sp value with the subline and fraction bar beneath. Status strip and stale
 * dimming follow the same rules as before.
 */
@Composable
private fun ValueTile(kind: TileKind, item: SidePanelLayout.Placed, palette: TilePalette) {
    val readout = readoutFor(kind)
    val accent = accentColor(readout, palette)
    val strip = readout.present && !readout.stale && readout.accent != TileAccent.NONE
    val primary = readout.primaryRes
    val primaryText = if (primary != null) string(primary) else readout.primary
    val unitColor = if (readout.accent == TileAccent.NONE) palette.secondary else accent
    Row(Modifier.fillMaxSize()) {
        if (strip) {
            Box(
                Modifier.width(3.dp).fillMaxHeight().padding(vertical = 12.dp)
                    .clip(RoundedCornerShape(3.dp)).background(accent),
            )
        }
        if (item.rowSpan == 1) {
            Row(
                Modifier.fillMaxSize().padding(start = if (strip) 8.dp else 10.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(string(kind.titleRes), color = palette.secondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Text(
                    valueWithUnit(primaryText, readout.unit, unitColor),
                    color = accent,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(Modifier.fillMaxSize().padding(start = if (strip) 8.dp else 10.dp, top = 8.dp, end = 10.dp, bottom = 8.dp)) {
                Text(string(kind.titleRes), color = palette.secondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    valueWithUnit(primaryText, readout.unit, unitColor),
                    color = accent,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                readout.secondary?.let { Text(it, color = palette.secondary, fontSize = 12.sp, maxLines = 1) }
                readout.fraction?.let { fraction ->
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(palette.secondary.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(accent))
                    }
                }
            }
        }
    }
}

/**
 * The big value with its unit set small on the same line, so the number keeps the visual weight:
 * "120 km/h", "63%". The percent sign hugs its number; other units get a space.
 */
private fun valueWithUnit(primary: String, unit: String, unitColor: Color): AnnotatedString {
    if (unit.isEmpty()) return AnnotatedString(primary)
    val glue = if (unit == "%") "" else " "
    return buildAnnotatedString {
        append(primary)
        withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, color = unitColor)) {
            append(glue + unit)
        }
    }
}

/** A grouped tile: each signal shown as a value over its short position label (FL, H/V, F/R...), laid out 2+rest when tall. */
@Composable
private fun MultiTile(kind: TileKind, item: SidePanelLayout.Placed, palette: TilePalette) {
    val reads = kind.fields.associateWith { readValue(it) }
    val cells = TileFormatter.cells(kind.fields, kind.labels, reads.mapValues { it.value.first })
        .mapIndexed { index, cell ->
            if (reads.getValue(kind.fields[index]).second) cell.copy(stale = true) else cell
        }
    val rows = if (item.rowSpan > 1) listOf(cells.take(2), cells.drop(2)).filter { it.isNotEmpty() } else listOf(cells)
    val big = item.rowSpan > 1
    Column(Modifier.fillMaxSize().padding(if (big) 10.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(if (big) 5.dp else 1.dp)) {
        Text(string(kind.titleRes), color = palette.secondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { cell ->
                    val value = cell.valueRes
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (value != null) string(value) else cell.value,
                            color = when {
                                cell.stale -> palette.secondary
                                cell.accent == TileAccent.WARN -> palette.warn
                                cell.accent == TileAccent.GOOD -> palette.good
                                cell.accent == TileAccent.LIVE -> palette.accent
                                else -> palette.primary
                            },
                            fontSize = if (big) 20.sp else 17.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (cell.label.isNotEmpty()) Text(cell.label, color = palette.secondary, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * The head unit's own load, read locally (no adb): CPU share and memory share each with a bar, the fastest
 * core's frequency, and the hottest thermal zone when the kernel lets a normal app read it. GPU is not shown:
 * Android 10 has no API a plain app can use to read it.
 */
@Composable
private fun SystemTile(kind: TileKind, item: SidePanelLayout.Placed, palette: TilePalette) {
    val reading = SystemStatus.latest()
    Column(Modifier.fillMaxSize().padding(if (item.rowSpan > 1) 10.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(string(kind.titleRes), color = palette.secondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (reading == null) {
            Text("—", color = palette.primary, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        } else {
            loadRow("CPU", "${reading.cpuPercent.roundToInt()}%", reading.cpuPercent / 100.0, palette, item.size == TileSize.LARGE)
            val memFraction = if (reading.memTotalGb > 0.0) reading.memUsedGb / reading.memTotalGb else 0.0
            loadRow("RAM", "%.1f/%.0f".format(reading.memUsedGb, reading.memTotalGb), memFraction, palette, item.size == TileSize.LARGE)
            val detail = buildList {
                reading.coreFreqMhz.maxOrNull()?.let { add(it / 1000.0 to "GHz") }
                reading.tempC?.let { add(it to "°C") }
            }
            if (detail.isNotEmpty()) {
                Text(
                    detail.joinToString(" · ") { (value, unit) -> if (unit == "GHz") "%.2f GHz".format(value) else "%.0f°C".format(value) },
                    color = palette.secondary, fontSize = 12.sp, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun loadRow(label: String, value: String, fraction: Double, palette: TilePalette, big: Boolean) {
    val hot = fraction >= 0.85
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = palette.secondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(value, color = if (hot) palette.warn else palette.primary, fontSize = if (big) 17.sp else 14.sp, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(palette.secondary.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat()).fillMaxHeight()
                .clip(RoundedCornerShape(3.dp)).background(if (hot) palette.warn else palette.accent))
        }
    }
}

/** The 2×2 tyre block the panel showed before, kept as one tile: front pair on top, rear pair below. */
@Composable
private fun TyresTile(kind: TileKind, item: SidePanelLayout.Placed, palette: TilePalette) {
    val tyres = BydTyreStatus.latest()
    val big = item.size == TileSize.LARGE || item.size == TileSize.TALL
    Column(Modifier.fillMaxSize().padding(if (big) 10.dp else 6.dp)) {
        Text(string(kind.titleRes), color = palette.secondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(if (big) 6.dp else 3.dp))
        if (tyres == null) {
            Text("—", color = palette.primary, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tyrePair(tyres, 0, 1, palette, big)
                tyrePair(tyres, 2, 3, palette, big)
            }
        }
    }
}

@Composable
private fun tyrePair(tyres: BydTyreReading, left: Int, right: Int, palette: TilePalette, big: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        tyreCell(tyres, left, palette, big)
        tyreCell(tyres, right, palette, big)
    }
}

@Composable
private fun tyreCell(tyres: BydTyreReading, index: Int, palette: TilePalette, big: Boolean) {
    val pressure = tyres.pressureKpa.getOrNull(index)?.let { "$it" } ?: "—"
    Column {
        Text(pressure, color = palette.primary, fontSize = if (big) 18.sp else 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        tyres.temperatureC.getOrNull(index)?.let { if (big) Text("$it°C", color = palette.secondary, fontSize = 11.sp, maxLines = 1) }
    }
}

private fun accentColor(readout: TileReadout, palette: TilePalette): Color = when {
    readout.stale -> palette.secondary
    else -> when (readout.accent) {
        TileAccent.WARN -> palette.warn
        TileAccent.GOOD -> palette.good
        TileAccent.LIVE -> palette.accent
        TileAccent.NONE -> if (readout.present) palette.primary else palette.secondary
    }
}

/**
 * A field's value and whether it is only a kept-but-stale reading: [BydPanelRepository.stale] serves
 * values past the fresh window so a quiet field dims rather than blanking while reads are down.
 */
private fun readValue(id: String): Pair<Double?, Boolean> {
    BydPanelRepository.latest(id)?.let { return it to false }
    BydPanelRepository.stale(id)?.let { return it to true }
    return null to false
}

/** The value a tile shows, read fresh so each recomposition (the live tick or the model revision) picks it up. */
private fun readoutFor(kind: TileKind): TileReadout {
    val stale = kind.fields.firstOrNull()?.let { readValue(it).second } ?: false
    val readout = when (kind.format) {
        TileFormat.CLOCK -> {
            val now = Calendar.getInstance()
            TileReadout("%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)))
        }
        TileFormat.BATTERY -> {
            // The built-in card has no field list of its own: it shows SOC over range from the repository.
            val (soc, socStale) = readValue("soc")
            val (range, _) = readValue("evRange")
            TileReadout(
                if (soc == null) "—" else "${soc.roundToInt()}%",
                unit = if (range == null) "" else "· ${range.roundToInt()} km",
                accent = if (soc != null && soc <= 15) TileAccent.WARN else TileAccent.NONE,
                present = soc != null,
                stale = soc != null && socStale,
            )
        }
        TileFormat.TYRES -> TileReadout("—", present = BydTyreStatus.latest() != null)
        else -> {
            val values = kind.fields.associateWith { readValue(it).first }
            val base = if (kind.composite) {
                TileFormatter.composite(kind.fields.first(), values[kind.fields.first()], kind.fields.getOrNull(1), values[kind.fields.getOrNull(1)])
            } else {
                TileFormatter.value(kind.fields.firstOrNull() ?: "", values.values.firstOrNull())
            }
            if (stale) base.copy(stale = true) else base
        }
    }
    return readout
}

/** The tier to poll for a tile: fast if any of its signals is, slow only when all are. */
private fun TileKind.tier(): BydTier? {
    val tiers = fields.mapNotNull { BydPanelFields.byId[it]?.tier }
    return when {
        tiers.isEmpty() -> null
        tiers.any { it == BydTier.FAST } -> BydTier.FAST
        tiers.all { it == BydTier.SLOW } -> BydTier.SLOW
        else -> BydTier.NORMAL
    }
}

@Composable
internal fun groupName(group: TileGroup): String = string(when (group) {
    TileGroup.CLOCK -> R.string.panel_group_clock
    TileGroup.ENERGY -> R.string.panel_group_energy
    TileGroup.DRIVE -> R.string.panel_group_drive
    TileGroup.CLIMATE -> R.string.panel_group_climate
    TileGroup.HYBRID -> R.string.panel_group_hybrid
    TileGroup.TECH -> R.string.panel_group_tech
    TileGroup.LIGHTS -> R.string.panel_group_lights
    TileGroup.SAFETY -> R.string.panel_group_safety
    TileGroup.SYSTEM -> R.string.panel_group_system
    TileGroup.BODY -> R.string.panel_group_body
})

/** A string resource, read from the composition's context. */
@Composable
internal fun string(res: Int): String = LocalContext.current.getString(res)
