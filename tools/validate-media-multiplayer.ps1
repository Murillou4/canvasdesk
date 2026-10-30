param([string]$JdkPath='',[ValidateRange(30,180)][int]$TimeoutSeconds=180)
$ErrorActionPreference='Stop'
$editorRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if(!$JdkPath){$JdkPath=Split-Path (Split-Path (Get-Command java.exe).Source)}
$env:JAVA_HOME=(Resolve-Path -LiteralPath $JdkPath).Path
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$editorOutput=Join-Path $editorRoot ('artifacts\1.5.0\multiplayer\attempt-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$env:ORG_GRADLE_PROJECT_canvasdesk_probe_output=$editorOutput
New-Item -ItemType Directory -Path $editorOutput -Force | Out-Null
foreach($role in @('mediaOwner','mediaPeer')){
 $profile=Join-Path $editorRoot ('.codex-temp\multiplayer-'+$role)
 New-Item -ItemType Directory -Path $profile -Force | Out-Null
 [IO.File]::WriteAllLines((Join-Path $profile 'options.txt'),[string[]]@('onboardAccessibility:false','guiScale:2','renderDistance:3','simulationDistance:5','maxFps:20','soundCategory_master:0.0','fullscreen:false','pauseOnLostFocus:false','tutorialStep:none','skipMultiplayerWarning:true'),[Text.UTF8Encoding]::new($false))
}
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
$mediaProcesses=@()
try {
 foreach($role in @('mediaOwner','mediaPeer')){
  # Loom shares its generated launch configuration; wait until the host has consumed it.
  if($role -eq 'mediaPeer'){
   while(!(Test-Path -LiteralPath (Join-Path $editorOutput 'connection.json'))){
    Register-EditorProcesses
    if($mediaProcesses[0].HasExited){throw 'Host ended before becoming ready.'}
    if((Get-Date) -gt $editorDeadline){throw 'Host startup reached the total timeout.'}
    Start-Sleep -Milliseconds 500
   }
  }
  $arguments='/d','/c',('gradlew.bat --no-daemon --console=plain runClientGameTest -Pcanvasdesk_test_role='+$role)
  $p=Start-Process -FilePath $env:ComSpec -ArgumentList $arguments -WorkingDirectory $editorRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $editorOutput ($role+'-gradle.log')) -RedirectStandardError (Join-Path $editorOutput ($role+'-stderr.log'))
  $mediaProcesses+=$p
  $current=Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)"
  $editorTracked[$p.Id]=[pscustomobject]@{id=$p.Id;parent=$PID;name='cmd.exe';created=([datetime]$current.CreationDate).ToUniversalTime().ToString('o')}
 }
 do {
  Register-EditorProcesses
  foreach($p in $mediaProcesses){if($p.HasExited){$p.WaitForExit()};if($p.HasExited -and $p.ExitCode -ne 0){throw 'A media client failed; inspect its Gradle log.'}}
  if((Get-Date) -gt $editorDeadline){throw "Two-client smoke exceeded its total $TimeoutSeconds-second runtime limit."}
  Start-Sleep -Milliseconds 500
 } while(@($mediaProcesses | Where-Object {!$_.HasExited}).Count -gt 0)
 foreach($role in @('mediaOwner','mediaPeer')){if((Get-Content -LiteralPath (Join-Path $editorOutput ($role+'.log')) -Raw).Trim() -ne 'PASS'){throw 'Missing real client PASS.'}}
 $summary=Get-Content -LiteralPath (Join-Path $editorOutput 'summary.json') -Raw | ConvertFrom-Json
 if($summary.status -ne 'PASS' -or $summary.actualClients -ne 2){throw 'Two-client assertions did not pass.'}
 Write-Output 'PASS: two real clients and browser sessions.'
} catch {$editorFailure=$_.Exception.Message;throw}
finally {
    Register-EditorProcesses
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
