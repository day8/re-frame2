(ns day8.re-frame2-causa.runtime
  "Injected-runtime namespace exposing Causa's read/mutate accessors
  to AI agents (rf2-8xzoe.4 / F-4, rf2-crhr8).

  Lives on the browser side of the stdio JSON-RPC pipe. Rides
  Causa-the-panel's `:devtools/preloads` (see `preload.cljs`'s require
  list) so a consumer app that already loads Causa-the-panel
  automatically carries the runtime — no separate preload entry.

  Per rf2-hvl1g (closure 2026-05-19) there is no dedicated `causa-mcp`
  jar; AI agents reach this runtime via `tools/re-frame2-pair-mcp/`
  (which can read this ns via `eval-cljs`). The accessors below are
  the framework-published Causa runtime API.

  ## What this namespace is

  Tool-shaped accessors (see [`API.md` §Causa runtime API](../../spec/API.md))
  rendered as EDN forms addressed at
  `day8.re-frame2-causa.runtime/<accessor>`; an MCP server (today
  `tools/re-frame2-pair-mcp/`) renders the form against the nREPL
  socket, shadow-cljs evaluates each form in the browser tab, and the
  return value comes back over the bencode-framed channel.

  Plus three load-bearing supports:

  - `session-id` — random UUID per preload load. The MCP-server-side
    preload probe reads either this CLJS var or its
    `js/globalThis.__day8_re_frame2_causa_runtime` mirror to confirm
    the runtime landed. A full page refresh wipes both — the next
    `discover-app` tool call reports `:reason :runtime-not-preloaded`
    with a setup hint.
  - `current-origin` — `^:dynamic` var holding the `:tags :origin`
    value the runtime stamps onto every mutation it performs. The
    default `:causa-mcp` is grandfathered from the original
    causa-mcp design; revising the default to a more accurate tag
    (e.g. `:causa-runtime`) is tracked separately as a follow-on.
    The MCP server is expected to rebind it for the synchronous
    extent of an eval'd form to its own `:origin` identifier.
  - `health` — one-call summary used by `discover-app`. Side-effect-free
    here; the runtime registers no listeners on its own.

  ## What this namespace is NOT

  - Not a new framework registry. Every accessor below routes through
    an existing `re-frame.core/*` surface. We add no new dispatch
    types, no new effect substrates, no new component substrates.
  - Not a re-frame2-pair-mcp port. The accessor surface is shaped to
    the Causa-specific surfaces (trace buffer, epoch history,
    app-db-diff, machine-state) rather than to re-frame2-pair-mcp's
    own tool shapes.
  - Not a streaming substrate. The runtime exposes
    `register-trace-listener!` / `register-epoch-listener!` indirection via
    re-frame.core, plus a thin `current-subscriptions` accessor for
    the diagnostic; per-tick queue / overflow bookkeeping lives on
    the MCP-server side.

  ## Why the install side-effect block is gated on `debug-enabled?`

  Per Causa-the-panel's preload, the framework's trace surface elides
  in production builds (`re-frame.interop/debug-enabled?` false). The
  runtime's sentinel installation is gated the same way so a stray
  production load (which is a configuration mistake but should fail
  gracefully) is a no-op rather than a `js/globalThis` pollution.

  ## Cross-side coupling is one-way

  The MCP server depends on the accessor signatures below (the
  contract); the runtime is independent of any server. Causa-the-panel
  loads this ns without an MCP server running, and any MCP consumer
  (re-frame2-pair-mcp today) can attach later without the runtime
  needing to know."
  (:require [re-frame.core :as rf]
            [re-frame.frame :as frame]
            ;; rf2-qwm0a: trace-buffer (and the rest of the listener +
            ;; ring-buffer surface) lives in re-frame.trace.tooling, not
            ;; re-frame.trace. CLJS deliberately omits `rf/<name>` aliases
            ;; for these so production counter bundles DCE the tooling
            ;; sibling wholesale; Causa's runtime is dev-only (rides the
            ;; panel's :devtools/preloads), so requiring the tooling ns
            ;; directly here is bundle-isolation-safe.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.interop :as interop]))

