(ns day8.re-frame2-xray.static.interceptors.panel-cljs-test
  "CLJS wiring + view tests for the Static Interceptors sub-tab."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.interceptors.panel :as panel]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `reset-all!` folds the trace-collector ring reset in, so no direct
  ;; trace reset is needed. Default `:all` tier + plain-atom adapter.
  (xray-test-support/make-xray-runtime-fixture))

(defn- setup-xray! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

(defn- panel-tree
  "The hiccup the view rows below walk, driven through the pure projection.

  `panel/Panel` is an `rf.fresco/defview` boundary, a real React function
  component whose body may only run inside a React render window, so
  `(panel/Panel)` is not a callable that answers hiccup. This helper
  REPRODUCES THE BOUNDARY'S READ EXACTLY — the one
  `:rf.xray.static.interceptors/tab-data` query the boundary issues — and
  hands the value to `panel/panel-tree`, so every row below asserts on the
  hiccup the boundary renders.

  The dispatcher is nil: no row here types into the search box, and the
  search box only calls it from `:on-change`."
  []
  (panel/panel-tree @(rf/subscribe [:rf.xray.static.interceptors/tab-data])
                    nil))

;; ---- fixture data -------------------------------------------------------

;; EP-0018: every event registers under the ONE form — the framework
;; handler-wrapping interceptor is the single `:rf/event-handler` (there are
;; no per-kind handler ids and no `:event/kind` sub-tag). The fixture models
;; that registrar shape.
(def sample-events-with-chains
  {:counter/inc
   {:interceptors [{:id :my/logging :before identity}
                   {:id :rf/event-handler :rf/default? true :before identity}]}

   :user/save
   {:interceptors [{:id :my/logging :before identity}
                   {:id :rf/path    :before identity}
                   {:id :rf/event-handler :rf/default? true :before identity}]}

   :anon/no-chain
   {:interceptors []}})

;; EP-0022 — a chain may carry REFERENCES (bare keyword /
;; `[id arg]`) into the `:interceptor` registrar alongside inline values.
;; The catalogue must surface refs by their authored form + enrich them
;; from the registered descriptor.
(def sample-events-with-refs
  {:cart/add
   {:interceptors [:my/logging                       ; bare-keyword ref
                   [:rf.interceptor/path [:cart]]     ; parameterized [id arg] ref
                   {:id :rf/event-handler :rf/default? true :before identity}]}

   :cart/clear
   {:interceptors [:my/logging                       ; same ref → collapses
                   {:id :rf/event-handler :rf/default? true :before identity}]}})

;; A stub resolver standing in for `(rf/handler-meta {:source :store :kind :interceptor :id id})` so the
;; pure helper test never needs a live registrar.
(defn- stub-resolve-ref [icpt-id]
  (get {:my/logging          {:rf/interceptor-descriptor {:before identity}
                              :doc "logs every dispatch"}
        :rf.interceptor/path {:rf/interceptor-descriptor {:factory identity}
                              :doc "framework path interceptor"}}
       icpt-id))

;; -------------------------------------------------------------------------
;; (1) pure helpers
;; -------------------------------------------------------------------------

(deftest filter-rows-substring
  (is (= [:my/logging]
         (mapv :id (panel/filter-rows
                     (panel/collect-interceptors sample-events-with-chains)
                     "logging")))))

;; -------------------------------------------------------------------------
;; (1b) EP-0022 ref-aware collection
;; -------------------------------------------------------------------------

(deftest collect-interceptors-surfaces-refs-by-authored-form
  (testing "one row per interceptor id, sorted, counting the chains it sits on.
            A bare-keyword or `[id arg]` chain entry is a REFERENCE, surfaced
            by its authored form and enriched from its registered descriptor
            (hooks, factory, doc); the framework's inline wrapper is not a
            reference and keeps its own hooks and `:default?`"
    (is (= [{:id          :my/logging
             :ref?        true
             :authored    :my/logging
             :arg         nil
             :factory?    false
             :before?     true
             :after?      false
             :default?    false
             :chain-count 2
             :doc         "logs every dispatch"}
            {:id          :rf.interceptor/path
             :ref?        true
             :authored    [:rf.interceptor/path [:cart]]
             :arg         [:cart]
             :factory?    true
             :before?     false
             :after?      false
             :default?    false
             :chain-count 1
             :doc         "framework path interceptor"}
            {:id          :rf/event-handler
             :ref?        false
             :authored    nil
             :arg         nil
             :factory?    false
             :before?     true
             :after?      false
             :default?    true
             :chain-count 2
             :doc         nil}]
           (panel/collect-interceptors sample-events-with-refs stub-resolve-ref)))))

(deftest collect-interceptors-arity-1-uses-default-resolver
  ;; The 1-arity (production) form resolves through the live registrar, and
  ;; `default-resolve-ref` is fail-soft: an unregistered ref still lands as a
  ;; row, reporting no hooks.
  (is (= [[:unregistered/icpt true false]]
         (mapv (juxt :id :ref? :before?)
               (panel/collect-interceptors {:x {:interceptors [:unregistered/icpt]}})))))

;; -------------------------------------------------------------------------
;; (2) view rendering
;; -------------------------------------------------------------------------

(deftest panel-is-cold-empty-for-a-host-with-no-events-rf2-y8doi-22
  (testing "Xray's OWN event registrations are not host chains.
            They sit in the same process source store as the host's, each
            carrying the framework-appended `:rf/event-handler`, so counting
            them would make a host with ZERO events read `:rf/event-handler
            default x<~160>` and never reach the cold-empty state.

            The registrations fed in are the store's own rows for every
            event whose recorded source file lies in Xray's `src` tree —
            taken from the producer, not typed by hand, and not selected by
            the id predicate the panel uses."
    (setup-xray!)
    (let [xray-own (into {}
                         (filter (fn [[_id meta]]
                                   (some-> (:file meta)
                                           (str/replace "\\" "/")
                                           (str/includes? "tools/xray/src/"))))
                         (rf/registrations {:source :store :kind :event}))]
      (is (seq (panel/collect-interceptors xray-own))
          "NON-VACUITY: Xray's own registrations carry interceptor chains, so
           an unfiltered catalogue has rows to show")
      (rf/with-frame :rf/xray
        (rf/dispatch-sync
          [:rf.xray.static.interceptors/set-registry-override-for-test xray-own])
        (is (some? (rf.test-helpers/find-by-testid
                     (panel-tree) "rf-xray-static-interceptors-empty"))
            "the cold-empty state renders")
        (testing "and a host's own events beside them still count, exactly as alone"
          (rf/dispatch-sync
            [:rf.xray.static.interceptors/set-registry-override-for-test
             (merge xray-own sample-events-with-chains)])
          (let [data @(rf/subscribe [:rf.xray.static.interceptors/tab-data])]
            (is (= 3 (:total data))
                "the host's three interceptors, and none of Xray's")
            (is (= 2 (:chain-count (some #(when (= :rf/event-handler (:id %)) %)
                                         (:interceptors data))))
                "`:rf/event-handler` counts the host's two chains, not Xray's")))))))

(deftest panel-renders-filtered-state-on-no-match
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.interceptors/set-registry-override-for-test
       sample-events-with-chains])
    (rf/dispatch-sync [:rf.xray.static.interceptors/set-query "no-such-id"])
    (let [tree (panel-tree)]
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-interceptors-empty-filtered"))))))
