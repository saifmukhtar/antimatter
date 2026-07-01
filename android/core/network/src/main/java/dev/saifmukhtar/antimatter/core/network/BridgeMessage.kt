package dev.saifmukhtar.antimatter.core.network

import com.google.gson.annotations.SerializedName

// ─────────────────────────────────────────────────────────────────────────────
//  PROTOCOL — Messages over the WebSocket bridge
// ─────────────────────────────────────────────────────────────────────────────

/** Step types that come from Antigravity's trajectory */
enum class StepCase(val raw: String) {
    USER_INPUT("userInput"),
    PLANNER_RESPONSE("plannerResponse"),
    MARKDOWN_CHUNK("markdownChunk"),
    TEXT("text"),
    TOOL_CALL("toolCall"),
    ERROR_MESSAGE("errorMessage"),
    EPHEMERAL_MESSAGE("ephemeralMessage"),
    CHECKPOINT("checkpoint"),
    TASK_BOUNDARY("taskBoundary"),
    INVOKE_SUBAGENT("invokeSubagent"),
    SEND_MESSAGE("sendMessage"),
    APPROVAL_INTERACTION("approvalInteraction"),
    ELICITATION("elicitation"),
    ASK_QUESTION("askQuestion"),
    UNKNOWN("unknown");

    companion object {
        fun from(raw: String) = entries.firstOrNull { 
            it.name.equals(raw, ignoreCase = true) || it.raw.equals(raw, ignoreCase = true) 
        } ?: UNKNOWN
    }
}

/** A single step in the AI conversation trajectory */
data class TrajectoryStep(
    @SerializedName("case") val case: String = "unknown",
    @SerializedName("value") val value: String? = null,
    @SerializedName("tool") val tool: String? = null,
    @SerializedName("command") val command: String? = null,
) {
    val stepCase: StepCase get() = StepCase.from(case)

    /** Human-readable display text for this step */
    val displayText: String
        get() = when (stepCase) {
            StepCase.USER_INPUT -> value ?: ""
            StepCase.PLANNER_RESPONSE -> value ?: ""
            StepCase.MARKDOWN_CHUNK, StepCase.TEXT -> value ?: ""
            StepCase.TOOL_CALL -> "Using tool: ${tool ?: "unknown"}"
            StepCase.ERROR_MESSAGE -> "⚠️ ${value ?: "Error"}"
            StepCase.EPHEMERAL_MESSAGE -> value ?: ""
            StepCase.APPROVAL_INTERACTION -> "⏸ Waiting for approval"
            StepCase.ELICITATION, StepCase.ASK_QUESTION -> "❓ ${value ?: "Question"}"
            else -> value ?: ""
        }
}

// ─────────────────────────────────────────────────────────────────────────────
//  INBOUND MESSAGES (PC → Android)
// ─────────────────────────────────────────────────────────────────────────────

sealed class InboundMessage {
    data class Pong(val dummy: Unit = Unit) : InboundMessage()

    data class SessionState(
        val conversationId: String? = null,
        val model: String = "",
        val stepCount: Int = 0,
        val cloudflareUrl: String? = null,
        val environment: String? = null,
        val agentId: String? = null
    ) : InboundMessage()

    data class Step(
        val step: TrajectoryStep = TrajectoryStep(),
        val index: Int = 0,
        val agentId: String? = null
    ) : InboundMessage()

    data class StepBatch(val steps: List<Step> = emptyList(), val agentId: String? = null, val conversationId: String? = null) : InboundMessage()

    data class Generating(val conversationId: String = "", val agentId: String? = null) : InboundMessage()
    data class ResponseComplete(val conversationId: String = "", val agentId: String? = null) : InboundMessage()

    data class ActiveFile(
        val path: String = "",
        val language: String = ""
    ) : InboundMessage()

    data class FileContent(
        val path: String = "",
        val content: String = "",
        val language: String = ""
    ) : InboundMessage()

    data class ArtifactContent(
        val path: String = "",
        val content: String = "",
        val language: String = "",
        val agentId: String? = null
    ) : InboundMessage()

