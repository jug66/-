$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Use-ProjectJava {
    $bundled = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools') -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -Like 'jdk-21*' | Select-Object -First 1
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath "$env:JAVA_HOME\bin\javac.exe")) {
        $version = & "$env:JAVA_HOME\bin\javac.exe" -version 2>&1 | Out-String
        if ($version -notmatch 'javac 21\.') { $env:JAVA_HOME = $null }
    }
    if (-not $env:JAVA_HOME -and $bundled) { $env:JAVA_HOME = $bundled.FullName }
    if (-not $env:JAVA_HOME) { throw 'Java 21 JDK is required. Set JAVA_HOME to your Java 21 directory.' }
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    if (-not (Get-Command mvn.cmd -ErrorAction SilentlyContinue)) { throw 'Maven 3.6.3+ is required on PATH.' }
}

function Invoke-Checked {
    param([scriptblock]$Command)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "Command failed with exit code $LASTEXITCODE" }
}
