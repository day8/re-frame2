(ns re-frame.story-multi-substrate-cljs-test
  "CLJS smoke tests for Stage 6 (rf2-zhwd) — multi-substrate
  side-by-side renderer. The JVM side has no DOM so the visual /
  React paths are CLJS-only."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.ui.multi-substrate :as multi]))

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  ;; Wipe the substrate registry so each test starts clean.
  (reset! multi/substrate->render-fn {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- registry surface ---------------------------------------------------

(deftest reagent-default-registered
  (testing "install-canonical-vocabulary! registers :reagent substrate"
    (is (contains? (multi/registered-substrates) :reagent))))

(deftest register-and-unregister
  (testing "register-substrate! + unregister-substrate! work"
    (multi/register-substrate! :uix
                               (fn [_ _ _] [:div "uix-stub"]))
    (is (contains? (multi/registered-substrates) :uix))
    (multi/unregister-substrate! :uix)
    (is (not (contains? (multi/registered-substrates) :uix)))))

(deftest public-register-substrate-on-story
  (testing "story/register-substrate! is the public form"
    (story/register-substrate! :helix (fn [_ _ _] [:div "helix-stub"]))
    (is (contains? (story/registered-substrates) :helix))))

;; ---- substrate-set resolution -------------------------------------------

(deftest substrate-set-from-variant
  (testing "resolve-substrate-set prefers variant :substrates when present"
    (is (= #{:reagent :uix}
           (multi/resolve-substrate-set
             {:substrates #{:reagent :uix}}
             {}
             :reagent)))))

(deftest substrate-set-from-story
  (testing "falls back to story body's :substrates"
    (is (= #{:reagent :helix}
           (multi/resolve-substrate-set
             {}
             {:substrates #{:reagent :helix}}
             :reagent)))))

(deftest substrate-set-defaults-host
  (testing "defaults to {host} when neither body nor story declares :substrates"
    (is (= #{:reagent}
           (multi/resolve-substrate-set {} {} :reagent)))))

;; ---- stage marker --------------------------------------------------------

(deftest stage-sentinel
  (testing "Stage 6 advertises :sota-features"
    (is (= :sota-features story/stage))))
