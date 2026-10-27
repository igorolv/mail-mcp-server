# RT-10 — MCP resources and client support

- Date: 2026-10-24
- Author: Codex / research session RT-10
- Brief: `docs/research-tasks/RT-10-mcp-resources.md` (historical path)
- Scope: design; no clients or servers were run and no mail connections were made.

## Summary

- [docs] The current published MCP revision is **2026-07-28**. It replaced
  `resources/subscribe` and `resources/unsubscribe` with `subscriptions/listen` [1].
- [docs] Resources, templates, reading, notifications and embedded resources already existed
  in 2024-11-05; the separate `resource_link` block appeared in 2025-06-18 [3–7].
- [docs] Listing concrete URIs and listing templates are separate operations. A tool's link
  can be absent from `resources/list`; the link itself does not transmit content [2, 10].
- [docs][code] Claude Code and VS Code allow selecting resources for context; Codex has tools
  for listing, reading and retrieving templates. Details and gaps are in the matrix [11, 15, 16].
- [docs] Cursor claims resource support, but its current page does not describe all requested
  capabilities. That claim cannot be extended to templates and subscriptions [14].
- [docs] ChatGPT web connects servers through HTTPS or Tunnel; desktop ChatGPT separately
  documents STDIO. Full support for ordinary mail resources is unconfirmed [17–19].
- [docs][code] Spring AI 2.x supports `@McpResource`, `@McpComplete` and programmatic
  registration; `ResourceLink` can be returned inside `CallToolResult` [21–26].
- [assumption] Recommendation: search and chunked-read tools as the primary interface, with
  resources and links as supplements. `localPath` is useful only if the client can access
  files, and should point to a filtered representation if the file is intended for the model.

## Answers to the questions

### В1. Specification: operations, version history and tool results

**Versions.** [docs] On the research date, `/specification` points to 2026-07-28. The following
distinguishes previous operations from current ones, which matters for compatibility [1, 2].

| Capability | Introduced / changed | Verified source |
|---|---|---|
| Resources, `resources/list`, `resources/read` | Already in the first published revision, 2024-11-05 | [docs] [3] |
| Templates, `resources/templates/list` | Already in 2024-11-05; URI templates per RFC 6570 | [docs] [3] |
| `resources/subscribe`, `resources/unsubscribe`, `notifications/resources/updated` | Mechanism from 2024-11-05; retained in 2025-11-25 | [docs] [3, 32] |
| `notifications/resources/list_changed` | Already in 2024-11-05; separate `listChanged` capability | [docs] [3] |
| `completion/complete` for resource arguments | Already in 2024-11-05; explicit `completions` capability added in 2025-03-26 | [docs] [4, 6] |
| Embedded content: `type: "resource"` | Already in 2024-11-05, including tool results | [docs] [5] |
| Link without content: `type: "resource_link"` | Added in 2025-06-18 | [docs] [7] |
| Resource and template icons | Added in 2025-11-25 | [docs] [8] |
| `subscriptions/listen` replaces subscribe/unsubscribe | Replaced in 2026-07-28 | [docs] [1] |
| `ttlMs`, `cacheScope`, `resultType` | New required fields in the corresponding results in 2026-07-28 | [docs] [1] |

**Discovery and reading.** [docs] `resources/list` returns descriptions of concrete resources,
while `resources/templates/list` returns parameterized families. Both support paging with
`cursor` / `nextCursor`. Obtain content through `resources/read` with a completed URI [2, 3].

[assumption] For mail, sensible families are `mail://{connection}/message/{id}` and
`mail://{connection}/attachment/{id}`. The resource list supports selecting already saved
objects; a template does not require listing every message in advance. Prefer an internal,
opaque identifier and encode URI components under URI rules; connection names must not
contain secrets.

**Two different notifications.** [docs] `list_changed` means the catalog changed; `updated`
means specific content changed. The latter notification carries a URI, and the client can
reread the resource as needed. The `listChanged` and `subscribe` capabilities are independent
[2, 32].

