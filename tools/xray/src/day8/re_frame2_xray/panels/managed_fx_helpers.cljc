(ns day8.re-frame2-xray.panels.managed-fx-helpers
  "Pure-data helpers for the managed-fx wire-boundary diff panel
  (rf2-uyp86, parent rf2-5aw5v — flagship cross-cutting feature from
  `tools/xray/spec/019-Cross-Cutting-Insight.md` §2.4).

  ## What the panel needs

  One uniform record per managed-fx invocation in a focused event-bundle,
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
                                            ;; doesn't emit timing;
                                            ;; ALWAYS nil for :http
       :res             <response-payload>  ;; nil when in-flight or
                                            ;; the surface has no
                                            ;; reply-shape; ALWAYS nil
                                            ;; for :http
       :handler         <handler-event-vec> ;; the CONFIGURED reply
                                            ;; target (:reply-to, else
                                            ;; the :on-success/:on-done/
                                            ;; :on-failure sugar) — what
                                            ;; the caller wrote, not an
                                            ;; observed delivery
       :status          :issued | :ok | :error | :in-flight
                                | :overridden | :skipped | :stub
       :phase           :issued | :sent | :received | :completed
                                | :failed | :aborted  ;; nil for :http
       :correlation-id  <id-or-nil>         ;; request-id, machine
                                            ;; spawn id, …
       :cancel-cause    <kw-or-nil>         ;; :user / :actor-destroyed
                                            ;; / :superseded / …
       :http-status     <int-or-nil>        ;; HTTP only
       :duration-ms     <num-or-nil>        ;; elapsed ms
       :failure         <failure-map-or-nil>;; `:rf.<surface>/*` kind +
                                            ;; tags
       :paths-touched   [<path> ...]        ;; app-db slice paths the
                                            ;; handler caused to change;
                                            ;; nil = UNTRACKED (no diff
                                            ;; feed is wired today), as
                                            ;; distinct from [] = measured
                                            ;; and nothing changed
       :origin-event-id <int-or-nil>}       ;; trace-event :id of the
                                            ;; `:rf.fx/handled` emit —
                                            ;; the cross-link anchor

  Pure data → data. JVM-runnable so the test suite can drive the
  projection without booting a CLJS runtime.

  ## Surface adapters

  Each adapter is a fn `(adapter fx-event surface-events handler-event)`
  taking three slices off the event-bundle:

    - `fx-event`        — the `:rf.fx/handled` event for this fx
                          invocation (carries `:fx-id`, `:fx-args`,
                          `:dispatch-id`, `:frame` on `:tags`).
    - `surface-events`  — the other trace events on the same event-bundle
                          for this surface (e.g.
                          `:rf.http/retry-attempt`,
                          `:rf.http/aborted-on-actor-destroy`,
                          `:rf.machine.lifecycle/spawned`,
                          `:rf.flow/computed`).
    - `handler-event`   — the dispatched response event vector (if
                          known via the fx-args `:on-success` /
                          `:on-failure` / `:on-done` slot or via the
                          event-bundle's child `:event` slot).

  Each adapter returns one record per managed-fx invocation in the
  event-bundle. The composite-sub layer walks the event-bundle once, partitions
  the effects by surface, and routes each to the right adapter.

  ## What's NOT addressed (yet)

  Wire-timing is only natively emitted by `:rf.http/managed` today —
  the other surfaces default `:wire` to nil and the panel renders a
  `n/a` placeholder. The retry-attempt timeline (F.3) lives under
  `:rf.http/retry-attempt` traces and is folded into the HTTP record's
  `:phase` / `:duration-ms` summary. Per-attempt drill-down is a
  follow-on bead."
  (:require [clojure.string :as str]
            [day8.re-frame2-xray.panels.common-helpers :as common]
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as diff-h]))

;; ---- surface taxonomy ---------------------------------------------------

