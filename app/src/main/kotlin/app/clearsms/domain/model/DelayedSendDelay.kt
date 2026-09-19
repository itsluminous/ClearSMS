package app.clearsms.domain.model

/**
 * How long a plain Send waits before actually dispatching, when delayed
 * sending is enabled (GitHub #40). A small fixed menu rather than a free
 * stepper: every option is long enough to read the "Sending in Ns" bar and
 * tap Cancel, and none is long enough to make sending feel broken.
 *
 * Default [SECONDS_10]: 5 s is the bare minimum to spot a typo, parse the
 * snackbar and reach Cancel (Gmail's undo-send floor), so the default sits
 * one comfortable notch above it; 30 s is the ceiling because past that a
 * "sent" message that hasn't gone anywhere starts to surprise people.
 */
enum class DelayedSendDelay(
    val seconds: Int,
) {
    SECONDS_5(5),
    SECONDS_10(10),
    SECONDS_15(15),
    SECONDS_30(30),
    ;

    val millis: Long get() = seconds * 1000L

    companion object {
        val DEFAULT = SECONDS_10
    }
}
