<#
.SYNOPSIS
    Runs a command with .env loaded into the environment.

.DESCRIPTION
    The datasource is entirely environment driven, so pointing the service at the
    canonical database (ADR-004) is a matter of which variables are set. This
    reads them from .env — which git ignores — and sets them for one child
    process only, so the credential never reaches a tracked file, a shell
    history or a terminal transcript.

    Values are not echoed. The script reports which variables it loaded, never
    what they contain.

.EXAMPLE
    powershell -File ./scripts/with-env.ps1 mvn -pl card-service quarkus:dev

.EXAMPLE
    powershell -File ./scripts/with-env.ps1 -EnvFile .env.staging psql
#>

[CmdletBinding()]
param(
    # Deliberately not positional. It was, and the first word of the command
    # bound to it instead: `with-env.ps1 mvn test` read an env file named "mvn"
    # and failed with a message about the wrong thing entirely.
    [Parameter(Mandatory = $false)]
    [string]$EnvFile = '.env',

    [Parameter(Mandatory = $true, Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$Command
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$path = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $repoRoot $EnvFile }

if (-not (Test-Path $path)) {
    throw "No $EnvFile found. Copy .env.example to .env and fill it in; git ignores .env."
}

$loaded = [System.Collections.Generic.List[string]]::new()
foreach ($line in Get-Content -LiteralPath $path -Encoding utf8) {
    $trimmed = $line.Trim()
    if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }

    $separator = $trimmed.IndexOf('=')
    if ($separator -lt 1) {
        throw "Malformed line in ${EnvFile}: expected NAME=value"
    }

    $name = $trimmed.Substring(0, $separator).Trim()
    # Only the first '=' separates; a JDBC URL carries more of them in its query
    # string and splitting on all of them would truncate it.
    $value = $trimmed.Substring($separator + 1).Trim()
    if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
        ($value.StartsWith("'") -and $value.EndsWith("'"))) {
        $value = $value.Substring(1, $value.Length - 2)
    }

    if ($value -match 'HOST|DATABASE|^USER$|^PASSWORD$') {
        throw "$name still holds a placeholder from .env.example. Fill in $EnvFile first."
    }

    Set-Item -Path "Env:$name" -Value $value
    $loaded.Add($name)
}

# Names only. Printing a value here is how a credential ends up in a transcript.
Write-Host "loaded from ${EnvFile}: $($loaded -join ', ')"

$executable, $arguments = $Command
& $executable @arguments
exit $LASTEXITCODE
