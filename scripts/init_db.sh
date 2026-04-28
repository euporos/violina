#!/usr/bin/env bash
set -e

MYSQL_CMD="mysql -u root"

$MYSQL_CMD <<SQL
CREATE DATABASE IF NOT EXISTS violina_directus;
CREATE USER IF NOT EXISTS 'directus'@'localhost' IDENTIFIED BY 'password';
GRANT ALL PRIVILEGES ON violina_directus.* TO 'directus'@'localhost';
FLUSH PRIVILEGES;
SQL

echo "Database 'violina_directus' and user 'directus' ready."
mkdir -p directus/uploads
