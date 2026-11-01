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

Each new session receives a locally generated token. `RecordsRepository` accepts only the next successful round for that active token and mode. On the final round, a single preference edit saves the personal record and XP and consumes the token. Duplicate callbacks, results taps, navigation, stale tokens, and restarting the app cannot reward the completed session again. Leaving the game invalidates an unfinished token; starting another session also replaces any token left by process death. No unbounded completed-token ledger is retained.

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

XP, session-token metadata, and personal records share the existing `reaction_rush` preferences. Normal app restarts retain completed rewards. RR-004E adds explicit interruption behavior, off-thread save confirmation, and retry feedback; its remaining crash-consistency limits are described below. Active timing measurements and session-result announcements are not restored after activity recreation or process death.

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

RR-004E governs interruption and save-confirmation behavior. Abrupt failure before disk persistence can still lose the latest update, and active games are never resumed after activity recreation or process death. All achievement evaluation and storage work offline; no permissions, network services, or dependencies were added.

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

History is encoded only for completed sessions, and decoded data is cached while its stored value is unchanged. Load, partial reactions, false starts, and rejected callbacks do not serialize history. Existing per-reaction preference updates still rewrite Android's containing XML file, including the bounded history string; this tradeoff preserves the single-edit completion architecture without adding a database or dependency. Completion durability and interruption behavior follow RR-004E below.

### Manual statistics acceptance checklist

- Upgrade an existing save; verify preserved player totals and records, empty history, and clearly explained mode-count tracking scope.
- Complete each mode. Verify newest-first rows, completion time, every round score, best/average/spread, false starts, XP, and medal in details.
- Make multiple false starts, then finish. Verify the exact count in history and unchanged normal XP rules. Abandon a partial session and confirm no history or completed-mode increment.
- Retap results, navigate, reopen Statistics, and restart the app. Verify each session is recorded once and both header and system Back return to the expected screen.
- With QA data, cross 100 completed sessions. Verify only the newest 100 remain, while saved session and mode totals retain older completions.
- Verify the retained average against individual round scores, and compare faster/slower/unchanged trends after ten sessions in one mode.
- With a QA save containing malformed history entries, confirm valid entries remain visible, the recovery notice appears, and all existing XP, records, and achievements remain intact.
- Check the full Statistics page and ten-round session details on a small physical device, including large font settings and accessibility reading order.

## RR-004E: Lifecycle reliability and persistence hardening

### Foreground timing and interruption policy

A round is playable only while the Activity is both resumed and window-focused. `MainActivity` forwards resume, pause, stop, destroy, and window-focus changes to a small synchronous `ForegroundMonitor`. Resume also samples the window's current focus because recreation can preserve a focused window without another focus-change callback. The game component owns the dependent behavior: on loss of foreground it immediately cancels the waiting coroutine, invalidates its timing ticket, cancels haptics, and abandons any unfinished repository session.

The policy is deliberately conservative: WAITING, GO, false-start retry, and between-round unfinished sessions end on interruption. Returning does not restart a timer automatically. `SESSION INTERRUPTED` explains what happened, and a deliberate tap starts the selected mode again from round one with a new token. Home remains available. Valid reactions recorded before interruption remain in lifetime statistics; interruption itself adds no reaction, false start, completed session, XP, achievement, history entry, or clean-streak reset.

`RoundTimingGuard` uses opaque object-identity tickets. A pending callback can signal GO once only for the current ticket, and a reaction can consume it once only while foregrounded. Backgrounding, cancellation, and clock anomalies invalidate the measurement. Rendered touch and accessibility actions capture their phase, session ID, ticket, and interaction identity, so an old action cannot act on another round, a replay, or a new session. Disposing the game disables its old handlers. Time outside the foreground cannot become a valid reaction score.

Already completed final-round and session-result screens can remain visible through an ordinary pause/resume because their timing tickets and repository tokens have already been consumed. Their rewards are not repeated.

### Recreation and startup

Every Activity recreation returns to Home. No active round, coroutine, timing ticket, or elapsed-time measurement is restored. A saved unfinished-session marker produces an explicit interruption notice; earned records, XP, achievements, and history remain available. At process startup, any orphan active-session metadata is invalidated without awarding progress, and the same notice is shown.

Preferences and player data are hydrated on `Dispatchers.IO`, with a loading state and retry action if initialization throws. Orphan invalidation occurs only after that load returns to the still-active UI coroutine, against already hydrated memory. A canceled old startup therefore cannot later invalidate a newer game. A completed save worker is process-scoped rather than Activity-scoped, so recreation does not cancel it.

### Completed-session save confirmation

