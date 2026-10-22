package com.shilapi.xcertplay.panel

import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydPanelFields

/**
 * Every widget the side panel can show. The three built-in cards mirror what the panel showed before it had
 * widgets; each BYD tile reads one autoservice signal (or a group of related ones, for a MULTI tile) the
 * repository can fetch. Related positions (front/rear motors, four doors, five belts) share one dense tile
 * so the drawer offers what the driver thinks about, not one card per raw signal.
 */
object TileCatalog {
    /** Built-in cards. BYD signals are already read elsewhere (battery/tyres) or come from the head unit itself (system). */
    private val builtin = listOf(
        TileKind("clock", R.string.panel_tile_clock, TileFormat.CLOCK, defaultSize = TileSize.WIDE, group = TileGroup.CLOCK),
        TileKind("battery", R.string.panel_tile_battery, TileFormat.BATTERY, defaultSize = TileSize.WIDE, group = TileGroup.ENERGY),
        TileKind("tyres", R.string.panel_tile_tyres, TileFormat.TYRES, defaultSize = TileSize.LARGE, group = TileGroup.BODY),
        TileKind("system", R.string.panel_tile_system, TileFormat.SYSTEM, defaultSize = TileSize.LARGE, group = TileGroup.SYSTEM),
    )

    /** Composite tiles that pair two signals on one card (primary big, secondary beneath). */
    private val composite = listOf(
        TileKind("energy", R.string.panel_tile_energy, TileFormat.VALUE, listOf("soc", "remainKwh"), TileSize.WIDE, TileGroup.ENERGY, composite = true),
        TileKind("range", R.string.panel_tile_range, TileFormat.VALUE, listOf("evRange", "fuelRange"), TileSize.WIDE, TileGroup.ENERGY, composite = true),
    )

    /**
     * Tiles that lay several related signals side by side, each with a short position label. The labels are
     * position/role codes (front/rear, wheel corners, heat/vent) shown as-is, so they need no translation;
     * each cell's value is formatted by its own field id exactly as a single tile would.
     */
    private val multi = listOf(
        // Drivetrain: front and rear motor, one dense tile per measured quantity.
        TileKind("motorRpm", R.string.panel_tile_motor_rpm, TileFormat.MULTI, listOf("motorRpmFront", "motorRpmRear"), TileSize.WIDE, TileGroup.TECH, labels = listOf("F", "R")),
        TileKind("motorCurrent", R.string.panel_tile_motor_current, TileFormat.MULTI, listOf("motorCurrentFront", "motorCurrentRear"), TileSize.WIDE, TileGroup.TECH, labels = listOf("F", "R")),
        TileKind("motorTemp", R.string.panel_tile_motor_temp, TileFormat.MULTI, listOf("motorTempFront", "motorTempRear"), TileSize.WIDE, TileGroup.TECH, labels = listOf("F", "R")),
        TileKind("inverterTemp", R.string.panel_tile_inverter_temp, TileFormat.MULTI, listOf("inverterTempFront", "inverterTempRear"), TileSize.WIDE, TileGroup.TECH, labels = listOf("F", "R")),
        // Climate summary and seat comfort.
        TileKind("climate", R.string.panel_tile_climate, TileFormat.MULTI, listOf("acStatus", "acTemp"), TileSize.WIDE, TileGroup.CLIMATE, labels = listOf("AC", "")),
        TileKind("seatDriver", R.string.panel_tile_seat_driver, TileFormat.MULTI, listOf("seatHeatDriver", "seatVentDriver"), TileSize.WIDE, TileGroup.CLIMATE, labels = listOf("H", "V")),
        TileKind("seatPassenger", R.string.panel_tile_seat_passenger, TileFormat.MULTI, listOf("seatHeatPassenger", "seatVentPassenger"), TileSize.WIDE, TileGroup.CLIMATE, labels = listOf("H", "V")),
        // Lights summary.
        TileKind("lights", R.string.panel_tile_lights, TileFormat.MULTI, listOf("lightLow", "lightHigh", "lightSide"), TileSize.WIDE, TileGroup.LIGHTS, labels = listOf("LO", "HI", "PO")),
        // Body: the four doors, the four windows, the openings, all as one block each.
        TileKind("doors", R.string.panel_tile_doors, TileFormat.MULTI, listOf("doorFL", "doorFR", "doorRL", "doorRR"), TileSize.LARGE, TileGroup.BODY, labels = listOf("FL", "FR", "RL", "RR")),
        TileKind("windows", R.string.panel_tile_windows, TileFormat.MULTI, listOf("windowFL", "windowFR", "windowRL", "windowRR"), TileSize.LARGE, TileGroup.BODY, labels = listOf("FL", "FR", "RL", "RR")),
        TileKind("openings", R.string.panel_tile_openings, TileFormat.MULTI, listOf("trunk", "frontTrunk", "hood"), TileSize.WIDE, TileGroup.BODY, labels = listOf("R", "F", "H")),
        // Cabin safety: five belts and five seats.
        TileKind("seatbelts", R.string.panel_tile_seatbelts, TileFormat.MULTI, listOf("seatbeltFL", "seatbeltFR", "seatbeltRL", "seatbeltRM", "seatbeltRR"), TileSize.LARGE, TileGroup.SAFETY, labels = listOf("D", "P", "RL", "RM", "RR")),
        TileKind("occupancy", R.string.panel_tile_occupancy, TileFormat.MULTI, listOf("occupancyFL", "occupancyFR", "occupancyRL", "occupancyRM", "occupancyRR"), TileSize.LARGE, TileGroup.SAFETY, labels = listOf("D", "P", "RL", "RM", "RR")),
        TileKind("wipers", R.string.panel_tile_wipers, TileFormat.MULTI, listOf("wiperRelay", "autoWipers"), TileSize.WIDE, TileGroup.SAFETY, labels = listOf("", "A")),
    )

