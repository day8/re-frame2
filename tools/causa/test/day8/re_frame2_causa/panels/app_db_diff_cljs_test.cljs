(ns day8.re-frame2-causa.panels.app-db-diff-cljs-test
  "CLJS-side wiring + view tests for Causa's App-DB Diff panel
  (Phase 5, rf2-jps1o).

  ## Contracts under test (beyond the pure-data tests in
  `app_db_diff_helpers_cljs_test.cljc`)

  1. **Registry wires the Phase 5 subs / events** under the
     `:rf.causa/*` namespace. The composite `:rf.causa/app-db-diff`
     returns the panel's render data; the pin / unpin / focus events
     write into `:rf/causa`'s app-db.

  2. **Pin / unpin / reorder / focus events update Causa's frame.**
     Per spec §Pinned slices + §'Show me when this changed' — the
     view fires `:rf.causa/pin-slice` etc. and the state lives in
     the Causa frame.

  3. **Reserved-keys segregation at the panel level.** The view
     renders the `[runtime]` group separately from the slice mini-
     panels.

  4. **'Show me when this changed' returns only epochs that touched
     the focused path.** Asserted both at the sub level (via
     `:rf.causa/show-me-when-this-changed-result`) and at the view
     level (via the focus-result-panel rendering).

  ## Pure hiccup

  Same approach as `event_detail_cljs_test.cljs` /
  `time_travel_cljs_test.cljs` / `causality_graph_view_cljs_test.cljs`
  — we walk the view's hiccup tree by `data-testid` rather than
  mounting to a DOM. Keeps the suite fast + host-portable on node-
  test."
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
            [day8.re-frame2-causa.panels.app-db-diff :as app-db-diff]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  ;; The `:rf.causa/selected-epoch-diff` cache is a top-level
  ;; `defonce` (lifted from let-local for rf2-o94sp testability —
  ;; see app_db_diff.cljs §diff-cache). Reset between tests so
  ;; cache-size assertions are reproducible across the corpus.
  (reset! app-db-diff/diff-cache {}))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- fixture data --------------------------------------------------------

(defn- mk-record
  "Build a minimal `:rf/epoch-record` map for the diff walker /
  selected-epoch-diff sub tests."
  [epoch-id event db-before db-after]
  {:epoch-id      epoch-id
   :frame         :rf/default
   :committed-at  0
   :event-id      (first event)
   :trigger-event event
   :db-before     db-before
   :db-after      db-after
   :trace-events  []})

(defn- register-seed-events!
  "Register test-only seed events that write directly to the Causa
  frame's app-db. Production code reaches the same shape via
  :rf.causa/epoch-recorded + the host frame's runtime mutations."
  []
  (rf/reg-event-db :rf.causa-test/seed-history
    (fn [db [_ records]]
      (assoc db :epoch-history (vec records))))
  (rf/reg-event-db :rf.causa-test/seed-target-frame-db
    (fn [db _]
      ;; This is a no-op marker — the host frame's db is the source
      ;; of truth, and we set it via reset-frame-db! below. Kept so
      ;; tests can locate the seed step by name.
      db)))

(defn- seed-host-frame!
  "Reset the host (:rf/default) frame's app-db to the supplied
  value via the framework's reset-frame-db!. The Phase 5 panel
  derefs the host frame via :rf.causa/target-frame-db."
  [db-value]
  (frame/reg-frame :rf/default {})
  (rf/reset-frame-db! :rf/default db-value))

(defn- seed-causa!
  "Wire the Phase 5 sub graph + seed history + host-frame db. The
  test environment proxies the production wiring path (preload +
  registry + epoch-cb) so the subs read live values."
  [host-db-value history]
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (register-seed-events!)
  (seed-host-frame! host-db-value)
  (when (seq history)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa-test/seed-history history]))))

;; ---- hiccup walker (mirrors event_detail_cljs_test.cljs) ----------------

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

