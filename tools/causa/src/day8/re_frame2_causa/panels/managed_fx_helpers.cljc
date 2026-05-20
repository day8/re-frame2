(ns day8.re-frame2-causa.panels.managed-fx-helpers
  "Pure-data helpers for the managed-fx wire-boundary diff panel
  (rf2-uyp86, parent rf2-5aw5v — flagship cross-cutting feature from
  `tools/causa/spec/019-Cross-Cutting-Insight.md` §2.4).

  ## What the panel needs

  One uniform record per managed-fx invocation in a focused cascade,
  satisfying [`spec/Managed-Effects.md`'s eight-property
  contract](../../../../spec/Managed-Effects.md). Across five surfaces
  (HTTP / WebSocket / machine `:spawn` / SSR `:rf.server/*` /
  `:rf.flow/*`) the record fields are the same; only the projection
  from the surface's raw trace events differs.

  ## The record shape

      {:surface         :http | :websocket | :machine-invoke
                                | :ssr-fx | :flow
       :fx-id           <id>                ;; the registered fx id
       :req             <request-payload>   ;; method, url, headers,
                                            ;; body (or surface-specific
                                            ;; equivalent)
       :wire            <wire-timing-map>   ;; nil when the surface
                                            ;; doesn't emit timing
       :res             <response-payload>  ;; nil when in-flight or
                                            ;; the surface has no
                                            ;; reply-shape
       :handler         <handler-event-vec> ;; the dispatched response
                                            ;; handler (e.g.
                                            ;; [:user/profile-loaded …])
       :status          :ok | :error | :in-flight | :overridden
                                | :skipped | :stub
       :phase           :issued | :sent | :received | :completed
                                | :failed | :aborted
       :correlation-id  <id-or-nil>         ;; request-id, machine
                                            ;; spawn id, …
       :cancel-cause    <kw-or-nil>         ;; :user / :actor-destroyed
                                            ;; / :superseded / …
       :http-status     <int-or-nil>        ;; HTTP only
       :duration-ms     <num-or-nil>        ;; elapsed ms
       :failure         <failure-map-or-nil>;; `:rf.<surface>/*` kind +
                                            ;; tags
       :paths-touched   [<path> ...]        ;; app-db slice paths the
                                            ;; handler caused to change
       :origin-event-id <int-or-nil>}       ;; trace-event :id of the
                                            ;; `:rf.fx/handled` emit —
                                            ;; the cross-link anchor

  Pure data → data. JVM-runnable so the test suite can drive the
  projection without booting a CLJS runtime.

  ## Surface adapters

  Each adapter is a fn `(adapter fx-event surface-events handler-event)`
  taking three slices off the cascade:

    - `fx-event`        — the `:rf.fx/handled` event for this fx
                          invocation (carries `:fx-id`, `:fx-args`,
                          `:dispatch-id`, `:frame` on `:tags`).
    - `surface-events`  — the other trace events on the same cascade
                          for this surface (e.g.
                          `:rf.http/retry-attempt`,
                          `:rf.http/aborted-on-actor-destroy`,
                          `:rf.machine.lifecycle/spawned`,
                          `:rf.flow/computed`).
    - `handler-event`   — the dispatched response event vector (if
                          known via the fx-args `:on-success` /
                          `:on-failure` / `:on-done` slot or via the
                          cascade's child `:event` slot).

  Each adapter returns one record per managed-fx invocation in the
  cascade. The composite-sub layer walks the cascade once, partitions
  the effects by surface, and routes each to the right adapter.

  ## What's NOT addressed (yet)

  Wire-timing is only natively emitted by `:rf.http/managed` today —
  the other surfaces default `:wire` to nil and the panel renders a
  `n/a` placeholder. The retry-attempt timeline (F.3) lives under
  `:rf.http/retry-attempt` traces and is folded into the HTTP record's
  `:phase` / `:duration-ms` summary. Per-attempt drill-down is a
  follow-on bead."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa.panels.common-helpers :as common]
            [day8.re-frame2-causa.panels.app-db-diff-helpers :as diff-h]))

;; ---- surface taxonomy ---------------------------------------------------

(def surfaces
  "Canonical render-order — the same order the template list uses, so a
  cascade with HTTP + machine-invoke + flow records always renders in
  the same vertical order."
  [:http :websocket :machine-invoke :ssr-fx :flow])

