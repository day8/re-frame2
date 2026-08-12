<#
  THE OS-LEVEL INPUT DRIVER for the scripted native-IME witness (rf2-hic-016).

  A long-lived request/response process, spoken to over stdin by
  `implementation/scripts/run-hicasso-native-ime-witness.cjs`. It exists
  because the thing this witness has to produce — a REAL Windows IME
  composition, with a real candidate window and a real ESC abort — is
  produced by the OS input stack and by nothing a page script can call. CDP's
  `Input.imeSetComposition` is Chromium's protocol; Playwright's
  `keyboard.type` posts synthesised events that never reach an IME at all.
  `SendInput` is the one door left, and it types into whatever window has
  focus.

  ## The interlock, which is the reason this is a separate process

  `SendInput` does not aim. It delivers to the foreground window — the
  operator's editor, their terminal, their bank — so a driver that can type
  is a driver that can type ANYWHERE, and it must not be startable by
  accident. Two locks, and both are here rather than in the caller:

  1. **`-Armed`.** Without it every mutating verb answers `REFUSED` and
     nothing is sent. The dry run starts this process WITHOUT the switch, so
     the whole pipeline — window lookup, IME interrogation, key-plan
     transport — is exercised end to end while the one call that would touch
     the desktop is refused BY CONSTRUCTION rather than by a branch the
     caller remembered to take.
  2. **Foreground re-verification, per key.** `KEYS` reads
     `GetForegroundWindow()` before EVERY keystroke and aborts the whole
     batch the moment it is not the window it was told to type into. If the
     operator alt-tabs mid-run, the run stops; it does not carry on typing
     romaji into whatever arrived.

  Read-only verbs (`PING`, `LAYOUTS`, `FIND`, `IMESTATE`) are always
  available. They are what the dry run is made of.

  ## Protocol

  One request per line on stdin, one reply per line on stdout:

      <id> <VERB> [args...]        ->    <id> OK [payload]
                                         <id> ERR <message>
                                         <id> REFUSED <message>

  `id` is an opaque token the caller correlates on. Payloads are compressed
  JSON. String arguments arrive base64-encoded (UTF-16LE), because a window
  title is arbitrary text and this protocol is whitespace-delimited.

  ## Verbs

    PING                          -> {armed, pid, psVersion}
    LAYOUTS                       -> {layouts:[hkl...], langids:[hex...]}
    FIND <b64 title-substring>    -> {hwnd, title, matches}
    IMESTATE <hwnd>               -> {hkl, langid, japanese, open, conversion,
                                      native, foreground, isForeground}
    RESOLVE <tokens> [hwnd]       -> {tokens, vks, scans}
    FOREGROUND <hwnd>             -> {isForeground}                    [armed]
    IMEON <hwnd>                  -> {requested, hkl, langid, ...}     [armed]
    IMECONV <hwnd>                -> {conversion, native, ...}         [armed]
    KEYS <hwnd> <tokens> <delay>  -> {sent, tokens, scans}             [armed]
    QUIT                          -> OK

  ## What it does NOT do

  It does not install a Japanese IME, and it will not pretend one is there:
  `LAYOUTS` is the preflight, and the caller refuses to arm when no
  `0x0411` layout is installed. It does not choose candidates by reading the
  candidate window — the key plan does that, by sending Space and Enter the
  way a person would. And `IMEON` is a REQUEST, not a guarantee: the modern
  Microsoft IME is a TSF text service, and the IMM32 messages below reach it
  through a compatibility layer that a given browser build may not honour.
  `IMESTATE` exists as a separate verb so the caller can ASK and then look —
  but see the next section for what that reading is worth, and for the one
  thing it must never be used as.

  ## What `IMESTATE` is worth: the write-through finding of 2026-08-12

  Two armed runs that day, and between them they emptied this verb of any
  authority.

  The FIRST got the whole way in — foreground seized on all three engines,
  romaji and ESC both delivered — and still decided nothing: `IMESTATE` read
  `langid 0x0411`, `japanese: true` and **`open: 0`** on Chromium, Firefox
  and WebKit alike. The TSF IME had ignored `IMC_SETOPENSTATUS`, exactly as
  the paragraph above said it might. The answer taken then was to open the
  IME by its OWN TOGGLE — `kanji` (半角/全角, VK 0x19) through `KEYS`, the
  door the keystrokes were demonstrably arriving by — and to require
  `open: 1` back from `IMESTATE` before typing anything.

  The SECOND run returned `open: 1`, `conversion: 9`, `native: true` on
  Chromium — engaged, by that gate — while `compositionstart` stayed at ZERO
  on every check and the romaji went into the box as literal ASCII. And
  `conversion: 9` is `IME_CMODE_HIRAGANA`: the exact constant
  `RequestJapanese` had just written. **The IMM32 shim's state is
  WRITE-THROUGH.** `IMC_GETOPENSTATUS` handed back the bit `IMC_SETOPENSTATUS`
  had set, on an input context the TSF service was not reading, so the gate
  proved a value had been written and nothing else. It could not have failed.

  So `IMESTATE`, `IMEON` and `IMECONV` are ATTEMPTS AND OBSERVATIONS, and the
  caller uses them as such: it prints the reading and gates on CONDUCT
  instead — one romaji letter typed into the page, and a `compositionstart`
  read off the page's own event stream. Nothing this driver can write to an
  input context produces one of those.

  ## Why the toggle went nowhere: `MapVirtualKey` and the zero scan code

  The same run leaves the toggle itself under suspicion, and the cause was in
  `SendKey`. It resolved every scan code with `MapVirtualKeyW`, which maps
  against the layout of the CALLING thread — this driver's, which is English.
  `VK_KANJI` has no key on that layout, so the call answered 0, and a
  zero-scan keystroke is one an IME may decline (the comment on `SendKey`
  said so all along). Measured here: `MapVirtualKeyW(0x19, VK_TO_VSC)` is
  `0x00`, and so, it turns out, is `MapVirtualKeyExW(0x19, VK_TO_VSC,
  0x04110411)` — `VK_KANJI` is an IMM32 virtual key with no physical key
  behind it in any layout table. `ScanFor` therefore asks the target
  window's own layout first, falls back to this thread's, and puts a LITERAL
  `0x29` under the IME toggle beneath both, which is the only one of the
  three that can answer for it.

  BUT THE LITERAL WAS NEVER DELIVERED AS THE KEY'S IDENTITY, and merged-PR
  audit #7956 is right about it. `SendKey` writes `wScan` but sets neither
  `KEYEVENTF_SCANCODE` (0x0008) nor anything else in `dwFlags` on the
  key-down. Microsoft's `KEYBDINPUT` contract is explicit — "If specified,
  wScan identifies the key and wVk is ignored" — so WITHOUT that flag the
  input is defined in terms of `wVk`, and the calculated `0x29` is carried
  rather than used to name the key.
  <https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-keybdinput>

  So `KEYS` and `RESOLVE` report the scan this driver CALCULATED, not one it
  can prove `SendInput` delivered. A zero is still never invisible, which is
  what those lines were added for. The flag is deliberately NOT set now: no
  armed run is sanctioned (see the scripted-witness doc §11), so selecting
  scan-code mode would change the identity of EVERY key this driver sends —
  romaji included — with no run permitted that could witness the change. An
  unwitnessable behaviour change to an artefact retained as a record is worse
  than an accurate account of what it does, so the account is corrected here
  instead.