;; ---------------------------------------------------------------------------
;; Session sentinel
;; ---------------------------------------------------------------------------
;;
;; A random UUID set once per preload load. Mirrored on `js/globalThis`
;; under `__day8_re_frame2_causa_runtime` (a JS object carrying
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
  "__day8_re_frame2_causa_runtime")

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
;; Convention: every MCP-driven side-effect on the trace bus carries a
;; `:tags :origin <server-name>` tag. The MCP server renders a
;; `binding` form that wraps the runtime's accessor call with
;; `(binding [current-origin <server-name>] ...)`; mutating accessors
;; read the bound value when they construct the dispatch payload.
;;
;; The default value is `:causa-mcp` (grandfathered from the original
;; causa-mcp design — see DESIGN-RATIONALE.md Lock #6 supersedence;
;; revising the default is tracked separately) so a bare call from the
;; server
;; already carries the tag; `eval-cljs` keeps the binding for the
;; synchronous extent of the eval'd form only (the documented
;; async-tagging gap per Lock #4 / I6).

(def ^:dynamic *current-origin*
  "The `:tags :origin` value stamped on every mutation the runtime
  performs on behalf of the MCP server. Defaults to `:causa-mcp`; the
  server's `eval-cljs` tool re-binds it for the synchronous extent of
  the user-supplied form."
  :causa-mcp)

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
  without an explicit pick — callers tag the result accordingly."
  [explicit]
  (cond
    (some? explicit) explicit
    :else            (let [fids (rf/frame-ids)]
                       (when (= 1 (count fids))
                         (first fids)))))

(defn- frames-list
  "All registered frame ids — used by `health` and indirectly by tools
  that need to enumerate frames for per-frame slice walks."
  []
  (vec (rf/frame-ids)))

;; ---------------------------------------------------------------------------
;; Privacy egress — single emission site
;; ---------------------------------------------------------------------------
;;
;; Per MUST-inventory rows #15 / #17 / #19: every direct-read accessor
;; routes returned values through `re-frame.core/elide-wire-value`
;; before egress. The single normative emission site lives in the
;; framework; the runtime's job is to call it with both
;; `:include-sensitive?` and `:include-large?` defaulting `false` and
;; to honour the caller's opt-in. The opt-in lives in the MCP-server
;; side's tool args; the runtime accepts the bools as plain args so
;; the eval form is one shape per call.

(defn- elide
  "Route `value` through the framework's wire-elision walker with both
  privacy + size defaults. Caller passes `:include-sensitive?` /
  `:include-large?` to opt back in per call (the runtime API uses
  plain-keyword opts; this wrapper translates to the framework's
  `:rf.size/*` namespaced opt keys per
  `re-frame.elision/elide-wire-value`). Tools wrap their ready-to-
  egress payload with this fn — single emission site per MUST-inventory
  row #17."
  ([value]
   (elide value nil))
  ([value {:keys [include-sensitive? include-large?]
           :or   {include-sensitive? false
                  include-large?     false}}]
   (rf/elide-wire-value value
                        {:rf.size/include-sensitive? include-sensitive?
                         :rf.size/include-large?     include-large?})))

;; ---------------------------------------------------------------------------
;; Inspection band (9 accessors)
;; ---------------------------------------------------------------------------

(defn get-trace-buffer
  "Tool: `get-trace-buffer`. Return a slice of the trace stream by
  filter; forwards to `(trace-tooling/trace-buffer opts)`. Filter keys are the
  canonical Spec 009 filter vocabulary (`:operation`, `:op-type`,
  `:since`, `:frame`, `:severity`, `:event-id`, `:handler-id`,
  `:source`, `:origin`, `:dispatch-id`, `:since-ms`, `:between`,
  `:pred`).

  Returns `{:ok? true :events <vec> :count <n>}`. Each event is routed
  through `elide-wire-value` so sensitive / large values are scrubbed
  at the wire boundary (MUST-inventory rows #2 / #15 / #19)."
  ([] (get-trace-buffer {}))
  ([opts]
   (let [{:keys [include-sensitive? include-large?]} opts
         filter-opts (dissoc opts :include-sensitive? :include-large?)
         events      (trace-tooling/trace-buffer filter-opts)
         scrubbed    (mapv #(elide % {:include-sensitive? include-sensitive?
                                      :include-large?     include-large?})
                           events)]
     {:ok?    true
      :events scrubbed
      :count  (count scrubbed)})))

