package com.shilapi.xcertplay.hud

/** How a raw autoservice word turns into a physical value. Mirrors the BYD SDK's per-feature decoders. */
enum class BydDecode {
    INT_RAW, INT_ENUM, INT_PERCENT, INT_KPA, INT_TEMP_C, INT_TEMP_OFS40,
    INT_RANGE_KM, INT_RPM, INT_SCALED, INT_DIV10,
    FLOAT_PERCENT, FLOAT_KW, FLOAT_KWH, FLOAT_VOLT, FLOAT_AMP, FLOAT_LITERS,
}

/** Refresh tier for a panel field: only what is on screen is read, and only as often as it moves. */
enum class BydTier { FAST, NORMAL, SLOW }

/**
 * One read-only BYD autoservice address the side panel can show, with the firmware symbol its number
 * is filed under (for catalog remap), the compiled DiLink 5 default, the decoder and a physical guard.
 * The device/fid/decoder values are technical facts about BYD's autoservice binder, re-implemented here
 * against DiPlay's own transport; none of BYDMate's source is used.
 */
data class BydPanelField(
    val id: String,
    val symbol: String?,
    val deviceName: String,
    val defaultDevice: Int,
    val defaultFid: Int,
    val transaction: Int,
    val decode: BydDecode,
    val scale: Double = 1.0,
    val guardMin: Double? = null,
    val guardMax: Double? = null,
    val tier: BydTier = BydTier.NORMAL,
) {
    /** The `service call` the adb shell runs for a resolved address. */
    fun command(device: Int, fid: Int): String = "service call autoservice $transaction i32 $device i32 $fid"
}

