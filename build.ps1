#requires -Version 5.1
$ErrorActionPreference = 'Stop'

# 零 Gradle 构建：javac -> d8 -> aapt2 -> zipalign -> apksigner
# PS 5.1 会把非 ASCII 参数传给原生 exe 时乱码，因此先复制到 ASCII 临时目录构建，再回拷产物。
$root = $PSScriptRoot
if ([string]::IsNullOrEmpty($root)) {
    $root = (Get-Location).Path
}
$sdk = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

$bt = Join-Path $sdk 'build-tools\35.0.0'
$androidJar = Join-Path $sdk 'platforms\android-35\android.jar'
foreach ($p in @($bt, $androidJar)) {
    if (-not (Test-Path $p)) { throw "Missing build dependency: $p" }
}

$version = '1.1'
$apkName = "ColorInversionFix-LSPosed-v$version.apk"

$javac = (Get-Command javac).Source
$d8 = Join-Path $bt 'd8.bat'
$apksigner = Join-Path $bt 'apksigner.bat'
$aapt2 = Join-Path $bt 'aapt2.exe'
$zipalign = Join-Path $bt 'zipalign.exe'
$keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
if (-not $keytool) { $keytool = 'keytool' }

$stage = Join-Path $env:TEMP 'color_inversion_fix_build'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage, "$stage\build" | Out-Null

Copy-Item (Join-Path $root 'AndroidManifest.xml') $stage -Force
foreach ($d in 'src', 'stubs', 'res', 'assets') {
    Copy-Item (Join-Path $root $d) $stage -Recurse -Force
}

$build = "$stage\build"
New-Item -ItemType Directory -Force -Path "$build\stubs", "$build\classes", "$build\dex" | Out-Null

$ksSrc = Join-Path $root 'debug.keystore'
$ksDst = Join-Path $stage 'debug.keystore'
if (Test-Path $ksSrc) { Copy-Item $ksSrc $ksDst -Force }

Push-Location $stage
try {
    Write-Host '[1/9] javac stubs'
    $stubFiles = @(Get-ChildItem 'stubs' -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    & $javac --release 11 -encoding UTF-8 -d 'build\stubs' @stubFiles
    if ($LASTEXITCODE -ne 0) { throw 'javac stubs failed' }

    Write-Host '[2/9] javac module'
    $srcFiles = @(Get-ChildItem 'src' -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    & $javac --release 11 -encoding UTF-8 -cp "build\stubs;$androidJar" -d 'build\classes' @srcFiles
    if ($LASTEXITCODE -ne 0) { throw 'javac module failed' }

    Write-Host '[3/9] d8 -> classes.dex'
    $classFiles = @(Get-ChildItem 'build\classes' -Recurse -Filter *.class | ForEach-Object { $_.FullName })
    & $d8 --min-api 29 --lib "$androidJar" --classpath 'build\stubs' --output 'build\dex' @classFiles
    if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

    Write-Host '[4/9] aapt2 compile/link'
    & $aapt2 compile --dir res -o build\res.zip
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
    & $aapt2 link -o build\base.apk -I "$androidJar" `
        --manifest AndroidManifest.xml -A assets `
        --min-sdk-version 29 --target-sdk-version 29 build\res.zip
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

    Write-Host '[5/9] add classes.dex'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open("$build\base.apk", 'Update')
    try {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, "$build\dex\classes.dex", 'classes.dex',
            [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    } finally {
        $zip.Dispose()
    }

    Write-Host '[6/9] zipalign'
    & $zipalign -f -p 4 build\base.apk build\aligned.apk
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

    Write-Host '[7/9] keystore'
    if (-not (Test-Path 'debug.keystore')) {
        & $keytool -genkeypair -keystore debug.keystore -alias colorfix -keyalg RSA -keysize 2048 `
            -validity 10000 -storepass android -keypass android `
            -dname 'CN=ColorInversionFix,OU=Local,O=Local,C=CN' -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
    }

    Write-Host '[8/9] apksigner sign/verify'
    & $apksigner sign --ks debug.keystore --ks-key-alias colorfix --ks-pass pass:android --key-pass pass:android `
        --min-sdk-version 29 --v1-signing-enabled true --v2-signing-enabled true `
        --out "build\$apkName" build\aligned.apk
    if ($LASTEXITCODE -ne 0) { throw 'apksigner sign failed' }
    & $apksigner verify --print-certs "build\$apkName"
    if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }

    Write-Host '[9/9] inspect apk'
    & $aapt2 dump badging "build\$apkName" | Select-String -Pattern "package:|sdkVersion|targetSdkVersion|application-label|launchable-activity"
    $zip = [System.IO.Compression.ZipFile]::OpenRead("$build\$apkName")
    try {
        $names = $zip.Entries | ForEach-Object { $_.FullName }
        foreach ($need in @('AndroidManifest.xml', 'classes.dex', 'assets/xposed_init')) {
            if ($names -notcontains $need) { throw "APK missing $need" }
        }
        Write-Host ("APK entries OK: " + ($names -join ', '))
    } finally {
        $zip.Dispose()
    }
} finally {
    Pop-Location
}

if (-not (Test-Path $ksSrc) -and (Test-Path $ksDst)) {
    Copy-Item $ksDst $ksSrc -Force
}

$outDir = [System.IO.Path]::GetFullPath((Join-Path $root '..\..\outputs'))
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$final = Join-Path $outDir $apkName
Copy-Item "$build\$apkName" $final -Force

Write-Host ''
Write-Host "BUILD OK: $final"
Write-Host "SIZE: $((Get-Item $final).Length) bytes"
