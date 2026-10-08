(ns day8.re-frame2-xray.settings.effects-dom-cljs-test
  "Browser-lane half of the Settings side-effect tests: every row reads a
  real DOM mutation on the shell root or `<html>`. The host-free half is
  `day8.re-frame2-xray.settings.effects-cljs-test`.

  The node lane loads this namespace too (its `cljs-test$` regexp matches
  `-dom-cljs-test`), so every row is guarded by [[browser?]] and asserts a
  skip marker on node rather than holding zero assertions."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.effects :as effects]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- host predicate (house shape — see
;; `day8.re-frame2-xray.palette.empty-row-frame-context-dom-cljs-test`)

(defn- browser?
  "True only under the real-DOM `:browser-test` build."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- stub shell root and readers -----------------------------------------

(defn- ensure-stub-shell-root! []
  (when (browser?)
    (when-not (.getElementById js/document "rf-xray-root")
      (let [el (.createElement js/document "div")]
        (set! (.-id el) "rf-xray-root")
        (when (.-body js/document)
          (.appendChild (.-body js/document) el))))))

(defn- remove-stub-shell-root! []
  (when (browser?)
    (when-let [el (.getElementById js/document "rf-xray-root")]
      (when (.-parentNode el)
        (.removeChild (.-parentNode el) el)))))

(defn- shell-root []
  (when (browser?)
    (.getElementById js/document "rf-xray-root")))

(defn- html-root []
  (when (browser?)
    (.-documentElement js/document)))

(defn- css-var [el prop]
  (some-> el .-style (.getPropertyValue prop)))

(defn- has-class? [el c]
  (some-> el .-classList (.contains c)))

(defn- force-colors [el]
  (some-> el (.getAttribute effects/force-colors-attribute)))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:tier       :runtime
     :post-reset (fn []
                   (effects/detach-auto-open-watcher!)
                   (remove-stub-shell-root!))}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(defn- dispatch-update! [section key value]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-update section key value])))

;; ---- text-size and theme ----------------------------------------------------

(deftest update-event-applies-text-size-effect
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (do
      (setup!)
      (ensure-stub-shell-root!)
      (dispatch-update! :general :text-size 15)
      (is (= ["15px" "15px"]
             (mapv #(css-var % "--rf-xray-text-size") [(shell-root) (html-root)]))
          "the shell root and <html> both carry the value"))))

(deftest update-event-applies-theme-effect
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (do
      (setup!)
      (ensure-stub-shell-root!)
      (let [dark-light #(mapv (partial has-class? (shell-root))
                              ["rf-xray-theme-dark" "rf-xray-theme-light"])]
        (dispatch-update! :theme nil :dark)
        (is (= [true false] (dark-light)))
        (dispatch-update! :theme nil :light)
        (is (= [false true] (dark-light))
            "the toggle is exclusive: the dark class is removed")))))

(deftest apply-all-restores-text-size-and-theme
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (do
      (ensure-stub-shell-root!)
      (config/update-setting! :general :text-size 12)
      (effects/apply-all!)
      (let [el (shell-root)]
        (is (= ["12px" true]
               [(css-var el "--rf-xray-text-size") (has-class? el "rf-xray-theme-light")]))))))

;; ---- panel width --------------------------------------------------------

(deftest apply-all-restores-panel-width
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (testing "the CSS var lands on `<html>`, so the layout host's flex-basis
              picks it up even before the shell mounts"
      (config/update-setting! :general :panel-width-px 700)
      (effects/apply-all!)
      (is (= "700px" (css-var (html-root) "--rf-xray-inline-width"))))))

;; ---- use-system-colors? -------------------------------------------------

(deftest update-event-applies-use-system-colors-effect
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (do
      (setup!)
      (ensure-stub-shell-root!)
      (let [stamps #(mapv force-colors [(shell-root) (html-root)])]
        (dispatch-update! :general :use-system-colors? true)
        (is (= ["active" "active"] (stamps))
            "the shell root and <html> are stamped")
        (dispatch-update! :general :use-system-colors? false)
        (is (= [nil nil] (stamps))
            "and both are cleared")))))

(deftest apply-all-restores-use-system-colors
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (testing "at boot, before the shell mounts, `<html>` still carries the
              persisted opt-in, so the cascade reaches the shell when it does"
      (is (nil? (shell-root)) "precondition: no shell root is mounted")
      (config/update-setting! :general :use-system-colors? true)
      (effects/apply-all!)
      (is (= "active" (force-colors (html-root))))
      (config/update-setting! :general :use-system-colors? false)
      (effects/apply-all!)
      (is (nil? (force-colors (html-root)))
          "and a persisted false clears it"))))

;; ---- density font-size --------------------------------------------------

(deftest update-event-applies-density-font-size-effect
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (do
      (setup!)
      (ensure-stub-shell-root!)
      (dispatch-update! :general :density :compact)
      (is (= "12px" (css-var (shell-root) "--rf-xray-font-size")))
      (dispatch-update! :general :density :cosy)
      (is (= "13px" (css-var (shell-root) "--rf-xray-font-size"))
          "a flip rewrites the inline value"))))

(deftest apply-all-restores-density-font-size
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (do
      (ensure-stub-shell-root!)
      (config/update-setting! :general :density :compact)
      (effects/apply-all!)
      (is (= ["12px" "12px"]
             (mapv #(css-var % "--rf-xray-font-size") [(shell-root) (html-root)]))
          "the shell root and <html> carry the persisted compact value"))))
