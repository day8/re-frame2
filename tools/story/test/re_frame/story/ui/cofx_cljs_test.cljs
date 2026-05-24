(ns re-frame.story.ui.cofx-cljs-test
  "Regression guard for rf2-gg4dz — the `:story/active-modes` and
  `:story/active-args` cofx bodies must inject their value under
  `[:coeffects <cofx-id>]`. If the body regresses to the wrong shape
  (assoc-at-top-level of ctx) the consuming event handler binds nil
  silently and the chrome's mode/arg awareness disappears.

  Both cofx bodies are 2-arity (take `ctx` plus an `inject-cofx`-passed
  value). The shape contract is identical to the 1-arity form."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.story.ui.cofx :as ui-cofx]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (ui-cofx/install-canonical-cofx!))}))

(deftest story-active-modes-cofx-shape
  (testing ":story/active-modes cofx body assoc's into [:coeffects :story/active-modes]"
    (let [meta    (rf/handler-meta :cofx :story/active-modes)
          handler (:handler-fn meta)
          result  (handler {:coeffects {}} nil)]
      (is (contains? (:coeffects result) :story/active-modes)
          "value lands at [:coeffects :story/active-modes] (canonical slot)")
      (is (vector? (get-in result [:coeffects :story/active-modes]))
          "the snapshot is a vector (default empty)")
      (is (nil? (get result :story/active-modes))
          "the misshapen-cofx witness — value must NOT land at top of ctx"))))

(deftest story-active-args-cofx-shape
  (testing ":story/active-args cofx body assoc's into [:coeffects :story/active-args]"
    (let [meta    (rf/handler-meta :cofx :story/active-args)
          handler (:handler-fn meta)
          result  (handler {:coeffects {}} nil)]
      (is (contains? (:coeffects result) :story/active-args)
          "value lands at [:coeffects :story/active-args] (canonical slot)")
      (is (nil? (get result :story/active-args))
          "the misshapen-cofx witness — value must NOT land at top of ctx"))))
