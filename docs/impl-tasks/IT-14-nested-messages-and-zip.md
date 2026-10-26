# IT-14 — Nested messages and ZIP archives as attachments (Д10, part 1)

## Goal and fixed decisions

Today a forwarded message (`message/rfc822`, `.eml`) or a ZIP archive is saved as one opaque
attachment: its documents are neither converted nor searchable. After this task the files inside
nested messages and ZIP archives become ordinary attachments of the same message: saved under
`attachments/`, converted to `<file>.md` by the existing `AttachmentText`, indexed, shown by
`getMessage`, readable by `getAttachment` and usable in search snippets.

This is part 1 of Д10. MSG (POI HSMF) and TNEF (POI HMEF) need `poi-scratchpad` and come in a
separate brief after this one is accepted. Other archive formats (7z, RAR, TAR, GZIP) are out of
scope.

Fixed decisions:

1. **Flat list, one numbering.** Files from containers are appended to the message's attachment
   list after the MIME attachments, in the same `attachments/` directory, numbered on from the
   last number (`<n>__<sanitized name>`). There are no subdirectories, so `getAttachment(file)`
   keeps working with one exact filename and no path from an archive ever becomes a file path.
2. **`AttachmentInfo.parent`.** A new last component: the stored `file` of the container the
   entry came from; null for attachments of the message itself. The tree is visible through it.
3. **Derived, not stored.** No new JSON file or `meta.json` field. The list is rebuilt from the
   saved container files on every read (`MessageProcessor.load`, `getMessage`, `getAttachment`,
   search snippets), exactly as the MIME list is rebuilt from `message.eml` today. The same code
   runs with saving on (processing) and off (reading), so both produce the same list.
4. **Breadth-first.** Walk the growing list by index: when an attachment is a container, append
   its entries to the end of the list. Entries of nested containers are expanded when the walk
   reaches them.
5. **Containers and depth.** A MIME attachment of the message has depth 1; an entry has its
   container's depth plus one. A container is opened only when its depth is at most **5**. This
   matches `testdata/mime/M19` (`maxContainerDepth` 5, stop before entering depth 6) and stops
   self-containing ZIP files.
6. **Limits** (local constants in the new class, no settings, no shared budget object):
   - a ZIP entry larger than **20 MiB** decoded (the existing text threshold) is skipped: its
     partial file is deleted, it gets no number and is not listed;
   - at most **1,000** extracted files and **256 MiB** extracted bytes per message. When either
     is reached, expansion stops for the rest of the message (the current archive and every later
     container). Check before each container and before each extracted entry, and stop copying an
     entry that would pass the byte limit (delete its partial file).

   The user approved items 5 and 6 on 2026-10-26 (`docs/plan.md` section 5); they are the only
   exception to the counter rule of `docs/plan.md` 3.8.
7. **No new dependency.** Nested messages are read by Angus through `MimeReader`; ZIP by
   `java.util.zip.ZipInputStream`.

What we take from `C:\git\redmine-mcp-server` (commit `f62b3e5`,
`extraction/parser/ZipParser.java` and `extraction/ExtractionPipeline.java`): recursive
processing of archive entries by the same converters, a depth bound, a per-entry size bound and an
entry-count bound, skipped directory entries. What we do not take: the parser framework
(`DocumentParser`, `ParseSink`, Tika fallbacks, `ExtractionLimits` settings), in-memory buffering
of entries, and staging entries under their archive paths. No code is copied.

## Read first

`AGENTS.md`, `docs/impl-tasks/README.md`, `docs/plan.md` 3.4, 3.5 and 3.8, `docs/mime-agent.md`,
`docs/storage-agent.md`, `docs/tools-agent.md`, `testdata/mime/README.md`,
`testdata/mime/M09-eml-msg-zip-chain.expected.json`, `testdata/mime/M19-nesting-over-limit.expected.json`,
then `mime/MimeReader.java`, `convert/AttachmentText.java`, `sync/MessageProcessor.java`,
`index/SearchSnippets.java`, `tools/MailTools.java` and their tests.

## What to build

### `model/AttachmentInfo`

Add `String parent` as the last component and update its Javadoc. `MimeReader` passes null.

### `mime/MimeReader`

- Add `read(Path eml, Path attachmentsDir, int firstNumber)`: stored names start at
  `firstNumber` instead of 1. The existing two-argument `read` delegates with 1.
- Make `safeName` package-private for the new class. The reader still treats
  `message/rfc822` and ZIP parts as opaque leaves.
