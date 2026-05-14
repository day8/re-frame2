(ns day8.re-frame2-causa.panels.subscriptions-view-cljs-test
  "CLJS-side wiring + view tests for Causa's Subscriptions panel
  (Phase 5, rf2-x0f5v).

  ## What's under test (in addition to the pure-data tests in
  `subscriptions_helpers_cljs_test.cljc`)

    1. **Registry wires the composite sub** under
       `:rf.causa/subscriptions-data`. The composite returns rows +
       counts + selection in the shape the view consumes.

    2. **Filter chip toggles** mutate `:rf.causa/sub-filters` on the
       Causa frame's db; the panel renders the toggled state.

    3. **Sub selection + chain open** wire through
       `:rf.causa/select-sub` and `:rf.causa/show-invalidation-chain`,
       and the panel surfaces the chain affordance.

    4. **Empty state** — when the sub-cache override is unset and
       `(rf/sub-cache target-frame)` returns nil (no host), the panel
       renders its empty state.

  ## Pure hiccup

  Same approach as `causality_graph_view_cljs_test.cljs` — walk the
  view's hiccup tree by `data-testid` rather than mounting to the
  DOM. Keeps the suite fast + host-portable on node-test."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.subscriptions :as subscriptions]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers (mirror event_detail_cljs_test) ---------------------

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
    node))

(defn- hiccup-seq [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree))
       (map expand-fn-component)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (hiccup-seq tree)))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- override-cache! [cache]
  (rf/dispatch-sync [:rf.causa/set-sub-cache-override-for-test cache]))

;; ---- (1) registry wires the composite sub -------------------------------

(deftest registry-installs-subscriptions-sub
  (testing "register-causa-handlers! installs the Phase 5 composite sub +
            every supporting event"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/subscriptions-data)))
    (is (some? (registrar/handler :sub :rf.causa/sub-cache)))
    (is (some? (registrar/handler :sub :rf.causa/sub-error-cache)))
    (is (some? (registrar/handler :sub :rf.causa/selected-sub)))
    (is (some? (registrar/handler :sub :rf.causa/sub-filters)))
    (is (some? (registrar/handler :sub :rf.causa/sub-chain-open?)))
    (is (some? (registrar/handler :event :rf.causa/select-sub)))
    (is (some? (registrar/handler :event :rf.causa/clear-selected-sub)))
    (is (some? (registrar/handler :event :rf.causa/toggle-sub-filter)))
    (is (some? (registrar/handler :event :rf.causa/show-invalidation-chain)))
    (is (some? (registrar/handler :event :rf.causa/hide-invalidation-chain)))))

(deftest subscriptions-sub-defaults-empty
  (testing "with no sub-cache override and no live substrate the
            composite returns an empty rows vector + empty filters"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/subscriptions-data])]
        (is (= [] (:rows data)))
        (is (= 0 (:total data)))
        (is (= #{} (:active-filters data)))
        (is (false? (:chain-open? data)))))))

(deftest subscriptions-sub-projects-cache-into-rows
  (testing "with a sub-cache override the composite returns one row
            per entry — projected via the helpers"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-cache!
        {[:cart/total]     {:ref-count 1 :layer 3
                            :input-subs [[:cart/items]]}
         [:cart/items]     {:ref-count 1 :layer 2
                            :input-subs [[:cart/items-raw]]}
         [:cart/items-raw] {:ref-count 1 :layer 1}})
      (let [data @(rf/subscribe [:rf.causa/subscriptions-data])
            ids  (set (map :sub-id (:rows data)))]
        (is (= 3 (:total data)))
        (is (= #{:cart/total :cart/items :cart/items-raw} ids))))))

;; ---- (2) view renders ---------------------------------------------------

(deftest empty-state-renders-when-cache-empty
  (testing "with no override and no substrate the panel renders the
            empty state"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [tree (subscriptions/subscriptions-view)]
        (is (some? (find-by-testid tree "rf-causa-subscriptions"))
            "panel container present")
        (is (some? (find-by-testid tree "rf-causa-subscriptions-empty"))
            "empty-state container present")
        (is (nil? (find-by-testid tree "rf-causa-subscriptions-list"))
            "no list when there are zero rows")))))

(deftest list-renders-when-cache-populated
  (testing "with a populated sub-cache the panel renders one row per
            sub plus the filter header"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-cache!
        {[:cart/total] {:ref-count 1 :layer 3}
         [:user/auth]  {:ref-count 2 :layer 1}})
      (let [tree (subscriptions/subscriptions-view)]
        (is (some? (find-by-testid tree "rf-causa-subscriptions-list"))
            "list container present")
        (is (some? (find-by-testid tree "rf-causa-sub-row-:cart/total"))
            "row for :cart/total present")
        (is (some? (find-by-testid tree "rf-causa-sub-row-:user/auth"))
            "row for :user/auth present")
        (is (some? (find-by-testid tree "rf-causa-subscriptions-filters"))
            "filter chip header present")))))

(deftest all-five-filter-chips-render
  (testing "the filter header renders one chip per status (5 total)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-cache! {[:a] {:ref-count 1}}) ; need >0 total to render filters
      (let [tree  (subscriptions/subscriptions-view)
            chips (find-all-by-testid-prefix tree "rf-causa-sub-filter-")]
        (is (= 5 (count chips))
            "5 chips — one per status in the taxonomy")))))

