param(
    [string]$Name = '你的姓名'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$outputDir = Join-Path $projectRoot 'output'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$safeName = ($Name -replace '[\\/:*?"<>|]', '_').Trim()
if ([string]::IsNullOrWhiteSpace($safeName)) { $safeName = '你的姓名' }
$archive = Join-Path $outputDir ("雪哈游-全栈-笔试题2-{0}-源码.zip" -f $safeName)
if (Test-Path $archive) { Remove-Item -Force $archive }

if (-not (Get-Command tar.exe -ErrorAction SilentlyContinue)) {
    throw '未找到 tar.exe。Windows 10/11 通常自带；也可以安装 Git for Windows 后重试。'
}
Push-Location $projectRoot
try {
    $tarArgs = @('-a', '-c', '-f', $archive)
    $tarArgs += @(
        'START_HERE.md',
        'PROJECT_OVERVIEW.md',
        '.env.example',
        'compose.yml',
        'backend/pom.xml',
        'backend/src',
        'frontend/index.html',
        'frontend/package.json',
        'frontend/package-lock.json',
        'frontend/tsconfig.json',
        'frontend/vite.config.ts',
        'frontend/public',
        'frontend/src',
        'frontend/tests',
        'scripts/environment.ps1',
        'scripts/start-backend.ps1',
        'scripts/start-backend.sh',
        'scripts/start-frontend.ps1',
        'scripts/start-frontend.sh',
        'scripts/stop-backend.ps1',
        'scripts/stop-backend.sh',
        'scripts/test.ps1'
    )
    & tar.exe @tarArgs
    if ($LASTEXITCODE -gt 1) { throw "tar 打包失败，退出码 $LASTEXITCODE。" }
} finally { Pop-Location }
$item = Get-Item $archive
Write-Host ("源码包已生成：{0} ({1:N1} MB)" -f $item.FullName, ($item.Length / 1MB))
Write-Host '压缩包只包含：源代码、必要配置、启动脚本、START_HERE.md 和 PROJECT_OVERVIEW.md。'
