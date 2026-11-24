# pShare publication review

Reviewed 2026-11-16. Source commit: unavailable (no readable Git metadata).

- Target SDK 35: below the current phone-app submission minimum of API 36, effective August 31, 2026 (extension ended November 1, 2026). See the official target API reference. Do not change a deliberate runtime target without redesign.
- Owner must confirm developer identity/contact, production support and privacy URLs, countries, pricing, ads, target age and content rating.
- Audit runtime traffic, dependencies and final merged release manifest before signing off Data safety. Source manifest alone is not a complete data audit.
- Create a signed release AAB, test the release on a device and complete the Play Console testing/review requirements applicable to this account. No release binary was built by this documentation task.

## Source evidence

Manifest: `app/src/main/AndroidManifest.xml`. Build: `app/build.gradle.kts`.

- network: `app/src/main/java/x/vladgba/share/pui/PAbout.kt`, `app/src/main/java/x/vladgba/share/transport/Lan.kt`, `app/src/main/java/x/vladgba/share/transport/P2p.kt`, `app/src/main/java/x/vladgba/share/ui/ReceiveActivity.kt`
- storage: `app/src/main/java/x/vladgba/share/data/AndroidItems.kt`
- sharing: `app/src/main/java/x/vladgba/share/data/AndroidItems.kt`, `app/src/main/java/x/vladgba/share/ui/MainActivity.kt`, `app/src/main/java/x/vladgba/share/ui/ReceiveActivity.kt`, `app/src/main/java/x/vladgba/share/ui/SendActivity.kt`
- learning_clipboard: No matches in scanned module sources; absence is not proof.
- speech_translation: No matches in scanned module sources; absence is not proof.
