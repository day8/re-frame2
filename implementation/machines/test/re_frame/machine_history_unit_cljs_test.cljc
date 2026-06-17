(ns re-frame.machine-history-unit-cljs-test
  "Comprehensive UNIT matrix for the first-class history ENGINE (rf2-mle6e.3,
  merged). The COMPLEMENT of `machine_history_smoke_test` — the smoke proved
  record/restore is wired end-to-end; this suite pins the corners the spec
  (005 §History states + 009 §History trace events) enumerates and that the
  smoke leaves uncovered:

    - SHALLOW vs DEEP depth distinction at the SAME exit leaf (a single shared
      compound, exited from the same deep leaf, restores differently under
      `:deep?` true vs absent).
    - DEEP NESTING — two history-bearing compounds at different depths record
      INDEPENDENTLY (keyed by their own declaration paths) and never interfere;
      the outer's deep restore returns the full leaf, the inner's its own.
    - ENTRY-CASCADE ORDERING during a restore — a restore is the standard
      entry cascade fed a recorded leaf, so it fires exit-deepest-first /
      entry-shallowest-first along the LCA, NOT a bespoke mechanism.
    - DEFAULT-TARGET fallback (no recording) — both with `:default-target`
      declared AND with it absent (the compound's `:initial`).
    - `:initial`-fallback when neither a recording nor a `:default-target`.
    - DANGLING recorded path after hot-reload (rf2-wgfv0) falls back, never
      enters the dead path, no `:rf.error/*`.
    - PER-REGION parallel history at STRUCTURALLY-IDENTICAL region paths —
      region-qualified keys never collide; restoring one region is isolated.
    - SNAPSHOT REVERT (Goal 2) — the `:rf/history` slot is part of the
      revertible snapshot VALUE (not a side-table): re-running the engine from
      an earlier captured snapshot value restores THAT snapshot's history, and
      the slot rides `pr-str` / `read-string` (SSR-serialisation shape).
    - The TRACE shapes (`:rf.machine.history/recorded` / `-restored`) — the
      exact spec/009 tag keys, asserted for the deep-nesting + shallow-vs-deep
      cases the smoke does not reach (two `-recorded` events in one exit
      cascade; the per-step `:source` stamping on a nested restore).

  The namespace is named `*-cljs-test` (file `*_cljs_test.cljc`) so it is
  discovered by BOTH the shadow-cljs `:node-test` build (`:ns-regexp
  \"cljs-test$\"`, run via `npm run test:cljs`) AND the JVM
  cognitect.test-runner (`.*-test$`, run via `clojure -M:test` in
  `implementation/machines`). The history engine is identical across runtimes,
  so the corpus must pass on both — mirroring the artefact's other dual-runtime
  tests (`scxml_conformance_cljs_test.cljc`, `final_state_cljs_test.cljc`).

  Drives the pure `machine-transition` primitive directly — no frame, no
  app-db, no dispatch loop. Every assertion is pure data."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   #?(:cljs [cljs.reader])
   [re-frame.machines :as machines]
   [re-frame.machines.result :as result]
   [re-frame.machines.test-support :as mtest]))

