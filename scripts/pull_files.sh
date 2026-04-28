#!/usr/bin/env bash
set -e

rsync -avz phylax@netcup-vps-2-arm:/home/phylax/projects/violina_macchiato/uploads/ directus/uploads/
