(ns re-frame.story-recorder-test
  "JVM tests for the Test Codegen recorder: the `recordable-event?` filter,
  the pure state machine, the impure entrypoints over the per-process atom,
  `gen-play-snippet` codegen, the trace-bus listener end to end, and the
  `re-frame.story` recorder facade. The node-test arm is
  `story_recorder_cljs_test.cljs`."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.async :as rf.story.async]
            [re-frame.story.late-bind :as rf.story.late-bind]
            [re-frame.story.recorder :as rf.story.recorder]
            [re-frame.story.recorder.play-export :as rf.story.recorder.play-export]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-recorder! [f]
  (rf.story.recorder/clear!)
  (f))

(use-fixtures :each reset-recorder!)

;; ---- recordable-event? ---------------------------------------------------

(deftest recordable-event?-skips-internal-story-events
  (testing ":rf.story/* and re-frame.story.* internal helpers, and non-event
            shapes, are not recordable"
    (is (= [false false false false false false false]
           (map (comp boolean rf.story.recorder/recordable-event?)
                [[:rf.story/lifecycle-tick]
                 [:re-frame.story.runtime/append-assertion {:a 1}]
                 [:re-frame.story.assertions/append {:r 1}]
                 nil [] "not-a-vector" [{} "no-keyword-id"]])))))

;; ---- pure state machine --------------------------------------------------

(deftest start-clobbers-previous-recording
  (testing "starting a fresh recording drops any captured events"
    (let [s0 (-> rf.story.recorder/initial-state
                 (rf.story.recorder/start :story.a/x 1000)
                 (rf.story.recorder/append [:a 1])
                 (rf.story.recorder/append [:b 2]))]
      (is (= [[] :story.b/y]
             ((juxt :events :variant-id) (rf.story.recorder/start s0 :story.b/y 2000)))))))

(deftest append-skips-assertions-and-internals
  (testing "append filters non-recordable events"
    (let [s0 (rf.story.recorder/start rf.story.recorder/initial-state :story.x/y 0)
          s1 (-> s0
                 (rf.story.recorder/append [:rf.assert/path-equals [:a] 1])
                 (rf.story.recorder/append [:counter/inc])
                 (rf.story.recorder/append [:re-frame.story.runtime/append-assertion {}])
                 (rf.story.recorder/append [:counter/dec]))]
      (is (= [[:counter/inc] [:counter/dec]]
             (:events s1))))))

(deftest append-noop-when-not-recording
  (testing "append drops events when :recording? is false"
    (let [s0 rf.story.recorder/initial-state
          s1 (rf.story.recorder/append s0 [:counter/inc])]
      (is (= [] (:events s1))))))

;; ---- impure entrypoints --------------------------------------------------

(deftest toggle!-flips
  (testing "toggle! starts when idle and stops when recording"
    (rf.story.recorder/toggle! :story.x/y)
    (is (rf.story.recorder/recording?))
    (rf.story.recorder/record-event! [:foo/bar])
    (rf.story.recorder/toggle! :story.x/y)
    (is (not (rf.story.recorder/recording?)))
    (is (= [[:foo/bar]] (rf.story.recorder/recorded-events)))))

(deftest clear!-resets-everything
  (rf.story.recorder/start-recording! :story.x/y 0)
  (rf.story.recorder/record-event! [:counter/inc])
  (rf.story.recorder/clear!)
  (is (= rf.story.recorder/initial-state (rf.story.recorder/current-state))))

;; ---- gen-play-snippet ----------------------------------------------------

