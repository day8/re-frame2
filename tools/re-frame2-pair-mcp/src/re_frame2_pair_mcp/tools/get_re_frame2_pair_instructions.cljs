(ns re-frame2-pair-mcp.tools.get-re-frame2-pair-instructions
  "Tool: get-re-frame2-pair-instructions — agent-onboarding text.

  Mirrors story-mcp's `get-story-instructions`. Returns the
  conventions an agent needs to drive the surface effectively (EDN
  posture, `:origin :pair` tagged mutations, streaming subscribe
  semantics, the wire-boundary cap + dedup + elision pipeline) plus a
  short `## Routing rules` index of which tool to reach for.

  IT DOES NOT ENUMERATE THE TOOLS (rf2-wyza). It used to — a 33-entry
  `## Tool catalogue` that was 75% of the blob and the only section
  that grew with the tool count, leaving 112 tokens of margin under
  the wire cap. That enumeration duplicated the `tools/list`
  descriptors the host already has at handshake, and a miss is
  answered live by the `:unknown-tool` hint, which folds
  `registry/tool-names` into the error. What the descriptors cannot
  carry is CROSS-tool judgement — each one's preference is buried in
  its own ~2,400-char paragraph — so this text keeps only that: the
  global first-contact routing index.

  `instructions-text` ships inline as a single string so the artefact
  is self-contained — no resource read at boot, one MCP frame, zero
  socket bytes. The structural peer in story-mcp
  (`story-instructions-text` in `re-frame.story-mcp.tools.dev`) uses
  the same inline-`(str ...)` shape — and carries no tool catalogue
  either — kept aligned so AI pairs reading both servers see one
  answer to the onboarding-text question."
  (:require [re-frame2-pair-mcp.tools.wire :as wire]))

