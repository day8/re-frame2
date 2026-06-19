(ns re-frame.birth-always-cljs-test
  "Per rf2-505ic (xstate-v5 / SCXML parity) — the machine's INITIAL
  MACROSTEP is initial-entry + the eventless (`:always`) + raise settle.
  After the initial-entry cascade builds the birth snapshot, the SAME
  raise-drain + `:always` fixed-point loop the event macrostep uses runs
  BEFORE the birth commit, so a transient initial leaf whose `:always`
  guard already holds is settled past — UNOBSERVED — on start, exactly as
  `createActor(m).start()` does in XState v5 (SCXML §3.13: after the
  initial configuration is entered the processor immediately runs the
  eventless microstep loop to quiescence, before any external event).

  NOTE — the namespace is named `*-cljs-test` (file `*_cljs_test.cljc`) so
  it is discovered by BOTH the shadow-cljs `:node-test` build
  (`npm run test:cljs`) AND the JVM cognitect.test-runner (`clojure -M:test`
  in `implementation/machines`). The engine + birth path are identical
  across runtimes, so the corpus must pass on both. Mirrors the established
  dual-runtime convention (`scxml_conformance_cljs_test.cljc` et al.).

  Two layers of coverage:

   - PURE — drives `parallel/apply-initial-entry-cascade` directly (the
     birth site for BOTH paths; runtime-free, deterministic, JVM- and
     CLJS-runnable from arguments alone). This is the SCXML-conformance-
     style proof of the birth-eventless settle (mirrors W3C test 372/388
     eventless-on-entry).
   - LIVE — drives the full lifecycle handler via `rf/reg-machine` +
     `rf/dispatch-sync`, exercising BOTH birth triggers: the eager
     `[:rf.machine/start]` kick (`createActor(m).start()` equivalent) and
     the lazy first-real-event birth.

  Cases (per the bead):
   (a) transient initial leaf whose `:always` guard holds → eager start
       settles past it (the initial leaf is never externally observed).
   (b) same on lazy first-event birth.
   (c) `:always` guard FALSE at birth → stays in the initial leaf.
   (d) compound initial cascade where a deep initial leaf has an `:always`.
   (e) parallel — each region's birth `:always` settles independently.
   (f) no-`:always` machines unaffected (birth identical to before)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines.parallel :as parallel]
   [re-frame.machines.result :as result]
   [re-frame.machines.test-support :as mtest]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate-adapter]
      :cljs [re-frame.adapter.reagent :as substrate-adapter])))

;; ===========================================================================
;; PURE — birth eventless settle via `apply-initial-entry-cascade`
;; ===========================================================================
;;
;; `apply-initial-entry-cascade` is the machine's single birth site; it now
;; composes the initial-entry cascade with `settle-birth` (the raise-drain +
;; `:always` fixed-point). `boot` builds the freshly-synthesised initial
;; snapshot via `build-initial-snapshot` (the same source-of-truth the
;; registration + spawn paths use), runs the birth macrostep, and returns
;; the settled `{:state :data}`.

(defn- boot
  "Birth `machine` from arguments alone — build its initial snapshot, run
  the initial macrostep (`apply-initial-entry-cascade`), and return
  `{:state :data}` from the settled snapshot. Asserts the Result is `:ok`."
  [machine]
  (let [initial (parallel/build-initial-snapshot machine {:bootstrap-pending? false})
        r       (parallel/apply-initial-entry-cascade machine initial)]
    (is (result/ok? r) "birth macrostep succeeds")
    (let [snap (result/snap r)]
      {:state (:state snap) :data (:data snap)})))

(defn- boot-result
  "Like `boot` but returns `{:state :data :fx}` so a caller can assert on
  the OUTBOUND effect vector (e.g. that an initial-`:entry` `:raise` drained
  internally and left no reserved `:raise` fx)."
  [machine]
  (let [initial (parallel/build-initial-snapshot machine {:bootstrap-pending? false})
        r       (parallel/apply-initial-entry-cascade machine initial)]
    (is (result/ok? r) "birth macrostep succeeds")
    (let [snap (result/snap r)]
      {:state (:state snap) :data (:data snap) :fx (result/fx r)})))

