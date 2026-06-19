;;;; tests/runtime/cascade_summary_redaction_test.clj
;;;;
;;;; Babashka-runnable verification of the cascade-summary `:event-vector`
;;;; egress redaction (rf2-6nks4, finding-2) in
;;;; `preload/re_frame2_pair/runtime.cljs`.
;;;;
;;;; THE BUG (rf2-6nks4, finding-2): `cascade-summary` /
;;;; `restore-cascade-summary` copy the epoch's RAW `:trigger-event` into
;;;; the projection's `:event-vector` slot verbatim, and the MCP
;;;; `restore-epoch` + `dispatch-dry-run` tools pass that projection
;;;; through (restore-epoch ships the runtime map verbatim; dispatch-dry-
;;;; run DELIBERATELY does not walk `:cascade-summary`). The merged
;;;; rf2-z7roa elision walker scrubbed dispatch-dry-run's
;;;; `:db-state-after-simulation` / `:would-fire-effects` but left the
;;;; cascade-summary `:event-vector` UNWALKED — so a restore / dry-run of
;;;; a SENSITIVE historical epoch returned the raw event payload (auth
;;;; tokens, passwords, …) even under `--allow-sensitive-reads` OFF.
;;;;
;;;; THE FIX: `redact-sensitive-event-vector` redacts the slot to
;;;; `:rf/redacted` when the source epoch is `:rf.epoch/sensitive? true`
;;;; AND the runtime raw-state gate is OFF (the published-build default —
;;;; the MCP server signals `configure-raw-state! {:allow-raw-state?
;;;; false}` before restore-epoch / dispatch-dry-run build the summary).
;;;; Fail-closed: gate OFF redacts; gate ON (operator opted in via
;;;; `--allow-sensitive-reads`) ships the raw vector. A non-sensitive
;;;; epoch is never redacted.
;;;;
;;;; Why a parallel implementation lives here:
;;;;
;;;; `preload/re_frame2_pair/runtime.cljs` is a CLJS-only file loaded into
;;;; the consumer app via shadow-cljs `:devtools :preloads`. It depends on
;;;; the live re-frame2 epoch history / trace buffer / `rf/elide-wire-value`,
;;;; none of which run under bb. This file mirrors the pure projection +
;;;; gate logic and asserts behaviour against canned epoch records; a
;;;; structural pin (below) keeps the mirror honest against the source so
;;;; a regression in the real cljs (e.g. someone re-introduces the raw
;;;; verbatim assoc) trips a RED here.
;;;;
;;;; KEEP IN SYNC WITH preload/re_frame2_pair/runtime.cljs §Cascade summary
;;;; (`redact-sensitive-event-vector` / `cascade-summary` /
;;;; `restore-cascade-summary`).
;;;;
;;;; Run: bb tests/runtime/cascade_summary_redaction_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns cascade-summary-redaction-test
  (:require [clojure.set]
            [clojure.test :refer [deftest is run-tests testing]]
            [runtime-support :as rt]))

;; ---------------------------------------------------------------------------
;; Mirror of preload/re_frame2_pair/runtime.cljs §Cascade summary
;; (the `:event-vector` egress redaction slice). KEEP IN SYNC.
;; ---------------------------------------------------------------------------

;; The runtime gate: `(defonce raw-state-config (atom {:allow-raw-state?
;; true}))`. Default true ⇒ a bare REPL session sees verbatim; the MCP
;; server flips it to false via `configure-raw-state!` the moment a
;; state-emitting tool first fires. We mirror it as a rebindable atom.
(def ^:private raw-state-config (atom {:allow-raw-state? true}))

(defn- redact-sensitive-event-vector
  "Mirror of the egress guard. Redacts the raw trigger-event to
  `:rf/redacted` when the epoch is sensitive AND the raw-state gate is
  OFF (fail-closed). Raw only on opt-in; non-sensitive never redacts."
  [trigger-event sensitive?]
  (if (and sensitive? (not (:allow-raw-state? @raw-state-config)))
    :rf/redacted
    trigger-event))

