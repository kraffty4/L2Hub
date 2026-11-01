# Ctrl+V в чате клиента Lineage II (docs/RU_CHAT.md, «Вставка в чат»): клиент вставку не принимает, скрипт впечатывает
# текст из буфера обмена символами. Файлы клиента не меняются, Python не нужен: скрипт один и для лаунчера, и для пакета друзей.
#
# Ctrl+V занят, только пока на переднем плане окно L2.exe из папки ClientDir: в других программах и у чужих клиентов вставка
# обычная. Перевод строки становится пробелом, сообщение игрок отправляет сам. Второй экземпляр сразу выходит, скрипт
# заканчивается вместе с клиентом. Клиент должен идти без повышенных прав: окну администратора символы не послать.
param(
    [string]$ClientDir = $PSScriptRoot,
    [switch]$SelfTest
)

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

public static class L2HubChatPaste
{
    const uint WM_CHAR = 0x0102;
    const uint WM_HOTKEY = 0x0312;
    const uint PM_REMOVE = 1;
    const uint MOD_CONTROL = 2;
    const uint MOD_NOREPEAT = 0x4000;
    const uint VK_V = 0x56;
    const int VK_CONTROL = 0x11;
    const uint CF_UNICODETEXT = 13;
    const uint QUERY_LIMITED = 0x1000;
    const uint BACKUP_SEMANTICS = 0x02000000;
    const uint OPEN_EXISTING = 3;
    const uint SHARE_ALL = 7;
    const int HOTKEY_ID = 0x4C32;
    const int MAX_CHARS = 300;
    const int START_WAIT_MS = 180000;

    [StructLayout(LayoutKind.Sequential)]
    struct MSG { public IntPtr hwnd; public uint message; public IntPtr wParam; public IntPtr lParam; public uint time; public int x; public int y; }

    [StructLayout(LayoutKind.Sequential)]
    struct RECT { public int left; public int top; public int right; public int bottom; }

    [StructLayout(LayoutKind.Sequential)]
    struct GUITHREADINFO
    {
        public int cbSize; public int flags; public IntPtr hwndActive; public IntPtr hwndFocus; public IntPtr hwndCapture;
        public IntPtr hwndMenuOwner; public IntPtr hwndMoveSize; public IntPtr hwndCaret; public RECT rcCaret;
    }

    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")] static extern bool GetGUIThreadInfo(uint thread, ref GUITHREADINFO info);
    [DllImport("user32.dll")] static extern bool PostMessageW(IntPtr hwnd, uint message, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] static extern bool RegisterHotKey(IntPtr hwnd, int id, uint modifiers, uint key);
    [DllImport("user32.dll")] static extern bool UnregisterHotKey(IntPtr hwnd, int id);
    [DllImport("user32.dll")] static extern bool PeekMessageW(out MSG message, IntPtr hwnd, uint from, uint to, uint remove);
    [DllImport("user32.dll")] static extern short GetAsyncKeyState(int key);
    [DllImport("user32.dll")] static extern bool OpenClipboard(IntPtr hwnd);
    [DllImport("user32.dll")] static extern bool CloseClipboard();
    [DllImport("user32.dll")] static extern IntPtr GetClipboardData(uint format);
    [DllImport("kernel32.dll")] static extern IntPtr GlobalLock(IntPtr memory);
    [DllImport("kernel32.dll")] static extern bool GlobalUnlock(IntPtr memory);
    [DllImport("kernel32.dll")] static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool QueryFullProcessImageNameW(IntPtr process, uint flags, StringBuilder name, ref uint size);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr CreateFileW(string name, uint access, uint share, IntPtr security, uint disposition, uint flags, IntPtr template);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern uint GetFinalPathNameByHandleW(IntPtr file, StringBuilder path, uint size, uint flags);

    static readonly Dictionary<uint, bool> Known = new Dictionary<uint, bool>();

