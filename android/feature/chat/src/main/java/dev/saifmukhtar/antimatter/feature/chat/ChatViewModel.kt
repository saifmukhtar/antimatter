package dev.saifmukhtar.antimatter.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.saifmukhtar.antimatter.core.network.BridgeWebSocket
import dev.saifmukhtar.antimatter.core.network.InboundMessage
import dev.saifmukhtar.antimatter.core.network.OutboundMessage
import dev.saifmukhtar.antimatter.core.network.TrajectoryStep
import dev.saifmukhtar.antimatter.core.network.StepCase
import dev.saifmukhtar.antimatter.core.data.GzipUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import dev.saifmukhtar.antimatter.core.data.UserPreferencesRepository
import dev.saifmukhtar.antimatter.core.data.AppDatabase
import dev.saifmukhtar.antimatter.core.data.ConversationEntity
import dev.saifmukhtar.antimatter.core.data.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import dev.saifmukhtar.antimatter.core.data.AppDao

data class ChatUiState(
    val connectionState: BridgeWebSocket.ConnectionState = BridgeWebSocket.ConnectionState.DISCONNECTED,
    val isGenerating: Boolean = false,
    val steps: List<TrajectoryStep> = emptyList(),
    val conversationId: String? = null,
    val expectedStepCount: Int = 0,
    val activeFile: String? = null,
    val activeFileLanguage: String? = null,
    val cloudflareUrl: String? = null,
    val environment: String? = null,
    val currentModel: String = "gemini-2.5-pro",
    val error: String? = null,
    val history: List<dev.saifmukhtar.antimatter.core.network.ConversationSummary> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<dev.saifmukhtar.antimatter.core.network.ConversationSummary>? = null,
    val artifacts: List<dev.saifmukhtar.antimatter.core.network.FileNode> = emptyList(),
    val activeArtifactContent: String? = null,
    val activeAgentId: String? = null,
    val availableAgents: List<dev.saifmukhtar.antimatter.core.network.InboundMessage.AgentInfo> = emptyList(),
    val allowedWorkspaces: List<String> = emptyList(),
    val selectedImageUri: Uri? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webSocket: BridgeWebSocket,
    val userPrefs: UserPreferencesRepository,
    private val appDao: AppDao
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val scrollStateFlow = MutableSharedFlow<Pair<Int, Int>>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val localAgents = appDao.getAllAgentsFlow().firstOrNull()
            if (localAgents != null && localAgents.isNotEmpty()) {
                val agentInfos = localAgents.map { dev.saifmukhtar.antimatter.core.network.InboundMessage.AgentInfo(it.id, it.name, it.status) }
                _uiState.update { state -> 
                    state.copy(
                        availableAgents = agentInfos,
                        activeAgentId = null // Require manual selection
                    )
                }
            }
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.map { it.activeAgentId }.distinctUntilChanged().flatMapLatest { agentId ->
                if (agentId != null) {
                    appDao.getConversationsForAgentFlow(agentId)
                } else {
                    flowOf(emptyList())
                }
            }.collect { localConversations ->
                val historyList = localConversations.map { 
                    dev.saifmukhtar.antimatter.core.network.ConversationSummary(id = it.id, title = it.title, timestamp = it.timestamp) 
                }
                _uiState.update { it.copy(history = historyList) }
            }
        }

        @OptIn(kotlinx.coroutines.FlowPreview::class)
        viewModelScope.launch {
            scrollStateFlow.debounce(500).collect { (index, offset) ->
                _uiState.value.conversationId?.let { cid ->
                    withContext(Dispatchers.IO) {
                        appDao.updateScrollState(cid, index, offset)
                    }
                }
            }
        }
        // Observe connection state
        viewModelScope.launch {
            var wasConnected = false
            webSocket.connectionState.collect { state ->
                val isConnected = state == BridgeWebSocket.ConnectionState.CONNECTED
                _uiState.update { it.copy(connectionState = state) }
                
                if (!wasConnected && isConnected) {
                    // We just connected or reconnected after connection loss
                    requestHistory()
                    
                    _uiState.value.conversationId?.let { cid ->
                        // Re-subscribe to the active conversation so we don't miss new messages
                        webSocket.sendMessage(OutboundMessage.SubscribeConversation(
                            conversationId = cid,
                            lastKnownStepCount = _uiState.value.steps.size,
                            agentId = _uiState.value.activeAgentId
                        ))
                    }
                }
                wasConnected = isConnected
            }
        }

        // Observe incoming messages
        viewModelScope.launch {
            webSocket.messages.collect { message ->
                handleInboundMessage(message)
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = null) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val history = _uiState.value.history
            val results = history.filter { it.title.contains(query, ignoreCase = true) }
            _uiState.update { it.copy(searchResults = results) }
        }
    }

    private fun handleInboundMessage(message: InboundMessage) {
        val activeAgentId = _uiState.value.activeAgentId
        
        // Guard: Ignore background adapter messages that don't match the active agent
        val msgAgentId = when (message) {
            is InboundMessage.SessionState -> message.agentId
            is InboundMessage.StepBatch -> message.agentId
            is InboundMessage.Generating -> message.agentId
            is InboundMessage.ResponseComplete -> message.agentId
            is InboundMessage.HistoryList -> message.agentId
            is InboundMessage.ArtifactsList -> message.agentId
            is InboundMessage.ArtifactContent -> message.agentId
            is InboundMessage.Step -> message.agentId
            else -> null
        }
        
        if (msgAgentId != null && msgAgentId != activeAgentId) {
            return
        }

        when (message) {
            is InboundMessage.SessionState -> {
                val previousConversationId = _uiState.value.conversationId
                
                _uiState.update {
                    it.copy(
                        conversationId = message.conversationId,
                        expectedStepCount = message.stepCount,
                        currentModel = message.model ?: it.currentModel,
                        cloudflareUrl = message.cloudflareUrl,
                        environment = message.environment
                    )
                }
                
                // Truncate local steps if server says there are fewer (due to edit)
                message.conversationId?.let { cid ->
                    viewModelScope.launch(Dispatchers.IO) {
                        appDao.deleteStepsFromIndex(cid, message.stepCount)
                        appDao.updateStepCount(cid, message.stepCount)
                    }
                }
                val cid = message.conversationId
                // Only subscribe if the conversation actually changed
                if (cid != null && cid != previousConversationId) {
                    subscribeConversation(cid)
                }
            }
            is InboundMessage.Step -> {
                _uiState.update { state ->
                    val newSteps = state.steps.toMutableList()
                    if (message.index < newSteps.size) {
                        newSteps[message.index] = message.step
                    } else {
                        while (newSteps.size < message.index) {
                            newSteps.add(TrajectoryStep(case = "unknown", value = "..."))
                        }
                        newSteps.add(message.step)
                    }
                    state.conversationId?.let { cid ->
                        viewModelScope.launch(Dispatchers.IO) {
                            appDao.insertSteps(listOf(message.step.toEntity(cid, message.index)))
                        }
                    }
                    var stillGenerating = state.isGenerating
                    when (message.step.stepCase) {
                        StepCase.PLANNER_RESPONSE, StepCase.TOOL_CALL -> stillGenerating = true
                        StepCase.TEXT, StepCase.MARKDOWN_CHUNK, StepCase.ERROR_MESSAGE, 
                        StepCase.APPROVAL_INTERACTION, StepCase.ASK_QUESTION, StepCase.ELICITATION -> stillGenerating = false
                        else -> {}
                    }
                    state.copy(steps = newSteps, isGenerating = stillGenerating)
                }
            }
            is InboundMessage.StepBatch -> {
                val targetCid = message.conversationId
                val currentCid = _uiState.value.conversationId

                // Guard: If StepBatch belongs to a different conversation, save it to DB but don't update UI
                if (targetCid != null && targetCid != currentCid) {
                    viewModelScope.launch(Dispatchers.IO) {
                        appDao.insertSteps(message.steps.map { it.step.toEntity(targetCid, it.index) })
                    }
                    return
                }

                _uiState.update { state ->
                    val newSteps = state.steps.toMutableList()
                    var stillGenerating = state.isGenerating
                    message.steps.forEach { batchStep ->
                        if (batchStep.index < newSteps.size) {
                            newSteps[batchStep.index] = batchStep.step
                        } else {
                            while (newSteps.size < batchStep.index) {
                                newSteps.add(TrajectoryStep(case = "unknown", value = "..."))
                            }
                            newSteps.add(batchStep.step)
                        }
                        // Self-regulate generating state based on step types
                        when (batchStep.step.stepCase) {
                            StepCase.PLANNER_RESPONSE, StepCase.TOOL_CALL -> stillGenerating = true
                            StepCase.TEXT, StepCase.MARKDOWN_CHUNK, StepCase.ERROR_MESSAGE, 
                            StepCase.APPROVAL_INTERACTION, StepCase.ASK_QUESTION, StepCase.ELICITATION -> stillGenerating = false
                            else -> {}
                        }
                    }
                    val cidToSave = targetCid ?: state.conversationId
                    cidToSave?.let { cid ->
                        viewModelScope.launch(Dispatchers.IO) {
                            appDao.insertSteps(message.steps.map { it.step.toEntity(cid, it.index) })
                        }
                    }
                    state.copy(steps = newSteps, isGenerating = stillGenerating)
                }
            }
            is InboundMessage.Generating -> {
                _uiState.update {
                    it.copy(isGenerating = true, conversationId = message.conversationId)
                }
            }
            is InboundMessage.ResponseComplete -> {
                _uiState.update {
                    if (it.conversationId == message.conversationId) {
                        it.copy(isGenerating = false)
                    } else it
                }
            }
            is InboundMessage.ActiveFile -> {
                _uiState.update {
                    it.copy(activeFile = message.path, activeFileLanguage = message.language)
                }
            }
            is InboundMessage.CloudflareUrl -> {
                _uiState.update { it.copy(cloudflareUrl = message.url) }
            }
            is InboundMessage.HistoryList -> {
                android.util.Log.d("ChatViewModel", "Received HISTORY_LIST with ${message.conversations.size} conversations")
                viewModelScope.launch(Dispatchers.IO) {
                    message.conversations.forEach { summary ->
                        val existing = appDao.getConversation(summary.id)
                        if (existing == null) {
                            appDao.insertConversation(ConversationEntity(id = summary.id, title = summary.title, timestamp = summary.timestamp, agentId = message.agentId ?: "legacy"))
                        }
                    }
                }
                _uiState.update { it.copy(history = message.conversations) }
            }
            is InboundMessage.ArtifactsList -> {
                _uiState.update { it.copy(artifacts = message.artifacts) }
                _uiState.value.conversationId?.let { cid ->
                    viewModelScope.launch(Dispatchers.IO) {
                        val entities = message.artifacts.map {
                            val existing = appDao.getArtifact(cid, it.path)
                            dev.saifmukhtar.antimatter.core.data.ArtifactEntity(
                                conversationId = cid,
                                path = it.path,
                                name = it.name,
                                compressedContent = existing?.compressedContent
                            )
                        }
                        appDao.insertArtifacts(entities)
                    }
                }
            }
            is InboundMessage.ArtifactContent -> {
                _uiState.update { it.copy(activeArtifactContent = message.content) }
                _uiState.value.conversationId?.let { cid ->
                    viewModelScope.launch(Dispatchers.IO) {
                        val compressed = GzipUtils.compress(message.content)
                        val name = message.path.substringAfterLast("/")
                        appDao.insertArtifacts(listOf(
                            dev.saifmukhtar.antimatter.core.data.ArtifactEntity(
                                conversationId = cid,
                                path = message.path,
                                name = name,
                                compressedContent = compressed
                            )
                        ))
                    }
                }
            }
            is InboundMessage.Error -> {
                _uiState.update { state -> 
                    val newSteps = state.steps.toMutableList()
                    if (state.isGenerating && newSteps.isNotEmpty() && newSteps.last().case == "userInput") {
                        newSteps.removeAt(newSteps.size - 1)
                    }
                    state.copy(error = message.message, steps = newSteps, isGenerating = false)
                }
                // Auto-dismiss error after 5 seconds
                viewModelScope.launch {
                    kotlinx.coroutines.delay(5000)
                    _uiState.update { if (it.error == message.message) it.copy(error = null) else it }
                }
            }
            is InboundMessage.AvailableAgents -> {
                val oldActiveAgentId = _uiState.value.activeAgentId
                var newActiveAgentId = oldActiveAgentId
                val oldAgent = _uiState.value.availableAgents.find { it.id == oldActiveAgentId }
                
                if (oldActiveAgentId != null && message.agents.none { it.id == oldActiveAgentId }) {
                    val sameNameAgent = message.agents.find { it.name == oldAgent?.name }
                    newActiveAgentId = sameNameAgent?.id ?: message.agents.firstOrNull()?.id
                }

                _uiState.update { it.copy(
                    availableAgents = message.agents, 
                    allowedWorkspaces = message.allowedWorkspaces,
                    activeAgentId = newActiveAgentId
                ) }

                if (newActiveAgentId != null && newActiveAgentId != oldActiveAgentId) {
                    _uiState.update { it.copy(
                        conversationId = null,
                        steps = emptyList(),
                        selectedImageUri = null
                    ) }
                    requestHistory()
                }

                viewModelScope.launch(Dispatchers.IO) {
                    appDao.deleteAllAgents()
                    message.agents.forEach { info ->
                        appDao.insertAgent(dev.saifmukhtar.antimatter.core.data.AgentEntity(id = info.id, name = info.name, status = info.status, lastSeen = System.currentTimeMillis()))
                    }
                }
            }
            else -> {} // Handle FileTree, FileContent etc in a separate ViewModel or UI state
        }
    }

    // --- Actions ---

    fun selectImage(uri: Uri?) {
        _uiState.update { it.copy(selectedImageUri = uri) }
    }

    fun sendMessage(text: String) {
        val activeAgentId = _uiState.value.activeAgentId ?: return
        if (text.isBlank() && _uiState.value.selectedImageUri == null) return
        
        val imageUri = _uiState.value.selectedImageUri
        val textToSend = text.ifBlank { "Attached an image." }
        
        // Optimistically add user step
        _uiState.update { state ->
            val newSteps = state.steps.toMutableList()
            newSteps.add(TrajectoryStep(case = "userInput", value = textToSend))
            state.copy(steps = newSteps, isGenerating = true, selectedImageUri = null) // Clear selection
        }

        if (imageUri != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(imageUri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    
                    if (bitmap != null) {
                        // Scale down to max 1024x1024
                        val maxDim = 1024f
                        val scale = minOf(maxDim / bitmap.width, maxDim / bitmap.height)
                        val scaledBitmap = if (scale < 1f) {
                            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                        } else {
                            bitmap
                        }
                        
                        val outputStream = ByteArrayOutputStream()
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                        val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                        val dataUri = "data:image/jpeg;base64,$base64"
                        
                        webSocket.sendMessage(OutboundMessage.SendMessage(textToSend, images = listOf(dataUri), agentId = _uiState.value.activeAgentId))
                        
                        if (scaledBitmap != bitmap) {
                            scaledBitmap.recycle()
                        }
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ChatViewModel", "Failed to process image", e)
                    _uiState.update { state -> 
                        val newSteps = state.steps.toMutableList()
                        if (newSteps.isNotEmpty() && newSteps.last().case == "userInput") {
                            newSteps.removeAt(newSteps.size - 1)
                        }
                        state.copy(error = "Failed to process image", steps = newSteps, isGenerating = false)
                    }
                }
            }
        } else {
            webSocket.sendMessage(OutboundMessage.SendMessage(textToSend, agentId = _uiState.value.activeAgentId))
        }
    }

    fun startNewConversation() {
        _uiState.update { it.copy(steps = emptyList(), conversationId = null, isGenerating = false) }
        _uiState.value.activeAgentId?.let {
            webSocket.sendMessage(OutboundMessage.NewConversation(agentId = it))
        }
        requestHistory() // Refresh history after starting new conversation
    }

    fun subscribeConversation(id: String) {
        viewModelScope.launch {
            // Instantly load cached steps from Room to support offline viewing
            val cachedSteps = withContext(Dispatchers.IO) {
                appDao.getStepsForConversation(id)
            }

            val initialSteps = mutableListOf<TrajectoryStep>()
            cachedSteps.forEach { entity ->
                while (initialSteps.size < entity.stepIndex) {
                    initialSteps.add(TrajectoryStep(case = "unknown", value = "..."))
                }
                initialSteps.add(entity.toTrajectoryStep())
            }

            _uiState.update { it.copy(steps = initialSteps, conversationId = id, isGenerating = false) }

            // Fetch only the new steps to save bandwidth and improve performance
            webSocket.sendMessage(OutboundMessage.SubscribeConversation(conversationId = id, lastKnownStepCount = initialSteps.size, agentId = _uiState.value.activeAgentId))
        }
    }

    fun requestHistory() {
        _uiState.value.activeAgentId?.let {
            webSocket.sendMessage(OutboundMessage.GetHistory(agentId = it))
        }
    }

    fun requestArtifacts() {
        _uiState.value.conversationId?.let { cid ->
            viewModelScope.launch {
                val cached = withContext(Dispatchers.IO) {
                    appDao.getArtifactsForConversation(cid)
                }
                if (cached.isNotEmpty()) {
                    val converted = cached.map { 
                        dev.saifmukhtar.antimatter.core.network.FileNode(name = it.name, path = it.path, isDir = false)
                    }
                    _uiState.update { it.copy(artifacts = converted) }
                }
                webSocket.sendMessage(OutboundMessage.GetArtifacts(cid, agentId = _uiState.value.activeAgentId))
            }
        }
    }

    fun requestArtifactContent(path: String) {
        _uiState.value.conversationId?.let { cid ->
            viewModelScope.launch {
                val existing = withContext(Dispatchers.IO) {
                    appDao.getArtifact(cid, path)
                }
                val compressedContent = existing?.compressedContent
                if (compressedContent != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val decompressed = GzipUtils.decompress(compressedContent)
                            _uiState.update { it.copy(activeArtifactContent = decompressed) }
                        } catch (e: Exception) {
                            android.util.Log.e("ChatViewModel", "Failed to decompress artifact", e)
                            webSocket.sendMessage(OutboundMessage.ReadArtifact(conversationId = cid, path = path, agentId = _uiState.value.activeAgentId))
                        }
                    }
                } else {
                    webSocket.sendMessage(OutboundMessage.ReadArtifact(conversationId = cid, path = path, agentId = _uiState.value.activeAgentId))
                }
            }
        }
    }

    fun clearActiveArtifact() {
        _uiState.update { it.copy(activeArtifactContent = null) }
    }

    fun cancelResponse() {
        webSocket.sendMessage(OutboundMessage.CancelResponse(agentId = _uiState.value.activeAgentId))
        _uiState.update { it.copy(isGenerating = false) }
    }
    
    fun acceptEdits() {
        webSocket.sendMessage(OutboundMessage.AcceptEdits(agentId = _uiState.value.activeAgentId))
    }
    
    fun rejectEdits() {
        webSocket.sendMessage(OutboundMessage.RejectEdits(agentId = _uiState.value.activeAgentId))
    }
    
    fun changeModel() {
        webSocket.sendMessage(OutboundMessage.ChangeModel(agentId = _uiState.value.activeAgentId))
    }
    
    
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun switchAgent(agentId: String) {
        if (_uiState.value.activeAgentId == agentId) return
        _uiState.update { it.copy(activeAgentId = agentId) }
        startNewConversation()
        viewModelScope.launch(Dispatchers.IO) {
            val localHistory = appDao.getConversationsForAgentFlow(agentId).firstOrNull() ?: emptyList()
            val summaries = localHistory.map { dev.saifmukhtar.antimatter.core.network.ConversationSummary(id = it.id, timestamp = it.timestamp, title = it.title) }
            _uiState.update { it.copy(history = summaries) }
        }
    }

    private var lastFetchedOffset = -1

    fun onStepVisible(stepIndex: Int) {
        val steps = _uiState.value.steps
        if (stepIndex in steps.indices) {
            val step = steps[stepIndex]
            if (step.case == "unknown" && step.value == "...") {
                val total = _uiState.value.expectedStepCount
                if (total > 0) {
                    // Calculate offset from the end
                    val offset = kotlin.math.max(0, total - stepIndex - 25)
                    if (offset != lastFetchedOffset) {
                        lastFetchedOffset = offset
                        fetchHistoryPage(_uiState.value.conversationId ?: return, offset, 50)
                    }
                }
            }
        }
    }

    fun fetchHistoryPage(conversationId: String, offset: Int, limit: Int) {
        webSocket.sendMessage(OutboundMessage.GetHistoryPage(conversationId, offset, limit, agentId = _uiState.value.activeAgentId))
    }

    fun updateScrollState(index: Int, offset: Int) {
        scrollStateFlow.tryEmit(Pair(index, offset))
    }

    suspend fun getScrollState(id: String): Pair<Int, Int> {
        return withContext(Dispatchers.IO) {
            val conv = appDao.getConversation(id)
            Pair(conv?.scrollIndex ?: 0, conv?.scrollOffset ?: 0)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Removed webSocket.disconnect() as it's a singleton (BUG-8)
    }
}
