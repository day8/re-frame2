(ns day8.re-frame2-xray.panels.event.event-status-colour
  "Event-lifecycle status colour — the canonical TanStack-style pure fn
  that maps an event-bundle's lifecycle state to a single palette token
  (rf2-b76v4, parent rf2-vtd5z).

  ## Why this lives in `panels/event/`

  TanStack Query Devtools ships a single
  `getQueryStatusColor(fetchStatus, observerCount, isStale)` → colour-key
  pure fn. Sidebar dots, row backgrounds, badges, and tab counters all
  consume the SAME fn so the devtool carries ONE lifecycle vocabulary
  end-to-end. Pre-rf2-b76v4 Xray rolled its own colour decision at every
  consumer site:

    - `shell/event-row` switched bg/border on `focused?` + `ungrouped?`
      with no notion of error/warning/in-flight at the row level.
    - `panels/event-detail/outcome-colour` mapped `:ok` / `:error` /
      `:warning` onto green / red / yellow at the Event header dot.
    - `panels/trace` had no per-row event-bundle-status surface — every
      trace row in the focused event-bundle rendered with the same neutral
      chrome regardless of the event-bundle's terminal state.

  This ns is the new central map. The hex-resolution wrapper
  `event-status-colour` is the one fn three call sites consume; the
  pure `classify-status` + `status->token` data layer underneath stays
  JVM-portable so the lifecycle vocabulary is testable from
  `clojure -M:test` without a CLJS runtime.

  ## Lifecycle vocabulary

  Five canonical states the devtool surfaces (mirroring the TanStack
  semantic anchors; the colour anchors are chosen from the existing
  Xray palette so no new tokens are introduced):

      Status            Token            Hex (dark)  When
      ----------------  ---------------  ----------  -----------------------
      :in-flight        :accent          #539bf5     event-bundle still building
                                                     (LIVE head, not yet
                                                     settled). The single
                                                     accent (GitHub blue) —
                                                     the LIVE head IS the
                                                     current-epoch accent
                                                     (§007).
      :settled-success  :green           #3fb950     handler ran, no
                                                     exception, no warnings.
      :settled-error    :red             #F87171     handler threw, or an
                                                     :rf.error/* trace
                                                     landed in the event-bundle.
      :paused-by-tool   :info            #79c0ff     spine paused
                                                     (LIVE+paused) — e.g.
                                                     a tool has claimed
                                                     the buffer. TanStack
                                                     uses purple for
                                                     paused; the in-flight
                                                     head owns the primary
                                                     accent, so paused picks
                                                     the fixed cool blue
                                                     `:info` as a distinct
                                                     peer. Magenta is
                                                     reserved for the `▥`
                                                     whole-redacted row
                                                     marker.
      :stale            :yellow          #FBBF24     event-bundle replayed via
                                                     time-travel / RETRO
                                                     mode. The TanStack
                                                     analog is the
                                                     `isStale` flag; in
                                                     Xray, an event-bundle in
                                                     RETRO mode is the
                                                     state being inspected
                                                     out of LIVE order.

  ## Input shape

  The `event-status-colour` fn takes a map of the event-bundle's pertinent
  lifecycle slots. Every field is optional; missing fields are treated
  as falsey / unknown. Callers project off whatever they have:

      {:outcome      :ok | :error | :warning | nil
                                  ;; from `event-detail/event-bundle-outcome`
       :focused?     <bool>       ;; spine focus is on this event-bundle
       :paused?      <bool>       ;; spine :paused? slot
       :mode         :live | :retro
       :in-flight?   <bool>       ;; event-bundle dispatched but no terminal
                                  ;; trace yet (rare in Xray today —
                                  ;; event-bundles are buffer-projected after
                                  ;; settle — but the slot is reserved
                                  ;; for the live in-progress surface a
                                  ;; follow-on bead will wire up)
       :stale?       <bool>}      ;; explicit replayed-from-history flag

  ## Mapping precedence

  The classifier resolves in this order — first match wins:

      :settled-error    when (= :error outcome)
      :stale            when stale? OR (= :retro mode)
      :in-flight        when in-flight? AND no terminal outcome
      :paused-by-tool   when paused?
      :settled-success  when (= :ok outcome) OR (= :warning outcome)
      :in-flight        fallback (no signals — treat as live)

  Notes:

    - `:warning` outcomes resolve to `:settled-success`. The warning
      glyph (`⚠`) ALREADY carries the warning signal at the Event
      header glyph slot; the status colour reads the row as 'settled'
      rather than re-amplifying the warning. (`outcome-colour` in
      event-detail still uses yellow for the glyph itself; this fn
      drives the broader row/header status, which the user reads
      AS WELL AS the glyph.)
    - `:error` always wins over `:stale` so a RETRO-replayed errored
      event-bundle still surfaces as red.
    - `:focused?` is captured by the caller's existing focus chrome
      (bg-active, cyan border in `event-row`); the status fn does NOT
      override the focus highlight — both can coexist in the row's
      style map.

  ## Pure data, JVM-portable

  Everything here is pure data → pure data. `.cljc` so the JVM test
  target exercises every state without a CLJS runtime. The hex
  resolution happens through `theme/tokens` which is also JVM-loadable
  pure data."
  {:no-doc true}
  (:require [day8.re-frame2-xray.theme.tokens :as tokens]))

;; ---- vocabulary ---------------------------------------------------------

(def statuses
  "Render-order vector of the five lifecycle statuses. Useful for
  enumerating chips / legends / tests."
  [:in-flight :settled-success :settled-error :paused-by-tool :stale])

(def status->token
  "Pure semantic map from lifecycle status keyword to token keyword.
  The hex resolution happens via `event-status-colour`, which looks
  up `theme/tokens`. Splitting the semantic mapping from the hex
  lookup keeps the map pure-data + tokens consolidated — mirrors the
  `tier->token` / `op-type->token` shape used elsewhere in the
  codebase."
  {:in-flight       :accent
   :settled-success :green
   :settled-error   :red
   :paused-by-tool  :info            ; fixed cool blue — distinct from :in-flight (accent)
   :stale           :yellow})

;; ---- classification ------------------------------------------------------

(defn classify-status
  "Pure classifier: map a per-event-bundle lifecycle-state input map onto a
  single status keyword. Returns one of `:in-flight` /
  `:settled-success` / `:settled-error` / `:paused-by-tool` / `:stale`.

  See the ns docstring for the precedence contract. Pure data →
  keyword; JVM-runnable."
  [{:keys [outcome paused? mode in-flight? stale?]
    :or   {outcome     nil
           paused?     false
           mode        nil
           in-flight?  false
           stale?      false}}]
  (cond
    (= :error outcome)                         :settled-error
    (or stale? (= :retro mode))                :stale
    (and in-flight? (nil? outcome))            :in-flight
    paused?                                    :paused-by-tool
    (or (= :ok outcome) (= :warning outcome))  :settled-success
    :else                                      :in-flight))

;; ---- public colour resolver ---------------------------------------------

(defn event-status-token
  "Resolve the event-bundle's lifecycle state to a token KEYWORD (not the
  hex). Useful for callers that want to colour-tag a span without
  inlining the hex (e.g. data-testid suffixes, style-map composition
  through `theme/tokens`).

  Pure data → keyword; JVM-runnable."
  [state]
  (get status->token (classify-status state) :accent))

(defn event-status-colour
  "Resolve the event-bundle's lifecycle state to a hex colour string,
  routing through `status->token` + `theme/tokens` so the palette
  has exactly one source of truth.

  This is the ONE fn three call sites consume:

    - `shell/event-row`     — L2 row left-border accent + dim bg tint
    - `event-detail/Panel`  — Event L4 header status dot + label
    - `panels/trace`        — per-row left-edge stripe when the row's
                              parent dispatch-id is in the focused
                              event-bundle

  Pure data → string; JVM-runnable."
  [state]
  (get tokens/tokens (event-status-token state) (:accent tokens/tokens)))

;; ---- event-bundle-outcome (relocated from event_detail.cljs · rf2-5gl5r) ----
;;
;; Pure-data classifier: project an event-bundle's `:other` bucket onto the
;; outcome triad `:ok | :error | :warning`. Originally a private helper
;; in the retired event-detail panel; the trace panel's
;; `event-bundle-status-bar` reads it via `event-bundle->state` (below), and the
;; existing JVM test corpus targets it directly. Lifted into the
;; event-status-colour ns alongside `event-bundle->state` because the two
;; are co-consumed (the typical call shape is `(event-bundle->state event-bundle
;; focus event-bundle-outcome)`) — keeping them in the same place removes
;; the dependency-injection indirection the prior cross-ns split forced.

(defn- error-trace?
  "True iff `ev` is an error trace — classified by the universal
  severity axis (`:op-type :error`, per Spec 009) with a namespace
  fallback for any `:rf.error/*` operation. Mirrors the namespace-based
  idiom in `issues-ribbon-helpers/op-type->severity` rather than
  enumerating individual ops the substrate may add over time."
  [{:keys [op-type operation] :as _ev}]
  (or (= :error op-type)
      (and (keyword? operation) (= "rf.error" (namespace operation)))))

(defn- warning-trace?
  "True iff `ev` is a warning trace — `:op-type :warning` (per Spec 009)
  with an `:rf.warning/*` namespace fallback. Severity-driven, not
  op-enumerated."
  [{:keys [op-type operation] :as _ev}]
  (or (= :warning op-type)
      (and (keyword? operation) (= "rf.warning" (namespace operation)))))

(defn- has-error?
  "True iff the event-bundle carries ANY error trace (severity `:error` /
  `:rf.error/*`) in its `:other` bucket. Pure predicate."
  [{:keys [other]}]
  (boolean (some error-trace? (or other []))))

(defn- has-warning?
  "True iff the event-bundle carries any non-fatal warning that should pivot
  the outcome glyph to ⚠ (amber). Pure predicate."
  [{:keys [other]}]
  (boolean (some warning-trace? (or other []))))

(defn event-bundle-outcome
  "Project an event-bundle record into an outcome-summary map:

      {:event-id    <kw>           ;; first element of :event vec
       :glyph       \"✓\" | \"✗\" | \"⚠\"
       :outcome     :ok | :error | :warning
       :duration-ms <num-or-nil>
       :dispatch-id <int>
       :ssr?        <bool>}        ;; true when this was an SSR-hydration event-bundle

  Pure data → data. JVM-portable. Relocated from the retired
  event-detail panel (rf2-5gl5r); the trace panel's event-bundle-status-bar
  is the surviving consumer alongside the JVM unit-test corpus."
  [{:keys [event handler dispatch-id] :as event-bundle}]
  (let [event-id    (when (vector? event) (first event))
        duration-ms (get-in handler [:tags :duration-ms])
        ssr?        (or (= :rf.ssr/hydrated event-id)
                        (= :rf.ssr/hydration-complete event-id))
        [outcome glyph] (cond
                          (has-error? event-bundle)   [:error   "✗"]
                          (has-warning? event-bundle) [:warning "⚠"]
                          :else                  [:ok      "✓"])]
    {:event-id    event-id
     :glyph       glyph
     :outcome     outcome
     :duration-ms duration-ms
     :dispatch-id dispatch-id
     :ssr?        ssr?}))

;; ---- convenience: event-bundle → state map -----------------------------------

(defn event-bundle->state
  "Project an event-bundle record + focus map onto the lifecycle-state input
  map `event-status-colour` consumes. Callers that already have the
  event-bundle in hand can call this once per row rather than threading the
  pieces by hand.

  - `event-bundle`       — the projected event-bundle record (`:errors`, `:other`,
                      `:event`, `:handler`)
  - `focus`         — the spine focus map (`:dispatch-id`, `:mode`,
                      `:paused?`); pass nil for callers that don't have
                      it (e.g. JVM unit tests building the state map by
                      hand)
  - `outcome-fn`    — a fn `(event-bundle) -> :ok|:error|:warning`. Default
                      `event-bundle-outcome` (above). The injection seam
                      survives from the pre-rf2-5gl5r era where this
                      ns deliberately avoided a circular dep on
                      `panels/event-detail`; with that panel retired,
                      the default behaviour is the common path.

  Pure data → map; JVM-runnable."
  ([event-bundle focus]
   (event-bundle->state event-bundle focus event-bundle-outcome))
  ([event-bundle focus outcome-fn]
  (let [outcome   (some-> event-bundle outcome-fn :outcome)
        ;; rf2-bz7flo — frame-strict focused? check. Dispatch ids are unique
        ;; only WITHIN a frame, so when a multi-frame caller renders two
        ;; same-id event-bundles from different frames, a dispatch-id-only match
        ;; would mark BOTH focused/paused/stale. When both the event-bundle and
        ;; focus carry a `:frame`, require them to agree; degrade to a plain
        ;; dispatch-id match when either is frameless (single-frame focus,
        ;; JVM unit tests building the event-bundle by hand).
        event-bundle-frame (:frame event-bundle)
        focus-frame   (:frame focus)
        focused?  (boolean
                    (and event-bundle focus
                         (= (:dispatch-id event-bundle) (:dispatch-id focus))
                         (or (nil? event-bundle-frame)
                             (nil? focus-frame)
                             (= event-bundle-frame focus-frame))))
        stale?    (boolean (and focused? (= :retro (:mode focus))))]
    {:outcome    outcome
     :focused?   focused?
     :paused?    (boolean (and focused? (:paused? focus)))
     :mode       (when focused? (:mode focus))
     :in-flight? false
     :stale?     stale?})))