/** Every signal the side panel can offer. Fields the car does not answer stay hidden, so this is a menu, not a contract. */
object BydPanelFields {
    val all: List<BydPanelField> = listOf(
        // Energy and range.
        BydPanelField("soc", "Statistic.STATISTIC_ELEC_PERCENTAGE", "STATISTIC", 1014, 1246777400, 7, BydDecode.FLOAT_PERCENT, guardMin = 0.0, guardMax = 100.0),
        BydPanelField("remainKwh", "Power.POWER_BATTERY_REMAIN_ELECTRICITY", "POWER", 1005, 882901008, 7, BydDecode.FLOAT_KWH, guardMin = 0.0, guardMax = 300.0),
        BydPanelField("evRange", "Statistic.STATISTIC_ELEC_DRIVING_RANGE", "STATISTIC", 1014, 1246765118, 5, BydDecode.INT_RANGE_KM, tier = BydTier.NORMAL),
        BydPanelField("avgConsumption", "Statistic.STATISTIC_TOTAL_ELEC_CON_PHM", "STATISTIC", 1014, 1246761008, 7, BydDecode.FLOAT_KWH, guardMin = 0.0, guardMax = 100.0, tier = BydTier.SLOW),
        BydPanelField("soh", "Statistic.STATISTIC_BATTERY_HEALTHY_INDEX", "STATISTIC", 1014, 1145045032, 5, BydDecode.INT_PERCENT, tier = BydTier.SLOW),
        BydPanelField("odometer", "Statistic.STATISTIC_TOTAL_MILEAGE", "STATISTIC", 1014, 1246765072, 5, BydDecode.INT_SCALED, scale = 0.1, tier = BydTier.SLOW),
        // Drive.
        BydPanelField("speed", "Speed.SPEED_AUTO_SPEED", "SPEED", 1013, -1807745016, 7, BydDecode.FLOAT_KW, guardMin = 0.0, guardMax = 300.0, tier = BydTier.FAST),
        BydPanelField("gear", "Gearbox.GEARBOX_AUTO_MODE_TYPE", "GEARBOX", 1011, 555745336, 5, BydDecode.INT_ENUM, tier = BydTier.FAST),
        BydPanelField("batteryPower", "Engine.ENGINE_POWER", "ENGINE", 1012, 339738656, 5, BydDecode.INT_RAW, guardMin = -300.0, guardMax = 500.0, tier = BydTier.FAST),
        BydPanelField("accelPedal", "Speed.SPEED_ACCELERATOR_S", "SPEED", 1013, 874512392, 5, BydDecode.INT_RAW, guardMin = 0.0, guardMax = 100.0, tier = BydTier.FAST),
        BydPanelField("brakePedal", "Speed.SPEED_BRAKE_S", "SPEED", 1013, 874512400, 5, BydDecode.INT_RAW, guardMin = 0.0, guardMax = 100.0, tier = BydTier.FAST),
        // Battery detail.
        BydPanelField("maxCellVoltage", "Statistic.STATISTIC_HIGHEST_BATTERY_VOLTAGE", "STATISTIC", 1014, 1147142192, 5, BydDecode.INT_SCALED, scale = 0.001, guardMin = 0.5),
        BydPanelField("minCellVoltage", "Statistic.STATISTIC_LOWEST_BATTERY_VOLTAGE", "STATISTIC", 1014, 1147142160, 5, BydDecode.INT_SCALED, scale = 0.001, guardMin = 0.5),
        BydPanelField("maxBatteryTemp", "Statistic.STATISTIC_HIGHEST_BATTERY_TEMP", "STATISTIC", 1014, 1148190752, 5, BydDecode.INT_TEMP_OFS40),
        BydPanelField("minBatteryTemp", "Statistic.STATISTIC_LOWEST_BATTERY_TEMP", "STATISTIC", 1014, 1148190736, 5, BydDecode.INT_TEMP_OFS40),
        BydPanelField("hvVoltage", "Charging.CHARGING_CHARGE_BATTERY_VOLT", "CHARGING", 1009, 1145045000, 5, BydDecode.INT_RAW, guardMin = 0.0, guardMax = 1000.0),
        BydPanelField("hvCurrent", "Charging.CHARGING_CHARGE_CURRENT", "CHARGING", 1009, 1145045016, 7, BydDecode.FLOAT_AMP),
        BydPanelField("voltage12v", "Ota.OTA_BATTERY_POWER_VOLTAGE", "OTA", 1001, 1128267816, 7, BydDecode.FLOAT_VOLT, guardMin = 0.0),
        // Climate and environment.
        BydPanelField("outsideTemp", "Ac.AC_TEMP_OUT", "AC", 1000, 1077936184, 5, BydDecode.INT_TEMP_C),
        BydPanelField("insideTemp", "Ac.AC_TEMP_INSIDE", "AC", 1000, 1031798832, 5, BydDecode.INT_TEMP_C),
        BydPanelField("lightLevel", "Sensor.SENSOR_LIGHT", "SENSOR", 1043, 315621396, 5, BydDecode.INT_RAW),
        // DM-i hybrid. On an EV these answer a sentinel (255 / 2046 / 8191) that the decoder drops, so fuel tiles hide themselves.
        BydPanelField("fuelPercent", null, "STATISTIC", 1014, 1246785600, 5, BydDecode.INT_PERCENT),
        BydPanelField("fuelRange", null, "STATISTIC", 1014, 1246773304, 5, BydDecode.INT_RANGE_KM),
        BydPanelField("engineRpm", null, "ENGINE", 1012, 339738642, 5, BydDecode.INT_RPM, tier = BydTier.FAST),
        BydPanelField("workMode", "Energy.ENERGY_MODE_INSTRUMENT", "ENERGY", 1006, 874512420, 5, BydDecode.INT_ENUM, tier = BydTier.FAST),
        BydPanelField("fuelTotalL", null, "STATISTIC", 1014, 1246760976, 7, BydDecode.FLOAT_LITERS, guardMin = 0.1, tier = BydTier.SLOW),
        BydPanelField("mileageHevKm", null, "STATISTIC", 1014, 1246773264, 5, BydDecode.INT_RAW, tier = BydTier.SLOW),
        BydPanelField("mileageEvKm", null, "STATISTIC", 1014, 1246773284, 5, BydDecode.INT_RAW, tier = BydTier.SLOW),
        // Drive state.
        BydPanelField("driveMode", "Energy.ENERGY_OPERATION_MODE", "ENERGY", 1006, 555745294, 5, BydDecode.INT_ENUM),
        BydPanelField("powerState", "Setting.SET_VEHICLE_STATE", "SETTING", 1023, 315621408, 5, BydDecode.INT_ENUM, tier = BydTier.FAST),
        // Traction motors, front and rear. A single-motor car answers a sentinel for the rear pair and those tiles hide.
        BydPanelField("motorRpmFront", "Engine.ENGINE_FRONT_MOTOR_SPEED", "ENGINE", 1012, 1141899272, 5, BydDecode.INT_RAW,
            guardMin = -20000.0, guardMax = 20000.0, tier = BydTier.FAST),
        BydPanelField("motorRpmRear", "Engine.ENGINE_REAR_MOTOR_SPEED", "ENGINE", 1012, 621805576, 5, BydDecode.INT_RAW,
            guardMin = -20000.0, guardMax = 20000.0, tier = BydTier.FAST),
        BydPanelField("motorCurrentFront", "Charging.CHARGING_DRIVER_MOTOR_CURRENT", "CHARGING", 1009, 1186988040, 7, BydDecode.FLOAT_AMP,
            guardMin = -2000.0, guardMax = 2000.0, tier = BydTier.FAST),
        BydPanelField("motorCurrentRear", "Charging.CHARGING_REAR_DRIVER_MOTOR_CURRENT", "CHARGING", 1009, 1186988056, 7, BydDecode.FLOAT_AMP,
            guardMin = -2000.0, guardMax = 2000.0, tier = BydTier.FAST),
        BydPanelField("motorTempFront", "Gb.GB_FRONT_MOTOR_TEMP", "GB", 1039, 1154482192, 5, BydDecode.INT_RAW, guardMin = -39.0, guardMax = 150.0),
        BydPanelField("motorTempRear", "Gb.GB_TREAR_MOTOR_TEMP", "GB", 1039, 1155530768, 5, BydDecode.INT_RAW, guardMin = -39.0, guardMax = 150.0),
        BydPanelField("inverterTempFront", "Gb.GB_FRONT_MOTOR_IPM_TEMP", "GB", 1039, 1154482184, 5, BydDecode.INT_RAW, guardMin = -39.0, guardMax = 150.0),
        BydPanelField("inverterTempRear", "Gb.GB_REAR_MOTOR_IPM_TEMP", "GB", 1039, 1155530760, 5, BydDecode.INT_RAW, guardMin = -39.0, guardMax = 150.0),
        BydPanelField("insulationKohm", "Gb.GB_BMC_INSULATION_VALUE", "GB", 1039, 1134559256, 5, BydDecode.INT_RAW,
            guardMin = 0.0, guardMax = 65000.0, tier = BydTier.SLOW),
        // Carve-outs (BYDMate): a Power. symbol answered on the AC device; naming AC keeps a catalog move on-device.
        BydPanelField("compressorW", "Power.POWER_COMPRESSOR_CONSUME_POWER", "AC", 1000, 1031798840, 5, BydDecode.INT_RAW, guardMin = 0.0, guardMax = 20000.0),
        // Climate.
        BydPanelField("acStatus", "Ac.AC_POWER_STATE", "AC", 1000, 1077936144, 5, BydDecode.INT_ENUM),
        BydPanelField("acTemp", "Ac.AC_TEMP_MAIN", "AC", 1000, 1077936168, 5, BydDecode.INT_TEMP_C),
        BydPanelField("fanLevel", "Ac.AC_WIND_LEVEL", "AC", 1000, 1077936156, 5, BydDecode.INT_RAW),
        BydPanelField("acCirc", "Ac.AC_CYCLE_MODE", "AC", 1000, 1077936148, 5, BydDecode.INT_ENUM),
        BydPanelField("acDefrostFront", "Ac.AC_DEFROST_FRONT_STATE", "AC", 1000, 1077936150, 5, BydDecode.INT_ENUM),
        BydPanelField("acWindMode", "Ac.AC_WIND_MODE", "AC", 1000, 1077936152, 5, BydDecode.INT_ENUM),
        BydPanelField("acCtrlMode", "Ac.AC_CTRL_MODE", "AC", 1000, 1077936146, 5, BydDecode.INT_ENUM),
        BydPanelField("seatHeatDriver", "Ac.AC_MAIN_DRIVE_SEAT_HEATING_LEVEL", "AC", 1000, 702545948, 5, BydDecode.INT_RAW, guardMin = 0.0, guardMax = 5.0),
        BydPanelField("seatVentDriver", "Ac.AC_MAIN_DRIVE_SEAT_VENTILATING_LEVEL", "AC", 1000, 702545944, 5, BydDecode.INT_RAW, guardMin = 0.0, guardMax = 5.0),
        BydPanelField("seatHeatPassenger", "Ac.AC_PASSENGER_SEAT_HEATING_LEVEL", "AC", 1000, 711983132, 5, BydDecode.INT_RAW, guardMin = 0.0, guardMax = 5.0),
        BydPanelField("seatVentPassenger", "Ac.AC_PASSENGER_SEAT_VENTILATING_LEVEL", "AC", 1000, 711983128, 5, BydDecode.INT_RAW, guardMin = 0.0, guardMax = 5.0),
        // Lights.
        BydPanelField("lightLow", "Light.LIGHT_LOW_BEAM_LIGHT", "LIGHT", 1004, 950009866, 5, BydDecode.INT_ENUM, tier = BydTier.FAST),
        BydPanelField("lightHigh", "Light.LIGHT_HIGH_BEAM_LIGHT", "LIGHT", 1004, 950009868, 5, BydDecode.INT_ENUM, tier = BydTier.FAST),
        BydPanelField("lightSide", "Light.LIGHT_SIDE_LIGHT", "LIGHT", 1004, 950009864, 5, BydDecode.INT_ENUM, tier = BydTier.FAST),
        BydPanelField("drl", "Light.LIGHT_CMD_DAY_RUNNING_LIGHT_STATE", "LIGHT", 1004, 1231040528, 5, BydDecode.INT_ENUM, tier = BydTier.FAST),
        BydPanelField("turnSignal", "Light.LIGHT_TURN_SIGNAL_LIGHT", "LIGHT", 1004, 950009900, 5, BydDecode.INT_ENUM, tier = BydTier.FAST),
        // Body: doors, windows, openings, lock.
        BydPanelField("doorFL", "Bodywork.BODYWORK_LEFT_HAND_FRONT_DOOR", "BODYWORK", 1001, 692060168, 5, BydDecode.INT_ENUM),
        BydPanelField("doorFR", "Bodywork.BODYWORK_RIGHT_HAND_FRONT_DOOR", "BODYWORK", 1001, 692060170, 5, BydDecode.INT_ENUM),
        BydPanelField("doorRL", "Bodywork.BODYWORK_LEFT_HAND_REAR_DOOR", "BODYWORK", 1001, 692060172, 5, BydDecode.INT_ENUM),
        BydPanelField("doorRR", "Bodywork.BODYWORK_RIGHT_HAND_REAR_DOOR", "BODYWORK", 1001, 692060174, 5, BydDecode.INT_ENUM),
        BydPanelField("windowFL", "Bodywork.BODYWORK_WINDOW_LEFT_FRONT_PERCENT", "BODYWORK", 1001, 947912728, 5, BydDecode.INT_PERCENT),
        BydPanelField("windowFR", "Bodywork.BODYWORK_WINDOW_RIGHT_FRONT_PERCENT", "BODYWORK", 1001, 1267728400, 5, BydDecode.INT_PERCENT),
        BydPanelField("windowRL", "Bodywork.BODYWORK_WINDOW_LEFT_REAR_PERCENT", "BODYWORK", 1001, 947912736, 5, BydDecode.INT_PERCENT),
        BydPanelField("windowRR", "Bodywork.BODYWORK_WINDOW_RIGHT_REAR_PERCENT", "BODYWORK", 1001, 947912752, 5, BydDecode.INT_PERCENT),
        BydPanelField("sunroof", "Bodywork.BODYWORK_MOON_ROOF_OPEN_PERCENT", "BODYWORK", 1001, 1101004808, 5, BydDecode.INT_PERCENT),
        BydPanelField("trunk", "Bodywork.BODYWORK_BACKDOOR_CURRENT_POSITION", "BODYWORK", 1001, 1074790416, 5, BydDecode.INT_ENUM),
        BydPanelField("frontTrunk", "Bodywork.BODYWORK_FRONT_HATCH_CURRENT_POSITION", "BODYWORK", 1001, 1156579344, 5, BydDecode.INT_ENUM),
        BydPanelField("hood", "Bodywork.BODYWORK_HOOD", "BODYWORK", 1001, 692060188, 5, BydDecode.INT_ENUM),
        BydPanelField("lockFL", "Ota.OTA_LF_DOOR_LOCK", "OTA", 1032, 1081081864, 5, BydDecode.INT_ENUM),
        // Cabin safety.
        BydPanelField("seatbeltFL", "Instrument.INSTRUMENT_DD_MAIN_SAFETYBELT_STATE", "INSTRUMENT", 1007, 692060184, 5, BydDecode.INT_ENUM),
        BydPanelField("seatbeltFR", "Instrument.INSTRUMENT_DD_DEPUTY_SAFETYBELT_STATE", "INSTRUMENT", 1007, 638582811, 5, BydDecode.INT_ENUM),
        BydPanelField("seatbeltRL", "Instrument.INSTRUMENT_DD_REAR_LEFT_SAFETYBELT_STATE", "INSTRUMENT", 1007, 638582812, 5, BydDecode.INT_ENUM),
        BydPanelField("seatbeltRM", "Instrument.INSTRUMENT_DD_REAR_MID_SAFETYBELT_STATE", "INSTRUMENT", 1007, 638582813, 5, BydDecode.INT_ENUM),
        BydPanelField("seatbeltRR", "Instrument.INSTRUMENT_DD_REAR_RIGHT_SAFETYBELT_STATE", "INSTRUMENT", 1007, 638582814, 5, BydDecode.INT_ENUM),
        BydPanelField("occupancyFL", "Safety.SAFETY_BELT_PASSENGER_COMMAND_FRONT_ROW_SEAT_LEFT", "SAFETY_BELT", 1042, 824180800, 5, BydDecode.INT_ENUM),
        BydPanelField("occupancyFR", "Safety.SAFETY_BELT_PASSENGER_COMMAND_FRONT_ROW_SEAT_RIGHT", "SAFETY_BELT", 1042, 824180758, 5, BydDecode.INT_ENUM),
        BydPanelField("occupancyRL", "Safety.SAFETY_BELT_PASSENGER_COMMAND_SECOND_ROW_SEAT_LEFT", "SAFETY_BELT", 1042, 824180760, 5, BydDecode.INT_ENUM),
        BydPanelField("occupancyRM", "Safety.SAFETY_BELT_PASSENGER_COMMAND_SECOND_ROW_SEAT_MID", "SAFETY_BELT", 1042, 824180762, 5, BydDecode.INT_ENUM),
        BydPanelField("occupancyRR", "Safety.SAFETY_BELT_PASSENGER_COMMAND_SECOND_ROW_SEAT_RIGHT", "SAFETY_BELT", 1042, 824180764, 5, BydDecode.INT_ENUM),
        BydPanelField("keyBatteryStatus", "Statistic.STATISTIC_KEY_BATTERY_LEVEL", "STATISTIC", 1014, 402653200, 5, BydDecode.INT_ENUM, tier = BydTier.SLOW),
        BydPanelField("wiperRelay", "Wiper.WIPER_RELAY_STATE", "WIPER", 1046, 1336934438, 5, BydDecode.INT_ENUM, tier = BydTier.FAST),
        // Carve-out (BYDMate): a Setting. symbol answered on the wiper device; naming WIPER keeps a catalog move on-device.
        BydPanelField("autoWipers", "Setting.SET_AUTO_RAIN_WIPER_SWITCH", "WIPER", 1046, 321912862, 5, BydDecode.INT_ENUM),
    )

