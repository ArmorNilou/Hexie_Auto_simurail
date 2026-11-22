param([int]$Sleep = 0)
if ($Sleep -gt 0) { Start-Sleep -Seconds $Sleep }
$log = 'H:\柳树服beta2\.minecraft\versions\1.21.1-NeoForge_21.1.265\logs\latest.log'
$off = 'H:\蓝色肥鱼小家\.tmp\watch-offset.txt'
$fs = [IO.File]::Open($log,'Open','Read','ReadWrite'); $sr = New-Object IO.StreamReader($fs)
$txt = $sr.ReadToEnd(); $sr.Close(); $fs.Close()
$L = ($txt -split "`r?`n") | Where-Object { $_ -ne '' }
$prev = 0; if (Test-Path $off) { $prev = [int](Get-Content $off -Raw) }
if ($prev -gt $L.Count) { $prev = 0 }
$new = @(); if ($prev -lt $L.Count) { $new = $L[$prev..($L.Count-1)] }
[IO.File]::WriteAllText($off, [string]$L.Count)
function N($p) { ($L | Where-Object { $_ -match $p }).Count }
$hit = N '过节点 .*命中'; $miss = N '走错股道'; $tot = $hit + $miss
$rate = if ($tot) { [math]::Round(100.0*$hit/$tot,1) } else { 100 }
Write-Host ("[" + (Get-Date -Format 'HH:mm:ss') + "] 过岔 {0}/{1} ({2}%)  走错 {3}  撕裂 {4}  规划失败 {5}  自校准 {6}  新增 {7}" -f `
   $hit,$tot,$rate,$miss,(N '\[tear\]'),(N '\[drive\] 新段.*失败'),(N '过岔失手'),$new.Count)
$ev = $new | Where-Object { $_ -match 'HexieTrain/' } |
      Where-Object { $_ -match '走错股道|\[tear\]|过岔失手|面选反|规划失败|抱闸|注销虚拟|/ERROR\]|\[path\]|反向进站|\[switch\]|恢复' }
if ($ev) { $ev | Select-Object -First 12 | ForEach-Object { Write-Host ('   ! ' + ($_ -replace '^\[[^]]+\] \[[^]]+\] \[([^]]+)\]: ','')) } }
$sp = $L | Where-Object { $_ -match '\[diag\]' } | Select-Object -Last 1
if ($sp -match 'vphys=([-0-9.]+)') { $v=$matches[1]; if ($sp -match 'dDest=([-0-9.]+)') { $d=$matches[1] } else { $d='?' }; Write-Host ("   当前 v={0} m/s  dDest={1} m" -f $v,$d) }