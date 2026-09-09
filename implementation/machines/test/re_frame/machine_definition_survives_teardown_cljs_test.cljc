(ns re-frame.machine-definition-survives-teardown-cljs-test
  "rf2-xjee — a `reg-machine` DEFINITION survives every actor teardown.

  The registration is a load-time PROGRAM that makes an address CREATABLE; the
  snapshot is the INSTANCE. Teardown ends the instance and never the program
  (Spec 005 §Liveness is derived from runtime-db, §Destroy is silent-idempotent,
  D4 / D5 / D7). `rf/clear` remains the one public spelling for permanent
  removal.

  The rule is INSEPARABLE from its amendment: a definition-bearing registrar
  entry is no longer a liveness signal. Without that, a destroyed singleton
  would read live for ever and a second destroy would re-run the whole teardown
  — a phantom `:rf.machine/destroyed` trace plus a re-fired resource release.
  Both halves are pinned here.

  Why the harm is not confined to the actor destroyed: `reg-machine` writes ONE
  registrar entry playing TWO roles — the singleton actor's ADDRESS and the
  shared TYPE DEFINITION every `[:rf.machine/spawn {:machine-id X}]` resolves
  through. Under the old behaviour any teardown of that address failed every
  later spawn of the type with `:rf.error/machine-spawn-unregistered-type`, and
  it was reachable with NO teardown code written by the author at all (a
  root-level `:final?` leaf fires the D7 auto-destroy).

  The file is named `*-cljs-test.cljc` so it is discovered by both
  cognitect.test-runner (JVM) and shadow-cljs (the `cljs-test$` ns-regexp)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines.test-support :as rf.machines.test-support]
   [re-frame.registrar :as rf.registrar]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- capture-traces [k]
  (let [a (atom [])]
    (rf.trace.tooling/register-listener! k (fn [ev] (swap! a conj ev)))
    a))

