package com.v2ray.ang.ui.server

import com.v2ray.ang.fmt.XDriveFmt
import com.v2ray.ang.fmt.XDriveFmt.Draft
import com.v2ray.ang.fmt.XDriveFmt.Problem
import com.v2ray.ang.fmt.XDriveFmt.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerXDriveViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val complete = Draft(
        remarks = "dav",
        id = "5f0c3a2e-8d21-4b7e-9a51-2c6f0e9b7d10",
        encryption = "mlkem768x25519plus.native.0rtt.key",
        storage = Storage.WEBDAV,
        folder = "tunnel",
        davUrl = "https://dav.example/dav",
        davUsername = "jmc",
        davPassword = "pw",
    )

    private class FakeStore : XDriveProfileStore {
        val raws = mutableMapOf<String, String>()
        val saved = mutableListOf<Triple<String, Draft, Int?>>()
        var failSave = false
        var selected: String? = null

        override fun loadRaw(guid: String) = raws[guid]

        override fun save(guid: String, subscriptionId: String, draft: Draft, socksPort: Int?): String {
            if (failSave) error("disk full")
            saved += Triple(guid, draft, socksPort)
            val key = guid.ifEmpty { "new-guid" }
            raws[key] = XDriveFmt.build(draft, socksPort ?: defaultSocksPort())
            return key
        }

        override fun defaultSocksPort() = 10808

        override fun delete(guid: String): Boolean {
            if (guid == selected) return false
            raws.remove(guid)
            return true
        }
    }

    private val store = FakeStore()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(guid: String = "") = ServerXDriveViewModel(store, guid, "sub", dispatcher)

    @Test
    fun startsEmptyForANewProfile() {
        val state = viewModel().state.value
        assertTrue(state.isLoaded)
        assertEquals(Draft(), state.draft)
        assertNull(state.problem)
        assertNull(state.savedGuid)
    }

    @Test
    fun loadsAProfileTheFormWrote() {
        store.raws["g1"] = XDriveFmt.build(complete, 2080)
        assertEquals(complete, viewModel("g1").state.value.draft)
    }

    @Test
    fun startsEmptyWhenTheSavedConfigIsNotOneTheFormWrote() {
        store.raws["g1"] = """{"outbounds":[]}"""
        assertEquals(Draft(), viewModel("g1").state.value.draft)
    }

    @Test
    fun refusesToSaveWithoutRemarksOrWithAProblem() {
        val vm = viewModel()
        vm.onDraftChange(complete.copy(remarks = " ", encryption = ""))
        vm.save()
        assertTrue(vm.state.value.isRemarksError)
        assertEquals(Problem.MISSING_ENCRYPTION, vm.state.value.problem)
        assertTrue(store.saved.isEmpty())

        // typing remarks clears that mark, the problem waits for the next save
        vm.onDraftChange(vm.state.value.draft.copy(remarks = "x"))
        assertFalse(vm.state.value.isRemarksError)
        assertEquals(Problem.MISSING_ENCRYPTION, vm.state.value.problem)
    }

    @Test
    fun savesACompleteDraftAndReportsItsGuid() {
        val vm = viewModel()
        vm.onDraftChange(complete)
        vm.save()
        assertEquals("new-guid", vm.state.value.savedGuid)
        assertFalse(vm.state.value.isSaving)
        assertEquals(listOf(Triple("", complete, null as Int?)), store.saved)
    }

    @Test
    fun keepsThePortOfAnEditedProfile() {
        store.raws["g1"] = XDriveFmt.build(complete, 2080)
        val vm = viewModel("g1")
        vm.onDraftChange(vm.state.value.draft.copy(remarks = "renamed"))
        vm.save()
        assertEquals(2080, store.saved.single().third)
        assertEquals("g1", vm.state.value.savedGuid)
    }

    @Test
    fun reportsAFailedSave() {
        store.failSave = true
        val vm = viewModel()
        vm.onDraftChange(complete)
        vm.save()
        assertTrue(vm.state.value.saveFailed)
        assertNull(vm.state.value.savedGuid)
        vm.onMessageShown()
        assertFalse(vm.state.value.saveFailed)
    }

    @Test
    fun deletesUnlessTheProfileIsSelected() {
        store.raws["g1"] = XDriveFmt.build(complete, 10808)
        store.selected = "g1"
        val vm = viewModel("g1")
        vm.delete()
        assertTrue(vm.state.value.deleteRefused)
        assertNull(vm.state.value.deletedGuid)

        store.selected = null
        vm.delete()
        assertEquals("g1", vm.state.value.deletedGuid)
        assertNull(store.raws["g1"])
    }
}
