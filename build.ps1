$ErrorActionPreference = 'Stop'

# 工程根目录（脚本所在目录）
$PROJ = $PSScriptRoot

# Android SDK：优先用环境变量，其次常见安装位置
$SDK = $env:ANDROID_HOME
if (-not $SDK) { $SDK = $env:ANDROID_SDK_ROOT }
if (-not $SDK) { $SDK = 'C:\Android\Sdk' }
if (-not (Test-Path -LiteralPath $SDK)) { throw "找不到 Android SDK，请设置 ANDROID_HOME" }

# build-tools 取版本号最大的一个
$BT = Get-ChildItem -LiteralPath "$SDK\build-tools" -Directory |
      Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $BT) { throw "SDK 里没有 build-tools" }

# 平台 jar：优先 android-34，没有就用最新的
$AJ = "$SDK\platforms\android-34\android.jar"
if (-not (Test-Path -LiteralPath $AJ)) {
    $p = Get-ChildItem -LiteralPath "$SDK\platforms" -Directory |
         Sort-Object { [version]($_.Name -replace 'android-', '') } -Descending |
         Select-Object -First 1 -ExpandProperty FullName
    $AJ = "$p\android.jar"
}

$OUT = "$PROJ\build"
$APK = "$PROJ\mi_fit_export.apk"

if (Test-Path -LiteralPath $OUT) { Remove-Item -LiteralPath $OUT -Recurse -Force }
New-Item -ItemType Directory -Force "$OUT\classes" | Out-Null
New-Item -ItemType Directory -Force "$OUT\dex" | Out-Null

# 1) 资源的二进制编译（图标等）
& "$BT\aapt2.exe" compile --dir "$PROJ\res" -o "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

# 1b) 资源/清单打包
& "$BT\aapt2.exe" link -o "$OUT\base.apk" --manifest "$PROJ\AndroidManifest.xml" `
    -I "$AJ" -A "$PROJ\assets" "$OUT\res.zip" --java "$OUT\gen" --min-sdk-version 26 --target-sdk-version 34 --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

# 2) 编译 Java
$srcs = Get-ChildItem -LiteralPath "$PROJ\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcs += (Get-ChildItem -LiteralPath "$OUT\gen" -Recurse -Filter R.java | ForEach-Object { $_.FullName })
$srcList = "$OUT\srcs.txt"
$srcs | Set-Content -LiteralPath $srcList -Encoding utf8
& javac -source 11 -target 11 -encoding UTF-8 -classpath "$AJ" -d "$OUT\classes" "@$srcList" 2>&1 | Where-Object { $_ -notmatch 'bootstrap class path|source value|target value|warning' }
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

# 3) dex（先把 class 打进 jar，避免 d8 命令行过长）
$jar = "$OUT\classes.jar"
& jar cf "$jar" -C "$OUT\classes" .
& "$BT\d8.bat" --release --min-api 26 --lib "$AJ" --output "$OUT\dex" "$jar"
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

# 4) 合包
Copy-Item -LiteralPath "$OUT\base.apk" -Destination "$OUT\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$OUT\unsigned.apk", 'Update')
$entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::NoCompression)
$src  = [System.IO.File]::OpenRead("$OUT\dex\classes.dex")
$dst  = $entry.Open()
$src.CopyTo($dst)
$dst.Close(); $src.Close(); $zip.Dispose()

# 5) 对齐+签名
& "$BT\zipalign.exe" -f 4 "$OUT\unsigned.apk" "$OUT\aligned.apk"
$ks = "$PROJ\debug.keystore"
if (-not (Test-Path -LiteralPath $ks)) {
    & keytool -genkeypair -keystore $ks -alias mfit -storepass android -keypass android `
        -dname "CN=hanxing, OU=mfit, O=mfit, L=BT, S=NM, C=CN" -keyalg RSA -keysize 2048 -validity 10000 2>&1 | Out-Null
}
& "$BT\apksigner.bat" sign --ks "$ks" --ks-pass pass:android --key-pass pass:android `
    --out "$APK" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "签名失败" }

Get-Item -LiteralPath $APK | Select-Object Name, Length
