# RT-02 — Клиент EWS и NTLM в Java

- Дата: 2026-10-24.
- Исполнитель: Codex, исследовательская сессия RT-02.
- Бриф: [RT-02-ews-java-client.md](../research-tasks/RT-02-ews-java-client.md).
- Метод: документация, исходники и метаданные публичных репозиториев; без сборки и входа в ящики.

## Кратко

1. [код] Оригинальный `OfficeDev/ews-java-api` архивирован 2024-06-18;
   артефакт 2.0 опубликован 2015-09-09, последний коммит `master` — 2018-07-25. [1–3]
2. [код] Форки с изменениями в 2025–2026 есть: `eischet`, `jcetech`, `SpanningCloudApps`.
   Подтверждённой проверки на Java 25 у рассмотренных форков не найдено. [7–9]
3. [док][код] `HttpURLConnection` умеет NTLM; `java.net.http.HttpClient` в JDK 25
   автоматически обрабатывает только Basic. Одинаковый `Authenticator` не уравнивает их. [15–19]
4. [код] На Unix JDK использует Java-реализацию с NTLMv2 по умолчанию;
   на Windows — системный механизм, версия зависит от политики Windows. [16–18]
5. [док][код] Apache HttpClient отключил NTLM по умолчанию и объявил устаревшим с 5.3.
   Удаления нет в проверенных 5.6.5 и 5.7-beta1; схему нужно регистрировать явно. [20–23]
6. [код][предположение] Рекомендую тонкий SOAP-клиент на StAX + HttpClient 5 classic
   с явной регистрацией NTLM; резерв — тот же SOAP-слой + буферизованный `HttpsURLConnection`. [19–24]
7. [код] DavMail 7.1.0 выпущен 2026-10-17: собственный SOAP/StAX поверх HttpClient 4,
   выбор между JCIFS и собственной схемой с привязкой к TLS. Это образец, а не готовый SDK. [33–38]
8. [предположение] На примерно 10 операций нужен порядок 15–25 рабочих дней с проверками.
   Успешный NTLM из .NET пока не доказывает успех Java: различаются домен, NTLMv2 и привязка к TLS.

## Ответы на вопросы

### В1. Состояние OfficeDev/ews-java-api и Java 17/21/25

[код] Последняя версия в Maven Central — `com.microsoft.ews-java-api:ews-java-api:2.0`;
каталог артефакта датирован 2015-09-09. GitHub Releases пуст: это дата публикации Maven,
а не дата GitHub Release. Последний коммит основной ветки —
`33235f37d97871f17c496526aad0b8c458f904c3`, 2018-07-25, изменение README. [2, 3]

[код] README объявляет режим сохранения существующих возможностей и прекращение новых функций
с 2018-07-19; репозиторий архивирован 2024-06-18. Считать это действующей поддержкой SDK нельзя.
Ограничения Exchange Online из README сами по себе не запрещают EWS нашего on-premise сервера. [1]

[код] В `pom.xml` основной ветки указаны HttpClient/HttpCore 4.4.1 и старые зависимости;
это не современная матрица совместимости. В исходниках есть два существенных импорта:
`ServiceRequestBase` → `javax.xml.ws.http.HTTPException`,
`TimeChange` → `javax.xml.bind.DatatypeConverter`. [4, 5]

[док] JDK 11 удалил JAX-WS, JAXB и их инструменты из поставки JDK.
Следовательно, на 17/21/25 эти Java EE API нужно добавлять отдельно либо заменять использование.
Обычный модуль `java.xml`, включая StAX, не удалён. [6]

| JDK | Что подтверждено | Вывод для исходного SDK |
|---|---|---|
| 17 | [код] Старые импорты остаются; `eischet` отдельно перенёс сборку на 17. [5, 7] | [предположение] Запуск возможен после исправления зависимостей; официальной гарантии нет. |
| 21 | [док][код] Удалённые Java EE API не возвращены; проверенного отчёта проекта о 21 нет. [5, 6] | [предположение] Нужны те же исправления и отдельная проверка поведения. |
| 25 | [код] Подтверждения CI/релиза SDK для 25 не найдено. [1, 4, 7–9] | [предположение] Не считать совместимым до сборки и проверки нужных операций. |