(def instructions-text
  "Inline onboarding prose. Inline `(str ...)` of `\\n`-glued lines —
  see the ns docstring for the rationale (mirrors story-mcp).

  Adding a tool does NOT oblige an edit here (rf2-wyza): `tools/list`
  and the `:unknown-tool` hint carry the tool set. Edit this string
  when a routing JUDGEMENT changes — a new tool that supersedes an
  older route, or a preference that turned out to be wrong.

  PARSE CONTRACT for `## Routing rules` (guarded by
  `onboarding_routing_test`): inside that section, a backticked
  lowercase-kebab identifier means A REGISTERED TOOL NAME and nothing
  else. Keywords (`:frame`), slashed names (`tools/list`) and plain
  prose are ignored by the parser, so they stay free-form — but do not
  backtick a bare word like `nil` there, or the guard will read it as
  a phantom tool."
  (str
    "re-frame2-pair-mcp agent quick reference.\n"
    "Full spec: tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md.\n"
    "\n"
    "## What this server is\n"
    "\n"
    "An MCP stdio server that pair-programs with a live re-frame2 app\n"
    "over a persistent nREPL socket. The agent host (Claude Code /\n"
    "Cursor / Copilot) launches it as a subprocess; one connection\n"
    "lives for the session; each tool routes through one of the\n"
    "eight Tool-Pair primitives on the runtime side.\n"
    "\n"
    "## Routing rules\n"
    "\n"
    "The `tools/list` descriptors say what each tool does; these say\n"
    "which one to reach for. Behind all six: a typed tool validates its\n"
    "arguments and names its failure, `eval-cljs` does neither — so\n"
    "`eval-cljs` is the last resort, not the default.\n"
    "\n"
    "  1. First contact — `discover-app`, then `orient`. Drill from\n"
    "     there with `list-handlers` for the ids under a kind, then\n"
    "     `handler-meta` for one id's source-coord and body.\n"
    "  2. One app-db value — `get-path`. One subscription — `read-sub`,\n"
    "     which validates the sub-id and returns `:nearest` matches on a\n"
    "     miss where a raw deref returns a silent nil. `snapshot` is a\n"
    "     coarse first look or a bounded subtree, never the default read.\n"
    "  3. What a `dispatch` DID — its own `:cascade-summary`, not a\n"
    "     `watch-epochs` correlation. What it WOULD do —\n"
    "     `dispatch-dry-run`, which runs the full chain, fires no fx and\n"
    "     rolls the app-db back.\n"
    "  4. Rendered output WITH provenance — `read-ui`, which returns the\n"
    "     subtree plus the view-id and source-coord that produced it. A\n"
    "     raw selector read, provenance irrelevant — `read-dom`.\n"
    "  5. Waiting on the app, never a poll loop — `watch-until` for a\n"
    "     predicate over a signal-set, `record` + `read-recording` to\n"
    "     sample while a human drives the UI, `subscribe` to stream,\n"
    "     `tail-build` for a hot reload.\n"
    "  6. Multi-frame — `set-operating-frame` ONCE, rather than a\n"
    "     `:frame` arg on every call; it is the escape from\n"
    "     `:ambiguous-frame`. `get-operating-frame` reports what\n"
    "     targeted ops resolve to. ONE EXCEPTION: `subscribe` takes no\n"
    "     `:frame` argument and the pin does NOT scope it — a pinned\n"
    "     session still streams every frame. Scope a stream with its\n"
    "     filter instead: `{:frame :foo}`.\n"
    "\n"
    "That is the whole routing index — this text does NOT list the tool\n"
    "set. `tools/list` ships every descriptor at handshake, a name that\n"
    "misses returns `:unknown-tool` with the live name list in its hint,\n"
    "and the per-tool reference is the spec named at the top.\n"
    "\n"
    "## EDN posture\n"
    "\n"
    "Every tool returns `{:content [{:type \"text\" :text <edn-string>}]}`.\n"
    "The `:text` slot is `pr-str`-rendered EDN — read with\n"
    "`clojure.edn/read-string` or its target-language peer. JSON-shaped\n"
    "tools/list args are parsed as EDN when sensible (e.g. `:path` accepts\n"
    "an EDN-encoded vector string or a JSON array).\n"
    "\n"
    "## Tagged mutations\n"
    "\n"
    "`dispatch` and `eval-cljs` tag their side-effects with `:origin :pair`\n"
    "(or `:origin :pair-mcp` where the runtime distinguishes) so the agent\n"
    "host's mutations are distinguishable from user-driven events in the\n"
    "epoch ring. Filter your `subscribe` / `watch-epochs` calls on\n"
    "`:origin :pair` to see only what you triggered.\n"
    "\n"
    "## Wire-boundary pipeline\n"
    "\n"
    "Every response passes through (in order): precheck cache → size\n"
    "elision → diff-encode (epochs) → structural dedup → wire-cap. Each\n"
    "stage has a per-tool knob — pass `dedup false` / `elision false` /\n"
    "`max-tokens 0` to bypass the respective stage. Over-cap responses\n"
    "return the `:rf.mcp/overflow` marker; declared-large slots return\n"
    "the `:rf.size/large-elided` marker with a `:handle [:rf.elision/at\n"
    "<path>]` fetch handle.\n"
    "\n"
    "## Streaming subscribe semantics\n"
    "\n"
    "`subscribe` is a long-running tools/call. Each batch of matching\n"
    "events arrives as a `notifications/progress` notification correlated\n"
    "by the call's `progressToken`. The call resolves with a summary when\n"
    "the client cancels or `unsubscribe` fires. Topics: `trace`, `epoch`,\n"
    "`fx`, `error`. Filter vocab is topic-dependent — see the descriptor.\n"
    "Per-tick `:events` vectors are structurally deduped; oversize tick\n"
    "payloads honour the wire-cap.\n"
    "\n"
    "## Session entry-point\n"
    "\n"
    "Call `discover-app` first every session — it verifies the nREPL\n"
    "socket, confirms the `re-frame2-pair.runtime` preload landed, and\n"
    "surfaces warnings (debug disabled, no frames, ambiguous frame,\n"
    "missing source-coord annotation). A health-check before the first\n"
    "real op short-circuits a class of confusing downstream errors.\n"))

(defn get-re-frame2-pair-instructions-tool
  "Return the agent-onboarding text. `conn` and `args` are accepted
  for shape uniformity with the other tool handlers but ignored —
  the text is inline and the call carries no per-request state."
  [_conn _args]
  (js/Promise.resolve
    (wire/ok-text {:ok? true
                   :tool "get-re-frame2-pair-instructions"
                   :text instructions-text})))