(def surface->label
  "Render-label for the panel header, e.g. `MANAGED FX [HTTP]`."
  {:http           "HTTP"
   :websocket      "WS"
   :machine-invoke "INVOKE"
   :ssr-fx         "SSR"
   :flow           "FLOW"})

(def surface->glyph
  "Per spec/019 §3 the badge taxonomy (F-C1) maps the five surfaces to
  glyphs. Same alphabet as the L2 event-list row badges so the panel
  header reads continuously with the row glyph."
  {:http           "🌐"   ;; 🌐
   :websocket      "🔌"   ;; 🔌
   :machine-invoke "🤖"   ;; 🤖
   :ssr-fx         "📄"   ;; 📄
   :flow           "🌊"}) ;; 🌊

(def status->colour-token
  "Status → colour-token mapping. The view resolves the token to a hex
  via the panel's `tokens` map."
  {:ok         :green
   :error      :red
   :in-flight  :cyan
   :overridden :accent-violet
   :skipped    :text-tertiary
   :stub       :accent-violet})

(def status->glyph
  "Status → shape-glyph (colour-blind-safe parallel signal)."
  {:ok         "✓"   ;; ✓
   :error      "✗"   ;; ✗
   :in-flight  "⧖"   ;; ⧖
   :overridden "◑"   ;; ◑
   :skipped    "○"   ;; ○
   :stub       "◑"}) ;; ◑

;; ---- common readers -----------------------------------------------------

(def tag common/tag-of)

(defn- fx-id-of [ev]
  (tag ev :fx-id))

(defn- fx-args-of [ev]
  (tag ev :fx-args))

(defn- dispatch-id-of [ev]
  (tag ev :dispatch-id))

(defn- frame-id-of [ev]
  (tag ev :frame))

(defn- ms-of [ev]
  (or (:time ev) (tag ev :time)))

;; ---- surface classifier -------------------------------------------------

(defn classify-fx-id
  "Return the surface this fx-id belongs to, or nil for non-managed fxs.

  Surfaces are recognised by the `:rf.<surface>/*` reserved-namespace
  convention per [spec/Conventions.md §Reserved namespaces](../../../../spec/Conventions.md):

    - `:rf.http/*`     → `:http`        (Spec 014)
    - `:rf.ws/*`       → `:websocket`   (Pattern-WebSocket)
    - `:rf.machine/*`  → `:machine-invoke` (Spec 005 `:spawn`)
    - `:rf.server/*`   → `:ssr-fx`      (Spec 011)
    - `:rf.flow/*` / `:rf.fx/reg-flow` / `:rf.fx/clear-flow`
                       → `:flow`        (Spec 013)

  Pure fn. Idempotent. Returns nil for `:db`, `:dispatch`, `:fx`, and
  every user-registered fx — those don't satisfy the eight-property
  contract."
  [fx-id]
  (when (keyword? fx-id)
    (let [n (namespace fx-id)
          base-name (name fx-id)]
      (cond
        (or (= n "rf.http")
            (str/starts-with? (or n "") "rf.http."))
        :http

        (or (= n "rf.ws")
            (str/starts-with? (or n "") "rf.ws."))
        :websocket

        (or (= n "rf.machine")
            (str/starts-with? (or n "") "rf.machine."))
        :machine-invoke

        (or (= n "rf.server")
            (str/starts-with? (or n "") "rf.server."))
        :ssr-fx

        (or (= n "rf.flow")
            (str/starts-with? (or n "") "rf.flow."))
        :flow

        ;; `:rf.fx/reg-flow` + `:rf.fx/clear-flow` are flow lifecycle fxs
        ;; per spec/013 §`:rf.fx/reg-flow`.
        (and (= n "rf.fx")
             (contains? #{"reg-flow" "clear-flow"} base-name))
        :flow

        :else nil))))

(defn managed-fx-effect?
  "True when a `:rf.fx/handled` (or override / skipped) trace event names
  a managed-fx-surface fx-id. Pure predicate."
  [ev]
  (boolean (classify-fx-id (fx-id-of ev))))

;; ---- surface-event collectors ------------------------------------------

(def http-trace-operations
  "Trace operations the HTTP surface emits between issuance and
  terminal outcome. The panel groups them under the per-record
  surface-events bag so the WIRE TIMING / phase summary can fold
  them."
  #{:rf.http/retry-attempt
    :rf.http/aborted-on-actor-destroy
    :rf.http/aborted
    :rf.http/transport
    :rf.http/timeout
    :rf.http/decode-failure
    :rf.http/handled
    :rf.http/managed-issued
    :rf.warning/decode-defaulted})

(def websocket-trace-operations
  #{:rf.ws/connected
    :rf.ws/disconnected
    :rf.ws/reconnecting
    :rf.ws/stale-socket
    :rf.ws/transport
    :rf.ws/auth
    :rf.ws/sent
    :rf.ws/received})

