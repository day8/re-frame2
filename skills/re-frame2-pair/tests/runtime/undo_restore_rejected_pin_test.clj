;;;; tests/runtime/undo_restore_rejected_pin_test.clj
;;;;
;;;; Babashka-runnable STRUCTURAL PIN for the undo-time-travel
;;;; restore-rejected SAFETY contract in
;;;; `preload/re_frame2_pair/runtime.cljs` (rf2-1v0vrr).
;;;;
;;;; The defect: `undo-to-epoch`'s failure branch returned
;;;; `{:ok? true :restored? false :reason :restore-rejected}` when
;;;; `rf/restore-epoch!` returned false — CONTRADICTING its correct
;;;; sibling `undo-step-back`, which returns `:ok? false` on the
;;;; identical path, and every other envelope fn in the file. Because the
;;;; skill trains the agent to pattern-match `:ok?` first and this backstop
;;;; sugar is reached via raw `eval-cljs` (which returns the map un-wrapped),
;;;; an agent read `{:ok? true}` as SUCCESS while the runtime REJECTED the
;;;; restore and the frame NEVER MOVED — a silent false-green over an
;;;; unchanged frame.
;;;;
;;;; Why a structural pin rather than a live runtime test:
;;;;
;;;; `preload/re_frame2_pair/runtime.cljs` is CLJS-only (loaded via
;;;; shadow-cljs `:devtools :preloads`) so it does not run under bb, and
;;;; `undo-to-epoch` / `undo-step-back` need a LIVE re-frame2 frame
;;;; (`rf/restore-epoch!`, `rf/epoch-history`, `rf/app-db-value`). We
;;;; therefore pin the SOURCE-level contract: BOTH sugars' rejected-restore
;;;; arms return the documented `:ok? false :reason :restore-rejected`
;;;; shape, and the two shapes MATCH so the pair of sibling sugars cannot
;;;; drift apart again.
;;;;
;;;; Run: bb tests/runtime/undo_restore_rejected_pin_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns undo-restore-rejected-pin-test
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(def ^:private step-back-form (rt/defn-named 'undo-step-back))
(def ^:private to-epoch-form  (rt/defn-named 'undo-to-epoch))

(defn- if-ok-node
  "The `(if ok? <success-arm> <rejected-arm>)` node inside `defn-form` —
   the branch that decides success vs the restore-rejected shape after
   `rf/restore-epoch!` binds its outcome to `ok?`. Both undo sugars share
   this shape."
  [defn-form]
  (let [found (atom nil)]
    (walk/postwalk
      (fn [node]
        (when (and (seq? node)
                   (= 'if (first node))
                   (= 'ok? (second node)))
          (reset! found node))
        node)
      defn-form)
    @found))

(defn- success-arm-map
  "The literal map inside the success arm. Both sugars build success as
   `(merge {:ok? true ...} (restore-cascade-summary ...))`, so we pull the
   first map argument out of the merge."
  [then-form]
  (when (and (seq? then-form) (= 'merge (first then-form)))
    (first (filter map? (rest then-form)))))

(defn- docstring [defn-form]
  ;; The defn's direct docstring is the only string among the top-level
  ;; children (arity bodies are seqs, not bare strings).
  (first (filter string? defn-form)))

;; ---------------------------------------------------------------------------
;; Both sugars must be defined and must actually attempt the restore via
;; rf/restore-epoch!, binding the outcome to `ok?`.
;; ---------------------------------------------------------------------------

(deftest both-undo-sugars-are-defined
  (is (some? step-back-form) "undo-step-back must be defined in the preload runtime")
  (is (some? to-epoch-form)  "undo-to-epoch must be defined in the preload runtime"))

(deftest both-attempt-restore-via-restore-epoch
  (doseq [[nm form] [["undo-step-back" step-back-form]
                     ["undo-to-epoch" to-epoch-form]]]
    (is (rt/form-contains? #(= % 'rf/restore-epoch!) form)
        (str nm " must attempt the restore via rf/restore-epoch!"))
    (is (some? (if-ok-node form))
        (str nm " must branch on (if ok? <success> <rejected>)"))))

;; ---------------------------------------------------------------------------
;; THE CORE OF rf2-1v0vrr: the rejected-restore arm of BOTH sugars must
;; return :ok? FALSE :reason :restore-rejected. undo-to-epoch used to
;; return :ok? true here — a false-green over an unchanged frame.
;; ---------------------------------------------------------------------------

(deftest rejected-restore-arm-is-ok-false-restore-rejected
  (doseq [[nm form] [["undo-step-back" step-back-form]
                     ["undo-to-epoch" to-epoch-form]]]
    (let [[_if _cond then else] (if-ok-node form)
          success (success-arm-map then)]
      (is (map? else)
          (str nm "'s rejected-restore arm must be a literal envelope map"))
      (is (= true (:ok? success))
          (str nm "'s success arm returns :ok? true"))
      (is (= false (:ok? else))
          (str nm "'s REJECTED-restore arm MUST return :ok? false — a "
               "restore that rf/restore-epoch! rejected left the frame "
               "UNCHANGED and must not read as success (rf2-1v0vrr). It "
               "returned " (pr-str (:ok? else))))
      (is (= false (:restored? else))
          (str nm "'s rejected arm carries :restored? false"))
      (is (= :restore-rejected (:reason else))
          (str nm "'s rejected arm carries the documented "
               ":reason :restore-rejected")))))

;; ---------------------------------------------------------------------------
;; The two sibling sugars must not DRIFT: their rejected-restore envelopes
;; must carry the same :ok? / :restored? / :reason contract keys. This is
;; what let undo-to-epoch quietly regress while undo-step-back stayed
;; correct — pin them together.
;; ---------------------------------------------------------------------------

(deftest sibling-rejected-shapes-agree
  (let [[_ _ _ sb-else] (if-ok-node step-back-form)
        [_ _ _ te-else] (if-ok-node to-epoch-form)
        keys-of         (fn [m] (select-keys m [:ok? :restored? :reason]))]
    (is (= (keys-of sb-else) (keys-of te-else))
        (str "undo-step-back and undo-to-epoch must return the SAME "
             ":ok?/:restored?/:reason contract on a rejected restore so "
             "the sibling sugars cannot drift. step-back="
             (pr-str (keys-of sb-else)) " to-epoch=" (pr-str (keys-of te-else))))))

;; ---------------------------------------------------------------------------
;; undo-to-epoch's docstring must document the failure shape (the old
;; docstring only described the success envelope, compounding the misread).
;; ---------------------------------------------------------------------------

(deftest to-epoch-docstring-documents-failure-shape
  (let [ds (docstring to-epoch-form)]
    (is (some? ds) "undo-to-epoch must carry a docstring")
    (is (str/includes? ds ":ok? false")
        "the docstring must frame the rejected restore as an :ok? false failure")
    (is (str/includes? ds "restore-rejected")
        "the docstring must document the :restore-rejected reason")))

(let [{:keys [fail error]} (run-tests 'undo-restore-rejected-pin-test)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
