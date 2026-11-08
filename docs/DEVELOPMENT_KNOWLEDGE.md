# Development knowledge and agent handoff

Recorded **2026-11-07, Asia/Calcutta**. This is the consolidated project record for continuing work without reconstructing the chat. Release reports are historical evidence; current code and new reproductions take precedence when behavior changes.

## 1. Current state

| Item | Recorded value |
| --- | --- |
| Workspace | `D:\WORK\HACK\HACKTHON\carrom-auto` |
| Source project | [BitAim-v4.0-source/BitAim-v4.0-source](BitAim-v4.0-source/BitAim-v4.0-source/) |
| Latest installed release | **v4.6.7 / versionCode 31** (v4.5 / 14 installed earlier the same day) |
| Package / target game | `com.bitaim.carromaim` / `com.miniclip.carrom` |
| Device used | Oppo CPH2781, Android 16/API 36; ADB serial `9DCYBIDMRCOJQSMN` |
| Build configuration | compile/target SDK 34, min SDK 23; gesture dispatch requires API 24+ |
| Release APK | [BitAim-v4.5.1-pro-paths.apk](BitAim-v4.5.1-pro-paths.apk) |
| Organized release copy | [OUTPUTS/apk](OUTPUTS/apk/BitAim-v4.5.1-pro-paths.apk) |
| APK SHA-256 | `FD8992FB124DB91B1F3D32A04B5AC45B0FA716A1EA98BD1EF4D13991DA2DE8BB` (build 14: `D6A7D2F488B6D97E55D41B33DC249BDB3E11473FA1F04DD961B68C13A6311882`) |
| Latest release report | [V4.5_REPORT.md](V4.5_REPORT.md) |

The final v4.4 release build, APK signature verification, TypeScript and regression checks passed. Android lint reported **0 errors / 28 warnings**. Installation returned `Success`; Carrom's PID stayed `10916` before and after that upgrade. This establishes that its process was not restarted, not that every network match condition was tested.

After installation the user reported seeing the floating star with an `All`/`Auto` caption. They were instructed to hold it and choose Black. A successful live colour-menu selection and resulting real shot have **not** subsequently been recorded. Do not upgrade that observation into a claim of complete device validation.

## 2. User requirements and scope history

- Stable guidance that follows the striker without random target or gold-marker changes.
- Correct own-team final targets in singles and doubles, including custom coin skins and covered badges.
- Automatic freestyle recognition, plus a manual colour freeze near the floating star: White, Black or another detected colour, independent of badge visibility.
- Quick one-shot coin selection that releases touch input so the user can drag/shoot normally.
- More than direct shots: cushion routes, coin-to-coin combinations, cluster opening and shots that pot while opening another cluster.
- The user's declared endgame priority: if one own coin and one verified red queen remain, guide the queen before the final own coin.
- Optional automatic positioning/shooting was requested later, explicitly including online matches. It is OFF by default and remains experimental.
- Use fresh user screenshots, work in parallel agents where useful, and organize the resulting evidence.

[GOALS_AND_TEST_PLAN.md](GOALS_AND_TEST_PLAN.md) is the original aim-only specification; later requests expanded it. Its original three-match/full-device checklist is **not fully passed**. The user requested 100% accuracy, but that has never been established and must not be promised.

## 3. Implementation history

