# BitAim / carrom-auto

Android carrom aim guidance built with React Native, Java and OpenCV. The latest recorded installed release is **v4.6.7, build 31** (2026-11-07): aim-tolerance ranking, positional path-opening shots and an off-thread planner.

## Development knowledge

Read **[DEVELOPMENT_KNOWLEDGE.md](DEVELOPMENT_KNOWLEDGE.md)** before continuing development. It records the implementation history, algorithms, source map, screenshot evidence, simulations, parallel-agent findings, tests, build/install procedure, and unresolved issues.

## Project links

| Resource | Location |
| --- | --- |
| Current source project | [BitAim source](BitAim-v4.0-source/BitAim-v4.0-source/) |
| Current release and verification | [v4.5 report](V4.5_REPORT.md) |
| Latest APK | [BitAim v4.6.7](OUTPUTS/apk/BitAim-v4.6.7-real-pots.apk) |
| Algorithm bench (every screenshot, every candidate) | [before](test-results/algorithm-bench/summary.md) / [after](test-results/algorithm-bench/after/summary.md) |
| Organized screenshots, simulations and reports | [OUTPUTS](OUTPUTS/README.md) |
| Latest six gallery references | [Screenshot list](test-results/gallery-2026-11-07/latest-six.txt) |
| Build environment requirements | [INSTRUCTION.md](INSTRUCTION.md) |
| Original goals and checklist | [GOALS_AND_TEST_PLAN.md](GOALS_AND_TEST_PLAN.md) |

The source directory retains its original v4.0 name; the actual version is set in the Android build file. The original checklist describes an aim-only app. Later user requests explicitly added optional gesture control; the knowledge file explains that scope change.

## Using the colour lock

1. Open BitAim and start **Aim Overlay**, then **Auto-Detect → Entire screen**.
2. Return to Carrom and hold the floating star without dragging it.
3. Choose **White**, **Black**, or another detected colour. The label under the star identifies the frozen colour.
4. Hold the star again for **Next route** or **Auto team**.

Colour lock restricts final targets; AUTO can still choose another coin of that colour. A separately tapped coin is an individual target lock. Freestyle can recognize all colours automatically, with an explicit fallback. Optional automatic shots start OFF and remain experimental.

## Reading the overlay (v4.5)

- **EASY / OK / TIGHT / RISKY** after the aim label is how many degrees of aim error the model still pots (2°+, 1°, 0.5°, under 0.5°). Prefer ★ when it offers an easier spot.
- **OPEN PATHS** is a positional shot for a blocked board: orange ring on the first contact, orange line where that coin should travel, and `before → after pocket paths`. Medium power, then pot next turn. Hold the star for *Next route* to see the pot alternatives.
- Nothing is automatic unless the experimental gesture toggle is on; openers and cluster guides are always manual.

## Verification

From the workspace root in PowerShell:

```powershell
$env:JAVA_HOME='D:\Tools\jdk-17'
& './BitAim-v4.0-source/BitAim-v4.0-source/tools/cv-test/verify-v4.1.ps1'
```

Despite its historical filename, this script runs the current regression suites. `tools/cv-test/bench.ps1 <screenshots>` runs the exhaustive planner bench (aim and speed tolerance windows, 41-position baseline sweep). Never run the two at the same time: they share a classes folder. More detailed commands and evidence are in the development knowledge file.

**Model scores are simulation stress results, not measured pot probabilities.** Real-game power, spin, gesture accuracy, sustained phone performance and some near-rail cases still need validation. Do not infer 100% accuracy from a green line or a 9/9 score.
