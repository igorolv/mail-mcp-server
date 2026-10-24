# RT-03 — операции EWS для Exchange 2013

- Дата: 2026-10-24
- Исполнитель: Codex / исследовательская сессия RT-03
- Бриф: docs/research-tasks/RT-03-ews-operations.md
- Область: Exchange 2013 CU23, SOAP EWS, `RequestServerVersion="Exchange2013_SP1"`.
- Метод: официальная документация Microsoft и RFC; запросы к почтовому серверу не выполнялись,
  `.local/` не открывался. Контекст проекта прочитан в `docs/plan.md` и `docs/research.md`;
  эти документы не изменены.

## Кратко

1. [док] Все десять исследованных операций доступны в Exchange 2013; `GetConversationItems`
   появился именно в 2013, `FindConversation` — в 2010 SP1. [30: версии операций][30]
2. [док] Дерево папок можно получить через `FindFolder Traversal="Deep"` и затем поддерживать
   через `SyncFolderHierarchy`. Изменения только `TotalCount`/`UnreadCount` последняя операция
   не сообщает: счётчики надо освежать отдельно. [2: FindFolder][2], [3: SyncFolderHierarchy][3]
3. [док] `FindItem` не заменяет `GetItem`: полного тела и списков получателей в выдаче нет;
   для потоковых свойств действует усечение. [6: FindItem][6]
4. [док] `SyncFolderItems` работает отдельно для каждой папки, возвращает максимум 512 изменений
   и непрозрачный `SyncState`; окончание обхода задаёт `IncludesLastItemInRange`.
   [9: SyncFolderItems][9], [11: синхронизация][11]
5. [док] `GetItem` получает письмо без установки `IsRead`; официальный пример с MIME возвращает
   `IsRead=false`. Установка флага и связанные уведомления — отдельное действие записи.
   [14: GetItem для письма][14], [16: IsRead][16]
6. [док] `MimeContent` — base64 от MIME-байтов. Exchange строит MIME из хранилища, поэтому это
   не гарантия побайтового оригинала SMTP и не полный снимок всех EWS-свойств.
   [15: MimeContent][15], [17: экспорт][17]
7. [док] `GetAttachment` получает файлы и вложенные items; MIME вложенного письма запрашивается
   через `AttachmentShape/IncludeMimeContent`. [18: GetAttachment][18], [20: IncludeMimeContent][20]
8. [док] AQS `attachment:report` ищет в имени и индексированном содержимом вложения; полнота
   зависит от Exchange Search и поддержанного формата. [24: QueryString][24], [27: форматы][27]
9. [док] `ItemId` может измениться при перемещении, `ChangeKey` обозначает версию;
   `ConvertId` меняет формат идентификатора. [28: идентификаторы][28], [29: ConvertId][29]
10. [предположение; рекомендация] Для постоянного ключа нужен локальный UUID в пространстве
    соединения, с отдельными текущими EWS-идентификаторами. `InternetMessageId` и хэш полезны
    для сопоставления, но не доказывают тождество экземпляров; подробности — В8.

## Ответы на вопросы

### Общая форма SOAP-примеров

[док] Ниже показано содержимое `s:Body`. Для каждого примера используется одна оболочка
SOAP 1.1 с указанными пространствами имён и версией схемы. Значения `ITEM_ID`, `FOLDER_ID`,
`ATTACHMENT_ID`, `CONVERSATION_ID`, `SYNC_STATE` — обозначения, которые заменяются значениями
из ответа сервера; это не рабочие идентификаторы. [30: схема и версии EWS][30]

```xml
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
            xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
  <s:Header>
    <t:RequestServerVersion Version="Exchange2013_SP1"/>
  </s:Header>
  <s:Body><!-- Insert one operation here. --></s:Body>
</s:Envelope>
```

[док] Во всех ответах следует проверять `ResponseClass`, `ResponseCode`, а при ошибке —
`MessageText`/`MessageXml`; HTTP 200 сам по себе не означает успех. Пакет может содержать
разные результаты для разных IDs. [31: проверка результатов][31]

### В1. Папки: GetFolder, FindFolder, SyncFolderHierarchy

**GetFolder.** [док] Получает свойства конкретных папок, в том числе по distinguished ID.
`Default` возвращает ID, имя и счётчики; для построения дерева явно добавим родителя и класс.
Ответ: `Folders`, конкретный тип папки, `FolderId/@Id`, `@ChangeKey`, `ParentFolderId`,
`DisplayName`, `FolderClass`, `TotalCount`, `UnreadCount`, `ChildFolderCount`.
`FolderIds` позволяет перечислить несколько папок; постраничного обхода у `GetFolder` нет.
В 2013 `AllProperties` не включает права папки без явного запроса `PermissionSet`.
[1: GetFolder][1]

```xml
<m:GetFolder>
  <m:FolderShape>
    <t:BaseShape>Default</t:BaseShape>
    <t:AdditionalProperties>
      <t:FieldURI FieldURI="folder:ParentFolderId"/>
      <t:FieldURI FieldURI="folder:FolderClass"/>
    </t:AdditionalProperties>
  </m:FolderShape>
  <m:FolderIds><t:DistinguishedFolderId Id="inbox"/></m:FolderIds>
</m:GetFolder>
```

**FindFolder.** [док] `Deep` возвращает потомков указанной папки. Ответ содержит
`RootFolder/@IncludesLastItemInRange`, `@IndexedPagingOffset`, `@TotalItemsInView`, `Folders`.
Следующую страницу запрашивают по возвращённому смещению; запрошенный размер не гарантирован.
Сортировки у `FindFolder` нет; deep traversal для public folders не поддержан.
Ограничение потоковых свойств — 512 байт / первые 255 Unicode-символов.
[2: FindFolder][2], [7: paging][7]

```xml
<m:FindFolder Traversal="Deep">
  <m:FolderShape>
    <t:BaseShape>Default</t:BaseShape>
    <t:AdditionalProperties>
      <t:FieldURI FieldURI="folder:ParentFolderId"/>
      <t:FieldURI FieldURI="folder:FolderClass"/>
    </t:AdditionalProperties>
  </m:FolderShape>
  <m:IndexedPageFolderView MaxEntriesReturned="100" Offset="0" BasePoint="Beginning"/>
  <m:ParentFolderIds><t:DistinguishedFolderId Id="msgfolderroot"/></m:ParentFolderIds>
</m:FindFolder>
```

