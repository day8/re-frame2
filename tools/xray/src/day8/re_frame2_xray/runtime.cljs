(ns day8.re-frame2-xray.runtime
  "Injected-runtime namespace exposing Xray's read/mutate accessors
  to AI agents (rf2-8xzoe.4 / F-4, rf2-crhr8).

  Lives on the browser side of the stdio JSON-RPC pipe. Rides
  Xray-the-panel's `:devtools/preloads` (see `preload.cljs`'s require
  list) so a consumer app that already loads Xray-the-panel
  automatically carries the runtime — no separate preload entry.

  Per rf2-hvl1g (closure 2026-05-19) there is no dedicated `xray-mcp`
  jar; AI agents reach this runtime via `tools/re-frame2-pair-mcp/`
  (which can read this ns via `eval-cljs`). The accessors below are
  the framework-published Xray runtime API.

  ## What this namespace is

  Tool-shaped accessors (see [`API.md` §Xray runtime API](../../spec/API.md))
  rendered as EDN forms addressed at
  `day8.re-frame2-xray.runtime/<accessor>`; an MCP server (today
  `tools/re-frame2-pair-mcp/`) renders the form against the nREPL
  socket, shadow-cljs evaluates each form in the browser tab, and the
  return value comes back over the bencode-framed channel.

  Plus three load-bearing supports:

  - `session-id` — random UUID per preload load. The MCP-server-side
    preload probe reads either this CLJS var or its
    `js/globalThis.__day8_re_frame2_xray_runtime` mirror to confirm
    the runtime landed. A full page refresh wipes both — the next
    `discover-app` tool call reports `:reason :runtime-not-preloaded`
    with a setup hint.
  - `*current-origin*` — `^:dynamic` var holding the dispatch `:origin`
    opt the runtime stamps onto every mutation it performs (surfacing on
    the trace bus as the `:rf.event/origin` tag);
    `current-origin` (no earmuffs) is the plain read accessor that
    returns it. The default `:xray-mcp` is grandfathered from the
    original xray-mcp design; revising the default to a more accurate
    tag (e.g. `:xray-runtime`) is tracked separately as a follow-on.
    The MCP server is expected to rebind `*current-origin*` for the
    synchronous extent of an eval'd form to its own `:origin`
    identifier.
  - `health` — one-call summary used by `discover-app`. Side-effect-free
    here; the runtime registers no listeners on its own.

  ## What this namespace is NOT

  - Not a new framework registry. Every accessor below routes through
    an existing `re-frame.core/*` surface. We add no new dispatch
    types, no new effect substrates, no new component substrates.
  - Not a re-frame2-pair-mcp port. The accessor surface is shaped to
    the Xray-specific surfaces (trace buffer, epoch history,
    app-db-diff, machine-state) rather than to re-frame2-pair-mcp's
    own tool shapes.
  - Not a streaming substrate. The runtime exposes
    `register-listener!` / `register-epoch-listener!` indirection via
    re-frame.core, plus a thin `current-subscriptions` accessor for
    the diagnostic; per-tick queue / overflow bookkeeping lives on
    the MCP-server side.

  ## Why the install side-effect block is gated on `debug-enabled?`

  Per Xray-the-panel's preload, the framework's trace surface elides
  in production builds (`re-frame.interop/debug-enabled?` false). The
  runtime's sentinel installation is gated the same way so a stray
  production load (which is a configuration mistake but should fail
  gracefully) is a no-op rather than a `js/globalThis` pollution.

  ## Cross-side coupling is one-way

  The MCP server depends on the accessor signatures below (the
  contract); the runtime is independent of any server. Xray-the-panel
  loads this ns without an MCP server running, and any MCP consumer
  (re-frame2-pair-mcp today) can attach later without the runtime
  needing to know."
  (:require [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            ;; rf2-qwm0a: trace-buffer (and the rest of the listener +
            ;; ring-buffer surface) lives in re-frame.trace.tooling, not
            ;; re-frame.trace. CLJS deliberately omits `rf/<name>` aliases
            ;; for these so production counter bundles DCE the tooling
            ;; sibling wholesale; Xray's runtime is dev-only (rides the
            ;; panel's :devtools/preloads), so requiring the tooling ns
            ;; directly here is bundle-isolation-safe.
            [re-frame.trace.tooling :as trace-tooling]
            ;; rf2-7737vq: the canonical RAW trace-event frame reader
            ;; (`re-frame.trace/trace-event-frame`). A plain public defn on
            ;; the trace contract ns (NOT a facade export); requiring it
            ;; here is the bundle-isolation-safe dev-tier dependency the
            ;; trace tooling sibling above already establishes.
            [re-frame.trace :as trace]
            [re-frame.interop :as interop]
            ;; rf2-uv2q2: the canonical Editscript-A* diff engine. Used by
            ;; get-app-db-diff to project the changed-paths slice diff its
            ;; docstring promises ({:added :removed :changed}) instead of
            ;; egressing two whole app-db snapshots.
            [day8.re-frame2-xray.diff.engine :as diff-engine]
            ;; Spec 016 §Xray and AI tooling — the resource-accessor
            ;; projection algebra (registry rows, instance/work-ledger
            ;; rows, lifecycle timeline, invalidation graph, and the
            ;; filter axes). Pure-data + JVM-portable; carries the
            ;; in-panel privacy summaries. The off-box egress here adds
            ;; the framework `egress-value` walker on top. Decoupled from
            ;; the optional resources artefact (reads via the registrar +
            ;; runtime-db slice; Xray never :requires re-frame.resources).
            [day8.re-frame2-xray.panels.resources-helpers :as resources-helpers]))

;; ---------------------------------------------------------------------------
;; Session sentinel
;; ---------------------------------------------------------------------------
;;
;; A random UUID set once per preload load. Mirrored on `js/globalThis`
;; under `__day8_re_frame2_xray_runtime` (a JS object carrying
;; session-id + installed-at-ms) so the MCP-server-side preload probe
;; is a single bencode round-trip — no CLJS compile required to test
;; "is the runtime here?". A full page refresh wipes both the var
;; and the global mirror; the next tool call surfaces
;; `:reason :runtime-not-preloaded` with a setup hint.

(def session-id
  "Per-preload random UUID. Read by the MCP server's `discover-app`
  tool to confirm the runtime landed in this browser session; survives
  shadow-cljs `:after-load` (this ns is loaded once at preload time
  and not re-evaluated on hot reload), wiped by a full page refresh."
  (str (random-uuid)))

(def ^:private global-marker-key
  "Key under which the session-id mirror lives on `js/globalThis`. The
  MCP-server side runs `(some? (and (exists? js/globalThis) (.-<key>
  js/globalThis)))` as the cheap probe; centralising the string here
  keeps the runtime <-> server contract editable in one place."
  "__day8_re_frame2_xray_runtime")

(defonce ^:private install-global-sentinel!
  ;; `js-obj` (not `#js`) so the file remains readable by bb's reader
  ;; for any future structural test that runs bb-side (parallels the
  ;; re-frame2-pair runtime idiom). Side-effect deliberately conditional on
  ;; `debug-enabled?` so a stray production load is a no-op.
  (do (when (and interop/debug-enabled? (exists? js/globalThis))
        (aset js/globalThis global-marker-key
              (js-obj "session-id" session-id
                      "installed"  (.now js/Date))))
      true))

;; ---------------------------------------------------------------------------
;; Origin dynamic var
;; ---------------------------------------------------------------------------
;;
;; Convention: every MCP-driven side-effect on the trace bus carries an
;; `:rf.event/origin <server-name>` tag. The runtime threads the value as
;; the framework dispatch `:origin` OPT (`{:origin <server-name>}`); the
;; router's `emit-dispatched-trace` projects that onto the
;; `:rf.event/dispatched` row's `[:tags :rf.event/origin]` (Spec 009
;; §Origin). The MCP server renders a
;; `binding` form that wraps the runtime's accessor call with
;; `(binding [*current-origin* <server-name>] ...)`; mutating accessors
;; read the bound value (via `*current-origin*` / `current-origin`) when
;; they construct the dispatch payload. Note the earmuffs: the
;; `^:dynamic` var is `*current-origin*` — `current-origin` (no
;; earmuffs) is a plain read fn and cannot be `binding`-rebound.
;;
;; The default value is `:xray-mcp` (grandfathered from the original
;; xray-mcp design — see DESIGN-RATIONALE.md Lock #6 supersedence;
;; revising the default is tracked separately) so a bare call from the
;; server
;; already carries the tag; `eval-cljs` keeps the binding for the
;; synchronous extent of the eval'd form only (the documented
;; async-tagging gap per Lock #4 / I6).

(def ^:dynamic *current-origin*
  "The dispatch `:origin` opt value stamped on every mutation the runtime
  performs on behalf of the MCP server (surfacing on the trace bus as the
  `:rf.event/origin` tag). Defaults to `:xray-mcp`; the
  server's `eval-cljs` tool re-binds it for the synchronous extent of
  the user-supplied form."
  :xray-mcp)

(defn current-origin
  "Read the current origin tag value. Public accessor so tests can pin
  the rebind contract without `#'`-piercing into the dynamic var."
  []
  *current-origin*)

