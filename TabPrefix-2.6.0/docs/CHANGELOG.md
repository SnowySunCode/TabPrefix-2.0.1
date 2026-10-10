# Изменения

## 2.6.0

Свободный HUD canvas через ресурс-пак плагина, панель без цифр с запасным TAB-выводом, необязательный LuckPerms и обновлённые редакторы с темами и поиском. [Release notes](CHANGELOG-2.6.0.md).

## 2.5.0

«Таб мечты», глифы/пак 1.12, стабильный sidebar с blank numbers на новых клиентах и нативные экранные блоки. [Release notes](CHANGELOG-2.5.0.md).

## 2.4.0

Адаптеры 1.12–1.21.x и 26.x, форматы resource pack, старые лимиты/цвета и API/packet матрица. [Release notes](CHANGELOG-2.4.0.md).

## 2.3.0

Суффиксы групп, цвет имени, общий формат TAB, сортировка обычного списка и приоритеты перетаскиванием. Новый шаблон «Спокойный TAB» и личные настройки. [Release notes](CHANGELOG-2.3.0.md).

## 2.2.0

Полностью настраиваемые native боссбары: показатели, таймеры, ротация, условия, эффекты и личные настройки. [Release notes](CHANGELOG-2.2.0.md).

## 2.1.0

Визуальный конструктор sidebar, Header/Footer, фиксированного TAB, name tags и анимаций. [Release notes](CHANGELOG-2.1.0.md).

## 2.0.2

- Адреса активных интерфейсов сервера, автоматический выбор подсети игрока и `/lptab linkwifi` с интерактивными строками.
- Подбор свободного HTTP-порта в автоматическом LAN-режиме, `web.port: 0`, реальные порты в ссылках редактора и pack.
- Отдельный временный выбор адреса для игрока и Origin, привязанный к его сессии.
- Конкретные состояния HTTP и причины ошибок в командах, `doctor` и `status`.
- EN/RU, справка, completion и инструкция по обновлению без удаления данных.
- Пройдены 252 автоматические проверки, включая 52 новые сетевые и 19 браузерных.

Полные release notes: [CHANGELOG-2.0.2.md](CHANGELOG-2.0.2.md). Настройка LAN и пределы доступа без проброса: [NETWORKING.md](NETWORKING.md).

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
