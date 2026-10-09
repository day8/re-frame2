(ns re-frame.epoch-silencing-lineage-285-test
  "The delayed predecessor-silencing lineage is EXACT, LINEARIZABLE and BOUNDED.

    EXACT — a generation re-registered after A's snapshot receives no stale
      silence: the claim rechecks the current generation.
    LINEARIZABLE — eligibility recheck + mark reservation is ONE atomic claim,
      taken per identity in cb-id order against FRESH observers, and rolled
      back (compare-and-prune) only when delivery throws.
    BOUNDED — a frame's marks are kept only while a deferred predecessor of
      THAT frame is outstanding.

  Fixtures compose the successor lineage at the epoch-state seam: a single real
  fan-out re-arms every listener uniformly and cannot diverge them."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks.
            [re-frame.epoch]
            [re-frame.epoch.listeners :as rf.epoch.listeners]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.util.concurrent CyclicBarrier TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- record-silences!
  "Capture every `:rf.epoch.cb/silenced-on-frame-destroy` trace into an atom."
  []
  (let [silencings (atom [])]
    (rf/register-listener! :trace ::silencing
      (fn [ev]
        (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
          (swap! silencings conj ev))))
    silencings))

(defn- silences-for [silencings cb]
  (count (filter #(= cb (:cb-id (:tags %))) @silencings)))

(defn- cb-generation [cb]
  (:generation (get (rf.epoch.state/listeners-snapshot) cb)))

(defn- marks
  "The retained terminal-silence marks as a set of `[frame cb]` pairs."
  []
  (set (for [[frame cbs] (rf.epoch.state/terminal-silence-marks-snapshot)
             cb          (keys cbs)]
         [frame cb])))

;; ---- EXACT ----------------------------------------------------------------

(deftest generation-replaced-between-snapshot-and-publish-gets-no-stale-silence
  (let [id         :vxgfnd285/replace-gap
        cb         ::vxgfnd285-replace-gap-cb
        token-a    (Object.)
        silencings (record-silences!)]
    (rf/register-listener! :epoch cb (fn [_] nil))
    (rf.epoch.state/claim-frame-owner! id token-a)
    (rf.epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
    (let [a-ev (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil)]
      ;; cb re-registered to a fresh generation in the deferred window.
      (rf/register-listener! :epoch cb (fn [_] nil))
      (rf.epoch.listeners/on-frame-destroyed! id token-a a-ev)
      (is (zero? (silences-for silencings cb))
          "the re-registered generation receives no stale prior-generation silence"))))

;; ---- LINEARIZABLE ---------------------------------------------------------

(deftest two-overlapping-publishers-exactly-one-claims-the-signal
  ;; Two deferred predecessors of one (frame, cb) publish at once behind a
  ;; barrier. A split predicate→emit→mark lets both pass before either marks.
  (let [cb         ::vxgfnd285-dual-cb
        silencings (record-silences!)
        rounds     60
        results
        (vec
          (for [round (range rounds)]
            (let [id (keyword "vxgfnd285" (str "dual-" round))]
              (rf/register-listener! :epoch cb (fn [_] nil))
              (rf.epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
              (let [ev-1    (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil)
                    ev-2    (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil)
                    ;; Drop the live observation so the silence is genuinely owed.
                    _       (rf.epoch.state/drop-frame-observation! id)
                    barrier (CyclicBarrier. 2)
                    publish (fn [ev]
                              (future
                                (.await barrier 5 TimeUnit/SECONDS)
                                (rf.epoch.listeners/on-frame-destroyed! id (Object.) ev)))
                    fs      [(publish ev-1) (publish ev-2)]]
                [(mapv #(not= ::timeout (deref % 5000 ::timeout)) fs)
                 (count (filter #(and (= cb (:cb-id (:tags %)))
                                      (= id (:frame (:tags %))))
                                @silencings))]))))]
    (is (= (repeat rounds [[true true] 1]) results)
        "every round: both publishers complete and exactly one claims the signal")))

(deftest trace-listener-rearming-a-later-identity-mid-fan-is-rechecked
  ;; The fan runs in cb-id order. a-trigger's silence re-arms z-target on live
  ;; successor B; a stale pre-loop observer set would still silence z-target.
  (let [id         :vxgfnd285/rearm
        trigger    ::a-trigger            ; sorts first
        target     ::z-target             ; sorts last
        token-a    (Object.)
        silencings (atom [])]
    (rf/register-listener! :epoch trigger (fn [_] nil))
    (rf/register-listener! :epoch target (fn [_] nil))
    (rf.epoch.state/claim-frame-owner! id token-a)
    (rf.epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
    (is (= #{trigger target} (set (rf.epoch.state/cbs-observing-frame id)))
        "both identities observed A")
    (let [target-gen (cb-generation target)
          a-ev       (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil)]
      ;; Live successor B claims, dropping both observations.
      (rf.epoch.state/claim-frame-owner! id (Object.))
      (rf/register-listener! :trace ::vxgfnd285-rearm-silencing
        (fn [ev]
          (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
            (swap! silencings conj ev)
            (when (= trigger (:cb-id (:tags ev)))
              (rf.epoch.state/record-observation! target target-gen id)))))
      (rf.epoch.listeners/on-frame-destroyed! id token-a a-ev)
      (is (= [trigger] (map (comp :cb-id :tags) @silencings))
          "a-trigger is silenced; z-target, re-armed live mid-fan, is rechecked and skipped")
      (is (= [target] (rf.epoch.state/cbs-observing-frame id))
          "z-target is a live observer of B after the re-arm"))))

(deftest failed-delivery-rolls-back-the-reservation-only-then
  (let [id     :vxgfnd285/rollback
        cb     ::vxgfnd285-rollback-cb
        _      (rf/register-listener! :epoch cb (fn [_] nil))
        g      (cb-generation cb)
        claim! (fn [publish!]
                 (rf.epoch.state/claim-and-publish-delayed-silence! id cb g 0 publish!))]
    (is (thrown? clojure.lang.ExceptionInfo
          (claim! (fn [] (throw (ex-info "delivery failed" {})))))
        "a throwing publish propagates")
    (is (true? (claim! (fn [] nil)))
        "the failed delivery released its reservation, so the one signal is re-claimed")
    (is (nil? (claim! (fn [] nil)))
        "a second claim is refused while that reservation stands")))

(deftest a-failed-publish-prunes-only-its-own-seq-never-a-fresher-mark
  ;; A fresher publisher claims the identity while our publish runs outside the
  ;; locks; our late failure must not release ITS mark.
  (let [id :vxgfnd285/rollback-stale
        cb ::vxgfnd285-rollback-stale-cb
        _  (rf/register-listener! :epoch cb (fn [_] nil))
        g  (cb-generation cb)]
    (is (thrown? clojure.lang.ExceptionInfo
          (rf.epoch.state/claim-and-publish-delayed-silence! id cb g 0
            (fn []
              (rf.epoch.state/claim-and-publish-delayed-silence!
                id cb g (rf.epoch.state/current-terminal-silence-seq) (fn [] nil))
              (throw (ex-info "delivery failed" {}))))))
    (is (nil? (rf.epoch.state/claim-and-publish-delayed-silence! id cb g 0 (fn [] nil)))
        "the fresher mark survived our rollback and still refuses a claim at the original baseline")))

;; ---- BOUNDED --------------------------------------------------------------

(deftest held-predecessor-keeps-only-its-own-frame-marks-reclaimed-after
  ;; Predecessor A of F-a is held while successor B of F-a fires the one silence
  ;; and unrelated frames are destroyed. Only F-a's mark is retained; late A does
  ;; not re-emit (A→B→nil ABA); and the mark is reclaimed once A resolves.
  (let [f-a        :vxgfnd285/held-a
        cb         ::vxgfnd285-held-cb
        token-a    (Object.)
        token-b    (Object.)
        silencings (record-silences!)
        destroy!   (fn [id token]
                     (rf.epoch.listeners/on-frame-destroyed! id token
                       (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil)))]
    (rf/register-listener! :epoch cb (fn [_] nil))
    (rf.epoch.state/claim-frame-owner! f-a token-a)
    (rf.epoch.listeners/notify-listeners! {:frame f-a :epoch-id 1})
    (let [a-ev (rf.epoch.listeners/snapshot-terminal-destroy-evidence! f-a nil nil nil)]
      ;; Successor B re-arms cb, then retires — firing the one F-a silence.
      (rf.epoch.state/claim-frame-owner! f-a token-b)
      (rf.epoch.listeners/notify-listeners! {:frame f-a :epoch-id 2})
      (destroy! f-a token-b)
      (dotimes [i 3]
        (let [id    (keyword "vxgfnd285-held-other" (str i))
              token (Object.)]
          (rf.epoch.state/claim-frame-owner! id token)
          (rf.epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
          (destroy! id token)))
      (is (= #{[f-a cb]} (marks))
          "held A keeps exactly B's F-a mark; unrelated frames self-clean")
      (rf.epoch.listeners/on-frame-destroyed! f-a token-a a-ev)
      (is (= 1 (count (filter #(= f-a (:frame (:tags %))) @silencings)))
          "late A adds no F-a silence — the retired successor already fired it")
      (is (empty? (marks))
          "F-a's mark is reclaimed once A — its last predecessor — resolves"))))
