(ns re-frame-pair2-mcp.tools.descriptors-data
  "MCP `tools/list` descriptor data — one `def` per tool.

  Catalogue-shaped leaf: the descriptor maps live here as named
  vars so `tools/registry.cljs` can reference each by symbol (one
  registry entry per tool, descriptor included by name) without
  forcing the registry to inline ~280 lines of descriptor blobs.

  Per rf2-zkca8's carve-out for catalogue-shaped leaves, this file
  is permitted to exceed the standard ≤250-line ceiling — splitting
  it per-tool would force readers to chase across twelve files when
  comparing descriptor shapes.

  Universal knobs (`max-tokens`, `cache`) are spliced via
  `descriptors-knobs/with-budget-knob` and `with-cache-knob` inside
  `descriptors/tool-descriptors-js`, not at the def site. Per-tool
  knobs (limit, cursor, dedup, elision) reach in by name below.

  Each entry carries `:name`, `:description`, `:inputSchema`, and
  `:typicalTokens` (rf2-6sddv) — an informational ballpark of the
  response-payload size in tokens that AI clients use to budget
  calls and pick size-conscious args (`max-tokens`, `cache`,
  `cursor`) without trial-and-error. Hint only; the real cap is
  enforced separately."
  (:require [re-frame-pair2-mcp.tools.descriptors-knobs :as knobs]))

(def discover-app
  {:name "discover-app"
   :description "Verify the shadow-cljs nREPL is reachable, confirm the pair2 runtime preload landed, and report a health summary. Run this first every session. Returns :reason :runtime-not-preloaded when the preload entry is missing."
   :typicalTokens 200
   :inputSchema {:type "object"
                 :properties {:build {:type "string"
                                      :description "shadow-cljs build id (default: app)"}}
                 :additionalProperties false}})

(def eval-cljs
  {:name "eval-cljs"
   :description "Evaluate a ClojureScript form in the connected browser runtime via shadow-cljs's cljs-eval. Returns the EDN value."
   :typicalTokens 500
   :inputSchema {:type "object"
                 :properties {:form  {:type "string" :description "The CLJS form to evaluate."}
                              :build {:type "string" :description "shadow-cljs build id (default: app)"}}
                 :required ["form"]
                 :additionalProperties false}})

(def dispatch
  {:name "dispatch"
   :description (str "Fire a re-frame2 event tagged with :origin :pair. Default mode is queued dispatch. "
                     "Set `sync` for dispatch-sync, `trace` for synchronous dispatch returning the "
                     "assembled :rf/epoch-record. The `event` arg is parsed as EDN server-side (rf2-vflrg) — "
                     "MUST be a vector (e.g. `[:cart/checkout {:reason :user}]`). Non-vector EDN returns "
                     "`:reason :not-an-event-vector`; unreadable input returns `:reason :invalid-event-edn`. "
                     "Host-form source (e.g. `(println :x)`) is rejected — use `eval-cljs` for arbitrary "
                     "evaluation.")
   :typicalTokens 300
   :inputSchema {:type "object"
                 :properties {:event {:type "string" :description "The event vector as EDN, e.g. \"[:cart/checkout]\" or \"[:cart/add {:sku \\\"abc\\\"}]\". MUST be a vector — non-vector EDN and host-form source are rejected."}
                              :sync  {:type "boolean"}
                              :trace {:type "boolean"}
                              :frame {:type "string" :description "Operating frame (e.g. :stories)"}
                              :fx-overrides {:type "object"
                                             :description "Per-call fx redirects, e.g. {:http :stub-http}"}
                              :build {:type "string"}}
                 :required ["event"]
                 :additionalProperties false}})

