# Puts a built and self-tested PikSeek folder into the project's 80_release, plus a zip and checksums.
#
#   powershell -ExecutionPolicy Bypass -File release.ps1 -Name v1.0.0_20261001_PikSeek
#
# Run createReleaseDistributable and selftest.ps1 first. Refuses to overwrite an existing release folder.
# Keep this file ASCII: Windows PowerShell reads scripts without a BOM in the legacy code page.
param(
    [Parameter(Mandatory = $true)][string]$Name,
    [string]$Version = '1.0.0'
)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$source = Join-Path $project '_runtime\pikseek\b\desktopApp\compose\binaries\main-release\app\PikSeek'
$release = Join-Path $project ('80_release\' + $Name)
$folder = Join-Path $release 'PikSeek'
$zipName = 'PikSeek_' + $Version + '_win-x64.zip'
$zip = Join-Path $release $zipName

if (-not (Test-Path (Join-Path $source 'PikSeek.exe'))) { throw 'no built app; run createReleaseDistributable first' }
# A data folder next to the exe means the built app was started by hand; it may hold a session.
if (Test-Path (Join-Path $source 'data')) { throw 'the built app folder contains data/; delete it and rebuild' }
if (Test-Path $release) { throw ($release + ' already exists') }

New-Item -ItemType Directory -Force $release | Out-Null
Copy-Item $source $folder -Recurse
# jpackage leaves the launcher read-only, which gets in the way when the folder is moved or deleted later
Get-ChildItem $folder -Recurse -Force -File | Where-Object { $_.IsReadOnly } | ForEach-Object { $_.IsReadOnly = $false }

# Entry names with forward slashes: CreateFromDirectory on .NET Framework writes backslashes,
# which other unzip tools do not all accept.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [IO.File]::Open($zip, [IO.FileMode]::CreateNew)
$archive = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create)
$prefix = $release.Length + 1
try {
    foreach ($file in (Get-ChildItem $folder -Recurse -Force -File | Sort-Object FullName)) {
        $entry = $file.FullName.Substring($prefix).Replace([char]92, [char]47)
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $entry, [IO.Compression.CompressionLevel]::Optimal)
    }
} finally {
    $archive.Dispose()
    $stream.Dispose()
}

$zipHash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
$exeHash = (Get-FileHash (Join-Path $folder 'PikSeek.exe') -Algorithm SHA256).Hash.ToLower()
$sums = $zipHash + '  ' + $zipName + "`n" + $exeHash + '  PikSeek/PikSeek.exe' + "`n"
[IO.File]::WriteAllText((Join-Path $release 'SHA256.txt'), $sums, (New-Object Text.UTF8Encoding $false))

$files = Get-ChildItem $folder -Recurse -Force -File
Write-Output ('folder: ' + $files.Count + ' files, ' + [math]::Round(($files | Measure-Object Length -Sum).Sum / 1MB, 1) + ' MB')
Write-Output ('zip:    ' + [math]::Round((Get-Item $zip).Length / 1MB, 1) + ' MB, sha256 ' + $zipHash)
Write-Output ('in:     ' + $release)
