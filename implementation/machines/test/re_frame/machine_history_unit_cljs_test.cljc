(ns re-frame.machine-history-unit-cljs-test
  "UNIT matrix for the first-class history ENGINE — the record/restore
  behaviours Spec 005 §History states and Spec 009 §History trace events
  enumerate. `machine_history_smoke_test` holds the trace-shape pins and the
  owning-compound exit-set cases:

    - SHALLOW vs DEEP at the SAME exit leaf.
    - DEEP NESTING — two history-bearing compounds record independently, keyed
      by their own declaration paths.
    - ENTRY-CASCADE ORDERING during a restore — the standard LCA cascade.
    - The `:initial` fallback when neither a recording nor a `:default-target`
      exists.
    - DANGLING recorded paths after hot-reload fall back, with no error.
    - PER-REGION history at structurally identical region paths.
    - SNAPSHOT REVERT — `:rf/history` is part of the revertible, EDN-printable
      snapshot value.
    - The exit-set boundary: a surviving owner records nothing.

  Named `*-cljs-test` so both the JVM runner and the shadow-cljs `:node-test`
  build run it. Drives the pure `machine-transition` directly."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   #?(:cljs [cljs.reader])
   [re-frame.machines :as rf.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]))

(use-fixtures :each rf.machines.test-support/trace-capture-fixture)

(defn- history-events
  [operation]
  (rf.machines.test-support/events-of operation))

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
;; :inner deep). Spec 005 §Deep nesting.
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
  (testing "exiting :outer records BOTH compounds under their own keys, one :recorded event each,
            and :outer's deep restore returns the full leaf, leaving the inner recording"
    (let [away     (step nested (seed [:outer :b :b2]) [:leave])
          recorded (mapv #(select-keys (:tags %) [:compound-path :kind :recorded-config :prev-config])
                         (history-events :rf.machine.history/recorded))
          back     (step nested away [:return])]
      (is (= [[:away]
              {[:outer] [:outer :b :b2] [:outer :b] [:outer :b :b2]}
              2
              #{{:compound-path [:outer] :kind :deep :recorded-config [:outer :b :b2]}
                {:compound-path [:outer :b] :kind :deep :recorded-config [:outer :b :b2]}}
              [:outer :b :b2]
              [:outer :b :b2]]
             [(:state away) (:rf/history away) (count recorded) (set recorded)
              (:state back) (get-in back [:rf/history [:outer :b]])])))))

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
  (testing "no :default-target ⇒ the OWNING COMPOUND's :initial, traced as :fallback :initial
            with no :restored-config"
    (let [m     {:initial :player
                 :states  {:player {:initial :stopped
                                    :on      {:leave :away}
                                    :states  {:hist    {:type :history :deep? true}
                                              :stopped {}
                                              :playing {:on {:stop :stopped}}}}
                           :away   {:on {:resume [:player :hist]}}}}
          after (step m (seed :away) [:resume])
          ev    (first (history-events :rf.machine.history/restored))]
      (is (= [[:player :stopped] :default {:fallback :initial}]
             [(:state after) (:source ev) (select-keys (:tags ev) [:fallback :restored-config])])))))

;; ===========================================================================
;; §5. DANGLING recorded path after hot-reload
;;
;; A recorded config the (hot-reloaded) definition no longer declares is
;; discarded on restore — the runtime falls back, never enters the dead path.
;; Benign — no :rf.error/*. Both the DEEP-leaf-removed and the SHALLOW-child-
;; removed shapes. Spec 005 §Dangling recorded paths after hot reload.
;; ===========================================================================

(deftest dangling-recorded-path-falls-back-no-error
  (testing "a recorded DEEP leaf or SHALLOW child the definition removed falls back to
            :default-target; no error"
    (doseq [[deep? recorded] [[true [:player :playing :gone]] [false :ghost]]]
      (let [r (rf.machines/machine-transition
                (player deep?)
                (assoc (seed :away) :rf/history {[:player] recorded})
                [:resume])]
        (is (= [:ok [:player :playing :at-start] []]
               [(:status r) (:state (:snapshot r))
                (filterv #(= :error (:op-type %)) (rf.machines.test-support/captured-events))])
            (pr-str recorded))))))

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
;; §8. :prev-config on a second recording (Spec 009 §History trace events)
;; ===========================================================================

(deftest recorded-prev-config-on-second-exit
  (testing ":prev-config names the value overwritten on a second recording for the same compound"
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
;; A history-owning compound records ONLY when it is itself in the EXIT SET
;; (XState v5 / SCXML). A sibling move under the owner leaves it standing as
;; the LCCA, so it records nothing, while a genuinely exited inner owner does.
;; ===========================================================================

(deftest surviving-outer-records-nothing-while-exited-inner-records
  (testing "a sibling move under :outer exits :b (records) but leaves :outer (records nothing)"
    (let [after (step nested (seed [:outer :b :b2]) [:to-a])]
      (is (= [[:outer :a] {[:outer :b] [:outer :b :b2]} [[:outer :b]]]
             [(:state after) (:rf/history after)
              (mapv (comp :compound-path :tags) (history-events :rf.machine.history/recorded))])))))
