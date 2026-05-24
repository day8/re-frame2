(ns day8.re-frame2-xray.panels.app-db-diff-cljs-test
  "CLJS-side wiring + view tests for Xray's app-db tab.

  ## rf2-okvit — current-state inspector

  The app-db tab was redesigned from a diff view into a CURRENT-STATE
  inspector (re-frame-10x style), sectioned by reserved `:rf/*` area.
  The view-render tests assert the inspector shape (TOP user-domain
  section, per-instance machine fan-out, singleton route, empty-state
  for absent areas) via `app-db-diff-state/state-body` through the
  Panel. The diff data subs (`:rf.xray/selected-epoch-diff` and the
  composite `:rf.xray/app-db-diff`) survive for the Event tab diff
  surface + the redacted-modified count; their sub-level contracts are
  still pinned here.

  ## Contracts under test (beyond the pure-data tests in
  `app_db_diff_helpers_cljs_test.cljc` / the view-shape tests in
  `app_db_diff_state_cljs_test.cljs`)

  1. **Registry wires the subs / events** under the `:rf.xray/*`
     namespace, including the new `:rf.xray/app-db-state` sub.

  2. **Focus events update Xray's frame.** `:rf.xray/focus-slice-path`
     remains wired (the Event tab diff renderer dispatches it).

  3. **Diff data subs** still produce correct triples / reserved-key
     segregation / redacted-modified counts (sub-level).

  4. **Current-state inspector view** renders the section model and
     follows the picker-selected frame.

  ## Pure hiccup

  We walk the view's hiccup tree by `data-testid` rather than mounting
  to a DOM. Keeps the suite fast + host-portable on node-test."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-bus :as trace-bus]
            [day8.re-frame2-xray.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-xray.panels.app-db-diff-subs
             :as app-db-diff-subs]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  ;; The `:rf.xray/selected-epoch-diff` cache is a top-level
  ;; `defonce` (lifted from let-local for rf2-o94sp testability).
  ;; Reset between tests so
  ;; cache-size assertions are reproducible across the corpus.
  (reset! app-db-diff-subs/diff-cache {})
  ;; rf2-bz1cl — redacted-paths-modified cache mirrors the same
  ;; per-`:epoch-id` caching contract; reset between tests for
  ;; reproducibility.
  (reset! app-db-diff-subs/redacted-modified-cache {})
  ;; rf2-s8r6c — flow-writes cache for the per-section origin-tag
  ;; chip projection. Reset between tests for the same reason.
  (reset! app-db-diff-subs/flow-writes-cache {}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

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
  "Register test-only seed events that write directly to the Xray
  frame's app-db. Production code reaches the same shape via
  :rf.xray/epoch-recorded + the host frame's runtime mutations."
  []
  (rf/reg-event-db :rf.xray-test/seed-history
    (fn [db [_ records]]
      (assoc db :epoch-history (vec records))))
  (rf/reg-event-db :rf.xray-test/seed-target-frame-db
    (fn [db _]
      ;; This is a no-op marker — the host frame's db is the source
      ;; of truth, and we set it via reset-frame-db! below. Kept so
      ;; tests can locate the seed step by name.
      db)))

(defn- seed-host-frame!
  "Reset the host (:rf/default) frame's app-db to the supplied
  value via the framework's reset-frame-db!. The Phase 5 panel
  derefs the host frame via :rf.xray/target-frame-db."
  [db-value]
  (frame/reg-frame :rf/default {})
  (rf/reset-frame-db! :rf/default db-value))

(defn- seed-xray!
  "Wire the Phase 5 sub graph + seed history + host-frame db. The
  test environment proxies the production wiring path (preload +
  registry + epoch-cb) so the subs read live values."
  [host-db-value history]
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {})
  (register-seed-events!)
  (seed-host-frame! host-db-value)
  (when (seq history)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray-test/seed-history history]))))

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
  (testing "register-xray-handlers! installs the Phase 5 subs.
            Pinned-slices subs were removed under rf2-e9tb0 (clickable
            path segments replaced the pinned-watches strip)."
    (registry/register-xray-handlers!)
    (is (some? (registrar/handler :sub :rf.xray/target-frame-db)))
    (is (some? (registrar/handler :sub :rf.xray/selected-epoch-record)))
    (is (some? (registrar/handler :sub :rf.xray/selected-epoch-diff)))
    (is (some? (registrar/handler :sub :rf.xray/focused-slice-path)))
    (is (some? (registrar/handler :sub :rf.xray/show-me-when-this-changed-result)))
    (is (some? (registrar/handler :sub :rf.xray/app-db-diff)))
    ;; rf2-okvit — the current-state inspector's section-model sub.
    (is (some? (registrar/handler :sub :rf.xray/app-db-state)))
    ;; rf2-e9tb0 — segment-inspector subs registered alongside.
    (is (some? (registrar/handler :sub :rf.xray/segment-inspector-open?)))
    (is (some? (registrar/handler :sub :rf.xray/segment-inspector-path)))
    (is (some? (registrar/handler :sub :rf.xray/segment-inspector-value)))
    ;; Pinned-slices subs are gone.
    (is (nil? (registrar/handler :sub :rf.xray/pinned-slices-store)))
    (is (nil? (registrar/handler :sub :rf.xray/pinned-slices)))))

