$conn = Get-NetTCPConnection -LocalPort 8081 -ErrorAction SilentlyContinue | Select-Object -First 1
if ($conn) {
    Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
    Write-Host "Stopped PID $($conn.OwningProcess) on port 8081"
    Start-Sleep -Seconds 2
} else {
    Write-Host "Port 8081 is free"
}
Set-Location "$PSScriptRoot\ai-call-server"
mvn spring-boot:run -DskipTests
