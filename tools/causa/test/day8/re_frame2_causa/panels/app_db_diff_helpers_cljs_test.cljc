(ns day8.re-frame2-causa.panels.app-db-diff-helpers-cljs-test
  "Pure-data tests for Causa's App-DB Diff panel helpers
  (Phase 5, rf2-jps1o).

  ## Why the `.cljc` + `_cljs_test` naming

  The file ends in `_cljs_test.cljc` so:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  Same dual-target pattern as `time_travel_helpers_cljs_test.cljc`.

  ## What's under test

  Each contract is asserted against the pure-data fns in
  `app-db-diff-helpers`; no Reagent, no DOM. View-side wiring is
  exercised in `app_db_diff_cljs_test.cljs` against the live Causa
  frame.

    1. **Diff algorithm produces correct `[op path before after]`
       triples** for `:added` / `:modified` / `:removed`. Mixed
       sub-tree changes produce the union of triples.

    2. **Pointer-equal subtrees short-circuit.** When `before` and
       `after` share an `identical?` sub-map, the recursive walker
       skips it entirely — assertable via an externally-mutated
       counter wired through a wrapper.

    3. **Reserved-keys segregation.** `partition-reserved` splits
       triples whose path roots in `:rf/machines` / `:rf/route` /
       etc. into a separate group.

    4. **`epochs-touching-path` walks the history.** Returns only
       epochs that touched the focused path, classified by op.

  ## rf2-e9tb0 — pin-store helpers dropped

  Pin-store tests (`pin-path`, `unpin-path`, `reorder-paths`,
  `slice-pins-for-frame`, `live-pinned-slices`) were removed when the
  pinned-watches strip was superseded by the segment-inspector popup.
  The helpers themselves are gone — the matching test deftests have
  been pulled in lockstep."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.app-db-diff-helpers :as h]))

;; ---- (1) diff algorithm produces correct triples ------------------------

(deftest diff-paths-empty-on-equal-maps
  (testing "diffing identical-value maps yields no triples"
    (is (= [] (h/diff-paths {:a 1} {:a 1})))
    (is (= [] (h/diff-paths {} {})))
    (let [m {:nested {:k 1}}]
      (is (= [] (h/diff-paths m m)) "identical? whole map → no triples"))))

(deftest diff-paths-added-key
  (testing "a key present in :after but not :before → :added triple"
    (let [diff (h/diff-paths {:a 1} {:a 1 :b 2})]
      (is (= 1 (count diff)))
      (is (= {:op :added :path [:b] :before nil :after 2}
             (first diff))))))

(deftest diff-paths-removed-key
  (testing "a key present in :before but not :after → :removed triple"
    (let [diff (h/diff-paths {:a 1 :b 2} {:a 1})]
      (is (= 1 (count diff)))
      (is (= {:op :removed :path [:b] :before 2 :after nil}
             (first diff))))))

(deftest diff-paths-modified-leaf
  (testing "a key whose value changed (non-map, non-identical) → :modified"
    (let [diff (h/diff-paths {:a 1} {:a 2})]
      (is (= 1 (count diff)))
      (is (= {:op :modified :path [:a] :before 1 :after 2}
             (first diff))))))

(deftest diff-paths-nested-modified
  (testing "nested maps with a changed leaf → recursive :modified triple
            at the leaf path, not at the parent path"
    (let [before {:cart {:items [] :totals {:gross 0}}}
          after  {:cart {:items [{:id 7}] :totals {:gross 0}}}
          diff   (h/diff-paths before after)]
      (is (= [{:op :modified :path [:cart :items]
               :before [] :after [{:id 7}]}]
             diff)
          "the unchanged :totals subtree must NOT produce a triple"))))

(deftest diff-paths-mixed-ops
  (testing "a single epoch's diff can contain a mix of :added / :modified /
            :removed; result is sorted by path-as-string for stability"
    (let [before {:cart {:items [{:id 7 :qty 1}]}
                  :user/auth :anon}
          after  {:cart  {:items [{:id 7 :qty 1} {:id 22 :qty 1}]
                          :totals {:gross 48}}
                  :flash "Welcome"}
          diff   (h/diff-paths before after)
          paths  (mapv :path diff)
          ops    (into {} (map (juxt :path :op)) diff)]
      (is (= 4 (count diff)) "added :totals, modified :items, removed :user/auth, added :flash")
      (is (= :modified (get ops [:cart :items])))
      (is (= :added    (get ops [:cart :totals])))
      (is (= :added    (get ops [:flash])))
      (is (= :removed  (get ops [:user/auth])))
      (is (= paths (sort-by pr-str paths))
          "triples sorted lexically by path"))))

(deftest diff-paths-non-map-leaf-modified
  (testing "when a key's value transitions from non-map to map, the
            non-map → map change is a single :modified at the key"
    (let [diff (h/diff-paths {:a 1} {:a {:b 2}})]
      (is (= 1 (count diff)))
      (is (= :modified (:op (first diff))))
      (is (= [:a] (:path (first diff))))
      (is (= 1 (:before (first diff))))
      (is (= {:b 2} (:after (first diff)))))))

;; ---- (2) structural-sharing short-circuit -------------------------------

(deftest diff-paths-structural-sharing-skips-unchanged-subtree
  (testing "PersistentHashMap pointer-equality at each level short-circuits
            the diff walk on unchanged subtrees. Assertable by reusing
            the same sub-map reference in both before and after — the
            diff produces zero triples for that subtree even though it
            holds many keys."
    (let [shared       (zipmap (range 1000) (range 1000))
          before       {:big shared :counter 0}
          after        (assoc before :counter 1)  ;; :big is identical?
          diff         (h/diff-paths before after)]
      (is (= 1 (count diff))
          "only the :counter triple; :big short-circuits via identical?")
      (is (= [:counter] (:path (first diff))))
      (is (= :modified (:op (first diff))))
      (is (= 0 (:before (first diff))))
      (is (= 1 (:after  (first diff)))))))

(deftest diff-paths-deep-structural-sharing
  (testing "structural-sharing short-circuit works at depth — a deeply-
            nested subtree that's identical? in both inputs is skipped"
    (let [deep-shared {:cart {:items [{:id 1 :qty 5}]
                              :totals {:gross 5 :tax 0.5}}}
          before      (merge deep-shared {:user "ada"})
          after       (merge deep-shared {:user "ben"})
          diff        (h/diff-paths before after)]
      (is (= [{:op :modified :path [:user] :before "ada" :after "ben"}]
             diff)
          "the whole :cart subtree is identical? — must produce zero triples"))))

;; ---- (3) reserved-keys partition ----------------------------------------

(deftest reserved-app-db-keys-includes-the-six-runtime-keys
  (testing "reserved-app-db-keys matches spec/Conventions.md
            §Reserved app-db keys"
    (is (contains? h/reserved-app-db-keys :rf/machines))
    (is (contains? h/reserved-app-db-keys :rf/route))
    (is (contains? h/reserved-app-db-keys :rf/system-ids))
    (is (contains? h/reserved-app-db-keys :rf/pending-navigation))
    (is (contains? h/reserved-app-db-keys :rf/spawned))
    (is (contains? h/reserved-app-db-keys :rf/elision))))

(def ^:private conventions-reserved-app-db-keys
  "The canonical set of reserved app-db keys per spec/Conventions.md
  §Reserved app-db keys. Hard-coded here as a drift-detector: if a
  new reserved key lands in Conventions and this set is updated,
  `partition-reserved`'s coverage MUST be updated in lockstep — see
  rf2-w1r29 for the gap that motivated this test (rf2-qictc surfaced
  the lockstep expectation; `:rf/elision` had drifted out of the
  partition set).

  Per the rule in tools/causa/spec/004-App-DB-Diff.md §Reserved-keys
  group: 'If a new reserved key lands in Conventions, the `[runtime]`
  group's coverage and this table are updated in lockstep.' This
  hard-coded set is the test-side mirror of that table; updating
  Conventions, the panel table, AND this set is a single atomic
  change."
  #{:rf/machines
    :rf/route
    :rf/system-ids
    :rf/pending-navigation
    :rf/spawned
    :rf/elision})

