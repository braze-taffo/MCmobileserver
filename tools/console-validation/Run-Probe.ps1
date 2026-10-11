param([ValidateSet('old','fixed')][string]$Mode, [string]$Device = 'emulator-5580', [switch]$Abrupt)
$ErrorActionPreference = 'Stop'
$project = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
Set-Location -LiteralPath $project
$taskDir = Join-Path $project "build\console-validation\$Mode"
New-Item -ItemType Directory -Path "$taskDir\classes","$taskDir\dex" -Force | Out-Null
$sdk = 'C:\Users\nac\AppData\Local\Android\Sdk'
$jdkBin = Join-Path $env:JAVA_HOME 'bin'
$stdlib = (Get-ChildItem -LiteralPath 'C:\Users\nac\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib\2.1.21' -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$androidJar = Join-Path $sdk 'platforms\android-36\android.jar'
if ($Mode -eq 'fixed') {
    $classes = Join-Path $project 'app\build\tmp\kotlin-classes\debug'
    $jarArgs = @('cf', "$taskDir\bridge.jar")
    $classFiles = Get-ChildItem -LiteralPath "$classes\com\mcmobile\server\core\console" -File | Where-Object Name -Match '^Console(BridgeServer|OutboundQueue|ReplyPatterns)'
    foreach ($file in $classFiles) { $jarArgs += @('-C', $classes, "com/mcmobile/server/core/console/$($file.Name)") }
    & "$jdkBin\jar.exe" @jarArgs
}
if (-not (Test-Path -LiteralPath "$taskDir\bridge.jar")) { throw 'The saved old bridge.jar or freshly compiled fixed bridge is required.' }
& "$jdkBin\javac.exe" --release 17 -cp "$taskDir\bridge.jar;$stdlib;$androidJar" -d "$taskDir\classes" 'tools\console-validation\ConsolePipeProbe.java'
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
& "$jdkBin\jar.exe" cf "$taskDir\probe.jar" -C "$taskDir\classes" .
& "$sdk\build-tools\36.1.0\d8.bat" --min-api 26 --lib $androidJar --output "$taskDir\dex" "$taskDir\bridge.jar" "$taskDir\probe.jar" $stdlib
if ($LASTEXITCODE -ne 0) { throw 'D8 failed' }
& "$jdkBin\jar.exe" cf "$taskDir\probe-dex.jar" -C "$taskDir\dex" classes.dex
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$remote = "/data/local/tmp/mcs-console-$Mode.jar"
& $adb -s $Device push "$taskDir\probe-dex.jar" $remote
if ($LASTEXITCODE -ne 0) { throw 'Probe push failed' }
& $adb -s $Device shell chmod 444 $remote
$probeArgs = @($Mode)
if ($Abrupt) { $probeArgs += 'abrupt' }
$result = @(& $adb -s $Device shell "CLASSPATH=$remote" app_process -Xmx128m /system/bin ConsolePipeProbe @probeArgs)
$probeExit = $LASTEXITCODE
$suffix = if ($Abrupt) { '-abrupt' } else { '' }
$result | Set-Content -LiteralPath (Join-Path $project "build\console-validation\$Mode$suffix-results.txt") -Encoding utf8
$result
if ($probeExit -ne 0) { throw "Android $Mode probe failed" }
if ($Abrupt) {
    $lineCount = (& $adb -s $Device shell wc -l /data/local/tmp/mcs-audit-abrupt.log) -join ''
    if ($lineCount -notmatch '^1000\s') { throw "Abrupt process exit lost logs: $lineCount" }
    $lineCount | Add-Content -LiteralPath (Join-Path $project "build\console-validation\$Mode$suffix-results.txt")
    Write-Output "ABRUPT_LOG_LINES_PRESERVED $lineCount"
    & $adb -s $Device shell rm /data/local/tmp/mcs-audit-abrupt.log
}
