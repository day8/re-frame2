(ns re-frame.trace.cascade
  "Per-epoch cascade-DAG aggregator (rf2-931pm).

  The raw trace stream emits `:sub/run` (recomputed), `:rf.sub/skip`
  (input value-equal → no recompute), `:rf.flow/computed`,
  `:rf.flow/skip`, `:view/render`, etc. one event at a time. Pair-shaped
  consumers (Causa's Reactive panel) want a single record per cascade
  capturing the full DAG — app-db changes → subs recomputed/skipped →
  views rendered/skipped — for the operator's currently-focused epoch.

  Capturing the full DAG for EVERY epoch in the ring buffer is too
  expensive (per-epoch sub/view sets can be large; storing them in the
  ring buffer blows the dev-only budget). The aggregator is therefore
  **focused-event-only**: the consumer publishes a focus predicate via
  `set-focus-predicate!` (typically `(fn [frame-id epoch-id event-id]
  ...)` against Causa's selected epoch + a small back-buffer), and the
  aggregator walks the harvested cascade ONLY when the predicate
  returns truthy. Off-focus epochs pay nothing beyond one predicate
  call.

  The capture is **bounded** at 50 subs and 100 views per epoch (per
  panel design rf2-931pm and Causa's Reactive panel rendering budget).
  Cascades exceeding either cap retain the first N entries and stamp
  `:truncated? true` so the panel can render a 'rest elided' affordance.

  Emit shape (per Spec 009 §`:op-type` vocabulary):

      :operation :rf.cascade/captured
      :op-type   :rf.cascade
      :tags
        {:frame                 <frame-id>
         :epoch-id              <epoch-id>
         :event-id              <event-id>
         :subs-recomputed       [{:sub-id :query-v} ...]
         :subs-skipped          [{:sub-id :query-v
                                  :reason :input-paths-unchanged} ...]
         :flows-computed        [{:flow-id :path} ...]
         :flows-skipped         [{:flow-id :input-paths-unchanged} ...]
         :views-rendered        [{:render-key :triggered-by} ...]
         :sub-cap-truncated?    <bool>
         :view-cap-truncated?   <bool>}

  Per pre-alpha posture (rf2-931pm): no transitional shims; the
  aggregator publishes through a single late-bind seam and the
  bundle-isolation gate verifies the namespace stays out of production
  CLJS bundles via the existing `interop/debug-enabled?` gate."
  (:require [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- focus-predicate seam -----------------------------------------------
;;
;; Default predicate returns false — i.e. NO epoch is focused, and the
;; aggregator never walks. A consumer (Causa's Reactive panel) calls
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

;; ---- bounds (per rf2-931pm panel design) --------------------------------

(def ^:const sub-cap
  "Maximum number of subs (recomputed + skipped together) captured per
  epoch. The Causa Reactive panel renders a 'rest elided' affordance
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

(defn aggregate-cascade
  "Pure: walk `events` (the raw trace events harvested for one cascade)
  ONCE and produce the structured cascade-DAG map shape documented in
  this namespace's docstring. Bounded per `sub-cap` and `view-cap`.

  Reads only `:operation` + `:tags`; emits no traces; pure JVM/CLJS.
  Caller is responsible for the focus gate — this fn always walks."
  [events]
  (let [acc (reduce
              (fn [acc ev]
                (let [op (:operation ev)
                      t  (:tags ev)]
                  (cond
                    (= :sub/run op)
                    (-> acc
                        (assoc! :sr (conj-bounded (get acc :sr)
                                                  {:sub-id  (:sub-id t)
                                                   :query-v (:query-v t)}
                                                  sub-cap))
                        (cond->
                          (>= (count (get acc :sr)) sub-cap)
                          (assoc! :st? true)))

                    (= :rf.sub/skip op)
                    (-> acc
                        (assoc! :ss (conj-bounded (get acc :ss)
                                                  {:sub-id  (:sub-id t)
                                                   :query-v (:query-v t)
                                                   :reason  (:reason t)
                                                   :input-paths-unchanged
                                                   (:input-paths-unchanged t)}
                                                  sub-cap))
                        (cond->
                          (>= (count (get acc :ss)) sub-cap)
                          (assoc! :st? true)))

                    (= :rf.flow/computed op)
                    (assoc! acc :fc
                            (conj! (get acc :fc)
                                   {:flow-id (:flow-id t)
                                    :path    (:path t)}))

                    (= :rf.flow/skip op)
                    (assoc! acc :fs
                            (conj! (get acc :fs)
                                   {:flow-id (:flow-id t)
                                    :input-paths-unchanged
                                    (:input-paths-unchanged t)}))

                    (= :view/render op)
                    (-> acc
                        (assoc! :vr (conj-bounded (get acc :vr)
                                                  {:render-key   (:render-key t)
                                                   :triggered-by (:triggered-by t)}
                                                  view-cap))
                        (cond->
                          (>= (count (get acc :vr)) view-cap)
                          (assoc! :vt? true)))

                    :else acc)))
              (transient {:sr  (transient [])
                          :ss  (transient [])
                          :fc  (transient [])
                          :fs  (transient [])
                          :vr  (transient [])
                          :st? false
                          :vt? false})
              events)]
    {:subs-recomputed     (persistent! (get acc :sr))
     :subs-skipped        (persistent! (get acc :ss))
     :flows-computed      (persistent! (get acc :fc))
     :flows-skipped       (persistent! (get acc :fs))
     :views-rendered      (persistent! (get acc :vr))
     :sub-cap-truncated?  (get acc :st?)
     :view-cap-truncated? (get acc :vt?)}))

(defn capture-for-epoch!
  "If the installed focus predicate returns truthy for the given
  (frame, epoch, event), walk `events` and emit one
  `:rf.cascade/captured` trace carrying the structured cascade DAG.

  Called from the epoch-settle seam (`re-frame.epoch/settle!`) AFTER
  the cascade buffer has been harvested. No-op when the predicate
  returns falsy (the off-focus epoch pays only the predicate call).

  Whole body is inside `interop/debug-enabled?` so CLJS production
  builds DCE the aggregator + emit."
  [frame-id epoch-id event-id events]
  (when interop/debug-enabled?
    (let [pred @focus-predicate]
      (when (try (boolean (pred frame-id epoch-id event-id))
                 (catch #?(:clj Throwable :cljs :default) _ false))
        (let [dag (aggregate-cascade events)]
          (trace/emit! :rf.cascade :rf.cascade/captured
                       (assoc dag
                              :frame    frame-id
                              :epoch-id epoch-id
                              :event-id event-id)))))))

;; ---- late-bind publication ----------------------------------------------
;;
;; The epoch-settle seam looks up this hook through `late-bind` so
;; `re-frame.epoch` does NOT require this namespace (and so a future
;; relocation to a tools artefact stays surgical). The hook is the
;; sticky-publication shape (rf2-f72pd) — published once at ns-load and
;; never withdrawn.

(late-bind/set-fn! :trace.cascade/capture-for-epoch! capture-for-epoch!)
(late-bind/set-fn! :trace.cascade/set-focus-predicate!   set-focus-predicate!)
(late-bind/set-fn! :trace.cascade/clear-focus-predicate! clear-focus-predicate!)

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
