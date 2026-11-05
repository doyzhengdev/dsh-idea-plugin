# 在 IDE 窗口内点击一个坐标（相对主窗口左上角）
#
# 典型用途：右键菜单打开后，点某个菜单项。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File ide-click.ps1 -X 700 -Y 440
#   powershell -NoProfile -ExecutionPolicy Bypass -File ide-click.ps1 -X 700 -Y 440 -Right
#
# 会先校验 IDE 是前台窗口；不是则退出（exit 2），避免误点其他应用。

param(
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y,
    [switch]$Right,
    [int]$DelayMs = 300
)

if (-not ("IdeWin32" -as [type])) {
    Add-Type -Path (Join-Path $PSScriptRoot "ide-win32.cs")
}

$proc = Get-Process idea64 -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $proc) {
    Write-Error "IDE 未运行"
    exit 2
}
$hwnd = $proc.MainWindowHandle

# 刻意不调用 SetForegroundWindow：焦点切换会关掉已经打开的右键菜单。
# 调用方应当先让 IDE 处于前台（例如刚跑过 ide-editor-menu.ps1）。
if ([IdeWin32]::GetForegroundWindow() -ne $hwnd) {
    Write-Error "IDE 不是前台窗口；先点一下 IDE 或运行 ide-editor-menu.ps1，已放弃本次点击"
    exit 2
}

$rect = New-Object IdeWin32+RECT
[IdeWin32]::GetWindowRect($hwnd, [ref]$rect) | Out-Null
$screenX = $rect.Left + $X
$screenY = $rect.Top + $Y

[void][IdeWin32]::SetCursorPos($screenX, $screenY)
Start-Sleep -Milliseconds $DelayMs

if ($Right) {
    [IdeWin32]::mouse_event([IdeWin32]::MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, [IntPtr]::Zero)
    [IdeWin32]::mouse_event([IdeWin32]::MOUSEEVENTF_RIGHTUP, 0, 0, 0, [IntPtr]::Zero)
}
else {
    [IdeWin32]::mouse_event([IdeWin32]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [IntPtr]::Zero)
    [IdeWin32]::mouse_event([IdeWin32]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [IntPtr]::Zero)
}

Write-Output ("clicked " + $X + "," + $Y)