;; ---------------------------------------------------------------------------
;; Frame resolution
;; ---------------------------------------------------------------------------
;;
;; Most accessors resolve a frame: explicit `:frame` arg → the sole
;; registered frame → nil. Multi-frame apps without an explicit
;; selection are surfaced as `:ambiguous-frame?` on `discover-app`'s
;; output rather than silently picking one. The MCP server's wire
;; layer is the right place to refuse mutations against an ambiguous
;; resolution; reads degrade through a documented fallback (the
;; tool-layer decides; the runtime doesn't pre-empt).

(defn- resolve-frame
  "Resolve the operating frame for an op. `explicit` is the caller's
  `:frame` arg (or nil); we fall back to the sole registered frame.
  Returns nil when no frame is registered or more than one is registered
  without an explicit pick — callers tag the result accordingly.

  NOTE this does NOT validate an explicit id against the registry — a
  caller-supplied id is returned verbatim. Accessors guard the
  explicit-but-unregistered case via `frame-failure` (rf2-xxo3zz) so a
  typo / stale frame id fails with a distinct `:no-such-frame` rather
  than reporting success against a nonexistent frame."
  [explicit]
  (cond
    (some? explicit) explicit
    :else            (let [fids (rf/frame-ids)]
                       (when (= 1 (count fids))
                         (first fids)))))

(defn- frame-failure
  "rf2-xxo3zz — the single frame-resolution guard every read / dispatch /
  mutation accessor consults before touching a frame. Given the caller's
  `explicit` `:frame` arg and the `fid` that `resolve-frame` returned,
  returns a consistent failure map when the frame cannot be operated on,
  or nil when the accessor may proceed:

    - `fid` nil → `:no-frame-resolved` — nothing explicit was passed and
      the registry isn't a single unambiguous frame (none registered, or
      more than one without a pick).
    - `explicit` given but NOT in `rf/frame-ids` → `:no-such-frame` — a
      typo or stale frame id. Without this guard `resolve-frame` returned
      the bogus id verbatim and reads returned `{:ok? true :value nil}`
      (indistinguishable from a legitimate nil) while mutations reported
      success against a frame that does not exist.

  Returns nil when `fid` is registered (the accessor proceeds). The
  implicit-resolution path (`explicit` nil, `fid` the sole frame) never
  hits the `:no-such-frame` branch — `resolve-frame` only ever returns a
  registered id there."
  [explicit fid]
  (cond
    (nil? fid)
    {:ok? false :reason :no-frame-resolved
     :hint "Pass :frame :foo or register at least one frame."}

    (and (some? explicit) (not (contains? (rf/frame-ids) fid)))
    {:ok? false :reason :no-such-frame :frame fid
     :hint (str "No frame " (pr-str fid) " is registered. "
                "Check the id for a typo or a stale/destroyed frame; "
                "discover-app lists the live frame ids.")}

    :else nil))

(defn- frames-list
  "All registered frame ids — used by `health` and indirectly by tools
  that need to enumerate frames for per-frame slice walks."
  []
  (vec (rf/frame-ids)))

;; ---------------------------------------------------------------------------
;; Privacy egress — the single named safe-egress entry point
;; ---------------------------------------------------------------------------
;;
;; Per MUST-inventory rows #15 / #17 / #19: every direct-read accessor
;; routes returned values through the framework's wire-elision walker
;; (`re-frame.core/elide-wire-value`) before egress. The walker is the
;; framework's single normative emission site; what was missing — and
;; what rf2-rcogp fixes — is a single NAMED off-box egress fn here so
;; THE SAFE PATH IS THE SHORT PATH.
;;
;; The threat model (Tool-Pair §Privacy egress, Security.md §Off-box
;; egress): this runtime hands values to an AI/MCP boundary and to
;; logs, both of which are sensitive sinks. The danger the senior-dev
;; API critique (rf2-814or) flagged is that `elide-wire-value` does
;; NOT bake off-box defaults — a forwarder author must KNOW to pass
;; `:rf.size/include-sensitive? false` + `:rf.size/include-large?
;; false`, and the unsafe path (`pr-str` the raw value you already
;; hold) is the same length or shorter. The fix: `egress-value` /
;; `egress-record` apply the off-box defaults already, so the
;; forwarder author's shortest call is the safe one and the verbose
;; opt-juggling never has to be re-derived per call site.
;;
;; Off-box default polarity: both `include-sensitive?` and
;; `include-large?` default `false` (the walker substitutes the slot —
;; sensitive ⇒ `:rf/redacted`, large ⇒ `:rf.size/large-elided`). A
;; caller passing `true` opts back in to seeing the raw value. The
;; runtime API uses plain-keyword opts; these fns translate to the
;; framework's `:rf.size/*` namespaced opt keys.

(defn egress-value
  "The single named off-box safe-egress fn for an arbitrary value
  (app-db slice, sub value, machine state, trace event, …). Routes
  `value` through the framework's wire-elision walker
  (`re-frame.core/elide-wire-value`) with the off-box privacy + size
  defaults BAKED IN, so the shortest call is the safe one.

  Off-box defaults: `include-sensitive?` + `include-large?` both
  `false` — sensitive slots become `:rf/redacted`, large slots become
  the `:rf.size/large-elided` marker. A caller that is itself the
  trust boundary opts back in per call:

      (egress-value v)                            ; safe (defaults)
      (egress-value v {:include-sensitive? true}) ; opt back in

  Optional `:path` — the ABSOLUTE app-db path the value sits at. The
  framework's app-db sensitive / large declarations (classified via the
  EP-0025 commit-plane `:sensitive` / `:large` effects — a `reg-event`
  returns them alongside `:db`, written `:source :effect`; Spec 015
  §Data classification) are keyed by absolute path, so a SLICE egress'd in
  isolation (e.g. one
  changed-path slice from `get-app-db-diff`, or a `:path`-scoped
  `get-app-db` read) must tell the walker where the slice lives or the
  declaration won't match. Defaults to `[]` (the value IS the whole
  walked root). rf2-uv2q2 — the diff accessor threads each leaf's path
  so per-slice elision honours schema declarations.

  Optional `:frame` — the frame whose elision policy applies (EP-0015
  frame-owned egress). EP-0002 / EP-0015: the wire-egress frame is the
  one the accessor RESOLVED for the read, NOT the eval-time ambient
  scope. Every accessor here first resolves a frame-id (`resolve-frame`)
  to pick WHICH frame's app-db / trace ring / runtime-db it reads, then
  the value is projected under THAT SAME frame's classification. Without
  the explicit `:frame`, `elide-wire-value` would fall back to the
  carried-invariant ambient scope (`frame/resolve-current-frame`), which
  under an MCP/eval seam is nil or the Xray/`:rf/default` frame — so a
  `(get-app-db {:frame :host})` read would project `:host`'s value under
  no frame (fail-closed → whole value redacted) or the wrong frame's
  policy. Threading the resolved `:frame` keeps the read and its
  projection on the same frame. A truly frameless value (no resolved
  frame) passes `:frame` nil and fails closed in the walker
  (`:rf/redacted`) unless `:include-sensitive? true` waives it.

  Every direct-read accessor on this runtime calls this fn before a
  value crosses the off-box boundary (MUST-inventory rows #15 / #17 /
  #19). rf2-rcogp — the safe path is the short path."
  ([value]
   (egress-value value nil))
  ([value {:keys [include-sensitive? include-large? path frame]
           :or   {include-sensitive? false
                  include-large?     false}}]
   (rf/elide-wire-value value
                        (cond-> {:rf.size/include-sensitive? include-sensitive?
                                 :rf.size/include-large?     include-large?}
                          (seq path)   (assoc :path (vec path))
                          (some? frame) (assoc :frame frame)))))

(defn egress-record
  "The single named off-box safe-egress fn for one epoch record. Routes
  the `:rf/epoch-record` through the framework's normative epoch
  projection (`re-frame.core/projected-record`) so payload slots are
  wire-elided with off-box defaults while bookkeeping slots (`:epoch-id`,
  `:dispatch-id`, `:outcome`, …) pass through unchanged.

  `projected-record` bakes the off-box defaults — naming the off-box
  egress of an epoch record `egress-record` (parallel to `egress-value`
  for arbitrary values) gives a forwarder author ONE obvious safe entry
  point for either shape instead of a choice between `elide-wire-value`
  (and the right opts) and `projected-record` they must first learn the
  difference between.

  ## Opt-back-in preserves every partition boundary (rf2-5w06uu)

  When a caller that is itself the trust boundary opts in to seeing
  sensitive or large APP-DB slots (`{:include-sensitive? true}` /
  `{:include-large? true}`), the opts are THREADED INTO `projected-record`
  rather than the record being walked raw through `egress-value`. This is
  load-bearing: those opts govern the APP-DB partition's privacy / size
  posture ONLY — they are ORTHOGONAL to the runtime-db partition
  boundary. `projected-record` keeps the `:rf.db/runtime` side of each
  frame-state slot (machine snapshots, route slice, spawn registry,
  elision registry, SSR/hydration metadata) `:rf/redacted` even under
  those opt-ins. The earlier bypass — walking the raw record through
  `egress-value` on opt-in — lifted that orthogonal runtime-db partition
  off-box just because the caller asked for sensitive / large APP-DB
  values (and mis-rooted the app-db path tracker so frame-state app-db
  sensitive declarations never matched). Routing through the normative
  projection both closes the leak and honours Security.md §Off-box
  egress's prohibition on per-tool reimplementation of the projection.

  A TRUSTED-LOCAL caller that genuinely needs runtime-db diagnostics
  opts into the partition explicitly with `{:include-runtime-db? true}`,
  which `projected-record` honours (the runtime-db value then rides the
  same value walk, where its own per-slot `:sensitive?` / `:large?`
  declarations still apply). Mirrors `egress-runtime-db-value`'s
  partition opt-in for the live-read accessors.

  rf2-rcogp — the safe path is the short path: the bare
  `(egress-record record)` is the fully-redacted off-box default."
  ([record]
   (rf/projected-record record))
  ([record {:keys [include-sensitive? include-large? include-runtime-db?]
            :or   {include-sensitive?  false
                   include-large?      false
                   include-runtime-db? false}}]
   (rf/projected-record record {:include-sensitive?  include-sensitive?
                                :include-large?      include-large?
                                :include-runtime-db? include-runtime-db?})))

