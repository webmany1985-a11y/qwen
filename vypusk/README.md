# Готовые APK

| Файл | Что это |
|---|---|
| `jarvis-auto-N.apk` | JARVIS Auto — Fermata Auto 2.0.2 под названием JARVIS Auto (JARVIS Музыка, JARVIS Экран) со значком JARVIS, полная (VLC, субтитры, перевод и прочие модули), с вкладками JARVIS (круг ассистента; разговор — Qwen с chat.qwen.ai, команды — JARVIS), Кинопоиск (hd.kinopoisk.ru, вместо ChatGPT) и TikTok (на телефоне — мобильный tiktok.com, в машине — версия для компьютера; досмотренное видео перелистывается само) и передачей непонятых голосовых фраз в JARVIS. Только arm64-v8a. |

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
