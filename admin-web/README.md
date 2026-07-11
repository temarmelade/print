# PrintKiosk · Admin (пульт управления)

React SPA (Vite + TypeScript) для управления сетью киосков. Живёт отдельно от
Maven-модулей, ваш серверный билд не трогает.

## Запуск
```bash
npm install
npm run dev        # http://localhost:5174
```
На старте вход работает на заглушке (`VITE_USE_MOCK_AUTH=true`).
Демо-аккаунты для проверки ролей:
- `owner` / `owner123`   — видит все модули
- `tech` / `tech123`     — Дашборд, Терминалы, Инциденты (без выручки/финансов)
- `support` / `support123` — Дашборд, Терминалы, Транзакции

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