(deftest reserved-app-db-keys-matches-conventions-md
  (testing "drift-detector: every key in spec/Conventions.md §Reserved
            app-db keys is covered by partition-reserved (rf2-w1r29
            follow-on to rf2-qictc)"
    (is (= conventions-reserved-app-db-keys h/reserved-app-db-keys)
        "partition-reserved's reserved-app-db-keys must equal the
        canonical Conventions set exactly — additions and removals
        in Conventions must land in Causa in the same change.")
    (doseq [k conventions-reserved-app-db-keys]
      (is (h/reserved-path? [k :child :leaf])
          (str "reserved-path? must return true for a path rooted at " k))
      (is (h/reserved-path? [k])
          (str "reserved-path? must return true for a top-level " k " path")))))

(deftest reserved-path-true-for-rf-roots
  (testing "reserved-path? returns true when the first path key is reserved"
    (is (true?  (h/reserved-path? [:rf/machines :foo :bar])))
    (is (true?  (h/reserved-path? [:rf/route])))
    (is (false? (h/reserved-path? [:cart :items])))
    (is (false? (h/reserved-path? [])))
    (is (false? (h/reserved-path? nil)))))

(deftest partition-reserved-splits-the-vector
  (testing "partition-reserved separates reserved-path triples from the rest"
    (let [triples [{:op :modified :path [:cart :items] :before [] :after [1]}
                   {:op :added    :path [:rf/route] :before nil :after {:id :home}}
                   {:op :modified :path [:user :name] :before "ada" :after "ben"}
                   {:op :modified :path [:rf/machines :auth]
                    :before {} :after {:state :idle}}]
          {:keys [reserved non-reserved]}
          (h/partition-reserved triples)]
      (is (= 2 (count reserved)))
      (is (= 2 (count non-reserved)))
      (is (every? #(h/reserved-path? (:path %)) reserved))
      (is (every? #(not (h/reserved-path? (:path %))) non-reserved)))))

(deftest reserved-summary-renders-current-rf-slots
  (testing "reserved-summary projects only the reserved keys present in db"
    (let [db {:user "ada"
              :rf/route    {:id :app/home}
              :rf/machines {:auth-id {:state :idle}}}
          summary (h/reserved-summary db)
          ks      (mapv first summary)]
      (is (= [:rf/machines :rf/route] ks)
          "sorted; only :rf/* keys actually in db are surfaced")
      (is (= [[:rf/machines {:auth-id {:state :idle}}]
              [:rf/route {:id :app/home}]]
             summary)))))

;; ---- (4) 'Show me when this changed' walker -----------------------------

(defn- mk-record
  "Build a minimal `:rf/epoch-record` for diff-walker tests. The
  walker reads :epoch-id, :db-before, :db-after, and :trigger-event."
  [epoch-id event db-before db-after]
  {:epoch-id      epoch-id
   :frame         :rf/default
   :committed-at  0
   :event-id      (first event)
   :trigger-event event
   :db-before     db-before
   :db-after      db-after
   :trace-events  []})

(deftest path-touched-true-on-direct-change
  (is (true?  (h/path-touched? {:a 1} {:a 2} [:a])))
  (is (false? (h/path-touched? {:a 1} {:a 1} [:a])))
  (is (true?  (h/path-touched? {:a {:b 1}} {:a {:b 2}} [:a :b]))))

(deftest path-touched-false-on-unchanged-sibling
  (testing "a change in a sibling subtree must NOT register as a touch
            of the focused path"
    (is (false? (h/path-touched? {:a {:b 1} :c 0}
                                 {:a {:b 1} :c 1}
                                 [:a :b])))))

(deftest op-at-path-classifies-by-presence
  (is (= :added    (h/op-at-path {} {:a 1} [:a])))
  (is (= :removed  (h/op-at-path {:a 1} {} [:a])))
  (is (= :modified (h/op-at-path {:a 1} {:a 2} [:a])))
  (is (nil?        (h/op-at-path {:a 1} {:a 1} [:a]))
      "unchanged path → nil"))

(deftest epochs-touching-path-returns-newest-first
  (testing "epochs-touching-path filters history to epochs that touched
            the focused path; result is newest-first.

            Per spec §Changed-paths derivation the walker is pointer-
            equality-based; we use `assoc-in` / `update-in` here so the
            unchanged subtree's :cart {:items ...} stays `identical?`
            across epoch boundaries — same shape a real host runtime
            produces via reg-event-db handlers."
    (let [db-0     {}
          db-1     (assoc-in db-0 [:cart :items] [])
          db-2     (assoc-in db-1 [:cart :items] [{:id 7}])
          db-3     (assoc db-2 :user "ada")  ;; :cart stays identical?
          db-4     (assoc-in db-3 [:cart :items] [])
          history [(mk-record :e-1 [:app/boot]     db-0 db-1)
                   (mk-record :e-2 [:cart/add-item] db-1 db-2)
                   (mk-record :e-3 [:user/login]    db-2 db-3)
                   (mk-record :e-4 [:cart/clear]    db-3 db-4)]
          hits    (h/epochs-touching-path history [:cart :items])
          eids    (mapv :epoch-id hits)]
      (is (= [:e-4 :e-2 :e-1] eids)
          "newest first; :e-3 (user/login) preserved :cart's identity, so
           the pointer-equality walker correctly skips it")
      (is (= :modified (:op (first hits))))
      (is (= [:cart/clear] (:event (first hits)))
          "event vector lifted off :trigger-event"))))

(deftest epochs-touching-path-empty-history
  (is (= [] (h/epochs-touching-path [] [:anywhere]))))

(deftest epochs-touching-path-no-hits
  (testing "when no epoch touched the path, returns an empty vector"
    (let [history [(mk-record :e-1 [:a] {} {:other 1})]]
      (is (= [] (h/epochs-touching-path history [:never :touched]))))))

;; ---- (6) count-redacted-modified-paths (rf2-bz1cl) ----------------------
;;
;; Contract per `count-redacted-modified-paths`:
;;
;;   - Walks both dbs in parallel.
;;   - Counts paths where BOTH sides carry `:rf/redacted` AND the
;;     parent subtree differs in pointer-identity.
;;   - Skips the reserved `:rf/elision` subtree.
;;   - Returns 0 for identical dbs, nil-safe.

(deftest redacted-modified-count-zero-when-no-redacted
  (testing "no redacted leaves anywhere → count is 0"
    (is (= 0 (h/count-redacted-modified-paths {:a 1}      {:a 2})))
    (is (= 0 (h/count-redacted-modified-paths {}          {})))
    (is (= 0 (h/count-redacted-modified-paths {:a {:b 1}} {:a {:b 2}})))))

(deftest redacted-modified-count-zero-when-only-one-side-redacted
  (testing "the chip is for `:rf/redacted` on BOTH sides — when only
            one side carries the sentinel, the diff algorithm already
            emits a normal :modified row and the count is 0."
    (is (= 0 (h/count-redacted-modified-paths
               {:auth {:token "secret"}}
               {:auth {:token :rf/redacted}}))
        "after-only sentinel → 0 (normal :modified diff row)")
    (is (= 0 (h/count-redacted-modified-paths
               {:auth {:token :rf/redacted}}
               {:auth {:token "fresh-secret"}}))
        "before-only sentinel → 0 (normal :modified diff row)")))

(deftest redacted-modified-count-counts-redacted-both-sides
  (testing "the canonical case: both sides redacted at the same path,
            in a parent subtree that mutated (a sibling slot's value
            changed). The diff algorithm sees :rf/redacted = :rf/redacted
            → no row; this counter is the separate signal."
    (let [before {:auth {:token :rf/redacted :method :jwt}}
          after  {:auth {:token :rf/redacted :method :session}}]
      (is (= 1 (h/count-redacted-modified-paths before after))))))

(deftest redacted-modified-count-zero-when-subtree-pointer-equal
  (testing "if the parent subtree is identical? across before/after,
            nothing inside it changed — skip it entirely, even if it
            contains a redacted slot. Mirrors the structural-sharing
            short-circuit in `diff-paths`."
    (let [inner  {:token :rf/redacted :method :jwt}
          before {:auth inner :user "ada"}
          after  (assoc before :user "bob")]
      (is (identical? (:auth before) (:auth after))
          "sanity: :auth subtree is pointer-equal across before/after")
      (is (= 0 (h/count-redacted-modified-paths before after))
          ":auth subtree didn't mutate → redacted slot isn't counted"))))

(deftest redacted-modified-count-multiple-paths-distinct
  (testing "distinct redacted leaves in mutated subtrees count
            independently. Two redacted leaves at two different paths
            → count is 2."
    (let [before {:auth   {:token   :rf/redacted}
                  :secret {:api-key :rf/redacted}
                  :public {:n 1}}
          after  {:auth   {:token   :rf/redacted :method :session}
                  :secret {:api-key :rf/redacted :rotated-at 99}
                  :public {:n 1}}]
      (is (= 2 (h/count-redacted-modified-paths before after))))))

(deftest redacted-modified-count-nested-redacted
  (testing "a redacted leaf deep in a nested tree counts when the
            enclosing subtree mutated."
    (let [before {:users {"u-1" {:profile {:ssn :rf/redacted :age 30}}}}
          after  {:users {"u-1" {:profile {:ssn :rf/redacted :age 31}}}}]
      (is (= 1 (h/count-redacted-modified-paths before after))))))

(deftest redacted-modified-count-skips-rf-elision-subtree
  (testing "the reserved `:rf/elision` subtree carries the elision
            registry; its own values may include `:rf/redacted` as
            example/documentation form. Counting them would confuse
            the signal — skip the entire subtree at the root."
    (let [before {:rf/elision {:sensitive-declarations
                               {[:foo] {:sensitive? true
                                        :sentinel :rf/redacted}}}
                  :auth {:token :rf/redacted}}
          after  (-> before
                     (assoc-in [:rf/elision :sensitive-declarations [:bar]]
                               {:sensitive? true :sentinel :rf/redacted})
                     (assoc-in [:auth :method] :session))]
      (is (= 1 (h/count-redacted-modified-paths before after))
          "only the :auth :token path counts; the :rf/elision tree is skipped"))))

(deftest redacted-modified-count-handles-nil-db
  (testing "nil-tolerant — a halted-destroy record may carry nil
            :db-before or :db-after per rf2-v0jwt. The counter must
            not throw."
    (is (= 0 (h/count-redacted-modified-paths nil nil)))
    (is (= 0 (h/count-redacted-modified-paths nil {:a 1})))
    (is (= 0 (h/count-redacted-modified-paths {:a 1} nil)))))

(deftest redacted-modified-count-pointer-equal-dbs
  (testing "identical? whole dbs → 0 immediately (no walk)."
    (let [db {:auth {:token :rf/redacted}}]
      (is (= 0 (h/count-redacted-modified-paths db db))))))
