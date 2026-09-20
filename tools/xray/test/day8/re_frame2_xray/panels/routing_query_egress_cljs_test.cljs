(ns day8.re-frame2-xray.panels.routing-query-egress-cljs-test
  "The Routing panel's CURRENT ROUTE query is an EGRESS PROJECTION, not raw
  frame state (rf2-8nyi2).

  ## The defect these rows pin

  `panels/routing.cljs`'s CURRENT ROUTE section rendered the slice's
  `:query` with `pr-str` straight into
  `[:span {:data-testid \"rf-xray-routing-current-query\"}]`, with NO
  classification step between the observed frame's runtime-db and the DOM.
  The input chain was raw end to end — `:rf.xray/target-frame-runtime-db`
  is `(:rf.db/runtime (rf/frame-state-value target))`,
  `current-route-slice-value` reaches into it, and
  `routing_helpers/project-topology-data` forwards the slice unchanged — so
  a route that had DECLARED a query key `:sensitive` displayed its live
  value under the on-box `:rf.egress/local-redacted` default.

  That is a missed EXPLICIT data-hygiene declaration. It is NOT a claim
  that arbitrary undeclared trace carriers form a security boundary, and
  nothing here scrubs anything the author did not declare — the
  `renders-an-undeclared-query-verbatim` row is what pins that.

  ## Why these rows assert on the RENDERED output

  The seam is the `rf-xray-routing-current-query` testid, and only the
  rendered text can fail on a panel that still leaks: a helper's return
  value can be correct while the span beside it prints the raw map. So
  every row here walks the panel's hiccup and reads the span's text.

  ## Why the navigation is real

  `rf/reg-route` + a real `:rf.route/handle-url-change` is what makes the
  classification exist at all: a route declares `:sensitive` /
  `:large` PROJECTION-RELATIVE to its `{:query … :params …}` shape, and it
  is ACTIVATION that re-roots those paths to runtime-db-absolute
  `[:rf.runtime/routing :current …]` in the frame's elision registry
  (`re-frame.routing.classification`). A hand-typed slice injected through
  the test-override seam would carry no registry at all, and would pass
  against the unfixed panel.

  The URL form also exercises the QUERY-KEY PROMOTION the classification
  contract depends on: `:sensitive [[:query :token]]` names the KEYWORD
  `:token`, but a URL query key stays a STRING unless the route's `:query`
  schema promotes it, in which case the re-rooted keyword decl never
  matches and the value ships raw with zero signal. Both keys here are
  promoted, and `promotes-both-query-keys-to-keywords` is the control that
  says so — without it a redaction row could pass for the wrong reason."
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
  "Register a route declaring `[:query :token]` sensitive, with BOTH query
  keys promoted to keywords by its `:query` schema — modelled on
  spec/012-Routing.md §Route data classification's own example. `:tab` is
  the UNCLASSIFIED sibling: its survival is what separates a path-precise
  redaction from a fail-closed whole-value one."
  []
  (rf/reg-route ::oauth
                {:sensitive [[:query :token]]
                 :query     [:map [:token :string] [:tab :string]]}
                "/rf2-8nyi2/oauth"))

(defn- plain-route!
  "A route declaring NO classification at all — the ordinary case the fix
  must leave exactly as it was."
  []
  (rf/reg-route ::plain
                {:query [:map [:tab :string]]}
                "/rf2-8nyi2/plain"))

(defn- navigate! [url]
  (rf/dispatch-sync [:rf.route/handle-url-change url {:rf.route/cause :link}]
                    {:frame :rf/default}))

(defn- host-slice []
  (get-in (rf.frame/frame-runtime-db-value :rf/default)
          [:rf.runtime/routing :current]))

