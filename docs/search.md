# Search

How the `search`, `listMessages` and `getThread` tools find mail, and how to tune Russian word
forms. The agent calls these tools itself; this page explains what to expect from them.

- [Queries](#queries)
- [Fields](#fields)
- [Dates](#dates)
- [Results and snippets](#results-and-snippets)
- [Copies of one message](#copies-of-one-message)
- [Conversations](#conversations)
- [Read and flag snapshots](#read-and-flag-snapshots)
- [Coverage](#coverage)
- [Word forms](#word-forms)
- [Known limits](#known-limits)

## Queries

Search runs on the local Lucene index of one connection; it never contacts the mail server.

- **Every word is required.** `договор поставка` finds messages that contain both words.
- **Quotes make a phrase:** `"акт сверки"`.
- **Word forms match** in Russian and English: `договоры` finds `договора`, `contracts` finds
  `contract`. Russian uses the Snowball stemmer, English its own field.
- Text analyzers ignore common **stop words** (`и`, `the`, …), so a query containing only stop
  words may find nothing. Exact address fields and filename analysis have different rules.
- **No wildcards and no fuzzy matching.** `догов*` is not a prefix search.
- **No OR or NOT operators.** Search alternatives separately.
- **No field prefixes in the query.** Pass `field: "from"` and `query: "user@example.com"`,
  rather than a query such as `from:user@example.com`.
- **Case** does not matter, and `ё` matches `е`.
- A query has at most 1,000 characters.

## Fields

The `field` argument selects one search field group; the default is `any`. It has no effect on
date-only results when `query` is omitted.

| Field | Searches |
|---|---|
| `any` | everything below |
| `subject` | the subject |
| `body` | the message body |
| `from` | sender address and name |
| `to` | addresses and names in To and Cc |
| `attachmentName` | attachment file names, whole or by parts (`отчёт` finds `Отчёт_за_май.xlsx`) |
| `attachmentText` | the text of attachments, including files inside ZIP archives, attached messages and Outlook `.msg` files |
| `label` | mailbox [labels](#labels), by their words, without regard to case (`inbox`, `My Project`) |

- Use `field: "from"` or `field: "to"` with a complete address (`user@example.com`) to filter by
  sender or recipient. Address fields match complete addresses without regard to case; a query
  of one complete address ignores display names. With `any`, the query can also match body or
  attachment text, so it does not restrict matches to message participants.
- `folder` restricts the search to one local folder (a key from `listFolders`, not its display
  name). Omit it to search all folders in the connection.

Attachment text exists for PDF, DOCX, DOC, XLSX, XLS, PPTX, PPT, ODT, RTF, EPUB, HTML, CSV,
plain text, attached `.eml` and `.msg` messages and `winmail.dat` (TNEF), up to 20 MiB per file
(XLSX up to 5 MiB). Images are not recognized; encrypted or damaged documents have no text.

## Dates

`search` and `listMessages` accept optional `since` and `until`:

- `since` is inclusive, `until` exclusive. Messages of October 26–27 (UTC):
  `since: "2026-10-26"`, `until: "2026-10-28"`.
- A value is a date `YYYY-MM-DD` (midnight UTC) or an ISO-8601 date and time with `Z` or an offset,
  e.g. `2026-10-26T09:00:00+03:00`, with at most three fractional second digits. When both bounds
  are given, `since` must be earlier than `until`.
- The date is the message's `Date` header, not the time it was received or downloaded. Messages
  without a date are left out whenever a bound is given.
- `search` without `query` but with a date bound lists all messages of the period across all
  folders (or one `folder`), newest first. Scores are zero and snippets are absent.

## Results and snippets

`search` returns pages of message cards: key, folder, date, sender, subject, attachment flag,
score and a snippet with the matches in `**bold**`. Pages default to 20 cards, at most 100;
`total` is exact and `nextOffset` gives the next page. Repeat the same search arguments with
`offset` set to `nextOffset`; a null `nextOffset` means there are no more pages.

For `any`, the snippet comes from the body when the body matches, otherwise from the first
matching attachment. `attachmentText` tries attachments first, even when the body matches.
An attachment snippet identifies its source in `snippetAttachmentFile` (pass it to
`getAttachment`) and `snippetAttachmentName`.
The snippet may show only part of a multiword match, or fall back to body context without
highlights when no matching passage is available.

`getMessage` returns headers, the body in chunks and the list of attachments. Its `nextOffset`
continues only the body; headers and attachments are repeated. To read an attachment, use
`getAttachment` with the same connection and message key and the entry's `file`, not its `name`
or `path`. Continue attachment text using that tool's own `nextOffset`, keeping the connection,
key and file. Message keys are local keys, not the `Message-ID` header.

These tools read saved text without converting files on demand. Missing body or attachment text
does not mean the original was empty; a message's `state` and `error` may explain missing content.
An unreadable saved MIME can also leave the attachment list empty. An attachment's original
may be available at `path` even without text; the path is absent if the original file is missing.
Existing saved text remains readable even if current conversion limits would reject the file.
Every card and message has a `localPath` to the message directory, so an agent with file access
can open the original files directly.

## Copies of one message

The same message is often stored in several folders, for example a message you sent to yourself.
`search` and `getThread` show it once: the card represents the group and lists the other copies as
`{key, folder}` under `copies`. Copies are recognized by the same `Message-ID`, date, sender and
subject. Search groups only copies that match its query and filters. `total` and pages count
groups. `listMessages` and the folder counts of `listFolders` still show every copy.

## Conversations

`getThread` takes the local key of any indexed message and returns the locally indexed part of
its conversation across all folders of the connection, oldest first, with undated messages last.
Messages are linked only by their
`Message-ID`, `In-Reply-To` and `References` headers, as mail clients do; the subject and the
participants do not link messages. Replies to a message that is not stored locally still join
the conversation. Pages default to 50 cards, at most 200.
Keep the original message key and connection when continuing with `nextOffset`. To read a
returned card, pass that card's key and the same connection to `getMessage`.

## Read and flag snapshots

Cards and messages carry optional `read` and `flagged` (the user's flag or star; not the sender's
importance and not Gmail's Important label). They show the state seen at the last synchronization,
not the live state of the mailbox. A missing value means unknown. The server never changes these
flags in the mailbox.

## Labels

Cards and messages carry optional `labels`: the mailbox labels seen at the last synchronization.
Currently only Gmail provides them. Gmail is synchronized as one folder, its All Mail folder, so a
message is stored once rather than once per label; its labels (`Inbox`, `Sent`, `Starred`, your
own labels) replace the folders for finding it. `field: "label"` searches labels by their words;
Russian word forms are not applied there, so use the label's own words. Gmail's `All` label is not
listed because it names the folder itself. An empty list means the message has no labels; a
missing `labels` means unknown or a mailbox without labels. The server never changes labels.

## Coverage

Search sees only what has been synchronized and indexed. During a synchronization results can be
incomplete. `listConnections.localDataAvailable` indicates only that a local data directory exists.
`listFolders.synced` indicates a recorded completed folder sync, not current mail; its message
count comes from the committed local index and is zero if there is no index. `syncStatus` shows
the current or last run, including timestamps and outcome. None of these flags alone guarantees
that all current remote mail is available locally.

## Word forms

Snowball does not join irregular forms such as `письмо` and `писем`, or `ребёнок` and `дети`.
To join them, create an optional UTF-8 file `word-forms.txt` in the data directory
(`%USERPROFILE%\.mail-mcp-server\word-forms.txt`); `MAIL_MCP_WORD_FORMS_FILE` points elsewhere.
One file serves all connections. Search works as usual without it.

```text
# First word chooses the stem for the whole group
письмо писем
ребёнок дети
человек люди
```

- Each line is a group of at least two different words, letters only, separated by spaces. `#`
  starts a comment. Case and `ё`/`е` do not matter.
- A word can belong to one group only. Repeats within a group are allowed.
- Every listed word is analyzed like the group's first word; forms you do not list still use
  Snowball. Listed words are searchable even when they are stop words (`человек`).
- One-word synonyms work (`счёт инвойс`), but widen results and can reduce precision. Phrases are
  not supported.
- The file applies to Russian analysis only; English is unchanged.

After editing the file:

1. Check the rules: `java -jar mail-mcp-server.jar mail-analyze писем дети`
   ([`mail-analyze`](commands.md#mail-analyze)). An invalid file is reported with its line number.
2. Rebuild the index of every connection offline: `mail-reindex <connection>`
   ([`mail-reindex`](commands.md#mail-reindex)), or let the next requested sync rebuild it.

The server rereads the file on every text search; no restart is needed. Until the index is
rebuilt, a text search reports that the word forms changed. Date-only search, `listMessages` and
`getThread` keep working. Comments, spacing and the order of groups do not require a rebuild.

## Known limits

- Without a word forms file, irregular Russian forms are missed (see above).
- The stemmer sometimes joins different names (`Тестову` matches `Тестова`).
- The agent should retry with other forms, synonyms or the other language before concluding that
  a message does not exist; the `search` tool description tells it so.
- On a synthetic control set of 48 messages and 80 queries, search finds 79 of 86 expected matches
  with 7 false positives; with four word-forms groups, all 86.