;; ---------------------------------------------------------------------------
;; Partition-aware runtime-db egress (EP-0001 rf2-jj1xer · Mike ruling #14)
;; ---------------------------------------------------------------------------
;;
;; A frame-state projection has TWO partitions: the user `app-db`
;; (`:rf.db/app`) and the framework `runtime-db` (`:rf.db/runtime` —
;; machine snapshots, route slice, spawn registry, SSR/hydration
;; metadata, the elision registry). Per Spec 011 §Off-box redaction +
;; Spec 009 §Privacy + Security.md, off-box egress (this runtime is the
;; AI/MCP + log boundary) DEFAULT-REDACTS the runtime-db partition: only
;; the app-db partition (subject to its own `:sensitive?` / `:large?`
;; elision via `egress-value`) and explicitly allowlisted serializable
;; runtime-db facts cross the wire. A TRUSTED-LOCAL caller (the developer
;; inspecting their own running app) may request richer runtime-db
;; diagnostics explicitly via `:include-runtime-db? true`.
;;
;; This is the runtime-egress analog of the framework's normative
;; `re-frame.epoch.tool-pair/elide-frame-state-slot` — which redacts the
;; `:rf.db/runtime` partition of an epoch's `:frame-state-before` /
;; `:frame-state-after` to `:rf/redacted` on the off-box default path.
;; The runtime-db egress here mirrors that posture for the LIVE-read
;; accessors (`get-machine-state`) that surface runtime-db state directly
;; rather than through an already-projected epoch record.

(defn egress-runtime-db-value
  "Off-box safe-egress fn for a value drawn from the framework RUNTIME-DB
  partition (a machine snapshot, the route slice, the spawn registry — any
  `:rf.db/runtime` state). Per Mike ruling #14 (Spec 011 §Off-box redaction
  + Spec 009 §Privacy) runtime-db is REDACTED/OMITTED off-box by default:
  the safe default substitutes the framework `:rf/redacted` sentinel rather
  than walking + shipping the live runtime-db value to the AI/MCP / log
  boundary.

      (egress-runtime-db-value v)                          ; safe → :rf/redacted
      (egress-runtime-db-value v {:include-runtime-db? true}) ; trusted-local opt-in

  When a TRUSTED-LOCAL caller opts in (`:include-runtime-db? true`) the
  value still routes through `egress-value` so any `:sensitive?` / `:large?`
  slots inside the runtime-db value (e.g. a `:sensitive?` `[:schemas :data]`
  slot on a machine snapshot) are elided per their own declarations — the
  partition opt-in lifts the runtime-db redaction, NOT the per-slot privacy
  / size posture. `:include-sensitive?` / `:include-large?` carry through to
  that inner walk; `:path` threads the absolute slice path so declarations
  keyed by path still match; `:frame` threads the resolved frame so the
  inner walk projects against THAT frame's classification (EP-0015
  frame-owned egress), not the eval-time ambient scope. The partition
  opt-out is the partition default; it composes with — does not override —
  the value-level off-box defaults.

  This is the partition-distinguishing peer of `egress-value` (app-db
  partition): app-db egresses subject to per-slot elision, runtime-db
  egresses redacted-whole unless the trusted-local opt-in is set
  (rf2-jj1xer)."
  ([value]
   (egress-runtime-db-value value nil))
  ([value {:keys [include-runtime-db?] :as opts}]
   (if include-runtime-db?
     (egress-value value (dissoc opts :include-runtime-db?))
     :rf/redacted)))

;; ---------------------------------------------------------------------------
;; Per-slot resource-payload egress (rf2-tgm1xu)
;; ---------------------------------------------------------------------------
;;
;; A resource cache entry is RUNTIME-DB state, but the EP-0003 tool contract
;; (Spec 016 §Xray, line 314) is that a REDACTED SUMMARY STILL EXPOSES
;; METADATA: the operator must always see status / owners / tags / request-id
;; / generation / timestamps to answer "is it stale, who owns it, what's the
;; request id" — those are non-PII bookkeeping facts. Only the PAYLOAD slots
;; (`:data` / `:error` / `:refresh-error`) and the key's scope/params carry
;; PII or a large blob, so ONLY they follow the off-box egress posture.
;;
;; `resource-egress-fn` builds the `(fn [value slot-key])` closure
;; `resources-helpers/project-instances` / `instance-row` call on each
;; payload value (scope / params / data / error / refresh-error) BEFORE
;; summarizing. It routes the value through `egress-runtime-db-value` so the
;; runtime-db PARTITION default holds (ruling #14 — a raw runtime-db payload
;; does NOT cross off-box unless the trusted-local `:include-runtime-db?
;; true` opt-in is set), and under the opt-in the value walks through the
;; per-slot `:sensitive?` / `:large?` posture (a value already redacted/
;; elided upstream keeps its sentinel). The in-panel
;; `resources-helpers/summarize` always wraps the result, so even an
;; opted-in raw payload renders as a bounded summary, never a flood. The
;; helper threads the slot's relative runtime-db path so a per-slot
;; declaration (e.g. all `:data` payloads) can match under the opt-in.

(defn- resource-egress-fn
  "Return the `(fn [value slot-key] -> egressed)` payload-egress closure for
  the resource accessors (rf2-tgm1xu). Each value routes through
  `egress-runtime-db-value` with `egress-opts` — the runtime-db partition
  default (redact unless `:include-runtime-db? true`) composed with the
  per-slot value posture under the opt-in. The slot's relative runtime-db
  path (`[:rf.runtime/resources :entries <slot>]`) is threaded so a
  declaration keyed by that path still matches under the opt-in."
  [egress-opts]
  (fn [value slot-key]
    (egress-runtime-db-value
      value
      (assoc egress-opts
             :path (conj (vec resources-helpers/entries-rel-path) slot-key)))))

;; ---------------------------------------------------------------------------
;; Event-level default-suppress gate (rf2-to36uj)
;; ---------------------------------------------------------------------------
;;
;; `egress-value` scrubs the VALUES carried inside a trace event, but it
;; does NOT drop a whole event that is itself marked `:sensitive? true`.
;; The framework's per-frame rings RETAIN every emitted event with no
;; `:sensitive?` check (`re-frame.trace.tooling/push-to-ring!` is a
;; faithful record of what the runtime emitted), so a sensitive event's
;; ENVELOPE — its existence, `:op-type`, timing, source, handler/event
;; ids, and any non-elided `:tags` — survives value-scrubbing and would
;; cross the off-box AI/MCP / log boundary by default.
;;
;; Per Spec 009 §Privacy + spec/013-Trace-Consumer.md (the same contract
;; the panel-side trace collector + `snapshot-from-rings` honour via
;; `config/suppress-sensitive?`): a framework-published trace consumer
;; default-SUPPRESSES whole `:sensitive? true` events. The runtime/MCP
;; seam's opt-back-in is the per-call `:include-sensitive?` opt (NOT the
;; panel's local-render egress profile — the seam is per-call, so the gate
;; is per-call too; the panel's `:rf.xray/egress-profile` governs the
;; on-box trace-collector display, not this off-box accessor). We compose
;; against the ONE
;; framework primitive `re-frame.core/sensitive?` (re-export of
;; `re-frame.privacy/sensitive?`) rather than reimplementing the
;; `:sensitive? true` check, exactly as `config/sensitive-event?` does.

(defn- drop-sensitive-events
  "Default-suppress whole `:sensitive? true` trace events at the
  runtime/MCP seam. `include-sensitive?` truthy is the explicit
  per-call opt-back-in — pass it and the sensitive ENVELOPES survive
  (their VALUES are still routed through `egress-value` by the caller).
  When `include-sensitive?` is false / nil (the safe default) every
  event for which `re-frame.core/sensitive?` is true is removed before
  the events cross the off-box boundary (rf2-to36uj — the envelope, not
  just the value, must respect the default-suppress contract)."
  [events include-sensitive?]
  (if include-sensitive?
    events
    (into [] (remove rf/sensitive?) events)))

;; ---------------------------------------------------------------------------
;; Per-event frame resolution for trace-value egress (rf2-5b2ct2)
;; ---------------------------------------------------------------------------
;;
;; EP-0015 frame-owned egress: a trace event's VALUES must project under the
;; classification of the FRAME THAT EMITTED IT. These are RAW trace events
;; (the `get-trace-buffer` / `get-issues` reads forward `{:flat true}`
;; ring slices), so each event's frame rides under `[:tags :frame]` — read
;; via the canonical reader `re-frame.trace/trace-event-frame` (rf2-7737vq).
;; For a per-frame ring read (`get-trace-buffer`) every surviving event
;; already belongs to the resolved frame, but `get-issues` MERGES rings
;; across frames, so each event must project under its OWN frame, not one
;; ambient/resolved frame. When an event carries no frame (a frameless
;; emit), fall back to the accessor's resolved frame so the read and the
;; projection stay aligned; a truly frameless value then fails closed in
;; the walker. The prior divergent top-level `:frame` fallback is removed
;; (raw events never carry top-level `:frame` per the ruling).

(defn- egress-event-frame
  "Resolve the frame-id a RAW trace event belongs to for value egress
  (EP-0015 frame-owned projection). Reads the event's `[:tags :frame]`
  via the canonical `re-frame.trace/trace-event-frame` reader; `fallback`
  is the accessor's resolved frame, used when the event carries none.
  rf2-5b2ct2 · rf2-7737vq."
  [ev fallback]
  (or (trace/trace-event-frame ev)
      fallback))

(defn- egress-trace-event
  "Egress one trace event's VALUES under its OWN frame's classification
  (EP-0015 frame-owned egress, rf2-5b2ct2). The event's frame resolves
  from its `[:tags :frame]` (canonical reader), falling back to the
  accessor's resolved `fid`. `egress-opts` carries the caller's
  `:include-sensitive?` / `:include-large?` opt-ins."
  [ev fid egress-opts]
  (egress-value ev (assoc egress-opts :frame (egress-event-frame ev fid))))

;; ---------------------------------------------------------------------------
;; Inspection band (9 accessors)
;; ---------------------------------------------------------------------------

