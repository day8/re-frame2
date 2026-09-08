(ns re-frame.final-region-sourcing-test
  "Per Spec 005 §Final states §`:final?` constraints (Parallel regions and
  `:final?`) + §Root parallel `:on` — the ancestor fallback.

  THE QUESTION (rf2-hu69, from the 2026-09-07 XState v6 parity review).
  XState `6.0.0-alpha.17` changed conflict resolution so that \"a transition
  sourced in a final state now yields to a conflicting transition from a live
  sibling region instead of preempting it\". Asking the same question of
  re-frame2 needs one prior fact settled: can a region whose active leaf is
  `:final?` SOURCE a transition at all? If it cannot, the v6 conflict is
  unreachable here by construction and no resolution rule is needed.

  THE MEASURED ANSWER, and it splits by DEPTH:

    - The `:final?` LEAF itself sources nothing. Registration refuses `:on`,
      `:always`, `:after`, `:spawn` and `:spawn-all` on a `:final?` state with
      `:rf.error/machine-final-state-has-transitions`. That half is a
      construction-time guarantee, stronger than a runtime halt.

    - The REGION does not halt. It keeps participating in every selection
      pass, and `pick-transition` / `pick-always-transition` walk leaf→root,
      so a transition declared on an ANCESTOR of the final leaf — or on the
      region body's own root `:on` — still selects for that region and moves
      it off its final leaf. All four sourcing routes reach it: `:on`,
      `:always`, `:after`, and a `:raise` re-broadcast.

  CONSEQUENCE — real, measured, and NOT the v6 hazard it was first read as
  (see the ruling below). Per §Root parallel `:on` the root transition is
  suppressed ENTIRELY when any region handles the event. \"Any region\" includes
  a region sitting on a `:final?` leaf — so a completed region can suppress a
  root transition that would have moved a LIVE sibling.
  `root-transition-suppressed-by-final-region` pins that, with
  `root-transition-fires-when-no-region-competes` as its control.

  These fixtures PIN TODAY'S BEHAVIOUR, and that behaviour is now RULED.
  rf2-hu69 (2026-09-08) RETAINS the atomic ancestor-fallback rule unchanged:
  the parallel root's `:on` is suppressed entirely when ANY region handles the
  event, one resting on a `:final?` leaf included. No engine change was made
  and none is owed. Nor is there a v6 divergence to close —
  `xstate@6.0.0-alpha.52` produces the IDENTICAL result on the machine in (7).
  What these fixtures assert is still what the engine does, not what it ought
  to do; the ruling is recorded in Spec 005 §`:final?` constraints."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; snapshot lookup via the shared machines test-support