(deftest registry-installs-app-db-diff-events
  (testing "register-xray-handlers! installs the Phase 5 events.
            Pin / unpin / reorder events were removed under rf2-e9tb0;
            the segment-inspector open / close events landed in their
            place."
    (registry/register-xray-handlers!)
    (is (some? (registrar/handler :event :rf.xray/focus-slice-path)))
    (is (some? (registrar/handler :event :rf.xray/clear-slice-focus)))
    (is (some? (registrar/handler :event :rf.xray/copy-value-to-clipboard)))
    (is (some? (registrar/handler :event :rf.xray/copy-path-to-clipboard)))
    ;; rf2-e9tb0 — segment-inspector events registered.
    (is (some? (registrar/handler :event :rf.xray/open-segment-inspector)))
    (is (some? (registrar/handler :event :rf.xray/close-segment-inspector)))
    ;; Pin events are gone.
    (is (nil? (registrar/handler :event :rf.xray/pin-slice)))
    (is (nil? (registrar/handler :event :rf.xray/unpin-slice)))
    (is (nil? (registrar/handler :event :rf.xray/reorder-pinned-slices)))))

(deftest registry-installs-clipboard-fx
  (testing "register-xray-handlers! installs the :rf.xray.fx/copy-to-
            clipboard effect"
    (registry/register-xray-handlers!)
    (is (some? (registrar/handler :fx :rf.xray.fx/copy-to-clipboard)))))

;; ---- (2) composite sub returns sane defaults ----------------------------

(deftest app-db-diff-sub-defaults
  (testing ":rf.xray/app-db-diff returns sane defaults on a fresh frame.
            rf2-e9tb0 — `:pinned-slices` was dropped from the composite
            when the pinned-watches strip was replaced by the segment-
            inspector popup."
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (frame/reg-frame :rf/default {})
    (rf/with-frame :rf/xray
      (let [data @(rf/subscribe [:rf.xray/app-db-diff])]
        (is (= :rf/default (:target-frame data)))
        (is (true? (:history-empty? data)))
        (is (nil? (:focused-path data)))
        (is (= [] (:focused-hits data)))
        (is (not (contains? data :pinned-slices))
            "the composite no longer carries :pinned-slices")
        ;; rf2-bz1cl — chip count defaults to 0 (no history → no
        ;; redacted-modified paths anywhere).
        (is (= 0 (:redacted-modified-count data)))))))

;; ---- (3) selected-epoch-diff produces correct triples -------------------

(deftest selected-epoch-diff-produces-triples
  (testing "with history populated, :rf.xray/selected-epoch-diff
            produces the [op path before after] triples"
    (seed-xray! {:cart {:items [{:id 7}]}}
                 [(mk-record :e-1 [:cart/add-item]
                             {:cart {:items []}}
                             {:cart {:items [{:id 7}]}})])
    (rf/with-frame :rf/xray
      (let [diff @(rf/subscribe [:rf.xray/selected-epoch-diff])]
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
      (seed-xray! {:counter 2} hist)
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/select-epoch :e-2])
        (let [diff @(rf/subscribe [:rf.xray/selected-epoch-diff])]
          (is (= [{:op :modified :path [:counter] :before 0 :after 1}]
                 diff)
              ":e-2's diff selected, not :e-3's"))))))

