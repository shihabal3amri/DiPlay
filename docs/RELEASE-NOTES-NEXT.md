# DiPlay next release notes

Changes through DiPlay 0.2.22 are documented in [0.2.22 release notes](RELEASE-NOTES-0.2.22.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

- Ask which iPhone to connect when DiPlay comes on screen for wireless CarPlay and more than one iPhone is paired with the car, for example two drivers who share it. The question comes back for each drive, also when the head unit kept DiPlay in memory. The list comes from the car's Bluetooth, so a new driver or owner needs no setup. An iPhone that is alone on the car's Bluetooth connects without the question; otherwise the last iPhone used connects after 8 s, and a driver who has not chosen one yet taps theirs. Headsets and other paired devices that are not phones are left out, and one iPhone with other devices works as before.