[docs] In 2026-07-28, subscriptions use `subscriptions/listen`:
`notifications.resourcesListChanged` selects catalog changes, and
`notifications.resourceSubscriptions` selects the URIs of watched resources. Revision
2025-11-25 uses the previous `resources/subscribe` / `resources/unsubscribe` [1, 2, 32].

[docs] The new revision also removed the initial `initialize` handshake: the client version
and capabilities are passed in each request's `_meta`; the server must implement
`server/discover`. Renaming one RPC is insufficient [1].

**Argument completion.** [docs] `completion/complete` accepts a `ref/resource` reference to a
template and an `argument.name` / `argument.value` pair. Dependent arguments use
`context.arguments`; the response has `values` (at most 100), optional `total` and `hasMore`.
The server declares `completions: {}` [9].

[assumption] We could complete `connection`, then `id` within the chosen connection. Do not
turn completion into a listing of the entire mail catalog.

**Link and embedded resource.** [docs] These are different `content` elements in a tool result
[10]:

```json
{
  "type": "resource_link",
  "uri": "mail://work/attachment/a42",
  "name": "attachment-a42-text",
  "mimeType": "text/plain",
  "description": "Extracted attachment text after filtering"
}
```

[docs] This block transmits an address and description. It guarantees neither inclusion in the
shared list nor automatic reading by the client. The embedded form already carries content [10]:

```json
{
  "type": "resource",
  "resource": {
    "uri": "mail://work/attachment/a42",
    "mimeType": "text/plain",
    "text": "Short filtered attachment content."
  }
}
```

[docs] These examples are individual blocks, not complete JSON-RPC responses. Binary resource
data is base64 in `blob`; `type: "blob"` is not a separate tool-result block [2, 10].

[assumption] An embedded resource suits small text. A link defers transmission, but reading a
whole large object afterward can still overflow context. A resource does not itself define
body paging: that needs separate design.

### В2. Which clients actually use resources

The matrix under Comparison tables covers seven required/popular clients and a separate row
for desktop ChatGPT. This checks documentation and source code, not particular installed
versions in operation.

[docs] The user workflow is most explicitly described for Claude Code (`@server:URI`) and
VS Code (`Add Context → MCP Resource`, `MCP: Browse Resources`) [11, 15].

[code] Codex has handlers for `list_mcp_resources`, `list_mcp_resource_templates` and
`read_mcp_resource`. This confirms model access to the operations, not a graphical template
picker, argument completion or automatic link reading [16].

[docs] The previous official MCP document describes manual resource selection in Claude
Desktop. It is evidence for the basic workflow, but does not establish the state of every
desktop feature on 2026-10-24 [12, 13].

[docs] "Full MCP support" in ChatGPT materials should not be read as a promise covering every
matrix cell: current help describes connecting and calling tools [18]. Importing skill files
through `resources/read` is a separately documented workflow; imported files are snapshots,
not live mail resources [19].

[docs] In current OpenAI help, the former developer mode article redirects to "Connecting
custom MCP servers in ChatGPT". It describes a Plugins interface without requiring Developer
Mode; old instructions and search excerpts may lag [18].

### В3. Resource registration in Spring AI MCP server 2.x

**Verified baseline.** [docs] The current reference read is labeled Spring AI **2.0.2**.
The 2.0.0 announcement of 2026-06-12 specifies MCP Java SDK 2.0.0 and MCP 2025-11-25 compliance.
This confirms the 2.x baseline, not automatic support for MCP 2026-07-28 [21, 27].

**Annotations.** [docs] `@McpResource` accepts a concrete URI or template with `{varName}`;
it supports `name`, `title`, `description`, `mimeType`. Return a string or `ReadResourceResult`.
Spring Boot discovers annotated Spring beans [21, 22].

[assumption] Declaration sketch for a future server, without a reading implementation:

```java
@McpResource(
    uri = "mail://{connection}/message/{id}",
    name = "message-text",
    mimeType = "text/plain")
public ReadResourceResult message(String connection, String id) {
    // The shared reading and filtering service retrieves the content.
}
```

