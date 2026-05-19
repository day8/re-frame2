(ns re-frame2-pair-mcp.tools.descriptors-data
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

  Each entry carries `:name`, `:description`, `:inputSchema`,
  `:outputSchema` (rf2-3l3be — describes the `:structuredContent`
  payload shape; agent hosts use it to validate the result client-
  side), and `:typicalTokens` (rf2-6sddv) — an informational
  ballpark of the response-payload size in tokens that AI clients
  use to budget calls and pick size-conscious args (`max-tokens`,
  `cache`, `cursor`) without trial-and-error. Hint only; the real
  cap is enforced separately."
  (:require [re-frame2-pair-mcp.tools.descriptors-knobs :as knobs]))

;; ---------------------------------------------------------------------------
;; Recurring outputSchema fragments (rf2-3l3be).
;;
;; The `:structuredContent` payload (rf2-hj3pi) is `clj->js` of the
;; EDN payload — keywords become string keys (`:ok?` → `"ok?"`). Tool
;; results split into two coarse families:
;;
;;   1. Envelope shape — `{:ok? bool ...}` with `:reason` keyword on
;;      the error path. Used by ~every tool. We declare the contract
;;      bones (`ok?`, optional `reason`) and accept tool-specific
;;      slots via `additionalProperties: true` rather than enumerating
;;      every per-tool slot (that's per-tool prose in the catalogue,
;;      not the schema's job).
;;
;;   2. Wire-bounded markers — `{:rf.mcp/cache-hit ...}` and
;;      `{:rf.mcp/overflow ...}`. These envelopes replace a tool's
;;      result when the cache step or cap step fires. The schema is
;;      a `oneOf` between the tool's normal envelope and the marker
;;      envelopes — agent hosts that match on `:rf.mcp/*` markers
;;      see them as a valid alternative.
;; ---------------------------------------------------------------------------

(def ^:private result-envelope
  "Generic success/error envelope schema — every tool's
  `:structuredContent` is at least this shape: a JS object with an
  optional `:ok?` boolean and optional `:reason` keyword (as string)
  on the error path. Tool-specific slots ride on
  `additionalProperties: true` per the catalogue prose."
  {:type "object"
   :additionalProperties true
   :properties {"ok?"    {:type "boolean"
                           :description "True on success; false on error (with :reason)."}
                "reason" {:type "string"
                           :description "Error reason keyword (as a string). Present iff :ok? false."}}})

(def ^:private rf-mcp-cache-hit-schema
  "Wire-bounded marker envelope — the cache-hit replacement (rf2-3rt1f).
  Replaces a tool's result on a per-session cache hit."
  {:type "object"
   :additionalProperties true
   :properties {"rf.mcp/cache-hit" {:type "object" :additionalProperties true}}})

(def ^:private rf-mcp-overflow-schema
  "Wire-bounded marker envelope — the overflow replacement (rf2-rvyzy).
  Replaces a tool's result when the wire-boundary token cap fires."
  {:type "object"
   :additionalProperties true
   :properties {"rf.mcp/overflow" {:type "object" :additionalProperties true}}})

(def ^:private envelope-or-marker
  "Default outputSchema — a `oneOf` of the tool's normal envelope
  and the two wire-bounded marker envelopes (`:rf.mcp/cache-hit` for
  cacheable tools that hit; `:rf.mcp/overflow` for any tool whose
  result exceeds the per-call cap). Read tools get this shape; the
  shape is permissive (`additionalProperties: true`) so per-tool
  slots ride."
  {:oneOf [result-envelope
           rf-mcp-cache-hit-schema
           rf-mcp-overflow-schema]
   :description (str "Result envelope: success / error map carrying :ok? + tool-specific slots. "
                     "Wire-bounded markers (:rf.mcp/cache-hit, :rf.mcp/overflow) replace the "
                     "tool's normal envelope when the cache or cap step fires; the agent host "
                     "pattern-matches on the marker key. See "
                     "spec/003-Tool-Catalogue.md §Universal: per-session response cache + "
                     "§Universal: wire-boundary token cap.")})

(def ^:private streaming-final-summary
  "Streaming-tool final-summary envelope (subscribe). The progress
  notifications it emits along the way carry their own shape
  documented in spec/003-Tool-Catalogue.md §subscribe; the
  `tools/call` result itself is just the termination summary."
  {:type "object"
   :additionalProperties true
   :properties {"ok?"             {:type "boolean"}
                "sub-id"          {:type "string"
                                    :description "uuid of the closed subscription"}
                "delivered"       {:type "integer"}
                "dropped-events"  {:type "integer"}
                "dropped-bytes"   {:type "integer"}
                "ticks"           {:type "integer"}
                "reason"          {:type "string"
                                    :description "termination reason keyword (as a string)"}}})

(def discover-app
  {:name "discover-app"
   :description (str "Verify the shadow-cljs nREPL is reachable, confirm the re-frame2-pair runtime preload landed, and report a health summary. Run this first every session. Returns :reason :runtime-not-preloaded when the preload entry is missing. "
                     "Examples: "
                     "1. Default build: {} -> {:ok? true :debug-enabled? true :frames [:rf/default] :coord-annotation-enabled? true :build-id :app}. "
                     "2. Named build: {:build \"app\"} -> same shape against the named build. "
                     "3. Preload missing: {} -> {:ok? false :reason :runtime-not-preloaded :hint \"...\"}.")
   :typicalTokens 200
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:build {:type "string"
                                      :description "shadow-cljs build id (default: app)"}}
                 :additionalProperties false}})