| Stage | Main changes | Evidence |
| --- | --- | --- |
| Fixed v4.0 / build 7 | ColorOS coordinate offsets, real pocket/striker detection, false-circle rejection, capture lifecycle, build repairs | [TEST_REPORT.md](TEST_REPORT.md) |
| v4.1 / build 8 | Own-team matching, one-shot PICK, stable shot tracking, cushion/combinations, nominal physics | [V4.1_REPORT.md](V4.1_REPORT.md) |
| v4.1.1 / build 9 | Doubles badge positions; AUTO startup; useful picker errors | [V4.1.1_REPORT.md](V4.1.1_REPORT.md) |
| v4.1.2 work | Freestyle, circular hue, mixed-colour transfers, coin cushion, model score; rolled into later release | [V4.1.2_REPORT.md](V4.1.2_REPORT.md) |
| v4.2 / build 11 | Gold scene lock, target persistence, optional gesture service/controller | [V4.2_REPORT.md](V4.2_REPORT.md) |
| v4.3 / build 12 | White-dot aim alignment, opening guides, lower freestyle legend, full-motion verification, complete physical cache signature | [V4.3_REPORT.md](V4.3_REPORT.md) |
| v4.4 / build 13 | Manual colour freeze, assignment precedence/cache, stale-selection repair, queen rule, dense-disc recovery, pot+spread objectives and bounded speed search | [V4.4_REPORT.md](V4.4_REPORT.md) |
| v4.5.4 / 18 and v4.5.5 / 19 | Pro style, brighter alternatives, off-turn capture throttling, realistic chance estimate and ranking, auto-shot menu toggle and 70% chance gate | [V4.5_REPORT.md](V4.5_REPORT.md) |
| v4.5.2 / 16 and v4.5.3 / 17 | Live-test fixes: plan acceptance by unchanged scene, striped-black and glowing-queen detection, slider knob detection, auto-shot signature, faint alternatives, stricter switching, soft-touch and full-power speeds | [V4.5_REPORT.md](V4.5_REPORT.md) |
| v4.5.1 / build 15 | Opener search skipped when a forgiving pot exists; cascade-pot label | [V4.5_REPORT.md](V4.5_REPORT.md) |
| v4.5 / build 14 | Exhaustive screenshot bench, aim-tolerance measurement and ranking, wider gold search, positional path-opening shots, planner moved off the UI thread, draw guard | [V4.5_REPORT.md](V4.5_REPORT.md) |

The original v4.0 APK had a different signing identity. An earlier replacement required uninstalling BitAim only. Subsequent recorded upgrades used `install -r` with the existing local signing key. Preserve that identity; an incompatible signature is not a reason to uninstall the game.

## 4. Source map

All Java paths below are relative to `BitAim-v4.0-source/BitAim-v4.0-source/android/app/src/main/java/com/bitaim/carromaim/`.

| File | Responsibility |
| --- | --- |
| `cv/BoardDetector.java` | Board/pocket/disc/striker detection, dense pass, frame IDs, manual eligibility and ownership session |
| `cv/TeamMatcher.java` | Badge/legend profiles, skin ownership, appearance keys and plain-queen recognition |
| `cv/Coin.java`, `cv/GameState.java` | Geometry, eligibility, colour/queen identity, mode and aim-guide state |
| `cv/TargetColourLock.java` | Explicit colour keys, hue matching and labels |
| `cv/QueenRule.java` | User-requested last-own-coin queen priority |
| `cv/ShotPlanner.java` | Direct contact geometry and shared Shot metadata |
| `cv/RoutePlanner.java` | Candidate routes, bounded verification, ranking, refresh and baseline search |
| `cv/ClusterPlanner.java` | Reachable first-contact cluster-opening candidates |
| `cv/OpeningPlanner.java` | Path-opening (positional) shots scored by pocket paths after the settled model; v4.5 |
| `cv/PhysicsVerifier.java` | Settled event simulation, perturbations, speed selection, opening metrics and cache signature |
| `cv/ShotTracker.java`, `cv/GoldSceneLock.java` | Target intent, stale-selection handling, route cycling and stable gold searches |
| `cv/AimGuideDetector.java`, `cv/AimAlignment.java` | Game-native white-dot guide and three-capture alignment confirmation |
| `cv/AutoPlayController.java` | Opt-in gesture planning and state gates; no Android input itself |
| `capture/ScreenCaptureService.java` | Capture worker, detector settings, preference restoration and ownership-mode transitions |
| `overlay/AimOverlayView.java` | Rendering, one-shot PICK, alignment feedback, model/queen/cluster labels and background gold search |
| `overlay/FloatingOverlayService.java` | Floating star, colour menu/caption, window touch modes and gesture handoff |
| `overlay/OverlayModule.java` | React Native bridge, permissions, settings and service control |
| `overlay/GameGestureService.java` | Optional Android AccessibilityService gesture dispatch |