(def trace-window
  {:name "trace-window"
   :description (str "Return the :rf/epoch-records added in the last N ms for the operating frame. "
                     "Per spec/009 §Privacy this forwarder default-drops items carrying `:sensitive? true` "
                     "at the top level; opt back in with `include-sensitive? true`. Dropped count surfaces "
                     "as `:dropped-sensitive` on the result when non-zero. "
                     "Each epoch's :db-after is diff-encoded against its own :db-before by default (rf2-1wdzp) "
                     "— pass `epochs-mode \"full\"` for the full-pair shape (the time-travel-restore mode) (needed for time-travel restore). "
                     "The epoch vector is structurally deduped by default (rf2-obpa9) — repeated subtrees "
                     "(notably the per-record `:db-before` reference) collapse to a `{:rf.mcp/dedup-table ...}` "
                     "wrapper; the agent host calls `(de-dupe.core/expand cache)` to reconstruct. Pass `dedup false` to skip. "
                     "Cursor pagination (rf2-kbqq3): the response is bounded at `:limit` records (default 50). "
                     "When more remain, `:next-cursor` carries an opaque continuation token and `:has-more? true`; "
                     "pass `cursor` back on the next call to resume. The window's upper bound is sticky across "
                     "pages — fresh epochs landing during pagination don't sneak in mid-iteration. A cursor "
                     "whose epoch-id has aged out of the runtime ring surfaces as `:reason :rf.mcp/cursor-stale`.")
   :typicalTokens 2000
   :inputSchema {:type "object"
                 :properties {:ms    {:type "integer" :description "Window size in milliseconds (default 1000). Sticky across pagination — encoded into the cursor on the first call."}
                              :frame {:type "string"}
                              :limit knobs/limit-property
                              :cursor knobs/cursor-property
                              :epochs-mode {:type "string"
                                            :description "How :db-after rides the wire: \"diff\" (default, intra-record structural diff against :db-before) or \"full\" (complete :db-after snapshot — opt-in for time-travel restore, which needs the verbatim state)."
                                            :enum ["diff" "full"]}
                              :dedup knobs/dedup-property
                              :include-sensitive? {:type "boolean"
                                                   :description "Opt back in to forwarding `:sensitive? true` items. Default false."}
                              :build {:type "string"}}
                 :additionalProperties false}})

(def watch-epochs
  {:name "watch-epochs"
   :description (str "Pull-mode poll: returns the epochs matching `pred` that landed after `since-id`. "
                     "Call repeatedly to live-watch. Predicate keys: :event-id, :event-id-prefix, :effects, "
                     ":touches-path, :sub-ran, :render, :origin, :frame, :timing-ms. "
                     ":timing-ms (rf2-r3azh) is a server-side wall-clock filter — accepts a number (sugar for "
                     "`>= N`) or a comparison string (`\">100\"`, `\"<=50\"`, `\">=100\"`, `\"<200\"`, `\"=42\"`). "
                     "Compares against the cascade's elapsed-ms derived from the `:event/run-start` / "
                     "`:event/run-end` trace pair. The filter rides server-side so non-matching epochs "
                     "never cross the wire — typicalTokens above is the worst case, narrowing on :timing-ms "
                     "(e.g. only :>100ms epochs) shrinks the payload roughly proportional to the match rate. "
                     "Per spec/009 §Privacy this forwarder "
                     "default-drops items carrying `:sensitive? true`; opt back in with `include-sensitive? true`. "
                     "Each epoch's :db-after is diff-encoded against its own :db-before by default (rf2-1wdzp) "
                     "— pass `epochs-mode \"full\"` for the full-pair shape (the time-travel-restore mode). "
                     "The matches vector is structurally deduped by default (rf2-obpa9); pass `dedup false` to skip. "
                     "Cursor pagination (rf2-kbqq3): the matches vector is bounded at `:limit` (default 50). "
                     "When more matches remain, `:next-cursor` is non-nil and `:has-more? true`; pass `cursor` "
                     "back to consume the next page. The `:cursor` arg overrides `:since-id` when both are "
                     "supplied. A cursor whose epoch-id has aged out of the ring surfaces as "
                     "`:reason :rf.mcp/cursor-stale`.")
   :typicalTokens 2000
   :inputSchema {:type "object"
                 :properties {:since-id {:type "string" :description "The last epoch id you've seen (omit to start fresh). Supplanted by :cursor when both are passed."}
                              :pred     {:type "object" :description "Filter map"}
                              :frame    {:type "string"}
                              :limit    knobs/limit-property
                              :cursor   knobs/cursor-property
                              :epochs-mode {:type "string"
                                            :description "How :db-after rides the wire: \"diff\" (default) or \"full\" (legacy, opt-in for time-travel restore)."
                                            :enum ["diff" "full"]}
                              :dedup    knobs/dedup-property
                              :include-sensitive? {:type "boolean"
                                                   :description "Opt back in to forwarding `:sensitive? true` items. Default false."}
                              :build    {:type "string"}}
                 :additionalProperties false}})

