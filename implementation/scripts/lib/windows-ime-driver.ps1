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
    RESOLVE <tokens>              -> {tokens, vks}
    FOREGROUND <hwnd>             -> {isForeground}                    [armed]
    IMEON <hwnd>                  -> {requested, hkl, langid, ...}     [armed]
    IMECONV <hwnd>                -> {conversion, native, ...}         [armed]
    KEYS <hwnd> <tokens> <delay>  -> {sent, tokens}                    [armed]
    QUIT                          -> OK

  ## What it does NOT do

  It does not install a Japanese IME, and it will not pretend one is there:
  `LAYOUTS` is the preflight, and the caller refuses to arm when no
  `0x0411` layout is installed. It does not choose candidates by reading the
  candidate window — the key plan does that, by sending Space and Enter the
  way a person would. And `IMEON` is a REQUEST, not a guarantee: the modern
  Microsoft IME is a TSF text service, and the IMM32 messages below reach it
  through a compatibility layer that a given browser build may not honour.
  That is why `IMESTATE` exists as a separate verb — the caller ASKS, then
  VERIFIES, and stops for the operator when the answer is no.

  ## The warning above came true, and what answers it

  On 2026-08-12 the first armed run got the whole way in — foreground
  seized on all three engines, romaji and ESC both delivered — and still
  decided nothing: `IMESTATE` read `langid 0x0411`, `japanese: true` and
  **`open: 0`** on Chromium, Firefox and WebKit alike. The TSF IME had
  simply ignored `IMC_SETOPENSTATUS`, exactly as this header said it might,
  so seven romaji letters landed as plain ASCII and all 24 checks returned
  INCONCLUSIVE.

  The door that was open all along is the one the keystrokes came through.
  `SendInput` reached that IME when IMM32 did not, so the caller opens it by
  sending the IME'S OWN TOGGLE — `kanji` (半角/全角, VK 0x19), already in the
  vocabulary below — through `KEYS`, and then re-reads `IMESTATE`. `IMECONV`
  is the same shape one level along: the toggle says nothing about which
  reading mode the IME came back in, so Hiragana is re-asserted and the
  `native` flag re-read. Neither verb is trusted either. The caller ASKS,
  VERIFIES, and refuses the engine when the answer is still no.
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
  /// shim that need not honour them. The caller verifies afterwards.
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

  /// One key down+up through the OS input stack. The virtual key carries a
  /// real scan code (`MapVirtualKey`) because an IME reads both, and a
  /// keystroke with a zero scan code is one an IME may decline to compose
  /// from.
  public static uint SendKey(ushort vk)
  {
    ushort scan = (ushort)MapVirtualKeyW(vk, 0 /* MAPVK_VK_TO_VSC */);
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
  # caller's engage preflight sends, through `KEYS`, when `IMEON`'s IMM32
  # request leaves a TSF IME closed. The other three are here so a key plan
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
        Reply $id 'OK' ([ordered]@{
          tokens = ($tokens -join ',')
          vks    = @($vks | ForEach-Object { '0x{0:X2}' -f $_ })
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
            [void][Rf2Ime]::SendKey($vks[$i])
            $sent++
            Start-Sleep -Milliseconds $delay
          }
          if ($sent -ge 0) { Reply $id 'OK' ([ordered]@{ sent = $sent; tokens = ($tokens -join ',') }) }
        }
      }
      'QUIT' { Reply $id 'OK' $null; break }
      default { Reply $id 'ERR' "unknown verb '$verb'" }
    }
  } catch {
    Reply $id 'ERR' ($_.Exception.Message -replace "`r?`n", ' ')
  }
}
