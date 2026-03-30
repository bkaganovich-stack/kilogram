# Origram

**Origram** is an unofficial Android client for the [Telegram](https://telegram.org) messaging network, forked from the [official Telegram Android open source release](https://github.com/DrKLO/Telegram).

The primary goal of this fork is to add **native [Outline](https://getoutline.org) proxy support** directly inside the app — no separate VPN app required.

---

## Built-in Outline Proxy

Outline is an open-source proxy protocol based on **Shadowsocks AEAD** with optional prefix padding (`ss://` access keys). It is designed to be resistant to deep-packet inspection (DPI) and censorship.

### How it works in Origram

```
Telegram tgnet (C++)
     │  SOCKS5  →  127.0.0.1:local_port
     ▼
MobileProxy (Go / gomobile-bound)
     │  Shadowsocks AEAD + prefix
     ▼
Outline server
```

Origram starts a local SOCKS5 forwarder (via the [Outline SDK `mobileproxy` package](https://github.com/Jigsaw-Code/outline-sdk/tree/main/x/mobileproxy)) on a random loopback port. Telegram's internal networking layer (`tgnet`) then connects through this local SOCKS5 as if it were a normal SOCKS5 proxy — no changes to the C++ networking core were needed.

### Using an Outline key

1. Open **Settings → Privacy and Security → Proxy Settings**
2. Tap **Add Proxy**
3. Select the **Outline** tab
4. Paste your `ss://` access key
5. Tap ✓ to save, then enable **Use Proxy**

The key can be obtained from any Outline server (self-hosted via [Outline Manager](https://getoutline.org/get-started/) or a shared access key from a trusted provider).

### IPv6 / Happy Eyeballs

Shadowsocks does not produce early TCP RSTs for unreachable IPv6 routes. When the Outline proxy is active, Kilogram automatically forces IPv4-only mode to prevent the "Connecting…" stall that would otherwise occur on dual-stack networks. IPv6 is restored when Outline is disabled.

---

## Building from Source

### Prerequisites

| Tool | Version |
|---|---|
| Android Studio | Hedgehog or later |
| NDK | r26d (`26.3.11579264`) |
| CMake | 3.22.1 |
| JDK | 17 |

> **Apple Silicon (M1/M2/M3/M4):** NDK r21 ships Intel-only binaries and hangs under Rosetta. Use NDK r26d which provides native arm64 fat binaries.

### Clone and build

```bash
git clone https://github.com/bkaganovich-stack/kilogram.git
cd kilogram
./gradlew :TMessagesProj_App:assembleAfatDebug
```

> The debug build targets **arm64-v8a + x86_64**. The `afat` release flavor builds all four ABIs (armeabi-v7a, arm64-v8a, x86, x86_64).

### API credentials (required for distribution)

The repository ships with Telegram's placeholder `APP_ID = 4`.
**This placeholder must not be used in distributed builds** — it will hit `API_ID_PUBLISHED_FLOOD` limits.

Register your own credentials at **https://my.telegram.org → API development tools** and set them in:

```
TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java
```

```java
public static int APP_ID = YOUR_API_ID;
public static String APP_HASH = "your_api_hash";
```

### Integrating the real Outline library

The repository currently ships **stub classes** (`mobileproxy/Mobileproxy.java`, `mobileproxy/Proxy.java`, `mobileproxy/StreamDialer.java`) that allow the project to compile without the Go bindings. At runtime, they throw `UnsupportedOperationException`.

To enable real Outline proxy functionality:

```bash
# 1. Install Go
brew install go

# 2. Install gomobile
go install golang.org/x/mobile/cmd/gomobile@latest
$(go env GOPATH)/bin/gomobile init

# 3. Build the AAR
$(go env GOPATH)/bin/gomobile bind \
  -target=android \
  -androidapi 21 \
  -o TMessagesProj/libs/mobileproxy.aar \
  github.com/Jigsaw-Code/outline-sdk/x/mobileproxy
```

Then in `TMessagesProj/build.gradle`, uncomment:
```gradle
implementation fileTree(dir: 'libs', include: ['mobileproxy.aar'])
```

And delete the stub classes:
```bash
rm TMessagesProj/src/main/java/mobileproxy/Mobileproxy.java
rm TMessagesProj/src/main/java/mobileproxy/Proxy.java
rm TMessagesProj/src/main/java/mobileproxy/StreamDialer.java
```

---

## Changes vs. upstream Telegram Android

| File | Change |
|---|---|
| `SharedConfig.java` | Added `proxyType`, `outlineKey` fields; schema V3 serialization |
| `OutlineProxyManager.java` | **New** — singleton lifecycle manager for MobileProxy |
| `mobileproxy/` | **New** — stub classes (replace with real AAR) |
| `ConnectionsManager.java` | Outline-aware `setProxySettings(ProxyInfo)` overload; IPv4-only strategy |
| `ProxySettingsActivity.java` | Outline tab with `ss://` key input field |
| `ProxyListActivity.java` | Outline-aware display, proxy selection, and enable/disable |
| `LoginActivity.java` | Removed QR-code login screen |
| `CMakeLists.txt` | Removed `-fno-integrated-as`; removed GNU gas NEON assembly (pixman) |
| `build.gradle` (both) | NDK r26d; CMake 3.22.1; x86_64-only debug ABI filter |

---

## Legal

### License

This project is a derivative work of Telegram for Android and is distributed under the **GNU General Public License v2.0 or later** — the same license as the upstream project.
See the [LICENSE](LICENSE) file.

### Third-party notices

See [NOTICE](NOTICE) for full attribution of all third-party components, including:
- Telegram for Android (GPL v2) — © Nikolai Kudashov & the Telegram team
- Outline SDK / mobileproxy (Apache 2.0) — © Jigsaw LLC / The Outline Authors
- ZXing (Apache 2.0)

### Trademark disclaimer

**Kilogram** is an independent project and is **not affiliated with, endorsed by, or associated with** Telegram Messenger or Telegram FZ-LLC. "Telegram" is a registered trademark of Telegram FZ-LLC.

The name "Kilogram" and the Outline integration are original contributions of this fork. The app icon must be replaced before any public distribution — the Telegram icon and logo are registered trademarks and may not be reused.

### Disclaimer

This software is provided as-is for research and personal use. Use it responsibly and in accordance with the laws of your jurisdiction and the [Telegram API Terms of Service](https://core.telegram.org/api/terms).
