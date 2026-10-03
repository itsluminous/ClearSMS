package app.clearsms.ui.inbox

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The FAB must not cover a list control at rest: in landscape at font
 * scale 2.0 the compose FAB sat over the OTP banner's X (and touched its
 * Copy). The rule is a WINDOW-HEIGHT decision ([InboxFabPlacement]), never
 * an orientation check, and the geometry it rests on is asserted here.
 */
class InboxFabPlacementTest {
    // region the measured geometry

    /** Status bar 24 + LargeTopAppBar 152 + NavigationBar 80. */
    private val chrome3Button = 256.dp

    /** ...plus the 24dp gesture-navigation inset the bar pads for. */
    private val chromeGesture = 280.dp

    /** 8dp list gutter + 12 + 16 (label line) + 44 (code line) + 12: the OTP card at font scale 1.0. */
    private val bannerAt1 = 8.dp + 84.dp

    /** 8 + 12 + 32 + 88 + 12: the same card at font scale 2.0 with linear sp scaling. */
    private val bannerAt2 = 8.dp + 144.dp

    /** The 48dp X, vertically centred on the card. */
    private fun xButton(bannerBottom: Dp): Pair<Dp, Dp> {
        val centre = 8.dp + (bannerBottom - 8.dp) / 2
        return (centre - 24.dp) to (centre + 24.dp)
    }

    @Test
    fun `411dp landscape, 3-button navigation - the X clears the FAB at scale 1 and is covered at scale 2`() {
        val content = 411.dp - chrome3Button // 155dp; the FAB owns 83-155
        val (top1, bottom1) = xButton(bannerAt1) // 26-74
        assertThat(InboxFabPlacement.inFootprint(content, top1, bottom1)).isFalse()
        val (top2, bottom2) = xButton(bannerAt2) // 56-104
        assertThat(InboxFabPlacement.inFootprint(content, top2, bottom2)).isTrue()
        // The overlap the user saw: the FAB's top edge cuts 21dp into the X.
        assertThat(bottom2 - (content - InboxFabPlacement.FabFootprint)).isEqualTo(21.dp)
    }

    @Test
    fun `411dp landscape, gesture navigation - the X is covered even at scale 1`() {
        val content = 411.dp - chromeGesture // 131dp; the FAB owns 59-131
        val (top, bottom) = xButton(bannerAt1)
        assertThat(InboxFabPlacement.inFootprint(content, top, bottom)).isTrue()
    }

    @Test
    fun `a 360dp landscape window gives the FAB over two thirds of the content area`() {
        val content = 360.dp - chrome3Button // 104dp
        assertThat(InboxFabPlacement.FabFootprint / content).isGreaterThan(0.69f)
        assertThat(InboxFabPlacement.inFootprint(content, xButton(bannerAt1).first, xButton(bannerAt1).second)).isTrue()
    }

    @Test
    fun `the tallest banner constant is the OTP card at scale 2 with its gutter`() {
        assertThat(InboxFabPlacement.TallestBannerAtRest).isEqualTo(bannerAt2)
        assertThat(InboxFabPlacement.RestChrome).isEqualTo(chromeGesture)
        // 56dp FAB + the Scaffold's 16dp margin.
        assertThat(InboxFabPlacement.FabFootprint).isEqualTo(56.dp + 16.dp)
    }

    // endregion

    // region the rule

    @Test
    fun `the limit is derived from the geometry, not borrowed - and sits above Material's compact height`() {
        assertThat(InboxFabPlacement.ShortWindowLimit)
            .isEqualTo(InboxFabPlacement.RestChrome + InboxFabPlacement.TallestBannerAtRest + InboxFabPlacement.FabFootprint)
        assertThat(InboxFabPlacement.ShortWindowLimit).isEqualTo(504.dp)
        assertThat(InboxFabPlacement.ShortWindowLimit).isAtLeast(480.dp)
    }

    @Test
    fun `every phone in landscape is short - the compose action moves into the app bar`() {
        listOf(320.dp, 360.dp, 384.dp, 411.dp, 432.dp, 448.dp).forEach { height ->
            assertThat(InboxFabPlacement.placement(height)).isEqualTo(FabPlacement.APP_BAR)
        }
    }

    @Test
    fun `a tablet in landscape is NOT short - it keeps its floating FAB`() {
        // 600x960 7-inch, 800x1280 10-inch, Pixel Tablet, unfolded foldable.
        listOf(600.dp, 720.dp, 800.dp, 848.dp, 1024.dp).forEach { height ->
            assertThat(InboxFabPlacement.placement(height)).isEqualTo(FabPlacement.FLOATING)
        }
    }

