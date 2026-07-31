package dev.saifmukhtar.antimatter.feature.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import dev.saifmukhtar.antimatter.core.network.BridgeWebSocket
import dev.saifmukhtar.antimatter.core.network.FileNode
import dev.saifmukhtar.antimatter.core.network.InboundMessage
import dev.saifmukhtar.antimatter.core.network.OutboundMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

data class FilesUiState(
    val fileTree: List<FileNode>? = null,
    val isLoadingTree: Boolean = false,
    val viewedFilePath: String? = null,
    val viewedFileContent: String? = null,
    val viewedFileLanguage: String? = null,
    val isLoadingFile: Boolean = false,
    val allowedWorkspaces: List<String> = emptyList(),
    val currentWorkspace: String? = null
)

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val webSocket: BridgeWebSocket
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    init {
        // Fetch current workspace and file tree when connected
        viewModelScope.launch(Dispatchers.Main.immediate) {
            webSocket.connectionState.collect { state ->
                if (state == BridgeWebSocket.ConnectionState.CONNECTED) {
                    webSocket.sendMessage(OutboundMessage.ListAgents())
                    if (_uiState.value.fileTree == null) {
                        loadFileTree(null)
                    }
                }
            }
        }

        viewModelScope.launch(Dispatchers.Main.immediate) {
            webSocket.messages.collect { message ->
                when (message) {
                    is InboundMessage.FileTree -> {
                        android.util.Log.d("FilesViewModel", "Received FileTree with ${message.tree.size} items, workspace=${message.workspace}")
                        _uiState.update {
                            it.copy(
                                fileTree = message.tree,
                                isLoadingTree = false,
                                // Keep currentWorkspace in sync if the gateway tells us the root
                                currentWorkspace = message.workspace.ifBlank { it.currentWorkspace }
                            )
                        }
                    }
                    is InboundMessage.FileContent -> {
                        _uiState.update { 
                            it.copy(
                                viewedFileContent = message.content, 
                                viewedFileLanguage = message.language,
                                isLoadingFile = false
                            )
                        }
                    }
                    is InboundMessage.Error -> {
                        _uiState.update { it.copy(isLoadingTree = false, isLoadingFile = false) }
                    }
                    is InboundMessage.AvailableAgents -> {
                        // Workspace is gateway-owned — never tied to a specific agent.
                        // Use current_workspace that the gateway sends explicitly.
                        val gatewayWorkspace = message.currentWorkspace.ifBlank {
                            // Fallback: first allowed workspace, or keep current
                            message.allowedWorkspaces.firstOrNull() ?: ""
                        }
                        _uiState.update {
                            it.copy(
                                allowedWorkspaces = message.allowedWorkspaces,
                                currentWorkspace = gatewayWorkspace.ifBlank { it.currentWorkspace }
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun loadFileTree(path: String? = null) {
        android.util.Log.d("FilesViewModel", "loadFileTree called for path: $path")
        _uiState.update { it.copy(isLoadingTree = true) }
        webSocket.sendMessage(OutboundMessage.GetFiles(path))
    }

    fun openFile(path: String) {
        _uiState.update { it.copy(viewedFilePath = path, isLoadingFile = true, viewedFileContent = null) }
        webSocket.sendMessage(OutboundMessage.ReadFile(path))
    }

    fun closeFile() {
        _uiState.update { it.copy(viewedFilePath = null, viewedFileContent = null, viewedFileLanguage = null) }
    }

    fun writeFile(path: String, content: String) {
        webSocket.sendMessage(OutboundMessage.WriteFile(path, content))
        // Optimistically update viewed content
        _uiState.update { it.copy(viewedFileContent = content) }
    }

    fun changeWorkspace(path: String) {
        webSocket.sendMessage(OutboundMessage.ChangeWorkspace(path))
        _uiState.update { it.copy(currentWorkspace = path, isLoadingTree = true) }
    }
}