(defn- raise-fx?
  "True iff `fx` carries any reserved `[:raise ...]` entry (which would have
  escaped the machine-local internal-event queue to the global fx layer)."
  [fx]
  (boolean (some (fn [[fx-id]] (= :raise fx-id)) fx)))

;; ---- bz0ox.1 — birth-time `:entry` `:raise` drains INSIDE the macrostep ----
;;
;; XState v5 / SCXML §3.13: the initial macrostep runs initial-entry THEN
;; the internal-event-queue drain. A `raise` emitted by an initial `:entry`
;; enters the machine's ONE internal queue and is consumed before quiescence
;; — it never surfaces as an outbound (reserved) effect. Before the fix
;; `settle-birth` seeded the drain with an EMPTY fx vector, so an initial
;; `:entry`'s `[:raise ...]` was concatenated onto the OUTBOUND fx instead of
;; draining: the birth snapshot stuck at the pre-raise leaf and a reserved
;; `:raise` fx escaped to the global layer (→ `:rf.error/no-such-fx`).

(deftest pure-birth-entry-raise-drains-flat
  (testing "(bz0ox.1) a flat machine whose initial `:entry` raises `[:go]`
            settles to the raised-event target in ONE birth macrostep, and
            the outbound fx carries NO reserved `:raise`"
    (let [m {:initial :a
             :data    {}
             :states  {:a {:entry (fn [_] {:fx [[:raise [:go]]]})
                           :on    {:go :b}}
                       :b {}}}
          {:keys [state fx]} (boot-result m)]
      (is (= :b state)
          "birth drained the initial-`:entry` `:raise` and settled to :b")
      (is (not (raise-fx? fx))
          "no reserved `:raise` escaped to the outbound fx layer"))))

(deftest pure-birth-entry-raise-drains-compound
  (testing "(bz0ox.1) a COMPOUND machine whose deep initial leaf's `:entry`
            raises settles past it on birth — the raise re-enters the
            macrostep queue, the enclosing `:on` takes the transition"
    (let [m {:initial :outer
             :data    {}
             :states  {:outer {:initial :inner
                               :states  {:inner {:entry (fn [_] {:fx [[:raise [:advance]]]})
                                                 :on    {:advance :landed}}
                                         :landed {}}}}}
          {:keys [state fx]} (boot-result m)]
      (is (= [:outer :landed] state)
          "birth drained :inner's `:entry` `:raise` and settled to [:outer :landed]")
      (is (not (raise-fx? fx))
          "no reserved `:raise` escaped to the outbound fx layer"))))

