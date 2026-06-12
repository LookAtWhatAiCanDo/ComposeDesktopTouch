# Touch-injection test harness: synthesizes real touch input via InjectTouchInput.
# Usage:
#   .\TouchInject.ps1 -Mode drag -Title "compose-desktop-touch demo" -X1 0.16 -Y1 0.7 -X2 0.16 -Y2 0.3 -Steps 30 -DelayMs 8
#   .\TouchInject.ps1 -Mode flick ...   (drag with fast finish, releases mid-motion)
#   .\TouchInject.ps1 -Mode tap -X1 0.16 -Y1 0.3
# Coordinates are fractions of the target window rect (0..1).
param(
    [string]$Mode = "drag",
    [string]$Title = "compose-desktop-touch demo",
    [double]$X1 = 0.5, [double]$Y1 = 0.7,
    [double]$X2 = 0.5, [double]$Y2 = 0.3,
    [int]$Steps = 30,
    [int]$DelayMs = 8,
    [int]$HoldMs = 60
)

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Threading;

public static class TouchInjector {
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }

    [StructLayout(LayoutKind.Sequential)] public struct POINTER_INFO {
        public uint pointerType; public uint pointerId; public uint frameId; public uint pointerFlags;
        public IntPtr sourceDevice; public IntPtr hwndTarget;
        public POINT ptPixelLocation; public POINT ptHimetricLocation;
        public POINT ptPixelLocationRaw; public POINT ptHimetricLocationRaw;
        public uint dwTime; public uint historyCount; public int InputData; public uint dwKeyStates;
        public ulong PerformanceCount; public int ButtonChangeType;
    }

    [StructLayout(LayoutKind.Sequential)] public struct POINTER_TOUCH_INFO {
        public POINTER_INFO pointerInfo;
        public uint touchFlags; public uint touchMask;
        public RECT rcContact; public RECT rcContactRaw;
        public uint orientation; public uint pressure;
    }

    [DllImport("user32.dll", SetLastError = true)] public static extern bool InitializeTouchInjection(uint maxCount, uint dwMode);
    [DllImport("user32.dll", SetLastError = true)] public static extern bool InjectTouchInput(uint count, ref POINTER_TOUCH_INFO contacts);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr FindWindowW(IntPtr cls, string title);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);

    const uint PT_TOUCH = 2;
    const uint FLAG_DOWN   = 0x00010000 | 0x2 | 0x4; // DOWN | INRANGE | INCONTACT
    const uint FLAG_UPDATE = 0x00020000 | 0x2 | 0x4;
    const uint FLAG_UP     = 0x00040000;

    static POINTER_TOUCH_INFO Make(int x, int y, uint flags) {
        var c = new POINTER_TOUCH_INFO();
        c.pointerInfo.pointerType = PT_TOUCH;
        c.pointerInfo.pointerId = 0;
        c.pointerInfo.pointerFlags = flags;
        c.pointerInfo.ptPixelLocation.X = x;
        c.pointerInfo.ptPixelLocation.Y = y;
        c.touchFlags = 0;
        c.touchMask = 7; // CONTACTAREA | ORIENTATION | PRESSURE
        c.rcContact = new RECT { left = x - 2, top = y - 2, right = x + 2, bottom = y + 2 };
        c.orientation = 90;
        c.pressure = 32000;
        return c;
    }

    public static void Init() {
        if (!InitializeTouchInjection(1, 1)) // TOUCH_FEEDBACK_DEFAULT
            throw new Exception("InitializeTouchInjection failed: " + Marshal.GetLastWin32Error());
    }

    static void Inject(POINTER_TOUCH_INFO c) {
        if (!InjectTouchInput(1, ref c))
            throw new Exception("InjectTouchInput failed: " + Marshal.GetLastWin32Error());
    }

    public static void Down(int x, int y) { Inject(Make(x, y, FLAG_DOWN)); }
    public static void Update(int x, int y) { Inject(Make(x, y, FLAG_UPDATE)); }
    public static void Up(int x, int y) { Inject(Make(x, y, FLAG_UP)); }
}
"@

$hwnd = [TouchInjector]::FindWindowW([IntPtr]::Zero, $Title)
if ($hwnd -eq [IntPtr]::Zero) { throw "Window not found: $Title" }
[TouchInjector]::SetForegroundWindow($hwnd) | Out-Null
Start-Sleep -Milliseconds 200
$rect = New-Object TouchInjector+RECT
[TouchInjector]::GetWindowRect($hwnd, [ref]$rect) | Out-Null
$w = $rect.right - $rect.left; $h = $rect.bottom - $rect.top
Write-Host "window rect: ($($rect.left),$($rect.top)) ${w}x${h}"

function Px([double]$fx, [double]$fy) {
    ,@([int]($rect.left + $fx * $w), [int]($rect.top + $fy * $h))
}

[TouchInjector]::Init()
$p1 = Px $X1 $Y1; $p2 = Px $X2 $Y2

switch ($Mode) {
    "tap" {
        [TouchInjector]::Down($p1[0], $p1[1])
        Start-Sleep -Milliseconds $HoldMs
        [TouchInjector]::Up($p1[0], $p1[1])
        Write-Host "tap at $($p1[0]),$($p1[1])"
    }
    default {  # drag and flick share the move loop
        [TouchInjector]::Down($p1[0], $p1[1])
        Start-Sleep -Milliseconds 30
        for ($i = 1; $i -le $Steps; $i++) {
            $t = $i / $Steps
            $x = [int]($p1[0] + ($p2[0] - $p1[0]) * $t)
            $y = [int]($p1[1] + ($p2[1] - $p1[1]) * $t)
            [TouchInjector]::Update($x, $y)
            Start-Sleep -Milliseconds $DelayMs
        }
        if ($Mode -eq "drag") { Start-Sleep -Milliseconds 150; [TouchInjector]::Update($p2[0], $p2[1]) }
        [TouchInjector]::Up($p2[0], $p2[1])
        Write-Host "$Mode from $($p1[0]),$($p1[1]) to $($p2[0]),$($p2[1])"
    }
}