(defn get-trace-buffer
  "Tool: `get-trace-buffer`. Return a slice of the trace stream by
  filter; forwards to `(trace-tooling/trace-buffer frame-id opts)`.
  Filter keys are the canonical Spec 009 filter vocabulary
  (`:operation`, `:op-type`, `:since`, `:severity`, `:event-id`,
  `:handler-id`, `:source`, `:origin`, `:dispatch-id`, `:since-ms`,
  `:between`, `:pred`).

  Per rf2-g1b2m / rf2-8uwce the ring is now per-frame and cascade-
  keyed. `:frame` selects the frame (defaults to the sole-registered
  one via `resolve-frame`); the response emits raw trace events (via
  `{:flat true}`) so the legacy flat-event consumer shape is
  preserved. Cascade-bundle reads land in a follow-on bead — this
  tool is the historical flat-events surface and stays so.

  Returns `{:ok? true :events <vec> :count <n> :frame <frame-id>}`
  on success, or `{:ok? false :reason :no-frame-resolved ...}` when
  ambiguous. Whole `:sensitive? true` events are default-SUPPRESSED at
  this off-box seam (`drop-sensitive-events`) — the framework rings
  retain them, but the consumer contract (Spec 009 §Privacy) drops the
  whole envelope unless the caller explicitly opts in with
  `:include-sensitive? true`. Each surviving event is then routed
  through `egress-value` so any sensitive / large VALUES are scrubbed at
  the wire boundary (MUST-inventory rows #2 / #15 / #19)."
  ([] (get-trace-buffer {}))
  ([opts]
   (let [{:keys [frame include-sensitive? include-large?]} opts
         fid (resolve-frame frame)]
     (if-let [fail (frame-failure frame fid)]
       fail
       (let [filter-opts (-> opts
                             (dissoc :frame :include-sensitive? :include-large?)
                             (assoc :flat true))
             events      (-> (trace-tooling/trace-buffer fid filter-opts)
                             (drop-sensitive-events include-sensitive?))
             egress-opts {:include-sensitive? include-sensitive?
                          :include-large?     include-large?}
             scrubbed    (mapv #(egress-trace-event % fid egress-opts) events)]
         {:ok?    true
          :frame  fid
          :events scrubbed
          :count  (count scrubbed)})))))

(defn get-epoch-history
  "Tool: `get-epoch-history`. Per-frame epoch history (vector of
  `:rf/epoch-record`) per Tool-Pair §Time-travel. Returns
  `{:ok? true :frame <id> :epochs <vec>}` or `{:ok? false :reason
  :no-frame-resolved}` when the frame can't be picked. Each record
  routes through `egress-record` for privacy + size egress.

  `:include-sensitive?` / `:include-large?` opt the APP-DB partition's
  sensitive / large values back in. The framework runtime-db partition
  (`:rf.db/runtime` in each record's frame-state slots) stays redacted
  under those opts — a trusted-local caller that genuinely needs
  runtime-db diagnostics passes `:include-runtime-db? true` explicitly
  (rf2-5w06uu)."
  ([] (get-epoch-history nil))
  ([opts]
   (let [{:keys [frame include-sensitive? include-large? include-runtime-db?]} opts
         fid (resolve-frame frame)]
     (if-let [fail (frame-failure frame fid)]
       fail
       (let [records  (rf/epoch-history fid)
             scrubbed (mapv #(egress-record % {:include-sensitive?  include-sensitive?
                                               :include-large?      include-large?
                                               :include-runtime-db? include-runtime-db?})
                            records)]
         {:ok?    true
          :frame  fid
          :epochs scrubbed
          :count  (count scrubbed)})))))

(defn get-app-db
  "Tool: `get-app-db`. Current `app-db` value at a frame, optionally
  scoped by `:path`. Routes through `elide-wire-value` (MUST-inventory
  row #19 — direct-read privacy posture). When `:path` is supplied the
  absolute path is threaded into the egress walker so a scoped slice is
  elided against the frame-owned `:sensitive` / `:large` app-db declarations
  (keyed by absolute path) — fail-closed, symmetric with the whole-db read and
  the `get-app-db-diff` slices (rf2-a96xq). Returns
  `{:ok? true :frame <id> :path <vec> :value <edn>}` or
  `{:ok? false :reason :no-frame-resolved}`."
  ([] (get-app-db nil))
  ([opts]
   (let [{:keys [frame path include-sensitive? include-large?]} opts
         fid (resolve-frame frame)]
     (if-let [fail (frame-failure frame fid)]
       fail
       (let [db    (rf/app-db-value fid)
             value (if (seq path) (get-in db path) db)]
         {:ok?   true
          :frame fid
          :path  (vec path)
          ;; Thread the ABSOLUTE app-db `:path` into the egress walker so
          ;; a `:path`-scoped slice is elided against the frame-owned
          ;; `:sensitive` / `:large` app-db declarations (keyed by absolute
          ;; path) — without
          ;; it the walker starts the sliced value at root `[]` and a
          ;; declaration registered for e.g. `[:auth :password]` never
          ;; matches a direct read of `{:path [:auth :password]}`, leaking
          ;; the raw leaf off-box (rf2-a96xq). Symmetric with the sibling
          ;; `get-app-db-diff` slices, which egress each leaf at its
          ;; absolute path. An unscoped read passes `path` = `nil`, and
          ;; `egress-value` only assoc's `:path` when `(seq path)`, so the
          ;; whole-db case is unchanged (value IS the walked root).
          :value (egress-value value {:include-sensitive? include-sensitive?
                                      :include-large?     include-large?
                                      :path               path
                                      ;; EP-0015 frame-owned egress: project
                                      ;; the value under the SAME frame the
                                      ;; read resolved (`fid`), not the
                                      ;; eval-time ambient scope (rf2-5b2ct2).
                                      :frame              fid})})))))

(defn- project-changed-paths
  "Project the `(before, after)` pair into the changed-paths slice diff
  the `get-app-db-diff` docstring promises:

      {:added   [{:path <vec> :value <egress'd after-slice>} ...]
       :removed [{:path <vec> :value <egress'd before-slice>} ...]
       :changed [{:path <vec>
                  :before <egress'd before-slice>
                  :after  <egress'd after-slice>} ...]}

  Routes the per-path slices through `egress-value` (rf2-uv2q2) so the
  off-box privacy + size defaults apply per-slice — never the whole-db
  snapshot the prior impl egressed. Built off the canonical
  Editscript-A* engine's `:flat-rows` lens (one row per non-`:same`
  leaf op): `:added` / `:removed` map 1:1, and the engine's `:modified`
  op (scalar change, container-kind flip, redaction) buckets into
  `:changed`. `egress-opts` carries the caller's `:include-sensitive?`
  / `:include-large?` opt-in so the diff slices honour the same
  trust-boundary override the sibling accessors expose, and the resolved
  `:frame` so every slice projects under that frame's classification
  (EP-0015 frame-owned egress, rf2-5b2ct2) rather than the ambient scope.
  Each slice is egress'd at its ABSOLUTE leaf `:path` so the frame-owned
  `:sensitive` / `:large` app-db declarations still match against the
  isolated slice."
  [before after egress-opts]
  (let [{:keys [flat-rows]} (diff-engine/project before after)
        egress-at (fn [v path] (egress-value v (assoc egress-opts :path path)))]
    (reduce
      (fn [acc {:keys [path op before after]}]
        (case op
          :added    (update acc :added conj
                            {:path  path
                             :value (egress-at after path)})
          :removed  (update acc :removed conj
                            {:path  path
                             :value (egress-at before path)})
          ;; :modified (scalar change / container-kind flip / R8
          ;; one-sided redaction) buckets into :changed — both sides
          ;; egress'd per-slice.
          (update acc :changed conj
                  {:path   path
                   :before (egress-at before path)
                   :after  (egress-at after path)})))
      {:added [] :removed [] :changed []}
      flat-rows)))

(defn get-app-db-diff
  "Tool: `get-app-db-diff`. Slice diff for a named epoch — read
  `:db-before` + `:db-after` off the epoch record and project as
  `{:added [...] :removed [...] :changed [...]}` (the changed-paths
  shape per MUST-inventory row #13; the heavier nested diff lives in
  the MCP server's wire-pipeline layer).

  Each entry carries its `:path` (a path vector into the app-db) plus
  the wire-elided slice value(s) at that path — `:added` / `:removed`
  carry a single `:value`, `:changed` carries `:before` + `:after`.
  Every slice routes through `egress-value` (rf2-uv2q2) so only the
  changed paths cross the off-box boundary, scrubbed per-slice — NOT
  two whole app-db snapshots. Diff projection uses the canonical
  Editscript-A* engine (`diff.engine/project`).

  Returns `{:ok? true :frame <id> :epoch-id <uuid> :diff <map>}` or
  `{:ok? false :reason :no-such-epoch ...}` / `:no-frame-resolved`."
  [{:keys [frame epoch-id include-sensitive? include-large?] :as _opts}]
  (let [fid  (resolve-frame frame)
        fail (frame-failure frame fid)]
    (cond
      fail fail

      (nil? epoch-id)
      {:ok? false :reason :missing-epoch-id
       :hint "Pass :epoch-id <uuid>."}

      :else
      (let [records (rf/epoch-history fid)
            match   (some #(when (= epoch-id (:epoch-id %)) %) records)]
        (if (nil? match)
          {:ok? false :reason :no-such-epoch
           :frame fid :epoch-id epoch-id}
          (let [before (:db-before match)
                after  (:db-after  match)
                diff   (project-changed-paths
                         before after
                         {:include-sensitive? include-sensitive?
                          :include-large?     include-large?
                          ;; EP-0015 frame-owned egress: each changed-path
                          ;; slice projects under the SAME frame the diff
                          ;; resolved (`fid`), not the ambient scope
                          ;; (rf2-5b2ct2).
                          :frame              fid})]
            {:ok?      true
             :frame    fid
             :epoch-id epoch-id
             :diff     diff}))))))

;; The live machine snapshot lives in the frame's RUNTIME-DB partition at
;; the runtime-owned `[:rf.runtime/machines :snapshots <machine-id>]` slot
;; (EP-0001 rf2-vzld77 moved machine snapshots out of app-db `:rf/runtime`
;; into the durable runtime-db partition; `re-frame.machines.paths/snapshot-path`
;; — singleton machines key the snapshot by machine-id). The snapshot is
;; `{:state <state-path> :data … :tags …}`; its `:state` is the LIVE FSM
;; position (a state-path vector — a region→state map for a parallel
;; machine, per Spec 005). The xray runtime reads it from the runtime-db
;; partition (`rf/runtime-db-value`) rather than reaching into a framework
;; internal: this is the same published slot the Machine Inspector's
;; `:rf.xray/machine-snapshots` sub (sourced from
;; `:rf.xray/target-frame-runtime-db`) + the framework's own resolver read.
(def ^:private machine-snapshot-path-root
  "Absolute RUNTIME-DB-partition path of the runtime-owned machines
  snapshot table, `[:rf.runtime/machines :snapshots]` (EP-0001 rf2-vzld77).
  Singleton machines key their snapshot by machine-id under this root
  (Spec 005 §Reserved runtime-db keys · `re-frame.machines.paths/snapshot-path`)."
  [:rf.runtime/machines :snapshots])

(defn get-machine-state
  "Tool: `get-machine-state`. The LIVE current state of a named machine
  in the running app — reads the machine's snapshot from the frame's
  RUNTIME-DB partition at `[:rf.runtime/machines :snapshots <machine-id>]`
  (EP-0001 rf2-vzld77 — machine snapshots are durable runtime-db state;
  the same slot the Machine Inspector's `:rf.xray/machine-snapshots` sub +
  the framework resolver read) and returns its current FSM position.
  Returns

      {:ok? true :frame <id> :machine-id <kw>
       :state <live-state-path>          ; the running FSM position
       :snapshot {:state … :data … :tags …}  ; the full live snapshot
       :spec <registered-definition>}    ; the static reg-machine spec

  or `{:ok? false :reason :no-such-machine ...}` / `:no-frame-resolved`
  / `:missing-machine-id`.

  `:state` is the machine's LIVE position — a state-path vector (e.g.
  `[:active :authenticating]`), or a region→state map for a `:parallel`
  machine (Spec 005). It is read off the live snapshot, NOT derived from
  the registered spec, so an agent asking 'what state is :auth in right
  now' gets the running answer. The static definition is available
  separately under `:spec` for callers that want transitions /
  initial-state / tags.

  When the machine is REGISTERED but has not yet been brought to life
  (no event has lazily synthesised its singleton snapshot), there is no
  live snapshot: `:state` and `:snapshot` are `nil` and `:reason` is
  `:not-yet-started` (the call still succeeds with `:ok? true` so the
  agent can read `:spec` and see the machine is registered-but-idle).

  ## Partition-aware off-box redaction (EP-0001 rf2-jj1xer · ruling #14)

  The machine snapshot is RUNTIME-DB state, NOT app-db. Per Spec 011
  §Off-box redaction the runtime-db partition is REDACTED/OMITTED off-box
  by default — so `:state` and `:snapshot` egress as the `:rf/redacted`
  sentinel on the safe default path (via `egress-runtime-db-value`). A
  TRUSTED-LOCAL caller (a developer inspecting their own running app)
  opts in to the live runtime-db diagnostics with `:include-runtime-db?
  true`; the snapshot then routes through `egress-value` against its
  absolute runtime-db-partition path so any per-slot `:sensitive?` /
  `:large?` declarations (e.g. a `:sensitive?` `[:schemas :data]` slot) still
  elide. `:spec` is a static REGISTRY value (not runtime-db state), so it
  egresses through `egress-value` (subject to its own sensitive / large
  elision) regardless of the runtime-db opt-in."
  [{:keys [frame machine-id include-sensitive? include-large?
           include-runtime-db?] :as _opts}]
  (let [fid  (resolve-frame frame)
        fail (frame-failure frame fid)]
    (cond
      fail fail

      (nil? machine-id)
      {:ok? false :reason :missing-machine-id
       :hint "Pass :machine-id <keyword>."}

      :else
      (let [spec (machines/machine-meta machine-id)]
        (if (nil? spec)
          {:ok? false :reason :no-such-machine
           :frame fid :machine-id machine-id
           :registered (vec (machines/machines))}
          (let [snapshot-path (conj machine-snapshot-path-root machine-id)
                ;; EP-0001 rf2-vzld77 — read the snapshot from the RUNTIME-DB
                ;; partition, not app-db. The partition distinction is the
                ;; correctness fix (the old app-db `:rf/runtime` slot is now
                ;; empty) AND the off-box-redaction site (runtime-db state is
                ;; redacted by default unless the trusted-local opt-in is set).
                snapshot      (get-in (rf/runtime-db-value fid) snapshot-path)
                rt-egress     {:include-sensitive?  include-sensitive?
                               :include-large?      include-large?
                               :include-runtime-db? include-runtime-db?
                               ;; EP-0015 frame-owned egress: the runtime-db
                               ;; snapshot projects under the resolved frame
                               ;; `fid` (rf2-5b2ct2).
                               :frame               fid}
                spec-edn      (egress-value spec {:include-sensitive? include-sensitive?
                                                  :include-large?     include-large?
                                                  :frame              fid})]
            (if (nil? snapshot)
              ;; Registered but not yet brought to life — no live snapshot
              ;; in the runtime-db partition. Succeed with the spec so the
              ;; agent can see the machine exists, but make the absence of a
              ;; live position explicit (so it can't be mistaken for state).
              {:ok?        true
               :frame      fid
               :machine-id machine-id
               :state      nil
               :snapshot   nil
               :spec       spec-edn
               :reason     :not-yet-started}
              ;; The snapshot is runtime-db state — redact off-box by
              ;; default (ruling #14); trusted-local opts back in via
              ;; `:include-runtime-db?`, and the inner walk then honours the
              ;; per-slot sensitive / large declarations keyed by path.
              {:ok?        true
               :frame      fid
               :machine-id machine-id
               :state      (egress-runtime-db-value
                             (:state snapshot)
                             (assoc rt-egress :path (conj snapshot-path :state)))
               :snapshot   (egress-runtime-db-value
                             snapshot
                             (assoc rt-egress :path snapshot-path))
               :spec       spec-edn})))))))

(defn get-machine-list
  "Tool: `get-machine-list`. List of registered machines per frame
  with current spec. Returns `{:ok? true :machines <map>}` where the
  map is keyed by machine-id.

  EP-0015 frame-owned egress (rf2-5b2ct2): each machine spec is a static
  REGISTRY value, but the wire walker classifies against a KNOWN frame —
  the operating frame (explicit `:frame`, else the sole registered frame)
  is resolved and threaded so the spec projects under that frame's policy
  rather than fail-closing under the eval-time ambient scope."
  ([] (get-machine-list nil))
  ([{:keys [include-sensitive? include-large? frame] :as _opts}]
   (let [ids (machines/machines)
         fid (resolve-frame frame)]
     {:ok?      true
      :machines (into {}
                      (map (fn [mid]
                             [mid (egress-value (machines/machine-meta mid)
                                                {:include-sensitive? include-sensitive?
                                                 :include-large?     include-large?
                                                 :frame              fid})]))
                      ids)
      :count    (count ids)})))

;; ---------------------------------------------------------------------------
;; Resource accessors (Spec 016 §Xray and AI tooling — 5 tool accessors)
;; ---------------------------------------------------------------------------
;;
;; list-resources / list-resource-instances / get-resource-state /
;; get-resource-history / list-resource-invalidations. Filter axes:
;; frame / scope / resource-id / tag / owner / status / stale? /
;; request-id / nav-token (Spec 016).
;;
;; PRIVACY (Spec 016, load-bearing): tool surfaces PREFER SUMMARIES over
;; raw values; params + scopes get the SAME privacy + size elision as
;; data (scopes carry user/tenant/locale/impersonation ids); history is
;; BOUNDED. Two elision layers compose:
;;   1. the in-panel `resources-helpers/summarize` shape (always applied
;;      — type + bounded size + a redaction-aware preview, never raw);
;;   2. the framework off-box `egress-runtime-db-value` / `egress-value`
;;      walker on the raw runtime-db values an accessor surfaces, so a
;;      `:sensitive?` data/scope slot egresses as `:rf/redacted` and a
;;      `:large?` slot as `:rf.size/large-elided` on the off-box default
;;      path. The resource cache is RUNTIME-DB state, so the raw-value
;;      egress default-redacts the partition (ruling #14) — a trusted-
;;      local caller opts in with `:include-runtime-db? true`.
;;
;; READ-ONLY (Spec 016 §Active owners and causes): an accessor NEVER
;; dispatches `:rf.resource/ensure`, attaches an owner, refetches, or
;; extends GC. Inspection has zero side effects on resource liveness —
;; Xray (and the AI/MCP boundary it serves) MUST NOT become an owner by
;; observing.

(def ^:private resource-kind
  "The `:resource` registrar kind (Spec 016 §Registration). A duplicated
  literal — the runtime does not :require the optional resources artefact
  (bundle isolation), reading the static registry process-globally via
  `(rf/registrations :resource)`."
  :resource)

(defn list-resources
  "Tool: `list-resources`. The STATIC resource registry (Spec 016 §Xray
  and AI tooling — the static resource registry): id, source coords,
  params/data schemas (summarized), request summary, stale/GC policy, tag
  producer, scope policy, sensitivity/large class, and declaring routes.
  Filter axis: `:resource-id` (a single id) — nil lists all.

  Returns `{:ok? true :resources [<registry-row> …] :count N}`. The rows
  carry only static REGISTRY facts (no live values), so they egress
  freely; param/data schemas are summarized in case they are large."
  ([] (list-resources nil))
  ([{:keys [resource-id]}]
   (let [registrations (rf/registrations resource-kind)
         routes-map    (rf/registrations :route)
         rows          (cond->> (resources-helpers/project-registry registrations routes-map)
                         (some? resource-id) (filter #(= resource-id (:resource-id %))))]
     {:ok?       true
      :resources (vec rows)
      :count     (count rows)})))

(defn list-resource-instances
  "Tool: `list-resource-instances`. The LIVE per-frame resource-instance
  table (Spec 016 §Xray and AI tooling): resource key, scope, status,
  timestamps, generation, request id, attempt, active owners, tags, data
  summary, GC eligibility. Filter axes: `:frame`, `:scope`,
  `:resource-id`, `:params`, `:status`, `:stale?`, `:tag`, `:owner`,
  `:request-id`.

  Reads the cache entries from the frame's RUNTIME-DB partition at
  `[:rf.runtime/resources :entries]` (EP-0001 — resource cache is
  framework-owned runtime-db state).

  PRIVACY (rf2-tgm1xu — the EP-0003 contract that a redacted summary STILL
  exposes metadata; Spec 016 §Xray, line 314): the projection algebra
  always `summarize`s scope/params/data (type + bounded size + a
  redaction-aware preview, never the raw value). On top of that the
  PAYLOAD slots — `:data`/`:error`/`:refresh-error` in the entry and the
  scope/params in the key — route through the off-box `resource-egress-fn`
  walker so a `:sensitive?` declaration egresses them as `:rf/redacted` and
  a `:large?` slot as `:rf.size/large-elided`. The non-PII metadata
  (status, generation, attempt, request-id, current-work, active-owners,
  tags, timestamps) is NEVER redacted — it projects so the status / tag /
  owner / request-id filters (which filter the PROJECTED rows) work without
  `:include-runtime-db?`. The trusted-local `:include-runtime-db? true`
  opt-in lifts even a non-declared payload to its raw value (still capped
  by the in-panel summary).

  Returns `{:ok? true :frame <id> :instances [<row> …] :count N}` or
  `{:ok? false :reason :no-frame-resolved}`."
  ([] (list-resource-instances nil))
  ([{:keys [frame scope resource-id params status stale? tag owner request-id
            include-sensitive? include-large? include-runtime-db?] :as _opts}]
   (let [fid (resolve-frame frame)]
     (if-let [fail (frame-failure frame fid)]
       fail
       (let [runtime-db  (rf/runtime-db-value fid)
             all-entries (get-in runtime-db resources-helpers/entries-rel-path)
             ;; upstream key-filter BEFORE projection (scope/resource-id/params
             ;; against the raw cache key)
             entries     (resources-helpers/select-raw-entries
                           all-entries
                           {:scope scope :resource-id resource-id :params params})
             egress-opts {:include-sensitive?  include-sensitive?
                          :include-large?      include-large?
                          :include-runtime-db? include-runtime-db?
                          ;; EP-0015 frame-owned egress: project the
                          ;; runtime-db payloads under the resolved frame
                          ;; `fid` (rf2-5b2ct2).
                          :frame               fid}
             ;; PER-SLOT egress (rf2-tgm1xu): the projection redacts ONLY the
             ;; payload values (scope/params/data/error) BEFORE summarizing,
             ;; while the metadata projects from the raw entry — so the rows
             ;; are USEFUL and the status/tag/owner/request-id filters (which
             ;; filter the projected rows) match. The RAW key is kept as the
             ;; row identity so two entries whose scope/params redact to the
             ;; same sentinel never collapse. Each value egresses at its
             ;; ABSOLUTE runtime-db slot path so per-slot :sensitive? /
             ;; :large? declarations match.
             rows        (-> (resources-helpers/project-instances
                               entries (.now js/Date)
                               (resource-egress-fn egress-opts))
                             (resources-helpers/filter-instance-rows
                               {:resource-id resource-id :status status :stale? stale?
                                :tag tag :owner owner :request-id request-id}))]
         {:ok?       true
          :frame     fid
          :instances rows
          :count     (count rows)})))))

(defn get-resource-state
  "Tool: `get-resource-state`. The LIVE durable state of ONE resource
  instance addressed by its scoped key (Spec 016 §Introspection /
  §Status semantics). Resolves the entry at `[:rf.runtime/resources
  :entries [scope resource-id params]]` in the frame's runtime-db.

  Required: `:resource-id` + `:scope` + `:params` (the scoped key). Per
  EP-0002 the frame target is carried explicitly (`:frame`); a frameless
  call with no resolvable context fails closed.

  Required scoped-key parts: `:resource-id` AND `:scope` AND `:params`.
  Any missing part fails closed with `:reason :missing-key` (a partial key
  cannot address an entry — Spec 016 §Introspection: the scoped key is the
  full `[scope resource-id params]` triple).

  Returns `{:ok? true :frame <id> :state <instance-row>}` (the row
  carries the PRIVACY-summarized status/data/error projection — Spec 016
  §Status semantics: `:stale?`/`:has-data?` are derived, not stored) or
  `{:ok? false :reason :no-such-instance ...}` / `:no-frame-resolved` /
  `:missing-key`.

  PRIVACY (rf2-tgm1xu): the projection always `summarize`s scope/params/
  data; on top, the PAYLOAD slots (`:data`/`:error`/`:refresh-error` + the
  key's scope/params) route through `resource-egress-fn` (runtime-db
  default-redacted off-box; `:include-runtime-db? true` to opt in to the
  raw payload). The non-PII metadata (status / owners / tags / request-id /
  generation / timestamps) is NEVER redacted — a redacted summary STILL
  exposes the metadata (the EP-0003 contract)."
  [{:keys [frame resource-id scope params
           include-sensitive? include-large? include-runtime-db?]}]
  (let [fid  (resolve-frame frame)
        fail (frame-failure frame fid)]
    (cond
      fail fail

      ;; A scoped key is the full [scope resource-id params] triple — any
      ;; missing part cannot address an entry (rf2-tgm1xu: missing scope or
      ;; params reports :missing-key, not a silent nil-key probe).
      (or (nil? resource-id) (nil? scope) (nil? params))
      {:ok? false :reason :missing-key
       :hint "Pass :resource-id + :scope + :params (the full scoped key)."}

      :else
      (let [scoped-key  [scope resource-id params]
            runtime-db  (rf/runtime-db-value fid)
            entry-path  (conj (vec resources-helpers/entries-rel-path) scoped-key)
            entry       (get-in runtime-db entry-path)]
        (if (nil? entry)
          {:ok? false :reason :no-such-instance
           :frame fid :resource-id resource-id}
          (let [egress-opts {:include-sensitive?  include-sensitive?
                             :include-large?      include-large?
                             :include-runtime-db? include-runtime-db?
                             ;; EP-0015 frame-owned egress: project the
                             ;; runtime-db payload under the resolved frame
                             ;; `fid` (rf2-5b2ct2).
                             :frame               fid}]
            ;; PER-SLOT egress (rf2-tgm1xu): the projection redacts only the
            ;; payload values (scope/params/data/error) before summarizing;
            ;; the metadata projects from the raw entry. The RAW scoped-key
            ;; is the row identity.
            {:ok?   true
             :frame fid
             :state (resources-helpers/instance-row
                      [scoped-key entry] (.now js/Date)
                      (resource-egress-fn egress-opts))}))))))

;; rf2-e0mq7a — the trace-value egress closure for the resource
;; history/invalidation accessors. The lifecycle-timeline / invalidation-graph
;; helpers project VALUE-BEARING fields off trace tags (the scoped key's
;; scope/params, the cause, the matched keys). Those are trace-borne VALUES
;; (NOT runtime-db `:entries` payloads — they live in the trace ring), so they
;; egress through the plain `egress-value` walker with the off-box
;; sensitive/large defaults baked in, NOT `resource-egress-fn` (which layers
;; the runtime-db partition default + the `:entries` slot path). This is the
;; trace-buffer peer of the per-slot egress the live-cache accessors thread:
;; `list-resource-instances` / `get-resource-state` route runtime-db payloads
;; through `resource-egress-fn`; these two route trace payloads through
;; `egress-value`. Both honour per-slot `:sensitive?` / `:large?` declarations
;; (matched by schema path) and both default-redact, opting back in per call.
;; `drop-sensitive-events` already removes whole `:sensitive? true` ENVELOPES
;; upstream; this scrubs the VALUES carried inside the surviving events.
(defn- resource-trace-egress-fn
  "Return the `(fn [value] -> egressed)` value-egress closure the resource
  trace projections (`lifecycle-timeline` / `invalidation-graph`) apply to
  their value-bearing fields (scope / params / cause / matched). Routes
  through `egress-value` with the off-box defaults; `include-sensitive?` /
  `include-large?` opt the matching declared slots back in (rf2-e0mq7a).
  `:frame` threads the accessor's resolved frame so the value-bearing trace
  fields project under THAT frame's classification (EP-0015 frame-owned
  egress, rf2-5b2ct2) rather than the eval-time ambient scope."
  [{:keys [include-sensitive? include-large? frame]}]
  (fn [value]
    (egress-value value (cond-> {:include-sensitive? include-sensitive?
                                 :include-large?     include-large?}
                          (some? frame) (assoc :frame frame)))))

(defn get-resource-history
  "Tool: `get-resource-history`. The BOUNDED lifecycle history for a
  resource (Spec 016 §Xray and AI tooling — the lifecycle timeline;
  history MUST be bounded). Projects the `:rf.resource/*` trace family
  out of the frame's trace ring into ordered lifecycle rows. Filter axes:
  `:resource-id`, `:nav-token`, `:limit` (default 50 — the bound).

  Returns `{:ok? true :frame <id> :history [<timeline-row> …] :count N}`
  or `{:ok? false :reason :no-frame-resolved}`.

  PRIVACY (rf2-e0mq7a — the off-box egress boundary for the value-bearing
  trace fields): TWO layers, mirroring the live-cache accessors.
    1. Whole `:sensitive? true` trace ENVELOPES are default-suppressed
       (`drop-sensitive-events`); `:include-sensitive? true` opts them back
       in.
    2. The VALUE-BEARING fields carried INSIDE the surviving events — the
       scoped key's scope/params (PII) and the cause (may carry mutation
       data) — route through `egress-value` (`resource-trace-egress-fn`)
       BEFORE the in-panel `summarize`, so per-slot `:sensitive?` / `:large?`
       declarations redact / elide them by default. `:include-sensitive?` /
       `:include-large?` are the trusted-local opt-ins. The METADATA
       (operation / class / resource-id / generation / owner / status) is
       NEVER egressed — a redacted timeline STILL exposes the lifecycle shape
       and the resource-id filter stays useful."
  ([] (get-resource-history nil))
  ([{:keys [frame resource-id nav-token limit include-sensitive? include-large?]
     :or   {limit 50} :as _opts}]
   (let [fid (resolve-frame frame)]
     (if-let [fail (frame-failure frame fid)]
       fail
       (let [events    (-> (trace-tooling/trace-buffer fid {:flat true})
                           (drop-sensitive-events include-sensitive?))
             egress-fn (resource-trace-egress-fn
                         {:include-sensitive? include-sensitive?
                          :include-large?     include-large?
                          :frame              fid})
             rows      (-> (resources-helpers/lifecycle-timeline events egress-fn)
                           (resources-helpers/filter-history-rows
                             {:resource-id resource-id :nav-token nav-token :limit limit}))]
         {:ok?     true
          :frame   fid
          :history (vec rows)
          :count   (count rows)})))))

(defn list-resource-invalidations
  "Tool: `list-resource-invalidations`. The invalidation / mutation graph
  (Spec 016 §Invalidation / §Xray and AI tooling). Projects the
  `:rf.resource/invalidated` trace rows — each carrying the scope
  (summarized), the invalidated tags, the cause (summarized), the matched
  scoped keys, and the refetch count. Distinguishes a broad-tag storm
  (high `:match-count`) and a zero-match invalidation (`:match-count 0`).
  Filter axis: `:tag` (only invalidations touching the tag).

  Returns `{:ok? true :frame <id> :invalidations [<row> …] :count N}` or
  `{:ok? false :reason :no-frame-resolved}`.

  PRIVACY (rf2-e0mq7a — symmetric with `get-resource-history`): whole
  `:sensitive? true` envelopes are default-suppressed, AND the value-bearing
  fields (`:scope` / `:cause` / each `:matched` key's scope/params) route
  through `egress-value` BEFORE `summarize` so per-slot `:sensitive?` /
  `:large?` declarations redact / elide them by default; `:include-sensitive?`
  / `:include-large?` are the trusted-local opt-ins. The non-PII metadata
  (`:tags` — the invalidation identity used by the `:tag` filter axis,
  `:match-count`, `:refetched`) is NEVER egressed, so tag filtering and the
  storm / zero-match distinction stay useful on the default-redacted path."
  ([] (list-resource-invalidations nil))
  ([{:keys [frame tag include-sensitive? include-large?] :as _opts}]
   (let [fid (resolve-frame frame)]
     (if-let [fail (frame-failure frame fid)]
       fail
       (let [events    (-> (trace-tooling/trace-buffer fid {:flat true})
                           (drop-sensitive-events include-sensitive?))
             egress-fn (resource-trace-egress-fn
                         {:include-sensitive? include-sensitive?
                          :include-large?     include-large?
                          :frame              fid})
             rows      (cond->> (resources-helpers/invalidation-graph events egress-fn)
                         (some? tag) (filter #(some #{tag} (:tags %))))]
         {:ok?           true
          :frame         fid
          :invalidations (vec rows)
          :count         (count rows)})))))

(defn get-issues
  "Tool: `get-issues`. Recent errors / warnings / schema violations /
  hydration mismatches — projection over the trace buffer filtered to
  the issue-tier `:op-type`s (`:error`, `:warning`,
  `:rf.schema/violation`, `:rf.hydration/mismatch`).

  Per rf2-g1b2m / rf2-8uwce trace rings are per-frame; iterate every
  registered frame and merge — issues fired across multiple frames
  during a single cascade reconstruct correctly. `:flat true` opts
  into the raw flat-event shape so the existing issue filter walks
  the same vocabulary.

  Whole `:sensitive? true` issue events are default-SUPPRESSED at this
  off-box seam (`drop-sensitive-events`, run on the merged stream before
  the issue-op-type filter) — symmetric with `get-trace-buffer`. The
  envelope (existence, `:op-type`, timing, source, ids, `:tags`) of a
  sensitive issue is dropped unless the caller explicitly opts in with
  `:include-sensitive? true`. Surviving issues then route through
  `egress-value` per MUST-inventory row #2.

  Returns `{:ok? true :issues <vec> :count <n>}`."
  ([] (get-issues {}))
  ([opts]
   (let [{:keys [include-sensitive? include-large?]} opts
         issue-op-types #{:error :warning
                          :rf.schema/violation
                          :rf.hydration/mismatch}
         ;; Per-frame ring: merge across frames so cross-frame issues
         ;; (e.g. an error landing in :stories while :rf/default is
         ;; the operating frame) still surface here.
         events   (into []
                        (mapcat #(trace-tooling/trace-buffer % {:flat true}))
                        (rf/frame-ids))
         ;; rf2-to36uj — default-suppress whole sensitive event envelopes
         ;; at the off-box seam before the issue filter walks them.
         events   (drop-sensitive-events events include-sensitive?)
         issues   (filterv #(contains? issue-op-types (:op-type %)) events)
         ;; EP-0015 frame-owned egress (rf2-5b2ct2): `get-issues` MERGES the
         ;; per-frame rings, so each issue must project under its OWN frame's
         ;; classification (read off `[:tags :frame]` / `:frame`), not one
         ;; ambient/resolved frame. A frameless issue has no fallback frame
         ;; here (the merge spans every frame), so it fails closed.
         egress-opts {:include-sensitive? include-sensitive?
                      :include-large?     include-large?}
         scrubbed (mapv #(egress-trace-event % nil egress-opts) issues)]
     {:ok?    true
      :issues scrubbed
      :count  (count scrubbed)})))

(def ^:private registrar-kinds
  "The canonical registrar kinds the framework's `registrar/valid-kind?`
  recognises. Used by `get-handlers` when the caller doesn't narrow via
  `:kind` — we walk each one with `rf/registrations`. Centralised so a
  new kind landing in Spec 002 is one edit here."
  [:event :sub :fx :cofx :machine :flow :reg-machine :frame :view])

(defn get-handlers
  "Tool: `get-handlers`. Registered handlers' metadata — routes
  through `rf/registrations` (per-kind map of `{id metadata}`) to
  project `{:kind kw :id any :meta {:doc :source-coord ...}}` records.

  Each handler's `:meta` map routes through `egress-value` before the
  off-box boundary (rf2-yl0v8 — handler metadata can carry user-supplied
  custom slots / a large `:doc` / a value-bearing `:tags` slot per
  Spec 001 §registrar query API), holding the runtime's
  every-read-routes-through-wire-elision invariant with no exceptions —
  the safe path is the short path. `:include-sensitive?` /
  `:include-large?` carry the same trust-boundary opt-in the sibling
  accessors expose.

  EP-0015 frame-owned egress (rf2-5b2ct2): handler metadata is a
  process-global REGISTRY value, not frame-scoped app-db, but the wire
  walker classifies against a KNOWN frame — with none it fails closed
  (whole-redact). The accessor resolves the operating frame (the explicit
  `:frame` opt, else the sole registered frame via `resolve-frame`) and
  threads it so the metadata projects under that frame's policy rather
  than redacting wholesale under the eval-time ambient scope.

  Returns `{:ok? true :handlers <vec> :count <n>}`. Optional `:kind`
  arg narrows to a single registrar kind (`:event`, `:sub`, `:fx`,
  `:cofx`, `:machine`, `:flow`, `:frame`, `:view`, `:reg-machine`).
  Optional `:frame` picks the frame whose classification applies."
  ([] (get-handlers {}))
  ([{:keys [kind include-sensitive? include-large? frame] :as _opts}]
   (let [fid     (resolve-frame frame)
         egress-opts {:include-sensitive? include-sensitive?
                      :include-large?     include-large?
                      :frame              fid}
         kinds   (if (some? kind) [kind] registrar-kinds)
         walked  (for [k kinds
                       [id meta] (rf/registrations k)]
                   {:kind k
                    :id   id
                    :meta (egress-value meta egress-opts)})]
     {:ok?      true
      :handlers (vec walked)
      :count    (count walked)})))

(defn get-source-coord
  "Tool: `get-source-coord`. Source coord for a given id (handler,
  view, machine state, sub) — projects the `:source-coord` slot off
  the handler's metadata.

  The projected `:source-coord` routes through `egress-value` before
  the off-box boundary (rf2-j8b0u), holding the runtime's
  every-read-routes-through-wire-elision invariant with NO exceptions —
  the safe path is the short path. Source-coord is structurally
  `{:ns :file :line :column}` today, but Spec 009's user-supplied
  `:rf.handler/source` override lets a code-gen pipeline stamp arbitrary
  values into the source slot, so the accessor egresses unconditionally
  rather than judging per-read whether THIS value happens to be safe.
  `:include-sensitive?` / `:include-large?` carry the same trust-boundary
  opt-in the sibling accessors (`get-handlers`) expose. EP-0015 frame-owned
  egress (rf2-5b2ct2): the operating frame (explicit `:frame`, else the
  sole registered frame) is resolved and threaded so the source-coord
  projects under that frame's classification rather than fail-closing
  under the eval-time ambient scope.

  Returns `{:ok? true :kind <kw> :id <any> :source-coord <map>}` or
  `{:ok? false :reason :no-source-coord ...}`."
  [{:keys [kind id include-sensitive? include-large? frame] :as _opts}]
  (cond
    (nil? kind) {:ok? false :reason :missing-kind
                 :hint "Pass :kind <registrar-kind>."}
    (nil? id)   {:ok? false :reason :missing-id
                 :hint "Pass :id <registered-id>."}
    :else
    (let [meta (rf/handler-meta kind id)
          coord (:source-coord meta)]
      (if (nil? coord)
        {:ok?   false
         :reason :no-source-coord
         :kind  kind
         :id    id}
        {:ok?          true
         :kind         kind
         :id           id
         :source-coord (egress-value coord
                                     {:include-sensitive? include-sensitive?
                                      :include-large?     include-large?
                                      :frame              (resolve-frame frame)})}))))

;; ---------------------------------------------------------------------------
;; Mutation band (3 accessors)
;; ---------------------------------------------------------------------------

(defn dispatch!
  "Tool: `dispatch`. Fire `event-vec` through the framework dispatch
  OPTS map as `{:origin *current-origin*}` (defaults to `:xray-mcp`), so
  the emitted `:rf.event/dispatched` trace carries `[:tags
  :rf.event/origin]` and `get-trace-buffer {:origin <origin>}` can
  isolate the tool-dispatched cascade. Returns `{:ok? true :event-id <kw>
  :frame <id> :origin <kw> :mode <kw>}`.

  Modes: `:queued` (default — non-blocking `rf/dispatch`); `:sync`
  (the synchronous variant). The MCP server picks the mode at the
  tool-arg layer. Frame resolution mirrors the read-side accessors;
  multi-frame apps must pass `:frame`."
  ([event-vec] (dispatch! event-vec nil))
  ([event-vec {:keys [frame sync?] :as _opts}]
   (let [fid    (resolve-frame frame)
         origin *current-origin*
         fail   (frame-failure frame fid)]
     (cond
       (not (vector? event-vec))
       {:ok? false :reason :not-an-event-vector
        :hint "event must be a vector, e.g. [:cart/checkout]"}

       fail fail

       :else
       ;; Pass the bound origin through the framework's dispatch OPTS map
       ;; (`{:origin origin}`) — NOT as event-vector metadata. The router's
       ;; `build-envelope` reads `:origin` off the opts map (defaulting to
       ;; `:app`) and `emit-dispatched-trace` stamps it onto the
       ;; `:rf.event/dispatched` trace as `[:tags :rf.event/origin]` (Spec
       ;; 002 §Frame target resolution / Spec 009 §Origin). A `{:tags
       ;; {:origin …}}` event-meta path was NEVER read by the framework, so
       ;; tool-dispatched cascades were indistinguishable from app-origin
       ;; ones on the trace bus.
       (do
         (rf/with-frame fid
           (if sync?
             (rf/dispatch-sync event-vec {:origin origin})
             (rf/dispatch event-vec {:origin origin})))
         {:ok?      true
          :event-id (first event-vec)
          :frame    fid
          :origin   origin
          :mode     (if sync? :sync :queued)})))))

(defn restore-epoch!
  "Tool: `restore-epoch`. Rewind a frame to the named epoch's canonical
  `:frame-state-after` via `rf/restore-epoch!` — the WHOLE frame-state,
  reinstalling app-db AND runtime-db (machine snapshots, the route
  slice, elision declarations, SSR metadata) as two partitions in ONE
  atomic write (epoch · `restore-epoch!`). `:frame-state-after` is the
  only restore source; the retained `:db-after` is an OPTIONAL app-db
  projection used for diffs, NOT the restore source. The framework's
  wrapper returns `true` on success and `false` on any of the six
  documented failure modes (per Tool-Pair §Time-travel — Restore — each
  emits a structured `:rf.epoch/*` error trace and leaves the
  frame-state unchanged); this accessor projects that boolean onto the
  wire-shape the MCP catalogue ships:

      {:ok? true  :frame <id> :epoch-id <uuid> :origin <kw>}
      {:ok? false :frame <id> :epoch-id <uuid> :origin <kw>
       :reason :rf.epoch/restore-failed
       :hint  \"See the trace bus for the :rf.epoch/* failure-row keyword.\"}

  The six failure rows surface on the trace bus where Xray-MCP's
  `subscribe :trace` (or the next `get-trace-buffer` call) reads them;
  the per-row keyword is intentionally NOT projected onto the
  accessor's return shape because the framework returns a plain
  boolean — the structured row already lives on the bus and
  double-projecting it would let the two drift."
  [{:keys [frame epoch-id] :as _opts}]
  (let [fid  (resolve-frame frame)
        fail (frame-failure frame fid)]
    (cond
      fail fail

      (nil? epoch-id)
      {:ok? false :reason :missing-epoch-id
       :hint "Pass :epoch-id <uuid>."}

      :else
      (let [ok? (rf/restore-epoch! fid epoch-id)]
        (cond-> {:ok?      (boolean ok?)
                 :frame    fid
                 :epoch-id epoch-id
                 :origin   *current-origin*}
          (not ok?) (assoc :reason :rf.epoch/restore-failed
                           :hint   (str "Restore failed — read the trace bus "
                                        "for the structured :rf.epoch/* row.")))))))

(defn replace-app-db!
  "Tool: `replace-app-db`. Inject `:value` into a frame's `app-db`,
  bypassing the cascade. Schema-validates against current schemas via
  `rf/replace-app-db!` (renamed from `rf/reset-frame-db!`, EP-0001
  rf2-tfepxu); the framework's wrapper returns `true` on success and
  `false` on any of the three documented failure rows
  (`:rf.error/no-such-handler`, `:rf.epoch/replace-during-drain`,
  `:rf.epoch/replace-schema-mismatch` — each emits a structured
  trace and leaves `app-db` unchanged).

  Returns `{:ok? true :frame <id> :origin <kw>}` on success;
  `{:ok? false :frame <id> :reason :rf.epoch/reset-failed ...}` on
  failure (same projection rationale as `restore-epoch!`)."
  [{:keys [frame value] :as _opts}]
  (let [fid  (resolve-frame frame)
        fail (frame-failure frame fid)]
    (cond
      fail fail

      (nil? value)
      {:ok? false :reason :missing-value
       :hint "Pass :value <edn-map> to inject."}

      :else
      (let [ok? (rf/replace-app-db! fid value)]
        (cond-> {:ok?    (boolean ok?)
                 :frame  fid
                 :origin *current-origin*}
          (not ok?) (assoc :reason :rf.epoch/reset-failed
                           :hint   (str "Reset failed — read the trace bus "
                                        "for the structured :rf.epoch/* row.")))))))

;; ---------------------------------------------------------------------------
;; Streaming band (3 accessors)
;; ---------------------------------------------------------------------------
;;
;; The MCP-server side owns the per-subscription queue + per-tick
;; overflow bookkeeping (one drain-batch per `notifications/progress`).
;; The runtime exposes the lightweight registration / lookup surface
;; the server's pump rides over.

(defonce ^:private subscriptions
  ;; Per-subscription metadata only — the *queue* lives on the server
  ;; side; this atom carries `{:id :topic :filter :created-at}` slots so
  ;; `list-subscriptions` can enumerate without an extra round-trip.
  (atom {}))

(defn subscribe!
  "Tool: `subscribe`. Open a streaming subscription for `:topic` with
  `:filter`. Returns `{:ok? true :sub-id <uuid> :topic <kw>
  :filter <map>}`.

  The runtime records the subscription's metadata; the MCP server
  owns the per-tick drain pump and the queue overflow bookkeeping.
  Recognised topics: `:trace`, `:epoch`, `:fx`, `:error`."
  [{:keys [topic filter] :as _opts}]
  (cond
    (not (contains? #{:trace :epoch :fx :error} topic))
    {:ok? false :reason :unknown-topic
     :hint  "Recognised topics: :trace :epoch :fx :error"
     :given topic}

    :else
    (let [sub-id (str (random-uuid))
          sub    {:id         sub-id
                  :topic      topic
                  :filter     (or filter {})
                  :origin     *current-origin*
                  :created-at (.now js/Date)}]
      (swap! subscriptions assoc sub-id sub)
      {:ok?    true
       :sub-id sub-id
       :topic  topic
       :filter (:filter sub)})))

(defn unsubscribe!
  "Tool: `unsubscribe`. Drop subscription `sub-id`. Returns
  `{:ok? true :sub-id <id> :existed? <bool>}` — idempotent close per
  the catalogue entry."
  [{:keys [sub-id] :as _opts}]
  (let [existed? (contains? @subscriptions sub-id)]
    (swap! subscriptions dissoc sub-id)
    {:ok? true :sub-id sub-id :existed? existed?}))

(defn list-subscriptions
  "Tool: `list-subscriptions`. Diagnostic enumerating active runtime-side
  subscription metadata. Returns `{:ok? true :subs <vec>}`.

  The per-tick `:queue-depth` / `:queue-bytes` / `:dropped-events`
  fields the spec lists live on the *server* side (each sub's pump
  carries the per-tick counters); the runtime supplies the topic /
  filter / created-at slots the server merges into its own view."
  ([] (list-subscriptions nil))
  ([{:keys [topic sub-id] :as _opts}]
   (let [subs (vals @subscriptions)
         filtered (cond->> subs
                    (some? topic)  (filter #(= topic  (:topic  %)))
                    (some? sub-id) (filter #(= sub-id (:id     %))))]
     {:ok?   true
      :subs  (mapv (fn [s]
                     {:id         (:id s)
                      :topic      (:topic s)
                      :filter     (:filter s)
                      :origin     (:origin s)
                      :created-at (:created-at s)})
                   filtered)
      :count (count filtered)})))

;; ---------------------------------------------------------------------------
;; Escape hatch (1 accessor)
;; ---------------------------------------------------------------------------

(defn eval-form-result
  "Tool: `eval-cljs` (runtime-side companion). The MCP server renders
  the user's CLJS form inside a `(binding [*current-origin* :xray-mcp]
  ...)` wrapper, then `cljs-eval`'s the wrapped form directly — the
  form is NOT routed through this fn (which would force the eval form
  to be a string + a `read-string` here, defeating the purpose of the
  escape hatch).

  This fn is the runtime-side **result shaper** the server's wrapper
  invokes on the eval'd value before egress:
  `{:ok? true :value (egress-value value)}` — privacy + size scrubbing
  applied to the eval'd result with caller's `:include-sensitive?` /
  `:include-large?` opt-in.

  EP-0015 frame-owned egress (rf2-5b2ct2): the eval'd value projects under
  a KNOWN frame — the caller's explicit `:frame` opt wins, else the sole
  registered frame (`resolve-frame`). Without a resolvable frame the walker
  fails closed (`:rf/redacted`) unless `:include-sensitive? true` waives it,
  rather than leaning on the eval-time ambient scope.

  The synchronous-extent binding of `*current-origin*` per Lock #4 / I6
  is the server's responsibility (the wrapper sits *around* the user's
  form); the runtime's role is the egress-side scrub."
  ([value] (eval-form-result value nil))
  ([value opts]
   {:ok?   true
    :value (egress-value value (assoc opts :frame (resolve-frame (:frame opts))))}))

;; ---------------------------------------------------------------------------
;; Meta band (2 accessors)
;; ---------------------------------------------------------------------------

(defn health
  "Tool: `discover-app` (runtime-side companion). One-call summary of
  the runtime's view of the world. Used by the MCP server to confirm
  the environment is healthy on every session's first tool call.

  Returns `{:ok? true :session-id <uuid> :debug-enabled? <bool>
  :frames <vec> :ambiguous-frame? <bool>
  :coord-annotation-enabled? <bool>}`.

  Side-effect-free — unlike re-frame2-pair's `health` we install no listeners
  here (Xray-the-panel's preload owns the trace + epoch listeners
  per `preload.cljs`)."
  []
  (let [fids (frames-list)]
    {:ok?                       true
     :session-id                session-id
     :debug-enabled?            (boolean interop/debug-enabled?)
     :frames                    fids
     :ambiguous-frame?          (> (count fids) 1)
     ;; Coord-annotation probe: the framework exposes
     ;; `data-rf2-source-coord` on DOM elements when the user has
     ;; called `(rf/configure! {:source-coords {:annotate-dom? true}})`.
     ;; The cheapest cross-substrate check is "is at least one
     ;; element annotated?" — a single DOM query, no framework
     ;; introspection ceremony. Browser-only; nil-safe under node-test.
     :coord-annotation-enabled? (boolean
                                  (when (exists? js/document)
                                    (some? (.querySelector
                                             js/document
                                             "[data-rf2-source-coord]"))))
     :origin                    *current-origin*}))

(defonce ^:private probe-counter
  ;; Per-preload monotonic counter exposed via `tail-build-probe`.
  ;; Reset to zero on every fresh ns load — shadow-cljs `:after-load`
  ;; does NOT re-evaluate `defonce` so the counter survives hot reloads;
  ;; only a full page refresh (which also wipes `session-id` + the
  ;; `js/globalThis` sentinel) starts the count over. The change-detect
  ;; logic lives MCP-server-side per the `tail-build` tool spec.
  (atom 0))

(defn tail-build-probe
  "Tool: `tail-build` (runtime-side companion). Returns a fresh marker
  every call — the MCP server polls this until its value changes
  (proving a hot-reload landed and the runtime re-evaluated). The
  monotonic counter survives `:after-load` (defonce) and resets only
  on full page refresh — same lifetime as `session-id`.

  The actual change-detect lives on the server side; the runtime's
  job is to expose a value that's stable across calls but different
  after a real hot-reload. Returns
  `{:ok? true :probe <int> :session-id <uuid> :build-tick <int>}`."
  []
  (swap! probe-counter inc)
  {:ok?        true
   :probe      @probe-counter
   :session-id session-id
   :build-tick @probe-counter})

;; ---------------------------------------------------------------------------
;; Test support — reset for fixture isolation
;; ---------------------------------------------------------------------------

(defn reset-for-test!
  "Reset the runtime's per-process state so test fixtures can drive
  multiple sessions. Test-only — never call from production code. Does
  not touch `session-id` (which is a per-preload constant by design)
  or the global sentinel."
  []
  (reset! subscriptions {})
  (reset! probe-counter 0)
  nil)