[док] Distinguished IDs не зависят от локализованного имени: `inbox`, `sentitems`, `drafts`,
`deleteditems`, `junkemail`, `outbox`; `msgfolderroot` — корень дерева сообщений, `root` —
корень всего ящика. Есть также `calendar`, `contacts`, `tasks`, `searchfolders`,
`recoverableitemsroot`, `archivemsgfolderroot`. Последние не означают, что архив включён или
всякая служебная папка доступна. [4: DistinguishedFolderId][4]

[предположение; рекомендация] «Полное дерево» для MVP означает всё доступное пользовательское
дерево под `msgfolderroot`, включая корень, полученный отдельным `GetFolder`. Это не обещание
обхода public folders, Recoverable Items и отдельного archive mailbox. Сохранить каркас дерева
всех типов, затем выбирать папки почты на клиенте — так родитель исключённого типа не потеряется.

[док] Почтовый класс — `IPF.Note`, календарь — `IPF.Appointment`, контакты — `IPF.Contact`,
задачи — `IPF.Task`. Допускаются расширения классов (`IPF.Note.Custom`); `SearchFolder` тоже
может иметь `IPF.Note`, но представляет виртуальную выборку чужих items. Неизвестный префикс
Exchange трактует как generic mail folder. [5: типы папок][5]

[предположение; рекомендация] Синхронизировать физические mail folders, распознавая `IPF.Note`
и его подклассы; исключать `SearchFolder` независимо от класса, чтобы не создавать дубли.
Для отсутствующего/неизвестного класса оставить явный статус «проверить», учитывать XML-тип
и разрешать ручной выбор. `TotalCount` — счётчик items папки, а не обещание числа только обычных
писем; при фильтрации items число проиндексированных писем может отличаться. Основание —
семантика типов и счётчиков [1], [5].

**SyncFolderHierarchy.** [док] Первоначальный запрос без `SyncState` получает иерархию,
последующие — `Changes/Create|Update|Delete`. Ответ: новый `SyncState`,
`IncludesLastFolderInRange`, свойства изменённых папок. Повторять с новым состоянием, пока
`IncludesLastFolderInRange=true`. Отдельного `MaxChangesReturned` в этой операции нет;
численный максимум страницы в использованной справке не указан. Изменения одних счётчиков
не порождают изменение иерархии. [3: SyncFolderHierarchy][3], [33: форма запроса][33]

```xml
<m:SyncFolderHierarchy>
  <m:FolderShape><t:BaseShape>Default</t:BaseShape></m:FolderShape>
  <m:SyncFolderId><t:DistinguishedFolderId Id="msgfolderroot"/></m:SyncFolderId>
</m:SyncFolderHierarchy>
```

[док] В последующие запросы после `SyncFolderId` добавляется
`<m:SyncState>SYNC_STATE</m:SyncState>`. Нельзя смешивать состояния разных корней или менять
между запросами «корень не задан» на `root`: это разные области синхронизации.
[12: ошибки синхронизации][12]

[предположение; рекомендация] Для новых/изменённых папок догружать класс и родителя через
`GetFolder`, если их нет в выбранной форме ответа; счётчики обновлять им же при показе списка
или периодически. Не полагаться на hierarchy delta для счётчиков.

### В2. Списки писем: FindItem и IndexedPageItemView

[док] `FindItem Traversal="Shallow"` перечисляет непосредственные items папки. Поддержаны
`SortOrder`, `IndexedPageItemView` и выбранные дополнительные свойства. В ответе:
`RootFolder/@IndexedPagingOffset`, `@IncludesLastItemInRange`, `@TotalItemsInView`,
`Items/Message/ItemId` и запрошенные доступные поля. [6: FindItem][6], [7: paging][7]

```xml
<m:FindItem Traversal="Shallow">
  <m:ItemShape>
    <t:BaseShape>IdOnly</t:BaseShape>
    <t:AdditionalProperties>
      <t:FieldURI FieldURI="item:Subject"/>
      <t:FieldURI FieldURI="item:DateTimeReceived"/>
      <t:FieldURI FieldURI="item:Size"/>
      <t:FieldURI FieldURI="item:HasAttachments"/>
      <t:FieldURI FieldURI="item:ItemClass"/>
      <t:FieldURI FieldURI="item:ConversationId"/>
      <t:FieldURI FieldURI="message:IsRead"/>
    </t:AdditionalProperties>
  </m:ItemShape>
  <m:IndexedPageItemView MaxEntriesReturned="100" Offset="0" BasePoint="Beginning"/>
  <m:SortOrder>
    <t:FieldOrder Order="Descending"><t:FieldURI FieldURI="item:DateTimeReceived"/></t:FieldOrder>
  </m:SortOrder>
  <m:ParentFolderIds><t:DistinguishedFolderId Id="inbox"/></m:ParentFolderIds>
</m:FindItem>
```

[док] Доступны метаданные, например тема, время, размер, importance, `IsRead`,
`HasAttachments`; это не полный объект письма. Справка ограничивает потоковые свойства 512
байтами / 255 Unicode-символами; тела и полных recipient lists нет, есть recipient summary.
Для `From`/`Sender` справка `FindItem` обещает имя, но не `EmailAddress`; SMTP-адреса следует
получать через `GetItem`. [6: ограничения FindItem][6], [25: пример набора полей][25]

[док] По умолчанию выдача без ограничения — до 1000 результатов за запрос; для AQS или
`Restriction` в Exchange 2013 — до 250. `EWSFindCountLimit` также учитывает совокупность
результатов одновременно выполняющихся поисков пользователя. Это не предел общего размера
папки. [8: throttling][8]

[док] `MaxEntriesReturned` — верхняя заявка, сервер может вернуть меньше. Следовать
`IndexedPagingOffset`, завершать по `IncludesLastItemInRange`, а не по сравнению количества
строк с размером страницы. Offset paging не создаёт snapshot: вставки и удаления способны
сдвигать страницы; Microsoft описывает проверку anchor item. [7: paging][7]

[предположение; рекомендация] Для UI взять 100 строк и дедупликацию ID; для полного наполнения
хранилища предпочесть `SyncFolderItems` с начальным пустым состоянием. Одной сортировки по
времени недостаточно для гарантированного обхода меняющейся папки: время может совпадать.

### В3. Инкрементальная синхронизация: SyncFolderItems