[код][вторично] Issue [#773](https://github.com/OfficeDev/ews-java-api/issues/773),
2023-12-20, прямо называет эти два класса и сообщает о локальной миграции автора на Jakarta.
Это свидетельство проблемы и наличия локального исправления, а не выпущенный Jakarta-релиз. [5]

[предположение] Формулировка issue о невозможности работы в Jakarta-приложении слишком общая:
для автономного Boot-приложения старые `javax` API могут сосуществовать с Jakarta в разных пакетах.
Однако Jakarta XML Binding 3+/XML Web Services 3+ не предоставляют старые классы `javax`;
одна замена координат зависимости импорты не исправляет. Основание — конкретные импорты [5].

[код][вторично] Issue [#692](https://github.com/OfficeDev/ews-java-api/issues/692),
2019-03-01, обсуждает создание общественного форка. Это не подтверждение, что появился
единый поддерживаемый преемник. Проверенные кандидаты перечислены в В2. [10]

### В2. Другие Java-библиотеки и форки с активностью в 2025–2026

[код] `eischet/ews-java-api` — наиболее содержательная из рассмотренных модернизаций:
отдельные модули API и транспорта Apache 4/5, Jakarta JAXB, Java 17;
пакеты переименованы в `com.eischet.ews`. README описывает 2.3.2 от 2025-12-29,
но одновременно говорит об отсутствии готовых пакетов. В POM остаётся HttpClient 5.2.1. [7]

[предположение] Этот форк полезен при необходимости широкого объектного API EWS.
Нельзя просто обновить его HttpClient и предположить, что NTLM останется включён:
между 5.2.1 и современной веткой изменились настройки аутентификации, см. В4.

[код] `jcetech/ews-java-api`, ветка `dev`: последний коммит 2025-08-17;
POM 2.2.0, целевая Java 17 и зависимости Jakarta, но HttpClient/HttpCore всё ещё 4.4.1.
GitHub Release не найден. Номер в POM не доказывает публикацию артефакта. [8]

[код] `SpanningCloudApps/ews-java-api`: последний коммит 2026-06-09,
добавляет стандартную конфигурацию запросов; POM использует HttpClient 4.5.13.
GitHub Release не найден; свежий коммит подтверждает точечную работу, не поддержку Java 25. [9]

[док] Коммерческий `Independentsoft JWebServices for Exchange` предлагает версию 2.2.150,
Exchange 2013, MIME, синхронизацию, поиск, папки и вложения. Страница заявляет Java 5+,
но не даёт даты выпуска, отдельной гарантии Java 25 или проверяемых деталей NTLMv2. [11]

[док] TuxCare публикует `2.0-tuxcare.1` от 2026-05-11 как исправление безопасности
для `ews-java-api` (PF-2697, CVE-2025-48924). Это пример сопровождения старого артефакта
поставщиком, а не доказательство нового открытого SDK или совместимости с Boot 4. [12]

[предположение] Итого: ответ «живых Java-вариантов совсем нет» неверен.
Есть небольшие форки, коммерческий продукт и активно выпускаемый DavMail; общего открытого
преемника с подтверждёнными Java 25, NTLMv2 и всем нашим набором операций не установлено.

### В3. Собственный тонкий SOAP-клиент, схемы и трудоёмкость

[док][код] EWS допускает прямой SOAP/XML; DavMail показывает этот подход без генерации SDK.
Для нашей задачи транспорт и XML-обработку можно разделить: NTLM не зависит от способа
представления XML. Версию запросов задавать явно: `Exchange2013_SP1`. [13, 14, 34]

| Способ | Что придётся сделать | Оценка применимости |
|---|---|---|
| XML-шаблоны + StAX | Запись конверта, пространств имён, параметров; потоковый разбор только нужных полей | [предположение] Основной кандидат: небольшой явный контракт, управляемая память. Пример подхода — [34]. |
| Jackson XML | Собственные классы для конверта, заголовка, запросов и ответов; отдельная обработка ошибок | [док][предположение] Удобно для простых DTO, но это сопоставление объектов с XML, не готовый SOAP/EWS SDK; есть ограничения XML-модели. [29] |
| JAXB из XSD, JAX-WS из WSDL | Внешние генераторы, Jakarta API/runtime, исправление imports и при необходимости WSDL service | [док][предположение] Типизация по полной схеме полезна для широкого API; для 10 операций обычно избыточна. [6, 13] |
| Spring Web Services | `WebServiceTemplate`, SOAP message factory, `Source` либо JAXB, настроенный HTTP sender | [док][предположение] Хорошая оболочка для SOAP, но EWS и NTLM она не реализует; настройка транспорта остаётся нашей. [30] |

[док] `Services.wsdl` описывает операции, `messages.xsd` — сообщения,
`types.xsd` — общие типы и заголовки. Microsoft предупреждает: WSDL может не иметь
`wsdl:service`, поэтому некоторым генераторам требуется добавить его локально. [13]

[код] Получить схемы без входа на наш сервер действительно можно:

- `php-ews/src/assets/` публично содержит согласованный по расположению комплект
  [`services.wsdl`](https://raw.githubusercontent.com/jamesiarmes/php-ews/master/src/assets/services.wsdl),
  [`messages.xsd`](https://raw.githubusercontent.com/jamesiarmes/php-ews/master/src/assets/messages.xsd),
  [`types.xsd`](https://raw.githubusercontent.com/jamesiarmes/php-ews/master/src/assets/types.xsd).
  В `types.xsd` есть `Exchange2013_SP1`. Это копии в стороннем проекте,
  а не заверенная точная схема нашего CU23. [31]
- [док] Microsoft Open Specifications `[MS-OXWSCDATA]`, приложение Full WSDL,
  даёт общие схемы и ссылки на их определения. Сам этот протокол WSDL не определяет;
  для операций нужны соответствующие спецификации, например `[MS-OXWSSRCH]`.
  Это первичный источник контракта, не готовый единый комплект `Services.wsdl`. [32]
- [код] Genivia публикует все три файла для примера gSOAP, но проверенные XSD имеют
  версию `Exchange2010_SP2`. Для генерации полного клиента Exchange 2013 их брать нельзя. [40]
- [код] В дереве `OfficeDev/ews-java-api/master` WSDL/XSD не найдены:
  библиотека содержит ручную реализацию XML, а не обещанный набор схем для генератора. [3, 4]

[предположение] Перед будущей генерацией закрепить ревизию схем, проверить все относительные
imports/includes, добавить XML catalog для внешних схем и отдельно сопоставить нужные операции
с Exchange 2013. Наличие значения версии в XSD само по себе не проверяет весь контракт. [13, 14, 31]

[предположение] Для тонкого клиента предлагается следующий объём, без кода в этой сессии:

1. Общий SOAP 1.1 конверт, `SOAPAction`, UTF-8, заголовок версии, безопасная запись параметров.
2. Разбор `SOAP Fault` независимо от HTTP-статуса и `ResponseClass`/`ResponseCode`
   отдельно для каждого результата пакетного запроса; ограничение размеров XML и Base64.
3. Десять операций из соседнего брифа:
   `GetFolder`, `FindFolder`, `SyncFolderHierarchy`, `FindItem`, `SyncFolderItems`,
   `GetItem`, `GetAttachment`, `FindConversation`, `GetConversationItems`, `ConvertId`.
   Поиск через `Restriction`/`QueryString` — варианты `FindItem`, не новые операции.
4. Потоковое получение MIME и файлов, обработка изменений и страниц;
   локальные примеры ответов для ошибок, кириллицы и больших вложений.

| Работа | Оценка, рабочих дней одного разработчика |
|---|---|
| Транспорт NTLM, TLS, тайм-ауты, изоляция ящиков | [предположение] 3–5 |
| Конверт, безопасный XML, ошибки и повторяемое тело запроса | [предположение] 2–3 |
| Папки, списки и синхронизация | [предположение] 3–5 |
| MIME, вложения, разговоры и ConvertId | [предположение] 3–5 |
| Проверки на сохранённых ответах и проверка пользователем на Exchange | [предположение] 4–7 |
| Всего для StAX-варианта | [предположение] 15–25; сложная привязка NTLM к TLS может увеличить срок |

[предположение] Это оценка реализации источника EWS, не всего MCP-сервера, индекса или MIME-парсера.
Jackson XML либо Spring-WS могут сократить шаблонную работу при знакомстве с ними,
но не устранят разбор особенностей EWS; JAXB добавляет риск отдельной отладки генератора.

### В4. NTLM и NTLMv2 в HTTP-клиентах

[док][код] Важно различать NTLMv2 и NTLM2 Session: это разные режимы.
Наличие слова NTLM в API не доказывает ни NTLMv2, ни поддержку привязки к TLS. [18, 24]

**`HttpURLConnection` / `HttpsURLConnection`.** [док] Работает через
`java.net.Authenticator`; домен может отсутствовать, входить в имя либо задаваться
`http.auth.ntlm.domain`. Прозрачный вход текущей учётной записью Windows управляется
`jdk.http.ntlm.transparentAuth`; он нам не нужен, оставляем `disabled`. [15]

[код] На Unix реализация читает `ntlm.version` и создаёт `com.sun.security.ntlm.Client`;
его режим по умолчанию — `LMv2/NTLMv2`, можно явно выбрать `NTLMv2`.
Это проверка HTTP-исходников, а не перенос вывода из документации SASL. [16, 18]

[код][предположение] На Windows используется `NTLMAuthSequence`, системная аутентификация;
свойство `ntlm.version` из Unix-ветки не является переключателем Windows NTLMv2.
Фактический режим проверяется политикой Windows и согласованием с сервером.
Windows-код имеет запасной домен `domain`, если свойство не задано: для bare login
нужно отдельно проверить явно пустой домен, не подставлять `example.com` автоматически. [17]

[док] Ограничение для SOAP POST: `setFixedLengthStreamingMode` и
`setChunkedStreamingMode` запрещают автоматическую обработку аутентификации и перенаправлений;
вместо неё возможен `HttpRetryException`. Поэтому резервный вариант буферизует небольшой
XML-запрос внутри URLConnection; потоковое чтение большого ответа этому не мешает. [19]

**`java.net.http.HttpClient`.** [код] В JDK 25 `AuthenticationFilter` выбирает Basic;
NTLM-handshake не реализован. Простое добавление `Authenticator` не поможет,
а ручной Type1/Type3 поверх непрозрачного пула соединений не является готовым решением. [39]

**Apache HttpClient 5 classic.** [док][код] С 5.3 NTLM deprecated и выключен по умолчанию;
в 5.6.5 стандартный реестр схем не содержит NTLM. При этом `NTLMScheme`,
`NTLMSchemeFactory`, `NTCredentials` существуют; `NTLMScheme` есть и в 5.7-beta1.
Указывать «удалён в 5.4/5.5» неправильно. [20–23]

[код][предположение] Подключение: собственный `Registry<AuthSchemeFactory>` с
`NTLMSchemeFactory`, `BasicCredentialsProvider` с `NTCredentials`, область только
целевого HTTPS-хоста/порта, разрешена только схема NTLM, повторяемое тело XML.
Для `user` передавать пустой домен отдельно; уточнение формата — проверка пользователем. [21, 22]

[код] Движок умеет NTLMv2, но также содержит более старые режимы и переходы между ними.
Поддержка NTLMv2 не равна политике «только NTLMv2». В исследованных исходниках схема
вызывает обычный `generateType3Msg` без передачи сертификата TLS, хотя в движке есть
код channel binding. Поэтому поддержку Extended Protection нельзя обещать по одному движку. [21, 24]

**Apache HttpClient 4.5.14.** [док] NTLMv1, NTLMv2 и NTLM2 Session поддерживаются
из коробки с 4.1; использовать `NTCredentials` и `CredentialsProvider`.
Ветка 4.x получает только существенные исправления и исправления безопасности;
официальный сайт не объявляет весь classic HttpClient 4.5 EOL. [25, 26]

**OkHttp.** [код] Есть интерфейс `Authenticator` для ответов 401/407,
но готового NTLM-движка он не предоставляет. Сторонний пример —
`netcosports/Android_Ntlm:1.0`, `NTLMAuthenticator` и собственный `NTLMEngineImpl`.
Движок содержит NTLMv2, но зависит от `android.util.Base64`: это не готовая Java 25 библиотека. [27, 28]

**JCIFS-ng.** [код] Предоставляет `Type1Message`/`Type2Message`/`Type3Message`,
NTLMv2 и контекст настроек; стандартный `lanManCompatibility` — 3.
Можно написать адаптер `NTLMEngine` для Apache или `Authenticator` для OkHttp,
но поддержание соединения, TLS, тайм-ауты и повторы обеспечивает HTTP-клиент. [41]

[предположение] Для JCIFS-ng нельзя копировать старые примеры JCIFS 1.x без адаптации:
API и контекст отличаются. Само наличие генератора Type3 также не доказывает
правильную привязку HTTPS-аутентификации к каналу.

**Spring `RestClient`.** [док][код] Поддержка определяется `ClientHttpRequestFactory`:
Apache factory использует HttpClient 5; JDK factory — новый JDK HttpClient;
Simple factory — URLConnection. В Spring 7 встроенная интеграция OkHttp3 удалена. [42, 43]

[код][предположение] Для Simple factory требуется особая осторожность: Spring 7 отправляет
тело в fixed-length/chunked streaming mode. Поэтому стандартная фабрика не решает NTLM SOAP POST
даже при настроенном `Authenticator`. Нужен прямой буферизованный URLConnection либо своя фабрика;
внешняя буферизующая оболочка не гарантирует отключения streaming во внутреннем запросе. [19, 44]

[код][предположение] В Apache factory повторяемость зависит от `Body.repeatable()`:
стандартное значение интерфейса — false. Для RestClient проверять конкретный способ передачи
XML, а первоначальную реализацию проще делать напрямую через повторяемую Apache entity. [48]

### В5. Как устроен DavMail и что переиспользовать

[код] Последний описанный релиз — 7.1.0 от 2026-10-17.
Исходники `master` читались 2026-10-24; это состояние ветки, не идентичность релизному архиву. [33]

| Файл в `src/java/davmail/` | Что изучать |
|---|---|
| `exchange/ews/EwsExchangeSession.java` | [код] Координация EWS, получение папок, MIME и вложений; заявлена совместимость с Exchange 2013. [35] |
| `exchange/ews/EWSMethod.java` | [код] SOAP-конверт, HTTP POST, повторяемое тело, StAX, gzip и ошибки EWS. [34] |
| `http/HttpClientAdapter.java` | [код] HttpClient 4, cookie store, соединения, `NTCredentials`, реестр схем, тайм-ауты. [36] |
| `http/JCIFSNTLMSchemeFactory.java`, `http/JCIFSEngine.java` | [код] Ветвь JCIFS: адаптация Type1/Type2/Type3 к Apache `NTLMEngine`. [37] |
| `http/DavMailNTLMSchemeFactory.java`, `http/DavMailNTLMScheme.java` | [код] Альтернативная схема, извлекающая сертификат TLS из текущего соединения. [38] |
| `http/DavMailNTLMEngineImpl.java` | [код] NTLMv2, добавление данных привязки к каналу и MIC в ответ. [38] |

[код] История важна: 6.3.0 (2025-02-26) удалил JCIFS ради собственной реализации с channel binding;
6.4.0 (2025-08-31) вернул JCIFS; 6.5.0 (2025-10-23) вернул
`davmail.enableJcifs=true` по умолчанию. Текущий POM содержит JCIFS **1.3.19**, не JCIFS-ng.
Следовательно, описание «DavMail больше не использует JCIFS» устарело. [33, 36, 37]

[код][предположение] В текущем коде `enableJcifs=false` выбирает собственную схему с TLS-привязкой.
Issue #352 показывает, почему это нужно: при включённом Extended Protection прежний
handshake завершался 401, хотя браузер входил успешно. Это не доказательство такой настройки у нас. [38, 45]

[код][предположение] Переиспользуемы архитектурные идеи: ограниченный набор SOAP-операций,
повторяемый запрос, потоковый ответ, изоляция HTTP-сессии. Файлы имеют заголовки GPL-2.0-or-later;
при переносе кода нужно учитывать эти условия. Встраивать весь шлюз как SDK не рекомендую:
он связан с Settings, протоколами, UI и своим журналированием, а также содержит операции записи. [34–38]

### В6. TLS, соединения, тайм-ауты, сжатие и cookies

| Область | Проверенный факт и следствие для реализации |
|---|---|
| TLS | [предположение] Начать с системного trust store, проверки имени хоста и TLS 1.2/1.3; доступность TLS 1.2 на нашем сервере отдельно не проверялась. Не переносить разрешение TLS 1.0/1.1 из списка протоколов DavMail как рекомендуемую настройку. [36] |
| Extended Protection | [код][предположение] Проверять передачу сертификата в NTLM-движок, а не только наличие NTLMv2. .NET и чистая Java могут вести себя по-разному. [21, 24, 38, 45] |
| Соединения | [док] NTLM аутентифицирует соединение; нужен один сокет на протяжении Type1 → Type2 → Type3. Аутентифицированные соединения нельзя передавать другому пользователю. [25] |
| Несколько ящиков | [предположение] Отдельный HTTP-клиент/пул, credentials и cookies на запись connections; начинать с одного одновременного запроса на ящик. URLConnection — отдельный экземпляр Authenticator на ящик. [19, 25] |
| Повторяемость POST | [док][код] В ходе NTLM запрос может отправляться повторно. Буферизовать небольшой XML; не передавать одноразовый InputStream как тело. DavMail явно объявляет SOAP entity повторяемой. [19, 34] |
| Тайм-ауты | [предположение] Задать ожидание свободного соединения, установление TCP/TLS, чтение и общий предел операции; начальная оценка 10–15 с на соединение, 120 с на чтение. У DavMail defaults 10/120 с — ориентир, не лимиты Exchange. [36] |
| Повторы | [предположение] Разделить ожидаемые 401 внутри handshake и окончательный отказ. Не перебирать логины/методы автоматически; сетевые повторы и задержки EWS ограничить. [21, 34] |
| gzip | [код][предположение] DavMail посылает `Accept-Encoding: gzip` и читает gzip. При настройке Apache/JDK выбрать один уровень распаковки; ограничивать также распакованный объём и потоково декодировать Base64. [34] |
| Cookies | [код][предположение] Сохранять `Set-Cookie` по правилам host/path/secure отдельно для ящика. `X-BackEndCookie` считать непрозрачным, не конструировать и не журналировать; cookie не заменяет NTLM-состояние сокета. [36, 46] |
| Привязка к серверу | [док] Для групп подписок Microsoft описывает `X-AnchorMailbox`, `X-PreferServerAffinity` и **`X-BackEndOverrideCookie`**; это не то же имя, что `X-BackEndCookie`. Для обычного SyncFolderItems не следует объявлять этот механизм подписок обязательным. [46] |
| Перенаправления | [предположение] URL фиксирован конфигурацией; запретить автоматическую передачу учётных данных другому хосту. Это проще, чем Autodiscover, и соответствует исходным условиям задачи. |
| XML и память | [док][предположение] Запретить DTD/внешние сущности и сетевое разрешение схем; задать пределы глубины и размера. MIME не собирать через DOM всего ответа. Основание — руководство JAXP и модель больших EWS-ответов. [34, 47] |

## Сравнительные таблицы

В первой таблице трудоёмкость — [предположение], совместимость с Java 25 без запуска — «проверить».
Транспортные варианты сведены во второй таблице, чтобы не смешивать SOAP SDK и HTTP.

| Вариант клиента EWS | Поддерживается (дата последнего релиза) | Java 25 | NTLM | Объём работы | Риски |
|---|---|---|---|---|---|
| OfficeDev 2.0 | [код] Архив; Maven 2015-09-09 [1–3] | Проверить, нужны внешние старые Java EE API | [код] HttpClient 4 [4] | Средний: восстановление зависимостей | Старый SDK, широкий API записи |
| eischet | [код] Версия в коде 2.3.2, 2025-12-29; опубликованный пакет не подтверждён [7] | Проверить; база 17 | [код] Apache 4/5; JDK-модуль с проблемами [7] | Средний | Переименованные пакеты, HTTP 5.2.1, обновления NTLM |
| jcetech | [код] Коммит 2025-08-17; релиз не найден [8] | Проверить; target 17 | [код] HTTP 4.4.1 | Средний/высокий | Старая транспортная зависимость |
| SpanningCloudApps | [код] Коммит 2026-06-09; релиз не найден [9] | Проверить | [код] HTTP 4.5.13 | Средний/высокий | Точечные исправления, нет обещания Java 25 |
| TuxCare 2.0-tuxcare.1 | [док] Исправление от 2026-05-11 [12] | Не установлено | Не установлено для этой сборки | Зависит от поставщика | Не подтверждены доступность и полный контракт |
| Independentsoft JWebServices | [док] 2.2.150; дата не опубликована [11] | Проверить у поставщика | NTLMv2 не установлено | Низкий/средний после проверки | Коммерческие условия, закрытая реализация |
| Свой SOAP: шаблоны + StAX | Поддерживаем сами | [док] StAX остаётся в JDK [6] | По транспорту | 15–25 дней | Свои разбор ошибок, синхронизация и ограничения |
| Свой SOAP: Jackson XML | [код] Живой XML-модуль [29] | Проверить выбранную версию | По транспорту | Сопоставимо со StAX | Моделирование SOAP и ограничений XML вручную |
| Свой SOAP: JAXB/JAX-WS | [док] Внешние API и генераторы [6, 13] | Проверить комплект | По транспорту | Средний/высокий | Сложность схем и генерации, объём классов |
| Spring Web Services | [док] Документация 5.0.2, 2026-06-10 [30] | Проверить комплект с Boot 4 | Через sender/HTTP-клиент | Средний | Не готовый EWS SDK, память SOAP message factory |
| DavMail как библиотека/образец | [код] 7.1.0, 2026-10-17 [33] | Не установлено для встраивания | [код] JCIFS или собственный движок [36–38] | Высокий при встраивании | Связность шлюза, GPL, операции записи |

| HTTP-клиент | NTLM есть? | NTLMv2? | Как подключить | Статус (устарел / удалён / жив) | Источник |
|---|---|---|---|---|---|
| HttpURLConnection, Unix | [код] Да | [код] Да; default LMv2/NTLMv2 | Authenticator; при необходимости ntlm.version=NTLMv2; POST без output streaming | [док] Публичный API JDK 25 | [15, 16, 18, 19] |
| HttpURLConnection, Windows | [код] Да | [предположение] Через системный механизм; зависит от политики Windows | Authenticator, transparentAuth=disabled, проверить пустой домен | [док] Публичный API JDK 25 | [15, 17, 19] |
| java.net.http.HttpClient | [код] Нет встроенного | Нет встроенного | Authenticator обрабатывает Basic; NTLM потребовал бы отдельной реализации | [код] Живой клиент без NTLM | [39] |
| Apache HttpClient 5.6.5 classic | [код] Да, явно включить | [код] Да, не обязательно только v2 | NTLMSchemeFactory + NTCredentials + CredentialsProvider + реестр и предпочтение NTLM | [док][код] Клиент жив; NTLM deprecated с 5.3, не удалён и в 5.7-beta1 | [20–24] |
| Apache HttpClient 4.5.14 classic | [док] Да | [док] Да с 4.1 | NTCredentials + CredentialsProvider | [док] Только существенные исправления и безопасность | [25, 26] |
| OkHttp | [код] Только через расширение | [код] Есть в движке Android_Ntlm; для Java адаптировать | Authenticator + движок, например JCIFS-ng; HTTP/1.1, проверить один сокет | [код][предположение] Основной API жив; готовый адаптер Java 25 не подтверждён | [27, 28, 41] |
| JCIFS-ng как движок | [код] Генератор сообщений, не HTTP-транспорт | [код] Да | CIFSContext + Type1/2/3, свой адаптер HTTP | [код] Последний проверенный коммит 2025-11-18; HTTP-интеграцию поддерживаем сами | [41] |
| RestClient + JdkClientHttpRequestFactory | [код] Нет | Нет | Фабрика не добавляет NTLM к JDK HttpClient | [док] Живой Spring API | [39, 42] |
| RestClient + HttpComponentsClientHttpRequestFactory | [док][код] Да при настройке Apache 5 | [код] Как у Apache 5 | Передать заранее настроенный HttpClient 5; проверить повторяемость тела | [док] Живой Spring API, NTLM транспорта устарел | [20–24, 42] |
| RestClient + SimpleClientHttpRequestFactory | [код] Для SOAP POST стандартная фабрика мешает handshake | У движка JDK есть, у этой связки проверить | Своя фабрика без output streaming либо прямой URLConnection | [код] Живой API с ограничением потокового POST | [19, 44] |
| RestClient + Apache 4 / OkHttp / JCIFS-ng | Встроенной фабрики Spring 7 нет | Зависит от адаптера | Своя ClientHttpRequestFactory; JCIFS-ng подключать к нижнему HTTP-слою | [док][предположение] Интеграцию сопровождаем сами; OkHttp3 factory удалена в 7.0 | [42, 43] |

## Рекомендация

[предположение] **Основной вариант: собственный SOAP/StAX + Apache HttpClient 5 classic**,
с явно зарегистрированным NTLM и отдельной HTTP-сессией на ящик. Он даёт необходимый контроль
соединений; интеграция с Spring RestClient возможна после проверки повторяемости тела,
см. В4. SOAP-контракт ограничен десятью операциями.
Компромисс существенный: NTLM в Apache больше не поддерживается разработчиками.
Закреплять проверенную версию и проверять этот участок при каждом обновлении. [20–24, 42, 48]

[предположение] **Резерв: тот же SOAP/StAX + прямой буферизованный HttpsURLConnection**,
с отдельным `Authenticator` и явными учётными данными. На целевой Windows это независимый
от Apache путь через системную реализацию NTLM; обязательны проверки bare login/пустого домена,
NTLMv2, отсутствия потокового POST и повторного использования соединения. Это не обещание,
что системный механизм решит Extended Protection без дополнительных изменений. [15–19]

[предположение] Если важнее готовая широкая модель EWS, следующий кандидат — форк `eischet`
с аудитом зависимостей. Если пользователь отдельно подтвердит Basic по HTTPS, тот же SOAP-слой
можно перевести на современный поддерживаемый транспорт без NTLM; пока это условный путь.
Решение остаётся за пользователем; документы плана этой сессией не изменялись.

## Что не удалось проверить

- [предположение] Работа любого выбранного сочетания на Java 25 + Boot 4 и нашем Exchange:
  код не собирался, запросы не выполнялись. Пользователь проверяет один read-only GetFolder,
  затем MIME, вложение, синхронизацию и повторное соединение; без перебора логинов.
- [предположение] Политика NTLMv2, Extended Protection и TLS на сервере неизвестна.
  Пользователь/администратор проверяет конфигурацию и безопасную диагностику выбранного клиента;
  доказательство «.NET вошёл» не заменяет этой проверки.
- [код] Последние релизы форков и официальный CI Java 21/25 не установлены.
  Метаданные GitHub API и исходники проверены; позднее анонимный API вернул ограничение частоты.
  Указанные даты коммитов не выданы за даты опубликованных пакетов. [7–9]
- [док] Для Independentsoft не установлены дата 2.2.150, NTLMv2 и Java 25;
  запросить эти сведения у поставщика до выбора. Для TuxCare отдельно выяснить условия доступа. [11, 12]
- [предположение] Генерация полного JAXB/JAX-WS клиента из публичных копий XSD не выполнялась;
  совместимость imports, полноту операций и память на больших ответах ещё проверить. [13, 31]
- [код] Схемы Genivia проверены как Exchange2010_SP2; точное совпадение публичных копий
  php-ews со схемой установленного CU23 не подтверждено. [31, 40]

## Что ещё стоит изучить

- [предположение] Ошибки, ограничения операций и восстановление состояния синхронизации —
  отдельное исследование [RT-03](../research-tasks/RT-03-ews-operations.md).
- [предположение] Потоковый разбор MIME и вложенных писем —
  [RT-09](../research-tasks/RT-09-mime-attachments.md); здесь только граница EWS → MIME.

## Источники

Все источники ниже прочитаны **2026-10-24**. Для страниц без даты публикации указано
«без даты»; даты коммитов и версий не считаются гарантией поддержки.
Исходники, которые веб-просмотр не выдавал целиком, прочитаны анонимным HTTP GET в память.

1. [OfficeDev/ews-java-api: README и статус архива](https://github.com/OfficeDev/ews-java-api) — архив 2024-06-18, заявление 2018-07-19.
2. [Maven Central, каталог 2.0](https://repo.maven.apache.org/maven2/com/microsoft/ews-java-api/ews-java-api/2.0/) — публикация 2015-09-09; [GitHub Releases](https://github.com/OfficeDev/ews-java-api/releases) — без релизов.
3. [Последний коммит OfficeDev](https://github.com/OfficeDev/ews-java-api/commit/33235f37d97871f17c496526aad0b8c458f904c3) — 2018-07-25; [API дерева](https://api.github.com/repos/OfficeDev/ews-java-api/git/trees/master?recursive=1) — без даты.
4. [OfficeDev pom.xml](https://raw.githubusercontent.com/OfficeDev/ews-java-api/master/pom.xml) — основная ветка, без даты.
5. [Issue #773](https://github.com/OfficeDev/ews-java-api/issues/773) — 2023-12-20; [ServiceRequestBase.java](https://raw.githubusercontent.com/OfficeDev/ews-java-api/master/src/main/java/microsoft/exchange/webservices/data/core/request/ServiceRequestBase.java), [TimeChange.java](https://raw.githubusercontent.com/OfficeDev/ews-java-api/master/src/main/java/microsoft/exchange/webservices/data/property/complex/TimeChange.java) — основная ветка, без даты.
6. [Oracle JDK 11 Release Notes: JEP 320 и удалённые модули](https://www.oracle.com/java/technologies/javase/11-relnote-issues.html) — изменения JDK 11, 2018; [JAXP JDK 25](https://docs.oracle.com/en/java/javase/25/security/java-api-xml-processing-jaxp-security-guide.html) — издание JDK 25.
7. [eischet/ews-java-api](https://github.com/eischet/ews-java-api), [readme.md](https://raw.githubusercontent.com/eischet/ews-java-api/main/readme.md), [pom.xml](https://raw.githubusercontent.com/eischet/ews-java-api/main/pom.xml) — 2.3.2 от 2025-12-29; [API коммитов](https://api.github.com/repos/eischet/ews-java-api/commits?per_page=2).
8. [jcetech/ews-java-api](https://github.com/jcetech/ews-java-api), [pom.xml dev](https://raw.githubusercontent.com/jcetech/ews-java-api/dev/pom.xml), [API коммитов](https://api.github.com/repos/jcetech/ews-java-api/commits?per_page=2) — последний коммит 2025-08-17.
9. [SpanningCloudApps/ews-java-api](https://github.com/SpanningCloudApps/ews-java-api), [pom.xml](https://raw.githubusercontent.com/SpanningCloudApps/ews-java-api/master/pom.xml), [API коммитов](https://api.github.com/repos/SpanningCloudApps/ews-java-api/commits?per_page=2) — последний коммит 2026-06-09.
10. [OfficeDev issue #692: EWS Community Project](https://github.com/OfficeDev/ews-java-api/issues/692) — 2019-03-01.
11. [Independentsoft JWebServices for Exchange](https://independentsoft.de/jwebservices/index.html) — 2.2.150, дата версии не указана.
12. [TuxCare PF-2697, ews-java-api 2.0-tuxcare.1](https://tuxcare.com/cve-tracker/fixes/details/pf-2697/) — 2026-05-11.
13. [Microsoft: EWS XML elements](https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/ews-xml-elements-in-exchange) — 2023-03-29.
14. [Microsoft: EWS schema versions](https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/ews-schema-versions-in-exchange) — дата публикации в проверенной выдержке не установлена.
15. [Oracle Java Networking, JDK 25](https://docs.oracle.com/en/java/javase/25/core/java-networking.html) — издание JDK 25.
16. [OpenJDK 25 GA: Unix NTLMAuthentication.java](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.base/unix/classes/sun/net/www/protocol/http/ntlm/NTLMAuthentication.java) — тег jdk-25-ga, 2025.
17. [OpenJDK 25 GA: Windows NTLMAuthentication.java](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.base/windows/classes/sun/net/www/protocol/http/ntlm/NTLMAuthentication.java) — тег jdk-25-ga, 2025.
18. [OpenJDK 25 GA: com.sun.security.ntlm.Client](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.base/share/classes/com/sun/security/ntlm/Client.java) — тег jdk-25-ga, 2025.
19. [Oracle HttpURLConnection, JDK 25](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/HttpURLConnection.html) — издание JDK 25; setAuthenticator и streaming modes.
20. [Apache HttpComponents News](https://hc.apache.org/news.html) — объявление 5.2 от 2022-11-10, 5.3 от 2023-12-07; [дистрибутивы](https://downloads.apache.org/httpcomponents/httpclient/binary/) — 5.6.5 от 2026-10-10, 5.7-beta1 от 2026-10-22.
21. [Apache NTLMScheme 5.6.5](https://raw.githubusercontent.com/apache/httpcomponents-client/rel/v5.6.5/httpclient5/src/main/java/org/apache/hc/client5/http/impl/auth/NTLMScheme.java) — тег 5.6.5.
22. [Apache HttpClientBuilder 5.6.5](https://raw.githubusercontent.com/apache/httpcomponents-client/rel/v5.6.5/httpclient5/src/main/java/org/apache/hc/client5/http/impl/classic/HttpClientBuilder.java) — тег 5.6.5, реестр схем и методы настройки.
23. [Apache NTLMScheme 5.7-beta1](https://raw.githubusercontent.com/apache/httpcomponents-client/rel/v5.7-beta1/httpclient5/src/main/java/org/apache/hc/client5/http/impl/auth/NTLMScheme.java) — тег 5.7-beta1.
24. [Apache NTLMEngineImpl 5.6.5](https://raw.githubusercontent.com/apache/httpcomponents-client/rel/v5.6.5/httpclient5/src/main/java/org/apache/hc/client5/http/impl/auth/NTLMEngineImpl.java) — тег 5.6.5.
25. [Apache HttpClient 4.5 Tutorial, §4.7](https://hc.apache.org/httpcomponents-client-4.5.x/current/tutorial/pdf/httpclient-tutorial.pdf) — руководство ветки 4.5, версия PDF без отдельно установленной даты.
26. [Apache Project Status](https://hc.apache.org/status.html) — без даты; различает HttpClient 4.5, HttpCore classic/NIO и HttpAsyncClient.
27. [OkHttp Authenticator.kt](https://raw.githubusercontent.com/square/okhttp/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Authenticator.kt) — основная ветка, без даты.
28. [Android_Ntlm README](https://github.com/netcosports/Android_Ntlm), [NTLMAuthenticator.java](https://raw.githubusercontent.com/netcosports/Android_Ntlm/master/library/src/main/java/com/netcosports/ntlm/NTLMAuthenticator.java), [NTLMEngineImpl.java](https://raw.githubusercontent.com/netcosports/Android_Ntlm/master/library/src/main/java/com/netcosports/ntlm/NTLMEngineImpl.java) — версия 1.0, дата не установлена.
29. [FasterXML jackson-dataformat-xml](https://github.com/FasterXML/jackson-dataformat-xml) — ветка 3.x, без даты.
30. [Spring Web Services Reference](https://docs.spring.io/spring-ws/docs/current/reference/html/) — 5.0.2, 2026-06-10, раздел Client-side API.
31. [php-ews services.wsdl](https://raw.githubusercontent.com/jamesiarmes/php-ews/master/src/assets/services.wsdl), [messages.xsd](https://raw.githubusercontent.com/jamesiarmes/php-ews/master/src/assets/messages.xsd), [types.xsd](https://raw.githubusercontent.com/jamesiarmes/php-ews/master/src/assets/types.xsd) — master, дата копий не установлена.
32. [Microsoft MS-OXWSCDATA, Full WSDL](https://learn.microsoft.com/th-th/openspecs/exchange_server_protocols/ms-oxwscdata/9328dc18-72e8-4f7c-bb9d-c6f9d5f0217c) — английский текст спецификации, обновление 2020-10-14; [MS-OXWSSRCH, редакция 2010](https://officeprotocoldoc.z19.web.core.windows.net/files/MS-OXWSSRCH/%5BMS-OXWSSRCH%5D-100210.pdf) — 2010-02-05, пример отдельного WSDL операций.
33. [DavMail RELEASE-NOTES.md](https://raw.githubusercontent.com/mguessan/davmail/master/RELEASE-NOTES.md) — 7.1.0 от 2026-10-17 и история 6.3–6.5.
34. [DavMail EWSMethod.java](https://raw.githubusercontent.com/mguessan/davmail/master/src/java/davmail/exchange/ews/EWSMethod.java) — master, без отдельной даты.
35. [DavMail EwsExchangeSession.java](https://raw.githubusercontent.com/mguessan/davmail/master/src/java/davmail/exchange/ews/EwsExchangeSession.java) — master, без отдельной даты.
36. [DavMail HttpClientAdapter.java](https://raw.githubusercontent.com/mguessan/davmail/master/src/java/davmail/http/HttpClientAdapter.java), [pom.xml](https://raw.githubusercontent.com/mguessan/davmail/master/pom.xml) — master, без отдельной даты; исходники прочитаны напрямую, поисковая копия отставала.
37. [DavMail JCIFSNTLMSchemeFactory.java](https://raw.githubusercontent.com/mguessan/davmail/master/src/java/davmail/http/JCIFSNTLMSchemeFactory.java), [JCIFSEngine.java](https://raw.githubusercontent.com/mguessan/davmail/master/src/java/davmail/http/JCIFSEngine.java) — master, без отдельной даты.
38. [DavMailNTLMScheme.java](https://raw.githubusercontent.com/mguessan/davmail/master/src/java/davmail/http/DavMailNTLMScheme.java), [DavMailNTLMEngineImpl.java](https://raw.githubusercontent.com/mguessan/davmail/master/src/java/davmail/http/DavMailNTLMEngineImpl.java) — master, без отдельной даты.
39. [OpenJDK 25 GA: AuthenticationFilter.java](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.net.http/share/classes/jdk/internal/net/http/AuthenticationFilter.java) — тег jdk-25-ga, 2025.
40. [Genivia gSOAP EWS example](https://www.genivia.com/examples/ews/index.html), [Services.wsdl](https://www.genivia.com/examples/ews/Services.wsdl), [messages.xsd](https://www.genivia.com/examples/ews/messages.xsd), [types.xsd](https://www.genivia.com/examples/ews/types.xsd) — дата не указана, XSD Exchange2010_SP2.
41. [JCIFS-ng](https://github.com/AgNO3/jcifs-ng), [Type3Message.java](https://raw.githubusercontent.com/AgNO3/jcifs-ng/master/src/main/java/jcifs/ntlmssp/Type3Message.java), [BaseConfiguration.java](https://raw.githubusercontent.com/AgNO3/jcifs-ng/master/src/main/java/jcifs/config/BaseConfiguration.java) — последний коммит master по API 2025-11-18, `8df7abd7fbba839f7654e028853c5903be5e17d0`.
42. [Spring Framework: REST Clients](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html) — проверена документация 7.0.10, без даты публикации страницы.
43. [Spring Framework 7.0 Release Notes: Other removals](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes/373c55a01235603a2a2ce8e9a5a67f5de6b6a70f) — версия документа с описанием удаления OkHttp3 support.
44. [Spring 7.0.10 SimpleClientHttpRequest.java](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.10/spring-web/src/main/java/org/springframework/http/client/SimpleClientHttpRequest.java) — тег v7.0.10, строки установки streaming mode.
45. [DavMail issue #352, Extended Protection](https://github.com/mguessan/davmail/issues/352) — 2024-05-10, закрыт; отчёт пользователя, механизм сверялся с кодом [38].
46. [Microsoft: Maintain affinity between subscriptions and Mailbox server](https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/how-to-maintain-affinity-between-group-of-subscriptions-and-mailbox-server) — дата публикации в проверенной выдержке не установлена.
47. [Oracle JAXP Security Guide, JDK 25](https://docs.oracle.com/en/java/javase/25/security/java-api-xml-processing-jaxp-security-guide.html) — издание JDK 25.
48. [Spring 7.0.10 HttpComponentsClientHttpRequest.java](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.10/spring-web/src/main/java/org/springframework/http/client/HttpComponentsClientHttpRequest.java), [StreamingHttpOutputMessage.java](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.10/spring-web/src/main/java/org/springframework/http/StreamingHttpOutputMessage.java) — тег v7.0.10, контракт повторяемости тела.