(deftest selected-epoch-diff-falls-back-to-latest-when-selection-stale
  (testing "rf2-drf32 — a stale `:selected-epoch-id` (no record in
            current history) falls back to (peek history) so the diff
            panel never gets stranded on an aged-out selection. This
            is the actual bug Mike's testbed UX hunt surfaced: clicking
            a mutate button produced no visible diff because a prior
            time-travel selection was no longer in history and the sub
            returned nil instead of the latest record's diff."
    (let [hist [(mk-record :e-99 [:counter/inc]
                           {:counter 41} {:counter 42})]]
      (seed-xray! {:counter 42} hist)
      (rf/with-frame :rf/xray
        ;; Set a selection that DOESN'T exist in history.
        (rf/dispatch-sync [:rf.xray/select-epoch :stale-id-that-aged-out])
        (let [diff @(rf/subscribe [:rf.xray/selected-epoch-diff])]
          (is (= [{:op :modified :path [:counter] :before 41 :after 42}]
                 diff)
              "stale selection falls through to (peek history) — the
               newest record's diff is shown rather than nil"))))))

;; ---- (3b) per-:epoch-id diff cache (rf2-qvaa0) --------------------------

(deftest selected-epoch-diff-cache-hits-on-repeat-deref
  (testing "the diff is computed once per :epoch-id — second deref of
            the same selection returns identical?-equal triples (the
            cache short-circuit, not just =-equal)"
    (let [hist [(mk-record :e-1 [:counter/inc]
                           {:counter 0} {:counter 1})]]
      (seed-xray! {:counter 1} hist)
      (rf/with-frame :rf/xray
        (let [first-call  @(rf/subscribe [:rf.xray/selected-epoch-diff])
              second-call @(rf/subscribe [:rf.xray/selected-epoch-diff])]
          (is (= first-call second-call))
          (is (identical? first-call second-call)
              "second deref returns the cached object — no recompute"))))))

(deftest selected-epoch-diff-cache-evicts-aged-out-epochs
  (testing "epoch-ids no longer in the history get pruned from the
            cache — the cache cannot grow past the history depth"
    (let [hist-a [(mk-record :e-1 [:a] {} {:n 1})]
          hist-b [(mk-record :e-2 [:b] {} {:n 2})
                  (mk-record :e-3 [:c] {:n 2} {:n 3})]]
      (seed-xray! {:n 1} hist-a)
      (rf/with-frame :rf/xray
        ;; Warm the cache with :e-1's diff.
        @(rf/subscribe [:rf.xray/selected-epoch-diff])
        ;; Rotate the history — :e-1 ages out, :e-2/:e-3 take its place.
        (rf/dispatch-sync [:rf.xray-test/seed-history hist-b])
        ;; Read the new newest-epoch diff. The cache must prune :e-1
        ;; (no longer in history) on this read.
        (let [diff @(rf/subscribe [:rf.xray/selected-epoch-diff])]
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
      (seed-xray! {:n 4} hist-a)
      (rf/with-frame :rf/xray
        (doseq [eid [:e-1 :e-2 :e-3 :e-4]]
          (rf/dispatch-sync [:rf.xray/select-epoch eid])
          @(rf/subscribe [:rf.xray/selected-epoch-diff]))
        (is (= 4 (count @app-db-diff-subs/diff-cache))
            "after warming all four selections, cache holds four entries")
        (is (= #{:e-1 :e-2 :e-3 :e-4}
               (set (keys @app-db-diff-subs/diff-cache)))
            "cache keys match the live history's epoch-ids exactly"))
      ;; Rotate to a 2-epoch history; :e-1/:e-2 age out.
      (let [hist-b [(mk-record :e-5 [:e] {:n 4} {:n 5})
                    (mk-record :e-6 [:f] {:n 5} {:n 6})]]
        (rf/with-frame :rf/xray
          (rf/dispatch-sync [:rf.xray-test/seed-history hist-b])
          ;; Read newest diff — triggers the prune-on-read step.
          (rf/dispatch-sync [:rf.xray/select-epoch nil])
          @(rf/subscribe [:rf.xray/selected-epoch-diff])
          (is (<= (count @app-db-diff-subs/diff-cache) 2)
              "after rotation + one read, cache size ≤ live history depth")
          (is (not (contains? @app-db-diff-subs/diff-cache :e-1))
              ":e-1 (aged out) is no longer in the cache")
          (is (not (contains? @app-db-diff-subs/diff-cache :e-2))
              ":e-2 (aged out) is no longer in the cache")
          (is (not (contains? @app-db-diff-subs/diff-cache :e-3))
              ":e-3 (aged out) is no longer in the cache")
          (is (not (contains? @app-db-diff-subs/diff-cache :e-4))
              ":e-4 (aged out) is no longer in the cache")
          (is (contains? @app-db-diff-subs/diff-cache :e-6)
              ":e-6 (just read) is in the cache"))))))

(deftest selected-epoch-diff-cache-distinguishes-distinct-epochs
  (testing "different :epoch-id selections each get their own cached diff"
    (let [hist [(mk-record :e-1 [:a] {} {:counter 0})
                (mk-record :e-2 [:counter/inc]
                           {:counter 0} {:counter 1})
                (mk-record :e-3 [:counter/inc]
                           {:counter 1} {:counter 2})]]
      (seed-xray! {:counter 2} hist)
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/select-epoch :e-2])
        (let [diff-e2 @(rf/subscribe [:rf.xray/selected-epoch-diff])]
          (rf/dispatch-sync [:rf.xray/select-epoch :e-3])
          (let [diff-e3 @(rf/subscribe [:rf.xray/selected-epoch-diff])]
            (is (not= diff-e2 diff-e3)
                "different selections produce different diffs")
            (is (= [{:op :modified :path [:counter] :before 0 :after 1}]
                   diff-e2))
            (is (= [{:op :modified :path [:counter] :before 1 :after 2}]
                   diff-e3))))
        ;; Returning to :e-2 still gets its cached triples (identical?
        ;; to the first read — proves the cache survives the cross-
        ;; selection roundtrip).
        (rf/dispatch-sync [:rf.xray/select-epoch :e-2])
        (let [diff-e2-second @(rf/subscribe [:rf.xray/selected-epoch-diff])
              diff-e2-third  @(rf/subscribe [:rf.xray/selected-epoch-diff])]
          (is (identical? diff-e2-second diff-e2-third)
              "the cache survives selection changes"))))))

;; ---- (4) reserved-keys segregation at the composite level ---------------

(deftest app-db-diff-segregates-reserved-keys
  (testing "the composite splits triples whose path roots in :rf/* from
            the rest; reserved triples go to :changed-reserved as the
            current snapshot (informational group per spec §Reserved-
            keys group), non-reserved go to :changed-non-reserved"
    (seed-xray! {:cart {:items [{:id 7}]}
                  :rf/route {:id :app/cart}
                  :rf/machines {:auth {:state :idle}}}
                 [(mk-record :e-1 [:nav/cart]
                             {:cart {:items []}}
                             {:cart {:items [{:id 7}]}
                              :rf/route {:id :app/cart}})])
    (rf/with-frame :rf/xray
      (let [data @(rf/subscribe [:rf.xray/app-db-diff])
            non-reserved-paths (set (map :path (:changed-non-reserved data)))
            reserved-keys-shown (set (map first (:changed-reserved data)))]
        (is (contains? non-reserved-paths [:cart :items])
            "non-reserved path lands in :changed-non-reserved")
        (is (not (contains? non-reserved-paths [:rf/route]))
            ":rf/route filtered out of :changed-non-reserved")
        (is (contains? reserved-keys-shown :rf/route))
        (is (contains? reserved-keys-shown :rf/machines))))))

;; ---- (5) rf2-e9tb0 — segment-inspector events + state ------------------

(deftest open-segment-inspector-writes-path-to-xray-frame
  (testing "rf2-e9tb0 — :rf.xray/open-segment-inspector writes the
            requested path into Xray's frame at :segment-inspector.
            The path is normalised to a vector so callers can pass
            seqs / lists / vectors interchangeably."
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (frame/reg-frame :rf/default {})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector
                         (list :cart :items 0 :price)])
      (is (= {:path [:cart :items 0 :price]}
             (:segment-inspector (frame/frame-app-db-value :rf/xray)))))))

(deftest close-segment-inspector-drops-slot
  (testing "rf2-e9tb0 — :rf.xray/close-segment-inspector dissocs the
            slot so the popup reg-view's `when`-gate short-circuits"
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:cart]])
      (is (some? (:segment-inspector (frame/frame-app-db-value :rf/xray))))
      (rf/dispatch-sync [:rf.xray/close-segment-inspector])
      (is (nil? (:segment-inspector (frame/frame-app-db-value :rf/xray)))))))

