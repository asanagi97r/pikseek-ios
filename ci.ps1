# Pushes the current commit, waits for the iOS workflow run on GitHub, and fetches its results
# (screenshots, self-test output, build log excerpts) into _runtime/pikseek/ci/.
#
#   powershell -ExecutionPolicy Bypass -File ci.ps1 [-NoPush] [-TimeoutMinutes 50]
#
# The local repository lives in _runtime/pikseek/ios.git with this folder as its work tree.
# Keep this file ASCII: Windows PowerShell reads scripts without a BOM in the legacy code page.
param([switch]$NoPush, [int]$TimeoutMinutes = 50)
$ErrorActionPreference = 'Continue'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$gitDir = Join-Path $project '_runtime\pikseek\ios.git'
$results = Join-Path $project '_runtime\pikseek\ci'
$env:GIT_TERMINAL_PROMPT = '0'
$env:GCM_INTERACTIVE = 'never'

function Git-Run { & git "--git-dir=$gitDir" @args }

$sha = (Git-Run rev-parse HEAD).Trim()
if (-not $NoPush) {
    Git-Run push -q origin main
    if ($LASTEXITCODE -ne 0) { Write-Output 'push failed'; exit 2 }
}
Write-Output ("waiting for run of " + $sha.Substring(0, 7))

$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$done = $false
$runners = @('macos-latest', 'macos-15-intel')
$winner = ''
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 30
    # Each runner force-pushes its results to its own branch, with the commit it ran for in the message.
    foreach ($runner in $runners) {
        Git-Run fetch -q -f origin "+refs/heads/ci-results-${runner}:refs/remotes/origin/ci-results-${runner}" 2>$null
        if ($LASTEXITCODE -ne 0) { continue }
        $message = (Git-Run log -1 --format=%s "origin/ci-results-$runner")
        # Prefer the Apple-silicon runner (it also runs the simulator self-test); take the Intel one only if it succeeded
        if ($message -match $sha) {
            $status = (Git-Run show "origin/ci-results-${runner}:RESULT.txt")
            if ($runner -eq 'macos-latest' -or $status -match 'status=success') { $done = $true; $winner = $runner; break }
        }
    }
    if ($done) { break }
}
if (-not $done) { Write-Output 'timed out waiting for results'; exit 3 }

if (Test-Path $results) { [IO.Directory]::Delete($results, $true) }
New-Item -ItemType Directory -Force $results | Out-Null
$archive = Join-Path $results '_results.tar'
Git-Run archive --format=tar -o $archive "origin/ci-results-$winner"
tar -xf $archive -C $results
[IO.File]::Delete($archive)
Get-Content (Join-Path $results 'RESULT.txt')
Get-ChildItem $results | ForEach-Object { Write-Output ('  ' + $_.Name + '  ' + $_.Length) }
