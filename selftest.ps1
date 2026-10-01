# 打好的 PikSeek 程序包的自检：真用启动器跑，不联网、不登录。
#   powershell -ExecutionPolicy Bypass -File selftest.ps1 -App <PikSeek 文件夹> [-LongVideo <本机的一个长视频>]
# 各项的结果写在 -Out 目录下（默认是项目的 _runtime/pikseek/t），全部通过时退出码为 0。
# 自检用单独的数据目录（经环境变量 PIKSEEK_DATA_DIR 指定），不碰程序旁的 data。
param(
    [Parameter(Mandatory = $true)][string]$App,
    [string]$Out = '',
    [string]$LongVideo = ''
)
$ErrorActionPreference = 'Continue'
$exe = Join-Path $App 'PikSeek.exe'
if (-not (Test-Path $exe)) { Write-Output "找不到 $exe"; exit 2 }
# 默认值放在这里算：参数表里取不到脚本所在目录
if ($Out -eq '') { $Out = Join-Path $PSScriptRoot '..\..\_runtime\pikseek\t' }
$Out = (Resolve-Path $Out).Path
$data = Join-Path $Out 'selfdata'
if (Test-Path $data) { Get-ChildItem $data -Recurse -Force | Sort-Object FullName -Descending | ForEach-Object { $_.Delete() } }
$env:PIKSEEK_DATA_DIR = $data
$sample = Join-Path $PSScriptRoot 'testdata\media\control-mpeg4-aac.mp4'

$runs = @(
    @{ Test = 'security'; Video = $sample; Label = 'security' },
    @{ Test = 'preview'; Video = $sample; Label = 'preview_sample' },
    @{ Test = 'play'; Video = $sample; Label = 'play' }
)
if ($LongVideo -ne '') { $runs += @{ Test = 'preview'; Video = $LongVideo; Label = 'preview_long' } }

$failed = 0
foreach ($run in $runs) {
    $result = Join-Path $Out ("selftest_" + $run.Label + ".txt")
    if (Test-Path $result) { [IO.File]::Delete($result) }
    $env:PIKSEEK_SELFTEST_PATH = $run.Video
    # 启动器不认命令行里的 -D，经这个环境变量交给 JVM；路径里用正斜杠，免得被当成转义；加引号，路径里可以有空格
    $env:JAVA_TOOL_OPTIONS = "-Dpikseek.selftest=" + $run.Test + ' -Dpikseek.selftest.out="' + $result.Replace([char]92, [char]47) + '"'
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $process = Start-Process -FilePath $exe -PassThru
    if (-not $process.WaitForExit(300000)) { $process.Kill(); Write-Output ("== " + $run.Label + " 超时") }
    Write-Output ("== " + $run.Label + " exit=" + $process.ExitCode + " wall=" + $watch.ElapsedMilliseconds + " ms")
    if ($process.ExitCode -ne 0) { $failed++ }
    if (Test-Path $result) { Get-Content $result -Encoding UTF8 } else { Write-Output '(没有结果文件)' }
}
$env:JAVA_TOOL_OPTIONS = $null
Write-Output ("程序目录里有没有 data：" + (Test-Path (Join-Path $App 'data')))
exit $failed