(deftest segment-inspector-open?-tracks-slot-presence
  (testing "rf2-e9tb0 — :rf.xray/segment-inspector-open? is true iff
            the :segment-inspector slot is non-nil"
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (is (false? @(rf/subscribe [:rf.xray/segment-inspector-open?])))
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:cart :items]])
      (is (true? @(rf/subscribe [:rf.xray/segment-inspector-open?])))
      (rf/dispatch-sync [:rf.xray/close-segment-inspector])
      (is (false? @(rf/subscribe [:rf.xray/segment-inspector-open?]))))))

(deftest segment-inspector-value-resolves-against-target-frame-db
  (testing "rf2-e9tb0 — :rf.xray/segment-inspector-value reads through
            :rf.xray/target-frame-db with `get-in` so the popup always
            shows the picker-selected frame's current value at the
            inspected path. Empty path yields the whole db (root
            inspection)."
    (seed-xray! {:cart {:items [{:id 7 :qty 1}]
                         :gross 42}
                  :user "ada"}
                 [])
    (rf/with-frame :rf/xray
      ;; Leaf at [:cart :gross]
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:cart :gross]])
      (is (= 42 @(rf/subscribe [:rf.xray/segment-inspector-value])))
      ;; Sub-map at [:cart]
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:cart]])
      (is (= {:items [{:id 7 :qty 1}] :gross 42}
             @(rf/subscribe [:rf.xray/segment-inspector-value])))
      ;; Root: empty path → whole db.
      (rf/dispatch-sync [:rf.xray/open-segment-inspector []])
      (is (= {:cart {:items [{:id 7 :qty 1}] :gross 42}
              :user "ada"}
             @(rf/subscribe [:rf.xray/segment-inspector-value]))))))

;; ---- (6) focus event + 'Show me when this changed' result ---------------

(deftest focus-slice-path-event-writes-to-xray-frame
  (testing ":rf.xray/focus-slice-path lands the path on :rf/xray's
            :focused-slice-path"
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-slice-path [:cart :items]]))
    (is (= [:cart :items]
           (:focused-slice-path (frame/frame-app-db-value :rf/xray))))))

(deftest clear-slice-focus-event-drops-focus
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {})
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-slice-path [:any]])
    (rf/dispatch-sync [:rf.xray/clear-slice-focus]))
  (is (nil? (:focused-slice-path (frame/frame-app-db-value :rf/xray)))))