(deftest gen-play-snippet-reads-back-as-the-reg-variant-form
  (testing "the snippet reads back as exactly the (reg-variant …) form its
            opts describe: the public :script slot (never :play-script), each
            event wrapped as a [:dispatch-sync <event>] step, the rf.story
            alias unless :alias names another, and :doc / :extends only when
            given"
    (are [events opts form]
         (= form (edn/read-string (rf.story.recorder/gen-play-snippet events opts)))
      ;; no events: the empty :script vector still renders
      []
      {:variant-id :story.x/y}
      '(rf.story/reg-variant :story.x/y
         {:script {:auto-run? true :script []}})

      ;; payload-bearing events keep their order and payloads
      [[:counter/inc]
       [:auth/login {:email "alice@example.com" :remember? true}]
       [:cart/add-item :widget-x 3]]
      {:variant-id :story.x/y}
      '(rf.story/reg-variant :story.x/y
         {:script {:auto-run? true
                   :script    [[:dispatch-sync [:counter/inc]]
                               [:dispatch-sync [:auth/login {:email "alice@example.com" :remember? true}]]
                               [:dispatch-sync [:cart/add-item :widget-x 3]]]}})

      ;; every optional slot, and a custom alias
      [[:counter/inc]]
      {:variant-id :story.counter/recorded
       :doc        "user inc once"
       :extends    :story.counter/happy-path
       :alias      "rf"}
      '(rf/reg-variant :story.counter/recorded
         {:doc     "user inc once"
          :extends :story.counter/happy-path
          :script  {:auto-run? true :script [[:dispatch-sync [:counter/inc]]]}}))))

;; ---- DOM-event entries + per-event timestamps ---------------------------

(deftest append-dom-lands-each-kind-as-one-entry
  (testing "append-dom translates each DOM-event vector into exactly one
            :entries map"
    (let [s0 (rf.story.recorder/start rf.story.recorder/initial-state :story.x/y 0)]
      (are [dom-event entry] (= [entry] (:entries (rf.story.recorder/append-dom s0 dom-event)))
        [:dom/click "[data-test=\"a\"]" 100]
        {:kind :dom/click :selector "[data-test=\"a\"]" :t 100}

        [:dom/type "[id=\"x\"]" "alice" 200]
        {:kind :dom/type :selector "[id=\"x\"]" :text "alice" :t 200}

        [:dom/submit "[id=\"login\"]" 300]
        {:kind :dom/submit :selector "[id=\"login\"]" :t 300}))))

(deftest append-dom-rejects-malformed
  (let [s0 (rf.story.recorder/start rf.story.recorder/initial-state :story.x/y 0)]
    (doseq [ev [nil [] [:not-a-dom-kind "x" 0]]]
      (is (= [] (:entries (rf.story.recorder/append-dom s0 ev))) (pr-str ev))))
  (is (= [] (:entries (rf.story.recorder/append-dom rf.story.recorder/initial-state
                                                     [:dom/click "[data-test=\"x\"]" 0])))
      "DOM events drop when no recording is in flight"))

