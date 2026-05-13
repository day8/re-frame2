(ns re-frame.story-save-variant-cljs-test
  "CLJS-side tests for the save-current-canvas-state-as-variant flow
  (rf2-one3t).

  Runs under shadow's `:node-test` build (ns-regexp `cljs-test$`).
  The pure-data corpus mirrors the JVM `re-frame.story-save-variant-
  test` arm — same args-snapshot + snippet generator + dialog state
  machine — so the cljs build proves the namespace compiles under CLJS
  as well as the JVM.

  Browser-only behaviour (Reagent ratom, modal dialog rendering) lives
  in the CLJS-only `re-frame.story.ui.save-variant` ns and is exercised
  under the `:browser-test` target via a separate Playwright spec
  when that layer ships."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [cljs.reader :as edn]
            [clojure.string :as str]
            [re-frame.story :as story]
            [re-frame.story.save-variant :as save-variant]
            [re-frame.story.ui.state :as state]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! [f]
  (story/clear-all!)
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!)
  (save-variant/set-open-dialog-fn! nil)
  (f))

(use-fixtures :each reset-all!)

;; ---- snapshot-args -------------------------------------------------------

(deftest snapshot-args-returns-resolved-args
  (story/reg-variant :story.snap/v
    {:args {:label "hello" :n 1}
     :events []})
  (let [snap (save-variant/snapshot-args :story.snap/v)]
    (is (= "hello" (:label snap)))
    (is (= 1 (:n snap)))))

(deftest snapshot-args-includes-cell-overrides
  (story/reg-variant :story.snap/v
    {:args   {:label "before" :keep "yes"}
     :events []})
  (let [snap (save-variant/snapshot-args
               :story.snap/v
               {:cell-overrides {:label "after"}})]
    (is (= "after" (:label snap)))
    (is (= "yes"   (:keep snap)))))

;; ---- gen-variant-snippet -------------------------------------------------

(deftest gen-variant-snippet-renders-reg-variant
  (let [snip (save-variant/gen-variant-snippet
               {:variant-id :story.counter/saved
                :extends    :story.counter/happy-path
                :args       {:label "hi" :n 3}})]
    (is (str/includes? snip "reg-variant"))
    (is (str/includes? snip ":story.counter/saved"))
    (is (str/includes? snip ":story.counter/happy-path"))
    (is (str/includes? snip ":args"))
    (is (str/includes? snip ":label"))
    (is (str/includes? snip "\"hi\""))))

(deftest gen-variant-snippet-empty-args-renders-empty-map
  (let [snip (save-variant/gen-variant-snippet
               {:variant-id :story.x/y :args {}})]
    (is (str/includes? snip ":args"))
    (is (str/includes? snip "{}"))))

(deftest gen-variant-snippet-args-roundtrip
  (let [args   {:label "alice" :n 42}
        snip   (save-variant/gen-variant-snippet
                 {:variant-id :story.x/y :args args})
        start  (str/index-of snip ":args")
        after  (subs snip start)
        open   (str/index-of after "{")
        end    (loop [i (inc open) depth 1]
                 (cond
                   (>= i (count after)) nil
                   (zero? depth) i
                   :else (let [c (.charAt after i)]
                           (case c
                             "{" (recur (inc i) (inc depth))
                             "}" (recur (inc i) (dec depth))
                             (recur (inc i) depth)))))
        args-str (subs after open end)]
    (is (= args (edn/read-string args-str)))))

;; ---- default-variant-id --------------------------------------------------

(deftest default-variant-id-derives-from-source
  (let [k (save-variant/default-variant-id :story.counter/happy-path 12345)]
    (is (qualified-keyword? k))
    (is (= "story.counter" (namespace k)))
    (is (str/starts-with? (name k) "saved-"))))

(deftest default-variant-id-nil-for-unqualified
  (is (nil? (save-variant/default-variant-id :unqualified 0))))

;; ---- dialog state machine -------------------------------------------------

(deftest open-builds-dialog-state
  (let [s (save-variant/open save-variant/initial-dialog-state
                             :story.x/y {:n 1} 1000)]
    (is (:open? s))
    (is (= :story.x/y (:source-id s)))
    (is (= {:n 1} (:args s)))))

(deftest close-returns-idle
  (let [opened (save-variant/open save-variant/initial-dialog-state
                                  :story.x/y {} 0)]
    (is (= save-variant/initial-dialog-state
           (save-variant/close opened)))))

;; ---- save-current-as-variant! --------------------------------------------

(deftest save-current-as-variant!-triggers-callback
  (story/reg-variant :story.snap/v {:args {:n 7} :events []})
  (state/swap-state! state/select-variant :story.snap/v)
  (let [captured (atom nil)]
    (save-variant/set-open-dialog-fn!
      (fn [source-id args _]
        (reset! captured {:source-id source-id :args args})))
    (let [result (save-variant/save-current-as-variant!)]
      (is (some? @captured))
      (is (= :story.snap/v (:source-id @captured)))
      (is (= 7 (-> @captured :args :n)))
      (is (= :story.snap/v (:source-id result))))))

(deftest save-current-as-variant!-nil-without-focus
  (state/swap-state! state/select-variant nil)
  (let [captured (atom nil)]
    (save-variant/set-open-dialog-fn!
      (fn [_ _ _] (reset! captured :fired)))
    (is (nil? (save-variant/save-current-as-variant!)))
    (is (nil? @captured))))
