package io.github.mayusi.emutran.ui.pickfolder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.data.storage.AllFilesAccess
import io.github.mayusi.emutran.data.storage.StorageRootStore
import io.github.mayusi.emutran.data.storage.StorageVolumes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PickFolderViewModel @Inject constructor(
    private val store: StorageRootStore,
    private val allFiles: AllFilesAccess,
    private val storageVolumes: StorageVolumes,
) : ViewModel() {

    private val _chosen = MutableStateFlow(store.defaultPath)
    val chosen: StateFlow<String> = _chosen.asStateFlow()

    val defaultPath: String get() = store.defaultPath

    /**
     * Available storage volumes (internal + SD card, etc.), exposed so the
     * screen can show quick-select rows instead of requiring the user to
     * type a path.
     *
     * Lazily populated on first access (list() may walk the filesystem).
     * Empty until loaded — the manual text-field fallback remains usable
     * in the meantime.
     */
    private val _volumes = MutableStateFlow<List<StorageVolumes.Volume>>(emptyList())
    val volumes: StateFlow<List<StorageVolumes.Volume>> = _volumes.asStateFlow()

    init {
        viewModelScope.launch {
            _volumes.value = storageVolumes.list()
        }
    }

    fun setPath(path: String) {
        _chosen.update { path.trim() }
    }

    /**
     * Select a storage volume as the root. Appends /Emulation to match the
     * convention in StorageRootStore.defaultPath.
     */
    fun selectVolume(volume: StorageVolumes.Volume) {
        val path = if (volume.path.endsWith("/Emulation"))
            volume.path
        else
            "${volume.path.trimEnd('/')}/Emulation"
        _chosen.update { path }
    }

    fun hasPermission(): Boolean = allFiles.isGranted()

    fun requestPermissionIntent() = allFiles.requestIntent()

    fun commit() {
        viewModelScope.launch { store.save(_chosen.value) }
    }
}