;; ---- (3) filter-toggle event --------------------------------------------

(deftest toggle-filter-writes-to-causa-frame
  (testing "dispatching :rf.causa/toggle-sub-filter toggles set membership
            on the Causa frame"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/toggle-sub-filter :error])
      (is (= #{:error} @(rf/subscribe [:rf.causa/sub-filters])))
      (rf/dispatch-sync [:rf.causa/toggle-sub-filter :invalidated])
      (is (= #{:error :invalidated} @(rf/subscribe [:rf.causa/sub-filters])))
      (rf/dispatch-sync [:rf.causa/toggle-sub-filter :error])
      (is (= #{:invalidated} @(rf/subscribe [:rf.causa/sub-filters]))
          "second toggle removes the status from the filter set"))))

(deftest filter-restricts-rendered-rows
  (testing "an active filter cuts the row list down to matching rows"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-cache!
        {[:fresh-sub]    {:ref-count 1}
         [:cached-sub]   {:ref-count 0}})
      (rf/dispatch-sync [:rf.causa/toggle-sub-filter :fresh])
      (let [tree (subscriptions/subscriptions-view)]
        (is (some? (find-by-testid tree "rf-causa-sub-row-:fresh-sub"))
            "fresh sub passes the filter")
        (is (nil? (find-by-testid tree "rf-causa-sub-row-:cached-sub"))
            "cached-no-watcher sub is filtered out")))))

;; ---- (4) sub selection + chain affordance -------------------------------

(deftest select-sub-event-writes-to-causa-frame
  (testing ":rf.causa/select-sub stores the query-v on the Causa frame"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-sub [:cart/total]])
      (is (= [:cart/total] @(rf/subscribe [:rf.causa/selected-sub]))))))

(deftest show-invalidation-chain-opens-the-affordance
  (testing "show-invalidation-chain sets sub-chain-open? + selects the sub"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/show-invalidation-chain [:cart/total]])
      (is (true? @(rf/subscribe [:rf.causa/sub-chain-open?])))
      (is (= [:cart/total] @(rf/subscribe [:rf.causa/selected-sub]))))))

(deftest hide-invalidation-chain-closes-the-affordance
  (testing "hide-invalidation-chain clears sub-chain-open?"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/show-invalidation-chain [:cart/total]])
      (rf/dispatch-sync [:rf.causa/hide-invalidation-chain])
      (is (false? @(rf/subscribe [:rf.causa/sub-chain-open?]))))))

(deftest chain-renders-when-open
  (testing "the chain affordance renders below the list when sub-chain-open?
            is true and the focused sub exists in the cache"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-cache!
        {[:cart/total] {:ref-count 1 :layer 3
                        :input-subs [[:cart/items]]}
         [:cart/items] {:ref-count 1 :layer 2}})
      (rf/dispatch-sync [:rf.causa/show-invalidation-chain [:cart/total]])
      (let [tree (subscriptions/subscriptions-view)]
        (is (some? (find-by-testid tree "rf-causa-subscriptions-chain"))
            "chain container present when chain-open? is true")
        (is (some? (find-by-testid tree "rf-causa-subscriptions-chain-focused"))
            "focused sub row present in chain")))))

(deftest chain-not-rendered-when-closed
  (testing "with sub-chain-open? false the chain affordance is absent"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-cache! {[:cart/total] {:ref-count 1 :layer 3}})
      (let [tree (subscriptions/subscriptions-view)]
        (is (nil? (find-by-testid tree "rf-causa-subscriptions-chain"))
            "no chain container when chain-open? is false")))))

(deftest chain-missing-sub-renders-missing-state
  (testing "if the focused sub isn't in the cache the chain shows the
            'missing' branch"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; No override — cache is nil; chain on any sub is :missing?
      (rf/dispatch-sync [:rf.causa/show-invalidation-chain [:nonexistent]])
      (let [tree (subscriptions/subscriptions-view)]
        (is (some? (find-by-testid tree "rf-causa-subscriptions-chain-missing"))
            "missing branch surfaces")))))

;; ---- (5) badges per status ----------------------------------------------

(deftest each-status-has-its-badge-in-row
  (testing "every status in the taxonomy gets its glyph + colour on the row"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-cache!
        {[:fresh-sub]    {:ref-count 1}
         [:invalid-sub]  {:invalidated? true :ref-count 0}
         [:running-sub]  {:rerunning? true :ref-count 1}
         [:cached-sub]   {:ref-count 0}})
      (let [tree (subscriptions/subscriptions-view)]
        (is (some? (find-by-testid tree "rf-causa-sub-badge-fresh")))
        (is (some? (find-by-testid tree "rf-causa-sub-badge-invalidated")))
        (is (some? (find-by-testid tree "rf-causa-sub-badge-re-running")))
        (is (some? (find-by-testid tree "rf-causa-sub-badge-cached-no-watcher")))))))

;; ---- (6) frame isolation ------------------------------------------------

(deftest sub-filter-state-does-not-leak-into-default-frame
  (testing "the panel's filter state lives on :rf/causa, never :rf/default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/toggle-sub-filter :error]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= #{:error} (:sub-filters causa-db))
          "filter set lands on Causa")
      (is (nil? (:sub-filters default-db))
          "filter set did NOT leak into :rf/default"))))