(defn- db-diff-summary
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
  "Mirror of `cascade-summary` (the slot subset relevant to the
  `:event-vector` redaction fix). `dispatch-dry-run`'s `:cascade-summary`
  routes through this same fn in the real runtime."
  [{:keys [epoch-id event-id trigger-event frame db-before db-after effects]
    :as record}]
  (when record
    (let [diff       (db-diff-summary db-before db-after)
          fx-fired   (->> effects (map :fx-id) distinct vec)
          sensitive? (:rf.epoch/sensitive? record)]
      (cond-> {:epoch-id        epoch-id
               :frame           frame
               :outcome         :ok
               :db-diff         diff
               :fx-fired        fx-fired}
        event-id      (assoc :event-id event-id)
        trigger-event (assoc :event-vector
                             (redact-sensitive-event-vector trigger-event sensitive?))
        sensitive?    (assoc :sensitive? true)))))

(defn restore-cascade-summary
  "Mirror of `restore-cascade-summary`'s `:event-vector` slice — reads the
  TARGET epoch's `:rf.epoch/sensitive?` rollup and redacts the
  `:event-vector` through the same gate. (The real fn also computes
  `:db-diff` from `pre-db`, `:unreplayable-effects`, etc.; this mirror
  pins only the redaction-relevant slots.)"
  [target]
  (let [sensitive? (:rf.epoch/sensitive? target)]
    {:cascade-summary
     (cond-> {:epoch-id (:epoch-id target)
              :frame    (:frame target)
              :outcome  :ok
              :restore? true}
       (:event-id target)      (assoc :event-id (:event-id target))
       (:trigger-event target) (assoc :event-vector
                                      (redact-sensitive-event-vector
                                        (:trigger-event target) sensitive?))
       sensitive?              (assoc :sensitive? true))}))

;; ---------------------------------------------------------------------------
;; Canned epoch records.
;; ---------------------------------------------------------------------------

;; A SENSITIVE epoch — a login cascade whose trigger-event carries a
;; password in the dispatch vector. The framework stamped the epoch
;; `:rf.epoch/sensitive? true` (declared-sensitive db slot OR a
;; `:sensitive? true` registration). The raw `:trigger-event` MUST NOT
;; ride the `:event-vector` off-box under the default gate-OFF posture.
(def sensitive-login-epoch
  {:epoch-id 42
   :event-id :auth/login
   :trigger-event [:auth/login {:username "ada" :password "hunter2"}]
   :frame :rf/default
   :rf.epoch/sensitive? true
   :db-before {:auth {}}
   :db-after  {:auth {:user "ada"}}
   :effects   [{:fx-id :http}]})

;; A NON-sensitive epoch — an ordinary cart-add. The trigger-event is
;; safe to surface verbatim regardless of the gate.
(def benign-cart-epoch
  {:epoch-id 7
   :event-id :cart/add
   :trigger-event [:cart/add {:sku "x"}]
   :frame :rf/default
   :db-before {:cart {}}
   :db-after  {:cart {:items 1}}
   :effects   []})

;; ---------------------------------------------------------------------------
;; Behavioural tests — reproduce-then-fix.
;;
;; RED on the pre-fix runtime (which copied `:trigger-event` into
;; `:event-vector` verbatim, so a sensitive epoch leaked the password
;; under gate OFF). GREEN on the fixed runtime.
;; ---------------------------------------------------------------------------

(defn- with-gate [allow-raw-state? thunk]
  (let [prev @raw-state-config]
    (reset! raw-state-config {:allow-raw-state? allow-raw-state?})
    (try (thunk) (finally (reset! raw-state-config prev)))))

(deftest sensitive-event-vector-redacted-by-default-in-cascade-summary
  ;; THE bug. Gate OFF (the published-build default once the MCP server
  ;; signals it) — the sensitive trigger-event MUST redact, not leak.
  (with-gate false
    (fn []
      (let [summary (cascade-summary sensitive-login-epoch)]
        (testing "the raw password never appears on the wire"
          (is (= :rf/redacted (:event-vector summary))
              ":event-vector redacts to :rf/redacted for a sensitive epoch under gate OFF")
          (is (not= [:auth/login {:username "ada" :password "hunter2"}]
                    (:event-vector summary))
              "the raw trigger-event MUST NOT ride the wire")
          (is (not (clojure.string/includes? (pr-str summary) "hunter2"))
              "the password literal appears NOWHERE in the projected summary"))
        (testing "the sensitivity is still annotated for the consumer"
          (is (true? (:sensitive? summary))))))))

