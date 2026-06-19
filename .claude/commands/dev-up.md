---
description: Start the backend + frontend dev servers (./dev.sh up) and report their URLs
allowed-tools: Bash(./dev.sh:*), Bash(hostname:*)
---

Bring up the local dev environment and tell me where it's reachable.

1. Run `./dev.sh up` from the repo root.
2. If it reports infra isn't reachable, tell me to run `docker compose up -d postgres redis` first and STOP — do not continue.
3. Once both servers report ready, get the LAN address with `hostname -I` (first token) and the machine name with `hostname`, then reply with ONLY a short URL block (no extra commentary):

   - **Backend**  → http://localhost:8080  ·  http://<LAN_IP>:8080
   - **Frontend** → http://localhost:3000  ·  http://<LAN_IP>:3000

   Also note the machine hostname (`<HOSTNAME>`) on its own line in case they want `http://<HOSTNAME>.local:<port>` from another device. If a server didn't come up ready, say so and point to `./dev.sh logs`.
