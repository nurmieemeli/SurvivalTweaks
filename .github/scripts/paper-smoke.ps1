$ErrorActionPreference = "Stop"

$paperUrl = "https://fill-data.papermc.io/v1/objects/defe82c1c89067186895de34cf32983e9f5a2ea387cfe7597c020faebb98ca16/paper-26.2-84.jar"
$paperSha256 = "DEFE82C1C89067186895DE34CF32983E9F5A2EA387CFE7597C020FAEBB98CA16"
$repository = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$runnerTemporary = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$smokeRoot = Join-Path $runnerTemporary "survivaltweaks-paper-smoke"
$utf8NoBom = [Text.UTF8Encoding]::new($false)

if (Test-Path -LiteralPath $smokeRoot) {
    Remove-Item -LiteralPath $smokeRoot -Recurse -Force
}
New-Item -ItemType Directory -Path (Join-Path $smokeRoot "plugins") -Force | Out-Null

$jars = @(Get-ChildItem -LiteralPath (Join-Path $repository "target") -Filter "SurvivalTweaks-*.jar" -File)
if ($jars.Count -ne 1) {
    throw "Expected one built SurvivalTweaks JAR, found $($jars.Count)."
}
$pluginJar = $jars[0]
$version = [regex]::Match($pluginJar.Name, "^SurvivalTweaks-(.+)\.jar$").Groups[1].Value
Copy-Item -LiteralPath $pluginJar.FullName -Destination (Join-Path $smokeRoot "plugins/$($pluginJar.Name)")

$paperJar = Join-Path $smokeRoot "paper-26.2-84.jar"
Invoke-WebRequest -Uri $paperUrl -OutFile $paperJar
$actualPaperSha256 = (Get-FileHash -LiteralPath $paperJar -Algorithm SHA256).Hash
if ($actualPaperSha256 -ne $paperSha256) {
    throw "Pinned Paper download checksum mismatch: $actualPaperSha256"
}

[IO.File]::WriteAllText((Join-Path $smokeRoot "eula.txt"), "eula=true`n", $utf8NoBom)
[IO.File]::WriteAllLines((Join-Path $smokeRoot "server.properties"), @(
    "online-mode=false"
    "server-port=25572"
    "enable-rcon=true"
    "rcon.port=25582"
    "rcon.password=survivaltweaks-smoke"
    "motd=SurvivalTweaks automated smoke test"
    "level-name=world"
    "view-distance=2"
    "simulation-distance=2"
    "max-players=2"
), $utf8NoBom)

function Invoke-Rcon([string]$Command) {
    $client = $null
    try {
        $client = [Net.Sockets.TcpClient]::new("127.0.0.1", 25582)
        $stream = $client.GetStream()
        $stream.ReadTimeout = 5000

        function Send-Packet([int]$Id, [int]$Type, [string]$Body) {
            $payload = [Text.Encoding]::UTF8.GetBytes($Body)
            $length = 10 + $payload.Length
            $packet = [byte[]]::new($length + 4)
            [BitConverter]::GetBytes($length).CopyTo($packet, 0)
            [BitConverter]::GetBytes($Id).CopyTo($packet, 4)
            [BitConverter]::GetBytes($Type).CopyTo($packet, 8)
            $payload.CopyTo($packet, 12)
            $stream.Write($packet, 0, $packet.Length)
        }

        function Read-Exact([int]$Count) {
            $data = [byte[]]::new($Count)
            $offset = 0
            while ($offset -lt $Count) {
                $read = $stream.Read($data, $offset, $Count - $offset)
                if ($read -eq 0) {
                    throw "RCON connection closed unexpectedly."
                }
                $offset += $read
            }
            return $data
        }

        function Read-Packet {
            $length = [BitConverter]::ToInt32((Read-Exact 4), 0)
            $data = Read-Exact $length
            return [Text.Encoding]::UTF8.GetString($data, 8, $length - 10)
        }

        Send-Packet 1 3 "survivaltweaks-smoke"
        $null = Read-Packet
        Send-Packet 2 2 $Command
        return Read-Packet
    } finally {
        if ($null -ne $client) {
            $client.Close()
        }
    }
}

$startInfo = [Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = "java"
$startInfo.WorkingDirectory = $smokeRoot
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.Arguments = "-Xms512M -Xmx1G -jar paper-26.2-84.jar --nogui"

$process = [Diagnostics.Process]::new()
$process.StartInfo = $startInfo
$null = $process.Start()
$standardOutputTask = $process.StandardOutput.ReadToEndAsync()
$standardErrorTask = $process.StandardError.ReadToEndAsync()
$smokeFailure = $null

try {
    $pluginsResponse = $null
    for ($attempt = 0; $attempt -lt 180; $attempt++) {
        if ($process.HasExited) {
            throw "Paper exited before RCON became ready."
        }
        try {
            $pluginsResponse = Invoke-Rcon "plugins"
            if ($pluginsResponse -match "SurvivalTweaks") {
                break
            }
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if ($pluginsResponse -notmatch "SurvivalTweaks") {
        throw "SurvivalTweaks was not visible through Paper's plugin list."
    }

    $reloadResponse = Invoke-Rcon "survivaltweaks reload"
    if ($reloadResponse -notmatch "Validating configuration") {
        throw "SurvivalTweaks reload did not start: $reloadResponse"
    }
    Start-Sleep -Seconds 2
    $doctorResponse = Invoke-Rcon "survivaltweaks doctor"
    if ($doctorResponse -notmatch "started") {
        throw "SurvivalTweaks diagnostics did not start: $doctorResponse"
    }
    Start-Sleep -Seconds 5
    $doctorCompletionProbe = Invoke-Rcon "survivaltweaks doctor"
    if ($doctorCompletionProbe -notmatch "started") {
        throw "SurvivalTweaks diagnostics did not complete within five seconds: $doctorCompletionProbe"
    }
    Start-Sleep -Seconds 5
    $null = Invoke-Rcon "stop"

    if (!$process.WaitForExit(60000)) {
        throw "Paper did not stop within 60 seconds."
    }
} catch {
    $smokeFailure = $_
} finally {
    if (!$process.HasExited) {
        $process.Kill($true)
        $process.WaitForExit()
    }
}

$standardOutput = $standardOutputTask.GetAwaiter().GetResult()
$standardError = $standardErrorTask.GetAwaiter().GetResult()
[IO.File]::WriteAllText((Join-Path $smokeRoot "server.out.log"), $standardOutput, $utf8NoBom)
[IO.File]::WriteAllText((Join-Path $smokeRoot "server.err.log"), $standardError, $utf8NoBom)
Write-Host $standardOutput
if ($standardError) {
    Write-Host $standardError
}
if ($null -ne $smokeFailure) {
    throw $smokeFailure
}

if ($standardOutput -notmatch "Enabling SurvivalTweaks v$([regex]::Escape($version))") {
    throw "Paper did not enable the expected SurvivalTweaks version $version."
}
if ($standardOutput -notmatch "Disabling SurvivalTweaks v$([regex]::Escape($version))") {
    throw "Paper did not disable SurvivalTweaks cleanly."
}
if ($standardOutput -notmatch "Reloaded configuration and language catalogs") {
    throw "The asynchronous SurvivalTweaks reload did not complete successfully."
}
if (($standardOutput + "`n" + $standardError) -match "(?im)\b(SEVERE|ERROR)\b|Exception") {
    throw "Paper smoke-test logs contain an exception or error-level entry. Logs: $smokeRoot"
}

Write-Host "Paper 26.2 build 84 smoke test passed for SurvivalTweaks $version."
