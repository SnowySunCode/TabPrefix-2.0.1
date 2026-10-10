# TabPrefix 2.6.0

- FREE_XY screen canvas на 1.13+: перенос блоков мышью/касанием, собственные X/Y/размер, текст и кадры PNG/GIF через vanilla pack плагина.
- Scoreless HUD и TAB footer modes: панель без objective/scores, отдельное положение, запасной TAB-вывод до загрузки подходящей ревизии pack. Классический sidebar остаётся доступным.
- Генератор шрифтов: private supplementary glyphs, подписанные пробелы для старого/современного формата, допустимые bitmap ascents, ограниченные физические размеры ячеек, измеренные по PNG advances и manifest. HUD-провайдеры не дублируются в prefix-only font.
- Manifest загружается при рестарте; изменённая геометрия требует подходящего pack. Графика включается после успешного status event, с сохранением сериализации, pack UUID ownership и отказа чужого формата.
- LuckPerms стал необязательным. Собственные permission-группы, порядок Studio и default fallback работают без его API в classpath.
- Общий стиль встроенных редакторов, темы, SVG-значки, поиск Ctrl/Cmd+K/Esc, готовые текстовые префиксы и автоматическое обновление статуса сборки pack. Все ресурсы поставляются локально в JAR.
- Новые проверки ZIP/геометрии, крайних координат, native delivery, standalone loading, API statuses и браузерного canvas. JVM сборки/тестов имеет ограниченный heap; тестовый incremental run поддерживает `bash test.sh --skip-build` после актуальной сборки.

На 1.12 свободный XY canvas и blank scores старого SIDEBAR не реализованы: используется native TAB/action-bar fallback. Реальный игровой renderer и серверные builds ещё требуют smoke test. Подробности: [NATIVE-HUD.md](NATIVE-HUD.md), [VALIDATION.md](VALIDATION.md).
