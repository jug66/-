. "$PSScriptRoot\environment.ps1"
Push-Location (Join-Path $projectRoot 'frontend')
try {
    if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) { throw 'Node.js 20.19+ or 22.12+ is required on PATH.' }
    if (-not (Test-Path node_modules)) { Invoke-Checked { npm.cmd ci --no-fund } }
    Invoke-Checked { npm.cmd run dev }
} finally { Pop-Location }
