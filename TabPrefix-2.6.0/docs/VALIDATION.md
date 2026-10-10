# Выполненные проверки TabPrefix 2.6.0

Дата проверки: 10 октября 2026. Production-код собран в `dist/TabPrefix.jar` с Java 8 bytecode, проверки выполнялись на JDK 17. SQLite, HTTP, ZIP, PNG/GIF и Chromium используются настоящие. Bukkit/NMS и события клиента моделируются независимыми fixtures. Здесь нет утверждения о запуске живого Minecraft-клиента или серверного ядра.

## Java, HTTP и автономный запуск

| Набор | Проверки | Основной результат |
|---|---:|---|
| CoreIntegrationTest | 45 | YAML, типы, plugin metadata, форматирование, команды, права, SQLite и восстановление |
| FullIntegrationTest | 97 | Реальные PNG/JPEG/GIF, обработка изображений, коды, glyph persistence, pack ZIP, миграции и HTTP |
| MessageDeliveryTest | 39 | Вызовы Bukkit/Spigot, кликабельные команды и ссылки, безопасные подстановки, отказ rich API |
| NetworkIntegrationTest | 52 | LAN/IPv6, смена адресов, занятые порты, фактический HTTP listener, Origin и pack URL |
| DisplayIntegrationTest | 186 | Валидация документа, ревизии SQLite, sidebar/name tags, TAB contract, HUD delivery и authenticated HTTP |
| BossBarIntegrationTest | 65 | Показатели, таймеры, ротация, flags, права/миры, дифференциальные обновления и cleanup |
| TabStyleIntegrationTest | 52 | Суффиксы, формат имён, все способы сортировки и кэшированные метаданные групп |
| CompatibilityIntegrationTest | 288 | Версии, лимиты текста, цвета, scoreboard API, форматы ZIP и миграция pack |
| DreamIntegrationTest | 90 | Прокси API, blank number adapter, HUD lifecycle, постоянные glyphs и Unicode PNG 1.12 |
| NativeCanvasIntegrationTest | 158012 | Геометрия сгенерированных bitmap providers, advances, цвета, изображения, manifest и границы ввода |
| NativeCanvasIntegrationTest --standalone | 7 | LP API отсутствует в classpath; самостоятельные группы и fallback загружаются |

Первые девять наборов содержат 914 assertions. Большой счётчик NativeCanvas возникает из проверок отдельных глифов и пиксельных метрик в нескольких ZIP, а не из тестирования тысяч клиентов. Проверены пакеты для 1.12.2, 1.13.2, 1.18.2, 1.19.4, 1.21.11 и 26.3; на 1.12 canvas отсутствует и сохраняются Unicode-глифы.

NativeCanvas проверяет собственные supplementary codepoints, signed spaces, нулевую суммарную ширину блоков, bold/italic и RGB/reset, сохранность обычных цифр, каждый provider в JSON, реально прочитанные PNG и физические ячейки ≤256×256. Есть крайние Y=-512, размеры 4…32, широкие картинки, разные GIF-кадры, детерминированная сборка, повторное чтение manifest и отказ дробным/некорректным координатам.

DisplayIntegration вызывает production PackBuilder/ResourcePackService/PackDelivery/DisplayController из конечного JAR. Проверены отправленные private glyphs, отсутствие classic objective в HUD mode, TAB fallback до загрузки, отсутствие разрешения canvas по одному ACCEPTED, включение после SUCCESSFULLY_LOADED, личные переключатели и возврат к fallback при изменённой геометрии. Это API-модель, не игровой renderer.

## Совместимость API и packet contracts

151 прямой Bukkit method/field reference разрешён на каждой из трёх API-поверхностей, полученных из compile API 1.16.5. Они не выдаются за настоящие дистрибутивы 1.12 или 26.x.

PackCompatibilityTest: 20 проверок старого API и 33 современного. Проверены legacy setResourcePack, pack IDs, чужие status events, ownership и условия разблокировки canvas.

В десяти независимых NMS families выполнены 990 packet assertions: legacy12/13/14/15, spigot17/19, update7/8/9 и mojang26. Проверяются reflective constructor/field/method shapes, synthetic player profiles, изменения имён/skin/latency, cleanup, отсутствие удаления реальных UUID и отказ недоступного adapter. Fixtures не входят в production JAR.

## Chromium

Chromium 153.0.8010.0 подключался к реальному встроенному HTTP-редактору с SQLite. Данные версий относятся к настройкам server fixture.

| Сценарий | Проверки |
|---|---:|
| Префиксы, desktop/mobile | 26 |
| Display Studio, desktop/touch | 91 |
| Dream TAB и canvas, fixture 1.21.11 | 68 |
| Dream TAB, fixture 1.12.2 | 58 |

Проверены перетаскивание и касание, undo/redo, импорт/экспорт, права и отзыв доступа, Origin, conflicts, PNG/GIF, реальные ZIP download, сохранение через HTTP, темы, SVG-палитра и поиск Ctrl/Cmd+K/Esc. Новые сценарии проверяют отдельные X/Y/размер, повторное перемещение размещённого блока, значения игрока в предпросмотре, отказ дробным координатам, автоматический pack build и отключение FREE_XY на 1.12.

Скриншоты desktop/light/mobile просмотрены; у проверенных размеров страницы нет горизонтального переполнения. Статус сборки обновляется автоматически, сведения о legacy-ограничениях остаются видны во время сборки.

## Воспроизведение

```bash
bash test.sh
bash test-compatibility.sh
# При уже загруженных зависимостях:
bash test.sh --offline
# При актуальной сборке:
bash test.sh --skip-build --offline
```

Браузерные сценарии требуют локальные Node, Playwright и Chromium. Можно задать TABPREFIX_PLAYWRIGHT_MODULE и TABPREFIX_BROWSER_EXECUTABLE. Из корня проекта:

```bash
node tests/editor-browser.cjs "$PWD" --start-server
node tests/studio-browser.cjs "$PWD"
node tests/dream-browser.cjs "$PWD"
node tests/dream-browser.cjs "$PWD" --legacy
```

Studio и Dream используют общий файл fixture info, поэтому их запускают последовательно. Java-проверки имеют ограниченный heap. Перезапуск только одного набора уместен после изменения его сценария; публикация требует актуальной production-сборки.

## Итоговый архив и границы проверки

Исходный ZIP извлечён в отдельную чистую папку. При повторной offline-сборке с теми же проверенными зависимостями содержимое всех JAR entries совпало с release JAR (ZIP timestamps не сравниваются). Проверены CRC обоих архивов, SHA256SUMS всех файлов проекта, 91 production Java-файл, bytecode major 52, версия 2.6.0 и softdepend LuckPerms. В JAR отсутствуют тестовые classes, Bukkit/NMS и LP API; локальные web/workbench ресурсы присутствуют. LICENSE сохранён побайтно.

В этой среде не запускались Minecraft renderer, живые Spigot/Paper/Purpur, JVM 8/25 и смешанные клиенты ViaVersion. Настоящий GUI Scale, shadow/italic bearing, принятие pack в игре и соответствие packet shapes конкретному серверному build требуют smoke test. План проверки и точные запасные пути находятся в [NATIVE-HUD.md](NATIVE-HUD.md) и [COMPATIBILITY.md](COMPATIBILITY.md).

На 1.12 scoreless panel выводится в TAB. Свободный XY HUD и скрытие числовой колонки старого стандартного sidebar не реализованы.