- Review correction authorized by the user on 2026-10-26: add package-private
  `readAttachments(Path eml, Path attachmentsDir, int firstNumber, int fileLimit, long byteLimit,
  List<AttachmentInfo> entries)`, returning whether a limit stopped expansion. Reuse the MIME
  walk, but do not decode the body or headers. Apply the remaining allowances during extraction,
  preserve completed entries on a later error and delete only the incomplete file. Keep the
  public `read` overloads' parsing behavior and do not add a shared budget object.

### `mime/Containers` (new)

```java
/** Appends the files of saved nested messages and ZIP archives to the attachment list (plan.md 3.5). */
public final class Containers {
    public static MailMessage expand(MailMessage message, Path attachmentsDir, boolean save)
}
```

Returns a copy of `message` whose `attachments()` are the original list plus all entries, by the
decisions above. `attachmentsDir` is the message's `attachments/` directory; containers are read
from the files saved there. A missing container file yields no entries. With `save` false nothing
is created, written or deleted.

- **Nested message:** content type `message/rfc822`, or extension `eml` of the decoded name
  (stored filename when unnamed). Entries are the attachments from
  the bounded `MimeReader.readAttachments` helper, with `parent` set. The nested
  message's body is not an entry; it goes to the container's `.md` (see `AttachmentText`).
  Checked on 2026-10-26: Angus parses the bytes `MimeReader` saves for `M09` (`forwarded.eml`,
  6,276 bytes, lists `nested.msg`) and for all six levels of `M19`.
- **ZIP:** extension `zip`, or content type `application/zip`, `application/x-zip-compressed`
  or `application/x-zip`. Read with `new ZipInputStream(..., Charset.forName("IBM866"))`: entries
  without the UTF-8 flag use CP866 (Russian Windows Explorer archives), flagged entries stay UTF-8.
  Checked on JDK 25.0.2 on 2026-10-26: the default UTF-8 decoding rejects such an archive with
  `ZipException: invalid LOC header (bad entry name)`. Skip directory entries. For each entry:
  `name` is the entry name with `\` replaced by `/`; the stored file is
  `<n>__<safeName(last path segment)>`; content type `application/octet-stream`; `contentId` and
  `charset` null; `inline` false; `size` the bytes read. Office files inside archives are not
  containers: only the two kinds above are opened.
- **Errors:** an exception while reading one container (`IOException` including `ZipException`
  for encrypted, damaged or unsupported archives, `MessagingException`, `RuntimeException`) stops
  that container only. Entries listed before the error stay; the partial file of the entry being
  copied is deleted and that entry is not listed. Log at debug level with the
  exception class name only, as `AttachmentText` does. Message state does not change.
- Keep the limit values as private constants; a package-private overload that takes the file and
  byte limits is allowed for the limit tests.

### `convert/AttachmentText`

New format `eml` (extension `eml` or content type `message/rfc822`), subject to the existing
20 MiB check: read the file with `MimeReader.read(file, null)` and render

```text
Subject: <subject>
From: <name> <address>, ...
To: ...
Cc: ...
Date: <ISO-8601 instant>