(def eval-cljs
  {:name "eval-cljs"
   :description (str "Evaluate a ClojureScript form in the connected browser runtime via shadow-cljs's cljs-eval. Returns the EDN value. "
                     "Examples: "
                     "1. Read a sub: {:form \"@(re-frame.core/subscribe [:current-user])\"} -> {:ok? true :value {:id 42 :name \"Ada\"}}. "
                     "2. Inspect a global: {:form \"(keys js/window)\"} -> {:ok? true :value [\"document\" ...]}. "
                     "3. Gate closed: any args -> {:ok? false :reason :rf.error/eval-cljs-disabled} when launched without --allow-eval.")
   :typicalTokens 500
   :outputSchema envelope-or-marker
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
                     "evaluation. "
                     "Examples: "
                     "1. Fire-and-forget: {:event \"[:cart/checkout]\"} -> {:ok? true :mode :queued}. "
                     "2. Trace mode (get the assembled epoch back): {:event \"[:cart/add {:sku \\\"x\\\"}]\" :trace true} -> {:ok? true :mode :trace :epoch {:rf/epoch-id ... :event-id :cart/add :db-after ... :effects [...]}}. "
                     "3. Bad event shape: {:event \"42\"} -> {:ok? false :reason :not-an-event-vector :event-edn 42}.")
   :typicalTokens 300
   :outputSchema envelope-or-marker
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
                     "at the top level; opt back in with `include-sensitive true`. Dropped count surfaces "
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
                     "whose epoch-id has aged out of the runtime ring surfaces as `:reason :rf.mcp/cursor-stale`. "
                     "Examples: "
                     "1. Last second on default frame: {} -> {:ok? true :window-ms 1000 :count 3 :epochs [...] :has-more? false}. "
                     "2. Larger window, paginated: {:ms 5000 :limit 20} -> {:ok? true :count 20 :has-more? true :next-cursor \"<b64>\"}; pass back as {:cursor \"<b64>\"} to get the next page. "
                     "3. Stale cursor: {:cursor \"<aged-out-b64>\"} -> {:ok? false :reason :rf.mcp/cursor-stale :head-id ... :hint \"drop cursor and restart\"}.")
   :typicalTokens 2000
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:ms    {:type "integer" :description "Window size in milliseconds (default 1000). Sticky across pagination — encoded into the cursor on the first call."}
                              :frame {:type "string"}
                              :limit knobs/limit-property
                              :cursor knobs/cursor-property
                              :epochs-mode {:type "string"
                                            :description "How :db-after rides the wire: \"diff\" (default, intra-record structural diff against :db-before) or \"full\" (complete :db-after snapshot — opt-in for time-travel restore, which needs the verbatim state)."
                                            :enum ["diff" "full"]}
                              :dedup knobs/dedup-property
                              :include-sensitive {:type "boolean"
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
                     "default-drops items carrying `:sensitive? true`; opt back in with `include-sensitive true`. "
                     "Each epoch's :db-after is diff-encoded against its own :db-before by default (rf2-1wdzp) "
                     "— pass `epochs-mode \"full\"` for the full-pair shape (the time-travel-restore mode). "
                     "The matches vector is structurally deduped by default (rf2-obpa9); pass `dedup false` to skip. "
                     "Cursor pagination (rf2-kbqq3): the matches vector is bounded at `:limit` (default 50). "
                     "When more matches remain, `:next-cursor` is non-nil and `:has-more? true`; pass `cursor` "
                     "back to consume the next page. The `:cursor` arg overrides `:since-id` when both are "
                     "supplied. A cursor whose epoch-id has aged out of the ring surfaces as "
                     "`:reason :rf.mcp/cursor-stale`. "
                     "Examples: "
                     "1. First poll for a specific event: {:pred {:event-id :cart/checkout}} -> {:ok? true :matches [...] :count 1 :head-id \"...\"}. "
                     "2. Resume after last seen id: {:since-id \"epoch-42\" :pred {:effects :http}} -> {:ok? true :matches [...] :head-id \"epoch-47\"}. "
                     "3. Slow-cascade probe: {:pred {:timing-ms \">100\"}} -> {:ok? true :matches [{:rf/epoch-id ... :elapsed-ms 142}]}.")
   :typicalTokens 2000
   :outputSchema envelope-or-marker
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
                              :include-sensitive {:type "boolean"
                                                   :description "Opt back in to forwarding `:sensitive? true` items. Default false."}
                              :build    {:type "string"}}
                 :additionalProperties false}})

