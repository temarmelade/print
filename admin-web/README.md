# PrintKiosk · Admin (пульт управления)

React SPA (Vite + TypeScript) для управления сетью киосков. Живёт отдельно от
Maven-модулей, ваш серверный билд не трогает.

## Запуск
```bash
npm install
npm run dev        # http://localhost:5174
```
Вход идёт через реальный сервер (`VITE_USE_MOCK_AUTH=false`) — запусти рядом
`kiosk-server`. Первый владелец создаётся автоматически при пустой БД:
- логин `owner` / пароль `owner12345` (задаётся `ADMIN_OWNER_*`, смени на проде).

Для работы над интерфейсом без сервера поставь `VITE_USE_MOCK_AUTH=true` —
тогда доступны демо-аккаунты `owner/owner123`, `tech/tech123`, `support/support123`.

## Что сделано (Фаза 1, фундамент)
- Вход, восстановление сессии по токену, выход.
- Роли Owner / Technician / Support: единая карта доступа `src/lib/permissions.ts`.
- Роут-гарды (авторизация + проверка роли) и гейтинг блоков внутри страниц (`RoleGate`).
- Оболочка: приборный рейл с меню по роли + топбар со статусом сети и ролью.
- Заглушки всех 7 модулей с указанием фазы.

## Подключение к бэкенду
Ожидаемый контракт (заглушку выключить `VITE_USE_MOCK_AUTH=false`):
- `POST /api/admin/auth/login` `{ username, password }` → `{ token, user: { id, name, username, role } }`
- `GET  /api/admin/auth/me` (Bearer) → `{ id, name, username, role }`
- `role` ∈ `OWNER | TECHNICIAN | SUPPORT`
- 401 → SPA чистит сессию и уводит на `/login`

Токен сейчас в `localStorage` (просто для старта). Для прода рекомендуется
перевести на httpOnly-cookie — тогда правится только `apiClient.ts`.