(def tail-build
  {:name "tail-build"
   :description "Wait for a hot-reload to land by polling a probe form until its value changes. Returns once changed, or times out."
   :typicalTokens 100
   :inputSchema {:type "object"
                 :properties {:probe   {:type "string" :description "CLJS form whose value should change after the reload"}
                              :wait-ms {:type "integer" :description "Max wait in ms (default 5000)"}
                              :build   {:type "string"}}
                 :additionalProperties false}})

(def snapshot
  {:name "snapshot"
   :description (str "Coarse-grained per-frame state read in one round-trip — the mega-op for investigate-X workflows. "
                     "Returns a map keyed by frame-id whose values carry the requested slices: "
                     ":app-db, :sub-cache, :machines, :epochs, :traces. "
                     "Server-side composition over the existing per-slice runtime readers. "
                     "Prefer this over chaining 5-10 individual reads. "
                     "Lazy-summary default (rf2-u2029): each rich slice in the response is replaced with a "
                     "`{:rf.mcp/summary {:type :map|:vector :keys [...] :count N :bytes ~B}}` marker by "
                     "default — keeps a discovery snapshot under the wire cap by construction. Agents drill "
                     "into the slice they actually need via `mode \"full\"` (every slice expands), per-slice "
                     "`modes {\"app-db\": \"full\"}` (one slice expands), or — for the :app-db slice only — "
                     "the `path` arg (rf2-tygdv: returns the addressed subtree). "
                     "Path slicing (rf2-tygdv): the `:app-db` slice supports a `path` arg (an EDN-encoded "
                     "vector of keys, e.g. \"[:cart :items 0]\"). With `path`, returns the addressed subtree. "
                     "Path-slicing supersedes the slice-level mode for `:app-db`. "
                     "Diff-encoded epochs (rf2-1wdzp): each epoch in the `:epochs` slice has its `:db-after` "
                     "replaced with a structural diff against its own `:db-before` by default — pass "
                     "`epochs-mode \"full\"` for full-pair shape (the time-travel-restore mode, which needs verbatim state). "
                     "Diff-encode runs before the lazy-summary so `bytes` hints reflect post-shrink cost. "
                     "Per spec/009 §Privacy the `:traces` and `:epochs` slices default-drop items carrying "
                     "`:sensitive? true`; opt back in with `include-sensitive? true`. "
                     "Per Tool-Pair §Direct-read privacy posture (rf2-vflrg) the `:app-db` and `:sub-cache` "
                     "slices are routed through `re-frame.core/elide-wire-value` with off-box defaults — "
                     "declared-sensitive paths return the `:rf/redacted` sentinel and large slots return "
                     "the `:rf.size/large-elided` marker; the same `include-sensitive? true` flag opts back "
                     "in to seeing the raw value at sensitive paths. The `:machines` slice passes through "
                     "unchanged — payload redaction there is the `with-redacted` interceptor's job. "
                     "Each frame's `:epochs` slice is structurally deduped (rf2-obpa9) after diff-encoding — "
                     "repeated subtrees (notably the per-record `:db-before` reference) collapse to a "
                     "`{:rf.mcp/dedup-table ...}` wrapper; agent host reconstructs via `de-dupe.core/expand`. "
                     "Pass `dedup false` to skip. "
                     "Size-elision (rf2-urjnc): each frame's `:app-db` slice is run through "
                     "`re-frame.core/elide-wire-value` server-side before crossing the wire — declared / "
                     "schema-`:large?` paths and over-threshold leaves are substituted with a "
                     "`{:rf.size/large-elided {:path [...] :handle [:rf.elision/at <path>] ...}}` marker. "
                     "Agent drills into the handle via `get-path` (or `snapshot {:path ...}` with a "
                     "non-elided sibling subpath). Pass `elision false` to bypass the walk and receive "
                     "the raw value.")
   :typicalTokens 3000
   :inputSchema {:type "object"
                 :properties {:frames  {:description "Frames to snapshot. Pass \"all\" (default) or an array of frame-id strings like [\":rf/default\", \":stories\"]."
                                        :oneOf [{:type "string"}
                                                {:type "array" :items {:type "string"}}]}
                              :include {:type "array"
                                        :description "Slices to include. Defaults to all five. Recognised: app-db, sub-cache, machines, epochs, traces."
                                        :items {:type "string"
                                                :enum ["app-db" "sub-cache" "machines" "epochs" "traces"]}}
                              :path    {:description (str "Path into the :app-db slice. EDN-encoded vector of keys "
                                                          "(e.g. \"[:cart :items 0]\") or a JSON array of segment "
                                                          "strings. When supplied, the :app-db slice in the result "
                                                          "is the subtree at the path. Out-of-range paths surface "
                                                          "as `:path-not-found` per-frame with deepest-valid-prefix "
                                                          "attached. Path-slicing supersedes the slice-level mode "
                                                          "for :app-db. When absent, the :app-db slice respects "
                                                          "the resolved mode (default :summary).")
                                        :oneOf [{:type "string"}
                                                {:type "array" :items {:type "string"}}]}
                              :mode    {:type "string"
                                        :description (str "Global lazy-summary mode (rf2-u2029). "
                                                          "\"summary\" (default) replaces every rich slice "
                                                          "(:app-db when no `path`, :sub-cache, :machines, :epochs, :traces) "
                                                          "with a `{:rf.mcp/summary ...}` marker — top-level keys, "
                                                          "count, and approximate bytes. \"full\" expands every "
                                                          "slice to its raw payload (legacy pre-rf2-u2029 behaviour). "
                                                          "Per-slice override via `modes` takes precedence.")
                                        :enum ["summary" "full"]}
                              :modes   {:type "object"
                                        :description (str "Per-slice mode override (rf2-u2029) — a map "
                                                          "{slice-name: \"summary\"|\"full\"}. Recognised slices: "
                                                          "app-db, sub-cache, machines, epochs, traces. Slices not "
                                                          "listed fall back to the global `mode` arg (default \"summary\"). "
                                                          "Example: `{\"app-db\": \"full\", \"epochs\": \"summary\"}` — "
                                                          "expand the live state, summarise the history.")
                                        :additionalProperties {:type "string"
                                                               :enum ["summary" "full"]}}
                              :epochs-mode {:type "string"
                                            :description "How :db-after rides the wire in the :epochs slice: \"diff\" (default, intra-record structural diff against :db-before) or \"full\" (complete :db-after snapshot — opt-in for time-travel restore, which needs the verbatim state)."
                                            :enum ["diff" "full"]}
                              :dedup    knobs/dedup-property
                              :elision  knobs/elision-property
                              :include-sensitive? {:type "boolean"
                                                   :description (str "Opt back in to BOTH (a) forwarding `:sensitive? true` "
                                                                     "items in the :traces / :epochs slices AND (b) seeing "
                                                                     "the raw value at declared-sensitive paths in the "
                                                                     ":app-db / :sub-cache slices (the walker's "
                                                                     "`:rf.size/include-sensitive?` opt). Default false.")}
                              :build   {:type "string" :description "shadow-cljs build id (default: app)"}}
                 :additionalProperties false}})

