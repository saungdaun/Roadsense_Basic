# build-reset.ps1
# PowerShell script to clean, build, and refresh Gradle for Android Studio

# Path ke Gradle wrapper
$gradlew = ".\gradlew.bat"

Write-Host "Cleaning project..."
& $gradlew clean

Write-Host "Assembling debug build..."
& $gradlew assembleDebug

Write-Host "Refreshing Gradle project..."
& $gradlew --refresh-dependencies

Write-Host "Done! BuildConfig and generated sources should be restored."
