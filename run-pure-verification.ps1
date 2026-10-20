<#
  EchoKey — Pure Invisible Layer Verification (Windows)
  --------------------------------------------------------
  This script compiles and runs the 100% pure-Kotlin verification
  that exercises the core invisible layer with ZERO Android SDK,
  ZERO Gradle, and ZERO device.

  Prerequisites (once):
    - Install Kotlin compiler from https://kotlinlang.org/docs/command-line.html
    - Make sure "kotlinc" and "kotlin" are on your PATH.

  Usage:
    1. Open PowerShell in the repo root (C:\_Sync\Files\GrokBuild\echoKey)
    2. .\run-pure-verification.ps1

  It will produce clear PASS/FAIL output and a FINAL_RESULT line.
#>

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$outDir = Join-Path $root "out-pure"
$srcMain = Join-Path $root "app/src/main/java/com/echokey/keyboard"
$srcTest = Join-Path $root "app/src/test/java/com/echokey/keyboard"

Write-Host "=== EchoKey Pure Invisible Verification ===" -ForegroundColor Cyan
Write-Host "Compiling pure Kotlin sources (no Android SDK, no Gradle)..."
Write-Host ""

# Clean previous
if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Path $outDir | Out-Null

# Collect ONLY the files required for 100% pure-Kotlin verification.
# We deliberately exclude:
#   - EchoKeyService.kt  (extends Android InputMethodService)
#   - EchoKeySettingsActivity.kt (extends Android Activity)
#   - Any other file that pulls in real Android framework types.
#
# The PureAndroidStubs.kt supplies the minimal EditorInfo / InputConnection
# declarations so the real invisible logic (T9Engine, Punctuator, etc.)
# compiles under plain kotlinc without any Android SDK.
# Root of the test java sources (so we can reach sibling packages like android.text)
$testJavaRoot = Join-Path $root "app/src/test/java"

$ktFiles = @(
    # Stubs first — these provide android.* types for the pure kotlinc run.
    (Join-Path $srcTest "PureAndroidStubs.kt"),
    (Join-Path $srcTest "PureEchoKeyServiceStub.kt"),
    # android.text.InputType stub (sibling package under java/, not under com/echokey)
    (Join-Path $testJavaRoot "android/text/InputType.kt"),

    # Pure-friendly top level definitions (no Android superclass)
    (Join-Path $srcMain "ShiftState.kt"),
    (Join-Path $srcMain "InputEngine.kt"),

    # The actual invisible logic we are proving
    (Join-Path $srcMain "ActionKeyHandler.kt"),
    (Join-Path $srcMain "BackspaceHandler.kt"),
    (Join-Path $srcMain "CurrentWordTracker.kt"),
    (Join-Path $srcMain "Dictionary.kt"),
    (Join-Path $srcMain "Punctuator.kt"),
    (Join-Path $srcMain "StaticDictionary.kt"),
    (Join-Path $srcMain "T9Engine.kt"),

    # NOTE: We intentionally DO NOT include:
    #   - EchoKeyService.kt  (extends real Android InputMethodService)
    #   - EchoKeySettingsActivity.kt
    #   - MultiTapEngine.kt  (holds a non-null EchoKeyService reference today)
    #
    # The pure verifier only exercises T9 + Punctuator + Tracker + BackspaceHandler
    # (multi-tap cycle behavior is covered by the tiny stand-in class inside the verifier itself).
    (Join-Path $srcTest "InvisibleLayerPureVerification.kt")
)

Write-Host "Files to compile (absolute, for diagnostics):"
foreach ($f in $ktFiles) { Write-Host "  $f" }
Write-Host ""

