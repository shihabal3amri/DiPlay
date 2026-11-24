package com.shilapi.xcertplay.panel

import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydPanelField
import com.shilapi.xcertplay.hud.BydPanelFields
import kotlin.math.roundToInt

/** Colour intent a tile uses so the Compose layer can tint it without knowing the field. */
enum class TileAccent { NONE, GOOD, WARN, LIVE }

/**
 * A rendered tile: a big value, a unit, an optional subline, and an optional 0..1 bar (pedals, light).
 * [primaryRes] is the localised text for a status word: the panel shows it, while [primary] keeps the
 * English token so tests and logs stay deterministic. [stale] marks a kept-but-no-longer-fresh reading
 * the panel dims instead of blanking.
 */
data class TileReadout(
    val primary: String,
    val unit: String = "",
    val secondary: String? = null,
    val fraction: Float? = null,
    val accent: TileAccent = TileAccent.NONE,
    val present: Boolean = true,
    val primaryRes: Int? = null,
    val stale: Boolean = false,
) {
    companion object {
        val EMPTY = TileReadout("—", present = false)
    }
}

/** One labelled cell of a MULTI tile: a short status/value token plus its position/role label. */
data class TileCell(
    val value: String,
    val label: String,
    val accent: TileAccent = TileAccent.NONE,
    val valueRes: Int? = null,
    val stale: Boolean = false,
) {
    companion object {
        val EMPTY = TileCell("—", "")
    }
}

/**
 * Turns raw signal values into display text. Values are already decoded to physical units by the repository;
 * this only chooses the format and rounding for each field. Pure, so the formatting is unit-tested without a
 * head unit. Composite tiles pass their primary and secondary values together. Enum-like signals (doors, belts,
 * climate modes, lights) carry both their English token in [TileReadout.primary] and the localised
 * [TileReadout.primaryRes] the panel renders; gear letters (P/R/N/D) are locale-neutral.
 */