(def tail-build
  {:name "tail-build"
   :description (str "Wait for a hot-reload to land by polling a probe form until its value changes. Returns once changed, or times out. "
                     "Examples: "
                     "1. Default 300ms soft delay: {} -> {:ok? true :t 312 :soft? true}. "
                     "2. Probe for a recompile signal: {:probe \"(rand)\" :wait-ms 10000} -> {:ok? true :t 1240 :soft? false}. "
                     "3. Timed out: {:probe \"my.app/build-marker\" :wait-ms 500} -> {:ok? false :reason :timed-out}.")
   :typicalTokens 100
   :outputSchema envelope-or-marker
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
                     "`:sensitive? true`; opt back in with `include-sensitive true`. "
                     "Per Tool-Pair §Direct-read privacy posture (rf2-vflrg) the `:app-db` and `:sub-cache` "
                     "slices are routed through `re-frame.core/elide-wire-value` with off-box defaults — "
                     "declared-sensitive paths return the `:rf/redacted` sentinel and large slots return "
                     "the `:rf.size/large-elided` marker; the same `include-sensitive true` flag opts back "
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
                     "the raw value. "
                     "Examples: "
                     "1. Discovery snapshot (summaries only): {:frames \"all\"} -> {:ok? true :mode :summary :snapshot {:rf/default {:app-db {:rf.mcp/summary {:type :map :keys [...] :count N :bytes ~B}} :sub-cache {...} :machines {...} :epochs {...} :traces {...}}}}. "
                     "2. Drill into one slice: {:frames [\":rf/default\"] :include [\"app-db\"] :path \"[:cart :items]\"} -> {:ok? true :mode :path-sliced :path [:cart :items] :snapshot {:rf/default {:app-db [{:sku \"x\" :qty 2}]}}}. "
                     "3. Full expansion (legacy shape): {:frames \"all\" :mode \"full\"} -> {:ok? true :mode :full :snapshot {:rf/default {:app-db {...} :epochs [...]}}}.")
   :typicalTokens 3000
   :outputSchema envelope-or-marker
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
                              :include-sensitive {:type "boolean"
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
                     "value at sensitive paths via `include-sensitive true`. "
                     "Examples: "
                     "1. Hit: {:path \"[:cart :items 0 :sku]\"} -> {:ok? true :exists? true :path [:cart :items 0 :sku] :value \"sku-abc\"}. "
                     "2. Path-not-found: {:path \"[:cart :items 99]\"} -> {:ok? false :reason :path-not-found :path [:cart :items 99] :deepest-valid-prefix [:cart :items]}. "
                     "3. Large slot elided: {:path \"[:big :payload]\"} -> {:ok? true :exists? true :value {:rf.size/large-elided {:path [:big :payload] :bytes 12345 :type :map :reason :schema :handle [:rf.elision/at [:big :payload]]}}}.")
   :typicalTokens 500
   :outputSchema envelope-or-marker
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
                              :include-sensitive {:type "boolean"
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
                     "level; opt back in with `include-sensitive true`. Dropped count surfaces as `:dropped-sensitive` "
                     "on each progress payload (when non-zero) and the final summary. "
                     "Each progress payload's `:events` vector is structurally deduped by default (rf2-obpa9) — "
                     "shared subtrees across the tick collapse to a `{:rf.mcp/dedup-table ...}` wrapper; "
                     "agent host reconstructs via `(de-dupe.core/expand cache-map)`. Dedup is per-tick, not "
                     "per-stream — each notifications/progress frame carries its own cache, no cross-tick "
                     "references. Pass `dedup false` to skip. "
                     "Examples: "
                     "1. Stream every epoch: {:topic \"epoch\"} -> progress ticks {:sub-id \"<uuid>\" :events [...]} until cancel; final summary {:ok? true :sub-id \"<uuid>\" :delivered N :ticks K :reason :aborted}. "
                     "2. Filtered fx stream: {:topic \"fx\" :filter {:event-id :cart/checkout}} -> ticks only for checkout-driven fx; ends with {:ok? true :delivered N :reason :aborted}. "
                     "3. Time-bounded probe: {:topic \"error\" :max-ms 30000 :max-events 100} -> closes after 30s or 100 errors, whichever first; {:ok? true :delivered K :reason :max-ms-reached}.")
   :typicalTokens 1000
   :outputSchema streaming-final-summary
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
                              :include-sensitive {:type "boolean"
                                                   :description "Opt back in to forwarding `:sensitive? true` items. Default false."}
                              :build   {:type "string"}}
                 :required ["topic"]
                 :additionalProperties false}})

