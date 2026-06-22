;;;; tests/runtime/cascade_summary_redaction_test.clj
;;;;
;;;; Babashka-runnable verification of the cascade-summary `:event-vector`
;;;; egress redaction in `preload/re_frame2_pair/runtime.cljs`.
;;;;
;;;; THE CONTRACT: `cascade-summary` / `restore-cascade-summary` copy the
;;;; epoch's RAW `:trigger-event` into the projection's `:event-vector`
;;;; slot, and the MCP `restore-epoch` + `dispatch-dry-run` tools pass that
;;;; projection through (restore-epoch ships the runtime map verbatim;
;;;; dispatch-dry-run DELIBERATELY does not walk `:cascade-summary`). The
;;;; wire-path elision walker scrubs dispatch-dry-run's
;;;; `:db-state-after-simulation` / `:would-fire-effects`, but the
;;;; cascade-summary `:event-vector` rides outside that walk — so without a
;;;; guard a restore / dry-run of a SENSITIVE historical epoch would return
;;;; the raw event payload (auth tokens, passwords, …) even under
;;;; `--allow-sensitive-reads` OFF.
;;;;
;;;; THE MECHANISM (rf2-nm611o / rf2-6klf02): `redact-sensitive-event-vector`
;;;; FAILS CLOSED on the trigger-event ARGS — the SAME projection the
;;;; framework's `projected-record` applies to a record's `:trigger-event`
;;;; slot (`epoch/tool_pair.cljc` §`elide-trigger-event-slot`). The event
;;;; args are registration-owned transient payloads the app-db
;;;; classification walker cannot prove safe, so under the OFF gate (the
;;;; published-build default — the MCP server signals `configure-raw-state!
;;;; {:allow-raw-state? false}` before restore-epoch / dispatch-dry-run
;;;; build the summary) the head event-id is RETAINED while every arg is
;;;; replaced with `:rf/redacted`: `[:login "topsecret"]` egresses as
;;;; `[:login :rf/redacted]`. The fail-close fires on EVERY epoch —
;;;; sensitive or NOT — because a secret carried IN the dispatch vector is
;;;; unprovable regardless of whether the epoch is declared
;;;; `:rf.epoch/sensitive?` (the old guard keyed redaction to the rollup
;;;; alone, so a non-declared trigger-event leaked — rf2-6klf02). Gate ON
;;;; (operator opted in via `--allow-sensitive-reads`) ships the raw
;;;; vector. A degenerate non-vector / empty slot redacts wholesale.
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
  "Mirror of the egress guard (rf2-nm611o fail-closed shape). Under the
  OFF gate the head event-id is RETAINED while every arg is replaced with
  `:rf/redacted` — for EVERY epoch, sensitive or not (the args are
  registration-owned transient payloads the classification walker cannot
  prove safe). Gate ON ships the raw vector (operator opt-in). A
  degenerate non-vector / empty / nil slot redacts wholesale / passes
  through nil."
  [trigger-event sensitive?]
  (cond
    (:allow-raw-state? @raw-state-config) trigger-event
    (nil? trigger-event)                  trigger-event
    (and (vector? trigger-event) (seq trigger-event))
    (into [(first trigger-event)]
          (repeat (dec (count trigger-event)) :rf/redacted))
    :else :rf/redacted))

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

