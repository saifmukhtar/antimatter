# Roadmap

Planned features and architectural improvements for the Antimatter ecosystem. Items are prioritized: security first, then core functionality, then quality-of-life.

---

## Upcoming Features & Improvements

### 1. Workspace Browser `.gitignore` Support
- **Description**: Respect the workspace's `.gitignore` rules during directory parsing.
- **Goal**: Visually grey out or hide files/folders that are ignored by Git (similar to VS Code) so the user instantly knows which files are untracked or ignored.
- **Implementation**: Update `shared-fs` Python logic to parse `.gitignore` (potentially using `git ls-files --ignored --exclude-standard -o --directory`), add an `isIgnored` boolean flag to the `FileNode` protocol model, and update the Android `FileTreeItem` Composables to render ignored items with reduced opacity.

---

## Status Legend

| Badge | Meaning |
|---|---|
| <span class="badge badge-shipped">✓ Shipped</span> | Released and available |
| <span class="badge badge-wip">⟳ In Progress</span> | Actively being developed |
| <span class="badge badge-planned">◦ Planned</span> | Scoped, not yet started |

---

## Security & Infrastructure

| Feature | Status | Notes |
|---|---|---|
| Independent Adapter Model | <span class="badge badge-shipped">✓ Shipped</span> | Gateway + lightweight adapters |
| Decentralized P2P DHT (Hyperswarm) | <span class="badge badge-wip">⟳ In Progress</span> | 100% serverless NAT hole-punching via public DHT (ENS-style routing) |
| Persistent SSH Tunnels (e.g. localhost.run) | <span class="badge badge-planned">◦ Planned</span> | Zero-setup persistent URLs via SSH keys for 100% free decentralized routing |
| Tor Hidden Services (.onion addresses) | <span class="badge badge-planned">◦ Planned</span> | Ultimate "no central authority" fully local P2P connectivity architecture |
| Tailscale Mesh Network | <span class="badge badge-planned">◦ Planned</span> | Zero-config remote access utilizing secure WireGuard VPN mesh |
| End-to-End Encryption (E2EE) | <span class="badge badge-shipped">✓ Shipped</span> | DH key exchange + AES-GCM |
| Biometric Authentication Gate | <span class="badge badge-shipped">✓ Shipped</span> | Fingerprint / Face ID / Face Unlock |
| Cloudflare Zero Trust support | <span class="badge badge-shipped">✓ Shipped</span> | Optional enterprise persistent tunnels |
| Daemonization & Logging | <span class="badge badge-shipped">✓ Shipped</span> | Native background detached processes |
| IPC Token Authentication | <span class="badge badge-shipped">✓ Shipped</span> | Secure local adapter registration |
| SQLite SQLCipher Encryption | <span class="badge badge-shipped">✓ Shipped</span> | Android offline database encrypted |
| Rate limiting (token brute-force) | <span class="badge badge-wip">⟳ In Progress</span> | Per-IP rate limit, close `4000` |
| Advanced Workspaces Allowlist | <span class="badge badge-planned">◦ Planned</span> | Strict explicit allowlists for agent access requests |
| Certificate pinning (mobile) | <span class="badge badge-planned">◦ Planned</span> | Additional TLS verification |
| Strict Workspace Isolation (BUG-016) | <span class="badge badge-planned">◦ Planned</span> | Ensure file explorer is scoped per adapter |
| Gateway agentId Injection (BUG-019) | <span class="badge badge-planned">◦ Planned</span> | Inject originating adapter ID into broadcast payloads |
| PTY Input Validation (AM-009) | <span class="badge badge-planned">◦ Planned</span> | Implement blocklists or validation for PTY input |
| PTY Sandboxing (AM-070, AM-071) | <span class="badge badge-planned">◦ Planned</span> | Implement `nsjail` or `cgroups` for PTY sessions |

## Core Features

| Feature | Status | Notes |
|---|---|---|
| Native Remote PTY Terminal | <span class="badge badge-shipped">✓ Shipped</span> | Termux (Android) + SwiftTerm (iOS) |
| Multiple Terminal Sessions | <span class="badge badge-planned">◦ Planned</span> | Spawn, manage, and switch between multiple PTYs |
| Advanced TUI Support | <span class="badge badge-planned">◦ Planned</span> | Full scrolling & rendering for interactive CLI tools (htop, nano) |
| Push Notifications | <span class="badge badge-shipped">✓ Shipped</span> | Agent completion + approval alerts |
| Full-Text Search | <span class="badge badge-shipped">✓ Shipped</span> | Room FTS4 across trajectory history |
| Multimodal Prompts (Images) | <span class="badge badge-shipped">✓ Shipped</span> | Send images to agent |
| Message Retry + ACK | <span class="badge badge-shipped">✓ Shipped</span> | Auto-retry queue on unstable connections |
| Remote Workspace Switching | <span class="badge badge-shipped">✓ Shipped</span> | Switch active VS Code workspace remotely |
| Artifact Diff Viewer | <span class="badge badge-wip">⟳ In Progress</span> | Accept/reject file edits from mobile |
| Multi-Agent Dashboard | <span class="badge badge-planned">◦ Planned</span> | Monitor multiple agents simultaneously |
| Audio Prompt Support | <span class="badge badge-planned">◦ Planned</span> | Voice-to-text prompt injection |

## Platform

| Feature | Status | Notes |
|---|---|---|
| Android App | <span class="badge badge-shipped">✓ Shipped</span> | Full feature parity |
| iOS App | <span class="badge badge-wip">⟳ In Progress</span> | Feature-matching Android, SwiftUI |
| iPad Layout | <span class="badge badge-planned">◦ Planned</span> | Multi-column adaptive iPad UI |
| Android Wear companion | <span class="badge badge-planned">◦ Planned</span> | Quick status + approve on Wear OS |
| macOS Menu Bar App | <span class="badge badge-planned">◦ Planned</span> | Native macOS client using Catalyst |

## Research & Moonshots

| Feature | Status | Notes |
|---|---|---|
| Decentralized Global Usernames | <span class="badge badge-planned">◦ Planned</span> | Solve Zooko's Triangle without requiring crypto fees or a central server. Potential avenues include Hyperswarm DHT routing, Tor Hidden Services (.onion), or Web-of-Trust (WoT) mechanisms to map human-readable names to public keys. The goal is to provide a seamless Web2-like UX (e.g. `connect@username`) while maintaining strict Web3/P2P ethos: no central authority, no subscription fees, no domain squatting by design. |

## Developer Experience

| Feature | Status | Notes |
|---|---|---|
| Adapter SDK (Python) | <span class="badge badge-planned">◦ Planned</span> | High-level helper library for custom adapters |
| Adapter SDK (TypeScript) | <span class="badge badge-planned">◦ Planned</span> | Type-safe SDK for TS/Node.js adapters |
| Gateway REST API | <span class="badge badge-planned">◦ Planned</span> | HTTP interface for headless control |
| Docker image | <span class="badge badge-planned">◦ Planned</span> | `ghcr.io/saifmukhtar/antimatter-gateway` |
| Native User Message Injection | <span class="badge badge-planned">◦ Planned</span> | Inject user prompt natively to avoid "Message from self" via agentapi |
