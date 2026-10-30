# Reaction Rush
A native Android reaction-time game by Mauro Interactive.

## Gameplay
Tap to start, wait for green, then tap as quickly as possible. False starts retry the current round without counting as a reaction or affecting the session average.

## RR-004A: Persistent personal records

- **Quick Draw:** fastest successful single reaction.
- **Classic Rush:** lowest average across a completed five-round session.
- **Endurance Rush:** lowest average across a completed ten-round session.

Session averages round to the nearest whole millisecond, matching the results screen. The first completed session sets a record; only a strictly faster displayed score replaces it. Ties and slower sessions leave it unchanged. A new personal record is announced after the final reaction and on the session results screen. Starting another session clears the announcement.

Mode cards and the selected mode's game screen show its saved record, or a dash before a record exists. Records save as soon as the final successful reaction is recorded, even if you return Home before opening the results. Unfinished sessions do not set mode records.

The lifetime **Overall Best** remains the fastest individual reaction across all modes, and **Reactions** counts every successful reaction, including those in unfinished sessions. Existing lifetime saves are preserved in the original `reaction_rush` preferences (`best` and `attempts`). Mode records use separate keys (`record_quick_draw`, `record_classic_rush`, `record_endurance_rush`) and start empty on upgrade because old saves do not identify the mode. All records persist locally across app restarts.

## Cloud APK build
The included GitHub Actions workflow runs unit tests and Android lint, then builds a debug APK for pull requests and pushes to `main`, or manually from the Actions tab. Download the `ReactionRush-debug-apk` artifact and install `app-debug.apk` on an Android device.

## Local build
Open the project in Android Studio and run it on a device, or build with the Gradle wrapper and JDK 21 (the project's configured Gradle runtime):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The unit tests cover record isolation, completed-session scoring and rounding, strict improvements, lifetime save compatibility, invalid scores, and persistence through a new repository instance.

With an Android emulator or device connected, run gameplay checks with:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

The gameplay tests cover false starts, unfinished sessions, all three modes, new-record feedback and replay, Home updates, and saved records after activity recreation. They restore the device's previous preferences after each test.
