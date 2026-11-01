# 启动服务端（双击即可，也可在计划任务里设开机自启）
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONUTF8 = '1'

Write-Host '检查防火墙规则（首次需要管理员权限，失败可忽略）…'
$rule = Get-NetFirewallRule -DisplayName 'XiaoduBrain' -ErrorAction SilentlyContinue
if (-not $rule) {
    try {
        New-NetFirewallRule -DisplayName 'XiaoduBrain' -Direction Inbound -Protocol TCP `
            -LocalPort 8765 -Action Allow -Profile Any | Out-Null
        Write-Host '  已放行 8765 端口'
    } catch {
        Write-Host '  未能自动放行，若设备连不上请手动允许 python.exe 入站'
    }
}

Write-Host ''
Write-Host '启动大脑…' -ForegroundColor Cyan
& python (Join-Path $root 'server\run.py')