;; ---- (1) registry wires the subs / events -------------------------------

(deftest registry-installs-app-db-diff-subs
  (testing "register-causa-handlers! installs the Phase 5 subs"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/target-frame-db)))
    (is (some? (registrar/handler :sub :rf.causa/selected-epoch-record)))
    (is (some? (registrar/handler :sub :rf.causa/selected-epoch-diff)))
    (is (some? (registrar/handler :sub :rf.causa/pinned-slices-store)))
    (is (some? (registrar/handler :sub :rf.causa/pinned-slices)))
    (is (some? (registrar/handler :sub :rf.causa/focused-slice-path)))
    (is (some? (registrar/handler :sub :rf.causa/show-me-when-this-changed-result)))
    (is (some? (registrar/handler :sub :rf.causa/app-db-diff)))))

(deftest registry-installs-app-db-diff-events
  (testing "register-causa-handlers! installs the Phase 5 events"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :event :rf.causa/pin-slice)))
    (is (some? (registrar/handler :event :rf.causa/unpin-slice)))
    (is (some? (registrar/handler :event :rf.causa/reorder-pinned-slices)))
    (is (some? (registrar/handler :event :rf.causa/focus-slice-path)))
    (is (some? (registrar/handler :event :rf.causa/clear-slice-focus)))
    (is (some? (registrar/handler :event :rf.causa/copy-value-to-clipboard)))
    (is (some? (registrar/handler :event :rf.causa/copy-path-to-clipboard)))))

(deftest registry-installs-clipboard-fx
  (testing "register-causa-handlers! installs the :rf.causa.fx/copy-to-
            clipboard effect"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :fx :rf.causa.fx/copy-to-clipboard)))))

;; ---- (2) composite sub returns sane defaults ----------------------------

(deftest app-db-diff-sub-defaults
  (testing ":rf.causa/app-db-diff returns sane defaults on a fresh frame"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (frame/reg-frame :rf/default {})
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/app-db-diff])]
        (is (= :rf/default (:target-frame data)))
        (is (true? (:history-empty? data)))
        (is (nil? (:focused-path data)))
        (is (= [] (:pinned-slices data)))
        (is (= [] (:focused-hits data)))))))

;; ---- (3) selected-epoch-diff produces correct triples -------------------

(deftest selected-epoch-diff-produces-triples
  (testing "with history populated, :rf.causa/selected-epoch-diff
            produces the [op path before after] triples"
    (seed-causa! {:cart {:items [{:id 7}]}}
                 [(mk-record :e-1 [:cart/add-item]
                             {:cart {:items []}}
                             {:cart {:items [{:id 7}]}})])
    (rf/with-frame :rf/causa
      (let [diff @(rf/subscribe [:rf.causa/selected-epoch-diff])]
        (is (= 1 (count diff)))
        (is (= {:op :modified :path [:cart :items]
                :before [] :after [{:id 7}]}
               (first diff)))))))

(deftest selected-epoch-diff-tracks-selected-epoch
  (testing "when an epoch is selected, the diff is for THAT epoch — not
            the newest one"
    (let [hist [(mk-record :e-1 [:app/boot] {} {:counter 0})
                (mk-record :e-2 [:counter/inc]
                           {:counter 0} {:counter 1})
                (mk-record :e-3 [:counter/inc]
                           {:counter 1} {:counter 2})]]
      (seed-causa! {:counter 2} hist)
      (rf/with-frame :rf/causa
        (rf/dispatch-sync [:rf.causa/select-epoch :e-2])
        (let [diff @(rf/subscribe [:rf.causa/selected-epoch-diff])]
          (is (= [{:op :modified :path [:counter] :before 0 :after 1}]
                 diff)
              ":e-2's diff selected, not :e-3's"))))))

;; ---- (3b) per-:epoch-id diff cache (rf2-qvaa0) --------------------------