# Always show the exact manual command (very handy when kotlinc is on PATH
# but you want to run it by hand, or when debugging the file list).
Write-Host "=== Exact manual command (copy/paste, relative paths) ===" -ForegroundColor Cyan
Write-Host ""
$relFiles = @()
foreach ($f in $ktFiles) {
    $rel = $f -replace [regex]::Escape($root + [IO.Path]::DirectorySeparatorChar), ''
    $rel = $rel -replace [regex]::Escape($root), ''
    $rel = $rel.TrimStart('\','/')
    $rel = $rel -replace '\\', '/'
    $relFiles += $rel
}
Write-Host 'kotlinc -nowarn -d out-pure `'
for ($i = 0; $i -lt $relFiles.Count; $i++) {
    $suffix = if ($i -eq ($relFiles.Count - 1)) { '' } else { ' `' }
    Write-Host ('  "' + $relFiles[$i] + '"' + $suffix)
}
Write-Host ""
Write-Host "NOTE: Do NOT use the 'kotlin' runner below on Windows."
Write-Host "The reliable way is the 'java' command shown later in this script output."
Write-Host ""

# Compile
# NOTE: Build an explicit argument list. This is the most reliable way
# to pass many files to an external exe from PowerShell (no glob, no array quirks).
$kotlincCmd = Get-Command kotlinc -ErrorAction SilentlyContinue
if (-not $kotlincCmd) {
    Write-Host ""
    Write-Host "ERROR: 'kotlinc' not found on PATH." -ForegroundColor Red
    Write-Host "Install the Kotlin command-line compiler from:" -ForegroundColor Yellow
    Write-Host "  https://kotlinlang.org/docs/command-line.html" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "After installing, make sure 'kotlinc' and 'kotlin' are on your PATH," -ForegroundColor Yellow
    Write-Host "then re-run this script (or use the manual command above) from the repo root." -ForegroundColor Yellow
    Write-Host ""
    exit 1
}

$argList = @('-nowarn', '-d', $outDir) + $ktFiles
& kotlinc @argList

if ($LASTEXITCODE -ne 0) {
    Write-Host "Compilation FAILED." -ForegroundColor Red
    exit 1
}

# Use absolute path for the classpath — this is much more reliable on Windows.
$absOutDir = (Resolve-Path $outDir).Path

Write-Host "Compilation succeeded." -ForegroundColor Green
Write-Host "Output directory: $absOutDir"
Write-Host ""

# Show exactly what class files were generated.
Write-Host "Classes generated:"
$allClasses = Get-ChildItem -Path $absOutDir -Recurse -Filter "*.class" -ErrorAction SilentlyContinue
if ($allClasses) {
    $allClasses | ForEach-Object {
        $rel = $_.FullName.Substring($absOutDir.Length).TrimStart('\','/')
        Write-Host "  $rel"
    }
} else {
    Write-Host "  (none)"
}
Write-Host ""

# Check for the two possible main class names
$ktClass   = Join-Path $absOutDir "com\echokey\keyboard\InvisibleLayerPureVerificationKt.class"
$plainClass = Join-Path $absOutDir "com\echokey\keyboard\InvisibleLayerPureVerification.class"

$hasKt   = Test-Path $ktClass
$hasPlain = Test-Path $plainClass

Write-Host "Main class file check:"
Write-Host "  InvisibleLayerPureVerificationKt.class   -> $(if ($hasKt) { 'EXISTS' } else { 'MISSING' })"
Write-Host "  InvisibleLayerPureVerification.class     -> $(if ($hasPlain) { 'EXISTS' } else { 'MISSING' })"
Write-Host ""

# ------------------------------------------------------------------
# The "kotlin" runner on Windows is notoriously flaky with main classes.
# We strongly prefer a direct "java -cp ..." using kotlin-stdlib.jar.
# ------------------------------------------------------------------

# Try to find kotlin-stdlib.jar in several likely places.
$stdlibCandidates = @()

# 1. Next to the kotlinc that is on PATH
try {
    $kotlincExe = (Get-Command kotlinc -ErrorAction Stop).Source
    $home1 = Split-Path (Split-Path $kotlincExe -Parent) -Parent
    $stdlibCandidates += (Join-Path $home1 "lib\kotlin-stdlib.jar")
} catch {}

# 2. Common manual install locations (user may have extracted kotlin-compiler-*.zip)
$stdlibCandidates += @(
    "C:\kotlin\lib\kotlin-stdlib.jar",
    "$env:USERPROFILE\kotlin\lib\kotlin-stdlib.jar",
    "$env:ProgramFiles\Kotlin\lib\kotlin-stdlib.jar"
)

# 3. If KOTLIN_HOME is set
if ($env:KOTLIN_HOME) {
    $stdlibCandidates += (Join-Path $env:KOTLIN_HOME "lib\kotlin-stdlib.jar")
}

$stdlibPath = $null
foreach ($cand in $stdlibCandidates) {
    if (Test-Path $cand) {
        $stdlibPath = $cand
        break
    }
}

$cp = $absOutDir
if ($stdlibPath) {
    $cp = "$absOutDir;$stdlibPath"
    Write-Host "Found kotlin-stdlib at: $stdlibPath" -ForegroundColor DarkGray
} else {
    Write-Host "WARNING: Could not auto-locate kotlin-stdlib.jar" -ForegroundColor Yellow
    Write-Host "We will still try the 'kotlin' runner, but it often fails on Windows for main classes." -ForegroundColor Yellow
    Write-Host ""
}

Write-Host ""
Write-Host "------------------------------------------------------------------"
Write-Host "RUN PHASE — using java + stdlib directly (the only reliable way on Windows)"
Write-Host "------------------------------------------------------------------"
Write-Host ""

$runExit = 1

if ($stdlibPath) {
    $cp = "$absOutDir;$stdlibPath"

    Write-Host "Classpath:"
    Write-Host "  $cp"
    Write-Host ""

    # Object + @JvmStatic main compiles to InvisibleLayerPureVerification (no Kt suffix).
    Write-Host "Trying main class: com.echokey.keyboard.InvisibleLayerPureVerification"
    & java -cp $cp com.echokey.keyboard.InvisibleLayerPureVerification
    $runExit = $LASTEXITCODE

    if ($runExit -ne 0) {
        Write-Host ""
        Write-Host "That name failed. Trying with the 'Kt' suffix:"
        & java -cp $cp com.echokey.keyboard.InvisibleLayerPureVerificationKt
        $runExit = $LASTEXITCODE
    }
} else {
    Write-Host "No kotlin-stdlib.jar was located. Cannot execute automatically." -ForegroundColor Red
    Write-Host "See the manual command printed above."
    $runExit = 1
}

Write-Host ""

if ($runExit -eq 0) {
    Write-Host "=== Verification ran successfully ===" -ForegroundColor Green
} else {
    Write-Host "Still got 'could not find or load main class'." -ForegroundColor Red
    Write-Host ""
    Write-Host "Please run the command below manually and paste everything it prints:"
    Write-Host ""
    Write-Host "    java -cp `"$absOutDir;$stdlibPath`" com.echokey.keyboard.InvisibleLayerPureVerificationKt" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "If that still fails, also try:"
    Write-Host "    java -cp `"$absOutDir;$stdlibPath`" com.echokey.keyboard.InvisibleLayerPureVerification" -ForegroundColor Cyan
}

Write-Host ""
Write-Host "=== Pure verification finished ===" -ForegroundColor Cyan
Write-Host ""

# Always print this at the end so the user has the command even if the automatic run failed.
$finalCp = "$absOutDir"
if ($stdlibPath) { $finalCp = "$absOutDir;$stdlibPath" }

Write-Host "=== If you still see 'could not find or load main class', run this manually ===" -ForegroundColor Yellow
Write-Host ""
Write-Host "java -cp `"$finalCp`" com.echokey.keyboard.InvisibleLayerPureVerificationKt" -ForegroundColor Green
Write-Host ""
Write-Host "or (alternate name):"
Write-Host "java -cp `"$finalCp`" com.echokey.keyboard.InvisibleLayerPureVerification" -ForegroundColor Green
Write-Host ""
Write-Host "Replace the second classpath entry with the real location of kotlin-stdlib.jar if needed."
Write-Host "(Run 'where kotlinc' then go up two levels and look in the 'lib' folder.)"
Write-Host ""
Write-Host "Later, with a full Android/Gradle environment you can also run the richer" -ForegroundColor Yellow
Write-Host "end-to-end proofs that drive the *real* EchoKeyService:" -ForegroundColor Yellow
Write-Host "   ./gradlew test --tests '*InvisibleFlowsVerification'" -ForegroundColor Yellow
Write-Host ""