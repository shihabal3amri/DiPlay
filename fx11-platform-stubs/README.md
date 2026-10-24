# FX11 compile-only HIDL signatures

Minimal signatures from the user's FX11 VHAL demo's platform-stubs module.
These classes are **not implementations** and must never be packaged in a DiPlay APK.
Only `common` uses this module, via `compileOnly`. The OEM framework supplies them at runtime.
The adapter is loaded lazily; missing framework APIs leave ordinary CarPlay unaffected.

Only getter, subscription and lifecycle signatures are provided. No vehicle setters are exposed.
