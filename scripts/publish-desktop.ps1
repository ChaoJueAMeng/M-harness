#Requires -Version 5.1
<#
.SYNOPSIS
    打包 WinUI 桌面端：自包含 .NET、server jar、jlink JRE，并可选生成 Setup.exe。

.DESCRIPTION
    不要用 MSIX：Agent 要在用户任选的 git 仓库里读写文件、跑 Shell，沙箱会拦住 Java 子进程。
    本脚本产出绿色 zip；若已安装 Inno Setup 6，再编译成每用户安装包。

.PARAMETER SkipJre
    不捆绑 JRE。安装后的机器仍需 JDK 21。

.PARAMETER SkipInstaller
    只发布 app 目录和 zip，不调用 ISCC。
#>
param(
    [switch] $SkipJre,
    [switch] $SkipInstaller
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Dist = Join-Path $Root "dist"
$AppDir = Join-Path $Dist "app"
$Version = "0.1.0"
$Jar = Join-Path $Root "agent-server\target\m-harness-server.jar"
$Csproj = Join-Path $Root "agent-desktop\MHarness.Desktop.csproj"
$Iss = Join-Path $Root "installer\m-harness.iss"

function Get-JdkHome {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $java = Join-Path $env:JAVA_HOME "bin\java.exe"
        $jlink = Join-Path $env:JAVA_HOME "bin\jlink.exe"
        if ((Test-Path $java) -and (Test-Path $jlink)) {
            return $env:JAVA_HOME
        }
    }
    $jdks = Join-Path $env:USERPROFILE ".jdks"
    if (-not (Test-Path $jdks)) {
        throw "找不到带 jlink 的 JDK 21。请设置 JAVA_HOME，例如 C:\Users\$env:USERNAME\.jdks\ms-21.0.12"
    }
    $dirs = @(Get-ChildItem $jdks -Directory)
    $preferred = @($dirs | Where-Object { $_.Name -match '(^|[-_])21(\.|$)' } | Sort-Object Name -Descending)
    $fallback = @($dirs | Sort-Object Name -Descending)
    foreach ($dir in ($preferred + $fallback)) {
        $java = Join-Path $dir.FullName "bin\java.exe"
        $jlink = Join-Path $dir.FullName "bin\jlink.exe"
        if ((Test-Path $java) -and (Test-Path $jlink)) {
            return $dir.FullName
        }
    }
    throw "找不到带 jlink 的 JDK 21。请设置 JAVA_HOME，例如 C:\Users\$env:USERNAME\.jdks\ms-21.0.12"
}

function Get-JreModules([string] $JdkHome, [string] $JarPath) {
    $jdeps = Join-Path $JdkHome "bin\jdeps.exe"
    $detected = ""
    if (Test-Path $jdeps) {
        try {
            $detected = (& $jdeps --multi-release 21 --print-module-deps --ignore-missing-deps $JarPath 2>$null | Select-Object -Last 1)
        }
        catch {
            $detected = ""
        }
    }
    $required = @(
        "java.base",
        "java.logging",
        "java.xml",
        "java.naming",
        "java.net.http",
        "java.management",
        "java.security.sasl",
        "jdk.crypto.ec",
        "jdk.crypto.cryptoki",
        "jdk.httpserver",
        "jdk.unsupported",
        "jdk.zipfs",
        "jdk.charsets",
        "jdk.localedata"
    )
    $modules = New-Object "System.Collections.Generic.HashSet[string]"
    foreach ($name in $required) {
        [void]$modules.Add($name)
    }
    if (-not [string]::IsNullOrWhiteSpace($detected)) {
        foreach ($name in ($detected -split ",")) {
            $trim = $name.Trim()
            if ($trim.Length -gt 0 -and $trim -notmatch "not found") {
                [void]$modules.Add($trim)
            }
        }
    }
    return (($modules | Sort-Object) -join ",")
}

