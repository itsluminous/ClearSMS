package app.clearsms.ui.navigation

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.FinanceTab
import app.clearsms.ui.alerts.AlertFilter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Customisable pill order for the three tab screens.
 *
 * CONTRACT: a parallel settings stage persists these as
 * `SettingsRepository.inboxPillOrder` / `financePillOrder` / `alertsPillOrder`.
 * Until that lands, [DefaultPillOrders] emits empty lists, which
 * [orderedPills] resolves to each enum's declaration order — the integration
 * pass only has to re-point this binding at the repository flows.
 */
interface PillOrders {
    val inboxPillOrder: Flow<List<Category>>
    val financePillOrder: Flow<List<FinanceTab>>
    val alertsPillOrder: Flow<List<AlertFilter>>
}

/** Fallback source until the settings-backed pill order lands: no stored order. */
@Singleton
class DefaultPillOrders
    @Inject
    constructor() : PillOrders {
        override val inboxPillOrder: Flow<List<Category>> = flowOf(emptyList())
        override val financePillOrder: Flow<List<FinanceTab>> = flowOf(emptyList())
        override val alertsPillOrder: Flow<List<AlertFilter>> = flowOf(emptyList())
    }

@Module
@InstallIn(SingletonComponent::class)
interface PillOrdersModule {
    @Binds
    fun bindPillOrders(impl: DefaultPillOrders): PillOrders
}

/**
 * Resolves a stored pill order against the full pill set [all]:
 *
 * - pills are rendered in the [configured] order;
 * - duplicates are collapsed to their first occurrence;
 * - entries not in [all] (unknown, or internal-only values such as a stale
 *   `INFORMATIONAL`) are dropped — never a crash;
 * - anything [all] contains that [configured] omits is appended at the end —
 *   never a hidden pill. An empty [configured] therefore yields [all]
 *   (the enum's declaration order) unchanged.
 */
fun <T> orderedPills(
    configured: List<T>,
    all: List<T>,
): List<T> {
    val known = configured.distinct().filter { it in all }
    return known + all.filterNot { it in known }
}
