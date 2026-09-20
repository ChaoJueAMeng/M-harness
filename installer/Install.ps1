#Requires -Version 5.1
<#
.SYNOPSIS
    把当前目录的 Meng Bot 安装到当前用户（开始菜单 + 设置中的应用）。
#>
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$here = $PSScriptRoot
$exeName = "MHarness.Desktop.exe"
$srcExe = Join-Path $here $exeName
$srcJar = Join-Path $here "m-harness-server.jar"
if (-not (Test-Path $srcExe) -or -not (Test-Path $srcJar)) {
    throw "请在解压后的 Meng Bot 目录里运行本脚本（需有 MHarness.Desktop.exe 和 m-harness-server.jar）。"
}

$dest = Join-Path $env:LOCALAPPDATA "Programs\Meng Bot"
$legacyDests = @(
    (Join-Path $env:LOCALAPPDATA "Programs\M Bot"),
    (Join-Path $env:LOCALAPPDATA "Programs\M-harness")
)
$destExe = Join-Path $dest $exeName
$hereFull = [System.IO.Path]::GetFullPath($here).TrimEnd("\")
$destFull = [System.IO.Path]::GetFullPath($dest).TrimEnd("\")

Get-Process -Name "MHarness.Desktop" -ErrorAction SilentlyContinue | ForEach-Object {
    $_.CloseMainWindow() | Out-Null
    Start-Sleep -Milliseconds 400
    if (-not $_.HasExited) {
        Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
    }
}

if ($hereFull -ne $destFull) {
    if (Test-Path $dest) {
        Remove-Item $dest -Recurse -Force
    }
    New-Item -ItemType Directory -Path $dest | Out-Null
    Copy-Item -Path (Join-Path $here "*") -Destination $dest -Recurse -Force
}

$programs = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"
if (-not (Test-Path $programs)) {
    New-Item -ItemType Directory -Path $programs | Out-Null
}
$lnk = Join-Path $programs "Meng Bot.lnk"
$wsh = New-Object -ComObject WScript.Shell
$shortcut = $wsh.CreateShortcut($lnk)
$shortcut.TargetPath = $destExe
$shortcut.WorkingDirectory = $dest
$shortcut.Description = "Meng Bot"
$shortcut.Save()

$uninstallPs1 = Join-Path $dest "Uninstall.ps1"
$key = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Meng-Bot"
New-Item $key -Force | Out-Null
$props = @{
    DisplayName     = "Meng Bot"
    DisplayVersion  = "0.1.0"
    Publisher       = "Meng Bot"
    InstallLocation = $dest
    DisplayIcon     = $destExe
    UninstallString = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$uninstallPs1`""
}
foreach ($name in $props.Keys) {
    New-ItemProperty -Path $key -Name $name -Value $props[$name] -Force | Out-Null
}
New-ItemProperty -Path $key -Name NoModify -Value 1 -PropertyType DWord -Force | Out-Null
New-ItemProperty -Path $key -Name NoRepair -Value 1 -PropertyType DWord -Force | Out-Null
$sizeKb = [int]((Get-ChildItem $dest -Recurse -File | Measure-Object Length -Sum).Sum / 1KB)
New-ItemProperty -Path $key -Name EstimatedSize -Value $sizeKb -PropertyType DWord -Force | Out-Null

Remove-Item (Join-Path $programs "M Bot.lnk") -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $programs "M-harness.lnk") -Force -ErrorAction SilentlyContinue
Remove-Item "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\M-Bot" -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\M-harness" -Recurse -Force -ErrorAction SilentlyContinue
foreach ($legacyDest in $legacyDests) {
    $legacyFull = [System.IO.Path]::GetFullPath($legacyDest).TrimEnd("\")
    if ((Test-Path $legacyDest) -and ($legacyFull -ne $destFull) -and ($legacyFull -ne $hereFull)) {
        Remove-Item $legacyDest -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "已安装到 $dest"
Write-Host "开始菜单中会出现 Meng Bot。也可在 Windows 设置 > 应用 里卸载。"