object TileFormatter {
    fun value(id: String, v: Double?): TileReadout {
        if (v == null) return TileReadout.EMPTY
        val field = BydPanelFields.byId[id] ?: return TileReadout.EMPTY
        return when (id) {
            "soc", "fuelPercent", "soh" -> TileReadout(v.roundToInt().toString(), "%", accent = if (v <= 15) TileAccent.WARN else TileAccent.NONE)
            "remainKwh" -> if (v <= 0.0) TileReadout.EMPTY else TileReadout(one(v), "kWh")
            "evRange", "fuelRange" -> TileReadout(v.roundToInt().toString(), "km")
            "avgConsumption" -> TileReadout(one(v), field.unit())
            "odometer" -> TileReadout(thousands(v.roundToInt()), "km")
            "speed" -> TileReadout(v.roundToInt().toString(), "km/h")
            "gear" -> TileReadout(gear(v.toInt()), accent = TileAccent.LIVE)
            "batteryPower" -> TileReadout(if (v > 0) "+" + one(v) else one(v), "kW", accent = if (v < 0) TileAccent.GOOD else TileAccent.NONE)
            "accelPedal" -> TileReadout(v.roundToInt().toString(), "%", fraction = (v / 100f).toFloat().coerceIn(0f, 1f))
            "brakePedal" -> TileReadout(v.roundToInt().toString(), "%", fraction = (v / 100f).toFloat().coerceIn(0f, 1f), accent = if (v > 0) TileAccent.WARN else TileAccent.NONE)
            "maxCellVoltage", "minCellVoltage" -> TileReadout(three(v), "V")
            "maxBatteryTemp", "minBatteryTemp", "outsideTemp", "insideTemp", "acTemp",
            "motorTempFront", "motorTempRear", "inverterTempFront", "inverterTempRear" -> TileReadout(v.roundToInt().toString(), "°C")
            "hvVoltage" -> TileReadout(v.roundToInt().toString(), "V")
            "hvCurrent" -> TileReadout(one(v), "A", accent = if (v < 0) TileAccent.GOOD else TileAccent.NONE)
            "motorCurrentFront", "motorCurrentRear" -> TileReadout(signedOne(v), "A")
            "voltage12v" -> TileReadout(one(v), "V")
            "lightLevel" -> TileReadout(v.roundToInt().toString(), fraction = (v / 5f).toFloat().coerceIn(0f, 1f))
            "engineRpm" -> TileReadout(v.roundToInt().toString(), "rpm", fraction = (v / 7000.0).toFloat().coerceIn(0f, 1f))
            "motorRpmFront", "motorRpmRear" -> TileReadout(v.roundToInt().toString(), "rpm", fraction = (v / 20000.0).toFloat().coerceIn(0f, 1f))
            "insulationKohm" -> TileReadout(v.roundToInt().toString(), "kΩ")
            "compressorW" -> TileReadout(v.roundToInt().toString(), "W")
            "workMode" -> workMode(v.toInt())
            "driveMode" -> driveMode(v.toInt())
            "powerState" -> powerState(v.toInt())
            "fanLevel" -> TileReadout(v.roundToInt().toString())
            "acStatus" -> onOff(v)
            "acDefrostFront" -> onOff(v)
            "autoWipers" -> onOff(v)
            "lightLow", "lightHigh", "lightSide", "drl" -> onOff(v)
            "acCirc" -> TileReadout(
                if (v == 1.0) "REC" else "EXT",
                primaryRes = if (v == 1.0) R.string.panel_air_recirc else R.string.panel_air_fresh,
                accent = TileAccent.LIVE,
            )
            "acCtrlMode" -> TileReadout(
                if (v == 0.0) "AUTO" else "MAN",
                primaryRes = if (v == 0.0) R.string.panel_air_auto else R.string.panel_air_manual,
                accent = TileAccent.LIVE,
            )
            "acWindMode" -> windMode(v.toInt())
            "turnSignal" -> TileReadout(turnSignal(v.toInt()), accent = if (v == 6.0) TileAccent.WARN else TileAccent.LIVE)
            "seatHeatDriver", "seatVentDriver", "seatHeatPassenger", "seatVentPassenger" -> level(v)
            "doorFL", "doorFR", "doorRL", "doorRR", "hood" -> openClosed(v)
            "windowFL", "windowFR", "windowRL", "windowRR", "sunroof" ->
                if (v <= 0.0) TileReadout("Closed", primaryRes = R.string.panel_state_closed)
                else TileReadout("${v.roundToInt()}%")
            "trunk", "frontTrunk" -> trunk(v.toInt())
            "lockFL" -> TileReadout(
                if (v == 2.0) "LOCK" else "OPEN",
                primaryRes = if (v == 2.0) R.string.panel_state_locked else R.string.panel_state_unlocked,
                accent = if (v == 2.0) TileAccent.GOOD else TileAccent.WARN,
            )
            "seatbeltFL", "seatbeltFR", "seatbeltRL", "seatbeltRM", "seatbeltRR" -> TileReadout(
                if (v == 1.0) "OK" else "—",
                primaryRes = if (v == 1.0) R.string.panel_state_ok else null,
                accent = if (v == 1.0) TileAccent.GOOD else TileAccent.WARN,
            )
            "occupancyFL", "occupancyFR", "occupancyRL", "occupancyRM", "occupancyRR" -> TileReadout(if (v == 2.0) "•" else "—", accent = if (v == 2.0) TileAccent.LIVE else TileAccent.NONE)
            "wiperRelay" -> TileReadout(
                if (v != 0.0) "ON" else "—",
                primaryRes = if (v != 0.0) R.string.panel_state_on else null,
                accent = if (v != 0.0) TileAccent.LIVE else TileAccent.NONE,
            )
            "keyBatteryStatus" -> TileReadout(
                if (v == 0.0) "OK" else "LOW",
                primaryRes = if (v == 0.0) R.string.panel_state_ok else R.string.panel_state_low,
                accent = if (v == 0.0) TileAccent.GOOD else TileAccent.WARN,
            )
            "fuelTotalL" -> TileReadout(one(v), "L")
            "mileageHevKm", "mileageEvKm" -> TileReadout(thousands(v.roundToInt()), "km")
            else -> TileReadout(one(v), field.unit())
        }
    }

    /** A MULTI tile: each field's value with its label, dropped cells shown as a dash. */
    fun cells(fields: List<String>, labels: List<String>, values: Map<String, Double?>): List<TileCell> =
        fields.mapIndexed { index, id ->
            val readout = value(id, values[id])
            TileCell(readout.primary, labels.getOrElse(index) { "" }, readout.accent, readout.primaryRes)
        }

    /** A composite tile: primary field's value, with the secondary shown small beneath it. */
    fun composite(primaryId: String, primary: Double?, secondaryId: String?, secondary: Double?): TileReadout {
        val main = value(primaryId, primary)
        if (!main.present) return TileReadout.EMPTY
        val sub = if (secondaryId != null && secondary != null) value(secondaryId, secondary) else null
        val subText = sub?.takeIf { it.present }?.let { "${it.primary} ${it.unit}".trim() }
        return main.copy(secondary = subText)
    }