(def get-path
  {:name "get-path"
   :description (str "Read a single value at `path` from a frame's app-db. Minimal primitive for "
                     "targeted reads — the agent already knows the path. Server-side `(get-in db path)`; "
                     "only the addressed subtree crosses the wire. Returns "
                     "`{:ok? true :exists? true :path [...] :value <subtree>}` on success or "
                     "`{:ok? false :reason :path-not-found :path [...] :deepest-valid-prefix [...]}` "
                     "when the path doesn't resolve. The deepest-valid-prefix lets the agent re-aim "
                     "without a binary search. Use this when `snapshot`'s summary mode (default) "
                     "tells you which key carries the answer. "
                     "Size-elision (rf2-urjnc): the resolved value is run through "
                     "`re-frame.core/elide-wire-value` server-side — a declared / schema-`:large?` "
                     "slot or an over-threshold leaf returns a `{:rf.size/large-elided ...}` marker "
                     "with a `:handle [:rf.elision/at <path>]` fetch handle, not the raw bytes. Drill "
                     "into a non-elided child by re-calling with a deeper `path`. Pass `elision false` "
                     "to bypass the walk and receive the raw value. "
                     "Privacy (rf2-vflrg, per Tool-Pair §Direct-read privacy posture): declared-sensitive "
                     "paths return the `:rf/redacted` sentinel by default — opt in to seeing the raw "
                     "value at sensitive paths via `include-sensitive? true`.")
   :typicalTokens 500
   :inputSchema {:type "object"
                 :properties {:path  {:description (str "Path into app-db. EDN-encoded vector of keys "
                                                        "(e.g. \"[:cart :items 0 :sku]\") or a JSON array "
                                                        "of segment strings (each parsed as EDN — bare "
                                                        "strings stay as map-key strings).")
                                      :oneOf [{:type "string"}
                                              {:type "array" :items {:type "string"}}]}
                              :frame   {:type "string"
                                        :description "Frame-id (e.g. \":rf/default\"). Defaults to the operating frame."}
                              :elision knobs/elision-property
                              :include-sensitive? {:type "boolean"
                                                   :description (str "Opt in to seeing the raw value at declared-sensitive "
                                                                     "paths (the walker's `:rf.size/include-sensitive?` opt). "
                                                                     "Default false ⇒ sensitive paths return the `:rf/redacted` "
                                                                     "sentinel.")}
                              :build   {:type "string"}}
                 :required ["path"]
                 :additionalProperties false}})