(deftest pure-birth-entry-raise-preserves-nonraise-fx-ordering
  (testing "(bz0ox.1) the initial-`:entry`'s NON-raise fx survive the drain in
            order — only the `:raise` is consumed; real effects flow out"
    (let [m {:initial :a
             :data    {}
             :states  {:a {:entry (fn [_] {:fx [[:log :before] [:raise [:go]] [:log :after]]})
                           :on    {:go :b}}
                       :b {:entry (fn [_] {:fx [[:log :in-b]]})}}}
          {:keys [state fx]} (boot-result m)]
      (is (= :b state) "settled to the raised target :b")
      (is (not (raise-fx? fx)) "the `:raise` drained — no reserved fx escaped")
      (is (= #{[:log :before] [:log :after] [:log :in-b]} (set fx))
          "every non-raise effect (entry's :before/:after + :b's entry :in-b) flows out")
      (is (= [:log :before] (first fx))
          "the entry fx emitted before the `:raise` keep their leading position"))))

(deftest pure-birth-entry-raise-drains-parallel-region
  (testing "(bz0ox.1) a PARALLEL machine: a region's initial `:entry` `:raise`
            is NOT region-local — it re-enters the parent macrostep queue and
            re-broadcasts across EVERY region before birth commit (so a sibling
            region observes it too), with no outbound `:raise`"
    (let [m {:type    :parallel
             :data    {}
             :regions {:left  {:initial :a
                               :states  {:a {:entry (fn [_] {:fx [[:raise [:go]]]})
                                            :on    {:go :b}}
                                         :b {}}}
                       ;; :right declares the SAME `:go` event but does NOT
                       ;; emit it — it can only fire if the region-:left raise
                       ;; was re-broadcast through the parent queue.
                       :right {:initial :r
                               :states  {:r {:on {:go :s}}
                                         :s {}}}}}
          {:keys [state fx]} (boot-result m)]
      (is (= :b (:left state))
          "region :left drained its own initial-`:entry` `:raise` → :b")
      (is (= :s (:right state))
          "region :right saw the re-broadcast `:go` (parent internal queue, not region-local) → :s")
      (is (not (raise-fx? fx))
          "no reserved `:raise` escaped to the outbound fx layer"))))

(deftest pure-birth-always-settles-past-transient-initial-leaf
  (testing "SCXML §3.13 initial macrostep: an initial leaf whose `:always`
            guard already holds is settled PAST on birth — the transient
            initial leaf is never externally observed (W3C test 372/388
            eventless-on-entry family)"
    (let [m {:initial :booting
             :data    {:ready? true}
             :guards  {:ready? (fn [{data :data}] (:ready? data))}
             :states  {:booting {:always [{:guard :ready? :target :ready}]}
                       :ready   {}
                       :stalled {}}}]
      (is (= :ready (:state (boot m)))
          "birth settled past the transient :booting leaf to the :always target :ready"))))

(deftest pure-birth-always-stays-when-guard-false
  (testing "the birth `:always` guard FALSE → the machine stays in the
            initial leaf (no spurious transition on start)"
    (let [m {:initial :booting
             :data    {:ready? false}
             :guards  {:ready? (fn [{data :data}] (:ready? data))}
             :states  {:booting {:always [{:guard :ready? :target :ready}]}
                       :ready   {}}}]
      (is (= :booting (:state (boot m)))
          "guard false — birth settles with zero microsteps, stays at :booting"))))

(deftest pure-birth-always-chains-to-fixed-point
  (testing "a birth `:always` chain settles to a FIXED POINT, not just one
            hop — :a →(always) :b →(always) :c, all guards true, on start"
    (let [m {:initial :a
             :data    {}
             :guards  {:always? (fn [_] true)}
             :states  {:a {:always [{:guard :always? :target :b}]}
                       :b {:always [{:guard :always? :target :c}]}
                       :c {}}}]
      (is (= :c (:state (boot m)))
          "birth eventless loop ran to quiescence — settled at :c, never observed :a/:b"))))

(deftest pure-birth-always-deep-compound-initial-leaf
  (testing "(d) a compound initial cascade whose DEEP initial leaf carries
            an `:always` settles past it on birth (initial cascade descends
            :outer→:inner, then :inner's `:always` fires)"
    (let [m {:initial :outer
             :data    {:go? true}
             :guards  {:go? (fn [{data :data}] (:go? data))}
             :states  {:outer {:initial :inner
                               :states  {:inner    {:always [{:guard :go? :target :resolved}]}
                                         :resolved {}}}
                       :elsewhere {}}}
          {:keys [state]} (boot m)]
      ;; The `:always` target :resolved is a sibling of :inner inside :outer,
      ;; so the settled path is [:outer :resolved].
      (is (= [:outer :resolved] state)
          "birth cascaded to the deep leaf then settled its `:always` to [:outer :resolved]"))))

(deftest pure-birth-always-data-action-committed
  (testing "the birth `:always` transition's `:action` :data write commits
            alongside the target — the whole initial macrostep is atomic"
    (let [m {:initial :booting
             :data    {:ready? true :n 0}
             :guards  {:ready? (fn [{data :data}] (:ready? data))}
             :actions {:bump (fn [{data :data}] {:data (update data :n inc)})}
             :states  {:booting {:always [{:guard :ready? :target :ready :action :bump}]}
                       :ready   {}}}
          {:keys [state data]} (boot m)]
      (is (= :ready state) "settled to :ready")
      (is (= 1 (:n data)) "the `:always` action's :data write committed with the target"))))

(deftest pure-birth-no-always-unaffected
  (testing "(f) a machine with NO `:always` boots identically to before —
            birth installs the initial state with zero microsteps"
    (let [m {:initial :idle
             :data    {:seeded? true}
             :states  {:idle {:on {:go :next}} :next {}}}
          {:keys [state data]} (boot m)]
      (is (= :idle state) "no `:always` — birth lands on the plain initial state")
      (is (= {:seeded? true} data) ":data is the declared seed, unchanged"))))

(deftest pure-birth-parallel-regions-settle-independently
  (testing "(e) parallel — each region's birth `:always` settles
            independently (region L's guard true → settles to :l-ready;
            region R's guard false → stays at its initial leaf)"
    (let [m {:type    :parallel
             :data    {:l? true :r? false}
             :guards  {:l? (fn [{data :data}] (:l? data))
                       :r? (fn [{data :data}] (:r? data))}
             :regions {:left  {:initial :l-boot
                               :states  {:l-boot  {:always [{:guard :l? :target :l-ready}]}
                                         :l-ready {}}}
                       :right {:initial :r-boot
                               :states  {:r-boot  {:always [{:guard :r? :target :r-ready}]}
                                         :r-ready {}}}}}
          {:keys [state]} (boot m)]
      (is (= :l-ready (:left state))
          "region :left's birth `:always` (guard true) settled to :l-ready")
      (is (= :r-boot (:right state))
          "region :right's birth `:always` (guard false) stayed at its initial leaf"))))

(deftest pure-birth-always-depth-limit-surfaces-as-failed-macrostep
  (testing "a birth `:always` cycle trips `:always-depth-limit` and surfaces
            as a FAILED macrostep (rf2-y3jv8q — XState v5 throws on such a
            runaway). The `:fail` carries the `::depth-abort?` sentinel and
            threads NO snapshot; atomic rollback is enforced by the failure
            surface (the lifecycle handler short-circuits to `{}`, leaving the
            post-cascade initial configuration committed)"
    (let [m {:initial :a
             :data    {}
             :always-depth-limit 5
             :guards  {:p? (fn [_] true)}
             :states  {:a {:always [{:guard :p? :target :b}]}
                       :b {:always [{:guard :p? :target :a}]}}}
          initial (parallel/build-initial-snapshot m {:bootstrap-pending? false})
          r       (parallel/apply-initial-entry-cascade m initial)]
      (is (result/fail? r) "birth returns a :fail (failed macrostep), not an :ok no-op")
      (is (result/depth-abort? r)
          "the :fail carries the ::depth-abort? sentinel (a bounded-depth trip)")
      (is (nil? (result/snap r))
          "a :fail threads no snapshot — the runaway settle commits nothing"))))

;; ===========================================================================
;; LIVE — eager `[:rf.machine/start]` and lazy first-event birth
;; ===========================================================================

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter substrate-adapter/adapter}))

