(ns day8.re-frame2-causa.panels.hydration-pane-render-cljs-test
  "Unit guard for the Hydration Debugger per-pane renderer (rf2-dkmnm
  — closes the gap flagged in ai/findings/2026-05-21-testcov-causa.md
  §Axis 1.1).

  Before this file the Hydration panel
  (`panels/hydration-pane-render`) rested on the browser feature gate
  alone — `scenarios.cjs:runHydration`. This is the millisecond-fast
  unit backstop for the SSR-mismatch projection: the per-side
  (`:server` / `:client`) rendering that flips `:added` ↔ `:removed`
  semantics per perspective (rf2-1mcax Phase 4 of rf2-abts7).

  Each test feeds REAL before/after hiccup through the Phase 3 diff
  engine (`diff.hiccup/diff-hiccup-node`) and asserts the per-pane
  renderer projects the divergence correctly from each perspective —
  the contract a structural-diff regression would break."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.diff.hiccup :as hd]
            [day8.re-frame2-causa.panels.hydration-pane-render :as hp]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- hiccup-tree introspection helpers ---------------------------------

(defn- has-testid? [tree testid]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (= testid (:data-testid (second node)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

(defn- any-testid-prefix? [tree prefix]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (let [tid (:data-testid (second node))]
                 (and (string? tid) (.startsWith tid prefix)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

;; ---- perspective root --------------------------------------------------

(deftest render-pane-tags-the-perspective-root
  (testing "render-pane emits a perspective-scoped root testid so the
            two panes are independently addressable in the layout"
    (let [n (hd/diff-hiccup-node [:div {:class "a"} "k"]
                                 [:div {:class "b"} "k"])]
      (is (has-testid? (hp/render-pane n :server "hydration-view")
                       "rf-causa-hydration-pane-render-server")
          "server pane root testid missing")
      (is (has-testid? (hp/render-pane n :client "hydration-view")
                       "rf-causa-hydration-pane-render-client")
          "client pane root testid missing"))))

;; ---- no-diff chip ------------------------------------------------------

(deftest render-pane-shows-no-diff-chip-when-trees-match
  (testing "identical server+client hiccup → the 'no structural
            difference' chip renders (the green-light state of a
            successful hydration)"
    (let [n (hd/diff-hiccup-node [:div {:class "x"} "same"]
                                 [:div {:class "x"} "same"])]
      (is (has-testid? (hp/render-pane n :server "hydration-view")
                       "rf-causa-hydration-pane-no-diff-server")
          "no-diff chip missing on a matching tree")
      (is (not (any-testid-prefix? (hp/render-pane n :server "hydration-view")
                                   "rf-causa-hydration-pane-element-"))
          "an element subtree rendered for a no-diff tree"))))

;; ---- SSR mismatch: client-only node (added) ----------------------------

(deftest added-node-projects-absent-on-server-present-on-client
  (testing "rf2-1mcax — a client-only node (`:added`) renders as an
            'absent on this side' ghost on the SERVER pane and a
            present (green) row on the CLIENT pane. This is the
            headline SSR-mismatch projection: the client rendered an
            extra node the server markup lacked."
    (let [before [:ul [:li "a"]]
          after  [:ul [:li "a"] [:li "b"]]   ; client added a second <li>
          n      (hd/diff-hiccup-node before after)
          server (hp/render-pane n :server "hydration-view")
          client (hp/render-pane n :client "hydration-view")]
      (is (has-testid? server "rf-causa-hydration-pane-server-absent")
          "client-only node not shown as absent on the server pane")
      (is (has-testid? client "rf-causa-hydration-pane-only-here")
          "client-only node not shown as present-only on the client pane")
      (is (not (has-testid? client "rf-causa-hydration-pane-client-absent"))
          "client pane wrongly marked its own added node absent"))))

;; ---- SSR mismatch: server-only node (removed) --------------------------

(deftest removed-node-projects-present-on-server-absent-on-client
  (testing "rf2-1mcax — a server-only node (`:removed`) renders as a
            present (red) row on the SERVER pane and an 'absent on
            this side' ghost on the CLIENT pane — the mirror of the
            added case (server markup carried a node the client did
            not hydrate)."
    (let [before [:ul [:li "a"] [:li "b"]]   ; server had two <li>
          after  [:ul [:li "a"]]             ; client dropped the second
          n      (hd/diff-hiccup-node before after)
          server (hp/render-pane n :server "hydration-view")
          client (hp/render-pane n :client "hydration-view")]
      (is (has-testid? server "rf-causa-hydration-pane-only-here")
          "server-only node not shown as present-only on the server pane")
      (is (has-testid? client "rf-causa-hydration-pane-client-absent")
          "server-only node not shown as absent on the client pane")
      (is (not (has-testid? server "rf-causa-hydration-pane-server-absent"))
          "server pane wrongly marked its own removed node absent"))))

;; ---- SSR mismatch: divergent leaf value (modified) ---------------------

(deftest modified-leaf-shows-each-sides-value-per-perspective
  (testing "rf2-1mcax — a divergent text leaf (`:modified`) shows the
            pane's OWN value highlighted, with the other side's value
            as a faint annotation. The classic hydration-mismatch
            row (e.g. server rendered a timestamp, client re-computed
            a different one)."
    (let [before [:span "server-value"]
          after  [:span "client-value"]
          n      (hd/diff-hiccup-node before after)]
      (is (has-testid? (hp/render-pane n :server "hydration-view")
                       "rf-causa-hydration-pane-modified-server")
          "server-perspective modified row missing")
      (is (has-testid? (hp/render-pane n :client "hydration-view")
                       "rf-causa-hydration-pane-modified-client")
          "client-perspective modified row missing"))))

;; ---- SSR mismatch: divergent attribute --------------------------------

(deftest modified-attr-renders-per-perspective-attr-row
  (testing "rf2-1mcax — an attribute whose value diverges
            server↔client renders a per-perspective modified-attr row
            (e.g. server `:class \"a\"`, client `:class \"b\"`)."
    (let [before [:div {:class "a"} "k"]
          after  [:div {:class "b"} "k"]
          n      (hd/diff-hiccup-node before after)]
      (is (any-testid-prefix? (hp/render-pane n :server "hydration-view")
                              "rf-causa-hydration-attr-modified-")
          "server pane did not render the modified-attr row")
      (is (any-testid-prefix? (hp/render-pane n :client "hydration-view")
                              "rf-causa-hydration-attr-modified-")
          "client pane did not render the modified-attr row"))))

;; ---- server-only / client-only attribute -------------------------------

(deftest added-attr-projects-absent-server-present-client
  (testing "rf2-1mcax — an attribute present on the client only
            (`:added`) renders absent on the server pane and present
            (client-only) on the client pane."
    (let [before [:div {} "k"]
          after  [:div {:title "tip"} "k"]   ; client added :title
          n      (hd/diff-hiccup-node before after)]
      (is (any-testid-prefix? (hp/render-pane n :server "hydration-view")
                              "rf-causa-hydration-attr-server-absent-")
          "client-only attr not shown absent on the server pane")
      (is (any-testid-prefix? (hp/render-pane n :client "hydration-view")
                              "rf-causa-hydration-attr-client-only-")
          "client-only attr not shown present-only on the client pane"))))

;; ---- fn-prop opaque rule flows through (rf2-1mcax §opts passthrough) ----

(deftest fn-ref-change-surfaces-only-when-toggle-on
  (testing "rf2-1mcax — the opts map flows straight through to the
            diff engine: an anonymous-fn prop whose ref changed is
            opaque-by-default (NO fn-ref row) and surfaces a per-
            perspective fn-ref row ONLY when :highlight-fn-ref-changes?
            is on. Guards the per-render-noise suppression."
    (let [f1     (fn [_e] :one)
          f2     (fn [_e] :two)
          before [:button {:on-click f1} "Go"]
          after  [:button {:on-click f2} "Go"]
          quiet  (hd/diff-hiccup-node before after)
          loud   (hd/diff-hiccup-node before after {:highlight-fn-ref-changes? true})]
      (is (not (any-testid-prefix? (hp/render-pane quiet :server "hydration-view")
                                   "rf-causa-hydration-attr-fn-ref-changed-"))
          "opaque fn-ref change leaked a row with the toggle OFF")
      (is (any-testid-prefix? (hp/render-pane loud :server "hydration-view")
                              "rf-causa-hydration-attr-fn-ref-changed-")
          "fn-ref row missing with :highlight-fn-ref-changes? ON"))))

;; ---- expand-state key isolation per perspective ------------------------

(deftest panes-do-not-share-expand-state-keys
  (testing "rf2-1mcax — the node-key folds the perspective in so the
            server and client panes hold INDEPENDENT expand state for
            the same path. A renderer that dropped the perspective
            from the key would collapse the two panes' expand state
            into one — assert the rendered trees differ in their
            per-perspective testids (a proxy for distinct keying)."
    (let [n      (hd/diff-hiccup-node [:span "server-value"]
                                      [:span "client-value"])
          server (hp/render-pane n :server "hydration-view")
          client (hp/render-pane n :client "hydration-view")]
      (is (has-testid? server "rf-causa-hydration-pane-modified-server"))
      (is (not (has-testid? server "rf-causa-hydration-pane-modified-client"))
          "server pane leaked the client-perspective testid")
      (is (has-testid? client "rf-causa-hydration-pane-modified-client"))
      (is (not (has-testid? client "rf-causa-hydration-pane-modified-server"))
          "client pane leaked the server-perspective testid"))))
