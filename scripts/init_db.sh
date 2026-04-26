#!/usr/bin/env bash
set -e

MYSQL_CMD="mysql -u root"

$MYSQL_CMD <<SQL
CREATE DATABASE IF NOT EXISTS festival_directus;
CREATE USER IF NOT EXISTS 'directus'@'localhost' IDENTIFIED BY 'password';
GRANT ALL PRIVILEGES ON festival_directus.* TO 'directus'@'localhost';
FLUSH PRIVILEGES;
SQL

echo "Database 'festival_directus' and user 'directus' ready."
mkdir -p directus/uploads