The existing completion edit still applies history, counters, records, XP, achievements, and session-token consumption together in memory. It then requests a background durability checkpoint from `CompletionPersistence`, shared by repository instances for that preference file.

A single daemon executor performs an empty-editor `commit()` barrier off the UI thread. Android snapshots the current preference generation, waits for queued writes, and reports whether that generation was written successfully. Multiple pending requests coalesce by generation; an older outcome cannot replace a newer pending result. Disk commits never run while holding the repository's external preference lock, so gameplay does not wait for the application's save worker.

Home and safe result screens show saving/saved status. A failed write or worker submission shows a retry action. Retry flushes the latest accepted in-memory state; it never replays reaction, XP, achievement, or history logic. Failed saves do not roll back accepted in-memory progression or reactivate consumed session tokens.

### Durability limits and migration

All existing preference keys and history schema remain compatible; no toolchain, production dependencies, permissions, or network services change. Startup removes only transient active-session metadata. Save status is a process-local acknowledgment, not a new permanent gameplay statistic.

`SAVED` means Android's commit barrier reported success for the completed state or a newer state. This is not an application journal, a cross-file database transaction, or an absolute guarantee against power loss, filesystem failure, or corruption. If the process dies before confirmation, the latest state may or may not have reached disk. A failed commit may also report failure after some data reached storage, so retry remains conservative and idempotent. Unrecoverable preference-file corruption or cleared app data cannot be reconstructed by this mechanism.

Android can itself wait for pending `apply()` writes during lifecycle transitions. Moving the application's commits and cold loads off Main reduces application-controlled blocking but does not remove that platform behavior. Existing bounded-history XML rewrite costs also remain.

### Manual lifecycle and save acceptance checklist

- Background each mode during WAITING and GO using Home/app switching. Return after a long delay; verify interruption, no playable old signal, and no automatic restart or new progression.
- Open/close the notification shade, lock/unlock the screen, and receive a call or system dialog on a physical device. Verify focus-loss interruption and a deliberate fresh restart.
- Repeat pause/resume cycles, including after a false-start retry and between Classic/Endurance rounds. Verify no extra false starts, completed sessions, XP, achievements, history, or clean-streak changes.
- Recreate the Activity during active play. Verify Home's interruption notice and preserved successful-reaction statistics; a new game must start at round one.
- Complete each mode, wait for the saved acknowledgment, then recreate and cold-restart. Verify records, XP, achievements, history, mode totals, and false-start counts persist once.
- On a disposable test device or controlled test harness, fail a save. Verify visible failure feedback, responsive UI, retry, and unchanged progression after retry.
- Check small-screen interruption/result layouts, large fonts, Back navigation, and accessibility announcements. Actual call, overlay, storage-hardware, and power-loss behavior requires physical-device acceptance.

## RR-005A: Visual identity and UI polish

### Design system

`DesignSystem.kt` defines the shared Material 3 dark theme and reusable panels, section labels, wrapping navigation headers, medal badges, and original pulse/target mark.

| Token | Value / purpose |
| --- | --- |
| Background | `#0B1020`, deep navy |
| Surface / raised surface | `#151D35` / `#1C2844` |
| Primary / secondary / achievement | `#62E6B5` mint / `#4DA3FF` cyan / `#FFB454` gold |
| Text / muted / secondary text | `#F5F7FF` / `#AAB8D6` / `#95A5C6` |
| Border / GO background / false-start text | `#33415E` / `#0B6549` / `#FF9AA8` |
| Spacing | 4, 8, 12, 16, 24, 32 dp |
| Shapes | 24 dp panels, 16 dp compact cards, pill badges |
| Type | System sans for navigation/body; monospace for reaction values and XP rewards |
| Hierarchy | 44 sp branding, 24–30 sp headings, 14–16 sp body, 10–12 sp supporting labels; sizes adapt for large fonts |

Home emphasizes player progression and personal statistics, with numbered mode cards: cyan Quick Draw, mint Classic Rush, and gold Endurance Rush. Achievements use explicit status pills and milestone progress. Statistics retain their lifetime/history scope labels, add compact comparative trend bars alongside numeric values, and provide readable session detail and round cards. All original scoring and progress calculations remain in their existing domain classes.

### Timing, motion, and accessibility

The GO background, title, and timing transition change immediately. No entrance, crossfade, color tween, or result animation sits between a timer signal and the GO display. Timed rounds retain the RR-004E monotonic clock, opaque tickets, lifecycle interruption policy, and immutable rendered input actions.

Home uses a finite 180 ms opacity/translation entrance. Mode cards use a 90 ms compositor-only press response; the click is handled immediately. Compose's platform motion-duration scale controls these finite animations, including disabled system animations. There are no looping decorative animations or animated progression counters.

