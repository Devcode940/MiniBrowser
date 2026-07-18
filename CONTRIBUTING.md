# Contributing to MiniBrowser

We welcome contributions! Here's how you can help make MiniBrowser better.

---

## Ways to Contribute

### Reporting Issues
- Use the [GitHub Issues](https://github.com/Devcode940/MiniBrowser/issues) tracker
- Include steps to reproduce the issue
- Specify your Android version and device (if applicable)
- Include screenshots or logs if helpful

### Suggesting Features
- Open an issue with your feature request
- Explain the use case and why it would be valuable
- Discuss before implementing large changes

### Code Contributions
- Fork the repository
- Create a feature branch
- Implement your changes
- Add tests
- Submit a Pull Request

---

## Getting Started

### Prerequisites
- [Android Studio](https://developer.android.com/studio) (recommended)
- Android SDK (API 24+)
- Java 17+
- Gradle 8.2+

### Setup
```bash
git clone https://github.com/Devcode940/MiniBrowser.git
cd MiniBrowser
# Build with Gradle wrapper
./gradlew :app:assembleDebug
```

### Running Tests
```bash
./gradlew test
```

---

## Code Style Guidelines

### Java
- **Version**: Java 8 compatibility (bytecode level 1.8)
- **Formatting**: Follow existing code style
- **Indentation**: 4 spaces (tabs are not used)
- **Braces**: Opening braces on same line, closing braces on new line
- **Naming**: camelCase for variables/methods, PascalCase for classes

### Architecture
- **Single Activity**: Keep the single-activity pattern
- **Separation of Concerns**: Browser logic in BrowserCore, UI in MainActivity
- **Thread Safety**: Use ConcurrentHashMap, synchronized blocks, or Handler for cross-thread communication
- **Memory Management**: Use WeakReference for Activity references in long-lived objects

### Documentation
- Add JavaDoc to all public classes and methods
- Include comments explaining non-obvious logic
- Keep comments up to date

---

## Pull Request Process

1. **Create a Branch**
   ```bash
   git checkout -b feature/your-feature
   ```

2. **Make Changes**
   - Keep commits atomic (one logical change per commit)
   - Write clear commit messages
   - Reference issue numbers in commits

3. **Test Your Changes**
   - Run existing tests: `./gradlew test`
   - Add new tests for new functionality
   - Manually test on a device/emulator

4. **Update Documentation**
   - Update README.md if adding features
   - Update this file if changing contribution guidelines

5. **Submit PR**
   - Clear title and description
   - Reference related issues
   - Include screenshots if UI changes

---

## Review Process

All PRs will be reviewed for:
- **Security**: No cleartext traffic, proper validation, no privacy leaks
- **Code Quality**: Follows style, well-documented, tested
- **Privacy**: Respects user privacy, no telemetry
- **Performance**: No unnecessary overhead, proper threading

---

## Coding Best Practices

### Privacy & Security
- **Never** enable cleartext traffic (`usesCleartextTraffic="false"`)
- **Always** validate user input
- **Never** accept invalid SSL certificates
- Use HTTPS for all network requests
- Minimize data collection

### Threading
- Never do file I/O or network operations on the UI thread
- Use Handler for main thread callbacks
- Use synchronized blocks for shared mutable state
- Consider using ExecutorService for thread pools

### Error Handling
- Catch exceptions at appropriate levels
- Provide meaningful error messages to users
- Log errors with enough context for debugging
- Don't crash the app for recoverable errors

### Resource Management
- Always close InputStreams, OutputStreams, and connections
- Use try-with-resources when possible
- Clean up resources in destroy/onDestroy methods
- Be mindful of memory usage with large files

---

## Project Structure

```
app/
├── src/main/java/com/minibrowser/
│   ├── BrowserCore.java          # Core browser engine
│   ├── MainActivity.java          # Main UI and entry point
│   ├── MainViewModel.java         # UI state
│   ├── GestureWebView.java        # Custom WebView with gestures
│   ├── BlocklistUpdater.java      # Blocklist management
│   ├── Bookmarks.java             # Bookmark storage
│   ├── ToolRegistry.java          # Tools menu configuration
│   ├── download/                  # Download functionality
│   └── media/                    # Media and AI features
├── src/main/res/                 # Resources
│   └── xml/network_security_config.xml  # Security configuration
├── src/main/assets/              # Assets
│   └── blocked_domains.txt        # Default blocklist
└── src/test/                     # Unit tests
```

---

## Testing

### Unit Tests
- Located in `app/src/test/java/`
- Use JUnit 4
- Test pure Java logic (parsers, validators, utilities)
- Mock Android dependencies when possible

### Adding Tests
- Create a test class matching the production class name
- Test public methods
- Include edge cases
- Keep tests fast (< 100ms each)

### Example Test
```java
@Test
public void parseHost_standardHostsFormat() {
    assertEquals("example.com", BlocklistUpdater.parseHost("0.0.0.0 example.com"));
}
```

---

## Versioning

We use semantic versioning:
- `MAJOR` - Breaking changes, significant new features
- `MINOR` - Backward-compatible new features
- `PATCH` - Bug fixes only

Version is set in `app/build.gradle`:
```gradle
versionCode 17
versionName "v17"
```

---

## Releasing

1. Update versionCode and versionName in build.gradle
2. Update CHANGELOG.md
3. Run full test suite
4. Build signed release APK
5. Create GitHub release with APK attached
6. Tag the commit

---

## Communication

- **GitHub Issues**: Bug reports and feature requests
- **Pull Requests**: Code contributions
- **Discussions**: General questions and ideas

---

## License

By contributing, you agree that your contributions will be licensed under the same license as the project (Apache 2.0).
