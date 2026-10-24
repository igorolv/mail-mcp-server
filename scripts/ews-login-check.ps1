<#
.SYNOPSIS
    Checks EWS sign-in by calling GetFolder(inbox) with Basic, NTLM or Negotiate authentication.

.DESCRIPTION
    Prompts once for the domain password (masked input) and keeps it in memory only, as a
    SecureString handed to .NET NetworkCredential. The password is never printed, written to
    disk or logs, and Read-Host input does not go to the shell history.

    Every login/method pair is one sign-in attempt and counts toward the domain lockout policy.
    After a 401 the script asks before continuing, so a mistyped password costs one attempt.

    The server and login default to the MAIL_EWS_SERVER and MAIL_EWS_LOGIN environment
    variables, so real host names and logins do not have to live in the repository.

.EXAMPLE
    $env:MAIL_EWS_SERVER = 'webmail.example.com'; $env:MAIL_EWS_LOGIN = 'user'
    .\scripts\ews-login-check.ps1

.EXAMPLE
    .\scripts\ews-login-check.ps1 -Server mail.example.com -Logins 'EXAMPLE\user', 'user@example.com' -Methods NTLM

.EXAMPLE
    .\scripts\ews-login-check.ps1 -Server webmail.example.com -Logins user -Methods Basic
