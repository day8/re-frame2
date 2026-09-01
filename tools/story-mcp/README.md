# tools/story-mcp/

`day8/re-frame2-story-mcp` is the MCP (Model Context Protocol) agent
surface for re-frame2-story. Its design contract is
[`spec/`](./spec/).

## What it is

A JVM-side stdio JSON-RPC server that exposes Story's read and gated
write surface as MCP tools. Agent hosts launch the server as a
subprocess, perform the `initialize` handshake, then call tools such as
`list-stories`, `run-variant`, `snapshot-identity`, and
`get-story-instructions`.

The handlers call Story's public API in the same JVM process. This
artefact does not attach to an app through nREPL and cannot dereference
state in a browser heap. A JVM launch therefore sees the Story
registrations loaded into that JVM; CLJS-only surfaces such as registered
render substrates and the browser a11y-panel atom return an explicit
empty result.

## What it isn't

- Not an IDE plugin. Agent hosts (for example Claude Code) bring the MCP
  client side; this artefact is the server.
- Not part of Story's authoring runtime. It reads from
  `re-frame.story`'s public query API in the same runtime and dispatches
  to its public runtime functions; nothing here registers new framework
  primitives.
- Not reachable from production CLJS bundles. Per
  [`tools/README.md`](../README.md) the dependency arrow flows
  tool → implementation; this jar is on a separate classpath root.

## Quick start

```bash
# Run the server (stdio transport). The agent host typically invokes
# this for you; you rarely run it by hand.
cd tools/story-mcp
clojure -M -m re-frame.story-mcp.server

# Open the write surface (defaults to off):
clojure -M -m re-frame.story-mcp.server --allow-writes
# or
RF_STORY_MCP_ALLOW_WRITES=true clojure -M -m re-frame.story-mcp.server
# or
clojure -J-Drf.story-mcp.allow-writes=true -M -m re-frame.story-mcp.server
```

A bare launch has canonical vocabulary (tags, assertions, modes) but
ZERO project stories — see the next section for the launch that
preloads yours.

## Loading your project's stories

Story-MCP is a same-JVM adapter: every catalogue read and run operates
on the Story registry inside the server's own process, so your
registration namespaces must be loaded before the stdio loop starts.
`clojure.main` already sequences this — `-e` init-opts run in order,
before `-m` — so the golden path is one alias in **your project's**
deps.edn whose `:main-opts` require your story namespaces and then hand
over to the server:

```clojure
;; deps.edn — consumer project root
{:aliases
 {:story-mcp
  {:extra-deps {day8/re-frame2-story-mcp {:mvn/version "..."}}
   :main-opts  ["-e" "(require 'app.stories)"   ; your registrations load here,
                "-m" "re-frame.story-mcp.server"]}}} ; then the server takes stdio
```

