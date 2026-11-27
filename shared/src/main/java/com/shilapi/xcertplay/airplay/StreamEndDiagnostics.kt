package com.shilapi.xcertplay.airplay

/**
 * Report lines that say why a CarPlay screen stream or the event channel ended (#1378).
 *
 * Until these lines, only logcat had the reason: a report showed that the main screen stream ended
 * and the session closed, but not whether the iPhone closed it or DiPlay could not read it.
 * A line holds sizes, counters and an exception's class and message, never stream contents.
 * Field names avoid "body=" and a "name" suffix, because DiagnosticRedactor drops a line with either.
 */
object StreamEndDiagnostics {
    /** The longest exception message a line keeps. */
    const val MAX_MESSAGE_CHARS = 120
    private const val MAX_CLASS_CHARS = 80
    private const val MAX_COMMAND_CHARS = 40
    private val unsafeClassChars = Regex("[^A-Za-z0-9_$]")
    private val lineBreaks = Regex("[\\p{Cntrl}\\s]+")

    /** "SocketException: Connection reset": the class and a one-line message cut to [MAX_MESSAGE_CHARS]. */
    fun describe(error: Throwable): String {
        val message = error.message?.replace(lineBreaks, " ")?.trim()?.take(MAX_MESSAGE_CHARS)
        return if (message.isNullOrEmpty()) errorClass(error) else "${errorClass(error)}: $message"
    }

    /** The iPhone closed the screen connection between two messages. */
    fun screenPeerEof(): String = "peer EOF"

    /** The iPhone closed the screen connection inside a message, after [received] of [expected] bytes of its [part]. */
    fun screenPeerEofInside(part: String, received: Int, expected: Int): String =
        "peer EOF in $part after $received of $expected bytes"

    /** A header gave a body size DiPlay does not read; [bodySize] is the raw unsigned 32-bit field. */
    fun screenBodySizeOutOfRange(bodySize: Int, max: Int): String =
        "body size out of range bodySize=${bodySize.toLong() and 0xffff_ffffL} max=$max"

    /** Sealed frame [frame] (the nonce counter) of [bodySize] bytes did not open. */
    fun screenDecryptFailed(frame: Long, bodySize: Int, error: Throwable): String =
        "frame decrypt failed frame=$frame bodySize=$bodySize error=${errorClass(error)}"

    /** Reading the screen connection failed. */
    fun screenSocketError(error: Throwable): String = "socket error ${describe(error)}"

    /** Handling a received message failed; not a read error. */
    fun screenReceiveError(error: Throwable): String = "receive error ${describe(error)}"

    /** The iPhone never got a screen connection DiPlay could read. */
    fun screenAcceptFailed(error: Throwable): String = "accept failed ${describe(error)}"

    /** The line for screen stream [type]; [reason] is one of the screen reasons above. */
    fun screenStreamEnded(type: Int, reason: String): String = "screen stream ended type=$type reason=$reason"

    /** An event-channel record did not open. [encrypted] counts the buffered encrypted bytes. */
    fun eventDecryptFailed(encrypted: Int, error: Throwable): String =
        "airplay event decrypt failed encrypted=$encrypted error=${errorClass(error)}"

    fun eventReadFailed(error: Throwable): String = "airplay event read failed error=${describe(error)}"

    /** DiPlay could not send its own command [type] on the event channel. */
    fun eventCommandFailed(type: Any?, error: Throwable): String =
        "airplay event command failed type=${type.toString().replace(lineBreaks, " ").take(MAX_COMMAND_CHARS)} error=${describe(error)}"

    fun eventConnectionClosed(reason: String): String = "airplay event connection closed reason=$reason"

    private fun errorClass(error: Throwable): String =
        error.javaClass.simpleName.ifEmpty { error.javaClass.name.substringAfterLast('.') }
            .take(MAX_CLASS_CHARS).replace(unsafeClassChars, "?")
}
