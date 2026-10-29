(ns re-frame.handler-frame-in-render-dom-cljs-test
  "An event handler resolves its envelope's frame even when it runs inside a
  Reagent render. While a component renders, the frame boundary above it in
  the tree answers an ambient read; a handler the render runs synchronously
  is not part of that render's frame, so inside the handler the frame the
  router bound for it answers instead (Spec 006 §One frame-resolution rule).

  The reachable case is `make-frame` with `:initial-events`, called from a
  view: each setup step runs through `dispatch-sync` with `:frame` forced to
  the new frame, while the parent's component is still rendering. The
  reverse nesting keeps the tree rule: a render a handler runs reads its own
  tree.

  The server-render cases need no DOM and run on the node lane too; the
  client mount runs in the browser lane."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            [reagent.dom.server :as rds]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.adapter.react-test-support :refer [browser?]]))

(def ^:private seen
  "What each `::init` step resolved: `{:frame-id … :captured …}`."
  (atom []))

(defn- read-id
  "The ambient frame id here, or `[:error <:rf.error/id>]`."
  []
  (try (rf/current-frame-id)
       (catch :default e [:error (:rf.error/id (ex-data e))])))

(rf/reg-event ::init
  (fn [_ _]
    (swap! seen conj {:frame-id (read-id)
                      :captured (try (:frame (rf/capture-frame))
                                     (catch :default e
                                       [:error (:rf.error/id (ex-data e))]))})
    ;; An ambient dispatch from the setup step: it must queue onto the frame
    ;; being set up, which drains it before `make-frame` returns.
    (rf/dispatch [::mark])
    {}))

(rf/reg-event ::mark
  (fn [{:keys [db]} _]
    {:db (assoc db :marked? true)}))

(rf/reg-event ::seed (fn [_ [_ label]] {:db {:label label}}))
(rf/reg-sub ::label (fn [db _] (:label db)))

(def ^:private rendered
  "The markup `::render-a-tree` rendered."
  (atom nil))

(defn- tree-probe []
  [:span (pr-str [(read-id) @(rf/subscribe [::label])])])

(rf/reg-event ::render-a-tree
  (fn [_ _]
    (reset! rendered
            (rds/render-to-string
              [rf/frame-provider {:frame :audit/tree} [tree-probe]]))
    {}))

;; Every registration sits above the fixture, which captures its registrar
;; baseline when it is built and restores that baseline before each test.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true}))

(defn- creator
  "A plain view that creates `child-id` with one setup step on its first
  render."
  [child-id]
  (let [created? (atom false)]
    (fn creating-view []
      (when-not @created?
        (reset! created? true)
        (rf/make-frame {:id child-id :initial-events [[::init]]}))
      [:span "created child"])))

(defn- assert-child-resolved [child-id parent-id]
  (is (= [{:frame-id child-id :captured child-id}] @seen)
      (str "the setup step resolved its own frame by both reads; got " (pr-str @seen)))
  (is (true? (:marked? (rf/app-db-value child-id)))
      "the step's ambient dispatch reached the frame being set up")
  (is (nil? (:marked? (rf/app-db-value parent-id)))
      "and not the frame whose view was rendering"))

(deftest an-initial-event-run-from-a-server-render-resolves-its-own-frame
  (reset! seen [])
  (rf/make-frame {:id :audit/parent})
  (let [html (rds/render-to-string
               [rf/frame-provider {:frame :audit/parent} [(creator :audit/child)]])]
    (is (re-find #"created child" html) "the render completed")
    (assert-child-resolved :audit/child :audit/parent)))

(deftest an-initial-event-run-from-a-client-render-resolves-its-own-frame
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
      (reset! seen [])
      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
      (try
        (rf/make-frame {:id :audit/parent})
        (let [root (rdc/create-root (.createElement js/document "div"))]
          (try
            (rf.test-support/flush-views!
              #(rdc/render root [rf/frame-provider {:frame :audit/parent}
                                 [(creator :audit/child)]]))
            (assert-child-resolved :audit/child :audit/parent)
            (finally
              (rf.test-support/flush-views! #(rdc/unmount root)))))
        (finally
          (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev))))))

(deftest control-an-initial-event-outside-any-render-resolves-its-own-frame
  (reset! seen [])
  (rf/make-frame {:id :audit/control :initial-events [[::init]]})
  (is (= [{:frame-id :audit/control :captured :audit/control}] @seen)
      "outside a render the setup step resolves its own frame")
  (is (true? (:marked? (rf/app-db-value :audit/control)))))

(deftest a-render-run-by-a-handler-still-reads-its-own-tree
  (testing "the reverse nesting: the handler encloses the render"
    (doseq [id [:audit/host :audit/tree]]
      (rf/make-frame {:id id})
      (rf/dispatch-sync [::seed (name id)] {:frame id}))
    (reset! rendered nil)
    (rf/dispatch-sync [::render-a-tree] {:frame :audit/host})
    (is (re-find #"audit/tree" (str @rendered))
        (str "the view rendered inside the handler read its provider; got " @rendered))
    (is (re-find #"&quot;tree&quot;" (str @rendered))
        "and subscribed against it")))