(deftest append-event-stamps-timestamp
  (testing "(append state event now-ms) keeps the bare event and stamps the
            entry's :t relative to the recording start"
    (let [s1 (-> rf.story.recorder/initial-state
                 (rf.story.recorder/start :story.x/y 1000)
                 (rf.story.recorder/append [:counter/inc] 1250))]
      (is (= [[[:counter/inc]] [{:kind :event/dispatch :event [:counter/inc] :t 250}]]
             [(:events s1) (map #(select-keys % [:kind :event :t]) (:entries s1))])))))

(deftest record-dom-event!-impure-entry
  (testing "the impure record-dom-event! entry mutates the shared atom"
    (rf.story.recorder/start-recording! :story.x/y)
    (rf.story.recorder/record-dom-event! [:dom/click "[data-test=\"go\"]" 50])
    (rf.story.recorder/record-dom-event! [:dom/type "[id=\"x\"]" "hi" 100])
    (is (= [:dom/click :dom/type] (map :kind (rf.story.recorder/recorded-entries))))))

;; ---- mid-recording assertion insertion ----------------------------------

(deftest assertion-vocabulary-covers-canonical-seven
  (is (= #{:rf.assert/path-equals :rf.assert/path-matches :rf.assert/sub-equals
           :rf.assert/dispatched? :rf.assert/state-is :rf.assert/no-warnings
           :rf.assert/effect-emitted}
         (set (map :id rf.story.recorder/assertion-vocabulary)))
      "the picker vocabulary enumerates the seven canonical ids from spec/004"))

(deftest assertion-vocabulary-entries-are-well-formed
  (testing "every vocabulary entry carries the picker's required keys"
    (doseq [entry rf.story.recorder/assertion-vocabulary]
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
  (testing "make-assertion builds the canonical event vector from a payload map,
            fills a missing field with nil, and answers nil for an unknown id"
    (doseq [[id payload event]
            [[:rf.assert/path-equals {:path [:auth :status] :expected :ok}
              [:rf.assert/path-equals [:auth :status] :ok]]
             [:rf.assert/sub-equals {:sub [:counter] :expected 3} [:rf.assert/sub-equals [:counter] 3]]
             [:rf.assert/dispatched? {:event [:counter/inc]} [:rf.assert/dispatched? [:counter/inc]]]
             [:rf.assert/state-is {:machine :auth/machine :state :authenticated}
              [:rf.assert/state-is :auth/machine :authenticated]]
             [:rf.assert/effect-emitted {:fx-id :http} [:rf.assert/effect-emitted :http]]
             [:rf.assert/no-warnings {} [:rf.assert/no-warnings]]
             [:rf.assert/path-equals {:path [:auth :status]} [:rf.assert/path-equals [:auth :status] nil]]
             [:rf.assert/not-a-real-one {} nil]
             [:counter/inc {} nil]]]
      (is (= event (rf.story.recorder/make-assertion id payload)) (pr-str id payload)))))

(deftest append-assertion-rejects-non-assertions
  (testing "append-assertion is a no-op for non-:rf.assert/* event vectors"
    (let [s0 (rf.story.recorder/start rf.story.recorder/initial-state :story.x/y 0)
          s1 (-> s0
                 (rf.story.recorder/append-assertion [:counter/inc])
                 (rf.story.recorder/append-assertion [:rf.story/lifecycle-tick])
                 (rf.story.recorder/append-assertion nil)
                 (rf.story.recorder/append-assertion []))]
      (is (= [] (:events s1))
          "only :rf.assert/* event vectors land via append-assertion"))))

(deftest insert-assertion!-noop-when-not-recording
  (testing "insert-assertion! is harmless when no recording is in flight"
    (is (not (rf.story.recorder/recording?)))
    (rf.story.recorder/insert-assertion! :rf.assert/no-warnings {})
    (is (= [] (rf.story.recorder/recorded-events)))))

(deftest gen-play-snippet-round-trips-with-inserted-assertions
  (testing "user dispatches and inserted assertions interleave, and the snippet's
            :script steps unwrap back to them"
    (rf.story.recorder/start-recording! :story.counter/x 0)
    (rf.story.recorder/record-event! [:counter/inc])
    (rf.story.recorder/insert-assertion! :rf.assert/sub-equals {:sub [:counter] :expected 1})
    (rf.story.recorder/record-event! [:counter/by 7])
    (rf.story.recorder/insert-assertion! :rf.assert/path-equals {:path [:n] :expected 8})
    (rf.story.recorder/stop-recording!)
    (let [events (rf.story.recorder/recorded-events)
          form   (edn/read-string (rf.story.recorder/gen-play-snippet
                                    events {:variant-id :story.counter/recorded
                                            :extends    :story.counter/x}))]
      (is (= [[:counter/inc]
              [:rf.assert/sub-equals [:counter] 1]
              [:counter/by 7]
              [:rf.assert/path-equals [:n] 8]]
             events))
      (is (= events (mapv second (-> form (nth 2) :script :script)))))))

;; ---- end-to-end: trace-bus integration -----------------------------------

(defn- reset-rf-state! []
  (rf.story.recorder/remove-trace-listener!)
  (rf.story.recorder/clear!)
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(defn- record-on!
  "Register and run `vid`'s frame, install the trace listener and start recording it."
  [vid]
  (rf.story/reg-variant vid {})
  (rf.story.async/deref-blocking (rf.story/run-variant vid) 5000)
  (rf.story.recorder/install-trace-listener!)
  (rf.story.recorder/start-recording! vid))

(deftest trace-listener-captures-dispatch-into-recording
  (testing "with a recording in flight, a dispatch against the target frame is
            captured as a bare event and a timestamped :event/dispatch entry"
    (reset-rf-state!)
    (rf/reg-event :counter/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/reg-event :counter/dec (fn [{:keys [db]} _] {:db (update db :n (fnil dec 0))}))
    (record-on! :story.recorder/v)
    (doseq [ev [[:counter/inc] [:counter/inc] [:counter/dec]]]
      (rf/dispatch-sync ev {:frame :story.recorder/v}))
    (rf.story.recorder/stop-recording!)
    (let [events  [[:counter/inc] [:counter/inc] [:counter/dec]]
          entries (rf.story.recorder/recorded-entries)]
      (is (= events (rf.story.recorder/recorded-events) (mapv :event entries)))
      (is (every? #(and (= :event/dispatch (:kind %)) (number? (:t %))) entries)))
    (rf.story/destroy-variant! :story.recorder/v)
    (rf.story.recorder/remove-trace-listener!)))

(deftest trace-listener-ignores-cross-frame-traffic
  (testing "dispatches to a non-target frame don't appear in the recording"
    (reset-rf-state!)
    (rf/reg-event :counter/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf.story/reg-variant :story.recorder/other {})
    (rf.story.async/deref-blocking (rf.story/run-variant :story.recorder/other) 5000)
    (record-on! :story.recorder/target)
    (doseq [frame [:story.recorder/target :story.recorder/other :story.recorder/target]]
      (rf/dispatch-sync [:counter/inc] {:frame frame}))
    (rf.story.recorder/stop-recording!)
    (is (= [[:counter/inc] [:counter/inc]] (rf.story.recorder/recorded-events)))
    (rf.story/destroy-variant! :story.recorder/target)
    (rf.story/destroy-variant! :story.recorder/other)
    (rf.story.recorder/remove-trace-listener!)))

(deftest trace-listener-redacts-sensitive-dispatches-end-to-end
  (testing "an event whose registration classifies payload paths is recorded in
            position with :rf/redacted at each classified path while an
            unclassified sibling rides raw — the recorder reads the dispatched
            trace, which the framework's registration redaction has projected"
    (reset-rf-state!)
    (rf/reg-event :counter/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/reg-event :auth/login {:sensitive [[:password] [:totp]]} (fn [{:keys [db]} _] {:db db}))
    (record-on! :story.recorder/sens-end-to-end)
    (doseq [ev [[:counter/inc] [:auth/login {:user "ada" :password "shh" :totp "123456"}] [:counter/inc]]]
      (rf/dispatch-sync ev {:frame :story.recorder/sens-end-to-end}))
    (rf.story.recorder/stop-recording!)
    (is (= [[:counter/inc]
            [:auth/login {:user "ada" :password :rf/redacted :totp :rf/redacted}]
            [:counter/inc]]
           (rf.story.recorder/recorded-events)))
    (rf.story/destroy-variant! :story.recorder/sens-end-to-end)
    (rf.story.recorder/remove-trace-listener!)))

(defn- with-dom-rail-hooks
  "Call `f` with stand-in `:recorder/inside-dom-step?` and
  `:recorder/redact-typed-secrets` hooks — the seams the CLJS DOM-capture
  rail publishes — removing them afterwards."
  [inside-dom-step? redact f]
  (try
    (rf.story.late-bind/set-fn! :recorder/inside-dom-step? inside-dom-step?)
    (rf.story.late-bind/set-fn! :recorder/redact-typed-secrets redact)
    (f)
    (finally
      (swap! rf.story.late-bind/hooks dissoc
             :recorder/inside-dom-step? :recorder/redact-typed-secrets))))

(deftest trace-listener-records-the-dom-step-or-its-dispatch-never-both
  (testing "a dispatch fired while the DOM rail handles an interaction it
            records as a step is skipped (replaying the step fires it again);
            one fired outside is recorded, with typed secrets redacted"
    (reset-rf-state!)
    (rf/reg-event :login/set-password (fn [{:keys [db]} [_ pw]] {:db (assoc db :pw pw)}))
    (rf/reg-event :login/submit (fn [{:keys [db]} _] {:db db}))
    (let [inside? (atom false)]
      (with-dom-rail-hooks
        (fn [] @inside?)
        (fn [ev] (mapv #(if (= "hunter2" %) "[:rf/redacted]" %) ev))
        (fn []
          (record-on! :story.recorder/login)
          (reset! inside? true)
          (rf/dispatch-sync [:login/submit] {:frame :story.recorder/login})
          (reset! inside? false)
          (rf/dispatch-sync [:login/set-password "hunter2"] {:frame :story.recorder/login})
          (rf.story.recorder/stop-recording!)
          (is (= [[:login/set-password "[:rf/redacted]"]]
                 (rf.story.recorder/recorded-events))))))
    (rf.story/destroy-variant! :story.recorder/login)
    (rf.story.recorder/remove-trace-listener!)))

;; The public `re-frame.story` recorder boundary, pinned so the docs
;; (tools/story/spec/API.md §Recorder facade, spec/005 §Recorder) and the
;; implementation cannot drift apart.

(def ^:private intended-recorder-facade-vars
  "The recorder entries the facade documents: the recorder lifecycle, the
  `gen-play-snippet` codegen, and `recording->script-body`, its runtime
  data->data counterpart re-exported from the `play-export` sub-ns."
  '#{start-recording!
     stop-recording!
     clear-recording!
     recording?
     recorder-state
     gen-play-snippet
     recording->script-body})

(deftest facade-exposes-every-documented-recorder-var
  (doseq [sym intended-recorder-facade-vars]
    (is (fn? (some-> (ns-resolve 're-frame.story sym) deref))
        (str "re-frame.story/" sym " is missing from the facade or not a fn"))))

(deftest facade-gen-play-snippet-emits-public-script-slot
  (testing "the facade's gen-play-snippet renders exactly the recorder's form,
            whose public :script slot gen-play-snippet-reads-back pins"
    (is (= (rf.story.recorder/gen-play-snippet [[:counter/inc]] {:variant-id :story.x/y})
           (rf.story/gen-play-snippet [[:counter/inc]] {:variant-id :story.x/y})))))

(deftest facade-recording->script-body-delegates-to-play-export
  (testing "the facade's recording->script-body returns the live play body the
            runner executes — one runner step per event under :script, never
            :play-script — through the play-export sub-ns in both arities"
    (let [events  [[:counter/inc] [:counter/by 7]]
          one-arg (rf.story/recording->script-body events)]
      (is (= (rf.story.recorder.play-export/recording->script-body events) one-arg))
      (is (= [2 true false]
             [(count (:script one-arg)) (contains? one-arg :auto-run?) (contains? one-arg :play-script)]))
      (is (= "rt" (:name (rf.story/recording->script-body events {:name "rt"})))))))

;; A recording's address is the variant frame (EP-0023): neither the captured
;; state nor the replayable play body carries a realm key, and replay dispatches
;; frame-scoped.

(deftest recording-state-is-the-bare-frame-target-shape
  (is (= {:recording? true :variant-id :story.x/y
          :events [] :cofx [] :entries [] :started-ms 1000}
         (rf.story.recorder/start rf.story.recorder/initial-state :story.x/y 1000))))

(deftest end-to-end-recording-and-play-body-carry-no-realm-key
  (testing "a recording against a variant frame captures no realm key, and its
            translated play body is the frame-only {:script :auto-run?} shape"
    (reset-rf-state!)
    (rf/reg-event :counter/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (record-on! :story.frame/target)
    (rf/dispatch-sync [:counter/inc] {:frame :story.frame/target})
    (rf/dispatch-sync [:counter/inc] {:frame :story.frame/target})
    (let [final-state (rf.story.recorder/stop-recording!)]
      (is (not-any? #(= "rf.realm" (namespace %)) (keys final-state)))
      (is (= :story.frame/target (:variant-id final-state)))
      (is (= [:auto-run? :script]
             (sort (keys (rf.story/recording->script-body (:events final-state)))))))
    (rf.story/destroy-variant! :story.frame/target)
    (rf.story.recorder/remove-trace-listener!)))

;; ---- a cascaded child is not a step of its own ---------------------------

(deftest trace-listener-skips-fx-cascaded-children
  (testing "an event another event dispatched through its :fx is not recorded
            as a step — replaying the root re-dispatches it, so the replay
            reproduces the recorded app-db instead of running the child twice"
    (reset-rf-state!)
    (rf/reg-event :p/plain  (fn [{:keys [db]} _] {:db (update db :plain (fnil inc 0))}))
    (rf/reg-event :p/submit (fn [{:keys [db]} _] {:db (update db :submits (fnil inc 0))
                                                  :fx [[:dispatch [:p/audit]]]}))
    (rf/reg-event :p/audit  (fn [{:keys [db]} _] {:db (update db :audits (fnil inc 0))}))
    (record-on! :story.cascade/source)
    (rf/dispatch-sync [:p/plain]  {:frame :story.cascade/source})
    (rf/dispatch-sync [:p/submit] {:frame :story.cascade/source})
    (rf.story.recorder/stop-recording!)
    (let [recorded-db (select-keys (rf/app-db-value :story.cascade/source)
                                   [:plain :submits :audits])]
      (is (= {:plain 1 :submits 1 :audits 1} recorded-db)
          "control: the recorded session ran the :fx child once")
      (is (= [[:p/plain] [:p/submit]] (rf.story.recorder/recorded-events))
          "only the two root dispatches are steps")
      (rf.story/reg-variant :story.cascade/replay
        {:extends :story.cascade/source
         :script  (rf.story.recorder.play-export/recording->script-body
                    (rf.story.recorder/recorded-entries))})
      (let [result (.get ^java.util.concurrent.CompletableFuture
                         (rf.story/run :story.cascade/replay))]
        (is (= recorded-db (select-keys (:app-db result) [:plain :submits :audits]))
            "the replay reproduces the recorded app-db")))
    (rf.story/destroy-variant! :story.cascade/source)
    (rf.story.recorder/remove-trace-listener!)))

;; ---- a timer child's wait covers its scheduled delay ---------------------

(defn- dispatched-trace
  "The `:rf.event/dispatched` trace the recorder's listener reads for `event`
  on `frame-id`, with `tags` merged over the base tags."
  [frame-id event tags]
  {:op-type   :rf.event
   :operation :rf.event/dispatched
   :tags      (merge {:frame frame-id :rf.event/v event} tags)})

(deftest a-timer-child-wait-covers-its-scheduled-delay
  (testing "the recorder's clock can stamp a :dispatch-later child one
            millisecond under its scheduled delay after the root that armed
            it. The marker carries the delay, so the export waits all of it"
    (let [listen @#'rf.story.recorder/trace-listener
          at     (fn [now-ms ev]
                   (with-redefs [rf.story.recorder/now-ms* (constantly now-ms)]
                     (listen ev)))]
      (rf.story.recorder/start-recording! :story.timer/v 1000)
      (at 1001 (dispatched-trace :story.timer/v [:t/root] {}))
      ;; A fired `:dispatch-later` child as the browser runtime traces it: no
      ;; parent dispatch id, and the seam's `:source-detail {:ms …}` stamp.
      (at 1080 (dispatched-trace :story.timer/v [:t/child]
                                 {:rf.event/source-detail {:ms 80}}))
      (let [[root child :as entries] (rf.story.recorder/recorded-entries)]
        (is (= 79 (- (:t child) (:t root)))
            "control: the measured gap is one millisecond under the 80ms delay")
        (is (= {:kind :event/timer-child :ms 80} (select-keys child [:kind :ms]))
            "the marker carries the child's scheduled delay")
        (is (= [[:dispatch [:t/root]] [:wait 80]]
               (:script (rf.story.recorder.play-export/recording->script-body entries)))
            "the export waits the whole delay, not the measured gap"))
      (rf.story.recorder/clear!))))
