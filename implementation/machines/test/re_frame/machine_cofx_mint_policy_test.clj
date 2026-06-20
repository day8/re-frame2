(ns re-frame.machine-cofx-mint-policy-test
  "Coverage for two machine cofx-correctness contracts:

    1. The machine consumer-attachment ensure path mints under the
       EFFECTIVE mint policy (per-call opt ▸ frame config ▸ `:live`). Under
       `:strict` (replay / the `:test` preset) a declared-absent
       generator-backed guard/action fact is
       `:rf.error/missing-required-cofx`, NEVER freshly minted;
       `:explicit-live` opts back into generation.

    2. A same-macrostep RAISED internal event (`[:raise <event>]`,
       including synthetic compound / parallel `:on-done` signals) has its
       selected transition's named guard/action `:rf.cofx/requires` ensured
       BEFORE selection, under the same mint policy. The external event's
       ensure-set runs once before the macrostep and does not cover a
       guard/action a raise can reach, so the engine re-ensures in-drain — a
       generator-backed fact is minted and a provided-but-absent fact raises
       missing-required rather than being read as nil.

  These drive the REAL dispatch path (`rf/dispatch-sync` through the live
  machine handler + transition engine), so the test hits the actual path,
  not a routed-around green."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.machines.cofx-attach :as cofx-attach]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private SCRIPTED-TIME-MS 1234500000)

;; ===========================================================================
;; the ensure path honours the effective mint policy
;; ===========================================================================

(deftest strict-ensure-refuses-to-mint-guard-fact-throws
  (testing "white-box: the machine ensure step under :strict refuses to mint a
            declared-absent generator-backed GUARD fact and raises
            :rf.error/missing-required-cofx instead of the old hard-wired :live
            that silently generated a fresh per-run value. Drives the real
            ensure path so the throw surfaces verbatim; dispatch-sync captures
            such throws into its error pipeline, so this white-box path is the
            deterministic throw surface."
    (rf/reg-cofx :mint/roll {:recordable? true} (fn [] 6))
    (let [m (cofx-attach/index-ensure-sets
              {:initial :idle
               :data    {}
               :guards  {:rolled-six?
                         {:rf.cofx/requires [:mint/roll]
                          :fn (fn [{cofx :rf.cofx}] (= 6 (:mint/roll cofx)))}}
               :states  {:idle {:on {:go {:target :done :guard :rolled-six?}}}
                         :done {}}})
          ;; the token has no :mint/roll; under :strict the ensure must throw
          ;; missing-required (not mint).
          e (is (thrown? ExceptionInfo
                         (cofx-attach/ensure-cofx
                           m {:state :idle :data {}} [:go]
                           {} nil :mint/strict-guard :strict)))]
      (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data e)))
          "strict refused to mint the declared-absent generator-backed fact"))))

