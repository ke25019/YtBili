package com.ytstyle.bili.ui.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class UploadUiState(
    val fileUri: Uri? = null,
    val fileName: String = "",
    val fileSizeLabel: String = "",
    val title: String = "",
    val description: String = "",
    val isBusy: Boolean = false,
    val error: String? = null,
)

/**
 * Holds投稿 form state. Deliberately has no transport: see [UploadScreen] for
 * why the upload call is not implemented.
 */
@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(UploadUiState())
    val state: StateFlow<UploadUiState> = _state.asStateFlow()

    fun onFileSelected(uri: Uri) {
        viewModelScope.launch {
            val (name, size) = queryFileMetadata(uri)
            _state.update {
                it.copy(
                    fileUri = uri,
                    fileName = name,
                    fileSizeLabel = formatSize(size),
                    // Seed the title from the filename, as most editors do.
                    title = it.title.ifEmpty { name.substringBeforeLast('.') },
                    error = null,
                )
            }
        }
    }

    fun onTitleChange(value: String) = _state.update { it.copy(title = value) }

    fun onDescriptionChange(value: String) = _state.update { it.copy(description = value) }

    /** Reads the display name and byte size from the content resolver. */
    private suspend fun queryFileMetadata(uri: Uri): Pair<String, Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use "" to 0L
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else {
                        0L
                    }
                    name to size
                } ?: ("" to 0L)
            }.getOrDefault("" to 0L)
        }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