[docs] URI completion uses `@McpComplete(uri = "...")`; handler arguments can include
`CompleteRequest.CompleteArgument`, returning a string list or `CompleteResult` [21].

**Programmatic registration.** [docs][code] Available options [23–26]:

| Registered item | API | Destination |
|---|---|---|
| Concrete URI and handler | `McpServerFeatures.SyncResourceSpecification` | `resources/list` and read handling |
| URI template and handler | `McpServerFeatures.SyncResourceTemplateSpecification` | `resources/templates/list` and read handling |
| Several resources in Spring Boot | Bean `List<SyncResourceSpecification>` | Server autoconfiguration |
| Resources and templates directly in SDK | `McpServer.sync(...).resources(...).resourceTemplates(...)` | Programmatic server construction |

[docs] `SyncResourceTemplateSpecification` contains `ResourceTemplate` and `readHandler` with
`McpSyncServerExchange`, `ReadResourceRequest` parameters; asynchronous counterparts exist
[24, 26]. [assumption] Annotations are simpler for an initial implementation; the programmatic
API is useful if individual catalog objects need runtime management.

**Links from tools.** [code] In Java SDK 2.0.1, `McpSchema.ResourceLink` implements `Content`;
`CallToolResult.Builder.addContent(...)` accepts this block [25]. [docs] An annotated tool can
return `CallToolResult` itself [21].

```java
var link = McpSchema.ResourceLink.builder()
    .uri("mail://work/attachment/a42")
    .name("attachment-a42-text")
    .mimeType("text/plain")
    .build();
return McpSchema.CallToolResult.builder()
    .addTextContent("Text is available through readAttachmentText(work, a42).")
    .addContent(link)
    .build();
```

[assumption] This is a future handler body fragment based on API [25], not a build-verified
application example. The text block leaves a clear reading path for clients that do not
show `resource_link` as a separate link.

**Limits and compatibility boundaries.**

- [docs] STDIO uses `spring-ai-starter-mcp-server` and `spring.ai.mcp.server.stdio=true`;
  resource capability is controlled by `spring.ai.mcp.server.capabilities.resource` [22, 23].
- [docs] In `SYNC` mode, reactive annotated methods are ignored; in `ASYNC`, synchronous methods
  are ignored. Do not accidentally mix these forms [22].
- [code] The inspected SDK 2.0.1 still has `initialize`, `resources/subscribe` and
  `resources/unsubscribe`. Verify support for the new protocol revision in the actual selected
  dependency, rather than relying on the 2.x version number [25].
- [assumption] Registering a template must not mean preregistering every message. A dynamic
  catalog and notifications need separate implementation; having a Java link type does not
  prove that every client displays it.

### В4. How existing servers deliver documents and large files

Three servers from the official MCP repository were compared; `Everything` is a demonstration,
not evidence that clients support every format in production [28–30].

| Server | Data delivery | Large-data approach | What to borrow for mail |
|---|---|---|---|
| Filesystem | [code] `read_text_file` returns text; `read_media_file` returns an image/audio block or an embedded resource for other binary files [28] | [docs] Line-based `head` / `tail`; not universal arbitrary continuation | [assumption] Separate text reading; a path is useful with shared filesystem access |
| Fetch | [docs][code] The `fetch` tool returns extracted page text, usually Markdown [29] | `max_length` defaults to 5000 with `start_index`; truncation message suggests continuation | [assumption] Bounded chunks and an explicit next-chunk offset |
| Everything | [code] `get-resource-links` returns introductory text and `resource_link`; `gzip-file-as-resource` offers a link or embedded resource [30] | A link defers file transmission; the embedded form immediately carries base64 | [assumption] A short description + link on top of the ordinary read tool |

[assumption] For a large PDF, DOCX or MIME message, prefer a small search result and selected
extracted-text chunks. A link reduces the initial response, but does not solve extraction,
subsequent-read limits or model format support. Everything's compressed binary resource is a
file-transfer example, not a way for the model to read it.

## Comparison tables

