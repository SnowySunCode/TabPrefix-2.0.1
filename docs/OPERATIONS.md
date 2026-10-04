# Эксплуатация

## Данные и backup

Все пути относительны `plugins/TabPrefix`. Каталоги должны быть различными, не вложенными друг в друга и без symlink; SQLite не может находиться внутри очищаемых каталогов.

| Путь по умолчанию | Содержимое |
|---|---|
| `config.yml`, `messages.yml`, `messages_ru.yml` | Настройки и сообщения |
| `tabprefix.db` | Группы, assets, glyphs, drafts, codes, admin preferences |
| `data/<uuid>/` | Immutable original, options.json и PNG frames |
| `packs/` | ZIP ревизии и active.json |
| `temp/` | Редактируемые загрузки sessions |
| `staging/` | Временная запись assets/pack |
| `audit.log` и ротации | История операций |

Для согласованного резервного копирования остановите сервер и скопируйте весь каталог TabPrefix. Для горячего backup нужна процедура SQLite backup с согласованной копией media; простое копирование только `.db` при активном WAL недостаточно. Восстановление выполняйте при остановленном сервере, возвращая БД и data вместе. Не удаляйте assets с уже назначенными glyphs. Старый ZIP не восстанавливает данные SQL.

При превышении media quota удаление групп не освобождает назначенные assets. Увеличьте лимит или подготовьте контролируемый перенос на отдельную новую базу и pack; ручная перенумерация нарушит ранее сохранённые строки.

## Адреса и reverse proxy

Для прямого подключения: `web.public-host` содержит только IP/домен без схемы и порта; порт задаёт `web.port`. `web.bind-address: 0.0.0.0` принимает соединения на интерфейсах машины, но не открывает firewall и NAT. Проверяйте ссылку с машины игрока.

Для HTTPS можно использовать Nginx. Это пример внешнего конфигурационного фрагмента; сертификат и DNS должны уже быть настроены:

```nginx
server {
    listen 443 ssl;
    server_name prefix.example.com;
    ssl_certificate /etc/letsencrypt/live/prefix.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/prefix.example.com/privkey.pem;
    client_max_body_size 12m;
    location / {
        proxy_pass http://127.0.0.1:8765;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

В плагине задайте `web.bind-address: 127.0.0.1`, `web.public-base-url: https://prefix.example.com` и перезапустите сервер. При увеличении лимита upload согласуйте `images.max-file-size-mb`, `web.max-request-size-mb` и proxy body limit. Встроенный сервер отдаёт HTTP; TLS завершает внешний proxy. Личная ссылка даёт доступ к сессии её владельца — не публикуйте её и код в общий чат.

## HTTP API

Ссылку создаёт команда в игре. Static editor и pack доступны без token, но данные редактора защищены. API находится под `/api/`; заголовок `Authorization: Bearer <token>` обязателен. Session token получен из fragment ссылки, не из query string. Целевая группа задаётся сервером при открытии.

| Метод/маршрут | Назначение |
|---|---|
| `GET /editor/` | Встроенный интерфейс |
| `GET /api/session` | Сессия, лимиты, текущий текст/изображение/options |
| `POST /api/upload` | Raw image body; декодирование и первичная обработка |
| `GET /api/original` | Оригинал текущего изображения |
| `GET /api/frame/<index>` | PNG обработанного кадра |
| `POST /api/preview` | JSON options → новый preview |
| `POST /api/text-preview` | JSON text/format → безопасный component JSON |
| `POST /api/save` | JSON image/textOverride/text/format/options → draft и code |
| `GET, HEAD /packs/TabPrefix-<sha1>.zip` | Версионный resource pack |

При `versioned-files: false` используется alias с hash query. Pack отдаёт ETag, Range/206 и 416 для неверного диапазона. API/оригиналы не кэшируются публично. Ошибки сообщаются кодами 400/401/403/409/413/429/503; 401 требует новой ссылки, 429 — паузы, 503 может означать заполненную очередь.

## Reload и диагностика

`/lptab reload` проверяет все настройки и сообщения до их активации. Изменения storage paths, сетевого bind/port/адресов, geometry/range и формата кодов требуют перезапуска. При отказе предыдущая конфигурация остаётся активной.

| Симптом | Действие |
|---|---|
| Редактор недоступен | Проверить port/bind, лог bind error, firewall, DNS, NAT и внешний URL |
| Сессия истекла | Выполнить `webeditor` заново; новая ссылка отзывает предыдущую |
| Код отклонён | Проверить TTL, владельца, текущую LP-группу и single-use |
| Изображение не видно | Проверить `pack status`, внешний URL, принятие/успешную загрузку pack |
| Pack pending после timeout | Переподключить игрока; pending сохраняется для позднего ответа |
| После rollback часть графики исчезла | Asset отсутствует в старом pack; вернуть новую revision либо rebuild |
| Недостаток glyphs | Проверить `glyphs`; назначения постоянны, удаление префикса их не освобождает |
| Startup media verification failed | Восстановить согласованную копию data и SQLite, изучить конкретный файл в логе |
| Ошибка более новой схемы | Использовать соответствующую версию плагина; понижение схемы не выполняется |
| Name tag занят другой team | Согласовать владельца teams; чужие teams плагин не перезаписывает |

Audit recent показывает до 50 последних операций, общий буфер — 200. Файловая ротация по умолчанию 10 MiB и 3 предыдущих файла; личный `/lptab adminlog off` не отключает файл. Токены и значения save codes не включаются в audit.

## Проверка на реальном сервере

Проверьте сначала на копии: startup/status → join двух клиентов → текст/графика/GIF → загрузка и отказ pack у разных зрителей → обновление LP group/context → set/remove/savecode → reload → перезапуск → rollback → отключение плагина и восстановление TAB/teams. Отдельно проверьте установленные TAB/chat/scoreboard/resource-pack плагины. Для каждого поддерживаемого 1.16.x нужен реальный server/client smoke test; автоматические проверки проекта не заменяют его.

## Команды молчат

В версии 2.0.1 обычные ответы идут напрямую через Bukkit. После установки убедитесь, что заменён именно JAR, сервер перезапущен и TabPrefix включён. Выполните в консоли сервера `tabprefix:lptab doctor` (без начального slash). В игре — `/tabprefix:lptab doctor` и `/tabprefix:lptab help`. Полное имя обращается к команде TabPrefix при конфликте обычного `/lptab`.

Doctor должен показать `version=2.0.1`, ядро/Minecraft, `ready`, версию Java и `replies=Bukkit/Spigot`. Если указана старая версия, запущен прежний JAR. `ready=false` требует проверки startup/error log. Если полное имя неизвестно, проверьте загрузку плагина и его регистрацию. Если doctor пишет отчёт в лог, но чат молчит, проверьте delivery warnings, совместные chat/protocol plugins и настройки клиента. Если `/lp info` отвечает, а отчёта doctor нет, нужен лог запуска TabPrefix и конкретная версия ядра.

Строка `issued server command` фиксирует ввод команды. Обычная справка предназначена отправителю и не обязана дублироваться в server log; doctor специально даёт диагностический вывод.
