import os
import json
from pathlib import Path
from pydantic import BaseModel, Field

CONFIG_FILE_PATH = Path(os.path.expanduser("~/.antimatter_daemon/config.json"))

class AntimatterConfig(BaseModel):
    cloudflare_url: str | None = None
    cloudflare_client_id: str | None = None
    cloudflare_client_secret: str | None = None
    pairing_token: str | None = None
    private_key_pem: str | None = None
    allowed_workspaces: list[str] = Field(default_factory=list)
    ignored_folders: list[str] = Field(default_factory=lambda: [
        'node_modules', '.git', 'dist', 'build', 'out', 
        '.gradle', '__pycache__', '.venv', 'venv', 
        '.idea', '.DS_Store', '.kotlin', 'gradle-user-home',
        'Pods', '.cxx', '.dart_tool'
    ])

from .secure_store import get_secret, set_secret

def load_config() -> AntimatterConfig:
    """Loads the daemon config from disk and the secure keyring/vault."""
    config = AntimatterConfig()
    if CONFIG_FILE_PATH.exists():
        try:
            with open(CONFIG_FILE_PATH, 'r') as f:
                data = json.load(f)
                config = AntimatterConfig(**data)
        except Exception:
            pass
            
    # Overlay secrets from the secure store
    if val := get_secret("cloudflare_client_secret"): config.cloudflare_client_secret = val
    if val := get_secret("pairing_token"): config.pairing_token = val
    if val := get_secret("private_key_pem"): config.private_key_pem = val
    
    return config

def save_config(config: AntimatterConfig) -> None:
    """Saves the daemon config to disk, delegating secrets to the secure vault."""
    CONFIG_FILE_PATH.parent.mkdir(parents=True, exist_ok=True)
    
    # Save sensitive fields to the OS Keyring / Headless Vault
    if config.cloudflare_client_secret: set_secret("cloudflare_client_secret", config.cloudflare_client_secret)
    if config.pairing_token: set_secret("pairing_token", config.pairing_token)
    if config.private_key_pem: set_secret("private_key_pem", config.private_key_pem)

    # Dump only non-sensitive data to the JSON config file
    safe_data = config.model_dump(exclude_none=True, exclude={
        "cloudflare_client_secret", "pairing_token", "private_key_pem"
    })
    
    # Write to a temporary file first for atomic replacement
    temp_path = CONFIG_FILE_PATH.with_suffix('.tmp')
    with open(temp_path, 'w') as f:
        json.dump(safe_data, f, indent=4)
    
    # Enforce strict read/write only for owner
    os.chmod(temp_path, 0o600)
    temp_path.rename(CONFIG_FILE_PATH)
