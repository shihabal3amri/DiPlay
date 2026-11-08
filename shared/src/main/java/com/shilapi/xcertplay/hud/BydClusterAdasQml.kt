package com.shilapi.xcertplay.hud

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * The QML DiPlay adds to the car's own `cluster.qml`, and the checks that decide whether it fits that file.
 *
 * While DiPlay's CarPlay map is on the cluster, BYD draws the large ADAS scene in the middle of the screen, over the
 * map, and leaves its own "navigation window" box on the right empty. The block moves the scene into that box, scaled
 * and centred, and only while that box is visible and DiPlay's heartbeat is fresh; otherwise nothing changes and the
 * layout is BYD's. It can also recolor the car picture of the 2D ADAS scenes with a small shader, from a color that
 * DiPlay writes as three more small images, and it switches that off by itself if the shader does not compile. It reads
 * nothing from the car except the size of six small images in the cluster's translate folder, and it adds no signal,
 * frame or setting.
 */
internal object BydClusterAdasQml {
    /** The id of the item the block adds; a file that already has it is not patched again. */
    const val MARKER = "diplayAdasId"

    /** Ids of `cluster.qml` that the block refers to. */
    private val CLUSTER_IDS = listOf("adas2dNaviWindowBg", "centerContainerId")

    /** Properties of the item `Center.qml` defines for the two ADAS scenes. */
    private val CENTER_PROPERTIES = listOf("adasUi", "adas3r1vUi")

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
    //visible (projection + big ADAS view) and DiPlay's heartbeat is alive, the scene is scaled and moved to sit centred in it.
    //Channel: 1-row PNGs in .cluster/translate/ (only the image size is read).
    //diplay-adas.png: width = mode (1 stock, 2 move into the box), height = heartbeat that changes every second.
    //diplay-adas-p.png (optional): width/height = scene left/top px (both +1), overrides the centred default.
    //diplay-adas-s.png (optional): width = scale in percent (20-100), default 95.
    //Missing, unreadable or stale (3 s) = stock BYD layout.
    //Car color, apart from the heartbeat: the car picture of the 2D ADAS scenes (the Image whose source has self_car) is drawn
    //through a small shader that tints the body paint and leaves glass, tyres and lights alone. 1-row PNGs again:
    //diplay-adas-ch.png width = hue in degrees + 1, diplay-adas-cs.png width = saturation in percent + 1,
    //diplay-adas-cv.png width = brightness in percent (30-150). Any of the three missing or unreadable = BYD's own car.
    //If the shader does not compile on this unit the color switches itself off.
    Item {
        id: diplayAdasId
        objectName: "diplayAdas"
        visible: false
        width: 0
        height: 0
        property bool projecting: adas2dNaviWindowBg.visible
        property int tick: 0
        property int mode: 1
        property int beat: -1
        property double beatAt: 0
        property bool alive: false
        property bool havePos: false
        property real filePx: 0
        property real filePy: 0
        property bool haveSize: false
        property real filePct: 95
        property real pct: haveSize ? filePct : 95
        //the scene paints around (960, 409) of its own 608x290 item; that point is put on the box centre
        property real defPx: adas2dNaviWindowBg.x + adas2dNaviWindowBg.width / 2 - 960 * pct / 100
        property real defPy: adas2dNaviWindowBg.y + adas2dNaviWindowBg.height / 2 - 409 * pct / 100
        property real px: havePos ? filePx : defPx
        property real py: havePos ? filePy : defPy
        property bool compact: projecting && alive && mode === 2
        property var adasUi: centerContainerId.adasUi
        property var adas3r1vUi: centerContainerId.adas3r1vUi
        property bool colored: false
        property real carHue: 0
        property real carSat: 0
        property real carBright: 1
        property int colorTick: 0
        property bool tintBroken: false
        property var tinted: []
        property string tintShader:
            "varying highp vec2 qt_TexCoord0;\n" +
            "uniform lowp sampler2D source;\n" +
            "uniform lowp float qt_Opacity;\n" +
            "uniform mediump float tintHue;\n" +
            "uniform mediump float tintSat;\n" +
            "uniform mediump float tintBright;\n" +
            "mediump vec3 diplayHsv2Rgb(mediump vec3 c) {\n" +
            "  mediump vec3 p = abs(fract(c.xxx + vec3(1.0, 0.6666667, 0.3333333)) * 6.0 - 3.0);\n" +
            "  return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);\n" +
            "}\n" +
            "mediump vec4 diplayTint(mediump vec4 px, mediump float hue, mediump float sat, mediump float bright) {\n" +
            "  mediump vec3 rgb = px.a > 0.0 ? px.rgb / px.a : px.rgb;\n" +
            "  mediump float v = max(max(rgb.r, rgb.g), rgb.b);\n" +
            "  mediump float s = v > 0.0 ? (v - min(min(rgb.r, rgb.g), rgb.b)) / v : 0.0;\n" +
            "  mediump float w = smoothstep(0.2, 0.42, v) * (1.0 - smoothstep(0.28, 0.55, s));\n" +
            "  mediump float ns = sat * (1.0 - 0.55 * smoothstep(0.78, 1.0, v));\n" +
            "  mediump vec3 tint = diplayHsv2Rgb(vec3(hue, ns, clamp(v * bright, 0.0, 1.0)));\n" +
            "  return vec4(mix(rgb, tint, w) * px.a, px.a);\n" +
            "}\n" +
            "void main() {\n" +
            "  mediump vec4 px = texture2D(source, qt_TexCoord0);\n" +
            "  gl_FragColor = diplayTint(px, tintHue, tintSat, tintBright) * qt_Opacity;\n" +
            "}\n"
        property real sc: compact ? Math.max(20, Math.min(100, pct)) / 100 : 1
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
        //the car pictures of the 2D scenes, which BYD makes when it shows the scene and destroys when it hides it
        function findCars(item, found, depth) {
            if (!item || depth > 6 || found.length >= 4) return
            var kids = item.children
            if (!kids) return
            for (var i = 0; i < kids.length; i++) {
                try {
                    var kid = kids[i]
                    if (kid.source !== undefined && String(kid.source).indexOf("self_car") >= 0) found.push(kid)
                    else findCars(kid, found, depth + 1)
                } catch (e) {}
            }
        }
        function tintCars() {
            var found = []
            try { findCars(adasUi, found, 0) } catch (e) {}
            try { findCars(adas3r1vUi, found, 0) } catch (e) {}
            tinted = found
            for (var i = 0; i < found.length; i++) {
                try {
                    found[i].layer.effect = diplayTintId
                    found[i].layer.smooth = true
                } catch (e) {}
            }
            setTint()
        }
        //The layers are only switched here, from the timer or a scene change, and never from the shader's own status handler:
        //that handler runs inside the effect, which must not be deleted from within.
        function setTint() {
            for (var i = 0; i < tinted.length; i++) {
                try { tinted[i].layer.enabled = colored && !tintBroken } catch (e) {}
            }
        }
        onAdasUiChanged: { apply(); tintCars() }
        onAdas3r1vUiChanged: { apply(); tintCars() }
        Component.onCompleted: { apply(); tintCars() }
        Component {
            id: diplayTintId
            ShaderEffect {
                property variant source
                property real tintHue: diplayAdasId.carHue / 360
                property real tintSat: diplayAdasId.carSat
                property real tintBright: diplayAdasId.carBright
                fragmentShader: diplayAdasId.tintShader
                onStatusChanged: if (status === ShaderEffect.Error) diplayAdasId.tintBroken = true
            }
        }
        Image {
            id: diplayModeImgId
            visible: false
            cache: false
            asynchronous: false
            source: "file:///data/cells/cell2-rw/data/media/0/.cluster/translate/diplay-adas.png?t=" + diplayAdasId.tick
        }
        Image {
            id: diplayPosImgId
            visible: false
            cache: false
            asynchronous: false
            source: "file:///data/cells/cell2-rw/data/media/0/.cluster/translate/diplay-adas-p.png?t=" + diplayAdasId.tick
        }
        Image {
            id: diplaySizeImgId
            visible: false
            cache: false
            asynchronous: false
            source: "file:///data/cells/cell2-rw/data/media/0/.cluster/translate/diplay-adas-s.png?t=" + diplayAdasId.tick
        }
        Image {
            id: diplayHueImgId
            visible: false
            cache: false
            asynchronous: false
            source: "file:///data/cells/cell2-rw/data/media/0/.cluster/translate/diplay-adas-ch.png?t=" + diplayAdasId.colorTick
        }
        Image {
            id: diplaySatImgId
            visible: false
            cache: false
            asynchronous: false
            source: "file:///data/cells/cell2-rw/data/media/0/.cluster/translate/diplay-adas-cs.png?t=" + diplayAdasId.colorTick
        }
        Image {
            id: diplayBriImgId
            visible: false
            cache: false
            asynchronous: false
            source: "file:///data/cells/cell2-rw/data/media/0/.cluster/translate/diplay-adas-cv.png?t=" + diplayAdasId.colorTick
        }
        Timer {
            interval: 250
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
                var okPos = diplayPosImgId.status === Image.Ready && diplayPosImgId.implicitWidth > 0
                diplayAdasId.havePos = okPos
                if (okPos) {
                    diplayAdasId.filePx = diplayPosImgId.implicitWidth - 1
                    diplayAdasId.filePy = diplayPosImgId.implicitHeight - 1
                }
                var okSize = diplaySizeImgId.status === Image.Ready && diplaySizeImgId.implicitWidth > 0
                diplayAdasId.haveSize = okSize
                if (okSize) diplayAdasId.filePct = Math.max(20, Math.min(100, diplaySizeImgId.implicitWidth))
                diplayAdasId.alive = diplayAdasId.beatAt > 0 && (now - diplayAdasId.beatAt) < 3000
                //the color files are looked at every second while there is a color, every two seconds while there is none
                if (diplayAdasId.tick % (diplayAdasId.colored ? 4 : 8) === 0) diplayAdasId.colorTick++
                var okHue = diplayHueImgId.status === Image.Ready && diplayHueImgId.implicitWidth > 0
                var okSat = diplaySatImgId.status === Image.Ready && diplaySatImgId.implicitWidth > 0
                var okBri = diplayBriImgId.status === Image.Ready && diplayBriImgId.implicitWidth > 0
                if (okHue && okSat && okBri) {
                    diplayAdasId.carHue = Math.max(0, Math.min(359, diplayHueImgId.implicitWidth - 1))
                    diplayAdasId.carSat = Math.max(0, Math.min(100, diplaySatImgId.implicitWidth - 1)) / 100
                    diplayAdasId.carBright = Math.max(30, Math.min(150, diplayBriImgId.implicitWidth)) / 100
                }
                var wasColored = diplayAdasId.colored
                diplayAdasId.colored = okHue && okSat && okBri
                //the cars are looked for again every second and when the color comes on, the layers follow the color on every tick
                if (diplayAdasId.colored && (!wasColored || diplayAdasId.tick % 4 === 0)) diplayAdasId.tintCars()
                else diplayAdasId.setTint()
            }
        }
    }
""".trimStart('\n')
}
