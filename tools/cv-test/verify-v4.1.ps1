$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot/../../../..").Path
$jdk=if($env:JAVA_HOME){$env:JAVA_HOME}else{'D:\Tools\jdk-17'}
& "$PSScriptRoot/verify.ps1"
$fixtures=@("$root/test-results/team-fixtures/mixed-1.jpeg","$root/test-results/team-fixtures/mixed-2.jpeg","$root/test-results/team-fixtures/mixed-3.jpeg")
$fixtures += "$root/test-results/current-2v2.png"
$fixtures += "$root/test-results/phone-capture-20261107-134613.png"
$fixtures += "$root/test-results/stability-feedback.png"
$fixtures += "$root/test-results/yellow-team-1.png"
$fixtures += "$root/test-results/yellow-team-2.png"
& "$PSScriptRoot/run.ps1" -Screenshots $fixtures
$cp="$PSScriptRoot/classes;$PSScriptRoot/lib/opencv-4.5.1-2.jar"
& "$jdk/bin/java.exe" '-Djava.io.tmpdir=D:\Tools\tmp' -cp $cp TeamRegression @fixtures
if($LASTEXITCODE -ne 0){throw 'Ownership regression failed'}
& "$jdk/bin/java.exe" -cp $cp RouteRegression
if($LASTEXITCODE -ne 0){throw 'Route regression failed'}
& "$jdk/bin/java.exe" -cp $cp PhysicsAuditRegression
if($LASTEXITCODE -ne 0){throw 'Physics audit regression failed'}
foreach($regression in @('QueenRuleRegression','SelectionRegression','PotSpreadRegression','SpeedBeamRegression','BaselineSweepRegression','OpponentWatcherRegression')){
    & "$jdk/bin/java.exe" -cp $cp $regression
    if($LASTEXITCODE -ne 0){throw "$regression failed"}
}
& "$jdk/bin/java.exe" '-Djava.io.tmpdir=D:\Tools\tmp' -cp $cp StripedSkinRegression "$root/test-results/live-striped-blue-20261107.png" "$root/test-results/live-striped-combo-20261107.jpg" "$root/test-results/live-striped-cluster-20261107.jpg" "$root/test-results/live-pocket-lip-20261107.png" "$root/test-results/live-rail-coin-20261107.png" "$root/test-results/live-purple-queen-20261108.jpg"
if($LASTEXITCODE -ne 0){throw 'Striped skin regression failed'}
& "$jdk/bin/java.exe" '-Djava.io.tmpdir=D:\Tools\tmp' -cp $cp LiveColourRegression "$root/test-results/live-striped-black-20261107.png" "$root/test-results/live-queen-glow-20261107.jpg" "$root/test-results/live-queen-rail-20261107.jpg" "$root/test-results/live-pro-style-20261107.jpg" "$root/test-results/live-striker-endcircle-20261107.png" "$root/test-results/live-stuck-cluster-20261107.jpg"
if($LASTEXITCODE -ne 0){throw 'Live colour regression failed'}
& "$jdk/bin/java.exe" '-Djava.io.tmpdir=D:/Tools/tmp' -cp $cp OrangeSkinRegression "$root/test-results/live-orange-black-skin-20261108.png"
if($LASTEXITCODE -ne 0){throw 'OrangeSkinRegression failed'}
& "$jdk/bin/java.exe" '-Djava.io.tmpdir=D:/Tools/tmp' -cp $cp TwoBluesRegression "$root/test-results/live-two-blues-20261108.png"
if($LASTEXITCODE -ne 0){throw 'TwoBluesRegression failed'}
& "$jdk/bin/java.exe" -cp $cp BackwardFoulRegression "$PSScriptRoot/fixtures/backward-direct-foul-165158.jsonl"
if($LASTEXITCODE -ne 0){throw 'BackwardFoulRegression failed'}
& "$jdk/bin/java.exe" '-Djava.io.tmpdir=D:/Tools/tmp' -cp $cp LuckyShotRegression "$root/test-results/live-lucky-shot-20261108.jpg"
if($LASTEXITCODE -ne 0){throw 'LuckyShotRegression failed'}
& "$jdk/bin/java.exe" '-Djava.io.tmpdir=D:/Tools/tmp' -cp $cp CoveredBoardRegression "$root/test-results/practice-board-clean.png"
if($LASTEXITCODE -ne 0){throw 'CoveredBoardRegression failed'}
& "$jdk/bin/java.exe" -cp $cp DiscMemoryRegression "$PSScriptRoot/fixtures/hidden-white-233351.jsonl"
if($LASTEXITCODE -ne 0){throw 'DiscMemoryRegression failed'}
& "$jdk/bin/java.exe" -cp $cp ShotLoggerRegression
if($LASTEXITCODE -ne 0){throw 'Shot logger regression failed'}
& "$jdk/bin/java.exe" -cp $cp AutoPlayRegression
if($LASTEXITCODE -ne 0){throw 'Auto-play regression failed'}
& "$jdk/bin/java.exe" '-Djava.io.tmpdir=D:\Tools\tmp' -cp $cp FocusRegression "$root/test-results/practice-board-clean.png" "$root/test-results/no-clear-shot-feedback.png" "$root/test-results/easy-shot-feedback.png" "$root/test-results/live-colour-20261107-151207.png"
if($LASTEXITCODE -ne 0){throw 'Focus/cluster regression failed'}
$test="$root/test-results/view-test"
$stubs=@(Get-ChildItem "$test/android" -Recurse -Filter '*.java' | ForEach-Object FullName)
$view=(Resolve-Path "$PSScriptRoot/../../android/app/src/main/java/com/bitaim/carromaim/overlay/AimOverlayView.java").Path
New-Item -ItemType Directory -Force "$test/classes" | Out-Null
& "$jdk/bin/javac.exe" -encoding UTF-8 -cp $cp -d "$test/classes" @stubs $view "$test/PickerRegression.java"
if($LASTEXITCODE -ne 0){throw 'Picker test compilation failed'}
& "$jdk/bin/java.exe" -cp "$test/classes;$cp" PickerRegression
if($LASTEXITCODE -ne 0){throw 'Picker regression failed'}
