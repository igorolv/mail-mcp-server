<#
.SYNOPSIS
    Prints the password of one generic credential from Windows Credential Manager.

.DESCRIPTION
    Meant as the passwordCommand of a mail-mcp-server connection (docs/build-config-agent.md,
    "Password commands"). The password is written to standard output as UTF-8 without a byte order
    mark and without a line break; nothing else is written there. Errors go to standard error with
    exit code 1. The script never prompts.

    Store the credential yourself first, for example in Control Panel > Credential Manager >
    Windows Credentials > Add a generic credential, with the same target name.

.PARAMETER Target
    Target name of the generic credential, for example mail-mcp/work.

.EXAMPLE
    powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File Get-StoredPassword.ps1 mail-mcp/work
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $Target
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Target)) {
    [Console]::Error.WriteLine('Usage: Get-StoredPassword.ps1 <target name of a generic credential>')
    exit 1
}

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class MailMcpCredentialReader
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct Credential
    {
        public int Flags;
        public int Type;
        public IntPtr TargetName;
        public IntPtr Comment;
        public long LastWritten;
        public int CredentialBlobSize;
        public IntPtr CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public IntPtr Attributes;
        public IntPtr TargetAlias;
        public IntPtr UserName;
    }

    private const int CredTypeGeneric = 1;

    [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredRead(string target, int type, int flags, out IntPtr credential);

    [DllImport("advapi32.dll")]
    private static extern void CredFree(IntPtr buffer);

    // Returns null when there is no such credential; the blob is UTF-16LE, as Credential Manager stores it.
    public static string Read(string target)
    {
        IntPtr pointer;
        if (!CredRead(target, CredTypeGeneric, 0, out pointer))
        {
            return null;
        }
        try
        {
            Credential credential = (Credential) Marshal.PtrToStructure(pointer, typeof(Credential));
            if (credential.CredentialBlob == IntPtr.Zero || credential.CredentialBlobSize == 0)
            {
                return "";
            }
            return Marshal.PtrToStringUni(credential.CredentialBlob, credential.CredentialBlobSize / 2);
        }
        finally
        {
            CredFree(pointer);
        }
    }
}
'@

$password = [MailMcpCredentialReader]::Read($Target)
if ($null -eq $password) {
    [Console]::Error.WriteLine("No generic credential named '$Target' in Windows Credential Manager")
    exit 1
}
if ($password.Length -eq 0) {
    [Console]::Error.WriteLine("The generic credential '$Target' has an empty password")
    exit 1
}

# Raw UTF-8 bytes: the console code page of Windows PowerShell 5.1 would garble non-ASCII text.
$bytes = (New-Object System.Text.UTF8Encoding $false).GetBytes($password)
$stdout = [Console]::OpenStandardOutput()
$stdout.Write($bytes, 0, $bytes.Length)
$stdout.Flush()
exit 0
