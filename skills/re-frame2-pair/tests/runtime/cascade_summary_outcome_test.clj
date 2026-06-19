;;;; tests/runtime/cascade_summary_outcome_test.clj
;;;;
;;;; Babashka-runnable verification of the cascade-summary's CONTAINED-THROW
;;;; detection (rf2-hhkbb) in `preload/re_frame2_pair/runtime.cljs`:
;;;;
;;;;   - `cascade-errors`              — scans an epoch's `:trace-events` for a
;;;;                                     contained cascade exception (a
;;;;                                     `:rf.error/*` op in `cascade-error-ops`)
;;;;                                     and projects compact descriptors.
;;;;   - `cascade-summary` :outcome    — forced `:error` when the cascade
;;;;                                     contained a throw, even though the
;;;;                                     epoch itself settled `:outcome :ok`.
;;;;   - `consequence-from-summary`    — `:no-op? false` for a thrown-action
;;;;                                     epoch (a throw is NOT a no-op).
;;;;
;;;; THE BUG (rf2-hhkbb): a `:*` wildcard machine action throwing
;;;; `:rf.error/machine-action-exception` does NOT halt the drain — the
;;;; interceptor error-capture seam contains it, the epoch settles
;;;; `:outcome :ok`, and the throw rides the trace stream. Because the
;;;; aborted action committed no `:db` and fired no fx, the structured
;;;; cascade-summary read it as `{:outcome :ok :no-op? true}` — a silent-
;;;; green-on-error trap for any non-visual consumer triaging on those
;;;; slots. (The human pink-card path, rf2-4yrr6, reads the trace directly
;;;; and is unaffected.) The fix surfaces the throws under `:errors`, forces
;;;; `:outcome :error`, and reports `:no-op? false`.
;;;;
;;;; Why a parallel implementation lives here:
;;;;
;;;; `preload/re_frame2_pair/runtime.cljs` is a CLJS-only file loaded into
;;;; the consumer app via shadow-cljs `:devtools :preloads`. It depends on
;;;; the live re-frame2 epoch history / trace buffer, none of which run
;;;; under bb. This file mirrors the pure projection logic and asserts
;;;; behaviour against canned epoch records; a structural pin (below) keeps
;;;; the mirror honest against the source.
;;;;
;;;; KEEP IN SYNC WITH preload/re_frame2_pair/runtime.cljs §Cascade summary
;;;; (`cascade-error-ops` / `cascade-errors` / `cascade-summary` /
;;;; `consequence-from-summary`).
;;;;
;;;; Run: bb tests/runtime/cascade_summary_outcome_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns cascade-summary-outcome-test
  (:require [clojure.set]
            [clojure.test :refer [deftest is run-tests testing]]
            [runtime-support :as rt]))

;; ---------------------------------------------------------------------------
;; Mirror of preload/re_frame2_pair/runtime.cljs §Cascade summary.
;; KEEP IN SYNC.
;; ---------------------------------------------------------------------------

(def ^:private cascade-error-ops
  "Closed set of cascade-level `:rf.error/*` trace ops that mark a contained
  throw. Mirrors Xray's `cascade-exception-ops`."
  #{:rf.error/coeffect-exception
    :rf.error/interceptor-exception
    :rf.error/handler-exception
    :rf.error/fx-handler-exception
    :rf.error/no-such-fx
    :rf.error/flow-eval-exception
    :rf.error/machine-action-exception})

(defn- cascade-errors
  "Project contained cascade-exception trace events into compact descriptors,
  or nil when the cascade carried no contained throw."
  [trace-events]
  (let [picks (->> trace-events
                   (filter (fn [ev] (contains? cascade-error-ops (:operation ev))))
                   (mapv (fn [ev]
                           (let [t (:tags ev)]
                             (cond-> {:operation (:operation ev)}
                               (string? (:exception-message t))
                               (assoc :message (:exception-message t))
                               (:machine-id t) (assoc :machine-id (:machine-id t))
                               (:action-id t)  (assoc :action-id (:action-id t)))))))]
    (when (seq picks) picks)))

