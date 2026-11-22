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

# Locate LWJGL 3.4.x (core, glfw, opengl + Windows x64 natives) from Gradle cache for real OpenGL 3.3 GPU shader execution
$lwjglRoot = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\org.lwjgl"
$lwjglJars = @()
foreach ($mod in @("lwjgl", "lwjgl-glfw", "lwjgl-opengl")) {
    $modDir = Join-Path $lwjglRoot $mod
    $mainJar = Get-ChildItem $modDir -Recurse -Filter "$mod-3.4.*.jar" | Where-Object { $_.Name -notmatch "natives|unsafe|sources|javadoc" } | Sort-Object Name -Descending | Select-Object -First 1
    $natJar  = Get-ChildItem $modDir -Recurse -Filter "$mod-3.4.*-natives-windows.jar" | Sort-Object Name -Descending | Select-Object -First 1
    if ($mainJar) { $lwjglJars += $mainJar.FullName }
    if ($natJar)  { $lwjglJars += $natJar.FullName }
}
$cp = (@("out", $modClasses) + $lwjglJars) -join ";"

Write-Host "[Anima-renderer] Compiling renderer (with LWJGL OpenGL 3.3 GPU backend)..."
javac --release 25 -cp $cp -d out src\dev\anima\renderer\*.java

Write-Host "[Anima-renderer] Running renderer..."
java --enable-native-access=ALL-UNNAMED -cp $cp dev.anima.renderer.AnimaRendererMain @args
Pop-Location
