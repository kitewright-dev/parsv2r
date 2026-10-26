package com.v2ray.ang.ui.server

import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.fmt.XDriveFmt
import com.v2ray.ang.fmt.XDriveFmt.Problem
import com.v2ray.ang.fmt.XDriveFmt.Storage
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.verticalScrollbar

/**
 * Creates and edits XDRIVE profiles. They are saved as JSON profiles, since XDRIVE has no
 * share link; [XDriveFmt.parseFormConfig] decides which saved configs this form may reopen.
 */
class ServerXDriveActivity : BaseComponentActivity() {

    private val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    private val subscriptionId by lazy { intent.getStringExtra("subscriptionId").orEmpty() }
    private val isRunning by lazy {
        intent.getBooleanExtra("isRunning", false) &&
            editGuid.isNotEmpty() &&
            editGuid == MmkvManager.getSelectServer()
    }

    private val viewModel: ServerXDriveViewModel by viewModels {
        viewModelFactory {
            initializer { ServerXDriveViewModel(XDriveProfileRepository, editGuid, subscriptionId) }
        }
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()

        LaunchedEffect(state.savedGuid) {
            state.savedGuid?.let { guid ->
                toastSuccess(R.string.toast_success)
                ProfileEditorResult.run { finishSaved(guid = guid, restartService = isRunning) }
            }
        }
        LaunchedEffect(state.deletedGuid) {
            state.deletedGuid?.let { guid -> ProfileEditorResult.run { finishDeleted(guid) } }
        }
        LaunchedEffect(state.saveFailed, state.deleteRefused) {
            when {
                state.saveFailed -> toast(R.string.toast_failure)
                state.deleteRefused -> toast(R.string.toast_action_not_allowed)
                else -> return@LaunchedEffect
            }
            viewModel.onMessageShown()
        }

        XDriveScreen(
            state = state,
            canDelete = editGuid.isNotEmpty() && !isRunning,
            onBackClick = { finish() },
            onDraftChange = viewModel::onDraftChange,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
        )
    }
}

@Composable
private fun XDriveScreen(
    state: XDriveUiState,
    canDelete: Boolean,
    onBackClick: () -> Unit,
    onDraftChange: (XDriveFmt.Draft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val draft = state.draft
    val problem = state.problem
    fun update(change: XDriveFmt.Draft.() -> XDriveFmt.Draft) = onDraftChange(draft.change())

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.xdrive_title),
                onBackClick = onBackClick,
                isLoading = state.isSaving,
                actions = {
                    if (canDelete) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(painterResource(R.drawable.ic_delete_24dp), stringResource(R.string.acc_delete))
                        }
                    }
                    IconButton(onClick = onSave, enabled = state.isLoaded && !state.isSaving) {
                        Icon(painterResource(R.drawable.ic_fab_check), stringResource(R.string.acc_save))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(scrollState)
                .verticalScrollbar(scrollState)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.xdrive_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            FormTextField(
                label = stringResource(R.string.server_lab_remarks),
                value = draft.remarks,
                onValueChange = { update { copy(remarks = it) } },
                isError = state.isRemarksError
            )
            ProblemTextField(
                label = R.string.server_lab_id,
                value = draft.id,
                onValueChange = { update { copy(id = it) } },
                problem = problem,
                owns = setOf(Problem.MISSING_ID, Problem.INVALID_ID)
            )
            ProblemTextField(
                label = R.string.xdrive_lab_encryption,
                value = draft.encryption,
                onValueChange = { update { copy(encryption = it) } },
                problem = problem,
                owns = setOf(Problem.MISSING_ENCRYPTION, Problem.INVALID_ENCRYPTION),
                placeholder = stringResource(R.string.xdrive_hint_encryption),
                maxLines = 4
            )
            StorageField(value = draft.storage, onValueChange = { update { copy(storage = it) } })
            ProblemTextField(
                label = if (draft.storage == Storage.GOOGLE_DRIVE) R.string.xdrive_lab_drive_folder else R.string.xdrive_lab_folder,
                value = draft.folder,
                onValueChange = { update { copy(folder = it) } },
                problem = problem,
                owns = setOf(Problem.MISSING_FOLDER, Problem.INVALID_FOLDER),
                supportingText = stringResource(R.string.xdrive_hint_folder)
            )
            when (draft.storage) {
                Storage.GOOGLE_DRIVE -> {
                    val owns = setOf(Problem.MISSING_DRIVE_SECRETS)
                    ProblemTextField(
                        label = R.string.xdrive_lab_client_id,
                        value = draft.driveClientId,
                        onValueChange = { update { copy(driveClientId = it) } },
                        problem = problem.takeIf { draft.driveClientId.isBlank() },
                        owns = owns
                    )
                    ProblemTextField(
                        label = R.string.xdrive_lab_client_secret,
                        value = draft.driveClientSecret,
                        onValueChange = { update { copy(driveClientSecret = it) } },
                        problem = problem.takeIf { draft.driveClientSecret.isBlank() },
                        owns = owns
                    )
                    ProblemTextField(
                        label = R.string.xdrive_lab_refresh_token,
                        value = draft.driveRefreshToken,
                        onValueChange = { update { copy(driveRefreshToken = it) } },
                        problem = problem.takeIf { draft.driveRefreshToken.isBlank() },
                        owns = owns
                    )
                }

                Storage.WEBDAV -> {
                    ProblemTextField(
                        label = R.string.xdrive_lab_dav_url,
                        value = draft.davUrl,
                        onValueChange = { update { copy(davUrl = it) } },
                        problem = problem,
                        owns = setOf(Problem.INVALID_DAV_URL),
                        placeholder = stringResource(R.string.xdrive_hint_dav_url),
                        keyboardType = KeyboardType.Uri,
                        supportingText = stringResource(
                            if (draft.davUrl.trim().startsWith("http://", ignoreCase = true)) {
                                R.string.xdrive_warning_http
                            } else {
                                R.string.xdrive_hint_dav_url_atomic
                            }
                        )
                    )
                    ProblemTextField(
                        label = R.string.xdrive_lab_dav_username,
                        value = draft.davUsername,
                        onValueChange = { update { copy(davUsername = it) } },
                        problem = problem,
                        owns = setOf(Problem.MISSING_DAV_USERNAME)
                    )
                    FormTextField(
                        label = stringResource(R.string.xdrive_lab_dav_password),
                        value = draft.davPassword,
                        onValueChange = { update { copy(davPassword = it) } },
                        keyboardType = KeyboardType.Password
                    )
                }

                Storage.TEMPLATE -> {
                    ProblemTextField(
                        label = R.string.xdrive_lab_template,
                        value = draft.template,
                        onValueChange = { update { copy(template = it) } },
                        problem = problem,
                        owns = setOf(Problem.INVALID_TEMPLATE),
                        maxLines = 14
                    )
                    FormTextField(
                        label = stringResource(R.string.xdrive_lab_template_secrets),
                        value = draft.templateSecrets,
                        onValueChange = { update { copy(templateSecrets = it) } },
                        maxLines = 6
                    )
                }
            }
            Text(
                text = stringResource(R.string.xdrive_note_speed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            NavigationBarsSpacer()
        }
    }

    if (showDeleteDialog) {
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_profile),
            itemName = draft.remarks,
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }
}