The main React Native screen is [App.tsx](BitAim-v4.0-source/BitAim-v4.0-source/App.tsx). Manifest and gesture configuration are under the source project's `android/app/src/main/`.

## 5. Detection and target ownership

### Board and disc detection

The detector processes a 720-pixel-wide frame, finds the light-wood playing surface and searches actual dark corner pockets. Coordinates are returned in source-image pixels. Small discs and the larger bottom-baseline striker are detected separately. Wood, painted decorations, power-up graphics, hole interiors and duplicate circles are filtered. A real detected bottom striker gates local-turn guidance; opponent-side frames must not be enabled merely to manufacture a replay route.

A second central-region Hough pass runs when at least eight raw circles occur there. It uses `dp=1`, narrower maximum radius `0.028 × boardWidth`, and a threshold capped at 16. Existing filtering/deduplication is reused. On the fresh dense opening it recovered the two missed whites and produced **9 white + 9 black + 1 queen**, instead of 17 discs.

Board bounds, stationary disc centres and radii are stabilized. Global atomic capture IDs remain increasing across detector/service recreation.

### Automatic assignment and freestyle

Badge profiles support singles positions and shared inner-avatar doubles positions. Freestyle is recognized from a validated red/white/black points legend, including upper/lower placement and either black/white order. Yellow patterned skins are retained as discs rather than discarded as decoration. Circular hue averaging prevents red values around HSV 0/180 from averaging into wood-like hues.

Unknown ownership never automatically means every coin is legal. A visible assigned team overrides leftover All mode, and its immutable profile is cached during brief badge occlusion. Confirmed assignment clears the stored All override and disarms automation. Board loss or positive freestyle recognition clears the assignment session. A fresh explicit All fallback is available when no assignment is established.

### Manual colour freeze

Hold the star for approximately 450 ms, release without dragging, then select a colour. The caption under the star shows the choice. The menu also contains Auto team, All/Freestyle and Next route. Keys are `AUTO=0`, light/White `1`, dark/Black `2`, or `100 + hue`; chromatic matching uses circular hue distance up to 10.

Manual colour choice overrides uncertain ownership and persists through capture restarts. Changing it clears previous target guidance, disarms automatic shots and rejects queued captures with an old key. The lock limits final targets; AUTO may switch to an easier eligible coin after repositioning. Selecting one specific coin through PICK is a separate individual target intent.

An adjacent green overlay initially turned one black disc's appearance key green. The appearance classifier now gives a strongly neutral/dark annulus priority without changing the ownership classifier. The dense fixture's Black lock now includes all nine black discs.

## 6. Geometry, simulation and ranking

### Contact and route families

For coin centre `C`, pocket `P` and radii `rCoin`, `rStriker`, the direct ghost centre is:

```text
d = unit(P - C)
G = C - d × (rCoin + rStriker)
```

The cyan path tracks the **striker centre**. It ends one combined radius away from the coin centre; the physical edges touch. A centre line not crossing the coin centre is not by itself evidence of a miss.

Supported families are direct pots, striker-cushion pots, target-coin cushion pots, one/two-transfer combinations, striker-cushion-plus-combination routes, and manual cluster opening. Known opponent-coloured intermediates can be used while the final target remains eligible. Other discs remain swept-path obstacles. Nominal opponent pots and striker scratches reject a candidate.

The search is bounded: it is not exhaustive over every possible angle, contact sequence, rail sequence or power. `No verified route` means the current search did not validate one, not that a human shot is impossible.

### Physics assumptions and important repairs

The model uses circles, continuous collision-event times, striker mass 1.2 versus disc mass 1, restitution 0.96, nominal wall restitution 0.90 and exponential velocity retention 0.70 per second. It does not model calibrated spin or the real game's exact friction/pocket engine.

