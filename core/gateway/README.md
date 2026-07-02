<div align="center">
  <h1>⚛️ Antimatter Core</h1>
  <p><b>The Universal Multi-Agent Microservices Gateway</b></p>
  
  [![PyPI Version](https://img.shields.io/pypi/v/antimatter-gateway.svg)](https://pypi.org/project/antimatter-gateway/)
  [![Python](https://img.shields.io/pypi/pyversions/antimatter-gateway.svg)](https://pypi.org/project/antimatter-gateway/)
  [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
</div>

---

**Antimatter** is a hyper-secure, locally hosted, multi-agent integration gateway. It acts as an End-to-End Encrypted (E2EE) bridge between your mobile device and a suite of powerful AI Agents running on your desktop.

By decoupling the architecture, Antimatter allows you to seamlessly switch between different AI models (like Claude, Antigravity 2.0, or the Antigravity IDE) from a single Android application without compromising your local file system's security.

## 🚀 Features

- **Multi-Agent Multiplexing**: Connect to multiple AI adapters (Claude Desktop, AG2, etc.) simultaneously.
- **End-to-End Encryption**: 100% locally generated X25519/HKDF/AES-GCM encryption ensures your data is never readable by Cloudflare or any middleman.
- **Dynamic Routing**: Switch active agents on-the-fly directly from the Antimatter Android app.
- **Headless & Secure Vaults**: Built-in dual-layer secret storage using OS-native Keyrings and a headless AES-GCM fallback vault.

## 📦 Installation

To install the Antimatter Gateway, you can use either standard `pip` or `uv`:

Using `pip`:
```bash
pip install antimatter-gateway
```

Using `uv` (recommended for cleaner isolation):
```bash
uv tool install antimatter-gateway
```

*Note: This package provides the core infrastructure for the Antimatter ecosystem.*

## 💻 CLI Commands

Once installed, you have access to a suite of clean, simple terminal commands:

| Command | Description |
|---|---|
| `antimatter-gateway start` | Starts the primary Gateway server silently as a background daemon. |
| `antimatter-gateway stop` | Safely kills the background Gateway daemon. |
| `antimatter-gateway status`| Checks if the Gateway daemon is running. |
| `antimatter-gateway pair`  | Generates a secure QR code to pair your Android device. |
| `antimatter-ag2` | Starts the Antigravity 2.0 Adapter. |
| `antimatter-claude` | Starts the Claude Desktop Adapter. |

## 🛠️ Quickstart

1. **Start the Gateway:**
   ```bash
   antimatter-gateway start
   ```
   *You will be prompted to choose your connection mode: Local Network (LAN), Cloudflare Tunnel, or Both. The gateway then detaches and runs silently in the background. Logs are written to `~/.antimatter_daemon/gateway.log`.*

2. **Pair your Device:**
   ```bash
   antimatter-gateway pair
   ```
   *Select the connection method you want to pair with, then scan the generated QR code using the Antimatter Android App.*

3. **Start an Agent:**
   Open a new terminal and start your preferred AI agent adapter:
   ```bash
   antimatter-ag2
   ```

4. **Stop the Gateway (when finished):**
   ```bash
   antimatter-gateway stop
   ```

## 📂 Workspace Whitelisting

By default, the Gateway restricts the mobile app's Workspace Explorer to only access the directory from which your adapter was started. To explicitly allow browsing and switching between specific directories, you can whitelist them in your config file (`~/.antimatter_daemon/config.json`):

```json
{
    "allowed_workspaces": [
        "/home/user/my-project",
        "/home/user/another-project"
    ]
}
```

## 🔒 Security Architecture

Antimatter relies entirely on **Application-Layer End-to-End Encryption (E2EE)** using `AES-256-GCM` with a pre-shared Elliptic Curve Diffie-Hellman (ECDH) key exchange. This ensures your data is 100% locally encrypted on the Android device before transmission and is decrypted locally by the PC Gateway.

Because of this robust E2EE architecture, Antimatter is secure over any transport medium:
- **Cloudflare Quick Tunnels**: Treats the tunnel as an untrusted medium. Cloudflare cannot read your traffic.
- **Local Network (LAN)**: Connects directly via WebSocket (`ws://`). Even though the transport layer is not TLS-encrypted, the E2EE layer ensures that anyone sniffing your local network sees only opaque, tamper-proof ciphertext.

> [!WARNING]
> **Dynamic Connection Modes**
> You are prompted to choose your connection mode (LAN or Cloudflare) every time you start the gateway. This preference is never saved, giving you the flexibility to switch seamlessly based on your current network environment without modifying configuration files.
