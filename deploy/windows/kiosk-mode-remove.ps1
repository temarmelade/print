<#
    PrintKiosk — отключение режима киоска (обратное kiosk-mode-setup.ps1).

    Запуск: двойной щелчок по kiosk-mode-remove.bat.
    Учётная запись «kiosk» и папка C:\PrintKiosk остаются — удаляются только
    автовход, задание планировщика и оболочка вместо рабочего стола.
#>
param(
    [string]$KioskUser = 'kiosk'
)

$ErrorActionPreference = 'Stop'
$TaskName = 'PrintKiosk'

function Ok([string]$Text)   { Write-Host "   OK  $Text" -ForegroundColor Green }
function Warn([string]$Text) { Write-Host "   !!  $Text" -ForegroundColor Yellow }

function Invoke-Native([scriptblock]$Command) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Command 2>&1 | Out-Null
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
}

$isAdmin = (New-Object Security.Principal.WindowsPrincipal(
    [Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { throw 'Нужны права администратора. Запускайте через kiosk-mode-remove.bat.' }

# Задание планировщика
if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Ok "Задание «$TaskName» удалено"
} else {
    Ok "Задания «$TaskName» не было"
}

# Автовход
$winlogon = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon'
New-ItemProperty -Path $winlogon -Name 'AutoAdminLogon' -Value '0' -PropertyType String -Force | Out-Null
Remove-ItemProperty -Path $winlogon -Name 'DefaultPassword' -ErrorAction SilentlyContinue
Ok 'Автовход выключен, пароль из реестра удалён'

# Оболочка — снова Проводник
$user = Get-LocalUser -Name $KioskUser -ErrorAction SilentlyContinue
if ($user) {
    $sid = $user.SID.Value
    $profileInfo = Get-CimInstance Win32_UserProfile -Filter "SID='$sid'" -ErrorAction SilentlyContinue
    if ($profileInfo) {
        $hiveLoaded = Test-Path "Registry::HKEY_USERS\$sid"
        $hive = if ($hiveLoaded) { "HKU\$sid" } else { 'HKU\PrintKioskSetup' }
        $ok = $true
        if (-not $hiveLoaded) {
            $ok = (Invoke-Native { reg.exe load $hive (Join-Path $profileInfo.LocalPath 'NTUSER.DAT') }) -eq 0
        }
        if ($ok) {
            try {
                $winlogonKey = "$hive\Software\Microsoft\Windows NT\CurrentVersion\Winlogon"
                Invoke-Native { reg.exe delete $winlogonKey /v Shell /f } | Out-Null
                Ok "У «$KioskUser» снова обычный рабочий стол"
            } finally {
                if (-not $hiveLoaded) {
                    [GC]::Collect(); [GC]::WaitForPendingFinalizers()
                    Invoke-Native { reg.exe unload $hive } | Out-Null
                }
            }
        } else {
            Warn "Не удалось открыть реестр «$KioskUser» — выйдите из этой учётной записи и повторите"
        }
    }
}

Write-Host ''
Write-Host 'Готово. Учётная запись «kiosk» осталась; удалить её можно в «Параметры -> Учётные записи».' -ForegroundColor Green