A six-second cutoff previously accepted a later scratch at **9.8667 seconds**. The solver now searches the entire remaining displacement under its drag model, rejecting event-budget exhaustion. Physical-state signatures include board geometry, radii, positions, eligibility and mode/rule fields; failed validation clears stale flags. A 3,000-world same-model audit found no late-continuation failures among 26,077 accepted routes after that repair. This is model-consistency evidence, not real-game accuracy.

### Speed and uncertainty search

Nominal speeds are `1.1`, `1.6`, `2.2 × boardWidth/second`. Complex scenes test all three, retain two speed candidates and run nine perturbation trials each: at most **21 model solves per candidate route**. Isolated scenes retain a faster path. Trials vary speed, aim and wall response. Nominal metrics are restored after stress testing.

The displayed model percentage counts successful sampled trials. **9/9 or 100% is not a measured pot probability.** It omits uncertainties such as missed discs and uncalibrated game physics.

### Pot and open cluster

A productive pot requires the intended pot plus at least two non-target discs moving more than their radii and at least one initially close surviving pair opening. Robust compound candidates need at least 7/9 compound-objective successes; compound robustness precedes score in ranking. Simpler options remain available.

A separate kind-4 cluster guide requires first contact and at least three moved discs in the nominal model. It shows the approach/contact marker and manual-power wording, with no invented pocket path. It is not automatically executed. The speed-beam regression demonstrates a higher-speed compound outcome where a lower valid speed merely pots, but those model units are not calibrated in-game power percentages.

### Aim tolerance and ranking (v4.5)

