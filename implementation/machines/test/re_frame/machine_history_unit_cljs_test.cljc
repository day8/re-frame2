(ns re-frame.machine-history-unit-cljs-test
  "Comprehensive UNIT matrix for the first-class history ENGINE — the
  record/restore behaviours the spec (005 §History states + 009 §History
  trace events) enumerates. `machine_history_smoke_test` holds the
  single-machine trace-shape pins, the one-region restore and the
  owning-compound exit-set cases:

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
    - `:initial`-fallback when neither a recording nor a `:default-target`
      (a missing `:deep?` reads as shallow — the §1 contrast's shallow arm
      declares none).
    - DANGLING recorded path after hot-reload falls back, never enters the
      dead path, no `:rf.error/*`.
    - PER-REGION parallel history at STRUCTURALLY-IDENTICAL region paths —
      region-qualified keys never collide; each region restores its own
      recording.
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
   [re-frame.machines :as rf.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]))

;; ===========================================================================
;; Harness — pure engine + trace capture (shared)
;; ===========================================================================
;;
;; Trace capture (register + guaranteed unregister, CLJ/CLJS-compatible) is
;; the shared `rf.machines.test-support/trace-capture-fixture`; `history-events` / `reset-
;; capture!` are thin wrappers over its `events-of` / `reset-captured!`.

(use-fixtures :each rf.machines.test-support/trace-capture-fixture)

(defn- history-events
  [operation]
  (rf.machines.test-support/events-of operation))

(defn- reset-capture! [] (rf.machines.test-support/reset-captured!))

(defn- step
  "Apply one macrostep; assert it succeeded; return the post snapshot."
  [machine snapshot event]
  (let [r (rf.machines/machine-transition machine snapshot event)]
    (is (= :ok (:status r)) (str "transition ok for event " (pr-str event)))
    (:snapshot r)))

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
;; shallow recording to fire (the XState v5 / SCXML exit-set rule).
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
  (testing "the SAME exit leaf records the absolute leaf (deep) vs the direct child (shallow),
            and restores the exact leaf vs the child's :initial"
    (let [deep-m       (player true)
          shallow-m    (player false)
          deep-stop    (step deep-m    (seed [:player :playing :mid-track]) [:leave])
          shallow-stop (step shallow-m (seed [:player :playing :mid-track]) [:leave])]
      (is (= [[:player :playing :mid-track] :playing
              [:player :playing :mid-track] [:player :playing :at-start]]
             [(get-in deep-stop [:rf/history [:player]])
              (get-in shallow-stop [:rf/history [:player]])
              (:state (step deep-m deep-stop [:resume]))
              (:state (step shallow-m shallow-stop [:resume]))])))))

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
      (is (= [[:away] {[:outer] [:outer :b :b2] [:outer :b] [:outer :b :b2]}]
             [(:state away) (:rf/history away)])))))

(deftest deep-nesting-outer-restore-returns-full-leaf
  (testing "re-entering via :outer's deep history restores the full recorded leaf, leaving the inner recording"
    (let [back (step nested (step nested (seed [:outer :b :b2]) [:leave]) [:return])]
      (is (= [[:outer :b :b2] [:outer :b :b2]]
             [(:state back) (get-in back [:rf/history [:outer :b]])])))))

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
          after-stop (step m (seed [:player :playing :mid-track]) [:stop])]
      (reset! log [])
      (is (= [[:player :playing :mid-track] [:ex-stopped :en-playing :en-mid]]
             [(:state (step m after-stop [:play])) @log])))))

;; ===========================================================================
;; §4. DEFAULT-TARGET / :initial fallback (no usable recording)
;; ===========================================================================

(deftest first-entry-no-default-target-falls-back-to-initial
  (testing "no :default-target ⇒ the OWNING COMPOUND's :initial"
    (let [m {:initial :player
             :states  {:player {:initial :stopped
                                :on      {:leave :away}
                                :states  {:hist    {:type :history :deep? true}
                                          :stopped {}
                                          :playing {:on {:stop :stopped}}}}
                       :away   {:on {:resume [:player :hist]}}}}]
      (is (= [:player :stopped] (:state (step m (seed :away) [:resume])))))))

