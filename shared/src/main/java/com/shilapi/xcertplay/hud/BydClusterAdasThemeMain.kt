package com.shilapi.xcertplay.hud

import android.os.Process
import java.io.File
import kotlin.system.exitProcess

/**
 * Shell-UID entry point that DiPlay starts through the head unit's adb with app_process. It reads the car's own cluster
 * files, which only the system and the shell may read, writes DiPlay's resource into the sdcard's `.cluster/translate`
 * folder and asks the cluster service to use it, all through [BydClusterAdasTheme]. Prints one protocol line.
 *
 * `probe` changes nothing. `install` and `remove` reload the cluster's interface, so DiPlay asks for them only while
 * the car is parked.
 */
object BydClusterAdasThemeMain {
    internal const val HEADER = "DIPLAY_ADAS_THEME_V1"
    internal const val PROBE = "probe"
    internal const val INSTALL = "install"
    internal const val REMOVE = "remove"
    private const val DEADLINE_MS = 28_000L
    private const val ROOT_UID = 0
    private const val SHELL_UID = 2000

    /** The shell command that runs this helper from the installed app, for [action] and the cluster's screen reload code. */
    internal fun command(apk: String, action: String, refreshCode: Int = BydClusterAdasTheme.REFRESH_12_3): String {
        val quoted = "'" + apk.replace("'", "'\"'\"'") + "'"
        return "CLASSPATH=$quoted app_process /system/bin ${BydClusterAdasThemeMain::class.java.name} $action $refreshCode"
    }

    /** Reads the helper's protocol line; other output, such as runtime warnings, is ignored. */
    internal fun parse(output: String?): BydClusterAdasTheme.Result? {
        val line = output?.lineSequence()?.map(String::trim)?.lastOrNull { it.startsWith("$HEADER|") } ?: return null
        val parts = line.removePrefix("$HEADER|").split('|')
        val status = BydClusterAdasTheme.Status.entries.firstOrNull { it.name == parts[0] } ?: return null
        val fields = parts.drop(1).mapNotNull { part ->
            part.indexOf('=').takeIf { it > 0 }?.let { part.substring(0, it) to part.substring(it + 1) }
        }.toMap()
        return BydClusterAdasTheme.Result(status, fields)
    }

    @JvmStatic fun main(args: Array<String>) {
        // A stuck Binder call must not leave a privileged helper running.
        Thread({ Thread.sleep(DEADLINE_MS); exitProcess(2) }, "adas-theme-helper-deadline")
            .apply { isDaemon = true; start() }
        val line = try {
            execute(args)
        } catch (error: Throwable) {
            BydClusterAdasTheme.Result(BydClusterAdasTheme.Status.FAILED, mapOf("code" to error.javaClass.simpleName)).line()
        }
        println("$HEADER|$line")
        exitProcess(0)
    }

    private fun execute(args: Array<String>): String {
        val uid = Process.myUid()
        require(uid == SHELL_UID || uid == ROOT_UID)
        require(args.size == 2 && args[0] in setOf(PROBE, INSTALL, REMOVE))
        val code = requireNotNull(args[1].toIntOrNull())
        val link = BydClusterThemeLink.connect() ?: return BydClusterAdasTheme.Result(BydClusterAdasTheme.Status.NO_CLUSTER).line()
        val theme = BydClusterAdasTheme(
            File(BydClusterAdasTheme.THEME_PATH),
            File(BydClusterAdasTheme.LANGUAGE_PATH),
            File(BydClusterAdasTheme.TRANSLATE_PATH),
            link,
        )
        return when (args[0]) {
            PROBE -> theme.probe()
            INSTALL -> theme.install(code)
            else -> theme.remove(code)
        }.line()
    }
}