(deftest sensitive-event-vector-redacted-by-default-in-restore-cascade-summary
  ;; restore-epoch's projection — same gate, same redaction. RED before
  ;; the fix (restore-cascade-summary never read :rf.epoch/sensitive? and
  ;; copied the raw target trigger-event verbatim).
  (with-gate false
    (fn []
      (let [extras  (restore-cascade-summary sensitive-login-epoch)
            summary (:cascade-summary extras)]
        (is (= :rf/redacted (:event-vector summary))
            "restore-epoch's :event-vector redacts for a sensitive target epoch under gate OFF")
        (is (true? (:sensitive? summary))
            "restore-cascade-summary annotates :sensitive? true")
        (is (not (clojure.string/includes? (pr-str extras) "hunter2"))
            "the password literal appears NOWHERE in the restore projection")))))

;; ---------------------------------------------------------------------------
;; Negative guards — the redaction must NOT over-fire.
;; ---------------------------------------------------------------------------

(deftest benign-event-vector-rides-verbatim-regardless-of-gate
  ;; A non-sensitive epoch's trigger-event is safe — it must ride
  ;; verbatim whether the gate is ON or OFF.
  (doseq [gate [true false]]
    (with-gate gate
      (fn []
        (let [summary (cascade-summary benign-cart-epoch)]
          (is (= [:cart/add {:sku "x"}] (:event-vector summary))
              (str "benign :event-vector rides verbatim (gate=" gate ")"))
          (is (nil? (:sensitive? summary))
              "a non-sensitive epoch carries no :sensitive? slot"))))))

(deftest sensitive-event-vector-rides-verbatim-on-explicit-opt-in
  ;; Gate ON (operator launched with --allow-sensitive-reads) — the raw
  ;; vector rides through deliberately. The redaction is opt-in-reversible,
  ;; not a hard scrub.
  (with-gate true
    (fn []
      (let [summary (cascade-summary sensitive-login-epoch)]
        (is (= [:auth/login {:username "ada" :password "hunter2"}]
               (:event-vector summary))
            "gate ON ships the raw trigger-event (operator opted in)")
        (is (true? (:sensitive? summary))
            "the :sensitive? annotation still rides so the consumer knows")))))

;; ---------------------------------------------------------------------------
;; Structural pin — keep the bb mirror honest against the cljs source.
;; A regression that re-introduces the raw `(assoc :event-vector
;; trigger-event)` verbatim (bypassing `redact-sensitive-event-vector`)
;; trips these.
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

(deftest source-defines-redact-sensitive-event-vector
  (is (some? runtime-cljs-path) "runtime.cljs must be locatable")
  (let [form (top-level-named #{'defn 'defn-} 'redact-sensitive-event-vector)]
    (is (some? form)
        "runtime.cljs must define `redact-sensitive-event-vector` (rf2-6nks4 finding-2).")
    (is (form-contains? #(= :rf/redacted %) form)
        "the guard MUST substitute the :rf/redacted sentinel.")
    (is (form-contains? #(= :allow-raw-state? %) form)
        "the guard MUST consult the :allow-raw-state? gate (fail-closed default).")))

(deftest source-cascade-summary-routes-event-vector-through-guard
  (let [form (top-level-named #{'defn 'defn-} 'cascade-summary)]
    (is (some? form) "runtime.cljs must define `cascade-summary`")
    (is (form-contains?
          (fn [node] (and (seq? node)
                          (= 'redact-sensitive-event-vector (first node))))
          form)
        "cascade-summary MUST route :event-vector through redact-sensitive-event-vector — never a raw verbatim assoc (rf2-6nks4 finding-2).")))

(deftest source-restore-cascade-summary-routes-event-vector-through-guard
  (let [form (top-level-named #{'defn 'defn-} 'restore-cascade-summary)]
    (is (some? form) "runtime.cljs must define `restore-cascade-summary`")
    (is (form-contains?
          (fn [node] (and (seq? node)
                          (= 'redact-sensitive-event-vector (first node))))
          form)
        "restore-cascade-summary MUST route :event-vector through redact-sensitive-event-vector (rf2-6nks4 finding-2).")
    (is (form-contains? #(= :rf.epoch/sensitive? %) form)
        "restore-cascade-summary MUST read the target epoch's :rf.epoch/sensitive? rollup.")))

(let [{:keys [fail error]} (run-tests 'cascade-summary-redaction-test)]
  (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))
