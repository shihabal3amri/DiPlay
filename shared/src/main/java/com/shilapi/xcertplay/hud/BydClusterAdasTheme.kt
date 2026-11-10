package com.shilapi.xcertplay.hud

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** How the helper talks to the cluster's resource channel; the real one is [BydClusterThemeLink]. */
internal interface BydClusterThemeChannel {
    /** The active resource, or null when the cluster did not answer. */
    fun active(): Active?

    /** Selects the resource file [name] in the translate folder, or the car's own when null; true when the cluster confirmed. */
    fun select(name: String?): Boolean

    /** Reloads the cluster's interface for the screen [code]; true when the request was handed over. */
    fun refresh(code: Int): Boolean

    class Active(val kind: Int, val path: String)
}

/**
 * Builds, installs and removes DiPlay's cluster resource, the file that moves the large ADAS scene beside the map.
 *
 * BYD's cluster loads its language resource (`commonTranslate.rcc`) and its theme (`cluster_theme1.rcc`) from the
 * system image, and accepts a replacement language resource from the sdcard's `.cluster/translate` folder, whose files
 * override single files of the theme. DiPlay's resource holds the car's own languages, copied byte for byte, and the
 * car's own `cluster.qml` with [BydClusterAdasQml]'s block added. It is built here, on the head unit, from the car's
 * own files, so nothing of BYD's is kept in DiPlay, and it always matches the car's software.
 */