(def unsubscribe
  {:name "unsubscribe"
   :description (str "Close the subscription with the given sub-id. Idempotent — closing an unknown sub-id returns :existed? false. "
                     "Examples: "
                     "1. Live close: {:sub-id \"abc-123\"} -> {:ok? true :sub-id \"abc-123\" :existed? true}. "
                     "2. Already-closed (idempotent): {:sub-id \"abc-123\"} -> {:ok? true :sub-id \"abc-123\" :existed? false}. "
                     "3. Unknown id: {:sub-id \"never-was\"} -> {:ok? true :sub-id \"never-was\" :existed? false}.")
   :typicalTokens 50
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:sub-id {:type "string"
                                       :description "The uuid returned by `subscribe`."}
                              :build  {:type "string"}}
                 :required ["sub-id"]
                 :additionalProperties false}})

(def list-subscriptions
  {:name "list-subscriptions"
   :description (str "List active streaming subscriptions opened via `subscribe`, with per-sub queue depth, "
                     "drop counts, and overflow-reason — without draining any queues. Diagnostic for "
                     "'what streams are currently open?' and 'is my probe still alive?'. Wraps the "
                     "`re-frame2-pair.runtime/subscription-info` runtime fn directly (no eval-cljs round-trip). "
                     "Returns `{:ok? true :subs [{:id :topic :filter :queue-depth :queue-bytes "
                     ":dropped-events :dropped-bytes :overflow-reason :created-at}]}` — one entry per "
                     "currently-registered subscription. Empty `:subs` vector when no streams are open. "
                     "Optional filters: `topic` (one of `:trace` / `:epoch` / `:fx` / `:error`) narrows to "
                     "a single topic; `sub-id` returns only the matching sub. A non-nil `:overflow-reason` "
                     "indicates the queue has been evicting older events to stay inside its budget — tune "
                     "`max-buffered-events` / `max-buffered-bytes` on the next `subscribe` call. "
                     "Examples: "
                     "1. No streams open: {} -> {:ok? true :subs []}. "
                     "2. Healthy probe alive: {:sub-id \"abc-123\"} -> {:ok? true :subs [{:id \"abc-123\" :topic :epoch :filter {} :queue-depth 0 :queue-bytes 0 :dropped-events 0 :overflow-reason nil :created-at 1234567890}]}. "
                     "3. Pressured queue: {:topic \"trace\"} -> {:ok? true :subs [{:id \"xyz-789\" :topic :trace :queue-depth 487 :queue-bytes 2400000 :dropped-events 12 :overflow-reason :max-buffered-bytes}]}.")
   :typicalTokens 500
   :outputSchema envelope-or-marker
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
                     "route, flow, head, error-projector, machine — the closed "
                     "v1 registrar set (per Spec 001 §Registry model) minus "
                     "`:app-schema` (intentionally empty registrar slot — its "
                     "metadata lives in the schemas artefact's per-frame side-"
                     "table, queried via `rf/app-schemas` instead). The "
                     "`machine` kind routes through (rf/machine-meta id) (Spec "
                     "005 §Querying machines); the other kinds route through "
                     "(rf/handler-meta kind id). Returns `{:ok? true :kind k "
                     ":id i ...meta...}` on a hit or `{:ok? false :reason "
                     ":not-registered :kind k :id i}` when no slot matches. "
                     "Examples: "
                     "1. Find an event: {:kind \"event\" :id \":user/login\"} -> {:ok? true :kind :event :id :user/login :ns my.app.user :line 42 :file \"src/my/app/user.cljs\" :rf.source/uri \"file://...\" :doc \"...\"}. "
                     "2. Subscription on a composite key: {:kind \"sub\" :id \"[:rf/composite [:items :by-id 42]]\"} -> {:ok? true :kind :sub :id [:rf/composite [:items :by-id 42]] :ns my.app.subs :line 18}. "
                     "3. Miss: {:kind \"fx\" :id \":missing/fx\"} -> {:ok? false :reason :not-registered :kind :fx :id :missing/fx}.")
   :typicalTokens 400
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:kind {:type "string"
                                     :description "Registrar kind. One of event, sub, fx, cofx, view, frame, route, flow, head, error-projector, machine."
                                     :enum ["event" "sub" "fx" "cofx" "view" "frame" "route" "flow" "head" "error-projector" "machine"]}
                              :id   {:type "string"
                                     :description (str "EDN-encoded id, e.g. \":user/login\". For "
                                                       "composite-key subs, pass the vector form "
                                                       "as a string, e.g. \"[:rf/composite :x]\".")}
                              :build {:type "string"}}
                 :required ["kind" "id"]
                 :additionalProperties false}})

