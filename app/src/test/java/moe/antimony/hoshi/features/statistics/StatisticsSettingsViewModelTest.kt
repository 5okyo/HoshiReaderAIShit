package moe.antimony.hoshi.features.statistics

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.settings.SettingsLoadState
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsSettingsViewModelTest {
    @Test
    fun delayedPreferencesWaitForBothStoredSourcesAndArchiveCount() = runTest {
        val firstSettings = CompletableDeferred<ReaderSettings>()
        val firstSync = CompletableDeferred<Boolean>()
        val persisted = ReaderSettings(statisticsResetMinutes = 315)
        val model = StatisticsSettingsViewModel(
            flow { emit(firstSettings.await()) },
            flow { emit(firstSync.await()) },
            {},
            StatisticsRepositoryFake().apply { archiveCount = 4 },
            backgroundScope,
        )
        runCurrent()
        assertEquals(SettingsLoadState.Loading, model.uiState.value.loadState)
        firstSettings.complete(persisted)
        runCurrent()
        assertEquals(SettingsLoadState.Loading, model.uiState.value.loadState)
        firstSync.complete(true)
        runCurrent()
        val loaded = model.uiState.value.loadState as SettingsLoadState.Loaded
        assertEquals(persisted, loaded.value.settings)
        assertTrue(loaded.value.syncEnabled)
        assertEquals(4, loaded.value.archivedBookCount)
    }

    @Test
    fun delayedArchiveSummaryIsRequiredBeforeControlsAreReady() = runTest {
        val count = CompletableDeferred<Int>()
        val model = StatisticsSettingsViewModel(
            MutableStateFlow(ReaderSettings()),
            MutableStateFlow(false),
            {},
            object : StatisticsRepositoryFake() {
                override suspend fun loadArchiveSummary() = count.await()
            },
            backgroundScope,
        )
        runCurrent()
        assertEquals(SettingsLoadState.Loading, model.uiState.value.loadState)
        count.complete(2)
        runCurrent()
        assertEquals(2, (model.uiState.value.loadState as SettingsLoadState.Loaded).value.archivedBookCount)
    }

    @Test
    fun failedInitialArchiveSummaryIsRetryableWithoutShowingEmptyArchive() = runTest {
        var attempts = 0
        val model = StatisticsSettingsViewModel(
            MutableStateFlow(ReaderSettings()),
            MutableStateFlow(false),
            {},
            object : StatisticsRepositoryFake() {
                override suspend fun loadArchiveSummary(): Int {
                    attempts++
                    if (attempts == 1) throw IOException()
                    return 2
                }
            },
            backgroundScope,
        )
        runCurrent()
        assertTrue(model.uiState.value.loadState is SettingsLoadState.Failed)
        model.retryLoad()
        assertEquals(SettingsLoadState.Loading, model.uiState.value.loadState)
        runCurrent()
        assertEquals(2, (model.uiState.value.loadState as SettingsLoadState.Loaded).value.archivedBookCount)
    }

    @Test
    fun failedSettingsReadCanRetryAndReopenedInstanceStartsLoading() = runTest {
        val persisted = ReaderSettings(statisticsAutostartOnBookOpen = false)
        val firstSync = CompletableDeferred<Boolean>()
        var attempts = 0
        val source = flow {
            attempts++
            if (attempts == 1) throw IOException()
            emit(persisted)
        }
        fun newModel() = StatisticsSettingsViewModel(
            source,
            flow { emit(firstSync.await()) },
            {},
            StatisticsRepositoryFake(),
            backgroundScope,
        )
        val model = newModel()
        runCurrent()
        assertTrue(model.uiState.value.loadState is SettingsLoadState.Failed)
        assertNull(model.uiState.value.settings)
        model.retryLoad()
        assertEquals(SettingsLoadState.Loading, model.uiState.value.loadState)
        runCurrent()
        firstSync.complete(false)
        runCurrent()
        assertEquals(persisted, (model.uiState.value.loadState as SettingsLoadState.Loaded).value.settings)

        val reopened = newModel()
        assertEquals(SettingsLoadState.Loading, reopened.uiState.value.loadState)
        runCurrent()
        assertEquals(persisted, (reopened.uiState.value.loadState as SettingsLoadState.Loaded).value.settings)
    }

    @Test
    fun archiveReadFailureIsVisibleEvenWhilePreferencesAreStillLoading() = runTest {
        val pendingSettings = CompletableDeferred<ReaderSettings>()
        val model = StatisticsSettingsViewModel(
            flow { emit(pendingSettings.await()) },
            MutableStateFlow(false),
            {},
            object : StatisticsRepositoryFake() {
                override suspend fun loadArchiveSummary(): Int = throw IOException()
            },
            backgroundScope,
        )
        runCurrent()
        assertTrue(model.uiState.value.loadState is SettingsLoadState.Failed)
    }

    @Test
    fun archiveRefreshRetainsReadyControlsAndReportsFailureSeparately() = runTest {
        val refresh = CompletableDeferred<Int>()
        var reads = 0
        val model = StatisticsSettingsViewModel(
            MutableStateFlow(ReaderSettings()),
            MutableStateFlow(false),
            {},
            object : StatisticsRepositoryFake() {
                override suspend fun loadArchiveSummary(): Int {
                    reads++
                    return if (reads == 1) 3 else refresh.await()
                }
            },
            backgroundScope,
        )
        runCurrent()
        val confirmed = model.uiState.value.loadState
        assertTrue(confirmed is SettingsLoadState.Loaded)
        model.reload()
        runCurrent()
        assertEquals(confirmed, model.uiState.value.loadState)
        refresh.completeExceptionally(IOException())
        runCurrent()
        assertEquals(confirmed, model.uiState.value.loadState)
        assertNotNull(model.uiState.value.error)
    }

    @Test
    fun settingsEditsUseLatestPreferencesAndClearArchiveRefreshesCount() = runTest {
        val settings = MutableStateFlow(ReaderSettings(statisticsSyncEnabled = false))
        val repository = StatisticsRepositoryFake().apply { archiveCount = 3 }
        val model = StatisticsSettingsViewModel(settings, MutableStateFlow(true), { settings.value = it(settings.value) }, repository, backgroundScope)
        model.reload()
        runCurrent()
        assertEquals(3, model.uiState.value.archivedBookCount)
        settings.value = settings.value.copy(fontSize = 30)
        model.update { it.copy(statisticsResetMinutes = 105) }
        runCurrent()
        assertEquals(30, model.uiState.value.settings?.fontSize)
        assertEquals(105, model.uiState.value.settings?.statisticsResetMinutes)
        assertFalse(model.uiState.value.settings!!.statisticsSyncEnabled)
        model.clearArchive()
        runCurrent()
        assertEquals(0, model.uiState.value.archivedBookCount)
        assertFalse(model.uiState.value.isWorking)
    }

    @Test
    fun failedArchiveClearRetainsCountAndReportsError() = runTest {
        val settings = MutableStateFlow(ReaderSettings())
        val repository = StatisticsRepositoryFake().apply { archiveCount = 2; failWrites = true }
        val model = StatisticsSettingsViewModel(settings, MutableStateFlow(false), {}, repository, backgroundScope)
        model.reload()
        runCurrent()
        model.clearArchive()
        runCurrent()
        assertEquals(2, model.uiState.value.archivedBookCount)
        assertNotNull(model.uiState.value.error)
        assertFalse(model.uiState.value.isWorking)
    }
}
