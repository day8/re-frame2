(ns re-frame.story-recorder-cljs-test
  "CLJS-side tests for the Test Codegen recorder (rf2-5fc15).

  Runs under shadow's `:node-test` build (ns-regexp `cljs-test$`).
  The pure-data corpus is identical to the JVM
  `re-frame.story-recorder-test` arm — same predicates and same
  snippet generator — so the cljs build proves the namespace compiles
  under CLJS as well as the JVM.

  Browser-only behaviour (Reagent mirror, modal dialog) lives in the
  CLJS-only `re-frame.story.ui.recorder` ns and is exercised under
  the `:browser-test` target via a separate Playwright spec when that
  layer ships."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [cljs.reader :as edn]
            [clojure.string :as str]
            [re-frame.story.recorder :as recorder]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-recorder! [f]
  (recorder/clear!)
  (f))

(use-fixtures :each reset-recorder!)

;; ---- recordable-event? ---------------------------------------------------

(deftest recordable-event?-accepts-user-events
  (is (recorder/recordable-event? [:counter/inc]))
  (is (recorder/recordable-event? [:auth/login {:email "a@b"}])))

(deftest recordable-event?-skips-assertions
  (is (not (recorder/recordable-event? [:rf.assert/path-equals [:a] 1])))
  (is (not (recorder/recordable-event? [:rf.assert/no-warnings]))))

(deftest recordable-event?-skips-internal-story-events
  (is (not (recorder/recordable-event? [:rf.story/lifecycle-tick])))
  (is (not (recorder/recordable-event?
             [:re-frame.story.runtime/append-assertion {}]))))

;; ---- pure state machine --------------------------------------------------

(deftest start-replaces-state
  (let [s (recorder/start recorder/initial-state :story.x/y 1000)]
    (is (:recording? s))
    (is (= :story.x/y (:variant-id s)))
    (is (= [] (:events s)))))

(deftest append-captures-recordable-events
  (let [s0 (recorder/start recorder/initial-state :story.x/y 0)
        s1 (-> s0
               (recorder/append [:counter/inc])
               (recorder/append [:rf.assert/path-equals [:a] 1])
               (recorder/append [:counter/dec]))]
    (is (= [[:counter/inc] [:counter/dec]] (:events s1)))))

(deftest stop-preserves-events
  (let [s (-> recorder/initial-state
              (recorder/start :story.x/y 0)
              (recorder/append [:counter/inc])
              (recorder/stop))]
    (is (not (:recording? s)))
    (is (= [[:counter/inc]] (:events s)))))

;; ---- impure entrypoints --------------------------------------------------

(deftest start-and-stop-cycle
  (recorder/start-recording! :story.x/y 0)
  (is (recorder/recording?))
  (recorder/record-event! [:counter/inc])
  (recorder/record-event! [:counter/dec])
  (recorder/stop-recording!)
  (is (not (recorder/recording?)))
  (is (= [[:counter/inc] [:counter/dec]]
         (recorder/recorded-events))))

(deftest toggle!-flips
  (recorder/toggle! :story.x/y)
  (is (recorder/recording?))
  (recorder/toggle! :story.x/y)
  (is (not (recorder/recording?))))

;; ---- gen-play-snippet ----------------------------------------------------

(deftest gen-play-snippet-renders-reg-variant
  (let [snippet (recorder/gen-play-snippet
                  [[:counter/inc] [:counter/dec]]
                  {:variant-id :story.x/y})]
    (is (str/includes? snippet "reg-variant"))
    (is (str/includes? snippet ":story.x/y"))
    (is (str/includes? snippet "[:counter/inc]"))
    (is (str/includes? snippet "[:counter/dec]"))))

;; ---- mid-recording assertion insertion (rf2-39u9e) ----------------------

(deftest assertion-vocabulary-covers-canonical-seven
  (let [ids (set (map :id recorder/assertion-vocabulary))]
    (is (= #{:rf.assert/path-equals
             :rf.assert/path-matches
             :rf.assert/sub-equals
             :rf.assert/dispatched?
             :rf.assert/state-is
             :rf.assert/no-warnings
             :rf.assert/effect-emitted}
           ids))))

(deftest make-assertion-builds-well-formed-events
  (is (= [:rf.assert/path-equals [:auth :status] :ok]
         (recorder/make-assertion :rf.assert/path-equals
                                  {:path [:auth :status] :expected :ok})))
  (is (= [:rf.assert/sub-equals [:counter] 3]
         (recorder/make-assertion :rf.assert/sub-equals
                                  {:sub [:counter] :expected 3})))
  (is (= [:rf.assert/no-warnings]
         (recorder/make-assertion :rf.assert/no-warnings {})))
  (is (nil? (recorder/make-assertion :rf.assert/not-a-real-one {}))))

(deftest insert-assertion!-interleaves-with-recorded-events
  (recorder/start-recording! :story.x/y 0)
  (recorder/record-event! [:counter/inc])
  (recorder/insert-assertion! :rf.assert/sub-equals
                              {:sub [:counter] :expected 1})
  (recorder/record-event! [:counter/inc])
  (recorder/insert-assertion! [:rf.assert/no-warnings])
  (recorder/stop-recording!)
  (is (= [[:counter/inc]
          [:rf.assert/sub-equals [:counter] 1]
          [:counter/inc]
          [:rf.assert/no-warnings]]
         (recorder/recorded-events))))

(deftest insert-assertion!-rejects-non-assertion
  (recorder/start-recording! :story.x/y 0)
  (recorder/insert-assertion! [:counter/inc])
  (recorder/insert-assertion! [:rf.story/lifecycle-tick])
  (is (= [] (recorder/recorded-events))))

(deftest gen-play-snippet-roundtrips
  (let [events [[:counter/inc] [:auth/login {:id 1}]]
        snip   (recorder/gen-play-snippet events {:variant-id :story.x/y})
        start  (str/index-of snip ":play")
        after  (subs snip start)
        open   (str/index-of after "[")
        end    (loop [i (inc open) depth 1]
                 (cond
                   (>= i (count after)) nil
                   (zero? depth) i
                   :else (let [c (.charAt after i)]
                           (case c
                             "[" (recur (inc i) (inc depth))
                             "]" (recur (inc i) (dec depth))
                             (recur (inc i) depth)))))
        play-str (subs after open end)]
    (is (= events (edn/read-string play-str)))))
