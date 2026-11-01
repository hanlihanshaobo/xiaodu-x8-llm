# 安装 Android 构建工具链（JDK + cmdline-tools + platform + build-tools）
# 不装 Android Studio，也不需要 Gradle：直接用 aapt2 + javac + d8 + apksigner 拼 APK。
$ErrorActionPreference = 'Stop'
$root = 'C:\tools'
$sdk  = Join-Path $root 'android-sdk'
$jdkHome = Join-Path $root 'jdk17'

if (-not (Test-Path $jdkHome)) {
    Write-Host '解压 JDK...'
    Expand-Archive -Path "$root\dl\jdk17.zip" -DestinationPath $root -Force
    $inner = Get-ChildItem $root -Directory | Where-Object { $_.Name -like 'jdk-17*' } | Select-Object -First 1
    if ($inner) { Rename-Item $inner.FullName $jdkHome }
}

if (-not (Test-Path "$sdk\cmdline-tools\latest\bin\sdkmanager.bat")) {
    Write-Host '解压 cmdline-tools...'
    New-Item -ItemType Directory -Force -Path "$sdk\cmdline-tools" | Out-Null
    Expand-Archive -Path "$root\dl\cmdline-tools.zip" -DestinationPath "$sdk\cmdline-tools\tmp" -Force
    Move-Item "$sdk\cmdline-tools\tmp\cmdline-tools" "$sdk\cmdline-tools\latest"
    Remove-Item "$sdk\cmdline-tools\tmp" -Recurse -Force
}

$env:JAVA_HOME = $jdkHome
$env:PATH = "$jdkHome\bin;$env:PATH"
$sdkmanager = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"

Write-Host '安装 SDK 组件...'
$yes = ("y`n" * 60)
$yes | & $sdkmanager --sdk_root=$sdk --licenses 2>&1 | Select-Object -Last 3
& $sdkmanager --sdk_root=$sdk "platform-tools" "platforms;android-33" "build-tools;33.0.2"

Write-Host ''
Write-Host '=== 结果 ==='
Get-ChildItem "$sdk\build-tools","$sdk\platforms" -ErrorAction SilentlyContinue | Select-Object Name
& "$jdkHome\bin\java.exe" -version 2>&1 | Select-Object -First 1
