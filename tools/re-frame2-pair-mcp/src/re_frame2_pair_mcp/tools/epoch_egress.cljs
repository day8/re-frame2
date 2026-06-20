(ns re-frame2-pair-mcp.tools.epoch-egress
  "Off-box projection wrap for the pull-mode epoch tools.

  `trace-window` and `watch-epochs` egress full epoch records — each
  carrying `:db-before` / `:db-after` app-db snapshots (plus
  `:trigger-event` / `:trace-events`) — over the MCP wire. The
  `:epoch-vector` wire-pipeline arm
  (`re-frame2-pair-mcp.tools.wire-pipeline`) only DROPS whole epochs
  STAMPED `:rf.epoch/sensitive?` and then diff-encodes / dedups the rest
  — none of which redact a declared-sensitive SLOT (e.g.
  `[:auth :password]`) sitting inside `:db-before` / `:db-after`. Without
  the projection this ns applies, a schema-declared `:sensitive?` slot
  would ride off-box verbatim even with the `--allow-sensitive-reads`
  gate OFF (the published default).

  The framework forbids exactly this hand-walk. `re-frame.core`
  (core.cljc §projected egress) names `projected-record` /
  `projected-history` the single normative off-box-egress emission site
  and states that tools egressing the epoch ring MUST route through it
  rather than walking `(epoch-history frame-id)` and re-wrapping by hand
  — \"the hand-walk is one missed `mapv projected-record` away from
  leaking un-elided data across the process boundary.\" Per Spec
  Security.md §Epoch privacy posture.

  ## What this ns does

  It builds the server-side source that wraps the let-bound page of
  records the tool egresses, routing each record through
  `re-frame.core/projected-record`. The projection runs APP-SIDE inside
  the eval form (where the frame's `[:rf.runtime/elision]` runtime-db registry is
  reachable, same as the snapshot / get-path / subscribe walkers), so
  the records the MCP server receives already carry `:rf/redacted` /
  `:rf.size/large-elided` markers in their payload slots.

  ## Gate parity with snapshot / get-path / subscribe

  The `--allow-sensitive-reads` boot gate (`raw-state/raw-state-allowed?`)
  governs whether a caller's `:include-sensitive true` is honoured. The
  pull-mode epoch tools mirror the gate exactly:

  - Gate OFF (default) — `include-sensitive` is forced false, so
    `project?` is true: every egressed record is routed through
    `projected-record` under the `:rf.egress/off-box-tool` profile
    (sensitive slots redact, large slots elide WITH the
    structural digest the tool needs). A hostile per-call
    `:include-sensitive true` cannot talk an operator who did not pass
    `--allow-sensitive-reads` into shipping raw state.
  - Gate ON + caller opts in (`include-sensitive true`) — `project?` is
    false: records ship raw, exactly as subscribe ships raw on the
    operator's explicit opt-in. This is the operator's deliberate choice
    to see verbatim state.

  ## Named-egress profile: `:rf.egress/off-box-tool`

  Pair-MCP is an OFF-BOX TOOL WIRE — epoch records cross to an external
  agent — so per Tool-Pair.md §Named-egress profile adoption (EP-0015 §10)
  this surface names the `:rf.egress/off-box-tool` boundary on EVERY epoch
  egress, NOT `projected-record`'s `:rf.egress/off-box-observability`
  default. Both share the redact/elide floor (sensitive → `:rf/redacted`,
  large → `:rf.size/large-elided`); off-box-tool additionally carries the
  `:rf.size/include-digests?` structural indicators a tool needs to reason
  about an elided slot's shape. `egress-opts-edn` names the profile
  unconditionally, so the off-box wire always ships the tool marker, never
  the less-informative hosted-observability one.

  `projected-record` is the framework's single off-box emission site — it
  carries hard `:include-sensitive? false` / `:include-large? false`
  defaults and handles all four payload-bearing slots
  (`:db-before`, `:db-after`, `:trigger-event`, `:trace-events`)
  including the `:trace-events` per-event re-root. Using it
  directly (rather than a per-slot `elide-wire-value` hand-walk) keeps
  the projection single-sourced in the framework and removes the
  \"one missed slot\" leak surface the core docstring warns about.

  ## `:include-sensitive` routes THROUGH projection, never around it

  The `--allow-sensitive-reads` boot gate + per-call `:include-sensitive
  true` opt-in does NOT disable `projected-record`. It is threaded as the
  `:include-sensitive? true` egress opt INTO the projection (composed OVER
  the `:rf.egress/off-box-tool` profile floor), lifting ONLY
  the app-db sensitive axis. The other independent projection axes stay at
  their fail-closed defaults regardless of `:include-sensitive`:

  - `:effects[*].args` (payload-bearing fx-handler input) stay
    `:rf/redacted` — they are a different keyspace from app-db sensitive
    values, governed by the orthogonal `:include-fx-args?` opt
    (Security.md §Off-box egress).
  - the `:rf.db/runtime` frame-state partition stays `:rf/redacted` —
    governed by the orthogonal `:include-runtime-db?` opt.
  - app-db `:large?` slots stay `:rf.size/large-elided` — governed by the
    independent `:include-large?` opt.
  - the app-installed `:redact-fn` advanced override still runs over the
    projected record.

  `:include-sensitive` lifts only the app-db sensitive axis — it is NOT a
  full raw epoch bypass. The app-db sensitive axis is independent of every
  other axis, so the raw fx-args / runtime-db partition / un-`:redact-fn`'d
  record never ship merely because sensitive app-db values were requested.
  This holds Security.md §98-108 (off-box epoch egress MUST route through
  `projected-record`; `:include-fx-args?` is orthogonal to app-db
  `:include-sensitive?` / `:include-large?`) and the EP-0015 projected-record
  contract. There is no app-db-`:include-sensitive`-implied raw escape
  hatch; a deliberate full-raw epoch read is the explicit per-axis opts on
  `projected-record`, not a side effect of asking for sensitive app-db
  values.")

(defn egress-opts-edn
  "Render the `projected-record` egress opts as an EDN string for inlining
  into a CLJS eval form.

  ## Named-egress profile: `:rf.egress/off-box-tool` on EVERY path

  Pair-MCP is an OFF-BOX TOOL WIRE — epoch records cross to an external
  agent — so per Tool-Pair.md §Named-egress profile adoption (EP-0015 §10)
  the egress MUST name the `:rf.egress/off-box-tool` boundary, NOT a
  hand-rolled `:rf.size/*` combination and NOT the `projected-record`
  default. `projected-record` defaults to `:rf.egress/off-box-observability`
  (epoch/tool_pair.cljc §resolve-egress-profile) — same redact/elide floor,
  but it OMITS the `:rf.size/include-digests?` structural indicators a tool
  needs to reason about an elided slot's shape. The off-box-tool profile
  turns digests ON. Both boundaries fail-closed identically on the
  sensitive / large redaction axes (projection.cljc §profile->size-opts);
  the ONLY difference the profile makes here is the digest indicators —
  the off-box-tool marker is more informative than the
  hosted-observability one (the same tool profile the direct-read
  surfaces resolve via `tools.elision`), and neither leaks raw data. The
  named selector is the framework's primary boundary answer —
  *\"which boundary is this?\"* — and a pair-MCP epoch wire is always the
  tool boundary. So the profile is emitted UNCONDITIONALLY (default + the
  trusted-local sensitive opt-in path alike); it is never the
  observability default for this surface.

  `incl?` is the resolved `:include-sensitive` opt-in (the
  `--allow-sensitive-reads` boot gate AND the per-call arg — see each
  tool's `incl?` derivation). When true it adds the unqualified
  `:include-sensitive? true` ADVANCED override on TOP of the profile floor
  — lifting ONLY the app-db sensitive axis through the projection (the
  override composes over the off-box-tool floor per
  projection.cljc §resolve-elision-opts; the off-box-tool boundary is still
  named). When false it emits just the bare profile map.

  Deliberately threads ONLY `:include-sensitive?` beyond the profile. The
  orthogonal `:include-fx-args?` / `:include-runtime-db?` / `:include-large?`
  axes stay at their fail-closed off-box-tool floor — `:include-sensitive`
  alone never lifts fx-args, the runtime-db partition, or large-slot
  elision (Security.md §Off-box egress)."
  [incl?]
  (if incl?
    "{:rf.egress/profile :rf.egress/off-box-tool :include-sensitive? true}"
    "{:rf.egress/profile :rf.egress/off-box-tool}"))

(defn project-dispatch-result-src
  "CLJS source that projects the epoch-bearing slots of a dispatch
  `:trace` / `:settle` result map for off-box egress. `result-src` is a
  raw CLJS expression that evaluates to the runtime dispatch fn's return
  (a map, or a non-map degraded value).

  `dispatch-and-collect` (`:trace`) returns the cascade envelope PLUS a
  full `:epoch` — the verbatim assembled `:rf/epoch-record` carrying
  `:db-before` / `:db-after` / `:trigger-event` / `:trace-events` app-db
  snapshots. `dispatch-and-settle!` (`:settle`) additionally returns
  `:render-events` — the view-lifecycle trace events folded out of that
  epoch's `:trace-events`. Both carry app-db material, so without this
  projection a schema-declared `:sensitive?` slot inside
  `:db-before`/`:db-after` (or inside a render event's `:rf.event/db`
  tag) would leak to the MCP/LLM boundary with `--allow-sensitive-reads`
  OFF (the published default) — the same surface the pull-mode
  `trace-window` / `watch-epochs` tools guard.

  The emitted source ALWAYS routes `:epoch` through
  `re-frame.core/projected-record` (the single normative off-box-egress
  emission site — Security.md §Epoch privacy posture) and RE-DERIVES
  `:render-events` from the projected epoch's now-elided `:trace-events`, so
  the two stay consistent and the render events can't carry un-elided
  app-db material the `:epoch` slot already redacted. `:cascade-summary` /
  `:epoch-id` / `:db-changed?` and the other consequence slots carry no raw
  app-db material and pass through untouched.

  `incl?` (the resolved `:include-sensitive` opt-in) is threaded as the
  `{:include-sensitive? true}` egress opt INTO `projected-record` — NOT as a
  projection bypass. It lifts ONLY the app-db sensitive axis;
  fx-args / runtime-db / large slots / `:redact-fn` stay at their
  fail-closed defaults. There is no `:include-sensitive`-implied raw escape
  hatch — the epoch always crosses the wire projected.

  Non-map results (a degraded runtime, or the `:ok? false` frame-
  untargetable envelope — which carries no epoch) pass through
  unchanged: the `when (map? ...)` guard keeps the transform total."
  [result-src incl?]
  (let [opts-edn (egress-opts-edn incl?)]
    (str "(let [r# " result-src "]"
         "  (if-not (map? r#) r#"
         "    (let [pe# (when (contains? r# :epoch)"
         "                (re-frame.core/projected-record (:epoch r#) " opts-edn "))]"
         "      (cond-> r#"
         "        (contains? r# :epoch)"
         "        (assoc :epoch pe#)"
         ;; Re-derive :render-events from the PROJECTED epoch's trace-events
         ;; so they inherit the same elision the :epoch slot just got — the
         ;; render events are a filtered view of :trace-events, never an
         ;; independent payload that could leak after the epoch redacts.
         "        (contains? r# :render-events)"
         "        (assoc :render-events"
         "               (filterv (fn [ev#]"
         "                          (contains? #{:rf.view/render :rf.view/rendered"
         "                                       :rf.view/rendered-cap-reached :rf.view/unmounted}"
         "                                     (:operation ev#)))"
         "                        (:trace-events pe#)))))))")))

(defn project-page-src
  "CLJS source that projects a let-bound vector of epoch records for
  off-box egress. `page-sym` is the name of the let-bound page (a CLJS
  symbol or string).

  The emitted source ALWAYS maps each record through
  `re-frame.core/projected-record` under the `:rf.egress/off-box-tool`
  egress profile (Pair-MCP is an off-box tool wire; see
  `egress-opts-edn`) — sensitive payload slots land as `:rf/redacted`,
  large slots as `:rf.size/large-elided` markers CARRYING the structural
  `:digest` the tool profile enables. There is no projection bypass: a
  page NEVER crosses the off-box wire unprojected, and it NEVER crosses
  under the bare 1-arity hosted-observability default (which would omit
  the tool digests).

  `incl?` (the resolved `:include-sensitive` opt-in — the
  `--allow-sensitive-reads` boot gate AND the per-call arg) is threaded as
  the `:include-sensitive? true` egress opt INTO `projected-record`,
  composed OVER the off-box-tool floor, lifting ONLY the app-db sensitive
  axis. The orthogonal fx-args / runtime-db / large axes and the app
  `:redact-fn` stay at their fail-closed off-box-tool defaults regardless of
  `:include-sensitive` (Security.md §Off-box egress). `:include-sensitive`
  never disables projection wholesale: the raw fx-args / runtime-db
  partition / un-`:redact-fn`'d record stay redacted regardless.

  `projected-record` returns `nil` for non-map input; the page is a
  vector of epoch-record maps, so the `mapv` is total. The fn is a pure
  data transform with no side effects. Both `incl?` branches thread the
  named profile through a fn literal so the off-box-tool boundary is named
  on every record."
  [page-sym incl?]
  (let [p        (name page-sym)
        opts-edn (egress-opts-edn incl?)]
    ;; #(projected-record % {:rf.egress/profile :rf.egress/off-box-tool …}) via
    ;; a fn literal so the named off-box-tool boundary (+ any sensitive
    ;; opt-in) rides into EVERY record's projection.
    (str "(mapv (fn [r#] (re-frame.core/projected-record r# " opts-edn ")) " p ")")))
