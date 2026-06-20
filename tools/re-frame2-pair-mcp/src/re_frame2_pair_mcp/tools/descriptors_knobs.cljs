(ns re-frame2-pair-mcp.tools.descriptors-knobs
  "Tool-descriptor knob properties — the schema fragments that get
  spliced into per-tool descriptors via the `with-*-knob` composers
  in `descriptors.cljs`.

  Each tool descriptor exposes a stable schema via `tools/list`. A
  handful of knobs are universal (wire-cap, cache) and pinned here so
  every descriptor inherits the same shape via composition rather than
  via copy-paste."
  (:require [re-frame2-pair-mcp.tools.cap :as cap]))

(def max-tokens-property
  {:type        "integer"
   :minimum     0
   :description (str "Wire-boundary token-budget cap (default "
                     cap/default-max-tokens
                     "). Per spec/Principles.md §Tight token budget, "
                     "responses serialising over this estimate are "
                     "replaced with an `{:rf.mcp/overflow ...}` "
                     "marker. Must be >= 0: pass 0 to disable the cap. "
                     "A negative value is rejected with an "
                     "`{:rf.mcp/invalid-arg ...}` error (rf2-5rdit).")})

(def limit-property
  "Per-tool descriptor slot for the `:limit` cursor-pagination knob.
  Applied to surfaces that ship epoch vectors and would otherwise blow
  the wire-cap on a single call: `trace-window` and `watch-epochs`.
  Default 50 — sized to fit the 5K-token cap after diff-encode +
  dedup."
  {:type        "integer"
   :description (str "Maximum number of epoch records in the response "
                     "(default 50). The default is sized to fit the "
                     "5K-token wire-cap (rf2-rvyzy) with diff-encode + "
                     "dedup active. When more records remain, "
                     "`:next-cursor` is non-nil and `:has-more? true`; "
                     "pass the cursor back to fetch the next page.")})

(def cursor-property
  "Per-tool descriptor slot for the opaque `:cursor` continuation token.
  Applied to `trace-window` and `watch-epochs`."
  {:type        "string"
   :description (str "Opaque cursor returned by a previous call's "
                     "`:next-cursor`. Pass back verbatim to fetch the "
                     "next page. A cursor whose epoch-id has aged out "
                     "of the runtime ring surfaces as "
                     "`{:ok? false :reason :rf.mcp/cursor-stale ...}` — "
                     "drop the cursor and restart, or widen the window.")})

(def dedup-property
  "Per-tool descriptor slot for the `:dedup` opt-out.
  Applied to surfaces that ship epoch slices (`snapshot`,
  `trace-window`, `watch-epochs`) and to the `subscribe` streaming
  channel — the surfaces where repeated subtrees dominate the wire
  cost. Default `true`."
  {:type        "boolean"
   :description (str "Apply structural dedup (day8/de-dupe) to the "
                     "epoch slice / event vector before the wire-cap "
                     "check. Default true. When deduped, the slot is "
                     "wrapped as `{:rf.mcp/dedup-table <cache-map>}` "
                     "and the agent host reconstructs via "
                     "`(de-dupe.core/expand cache-map)`. Pass false "
                     "to skip dedup — useful for ad-hoc reads when "
                     "the agent host hasn't been taught to call "
                     "`expand`.")})

(def elision-property
  "Per-tool descriptor slot for the `:elision` opt-out.
  Applied to every surface that egresses an `:app-db`-rooted VALUE through
  the per-slot walker: the direct-read app-db readers (`snapshot`,
  `get-path`, `read-sub`), `list-subscriptions :include-values`' per-sub
  `:value`, the streaming `subscribe` payload values,
  and the signal-recorder sample values — `record`'s `:app-db` / `:sub`
  samples and `watch-until`'s `:sample` / `:last-sample` —
  surfaces where a declared-`:large?` slot or a declared-`:sensitive?` leaf
  would otherwise ride off-box verbatim. Default `true`. (The pull-mode
  epoch tools — `trace-window`, `watch-epochs`, and `dispatch`'s
  `:trace` / `:settle` modes — egress whole records via `projected-record`,
  not this per-slot walker, so they have no `:elision` knob; their
  `:include-sensitive` arg governs the app-db sensitive axis of that
  projection instead.)

  NOTE: this is the prose source of truth for which surfaces walk per-slot;
  each tool declares its own inline `:elision` property in
  `descriptors-data.cljs` rather than referencing this var, so keep the two
  in sync (the `descriptor-elision-knob-parity` regression test pins it)."
  {:type        "boolean"
   :description (str "Apply the size-elision walker "
                     "(`re-frame.core/elide-wire-value`, rf2-v9tw2) "
                     "to the egressed app-db value server-side, before the "
                     "EDN crosses the wire. Default true. "
                     "Schema-driven `:large? true` slots get "
                     "substituted with a "
                     "`{:rf.size/large-elided {:path [...] :bytes N "
                     ":type ... :handle [:rf.elision/at <path>]}}` "
                     "marker; the agent re-fetches via `get-path` "
                     "using the handle's path. Schemas are the only "
                     "nomination path — there is no runtime "
                     "declaration API for size. This is the SIZE "
                     "axis only (EP-0015 §10): false overlays "
                     "`include-large? true` on the surface's "
                     "`:rf.egress/off-box-tool` profile floor so "
                     "large slots ride verbatim, but declared-"
                     "sensitive slots STILL redact to `:rf/redacted`. "
                     "Seeing raw sensitive values needs the separate "
                     "`include-sensitive true` opt-in, honoured only "
                     "under `--allow-sensitive-reads`; the opt-ins "
                     "thread through the walk rather than bypassing "
                     "it.")})

(def cache-property
  "Per-tool descriptor slot for the `:cache` opt-in.
  Applied to read-tool descriptors via `with-cache-knob`. Default
  `false` — opt-in until the agent host has been taught the
  `:rf.mcp/cache-hit` marker shape."
  {:type        "boolean"
   :description (str "Consult the per-session response cache. Default "
                     "false. When true and the result's hash matches "
                     "the prior call for this (tool, args), the full "
                     "payload is replaced with a "
                     "`{:rf.mcp/cache-hit {:hash <h> "
                     ":unchanged-since <ms> :tool <t> :hint <s>}}` "
                     "marker — the agent host already has the byte-"
                     "identical payload from the prior call. Cache is "
                     "an 8-slot LRU keyed by (tool, args-fingerprint); "
                     "lifetime is the MCP server process (= one "
                     "session per persistent-socket principle). Read "
                     "tools only (snapshot, get-path, trace-window, "
                     "watch-epochs, discover-app); action tools "
                     "(dispatch, eval-cljs, tail-build) and streaming "
                     "tools (subscribe) bypass.")})

;; `with-budget-knob` and `with-cache-knob` splicers live in
;; `descriptors.cljs` — they consume `registry/cacheable?` and the
;; descriptor data, so siting them at the assembly boundary keeps
;; this ns property-data-only and breaks the otherwise-circular
;; descriptors-knobs ↔ registry require chain.