(def machine-invoke-trace-operations
  #{:rf.machine/transition
    :rf.machine.transition/suppressed
    :rf.machine.lifecycle/spawned
    :rf.machine.lifecycle/destroyed
    :rf.machine/destroy
    :rf.machine/invoke-failed
    :rf.machine.timer/scheduled
    :rf.machine.timer/fired
    :rf.machine.timer/stale-after
    :rf.machine.timer/cancelled-on-resolution
    :rf.machine/snapshot-version-mismatch})

(def ssr-fx-trace-operations
  #{:rf.ssr/render-failed
    :rf.ssr/hydration-mismatch
    :rf.ssr/payload-too-large
    :rf.ssr/streaming-boundary
    :rf.ssr/streaming-boundary-failed})

(def flow-trace-operations
  #{:rf.flow/computed
    :rf.flow/failed
    :rf.flow/skip
    :rf.flow/registered
    :rf.flow/cleared
    :rf.error/flow-eval-exception})

(defn- surface-events-for
  "Filter `cascade-other-events` to the operations a given surface
  emits. The `:other` slot of the cascade record per
  `re-frame.trace.projection/group-cascades` is where these non-domino
  traces land. Pure fn."
  [cascade-other-events surface]
  (let [ops (case surface
              :http           http-trace-operations
              :websocket      websocket-trace-operations
              :machine-invoke machine-invoke-trace-operations
              :ssr-fx         ssr-fx-trace-operations
              :flow           flow-trace-operations
              #{})]
    (filterv #(contains? ops (:operation %)) (or cascade-other-events []))))

;; ---- status derivation --------------------------------------------------

(defn- fx-event->status
  "Project an fx event's `:operation` onto the panel's status taxonomy.
  The :stub status is a UI synthesis — a `:rf.fx/override-applied`
  event flags the row as stubbed in addition to its own outcome."
  [fx-ev]
  (case (:operation fx-ev)
    :rf.fx/handled                 :ok
    :rf.fx/override-applied        :overridden
    :rf.fx/skipped-on-platform     :skipped
    :rf.error/fx-handler-exception :error
    :rf.error/no-such-fx           :error
    :in-flight))

(defn- surface-events->failure
  "Walk the surface-events bag for a terminal failure trace. The first
  matching event's `:tags` are projected into the record's
  `:failure` map. Returns nil when no failure trace is present."
  [surface-events]
  (when-let [failed (some (fn [ev]
                            (when (or (= (:op-type ev) :error)
                                      (contains? #{:rf.http/transport
                                                   :rf.http/timeout
                                                   :rf.http/decode-failure
                                                   :rf.http/aborted
                                                   :rf.http/aborted-on-actor-destroy
                                                   :rf.ws/transport
                                                   :rf.ws/auth
                                                   :rf.ws/stale-socket
                                                   :rf.machine/invoke-failed
                                                   :rf.ssr/render-failed
                                                   :rf.flow/failed
                                                   :rf.error/flow-eval-exception}
                                                 (:operation ev)))
                              ev))
                          (or surface-events []))]
    {:kind (:operation failed)
     :tags (:tags failed)
     :message (or (get-in failed [:tags :message])
                  (get-in failed [:tags :reason]))}))

(defn- surface-events->cancel-cause
  "When the cascade includes an abort trace, surface its cause so the
  panel header can render `cancel: :actor-destroyed`. Returns nil when
  no cancellation is present."
  [surface-events]
  (some (fn [ev]
          (case (:operation ev)
            :rf.http/aborted-on-actor-destroy :actor-destroyed
            :rf.http/aborted                  (or (get-in ev [:tags :reason])
                                                  :user)
            :rf.machine/destroy               :actor-destroyed
            nil))
        (or surface-events [])))

(defn- surface-events->phase
  "Project the most-advanced phase observed in the surface-events bag.
  Phases are coarse; the precise wall-clock waterfall belongs in
  `:wire`. Phases progress in this order:
  `:issued → :sent → :received → :completed | :failed | :aborted`."
  [fx-ev surface-events status]
  (cond
    (some #(or (= (:operation %) :rf.http/aborted-on-actor-destroy)
               (= (:operation %) :rf.http/aborted))
          surface-events) :aborted
    (= status :error)     :failed
    (= status :ok)        :completed
    (= status :in-flight) :issued
    :else                 :completed))

;; ---- handler / response extraction -------------------------------------

(defn- args-handler-event
  "Pull the response handler event vector off the fx-args, if any. The
  managed-effect surfaces all carry the dispatched response under one
  of `:on-success`, `:on-failure`, `:on-done`, `:on-reply` — the
  caller's projection of 'what fires when this returns'. Pure fn."
  [args]
  (when (map? args)
    (or (:on-success args)
        (:on-done args)
        (:on-reply args)
        (:on-failure args))))

;; ---- correlation-id resolution -----------------------------------------

(defn- args-correlation-id
  "Surface-specific correlation id reader. HTTP carries `:request-id`;
  machine `:spawn` carries `:machine-id` or `:spawn-id`; SSR-fx
  events carry `:request-id` at the server-side accumulator. The
  caller's args map names it; we resolve defensively."
  [surface args]
  (when (map? args)
    (case surface
      :http           (:request-id args)
      :websocket      (or (:socket-id args) (:request-id args))
      :machine-invoke (or (:spawn-id args) (:machine-id args) (:id args))
      :ssr-fx         (:request-id args)
      :flow           (:flow-id args)
      nil)))

;; ---- wire timing (HTTP only natively; others are nil) ------------------

(defn- http-wire-timing
  "Pull a wire-timing map off the HTTP surface events. The runtime today
  does NOT emit per-phase timing (DNS / connect / SSL / request / TTFB
  / download); when it does, the shape is
  `{:phases [[<phase-kw> <duration-ms>]] :total-ms <ms>}` and the panel
  renders the waterfall.

  Two synthetic phases the panel can always show today:

    - `:issued`   — fx event time
    - `:received` — last surface event time (if any)

  Pure fn. Returns nil when there's no time information at all so the
  panel falls back to the `n/a` rendering."
  [fx-ev surface-events]
  (let [issued-ms (ms-of fx-ev)
        last-ev   (last (sort-by ms-of (or surface-events [])))
        last-ms   (when last-ev (ms-of last-ev))
        explicit  (some-> fx-ev :tags :wire-timing)]
    (cond
      (and (map? explicit) (seq (:phases explicit)))
      explicit

      (and (number? issued-ms) (number? last-ms) (> last-ms issued-ms))
      {:phases [[:issued 0]
                [:elapsed (- last-ms issued-ms)]]
       :total-ms (- last-ms issued-ms)
       :synthesised? true}

      :else
      nil)))

(defn- non-http-wire-timing
  "Same synthesised two-phase waterfall (`:issued` → end of cascade) for
  non-HTTP surfaces — used to show *some* timing even when the surface
  doesn't carry native per-phase data. Returns nil when no end-event
  is available so the panel renders `n/a`."
  [fx-ev surface-events]
  (let [issued-ms (ms-of fx-ev)
        last-ev   (last (sort-by ms-of (or surface-events [])))
        last-ms   (when last-ev (ms-of last-ev))]
    (when (and (number? issued-ms) (number? last-ms) (> last-ms issued-ms))
      {:phases [[:issued 0]
                [:elapsed (- last-ms issued-ms)]]
       :total-ms (- last-ms issued-ms)
       :synthesised? true})))

;; ---- per-surface adapters ----------------------------------------------

(defn- common-record
  "Folder shared by every surface — the basic record before
  surface-specific fields are added."
  [surface fx-ev surface-events]
  (let [status        (fx-event->status fx-ev)
        cancel-cause  (surface-events->cancel-cause surface-events)
        failure       (surface-events->failure surface-events)
        ;; Cancel cause overrides the basic status to :aborted-shape.
        terminal-status (cond
                          cancel-cause :error
                          failure      :error
                          :else        status)
        args         (fx-args-of fx-ev)]
    {:surface         surface
     :fx-id           (fx-id-of fx-ev)
     :req             args
     :wire            nil
     :res             nil
     :handler         (args-handler-event args)
     :status          terminal-status
     :phase           (surface-events->phase fx-ev surface-events terminal-status)
     :correlation-id  (args-correlation-id surface args)
     :cancel-cause    cancel-cause
     :http-status     nil
     :duration-ms     (when-let [w (or (http-wire-timing fx-ev surface-events)
                                       (non-http-wire-timing fx-ev surface-events))]
                        (:total-ms w))
     :failure         failure
     :paths-touched   []
     :origin-event-id (:id fx-ev)
     :dispatch-id     (dispatch-id-of fx-ev)
     :frame           (frame-id-of fx-ev)
     :stubbed?        (= status :overridden)}))

(defn http-adapter
  "HTTP surface adapter. Pull request payload off `:fx-args :request`;
  pull HTTP status / response off the surface events; fold the
  per-phase wire timing where available."
  [fx-ev cascade-other]
  (let [surface-events (surface-events-for cascade-other :http)
        args           (fx-args-of fx-ev)
        request        (when (map? args) (:request args))
        ;; A successful response trace (if any) carries `:response`
        ;; under `:tags` per Spec 014. Failure traces carry `:kind` +
        ;; `:status` for HTTP-4xx/5xx.
        terminal       (some (fn [ev]
                               (when (contains? #{:rf.http/handled
                                                  :rf.http/transport
                                                  :rf.http/timeout
                                                  :rf.http/decode-failure}
                                                (:operation ev))
                                 ev))
                             surface-events)
        response       (some-> terminal :tags :response)
        http-status    (or (some-> terminal :tags :status)
                           (some-> response :status))
        wire           (http-wire-timing fx-ev surface-events)
        rec            (common-record :http fx-ev surface-events)]
    (cond-> rec
      true        (assoc :req request
                         :wire wire
                         :res  response
                         :http-status http-status)
      ;; Failure tags can land directly on the surface event when the
      ;; runtime classifies the outcome under `:rf.http/*`; promote
      ;; them into the record's `:failure` map for the panel.
      (and (not (:failure rec))
           terminal
           (contains? #{:rf.http/transport
                        :rf.http/timeout
                        :rf.http/decode-failure}
                      (:operation terminal)))
      (assoc :failure {:kind (:operation terminal)
                       :tags (:tags terminal)
                       :message (some-> terminal :tags :message)}))))

(defn websocket-adapter
  "WebSocket surface adapter. The `:fx-args` carries connection-config
  (`:url`, `:socket-id`, frame payload); the surface events carry
  connection-state transitions. Per the divergence allowance, ship a
  basic record now — bi-directional frame timeline is a follow-on."
  [fx-ev cascade-other]
  (let [surface-events (surface-events-for cascade-other :websocket)
        args           (fx-args-of fx-ev)
        rec            (common-record :websocket fx-ev surface-events)]
    (assoc rec
           :req args
           :wire (non-http-wire-timing fx-ev surface-events))))

(defn machine-invoke-adapter
  "Machine `:spawn` adapter. The fx-args carry the spawned actor's
  `:machine-id` / `:spawn-id` / initial `:data`; the surface events
  carry the spawn/transition/destroy lifecycle."
  [fx-ev cascade-other]
  (let [surface-events (surface-events-for cascade-other :machine-invoke)
        args           (fx-args-of fx-ev)
        spawned        (some #(when (= (:operation %) :rf.machine.lifecycle/spawned) %)
                             surface-events)
        rec            (common-record :machine-invoke fx-ev surface-events)]
    (assoc rec
           :req args
           :wire (non-http-wire-timing fx-ev surface-events)
           :res (when spawned (select-keys (:tags spawned)
                                            [:spawn-id :machine-id :state])))))

(defn ssr-fx-adapter
  "SSR `:rf.server/*` adapter. The fx-args carry the response-shape
  contribution (status code, header name+value, cookie); the surface
  events carry render lifecycle. Records sit per-call, so a request
  with N `:rf.server/set-header` calls produces N records."
  [fx-ev cascade-other]
  (let [surface-events (surface-events-for cascade-other :ssr-fx)
        args           (fx-args-of fx-ev)
        rec            (common-record :ssr-fx fx-ev surface-events)]
    (assoc rec
           :req args
           :wire (non-http-wire-timing fx-ev surface-events)
           ;; The 'response' for an SSR-fx is the contribution to the
           ;; per-request accumulator — the args map IS the
           ;; contribution.
           :res args)))

(defn flow-adapter
  "Managed-flow adapter. The fx-args carry the registration (for
  `:rf.fx/reg-flow`) or the flow-id (`:rf.fx/clear-flow`); surface
  events carry per-flow computation / failure traces."
  [fx-ev cascade-other]
  (let [surface-events (surface-events-for cascade-other :flow)
        args           (fx-args-of fx-ev)
        computed       (some #(when (= (:operation %) :rf.flow/computed) %)
                             surface-events)
        rec            (common-record :flow fx-ev surface-events)]
    (assoc rec
           :req args
           :wire (non-http-wire-timing fx-ev surface-events)
           :res (some-> computed :tags :output))))

