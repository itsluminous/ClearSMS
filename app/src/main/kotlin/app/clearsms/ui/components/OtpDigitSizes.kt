package app.clearsms.ui.components

import app.clearsms.domain.model.OtpDisplaySize

/**
 * Font size (sp) of the OTP digits in the settings dialog preview.
 * Strictly increasing from Option 1 to Option 5; Option 2 (the default)
 * keeps the size the old "Default" entry rendered at.
 */
fun otpPreviewFontSp(size: OtpDisplaySize): Int =
    when (size) {
        OtpDisplaySize.OPTION_1 -> 16
        OtpDisplaySize.OPTION_2 -> 20
        OtpDisplaySize.OPTION_3 -> 24
        OtpDisplaySize.OPTION_4 -> 30
        OtpDisplaySize.OPTION_5 -> 36
    }

/**
 * Font size (sp) of the OTP digits in the in-app inbox banner. Larger than
 * the dialog preview (the banner is the primary reading surface) but the
 * same strictly increasing progression.
 */
fun otpBannerFontSp(size: OtpDisplaySize): Int =
    when (size) {
        OtpDisplaySize.OPTION_1 -> 26
        OtpDisplaySize.OPTION_2 -> 32
        OtpDisplaySize.OPTION_3 -> 38
        OtpDisplaySize.OPTION_4 -> 44
        OtpDisplaySize.OPTION_5 -> 50
    }