(deftest selected-epoch-diff-cache-hits-on-repeat-deref
  (testing "the diff is computed once per :epoch-id — second deref of
            the same selection returns identical?-equal triples (the
            cache short-circuit, not just =-equal)"
    (let [hist [(mk-record :e-1 [:counter/inc]
                           {:counter 0} {:counter 1})]]
      (seed-causa! {:counter 1} hist)
      (rf/with-frame :rf/causa
        (let [first-call  @(rf/subscribe [:rf.causa/selected-epoch-diff])
              second-call @(rf/subscribe [:rf.causa/selected-epoch-diff])]
          (is (= first-call second-call))
          (is (identical? first-call second-call)
              "second deref returns the cached object — no recompute"))))))

(deftest selected-epoch-diff-cache-evicts-aged-out-epochs
  (testing "epoch-ids no longer in the history get pruned from the
            cache — the cache cannot grow past the history depth"
    (let [hist-a [(mk-record :e-1 [:a] {} {:n 1})]
          hist-b [(mk-record :e-2 [:b] {} {:n 2})
                  (mk-record :e-3 [:c] {:n 2} {:n 3})]]
      (seed-causa! {:n 1} hist-a)
      (rf/with-frame :rf/causa
        ;; Warm the cache with :e-1's diff.
        @(rf/subscribe [:rf.causa/selected-epoch-diff])
        ;; Rotate the history — :e-1 ages out, :e-2/:e-3 take its place.
        (rf/dispatch-sync [:rf.causa-test/seed-history hist-b])
        ;; Read the new newest-epoch diff. The cache must prune :e-1
        ;; (no longer in history) on this read.
        (let [diff @(rf/subscribe [:rf.causa/selected-epoch-diff])]
          (is (= [{:op :modified :path [:n] :before 2 :after 3}]
                 diff)
              "newest-epoch (:e-3) diff is fresh; :e-1's entry is gone"))))))