(def surfaces
  "Canonical render-order — the same order the template list uses, so a
  event-bundle with HTTP + machine-invoke + flow records always renders in
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
  {:issued     :text-secondary          ; neutral — a statement about the request going out, not about its outcome
   :ok         :green
   :error      :red
   :in-flight  :info                     ; fixed cool blue — distinct from the accent-coloured :overridden/:stub
   :overridden :accent
   :skipped    :text-tertiary
   :stub       :accent})

(def status->glyph
  "Status → shape-glyph (colour-blind-safe parallel signal)."
  {:issued     "◦"   ;; ◦ — deliberately not ✓: nothing here says the request succeeded
   :ok         "✓"   ;; ✓
   :error      "✗"   ;; ✗
   :in-flight  "⧖"   ;; ⧖
   :overridden "◑"   ;; ◑
   :skipped    "○"   ;; ○
   :stub       "◑"}) ;; ◑

;; ---- common readers -----------------------------------------------------

(def tag common/tag-of)

(defn- fx-id-of [ev]
  (tag ev :rf.fx/id))

(defn- fx-args-of [ev]
  (tag ev :rf.fx/args))

(defn- dispatch-id-of [ev]
  (tag ev :rf.trace/dispatch-id))

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
  "Trace operations the HTTP surface really emits, kept under their real
  names so the deferred cross-buffer join (rf2-6ooch) has them.

  IN THE ISSUING EVENT-BUNDLE THIS FILTER IS ALL BUT EMPTY, AND THAT IS
  THE RUNTIME'S SHAPE RATHER THAN A GAP. `re-frame.trace/emit!` and
  `emit-error!` take the dispatch-id from the dynamic `*handler-scope*`,
  and every HTTP row after issuance is emitted either from a transport
  callback (no scope at all — the grouper files it under
  `[nil :ungrouped]`) or inside a DIFFERENT run's drain (the aborting or
  destroying event's). So the completion, the retries, the aborts and the
  stale-suppressions can none of them reach the bundle that issued the
  request.

  The ONE exception is a SYNCHRONOUS request-body-prep failure: a
  throwing `:body` thunk or an unencodable body fails inside the fx
  handler's own stack, so its `:rf.http/transport` row is emitted while
  the issuing drain is still live and lands in this bundle.

  `:rf.http/handled` and `:rf.http/managed-issued` USED TO BE LISTED HERE
  AND ARE NOT EMITTED ANYWHERE (rf2-y8doi.18). They were the collector's
  only route to a `:res` / `:http-status` / wire timing, so reading them
  produced a record that said `OK · completed` with every outcome field
  nil, for a request that may well have failed. Do not restore them; the
  runtime's completion op is `:rf.http/replied`, and it is out of reach
  from here by construction."
  #{:rf.http/retry-attempt
    :rf.http/aborted-on-actor-destroy
    :rf.http/aborted
    :rf.http/transport
    :rf.http/timeout
    :rf.http/decode-failure})

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
  "Trace operations the machine-invoke surface emits.

  Both DESTROY channels are listed, and both are required: per Spec 009
  §op-type vocabulary they are disjoint, so neither alone is a complete
  record of an actor going away. `:rf.machine/destroyed` is the
  fx-substrate terminal — the normal teardown (`:explicit`,
  `:rf.machine/finished`);
  `:rf.machine.lifecycle/destroyed` is the registrar-substrate
  frame-exit reap (`:parent-frame-destroyed`, its sole reason).

  Note `:rf.machine/destroy` (no trailing `-ed`) is deliberately NOT
  here: it is the reserved fx-id — a COMMAND the runtime consumes — and
  never appears as a trace `:operation`. Listing it collected nothing
  while the real terminal went unread."
  #{:rf.machine/transition
    :rf.machine.transition/suppressed
    :rf.machine.lifecycle/spawned
    :rf.machine.lifecycle/destroyed
    :rf.machine/destroyed
    :rf.machine/invoke-failed
    :rf.machine.timer/scheduled
    :rf.machine.timer/fired
    :rf.machine.timer/stale-after
    :rf.machine.timer/cancelled
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
  "Filter `event-bundle-other-events` to the operations a given surface
  emits. The `:other` slot of the event-bundle record per
  `re-frame.trace.projection/group-by-event` is where these non-domino
  traces land. Pure fn."
  [event-bundle-other-events surface]
  (let [ops (case surface
              :http           http-trace-operations
              :websocket      websocket-trace-operations
              :machine-invoke machine-invoke-trace-operations
              :ssr-fx         ssr-fx-trace-operations
              :flow           flow-trace-operations
              #{})]
    (filterv #(contains? ops (:operation %)) (or event-bundle-other-events []))))

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
  "When the event-bundle includes an HTTP abort trace, surface its cause so
  the panel header can render `cancel: :actor-destroyed`. Returns nil when
  no cancellation is present.

  This reader owns HTTP-request cancellation only. Machine disappearance is
  NOT a cancel-cause here: `:rf.machine/destroy` is the reserved fx-id — a
  COMMAND the runtime consumes — and never appears as a trace `:operation`
  (see `machine-invoke-trace-operations`; the real fx-substrate terminal is
  `:rf.machine/destroyed`). Machine-disappearance cancellation semantics are
  owned by the cancellation-cascade projection, not this per-record reader."
  [surface-events]
  (some (fn [ev]
          (case (:operation ev)
            :rf.http/aborted-on-actor-destroy :actor-destroyed
            :rf.http/aborted                  (or (get-in ev [:tags :reason])
                                                  :user)
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
  "Pull the CONFIGURED reply target off the fx-args, if any.

  This is configuration the caller wrote, NOT an observation: nothing
  here watches a reply being delivered, and for HTTP the reply lands in
  its own later event-bundle with its own dispatch-id. The panel labels
  it accordingly.

  `:reply-to` is first because it is the app-facing unified key — per
  [Spec 014 §Reply addressing](../../../../spec/014-HTTPRequests.md) one
  target for both the success and the failure reply, with `:on-success` /
  `:on-failure` the split routing sugar over it, all three lowering onto
  the one internal descriptor. It was missing here, so a request written
  the recommended way read as having no reply target at all.

  `:on-done` is the machine surface's spelling. `:on-reply` was listed
  here too and is NOT a reply target anywhere in this tree — every
  occurrence of it is a derivation `:evaluation` policy value
  (`#{:on-route :on-reply :scheduled :manual}`), so reading it could only
  ever have surfaced a policy keyword as though it were an event vector.

  Pure fn."
  [args]
  (when (map? args)
    (or (:reply-to args)
        (:on-success args)
        (:on-done args)
        (:on-failure args))))

;; ---- correlation-id resolution -----------------------------------------

(defn- args-correlation-id
  "Surface-specific correlation id reader. HTTP carries `:request-id`;
  machine `:spawn` carries the registered TYPE under `:machine-id` and an
  explicit actor-address input under `:fixed-actor-id` (rf2-0ggtr5 — the
  former overloaded `:spawn-id`); SSR-fx events carry `:request-id` at the
  server-side accumulator. The caller's args map names it; we resolve
  defensively."
  [surface args]
  (when (map? args)
    (case surface
      :http           (:request-id args)
      :websocket      (or (:socket-id args) (:request-id args))
      :machine-invoke (or (:fixed-actor-id args) (:machine-id args) (:id args))
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
  "Same synthesised two-phase waterfall (`:issued` → end of event-bundle) for
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
     ;; nil means UNTRACKED, and is the default because no diff feed is
     ;; wired at all today. `[]` would mean "measured, and nothing
     ;; changed" — a different claim, and the one the retired amber
     ;; warning was drawn from. The walker fills this in when a caller
     ;; supplies `paths-by-dispatch-id`.
     :paths-touched   nil
     :origin-event-id (:id fx-ev)
     :dispatch-id     (dispatch-id-of fx-ev)
     :frame           (frame-id-of fx-ev)
     :stubbed?        (= status :overridden)}))

(defn- http-row-for-this-record?
  "Does a same-bundle HTTP row belong to THIS record?

  One event can issue several HTTP requests, so a bundle can hold more
  than one HTTP record and (in the synchronous body-prep case) a failure
  row belonging to exactly one of them. Attributing any failure row to
  every HTTP record in the bundle reddens requests that were merely
  issued, which is the same class of untruth as the `OK · completed` this
  record was narrowed to escape — so attribution is positive-evidence
  only, and the fallback is `:issued` rather than a guess.

  The failure emit carries the caller's `:request-id` in its tags
  (`re-frame.http.transport` stamps `:request-id (:request-id ctx)` onto
  the redacted failure map), so when the caller supplied one there is a
  real key to match on. `:request-id` is OPTIONAL per Spec 014, and when
  the record has none the only sound attribution left is arithmetic: if
  this is the bundle's SOLE HTTP effect, an HTTP row in the bundle can
  have come from nothing else."
  [record-request-id sole-http-fx? ev]
  (if (some? record-request-id)
    (= record-request-id (get-in ev [:tags :request-id]))
    (boolean sole-http-fx?)))

(defn http-adapter
  "HTTP surface adapter — the record is narrowed to ISSUANCE.

  What one drain can see about a managed HTTP request is that the fx
  handler returned and the transport was entered: `:rf.fx/handled` is
  emitted AFTER the handler returns true, so the row means the request
  went out, and nothing after that point reaches this bundle (see
  `http-trace-operations`). The record therefore says `:issued` and
  leaves `:phase` / `:wire` / `:res` / `:duration-ms` / `:http-status`
  nil BY CONSTRUCTION rather than pending — there is no later event that
  could fill them in, so a UI that renders them would be rendering a
  promise, not a measurement.

  `:issued` is a NEW status rather than `:ok` relabelled, so
  `(= status :ok)` can never again mean 'completed' by accident. It is
  set here, on the HTTP surface, rather than in `fx-event->status`: the
  other four surfaces DO get their end events in-bundle, so `:ok` keeps
  its meaning for them.

  The one outcome this bundle CAN witness is a synchronous
  request-body-prep failure, which runs inside the fx handler's own
  stack; it is attributed per `http-row-for-this-record?` and reddens the
  record to `:error` through `common-record`. `opts` carries
  `:sole-http-fx?` — whether this is the bundle's only HTTP effect, which
  the walker knows and the adapter cannot. The 2-arity assumes it is,
  which is both the common case and what a direct single-record caller
  means."
  ([fx-ev event-bundle-other]
   (http-adapter fx-ev event-bundle-other {:sole-http-fx? true}))
  ([fx-ev event-bundle-other {:keys [sole-http-fx?] :or {sole-http-fx? true}}]
   (let [args           (fx-args-of fx-ev)
         request-id     (when (map? args) (:request-id args))
         surface-events (filterv #(http-row-for-this-record? request-id sole-http-fx? %)
                                 (surface-events-for event-bundle-other :http))
         request        (when (map? args) (:request args))
         rec            (common-record :http fx-ev surface-events)]
     (assoc rec
            :req         request
            ;; Nil by construction, not "not yet" — see the docstring.
            :wire        nil
            :res         nil
            :phase       nil
            :duration-ms nil
            :http-status nil
            :status      (if (= :ok (:status rec)) :issued (:status rec))))))

(defn websocket-adapter
  "WebSocket surface adapter. The `:fx-args` carries connection-config
  (`:url`, `:socket-id`, frame payload); the surface events carry
  connection-state transitions. Per the divergence allowance, ship a
  basic record now — bi-directional frame timeline is a follow-on."
  [fx-ev event-bundle-other]
  (let [surface-events (surface-events-for event-bundle-other :websocket)
        args           (fx-args-of fx-ev)
        rec            (common-record :websocket fx-ev surface-events)]
    (assoc rec
           :req args
           :wire (non-http-wire-timing fx-ev surface-events))))

(defn machine-invoke-adapter
  "Machine `:spawn` adapter. The fx-args carry the registered TYPE under
  `:machine-id`, the explicit actor-address input under `:fixed-actor-id`
  (rf2-0ggtr5), and initial `:data`; the surface events carry the
  spawn/transition/destroy lifecycle. The `:rf.machine.lifecycle/spawned`
  trace carries the TYPE (`:machine-id`), the instance address
  (`:spawned-id`), and the declarative invocation path (`:invoke-id`,
  rf2-0ggtr5 — was `:spawn-id`)."
  [fx-ev event-bundle-other]
  (let [surface-events (surface-events-for event-bundle-other :machine-invoke)
        args           (fx-args-of fx-ev)
        spawned        (some #(when (= (:operation %) :rf.machine.lifecycle/spawned) %)
                             surface-events)
        rec            (common-record :machine-invoke fx-ev surface-events)]
    (assoc rec
           :req args
           :wire (non-http-wire-timing fx-ev surface-events)
           :res (when spawned (select-keys (:tags spawned)
                                            [:invoke-id :machine-id :spawned-id :state])))))

(defn ssr-fx-adapter
  "SSR `:rf.server/*` adapter. The fx-args carry the response-shape
  contribution (status code, header name+value, cookie); the surface
  events carry render lifecycle. Records sit per-call, so a request
  with N `:rf.server/set-header` calls produces N records."
  [fx-ev event-bundle-other]
  (let [surface-events (surface-events-for event-bundle-other :ssr-fx)
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
  [fx-ev event-bundle-other]
  (let [surface-events (surface-events-for event-bundle-other :flow)
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

;; ---- event-bundle walker ----------------------------------------------------

(defn event-bundle->managed-fx-records
  "Walk an event-bundle record (per `re-frame.trace.projection/group-by-event`)
  and project one managed-fx record per `:rf.fx/handled` (or
  `:rf.fx/override-applied`, etc.) event whose fx-id classifies as a
  managed-fx surface.

  Pure fn. Returns a vector of records in event-bundle order. Each record
  carries the surface-specific projection of `req` / `wire` / `res` /
  `handler` / `status` / `phase` / `correlation-id` / `cancel-cause`.

  When `paths-by-dispatch-id` is supplied the record's `:paths-touched`
  is filled with the app-db diff paths that changed during this
  event-bundle. NO CALLER SUPPLIES IT TODAY — the composite sub in
  `panels/managed_fx_subs` calls the 1-arity — so `:paths-touched` is
  normally nil, meaning UNTRACKED rather than measured-and-empty. The two
  must stay distinguishable: the panel used to read the empty vector as
  evidence and warn, on every single successful record, that the author's
  handler had failed to write app-db."
  ([event-bundle]
   (event-bundle->managed-fx-records event-bundle nil))
  ([{:keys [effects other dispatch-id] :as _event-bundle} paths-by-dispatch-id]
   (let [fx-events     (filterv managed-fx-effect? (or effects []))
         path-touched  (when paths-by-dispatch-id
                         (vec (get paths-by-dispatch-id dispatch-id [])))
         ;; Whether an HTTP failure row in this bundle can be attributed
         ;; to a record that carries no `:request-id` is a fact about the
         ;; BUNDLE, so only this walker can answer it.
         http-fx-count (count (filterv #(= :http (classify-fx-id (fx-id-of %)))
                                       fx-events))]
     (vec
       (for [fx-ev fx-events
             :let  [surface (classify-fx-id (fx-id-of fx-ev))
                    adapter (get surface->adapter surface)]
             :when adapter]
         (-> (if (= surface :http)
               (http-adapter fx-ev other {:sole-http-fx? (= 1 http-fx-count)})
               (adapter fx-ev other))
             (assoc :paths-touched path-touched)))))))

;; ---- formatting helpers (consumed by the view) ------------------------

(defn format-status-label
  "Human-readable status label for the panel header."
  [status]
  (case status
    :issued     "ISSUED"
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

;; The `bug-class-coverage` map that used to sit here is DELETED
;; (rf2-y8doi.12 #9, rf2-y8doi.18). It claimed, as data, that each bug
;; class in `tools/xray/spec/019-Cross-Cutting-Insight.md` was addressed
;; by a named record field, and nothing but its own test ever read it —
;; so it was a claim that could not go stale loudly. Several of its rows
;; were already untrue: the F.1 row named `[:wire :duration-ms]`, both of
;; which are nil by construction for HTTP, and the F.4 row
;; (`[:paths-touched :status]`) was the only anchor for the amber
;; "app-db wasn't updated" warning this bead removed.
;;
;; The 019 catalogue is still audited in both directions, by a separate
;; and unrelated map of the same name in
;; `tools/xray/test/day8/re_frame2_xray/coverage_matrix_metadata_test.clj`,
;; which never referred to this one.

;; ---- section disclosure state -------------------------------------------
;;
;; `theme/section/section-row` draws the `▶`/`▼` glyph but wires no click:
;; its own docstring states the contract deliberately — "No interactivity.
;; Click-to-toggle wiring is the caller's responsibility." The primitive is
;; shared with `panels/fresco` and `panels/module_view`, so the open/closed
;; state belongs to THIS panel and not to the widget.
;;
;; Shape copied from the edn-inspector widget's own expansion slot
;; (`views/edn_inspector_state.cljs`): a SPARSE override map plus a pure
;; `resolve-expanded?` projection that falls back to a static default.
;; Sparse-with-default is what lets the panel paint correctly before the
;; operator has touched anything — an absent entry means "at its default",
;; which is not the same as "closed", and two of the five sections default
;; open.
;;
;; Pure data, JVM-portable — no tokens, no hiccup, no re-frame registration.
;; The `reg-sub` / `reg-event` that own the slot live in `managed_fx_subs`.

(def expansion-slot
  "App-db slot holding the record panel's per-section expansion
  overrides — a sparse map of `(expansion-key …)` → boolean."
  :rf.xray.managed-fx/expanded-sections)

(def section-defaults
  "First-paint `:expanded?` for each of the record panel's five sections,
  per `record-panel`'s documented contract: WIRE TIMING and APP-DB SLICE
  TOUCHED open, REQUEST / RESPONSE / HANDLER DISPATCHED closed so the
  panel stays scannable until the operator drills in.

  Read by BOTH the renderer (to paint the untouched section) and the
  toggle event (to know what the first click must invert). One map, so
  the two cannot disagree — the edn-inspector widget has to pass the
  rendered state through the dispatch payload precisely because its
  defaults are computed per node rather than fixed per section."
  {:request  false
   :wire     true
   :response false
   :handler  false
   :app-db   true})

(defn record-key
  "Stable per-record identity — `\"<surface>-<origin-event-id>-<fx-id>\"`.

  One composer for two consumers that MUST agree: the React `:key` the
  panel carries in its `:section` attribute map, and the expansion key
  below. If they drifted, toggling a section on one record would move a
  different record's disclosure."
  [record]
  (str (:surface record) "-" (:origin-event-id record) "-" (:fx-id record)))

(defn expansion-key
  "Compose the per-section override key. Pure data, JVM-portable.

  Keyed per RECORD as well as per section: an event-bundle can carry
  several managed-fx records, and opening REQUEST on one of them must
  not open it on all of them."
  [rec-key section-id]
  [rec-key section-id])

(defn resolve-expanded?
  "Pure projection — does this record's `section-id` render expanded?
  The operator's stored override wins; absent one, the section's
  `section-defaults` entry does.

  `overrides` is the value of `expansion-slot` (nil before the operator
  has touched anything, which resolves every section to its default)."
  [overrides rec-key section-id]
  (let [override (get overrides (expansion-key rec-key section-id))]
    (if (some? override)
      (boolean override)
      (boolean (get section-defaults section-id)))))