/** A text field that shows the message of [problem] when the problem is one of [owns]. */
@Composable
private fun ProblemTextField(
    @StringRes label: Int,
    value: String,
    onValueChange: (String) -> Unit,
    problem: Problem?,
    owns: Set<Problem>,
    placeholder: String? = null,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    maxLines: Int = 5,
) {
    val own = problem?.takeIf { it in owns }
    FormTextField(
        label = stringResource(label),
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        keyboardType = keyboardType,
        maxLines = maxLines,
        isError = own != null,
        supportingText = own?.let { stringResource(it.messageRes()) } ?: supportingText
    )
}

@Composable
private fun StorageField(value: Storage, onValueChange: (Storage) -> Unit) {
    val labels = Storage.entries.associateWith { stringResource(it.labelRes()) }
    FormDropdownField(
        label = stringResource(R.string.xdrive_lab_storage),
        value = labels.getValue(value),
        options = labels.values.toList(),
        onValueChange = { picked -> labels.entries.firstOrNull { it.value == picked }?.let { onValueChange(it.key) } }
    )
}

@StringRes
private fun Storage.labelRes(): Int = when (this) {
    Storage.GOOGLE_DRIVE -> R.string.xdrive_storage_google_drive
    Storage.WEBDAV -> R.string.xdrive_storage_webdav
    Storage.TEMPLATE -> R.string.xdrive_storage_template
}

@StringRes
internal fun Problem.messageRes(): Int = when (this) {
    Problem.MISSING_ID -> R.string.xdrive_error_id_missing
    Problem.INVALID_ID -> R.string.xdrive_error_id_invalid
    Problem.MISSING_ENCRYPTION -> R.string.xdrive_error_encryption_missing
    Problem.INVALID_ENCRYPTION -> R.string.xdrive_error_encryption_invalid
    Problem.MISSING_FOLDER -> R.string.xdrive_error_folder_missing
    Problem.INVALID_FOLDER -> R.string.xdrive_error_folder_invalid
    Problem.MISSING_DRIVE_SECRETS -> R.string.xdrive_error_drive_secrets
    Problem.INVALID_DAV_URL -> R.string.xdrive_error_dav_url
    Problem.MISSING_DAV_USERNAME -> R.string.xdrive_error_dav_username
    Problem.INVALID_TEMPLATE -> R.string.xdrive_error_template
}