    data class FileTree(
        val tree: List<FileNode> = emptyList(),
        val workspace: String = ""
    ) : InboundMessage()
    data class CloudflareUrl(val url: String = "", val agentId: String? = null) : InboundMessage()
    data class Error(val message: String = "", val agentId: String? = null) : InboundMessage()
    data class SystemAlert(val title: String = "", val body: String = "", val agentId: String? = null) : InboundMessage()
    data class SystemNotification(val title: String = "", val body: String = "", val agentId: String? = null) : InboundMessage()
    data class HistoryList(val conversations: List<ConversationSummary> = emptyList(), val agentId: String? = null) : InboundMessage()
    data class AuthResponse(val signature: String = "", val pubkey: String = "") : InboundMessage()
    data class ArtifactsList(val artifacts: List<FileNode> = emptyList(), val agentId: String? = null) : InboundMessage()
    data class AgentInfo(val id: String, val name: String, val status: String, val workspaceRoot: String? = null)
    data class AvailableAgents(
        val agents: List<AgentInfo> = emptyList(),
        @SerializedName("allowed_workspaces") val allowedWorkspaces: List<String> = emptyList(),
        // Gateway's active workspace — independent of any connected agent
        @SerializedName("current_workspace") val currentWorkspace: String = "",
        val agentId: String? = null
    ) : InboundMessage()
    data class PtyOutput(val ptyId: String = "", val data: String = "", val agentId: String? = null) : InboundMessage()

    data class Ack(val id: String = "") : InboundMessage()
    object Unknown : InboundMessage()
}

// ─────────────────────────────────────────────────────────────────────────────
//  OUTBOUND MESSAGES (Android → PC)
// ─────────────────────────────────────────────────────────────────────────────

sealed class OutboundMessage {
    abstract val id: String?
    abstract val agentId: String?
    
    data class SendMessage(val text: String, val images: List<String>? = null, val type: String = "SEND_MESSAGE", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class Hello(val pubkey: String, val type: String = "HELLO", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class NewConversation(val type: String = "NEW_CONVERSATION", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class CancelResponse(val type: String = "CANCEL_RESPONSE", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class AcceptEdits(val type: String = "ACCEPT_EDITS", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class RejectEdits(val type: String = "REJECT_EDITS", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class ChangeModel(val type: String = "CHANGE_MODEL", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class NextHunk(val type: String = "NEXT_HUNK", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class PrevHunk(val type: String = "PREV_HUNK", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class AcceptHunk(val type: String = "ACCEPT_HUNK", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class RejectHunk(val type: String = "REJECT_HUNK", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class GetFiles(val path: String? = null, val type: String = "GET_FILES", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class ReadFile(val path: String, val type: String = "READ_FILE", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class SubscribeConversation(
        val conversationId: String,
        val lastKnownStepCount: Int? = null,
        val type: String = "SUBSCRIBE_CONVERSATION",
        override val id: String? = null,
        override val agentId: String? = null
    ) : OutboundMessage()
    data class GetHistoryPage(
        val conversationId: String,
        val offset: Int,
        val limit: Int,
        val type: String = "FETCH_HISTORY_PAGE",
        override val id: String? = null,
        override val agentId: String? = null
    ) : OutboundMessage()
    data class GetHistory(val type: String = "GET_HISTORY", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class Ping(val type: String = "PING", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class AuthChallenge(val challenge: String, val type: String = "AUTH_CHALLENGE", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class GetArtifacts(val conversationId: String, val type: String = "GET_ARTIFACTS", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class ReadArtifact(val conversationId: String, val path: String, val type: String = "READ_ARTIFACT", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class ListAgents(val type: String = "LIST_AGENTS", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    
    data class WriteFile(val path: String, val content: String, val type: String = "WRITE_FILE", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class ChangeWorkspace(val path: String, val type: String = "CHANGE_WORKSPACE", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()

    // PTY messages — type strings must be UPPERCASE to match the Gateway's pty_manager.py
    data class PtyStart(val ptyId: String, val cols: Int, val rows: Int, val type: String = "PTY_START", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class PtyInput(val ptyId: String, val data: String, val type: String = "PTY_INPUT", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
    data class PtyResize(val ptyId: String, val cols: Int, val rows: Int, val type: String = "PTY_RESIZE", override val id: String? = null, override val agentId: String? = null) : OutboundMessage()
}

// ─────────────────────────────────────────────────────────────────────────────
//  FILE SYSTEM
// ─────────────────────────────────────────────────────────────────────────────

data class FileNode(
    val name: String = "",
    val path: String = "",
    val isDir: Boolean = false,
    val children: List<FileNode>? = null
)

data class ConversationSummary(
    val id: String = "",
    val timestamp: Long = 0L,
    val title: String = ""
)
