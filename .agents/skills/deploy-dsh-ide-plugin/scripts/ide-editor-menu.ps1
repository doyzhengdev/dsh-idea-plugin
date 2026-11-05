# 在 IDE 编辑器里拖选一段代码、打开右键菜单并截图
#
# 用途：验证编辑器右键菜单项（例如插件新增的编辑器动作）是否存在、是否可用。
#
# 坐标全部相对 IDE 主窗口左上角；先用 ide-capture.ps1 截一张图，按图里的编辑器位置传参。
# 截的是 CopyFromScreen 区域图，因为 Swing 右键菜单是独立弹窗，PrintWindow 截不到。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File ide-editor-menu.ps1 -X 600 -StartY 300 -EndY 430 -MenuY 380
#   powershell -NoProfile -ExecutionPolicy Bypass -File ide-editor-menu.ps1 -X 600 -StartY 300 -EndY 430 -MenuY 380 -OutFile E:\tmp\menu.png
#
# 注意事项：
#   - 脚本会先校验前台窗口确实是 IDE，否则直接退出（exit 2），避免把键鼠事件打到别的应用。
#   - 不要点断点槽（编辑器左侧几像素），会弹出断点对话框而不是右键菜单。

param(
    [int]$X = 600,
    [int]$StartY = 300,
    [int]$EndY = 430,
    [int]$MenuY = 380,
    [string]$OutFile = "$env:TEMP\ide-editor-menu.png",
    [int]$StepMs = 20
)

Add-Type -AssemblyName System.Windows.Forms, System.Drawing
if (-not ("IdeWin32" -as [type])) {
    Add-Type -Path (Join-Path $PSScriptRoot "ide-win32.cs")
}

$proc = Get-Process idea64 -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $proc) {
    Write-Error "IDE 未运行"
    exit 2
}
$hwnd = $proc.MainWindowHandle

# 关掉可能已经打开的弹窗（上一次遗留的菜单/对话框）。
[System.Windows.Forms.SendKeys]::SendWait("{ESC}")
Start-Sleep -Milliseconds 400
[void][IdeWin32]::SetForegroundWindow($hwnd)
Start-Sleep -Milliseconds 700
if ([IdeWin32]::GetForegroundWindow() -ne $hwnd) {
    Write-Error "IDE 不是前台窗口，已放弃以免误发键鼠事件"
    exit 2
}

$rect = New-Object IdeWin32+RECT
[IdeWin32]::GetWindowRect($hwnd, [ref]$rect) | Out-Null
$screenX = $rect.Left + $X
$startScreenY = $rect.Top + $StartY
$endScreenY = $rect.Top + $EndY
$menuScreenY = $rect.Top + $MenuY

# 鼠标拖选：必须逐点移动，一次 SetCursorPos 到终点不会产生选区。
[void][IdeWin32]::SetCursorPos($screenX, $startScreenY)
Start-Sleep -Milliseconds 250
[IdeWin32]::mouse_event([IdeWin32]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [IntPtr]::Zero)
Start-Sleep -Milliseconds 150
for ($y = $startScreenY; $y -le $endScreenY; $y += 10) {
    [void][IdeWin32]::SetCursorPos($screenX, $y)
    Start-Sleep -Milliseconds $StepMs
}
[IdeWin32]::mouse_event([IdeWin32]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [IntPtr]::Zero)
Start-Sleep -Milliseconds 500

# 在选区内右键，打开编辑器上下文菜单。
[void][IdeWin32]::SetCursorPos($screenX, $menuScreenY)
Start-Sleep -Milliseconds 200
[IdeWin32]::mouse_event([IdeWin32]::MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, [IntPtr]::Zero)
[IdeWin32]::mouse_event([IdeWin32]::MOUSEEVENTF_RIGHTUP, 0, 0, 0, [IntPtr]::Zero)
Start-Sleep -Milliseconds 1200

$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top
$bitmap = New-Object System.Drawing.Bitmap $width, $height
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
try {
    $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, (New-Object System.Drawing.Size $width, $height))
    $bitmap.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Output $OutFile
}
finally {
    $graphics.Dispose()
    $bitmap.Dispose()
}
