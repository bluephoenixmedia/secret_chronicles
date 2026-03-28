# setup.ps1 - Downloads the Gradle wrapper JAR so gradlew.bat works.
# Run once: .\setup.ps1
# Requires PowerShell 5+ (ships with Windows 10/11).

$WrapperJar = "$PSScriptRoot\gradle\wrapper\gradle-wrapper.jar"

if (Test-Path $WrapperJar) {
    Write-Host 'gradle-wrapper.jar already present - skipping download.' -ForegroundColor Green
} else {
    $Url = 'https://raw.githubusercontent.com/gradle/gradle/v8.14.0/gradle/wrapper/gradle-wrapper.jar'
    Write-Host 'Downloading gradle-wrapper.jar from GitHub...' -ForegroundColor Cyan
    try {
        Invoke-WebRequest -Uri $Url -OutFile $WrapperJar -UseBasicParsing
        Write-Host 'Done.' -ForegroundColor Green
    } catch {
        Write-Host "Download failed: $_" -ForegroundColor Red
        Write-Host 'Alternative: install Gradle via winget install Gradle.Gradle then run: gradle wrapper' -ForegroundColor Yellow
        exit 1
    }
}

Write-Host ''
Write-Host 'To run the game:' -ForegroundColor White
Write-Host '  .\gradlew.bat desktop:run' -ForegroundColor Cyan
Write-Host ''
Write-Host 'To build a fat JAR:' -ForegroundColor White
Write-Host '  .\gradlew.bat desktop:jar' -ForegroundColor Cyan
