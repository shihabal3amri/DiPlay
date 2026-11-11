# DiPlay next release notes

Changes through DiPlay 0.2.22 are documented in [0.2.22 release notes](RELEASE-NOTES-0.2.22.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

- **CarPlay map on dashboard (experimental)** adds the BYD Tang L (`eng.build20260423.233011`) to the shared full-map and side-map layout profile and requests the reported native 1920×720 cluster size instead of the logged 1600×600 size. The existing 15 s cluster release behavior remains in place pending in-car acceptance. Standalone HUD eligibility now uses a separate exact-firmware allowlist and still verifies the receiver build/signature at runtime. Layer and receiver details come from a 0.2.21 diagnostic report; pending in-car acceptance.
