$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
New-Item -ItemType Directory -Force -Path out | Out-Null

$modClasses = "..\Anima-mod\build\classes\java\main"
if (-not (Test-Path $modClasses)) {
    Write-Host "[Anima-renderer] Compiling Anima-mod core classes..."
    Push-Location "..\Anima-mod"
    gradle compileJava --no-daemon
    Pop-Location
}

Write-Host "[Anima-renderer] Compiling renderer..."
javac --release 25 -cp $modClasses -d out src\dev\anima\renderer\*.java

Write-Host "[Anima-renderer] Running renderer..."
java -cp "out;$modClasses" dev.anima.renderer.AnimaRendererMain @args
Pop-Location