(defn- outcome-tier
  "Project the epoch's detailed `:outcome` cause onto the consumer-facing
  three-tier summary. Defaults to `:ok` for absent / unknown causes."
  [outcome]
  (case outcome
    :ok                       :ok
    :halted-depth             :blocked
    :halted-destroy           :blocked
    :halted-handler-exception :error
    :ok))

(defn- db-diff-summary
  "Depth-1 path summary of the db-before -> db-after delta."
  [db-before db-after]
  (cond
    (and (map? db-before) (map? db-after))
    (let [ks-b   (set (keys db-before))
          ks-a   (set (keys db-after))
          common (clojure.set/intersection ks-b ks-a)]
      {:added-paths   (vec (sort (map vector (clojure.set/difference ks-a ks-b))))
       :removed-paths (vec (sort (map vector (clojure.set/difference ks-b ks-a))))
       :changed-paths (vec (sort (for [k common
                                       :when (not= (get db-before k) (get db-after k))]
                                   [k])))})
    (= db-before db-after)
    {:added-paths [] :removed-paths [] :changed-paths []}
    :else
    {:added-paths [] :removed-paths [] :changed-paths [[]]}))

(defn cascade-summary
  "Mirror of the cascade-summary projection (slot subset relevant to the
  contained-throw fix: :outcome / :errors / :db-diff / :fx-fired)."
  [{:keys [epoch-id frame outcome db-before db-after effects trace-events]
    :as record}]
  (when record
    (let [diff     (db-diff-summary db-before db-after)
          fx-fired (->> effects (map :fx-id) distinct vec)
          errors   (cascade-errors trace-events)]
      (cond-> {:epoch-id epoch-id
               :frame    frame
               :outcome  (if errors :error (outcome-tier outcome))
               :db-diff  diff
               :fx-fired fx-fired}
        errors (assoc :errors errors)))))

(defn consequence-from-summary
  "Mirror of the dispatch-consequence projection (`:db-changed?` /
  `:effects-fired` / `:no-op?` derivation from the cascade-summary)."
  [result]
  (let [{:keys [cascade-summary]} result
        {:keys [db-diff fx-fired outcome errors]} cascade-summary
        changed (vec (concat (:changed-paths db-diff)
                             (:added-paths db-diff)
                             (:removed-paths db-diff)))
        effects (vec (or fx-fired []))
        db-changed? (boolean (seq changed))
        threw?  (boolean (seq errors))
        no-op?  (and (not threw?) (not db-changed?) (empty? effects))]
    (-> result
        (assoc :db-changed?   db-changed?
               :changed-paths changed
               :effects-fired effects
               :no-op?        no-op?)
        (cond-> (= :error outcome) (assoc :outcome :error)))))

;; ---------------------------------------------------------------------------
;; Canned epoch records (the live :8033 machine-epochs deck shapes).
;; ---------------------------------------------------------------------------

;; Rung 11 — `[:fuse/box [:fuse/short-circuit]]`: the `:*` wildcard action
;; THROWS `:rf.error/machine-action-exception`. The epoch settles
;; `:outcome :ok` (the interceptor seam contained the throw), committed NO
;; db-change and fired NO fx — precisely BECAUSE the action aborted.
(def thrown-action-epoch
  {:epoch-id 11
   :frame    :machine-epochs
   :outcome  :ok                       ;; the contained-throw epoch settles :ok
   :db-before {:baseline 5}
   :db-after  {:baseline 5}            ;; no db-change — the action aborted
   :effects  []                        ;; no fx — the action aborted
   :trace-events
   [{:operation :rf.event/run-start :tags {:frame :machine-epochs}}
    {:operation :rf.machine/action-ran
     :tags {:frame :machine-epochs :machine-id :fuse/box
            :action-id :blow-fuse :outcome :rf.error/action-threw}}
    {:operation :rf.error/machine-action-exception
     :tags {:frame :machine-epochs :machine-id :fuse/box :action-id :blow-fuse
            :exception-message "fuse blew"
            :transition {:rf/via-wildcard? true}}}
    {:operation :rf.event/run-end :tags {:frame :machine-epochs}}]})

