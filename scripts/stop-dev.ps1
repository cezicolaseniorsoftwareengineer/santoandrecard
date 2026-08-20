<#
.SYNOPSIS
    Ends every development process this project starts, and nothing else.

.DESCRIPTION
    Selection is an allow-list, never a deny-list. A command such as
    `Get-Process java, node | Stop-Process` reads as thorough and is not: on a
    developer machine the same executables run the editor's language server, its
    extension host and any MCP or agent tooling, none of which belong to this
    project. Ending them looks like the editor crashed.

    A process is a candidate here only if it matches one of two things:

      1. it holds one of this project's ports (8080 for the API, 4420 for the
         interface), or
      2. its command line names this repository or one of its dev commands.

    Everything else is left alone, whatever it is called. Descendants of a match
    are included, because `npm start` runs the dev server as a child and ending
    the parent alone leaves the child holding the port.

    Containers are deliberately out of scope: they are not terminal processes.
    Use -IncludeContainers to run `docker compose down` as well.

.PARAMETER WhatIf
    Lists what would be ended, and ends nothing. Worth running first.

.PARAMETER IncludeContainers
    Also stops the Compose stack, releasing 5432, 6379, 9092 and 8180.

.EXAMPLE
    ./scripts/stop-dev.ps1 -WhatIf
.EXAMPLE
    ./scripts/stop-dev.ps1
.EXAMPLE
    ./scripts/stop-dev.ps1 -IncludeContainers
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [switch] $IncludeContainers
)

$ErrorActionPreference = 'Stop'

# The CIM module is loaded up front, with -WhatIf suppressed by preference
# rather than by parameter: importing a module defines its aliases, each of which
# is itself a ShouldProcess operation, so under -WhatIf the autoload prints a
# dozen lines about aliases before the report the operator asked for.
#
# The suppression has to be the preference variable. `Import-Module -WhatIf` does
# not exist in Windows PowerShell 5.1 and fails to bind, which is exactly how
# this line was wrong the first time.
$savedWhatIf = $WhatIfPreference
$WhatIfPreference = $false
Import-Module CimCmdlets -ErrorAction SilentlyContinue
$WhatIfPreference = $savedWhatIf

# The ports this project publishes from the host. Containers are not here: their
# ports are released by `docker compose down`, not by ending a process.
$projectPorts = @(8080, 4420)

# Command-line markers. The repository directory catches anything started from
# the working tree; the command names catch a process whose path was resolved
# elsewhere. Kept as literal substrings so a path with accents or spaces — which
# this repository has — needs no escaping.
$projectMarkers = @(
    'Santo_Andre_Card',
    'quarkus:dev',
    'card-service',
    'banco-santo-andre',
    'ng.js" serve',
    '@angular\cli\bin'
)

# Never end these, even if something above matches them. The editor's own
# tooling runs on the same executables as the project.
$protectedMarkers = @(
    'ALL-SYSTEM',          # the editor's Java language server
    'extensionHost',
    'Microsoft VS Code',
    '\.vscode\',
    '\.vscode-server\',
    'mcp\server.mjs',
    'claude-code',
    'shell-snapshots',     # an agent or task shell, not a server
    '\.claude'
)

# This script ends servers, never shells or consoles. A terminal whose working
# directory is inside the repository matches the path marker exactly as a dev
# server does, and the first dry run of this script duly proposed to close the
# very shells it was running in. The rule is by executable, because the intent
# does not depend on the command line: a shell is never the thing holding a port.
$protectedNames = @(
    'bash.exe', 'sh.exe', 'zsh.exe', 'cmd.exe', 'powershell.exe', 'pwsh.exe',
    'conhost.exe', 'OpenConsole.exe', 'WindowsTerminal.exe',
    'Code.exe', 'explorer.exe', 'node_repl.exe', 'ssh.exe'
)

$all = Get-CimInstance Win32_Process

function Test-Protected {
    param([string] $CommandLine, [string] $Name)
    if ($Name -and ($protectedNames -contains $Name)) { return $true }
    if (-not $CommandLine) { return $false }
    foreach ($marker in $protectedMarkers) {
        if ($CommandLine.Contains($marker)) { return $true }
    }
    return $false
}

# --- 1. Whoever holds a project port ------------------------------------------
$byPort = @()
foreach ($port in $projectPorts) {
    try {
        $listeners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop
    } catch {
        continue    # nothing listening on this port
    }
    foreach ($listener in $listeners) {
        $byPort += [int] $listener.OwningProcess
    }
}