#>

[CmdletBinding()]
param(
  # Without this, every verb that could touch the desktop answers REFUSED.
  [switch]$Armed
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

public static class Rf2Ime
{
  // --- window discovery ---------------------------------------------------
  private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

  [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc cb, IntPtr p);
  [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowTextLengthW(IntPtr h);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowTextW(IntPtr h, StringBuilder s, int max);

  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);

  // --- input locale / IME -------------------------------------------------
  [DllImport("user32.dll")] private static extern IntPtr GetKeyboardLayout(uint threadId);
  [DllImport("user32.dll")] private static extern int GetKeyboardLayoutList(int n, [Out] IntPtr[] list);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern IntPtr LoadKeyboardLayoutW(string id, uint flags);
  [DllImport("user32.dll")] private static extern IntPtr PostMessageW(IntPtr h, uint msg, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] private static extern IntPtr SendMessageW(IntPtr h, uint msg, IntPtr w, IntPtr l);
  [DllImport("imm32.dll")] private static extern IntPtr ImmGetDefaultIMEWnd(IntPtr h);

  // --- synthetic input ----------------------------------------------------
  [DllImport("user32.dll", SetLastError = true)] private static extern uint SendInput(uint n, INPUT[] inputs, int size);
  [DllImport("user32.dll")] private static extern uint MapVirtualKeyW(uint code, uint mapType);
  [DllImport("user32.dll")] private static extern uint MapVirtualKeyExW(uint code, uint mapType, IntPtr hkl);

  [StructLayout(LayoutKind.Sequential)]
  private struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Sequential)]
  private struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Sequential)]
  private struct HARDWAREINPUT { public uint uMsg; public ushort wParamL; public ushort wParamH; }
  [StructLayout(LayoutKind.Explicit)]
  private struct InputUnion
  {
    [FieldOffset(0)] public MOUSEINPUT mi;
    [FieldOffset(0)] public KEYBDINPUT ki;
    [FieldOffset(0)] public HARDWAREINPUT hi;
  }
  [StructLayout(LayoutKind.Sequential)]
  private struct INPUT { public uint type; public InputUnion U; }

  private const uint INPUT_KEYBOARD = 1;
  private const uint KEYEVENTF_KEYUP = 0x0002;

  private const uint WM_INPUTLANGCHANGEREQUEST = 0x0050;
  private const uint WM_IME_CONTROL = 0x0283;
  private const int IMC_GETCONVERSIONMODE = 0x0001;
  private const int IMC_SETCONVERSIONMODE = 0x0002;
  private const int IMC_GETOPENSTATUS = 0x0005;
  private const int IMC_SETOPENSTATUS = 0x0006;
  // The bit that says the IME is in a JAPANESE READING mode at all. Without
  // it romaji is typed straight through as ASCII and no composition starts,
  // whatever the open status says.
  private const int IME_CMODE_NATIVE = 0x0001;
  // Hiragana: native reading, full-width. What a person selects when they
  // pick "ひらがな" in the language bar.
  private const int IME_CMODE_HIRAGANA = 0x0009;   // NATIVE | FULLSHAPE

  private const uint MAPVK_VK_TO_VSC = 0;
  private const uint VK_KANJI = 0x19;
  // 半角/全角 sits where the backtick does on a US board: set-1 scan code
  // 0x29. A LITERAL, and load-bearing rather than belt-and-braces. Measured
  // on this machine, with the Japanese layout installed:
  //
  //     MapVirtualKeyW  (0x19, VK_TO_VSC)             -> 0x00
  //     MapVirtualKeyExW(0x19, VK_TO_VSC, 0x04110411) -> 0x00
  //
  // VK_KANJI is an IMM32 virtual key with no physical key behind it in ANY
  // layout table — the JIS 半角/全角 key reaches Windows as VK_OEM_AUTO — so
  // no lookup can supply this and only a literal can.
  private const ushort SCAN_HANKAKU_ZENKAKU = 0x29;

  /// Every visible top-level window whose title contains `needle`. Returned
  /// with the match COUNT rather than only the first hit: two windows
  /// answering to the same nonce means the caller is about to type into a
  /// window it did not identify, and that has to be visible.
  public static IntPtr[] FindWindows(string needle)
  {
    List<IntPtr> hits = new List<IntPtr>();
    EnumWindows(delegate (IntPtr h, IntPtr p)
    {
      if (!IsWindowVisible(h)) return true;
      int len = GetWindowTextLengthW(h);
      if (len <= 0) return true;
      StringBuilder sb = new StringBuilder(len + 2);
      GetWindowTextW(h, sb, sb.Capacity);
      if (sb.ToString().IndexOf(needle, StringComparison.Ordinal) >= 0) hits.Add(h);
      return true;
    }, IntPtr.Zero);
    return hits.ToArray();
  }

  public static string TitleOf(IntPtr h)
  {
    int len = GetWindowTextLengthW(h);
    if (len <= 0) return "";
    StringBuilder sb = new StringBuilder(len + 2);
    GetWindowTextW(h, sb, sb.Capacity);
    return sb.ToString();
  }

  /// The input locale of the thread that OWNS the window — not of this
  /// process. "Is the Japanese IME actually active in the browser?" is a
  /// question about the browser's UI thread, and asking it about ourselves
  /// is how a witness convinces itself of something untrue.
  public static long LayoutOf(IntPtr hwnd)
  {
    uint pid;
    uint tid = GetWindowThreadProcessId(hwnd, out pid);
    if (tid == 0) return 0;
    return GetKeyboardLayout(tid).ToInt64();
  }

  public static long[] InstalledLayouts()
  {
    int n = GetKeyboardLayoutList(0, null);
    if (n <= 0) return new long[0];
    IntPtr[] buf = new IntPtr[n];
    GetKeyboardLayoutList(n, buf);
    long[] outp = new long[n];
    for (int i = 0; i < n; i++) outp[i] = buf[i].ToInt64();
    return outp;
  }

  public static long ImeQuery(IntPtr hwnd, int what)
  {
    IntPtr ime = ImmGetDefaultIMEWnd(hwnd);
    if (ime == IntPtr.Zero) return -1;
    return SendMessageW(ime, WM_IME_CONTROL, new IntPtr(what), IntPtr.Zero).ToInt64();
  }

  /// Ask the window's thread to switch to the Japanese input locale, open
  /// the IME and put it in Hiragana. A REQUEST: `WM_INPUTLANGCHANGEREQUEST`
  /// is posted (the owning thread applies it, not us), and the IMM32
  /// control messages reach a TSF text service through a compatibility
  /// shim that need not honour them — and whose stored state answers the
  /// matching GETs whether it honoured them or not. The caller verifies
  /// afterwards, by conduct, not by reading these values back.
  public static long RequestJapanese(IntPtr hwnd)
  {
    IntPtr hkl = LoadKeyboardLayoutW("00000411", 0x00000001 /* KLF_ACTIVATE */);
    if (hkl == IntPtr.Zero) return 0;
    PostMessageW(hwnd, WM_INPUTLANGCHANGEREQUEST, IntPtr.Zero, hkl);
    IntPtr ime = ImmGetDefaultIMEWnd(hwnd);
    if (ime != IntPtr.Zero)
    {
      SendMessageW(ime, WM_IME_CONTROL, new IntPtr(IMC_SETOPENSTATUS), new IntPtr(1));
      SendMessageW(ime, WM_IME_CONTROL, new IntPtr(IMC_SETCONVERSIONMODE), new IntPtr(IME_CMODE_HIRAGANA));
    }
    return hkl.ToInt64();
  }

  /// Re-assert Hiragana on an IME that is already open. Split out of
  /// `RequestJapanese` because the two requests answer different questions:
  /// that one asks for the input locale AND the open state AND the mode in
  /// one shot, while this one is what the caller sends AFTER opening the IME
  /// by its own toggle key — a toggle says nothing about which reading mode
  /// the IME came back in, and an alphanumeric one turns the romaji that
  /// follows back into ASCII. Returns -1 when there is no IME window to ask.
  public static long RequestHiragana(IntPtr hwnd)
  {
    IntPtr ime = ImmGetDefaultIMEWnd(hwnd);
    if (ime == IntPtr.Zero) return -1;
    return SendMessageW(ime, WM_IME_CONTROL,
                        new IntPtr(IMC_SETCONVERSIONMODE),
                        new IntPtr(IME_CMODE_HIRAGANA)).ToInt64();
  }

  public static int ImeOpenStatus(IntPtr hwnd) { return (int)ImeQuery(hwnd, IMC_GETOPENSTATUS); }
  public static int ImeConversion(IntPtr hwnd) { return (int)ImeQuery(hwnd, IMC_GETCONVERSIONMODE); }

  /// Does a conversion mode carry the NATIVE bit? A PREDICATE over the value
  /// rather than a second query, so the flag reported alongside `conversion`
  /// is derived from the very reading printed next to it. The sign is tested
  /// first because `ImeQuery` answers -1 when there is no IME window, and
  /// `-1 & 1` is 1: an UNANSWERABLE query must not read as a satisfied one.
  public static bool IsNativeMode(long conversion)
  {
    return conversion >= 0 && (conversion & IME_CMODE_NATIVE) == IME_CMODE_NATIVE;
  }

  /// The scan code a physical keyboard would carry for `vk`, resolved UNDER
  /// THE LAYOUT OF THE WINDOW BEING TYPED INTO.
  ///
  /// `MapVirtualKeyW` maps against the CALLING thread's layout — this
  /// driver's, which is English — where `VK_KANJI` is not a key and the
  /// answer is 0. That is how the 半角/全角 toggle came to be sent all of
  /// 2026-08-12 with a zero scan code, which is precisely the keystroke
  /// `SendKey` below says an IME may decline.
  ///
  /// So: the window's own HKL first (`MapVirtualKeyExW`), this thread's
  /// layout as the fallback, and a literal under the IME toggle beneath
  /// both. THE LITERAL IS THE ONE THAT ANSWERS FOR THAT KEY — measured, the
  /// Japanese HKL returns 0 for `VK_KANJI` as well (see
  /// `SCAN_HANKAKU_ZENKAKU`). The layout lookup is still the right first
  /// question for the ordinary keys, whose scan codes DO differ between
  /// layouts, and a zero surviving all three is a real answer that `KEYS`
  /// reports rather than hides.
  ///
  /// WHAT THIS DOES NOT ESTABLISH: that the answer reached the target as the
  /// key's identity. `SendKey` does not set `KEYEVENTF_SCANCODE`, so the
  /// keystroke is defined by `wVk` and this scan rides along beside it. The
  /// number below is the one CALCULATED; see the header.
  public static ushort ScanFor(ushort vk, IntPtr hwnd)
  {
    long hkl = LayoutOf(hwnd);
    ushort scan = 0;
    if (hkl != 0) scan = (ushort)MapVirtualKeyExW(vk, MAPVK_VK_TO_VSC, new IntPtr(hkl));
    if (scan == 0) scan = (ushort)MapVirtualKeyW(vk, MAPVK_VK_TO_VSC);
    if (scan == 0 && vk == VK_KANJI) scan = SCAN_HANKAKU_ZENKAKU;
    return scan;
  }

  /// One key down+up through the OS input stack. The virtual key carries a
  /// real scan code because an IME reads both, and a keystroke with a zero
  /// scan code is one an IME may decline to compose from. The scan is a
  /// PARAMETER rather than something computed here: the only correct answer
  /// depends on the window being typed into, which this function is not
  /// told, and a witness has to be able to print what it actually sent.
  ///
  /// `dwFlags` deliberately does NOT carry `KEYEVENTF_SCANCODE` (0x0008).
  /// Under that flag `wScan` would identify the key and `wVk` would be
  /// IGNORED — for every key here, not just the toggle — and no armed run is
  /// sanctioned that could witness the difference. So the input is defined by
  /// `wVk`, `wScan` rides beside it, and nothing in this file may be read as
  /// proof that a literal physical scan was delivered. See the header.
  public static uint SendKey(ushort vk, ushort scan)
  {
    INPUT[] two = new INPUT[2];
    two[0].type = INPUT_KEYBOARD;
    two[0].U.ki.wVk = vk; two[0].U.ki.wScan = scan; two[0].U.ki.dwFlags = 0;
    two[1].type = INPUT_KEYBOARD;
    two[1].U.ki.wVk = vk; two[1].U.ki.wScan = scan; two[1].U.ki.dwFlags = KEYEVENTF_KEYUP;
    return SendInput(2, two, Marshal.SizeOf(typeof(INPUT)));
  }
}
'@