(def subscribe
  {:name "subscribe"
   :description (str "Open a streaming subscription on the trace or epoch bus. Push-mode replacement for watch-epochs. "
                     "Long-running tools/call — emits each batch of matching events as a notifications/progress notification "
                     "(correlated via the call's progressToken), and resolves with a summary when the client cancels or an "
                     "unsubscribe op fires. Topics: 'trace' (raw trace stream), 'epoch' (assembled :rf/epoch-records), "
                     "'fx' (trace stream filtered to :op-type :fx), 'error' (trace stream filtered to :op-type :error). "
                     "Filter vocab depends on topic — :trace/:fx/:error accept the (rf/trace-buffer) filter map "
                     "(:operation :op-type :frame :severity :event-id :handler-id :source :origin :dispatch-id :since-ms :between); "
                     ":epoch accepts the epoch-matches? predicate map (:event-id :event-id-prefix :effects :touches-path "
                     ":sub-ran :render :origin :frame :timing-ms). :timing-ms (rf2-r3azh) is a server-side wall-clock "
                     "filter — number (sugar for `>= N`) or comparison string (`\">100\"`, `\"<=50\"`, …). "
                     "Pass `filter` either as a JSON object or as an EDN-encoded string. "
                     "Per spec/009 §Privacy this forwarder default-drops events carrying `:sensitive? true` at the top "
                     "level; opt back in with `include-sensitive? true`. Dropped count surfaces as `:dropped-sensitive` "
                     "on each progress payload (when non-zero) and the final summary. "
                     "Each progress payload's `:events` vector is structurally deduped by default (rf2-obpa9) — "
                     "shared subtrees across the tick collapse to a `{:rf.mcp/dedup-table ...}` wrapper; "
                     "agent host reconstructs via `(de-dupe.core/expand cache-map)`. Dedup is per-tick, not "
                     "per-stream — each notifications/progress frame carries its own cache, no cross-tick "
                     "references. Pass `dedup false` to skip.")
   :typicalTokens 1000
   :inputSchema {:type "object"
                 :properties {:topic   {:type "string"
                                        :description "Topic name. Required."
                                        :enum ["trace" "epoch" "fx" "error"]}
                              :filter  {:description "Filter map (JSON object) or EDN string. Vocab depends on topic."
                                        :oneOf [{:type "object"}
                                                {:type "string"}]}
                              :max-buffered-events {:type "integer"
                                                    :description "Runtime-side queue cap in EVENTS. Default 500. On overflow the OLDEST events are evicted (drop-oldest FIFO) and reported as `:dropped-events` / `:overflow-reason :max-buffered-events` on the next progress tick. OR-combined with :max-buffered-bytes — whichever trips first evicts."}
                              :max-buffered-bytes  {:type "integer"
                                                    :description "Runtime-side queue cap in BYTES (pr-str char count). Default 5_000_000 (~5 MB). Same drop-oldest policy; reports `:dropped-bytes` / `:overflow-reason :max-buffered-bytes`. Sized to fit the 5,000-token wire-cap posture across a normal poll cadence."}
                              :poll-ms {:type "integer"
                                        :description "Server poll cadence in ms. Default 100."}
                              :max-ms  {:type "integer"
                                        :description "Hard upper-bound on how long the subscription stays open, ms. 0 = unbounded (close on cancel only). Default 0."}
                              :max-events {:type "integer"
                                           :description "Terminate after this many events have been delivered. 0 = unbounded. Default 0."}
                              :dedup    knobs/dedup-property
                              :include-sensitive? {:type "boolean"
                                                   :description "Opt back in to forwarding `:sensitive? true` items. Default false."}
                              :build   {:type "string"}}
                 :required ["topic"]
                 :additionalProperties false}})

