package app.clearsms.diagnostics

/**
 * The device / app facts a diagnostic report opens with. Every field here is
 * either a public build constant, an app preference, or a count - by
 * construction there is no slot for an identifier: no IMEI or serial, no
 * carrier name, no SSID, no account, no phone number, no contact. Adding a
 * field means adding it here, where `DiagnosticReportTest` pins the exact
 * allowed key set.
 */
data class SystemState(
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val appVersion: String,
    val versionCode: Int,
    val debugBuild: Boolean,
    val defaultSmsApp: Boolean,
    /** Runtime permission -> granted, keyed by the bare permission name (`READ_SMS`). */
    val permissions: Map<String, Boolean>,
    /** null when connectivity could not be read. */
    val networkConnected: Boolean?,
    /** `WIFI` / `CELLULAR` / `ETHERNET` / `VPN` / `NONE` / `UNKNOWN` - never an SSID or carrier. */
    val networkTransport: String,
    /** Number of SIM slots on the device (a hardware fact), null when unreadable. */
    val simSlots: Int?,
    val inboxSection: Boolean,
    val financeSection: Boolean,
    val alertsSection: Boolean,
    /** The default inbox filter's category name, or "ALL". */
    val defaultInboxFilter: String,
    val messageCount: Int,
    val ruleCount: Int,
    val transactionCount: Int,
    /** The bundled rules document version currently loaded, or "none". */
    val bundledRulesVersion: String,
    val timeZone: String,
) {
    /** `key: value` lines, one per fact, in a stable order. */
    fun render(): String =
        buildString {
            appendLine("android: $androidRelease (SDK $sdkInt)")
            appendLine("device: $manufacturer $model")
            appendLine("app: $appVersion ($versionCode)${if (debugBuild) " debug" else ""}")
            appendLine("defaultSmsApp: $defaultSmsApp")
            appendLine("permissions: " + permissions.entries.joinToString(" ") { "${it.key}=${if (it.value) "granted" else "denied"}" })
            appendLine("network: connected=${networkConnected ?: "unknown"} transport=$networkTransport")
            appendLine("simSlots: ${simSlots ?: "unknown"}")
            appendLine("sections: inbox=$inboxSection finance=$financeSection alerts=$alertsSection")
            appendLine("defaultInboxFilter: $defaultInboxFilter")
            appendLine("counts: messages=$messageCount rules=$ruleCount transactions=$transactionCount")
            appendLine("bundledRulesVersion: $bundledRulesVersion")
            appendLine("timeZone: $timeZone")
        }

    companion object {
        /** The header keys, in render order - the contract the report test asserts. */
        val KEYS =
            listOf(
                "android",
                "device",
                "app",
                "defaultSmsApp",
                "permissions",
                "network",
                "simSlots",
                "sections",
                "defaultInboxFilter",
                "counts",
                "bundledRulesVersion",
                "timeZone",
            )
    }
}
