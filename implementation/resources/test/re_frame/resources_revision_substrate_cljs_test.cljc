(ns re-frame.resources-revision-substrate-cljs-test
  "EP-0019 slice 1 — the per-entry `:revision` durable-entry substrate the
  optimistic-mutation-rollback settle protocol builds on (rf2-byl7bk.1, Open
  Issue 5 ruling 2026-06-15).

  `:revision` is the per-entry WRITE identity: a monotone counter bumped on
  EVERY authoritative durable entry write a rollback could clobber, and the
  basis of the settle-time conflict check (`state/revision-conflict?`). These
  JVM+CLJS unit tests pin the substrate contract the later slices depend on:

    1. SHAPE — `empty-entry` carries `:revision 0`, DISTINCT from `:generation`;
    2. DISTINCT FROM `:generation` — `entry-start-load` bumps `:generation`
       (load START) and leaves `:revision` UNMOVED (no false-conflict on an
       in-flight refetch); `entry-succeeded` bumps `:revision`;
    3. UNCONDITIONAL BUMP — `entry-succeeded` / `patch-entry` / `populate-entry`
       bump `:revision` even when `:data` is `=`-shared (the freshness-only
       settle the ruling names — a value-gated token would MISS it);
    4. CONFLICT COMPARISON — `revision-conflict?` is false when the recorded
       revision matches and TRUE when a competing authoritative write moved it.

  PURE — the substrate is the pure transition functions in `re-frame.resources
  .state` + `re-frame.resources.mutation-runtime`; CLJC so the load-bearing JVM
  run (`clojure -M:test`) exercises it alongside the CLJS node runtime."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [re-frame.resources.state :as state]
   [re-frame.resources.mutation-runtime :as mut-rt]))

;; ---- shape: base value, distinct from :generation -------------------------

(deftest empty-entry-carries-revision-zero
  (testing "the base empty entry carries :revision 0, alongside :generation 0"
    (let [e (state/empty-entry :conduit/article)]
      (is (= 0 (:revision e)) ":revision base value is 0")
      (is (= 0 (:generation e)) ":generation base value is 0")
      (is (contains? e :revision)
          ":revision is a present durable entry fact, not absent")))
  (testing "the 2-arity (with scoped-key) also carries :revision 0"
    (let [sk (state/scoped-resource-key :rf.scope/global :conduit/article {:slug "a"})
          e  (state/empty-entry :conduit/article sk)]
      (is (= 0 (:revision e)))
      (is (= sk (:resource/key e))))))