(def unsubscribe
  {:name "unsubscribe"
   :description "Close the subscription with the given sub-id. Idempotent — closing an unknown sub-id returns :existed? false."
   :typicalTokens 50
   :inputSchema {:type "object"
                 :properties {:sub-id {:type "string"
                                       :description "The uuid returned by `subscribe`."}
                              :build  {:type "string"}}
                 :required ["sub-id"]
                 :additionalProperties false}})

(def subscription-info
  {:name "subscription-info"
   :description (str "List active streaming subscriptions opened via `subscribe`, with per-sub queue depth, "
                     "drop counts, and overflow-reason — without draining any queues. Diagnostic for "
                     "'what streams are currently open?' and 'is my probe still alive?'. Wraps the "
                     "`re-frame-pair2.runtime/subscription-info` runtime fn directly (no eval-cljs round-trip). "
                     "Returns `{:ok? true :subs [{:id :topic :filter :queue-depth :queue-bytes "
                     ":dropped-events :dropped-bytes :overflow-reason :created-at}]}` — one entry per "
                     "currently-registered subscription. Empty `:subs` vector when no streams are open. "
                     "Optional filters: `topic` (one of `:trace` / `:epoch` / `:fx` / `:error`) narrows to "
                     "a single topic; `sub-id` returns only the matching sub. A non-nil `:overflow-reason` "
                     "indicates the queue has been evicting older events to stay inside its budget — tune "
                     "`max-buffered-events` / `max-buffered-bytes` on the next `subscribe` call.")
   :typicalTokens 500
   :inputSchema {:type "object"
                 :properties {:topic  {:type "string"
                                       :description "Optional filter — only return subs on this topic. One of trace, epoch, fx, error."
                                       :enum ["trace" "epoch" "fx" "error"]}
                              :sub-id {:type "string"
                                       :description "Optional filter — only return the sub with this uuid."}
                              :build  {:type "string"}}
                 :additionalProperties false}})