**Legend:** Yes — confirmed by documentation or source; Partial — only the stated scenario
confirmed; ? — to verify, no evidence for this feature found. ? does not mean unsupported.
The subscription column concerns content changes, not just `list_changed`.

| Client | Resource list | Reading | Templates | Subscriptions | Resource links from tools | Attach to context | Source |
|---|---|---|---|---|---|---|---|
| Claude Code | Yes [docs] | Yes [docs] | ? | ?; list refresh confirmed | ? | Yes, `@server:URI` [docs] | [11] |
| Claude Desktop, local MCP | Yes, manual selection [docs; previous guide] | Yes, selected resource [docs; previous guide] | ? | ? | ? | Yes, manual selection; current UI to verify [docs] | [12, 13] |
| Cursor IDE | ?; listing method not separately described | Yes, resources described as readable [docs] | ? | ? | ? | Partial: "read and referenced"; specific selection to verify [docs] | [14] |
| VS Code, GitHub Copilot agent mode | Yes [docs] | Yes [docs] | Yes, argument entry and completion [docs] | Yes, resource watching [code]; RPC revision to verify | ? | Yes, `Add Context → MCP Resource` [docs] | [15, 31] |
| OpenAI Codex CLI | Yes [code] | Yes [code] | Yes, listing [code]; argument completion ? | ? | ? | Partial: model reading; separate user selection ? | [16, 17] |
| ChatGPT web, custom MCP / previous developer mode | ? | Partial: skill file import [docs]; ordinary mail ? | ? | ? | ? | ?; selecting an app is not selecting a resource | [18, 19] |
| ChatGPT desktop, local MCP | ? | ? for ordinary resources | ? | ? | ? | ? | [17] confirms STDIO, not these features |
| Gemini CLI | Yes [docs] | Yes [docs]; binary data represented by a placeholder | ? | ? | ? | Partial: model reading; separate manual selection ? | [20] |

**Interpreting the limitations.**

- [docs] Claude Code refreshes lists on `list_changed`; noninteractive mode and Agent SDK
  describe refreshing only the tool list. This does not confirm resource-content subscriptions
  [11].
- [code] When watching, VS Code checks `ResourcesSubscribe`, calls `handler.subscribe` and
  handles `onDidUpdateResource`. This proves client behavior, not support for the new
  `subscriptions/listen` in every VS Code version [31].
- [assumption] Every ? needs separate verification: `resource_link` may reach the model as
  data, appear as a card, or lack a convenient read action. Having `resources/read` does not
  establish a particular UI.
- [docs] ChatGPT web uses HTTPS/Tunnel [18]; current general instructions separately allow
  STDIO in the desktop app [17]. These connection methods must not be conflated.

## Recommendation

This is a proposal for discussion in [plan.md](../plan.md), former section 5.5
("Messages as MCP resources"), not an accepted decision.

1. [assumption] **Tools are the primary contract.** Search returns identifiers, short filtered
   excerpts and metadata; body/attachment text reading takes a length limit and offset. Responses
   need `truncated`, `nextOffset` and a content version. Reading can continue without requiring
   client support for templates or links.
2. [assumption] **Resources supplement context selection.** Keep the URI families from В1,
   return a bounded catalog of available objects and templates. Resource and tool handlers
   should use one filtering service.
3. [assumption] **`resource_link` supplements tool results.** Return short ordinary text and a
   way to read the object through a tool alongside the link. Task success must not depend on
   automatically opening the link.
4. [assumption] **`localPath` is auxiliary.** It helps a local agent with file access, but does
   not itself transmit a file to the model or help remote clients. Give the model paths only
   to filtered text; a path to original MIME/attachments would bypass the project's filter.
5. [assumption] **Read large content in chunks.** Explicitly state MIME type and representation
   for extracted-text resources; original attachments and text must not silently replace one
   another. For an oversized resource, return a clear error and point to the chunked-read tool,
   rather than silently truncating it.
6. [assumption] **Defer subscriptions until needed.** They add little for immutable saved
   representations. First check the selected Spring AI and client versions; do not declare a
   capability without a working handler.