(defn- observe!
  "Install Xray's handlers and point the panel's OBSERVED frame at `frame`
  — the frame whose classification must govern. `:rf.xray/set-frame` is
  the frame picker's own event, so this is the production path."
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

;; ---- (0) the control: the promotion actually happened -------------------

(deftest promotes-both-query-keys-to-keywords
  (testing "rf2-8nyi2 control — the route's :query schema promotes BOTH keys
            to keywords, so the re-rooted [:query :token] declaration can
            match. Without this a redaction row below could pass because
            the walk failed closed rather than because it matched."
    (classified-route!)
    (navigate! (str "/rf2-8nyi2/oauth?token=" secret "&tab=" sibling))
    (let [q (:query (host-slice))]
      (is (= {:token secret :tab sibling} q)
          "both query keys promoted to keywords, values intact on the HOST slice
           — in-process reads stay RAW; redaction is read only at egress"))))

;; ---- (1) the defect: a declared-sensitive query key reaches the DOM -----

(deftest redacts-a-declared-sensitive-query-key-in-the-rendered-span
  (testing "rf2-8nyi2 — the CURRENT ROUTE query span renders :rf/redacted for
            a key the ACTIVE ROUTE declared :sensitive, and never its value.
            RED against the unfixed panel, which printed the live token."
    (classified-route!)
    (navigate! (str "/rf2-8nyi2/oauth?token=" secret "&tab=" sibling))
    (observe! :rf/default)
    (let [text (current-query-text)]
      (is (some? text)
          "the query span rendered at all (a nil here means the section
           went quiet, which would pass the leak assertion vacuously)")
      (is (not (re-find (re-pattern secret) text))
          (str "the declared-sensitive token LEAKED to the DOM: " (pr-str text)))
      (is (re-find #":rf/redacted" text)
          (str "the sensitive key did not lower to the :rf/redacted sentinel: "
               (pr-str text)))
      ;; The unclassified sibling is what proves this is the ROUTE's
      ;; path-precise declaration matching, and not a fail-closed
      ;; whole-value redaction that would have hidden the leak by accident.
      (is (re-find (re-pattern sibling) text)
          (str "the UNCLASSIFIED sibling key was scrubbed too — that is a
                blanket redaction, not the declaration: " (pr-str text))))))

;; ---- (2) ordinary query display is unchanged ----------------------------

(deftest renders-an-undeclared-query-verbatim
  (testing "rf2-8nyi2 — a route declaring NO classification renders its query
            exactly as before. The walk is path-precise, never a blanket
            scrub, and this item is explicitly not a claim that undeclared
            carriers are a boundary."
    (plain-route!)
    (navigate! (str "/rf2-8nyi2/plain?tab=" sibling))
    (observe! :rf/default)
    (let [text (current-query-text)]
      (is (some? text) "the query span rendered")
      (is (re-find (re-pattern sibling) text)
          (str "an undeclared query value was withheld: " (pr-str text)))
      (is (not (re-find #":rf/redacted" text))
          (str "an undeclared query redacted — over-scrub: " (pr-str text))))))

(deftest renders-no-query-span-when-the-route-carries-no-query
  (testing "rf2-8nyi2 — a slice with no query still renders NOTHING, not a
            sentinel. Fail-closed must not invent a value where the router
            wrote none, which is why only a slice that CARRIES a query is
            projected."
    (plain-route!)
    (navigate! "/rf2-8nyi2/plain")
    (observe! :rf/default)
    (is (nil? (current-query-text))
        "an absent query rendered a span")))

;; ---- (3) the sentinel paths render without a seq / type error ----------

(deftest whole-value-redaction-renders-without-a-seq-error
  (testing "rf2-8nyi2 — an UNREACHABLE observed frame fails closed to the
            scalar :rf/redacted sentinel. The old `(seq query)` guard threw
            on it (seq of a keyword is an error), so the panel would have
            died on exactly the frames whose policy had done its job."
    (classified-route!)
    (navigate! (str "/rf2-8nyi2/oauth?token=" secret "&tab=" sibling))
    ;; A frame id that was never registered: stamped VERBATIM, so the walker
    ;; takes its unresolvable-frame arm rather than borrowing the ambient
    ;; (Xray chrome) frame, which is live and declares nothing.
    (observe! ::never-registered-frame)
    (let [text (current-query-text)]
      (is (some? text)
          "the section threw or went quiet on the whole-value sentinel")
      (is (= ":rf/redacted" text)
          (str "the fail-closed sentinel did not render as itself: "
               (pr-str text)))
      (is (not (re-find (re-pattern secret) text))
          "the token survived an unreachable-frame walk"))))

(deftest show-query-admits-a-scalar-sentinel-and-still-hides-an-empty-map
  (testing "rf2-8nyi2 — the guard's two arms, taken directly. A collection
            still answers `seq` (so an empty query renders nothing, as
            before); a non-collection sentinel renders as itself; and the
            `{:rf.size/large-elided …}` marker needs no special case because
            it IS a map."
    (let [tree-for (fn [q]
                     (routing/panel-tree {:silent? false
                                          :topology []
                                          :current  {:route-id ::oauth
                                                     :params   {}
                                                     :query    q}}))
          text-for (fn [q]
                     (some-> (find-by-testid (tree-for q)
                                             "rf-xray-routing-current-query")
                             node-text))]
      (is (nil? (text-for nil))     "nil query rendered a span")
      (is (nil? (text-for {}))      "empty query rendered a span")
      (is (= ":rf/redacted" (text-for :rf/redacted))
          "the scalar sentinel did not render")
      (is (re-find #":rf.size/large-elided"
                   (text-for {:rf.size/large-elided {:chars 9000}}))
          "the large-elided marker did not render"))))

;; ---- (4) the explicit local-raw grain ----------------------------------

(deftest local-raw-opt-in-returns-the-declared-key-verbatim
  (testing "rf2-8nyi2 — the per-(tool,frame) :rf.egress/local-raw opt-in
            (EP-0015 §Cross-tool visibility grain) reaches the route-sub
            seam end to end: the SAME projection with `raw? true` returns
            the declared-sensitive value verbatim. The panel deliberately
            exposes no toggle, so it always passes the redacted default —
            this row pins that the mechanism exists rather than that the
            panel offers it."
    (classified-route!)
    (navigate! (str "/rf2-8nyi2/oauth?token=" secret "&tab=" sibling))
    (let [q       (:query (host-slice))
          default (local-render/local-render-route-sub-value
                    q :rf/default :rf.route/query)
          raw     (local-render/local-render-route-sub-value
                    q :rf/default :rf.route/query true)]
      (is (= :rf/redacted (:token default))
          "the redacted default did not lower the declared key")
      (is (= sibling (:tab default))
          "the redacted default scrubbed the undeclared sibling")
      (is (= secret (:token raw))
          "the trusted-local raw opt-in withheld the declared key")
      (is (= q raw)
          "raw is the identity over the whole query"))))

;; ---- (5) the seam is the ROUTE re-seeding, not a whole-value walk -------

(deftest whole-value-walk-cannot-match-the-re-rooted-declaration
  (testing "rf2-8nyi2 — why the fix names the sub rather than walking the
            value. A route's declaration is RE-ROOTED to the absolute
            `[:rf.runtime/routing :current :query :token]`, so the
            plain whole-value seam (`local-render-value`, path []) cannot
            match it and ships the token raw. This is the negative control
            for the door that was chosen."
    (classified-route!)
    (navigate! (str "/rf2-8nyi2/oauth?token=" secret "&tab=" sibling))
    (let [q       (:query (host-slice))
          at-root (local-render/local-render-value q :rf/default)
          seeded  (local-render/local-render-route-sub-value
                    q :rf/default :rf.route/query)]
      (is (= secret (:token at-root))
          "the whole-value seam unexpectedly matched — if this fails the
           re-rooting contract has changed and the route re-seed may be
           redundant")
      (is (= :rf/redacted (:token seeded))
          "the route-sub seam did not re-seed at the slice's storage position"))))