[док] Синхронизация идёт по одной папке. В начальном запросе `SyncState` отсутствует,
в продолжениях содержит ровно blob из предыдущего успешного ответа. `MaxChangesReturned`
допускает **1–512 включительно**. `NormalItems` исключает folder-associated information.
Сервер возвращает `SyncState`, `IncludesLastItemInRange`, `Changes`.
[9: SyncFolderItems][9], [10: MaxChangesReturned][10], [11: синхронизация][11]

```xml
<m:SyncFolderItems>
  <m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape>
  <m:SyncFolderId><t:FolderId Id="FOLDER_ID"/></m:SyncFolderId>
  <m:MaxChangesReturned>100</m:MaxChangesReturned>
  <m:SyncScope>NormalItems</m:SyncScope>
</m:SyncFolderItems>
```

[док] Для продолжения вставить `<m:SyncState>SYNC_STATE</m:SyncState>` перед
`MaxChangesReturned`. Повторять до `IncludesLastItemInRange=true`, затем сохранять состояние
для следующего цикла. `IdOnly` уменьшает обращения к Exchange; MIME, `Body` и коллекцию
`Attachments` получать через `GetItem`. [9: SyncFolderItems][9], [11: best practices][11]

| Изменение | Что содержит / означает | Обработка в локальном store |
|---|---|---|
| `Create` | [док] Новый item с ID и выбранными полями | [предположение] Upsert locator; поставить скачивание в очередь |
| `Update` | [док] Изменившийся item | [предположение] Обновить метаданные; не считать каждое изменение причиной повторного MIME |
| `Delete` | [док] ID исчезнувшего из папки item | [предположение] Пометить локальный экземпляр отсутствующим; не удалять ничего на сервере |
| `ReadFlagChange` | [док] `ItemId` и новое `IsRead` | [предположение] Обновить только локальный флаг |

[док] Источники таблицы: SOAP-пример содержит именно `ReadFlagChange`;
обзор Microsoft [11] называет этот тип `ReadStateChange` для EWS — расхождение терминов.
Для XML следовать форме ответа операции, не названию в обзорном тексте.
[34: пример изменений SyncFolderItems][34], [11: обзор][11]

[док] При потерянном состоянии новый initial sync выполняется без `SyncState`.
`ErrorInvalidSyncStateData` требует пересинхронизации без него. Состояния hierarchy/items,
разных папок и разных областей невзаимозаменяемы. [12: ошибки][12], [13: ResponseCodeType][13]

[предположение; рекомендация] Хранить blob без разбора/усечения вместе с соединением,
папкой и scope. Применять страницу изменений и фиксировать новое состояние одной локальной
транзакцией либо вместе с надёжной очередью догрузки. После сбоя повторять идемпотентно;
не продвигать checkpoint поверх потерянной работы. При `ErrorSyncFolderNotFound` сначала
освежить иерархию, при `ErrorItemNotFound` во время догрузки повторно сверить delta.
Основание для разделения ошибок — [12], [13].

**Слишком старое состояние.** [док] Проверенные источники описывают invalid state и recovery,
но не задают гарантированный TTL для Exchange 2013. Утверждения «протухает через N дней»
или «никогда не протухает» здесь не подтверждены. [12], [13]
[предположение; рекомендация] При отказе сервера использовать тот же путь полной сверки:
построить новую локальную генерацию присутствия, завершить все страницы, только затем
пометить пропавшие locators. Не очищать рабочий индекс до успешного окончания initial sync.

### В4. Содержимое письма: GetItem с IncludeMimeContent

[док] `GetItem` получает выбранные свойства нескольких IDs в одном `ItemIds`; каждую запись
`GetItemResponseMessage` проверяют отдельно. В ответе нужны `Items/Message/ItemId`,
`MimeContent`, а также явно запрошенные `InternetMessageId`, `IsRead`, `Attachments`.
Коллекция `Attachments` здесь содержит метаданные, а не отдельные скачанные payloads.
[14: GetItem для письма][14], [19: получение вложений][19], [31: результаты][31]

```xml
<m:GetItem>
  <m:ItemShape>
    <t:BaseShape>IdOnly</t:BaseShape>
    <t:IncludeMimeContent>true</t:IncludeMimeContent>
    <t:AdditionalProperties>
      <t:FieldURI FieldURI="message:InternetMessageId"/>
      <t:FieldURI FieldURI="message:IsRead"/>
      <t:FieldURI FieldURI="item:Attachments"/>
    </t:AdditionalProperties>
  </m:ItemShape>
  <m:ItemIds><t:ItemId Id="ITEM_ID"/><t:ItemId Id="ITEM_ID_2"/></m:ItemIds>
</m:GetItem>
```

[док] `MimeContent` — XML base64Binary, атрибут `CharacterSet` не заменяет MIME-заголовки.
После снятия внешнего base64 получается поток для `.eml`; внутри письма ещё могут быть
собственные transfer encodings и разные charset. [15: уровни кодирования][15]
[предположение; рекомендация] Сохранять декодированные байты напрямую; текст извлекать MIME-
парсером, не преобразовывать весь поток через `new String(..., UTF_8)`.

**IsRead.** [док] Операция получает свойства, а не устанавливает флаг. В официальном
примере `GetItem` с `IncludeMimeContent=true` в ответе остаётся `IsRead=false`.
Таким образом, получение MIME само по себе не помечает письмо прочитанным. Изменение
`IsRead` является отдельной записью; при запрошенном read receipt установка `true` может
отправить уведомление. [14: пример GetItem][14], [16: IsRead][16]
[предположение; рекомендация] Не добавлять «восстановление непрочитанности» через `UpdateItem`:
оно само нарушает read-only. Проверку на конкретном CU пользователь сможет выполнить
повторным чтением `IsRead`; агент её не запускал.

**Размеры и цена.** [док] MIME-конверсия затратна и содержит ограниченный набор свойств;
Microsoft не рекомендует массовый частый MIME-экспорт. [17: экспорт][17]
[док] В Exchange 2013 документированы EWS `maxAllowedContentLength` и
`maxReceivedMessageSize` по 67 108 864 байта (64 MiB), с учётом base64 usable size меньше.
Это настройки принимаемых запросов/IIS/bindings, **не доказательство предела 48 MiB для
ответа `GetItem` или скачиваемого вложения**. Универсальный лимит MIME-ответа для CU23
в этих источниках не установлен. [32: размеры в Exchange 2013][32]

