# Build/launch the independent document service using private local integration settings.
# Related: document-system/pom.xml, configure_integration.py and Stop-Documents.ps1.
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localDirectory = Join-Path $projectRoot '.local'
if (-not (Test-Path -LiteralPath (Join-Path $localDirectory 'office-local.properties'))) { throw '请先运行 scripts/configure_integration.py 配置应用接入。' }
if (Get-NetTCPConnection -LocalPort 9090 -State Listen -ErrorAction SilentlyContinue) { throw '9090 已占用，请确认占用者，不自动终止其他进程。' }
if (-not $SkipBuild) {
    Push-Location (Join-Path $projectRoot 'backend')
    try { & '.\mvnw.cmd' -f '../document-system/pom.xml' -B -ntp package; if ($LASTEXITCODE -ne 0) { throw '公文系统构建或测试失败。' } }
    finally { Pop-Location }
}
$jarPath = Join-Path $projectRoot 'document-system/target/document-system-0.1.0.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw '公文系统 JAR 不存在，请先构建。' }
$officeProcess = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList @('-jar', ('"' + $jarPath + '"')) -WorkingDirectory (Join-Path $projectRoot 'document-system') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $localDirectory 'office.log') -RedirectStandardError (Join-Path $localDirectory 'office-error.log') -PassThru
@{ pid=$officeProcess.Id; jar=$jarPath; root=$projectRoot } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $localDirectory 'office-server.json') -Encoding UTF8
for ($attempt=0; $attempt -lt 30; $attempt++) {
    Start-Sleep -Seconds 1
    if ($officeProcess.HasExited) { throw '公文系统启动失败，请检查 .local/office.log。' }
    try { $health = Invoke-RestMethod 'http://127.0.0.1:9090/office-api/health' -TimeoutSec 2; if ($health.status -eq 'UP') { Write-Output '公文系统已启动：http://127.0.0.1:9090'; exit 0 } }
    catch { if ($attempt -eq 29) { throw '公文系统健康检查未通过。' } }
}