and an MCP-host entry. The property the entry must establish: **the
server starts with your project root as its working directory** — that
is where `clojure -M:story-mcp` finds the deps.edn carrying the alias
and your `:paths`. Agent hosts frequently launch MCP subprocesses from
somewhere else, even when the config file itself lives in the project
root, so pin the directory explicitly (the SDK witness below pins it
the same way, through its transport's `cwd`). Where the host entry has
a working-directory field, use it — VS Code's project-scoped
`.vscode/mcp.json` documents `cwd` and the `${workspaceFolder}`
variable:

```json
{"servers":
 {"story": {"type": "stdio", "command": "clojure",
            "args": ["-M:story-mcp"], "cwd": "${workspaceFolder}"}}}
```

Claude Code's project-scoped `.mcp.json` and Cursor's
`.cursor/mcp.json` document no `cwd` field, so there the command itself
must establish the directory (swap `sh -c` for your shell on Windows).
Claude Code sets `CLAUDE_PROJECT_DIR` (the project root) in the
server's environment and expands `${VAR}` in `command`/`args`:

```json
{"mcpServers":
 {"story": {"command": "sh",
            "args": ["-c", "cd \"${CLAUDE_PROJECT_DIR}\" && exec clojure -M:story-mcp"]}}}
```

Cursor documents no equivalent project-root variable (entries carry
only `command`/`args`/`env`/`envFile`), so write the absolute project
path into that `cd` yourself. From any other working directory
`clojure` cannot see your project's deps.edn — neither the alias nor
your stories are reachable.

Repeat the `-e` form (or require several namespaces in one) as needed;
init-opts run in command order. Add `--allow-writes` to the host's
`args` only when you want the author/refine loop. A missing or throwing
namespace aborts the launch loudly — the ordinary `require` failure on
stderr and a non-zero exit — so the server never comes up over a
silently empty project registry.

Two rules the required namespaces must obey:

- **JVM-loadable.** They must be CLJ/CLJC-loadable in this headless
  JVM. Hicasso-substrate stories qualify (`:component` is a view-id
  keyword; the body is pure data). Reagent/UIx `.cljs` story files are
  browser-side registrations: a running browser's CLJS registry is
  reached through re-frame2-pair's `eval-cljs`
  ([`skills/re-frame2-pair/references/stories.md`](../../skills/re-frame2-pair/references/stories.md)),
  never through this server.
- **stdout is the wire.** The stdio loop owns stdout for JSON-RPC
  frames, so keep load-time printing off stdout (use `*err*`); a stray
  `println` in a required namespace corrupts the handshake.

The end-to-end witness for this path is
[`tools/mcp-conformance/test/end-to-end-project-stories.cjs`](../mcp-conformance/test/end-to-end-project-stories.cjs),
driven from the consumer-shaped fixture project at
[`tools/mcp-conformance/test/fixtures/project-stories/`](../mcp-conformance/test/fixtures/project-stories/).

Tests:

```bash
cd tools/story-mcp
clojure -M:test
```

## Where the depth lives

Per the per-tool spec-folder convention in
[`tools/README.md`](../README.md), the substantive contract for
this jar is decomposed into [`spec/`](./spec/):

| File | Covers |
|---|---|
| [`spec/000-Vision.md`](./spec/000-Vision.md) | What this jar is, why it's separate from Story. |
| [`spec/001-Wire-Protocol.md`](./spec/001-Wire-Protocol.md) | JSON-RPC 2.0 over stdio; `initialize`; `tools/list`; `tools/call`; protocol-version pin. |
| [`spec/002-Tool-Registry.md`](./spec/002-Tool-Registry.md) | The 19 tools across Dev / Docs / Testing / Write categories. |
| [`spec/003-Write-Surface-Gating.md`](./spec/003-Write-Surface-Gating.md) | The `allow-writes?` config; what's gated; how the gate fails. |
| [`spec/API.md`](./spec/API.md) | Consolidated tool surface (each tool's input/output schemas). |
| [`spec/DESIGN-RATIONALE.md`](./spec/DESIGN-RATIONALE.md) | Why Cheshire over data.json; why stage-marker is independent; why protocol-version pinned. |

The 4 categories, at a glance:

- **Dev** (3) — `get-story-instructions`, `preview-variant`, `list-substrates`.
- **Docs** (10) — `list-stories`, `get-story`, `get-variant`, `list-tags`,
  `list-modes`, `list-decorators`, `list-assertions`, `variant->edn`,
  `explain-variant`, `get-docs-markdown`.
- **Testing** (4) — `run-variant`, `snapshot-identity`, `read-a11y-violations`,
  `read-failures`.
- **Write** (2, gated) — `register-variant`, `unregister-variant`.

The former blocking recorder bridge `record-as-variant` was retired
(rf2-5saz7; see [`spec/002-Tool-Registry.md`](./spec/002-Tool-Registry.md)
§What's NOT in the registry); interactive canvas recording is performed
through Pair in the attached CLJS runtime.

## File layout

```
tools/story-mcp/
├── deps.edn                                      ; coord day8/re-frame2-story-mcp
├── README.md                                     ; this file
├── spec/                                         ; the contract; see above
└── src/re_frame/story_mcp/
    ├── config.cljc                               ; protocol-version + allow-writes? gate
    ├── protocol.cljc                             ; JSON-RPC envelope + frame I/O
    ├── server.cljc                               ; dispatcher + -main + run-loop
    └── tools/                                    ; tool implementations
        ├── wire_pipeline.cljc                    ; invoke-tool dispatcher + token-cap + dedup egress
        ├── cursor.cljc                           ; Docs list-* pagination (consumes mcp-base.cursor)
        ├── registry.cljc                         ; tool-registry + descriptors + by-name
        ├── result.cljc                           ; result-envelope builders (pr-edn, text-result, error-result)
        ├── args.cljc                             ; arg readers + bounded-allowlist coercions (with-variant, read-run-opts, include-sensitive?)
        ├── cljs_resolve.cljc                     ; cross-platform CLJS var resolution (registered-substrates)
        ├── lifecycle.cljc                        ; shared blocking run + error normalization
        ├── egress.cljc                           ; wire-egress scrubbers (elide-app-db, scrub-assertions+count, scrub-rendered)
        ├── schemas.cljc                          ; recurring JSON-schema fragments
        ├── dev.cljc                              ; get-story-instructions, preview-variant, list-substrates
        ├── docs.cljc                             ; list-stories, get-story, get-variant, list-tags, list-modes, list-decorators, list-assertions, variant->edn, explain-variant, get-docs-markdown
        ├── testing.cljc                          ; run-variant, snapshot-identity, read-a11y-violations, read-failures
        └── write.cljc                            ; gated: register-variant, unregister-variant
└── test/
    ├── fixtures/tool-names.json                  ; canonical tool-name list (shared JVM + Node fixture)
    ├── stdio-roundtrip.js                        ; Node stdio JSON-RPC roundtrip (initialize → tools/list → tools/call)
    └── re_frame/story_mcp/
        ├── protocol_test.clj                     ; wire-format coverage
        ├── tools_test.clj                        ; per-tool semantics + dispatcher + run-loop
        ├── run_result_roundtrip_test.clj         ; unified run-result wire round-trip
        └── tools/
            ├── cursor_result_test.clj            ; pagination cursor mint / deref / staleness
            └── dedup_test.clj                    ; wire-boundary structural-dedup eligibility + shape
```

## See also

- [`spec/`](./spec/) — this jar's contract.
- [`tools/story/spec/006-MCP-Surface.md`](../story/spec/006-MCP-Surface.md) —
  Story's side of the boundary.
- [`tools/README.md`](../README.md) — per-tool jar convention + bundle
  isolation.
- [`spec/007-Stories.md`](../../spec/007-Stories.md) — the spec the
  Story runtime implements (this jar is one of its consumers).
- [`spec/Tool-Pair.md`](../../spec/Tool-Pair.md) — the runtime contract
  for pair-shaped AI tools (Story-MCP follows it implicitly via the
  Story public surface).
