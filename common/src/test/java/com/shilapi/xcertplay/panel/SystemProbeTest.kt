package com.shilapi.xcertplay.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The head-unit load is read from local files; parsing is pure, so real /proc and sysfs samples are testable. */
class SystemProbeTest {
    @JvmField @Rule val folder = TemporaryFolder()

    @Test fun cpuTimesUseIdlePlusIOWait() {
        val times = SystemProbe.parseCpuTimes("cpu  36744 0 26275 2401276 17865 0 421 0 0 0")!!
        assertEquals(2482581L, times.total)
        assertEquals(2419141L, times.idle)
    }

    @Test fun cpuPercentIsTheBusyShare() {
        val first = SystemProbe.parseCpuTimes("cpu  10 0 10 980 0 0 0 0 0 0")!!
        val second = SystemProbe.parseCpuTimes("cpu  20 0 20 1960 0 0 0 0 0 0")!!
        assertEquals(2.0, SystemProbe.cpuPercent(first, second)!!, 0.001)
    }

    @Test fun cpuPercentNeedsTwoSamples() =
        assertNull(SystemProbe.cpuPercent(null, SystemProbe.parseCpuTimes("cpu 1 2 3 4 5")!!))

    @Test fun memoryTotalsComeFromMeminfo() {
        val meminfo = """
            MemTotal:        8000000 kB
            MemFree:          500000 kB
            MemAvailable:    3000000 kB
            Buffers:          100000 kB
        """.trimIndent()
        val (total, available) = SystemProbe.parseMemoryKb(meminfo)!!
        assertEquals(8000000L, total)
        assertEquals(3000000L, available)
    }

    @Test fun memoryNeedsBothLines() =
        assertNull(SystemProbe.parseMemoryKb("MemTotal: 8000000 kB"))

    @Test fun coreFrequenciesSkipSilentCores() {
        val root = folder.newFolder("cpus")
        fileIn(root, "cpu0/cpufreq/scaling_cur_freq", "1800000\n")
        fileIn(root, "cpu4/cpufreq/scaling_cur_freq", "0\n")
        File(root, "cpu6").mkdirs()
        val mhz = SystemProbe.readCoreFreqMhz(root)
        assertEquals(listOf(1800), mhz)
    }

    @Test fun hottestThermalZoneIsReported() {
        val root = folder.newFolder("thermal")
        fileIn(root, "thermal_zone0/temp", "45000")
        fileIn(root, "thermal_zone1/temp", "68000")
        fileIn(root, "thermal_zone2/temp", "-1")
        assertEquals(68.0, SystemProbe.readMaxTempC(root)!!, 0.001)
    }

    @Test fun absurdTemperaturesAreIgnored() {
        val root = folder.newFolder("thermal2")
        fileIn(root, "thermal_zone0/temp", "999999")
        assertNull(SystemProbe.readMaxTempC(root))
    }

    private fun fileIn(root: File, relative: String, content: String) {
        val file = File(root, relative)
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}
