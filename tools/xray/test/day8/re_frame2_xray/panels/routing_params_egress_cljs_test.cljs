(ns day8.re-frame2-xray.panels.routing-params-egress-cljs-test
  "The Routing panel's CURRENT ROUTE params are an EGRESS PROJECTION, not raw
  frame state.

  The params span renders UNCONDITIONALLY, so without the projection a path
  capture the active route declared `:sensitive` would reach the screen on
  every activation of that route. That is a missed EXPLICIT data-hygiene
  declaration; nothing here scrubs anything the author did not declare.

  A path capture is always keyword-keyed (`re-frame.routing.match` keywords
  every capture name), so — unlike the query axis — the re-rooted
  `[:params :token]` declaration matches with no `:params` schema.

  The rows assert on the RENDERED output, because only the rendered text can
  fail on a panel that leaks, and they navigate for real, because it is
  ACTIVATION that re-roots the route's projection-relative paths into the
  frame's elision registry — a hand-typed slice would carry no registry and
  pass against a panel that leaks."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.routing]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.panels.local-render :as local-render]
            [day8.re-frame2-xray.panels.routing :as routing]))

(use-fixtures :each (xray-test-support/make-xray-runtime-fixture))

;; ---- hiccup walkers (same shape as routing_query_egress_cljs_test) ------

(declare expand-fn-component)

(defn- expand-children [node]
  (cond
    (vector? node) (mapv expand-fn-component node)
    (seq? node)    (map  expand-fn-component node)
    :else          node))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-children (apply (first node) (rest node)))
    (expand-children node)))

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- node-text [node]
  (->> (hiccup-seq node)
       (filter string?)
       (apply str)))

;; ---- fixtures -----------------------------------------------------------

(def ^:private secret "secret-abc123")
(def ^:private sibling "posts")

(defn- classified-params-route!
  "A route declaring the PATH CAPTURE `[:params :token]` sensitive, with an
  UNCLASSIFIED sibling capture `:tab`, and no `:params` schema."
  []
  (rf/reg-route ::user
                {:sensitive [[:params :token]]}
                "/rf2-6j8gd/user/:token/:tab"))

(defn- navigate! [url]
  (rf/dispatch-sync [:rf.route/handle-url-change url {:rf.route/cause :link}]
                    {:frame :rf/default}))

(defn- observe!
  "Install Xray's handlers and point the panel's OBSERVED frame at `frame`
  through the frame picker's own event."
  [frame]
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/dispatch-sync [:rf.xray/set-frame frame] {:frame :rf/xray}))

(defn- current-params-text
  "The rendered text of the CURRENT ROUTE params span, or nil when the
  section rendered no params span at all."
  []
  (rf/with-frame :rf/xray
    (let [tree (routing/panel-tree @(rf/subscribe [:rf.xray/routing-tab-data]))]
      (some-> (find-by-testid tree "rf-xray-routing-current-params")
              node-text))))

(defn- current-section-text
  "Every string in the WHOLE CURRENT ROUTE section. A leak assertion scoped
  to the params span alone would pass over a value that reached the DOM
  through a neighbouring span instead."
  []
  (rf/with-frame :rf/xray
    (let [tree (routing/panel-tree @(rf/subscribe [:rf.xray/routing-tab-data]))]
      (or (some-> (find-by-testid tree "rf-xray-routing-current") node-text)
          ""))))

;; ---- a declared-sensitive path param must not reach the DOM --------------

(deftest redacts-a-declared-sensitive-path-param-in-the-rendered-span
  (testing "the CURRENT ROUTE params span renders :rf/redacted for a path
            capture the ACTIVE ROUTE declared :sensitive, and never its value"
    (classified-params-route!)
    (navigate! (str "/rf2-6j8gd/user/" secret "/" sibling))
    (observe! :rf/default)
    (let [text (current-params-text)]
      (is (not (re-find (re-pattern secret) (current-section-text)))
          (str "the declared-sensitive path param LEAKED to the DOM: "
               (pr-str (current-section-text))))
      (is (re-find #":rf/redacted" text)
          (str "the sensitive capture did not lower to the :rf/redacted
                sentinel: " (pr-str text)))
      ;; The unclassified sibling is what proves this is the route's
      ;; path-precise declaration matching, and not a blanket redaction.
      (is (re-find (re-pattern sibling) text)
          (str "the UNCLASSIFIED sibling capture was scrubbed too — that is a
                blanket redaction, not the declaration: " (pr-str text))))))

;; ---- the App-DB tab is NOT a second leaking site --------------------------

(deftest the-app-db-style-whole-runtime-db-walk-already-matches
  (testing "the App-DB tab reaches the SAME route slice, but projects the WHOLE
            runtime-db partition through `local-render-value`, whose walk
            coordinates ARE the absolute paths the route declaration was
            re-rooted onto — so it redacts the capture too"
    (classified-params-route!)
    (navigate! (str "/rf2-6j8gd/user/" secret "/" sibling))
    (let [projected (local-render/local-render-value
                      (rf.frame/frame-runtime-db-value :rf/default) :rf/default)]
      (is (= :rf/redacted
             (get-in projected [:rf.runtime/routing :current :params :token]))
          "a whole-runtime-db walk did NOT match the re-rooted route
           declaration — the App-DB tab would be a second leaking site")
      (is (= sibling
             (get-in projected [:rf.runtime/routing :current :params :tab]))
          "the whole-runtime-db walk scrubbed the unclassified sibling"))))
