# Changelog

All notable changes to MiniBrowser are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [v17] - 2026-07-18

### Security Improvements
- Removed `usesCleartextTraffic="true"` from AndroidManifest.xml, enforcing HTTPS-only connections
- Added network security configuration (`network_security_config.xml`) with:
  - Cleartext traffic blocked by default
  - Domain-specific configurations for AI providers and blocklist sources
  - Certificate pinning placeholder for future use
- Fixed duplicate `sentry.io` entry in blocked_domains.txt

### Bug Fixes
- Fixed state synchronization between MainActivity and ViewModel:
  - `onUrlChanged()` now updates ViewModel home state
  - `onHome()` now updates ViewModel home state
  - Prevents state desynchronization during navigation

### Enhancements
- Added URL validation in `BlocklistUpdater.setUrl()` - rejects non-HTTP/HTTPS URLs
- Added null checks in `BlocklistUpdater.doRefresh()` - prevents NPEs
- Added rate limiting in `AiClient.send()` - minimum 2 seconds between requests to prevent API abuse
- Configured release signing in build.gradle with support for:
  - Environment variables (`MINIBROWSER_RELEASE_*`)
  - Gradle properties (`android.injected.signing.*`)

### Testing
- Enhanced `BlocklistUpdaterTest.java` with URL validation tests
- Enhanced `BrowserCoreTest.java` with null-safety test
- Added new `AiClientTest.java` with provider and message tests

### Documentation
- Added comprehensive README.md with:
  - Feature list
  - Quick start guide
  - Project structure
  - Configuration instructions
  - Customization guide
  - Architecture overview
  - Privacy & security details
  - Testing instructions
  - Contributing guidelines
- Added CONTRIBUTING.md with:
  - Contribution guidelines
  - Code style rules
  - Pull request process
  - Best practices
  - Project structure reference
- Added CHANGELOG.md (this file)

---

## [v16] - 2025-XX-XX

*(Previous version - changelog not maintained before v17)*

---

## Format

### Added
- For new features.

### Changed
- For changes in existing functionality.

### Deprecated
- For soon-to-be removed features.

### Removed
- For now removed features.

### Fixed
- For any bug fixes.

### Security
- In case of vulnerabilities.