(def list-handlers
  {:name "list-handlers"
   :description (str "Return every registered id under a kind. The discovery "
                     "surface — agents call this first to find out what's "
                     "registered, then `handler-meta` to drill into a specific "
                     "id. Supported kinds: event, sub, fx, cofx, view, frame, "
                     "route, flow, head, error-projector, machine — the closed "
                     "v1 registrar set (per Spec 001 §Registry model) minus "
                     "`:app-schema` (intentionally empty registrar slot — use "
                     "`rf/app-schemas` for schema enumeration). The `machine` "
                     "kind lists every event handler flagged `:rf/machine? "
                     "true` via (rf/machines); the other kinds lift the id "
                     "vector off the registrar's per-kind map. Returns "
                     "`{:ok? true :kind k :ids [...] :count n}`. The id "
                     "vector is sorted (string / keyword / symbol ordering) "
                     "so the list shape is stable across calls. "
                     "Examples: "
                     "1. List events: {:kind \"event\"} -> {:ok? true :kind :event :ids [:cart/add :cart/checkout :user/login ...] :count 47}. "
                     "2. List subs: {:kind \"sub\"} -> {:ok? true :kind :sub :ids [:current-user :cart/items ...] :count 23}. "
                     "3. List machines: {:kind \"machine\"} -> {:ok? true :kind :machine :ids [:auth/session :checkout/flow] :count 2}.")
   :typicalTokens 800
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:kind {:type "string"
                                     :description "Registrar kind. One of event, sub, fx, cofx, view, frame, route, flow, head, error-projector, machine."
                                     :enum ["event" "sub" "fx" "cofx" "view" "frame" "route" "flow" "head" "error-projector" "machine"]}
                              :build {:type "string"}}
                 :required ["kind"]
                 :additionalProperties false}})

(def get-re-frame2-pair-instructions
  {:name "get-re-frame2-pair-instructions"
   :description (str "Return the re-frame2-pair-mcp agent-onboarding text (rf2-fnpqg): tool catalogue, EDN posture, "
                     "tagged-mutation conventions, streaming subscribe semantics, the wire-boundary "
                     "pipeline. Inline prose, no nREPL round-trip — call this at session start to orient "
                     "before the first real op. Mirrors story-mcp's `get-story-instructions`. Returns "
                     "`{:ok? true :tool \"get-re-frame2-pair-instructions\" :text <string>}` — a single text slot "
                     "the agent host renders verbatim. "
                     "Examples: "
                     "1. Session bootstrap: {} -> {:ok? true :tool \"get-re-frame2-pair-instructions\" :text \"re-frame2-pair quick reference...\"}. "
                     "2. Cached on second call (universal cache opt-in): {:cache true} -> {:rf.mcp/cache-hit {:hash ... :via :result-hash :hint \"...\"}} (after the first uncached call). "
                     "3. With budget override: {:max-tokens 0} -> {:ok? true :text \"...\"} (cap disabled; the text always fits comfortably).")
   :typicalTokens 1500
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {}
                 :additionalProperties false}})
