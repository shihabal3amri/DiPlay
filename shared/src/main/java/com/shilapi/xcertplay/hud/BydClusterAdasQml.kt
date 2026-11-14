package com.shilapi.xcertplay.hud

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * The QML DiPlay adds to the car's own `cluster.qml`, and the checks that decide whether it fits that file and may move
 * the car's ADAS scenes.
 *
 * While DiPlay's CarPlay map is on the cluster, BYD draws the large ADAS scene in the middle of the screen, over the
 * map, and leaves its own "navigation window" box on the right empty. The block moves the scene into that box, scaled
 * and centred, and only while that box is visible and DiPlay's heartbeat is fresh; otherwise nothing changes and the
 * layout is BYD's. It reads nothing from the car except the size of one small image in the cluster's translate
 * folder, and it adds no signal, frame or setting.
 */
internal object BydClusterAdasQml {
    /** The id of the item the block adds; a file that already has it is not patched again. */
    const val MARKER = "diplayAdasId"

    /** Ids of `cluster.qml` that the block refers to. */
    private val CLUSTER_IDS = listOf("adas2dNaviWindowBg", "centerContainerId")

    /** Properties of the item `Center.qml` defines for the two ADAS scenes. */
    private val CENTER_PROPERTIES = listOf("adasUi", "adas3r1vUi")

    /** The block sets `transform` on the two scenes, which replaces any list BYD sets; none of BYD's own QML uses it. */
    private val TRANSFORM = Regex("""\btransform\b""")

    /**
     * The files of the two ADAS scenes, in the order [scenesDigest] takes them: the 2D scene and the door warning it
     * holds, then the 3R1V scene. `Center.qml` creates each scene at x 0, y 0 with a size of 608x290, and both draw
     * in the coordinates of the 1920x720 screen.
     */
    val SCENE_FILES = listOf("/qml/adas2d/Adas.qml", "/qml/adas2d/AdasDowWarning.qml", "/qml/adas2d/Adas3R1V.qml")

    /**
     * Digests, see [scenesDigest], of the scenes of the software whose scenes were sampled: every state those scenes
     * can be put in was mapped through the block's move (the paint centre 960,409 and 95 %), and nothing they draw
     * ends up off the screen. The block moves no other scenes. Software is added here once its scenes are sampled.
     */
    val SAMPLED_SCENES: Set<String> = setOf(
        "381b36ef0c23db5b326ebf76e388d8f30252538c337a3bf75a0501c46ab6bbc3", // BYD Han, DiLink 3.0, eng.build.20260204.025317
    )

    sealed class Result {
        class Patched(val qml: ByteArray) : Result()

        /** [code] is a short, stable token for the helper's protocol line. */
        class Refused(val code: String) : Result()
    }

    /**
     * Inserts the block as the last child of the root item of [cluster], the car's `/qml/cluster.qml`. [center] is the
     * car's `/qml/Center.qml`, which must define the properties the block uses. The file keeps its byte order mark and
     * line endings.
     */
    fun patch(cluster: ByteArray, center: ByteArray): Result {
        val text = decode(cluster) ?: return Result.Refused("cluster-not-text")
        val centerText = decode(center) ?: return Result.Refused("center-not-text")
        if (MARKER in text) return Result.Refused("already-patched")
        for (id in CLUSTER_IDS) {
            if (!Regex("""\bid\s*:\s*$id\b""").containsMatchIn(text)) return Result.Refused("missing-$id")
        }
        for (property in CENTER_PROPERTIES) {
            if (!Regex("""\bproperty\s+\w+\s+$property\b""").containsMatchIn(centerText)) return Result.Refused("missing-$property")
        }
        if (TRANSFORM.containsMatchIn(text) || TRANSFORM.containsMatchIn(centerText)) return Result.Refused("transform-used")
        val end = text.trimEnd().length
        if (end == 0 || text[end - 1] != '}') return Result.Refused("no-root-item")
        val crlf = "\r\n" in text
        val block = BLOCK.let { if (crlf) it.replace("\n", "\r\n") else it }
        val separator = if (crlf) "\r\n" else "\n"
        // The root item's closing brace is the last one in the file; the block goes in front of it.
        val patched = text.substring(0, end - 1).trimEnd(' ', '\t').let { head ->
            val gap = if (head.endsWith("\n")) "" else separator
            head + gap + separator + block + text.substring(end - 1)
        }
        return Result.Patched(patched.toByteArray(Charsets.UTF_8))
    }

