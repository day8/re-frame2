(ns re-frame.story.play.step-runner-test
  "The tagged setup/script step runner (NewTestStory rf2-5x1wt.17,
  spec/017-Testing-Story.md §Script step grammar + §Setup and script).

  Three layers, all under `clojure -M:test` (JVM) + the node-runtime CLJS
  build:

  - PURE grammar — `runner/step-types` / `step-arity-ok?` / `coerce-script`
    / `step-assertion` / `step-wait-until` recognise the tagged steps
    (`[:dispatch]` / `[:wait-until]` / `[:wait]` / `[:assert]` / `[:focus]`)
    and lift bare event-vector shorthand to `[:dispatch …]` during
    migration. No re-frame dep.
  - PLAN-COMPILE rejection — an `[:assert …]` checkpoint in `:setup` FAILS
    plan construction (`re-frame.story.plan`) with
    `:rf.error/story-assert-in-setup`.
  - HEADLESS execution against a live frame — `runner-events/exec-step!`
    drives a tagged `[:dispatch …]` through `settled-boundary`, settles a
    `[:wait-until pred]` on a queue/state predicate (timing out readably),
    records an `[:assert …]` checkpoint at its point in the script, and
    REFUSES `[:focus …]` headless with `:cannot-run`.

  The headless execution layer drives `exec-step!` directly (a private
  var reached via var-quote — the established Story-test seam) so a single
  step's behaviour is observable without standing up the async run loop."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core              :as rf]
            [re-frame.frame             :as frame]
            [re-frame.registrar         :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story             :as story]
            [re-frame.story.plan        :as plan]
            [re-frame.story.play.runner :as runner]
            [re-frame.story.play.runner-events :as re]
            [re-frame.story.play.settled-boundary :as boundary]
            [re-frame.story.requirements :as requirements]))

;; ===========================================================================
;; PURE: the tagged step grammar
;; ===========================================================================

(deftest step-types-include-new-tags
  (testing "the one tagged grammar recognises :assert / :wait-until / :focus"
    (is (contains? runner/step-types :assert))
    (is (contains? runner/step-types :wait-until))
    (is (contains? runner/step-types :focus))
    (is (= :assert     (runner/step-type [:assert [:rf.assert/path-equals [:k] 1]])))
    (is (= :wait-until (runner/step-type [:wait-until [:db [:k] 1]])))
    (is (= :focus      (runner/step-type [:focus "[data-test=in]"])))
    (is (true? (runner/known-step? [:assert [:rf.assert/path-equals [:k] 1]])))
    (is (true? (runner/known-step? [:wait-until [:queue-empty]])))
    (is (true? (runner/known-step? [:focus "sel"])))))

(deftest assert-step-is-an-assertion-class-step
  (testing ":assert contributes to pass/fail (it is an assertion-class step)"
    (is (contains? runner/assertion-step-types :assert))
    (is (true? (runner/assertion? [:assert [:rf.assert/path-equals [:k] 1]])))))

(deftest assert-arity
  (testing "[:assert assertion-vector] requires a tagged assertion atom"
    (is (true?  (runner/step-arity-ok? [:assert [:rf.assert/path-equals [:k] 1]])))
    (is (true?  (runner/step-arity-ok? [:assert [:rf.assert/no-warnings]])))
    (is (false? (runner/step-arity-ok? [:assert])))
    (is (false? (runner/step-arity-ok? [:assert "not-a-vec"])))
    (is (false? (runner/step-arity-ok? [:assert []])))
    (is (false? (runner/step-arity-ok? [:assert ["not-keyword"]])))))

