# Состав версии 2.0.1

Функциональные модули полного TabPrefix включены и связаны в runtime.

| Модуль | Состояние реализации |
|---|---|
| Shell build, pinned dependencies, Java 8 target | Реализовано |
| Lifecycle, strict YAML/defaults, EN/RU | Реализовано |
| LuckPerms groups/contexts/events | Реализовано |
| Text overrides, MiniMessage/legacy, commands/permissions | Реализовано |
| PNG/JPEG/EXIF/GIF и crop/color/fit | Реализовано |
| Asset storage/dedup, постоянные glyphs, PNG atlases | Реализовано |
| Resource pack ZIP/hash/revisions/rebuild/rollback | Реализовано |
| HTTP editor/API/sessions/upload/preview | Реализовано |
| Drafts/codes/TTL/owner/group/atomic apply | Реализовано |
| Per-viewer TAB, готовые GIF frames, text fallback | Реализовано |
| Per-recipient chat, optional name tags | Реализовано |
| Audit chat/file/rotation/preferences/recent | Реализовано |
| SQLite schema 2/migration/cleanup/restart | Реализовано |
| Java/HTTP/browser verification | Пройдено: 200 проверок |
| Живые Minecraft server/client smoke tests | В этой среде не выполнены |

Следующий этап выпуска — проверка готового JAR на реальных Spigot/Paper/Purpur 1.16.x и совместимости с набором плагинов конкретного сервера. Это проверка платформенных адаптеров, а не обещание отсутствующих модулей. Список действий и пределы автоматического покрытия приведены в OPERATIONS.md и VALIDATION.md.

Автоматическое слияние чужих resource packs, клиентские версии вне 1.16–1.16.5 и миграция с перераспределением старых glyphs не входят в эту версию. Назначения сохраняются, чтобы старые сообщения/pack не меняли смысл.
