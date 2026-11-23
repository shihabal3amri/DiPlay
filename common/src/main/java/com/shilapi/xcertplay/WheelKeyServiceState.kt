package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import com.shilapi.xcertplay.host.R

/**
 * What Android's accessibility manager lists about DiPlay's wheel key service, from `dumpsys accessibility`.
 * A list the dump does not have is null: Android versions and head units print different ones. Nothing else of
 * the dump is kept, because it also names other apps' services and windows.
 */
internal data class WheelKeyServiceState(
    val enabled: Boolean? = null,
    val bound: Boolean? = null,
    val binding: Boolean? = null,
    val crashed: Boolean? = null,
) {
    /**
     * Android skips a service in either list when it starts the allowed ones, so rewriting the allowed list
     * does not start it again (#606, #1355). A service that is only just starting is also listed as binding.
     */
    val stuck: Boolean get() = bound != true && (binding == true || crashed == true)

    /** For the diagnostic report. */
    override fun toString() =
        "enabled=${text(enabled)} bound=${text(bound)} binding=${text(binding)} crashed=${text(crashed)}"

    private fun text(value: Boolean?) = value?.toString() ?: "unknown"

    companion object {
        fun read(context: Context, shell: (String) -> String?): WheelKeyServiceState {
            val component = ComponentName(context, WheelKeyService::class.java)
            return parse(
                WheelKeyService.checkedShell("dumpsys accessibility", shell),
                setOf(component.flattenToString(), component.flattenToShortString()),
                labels(context),
            )
        }

        /** [labels]: the list of bound services names a service by its label, in the system's language. */
        fun parse(dump: String?, components: Set<String>, labels: Set<String>): WheelKeyServiceState {
            val all = dump.orEmpty()
            val user = all.split(USER_STATE).firstOrNull { "currentUser=true" in it } ?: all
            fun section(heading: String): String? {
                val start = Regex("(?m)^[ \\t]*$heading:[ \\t]*\\{").find(user) ?: return null
                val rest = user.substring(start.range.last + 1)
                return rest.substring(0, SECTION_END.find(rest)?.range?.first ?: rest.length)
            }
            fun named(text: String) = components.any {
                Regex("(?<![A-Za-z0-9_.$])${Regex.escape(it)}(?![A-Za-z0-9_.$])").containsMatchIn(text)
            }
            return WheelKeyServiceState(
                enabled = section("Enabled services")?.let(::named),
                // Android 9 and older head this list "services" alone.
                bound = section("(?:Bound )?services")?.let { text -> named(text) || labels.any { "label=$it," in text } },
                binding = section("Binding services")?.let(::named),
                crashed = section("Crashed services")?.let(::named),
            )
        }

        private val USER_STATE = Regex("(?=User state\\[)")

        // The next list or a blank line. The bound services may take a line each, starting with "Service[".
        private val SECTION_END = Regex("\\r?\\n[ \\t]*(?:\\r?\\n|[A-Za-z][A-Za-z ]*:[ \\t]*\\{)")

        // The system's language need not be DiPlay's, so every translation counts.
        private fun labels(context: Context): Set<String> =
            (listOf(context) + AppLocale.ALL.mapNotNull(AppLocale::locale).map { locale ->
                context.createConfigurationContext(Configuration().apply { setLocale(locale) })
            }).map { it.getString(R.string.wheel_key_service_label) }.toSet()
    }
}
