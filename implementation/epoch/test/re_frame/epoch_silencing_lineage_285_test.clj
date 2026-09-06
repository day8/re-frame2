(ns re-frame.epoch-silencing-lineage-285-test
  "rf2-vxgfnd.285 — make the delayed predecessor-silencing lineage EXACT,
  LINEARIZABLE, and BOUNDED, the exact audit follow-up to the merged .265
  per-callback-generation work.

  .265 decided each delayed `:rf.epoch.cb/silenced-on-frame-destroy` PER
  callback-generation identity, but snapshot, eligibility, publication and
  retention were still split across non-linearizable reads/writes. .285 closes
  three residual gaps, each pinned here (red before the fix; the noted mutation
  re-reddens it):

    EXACT — `snapshot-terminal-destroy-evidence!` validated a generation through
      the observation ledger, then RE-READ the registry to build cb→generation.
      A replacement between the two reads recorded a fresh generation as having
      observed A. The fix derives the generation from ONE consistent read, taking
      it from the observation STAMP. Mutation: restore the second registry read.

    LINEARIZABLE — the publish loop snapshotted live observers ONCE, then for
      each identity separately checked eligibility, emitted, and only afterward
      recorded the mark. Two publishers could both pass before either mark; a
      trace listener could re-arm a later identity against the stale pre-loop
      set; a callback replaced between predicate and emit inherited an unqualified
      silence. The fix makes eligibility-recheck + mark-reservation ONE atomic
      claim before delivery, over a deterministic identity order, rolling back
      only on delivery failure. Mutations: split predicate→emit→mark; reuse a
      stale pre-loop observer set.

    BOUNDED — `terminal-silence-marks` grew one tombstone per unique destroyed
      frame until that id/cb was reused; `reset-listeners!` left marks; and
      `reset-frame-silences!` recycled the monotonic seq below an outstanding
      baseline. The fix brackets each frame's deferred window with a per-frame
      outstanding-predecessor count, reclaiming the frame's marks when its last
      predecessor resolves, clears marks on `reset-listeners!`, and never recycles
      the seq. Mutations: retain marks past resolution; recycle the seq on reset.

  JVM fixtures compose the successor lineage at the epoch-state seam (a single
  real fan-out re-arms every listener uniformly and cannot diverge them) and use
  aligned threads for the concurrent claim seam; the synchronous/reentrant CLJS
  peers live in `re-frame.epoch-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks.
            [re-frame.epoch]
            [re-frame.epoch.listeners :as rf.epoch.listeners]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.util.concurrent CountDownLatch CyclicBarrier TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- silences-for
  "Count silencing traces in `silencings` whose `:cb-id` is `cb`."
  [silencings cb]
  (count (filter #(= cb (:cb-id (:tags %))) @silencings)))

(defn- silences-for-frame
  "Count silencing traces in `silencings` for `cb` on `frame`."
  [silencings cb frame]
  (count (filter #(and (= cb (:cb-id (:tags %)))
                       (= frame (:frame (:tags %))))
                 @silencings)))

(defn- cb-generation
  "The live generation token currently registered under `cb`."
  [cb]
  (:generation (get (rf.epoch.state/listeners-snapshot) cb)))

(defn- total-marks
  "Count of `[frame cb]` terminal-silence marks currently retained."
  []
  (reduce + 0 (map count (vals (rf.epoch.state/terminal-silence-marks-snapshot)))))

;; ---- EXACTNESS ------------------------------------------------------------

(deftest snapshot-attributes-the-observation-stamp-generation
  ;; The owed `{cb → generation}` map is derived from ONE consistent read with
  ;; the generation taken from the OBSERVATION STAMP. A cb replaced AFTER it
  ;; observed (stamp G, live H) is omitted — its current generation never
  ;; observed A — and the fresh generation H is never attributed the old
  ;; observation. Re-observing under H re-includes it at H.
  (let [id :vxgfnd285/exact
        cb ::vxgfnd285-exact-cb]
    (rf/register-listener! :epoch cb (fn [_] nil))
    (try
      (rf.epoch.state/claim-frame-owner! id (Object.))
      (rf.epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
      (let [g (cb-generation cb)
            observing-1 (:observing (rf.epoch.state/snapshot-terminal-observers id))]
        (is (= g (get observing-1 cb))
            "the owed generation is the exact generation that observed the frame")
        ;; Replace cb → fresh generation H, WITHOUT re-observing (stamp stays G).
        (rf/register-listener! :epoch cb (fn [_] nil))
        (let [h (cb-generation cb)
              observing-2 (:observing (rf.epoch.state/snapshot-terminal-observers id))]
          (is (not= g h) "replacement minted a fresh generation")
          (is (not (contains? observing-2 cb))
              "a cb replaced after it observed is omitted — H never observed A")
          ;; Re-observe under H → included again, now attributed H (the new stamp).
          (rf.epoch.listeners/notify-listeners! {:frame id :epoch-id 2})
          (let [observing-3 (:observing (rf.epoch.state/snapshot-terminal-observers id))]
            (is (= h (get observing-3 cb))
                "re-observing re-includes the cb under its current generation stamp"))))
      (finally
        (rf/unregister-listener! :epoch cb)))))

(deftest generation-replaced-between-snapshot-and-publish-gets-no-stale-silence
  ;; The emit-time consequence of exactness (never emits A silence for H): A's
  ;; snapshot owes cb under generation G; cb is then re-registered to a fresh
  ;; generation before A publishes. The atomic claim rechecks the current
  ;; generation, so H — which never observed A — receives no stale signal.
  (let [id         :vxgfnd285/replace-gap
        cb         ::vxgfnd285-replace-gap-cb
        token-a    (Object.)
        silencings (atom [])]
    (rf/register-listener! :epoch cb (fn [_] nil))
    (rf/register-listener! :trace ::vxgfnd285-replace-gap-silencing
      (fn [ev]
        (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
          (swap! silencings conj ev))))
    (try
      (rf.epoch.state/claim-frame-owner! id token-a)
      (rf.epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
      (let [a-ev (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil)]
        ;; cb re-registered to a fresh generation in the deferred window.
        (rf/register-listener! :epoch cb (fn [_] nil))
        (rf.epoch.listeners/on-frame-destroyed! id token-a a-ev)
        (is (zero? (silences-for silencings cb))
            "the re-registered generation receives no stale prior-generation silence"))
      (finally
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace ::vxgfnd285-replace-gap-silencing)))))

;; ---- LINEARIZABLE: atomic single-claim ------------------------------------

(deftest two-overlapping-publishers-exactly-one-claims-the-signal
  ;; Two deferred predecessors of the same (frame, cb) publish concurrently, with
  ;; aligned threads maximising the overlap. The eligibility-recheck + mark write
  ;; is ONE atomic claim, so exactly one publisher reserves and emits the single
  ;; signal; the other sees the mark and skips. A split predicate→emit→mark lets
  ;; both pass before either mark → a double signal.
  (dotimes [round 60]
    (let [id         (keyword "vxgfnd285" (str "dual-" round))
          cb         ::vxgfnd285-dual-cb
          silencings (atom [])]
      (rf/register-listener! :epoch cb (fn [_] nil))
      (rf/register-listener! :trace ::vxgfnd285-dual-silencing
        (fn [ev]
          (when (and (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
                     (= id (:frame (:tags ev))))
            (swap! silencings conj ev))))
      (try
        (rf.epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
        (let [ev-1    (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil)
              ev-2    (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil)
              ;; Both bundles owe cb; drop its live observation so the silence is
              ;; genuinely owed (as A's own compare-owned cleanup would).
              _       (rf.epoch.state/drop-frame-observation! id)
              barrier (CyclicBarrier. 2)
              publish (fn [ev token]
                        (future
                          (.await barrier 5 TimeUnit/SECONDS)
                          (rf.epoch.listeners/on-frame-destroyed! id token ev)))
              f1      (publish ev-1 (Object.))
              f2      (publish ev-2 (Object.))]
          (is (not= ::timeout (deref f1 5000 ::timeout)) "publisher 1 completes")
          (is (not= ::timeout (deref f2 5000 ::timeout)) "publisher 2 completes")
          (is (= 1 (silences-for silencings cb))
              "exactly one publisher atomically claimed the one signal"))
        (finally
          (rf/unregister-listener! :epoch cb)
          (rf/unregister-listener! :trace ::vxgfnd285-dual-silencing))))))

(deftest trace-listener-rearming-a-later-identity-mid-fan-is-rechecked
  ;; The owed identities fan in a deterministic cb-id order. A trace listener,
  ;; fired by the FIRST (a-trigger) silence, re-arms a LATER identity (z-target)
  ;; on a live successor B. Because eligibility is re-read FRESH inside each
  ;; per-identity claim, z-target — now a live observer — is skipped. A stale
  ;; pre-loop observer set (computed before the fan) would miss the re-arm and
  ;; wrongly silence z-target.
  (let [id         :vxgfnd285/rearm
        trigger    ::a-trigger            ; sorts first
        target     ::z-target             ; sorts last
        token-a    (Object.)
        token-b    (Object.)
        silencings (atom [])]
    (rf/register-listener! :epoch trigger (fn [_] nil))
    (rf/register-listener! :epoch target (fn [_] nil))
    (try
      (rf.epoch.state/claim-frame-owner! id token-a)
      (rf.epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
      (is (= #{trigger target} (set (rf.epoch.state/cbs-observing-frame id)))
          "both identities observed A")
      (let [target-gen (cb-generation target)
            a-ev       (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil)]
        ;; A live successor B claims (dropping every observation). Neither
        ;; identity is a live observer at fan start.
        (rf.epoch.state/claim-frame-owner! id token-b)
        (is (empty? (rf.epoch.state/cbs-observing-frame id))
            "successor B dropped both observations")
        ;; The trace listener re-arms z-target on B the moment a-trigger's silence
        ;; fires — mid-fan, before z-target is reached.
        (rf/register-listener! :trace ::vxgfnd285-rearm-silencing
          (fn [ev]
            (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
              (swap! silencings conj ev)
              (when (= trigger (:cb-id (:tags ev)))
                (rf.epoch.state/record-observation! target target-gen id)))))
        (rf.epoch.listeners/on-frame-destroyed! id token-a a-ev)
        (is (= 1 (silences-for silencings trigger))
            "a-trigger — A-only — is silenced first")
        (is (zero? (silences-for silencings target))
            "z-target — re-armed live mid-fan — is rechecked and skipped")
        (is (= [target] (rf.epoch.state/cbs-observing-frame id))
            "z-target is a live observer of B after the re-arm"))
      (finally
        (rf/unregister-listener! :epoch trigger)
        (rf/unregister-listener! :epoch target)
        (rf/unregister-listener! :trace ::vxgfnd285-rearm-silencing)))))

(deftest failed-delivery-rolls-back-the-reservation-only-then
  ;; A granted claim RESERVES the signal (mark written, under both ledger locks)
  ;; before the external publish runs OUTSIDE them, so a concurrent re-claim of
  ;; the same identity is refused. A publish that THROWS rolls the reservation
  ;; back and propagates, releasing the one signal so it can be legitimately
  ;; re-attempted; a reservation whose publish SUCCEEDED stays claimed.
  ;;
  ;; rf2-6r9j.78: exercised through the shipped
  ;; `claim-and-publish-delayed-silence!` — the reserve-only
  ;; `claim-delayed-silence!` / `rollback-delayed-silence!` pair this used to
  ;; drive is retired, so there is no shipped reserve-only API to stage against.
  (let [id :vxgfnd285/rollback
        cb ::vxgfnd285-rollback-cb]
    (rf/register-listener! :epoch cb (fn [_] nil))
    (try
      ;; cb is owed (not a live observer of id), so the claim reserves the signal.
      (let [g      (cb-generation cb)
            claim! (fn [publish!]
                     (rf.epoch.state/claim-and-publish-delayed-silence! id cb g 0 publish!))]
        ;; A publish that throws propagates AND releases the reservation.
        (is (thrown? clojure.lang.ExceptionInfo
              (claim! (fn [] (throw (ex-info "delivery failed" {})))))
            "a throwing publish propagates contained")
        (is (zero? (total-marks))
            "the failed delivery left no phantom mark behind")
        (is (true? (claim! (fn [] nil)))
            "after the rollback the one signal can be re-claimed and published")
        (is (pos? (total-marks)) "the successful claim's mark stands")
        ;; Ineligible short-circuits the `when-let` — nil, not false.
        (is (nil? (claim! (fn [] nil)))
            "a second claim is refused while that reservation stands"))
      (finally
        (rf/unregister-listener! :epoch cb)))))

(deftest a-failed-publish-prunes-only-its-own-seq-never-a-fresher-mark
  ;; The rollback is a COMPARE-and-prune: it releases the mark only while OUR
  ;; reserved seq is still the one standing. A fresher publisher that claimed the
  ;; same identity while our publish ran outside the ledger locks owns the signal
  ;; now, and our late failure must not release ITS mark — that would let the one
  ;; signal be emitted twice.
  (let [id :vxgfnd285/rollback-stale
        cb ::vxgfnd285-rollback-stale-cb]
    (rf/register-listener! :epoch cb (fn [_] nil))
    (try
      (let [g           (cb-generation cb)
            superseded? (atom nil)]
        (is (thrown? clojure.lang.ExceptionInfo
              (rf.epoch.state/claim-and-publish-delayed-silence! id cb g 0
                (fn []
                  ;; Locks are released here, so a successor publisher — whose
                  ;; baseline sits above our fresh mark — can claim the identity
                  ;; between our reservation and our (failing) delivery.
                  (reset! superseded?
                          (rf.epoch.state/claim-and-publish-delayed-silence!
                            id cb g (rf.epoch.state/current-terminal-silence-seq)
                            (fn [] nil)))
                  (throw (ex-info "delivery failed" {})))))
            "our publish still throws contained")
        (is (true? @superseded?)
            "the successor reserved and published while we held no lock")
        (is (pos? (total-marks))
            "our failed publish compare-and-pruned only its OWN seq — the fresher mark stands")
        (is (nil? (rf.epoch.state/claim-and-publish-delayed-silence! id cb g 0 (fn [] nil)))
            "and that fresher mark still refuses a claim at the original baseline"))
      (finally
        (rf/unregister-listener! :epoch cb)))))

;; ---- BOUNDED --------------------------------------------------------------

(deftest lineage-marks-are-bounded-across-many-unique-frame-destroys
  ;; One persistent callback observes and destroys thousands of UNIQUE frame ids
  ;; at the epoch-state seam. Each destroy is a self-contained deferred window
  ;; whose count returns to zero, reclaiming that frame's marks — so lineage
  ;; storage returns to a constant (empty) baseline rather than accreting one
  ;; permanent tombstone per id.
  (let [cb         ::vxgfnd285-bounded-cb
        n          3000
        silencings (atom 0)]
    (rf/register-listener! :epoch cb (fn [_] nil))
    (rf/register-listener! :trace ::vxgfnd285-bounded-silencing
      (fn [ev]
        (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
          (swap! silencings inc))))
    (try
      (dotimes [i n]
        (let [id    (keyword "vxgfnd285-bounded" (str i))
              token (Object.)]
          (rf.epoch.state/claim-frame-owner! id token)
          (rf.epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
          (rf.epoch.listeners/on-frame-destroyed! id token
            (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil))
          (is (<= (total-marks) 1)
              "at most one frame's marks are ever retained at once")))
      (is (= n @silencings) "each unique destroy silenced the persistent cb once")
      (is (zero? (total-marks))
          "after all destroys settle, lineage storage returns to a constant baseline")
      (finally
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace ::vxgfnd285-bounded-silencing)))))

(deftest held-predecessor-keeps-only-its-own-frame-marks-reclaimed-after
  ;; A deferred predecessor A of frame F-a is held. A successor B of F-a fires the
  ;; one silence, leaving a mark A must still see. Meanwhile MANY unrelated frames
  ;; are destroyed synchronously. While A is held, ONLY F-a's mark is retained —
  ;; the unrelated frames self-clean — and when A resolves (skipping the ABA
  ;; re-emit) its mark is reclaimed too.
  (let [f-a        :vxgfnd285/held-a
        cb         ::vxgfnd285-held-cb
        token-a    (Object.)
        token-b    (Object.)
        silencings (atom [])]
    (rf/register-listener! :epoch cb (fn [_] nil))
    (rf/register-listener! :trace ::vxgfnd285-held-silencing
      (fn [ev]
        (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
          (swap! silencings conj ev))))
    (try
      ;; A observes F-a and snapshots (opening F-a's window) but does NOT publish.
      (rf.epoch.state/claim-frame-owner! f-a token-a)
      (rf.epoch.listeners/notify-listeners! {:frame f-a :epoch-id 1})
      (let [a-ev (rf.epoch.listeners/snapshot-terminal-destroy-evidence! f-a nil nil nil)]
        ;; Successor B of F-a re-arms cb, then retires — firing the one silence and
        ;; leaving a (F-a, cb) mark above A's baseline.
        (rf.epoch.state/claim-frame-owner! f-a token-b)
        (rf.epoch.listeners/notify-listeners! {:frame f-a :epoch-id 2})
        (rf.epoch.listeners/on-frame-destroyed! f-a token-b
          (rf.epoch.listeners/snapshot-terminal-destroy-evidence! f-a nil nil nil))
        (is (= 1 (silences-for-frame silencings cb f-a)) "B fired the one truthful F-a silence")
        (is (= 1 (total-marks)) "exactly F-a's mark is retained while A is held")
        ;; Destroy many UNRELATED frames synchronously — they must not accrete.
        (dotimes [i 500]
          (let [id    (keyword "vxgfnd285-held-other" (str i))
                token (Object.)]
            (rf.epoch.state/claim-frame-owner! id token)
            (rf.epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
            (rf.epoch.listeners/on-frame-destroyed! id token
              (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil))))
        (is (= 1 (total-marks))
            "held A keeps ONLY F-a's mark — unrelated frames self-clean")
        (is (= #{cb} (set (keys (get (rf.epoch.state/terminal-silence-marks-snapshot) f-a))))
            "the retained mark is exactly the one A could still consume")
        ;; A resumes: it must NOT re-emit (B's mark is above A's baseline)…
        (rf.epoch.listeners/on-frame-destroyed! f-a token-a a-ev)
        (is (= 1 (silences-for-frame silencings cb f-a))
            "late A adds no F-a silence — the retired successor already fired it")
        (is (zero? (total-marks))
            "F-a's mark is reclaimed once A — its last predecessor — resolves"))
      (finally
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace ::vxgfnd285-held-silencing)))))

(deftest reset-listeners-clears-the-silence-lineage
  ;; A full listener wipe must clear the terminal-silence marks too — the old
  ;; `reset-listeners!` left a tombstone per destroyed frame behind.
  (let [id :vxgfnd285/reset-listeners
        cb ::vxgfnd285-reset-listeners-cb]
    (rf/register-listener! :epoch cb (fn [_] nil))
    ;; A destroyed predecessor left a terminal-silence mark that `reset-listeners!`
    ;; must clear (cb is owed — not a live observer — so the claim reserves it).
    (rf.epoch.state/open-silence-lineage! id)
    (rf.epoch.state/claim-and-publish-delayed-silence! id cb (cb-generation cb) 0
                                                    (fn [] nil))
    (is (pos? (total-marks)) "a terminal-silence mark is present")
    (rf.epoch.state/reset-listeners!)
    (is (zero? (total-marks))
        "reset-listeners! clears the terminal-silence lineage, not just the registry")))

(deftest reset-does-not-recycle-the-monotonic-domain-under-an-outstanding-baseline
  ;; `reset-frame-silences!` clears marks but must NOT recycle the monotonic seq:
  ;; recycling to 0 could make a post-reset successor's mark compare at/below an
  ;; outstanding predecessor's earlier baseline, letting it re-emit a silence the
  ;; successor already fired. The seq only ever climbs.
  (let [;; An outstanding predecessor's baseline, taken after the domain has moved
        ;; well above zero.
        _        (dotimes [_ 5] (rf.epoch.state/next-terminal-silence-seq))
        baseline (rf.epoch.state/current-terminal-silence-seq)]
    (is (pos? baseline) "the comparison domain has advanced above zero")
    (rf.epoch.state/reset-frame-silences!)
    (is (zero? (total-marks)) "reset cleared the marks")
    (let [after (rf.epoch.state/next-terminal-silence-seq)]
      (is (> after baseline)
          "a post-reset mark is stamped ABOVE an outstanding baseline — the domain never recycles"))))

;; ---- concurrent seam stress (EXACT + LINEARIZABLE under load) --------------

(deftest concurrent-claim-seam-never-double-signals-under-generation-churn
  ;; A background thread churns cb's generation (replace + re-observe) while the
  ;; main thread repeatedly destroys the shared frame at the seam. The claim seam
  ;; must stay coherent: no destroy ever yields more than one silence for the
  ;; cb, and a callback re-registered mid-flight inherits no unqualified stale
  ;; silence.
  (let [id         :vxgfnd285/churn
        cb         ::vxgfnd285-churn-cb
        rounds     400
        stop?      (atom false)
        silencings (atom [])]
    (rf/register-listener! :epoch cb (fn [_] nil))
    (rf/register-listener! :trace ::vxgfnd285-churn-silencing
      (fn [ev]
        (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
          (swap! silencings conj ev))))
    (let [churner (future
                    (while (not @stop?)
                      (rf/register-listener! :epoch cb (fn [_] nil))
                      (rf.epoch.state/record-observation! cb (cb-generation cb) id)))]
      (try
        (dotimes [_ rounds]
          (reset! silencings [])
          (let [token (Object.)]
            (rf.epoch.state/claim-frame-owner! id token)
            (rf.epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
            (rf.epoch.listeners/on-frame-destroyed! id token
              (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil)))
          (is (<= (silences-for silencings cb) 1)
              "no destroy double-signals the cb under concurrent generation churn"))
        (finally
          (reset! stop? true)
          (deref churner 5000 ::timeout)
          (rf/unregister-listener! :epoch cb)
          (rf/unregister-listener! :trace ::vxgfnd285-churn-silencing))))))
