<#
    PrintKiosk — перевод мини-ПК в режим киоска.

    Запускать ОДИН РАЗ, от администратора: двойной щелчок по
    kiosk-mode-setup.bat (он сам попросит права администратора).

    Что делает:
      1. Учётная запись «kiosk» — отдельная, с автовходом. Ваша обычная
         учётная запись остаётся как есть, с рабочим столом, для обслуживания.
      2. Вместо рабочего стола у «kiosk» запускается только клиент киоска.
         Проводник (панель задач, «Пуск», жесты с краёв экрана, уведомления)
         не запускается вовсе — выйти из киоска касаниями нельзя.
      3. Задание планировщика «PrintKiosk» — при входе «kiosk» запускает
         сторож (kiosk-launcher.ps1) с правами администратора.
      4. Экран не гаснет, ПК не засыпает, Windows Update не перезагружает
         компьютер сам, пока киоск работает.

    Отменить всё: kiosk-mode-remove.bat.

    Встроенный «Режим киоска» Windows (Assigned Access) не подходит: в Pro
    он запускает только приложения из Store и Edge, а обычные программы —
    лишь в Enterprise/Education/IoT (Shell Launcher). Здесь то же самое
    сделано средствами, которые есть в любой редакции.
#>
param(
    [string]$KioskUser = 'kiosk',
    [string]$KioskHome = 'C:\PrintKiosk'
)

$ErrorActionPreference = 'Stop'
$TaskName      = 'PrintKiosk'
$LauncherName  = 'kiosk-launcher.ps1'
$AdminsSid     = 'S-1-5-32-544'      # «Администраторы» — по SID, имя группы зависит от языка Windows

function Step([string]$Text) { Write-Host ''; Write-Host "== $Text" -ForegroundColor Cyan }
function Ok([string]$Text)   { Write-Host "   OK  $Text" -ForegroundColor Green }
function Warn([string]$Text) { Write-Host "   !!  $Text" -ForegroundColor Yellow }

# Внешняя программа без аварийного выхода: в Windows PowerShell 5.1 любой
# вывод в stderr при $ErrorActionPreference = 'Stop' стал бы исключением.
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

function Set-RegValue([string]$Path, [string]$Name, $Value, [string]$Type = 'String') {
    if (-not (Test-Path $Path)) { New-Item -Path $Path -Force | Out-Null }
    New-ItemProperty -Path $Path -Name $Name -Value $Value -PropertyType $Type -Force | Out-Null
}

# ── 0. Проверки ─────────────────────────────────────────────────────────
Step 'Проверки'

$isAdmin = (New-Object Security.Principal.WindowsPrincipal(
    [Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { throw 'Нужны права администратора. Запускайте через kiosk-mode-setup.bat.' }

$os = Get-CimInstance Win32_OperatingSystem
Ok ("{0} (сборка {1})" -f $os.Caption, $os.BuildNumber)

if (-not (Test-Path $KioskHome)) { throw "Нет папки $KioskHome — сначала разложите туда клиент." }

# Скрипты можно запускать хоть с флешки — сторож копируем на место.
$launcherTarget = Join-Path $KioskHome $LauncherName
$launcherSource = Join-Path $PSScriptRoot $LauncherName
if ((Test-Path $launcherSource) -and ($launcherSource -ne $launcherTarget)) {
    Copy-Item $launcherSource $launcherTarget -Force
    Ok "Сторож скопирован в $launcherTarget"
}
if (-not (Test-Path $launcherTarget)) { throw "Нет $launcherTarget — положите его рядом с этим скриптом." }

$jar = Get-ChildItem -Path $KioskHome -Filter 'kiosk-client*.jar' -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($jar) { Ok "Клиент: $($jar.Name)" } else { Warn "В $KioskHome нет kiosk-client*.jar — положите до перезагрузки" }

if (Test-Path (Join-Path $KioskHome 'config\application.yml')) { Ok 'Конфиг: config\application.yml' }
else { Warn 'Нет config\application.yml — без него киоск не подключится к серверу' }

# Java ищем сейчас, под вашей учётной записью, и запоминаем полный путь:
# у «kiosk» может не быть вашего JAVA_HOME/PATH из пользовательских настроек.
$javaPathFile = Join-Path $KioskHome 'java-path.txt'
$bundled = Join-Path $KioskHome 'jre\bin\javaw.exe'
if (Test-Path $bundled) {
    Ok "Java: $bundled"
    Remove-Item $javaPathFile -ErrorAction SilentlyContinue
} else {
    $javaw = $null
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javaw.exe'))) {
        $javaw = Join-Path $env:JAVA_HOME 'bin\javaw.exe'
    } else {
        $cmd = Get-Command javaw.exe -ErrorAction SilentlyContinue
        if ($cmd) { $javaw = $cmd.Source }
    }
    if ($javaw) {
        Set-Content -Path $javaPathFile -Value $javaw -Encoding UTF8
        Ok "Java: $javaw (путь записан в java-path.txt)"
    } else {
        Warn 'javaw.exe не найден. Установите Java 21 или положите JRE в C:\PrintKiosk\jre'
    }
}

# ── 1. Учётная запись ───────────────────────────────────────────────────
Step "Учётная запись «$KioskUser»"

$pwd1 = Read-Host "Пароль для «$KioskUser» (нужен для автовхода; буквы и цифры)" -AsSecureString
$pwd2 = Read-Host 'Повторите пароль' -AsSecureString
$plain1 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($pwd1))
$plain2 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($pwd2))
if ($plain1 -ne $plain2) { throw 'Пароли не совпадают.' }
if ($plain1.Length -lt 8) { throw 'Пароль короче 8 символов.' }