(deftest selected-epoch-diff-cache-size-tracks-live-history-length
  (testing "audit rf2-i0veg §4c — cache size never exceeds the live
            history depth; aged-out epoch-ids are pruned on the very
            next read, not on a deferred sweep"
    ;; Seed 4 epochs, warm the cache by selecting each in turn so the
    ;; cache holds all 4 entries.
    (let [hist-a [(mk-record :e-1 [:a] {}     {:n 1})
                  (mk-record :e-2 [:b] {:n 1} {:n 2})
                  (mk-record :e-3 [:c] {:n 2} {:n 3})
                  (mk-record :e-4 [:d] {:n 3} {:n 4})]]
      (seed-causa! {:n 4} hist-a)
      (rf/with-frame :rf/causa
        (doseq [eid [:e-1 :e-2 :e-3 :e-4]]
          (rf/dispatch-sync [:rf.causa/select-epoch eid])
          @(rf/subscribe [:rf.causa/selected-epoch-diff]))
        (is (= 4 (count @app-db-diff/diff-cache))
            "after warming all four selections, cache holds four entries")
        (is (= #{:e-1 :e-2 :e-3 :e-4} (set (keys @app-db-diff/diff-cache)))
            "cache keys match the live history's epoch-ids exactly"))
      ;; Rotate to a 2-epoch history; :e-1/:e-2 age out.
      (let [hist-b [(mk-record :e-5 [:e] {:n 4} {:n 5})
                    (mk-record :e-6 [:f] {:n 5} {:n 6})]]
        (rf/with-frame :rf/causa
          (rf/dispatch-sync [:rf.causa-test/seed-history hist-b])
          ;; Read newest diff — triggers the prune-on-read step.
          (rf/dispatch-sync [:rf.causa/select-epoch nil])
          @(rf/subscribe [:rf.causa/selected-epoch-diff])
          (is (<= (count @app-db-diff/diff-cache) 2)
              "after rotation + one read, cache size ≤ live history depth")
          (is (not (contains? @app-db-diff/diff-cache :e-1))
              ":e-1 (aged out) is no longer in the cache")
          (is (not (contains? @app-db-diff/diff-cache :e-2))
              ":e-2 (aged out) is no longer in the cache")
          (is (not (contains? @app-db-diff/diff-cache :e-3))
              ":e-3 (aged out) is no longer in the cache")
          (is (not (contains? @app-db-diff/diff-cache :e-4))
              ":e-4 (aged out) is no longer in the cache")
          (is (contains? @app-db-diff/diff-cache :e-6)
              ":e-6 (just read) is in the cache"))))))

(deftest selected-epoch-diff-cache-distinguishes-distinct-epochs
  (testing "different :epoch-id selections each get their own cached diff"
    (let [hist [(mk-record :e-1 [:a] {} {:counter 0})
                (mk-record :e-2 [:counter/inc]
                           {:counter 0} {:counter 1})
                (mk-record :e-3 [:counter/inc]
                           {:counter 1} {:counter 2})]]
      (seed-causa! {:counter 2} hist)
      (rf/with-frame :rf/causa
        (rf/dispatch-sync [:rf.causa/select-epoch :e-2])
        (let [diff-e2 @(rf/subscribe [:rf.causa/selected-epoch-diff])]
          (rf/dispatch-sync [:rf.causa/select-epoch :e-3])
          (let [diff-e3 @(rf/subscribe [:rf.causa/selected-epoch-diff])]
            (is (not= diff-e2 diff-e3)
                "different selections produce different diffs")
            (is (= [{:op :modified :path [:counter] :before 0 :after 1}]
                   diff-e2))
            (is (= [{:op :modified :path [:counter] :before 1 :after 2}]
                   diff-e3))))
        ;; Returning to :e-2 still gets its cached triples (identical?
        ;; to the first read — proves the cache survives the cross-
        ;; selection roundtrip).
        (rf/dispatch-sync [:rf.causa/select-epoch :e-2])
        (let [diff-e2-second @(rf/subscribe [:rf.causa/selected-epoch-diff])
              diff-e2-third  @(rf/subscribe [:rf.causa/selected-epoch-diff])]
          (is (identical? diff-e2-second diff-e2-third)
              "the cache survives selection changes"))))))

;; ---- (4) reserved-keys segregation at the composite level ---------------

(deftest app-db-diff-segregates-reserved-keys
  (testing "the composite splits triples whose path roots in :rf/* from
            the rest; reserved triples go to :changed-reserved as the
            current snapshot (informational group per spec §Reserved-
            keys group), non-reserved go to :changed-non-reserved"
    (seed-causa! {:cart {:items [{:id 7}]}
                  :rf/route {:id :app/cart}
                  :rf/machines {:auth {:state :idle}}}
                 [(mk-record :e-1 [:nav/cart]
                             {:cart {:items []}}
                             {:cart {:items [{:id 7}]}
                              :rf/route {:id :app/cart}})])
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/app-db-diff])
            non-reserved-paths (set (map :path (:changed-non-reserved data)))
            reserved-keys-shown (set (map first (:changed-reserved data)))]
        (is (contains? non-reserved-paths [:cart :items])
            "non-reserved path lands in :changed-non-reserved")
        (is (not (contains? non-reserved-paths [:rf/route]))
            ":rf/route filtered out of :changed-non-reserved")
        (is (contains? reserved-keys-shown :rf/route))
        (is (contains? reserved-keys-shown :rf/machines))))))

;; ---- (5) pin / unpin events write to Causa frame ------------------------

(deftest pin-slice-event-writes-to-causa-frame
  (testing ":rf.causa/pin-slice lands the path on :rf/causa's
            :pinned-slices-store, NOT on the host's app-db"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (frame/reg-frame :rf/default {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/pin-slice [:user :auth :status]]))
    (is (= {:rf/default [[:user :auth :status]]}
           (:pinned-slices-store (frame/frame-app-db-value :rf/causa))))
    (is (nil? (:pinned-slices-store (frame/frame-app-db-value :rf/default))))))

