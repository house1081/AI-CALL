# 管理员运行：安装 AI-CALL 可用 FreeSWITCH 配置（UTF-8 中文注释）
$ErrorActionPreference = "Stop"
$FsConf = "C:\Program Files\FreeSWITCH\conf"
$Src = Split-Path -Parent $MyInvocation.MyCommand.Path
$FsCli = "C:\Program Files\FreeSWITCH\fs_cli.exe"

Write-Host "复制网关与 ESL/ACL/Verto 配置..."
Copy-Item "$Src\sip_profiles\external\343543658.xml" "$FsConf\sip_profiles\external\343543658.xml" -Force
Copy-Item "$Src\sip_profiles\external\provide.xml" "$FsConf\sip_profiles\external\provide.xml" -Force
Remove-Item "$FsConf\sip_profiles\external\343543656.xml" -Force -ErrorAction SilentlyContinue
Copy-Item "$Src\autoload_configs\event_socket.conf.xml" "$FsConf\autoload_configs\event_socket.conf.xml" -Force
Copy-Item "$Src\autoload_configs\acl.conf.xml" "$FsConf\autoload_configs\acl.conf.xml" -Force
Copy-Item "$Src\autoload_configs\verto.conf.xml" "$FsConf\autoload_configs\verto.conf.xml" -Force

Write-Host "热加载..."
& $FsCli -x "reloadacl"
& $FsCli -x "reloadxml"
& $FsCli -x "sofia profile external killgw 343543656" 2>$null | Out-Null
& $FsCli -x "sofia profile external killgw example.com" 2>$null | Out-Null
& $FsCli -x "sofia profile external rescan"
Start-Sleep 2
foreach ($gw in @("343543658","gw-400","gw-wh","gw-ipwhite")) {
  & $FsCli -x "sofia profile external startgw $gw" 2>$null | Out-Null
}
Start-Sleep 4
& $FsCli -x "sofia status"
Write-Host "完成。主用 343543658 应为 REGED。"
