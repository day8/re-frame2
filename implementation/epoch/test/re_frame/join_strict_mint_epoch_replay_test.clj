(ns re-frame.join-strict-mint-epoch-replay-test
  "rf2-s3qjlw — composition proof that a `:spawn-all` join's strict-mint replay
  survives the ACTUAL epoch record + restore seam, not a proxy that routes
  around it.

  ## Why this suite exists

  PR #5929's `re-frame.join-strict-mint-cljs-test` proves the ISOLATED join
  strict/live logic: a completion under inherited `:strict` reads a recorded
  fact rather than consulting the host, and an absent fact is the canonical
  `:rf.error/missing-required-cofx`. But its central `recorded-completion-
  strict-replays-without-host-generation` fixture bypasses the framework
  record/restore seam three ways:

    1. it HAND-BUILDS `recorded-cofx` from a roll observed off a wrapped
       `:router/dispatch!` — it never reads `rf/epoch-history`;
    2. it installs a TEST-ONLY raw runtime-db restore event
       (`:sm3/restore-runtime` → `{:rf.db/runtime rt}`) and snapshots the
       pre-fold `runtime-db` by hand — it never calls `rf/restore-epoch!`;
    3. it never round-trips the actual `:trigger-event` + post-generation
       `:rf.cofx` the epoch assembly pins.

  So a regression in epoch assembly (the `:rf.cofx/generated` merge that folds a
  mid-run mint into the replay token), in the durable replay material (the
  `:trigger-event` / `:rf.cofx` slots surviving an EDN round-trip), or in the
  restore path (`restore-epoch!` reviving the whole frame-state — machine
  snapshots included — to the pre-completion epoch) could pass while the
  isolated join logic stays green.

  This suite adds ONE composition proof at the natural epoch/machines boundary.
  It drives a real live join completion, reads the REAL `:rf/epoch-record` off
  `rf/epoch-history`, round-trips the recorded event + cofx through EDN, rewinds
  with the REAL `rf/restore-epoch!`, and STRICT-replays the round-tripped
  material — asserting on the real record's identity and lineage so the test
  provably hits the actual record, not a stub. No replay framework, no new
  production seam, the shipped fixtures only.

  ## Why the epoch test surface (module layering)

  The proof needs BOTH machine dispatch AND the real `rf/epoch-history` /
  `rf/restore-epoch!` on one classpath. The machines test lane carries no
  epoch; the epoch `:test` alias carries machines as a test dep (beside its
  sibling `machine-minted-cofx-replay-token` and `actor-revertibility-restore`
  proofs). So the epoch surface is the only lane that can host it — the bead's
  authorised relocation."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Side-effect require — loads the machines late-bind hooks
            ;; (`:machines/reg-machine`, the `:rf.machine/spawn` fx, the join
            ;; lifecycle fx) and the `install-runtime!` hook the reset fixture
            ;; re-fires each test. The capture/restore fixture preserves these
            ;; ns-load-time registrations across each test.
            [re-frame.machines]))

;; The shipped reset fixture (NOT a `clear-all!` reset) so the machines
;; artefact's ns-load fx + sub registrations survive; `:adapter` installs the
;; plain-atom substrate and ensures + binds the ambient `:rf/default` frame, so
;; bare `dispatch-sync` lands there. Clear the epoch ring/listeners per test.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.epoch/clear-history!)
                (rf.epoch/clear-epoch-listeners!))}))

;; ---------------------------------------------------------------------------
;; probes over the `:rf/default` frame the fixture seats
;; ---------------------------------------------------------------------------

(defn- runtime-db []
  (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- join-state [parent-id]
  (get-in (runtime-db) [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- machine-state [machine-id]
  (:state (get-in (runtime-db) [:rf.runtime/machines :snapshots machine-id])))

(defn- child-a [parent-id]
  (get-in (join-state parent-id) [:children :a]))

(defn- last-epoch-id []
  (:epoch-id (last (rf/epoch-history :rf/default))))

(defn- epoch-record-for
  "The most-recent `:rf/epoch-record` in `:rf/default`'s ring whose
  `:trigger-event` equals `trigger`, or nil."
  [trigger]
  (some (fn [r] (when (= trigger (:trigger-event r)) r))
        (reverse (rf/epoch-history :rf/default))))

(defn- ops-of [cap op]
  (filterv #(= op (:operation %)) @cap))

(defn- capture-trace! []
  (let [cap (atom [])]
    (rf/register-listener! :trace ::cap (fn [ev] (swap! cap conj ev)))
    cap))

;; ---------------------------------------------------------------------------
;; machine specs (shape mirrors `re-frame.join-strict-mint-cljs-test`)
;; ---------------------------------------------------------------------------

(defn- reg-roll!
  "Register `:strictmint/roll` as a generator-backed recordable cofx whose
  supplier increments `calls` and returns `value`. `:live` runs the supplier;
  `:strict` refuses and an absent recorded fact is missing-required."
  [calls value]
  (rf/reg-cofx :strictmint/roll
    {:recordable? true
     :doc "Test generator-backed recordable fact: a join completion's minted roll."}
    (fn [] (swap! calls inc) value)))

(defn- mk-completing-child
  "The JOIN COMPLETION TARGET: member child whose completion action
  (`:dispatch-done`, on `:go` → `:done`) DECLARES a generator-backed recordable
  `:strictmint/roll` and forwards the minted value on its completion carrier
  `[parent-id [:child/done :a <roll>]]`. The action runs only when its ensure
  step satisfies `:rf.cofx/requires` under the effective mint policy."
  [parent-id]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :dispatch-done {:rf.cofx/requires [:strictmint/roll]
                             :fn (fn [{data :data cofx :rf.cofx}]
                                   {:fx [[:dispatch [parent-id [:child/done (:id data)
                                                                (:strictmint/roll cofx)]]]]})}}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :dispatch-done}}}
             :done {}}})

