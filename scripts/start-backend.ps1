param([switch]$ExternalDatabase)
. "$PSScriptRoot\environment.ps1"
Use-ProjectJava
Push-Location (Join-Path $projectRoot 'backend')
try {
    if ($ExternalDatabase) {
        Invoke-Checked { mvn.cmd -B -DskipTests package }
        & "$env:JAVA_HOME\bin\java.exe" -jar target/message-board-1.0.0.jar
    } else {
        Write-Host 'Starting local PostgreSQL (127.0.0.1:55432) and API (127.0.0.1:8080). Ctrl+C stops them.'
        Invoke-Checked { mvn.cmd -B test-compile dependency:build-classpath '-Dmdep.outputFile=target/dev-classpath.txt' '-DincludeScope=test' }
        $dependencies = (Get-Content target/dev-classpath.txt -Raw).Trim()
        & "$env:JAVA_HOME\bin\java.exe" -cp "target/test-classes;target/classes;$dependencies" dev.messageboard.DevApplication
    }
} finally { Pop-Location }
