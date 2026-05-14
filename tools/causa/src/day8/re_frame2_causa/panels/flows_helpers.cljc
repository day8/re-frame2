(ns day8.re-frame2-causa.panels.flows-helpers
  "Pure-data helpers for Causa's Flows panel (Phase 5, rf2-83irn,
  parent rf2-5aw5v).

  ## Why a separate `.cljc` ns

  Same dual-target pattern every other panel uses (subscriptions,
  causality-graph, time-travel, app-db-diff, ...). The panel view in
  `flows.cljs` builds the hiccup; the *logic* — folding the registered-
  flow set + the trace-buffer's `:flow`-op-type events into per-flow
  rows with a recomputation indicator — is pure data → data and runs
  under the JVM unit-test target (`clojure -M:test`).

  ## The four-status taxonomy

  The Flows panel surfaces one status per flow row, derived from the
  most recent `:rf.flow/*` trace event for that flow:

  | Status        | Glyph | Colour token | Tooltip                         |
  |---------------|-------|--------------|---------------------------------|
  | `:computing`  | `◐`   | `:cyan`      | Recomputed                      |
  | `:idle`       | `●`   | `:green`     | Idle                            |
  | `:skipping`   | `○`   | `:text-tertiary` | Skipped (inputs unchanged)  |
  | `:failed`     | `▲`   | `:red`       | Failed                          |

  `:computing` is a *pulse* — when the most recent trace event for a
  flow is `:rf.flow/computed` and that event is part of the latest
  cascade, the row's status renders as `:computing` so the view
  surfaces the live recomputation indicator the bead's
  minimum-viable contract calls for. Older `:rf.flow/computed` events
  decay to `:idle` as soon as another cascade lands.

  `:skipping` corresponds to the runtime's `:rf.flow/skip` trace event
  (per rf2-719e value-equal recompute suppression — Spec 013 §Dirty-
  check semantics + Spec 009 §Flow trace events). It surfaces the
  'flow ran but inputs were stable' signal as a distinct status.

  Flows that registered but have never emitted a `:rf.flow/computed`
  / `:rf.flow/skip` / `:rf.flow/failed` event are `:idle` by default.

  Cleared flows (`:rf.flow/cleared`) drop out of the registered-flows
  list at the same instant — they never appear in the panel; the
  `:rf.flow/cleared` event is informational only.

  ## What this doesn't do

  Pure data. No subscription, no atom, no `js/` interop — the same
  fn runs under CLJ and CLJS. The CLJS-only surfaces (`rf/registrations`
  on a populated registrar) are read by the composite sub in
  `registry.cljs`; the result is handed to this ns as a plain map."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa.panels.common-helpers :as common]))

;; ---- design tokens (mirrors view tokens — kept here for tests) ----------
;;
;; The view consumes these via this ns so the status → token mapping has
;; one source of truth. The pure-data side stays JVM-portable.

(def status->token
  "Status → colour-token mapping. The view resolves the token to a hex
  via its private `tokens` map. Source of truth for the panel + any
  follow-on Causa surface that wants to render a flow status badge."
  {:computing :cyan
   :idle      :green
   :skipping  :text-tertiary
   :failed    :red})

(def status->glyph
  "Status → shape-glyph mapping. The glyph carries the taxonomy
  without any colour signal so the panel is legible to colour-blind
  users — same 'colour is never alone' discipline the Subscriptions
  panel ships."
  {:computing "◐"
   :idle      "●"
   :skipping  "○"
   :failed    "▲"})

(def status->tooltip
  "Status → hover-tooltip mapping. Hover over a badge surfaces this
  string."
  {:computing "Recomputed this cascade"
   :idle      "Idle"
   :skipping  "Skipped (inputs unchanged)"
   :failed    "Failed"})

(def statuses
  "Canonical row-ordering — failures first, then recently-recomputed,
  then skipping, then idle. The view sorts rows by this order so the
  most actionable rows surface at the top."
  [:failed :computing :skipping :idle])

;; ---- helpers ------------------------------------------------------------

(defn flow-trace-event?
  "True when `ev` is a `:op-type :flow` trace event. Pure predicate
  — used by the composite sub to filter the Causa trace buffer down
  to the flow stream."
  [ev]
  (= :flow (:op-type ev)))