[док] Microsoft рекомендует при синхронизации `GetItem`-пакеты по 10 items.
[11: best practices][11]
[предположение; рекомендация] Для MIME начать с одного крупного письма или небольшого пакета
до 10 мелких; ограничивать суммарные байты и время локально. Не принимать количество 10 за
протокольный максимум. Повторно не скачивать MIME только из-за смены read flag; метаданные
версии и состояния хранить отдельно от `.eml`.

### В5. Вложения: GetAttachment

[док] Сначала получить `item:Attachments` через `GetItem`, затем вызвать `GetAttachment`
с одним или несколькими attachment IDs. Файл приходит как `FileAttachment/Content` (base64),
вложенный item — как `ItemAttachment/Message` или другой тип item. Важны `AttachmentId`,
`Name`, `ContentType`, `ContentId`, `IsInline`, `Size`; значения метаданных могут зависеть
от исходного вложения. [18: GetAttachment][18], [19: процесс получения][19]

Файл, минимальный запрос [док; 18]:

```xml
<m:GetAttachment>
  <m:AttachmentShape/>
  <m:AttachmentIds><t:AttachmentId Id="ATTACHMENT_ID"/></m:AttachmentIds>
</m:GetAttachment>
```

MIME вложенного письма [док; 20]:

```xml
<m:GetAttachment>
  <m:AttachmentShape><t:IncludeMimeContent>true</t:IncludeMimeContent></m:AttachmentShape>
  <m:AttachmentIds><t:AttachmentId Id="ATTACHMENT_ID"/></m:AttachmentIds>
</m:GetAttachment>
```

[док] Во втором случае `ItemAttachment/Message/MimeContent` надо декодировать как `.eml`.
Пустой `AttachmentShape` MIME item attachment не запрашивает. Пагинации или byte-range
в форме `GetAttachment` нет: получаем полное вложение, а не его очередной кусок.
[18: GetAttachment][18], [20: IncludeMimeContent][20]

[док] Вложения могут вкладываться рекурсивно. `HasAttachments` не сигнализирует об одних
inline-вложениях; для HTML с `cid:` надо читать коллекцию. Файл `.msg` относится к file
attachment и не превращается автоматически в EWS item attachment. [21: типы вложений][21]

[предположение; рекомендация] Для больших вложений — один ID на запрос, потоковое base64-
декодирование в ограниченный локальный файл, лимиты размера и глубины распаковки. Численный
предел скачивания для CU23 — **не установлен**; настройки 64 MiB из В4 нельзя механически
считать пределом файла. [32] описывает транспортные настройки, а не range download.
Если полный MIME родителя уже сохранён, повторное скачивание file attachment может быть
излишним; выбор между MIME-частью и EWS attachment требует сопоставления метаданных.

### В6. Цепочки: ConversationId, FindConversation, GetConversationItems

[док] `item:ConversationId` связывает item с разговором; его можно запросить среди
метаданных. `FindConversation` перечисляет разговоры папки, доступен с 2010 SP1.
`GetConversationItems` появился в 2013 и поддерживает primary/archive mailbox.
[22: FindConversation][22], [23: GetConversationItems][23], [30: версии][30]

```xml
<m:FindConversation>
  <m:IndexedPageItemView MaxEntriesReturned="25" Offset="0" BasePoint="Beginning"/>
  <m:ParentFolderId><t:DistinguishedFolderId Id="inbox"/></m:ParentFolderId>
</m:FindConversation>
```

[док] Ответ: `Conversations/Conversation`, `ConversationId`, `ConversationTopic`,
`LastDeliveryTime`, `MessageCount`, `UnreadCount`, `ItemIds`; варианты `Global*` описывают
разговор шире текущей папки. Есть индексированный view; образец ответа и справка
`FindConversationResponse` не показывают `RootFolder` с курсором как у `FindItem`.
Точный потолок страницы CU23 в этих источниках не указан. [22: операция][22],
[36: форма ответа][36]
[предположение; рекомендация] Не переносить парсер paging от `FindItem` без проверки;
использовать небольшие страницы и проверить завершение обхода на CU23. Для полноты локального
индекса исходить из folder/item sync, а не из перечисления conversations.

```xml
<m:GetConversationItems>
  <m:ItemShape>
    <t:BaseShape>IdOnly</t:BaseShape>
    <t:AdditionalProperties><t:FieldURI FieldURI="item:Subject"/></t:AdditionalProperties>
  </m:ItemShape>
  <m:MaxItemsToReturn>100</m:MaxItemsToReturn>
  <m:SortOrder>TreeOrderAscending</m:SortOrder>
  <m:MailboxScope>PrimaryOnly</m:MailboxScope>
  <m:Conversations>
    <t:Conversation><t:ConversationId Id="CONVERSATION_ID"/></t:Conversation>
  </m:Conversations>
</m:GetConversationItems>
```

[док] Ответ: `ConversationId`, собственный `SyncState`, `ConversationNodes/ConversationNode`
с `InternetMessageId`, `ParentInternetMessageId` и `Items`. Можно исключить папки через
`FoldersToIgnore`; передача conversation sync state ограничивает выдачу новыми/изменёнными
items. Это другое состояние, чем у `SyncFolderItems`.
[23: GetConversationItems][23]
[док] `MaxItemsToReturn` ограничивает размер выдачи, но его справка не устанавливает
численный default/max; 100 в примере — выбранный размер, не подтверждённый максимум.
[35: MaxItemsToReturn][35]
[предположение; рекомендация] Не считать ограниченный ответ полной историей и не использовать
conversation sync state как offset-курсор для всей старой цепочки. При необходимости полноту
цепочки обеспечивать локальными проиндексированными items по conversation ID, догружая MIME
через `GetItem`. Между разными соединениями conversation IDs не объединять автоматически.

### В7. Поиск: Restriction против QueryString (AQS)

[док] `Restriction` задаёт сравнения полей, `And`/`Or`/`Not`, `Exists`, `Contains`, диапазоны
и extended properties; `Contains` управляет case/containment. Это предпочтительный путь
для точного поиска по конкретным свойствам. [26: search filters][26]

