# docs/storage-agent.md — message directories and the derived catalog

Implemented in IT-02 (first half of plan.md 7.2). See `docs/plan.md` 6.2–6.5 for the design.
`MessageStore` and `MailIndex` take one connection directory; neither contacts a mailbox.

## Disk layout

Under `ConnectionDefinition.connectionDir()`:

```text
<connection>/
  folders.json
  messages/<k2>/<key>/
    message.eml
    meta.json
  index/
```

`key` is a lowercase canonical UUID; `k2` is its first two characters. `messageDir` validates
the key without I/O. `createMessageDir` requires a new directory. `keys` returns sorted valid
message directory names under the matching prefix; unrelated names and files are ignored.
`delete` removes a message directory recursively and leaves its prefix directory.

`meta.json` example (synthetic):

```json
{
  "key": "3f2a0000-0000-0000-0000-000000000001",
  "folderKey": "f1",
  "folder": "INBOX",
  "ref": "101",
  "size": 12,
  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "downloadedAt": "2026-10-25T12:00:00Z",
  "state": "downloaded"
}
```

States are `downloaded`, `converted` and `failed`. A failed download has size 0, no SHA-256 and
an `error` explaining the failure; it may have no `message.eml`. Null properties are omitted
by the configured Jackson 3 mapper and read back as null. Conversion is not implemented yet.

`folders.json` example (synthetic):

```json
{
  "folders": [
    { "key": "f1", "remoteId": "INBOX", "path": "INBOX", "cursor": "7" }
  ]
}
```

Sync assigns local folder keys. The source owns the opaque cursor; null means no full pass yet.
A missing folders file returns an empty list. Missing metadata returns an empty `Optional`.

## Write order and failures

The caller creates the message directory, downloads `message.eml`, then writes `meta.json`
last. `MessageStore` never writes MIME. Without metadata, the directory is incomplete.
Metadata writes require an existing message directory; saving folders creates the connection
directory if needed. Constructors perform no I/O.

Both JSON files are written to a temporary file in the target directory, then moved with
`ATOMIC_MOVE` and `REPLACE_EXISTING`. Temporary files are cleaned up after a write. File I/O
failures become `UncheckedIOException`; Jackson exceptions propagate unchanged.

## Lucene catalog and reconciliation

`MailIndex.openWriter` creates `index/` and opens the default `IndexWriter` on `FSDirectory`.
Only `lucene-core 10.5.2` is used. The catalog contains these fields, derived from `MessageMeta`:

| Field | Value | Type |
|---|---|---|
| `key` | Local UUID | `StringField`, stored |
| `ref` | `folderKey + "/" + ref` | `StringField`, stored |
| `folder` | Folder path | `StringField`, stored |
| `state` | Lowercase state | `StringField`, stored |
| `error` | Failure explanation, only when non-null | `StoredField` |

`put` replaces by key; `delete` removes by key. `keys` and `folderRefs` open and close an NRT
reader of the writer, so pending changes are visible before commit. Folder lookup uses the
`folderKey + "/"` prefix: `f1` excludes `f10`, and `/` inside a server ref is preserved.

Lucene's `write.lock` allows one writer per connection. A second writer fails immediately with
`IndexLockedException` and the message `Connection is being synchronized by another process`.
There is no retry or additional lock. Closing commits the writer and closes the directory.
Other Lucene I/O failures become `UncheckedIOException`.

`reconcile(store)` deletes incomplete message directories, removes indexed keys absent from
disk, indexes complete directories missing from the index, then commits. It reads metadata
only for missing index entries; existing indexed entries are left alone. Failed messages with
metadata remain valid without MIME. Deleting `index/` loses no source data: open a new writer
and reconcile to restore the catalog from disk, without the network.

`sync-status.json`, synchronization, MIME parsing, conversion, text fields and tool readers
remain for later tasks.
