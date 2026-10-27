# Исследования

Результаты исследований 2026-10-24. Это справочный материал на момент исследования: решения
по ним — в [decisions.md](../decisions.md), текущее устройство — в `docs/*-agent.md`. Часть
выводов пересмотрена: SQLite, Mime4j, фильтр до индекса. Ссылки на разделы `plan.md` внутри
файлов относятся к его тогдашней структуре.

| Файл | О чём | Пригодится для |
|---|---|---|
| [RT-01](RT-01-mail-mcp-servers.md) | Существующие почтовые MCP-серверы | Д15 |
| [RT-02](RT-02-ews-java-client.md) | Клиент EWS и NTLM в Java | Д23 |
| [RT-03](RT-03-ews-operations.md) | Операции EWS, разрешённый список, троттлинг | Д20 |
| [RT-04](RT-04-imap-gmail-mailru.md) | IMAP Gmail и Mail.ru, вход, расширения | Д7 |
| [RT-05](RT-05-sibling-code-reuse.md) | Что брать из соседних проектов | — |
| [RT-06](RT-06-search-engine.md) | Движок поиска и морфология | словоформы (`docs/search.md`), Д13 |
| [RT-07](RT-07-embeddings.md) | Модели эмбеддингов | Д13 |
| [RT-08](RT-08-redaction-detectors.md) | Детекторы секретов | фильтр ответа (`docs/redaction-agent.md`) |
| [RT-09](RT-09-mime-attachments.md) | Разбор MIME и вложений | вложения и контейнеры (`docs/mime-agent.md`) |
| [RT-10](RT-10-mcp-resources.md) | MCP-ресурсы в клиентах | Д14 (отклонено) |
| [RT-11](RT-11-synthetic-mime.md), [RT-12](RT-12-redaction-samples.md), [RT-13](RT-13-search-queries.md) | Как созданы наборы `testdata/mime`, `testdata/redaction`, `testdata/search` | тесты разбора и фильтра |
