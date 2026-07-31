import asyncio
import json
import logging
import pathlib
import aiofiles
from antimatter_crypto.e2ee import E2EESession

logger = logging.getLogger(__name__)

class MessageRouter:
    def __init__(self, gateway=None):
        self.gateway = gateway
        # Maps websocket → E2EESession so each client is encrypted with its own
        # per-session ephemeral key (forward secrecy, AM-023/AM-043).
        self.clients: dict = {}
        self.adapters = {}  # id -> {"name": str, "ws": websocket, "workspace_root": str}

        # Workspace is completely independent of agents.
        # Initialise from the first allowed_workspace in config, fall back to CWD.
        allowed = (gateway.config.allowed_workspaces if gateway else [])
        self.current_workspace: str = allowed[0] if allowed else str(pathlib.Path.cwd())
        logger.info(f"Gateway workspace initialised to: {self.current_workspace}")

    # ------------------------------------------------------------------ #
    # Client / Adapter lifecycle
    # ------------------------------------------------------------------ #

    def add_client(self, websocket, e2ee_session=None):
        self.clients[websocket] = e2ee_session
        asyncio.create_task(self.broadcast_system_state())

    def remove_client(self, websocket):
        self.clients.pop(websocket, None)

    async def register_adapter(self, agent_id: str, agent_name: str, websocket, workspace_root: str = None):
        self.adapters[agent_id] = {"name": agent_name, "ws": websocket, "workspace_root": workspace_root}
        await self.broadcast_system_state()

    async def unregister_adapter(self, agent_id: str):
        if agent_id in self.adapters:
            del self.adapters[agent_id]
            await self.broadcast_system_state()

    # ------------------------------------------------------------------ #
    # System state broadcast
    # ------------------------------------------------------------------ #

    # ------------------------------------------------------------------ #
    # Tree serialisation helper
    # ------------------------------------------------------------------ #

    def _node_to_dict(self, node) -> dict:
        """
        Recursively converts a FileNode pydantic model to a plain dict,
        renaming `is_directory` → `isDir` at every depth so Android Gson
        can deserialise nested folders correctly.
        """
        d: dict = {
            "name": node.name,
            "path": node.path,
            "isDir": node.is_directory,
        }
        if node.size is not None:
            d["size"] = node.size
        if node.children is not None:
            d["children"] = [self._node_to_dict(child) for child in node.children]
        return d

    # ------------------------------------------------------------------ #
    # System state broadcast
    # ------------------------------------------------------------------ #

    async def broadcast_system_state(self):
        if not self.clients or not self.gateway:
            return

        agents = [
            {"id": aid, "name": info["name"], "status": "online", "workspaceRoot": info["workspace_root"]}
            for aid, info in self.adapters.items()
        ]

        allowed_workspaces = list(self.gateway.config.allowed_workspaces) if self.gateway else []
        for aid, info in self.adapters.items():
            ws_root = info.get("workspace_root")
            if ws_root and ws_root not in allowed_workspaces:
                allowed_workspaces.append(ws_root)

        payload = {
            "type": "AVAILABLE_AGENTS",
            "agents": agents,
            "allowed_workspaces": allowed_workspaces,
            # Always include the gateway's active workspace so the app can reflect it
            "current_workspace": self.current_workspace,
        }

        await self.broadcast_to_clients_encrypted(payload)

    # ------------------------------------------------------------------ #
    # Workspace helper
    # ------------------------------------------------------------------ #

    def _get_workspace_for_cmd(self, parsed_cmd: dict) -> str:
        agent_id = parsed_cmd.get("agentId")
        if agent_id and agent_id in self.adapters:
            return self.adapters[agent_id].get("workspace_root") or self.current_workspace
        return self.current_workspace

    def _resolve_path(self, path: str, workspace: str) -> pathlib.Path:
        """
        Resolve a path against the given workspace.
        Absolute paths are used as-is; relative paths are anchored to
        the workspace.  Raises ValueError if the resolved path
        escapes the workspace (path traversal guard).
        """
        p = pathlib.Path(path)
        if not p.is_absolute():
            p = pathlib.Path(workspace) / p
        p = p.resolve()

        # Security: reject paths that escape the given workspace
        workspace_path = pathlib.Path(workspace).resolve()
        try:
            p.relative_to(workspace_path)
        except ValueError:
            raise ValueError(f"Path traversal rejected: {path!r} is outside workspace {workspace!r}")

        return p

    # ------------------------------------------------------------------ #
    # Command routing
    # ------------------------------------------------------------------ #

    async def route_to_adapter(self, parsed_cmd: dict, e2ee: E2EESession, websocket):
        """
        Receives a decrypted command from a mobile client and either
        handles it natively inside the gateway or forwards it to a
        connected adapter (AI agent).

        Filesystem commands (GET_FILES, READ_FILE, WRITE_FILE,
        CHANGE_WORKSPACE) are ALWAYS handled natively — they never
        require an agent to be connected.
        """
        cmd_type = parsed_cmd.get("type")

        # ── Workspace selection ─────────────────────────────────────────
        if cmd_type == "CHANGE_WORKSPACE":
            new_path = parsed_cmd.get("path", "")
            allowed = list(self.gateway.config.allowed_workspaces) if self.gateway else []
            for agent in self.adapters.values():
                if agent.get("workspace_root"):
                    allowed.append(agent["workspace_root"])
            if new_path in allowed:
                self.current_workspace = new_path
                logger.info(f"Gateway workspace changed to: {new_path}")
                await self.broadcast_system_state()
            else:
                logger.warning(f"CHANGE_WORKSPACE rejected — not in allowed_workspaces: {new_path!r}")
                await self.broadcast_to_clients_encrypted({
                    "type": "ERROR",
                    "message": f"Workspace '{new_path}' is not in the allowed list."
                })
            return

        # ── File tree ───────────────────────────────────────────────────
        if cmd_type == "GET_FILES":
            from antimatter_fs.tree import build_file_tree
            try:
                target_workspace = self._get_workspace_for_cmd(parsed_cmd)
                tree = await build_file_tree(target_workspace)
                # Use recursive helper so isDir is renamed at EVERY depth,
                # not just on top-level nodes.
                tree_dict = [self._node_to_dict(node) for node in tree]
                await self.broadcast_to_clients_encrypted({
                    "type": "FILE_TREE",
                    "tree": tree_dict,
                    "workspace": target_workspace,
                })
            except Exception as ex:
                logger.error(f"GET_FILES failed: {ex}")
                await self.broadcast_to_clients_encrypted({
                    "type": "ERROR",
                    "message": f"Could not read workspace: {ex}"
                })
            return

        # ── Read file ───────────────────────────────────────────────────
        if cmd_type == "READ_FILE":
            raw_path = parsed_cmd.get("path", "")
            try:
                target_workspace = self._get_workspace_for_cmd(parsed_cmd)
                resolved = self._resolve_path(raw_path, target_workspace)

                # Guard: if the client accidentally sends a directory path,
                # return a clear error instead of crashing with EISDIR.
                if resolved.is_dir():
                    logger.warning(f"READ_FILE: {resolved} is a directory — ignoring")
                    await self.broadcast_to_clients_encrypted({
                        "type": "ERROR",
                        "message": f"'{resolved.name}' is a folder, not a file."
                    })
                    return

                async with aiofiles.open(resolved, "r", encoding="utf-8", errors="replace") as f:
                    content = await f.read()
                ext = resolved.suffix.lstrip(".").lower()
                await self.broadcast_to_clients_encrypted({
                    "type": "FILE_CONTENT",
                    "path": str(resolved),
                    "content": content,
                    "language": ext or "text",
                })
                logger.info(f"READ_FILE: {resolved} ({len(content)} chars)")
            except Exception as ex:
                logger.error(f"READ_FILE failed: {type(ex).__name__}")
                await self.broadcast_to_clients_encrypted({
                    "type": "ERROR",
                    "message": f"Could not read file: {ex}"
                })
            return

        # ── Write file ──────────────────────────────────────────────────
        if cmd_type == "WRITE_FILE":
            raw_path = parsed_cmd.get("path", "")
            content = parsed_cmd.get("content", "")
            try:
                target_workspace = self._get_workspace_for_cmd(parsed_cmd)
                resolved = self._resolve_path(raw_path, target_workspace)
                resolved.parent.mkdir(parents=True, exist_ok=True)
                async with aiofiles.open(resolved, "w", encoding="utf-8") as f:
                    await f.write(content)
                logger.info(f"WRITE_FILE: {resolved} ({len(content)} chars)")
                await self.broadcast_to_clients_encrypted({
                    "type": "FILE_WRITE_OK",
                    "path": str(resolved),
                })
            except Exception as ex:
                logger.error(f"WRITE_FILE failed: {type(ex).__name__}")
                await self.broadcast_to_clients_encrypted({
                    "type": "ERROR",
                    "message": f"Could not write file: {ex}"
                })
            return

        # ── Agent-routed commands ───────────────────────────────────────
        agent_id = parsed_cmd.get("agentId")
        if not agent_id:
            logger.warning(f"No agentId specified in command: {cmd_type}")
            return

        adapter = self.adapters.get(agent_id)
        if not adapter:
            logger.warning(f"Target agent {agent_id} is offline. Dropping command: {cmd_type}")
            return

        try:
            await adapter["ws"].send(json.dumps(parsed_cmd))
        except Exception as ex:
            logger.error(f"Failed to route to adapter {agent_id}: {ex}")

    # ------------------------------------------------------------------ #
    # Broadcast helpers
    # ------------------------------------------------------------------ #

    async def broadcast_to_clients_encrypted(self, payload: dict):
        """
        Encrypts payload individually for each client using their own per-session
        E2EE key, then sends. This is required for forward secrecy: each client
        has a distinct ephemeral ECDH key so we cannot share a single ciphertext.
        """
        for client_ws, session in list(self.clients.items()):
            if session is None:
                continue
            try:
                plaintext = json.dumps(payload)
                envelope = session.encrypt(plaintext, direction="output")
                await client_ws.send(json.dumps(envelope))
            except Exception:
                self.clients.pop(client_ws, None)

    async def broadcast_to_clients(self, payload: dict, e2ee):
        """
        Legacy broadcast using a shared E2EE session.
        Kept for call-sites that still supply an explicit e2ee object
        (e.g. router-internal file/PTY responses where the session is in scope).
        """
        plaintext = json.dumps(payload)
        envelope = e2ee.encrypt(plaintext, direction="output")
        payload_str = json.dumps(envelope)

        for client in list(self.clients):
            try:
                await client.send(payload_str)
            except Exception:
                self.clients.pop(client, None)
