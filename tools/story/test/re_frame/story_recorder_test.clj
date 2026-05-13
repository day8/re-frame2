(ns re-frame.story-recorder-test
  "JVM tests for the Test Codegen recorder (rf2-5fc15).

  Pure-data coverage: the recordable-event? predicate, the state
  machine (start / append / stop / reset), the impure entrypoints
  driving the per-process atom, and the gen-play-snippet codegen.
  Mirrors the cljc node-test arm in `recorder_cljs_test.cljs`.

  ## Coverage layers

  - `recordable-event?` — filters assertion events + Story-internal
    helpers without dropping legitimate user dispatches.
  - State machine (`start` / `append` / `stop` / `reset`) — pure
    transitions; one place to lock the contract before wiring the
    impure side.
  - Impure entrypoints (`start-recording!`, `stop-recording!`,
    `record-event!`, `clear!`, `toggle!`) — exercise the per-process
    atom alongside the predicate filter.
  - `gen-play-snippet` — the codegen output is `read-string`-able
    EDN; the assertion is shape-level (round-trips back to the same
    `:play` vector) so future cosmetic changes to formatting don't
    churn the test."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.async :as async]
            [re-frame.story.recorder :as recorder]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-recorder! [f]
  (recorder/clear!)
  (f))

(use-fixtures :each reset-recorder!)

;; ---- recordable-event? ---------------------------------------------------

(deftest recordable-event?-accepts-user-events
  (testing "ordinary user dispatches are recordable"
    (is (recorder/recordable-event? [:counter/inc]))
    (is (recorder/recordable-event? [:auth/login {:email "a@b"}]))
    (is (recorder/recordable-event? [:cart/add-item :widget-x 3]))))

(deftest recordable-event?-skips-assertions
  (testing ":rf.assert/* events are filtered (assertions are authored, not recorded)"
    (is (not (recorder/recordable-event? [:rf.assert/path-equals [:auth :status] :ok])))
    (is (not (recorder/recordable-event? [:rf.assert/sub-equals [:count] 3])))
    (is (not (recorder/recordable-event? [:rf.assert/no-warnings])))
    (is (not (recorder/recordable-event? [:rf.assert/dispatched? [:counter/inc]])))))

(deftest recordable-event?-skips-internal-story-events
  (testing ":rf.story/* + re-frame.story.* internal helpers are filtered"
    (is (not (recorder/recordable-event? [:rf.story/lifecycle-tick])))
    (is (not (recorder/recordable-event?
               [:re-frame.story.runtime/append-assertion {:a 1}])))
    (is (not (recorder/recordable-event?
               [:re-frame.story.assertions/append {:r 1}])))))

(deftest recordable-event?-handles-malformed-input
  (testing "non-event shapes are rejected"
    (is (not (recorder/recordable-event? nil)))
    (is (not (recorder/recordable-event? [])))
    (is (not (recorder/recordable-event? "not-a-vector")))
    (is (not (recorder/recordable-event? [{} "no-keyword-id"])))))

;; ---- pure state machine --------------------------------------------------

(deftest start-replaces-state
  (testing "start! resets events + flips :recording? true"
    (let [s0 recorder/initial-state
          s1 (recorder/start s0 :story.counter/happy-path 1000)]
      (is (true? (:recording? s1)))
      (is (= :story.counter/happy-path (:variant-id s1)))
      (is (= [] (:events s1)))
      (is (= 1000 (:started-ms s1))))))

(deftest start-clobbers-previous-recording
  (testing "starting a fresh recording drops any captured events"
    (let [s0 (-> recorder/initial-state
                 (recorder/start :story.a/x 1000)
                 (recorder/append [:a 1])
                 (recorder/append [:b 2]))
          s1 (recorder/start s0 :story.b/y 2000)]
      (is (= [] (:events s1)))
      (is (= :story.b/y (:variant-id s1))))))

