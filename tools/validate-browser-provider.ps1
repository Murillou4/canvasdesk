param([string]$JdkPath,[ValidateRange(5,30)][int]$TimeoutSeconds=25)
$ErrorActionPreference='Stop'
$providerRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$providerOutput=Join-Path $providerRoot ('artifacts\1.4.0\providers\youtube-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $providerOutput -Force | Out-Null
$start=[Diagnostics.ProcessStartInfo]::new()
$start.FileName=Join-Path $JdkPath 'bin\javaw.exe'
$start.UseShellExecute=$false;$start.CreateNoWindow=$true
$start.RedirectStandardInput=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
foreach($argument in @('-Dcanvasdesk.browserSilent=true','--enable-native-access=ALL-UNNAMED','--add-modules=jdk.httpserver','-jar',(Join-Path $providerRoot 'build\browser\canvasdesk-browser.jar'),(Join-Path $providerRoot '.codex-temp\browser-native-152'),$providerOutput,'https://www.youtube.com/watch?v=M7lc1UVf-VE','youtube')){$start.ArgumentList.Add($argument)}
$process=[Diagnostics.Process]::new();$process.StartInfo=$start
$timer=[Diagnostics.Stopwatch]::StartNew();$passed=$false;$errors=@();$lastTelemetry=$null
try {
 $null=$process.Start();$stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
 while($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds -and !$process.HasExited){
  $state=@{mediaSession='provider-probe';mediaRevision=0;navigationRevision=0;playing=$true;position=$timer.Elapsed.TotalSeconds;volume=0;scrollX=0;scrollY=0}
  $process.StandardInput.WriteLine((@{cmd='state';state=$state;editable=$true}|ConvertTo-Json -Compress -Depth 4));$process.StandardInput.Flush()
  $file=Join-Path $providerOutput 'events.jsonl'
  if(Test-Path -LiteralPath $file){foreach($line in @(Get-Content -LiteralPath $file -Tail 50)){try{$event=$line|ConvertFrom-Json}catch{continue};if($event.kind -eq 'error'){$errors+=@($event.message)};if($event.kind -eq 'telemetry'){$lastTelemetry=$event;if($event.ready -and $event.playing -and $event.position -gt 1){$passed=$true}}}}
  if($passed){break};Start-Sleep -Milliseconds 250
 }
} finally {
 if(!$process.HasExited){try{$process.StandardInput.WriteLine('{"cmd":"close"}');$process.StandardInput.Flush()}catch{};if(!$process.WaitForExit(2000)){$process.Kill($true);$process.WaitForExit(2000)|Out-Null}}
 if($stdout){try{[IO.File]::WriteAllText((Join-Path $providerOutput 'stdout.log'),$stdout.GetAwaiter().GetResult())}catch{}}
 if($stderr){try{[IO.File]::WriteAllText((Join-Path $providerOutput 'stderr.log'),$stderr.GetAwaiter().GetResult())}catch{}}
 $result=[pscustomobject]@{status= $(if($passed){'PASS'}elseif($errors.Count){'PROVIDER_ERROR'}else{'TIMEOUT'});timeoutSeconds=$TimeoutSeconds;elapsedSeconds=$timer.Elapsed.TotalSeconds;errors=@($errors|Select-Object -Unique);lastTelemetry=$lastTelemetry;silent=$true;hidden=$true}
 $result|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $providerOutput 'summary.json') -Encoding utf8
 $result|ConvertTo-Json -Depth 5|Write-Output
}