All screens share safe-drawing insets for system bars and cutouts. Navigation uses at least 48 dp touch targets. Large text stacks Home statistics, uses wrapping screen headers, and switches gameplay to a compact header so the reaction field retains space. Results, achievements, statistics, and round details remain scrollable. Text is never ellipsized to hide progression values. GO uses bright text throughout for contrast on green; false starts and interruptions have distinct labels and supporting instructions. Decorative marks and chart rails carry no unique information; text supplies state, values, units, and navigation meaning. Existing polite record/unlock/save announcements remain.

### Original launcher and splash artwork

The original mint lightning pulse inside a split cyan target ring is authored entirely as vectors, with a matching in-app Canvas mark. No third-party artwork, fonts, images, permissions, network services, or production dependencies were added. The vectors are the implemented identity, not temporary placeholder art.

Adaptive launcher icons work from the existing minimum API 26. API 33 resource variants add a monochrome themed icon. The 108-unit foreground canvas keeps all visible artwork inside a 58.5-unit diameter, within the central 66-unit safe zone. The static native Android 12+ splash uses a 288 dp icon canvas with 156 dp visible artwork; older Android uses a matching centered launch background. There is no splash Activity or artificial hold time.

Two narrowly scoped resource lint annotations document platform-specific choices: monochrome is provided in the API 33 variants, and the native splash uses the platform's 288 dp canvas rather than the generic small-vector recommendation. API/toolchain versions and the existing portrait policy are unchanged. Launcher/store marketing artwork, screenshots for Play, release signing, and physical/OEM acceptance should be reviewed during release preparation.

### Manual visual acceptance checklist

- Check normal and small physical screens, including large text, display scaling, gesture navigation, and three-button navigation. Confirm every mode and screen can be reached without clipped text or controls.
- Complete all three modes and review records, medals, XP, level-up feedback, unlocks, history, and round details. Compare values after recreation and a cold restart.
- Exercise WAITING, GO, false-start retry, and interrupted-session restart. Confirm GO appears immediately and the RR-004E interruption behavior is unchanged.
- Disable system animations and check that Home and mode actions remain usable. Review TalkBack order, button labels, progress announcements, and status text on a physical device.
- Check launcher masks, themed icons, and cold/warm splash transitions on API 26, 31, 33+, and representative OEM launchers. Check smooth scrolling and input response on a midrange physical device.

### RR-005A file map

| Status | Files |
| --- | --- |
| Modified UI | `MainActivity.kt`, `AchievementsScreen.kt`, `StatisticsScreen.kt` in `app/src/main/java/com/maurointeractive/reactionrush/` |
| Added design system | `app/src/main/java/com/maurointeractive/reactionrush/DesignSystem.kt` |
| Modified Android configuration | `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/styles.xml` |
| Added palette and splash theme | `app/src/main/res/values/colors.xml`, `app/src/main/res/values-v31/styles.xml` |
| Added original vector resources | `app/src/main/res/drawable/rr_launch_background.xml`, `rr_launcher_background.xml`, `rr_pulse_mark.xml`, `rr_pulse_mark_monochrome.xml`, `rr_splash_icon.xml` |
| Added launcher variants | `ic_launcher.xml` and `ic_launcher_round.xml` in both `app/src/main/res/mipmap-anydpi/` and `app/src/main/res/mipmap-anydpi-v33/` |
| Modified regression tests | `app/src/androidTest/java/com/maurointeractive/reactionrush/PersonalRecordsGameplayTest.kt` |
| Modified documentation | `README.md` |

Generated screenshots, native drawable previews, test logs, and the compiled-resource audit are local artifacts under the ignored `app/build/qa-rr005a/` directory. Native drawable previews verify rendering of the packaged assets; they are not recordings of operating-system startup animations.

## RR-005B: Audio and haptics

### Approved assets

The runtime package is `ReactionRush_RR005B_Android_Assets_Only.zip`, SHA-256
`A0ED0C54E2E3DB35A66BB066C8ECD93BC6DA91CC242FA53ACB85EFC9D8F9EACD`.
The user approved all 20 exact playback files on November 1, 2026, superseding the
package manifest's older pending-listening markers for Classic, Endurance, and Results.
All 20 source hashes match the manifest, all fully decoded with the local FFmpeg
decoder, and runtime copies retain those bytes unchanged. Five OGG tracks and fifteen
WAV files total 8,362,169 bytes. Original music WAV masters are not runtime resources;
the source ZIP and any separately retained masters remain untouched.

