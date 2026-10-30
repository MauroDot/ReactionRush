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

## RR-004C: Achievements and milestones

Home opens a scrollable Achievements screen showing all 12 definitions, explicit locked/unlocked labels, measurable progress, and an unlocked total. The final reaction and session summary announce new unlocks without opening a modal or interrupting play; the results also list their names. Achievements do not add XP or change scoring, medals, personal records, or the RR-004B reward formulas.

| Persistent ID | Name | Unlock condition |
| --- | --- | --- |
| `GOLD_REFLEX` | Gold Reflex | Gold or better on a completed session |
| `PLATINUM_REFLEX` | Platinum Reflex | Platinum or better on a completed session |
| `ELITE_REFLEX` | Elite Reflex | Elite on a completed session |
| `STEADY_HANDS` | Steady Hands | Classic Rush reaction spread at most 40 ms |
| `IRON_FOCUS` | Iron Focus | Endurance Rush reaction spread at most 60 ms |
| `CLEAN_SWEEP` | Clean Sweep | Five consecutive completed sessions with no false starts |
| `GETTING_STARTED` | Getting Started | 10 completed sessions |
| `DEDICATED` | Dedicated | 50 completed sessions |
| `VETERAN` | Veteran | 100 completed sessions |
| `RISING_STAR` | Rising Star | Reach level 5 |
| `REFLEX_MASTER` | Reflex Master | Reach level 10 |
| `LEGEND` | Legend | Reach level 25 |

### Completion and streak algorithm

`Achievements` contains the pure unlock/progress calculations. `RecordsRepository` accepts only the next successful round of the current session token, preserving RR-004B's duplicate protection. False starts set a persisted session flag; they never increment completed sessions, reset the clean streak, award XP, or unlock achievements by themselves.

Only the final accepted reaction increments completed sessions. A session whose false-start flag is false increments the clean streak; a session with any earlier false start resets it to zero, even after a successful retry. Abandoning a session leaves the previous completed count and streak unchanged. Spread is the slowest minus fastest valid reaction, excluding false starts.

Unlock evaluation uses the completed-session medal, mode-specific spread, updated counters, and the level after awarding that session's normal XP. Higher medals unlock all lower reflex tiers. Earned IDs remain permanent; newly unlocked IDs are the difference between the resulting and previous sets. Results navigation, repeated callbacks, stale tokens, and restart cannot count the same completion twice.

### Persistence and migration

The existing `reaction_rush` preferences now also hold:

| Key | Type | Meaning |
| --- | --- | --- |
| `unlocked_achievement_ids` | String set | Recognized permanent achievement IDs |
| `completed_sessions` | Long | Successful full sessions tracked since RR-004C |
| `clean_session_streak` | Int | Consecutive clean completed sessions |
| `active_session_had_false_start` | Boolean | Whether the current session has failed any round |

The final reaction saves achievement IDs, counters, XP, any personal record, and session-token consumption in one preference edit under the repository's existing lock. Session start initializes the false-start flag; completion or abandonment removes it with the other active-session metadata. A missing or malformed flag cannot establish a clean session.

On load, valid existing per-mode records can prove reflex-medal achievements, and existing XP can prove level milestones. Those IDs are reconciled and persisted without awarding XP or incrementing counters. Older saves cannot establish historical completion counts, spreads, or clean streaks, so these start at zero. Reconciliation can also recover missing lower-tier IDs implied by an already earned higher-tier achievement, without inventing numeric history.

Unknown or malformed saved IDs are ignored safely. Negative or wrongly typed counters load as zero, streaks cannot exceed completed sessions, and counters saturate instead of overflowing. Unlocks are never revoked when a current streak resets or numeric history needs recovery. For unlocked measurable achievements, the UI shows their target as completed; the screen separately shows the current clean streak.

The RR-004B asynchronous-write and lifecycle limits still apply: abrupt failure before disk persistence can lose the latest update, and active games are not resumed after activity recreation or process death. All achievement evaluation and storage work offline; no permissions, network services, or dependencies were added.

### Manual achievement acceptance checklist

- Upgrade a save with personal records and XP. Verify only provable reflex/level achievements unlock, old statistics remain intact, and untracked session counts start at zero.
- Open Achievements from Home. Scroll through all 12 names and descriptions, verify locked/unlocked text, progress, total unlocked, and both Home and system Back navigation.
- Earn Gold, Platinum, and Elite; verify each higher medal includes lower reflex unlocks and no achievement is announced twice.
- Complete Classic with spreads 40 and 41 ms and Endurance with 60 and 61 ms; verify the inclusive thresholds use valid reactions only.
- Complete five clean sessions. Verify Clean Sweep, then complete a dirty session: the current streak resets while the unlock remains permanent.
- False-start and abandon a session; verify the current streak, session count, XP, and unlocked IDs remain unchanged. Retry a false start to completion and verify that session is counted but not clean.
- Cross session counts 10, 50, and 100 and levels 5, 10, and 25. Verify each milestone unlocks once after the relevant saved facts exist.
- Retap results, navigate away, restart, and reopen Achievements. Verify no duplicate counts/unlocks, persistent XP and records, and readable results on a small screen.

## RR-004D: Session history and player statistics

Home opens Statistics, which separates saved player totals from retained-history metrics. It shows total completed sessions, successful reactions, global and per-mode records, XP, level, clean streak, achievement count, mode completion totals, recent averages/trends, and a newest-first session list. Each session opens a scrollable detail view with its completion time, all round scores, best time, rounded average, spread, false-start count, actual credited XP, and medal. Home and system Back navigation work from both list and detail views.

