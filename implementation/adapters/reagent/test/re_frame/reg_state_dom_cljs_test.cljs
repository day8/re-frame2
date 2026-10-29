(ns re-frame.reg-state-dom-cljs-test
  "`rf/reg-state` from a Reagent view: one widget written once and mounted
  twice, each instance reading its own value through an ordinary
  subscription, with no Fresco anywhere (Spec 002 §Per-instance view state).

  The server-render case needs no DOM and runs on the node lane too; the
  mounted case runs in the browser lane."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            [reagent.dom.server :as rds]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.adapter.react-test-support :refer [browser?]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil}))

(defn- disclosure
  "A disclosure panel holding its own open flag, keyed by the `ikey` it is
  handed — the widget knows nothing else about where it sits."
  [{:keys [ikey title]}]
  (let [open? @(rf/subscribe [::open? ikey])]
    [:section {:class (if open? "open" "shut")}
     title
     (when open? [:p.body "body of " title])]))

(defn- page []
  [:div
   [disclosure {:ikey [:panel :billing]  :title "Billing"}]
   [disclosure {:ikey [:panel :shipping] :title "Shipping"}]])

(defn- frame! [id]
  (rf/reg-state ::open? {:default false})
  (rf/make-frame {:id id})
  id)

(defn- with-act-environment [f]
  (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (try (f)
         (finally (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev)))))

(defn- classes [node]
  (mapv #(.-className %) (array-seq (.querySelectorAll node "section"))))

(deftest a-server-render-reads-each-instance-on-its-own
  (let [f (frame! ::ssr)]
    (rf/dispatch-sync [::open? [:panel :billing] true] {:frame f})
    (let [html (rds/render-to-string [rf/frame-provider {:frame f} [page]])]
      (is (re-find #"class=\"open\">Billing" html) "the written instance reads its value")
      (is (re-find #"class=\"shut\">Shipping" html)
          "its sibling reads the default — one concern, two keys, two values")
      (is (= 1 (count (re-seq #"class=\"body\"" html)))
          "only the open instance renders its body"))))

(deftest a-mounted-view-follows-writes-and-clears
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (with-act-environment
      (fn []
        (let [f    (frame! ::mounted)
              node (.createElement js/document "div")
              root (rdc/create-root node)]
          (try
            (rf.test-support/flush-views!
              #(rdc/render root [rf/frame-provider {:frame f} [page]]))
            (is (= ["shut" "shut"] (classes node)) "both instances start at the default")
            (rf.test-support/flush-views!
              #(rf/dispatch-sync [::open? [:panel :shipping] true] {:frame f}))
            (is (= ["shut" "open"] (classes node))
                "the setter moved exactly the instance it named")
            (testing "the shared clear event puts the instance back to its default"
              (rf.test-support/flush-views!
                #(rf/dispatch-sync [:rf.state/clear ::open? [:panel :shipping]] {:frame f}))
              (is (= ["shut" "shut"] (classes node)))
              (is (= {:ui {}} (rf/app-db-value f)) "the entry is removed, not overwritten"))
            (finally
              (rf.test-support/flush-views! #(rdc/unmount root)))))))))