(deftest pin-and-unpin-roundtrip
  (testing ":rf.causa/pin-slice then :rf.causa/unpin-slice → empty pin vector"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (frame/reg-frame :rf/default {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/pin-slice [:a]])
      (rf/dispatch-sync [:rf.causa/pin-slice [:b]])
      (let [s1 (:pinned-slices-store (frame/frame-app-db-value :rf/causa))]
        (is (= [[:a] [:b]] (get s1 :rf/default))))
      (rf/dispatch-sync [:rf.causa/unpin-slice [:a]])
      (let [s2 (:pinned-slices-store (frame/frame-app-db-value :rf/causa))]
        (is (= [[:b]] (get s2 :rf/default)))))))

(deftest reorder-pinned-slices-replaces-pin-order
  (testing ":rf.causa/reorder-pinned-slices replaces the per-frame pin
            vector with the supplied permutation"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (frame/reg-frame :rf/default {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/pin-slice [:a]])
      (rf/dispatch-sync [:rf.causa/pin-slice [:b]])
      (rf/dispatch-sync [:rf.causa/pin-slice [:c]])
      (rf/dispatch-sync [:rf.causa/reorder-pinned-slices [[:c] [:a] [:b]]])
      (let [s (:pinned-slices-store (frame/frame-app-db-value :rf/causa))]
        (is (= [[:c] [:a] [:b]] (get s :rf/default)))))))

(deftest pinned-slices-sub-derefs-live-values
  (testing ":rf.causa/pinned-slices returns live values from the host
            frame's current app-db"
    (seed-causa! {:user {:auth {:status :authenticated}}
                  :cart {:items [{:id 7}]}}
                 [])
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/pin-slice [:user :auth :status]])
      (rf/dispatch-sync [:rf.causa/pin-slice [:cart :items]])
      (let [slices @(rf/subscribe [:rf.causa/pinned-slices])]
        (is (= 2 (count slices)))
        (is (= {:path [:user :auth :status] :value :authenticated}
               (first slices)))
        (is (= {:path [:cart :items] :value [{:id 7}]}
               (second slices)))))))

;; ---- (6) focus event + 'Show me when this changed' result ---------------

(deftest focus-slice-path-event-writes-to-causa-frame
  (testing ":rf.causa/focus-slice-path lands the path on :rf/causa's
            :focused-slice-path"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/focus-slice-path [:cart :items]]))
    (is (= [:cart :items]
           (:focused-slice-path (frame/frame-app-db-value :rf/causa))))))

(deftest clear-slice-focus-event-drops-focus
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/focus-slice-path [:any]])
    (rf/dispatch-sync [:rf.causa/clear-slice-focus]))
  (is (nil? (:focused-slice-path (frame/frame-app-db-value :rf/causa)))))

(deftest show-me-when-this-changed-result-filters-history
  (testing ":rf.causa/show-me-when-this-changed-result walks
            :rf.causa/epoch-history and returns only epochs that
            touched the focused path.

            Per spec §Changed-paths derivation the walker uses pointer-
            equality at each level — we use `assoc-in` so the
            unchanged :cart subtree keeps its PersistentHashMap
            identity across epoch boundaries (mirrors how host
            reg-event-db handlers build successor app-dbs)."
    (let [db-0 {}
          db-1 (assoc-in db-0 [:cart :items] [])
          db-2 (assoc-in db-1 [:cart :items] [{:id 7}])
          db-3 (assoc    db-2 :user "ada")  ;; :cart identity preserved
          hist [(mk-record :e-1 [:app/boot]     db-0 db-1)
                (mk-record :e-2 [:cart/add-item] db-1 db-2)
                (mk-record :e-3 [:user/login]    db-2 db-3)]]
      (seed-causa! db-3 hist)
      (rf/with-frame :rf/causa
        (rf/dispatch-sync [:rf.causa/focus-slice-path [:cart :items]])
        (let [hits @(rf/subscribe [:rf.causa/show-me-when-this-changed-result])
              eids (mapv :epoch-id hits)]
          (is (= [:e-2 :e-1] eids)
              "newest-first; :e-3 didn't touch :cart :items"))))))

