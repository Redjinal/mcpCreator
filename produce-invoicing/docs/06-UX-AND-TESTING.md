# UX, QA and Definition of Release

## Main screens
1. Login/device provisioning + first sync; 2. Dashboard (today's invoices, totals, pending sync, printer status); 3. New invoice (customer, lines, quantity, unit, amounts); 4. Review/issue; 5. Invoice detail/print/reprint; 6. Invoice search/list; 7. Customers; 8. Products (read-only drivers); 9. Owner catalogue editing & daily report; 10. Settings/printer pairing/sync diagnostics.

## Visual direction
Translate v4's quiet neutral/off-white background, forest green accents, information-dense cards, clear typographic hierarchy. Native adaptive Compose, not a screenshot/WebView recreation. Portrait phone can use bottom navigation; tablets landscape sidebar + split panes. Show BBD on each monetary output.

## High-value improvements
- Search-last-used customers and products; optional favorites; numeric keyboard on weights and price; responsive line editor; prevent double-tap issuance.
- Persistent draft and unsaved-change warning; last price sync timestamp; catalogue version visible; stale prices flagged without changing issued invoice.
- Discrete badges: "Issued", "Not synced", "Printed/Uncertain"; never collapse all statuses into a misleading single green tick.
- Print retry/confirm flow; one-tap day summary; offline signal without blocking invoice creation.
- Large controls and text for field use; support system font scale; TalkBack labels and adequate contrast.

## Test pyramid
- Unit: decimal math, input validation, numbering, state machine, owner/driver rules, print formatting.
- Database: migrations, foreign keys, issued invoice immutable, atomic invoice+outbox.
- Contract/integration: sync idempotency and conflicts, authorization, mock printer and failure recovery.
- Instrumentation/UI: app kill/reopen, rotation, offline issue, Bluetooth permission refusal, print unknown outcome.
- Physical: at least one phone, one tablet, selected printer over actual BT and/or Wi-Fi.

## Release criteria
`./gradlew test` and `./gradlew assembleDebug` pass; instrumentation tests recorded; no P0 failures; APK SHA-256 logged; on-device install/print walkthrough and owner/driver test checklist completed. Debug APK only for pilot/internal sharing; production signing/Play distribution requires separate release process.

## V2 screens and UAT
Driver: Assigned Shift / Load Stock; Payment Method + Paid/Unpaid; Return Intake (condition/disposition); Stock-on-Vehicle; End Shift Count & Variances; Sync Health and Print Diagnostics with multiple saved printer profiles. Owner: Load Allocation; Approve Returns/Adjustments; Shift Reconciliation and Payment Methods Summary; Printer Compatibility Setup. UX must show unsynced events/last LAN sync without implying online server access. Test offline stock debit, count, paid-status correction, printer change, and two-device consistency on phone/tablet.
