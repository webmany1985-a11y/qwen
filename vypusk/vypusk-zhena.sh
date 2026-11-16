#!/bin/sh
# Выпуск JARVIS Auto жены для кнопки «Обновить приложение» (16.11.2026, по образцу tools/vypusk-domashka.sh android-team):
# сборка — в Cloudflare KV «jarvis-zhena-vypusk» частями по 24 МБ + описание jarvis-zhena.json; отдаёт воркер
# jarvis-zhena-vypusk (vypusk/zhena-worker). Хранится одна сборка — прежние части удаляются.
# Использование (из корня qwen): sh vypusk/vypusk-zhena.sh <APK> <номер сборки = versionCode>
# Ключ Cloudflare в облачной сессии подставляет прокси; на компьютере — свой CLOUDFLARE_API_TOKEN.
set -e
APK=$1; N=$2
[ -f "$APK" ] && [ -n "$N" ] || { echo "нужно: <APK> <номер>"; exit 1; }
W=vypusk/zhena-worker
NS=$(grep '^id = ' "$W/wrangler.toml" | cut -d'"' -f2)
S=$(grep '^SEKRET = ' "$W/wrangler.toml" | cut -d'"' -f2)
API=https://api.cloudflare.com/client/v4
AUTH="Authorization: Bearer ${CLOUDFLARE_API_TOKEN:-zaglushka}"
ACC=$(curl -sS -H "$AUTH" $API/accounts | python3 -I -c "import json,sys;print(json.load(sys.stdin)['result'][0]['id'])")
B="$API/accounts/$ACC/storage/kv/namespaces/$NS/values"
F=jarvis-zhena-$N.apk
TMP=$(mktemp -d)
split -b 24000000 -d -a 2 "$APK" "$TMP/c"
K=0
for c in "$TMP"/c*; do
  curl -sS -X PUT -H "$AUTH" -H "Content-Type: application/octet-stream" --data-binary @"$c" "$B/$F.$K" | grep -q '"success":true'
  K=$((K + 1))
done
printf '{"nomer": %s, "fajl": "%s", "razmer": %s, "chastej": %s, "md5": "%s"}\n' "$N" "$F" "$(stat -c %s "$APK")" "$K" \
  "$(md5sum "$APK" | cut -d' ' -f1)" > "$TMP/m.json"
curl -sS -X PUT -H "$AUTH" -H "Content-Type: application/octet-stream" --data-binary @"$TMP/m.json" "$B/jarvis-zhena.json" | grep -q '"success":true'
# Прежние сборки убрать — после описания, чтобы телефон не увидел описание без файла
for k in $(curl -sS -H "$AUTH" "$API/accounts/$ACC/storage/kv/namespaces/$NS/keys" | python3 -I -c "import json,sys;print(' '.join(x['name'] for x in json.load(sys.stdin)['result'] if '.apk.' in x['name']))"); do
  case "$k" in "$F".*) ;; *) curl -sS -X DELETE -H "$AUTH" "$B/$k" >/dev/null ;; esac
done
rm -rf "$TMP"
U="https://jarvis-zhena-vypusk.jarvisauto.workers.dev/$S"
echo "KV: $F ($K частей) и jarvis-zhena.json выложены"
sleep 5
curl -sS "$U/jarvis-zhena.json"
curl -sS -o /dev/null -w "APK: %{http_code} %{size_download} байт\n" "$U/$F"