;; ===========================================================================
;; §5. DANGLING recorded path after hot-reload
;;
;; A recorded config the (hot-reloaded) definition no longer declares is
;; discarded on restore — the runtime falls back, never enters the dead path.
;; Benign — no :rf.error/*. Both the DEEP-leaf-removed and the SHALLOW-child-
;; removed shapes. Spec 005 §Dangling recorded paths after hot reload.
;; ===========================================================================

(deftest dangling-deep-leaf-falls-back-no-error
  (testing "a recorded DEEP leaf the definition removed falls back to :default-target; no error"
    (let [r (rf.machines/machine-transition
              (player true)
              (assoc (seed :away) :rf/history {[:player] [:player :playing :gone]})
              [:resume])]
      (is (= [:ok [:player :playing :at-start] []]
             [(:status r) (:state (:snapshot r))
              (filterv #(= :error (:op-type %)) (rf.machines.test-support/captured-events))])))))

(deftest dangling-shallow-child-falls-back-no-error
  (testing "a recorded SHALLOW child the definition removed falls back; no error"
    (let [r (rf.machines/machine-transition
              (player false)
              (assoc (seed :away) :rf/history {[:player] :ghost})
              [:resume])]
      (is (= [:ok [:player :playing :at-start] []]
             [(:status r) (:state (:snapshot r))
              (filterv #(= :error (:op-type %)) (rf.machines.test-support/captured-events))])))))

;; ===========================================================================
;; §6. PER-REGION parallel history at STRUCTURALLY-IDENTICAL paths
;;
;; Two regions whose compounds sit at structurally-identical within-region
;; paths ([:group]) record under REGION-QUALIFIED keys ([:left :group] /
;; [:right :group]) that never collide, and each region restores its own
;; recording.
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

(deftest parallel-restore-resolves-each-regions-own-recording
  (testing "structurally identical regions record separately, and a broadcast restore returns each to ITS OWN leaf"
    (let [off  (step parallel-history {:state {:left [:group :on :bright] :right [:group :on :dim]} :data {}}
                     [:turn-off])
          back (step parallel-history off [:turn-on])]
      (is (= [{:left [:group :off] :right [:group :off]}
              {:left [:group :on :bright] :right [:group :on :dim]}]
             [(:state off) (:state back)])))))

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
          s1 (step m (seed [:player :playing :mid-track]) [:leave])
          s2 (step m (seed [:player :playing :at-start])  [:leave])]
      (is (= [[:player :playing :mid-track] [:player :playing :at-start]]
             [(:state (step m s1 [:resume])) (:state (step m s2 [:resume]))])))))

(deftest history-slot-edn-round-trips
  (testing ":rf/history survives pr-str / read-string, and a restore works off the round-tripped snapshot"
    (let [m  (player true)
          s1 (step m (seed [:player :playing :mid-track]) [:leave])
          rt #?(:clj  (read-string (pr-str s1))
                :cljs (cljs.reader/read-string (pr-str s1)))]
      (is (= [s1 [:player :playing :mid-track]] [rt (:state (step m rt [:resume]))])))))

;; ===========================================================================
;; §8. TRACE shapes — the corners the smoke does not reach
;;
;; Spec/009 §History trace events: the deep-nesting exit emits TWO
;; `:rf.machine.history/recorded` events (one per history-bearing compound) in
;; one cascade; a nested deep restore stamps `:source :recorded` on the
;; history-driven :entry cascade steps. Asserts the exact tag keys.
;; ===========================================================================

(deftest deep-nesting-emits-one-recorded-per-compound
  (testing "exiting two history-bearing compounds emits one :recorded event each"
    (reset-capture!)
    (step nested (seed [:outer :b :b2]) [:leave])
    (is (= #{{:compound-path [:outer] :kind :deep :recorded-config [:outer :b :b2]}
             {:compound-path [:outer :b] :kind :deep :recorded-config [:outer :b :b2]}}
           (set (map #(select-keys (:tags %) [:compound-path :kind :recorded-config :prev-config])
                     (history-events :rf.machine.history/recorded)))))
    (is (= 2 (count (history-events :rf.machine.history/recorded))))))

(deftest recorded-prev-config-on-second-exit
  (testing ":prev-config names the value overwritten on a second recording for the same compound"
    (reset-capture!)
    (step (player true)
          (assoc (seed [:player :playing :at-start])
                 :rf/history {[:player] [:player :playing :mid-track]})
          [:leave])
    (is (= {:prev-config [:player :playing :mid-track] :recorded-config [:player :playing :at-start]}
           (select-keys (:tags (first (history-events :rf.machine.history/recorded)))
                        [:prev-config :recorded-config])))))

;; ===========================================================================
;; §9. EXIT-SET BOUNDARY — the surviving-LCCA owner records NOTHING
;;
;; The decisive case for the XState v5 / SCXML alignment: a history-owning
;; compound records ONLY when it is itself in the EXIT SET (the strict `<`
;; gate). A pure WITHIN-compound sibling move — where the owner SURVIVES as
;; the LCCA — records nothing. These pin the boundary on BOTH a flat
;; single-machine chart AND a nested chart (where the surviving OUTER owner
;; records nothing while a genuinely-exited INNER owner still does), so it can
;; never silently slip to an active-child-subtree-teardown (`<=`) trigger.
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
  (testing "a within-compound sibling move (surviving LCCA) records NOTHING"
    (reset-capture!)
    (let [after (step surviving-owner (seed [:player :playing :mid-track]) [:swap])]
      (is (= [[:player :stopped] nil []]
             [(:state after) (:rf/history after) (history-events :rf.machine.history/recorded)])))))

(deftest surviving-outer-records-nothing-while-exited-inner-records
  (testing "a sibling move under :outer exits :b (records) but leaves :outer (records nothing)"
    (reset-capture!)
    (let [after (step nested (seed [:outer :b :b2]) [:to-a])]
      (is (= [[:outer :a] {[:outer :b] [:outer :b :b2]} [[:outer :b]]]
             [(:state after) (:rf/history after)
              (mapv (comp :compound-path :tags) (history-events :rf.machine.history/recorded))])))))
