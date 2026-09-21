<#
    PrintKiosk — сторож клиента.

    Запускается при входе учётной записи киоска (задание планировщика
    «PrintKiosk», см. kiosk-mode-setup.ps1) и держит клиент на экране:

      код 42     — перезапуск по команде «Перезапуск» из админки: сразу;
      код 0      — окно закрыли (Alt+F4 и т.п.): тоже перезапускаем, на
                   киоске «штатного выхода» нет;
      иной код   — падение: перезапуск с паузой; если падает слишком часто —
                   долгая пауза, чтобы окно не мигало перед клиентами.

    Режим обслуживания: пока существует файл C:\PrintKiosk\maintenance.flag,
    сторож клиент не запускает и не поднимает после закрытия. Удалили файл —
    через несколько секунд клиент стартует сам, перезагрузка не нужна.

    Логи сторожа: C:\PrintKiosk\logs\launcher.log
#>

$ErrorActionPreference = 'Stop'

# ── Настройки ───────────────────────────────────────────────────────────
$KioskHome       = 'C:\PrintKiosk'
$LogDir          = Join-Path $KioskHome 'logs'
$MaintenanceFlag = Join-Path $KioskHome 'maintenance.flag'

$StartupDelaySec = 5     # дать сети, принтеру и спулеру подняться после входа
$RestartDelaySec = 2     # перезапуск по команде или после закрытия окна
$CrashDelaySec   = 15    # перезапуск после падения
$MaxCrashes      = 5     # столько падений за окно — и уходим в долгую паузу
$CrashWindowMin  = 10
$CooldownMin     = 5

# Параметры клиента:
#   kiosk.ui.fullscreen — на весь экран;
#   kiosk.ui.lock       — Esc не выкидывает из полноэкранного режима.
$ClientArgs = @('-Dkiosk.ui.fullscreen=true', '-Dkiosk.ui.lock=true')
# ─────────────────────────────────────────────────────────────────────────

New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
$LogFile = Join-Path $LogDir 'launcher.log'

function Write-Log([string]$Message) {
    $line = '{0}  {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

# Один сторож на машину: задание стартует и по входу, и из «оболочки»
# учётной записи — второй экземпляр должен тихо выйти.
$createdNew = $false
try {
    $mutex = New-Object System.Threading.Mutex($true, 'Global\PrintKioskLauncher', [ref]$createdNew)
} catch [System.UnauthorizedAccessException] {
    # Мьютекс создал сторож с правами администратора — экземпляру без прав
    # в доступе отказано. Это тоже значит «уже запущен».
    $createdNew = $false
}
if (-not $createdNew) {
    Write-Log 'Сторож уже запущен — второй экземпляр выходит'
    exit 0
}

function Find-Java {
    # java-path.txt пишет kiosk-mode-setup.ps1: полный путь к javaw.exe,
    # найденный под учёткой администратора (у «kiosk» может не быть её PATH).
    $javaPathFile = Join-Path $KioskHome 'java-path.txt'
    $fromFile = if (Test-Path $javaPathFile) { (Get-Content $javaPathFile -TotalCount 1).Trim() }
    $candidates = @(
        (Join-Path $KioskHome 'jre\bin\javaw.exe'),
        $fromFile,
        $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\javaw.exe' })
    ) | Where-Object { $_ }
    foreach ($c in $candidates) {
        if (Test-Path $c) { return $c }
    }
    $cmd = Get-Command javaw.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

# Самый свежий kiosk-client*.jar: новый jar можно положить рядом со старым
# под другим именем (kiosk-client-1.0.1.jar) — возьмётся он.
function Find-Jar {
    Get-ChildItem -Path $KioskHome -Filter 'kiosk-client*.jar' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

function Test-IsAdmin {
    $principal = New-Object Security.Principal.WindowsPrincipal(
        [Security.Principal.WindowsIdentity]::GetCurrent())
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

Write-Log ('Сторож запущен (пользователь {0}, права администратора: {1})' -f $env:USERNAME, (Test-IsAdmin))
if (-not (Test-IsAdmin)) {
    Write-Log 'ВНИМАНИЕ: без прав администратора клиент не сможет очистить очередь печати при старте'
}

Start-Sleep -Seconds $StartupDelaySec

$crashes = New-Object System.Collections.Generic.List[datetime]

while ($true) {
    if (Test-Path $MaintenanceFlag) {
        Write-Log "Режим обслуживания ($MaintenanceFlag) — клиент не запускаем"
        while (Test-Path $MaintenanceFlag) { Start-Sleep -Seconds 5 }
        Write-Log 'Режим обслуживания снят'
    }

    $java = Find-Java
    $jar  = Find-Jar
    if (-not $java -or -not $jar) {
        Write-Log ('Не найден {0} — повтор через минуту' -f $(if (-not $java) { 'javaw.exe' } else { "kiosk-client*.jar в $KioskHome" }))
        Start-Sleep -Seconds 60
        continue
    }

    $argList = $ClientArgs + @('-jar', ('"{0}"' -f $jar.FullName))
    Write-Log ('Запуск: {0} {1}' -f $java, ($argList -join ' '))

    $exitCode = $null
    try {
        # Рабочая папка — C:\PrintKiosk: Spring Boot ищет config\application.yml
        # относительно неё. Запусти из другой папки — киоск стартует без конфига.
        $proc = Start-Process -FilePath $java -ArgumentList $argList `
            -WorkingDirectory $KioskHome -PassThru `
            -RedirectStandardOutput (Join-Path $LogDir 'client-stdout.log') `
            -RedirectStandardError  (Join-Path $LogDir 'client-stderr.log')
        $null = $proc.Handle          # без этого ExitCode иногда приходит пустым
        $proc.WaitForExit()
        $exitCode = $proc.ExitCode
    } catch {
        Write-Log ('Не удалось запустить клиент: {0}' -f $_.Exception.Message)
    }

    if (Test-Path $MaintenanceFlag) {
        Write-Log "Клиент завершился (код $exitCode) в режиме обслуживания — не перезапускаем"
        continue
    }

    if ($exitCode -eq 42) {
        Write-Log 'Код 42 — перезапуск по команде из админки'
        Start-Sleep -Seconds $RestartDelaySec
        continue
    }
    if ($exitCode -eq 0) {
        Write-Log 'Клиент закрыт (код 0) — на киоске перезапускаем'
        Start-Sleep -Seconds $RestartDelaySec
        continue
    }

    # Падение
    $now = Get-Date
    $crashes.Add($now)
    $crashes.RemoveAll([Predicate[datetime]] { param($t) $t -lt $now.AddMinutes(-$CrashWindowMin) }) | Out-Null
    Write-Log ('Клиент упал (код {0}); падений за {1} мин: {2}' -f $exitCode, $CrashWindowMin, $crashes.Count)

    if ($crashes.Count -ge $MaxCrashes) {
        Write-Log "Слишком частые падения — пауза $CooldownMin мин. Смотрите logs\client-stderr.log"
        Start-Sleep -Seconds ($CooldownMin * 60)
        $crashes.Clear()
    } else {
        Start-Sleep -Seconds $CrashDelaySec
    }
}
