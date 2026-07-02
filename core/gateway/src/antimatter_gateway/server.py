import asyncio
import json
import logging
import logging.handlers
import os
import socket
import sys
import signal
import secrets
import time
import websockets
from pathlib import Path
from websockets.exceptions import ConnectionClosed

from antimatter_shared_config.config import load_config, save_config
from antimatter_crypto.auth import Ed25519Auth
from antimatter_crypto.e2ee import E2EESession
from .router import MessageRouter

logger = logging.getLogger(__name__)

def get_local_ip() -> str | None:
    """Discover the machine's primary LAN IP by connecting a UDP socket."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            # Does not actually send a packet; just resolves the outbound interface.
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except Exception:
        return None

def prompt_connection_mode() -> str:
    """Interactively ask the user how they want to connect."""
    print()
    print("=" * 55)
    print("  HOW WOULD YOU LIKE TO CONNECT TO ANTIMATTER?")
    print("=" * 55)
    print("  1. Local Network (LAN)")
    print("     └ Fast, works on the same Wi-Fi. No domain needed.")
    print("  2. Cloudflare Tunnel")
    print("     └ Remote access over the internet via your domain.")
    print("  3. Both")
    print("     └ LAN at home + Cloudflare when away.")
    print("=" * 55)
    while True:
        choice = input("  Choice (1/2/3) [default: 3]: ").strip()
        if choice == "" or choice == "3":
            return "both"
        elif choice == "1":
            return "lan"
        elif choice == "2":
            return "cloudflare"
        else:
            print("  Invalid choice. Please enter 1, 2, or 3.")


# IPC token file — readable only by the owning user (0o600)
IPC_TOKEN_PATH = Path(os.path.expanduser("~/.antimatter_daemon/.ipc_token"))

# Rate Limiting
_ip_failure_counts: dict[str, dict] = {}
RATE_LIMIT_MAX_FAILURES = 5
RATE_LIMIT_WINDOW = 60

def _record_failure(ip: str):
    now = time.monotonic()
    data = _ip_failure_counts.get(ip, {"count": 0, "reset_at": 0.0})
    if now >= data["reset_at"]:
        data = {"count": 0, "reset_at": now + RATE_LIMIT_WINDOW}
    data["count"] += 1
    _ip_failure_counts[ip] = data

class GatewayServer:
    def __init__(self, port: int = 8765):
        self.port = port
        self.config = load_config()
        self.auth = Ed25519Auth(self.config.private_key_pem)
        self.router = MessageRouter(gateway=self)

        # Generate a fresh ephemeral IPC token every time the gateway starts.
        # Written to ~/.antimatter_daemon/.ipc_token (mode 0o600) so only the
        # owning user can read it. Adapters must present this token in
        # REGISTER_ADAPTER to prove they are a legitimate local process
        # (AM-010, AM-020). The token changes on every restart so a captured
        # token from a previous session cannot be replayed (AM-005 for IPC).
        self.ipc_token = secrets.token_hex(32)
        IPC_TOKEN_PATH.parent.mkdir(parents=True, exist_ok=True)
        IPC_TOKEN_PATH.write_text(self.ipc_token)
        os.chmod(IPC_TOKEN_PATH, 0o600)
        logger.info("IPC token written to %s", IPC_TOKEN_PATH)

        # Persist Ed25519 identity key if newly generated.
        needs_save = False
        if not self.config.private_key_pem or self.config.private_key_pem != self.auth.private_key_base64:
            self.config.private_key_pem = self.auth.private_key_base64
            self.config.pairing_token = self.auth.pairing_token
            needs_save = True
        if needs_save:
            save_config(self.config)

    async def handler(self, websocket):
        ip = getattr(websocket, "remote_address", ("unknown",))[0]
        now = time.monotonic()
        
        # 1. Rate Limiting Check
        rate_data = _ip_failure_counts.get(ip)
        if rate_data and rate_data["count"] >= RATE_LIMIT_MAX_FAILURES and now < rate_data["reset_at"]:
            logger.warning(f"Rate limited connection rejected: {ip}")
            await websocket.close(1008, "Rate Limited")
            return

        authenticated = False
        e2ee_established = False
        # Fresh ephemeral X25519 session per connection — provides forward secrecy (AM-023, AM-043).
        # Each client gets a unique ECDH exchange; compromise of one session key never exposes others.
        session_e2ee = E2EESession(role="gateway")

        try:
            async for message in websocket:
                try:
                    data = json.loads(message)
                except Exception:
                    continue
                
                msg_type = data.get("type")

                # Adapter Registration (Local IPC)
                if msg_type == "REGISTER_ADAPTER":
                    # Validate IPC token before accepting any adapter (AM-010, AM-020).
                    # Token is a 32-byte hex secret generated fresh on every gateway start,
                    # stored at ~/.antimatter_daemon/.ipc_token (0o600).
                    presented_token = data.get("ipc_token", "")
                    if not secrets.compare_digest(presented_token, self.ipc_token):
                        remote = getattr(websocket, "remote_address", ("unknown", 0))
                        agent_name = data.get("name", "")
                        logger.warning("REGISTER_ADAPTER rejected: expected %s, got %s from %s (agent_name: '%s')", self.ipc_token, presented_token, remote, agent_name)
                        await websocket.close(1008, "Unauthorized")
                        return

                    agent_id = data.get("id")
                    agent_name = data.get("name", "")
                    workspace_root = data.get("workspaceRoot")
                    if agent_id and agent_name:
                        logger.info(f"Adapter registered: {agent_name} ({agent_id})")
                        await self.router.register_adapter(agent_id, agent_name, websocket, workspace_root)
                        await self._adapter_loop(websocket, agent_id)
                        return
                    else:
                        await websocket.close(1008, "Missing adapter id/name")
                        return

                # State 1: Ed25519 Auth Challenge
                if msg_type == "AUTH_CHALLENGE":
                    challenge = data.get("challenge")
                    if not challenge:
                        await websocket.close(1008, "Missing challenge")
                        return
                    
                    logger.debug(f"Received challenge: {challenge}")
                    sig = self.auth.sign_challenge(challenge)
                    logger.debug(f"Generated signature: {sig}")
                    
                    session_e2ee = E2EESession("gateway")
                    
                    # Send ephemeral pubkey so Android does ECDH with this session's key
                    await websocket.send(json.dumps({
                        "type": "AUTH_RESPONSE", 
                        "signature": sig,
                        "pubkey": session_e2ee.public_key_b64
                    }))
                    authenticated = True
                    logger.info(f"Client {ip} authenticated.")
                    continue
                
                if not authenticated:
                    _record_failure(ip)
                    await websocket.close(1008, "Unauthorized")
                    return

                # State 2: E2EE Handshake
                if msg_type == "HELLO":
                    client_pubkey = data.get("pubkey")
                    if not client_pubkey:
                        await websocket.close(1008, "Missing X25519 pubkey")
                        return
                    
                    session_e2ee.derive_session_keys(client_pubkey)
                    e2ee_established = True
                    logger.info("E2EE Session established (ephemeral ECDH + HKDF).")
                    
                    # Add client to router ONLY after E2EE is established
                    self.router.add_client(websocket, session_e2ee)
                    
                    # Notify adapters
                    await self.router.broadcast_system_state()
                    continue
                
                if not e2ee_established:
                    await websocket.send(json.dumps({"type": "ERROR", "message": "E2EE Handshake required."}))
                    continue

                # State 3: E2EE Encrypted Payload Routing
                if "iv" in data and "ct" in data and "aad" in data:
                    try:
                        plaintext = session_e2ee.decrypt(data, expected_direction="cmd:")
                        parsed_cmd = json.loads(plaintext)
                        logger.debug(f"Routing command type: {parsed_cmd.get('type')}")
                        await self.router.route_to_adapter(parsed_cmd, session_e2ee, websocket)
                    except ValueError as e:
                        logger.error(f"E2EE Decryption/AAD failed: {e}")
                        await websocket.close(1008, "Encryption Error")
                        return
                else:
                    logger.warning("Unencrypted message received post-handshake. Dropping.")

        except ConnectionClosed:
            if authenticated or e2ee_established:
                logger.info(f"Connection closed: {ip}")
        except Exception as e:
            logger.exception(f"Unhandled exception in websocket handler for {ip}: {e}")
        finally:
            self.router.remove_client(websocket)

    async def _adapter_loop(self, websocket, agent_id: str):
        """
        Dedicated loop for listening to incoming messages from a local adapter.
        When an adapter sends a message, stamp agentId and broadcast to all clients.
        Each client connection has its own session_e2ee, so we must broadcast using
        per-client encryption inside broadcast_to_clients.
        """
        try:
            async for message in websocket:
                try:
                    data = json.loads(message)
                    msg_type = data.get("type", "UNKNOWN")
                    if msg_type == "DEBUG_ERROR":
                        logger.error(f"[Adapter -> Gateway] DEBUG_ERROR: {str(data.get('message', ''))[:200]}")
                    else:
                        logger.debug(f"[Adapter -> Gateway] Received message type: {msg_type}")
                except Exception:
                    continue
                
                if isinstance(data, dict):
                    data["agentId"] = agent_id
                # broadcast_to_clients encrypts per client using each client's session key
                await self.router.broadcast_to_clients_encrypted(data)
                
        except ConnectionClosed:
            logger.info(f"Adapter connection closed: {agent_id}")
        finally:
            await self.router.unregister_adapter(agent_id)

    async def start(self, mode: str = "both"):
        local_ip = get_local_ip()

        bind_addresses: list[str] = ["127.0.0.1"]
        if mode in ("lan", "both") and local_ip:
            bind_addresses.append(local_ip)

        log_parts = []
        if mode in ("cloudflare", "both") and self.config.cloudflare_url:
            log_parts.append(f"Cloudflare: {self.config.cloudflare_url}")
        if mode in ("lan", "both") and local_ip:
            log_parts.append(f"LAN: ws://{local_ip}:{self.port}")
        if not log_parts:
            log_parts.append(f"Localhost: ws://127.0.0.1:{self.port}")

        servers = []
        for addr in bind_addresses:
            servers.append(
                await websockets.serve(
                    self.handler, addr, self.port,
                    max_size=None, ping_interval=None, ping_timeout=None
                )
            )

        logger.info("Gateway running. Access via: %s", " | ".join(log_parts))
        logger.info("Run 'antimatter-gateway pair' to view the pairing QR code.")

        await asyncio.Future()  # run forever

async def main_async(port: int = 8765, mode: str = "both"):
    server = GatewayServer(port=port)
    await server.start(mode=mode)

def daemonize(log_path: Path, pid_path: Path):
    if pid_path.exists():
        try:
            pid = int(pid_path.read_text().strip())
            os.kill(pid, 0)
            print(f"Gateway is already running (PID: {pid}).")
            sys.exit(1)
        except OSError:
            pass # Stale PID file

    # First fork
    try:
        if os.fork() > 0:
            sys.exit(0)
    except OSError as e:
        sys.stderr.write(f"Fork #1 failed: {e}\n")
        sys.exit(1)

    os.setsid()
    os.umask(0)

    # Second fork
    try:
        if os.fork() > 0:
            sys.exit(0)
    except OSError as e:
        sys.stderr.write(f"Fork #2 failed: {e}\n")
        sys.exit(1)

    sys.stdout.flush()
    sys.stderr.flush()

    # Write PID
    pid_path.parent.mkdir(parents=True, exist_ok=True)
    pid_path.write_text(str(os.getpid()))

    # Setup file logging
    log_path.parent.mkdir(parents=True, exist_ok=True)
    
    with open(log_path, "a+") as f:
        f.seek(0)
        os.dup2(f.fileno(), sys.stdin.fileno())
        
        os.dup2(f.fileno(), sys.stdout.fileno())
        os.dup2(f.fileno(), sys.stderr.fileno())
    
    handler = logging.handlers.RotatingFileHandler(
        log_path, maxBytes=5 * 1024 * 1024, backupCount=3
    )
    handler.setFormatter(logging.Formatter("[%(asctime)s] [%(levelname)s] %(message)s"))
    root_logger = logging.getLogger()
    root_logger.setLevel(logging.INFO)
    root_logger.addHandler(handler)
    
    # Suppress verbose websocket connection errors
    logging.getLogger("websockets.server").setLevel(logging.CRITICAL)

def stop_daemon(pid_path: Path):
    if not pid_path.exists():
        print("Gateway is not running (no PID file found).")
        return

    try:
        pid = int(pid_path.read_text().strip())
        os.kill(pid, signal.SIGTERM)
        print(f"Stopped Gateway (PID: {pid}).")
        pid_path.unlink(missing_ok=True)
    except ProcessLookupError:
        print("Gateway was not running (stale PID file).")
        pid_path.unlink(missing_ok=True)
    except Exception as e:
        print(f"Error stopping gateway: {e}")

def check_status(pid_path: Path):
    if not pid_path.exists():
        print("Gateway is NOT running.")
        return

    try:
        pid = int(pid_path.read_text().strip())
        os.kill(pid, 0)
        print(f"Gateway is RUNNING (PID: {pid}).")
    except OSError:
        print("Gateway is NOT running (stale PID file found).")

def main():
    import argparse
    from antimatter_shared_config.config import load_config, save_config

    daemon_dir = Path(os.path.expanduser("~/.antimatter_daemon"))
    pid_path = daemon_dir / "gateway.pid"
    log_path = daemon_dir / "gateway.log"

    parser = argparse.ArgumentParser(prog="antimatter-gateway", description="Antimatter E2EE Gateway")
    parser.add_argument("--port", type=int, default=8765, help="Port to run the Gateway on")
    
    subparsers = parser.add_subparsers(dest="command")
    
    # Start/Stop/Status
    subparsers.add_parser("start", help="Start the gateway as a background daemon")
    subparsers.add_parser("stop", help="Stop the background gateway daemon")
    subparsers.add_parser("status", help="Check the status of the gateway daemon")

    # Interactive Setup Command
    subparsers.add_parser("setup", help="Interactive setup: connection mode, Cloudflare, allowed workspaces")
    
    # QR Command
    subparsers.add_parser("pair", help="Show the pairing QR code and exit")

    # Config Command
    config_parser = subparsers.add_parser("config", help="Manage gateway configuration")
    config_sub = config_parser.add_subparsers(dest="config_command")
    
    set_parser = config_sub.add_parser("set", help="Set a configuration key")
    set_parser.add_argument("key", help="Configuration key to set")
    set_parser.add_argument("value", help="Value to set")
    
    args = parser.parse_args()

    # Configure stdout logging for interactive commands
    if args.command not in ("start",):
        logging.basicConfig(level=logging.INFO, format="[%(levelname)s] %(message)s")
        logging.getLogger("websockets.server").setLevel(logging.CRITICAL)
    
    if args.command == "start":
        # Always ask every time — mode is never persisted so user
        # can freely switch between LAN and Cloudflare on each run.
        mode = prompt_connection_mode()
        print(f"\nStarting Antimatter Gateway in background... (Logs: {log_path})")
        daemonize(log_path, pid_path)
        asyncio.run(main_async(args.port, mode=mode))
        return

    if args.command == "stop":
        stop_daemon(pid_path)
        return
        
    if args.command == "status":
        check_status(pid_path)
        return

    if args.command == "setup":
        import getpass
        config = load_config()

        # Step 1: Connection mode
        print()
        current_mode = config.connection_mode or "not set"
        print(f"Current connection mode: {current_mode.upper()}")
        new_mode = prompt_connection_mode()
        config.connection_mode = new_mode

        # Step 2: Cloudflare details (only if mode requires it)
        if new_mode in ("cloudflare", "both"):
            print()
            print("=" * 55)
            print("  CLOUDFLARE TUNNEL CONFIGURATION")
            print("=" * 55)

            url_prompt = f"Enter Cloudflare WebSocket URL (current: {config.cloudflare_url or 'None'}): "
            url_input = input(url_prompt).strip()
            if url_input:
                config.cloudflare_url = url_input

            id_prompt = f"Enter Cloudflare Client ID (current: {config.cloudflare_client_id or 'None'}): "
            id_input = input(id_prompt).strip()
            if id_input:
                config.cloudflare_client_id = id_input

            sec_prompt = "Enter Cloudflare Client Secret (hidden) [leave blank to keep current]: "
            sec_input = getpass.getpass(sec_prompt).strip()
            if sec_input:
                config.cloudflare_client_secret = sec_input
        else:
            print("\n  ⏭  Skipping Cloudflare configuration (LAN mode selected).")

        save_config(config)
        print("\n✅ Configuration saved securely!")
        return

    if args.command == "pair" or args.command == "qr":
        from antimatter_gateway.qr import main as qr_main
        qr_main()
        return

    if args.command == "config":
        if args.config_command == "set":
            config = load_config()
            if hasattr(config, args.key):
                setattr(config, args.key, args.value)
                save_config(config)
                print(f"✅ Successfully set '{args.key}'")
            else:
                print(f"❌ Unknown config key: {args.key}")
        return

    # If no valid subcommand is provided, show help
    parser.print_help()

if __name__ == "__main__":
    main()