(defn- mk-plain-child
  "A member child with NO coeffect requirement — completes cleanly under any
  policy. Pairs with `mk-completing-child` so the two-child `:all` join stays
  OPEN after `:a` folds (a non-decisive fold, so `:a`'s terminal rides the
  `:rf.machine.spawn-all/child-completed` trace we assert on)."
  [parent-id]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch [parent-id [:child/done (:id data)]]]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :dispatch-done}}}
             :done {}}})

(defn- reg-parent!
  "A two-child `:all` join parent: child `:a` is the generator-backed completion
  target, child `:b` a plain never-driven sibling holding the join open. Stays
  on `:racing` at fold so the join slot survives probes."
  [parent-kw target-kw plain-kw]
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        {:children        [{:id :a :machine-id target-kw :start [:set-id :a]}
                                           {:id :b :machine-id plain-kw  :start [:set-id :b]}]
                         :join            :all
                         :on-child-done   :child/done
                         :on-child-error  :child/failed
                         :on-all-complete [:all/done]}
                        :on {:abort :idle}}}}))

(defn- setup-join!
  "Register the completion target / plain sibling / parent under the given
  keyword triple and drive `[:start]` so both children are spawned and
  `:running`. Returns the pre-completion epoch id (the state to restore to)."
  [parent-kw target-kw plain-kw]
  (rf/reg-machine target-kw (mk-completing-child parent-kw))
  (rf/reg-machine plain-kw  (mk-plain-child parent-kw))
  (reg-parent! parent-kw target-kw plain-kw)
  (rf/dispatch-sync [parent-kw [:start]])
  ;; The last settled epoch after `[:start]` is the pre-completion frame state:
  ;; both children :running, the join slot open with `:done #{}`.
  (last-epoch-id))

;; ---------------------------------------------------------------------------
;; (1) the strict replay flows through the ACTUAL epoch record + restore path
;; ---------------------------------------------------------------------------

