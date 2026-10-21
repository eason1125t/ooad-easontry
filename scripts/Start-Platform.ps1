# Build and launch this project's Spring/Vue platform; local credentials stay in .local.
# Related: backend/mvnw.cmd, frontend/package.json and docs/运行与使用.md.
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localDirectory = Join-Path $projectRoot '.local'
if (-not (Test-Path -LiteralPath (Join-Path $localDirectory 'application-local.properties'))) { throw '请先运行 scripts/setup_local.py 完成本机初始化。' }
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { throw '8080 端口已被占用，请先确认或停止已有平台进程。' }
Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        & npm.cmd run build --prefix frontend
        if ($LASTEXITCODE -ne 0) { throw '前端类型检查或构建失败。' }
        Push-Location (Join-Path $projectRoot 'backend')
        try { & '.\mvnw.cmd' -B -ntp -DskipTests package; if ($LASTEXITCODE -ne 0) { throw '后端打包失败。' } }
        finally { Pop-Location }
    }
    $jarPath = Join-Path $projectRoot 'backend\target\access-platform-0.1.0.jar'
    if (-not (Test-Path -LiteralPath $jarPath)) { throw '后端构建产物不存在。' }
    $javaExecutable = (Get-Command java.exe).Source
    $platformProcess = Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', ('"' + $jarPath + '"')) -WorkingDirectory (Join-Path $projectRoot 'backend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $localDirectory 'backend.log') -RedirectStandardError (Join-Path $localDirectory 'backend-error.log') -PassThru
    @{ pid = $platformProcess.Id; jar = $jarPath; root = $projectRoot } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $localDirectory 'server.json') -Encoding UTF8
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        Start-Sleep -Seconds 1
        if ($platformProcess.HasExited) { throw '后端启动失败，请查看 .local/backend.log。' }
        try { $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 2; if ($health.status -eq 'UP') { Write-Output '平台已启动：http://127.0.0.1:8080'; exit 0 } }
        catch { if ($attempt -eq 29) { throw '后端健康检查未通过，请查看本机日志。' } }
    }
} finally { Pop-Location }
