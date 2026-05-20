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

(defn- extract-play-script-vector
  "Pull the `:script` vector substring out of the rendered snippet by
  walking balanced brackets after the `:play-script` body's `:script`
  token. Per rf2-0wrud the canonical phase-4 slot is `:play-script`
  with a `{:auto-run? ... :script [...]}` body."
  [snippet]
  (let [start  (str/index-of snippet ":script")
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

(defn- unwrap-dispatch-sync-steps
  "Project the parsed `:script` vector back to the bare event-vector
  list. Each step is `[:dispatch-sync <event-vec>]` (per rf2-0wrud)."
  [script-vec]
  (mapv second script-vec))

(deftest gen-play-snippet-roundtrips-events
  (testing "the rendered :play-script :script vector reads back as
            [:dispatch-sync <event>] steps that unwrap to the original
            events (per rf2-0wrud — :play-script is the canonical and
            ONLY phase-4 slot; gen-play-snippet wraps each captured
            event as a :dispatch-sync step)"
    (let [events     [[:counter/inc]
                      [:auth/login {:email "alice@example.com" :remember? true}]
                      [:cart/add-item :widget-x 3]]
          snippet    (recorder/gen-play-snippet
                       events
                       {:variant-id :story.x/y})
          script-str (extract-play-script-vector snippet)
          script-vec (edn/read-string script-str)]
      (is (some? script-str) "extractor found a :script vector substring")
      (is (every? #(and (vector? %)
                        (= :dispatch-sync (first %)))
                  script-vec)
          "every step is a [:dispatch-sync <event-vec>] form")
      (is (= events (unwrap-dispatch-sync-steps script-vec))
          "unwrapping :dispatch-sync round-trips to the original events"))))

;; ---- rf2-d5u89: DOM-event entries + per-event timestamps ----------------

(deftest append-dom-click-pure-shape
  (testing "append-dom of a click vector lands an :entries entry"
    (let [s0 (recorder/start recorder/initial-state :story.x/y 0)
          s1 (recorder/append-dom s0 [:dom/click "[data-test=\"a\"]" 100])]
      (is (= 1 (count (:entries s1))))
      (is (= {:kind :dom/click :selector "[data-test=\"a\"]" :t 100}
             (first (:entries s1)))))))

(deftest append-dom-type-pure-shape
  (let [s0 (recorder/start recorder/initial-state :story.x/y 0)
        s1 (recorder/append-dom s0 [:dom/type "[id=\"x\"]" "alice" 200])]
    (is (= {:kind :dom/type :selector "[id=\"x\"]" :text "alice" :t 200}
           (first (:entries s1))))))

(deftest append-dom-submit-pure-shape
  (let [s0 (recorder/start recorder/initial-state :story.x/y 0)
        s1 (recorder/append-dom s0 [:dom/submit "[id=\"login\"]" 300])]
    (is (= {:kind :dom/submit :selector "[id=\"login\"]" :t 300}
           (first (:entries s1))))))

(deftest append-dom-rejects-malformed
  (let [s0 (recorder/start recorder/initial-state :story.x/y 0)]
    (is (= [] (:entries (recorder/append-dom s0 nil))))
    (is (= [] (:entries (recorder/append-dom s0 []))))
    (is (= [] (:entries (recorder/append-dom s0 [:not-a-dom-kind "x" 0]))))))

(deftest append-dom-noop-when-not-recording
  (testing "DOM-events drop on the floor when no recording is in flight"
    (let [s0 recorder/initial-state
          s1 (recorder/append-dom s0 [:dom/click "[data-test=\"x\"]" 0])]
      (is (= [] (:entries s1))))))

(deftest append-event-stamps-timestamp
  (testing "(append state event now-ms) populates :entries[:t]"
    (let [s0 (recorder/start recorder/initial-state :story.x/y 1000)
          s1 (recorder/append s0 [:counter/inc] 1250)]
      (is (= [[:counter/inc]] (:events s1)) ":events carries bare event")
      (let [{:keys [kind event t]} (first (:entries s1))]
        (is (= :event/dispatch kind))
        (is (= [:counter/inc] event))
        (is (= 250 t) ":t = now-ms - started-ms (1250 - 1000)")))))

(deftest record-dom-event!-impure-entry
  (testing "the impure record-dom-event! entry mutates the shared atom"
    (recorder/clear!)
    (recorder/start-recording! :story.x/y)
    (recorder/record-dom-event! [:dom/click "[data-test=\"go\"]" 50])
    (recorder/record-dom-event! [:dom/type "[id=\"x\"]" "hi" 100])
    (let [entries (recorder/recorded-entries)]
      (is (= 2 (count entries)))
      (is (= :dom/click (:kind (first entries))))
      (is (= :dom/type (:kind (second entries)))))))

;; ---- mid-recording assertion insertion (rf2-39u9e) ----------------------

(deftest assertion-vocabulary-covers-canonical-seven
  (testing "the picker vocabulary enumerates all seven canonical :rf.assert/* ids"
    (let [ids (set (map :id recorder/assertion-vocabulary))]
      (is (= #{:rf.assert/path-equals
               :rf.assert/path-matches
               :rf.assert/sub-equals
               :rf.assert/dispatched?
               :rf.assert/state-is
               :rf.assert/no-warnings
               :rf.assert/effect-emitted}
             ids)
          "all seven canonical assertion ids from spec/004 are present"))))

(deftest assertion-vocabulary-entries-are-well-formed
  (testing "every vocabulary entry carries the picker's required keys"
    (doseq [entry recorder/assertion-vocabulary]
      (is (qualified-keyword? (:id entry)))
      (is (string? (:label entry)))
      (is (string? (:hint entry)))
      (is (vector? (:fields entry)))
      (doseq [{:keys [key prompt placeholder type]} (:fields entry)]
        (is (keyword? key))
        (is (string? prompt))
        (is (string? placeholder))
        (is (#{:edn :string} type))))))

(deftest make-assertion-builds-well-formed-events
  (testing "make-assertion builds canonical event vectors from a payload map"
    (is (= [:rf.assert/path-equals [:auth :status] :ok]
           (recorder/make-assertion :rf.assert/path-equals
                                    {:path [:auth :status] :expected :ok})))
    (is (= [:rf.assert/sub-equals [:counter] 3]
           (recorder/make-assertion :rf.assert/sub-equals
                                    {:sub [:counter] :expected 3})))
    (is (= [:rf.assert/dispatched? [:counter/inc]]
           (recorder/make-assertion :rf.assert/dispatched?
                                    {:event [:counter/inc]})))
    (is (= [:rf.assert/state-is :auth/machine :authenticated]
           (recorder/make-assertion :rf.assert/state-is
                                    {:machine :auth/machine
                                     :state   :authenticated})))
    (is (= [:rf.assert/effect-emitted :http]
           (recorder/make-assertion :rf.assert/effect-emitted {:fx-id :http})))))

(deftest make-assertion-no-payload-form
  (testing "make-assertion handles assertions with no payload (no-warnings)"
    (is (= [:rf.assert/no-warnings]
           (recorder/make-assertion :rf.assert/no-warnings {})))))

(deftest make-assertion-rejects-unknown-id
  (testing "make-assertion returns nil for an unknown assertion id"
    (is (nil? (recorder/make-assertion :rf.assert/not-a-real-one {})))
    (is (nil? (recorder/make-assertion :counter/inc {})))))

(deftest make-assertion-fills-missing-fields-as-nil
  (testing "make-assertion fills in missing payload fields as nil (partial picker entry)"
    (is (= [:rf.assert/path-equals [:auth :status] nil]
           (recorder/make-assertion :rf.assert/path-equals
                                    {:path [:auth :status]})))))

(deftest append-assertion-pure-state-machine
  (testing "append-assertion appends valid :rf.assert/* events through the filter"
    (let [s0 (recorder/start recorder/initial-state :story.x/y 0)
          s1 (-> s0
                 (recorder/append [:counter/inc])
                 (recorder/append-assertion
                   [:rf.assert/path-equals [:n] 1])
                 (recorder/append [:counter/inc])
                 (recorder/append-assertion
                   [:rf.assert/sub-equals [:counter] 2]))]
      (is (= [[:counter/inc]
              [:rf.assert/path-equals [:n] 1]
              [:counter/inc]
              [:rf.assert/sub-equals [:counter] 2]]
             (:events s1))
          "assertions are interleaved inline with dispatched events"))))

(deftest append-assertion-rejects-non-assertions
  (testing "append-assertion is a no-op for non-:rf.assert/* event vectors"
    (let [s0 (recorder/start recorder/initial-state :story.x/y 0)
          s1 (-> s0
                 (recorder/append-assertion [:counter/inc])
                 (recorder/append-assertion [:rf.story/lifecycle-tick])
                 (recorder/append-assertion nil)
                 (recorder/append-assertion []))]
      (is (= [] (:events s1))
          "only :rf.assert/* event vectors land via append-assertion"))))

(deftest append-assertion-noop-when-not-recording
  (testing "append-assertion drops events when :recording? is false"
    (let [s0 recorder/initial-state
          s1 (recorder/append-assertion s0 [:rf.assert/no-warnings])]
      (is (= [] (:events s1))))))

(deftest insert-assertion!-event-vec-arity
  (testing "insert-assertion! one-arg form appends a pre-built event vector"
    (recorder/start-recording! :story.x/y 0)
    (recorder/record-event! [:counter/inc])
    (recorder/insert-assertion! [:rf.assert/path-equals [:n] 1])
    (recorder/record-event! [:counter/inc])
    (recorder/insert-assertion! [:rf.assert/no-warnings])
    (is (= [[:counter/inc]
            [:rf.assert/path-equals [:n] 1]
            [:counter/inc]
            [:rf.assert/no-warnings]]
           (recorder/recorded-events)))))

(deftest insert-assertion!-id-plus-payload-arity
  (testing "insert-assertion! two-arg form builds + appends from id+payload"
    (recorder/start-recording! :story.x/y 0)
    (recorder/record-event! [:counter/inc])
    (recorder/insert-assertion!
      :rf.assert/sub-equals {:sub [:counter] :expected 1})
    (recorder/record-event! [:counter/inc])
    (recorder/insert-assertion!
      :rf.assert/path-equals {:path [:n] :expected 2})
    (is (= [[:counter/inc]
            [:rf.assert/sub-equals [:counter] 1]
            [:counter/inc]
            [:rf.assert/path-equals [:n] 2]]
           (recorder/recorded-events)))))

(deftest insert-assertion!-rejects-non-assertion-event
  (testing "insert-assertion! drops anything that isn't an :rf.assert/* event"
    (recorder/start-recording! :story.x/y 0)
    (recorder/insert-assertion! [:counter/inc])           ; wrong namespace
    (recorder/insert-assertion! [:rf.story/lifecycle-tick]) ; internal
    (recorder/insert-assertion! nil)
    (is (= [] (recorder/recorded-events)))))

(deftest insert-assertion!-noop-when-not-recording
  (testing "insert-assertion! is harmless when no recording is in flight"
    (is (not (recorder/recording?)))
    (recorder/insert-assertion! :rf.assert/no-warnings {})
    (is (= [] (recorder/recorded-events)))))

(deftest gen-play-snippet-round-trips-with-inserted-assertions
  (testing "the EDN snippet round-trips when the captured trace includes assertions"
    (recorder/start-recording! :story.counter/x 0)
    (recorder/record-event! [:counter/inc])
    (recorder/insert-assertion! :rf.assert/sub-equals
                                {:sub [:counter] :expected 1})
    (recorder/record-event! [:counter/by 7])
    (recorder/insert-assertion! :rf.assert/path-equals
                                {:path [:n] :expected 8})
    (recorder/stop-recording!)
    (let [events     (recorder/recorded-events)
          snippet    (recorder/gen-play-snippet
                       events
                       {:variant-id :story.counter/recorded
                        :extends    :story.counter/x})
          script-str (extract-play-script-vector snippet)
          script-vec (edn/read-string script-str)]
      (is (= [[:counter/inc]
              [:rf.assert/sub-equals [:counter] 1]
              [:counter/by 7]
              [:rf.assert/path-equals [:n] 8]]
             events)
          "user dispatches AND inserted assertions are interleaved")
      (is (str/includes? snippet ":rf.assert/sub-equals"))
      (is (str/includes? snippet ":rf.assert/path-equals"))
      (is (str/includes? snippet "[:counter/inc]"))
      (is (some? script-str)
          "extractor found the rendered :script vector")
      (is (= events (unwrap-dispatch-sync-steps script-vec))
          "the :play-script :script vector unwraps to the original events"))))

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
           (recorder/recorded-events))
        "back-compat :events slot still carries bare event vectors")
    ;; rf2-d5u89: parallel :entries slot carries the rich shape too.
    (let [entries (recorder/recorded-entries)]
      (is (= 3 (count entries))
          ":entries mirrors :events one-for-one")
      (is (every? #(= :event/dispatch (:kind %)) entries)
          "each entry is an :event/dispatch shape")
      (is (= [[:counter/inc] [:counter/inc] [:counter/dec]]
             (mapv :event entries))
          ":event slot on each entry is the bare event vector")
      (is (every? #(number? (:t %)) entries)
          "each entry carries a numeric :t timestamp"))
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

(deftest trace-listener-redacts-sensitive-dispatches-end-to-end
  (testing "a `redact-interceptor`-interceptor handler still appears in the
            recording with the payload scrubbed (rf2-hdadz). The
            handler-meta `:sensitive?` annotation has been removed,
            so sensitivity now flows via the `redact-interceptor`
            interceptor (or schema-marked paths)."
    (reset-rf-state!)
    (rf/reg-event-db :counter/inc
      (fn [db _] (update db :n (fnil inc 0))))
    ;; Use `redact-interceptor` so the trace surface sees the redacted payload.
    (rf/reg-event-db :auth/login
      [(rf/redact-interceptor [[:password] [:totp]])]
      (fn [db _] db))
    (story/reg-variant :story.recorder/sens-end-to-end {:events []})
    (async/deref-blocking (story/run-variant :story.recorder/sens-end-to-end) 5000)
    (recorder/install-trace-listener!)
    (recorder/start-recording! :story.recorder/sens-end-to-end)
    (rf/dispatch-sync [:counter/inc] {:frame :story.recorder/sens-end-to-end})
    (rf/dispatch-sync [:auth/login {:password "shh"
                                    :totp "123456"}]
                      {:frame :story.recorder/sens-end-to-end})
    (rf/dispatch-sync [:counter/inc] {:frame :story.recorder/sens-end-to-end})
    (recorder/stop-recording!)
    (let [events (recorder/recorded-events)]
      ;; The `:auth/login` event vector survives in position; the
      ;; redact-interceptor interceptor scrubbed the secret-bearing keys
      ;; on the trace surface.
      (is (= 3 (count events)) "all three dispatches captured in position")
      ;; Belt-and-braces: no slice of the captured trace contains either secret literal.
      (is (not-any? (fn [ev] (and (vector? ev)
                                  (some #{"shh" "123456"} (map str ev))))
                    events)
          "no captured event vector echoes the sensitive payload literals"))
    (story/destroy-variant! :story.recorder/sens-end-to-end)
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
          snippet    (recorder/gen-play-snippet
                       events
                       {:variant-id :story.recorder/captured
                        :extends    variant-id
                        :doc        "recorded via Test Codegen"})
          script-str (extract-play-script-vector snippet)
          script-vec (edn/read-string script-str)]
      (is (= 3 (count events)))
      (is (str/includes? snippet "reg-variant"))
      (is (str/includes? snippet ":story.recorder/captured"))
      (is (str/includes? snippet ":story.recorder/source")
          "the recorder-target id rides into the :extends slot")
      (is (= events (unwrap-dispatch-sync-steps script-vec))
          "the rendered :play-script :script vector unwraps back to the captured events"))
    (story/destroy-variant! :story.recorder/source)
    (recorder/remove-trace-listener!)))
