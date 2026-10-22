package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The side panel decoder drops BYD's not-a-value sentinels and out-of-range readings; pure, no head unit. */
class BydPanelCodecTest {
    private fun bits(x: Float) = java.lang.Float.floatToRawIntBits(x)
    private fun decode(id: String, raw: Int?) = BydPanelCodec.decode(BydPanelFields.byId.getValue(id), raw)

    @Test fun floatPercentWithinRange() = assertEquals(25.0, decode("soc", bits(25f))!!, 0.001)

    @Test fun floatOverGuardIsDropped() = assertNull(decode("soc", bits(150f)))

    @Test fun floatMinusOneIsSentinel() = assertNull(decode("soc", bits(-1f)))

    @Test fun remainingKwhWithinGuard() = assertEquals(25.1, decode("remainKwh", bits(25.1f))!!, 0.001)

    @Test fun remainingKwhOverGuard() = assertNull(decode("remainKwh", bits(400f)))

    @Test fun integerPercentPlausible() = assertEquals(80.0, decode("fuelPercent", 80)!!, 0.001)

    @Test fun fuelSentinelHidesTileOnEv() = assertNull(decode("fuelPercent", 255))

    @Test fun rangePlausible() = assertEquals(300.0, decode("evRange", 300)!!, 0.001)

    @Test fun rangeAbsentWordIsDropped() = assertNull(decode("evRange", 2046))

    @Test fun rpmPlausible() = assertEquals(1500.0, decode("engineRpm", 1500)!!, 0.001)

    @Test fun rpmNoEngineWordIsDropped() = assertNull(decode("engineRpm", 8191))

    @Test fun featureLinkErrorIsDropped() = assertNull(decode("accelPedal", 65535))

    @Test fun wrongTransactIsDropped() = assertNull(decode("accelPedal", -10013))

    @Test fun rawPedalPlausible() = assertEquals(60.0, decode("accelPedal", 60)!!, 0.001)

    @Test fun rawPedalOutOfPhysicalRange() = assertNull(decode("accelPedal", 120))

    @Test fun batteryTempCarriesMinusFortyOffset() = assertEquals(11.0, decode("maxBatteryTemp", 51)!!, 0.001)

    @Test fun plainTempWithinRange() = assertEquals(30.0, decode("outsideTemp", 30)!!, 0.001)

    @Test fun plainTempOutsideRange() = assertNull(decode("outsideTemp", 90))

    @Test fun floatAmplitudeKeepsSign() = assertEquals(-30.0, decode("hvCurrent", bits(-30f))!!, 0.001)

    @Test fun motorCurrentPlausible() = assertEquals(45.3, decode("motorCurrentRear", bits(45.3f))!!, 0.001)

    @Test fun motorCurrentAbsurdIsDropped() = assertNull(decode("motorCurrentRear", bits(3000f)))

    @Test fun motorRpmKeepsNegativeRegen() = assertEquals(-4000.0, decode("motorRpmFront", -4000)!!, 0.001)

    @Test fun motorTempOverEnvelopeIsDropped() = assertNull(decode("motorTempFront", 200))

    @Test fun doorOpenPlausible() = assertEquals(1.0, decode("doorFL", 1)!!, 0.001)

    @Test fun windowPercentPlausible() = assertEquals(50.0, decode("windowRL", 50)!!, 0.001)

    @Test fun trunkSentinelIsDropped() = assertNull(decode("trunk", 65535))

    @Test fun seatLevelOverFiveIsDropped() = assertNull(decode("seatHeatDriver", 9))

    @Test fun insulationOverRangeIsDropped() = assertNull(decode("insulationKohm", 70000))

    @Test fun fuelLitresPlausible() = assertEquals(312.5, decode("fuelTotalL", bits(312.5f))!!, 0.001)

    @Test fun fuelLitresAbsentOnEvIsDropped() = assertNull(decode("fuelTotalL", bits(0f)))

    @Test fun hevMileageSentinelIsDropped() = assertNull(decode("mileageHevKm", 1048575))

    @Test fun hevMileagePlausible() = assertEquals(52000.0, decode("mileageHevKm", 52000)!!, 0.001)

    @Test fun turnSignalPlausible() = assertEquals(6.0, decode("turnSignal", 6)!!, 0.001)

    @Test fun occupancyCodesPassThrough() = assertEquals(2.0, decode("occupancyFL", 2)!!, 0.001)

    @Test fun powerStatePlausible() = assertEquals(2.0, decode("powerState", 2)!!, 0.001)

    @Test fun nullWordIsNotAValue() = assertNull(decode("soc", null))
}
