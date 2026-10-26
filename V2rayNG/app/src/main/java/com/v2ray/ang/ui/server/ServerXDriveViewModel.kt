package com.v2ray.ang.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.fmt.XDriveFmt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the XDRIVE form reads and writes its profiles; the app's store is [XDriveProfileRepository]. */
interface XDriveProfileStore {
    /** The raw JSON of a saved profile, or null. */
    fun loadRaw(guid: String): String?

    /** Saves [draft] as a JSON profile and returns its GUID, new or [guid] when editing. */
    fun save(guid: String, subscriptionId: String, draft: XDriveFmt.Draft, socksPort: Int?): String

    /** The SOCKS port a new profile listens on. */
    fun defaultSocksPort(): Int

    /** Removes the profile; false when it may not be removed now (it is the selected one). */
    fun delete(guid: String): Boolean
}

data class XDriveUiState(
    val draft: XDriveFmt.Draft = XDriveFmt.Draft(),
    val isLoaded: Boolean = false,
    val isRemarksError: Boolean = false,
    val problem: XDriveFmt.Problem? = null,
    val isSaving: Boolean = false,
    /** GUID of the saved profile once a save has gone through. */
    val savedGuid: String? = null,
    val saveFailed: Boolean = false,
    /** GUID of the deleted profile once a delete has gone through. */
    val deletedGuid: String? = null,
    val deleteRefused: Boolean = false,
)

class ServerXDriveViewModel(
    private val store: XDriveProfileStore,
    private val editGuid: String,
    private val subscriptionId: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(XDriveUiState())
    val state: StateFlow<XDriveUiState> = _state.asStateFlow()

    // kept so an edit is saved on the port the profile already listens on
    private var socksPort: Int? = null

    init {
        viewModelScope.launch {
            val draft = withContext(ioDispatcher) {
                if (editGuid.isEmpty()) {
                    null
                } else {
                    store.loadRaw(editGuid)?.let { raw ->
                        socksPort = XDriveFmt.socksPortOf(raw)
                        XDriveFmt.parseFormConfig(raw)
                    }
                }
            }
            _state.update { it.copy(draft = draft ?: it.draft, isLoaded = true) }
        }
    }

    fun onDraftChange(draft: XDriveFmt.Draft) {
        _state.update {
            it.copy(
                draft = draft,
                // an edit clears the message, the next save checks again
                isRemarksError = it.isRemarksError && draft.remarks.isBlank(),
                problem = null,
            )
        }
    }

    fun save() {
        val current = _state.value
        if (current.isSaving || !current.isLoaded) return
        val remarksError = current.draft.remarks.isBlank()
        val problem = XDriveFmt.validate(current.draft)
        if (remarksError || problem != null) {
            _state.update { it.copy(isRemarksError = remarksError, problem = problem) }
            return
        }
        _state.update { it.copy(isRemarksError = false, problem = null, isSaving = true, saveFailed = false) }
        viewModelScope.launch {
            val guid = runCatching {
                withContext(ioDispatcher) { store.save(editGuid, subscriptionId, current.draft, socksPort) }
            }.getOrNull()
            _state.update { it.copy(isSaving = false, savedGuid = guid, saveFailed = guid == null) }
        }
    }

    fun delete() {
        if (editGuid.isEmpty()) return
        viewModelScope.launch {
            val deleted = runCatching { withContext(ioDispatcher) { store.delete(editGuid) } }.getOrDefault(false)
            _state.update {
                if (deleted) it.copy(deletedGuid = editGuid, deleteRefused = false) else it.copy(deleteRefused = true)
            }
        }
    }

    /** Clears the one-off refusal flags once the screen has shown them. */
    fun onMessageShown() {
        _state.update { it.copy(saveFailed = false, deleteRefused = false) }
    }
}