(defn- destroyed-count [traces]
  (count (filter #(= :rf.machine/destroyed (:operation %)) @traces)))

(defn- definition? [id]
  (:rf/machine? (rf.registrar/lookup :event id)))

;; ===========================================================================
;; (1) The shipped start / stop / start flow
;;
;; The concrete consumer this bead was filed against:
;; `examples/capabilities/resources/resources/core.cljs` registers ONE reader
;; singleton, starts it, stops it with `[:rf.machine/destroy <id>]`, and offers
;; "Open in reader" again. The second open had no definition left to resolve.
;; ===========================================================================

(deftest explicit-destroy-of-a-singleton-leaves-the-address-restartable
  (testing "start → explicit destroy → start again: the DEFINITION survives the
            teardown, so the address re-creates from its initial snapshot"
    (rf/reg-machine :xjee/reader
      {:initial :idle
       :data    {:opened 0}
       :actions {:open (fn [{d :data}] {:data (update d :opened inc)})}
       :states  {:idle {:on {:open {:target :reading :action :open}}}
                 :reading {:on {:close :idle}}}})
    (rf/reg-event :xjee/stop-reader
      (fn [_ _] {:fx [[:rf.machine/destroy :xjee/reader]]}))

    (rf/dispatch-sync [:xjee/reader [:open]])
    (is (= :reading (:state (snapshot :xjee/reader))) "first open worked")
    (is (= 1 (:opened (:data (snapshot :xjee/reader)))))

    (rf/dispatch-sync [:xjee/stop-reader])
    (is (nil? (snapshot :xjee/reader)) "the INSTANCE is gone")
    (is (definition? :xjee/reader)
        "the DEFINITION survives — teardown ended the instance, not the program")

    ;; The second open. Under the old behaviour this dispatch found no handler.
    (rf/dispatch-sync [:xjee/reader [:open]])
    (is (= :reading (:state (snapshot :xjee/reader)))
        "the address re-created and took the event")
    (is (= 1 (:opened (:data (snapshot :xjee/reader))))
        "and it started over from its INITIAL data — a fresh instance, not a
         resurrected one")))

(deftest final-auto-destroy-of-a-singleton-leaves-the-address-restartable
  (testing "D7 auto-destroy → ordinary event → fresh instance. This is the
            worst-visibility path: no teardown code is written by the author at
            all, a root-level `:final?` leaf is enough"
    (rf/reg-machine :xjee/finisher
      {:initial :running
       :data    {}
       :states  {:running {:on {:fin :done}}
                 :done    {:final? true}}})
    (rf/dispatch-sync [:xjee/finisher [:fin]])
    (is (nil? (snapshot :xjee/finisher)) "the instance auto-destroyed (D4/D7)")
    (is (definition? :xjee/finisher) "its DEFINITION survives (D7 rider)")
    ;; An ORDINARY event, not [:rf.machine/start] — the D5 revision covers the
    ;; whole event surface, because an absent snapshot is synthesised for any
    ;; event that arrives.
    (rf/dispatch-sync [:xjee/finisher [:anything]])
    (is (= :running (:state (snapshot :xjee/finisher)))
        "a fresh instance was born at the initial state (D5, revised)")))

;; ===========================================================================
;; (2) The harm is not confined to the actor destroyed — the TYPE keeps
;;     resolving for every later spawn anywhere in the app.
;; ===========================================================================

(deftest a-torn-down-singleton-address-still-spawns-its-type
  (testing "after ANY teardown of the address, [:rf.machine/spawn {:machine-id
            X}] still resolves — under the old behaviour every later spawn of
            the type failed :rf.error/machine-spawn-unregistered-type"
    (let [traces (capture-traces ::spawn-after)]
      (try
        (rf/reg-machine :xjee/type
          {:initial :running :data {} :states {:running {}}})
        (rf/reg-event :xjee/kill-type
          (fn [_ _] {:fx [[:rf.machine/destroy :xjee/type]]}))
        (rf/reg-event :xjee/spawn-type
          (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :xjee/type
                                              :fixed-actor-id :xjee/elsewhere}]]}))
        ;; Materialise the singleton, then tear it down.
        (rf/dispatch-sync [:xjee/type [:kick]])
        (is (some? (snapshot :xjee/type)) "the singleton instance is live")
        (rf/dispatch-sync [:xjee/kill-type])
        (is (nil? (snapshot :xjee/type)) "and torn down")

        (rf/dispatch-sync [:xjee/spawn-type])
        (is (some? (snapshot :xjee/elsewhere))
            "a spawn of the same TYPE, at an unrelated address, still resolves")
        (is (nil? (:rf/bootstrap-pending? (snapshot :xjee/elsewhere)))
            "and it BOOTSTRAPPED — a stranded actor keeps :rf/bootstrap-pending?
             because its :rf/machine-type resolves to nothing")
        (is (not-any? #(= :rf.error/machine-spawn-unregistered-type (:operation %))
                      @traces)
            "no :rf.error/machine-spawn-unregistered-type")
        (finally (rf.trace.tooling/unregister-listener! ::spawn-after))))))

(deftest a-spawned-actor-at-its-own-types-address-keeps-the-definition-and-siblings
  (testing "correction 4 — a definition-bearing address is NOT equivalent to a
            singleton. A SPAWNED actor may sit at a :fixed-actor-id equal to its
            own registered TYPE; destroying it must keep the definition, and a
            sibling actor of the type must survive and stay addressable"
    (rf/reg-machine :xjee/selfnamed
      {:initial :running
       :data    {}
       :actions {:mark (fn [{d :data}] {:data (assoc d :marked true)})}
       :states  {:running {:on {:mark {:action :mark}}}}})
    (rf/reg-event :xjee/install-pair
      (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :xjee/selfnamed
                                          :fixed-actor-id :xjee/selfnamed}]
                      [:rf.machine/spawn {:machine-id     :xjee/selfnamed
                                          :fixed-actor-id :xjee/sibling}]]}))
    (rf/reg-event :xjee/drop-selfnamed
      (fn [_ _] {:fx [[:rf.machine/destroy :xjee/selfnamed]]}))

    (rf/dispatch-sync [:xjee/install-pair])
    (is (some? (snapshot :xjee/selfnamed)) "actor installed at its own type name")
    (is (some? (snapshot :xjee/sibling))   "sibling installed elsewhere")

    (rf/dispatch-sync [:xjee/drop-selfnamed])
    (is (nil? (snapshot :xjee/selfnamed)) "the actor at the type's name is gone")
    (is (definition? :xjee/selfnamed)
        "the shared DEFINITION survives destroying an actor that occupied its
         keyword")
    (is (some? (snapshot :xjee/sibling)) "the sibling actor is untouched")
    (rf/dispatch-sync [:xjee/sibling [:mark]])
    (is (true? (:marked (:data (snapshot :xjee/sibling))))
        "and the sibling still resolves its handler through that definition")))

