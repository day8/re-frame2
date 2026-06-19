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

;; ---- failure / cancel SETTLE bumps :revision (rf2-mx0w2o) ------------------
;;
;; THE BUG (correctness review rf2-mx0w2o): a resource failure / cancel SETTLE
;; clears `:current-work` and records terminal facts but did NOT bump
;; `:revision`. So a snapshot taken while the attempt was IN-FLIGHT (`:fetching`
;; + `:current-work` set) stayed at the same revision after the settle, and the
;; optimistic-rollback conflict check (`optimistic-conflict?` over `:revision`)
;; could NOT tell the entry had settled in-flight → a later rollback RESTORED
;; the stale in-flight `:before`, RESURRECTING the cancelled / failed
;; `:current-work` pointer over the terminal settled state. The fix: a
;; failure / cancel settle is an authoritative durable write, so it bumps
;; `:revision` — symmetric with `entry-succeeded`.

(deftest entry-failed-bumps-revision-on-a-background-refresh-failure
  (testing "a BACKGROUND-refresh failure (entry was :fetching with prior data)
            returns to :loaded, records :refresh-error, clears :current-work —
            and BUMPS :revision (rf2-mx0w2o): the settle is an authoritative
            durable write a later optimistic rollback could clobber"
    (let [loaded   (-> (state/empty-entry :conduit/article)
                       (state/entry-succeeded {:data {:n 1} :loaded-at 1
                                               :stale-at 2 :tags #{}}))
          ;; a background refetch starts — :fetching, :current-work set; the
          ;; revision is UNMOVED at load start (entry-start-load) — this is the
          ;; revision a snapshot taken here would record.
          fetching (state/entry-start-load
                     loaded {:generation 5 :work-id [:w 1] :request-id "r" :owner :o})
          rec-rev  (state/entry-revision fetching)
          ;; the in-flight refetch FAILS — background failure settle.
          settled  (state/entry-failed fetching {:error {:kind :rf.http/http-5xx}})]
      (is (= :fetching (:status fetching)) "the refetch is in flight")
      (is (= [:w 1] (:current-work fetching)) "with a :current-work pointer")
      (is (= rec-rev (:revision fetching))
          "load START did not move :revision (entry-start-load)")
      (testing "the failure settle is durable + terminal"
        (is (= :loaded (:status settled)) "background failure returns to :loaded")
        (is (= {:kind :rf.http/http-5xx} (:refresh-error settled)))
        (is (nil? (:current-work settled)) ":current-work cleared by the settle"))
      (testing "and it BUMPED :revision (the rf2-mx0w2o fix)"
        (is (= (inc rec-rev) (:revision settled))
            "the failure settle moved the write identity past the snapshot's"))
      (testing "so the optimistic-rollback conflict check now DETECTS the move"
        (is (true? (state/revision-conflict? settled rec-rev))
            "a snapshot recorded at the in-flight revision sees the settle as a
             conflict — the rollback will NOT resurrect the stale :before")))))

(deftest entry-failed-bumps-revision-on-a-first-load-failure
  (testing "a FIRST-load failure (no usable data) settles :error and BUMPS
            :revision (rf2-mx0w2o)"
    (let [loading  (-> (state/empty-entry :conduit/article)
                       (state/entry-start-load
                         {:generation 3 :work-id [:w 2] :request-id "r" :owner :o}))
          rec-rev  (state/entry-revision loading)
          settled  (state/entry-failed loading {:error {:kind :rf.http/http-404}})]
      (is (= :loading (:status loading)) "first load — no usable data yet")
      (is (= :error (:status settled)) "first-load failure → :error")
      (is (nil? (:data settled)))
      (is (nil? (:current-work settled)))
      (is (= (inc rec-rev) (:revision settled))
          ":revision moved on the first-load failure settle")
      (is (true? (state/revision-conflict? settled rec-rev))
          "the conflict check sees the settle"))))

(deftest entry-page-failed-bumps-revision-on-a-load-more-failure
  (testing "a load-more (page N>0) failure keeps the feed, records :page-error,
            clears :current-work — and BUMPS :revision (rf2-mx0w2o)"
    (let [feed     (-> (state/empty-infinite-entry :conduit/feed)
                       (state/entry-append-page {:page [:a :b] :page-param nil
                                                 :loaded-at 1 :stale-at 2}))
          ;; a load-more starts (page 1 fetch) — :fetching, :current-work set.
          fetching (state/entry-start-load
                     feed {:generation 4 :work-id [:w 3] :request-id "r" :owner :o})
          rec-rev  (state/entry-revision fetching)
          settled  (state/entry-page-failed fetching {:error {:kind :rf.http/timeout}})]
      (is (= [[:a :b]] (:data settled)) "the accumulated feed is KEPT")
      (is (= {:kind :rf.http/timeout} (:page-error settled)) ":page-error recorded")
      (is (nil? (:current-work settled)) ":current-work cleared")
      (is (= (inc rec-rev) (:revision settled))
          "the page-failure settle moved :revision (the rf2-mx0w2o fix)")
      (is (true? (state/revision-conflict? settled rec-rev))
          "the conflict check sees the load-more failure settle"))))

(deftest optimistic-rollback-does-not-resurrect-a-settled-in-flight-snapshot
  (testing "THE RESURRECTION REPRO (rf2-mx0w2o): an optimistic apply snapshots an
            IN-FLIGHT entry (the apply PRESERVES the in-flight :current-work — it
            does not clear it), then the still-live in-flight refetch SETTLES
            (background-refresh failure). The rollback disposition must DETECT the
            conflict and :invalidate — NOT :restore the stale in-flight :before
            (which would RESURRECT the in-flight :current-work pointer over the
            settled-failure state)."
    (let [sk       (state/scoped-resource-key :rf.scope/global :conduit/article {:slug "w"})
          loaded   (-> (state/empty-entry :conduit/article sk)
                       (state/entry-succeeded {:data {:n 1} :loaded-at 1
                                               :stale-at 2 :tags #{}}))
          ;; an in-flight background refetch — :fetching, :current-work set.
          fetching (state/entry-start-load
                     loaded {:generation 9 :work-id [:w 7] :request-id "r" :owner :o})
          ;; the OPTIMISTIC APPLY (phase 1.5): snapshot the in-flight entry as
          ;; `:before`, then apply the forward patch — which bumps :revision and
          ;; PRESERVES :current-work (apply-optimistic-patch never clears it). The
          ;; recorded `:applied-revision` is the revision the apply LEFT it at.
          observed (state/entry-revision fetching)
          applied  (mut-rt/apply-optimistic-patch
                     fetching (fn [d] (assoc d :n 99)) :conduit/article
                     {:clock-ms 5 :stale-at 9 :scoped-key sk})
          recorded (mut-rt/record-optimistic-entry
                     sk fetching :patch observed (state/entry-revision applied))
          ;; the in-flight refetch is STILL live after the apply (its
          ;; :current-work survived) → its failure reply settles the entry.
          settled  (state/entry-failed applied {:error {:kind :rf.http/http-5xx}})
          ;; the mutation then fails → settle-time rollback for this recorded key.
          disp     (mut-rt/rollback-entry-disposition settled recorded :invalidate)]
      (testing "the recorded snapshot captured the IN-FLIGHT state (the danger)"
        (is (= :fetching (:status (:before recorded))))
        (is (= [:w 7] (:current-work (:before recorded)))
            "the :before snapshot carries the in-flight :current-work pointer"))
      (testing "the apply preserved :current-work, so the refetch was still live"
        (is (= [:w 7] (:current-work applied))
            "the optimistic apply did NOT clear the in-flight :current-work"))
      (testing "the apply LEFT the entry at applied-revision = observed + 1"
        (is (= (inc observed) (:applied-revision recorded))))
      (testing "the failure settle moved the entry's revision PAST the apply
                baseline (the rf2-mx0w2o fix)"
        (is (= (inc (:applied-revision recorded)) (:revision settled)))
        (is (true? (mut-rt/optimistic-conflict? settled (:applied-revision recorded)))
            "the settle is detected as a competing write since the apply"))
      (testing "so the rollback INVALIDATES — it does NOT restore the stale
                in-flight :before (no resurrection of :current-work)"
        (is (true? (:conflict? disp)) "the disposition flags the conflict")
        (is (= :invalidate (:disposition disp))
            "the default :invalidate rule defers to the read path — the stale
             in-flight :before is NOT restored over the settled failure state")))))

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
