(ns re-frame.story.play.presence-cljs-test
  "Story's presence rung: the `[:flush-presence]` script step consumes the
  framework's presence clock so a presence-bearing variant settles
  deterministically during playback. A presence boundary retains a removed
  child until its timeout fires; that retention is a clock, not a queue, so
  no settled-boundary rung settles it and `[:wait ms]` is the determinism
  opt-out the gate refuses.

  The rung is a seam: a host installs its own advance through
  `install-presence-flush!`, and no supported substrate publishes a
  presence-clock verb, so a stub host is the right instrument. These tests
  pin that playback reaches the installed verb and fails closed when there
  is none, on both hosts (`.cljc` ending `-cljs-test`)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                        :as rf]
            [re-frame.frame                       :as rf.frame]
            [re-frame.router                      :as rf.router]
            [re-frame.registrar                   :as rf.registrar]
            [re-frame.substrate.plain-atom        :as rf.substrate.plain-atom]
            [re-frame.story                       :as rf.story]
            [re-frame.story.determinism           :as rf.story.determinism]
            [re-frame.story.late-bind             :as rf.story.late-bind]
            [re-frame.story.plan                  :as rf.story.plan]
            [re-frame.story.play.presence         :as rf.story.play.presence]
            [re-frame.story.play.runner           :as rf.story.play.runner]
            [re-frame.story.play.runner-events    :as rf.story.play.runner-events]
            [re-frame.story.requirements          :as rf.story.requirements]))

;; ===========================================================================
;; PURE: the step grammar (both hosts, no runtime)
;; ===========================================================================

(deftest flush-presence-is-a-known-step
  ;; An unknown step would lift to a :dispatch that still validates, so only
  ;; this reads step-types membership.
  (is (true? (rf.story.play.runner/known-step? [:flush-presence]))))

(deftest flush-presence-arity-mirrors-the-framework-verb
  ;; bare (to quiescence) and a non-negative ms (partial advance)
  (doseq [[step ok?] [[[:flush-presence] true]
                      [[:flush-presence 0] true]
                      [[:flush-presence -1] false]
                      [[:flush-presence "300"] false]
                      [[:flush-presence 100 200] false]]]
    (is (= ok? (rf.story.play.runner/step-arity-ok? step)) (pr-str step))))

(deftest flush-presence-yields-a-tick
  ;; the framework verb is Promise-backed on CLJS, so the driver yields one
  ;; tick for the removal commit to land before the next step reads it
  (is (true? (rf.story.play.runner/async-yield? [:flush-presence]))))

;; ===========================================================================
;; PURE: capabilities + determinism (both hosts)
;; ===========================================================================

