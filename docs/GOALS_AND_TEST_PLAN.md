# BitAim v4.0: Goals and Test Plan

This file is for whoever tests or keeps working on BitAim, whether that's a person or an AI agent.
**Loop:** test → if something fails, fix it → rebuild → test again → repeat until every item in section 3 passes.

---

## 1. What the app must do (the goal)

BitAim is a carrom **aim helper**. It draws lines on top of the Carrom Pool game. It **never** touches or controls the game itself.

The user wants it to be **simple and not distracting**:

1. **Automatic best shot (AUTO mode)**
   - When it's the user's turn (their striker is on the **bottom baseline**), the app shows by itself:
     - 🔵 a **cyan line** from the striker to the exact **hit spot**, plus a dashed circle showing where the striker will be at impact
     - 🟢 a **green ring** around the chosen coin and a **green dashed line** from that coin to the **pocket**
   - Nothing else: no dots on every coin and no messy bounce lines.
2. **Follows the striker:** when the user slides the striker along the baseline in the game, the lines update live, within about 0.3 s. If the user moves to a bad spot, the line changes or disappears.
3. **⭐ Gold spot:** a gold ring with a ★ on the baseline shows the **best place for the striker**. It's shown only when it's clearly better than the current spot.
4. **Not distracting:**
   - Lines are shown **only on the user's turn**. When the opponent's striker is at the top, nothing is drawn.
   - In AUTO mode **every touch goes to the game**, so the game must never "freeze".
5. **Manual option (AIM mode):** tap the coin you want. The cyan and green lines appear for that coin. In this mode touches go to the overlay.
6. **Floating button** cycles through: **OFF → ⚡ AUTO → 🎯 AIM → OFF**, with a short toast message for each mode.
7. **The app must never crash or close.** Main target is Android 14+ and Oppo / ColorOS. Minimum is Android 6.

Not a goal: making the striker move or shoot automatically. That would need Accessibility control and could get game accounts banned.

---

## 2. How it works (where to look in the code)

| Part | File |
|---|---|
| Main settings screen (React Native) | `App.tsx` |
| Bridge between the screen and the Java code | `android/app/src/main/java/com/bitaim/carromaim/overlay/OverlayModule.java` |
| Floating button + overlay window + modes | `overlay/FloatingOverlayService.java` |
| Drawing the lines, AUTO and AIM logic | `overlay/AimOverlayView.java` (`drawAuto`, `applySnap`) |
| Screen capture (MediaProjection, 10 FPS) | `capture/ScreenCaptureService.java` |
| Board / pocket / coin / striker detection (OpenCV) | `cv/BoardDetector.java` |
| Best shot + gold spot geometry | `cv/ShotPlanner.java` |

How detection works in `BoardDetector` (tuned on real Carrom Pool screenshots):
- **Board:** the largest **light-wood** area, HSV H 6–24 / S 55–165 / V ≥ 170, and roughly square. The game's background is red-brown, so the board is never found by looking for red-brown.
- **Pockets:** the 4 dark circles (V < 80, S < 70) near the board corners. If they aren't found, the corners are used.
- **Coins:** HoughCircles inside the board. Rejected: wood colour, orange decoration dots (S ≥ 235), and anything with yellow from the ⭐ power-up badge. Any other colour counts as a coin.
- **Striker:** the largest circle on the bottom baseline (y ≈ 84.5 % of the board). It can be any colour; the striker in this game is dark with a blue ring. `GameState.strikerDetected` is true only when a real striker is found.

How the shot is chosen in `ShotPlanner`:
- For each coin C and each pocket P, the hit spot (ghost) is `G = C − unit(P−C)·(rCoin + rStriker)`.
- A shot is valid only if:
  - the striker path S→G is clear of other coins
  - the coin path C→P is clear of other coins
  - the cut angle is under about 72°
  - G is inside the board
- **Score** = cos²(cut angle)·100 − path length penalty.
- **Gold spot:** the planner tries 41 striker positions along the baseline and picks the best score.

---

## 3. Test checklist (every item must pass)

### A. Install and start
- [ ] A1. `BitAim-v4.0.apk` installs over older versions. If the signature doesn't match, uninstall the old app first.
- [ ] A2. The app opens. The title shows **v4.0 • Auto best shot + ★ gold spot**.
- [ ] A3. On Android 13+ the app asks for notification permission once.

### B. Permissions (Android 13+ / Oppo)
- [ ] B1. If "Display over other apps" is greyed out with **"App was denied access"**, go to App info → ⋮ → **Allow restricted settings**. Then the toggle works. This is an Android rule for apps installed from a file, not a bug in the app.
- [ ] B2. Turning **Aim Overlay** ON does **not** close or crash the app. Test this on Android 14 especially.
- [ ] B3. Turning **Auto-Detect** ON shows Android's screen-recording prompt. Choose **Entire screen**. The app keeps running.

