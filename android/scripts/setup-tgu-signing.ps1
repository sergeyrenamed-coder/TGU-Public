param(
    [string]$Repository = "sergeyrenamed-coder/TGU-Public"
)

$ErrorActionPreference = "Stop"

$keytoolPath = $null

# First try PATH and common Java environment variables.
$keytoolCommand = Get-Command keytool.exe -ErrorAction SilentlyContinue
if (-not $keytoolCommand) {
    $keytoolCommand = Get-Command keytool -ErrorAction SilentlyContinue
}
if ($keytoolCommand) {
    $keytoolPath = $keytoolCommand.Source
}

$candidatePaths = @()
if ($env:JAVA_HOME) {
    $candidatePaths += (Join-Path $env:JAVA_HOME "bin\keytool.exe")
}
if ($env:STUDIO_JDK) {
    $candidatePaths += (Join-Path $env:STUDIO_JDK "bin\keytool.exe")
}
if ($env:ProgramFiles) {
    $candidatePaths += (Join-Path $env:ProgramFiles "Android\Android Studio\jbr\bin\keytool.exe")
    $candidatePaths += (Join-Path $env:ProgramFiles "Android\Android Studio\jre\bin\keytool.exe")
    $candidatePaths += (Join-Path $env:ProgramFiles "Java\jdk-17\bin\keytool.exe")
    $candidatePaths += (Join-Path $env:ProgramFiles "Microsoft\jdk-17\bin\keytool.exe")
    $candidatePaths += (Join-Path $env:ProgramFiles "Eclipse Adoptium\jdk-17\bin\keytool.exe")
}
if ($env:LOCALAPPDATA) {
    $candidatePaths += (Join-Path $env:LOCALAPPDATA "Programs\Android Studio\jbr\bin\keytool.exe")
}

foreach ($candidate in $candidatePaths) {
    if (-not $keytoolPath -and (Test-Path $candidate -PathType Leaf)) {
        $keytoolPath = $candidate
        break
    }
}

# Search common versioned JDK folders without scanning the entire disk.
$searchRoots = @()
if ($env:ProgramFiles) {
    $searchRoots += (Join-Path $env:ProgramFiles "Java")
    $searchRoots += (Join-Path $env:ProgramFiles "Microsoft")
    $searchRoots += (Join-Path $env:ProgramFiles "Eclipse Adoptium")
    $searchRoots += (Join-Path $env:ProgramFiles "Android")
}
foreach ($root in $searchRoots) {
    if (-not $keytoolPath -and (Test-Path $root -PathType Container)) {
        $found = Get-ChildItem -Path $root -Filter keytool.exe -File -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match '\\(jdk-17[^\\]*|jbr)\\bin\\keytool\.exe$' } |
            Select-Object -First 1
        if ($found) {
            $keytoolPath = $found.FullName
            break
        }
    }
}

if (-not $keytoolPath) {
    Write-Host ""
    Write-Host "JDK 17 was not found. If Android Studio is installed, its bundled JBR may contain keytool."
    Write-Host "Otherwise install Microsoft OpenJDK 17 with this command:"
    Write-Host "  winget install --id Microsoft.OpenJDK.17 -e"
    Write-Host "Then close and reopen PowerShell and run this script again."
    throw "keytool.exe was not found. No signing key has been created."
}

Write-Host ("Using keytool: " + $keytoolPath)

$signingDirectory = Join-Path $env:LOCALAPPDATA "RNMD\TGU\signing"
New-Item -ItemType Directory -Force -Path $signingDirectory | Out-Null

$keystorePath = Join-Path $signingDirectory "tgu-debug.jks"
$base64Path = Join-Path $signingDirectory "TGU_DEBUG_KEYSTORE_BASE64.txt"
$passwordPath = Join-Path $signingDirectory "TGU_DEBUG_KEYSTORE_PASSWORD.txt"

if (Test-Path $keystorePath) {
    throw ("A keystore already exists: " + $keystorePath + ". Do not overwrite it or create a new key.")
}

$randomBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $rng.GetBytes($randomBytes)
}
finally {
    $rng.Dispose()
}
$password = [Convert]::ToBase64String($randomBytes).TrimEnd("=").Replace("+", "A").Replace("/", "B")

Write-Host "Creating persistent TGU debug keystore..."
$keytoolArguments = @(
    "-genkeypair", "-v",
    "-keystore", $keystorePath,
    "-storetype", "JKS",
    "-alias", "tgu-debug",
    "-keyalg", "RSA",
    "-keysize", "2048",
    "-validity", "10000",
    "-storepass", $password,
    "-keypass", $password,
    "-dname", "CN=TGU Debug, OU=Build, O=RNMD, C=US"
)
& $keytoolPath @keytoolArguments

if ($LASTEXITCODE -ne 0) {
    Remove-Item $keystorePath -Force -ErrorAction SilentlyContinue
    throw "keytool failed. The keystore was not created."
}

$encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))
[IO.File]::WriteAllText($base64Path, $encoded, [Text.Encoding]::ASCII)
[IO.File]::WriteAllText($passwordPath, $password, [Text.Encoding]::ASCII)

Write-Host ""
Write-Host ("Keystore created at: " + $keystorePath)
Write-Host "Add these two GitHub Actions repository secrets:"
Write-Host "  TGU_DEBUG_KEYSTORE_BASE64   <- contents of the file:"
Write-Host ("     " + $base64Path)
Write-Host "  TGU_DEBUG_KEYSTORE_PASSWORD <- contents of the file:"
Write-Host ("     " + $passwordPath)
Write-Host ""

$ghCommand = Get-Command gh -ErrorAction SilentlyContinue
if ($ghCommand) {
    & $ghCommand.Source auth status *> $null
    if ($LASTEXITCODE -eq 0) {
        $answer = Read-Host ("GitHub CLI is authenticated. Add both secrets to " + $Repository + " automatically? (y/N)")
        if ($answer -match "^(y|yes)$") {
            Get-Content -Raw $base64Path | & $ghCommand.Source secret set TGU_DEBUG_KEYSTORE_BASE64 --repo $Repository
            if ($LASTEXITCODE -ne 0) { throw "Failed to set TGU_DEBUG_KEYSTORE_BASE64." }

            Get-Content -Raw $passwordPath | & $ghCommand.Source secret set TGU_DEBUG_KEYSTORE_PASSWORD --repo $Repository
            if ($LASTEXITCODE -ne 0) { throw "Failed to set TGU_DEBUG_KEYSTORE_PASSWORD." }

            Write-Host "Both GitHub Actions secrets have been added."
            Remove-Item $base64Path, $passwordPath -Force
        }
    }
}

Write-Host "IMPORTANT: Back up the keystore and its password securely. Every future build must use this same key."