(deftest revision-is-distinct-from-generation
  (testing ":revision and :generation are independent facts; entry-start-load
            bumps :generation (load START) but NOT :revision — so an in-flight
            refetch never false-conflicts"
    (let [e0 (state/empty-entry :conduit/article)
          e1 (state/entry-start-load
               e0 {:generation 7 :work-id [:w 1] :request-id "r1" :owner :o})]
      (is (= 7 (:generation e1)) ":generation moves to the allocated value at load start")
      (is (= 0 (:revision e1))
          ":revision is UNMOVED by entry-start-load — load START is the work
           identity (:generation), not an authoritative durable write"))))

;; ---- unconditional bump on an authoritative durable write -----------------

(deftest entry-succeeded-bumps-revision-even-on-equal-data
  (testing "a load success bumps :revision; and a freshness-only settle
            (re-stamping :loaded-at / :stale-at while :data is `=`-shared) STILL
            bumps :revision — the byl7bk ruling's load-bearing case"
    (let [loaded (-> (state/empty-entry :conduit/article)
                     (state/entry-succeeded {:data {:n 1} :loaded-at 100
                                             :stale-at 200 :tags #{[:a]}}))]
      (is (= 1 (:revision loaded)) "first load success bumps 0 -> 1")
      (is (= 100 (:loaded-at loaded)))
      ;; a refetch returning EQUAL data: structural sharing keeps the old :data
      ;; identity, but :loaded-at / :stale-at are re-stamped — an authoritative
      ;; freshness settle a rollback could clobber.
      (let [resettled (state/entry-succeeded
                        loaded {:data {:n 1} :loaded-at 999 :stale-at 1200
                                :tags #{[:a]}})]
        (is (identical? (:data loaded) (:data resettled))
            "structural sharing: equal :data keeps the SAME value identity")
        (is (= 999 (:loaded-at resettled))
            "freshness IS re-stamped even though :data is `=`-shared")
        (is (= 2 (:revision resettled))
            ":revision bumps UNCONDITIONALLY on the equal-data freshness settle
             — NOT gated on `(= old new)` of :data (the cache-coherence fix)")))))

(deftest patch-entry-bumps-revision-even-on-equal-data
  (testing "a controlled patch bumps :revision, including the `=`-shared branch"
    (let [base   (-> (state/empty-entry :conduit/article)
                     (state/entry-succeeded {:data {:count 0} :loaded-at 10
                                             :stale-at 20 :tags #{}}))
          rev0   (:revision base)
          ;; identity patch (returns `=` data) — structural-shared but a
          ;; re-stamp of :loaded-at / :stale-at, an authoritative durable write.
          patched (mut-rt/patch-entry base (fn [d _r] d) :ignored-result
                                      {:clock-ms 50 :stale-at 60})]
      (is (= (inc rev0) (:revision patched))
          "patch bumps :revision even when the patch-fn returns `=` data")
      (is (identical? (:data base) (:data patched))
          "structural sharing still holds for `=` patched data")
      (is (= 50 (:loaded-at patched)) "freshness IS re-stamped")))
  (testing "a patch of an entry with NO usable data is a no-op — no bump"
    (let [empty   (state/empty-entry :conduit/article)
          patched (mut-rt/patch-entry empty (fn [d _r] {:x 1}) :r
                                      {:clock-ms 1 :stale-at 2})]
      (is (= empty patched) "no-data patch returns the entry unchanged")
      (is (= 0 (:revision patched)) "the no-op path makes no write and no bump"))))

(deftest populate-entry-bumps-revision
  (testing "populate-of-fresh seeds :revision 1 (base 0 -> bump); populate-over-
            existing bumps including the `=`-shared branch"
    (let [sk    (state/scoped-resource-key :rf.scope/global :conduit/article {})
          fresh (mut-rt/populate-entry nil :conduit/article {:v 1}
                                       {:clock-ms 5 :stale-at 9 :tags #{[:t]}
                                        :scoped-key sk})]
      (is (= 1 (:revision fresh)) "a freshly-seeded populate bumps 0 -> 1")
      (is (= {:v 1} (:data fresh)))
      ;; populate over the existing entry with EQUAL value — structural-shared,
      ;; but re-stamps freshness: an authoritative write.
      (let [re (mut-rt/populate-entry fresh :conduit/article {:v 1}
                                      {:clock-ms 77 :stale-at 88 :tags #{[:t]}})]
        (is (identical? (:data fresh) (:data re))
            "equal populate value keeps the SAME :data identity")
        (is (= 77 (:loaded-at re)) "freshness IS re-stamped")
        (is (= 2 (:revision re))
            ":revision bumps unconditionally on the equal-value populate")))))

;; ---- the bump helper itself -----------------------------------------------

(deftest bump-revision-helper-is-total-and-monotone
  (testing "bump-revision increments, treats absent/nil :revision as 0, and
            leaves a nil entry unchanged"
    (is (= 1 (:revision (state/bump-revision {:revision 0}))))
    (is (= 6 (:revision (state/bump-revision {:revision 5}))))
    (is (= 1 (:revision (state/bump-revision {})))
        "absent :revision is treated as 0 (a pre-EP-0019 entry)")
    (is (= 1 (:revision (state/bump-revision {:revision nil})))
        "nil :revision is treated as 0")
    (is (nil? (state/bump-revision nil))
        "a nil entry has nothing to bump — returned unchanged"))
  (testing "entry-revision reads the fact, defaulting to 0"
    (is (= 0 (state/entry-revision nil)))
    (is (= 0 (state/entry-revision {})))
    (is (= 3 (state/entry-revision {:revision 3})))))

;; ---- the canonical-identity conflict comparison ---------------------------

(deftest revision-conflict-detects-a-competing-authoritative-write
  (testing "no conflict when the recorded revision matches the current one"
    (let [loaded (-> (state/empty-entry :conduit/article)
                     (state/entry-succeeded {:data {:n 1} :loaded-at 1
                                             :stale-at 2 :tags #{}}))
          rec    (state/entry-revision loaded)]
      (is (false? (state/revision-conflict? loaded rec))
          "an entry that has NOT been written since the apply is conflict-free")))
  (testing "CONFLICT when a competing authoritative write moved the revision
            between the recorded apply and the settle"
    (let [loaded   (-> (state/empty-entry :conduit/article)
                       (state/entry-succeeded {:data {:n 1} :loaded-at 1
                                               :stale-at 2 :tags #{}}))
          recorded (state/entry-revision loaded)         ;; observed at apply time
          ;; a competing write lands (a refetch returning equal data — still an
          ;; authoritative freshness settle that bumps :revision):
          competed (state/entry-succeeded
                     loaded {:data {:n 1} :loaded-at 500 :stale-at 600 :tags #{}})]
      (is (= (inc recorded) (state/entry-revision competed)))
      (is (true? (state/revision-conflict? competed recorded))
          "the moved revision is detected as a conflict — the recorded inverse
           is now a stale `before` the settle must NOT blindly restore")))
  (testing "the comparison is canonical-identity over the counter, not a value
            diff: a recorded nil compares as 0"
    (is (false? (state/revision-conflict? {:revision 0} nil))
        "recorded nil ~ 0 vs current 0 — no conflict")
    (is (true? (state/revision-conflict? {:revision 1} nil))
        "recorded nil ~ 0 vs current 1 — conflict")))