Rationale: [docs][code] Filesystem and Fetch use read tools [28, 29], and useful resource
workflows are confirmed for Claude Code and VS Code [11, 15]. [assumption] The tradeoff is two
access paths to one object and the need to maintain shared limits. The benefit is working
without relying on the least documented matrix cells.

## What could not be verified

- Exact installed client versions and actual `resource_link` display across all eight rows:
  the brief excludes running clients. Check later with synthetic files.
- Every ? in the matrix, including Claude Desktop/Cursor templates, manual CLI selection,
  subscriptions and ordinary ChatGPT resources: the documents found give insufficient evidence.
- MCP 2026-07-28 support in the specific future Spring AI / Java SDK combination: 2.x
  documentation and the inspected SDK do not establish that compatibility.
- Java sketches were not compiled: no application or build exists in the repository, and the
  task does not allow adding them. APIs were checked against the cited documents and source.
- Binary MIME/PDF handling and size limits in each client: there is no universal promise to
  read any `blob`. Separate checks on nonsecret data are needed.

[assumption] A future check needs only a small test server with a concrete resource, template,
completion and a tool returning text, a link and an embedded resource. Check reading a URI absent
from the list, catalog/content changes, large text and a binary file; record the client version
and negotiated protocol revision. No server was created in this session.

## Further research

- [assumption] URI stability rules when rebuilding indexes or changing filter versions.
- [assumption] Whether the user needs a separate original-attachment export outside model context.
- [docs] Prompts and sampling are separate MCP capabilities, not studied here [1].

## Sources

All links were read on **2026-10-24**. Without a stated publication date, a source is the current
undated page; `main` denotes the source state when read, not a release number. Search excerpts
were not used as evidence of current product behavior.

