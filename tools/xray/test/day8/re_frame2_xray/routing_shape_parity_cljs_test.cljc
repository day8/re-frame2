(ns day8.re-frame2-xray.routing-shape-parity-cljs-test
  "Shape parity for the ROUTING fixture family (rf2-y8doi.28).

  The Routing lens and the Static Routes preview read the route slice the
  router writes at `[:rf.runtime/routing :current]`, and their suites feed
  it through `:rf.xray/set-current-route-slice-override-for-test`. The slice
  those suites fed used to be typed by hand with `:path` (and once `:id`) —
  keys the router never writes — so the panels' `(when path …)` branch
  passed in tests and was dead in production (rf2-y8doi.22).

  The REAL side here is one real `:rf.route/navigate`, taken once; the
  FIXTURE side is the slice source the routing helper suite uses
  (`routing-helpers-cljs-test/navigated-slice`), never retyped. Their key
  sets must be EQUAL.

  `.cljc` on purpose: the router runs under the plain-atom adapter on both
  hosts, so this pins the node lane and the JVM gate alike."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test    :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.routing]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.routing-helpers-cljs-test :as routing-suite]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- key-set [m] (set (keys m)))

(defn- current-slice []
  (get-in (rf.frame/frame-runtime-db-value :rf/default) [:rf.runtime/routing :current]))

(deftest routing-suite-slice-has-the-routers-shape
  (testing "the route slice the routing suites inject carries exactly the keys
            one real navigation writes"
    (rf/reg-route ::probe {} "/routing-shape-parity/:id")
    (rf/dispatch-sync [:rf.route/navigate {:to ::probe :params {:id "1"}}])
    (let [real    (current-slice)
          fixture (#'routing-suite/navigated-slice)]
      (is (= ::probe (:route-id real))
          "PRECONDITION: the real navigation landed")
      (is (= (key-set real) (key-set fixture)))
      (is (not= (key-set real) #{:route-id :params :path})
          "control: the hand-typed slice rf2-y8doi.22 replaced fails this parity"))))
