(ns re-frame.machine-action-throw-rollback-test
  "A throwing PRE-COMMIT machine action routes through the documented
  machine action exception contract and rolls the fold back — it MUST NOT
  escape as a generic `:rf.error/handler-exception`.

  Every pre-commit callback the transition fold invokes (`:entry` / `:exit`
  / `:action`, and the `:spawn` `:data` fn — see
  `spawn_data_fn_form_test` §4) runs inside the machine layer's try/catch
  with Result conversion, so a throwing body is caught and routed through
  the machine-scoped `:rf.error/machine-action-exception` diagnostic and
  the frame-tagged trace, never aborting the whole event as an uncaught
  handler failure.

  The contract, verified here on a throwing `:entry`:

   1. A throwing pre-commit action emits EXACTLY ONE
      `:rf.error/machine-action-exception` (op-type `:error`), frame-tagged
      and carrying the originating throwable.
   2. NO generic `:rf.error/handler-exception` is emitted — the throw is
      caught inside the machine layer, not at the core dispatch boundary.
   3. ATOMIC ROLLBACK: the transition does not commit — the accumulated
      effects are dropped and the parent snapshot is left at its
      pre-transition state.

  Runs on the JVM through the plain-atom substrate, exercising the full
  lifecycle handler."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- action-exceptions
  "The `:rf.error/machine-action-exception` envelopes among `traces`."
  [traces]
  (filter #(and (= :error (:op-type %))
                (= :rf.error/machine-action-exception (:operation %)))
          traces))

(defn- handler-exceptions
  "The generic `:rf.error/handler-exception` envelopes among `traces` — the
  throw must NOT reach the core dispatch boundary, so this is expected to
  be empty."
  [traces]
  (filter #(= :rf.error/handler-exception (:operation %)) traces))

(deftest throwing-entry-routes-to-machine-action-exception-and-rolls-back
  (testing "a throwing :entry action emits exactly one frame-tagged
   :rf.error/machine-action-exception, NO generic
   :rf.error/handler-exception, and commits no transition"
    (rf/reg-machine :sup/entry-throw
      {:initial :idle
       :actions {:boom (fn [_] (throw (ex-info "entry boom" {:why :test})))}
       :states  {:idle    {:on {:start :working}}
                 :working {:entry :boom}}})
    (rf.machines.test-support/with-trace-capture traces
      (rf/dispatch-sync [:sup/entry-throw [:start]])
      (let [errs (action-exceptions @traces)]
        (is (= 1 (count errs))
            "exactly one :rf.error/machine-action-exception for the throw")
        (let [{:keys [tags]} (first errs)]
          (is (= :sup/entry-throw (:actor-id tags))
              "the error is scoped to the throwing LIVE actor")
          (is (= :rf/default (:frame tags))
              "the error is frame-tagged (epoch-capture admission)")
          (is (some? (:exception tags))
              "the originating throwable rides the error trace"))
        (is (zero? (count (handler-exceptions @traces)))
            "NO generic :rf.error/handler-exception — caught in the machine layer")
        (let [snap (snapshot :sup/entry-throw)]
          (is (or (nil? snap) (= :idle (:state snap)))
              "the transition did not commit — the fold rolled back"))))))

(deftest non-throwing-entry-commits-cleanly
  (testing "the try/catch wrap is transparent on the happy path — a quiet
   :entry action runs, the transition commits, and NO error trace fires"
    (let [seen (atom nil)]
      (rf/reg-machine :sup/entry-quiet
        {:initial :idle
         :actions {:note (fn [{data :data}] (reset! seen :ran) data)}
         :states  {:idle    {:on {:start :working}}
                   :working {:entry :note}}})
      (rf.machines.test-support/with-trace-capture traces
        (rf/dispatch-sync [:sup/entry-quiet [:start]])
        (is (= :ran @seen) "the :entry action ran on the happy path")
        (is (= :working (:state (snapshot :sup/entry-quiet)))
            "the transition committed — the success path is unchanged")
        (is (zero? (count (action-exceptions @traces)))
            "no machine-action-exception when the :entry action does not throw")
        (is (zero? (count (handler-exceptions @traces)))
            "no handler-exception on the happy path")))))