$user = Get-LocalUser -Name $KioskUser -ErrorAction SilentlyContinue
if ($user) {
    Set-LocalUser -Name $KioskUser -Password $pwd1 -PasswordNeverExpires $true
    Ok 'Уже была — пароль обновлён'
} else {
    New-LocalUser -Name $KioskUser -Password $pwd1 -FullName 'PrintKiosk' `
        -Description 'Учётная запись терминала PrintKiosk' `
        -PasswordNeverExpires -AccountNeverExpires | Out-Null
    Ok 'Создана'
}

# Администратор: клиент при старте чистит очередь печати (останавливает
# службу spooler), а это право есть только у администраторов.
try {
    Add-LocalGroupMember -SID $AdminsSid -Member $KioskUser
    Ok 'Добавлена в «Администраторы»'
} catch {
    if ($_.FullyQualifiedErrorId -like '*MemberExists*') { Ok 'Уже в «Администраторах»' } else { throw }
}

$sid = (Get-LocalUser -Name $KioskUser).SID.Value
$account = "$env:COMPUTERNAME\$KioskUser"

# ── 2. Задание планировщика ─────────────────────────────────────────────
Step "Задание планировщика «$TaskName»"

$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$launcherTarget`"" `
    -WorkingDirectory $KioskHome
$trigger   = New-ScheduledTaskTrigger -AtLogOn -User $account
$principal = New-ScheduledTaskPrincipal -UserId $account -LogonType Interactive -RunLevel Highest
# ExecutionTimeLimit = 0: по умолчанию планировщик убивает задание через
# 3 дня — киоск молча погас бы на четвёртые сутки.
$settings  = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Force `
    -Description 'PrintKiosk: сторож клиента киоска (kiosk-launcher.ps1)' | Out-Null
Ok 'Запускает сторож при входе, с правами администратора, без ограничения по времени'

# ── 3. Автовход ─────────────────────────────────────────────────────────
Step 'Автовход'

$winlogon = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon'
Set-RegValue $winlogon 'AutoAdminLogon'    '1'
Set-RegValue $winlogon 'DefaultUserName'   $KioskUser
Set-RegValue $winlogon 'DefaultDomainName' $env:COMPUTERNAME
Set-RegValue $winlogon 'DefaultPassword'   $plain1
Remove-ItemProperty -Path $winlogon -Name 'AutoLogonCount' -ErrorAction SilentlyContinue
# Windows 11: режим «вход только через Windows Hello» мешает автовходу.
Set-RegValue 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\PasswordLess\Device' `
    'DevicePasswordLessBuildVersion' 0 'DWord'
Ok "После включения ПК входит под «$KioskUser» сам"
Warn 'Пароль хранится в реестре (читают только администраторы). Если нужно зашифрованно — Sysinternals Autologon'

# ── 4. Оболочка вместо рабочего стола ───────────────────────────────────
Step 'Оболочка вместо рабочего стола'

# Профиль «kiosk» создаётся при первом входе. Чтобы не входить руками,
# один раз запускаем от её имени пустую команду — Windows создаст профиль.
$profileInfo = Get-CimInstance Win32_UserProfile -Filter "SID='$sid'" -ErrorAction SilentlyContinue
if (-not $profileInfo) {
    $cred = New-Object System.Management.Automation.PSCredential($account, $pwd1)
    Start-Process -FilePath "$env:SystemRoot\System32\cmd.exe" -ArgumentList '/c', 'exit' `
        -Credential $cred -LoadUserProfile -WorkingDirectory "$env:SystemRoot\System32" `
        -WindowStyle Hidden -Wait
    Start-Sleep -Seconds 2
    $profileInfo = Get-CimInstance Win32_UserProfile -Filter "SID='$sid'"
    Ok "Профиль создан: $($profileInfo.LocalPath)"
} else {
    Ok "Профиль есть: $($profileInfo.LocalPath)"
}