    val byId: Map<String, BydPanelField> = all.associateBy { it.id }
}

/**
 * Turns a raw `service call` reply word into a physical value, dropping BYD's not-a-value sentinels and
 * applying the per-field decoder and physical guard. Sentinels and ranges are observed BYD autoservice
 * behaviour, re-stated here independently.
 */
object BydPanelCodec {
    private const val FEATURE_LINK_ERROR = 0x0000FFFF // 65535, fid-CAN link not established
    private const val NOT_INITIALIZED_20BIT = 0x000FFFFF // 1048575
    private const val WRONG_TRANSACT = -10013 // 0xFFFFD8E3
    private const val WRONG_DIRECTION = -10011 // 0xFFFFD8E5
    private const val RANGE_ABSENT = 2046
    private const val RPM_ABSENT = 8191

    fun decode(signal: BydPanelField, rawWord: Int?): Double? {
        val value = when (signal.decode) {
            BydDecode.INT_RAW, BydDecode.INT_ENUM, BydDecode.INT_KPA -> int(signal, rawWord)
            BydDecode.INT_PERCENT -> int(signal, rawWord)?.takeIf { it in 0.0..100.0 }
            BydDecode.INT_TEMP_C -> int(signal, rawWord)?.takeIf { it in -50.0..80.0 }
            BydDecode.INT_TEMP_OFS40 -> int(signal, rawWord)?.let { it - 40 }?.takeIf { it in -50.0..80.0 }
            BydDecode.INT_RANGE_KM -> int(signal, rawWord)?.takeIf { rawWord != RANGE_ABSENT }
            BydDecode.INT_RPM -> int(signal, rawWord)?.takeIf { rawWord != RPM_ABSENT }
            BydDecode.INT_SCALED -> int(signal, rawWord)?.let { it * signal.scale }
            BydDecode.INT_DIV10 -> int(signal, rawWord)?.let { it / 10.0 }
            BydDecode.FLOAT_PERCENT, BydDecode.FLOAT_KW, BydDecode.FLOAT_KWH,
            BydDecode.FLOAT_VOLT, BydDecode.FLOAT_AMP -> float(signal, rawWord)
            BydDecode.FLOAT_LITERS -> float(signal, rawWord)?.takeIf { it > 0.0 }
        }
        return value
    }

    private fun int(signal: BydPanelField, rawWord: Int?): Double? {
        val cleaned = rawWord?.takeUnless { it == FEATURE_LINK_ERROR || it == NOT_INITIALIZED_20BIT || it == WRONG_TRANSACT || it == WRONG_DIRECTION }
            ?: return null
        return cleaned.toDouble().let { guarded(signal, it) }
    }

    private fun float(signal: BydPanelField, rawWord: Int?): Double? {
        val bits = rawWord ?: return null
        val f = java.lang.Float.intBitsToFloat(bits)
        if (f.isNaN() || f.isInfinite() || f == -1.0f) return null
        return guarded(signal, f.toDouble())
    }

    private fun guarded(signal: BydPanelField, value: Double): Double? = when {
        signal.guardMin != null && value < signal.guardMin -> null
        signal.guardMax != null && value > signal.guardMax -> null
        else -> value
    }
}
