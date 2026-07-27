package app.clearsms.domain.model

/** Outcome of running the categorization priority chain on a message. */
data class CategorizationResult(
    val category: Category,
    val subCategory: SubCategory? = null,
    val extracted: Map<String, String> = emptyMap(),
    val matchedRuleId: String? = null,
)