# --- 2. Whoever names the project on its command line -------------------------
$byMarker = @()
foreach ($process in $all) {
    if (-not $process.CommandLine) { continue }
    foreach ($marker in $projectMarkers) {
        if ($process.CommandLine.Contains($marker)) {
            $byMarker += [int] $process.ProcessId
            break
        }
    }
}

# --- 2b. One step up, for the build tool that owns a matched server ----------
# `npm start` runs the dev server through npm-cli.js, whose own command line
# names neither the project nor the port. Ending only the child leaves npm alive
# and watching. The walk is one level and only through a build tool, so it can
# never reach the terminal that launched it.
$byParent = @()
foreach ($seedId in @($byPort + $byMarker | Sort-Object -Unique)) {
    $seed = $all | Where-Object { $_.ProcessId -eq $seedId } | Select-Object -First 1
    if (-not $seed) { continue }
    $parent = $all | Where-Object { $_.ProcessId -eq $seed.ParentProcessId } | Select-Object -First 1
    if (-not $parent -or -not $parent.CommandLine) { continue }
    if ($parent.CommandLine -match 'npm-cli\.js|npx-cli\.js|maven|mvnw|quarkus') {
        $byParent += [int] $parent.ProcessId
    }
}

$seeds = @($byPort + $byMarker + $byParent | Sort-Object -Unique)

# --- 3. Expand to descendants -------------------------------------------------
# `npm start` is the parent of the dev server. Ending only the parent orphans a
# child that still holds the port, so the whole subtree is taken.
$targets = New-Object System.Collections.Generic.HashSet[int]
$queue = New-Object System.Collections.Generic.Queue[int]
foreach ($seed in $seeds) { [void] $queue.Enqueue($seed) }

while ($queue.Count -gt 0) {
    $current = $queue.Dequeue()
    # 0 is System Idle and 4 is System. A process whose parent has already exited
    # reports a recycled or zero parent id, and enumerating the children of 0
    # would pull in most of the operating system. Guarding here rather than at
    # the kill site keeps them out of the report as well.
    if ($current -le 4) { continue }
    if (-not $targets.Add($current)) { continue }
    foreach ($child in $all | Where-Object { $_.ParentProcessId -eq $current }) {
        [void] $queue.Enqueue([int] $child.ProcessId)
    }
}

# --- 4. Filter, report, end ---------------------------------------------------
$selected = foreach ($pidValue in $targets) {
    $process = $all | Where-Object { $_.ProcessId -eq $pidValue } | Select-Object -First 1
    if (-not $process) { continue }
    if ($process.ProcessId -eq $PID) { continue }          # never this shell
    if (Test-Protected -CommandLine $process.CommandLine -Name $process.Name) {
        Write-Host ("  protected  {0,-6} {1}" -f $process.ProcessId, $process.Name)
        continue
    }
    $process
}

if (-not $selected) {
    Write-Host 'Nothing to stop: no project process is running.'
} else {
    foreach ($process in $selected) {
        $line = if ($process.CommandLine) {
            $process.CommandLine.Substring(0, [Math]::Min(90, $process.CommandLine.Length))
        } else { '' }
        if ($PSCmdlet.ShouldProcess("$($process.Name) ($($process.ProcessId))", 'Stop')) {
            try {
                Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
                Write-Host ("  stopped    {0,-6} {1}" -f $process.ProcessId, $line)
            } catch {
                # Already gone: a parent was ended first and took this with it.
                Write-Host ("  gone       {0,-6} {1}" -f $process.ProcessId, $process.Name)
            }
        } else {
            Write-Host ("  would stop {0,-6} {1}" -f $process.ProcessId, $line)
        }
    }
}

# --- 5. Containers, only when asked ------------------------------------------
if ($IncludeContainers) {
    if ($PSCmdlet.ShouldProcess('docker compose', 'down')) {
        Write-Host 'Stopping the Compose stack...'
        docker compose down
    }
}

# --- 6. Prove the ports are free ---------------------------------------------
foreach ($port in $projectPorts) {
    $still = $null
    try { $still = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop } catch {}
    if ($still) {
        Write-Host ("  port {0} is STILL held by PID {1}" -f $port, $still.OwningProcess)
    } else {
        Write-Host ("  port {0} is free" -f $port)
    }
}
