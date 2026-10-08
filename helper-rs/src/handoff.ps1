$ErrorActionPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Runtime.WindowsRuntime, UIAutomationClient, UIAutomationTypes
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | ? { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select -First 1
function Await($op, [Type]$t) { $k = $asTask.MakeGenericMethod($t).Invoke($null, @($op)); $k.Wait(4000) | Out-Null; $k.Result }
[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime] | Out-Null
$m = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
$paused = $false
foreach ($s in $m.GetSessions()) {
  $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
  $tl = $s.GetTimelineProperties(); $st = $s.GetPlaybackInfo().PlaybackStatus
  $pos = $tl.Position.TotalSeconds
  if ($st -eq 'Playing') { $pos += ([DateTimeOffset]::Now - $tl.LastUpdatedTime).TotalSeconds }
  'S' + [char]9 + $s.SourceAppUserModelId + [char]9 + $st + [char]9 + [math]::Round($pos, 1) + [char]9 + $p.Title + [char]9 + $p.Artist
  if (PAUSE -and -not $paused -and $st -eq 'Playing') { $s.TryPauseAsync() | Out-Null; $paused = $true }
}
foreach ($b in Get-Process chrome, msedge, brave, opera, vivaldi | ? { $_.MainWindowHandle -ne 0 }) {
  $root = [System.Windows.Automation.AutomationElement]::FromHandle($b.MainWindowHandle)
  $c = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Edit)
  $e = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $c)
  if ($e) { $v = $e.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).Current.Value; if ($v) { 'U' + [char]9 + $b.ProcessName + [char]9 + $b.MainWindowTitle + [char]9 + $v } }
}
foreach ($b in Get-Process firefox | ? { $_.MainWindowHandle -ne 0 }) {
  $root = [System.Windows.Automation.AutomationElement]::FromHandle($b.MainWindowHandle)
  $c = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::AutomationIdProperty, 'urlbar-input')
  $e = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $c)
  if ($e) { $v = $e.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).Current.Value; if ($v) { 'U' + [char]9 + 'firefox' + [char]9 + $b.MainWindowTitle + [char]9 + $v } }
}
