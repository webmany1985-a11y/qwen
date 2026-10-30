# Готовые APK

| Файл | Что это |
|---|---|
| `jarvis-auto-N.apk` | JARVIS Auto — Fermata Auto 2.0.2 под названием JARVIS Auto (JARVIS Музыка, JARVIS Экран) со значком JARVIS, полная (VLC, субтитры, перевод и прочие модули), с вкладками JARVIS (круг ассистента; разговор — Qwen с chat.qwen.ai, команды — JARVIS), Кинопоиск (hd.kinopoisk.ru, вместо ChatGPT), «Видео» (свой список сайтов, видео сразу во весь экран) и TikTok (на телефоне — мобильный tiktok.com, в машине — версия для компьютера; досмотренное видео перелистывается само) и передачей непонятых голосовых фраз в JARVIS. Только arm64-v8a. |

Номер сборки — в имени файла (с 21.10.2026, по просьбе пользователя): N из
`vypusk/nomer-sborki.txt`, при каждом выпуске +1, старый файл удаляется.
Внутри приложения версия прежняя (2.0.2, 268).

Сборка: `Fermata-2.0.2/` с наложенным `jarvis-v-fermata/fermata-jarvis.patch`,
`./gradlew -I ../vypusk/compress-jni.gradle fermata:packageAutoReleaseUniversalApk -PABI=arm64-v8a -PAPP_ID_SFX=.lichnaya`
с `NO_GS=true`; init-скрипт только включает сжатие нативных библиотек
(`jniLibs.useLegacyPackaging = true`), чтобы APK уложился в предел GitHub 100 МБ.
Подпись — ключ Fermata по умолчанию из `res/build_jks.gradle`
(SHA-256 сертификата `6bd055ae…ba910e`).

Порядок установки: сначала JARVIS (сборка 212 или новее), потом Fermata.
Fermata, поставленная из Play или с GitHub автора, подписана другим ключом —
её нужно удалить перед установкой.

Имя пакета — `me.aap.fermata.auto.lichnaya` (параметр `APP_ID_SFX`, как велит
README Fermata): голое `me.aap.fermata.auto` Android Auto отсеивает даже при
правильной записи об установке (16.10.2026). APK переподписан тем же ключом
схемами v2+v3: `apksigner sign --min-sdk-version 24`. Ставить через
KingInstaller на пустое место, чтобы установщиком был Google Play, а
инициатором — стандартный установщик пакетов.

**Сборка в облаке (30.10.2026, выпуск 38).** Maven Central из облака отвечает 429 — добавить `-I ../vypusk/mirror.gradle`
(то же содержимое с зеркала Google). Нужны `ndk;29.0.14206865` и `cmake;4.1.2` (`sdkmanager --install`). Установка на
телефон — **только через KingInstaller** (см. ниже), APK — файлом в чат (64 МБ проходит).

**Запрещено: `pm install -r -i com.android.vending` из root-консоли** (ошибка 30.10.2026, сборки 38–42). Запись об
установке получается без инициатора (`installInitiator` пуст, `packageSource=1`), и Android Auto приложение не
показывает, хотя установщик — Google Play и подпись та же. Проверено по `/data/system/packages.xml` на телефоне:

| | `pm install` из root | KingInstaller (работает в машине, проверено 30.10.2026) |
|---|---|---|
| installer | com.android.vending | com.android.vending |
| installInitiator | — | com.google.android.packageinstaller |
| installOriginator | — | com.example.kinginstaller |
| packageSource | 1 | 4 |

Проверка после установки (root, канал): `abx2xml /data/system/packages.xml - | grep -oE '<package
name="me.aap.fermata.auto.lichnaya"[^>]*>'` — должен быть `installInitiator="com.google.android.packageinstaller"`.
Установка от имени uid Play через свой установщик отклонена средой как подмена магазина — не пытаться.
