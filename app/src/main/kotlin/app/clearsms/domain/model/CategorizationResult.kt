package app.clearsms.domain.model

/** Outcome of running the categorization priority chain on a message. */
data class CategorizationResult(
    val category: Category,
    val subCategory: SubCategory? = null,
    val extracted: Map<String, String> = emptyMap(),
    /**
     * Rule extracts resolved to typed values (see [ExtractedValue]); keys
     * whose captures failed to parse as their declared/inferred type are
     * absent here but still present raw in [extracted].
     */
    val typed: Map<String, ExtractedValue> = emptyMap(),
    val matchedRuleId: String? = null,
)
