# :core:data — local durable storage (Android library)

Owns Room entities, DAOs, migrations and repositories. The invoice issue
transaction (driver sequence increment + invoice snapshot + SALE_DEBIT stock
movements + audit + outbox, all in ONE Room transaction) lives here.

Status: **empty boundary** — filled in Task 02 (and extended by 10/11/12).