;; Rung 7 — a benign unhandled-event no-op: the machine has no `:strict`
;; flag, the event resolves to nothing, NO exception trace fires.
(def benign-no-op-epoch
  {:epoch-id 7
   :frame    :machine-epochs
   :outcome  :ok
   :db-before {:baseline 3}
   :db-after  {:baseline 3}            ;; nothing changed
   :effects  []                        ;; nothing fired
   :trace-events
   [{:operation :rf.event/run-start :tags {:frame :machine-epochs}}
    {:operation :rf.event/run-end :tags {:frame :machine-epochs}}]})

;; A successful machine action: changes db, no exception trace.
(def successful-action-epoch
  {:epoch-id 4
   :frame    :machine-epochs
   :outcome  :ok
   :db-before {:baseline 1 :door :open}
   :db-after  {:baseline 1 :door :closed}
   :effects  []
   :trace-events
   [{:operation :rf.event/run-start :tags {:frame :machine-epochs}}
    {:operation :rf.machine/transition
     :tags {:frame :machine-epochs :machine-id :door/lock :from :open :to :closed}}
    {:operation :rf.event/run-end :tags {:frame :machine-epochs}}]})

;; A genuine non-machine fx error: a reg-event handler returned an fx-id
;; whose handler threw. The epoch carries the per-effect :outcome :error AND
;; the `:rf.error/fx-handler-exception` trace.
(def fx-error-epoch
  {:epoch-id 20
   :frame    :rf/default
   :outcome  :ok
   :db-before {:cart {}}
   :db-after  {:cart {:items 1}}       ;; the db committed before the fx threw
   :effects  [{:fx-id :http :outcome :error :error-trace 99}]
   :trace-events
   [{:operation :rf.event/run-start :tags {:frame :rf/default}}
    {:operation :rf.error/fx-handler-exception
     :tags {:frame :rf/default :rf.fx/id :http :exception-message "boom"}}
    {:operation :rf.event/run-end :tags {:frame :rf/default}}]})

;; ---------------------------------------------------------------------------
;; Behavioural tests — reproduce-then-fix.
;; ---------------------------------------------------------------------------

(deftest thrown-action-summary-is-error-not-ok
  ;; THE bug. RED before the fix: cascade-summary read :outcome :ok.
  ;; GREEN after: a contained machine-action throw forces :outcome :error
  ;; and surfaces the throw under :errors.
  (let [summary (cascade-summary thrown-action-epoch)]
    (testing "a thrown machine action projects :outcome :error, not :ok"
      (is (= :error (:outcome summary))
          "the cascade-summary outcome must be :error for a contained throw"))
    (testing "the throw is surfaced under :errors so a tool can read it"
      (is (seq (:errors summary)) ":errors must be present")
      (let [err (first (:errors summary))]
        (is (= :rf.error/machine-action-exception (:operation err)))
        (is (= :fuse/box (:machine-id err)))
        (is (= :blow-fuse (:action-id err)))
        (is (= "fuse blew" (:message err)))))))

(deftest thrown-action-consequence-is-not-a-no-op
  ;; THE bug, second defect. RED before the fix: :no-op? true (no db-change
  ;; + no fx). GREEN after: a throw is never a no-op.
  (let [summary (cascade-summary thrown-action-epoch)
        conseq  (consequence-from-summary {:ok? true :cascade-summary summary})]
    (testing "a thrown machine action is NOT a no-op"
      (is (false? (:no-op? conseq))
          ":no-op? must be false for a thrown-action epoch (the action aborted)"))
    (testing "the consequence :outcome is promoted to :error"
      (is (= :error (:outcome conseq))))))

;; ---------------------------------------------------------------------------
;; Negative guards — the fix must NOT over-fire.
;; ---------------------------------------------------------------------------

(deftest benign-no-op-stays-a-no-op
  ;; Rung 7. A genuine unhandled-event no-op carries no exception trace —
  ;; it must stay :outcome :ok + :no-op? true (the inverse of rung 11).
  (let [summary (cascade-summary benign-no-op-epoch)
        conseq  (consequence-from-summary {:ok? true :cascade-summary summary})]
    (is (= :ok (:outcome summary)) "a benign no-op stays :ok")
    (is (nil? (:errors summary))   "a benign no-op carries no :errors slot")
    (is (true? (:no-op? conseq))   "a benign no-op stays :no-op? true")))

