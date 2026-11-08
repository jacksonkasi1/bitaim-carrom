# Builds and runs the BitAim PC brain.
# Phone: USB debugging on, BitAim open with Aim Overlay + Auto-Detect, "PC brain (USB)" switch ON.
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$cv = "$here/../cv-test"
$src = "$here/../../android/app/src/main/java/com/bitaim/carromaim/cv"
$jdk = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME/bin/javac.exe")) { $env:JAVA_HOME } else { 'D:\Tools\jdk-17' }
$adb = if ($env:ADB) { $env:ADB } else { 'D:\Tools\Android\platform-tools\adb.exe' }
$jar = "$cv/lib/opencv-4.5.1-2.jar"
if (!(Test-Path $jar)) {
    New-Item -ItemType Directory -Force "$cv/lib" | Out-Null
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/openpnp/opencv/4.5.1-2/opencv-4.5.1-2.jar' -OutFile $jar
}
New-Item -ItemType Directory -Force "$here/classes" | Out-Null
$sources = @(Get-ChildItem "$cv/stubs" -Filter '*.java' -Recurse | ForEach-Object FullName)
$sources += @('BoardDetector','Coin','GameState','ShotPlanner','TeamMatcher','ShotTracker','RoutePlanner','PhysicsVerifier','AutoPlayController','GoldSceneLock','ClusterPlanner','AimGuideDetector','AimAlignment','TargetColourLock','QueenRule','OpeningPlanner','SweepPlanner','AimCorrection','ShotLogger','StrikerBaseline','OpponentWatcher','DiscSeparation') | ForEach-Object { "$src/$_.java" }
$sources += "$here/PcBrain.java"
& "$jdk/bin/javac.exe" -nowarn -encoding UTF-8 -cp $jar -d "$here/classes" @sources
if ($LASTEXITCODE -ne 0) { throw 'PC brain compilation failed' }
& $adb reverse tcp:8765 tcp:8765
& "$jdk/bin/java.exe" '-Djava.io.tmpdir=D:/Tools/tmp' -cp "$here/classes;$jar" PcBrain "$here/recordings"
