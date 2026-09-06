(ns re-frame.flows-output-write-attribution-cljs-test
  "rf2-gpj9r — a flow's output-path WRITE failure is attributed to the write,
  not to the application's `:derive` fn.

  `evaluate-flow!` used to wrap the whole recompute-and-install block in ONE
  `try`, so a `:derive` fn that returned perfectly good value could still be
  blamed for a throw raised afterwards by the framework's own
  `(assoc-in db (:output-path flow) new-output)`. Both the dev-only
  `:rf.flow/failed` trace and the always-on production error record said \"a
  flow's :derive fn threw\" and \"Fix the :derive fn\" — sending the programmer
  to code that ran to completion, and concealing the output-path / container
  mismatch that actually aborted the event.

  The repair is STRUCTURAL rather than message-sniffing: the authored callback
  and the framework install now sit in separate `try` forms, so an install
  failure is unreachable from the derive handler. The phase (`:derive` /
  `:output-write`) rides the `:rf.flow/failed` trace, the thrown ex-data
  (`:rf.flow/failed-phase`, `:rf.flow/output-path`) and — via the router's
  `emit-flow-eval-exception!` — the always-on error record's `:phase` slot, so
  a production monitor sees the honest attribution too.

  This file is `*-cljs-test.cljc` so the shadow-cljs `:node-test` build
  (ns-regexp `cljs-test$`) discovers it AND the JVM runner runs it: `assoc` on
  a vector with a keyword key throws on BOTH hosts (`IllegalArgumentException`
  \"Key must be integer\" on the JVM; `js/Error` \"Vector's key for assoc must
  be a number.\" on CLJS), so one fixture exercises the boundary on both."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.trace :as rf.trace]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

;; ---------------------------------------------------------------------------
;; Recorders.  The reset fixture clears both registries between tests, so each
;; test's recorders start empty and need no teardown.
;; ---------------------------------------------------------------------------

(defn- record-flow-traces!
  "Capture every `:flow`-op-type trace event into `sink`."
  [sink]
  (rf.trace/register-listener!
    ::flow-trace-recorder
    (fn [ev]
      (when (= :flow (:op-type ev))
        (swap! sink conj ev)))))

(defn- record-errors!
  "Capture every always-on error-emit record into `sink`.  This is the
  PRODUCTION-surviving axis — it fires under CLJS `:advanced` +
  `goog.DEBUG=false`, where the trace surface above is compile-time elided —
  so asserting it is what stops production attribution silently staying wrong."
  [sink]
  (rf/register-listener! :errors ::error-recorder
                         (fn [record] (swap! sink conj record))))

(defn- by-op [events op]
  (filterv #(= op (:operation %)) events))

;; The scenario the bead specifies: app-db holds a VECTOR at `[:items]`, and a
;; flow declares the leaf `[:items :total]`.  Every segment is legal, so
;; registration correctly accepts it (it cannot prove the shape of a future
;; pending app-db); the mismatch only bites at install time.
(def ^:private output-path [:items :total])

(defn- seed!
  "Install `{:source 1 :items <items>}` with no flow registered yet, so the
  first flow evaluation happens on the LATER `:bump` drain."
  [items]
  (rf/reg-event :init (fn [_ _] {:db {:source 1 :items items}}))
  (rf/reg-event :bump (fn [{:keys [db]} _] {:db (assoc db :source 2)}))
  (rf/dispatch-sync [:init]))

;; ---------------------------------------------------------------------------
;; 1. The repro: derive RETURNS, the output write throws.
;; ---------------------------------------------------------------------------

(deftest output-write-failure-is-attributed-to-the-write-not-to-derive
  (testing "rf2-gpj9r — a successful :derive followed by a failing output
            install reports phase :output-write on both the dev trace and the
            always-on error record, and never advises fixing :derive"
    (let [traces (atom [])
          errors (atom [])
          calls  (atom 0)]
      (record-flow-traces! traces)
      (record-errors! errors)
      (seed! [])
      (rf/reg-flow :flow/output-write
                   {:inputs [[:source]] :output-path output-path}
                   (fn [_source] (swap! calls inc) 42))
      (let [before (rf/app-db-value :rf/default)]
        (rf/dispatch-sync [:bump])

        ;; --- non-vacuity: derive really ran, and really returned -----------
        (is (= 1 @calls)
            "the :derive fn was called exactly once and RETURNED — the failure
             is not a derive throw, and this counter is what makes every
             assertion below non-vacuous")

        ;; --- atomicity is untouched ---------------------------------------
        (is (= before (rf/app-db-value :rf/default))
            "the event aborted before the :db install — app-db is unchanged
             (the :bump handler's own write did not land either)")

        ;; --- the dev-only per-flow trace ----------------------------------
        (let [failed (last (by-op @traces :rf.flow/failed))
              tags   (:tags failed)]
          (is (some? failed) ":rf.flow/failed fired for the failing flow")
          (is (= :flow/output-write (:flow-id tags))
              "the trace attributes the failure to the right flow")
          (is (= :output-write (:phase tags))
              "the trace names the phase that ACTUALLY threw — the framework's
               output write, not the application's :derive fn")
          (is (= output-path (:path tags))
              "the structural output path rides the trace, so the programmer
               can see which declared path could not be written")
          (is (= :rf/default (:frame tags))
              "frame attribution is preserved"))

        ;; --- the always-on production error record ------------------------
        (is (= 1 (count @errors))
            "exactly one always-on record fired for one flow-eval failure")
        (let [r (first @errors)]
          (is (= :rf.error/flow-eval-exception (:error r))
              ":rf.error/flow-eval-exception remains the aggregate
               flow-evaluation category — no new public error id")
          (is (= :flow-eval (:where r)) ":where still discriminates the path")
          (is (= :flow/output-write (:flow-id r))
              "flow attribution rides the production record")
          (is (= :output-write (:phase r))
              "the PHASE rides the production record too — an off-box monitor
               reading this in an :advanced build is told the output write
               failed, not that the application's :derive fn threw")
          ;; The attribution must survive an egress profile that strips
          ;; :exception, exactly as :where / :flow-id already do.
          (let [public (dissoc r :exception)]
            (is (= :output-write (:phase public))
                ":phase survives an :exception-dropping egress profile")))

        ;; --- the thrown diagnostic ----------------------------------------
        (let [thrown (:exception (first @errors))
              data   (ex-data thrown)
              msg    (ex-message thrown)]
          (is (= :flow/output-write (:rf.flow/failed-id data))
              "the existing flow-attribution slot is unchanged")
          (is (= :output-write (:rf.flow/failed-phase data))
              "the phase is machine-readable off the thrown ex-data")
          (is (= output-path (:rf.flow/output-path data))
              "the declared output path is machine-readable off the ex-data")
          (is (some? (:cause data))
              "the original install exception is preserved under :cause")
          ;; The whole point of the bead: the human sentence must not send the
          ;; programmer to working code.
          (is (nil? (re-find #":derive fn threw" msg))
              "the message does not CLAIM the :derive fn threw")
          (is (nil? (re-find #"Fix the :derive fn" msg))
              "the message does not ADVISE fixing the :derive fn")
          (is (some? (re-find #":output-path" msg))
              "the message names the output write as the failing phase")
          (is (some? (re-find (re-pattern (pr-str output-path)) msg))
              "the message quotes the declared output path that could not be
               written"))))))

;; ---------------------------------------------------------------------------
;; 2. Negative control: :derive itself throws, BEFORE any output install.
;;    The pre-existing contract must be untouched — same error id, same
;;    message, flow attribution, atomic abort — now with phase :derive and no
;;    output-write attribution anywhere.
;; ---------------------------------------------------------------------------

(deftest derive-throw-still-reports-phase-derive
  (testing "rf2-gpj9r negative control — when :derive itself throws, the
            existing :rf.error/flow-eval-exception contract still holds and the
            phase is :derive"
    (let [traces (atom [])
          errors (atom [])]
      (record-flow-traces! traces)
      (record-errors! errors)
      (seed! {})
      (rf/reg-flow :flow/derive-throws
                   {:inputs [[:source]] :output-path output-path}
                   (fn [_source] (throw (ex-info "derive boom" {:why :test}))))
      (let [before (rf/app-db-value :rf/default)]
        (rf/dispatch-sync [:bump])

        (is (= before (rf/app-db-value :rf/default))
            "atomic abort is unchanged for a derive throw")

        (let [tags (:tags (last (by-op @traces :rf.flow/failed)))]
          (is (= :flow/derive-throws (:flow-id tags)) "flow attribution")
          (is (= :derive (:phase tags))
              "the trace names :derive — the authored callback really did throw")
          (is (= "derive boom" (:exception-message tags))
              "the structured exception summary is unchanged")
          (is (= {:why :test} (:exception-data tags))
              "the ex-data summary is unchanged"))

        (is (= 1 (count @errors)) "one always-on record")
        (let [r      (first @errors)
              data   (ex-data (:exception r))
              msg    (ex-message (:exception r))]
          (is (= :rf.error/flow-eval-exception (:error r))
              "the same aggregate category")
          (is (= :flow/derive-throws (:flow-id r)) "flow attribution")
          (is (= :derive (:phase r)) "the production record names :derive")
          (is (= :flow/derive-throws (:rf.flow/failed-id data))
              "the ex-data attribution slot is unchanged")
          (is (= :derive (:rf.flow/failed-phase data))
              "the ex-data phase is :derive")
          (is (= :no-recovery (:recovery data))
              "the catalogued disposition is unchanged")
          ;; The pre-existing derive-throw wording is preserved verbatim.
          (is (some? (re-find #":derive fn threw" msg))
              "the derive-throw message is unchanged — it still says the
               :derive fn threw, because here it did")
          (is (some? (re-find #"Fix the :derive fn" msg))
              "and still advises fixing it")
          (is (nil? (re-find #"output-write" msg))
              "no output-write attribution leaks onto a genuine derive throw"))))))

;; ---------------------------------------------------------------------------
;; 3. Positive control: the SAME derive and the SAME output path, over a MAP at
;;    `[:items]`.  Nothing about the repair may make a legal write fail.
;; ---------------------------------------------------------------------------

(deftest legal-output-write-commits-and-emits-no-failure
  (testing "rf2-gpj9r positive control — the same [:items :total] output path
            over a map commits, advances dirty-check state, and emits no
            failure on either axis"
    (let [traces (atom [])
          errors (atom [])
          calls  (atom 0)]
      (record-flow-traces! traces)
      (record-errors! errors)
      (seed! {})
      (rf/reg-flow :flow/output-write
                   {:inputs [[:source]] :output-path output-path}
                   (fn [_source] (swap! calls inc) 42))
      (rf/dispatch-sync [:bump])

      (is (= 1 @calls) ":derive ran once")
      (is (= 42 (get-in (rf/app-db-value :rf/default) output-path))
          "the derived value COMMITTED at the declared output path")
      (is (= 2 (:source (rf/app-db-value :rf/default)))
          "the handler's own :db write committed alongside it")
      (is (empty? (by-op @traces :rf.flow/failed))
          "no :rf.flow/failed trace")
      (is (empty? @errors)
          "no always-on error record")
      (is (seq (by-op @traces :rf.flow/computed))
          ":rf.flow/computed fired for the successful evaluation")

      ;; Dirty-check state advanced: a second drain that leaves the flow's
      ;; inputs value-equal SKIPS the recompute rather than re-running it.
      (reset! traces [])
      (rf/dispatch-sync [:bump])
      (is (= 1 @calls)
          ":derive was NOT re-run — last-inputs was recorded by the successful
           install, so the value-equal input short-circuits")
      (let [skip (last (by-op @traces :rf.flow/skip))]
        (is (some? skip) ":rf.flow/skip fired on the second drain")
        (is (= :inputs-value-equal (:reason (:tags skip)))
            "the skip is the dirty-check short-circuit")))))
