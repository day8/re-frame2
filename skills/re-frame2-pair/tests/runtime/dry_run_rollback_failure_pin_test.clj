;;;; tests/runtime/dry_run_rollback_failure_pin_test.clj
;;;;
;;;; Babashka-runnable STRUCTURAL PIN for the dry-run rollback-failure
;;;; SAFETY contract in `preload/re_frame2_pair/runtime.cljs` (rf2-glg4uo).
;;;;
;;;; The defect: `dispatch-dry-run`'s `:else` branch used to return
;;;; `:ok? true :rolled-back? false` + a human-readable `:rollback-hint`
;;;; when `restore-epoch!` returned false — CONTRADICTING its own
;;;; docstring (which lists `:reason :rollback-failed` as an `:ok? false`
;;;; failure). Because the MCP tool routes isError purely on
;;;; `(false? (:ok? result))`, that read GREEN over a MUTATED live app-db
;;;; (the would-be epoch's db IS now the live db + a spurious epoch left
;;;; in the ring). A dry-run whose whole contract is "no observable
;;;; effect" must NOT report success when it in fact mutated the app.
;;;;
;;;; Why a structural pin rather than a live runtime test:
;;;;
;;;; `preload/re_frame2_pair/runtime.cljs` is CLJS-only (loaded via
;;;; shadow-cljs `:devtools :preloads`) so it does not run under bb, and
;;;; `dispatch-dry-run` needs a LIVE re-frame2 frame (`rf/dispatch-sync`,
;;;; `rf/restore-epoch!`, `rf/epoch-history`). We therefore pin the
;;;; SOURCE-level contract: the not-rolled-back arm returns the documented
;;;; `:ok? false :reason :rollback-failed` shape and the old silent-green
;;;; `:rollback-hint` key is GONE. The MCP-boundary routing (isError on
;;;; :ok? false AND the belt-and-braces :rolled-back? false guard) is
;;;; covered by the real cljs.test unit + conformance suites at
;;;; tools/re-frame2-pair-mcp/test/.
;;;;
;;;; Run: bb tests/runtime/dry_run_rollback_failure_pin_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns dry-run-rollback-failure-pin-test
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(def ^:private ddr-form (rt/defn-named 'dispatch-dry-run))

(defn- if-rolled-back-form
  "The `(if rolled-back? <success-arm> <failure-arm>)` node inside
   `dispatch-dry-run` — the branch that decides success vs the
   rollback-failure shape."
  []
  (let [found (atom nil)]
    (walk/postwalk
      (fn [node]
        (when (and (seq? node)
                   (= 'if (first node))
                   (= 'rolled-back? (second node)))
          (reset! found node))
        node)
      ddr-form)
    @found))

(defn- assoc-pairs
  "For an `(assoc m :k1 v1 :k2 v2 ...)` form, the `{:k1 v1 ...}` map. nil
   when `form` isn't an assoc call."
  [form]
  (when (and (seq? form) (= 'assoc (first form)))
    (apply hash-map (drop 2 form))))

(defn- docstring []
  ;; The defn's direct docstring is the only string among the top-level
  ;; children (arity bodies are seqs, not bare strings).
  (first (filter string? ddr-form)))

;; ---------------------------------------------------------------------------
;; The runtime must ATTEMPT a rollback, and the two arms of that attempt
;; must carry the documented shapes: success -> :ok? true, failure ->
;; :ok? false :reason :rollback-failed. This is the core of rf2-glg4uo.
;; ---------------------------------------------------------------------------

(deftest dispatch-dry-run-is-defined
  (is (some? ddr-form) "dispatch-dry-run must be defined in the preload runtime"))

(deftest attempts-rollback-via-restore-epoch
  (is (rt/form-contains? #(= % 'rf/restore-epoch!) ddr-form)
      "dispatch-dry-run must attempt the rollback via rf/restore-epoch!")
  (is (rt/form-contains? #(= % 'rolled-back?) ddr-form)
      "it must bind the rollback outcome to `rolled-back?` and branch on it"))

(deftest not-rolled-back-arm-returns-ok-false-rollback-failed
  (let [if-form (if-rolled-back-form)]
    (is (some? if-form)
        "dispatch-dry-run must branch on (if rolled-back? <success> <failure>)")
    (let [[_if _cond then else] if-form
          then-kv (assoc-pairs then)
          else-kv (assoc-pairs else)]
      (is (= true (:ok? then-kv))
          "the rolled-back? TRUE arm returns :ok? true (the success shape)")
      (is (= false (:ok? else-kv))
          (str "the NOT-rolled-back arm MUST return :ok? false — a dry-run "
               "whose rollback failed left the live app MUTATED and must not "
               "read as success (rf2-glg4uo). It returned "
               (pr-str (:ok? else-kv))))
      (is (= :rollback-failed (:reason else-kv))
          "the failed-rollback arm carries the documented :reason :rollback-failed")
      (is (contains? else-kv :hint)
          "the failed-rollback arm carries a :hint for manual re-restore"))))

;; ---------------------------------------------------------------------------
;; The old silent-green shape must be DELETED. If `:rollback-hint` (the
;; :ok? true + human-string signal that read GREEN over a mutated db)
;; reappears, dry-run has regressed to reporting success on a failed
;; rollback — the exact defect rf2-glg4uo closed.
;; ---------------------------------------------------------------------------

(deftest silent-green-rollback-hint-is-gone
  (is (not (rt/form-contains? #(= % :rollback-hint) ddr-form))
      (str "the old :rollback-hint key (an :ok? true + string signal that "
           "read GREEN over a mutated live db) MUST be deleted — a failed "
           "rollback is an :ok? false :reason :rollback-failed failure now")))

;; ---------------------------------------------------------------------------
;; The docstring already documented :rollback-failed as an :ok? false
;; failure path; pin that the code now honours it (docstring + code agree).
;; ---------------------------------------------------------------------------

(deftest docstring-documents-rollback-failed-as-failure
  (let [ds (docstring)]
    (is (some? ds) "dispatch-dry-run must carry a docstring")
    (is (str/includes? ds "rollback-failed")
        "the docstring must document the :rollback-failed reason")
    (is (str/includes? ds ":ok? false")
        "the docstring must frame :rollback-failed as an :ok? false failure")))

(let [{:keys [fail error]} (run-tests 'dry-run-rollback-failure-pin-test)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
