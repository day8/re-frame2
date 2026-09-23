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
       :fx-id           <id>                ;; the fx id the handler
                                            ;; EMITTED (a redirect's
                                            ;; target rides :override-to)
       :req             <request-payload>   ;; method, url, headers,
                                            ;; body (or surface-specific
                                            ;; equivalent)
       :wire            <wire-timing-map>   ;; nil when the surface
                                            ;; doesn't emit timing; for
                                            ;; :http the `:issued ->
                                            ;; :elapsed` pair, and only
                                            ;; once the completion joined
       :res             <response-payload>  ;; nil when in-flight or
                                            ;; the surface has no
                                            ;; reply-shape; for :http the
                                            ;; joined terminal row's
                                            ;; elided summary
       :handler         <handler-event-vec> ;; the CONFIGURED reply
                                            ;; target (:reply-to, else
                                            ;; the :on-success/:on-done/
                                            ;; :on-failure sugar) — what
                                            ;; the caller wrote, not an
                                            ;; observed delivery
       :status          :issued | :ok | :error | :cancelled | :stale
                                | :in-flight | :overridden | :skipped
                                | :stub
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
       :origin-event-id <int-or-nil>        ;; trace-event :id of the
                                            ;; `:rf.fx/handled` emit —
                                            ;; the cross-link anchor
       :overridden?     <bool>              ;; an `:fx-overrides` entry
                                            ;; replaced the handler —
                                            ;; read off override
                                            ;; PROVENANCE only, never off
                                            ;; a missing row
       :override-to     <id-or-nil>         ;; the replacement: a redirect
                                            ;; target, or
                                            ;; :re-frame.fx/fn-value
       :override-from   <id-or-nil>         ;; the id the handler emitted
       ;; HTTP only (rf2-6ooch — the cross-buffer completion join):
       :completion      :joined | :none | nil ;; see `http-adapter`
       :attempts        <int-or-nil>        ;; the terminal row's attempt
       :reply-link      {:dispatch-id :frame} | nil} ;; the delivered
                                            ;; reply's bundle, when it
                                            ;; is in the capture

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

  No surface natively emits per-phase wire timing today. The non-HTTP
  surfaces synthesise an `:issued -> :elapsed` pair from their in-bundle
  rows, and HTTP draws the same pair once its completion has joined
  (elapsed includes any retry backoff). The retry-attempt timeline (F.3)
  lives under `:rf.http/retry-attempt` traces, which carry no work id and
  are not joined; the HTTP record reads its attempt count off the
  terminal row. Per-attempt drill-down is a follow-on bead."
  (:require [clojure.string :as str]
            [day8.re-frame2-xray.panels.common-helpers :as common]
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as diff-h]
            [day8.re-frame2-xray.panels.reply-envelope :as re]))

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
   :cancelled  :magenta                  ; intentionally cancelled — not a failure, so not red (rf2-6ooch)
   :stale      :text-tertiary            ; completed after its correlation went obsolete; nothing delivered
   :in-flight  :info                     ; fixed cool blue — distinct from the accent-coloured :overridden/:stub
   :overridden :accent
   :skipped    :text-tertiary
   :stub       :accent})