(deftest strict-per-call-machine-does-not-advance
  (testing "end-to-end: under a per-call `:rf.cofx/mint-policy :strict` the
            machine does NOT mint the generator-backed guard fact — the missing-
            required failure raised by the dispatch-time ensure step fails the
            macrostep atomically (no snapshot committed), so the machine NEVER
            reaches :done. Proves the policy is threaded into the live handler
            (vs the old always-:live mint that would advance to :done)"
    (rf/reg-cofx :mint/roll-strict {:recordable? true} (fn [] 6))
    (let [m {:initial :idle
             :data    {}
             :guards  {:rolled-six?
                       {:rf.cofx/requires [:mint/roll-strict]
                        :fn (fn [{cofx :rf.cofx}] (= 6 (:mint/roll-strict cofx)))}}
             :states  {:idle {:on {:go {:target :done :guard :rolled-six?}}}
                       :done {}}}]
      (rf/reg-machine :mint/strict-noadvance m)
      (rf/dispatch-sync [:mint/strict-noadvance [:go]]
                        {:rf.cofx           {:rf/time-ms SCRIPTED-TIME-MS}
                         :rf.cofx/mint-policy :strict})
      (is (not= :done (mtest/machine-state :mint/strict-noadvance))
          "strict refused to mint → the guard fact was missing-required → the
           macrostep failed atomically → the machine did NOT advance to :done"))))

(deftest explicit-live-per-call-opts-back-into-minting
  (testing "the SAME machine under a per-call `:rf.cofx/mint-policy
            :explicit-live` DOES mint the generator-backed fact — the guard
            reads the generated value (6) and fires the transition. Proves the
            policy is threaded (not ignored): :strict refuses, :explicit-live
            generates, from the same code"
    (rf/reg-cofx :mint/roll2 {:recordable? true} (fn [] 6))
    (let [seen (atom ::unset)
          m {:initial :idle
             :data    {}
             :guards  {:rolled-six?
                       {:rf.cofx/requires [:mint/roll2]
                        :fn (fn [{cofx :rf.cofx}]
                              (reset! seen (:mint/roll2 cofx))
                              (= 6 (:mint/roll2 cofx)))}}
             :states  {:idle {:on {:go {:target :done :guard :rolled-six?}}}
                       :done {}}}]
      (rf/reg-machine :mint/live-guard m)
      (rf/dispatch-sync [:mint/live-guard [:go]]
                        {:rf.cofx           {:rf/time-ms SCRIPTED-TIME-MS}
                         :rf.cofx/mint-policy :explicit-live})
      (is (= 6 @seen) "explicit-live generated the fact the guard read")
      (is (= :done (mtest/machine-state :mint/live-guard))
          "the guard fired on the generated value"))))

(deftest default-live-still-mints
  (testing "with NO per-call / frame-config policy the router default :live
            still mints (the no-regression check — the common live runtime
            keeps minting generator-backed facts)"
    (rf/reg-cofx :mint/roll3 {:recordable? true} (fn [] 6))
    (let [seen (atom ::unset)
          m {:initial :idle
             :data    {}
             :guards  {:rolled-six?
                       {:rf.cofx/requires [:mint/roll3]
                        :fn (fn [{cofx :rf.cofx}]
                              (reset! seen (:mint/roll3 cofx))
                              (= 6 (:mint/roll3 cofx)))}}
             :states  {:idle {:on {:go {:target :done :guard :rolled-six?}}}
                       :done {}}}]
      (rf/reg-machine :mint/default-guard m)
      (rf/dispatch-sync [:mint/default-guard [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= 6 @seen) "default :live minted the fact")
      (is (= :done (mtest/machine-state :mint/default-guard))))))

(deftest strict-bootstrap-ensure-refuses-to-mint-birth-fact-throws
  (testing "white-box: the BIRTH ensure step (`cofx-attach/bootstrap-ensure-
            cofx`) under `:strict` refuses to mint a generator-backed initial-
            entry action fact and raises missing-required — the frame-config /
            replay path the :test preset selects. Drives the real bootstrap
            ensure path"
    (rf/reg-cofx :mint/birth-roll {:recordable? true} (fn [] 9))
    (let [m (cofx-attach/index-ensure-sets
              {:initial :booting
               :data    {}
               :actions {:stamp-birth
                         {:rf.cofx/requires [:mint/birth-roll]
                          :fn (fn [{:keys [data] cofx :rf.cofx}]
                                {:data (assoc data :birth (:mint/birth-roll cofx))})}}
               :states  {:booting {:entry :stamp-birth}}})
          e (is (thrown? ExceptionInfo
                         (cofx-attach/bootstrap-ensure-cofx
                           m {} nil :mint/strict-birth :strict)))]
      (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data e)))
          "strict refused to mint the birth :entry action's generator-backed fact"))))

(deftest live-frame-config-tier-still-mints-end-to-end
  (testing "end-to-end: a :live (default) frame mints normally — the frame-tier
            resolution does not break the common live runtime. (Pairs with the
            :strict white-box above: the tier is read, not ignored.)"
    (rf/reg-cofx :mint/birth-roll2 {:recordable? true} (fn [] 9))
    (let [m {:initial :booting
             :data    {}
             :actions {:stamp-birth
                       {:rf.cofx/requires [:mint/birth-roll2]
                        :fn (fn [{:keys [data] cofx :rf.cofx}]
                              {:data (assoc data :birth (:mint/birth-roll2 cofx))})}}
             :states  {:booting {:entry :stamp-birth}}}]
      (rf/reg-machine :mint/live-birth m)
      (rf/dispatch-sync [:mint/live-birth [:rf.machine/start]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= 9 (:birth (mtest/machine-data :mint/live-birth)))
          "default :live minted the birth :entry fact"))))

;; ===========================================================================
;; raised-event transitions get their cofx ensured
;; ===========================================================================

(deftest raised-user-event-guard-fact-ensured
  (testing "a same-macrostep RAISED user event whose selected guard requires a
            generator-backed recordable fact has it ENSURED before selection —
            the guard reads the GENERATED value (not nil). The external event's
            ensure-set never covered the raised event's guard, so before
            rf2-xsdn5h the raise read nil"
    (rf/reg-cofx :raise/roll {:recordable? true} (fn [] 6))
    (let [seen (atom ::unset)
          m {:initial :a
             :data    {}
             :guards  {:rolled-six?
                       {:rf.cofx/requires [:raise/roll]
                        :fn (fn [{cofx :rf.cofx}]
                              (reset! seen (:raise/roll cofx))
                              (= 6 (:raise/roll cofx)))}}
             :actions {;; :go's action raises [:inner], which selects a guard
                       ;; that requires :raise/roll — NOT in :go's ensure-set.
                       :raise-inner (fn [_] {:fx [[:raise [:inner]]]})}
             :states  {:a {:on {:go {:target :b :action :raise-inner}}}
                       :b {:on {:inner {:target :done :guard :rolled-six?}}}
                       :done {}}}]
      (rf/reg-machine :raise/user-guard m)
      (rf/dispatch-sync [:raise/user-guard [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= 6 @seen)
          "the RAISED event's guard read the GENERATED fact (not nil) —
           ensured before the raised transition's selection")
      (is (= :done (mtest/machine-state :raise/user-guard))
          "the raised event's guard fired on the ensured value"))))

(deftest raised-user-event-strict-missing-fact-throws
  (testing "white-box on the engine: a PROVIDED (non-generator) fact required
            by a RAISED event's guard, absent from the in-flight token, raises
            missing-required from the in-engine raised-event ensure step — under
            :strict the raise's required fact is ENFORCED, not silently read as
            nil (the rf2-xsdn5h hole). Drives `machine-transition` directly with
            a `:rf/cofx`-carrying machine so the throw surfaces verbatim
            (dispatch-sync captures it into the error pipeline)"
    (let [m (-> (cofx-attach/index-ensure-sets
                  {:initial :a
                   :data    {}
                   :guards  {:needs-time?
                             {:rf.cofx/requires [:rf/time-ms]
                              :fn (fn [{cofx :rf.cofx}] (some? (:rf/time-ms cofx)))}}
                   :actions {:raise-inner (fn [_] {:fx [[:raise [:inner]]]})}
                   :states  {:a {:on {:go {:target :b :action :raise-inner}}}
                             :b {:on {:inner {:target :done :guard :needs-time?}}}
                             :done {}}})
                ;; carry the in-flight token (deliberately WITHOUT :rf/time-ms)
                ;; + the :strict policy, exactly as the live handler stamps them.
                (assoc :rf/cofx {} :rf/cofx-mint-policy :strict))
          e (is (thrown? ExceptionInfo
                         (machines/machine-transition m {:state :a :data {}} [:go])))]
      (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data e)))
          "the RAISED event's provided-fact requirement surfaced missing-required
           from the in-engine ensure — not a silent nil"))))

(deftest compound-on-done-raised-signal-ensures-cofx
  (testing "a compound `:on-done` is implemented as `[:raise [:rf.machine/done
            path]]`; an `:on-done` guard requiring a generator-backed fact has
            it ENSURED for the synthetic raised done signal (the same hole as a
            user :raise)"
    (rf/reg-cofx :raise/done-roll {:recordable? true} (fn [] 6))
    (let [seen (atom ::unset)
          m {:initial :work
             :data    {}
             :guards  {:rolled-six?
                       {:rf.cofx/requires [:raise/done-roll]
                        :fn (fn [{cofx :rf.cofx}]
                              (reset! seen (:raise/done-roll cofx))
                              (= 6 (:raise/done-roll cofx)))}}
             :states  {:work {:initial :step
                              :states  {:step {:on {:fin :fin}}
                                        :fin  {:final? true}}
                              ;; reaching :work's :final? child raises
                              ;; [:rf.machine/done [:work]]; the :on-done guard
                              ;; requires :raise/done-roll (not in [:fin]'s set).
                              :on-done {:target :done :guard :rolled-six?}}
                       :done {}}}]
      (rf/reg-machine :raise/compound-done m)
      (rf/dispatch-sync [:raise/compound-done [:fin]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= 6 @seen)
          "the synthetic :on-done raised-done guard read the GENERATED fact —
           ensured for the raised done signal")
      (is (= :done (mtest/machine-state :raise/compound-done))
          "the :on-done fired on the ensured value"))))

(deftest parallel-region-raise-guard-fact-ensured
  (testing "a PARALLEL region's raise re-enters the parent's internal-event
            queue and is re-broadcast; a region guard the re-broadcast selects,
            requiring a generator-backed fact, has it ENSURED at the parent
            before the re-broadcast (the parallel arm of the rf2-xsdn5h hole)"
    (rf/reg-cofx :raise/par-roll {:recordable? true} (fn [] 6))
    (let [seen (atom ::unset)
          m {:type    :parallel
             :data    {}
             :guards  {:rolled-six?
                       {:rf.cofx/requires [:raise/par-roll]
                        :fn (fn [{cofx :rf.cofx}]
                              (reset! seen (:raise/par-roll cofx))
                              (= 6 (:raise/par-roll cofx)))}}
             :actions {:raise-inner (fn [_] {:fx [[:raise [:inner]]]})}
             :regions {:left  {:initial :one
                               :states  {:one {:on {:go {:target :two :action :raise-inner}}}
                                         :two {}}}
                       ;; :right's :on {:inner ...} guard requires :raise/par-roll;
                       ;; :inner is raised by :left and re-broadcast across regions.
                       :right {:initial :one
                               :states  {:one {:on {:inner {:target :two :guard :rolled-six?}}}
                                         :two {}}}}}]
      (rf/reg-machine :raise/parallel-region m)
      (rf/dispatch-sync [:raise/parallel-region [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= 6 @seen)
          "the re-broadcast raised event's region guard read the GENERATED fact
           — ensured at the parent before the re-broadcast")
      (is (= {:left :two :right :two}
             (:state (mtest/snapshot :raise/parallel-region)))
          "both regions advanced: :left on :go, :right on the ensured :inner"))))

;; ===========================================================================
;; wrote-db `:offending-value` redacted at egress
;; ===========================================================================

(deftest wrote-db-offending-value-redacted-at-egress
  (testing "an action that wrongly returns :db carrying a SENSITIVE payload
            does NOT leak it raw on the :rf.error/machine-action-wrote-db trace
            — `:offending-value` is summarized to :rf/redacted at the egress
            chokepoint; the structural slots (`:action-id`) survive"
    (let [m {:initial :a
             :actions {:bad (fn [_] {:db   {:auth {:token "super-secret-jwt"}}
                                     :data {:legit 1}})}
             :states  {:a {:on {:go {:target :b :action :bad}}} :b {}}}]
      (rf/reg-machine :wrote-db/sensitive m)
      (mtest/with-trace-capture seen
        (rf/dispatch-sync [:wrote-db/sensitive [:go]])
        (let [errs (filterv #(= :rf.error/machine-action-wrote-db (:operation %)) @seen)]
          (is (= 1 (count errs)) "exactly one wrote-db error")
          (let [tags (:tags (first errs))]
            (is (= :rf/redacted (:offending-value tags))
                "the offending app-db (with the secret) is redacted at egress")
            (is (= :bad (:action-id tags))
                "the structural :action-id slot survives so the operator can locate it")
            ;; the secret string appears NOWHERE in the egressed tags.
            (is (not (re-find #"super-secret-jwt" (pr-str tags)))
                "the sensitive token does not appear anywhere in the egressed trace")))))))

(deftest update-snapshot-wrote-db-uses-actor-id-and-redacts
  (testing "the :rf.machine/update-snapshot escape-hatch :db hard-disallow now
            addresses the actor under :actor-id (aligned with the action-effect
            path / Spec 009 rf2-yyvtk5) and redacts :offending-value at egress"
    (let [m {:initial :a
             :actions {:patch
                       (fn [_]
                         {:fx [[:rf.machine/update-snapshot
                                {:rf/machine-id :wrote-db/upd
                                 :rf/patch      {:data {:n 7}
                                                 :db   {:secret "leak-me"}}}]]})}
             :states  {:a {:on {:go {:target :a :action :patch}}}}}]
      (rf/reg-machine :wrote-db/upd m)
      (rf/dispatch-sync [:wrote-db/upd [:noop]])
      (mtest/with-trace-capture seen
        (rf/dispatch-sync [:wrote-db/upd [:go]])
        (let [errs (filterv #(= :rf.error/machine-action-wrote-db (:operation %)) @seen)]
          (is (= 1 (count errs)) "exactly one wrote-db error from the escape hatch")
          (let [tags (:tags (first errs))]
            (is (= :wrote-db/upd (:actor-id tags))
                "the escape-hatch error addresses the actor under :actor-id")
            (is (= :rf/redacted (:offending-value tags))
                "the offending :db patch value is redacted at egress")
            (is (not (re-find #"leak-me" (pr-str tags)))
                "the sensitive patch value does not appear in the egressed trace")))))))
