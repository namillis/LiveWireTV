package com.livewire.tv.feature.update

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.livewire.tv.feature.update.UpdateViewModel.UpdateUiState

/**
 * True when [state] is one the update dialog renders (so a host screen should yield focus to
 * it). Idle / Checking / UpToDate / Installing show no dialog. Pure, so it is unit-testable and
 * shared by Home (to stop fighting the dialog for focus) and the overlay.
 */
fun isDialogState(state: UpdateUiState): Boolean = when (state) {
    is UpdateUiState.Available,
    is UpdateUiState.Downloading,
    is UpdateUiState.Verifying,
    is UpdateUiState.NeedsUnknownSourcesPermission,
    is UpdateUiState.Failed,
    -> true
    else -> false
}

/**
 * The one [UpdateViewModel] instance for the app, scoped to the ACTIVITY rather than the
 * calling composable's nav-back-stack entry. Home and Settings both resolve the same instance,
 * so a check started from Settings surfaces its dialog on Home and vice versa, and there is a
 * single source of truth for the update flow (brief step 4).
 *
 * Falls back to the default (nav-entry-scoped) owner if no Activity owner is available, which
 * never happens in the running app but keeps previews/tests from crashing.
 */
@Composable
fun rememberActivityUpdateViewModel(): UpdateViewModel {
    val activity = LocalActivity.current
    val owner = activity as? androidx.lifecycle.ViewModelStoreOwner
        ?: LocalViewModelStoreOwner.current
    return if (owner != null) hiltViewModel(owner) else hiltViewModel()
}

/**
 * Hosts the update dialog for a screen (Home, and Settings when the user checks there). Renders
 * nothing until [UpdateViewModel.state] is a state the dialog shows. Wires every dialog action
 * to the shared ViewModel, launches the "install unknown apps" settings screen when the user
 * asks, and re-checks that permission when the app resumes (so a grant made on the settings
 * screen continues the install automatically — brief step 3).
 *
 * The caller supplies [viewModel] (the activity-scoped one from
 * [rememberActivityUpdateViewModel]) so Home and Settings share the same instance.
 */
@Composable
fun UpdateOverlay(viewModel: UpdateViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // On resume, re-check the unknown-sources permission: if the user just granted it on the
    // settings screen, the download/install continues automatically.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onPermissionMaybeGranted()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    UpdateDialogHost(
        state = state,
        onUpdateNow = viewModel::download,
        onLater = viewModel::later,
        onSkip = viewModel::skip,
        onCancel = viewModel::cancel,
        onOpenSettings = {
            (state as? UpdateUiState.NeedsUnknownSourcesPermission)?.settingsIntent?.let { intent ->
                runCatching { context.startActivity(Intent(intent)) }
            }
        },
        onTryAgain = viewModel::retry,
        onDismiss = viewModel::dismiss,
    )
}