```xml
<m:FindItem Traversal="Shallow">
  <m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape>
  <m:IndexedPageItemView MaxEntriesReturned="100" Offset="0" BasePoint="Beginning"/>
  <m:Restriction>
    <t:IsEqualTo>
      <t:FieldURI FieldURI="message:IsRead"/>
      <t:FieldURIOrConstant><t:Constant Value="false"/></t:FieldURIOrConstant>
    </t:IsEqualTo>
  </m:Restriction>
  <m:ParentFolderIds><t:DistinguishedFolderId Id="inbox"/></m:ParentFolderIds>
</m:FindItem>
```

[док] AQS ищет по content index: без имени поля — по индексированным свойствам, с именем —
например `subject:`, `body:`, `from:`, `attachment:`. Произвольные custom properties в этот
индекс не входят. `QueryString` и `Restriction` в одном запросе взаимоисключаются.
В 2013 `QueryString` получил тип `QueryStringType` с дополнительными необязательными
атрибутами; сам AQS существовал до 2013. [24: QueryString][24], [25: AQS][25]

```xml
<m:FindItem Traversal="Shallow">
  <m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape>
  <m:IndexedPageItemView MaxEntriesReturned="100" Offset="0" BasePoint="Beginning"/>
  <m:ParentFolderIds><t:DistinguishedFolderId Id="inbox"/></m:ParentFolderIds>
  <m:QueryString>attachment:report</m:QueryString>
</m:FindItem>
```

[док] `attachment:` охватывает имя и тело файла, а не только название. Однако Exchange Search
индексирует лишь поддержанные форматы; в Exchange 2013 обработка идёт в transport pipeline
или вскоре после создания/доставки. Это не численный SLA задержки. [24: attachment][24],
[27: поддержанные форматы][27], [37: Exchange Search 2013][37]

[док] AQS использует регистронезависимое сопоставление, по умолчанию prefix matching;
кавычки задают слово/фразу. Возможны ошибки `ErrorContentIndexingNotEnabled`,
`ErrorInvalidQueryString` и `ErrorQueryFilterTooLong`; последняя ограничивает restriction
255 выражениями. [24: синтаксис][24], [13: коды ошибок][13]
[предположение; рекомендация] Не обещать, что найдено содержимое всех PDF/архивов/сканов:
повреждение, шифрование, отсутствие текстового слоя и состояние индекса требуют локальной
проверки. Серверный поиск использовать как дополнительный канал, полный поиск вложений
строить по локально извлечённому тексту.

[док] Для обоих видов поиска применять ограничения страницы и paging из В2.
[предположение; рекомендация] Не создавать search folders ради read-only поиска:
их создание меняет ящик. Обход поддерева выполнять по известным физическим папкам;
`FindItem` с `Shallow` сам не рекурсивен. Основание — [6], [30].

### В8. Идентификаторы и ключ сообщения

| Значение | Проверенное поведение | Следствие для store |
|---|---|---|
| `ItemId/@Id` | [док] Непрозрачный, регистрозависимый; при move может смениться, поскольку move реализуется через copy/delete. [28] | [предположение] Текущий адрес экземпляра, не постоянный ключ |
| Copy | [док] Копирование создаёт новый item; исходный остаётся. [30], [28] | [предположение] Два экземпляра нельзя сливать только по одинаковому содержимому |
| Sync IDs | [док] Sync возвращает ID текущих items и ID удалений; полноценного move-события среди четырёх типов delta нет. [9], [34] | [предположение] Межпапочный move наблюдать как исчезновение/появление; корреляция не всегда однозначна |
| `ChangeKey` | [док] Меняется при изменении item; используется для контроля версии. [28] | [предположение] Хранить отдельно; изменение не доказывает изменение MIME |
| `InternetMessageId` | [док] EWS Internet message identifier; RFC `Message-ID` описывает версию сообщения, а не его положение в ящике. [38], [39] | [предположение] Поле корреляции/дедупликации контента, не уникальный ключ экземпляра |
| `ConvertId` | [док] Конверсия между EwsId, EntryId/HexEntryId и другими форматами, доступна с 2007 SP1. [29] | [предположение] Не даёт обещания неизменяемости после move |

[док] RFC 5322 требует глобальную уникальность корректно сформированного `Message-ID`, но
его наличие для сообщения — `SHOULD`, а не универсальное `MUST`. Копии одного сообщения
не превращаются из-за копирования в разные версии сообщения. [39: RFC 5322, §3.6.4][39]
[предположение] В реальных данных надо допускать отсутствующий/некорректный/повторённый ID;
даже корректный ID может присутствовать в нескольких экземплярах и разных ящиках. Поэтому
`connection + InternetMessageId` не обеспечивает уникальность item.

[док] `ConvertId` принимает массив `SourceIds`, возвращает `AlternateId` с `Format`, `Id`,
`Mailbox`; пагинации нет. Операция проверяет формат SMTP-адреса, но не существование ящика.
Универсальный размер пакета для CU23 в справке не задан. [29: ConvertId][29]

```xml
<m:ConvertId DestinationFormat="EwsId">
  <m:SourceIds>
    <t:AlternateId Format="HexEntryId" Id="HEX_ENTRY_ID" Mailbox="user@example.com"/>
  </m:SourceIds>
</m:ConvertId>
```

**Рекомендация по ключу** [предположение, не принятое решение]: постоянный ключ записи
`(connectionId, localMessageUuid)`, где локальный UUID назначается экземпляру и не зависит
от сервера. Отдельно хранить:

- текущий locator `(connectionId, mailboxScope, ewsItemId)` с регистрозависимым сравнением,
  родительскую папку, `ChangeKey`, состояние присутствия и историю aliases;
- `InternetMessageId`, даты, отправителя, размер и fingerprint нормализованного содержимого
  для поиска кандидатов при перемещении;
- SHA-256 скачанных MIME-байтов для целостности/повторного использования blob, без требования
  стабильности этого хэша между повторными EWS-конверсиями.

[предположение; рекомендация] После delete/create переносить UUID на новый locator только
при достаточно однозначном сопоставлении в том же ящике; при нескольких одинаковых копиях
сохранять разные UUID и при необходимости связь «то же содержимое». Удаление locator не
должно уничтожать blob, если есть другие ссылки. Это сохраняет стабильность локальных URI
и не теряет дубли; компромисс — абсолютное распознавание move против copy+delete по этим
операциям не гарантируется. Основание: изменчивость IDs [28], семантика MIME [17] и
`Message-ID` [39].

### В9. Throttling, лимиты и повтор запросов