function Find-Iscc {
    $programFilesX86 = [Environment]::GetFolderPath("ProgramFilesX86")
    $paths = @(
        (Join-Path $env:LOCALAPPDATA "Programs\Inno Setup 6\ISCC.exe"),
        (Join-Path $env:LOCALAPPDATA "Programs\Inno Setup 7\ISCC.exe"),
        (Join-Path $programFilesX86 "Inno Setup 6\ISCC.exe"),
        (Join-Path $programFilesX86 "Inno Setup 7\ISCC.exe"),
        (Join-Path ${env:ProgramFiles} "Inno Setup 6\ISCC.exe"),
        (Join-Path ${env:ProgramFiles} "Inno Setup 7\ISCC.exe")
    )
    foreach ($path in $paths) {
        if (Test-Path $path) {
            return $path
        }
    }
    $cmd = Get-Command iscc -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }
    return $null
}

Write-Host "==> 打包 agent-server"
$mvnw = Join-Path $Root "mvnw.cmd"
& $mvnw -f (Join-Path $Root "pom.xml") -pl agent-server -am package -q
if ($LASTEXITCODE -ne 0) {
    throw "Maven 打包失败，退出码 $LASTEXITCODE"
}
if (-not (Test-Path $Jar)) {
    throw "未生成 $Jar"
}

if (Test-Path $Dist) {
    Remove-Item $Dist -Recurse -Force
}
New-Item -ItemType Directory -Path $AppDir | Out-Null

Write-Host "==> dotnet publish（自包含 win-x64）"
dotnet publish $Csproj -c Release -p:Platform=x64 -p:RuntimeIdentifier=win-x64 --self-contained true -o $AppDir
if ($LASTEXITCODE -ne 0) {
    throw "dotnet publish 失败，退出码 $LASTEXITCODE"
}

$publishedJar = Join-Path $AppDir "m-harness-server.jar"
if (-not (Test-Path $publishedJar)) {
    Copy-Item $Jar $publishedJar
}

if (-not $SkipJre) {
    $jdkHome = Get-JdkHome
    $jreDir = Join-Path $AppDir "jre"
    if (Test-Path $jreDir) {
        Remove-Item $jreDir -Recurse -Force
    }
    $modules = Get-JreModules $jdkHome $Jar
    Write-Host "==> jlink JRE  ($modules)"
    $jlink = Join-Path $jdkHome "bin\jlink.exe"
    & $jlink `
        --module-path (Join-Path $jdkHome "jmods") `
        --add-modules $modules `
        --strip-debug `
        --no-man-pages `
        --no-header-files `
        --compress=2 `
        --output $jreDir
    if ($LASTEXITCODE -ne 0) {
        throw "jlink 失败，退出码 $LASTEXITCODE"
    }
}

$zip = Join-Path $Dist "M-Bot-$Version-win-x64.zip"
if (Test-Path $zip) {
    Remove-Item $zip -Force
}
Copy-Item (Join-Path $Root "installer\Install.ps1") (Join-Path $AppDir "Install.ps1") -Force
Copy-Item (Join-Path $Root "installer\Uninstall.ps1") (Join-Path $AppDir "Uninstall.ps1") -Force

Write-Host "==> zip  $zip"
Compress-Archive -Path (Join-Path $AppDir "*") -DestinationPath $zip -CompressionLevel Optimal

$setup = $null
if (-not $SkipInstaller) {
    $iscc = Find-Iscc
    if ($null -eq $iscc) {
        Write-Warning "未找到 Inno Setup 6（ISCC.exe）。已生成绿色版 zip。安装 Inno Setup 后重新运行本脚本可得到 Setup.exe：https://jrsoftware.org/isinfo.php"
    }
    else {
        Write-Host "==> Inno Setup  $iscc"
        & $iscc /DMyAppVersion=$Version $Iss
        if ($LASTEXITCODE -ne 0) {
            throw "ISCC 编译失败，退出码 $LASTEXITCODE"
        }
        $setup = Join-Path $Dist "M-Bot-Setup-$Version.exe"
    }
}

Write-Host ""
Write-Host "完成。产物："
Write-Host "  可运行目录: $AppDir"
Write-Host "  绿色版 zip: $zip"
if ($setup -and (Test-Path $setup)) {
    Write-Host "  安装包:     $setup"
}
Write-Host ""
Write-Host "安装后从开始菜单打开即可，不必再装 JDK / .NET SDK。"
Write-Host "Agent 模式仍需要本机 Git（工作区必须是 git 仓库）。"