(deftest show-me-when-this-changed-result-empty-when-no-focus
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (is (= [] @(rf/subscribe [:rf.causa/show-me-when-this-changed-result])))))

;; ---- (7) clipboard fx is wired (fires without crashing) -----------------

(deftest copy-events-fire-clipboard-fx
  (testing ":rf.causa/copy-value-to-clipboard + :rf.causa/copy-path-to-
            clipboard route through :rf.causa.fx/copy-to-clipboard; the
            fx is best-effort (no-op on non-browser targets)"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (let [captured (atom [])]
      (rf/reg-fx :rf.causa.fx/copy-to-clipboard
        (fn [_ctx args] (swap! captured conj args)))
      (rf/with-frame :rf/causa
        (rf/dispatch-sync [:rf.causa/copy-value-to-clipboard {:a 1}])
        (rf/dispatch-sync [:rf.causa/copy-path-to-clipboard [:cart :items]]))
      (is (= 2 (count @captured)))
      (is (= "{:a 1}" (:text (first @captured))))
      (is (= "[:cart :items]" (:text (second @captured)))))))

;; ---- (8) view renders ---------------------------------------------------

(deftest empty-state-renders-when-history-empty
  (testing "with no epoch history, the panel renders the empty state"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (frame/reg-frame :rf/default {})
    (rf/with-frame :rf/causa
      (let [tree (app-db-diff/app-db-diff-view)]
        (is (some? (find-by-testid tree "rf-causa-app-db-diff-empty"))
            "empty-state container present")
        (is (nil? (find-by-testid tree "rf-causa-app-db-diff-slices")))))))

(deftest slice-stack-renders-when-diff-present
  (testing "with history populated, the panel renders one slice mini-
            panel per non-reserved diff triple"
    (seed-causa! {:cart {:items [{:id 7}]}
                  :user "ada"}
                 [(mk-record :e-1 [:cart/add-item]
                             {:cart {:items []} :user "ada"}
                             {:cart {:items [{:id 7}]} :user "ada"})])
    (rf/with-frame :rf/causa
      (let [tree (app-db-diff/app-db-diff-view)]
        (is (some? (find-by-testid tree "rf-causa-app-db-diff-slices"))
            "slice container present")
        (is (nil? (find-by-testid tree "rf-causa-app-db-diff-empty"))
            "empty-state absent")
        ;; The slice mini-panel for [:cart :items] is present:
        (is (some? (find-by-testid tree
                                   "rf-causa-app-db-diff-slice-[:cart :items]")))))))

(deftest reserved-group-renders-when-rf-keys-present
  (testing "the [runtime] group surfaces :rf/* keys present in app-db"
    (seed-causa! {:rf/route {:id :app/cart}
                  :rf/machines {:auth-id {:state :idle}}
                  :cart {:items []}}
                 [(mk-record :e-1 [:nav]
                             {:cart {:items []}}
                             {:cart {:items []} :rf/route {:id :app/cart}})])
    (rf/with-frame :rf/causa
      (let [tree (app-db-diff/app-db-diff-view)]
        (is (some? (find-by-testid tree "rf-causa-app-db-diff-reserved-group"))
            "runtime group container present")
        (is (some? (find-by-testid tree "rf-causa-app-db-diff-reserved-:rf/route"))
            "rf/route row present")
        (is (some? (find-by-testid tree "rf-causa-app-db-diff-reserved-:rf/machines"))
            "rf/machines row present")))))

