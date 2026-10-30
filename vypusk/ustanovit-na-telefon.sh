#!/bin/sh
# Установка JARVIS Auto на телефон через канал так, чтобы Android Auto его видел (правило 30.10.2026).
# Android Auto смотрит не только установщика (Google Play), но и КТО ИНИЦИИРОВАЛ установку: без записи
# installInitiator=com.google.android.packageinstaller приложение в машине не появляется. Такую запись даёт только
# установка через окно системного установщика, поэтому `pm install` из root не годится.
# Скрипт сам скачивает сборку на телефон и открывает её в KingInstaller; пользователь нажимает «Установить».
# Запуск: sh vypusk/ustanovit-na-telefon.sh N   (tk.sh — помощник канала телефона)
N=${1:?номер сборки}
TK=${TK:-/tmp/claude-0/-home-user/90e13294-ac36-5628-9d87-69cb04975f9a/scratchpad/tk.sh}
F=/sdcard/Download/jarvis-auto-$N.apk
URL=https://raw.githubusercontent.com/webmany1985-a11y/qwen/claude/jarvis-app-integration-0ewhi6/vypusk/jarvis-auto-$N.apk
sh "$TK" shell "curl -sSL -o $F $URL && unzip -l $F >/dev/null && am start -a android.intent.action.VIEW \
-t application/vnd.android.package-archive -d file://$F -n com.example.kinginstaller/.ApkHandler" true
echo "После нажатия «Установить» проверить запись:"
echo "sh \$TK shell \"abx2xml /data/system/packages.xml - | grep -oE '<package name=\\\"me.aap.fermata.auto.lichnaya\\\"[^>]*>'\""
echo "нужно installInitiator=\"com.google.android.packageinstaller\""