# The token vocabulary the key plans are written in. A closed set: an
# unknown token is an ERR rather than a silently-skipped keystroke, because
# a witness that types six of seven romaji letters and reports success is
# worse than one that stops.
$VK = @{
  'space' = 0x20; 'enter' = 0x0D; 'escape' = 0x1B; 'backspace' = 0x08;
  'tab' = 0x09; 'left' = 0x25; 'up' = 0x26; 'right' = 0x27; 'down' = 0x28;
  'home' = 0x24; 'end' = 0x23; 'delete' = 0x2E;
  # The IME's own keys: 半角/全角 toggles the IME, 変換 opens conversion,
  # F7 forces katakana. `kanji` is no longer decorative — it is what the
  # caller's engage ladder sends, through `KEYS`, while the page is still
  # showing no `compositionstart`. The other three are here so a key plan
  # can name them, and no default plan does.
  'kanji' = 0x19; 'convert' = 0x1C; 'nonconvert' = 0x1D; 'f7' = 0x76
}

function Resolve-Vk([string]$token) {
  if ($VK.ContainsKey($token)) { return [uint16]$VK[$token] }
  if ($token.Length -eq 1) {
    $c = $token.ToUpperInvariant()[0]
    if (($c -ge [char]'A' -and $c -le [char]'Z') -or ($c -ge [char]'0' -and $c -le [char]'9')) {
      return [uint16][int]$c
    }
  }
  throw "unknown key token '$token'"
}

