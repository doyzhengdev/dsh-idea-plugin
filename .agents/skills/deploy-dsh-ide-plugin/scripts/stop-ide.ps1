# 关闭本机 IDE（优雅退出，必要时兜底强杀）
#
# 对应 SKILL.md 第 2 步。优先让 IDE 自己退出，这样 LAF、窗口布局等配置能写回；
# IntelliJ 默认开启"失焦时保存文档"，所以正常 CloseMainWindow 不会丢未保存内容。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File stop-ide.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File stop-ide.ps1 -TimeoutSeconds 15 -Force

param(
    [int]$TimeoutSeconds = 30,
    [switch]$Force
)

$procs = @(Get-Process idea64 -ErrorAction SilentlyContinue)
if ($procs.Count -eq 0) {
    Write-Output "IDE 未运行"
    exit 0
}

foreach ($p in $procs) {
    Write-Output ("请求关闭 idea64 pid=" + $p.Id + " title=" + $p.MainWindowTitle)
    [void]$p.CloseMainWindow()
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
# IDEA 有时会忽略第一次 WM_CLOSE（本机实测要发第二次才退出），等待期间每 5 秒补发一次。
$nextNudge = (Get-Date).AddSeconds(5)
while ((Get-Date) -lt $deadline) {
    if (@(Get-Process idea64 -ErrorAction SilentlyContinue).Count -eq 0) {
        Write-Output "IDE 已退出"
        exit 0
    }
    if ((Get-Date) -ge $nextNudge) {
        foreach ($p in @(Get-Process idea64 -ErrorAction SilentlyContinue)) {
            [void]$p.CloseMainWindow()
        }
        $nextNudge = (Get-Date).AddSeconds(5)
    }
    Start-Sleep -Milliseconds 500
}

if ($Force) {
    Write-Output "等待超时，强制结束 idea64（可能丢失未保存内容）"
    Get-Process idea64 -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Seconds 2
    if (@(Get-Process idea64 -ErrorAction SilentlyContinue).Count -eq 0) {
        exit 0
    }
    exit 1
}

Write-Output ("等待 " + $TimeoutSeconds + " 秒后 IDE 仍在运行；确认没有未保存文件后可加 -Force")
exit 1
