# AI-CALL 一键启动（Windows PowerShell）
# 依赖：JDK 17+、Maven、Node.js/npm、MySQL、Redis 已安装并在 PATH 中

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

Write-Host "=== AI-CALL 启动脚本 ===" -ForegroundColor Cyan
Write-Host "项目根目录: $Root"

# 检查 Java 17+
$javaVersion = java -version 2>&1 | Out-String
if ($javaVersion -match 'version "1\.([0-9]+)' -or $javaVersion -match 'version "([0-9]+)') {
    $ver = [int]$Matches[1]
    if ($ver -lt 17 -and $javaVersion -notmatch 'version "17' -and $javaVersion -notmatch 'version "21') {
        Write-Warning "当前 Java 版本可能低于 17，Spring Boot 3 需要 JDK 17+。请安装 JDK 17 并设置 JAVA_HOME。"
    }
}

$Jdk17Candidates = @(
    "C:\Program Files\Java\jdk-17.0.18",
    "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot",
    $env:JAVA_HOME
) | Where-Object { $_ -and (Test-Path $_) }
$Jdk17 = $Jdk17Candidates | Select-Object -First 1
if (-not $Jdk17) {
    Write-Warning "未找到 JDK 17，请安装 JDK 17 并设置 JAVA_HOME"
}

Write-Host "`n[1/3] 启动后端 ai-call-server (端口 8081，真实外呼)..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList @(
    "-NoExit", "-Command",
    "`$env:JAVA_HOME='$Jdk17'; `$env:Path=`"`$env:JAVA_HOME\bin;`$env:Path`"; cd '$Root\ai-call-server'; mvn -s maven-settings-aliyun.xml spring-boot:run -DskipTests"
) -WindowStyle Normal

Start-Sleep -Seconds 3

Write-Host "[2/3] 启动管理端 ai-call-web (端口 5173)..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList @(
    "-NoExit", "-Command",
    "cd '$Root\ai-call-web'; if (-not (Test-Path node_modules)) { npm install }; npm run dev"
) -WindowStyle Normal

Write-Host "[3/3] 启动商户端 ai-call-work (端口 5174)..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList @(
    "-NoExit", "-Command",
    "cd '$Root\ai-call-work'; if (-not (Test-Path node_modules)) { npm install }; npm run dev"
) -WindowStyle Normal

Write-Host "`n=== 启动命令已发送到新窗口 ===" -ForegroundColor Green
Write-Host "管理端: http://127.0.0.1:5173  (admin / admin123)"
Write-Host "商户端: http://127.0.0.1:5174"
Write-Host "后端API: http://127.0.0.1:8081"
Write-Host "`n请确保 MySQL(3306/ai-call) 与 Redis(6379) 已运行。"
