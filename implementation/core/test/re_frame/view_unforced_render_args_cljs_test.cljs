(ns re-frame.view-unforced-render-args-cljs-test
  "A view taking an endless seq renders in a classified frame, and its
  `:rf.view/rendered` trace carries the seq as `:rf/redacted` (Spec 015
  §`elide-wire-value`). The trace fires inside the render, after the render
  body, so a trace walk that realised the seq would never let the render
  return.

  The first seq is GUARDED — it counts the elements computed and throws past
  a bound — so a walk that forces it fails the render at once. `(range)` is
  rendered only after that render has returned with nothing computed beyond
  what the body used, because a CLJS walk cannot be pre-empted."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(def ^:private this-ns "re-frame.view-unforced-render-args-cljs-test")

(rf/reg-classification ::classified {:sensitive [[:auth :token]]})

(rf/reg-view ^{:rf/id ::first-three} first-three [xs]
  (into [:ol] (map (fn [x] [:li x])) (take 3 xs)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- guarded-endless
  "An endless unchunked lazy seq 0, 1, 2, … that records in `computed` how
  many elements have been computed, and throws when asked for the
  ten-thousandth."
  [computed]
  (letfn [(step [i]
            (lazy-seq
              (when (>= i 10000)
                (throw (ex-info "realised past the guard" {:i i})))
              (swap! computed max (inc i))
              (cons i (step (inc i)))))]
    (step 0)))

(defn- classified-frame!
  "A frame whose image selects this namespace alone, so the one
  classification above is the whole of its policy."
  []
  (rf/make-frame {:id     ::classified
                  :images [(rf/image {:id ::classified :select-ns {:include [this-ns]}})]})
  ::classified)

(defn- render-traced
  "Render `::first-three` with `arg` in `frame`. Returns the render's
  `[(first hiccup) element-count]`, or `::threw`, beside the render args each
  `:rf.view/rendered` trace carried."
  [frame arg]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::rendered
                           (fn [ev]
                             (when (= :rf.view/rendered (:operation ev))
                               (swap! traces conj (get-in ev [:tags :rf.view/render-args])))))
    (try
      (let [out (try (rf/with-frame frame
                       (let [h ((rf/view ::first-three) arg)]
                         [(first h) (count (filter vector? h))]))
                     (catch :default _ ::threw))]
        [out @traces])
      (finally
        (rf/unregister-listener! :trace ::rendered)))))

(deftest a-view-taking-an-endless-seq-renders-and-traces-it-redacted
  (let [frame    (classified-frame!)
        computed (atom 0)
        [out traced] (render-traced frame (guarded-endless computed))]
    (testing "the render returned, having computed the three elements it used"
      (is (= [[:ol 3] 3] [out @computed])))
    (testing "its trace carries the seq as :rf/redacted"
      (is (= [[rf.privacy/redacted-sentinel]] traced)))
    (testing "a view taking (range)"
      (if (= 3 @computed)
        (is (= [[:ol 3] [[rf.privacy/redacted-sentinel]]] (render-traced frame (range))))
        (is false "not rendered: the trace walk realised a guarded seq, so this render would hang")))))