(defn get-epoch-history
  "Tool: `get-epoch-history`. Per-frame epoch history (vector of
  `:rf/epoch-record`) per Tool-Pair §Time-travel. Returns
  `{:ok? true :frame <id> :epochs <vec>}` or `{:ok? false :reason
  :no-frame-resolved}` when the frame can't be picked. Each record
  routes through `elide-wire-value` for privacy + size egress."
  ([] (get-epoch-history nil))
  ([opts]
   (let [{:keys [frame include-sensitive? include-large?]} opts
         fid (resolve-frame frame)]
     (if (nil? fid)
       {:ok? false :reason :no-frame-resolved
        :hint "Pass :frame :foo or register at least one frame."}
       (let [records  (rf/epoch-history fid)
             scrubbed (mapv #(elide % {:include-sensitive? include-sensitive?
                                       :include-large?     include-large?})
                            records)]
         {:ok?    true
          :frame  fid
          :epochs scrubbed
          :count  (count scrubbed)})))))

(defn get-app-db
  "Tool: `get-app-db`. Current `app-db` value at a frame, optionally
  scoped by `:path`. Routes through `elide-wire-value` (MUST-inventory
  row #19 — direct-read privacy posture). Returns
  `{:ok? true :frame <id> :path <vec> :value <edn>}` or
  `{:ok? false :reason :no-frame-resolved}`."
  ([] (get-app-db nil))
  ([opts]
   (let [{:keys [frame path include-sensitive? include-large?]} opts
         fid (resolve-frame frame)]
     (if (nil? fid)
       {:ok? false :reason :no-frame-resolved
        :hint "Pass :frame :foo or register at least one frame."}
       (let [db    (rf/get-frame-db fid)
             value (if (seq path) (get-in db path) db)]
         {:ok?   true
          :frame fid
          :path  (vec path)
          :value (elide value {:include-sensitive? include-sensitive?
                               :include-large?     include-large?})})))))

(defn get-app-db-diff
  "Tool: `get-app-db-diff`. Slice diff for a named epoch — read
  `:db-before` + `:db-after` off the epoch record and project as
  `{:added [...] :removed [...] :changed [...]}` (the changed-paths
  shape per MUST-inventory row #13; the heavier nested diff lives in
  the MCP server's wire-pipeline layer).

  Returns `{:ok? true :frame <id> :epoch-id <uuid> :diff <map>}` or
  `{:ok? false :reason :no-such-epoch ...}` / `:no-frame-resolved`."
  [{:keys [frame epoch-id include-sensitive? include-large?] :as _opts}]
  (let [fid (resolve-frame frame)]
    (cond
      (nil? fid)
      {:ok? false :reason :no-frame-resolved
       :hint "Pass :frame :foo or register at least one frame."}

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
                diff   {:before (elide before {:include-sensitive? include-sensitive?
                                               :include-large?     include-large?})
                        :after  (elide after  {:include-sensitive? include-sensitive?
                                               :include-large?     include-large?})}]
            {:ok?      true
             :frame    fid
             :epoch-id epoch-id
             :diff     diff}))))))

(defn get-machine-state
  "Tool: `get-machine-state`. Current snapshot for a named machine —
  routes through `rf/machine-meta` to read the registered spec. Returns
  `{:ok? true :frame <id> :machine-id <kw> :state <edn>}` or
  `{:ok? false :reason :no-such-machine ...}` / `:no-frame-resolved`.

  Note: the `:state` slot carries the registered machine spec
  (transitions, initial-state, tags); per-frame runtime state (the
  current FSM position) is a separate framework surface that lands
  alongside the Machine Inspector panel — when that surface stabilises
  the accessor extends; today the spec snapshot is the load-bearing
  read."
  [{:keys [frame machine-id include-sensitive? include-large?] :as _opts}]
  (let [fid (resolve-frame frame)]
    (cond
      (nil? fid)
      {:ok? false :reason :no-frame-resolved
       :hint "Pass :frame :foo or register at least one frame."}

      (nil? machine-id)
      {:ok? false :reason :missing-machine-id
       :hint "Pass :machine-id <keyword>."}

      :else
      (let [m (rf/machine-meta machine-id)]
        (if (nil? m)
          {:ok? false :reason :no-such-machine
           :frame fid :machine-id machine-id
           :registered (vec (rf/machines))}
          {:ok?        true
           :frame      fid
           :machine-id machine-id
           :state      (elide m {:include-sensitive? include-sensitive?
                                 :include-large?     include-large?})})))))

