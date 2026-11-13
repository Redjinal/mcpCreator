# LAN-to-cloud deployment and multi-printer compatibility

## Pilot LAN topology
Dedicated office server (mini-PC/PC/NAS/VM) hosts authenticated API and PostgreSQL on a stable office LAN. Android phones/tablets each use Room and an outbox; owner tablet uses same API. Prefer wired server, DHCP reservation, HTTPS with device-trusted certificate, firewall segmentation, and encrypted automatic backup to a **separate** device/location. Document power-loss restart, UPS, restore drill, logs, database migrations and owner support procedure.

Only devices on office LAN (or approved secure VPN) can sync. Do NOT expose database port or unauthenticated REST endpoints to internet, build open Wi-Fi, or promise same-day visibility for drivers not returning to office. Driver-code issuing leases and stock allocations are downloaded before shifts. Price/role updates reach devices only after next successful sync; define token offline expiry and revocation limitations.

## Portable contract
Define versioned REST endpoints, stable UUIDs, idempotency key, event cursor, tenant/role checks, conflict semantics. API URL from secure deploy config, not hardcoded LAN IP. Postgres migrations/backups independent from Android; Docker Compose for local optional, environment secrets never committed. Cloud phase deploy same API+DB migrations with HTTPS/domain and device URL/config switch, import verified backup, retain IDs and event cursors, rehearse rollback. Do not run old local and new cloud servers simultaneously as independent write authorities.

## Printer compatibility matrix columns
`manufacturer | model | firmware | paper width | printable dots | BT Classic SPP / BLE / Wi-Fi TCP / IPP | supported command language | code page | reconnect | status protocol | tested phone | tested tablet | actual 1/8/30 line output | limitations | verdict`.

Profiles contain print width (not just roll width), supported glyphs, columns, line feed, alignment, cut command only if hardware supports it. Start with shared 58 mm receipt renderer and independent `BluetoothSppEscPosTransport`, `TcpEscPosTransport`, `VendorSdkTransport` as proven. BLE transport requires explicit vendor-defined GATT service/protocol and chunking; never treat generic BLE as SPP. Support discovery/paired list, selecting default printer, test print, connect timeout, reconnect, print queue, uncertain outcomes, COPY banner.

Pilot P0 must demonstrate >=2 distinct printer models if accessible; if only one is provided, release can be a restricted **single-certified-model pilot** while multi-model capability remains architecturally implemented and clearly marked not verified.