1. MCP, Key Changes — [revision 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/changelog); revision dated 2026-07-28.
2. MCP, Resources — [revision 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/server/resources); revision dated 2026-07-28.
3. MCP, Resources — [revision 2024-11-05](https://modelcontextprotocol.io/specification/2024-11-05/server/resources); revision dated 2024-11-05.
4. MCP, Completion — [revision 2024-11-05](https://modelcontextprotocol.io/specification/2024-11-05/server/utilities/completion); revision dated 2024-11-05.
5. MCP, Tools — [revision 2024-11-05](https://modelcontextprotocol.io/specification/2024-11-05/server/tools); revision dated 2024-11-05.
6. MCP, Key Changes — [revision 2025-03-26](https://modelcontextprotocol.io/specification/2025-03-26/changelog); revision dated 2025-03-26.
7. MCP, Key Changes — [revision 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18/changelog); revision dated 2025-06-18.
8. MCP, Key Changes — [revision 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/changelog); revision dated 2025-11-25.
9. MCP, Completion — [revision 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/completion); revision dated 2026-07-28.
10. MCP, Tools — [revision 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/server/tools); revision dated 2026-07-28.
11. Anthropic, Connect Claude Code to tools via MCP — [official reference](https://code.claude.com/docs/en/mcp), Use MCP resources, Dynamic tool updates and Notification streams sections; no explicit publication date.
12. MCP, previous Resources guide — [source `docs/concepts/resources.mdx`](https://raw.githubusercontent.com/modelcontextprotocol/docs/main/docs/concepts/resources.mdx), `main`; no explicit publication date, describes the previous protocol.
13. Anthropic, Getting Started with Local MCP Servers on Claude Desktop — [official help](https://support.claude.com/en/articles/10949351-getting-started-with-local-mcp-servers-on-claude-desktop); the page shows an update on the reading date, 2026-10-24.
14. Cursor, Model Context Protocol — [official reference](https://cursor.com/docs/mcp), Protocol and extension support table; no explicit publication date.
15. Microsoft, MCP developer guide — [official VS Code reference](https://code.visualstudio.com/api/extension-guides/ai/mcp), Resources and Resource templates sections; no explicit publication date.
16. OpenAI Codex, `codex-rs/core/src/tools/handlers/` — [`mcp_resource.rs`](https://raw.githubusercontent.com/openai/codex/main/codex-rs/core/src/tools/handlers/mcp_resource.rs), [`list_mcp_resource_templates.rs`](https://raw.githubusercontent.com/openai/codex/main/codex-rs/core/src/tools/handlers/mcp_resource/list_mcp_resource_templates.rs), [`read_mcp_resource.rs`](https://raw.githubusercontent.com/openai/codex/main/codex-rs/core/src/tools/handlers/mcp_resource/read_mcp_resource.rs), `main`; not tied to the installed CLI version.
17. OpenAI, Model Context Protocol — [current general reference](https://learn.chatgpt.com/docs/extend/mcp?surface=chatgpt), Configure in the ChatGPT desktop app and Configure with the CLI sections; no explicit publication date.
18. OpenAI, Connecting custom MCP servers in ChatGPT — [official help](https://help.openai.com/en/articles/12584461-connecting-custom-mcp-servers-in-chatgpt); the update date was relative when read, with no absolute publication date.
19. OpenAI, Build an MCP server — [official Plugins reference](https://developers.openai.com/plugins/build/mcp-server), Return every listed resource section; no explicit publication date.
20. Google, MCP resource tools — [official Gemini CLI reference](https://geminicli.com/docs/tools/mcp-resources/); no explicit publication date.
21. Spring AI, MCP Server Annotations — [2.0.2 reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html); version in the page heading, no publication date.
22. Spring AI, MCP Server Boot Starter — [2.0.2 reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html); no explicit publication date.
23. Spring AI, STDIO/SSE Server Boot Starter — [reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-stdio-sse-server-boot-starter-docs.html), properties and Resources section; no explicit publication date.
24. MCP Java SDK Core 2.0.1 — [Javadoc `SyncResourceTemplateSpecification`](https://javadoc.io/static/io.modelcontextprotocol.sdk/mcp-core/2.0.1/io/modelcontextprotocol/server/McpServerFeatures.SyncResourceTemplateSpecification.html); version 2.0.1, release date not stated on the page.
25. MCP Java SDK, `McpSchema.java` — [v2.0.1 source](https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/v2.0.1/mcp-core/src/main/java/io/modelcontextprotocol/spec/McpSchema.java), `ResourceLink`, `CallToolResult.Builder`, method constants; release date not separately established.
26. MCP Java SDK, `McpServer.java` — [v2.0.1 source](https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/v2.0.1/mcp-core/src/main/java/io/modelcontextprotocol/server/McpServer.java), resource and template registration; release date not separately established.
27. Spring, Spring AI 2.0.0 GA Available Now — [announcement](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/); published 2026-06-12.
28. MCP Filesystem — [`README.md`](https://github.com/modelcontextprotocol/servers/blob/main/src/filesystem/README.md) and [`server.ts`](https://raw.githubusercontent.com/modelcontextprotocol/servers/main/src/filesystem/server.ts), `main`; not tied to a published package version.
29. MCP Fetch — [`README.md`](https://github.com/modelcontextprotocol/servers/blob/main/src/fetch/README.md) and [`server.py`](https://raw.githubusercontent.com/modelcontextprotocol/servers/main/src/fetch/src/mcp_server_fetch/server.py), `main`; not tied to a published package version.
30. MCP Everything — [`get-resource-links.ts`](https://raw.githubusercontent.com/modelcontextprotocol/servers/main/src/everything/tools/get-resource-links.ts) and [`gzip-file-as-resource.ts`](https://raw.githubusercontent.com/modelcontextprotocol/servers/main/src/everything/tools/gzip-file-as-resource.ts), `main`; demonstration handlers, not tied to a release.
31. Microsoft VS Code, `McpResourceFilesystem.watch` — [source `mcpResourceFilesystem.ts`](https://raw.githubusercontent.com/microsoft/vscode/main/src/vs/workbench/contrib/mcp/common/mcpResourceFilesystem.ts), `main`; not tied to the installed VS Code version.
32. MCP, Resources — [revision 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/server/resources); revision dated 2025-11-25.