[док] Exchange 2013 использует budget/recharge и microdelays; администратор может переопределить
глобальную политику через Organization/Regular scopes. Значение по документации не доказывает
действующую политику конкретного пользователя. [40: workload management 2013][40]

| Параметр | Что применимо к Exchange 2013 | Статус значения / источник |
|---|---|---|
| `EWSMaxConcurrency` | [док] Одновременные запросы пользователя | **27** в версии-специфичной таблице EWS [8]; справка cmdlet пишет **10** без разделения версий [41]. Конфликт источников; проверить effective policy |
| `EWSPercentTimeInCAS` | [док] Параметр Exchange 2010 | Для 2013 неприменим как документированный процентный default; не переносить старые числа [41] |
| `EWSPercentTimeInAD` | [док] Параметр Exchange 2010 | То же; для 2013 не задаём процент [41] |
| `EWSPercentTimeInMailboxRPC` | [док] Параметр Exchange 2010 | То же; для 2013 не задаём процент [41] |
| `EwsMaxBurst`, `EwsRechargeRate`, `EwsCutoffBalance` | [док] Модель бюджета с Exchange 2013 | Численные defaults для CU23 здесь не установлены; effective policy проверяет администратор [40], [41] |
| `EWSFindCountLimit` | [док] Числа и учёт параллельных поисков — В2 | С 2013 не доступен для чтения/изменения через cmdlet [8] |
| `EWSMaxBatchSize` | [предположение] Публичного параметра с этим именем не найдено | В перечне параметров cmdlet [41] отсутствует; утверждать «default 100» нельзя |

[док] Справка [41] охватывает несколько версий и прямо помечает три `PercentTimeIn*`
как применимые только к 2010. Строка `Default value: None` в метаданных cmdlet не означает
«бюджет отключён» или действующий policy default. [41: New-ThrottlingPolicy][41]

[предположение; рекомендация] Рабочая гипотеза для 2013 — 27 по специализированной таблице,
но начинать с одного выполняемого запроса на соединение. Не планировать нагрузку исходя
из максимума: тот же пользователь может обращаться к EWS другими клиентами.

[док] EWS проверяет бюджет в пакетах по items; слишком большой пакет может не уложиться
в документированную минуту выполнения batch. [8: batch throttling][8]
[предположение; рекомендация] Размер пакета выбирать по байтам/латентности, а не искать
единственный «максимум items». Рекомендованные 10 при sync — ориентир, не `EWSMaxBatchSize`.
[11: sync best practices][11]

| Ошибка / ответ | Что делать |
|---|---|
| `ErrorServerBusy`, включая SOAP fault / inner error | [док] Выдержать `BackOffMilliseconds`, когда сервер его прислал [8] |
| `ErrorExceededConnectionCount` | [док] Превышена concurrency [13]; [предположение] уменьшить параллелизм, поставить запрос в очередь |
| `ErrorExceededFindCountLimit` | [док] Превышен find budget [13]; [предположение] уменьшить страницу и число одновременных поисков |
| HTTP 503 / 500 / 200 с EWS error | [док] Все могут сопровождать throttling; разбирать HTTP и SOAP [8], [31] |
| `ErrorInvalidSyncStateData` | [док] Полная синхронизация без состояния, не бесконечный retry [13] |
| `ErrorContentConversionFailed`, `ErrorItemCorrupt` | [док] Ошибка MIME/свойств [13]; [предположение] оставить явную ошибку контента, отдельно попробовать метаданные |
| `ErrorItemNotFound`, `ErrorAccessDenied`, 401/403 | [предположение] Не повторять автоматически вход; проверить права/конфигурацию или сверить locator с delta. Значения EWS ошибок — [13] |

[предположение; рекомендация] На transient failure повторять только неуспешную часть read-only
пакета. Остановить запросы затронутого соединения как минимум на серверный backoff; если его
нет — ограниченный exponential backoff с jitter, например 1, 2, 4, 8, 16, 32 секунды,
с общим deadline и возможностью отмены. Серверный backoff нельзя сокращать до клиентского
потолка. После нескольких неудач вернуть состояние «временно недоступно», сохранив checkpoint.
Это предлагаемые настройки клиента, не defaults Exchange; основание для прекращения потока
запросов при throttling — [11].

### В10. Безопасность режима только чтения

[док] `GetFolder`, `FindFolder`, `FindItem`, `GetItem`, `GetAttachment`, `FindConversation`,
`GetConversationItems` получают данные; `SyncFolderHierarchy`/`SyncFolderItems` сообщают
изменения клиенту; `ConvertId` преобразует ID. Эти операции не дают команды изменения
письма, папки или настроек. В частности, `Create`/`Update`/`Delete` в **ответе sync** —
описание уже произошедших изменений, не команда записать их обратно. Подтверждения по каждой
операции и ограничения собраны в сравнительной таблице ниже. [1], [2], [3], [6], [9], [14],
[18], [22], [23], [29]

[предположение; рекомендация] Использовать строгий allowlist этих SOAP operations;
не предоставлять модели произвольный SOAP. Имена методов SDK вроде `Load`/`Bind` проверять
по отправляемому wire request; UI-действие «открыть письмо» не должно вызывать дополнительную
запись. Эта рекомендация относится к будущей реализации, кода в этом исследовании нет.

Запрещённые в read-only операции [док: назначения в каталоге EWS][30]:

- items: `CreateItem` (включая draft, reply/forward, response objects и `SuppressReadReceipt`),
  `UpdateItem` (включая `IsRead`, флаги, категории), `DeleteItem`, `MoveItem`, `CopyItem`,
  `SendItem`, `ArchiveItem`, `MarkAllItemsAsRead`, `MarkAsJunk`;
- вложения и папки: `CreateAttachment`, `DeleteAttachment`, `CreateFolder`, `CreateFolderPath`,
  `CreateManagedFolder`, `UpdateFolder`, `DeleteFolder`, `EmptyFolder`, `MoveFolder`, `CopyFolder`;
- conversation actions и настройки: `ApplyConversationAction`, `UpdateInboxRules`,
  `SetUserOofSettings`, `AddDelegate`, `UpdateDelegate`, `RemoveDelegate`,
  `CreateUserConfiguration`, `UpdateUserConfiguration`, `DeleteUserConfiguration`, `UploadItems`.

