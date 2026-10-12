package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.port.CategoryRoleRepository
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage

/** One change to a form's fee, given the categories the form currently lists. */
internal typealias FeeChange = (FeeFormState, List<Category>) -> FeeFormState

/**
 * Reads the Fees binding again after category changes, with a retry for a failed lookup. Each form
 * passes [updateFee], which applies a change to its own fee state, so both forms share these steps.
 */
internal class FeeCategoryReader(
    private val scope: CoroutineScope,
    private val roles: CategoryRoleRepository,
    private val errors: ErrorReporter,
    private val updateFee: (FeeChange) -> Unit,
) {
    private var job: Job? = null

    fun start() {
        job?.cancel()
        updateFee { fee, _ -> fee.withRoleLoading() }
        job = scope.launch {
            try {
                val categoryId = roles.categoryFor(CategoryRole.FEES)
                ensureActive()
                updateFee { fee, categories -> fee.withRole(categoryId, null, categories) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                ensureActive()
                val message = e.toUiMessage()
                if (message == UiMessage.Unexpected) errors.report("Fee category could not be read", e)
                updateFee { fee, categories -> fee.withRole(null, message, categories) }
            }
        }
    }
}
