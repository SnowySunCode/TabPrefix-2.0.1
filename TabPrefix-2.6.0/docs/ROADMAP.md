# Состав версии 2.6.0

Функциональные модули полного TabPrefix включены и связаны в runtime.

| Модуль | Состояние реализации |
|---|---|
| Shell build, pinned dependencies, Java 8 target | Реализовано |
| Lifecycle, strict YAML/defaults, EN/RU | Реализовано |
| LuckPerms groups/contexts/events | Необязательная интеграция; есть собственные permission-группы |
| Text overrides, MiniMessage/legacy, commands/permissions | Реализовано |
| PNG/JPEG/EXIF/GIF и crop/color/fit | Реализовано |
| Asset storage/dedup, постоянные glyphs, PNG atlases | Реализовано |
| Resource pack ZIP/hash/revisions/rebuild/rollback | Реализовано |
| HTTP editor/API/sessions/upload/preview | Реализовано |
| Автоматические LAN-IP/порт, linkwifi, HTTP diagnostics | Реализовано |
| Drafts/codes/TTL/owner/group/atomic apply | Реализовано |
| Per-viewer TAB, готовые GIF frames, text fallback | Реализовано |
| Per-recipient chat, optional name tags | Реализовано |
| Audit chat/file/rotation/preferences/recent | Реализовано |
| SQLite schema 3/migration/cleanup/restart | Реализовано |
| Visual sidebar, header/footer, fixed TAB, animations | Реализовано |
| Group suffixes, ordinary TAB format, drag priorities and LuckPerms weights | Реализовано |
| Native boss bars, progress/timers, rotation, conditions/effects | Реализовано |
| Display Studio drag/drop, presets, undo/redo, JSON | Реализовано |
| «Таб мечты»: палитра → TAB, картинки, touch | Реализовано |
| Генератор Unicode pack/glyphs 1.12, исходные метрики | Реализовано; размер глифа ограничен клиентом |
| Native HUD TITLE/SUBTITLE/ACTION_BAR, условия, личные настройки | Реализовано |
| Стабильный sidebar, blank numbers с 1.20.3 | Реализовано; статус adapter в doctor |
| Свободные XY HUD | Native bitmap canvas с vanilla pack на 1.13+; для 1.12 стандартный fallback |
| Панель без правых scores | Native HUD на 1.13+ с pack; TAB footer на 1.12/без pack |
| Personal display preferences, schema 3 | Реализовано |
| Java/HTTP/browser verification | Java/HTTP проверки пройдены; браузерные проверки описаны в VALIDATION.md |
| Адаптеры 1.12–1.21.11/26.1–26.3, pack formats и fallback | Реализованы; пределы проверки — COMPATIBILITY.md |
| Живые Minecraft server/client smoke tests | В этой среде не выполнены |

Следующий этап выпуска — проверка готового JAR на реальных Spigot/Paper/Purpur перечисленных версий и совместимости с набором плагинов конкретного сервера. Это проверка платформенных адаптеров, а не обещание отсутствующих модулей. Список действий и пределы автоматического покрытия приведены в OPERATIONS.md и VALIDATION.md.

Автоматическое слияние чужих resource packs, смешанные клиентские версии через ViaVersion и миграция с перераспределением старых glyphs не входят в эту версию. Назначения сохраняются, чтобы старые сообщения/pack не меняли смысл.
