# The application started after the computer starts, no logon needed: a task of the Task Scheduler
# running run.bat as the user who registers it — without its password kept (S4U), in the background,
# no window; the log is in %LOCALAPPDATA%\recommend4me\app.log, the application stopped by "Exit" of
# its menu. A minute after the start, for the network (Tailscale) to come up first; restarted up to
# three times a minute apart when it fails to start.
#
# Run once, in PowerShell started as an administrator (a task that runs with nobody logged on asks
# for it): powershell -ExecutionPolicy Bypass -File tools\autostart.ps1
# Again to update the task; -Remove to remove it. Tailscale itself keeps the computer in the network
# with nobody logged on only when it runs unattended: -Tailscale sets that too.
param([switch]$Remove, [switch]$Tailscale)

$name = 'recommend4me'
$root = Split-Path -Parent $PSScriptRoot

if ($Remove) {
    Unregister-ScheduledTask -TaskName $name -Confirm:$false
    "The task $name is removed"
    return
}

if ($Tailscale) {
    & "$env:ProgramFiles\Tailscale\tailscale.exe" set --unattended
    "Tailscale runs unattended: the computer stays in the network with nobody logged on"
}

$action = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument "/c `"$root\run.bat`" gpu" -WorkingDirectory $root
$trigger = New-ScheduledTaskTrigger -AtStartup
$trigger.Delay = 'PT1M'
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType S4U -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $name -Force -Action $action -Trigger $trigger -Principal $principal -Settings $settings `
    -Description "recommend4me started after the computer starts, no logon needed: $root\run.bat gpu" |
    Select-Object TaskName, State
