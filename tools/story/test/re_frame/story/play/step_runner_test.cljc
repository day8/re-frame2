(ns re-frame.story.play.step-runner-test
  "The tagged setup/script step runner
  (spec/017-Testing-Story.md §Script step grammar + §Setup and script).

  Two layers, both under `clojure -M:test` (JVM):

  - PURE grammar — `rf.story.play.runner/step-types` / `step-arity-ok?` / `coerce-script`
    / `step-assertion` / `step-wait-until` recognise the tagged steps
    (`[:dispatch]` / `[:wait-until]` / `[:wait]` / `[:assert]` / `[:focus]`),
    and `coerce-script` never mistakes one for the bare event-vector
    shorthand it lifts to `[:dispatch …]` (the lift itself is
    `runner-test`'s). No re-frame dep.
  - HEADLESS execution against a live frame — `runner-events/exec-step!`
    drives a tagged `[:dispatch …]` through `settled-boundary`, settles a
    `[:wait-until pred]` on a queue/state predicate (timing out readably),
    records an `[:assert …]` checkpoint at its point in the script, and
    REFUSES `[:focus …]` headless with `:cannot-run`.

  The headless execution layer drives `exec-step!` directly (a private
  var reached via var-quote — the established Story-test seam) so a single
  step's behaviour is observable without standing up the async run loop."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core              :as rf]
            [re-frame.router            :as rf.router]
            [re-frame.frame             :as rf.frame]
            [re-frame.registrar         :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story             :as rf.story]
            [re-frame.story.play.runner :as rf.story.play.runner]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            [re-frame.story.play.settled-boundary :as rf.story.play.settled-boundary]))

;; ===========================================================================
;; PURE: the tagged step grammar
;; ===========================================================================

(deftest assert-step-is-an-assertion-class-step
  (testing ":assert contributes to pass/fail (it is an assertion-class step)"
    (is (true? (rf.story.play.runner/assertion? [:assert [:rf.assert/path-equals [:k] 1]])))))

