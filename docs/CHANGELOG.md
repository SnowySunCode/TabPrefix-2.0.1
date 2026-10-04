# Изменения

## 2.0.1

- Убран audience provider из канала ответов команд; native Bukkit отправляет обычный текст всем CommandSender.
- Для interactive сообщений используется Spigot SYSTEM и JSON bridge, сохраняющий click/hover/RGB.
- Ошибка rich API даёт читаемый text/URL fallback и одно предупреждение без session tokens.
- Добавлен `/lptab doctor` с `tabprefix.status`, прямым ответом, readiness/version и отчётом в лог для игрока; доступен также `/tabprefix:lptab doctor`.
- Добавлены 39 проверок production MessageService и native API calls. Пройдено 200 проверок всего.
- Storage schema, formats, defaults и workflow сохранены; обновление заменяет JAR при остановленном сервере.

Лог пользователя подтверждает отсутствие ответа, но не содержит startup/packet diagnostics. Сборка устраняет зависимость ответов от audience registry; конкретный сервер проверяется doctor после установки.

## 2.0.0

Полная реализация text/image/GIF, editor, drafts/codes, resource packs, SQLite schema 2, LP integration, display и audit.
