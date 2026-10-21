# Stop only the process recorded by Start-Platform after checking its absolute JAR path.
# No files, databases or unrelated Java processes are removed.
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$recordPath = Join-Path $projectRoot '.local\server.json'
if (-not (Test-Path -LiteralPath $recordPath)) { throw '没有本项目启动记录，已停止自动关闭操作。' }
$serverRecord = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
$runningProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($serverRecord.pid)"
if (-not $runningProcess) { Write-Output '记录中的平台进程已退出。'; exit 0 }
if (-not $runningProcess.CommandLine.Contains($serverRecord.jar) -or $serverRecord.root -ne $projectRoot) { throw '进程与本项目启动记录不符，拒绝关闭。' }
Stop-Process -Id $serverRecord.pid
Write-Output '本项目平台进程已停止。'
