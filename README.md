# pShare

Send files, folders and text between two Android phones by whatever channel works: **Wi-Fi, Wi-Fi Direct, the internet, Bluetooth, or a QR slideshow** when nothing else does. Pairing is always one QR code, and every transfer is end-to-end encrypted.

## Features

- **One QR, every channel.** The sender shows a QR code; the receiver scans it and tries all channels at once. The first one that connects wins.
- **Works without a network.** Wi-Fi Direct and Bluetooth need no router; the QR slideshow needs nothing but the camera.
- **Files, folders and text**, with real progress on both phones.
- **Share into pShare** from any app, or use the **Send files** Quick Settings tile.
- **Encrypted.** Every transfer over a network or Bluetooth uses AES-256-GCM with a fresh key from the QR code.
- **Folder sync** with your other devices on the same Wi-Fi (the pSync engine): pair once with a 6-digit code, then folders stay in sync in the background, two-way or one-way, with line-by-line merge of text edits and a version archive. Compatible with pSync. Android 8.0+.
- **No ads, no accounts, no data collection.**

## Permissions

| Permission | Why | Required |
|---|---|---|
| Camera | Scanning the pairing QR and the QR slideshow | Yes, on the receiving phone |
| Internet, network and Wi-Fi state / change | Wi-Fi, LAN, Wi-Fi Direct and internet transfers | Yes |
| Wi-Fi multicast | Finding your other devices for folder sync | Only for folder sync |
| Nearby Wi-Fi devices | Wi-Fi Direct on Android 13+ | Optional |
| Nearby devices / Bluetooth / location | Finding the other phone over Bluetooth (Android asks for location before Android 12) | Optional |
| Notifications, foreground service, wake lock | Keeping a transfer running with the screen off | Optional |
| Ignore battery optimizations | Long transfers on aggressive battery savers | Optional |
| Vibrate | Feedback when a code is scanned | Optional |
| Honeywell decode | Uses the built-in scan engine on Honeywell devices; ignored elsewhere | No |

### Notes

- Received files are saved to **Download/pShare**.
- The QR slideshow is not encrypted: it is line-of-sight only.
- Folder sync is off until you press Start on the Folder sync screen; it then runs as a background service with an ongoing notification. Synced folders are picked with the system folder picker, so no "All files access" is needed.
- How each channel finds the other phone, and the security design, are described in the [guide](docs/GUIDE.md).

## Build

Requires Android 7.0+ (API 24). Built with Kotlin on the Android framework; the only library is [ZXing](https://github.com/zxing/zxing) core for QR codes. The folder sync engine lives in the `sync-core` module (plain Kotlin + JDK, shared with pSync).

```bash
./gradlew assembleRelease
./gradlew :sync-core:runTests
```

The APK is written to `app/build/outputs/apk/release/`.

## License

See [LICENSE](LICENSE).