function From-B64([string]$s) {
  [System.Text.Encoding]::Unicode.GetString([System.Convert]::FromBase64String($s))
}

function Reply([string]$id, [string]$status, $payload) {
  $body = if ($null -eq $payload) { '' }
          elseif ($payload -is [string]) { $payload }
          else { ($payload | ConvertTo-Json -Compress -Depth 6) }
  [Console]::Out.WriteLine("$id $status $body")
  [Console]::Out.Flush()
}

function Assert-Armed([string]$id, [string]$verb) {
  if (-not $Armed) {
    Reply $id 'REFUSED' "$verb needs -Armed; this driver was started unarmed (dry run)"
    return $false
  }
  return $true
}

function Get-ImeState([IntPtr]$hwnd) {
  $hkl = [Rf2Ime]::LayoutOf($hwnd)
  $langid = $hkl -band 0xFFFF
  $fg = [Rf2Ime]::GetForegroundWindow()
  # Read once, report twice: `native` is a predicate over the very number
  # printed beside it, so the flag and the reading can never disagree.
  $conv = [Rf2Ime]::ImeConversion($hwnd)
  [ordered]@{
    hwnd         = $hwnd.ToInt64()
    hkl          = ('0x{0:X8}' -f $hkl)
    langid       = ('0x{0:X4}' -f $langid)
    japanese     = ($langid -eq 0x0411)
    open         = [Rf2Ime]::ImeOpenStatus($hwnd)
    conversion   = $conv
    native       = [Rf2Ime]::IsNativeMode($conv)
    foreground   = $fg.ToInt64()
    isForeground = ($fg -eq $hwnd)
  }
}