    @Test
    fun `a phone in portrait keeps its FAB`() {
        listOf(640.dp, 731.dp, 800.dp, 915.dp).forEach { height ->
            assertThat(InboxFabPlacement.placement(height)).isEqualTo(FabPlacement.FLOATING)
        }
    }

    @Test
    fun `split-screen is decided by the app's own window height, as it should be`() {
        // Half of a portrait phone: genuinely short, whatever the orientation says.
        assertThat(InboxFabPlacement.placement(400.dp)).isEqualTo(FabPlacement.APP_BAR)
        // Half of a 1280dp-tall tablet: not short - the FAB stays.
        assertThat(InboxFabPlacement.placement(640.dp)).isEqualTo(FabPlacement.FLOATING)
        // Exactly at the limit the banner and the footprint just fit.
        assertThat(InboxFabPlacement.placement(InboxFabPlacement.ShortWindowLimit)).isEqualTo(FabPlacement.FLOATING)
        assertThat(InboxFabPlacement.placement(InboxFabPlacement.ShortWindowLimit - 1.dp)).isEqualTo(FabPlacement.APP_BAR)
    }

    @Test
    fun `on any window the rule keeps floating, a single banner at rest clears the FAB - at every font scale`() {
        var height = InboxFabPlacement.ShortWindowLimit
        while (height <= 1400.dp) {
            val content = height - InboxFabPlacement.RestChrome
            listOf(bannerAt1, bannerAt2).forEach { banner ->
                // The whole banner, controls included, lies above the footprint.
                assertThat(InboxFabPlacement.inFootprint(content, 8.dp, banner)).isFalse()
            }
            height += 1.dp
        }
    }

    @Test
    fun `under a floating FAB the list reserves at least the footprint so every row can scroll clear`() {
        val floating = InboxFabPlacement.listBottomPadding(FabPlacement.FLOATING)
        assertThat(floating).isAtLeast(InboxFabPlacement.FabFootprint)
        assertThat(floating).isEqualTo(88.dp)
        // Nothing floats, nothing is reserved: no dead strip under the list.
        assertThat(InboxFabPlacement.listBottomPadding(FabPlacement.APP_BAR)).isEqualTo(0.dp)
    }

    // endregion

    // region source contract - the screen uses the rule, and only the rule

    private val inbox = File("src/main/kotlin/app/clearsms/ui/inbox/InboxScreen.kt").readText()

    @Test
    fun `the screen decides by window height, never by orientation`() {
        assertThat(inbox).contains("LocalWindowInfo.current.containerSize.height.toDp()")
        assertThat(inbox).contains("InboxFabPlacement.placement(windowHeight)")
        val uiSources =
            File("src/main/kotlin/app/clearsms/ui").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        // "Landscape" is a proxy that is wrong on tablets and in split-screen.
        assertThat(uiSources).doesNotContain("ORIENTATION_LANDSCAPE")
        assertThat(uiSources).doesNotContain("ORIENTATION_PORTRAIT")
        assertThat(uiSources).doesNotContain("LocalConfiguration.current.orientation")
    }

    @Test
    fun `the FAB floats only when the rule says so, and still yields to a snackbar and to selection`() {
        assertThat(inbox).contains("if (!selection.active && !snackbarShowing && fabPlacement == FabPlacement.FLOATING) {")
        assertThat(inbox).contains("FloatingActionButton(onClick = onCompose)")
    }

    @Test
    fun `in a short window the compose affordance is in the app bar, first, not gone`() {
        val action = inbox.indexOf("if (fabPlacement == FabPlacement.APP_BAR) {")
        assertThat(action).isGreaterThan(-1)
        val button = inbox.indexOf("label = stringResource(R.string.action_compose),", action)
        assertThat(button).isGreaterThan(action)
        assertThat(inbox.substring(action, button)).contains("TooltipIconButton(")
        assertThat(inbox.substring(button, button + 200)).contains("onClick = onCompose,")
        // Before Search and Settings: it is the tab's primary action.
        val shared = inbox.indexOf("SearchSettingsActions(onSearch = onSearch, onSettings = onSettings)")
        assertThat(shared).isGreaterThan(button)
    }

    @Test
    fun `the list's bottom clearance comes from the rule`() {
        assertThat(inbox).contains("PaddingValues(bottom = InboxFabPlacement.listBottomPadding(fabPlacement))")
        assertThat(inbox).contains("contentPadding = listClearance")
    }

    // endregion
}
