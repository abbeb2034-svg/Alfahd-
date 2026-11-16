package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.remote.CloudCitizen
import com.example.data.remote.FirestoreCloudRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface CloudUiState<out T> {
    data object Loading : CloudUiState<Nothing>
    data class Success<T>(val data: T) : CloudUiState<T>
    data class Error(val message: String) : CloudUiState<Nothing>
}

class CloudSyncViewModel(
    private val cloudRepo: FirestoreCloudRepository,
    private val currentUserId: String
) : ViewModel() {

    val cloudCitizensState: StateFlow<CloudUiState<List<CloudCitizen>>> = cloudRepo.observeCitizens()
        .map<List<CloudCitizen>, CloudUiState<List<CloudCitizen>>> { CloudUiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing cloud citizens for $currentUserId", rootError)
            emit(CloudUiState.Error(rootError.message ?: "تعذر قراءة بيانات السحابة"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = CloudUiState.Loading
        )

    private val _lastSyncMessage = MutableStateFlow<String?>(null)
    val lastSyncMessage: StateFlow<String?> = _lastSyncMessage.asStateFlow()

    fun syncNow(localDb: AppDatabase, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val summary = cloudRepo.syncLocalDatabase(localDb)
                _lastSyncMessage.value = summary.message
                onResult(summary.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "تعذرت المزامنة مع Firestore"
                _lastSyncMessage.value = msg
                onResult(msg)
            }
        }
    }

    fun syncSingleProfile(profile: com.example.data.local.ProfileEntity) {
        viewModelScope.launch {
            try {
                cloudRepo.upsertProfile(profile, profile.email)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Deferred profile sync for ${profile.username}", e)
            }
        }
    }

    private companion object {
        const val TAG = "CloudSyncVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
