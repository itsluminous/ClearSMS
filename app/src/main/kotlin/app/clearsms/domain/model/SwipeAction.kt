package app.clearsms.domain.model

/** What a horizontal swipe on an inbox row does. */
enum class SwipeAction {
    NONE,
    TOGGLE_READ,
    DELETE,
    ARCHIVE,
}