(deftest show-me-when-this-changed-result-filters-history
  (testing ":rf.xray/show-me-when-this-changed-result walks
            :rf.xray/epoch-history and returns only epochs that
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
      (seed-xray! db-3 hist)
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/focus-slice-path [:cart :items]])
        (let [hits @(rf/subscribe [:rf.xray/show-me-when-this-changed-result])
              eids (mapv :epoch-id hits)]
          (is (= [:e-2 :e-1] eids)
              "newest-first; :e-3 didn't touch :cart :items"))))))

(deftest show-me-when-this-changed-result-empty-when-no-focus
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {})
  (rf/with-frame :rf/xray
    (is (= [] @(rf/subscribe [:rf.xray/show-me-when-this-changed-result])))))

;; ---- (7) clipboard fx is wired (fires without crashing) -----------------

(deftest copy-events-fire-clipboard-fx
  (testing ":rf.xray/copy-value-to-clipboard + :rf.xray/copy-path-to-
            clipboard route through :rf.xray.fx/copy-to-clipboard; the
            fx is best-effort (no-op on non-browser targets)"
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (let [captured (atom [])]
      (rf/reg-fx :rf.xray.fx/copy-to-clipboard
        (fn [_ctx args] (swap! captured conj args)))
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/copy-value-to-clipboard {:a 1}])
        (rf/dispatch-sync [:rf.xray/copy-path-to-clipboard [:cart :items]]))
      (is (= 2 (count @captured)))
      (is (= "{:a 1}" (:text (first @captured))))
      (is (= "[:cart :items]" (:text (second @captured)))))))

;; ---- (8) view renders — current-state inspector (rf2-okvit) -------------
;;
;; The app-db tab is a CURRENT-STATE inspector, not a diff. The Panel
;; renders `app-db-diff-state/state-body` over the observed frame's live
;; app-db: a TOP user-domain section + one section per reserved `:rf/*`
;; area (machines/spawned fan out per id; route + slices are singletons;
;; absent/empty areas render an empty-state). The diff / focus-result /
;; redacted-chip view affordances were dropped here (rf2-okvit) — their
;; data subs survive and are exercised at the sub level above.

(deftest panel-renders-current-state-container
  (testing "the Panel renders the current-state inspector container +
            the TOP user-domain section over the observed frame's db"
    (seed-host-frame! {:counter 5 :user {:name "ada"}})
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [tree (app-db-diff/Panel)]
        (is (some? (find-by-testid tree "rf-xray-app-db-diff"))
            "panel root present")
        (is (some? (find-by-testid tree "rf-xray-app-db-state"))
            "current-state inspector body present")
        (is (some? (find-by-testid tree "rf-xray-app-db-state-top"))
            "TOP user-domain section present")
        ;; rf2-okvit — no diff machinery on this view.
        (is (nil? (find-by-testid tree "rf-xray-diff-sections"))
            "no diff sections")
        (is (nil? (find-by-testid tree "rf-xray-app-db-diff-slices"))
            "no slice stack")))))

(deftest panel-sections-reserved-areas
  (testing "reserved :rf/* areas render as their own sections: machines
            fan out one section per machine id; route is a singleton"
    (seed-host-frame! {:cart {:items [{:id 7}]}
                       :rf/route {:id :app/cart}
                       :rf/machines {:auth {:state :idle}
                                     :title/flow {:state :playing}}})
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [tree (app-db-diff/Panel)]
        ;; machines fan out one section per id (title = machine id).
        (is (some? (find-by-testid
                     tree "rf-xray-app-db-state-instance-:rf/machines-:auth"))
            "machine :auth has its own section")
        (is (some? (find-by-testid
                     tree "rf-xray-app-db-state-instance-:rf/machines-:title/flow"))
            "machine :title/flow has its own section")
        ;; route is a singleton section.
        (is (some? (find-by-testid tree "rf-xray-app-db-state-area-:rf/route"))
            "route singleton section present")))))

