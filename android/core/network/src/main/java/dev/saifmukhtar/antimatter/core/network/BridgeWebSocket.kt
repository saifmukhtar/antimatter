package dev.saifmukhtar.antimatter.core.network

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class BridgeWebSocket(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Disable timeout for websocket
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var currentUrl: String? = null
    private var token: String? = null
    private var clientId: String? = null
    private var clientSecret: ByteArray? = null
    private var pubKey: String? = null
    private var reconnectAttempt = 0
    private val isConnecting = java.util.concurrent.atomic.AtomicBoolean(false)
    private var manuallyDisconnected = false

    private var pendingChallenge: ByteArray? = null
    private var authTimeoutJob: Job? = null
    private var e2eeSession: E2EESession? = null

    private data class PendingMessage(
        val id: String,
        val message: OutboundMessage,
        val sentAt: Long,
        val retryCount: AtomicInteger = AtomicInteger(0),
        val timeoutJob: AtomicReference<Job?> = AtomicReference(null)
    )
    private val pendingAcks = ConcurrentHashMap<String, PendingMessage>()
    private val notificationIdCounter = java.util.concurrent.atomic.AtomicInteger(0)

    // State flows
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private fun updateConnectionState(newState: ConnectionState) {
        _connectionState.update { newState }
        if (newState == ConnectionState.CONNECTED) {
            val intent = Intent(context, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else if (newState == ConnectionState.DISCONNECTED) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _messages = MutableSharedFlow<InboundMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<InboundMessage> = _messages

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    init {
        // No init needed
    }

    fun connect(url: String, clientId: String? = null, clientSecret: String? = null, token: String? = null, pubKey: String? = null) {
        manuallyDisconnected = false
        reconnectAttempt = 0
        currentUrl = url
        this.token = token?.takeIf { it.isNotBlank() }
        this.clientId = clientId?.takeIf { it.isNotBlank() }
        this.clientSecret = clientSecret?.takeIf { it.isNotBlank() }?.toByteArray()
        this.pubKey = pubKey?.takeIf { it.isNotBlank() }
        connectInternal()
    }

    fun disconnect() {
        manuallyDisconnected = true
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        updateConnectionState(ConnectionState.DISCONNECTED)
    }

    private fun connectInternal() {
        if (isConnecting.get() || currentUrl == null || manuallyDisconnected) return
        isConnecting.set(true)
        updateConnectionState(ConnectionState.CONNECTING)

        try {
            val requestBuilder = Request.Builder()
                .url(currentUrl!!)
                .header("Bypass-Tunnel-Reminder", "true")
                
            if (token != null) {
                requestBuilder.header("Authorization", "Bearer $token")
            }
                
            if (clientId != null && clientSecret != null) {
                requestBuilder.header("CF-Access-Client-Id", clientId!!)
                requestBuilder.header("CF-Access-Client-Secret", String(clientSecret!!))
                // Note: Not clearing the secret to allow reconnects
            }
                
            val request = requestBuilder.build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("BridgeWebSocket", "Connected to $currentUrl")
                    isConnecting.set(false)
                    
                    if (pubKey != null) {
                        val random = java.security.SecureRandom()
                        val nonce = ByteArray(32)
                        random.nextBytes(nonce)
                        pendingChallenge = nonce
                        
                        val challengeBase64 = android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP)
                        val authMsg = OutboundMessage.AuthChallenge(challengeBase64)
                        webSocket.send(gson.toJson(authMsg))
                        Log.d("BridgeWebSocket", "Sent AUTH_CHALLENGE, waiting for signature...")
                        
                        authTimeoutJob = scope.launch {
                            delay(5000)
                            Log.e("BridgeWebSocket", "AUTH_RESPONSE timeout. Terminating connection.")
                            webSocket.close(4001, "Timeout waiting for AUTH_RESPONSE")
                            updateConnectionState(ConnectionState.DISCONNECTED)
                        }
                    } else {
                        // Fallback/Legacy
                        updateConnectionState(ConnectionState.CONNECTED)
                    }
                }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    try {
                        val jsonObject = JsonParser.parseString(text).asJsonObject
                        
                        val payloadText = if (jsonObject.has("iv") && jsonObject.has("ct") && jsonObject.has("aad")) {
                            e2eeSession?.decrypt(
                                jsonObject.get("iv").asString,
                                jsonObject.get("ct").asString,
                                jsonObject.get("aad").asString,
                                "output"
                            ) ?: throw java.lang.IllegalStateException("Received encrypted packet but E2EE session is not initialized")
                        } else {
                            text
                        }

                        val payloadObj = JsonParser.parseString(payloadText).asJsonObject
                        val type = payloadObj.get("type").asString
                        Log.d("BridgeWebSocket", "Decrypted and parsed message type: $type")
                        
                        val message = when (type) {
                            "PONG" -> InboundMessage.Pong()
                            "SESSION_STATE" -> gson.fromJson(payloadText, InboundMessage.SessionState::class.java)
                            "STEP" -> gson.fromJson(payloadText, InboundMessage.Step::class.java)
                            "STEP_BATCH" -> gson.fromJson(payloadText, InboundMessage.StepBatch::class.java)
                            "GENERATING" -> gson.fromJson(payloadText, InboundMessage.Generating::class.java)
                            "RESPONSE_COMPLETE" -> gson.fromJson(payloadText, InboundMessage.ResponseComplete::class.java)
                            "ACTIVE_FILE" -> gson.fromJson(payloadText, InboundMessage.ActiveFile::class.java)
                            "ARTIFACT_CONTENT" -> gson.fromJson(payloadText, InboundMessage.ArtifactContent::class.java)
                            "FILE_CONTENT" -> gson.fromJson(payloadText, InboundMessage.FileContent::class.java)
                            "FILE_TREE" -> gson.fromJson(payloadText, InboundMessage.FileTree::class.java)
                            "CLOUDFLARE_URL" -> gson.fromJson(payloadText, InboundMessage.CloudflareUrl::class.java)
                            "HISTORY_LIST" -> gson.fromJson(payloadText, InboundMessage.HistoryList::class.java)
                            "AVAILABLE_AGENTS" -> gson.fromJson(payloadText, InboundMessage.AvailableAgents::class.java)
                            "ERROR" -> gson.fromJson(payloadText, InboundMessage.Error::class.java)
                            "SYSTEM_ALERT" -> gson.fromJson(payloadText, InboundMessage.SystemAlert::class.java)
                            "SYSTEM_NOTIFICATION" -> gson.fromJson(payloadText, InboundMessage.SystemNotification::class.java)
                            "AUTH_RESPONSE" -> gson.fromJson(payloadText, InboundMessage.AuthResponse::class.java)
                            "ARTIFACTS_LIST" -> gson.fromJson(payloadText, InboundMessage.ArtifactsList::class.java)
                            "PTY_OUTPUT" -> gson.fromJson(payloadText, InboundMessage.PtyOutput::class.java)
                            "ACK" -> gson.fromJson(payloadText, InboundMessage.Ack::class.java)
                            else -> InboundMessage.Unknown
                        }
                        
                        Log.d("BridgeWebSocket", "Mapped to message class: ${message.javaClass.simpleName}")
                        
                        if (message is InboundMessage.AuthResponse) {
                            authTimeoutJob?.cancel()
                            authTimeoutJob = null
                            
                            val pubKeyStr = pubKey
                            val challenge = pendingChallenge
                            pendingChallenge = null
                            
                            if (pubKeyStr != null && challenge != null) {
                                try {
                                    val signatureBytes = android.util.Base64.decode(message.signature, android.util.Base64.DEFAULT)
                                    val pubKeyRawBytes = android.util.Base64.decode(pubKeyStr, android.util.Base64.DEFAULT)
                                    
                                    // RFC 8410 SPKI prefix for Ed25519 (OID 1.3.101.112)
                                    val prefix = byteArrayOf(
                                        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00
                                    )
                                    val reconstructedSpki = prefix + pubKeyRawBytes
                                    
                                    val kf = java.security.KeyFactory.getInstance("Ed25519")
                                    val publicKey = kf.generatePublic(java.security.spec.X509EncodedKeySpec(reconstructedSpki))
                                    
                                    val verifier = java.security.Signature.getInstance("Ed25519")
                                    verifier.initVerify(publicKey)
                                    verifier.update(challenge)
                                    val isValid = verifier.verify(signatureBytes)
                                    
                                    if (isValid) {
                                        Log.d("BridgeWebSocket", "Ed25519 Handshake Verified. Initializing E2EE.")
                                        try {
                                            val e2ee = E2EESession()
                                            e2ee.deriveSessionKeys(message.pubkey)
                                            e2eeSession = e2ee
                                            
                                            val helloMsg = OutboundMessage.Hello(e2ee.publicKeyBase64)
                                            webSocket.send(gson.toJson(helloMsg))
                                            Log.d("BridgeWebSocket", "Sent HELLO X25519 payload. E2EE Established.")
                                            
                                            updateConnectionState(ConnectionState.CONNECTED)
                                            reconnectAttempt = 0
                                        } catch (e: Exception) {
                                            Log.e("BridgeWebSocket", "E2EE Handshake failed", e)
                                            webSocket.close(4003, "Crypto Error")
                                            updateConnectionState(ConnectionState.DISCONNECTED)
                                        }
                                    } else {
                                        Log.e("BridgeWebSocket", "Ed25519 Signature INVALID. Aborting connection.")
                                        webSocket.close(4003, "Invalid Signature")
                                        updateConnectionState(ConnectionState.DISCONNECTED)
                                    }
                                } catch (e: Exception) {
                                    Log.e("BridgeWebSocket", "Error verifying Ed25519 signature", e)
                                    webSocket.close(4003, "Crypto Error")
                                    updateConnectionState(ConnectionState.DISCONNECTED)
                                }
                            }
                            return@launch
                        } else if (message is InboundMessage.Ack) {
                            val pending = pendingAcks.remove(message.id)
                            pending?.timeoutJob?.get()?.cancel()
                            Log.d("BridgeWebSocket", "ACK received for ${message.id}")
                            return@launch
                        } else if (message is InboundMessage.SystemNotification) {
                            showSystemNotification(message.title, message.body)
                            return@launch
                        }
                        
                        Log.d("BridgeWebSocket", "Emitting message to flow: ${message.javaClass.simpleName}")
                        _messages.emit(message)
                    } catch (e: Exception) {
                        Log.e("BridgeWebSocket", "Failed to parse message: $text", e)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("BridgeWebSocket", "Closed: $reason")
                isConnecting.set(false)
                authTimeoutJob?.cancel()
                authTimeoutJob = null
                updateConnectionState(ConnectionState.DISCONNECTED)
                if (!manuallyDisconnected) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("BridgeWebSocket", "Failure: ${t.message}")
                isConnecting.set(false)
                authTimeoutJob?.cancel()
                authTimeoutJob = null
                updateConnectionState(ConnectionState.DISCONNECTED)
                scheduleReconnect()
            }
        })
        } catch (e: Exception) {
            isConnecting.set(false)
            updateConnectionState(ConnectionState.DISCONNECTED)
            Log.e("BridgeWebSocket", "Failed to create request", e)
        }
    }

    private fun scheduleReconnect() {
        if (manuallyDisconnected || currentUrl == null) return
        if (reconnectAttempt >= 20) {
            scope.launch {
                _messages.emit(InboundMessage.Error("Failed to connect after 20 attempts. Please check your network or restart the app."))
            }
            return
        }
        
        reconnectAttempt++
        // Exponential backoff: max 30s
        val delayMs = (2.0.pow(reconnectAttempt.coerceAtMost(5)).toLong() * 1000).coerceAtMost(30000)
        
        Log.d("BridgeWebSocket", "Scheduling reconnect in ${delayMs}ms (Attempt $reconnectAttempt)")
        scope.launch {
            delay(delayMs)
            connectInternal()
        }
    }

    fun sendMessage(message: OutboundMessage) {
        val json = gson.toJson(message)
        val payload = if (e2eeSession != null && message !is OutboundMessage.AuthChallenge && message !is OutboundMessage.Hello) {
            val env = e2eeSession!!.encrypt(json, "cmd:")
            gson.toJson(env)
        } else {
            json
        }
        Log.d("BridgeWebSocket", "Sending: ${payload.take(100)}")
        webSocket?.send(payload)
    }

    fun sendWithRetry(messageBuilder: (String) -> OutboundMessage) {
        val id = UUID.randomUUID().toString()
        val message = messageBuilder(id)
        val pending = PendingMessage(id, message, System.currentTimeMillis())
        pendingAcks[id] = pending
        
        sendMessage(message)
        scheduleAckTimeout(pending)
    }

    private fun scheduleAckTimeout(pending: PendingMessage) {
        pending.timeoutJob.getAndSet(null)?.cancel()
        val newJob = scope.launch {
            delay(5000) // 5 second timeout
            if (pendingAcks.containsKey(pending.id)) {
                val retries = pending.retryCount.incrementAndGet()
                if (retries <= 3) {
                    Log.w("BridgeWebSocket", "No ACK for ${pending.id}, retrying ($retries/3)...")
                    sendMessage(pending.message)
                    scheduleAckTimeout(pending)
                } else {
                    Log.e("BridgeWebSocket", "Failed to deliver message ${pending.id} after 3 retries.")
                    pendingAcks.remove(pending.id)
                    scope.launch {
                        _messages.emit(InboundMessage.Error("Message could not be delivered to the agent."))
                    }
                }
            }
        }
        pending.timeoutJob.set(newJob)
    }

    private fun showSystemNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w("BridgeWebSocket", "Missing POST_NOTIFICATIONS permission")
                return
            }
        }

        val channelId = "antimatter_system_notifications"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "System Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        // Just use an implicit intent or a simple launch intent
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationIdCounter.incrementAndGet(), notification)
    }
}
