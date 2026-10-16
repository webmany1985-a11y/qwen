#!/system/bin/sh
# Fermata Auto системным приложением (/system/app, не priv-app: особых
# разрешений ей не нужно, список privapp не требуется — меньше риск при загрузке).
# APK внутри модуля нет: берём уже установленную Fermata Auto с телефона.
PKG=me.aap.fermata.auto
# Сборка из jarvis-v-fermata, подписанная схемами v2+v3: перенос через
# Lucky Patcher (только v3) телефон 16.10.2026 отверг — «Failed to collect
# certificates … APK Signature Scheme v3» — и стёр Fermata.
WANT=cb7d3b90be5c537409a26d14190ce275e7f70226aef800fe2facc61ca2a0086b
DIR=$MODPATH/system/app/FermataAuto

APK=$(pm path $PKG 2>/dev/null | grep -m1 'base.apk' | cut -d: -f2)
[ -n "$APK" ] && [ -f "$APK" ] || abort "! Fermata Auto не установлена. Сначала поставьте APK из репозитория, затем этот модуль."
case "$APK" in /system/*) abort "! Fermata Auto уже системная ($APK). Сначала обновите её из APK репозитория.";; esac

GOT=$(sha256sum "$APK" | cut -d' ' -f1)
if [ "$GOT" != "$WANT" ]; then
  ui_print "! Установлена не та сборка Fermata Auto."
  ui_print "  Скачайте и поставьте fermata-auto-2.0.2-jarvis.apk из репозитория"
  ui_print "  (ветка claude/jarvis-app-integration-0ewhi6), затем снова этот модуль."
  abort "! Модуль не установлен"
fi

# Остатки переноса Lucky Patcher — испорченная копия мешает. Не удаляем,
# а переносим в /data/adb/fermata-lp-backup (вернуть — переложить обратно).
LP=/data/adb/modules/luckypatcher_apps/system
BK=/data/adb/fermata-lp-backup
for f in $LP/priv-app/me.aap.fermata.auto* $LP/app/me.aap.fermata.auto* $LP/etc/permissions/*me.aap.fermata.auto*; do
  [ -e "$f" ] || continue
  mkdir -p $BK
  ui_print "- Убираю копию Lucky Patcher: $f → $BK"
  mv "$f" $BK/
done

ui_print "- Fermata Auto: копирую $APK"
mkdir -p "$DIR/lib/arm64"
cp "$APK" "$DIR/FermataAuto.apk" || abort "! Не удалось скопировать APK"
# Нативные библиотеки в APK сжаты: системное приложение берёт их из lib/
T=$TMPDIR/fermata-lib
rm -rf $T; mkdir -p $T
unzip -oq "$DIR/FermataAuto.apk" 'lib/arm64-v8a/*' -d $T 2>/dev/null
mv $T/lib/arm64-v8a/* "$DIR/lib/arm64/" 2>/dev/null
ui_print "- Библиотек: $(ls "$DIR/lib/arm64" | wc -l)"
rm -rf $T

# Свежее время файла и сброс кэша разбора пакетов — как в модуле Джарвиса:
# иначе система может взять из кэша старую запись
touch "$DIR/FermataAuto.apk"
rm -rf /data/system/package_cache/*
set_perm_recursive "$DIR" 0 0 0755 0644 u:object_r:system_file:s0
ui_print "- Готово. Перезагрузите телефон."