(deftest frame-destroy-preserves-the-definition-for-both-actor-kinds
  (testing "the frame-destroy cascade reaps every actor IN THE FRAME IT
            DESTROYS and clears NO definition — the SPAWNED branch reaches the
            same registrar cleanup as the singleton straggler branch, so both
            are covered"
    ;; The actors MUST live in the frame that is destroyed. An earlier form of
    ;; this test materialised them in the DEFAULT frame and destroyed an empty
    ;; unrelated one, so the cascade walked no actors at all and neither
    ;; teardown branch it names was ever entered — the definitions survived
    ;; because nothing had run (rf2-xjee audit of PR #9544). The rule is
    ;; unchanged; the proof is repaired.
    (let [exits (atom [])]
      ;; The singleton TYPE — its instance is materialised at its own
      ;; registered address, so frame teardown reaps it down the SINGLETON
      ;; STRAGGLER branch (a snapshot with no `:rf/machine-type`).
      (rf/reg-machine :xjee/fd-type
        {:initial :running
         :data    {}
         :states  {:running {:exit (fn [_] (swap! exits conj :singleton) {})}}})
      ;; The spawned TYPE — two instances, reaped down the SPAWNED branch,
      ;; which is the branch that reaches `registrar/unregister!`.
      (rf/reg-machine :xjee/fd-spawned
        {:initial :running
         :data    {}
         :states  {:running {:exit (fn [{data :data}]
                                     (swap! exits conj (:rf/self-id data))
                                     {})}}})
      (rf/reg-event :xjee/fd-spawn-pair
        (fn [_ _]
          {:fx [;; (i) at the TYPE's OWN keyword — the same-type-address
                ;; control. This is the dangerous one: unregistering this
                ;; actor's address would delete the shared DEFINITION.
                [:rf.machine/spawn {:machine-id     :xjee/fd-spawned
                                    :fixed-actor-id :xjee/fd-spawned}]
                ;; (ii) a SIBLING instance of the same type at a plain
                ;; address, which carries no definition of its own.
                [:rf.machine/spawn {:machine-id     :xjee/fd-spawned
                                    :fixed-actor-id :xjee/fd-sibling}]]}))
      (rf/make-frame {:id :xjee/fd-frame :doc "frame-destroy coverage"})
      ;; Materialise BOTH actor kinds INSIDE the frame that gets destroyed.
      (rf/dispatch-sync [:xjee/fd-type [:kick]] {:frame :xjee/fd-frame})
      (rf/dispatch-sync [:xjee/fd-spawn-pair] {:frame :xjee/fd-frame})
      (is (some? (snapshot :xjee/fd-frame :xjee/fd-type))
          "singleton instance live IN the frame under test")
      (is (some? (snapshot :xjee/fd-frame :xjee/fd-spawned))
          "spawned instance live at its TYPE's own keyword, in that frame")
      (is (some? (snapshot :xjee/fd-frame :xjee/fd-sibling))
          "sibling spawned instance live at a plain address, in that frame")

      (rf/destroy-frame! :xjee/fd-frame)

      ;; The cascade really walked all three — each `:exit` fired exactly once,
      ;; which is what proves BOTH branches were entered on real actors. (The
      ;; snapshot assertions below are weaker on their own, since releasing the
      ;; frame takes its runtime-db with it; the exit log is the discriminator.)
      (is (= 3 (count @exits))
          "exactly one :exit per actor in the destroyed frame — no re-run, no
           actor skipped")
      (is (= #{:singleton :xjee/fd-spawned :xjee/fd-sibling} (set @exits))
          "every actor in the destroyed frame ran its :exit — the singleton
           straggler branch AND the spawned branch both executed")
      (is (nil? (snapshot :xjee/fd-frame :xjee/fd-type))
          "the singleton INSTANCE is gone")
      (is (nil? (snapshot :xjee/fd-frame :xjee/fd-spawned))
          "the spawned INSTANCE at the type's own keyword is gone")
      (is (nil? (snapshot :xjee/fd-frame :xjee/fd-sibling))
          "the sibling spawned INSTANCE is gone")

      ;; ... and NEITHER definition went with them.
      (is (definition? :xjee/fd-type)
          "the singleton branch cleared no DEFINITION")
      (is (definition? :xjee/fd-spawned)
          "the spawned branch cleared no DEFINITION either — even though one
           of its instances occupied the type's own keyword")
      ;; The consequence that matters: both addresses are still creatable.
      (rf/dispatch-sync [:xjee/fd-type [:kick]])
      (is (some? (snapshot :xjee/fd-type))
          "the singleton address still re-creates after the frame teardown")
      (rf/dispatch-sync [:xjee/fd-spawn-pair])
      (is (some? (snapshot :xjee/fd-spawned))
          "the type still spawns after the frame teardown"))))

;; ===========================================================================
;; (3) The amendment — a definition-bearing entry is NOT a liveness signal.
;;     Exactly ONE teardown for a repeated destroy; ZERO for a never-started
;;     one. This is the silent-idempotence regression the amendment exists for.
;; ===========================================================================

(deftest repeated-destroy-of-a-singleton-runs-exactly-one-teardown
  (testing "with the definition preserved, a second [:rf.machine/destroy <id>]
            must NOT see the surviving registration as liveness and re-run the
            teardown: exactly ONE :rf.machine/destroyed, and the authored :exit
            runs exactly once"
    (let [exits  (atom 0)
          traces (capture-traces ::repeat-destroy)]
      (try
        (rf/reg-machine :xjee/once
          {:initial :running
           :data    {}
           :states  {:running {:exit (fn [_] (swap! exits inc) {})}}})
        (rf/reg-event :xjee/kill-once
          (fn [_ _] {:fx [[:rf.machine/destroy :xjee/once]]}))
        (rf/dispatch-sync [:xjee/once [:kick]])
        (is (some? (snapshot :xjee/once)))
        (rf/dispatch-sync [:xjee/kill-once])
        (rf/dispatch-sync [:xjee/kill-once])
        (rf/dispatch-sync [:xjee/kill-once])
        (is (= 1 (destroyed-count traces))
            "exactly ONE :rf.machine/destroyed across three destroys")
        (is (= 1 @exits) "the authored :exit ran exactly once")
        (is (definition? :xjee/once) "and the definition still stands")
        (finally (rf.trace.tooling/unregister-listener! ::repeat-destroy))))))

(deftest destroying-a-never-started-singleton-is-a-genuine-no-op
  (testing "a registered-but-never-started singleton has a DEFINITION and no
            instance, so a destroy tears nothing down and emits NO
            :rf.machine/destroyed — under the old reading the bare registration
            answered `live` and a phantom teardown ran"
    (let [exits  (atom 0)
          traces (capture-traces ::never-started)]
      (try
        (rf/reg-machine :xjee/unborn
          {:initial :running
           :data    {}
           :states  {:running {:exit (fn [_] (swap! exits inc) {})}}})
        (rf/reg-event :xjee/kill-unborn
          (fn [_ _] {:fx [[:rf.machine/destroy :xjee/unborn]]}))
        (is (nil? (snapshot :xjee/unborn)) "precondition: never started")
        (rf/dispatch-sync [:xjee/kill-unborn])
        (is (zero? (destroyed-count traces))
            "NO phantom :rf.machine/destroyed for an address that never had an
             instance")
        (is (zero? @exits) "and no :exit cascade ran")
        (is (definition? :xjee/unborn) "the definition is untouched")
        (finally (rf.trace.tooling/unregister-listener! ::never-started))))))

(deftest a-non-machine-entry-at-an-actor-address-is-still-cleared
  (testing "the negative control that proves the predicate is not over-broad: a
            plain handler squatting at an actor address carries no
            :rf/machine? true, so the teardown's registrar cleanup still clears
            it — that stale / externally-installed case is what the step exists
            for"
    (let [frame-id :xjee/plain-frame
          actor-id :xjee/plain-actor]
      (rf/reg-machine :xjee/plain-type
        {:initial :running :data {} :states {:running {}}})
      (rf/reg-event :xjee/install-plain
        (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :xjee/plain-type
                                            :fixed-actor-id actor-id}]]}))
      (rf/reg-event :xjee/drop-plain
        (fn [_ _] {:fx [[:rf.machine/destroy actor-id]]}))
      (rf/dispatch-sync [:xjee/install-plain])
      ;; Squat a plain (non-machine) handler at the live actor's address.
      (rf.registrar/register! :event actor-id {:fn (fn [db _] db)})
      (is (some? (rf.registrar/lookup :event actor-id)))
      (is (not (definition? actor-id)) "it carries no :rf/machine? true")
      (rf/dispatch-sync [:xjee/drop-plain])
      (is (nil? (rf.registrar/lookup :event actor-id))
          "the plain entry WAS cleared — only definitions are spared")
      (is (definition? :xjee/plain-type)
          "and the type's own definition, at a different keyword, is untouched")
      frame-id)))

;; ===========================================================================
;; (4) `rf/clear` is unchanged and remains the spelling for permanent removal.
;; ===========================================================================

(deftest rf-clear-still-removes-the-definition
  (testing "destroy ends an INSTANCE; clear removes a REGISTRATION. The two
            verbs stay on their own axes (Conventions §Tear-down verb axis)"
    (rf/reg-machine :xjee/clearable
      {:initial :running :data {} :states {:running {}}})
    (rf/reg-event :xjee/kill-clearable
      (fn [_ _] {:fx [[:rf.machine/destroy :xjee/clearable]]}))
    (rf/dispatch-sync [:xjee/clearable [:kick]])
    (rf/dispatch-sync [:xjee/kill-clearable])
    (is (definition? :xjee/clearable) "destroy left the definition standing")
    (rf/clear :event :xjee/clearable)
    (is (nil? (rf.registrar/lookup :event :xjee/clearable))
        "clear removed it permanently")
    (is (nil? (snapshot :xjee/clearable))
        "and the address is no longer creatable — nothing re-materialises")))
