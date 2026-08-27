#!/usr/bin/env bash
# Резервная копия базы. Ставится в cron на хосте:
#     0 3 * * * /opt/print/deploy/backup-db.sh >> /var/log/kiosk-backup.log 2>&1
#
# Держим 14 суточных копий. База маленькая (задания, транзакции,
# телеметрия), поэтому проще хранить полные дампы, чем городить
# инкрементальные.
set -euo pipefail

BACKUP_DIR="$(dirname "$0")/../backups"
KEEP_DAYS=14
STAMP=$(date +%Y%m%d-%H%M%S)

mkdir -p "$BACKUP_DIR"

docker exec kiosk-postgres pg_dump -U "${DB_USER:-kiosk}" -d "${DB_NAME:-kiosk}" \
    | gzip > "$BACKUP_DIR/kiosk-$STAMP.sql.gz"

# Пустой дамп означает, что что-то пошло не так, а cron об этом молчит.
SIZE=$(stat -c%s "$BACKUP_DIR/kiosk-$STAMP.sql.gz")
if [ "$SIZE" -lt 1024 ]; then
    echo "ОШИБКА: дамп подозрительно мал ($SIZE байт)"
    exit 1
fi

find "$BACKUP_DIR" -name 'kiosk-*.sql.gz' -mtime +$KEEP_DAYS -delete
echo "$(date -Is) бэкап готов: kiosk-$STAMP.sql.gz ($SIZE байт)"
