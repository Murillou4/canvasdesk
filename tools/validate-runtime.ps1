param(
    [string]$JdkPath = '',
    [switch]$SkipBuild,
    [switch]$SkipSingleplayer,
    [switch]$SkipVisual,
    [switch]$SkipMultiplayer,
    [switch]$SkipDedicated
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (!$JdkPath) { $JdkPath = Join-Path $taskRoot '.codex-temp\jdk\jdk-25.0.4.1+1' }
$env:JAVA_HOME = (Resolve-Path $JdkPath).Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$taskStarted = Get-Date
$taskProcesses = [Collections.Generic.List[Diagnostics.Process]]::new()
$taskArtifacts = Join-Path $taskRoot 'artifacts'
New-Item -ItemType Directory -Path $taskArtifacts -Force | Out-Null
function Invoke-Gradle([string[]]$TaskArgs, [string]$Log) {
    $taskLog=Join-Path $taskArtifacts $Log
    $process=Start-Process -FilePath $env:ComSpec -ArgumentList '/d','/c',('gradlew.bat --no-daemon --console=plain '+($TaskArgs -join ' ')) -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $taskLog -RedirectStandardError ($taskLog+'.stderr')
    $taskProcesses.Add($process)
    $deadline=(Get-Date).AddMinutes(12)
    while(!$process.HasExited) {
        if((Get-Date) -gt $deadline){throw "Gradle stage timed out: $TaskArgs"}
        Start-Sleep -Seconds 1
    }
    $process.WaitForExit()
    Get-Content -LiteralPath $taskLog | Select-Object -Last 35
    if ($process.ExitCode -ne 0) { throw "Gradle failed: $TaskArgs. See $taskLog and its .stderr file." }
}
function Archive-TestOutput([string]$Name) {
    $source = Join-Path $taskArtifacts $Name
    if (Test-Path -LiteralPath $source) {
        $target = Join-Path $taskArtifacts ('history\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + $Name)
        New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
        Move-Item -LiteralPath $source -Destination $target
    }
}
function Assert-Pass([string]$Path) {
    $file = Get-Item -LiteralPath $Path
    if ($file.LastWriteTime -lt $taskStarted.AddSeconds(-2)) { throw "Stale evidence: $Path" }
    $lines = @(Get-Content -LiteralPath $Path | Where-Object { $_.Trim() })
    if (!$lines -or $lines[-1] -ne 'PASS' -or ($lines | Where-Object { $_ -match '^(FAIL|ERROR)' })) { throw "Failed evidence: $Path" }
}
Push-Location $taskRoot
try {
    if (!$SkipBuild) { Invoke-Gradle @('clean','build') 'build.log' }
    if (!$SkipSingleplayer) {
        Archive-TestOutput 'runtime'
        Invoke-Gradle @('runClientGameTest') 'client-gradle.log'
        Assert-Pass (Join-Path $taskArtifacts 'runtime\gameplay-selftest.log')
        Assert-Pass (Join-Path $taskArtifacts 'runtime\ui-selftest.log')
        $summary=Get-Content -Raw (Join-Path $taskArtifacts 'runtime\summary.json') | ConvertFrom-Json
        if ($summary.status -ne 'PASS') { throw 'Client summary did not pass' }
        foreach($shot in $summary.screenshots) { if ((Get-Item -LiteralPath $shot).Length -lt 10000) { throw "Invalid screenshot: $shot" } }
    }
    if (!$SkipVisual) {
        Archive-TestOutput 'visual'
        Invoke-Gradle @('runClientGameTest','-Pcanvasdesk_test_role=visual') 'visual-gradle.log'
        Assert-Pass (Join-Path $taskArtifacts 'visual\visual-selftest.log')
    }
    if (!$SkipMultiplayer) {
        Archive-TestOutput 'multiplayer'
        New-Item -ItemType Directory -Path (Join-Path $taskArtifacts 'multiplayer') -Force | Out-Null
        $primary=Start-Process -FilePath $env:ComSpec -ArgumentList '/d','/c','gradlew.bat --no-daemon --console=plain runClientGameTest -Pcanvasdesk_test_role=primary' -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskArtifacts 'multiplayer\primary-gradle.log') -RedirectStandardError (Join-Path $taskArtifacts 'multiplayer\primary-stderr.log')
        $taskProcesses.Add($primary)
        $deadline=(Get-Date).AddMinutes(4)
        while (!(Test-Path -LiteralPath (Join-Path $taskArtifacts 'multiplayer\port.txt'))) {
            if ($primary.HasExited -or (Get-Date) -gt $deadline) { throw 'Primary multiplayer test did not become ready; inspect primary-gradle.log' }
            Start-Sleep -Seconds 1
        }
        $peer=Start-Process -FilePath $env:ComSpec -ArgumentList '/d','/c','gradlew.bat --no-daemon --console=plain runClientGameTest -Pcanvasdesk_test_role=peer' -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskArtifacts 'multiplayer\peer-gradle.log') -RedirectStandardError (Join-Path $taskArtifacts 'multiplayer\peer-stderr.log')
        $taskProcesses.Add($peer)
        $deadline=(Get-Date).AddMinutes(6)
        while (!$primary.HasExited -or !$peer.HasExited) {
            if ((Get-Date) -gt $deadline) { throw 'Multiplayer test timed out; inspect both process logs' }
            Start-Sleep -Seconds 1
        }
        if ($primary.ExitCode -ne 0 -or $peer.ExitCode -ne 0) { throw 'A multiplayer client failed; inspect artifacts/multiplayer' }
        Assert-Pass (Join-Path $taskArtifacts 'multiplayer\primary.log')
        Assert-Pass (Join-Path $taskArtifacts 'multiplayer\peer.log')
    }
    if (!$SkipDedicated) {
        Archive-TestOutput 'dedicated'
        $run=Join-Path $taskRoot '.codex-temp\dedicated-validation'
        New-Item -ItemType Directory -Path $run -Force | Out-Null
        $world='canvasdesk-load-'+(Get-Date -Format 'yyyyMMdd-HHmmss')
        [IO.File]::WriteAllText((Join-Path $run 'eula.txt'),"eula=true`n")
        [IO.File]::WriteAllText((Join-Path $run 'server.properties'),"server-ip=127.0.0.1`nserver-port=0`nonline-mode=false`nlevel-name=$world`nview-distance=2`nsimulation-distance=2`n")
        Invoke-Gradle @('runDedicatedValidation') 'dedicated-gradle.log'
        Assert-Pass (Join-Path $taskArtifacts 'dedicated\gameplay-selftest.log')
        Invoke-Gradle @('runDedicatedValidation','-Pcanvasdesk_server_reload=true') 'dedicated-reload-gradle.log'
        Assert-Pass (Join-Path $taskArtifacts 'dedicated\restart-selftest.log')
    }
    Write-Host 'PASS: requested validation stages completed; clients and server exited.'
} finally {
    foreach($taskProcess in $taskProcesses) {
        if (!$taskProcess.HasExited) {
            $all = @(Get-CimInstance Win32_Process)
            $descendants = [Collections.Generic.List[int]]::new();$descendants.Add($taskProcess.Id)
            for($i=0;$i -lt $descendants.Count;$i++) {
                foreach($child in $all | Where-Object ParentProcessId -EQ $descendants[$i]) { $descendants.Add([int]$child.ProcessId) }
            }
            for($i=$descendants.Count-1;$i -ge 0;$i--) { Stop-Process -Id $descendants[$i] -Force -ErrorAction SilentlyContinue }
            Write-Warning 'Stopped only the process tree launched by this failed/timed-out test.'
        }
    }
    Pop-Location
}
