param([string]$Device = 'emulator-5580')
$ErrorActionPreference = 'Stop'
$project = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
Set-Location -LiteralPath $project
$taskDir = Join-Path $project 'build\console-validation\session'
New-Item -ItemType Directory -Path "$taskDir\dex" -Force | Out-Null
$sdk = 'C:\Users\nac\AppData\Local\Android\Sdk'
$jdkBin = Join-Path $env:JAVA_HOME 'bin'
$runtime = 'C:\Users\nac\.gradle\caches\modules-2\files-2.1'
$dependencies = @(
    @('org.jetbrains.kotlin','kotlin-stdlib','2.1.21'),
    @('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.9.0'),
    @('org.jetbrains.kotlinx','kotlinx-serialization-core-jvm','1.7.3'),
    @('org.jetbrains.kotlinx','kotlinx-serialization-json-jvm','1.7.3')
)
$jars = foreach ($dependency in $dependencies) {
    $directory = Join-Path $runtime ($dependency -join '\')
    (Get-ChildItem -LiteralPath $directory -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
}
$classes = Join-Path $project 'app\build\tmp\kotlin-classes\debug'
& "$jdkBin\jar.exe" cf "$taskDir\console.jar" -C $classes 'com/mcmobile/server/core/console'
$tests = Join-Path $project 'app\build\tmp\kotlin-classes\debugUnitTest'
$jarArgs = @('cf', "$taskDir\probe.jar")
foreach ($file in Get-ChildItem -LiteralPath "$tests\com\mcmobile\server\core\console" -Filter 'ConsoleAndroidProbe*') {
    $jarArgs += @('-C', $tests, "com/mcmobile/server/core/console/$($file.Name)")
}
& "$jdkBin\jar.exe" @jarArgs
& "$sdk\build-tools\36.1.0\d8.bat" --min-api 26 --lib "$sdk\platforms\android-36\android.jar" --output "$taskDir\dex" "$taskDir\console.jar" "$taskDir\probe.jar" @jars
if ($LASTEXITCODE -ne 0) { throw 'D8 failed' }
& "$jdkBin\jar.exe" cf "$taskDir\probe-dex.jar" -C "$taskDir\dex" classes.dex
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$remote = '/data/local/tmp/mcs-console-session.jar'
& $adb -s $Device push "$taskDir\probe-dex.jar" $remote
if ($LASTEXITCODE -ne 0) { throw 'Probe push failed' }
& $adb -s $Device shell chmod 444 $remote
$result = @(& $adb -s $Device shell "CLASSPATH=$remote" app_process -Xmx128m /system/bin com.mcmobile.server.core.console.ConsoleAndroidProbe)
$probeExit = $LASTEXITCODE
$result | Set-Content -LiteralPath (Join-Path $project 'build\console-validation\session-results.txt') -Encoding utf8
$result
if ($probeExit -ne 0) { throw 'Android session probe failed' }
