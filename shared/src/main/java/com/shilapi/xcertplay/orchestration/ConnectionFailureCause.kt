package com.shilapi.xcertplay.orchestration

import java.io.IOException

enum class ConnectionFailureCause { CHOSEN_IPHONE_NOT_PAIRED, NO_PAIRED_IPHONE, IPHONE_NOT_CHOSEN, WIFI_DIRECT_CHANNEL_UNAVAILABLE }

/** Unlike a [com.shilapi.xcertplay.network.WirelessStartupException], it changes what the screen says, not the retries. */
open class ConnectionFailureException(val reason: ConnectionFailureCause, message: String, cause: Throwable? = null) :
    IOException(message, cause)
