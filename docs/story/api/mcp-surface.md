# MCP surface

Story-MCP exposes a Story catalogue to MCP clients through a JVM stdio
server. It loads your portable registrations and calls Story's public
runtime functions in that process. The separate artifact is
`day8/re-frame2-story-mcp`; Story itself needs no MCP dependency.

## Host execution model — one JVM, no browser bridge

The server's registry and variant frames belong to its JVM. Loading the
same stories in a browser does not connect their live state.

### Two surfaces, one live door

Use Story-MCP for catalogue discovery, headless execution and optional
variant registration. To inspect the running browser's frames, use the
[Pair MCP server](https://github.com/day8/re-frame2/blob/main/tools/re-frame2-pair-mcp/README.md),
which evaluates calls inside that browser's ClojureScript runtime.

`list-substrates` and `read-a11y-violations` need browser-only state.
The JVM server returns `isError true` with
`:rf.error/story-mcp-capability-unavailable` for them.

## Running the server

The server is `re-frame.story-mcp.server`, a stdio process an agent host launches. It reads only the stories loaded into its own JVM, so launch it through an alias in your project's `deps.edn` that requires your stories namespace first:

```clojure
;; deps.edn
{:aliases
 {:story-mcp
  {:extra-deps {day8/re-frame2-story-mcp {:local/root "../re-frame2/tools/story-mcp"}}
   :main-opts  ["-e" "(require 'my-app.stories)"
                "-m" "re-frame.story-mcp.server"]}}}
```

The stories namespace must load on the JVM, so `.cljs` story files stay with the browser host. To run variants rather than only read them, that namespace also installs a substrate, as `(rf/init! re-frame.substrate.plain-atom/adapter)`; without one, `run-variant` and `preview-variant` refuse with `:rf.error/no-adapter-installed`. Keep load-time printing off stdout, which carries the JSON-RPC frames.

The write tools are closed by default. Open them with the `--allow-writes` flag, the JVM property `-Drf.story-mcp.allow-writes=true`, or the environment variable `RF_STORY_MCP_ALLOW_WRITES=true`; the flag beats the property, which beats the variable. The [story-mcp README](https://github.com/day8/re-frame2/blob/main/tools/story-mcp/README.md#loading-your-projects-stories) shows the host entries for VS Code, Claude Code and Cursor.

## The tool registry at a glance

The jar exposes **19 tools** across four categories. Clients read each tool's input schema from the server's `tools/list` response.

| Category | Tools |
|---|---|
| **Dev** (3) | `get-story-instructions`, `preview-variant`, `list-substrates` |
| **Docs** (10) | `list-stories`, `get-story`, `get-variant`, `variant->edn`, `list-tags`, `list-modes`, `list-decorators`, `list-assertions`, `get-docs-markdown`, `explain-variant` |
| **Testing** (4) | `run-variant`, `snapshot-identity`, `read-failures`, `read-a11y-violations` |
| **Write** (2, gated) | `register-variant`, `unregister-variant` |

`run-variant`, `read-failures` and `preview-variant` return the **same unified run-result** the human Story UI reads — a top-level `:status` in `#{:pass :fail :cannot-run :error}`, unified assertion records, `:checks`, and the evidence slots. The status vocabulary matches the shell; `:cannot-run` names capability or evidence the host could not obtain.

## Wire-egress boundary

Story core returns **marks-as-data**: registered bodies and per-frame snapshots travel unchanged across the read primitives, with `:sensitive` / `:large` declarations carried alongside as declarative metadata. The substitution to `:rf/redacted` / `:rf.size/large-elided` happens at the **MCP jar's egress boundary**, not in Story core.

Runtime values and authored metadata are projected differently:

- **Runtime / captured value — path-projected by default.** `:app-db`, `:snapshot`, `:effective-args`, evidence slots and assertion records on `preview-variant` / `run-variant` / `read-failures`, plus `read-a11y-violations`'s `:violations` and `:incomplete`. A value at a declared-`:sensitive` path becomes `:rf/redacted`; a value at a declared-`:large` path becomes the `:rf.size/large-elided` marker. Projection is **path-based**: a value *re-keyed* to a position the classification path cannot reach ships raw, by design. To redact a value a derived tree re-surfaces, classify its app-db path.
- **Author-published static metadata — intentionally public, not scrubbed.** `get-story` / `get-variant` / `variant->edn` bodies, the `list-*` enumerations, `get-docs-markdown`, and the whole of `explain-variant`'s `:explain` map. These are registration-time authoring prose, not runtime or user state; scrubbing them would degrade discovery without protecting a secret.
- **Direct API calls.** An in-process consumer that calls the read primitives directly, without going through the MCP jar, gets real values — so on-box devtool surfaces read the same data unredacted.

Three tools (`preview-variant`, `run-variant`, `read-failures`) carry scalar `:dropped-sensitive` / `:elided-large` indicators alongside their own result slots, each omitted when its count is zero, so an agent can tell that a payload was filtered and by how much.

The one documented opt-out is `--allow-sensitive-reads` at boot plus a per-call `:include-sensitive`. With the boot gate closed — the default — the `:include-sensitive` slot is omitted from the `tools/list` schema entirely and any caller-supplied value is ignored at egress.

Story itself returns real values; redacting them for the wire is the MCP jar's job.

## Public read primitives

The MCP handlers call the registry queries, `variant->edn`,
`run-variant`, `snapshot-identity` and assertion readers in
`re-frame.story`. Direct application/tool integrations can call that
facade too; the [runtime reference](runtime.md) gives exact signatures.
The MCP tool registry is narrower than the facade API.

## Public write primitives

The two MCP write tools are `register-variant` and `unregister-variant`,
calling `reg-variant*` and `unregister!` for variants. They are disabled
by default. The ordinary in-process registration APIs remain available;
the MCP gate controls access through this server.

Registered live variants survive only in that process. Save their EDN
declarations in a loaded stories namespace to keep them after restart.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Catalogue is empty | The server never required your stories | Add the portable namespace to its launch expression. |
| `:rf.error/no-adapter-installed` | The JVM runtime was not initialized | Install the plain-atom adapter in your host bootstrap. |
| Browser variant is absent | The JVM and browser registries are separate | Load its portable declarations or use the browser connection. |
| `:rf.error/story-mcp-capability-unavailable` | A tool needs browser state | Perform that operation through the browser host. |
| Write tools are unavailable | The server's write gate is closed | Enable writes at server boot when you want live registration. |
| Invalid JSON-RPC output | Application bootstrap printed to stdout | Send diagnostic printing to stderr; keep stdout for the protocol. |
