package moe.antimony.hoshi.features.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.features.settings.map
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository
import moe.antimony.hoshi.features.sync.SyncSettingsRepository
import moe.antimony.hoshi.ui.UiText

internal data class StatisticsSettingsValues(
    val settings: ReaderSettings,
    val syncEnabled: Boolean,
    val archivedBookCount: Int,
)

internal data class StatisticsSettingsPreferences(
    val settings: ReaderSettings,
    val syncEnabled: Boolean,
)

internal data class StatisticsSettingsUiState(
    val preferences: SettingsLoadState<StatisticsSettingsPreferences> = SettingsLoadState.Loading,
    val archive: SettingsLoadState<Int> = SettingsLoadState.Loading,
    val isWorking: Boolean = false,
    val error: UiText? = null,
) {
    val loadState: SettingsLoadState<StatisticsSettingsValues>
        get() {
            val currentPreferences = preferences
            val currentArchive = archive
            return when {
                currentPreferences is SettingsLoadState.Failed -> currentPreferences
                currentArchive is SettingsLoadState.Failed -> currentArchive
                currentPreferences is SettingsLoadState.Loaded -> currentArchive.map { count ->
                    StatisticsSettingsValues(
                        settings = currentPreferences.value.settings,
                        syncEnabled = currentPreferences.value.syncEnabled,
                        archivedBookCount = count,
                    )
                }
                else -> SettingsLoadState.Loading
            }
        }

    val settings: ReaderSettings?
        get() = (preferences as? SettingsLoadState.Loaded)?.value?.settings
    val syncEnabled: Boolean
        get() = (preferences as? SettingsLoadState.Loaded)?.value?.syncEnabled == true
    val archivedBookCount: Int
        get() = (archive as? SettingsLoadState.Loaded)?.value ?: 0
}

@HiltViewModel
internal class StatisticsSettingsViewModel internal constructor(
    private val readerSettings: Flow<ReaderSettings>,
    private val syncEnabled: Flow<Boolean>,
    private val updateSettings: suspend ((ReaderSettings) -> ReaderSettings) -> Unit,
    private val repository: StatisticsRepository,
    private val coroutineScope: CoroutineScope?,
) : ViewModel() {
    @Inject
    constructor(
        readerSettingsRepository: ReaderSettingsRepository,
        syncSettingsRepository: SyncSettingsRepository,
        repository: StatisticsRepository,
    ) : this(
        readerSettingsRepository.settings,
        syncSettingsRepository.settings.map { it.enabled },
        readerSettingsRepository::update,
        repository,
        null,
    )

    private val scope get() = coroutineScope ?: viewModelScope
    private val _uiState = MutableStateFlow(StatisticsSettingsUiState())
    val uiState = _uiState.asStateFlow()
    private var settingsJob: Job? = null
    private var reloadJob: Job? = null
    private var generation = 0

    init {
        retryLoad()
    }

    fun retryLoad() {
        settingsJob?.cancel()
        _uiState.update {
            it.copy(preferences = SettingsLoadState.Loading, archive = SettingsLoadState.Loading)
        }
        settingsJob = scope.launch {
            try {
                combine(readerSettings, syncEnabled) { settings, enabled ->
                    StatisticsSettingsPreferences(settings, enabled)
                }.collect { preferences ->
                    _uiState.update { it.copy(preferences = SettingsLoadState.Loaded(preferences)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(preferences = SettingsLoadState.Failed(UiText.Resource(R.string.settings_load_failed)))
                }
            }
        }
        reload()
    }

    fun reload() {
        val request = ++generation
        reloadJob?.cancel()
        reloadJob = scope.launch {
            try {
                val count = repository.loadArchiveSummary()
                if (request == generation) _uiState.update { it.copy(archive = SettingsLoadState.Loaded(count)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) {
                    if (_uiState.value.archive is SettingsLoadState.Loaded) {
                        showError()
                    } else {
                        _uiState.update {
                            it.copy(archive = SettingsLoadState.Failed(UiText.Resource(R.string.settings_load_failed)))
                        }
                    }
                }
            }
        }
    }

    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        if (_uiState.value.loadState !is SettingsLoadState.Loaded) return
        scope.launch {
            try {
                updateSettings(transform)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError()
            }
        }
    }

    fun clearArchive() {
        if (_uiState.value.isWorking || _uiState.value.loadState !is SettingsLoadState.Loaded) return
        ++generation
        reloadJob?.cancel()
        _uiState.update { it.copy(isWorking = true) }
        scope.launch {
            try {
                repository.clearArchive()
                _uiState.update { it.copy(archive = SettingsLoadState.Loaded(0)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError()
            } finally {
                _uiState.update { it.copy(isWorking = false) }
                reload()
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    private fun showError() = _uiState.update {
        it.copy(error = UiText.Resource(R.string.statistics_operation_failed))
    }
}
