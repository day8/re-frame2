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

    3. **Reserved-key filtering and the current-state section model.**
       `user-domain-db` hides the reserved `:rf*` namespace family;
       `runtime-areas` + `reserved-summary` project the runtime-db
       partition; `current-state-sections` builds what the panel draws.

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

(deftest diff-paths-equal-but-rebuilt-leaf-is-no-change
  (testing "rf2-3x7nj.24.5 — a leaf the handler REBUILT to an `=` value is
            no change: `diff-paths` agrees with the runtime's value
            equality instead of reporting `~ [:todos] X → X`"
    (let [todos  [{:id 1 :done false} {:id 2 :done false}]
          before {:loading? true :todos todos :user {:roles #{:admin :ops}}}
          ;; The two idioms the bead names: a filter that removes nothing,
          ;; and a set re-built with `into`. Both are `=`, neither is
          ;; `identical?` — the precondition this row exists for.
          after  (-> before
                     (assoc :loading? false)
                     (update :todos #(vec (remove :done %)))
                     (update-in [:user :roles] #(into #{} %)))]
      (is (and (= (:todos before) (:todos after))
               (not (identical? (:todos before) (:todos after)))
               (= (get-in before [:user :roles]) (get-in after [:user :roles]))
               (not (identical? (get-in before [:user :roles])
                                (get-in after [:user :roles]))))
          "precondition: both rebuilt leaves are `=` but not `identical?`")
      (is (= [{:op :modified :path [:loading?] :before true :after false}]
             (h/diff-paths before after))
          "only the real change — no phantom `:modified` for the rebuilt leaves")
      (is (= [] (h/diff-paths (:todos before) (:todos after)))
          "the top-level non-map arm applies the same `=` test")))
  (testing "control — a leaf that genuinely changed is still `:modified`"
    (is (= [{:op :modified :path [:todos] :before [1] :after [1 2]}]
           (h/diff-paths {:todos [1]} {:todos [1 2]})))
    (is (= [{:op :modified :path [] :before [1] :after [2]}]
           (h/diff-paths [1] [2])))))

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
;; `runtime-areas` table (now pointing into the runtime-db partition), and
;; `user-domain-db` hides the reserved `:rf*` NAMESPACE family from the TOP
;; section (a normal app-db diff triple is never reserved).
;;
;; rf2-y8doi.29 — the `reserved-app-db-keys` / `reserved-path?` /
;; `triple-path` / `partition-reserved` cluster went with the unreachable
;; path-click machinery: nothing in `tools/xray/src` called any of them
;; but each other. Their deftests went with them.

(deftest runtime-areas-covers-the-six-subsystems-in-runtime-db
  (testing "runtime-areas maps each operator-facing area-id to its
            sub-path under the RUNTIME-DB partition's reserved
            :rf.runtime/* roots (EP-0001 rf2-vzld77 / rf2-tj6w9l)"
    (is (= [:rf.runtime/machines :snapshots]         (get h/runtime-areas :rf/machines)))
    (is (= [:rf.runtime/machines :spawned]           (get h/runtime-areas :rf/spawned)))
    (is (= [:rf.runtime/routing :current]            (get h/runtime-areas :rf/route)))
    (is (= [:rf.runtime/routing :pending-navigation] (get h/runtime-areas :rf/pending-navigation)))
    (is (= [:rf.runtime/elision]                     (get h/runtime-areas :rf/elision)))
    ;; The OLD app-db `:rf/runtime` paths are GONE (the executable check
    ;; for the stale-path regression rf2-tj6w9l flagged).
    (is (not (some (fn [p] (= :rf/runtime (first p))) (vals h/runtime-areas)))
        "no runtime-area path roots in the retired app-db :rf/runtime container")))

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
                                            :spawned    {:parent {:invoke :child}}}
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
          rt-after  {:rf.runtime/routing {:current            {:route-id :cart}
                                          :pending-navigation {:to :checkout}}}
          model   (h/current-state-sections {} rt-after
                                            {:app {} :runtime rt-before})
          route   (area-by model :rf/route)
          pending (area-by model :rf/pending-navigation)]
      (is (= {:route-id :home} (:before route)) "route diffs old → new")
      (is (= {:route-id :cart} (:value route)))
      (is (= h/added (:before pending))
          "rf2-227cz — an area absent before-cascade → `added`
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

;; ---- rf2-3x7nj.24.2: a whole-section removal stays visible ---------------
;;
;; The section model was built from the post-state alone, so anything this
;; epoch removed at section granularity — a destroyed machine, the last
;; machine, a cleared pending-navigation — left no row at all. In diff mode
;; the ids walked are the union of both sides, a before-only instance or
;; slot carries the `removed` sentinel as its `:value` and its prior state as
;; `:before`, and an area this epoch emptied survives the empty-area filter.

(defn- instance-by [area id]
  (some #(when (= id (:id %)) %) (:instances area)))

(deftest current-state-sections-destroyed-instance-reads-removed
  (testing "a machine destroyed this epoch is a `removed` instance carrying
            its prior snapshot, beside the survivor that still diffs"
    (let [rt-before {:rf.runtime/machines {:snapshots {:door/main {:state :open}
                                                       :other     {:state :idle}}}}
          rt-after  {:rf.runtime/machines {:snapshots {:other {:state :idle}}}}
          area      (area-by (h/current-state-sections {} rt-after
                                                       {:app {} :runtime rt-before})
                             :rf/machines)
          door      (instance-by area :door/main)]
      (is (= [:door/main :other] (mapv :id (:instances area)))
          "the destroyed id is still a row, in the stable sort order")
      (is (= h/removed (:value door)) "its value is the `removed` sentinel")
      (is (= {:state :open} (:before door)) "its before is the prior snapshot")
      (is (= {:state :idle} (:value (instance-by area :other)))
          "control — the surviving instance is untouched"))))

(deftest current-state-sections-area-emptied-this-epoch-survives
  (testing "the ONLY machine destroyed: the area survives the empty-area
            filter because the pre-image carried state"
    (let [model (h/current-state-sections
                  {} {:rf.runtime/machines {:snapshots {}}}
                  {:app {} :runtime {:rf.runtime/machines {:snapshots {:door/main {:state :open}}}}})
          area  (area-by model :rf/machines)]
      (is (some? area) "the machines area is still in :areas")
      (is (false? (:empty? area)))
      (is (= [[:door/main h/removed {:state :open}]]
             (mapv (juxt :id :value :before) (:instances area))))))
  (testing "a cleared pending-navigation reads as a `removed` singleton"
    (let [model (h/current-state-sections
                  {} {:rf.runtime/routing {:current {:id :home}}}
                  {:app {} :runtime {:rf.runtime/routing {:current            {:id :home}
                                                          :pending-navigation {:to :app/settings}}}})
          pending (area-by model :rf/pending-navigation)]
      (is (some? pending) "the pending-navigation area survives")
      (is (= h/removed (:value pending)))
      (is (= {:to :app/settings} (:before pending)))))
  (testing "a slot emptied to a PRESENT `{}` diffs as itself (member-level),
            not as a whole-slot removal"
    (let [model (h/current-state-sections
                  {} {:rf.runtime/routing {:pending-navigation {}}}
                  {:app {} :runtime {:rf.runtime/routing {:pending-navigation {:to :x}}}})
          pending (area-by model :rf/pending-navigation)]
      (is (= {} (:value pending)))
      (is (= {:to :x} (:before pending))))))

(deftest current-state-sections-removal-needs-a-pre-image
  (testing "control — without a pre-image (no-diff mode) nothing reads
            removed and an empty area is still omitted, and an area empty
            on BOTH sides stays omitted in diff mode"
    (is (= [] (:areas (h/current-state-sections {} {:rf.runtime/machines {:snapshots {}}})))
        "no-diff mode: no removal claim to make")
    (is (= [] (:areas (h/current-state-sections
                        {} {} {:app {} :runtime {:rf.runtime/routing {:pending-navigation {}}}})))
        "empty before AND after: still omitted")))