`tools/cv-test/bench.ps1` (class `AlgorithmBench`) replays every screenshot through the production detector, enumerates **every** geometric candidate (not the phone's per-kind cap), validates each with the production stress set, then measures two extra things per validated route: the **aim tolerance window** (largest symmetric aim error in 0.25-degree steps, up to 8 degrees, that still pots in the nominal model) and the **speed window** (how many of 13 launch-speed factors from 0.6 to 2.0 still pot). It also sweeps all 41 baseline positions. Output: `test-results/algorithm-bench/{before,after}/summary.md`, `routes.csv`, `positions.csv` and annotated PNGs (cyan = production choice, magenta = most tolerant alternative).

The v4.4 ranking (productive pot first, then complexity, then stress passes) was fragile: routes passing 9/9 stress samples still failed at 0.25 degrees of aim error while alternatives on the same frame tolerated 3 to 8 degrees, and the gold search validated only four spots by raw score. v4.5 therefore:

- computes `Shot.aimMarginDegrees` inside `PhysicsVerifier.validate` by bracketing ±0.5, ±1, ±2, ±4 degrees at the selected speed (at most 8 extra solves, values 0 / 0.5 / 1 / 2 / 4);
- ranks with `RoutePlanner.compare`: productive pots keep the top tier only when their tolerance is at least 1 degree; otherwise routes are ordered by **effective tolerance** (tolerance weighted by family, direct 1.0, combination 0.75, striker cushion 0.6, coin cushion 0.5, cushion plus combination 0.4, in half-degree buckets), then stress passes, then complexity, then score. Manual cluster guides stay last;
- `Shot.quality()` = score + 30 × min(4, tolerance) is used for gold-versus-current comparison and the tracker's switch hysteresis;
- `goldX` keeps up to three candidates per baseline sample, validates up to 12 of them and ranks with the same comparator;
- the overlay prints the tolerance as EASY (≥2°), OK (≥1°), TIGHT (≥0.5°) or RISKY (<0.5°) next to the alignment label.

The tolerance is still a nominal-model number: it does not include detection noise, real pocket geometry or game spin. Rail families are down-weighted precisely because the rail model is uncalibrated.

### Positional play: path-opening shots (v4.5)

The user's main complaint was that clustered or blocked boards produced `No verified route` instead of the preparation shot a strong player would play. `OpeningPlanner` now adds kind-5 routes: for every known disc the striker can reach cleanly it settles five cut offsets (0, ±0.45, ±0.8 of the contact radius) at three launch speeds in the nominal model (`PhysicsVerifier.run`, which returns the full settled outcome), rejects scratches, opponent pots, wrong first contacts and unsettled runs, and scores the settled board by **pocket paths**: the number of (own coin, pocket) pairs with a clear direct path from any of five baseline striker positions. A candidate counts only if it adds at least one path or pots an own coin. Each coin's best candidate is stress-tested at ±1 degree and bracketed for tolerance (±0.5, ±1, ±2 degrees).

Ranking tiers in `RoutePlanner.compare`: 0 = pot tolerating at least 1 degree effective, 1 = pot tolerating 0.5 degree, 2 = path opener (most new paths first), 3 = risky pot (under 0.5 degree effective), 4 = manual cluster guide. Among pots of equal tolerance and stress, the route that leaves more pocket paths afterwards (`Shot.pathsAfter`, a one-ply look-ahead computed in `PhysicsVerifier.validate`) ranks first. The gold sweep also tries quick openers every fourth sample when a position has no pot or cluster candidate. Automatic gestures never fire for kind 4 or 5.

Overlay wording: `OPEN PATHS • <alignment> • OPENER ±N°`, then `Open paths +N (model) • before → after pocket paths • M coins move`, then `Positional shot: medium power, then pot next turn`. The predicted travel of the first coin is drawn in orange with a dashed end ring; no coin-to-pocket line is drawn. On the previously blocked gallery frames (15:28:34, 15:36:27, 15:45:38, 16:06:56) and `wrong-team-confirmed.png` the planner now returns an opener instead of nothing; `15:54:23` and `phone-capture-134613` still need the ★ reposition.

### Alignment and stability

Only the game's small **white** guide dots are used for observed aim direction. At least six dots are required. Forward and backward guides are tested. The suggested striker leg turns green after agreement within 1.5 degrees across three increasing capture IDs. Same-frame route changes, redraws and replayed IDs cannot retain or create false confirmation.

`ShotTracker` preserves valid intent through small noise, but does not display a stale eligible clone after the current coin becomes ineligible. This fixed the black ring shown with `Opening cluster — no verified pot yet`. Colour freeze does not permanently freeze AUTO on a poor individual coin. `GoldSceneLock` compares retained board/disc/pocket geometry rather than restarting on four-pixel rounding boundaries.

## 7. Queen priority

`isQueen` is an explicit detection, not equivalent to `COLOR_RED`. Plain queen recognition requires predominantly red inner fill. Measured inner coverage was 98.2% for the queen versus 25.9–32.7% for five red/black opponent skins.

In known assigned play, exactly one eligible own ordinary disc plus exactly one verified queen activates the user's requested priority: queen becomes the sole final target, the last own disc remains an obstacle/transfer, and the old last-own target lock is released. Freestyle, unknown ownership and ambiguous multiple queens do not force this rule.

`QUEEN FIRST` explains the target change. Automatic gestures are gated during this state. Queen cover and variant-specific rule sequences remain manual and are not fully implemented.

## 8. UX and optional automatic play

- Short tap cycles AUTO/PICK/OFF; PICK consumes one gesture and returns to pass-through after release, cancellation or accidental drag.
- Hold the star to select/freeze a colour or choose Next route. Auto/All/colour captions identify the mode.
- Cyan is the planned striker approach; green striker guidance means observed alignment. Orange identifies transfer or manual cluster contact. Final coin-path green reflects model robustness, not guaranteed success.
- v4.5 moves all route planning off the UI thread: `AimOverlayView` queues the newest frame for a single planner thread (`BitAim-Plan`, frame coalescing through an `AtomicReference`), the gold search runs on `BitAim-Gold`, and results are applied only if they belong to the frame currently displayed. While a new frame is being planned the previous lines stay visible if the striker has not moved; otherwise a short "Planning…" hint appears after 400 ms. `ShotTracker` is accessed under its own monitor (`clear`/`lock` on the UI thread, `update`/`next` on the planner thread) and running passes are interrupted before a reset. `onDraw` is wrapped in a catch-all so a planner or render fault logs instead of closing the overlay. `cycleRoute` is now asynchronous and reports its label through a callback.
- Gold is a suggested baseline position. Hidden/obscured board, unknown assignment, unavailable route, cluster opening and queen priority have separate messages.

Optional automatic shots require both Android's gesture-service permission and explicit arming. They start OFF. The controller requires fresh stable captures, current physical signature, local turn and at least 8/9 stress passes, verifies slider repositioning and restricts input to the game window. Mode changes/menu opening disarm it. Queen and spread-only cluster shots remain manual.

The slider mapping, pull distance and actual power are experimental. Native/JVM/controller tests are not proof of live gesture accuracy. Android rejects direct ADB starts of the non-exported overlay service; start it from BitAim. An APK upgrade stops overlay/capture even if Carrom's process continues.

## 9. Screenshot evidence and organization

| Evidence | Main lesson |
| --- | --- |
| [mixed team fixtures](test-results/team-fixtures/) | Blue versus teal, striped own coins and opponent blockers |
| [current-2v2.png](test-results/current-2v2.png) | Shared badge positions; two own whites and seven black obstacles |
| [freestyle frame](test-results/phone-capture-20261107-134613.png) | Upper legend, red/white/black selection and plain queen |
| [live-colour failure](test-results/live-colour-20261107-151207.png) | Lower/reversed legend; 18 freestyle discs recognized |
| [wrong-team-confirmed.png](test-results/wrong-team-confirmed.png) | All-mode carryover can permit red/black opponents; AUTO rejects them |
| [yellow-team-1.png](test-results/yellow-team-1.png), [yellow-team-2.png](test-results/yellow-team-2.png) | Yellow skins and badge-covering emote |
| [no-clear-shot frame](test-results/stability-feedback.png) | Mixed-colour transfer and route-search improvements |
| [win/occluded board](test-results/colour-failure-20261107-151021.png) | No board is expected beneath the victory banner; capture advice must distinguish obscuration |
| [near-rail frame](test-results/easy-shot-feedback.png) | Remaining no-verified-route case; do not claim solved |

Eighteen user gallery screenshots from **2026-11-07** (the 16:53:45 frame was copied, not deleted, after the v4.5 install) are archived in [test-results/gallery-2026-11-07](test-results/gallery-2026-11-07/). See [manifest.json](test-results/gallery-2026-11-07/manifest.json) for exact names, SHA-256 hashes and copy/deletion status. All 17 were verified locally before deleting their phone MediaStore originals, under that day's explicit user request. Other dates/photos were untouched. This record is not authorization for future deletions.

The dense `15:45:38` image exposed the stale black highlight and two missed whites. The `16:06:21`/`16:06:56` pair is 35 seconds apart: a starting pose and later state do not prove one shot, exact disc identity through contacts or power. `16:10` animation frames contain obscured discs/graphic circles; the opponent-turn guard still produces no local route.

[latest-six.txt](test-results/gallery-2026-11-07/latest-six.txt) currently lists 16:10:27, 16:10:01, 16:06:56, 16:06:21, 16:01:07 and 15:58:08. Prefer newly supplied references for new failures; retain historical regression fixtures so fixed bugs do not return.

[OUTPUTS/README.md](OUTPUTS/README.md) indexes organized screenshot copies, latest six, simulation annotations, research notes, reports and APK. [inventory.json](OUTPUTS/inventory.json) records provenance and hashes. These are copies; canonical fixtures were not moved. Annotated replay images may contain drawings baked into the original screenshots and must not be presented as clean live UI.

Older interactive [contact simulation](test-results/contact-simulation.html) and [shot replay](test-results/shot-replay.html) predate the latest route set. They are local diagnostic models, not v4.4/game-engine validation. No neural-network training or automatic learning of unseen shot outcomes was performed.

## 10. Research used

- [Running the Table / PickPocket, AAAI 2006](https://fileadmin.cs.lth.se/ai/Proceedings/aaai06/09/AAAI06-156.pdf): relevant shot-class generation, speed-dependent follow-up states and uncertain-outcome evaluation informed bounded speed exploration. Full Monte Carlo game-tree search is not implemented here.
- [CarromTutor, IIT Bombay](https://www.cse.iitb.ac.in/~sri/papers/carromTutor-t4e2014.pdf): deflection, cluster/position strategies and expert-based simulator calibration informed the separation of pot, spread and combined objectives.
- [Research/reference note](test-results/agent-simulation/RESEARCH_AND_1606_REFERENCES.md) and [speed-beam report](test-results/agent-simulation/SPEED_BEAM_REPORT.md) document adaptations and limitations. A journal full-text fetch was unavailable; no inaccessible sections were treated as evidence.

## 11. Verification and parallel agents

Three agents used separate output/class directories for simulation, independent tests and Android/UX review. They found and helped repair late scratches, incomplete cache signatures, stale green alignment, five-dot acceptance, replayed-frame confirmation, API-24-only sorting, unknown-team automation resets, stale target rings, ownership carryover and missed dense discs.

Recorded targeted results: manual colour 13/13, negative guide 24/24, queen/ownership 19/19. Source-to-APK build, lint, signature and TypeScript checks passed. See [v4.4-tests.txt](test-results/v4.4-tests.txt), [build log](test-results/v4.4-build.log), [independent results](test-results/agent-tests/full-results.txt) and [simulation reports](test-results/agent-simulation/).

| Suite | Scope |
| --- | --- |
| `Regression`, `PlannerRegression`, `TeamRegression` | Real screenshots, geometry, all team/mode fixtures |
| `RouteRegression`, `PhysicsAuditRegression` | Bank/combination contacts, late scratches, physical cache invalidation |
| `QueenRuleRegression`, `SelectionRegression` | Queen priority and stale/manual selection transitions |
| `PotSpreadRegression`, `SpeedBeamRegression` | Productive pots, nominal/stress metadata and bounded speed alternatives |
| `FocusRegression` | Cluster opening, forward/backward guide, frame confirmation and lower legend |
| `AutoPlayRegression`, `PickerRegression` | Opt-in gates, gestures as planned actions, touch release and view handlers |
| Agent-specific harnesses | Manual colour completeness, ownership sessions, guide negatives and fresh gallery replays |
| `ShotLogger` / `ShotLogCalibrator` | In-app real-shot log (`shots.jsonl` under the app files folder) and desktop fit of launch speed, rail restitution, pocket capture and pull-to-power |
| `RealityGapBench` | Monte Carlo of the top routes under aim error, power, pocket, drag and rail variation; compares realistic success with the shown percentage |
| `AlgorithmBench` (`tools/cv-test/bench.ps1`) | Exhaustive candidate validation, aim/speed tolerance windows and 41-position baseline sweep on every screenshot |

The desktop harness compiles the actual Java detector/planner with OpenCV and Android JVM stubs. It does not run the real game engine, prove Android WindowManager delivery, or certify match wins.

## 12. Build, test and install procedure

Follow [INSTRUCTION.md](INSTRUCTION.md). Keep tools, dependencies, caches and temporary files on **D:**. Existing project tools: JDK17, SDK34, Gradle wrapper 8.0.1/AGP7.4.2; use the checked-in wrapper rather than changing toolchain incidentally.

From the workspace root:

```powershell
$env:JAVA_HOME='D:\Tools\jdk-17'
$env:ANDROID_HOME='D:\Tools\Android'
$env:ANDROID_SDK_ROOT='D:\Tools\Android'
$env:GRADLE_USER_HOME='D:\Tools\gradle-home'
$env:npm_config_cache='D:\Tools\npm-cache'
$env:TEMP='D:\Tools\tmp'
$env:TMP='D:\Tools\tmp'

# Historical script name; runs current suites.
& './BitAim-v4.0-source/BitAim-v4.0-source/tools/cv-test/verify-v4.1.ps1'

Push-Location './BitAim-v4.0-source/BitAim-v4.0-source'
npx tsc --noEmit
Push-Location android
.\gradlew.bat assembleRelease lintRelease --no-daemon --console=plain --max-workers=2
Pop-Location
Pop-Location
```

`run.ps1`, `verify-v4.1.ps1` and `bench.ps1` compile into the same `tools/cv-test/classes` directory: chain them in one job, never run two at once. The picker view test under `test-results/view-test` needs the `android.os.SystemClock` stub added in v4.5.

Use the **release** APK containing the JS bundle. Output is `android/app/build/outputs/apk/release/app-release.apk` inside the source project. Preserve the current signing key, verify the APK signature/version/hash, and record the immutable delivered artifact.

For an authorized device upgrade:

```powershell
& 'D:\Tools\Android\platform-tools\adb.exe' devices -l
& 'D:\Tools\Android\platform-tools\adb.exe' -s 9DCYBIDMRCOJQSMN install -r 'D:\WORK\HACK\HACKTHON\carrom-auto\BitAim-v4.4-colour-lock.apk'
& 'D:\Tools\Android\platform-tools\adb.exe' -s 9DCYBIDMRCOJQSMN shell dumpsys package com.bitaim.carromaim
```

Confirm the connected device/desired artifact before using the recorded serial/path. Restart overlay/capture inside BitAim after upgrade and choose Entire screen. Do not infer permission to control a live game from a screenshot request.

The [gallery collection script](tools/Collect-GalleryScreenshots.ps1) supports copy-only operation and a separate explicit `-DeleteVerifiedFromPhone` switch. Its day/serial are session-specific. Use deletion only within newly confirmed user scope, after hash verification, and retain the manifest. Do not delete the canonical D: regression/archive copies.

## 13. Continuation checklist and unresolved work

1. Read this file, the latest release report and current user scope. Distinguish installed behavior from unshipped source changes.
2. Reproduce a reported failure from fresh evidence; record game mode, visible own badge, manual colour key, selected/first/final disc and whether a route actually exists.
3. Preserve raw screenshots. Label any manually annotated actor/striker reconstruction; never bypass the production opponent-turn guard to claim a real local recommendation.
4. Use parallel agents for independent simulation, tests and Android/UX checks, with non-overlapping production ownership and isolated harness classes.
5. Run relevant regressions; verify same-frame route changes, badge occlusion, colour freeze, queen transitions and stale captures as applicable.
6. Record build/lint/signature/install evidence, screenshots used, unresolved cases and the difference between model checks and real outcomes. Update this document and README when the handoff changes.

Outstanding priorities:

- Practice Mode for safe live tests: game Settings → Practice Mode → Carrom (pass-and-play, so BitAim only draws on the bottom turn). Re-measure CPU after the 4.5.2 planner fix and confirm faint alternatives, slider detection and one automatic shot there.
- Verify the anchored colour menu on the actual phone, successful White/Black locking, and touch pass-through after selection. The star/caption is reported visible; menu selection is not yet confirmed.
- Measure live rendering/solver latency and sustained match performance, especially dense scenes. Since v4.5 no route work runs in drawing; confirm on the phone that lines follow the striker within about 0.3 s in dense scenes.
- Calibrate actual game power, friction, cushion behavior and spin from time-resolved shots. Still screenshots alone are insufficient.
- Reproduce the unresolved near-rail cases, without inventing unsafe routes to suppress a message. `15:54:23` and `phone-capture-134613` still have no route or opener from the current spot (★ only).
- Validate openers on the phone: an opener is a model prediction of where coins settle; real power control decides whether the paths actually open. Collect before/after screenshots of a few openers and compare `pathsAfter` with the real board.
- Improve resistance to animated strikers, trails, occlusions and overlays; they can hide discs or create ineligible graphic detections.
- Broaden contact/cushion search with explicit compute bounds where evidence justifies it. Arbitrary multi-cushion/spin solutions and full multi-turn planning are not implemented.
- Validate full queen-cover/rule variants and automatic gestures separately. Do not equate one queen-priority condition with complete rule knowledge.
- Complete the remaining device/three-match checklist. Maintain the distinction between build success, simulation consistency, UI verification and measured shot accuracy.