(deftest flush-presence-requires-no-capability
  ;; the presence clock is process-global; the ASSERTION that follows carries
  ;; any :dom requirement
  (is (= #{} (rf.story.requirements/step-tokens [:flush-presence])))
  (is (not (contains? (:required-runner (rf.story.plan/variant-plan
                                          {:variant/id :story.presence/headless
                                           :script [[:dispatch [:e]]
                                                    [:flush-presence 100]
                                                    [:flush-presence]]}
                                          {}))
                      :dom))))

(deftest flush-presence-is-deterministic
  ;; the fake-clock advance is not the wall-clock [:wait ms] opt-out
  (is (false? (rf.story.determinism/has-wall-clock-wait?
                {:event-program [[:dispatch [:e]] [:flush-presence 100] [:flush-presence]]}))))

;; ===========================================================================
;; The host hook (both hosts)
;; ===========================================================================

(deftest install-presence-flush-registers-the-hook
  ;; 0 is a legal advance, not the nil quiescence arity: a host tells them
  ;; apart on `some?`, so the seam must hand 0 through as 0
  (let [calls (atom [])]
    (try
      (rf.story.play.presence/install-presence-flush! #(swap! calls conj %))
      (is (= {:status :advanced :ms 0} (rf.story.play.presence/advance! 0)))
      (is (= [0] @calls))
      (finally (swap! rf.story.late-bind/hooks dissoc :flush-presence!)))))

;; ===========================================================================
;; PLAYBACK against a live frame
;; ===========================================================================

(def exec-step!
  "The private single-step executor, reached via var-quote."
  @#'rf.story.play.runner-events/exec-step!)

(def presence-frame :story.presence/frame)

(defn setup!
  "Fresh registrar + runtime + variant frame. Shared with
  `presence-stale-settlement-cljs-test` so both suites test the same
  harness."
  []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  ;; Start every test HOOK-FREE, symmetrically with `teardown!`. The hook
  ;; registry is process-global and any namespace may install into it at load
  ;; time, so the no-host tests must not depend on ns-load order to see an
  ;; empty slot.
  (swap! rf.story.late-bind/hooks dissoc :flush-presence!)
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (reset! rf.story.play.runner-events/run-state {})
  ;; The canonical `:rf.assert/*` handlers must be installed so the
  ;; `[:assert-db …]` steps below record onto the assertion slot.
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/make-frame {:id presence-frame :doc "presence rung test frame"})
  ;; `:presence/tick` stands in for "the toast is on screen and has just been
  ;; dismissed" — the source list dropped the key, so the boundary RETAINS the
  ;; child; `:presence/exited` is the app-visible consequence of its terminal
  ;; removal once the retention timeout fires.
  (rf/reg-event :presence/tick   (fn [{:keys [db]} _] {:db (assoc db :toast :retained)}))
  (rf/reg-event :presence/exited (fn [{:keys [db]} _] {:db (assoc db :toast :removed)}))
  nil)

(defn teardown!
  "Drop the process-global presence host.

  There is no clock to reset alongside it: every host in this ns is a stub
  whose whole state is the atom `install-stub-presence-host!` returns, and
  that dies with the test."
  []
  ;; The late-bind hook registry is process-global — a leaked presence host
  ;; would silently arm every LATER test's `[:flush-presence]` step.
  (swap! rf.story.late-bind/hooks dissoc :flush-presence!)
  nil)

;; The cross-platform FN form (`meta-fixtures-test`): the map form silently
;; skips every deftest on the JVM half of a `.cljc`. Everything in THIS ns is
;; synchronous — a stub host advances in the calling thread — so the fn form
;; is honoured on both hosts and no arm here needs the map form.
(use-fixtures :each (fn [f] (setup!) (try (f) (finally (teardown!)))))

(defn db [] (rf/app-db-value presence-frame))

;; ---------------------------------------------------------------------------
;; The seam, against a STUB presence host (both hosts)
;; ---------------------------------------------------------------------------
;;
;; A stub host stands in for the framework's exit scheduler: it holds ONE
;; pending exit with a `:timeout-ms` bound and fires it (dispatching the
;; app-visible consequence into the variant frame) only once the advanced
;; logical clock reaches that bound. That is the retention shape the real
;; presence clock has — enough to prove the PLAYBACK seam on both hosts,
;; without re-proving the framework's three-phase machine.

(defn- install-stub-presence-host!
  "Install a stub presence host holding one exit due at `timeout-ms`.
  Returns the atom holding its logical state."
  [timeout-ms]
  (let [state (atom {:now 0 :pending? true :advances []})]
    (rf.story.play.presence/install-presence-flush!
      (fn [ms]
        (swap! state update :advances conj ms)
        (let [now (if (nil? ms)
                    ;; nil = advance to quiescence: past every pending exit
                    (inc timeout-ms)
                    (+ (:now @state) ms))]
          (swap! state assoc :now now)
          (when (and (:pending? @state) (>= now timeout-ms))
            (swap! state assoc :pending? false)
            (rf.router/dispatch-sync! [:presence/exited] {:frame presence-frame})))))
    state))

(deftest presence-step-drives-the-host-verb
  (let [state (install-stub-presence-host! 300)]
    (testing "a partial advance below :timeout-ms leaves the exit RETAINED"
      (is (nil? (:passed? (exec-step! presence-frame 0 [:flush-presence 100])))
          "an advance contributes no pass/fail of its own")
      (is (true? (:pending? @state))))
    (testing "an advance to quiescence fires the retained exit"
      (exec-step! presence-frame 1 [:flush-presence])
      (is (= :removed (:toast (db)))))
    (is (= [100 nil] (:advances @state)) "both arities reached the host verb")))

(deftest presence-step-with-no-host-refuses-cannot-run
  ;; An app can render retaining views and omit the install, so an absent
  ;; host refuses rather than reporting a clean verdict over a clock that
  ;; never moved.
  (let [res (exec-step! presence-frame 0 [:flush-presence])]
    (is (true? (:cannot-run? res)))
    (is (false? (:passed? res)))
    (is (re-find #"install-presence-flush!" (:message res)) "the refusal names the install path")))

(deftest presence-step-surfaces-a-throwing-host-as-an-exception
  (rf.story.play.presence/install-presence-flush! (fn [_] (throw (ex-info "boom" {}))))
  (is (some? (:exception (exec-step! presence-frame 0 [:flush-presence])))))

;; ---------------------------------------------------------------------------
;; Through the real playback loop (JVM — synchronous run!)
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- play! [play-key script]
     (let [done (atom nil)]
       (rf.story.play.runner-events/run! presence-frame play-key {:name play-key :script script}
                                         #(reset! done %))
       @done)))

#?(:clj
   (deftest playback-with-the-presence-step-settles-deterministically
     ;; retained below :timeout-ms, then removed, with no wall-clock sleep
     (install-stub-presence-host! 300)
     (is (= :pass (:status (play! "presence"
                                  [[:dispatch [:presence/tick]]
                                   [:flush-presence 100]
                                   [:assert-db [:toast] :retained]
                                   [:flush-presence]
                                   [:assert-db [:toast] :removed]]))))))

#?(:clj
   (deftest playback-with-no-presence-host-refuses-rather-than-passing-falsely
     ;; With no host the toast is retained because nothing moved the clock, and
     ;; the assertion cannot tell that from a sub-timeout advance, so the step
     ;; must refuse.
     (is (= :cannot-run (:status (play! "no-host"
                                        [[:dispatch [:presence/tick]]
                                         [:flush-presence 100]
                                         [:assert-db [:toast] :retained]]))))))
