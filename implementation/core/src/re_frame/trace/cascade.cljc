(ns re-frame.trace.cascade
  "Per-epoch cascade-DAG aggregator.

  The raw trace stream emits `:sub/run` (recomputed), `:rf.sub/skip`
  (input value-equal → no recompute), `:rf.flow/computed`,
  `:rf.flow/skip`, `:view/render`, etc. one event at a time. Pair-shaped
  consumers (Xray's Reactive panel) want a single record per cascade
  capturing the full DAG — app-db changes → subs recomputed/skipped →
  views rendered/skipped — for the operator's currently-focused epoch.

  Capturing the full DAG for EVERY epoch in the ring buffer is too
  expensive (per-epoch sub/view sets can be large; storing them in the
  ring buffer blows the dev-only budget). The aggregator is therefore
  **focused-event-only**: the consumer publishes a focus predicate via
  `set-focus-predicate!` (typically `(fn [frame-id epoch-id event-id]
  ...)` against Xray's selected epoch + a small back-buffer), and the
  aggregator walks the harvested cascade ONLY when the predicate
  returns truthy. Off-focus epochs pay nothing beyond one predicate
  call.

  The capture is **bounded** at 50 subs and 100 views per epoch to match
  Xray's Reactive panel rendering budget.
  Cascades exceeding either cap retain the first N entries and stamp
  `:truncated? true` so the panel can render a 'rest elided' affordance.

  Emit shape (per Spec 009 §`:op-type` vocabulary):

      :operation :rf.cascade/captured
      :op-type   :rf.cascade
      :tags
        {:frame                 <frame-id>          ;; bare carve-out routing tag
         :rf.epoch/id           <epoch-id>          ;; canonical epoch-identity tag
         :rf.trace/event-id     <event-id>          ;; canonical trace event-id tag
         :subs-recomputed       [{:sub-id :query-v} ...]
         :subs-skipped          [{:sub-id :query-v
                                  :reason :input-paths-unchanged} ...]
         :flows-computed        [{:flow-id :path} ...]
         :flows-skipped         [{:flow-id :input-paths-unchanged} ...]
         :views-rendered        [{:render-key :triggered-by} ...]
         :sub-cap-truncated?    <bool>
         :view-cap-truncated?   <bool>}

  The aggregator publishes through one late-bind seam. The bundle-isolation
  gate verifies that this namespace and its capture literals stay out of
  production CLJS bundles through the `rf.interop/debug-enabled?` gate."
  (:require [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.trace :as rf.trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- focus-predicate seam -----------------------------------------------
;;
;; Default predicate returns false — i.e. NO epoch is focused, and the
;; aggregator never walks. A consumer (Xray's Reactive panel) calls
;; `set-focus-predicate!` once at mount to install its focus logic, and
;; `clear-focus-predicate!` on unmount.

(defonce ^:private focus-predicate
  (atom (fn [_frame-id _epoch-id _event-id] false)))

(defn set-focus-predicate!
  "Install the predicate the aggregator consults at end-of-epoch.
  Signature: `(fn [frame-id epoch-id event-id] truthy?)`. When the
  predicate returns truthy the aggregator walks the harvested cascade
  events and emits `:rf.cascade/captured`. Replaces any previously
  installed predicate."
  [pred-fn]
  (reset! focus-predicate (or pred-fn
                              (fn [_frame-id _epoch-id _event-id] false)))
  nil)

(defn clear-focus-predicate!
  "Restore the no-op default (no epoch is focused)."
  []
  (reset! focus-predicate (fn [_frame-id _epoch-id _event-id] false))
  nil)

;; ---- bounds --------------------------------------------------------------

(def ^:const sub-cap
  "Maximum number of subs (recomputed + skipped together) captured per
  epoch. The Xray Reactive panel renders a 'rest elided' affordance
  when exceeded."
  50)

(def ^:const view-cap
  "Maximum number of view-render entries captured per epoch."
  100)

;; ---- per-epoch aggregation ----------------------------------------------

(defn- conj-bounded
  "Conj `entry` onto transient vector `tv` only when its length is
  still under `cap`. Returns the (possibly unchanged) transient. The
  bound is checked via `count` per call — transient vectors support
  `count` in O(1)."
  [tv entry cap]
  (if (< (count tv) cap)
    (conj! tv entry)
    tv))

(defn- accumulate-bounded
  "Conj `entry` onto the transient vector under `vec-key` in the transient
  accumulator `acc`, bounded at `cap` (via `conj-bounded`). When that vector is
  ALREADY at `cap` — so `entry` is DROPPED — stamp `flag-key` true.

  The truncation flag therefore fires ONLY when an entry is genuinely elided: a
  cascade with EXACTLY `cap` entries retains all of them and is NOT truncated
  (rf2-x76af2.31); only cap+1 sets the flag — matching this namespace's
  docstring (`Cascades exceeding either cap …`) and Spec 009's `:rf.cascade/
  captured` wording (`cascades exceeding the cap stamp :sub-cap-truncated?
  true`). The `full?` guard is read BEFORE the conj so it never observes the
  post-mutation count of the in-place transient vector."
  [acc vec-key entry cap flag-key]
  (let [full? (>= (count (get acc vec-key)) cap)]
    (cond-> (assoc! acc vec-key (conj-bounded (get acc vec-key) entry cap))
      full? (assoc! flag-key true))))

(defn aggregate-cascade
  "Pure: walk `events` (the raw trace events harvested for one cascade)
  ONCE and produce the structured cascade-DAG map shape documented in
  this namespace's docstring. Bounded per `sub-cap` and `view-cap`.

  Reads only `:operation` + `:tags`; emits no traces; pure JVM/CLJS.
  Caller is responsible for the focus gate — this fn always walks."
  [events]
  ;; The transient accumulator keys mirror the public result-map keys
  ;; (`:subs-recomputed`, `:subs-skipped`, …) so the reduce body and the
  ;; final `persistent!` projection read against one vocabulary — no
  ;; abbreviated-alias translation layer to keep in your head. The two
  ;; truncation flags use the public `:sub-cap-truncated?` /
  ;; `:view-cap-truncated?` names for the same reason.
  (let [acc (reduce
              (fn [acc ev]
                (let [op (:operation ev)
                      t  (:tags ev)]
                  (cond
                    (= :rf.sub/run op)
                    ;; Per rf2-l1jz8 — thread the reactive recompute's value-
                    ;; change + cascade attribution onto the aggregated record so
                    ;; the `:rf.cascade/captured` projection carries the same
                    ;; fields as the epoch record's `:sub-runs`. Slots are nil for
                    ;; the `compute-sub` base-shape emit (which omits them).
                    (accumulate-bounded
                      acc :subs-recomputed
                      {:sub-id         (:rf.sub/id t)
                       :query-v        (:rf.sub/query-v t)
                       :value-changed? (:rf.sub/value-changed? t)
                       :prev-value     (:rf.sub/prev-value t)
                       :value          (:rf.sub/value t)
                       :cascade?       (:rf.sub/cascade? t)
                       :cause-sub      (:rf.sub/cause-sub t)
                       ;; rf2-okz1u — `:cause-event-id` names the dispatching
                       ;; cascade's event-id (the head of the event vector that
                       ;; kicked off the in-flight drain). Threaded from
                       ;; `:rf.sub/cause-event-id` on the recompute trace tag
                       ;; (sourced via the `:epoch/run-cause` late-bind hook at
                       ;; emit time). nil when the sub ran outside any run or the
                       ;; epoch artefact is absent — consumers (Xray's Epoch panel
                       ;; SUBSCRIPTIONS section) read it to credit each sub-run to
                       ;; the right epoch row even when the physical reactive flush
                       ;; deferred into a chained sibling event's drain.
                       :cause-event-id (:rf.sub/cause-event-id t)}
                      sub-cap :sub-cap-truncated?)

                    (= :rf.sub/skip op)
                    (accumulate-bounded
                      acc :subs-skipped
                      {:sub-id  (:rf.sub/id t)
                       :query-v (:rf.sub/query-v t)
                       :reason  (:rf.sub/reason t)
                       :input-paths-unchanged
                       (:rf.sub/input-paths-unchanged t)}
                      sub-cap :sub-cap-truncated?)

                    (= :rf.flow/computed op)
                    (assoc! acc :flows-computed
                            (conj! (get acc :flows-computed)
                                   {:flow-id (:flow-id t)
                                    :path    (:path t)}))

                    (= :rf.flow/skip op)
                    (assoc! acc :flows-skipped
                            (conj! (get acc :flows-skipped)
                                   {:flow-id (:flow-id t)
                                    :input-paths-unchanged
                                    (:input-paths-unchanged t)}))

                    (= :rf.view/render op)
                    (accumulate-bounded
                      acc :views-rendered
                      {:render-key   (:rf.view/render-key t)
                       :triggered-by (:triggered-by t)}
                      view-cap :view-cap-truncated?)

                    :else acc)))
              (transient {:subs-recomputed     (transient [])
                          :subs-skipped        (transient [])
                          :flows-computed      (transient [])
                          :flows-skipped       (transient [])
                          :views-rendered      (transient [])
                          :sub-cap-truncated?  false
                          :view-cap-truncated? false})
              events)]
    {:subs-recomputed     (persistent! (get acc :subs-recomputed))
     :subs-skipped        (persistent! (get acc :subs-skipped))
     :flows-computed      (persistent! (get acc :flows-computed))
     :flows-skipped       (persistent! (get acc :flows-skipped))
     :views-rendered      (persistent! (get acc :views-rendered))
     :sub-cap-truncated?  (get acc :sub-cap-truncated?)
     :view-cap-truncated? (get acc :view-cap-truncated?)}))

(defn capture-for-epoch!
  "If the installed focus predicate returns truthy for the given
  (frame, epoch, event), walk `events` and emit one
  `:rf.cascade/captured` trace carrying the structured cascade DAG.

  Called from the epoch-settle seam (`re-frame.epoch/settle!`) AFTER
  the cascade buffer has been harvested. No-op when the predicate
  returns falsy (the off-focus epoch pays only the predicate call).

  Whole body is inside `rf.interop/debug-enabled?` so CLJS production
  builds DCE the aggregator + emit."
  [frame-id epoch-id event-id events]
  (when rf.interop/debug-enabled?
    (let [pred @focus-predicate]
      (when (try (boolean (pred frame-id epoch-id event-id))
                 (catch #?(:clj Throwable :cljs :default) _ false))
        (let [dag (aggregate-cascade events)]
          (rf.trace/emit! :rf.cascade :rf.cascade/captured
                       (assoc dag
                              :frame             frame-id
                              :rf.epoch/id       epoch-id
                              :rf.trace/event-id event-id)))))))

;; ---- late-bind publication ----------------------------------------------
;;
;; The epoch-settle seam looks up this hook through `late-bind` so
;; `re-frame.epoch` does NOT require this namespace (and so a future
;; relocation to a tools artefact stays surgical). The hook is the
;; sticky-publication shape (rf2-f72pd) — published once at ns-load and
;; never withdrawn.

(rf.late-bind/set-fn! :trace.cascade/capture-for-epoch! capture-for-epoch!)
(rf.late-bind/set-fn! :trace.cascade/set-focus-predicate!   set-focus-predicate!)
(rf.late-bind/set-fn! :trace.cascade/clear-focus-predicate! clear-focus-predicate!)

;; ---- bundle-isolation sentinel ------------------------------------------
;;
;; Per rf2-931pm — the cascade aggregator is dev-only; CLJS production
;; bundles must NOT pull this ns in (the require in `re-frame.core` is
;; gated under `#?(:clj ...)` so Closure DCE strips the body). The
;; bundle-isolation gate searches every release bundle for the sentinel
;; string below; presence indicates the gate is broken — a `:require`
;; on `re-frame.trace.cascade` slipped into a core path that survives
;; production CLJS compilation.
;;
;; If you are adjusting this sentinel: also update
;; `implementation/scripts/check-bundle-isolation.cjs` (the production
;; bundle-isolation gate) so the two stay in sync.

(def ^:no-doc bundle-isolation-sentinel
  "rf.trace.cascade/sentinel:rf2-931pm:do-not-rename")
