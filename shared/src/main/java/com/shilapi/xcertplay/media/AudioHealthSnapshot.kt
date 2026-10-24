package com.shilapi.xcertplay.media

/** Separate short line: important counters must not be lost to the export's 700-character cap. */
internal data class AudioHealthSnapshot(
    val audioType: String,
    val queuedMs: Long,
    val targetMs: Long,
    val queuePackets: Int,
    val queueWaitMaxMs: Long,
    val workerCycleMaxMs: Long,
    val receiveGapMaxMs: Long,
    val underrunsDelta: Int,
    val rebuffers: Int,
    val actualRate: Int,
    val trimEnabled: Boolean,
) {
    fun line() = "Audio: health audioType=${audioType.take(24)} queuedMs=$queuedMs targetMs=$targetMs " +
        "queuePackets=$queuePackets queueWaitMaxMs=$queueWaitMaxMs workerCycleMaxMs=$workerCycleMaxMs " +
        "receiveGapMaxMs=$receiveGapMaxMs underrunsDelta=$underrunsDelta rebuffers=$rebuffers " +
        "actualPlaybackRate=$actualRate trimEnabled=$trimEnabled"
}