(def status->glyph
  "Status → shape-glyph (colour-blind-safe parallel signal)."
  {:issued     "◦"   ;; ◦ — deliberately not ✓: nothing here says the request succeeded
   :ok         "✓"   ;; ✓
   :error      "✗"   ;; ✗
   :cancelled  "◌"   ;; ◌
   :stale      "⊘"   ;; ⊘
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

(defn- record-fx-id
  "The id a record is listed under: the id the handler EMITTED.

  A keyword-redirected `:rf.fx/handled` row carries the redirect TARGET as
  `:rf.fx/id` and the emitted id as `:rf.fx/from` (`re-frame.fx/emit-
  handled!`), so the emitted id wins whenever it names a managed surface —
  otherwise `{:rf.http/managed :app/fake-http}` keys on `:app/fake-http`,
  which classifies as no surface, and the record vanishes. A redirect INTO
  a managed surface from a non-managed id (`{:app/fetch :rf.http/managed}`)
  keeps the target, so that record does not vanish either.

  ONE classification: the walker's filter, its HTTP set and its adapter
  dispatch all read this, so the three cannot disagree."
  [ev]
  (let [from (tag ev :rf.fx/from)]
    (if (classify-fx-id from) from (fx-id-of ev))))

(defn managed-fx-effect?
  "True when an effect row names a managed-fx-surface fx-id (per
  `record-fx-id`). An `:rf.fx/override-applied` row never is one: it is
  PROVENANCE for the handled row that follows it, not a record of its
  own. Pure predicate."
  [ev]
  (and (not= :rf.fx/override-applied (:operation ev))
       (boolean (classify-fx-id (record-fx-id ev)))))

;; ---- surface-event collectors ------------------------------------------

(def http-trace-operations
  "Trace operations the HTTP surface really emits, as they can appear IN
  THE ISSUING BUNDLE. The request's outcome is read off the whole trace
  buffer instead — see `http-terminal-index` (rf2-6ooch).

  IN THE ISSUING EVENT-BUNDLE THIS FILTER IS ALL BUT EMPTY, AND THAT IS
  THE RUNTIME'S SHAPE RATHER THAN A GAP. `re-frame.trace/emit!` and
  `emit-error!` take the dispatch-id from the dynamic `*handler-scope*`,
  and most HTTP rows after issuance are emitted either from a transport
  callback (no scope at all — the grouper files it under
  `[nil :ungrouped]`) or inside a DIFFERENT run's drain (the aborting or
  destroying event's). So the completion, the retries and the
  stale-suppressions can none of them reach the bundle that issued the
  request.

  THREE THINGS DO REACH IT, because all three run inside the issuing fx
  handler's own stack and so inherit the issuing scope's dispatch-id:

  1. A SYNCHRONOUS request-body-prep failure: a throwing `:body` thunk or
     an unencodable body fails inside the fx handler's stack, so its
     `:rf.http/transport` row is emitted while the issuing drain is still
     live. This is THIS attempt's own outcome and is attributed.

  2. The `:rf.http/aborted` an issuance FIRES AT THE ATTEMPT IT REPLACES
     (rf2-n3sx9). `managed-handler` calls `registry/supersede!`
     synchronously while issuing the new request; `supersede!` calls the
     evicted handle's abort-fn with `:request-id-superseded`, which
     reaches `dispatch-aborted!` and emits the row — with the issuing
     scope still on the stack and the SAME `:request-id`, because sharing
     the id is what supersession IS. That row belongs to the PRIOR
     attempt, and `http-row-for-this-record?` excludes it.

  3. The `:rf.http/aborted` an ALREADY-ABORTED EXTERNAL `:abort-signal`
     fires at THIS attempt — on CLJS only. `run-attempt!` binds the
     caller's signal to this handle's canonical abort-fn during attempt
     setup; a signal that is ALREADY aborted fires that abort-fn
     synchronously right there (`transport-cljs/bind-external-abort!`),
     with reason `:user` and this record's own `:request-id`, and the
     `(when-not @finalised? …)` guard below it then skips body prep and
     the transport entirely. Like (1) this is THIS attempt's own outcome
     and IS attributed — `http-row-for-this-record?` calls it the one
     cancellation the issuing bundle genuinely witnesses about its own
     attempt.

  THIS DOCSTRING USED TO SAY THE BODY-PREP FAILURE WAS THE ONE EXCEPTION.
  It was wrong about (2), and the cost was real: every debounced search
  request after the first read `ERROR · cancel: :request-id-superseded`
  while being perfectly healthy. It was then short by (3) until rf2-or16u,
  which is the same failure one generation on: a hand-maintained
  enumeration the producer had moved past.

  A cancellation can also arrive from a NON-HTTP effect in the same drain
  — an actor destroy walking its in-flight handles — naming a request this
  bundle never issued. That row is real and correctly collected here; what
  it is not is any particular record's, which is again
  `http-row-for-this-record?`'s call.

  `:rf.http/handled` and `:rf.http/managed-issued` USED TO BE LISTED HERE
  AND ARE NOT EMITTED ANYWHERE (rf2-y8doi.18). They were the collector's
  only route to a `:res` / `:http-status` / wire timing, so reading them
  produced a record that said `OK · completed` with every outcome field
  nil, for a request that may well have failed. Do not restore them; the
  runtime's completion op is `:rf.http/replied`, it is out of reach from
  the issuing bundle by construction, and the cross-buffer join reads it
  where it actually lands."
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
  Override provenance is read separately (`override-of`): `common-record`
  turns an overridden handled row's `:ok` into `:overridden`."
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

(defn- cancellation-row?
  "Is this surface row a CANCELLATION rather than a failure?

  Single-sourced off `surface-events->cancel-cause` — the one reader that
  already knows which operations are cancellations — so the two cannot
  drift. A one-row call returns that row's cause, or nil when the row is
  not a cancellation at all."
  [ev]
  (some? (surface-events->cancel-cause [ev])))

(defn- supersede-of-the-attempt-we-replaced?
  "Is this cancellation the one THIS issuance fired at the attempt it
  replaced?

  `re-frame.http.registry/supersede!` is the only site in the tree that
  passes `:request-id-superseded` to an abort-fn, and it passes it to the
  handle it has just evicted — so the reason names a PRIOR attempt by
  construction, never the one being issued.

  It has to be read off the REASON, because the superseded attempt and its
  replacement share the `:request-id`: that sharing is what supersession
  IS, so no id comparison can separate them. The work-ids do differ (the
  issuance number discriminates them), but the `:rf.fx/handled` row carries
  only the caller's own fx-args, which have no issuance in them — there is
  nothing on this side to compare against."
  [ev]
  (and (= :rf.http/aborted (:operation ev))
       (= :request-id-superseded (get-in ev [:tags :reason]))))

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

(defn- override-of
  "The `:fx-overrides` entry that replaced this handled row's handler, as
  `{:from <id the handler emitted> :to <redirect target, or
  :re-frame.fx/fn-value>}` — or nil when the row carries no override
  PROVENANCE (rf2-3x7nj.23.5).

  Two producer shapes, and only these: a keyword redirect stamps
  `:rf.fx/from` on the handled row itself; a function override leaves the
  handled row plain and emits `:rf.fx/override-applied` just before the
  function fires, which the walker pairs by position
  (`pair-override-rows`) and records under `::override`. The ABSENCE of
  some other row — an `:rf.http/issued` row, say — is never read as an
  override: an issued row also goes missing when it ages out of the ring
  or predates the capture."
  [fx-ev]
  (or (::override fx-ev)
      (when-let [from (tag fx-ev :rf.fx/from)]
        {:from from :to (fx-id-of fx-ev)})))

(defn- common-record
  "Folder shared by every surface — the basic record before
  surface-specific fields are added."
  [surface fx-ev surface-events]
  (let [override      (override-of fx-ev)
        handled       (fx-event->status fx-ev)
        ;; A handled row's `:ok` only ever said "the handler returned". When
        ;; an override replaced that handler it was the REPLACEMENT that
        ;; returned, so the record reads `:overridden` — and cancel and
        ;; failure evidence still win below, exactly as for any record.
        status        (if (and override (= :ok handled)) :overridden handled)
        cancel-cause  (surface-events->cancel-cause surface-events)
        failure       (surface-events->failure surface-events)
        ;; A cancellation is its own closed reply status (Managed-Effects
        ;; §Status taxonomy), not a failure — it no longer folds into
        ;; `:error` (rf2-6ooch).
        terminal-status (cond
                          cancel-cause :cancelled
                          failure      :error
                          :else        status)
        args         (fx-args-of fx-ev)]
    {:surface         surface
     :fx-id           (record-fx-id fx-ev)
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
     ;; Read off the provenance, never off the status: a delegating
     ;; override that really issued and completed reads `:ok`, and is
     ;; still overridden.
     :overridden?     (some? override)
     :override-to     (:to override)
     :override-from   (:from override)}))

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
  have come from nothing else.

  TWO EXCLUSIONS, BOTH FOR CANCELLATION ROWS (rf2-n3sx9). A cancellation
  is not like a failure here, because it terminates a request that was
  ALREADY IN FLIGHT — issued in an EARLIER bundle — so neither of the two
  tests above is sound evidence about it on its own:

  1. A `:request-id-superseded` abort shares this record's `:request-id`,
     so the id match cannot reject it, and it is fired BY this very
     issuance — see `supersede-of-the-attempt-we-replaced?`. Without this
     exclusion every debounced search request after the first reads
     `ERROR · cancel: :request-id-superseded` while being perfectly
     healthy, which is the same class of untruth as the `OK · completed`
     this record was narrowed to escape.

  2. The arithmetic does not extend to a cancellation. It is sound for a
     body-prep failure, which ONLY this bundle's own HTTP effects can
     produce — so counting those effects really does account for every
     candidate. A cancellation can be fired by something that is not an
     HTTP effect at all (an actor destroy walking its in-flight handles, a
     frame teardown, an epoch restore) landing in the same drain, so the
     count says nothing about where the row came from. A record with no
     `:request-id` therefore takes no cancellation.

  What survives is the one cancellation the issuing bundle genuinely
  witnesses about its own attempt: on CLJS an already-aborted external
  `:abort-signal` fires this handle's abort-fn synchronously inside
  `run-attempt!` (`transport-cljs/bind-external-abort!`), reason `:user`
  and this record's own `:request-id`."
  [record-request-id sole-http-fx? ev]
  (let [cancel? (cancellation-row? ev)]
    (and
      ;; Exclusion 1 — a supersede is the PRIOR attempt's, never ours.
      (not (and cancel? (supersede-of-the-attempt-we-replaced? ev)))
      (if (some? record-request-id)
        (= record-request-id (get-in ev [:tags :request-id]))
        ;; Exclusion 2 — the arithmetic stands in for an id only for a row
        ;; this bundle's own HTTP effects could have produced.
        (and (not cancel?) (boolean sole-http-fx?))))))

;; ---- the cross-buffer completion join (rf2-6ooch) ----------------------
;;
;; Every HTTP row after issuance lands OUTSIDE the issuing bundle (see
;; `http-trace-operations`), so the record's outcome can only be read off
;; the WHOLE trace buffer. The framework already names the attempt:
;; `:rf.http/issued` (emitted inside the issuing fx handler, so it rides
;; the issuing bundle's `:other`) carries the attempt-1 work id
;; `[:rf.work/http logical-id issuance 1]`, and every terminal row carries
;; the work id of the attempt that COMPLETED. After a retry the two differ
;; in the fourth (attempt) slot, so the join is on the frame plus the
;; three-element ISSUANCE PREFIX, never on full work-id equality
;; (rf2-ojn0y). An anonymous request's logical id is the tagged
;; `[:rf.http/anonymous event-id]` (rf2-5g0bt), numbered per (frame,
;; event-id) and never reset, so the prefix is exact for it too.
;;
;; A named id IS legitimately reused (`[x 1 1]` again once the first
;; attempt completed and its counter was evicted), so "the latest row by
;; time" would hand an earlier record a later request's outcome. The rule
;; is POSITIONAL instead: the first terminal row whose trace `:id` is
;; greater than the record's own issued row. Trace ids are monotonic
;; (`re-frame.trace/next-event-id`), which is also why nothing here reads
;; `:time` to ORDER rows — two rows routinely share a millisecond.

(def ^:private http-canonical-terminal-ops
  "The canonical completion rows. `:rf.http/aborted` and the failure-kind
  rows are ALSO `:completed` in the reply-envelope op table, but they
  precede the canonical row for the same attempt, and an abort fired by a
  supersession lands at the SUPERSEDED attempt's id inside the
  SUPERSEDER's bundle (rf2-n3sx9) — so the canonical row takes
  precedence whenever both are present."
  #{:rf.http/replied :rf.http/stale-suppressed})

(defn- issuance-prefix
  "The three-element issuance prefix of an HTTP work id, or nil for
  anything that is not one."
  [wid]
  (when (and (vector? wid)
             (= :rf.work/http (first wid))
             (<= 3 (count wid)))
    (subvec wid 0 3)))

(defn- terminal-work-id
  "The work id a terminal row speaks for. A stale-suppression row carries
  TWO — `:rf.reply/carried` (the attempt suppressed) and
  `:rf.reply/current` (the attempt that superseded it) — and it is the
  CARRIED attempt's outcome, so that is the id read; `:current` is never
  read, or the superseder's record would take its predecessor's stale row."
  [ev]
  (let [tags (:tags ev)]
    (if (= :rf.http/stale-suppressed (:operation ev))
      (or (get-in tags [:rf.reply/carried :work/id])
          (re/work-id-of tags))
      (re/work-id-of tags))))

(defn- terminal-row-frame
  "HTTP completion rows stamp the carried frame as `:rf.frame/id` (the
  reply summary); the stale-suppression rows stamp the bare `:frame`."
  [ev]
  (let [tags (:tags ev)]
    (or (:rf.frame/id tags) (:frame tags))))

(defn http-terminal-index
  "Index a trace buffer's HTTP terminal rows ONCE, for every record the
  composite projects on this recompute:
  `{[frame issuance-prefix] -> [rows sorted by :id]}`.

  Pre-filtered to reply-envelope ops (`reply-envelope/phase-of`) at the
  `:completed` or `:stale-suppressed` phase whose work id is an HTTP one
  — so a resource row, a nonsense op, or a row with no frame never enters
  it. Work ids are frame-local (Managed-Effects §Work-id correlation), so
  the frame is part of the key. Pure."
  [trace-buffer]
  (let [grouped (reduce
                  (fn [m ev]
                    (if (contains? #{:completed :stale-suppressed}
                                   (re/phase-of (:operation ev)))
                      (let [prefix (issuance-prefix (terminal-work-id ev))
                            frame  (terminal-row-frame ev)]
                        (if (and prefix (some? frame))
                          (update m [frame prefix] (fnil conj []) ev)
                          m))
                      m))
                  {}
                  (or trace-buffer []))]
    (reduce-kv (fn [m k rows] (assoc m k (vec (sort-by :id rows)))) {} grouped)))

(defn http-reply-index
  "Index the DELIVERED HTTP replies: `{[frame work-id] -> [{:dispatch-id
  :frame :id} ...]}`, sorted by the trace `:id` of each delivery's
  `:rf.event/dispatched` row, over the event-bundles whose run was
  dispatched by the HTTP transport (`:source :http`) and whose event
  vector carries a reply map with an HTTP `:rf.reply/work-id` — the same
  reading of reply maps off event args the `:http-correlation` matcher
  makes (rf2-st7j0).

  A VECTOR per key, because a work id is legitimately reused once its
  attempt has terminated — measured on the producer: an explicit
  `[:rf.http/managed-abort :y]` evicts `:y`'s issuance counter, so a
  same-id re-issue in the same run is `[:rf.work/http :y 1 1]` again, and
  both replies carry it; so does any named id re-issued after it
  completed. `reply-link-for` resolves by position.

  `:source :http` is required, not decorative: an app handler that
  forwards the reply map to another event carries the same work id, and
  that forwarded event is not the delivery. Pure."
  [event-bundles]
  (let [grouped (reduce
                  (fn [m {:keys [frame dispatch-id dispatched event]}]
                    (let [source (or (:source dispatched) (get-in dispatched [:tags :source]))
                          reply  (when (vector? event)
                                   (some (fn [a] (when (and (re/reply-map? a)
                                                            (issuance-prefix (re/work-id-of a)))
                                                   a))
                                         (rest event)))]
                      (if (and (= :http source) reply)
                        (update m [frame (re/work-id-of reply)] (fnil conj [])
                                {:dispatch-id dispatch-id :frame frame :id (:id dispatched)})
                        m)))
                  {}
                  (or event-bundles []))]
    (reduce-kv (fn [m k v] (assoc m k (vec (sort-by :id v)))) {} grouped)))

(defn- reply-link-for
  "The delivery of ONE completion: the first reply bundle under the same
  frame and full work id whose dispatch lands AFTER that completion's
  terminal row and BEFORE the next completion under the same issuance key
  (`until-id`, nil when there is none in the capture).

  Both ends are the producer's own order. The transport emits the
  completion row and then dispatches the reply in one synchronous tail
  (`emit-reply-trace!`, then `dispatch-reply!`, whose `:rf.event/dispatched`
  row the router emits at enqueue), so a completion's delivery lands
  before any later completion under its key can. A delivery past that
  next completion therefore belongs to it, never to this one — which is
  exactly the case of a SILENCED reply (`:on-failure nil`, `:reply-to
  nil`) followed by a delivered one under a reused id: both carry the
  identical full work id, and without the upper bound the silenced
  record would borrow its successor's reply. `{:dispatch-id :frame}`, or
  nil when this completion's delivery is not in the capture — including
  when it delivered nothing."
  [reply-index frame wid terminal-id until-id]
  (when-let [hit (some #(let [id (:id %)]
                          (when (and (number? id)
                                     (> id terminal-id)
                                     (or (nil? until-id) (< id until-id)))
                            %))
                       (get reply-index [frame wid]))]
    (select-keys hit [:dispatch-id :frame])))

(defn- next-completion-id
  "The trace `:id` of the first terminal-index row under `key` after
  `terminal-id` — where the NEXT completion under the same issuance key
  begins — or nil when there is none in the capture. A completion's own
  failure-kind and `:rf.http/aborted` rows precede its canonical row, so
  the first row after a canonical terminal is always a later request's."
  [terminal-index key terminal-id]
  (some #(when (> (:id %) terminal-id) (:id %)) (get terminal-index key)))

(defn http-join-context
  "The per-recompute join context the walker threads to every HTTP
  record: the terminal-row index over the trace buffer and the delivered
  reply index over the event-bundles. Built ONCE per recompute — never
  one buffer scan per record, since the composite recomputes on every
  trace tick."
  [trace-buffer event-bundles]
  {:terminal-index (http-terminal-index trace-buffer)
   :reply-index    (http-reply-index event-bundles)})

(defn- issued-key
  "The join key an `:rf.http/issued` row names: its frame plus the
  issuance prefix of its attempt-1 work id."
  [issued]
  (let [prefix (issuance-prefix (get-in issued [:tags :rf.reply/work-id]))
        frame  (get-in issued [:tags :frame])]
    (when (and prefix (some? frame))
      [frame prefix])))

(defn- terminal-row-for
  "The terminal row for one issuance: among the index rows under `key`
  whose trace `:id` is greater than the issued row's, the FIRST canonical
  row, else the first row of any terminal kind."
  [terminal-index key issued-id]
  (let [after (filterv #(> (:id %) issued-id) (get terminal-index key))]
    (or (some #(when (contains? http-canonical-terminal-ops (:operation %)) %) after)
        (first after))))

(defn- joined-fields
  "Project a joined terminal row onto the record's outcome fields.

  Status is the closed reply status `reply-envelope/work-event-row`
  projects (`:ok :error :cancelled :stale`). ELAPSED is the terminal row's
  trace `:time` minus the issued row's — one clock, stamped on every row
  by `re-frame.trace` — and it includes any retry backoff, which is why
  it is labelled elapsed rather than a round-trip. `:completed-at` is
  epoch milliseconds on a different clock and is never subtracted from
  trace time. RESPONSE is whatever summary the row carries: the elision
  walker's output is the ceiling, and under `:sensitive?` it is the
  `:rf/redacted` sentinel. `until-id` bounds the reply link to this
  completion's own delivery — see `reply-link-for`."
  [issued terminal reply-index until-id]
  (let [tags    (:tags terminal)
        row     (re/work-event-row terminal)
        status  (or (:status row)
                    (if (= :rf.http/aborted (:operation terminal)) :cancelled :error))
        wid     (terminal-work-id terminal)
        t0      (:time issued)
        t1      (:time terminal)
        elapsed (when (and (number? t0) (number? t1) (<= t0 t1)) (- t1 t0))
        attempt (or (:attempt tags)
                    (when (and (vector? wid) (< 3 (count wid))) (nth wid 3)))
        error   (:error tags)
        http-code (case status
                  :ok    (get-in tags [:meta :status])
                  :error (when (map? error) (:status error))
                  nil)]
    {:status       status
     :completion   :joined
     :terminal-op  (:operation terminal)
     :duration-ms  elapsed
     :wire         (when elapsed
                     {:phases   [[:issued 0] [:elapsed elapsed]]
                      :total-ms elapsed})
     :attempts     attempt
     :http-status  (when (number? http-code) http-code)
     :res          (case status
                     :ok                 (:value tags)
                     (:error :cancelled) error
                     nil)
     :failure      (when (and (= :error status) (map? error))
                     {:kind    (:kind error)
                      :tags    error
                      :message (or (:message error) (:reason error))})
     :cancel-cause (case status
                     :cancelled (or (:rf.reply/cancel-reason tags)
                                    (when (map? error) (:reason error)))
                     :stale     (:rf.reply/stale-reason tags)
                     nil)
     ;; A stale outcome is never delivered. Any other status MAY have been
     ;; — a silenced branch (`:on-failure nil`, `:reply-to nil`) completes
     ;; and delivers nothing — so the link is an observed delivery in this
     ;; completion's own window, never inferred from the status.
     :reply-link   (when (contains? #{:ok :error :cancelled} status)
                     (reply-link-for reply-index (terminal-row-frame terminal) wid
                                     (:id terminal) until-id))}))

(defn http-adapter
  "HTTP surface adapter — the ISSUING bundle says `:issued`; the WHOLE
  trace buffer says how the request ended.

  What one drain can see about a managed HTTP request is that the fx
  handler returned and the transport was entered: `:rf.fx/handled` is
  emitted AFTER the handler returns true, so the row means the request
  went out, and nothing after that point reaches this bundle (see
  `http-trace-operations`). Read from the bundle alone, the record says
  `:issued` and leaves `:wire` / `:res` / `:duration-ms` / `:http-status`
  nil.

  `:issued` is a NEW status rather than `:ok` relabelled, so
  `(= status :ok)` can never again mean 'completed' by accident. It is
  set here, on the HTTP surface, rather than in `fx-event->status`: the
  other four surfaces DO get their end events in-bundle, so `:ok` keeps
  its meaning for them.

  THE JOIN (rf2-6ooch). When the walker pairs this record with its own
  `:rf.http/issued` row (`opts` `:issued`) and threads a
  `:terminal-index`, the record takes its outcome from the first terminal
  row after that issued row under the same frame and issuance prefix —
  see `joined-fields`. `:completion` then says which case it is:
  `:joined`; `:none` when the issued row is present and no terminal row
  is in the capture (the buffer cannot tell pending from aged-out, so the
  panel never says 'in flight'); nil when there is no issued row at all
  (a capture from before the row existed, a skipped effect, or an issued
  row aged out of the ring), which stays an unattributed `:issued`.

  AN OVERRIDDEN RECORD (rf2-3x7nj.23.5). An override replaces the fx
  HANDLER; it does not say whether the replacement went to the network.
  So the status reports what the capture EVIDENCES about the replacement:
  with its own issued row paired it really entered the transport, starts
  from `:issued` and joins like any other record; with none it stays
  `:overridden` — never `:issued`, which claims a request went out. The
  converse never holds: a missing issued row alone is not an override.

  In-bundle attribution. With an issued row, a same-bundle HTTP row
  belongs to this record exactly when it comes AFTER the issued row and
  its work id shares the record's issuance prefix. Position is the half
  that separates an explicit `[:rf.http/managed-abort :x]` beside a
  same-id re-issue from an already-aborted `:abort-signal` firing at THIS
  attempt — two cases the request-id alone could not tell apart
  (rf2-n3sx9's residue) — because the explicit abort evicts `:x`'s
  issuance counter and the re-issue reuses the SAME work id, so the ids
  agree and only the order differs: that abort fires before the re-issue's
  issued row, a same-attempt abort after it. The prefix is the half that
  rejects the abort a supersession fires, which lands after the issued
  row but names the superseded issuance. Without an issued row,
  attribution falls back to `http-row-for-this-record?`.

  `opts` also carries `:sole-http-fx?` — whether this is the bundle's
  only HTTP effect, which the walker knows and the adapter cannot. The
  2-arity assumes it is, and joins nothing."
  ([fx-ev event-bundle-other]
   (http-adapter fx-ev event-bundle-other {:sole-http-fx? true}))
  ([fx-ev event-bundle-other {:keys [sole-http-fx? issued terminal-index reply-index]
                              :or   {sole-http-fx? true}}]
   (let [args           (fx-args-of fx-ev)
         request-id     (when (map? args) (:request-id args))
         key            (when issued (issued-key issued))
         own-row?       (if key
                          (fn [ev]
                            (let [tags (:tags ev)
                                  id   (:id ev)]
                              (and (number? id)
                                   (> id (:id issued))
                                   (= (second key)
                                      (issuance-prefix (or (:work/id tags)
                                                           (:rf.reply/work-id tags)))))))
                          #(http-row-for-this-record? request-id sole-http-fx? %))
         surface-events (filterv own-row? (surface-events-for event-bundle-other :http))
         request        (when (map? args) (:request args))
         rec            (common-record :http fx-ev surface-events)
         base           (assoc rec
                               :req         request
                               :wire        nil
                               :res         nil
                               :phase       nil
                               :duration-ms nil
                               :http-status nil
                               :attempts    nil
                               :reply-link  nil
                               :completion  nil
                               :status      (cond
                                              (= :ok (:status rec)) :issued
                                              (and issued (= :overridden (:status rec))) :issued
                                              :else (:status rec)))]
     (if (and key (some? terminal-index))
       (if-let [terminal (terminal-row-for terminal-index key (:id issued))]
         (merge base (joined-fields issued terminal reply-index
                                    (next-completion-id terminal-index key (:id terminal))))
         (assoc base :completion :none))
       base))))

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

(defn- pair-issued-rows
  "Pair each HTTP effect row with the bundle's `:rf.http/issued` row it
  caused: `{<fx-row :id> -> <issued row>}`.

  Paired by POSITION over the monotonic trace `:id`, never by `:time`: the
  issued row is emitted inside the fx handler and `re-frame.fx` emits
  `:rf.fx/handled` after the handler returns, so an effect's issued row
  lies strictly between the PREVIOUS HTTP effect row and its own. Reading
  the window rather than zipping ordinals keeps an HTTP effect that
  issued nothing — an overridden or skipped effect, a
  `:rf.http/managed-abort`, a capture from before the issued row existed —
  from shifting every later pairing by one. An effect with no issued row
  in its window pairs to nothing and stays an unjoined `:issued`."
  [http-fx other]
  (let [issued (sort-by :id (filterv #(= :rf.http/issued (:operation %)) (or other [])))
        fx     (sort-by :id http-fx)]
    (loop [fx fx, lo nil, acc {}]
      (if-let [f (first fx)]
        (let [hi  (:id f)
              hit (when (number? hi)
                    (last (filter #(let [i (:id %)]
                                     (and (number? i) (< i hi) (or (nil? lo) (> i lo))))
                                  issued)))]
          (recur (rest fx) hi (if hit (assoc acc hi hit) acc)))
        acc))))

(defn- pair-override-rows
  "Pair each FUNCTION-overridden `:rf.fx/handled` row with the
  `:rf.fx/override-applied` row that replaced its handler:
  `{<handled row :id> -> <override-applied row>}`.

  `re-frame.fx` emits override-applied immediately before the override
  function fires and `:rf.fx/handled` after it returns, so the pair lies
  in one window — after the previous non-override effect row, before the
  handled row — read by POSITION over the monotonic trace `:id`, never by
  `:time` (the discipline `pair-issued-rows` uses). The override row's
  `:rf.fx/from` must name the handled row's `:rf.fx/id`. A keyword
  redirect needs no pairing: its handled row carries `:rf.fx/from`."
  [effects]
  (loop [rows (sort-by :id (filterv #(number? (:id %)) (or effects [])))
         pending []
         acc     {}]
    (if-let [r (first rows)]
      (if (= :rf.fx/override-applied (:operation r))
        (recur (rest rows) (conj pending r) acc)
        (let [hit (when (and (= :rf.fx/handled (:operation r))
                             (nil? (tag r :rf.fx/from)))
                    (last (filter #(= (fx-id-of r) (tag % :rf.fx/from)) pending)))]
          (recur (rest rows) [] (if hit (assoc acc (:id r) hit) acc))))
      acc)))

(defn event-bundle->managed-fx-records
  "Walk an event-bundle record (per `re-frame.trace.projection/group-by-event`)
  and project one managed-fx record per `:rf.fx/handled` row whose id (per
  `record-fx-id`, the id the handler emitted) classifies as a managed-fx
  surface. An `:rf.fx/override-applied` row is PROVENANCE for such a
  record — it marks it overridden — and never a record of its own.

  Pure fn. Returns a vector of records in event-bundle order. Each record
  carries the surface-specific projection of `req` / `wire` / `res` /
  `handler` / `status` / `phase` / `correlation-id` / `cancel-cause`.

  When `join-context` is supplied (`http-join-context` — built ONCE per
  recompute over the whole trace buffer and the event-bundles) each HTTP
  record is paired with its own `:rf.http/issued` row
  (`pair-issued-rows`) and takes its outcome from the cross-buffer join;
  without it an HTTP record says only `:issued` (rf2-6ooch).

  When `paths-by-dispatch-id` is supplied the record's `:paths-touched`
  is filled with the app-db diff paths that changed during this
  event-bundle. NO CALLER SUPPLIES IT TODAY — the composite sub in
  `panels/managed_fx_subs` passes nil — so `:paths-touched` is
  normally nil, meaning UNTRACKED rather than measured-and-empty. The two
  must stay distinguishable: the panel used to read the empty vector as
  evidence and warn, on every single successful record, that the author's
  handler had failed to write app-db."
  ([event-bundle]
   (event-bundle->managed-fx-records event-bundle nil nil))
  ([event-bundle paths-by-dispatch-id]
   (event-bundle->managed-fx-records event-bundle paths-by-dispatch-id nil))
  ([{:keys [effects other dispatch-id] :as _event-bundle} paths-by-dispatch-id join-context]
   (let [fn-overrides  (pair-override-rows effects)
         fx-events     (into []
                             (comp (filter managed-fx-effect?)
                                   (map (fn [ev]
                                          (if-let [o (get fn-overrides (:id ev))]
                                            (assoc ev ::override {:from (fx-id-of ev)
                                                                  :to   (tag o :rf.fx/to)})
                                            ev))))
                             (or effects []))
         path-touched  (when paths-by-dispatch-id
                         (vec (get paths-by-dispatch-id dispatch-id [])))
         http-fx       (filterv #(= :http (classify-fx-id (record-fx-id %))) fx-events)
         ;; Whether an HTTP failure row in this bundle can be attributed
         ;; to a record that carries no `:request-id` is a fact about the
         ;; BUNDLE, so only this walker can answer it.
         http-fx-count (count http-fx)
         issued->fx    (pair-issued-rows http-fx other)]
     (vec
       (for [fx-ev fx-events
             :let  [surface (classify-fx-id (record-fx-id fx-ev))
                    adapter (get surface->adapter surface)]
             :when adapter]
         (-> (if (= surface :http)
               (http-adapter fx-ev other
                             (merge {:sole-http-fx? (= 1 http-fx-count)
                                     :issued        (get issued->fx (:id fx-ev))}
                                    (select-keys join-context
                                                 [:terminal-index :reply-index])))
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
    :cancelled  "CANCELLED"
    :stale      "STALE"
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
