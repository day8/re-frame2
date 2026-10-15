(ns day8.re-frame2-xray.panels-e2e.configure-multi-key-e2e-cljs-test
  "Configure! accepts whole maps and leaves omitted keys untouched."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.config :as cfg]))

(defn- snapshot []
  {:editor          (cfg/get-editor)
   :project-root    (cfg/get-project-root)
   :layout-host     (cfg/get-layout-host-selector)
   :auto-open?      (cfg/auto-open-enabled?)
   :egress-profile  (cfg/get-egress-profile)})

(defn- restore! [s]
  (cfg/configure! {:rf.xray/editor                (:editor s)
                   :rf.xray/project-root          (:project-root s)
                   :rf.xray/layout-host-selector  (:layout-host s)
                   :rf.xray/auto-open?            (:auto-open? s)
                   :rf.xray/egress-profile        (:egress-profile s)}))

(use-fixtures :each
  {:before (fn []
             (set! (.-__rf2_rviu8_pre__ js/globalThis) (snapshot)))
   :after  (fn []
             (restore! (.-__rf2_rviu8_pre__ js/globalThis)))})

(deftest configure-multi-key-round-trips
  (cfg/configure! {:rf.xray/editor :idea
                   :rf.xray/project-root "/tmp/probe-multi-key"
                   :rf.xray/layout-host-selector "#rf2-qd5r6-probe-host"
                   :rf.xray/auto-open? false
                   :rf.xray/egress-profile :rf.egress/local-raw})
  (is (= {:editor :idea :project-root "/tmp/probe-multi-key"
          :layout-host "#rf2-qd5r6-probe-host" :auto-open? false
          :egress-profile :rf.egress/local-raw}
         (snapshot))))

(deftest configure-partial-update-leaves-others-alone
  (cfg/configure! {:rf.xray/editor :idea
                   :rf.xray/project-root "/tmp/probe-multi-key"
                   :rf.xray/layout-host-selector "#rf2-qd5r6-probe-host"
                   :rf.xray/auto-open? false
                   :rf.xray/egress-profile :rf.egress/local-raw})
  (cfg/configure! {:rf.xray/editor :zed})
  (is (= {:editor :zed :project-root "/tmp/probe-multi-key"
          :layout-host "#rf2-qd5r6-probe-host" :auto-open? false
          :egress-profile :rf.egress/local-raw}
         (snapshot))))

(deftest configure-empty-map-is-noop
  (testing "configure! with empty map leaves every slot at its current value"
    (cfg/configure! {:rf.xray/editor                :cursor
                     :rf.xray/project-root          "/tmp/noop-baseline"
                     :rf.xray/auto-open?            true
                     :rf.xray/egress-profile        :rf.egress/local-redacted})
    (let [pre (snapshot)]
      (cfg/configure! {})
      (is (= pre (snapshot))
          "empty configure! mutated state — partial-update contract violated"))))
