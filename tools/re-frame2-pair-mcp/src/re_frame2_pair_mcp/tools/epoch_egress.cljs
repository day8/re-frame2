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
  (core.cljc §projected egress) names `project-egress` the single
  normative record-level egress door, and every record egressing the
  epoch ring MUST go through it — a hand-walk of
  `(epoch-history frame-id)` that re-wraps by hand is one missed
  `project-egress` away from leaking un-elided data across the process
  boundary. Per Spec Security.md §Epoch privacy posture.

  ## What this ns does

  It builds the server-side source that wraps the let-bound page of
  records the tool egresses, routing each record through
  `re-frame.core/project-egress`. The projection runs APP-SIDE inside
  the eval form (where the frame's `[:rf.runtime/elision]` runtime-db registry is
  reachable, same as the snapshot / get-path walkers), so
  the records the MCP server receives already carry `:rf/redacted` /
  `:rf.size/large-elided` markers in their payload slots.

  ## GUARD G3 — an epoch record MUST arrive STAMPED

  `project-egress` is the ONE record-level door (rf2-bv1p, ruling
  rf2-kuky.9 option A retired the standalone `projected-record` door),
  and it recognises an epoch record by ONE thing: the `:kind
  :rf/epoch-record` stamp `re-frame.epoch.assembly` puts on it. A record
  it does not recognise is not an error there — it falls through to the
  KINDLESS BARE-VALUE WALK, which is the correct answer for a
  direct-read value and the WRONG one for an epoch record.

  That fall-through is a fail-OPEN for this surface, and it is why every
  render site here guards before it calls the door. The bare walk starts
  at `:path []`, so a frame's `[:auth :token]` sensitive declaration
  cannot match the record's `[:db-after :auth :token]` slot: NOTHING
  matches, nothing redacts, and the whole app-db snapshot ships RAW
  across the MCP wire to an external agent. An app running a
  pre-rf2-kuky.92 `re-frame.epoch.assembly` stamps no `:kind` at all, so
  this is not a hypothetical — it is what a version-skewed pair session
  looks like.

  So the emitted source checks `(= :rf/epoch-record (:kind r))` BEFORE
  it calls the door and THROWS otherwise, carrying
  `:rf.error/pair-mcp-unstamped-epoch-record` in its ex-data as the
  machine-readable discriminator. A loud error naming a too-old app is
  strictly better than silently shipping raw state: the operator gets a
  diagnosis, not a leak. See `stamped-epoch-guard-src`.

  ## Gate parity with snapshot / get-path

  The `--allow-sensitive-reads` boot gate (`raw-state/raw-state-allowed?`)
  governs whether a caller's `:include-sensitive true` is honoured. The
  pull-mode epoch tools mirror the gate exactly. What the gate moves is
  ONE AXIS INSIDE the projection — it never moves the projection itself:

  - Gate OFF (default) — `incl?` is forced false regardless of the
    per-call arg, so `egress-opts-edn` emits the bare
    `:rf.egress/off-box-tool` profile: sensitive slots redact, large
    slots elide WITH the structural digest the tool needs. A hostile
    per-call `:include-sensitive true` cannot talk an operator who did
    not pass `--allow-sensitive-reads` into shipping raw state.
  - Gate ON + caller opts in (`include-sensitive true`) — `incl?` is
    true, so `:rf.size/include-sensitive? true` is composed OVER that
    same profile floor. The record STILL crosses `project-egress`; the
    operator's deliberate opt-in lifts the app-db sensitive axis and
    nothing else. There is no posture on which a record ships
    unprojected — see the `:include-sensitive` section below.

  ## Named-egress profile: `:rf.egress/off-box-tool`

  Pair-MCP is an OFF-BOX TOOL WIRE — epoch records cross to an external
  agent — so per Tool-Pair.md §Named-egress profile adoption (EP-0015 §10)
  this surface names the `:rf.egress/off-box-tool` boundary on EVERY epoch
  egress, NOT the `:rf.egress/off-box-observability` profile the epoch
  projector falls back to when none is named. Both share the
  redact/elide floor (sensitive → `:rf/redacted`, large →
  `:rf.size/large-elided`); off-box-tool additionally carries the
  `:rf.size/include-digests?` structural indicators a tool needs to reason
  about an elided slot's shape. `egress-opts-edn` names the profile
  unconditionally, so the off-box wire always ships the tool marker, never
  the less-informative hosted-observability one.

  `project-egress` is the framework's single record-level egress door —
  on a stamped `:rf/epoch-record` it dispatches to the late-bound
  `:epoch/project-record` per-kind projector, which carries hard
  `:rf.size/include-sensitive? false` / `:rf.size/include-large? false`
  defaults and handles all four payload-bearing slots
  (`:db-before`, `:db-after`, `:trigger-event`, `:trace-events`)
  including the `:trace-events` per-event re-root. Handing it the WHOLE
  record (rather than walking the record's slots one at a time through
  per-slot `project-egress` calls of our own) keeps the projection
  single-sourced in the framework and removes the \"one missed slot\"
  leak surface the core docstring warns about.

  ## `:include-sensitive` routes THROUGH projection, never around it

  The `--allow-sensitive-reads` boot gate + per-call `:include-sensitive
  true` opt-in does NOT disable the projection. It is threaded as the
  `:rf.size/include-sensitive? true` egress opt INTO `project-egress` (composed OVER
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
    independent `:rf.size/include-large?` opt.

  `:include-sensitive` lifts only the app-db sensitive axis — it is NOT a
  full raw epoch bypass. The app-db sensitive axis is independent of every
  other axis, so the raw fx-args / runtime-db partition never ship merely
  because sensitive app-db values were requested.
  This holds Security.md §98-108 (off-box epoch egress MUST route through
  the record-level door; `:include-fx-args?` is orthogonal to app-db
  `:rf.size/include-sensitive?` / `:rf.size/include-large?`) and the EP-0015
  record-projection contract. There is no
  app-db-`:include-sensitive`-implied raw escape hatch; a deliberate
  full-raw epoch read is the explicit per-axis opts on `project-egress`
  (the three epoch-only axes `:include-fx-args?` /
  `:include-runtime-db?` / `:include-event-args?` are door vocabulary
  since rf2-bv1p), not a side effect of asking for sensitive app-db
  values.")

(defn egress-opts-edn
  "Render the `project-egress` egress opts as an EDN string for inlining
  into a CLJS eval form.

  ## Named-egress profile: `:rf.egress/off-box-tool` on EVERY path

  Pair-MCP is an OFF-BOX TOOL WIRE — epoch records cross to an external
  agent — so per Tool-Pair.md §Named-egress profile adoption (EP-0015 §10)
  the egress MUST name the `:rf.egress/off-box-tool` boundary, NOT a
  hand-rolled `:rf.size/*` combination and NOT the unnamed default. With
  no `:rf.egress/profile` opt the epoch projector resolves
  `:rf.egress/off-box-observability` (epoch/tool_pair.cljc
  §resolve-egress-profile) — same redact/elide floor,
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
  `:rf.size/include-sensitive? true` ADVANCED override on TOP of the profile floor
  — lifting ONLY the app-db sensitive axis through the projection (the
  override composes over the off-box-tool floor per
  projection.cljc §resolve-elision-opts; the off-box-tool boundary is still
  named). When false it emits just the bare profile map.

  Deliberately threads ONLY `:rf.size/include-sensitive?` beyond the profile. The
  orthogonal `:include-fx-args?` / `:include-runtime-db?` / `:rf.size/include-large?`
  axes stay at their fail-closed off-box-tool floor — `:include-sensitive`
  alone never lifts fx-args, the runtime-db partition, or large-slot
  elision (Security.md §Off-box egress)."
  [incl?]
  (if incl?
    "{:rf.egress/profile :rf.egress/off-box-tool :rf.size/include-sensitive? true}"
    "{:rf.egress/profile :rf.egress/off-box-tool}"))

(def unstamped-epoch-record-error-id
  "The machine-readable discriminator GUARD G3 throws in its ex-data
  (both as `:rf.error/id` and as `:reason`, the two slots pair-MCP's
  error envelope reads — see `server.cljs` §error mapping).

  It is a PAIR-MCP-owned id in the `:rf.error/*` namespace, alongside
  `:rf.error/pair-mcp-ambiguous-shadow` /
  `:rf.error/pair-mcp-nrepl-port-not-found`: the throw happens APP-SIDE
  inside our emitted eval form, but the fault it names is a pair-session
  VERSION SKEW — the connected app's `re-frame.epoch.assembly` predates
  the `:kind :rf/epoch-record` stamp (rf2-kuky.92) — not a framework
  error the framework would raise on its own."
  :rf.error/pair-mcp-unstamped-epoch-record)

(defn stamped-epoch-guard-src
  "GUARD G3 — render the CLJS source that refuses an UNSTAMPED record
  before it reaches the egress door. `sym` is the name of the emitted
  binding holding the record (a CLJS symbol or string).

  ## Why this guard exists (it is the fail-closed half of the door)

  `re-frame.core/project-egress` is the ONE record-level egress door
  since rf2-bv1p, and it recognises an epoch record SOLELY by its
  stamped `:kind :rf/epoch-record` — there is no shape test and no
  second name to call. An unrecognised input is not refused by the door:
  it falls through to the KINDLESS BARE-VALUE WALK, which is the right
  answer for the direct-read path and a LEAK for an epoch record.

  A bare walk starts at `:path []`. A frame that declares
  `[:auth :token]` sensitive therefore cannot match the record's
  `[:db-after :auth :token]` slot — the declaration is looked up at a
  path prefix the record never presents — so no slot redacts and the
  ENTIRE `:db-before` / `:db-after` snapshot crosses the MCP wire RAW,
  to an off-box agent, with `--allow-sensitive-reads` OFF. That is the
  precise fail-open this guard closes.

  The trigger is real: an app running a pre-rf2-kuky.92
  `re-frame.epoch.assembly` stamps no `:kind`, so a pair session against
  a version-skewed app would hit exactly this path. Throwing names the
  cause; falling through says nothing and ships the state.

  ## What it emits

  `(when-not (= :rf/epoch-record (:kind <sym>)) (throw (ex-info …)))` —
  a statement, evaluated for its throw, sequenced BEFORE the door call.
  The message names the observed `:kind` and the too-old app;
  `unstamped-epoch-record-error-id` rides in ex-data under both
  `:rf.error/id` and `:reason` so an agent can BRANCH on the skew rather
  than parse prose.

  Presence is the caller's business, not the guard's: `:epoch` may
  legitimately be ABSENT (a degraded runtime, an `:ok? false`
  frame-untargetable envelope), and each render site keeps its own
  presence check. This guard fires only on a record it was actually
  handed."
  [sym]
  (let [s (name sym)]
    (str "(when-not (= :rf/epoch-record (:kind " s "))"
         " (throw (ex-info"
         " (str \"pair-MCP off-box epoch egress refused: :kind is \" (pr-str (:kind " s "))"
         " \", not :rf/epoch-record. re-frame.core/project-egress would bare-walk this record from :path [],"
         " so app-db :sensitive? declarations cannot match its :db-after-prefixed slots and raw app-db would"
         " ship off-box. The connected app's re-frame.epoch.assembly predates the :kind stamp (rf2-kuky.92)"
         " -- upgrade the app's re-frame2 epoch artefact.\")"
         " {:rf.error/id " unstamped-epoch-record-error-id
         " :reason " unstamped-epoch-record-error-id
         " :kind (:kind " s ")})))")))

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
  `re-frame.core/project-egress` (the single normative record-level
  egress door — Security.md §Epoch privacy posture) and RE-DERIVES
  `:render-events` from the projected epoch's now-elided `:trace-events`, so
  the two stay consistent and the render events can't carry un-elided
  app-db material the `:epoch` slot already redacted. `:cascade-summary` /
  `:epoch-id` / `:db-changed?` and the other consequence slots carry no raw
  app-db material and pass through untouched.

  GUARD G3 (`stamped-epoch-guard-src`) fires FIRST on a PRESENT `:epoch`
  that is not stamped `:kind :rf/epoch-record`: `project-egress` would
  bare-walk such a record from `:path []` and ship its app-db slots RAW,
  so the emitted form throws instead. An ABSENT `:epoch` stays
  legitimate and untouched — a degraded runtime and the `:ok? false`
  frame-untargetable envelope both carry none, and the
  `(contains? r# :epoch)` presence check that already governed the
  projection now governs the guard with it.

  `incl?` (the resolved `:include-sensitive` opt-in) is threaded as the
  `{:rf.size/include-sensitive? true}` egress opt INTO `project-egress` — NOT as a
  projection bypass. It lifts ONLY the app-db sensitive axis;
  fx-args / runtime-db / large slots stay at their fail-closed defaults.
  There is no `:include-sensitive`-implied raw escape hatch — the epoch
  always crosses the wire projected.

  Non-map results (a degraded runtime, or the `:ok? false` frame-
  untargetable envelope — which carries no epoch) pass through
  unchanged: the `when (map? ...)` guard keeps the transform total."
  [result-src incl?]
  (let [opts-edn (egress-opts-edn incl?)]
    (str "(let [r# " result-src "]"
         "  (if-not (map? r#) r#"
         "    (let [pe# (when (contains? r# :epoch)"
         "                (let [e# (:epoch r#)]"
         ;; GUARD G3 — refuse an UNSTAMPED epoch rather than let
         ;; `project-egress` bare-walk it and ship app-db slots raw.
         "                  " (stamped-epoch-guard-src "e#")
         "                  (re-frame.core/project-egress e# " opts-edn ")))]"
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
  `re-frame.core/project-egress` under the `:rf.egress/off-box-tool`
  egress profile (Pair-MCP is an off-box tool wire; see
  `egress-opts-edn`) — sensitive payload slots land as `:rf/redacted`,
  large slots as `:rf.size/large-elided` markers CARRYING the structural
  `:digest` the tool profile enables. There is no projection bypass: a
  page NEVER crosses the off-box wire unprojected, and it NEVER crosses
  under the unnamed default profile (which resolves
  `:rf.egress/off-box-observability` and would omit the tool digests).

  GUARD G3 (`stamped-epoch-guard-src`) runs on EVERY record in the
  `mapv`, before the door call. `project-egress` recognises an epoch
  record only by its stamped `:kind :rf/epoch-record`; an unstamped one
  falls through to the kindless bare-value walk, which starts at
  `:path []` and so cannot match any app-db classification against a
  `:db-after`-prefixed slot — the whole page would ship RAW. An app
  whose `re-frame.epoch.assembly` predates rf2-kuky.92 stamps nothing,
  so the guard throws
  `:rf.error/pair-mcp-unstamped-epoch-record` naming the skew rather
  than leaking the ring.

  `incl?` (the resolved `:include-sensitive` opt-in — the
  `--allow-sensitive-reads` boot gate AND the per-call arg) is threaded as
  the `:rf.size/include-sensitive? true` egress opt INTO `project-egress`,
  composed OVER the off-box-tool floor, lifting ONLY the app-db sensitive
  axis. The orthogonal fx-args / runtime-db / large axes stay at their
  fail-closed off-box-tool defaults regardless of `:include-sensitive`
  (Security.md §Off-box egress). `:include-sensitive` never disables
  projection wholesale: the raw fx-args / runtime-db partition stays
  redacted regardless.

  The page is a vector of epoch-record maps, so the `mapv` is total —
  and after GUARD G3 every element that survives to the door is a
  stamped record by construction. The fn is a pure data transform with
  no side effects beyond the guard's throw. Both `incl?` branches thread
  the named profile through a fn literal so the off-box-tool boundary is
  named on every record."
  [page-sym incl?]
  (let [p        (name page-sym)
        opts-edn (egress-opts-edn incl?)]
    ;; #(project-egress % {:rf.egress/profile :rf.egress/off-box-tool …}) via
    ;; a fn literal so the named off-box-tool boundary (+ any sensitive
    ;; opt-in) rides into EVERY record's projection — behind GUARD G3, so
    ;; an unstamped record throws instead of being bare-walked.
    (str "(mapv (fn [r#] " (stamped-epoch-guard-src "r#")
         " (re-frame.core/project-egress r# " opts-edn ")) " p ")")))