(deftest wait-until-arity
  (testing "[:wait-until predicate-spec] accepts :db equals / :db :pred /
            :queue-empty forms"
    (is (true?  (runner/step-arity-ok? [:wait-until [:db [:k] 1]])))
    (is (true?  (runner/step-arity-ok? [:wait-until [:db [:a :b] :pred even?]])))
    (is (true?  (runner/step-arity-ok? [:wait-until [:db [:a :b] :pred 'my/pred?]])))
    (is (true?  (runner/step-arity-ok? [:wait-until [:queue-empty]])))
    (is (false? (runner/step-arity-ok? [:wait-until])))
    (is (false? (runner/step-arity-ok? [:wait-until [:unknown-pred]])))
    (is (false? (runner/step-arity-ok? [:wait-until [:db "not-a-vec" 1]])))
    (is (false? (runner/step-arity-ok? [:wait-until [:queue-empty :extra]])))))

(deftest focus-arity
  (testing "[:focus selector] requires a string selector"
    (is (true?  (runner/step-arity-ok? [:focus "sel"])))
    (is (false? (runner/step-arity-ok? [:focus])))
    (is (false? (runner/step-arity-ok? [:focus 42])))))

(deftest wait-is-still-the-determinism-opt-out
  (testing "[:wait ms] keeps its tag distinct from [:wait-until] — the
            determinism gate refuses [:wait ms] but not [:wait-until]"
    (is (= :wait       (runner/step-type [:wait 100])))
    (is (= :wait-until (runner/step-type [:wait-until [:db [:k] 1]])))
    (is (not= :wait    (runner/step-type [:wait-until [:db [:k] 1]])))))

(deftest step-accessors
  (testing "step-assertion unwraps the [:assert …] atom"
    (is (= [:rf.assert/path-equals [:k] 1]
           (runner/step-assertion [:assert [:rf.assert/path-equals [:k] 1]])))
    (is (nil? (runner/step-assertion [:dispatch [:e]]))))
  (testing "step-wait-until decomposes the predicate-spec"
    (is (= {:kind :db :path [:k] :mode :equals :expected 1}
           (runner/step-wait-until [:wait-until [:db [:k] 1]])))
    (is (= {:kind :queue-empty}
           (runner/step-wait-until [:wait-until [:queue-empty]])))
    (let [d (runner/step-wait-until [:wait-until [:db [:k] :pred pos?]])]
      (is (= :pred (:mode d)))
      (is (true? (:pred-fn? d)))
      (is (identical? pos? (:pred-ref d)))))
  (testing "step-selector covers :focus"
    (is (= "sel" (runner/step-selector [:focus "sel"])))))

(deftest step-summary-new-steps
  (is (= "wait-until [:db [:k] 1]" (runner/step-summary [:wait-until [:db [:k] 1]])))
  (is (= "assert [:rf.assert/path-equals [:k] 1]"
         (runner/step-summary [:assert [:rf.assert/path-equals [:k] 1]])))
  (is (= "focus \"sel\"" (runner/step-summary [:focus "sel"]))))

;; ---- migration: bare event vectors normalize, tagged steps round-trip ----

(deftest bare-event-vectors-normalize-during-migration
  (testing "coerce-script lifts a bare event vector to [:dispatch …] — the
            migration shorthand, NOT the P1 public form (spec/017 §Script
            step grammar)"
    (is (= [[:dispatch [:counter/inc]]
            [:dispatch [:counter/dec]]]
           (runner/coerce-script [[:counter/inc] [:counter/dec]]))))
  (testing "the tagged steps are NEVER mistaken for bare event vectors —
            they round-trip unchanged"
    (let [tagged [[:dispatch [:e]]
                  [:wait-until [:db [:k] 1]]
                  [:assert [:rf.assert/path-equals [:k] 1]]
                  [:focus "sel"]
                  [:wait 50]]]
      (is (= tagged (runner/coerce-script tagged))))))

;; ===========================================================================
;; PLAN COMPILE: [:assert …] is rejected in :setup
;; ===========================================================================

(defn- err-id [thunk]
  (try (thunk) ::no-throw
       (catch #?(:clj Exception :cljs :default) e
         (:rf.error/id (ex-data e)))))

(deftest assert-in-setup-is-rejected-at-plan-compile
  (testing "[:assert …] in :setup FAILS plan construction (spec/017 §Script
            step grammar — illegal in :setup)"
    (is (= :rf.error/story-assert-in-setup
           (err-id #(plan/variant-plan
                      {:variant/id :story.bad/setup-assert
                       :setup  [[:dispatch [:seed/a]]
                                [:assert [:rf.assert/path-equals [:k] 1]]]
                       :script [[:dispatch [:act/b]]]}
                      {})))))
  (testing "[:assert …] in :script compiles fine — only :setup rejects it"
    (let [p (plan/variant-plan
              {:variant/id :story.ok/script-assert
               :setup  [[:dispatch [:seed/a]]]
               :script [[:dispatch [:act/b]]
                        [:assert [:rf.assert/path-equals [:k] 1]]]}
              {})]
      (is (= [[:dispatch [:act/b]]
              [:assert [:rf.assert/path-equals [:k] 1]]]
             (:script p)))))
  (testing "a bare-event-shorthand setup that happens to start with the
            :assert keyword still rejects after migration normalization"
    ;; `[:assert …]` is a known step, so coerce-script does NOT lift it to
    ;; `[:dispatch [:assert …]]` — it stays a checkpoint and the reject fires.
    (is (= :rf.error/story-assert-in-setup
           (err-id #(plan/variant-plan
                      {:variant/id :story.bad/events-assert
                       :events [[:assert [:rf.assert/no-warnings]]]}
                      {}))))))

;; ===========================================================================
;; REQUIRED-RUNNER: [:focus] requires :dom, the others are headless
;; ===========================================================================

(deftest focus-step-requires-dom-in-plan
  (testing "a [:focus …] script step lifts :required-runner to #{:dom}"
    (let [p (plan/variant-plan
              {:variant/id :story.focus/v
               :script [[:dispatch [:e]] [:focus "[data-test=in]"]]}
              {})]
      (is (contains? (:required-runner p) :dom))))
  (testing "an all-headless script (dispatch + wait-until + assert) needs no
            DOM capability"
    (let [p (plan/variant-plan
              {:variant/id :story.headless/v
               :script [[:dispatch [:e]]
                        [:wait-until [:db [:k] 1]]
                        [:assert [:rf.assert/path-equals [:k] 1]]]}
              {})]
      (is (not (contains? (:required-runner p) :dom))))))

;; ===========================================================================
;; HEADLESS execution against a live frame
;; ===========================================================================

(def ^:private exec-step! @#'re/exec-step!)
(def ^:private queue-empty? @#'re/queue-empty?)

(def ^:private step-frame :story.step-runner/frame)

(defn- reset-rf! [test-fn]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (reset! re/run-state {})
  ;; The canonical `:rf.assert/*` handlers must be installed so an
  ;; `[:assert …]` checkpoint's dispatched atom records onto the slot.
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (frame/reg-frame step-frame {:doc "tagged step-runner test frame"})
  (rf/reg-event :step/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (rf/reg-event :step/set (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
  (test-fn))

(use-fixtures :each reset-rf!)

(deftest tagged-dispatch-drains-through-settled-boundary
  (testing "a [:dispatch …] step settles to a fixed point in headless —
            the event has committed by the time exec-step! returns"
    (let [res (exec-step! step-frame 0 [:dispatch [:step/inc]])]
      (is (nil? (:passed? res)) "a plain dispatch contributes no pass/fail")
      (is (= 1 (:n (rf/app-db-value step-frame)))
          "the dispatch drained synchronously through settled-boundary"))))

(deftest wait-until-settles-when-predicate-true
  (testing "[:wait-until [:db path expected]] advances (step-skip) once the
            preceding dispatch made the predicate true"
    (rf/dispatch-sync* [:step/set 42] {:frame step-frame})
    (let [res (exec-step! step-frame 0 [:wait-until [:db [:v] 42]])]
      (is (nil? (:passed? res)) "a satisfied wait-until is a step-skip (advance)")
      (is (not (:exception res)))))
  (testing "[:wait-until [:db path :pred fn]] settles on a predicate"
    (rf/dispatch-sync* [:step/inc] {:frame step-frame})
    (let [res (exec-step! step-frame 0 [:wait-until [:db [:n] :pred pos?]])]
      (is (nil? (:passed? res)))))
  (testing "[:wait-until [:queue-empty]] settles — the queue drained at the
            settled boundary"
    (let [res (exec-step! step-frame 0 [:wait-until [:queue-empty]])]
      (is (nil? (:passed? res))))))

(deftest queue-empty-reads-the-real-router-queue
  (testing "queue-empty? reads the frame's ACTUAL router queue rather than
            a hard-coded `true` stub (rf2-m0cge5 finding 1). `[:click]` /
            `[:type]` / `[:focus]` fire a synthetic DOM event directly and
            never call `boundary/dispatch-and-settle!`, so a handler the
            event triggers may enqueue an ASYNC `[:dispatch …]` that has not
            yet drained (the router schedules async drains via
            `interop/next-tick` — genuinely deferred, never inline). A
            following `[:wait-until [:queue-empty]]` must see that as NOT
            drained rather than silently reporting `true` — the exact
            dispatch-vs-settle race the step exists to catch."
    (is (true? (queue-empty? step-frame))
        "a frame with an empty router queue and no pending drain reads as
         drained")
    ;; Directly manipulate the frame's router state (rather than racing a
    ;; REAL async `rf/dispatch` against `interop/next-tick`'s host scheduler
    ;; — on the JVM `next-tick` runs on a SEPARATE executor thread, so timing
    ;; the check against a live dispatch would be inherently flaky). This
    ;; exercises the read mechanism deterministically: a non-empty `:queue`
    ;; is exactly the state an async-dispatched, not-yet-drained envelope
    ;; leaves behind.
    (let [router (:router (frame/frame step-frame))]
      (swap! router update :queue conj {:event [:step/inc]})
      (is (false? (queue-empty? step-frame))
          "a non-empty router queue reads as NOT drained — the old
           hard-coded stub silently reported `true` here, hiding a pending
           dispatch from a following :assert-* read")
      ;; Draining the queue (what the router's own drain loop does once the
      ;; scheduled tick runs) flips the read back to true.
      (swap! router assoc :queue (empty (:queue @router)))
      (is (true? (queue-empty? step-frame))
          "once the queue is actually empty again, the real check reports
           true"))))

(deftest wait-until-times-out-readably-when-predicate-never-true
  (testing "an unmet [:wait-until pred] records a step-fail with a readable
            message — never a silent pass (spec/017 §Script step grammar)"
    (let [res (exec-step! step-frame 0 [:wait-until [:db [:never] :appears]])]
      (is (false? (:passed? res)) "unmet wait-until is a FAIL, not a skip")
      (is (= [:db [:never] :appears] (:expected res)))
      (is (re-find #"never became true" (:message res))))))

(deftest assert-checkpoint-records-at-this-point-in-the-script
  (testing "[:assert [:rf.assert/path-equals …]] records a PASSING assertion
            on the frame's :rf.story/assertions slot when true at this point"
    (rf/dispatch-sync* [:step/set :ready] {:frame step-frame})
    (let [res (exec-step! step-frame 0 [:assert [:rf.assert/path-equals [:v] :ready]])]
      (is (true? (:passed? res)) "the checkpoint passed at this point")
      (let [recs (:rf.story/assertions (rf/app-db-value step-frame))]
        (is (= 1 (count recs)) "exactly one assertion record landed")
        (is (= :rf.assert/path-equals (:assertion (last recs))))
        (is (true? (:passed? (last recs)))))))
  (testing "a FAILING checkpoint records :passed? false and surfaces a
            step-fail"
    (rf/dispatch-sync* [:step/set :ready] {:frame step-frame})
    (let [res (exec-step! step-frame 1 [:assert [:rf.assert/path-equals [:v] :NOPE]])]
      (is (false? (:passed? res)) "the checkpoint failed at this point")
      (let [recs (:rf.story/assertions (rf/app-db-value step-frame))]
        (is (false? (:passed? (last recs)))))))
  (testing "the checkpoint records exactly ONE assertion (no double-count
            from the assertion-slot mirror bridge)"
    (rf/dispatch-sync* [:step/set 7] {:frame step-frame})
    (let [before (count (:rf.story/assertions (rf/app-db-value step-frame)))]
      (exec-step! step-frame 0 [:assert [:rf.assert/path-equals [:v] 7]])
      (is (= 1 (- (count (:rf.story/assertions (rf/app-db-value step-frame))) before))
          "the wrapped :rf.assert/* handler is the SOLE recorder for [:assert …]"))))

(deftest focus-refuses-cannot-run-under-headless
  (testing "[:focus selector] returns a :cannot-run-shaped step-fail under a
            headless runner (no DOM) — never a silent pass (spec/017
            §`:cannot-run`)"
    (let [res (exec-step! step-frame 0 [:focus "[data-test=in]"])]
      (is (false? (:passed? res)))
      (is (true? (:skipped? res)) "headless focus is a no-DOM skip, not a pass")
      (is (re-find #"no DOM" (:message res)))))
  (testing "the boundary ladder agrees: a headless runner does not satisfy
            the :dom boundary [:focus] requires"
    (is (= :dom (boundary/step-required-boundary [:focus "sel"])))
    (is (not (boundary/satisfies-boundary? :headless :dom))))
  (testing "the capability registry agrees: [:focus] requires the :dom token
            a :headless runner lacks"
    (is (= #{:dom} (requirements/step-tokens [:focus "sel"])))
    (is (false? (requirements/runner-satisfies?
                  (requirements/runner-provides :headless)
                  (requirements/step-tokens [:focus "sel"]))))))
