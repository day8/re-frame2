(ns day8.re-frame2-causa.panels.effects-helpers
  "Pure-data helpers for Causa's Effects panel (Phase 5, rf2-ts41u,
  parent rf2-5aw5v).

  ## Why a separate `.cljc` ns

  Same dual-target pattern every other panel uses (flows, subscriptions,
  causality-graph, time-travel, app-db-diff, ...). The panel view in
  `effects.cljs` builds the hiccup; the *logic* — folding the
  registered-fx set + the trace-buffer's `:op-type :fx` events into
  per-fx rows with a stub indicator and an outcome summary — is pure
  data → data and runs under the JVM unit-test target
  (`clojure -M:test`).

  ## The four-outcome taxonomy

  Each fx row's status is derived from the latest `:rf.fx/*` event for
  that fx-id (per Spec 009 §Error event catalogue):

  | Outcome      | Glyph | Colour token   | Source operation                |
  |--------------|-------|----------------|----------------------------------|
  | `:ok`        | `●`   | `:green`       | `:rf.fx/handled`                |
  | `:overridden`| `◑`   | `:accent-violet`| `:rf.fx/override-applied`      |
  | `:skipped`   | `○`   | `:text-tertiary`| `:rf.fx/skipped-on-platform`  |
  | `:error`     | `▲`   | `:red`          | `:rf.error/fx-handler-exception` / `:rf.error/no-such-fx` |

  `:never-invoked` (no prior event) renders as the empty/idle state —
  the `:text-tertiary` `○` — so a freshly-registered fx that has never
  fired is legible at a glance.

  ## Stub indicator

  Per the bead's contract, a per-fx 'stub indicator' surfaces when an
  fx-override is active for that id. Spec 002 §Per-frame and per-call
  overrides documents `:fx-overrides` as the canonical surface; when
  the runtime emits `:rf.fx/override-applied` for a given fx-id, that
  fx is being stubbed (replaced) at the call site. The most recent
  `:rf.fx/override-applied` event flags the row's `:stubbed?` boolean.

  ## What this doesn't do

  Pure data. No subscription, no atom, no `js/` interop — the same
  fn runs under CLJ and CLJS. The CLJS-only surfaces (`rf/registrations`
  on a populated registrar) are read by the composite sub in
  `registry.cljs`; the result is handed to this ns as a plain map."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa.panels.common-helpers :as common]))

;; ---- design tokens (mirrors view tokens — kept here for tests) ----------
;;
;; The view consumes these via this ns so the outcome → token mapping
;; has one source of truth. The pure-data side stays JVM-portable.

(def outcome->token
  "Outcome → colour-token mapping. The view resolves the token to a hex
  via its private `tokens` map. Source of truth for the panel + any
  follow-on Causa surface that wants to render an fx outcome badge."
  {:ok            :green
   :overridden    :accent-violet
   :skipped       :text-tertiary
   :error         :red
   :never-invoked :text-tertiary})

(def outcome->glyph
  "Outcome → shape-glyph mapping. The glyph carries the taxonomy
  without any colour signal so the panel is legible to colour-blind
  users — same 'colour is never alone' discipline the Subscriptions /
  Flows panels ship."
  {:ok            "●"
   :overridden    "◑"
   :skipped       "○"
   :error         "▲"
   :never-invoked "○"})

(def outcome->tooltip
  "Outcome → hover-tooltip mapping. Hover over a badge surfaces this
  string."
  {:ok            "Handled"
   :overridden    "Override applied"
   :skipped       "Skipped on platform"
   :error         "Errored"
   :never-invoked "Never invoked"})

(def outcomes
  "Canonical row-ordering — errors first, then overridden (a stubbed
  fx in flight needs visibility), then skipped, then ok, then
  never-invoked. The view sorts rows by this order so the most
  actionable rows surface at the top."
  [:error :overridden :skipped :ok :never-invoked])

;; ---- helpers ------------------------------------------------------------

