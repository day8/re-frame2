(ns day8.re-frame2-xray.panels.derivation-graph-on-box-redaction-cljs-test
  "The Derivation Graph tab's ON-BOX render takes Xray's local-redacted
  default (`derivation-graph-helpers/tab-data`, tools/xray/spec/025
  §Privacy): a live node value the frame declared `:sensitive` previews as
  `:rf/redacted`, as it does on the Routing tab, while an undeclared sibling
  previews raw.

  The route row navigates for real and takes its node from
  `re-frame.routing.tooling/route-slice-algebra-view`, the producer the live
  graph composes, so the declaration under test is re-rooted exactly as the
  frame's policy re-roots it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing]
            [re-frame.routing.tooling :as rf.routing.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.derivation-graph-helpers :as h]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private secret "graph-on-box-secret")

(defn- route-node-summary
  "The `[<field> <summary>]` previews the panel would render for the route
  node of `tab-data`."
  [tab-data]
  (some (fn [[_family entries]]
          (some (fn [[id node]] (when (= :rf/route id) (:summaries node))) entries))
        (:by-family tab-data)))

(defn- live-route-graph [frame]
  {:mode  :live
   :frame frame
   :nodes {:rf/route (rf.routing.tooling/route-slice-algebra-view :rf/default)}
   :edges []})

(deftest live-route-params-preview-redacted-on-box
  (testing "a route param declared :sensitive previews :rf/redacted; the
            undeclared sibling previews raw"
    (rf/reg-route ::user {:sensitive [[:params :token]]} "/graph-on-box/user/:token/:tab")
    (rf/dispatch-sync [:rf.route/handle-url-change
                       (str "/graph-on-box/user/" secret "/posts")
                       {:rf.route/cause :link}]
                      {:frame :rf/default})
    (let [graph   (live-route-graph :rf/default)
          preview (get-in (route-node-summary (h/tab-data graph)) [:params :preview])]
      (is (str/includes? (pr-str (get-in graph [:nodes :rf/route :params])) secret)
          "PRECONDITION: the composed node carries the raw token")
      (is (not (str/includes? (str preview) secret))
          (str "the declared-sensitive token reached the on-box preview: " preview))
      (is (str/includes? (str preview) ":rf/redacted")
          (str "the token must preview as the redaction sentinel: " preview))
      (is (str/includes? (str preview) "posts")
          (str "the undeclared sibling must preview raw: " preview)))))

(defn- live-sub-node
  "A live sub node in the shape `re-frame.subs.tooling` composes it: its
  concrete query vector as `:id`, the sub's output as `:value`."
  [sub-id value]
  {:id          [sub-id]
   :kind        :derivation
   :rf/family   :subs
   :source-form {:kind :reg-sub :id sub-id}
   :value       value})

(defn- sub-preview
  "The `:value` preview the panel would render for sub node `node-id`."
  [tab-data node-id]
  (some (fn [[id node]] (when (= node-id id) (get-in node [:summaries :value :preview])))
        (get-in tab-data [:by-family :subs])))

(deftest live-sub-value-takes-its-own-sensitive-declaration-on-box
  (testing "a live sub whose registration declares `:sensitive [[:token]]`
            previews its token as :rf/redacted and its undeclared field raw;
            an undeclared sibling sub previews in full"
    (rf/reg-sub :graph-on-box/secret-sub {:sensitive [[:token]]} (fn [_ _] nil))
    (rf/reg-sub :graph-on-box/plain-sub (fn [_ _] nil))
    (let [value     {:token secret :public "visible"}
          secret-id [:sub [:graph-on-box/secret-sub]]
          plain-id  [:sub [:graph-on-box/plain-sub]]
          graph     {:mode  :live
                     :frame :rf/default
                     :nodes {secret-id (live-sub-node :graph-on-box/secret-sub value)
                             plain-id  (live-sub-node :graph-on-box/plain-sub value)}
                     :edges []}
          projected (h/project-live-values graph)
          tab-data  (h/tab-data graph)]
      (is (= {:token :rf/redacted :public "visible"}
             (get-in projected [:nodes secret-id :value]))
          "the declared path is redacted and the undeclared one kept")
      (is (= [:graph-on-box/secret-sub] (get-in projected [:nodes secret-id :id]))
          "the node's query-vector identity stays raw")
      (is (not (str/includes? (str (sub-preview tab-data secret-id)) secret))
          (str "the declared-sensitive token reached the on-box preview: "
               (sub-preview tab-data secret-id)))
      (is (= value (get-in projected [:nodes plain-id :value]))
          "CONTROL: the undeclared sibling sub keeps its whole value")
      (is (str/includes? (str (sub-preview tab-data plain-id)) secret)
          "CONTROL: and its preview shows it"))))

(deftest live-graph-fails-closed-without-a-frame
  (testing "a live graph whose frame is nil redacts every value whole rather
            than preview it raw"
    (rf/reg-route ::plain {} "/graph-on-box/plain/:id")
    (rf/dispatch-sync [:rf.route/handle-url-change
                       (str "/graph-on-box/plain/" secret)
                       {:rf.route/cause :link}]
                      {:frame :rf/default})
    (let [preview (get-in (route-node-summary (h/tab-data (live-route-graph nil)))
                          [:params :preview])]
      (is (= ":rf/redacted" preview)
          (str "a frameless live graph must fail closed: " preview)))))

(deftest static-graph-passes-through
  (testing "a static graph carries no live values, so the projection is the
            identity on it"
    (let [graph {:mode :static :nodes {[:sub :a] {:kind :derivation :rf/family :subs}} :edges []}]
      (is (= graph (h/project-live-values graph))))))
