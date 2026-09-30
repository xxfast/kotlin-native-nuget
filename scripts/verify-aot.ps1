$ErrorActionPreference = 'Stop'

# Child-process PATH only: local Visual Studio installations need not expose vswhere globally.
if ($null -eq (Get-Command vswhere.exe -ErrorAction SilentlyContinue) -and
    -not [string]::IsNullOrEmpty(${env:ProgramFiles(x86)})) {
    $installer = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer'
    if (Test-Path -LiteralPath (Join-Path $installer 'vswhere.exe') -PathType Leaf) {
        $env:PATH = $installer + [IO.Path]::PathSeparator + $env:PATH
    }
}

dotnet publish AotSmokeTest -r win-x64 -c Release -p:PublishAot=true
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& ./AotSmokeTest/bin/Release/net10.0/win-x64/publish/AotSmokeTest.exe
exit $LASTEXITCODE