(defn filter-flow-events
  "Return only the `:op-type :flow` events from a trace-event vector.
  Oldest first (matches the buffer's natural order). Pure fn."
  [events]
  (filterv flow-trace-event? (or events [])))

;; Re-export `tag-of` under the local name `tag` so existing call sites
;; (`(tag ev :flow-id)`, etc.) keep working without churn. Body lives in
;; `common-helpers`.
(def ^:private tag common/tag-of)

(defn- event-flow-id [ev] (tag ev :flow-id))
(defn- event-dispatch-id [ev] (tag ev :dispatch-id))

(defn latest-event-per-flow
  "Index `flow-events` (oldest first) into `{flow-id <latest-event>}`.
  Pure fn — the linear scan keeps the latest event seen per flow-id
  as the buffer iterates oldest→newest. Tools render the row's
  current status from the latest event's `:operation`."
  [flow-events]
  (reduce (fn [acc ev]
            (if-let [fid (event-flow-id ev)]
              (assoc acc fid ev)
              acc))
          {}
          (or flow-events [])))

(defn latest-cascade-dispatch-id
  "Return the `:dispatch-id` of the newest event in `events`, or nil
  when no event carries one. Used to mark a flow as `:computing` only
  when its most recent recompute happened *in the latest cascade* — so
  the live-recomputation indicator pulses with the cascade rather than
  staying lit forever."
  [events]
  (->> (or events [])
       reverse
       (some event-dispatch-id)))

(defn compute-status
  "Project one flow's status from its latest `:rf.flow/*` trace event
  and the cascade context. Pure fn.

  Arguments:
    `latest-ev`       — the newest `:op-type :flow` event for this
                        flow-id, or nil if the flow has never emitted.
    `latest-cascade-id` — the `:dispatch-id` of the newest event in
                        the buffer (any op-type). When the latest
                        flow-event for this flow shares this id, the
                        row is in the *current* cascade and renders
                        the `:computing` pulse.

  Decision order:

    1. `:failed`     — latest event is `:rf.flow/failed`. Wins over
                       everything; the row stays in the failure
                       status until a successful recompute lands.
    2. `:computing`  — latest event is `:rf.flow/computed` AND that
                       event is in the latest cascade.
    3. `:skipping`   — latest event is `:rf.flow/skip` AND that event
                       is in the latest cascade.
    4. `:idle`       — every other case (no events; latest event is
                       `:rf.flow/registered`; older `:rf.flow/computed`
                       / `:rf.flow/skip` that's no longer the active
                       cascade)."
  [latest-ev latest-cascade-id]
  (let [op           (:operation latest-ev)
        ev-cascade   (event-dispatch-id latest-ev)
        in-cascade?  (and (some? latest-cascade-id)
                          (= ev-cascade latest-cascade-id))]
    (cond
      (= op :rf.flow/failed)                       :failed
      (and in-cascade? (= op :rf.flow/computed))   :computing
      (and in-cascade? (= op :rf.flow/skip))       :skipping
      :else                                        :idle)))

(defn project-rows
  "Project the registered-flows map + the Causa trace-buffer's
  `:flow`-op-type slice into a vector of row maps the view consumes.
  Pure fn — JVM-runnable.

  Row shape:

      {:flow-id          <id>
       :frame            <frame-id-or-nil>
       :inputs           [<path> ...]    ;; vector of app-db paths
       :path             <path>          ;; output app-db path
       :status           :failed | :computing | :skipping | :idle
       :recomputing?     <bool>          ;; true when status :computing
       :doc              <string-or-nil>
       :last-operation   <:rf.flow/...-or-nil>
       :last-trace-id    <int-or-nil>}   ;; trace event :id; nil when
                                          ;; the flow never emitted

  Inputs:

    `flows-map`   — `{flow-id metadata}` from `(rf/registrations :flow)`.
                    May be empty / nil; the projection returns `[]`.

    `flow-events` — trace events filtered to `:op-type :flow`,
                    oldest first.

  The returned vector is sorted by `statuses` (failures first, then
  computing, then skipping, then idle). Within a status, rows are
  sorted by `:flow-id` (as a printable string) for deterministic
  test output."
  [flows-map flow-events]
  (if (empty? flows-map)
    []
    (let [latest-by-flow      (latest-event-per-flow flow-events)
          latest-cascade-id   (latest-cascade-dispatch-id flow-events)
          rows                (for [[flow-id meta] flows-map
                                    :let [latest-ev (get latest-by-flow flow-id)
                                          status    (compute-status
                                                      latest-ev
                                                      latest-cascade-id)]]
                                {:flow-id        flow-id
                                 :frame          (:frame meta)
                                 :inputs         (vec (or (:inputs meta) []))
                                 :path           (:path meta)
                                 :status         status
                                 :recomputing?   (= status :computing)
                                 :doc            (:doc meta)
                                 :last-operation (:operation latest-ev)
                                 :last-trace-id  (:id latest-ev)})
          sorter              (zipmap statuses (range))]
      (vec
        (sort-by (fn [{:keys [status flow-id]}]
                   [(get sorter status (count statuses))
                    (pr-str flow-id)])
                 rows)))))

(defn status-counts
  "Per-status tally over the projected rows. Pure fn."
  [rows]
  (frequencies (map :status rows)))

(defn recent-events-for-flow
  "Return the trace events for `flow-id`, newest first, capped at
  `n` (default 20). Used by the panel's per-flow detail strip so the
  user can scan the flow's recent recompute history without leaving
  the panel.

  Pure fn — JVM-runnable; the cap keeps the render cost bounded even
  on a deep trace buffer."
  ([flow-events flow-id]
   (recent-events-for-flow flow-events flow-id 20))
  ([flow-events flow-id n]
   (let [matches (filterv #(= flow-id (event-flow-id %))
                          (or flow-events []))]
     (vec (take n (reverse matches))))))

;; ---- formatting helpers (consumed by the view) -------------------------

(defn format-flow-id
  "Render a flow-id for compact display in the mono column. Keywords
  keep their `:` prefix; symbols / strings render plain."
  [flow-id]
  (cond
    (keyword? flow-id) (str flow-id)
    (symbol?  flow-id) (str flow-id)
    :else              (str/trim (str flow-id))))

(defn format-path
  "Pretty-print an `app-db` path for display. Mirrors `pr-str`'s
  output but tolerates an unprintable element (falling back to
  `str`) — flow paths are always plain vectors of keywords / strings
  in practice, but the helper is forgiving."
  [path]
  (try
    (pr-str path)
    (catch #?(:clj Throwable :cljs :default) _
      (str path))))

(defn format-inputs
  "Render the `:inputs` vector — each path pr-str'd, separated by
  ` · ` so the mono column stays readable when a flow takes several
  inputs. Returns the empty-marker `\"—\"` for the empty case rather
  than an empty string."
  [inputs]
  (if (empty? inputs)
    "—"
    (str/join " · " (map format-path inputs))))
