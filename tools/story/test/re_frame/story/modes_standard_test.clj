(ns re-frame.story.modes-standard-test
  "Tests for `re-frame.story.modes.standard` — the canonical
  viewport + background `reg-mode` bundle."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.modes.standard :as rf.story.modes.standard]
            [re-frame.story.registrar :as rf.story.registrar]))

(defn reset-all! [test-fn]
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (test-fn))

(use-fixtures :each reset-all!)

(deftest register-all-installs-both-axes
  ;; The mode ids are public (spec/010 documents them); each axis carries its
  ;; documented arg slot.
  (is (= #{:Mode.viewport/mobile :Mode.viewport/tablet
           :Mode.viewport/desktop :Mode.viewport/ultra-wide
           :Mode.background/light :Mode.background/dark
           :Mode.background/transparent}
         (rf.story.modes.standard/register-all!)))
  (let [registered (rf.story.registrar/registrations :mode)]
    (doseq [id (keys rf.story.modes.standard/viewports)
            :let [body (registered id)]]
      (is (= :viewport (:axis body)) (str id))
      (is (keyword? (:viewport (:args body))) (str id)))
    (doseq [id (keys rf.story.modes.standard/backgrounds)
            :let [body (registered id)]]
      (is (= :background (:axis body)) (str id))
      (is (string? (:background (:args body))) (str id)))))