(deftest assert-wait-until-and-focus-arity
  (are [step ok?] (= ok? (rf.story.play.runner/step-arity-ok? step))
    ;; [:assert assertion-vector] takes a tagged assertion atom
    [:assert [:rf.assert/path-equals [:k] 1]]   true
    [:assert [:rf.assert/no-warnings]]          true
    [:assert]                                   false
    [:assert "not-a-vec"]                       false
    [:assert []]                                false
    [:assert ["not-keyword"]]                   false
    ;; [:wait-until predicate-spec] takes the :db equals, :db :pred and
    ;; :queue-empty forms
    [:wait-until [:db [:k] 1]]                  true
    [:wait-until [:db [:a :b] :pred even?]]     true
    [:wait-until [:db [:a :b] :pred 'my/pred?]] true
    [:wait-until [:queue-empty]]                true
    [:wait-until]                               false
    [:wait-until [:unknown-pred]]               false
    [:wait-until [:db "not-a-vec" 1]]           false
    [:wait-until [:queue-empty :extra]]         false
    ;; [:focus selector] takes a string selector
    [:focus "sel"]                              true
    [:focus]                                    false
    [:focus 42]                                 false))

(deftest step-accessors
  (testing "step-assertion unwraps the [:assert …] atom"
    (is (= [:rf.assert/path-equals [:k] 1]
           (rf.story.play.runner/step-assertion [:assert [:rf.assert/path-equals [:k] 1]])))
    (is (nil? (rf.story.play.runner/step-assertion [:dispatch [:e]]))))
  (testing "step-wait-until decomposes the predicate-spec"
    (is (= {:kind :db :path [:k] :mode :equals :expected 1}
           (rf.story.play.runner/step-wait-until [:wait-until [:db [:k] 1]])))
    (is (= {:kind :queue-empty}
           (rf.story.play.runner/step-wait-until [:wait-until [:queue-empty]])))
    (let [d (rf.story.play.runner/step-wait-until [:wait-until [:db [:k] :pred pos?]])]
      (is (= :pred (:mode d)))
      (is (true? (:pred-fn? d)))
      (is (identical? pos? (:pred-ref d)))))
  (testing "step-selector covers :focus"
    (is (= "sel" (rf.story.play.runner/step-selector [:focus "sel"])))))

(deftest step-summary-new-steps
  (is (= "wait-until [:db [:k] 1]" (rf.story.play.runner/step-summary [:wait-until [:db [:k] 1]])))
  (is (= "assert [:rf.assert/path-equals [:k] 1]"
         (rf.story.play.runner/step-summary [:assert [:rf.assert/path-equals [:k] 1]])))
  (is (= "focus \"sel\"" (rf.story.play.runner/step-summary [:focus "sel"]))))

;; ---- tagged steps round-trip coerce-script unchanged ----------------------

(deftest tagged-steps-are-never-lifted-as-bare-event-vectors
  (testing "coerce-script never mistakes a tagged step for the bare
            event-vector shorthand it lifts to [:dispatch …] (the migration
            shorthand, NOT the P1 public form — spec/017 §Script step
            grammar): tagged steps round-trip unchanged"
    (let [tagged [[:dispatch [:e]]
                  [:wait-until [:db [:k] 1]]
                  [:assert [:rf.assert/path-equals [:k] 1]]
                  [:focus "sel"]
                  [:wait 50]]]
      (is (= tagged (rf.story.play.runner/coerce-script tagged))))))

;; ===========================================================================
;; HEADLESS execution against a live frame
;; ===========================================================================

(def ^:private exec-step! @#'rf.story.play.runner-events/exec-step!)
(def ^:private queue-empty? @#'rf.story.play.runner-events/queue-empty?)

(def ^:private step-frame :story.step-runner/frame)

(defn- reset-rf! [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (reset! rf.story.play.runner-events/run-state {})
  ;; The canonical `:rf.assert/*` handlers must be installed so an
  ;; `[:assert …]` checkpoint's dispatched atom records onto the slot.
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/make-frame {:id step-frame :doc "tagged step-runner test frame"})
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
    (rf.router/dispatch-sync! [:step/set 42] {:frame step-frame})
    (let [res (exec-step! step-frame 0 [:wait-until [:db [:v] 42]])]
      (is (nil? (:passed? res)) "a satisfied wait-until is a step-skip (advance)")
      (is (not (:exception res)))))
  (testing "[:wait-until [:db path :pred fn]] settles on a predicate"
    (rf.router/dispatch-sync! [:step/inc] {:frame step-frame})
    (let [res (exec-step! step-frame 0 [:wait-until [:db [:n] :pred pos?]])]
      (is (nil? (:passed? res)))))
  (testing "[:wait-until [:queue-empty]] settles — the queue drained at the
            settled boundary"
    (let [res (exec-step! step-frame 0 [:wait-until [:queue-empty]])]
      (is (nil? (:passed? res))))))

(deftest queue-empty-reads-the-real-router-queue
  (testing "queue-empty? reads the frame's ACTUAL router queue. `[:click]` /
            `[:type]` / `[:focus]` fire a synthetic DOM event directly and
            never call `rf.story.play.settled-boundary/dispatch-and-settle!`, so a handler the
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
    (let [router (:router (rf.frame/frame step-frame))]
      (swap! router update :queue conj {:event [:step/inc]})
      (is (false? (queue-empty? step-frame))
          "a non-empty router queue reads as NOT drained — a constant
           `true` here would hide a pending dispatch from a following
           :assert-* read")
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
    (rf.router/dispatch-sync! [:step/set :ready] {:frame step-frame})
    (let [res (exec-step! step-frame 0 [:assert [:rf.assert/path-equals [:v] :ready]])]
      (is (true? (:passed? res)) "the checkpoint passed at this point")
      (let [recs (:rf.story/assertions (rf/app-db-value step-frame))]
        (is (= 1 (count recs)) "exactly one assertion record landed")
        (is (= :rf.assert/path-equals (:assertion (last recs))))
        (is (true? (:passed? (last recs)))))))
  (testing "a FAILING checkpoint records :passed? false and surfaces a
            step-fail"
    (rf.router/dispatch-sync! [:step/set :ready] {:frame step-frame})
    (let [res (exec-step! step-frame 1 [:assert [:rf.assert/path-equals [:v] :NOPE]])]
      (is (false? (:passed? res)) "the checkpoint failed at this point")
      (let [recs (:rf.story/assertions (rf/app-db-value step-frame))]
        (is (false? (:passed? (last recs)))))))
  (testing "the checkpoint records exactly ONE assertion (no double-count
            from the assertion-slot mirror bridge)"
    (rf.router/dispatch-sync! [:step/set 7] {:frame step-frame})
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
    (is (= :dom (rf.story.play.settled-boundary/step-required-boundary [:focus "sel"])))
    (is (not (rf.story.play.settled-boundary/satisfies-boundary? :headless :dom)))))
