# MiniBrowser

A lightweight, privacy-focused Android web browser with media downloading capabilities and AI chat integration.

[![Build APK](https://github.com/Devcode940/MiniBrowser/actions/workflows/build.yml/badge.svg)](https://github.com/Devcode940/MiniBrowser/actions/workflows/build.yml)

## Summary

Implements all prioritized improvements from the code review:

### 🔒 P0: Critical Security (Week 1)

- [x] Input validation for AI endpoints, CSS/JS, blocklist URLs

- [x] Secrets management using Android Keystore

- [x] Object-level authorization for bookmarks/downloads

### 🎯 P0: Multi-Tab (Week 2)

- [x] Dynamic tab management with ViewPager2

- [x] Tab creation, switching, closing

- [x] User-specific tab isolation

### 📊 P1: Infrastructure (Week 3-4)

- [x] Room database migration

- [x] Priority thread pool for concurrent downloads

- [x] OpenTelemetry metrics for observability

### 🧪 P2: Testing (Month 2)

- [x] Espresso tests for critical UI flows

- [x] Test coverage for new features

## Testing

- All existing tests pass

- New tests added for all major features

- Manual testing completed on Pixel 6 (API 33)

---

## Features

### Privacy & Security
- **Ad & Tracker Blocking**: Built-in blocklist with ~130 domains + auto-updating remote list (StevenBlack hosts)
- **HTTPS-Only Mode**: Cleartext traffic blocked by default via network security configuration
- **SSL Certificate Hardening**: Invalid certificates are always rejected
- **Fingerprint Spoofing**: Desktop/mobile user-agent toggle with frozen navigator properties
- **WebRTC Blocked**: Prevents local IP address leaks
- **Clipboard Protection**: Blocks read access to clipboard API
- **Third-Party Cookies**: Disabled by default
- **Safe Browsing**: Enabled for malicious site protection

### Media & Downloads
- **HLS Streaming Download**: Supports `.m3u8` playlists with segment merging
- **DASH Streaming Download**: Supports `.mpd` manifests
- **Direct Media Download**: MP4, WebM, MKV, MOV, AVI, and audio formats
- **Queue System**: Concurrent downloads with pause/resume support
- **Progress Tracking**: Real-time download progress with notifications

### AI Integration
- **Multi-Provider Support**: ChatGPT (OpenAI), Gemini (Google), DeepSeek, Kimi (Moonshot), Qwen (DashScope), Grok (xAI), OpenRouter, Copilot (GitHub), Arena, Blackbox
- **Custom Providers**: Add your own OpenAI-compatible endpoints
- **Conversation History**: Persists across rotation
- **Rate Limiting**: Built-in protection against API abuse (2s minimum interval)

### User Experience
- **Gesture Navigation**: Edge swipe for back/forward
- **Home Page**: Quick access shortcuts to popular sites
- **Bookmarks**: Save and manage favorite sites
- **Notepad**: Built-in text editor
- **Offline Pages**: Save web pages for offline reading
- **Custom CSS/JS**: Inject your own styles and scripts
- **Click-to-Block**: Select page elements to hide permanently
- **Tools Menu**: Customizable tool registry

### Network Features
- **Cellular Guard**: Blocks heavy media (video, audio, HLS, DASH) on mobile data
- **Compatibility Mode**: Relaxes mixed-content and third-party cookie restrictions
- **Network Monitoring**: Detects connectivity changes
- **Auto-Translate**: Automatic page translation based on language detection

---

## Screenshots

*(Add screenshots here when available)*

---

## Quick Start

### Prerequisites
- Android SDK (API 24+)
- Java 17+ (for Gradle 8.4)
- Android Studio (recommended) or Gradle command line

### Building

#### Using Gradle Wrapper (Recommended)
```bash
# Clone the repository
git clone https://github.com/Devcode940/MiniBrowser.git
cd MiniBrowser

# Build debug APK
./gradlew :app:assembleDebug

# Build release APK (unsigned)
./gradlew :app:assembleRelease
```

#### Using Android Studio
1. Open the project in Android Studio
2. Let Gradle sync
3. Build via **Build > Build Bundle(s) / APK(s) > Build APK**

### Output
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`

---

## Project Structure

```
MiniBrowser/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/minibrowser/
│   │   │   │   ├── BrowserCore.java          # WebView engine & privacy features
│   │   │   │   ├── GestureWebView.java        # Custom WebView with gestures
│   │   │   │   ├── MainActivity.java          # Entry point & UI
│   │   │   │   ├── MainViewModel.java         # UI state persistence
│   │   │   │   ├── MiniApp.java               # Application class
│   │   │   │   ├── BlocklistUpdater.java      # Remote blocklist management
│   │   │   │   ├── Bookmarks.java             # Bookmark storage
│   │   │   │   ├── ToolRegistry.java          # Customizable tools menu
│   │   │   │   ├── NotepadActivity.java       # Built-in text editor
│   │   │   │   ├── download/                  # Download manager & parsers
│   │   │   │   │   ├── DownloadManager.java
│   │   │   │   │   ├── DownloadTask.java
│   │   │   │   │   ├── DownloadActivity.java
│   │   │   │   │   ├── SegmentDownloader.java
│   │   │   │   │   ├── M3u8Parser.java
│   │   │   │   │   ├── MpdParser.java
│   │   │   │   │   └── MediaSniffer.java
│   │   │   │   └── media/                    # Media playback & AI
│   │   │   │       ├── AiClient.java
│   │   │   │       ├── MediaPlayerActivity.java
│   │   │   │       ├── SnackPlayer.java
│   │   │   │       └── SurfingKeys.java
│   │   │   ├── res/                          # Resources
│   │   │   │   ├── xml/network_security_config.xml  # Security config
│   │   │   │   ├── xml/file_paths.xml
│   │   │   │   ├── xml/data_extraction_rules.xml
│   │   │   │   └── ...
│   │   │   └── assets/
│   │   │       └── blocked_domains.txt    # Default blocklist
│   │   └── test/                         # Unit tests
│   │       └── java/com/minibrowser/
│   │           ├── BlocklistUpdaterTest.java
│   │           ├── BrowserCoreTest.java
│   │           └── media/AiClientTest.java
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties
└── build.gradle
```

---

## Configuration

### Network Security
The app uses a network security configuration that:
- Blocks all cleartext (HTTP) traffic by default
- Allows HTTPS connections to all domains
- Supports certificate pinning (configured but disabled by default)

See `app/src/main/res/xml/network_security_config.xml` for details.

### Release Signing
Release builds require signing. Configure via:

**Environment Variables:**
```bash
MINIBROWSER_RELEASE_STORE_FILE=/path/to/keystore.jks
MINIBROWSER_RELEASE_STORE_PASSWORD=yourpassword
MINIBROWSER_RELEASE_KEY_ALIAS=minibrowser
MINIBROWSER_RELEASE_KEY_PASSWORD=yourpassword
```

**Gradle Properties:**
```bash
./gradlew -Pandroid.injected.signing.store.file=/path/to/keystore.jks \
         -Pandroid.injected.signing.store.password=yourpassword \
         -Pandroid.injected.signing.key.alias=minibrowser \
         -Pandroid.injected.signing.key.password=yourpassword \
         assembleRelease
```

See `app/build.gradle` for the signing configuration.

---

## Customization

### Adding Custom AI Providers
```java
// In your code or via settings
AiClient aiClient = new AiClient(context);
aiClient.addCustomProvider(
    "My Custom Provider",
    "https://api.example.com/v1/chat/completions",
    "my-model"
);
```

### Adding Custom Tools
Tools are defined in `ToolRegistry.java`. Add entries to the `BUILTINS` map:
```java
BUILTINS.put("mytool", new Object[]{"My Tool", Boolean.TRUE, Boolean.FALSE});
```

### Custom CSS/JS
Place files in the app's files directory:
- `custom.css` - Applied to all pages
- `userscript.js` - Executed on page load

Or use the settings menu to edit them directly.

### Custom Blocklist
The default blocklist is in `app/src/main/assets/blocked_domains.txt`.

Remote blocklist can be configured:
```java
BlocklistUpdater updater = new BlocklistUpdater(context, domainSet);
updater.setUrl("https://your-hosts-file-url/hosts");
updater.setEnabled(true);
```

---

## Architecture

### Core Components

```
┌─────────────────────────────────────────────────────────┐
│                      MainActivity                           │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────────┐ │
│  │   ViewModel  │  │ GestureWebView │  │   ToolRegistry    │ │
│  │   (State)   │  │   (Input)     │  │    (Tools)        │ │
│  └──────┬──────┘  └─────────────┘  └──────────────────┘ │
└─────────┼────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────┐
│                     BrowserCore                             │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────────┐ │
│  │ WebViewClient│  │WebChromeClient│  │ BlocklistUpdater  │ │
│  │ (Navigation) │  │ (Permissions) │  │  (Blocklist)      │ │
│  └─────────────┘  └─────────────┘  └──────────────────┘ │
└─────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────┐
│                   WebView (GestureWebView)                 │
└─────────────────────────────────────────────────────────┘
```

### Threading Model
- **UI Thread**: Activity lifecycle, user interactions
- **Background Threads**: Blocklist loading, network requests, file I/O
- **Handler**: Main thread callbacks from background operations
- **ExecutorService**: DownloadManager uses thread pool (size=3) for concurrent downloads

### State Management
- **ViewModel**: Persists UI state across configuration changes
- **SharedPreferences**: Stores user preferences and settings
- **WeakReference**: Used in JS bridges to prevent memory leaks
- **Volatile Fields**: Runtime flags for thread-safe access

---

## Privacy & Security Details

### Blocking Mechanism
- **Host-based**: O(1) lookup via `ConcurrentHashMap`
- **Subdomain Matching**: Automatic (e.g., `doubleclick.net` blocks `ads.doubleclick.net`)
- **Suffix Matching**: Checks parent domains recursively
- **Local Blocklist**: ~130 domains shipped in APK
- **Remote Blocklist**: Fetches from StevenBlack/hosts (configurable)
- **Conditional GET**: Uses If-None-Match/If-Modified-Since for efficient updates
- **Memory Cap**: 200,000 domains maximum

### Fingerprint Spoofing
JavaScript injection freezes these properties:
- `navigator.userAgent`
- `navigator.appVersion`
- `navigator.platform`
- `navigator.vendor`
- `navigator.hardwareConcurrency`
- `navigator.deviceMemory`
- `navigator.maxTouchPoints`
- `navigator.language`
- `navigator.languages`
- `window.open` (prevents popup tracking)
- `navigator.clipboard.readText` (blocked)
- `window.RTCPeerConnection` (blocked)

### SSL/TLS
- Invalid certificates are **never** accepted
- Safe Browsing enabled (API 26+)
- Mixed content blocked by default (configurable via Compatibility Mode)

---

## Testing

Run unit tests:
```bash
./gradlew test
```

Test coverage includes:
- Blocklist parsing and validation
- URL detection and normalization
- JavaScript string escaping
- Heavy resource detection
- Provider management

---

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Commit your changes (`git commit -am 'Add some feature'`)
4. Push to the branch (`git push origin feature/your-feature`)
5. Open a Pull Request

### Code Style
- Java 8 compatibility
- Follow existing code patterns
- Add JavaDoc for public APIs
- Keep methods small and focused
- Thread-safe by design (use `ConcurrentHashMap`, `synchronized`, `Handler`)

### Pull Request Checklist
- [ ] Code compiles successfully
- [ ] All existing tests pass
- [ ] New tests added for new functionality
- [ ] No cleartext traffic introduced
- [ ] Privacy implications considered

---

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

---

## Acknowledgments

- Blocklist data from [StevenBlack/hosts](https://github.com/StevenBlack/hosts)
- AndroidX libraries from Google
- Media3 libraries from Google
- All contributors and testers