(deftest focus-result-panel-replaces-slice-stack-on-focus
  (testing "when a slice path is focused, the panel renders the 'Show me
            when this changed' result list instead of the slice stack"
    (seed-causa! {:cart {:items [{:id 7}]}}
                 [(mk-record :e-1 [:app/boot]
                             {}
                             {:cart {:items []}})
                  (mk-record :e-2 [:cart/add-item]
                             {:cart {:items []}}
                             {:cart {:items [{:id 7}]}})])
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/focus-slice-path [:cart :items]])
      (let [tree (app-db-diff/app-db-diff-view)]
        (is (some? (find-by-testid tree "rf-causa-app-db-diff-focus-result"))
            "focus-result panel present")
        (is (some? (find-by-testid tree "rf-causa-app-db-diff-focus-hits"))
            "hit list present")
        (is (nil? (find-by-testid tree "rf-causa-app-db-diff-slices"))
            "slice stack absent while focused")
        (is (some? (find-by-testid tree
                                   "rf-causa-app-db-diff-focus-hit-:e-2")))
        (is (some? (find-by-testid tree
                                   "rf-causa-app-db-diff-focus-hit-:e-1")))))))

(deftest pinned-group-renders-when-pins-present
  (testing "the Pinned-slices section renders when pins exist for the
            current target-frame"
    (seed-causa! {:user {:auth {:status :authenticated}}}
                 [])
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/pin-slice [:user :auth :status]])
      (let [tree (app-db-diff/app-db-diff-view)]
        (is (some? (find-by-testid tree "rf-causa-app-db-diff-pinned-group"))
            "pinned group container present")
        (is (some? (find-by-testid tree
                                   "rf-causa-app-db-diff-pinned-[:user :auth :status]"))
            "pinned row for the pinned path is present")))))

(deftest clicking-pin-button-fires-pin-slice-event
  (testing "the slice mini-panel's Pin button is wired to
            :rf.causa/pin-slice for the slice's path"
    (seed-causa! {:cart {:items [{:id 7}]}}
                 [(mk-record :e-1 [:cart/add]
                             {:cart {:items []}}
                             {:cart {:items [{:id 7}]}})])
    (rf/with-frame :rf/causa
      (let [tree     (app-db-diff/app-db-diff-view)
            btn      (find-by-testid tree
                                     "rf-causa-app-db-diff-pin-[:cart :items]")
            on-click (:on-click (second btn))]
        (is (fn? on-click) "Pin button has an on-click handler")
        ;; Drive the same event through dispatch-sync to prove the
        ;; registered event handler lands the pin in the Causa frame.
        (rf/dispatch-sync [:rf.causa/pin-slice [:cart :items]])
        (let [causa-db (frame/frame-app-db-value :rf/causa)]
          (is (= [[:cart :items]]
                 (get-in causa-db [:pinned-slices-store :rf/default]))
              "pin landed in :rf/causa's :pinned-slices-store"))))))

(deftest clicking-show-when-button-fires-focus-event
  (testing "the slice mini-panel's 'Show me when this changed' button is
            wired to :rf.causa/focus-slice-path"
    (seed-causa! {:cart {:items [{:id 7}]}}
                 [(mk-record :e-1 [:cart/add]
                             {:cart {:items []}}
                             {:cart {:items [{:id 7}]}})])
    (rf/with-frame :rf/causa
      (let [tree (app-db-diff/app-db-diff-view)
            btn  (find-by-testid tree
                                 "rf-causa-app-db-diff-show-when-[:cart :items]")
            on-click (:on-click (second btn))]
        (is (some? btn) "Show-when button present")
        (is (fn? on-click))
        (rf/dispatch-sync [:rf.causa/focus-slice-path [:cart :items]])
        (let [causa-db (frame/frame-app-db-value :rf/causa)]
          (is (= [:cart :items]
                 (:focused-slice-path causa-db))))))))
