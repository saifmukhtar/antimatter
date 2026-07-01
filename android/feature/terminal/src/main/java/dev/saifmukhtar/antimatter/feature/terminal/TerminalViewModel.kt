package dev.saifmukhtar.antimatter.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.saifmukhtar.antimatter.core.network.BridgeWebSocket
import dev.saifmukhtar.antimatter.core.network.InboundMessage
import dev.saifmukhtar.antimatter.core.network.OutboundMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.util.UUID

import kotlinx.coroutines.FlowPreview

@OptIn(FlowPreview::class)
@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val webSocket: BridgeWebSocket,
    @ApplicationContext private val context: Context
) : ViewModel(), TerminalSessionClient {

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _redrawEvent = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val redrawEvent = _redrawEvent.asSharedFlow()

    val terminalSession: TerminalSession = TerminalSession(this)
    private var ptyId: String? = null

    // Channel for buffering resize events so we can debounce them
    private val resizeChannel = Channel<Pair<Int, Int>>(Channel.CONFLATED)

    // Channel signaling that the terminal has new input ready to be read
    private val inputReadyChannel = Channel<Unit>(Channel.CONFLATED)

    private var ioJob: Job? = null

    // Buffer for early PTY output before TerminalView creates the emulator
    private val pendingOutput = mutableListOf<ByteArray>()

    init {
        // Observe WebSocket events
        viewModelScope.launch(Dispatchers.Main.immediate) {
            webSocket.connectionState.collect { state ->
                val connected = state == BridgeWebSocket.ConnectionState.CONNECTED
                _isConnected.value = connected
                if (connected) {
                    startPtySession()
                } else {
                    ptyId = null
                }
            }
        }

        viewModelScope.launch(Dispatchers.Main.immediate) {
            webSocket.messages.collect { message ->
                if (message is InboundMessage.PtyOutput) {
                    handleMessage(message)
                }
            }
        }

        // Process debounced resize events
        viewModelScope.launch {
            resizeChannel.receiveAsFlow()
                .debounce(300L) // Wait 300ms before sending resize
                .collectLatest { (cols, rows) ->
                    val id = ptyId ?: return@collectLatest
                    val resizeMsg = OutboundMessage.PtyResize(id, cols, rows)
                    webSocket.sendMessage(resizeMsg)
                }
        }
    }

    private fun startPtySession() {
        val id = UUID.randomUUID().toString()
        ptyId = id
        val startMsg = OutboundMessage.PtyStart(id, 80, 24)
        webSocket.sendMessage(startMsg)
        
        startIoLoop()
    }

    private fun startIoLoop() {
        ioJob?.cancel()
        ioJob = viewModelScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            while (isActive) {
                // Wait until the TerminalSession signals it has input ready.
                // This avoids the 50ms busy-poll that wastes CPU when idle.
                inputReadyChannel.receive()

                // Drain the input buffer completely
                while (isActive) {
                    val bytesRead = terminalSession.readInput(buffer, false)
                    if (bytesRead > 0) {
                        val id = ptyId
                        if (id != null) {
                            val dataB64 = android.util.Base64.encodeToString(buffer, 0, bytesRead, android.util.Base64.NO_WRAP)
                            val inputMsg = OutboundMessage.PtyInput(id, dataB64)
                            webSocket.sendMessage(inputMsg)
                        }
                    } else {
                        break // No more data right now, go back to waiting
                    }
                }
            }
        }
    }

    private suspend fun handleMessage(message: InboundMessage.PtyOutput) {
        // The gateway base64-encodes all PTY output. We must decode before feeding the emulator.
        val bytes = android.util.Base64.decode(message.data, android.util.Base64.DEFAULT)
        withContext(Dispatchers.Main.immediate) {
            if (terminalSession.emulator != null) {
                terminalSession.appendToEmulator(bytes, bytes.size)
            } else {
                synchronized(pendingOutput) {
                    pendingOutput.add(bytes)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        ioJob?.cancel()
    }

    // Called by TerminalView when its size changes or emulator is first set
    fun onResize(cols: Int, rows: Int) {
        resizeChannel.trySend(Pair(cols, rows))
        
        // Flush any buffered output if emulator is now ready
        if (terminalSession.emulator != null) {
            synchronized(pendingOutput) {
                if (pendingOutput.isNotEmpty()) {
                    val allBytes = pendingOutput.reduce { acc, bytes -> acc + bytes }
                    terminalSession.appendToEmulator(allBytes, allBytes.size)
                    pendingOutput.clear()
                }
            }
        }
    }

    // --- TerminalSessionClient implementation ---
    
    override fun onTextChanged(session: TerminalSession) {
        _redrawEvent.tryEmit(Unit)
        // Signal the IO loop that the user may have typed input ready to read.
        inputReadyChannel.trySend(Unit)
    }

    override fun onTitleChanged(session: TerminalSession) {}

    override fun onSessionFinished(session: TerminalSession) {}

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Terminal", text)
        clipboard.setPrimaryClip(clip)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()
                if (!text.isNullOrEmpty()) {
                    session?.emulator?.paste(text)
                }
            }
        }
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}
    
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