(deftest append-while-recording
  (testing "append captures recordable events while recording"
    (let [s0 (recorder/start recorder/initial-state :story.counter/x 0)
          s1 (recorder/append s0 [:counter/inc])
          s2 (recorder/append s1 [:counter/inc])
          s3 (recorder/append s2 [:counter/dec])]
      (is (= [[:counter/inc] [:counter/inc] [:counter/dec]]
             (:events s3))))))

(deftest append-skips-assertions-and-internals
  (testing "append filters non-recordable events"
    (let [s0 (recorder/start recorder/initial-state :story.x/y 0)
          s1 (-> s0
                 (recorder/append [:rf.assert/path-equals [:a] 1])
                 (recorder/append [:counter/inc])
                 (recorder/append [:re-frame.story.runtime/append-assertion {}])
                 (recorder/append [:counter/dec]))]
      (is (= [[:counter/inc] [:counter/dec]]
             (:events s1))))))

(deftest append-noop-when-not-recording
  (testing "append drops events when :recording? is false"
    (let [s0 recorder/initial-state
          s1 (recorder/append s0 [:counter/inc])]
      (is (= [] (:events s1))))))

(deftest stop-preserves-events
  (testing "stop flips :recording? false but keeps the captured trace"
    (let [s0 (-> recorder/initial-state
                 (recorder/start :story.x/y 0)
                 (recorder/append [:counter/inc]))
          s1 (recorder/stop s0)]
      (is (false? (:recording? s1)))
      (is (= [[:counter/inc]] (:events s1)))
      (is (= :story.x/y (:variant-id s1))))))

(deftest reset-returns-idle
  (testing "reset drops everything"
    (let [s0 (-> recorder/initial-state
                 (recorder/start :story.x/y 0)
                 (recorder/append [:counter/inc]))
          s1 (recorder/reset s0)]
      (is (= recorder/initial-state s1)))))

;; ---- impure entrypoints --------------------------------------------------

(deftest start-recording!-mutates-state
  (recorder/start-recording! :story.counter/x 5000)
  (is (recorder/recording?))
  (is (= :story.counter/x (recorder/recording-variant)))
  (is (= [] (recorder/recorded-events))))

(deftest record-event!-captures-recordable
  (recorder/start-recording! :story.counter/x 0)
  (recorder/record-event! [:counter/inc])
  (recorder/record-event! [:rf.assert/path-equals [:a] 1])
  (recorder/record-event! [:counter/dec])
  (is (= [[:counter/inc] [:counter/dec]]
         (recorder/recorded-events))))

(deftest stop-recording!-flips-state
  (recorder/start-recording! :story.x/y 0)
  (recorder/record-event! [:counter/inc])
  (recorder/stop-recording!)
  (is (false? (recorder/recording?)))
  (is (= [[:counter/inc]] (recorder/recorded-events))))

(deftest toggle!-flips
  (testing "toggle! starts when idle and stops when recording"
    (recorder/toggle! :story.x/y)
    (is (recorder/recording?))
    (recorder/record-event! [:foo/bar])
    (recorder/toggle! :story.x/y)
    (is (not (recorder/recording?)))
    (is (= [[:foo/bar]] (recorder/recorded-events)))))

(deftest clear!-resets-everything
  (recorder/start-recording! :story.x/y 0)
  (recorder/record-event! [:counter/inc])
  (recorder/clear!)
  (is (= recorder/initial-state (recorder/current-state))))

;; ---- gen-play-snippet ----------------------------------------------------

(deftest gen-play-snippet-empty
  (testing "gen-play-snippet with no events renders an empty :play vector"
    (let [snip (recorder/gen-play-snippet [] {:variant-id :story.x/y})]
      (is (string? snip))
      (is (str/includes? snip ":story.x/y"))
      (is (str/includes? snip ":play"))
      (is (str/includes? snip "[]")))))

