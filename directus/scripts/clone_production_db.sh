#!/usr/bin/env bash

filename=euporos_violina_directus_backup.sql
command="mysqldump --databases euporos_violina_directus > $filename"
ssh uberspace $command
scp uberspace:$filename $filename
mysql -h localhost -u root < $filename
