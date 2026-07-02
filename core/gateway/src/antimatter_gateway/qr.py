import urllib.parse
import qrcode
import logging

logger = logging.getLogger(__name__)

def generate_qr_payload(
    ws_url: str,
    pairing_token: str,
    gateway_x25519_pub: str,
    client_id: str | None = None,
    client_secret: str | None = None
) -> str:
    """
    Generates the canonical Deep Link payload for Android pairing.
    Includes the X25519 public key for the E2EE ECDH handshake.
    """
    base_url = "https://antimatter.saifmukhtar.dev/connect"
    
    params = {
        "url": ws_url,
        "token": pairing_token,
        "x25519_pub": gateway_x25519_pub
    }
    
    if client_id and client_secret:
        import hashlib
        import os
        import json
        import base64
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        
        key = hashlib.sha256(pairing_token.encode("utf-8")).digest()
        aesgcm = AESGCM(key)
        cf_payload = json.dumps({"id": client_id, "secret": client_secret}).encode("utf-8")
        
        iv = os.urandom(12)
        encrypted_data = aesgcm.encrypt(iv, cf_payload, None)
        ciphertext = encrypted_data[:-16]
        auth_tag = encrypted_data[-16:]
        
        params["cfenc"] = f"{base64.b64encode(iv).decode('utf-8')}:{base64.b64encode(auth_tag).decode('utf-8')}:{base64.b64encode(ciphertext).decode('utf-8')}"
    elif client_id:
        params["client_id"] = client_id
        
    query = urllib.parse.urlencode(params)
    return f"{base_url}?{query}"

def print_qr_to_terminal(payload: str) -> None:
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_L, box_size=10, border=4)
    qr.add_data(payload)
    
    try:
        qr.print_ascii(invert=True)
    except Exception:
        logger.warning("Failed to print ASCII QR. Terminal might not support it.")

def main():
    from antimatter_shared_config.config import load_config
    from antimatter_gateway.server import get_local_ip

    config = load_config()
    if not config.private_key_pem or not config.pairing_token:
        print("Error: Gateway not initialized. Please run 'antimatter-gateway start' first.")
        return
    
    from antimatter_crypto.auth import Ed25519Auth
    auth = Ed25519Auth(config.private_key_pem)
    import base64
    ed25519_pub_b64 = base64.b64encode(auth.public_key_raw).decode('utf-8')

    local_ip = get_local_ip()

    # Determine which options are available
    can_lan = local_ip is not None
    can_cf  = bool(config.cloudflare_url)

    # If both are available, ask the user which one they want
    if can_lan and can_cf:
        print()
        print("=" * 55)
        print("  GENERATE PAIRING QR CODE FOR:")
        print("=" * 55)
        print("  1. Local Network (LAN)  →  ws://{0}:8765".format(local_ip))
        print("  2. Cloudflare (Remote)  →  {0}".format(config.cloudflare_url))
        print("=" * 55)
        while True:
            choice = input("  Choice (1/2) [default: 1]: ").strip()
            if choice == "" or choice == "1":
                target = "lan"
                break
            elif choice == "2":
                target = "cloudflare"
                break
            else:
                print("  Invalid choice.")
    elif can_lan:
        target = "lan"
    elif can_cf:
        target = "cloudflare"
    else:
        # Fallback: no Cloudflare configured and no LAN IP
        print("⚠️  No connection configured. Falling back to localhost.")
        target = "local"

    # Build the ws URL based on the target
    if target == "lan":
        ws_url = f"ws://{local_ip}:8765"
        label = f"LOCAL NETWORK  →  {ws_url}"
    elif target == "cloudflare":
        ws_url = config.cloudflare_url
        label = f"CLOUDFLARE (REMOTE)  →  {ws_url}"
    else:
        ws_url = "ws://127.0.0.1:8765"
        label = f"LOCALHOST  →  {ws_url}"

    payload = generate_qr_payload(
        ws_url=ws_url,
        pairing_token=config.pairing_token,
        gateway_x25519_pub=ed25519_pub_b64,
        client_id=config.cloudflare_client_id if target == "cloudflare" else None,
        client_secret=config.cloudflare_client_secret if target == "cloudflare" else None,
    )
    
    print("\n" + "=" * 55)
    print("  ANTIMATTER E2EE GATEWAY — SECURE PAIRING")
    print("  " + label)
    print("=" * 55)
    print("\nScan this QR Code with the Antimatter App:\n")
    print_qr_to_terminal(payload)
    print("=" * 55)

if __name__ == "__main__":
    main()
