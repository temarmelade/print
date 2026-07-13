-- Пользователи админ-панели (пульта управления). Роли: владелец, техник, поддержка.
CREATE TABLE admin_users (
    id            UUID          PRIMARY KEY,
    name          VARCHAR(120)  NOT NULL,
    username      VARCHAR(64)   NOT NULL UNIQUE,
    password_hash VARCHAR(100)  NOT NULL,
    role          VARCHAR(20)   NOT NULL,
    enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT chk_admin_role CHECK (role IN ('OWNER', 'TECHNICIAN', 'SUPPORT'))
);

CREATE INDEX idx_admin_users_username ON admin_users (username);
