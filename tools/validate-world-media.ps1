param(
    [string]$JdkPath = '',
    [ValidateSet('all','materials','none')][string]$NativeInputMode = 'none',
    [ValidateRange(30,180)][int]$TimeoutSeconds = 180
)
$env:ORG_GRADLE_PROJECT_canvasdesk_native_input_mode = $NativeInputMode
$ErrorActionPreference = 'Stop'
$editorRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (!$JdkPath) { $JdkPath = Split-Path (Split-Path (Get-Command java.exe).Source) }
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JdkPath).Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$editorProfile = Join-Path $editorRoot '.codex-temp\world-media-client-141'
$editorAttempt = 'attempt-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
$editorOutput = Join-Path $editorRoot ('artifacts\1.4.1\smoke\' + $editorAttempt)
$env:ORG_GRADLE_PROJECT_canvasdesk_probe_output = $editorOutput
New-Item -ItemType Directory -Path $editorProfile,$editorOutput -Force | Out-Null

# This profile is dedicated to this bounded smoke; normal game options/worlds are untouched.
$editorOptions = @{
    onboardAccessibility='false'; guiScale='2'; renderDistance='3'; simulationDistance='5'; maxFps='30'
    soundCategory_master='0.0'; fullscreen='false'; pauseOnLostFocus='false'; tutorialStep='none'; skipMultiplayerWarning='true'
}
$editorOptionsFile = Join-Path $editorProfile 'options.txt'
$editorLines = if (Test-Path -LiteralPath $editorOptionsFile) { @(Get-Content -LiteralPath $editorOptionsFile) } else { @() }
$editorLines = @($editorLines | Where-Object { ($_ -split ':',2)[0] -notin $editorOptions.Keys })
$editorLines += @($editorOptions.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Name+':'+$_.Value })
[IO.File]::WriteAllLines($editorOptionsFile,[string[]]$editorLines,[Text.UTF8Encoding]::new($false))

$editorBaseline = @{}
foreach ($entry in Get-CimInstance Win32_Process) { $editorBaseline[[int]$entry.ProcessId] = $true }
$editorTracked = @{}
$editorStarted = Get-Date
$editorDeadline = $editorStarted.AddSeconds($TimeoutSeconds)
$editorProcess = $null
$editorFailure = $null
function Register-EditorProcesses {
    $snapshot = @(Get-CimInstance Win32_Process)
    do {
        $changed = $false
        foreach ($entry in $snapshot) {
            $entryId = [int]$entry.ProcessId
            if ($editorBaseline.ContainsKey($entryId) -or $editorTracked.ContainsKey($entryId)) { continue }
            if ($editorTracked.ContainsKey([int]$entry.ParentProcessId)) {
                $editorTracked[$entryId] = [pscustomobject]@{
                    id=$entryId; parent=[int]$entry.ParentProcessId; name=$entry.Name
                    created=([datetime]$entry.CreationDate).ToUniversalTime().ToString('o')
                }
                $changed = $true
            }
        }
    } while ($changed)
    @($editorTracked.Values | Sort-Object id) | ConvertTo-Json -Depth 3 |
        Set-Content -LiteralPath (Join-Path $editorOutput 'processes.json') -Encoding utf8
}
try {
    # Compile the source set before invoking this runner. Runtime gets one total deadline, not one per stage.
    $editorProcess = Start-Process -FilePath $env:ComSpec -ArgumentList '/d','/c','gradlew.bat --no-daemon --console=plain runClientGameTest -Pcanvasdesk_test_role=worldMedia' `
        -WorkingDirectory $editorRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $editorOutput 'runtime-gradle.log') `
        -RedirectStandardError (Join-Path $editorOutput 'runtime-stderr.log')
    $editorTracked[$editorProcess.Id] = [pscustomobject]@{
        id=$editorProcess.Id; parent=$PID; name='cmd.exe'; created=$editorProcess.StartTime.ToUniversalTime().ToString('o')
    }
    $editorRootProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($editorProcess.Id)" -ErrorAction SilentlyContinue
    if ($editorRootProcess) {
        $editorTracked[$editorProcess.Id].created = ([datetime]$editorRootProcess.CreationDate).ToUniversalTime().ToString('o')
    }
    do {
        Register-EditorProcesses
        if ((Get-Date) -gt $editorDeadline) { throw "Editor smoke exceeded its total $TimeoutSeconds-second runtime limit." }
        if (!$editorProcess.HasExited) { Start-Sleep -Milliseconds 500 }
    } while (!$editorProcess.HasExited)
    $editorProcess.WaitForExit()
    if ($editorProcess.ExitCode -ne 0) { throw "Editor runtime failed with exit code $($editorProcess.ExitCode)." }
    $editorSummaryFile = Get-Item -LiteralPath (Join-Path $editorOutput 'summary.json')
    if ($editorSummaryFile.LastWriteTime -lt $editorStarted.AddSeconds(-1)) { throw 'Stale editor summary.' }
    $editorSummary = Get-Content -LiteralPath $editorSummaryFile.FullName -Raw | ConvertFrom-Json
    $editorLog = @(Get-Content -LiteralPath (Join-Path $editorOutput 'editor-selftest.log') | Where-Object { $_.Trim() })
    if ($editorSummary.status -ne 'PASS' -or !$editorLog -or $editorLog[-1] -ne 'PASS' -or ($editorLog | Where-Object { $_ -match '^(FAIL|ERROR)' })) {
        throw 'Editor assertions did not pass; inspect summary.json and editor-selftest.log.'
    }
    foreach ($shot in $editorSummary.screenshots) {
        $file = Get-Item -LiteralPath $shot
        if ($file.LastWriteTime -lt $editorStarted.AddSeconds(-1) -or $file.Length -lt 1000) { throw "Missing/stale screenshot: $shot" }
    }
    Write-Host 'PASS: world media smoke completed.'
} catch {
    $editorFailure = $_.Exception.Message
    throw
} finally {
    if ($editorProcess) { Register-EditorProcesses }
    # Only newly created, registered descendants with the same creation time can be stopped.
    # Every process in the baseline (including an existing Gradle daemon) is preserved.
    $editorStopped = [Collections.Generic.List[int]]::new()
    foreach ($entry in @($editorTracked.Values | Sort-Object id -Descending)) {
        if ($editorBaseline.ContainsKey([int]$entry.id)) { continue }
        $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.id)" -ErrorAction SilentlyContinue
        if ($current -and ([datetime]$current.CreationDate).ToUniversalTime().ToString('o') -eq $entry.created) {
            Stop-Process -Id $entry.id -Force -ErrorAction SilentlyContinue
            $editorStopped.Add([int]$entry.id)
        }
    }
    [pscustomobject]@{
        started=$editorStarted.ToUniversalTime().ToString('o'); ended=(Get-Date).ToUniversalTime().ToString('o')
        timeoutSeconds=$TimeoutSeconds; failure=$editorFailure; stoppedNewDescendants=$editorStopped.ToArray()
        baselinePreserved=$true; baselineCount=$editorBaseline.Count
    } | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $editorOutput 'runner.json') -Encoding utf8
}