(defn get-machine-list
  "Tool: `get-machine-list`. List of registered machines per frame
  with current spec. Returns `{:ok? true :machines <map>}` where the
  map is keyed by machine-id."
  ([] (get-machine-list nil))
  ([{:keys [include-sensitive? include-large?] :as _opts}]
   (let [ids (rf/machines)]
     {:ok?      true
      :machines (into {}
                      (map (fn [mid]
                             [mid (elide (rf/machine-meta mid)
                                         {:include-sensitive? include-sensitive?
                                          :include-large?     include-large?})]))
                      ids)
      :count    (count ids)})))

(defn get-issues
  "Tool: `get-issues`. Recent errors / warnings / schema violations /
  hydration mismatches — projection over the trace buffer filtered to
  the issue-tier `:op-type`s (`:error`, `:warning`,
  `:rf.schema/violation`, `:rf.hydration/mismatch`).

  Returns `{:ok? true :issues <vec>}`. Routes through
  `elide-wire-value` per MUST-inventory row #2."
  ([] (get-issues {}))
  ([opts]
   (let [{:keys [include-sensitive? include-large?]} opts
         issue-op-types #{:error :warning
                          :rf.schema/violation
                          :rf.hydration/mismatch}
         events  (trace-tooling/trace-buffer {})
         issues  (filterv #(contains? issue-op-types (:op-type %)) events)
         scrubbed (mapv #(elide % {:include-sensitive? include-sensitive?
                                   :include-large?     include-large?})
                        issues)]
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

  Returns `{:ok? true :handlers <vec> :count <n>}`. Optional `:kind`
  arg narrows to a single registrar kind (`:event`, `:sub`, `:fx`,
  `:cofx`, `:machine`, `:flow`, `:frame`, `:view`, `:reg-machine`)."
  ([] (get-handlers {}))
  ([{:keys [kind] :as _opts}]
   (let [kinds   (if (some? kind) [kind] registrar-kinds)
         walked  (for [k kinds
                       [id meta] (rf/registrations k)]
                   {:kind k
                    :id   id
                    :meta meta})]
     {:ok?      true
      :handlers (vec walked)
      :count    (count walked)})))

(defn get-source-coord
  "Tool: `get-source-coord`. Source coord for a given id (handler,
  view, machine state, sub) — projects the `:source-coord` slot off
  the handler's metadata.

  Returns `{:ok? true :kind <kw> :id <any> :source-coord <map>}` or
  `{:ok? false :reason :no-source-coord ...}`."
  [{:keys [kind id] :as _opts}]
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
         :source-coord coord}))))

;; ---------------------------------------------------------------------------
;; Mutation band (3 accessors)
;; ---------------------------------------------------------------------------

(defn dispatch!
  "Tool: `dispatch`. Fire `event-vec` tagged `:origin *current-origin*`
  (defaults to `:causa-mcp`). Returns `{:ok? true :event-id <kw>
  :origin <kw> :mode <kw>}`.

  Modes: `:queued` (default — non-blocking `rf/dispatch`); `:sync`
  (the synchronous variant). The MCP server picks the mode at the
  tool-arg layer. Frame resolution mirrors the read-side accessors;
  multi-frame apps must pass `:frame`."
  ([event-vec] (dispatch! event-vec nil))
  ([event-vec {:keys [frame sync?] :as _opts}]
   (let [fid    (resolve-frame frame)
         origin *current-origin*]
     (cond
       (not (vector? event-vec))
       {:ok? false :reason :not-an-event-vector
        :hint "event must be a vector, e.g. [:cart/checkout]"}

       (nil? fid)
       {:ok? false :reason :no-frame-resolved
        :hint "Pass :frame :foo or register at least one frame."}

       :else
       (let [tagged (with-meta event-vec {:tags {:origin origin}})]
         (rf/with-frame fid
           (if sync?
             (rf/dispatch-sync tagged)
             (rf/dispatch tagged)))
         {:ok?      true
          :event-id (first event-vec)
          :frame    fid
          :origin   origin
          :mode     (if sync? :sync :queued)})))))

