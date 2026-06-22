(ns day8.re-frame2-xray.panels.app-db-diff-helpers-cljs-test
  "Pure-data tests for Xray's App-DB Diff panel helpers
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
  exercised in `app_db_diff_cljs_test.cljs` against the live Xray
  frame.

    1. **Diff algorithm produces correct `[op path before after]`
       triples** for `:added` / `:modified` / `:removed`. Mixed
       sub-tree changes produce the union of triples.

    2. **Pointer-equal subtrees short-circuit.** When `before` and
       `after` share an `identical?` sub-map, the recursive walker
       skips it entirely — assertable via an externally-mutated
       counter wired through a wrapper.

    3. **Reserved-keys segregation.** `partition-reserved` splits
       triples whose path roots in the reserved `:rf*` namespace family
       (e.g. `:rf.runtime/*`) into a separate group.

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
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as h]))

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
;;
;; EP-0001 (rf2-vzld77 / rf2-tj6w9l): the runtime subsystems (machines /
;; routing / elision) moved OUT of app-db's `:rf/runtime` container into a
;; SEPARATE runtime-db partition keyed by the reserved `:rf.runtime/*`
;; namespace. The App-DB panel surfaces them as sections via the
;; `runtime-areas` table (now pointing into the runtime-db partition); the
;; reserved-keys partition / `reserved-path?` now key on the reserved
;; `:rf*` NAMESPACE family (a normal app-db diff triple is never reserved).