[предположение; рекомендация] Список запрещённых примеров не заменяет allowlist: все остальные
операции, включая подписки и управление search folders, вне разрешённого набора MVP.
Возвращаемые в `ResponseObjects` возможности ответа не исполнять. Переключать прочитанность
для «проверки» и затем возвращать её обратно также нельзя.

## Сравнительные таблицы

Все строки относятся к `Exchange2013_SP1`. «Да» означает отсутствие изменения содержимого и
настроек ящика самой указанной операцией, а не отсутствие серверного учёта нагрузки.

| Операция | Зачем нам | Только чтение? | Лимиты | Особенности Exchange 2013 | Источник |
|---|---|---|---|---|---|
| `GetFolder` | Свойства и актуальные счётчики | [док] Да | Нет paging; численный batch max не установлен | `AllProperties` не возвращает права без явного запроса | [1] |
| `FindFolder` | Дерево папок | [док] Да | Paging; find budget; усечение streamable properties | Deep для private tree; не для public folders | [2], [7], [8] |
| `SyncFolderHierarchy` | Изменения дерева | [док] Да | `IncludesLastFolderInRange`; нет клиентского max changes | Изменения одних счётчиков не сообщаются | [3], [33] |
| `FindItem` | Список и серверный поиск | [док] Да | 1000 без filter / 250 с filter или AQS; paging | Нет полного тела/recipient lists; AQS поддержан | [6], [8], [24] |
| `SyncFolderItems` | Delta каждой физической папки | [док] Да | 1–512 изменений; цикл до last=true | Нет полноценного MIME; не использовать для search folders | [9], [10], [11] |
| `GetItem` | MIME и полные выбранные свойства | [док] Да, `IsRead` не устанавливает | Нет paging; MIME response max не установлен; рекомендованы малые пакеты | MIME-конверсия затратна; это не raw backup | [14], [17] |
| `GetAttachment` | Файлы и вложенные письма | [док] Да | Целое вложение; нет byte-range; download max не установлен | Item MIME требует `IncludeMimeContent` | [18], [20] |
| `FindConversation` | Список разговоров | [док] Да | Indexed view; численный max CU23 не установлен | С 2010 SP1; folder/global свойства различаются | [22], [36] |
| `GetConversationItems` | Узлы и items цепочки | [док] Да | `MaxItemsToReturn`; default/max не установлены | С 2013; отдельный conversation sync state | [23], [35] |
| `ConvertId` | Совместимость форматов IDs | [док] Да | Массив IDs; нет paging; численный max не установлен | Не гарантирует постоянный ID при move | [29], [28] |

## Рекомендация

[предположение; рекомендация, решение за пользователем] Для MVP: одно соединение —
одна очередь ограниченных read-only запросов. Получить дерево под `msgfolderroot`, выбрать
физические mail folders, для каждой выполнить initial `SyncFolderItems IdOnly`, затем
догрузить метаданные и MIME. Поддерживать отдельно hierarchy state, item state каждой папки
и при необходимости conversation state. Счётчики освежать через `GetFolder`.

[предположение; рекомендация] Использовать локальный UUID с изменяемым EWS locator по В8;
`InternetMessageId` и fingerprint оставить для корреляции. Это сложнее единственного
`ItemId` или `Message-ID`, зато позволяет хранить копии и переживать перемещения без обещания
невозможной стопроцентной корреляции.

[предположение; рекомендация] Сохранять `.eml` и EWS-метаданные раздельно. Первичную MIME-
загрузку дозировать; при повторных sync не скачивать MIME из-за read flag. Серверные AQS/
restriction запросы оставить для оперативного поиска и сверок; основной поиск вложений —
локальный индекс. Основание и компромиссы приведены в В2–В9; это предложения, не изменение
принятых решений проекта.

## Что не удалось проверить

- [предположение / требуется проверка] Effective throttling policy пользователя CU23,
  фактические максимумы пакетов и download response. Агент не входит в ящик. Администратор
  может **только прочитать** `Get-ThrottlingPolicyAssociation` и соответствующую
  `Get-ThrottlingPolicy`, учитывая наследование; отдельно сверить применимые EWS/IIS настройки.
  Число 27 не подставлять как гарантированную ёмкость.
- [док / пробел источников] Конфликт источников о concurrency описан в В9;
  документированный для CU23 `EWSMaxBatchSize` не найден. Проверка policy не заменяет
  адаптивную обработку throttling.
- [док / пробел источников] Гарантированный срок годности `SyncState` Exchange 2013 не найден.
  Recovery при invalid state подтверждён; TTL нельзя заимствовать у Graph или subscriptions.
- [док / пробел источников] Численные пределы страницы `SyncFolderHierarchy`,
  `FindConversation`, default/max `MaxItemsToReturn` и универсальный предел MIME/attachment
  **ответа** CU23 не установлены. При реализации проверить документацию схемы/клиентского
  SDK и затем ограниченные чтения, запускаемые пользователем. 64 MiB входящего транспорта
  и 512 изменений item sync не переносить на другие операции.
- [предположение / требуется проверка] Завершение paging `FindConversation` и полнота длинной
  цепочки при `MaxItemsToReturn` требуют отдельной проверки на CU23; до неё эти операции
  не использовать как единственный способ полного наполнения store.
- [док / без live-проверки] Read-only семантика `GetItem` и пример с `IsRead=false`
  подтверждены документацией; конкретный mailbox не проверялся. Пользователь при реализации
  может дважды получить `IsRead` вокруг MIME-чтения непрочитанного письма, без `UpdateItem`
  и без одновременного открытия этого письма в другом клиенте.
- [предположение / требуется проверка] Реальные классы пользовательских папок, состояние
  Exchange Search, задержка индексации и покрытие нужных русскоязычных вложений. Пользователь
  сможет сопоставить серверную AQS-выдачу с уже имеющимися известными письмами/вложениями;
  исследование не создаёт и не отправляет тестовые письма.
- [предположение / ограничение модели] Отличить внешнее перемещение от copy+delete
  одинаковых экземпляров безошибочно по этим delta нельзя; это ограничение схемы ключей,
  а не обещанный будущий тест, который обязательно его устранит.

## Что ещё стоит изучить

- За пределами RT-03: конкретный Java-клиент, NTLM и контроль создаваемых им SOAP-вызовов
  относятся к RT-02.
- За пределами RT-03: сохранение inline/TNEF, S/MIME, `.msg`, рекурсивных MIME-вложений
  и сопоставление MIME-частей с attachment IDs относятся к RT-09.
