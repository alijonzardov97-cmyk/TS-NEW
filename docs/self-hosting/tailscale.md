# Running TS privately over Tailscale

This setup keeps the server off the public internet. Only devices in your tailnet can reach it,
and the Android app talks to it over HTTPS with a real certificate.

## What you get, and what you do not

- The server has no open ports to the internet or the LAN. Port scanners find nothing.
- Traffic is encrypted twice: WireGuard (Tailscale) and TLS, and message content is end-to-end
  encrypted by TS on top of that.
- Tailscale's coordination server still sees metadata (which devices exist and when they are online).
  Run Headscale instead if that matters to you.
- If the host is off, offline, or loses power, TS is unavailable.
- Every user needs Tailscale on their phone and access to your tailnet.

## Steps

1. Install Tailscale on the host (an always-on mini PC or server is best) and sign in.
   Protect the identity account with two-factor authentication.
2. In the Tailscale admin console: turn on **MagicDNS** and **HTTPS Certificates** (DNS page),
   enable **device approval** and key expiry, and write ACLs so users' devices can reach only
   TCP 443 of the TS host.
3. Prepare secrets as described in `docker-deployment.md` (JWT keys, `DB_PASSWORD`, a random
   `TOTP_ENCRYPTION_KEY` of at least 32 characters).
4. In `.env` set:

   ```
   PUBLIC_URL=https://<host>.<tailnet>.ts.net
   ICE_SERVERS=[]
   ```

   `ICE_SERVERS=[]` stops clients from contacting Google STUN servers (which would reveal users'
   public IPs). Inside a tailnet devices reach each other directly, so STUN/TURN is not needed.
5. Start the stack with the overlay:

   ```
   docker compose -f docker-compose.yml -f docker-compose.tailscale.yml up -d --build
   ```
6. Publish it inside the tailnet:

   ```
   tailscale serve --bg --https=443 http://127.0.0.1:8080
   tailscale serve status
   ```

   Do not run `tailscale funnel`: it exposes the server to the whole internet.
7. In the Android app enter `https://<host>.<tailnet>.ts.net` and compare the fingerprint it shows.

## Certificate renewals

Tailscale certificates come from Let's Encrypt and are renewed roughly every 90 days. When the
certificate changes, the app shows the old and new fingerprints and asks whether to trust the new
one. Confirm only if you expect a renewal.

## Hardening checklist

- Encrypt the host disk (LUKS or BitLocker) and keep the OS and Docker images updated.
- Do not forward any router ports to this machine.
- Back up the Postgres volume and the `secrets/` folder, encrypted, to a different place.
- Keep `REGISTRATION_MODE=invite_only` (or `closed`).
- Review the tailnet device list regularly and remove devices you do not recognise.