(deftest successful-action-stays-ok
  ;; A successful machine action (db changed, no throw) stays :ok and is
  ;; not a no-op.
  (let [summary (cascade-summary successful-action-epoch)
        conseq  (consequence-from-summary {:ok? true :cascade-summary summary})]
    (is (= :ok (:outcome summary)) "a successful action stays :ok")
    (is (nil? (:errors summary))   "a successful action carries no :errors slot")
    (is (false? (:no-op? conseq))  "a successful action changed db, not a no-op")
    (is (true? (:db-changed? conseq)))))

(deftest fx-error-surfaces-as-error
  ;; A genuine non-machine reg-event error: the fx-handler threw. It
  ;; carries the `:rf.error/fx-handler-exception` trace, so the same
  ;; contained-throw detection applies — :outcome :error, not a no-op.
  (let [summary (cascade-summary fx-error-epoch)
        conseq  (consequence-from-summary {:ok? true :cascade-summary summary})]
    (is (= :error (:outcome summary))
        "an fx-handler throw surfaces :outcome :error")
    (is (some #(= :rf.error/fx-handler-exception (:operation %)) (:errors summary))
        "the fx-handler exception is surfaced under :errors")
    (is (false? (:no-op? conseq))
        "the fx error epoch is not a no-op (db changed AND it threw)")))

;; ---------------------------------------------------------------------------
;; Structural pin — keep the bb mirror honest against the cljs source.
;; ---------------------------------------------------------------------------

;; Shared locate+parse+walk scaffold lives in tests/runtime/_support.clj
;; (rf2-yrpt90). Alias the vars the assertions below use.
(def ^:private runtime-cljs-path rt/runtime-cljs-path)
(def ^:private form-contains? rt/form-contains?)

(defn- top-level-named [kinds sym]
  (some (fn [form]
          (when (and (seq? form) (kinds (first form)) (= sym (second form)))
            form))
        rt/all-forms))

(deftest source-defines-cascade-error-ops
  (is (some? runtime-cljs-path) "runtime.cljs must be locatable")
  (is (some? (top-level-named #{'def} 'cascade-error-ops))
      "runtime.cljs must define `cascade-error-ops` — the closed `:rf.error/*` set (rf2-hhkbb).")
  (let [form (top-level-named #{'def} 'cascade-error-ops)]
    (is (form-contains? #(= :rf.error/machine-action-exception %) form)
        "cascade-error-ops MUST include :rf.error/machine-action-exception (the rung-11 throw).")))

(deftest source-cascade-summary-forces-error-and-errors-slot
  (let [form (top-level-named #{'defn 'defn-} 'cascade-summary)]
    (is (some? form) "runtime.cljs must define `cascade-summary`")
    (is (some? (top-level-named #{'defn 'defn-} 'cascade-errors))
        "runtime.cljs must define `cascade-errors` (rf2-hhkbb).")
    (is (form-contains? #(= :errors %) form)
        "cascade-summary MUST surface the :errors slot (rf2-hhkbb).")))

(deftest source-consequence-excludes-thrown-from-no-op
  (let [form (top-level-named #{'defn 'defn-} 'consequence-from-summary)]
    (is (some? form) "runtime.cljs must define `consequence-from-summary`")
    ;; The :no-op? derivation must read the summary's errors so a thrown-
    ;; action epoch is excluded from the quiescence heuristic. `errors` is
    ;; bound via `:keys` destructuring (a symbol, not a keyword literal in
    ;; the form), and the exclusion threads through a `threw?` binding.
    (is (form-contains? #(= 'errors %) form)
        "consequence-from-summary MUST read the summary's `errors` so a throw is not a no-op (rf2-hhkbb).")
    (is (form-contains? #(= 'threw? %) form)
        "consequence-from-summary MUST exclude a thrown-action epoch from :no-op? via `threw?` (rf2-hhkbb).")))

(let [{:keys [fail error]} (run-tests 'cascade-summary-outcome-test)]
  (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))
