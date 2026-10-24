package com.shilapi.xcertplay.mfi

import java.io.File
import java.io.InputStream

/** Repairs only the local identity cache, using validated material supplied by the host. */
object LocalMfiIdentityStore {
    enum class Result { EXISTING, INSTALLED_FROM_APK, REPAIRED_FROM_APK, RESTORED_PREVIOUS, RESTORED_STAGING }

    @Synchronized
    fun ensure(directory: File, openAsset: (String) -> InputStream): Result {
        val staging = File(directory.parentFile, "${directory.name}-staging")
        val previous = File(directory.parentFile, "${directory.name}-previous")
        if (valid(directory)) return Result.EXISTING

        // Recover a process exit between the two directory renames without changing identity.
        if (!directory.exists() && valid(previous)) {
            check(previous.renameTo(directory)) { "Could not restore previous local authentication" }
            return Result.RESTORED_PREVIOUS
        }
        val hadExisting = directory.exists()
        var validatedStage = valid(staging)
        var result = Result.RESTORED_STAGING
        try {
            if (!validatedStage) {
                check(!staging.exists() || staging.deleteRecursively()) { "Could not clear authentication staging" }
                check(staging.mkdirs()) { "Could not prepare local authentication" }
                staging.setReadable(false, false); staging.setReadable(true, true)
                staging.setExecutable(false, false); staging.setExecutable(true, true)
                val usePrevious = valid(previous)
                for (name in listOf("identity.pk8", "certificate.p7b")) {
                    val file = File(staging, name)
                    val input = if (usePrevious) File(previous, name).inputStream() else openAsset(name)
                    input.use { source -> file.outputStream().use { output ->
                        source.copyTo(output)
                        output.fd.sync()
                    } }
                    file.setReadable(false, false); file.setReadable(true, true)
                    file.setWritable(false, false); file.setWritable(true, true)
                }
                // Check parsing, curve and key/certificate consistency before touching the old cache.
                LocalMfiAuthenticationClient.load(staging)
                validatedStage = true
                result = when {
                    usePrevious -> Result.RESTORED_PREVIOUS
                    hadExisting -> Result.REPAIRED_FROM_APK
                    else -> Result.INSTALLED_FROM_APK
                }
            }
            check(!previous.exists() || previous.deleteRecursively()) { "Could not prepare authentication backup" }
            if (hadExisting) check(directory.renameTo(previous)) { "Could not back up local authentication" }
            if (!staging.renameTo(directory)) {
                if (!directory.exists() && previous.exists()) previous.renameTo(directory)
                error("Could not install local authentication")
            }
            previous.deleteRecursively()
            return result
        } finally {
            // Keep a complete validated stage if a rename failed; the next launch can resume it.
            if (!validatedStage) staging.deleteRecursively()
        }
    }

    private fun valid(directory: File): Boolean = try {
        LocalMfiAuthenticationClient.load(directory)
        true
    } catch (_: Exception) {
        false
    }
}
