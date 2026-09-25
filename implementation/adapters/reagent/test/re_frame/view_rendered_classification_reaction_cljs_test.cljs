(ns re-frame.view-rendered-classification-reaction-cljs-test
  "Classifying a view's `:rf.view/rendered` trace stays OUT of the view's
  reactive graph.

  With tracing on, a view that takes render args emits `:rf.view/rendered`
  INSIDE its render, and the classification chokepoint projects those args
  against the frame's elision registry. It reads that registry out of the
  frame's runtime-db projection even in a frame that declares nothing, because
  the read is how it learns there is nothing to redact. On a ratom substrate a
  capturing read there would make the whole runtime-db projection an input of
  the view's render reaction, so every unrelated runtime-db write would
  re-render the view.

  The suite renders a registered view, with a render arg, inside an
  auto-running Reagent reaction — the stand-in for the component's render
  reaction — under the Reagent adapter, in the fixture's `:rf/default` frame,
  which starts with NO elision declarations. It pins:

    * the render reaction does not watch the runtime-db projection;
    * an unrelated runtime-db write does not re-run the render;
    * the render's own input still re-runs it;
    * a declaration added after the first render governs the next render's
      trace, so the registry is read fresh on each render rather than cached.

  The ns ends in -cljs-test so the always-on :node-test build runs it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.ratom :as ratom]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(defn- watches?
  "True when Reagent reaction `rx` currently lists `source` among the reactive
  sources it captured on its last run."
  [rx source]
  (boolean (some #(identical? % source) (array-seq (.-watching ^clj rx)))))

(defn- rendered-traces
  [events]
  (filterv #(= :rf.view/rendered (:operation %)) events))

(deftest view-render-does-not-capture-the-runtime-db-projection
  (let [traces (atom [])
        input  (ratom/atom {:token "first-token"})
        runs   (volatile! 0)]
    (rf/reg-view ^{:rf/id ::labelled} labelled-view [props]
      [:span (:token props)])
    (rf/register-listener! :trace ::rendered (fn [ev] (swap! traces conj ev)))
    (let [render (rf/view ::labelled)
          driver (ratom/run!
                   (vswap! runs inc)
                   (render @input))]
      (try
        (ratom/flush!)
        (let [first-render (first (rendered-traces @traces))
              frame-id     (get-in first-render [:tags :frame])
              runtime-db   (rf.frame/runtime-db-container frame-id)]
          (testing "preconditions: one traced render, carrying its render arg, in a
                    frame that declares nothing"
            (is (= 1 @runs))
            (is (= [{:token "first-token"}]
                   (get-in first-render [:tags :rf.view/render-args]))
                "the render arg reaches the trace raw, so classification ran and found nothing")
            (is (some? runtime-db) "the render resolved a live frame")
            (is (= {} (rf.elision/sensitive-declarations frame-id)))
            (is (= {} (rf.elision/declarations frame-id))))

          (testing "the render reaction watches its input and not the runtime-db projection"
            (is (watches? driver input) "the render captured its own input")
            (is (not (watches? driver runtime-db))
                "the render carries no runtime-db dependency"))

          (testing "an unrelated runtime-db write does not re-run the render"
            (reset! traces [])
            (rf.frame/swap-runtime-db! frame-id assoc ::unrelated 1)
            (ratom/flush!)
            (is (= 1 @runs) "the render did not re-run")
            (is (= [] (rendered-traces @traces)) "no :rf.view/rendered was emitted"))

          (testing "a declaration added after the first render governs the next render"
            (reset! traces [])
            (rf.frame/swap-runtime-db! frame-id
              (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[:token]]})))
            (ratom/flush!)
            (is (= 1 @runs) "adding a declaration is itself an unrelated runtime-db write")
            (reset! input {:token "second-token"})
            (ratom/flush!)
            (is (= 2 @runs) "the render's own input still re-runs it")
            (let [renders (rendered-traces @traces)]
              (is (= 1 (count renders)))
              (is (= [{:token rf.privacy/redacted-sentinel}]
                     (get-in (first renders) [:tags :rf.view/render-args]))
                  "the new declaration redacts the next render's arg")))

          (testing "after that re-render the view still ignores unrelated runtime-db writes"
            (reset! traces [])
            (rf.frame/swap-runtime-db! frame-id assoc ::unrelated 2)
            (ratom/flush!)
            (is (= 2 @runs))
            (is (not (watches? driver runtime-db)))))
        (finally
          (rf/unregister-listener! :trace ::rendered)
          (ratom/dispose! driver))))))
