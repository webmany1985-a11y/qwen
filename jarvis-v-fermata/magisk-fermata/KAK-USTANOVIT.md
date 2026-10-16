# Fermata Auto системным приложением (модуль Magisk)

Зачем: проверить, покажет ли Android Auto Fermata Auto, если она встроена в
систему, как JARVIS и сам Android Auto. Перенос через Lucky Patcher 16.10.2026
не сработал: копия была только с подписью v3, телефон её отверг и стёр Fermata.

Модуль кладёт **уже установленную** Fermata Auto в `/system/app/FermataAuto`
(не priv-app: особых разрешений не нужно). APK внутри модуля нет.

## Порядок

1. Поставить `vypusk/fermata-auto-2.0.2-jarvis.apk` (подпись v2+v3) поверх
   текущей Fermata. Модуль сверяет SHA-256 и с другой сборкой не ставится.
2. Magisk → Модули → Установить из хранилища → `fermata-sistemnaya.zip`.
   Копии Fermata от Lucky Patcher модуль переносит в `/data/adb/fermata-lp-backup`.
3. Перезагрузка.
4. Android Auto → «Настроить панель запуска»: есть ли Fermata Auto.

Сборка zip: `cd jarvis-v-fermata/magisk-fermata && zip -qr -X ../fermata-sistemnaya.zip META-INF module.prop customize.sh`

## Откат

Magisk → Модули → «Fermata Auto — системное приложение» → выключить или
удалить → перезагрузка. Обычная установка Fermata остаётся.