- За пределами RT-03: уведомления как оптимизация polling, отдельный archive mailbox
  и public folders требуют самостоятельной спецификации области синхронизации.

## Источники

Все источники прочитаны **2026-10-24**. Ниже приведены даты обновления, когда они были
зафиксированы при чтении; они не означают дату появления API. Для старого Exchange 2013
версия операции важнее свежести публикации; современные свойства без подтверждения
совместимости не перенесены. Числа в тексте ссылаются на эти же URL.

1. [GetFolder operation][1] (обновлено 2023-03-29).
2. [FindFolder operation][2] (справочник EWS).
3. [SyncFolderHierarchy operation][3] (справочник EWS).
4. [DistinguishedFolderId][4] (обновлено 2023-03-29).
5. [Folders and items in EWS in Exchange][5] (официальная таблица типов; дата публикации не зафиксирована).
6. [FindItem operation][6] (справочник EWS).
7. [Perform paged searches by using EWS in Exchange][7] (официальное руководство).
8. [EWS throttling in Exchange][8] (обновлено 2022-02-11; таблица различает 2010/2013/Online).
9. [SyncFolderItems operation][9] (справочник EWS).
10. [MaxChangesReturned][10] (обновлено 2023-03-28).
11. [Mailbox synchronization and EWS in Exchange][11] (обновлено 2022-02-10).
12. [Handling synchronization-related errors in EWS in Exchange][12] (обновлено 2021-09-15).
13. [[MS-OXWSCDATA]: m:ResponseCodeType Simple Type][13] (официальная спецификация протокола).
14. [GetItem operation (email message)][14] (обновлено 2023-03-29).
15. [MimeContent][15] (обновлено 2023-03-29).
16. [IsRead][16] (обновлено 2023-03-29).
17. [Export items by using EWS in Exchange][17] (официальное руководство MIME-экспорта).
18. [GetAttachment operation][18] (справочник EWS).
19. [Get attachments by using EWS in Exchange][19] (официальное руководство).
20. [IncludeMimeContent][20] (обновлено 2023-03-29).
21. [Attachments and EWS in Exchange][21] (официальный обзор; использована семантика file/item/inline).
22. [FindConversation operation][22] (справочник EWS).
23. [GetConversationItems operation][23] (справочник EWS; прямо указано появление в 2013).
24. [QueryString (QueryStringType)][24] (справочник EWS; различия типов 2010/2013).
25. [Perform an AQS search by using EWS in Exchange][25] (обновлено 2020-01-15).
26. [Use search filters with EWS in Exchange][26] (официальное руководство).
27. [File formats indexed by Exchange Search: Exchange 2013 Help][27] (версия явно Exchange 2013).
28. [EWS Identifiers in Exchange][28] (обновлено 2020-01-15).
29. [ConvertId operation][29] (обновлено 2023-03-29).
30. [EWS operations in Exchange][30] (официальный каталог с версиями появления операций).
31. [Verifying the results of an EWS or EWS Managed API call][31] (официальное руководство).
32. [Configure client-specific message size limits: Exchange 2013 Help][32] (версия явно Exchange 2013).
33. [SyncFolderHierarchy — request element][33] (обновлено 2024-09-06).
34. [Synchronize items by using EWS in Exchange][34] (SOAP-пример изменений и continuation).
35. [MaxItemsToReturn][35] (обновлено 2023-03-29).
36. [FindConversationResponse][36] (обновлено 2023-03-29).
37. [Exchange Search: Exchange 2013 Help][37] (обновлено 2023-01-26).
38. [InternetMessageId][38] (обновлено 2023-03-29).
39. [RFC 5322 — Internet Message Format, §3.6.4][39] (опубликован 2008-10).
40. [Exchange workload management: Exchange 2013 Help][40] (версия явно Exchange 2013).
41. [New-ThrottlingPolicy — Exchange PowerShell][41] (справочник с применимостью параметров по версиям;
    значение concurrency противоречит [8], см. В9).

[1]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/getfolder-operation
[2]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/findfolder-operation
[3]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/syncfolderhierarchy-operation
[4]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/distinguishedfolderid
[5]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/folders-and-items-in-ews-in-exchange
[6]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/finditem-operation
[7]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/how-to-perform-paged-searches-by-using-ews-in-exchange
[8]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/ews-throttling-in-exchange
[9]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/syncfolderitems-operation
[10]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/maxchangesreturned
[11]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/mailbox-synchronization-and-ews-in-exchange
[12]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/handling-synchronization-related-errors-in-ews-in-exchange
[13]: https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-oxwscdata/53734587-8b80-4cd8-b0ea-92da6b960d9e
[14]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/getitem-operation-email-message
[15]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/mimecontent
[16]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/isread
[17]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/how-to-export-items-by-using-ews-in-exchange
[18]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/getattachment-operation
[19]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/how-to-get-attachments-by-using-ews-in-exchange
[20]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/includemimecontent
[21]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/attachments-and-ews-in-exchange
[22]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/findconversation-operation
[23]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/getconversationitems-operation
[24]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/querystring-querystringtype
[25]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/how-to-perform-an-aqs-search-by-using-ews-in-exchange
[26]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/how-to-use-search-filters-with-ews-in-exchange
[27]: https://learn.microsoft.com/en-us/exchange/file-formats-indexed-by-exchange-search-exchange-2013-help
[28]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/ews-identifiers-in-exchange
[29]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/convertid-operation
[30]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/ews-operations-in-exchange
[31]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/verifying-the-results-of-an-ews-or-ews-managed-api-call
[32]: https://learn.microsoft.com/en-us/exchange/configure-client-specific-message-size-limits-exchange-2013-help
[33]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/syncfolderhierarchy
[34]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/how-to-synchronize-items-by-using-ews-in-exchange
[35]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/maxitemstoreturn
[36]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/findconversationresponse
[37]: https://learn.microsoft.com/en-us/exchange/exchange-search-exchange-2013-help
[38]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/internetmessageid
[39]: https://www.rfc-editor.org/rfc/rfc5322#section-3.6.4
[40]: https://learn.microsoft.com/en-us/exchange/exchange-workload-management-exchange-2013-help
[41]: https://learn.microsoft.com/en-us/powershell/module/exchangepowershell/new-throttlingpolicy?view=exchange-ps
