param()
$server = Start-Process -FilePath python -ArgumentList '-m http.server 34570 --bind 0.0.0.0' -WorkingDirectory 'D:\AI\agent\codex\android\tv\tools\dead_test' -WindowStyle Hidden -PassThru
$server.Id | Out-File 'D:\AI\agent\codex\android\tv\tools\dead_test\server.pid'
Start-Sleep -Seconds 2
try {
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:34570/dead.txt' -TimeoutSec 5
    Write-Output "OK $($r.StatusCode) $($r.Content.Trim())"
} catch {
    Write-Output "ERR $($_.Exception.Message)"
}
