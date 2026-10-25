# RT-11 — Синтетические письма MIME

- Дата: 2026-10-25
- Исполнитель: Codex
- Бриф: docs/research-tasks/RT-11-synthetic-mime.md
- Данные: testdata/mime/

## Кратко

- [код] Созданы все обязательные M01–M14 и дополнительные M15–M20: 20 писем с ожидаемыми результатами.
- [код] Цепочка M09 содержит настоящий MSG: EML → MSG → ZIP → TXT.
- [код] TNEF содержит RTF и вложение с длинным русским именем; прочитан POI HMEF.
- [код] S/MIME содержит настоящие CMS-структуры, созданные только учебным сертификатом.
- [код] Все письма меньше 64 KiB, имеют CRLF, размеры и SHA-256 рассчитаны.
- [предположение] Правила предупреждений и спорные восстановления — предложения для будущего парсера.

## Состав набора

| ID | Файл | Покрытие | Создан | Проверен |
|---|---|---|---|---|
| M01 | `M01-rfc2231-continuations.eml` | rfc2231-continuations | да | Python email |
| M02 | `M02-encoded-word-filename.eml` | encoded-word-filename | да | Python email |
| M03 | `M03-koi8r.eml` | koi8r | да | Python email |
| M04 | `M04-windows-1251.eml` | windows-1251 | да | Python email |
| M05 | `M05-mislabelled-charset.eml` | mislabelled-charset | да | Python email |
| M06 | `M06-plain-and-html.eml` | plain-and-html | да | Python email |
| M07 | `M07-html-only.eml` | html-only | да | Python email |
| M08 | `M08-cid-inline-images.eml` | cid-inline-images | да | Python email |
| M09 | `M09-eml-msg-zip-chain.eml` | eml-msg-zip-chain | да | Python email + POI HSMF 5.4.1 |
| M10 | `M10-tnef-winmail.eml` | tnef-winmail | да | Python email + POI HMEF 5.4.1 |
| M11 | `M11-ics-tzid.eml` | ics-tzid | да | Python email |
| M12 | `M12-smime-signed.eml` | smime-signed | да | Python email + OpenSSL 3.0.3 |
| M13 | `M13-smime-encrypted.eml` | smime-encrypted | да | Python email + OpenSSL 3.0.3 |
| M14 | `M14-broken-boundary.eml` | broken-boundary | да | Python email |
| M15 | `M15-smime-opaque-signed.eml` | smime-opaque-signed | да | Python email + OpenSSL 3.0.3 |
| M16 | `M16-unsafe-filenames.eml` | unsafe-filenames | да | Python email |
| M17 | `M17-no-filename.eml` | no-filename | да | Python email |
| M18 | `M18-split-encoded-word.eml` | split-encoded-word | да | Python email |
| M19 | `M19-nesting-over-limit.eml` | nesting-over-limit | да | Python email |
| M20 | `M20-bad-headers.eml` | bad-headers | да | Python email |

## Как получены файлы

- Python 3.14.3; email, base64, quopri, struct, zlib, zipfile, hashlib.
- Генератор: `testdata/.work/RT-11/generate.py`, исключён из git.
- Код BinaryFixtures.java лежит там же; classpath собран из уже существующего кэша Gradle.
- PNG: собственные IHDR/IDAT/IEND с CRC32, один RGB-пиксель.
- ZIP: один layout.txt с фиксированной датой, без опасной распаковки.
- MSG: CFB через POIFSFileSystem, MAPI-потоки темы, тела, свойств и одного вложения.
- MSG прочитан MAPIMessage; имя package.zip и его байты сравнены с оригиналом.
- TNEF: сигнатура, ключ, уровни 1/2, атрибуты и суммы по 16 бит.
- RTF: MELA-оболочка с обычным текстом, без макросов и внешних объектов.
- Длинное имя TNEF записано Unicode-свойством PR_ATTACH_LONG_FILENAME.
- HMEFMessage подтвердил RTF, русское имя и содержимое вложения.
- OpenSSL создал новый самоподписанный сертификат test@example.com; никаких реальных ключей.
- `openssl cms -sign -binary` — отделённая подпись; `-nodetach` — непрозрачная.
- `openssl cms -encrypt -aes256` — зашифрованная оболочка.
- `cms -verify -noverify` и `cms -decrypt` вернули исходную MIME-сущность побайтно.
- Закрытый ключ и отдельный сертификат не включены в набор: они в игнорируемом каталоге.
- Новые пакеты, документы или образцы не скачивались.

## Проверка

- [код] Каждый EML разобран Python email; выбранная часть найдена по пути.
- [код] Каждая строка body.textContains присутствует в декодированном теле.
- [код] Для HTML берётся видимый текст без style/script; сеть не используется.
- [код] Размер и SHA-256 вычислены по декодированным байтам каждой части.
- [код] Для MIME-контейнера используются байты сериализованной сущности, правило описано в README.
- [код] Для PNG проверены сигнатура, CRC и распаковка IDAT общим валидатором.
- [код] M09 и M10 дополнительно прочитаны независимой библиотекой POI 5.4.1.
- [код] M12/M13/M15 проверены OpenSSL; cmsValid=true.
- [код] Проверены CRLF и ограничения размера для всех 20 сообщений.
- [код] Манифест включает каждый файл и хэш; собственный хэш манифеста не задаётся.
- [код] JSON проверяется повторным чтением.
- Рабочий Mime4j-конвейер не запускался: он ещё не реализован.

## Спорные места

- M05: CP1251 — известное происхождение байтов, но автоматическая коррекция метки не решена.
- M06: выбран plain, HTML не добавляется к тому же тексту повторно.
- M07: ссылки должны сохраняться, URL нельзя загружать.
- M08: Content-ID сопоставляются с двумя inline-частями; третья картинка — вложение.
- M12: независимая проверка подписи генератором не включает проверку подписи в будущий продукт.
- M14: допустим ограниченный мягкий разбор; повреждённую часть можно пропустить с предупреждением.
- M15: непрозрачная подпись распознаётся отдельно от шифрования.
- M16: display-имена сохраняются, запись на диск только по внутренним именам.
- M18: правильная строка — ground truth; допустим отказ от восстановления при явном дефекте.
- M19: расширение дочерних частей прекращается перед шестым переходом контейнера.
- После ZIP/MSG/TNEF пути логические: они не выданы сервером IMAP.

## Что не удалось сделать

- Независимый разбор всего набора Mime4j пока не выполнялся.
- Нельзя считать поведение Python email нормативной реализацией всех пограничных случаев.
- Синтетический MSG минимален; реальная палитра MAPI-свойств значительно шире.
- S/MIME не моделирует доверие цепочке сертификатов или отзыв.
- Вредоносные большие архивы не создавались: лимиты проверяются малым M19.

## Что ещё стоит изучить

- При реализации подключить набор к потоковому Mime4j и общему счётчику производных байтов.
- Независимо сравнить предупреждения о неверном charset и повреждённых границах.

## Источники

- docs/research/RT-09-mime-attachments.md, В2/В4/В5; прочитано 2026-10-25.
- Microsoft MS-OXTNEF — https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-oxtnef/ (прочитано 2026-10-25).
- Локальные исходники Apache POI 5.4.1: hmef, hsmf, poifs, кэш Gradle; прочитано 2026-10-25.
- OpenSSL 3.0.3, установленная команда `openssl cms -help`; проверено 2026-10-25.
