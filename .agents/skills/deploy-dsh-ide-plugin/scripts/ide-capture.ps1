# 截取 IDE 窗口 / 屏幕区域
#
# 三种模式：
#   window  PrintWindow，只截主窗口（Swing 右键菜单等弹出窗口截不到）
#   region  按主窗口矩形用 CopyFromScreen，能截到落在窗口内的弹出菜单
#   screen  整块虚拟屏幕（多显示器）
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File ide-capture.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File ide-capture.ps1 -Mode region -OutFile E:\tmp\menu.png
#   powershell -NoProfile -ExecutionPolicy Bypass -File ide-capture.ps1 -ToggleToolWindows
#
# -ToggleToolWindows 会先按一次 Ctrl+Shift+F12（隐藏/恢复所有工具窗口），方便把编辑器占满再截图。

param(
    [ValidateSet("window", "region", "screen")]
    [string]$Mode = "window",
    [string]$OutFile = "$env:TEMP\ide-window.png",
    [switch]$ToggleToolWindows,
    [int]$DelayMs = 600
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
if ($hwnd -eq [IntPtr]::Zero) {
    Write-Error "拿不到 IDE 主窗口句柄"
    exit 2
}

if ($ToggleToolWindows) {
    [void][IdeWin32]::SetForegroundWindow($hwnd)
    Start-Sleep -Milliseconds 700
    if ([IdeWin32]::GetForegroundWindow() -ne $hwnd) {
        Write-Warning "IDE 不是前台窗口，跳过 -ToggleToolWindows（避免把 Ctrl+Shift+F12 发到其他应用）"
    }
    else {
        [System.Windows.Forms.SendKeys]::SendWait("^+{F12}")
        Start-Sleep -Milliseconds 1200
    }
}

$rect = New-Object IdeWin32+RECT
[IdeWin32]::GetWindowRect($hwnd, [ref]$rect) | Out-Null
Write-Output ("window=" + $rect.Left + "," + $rect.Top + " " + $rect.Right + "," + $rect.Bottom)

$bitmap = $null
$graphics = $null
try {
    switch ($Mode) {
        "window" {
            $width = $rect.Right - $rect.Left
            $height = $rect.Bottom - $rect.Top
            $bitmap = New-Object System.Drawing.Bitmap $width, $height
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            $hdc = $graphics.GetHdc()
            [IdeWin32]::PrintWindow($hwnd, $hdc, 2) | Out-Null
            $graphics.ReleaseHdc($hdc)
        }
        "region" {
            $width = $rect.Right - $rect.Left
            $height = $rect.Bottom - $rect.Top
            $bitmap = New-Object System.Drawing.Bitmap $width, $height
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, (New-Object System.Drawing.Size $width, $height))
        }
        "screen" {
            $bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen
            $bitmap = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            $graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
        }
    }

    if ($DelayMs -gt 0) { Start-Sleep -Milliseconds $DelayMs }
    $bitmap.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Output $OutFile
}
finally {
    if ($graphics) { $graphics.Dispose() }
    if ($bitmap) { $bitmap.Dispose() }
}
