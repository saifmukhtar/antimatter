package dev.saifmukhtar.antimatter.navigation

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.saifmukhtar.antimatter.core.network.BridgeWebSocket
import dev.saifmukhtar.antimatter.feature.chat.ChatScreen
import dev.saifmukhtar.antimatter.feature.chat.ChatViewModel
import dev.saifmukhtar.antimatter.feature.connect.ConnectScreen
import dev.saifmukhtar.antimatter.feature.connect.ConnectionViewModel
import dev.saifmukhtar.antimatter.feature.connect.QRScannerScreen
import dev.saifmukhtar.antimatter.feature.files.FileViewScreen
import dev.saifmukhtar.antimatter.feature.files.FilesScreen
import dev.saifmukhtar.antimatter.feature.files.FilesViewModel
import dev.saifmukhtar.antimatter.core.ui.utils.LocalCrashHandler
import dev.saifmukhtar.antimatter.MainActivity

@Composable
fun AntimatterNavigation(
    intent: Intent?
) {
    val chatViewModel: ChatViewModel = hiltViewModel()
    val filesViewModel: FilesViewModel = hiltViewModel()
    val connectionViewModel: ConnectionViewModel = hiltViewModel()

    val chatUiState by chatViewModel.uiState.collectAsState()
    val filesUiState by filesViewModel.uiState.collectAsState()
    val connectionState by connectionViewModel.connectionState.collectAsState()
    val savedCredentials by connectionViewModel.savedCredentialsFlow.collectAsState()
    val savedUrl = savedCredentials.url
    
    var currentTab by remember { mutableStateOf(0) }
    var isScanningQR by remember { mutableStateOf(false) }
    var pendingDeepLinkUrl by remember { mutableStateOf<String?>(null) }
    var pendingDeepLinkToken by remember { mutableStateOf<String?>(null) }
    var pendingDeepLinkPubKey by remember { mutableStateOf<String?>(null) }
    var pendingDeepLinkScheme by remember { mutableStateOf<String?>(null) }
    var showCrashDialog by remember { mutableStateOf(false) }
    
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (LocalCrashHandler.hasUnsentLogs(context)) {
            showCrashDialog = true
        }
    }

    LaunchedEffect(intent) {
        intent?.data?.let { uri ->
            val isLegacy = uri.scheme == "antimatter" && uri.host == "connect"
            val isSecure = uri.scheme == "https" && uri.host == "antimatter.saifmukhtar.dev" && uri.path?.startsWith("/connect") == true
            
            if (isLegacy || isSecure) {
                val url = uri.getQueryParameter("url")
                val token = uri.getQueryParameter("token")
                val pubKey = uri.getQueryParameter("pubkey")
                if (url != null && token != null) {
                    pendingDeepLinkUrl = url
                    pendingDeepLinkToken = token
                    pendingDeepLinkPubKey = pubKey
                    pendingDeepLinkScheme = uri.scheme
                }
            }
        }
    }
    
    if (showCrashDialog) {
        AlertDialog(
            onDismissRequest = { showCrashDialog = false },
            title = { Text("Crash Detected") },
            text = { Text("It looks like the app crashed recently. Would you like to share the crash log to help us fix the issue?") },
            confirmButton = {
                TextButton(onClick = {
                    LocalCrashHandler.shareLatestCrashLog(context)
                    showCrashDialog = false
                }) {
                    Text("Share Log")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    LocalCrashHandler.clearLogs(context)
                    showCrashDialog = false 
                }) {
                    Text("Dismiss")
                }
            }
        )
    }

    if (pendingDeepLinkUrl != null) {
        AlertDialog(
            onDismissRequest = { pendingDeepLinkUrl = null },
            title = { Text("Incoming Connection") },
            text = { 
                Column {
                    Text("Connect to workspace at $pendingDeepLinkUrl?")
                    if (pendingDeepLinkScheme == "antimatter") {
                        Text(
                            text = "\nWARNING: You are using the legacy insecure connection method. Custom URI schemes are vulnerable to intent hijacking. Please use the secure HTTPS QR code.",
                            color = Color.Red,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    connectionViewModel.connectManually(pendingDeepLinkUrl!!, null, null, pendingDeepLinkToken, pendingDeepLinkPubKey)
                    pendingDeepLinkUrl = null
                    pendingDeepLinkToken = null
                    pendingDeepLinkPubKey = null
                }) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingDeepLinkUrl = null
                    pendingDeepLinkToken = null
                    pendingDeepLinkPubKey = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    var scannedProfileData by remember { mutableStateOf<Map<String, String?>?>(null) }
    
    if (scannedProfileData != null) {
        var profileName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { scannedProfileData = null },
            title = { Text("Name this Connection") },
            text = { 
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text("Profile Name (e.g., Work PC)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val data = scannedProfileData!!
                        // We need a new method in ConnectionViewModel to save a NAMED profile
                        // We will add `saveProfileAndConnect` next. For now, use connectManually but it doesn't take a name.
                        // Wait, we need to add `connectNamedProfile` to ConnectionViewModel.
                        connectionViewModel.connectNamedProfile(
                            profileName.ifBlank { "Gateway Connection" },
                            data["url"] ?: "",
                            data["cfId"],
                            data["cfSecret"],
                            data["token"],
                            data["pubKey"]
                        )
                        scannedProfileData = null
                    }
                ) {
                    Text("Save & Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { scannedProfileData = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (isScanningQR) {
        BackHandler {
            isScanningQR = false
        }
        QRScannerScreen(
            onQRScanned = { url, token, cfId, cfSecret, pubKey ->
                isScanningQR = false
                scannedProfileData = mapOf(
                    "url" to url,
                    "token" to token,
                    "cfId" to cfId,
                    "cfSecret" to cfSecret,
                    "pubKey" to pubKey
                )
            },
            onNavigateBack = { isScanningQR = false }
        )
    } else if (!savedUrl.isNullOrEmpty()) {
        if (filesUiState.viewedFilePath != null) {
            BackHandler {
                filesViewModel.closeFile()
            }
            FileViewScreen(
                uiState = filesUiState,
                onBack = { filesViewModel.closeFile() },
                onSave = { path, content -> filesViewModel.writeFile(path, content) }
            )
        } else {
            BackHandler(enabled = currentTab != 0) {
                currentTab = 0
            }
            Scaffold(
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier.height(64.dp)
                    ) {
                        NavigationBarItem(
                            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat") },
                            selected = currentTab == 0,
                            onClick = { currentTab = 0 },
                            alwaysShowLabel = false
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Folder, contentDescription = "Workspace") },
                            selected = currentTab == 1,
                            onClick = { currentTab = 1 },
                            alwaysShowLabel = false
                        )
                    }
                }
            ) { paddingValues ->
                Box(modifier = Modifier
                    .padding(bottom = paddingValues.calculateBottomPadding())
                    .consumeWindowInsets(PaddingValues(bottom = paddingValues.calculateBottomPadding()))
                ) {
                    if (currentTab == 0) {
                        ChatScreen(
                            uiState = chatUiState,
                            onSendPrompt = { chatViewModel.sendMessage(it) },
                            onCancel = { chatViewModel.cancelResponse() },
                            onNewConversation = { chatViewModel.startNewConversation() },
                            onSubscribeConversation = { id -> chatViewModel.subscribeConversation(id) },
                            onAcceptEdits = { chatViewModel.acceptEdits() },
                            onRejectEdits = { chatViewModel.rejectEdits() },
                            onChangeModel = { chatViewModel.changeModel() },
                            onDisconnect = { connectionViewModel.disconnectManually() },
                            onScrollStateChange = { index, offset -> chatViewModel.updateScrollState(index, offset) },
                            onLoadScrollState = { id -> chatViewModel.getScrollState(id) },
                            onRequestArtifacts = { chatViewModel.requestArtifacts() },
                            onRequestArtifactContent = { path -> chatViewModel.requestArtifactContent(path) },
                            onClearArtifactContent = { chatViewModel.clearActiveArtifact() },
                            onSwitchAgent = { id -> chatViewModel.switchAgent(id) },
                            onSearchHistory = { query -> chatViewModel.updateSearchQuery(query) },
                            onSelectImage = { uri -> chatViewModel.selectImage(uri) },
                            onStepVisible = { index -> chatViewModel.onStepVisible(index) }
                        )
                    } else if (currentTab == 1) {
                        FilesScreen(
                            uiState = filesUiState,
                            onRefresh = { filesViewModel.loadFileTree() },
                            onOpenFile = { path -> filesViewModel.openFile(path) },
                            onChangeWorkspace = { path ->
                                (context as? MainActivity)?.showBiometricPrompt { success ->
                                    if (success) {
                                        filesViewModel.changeWorkspace(path)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    } else {
        val profiles by connectionViewModel.profilesFlow.collectAsState()
        ConnectScreen(
            connectionState = connectionState,
            savedUrl = savedUrl,
            savedClientId = savedCredentials.clientId,
            savedClientSecret = savedCredentials.clientSecret,
            profiles = profiles,
            onConnectClick = { url, clientId, clientSecret, token ->
                connectionViewModel.connectManually(url, clientId, clientSecret, token)
            },
            onScanQRClick = { isScanningQR = true },
            onProfileSelected = { id -> connectionViewModel.switchProfile(id) },
            onProfileDeleted = { id -> connectionViewModel.deleteProfile(id) }
        )
    }
}