    /** Lower-case SHA-256 of [bytes], used to tell whether the car's own file changed since DiPlay built from it. */
    fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The digest of the car's scene files [scenes], given in the order of [SCENE_FILES], one after the other. */
    fun scenesDigest(scenes: List<ByteArray>): String = digest(scenes.fold(ByteArray(0)) { all, scene -> all + scene })

    /**
     * Why the block must not move the car's [scenes] (in the order of [SCENE_FILES]), or null when it may: one of them
     * sets `transform`, which the block would replace, or they are not the scenes of any software in [sampled].
     */
    fun refuseScenes(scenes: List<ByteArray>, sampled: Set<String> = SAMPLED_SCENES): String? = when {
        scenes.any { TRANSFORM.containsMatchIn(String(it, Charsets.UTF_8)) } -> "transform-used"
        scenesDigest(scenes) !in sampled -> "scenes-unchecked"
        else -> null
    }

    private fun decode(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** The block, with `\n` line endings; the patch converts them to the file's. */
    internal val BLOCK = """
    //DiPlay ADAS placement. During map projection BYD draws the big ADAS scene in the middle of the screen, on top of the map,
    //while its own "navi window" box (adas2dNaviWindowBg, x=1256 y=225, 563x353) on the right stays empty. While that box is
    //visible (projection + big ADAS view) and DiPlay's heartbeat is alive, the scene is scaled to 95% and moved to sit centred in it.
    //Channel: one 1-row PNG in .cluster/translate/ (only the image size is read).
    //diplay-adas.png: width = mode (1 stock, 2 move into the box), height = heartbeat that changes every second.
    //Missing, unreadable or stale (3 s) = stock BYD layout. The file is read once a second while there is no heartbeat.
    Item {
        id: diplayAdasId
        visible: false
        width: 0
        height: 0
        property bool projecting: adas2dNaviWindowBg.visible
        property int tick: 0
        property int mode: 1
        property int beat: -1
        property double beatAt: 0
        property bool alive: false
        property real pct: 95
        //the scene paints around (960, 409) of its own 608x290 item; that point is put on the box centre
        property real px: adas2dNaviWindowBg.x + adas2dNaviWindowBg.width / 2 - 960 * pct / 100
        property real py: adas2dNaviWindowBg.y + adas2dNaviWindowBg.height / 2 - 409 * pct / 100
        property bool compact: projecting && alive && mode === 2
        property var adasUi: centerContainerId.adasUi
        property var adas3r1vUi: centerContainerId.adas3r1vUi
        property real sc: compact ? pct / 100 : 1
        property real tx: compact ? px : 0
        property real ty: compact ? py : 0
        Scale { id: diplayScale1; origin.x: 0; origin.y: 0; xScale: diplayAdasId.sc; yScale: diplayAdasId.sc }
        Translate { id: diplayMove1; x: diplayAdasId.tx; y: diplayAdasId.ty }
        Scale { id: diplayScale2; origin.x: 0; origin.y: 0; xScale: diplayAdasId.sc; yScale: diplayAdasId.sc }
        Translate { id: diplayMove2; x: diplayAdasId.tx; y: diplayAdasId.ty }
        function apply() {
            try { if (adasUi) adasUi.transform = [diplayScale1, diplayMove1] } catch (e) {}
            try { if (adas3r1vUi) adas3r1vUi.transform = [diplayScale2, diplayMove2] } catch (e) {}
        }
        onAdasUiChanged: apply()
        onAdas3r1vUiChanged: apply()
        Component.onCompleted: apply()
        Image {
            id: diplayModeImgId
            visible: false
            cache: false
            asynchronous: false
            source: "file:///data/cells/cell2-rw/data/media/0/.cluster/translate/diplay-adas.png?t=" + diplayAdasId.tick
        }
        Timer {
            interval: diplayAdasId.alive ? 250 : 1000
            running: true
            repeat: true
            onTriggered: {
                diplayAdasId.tick++
                var now = Date.now()
                if (diplayModeImgId.status === Image.Ready && diplayModeImgId.implicitWidth > 0) {
                    diplayAdasId.mode = diplayModeImgId.implicitWidth
                    var h = diplayModeImgId.implicitHeight
                    if (h !== diplayAdasId.beat) {
                        if (diplayAdasId.beat !== -1) diplayAdasId.beatAt = now
                        diplayAdasId.beat = h
                    }
                }
                diplayAdasId.alive = diplayAdasId.beatAt > 0 && (now - diplayAdasId.beatAt) < 3000
            }
        }
    }
""".trimStart('\n')
}