(deftest reserved-app-db-keys-is-empty-post-migration
  (testing "EP-0001 (rf2-tj6w9l) — runtime subsystems moved to the
            runtime-db partition, so app-db carries no reserved root key;
            reserved-app-db-keys is empty"
    (is (= #{} h/reserved-app-db-keys))))

(deftest runtime-areas-covers-the-six-subsystems-in-runtime-db
  (testing "runtime-areas maps each operator-facing area-id to its
            sub-path under the RUNTIME-DB partition's reserved
            :rf.runtime/* roots (EP-0001 rf2-vzld77 / rf2-tj6w9l)"
    (is (= [:rf.runtime/machines :snapshots]         (get h/runtime-areas :rf/machines)))
    (is (= [:rf.runtime/machines :spawned]           (get h/runtime-areas :rf/spawned)))
    (is (= [:rf.runtime/machines :system-ids]        (get h/runtime-areas :rf/system-ids)))
    (is (= [:rf.runtime/routing :current]            (get h/runtime-areas :rf/route)))
    (is (= [:rf.runtime/routing :pending-navigation] (get h/runtime-areas :rf/pending-navigation)))
    (is (= [:rf.runtime/elision]                     (get h/runtime-areas :rf/elision)))
    ;; The OLD app-db `:rf/runtime` paths are GONE (the executable check
    ;; for the stale-path regression rf2-tj6w9l flagged).
    (is (not (some (fn [p] (= :rf/runtime (first p))) (vals h/runtime-areas)))
        "no runtime-area path roots in the retired app-db :rf/runtime container")))

(deftest reserved-path-true-for-reserved-namespace-root
  (testing "reserved-path? catches the reserved :rf*-namespace family; a
            normal app-db triple is never reserved (EP-0001 rf2-tj6w9l —
            runtime subsystems no longer live in app-db)"
    (is (true?  (h/reserved-path? [:rf/runtime :machines])) ":rf/* root")
    (is (true?  (h/reserved-path? [:rf.machine/foo])) ":rf.<subns>/* root")
    (is (false? (h/reserved-path? [:cart :items])))
    (is (false? (h/reserved-path? [:user :name]))
        "an ordinary user-domain app-db path is not reserved")
    (is (false? (h/reserved-path? [])))
    (is (false? (h/reserved-path? nil)))))

(deftest reserved-summary-renders-current-runtime-subsystems
  (testing "reserved-summary projects populated runtime subsystem
            sub-paths out of the RUNTIME-DB partition value into
            [:area-id value] pairs, sorted by area-id (EP-0001 rf2-tj6w9l)"
    (let [runtime-db {:rf.runtime/routing  {:current {:route-id :app/home}}
                      :rf.runtime/machines {:snapshots {:auth-id {:state :idle}}}}
          summary (h/reserved-summary runtime-db)
          ks      (mapv first summary)]
      (is (= [:rf/machines :rf/route] ks)
          "sorted; only logical areas with a live value are surfaced")
      (is (= [[:rf/machines {:auth-id {:state :idle}}]
              [:rf/route {:route-id :app/home}]]
             summary)))))

(deftest partition-reserved-splits-on-reserved-namespace
  (testing "partition-reserved separates triples whose path roots in the
            reserved :rf* namespace family from the rest (EP-0001
            rf2-tj6w9l — a normal app-db triple is non-reserved)"
    (let [triples [{:op :modified :path [:cart :items] :before [] :after [1]}
                   {:op :modified :path [:user :name] :before "ada" :after "ben"}
                   {:op :added    :path [:rf.machine/transient :x] :before nil :after 1}]
          {:keys [reserved non-reserved]}
          (h/partition-reserved triples)]
      (is (= 1 (count reserved)) "only the :rf.machine/* triple is reserved")
      (is (= 2 (count non-reserved)) "the two user-domain triples are not")
      (is (every? #(h/reserved-path? (:path %)) reserved))
      (is (every? #(not (h/reserved-path? (:path %))) non-reserved)))))

;; ---- (3b) current-state sectioning (rf2-okvit; EP-0001 rf2-tj6w9l) -------
;;
;; The app-db tab is a CURRENT-STATE inspector. `current-state-sections`
;; splits the frame's TWO partitions into:
;;   - TOP: app-db MINUS reserved keys (user-domain). ALWAYS present.
;;   - one section per POPULATED reserved runtime area, read from the
;;     SEPARATE runtime-db partition at the `:rf.runtime/*` paths:
;;     machines/spawned fan out one entry per instance id; route + the
;;     other slices are singletons.
;;
;; EP-0001 (rf2-tj6w9l): the runtime subsystems moved out of app-db's
;; `:rf/runtime` into the runtime-db partition. The signature is now
;; `(current-state-sections app-db runtime-db [before])` — the TOP reads
;; app-db, the areas read runtime-db at `:rf.runtime/*`.
;;
;; rf2-jcdvo — empty / absent reserved areas are FILTERED at projection
;; time (omitted from `:areas` entirely). The renderer never draws
;; labelled "No X" placeholder cards; the operator sees only areas that
;; actually carry state. The TOP user-domain section is the only
;; always-rendered slot.

(defn- area-by [model area]
  (some (fn [a] (when (= area (:area a)) a)) (:areas model)))

(deftest user-domain-db-strips-reserved-keys
  (testing "user-domain-db drops every reserved :rf*-namespaced key from
            app-db, keeps the rest. Post EP-0001 the runtime subsystems
            no longer live in app-db; this filter still hides any
            framework-internal `:rf*` key a host stashes at the app-db
            root."
    (is (= {:cart {:items []} :user "ada"}
           (h/user-domain-db {:cart {:items []}
                              :user "ada"
                              :rf.machine/transient {:x 1}})))
    (is (= {} (h/user-domain-db nil)) "nil db → empty map")
    (is (= {} (h/user-domain-db {:rf.machine/transient {:x 1}}))
        "reserved-keys-only db → empty user-domain map")))

(deftest current-state-sections-top-is-user-domain
  (testing "the :top section is the app-db minus any reserved :rf* key;
            the runtime-db partition does NOT contribute to TOP"
    (let [model (h/current-state-sections
                  {:counter 5 :user {:name "ada"}}
                  {:rf.runtime/routing {:current {:route-id :app/home}}})]
      (is (= {:counter 5 :user {:name "ada"}} (:top model))
          ":top is the user-domain app-db (runtime-db is the areas' source)"))))

(deftest current-state-sections-tolerates-whole-redacted-value
  (testing "rf2-cra0nq — when the local-render egress redacts the WHOLE value
            (an unreachable / nil observed frame fails closed to the
            `:rf/redacted` sentinel, a scalar — NOT a map), the section model
            treats it as the empty partition rather than iterating the scalar
            (which would throw). The model is still a non-nil, well-formed
            section map with an empty TOP + no areas"
    (let [model (h/current-state-sections :rf/redacted :rf/redacted)]
      (is (some? model) "a whole-redacted value yields a non-nil model")
      (is (= {} (:top model))
          "a whole-redacted app-db has no decomposable user-domain content")
      (is (= [] (:areas model))
          "a whole-redacted runtime-db contributes no reserved areas")))
  (testing "diff mode tolerates a whole-redacted pre-image too — the redacted
            before-image is treated as empty (everything reads :added), never
            throws"
    (let [model (h/current-state-sections {:counter 1} {}
                                          {:app :rf/redacted :runtime :rf/redacted})]
      (is (some? model))
      (is (= {:counter 1} (:top model))
          "a present value still decomposes; only the redacted pre-image is empties"))))

(deftest current-state-sections-enumerates-only-populated-areas
  (testing "rf2-jcdvo — :areas contains ONLY populated runtime
            subsystems (read from runtime-db); empty / absent subsystems
            are omitted entirely (no placeholder cards in the panel)"
    (let [model (h/current-state-sections {:counter 1} {})]
      (is (= [] (:areas model))
          "an empty runtime-db produces zero reserved-area entries"))
    (let [model (h/current-state-sections
                  {:counter 1}
                  {:rf.runtime/routing  {:current {:route-id :home}}
                   :rf.runtime/machines {:snapshots {:auth {:state :idle}}}})
          areas (set (map :area (:areas model)))]
      (is (= #{:rf/machines :rf/route} areas)
          "only the two populated areas appear; the other four reserved
           subsystems are omitted")
      (is (every? (complement :empty?) (:areas model))
          "every entry in :areas is non-empty"))))

(deftest current-state-sections-machines-fan-out-one-per-instance
  (testing ":rf/machines fans out to one instance entry per machine id —
            section title = the machine id, NOT a single combined blob.
            The snapshots map lives at [:rf.runtime/machines :snapshots]
            in the runtime-db partition (EP-0001 rf2-tj6w9l)."
    (let [runtime-db {:rf.runtime/machines {:snapshots {:title/flow {:state :playing}
                                                        :auth       {:state :idle}}}}
          area (area-by (h/current-state-sections {} runtime-db) :rf/machines)]
      (is (= :instances (:kind area)))
      (is (false? (:empty? area)))
      (is (= 2 (count (:instances area))))
      (is (= [:auth :title/flow] (mapv :id (:instances area)))
          "instances sorted by (pr-str id) for stable order")
      (is (= {:state :playing}
             (:value (some #(when (= :title/flow (:id %)) %)
                           (:instances area))))
          "each instance carries its own snapshot value"))))

(deftest current-state-sections-spawned-fans-out-per-parent
  (testing ":rf/spawned (map-of-instances by parent id) lives at
            [:rf.runtime/machines :spawned] and fans out per parent"
    (let [runtime-db {:rf.runtime/machines {:spawned {:parent-a {:invoke-1 :spawned-x}}}}
          area (area-by (h/current-state-sections {} runtime-db) :rf/spawned)]
      (is (= :instances (:kind area)))
      (is (= [:parent-a] (mapv :id (:instances area)))))))

(deftest current-state-sections-empty-machines-registry-is-omitted
  (testing "rf2-jcdvo — an absent OR present-but-empty :rf/machines
            registry is OMITTED from :areas entirely; no placeholder
            card reaches the renderer"
    (is (nil? (area-by (h/current-state-sections {:counter 1} {}) :rf/machines))
        "absent :rf.runtime/machines → no area entry")
    (is (nil? (area-by (h/current-state-sections
                         {} {:rf.runtime/machines {:snapshots {}}})
                       :rf/machines))
        "present-but-empty registry → no area entry")))

(deftest current-state-sections-route-is-singleton
  (testing ":rf/route (logical area for [:rf.runtime/routing :current])
            is a SINGLE current-route slice → :singleton kind, one
            section carrying the slice value"
    (let [route {:route-id :app/article :params {:id "A"}
                 :query {} :fragment nil :transition :idle
                 :error nil :nav-token "nav-1"}
          area  (area-by (h/current-state-sections
                           {} {:rf.runtime/routing {:current route}})
                         :rf/route)]
      (is (= :singleton (:kind area)))
      (is (false? (:empty? area)))
      (is (= route (:value area))
          "the section value is the whole current-route slice"))))

(deftest current-state-sections-absent-route-is-omitted
  (testing "rf2-jcdvo — an absent :rf/route is OMITTED from :areas
            entirely; no placeholder card reaches the renderer"
    (is (nil? (area-by (h/current-state-sections {:counter 1} {}) :rf/route))
        "absent :rf.runtime/routing → no area entry")))

(deftest current-state-sections-empty-singleton-collection-is-omitted
  (testing "rf2-jcdvo — a present-but-empty singleton collection (e.g. {}
            pending-nav at [:rf.runtime/routing :pending-navigation]) is
            OMITTED from :areas entirely"
    (is (nil? (area-by (h/current-state-sections
                         {} {:rf.runtime/routing {:pending-navigation {}}})
                       :rf/pending-navigation))
        "{} pending-navigation → no area entry")))

(deftest current-state-sections-nil-and-empty-db-safe
  (testing "rf2-jcdvo — nil-safe: nil / empty partitions yield an empty
            TOP + ZERO reserved-area entries (every reserved area is empty
            so every entry is filtered out)"
    (doseq [app-db [nil {}]
            rt     [nil {}]]
      (let [model (h/current-state-sections app-db rt)]
        (is (= {} (:top model)))
        (is (= [] (:areas model))
            "no reserved-area entries — every reserved slot is empty so
             every entry is filtered out at projection time")))))

(deftest current-state-sections-area-order-is-stable
  (testing "areas render in `reserved-area-order` — machines + spawned
            (the registries) lead, then the singleton slices. With every
            runtime subsystem populated, all six appear in canonical
            order. The underlying values live at [:rf.runtime/…] in the
            runtime-db partition (EP-0001 rf2-tj6w9l)."
    (let [runtime-db {:rf.runtime/machines {:snapshots  {:auth {:state :idle}}
                                            :spawned    {:parent {:invoke :child}}
                                            :system-ids #{:app}}
                      :rf.runtime/routing  {:current             {:route-id :home}
                                            :pending-navigation  {:to :next}}
                      :rf.runtime/elision  {:declarations {}}}
          model (h/current-state-sections {} runtime-db)]
      (is (= h/reserved-area-order (mapv :area (:areas model)))))))

;; ---- inline-diff section model (spec/021 §4.3, rf2-ad7zx.11) -------------
;;
;; The 3-arity `current-state-sections` threads a `{:app .. :runtime ..}`
;; before-image so each section carries a `:before` slice for the inline
;; `← changed` annotation. The 2-arity form (no pre-image) tags every
;; section with the `no-diff` sentinel so the renderer falls back to plain
;; current-state.

(deftest current-state-sections-2-arity-is-no-diff-everywhere
  (testing "the 2-arity form tags TOP + every section with the no-diff
            sentinel (renderer renders plain current-state, no annotation)"
    (let [model (h/current-state-sections
                  {:counter 1}
                  {:rf.runtime/routing  {:current {:route-id :home}}
                   :rf.runtime/machines {:snapshots {:title/flow {:state :idle}}}})]
      (is (= h/no-diff (:before-top model)) "TOP carries the no-diff sentinel")
      (doseq [a (:areas model)]
        (if (= :instances (:kind a))
          (doseq [inst (:instances a)]
            (is (= h/no-diff (:before inst))
                "each instance no-diff in 2-arity"))
          (is (= h/no-diff (:before a))
              "each singleton no-diff in 2-arity"))))))

(deftest current-state-sections-3-arity-top-before-is-prior-user-domain
  (testing ":before-top is the user-domain slice of the app-db pre-image,
            so the TOP section diffs old → new"
    (let [model (h/current-state-sections
                  {:counter 2}          ;; app-db now
                  {}                     ;; runtime-db now
                  {:app {:counter 1} :runtime {}})]
      (is (= {:counter 1} (:before-top model))
          ":before-top is the prior user-domain app-db")
      (is (= {:counter 2} (:top model))))))

(deftest current-state-sections-3-arity-instance-before-is-prior-snapshot
  (testing "each machine instance carries its prior snapshot as :before;
            an instance absent before-cascade gets the `added` sentinel
            (rf2-227cz). Snapshots live at [:rf.runtime/machines
            :snapshots] in the runtime-db pre/post-image."
    (let [rt-before {:rf.runtime/machines {:snapshots {:title/flow {:state :idle}}}}
          rt-after  {:rf.runtime/machines {:snapshots {:title/flow {:state :loaded}
                                                       :auth       {:state :idle}}}}
          area   (area-by (h/current-state-sections {} rt-after
                                                    {:app {} :runtime rt-before})
                          :rf/machines)
          flow   (some #(when (= :title/flow (:id %)) %) (:instances area))
          auth   (some #(when (= :auth (:id %)) %) (:instances area))]
      (is (= {:state :idle} (:before flow))
          "title/flow diffs against its prior snapshot")
      (is (= {:state :loaded} (:value flow)))
      (is (= h/added (:before auth))
          "rf2-227cz — a freshly-spawned machine (absent in before) is
           the `added` sentinel, not `no-diff`"))))

(deftest current-state-sections-3-arity-singleton-before-is-prior-slice
  (testing "a singleton slice carries its prior runtime-db value as
            :before; an absent-before singleton gets the `added` sentinel
            (rf2-227cz)"
    (let [rt-before {:rf.runtime/routing {:current {:route-id :home}}}
          rt-after  {:rf.runtime/routing  {:current {:route-id :cart}}
                     :rf.runtime/machines {:system-ids #{:app}}}
          model  (h/current-state-sections {} rt-after
                                           {:app {} :runtime rt-before})
          route  (area-by model :rf/route)
          sysids (area-by model :rf/system-ids)]
      (is (= {:route-id :home} (:before route)) "route diffs old → new")
      (is (= {:route-id :cart} (:value route)))
      (is (= h/added (:before sysids))
          "rf2-227cz — system-ids absent before-cascade → `added`
           (the slice appeared this epoch), not `no-diff`"))))

(deftest current-state-sections-3-arity-nil-before-safe
  (testing "a nil before-image map (boot epoch — every slot is newly
            added) is handled: app/runtime befores degrade to {}; an
            absent singleton slot classifies `added` (rf2-227cz — the
            route slot appeared this epoch)"
    (let [model (h/current-state-sections
                  {:counter 1}
                  {:rf.runtime/routing {:current {:route-id :home}}}
                  {:app nil :runtime nil})]
      ;; A present (non-no-diff) before-image flips diff? on, so the
      ;; user-domain before is {} (not the sentinel).
      (is (= {} (:before-top model)))
      (is (= h/added (:before (area-by model :rf/route)))
          "rf2-227cz — an added route slot (absent before) → `added`,
           not `no-diff`"))))

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
            produces via db-only reg-event handlers."
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
