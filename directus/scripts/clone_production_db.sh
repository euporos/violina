#!/usr/bin/env bash

filename=euporos_festival_directus_backup.sql
command="mysqldump --databases euporos_festival_directus > $filename"
ssh uberspace $command
scp uberspace:$filename $filename
mysql -h localhost -u root < $filename