(def surface->adapter
  {:http           http-adapter
   :websocket      websocket-adapter
   :machine-invoke machine-invoke-adapter
   :ssr-fx         ssr-fx-adapter
   :flow           flow-adapter})

;; ---- cascade walker ----------------------------------------------------

(defn cascade->managed-fx-records
  "Walk a cascade record (per `re-frame.trace.projection/group-cascades`)
  and project one managed-fx record per `:rf.fx/handled` (or
  `:rf.fx/override-applied`, etc.) event whose fx-id classifies as a
  managed-fx surface.

  Pure fn. Returns a vector of records in cascade order. Each record
  carries the surface-specific projection of `req` / `wire` / `res` /
  `handler` / `status` / `phase` / `correlation-id` / `cancel-cause`.

  When `paths-by-dispatch-id` is supplied (per the composite sub) the
  record's `:paths-touched` is filled with the app-db diff paths that
  changed during this cascade — the F.4 'app-db wasn't updated' bug
  class lights up when this list is empty for an OK-status record."
  ([cascade]
   (cascade->managed-fx-records cascade nil))
  ([{:keys [effects other dispatch-id] :as _cascade} paths-by-dispatch-id]
   (let [fx-events     (filterv managed-fx-effect? (or effects []))
         path-touched  (when paths-by-dispatch-id
                         (get paths-by-dispatch-id dispatch-id []))]
     (vec
       (for [fx-ev fx-events
             :let  [surface (classify-fx-id (fx-id-of fx-ev))
                    adapter (get surface->adapter surface)]
             :when adapter]
         (-> (adapter fx-ev other)
             (assoc :paths-touched (vec path-touched))))))))