`AudioAssets.kt` catalogs each resource's source path, duration, and SHA-256. The GO WAV
is preserved as a **reserved, inactive resource**: it has no playable event mapping and
is not preloaded. There is no audio-assisted gameplay mode or separate scoring path.

### Playback and ownership

`AudioController` separates testable event policy from `AndroidAudioBackend`'s native
playback. One controller is remembered after saved preferences load, survives ordinary
recomposition, and is disposed with its app composition. Screen owners, actual session
tokens, round ordering, and once-only completion events reject duplicate or stale cues.

Framework `MediaPlayer` uses asynchronous preparation and native looping for the five
local music tracks. `SoundPool` preloads nine active effects and five stingers; the
largest decoded sample is 672,000 bytes, below its per-sample limit. This deliberately
uses the existing Android platform rather than adding Media3 for a small local-only
playlist: no production dependency, background service, permission, or build-tool
change is required. RR-005B physical-device acceptance passed 10/10, as confirmed by
the user during C2 closeout, including all approved assets and C1 music continuity.

Music maps to Menu (including Achievements, Statistics, and Settings), each mode's
soundtrack throughout READY, WAITING, GO, nonfinal results, false starts, and retries,
and Results only after a verified final reaction. Within an uninterrupted game mode,
the same music player and playhead continue without a restart. Preparation feedback
occurs only on READY. Before WAITING begins, effects, queued celebrations, and
preparation haptics are canceled; music and its audio focus remain active when music
is audible. Transient sounds stay suppressed through WAITING and GO. GO makes no
music operation: no pause, fade, volume change, seek, track switch, or focus request.
Independent settings and lifecycle/focus events retain their existing behavior.
The monotonic GO timestamp and reaction calculation are unchanged; response sounds
run only after the existing reaction transaction. The existing 35 ms GO vibration
remains optional under Haptics and runs after timestamp capture; GO has no sound effect.

At most four short streams play simultaneously. Celebrations use one exclusive,
serialized lead-effect/stinger pair with this precedence: level-up, session/level
milestone achievement, other achievement, personal record, medal, ordinary completion.
Higher-priority simultaneous celebrations supersede lower ones. Music ducks during
the celebration. Unloaded effects are dropped, never deferred into a later game phase.

Foreground/window-focus loss stops playback and haptics and invalidates queued events.
Audio-focus loss or headphone-disconnection also stops sound. Regaining focus can
restore eligible music, never old effects; a lifecycle-interrupted session stays silent
until a deliberate new attempt. Native preparation and delayed stinger callbacks are
generation-checked. Release is terminal and idempotent.

### Persistent controls

Home's **AUDIO & HAPTICS** screen offers independent Music and Sound effects toggles
and volumes, plus Haptics. Defaults are music 45%, effects/celebrations 70%, and haptics
enabled. Controls use the RR-005A design system, accessible toggle rows and labeled
sliders, and a scrollable layout. Device system volume/vibration policy is respected
and never changed by the app.

Five `audio_*` keys share the existing `reaction_rush` preferences file and asynchronous
`CompletionPersistence` writer. Invalid types/non-finite volumes recover to defaults;
finite volumes clamp to 0–100%. Playback updates immediately. Sliders persist when a
gesture finishes; pending changes also flush on settings exit, backgrounding, and
disposal. Switches persist immediately, and unchanged saves do not add checkpoints.
Save failure/retry uses the existing nonblocking persistence feedback. Player-save
migrations and completed-session transactions are unchanged. The existing documented
SharedPreferences crash-durability limitations also apply to these settings.

### Audio validation and phone acceptance

Automated coverage includes event mappings, mute/volume/haptics policy, continuous
gameplay music with timed-phase transient suppression, focus/foreground changes,
stale owners/tokens, repeated events, celebration
precedence, persistence/corrupt values, cleanup, all 20 packaged hashes/native media
preparation, and native SoundPool loading. Existing gameplay/domain tests remain.
Desktop decoding and emulator preparation do not prove audible latency or seamlessness.

- Listen to several complete loops and transitions on speakers and wired/Bluetooth
  headphones. Check volume balance, loop seams, clipping, and celebration priorities.
- Verify each mode's music continues through READY, WAITING, GO, nonfinal results,
  false starts, and retries without restarting. Check that GO causes no audible music
  change or sound effect, and that preparation/celebration sounds stop before WAITING.
- Check music mute and volume independently of effects/haptics, including zero volume,
  rapid retries, queued celebrations, and foreground/audio-focus interruptions.
- Exercise calls, notification shade, screen lock, headphone removal, another media
  app, and repeated recreation. Verify no stale sound or vibration resumes.