;; A NON-sensitive epoch — an ordinary cart-add carrying NO secret. The
;; head + args are safe; under gate OFF the args still fail closed (the
;; walker can't prove ANY trigger-event arg safe), so the projected shape
;; keeps the head and redacts the (innocuous) arg. Gate ON rides verbatim.
(def benign-cart-epoch
  {:epoch-id 7
   :event-id :cart/add
   :trigger-event [:cart/add {:sku "x"}]
   :frame :rf/default
   :db-before {:cart {}}
   :db-after  {:cart {:items 1}}
   :effects   []})

;; rf2-6klf02 — a NON-declared sensitive epoch whose trigger-event carries
;; a secret POSITIONALLY in the dispatch vector. The framework did NOT
;; stamp `:rf.epoch/sensitive?` (no declared-sensitive db slot matched —
;; the secret lives only in the event args, which the app-db classification
;; walker cannot see). The old guard, keyed to the `:rf.epoch/sensitive?`
;; rollup alone, shipped this raw off-box — the leak this bead closes.
(def undeclared-secret-login-epoch
  {:epoch-id 99
   :event-id :login
   :trigger-event [:login "topsecret"]
   :frame :rf/default
   ;; NB: NO :rf.epoch/sensitive? — the secret is non-declared.
   :db-before {}
   :db-after  {:auth {:ok true}}
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
  ;; signals it) — the sensitive trigger-event MUST fail closed, not leak.
  ;; rf2-nm611o shape: head event-id retained, every arg :rf/redacted.
  (with-gate false
    (fn []
      (let [summary (cascade-summary sensitive-login-epoch)]
        (testing "the raw password never appears on the wire"
          (is (= [:auth/login :rf/redacted] (:event-vector summary))
              ":event-vector fails closed — head retained, arg redacted (gate OFF)")
          (is (not= [:auth/login {:username "ada" :password "hunter2"}]
                    (:event-vector summary))
              "the raw trigger-event MUST NOT ride the wire")
          (is (not (clojure.string/includes? (pr-str summary) "hunter2"))
              "the password literal appears NOWHERE in the projected summary")
          (is (not (clojure.string/includes? (pr-str summary) "ada"))
              "the username literal also appears nowhere — every arg redacts"))
        (testing "the sensitivity is still annotated for the consumer"
          (is (true? (:sensitive? summary))))))))

(deftest undeclared-secret-event-vector-fails-closed-in-cascade-summary
  ;; rf2-6klf02 — THE leak this bead closes. An epoch NOT stamped
  ;; `:rf.epoch/sensitive?` whose trigger-event carries a secret in its
  ;; args MUST still fail closed off-box: the old guard keyed redaction to
  ;; the rollup alone, so a non-declared trigger-event shipped the secret
  ;; raw. The args are unprovable regardless of the epoch's sensitivity.
  (with-gate false
    (fn []
      (let [summary (cascade-summary undeclared-secret-login-epoch)]
        (is (= [:login :rf/redacted] (:event-vector summary))
            ":event-vector fails closed even for a NON-declared-sensitive epoch")
        (is (not (clojure.string/includes? (pr-str summary) "topsecret"))
            "the non-declared positional secret appears NOWHERE on the wire")
        (is (nil? (:sensitive? summary))
            "the epoch was not declared sensitive — no :sensitive? annotation, yet args still redact")))))

(deftest sensitive-event-vector-redacted-by-default-in-restore-cascade-summary
  ;; restore-epoch's projection — same gate, same redaction. RED before
  ;; the fix (restore-cascade-summary never read :rf.epoch/sensitive? and
  ;; copied the raw target trigger-event verbatim).
  (with-gate false
    (fn []
      (let [extras  (restore-cascade-summary sensitive-login-epoch)
            summary (:cascade-summary extras)]
        (is (= [:auth/login :rf/redacted] (:event-vector summary))
            "restore-epoch's :event-vector fails closed (head retained, arg redacted) under gate OFF")
        (is (true? (:sensitive? summary))
            "restore-cascade-summary annotates :sensitive? true")
        (is (not (clojure.string/includes? (pr-str extras) "hunter2"))
            "the password literal appears NOWHERE in the restore projection")))))

;; ---------------------------------------------------------------------------
;; Negative guards — the redaction must NOT over-fire.
;; ---------------------------------------------------------------------------

(deftest benign-event-vector-rides-verbatim-on-opt-in-fails-closed-by-default
  ;; A non-sensitive epoch's trigger-event args are STILL unprovable, so
  ;; under gate OFF they fail closed (head retained) — the fail-close is
  ;; uniform across sensitive and non-sensitive epochs. Gate ON (operator
  ;; opted in) rides the full vector verbatim.
  (with-gate false
    (fn []
      (let [summary (cascade-summary benign-cart-epoch)]
        (is (= [:cart/add :rf/redacted] (:event-vector summary))
            "gate OFF fails closed even for a benign epoch — head retained, arg redacted")
        (is (nil? (:sensitive? summary))
            "a non-sensitive epoch carries no :sensitive? slot"))))
  (with-gate true
    (fn []
      (let [summary (cascade-summary benign-cart-epoch)]
        (is (= [:cart/add {:sku "x"}] (:event-vector summary))
            "gate ON rides the benign :event-vector verbatim (operator opted in)")
        (is (nil? (:sensitive? summary))
            "a non-sensitive epoch carries no :sensitive? slot")))))

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

;; Shared locate+parse+walk scaffold lives in tests/runtime/_support.clj.
;; Alias the vars the assertions below use.
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
