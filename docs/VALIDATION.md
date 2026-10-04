# Выполненные проверки TabPrefix 2.0.1

Проверка итогового исходного проекта: **45 core + 97 full integration + 39 delivery + 19 browser = 200 проверок**. Java-тесты работают с собранным relocated `dist/TabPrefix.jar`, реальным native SQLite, реальным JDK HTTP и изображениями; это не только mocks сервисов.

## Java

```bash
bash test.sh
# После первой загрузки зависимостей:
bash test.sh --offline
```

CoreIntegrationTest — 45 проверок: YAML/defaults/strict types, plugin metadata, форматирование и policies, injection-safe placeholders, проценты chat format, команды/readiness/права/completion, реальные SQL записи, 20 последовательных изменений, отказ при SQLite lock без порчи кэша, восстановление, reopen/delete, слишком новая схема, платформы/версии и безопасные пути.

FullIntegrationTest — 97 проверок:

- Реальные PNG pixels, crop/flips/fit/colors, oversized dimensions/files, JPEG EXIF, GIF palettes/offsets/disposal/restorePrevious/background и tick delays, ограничение scan frames, отключённая анимация.
- RGB/name prefix truncation, typed click URL, политика MiniMessage и отклонение `<newline>` в prefix.
- Session TTL/revocation/owner/limits; audit rotation/preferences/history/flush/reopen.
- Коды: wrong owner/group, expiry, concurrent single-use, reusable/unbound, rollback при нехватке glyphs, совместные text/graphic снимки.
- Порядок и постоянство назначений glyphs, dedup, saved editor options, удаление временных неназначенных данных и повторное открытие schema 2, migration schema 1.
- ZIP/font JSON/pack format 5 и 6, реальные pixels страниц, atlas capacity/individual mode, детерминированный hash и rollback.
- Реальный HTTP: Bearer auth, Origin, upload/preview/save, invalid input/limits, отсутствие доступа к DB/private paths, HEAD/Range/ETag packs и отзыв token.

## Доставка команд — 39 проверок

MessageDeliveryTest вызывает production MessageService из конечного JAR и записывает фактические вызовы Bukkit/Spigot API у тестовых отправителей. Проверены обычные ответы игрока без регистрации audience, console/command block/custom sender, RGB, typed clickable URL с fragment, SYSTEM message type, click/hover, отсутствие дублирования, fallback при отказе rich API, отсутствие token/URL в предупреждениях, offline/close, безопасные подстановки и missing keys. Пройдены реальные core command handlers help/status/usage/no-permission, readiness replies FeatureCommands, doctor при неполном startup, запись отчёта на границу server log и completion.

Это проверка адаптера API с тестовыми отправителями, а не подключённого Minecraft-клиента. Отсутствие ответа на конкретном сервере проверяется через `/tabprefix:lptab doctor` и его лог.

## Реальный браузер

Опциональный тест использует Node.js, Playwright и доступный Chromium. Они не являются зависимостями Minecraft-плагина. После `bash test.sh`:

```bash
node tests/editor-browser.cjs "$PWD" --start-server
```

Если Playwright или браузер установлены в другом месте, задайте `TABPREFIX_PLAYWRIGHT_MODULE` (путь/имя Node module) и `TABPREFIX_BROWSER_EXECUTABLE` (путь Chromium). `--start-server` запускает Java fixture и браузер в одном окружении; отдельный интерактивный fixture доступен через `bash tests/editor-test-server.sh`.

Выполнено 19 проверок на Chromium 153.0.8010.0: bound owner/group, no-token state, RU/EN, безопасный text preview без HTML injection, реальная загрузка PNG, pointer crop, server preview, GIF upload/frames/pause/timeline, save code и команда, desktop 1320×1050, mobile 390×844, отсутствие горизонтального переполнения и JavaScript errors. Screenshots создаются локально в `.build/browser-checks`, временная база fixture в production не устанавливается.

## Сборка и пределы проверки

Компиляция выполнялась на JDK 17 с `--release 8`; production bytecode проверяется на Java 8. Embedded libraries изолируются, service descriptors объединяются/переносятся, API/build tools исключены, editor и обе локализации включены. SHA-256 закреплённых dependencies проверяется build script. JAR/ZIP проверяются на целостность, полный source archive — на наличие необходимых файлов и возможность сборки после распаковки.

**Не выполнялись:** запуск JVM 8, живой Spigot/Paper/Purpur server, подключение Minecraft-клиента, фактический NMS packet render и совместная работа с произвольными сторонними TAB/chat/team/pack plugins. Эти ограничения нельзя считать покрытыми Java/HTTP/browser тестами. Практический server checklist находится в [OPERATIONS.md](OPERATIONS.md).
