$ErrorActionPreference = 'Stop'
$fixtures = (Resolve-Path "$PSScriptRoot/../../../../test-results").Path
$images = @("$fixtures/practice-board-clean.png", "$fixtures/midgame-clean.png", "$fixtures/initial.png")
$images += @("$fixtures/latest-game-2.png", "$fixtures/latest-game-3.png", "$fixtures/latest-game-4.png", "$fixtures/latest-game-5.png", "$fixtures/latest-game.png")
$images += @("$fixtures/latest-game-6.png", "$fixtures/latest-game-7.png")
& "$PSScriptRoot/run.ps1" -Screenshots $images
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\Tools\jdk-17' }
$cp = "$PSScriptRoot/classes;$PSScriptRoot/lib/opencv-4.5.1-2.jar"
& "$jdk/bin/java.exe" '-Djava.io.tmpdir=D:\Tools\tmp' -cp $cp Regression @images
if ($LASTEXITCODE -ne 0) { throw 'Device screenshot regression failed' }
& "$jdk/bin/java.exe" -cp $cp PlannerRegression
if ($LASTEXITCODE -ne 0) { throw 'Shot geometry regression failed' }