;; ---- formatting helpers (consumed by the view) ------------------------

(defn format-status-label
  "Human-readable status label for the panel header."
  [status]
  (case status
    :ok         "OK"
    :error      "ERROR"
    :in-flight  "IN-FLIGHT"
    :overridden "OVERRIDDEN"
    :skipped    "SKIPPED"
    :stub       "STUB"
    "—"))

(defn format-fx-id
  "Render an fx-id keyword as `:ns/name` for compact display."
  [fx-id]
  (cond
    (keyword? fx-id) (str fx-id)
    (nil? fx-id)     "—"
    :else            (str fx-id)))

(defn format-http-status-band
  "Map HTTP status codes onto colour bands per the panel header rule:
  green for 2xx, yellow for 3xx, red for 4xx/5xx, gray for nil/in-flight."
  [status]
  (cond
    (and (number? status) (<= 200 status 299)) :green
    (and (number? status) (<= 300 status 399)) :yellow
    (and (number? status) (<= 400 status 599)) :red
    :else                                       :text-tertiary))

(defn format-duration-ms
  "Render a ms duration as `<n>ms` (or `<n.n>s` once it crosses 1s).
  Returns `—` for nil / non-number input."
  [ms]
  (cond
    (not (number? ms)) "—"
    (< ms 1000)        (str (long ms) "ms")
    :else              (str (Math/round (/ ms 100.0)) "00ms")))

;; ---- spec-018 §Bug class coverage table -------------------------------
;;
;; Surfaced here as data so the view can iterate the coverage rows in
;; the panel doc-overlay (if/when added) and so the test suite can pin
;; the bug-class → field mapping the panel claims to address.

(def bug-class-coverage
  "Map of `:F.<n>` → the record field(s) the panel surfaces to address
  that bug class. Anchors the per-class coverage claim in the
  findings doc."
  {:F.1  [:wire :duration-ms]            ;; request-timeout waterfall
   :F.2  [:req]                          ;; bad-request payload inspector
   :F.3  [:handler :failure]             ;; failed response handler
   :F.4  [:paths-touched :status]        ;; app-db wasn't updated
   :F.5  [:cancel-cause :correlation-id] ;; cancellation cascade (deferred to rf2-wvkn)
   :F.6  [:correlation-id]               ;; stale response — id mismatch in header
   :F.7  [:failure]                      ;; remaining structured-failure surfacing
   :F.8  [:status :stubbed?]             ;; stub/override visibility
   :F.9  [:status]                       ;; skipped-on-platform visibility
   :F.10 [:surface]                      ;; surface badge taxonomy
   :F.11 [:cancel-cause]})               ;; cross-surface stale-suppression