;; snapshot lookup via the shared machines test-support (rf2-3l8lqe finding #4)
;; — no hardcoded `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot mtest/snapshot)

(deftest live-eager-start-settles-birth-always
  (testing "(a) eager `[:rf.machine/start]` — a transient initial leaf whose
            `:always` guard holds settles past it on start, no user event"
    (let [m {:initial :booting
             :data    {:ready? true}
             :guards  {:ready? (fn [{data :data}] (:ready? data))}
             :states  {:booting {:always [{:guard :ready? :target :ready}]}
                       :ready   {}}}]
      (rf/reg-machine :rf2-505ic/eager m)
      (rf/dispatch-sync [:rf2-505ic/eager [:rf.machine/start]])
      (is (= :ready (:state (snapshot :rf2-505ic/eager)))
          "eager start settled past :booting to :ready — birth `:always` fired with no external event"))))

(deftest live-lazy-first-event-settles-birth-always
  (testing "(b) lazy first-event birth — the birth `:always` settles as part
            of the SAME first dispatch, BEFORE the user event is processed"
    (let [m {:initial :booting
             :data    {:ready? true}
             :guards  {:ready? (fn [{data :data}] (:ready? data))}
             :actions {:noted (fn [{data :data}] {:data (assoc data :noted? true)})}
             :states  {:booting {:always [{:guard :ready? :target :ready}]}
                       :ready   {:on {:note {:action :noted}}}}}]
      (rf/reg-machine :rf2-505ic/lazy m)
      ;; First real event is `:note`, declared on :ready. It can only fire
      ;; if the birth `:always` already settled :booting→:ready BEFORE the
      ;; user event was processed (the birth happens-before the event).
      (rf/dispatch-sync [:rf2-505ic/lazy [:note]])
      (let [s (snapshot :rf2-505ic/lazy)]
        (is (= :ready (:state s))
            "birth settled :booting→:ready on the lazy first dispatch")
        (is (true? (get-in s [:data :noted?]))
            ":note resolved at :ready — proving the birth `:always` settled before the user event")))))

(deftest live-birth-always-stays-when-guard-false
  (testing "(c) eager start with a FALSE `:always` guard stays in the initial leaf"
    (let [m {:initial :booting
             :data    {:ready? false}
             :guards  {:ready? (fn [{data :data}] (:ready? data))}
             :states  {:booting {:always [{:guard :ready? :target :ready}]}
                       :ready   {}}}]
      (rf/reg-machine :rf2-505ic/false-guard m)
      (rf/dispatch-sync [:rf2-505ic/false-guard [:rf.machine/start]])
      (is (= :booting (:state (snapshot :rf2-505ic/false-guard)))
          "guard false — eager start stays at :booting, no spurious birth transition"))))

(deftest live-eager-start-deep-compound-initial-always
  (testing "(d) eager start on a compound initial cascade whose deep leaf
            carries an `:always` settles past it"
    (let [m {:initial :outer
             :data    {:go? true}
             :guards  {:go? (fn [{data :data}] (:go? data))}
             :states  {:outer {:initial :inner
                               :states  {:inner    {:always [{:guard :go? :target :resolved}]}
                                         :resolved {}}}}}]
      (rf/reg-machine :rf2-505ic/compound m)
      (rf/dispatch-sync [:rf2-505ic/compound [:rf.machine/start]])
      (is (= [:outer :resolved] (:state (snapshot :rf2-505ic/compound)))
          "birth cascaded :outer→:inner then settled :inner's `:always` to [:outer :resolved]"))))

(deftest live-eager-start-parallel-regions-settle
  (testing "(e) eager start on a parallel machine — each region's birth
            `:always` settles independently"
    (let [m {:type    :parallel
             :data    {:l? true :r? false}
             :guards  {:l? (fn [{data :data}] (:l? data))
                       :r? (fn [{data :data}] (:r? data))}
             :regions {:left  {:initial :l-boot
                               :states  {:l-boot  {:always [{:guard :l? :target :l-ready}]}
                                         :l-ready {}}}
                       :right {:initial :r-boot
                               :states  {:r-boot  {:always [{:guard :r? :target :r-ready}]}
                                         :r-ready {}}}}}]
      (rf/reg-machine :rf2-505ic/par m)
      (rf/dispatch-sync [:rf2-505ic/par [:rf.machine/start]])
      (let [s (snapshot :rf2-505ic/par)]
        (is (= :l-ready (get-in s [:state :left]))
            "region :left settled its birth `:always`")
        (is (= :r-boot (get-in s [:state :right]))
            "region :right's guard false — stayed at its initial leaf")))))

(deftest live-eager-start-no-always-unaffected
  (testing "(f) eager start on a no-`:always` machine installs the plain
            initial state — birth identical to before rf2-505ic"
    (let [m {:initial :idle
             :data    {:seeded? true}
             :states  {:idle {:on {:go :next}} :next {}}}]
      (rf/reg-machine :rf2-505ic/plain m)
      (rf/dispatch-sync [:rf2-505ic/plain [:rf.machine/start]])
      (let [s (snapshot :rf2-505ic/plain)]
        (is (= :idle (:state s)) "lands on the plain initial state")
        (is (= {:seeded? true} (:data s)) ":data unchanged")))))

(deftest live-eager-start-settling-onto-final-auto-destroys
  (testing "an eager `[:rf.machine/start]` whose birth `:always` settles onto
            a `:final?` leaf auto-destroys at start (XState v5: such an actor
            is done immediately) — the eager-start path runs the finalize
            cascade just as the lazy path would"
    (let [m {:initial :booting
             :data    {:ready? true}
             :guards  {:ready? (fn [{data :data}] (:ready? data))}
             :states  {:booting {:always [{:guard :ready? :target :done}]}
                       :done    {:final? true}}}]
      (rf/reg-machine :rf2-505ic/final-at-birth m)
      (rf/dispatch-sync [:rf2-505ic/final-at-birth [:rf.machine/start]])
      (is (nil? (snapshot :rf2-505ic/final-at-birth))
          "birth settled :booting→:done (:final?) and auto-destroyed — snapshot cleared on eager start"))))