Reply 'boot' 'READY' ([ordered]@{ armed = [bool]$Armed; pid = $PID })

while ($null -ne ($line = [Console]::In.ReadLine())) {
  $line = $line.Trim()
  if ($line.Length -eq 0) { continue }
  $parts = $line.Split(' ')
  $id = $parts[0]
  $verb = if ($parts.Length -gt 1) { $parts[1].ToUpperInvariant() } else { '' }
  try {
    switch ($verb) {
      'PING' {
        Reply $id 'OK' ([ordered]@{ armed = [bool]$Armed; pid = $PID; psVersion = $PSVersionTable.PSVersion.ToString() })
      }
      'LAYOUTS' {
        $ls = [Rf2Ime]::InstalledLayouts()
        Reply $id 'OK' ([ordered]@{
          layouts  = @($ls | ForEach-Object { '0x{0:X8}' -f $_ })
          langids  = @($ls | ForEach-Object { '0x{0:X4}' -f ($_ -band 0xFFFF) })
          japanese = @($ls | Where-Object { ($_ -band 0xFFFF) -eq 0x0411 }).Count -gt 0
        })
      }
      'FIND' {
        $needle = From-B64 $parts[2]
        $hits = [Rf2Ime]::FindWindows($needle)
        if ($hits.Count -eq 0) { Reply $id 'ERR' "no visible window titled like '$needle'" }
        else {
          Reply $id 'OK' ([ordered]@{
            hwnd    = $hits[0].ToInt64()
            title   = [Rf2Ime]::TitleOf($hits[0])
            matches = $hits.Count
          })
        }
      }
      'IMESTATE' {
        Reply $id 'OK' (Get-ImeState ([IntPtr][int64]$parts[2]))
      }
      'RESOLVE' {
        # Read-only, and available unarmed on purpose: it is how the dry run
        # proves every key plan is spellable BEFORE anything can be typed.
        # The vocabulary lives here and only here, so a plan validated
        # against this verb cannot drift from the table that sends it.
        $tokens = $parts[2].Split(',') | Where-Object { $_.Length -gt 0 }
        $vks = @($tokens | ForEach-Object { Resolve-Vk $_ })
        # The window handle is OPTIONAL, because which scan code a key carries
        # is a question about the layout of the thread that owns the window
        # and the preflight asks this before any window is found. Given one,
        # the answer is the scan `KEYS` would CALCULATE and pass to `SendKey`
        # — which is how the REHEARSAL can show the 半角/全角 toggle resolving
        # to a non-zero scan without sending a single keystroke. It is not a
        # claim that the scan identifies the key on the wire: `SendKey` sets
        # no `KEYEVENTF_SCANCODE`, so `wVk` does that. See `ScanFor`.
        $h = if ($parts.Length -gt 3) { [IntPtr][int64]$parts[3] } else { [IntPtr]::Zero }
        Reply $id 'OK' ([ordered]@{
          tokens = ($tokens -join ',')
          vks    = @($vks | ForEach-Object { '0x{0:X2}' -f $_ })
          scans  = @($vks | ForEach-Object { '0x{0:X2}' -f [Rf2Ime]::ScanFor($_, $h) })
        })
      }
      'FOREGROUND' {
        if (Assert-Armed $id 'FOREGROUND') {
          $h = [IntPtr][int64]$parts[2]
          [void][Rf2Ime]::SetForegroundWindow($h)
          Start-Sleep -Milliseconds 250
          Reply $id 'OK' (Get-ImeState $h)
        }
      }
      'IMEON' {
        if (Assert-Armed $id 'IMEON') {
          $h = [IntPtr][int64]$parts[2]
          $requested = [Rf2Ime]::RequestJapanese($h)
          Start-Sleep -Milliseconds 400
          $state = Get-ImeState $h
          $state['requestedHkl'] = ('0x{0:X8}' -f $requested)
          Reply $id 'OK' $state
        }
      }
      'IMECONV' {
        # The follow-up to opening the IME by its toggle key. Armed like every
        # other verb that reaches into another window, and — like `IMEON` — a
        # REQUEST: the reply carries the state read AFTER it, so the caller
        # checks `native` rather than believing the send.
        if (Assert-Armed $id 'IMECONV') {
          $h = [IntPtr][int64]$parts[2]
          [void][Rf2Ime]::RequestHiragana($h)
          Start-Sleep -Milliseconds 250
          Reply $id 'OK' (Get-ImeState $h)
        }
      }
      'KEYS' {
        if (Assert-Armed $id 'KEYS') {
          $h = [IntPtr][int64]$parts[2]
          $tokens = $parts[3].Split(',') | Where-Object { $_.Length -gt 0 }
          $delay = [int]$parts[4]
          # Resolve the WHOLE plan before sending anything. A typo in the
          # fifth token must not leave four keystrokes already delivered.
          $vks = @($tokens | ForEach-Object { Resolve-Vk $_ })
          # Resolved against the TARGET window's layout, once, before the
          # first key goes out — see `ScanFor`. Reported below, because the
          # zero that stopped the IME toggle working was invisible. The
          # report says what was CALCULATED and handed to `SendKey`, not what
          # arrived: absent `KEYEVENTF_SCANCODE` the key is named by `wVk`.
          $scans = @($vks | ForEach-Object { [Rf2Ime]::ScanFor($_, $h) })
          $sent = 0
          for ($i = 0; $i -lt $vks.Count; $i++) {
            # Re-verified per key, not once per batch: the operator can
            # alt-tab between two keystrokes, and everything after that
            # would be typed into their window.
            if ([Rf2Ime]::GetForegroundWindow() -ne $h) {
              Reply $id 'ERR' "foreground window changed after $sent key(s); batch aborted"
              $sent = -1
              break
            }
            [void][Rf2Ime]::SendKey($vks[$i], $scans[$i])
            $sent++
            Start-Sleep -Milliseconds $delay
          }
          if ($sent -ge 0) {
            Reply $id 'OK' ([ordered]@{
              sent   = $sent
              tokens = ($tokens -join ',')
              scans  = ((@($scans | ForEach-Object { '0x{0:X2}' -f $_ })) -join ',')
            })
          }
        }
      }
      'QUIT' { Reply $id 'OK' $null; break }
      default { Reply $id 'ERR' "unknown verb '$verb'" }
    }
  } catch {
    Reply $id 'ERR' ($_.Exception.Message -replace "`r?`n", ' ')
  }
}
