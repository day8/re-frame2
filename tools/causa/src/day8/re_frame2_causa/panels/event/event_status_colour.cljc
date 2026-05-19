(ns day8.re-frame2-causa.panels.event.event-status-colour
  "Event-lifecycle status colour — the canonical TanStack-style pure fn
  that maps a cascade's lifecycle state to a single palette token
  (rf2-b76v4, parent rf2-vtd5z).

  ## Why this lives in `panels/event/`

  TanStack Query Devtools ships a single
  `getQueryStatusColor(fetchStatus, observerCount, isStale)` → colour-key
  pure fn. Sidebar dots, row backgrounds, badges, and tab counters all
  consume the SAME fn so the devtool carries ONE lifecycle vocabulary
  end-to-end. Pre-rf2-b76v4 Causa rolled its own colour decision at every
  consumer site:

    - `shell/event-row` switched bg/border on `focused?` + `ungrouped?`
      with no notion of error/warning/in-flight at the row level.
    - `panels/event-detail/outcome-colour` mapped `:ok` / `:error` /
      `:warning` onto green / red / yellow at the Event header dot.
    - `panels/trace` had no per-row cascade-status surface — every
      trace row in the focused cascade rendered with the same neutral
      chrome regardless of the cascade's terminal state.

  This ns is the new central map. The hex-resolution wrapper
  `event-status-colour` is the one fn three call sites consume; the
  pure `classify-status` + `status->token` data layer underneath stays
  JVM-portable so the lifecycle vocabulary is testable from
  `clojure -M:test` without a CLJS runtime.

  ## Lifecycle vocabulary

  Five canonical states the devtool surfaces (mirroring the TanStack
  semantic anchors; the colour anchors are chosen from the existing
  Causa palette so no new tokens are introduced):

      Status            Token            Hex (dark)  When
      ----------------  ---------------  ----------  -----------------------
      :in-flight        :accent-violet   #7C5CFF     cascade still building
                                                     (LIVE head, not yet
                                                     settled). Mirrors
                                                     Causa's existing
                                                     causal-chain accent.
      :settled-success  :green           #4ADE80     handler ran, no
                                                     exception, no warnings.
      :settled-error    :red             #F87171     handler threw, or an
                                                     :rf.error/* trace
                                                     landed in the cascade.
      :paused-by-tool   :cyan            #43C3D0     spine paused
                                                     (LIVE+paused) — e.g.
                                                     a tool has claimed
                                                     the buffer. TanStack
                                                     uses purple for
                                                     paused; we already
                                                     own violet for the
                                                     causal chain so we
                                                     pick cyan as the
                                                     peer accent. Magenta
                                                     is reserved for the
                                                     `▥` whole-redacted
                                                     row marker.
      :stale            :yellow          #FBBF24     cascade replayed via
                                                     time-travel / RETRO
                                                     mode. The TanStack
                                                     analog is the
                                                     `isStale` flag; in
                                                     Causa, a cascade in
                                                     RETRO mode is the
                                                     state being inspected
                                                     out of LIVE order.

  ## Input shape

  The `event-status-colour` fn takes a map of the cascade's pertinent
  lifecycle slots. Every field is optional; missing fields are treated
  as falsey / unknown. Callers project off whatever they have:

      {:outcome      :ok | :error | :warning | nil
                                  ;; from `event-detail/cascade-outcome`
       :focused?     <bool>       ;; spine focus is on this cascade
       :paused?      <bool>       ;; spine :paused? slot
       :mode         :live | :retro
       :in-flight?   <bool>       ;; cascade dispatched but no terminal
                                  ;; trace yet (rare in Causa today —
                                  ;; cascades are buffer-projected after
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
      cascade still surfaces as red.
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
  (:require [day8.re-frame2-causa.theme.tokens :as tokens]))

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
  {:in-flight       :accent-violet
   :settled-success :green
   :settled-error   :red
   :paused-by-tool  :cyan
   :stale           :yellow})

;; ---- classification ------------------------------------------------------

(defn classify-status
  "Pure classifier: map a per-cascade lifecycle-state input map onto a
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
  "Resolve the cascade's lifecycle state to a token KEYWORD (not the
  hex). Useful for callers that want to colour-tag a span without
  inlining the hex (e.g. data-testid suffixes, style-map composition
  through `theme/tokens`).

  Pure data → keyword; JVM-runnable."
  [state]
  (get status->token (classify-status state) :accent-violet))

(defn event-status-colour
  "Resolve the cascade's lifecycle state to a hex colour string,
  routing through `status->token` + `theme/tokens` so the palette
  has exactly one source of truth.

  This is the ONE fn three call sites consume:

    - `shell/event-row`     — L2 row left-border accent + dim bg tint
    - `event-detail/Panel`  — Event L4 header status dot + label
    - `panels/trace`        — per-row left-edge stripe when the row's
                              parent dispatch-id is in the focused
                              cascade

  Pure data → string; JVM-runnable."
  [state]
  (get tokens/tokens (event-status-token state) (:accent-violet tokens/tokens)))

;; ---- convenience: cascade → state map -----------------------------------

(defn cascade->state
  "Project a cascade record + focus map onto the lifecycle-state input
  map `event-status-colour` consumes. Callers that already have the
  cascade in hand can call this once per row rather than threading the
  pieces by hand.

  - `cascade`       — the projected cascade record (`:errors`, `:other`,
                      `:event`, `:handler`)
  - `focus`         — the spine focus map (`:dispatch-id`, `:mode`,
                      `:paused?`); pass nil for callers that don't have
                      it (e.g. JVM unit tests building the state map by
                      hand)
  - `outcome-fn`    — a fn `(cascade) -> :ok|:error|:warning`. Passed
                      in by the caller (typically
                      `event-detail/cascade-outcome` composed with
                      `:outcome` selection) so this ns does NOT pull a
                      circular dep on `panels/event-detail`.

  Pure data → map; JVM-runnable."
  [cascade focus outcome-fn]
  (let [outcome   (some-> cascade outcome-fn :outcome)
        focused?  (boolean
                    (and cascade focus
                         (= (:dispatch-id cascade) (:dispatch-id focus))))
        stale?    (boolean (and focused? (= :retro (:mode focus))))]
    {:outcome    outcome
     :focused?   focused?
     :paused?    (boolean (and focused? (:paused? focus)))
     :mode       (when focused? (:mode focus))
     :in-flight? false
     :stale?     stale?}))
