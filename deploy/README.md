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

## Доступ к API

Открыты только маршруты, которые открывает браузер постороннего:

| Маршрут | Кто вызывает |
|---|---|
| `POST /api/files/upload` | веб-портал загрузки с телефона |
| `GET /api/files/d/{token}` | скачивание скана по QR-ссылке |
| `POST /api/payments/webhook/**` | колбэк платёжного провайдера |
| `POST /api/admin/auth/login` | вход в админку |

Всё остальное под `/api` требует либо ключ киоска (`X-Kiosk-Key`),
либо JWT админки. Незнакомые маршруты под `/api` закрыты по умолчанию
(`denyAll`), поэтому новый эндпоинт нужно явно добавить в
`SecurityConfig` — иначе он вернёт 403.

**Важно при обновлении киосков.** Терминалы без ключа перестанут
работать: проверка PIN, создание заданий и поток оплаты теперь требуют
`X-Kiosk-Key`. Перед выкаткой пропишите на каждом киоске ключ из
раздела «Терминалы» — и перевыпустите его, прежний лежал в публичном
репозитории.

## Что ещё не сделано

- Загрузка через веб-портал открыта без ограничений: любой может
  залить файл. Смягчено лимитом Nginx и десятиминутным TTL, но при
  злоупотреблении понадобится капча.
- Нет повторов запросов на стороне киоска — одна потерянная сетевая
  посылка означает ошибку у человека за терминалом.