(deftest strict-replay-through-real-epoch-record-and-restore-reproduces-the-join
  (testing "rf2-s3qjlw — a live join completion mints the generator-backed
            `:strictmint/roll`; the ACTUAL `:rf/epoch-record` (read off
            `rf/epoch-history`) carries the real `:trigger-event` and the
            post-generation `:rf.cofx` token; the recorded event + cofx survive
            an EDN round-trip; `rf/restore-epoch!` rewinds the WHOLE frame-state
            (machine snapshots included) to the pre-completion epoch; and a
            STRICT replay of the round-tripped material reproduces the fold, the
            join authority (no stale suppression), and the child-completed
            terminal WITHOUT re-running the generator — reading the recorded
            fact, never consulting the host."
    (let [calls        (atom 0)
          pre-epoch-id (do (reg-roll! calls 6)
                           (setup-join! :j1/rp :j1/ta :j1/pb))
          a            (child-a :j1/rp)
          cap          (capture-trace!)]
      ;; --- RECORD a genuine live completion. Supply an external :rf.cofx so
      ;;     run-start pins a replay token the mid-run mint folds into.
      (rf/dispatch-sync [a [:go]] {:rf.cofx {:rf/time-ms 1}})
      (is (= 1 @calls) "the live completion ran the generator once")
      (is (= #{:a} (:done (join-state :j1/rp))) "the live completion folded :a")

      ;; --- READ THE ACTUAL EPOCH RECORD (not a hand-built cofx / observed roll).
      (let [rec (epoch-record-for [a [:go]])]
        (is (some? rec)
            "an epoch record was assembled for the real completing event")
        ;; Identity / lineage: this IS a live record in the frame's ring.
        (is (= :rf/default (:frame rec)) "the record belongs to the driven frame")
        (is (contains? (set (map :epoch-id (rf/epoch-history :rf/default)))
                       (:epoch-id rec))
            "the record's :epoch-id is a live entry in the actual ring buffer")
        (is (= [a [:go]] (:trigger-event rec))
            "the record pins the REAL completing trigger event")
        ;; The post-generation replay token carries the minted fact.
        (is (= 6 (:strictmint/roll (:rf.cofx rec)))
            "the :rf.cofx token captured the mid-run minted generator fact")
        (is (= 1 (:rf/time-ms (:rf.cofx rec)))
            "the external :rf/time-ms survives in the token")

        ;; --- EDN ROUND-TRIP the durable replay material.
        (let [durable       [(:trigger-event rec) (:rf.cofx rec)]
              [ev cofx]     (edn/read-string (pr-str durable))]
          (is (= durable [ev cofx])
              "the recorded event + cofx survive an EDN round-trip verbatim")

          ;; --- RESTORE via the REAL restore path to the pre-completion epoch.
          (is (true? (rf/restore-epoch! :rf/default pre-epoch-id))
              "restore-epoch! to the pre-completion epoch succeeded")
          (is (= #{} (:done (join-state :j1/rp)))
              "restore rewound the join slot: :done is empty again")
          (is (= :running (machine-state a))
              "restore revived the target child's snapshot to :running")
          (is (= a (child-a :j1/rp))
              "restore preserved the target child's instance address")

          ;; --- STRICT REPLAY of the round-tripped material.
          (reset! calls 0)
          (reset! cap [])
          (rf/dispatch-sync ev {:rf.cofx cofx :rf.cofx/mint-policy :strict})
          (is (zero? @calls)
              "strict replay did NOT re-run the generator — the host was not consulted")
          (is (= #{:a} (:done (join-state :j1/rp)))
              "the parent fold reproduced from the recorded fact")
          (is (empty? (ops-of cap :rf.machine.spawn-all/stale-completion))
              "join authority reproduced — no stale suppression on the faithful replay")
          (is (= 1 (count (ops-of cap :rf.machine.spawn-all/child-completed)))
              "terminal evidence reproduced — one child-completed terminal")
          (is (empty? (ops-of cap :rf.error/missing-required-cofx))
              "no missing-required error on the faithful strict replay"))))))

;; ---------------------------------------------------------------------------
;; (2) stripping the recorded fact is canonical missing-required; the same
;;     stripped input under :live is the load-bearing foil (gives teeth).
;; ---------------------------------------------------------------------------

(deftest stripped-recorded-fact-is-canonical-missing-required-with-a-live-foil
  (testing "rf2-s3qjlw — driving the SAME pre-completion epoch (rewound with the
            real `rf/restore-epoch!`) and STRICT-replaying the real recorded
            event with the `:strictmint/roll` fact REMOVED from the token yields
            the canonical `:rf.error/missing-required-cofx` and no fold — strict
            refuses to mint. The SAME stripped input under `:live` DOES mint and
            fold, so the strict no-fold is policy-driven, not incidental: this is
            the load-bearing foil that gives the strict assertion teeth."
    (let [calls        (atom 0)
          pre-epoch-id (do (reg-roll! calls 6)
                           (setup-join! :j2/rp :j2/ta :j2/pb))
          a            (child-a :j2/rp)
          cap          (capture-trace!)]
      ;; Record a genuine live completion, then read the REAL recorded event.
      (rf/dispatch-sync [a [:go]] {:rf.cofx {:rf/time-ms 1}})
      (is (= #{:a} (:done (join-state :j2/rp))) "the live completion folded :a")
      (let [rec       (epoch-record-for [a [:go]])
            ev        (:trigger-event rec)
            recorded  (:rf.cofx rec)
            stripped  (dissoc recorded :strictmint/roll)]
        (is (= 6 (:strictmint/roll recorded)) "the recorded token carried the fact")
        (is (nil? (:strictmint/roll stripped)) "the stripped token lacks the fact")

        ;; --- STRICT with the recorded fact REMOVED → canonical missing-required.
        (is (true? (rf/restore-epoch! :rf/default pre-epoch-id))
            "restore-epoch! to the pre-completion epoch succeeded")
        (reset! calls 0)
        (reset! cap [])
        (rf/dispatch-sync ev {:rf.cofx stripped :rf.cofx/mint-policy :strict})
        (is (zero? @calls) "the stripped strict replay did NOT mint")
        (is (= #{} (:done (join-state :j2/rp)))
            "the stripped strict replay folded nothing")
        (is (= :running (machine-state a))
            "the target child stayed :running — its completion action was skipped")
        (is (= 1 (count (ops-of cap :rf.error/missing-required-cofx)))
            "stripping the recorded fact is the canonical :rf.error/missing-required-cofx")

        ;; --- TEETH: the SAME stripped input under :live mints + folds.
        (is (true? (rf/restore-epoch! :rf/default pre-epoch-id))
            "restore-epoch! back to the pre-completion epoch succeeded")
        (reset! calls 0)
        (reset! cap [])
        (rf/dispatch-sync ev {:rf.cofx stripped :rf.cofx/mint-policy :live})
        (is (= 1 @calls) "the :live foil minted the fact")
        (is (= #{:a} (:done (join-state :j2/rp)))
            "the :live foil folded :a — proving the strict no-fold is policy-driven")))))