internal class BydClusterAdasTheme(
    private val themeFile: File,
    private val languageFile: File,
    private val translateDir: File,
    private val channel: BydClusterThemeChannel,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val sampledScenes: Set<String> = BydClusterAdasQml.SAMPLED_SCENES,
) {
    enum class Status { READY, UNSUPPORTED, INSTALLED, ALREADY, REMOVED, OTHER_ACTIVE, NO_CLUSTER, FAILED }

    /** One line of the helper's protocol: a status and `key=value` fields without `|`. */
    class Result(val status: Status, val fields: Map<String, String> = emptyMap()) {
        fun line(): String = (listOf(status.name) + fields.map { (key, value) -> "$key=${value.replace('|', '_')}" })
            .joinToString("|")

        operator fun get(key: String): String? = fields[key]
    }

    private class Built(val name: String, val bytes: ByteArray, val digest: String)

    private sealed class Build {
        class Ready(val built: Built) : Build()
        class Unsupported(val code: String) : Build()
    }

    /** The car's own files fit the block: [patched] is its `cluster.qml`, [digest] says which files it was made from. */
    private class Fitted(val patched: ByteArray, val digest: String) {
        val name: String get() = packName(digest)
    }

    private sealed class Inspection {
        class Fits(val fitted: Fitted) : Inspection()
        class Unsupported(val code: String) : Inspection()
    }

    /**
     * Read-only: whether the car's files fit, and which of DiPlay's resources is in use. Only the car's QML files are
     * read and hashed (`cluster.qml`, `Center.qml` and the two ADAS scenes); nothing is built. A resource of DiPlay's
     * that is selected is reported as `STALE` when the car's files do not fit it any more, when it was made from other
     * files, and when its file is no longer in the folder.
     */
    fun probe(): Result {
        val inspection = inspect()
        val fitted = (inspection as? Inspection.Fits)?.fitted
        val active = channel.active()
        val selected = active?.let { ownName(it.path) }
        val state = when {
            active == null -> "UNKNOWN"
            isDefault(active.path) -> "NONE"
            selected == null -> "OTHER"
            selected == fitted?.name && File(translateDir, selected).isFile -> "CURRENT"
            else -> "STALE"
        }
        return when (inspection) {
            is Inspection.Unsupported -> Result(Status.UNSUPPORTED, mapOf("code" to inspection.code, "state" to state))
            is Inspection.Fits -> Result(
                Status.READY,
                mapOf(
                    "state" to state,
                    "expected" to inspection.fitted.name,
                    "installed" to (ownPacks().firstOrNull()?.name ?: "-"),
                    "digest" to inspection.fitted.digest,
                ),
            )
        }
    }

    /** Builds the resource, writes it, selects it and asks for the interface to be reloaded. */
    fun install(refreshCode: Int): Result {
        if (refreshCode != REFRESH_12_3) return unsupported("refresh-code")
        val built = when (val build = build()) {
            is Build.Unsupported -> return unsupported(build.code)
            is Build.Ready -> build.built
        }
        val active = channel.active() ?: return Result(Status.NO_CLUSTER)
        val activeOwn = ownName(active.path)
        if (!isDefault(active.path) && activeOwn == null) return Result(Status.OTHER_ACTIVE, mapOf("path" to active.path))
        if (activeOwn == built.name && File(translateDir, built.name).isFile) {
            return Result(Status.ALREADY, mapOf("pack" to built.name))
        }
        try {
            write(built)
        } catch (error: IOException) {
            File(translateDir, built.name + PART).delete()
            return Result(Status.FAILED, mapOf("code" to "write"))
        }
        if (!channel.select(built.name) || ownName(channel.active()?.path.orEmpty()) != built.name) {
            return rollBack(built)
        }
        ownPacks().filter { it.name != built.name }.forEach { it.delete() }
        pause(RELOAD_DELAY_MILLIS)
        channel.refresh(refreshCode)
        return Result(Status.INSTALLED, mapOf("pack" to built.name))
    }

    /**
     * Back to the car's own resource after a selection that was not confirmed. The reply to a selection can be lost
     * after the cluster applied it, so the file is deleted only once the cluster shows the car's own resource again;
     * otherwise it stays, because a selected file that is gone is worse than one that is not needed.
     */
    private fun rollBack(built: Built): Result {
        channel.select(null)
        if (channel.active()?.path?.let(::isDefault) != true) return Result(Status.FAILED, mapOf("code" to "rollback"))
        File(translateDir, built.name).delete()
        return Result(Status.FAILED, mapOf("code" to "select"))
    }

    /**
     * Gives the cluster its own resource back, reloads it, and deletes DiPlay's files. With [reload] false the cluster
     * is not reloaded: it keeps what it has loaded and uses its own resource the next time it starts, which is what
     * to do while the car is not parked.
     */
    fun remove(refreshCode: Int, reload: Boolean = true): Result {
        if (refreshCode != REFRESH_12_3) return unsupported("refresh-code")
        val active = channel.active() ?: return Result(Status.NO_CLUSTER)
        val selected = ownName(active.path) != null
        if (selected) {
            if (!channel.select(null) || channel.active()?.path?.let(::isDefault) != true) {
                return Result(Status.FAILED, mapOf("code" to "select"))
            }
        }
        ownPacks().forEach { it.delete() }
        channelFiles().forEach { it.delete() }
        val reloaded = selected && reload
        if (reloaded) {
            pause(RELOAD_DELAY_MILLIS)
            channel.refresh(refreshCode)
        }
        return Result(Status.REMOVED, mapOf("reloaded" to reloaded.toString()))
    }

    private fun unsupported(code: String) = Result(Status.UNSUPPORTED, mapOf("code" to code))

    private fun <T> withArchives(failed: T, use: (QtResource.Archive, QtResource.Archive) -> T): T = try {
        QtResource.open(QtResource.FileSource(themeFile)).use { theme ->
            QtResource.open(QtResource.FileSource(languageFile)).use { languages -> use(theme, languages) }
        }
    } catch (_: IOException) {
        failed
    } catch (_: SecurityException) {
        failed
    }

    private fun inspect(): Inspection = withArchives(Inspection.Unsupported("files")) { theme, languages ->
        inspect(theme, languages)
    }

    private fun build(): Build = withArchives(Build.Unsupported("files")) { theme, languages ->
        when (val inspection = inspect(theme, languages)) {
            is Inspection.Unsupported -> Build.Unsupported(inspection.code)
            is Inspection.Fits -> assemble(languages, inspection.fitted)
        }
    }

    private fun inspect(theme: QtResource.Archive, languages: QtResource.Archive): Inspection {
        val cluster = theme.find(CLUSTER_QML) ?: return Inspection.Unsupported("no-cluster-qml")
        val center = theme.find(CENTER_QML) ?: return Inspection.Unsupported("no-center-qml")
        val clusterBytes = theme.content(cluster)
        val centerBytes = theme.content(center)
        val patched = when (val result = BydClusterAdasQml.patch(clusterBytes, centerBytes)) {
            is BydClusterAdasQml.Result.Refused -> return Inspection.Unsupported(result.code)
            is BydClusterAdasQml.Result.Patched -> result.qml
        }
        val scenes = BydClusterAdasQml.SCENE_FILES.map { path ->
            theme.find(path)?.let(theme::content) ?: return Inspection.Unsupported("no-scenes")
        }
        BydClusterAdasQml.refuseScenes(scenes, sampledScenes)?.let { return Inspection.Unsupported(it) }
        if (languages.entries.none { it.path.startsWith(LANGUAGE_DIR) && it.path.endsWith(".qm") }) {
            return Inspection.Unsupported("no-languages")
        }
        if (languages.find(CLUSTER_QML) != null) return Inspection.Unsupported("language-has-qml")
        return Inspection.Fits(Fitted(patched, BydClusterAdasQml.digest(clusterBytes + centerBytes)))
    }

    private fun assemble(languages: QtResource.Archive, fitted: Fitted): Build {
        val items = languages.entries.map { entry ->
            QtResource.Item(entry.path, entry.flags, languages.stored(entry), entry.lastModified, entry.country, entry.language)
        } + QtResource.item(CLUSTER_QML, fitted.patched, nowMillis())
        val bytes = QtResource.write(items, VERSION)
        verify(bytes, items, fitted.patched)?.let { return Build.Unsupported(it) }
        return Build.Ready(Built(fitted.name, bytes, fitted.digest))
    }

    /** Reads the built file back: every language blob is the car's own, and the file is the patched one. */
    private fun verify(bytes: ByteArray, items: List<QtResource.Item>, patched: ByteArray): String? = try {
        QtResource.open(QtResource.BytesSource(bytes)).use { back ->
            when {
                back.entries.size != items.size -> "build-check"
                items.any { item ->
                    val entry = back.find(item.path)
                    entry == null || entry.flags != item.flags || !back.stored(entry).contentEquals(item.stored)
                } -> "build-check"
                !back.content(back.find(CLUSTER_QML)!!).contentEquals(patched) -> "build-check"
                else -> null
            }
        }
    } catch (_: IOException) {
        "build-check"
    }

    private fun write(built: Built) {
        if (!translateDir.isDirectory && !translateDir.mkdirs() && !translateDir.isDirectory) throw IOException("no folder")
        val part = File(translateDir, built.name + PART)
        FileOutputStream(part).use {
            it.write(built.bytes)
            it.fd.sync()
        }
        if (part.length() != built.bytes.size.toLong() || !part.readBytes().contentEquals(built.bytes)) {
            throw IOException("written file differs")
        }
        val target = File(translateDir, built.name)
        if (!part.renameTo(target)) {
            target.delete()
            if (!part.renameTo(target)) throw IOException("rename")
        }
    }

    private fun ownPacks(): List<File> =
        translateDir.listFiles().orEmpty().filter { OWN_PACK.matches(it.name) }.sortedBy { it.name }

    private fun channelFiles(): List<File> =
        translateDir.listFiles().orEmpty().filter { it.name.startsWith(CHANNEL_PREFIX) && it.name.endsWith(".png") }

    private fun ownName(path: String): String? = path.substringAfterLast('/').takeIf { OWN_PACK.matches(it) }

    private fun isDefault(path: String) = path.isEmpty() || path.substringAfterLast('/') == LANGUAGE_FILE_NAME

    companion object {
        const val THEME_PATH = "/system/lib64/cluster_theme1.rcc"
        const val LANGUAGE_PATH = "/system/lib64/commonTranslate.rcc"
        const val TRANSLATE_PATH = "/sdcard/.cluster/translate"

        /** The cluster only loads a replacement named like this: `<theme>_<ui version>_Translate_<token>.rcc`. */
        const val PACK_PREFIX = "Di3F_DynastyOceanTranslate_V0001_Translate_"

        /** The files of the channel DiPlay's block reads: the name's prefix, see [BydClusterAdasQml]. */
        const val CHANNEL_PREFIX = "diplay-adas"

        /** The screen reload for a 12.3 inch cluster of 1920x720; BYD's tools label the other sizes 29 and 31. */
        const val REFRESH_12_3 = 30

        /** Raise when the block changes so a resource built earlier is replaced. */
        const val PATCH_REVISION = 3

        const val CLUSTER_QML = "/qml/cluster.qml"
        const val CENTER_QML = "/qml/Center.qml"
        private const val LANGUAGE_DIR = "/common/language/"
        private const val LANGUAGE_FILE_NAME = "commonTranslate.rcc"
        private const val PART = ".part"
        private const val VERSION = 2
        private const val RELOAD_DELAY_MILLIS = 1_100L

        /** The file name for a car whose `cluster.qml` and `Center.qml` hash to [digest]; the token is letters and digits. */
        fun packName(digest: String): String = PACK_PREFIX + "DiPlay" + PATCH_REVISION + digest.take(8) + ".rcc"

        val OWN_PACK = Regex("^" + PACK_PREFIX + "DiPlay[0-9]+[0-9a-f]{8}\\.rcc$")
    }
}