(deftest gen-play-snippet-renders-reg-variant
  (testing "snippet renders the (reg-variant ...) form with the captured trace"
    (let [snip (recorder/gen-play-snippet
                 [[:counter/inc] [:counter/dec]]
                 {:variant-id :story.counter/recorded})]
      (is (str/includes? snip "reg-variant"))
      (is (str/includes? snip ":story.counter/recorded"))
      (is (str/includes? snip "[:counter/inc]"))
      (is (str/includes? snip "[:counter/dec]")))))

(deftest gen-play-snippet-includes-doc
  (let [snip (recorder/gen-play-snippet
               [[:counter/inc]]
               {:variant-id :story.counter/x :doc "user inc once"})]
    (is (str/includes? snip ":doc"))
    (is (str/includes? snip "user inc once"))))

(deftest gen-play-snippet-includes-extends
  (let [snip (recorder/gen-play-snippet
               [[:counter/inc]]
               {:variant-id :story.counter/recorded
                :extends    :story.counter/happy-path})]
    (is (str/includes? snip ":extends"))
    (is (str/includes? snip ":story.counter/happy-path"))))

(deftest gen-play-snippet-uses-custom-alias
  (let [snip (recorder/gen-play-snippet
               []
               {:variant-id :story.x/y :alias "rf"})]
    (is (str/includes? snip "rf/reg-variant"))))

(defn- extract-play-vector
  "Pull the `:play` vector substring out of the rendered snippet by
  walking balanced brackets after the `:play` token."
  [snippet]
  (let [start  (str/index-of snippet ":play")
        after  (subs snippet start)
        open   (str/index-of after "[")]
    (loop [i (inc open) depth 1]
      (cond
        (or (nil? i) (>= i (count after)))
        nil

        (zero? depth)
        (subs after open i)

        :else
        (let [c (.charAt ^String after i)]
          (case c
            \[ (recur (inc i) (inc depth))
            \] (recur (inc i) (dec depth))
            (recur (inc i) depth)))))))

(deftest gen-play-snippet-roundtrips-events
  (testing "the rendered :play vector reads back as the original events"
    (let [events   [[:counter/inc]
                    [:auth/login {:email "alice@example.com" :remember? true}]
                    [:cart/add-item :widget-x 3]]
          snippet  (recorder/gen-play-snippet
                     events
                     {:variant-id :story.x/y})
          play-str (extract-play-vector snippet)]
      (is (some? play-str) "extractor found a :play vector substring")
      (is (= events (edn/read-string play-str))))))

;; ---- end-to-end: trace-bus integration -----------------------------------