    // Настоящий путь: клиенты могут лежать за ссылкой-junction, а у процесса путь уже без неё.
    public static string FinalPath(string path)
    {
        IntPtr file = CreateFileW(path, 0, SHARE_ALL, IntPtr.Zero, OPEN_EXISTING, BACKUP_SEMANTICS, IntPtr.Zero);
        if (file == new IntPtr(-1))
        {
            return path;
        }
        try
        {
            StringBuilder result = new StringBuilder(1024);
            uint length = GetFinalPathNameByHandleW(file, result, (uint)result.Capacity, 0);
            if ((length == 0) || (length >= result.Capacity))
            {
                return path;
            }
            string text = result.ToString();
            return text.StartsWith(@"\\?\UNC\") ? @"\\" + text.Substring(8) : text.StartsWith(@"\\?\") ? text.Substring(4) : text;
        }
        finally
        {
            CloseHandle(file);
        }
    }

    static string ImagePath(uint pid)
    {
        IntPtr process = OpenProcess(QUERY_LIMITED, false, pid);
        if (process == IntPtr.Zero)
        {
            return "";
        }
        try
        {
            uint size = 1024;
            StringBuilder name = new StringBuilder((int)size);
            return QueryFullProcessImageNameW(process, 0, name, ref size) ? name.ToString() : "";
        }
        finally
        {
            CloseHandle(process);
        }
    }

    static bool IsClientPid(uint pid, string dir)
    {
        bool known;
        if (Known.TryGetValue(pid, out known))
        {
            return known;
        }
        string image = ImagePath(pid);
        known = (image.Length > 0) && image.EndsWith(@"\L2.exe", StringComparison.OrdinalIgnoreCase)
            && FinalPath(image).StartsWith(dir.TrimEnd('\\') + @"\", StringComparison.OrdinalIgnoreCase);
        Known[pid] = known;
        return known;
    }

    static bool IsClientWindow(IntPtr hwnd, string dir)
    {
        if (hwnd == IntPtr.Zero)
        {
            return false;
        }
        uint pid;
        GetWindowThreadProcessId(hwnd, out pid);
        return IsClientPid(pid, dir);
    }

    static bool ClientRunning(string dir)
    {
        foreach (Process process in Process.GetProcessesByName("L2"))
        {
            if (IsClientPid((uint)process.Id, dir))
            {
                return true;
            }
        }
        return false;
    }

    static string ClipboardText()
    {
        for (int i = 0; !OpenClipboard(IntPtr.Zero); i++)
        {
            if (i >= 5)
            {
                return "";
            }
            Thread.Sleep(20);
        }
        try
        {
            IntPtr handle = GetClipboardData(CF_UNICODETEXT);
            IntPtr pointer = handle == IntPtr.Zero ? IntPtr.Zero : GlobalLock(handle);
            if (pointer == IntPtr.Zero)
            {
                return "";
            }
            try
            {
                return Marshal.PtrToStringUni(pointer) ?? "";
            }
            finally
            {
                GlobalUnlock(handle);
            }
        }
        finally
        {
            CloseClipboard();
        }
    }

    // Одна строка чата: управляющие символы и переводы строк — пробелы, не длиннее MAX_CHARS и без половины суррогатной пары.
    public static string ChatLine(string text)
    {
        string line = Regex.Replace(text, @"[\x00-\x20\x7f]+", " ").Trim();
        if (line.Length > MAX_CHARS)
        {
            line = line.Substring(0, char.IsHighSurrogate(line[MAX_CHARS - 1]) ? MAX_CHARS - 1 : MAX_CHARS);
        }
        return line;
    }

    static void Paste(string dir)
    {
        IntPtr hwnd = GetForegroundWindow();
        if (!IsClientWindow(hwnd, dir))
        {
            return;
        }
        string line = ChatLine(ClipboardText());
        if (line.Length == 0)
        {
            return;
        }
        // Пока Ctrl зажат, клиент может счесть символы сочетаниями клавиш.
        for (int i = 0; ((GetAsyncKeyState(VK_CONTROL) & 0x8000) != 0) && (i < 100); i++)
        {
            Thread.Sleep(10);
        }
        GUITHREADINFO info = new GUITHREADINFO();
        info.cbSize = Marshal.SizeOf(typeof(GUITHREADINFO));
        uint pid;
        IntPtr target = GetGUIThreadInfo(GetWindowThreadProcessId(hwnd, out pid), ref info) && (info.hwndFocus != IntPtr.Zero) ? info.hwndFocus : hwnd;
        foreach (char unit in line)
        {
            if (!PostMessageW(target, WM_CHAR, new IntPtr(unit), new IntPtr(1)))
            {
                return;
            }
            Thread.Sleep(1);
        }
    }

    public static void Run(string clientDir)
    {
        bool first;
        using (Mutex mutex = new Mutex(true, @"Local\L2HubChatPaste", out first))
        {
            if (!first)
            {
                return;
            }
            string dir = FinalPath(clientDir);
            bool registered = false;
            bool seen = false;
            int gone = 0;
            int started = Environment.TickCount;
            int checkedAt = started - 2000;
            MSG message;
            try
            {
                while (true)
                {
                    if ((Environment.TickCount - checkedAt) >= 2000)
                    {
                        checkedAt = Environment.TickCount;
                        if (ClientRunning(dir))
                        {
                            seen = true;
                            gone = 0;
                        }
                        else if (seen ? (++gone >= 3) : ((Environment.TickCount - started) >= START_WAIT_MS))
                        {
                            return;
                        }
                    }
                    bool wanted = IsClientWindow(GetForegroundWindow(), dir);
                    if (wanted != registered)
                    {
                        if (wanted)
                        {
                            registered = RegisterHotKey(IntPtr.Zero, HOTKEY_ID, MOD_CONTROL | MOD_NOREPEAT, VK_V);
                        }
                        else
                        {
                            UnregisterHotKey(IntPtr.Zero, HOTKEY_ID);
                            registered = false;
                        }
                    }
                    while (PeekMessageW(out message, IntPtr.Zero, 0, 0, PM_REMOVE))
                    {
                        if ((message.message == WM_HOTKEY) && (message.wParam.ToInt64() == HOTKEY_ID))
                        {
                            Paste(dir);
                        }
                    }
                    Thread.Sleep(50);
                }
            }
            finally
            {
                if (registered)
                {
                    UnregisterHotKey(IntPtr.Zero, HOTKEY_ID);
                }
            }
        }
    }
}
'@

if ($SelfTest) {
    $cases = @(
        @("  первая`r`nвторая`tтретья  ", "первая вторая третья"),
        @("`r`n", ""),
        @(("x" * 400), ("x" * 300)),
        @((("x" * 299) + [char]::ConvertFromUtf32(0x1F600)), ("x" * 299))
    )
    $failed = 0
    foreach ($case in $cases) {
        if ([L2HubChatPaste]::ChatLine($case[0]) -cne $case[1]) {
            Write-Output "ChatLine: не сошлось на длине $($case[0].Length)"
            $failed++
        }
    }
    Write-Output "ChatLine: ошибок $failed; папка клиента: $([L2HubChatPaste]::FinalPath($ClientDir))"
    exit $failed
}

[L2HubChatPaste]::Run($ClientDir)
