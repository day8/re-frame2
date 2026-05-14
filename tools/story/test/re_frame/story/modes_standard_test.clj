(ns re-frame.story.modes-standard-test
  "Tests for `re-frame.story.modes.standard` — the canonical
  viewport + background `reg-mode` bundle (rf2-wk41).

  Pure-data registry — JVM-only is sufficient; the registrar runs on
  both runtimes and there is no view layer to exercise."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            [re-frame.story.modes.standard :as standard]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.schemas :as schemas]
            [malli.core :as m]))

(defn reset-all! [test-fn]
  (story/clear-all!)
  (story/install-canonical-vocabulary!)
  (test-fn))

(use-fixtures :each reset-all!)

;; ---- canonical-data shape -----------------------------------------------

(deftest viewports-canonical-set
  (testing "viewports map carries the four canonical preset ids"
    (is (= #{:Mode.viewport/mobile
             :Mode.viewport/tablet
             :Mode.viewport/desktop
             :Mode.viewport/ultra-wide}
           (set (keys standard/viewports)))))
  (testing "every viewport body sits on `:axis :viewport`"
    (doseq [[id body] standard/viewports]
      (is (= :viewport (:axis body))
          (str id " missing :axis :viewport"))))
  (testing "every viewport body contributes a `:viewport` arg"
    (doseq [[_ body] standard/viewports]
      (is (keyword? (:viewport (:args body)))))))

(deftest backgrounds-canonical-set
  (testing "backgrounds map carries the three canonical preset ids"
    (is (= #{:Mode.background/light
             :Mode.background/dark
             :Mode.background/transparent}
           (set (keys standard/backgrounds)))))
  (testing "every background body sits on `:axis :background`"
    (doseq [[id body] standard/backgrounds]
      (is (= :background (:axis body))
          (str id " missing :axis :background"))))
  (testing "every background body contributes a string `:background` arg"
    (doseq [[_ body] standard/backgrounds]
      (is (string? (:background (:args body)))))))

;; ---- schema conformance --------------------------------------------------

(deftest every-canonical-body-validates-against-mode-schema
  (testing "viewport bodies pass `:rf/mode` schema"
    (doseq [[id body] standard/viewports]
      (is (m/validate schemas/Mode body)
          (str id " failed Mode schema"))))
  (testing "background bodies pass `:rf/mode` schema"
    (doseq [[id body] standard/backgrounds]
      (is (m/validate schemas/Mode body)
          (str id " failed Mode schema")))))

;; ---- installer side-effects ---------------------------------------------

(deftest register-viewports-installs-all
  (testing "register-viewports! adds every canonical viewport to the registry"
    (let [ids (standard/register-viewports!)]
      (is (= (set (keys standard/viewports)) ids))
      (doseq [id (keys standard/viewports)]
        (is (registrar/registered? :mode id))))))

(deftest register-backgrounds-installs-all
  (testing "register-backgrounds! adds every canonical background to the registry"
    (let [ids (standard/register-backgrounds!)]
      (is (= (set (keys standard/backgrounds)) ids))
      (doseq [id (keys standard/backgrounds)]
        (is (registrar/registered? :mode id))))))

(deftest register-all-installs-both-axes
  (testing "register-all! installs every viewport + background"
    (let [ids (standard/register-all!)]
      (is (= (into (set (keys standard/viewports))
                   (set (keys standard/backgrounds)))
             ids))
      (doseq [id ids]
        (is (registrar/registered? :mode id))))))

(deftest installers-are-idempotent
  (testing "calling register-all! twice does not throw and leaves registry consistent"
    (standard/register-all!)
    (standard/register-all!)
    (let [registered (set (keys (registrar/handlers :mode)))]
      (is (every? registered (keys standard/viewports)))
      (is (every? registered (keys standard/backgrounds))))))

;; ---- toolbar interaction smoke ------------------------------------------

(deftest registered-modes-appear-on-mode-registry
  (testing "after register-all! the live registry exposes the canonical ids"
    (standard/register-all!)
    (let [snapshot (registrar/handlers :mode)]
      (is (= (:axis (get snapshot :Mode.viewport/mobile)) :viewport))
      (is (= (:axis (get snapshot :Mode.background/dark)) :background))
      (is (= (:viewport (:args (get snapshot :Mode.viewport/tablet))) :tablet))
      (is (= (:background (:args (get snapshot :Mode.background/transparent))) "transparent")))))
