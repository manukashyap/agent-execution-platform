#!/bin/sh
# One-shot: wait for the frontend, then make sure the `default` namespace exists.
set -eu
i=0
until temporal operator cluster health >/dev/null 2>&1; do
  i=$((i + 1))
  [ "$i" -gt 60 ] && echo "temporal frontend not healthy" && exit 1
  sleep 2
done
temporal operator namespace describe --namespace default >/dev/null 2>&1 \
  || temporal operator namespace create --namespace default --retention 72h
