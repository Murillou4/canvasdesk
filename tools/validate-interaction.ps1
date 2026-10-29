param(
    [string]$JdkPath = '',
    [ValidateRange(30,180)][int]$TimeoutSeconds = 180
)
$ErrorActionPreference = 'Stop'
$interactionRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (!$JdkPath) { $JdkPath = Split-Path (Split-Path (Get-Command java.exe).Source) }
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JdkPath).Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$interactionProfile = Join-Path $interactionRoot '.codex-temp\interaction-client-121'
$interactionOutput = Join-Path $interactionRoot 'artifacts\1.2.1\smoke'
New-Item -ItemType Directory -Path $interactionProfile,$interactionOutput -Force | Out-Null

# This profile is dedicated to this bounded smoke; normal game options/worlds are untouched.
$interactionOptions = @{
    onboardAccessibility='false'; guiScale='2'; renderDistance='3'; simulationDistance='5'; maxFps='30'
    fullscreen='false'; pauseOnLostFocus='false'; tutorialStep='none'; skipMultiplayerWarning='true'
}
$interactionOptionsFile = Join-Path $interactionProfile 'options.txt'
$interactionLines = if (Test-Path -LiteralPath $interactionOptionsFile) { @(Get-Content -LiteralPath $interactionOptionsFile) } else { @() }
$interactionLines = @($interactionLines | Where-Object { ($_ -split ':',2)[0] -notin $interactionOptions.Keys })
$interactionLines += @($interactionOptions.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Name+':'+$_.Value })
[IO.File]::WriteAllLines($interactionOptionsFile,[string[]]$interactionLines,[Text.UTF8Encoding]::new($false))

$interactionBaseline = @{}
foreach ($entry in Get-CimInstance Win32_Process) { $interactionBaseline[[int]$entry.ProcessId] = $true }
$interactionTracked = @{}
$interactionStarted = Get-Date
$interactionDeadline = $interactionStarted.AddSeconds($TimeoutSeconds)
$interactionProcess = $null
$interactionFailure = $null
function Register-InteractionProcesses {
    $snapshot = @(Get-CimInstance Win32_Process)
    do {
        $changed = $false
        foreach ($entry in $snapshot) {
            $entryId = [int]$entry.ProcessId
            if ($interactionBaseline.ContainsKey($entryId) -or $interactionTracked.ContainsKey($entryId)) { continue }
            if ($interactionTracked.ContainsKey([int]$entry.ParentProcessId)) {
                $interactionTracked[$entryId] = [pscustomobject]@{
                    id=$entryId; parent=[int]$entry.ParentProcessId; name=$entry.Name
                    created=([datetime]$entry.CreationDate).ToUniversalTime().ToString('o')
                }
                $changed = $true
            }
        }
    } while ($changed)
    @($interactionTracked.Values | Sort-Object id) | ConvertTo-Json -Depth 3 |
        Set-Content -LiteralPath (Join-Path $interactionOutput 'processes.json') -Encoding utf8
}
try {
    # Compile the source set before invoking this runner. Runtime gets one total deadline, not one per stage.
    $interactionProcess = Start-Process -FilePath $env:ComSpec -ArgumentList '/d','/c','gradlew.bat --no-daemon --console=plain runClientGameTest -Pcanvasdesk_test_role=interaction' `
        -WorkingDirectory $interactionRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $interactionOutput 'runtime-gradle.log') `
        -RedirectStandardError (Join-Path $interactionOutput 'runtime-stderr.log')
    $interactionTracked[$interactionProcess.Id] = [pscustomobject]@{
        id=$interactionProcess.Id; parent=$PID; name='cmd.exe'; created=$interactionProcess.StartTime.ToUniversalTime().ToString('o')
    }
    $interactionRootProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($interactionProcess.Id)" -ErrorAction SilentlyContinue
    if ($interactionRootProcess) {
        $interactionTracked[$interactionProcess.Id].created = ([datetime]$interactionRootProcess.CreationDate).ToUniversalTime().ToString('o')
    }
    do {
        Register-InteractionProcesses
        if ((Get-Date) -gt $interactionDeadline) { throw "Interaction smoke exceeded its total $TimeoutSeconds-second runtime limit." }
        if (!$interactionProcess.HasExited) { Start-Sleep -Milliseconds 500 }
    } while (!$interactionProcess.HasExited)
    $interactionProcess.WaitForExit()
    if ($interactionProcess.ExitCode -ne 0) { throw "Interaction runtime failed with exit code $($interactionProcess.ExitCode)." }
    $interactionSummaryFile = Get-Item -LiteralPath (Join-Path $interactionOutput 'summary.json')
    if ($interactionSummaryFile.LastWriteTime -lt $interactionStarted.AddSeconds(-1)) { throw 'Stale interaction summary.' }
    $interactionSummary = Get-Content -LiteralPath $interactionSummaryFile.FullName -Raw | ConvertFrom-Json
    $interactionLog = @(Get-Content -LiteralPath (Join-Path $interactionOutput 'interaction-selftest.log') | Where-Object { $_.Trim() })
    if ($interactionSummary.status -ne 'PASS' -or !$interactionLog -or $interactionLog[-1] -ne 'PASS' -or ($interactionLog | Where-Object { $_ -match '^(FAIL|ERROR)' })) {
        throw 'Interaction assertions did not pass; inspect summary.json and interaction-selftest.log.'
    }
    foreach ($shot in $interactionSummary.screenshots) {
        $file = Get-Item -LiteralPath $shot
        if ($file.LastWriteTime -lt $interactionStarted.AddSeconds(-1) -or $file.Length -lt 1000) { throw "Missing/stale screenshot: $shot" }
    }
    Write-Host 'PASS: focused interaction smoke completed.'
} catch {
    $interactionFailure = $_.Exception.Message
    throw
} finally {
    if ($interactionProcess) { Register-InteractionProcesses }
    # Only newly created, registered descendants with the same creation time can be stopped.
    # Every process in the baseline (including an existing Gradle daemon) is preserved.
    $interactionStopped = [Collections.Generic.List[int]]::new()
    foreach ($entry in @($interactionTracked.Values | Sort-Object id -Descending)) {
        if ($interactionBaseline.ContainsKey([int]$entry.id)) { continue }
        $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.id)" -ErrorAction SilentlyContinue
        if ($current -and ([datetime]$current.CreationDate).ToUniversalTime().ToString('o') -eq $entry.created) {
            Stop-Process -Id $entry.id -Force -ErrorAction SilentlyContinue
            $interactionStopped.Add([int]$entry.id)
        }
    }
    [pscustomobject]@{
        started=$interactionStarted.ToUniversalTime().ToString('o'); ended=(Get-Date).ToUniversalTime().ToString('o')
        timeoutSeconds=$TimeoutSeconds; failure=$interactionFailure; stoppedNewDescendants=$interactionStopped.ToArray()
        baselinePreserved=$true; baselineCount=$interactionBaseline.Count
    } | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $interactionOutput 'runner.json') -Encoding utf8
}
