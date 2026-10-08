(ns day8.re-frame2-xray.static.shell-cljs-test
  "Node-lane tests for Xray's Static surface: the mode codec, the Static
  chrome's hiccup (walked through `test-helpers.static-shell-tree`) and
  the Static tab slot. The localStorage rows live in
  `static.shell-dom-cljs-test`, because only a `-dom-cljs-test`
  namespace runs in the browser lane."
  (:require [cljs.test :refer-macros [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
            [day8.re-frame2-xray.static.shell :as static-shell]
            [day8.re-frame2-xray.test-helpers.static-shell-tree
             :as static-shell-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (config/reset-suppressed-count!)
                   (static-persistence/clear!))}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

;; ---- mode codec ---------------------------------------------------------

(deftest persistence-mode-codec-normalises-and-round-trips
  (testing "an unrecognised or absent mode, keyword or string, falls back
            to :dynamic"
    (are [raw mode] (= mode (static-persistence/normalise-mode raw))
      :static   :static
      :nonsense :dynamic
      "static"  :static
      "junk"    :dynamic
      nil       :dynamic))
  (is (= :static (static-persistence/<-raw (static-persistence/->raw :static)))
      "->raw / <-raw round-trip"))

;; ---- Static chrome ------------------------------------------------------

(deftest static-ribbon-mounts-mode-pill-frame-picker-and-right-icons
  (xray-setup!)
  (rf/with-frame :rf/xray
    (let [tree (static-shell-tree/surface-tree)]
      (is (= [true true true true]
             (map #(some? (rf.test-helpers/find-by-testid tree %))
                  ["rf-xray-mode-pill" "rf-xray-ribbon-frame"
                   "rf-xray-static-icon-settings" "rf-xray-static-icon-close"]))))))

(deftest static-ribbon-has-no-left-edge-stripe
  (testing "the Static ribbon, like the Dynamic one
            (`chrome-ribbon-has-no-left-edge-stripe`), paints no left-edge
            accent stripe"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [ribbon (rf.test-helpers/find-by-testid (static-shell-tree/surface-tree)
                                                   "rf-xray-static-ribbon")
            style  (:style (second ribbon))]
        (is (some? (:border-bottom style))
            "CONTROL: the ribbon's style map is read, so a nil :border-left
             is an absence")
        (is (nil? (:border-left style)))))))

(deftest static-tab-bar-uses-tablist-aria
  (testing "the tab bar is a role=tablist and each tab a role=tab whose
            aria-selected tracks the active tab"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (let [tree  (static-shell-tree/surface-tree)
            attrs #(second (rf.test-helpers/find-by-testid tree %))]
        (is (= "tablist" (:role (attrs "rf-xray-static-tab-bar"))))
        (is (= [["tab" "true"] ["tab" "false"]]
               (map (comp (juxt :role :aria-selected) attrs)
                    ["rf-xray-static-tab-machines" "rf-xray-static-tab-routes"]))
            "the active :machines tab, and an inactive one")))))

(deftest mode-dropdown-reflects-active-mode
  (testing "the mode <select> offers both modes, and its :value and
            data-active-mode track the live mode"
    (xray-setup!)
    (let [pill #(rf/with-frame :rf/xray (static-shell-tree/mode-pill-tree rf/dispatch))
          live (comp (juxt :value :data-active-mode) second)
          [_ _ options :as tree] (pill)]
      (is (= ["rf-xray-mode-pill-dynamic" "rf-xray-mode-pill-static"]
             (map (comp :data-testid second) options)))
      (is (= ["dynamic" "dynamic"] (live tree)))
      (frame-dispatch [:rf.xray/set-mode :static])
      (is (= ["static" "static"] (live (pill)))))))

;; ---- Static tab slot + inventory ----------------------------------------

(deftest static-select-tab-flips-the-slot
  (xray-setup!)
  (is (= :machines (frame-sub [:rf.xray.static/selected-tab])) "default is :machines")
  (frame-dispatch [:rf.xray/select-tab :machines])
  (frame-dispatch [:rf.xray.static/select-tab :flows])
  (is (= [:flows :machines]
         [(frame-sub [:rf.xray.static/selected-tab]) (frame-sub [:rf.xray/selected-tab])])
      "the Static slot moves and the Dynamic one does not")
  (frame-dispatch [:rf.xray.static/select-tab :not-a-tab])
  (is (= :flows (frame-sub [:rf.xray.static/selected-tab]))
      "an id outside the inventory is ignored"))

(deftest static-tab-inventory-shape
  (is (= [:machines :routes :schemas :flows :interceptors]
         (mapv :id (static-shell/tabs)))
      "the five Static tabs, in canonical order"))
