# Makes a jpackage launcher start from folders whose names the system's legacy code page cannot spell
# (for example Chinese folder names on an English Windows).
#
# The JDK's launcher code passes its own location around as legacy-code-page text. A character outside
# that code page becomes "?", the runtime is not found, and the program exits with code 2 without a window.
# Declaring activeCodePage=UTF-8 in the executable's manifest makes the legacy code page of that one
# process UTF-8 (Windows 10 1903 and later; older systems ignore the setting and behave as before).
#
#   powershell -ExecutionPolicy Bypass -File utf8-manifest.ps1 -Exe <launcher.exe> [-Show]
#
# -Show prints the manifest and changes nothing. Running it twice is harmless.
# Keep this file ASCII: Windows PowerShell reads scripts without a BOM in the legacy code page.
param(
    [Parameter(Mandatory = $true)][string]$Exe,
    [switch]$Show
)
$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class LauncherManifest
{
    const int RT_MANIFEST = 24;
    const uint LOAD_LIBRARY_AS_DATAFILE = 0x2;
    const uint LOAD_LIBRARY_AS_IMAGE_RESOURCE = 0x20;

    delegate bool EnumNames(IntPtr module, IntPtr type, IntPtr name, IntPtr param);
    delegate bool EnumLanguages(IntPtr module, IntPtr type, IntPtr name, ushort language, IntPtr param);

    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern IntPtr LoadLibraryEx(string file, IntPtr reserved, uint flags);
    [DllImport("kernel32", SetLastError = true)]
    static extern bool FreeLibrary(IntPtr module);
    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool EnumResourceNames(IntPtr module, IntPtr type, EnumNames callback, IntPtr param);
    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool EnumResourceLanguages(IntPtr module, IntPtr type, IntPtr name, EnumLanguages callback, IntPtr param);
    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern IntPtr FindResourceEx(IntPtr module, IntPtr type, IntPtr name, ushort language);
    [DllImport("kernel32", SetLastError = true)]
    static extern IntPtr LoadResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32", SetLastError = true)]
    static extern IntPtr LockResource(IntPtr data);
    [DllImport("kernel32", SetLastError = true)]
    static extern uint SizeofResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern IntPtr BeginUpdateResource(string file, bool deleteExisting);
    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool UpdateResource(IntPtr update, IntPtr type, IntPtr name, ushort language, byte[] data, uint size);
    [DllImport("kernel32", SetLastError = true)]
    static extern bool EndUpdateResource(IntPtr update, bool discard);

    public sealed class Entry
    {
        public int Name;
        public ushort Language;
        public byte[] Data;
    }

    // Every manifest resource in the file, with the id and language it is stored under.
    public static List<Entry> Read(string file)
    {
        var entries = new List<Entry>();
        IntPtr module = LoadLibraryEx(file, IntPtr.Zero, LOAD_LIBRARY_AS_DATAFILE | LOAD_LIBRARY_AS_IMAGE_RESOURCE);
        if (module == IntPtr.Zero) throw new Win32Exception();
        try
        {
            var type = (IntPtr)RT_MANIFEST;
            var names = new List<IntPtr>();
            EnumResourceNames(module, type, (m, t, name, p) => { names.Add(name); return true; }, IntPtr.Zero);
            foreach (IntPtr name in names)
            {
                // Only integer ids: a manifest stored under a string name is not one Windows applies to a process.
                if (((long)name >> 16) != 0) continue;
                var languages = new List<ushort>();
                EnumResourceLanguages(module, type, name, (m, t, n, language, p) => { languages.Add(language); return true; }, IntPtr.Zero);
                foreach (ushort language in languages)
                {
                    IntPtr resource = FindResourceEx(module, type, name, language);
                    if (resource == IntPtr.Zero) throw new Win32Exception();
                    IntPtr pointer = LockResource(LoadResource(module, resource));
                    var data = new byte[SizeofResource(module, resource)];
                    Marshal.Copy(pointer, data, 0, data.Length);
                    entries.Add(new Entry { Name = (int)name, Language = language, Data = data });
                }
            }
        }
        finally { FreeLibrary(module); }
        return entries;
    }

    public static void Write(string file, int name, ushort language, byte[] data)
    {
        IntPtr update = BeginUpdateResource(file, false);
        if (update == IntPtr.Zero) throw new Win32Exception();
        if (!UpdateResource(update, (IntPtr)RT_MANIFEST, (IntPtr)name, language, data, (uint)data.Length))
        {
            int error = Marshal.GetLastWin32Error();
            EndUpdateResource(update, true);
            throw new Win32Exception(error);
        }
        if (!EndUpdateResource(update, false)) throw new Win32Exception();
    }
}
'@

$Exe = (Resolve-Path -LiteralPath $Exe).Path
$setting = '<activeCodePage xmlns="http://schemas.microsoft.com/SMI/2019/WindowsSettings">UTF-8</activeCodePage>'
$entries = [LauncherManifest]::Read($Exe)

if ($Show) {
    foreach ($entry in $entries) {
        Write-Output ("-- manifest id=" + $entry.Name + " language=" + $entry.Language + " bytes=" + $entry.Data.Length)
        Write-Output ([Text.Encoding]::UTF8.GetString($entry.Data))
    }
    if ($entries.Count -eq 0) { Write-Output 'no manifest' }
    exit 0
}

# The process manifest of an executable is resource id 1.
$entry = $entries | Where-Object { $_.Name -eq 1 } | Select-Object -First 1
if ($null -eq $entry) {
    $xml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' + "`r`n" +
        '<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">' + "`r`n" +
        '</assembly>'
    $language = 1033
} else {
    $xml = [Text.Encoding]::UTF8.GetString($entry.Data).TrimStart([char]0xFEFF)
    $language = $entry.Language
}
if ($xml -match 'activeCodePage') {
    Write-Output 'utf8-manifest: already set'
    exit 0
}

$settingsEnd = [regex]::Match($xml, '</(\w+:)?windowsSettings>')
$assemblyEnd = $xml.LastIndexOf('</assembly>')
if ($settingsEnd.Success) {
    $xml = $xml.Insert($settingsEnd.Index, $setting)
} elseif ($assemblyEnd -ge 0) {
    $block = '<application xmlns="urn:schemas-microsoft-com:asm.v3"><windowsSettings>' + $setting + '</windowsSettings></application>' + "`r`n"
    $xml = $xml.Insert($assemblyEnd, $block)
} else {
    throw 'utf8-manifest: the existing manifest has no </assembly>'
}

# jpackage leaves the launcher read-only
$file = Get-Item -LiteralPath $Exe
$wasReadOnly = $file.IsReadOnly
$file.IsReadOnly = $false
try {
    [LauncherManifest]::Write($Exe, 1, $language, [Text.Encoding]::UTF8.GetBytes($xml))
} finally {
    $file.IsReadOnly = $wasReadOnly
}

$check = [LauncherManifest]::Read($Exe) | Where-Object { $_.Name -eq 1 } | Select-Object -First 1
if ($null -eq $check -or [Text.Encoding]::UTF8.GetString($check.Data) -notmatch 'activeCodePage') {
    throw 'utf8-manifest: the manifest was written but does not read back'
}
Write-Output ('utf8-manifest: set on ' + [IO.Path]::GetFileName($Exe))