# Реестр пользователя: если он сейчас вошёл — ветка уже загружена,
# иначе подгружаем NTUSER.DAT во временную ветку.
$hiveLoaded = Test-Path "Registry::HKEY_USERS\$sid"
$hive = if ($hiveLoaded) { "HKU\$sid" } else { 'HKU\PrintKioskSetup' }
if (-not $hiveLoaded) {
    $ntuser = Join-Path $profileInfo.LocalPath 'NTUSER.DAT'
    $loaded = $false
    for ($i = 0; $i -lt 15 -and -not $loaded; $i++) {
        # Сразу после создания профиля файл ещё может быть занят — повторяем.
        if ((Invoke-Native { reg.exe load $hive $ntuser }) -eq 0) { $loaded = $true } else { Start-Sleep -Seconds 1 }
    }
    if (-not $loaded) { throw "Не удалось открыть реестр «$KioskUser» ($ntuser). Перезагрузите ПК и запустите настройку снова." }
}

try {
    # Оболочка — вместо Проводника. Её задача только подстраховать запуск
    # сторожа; основной путь — задание планировщика по входу.
    # Без кавычек внутри: Windows PowerShell 5.1 передаёт вложенные кавычки
    # внешним программам с ошибкой. -Command и так берёт остаток строки.
    $shell = "powershell.exe -NoProfile -WindowStyle Hidden -Command Start-ScheduledTask -TaskName $TaskName"
    $winlogonKey = "$hive\Software\Microsoft\Windows NT\CurrentVersion\Winlogon"
    if ((Invoke-Native { reg.exe add $winlogonKey /v Shell /t REG_SZ /d $shell /f }) -ne 0) {
        throw 'Не удалось записать оболочку'
    }
    # Экран «Давайте завершим настройку устройства» после обновлений Windows.
    $engagementKey = "$hive\Software\Microsoft\Windows\CurrentVersion\UserProfileEngagement"
    Invoke-Native { reg.exe add $engagementKey /v ScoobeSystemSettingEnabled /t REG_DWORD /d 0 /f } | Out-Null
    Ok "У «$KioskUser» вместо рабочего стола запускается только киоск"
} finally {
    if (-not $hiveLoaded) {
        [GC]::Collect(); [GC]::WaitForPendingFinalizers()
        Invoke-Native { reg.exe unload $hive } | Out-Null
    }
}

# ── 5. Питание, обновления, мелочи ──────────────────────────────────────
Step 'Питание и обновления'

Invoke-Native { powercfg.exe /change standby-timeout-ac 0 }   | Out-Null
Invoke-Native { powercfg.exe /change monitor-timeout-ac 0 }   | Out-Null
Invoke-Native { powercfg.exe /change hibernate-timeout-ac 0 } | Out-Null
Invoke-Native { powercfg.exe /change disk-timeout-ac 0 }      | Out-Null
# Заодно выключает «быстрый запуск» — с ним «выключение» на деле гибернация,
# и киоск после включения поднимается в старом состоянии.
Invoke-Native { powercfg.exe /hibernate off } | Out-Null
Ok 'Экран не гаснет, ПК не засыпает'

Set-RegValue 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU' `
    'NoAutoRebootWithLoggedOnUsers' 1 'DWord'
Ok 'Windows Update не перезагружает ПК сам, пока киоск работает'

Set-RegValue 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System' `
    'EnableFirstLogonAnimation' 0 'DWord'
Ok 'Без анимации «Привет» при входе'

# ── Итог ────────────────────────────────────────────────────────────────
Write-Host ''
Write-Host 'Готово. Перезагрузите ПК: он сам войдёт под «kiosk» и откроет киоск.' -ForegroundColor Green
Write-Host ''
Write-Host 'Обслуживание (нужна клавиатура):'
Write-Host '  Ctrl+Alt+Del -> Диспетчер задач -> Файл -> Запустить новую задачу:'
Write-Host '    explorer.exe                                   — рабочий стол поверх киоска'
Write-Host "    cmd /c echo.>$KioskHome\maintenance.flag       — киоск не перезапускается"
Write-Host "  Удалите $KioskHome\maintenance.flag — киоск запустится сам."
Write-Host '  Войти под своей учётной записью: удерживайте Shift при выходе из системы.'
Write-Host ''
Write-Host "Логи сторожа: $KioskHome\logs\launcher.log"