(defn restore-epoch!
  "Tool: `restore-epoch`. Rewind a frame's `app-db` to the named
  epoch's `:db-after` via `rf/restore-epoch`. The framework's wrapper
  returns `true` on success and `false` on any of the six documented
  failure modes (per Tool-Pair §Time-travel — Restore — each emits a
  structured `:rf.epoch/*` error trace and leaves `app-db` unchanged);
  this accessor projects that boolean onto the wire-shape the MCP
  catalogue ships:

      {:ok? true  :frame <id> :epoch-id <uuid> :origin <kw>}
      {:ok? false :frame <id> :epoch-id <uuid> :origin <kw>
       :reason :rf.epoch/restore-failed
       :hint  \"See the trace bus for the :rf.epoch/* failure-row keyword.\"}

  The six failure rows surface on the trace bus where Causa-MCP's
  `subscribe :trace` (or the next `get-trace-buffer` call) reads them;
  the per-row keyword is intentionally NOT projected onto the
  accessor's return shape because the framework returns a plain
  boolean — the structured row already lives on the bus and
  double-projecting it would let the two drift."
  [{:keys [frame epoch-id] :as _opts}]
  (let [fid (resolve-frame frame)]
    (cond
      (nil? fid)
      {:ok? false :reason :no-frame-resolved
       :hint "Pass :frame :foo or register at least one frame."}

      (nil? epoch-id)
      {:ok? false :reason :missing-epoch-id
       :hint "Pass :epoch-id <uuid>."}

      :else
      (let [ok? (rf/restore-epoch fid epoch-id)]
        (cond-> {:ok?      (boolean ok?)
                 :frame    fid
                 :epoch-id epoch-id
                 :origin   *current-origin*}
          (not ok?) (assoc :reason :rf.epoch/restore-failed
                           :hint   (str "Restore failed — read the trace bus "
                                        "for the structured :rf.epoch/* row.")))))))

(defn reset-frame-db!
  "Tool: `reset-frame-db`. Inject `:value` into a frame's `app-db`,
  bypassing the cascade. Schema-validates against current schemas via
  `rf/reset-frame-db!`; the framework's wrapper returns `true` on
  success and `false` on any of the three documented failure rows
  (`:rf.error/no-such-handler`, `:rf.epoch/reset-frame-db-during-drain`,
  `:rf.epoch/reset-frame-db-schema-mismatch` — each emits a structured
  trace and leaves `app-db` unchanged).

  Returns `{:ok? true :frame <id> :origin <kw>}` on success;
  `{:ok? false :frame <id> :reason :rf.epoch/reset-failed ...}` on
  failure (same projection rationale as `restore-epoch!`)."
  [{:keys [frame value] :as _opts}]
  (let [fid (resolve-frame frame)]
    (cond
      (nil? fid)
      {:ok? false :reason :no-frame-resolved
       :hint "Pass :frame :foo or register at least one frame."}

      (nil? value)
      {:ok? false :reason :missing-value
       :hint "Pass :value <edn-map> to inject."}

      :else
      (let [ok? (rf/reset-frame-db! fid value)]
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
  the user's CLJS form inside a `(binding [current-origin :causa-mcp]
  ...)` wrapper, then `cljs-eval`'s the wrapped form directly — the
  form is NOT routed through this fn (which would force the eval form
  to be a string + a `read-string` here, defeating the purpose of the
  escape hatch).

  This fn is the runtime-side **result shaper** the server's wrapper
  invokes on the eval'd value before egress:
  `{:ok? true :value (elide value)}` — privacy + size scrubbing applied
  to the eval'd result with caller's `:include-sensitive?` /
  `:include-large?` opt-in.

  The synchronous-extent binding of `*current-origin*` per Lock #4 / I6
  is the server's responsibility (the wrapper sits *around* the user's
  form); the runtime's role is the egress-side scrub."
  ([value] (eval-form-result value nil))
  ([value opts]
   {:ok?   true
    :value (elide value opts)}))

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
  here (Causa-the-panel's preload owns the trace + epoch listeners
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
     ;; called `(rf/configure :source-coords {:annotate-dom? true})`.
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
