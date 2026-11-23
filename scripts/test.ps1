param([switch]$CleanInstall)
. "$PSScriptRoot\environment.ps1"
Use-ProjectJava
Push-Location (Join-Path $projectRoot 'backend')
try { Invoke-Checked { mvn.cmd -B verify } } finally { Pop-Location }
Push-Location (Join-Path $projectRoot 'frontend')
try {
    if ($CleanInstall -or -not (Test-Path node_modules)) {
        Invoke-Checked { npm.cmd ci --no-fund }
    }
    Invoke-Checked { npm.cmd run build }
    Invoke-Checked { npm.cmd test }
} finally { Pop-Location }
