package com.shilapi.xcertplay.hud

/**
 * The heartbeat DiPlay's block in the cluster waits for, kept by a small loop on the head unit.
 *
 * The block in the cluster reads the size of `diplay-adas.png` in the translate folder: its width is the mode and its
 * height changes every second while DiPlay is there. A loop writes the file once a second, alternating two tiny
 * images, for as long as a lease file is fresh, and DiPlay refreshes the lease every few seconds while its map is on
 * the cluster. When DiPlay stops, crashes or loses the link, the lease runs out, the loop removes the file, and the
 * cluster goes back to BYD's own layout within seconds. The loop only copies two fixed images into one folder.
 */
internal object BydClusterAdasBeat {
    const val WORK = "/data/local/tmp"
    const val LEASE = "$WORK/diplay-adas.lease"
    private const val PID = "$WORK/diplay-adas.pid"
    private const val SCRIPT = "$WORK/diplay-adas-beat-1.sh"

    /** The loop ends when the lease is older than this; DiPlay refreshes it every [com.shilapi.xcertplay.hud.BydClusterAdasPlacement.TICK_SECONDS]. */
    const val LEASE_SECONDS = 20

    /** Two 4-byte-per-pixel PNGs, 2x2 and 2x3: width 2 is the mode, the height is what changes. */
    private const val PNG_A = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAC0lEQVR42mNgQAcAABIAAeRVjecAAAAASUVORK5CYII="
    private const val PNG_B = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAADCAYAAAC56t6BAAAAC0lEQVR42mNgwAkAABsAAVmYPeoAAAAASUVORK5CYII="

    /** The loop's lines. [dir] and [work] are fixed paths without spaces or quotes; tests point them at a temporary folder. */
    internal fun script(dir: String = BydClusterAdasTheme.TRANSLATE_PATH, work: String = WORK): List<String> {
        require(listOf(dir, work).none { path -> path.any { it == ' ' || it == '\'' || it == '"' || it == '$' } })
        val lease = "$work/diplay-adas.lease"
        val pid = "$work/diplay-adas.pid"
        return listOf(
            "[ -f $work/diplay-adas-a.png ] || echo $PNG_A | base64 -d > $work/diplay-adas-a.png",
            "[ -f $work/diplay-adas-b.png ] || echo $PNG_B | base64 -d > $work/diplay-adas-b.png",
            "trap \"rm -f $dir/diplay-adas.png $dir/.diplay-adas.tmp $pid\" EXIT",
            "echo \$\$ > $pid",
            "i=0",
            "while [ -f $lease ]; do",
            "n=\$(date +%s); m=\$(stat -c %Y $lease 2>/dev/null)",
            "[ -n \"\$m\" ] && [ \$((n - m)) -le $LEASE_SECONDS ] || break",
            "if [ \$((i % 2)) -eq 0 ]; then s=$work/diplay-adas-a.png; else s=$work/diplay-adas-b.png; fi",
            "cp \$s $dir/.diplay-adas.tmp && mv -f $dir/.diplay-adas.tmp $dir/diplay-adas.png",
            "i=\$((i + 1)); sleep 1",
            "done",
        )
    }

    /** One command for the head unit's shell: refresh the lease and start the loop unless it is already running. */
    fun keepAlive(): String {
        val lines = script().onEach { require('\'' !in it) }
        val quoted = lines.joinToString(" ") { "'$it'" }
        return listOf(
            "[ -f $SCRIPT ] || { printf '%s\\n' $quoted > $SCRIPT.tmp && mv -f $SCRIPT.tmp $SCRIPT; }",
            "touch $LEASE",
            "if grep -q diplay-adas-beat /proc/\$(cat $PID 2>/dev/null)/cmdline 2>/dev/null; then :; " +
                "else setsid sh $SCRIPT </dev/null >/dev/null 2>&1 & fi",
        ).joinToString("; ")
    }

    /** Ends the lease: the loop stops within a second and removes its file. */
    fun stop(): String = "rm -f $LEASE"
}
