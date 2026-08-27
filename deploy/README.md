# Развёртывание сервера

## Что получится

```
интернет → Nginx (443, TLS) ─┬→ /            админка (статика)
                             └→ /api/        kiosk-server:8080
                                             └→ postgres (без порта наружу)
```

## Первый запуск

**1. Домен.** Направьте A-запись на IP сервера. Без этого Let's Encrypt
не выдаст сертификат.

**2. Секреты.**

```bash
cp .env.prod.example .env.prod
openssl rand -base64 48   # для ADMIN_JWT_SECRET
openssl rand -base64 24   # для DB_PASSWORD и пароля владельца
```

Заполните `.env.prod`. Прод-профиль **не запустится** со значениями из
репозитория — `ProdSecretsGuard` проверяет это на старте.

**3. Домен в конфиге Nginx.** Замените `kioskprint.kg` в
`deploy/nginx/kiosk.conf` на свой (три вхождения).

**4. Сертификат.** Сначала поднимите только Nginx на 80-м порту:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d nginx
docker compose -f docker-compose.prod.yml run --rm certbot certonly \
    --webroot -w /var/www/certbot -d kioskprint.kg --agree-tos -m ваша@почта
```

**5. Админка.**

```bash
cd admin-web && npm ci && npm run build && cd ..
```

**6. Запуск.**

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
docker compose -f docker-compose.prod.yml logs -f server
```

В логах должно появиться `Проверка секретов пройдена`.

**7. Бэкапы.**

```bash
crontab -e
0 3 * * * /путь/к/deploy/backup-db.sh >> /var/log/kiosk-backup.log 2>&1
```

## Обновление

```bash
git pull
cd admin-web && npm run build && cd ..      # админка: без простоя
docker compose -f docker-compose.prod.yml up -d --build server   # сервер: ~2 мин
```

## Подключение киосков

В `application-local.yml` каждого киоска укажите адрес сервера и ключ,
выданный в админке (Терминалы → ключ доступа):

```yaml
kiosk:
  server:
    base-url: https://kioskprint.kg
    kiosk-id: KIOSK-01
    api-key: <ключ из админки>
```

## Что ещё НЕ закрыто

Эти пункты остались от аудита и требуют работы в коде:

- `/api/jobs/**`, `/api/files/**`, `/api/payments/**` открыты без
  авторизации. Nginx ограничивает частоту, но не доступ.
- Скачивание по четырёхзначному PIN защищено только лимитом Nginx.
  Нужен длинный одноразовый токен.

До того как это сделано, админку и API разумно держать за
белым списком адресов или VPN.
