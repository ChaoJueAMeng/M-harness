#Requires -Version 5.1
<#
.SYNOPSIS
    卸载当前用户安装的 Meng Bot（开始菜单快捷方式、注册表、程序目录）。
#>
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

Get-Process -Name "MHarness.Desktop" -ErrorAction SilentlyContinue | ForEach-Object {
    $_.CloseMainWindow() | Out-Null
    Start-Sleep -Milliseconds 400
    if (-not $_.HasExited) {
        Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
    }
}

$dest = Join-Path $env:LOCALAPPDATA "Programs\Meng Bot"
$legacyDests = @(
    (Join-Path $env:LOCALAPPDATA "Programs\M Bot"),
    (Join-Path $env:LOCALAPPDATA "Programs\M-harness")
)
$programs = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"
$lnk = Join-Path $programs "Meng Bot.lnk"
$legacyLnks = @(
    (Join-Path $programs "M Bot.lnk"),
    (Join-Path $programs "M-harness.lnk")
)
$key = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Meng-Bot"
$legacyKeys = @(
    "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\M-Bot",
    "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\M-harness"
)

Remove-Item $key -Recurse -Force -ErrorAction SilentlyContinue
foreach ($legacyKey in $legacyKeys) {
    Remove-Item $legacyKey -Recurse -Force -ErrorAction SilentlyContinue
}
Remove-Item $lnk -Force -ErrorAction SilentlyContinue
foreach ($legacyLnk in $legacyLnks) {
    Remove-Item $legacyLnk -Force -ErrorAction SilentlyContinue
}

function Remove-InstallDir([string] $path) {
    if (-not (Test-Path $path)) {
        return
    }
    $runningFromDest = $false
    if ($PSScriptRoot) {
        $hereFull = [System.IO.Path]::GetFullPath($PSScriptRoot).TrimEnd("\")
        $destFull = [System.IO.Path]::GetFullPath($path).TrimEnd("\")
        $runningFromDest = $hereFull -eq $destFull
    }
    if ($runningFromDest) {
        $parent = Split-Path $path -Parent
        Start-Process -FilePath "cmd.exe" -ArgumentList "/c ping 127.0.0.1 -n 2 >nul & rmdir /s /q `"$path`"" -WorkingDirectory $parent -WindowStyle Hidden
    }
    else {
        Remove-Item $path -Recurse -Force
    }
}

Remove-InstallDir $dest
foreach ($legacyDest in $legacyDests) {
    Remove-InstallDir $legacyDest
}

Write-Host "已卸载 Meng Bot。"
