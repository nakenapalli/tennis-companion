---
description: Show dev server status + reachable URLs
allowed-tools: Bash(./dev.sh:*), Bash(hostname:*)
---

Run `./dev.sh status` from the repo root. Report which of backend/frontend are running and whether Postgres/Redis are reachable. If both servers are running, also include their reachable URLs (localhost plus the `hostname -I` LAN IP) for :8080 and :3000. Keep it terse.
