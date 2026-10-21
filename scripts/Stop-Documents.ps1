# Stop only the recorded document-system JAR process, preserving files and H2 data.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$recordPath = Join-Path $projectRoot '.local/office-server.json'
if (-not (Test-Path -LiteralPath $recordPath)) { throw '没有公文系统启动记录。' }
$record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
$officeProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($record.pid)"
if (-not $officeProcess) { Write-Output '公文系统进程已退出。'; exit 0 }
if ($record.root -ne $projectRoot -or -not $officeProcess.CommandLine.Contains($record.jar)) { throw '进程身份不符，拒绝关闭。' }
Stop-Process -Id $record.pid
Write-Output '公文系统已停止。'
