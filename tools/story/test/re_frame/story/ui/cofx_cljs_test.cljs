(ns re-frame.story.ui.cofx-cljs-test
  "Regression guard: the `:story/active-modes` and `:story/active-args`
  cofx suppliers are value-returning nullary fns (EP-0017 `reg-cofx`). The
  runtime delivers a supplier's return value flat under the declared
  cofx-id, so a supplier of the wrong shape would leave the consuming
  handler binding nil silently."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.story.ui.cofx :as rf.story.ui.cofx]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf.story.ui.cofx/install-canonical-cofx!))}))

(deftest story-cofx-suppliers-return-their-values
  (doseq [[cofx-id shape?] [[:story/active-modes vector?]
                            [:story/active-args  map?]]]
    (let [supplier (:handler-fn (rf/handler-meta {:source :store :kind :cofx :id cofx-id}))]
      (is (shape? (supplier))
          (str cofx-id " — the nullary supplier returns its value directly")))))
