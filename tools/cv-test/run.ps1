param([Parameter(Mandatory=$true, Position=0, ValueFromRemainingArguments=$true)][string[]]$Screenshots)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$src = Join-Path $here '../../android/app/src/main/java/com/bitaim/carromaim/cv'
$jar = Join-Path $here 'lib/opencv-4.5.1-2.jar'
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\Tools\jdk-17' }
$tempDir = 'D:\Tools\tmp'
New-Item -ItemType Directory -Force "$here/lib", "$here/classes", "$here/out", $tempDir | Out-Null
if (!(Test-Path $jar)) {
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/openpnp/opencv/4.5.1-2/opencv-4.5.1-2.jar' -OutFile $jar
}
$inputs = @($Screenshots | ForEach-Object { (Resolve-Path -LiteralPath $_).Path })
$sources = @(Get-ChildItem "$here/stubs" -Filter '*.java' -Recurse | ForEach-Object FullName)
$sources += @('BoardDetector','Coin','GameState','ShotPlanner','TeamMatcher','ShotTracker','RoutePlanner','PhysicsVerifier','AutoPlayController','GoldSceneLock','ClusterPlanner','AimGuideDetector','AimAlignment','TargetColourLock','QueenRule','OpeningPlanner','SweepPlanner','AimCorrection','ShotLogger','StrikerBaseline','OpponentWatcher','DiscSeparation') | ForEach-Object { "$src/$_.java" }
$sources += "$here/Harness.java"
$sources += "$here/Regression.java"
$sources += "$here/PlannerRegression.java"
$sources += "$here/TeamRegression.java"
$sources += "$here/RouteRegression.java"
$sources += "$here/AutoPlayRegression.java"
$sources += "$here/FocusRegression.java"
$sources += "$here/PhysicsAuditRegression.java"
$sources += "$here/QueenRuleRegression.java"
$sources += "$here/SelectionRegression.java"
$sources += "$here/PotSpreadRegression.java"
$sources += "$here/SpeedBeamRegression.java"
$sources += "$here/LiveColourRegression.java"
$sources += "$here/StripedSkinRegression.java"
$sources += "$here/OutcomeCalibrator.java"
$sources += "$here/ShotLogCalibrator.java"
$sources += "$here/ShotLoggerRegression.java"
$sources += "$here/OrangeSkinRegression.java"
$sources += "$here/BaselineSweepRegression.java"
$sources += "$here/OpponentWatcherRegression.java"
$sources += "$here/TwoBluesRegression.java"
& "$jdk/bin/javac.exe" -encoding UTF-8 -d "$here/classes" -cp $jar @sources
if ($LASTEXITCODE -ne 0) { throw 'CV test compilation failed' }
Push-Location $here
try {
    & "$jdk/bin/java.exe" "-Djava.io.tmpdir=$tempDir" -cp "classes;$jar" Harness @inputs
    if ($LASTEXITCODE -ne 0) { throw 'CV test failed' }
} finally { Pop-Location }
