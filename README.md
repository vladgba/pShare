# pShare

Send files, folders and text between two Android phones by whatever channel works: **Wi-Fi, Wi-Fi Direct, the internet, Bluetooth, or a QR slideshow** when nothing else does. Pairing is always one QR code, and every transfer is end-to-end encrypted.

## Features

- **One QR, every channel.** The sender shows a QR code; the receiver scans it and tries all channels at once. The first one that connects wins.
- **Works without a network.** Wi-Fi Direct and Bluetooth need no router; the QR slideshow needs nothing but the camera.
- **Files, folders and text**, with real progress on both phones.
- **Share into pShare** from any app, or use the **Send files** Quick Settings tile.
- **Encrypted.** Every transfer over a network or Bluetooth uses AES-256-GCM with a fresh key from the QR code.
- **No ads, no accounts, no data collection.**

## Permissions

| Permission | Why | Required |
|---|---|---|
| Camera | Scanning the pairing QR and the QR slideshow | Yes, on the receiving phone |
| Internet, network and Wi-Fi state / change | Wi-Fi, LAN, Wi-Fi Direct and internet transfers | Yes |
| Nearby Wi-Fi devices | Wi-Fi Direct on Android 13+ | Optional |
| Nearby devices / Bluetooth / location | Finding the other phone over Bluetooth (Android asks for location before Android 12) | Optional |
| Notifications, foreground service, wake lock | Keeping a transfer running with the screen off | Optional |
| Ignore battery optimizations | Long transfers on aggressive battery savers | Optional |
| Vibrate | Feedback when a code is scanned | Optional |
| Honeywell decode | Uses the built-in scan engine on Honeywell devices; ignored elsewhere | No |

### Notes

- Received files are saved to **Download/pShare**.
- The QR slideshow is not encrypted: it is line-of-sight only.
- How each channel finds the other phone, and the security design, are described in the [guide](docs/GUIDE.md).

## Build

Requires Android 7.0+ (API 24). Built with Kotlin on the Android framework; the only library is [ZXing](https://github.com/zxing/zxing) core for QR codes.

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`.
