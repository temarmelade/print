# Развёртывание сервера

## Схема

```
интернет → Cloudflare (DNS, TLS, DDoS, WAF, Access)
              │  туннель, исходящее соединение
              ▼
           cloudflared ─→ nginx ─→ server:8080 ─→ postgres
```

На сервере **нет открытых входящих портов**. Nginx и Postgres доступны
только внутри docker-сети, `cloudflared` сам подключается к Cloudflare.
Поэтому публичный IPv4 не нужен, а ударить напрямую в origin, минуя
защиту Cloudflare, невозможно.

## Первый запуск

**1. Домен в Cloudflare.**

Заведите бесплатный аккаунт, добавьте `basmaprint.kg`. Cloudflare выдаст
два NS-сервера — пропишите их в панели `cctld.kg` вместо текущих.
Расхождение занимает до суток. Отдельную услугу DNS у регистратора
покупать не нужно.

**2. Сервер.**

Ubuntu 24.04, 2 ядра, 4 ГБ, 40 ГБ. Статический IPv4 не нужен.

```bash
apt update && apt install -y docker.io docker-compose-plugin git
git clone <репозиторий> /opt/print && cd /opt/print
```

**3. Туннель.**

В Cloudflare: Zero Trust → Networks → Tunnels → Create a tunnel →
Cloudflared. На вкладке Docker скопируйте значение после `--token`.

Там же, в Public Hostnames, добавьте маршрут:

| Поле | Значение |
|---|---|
| Subdomain | *(пусто)* |
| Domain | basmaprint.kg |
| Service | `HTTP` → `nginx:80` |

**4. Секреты.**

```bash
cp .env.prod.example .env.prod
openssl rand -base64 48   # ADMIN_JWT_SECRET
openssl rand -base64 24   # DB_PASSWORD
openssl rand -base64 12   # ADMIN_OWNER_PASSWORD, его вводить руками
chmod 600 .env.prod
```

Прод-профиль **не запустится** со значениями из репозитория — это
проверяет `ProdSecretsGuard`.

**5. Админка и запуск.**

```bash
cd admin-web && npm ci && npm run build && cd ..
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
docker compose -f docker-compose.prod.yml logs -f server
```

В логах должно появиться `Проверка секретов пройдена`.

**6. Бэкапы.**

```bash
crontab -e
0 3 * * * /opt/print/deploy/backup-db.sh >> /var/log/kiosk-backup.log 2>&1
```

Копии кладутся на тот же сервер — настройте выгрузку наружу, иначе
отказ диска унесёт и базу, и бэкапы.

## После запуска

**Адрес колбэка Bakai.** В кабинете Bakai Business в поле «получать
уведомления» замените адрес ngrok на:

```
https://basmaprint.kg/api/payments/webhook/bakai
```

**Киоск.** В `C:\PrintKiosk\config\application.yml`:

```yaml
kiosk:
  server:
    base-url: https://basmaprint.kg
    public-base-url: https://basmaprint.kg
    api-key: <новый ключ из админки>
  printer:
    readiness:
      enforce: true
```

Ключ **перевыпустите** — прежний лежал в публичном репозитории.
И проверьте `enforce: true`: его выключали для отладки без принтера.

**Защита админки (по желанию).** Zero Trust → Access → Applications:
добавьте `basmaprint.kg/admin` и правило по вашей почте. Тогда до
приложения человек не дойдёт без авторизации Cloudflare.

## Проверка

```bash
# настоящие адреса клиентов, а не один и тот же
docker compose -f docker-compose.prod.yml logs nginx | tail -20
```

Приёмочный прогон: печать через бота, сканирование с получением файла
на телефон, реальная оплата на минимальную сумму.

## Обновление

```bash
git pull
cd admin-web && npm run build && cd ..            # админка: без простоя
docker compose -f docker-compose.prod.yml up -d --build server   # ~2 мин
```

## Ограничения, о которых стоит помнить

- **Загрузка через Cloudflare Free ограничена 100 МБ.** Рекламные ролики
  тяжелее не пройдут; в Nginx стоит 95 МБ, чтобы обрыв происходил
  предсказуемо.
- **Геоблокировку по стране на старте не включайте.** Неизвестно, с каких
  адресов приходит колбэк Bakai: заблокируете — оплата перестанет
  подтверждаться, и выглядеть это будет как «деньги ушли, документ не
  напечатался».
- **Bot Fight Mode не включайте** — киоск ходит по API без браузера и
  будет принят за бота.
- `cloudflared` — единственная точка входа. Упал туннель, недоступно всё,
  включая оплату. `restart: unless-stopped` это покрывает, но за
  контейнером стоит следить.
