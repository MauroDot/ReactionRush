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

## RR-004B: Player progression

Progression works entirely offline. Home shows the player's level, total XP, and progress toward the next level. Completed-session results show a performance medal, earned XP with its bonus breakdown, and a level-up announcement when applicable. Personal records keep the RR-004A rules above.

### XP rewards

| Mode | Base XP | Maximum speed bonus | Maximum consistency bonus | Maximum total |
| --- | ---: | ---: | ---: | ---: |
| Quick Draw | 10 | 5 | 0 | 15 |
| Classic Rush | 40 | 20 | 10 | 70 |
| Endurance Rush | 100 | 50 | 25 | 175 |

Let `B` be the mode's base XP, `A` its completed-session score in whole milliseconds (single reaction for Quick Draw, rounded average for Rush modes), and `R` the slowest minus fastest successful reaction in that session. All divisions below round down:

```text
speed bonus       = floor(B * clamp(450 - A, 0, 300) / 600)
consistency bonus = floor(B * clamp(100 - R, 0, 100) / 400)
session XP        = B + speed bonus + consistency bonus
```

Quick Draw's consistency bonus is always zero because one reaction cannot measure consistency. Speed bonuses cap at half the base reward and consistency bonuses at one quarter. False starts do not enter `A` or `R`; retrying and completing a session earns its normal reward. Incomplete or abandoned sessions earn zero XP.

Each new session receives a locally generated token. `RecordsRepository` accepts only the next successful round for that active token and mode. On the final round, a single preference edit saves the personal record and XP and consumes the token. Duplicate callbacks, results taps, navigation, stale tokens, and restarting the app cannot reward the completed session again. Leaving the game invalidates an unfinished token; starting another session also replaces any token left by process death. No reward history grows over time.

### Levels and medals

Level 1 starts at zero XP. The cumulative XP threshold for level `L` is `50 * L * (L - 1)`, so advancing from level `L` needs `100 * L` more XP. Thresholds begin at 0, 100, 300, 600, and 1,000 XP. The progress indicator uses XP earned within the current level divided by that level's full requirement. Integer binary search determines the level without floating-point threshold estimates.

XP is stored as a nonnegative 64-bit integer and addition saturates at `Long.MAX_VALUE`. The highest representable level is 429,496,730; it displays complete progress and `MAX LEVEL`. At the XP storage limit, results show only XP actually credited and explain the cap. Missing, negative, or wrongly typed persisted XP loads as zero. Invalid legacy numeric statistics also load safely as zero; valid RR-004A saves are preserved.

| Completed-session score | Medal |
| --- | --- |
| Below 200 ms | Elite |
| 200–249 ms | Platinum |
| 250–299 ms | Gold |
| 300–349 ms | Silver |
| 350–449 ms | Bronze |
| 450 ms or higher | Unranked |

Medals use the same completed-session score as personal records and never alter those records. Level and medal calculations live in `PlayerProgression`, outside Compose.

### Persistence and lifecycle limits

XP, session-token metadata, and personal records share the existing `reaction_rush` preferences. Normal app restarts retain completed rewards. Preference writes use Android's asynchronous `apply()`: sudden process failure before a write reaches disk can lose the latest update, and disk errors are not reported to the UI. In-progress sessions and result announcements are not restored after activity recreation or process death. Background timing behavior is unchanged from RR-004A; a round can continue while the app is hidden.

### Manual acceptance checklist

- Launch with an existing RR-004A save; confirm overall best, reactions, and mode records remain intact, with level 1 and 0 XP if progression is new.
- Complete each mode. Confirm XP stays unchanged until its final valid reaction, then results show a medal, XP, and updated progress using the formulas above.
- False-start and leave a session; confirm zero XP. Retry a false start and complete a session; confirm normal XP based only on successful reactions.
- Repeatedly tap results, return Home, reopen the mode, and restart the app. Confirm completed-session XP is credited once and remains saved.
- Cross 100 XP and later 300 XP; confirm levels 2 and 3, updated progress, and level-up feedback. Replay should clear the old announcement.
- Confirm tied/slower performances still earn session XP without replacing personal records or displaying a new-record announcement.
- On a small screen, scroll Home and the full Endurance results. Confirm XP, medals, record feedback, and all round scores remain reachable.

## Cloud APK build
The included GitHub Actions workflow runs unit tests and Android lint, then builds a debug APK for pull requests and pushes to `main`, or manually from the Actions tab. Download the `ReactionRush-debug-apk` artifact and install `app-debug.apk` on an Android device.

## Local build
Open the project in Android Studio and run it on a device, or build with the Gradle wrapper and JDK 21 (the project's configured Gradle runtime):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The unit tests cover record isolation, completed-session scoring and rounding, strict improvements, lifetime save compatibility, XP formulas and caps, medal and level boundaries, overflow, invalid persisted values, exactly-once rewards, and preference-file persistence.

With an Android emulator or device connected, run gameplay checks with:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

The gameplay tests cover false starts, unfinished sessions, all three modes, record and progression feedback, replay, Home updates, and saved records after activity recreation. Run them on a dedicated emulator: they temporarily clear the app's preferences and restore them after each test, but an interrupted test process cannot run that restoration.
