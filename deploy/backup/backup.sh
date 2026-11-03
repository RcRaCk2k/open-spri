#!/bin/sh
# Backup of an open-spri installation (docs/betrieb.md, "Backup und Restore"). Run in the project directory,
# e.g. daily from cron:  15 1 * * *  cd /opt/open-spri && deploy/backup/backup.sh /var/backups/open-spri
#
# Writes to TARGET/<timestamp>/:
#   openspri.sql.gz  consistent dump of the database (single transaction, no lock on InnoDB tables)
#   files.tar.gz     .env (master key!), secrets/ and config/; contains secrets, keep the backup protected
# and deletes backups older than KEEP_DAYS (default 30). The dump contains end customer data (NF-10): the
# rotation should match OPENSPRI_RETENTION_PERSONAL_DATA.
set -eu
TARGET="${1:?usage: deploy/backup/backup.sh <target directory>}"
KEEP_DAYS="${KEEP_DAYS:-30}"
dir="$TARGET/$(date +%Y%m%d-%H%M%S)"
umask 077
mkdir -p "$dir"

# shellcheck disable=SC2016
docker compose exec -T db sh -c 'exec mariadb-dump -uroot -p"$MARIADB_ROOT_PASSWORD" --single-transaction --routines --triggers --databases openspri' \
  | gzip > "$dir/openspri.sql.gz"
tar -czf "$dir/files.tar.gz" .env secrets config 2>/dev/null || tar -czf "$dir/files.tar.gz" .env
gzip -t "$dir/openspri.sql.gz"
find "$TARGET" -mindepth 1 -maxdepth 1 -type d -mtime +"$KEEP_DAYS" -exec rm -rf {} +
echo "Backup: $dir ($(du -sh "$dir" | cut -f1))"
