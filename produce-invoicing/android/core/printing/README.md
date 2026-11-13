# :core:printing — 58 mm receipt rendering and printer gateway (pure Kotlin)

Owns the `PrinterGateway` interface, `FakePrinterGateway`, the profile-driven
58 mm renderer and print-attempt state model. Android transports (Bluetooth
SPP, Wi-Fi TCP, vendor SDK) are added only for hardware-confirmed models.

Status: **empty boundary** — filled in Task 06; real transports Task 07
(BLOCKED: hardware/client decision until printer models are supplied).
