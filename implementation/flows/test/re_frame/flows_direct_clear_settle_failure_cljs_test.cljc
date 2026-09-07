(ns re-frame.flows-direct-clear-settle-failure-cljs-test
  "Cross-host coverage for Spec 013 §The same boundary for a direct
  `clear-flow`, point 2 — the FAILURE half of the boundary its sibling
  `re-frame.flows-direct-clear-settle-cljs-test` pins on the success path.

  When a direct `clear-flow`'s settle hits a remaining flow whose `:derive`
  throws — or whose returned value the framework cannot install — the spec
  says the deregistration and vacation STAND (they are already committed, and
  are what the caller asked for), the settle's candidate app-db is discarded
  unwritten, and the ordinary `:rf.error/flow-eval-exception` propagates to
  the direct caller. No rollback, no new error category.

  ## What was wrong, and why a message is worth a test file

  The behaviour above was correct from the day the direct settle landed. The
  DIAGNOSTIC was not. `settle-frame-flows!` runs the ordinary
  `run-flows-on-db`, and its failure sentence was written for the only caller
  that then existed — the router. So the exception handed to a direct
  `clear-flow` caller asserted three things that are each false for it:

    - that the evaluation ran \"during the drain\" — no drain ran, and there
      was no event at all;
    - that \"the event aborts before the :db install\" — nothing aborted; the
      clear completed and its flow is deregistered;
    - that app-db is \"unchanged\" — app-db DID change, because the cleared
      flow's output leaf was vacated and committed before the settle began.

  A wrong message on a failure path is not cosmetic: it is read at exactly the
  moment the reader has least context, and each of those three claims sends
  them somewhere real but wrong — hunting an event id that was never
  dispatched, or trusting an app-db snapshot that has already moved. Every
  assertion below is therefore made against the sentence a caller actually
  receives, not against behaviour alone.

  The repair is confined to the sentence. The error id, the `:derive` /
  `:output-write` phase discrimination, the trace tags and the thrown ex-data
  are identical for every caller, and the tests here assert that identity as
  hard as they assert the difference — a message fix that quietly forked the
  taxonomy would be a worse bug than the one it replaced.

  ## Shape

  Each test observes the exception the DIRECT CALL raises, by catching it: a
  direct `clear-flow` is a plain synchronous function, so its failure arrives
  on the caller's own stack. The in-drain control at the end reads the same
  failure through the always-on `:errors` listener instead, because the router
  absorbs a flow throw rather than re-raising it at `dispatch-sync`.

  This file is `*-cljs-test.cljc` so the shadow-cljs `:node-test` build
  (ns-regexp `cljs-test$`) discovers it AND the cognitect JVM runner runs it
  (the `-test` suffix). Both hosts matter here: the `:output-write` fixture
  relies on `assoc` refusing a keyword key on a vector, which throws on each
  (`IllegalArgumentException` on the JVM, `js/Error` on CLJS), and the
  lifecycle code under test is all `.cljc`.

  `re-frame.core/app-db-value` is a pure deref of the frame's app-db
  projection through the substrate adapter, so the observation itself cannot
  trigger a pass."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.flows :as rf.flows]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

(defn- caught
  "Run `f` and return what it threw, or `::no-throw`."
  [f]
  (try (f) ::no-throw
       (catch #?(:clj Throwable :cljs :default) e e)))

(defn- registered?
  [frame-id flow-id]
  (contains? (get (rf.flows/flows-snapshot) frame-id) flow-id))

;; ---------------------------------------------------------------------------
;; 1. `:derive` throws while a direct clear settles.
;; ---------------------------------------------------------------------------

(deftest direct-clear-settle-derive-throw-reports-the-direct-boundary
  (testing "the exception a direct clear-flow raises describes ITS boundary —
            the clear stands, the settle candidate is dropped — and never the
            drain's abort, which did not happen"
    (let [derives (atom 0)]
      (rf/reg-event :seed (fn [_ _] {:db {:x 2}}))
      (rf/reg-flow :probe/a {:inputs [[:x]] :output-path [:a]} (fn [x] x))
      ;; B is total on 2 and throws on A's absence, so it survives the seeding
      ;; drain and fails only inside the settle the clear performs.
      (rf/reg-flow :probe/b {:inputs [[:a]] :output-path [:b]}
        (fn [a]
          (swap! derives inc)
          (if (nil? a) (throw (ex-info "dependent refuses nil" {})) a)))
      (rf/dispatch-sync [:seed])
      (is (= {:x 2 :a 2 :b 2} (rf/app-db-value :rf/default))
          "precondition — both flows materialised on the seeding drain")

      (let [derives-before @derives
            thrown         (caught #(rf/clear :flow :probe/a))
            observed       (rf/app-db-value :rf/default)]

        (is (not= ::no-throw thrown)
            "the settle's failure propagates to the direct caller rather than
             being swallowed")
        (is (= 1 (- @derives derives-before))
            "the dependent was evaluated exactly once, inside the clear — this
             counter is what makes the assertions below non-vacuous")

        ;; --- the taxonomy is UNCHANGED -----------------------------------
        (let [data (ex-data thrown)]
          (is (= :rf.error/flow-eval-exception (:rf.error/id data))
              "the existing aggregate category is reused — a direct caller's
               failure is not a new error id")
          (is (= :probe/b (:rf.flow/failed-id data))
              "flow attribution is unchanged")
          (is (= :derive (:rf.flow/failed-phase data))
              "the phase discriminator is unchanged: the authored :derive fn
               threw")
          (is (= [:b] (:rf.flow/output-path data))
              "the failing flow's declared output path rides the ex-data")
          (is (some? (:cause data))
              "the original exception is preserved under :cause")
          (is (= :no-recovery (:recovery data))
              "the recovery classification is unchanged"))

        ;; --- the SENTENCE is context-aware -------------------------------
        (let [msg (:reason (ex-data thrown))]
          (is (some? (re-find #"clear-flow" msg))
              "the message names the call the reader actually made")
          (is (nil? (re-find #"during the drain" msg))
              "it does not claim a drain ran — none did")
          (is (nil? (re-find #"the event aborts" msg))
              "it does not claim an event aborted — there was no event")
          (is (nil? (re-find #"app-db unchanged" msg))
              "it does not claim app-db is unchanged — the vacation committed
               before the settle began, so app-db HAS moved")
          (is (some? (re-find #"stands" msg))
              "it says the clear stands, which is the fact the reader needs
               before deciding what to do next")
          (is (some? (re-find #"discarded unwritten" msg))
              "and that the settle's candidate db was dropped")
          (is (some? (re-find #"Fix the :derive fn" msg))
              "the actionable advice for a :derive throw is unchanged"))

        ;; --- the intended POST-FAILURE state -----------------------------
        (is (not (registered? :rf/default :probe/a))
            "the clear STANDS — its flow is deregistered, not rolled back")
        (is (not (contains? observed :a))
            "and its output leaf stays vacated")
        (is (= 2 (:b observed))
            "the settle candidate is unwritten: the dependent's slot keeps its
             pre-clear value rather than a partial result")
        (is (= {:x 2 :b 2} observed)
            "nothing else moved"))

      ;; --- the dependent RETRIES later --------------------------------
      ;; The dirty-check rollback inside `run-flows-on-db` is what makes the
      ;; failure recoverable rather than terminal: B's `last-inputs` did not
      ;; advance, so the next ordinary drain re-attempts it. Re-registering B
      ;; with a total derive is how an application would actually recover.
      (rf/reg-flow :probe/b {:inputs [[:a]] :output-path [:b]}
        (fn [a] (if (nil? a) :repaired a)))
      (rf/reg-event :unrelated-no-op (fn [_ _] {}))
      (rf/dispatch-sync [:unrelated-no-op])
      (is (= {:x 2 :b :repaired} (rf/app-db-value :rf/default))
          "the next drain re-attempts the flow the settle could not complete —
           the failure deferred the recompute, it did not abandon it"))))

;; ---------------------------------------------------------------------------
;; 2. The framework's own output write throws while a direct clear settles.
;;    The bead's note records this branch carrying the same false wording.
;; ---------------------------------------------------------------------------

(deftest direct-clear-settle-output-write-throw-reports-the-direct-boundary
  (testing "an :output-write failure inside a direct clear's settle reports the
            direct boundary too, keeps phase :output-write, and still refuses
            to blame the :derive fn"
    (let [derives (atom 0)]
      ;; B installs under [:v :k]. `:v` is seeded as a MAP so B's first install
      ;; succeeds; `:vectorise` then swaps it for a VECTOR without touching B's
      ;; input, so B is dirty-check clean and writes nothing on that drain.
      ;; The clear changes B's input to nil, B derives fine, and the framework's
      ;; install of the result under a keyword segment of a vector throws.
      (rf/reg-event :seed (fn [_ _] {:db {:x 2 :v {}}}))
      (rf/reg-event :vectorise (fn [{:keys [db]} _] {:db (assoc db :v [1 2])}))
      (rf/reg-flow :probe/a {:inputs [[:x]] :output-path [:a]} (fn [x] x))
      (rf/reg-flow :probe/b {:inputs [[:a]] :output-path [:v :k]}
        (fn [a] (swap! derives inc) a))
      (rf/dispatch-sync [:seed])
      (rf/dispatch-sync [:vectorise])
      (is (= {:x 2 :v [1 2] :a 2} (rf/app-db-value :rf/default))
          "precondition — [:v] is now a vector and B did not rewrite it")

      (let [derives-before @derives
            thrown         (caught #(rf/clear :flow :probe/a))
            observed       (rf/app-db-value :rf/default)]

        (is (not= ::no-throw thrown)
            "the install failure propagates to the direct caller")
        (is (= 1 (- @derives derives-before))
            "the :derive fn RAN and RETURNED — the failure is the write that
             followed it, which is what makes the attribution assertions below
             meaningful")

        (let [data (ex-data thrown)
              msg  (:reason data)]
          (is (= :rf.error/flow-eval-exception (:rf.error/id data))
              "same aggregate category")
          (is (= :output-write (:rf.flow/failed-phase data))
              "the phase still names the framework's write, not the authored
               callback")
          (is (= [:v :k] (:rf.flow/output-path data))
              "the declared output path rides the ex-data")

          (is (nil? (re-find #":derive fn threw" msg))
              "the message does not claim the :derive fn threw (rf2-gpj9r)")
          (is (nil? (re-find #"Fix the :derive fn" msg))
              "nor advise fixing it")
          (is (nil? (re-find #"the event aborts" msg))
              "and it no longer claims an event aborted — there was no event")
          (is (nil? (re-find #"app-db unchanged" msg))
              "nor that app-db is unchanged")
          (is (nil? (re-find #"pending app-db" msg))
              "nor that the container it could not write sits in a PENDING
               app-db — a direct settle recomputes against the committed one")
          (is (some? (re-find #"clear-flow" msg))
              "it names the call the reader made")
          (is (some? (re-find #"\[:v :k\]" msg))
              "and quotes the output path that could not be written"))

        (is (not (registered? :rf/default :probe/a))
            "the clear stands")
        (is (= {:x 2 :v [1 2]} observed)
            "the vacation committed and the settle candidate did not: [:v]
             still holds the vector, with no partial write under it")))))

;; ---------------------------------------------------------------------------
;; 3. Control — the DRAIN's wording is untouched.
;;    Without this, "the direct message no longer says 'during the drain'"
;;    would also be satisfied by deleting the drain's sentence outright.
;; ---------------------------------------------------------------------------

(deftest in-drain-failure-keeps-the-drain-wording
  (testing "the same failure reached through the router still describes the
            event's abort — the fix narrows the drain's sentence to the drain,
            it does not retire it"
    (let [errors (atom [])]
      (rf/register-listener! :errors ::error-recorder
                             (fn [record] (swap! errors conj record)))
      (rf/reg-event :seed (fn [_ _] {:db {:x 2}}))
      (rf/reg-event :bump (fn [{:keys [db]} _] {:db (assoc db :x 3)}))
      (rf/reg-flow :probe/a {:inputs [[:x]] :output-path [:a]}
        (fn [x] (if (= 3 x) (throw (ex-info "boom" {})) x)))
      (rf/dispatch-sync [:seed])
      (let [before (rf/app-db-value :rf/default)]
        (reset! errors [])
        (rf/dispatch-sync [:bump])

        (is (= 1 (count @errors))
            "exactly one always-on error record fired — the control is reading
             a real failure, not an empty sink")
        (let [record (first @errors)
              msg    (:reason (ex-data (:exception record)))]
          (is (= :rf.error/flow-eval-exception (:error record))
              "same category through the drain")
          (is (some? (re-find #"during the drain" msg))
              "the drain still says so")
          (is (some? (re-find #"the event aborts before the :db install" msg))
              "and still states the event's abort, which IS true here")
          (is (some? (re-find #"app-db unchanged" msg))
              "and that app-db is unchanged, which is also true here")
          (is (nil? (re-find #"clear-flow" msg))
              "and does not mention a clear-flow the caller never made"))
        (is (= before (rf/app-db-value :rf/default))
            "the drain's atomicity is untouched: the aborted event installed
             nothing")))))
