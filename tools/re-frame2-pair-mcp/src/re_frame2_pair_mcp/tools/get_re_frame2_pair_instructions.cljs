(ns re-frame2-pair-mcp.tools.get-re-frame2-pair-instructions
  "Tool: get-re-frame2-pair-instructions (rf2-fnpqg) — agent-onboarding text.

  Mirrors story-mcp's `get-story-instructions`. Returns an inline
  prose summary of re-frame2-pair-mcp's tool catalogue + the conventions an
  agent needs to drive the surface effectively (EDN posture,
  `:origin :pair` tagged mutations, streaming subscribe semantics,
  the wire-boundary cap + dedup + elision pipeline).

  No nREPL round-trip; the text is a `def` baked into the bundle so
  the call costs one MCP frame and zero socket bytes. Hostable agents
  load this at session start to orient before the first real op."
  (:require [re-frame2-pair-mcp.tools.wire :as wire]))

(def instructions-text
  "Inline onboarding prose. Kept here (rather than read from a
  resource) so the compiled `.js` bundle is self-contained and the
  call is a single frame. Edit this string when the catalogue
  changes; the docstring on `re-frame2-pair-mcp.tools/tool-descriptors`
  is the structural peer."
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
    "## Tool catalogue (fourteen tools)\n"
    "\n"
    "  discover-app          — verify nREPL + preloaded runtime; run first.\n"
    "  eval-cljs             — eval a CLJS form; returns the EDN value.\n"
    "  dispatch              — fire an event tagged :origin :pair.\n"
    "  trace-window          — epochs in the last N ms (cursor-paginated).\n"
    "  watch-epochs          — pull-mode poll for matching epochs.\n"
    "  tail-build            — wait for a hot-reload to land.\n"
    "  snapshot              — coarse per-frame mega-read (app-db, subs,\n"
    "                          machines, epochs, traces).\n"
    "  get-path              — read a single value at `path` from a\n"
    "                          frame's app-db.\n"
    "  subscribe             — streaming subscription; emits each batch\n"
    "                          as a notifications/progress.\n"
    "  unsubscribe           — close a streaming subscription.\n"
    "  list-subscriptions    — list active streams + queue stats.\n"
    "  handler-meta          — return :source-coord + :doc + :tags for a\n"
    "                          registered handler (event / sub / fx / cofx /\n"
    "                          view / frame / machine).\n"
    "  list-handlers         — enumerate every registered id under a kind\n"
    "                          (the discovery peer of handler-meta).\n"
    "  get-re-frame2-pair-instructions — this text.\n"
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
