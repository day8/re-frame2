(ns re-frame.ui.resource-lease-hook-matrix-elision-prod-test
  "rf2-vxgfnd.106 — actual :advanced/goog.DEBUG=false mounted proof of the
  frozen production hook matrix: neither 0/0, sub 1/0, lease 0/1, both 1/1
  for useSyncExternalStore/resource-passive respectively."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            ["react" :as React]
            ["react-dom" :as ReactDOM]
            [re-frame.core :as rf]
            [re-frame.resources]
            [re-frame.resources.test-support]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.reactive :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true
    :init-fn reactive/reset-scheduler!}))

(def ^:private this-ns
  "re-frame.ui.resource-lease-hook-matrix-elision-prod-test")

(defview matrix-neither []
  [:output {:data-matrix "neither"} "plain"])

(defview matrix-sub []
  [:output {:data-matrix "sub"} (str (ui/sub [::value]))])

(defview matrix-lease []
  (ui/lease {:resource ::feed :params {}})
  [:output {:data-matrix "lease"} "lease"])

(defview matrix-both []
  (ui/lease {:resource ::feed :params {}})
  [:output {:data-matrix "both"} (str (ui/sub [::value]))])

(defn- install-matrix-registrations!
  []
  (rf/reg-sub ::value (fn [db _] (:value db)))
  (rf/reg-resource ::feed
                   {:scope :rf.scope/global
                    :params-schema [:map]}
                   (fn [_ _]
                     {:request {:method :get :url "/ui-lease-matrix"}}))
  (rf/reg-event :rf.resource/ensure (fn [{:keys [db]} _] {:db db}))
  (rf/reg-event :rf.resource/release-owner (fn [{:keys [db]} _] {:db db})))

(defn- matrix-frame []
  (rf/make-frame
   {:id :ui.lease/prod-matrix
    :images [(rf/image {:id ::base
                        :select-ns {:include ["**"]
                                    :exclude [this-ns]}})
             (rf/image {:id ::overrides
                        :select-ns {:include [this-ns]}})]
    :initial-events [[:rf/set-db {:value 7}]]}))

(defn- measure-hooks!
  [f component label]
  (let [container (js/document.createElement "div")
        root      (volatile! nil)
        counts    (atom {:uses 0 :passive 0})
        original-uses (.-useSyncExternalStore React)
        original-effect (.-useEffect React)]
    (.appendChild js/document.body container)
    (vreset! root (ui/create-root container {:root-id :ui.lease/matrix}))
    (set! (.-useSyncExternalStore React)
          (fn [& args]
            (swap! counts update :uses inc)
            (.apply original-uses nil (to-array args))))
    (set! (.-useEffect React)
          (fn [& args]
            (swap! counts update :passive inc)
            (.apply original-effect nil (to-array args))))
    (try
      (ReactDOM/flushSync
       #(ui/render! @root
                    [ui/frame-provider {:frame f}
                     (ui/raw (React/createElement component nil))]))
      @counts
      (finally
        (set! (.-useSyncExternalStore React) original-uses)
        (set! (.-useEffect React) original-effect)
        (when @root
          (ReactDOM/flushSync #(ui/unmount! @root)))
        (.remove container)))))

(def ^:private expected-matrix
  {:neither {:uses 0 :passive 0}
   :sub     {:uses 1 :passive 0}
   :lease   {:uses 0 :passive 1}
   :both    {:uses 1 :passive 1}})

(defn- valid-matrix? [m] (= expected-matrix m))

(deftest advanced-mounted-capability-matrix-has-exact-hook-cardinality
  (install-matrix-registrations!)
  (let [f (matrix-frame)
        observed {:neither (measure-hooks! f matrix-neither :neither)
                  :sub     (measure-hooks! f matrix-sub :sub)
                  :lease   (measure-hooks! f matrix-lease :lease)
                  :both    (measure-hooks! f matrix-both :both)}]
    (js/console.log
     (str "RF2_UI_RESOURCE_LEASE_HOOK_MATRIX observed=" (pr-str observed)))
    (is (valid-matrix? observed))
    ;; Mutation teeth: each historically-dangerous shape change makes the
    ;; exact production gate red, including charging lease-only for uSES and
    ;; leaking the resource passive into sub-only/neither.
    (doseq [mutated [(assoc-in observed [:lease :uses] 1)
                     (assoc-in observed [:sub :passive] 1)
                     (assoc-in observed [:neither :passive] 1)
                     (assoc-in observed [:both :uses] 0)]]
      (is (not (valid-matrix? mutated))))
    (rf/destroy-frame! f)))