;; — no hardcoded `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- state-of [machine-id]
  (:state (snapshot machine-id)))

;; ---- (1) the LEAF sources nothing — registration refuses all four ----------
;;
;; Spec 005 §`:final?` constraints: "No `:on`, `:always`, `:after`, `:spawn`,
;; `:spawn-all` on a `:final?` state." Measured for the three transition slots
;; the v6 question names; `:spawn` / `:spawn-all` share the same reject arm in
;; `validate-final-state!`.

(deftest final-leaf-cannot-declare-any-transition-slot
  (testing ":on / :always / :after on a :final? leaf are refused at REGISTRATION"
    (doseq [[label slot] [[":on"     {:on {:ev {:target :other}}}]
                          [":always" {:always {:target :other}}]
                          [":after"  {:after {1000 {:target :other}}}]]]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"final means final"
            (rf/reg-machine
              (keyword "hu69" (str "leaf" (hash label)))
              {:initial :working
               :states  {:working {:on {:finish {:target :done}}}
                         :done    (merge {:final? true} slot)
                         :other   {}}}))
          (str label " on a :final? leaf is rejected with "
               ":rf.error/machine-final-state-has-transitions")))))

;; ---- (2) :on — the region ROOT's ancestor fallback still fires -------------

(deftest final-region-sources-on-via-region-root
  (testing "a region sitting on its :final? leaf still takes an :on declared on
            the REGION ROOT — the region does not halt"
    (rf/reg-machine
      :hu69/root-on
      {:type :parallel
       :regions
       {:mode {:initial :working
               ;; the region body's own root `:on` — that region's ancestor
               ;; fallback (Spec 005 §A region-local `:target` never names a
               ;; sibling region).
               :on      {:reset {:target :working}}
               :states  {:working {:on {:finish {:target :done}}}
                         :done    {:final? true}}}
        :work {:initial :live
               :states  {:live  {:on {:reset {:target :again}}}
                         :again {}}}}})
    (rf/dispatch-sync [:hu69/root-on [:finish]])
    (is (= {:mode :done :work :live} (state-of :hu69/root-on))
        ":mode reached its :final? leaf; :work is still live")
    (rf/dispatch-sync [:hu69/root-on [:reset]])
    (is (= {:mode :working :work :again} (state-of :hu69/root-on))
        ":mode moved OFF its final leaf via the region-root :on — a :final?
         region CAN source an :on transition")))

;; ---- (3) :on — a compound ANCESTOR of the final leaf also fires ------------

(deftest final-region-sources-on-via-compound-ancestor
  (testing "the same holds one level down: an :on on the compound ancestor of a
            :final? leaf selects while that leaf is final"
    (rf/reg-machine
      :hu69/anc-on
      {:type :parallel
       :regions
       {:mode {:initial :phase
               :states  {:phase {:initial :working
                                 :on      {:reset {:target [:phase :working]}}
                                 :states  {:working {:on {:finish {:target :done}}}
                                           :done    {:final? true}}}}}
        :work {:initial :live
               :states  {:live  {:on {:reset {:target :again}}}
                         :again {}}}}})
    (rf/dispatch-sync [:hu69/anc-on [:finish]])
    (is (= {:mode [:phase :done] :work :live} (state-of :hu69/anc-on))
        ":mode's active leaf is the :final? :done inside :phase")
    (rf/dispatch-sync [:hu69/anc-on [:reset]])
    (is (= {:mode [:phase :working] :work :again} (state-of :hu69/anc-on))
        "the ancestor's :on selected for a region whose leaf was final")))

;; ---- (4) :always — the EVENTLESS route, no event aimed at the region -------
;;
;; The sharpest of the four: no event reaches :mode at all. The ancestor's
;; guarded `:always` becomes enabled when a LIVE sibling moves, and the
;; parent-owned eventless round then moves the final region.
;;
;; (The `:always` target deliberately ESCAPES the ancestor. An `:always` on
;;  `:phase` targeting a state still inside `:phase` re-enables itself every
;;  round and trips `:always-depth-limit`, rolling the whole macrostep back.)

(deftest final-region-sources-always-via-ancestor
  (testing "a guarded :always on the ancestor of a :final? leaf fires in a
            parent-owned eventless round — the final region moves with NO event
            addressed to it"
    (rf/reg-machine
      :hu69/anc-always
      {:type   :parallel
       :guards {:work-armed? (fn [{:keys [all-state]}] (= :armed (:work all-state)))}
       :regions
       {:mode {:initial :phase
               :states  {:phase   {:initial :working
                                   :always  [{:target :escaped :guard :work-armed?}]
                                   :states  {:working {:on {:finish {:target :done}}}
                                             :done    {:final? true}}}
                         :escaped {}}}
        :work {:initial :live
               :states  {:live  {:on {:arm {:target :armed}}}
                         :armed {}}}}})
    (rf/dispatch-sync [:hu69/anc-always [:finish]])
    (is (= {:mode [:phase :done] :work :live} (state-of :hu69/anc-always))
        ":mode is final; the :always guard is still false")
    (rf/dispatch-sync [:hu69/anc-always [:arm]])
    (is (= {:mode :escaped :work :armed} (state-of :hu69/anc-always))
        ":work armed, and the eventless round then moved the FINAL :mode region
         off its final leaf — the :always route is open")))

;; ---- (5) :after — an ancestor timer stays live over a final leaf -----------
;;
;; Per Spec 005 §Hierarchy interaction a timer is live while its scheduling
;; node is on the active path and its per-path epoch is unchanged. A leaf-only
;; transition onto a final sibling leaf leaves the ancestor on the path and its
;; epoch untouched, so the ancestor's `:after` survives the region going final.
;; The synthetic elapsed event is dispatched manually (the after_test.clj
;; idiom) so the JVM assertion is deterministic.

(deftest final-region-sources-after-via-ancestor
  (testing "an ancestor's :after timer fires while the region's leaf is :final?"
    (rf/reg-machine
      :hu69/anc-after
      {:type :parallel
       :regions
       {:mode {:initial :phase
               :states  {:phase {:initial :working
                                 :after   {1000 {:target [:phase :expired]}}
                                 :states  {:working {:on {:finish {:target :done}}}
                                           :done    {:final? true}
                                           :expired {}}}}}
        :work {:initial :live
               :states  {:live {}}}}})
    (rf/dispatch-sync [:hu69/anc-after [:finish]])
    (is (= {:mode [:phase :done] :work :live} (state-of :hu69/anc-after))
        ":mode is on its :final? leaf, still inside the :after-bearing :phase")
    (rf/dispatch-sync [:hu69/anc-after
                       [:rf.machine.timer/after-elapsed 1000 1 [:mode :phase]]])
    (is (= {:mode [:phase :expired] :work :live} (state-of :hu69/anc-after))
        "the ancestor's timer was still live and moved the final region — the
         :after route is open")))

;; ---- (6) raise — a live sibling's raise re-broadcasts into the final region -

(deftest final-region-sources-on-via-raise-rebroadcast
  (testing "a :raise from a LIVE region re-broadcasts across every region and is
            taken by the FINAL region's root :on"
    (rf/reg-machine
      :hu69/raise
      {:type    :parallel
       :actions {:shout (fn [_] {:fx [[:raise [:echo]]]})}
       :regions
       {:mode {:initial :working
               :on      {:echo {:target :working}}
               :states  {:working {:on {:finish {:target :done}}}
                         :done    {:final? true}}}
        :work {:initial :live
               :states  {:live {:on {:go {:target :sent :action :shout}}}
                         :sent {}}}}})
    (rf/dispatch-sync [:hu69/raise [:finish]])
    (is (= {:mode :done :work :live} (state-of :hu69/raise))
        ":mode is final before the raise")
    (rf/dispatch-sync [:hu69/raise [:go]])
    (is (= {:mode :working :work :sent} (state-of :hu69/raise))
        "the raised :echo reached the final region and moved it — the raise
         route is open, inside the one atomic macrostep")))

;; ---- (7) THE CONFLICT — a final region preempts the parallel ROOT ----------
;;
;; Spec 005 §Transition broadcast: "If any region handled the event, the
;; snapshot commits with that region's transition applied, the root `:on` is
;; SUPPRESSED." Measured: "any region" includes one sitting on a :final? leaf,
;; so a COMPLETED region suppresses a root transition that would have moved a
;; LIVE sibling.
;;
;; RULED (rf2-hu69, 2026-09-08) — this behaviour is RETAINED, unchanged, and
;; the earlier framing of it as \"the re-frame2 shape of the hazard XState v6
;; alpha.17 addressed from the other direction\" is REFUTED. `xstate@6.0.0-
;; alpha.52` produces the IDENTICAL result on this machine, so there is no
;; divergence to close. alpha.17's clause tests the transition's ACTUAL SOURCE
;; NODE, and the upstream shape it addresses — `on` declared directly on a
;; final node targeting a sibling region — is rejected here twice over
;; (registration refuses `:on` on a `:final?` state, and a region-local target
;; cannot name a sibling region). The mechanism measured below is
;; ANCESTOR-versus-ROOT; see the control's note on what actually causes it.

(deftest root-transition-suppressed-by-final-region
  (testing "a :final? region's ancestor :on suppresses the parallel root
            transition — including one whose target is a LIVE sibling region"
    (rf/reg-machine
      :hu69/suppress
      {:type :parallel
       ;; the root would rescue the live :work region …
       :on   {:bump {:target [:work :rescued]}}
       :regions
       {:mode {:initial :working
               ;; … but the COMPLETED :mode region competes for :bump.
               :on      {:bump {:target :working}}
               :states  {:working {:on {:finish {:target :done}}}
                         :done    {:final? true}}}
        :work {:initial :live
               :states  {:live    {}
                         :rescued {}}}}})
    (rf/dispatch-sync [:hu69/suppress [:finish]])
    (is (= {:mode :done :work :live} (state-of :hu69/suppress))
        ":mode has completed; :work is live and unrescued")
    (rf/dispatch-sync [:hu69/suppress [:bump]])
    (is (= {:mode :working :work :live} (state-of :hu69/suppress))
        "the completed region won: it took :bump and the root transition was
         suppressed ENTIRELY, so the LIVE :work region did NOT reach :rescued")))

(deftest root-transition-fires-when-no-region-competes
  (testing "CONTROL for the case above — the identical machine with no region
            handler for :bump lets the root transition through, proving the
            suppression is A REGION HANDLING THE EVENT and not a dead root :on.
            The cause is the ANCESTOR HANDLER, not the finality: keep that
            handler but make `done` an ordinary leaf and the suppression is
            unchanged. The final region only makes it visible."
    (rf/reg-machine
      :hu69/control
      {:type :parallel
       :on   {:bump {:target [:work :rescued]}}
       :regions
       {:mode {:initial :working
               ;; NO :bump handler anywhere in this region.
               :states  {:working {:on {:finish {:target :done}}}
                         :done    {:final? true}}}
        :work {:initial :live
               :states  {:live    {}
                         :rescued {}}}}})
    (rf/dispatch-sync [:hu69/control [:finish]])
    (is (= {:mode :done :work :live} (state-of :hu69/control))
        "same starting configuration as the suppression case")
    (rf/dispatch-sync [:hu69/control [:bump]])
    (is (= {:mode :done :work :rescued} (state-of :hu69/control))
        "with nothing competing, the root transition fires and rescues :work")))
