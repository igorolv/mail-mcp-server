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
- **Stop words** (`и`, `the`, …) are ignored; a query of stop words only finds nothing.
- **No wildcards and no fuzzy matching.** `догов*` is not a prefix search.
- **Case** does not matter, and `ё` matches `е`.
- A query has at most 1,000 characters.

## Fields

The `field` argument narrows the search; the default is `any`.

| Field | Searches |
|---|---|
| `any` | everything below |
| `subject` | the subject |
| `body` | the message body |
| `from` | sender address and name |
| `to` | addresses and names in To and Cc |
| `attachmentName` | attachment file names, whole or by parts (`отчёт` finds `Отчёт_за_май.xlsx`) |
| `attachmentText` | the text of attachments, including files inside ZIP archives, attached messages and Outlook `.msg` files |

- A query that is one complete address (`user@example.com`) matches the address fields only, not
  display names. Addresses match only when complete.
- `folder` restricts the search to one local folder (keys from `listFolders`).

Attachment text exists for PDF, DOCX, DOC, XLSX, XLS, PPTX, PPT, ODT, RTF, EPUB, HTML, CSV,
plain text, attached `.eml` and `.msg` messages and `winmail.dat` (TNEF), up to 20 MiB per file
(XLSX up to 5 MiB). Images are not recognized; encrypted or damaged documents have no text.

## Dates

`search` and `listMessages` accept optional `since` and `until`:

- `since` is inclusive, `until` exclusive. Messages of October 26–27 (UTC):
  `since: "2026-10-26"`, `until: "2026-10-28"`.
- A value is a date `YYYY-MM-DD` (midnight UTC) or an ISO-8601 date and time with `Z` or an offset,
  e.g. `2026-10-26T09:00:00+03:00`.
- The date is the message's `Date` header, not the time it was received or downloaded. Messages
  without a date are left out whenever a bound is given.
- `search` without `query` but with a date bound lists all messages of the period across all
  folders (or one `folder`), newest first. It has no scores or snippets.

## Results and snippets

`search` returns pages of message cards: key, folder, date, sender, subject, attachment flag,
score and a snippet with the matches in `**bold**`. Pages default to 20 cards, at most 100;
`total` is exact and `nextOffset` gives the next page.

The snippet comes from the body when the body matches. Otherwise, for `any` and
`attachmentText`, it comes from the first matching attachment, and the card names that attachment
in `snippetAttachmentFile` (pass it to `getAttachment`) and `snippetAttachmentName`.

`getMessage` returns headers, the body in chunks and the list of attachments;
`getAttachment` returns an attachment's text in chunks. Every card and message has a `localPath`
to the message directory, so an agent with file access can open the original files directly.

## Copies of one message

The same message is often stored in several folders, for example a message you sent to yourself.
`search` and `getThread` show it once: the card represents the group and lists the other copies as
`{key, folder}` under `copies`. Copies are recognized by the same `Message-ID`, date, sender and
subject. `total` and pages count groups. `listMessages` and the folder counts of `listFolders`
still show every copy.

## Conversations

`getThread` takes the key of any message and returns the whole conversation it belongs to, across
all folders of the connection, oldest first. Messages are linked only by their
`Message-ID`, `In-Reply-To` and `References` headers, as mail clients do; the subject and the
participants do not link messages. Replies to a message that is not stored locally still join
the conversation. Pages default to 50 cards, at most 200.

## Read and flag snapshots

Cards and messages carry optional `read` and `flagged` (the user's flag or star; not the sender's
importance and not Gmail's Important label). They show the state seen at the last synchronization,
not the live state of the mailbox. A missing value means unknown. The server never changes these
flags in the mailbox.

## Coverage

Search sees only what has been synchronized and indexed. During a synchronization results can be
incomplete: `listFolders` shows which folders were synchronized completely (`synced`), and
`syncStatus` shows the progress of a running sync.

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