### Storage and completion integrity

History uses the existing `reaction_rush` preferences, under `session_history`, as JSON with a `version: 1` wrapper and a `sessions` array. Entries contain the session token ID, mode, completion timestamp in epoch milliseconds, round scores, best/average/spread, false-start count, earned XP, and medal. The list retains at most 100 distinct completed sessions. New completions prepend entries; array order remains completion order even if the device clock moves backward. Dates display in the device's locale and time zone.

Only an accepted final reaction creates history. The repository's existing token and round-sequence checks reject duplicate or stale callbacks. One preference edit saves the session entry, mode counts, records, XP, achievements, and token consumption together. Abandoned or incomplete sessions never enter history, although their valid reactions still contribute to the preexisting lifetime reaction count and global best.

`active_session_false_start_count` extends the existing false-start flag with a saturated integer count. Each failure increments it; retries retain it, and completion/abandonment clears it. Missing or contradictory active-session metadata is treated conservatively as dirty with at least one false start. Normal sessions retain their exact count. Historical XP is the actual credited amount, including reduced or zero credit at the XP storage limit.

Per-mode totals use nonnegative Long values under `completed_sessions_quick_draw`, `completed_sessions_classic_rush`, and `completed_sessions_endurance_rush`. These counters keep growing after old history is pruned. Missing, invalid, or too-low mode counters recover a minimum from valid retained entries. The next completion persists those recovered minimums, including other modes, so subsequent pruning cannot erase them. Counter overflow saturates rather than wrapping.

### Statistics rules and migration

- **Saved player totals:** Existing lifetime successful reactions, global best, personal records, XP, level, clean streak, and unlocked achievements retain their previous definitions. The completed-session total remains RR-004C's saved count; earlier sessions before that counter existed cannot be reconstructed.
- **Mode completion totals:** Tracking begins with RR-004D. Old saves start with no history and zero per-mode counts; earlier unknown mode totals are not guessed from XP or reaction counts. The screen explains this cutoff.
- **Retained average:** Sum all valid individual round times in retained completed sessions, divide by the number of those reactions, then round to the nearest whole millisecond. Every reaction has equal weight, so a ten-round Endurance session contributes ten observations. False starts and abandoned-session reactions are excluded. Empty history displays a dash.
- **Recent trends:** Calculate separately for each mode only when at least ten of that mode's sessions are retained. Compare the mean reaction time across the latest five sessions against the previous five. A lower recent average is faster; equal displayed averages are unchanged. These comparisons use retained history only, never mixed-mode averages.

### Recovery and storage limits

The decoder accepts at most 200,000 serialized characters and limits nesting and ID length. Strict streaming JSON validation enforces the nesting bound before parsing objects. It preserves valid sibling entries when individual entries are malformed, including out-of-range numeric fields, ignores duplicate IDs after the first, and retains only the first 100 valid distinct entries. Unknown schema versions or wholly unreadable payloads load as unavailable history without changing saved player totals.

Core mode, ID, timestamp, scores, false-start count, and XP fields must be valid. Best time, rounded average, spread, and medal are recomputed from valid scores, repairing inconsistent derived fields. The UI marks history that needed recovery. The next valid completion writes canonical bounded history and clears that payload warning; discarded entries cannot be reconstructed. XP and achievements are never re-awarded from decoded history.

History is encoded only for completed sessions, and decoded data is cached while its stored value is unchanged. Load, partial reactions, false starts, and rejected callbacks do not serialize history. Existing per-reaction preference updates still rewrite Android's containing XML file, including the bounded history string; this tradeoff preserves the single-edit completion architecture without adding a database or dependency. The earlier asynchronous-write and active-game lifecycle limitations still apply.

### Manual statistics acceptance checklist

- Upgrade an existing save; verify preserved player totals and records, empty history, and clearly explained mode-count tracking scope.
- Complete each mode. Verify newest-first rows, completion time, every round score, best/average/spread, false starts, XP, and medal in details.
- Make multiple false starts, then finish. Verify the exact count in history and unchanged normal XP rules. Abandon a partial session and confirm no history or completed-mode increment.
- Retap results, navigate, reopen Statistics, and restart the app. Verify each session is recorded once and both header and system Back return to the expected screen.
- With QA data, cross 100 completed sessions. Verify only the newest 100 remain, while saved session and mode totals retain older completions.
- Verify the retained average against individual round scores, and compare faster/slower/unchanged trends after ten sessions in one mode.
- With a QA save containing malformed history entries, confirm valid entries remain visible, the recovery notice appears, and all existing XP, records, and achievements remain intact.
- Check the full Statistics page and ten-round session details on a small physical device, including large font settings and accessibility reading order.

## Cloud APK build
The included GitHub Actions workflow runs unit tests and Android lint, then builds a debug APK for pull requests and pushes to `main`, or manually from the Actions tab. Download the `ReactionRush-debug-apk` artifact and install `app-debug.apk` on an Android device.

## Local build
Open the project in Android Studio and run it on a device, or build with the Gradle wrapper and JDK 21 (the project's configured Gradle runtime):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The unit tests cover records, XP, levels, achievements, clean streaks, history capture/retention/recovery, weighted statistics, same-mode trends, overflow, exactly-once completion, migration, and preference-file persistence.

With an Android emulator or device connected, run gameplay checks with:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

The gameplay tests cover all three modes, existing records/progression/achievements, Statistics and session details, trends, false starts, unfinished sessions, recovery, migration, navigation, and saved state after activity recreation. Run them on a dedicated emulator: they temporarily clear the app's preferences and restore them after each test, but an interrupted test process cannot run that restoration.
