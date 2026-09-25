# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.1] - 2026-09-25

### Heads-up for Android hosts
- **The plugin now ships an Android manifest.** It registers
  `SynheartNotificationListenerService` (guarded by
  `BIND_NOTIFICATION_LISTENER_SERVICE`) and requests
  `BIND_NOTIFICATION_LISTENER_SERVICE` and `READ_PHONE_STATE` (the call
  collector). Manifest merger adds all three to every host: the app is
  listed on the system's *Notification access* screen, and both
  permissions join its manifest. The service does nothing until
  the person grants notification access. A host that does not collect
  interruptions removes them:

  ```xml
  <uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
      tools:node="remove" />
  <uses-permission android:name="android.permission.READ_PHONE_STATE"
      tools:node="remove" />
  <service android:name="ai.synheart.behavior.SynheartNotificationListenerService"
      tools:node="remove" />
  ```

  A host that collects them only on opt-in can merge the service with
  `android:enabled="false"` and enable the component at runtime.

### Fixed
- **Android: the notification listener was never registered.** The plugin
  had no manifest, so the collector was dead code in every host and
  `NotificationReceived` reached the engine with no action on the one
  platform that can observe a click (`REASON_CLICK`).
- **Android: notification events carry `source_app`.** The collector had
  the posting package in scope but never reported it, so an interruption
  could not be attributed. Tracked per notification id, so the later
  click and the delayed `ignored` name the same app.
- **Android: accelerometer at 50 Hz.** `MotionSignalCollector` requested
  `SENSOR_DELAY_NORMAL` (about 5 Hz, varying by OEM) while documenting
  50 Hz, which iOS delivers; it now requests an explicit 20 000 µs period.
- **Android: one event per call, at its outcome.** A call used to emit when
  it started ringing, labelled `ignored`, and again with its outcome
  (`ignored` after 30 s, or `answered`). A consumer counting call events
  saw every unanswered call twice, and an answered call as one ignored
  plus one answered. Android now emits only the outcome, which is what the
  iOS `CallCollector` already did. Notifications are unchanged: an arrival
  (`received`) followed by its outcome.

## [0.4.0] - 2026-05-20

This release narrows the SDK's responsibility: it is now a behavioral
*event producer*. Per-session aggregate metrics that the SDK used to
compute on-device are no longer produced here — a downstream consumer
derives them from the event stream.

### Breaking
- **Session-end aggregation removed.** `BehaviorSessionSummary` no
  longer carries computed aggregates. `behavioralMetrics` is now
  `BehavioralMetrics?` and is `null` unless a consumer has populated
  it; `interactionIntensity`, `taskSwitchRate`, `burstiness`,
  `scrollJitterRate`, `notificationIgnoreRate`,
  `notificationClusteringIndex`, and `typingSessionSummary` are no
  longer emitted at session end. `toJson` omits `behavioral_metrics`
  when absent; `fromJson` yields `null` when the key is missing.
  Callers that read these fields must null-check, or compute the
  aggregates themselves from the event stream.
- The native plugins (iOS and Android) drop the `behavioral_metrics`,
  `notification_ignore_rate`, `notification_clustering_index`, and
  `typing_session_summary` keys from both the session-end payload and
  `calculateMetricsForTimeRange()`.

### Added
- `BehaviorEvent.appSwitch(...)` typed factory — closes a cross-SDK
  parity gap (Kotlin and Swift already exposed `appSwitch`). The
  `app_switch` value already existed on `BehaviorEventType`; this adds
  the matching Dart constructor.

### Changed
- `BehaviorStats` exposes `typingCadence`, `interKeyLatency`, and
  `burstLength` as nullable fields again, so the stats shape stays
  symmetric with the Swift and Kotlin SDKs on the wire.

### Kept
- Real-time stats via `getCurrentStats` (cheap, last-seen values).
- Raw event emission — every event type is unchanged.
- Raw counts on the session summary: `notification_count`,
  `notification_ignored`, `call_count`, `call_ignored`,
  `clipboard_*`, and activity totals.

## [0.3.0] - 2026-05-07

OSS-launch refactor pass.

### Breaking
- Removed `motion_state_inference` public export and the underlying
  `MotionFeatureExtractor` / `MotionSignalCollector` classifier path.
  Motion classification is no longer part of the public surface.
- Removed `behavior_window_aggregator`, `behavior_window_features`, and
  `behavior_feature_extractor` (already deprecated; window features
  moved out of the real-time event stream).

### Added
- `motion_sample` model export — raw accelerometer sample batching for
  callers that need to do their own classification.
- `core/logger` export — pluggable logging shared with the rest of the
  Synheart SDK family.
- `app_switch` event type and improved gesture/touch handling on both
  platforms.

### Changed
- Renamed Android package `com.synheart` → `ai.synheart` to match the
  org-wide naming convention.
- Cleaned internal-only language and stale runtime references from
  source comments and README.

## [0.2.1] - 2026-05-06

Initial open-source release of the Synheart Behavior SDK for Flutter.

The SDK collects privacy-preserving behavioral signals (taps, scrolls,
swipes, app switches, idle gaps, typing session counts) on iOS and
Android. No text, content, or PII is captured. Behavioral and typing
metrics are computed locally by the native iOS / Android implementations
and surfaced through `BehaviorSessionSummary` (`behavioralMetrics`,
`typingSessionSummary`).

### Public surface
- `SynheartBehavior`, `BehaviorConfig`, `BehaviorEvent`,
  `BehaviorSession`, `BehaviorSessionSummary`, `BehavioralMetrics`,
  `TypingSessionSummary`, `BehaviorStats`.
- Streaming API for real-time behavioral events; session-tracking API
  with summaries; manual stats polling.
- On-demand metrics for ended sessions:
  `calculateMetricsForTimeRange()`.

### Platform support
- iOS 12.0+
- Android API 21+ (Android 5.0+)
- Flutter 3.10.0+

[Unreleased]: https://github.com/synheart-ai/synheart-behavior-flutter/compare/v0.4.1...HEAD
[0.4.1]: https://github.com/synheart-ai/synheart-behavior-flutter/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/synheart-ai/synheart-behavior-flutter/releases/tag/v0.4.0
[0.3.0]: https://github.com/synheart-ai/synheart-behavior-flutter/releases/tag/v0.3.0
[0.2.1]: https://github.com/synheart-ai/synheart-behavior-flutter/releases/tag/v0.2.1