<body: plain as is, HTML through pandoc.htmlToMarkdown>
```

Omit a header line without a value and the body when there is none; an address without a name is
just the address. Add `MessagingException` to the caught exceptions. ZIP archives get no `.md`.

### Callers

- `MessageProcessor.process`: after `MimeReader.read`, call `Containers.expand(..., true)`; convert
  and index the expanded list. `load`: `Containers.expand(..., false)`.
- `SearchSnippets.select`, `MailTools.getMessage`, `MailTools.getAttachment`: replace the bare
  `MimeReader.read(...).message()` attachment list with `Containers.expand(..., false)`. Snippets
  keep "first match in list order", now including entries.
- `api/AttachmentRef`: add nullable `parent` (stored filename of the containing attachment;
  absent for attachments of the message itself) as the last component; fill it in `getMessage`.
  Update the `getMessage` and `getAttachment` tool descriptions: attachments include files from
  nested messages and ZIP archives, and `getAttachment` accepts their `file`.

The index needs no code change and keeps schema 3: `attachmentNames`, `attachmentName`,
`attachmentFile` and `attachmentText` come from `message.attachments()` and saved Markdown.
Messages already on disk get entries after the user runs `mail-reprocess`; until then
`getMessage` may list entries of saved containers without `path` or `textPath`.

## Tests

New `src/test/java/mail/mcp/mime/ContainersTest.java` (ZIP files built with `ZipOutputStream`
and wrapped in synthetic MIME in `@TempDir`):

- `zipEntriesAreAppendedWithParent`: two entries, one in a subfolder; numbers continue after the
  MIME attachments, names keep the path, files match the entry bytes.
- `cp866EntryNamesAreDecoded`: a Cyrillic name written with `ZipOutputStream(out, IBM866)`.
- `nestedMessageAttachmentsAreListed`: `M09` gives `1__forwarded.eml`, then `2__nested.msg` with
  parent `1__forwarded.eml`; the MSG is not opened (part 2).
- `depthStopsBeforeLevelSix`: `M19` lists `level-1.eml` … `level-6.eml`, each with the previous
  as parent; nothing is listed under `level-6.eml`. Also a chain of six ZIP files inside each
  other stops the same way.
- `oversizedEntryIsSkipped`: an entry of 20 MiB + 1 byte is neither listed nor left on disk; the
  next entry gets the next number.
- `messageLimitsStopExpansion`: through the package-private overload with small limits, the file
  limit and the byte limit each stop all later containers.
- `brokenArchiveKeepsEarlierEntries`: a ZIP cut in the middle of its second entry lists the
  first entry and throws nothing; a later container in the same message is still expanded.
- `readingWithoutSavingGivesTheSameList`: `save` false after `save` true returns an equal list
  and leaves the directory snapshot unchanged.
- Review regression tests: `nestedFileLimitStopsBeforeLaterAttachmentsAreDecoded` and
  `nestedByteLimitStopsWhileDecodingAttachment` use malformed encoded content beyond the limit
  to prove that extraction stops during reading, with saving both enabled and disabled.
  `brokenNestedMessageKeepsEarlierEntries` verifies completed entries survive a later MIME error.

Add:

- `MimeReaderTest.firstNumberContinuesNumbering`.
- `AttachmentTextTest.nestedMessageBecomesHeadersAndBody`: plain body and, with pandoc, HTML body.
- `MessageProcessorTest.archiveEntriesGetMarkdownAndText`: a TXT inside a ZIP gets `<file>.md` and
  is part of `Processed.attachmentText`; `loadListsArchiveEntriesWithoutWriting`.
- `MailToolsTest.getMessageShowsArchiveEntriesWithParent`, `getAttachmentReadsArchiveEntry`.
- `AttachmentSnippetTest.snippetComesFromArchiveEntry`: the hit names the entry's `file`.

Existing tests keep passing unchanged, except constructor calls of `AttachmentInfo` and
`AttachmentRef`, which get the new argument.

## Files and documents

Create `src/main/java/mail/mcp/mime/Containers.java` and
`src/test/java/mail/mcp/mime/ContainersTest.java`.

Change `model/AttachmentInfo.java`, `mime/MimeReader.java`, `convert/AttachmentText.java`,
`sync/MessageProcessor.java`, `index/SearchSnippets.java`, `tools/MailTools.java`,
`api/AttachmentRef.java`, and the tests `MimeReaderTest`, `AttachmentTextTest`,
`MessageProcessorTest`, `MailToolsTest`, `AttachmentSnippetTest`, `MailIndexReaderTest`,
`SearchToolsTest`, `MailIndexTest` (the last three only for the `AttachmentInfo` constructor;
`MailIndexTest` was omitted originally and authorized by the user with the review fixes on 2026-10-26).

Documents:

- `docs/mime-agent.md`: a section on containers (kinds, order, numbering, `parent`, depth,
  limits, CP866, errors, `eml` Markdown); remove the statements that nested messages and ZIP are
  never opened (MSG, TNEF, S/MIME and calendars still are not).
- `docs/storage-agent.md`: entries live flat in `attachments/`, the list is derived from saved
  containers, `mail-reprocess` is needed for existing messages, schema unchanged.
- `docs/tools-agent.md`: `AttachmentRef.parent`, `getAttachment` accepts entries.
- `README.md`: the attachment-text bullet mentions files inside nested messages and ZIP archives.

## Do not

- No MSG, TNEF, 7z, RAR, TAR or GZIP; no new dependency.
- No settings for the limits, no shared budget or context object, no new message states or
  error codes, no timeouts.
- No subdirectories under `attachments/`, no use of entry paths as file paths, no new JSON file,
  no `meta.json` or index schema change.
- No charset guessing for ZIP names beyond the fixed CP866 rule; no password attempts for
  encrypted archives.
- Do not change `testdata/`, `docs/plan.md`, `AGENTS.md` or research files.

## For the user to run after acceptance

`mail-reprocess work` and `mail-reprocess mailru` (offline). Report the counts the command prints;
then check in an MCP client that `search` with `attachmentText` finds a word that exists only in a
document inside an archive, and that `getMessage` shows that document with its `parent`.