(deftest panel-renders-empty-reserved-areas
  (testing "absent reserved areas still render (empty-state), not omitted
            — the full :rf/* inventory is always visible"
    (seed-host-frame! {:counter 1})
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [tree (app-db-diff/Panel)]
        (doseq [area [:rf/machines :rf/spawned :rf/route :rf/system-ids
                      :rf/pending-navigation :rf/elision]]
          (is (some? (find-by-testid
                       tree (str "rf-xray-app-db-state-area-" (pr-str area))))
              (str "empty-state section present for " area)))))))

(deftest focus-slice-path-event-still-wired
  (testing "the :rf.xray/focus-slice-path event remains registered. UI
            affordance moved off the slice mini-panel — same follow-on
            as the pin button above."
    (seed-xray! {:cart {:items [{:id 7}]}}
                 [(mk-record :e-1 [:cart/add]
                             {:cart {:items []}}
                             {:cart {:items [{:id 7}]}})])
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-slice-path [:cart :items]])
      (let [xray-db (frame/frame-app-db-value :rf/xray)]
        (is (= [:cart :items]
               (:focused-slice-path xray-db)))))))

;; ---- rf2-fvplw — App-db panel follows picker / focused frame -----------

(deftest observed-frame-follows-rf-xray-set-frame
  (testing "rf2-fvplw — the frame-picker dispatches `:rf.xray/set-frame`
            which writes `[:focus :frame]`. The App-db panel's
            `:rf.xray/observed-frame` sub picks the focused frame up,
            and the composite's `:target-frame` surface reflects it.
            Pre-fix the panel read the legacy `:rf.xray/target-frame`
            slot (which `:rf.xray/set-frame` does NOT touch) and stayed
            stuck on `:rf/default` regardless of picker selection."
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (frame/reg-frame :rf/default {})
    (frame/reg-frame :checkout-frame {})
    (rf/with-frame :rf/xray
      ;; Cold-start sanity — observed frame is the default before any
      ;; picker selection lands.
      (is (= :rf/default @(rf/subscribe [:rf.xray/observed-frame])))
      (is (= :rf/default (:target-frame @(rf/subscribe [:rf.xray/app-db-diff]))))
      ;; User picks :checkout-frame in the ribbon dropdown.
      (rf/dispatch-sync [:rf.xray/set-frame :checkout-frame])
      (is (= :checkout-frame @(rf/subscribe [:rf.xray/observed-frame])))
      (is (= :checkout-frame
             (:target-frame @(rf/subscribe [:rf.xray/app-db-diff])))
          "composite's :target-frame surface follows the picker"))))

(deftest current-state-view-follows-picker-selected-frame
  (testing "rf2-okvit / rf2-fvplw — the current-state inspector reads the
            observed (picker-selected) frame's live app-db via
            `:rf.xray/target-frame-db`. Picking `:checkout-frame`
            surfaces THAT frame's value in the TOP section, not the
            `:rf/default` frame's."
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (frame/reg-frame :rf/default {})
    (frame/reg-frame :checkout-frame {})
    ;; Give the two frames distinguishable values.
    (rf/reset-frame-db! :rf/default {:counter 0})
    (rf/reset-frame-db! :checkout-frame {:checkout {:step :payment}})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-frame :checkout-frame])
      (let [tree (app-db-diff/Panel)
            top  (find-by-testid tree "rf-xray-app-db-state-top")]
        (is (some? top) "TOP section present")
        (is (= :checkout-frame @(rf/subscribe [:rf.xray/observed-frame]))
            "inspector observes the picker-selected frame")
        (is (= {:checkout {:step :payment}}
               @(rf/subscribe [:rf.xray/target-frame-db]))
            "the observed db is the picked frame's live value")))))

;; ---- rf2-ug1r6 — picker change resets epoch-history slot ----------------
;;
;; rf2-fvplw wired `:rf.xray/observed-frame` + `:rf.xray/target-frame-
;; db` to follow the picker, but the App-DB Diff panel's
;; selected-epoch-* sub chain (the diff triples + annotated tree +
;; sections) read off `:rf.xray/epoch-history` — a Xray-side slot
;; keyed on the legacy `:target-frame` axis the picker did NOT touch.
;; After a picker change the slot stayed on the previous (likely
;; empty `:rf/default`) frame's history, so:
;;
;;   :rf.xray/selected-epoch-diff → (peek <empty>) → nil → composite's
;;   :history-empty? → true → the panel rendered the boot empty-state
;;   "app-db for :cart-frame is at the boot value. No diffs yet."
;;   EVEN WITH a focused cascade in the picked frame (the Mike report).
;;
;; The fix in `spine/set-frame-reducer` aligns the two axes — picker
;; now also writes `:target-frame` and re-seeds `:epoch-history` from
;; the framework's per-frame ring. This regression guard asserts the
;; alignment.

(deftest set-frame-aligns-target-frame-and-resets-epoch-history
  (testing "rf2-ug1r6 — picker writes `:target-frame` + clears the
            `:epoch-history` slot so future `:rf.xray/epoch-recorded`
            callbacks pump the picked frame's epochs into the right
            slot. Pre-fix the slot stayed keyed to the legacy
            target frame and the App-DB diff panel rendered empty-
            state with a focused cascade present."
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (frame/reg-frame :rf/default {})
    (frame/reg-frame :cart-frame {})
    (rf/with-frame :rf/xray
      ;; Seed the slot with stale `:rf/default` history; user picks
      ;; `:cart-frame`; the slot MUST reset so the wrong-frame epochs
      ;; do not bleed into the picked-frame panel.
      (rf/dispatch-sync [:rf.xray/sync-epoch-history
                         [(mk-record :stale-e [:default/event] {} {})]])
      (is (= 1 (count @(rf/subscribe [:rf.xray/epoch-history])))
          "stale slot present pre-picker")
      (rf/dispatch-sync [:rf.xray/set-frame :cart-frame])
      (is (= :cart-frame @(rf/subscribe [:rf.xray/target-frame]))
          ":target-frame follows picker so :rf.xray/epoch-recorded
           delivers future cart-frame epochs into the slot")
      (is (= [] @(rf/subscribe [:rf.xray/epoch-history]))
          "stale `:rf/default` history MUST clear so the wrong-frame
           records do not leak into the picked-frame panel"))))

(deftest app-db-diff-renders-cart-frame-diff-after-picker-and-seed
  (testing "rf2-ug1r6 — post-picker-change + post-history-reseed, the
            App-DB Diff composite reports the cart-frame's diff (not
            the boot empty-state). This is the structural inverse of
            Mike's bug report: with the alignment in place, focused
            cascades in the picked frame produce a real diff."
    (registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (frame/reg-frame :rf/default {})
    (frame/reg-frame :cart-frame {})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-frame :cart-frame])
      ;; Framework's `:rf.xray/epoch-recorded` cb re-pumps the
      ;; cart-frame's ring into the slot; tests drive the wholesale
      ;; overwrite directly.
      (let [rec (-> (mk-record :cart-e [:cart/add]
                               {:cart {:items []}}
                               {:cart {:items [{:id 7}]}})
                    (assoc :frame :cart-frame))]
        (rf/dispatch-sync [:rf.xray/sync-epoch-history [rec]]))
      (let [data @(rf/subscribe [:rf.xray/app-db-diff])]
        (is (false? (:history-empty? data))
            "history present for the picked frame → composite's
             :history-empty? is false → panel renders the diff body
             rather than the boot empty-state (rf2-ug1r6)")
        (is (= :cart-frame (:target-frame data))
            "composite's :target-frame surface reflects the picker
             selection (rf2-fvplw — preserved post-rf2-ug1r6)")))))

;; ---- rf2-bz1cl — redacted-paths-modified hint chip ----------------------
;;
;; Per spec/015-Data-Classification.md the elision contract substitutes
;; the `:rf/redacted` sentinel at sensitive paths. An app-supplied
;; epoch `:redact-fn` (per spec/Security.md §Epoch privacy posture) can
;; substitute the sentinel into `:db-before` / `:db-after` directly.
;; When that happens the structural diff sees `:rf/redacted` on both
;; sides → no row → empty diff body → developer can't tell anything
;; changed.
;;
;; The chip closes that gap. These tests pin the surface end-to-end:
;; composite count slot + view render.

(deftest redacted-modified-count-zero-when-no-redacted-history
  (testing "the composite's `:redacted-modified-count` is 0 when no
            cascade involved a redacted leaf"
    (seed-xray! {:counter 1}
                 [(mk-record :e-1 [:counter/inc] {:counter 0} {:counter 1})])
    (rf/with-frame :rf/xray
      (let [data @(rf/subscribe [:rf.xray/app-db-diff])]
        (is (= 0 (:redacted-modified-count data)))))))

(deftest redacted-modified-count-non-zero-when-redact-fn-substituted
  (testing "when a `:redact-fn` substituted `:rf/redacted` into both
            `:db-before` and `:db-after` at the same path inside a
            mutated subtree, the count is non-zero — the chip will
            surface on the view"
    (let [before {:auth {:token :rf/redacted :method :jwt}}
          after  {:auth {:token :rf/redacted :method :session}}]
      (seed-xray! after [(mk-record :e-1 [:auth/rotate] before after)])
      (rf/with-frame :rf/xray
        (let [data @(rf/subscribe [:rf.xray/app-db-diff])]
          (is (= 1 (:redacted-modified-count data))
              "the :auth :token leaf is redacted both sides; the parent
               subtree mutated (sibling :method changed); chip count = 1"))))))

(deftest redacted-modified-count-cache-hits-on-repeat-deref
  (testing "the count is cached per `:epoch-id`; second deref returns
            the same integer (the cache short-circuit pattern mirrors
            `:selected-epoch-diff`)"
    (let [before {:auth {:token :rf/redacted}}
          after  {:auth {:token :rf/redacted :method :session}}]
      (seed-xray! after [(mk-record :e-1 [:auth/rotate] before after)])
      (rf/with-frame :rf/xray
        (let [first-call  @(rf/subscribe
                             [:rf.xray/selected-epoch-redacted-modified-count])
              second-call @(rf/subscribe
                             [:rf.xray/selected-epoch-redacted-modified-count])]
          (is (= 1 first-call))
          (is (= first-call second-call))
          (is (= 1 (count @app-db-diff-subs/redacted-modified-cache))
              "cache holds exactly one entry for the one live epoch"))))))

;; rf2-okvit — the redacted-modified-chip was a DIFF-view affordance and
;; was dropped from the current-state inspector. The redacted-modified
;; COUNT sub survives (exercised above + in the Event tab diff surface);
;; the chip's view-render tests went with the chip.

;; ============================================================================
;;  rf2-dl3gx — egress slot wins; heuristic is the absent-slot fallback
;; ============================================================================
;;
;; Per `tools/xray/spec/004-App-DB-Diff.md` §Count semantics: the
;; framework's `:rf.epoch/redacted-modified-paths-count` slot is the
;; preferred source. The Xray-side heuristic only fires when the
;; record lacks the slot (legacy snapshots, hand-rolled fixtures, hosts
;; with no schema layer).

(defn- mk-record-with-egress
  "`mk-record` plus the `:rf.epoch/redacted-modified-paths-count`
  egress slot — exercises the rf2-dl3gx preferred read path."
  [epoch-id event db-before db-after egress-count]
  (assoc (mk-record epoch-id event db-before db-after)
         :rf.epoch/redacted-modified-paths-count egress-count))

(deftest egress-slot-wins-over-heuristic
  (testing "when a record carries :rf.epoch/redacted-modified-paths-count,
            the sub reads that integer directly — the heuristic does not
            run. Pin this with a record whose egress count DISAGREES with
            what the heuristic would compute, so the test would fail if
            the sub picked the wrong source."
    ;; This record's :db-before / :db-after carry no :rf/redacted
    ;; sentinels at all — the heuristic would compute 0. The egress
    ;; slot reports 3 (e.g. the framework saw three schema-declared
    ;; sensitive paths mutate, the redact-fn replaced them with
    ;; non-sentinel app-supplied shapes, or the records here are a
    ;; pre-redact snapshot). The egress integer wins.
    (let [before {:counter 0}
          after  {:counter 1}
          record (mk-record-with-egress :e-1 [:counter/inc] before after 3)]
      (seed-xray! after [record])
      (rf/with-frame :rf/xray
        (let [n @(rf/subscribe
                   [:rf.xray/selected-epoch-redacted-modified-count])
              data @(rf/subscribe [:rf.xray/app-db-diff])]
          (is (= 3 n)
              "sub reads the egress slot verbatim — the heuristic would
               have produced 0 because no :rf/redacted sentinel is in
               the dbs")
          (is (= 3 (:redacted-modified-count data))
              "composite carries the egress figure too"))))))

(deftest egress-slot-zero-overrides-heuristic-positive
  (testing "the egress slot landing as 0 means the framework computed
            'no schema-declared sensitive path actually changed' —
            this overrides a heuristic that would otherwise count
            sentinels in the dbs (e.g. the redact-fn replaced an
            unchanged sensitive path with the sentinel anyway)."
    ;; Heuristic on these dbs would count 1 (:auth :token, both
    ;; sides sentinel, parent subtree differs). The framework's
    ;; exact count says 0 — :auth :token didn't actually change;
    ;; the sibling :method did. Egress wins.
    (let [before  {:auth {:token :rf/redacted :method :jwt}}
          after   {:auth {:token :rf/redacted :method :session}}
          record  (mk-record-with-egress :e-1 [:auth/rotate] before after 0)]
      (seed-xray! after [record])
      (rf/with-frame :rf/xray
        (let [n @(rf/subscribe
                   [:rf.xray/selected-epoch-redacted-modified-count])]
          (is (= 0 n)
              "sub reads the 0 — the egress slot is the source of truth
               when present, even when 0"))))))

(deftest heuristic-fires-when-egress-slot-absent
  (testing "records WITHOUT :rf.epoch/redacted-modified-paths-count
            fall back to the Xray-side heuristic. Pin the existing
            legacy-snapshot path with an mk-record (no egress slot)
            that the heuristic counts to 1."
    (let [before {:auth {:token :rf/redacted}}
          after  {:auth {:token :rf/redacted :method :session}}
          record (mk-record :e-1 [:auth/rotate] before after)]
      ;; Pre-condition: the test fixture record does NOT carry the
      ;; rf2-dl3gx slot — this exercises the fallback intentionally.
      (is (not (contains? record :rf.epoch/redacted-modified-paths-count))
          "mk-record produces records without the egress slot — covers
           the legacy-snapshot fallback path")
      (seed-xray! after [record])
      (rf/with-frame :rf/xray
        (let [n @(rf/subscribe
                   [:rf.xray/selected-epoch-redacted-modified-count])]
          (is (= 1 n)
              "heuristic kicked in — counts the :auth :token sentinel
               in a mutated subtree (sibling :method changed)"))))))

(deftest egress-slot-cached-by-epoch-id-like-heuristic
  (testing "the per-`:epoch-id` cache covers both sources — second
            deref of the same epoch returns the same integer without
            re-reading the slot (same short-circuit pattern the
            heuristic uses)."
    (let [record (mk-record-with-egress :e-1 [:auth/rotate]
                                        {:counter 0} {:counter 1} 5)]
      (seed-xray! {:counter 1} [record])
      (rf/with-frame :rf/xray
        (let [first-call  @(rf/subscribe
                             [:rf.xray/selected-epoch-redacted-modified-count])
              second-call @(rf/subscribe
                             [:rf.xray/selected-epoch-redacted-modified-count])]
          (is (= 5 first-call))
          (is (= first-call second-call))
          (is (= 1 (count @app-db-diff-subs/redacted-modified-cache))
              "cache holds exactly one entry — same shape as the
               heuristic path"))))))