(defn- reset-rf-state! []
  (recorder/remove-trace-listener!)
  (recorder/clear!)
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(deftest trace-listener-captures-dispatch-into-recording
  (testing "with a recording in flight, a dispatch against the target frame is captured"
    (reset-rf-state!)
    (rf/reg-event-db :counter/inc
      (fn [db _] (update db :n (fnil inc 0))))
    (rf/reg-event-db :counter/dec
      (fn [db _] (update db :n (fnil dec 0))))
    (story/reg-variant :story.recorder/v {:events []})
    ;; Allocate the variant frame + install the listener.
    (async/deref-blocking (story/run-variant :story.recorder/v) 5000)
    (recorder/install-trace-listener!)
    (recorder/start-recording! :story.recorder/v)
    ;; Drive a few dispatches against the target frame.
    (rf/dispatch-sync [:counter/inc] {:frame :story.recorder/v})
    (rf/dispatch-sync [:counter/inc] {:frame :story.recorder/v})
    (rf/dispatch-sync [:counter/dec] {:frame :story.recorder/v})
    (recorder/stop-recording!)
    (is (= [[:counter/inc] [:counter/inc] [:counter/dec]]
           (recorder/recorded-events)))
    (story/destroy-variant! :story.recorder/v)
    (recorder/remove-trace-listener!)))

(deftest trace-listener-ignores-cross-frame-traffic
  (testing "dispatches to a non-target frame don't appear in the recording"
    (reset-rf-state!)
    (rf/reg-event-db :counter/inc
      (fn [db _] (update db :n (fnil inc 0))))
    (story/reg-variant :story.recorder/target {:events []})
    (story/reg-variant :story.recorder/other  {:events []})
    (async/deref-blocking (story/run-variant :story.recorder/target) 5000)
    (async/deref-blocking (story/run-variant :story.recorder/other)  5000)
    (recorder/install-trace-listener!)
    (recorder/start-recording! :story.recorder/target)
    ;; This one lands on the recording target.
    (rf/dispatch-sync [:counter/inc] {:frame :story.recorder/target})
    ;; This one lands on a different frame and MUST be ignored.
    (rf/dispatch-sync [:counter/inc] {:frame :story.recorder/other})
    ;; This one lands on the target again.
    (rf/dispatch-sync [:counter/inc] {:frame :story.recorder/target})
    (recorder/stop-recording!)
    (is (= [[:counter/inc] [:counter/inc]]
           (recorder/recorded-events))
        "only the target-frame dispatches are captured")
    (story/destroy-variant! :story.recorder/target)
    (story/destroy-variant! :story.recorder/other)
    (recorder/remove-trace-listener!)))

(deftest trace-listener-skips-assertion-events
  (testing "with a recording active, :rf.assert/* dispatches don't leak into the captured :play body"
    (reset-rf-state!)
    (rf/reg-event-db :counter/inc
      (fn [db _] (update db :n (fnil inc 0))))
    (story/reg-variant :story.recorder/v {:events []})
    (async/deref-blocking (story/run-variant :story.recorder/v) 5000)
    (recorder/install-trace-listener!)
    (recorder/start-recording! :story.recorder/v)
    (rf/dispatch-sync [:counter/inc] {:frame :story.recorder/v})
    ;; Assertions ARE dispatchable but the recorder skips them.
    (rf/dispatch-sync [:rf.assert/path-equals [:n] 1]
                      {:frame :story.recorder/v})
    (rf/dispatch-sync [:counter/inc] {:frame :story.recorder/v})
    (recorder/stop-recording!)
    (is (= [[:counter/inc] [:counter/inc]]
           (recorder/recorded-events))
        "only the user dispatches are captured — assertions filtered")
    (story/destroy-variant! :story.recorder/v)
    (recorder/remove-trace-listener!)))

(deftest end-to-end-recording-to-snippet
  (testing "the full record→stop→gen-play-snippet cycle produces a valid :play body"
    (reset-rf-state!)
    (rf/reg-event-db :counter/inc
      (fn [db _] (update db :n (fnil inc 0))))
    (rf/reg-event-db :counter/by
      (fn [db [_ n]] (update db :n (fnil + 0) n)))
    (story/reg-variant :story.recorder/source {:events []})
    (async/deref-blocking (story/run-variant :story.recorder/source) 5000)
    (recorder/install-trace-listener!)
    (recorder/start-recording! :story.recorder/source)
    (rf/dispatch-sync [:counter/inc]        {:frame :story.recorder/source})
    (rf/dispatch-sync [:counter/inc]        {:frame :story.recorder/source})
    (rf/dispatch-sync [:counter/by 7]       {:frame :story.recorder/source})
    (let [{:keys [events variant-id]} (recorder/stop-recording!)
          snippet (recorder/gen-play-snippet
                    events
                    {:variant-id :story.recorder/captured
                     :extends    variant-id
                     :doc        "recorded via Test Codegen"})
          play-str (extract-play-vector snippet)]
      (is (= 3 (count events)))
      (is (str/includes? snippet "reg-variant"))
      (is (str/includes? snippet ":story.recorder/captured"))
      (is (str/includes? snippet ":story.recorder/source")
          "the recorder-target id rides into the :extends slot")
      (is (= events (edn/read-string play-str))
          "the rendered :play vector round-trips back through read-string"))
    (story/destroy-variant! :story.recorder/source)
    (recorder/remove-trace-listener!)))
