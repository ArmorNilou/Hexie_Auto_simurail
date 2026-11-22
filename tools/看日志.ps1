# 格致路由 日志监控面板 —— 一条命令看全部关键指标
# 用法：.\tools\看日志.ps1            （只看当前 latest.log）
#       .\tools\看日志.ps1 -Tail 40   （顺带看最后 40 行）
param([int]$Tail = 0)

$log = 'H:\柳树服beta2\.minecraft\versions\1.21.1-NeoForge_21.1.265\logs\latest.log'
if (-not (Test-Path -LiteralPath $log)) { Write-Host "找不到日志: $log" -ForegroundColor Red; return }

# 游戏在写，共享读
$fs = [IO.File]::Open($log, 'Open', 'Read', 'ReadWrite')
$sr = New-Object IO.StreamReader($fs)
$txt = $sr.ReadToEnd()
$sr.Close(); $fs.Close()

$L = ($txt -split "`r?`n") | Where-Object { $_ -ne '' }
$mod = ($L | Where-Object { $_ -match 'Hexie:Auto_simuroute' } | Select-Object -First 1)
$ver = if ($mod -match 'simuroute ([\d.]+)') { $matches[1] } else { '?' }

function N($pattern) { ($L | Where-Object { $_ -match $pattern }).Count }

Write-Host ("=== 格致路由 监控  " + (Get-Date -Format 'HH:mm:ss') + "  版本 " + $ver + "  日志 " + $L.Count + " 行 ===") -ForegroundColor Cyan

$hit   = N '过节点 .*命中'
$miss  = N '走错股道'
$tot   = $hit + $miss
$rate  = if ($tot -gt 0) { [math]::Round(100.0 * $hit / $tot, 1) } else { 100 }
$color = if ($miss -eq 0) { 'Green' } elseif ($rate -ge 95) { 'Yellow' } else { 'Red' }
Write-Host ("过岔     命中 {0,-4} 走错 {1,-3} 命中率 {2}%" -f $hit, $miss, $rate) -ForegroundColor $color

Write-Host ("导航     喂 currentPath {0,-4} ｜ 规划 ok {1,-3} 失败 {2}" -f (N '\[path\]'), (N '\[drive\] 新段.*ok'), (N '\[drive\] 新段.*失败'))
Write-Host ("安全     撕裂 {0} ｜ 反向进站 {1} ｜ 自校准翻号 {2}" -f (N '\[tear\]'), (N '反向进站'), (N '过岔失手'))
Write-Host ("其它     岔道方块 {0} ｜ 组内让权 {1} ｜ 建车/注销 {2}" -f (N '\[switch\]'), (N '让权'), (N '\[presence\]'))

$warn = ($L | Where-Object { $_ -match 'HexieTrain' -and $_ -match '/WARN\]|/ERROR\]' -and $_ -notmatch '\[crash\]' })
Write-Host ("警告(除撞车噪声) {0} 条" -f $warn.Count) -ForegroundColor $(if ($warn.Count -eq 0) { 'Green' } else { 'Yellow' })
if ($warn.Count -gt 0) { $warn | Select-Object -Last 5 | ForEach-Object { Write-Host ('   ' + ($_ -replace '^\[[^]]+\] \[[^]]+\] \[([^]]+)\]: ', '')) } }

$last = $L | Where-Object { $_ -match '\[HexieTrain\]\[diag\]' } | Select-Object -Last 1
if ($last -match 'vphys=([-0-9.]+).*dDest=([-0-9.]+)') {
   Write-Host ("当前     物理速度 {0} m/s ｜ 距站 {1} m" -f $matches[1], $matches[2]) -ForegroundColor Cyan
}

if ($Tail -gt 0) { Write-Host '--- 尾部 ---'; $L | Select-Object -Last $Tail | ForEach-Object { Write-Host ('   ' + ($_ -replace '^\[[^]]+\] \[[^]]+\] \[([^]]+)\]: ', '')) } }