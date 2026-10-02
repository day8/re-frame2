(ns re-frame.story-recorder-cljs-test
  "CLJS-side tests for the Test Codegen recorder.

  Runs under shadow's `:node-test` build (ns-regexp `cljs-test$`).
  The recorder's pure predicates and state transitions carry no reader
  conditional and are covered on the JVM by
  `re-frame.story-recorder-test`. This ns keeps the paths that do
  differ by platform running under CLJS — the `now-ms*` clock and
  `start-recording!` behind `append` and the start/stop cycle — and
  round-trips the snippet generator through the CLJS reader.

  Browser-only behaviour (Reagent mirror, modal dialog) lives in the
  CLJS-only `re-frame.story.ui.recorder` ns, which
  `re-frame.story.ui.recorder-cljs-test` covers."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [cljs.reader :as edn]
            [clojure.string :as str]
            [re-frame.story.recorder :as rf.story.recorder]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-recorder! [f]
  (rf.story.recorder/clear!)
  (f))

(use-fixtures :each reset-recorder!)

;; ---- pure state machine --------------------------------------------------

(deftest append-captures-recordable-events
  (let [s0 (rf.story.recorder/start rf.story.recorder/initial-state :story.x/y 0)
        s1 (-> s0
               (rf.story.recorder/append [:counter/inc])
               (rf.story.recorder/append [:rf.assert/path-equals [:a] 1])
               (rf.story.recorder/append [:counter/dec]))]
    (is (= [[:counter/inc] [:counter/dec]] (:events s1)))))

;; ---- impure entrypoints --------------------------------------------------

(deftest start-and-stop-cycle
  (rf.story.recorder/start-recording! :story.x/y 0)
  (is (rf.story.recorder/recording?))
  (rf.story.recorder/record-event! [:counter/inc])
  (rf.story.recorder/record-event! [:counter/dec])
  (rf.story.recorder/stop-recording!)
  (is (not (rf.story.recorder/recording?)))
  (is (= [[:counter/inc] [:counter/dec]]
         (rf.story.recorder/recorded-events))))

(deftest toggle!-flips
  (rf.story.recorder/toggle! :story.x/y)
  (is (rf.story.recorder/recording?))
  (rf.story.recorder/toggle! :story.x/y)
  (is (not (rf.story.recorder/recording?))))

;; ---- gen-play-snippet ----------------------------------------------------

(deftest gen-play-snippet-renders-reg-variant
  (let [snippet (rf.story.recorder/gen-play-snippet
                  [[:counter/inc] [:counter/dec]]
                  {:variant-id :story.x/y})]
    (is (str/includes? snippet "reg-variant"))
    (is (str/includes? snippet ":story.x/y"))
    (is (str/includes? snippet "[:counter/inc]"))
    (is (str/includes? snippet "[:counter/dec]"))))

;; ---- mid-recording assertion insertion ----------------------------------

(deftest insert-assertion!-interleaves-with-recorded-events
  (rf.story.recorder/start-recording! :story.x/y 0)
  (rf.story.recorder/record-event! [:counter/inc])
  (rf.story.recorder/insert-assertion! :rf.assert/sub-equals
                              {:sub [:counter] :expected 1})
  (rf.story.recorder/record-event! [:counter/inc])
  (rf.story.recorder/insert-assertion! [:rf.assert/no-warnings])
  (rf.story.recorder/stop-recording!)
  (is (= [[:counter/inc]
          [:rf.assert/sub-equals [:counter] 1]
          [:counter/inc]
          [:rf.assert/no-warnings]]
         (rf.story.recorder/recorded-events))))

(defn- extract-play-script-vector
  "Pull the inner `:script` vector substring out of the rendered snippet
  by walking balanced brackets after the public `:script` body's inner
  `:script` token. The recorder emits the PUBLIC `:script` slot with a
  `{:auto-run? ... :script [...]}` body."
  [snippet]
  (let [start (str/index-of snippet ":script")
        after (subs snippet start)
        open  (str/index-of after "[")
        end   (loop [i (inc open) depth 1]
                (cond
                  (>= i (count after)) nil
                  (zero? depth) i
                  :else (let [c (.charAt after i)]
                          (case c
                            "[" (recur (inc i) (inc depth))
                            "]" (recur (inc i) (dec depth))
                            (recur (inc i) depth)))))]
    (subs after open end)))

(defn- unwrap-dispatch-sync-steps
  "Project the parsed `:script` vector back to the bare event-vector
  list. Each step is `[:dispatch-sync <event-vec>]`."
  [script-vec]
  (mapv second script-vec))

(deftest gen-play-snippet-roundtrips
  (testing "the rendered public :script body vector reads back as
            [:dispatch-sync <event>] steps that unwrap to the original
            events (the recorder emits the public :script slot, and
            gen-play-snippet wraps each captured event as a :dispatch-sync
            step)"
    (let [events     [[:counter/inc] [:auth/login {:id 1}]]
          snip       (rf.story.recorder/gen-play-snippet events {:variant-id :story.x/y})
          script-str (extract-play-script-vector snip)
          script-vec (edn/read-string script-str)]
      (is (some? script-str) "extractor found a :script vector substring")
      (is (not (str/includes? snip ":play-script"))
          "the snippet never emits a :play-script slot")
      (is (every? #(and (vector? %)
                        (= :dispatch-sync (first %)))
                  script-vec)
          "every step is a [:dispatch-sync <event-vec>] form")
      (is (= events (unwrap-dispatch-sync-steps script-vec))
          "unwrapping :dispatch-sync round-trips to the original events"))))