;; ===========================================================================
;; Harness — pure engine + trace capture (shared, rf2-3l8lqe finding #4)
;; ===========================================================================
;;
;; Trace capture (register + guaranteed unregister, CLJ/CLJS-compatible) is
;; the shared `mtest/trace-capture-fixture`; `history-events` / `reset-
;; capture!` are thin wrappers over its `events-of` / `reset-captured!`.

(use-fixtures :each mtest/trace-capture-fixture)

(defn- history-events
  [operation]
  (mtest/events-of operation))

(defn- reset-capture! [] (mtest/reset-captured!))

(defn- step
  "Apply one macrostep; assert it succeeded; return the post snapshot."
  [machine snapshot event]
  (let [r (machines/machine-transition machine snapshot event)]
    (is (result/ok? r) (str "transition ok for event " (pr-str event)))
    (result/snap r)))

(defn- seed [state] {:state state :data {}})

;; A recorder for entry/exit ORDERING assertions: `(mk tag)` is an entry/exit
;; fn appending `tag` to the shared log (in invocation order), returning nil.
(defn- order-recorder []
  (let [log (atom [])]
    [log (fn [tag] (fn [_ctx] (swap! log conj tag) nil))]))

;; ===========================================================================
;; §1. SHALLOW vs DEEP at the SAME exit leaf
;;
;; One shared compound shape exited from the identical deep leaf
;; ([:player :playing :mid-track]) restores DIFFERENTLY under `:deep?` true vs
;; absent — deep returns the exact leaf, shallow returns the recorded direct
;; child descended through its OWN `:initial` chain. This isolates the depth
;; semantic from every other variable. Spec 005 §Recording / §Restoration.
;;
;; The contrast intentionally records `:playing` as `:player`'s SHALLOW direct
;; child, so the owning compound (`:player`) must be GENUINELY EXITED for the
;; shallow recording to fire (the XState v5 / SCXML exit-set rule, rf2-9eidiv).
;; This uses the external-sibling shape (mirroring SCXML test388 `:s0 -> :away`):
;; `:leave` exits the whole `:player` subtree to a top-level sibling `:away`;
;; `:resume` re-enters via `[:player :hist]`.
;; ===========================================================================

(defn- player [deep?]
  {:initial :player
   :states  {:player {:initial :stopped
                      :on      {:leave :away}
                      :states  {:hist    (cond-> {:type :history :default-target :playing}
                                           deep? (assoc :deep? true))
                                :stopped {}
                                :playing {:initial :at-start
                                          :states  {:at-start  {:on {:seek :mid-track}}
                                                    :mid-track {}}}}}
             :away   {:on {:resume [:player :hist]}}}})

(deftest deep-vs-shallow-diverge-from-identical-exit-leaf
  (testing "the SAME exit leaf restores to the exact leaf (deep) vs the child's :initial (shallow)"
    (let [deep-m    (player true)
          shallow-m (player false)
          ;; Identical exit: from :mid-track, :leave exits :player → :away.
          deep-stop    (step deep-m    (seed [:player :playing :mid-track]) [:leave])
          shallow-stop (step shallow-m (seed [:player :playing :mid-track]) [:leave])]
      ;; Recording differs: deep stores the full leaf path; shallow the child kw.
      (is (= [:player :playing :mid-track] (get-in deep-stop    [:rf/history [:player]]))
          "deep records the absolute leaf path")
      (is (= :playing                       (get-in shallow-stop [:rf/history [:player]]))
          "shallow records only the direct-child keyword")
      ;; Restore diverges from the IDENTICAL recorded exit point.
      (is (= [:player :playing :mid-track] (:state (step deep-m    deep-stop    [:resume])))
          "deep restores the EXACT recorded leaf (:mid-track)")
      (is (= [:player :playing :at-start]  (:state (step shallow-m shallow-stop [:resume])))
          "shallow restores the recorded child then its :initial (:at-start), NOT the exit leaf"))))

;; ===========================================================================
;; §2. DEEP NESTING — independent recordings, no interference
;;
;; Two history-bearing compounds at DIFFERENT depths (:outer deep, nested
;; :inner deep). Fully exiting :outer (to a top-level sibling :away) records
;; BOTH compounds — each keyed by its OWN declaration path — and re-entering
;; via :outer's deep history restores the full leaf, with the inner recording
;; untouched. Spec 005 §Deep nesting (the two never interfere).
;; ===========================================================================

(def nested
  {:initial :outer
   :states
   {:outer {:initial :a
            :on      {:leave :away}
            :states  {:a {:on {:to-b :b}}
                      :b {:initial :b1
                          :on      {:to-a :a}
                          :states  {:b1         {:on {:to-b2 :b2}}
                                    :b2         {}
                                    :hist-inner {:type :history :deep? true}}}
                      :hist-outer {:type :history :deep? true}}}
    :away {:on {:return [:outer :hist-outer]}}}})

(deftest deep-nesting-records-each-compound-independently
  (testing "exiting :outer records BOTH the outer and the nested-inner compound, keyed independently"
    (let [away (step nested (seed [:outer :b :b2]) [:leave])]
      (is (= :away (:state away)) "left the whole :outer subtree")
      ;; Two independent recordings, each keyed by its own declaration path.
      (is (= [:outer :b :b2] (get-in away [:rf/history [:outer]]))
          "the outer compound recorded its full deep leaf")
      (is (= [:outer :b :b2] (get-in away [:rf/history [:outer :b]]))
          "the nested :b compound recorded its own deep leaf, under a SEPARATE key")
      (is (= 2 (count (:rf/history away)))
          "exactly two history entries — one per history-bearing compound"))))

(deftest deep-nesting-outer-restore-returns-full-leaf
  (testing "re-entering via :outer's deep history restores the full recorded leaf"
    (let [away (step nested (seed [:outer :b :b2]) [:leave])
          back (step nested away [:return])]
      (is (= [:outer :b :b2] (:state back))
          "the outer deep history restored the exact leaf across the full subtree")
      ;; The inner recording is untouched by the outer restore.
      (is (= [:outer :b :b2] (get-in back [:rf/history [:outer :b]]))
          "the inner compound's recording is unaffected by the outer restore"))))

;; ===========================================================================
;; §3. ENTRY-CASCADE ORDERING during a restore
;;
;; A restore is the STANDARD entry cascade fed a recorded leaf (not a bespoke
;; mechanism). With entry/exit recorders on every level, a deep restore from
;; :stopped → :mid-track fires exit-deepest-first then entry-shallowest-first
;; along the LCA (:player), which is neither exited nor re-entered. Spec 005
;; §Composition with the LCA, entry/exit cascade.
;; ===========================================================================

(deftest restore-feeds-the-standard-lca-entry-cascade-in-order
  (testing "a deep restore fires exit-deepest-first / entry-shallowest-first along the LCA"
    (let [[log mk] (order-recorder)
          ;; :playing owns the deep history (the compound :stop genuinely
          ;; exits), so its last-active leaf records; :play re-enters via
          ;; [:player :playing :hist].
          m {:initial :player
             :states
             {:player {:initial :stopped
                       :entry   (mk :en-player) :exit (mk :ex-player)
                       :states
                       {:stopped {:entry (mk :en-stopped) :exit (mk :ex-stopped)
                                  :on    {:play [:player :playing :hist]}}
                        :playing {:initial :at-start
                                  :entry   (mk :en-playing) :exit (mk :ex-playing)
                                  :on      {:stop [:player :stopped]}
                                  :states  {:hist      {:type :history :deep? true :default-target :at-start}
                                            :at-start  {:entry (mk :en-at-start) :exit (mk :ex-at-start)
                                                        :on    {:seek :mid-track}}
                                            :mid-track {:entry (mk :en-mid) :exit (mk :ex-mid)
                                                        :on    {:stop [:player :stopped]}}}}}}}}
          ;; Record :mid-track by stopping from it (exits :playing); then restore.
          after-stop (step m (seed [:player :playing :mid-track]) [:stop])]
      (reset! log [])
      (let [restored (step m after-stop [:play])]
        (is (= [:player :playing :mid-track] (:state restored)))
        ;; LCA is :player — neither exited nor re-entered. Exit :stopped
        ;; (the source leaf below the LCA), then enter :playing (shallowest)
        ;; then :mid-track (deepest). The :initial of :playing (:at-start) is
        ;; NOT descended — the deep recorded leaf overrides it.
        (is (= [:ex-stopped :en-playing :en-mid] @log)
            "exit source leaf, then entry shallowest-first to the recorded deep leaf; LCA :player untouched")))))

;; ===========================================================================
;; §4. DEFAULT-TARGET / :initial fallback (no usable recording)
;; ===========================================================================

(deftest first-entry-no-recording-uses-default-target
  (testing "nothing recorded ⇒ the pseudo-state's :default-target (descended to its :initial)"
    (let [m (player true)
          ;; First entry into :player (from :away) via the history pseudo-state,
          ;; nothing recorded ⇒ :default-target :playing → :at-start.
          restored (step m (seed :away) [:resume])]
      (is (= [:player :playing :at-start] (:state restored))
          ":default-target :playing descended to its :initial :at-start"))))

(deftest first-entry-no-default-target-falls-back-to-initial
  (testing "no :default-target ⇒ the OWNING COMPOUND's :initial"
    ;; :player owns history with NO :default-target and is entered (from :away)
    ;; via the pseudo-state on first entry ⇒ falls back to :player's :initial.
    (let [m {:initial :player
             :states  {:player {:initial :stopped
                                :on      {:leave :away}
                                :states  {:hist    {:type :history :deep? true}
                                          :stopped {}
                                          :playing {:on {:stop :stopped}}}}
                       :away   {:on {:resume [:player :hist]}}}}
          restored (step m (seed :away) [:resume])]
      (is (= [:player :stopped] (:state restored))
          "no :default-target ⇒ :player's :initial (:stopped)"))))

(deftest absent-deep-key-is-shallow
  (testing "a missing :deep? reads as shallow (records the direct child, descends :initial)"
    ;; player(false) has no :deep? at all — proves missing ≡ shallow. :leave
    ;; exits :player (the owner) so the shallow recording fires.
    (let [m (player false)
          after-leave (step m (seed [:player :playing :mid-track]) [:leave])]
      (is (= :playing (get-in after-leave [:rf/history [:player]]))
          "missing :deep? records the direct child (shallow)"))))

;; ===========================================================================
;; §5. DANGLING recorded path after hot-reload (rf2-wgfv0)
;;
;; A recorded config the (hot-reloaded) definition no longer declares is
;; discarded on restore — the runtime falls back, never enters the dead path.
;; Benign — no :rf.error/*. Both the DEEP-leaf-removed and the SHALLOW-child-
;; removed shapes. Spec 005 §Dangling recorded paths after hot reload.
;; ===========================================================================

(deftest dangling-deep-leaf-falls-back-no-error
  (testing "a recorded DEEP leaf the definition removed falls back to :default-target; no error"
    (let [m    (player true)
          snap (assoc (seed :away)
                      :rf/history {[:player] [:player :playing :gone]})
          r    (machines/machine-transition m snap [:resume])]
      (is (result/ok? r) "dangling deep path is benign — no failure Result")
      (is (= [:player :playing :at-start] (:state (result/snap r)))
          "discarded the dead leaf ⇒ fell back to :default-target → :at-start")
      (is (empty? (filterv #(= :error (:op-type %)) (mtest/captured-events)))
          "no :rf.error/* trace for a dangling-at-runtime recording"))))

(deftest dangling-shallow-child-falls-back-no-error
  (testing "a recorded SHALLOW child the definition removed falls back; no error"
    (let [m    (player false)
          ;; :ghost is not a child of :player in the current definition.
          snap (assoc (seed :away) :rf/history {[:player] :ghost})
          r    (machines/machine-transition m snap [:resume])]
      (is (result/ok? r) "dangling shallow child is benign")
      (is (= [:player :playing :at-start] (:state (result/snap r)))
          "discarded the dead child ⇒ fell back to :default-target → :at-start")
      (is (empty? (filterv #(= :error (:op-type %)) (mtest/captured-events)))
          "no :rf.error/* for a dangling shallow child"))))

;; ===========================================================================
;; §6. PER-REGION parallel history at STRUCTURALLY-IDENTICAL paths
;;
;; Two regions whose compounds sit at structurally-identical within-region
;; paths ([:group]) record under REGION-QUALIFIED keys ([:left :group] /
;; [:right :group]) that never collide. Restoring one region is isolated.
;; Spec 005 §Composition with parallel regions — per-region history.
;; ===========================================================================

;; The history-owning compound is `:on` (the dim/bright compound), which
;; :turn-off genuinely EXITS to its sibling :off — so its last-active leaf
;; records (the exit-set rule); :turn-on re-enters via [:group :on :hist].
(defn- region []
  {:initial :group
   :states  {:group {:initial :off
                     :states  {:off {:on {:turn-on [:group :on :hist]}}
                               :on  {:initial :dim
                                     :on      {:turn-off [:group :off]}
                                     :states  {:hist   {:type :history :deep? true}
                                               :dim    {:on {:brighten :bright}}
                                               :bright {:on {:turn-off [:group :off]}}}}}}}})

(def parallel-history
  {:type    :parallel
   :regions {:left (region) :right (region)}})

(deftest parallel-region-keys-never-collide-at-identical-paths
  (testing "structurally-identical region compounds record under distinct region-qualified keys"
    (let [snap0 {:state {:left [:group :on :bright] :right [:group :on :dim]} :data {}}
          ;; Broadcast :turn-off to both regions; each records its own config.
          off   (step parallel-history snap0 [:turn-off])]
      (is (= {:left [:group :off] :right [:group :off]} (:state off))
          "both regions turned off")
      (is (= [:group :on :bright] (get-in off [:rf/history [:left :group :on]]))
          ":left recorded under [:left :group :on]")
      (is (= [:group :on :dim]    (get-in off [:rf/history [:right :group :on]]))
          ":right recorded under [:right :group :on] — no collision despite identical structure")
      (is (= 2 (count (:rf/history off))) "two distinct region-qualified entries"))))

(deftest parallel-restore-one-region-leaves-sibling-untouched
  (testing "restoring history in one region is isolated from siblings"
    (let [snap0 {:state {:left [:group :on :bright] :right [:group :on :dim]} :data {}}
          off   (step parallel-history snap0 [:turn-off])
          ;; turn-on is on the :off leaf of BOTH regions — but each region only
          ;; restores its OWN recorded config; the broadcast restores both.
          ;; To isolate, drive a turn-on that only :left handles we cannot
          ;; (same key) — so assert per-region independence via the recorded
          ;; keys: re-entering restores each region to ITS OWN recorded leaf.
          back  (step parallel-history off [:turn-on])]
      (is (= [:group :on :bright] (get-in back [:state :left]))
          ":left restored ITS recorded deep leaf (:bright)")
      (is (= [:group :on :dim]    (get-in back [:state :right]))
          ":right restored ITS OWN recorded deep leaf (:dim) — not :left's"))))

;; ===========================================================================
;; §7. SNAPSHOT REVERT (Goal 2) — :rf/history is part of the revertible VALUE
;;
;; The spec's load-bearing claim: history rides revertibility FOR FREE because
;; the recording is part of the snapshot VALUE, not a side-table. Proven
;; structurally: capture snapshot S1 (history H1), advance to S2 (history H2);
;; re-running the engine from the EARLIER captured value S1 resolves against
;; H1 — exactly what restore-epoch! does when it rewinds the snapshot value.
;; Plus the EDN round-trip (pr-str / read-string) the SSR-serialisation path
;; and time-axis both ride.
;; ===========================================================================

(deftest history-slot-is-part-of-the-revertible-snapshot-value
  (testing "re-running from an earlier captured snapshot value restores THAT value's history"
    (let [m  (player true)
          ;; Two captured snapshot values carrying DIFFERENT recorded leaves:
          ;; S1 exits :player from :mid-track (records :mid-track); S2 exits
          ;; from :at-start (records :at-start).
          s1 (step m (seed [:player :playing :mid-track]) [:leave])
          s2 (step m (seed [:player :playing :at-start])  [:leave])]
      ;; H1 ≠ H2 — the two captured values carry DIFFERENT recorded leaves.
      (is (= [:player :playing :mid-track] (get-in s1  [:rf/history [:player]])) "H1 = :mid-track")
      (is (= [:player :playing :at-start]  (get-in s2  [:rf/history [:player]])) "H2 = :at-start")
      ;; The load-bearing assertion: each captured value is self-contained.
      ;; "Reverting" = re-using the earlier value as the engine's input — no
      ;; external history side-table is consulted, so a restore off S1 resolves
      ;; H1 and a restore off S2 resolves H2, totally independently. This is
      ;; exactly what restore-epoch! does when it rewinds the snapshot value.
      (is (= [:player :playing :mid-track] (:state (step m s1 [:resume])))
          "restore off the reverted S1 value resolves S1's recorded leaf (H1)")
      (is (= [:player :playing :at-start]  (:state (step m s2 [:resume])))
          "restore off S2 resolves S2's recorded leaf (H2) — histories revert with their values"))))

(deftest history-slot-edn-round-trips
  (testing ":rf/history survives pr-str / read-string (SSR-serialisation + time-axis shape)"
    (let [m   (player true)
          s1  (step m (seed [:player :playing :mid-track]) [:leave])
          ;; The whole snapshot (incl. :rf/history) round-trips =-equal.
          rt  #?(:clj  (read-string (pr-str s1))
                 :cljs (cljs.reader/read-string (pr-str s1)))]
      (is (= s1 rt) "snapshot incl. :rf/history round-trips =-equal")
      ;; And a restore off the round-tripped value resolves the recorded leaf.
      (is (= [:player :playing :mid-track] (:state (step m rt [:resume])))
          "restore works off a round-tripped snapshot (server→client / epoch replay)"))))

;; ===========================================================================
;; §8. TRACE shapes — the corners the smoke does not reach
;;
;; Spec/009 §History trace events: the deep-nesting exit emits TWO
;; `:rf.machine.history/recorded` events (one per history-bearing compound) in
;; one cascade; a nested deep restore stamps `:source :recorded` on the
;; history-driven :entry cascade steps. Asserts the exact tag keys.
;; ===========================================================================

(deftest deep-nesting-emits-one-recorded-per-compound
  (testing "exiting two history-bearing compounds emits two :rf.machine.history/recorded events"
    (reset-capture!)
    (step nested (seed [:outer :b :b2]) [:leave])
    (let [recs (history-events :rf.machine.history/recorded)
          by-path (into {} (map (juxt #(:compound-path (:tags %)) :tags)) recs)]
      (is (= 2 (count recs)) "two recorded events — one per history-bearing compound")
      (is (contains? by-path [:outer])    "the outer compound recorded")
      (is (contains? by-path [:outer :b]) "the nested-inner compound recorded")
      ;; Both deep, both first-ever (no :prev-config), both full leaf paths.
      (doseq [[path tags] by-path]
        (is (= :deep (:kind tags)) (str path " recorded :kind :deep"))
        (is (= [:outer :b :b2] (:recorded-config tags))
            (str path " recorded the full deep leaf"))
        (is (not (contains? tags :prev-config))
            (str path " :prev-config absent on the first-ever recording"))))))

(deftest nested-restore-stamps-source-on-entry-steps
  (testing "the history-driven :entry steps of a nested deep restore carry :source :recorded"
    (let [away (step nested (seed [:outer :b :b2]) [:leave])]
      (reset-capture!)
      (let [r       (machines/machine-transition nested away [:return])
            cascade (result/cascade r)
            entries (filterv #(= :entry (:kind %)) cascade)
            restored (history-events :rf.machine.history/restored)]
        (is (result/ok? r))
        (is (= 1 (count restored)) "one restored event for the outer history re-entry")
        (is (= :recorded (:source (first restored))) ":source :recorded (hoisted to envelope)")
        (is (= [:outer] (:compound-path (:tags (first restored))))
            ":compound-path is the OUTER compound's declaration path")
        (is (seq entries) "the restore produced entry steps")
        (is (every? #(= :recorded (:source %)) entries)
            "every history-driven :entry step carries :source :recorded")))))

(deftest recorded-prev-config-on-second-exit
  (testing ":prev-config names the value overwritten on a second recording for the same compound"
    ;; Seed an already-allocated slot (as a prior exit would leave it), then
    ;; exit from a DIFFERENT leaf — the new -recorded event reports :prev-config
    ;; = the seeded value it overwrote.
    (let [m     (player true)
          snap0 (assoc (seed [:player :playing :at-start])
                       :rf/history {[:player] [:player :playing :mid-track]})]
      (reset-capture!)
      (step m snap0 [:leave])
      (let [tags (:tags (first (history-events :rf.machine.history/recorded)))]
        (is (= [:player :playing :mid-track] (:prev-config tags))
            ":prev-config = the value the slot held before this write")
        (is (= [:player :playing :at-start] (:recorded-config tags))
            ":recorded-config = the value written by this exit")))))

;; ===========================================================================
;; §9. EXIT-SET BOUNDARY (rf2-9eidiv) — the surviving-LCCA owner records NOTHING
;;
;; The decisive regression for the XState v5 / SCXML alignment: a history-
;; owning compound records ONLY when it is itself in the EXIT SET. A pure
;; WITHIN-compound sibling move — where the owner SURVIVES as the LCCA — is
;; the OLD `<=` (active-child-subtree-teardown) trigger that the strict `<`
;; gate retires. These pin the new boundary on BOTH a flat single-machine
;; chart AND a nested chart (where the surviving OUTER owner records nothing
;; while a genuinely-exited INNER owner still does), so it can never silently
;; slip back to `<=`.
;; ===========================================================================

;; `:player` owns deep history; `:swap` moves between its two children
;; (:playing ↔ :stopped). `:player` is the LCA of that move — it SURVIVES —
;; so under the exit-set rule it records nothing.
(def surviving-owner
  {:initial :player
   :states  {:player {:initial :stopped
                      :states  {:hist    {:type :history :deep? true :default-target :stopped}
                                :stopped {:on {:swap [:player :playing]}}
                                :playing {:initial :at-start
                                          :on      {:swap [:player :stopped]}
                                          :states  {:at-start  {:on {:seek :mid-track}}
                                                    :mid-track {}}}}}}})

(deftest within-compound-sibling-move-records-nothing
  (testing "a within-compound sibling move (surviving LCCA — the old <= trigger) records NOTHING"
    (reset-capture!)
    ;; :swap from [:player :playing :mid-track] → [:player :stopped] keeps
    ;; :player as the surviving LCA: it is NOT exited, so nothing records.
    (let [after (step surviving-owner (seed [:player :playing :mid-track]) [:swap])]
      (is (= [:player :stopped] (:state after)) "moved between :player's children")
      (is (nil? (:rf/history after))
          "the surviving-LCCA owner recorded NOTHING — slot left untouched")
      (is (empty? (history-events :rf.machine.history/recorded))
          "no :rf.machine.history/recorded event for a pure within-compound sibling move"))))

(deftest surviving-outer-records-nothing-while-exited-inner-records
  (testing "the exit-set boundary in a NESTED chart: a sibling move under :outer exits :b (records) but leaves :outer (records nothing)"
    (reset-capture!)
    ;; :to-a moves :b → its sibling :a (both children of :outer). :outer is
    ;; the surviving LCA — records nothing — but :b IS in the exit set and
    ;; records its last-active deep leaf.
    (let [after (step nested (seed [:outer :b :b2]) [:to-a])]
      (is (= [:outer :a] (:state after)) "moved to :outer's sibling child :a")
      (is (not (contains? (:rf/history after) [:outer]))
          "the surviving :outer owner recorded NOTHING")
      (is (= [:outer :b :b2] (get-in after [:rf/history [:outer :b]]))
          "the genuinely-exited :b owner DID record its last-active deep leaf")
      (is (= 1 (count (:rf/history after)))
          "exactly one recording — only the exited inner compound, not the surviving outer")
      (let [recs (history-events :rf.machine.history/recorded)]
        (is (= 1 (count recs)) "exactly one :recorded event (the exited inner compound)")
        (is (= [:outer :b] (:compound-path (:tags (first recs))))
            "the lone recording is the exited :b, not the surviving :outer")))))
