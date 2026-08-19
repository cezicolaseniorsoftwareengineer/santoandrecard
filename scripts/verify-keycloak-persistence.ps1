<#
.SYNOPSIS
    Proves that a Keycloak account survives recreation of the container.

.DESCRIPTION
    Accounts were moved out of the container and into PostgreSQL so that a
    self-registered customer is not silently lost. That is a claim about a
    failure that only appears on recreation, so a restart proves nothing and
    reading the configuration proves less: the realm is imported on every start,
    and the question is whether the import lands on top of existing accounts.

    The script creates a customer, recreates the Keycloak container from scratch,
    and then requires that same customer to obtain a token. It fails loudly if
    the account is gone, and it removes the account it created either way.

    Run it against the local Compose stack. Against the canonical database
    (ADR-004) set KC_DB_URL, KC_DB_USERNAME and KC_DB_PASSWORD first — but note
    that it writes and deletes an account there, so prefer the local stack.

.EXAMPLE
    powershell -File ./scripts/verify-keycloak-persistence.ps1
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8180',
    [string]$Realm = 'card-platform',
    [string]$AdminUser = 'admin',
    [string]$AdminPassword = 'admin_local',
    # The realm's only public client, used by both the web application and the
    # service. Direct access grants are enabled on it, which is what lets this
    # check authenticate without driving a browser flow.
    [string]$ClientId = 'card-service',
    [int]$ReadyTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$probeUser = "persistence-probe-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$probePassword = "Probe!$([guid]::NewGuid().ToString('N').Substring(0, 12))"
$repoRoot = Split-Path -Parent $PSScriptRoot

function Get-AdminToken {
    $body = @{
        grant_type = 'password'
        client_id  = 'admin-cli'
        username   = $AdminUser
        password   = $AdminPassword
    }
    (Invoke-RestMethod -Method Post -Uri "$BaseUrl/realms/master/protocol/openid-connect/token" -Body $body).access_token
}

function Wait-ForKeycloak {
    param([int]$TimeoutSeconds)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-RestMethod -Method Get -Uri "$BaseUrl/realms/$Realm/.well-known/openid-configuration" -TimeoutSec 5 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 3
        }
    }
    throw "Keycloak did not become ready within $TimeoutSeconds seconds at $BaseUrl"
}

function Find-ProbeUserId {
    param([string]$Token)

    $headers = @{ Authorization = "Bearer $Token" }
    $found = Invoke-RestMethod -Method Get -Headers $headers `
        -Uri "$BaseUrl/admin/realms/$Realm/users?username=$probeUser&exact=true"
    if ($found.Count -eq 0) { return $null }
    return $found[0].id
}

function Remove-ProbeUser {
    try {
        $token = Get-AdminToken
        $id = Find-ProbeUserId -Token $token
        if ($id) {
            Invoke-RestMethod -Method Delete -Headers @{ Authorization = "Bearer $token" } `
                -Uri "$BaseUrl/admin/realms/$Realm/users/$id" | Out-Null
            Write-Host "cleanup: removed probe account $probeUser"
        }
    } catch {
        # Cleanup failing must not mask the result of the check itself.
        Write-Warning "cleanup: could not remove probe account $probeUser - $($_.Exception.Message)"
    }
}

try {
    Write-Host '== 1. waiting for Keycloak =='
    Wait-ForKeycloak -TimeoutSeconds $ReadyTimeoutSeconds

    Write-Host "== 2. creating probe account $probeUser =="
    $token = Get-AdminToken
    $headers = @{ Authorization = "Bearer $token"; 'Content-Type' = 'application/json' }
    # firstName, lastName and an empty requiredActions are not decoration. The
    # realm requires a complete profile, and an account missing any of them is
    # refused at the token endpoint with "Account is not fully set up" — which
    # reads like a persistence failure and is not one.
    $payload = @{
        username        = $probeUser
        enabled         = $true
        emailVerified   = $true
        email           = "$probeUser@example.invalid"
        firstName       = 'Persistence'
        lastName        = 'Probe'
        requiredActions = @()
        credentials     = @(@{ type = 'password'; value = $probePassword; temporary = $false })
    } | ConvertTo-Json -Depth 5
    Invoke-RestMethod -Method Post -Headers $headers -Uri "$BaseUrl/admin/realms/$Realm/users" -Body $payload | Out-Null

    # Authenticating now establishes that the account works before recreation, so
    # a failure afterwards can only mean the account did not survive.
    $login = @{
        grant_type = 'password'
        client_id  = $ClientId
        username   = $probeUser
        password   = $probePassword
    }
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/realms/$Realm/protocol/openid-connect/token" -Body $login | Out-Null
    Write-Host 'account authenticates before recreation'

    Write-Host '== 3. destroying and recreating the Keycloak container =='
    Push-Location $repoRoot
    try {
        # Removes the container and its filesystem, not just its process. A
        # restart would leave container state intact and prove nothing.
        docker compose rm --stop --force --volumes keycloak
        if ($LASTEXITCODE -ne 0) { throw "docker compose rm failed with exit code $LASTEXITCODE" }
        docker compose up -d keycloak
        if ($LASTEXITCODE -ne 0) { throw "docker compose up failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }

    Write-Host '== 4. waiting for the new container =='
    Wait-ForKeycloak -TimeoutSeconds $ReadyTimeoutSeconds

    Write-Host '== 5. requiring the same account to authenticate =='
    try {
        Invoke-RestMethod -Method Post -Uri "$BaseUrl/realms/$Realm/protocol/openid-connect/token" -Body $login | Out-Null
    } catch {
        throw "FAILED: $probeUser could not authenticate after recreation. " +
              'Accounts are not surviving in PostgreSQL, or the realm import overwrote them. ' +
              "Underlying error: $($_.Exception.Message)"
    }

    Write-Host ''
    Write-Host 'PASS: the account created before recreation still authenticates after it.'
    Write-Host 'Accounts live in PostgreSQL and the realm import did not overwrite them.'
    exit 0
} finally {
    Remove-ProbeUser
}
