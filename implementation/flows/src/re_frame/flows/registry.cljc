(ns re-frame.flows.registry
  "Per-frame flow registry — owns the `flows` and `last-inputs` atoms,
  flow-map validation, registration (`reg-flow` / `clear-flow`), and
  the registrar replacement-hook that invalidates the dirty-check on
  hot reload.

  Per Spec 013 flows are FRAME-SCOPED: same flow-id can register against
  two frames with different `:inputs` / `:output` / `:path`, and
  undo / time-travel semantics belong to one frame's history. The
  registry shape is `{frame-id {flow-id flow-map}}`.

  Per rf2-mnu8z this is the second leg of the flows split. The façade
  (`re-frame.flows`) re-exports `flows`, `last-inputs`, `reg-flow`,
  `clear-flow`, `reset-flows!`, `reset-last-inputs!` so external
  consumers — production code, the late-bind directory, and the test
  fixtures that `(reset! flows/flows {})` or
  `(resolve 're-frame.flows/last-inputs)` — continue to reach them at
  their documented namespace-qualified names."
  (:require [re-frame.flows.topo :as topo]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.source-coords :as source-coords]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

;; ---- state ---------------------------------------------------------------

(defonce
  ^{:doc "frame-id → flow-id → flow-map. Per-frame so undo / time-travel
          / clear semantics are unambiguous."}
  flows
  (atom {}))

(defonce
  ^{:doc "Per-frame last-inputs, keyed by [frame-id flow-id] → last seen
          input vec. Drives the dirty-check skip path in
          `re-frame.flows/evaluate-flow!` (rf2-719e)."}
  last-inputs
  (atom {}))

;; ---- validation ----------------------------------------------------------
;;
;; Per Spec 013 §Flow shape: `:inputs` is a vector of app-db paths, `:path`
;; is an app-db path. A path is a non-empty vector of scalar map keys. The
;; prior validator only enforced `vector?` on each, so three classes of
;; malformed input slipped through (per audit rf2-o3hok findings Q5 / TE4):
;;
;;   - `:inputs [:foo :bar]` (vector of bare keywords) — passed; then
;;     topo's `prefix?` threw on `(count :foo)`.
;;   - `:inputs [[:foo] :bar]` (mixed) — same path, same delayed boom.
;;   - `:path []` — passed; `(prefix? [] anything)` is true, so the empty-
;;     path flow silently became a depends-on prerequisite of EVERY other
;;     flow in the frame (per Spec 013 §Dependency rule).
;;
;; The tightened validator rejects each malformation up front with a stable
;; error id and ex-data that names the offending entries / elements so
;; callers don't have to chase the failure into the topo / evaluator stack.

(defn- valid-path-element?
  "Path elements are scalar map keys: keyword, string, integer, symbol, or
  boolean. Collections (vectors / maps / sets / seqs) are never the right
  value for a `get-in` path step and almost always indicate a caller bug
  (e.g. passing a bare keyword where a vector-of-paths was expected, then
  wrapping it one level too many)."
  [x]
  (or (keyword? x) (string? x) (integer? x) (symbol? x) (boolean? x)))

(defn- valid-path?
  "A path is a non-empty vector of valid path elements."
  [x]
  (and (vector? x) (seq x) (every? valid-path-element? x)))

(defn- validate-flow [flow]
  (cond
    (nil? (:id flow))
    (throw (ex-info ":rf.error/flow-missing-id" {:flow flow}))

    (not (vector? (:inputs flow)))
    (throw (ex-info ":rf.error/flow-bad-inputs"
                    {:flow flow :reason ":inputs must be a vector of paths"}))

    ;; One clause for both "entry isn't a vector" and "entry isn't a
    ;; valid path" — `valid-path?` already requires `vector?`, so the
    ;; older two-arm split (the prior code carried a separate
    ;; `(every? vector? ...)` check) was strictly subsumed by this one.
    ;; The single rejection message names what the entry must be; the
    ;; `:bad-entries` slot points at the offending values so callers
    ;; can fix them without a stack-trace dig.
    (not (every? valid-path? (:inputs flow)))
    (throw (ex-info ":rf.error/flow-bad-inputs"
                    {:flow flow
                     :reason ":inputs entries must each be a non-empty vector of scalar keys (keyword / string / integer / symbol / boolean)"
                     :bad-entries (vec (remove valid-path? (:inputs flow)))}))

    (not (fn? (:output flow)))
    (throw (ex-info ":rf.error/flow-bad-output"
                    {:flow flow :reason ":output must be a fn"}))

    (not (vector? (:path flow)))
    (throw (ex-info ":rf.error/flow-bad-path"
                    {:flow flow :reason ":path must be a vector"}))

    (empty? (:path flow))
    (throw (ex-info ":rf.error/flow-bad-path"
                    {:flow flow
                     :reason ":path must be non-empty (an empty :path would make this flow a depends-on prerequisite of every other flow per Spec 013 §Dependency rule)"}))

    (not (every? valid-path-element? (:path flow)))
    (throw (ex-info ":rf.error/flow-bad-path"
                    {:flow flow
                     :reason ":path elements must each be a scalar key (keyword / string / integer / symbol / boolean)"
                     :bad-elements (vec (remove valid-path-element? (:path flow)))}))))

;; ---- registration --------------------------------------------------------

(defn reg-flow
  "Register a flow against a frame. Per Spec 013 — flows are frame-
  scoped: their lifecycle, evaluation, undo / time-travel semantics
  all belong to one frame.

  Required keys on the flow map: :id :inputs :output :path.
  Optional: :doc :spec.

  The frame to register against comes from the optional :frame opt;
  default is (frame/current-frame) — usually :rf/default unless
  called inside a (with-frame ...) wrapper or under a frame-provider."
  ([flow] (reg-flow flow {}))
  ([flow {:keys [frame] :as _opts}]
   (validate-flow flow)
   (let [frame-id     (or frame (frame/current-frame))
         flow-id      (:id flow)
         prior-frame  (get @flows frame-id)
         ;; Per rf2-7csri: detect cycles on a PROSPECTIVE flow-map
         ;; BEFORE mutating the atom or the registrar. The earlier
         ;; write-then-rollback path silently deleted the prior
         ;; registration along with the rejected one when a REPLACEMENT
         ;; introduced a cycle — the rollback dissoc'd by flow-id,
         ;; vacating the slot the prior entry was sharing. Now we run
         ;; topo-sort on (prior-frame `assoc` new-entry) up-front; if it
         ;; throws, nothing has been written and the prior registration
         ;; stays intact.
         prospective  (assoc prior-frame flow-id flow)]
     (topo/topo-sort prospective)
     ;; Cycle check passed — commit. The :flow registrar slot keys on
     ;; flow-id only; stamp :frame into the metadata so introspection
     ;; / hot-reload hooks can read the owning frame.
     (registrar/register! :flow flow-id
                          (source-coords/merge-coords
                            (assoc flow :frame frame-id)))
     (swap! flows assoc-in [frame-id flow-id] flow)
     ;; Per Spec 009 §:op-type vocabulary: :rf.flow/registered fires after
     ;; reg-flow successfully completes (including post-cycle-detection).
     ;; Tools observe this to track the flow population over hot reloads /
     ;; toggles. Op-type :flow is the discriminator for the whole flow
     ;; trace stream (per Spec 009 §:op-type vocabulary, §Flow tracing).
     (trace/emit! :flow :rf.flow/registered
                  {:flow-id flow-id
                   :inputs  (:inputs flow)
                   :path    (:path flow)
                   :frame   frame-id})
     flow-id)))

(defn clear-flow
  "Deregister a flow from a frame; dissoc its output path from that
  frame's app-db (only that frame). Frame defaults to (current-frame)."
  ([id] (clear-flow id {}))
  ([id {:keys [frame] :as _opts}]
   (let [frame-id (or frame (frame/current-frame))]
     (when-let [flow (get-in @flows [frame-id id])]
       (let [path (:path flow)]
         (when-let [container (frame/get-frame-db frame-id)]
           (let [cur    (adapter/read-container container)
                 ;; rf2-aqt7: when :path is a single-element vector [:k],
                 ;; (butlast [:k]) is () and (update-in cur [] dissoc :k)
                 ;; does NOT dissoc — Clojure's update-in on the empty
                 ;; path falls into (assoc {} nil (apply f val args)),
                 ;; producing {... nil nil}. Special-case length 1 so
                 ;; the leaf is dissoc'd directly.
                 new-db (cond
                          (not (vector? path))         (dissoc cur path)
                          (empty? path)                cur
                          (= 1 (count path))           (dissoc cur (first path))
                          :else                        (update-in cur
                                                                  (vec (butlast path))
                                                                  dissoc
                                                                  (last path)))]
             (adapter/replace-container! container new-db)))
         (swap! flows update frame-id dissoc id)
         (swap! last-inputs dissoc [frame-id id])
         ;; Only unregister from the registrar if this was the LAST
         ;; frame holding the flow id — otherwise other frames still
         ;; need the registry slot for hot-reload tracking.
         (when (every? (fn [[_ frame-flows]] (not (contains? frame-flows id)))
                       @flows)
           (registrar/unregister! :flow id))
         ;; Per Spec 009 §:op-type vocabulary: :rf.flow/cleared fires after
         ;; clear-flow has removed the flow from the per-frame registry
         ;; and dissoc-in'd its output path. Tools observe this to drop
         ;; their per-flow display state.
         (trace/emit! :flow :rf.flow/cleared
                      {:flow-id id
                       :path    path
                       :frame   frame-id})))
     nil)))

;; ---- hot-reload invalidation --------------------------------------------
;;
;; Per Spec 001 §Hot-reload semantics: when a flow re-registers, the
;; per-frame :last-inputs entry MUST clear so the new flow re-evaluates
;; on the next drain regardless of whether inputs changed. Without this,
;; a hot-reloaded flow with a different :output fn but identical recent
;; inputs would silently keep serving the previous result.

(defn- invalidate-flow-on-replace!
  [{:keys [kind id]}]
  (when (= kind :flow)
    (swap! last-inputs
           (fn [m]
             (into {} (remove (fn [[[_ flow-id] _]] (= flow-id id))) m)))))

(defonce ^:private _hot-reload-hook
  (do (registrar/add-replacement-hook! invalidate-flow-on-replace!)
      :installed))

;; ---- test-only resets ----------------------------------------------------

(defn reset-last-inputs!
  "Test-only: clear the dirty-check `last-inputs` map. The flows
  reset-runtime fixture uses this to drop stale per-flow state between
  tests so re-registration does not silently no-op when new-inputs
  =-equal a stale entry from a sibling test. Per rf2-tfw3 (the fourth
  per-feature split): this is published through the late-bind hook
  table so `re-frame.test-support`'s reset-runtime fixture can call it
  without statically requiring `re-frame.flows`."
  []
  (reset! last-inputs {})
  nil)

(defn reset-flows!
  "Test-only: clear the per-frame flow registry. Pairs with
  `reset-last-inputs!` for the test-fixture reset bracket. Per
  rf2-tfw3 — exposed via the late-bind hook table so
  `re-frame.test-support` can reset state without a static require on
  this namespace."
  []
  (reset! flows {})
  nil)