    /** Single-signal tiles, drawn from every field the catalog gives a title to; grouped signals are omitted here. */
    private val singles = BydPanelFields.all.map { signal ->
        TileKind(
            id = signal.id,
            titleRes = title(signal.id),
            format = TileFormat.VALUE,
            fields = listOf(signal.id),
            defaultSize = size(signal.id),
            group = group(signal.id),
        )
    }.filter { title(it.id) != 0 }

    val all: List<TileKind> = builtin + composite + multi + singles
    val byId: Map<String, TileKind> = all.associateBy { it.id }

    /** The starting layout: the panel's original clock / battery / tyres, in that order. */
    val defaultLayout: List<SidePanelWidget> =
        listOf("clock", "battery", "tyres").map { SidePanelWidget(it, byId.getValue(it).defaultSize) }

    /** Kinds the add drawer offers, grouped, with the built-ins first so they are easy to find. */
    fun grouped(): Map<TileGroup, List<TileKind>> = all.groupBy { it.group }

    private fun title(id: String): Int = when (id) {
        "clock", "battery", "tyres" -> 0
        "soc" -> R.string.panel_tile_soc
        "remainKwh" -> R.string.panel_tile_kwh
        "evRange" -> R.string.panel_tile_ev_range
        "avgConsumption" -> R.string.panel_tile_avg_consumption
        "soh" -> R.string.panel_tile_soh
        "odometer" -> R.string.panel_tile_odometer
        "speed" -> R.string.panel_tile_speed
        "gear" -> R.string.panel_tile_gear
        "batteryPower" -> R.string.panel_tile_battery_power
        "accelPedal" -> R.string.panel_tile_accelerator
        "brakePedal" -> R.string.panel_tile_brake
        "maxCellVoltage" -> R.string.panel_tile_max_cell
        "minCellVoltage" -> R.string.panel_tile_min_cell
        "maxBatteryTemp" -> R.string.panel_tile_max_batt_temp
        "minBatteryTemp" -> R.string.panel_tile_min_batt_temp
        "hvVoltage" -> R.string.panel_tile_hv_volt
        "hvCurrent" -> R.string.panel_tile_hv_current
        "voltage12v" -> R.string.panel_tile_v12
        "outsideTemp" -> R.string.panel_tile_outside_temp
        "insideTemp" -> R.string.panel_tile_inside_temp
        "lightLevel" -> R.string.panel_tile_light
        "fuelPercent" -> R.string.panel_tile_fuel
        "fuelRange" -> R.string.panel_tile_fuel_range
        "engineRpm" -> R.string.panel_tile_engine_rpm
        "workMode" -> R.string.panel_tile_energy_mode
        "driveMode" -> R.string.panel_tile_drive_mode
        "powerState" -> R.string.panel_tile_power_state
        "insulationKohm" -> R.string.panel_tile_insulation
        "compressorW" -> R.string.panel_tile_compressor
        "fanLevel" -> R.string.panel_tile_fan
        "acCirc" -> R.string.panel_tile_recirculation
        "acDefrostFront" -> R.string.panel_tile_defrost
        "acWindMode" -> R.string.panel_tile_wind_mode
        "acCtrlMode" -> R.string.panel_tile_ac_control
        "drl" -> R.string.panel_tile_drl
        "turnSignal" -> R.string.panel_tile_turn_signal
        "sunroof" -> R.string.panel_tile_sunroof
        "lockFL" -> R.string.panel_tile_lock
        "keyBatteryStatus" -> R.string.panel_tile_key_battery
        "fuelTotalL" -> R.string.panel_tile_fuel_total
        "mileageHevKm" -> R.string.panel_tile_hev_mileage
        "mileageEvKm" -> R.string.panel_tile_ev_mileage
        else -> 0
    }

    private fun size(id: String): TileSize = when (id) {
        "odometer" -> TileSize.WIDE
        "accelPedal", "brakePedal" -> TileSize.TALL
        "speed" -> TileSize.LARGE
        "turnSignal" -> TileSize.WIDE
        else -> TileSize.SMALL
    }

    private fun group(id: String): TileGroup = when (id) {
        "soc", "remainKwh", "evRange", "avgConsumption", "soh", "odometer" -> TileGroup.ENERGY
        "speed", "gear", "accelPedal", "brakePedal", "workMode", "driveMode", "powerState" -> TileGroup.DRIVE
        "outsideTemp", "insideTemp", "lightLevel", "fanLevel", "acCirc", "acDefrostFront", "acWindMode", "acCtrlMode" -> TileGroup.CLIMATE
        "fuelPercent", "fuelRange", "engineRpm", "fuelTotalL", "mileageHevKm", "mileageEvKm" -> TileGroup.HYBRID
        "batteryPower", "maxCellVoltage", "minCellVoltage", "maxBatteryTemp", "minBatteryTemp",
        "hvVoltage", "hvCurrent", "voltage12v", "insulationKohm", "compressorW" -> TileGroup.TECH
        "lightLow", "lightHigh", "lightSide", "drl", "turnSignal" -> TileGroup.LIGHTS
        "keyBatteryStatus" -> TileGroup.SAFETY
        "sunroof", "lockFL" -> TileGroup.BODY
        else -> TileGroup.BODY
    }
}