#>
[CmdletBinding()]
param(
    [string] $Server = $env:MAIL_EWS_SERVER,
    # A bare user name leaves the domain empty, so the server applies its own default domain.
    [string[]] $Logins = @($env:MAIL_EWS_LOGIN),
    [ValidateSet('Basic', 'NTLM', 'Negotiate')]
    [string[]] $Methods = @('NTLM'),
    [int] $TimeoutSec = 30
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Logins = @($Logins | Where-Object { $_ })
if (-not $Server -or $Logins.Count -eq 0) {
    throw 'Set -Server and -Logins, or the MAIL_EWS_SERVER and MAIL_EWS_LOGIN environment variables.'
}

$uri = [Uri] "https://$Server/EWS/Exchange.asmx"
$typesNs = 'http://schemas.microsoft.com/exchange/services/2006/types'
$messagesNs = 'http://schemas.microsoft.com/exchange/services/2006/messages'

$body = [Text.Encoding]::UTF8.GetBytes(@"
<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="$typesNs"
               xmlns:m="$messagesNs">
  <soap:Header>
    <t:RequestServerVersion Version="Exchange2013_SP1"/>
  </soap:Header>
  <soap:Body>
    <m:GetFolder>
      <m:FolderShape><t:BaseShape>Default</t:BaseShape></m:FolderShape>
      <m:FolderIds><t:DistinguishedFolderId Id="inbox"/></m:FolderIds>
    </m:GetFolder>
  </soap:Body>
</soap:Envelope>
"@)

# 'DOMAIN\user' is split into user and domain; a UPN (user@domain) goes in as the user name.
function New-NetworkCredential([string] $login, [Security.SecureString] $password) {
    if ($login.Contains('\')) {
        $domain, $user = $login.Split('\', 2)
        return New-Object Net.NetworkCredential($user, $password, $domain)
    }
    return New-Object Net.NetworkCredential($login, $password)
}

function New-Result([string] $method, [string] $login) {
    [pscustomobject] [ordered] @{
        Method = $method
        Login  = $login
        Http   = '-'
        Result = '-'
        Folder = ''
        Total  = ''
        Unread = ''
        Detail = ''
    }
}

# A CredentialCache keyed by one scheme makes .NET answer only that challenge, so 'NTLM'
# stays NTLM even though the server also offers Negotiate.
function Invoke-EwsGetFolder([string] $method, [string] $login, [Net.NetworkCredential] $credential) {
    $result = New-Result $method $login

    $cache = New-Object Net.CredentialCache
    $cache.Add($uri, $method, $credential)

    $request = [Net.HttpWebRequest]::Create($uri)
    $request.Method = 'POST'
    $request.ContentType = 'text/xml; charset=utf-8'
    $request.UserAgent = 'mail-mcp-server-ews-login-check'
    $request.Credentials = $cache
    $request.AllowAutoRedirect = $false
    $request.Timeout = $TimeoutSec * 1000

    $stream = $request.GetRequestStream()
    try { $stream.Write($body, 0, $body.Length) } finally { $stream.Dispose() }

    try {
        $response = $request.GetResponse()
    } catch {
        $ex = $_.Exception
        if ($ex -isnot [Net.WebException] -and $ex.InnerException -is [Net.WebException]) {
            $ex = $ex.InnerException
        }
        if ($ex -isnot [Net.WebException] -or $null -eq $ex.Response) { throw }
        $response = $ex.Response
    }

    try {
        $result.Http = '{0} {1}' -f [int] $response.StatusCode, $response.StatusDescription
        $reader = New-Object IO.StreamReader($response.GetResponseStream(), [Text.Encoding]::UTF8)
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }

        if ([int] $response.StatusCode -eq 401) {
            $result.Result = 'Unauthorized'
            $result.Detail = 'WWW-Authenticate: ' + (@($response.Headers.GetValues('WWW-Authenticate')) -join ', ')
            return $result
        }

        $doc = New-Object Xml.XmlDocument
        try { $doc.LoadXml($text) } catch {
            $result.Result = 'Non-XML response'
            $result.Detail = $text.Substring(0, [Math]::Min(200, $text.Length))
            return $result
        }

        $fault = $doc.GetElementsByTagName('faultstring')
        if ($fault.Count -gt 0) {
            $result.Result = 'SOAP fault'
            $result.Detail = $fault[0].InnerText
            return $result
        }

        $message = $doc.GetElementsByTagName('GetFolderResponseMessage', $messagesNs)
        if ($message.Count -eq 0) {
            $result.Result = 'Unexpected response'
            return $result
        }
        $result.Result = $message[0].GetAttribute('ResponseClass')
        $result.Detail = $message[0].GetElementsByTagName('ResponseCode', $messagesNs)[0].InnerText
        if ($result.Result -eq 'Success') {
            $result.Folder = $doc.GetElementsByTagName('DisplayName', $typesNs)[0].InnerText
            $result.Total = $doc.GetElementsByTagName('TotalCount', $typesNs)[0].InnerText
            $result.Unread = $doc.GetElementsByTagName('UnreadCount', $typesNs)[0].InnerText
        }
        return $result
    } finally {
        $response.Dispose()
    }
}

Write-Host "EWS endpoint: $uri"
Write-Host ("Planned attempts: {0} ({1} x {2}); each one counts toward the lockout policy." -f `
        ($Logins.Count * $Methods.Count), ($Logins -join ', '), ($Methods -join ', '))

$password = Read-Host -AsSecureString -Prompt 'Domain password'
if ($password.Length -eq 0) {
    Write-Host 'Empty password, nothing to check.'
    return
}

$results = New-Object Collections.Generic.List[object]
try {
    :attempts foreach ($login in $Logins) {
        foreach ($method in $Methods) {
            Write-Host "-> $method as $login"
            try {
                $result = Invoke-EwsGetFolder $method $login (New-NetworkCredential $login $password)
            } catch {
                $result = New-Result $method $login
                $result.Result = 'Error'
                $result.Detail = $_.Exception.ToString()
            }
            $results.Add($result)
            Write-Host ("   {0} | {1} | {2}" -f $result.Http, $result.Result, $result.Detail)

            $isLast = ($login -eq $Logins[-1]) -and ($method -eq $Methods[-1])
            if ($result.Result -eq 'Unauthorized' -and -not $isLast) {
                $answer = Read-Host 'Got 401 (wrong password or login format). Continue with remaining attempts? [y/N]'
                if ($answer -notmatch '^(y|yes)$') { break attempts }
            }
        }
    }
} finally {
    $password.Dispose()
}

$results | Format-Table -AutoSize -Wrap