(def handler-meta
  {:name "handler-meta"
   :description (str "Return the registration-metadata map for a registered "
                     "handler — source-coord (file/line/column/ns), :doc, :tags, "
                     "and any custom slots emitted by the reg-* macro. The wire "
                     "pipeline (rf2-cibp8) decorates the :source-coord map with "
                     "an :rf.source/uri string the AI host renders as a "
                     "clickable jump-to-editor link. "
                     "Use this when you know an id and want to find its "
                     "definition without a wide-authority eval-cljs round-trip "
                     "— `where is :user/login registered?`, `what does sub "
                     ":current-user look like?`, `which file owns the :navigate "
                     "fx?`. Supported kinds: event, sub, fx, cofx, view, frame, "
                     "machine. The `machine` kind routes through "
                     "(rf/machine-meta id) (Spec 005 §Querying machines); the "
                     "other six route through (rf/handler-meta kind id). "
                     "Returns `{:ok? true :kind k :id i ...meta...}` on a hit "
                     "or `{:ok? false :reason :not-registered :kind k :id i}` "
                     "when no slot matches.")
   :typicalTokens 400
   :inputSchema {:type "object"
                 :properties {:kind {:type "string"
                                     :description "Registrar kind. One of event, sub, fx, cofx, view, frame, machine."
                                     :enum ["event" "sub" "fx" "cofx" "view" "frame" "machine"]}
                              :id   {:type "string"
                                     :description (str "EDN-encoded id, e.g. \":user/login\". For "
                                                       "composite-key subs, pass the vector form "
                                                       "as a string, e.g. \"[:rf/composite :x]\".")}
                              :build {:type "string"}}
                 :required ["kind" "id"]
                 :additionalProperties false}})

(def registry-list
  {:name "registry-list"
   :description (str "Return every registered id under a kind. The discovery "
                     "surface — agents call this first to find out what's "
                     "registered, then `handler-meta` to drill into a specific "
                     "id. Supported kinds: event, sub, fx, cofx, view, frame, "
                     "machine. The `machine` kind lists every event handler "
                     "flagged `:rf/machine? true` via (rf/machines); the other "
                     "six lift the id vector off the registrar's per-kind map. "
                     "Returns `{:ok? true :kind k :ids [...] :count n}`. The "
                     "id vector is sorted (string / keyword / symbol ordering) "
                     "so the list shape is stable across calls.")
   :typicalTokens 800
   :inputSchema {:type "object"
                 :properties {:kind {:type "string"
                                     :description "Registrar kind. One of event, sub, fx, cofx, view, frame, machine."
                                     :enum ["event" "sub" "fx" "cofx" "view" "frame" "machine"]}
                              :build {:type "string"}}
                 :required ["kind"]
                 :additionalProperties false}})

(def get-pair2-instructions
  {:name "get-pair2-instructions"
   :description (str "Return the pair2-mcp agent-onboarding text (rf2-fnpqg): tool catalogue, EDN posture, "
                     "tagged-mutation conventions, streaming subscribe semantics, the wire-boundary "
                     "pipeline. Inline prose, no nREPL round-trip — call this at session start to orient "
                     "before the first real op. Mirrors story-mcp's `get-story-instructions`. Returns "
                     "`{:ok? true :tool \"get-pair2-instructions\" :text <string>}` — a single text slot "
                     "the agent host renders verbatim.")
   :typicalTokens 1500
   :inputSchema {:type "object"
                 :properties {}
                 :additionalProperties false}})
