param(
    [string[]]$Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest'),
    [switch]$Check
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$callerJavaHome = $env:JAVA_HOME
$buildJavaHome = 'F:\located_world\devkit\jdk-17.0.2'
$cachedGradle = 'F:\located_world\devkit\gradle-8.2\bin\gradle.bat'
Push-Location $projectRoot
try {
    # Gradle 8.2 cannot run on the shared tooling JDK 21.
    if (Test-Path -LiteralPath $buildJavaHome) { $env:JAVA_HOME = $buildJavaHome }
    if ($Check) { $Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest') }
    if (Test-Path -LiteralPath $cachedGradle) { & $cachedGradle @Tasks --no-daemon --console=plain }
    else { & (Join-Path $projectRoot 'gradlew.bat') @Tasks --no-daemon --console=plain }
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
    if ($Check) {
        & npm.cmd run test:sign
        if ($LASTEXITCODE -ne 0) { throw 'Signing tests failed' }
    }
} finally {
    $env:JAVA_HOME = $callerJavaHome
    Pop-Location
}