(defn fx-trace-event?
  "True when `ev` is an fx-related trace event the Effects panel
  consumes. Includes the `:op-type :fx` success / lifecycle stream
  (`:rf.fx/handled`, `:rf.fx/override-applied`) AND the two `:op-type
  :error` categories the fx layer emits (`:rf.error/fx-handler-
  exception`, `:rf.error/no-such-fx`) AND the platform-skip warning
  (`:rf.fx/skipped-on-platform`). Pure predicate — used by the
  composite sub to filter the Causa trace buffer down to the fx
  stream."
  [{:keys [op-type operation] :as _ev}]
  (or (= op-type :fx)
      (and (= op-type :error)
           (contains? #{:rf.error/fx-handler-exception
                        :rf.error/no-such-fx}
                      operation))
      (= operation :rf.fx/skipped-on-platform)))

(defn filter-fx-events
  "Return only the fx-related events from a trace-event vector. Oldest
  first (matches the buffer's natural order). Pure fn."
  [events]
  (filterv fx-trace-event? (or events [])))

;; Re-export `tag-of` under the local name `tag` so existing call sites
;; (`(tag ev :fx-id)`, etc.) keep working without churn. Body lives in
;; `common-helpers`.
(def ^:private tag common/tag-of)

(defn- event-fx-id
  "The fx-id this event refers to. `:rf.fx/handled` and friends carry
  `:fx-id` directly under `:tags`; the helper falls back on a flat
  shape. `:rf.fx/override-applied` carries `:from` (the original
  fx-id being overridden) — that's the id we want for attribution."
  [ev]
  (or (tag ev :fx-id)
      (tag ev :from)))

(defn- event-dispatch-id [ev] (tag ev :dispatch-id))

(defn latest-event-per-fx
  "Index `fx-events` (oldest first) into `{fx-id <latest-event>}`.
  Pure fn — the linear scan keeps the latest event seen per fx-id as
  the buffer iterates oldest→newest. Tools render the row's current
  outcome from the latest event's `:operation`."
  [fx-events]
  (reduce (fn [acc ev]
            (if-let [fid (event-fx-id ev)]
              (assoc acc fid ev)
              acc))
          {}
          (or fx-events [])))

(defn latest-override-per-fx
  "Index `fx-events` into `{fx-id <latest-override-event>}` — used to
  surface the per-row stub indicator. Only `:rf.fx/override-applied`
  events contribute. Pure fn."
  [fx-events]
  (reduce (fn [acc ev]
            (if (and (= :rf.fx/override-applied (:operation ev))
                     (some? (event-fx-id ev)))
              (assoc acc (event-fx-id ev) ev)
              acc))
          {}
          (or fx-events [])))

(defn invocation-count-per-fx
  "Index `fx-events` into `{fx-id <count>}` — used by the row's
  invocation-counter caption. Counts every `:rf.fx/handled` /
  `:rf.fx/override-applied` / `:rf.fx/skipped-on-platform` /
  `:rf.error/fx-handler-exception` / `:rf.error/no-such-fx` event
  per fx-id. Pure fn."
  [fx-events]
  (reduce (fn [acc ev]
            (if-let [fid (event-fx-id ev)]
              (update acc fid (fnil inc 0))
              acc))
          {}
          (or fx-events [])))

(defn compute-outcome
  "Project one fx's outcome from its latest fx-related trace event.
  Pure fn.

  Decision order:

    1. `:error`         — `:rf.error/fx-handler-exception` or
                          `:rf.error/no-such-fx`.
    2. `:overridden`    — `:rf.fx/override-applied` (the fx ran via
                          override; surfaces the stub indicator).
    3. `:skipped`       — `:rf.fx/skipped-on-platform`.
    4. `:ok`            — `:rf.fx/handled` (the canonical success
                          path).
    5. `:never-invoked` — no prior event for this fx-id."
  [latest-ev]
  (let [op (:operation latest-ev)]
    (cond
      (= op :rf.error/fx-handler-exception)  :error
      (= op :rf.error/no-such-fx)            :error
      (= op :rf.fx/override-applied)         :overridden
      (= op :rf.fx/skipped-on-platform)      :skipped
      (= op :rf.fx/handled)                  :ok
      :else                                  :never-invoked)))

(defn project-rows
  "Project the registered-fx map + the Causa trace-buffer's fx-related
  slice into a vector of row maps the view consumes. Pure fn —
  JVM-runnable.

  Row shape:

      {:fx-id            <id>
       :platforms        #{:client :server} | nil
       :doc              <string-or-nil>
       :outcome          :ok | :overridden | :skipped | :error
                                | :never-invoked
       :stubbed?         <bool>          ;; true when an override is
                                          ;; active for this fx-id
       :invocation-count <int>           ;; total fx-related events
                                          ;; seen for this fx-id in
                                          ;; the current buffer
       :last-operation   <:rf.fx/...-or-nil>
       :last-trace-id    <int-or-nil>    ;; trace event :id; nil when
                                          ;; the fx has never fired
       :last-dispatch-id <int-or-nil>}   ;; cascade attribution for
                                          ;; the latest event

  Inputs:

    `fx-map`    — `{fx-id metadata}` from `(rf/registrations :fx)`. May be
                  empty / nil; the projection returns `[]`.

    `fx-events` — trace events filtered to the fx-related stream,
                  oldest first.

  The returned vector is sorted by `outcomes` (errors first, then
  overridden, then skipped, then ok, then never-invoked). Within an
  outcome, rows are sorted by `:fx-id` (as a printable string) for
  deterministic test output."
  [fx-map fx-events]
  (if (empty? fx-map)
    []
    (let [latest-by-fx    (latest-event-per-fx fx-events)
          override-by-fx  (latest-override-per-fx fx-events)
          counts          (invocation-count-per-fx fx-events)
          rows            (for [[fx-id meta] fx-map
                                :let [latest-ev (get latest-by-fx fx-id)
                                      outcome   (compute-outcome latest-ev)]]
                            {:fx-id            fx-id
                             :platforms        (:platforms meta)
                             :doc              (:doc meta)
                             :outcome          outcome
                             :stubbed?         (contains? override-by-fx fx-id)
                             :invocation-count (get counts fx-id 0)
                             :last-operation   (:operation latest-ev)
                             :last-trace-id    (:id latest-ev)
                             :last-dispatch-id (event-dispatch-id latest-ev)})
          sorter          (zipmap outcomes (range))]
      (vec
        (sort-by (fn [{:keys [outcome fx-id]}]
                   [(get sorter outcome (count outcomes))
                    (pr-str fx-id)])
                 rows)))))

(defn outcome-counts
  "Per-outcome tally over the projected rows. Pure fn."
  [rows]
  (frequencies (map :outcome rows)))

(defn recent-events-for-fx
  "Return the fx-related trace events for `fx-id`, newest first,
  capped at `n` (default 20). Used by the panel's per-fx detail strip
  so the user can scan the fx's recent invocations without leaving
  the panel.

  Pure fn — JVM-runnable; the cap keeps the render cost bounded even
  on a deep trace buffer."
  ([fx-events fx-id]
   (recent-events-for-fx fx-events fx-id 20))
  ([fx-events fx-id n]
   (let [matches (filterv #(= fx-id (event-fx-id %))
                          (or fx-events []))]
     (vec (take n (reverse matches))))))

(defn dispatch-ids-for-fx
  "Return the set of `:dispatch-id`s the fx fired under, across the
  current buffer. Used by the click-to-event-detail affordance — the
  user clicks an fx row and the panel pivots to event-detail filtered
  to invocations of this fx. Pure fn."
  [fx-events fx-id]
  (into #{}
        (comp (filter #(= fx-id (event-fx-id %)))
              (keep event-dispatch-id))
        (or fx-events [])))

;; ---- formatting helpers (consumed by the view) -------------------------

(defn format-fx-id
  "Render an fx-id for compact display in the mono column. Keywords
  keep their `:` prefix; symbols / strings render plain."
  [fx-id]
  (cond
    (keyword? fx-id) (str fx-id)
    (symbol?  fx-id) (str fx-id)
    :else            (str/trim (str fx-id))))

(defn format-platforms
  "Render the `:platforms` set as a short caption — `client`,
  `server`, or `client/server`. Returns the empty-marker `\"any\"` for
  the nil / fully-defaulted case (per Spec 002 §reg-fx the default is
  `#{:client :server}`)."
  [platforms]
  (cond
    (nil? platforms)                              "any"
    (and (contains? platforms :client)
         (contains? platforms :server))            "any"
    (contains? platforms :client)                  "client"
    (contains? platforms :server)                  "server"
    :else                                          (str/join "/" (map name platforms))))
