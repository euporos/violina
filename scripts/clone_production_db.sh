#!/usr/bin/env bash
set -e

filename="festival_directus_backup.sql"
ssh root@netcup-vps-2-arm "mysqldump --databases festival_directus --skip-comments > $filename"
scp "root@netcup-vps-2-arm:$filename" "$filename"
sed -i '1d' "$filename"
sed -i -E 's/DEFINER=`[^`]+`@`[^`]+`//g' "$filename"
mysql -u root < "$filename"
echo "Production database cloned to local."