### C. Overlay modes
- [ ] C1. A floating button appears and can be dragged.
- [ ] C2. Tap 1 → toast "⚡ AUTO…". The game is still fully playable. Tapping the board and dragging the striker work normally.
- [ ] C3. Tap 2 → toast "🎯 AIM…". Tapping a coin shows the cyan + green lines for that coin.
- [ ] C4. Tap 3 → "Lines OFF". Nothing is drawn.
- [ ] C5. The floating button can always be tapped, in every mode.
- [ ] C6. "Stop" in the notification removes the button and lines. The app's switch shows OFF within 2 s.

### D. AUTO mode in a real match
- [ ] D1. On the user's turn the lines appear by themselves within about 1 s.
- [ ] D2. The pocket ring is on a **real pocket** (black hole), never outside the board.
- [ ] D3. The cyan line starts at the **real striker**.
- [ ] D4. The green line goes from a real coin to a real pocket, and no other coin is in the way.
- [ ] D5. Sliding the striker → the lines follow within about 0.3–0.5 s.
- [ ] D6. ⭐ gold spot appears on the baseline when a better position exists. Moving the striker there shows a shot.
- [ ] D7. On the opponent's turn **nothing** is drawn.
- [ ] D8. No lines jumping around and no extra dots.
- [ ] D9. Phone stays smooth, with no noticeable lag in the game during a whole match.

### E. Robustness
- [ ] E1. Rotate the screen or switch apps during Auto-Detect → no crash.
- [ ] E2. Turn Auto-Detect off and on again → it still works.
- [ ] E3. Play 3 full matches → no crash and no freeze.

---

## 4. Testing detection without a phone (fast loop)

The app's **real** `BoardDetector` + `ShotPlanner` Java code can run on a computer, using real game screenshots:

```bash
tools/cv-test/run.sh path/to/screenshot1.jpg path/to/screenshot2.jpg
```

- It needs Java 17 and internet access the first time, to download the OpenCV jar from Maven Central.
- It prints, for each screenshot: board, striker, number of coins, pockets, best shot, and gold spot.
- It writes annotated images to `tools/cv-test/out/`:
  - green box: board
  - green rings: pockets
  - yellow: striker
  - magenta: coins
  - cyan: hit line
  - green: coin → pocket
  - yellow dot: gold spot

Use **clean** screenshots, taken with BitAim lines OFF. Old overlay drawings in a screenshot confuse the detector.

Results on the user's 5 screenshots (Nov 2026):

| Screenshot | Board | Pockets | Striker | Coins | Shot |
|---|---|---|---|---|---|
| Blue/striped mid-game | ✅ | ✅ 4 real | ✅ | 11–13 found (2 hidden under ⭐) | ✅ clear shot to top-left |
| Opening break (cluster) | ✅ | ✅ | ✅ | 19 | none from the centre (correct: the cluster blocks it). Gold spot suggested |
| Detection time | | | | | ~20–40 ms per frame on a PC |

---

## 5. Build

- Node 18+, **JDK 17**, Android SDK (platform 34, build-tools 33.0.0), Gradle 8.0.1.

```bash
npm install --legacy-peer-deps
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
cd android && gradle assembleRelease
```

- Output: `android/app/build/outputs/apk/release/app-release.apk`. The release build includes the JS bundle and is signed with the debug key.
- Don't hand out the **debug** APK: it has no JS bundle and only shows a blank or red screen.
- `.github/workflows/build-apk.yml` builds a debug APK on GitHub. Change it to `assembleRelease` if you want an installable artifact.

---

## 6. Known limits

- Coins hidden under the game's ⭐ badge can't be seen. Use AIM mode and tap where the coin is.
- The shot is a straight cut, with no rebounds and no power estimate. The user still controls the power.
- Other carrom games or board themes may need different HSV ranges in `BoardDetector`.
- Screen capture also records BitAim's own lines. All drawings are outlines only, so the detector ignores them. Keep it that way, and don't add filled shapes.
- The game may ban accounts that use helper tools. That's the user's own risk.

---

## 7. Change log

- **v3.1.1:** fix crash on Android 14+ (foreground service types); no restart loop.
- **v3.1.2:** button stays tappable; rotation fix; settings kept; capture stops with the overlay.
- **v3.2:** AIM/PLAY modes; touches pass to the game (alpha 0.8); no fake dots.
- **v4.0:** new detector tuned on real Carrom Pool; AUTO best shot; ⭐ gold spot; lines only on your turn; desktop test tool.
