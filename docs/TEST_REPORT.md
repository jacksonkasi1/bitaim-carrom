# BitAim v4.0 corrected build — 7 November 2026

The corrected APK was subsequently **installed and opened at the user's request** (version 4.0, code 7). Android rejected an in-place update because the original signing key differed, so only the old BitAim package was uninstalled and replaced. BitAim permissions/settings were reset. Carrom Pool was not uninstalled or cleared. Live overlay testing on the corrected APK remains pending while the user tests it.

## Defects found and corrected

1. **Overlay origin:** the original ColorOS window began at screen y=120 while detection returned full-screen coordinates. Explicit inset/cutout configuration now requests a full-display overlay. This needs final phone verification.
2. **Pocket centres:** circle detection sometimes fell back to a board corner. Dark-hole contours now locate actual pocket centres; all four measured fixture centres pass a 12-pixel tolerance.
3. **Collision radius:** the striker's animated halo was being included in the physical radius. The planner now uses the puck radius.
4. **Cluster detection:** large-circle candidates suppressed touching coins. Coins and the baseline striker now use separate size ranges and search regions. All 19 real opening coins are detected.
5. **False objects:** baseline circles, brown board artwork, and smaller blue coins could become coins or a striker. Wood filtering and striker-sized perimeter checks reject these cases.
6. **Rail coins:** the shaded wood mask excluded pieces touching a rail. A limited rail allowance keeps those pieces as collision obstacles.
7. **Stale guidance:** loss of the board or stopping capture now clears detected state. Opponent-turn frames clear manual selections too.
8. **Manual AIM:** removed the imaginary board/striker fallback and the separate nearest-pocket logic. Manual selections now use the same blocked-path and cut-angle validation as AUTO.
9. **Capture lifecycle:** bitmap cleanup runs even on detection errors; native Mats are released on shutdown; failed foreground-service startup stops cleanly; recreated permission activities do not launch duplicate consent requests.
10. **Controls:** stopping an inactive overlay no longer starts it, the floating button is clamped to visible bounds, detection slider limits match native limits, and the displayed version footer is corrected.
11. **Build setup:** restored the missing Windows Gradle wrapper, corrected the local SDK path, kept dependencies/caches/temp data on D:, and configured a project-local debug signing key. Fixed the inherited TypeScript type configuration.

## Local verification

- Artifact: `BitAim-v4.0-fixed.apk` (version name 4.0, version code 7), original APK preserved.
- SHA-256: `FA9B792B7F489B7937DB523A52090ACC311287269D931BB64697F57D34EC2174`.
- APK signature verification passed (v1/v2); standalone JS bundle and both ARM OpenCV libraries are present.
- `assembleRelease`: passed; final result recorded in `test-results/final-build.log`.
- `lintRelease`: no errors; remaining warnings include old target/dependency versions, accessibility, and unused resources.
- `npx tsc --noEmit`: passed.
- Screenshot and geometry regressions: `test-results/regression-results.txt`.
- Repeat tests: run `BitAim-v4.0-source/BitAim-v4.0-source/tools/cv-test/verify.ps1` from PowerShell.

| Fixture | Expected observation | Result |
|---|---|---|
| Practice opening | Actual bottom striker, all 19 clustered coins, four real holes | Passed |
| Practice opening | No invented clear shot through the packed cluster | Passed |
| Opponent practice turn | No detected bottom striker; no AUTO guidance | Passed |
| Blue coin on bottom baseline | Coin must not become the striker | Passed |
| Your turn, right-hand striker | Striker near (818,1573), 14 discs after score 1+3 | Passed; detected (815,1568), 14 discs |
| Your aiming gesture | Striker near (461,1573), 11 discs after score 4+3 | Passed; detected (461,1571), 11 discs |
| Latest aiming gesture | Striker near (417,1573), 10 discs after score 5+3 | Passed; detected (417,1571), 10 discs |
| Rail-edge pieces | Visible rail coin remains an obstacle | Passed |
| Settings / obscured board | No board or guidance | Passed |
| Shot geometry | Correct contact radius, blocked striker/coin paths rejected, backwards cut rejected, gold position produces a valid shot | Passed |

Desktop detector timings on the final regression run were about 29–41 ms per board frame. These are PC measurements, not phone latency measurements.

The original test-plan statement that the opening must suggest a gold position was too strong: after detecting all 19 coins, this opening fixture has no clear straight pot from any sampled baseline position. The app correctly avoids inventing one. Gold-position geometry is tested separately on a layout where a clear shot exists.

## Original APK observations before phone testing was deferred

The connected device was Oppo CPH2781, Android 16 / API 36. The supplied APK installed and opened with the expected v4.0 heading. Overlay permission and full-screen recording consent were available. Both foreground services ran. The floating button appeared and could be dragged, AUTO passed striker movement and a test break through to practice mode, and OFF removed the lines. No BitAim crash appeared in the captured crash buffer during this short session. The 120-pixel line offset was confirmed from screenshots and window state.

These observations describe the **original supplied APK**, not validation of the corrected APK.

## Test-plan items still pending on the corrected APK

| Items | Remaining work |
|---|---|
| A1–A3 | Fresh installation and launch completed; in-place upgrade failed due to different signing key. Heading and notification permission flow still need confirmation on the corrected installation. |
| B1–B3 | Permission flow and capture startup on the corrected APK; restricted-settings branch if applicable |
| C1–C6 | Live drag/cycle/manual selection/touch pass-through/notification Stop and switch synchronization |
| D1–D9 | Live line alignment, shot guidance, tracking latency, gold marker, opponent suppression, stability and whole-match smoothness |
| E1–E3 | Rotation/app switching, capture restart and three completed matches |
| Android coverage | Android 14 and older supported devices were not available for live verification |

The full checklist is **not yet complete**. No claim is made that three matches or all Android versions have passed. Screenshots validate detection and geometry; they cannot prove live window alignment, touch behaviour, capture restarts, game performance, or successful pots.

BitAim remains an aim helper as defined in the supplied goal: it does not move the striker, choose power, or shoot automatically. Current detection remains tuned to the tested Carrom Pool themes; hidden pieces and other themes can require additional work.
