-- На свежей БД таблица уже пустая, default нужен только формально.
ALTER TABLE files ADD COLUMN page_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE files ALTER COLUMN page_count DROP DEFAULT;
ALTER TABLE files ADD CONSTRAINT chk_page_count_positive CHECK (page_count >= 0);