    private fun gear(code: Int) = when (code) {
        1 -> "P"; 2 -> "R"; 3 -> "N"; 4 -> "D"; 5 -> "M"; 6 -> "S"; else -> "—"
    }

    private fun workMode(code: Int) = when (code) {
        0 -> TileReadout("OFF", primaryRes = R.string.panel_state_off, accent = TileAccent.LIVE)
        1, 2 -> TileReadout("EV", accent = TileAccent.LIVE)
        3 -> TileReadout("HEV", accent = TileAccent.LIVE)
        else -> TileReadout("—", accent = TileAccent.LIVE)
    }

    private fun driveMode(code: Int) = when (code) {
        1 -> TileReadout("ECO", primaryRes = R.string.panel_mode_eco, accent = TileAccent.LIVE)
        2 -> TileReadout("SPRT", primaryRes = R.string.panel_mode_sport, accent = TileAccent.LIVE)
        3 -> TileReadout("NORM", primaryRes = R.string.panel_mode_normal, accent = TileAccent.LIVE)
        4 -> TileReadout("SNOW", primaryRes = R.string.panel_mode_snow, accent = TileAccent.LIVE)
        else -> TileReadout("—", accent = TileAccent.LIVE)
    }

    private fun powerState(code: Int) = when (code) {
        0 -> TileReadout("OFF", primaryRes = R.string.panel_state_off, accent = TileAccent.LIVE)
        1 -> TileReadout("ON", primaryRes = R.string.panel_state_on, accent = TileAccent.LIVE)
        2 -> TileReadout("DRV", primaryRes = R.string.panel_state_drive, accent = TileAccent.LIVE)
        else -> TileReadout("—", accent = TileAccent.LIVE)
    }

    private fun windMode(code: Int) = when (code) {
        1, 2 -> TileReadout("FACE", primaryRes = R.string.panel_wind_face, accent = TileAccent.LIVE)
        3, 4 -> TileReadout("FEET", primaryRes = R.string.panel_wind_feet, accent = TileAccent.LIVE)
        5 -> TileReadout("GLASS", primaryRes = R.string.panel_wind_glass, accent = TileAccent.LIVE)
        else -> TileReadout("—", accent = TileAccent.LIVE)
    }

    private fun turnSignal(code: Int) = when (code) {
        2 -> "◀"; 4 -> "▶"; 6 -> "⚠"; else -> "—"
    }

    private fun trunk(code: Int) = when (code) {
        2 -> TileReadout("SHUT", primaryRes = R.string.panel_state_closed)
        1 -> TileReadout("OPEN", primaryRes = R.string.panel_state_open, accent = TileAccent.WARN)
        3 -> TileReadout("MOVE", primaryRes = R.string.panel_state_moving)
        else -> TileReadout("—")
    }

    private fun onOff(v: Double) = if (v == 1.0) {
        TileReadout("ON", primaryRes = R.string.panel_state_on, accent = TileAccent.LIVE)
    } else {
        TileReadout("—", accent = TileAccent.NONE)
    }

    private fun openClosed(v: Double) = if (v == 1.0) {
        TileReadout("OPEN", primaryRes = R.string.panel_state_open, accent = TileAccent.WARN)
    } else {
        TileReadout("—", accent = TileAccent.NONE)
    }

    private fun level(v: Double) = TileReadout(if (v == 0.0) "—" else v.roundToInt().toString(), accent = if (v > 0) TileAccent.LIVE else TileAccent.NONE)

    private fun BydPanelField.unit(): String = when (decode) {
        com.shilapi.xcertplay.hud.BydDecode.FLOAT_KWH -> if (id == "avgConsumption") "kWh/100km" else "kWh"
        com.shilapi.xcertplay.hud.BydDecode.FLOAT_VOLT -> "V"
        com.shilapi.xcertplay.hud.BydDecode.FLOAT_AMP -> "A"
        else -> ""
    }

    private fun one(v: Double) = trim((v * 10).roundToInt() / 10.0)
    private fun signedOne(v: Double) = (if (v > 0) "+" else "") + one(v)
    private fun three(v: Double) = trim((v * 1000).roundToInt() / 1000.0)
    private fun trim(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    private fun thousands(v: Int) = "%,d".format(v)
}

