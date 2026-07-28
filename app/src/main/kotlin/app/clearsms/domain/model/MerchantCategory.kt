package app.clearsms.domain.model

/** Spending category assigned to a transaction based on merchant keywords. */
enum class MerchantCategory {
    FOOD,
    SHOPPING,
    TRANSPORTATION,
    TRAVEL_HOTEL,
    ENTERTAINMENT,
    EDUCATION,
    HOSPITAL,
    UTILITY_BILL,
    INVESTMENT,
    TRANSFER,

    /** Prepaid mobile / DTH / data top-ups. */
    RECHARGE,
    OTHER,
}
