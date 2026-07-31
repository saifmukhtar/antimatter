from enum import Enum
from pydantic import BaseModel, Field

class StepCase(str, Enum):
    """
    Exact string matches for Android StepCase enum and TypeScript types.
    Do not change these strings, as they serialize directly to the wire.
    """
    USER_INPUT = "USER_INPUT"
    PLANNER_RESPONSE = "PLANNER_RESPONSE"
    TOOL_CALL = "TOOL_CALL"
    TOOL_RESPONSE = "TOOL_RESPONSE"
    ERROR = "ERROR"
    SYSTEM_MESSAGE = "SYSTEM_MESSAGE"
    SYSTEM_ALERT = "SYSTEM_ALERT"
    MODEL_TEXT = "MODEL_TEXT"

class TrajectoryStep(BaseModel):
    case: StepCase
    value: str
    tool: str | None = None
    call_id: str | None = None

class FileNode(BaseModel):
    name: str
    is_directory: bool
    path: str
    size: int | None = None
    children: list['FileNode'] | None = None

# Update forward refs for recursive type
FileNode.model_rebuild()

class ConversationSummary(BaseModel):
    id: str
    title: str
    updatedAt: int
    agentId: str | None = None

class AgentInfo(BaseModel):
    id: str
    name: str
    status: str # "online" or "offline"

# We can define InboundMessage/OutboundMessage unions if needed, 
# but usually we just parse dictionaries dynamically based on the 'type' field.

