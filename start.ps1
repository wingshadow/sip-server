# start.ps1 - 启动 GB28181 sip-server（先停止旧实例）
param(
    [string]$JavaHome = "C:\Program Files\Java\jdk-17"
)
$ErrorActionPreference = "Stop"

$java = Join-Path $JavaHome "bin\java.exe"
if (-not (Test-Path $java)) { $java = "java" }

# 停止旧实例
$old = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like "*sip-server-1.0.0.jar*" }
foreach ($proc in $old) {
    Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
    Write-Host "stopped old instance pid=$($proc.ProcessId)"
}

if (-not (Test-Path "logs")) { New-Item -ItemType Directory "logs" | Out-Null }

$p = Start-Process -FilePath $java -ArgumentList "-jar", "target\sip-server-1.0.0.jar" `
    -PassThru `
    -RedirectStandardOutput "logs\server.out.log" `
    -RedirectStandardError "logs\server.err.log"

Write-Host "sip-server started, pid=$($p.Id)"
Write-Host "SIP: udp://0.0.0.0:5060  HTTP: http://localhost:8080"
