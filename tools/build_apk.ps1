# 构建 APK：aapt2 → javac → d8 → zipalign → apksigner
# 不依赖 Gradle / Android Studio，只用到 cmdline-tools 里的几个命令行工具。
# 说明：aapt2/zipalign 这些原生工具对中文路径支持不好，所以先复制到纯英文目录构建。
param(
    # 编完自动上传到这个 FTP 目录（小度上用 ES 的文件管理器可直接安装）
    [string]$FtpDir = "ftp://<X8_IP>:3721/Download/",
    [switch]$NoUpload
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$sdk  = 'C:\tools\android-sdk'
$jdk  = 'C:\tools\jdk17'
$bt   = Join-Path $sdk 'build-tools\33.0.2'
$androidJar = Join-Path $sdk 'platforms\android-33\android.jar'

$work = 'C:\xdlink-build'
$src  = Join-Path $work 'client'
$dist = Join-Path $root 'dist'
$apkName = 'XiaoduLink.apk'

foreach ($p in @($bt, $androidJar, $jdk)) {
    if (-not (Test-Path $p)) { throw "缺少依赖: $p" }
}

Write-Host '准备工作目录…'
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path $work | Out-Null
Copy-Item (Join-Path $root 'client') $src -Recurse
New-Item -ItemType Directory -Force -Path "$src\build\gen","$src\build\classes","$src\build\dex",$dist | Out-Null

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

Write-Host '1/6 编译资源…'
& "$bt\aapt2.exe" compile --dir "$src\res" -o "$src\build\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

Write-Host '2/6 链接资源与清单…'
& "$bt\aapt2.exe" link -o "$src\build\base.apk" `
    -I $androidJar `
    --manifest "$src\AndroidManifest.xml" `
    -R "$src\build\res.zip" `
    --java "$src\build\gen" `
    --min-sdk-version 26 --target-sdk-version 28 `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

Write-Host '3/6 编译 Java…'
$sources = @()
$sources += (Get-ChildItem -Path "$src\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem -Path "$src\build\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn `
    -classpath $androidJar -d "$src\build\classes" $sources
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

Write-Host '4/6 转 dex…'
$classes = Get-ChildItem -Path "$src\build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
# 直接调 d8 的 jar：d8.bat 里有 -Djava.ext.dirs，JDK 17 已不支持
& "$jdk\bin\java.exe" -cp "$bt\lib\d8.jar" com.android.tools.r8.D8 `
    --lib $androidJar --min-api 26 --output "$src\build\dex" @classes
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

Write-Host '5/6 打包…'
Copy-Item "$src\build\base.apk" "$src\build\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$src\build\unsigned.apk", 'Update')
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, "$src\build\dex\classes.dex", 'classes.dex', 'Optimal') | Out-Null
} finally {
    $zip.Dispose()
}

& "$bt\zipalign.exe" -p -f 4 "$src\build\unsigned.apk" "$src\build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

Write-Host '6/6 签名…'
$ks = 'C:\tools\xdl-debug.keystore'
if (-not (Test-Path $ks)) {
    & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -alias androiddebugkey `
        -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" | Out-Null
}
$out = Join-Path $dist $apkName
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out $out "$src\build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败' }

Write-Host ''
Write-Host "完成: $out"
Get-Item $out | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 2) } }, LastWriteTime

if (-not $NoUpload) {
    Write-Host "上传到 $FtpDir（覆盖同名文件）…"
    & curl.exe -sS --max-time 120 --user "anonymous:" -T $out "$FtpDir$apkName"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "上传失败（curl exit $LASTEXITCODE），固件包仍在 $out" -ForegroundColor Yellow
    } else {
        Write-Host "已上传: $FtpDir$apkName" -ForegroundColor Green
    }
}
