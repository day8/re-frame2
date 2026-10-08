(ns day8.re-frame2-xray.panels.routing-query-egress-cljs-test
  "The Routing panel's CURRENT ROUTE query is an EGRESS PROJECTION, not raw
  frame state.

  The input chain around the projection is raw —
  `:rf.xray/target-frame-runtime-db` is
  `(:rf.db/runtime (rf/frame-state-value target))`, and
  `routing_helpers/project-topology-data` forwards the slice as-is — so
  `current-route-slice-value`'s projection is the only classification step
  between the observed frame's runtime-db and the DOM. Without it the query
  span would display the live value of a key the route DECLARED `:sensitive`.
  That is a missed EXPLICIT data-hygiene declaration; nothing here scrubs
  anything the author did not declare.

  The rows assert on the RENDERED output, because only the rendered text can
  fail on a panel that leaks, and they navigate for real, because it is
  ACTIVATION that re-roots the route's projection-relative paths into the
  frame's elision registry — a hand-typed slice would carry no registry and
  pass against a panel that leaks. The route's `:query` schema promotes both
  keys to keywords, which the re-rooted keyword declaration needs to match."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.routing]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.panels.local-render :as local-render]
            [day8.re-frame2-xray.panels.routing :as routing]))

(use-fixtures :each (xray-test-support/make-xray-runtime-fixture))

;; ---- hiccup walkers (same shape as routing_cljs_test) -------------------

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

(defn- classified-route!
  "A route declaring `[:query :token]` sensitive, with BOTH query keys
  promoted to keywords by its `:query` schema — modelled on
  spec/012-Routing.md §Route data classification's own example. `:tab` is
  the UNCLASSIFIED sibling: its survival is what separates a path-precise
  redaction from a fail-closed whole-value one."
  []
  (rf/reg-route ::oauth
                {:sensitive [[:query :token]]
                 :query     [:map [:token :string] [:tab :string]]}
                "/rf2-8nyi2/oauth"))

(defn- navigate! [url]
  (rf/dispatch-sync [:rf.route/handle-url-change url {:rf.route/cause :link}]
                    {:frame :rf/default}))

(defn- host-slice []
  (get-in (rf.frame/frame-runtime-db-value :rf/default)
          [:rf.runtime/routing :current]))

(defn- observe!
  "Install Xray's handlers and point the panel's OBSERVED frame at `frame`
  through the frame picker's own event."
  [frame]
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/dispatch-sync [:rf.xray/set-frame frame] {:frame :rf/xray}))

(defn- current-query-text
  "The rendered text of the CURRENT ROUTE query span, or nil when the
  section rendered no query span at all."
  []
  (rf/with-frame :rf/xray
    (let [tree (routing/panel-tree @(rf/subscribe [:rf.xray/routing-tab-data]))]
      (some-> (find-by-testid tree "rf-xray-routing-current-query")
              node-text))))

(defn- current-section-text
  "Every string in the WHOLE CURRENT ROUTE section. A leak assertion
  scoped to the query span alone would pass over a value that reached the
  DOM through a neighbouring span instead."
  []
  (rf/with-frame :rf/xray
    (let [tree (routing/panel-tree @(rf/subscribe [:rf.xray/routing-tab-data]))]
      (or (some-> (find-by-testid tree "rf-xray-routing-current") node-text)
          ""))))

;; ---- a declared-sensitive query key must not reach the DOM -------------

(deftest redacts-a-declared-sensitive-query-key-in-the-rendered-span
  (testing "the CURRENT ROUTE query span renders :rf/redacted for a key the
            ACTIVE ROUTE declared :sensitive, and never its value"
    (classified-route!)
    (navigate! (str "/rf2-8nyi2/oauth?token=" secret "&tab=" sibling))
    (observe! :rf/default)
    (let [text (current-query-text)]
      (is (not (re-find (re-pattern secret) (current-section-text)))
          (str "the declared-sensitive token LEAKED to the DOM: "
               (pr-str (current-section-text))))
      (is (re-find #":rf/redacted" text)
          (str "the sensitive key did not lower to the :rf/redacted sentinel: "
               (pr-str text)))
      ;; The unclassified sibling is what proves this is the route's
      ;; path-precise declaration matching — which also needs the keys
      ;; promoted — and not a fail-closed whole-value redaction.
      (is (re-find (re-pattern sibling) text)
          (str "the UNCLASSIFIED sibling key was scrubbed too — that is a
                blanket redaction, not the declaration: " (pr-str text))))))

;; ---- an unreachable observed frame cannot leak ----------------------------

(deftest unreachable-observed-frame-yields-no-slice-and-cannot-leak
  (testing "an UNREACHABLE observed frame cannot put a query on screen at all:
            `frame-state-value` answers nil for an unknown frame, so there is no
            slice to project and no query span. The seam's fail-closed sentinel
            is still reachable, asserted directly here, and the render tolerates
            it — `show-query-admits-a-scalar-sentinel…` below."
    (classified-route!)
    (navigate! (str "/rf2-8nyi2/oauth?token=" secret "&tab=" sibling))
    (let [q (:query (host-slice))]
      ;; A frame id that was never registered, stamped VERBATIM, so the
      ;; walker takes its unresolvable-frame arm rather than borrowing the
      ;; ambient (Xray chrome) frame — which IS live and declares nothing.
      (observe! ::never-registered-frame)
      (is (nil? (current-query-text))
          "an unreachable observed frame produced a query span")
      (is (not (re-find (re-pattern secret) (current-section-text)))
          "the token reached the CURRENT ROUTE section under an unreachable frame")
      (is (= :rf/redacted
             (local-render/local-render-route-sub-value
               q ::never-registered-frame :rf.route/query))
          "the seam's fail-closed arm did not redact the whole value"))))

;; ---- the render guard ------------------------------------------------------

(deftest show-query-admits-a-scalar-sentinel-and-still-hides-an-empty-map
  (testing "the guard's two arms: a collection answers `seq`, so an empty query
            renders nothing; a non-collection sentinel renders as itself"
    (let [text-for (fn [q]
                     (some-> (find-by-testid
                               (routing/panel-tree {:silent? false
                                                    :topology []
                                                    :current  {:route-id ::oauth
                                                               :params   {}
                                                               :query    q}})
                               "rf-xray-routing-current-query")
                             node-text))]
      (is (nil? (text-for nil))     "nil query rendered a span")
      (is (nil? (text-for {}))      "empty query rendered a span")
      (is (= ":rf/redacted" (text-for :rf/redacted))
          "the scalar sentinel did not render"))))