- Check all controls with large fonts, TalkBack, system mute/vibration settings, and
  devices without vibration hardware. Verify settings after a cold restart.
- Measure input/frame response and startup/scrolling/battery behavior on a midrange
  phone. RR-005B physical-device acceptance is complete (10/10 PASS, user-confirmed
  at C2 closeout); these checks remain useful for future regressions.
- Retain the package's requested commercial-rights/source evidence separately before
  production release; importing the assets is not a commercial-rights audit.

Audio preparation reference: [MediaPlayer state/resources](https://developer.android.com/media/platform/mediaplayer/state-resources).
Short-sample constraints: [SoundPool](https://developer.android.com/reference/android/media/SoundPool).
Focus policy: [Android audio focus](https://developer.android.com/media/optimize/audio-focus).

## RR-006A: Cyber arcade backgrounds

Home and the three game modes use only the four backgrounds approved for RR-006A
from `ReactionRush_RR006_Graphics_Review_Pack.zip`, SHA-256
`282377378B33250BE9791A50B117A90DC8988AB5BEC8C84560DB31924E7BE273`.
The current assignment's approval supersedes the package's older candidate/review note
for these four named backgrounds. Source artwork and the original ZIP remain intact.

| Android resource in `drawable-nodpi` | Dimensions | Format | Bytes |
| --- | --- | --- | ---: |
| `rr_main_menu_background.webp` | 941 × 1672 | Lossless WebP | 1,822,956 |
| `rr_quick_draw_background.webp` | 1440 × 2560 | Supplied WebP | 299,888 |
| `rr_classic_rush_background.webp` | 1440 × 2560 | Supplied WebP | 259,386 |
| `rr_endurance_rush_background.webp` | 1440 × 2560 | Supplied WebP | 333,050 |

The menu conversion preserves every decoded RGBA pixel of its approved PNG. The
three supplied Android WebPs are copied byte-for-byte. Runtime artwork totals
2,715,280 bytes; PNG masters and review files are not packaged as duplicate resources.
As noted in the source manifest, mode artwork was upscaled from roughly 538 × 954
concept crops; its export dimensions do not imply native detail at that resolution.

`CyberArcadeBackground` draws a fixed, centered, cropped image beneath an 86% navy
scrim and the existing native controls. It stays outside Home's scrolling and entrance
animation, respects the existing safe-drawing insets, and contributes no accessibility
label, focus target, or input action. Existing opaque cards and typography remain.
The scrim provides at least 4.90:1 contrast for the checked text/accent colors even
against a white source pixel.

Each screen's painter is resolved on entry, before a round can begin. The game-mode
resource stays fixed throughout READY, WAITING, GO, results, false starts, and retries.
GO and false starts retain their immediate opaque native signal colors over the
artwork; no asset switch, image loading, animation, coroutine, or crossfade is attached
to a signal transition. Timing, input snapshots, lifecycle, audio, progression,
records, and persistence logic are unchanged.

WebP compression reduces packaged size, not decoded bitmap memory. At ARGB_8888,
the menu is about 6.00 MiB and each mode about 14.06 MiB; all four would total
48.19 MiB if retained together. The UI resolves the current screen's painter without
preloading a global four-image cache. Navigation can temporarily retain older bitmaps
until garbage collection; physical-device memory and frame behavior still need acceptance.

For physical acceptance, inspect Home and all three READY screens on small and tall
devices, then WAITING, GO, false starts, retries, interruption, and session results.
Check artwork crop, text readability, immediate green GO feedback, large fonts,
TalkBack navigation, and stable audio. No background animation or new motion setting
is introduced. RR-006A physical acceptance and promotion remain separate from local
engineering validation.

## Cloud APK build
The included GitHub Actions workflow runs unit tests and Android lint, then builds a debug APK for pull requests and pushes to `main`, or manually from the Actions tab. Download the `ReactionRush-debug-apk` artifact and install `app-debug.apk` on an Android device.

## Local build
Open the project in Android Studio and run it on a device, or build with the Gradle wrapper and JDK 21 (the project's configured Gradle runtime):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The unit tests cover records, XP, levels, achievements, clean streaks, history/statistics, foreground timing, stale tickets, save-worker ordering/failures/retries, overflow, exactly-once completion, migration, orphan recovery, and preference-file persistence.

With an Android emulator or device connected, run gameplay checks with:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

The gameplay tests cover all three modes, existing records/progression/achievements/statistics, lifecycle interruptions, focus loss, stale callbacks, recreation, saved-state confirmation, false starts, recovery, migration, and navigation. Run them on a dedicated emulator: they temporarily clear the app's preferences and restore them after each test, but an interrupted test process cannot run that restoration.
