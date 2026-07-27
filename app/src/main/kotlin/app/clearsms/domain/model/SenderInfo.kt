package app.clearsms.domain.model

/** Resolved identity of an SMS sender ID from the bundled sender directory. */
data class SenderInfo(
    val name: String,
    val category: Category,
    val sub: String?,
)
