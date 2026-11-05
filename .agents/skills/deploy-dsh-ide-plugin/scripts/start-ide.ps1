# 启动本机 IDE 并打开插件工程
#
# 对应 SKILL.md 第 4 步。窗口出现约需 30–45 秒，别在启动过程中抓状态。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File start-ide.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File start-ide.ps1 -WaitSeconds 45

param(
    [string]$IdePath = "C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.2\bin\idea64.exe",
    [string]$ProjectPath = "E:\gitee\doyzheng\dsh-ide\dsh-idea-plugin",
    [int]$WaitSeconds = 0
)

if (-not (Test-Path $IdePath)) {
    Write-Error ("找不到 IDE 可执行文件：" + $IdePath)
    exit 2
}
if (-not (Test-Path $ProjectPath)) {
    Write-Error ("找不到工程目录：" + $ProjectPath)
    exit 2
}

if (@(Get-Process idea64 -ErrorAction SilentlyContinue).Count -gt 0) {
    Write-Warning "已有 idea64 在运行；再次启动可能产生第二个实例，先确认是否需要 stop-ide.ps1"
}

Start-Process -FilePath $IdePath -ArgumentList $ProjectPath -WorkingDirectory $ProjectPath
Write-Output "已启动 IDE"

if ($WaitSeconds -gt 0) {
    Start-Sleep -Seconds $WaitSeconds
    Write-Output ("已等待 " + $WaitSeconds + " 秒")
}
