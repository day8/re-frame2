(ns re-frame.trace-listener-post-drain-mutation-test
  "Trace listeners are OBSERVERS, not participants (rf2-wxy1c).

  THE CONTRACT THIS PINS. A trace listener's body never runs while the framework
  owns a frame's drain lock. Internal drain-owned emits are delivered at the
  POST-DRAIN boundary — serialized process-wide, in emission order, completing
  before the enclosing dispatch / drain call returns. The consequence for
  application code is the thing worth pinning directly, because it is the part a
  programmer can observe:

    A registrar mutation performed from inside a trace listener takes effect
    when the listener's callback RUNS — post-drain. It is therefore exactly
    equivalent to performing the same mutation on the line AFTER `dispatch-sync`
    returns. Intra-drain influence by listener side effects is OUT OF CONTRACT.

  ARM 1 ≡ ARM 2 is the whole test. Arm 1 mutates the registrar from a listener
  on an emit the drain owns; arm 2 performs the identical mutation immediately
  after the same `dispatch-sync` returns. Every observable — the installed
  `:rf/machine-type` reference FORM, the actor's reachable state, and the
  `:rf.error/no-such-handler` count — must agree. Arm 3 is the undisturbed
  control, so a bug that flattened all three arms into one value could not pass
  silently.

  `:spawn-all` child installation is the probe because it is the most
  timing-sensitive consumer of the registrar in the codebase: `install-spawn!`
  forces `prepared-type-ref` at the last point before the runtime-db write, and
  the definition-lifetime rule (rf2-rxjy3 / rf2-zo5n9) makes the FORM of the
  stamped reference a pure function of whether the registrar had diverged BY
  THAT INSTANT. If a listener could still act inside the drain, arm 1 would pin
  a definition map and arm 2 would keep a keyword — which is precisely what this
  test would catch.

  WHY THIS IS NOT A PLATFORM-SPLIT TEST. The assertion is on OBSERVABLE OUTCOMES
  (reference form, reachable state, error counts), never on exceptions and never
  on delivery mechanics. CLJS delivers inline — that is an implementation detail
  of the `trace.cljc` seam (load-bearing for bundle isolation), not a promise —
  and the outcome contract is identical on both platforms. This suite is JVM
  because the deferral seam it characterises is JVM-only by construction; what
  it asserts is the cross-platform contract.

  RED-BEFORE LEVER. Make `re-frame.trace/call-with-deferred-listener-delivery`
  the identity on JVM (`#?(:clj (f) :cljs (f))`), restoring the pre-rf2-wxy1c
  inline fan-out: arm 1 then pins a definition map and reaches `:working` while
  arm 2 keeps a keyword and strands the child, and the arm-1 ≡ arm-2 assertions
  fail."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private booting-child
  "A child whose synthetic `[:rf.machine.spawn/spawned]` bootstrap causes an
  OBSERVABLE `:idle` → `:ready` transition, and which then accepts an ordinary
  `:go` event. An installed-but-unresolvable child stays at `:idle` instead —
  so the reachable state distinguishes a LIVE actor from an inert snapshot."
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:rf.machine.spawn/spawned :ready}}
             :ready   {:on {:go :working}}
             :working {}}})

(defn- parent-over [children]
  {:initial :idle
   :states
   {:idle    {:on {:start :forking}}
    :forking {:spawn-all {:children         children
                          :join             :all
                          :on-all-complete  [:all/done]}
              :on {:all/done :ready}}
    :ready   {}}})

(defn- snap-of [actor-id]
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :snapshots actor-id]))

(defn- observe
  "The arm's observable end state — deliberately NOT a delivery-mechanics
  reading. `:pinned?` is the FORM of the installed reference (a pinned
  definition map vs the revertible TYPE keyword), `:state` is the state the
  actor actually reaches after an ordinary later event, and
  `:no-such-handler` counts the resolver failures an inert actor would raise.

  Note the CLJS-safety of this shape (rf2-wxy1c): a stale reference does not
  THROW on either platform — it strands the actor — so every reading here is an
  outcome, never an exception.

  The resolver-failure count is filtered to THIS child: the trace capture is
  cumulative across the arms in one deftest, and an unfiltered count would make
  arm 2 differ from arm 1 for a reason that has nothing to do with the contract."
  [child-id]
  (rf/dispatch-sync [child-id [:go]])
  {:pinned?         (map? (:rf/machine-type (snap-of child-id)))
   :installed?      (some? (snap-of child-id))
   :state           (rf.machines.test-support/machine-state child-id)
   :no-such-handler (count (filterv #(= child-id (get-in % [:tags :rf.trace/event-id]))
                                    (rf.machines.test-support/events-of :rf.error/no-such-handler)))})

(defn- run-arm
  "Register `type-kw` + a `:spawn-all` parent over it, dispatch the fork, and
  return `(observe child)`. `during` runs from a trace listener on the
  drain-owned `:rf.machine.spawn-all/started` emit; `after` runs on the line
  following `dispatch-sync`. Exactly one of them is supplied per arm."
  [type-kw parent-kw {:keys [during after]}]
  (rf/reg-machine type-kw booting-child)
  (rf/reg-machine parent-kw (parent-over [{:id :c :machine-id type-kw}]))
  (let [listener-id ::arm
        fired       (atom false)]
    (when during
      (rf.trace/register-listener!
        listener-id
        (fn [ev]
          (when (and (= :rf.machine.spawn-all/started (:operation ev))
                     (not @fired))
            (reset! fired true)
            (during)))))
    (try
      (rf/dispatch-sync [parent-kw [:start]])
      (when after (after))
      (finally
        (when during (rf.trace/unregister-listener! listener-id))))
    (when during
      (is (true? @fired)
          "(precondition) the listener ran — deferred delivery still DELIVERS"))
    (observe (get-in (rf.machines.test-support/runtime-db)
                     [:rf.runtime/machines :spawned parent-kw [:forking] :children :c]))))

;; ===========================================================================
;; UNREGISTER — a listener that unregisters the child TYPE mid-drain lands
;; post-drain, so it is indistinguishable from unregistering after the call.
;; ===========================================================================

(deftest mid-drain-listener-unregister-is-equivalent-to-unregistering-after-the-drain
  (testing "a :rf.machine.spawn-all/started listener that UNREGISTERS the child
            TYPE produces EXACTLY the observable end state that the same
            unregister performed one line after dispatch-sync produces — the
            listener body runs at the post-drain boundary, so it cannot
            influence the install it appears to precede (rf2-wxy1c)."
    (let [arm-1 (run-arm :pd/a1 :pd/sup-a1
                         {:during #(rf.registrar/unregister! :event :pd/a1)})
          arm-2 (run-arm :pd/a2 :pd/sup-a2
                         {:after  #(rf.registrar/unregister! :event :pd/a2)})
          arm-3 (run-arm :pd/a3 :pd/sup-a3 {})]
      (is (= arm-1 arm-2)
          (str "ARM 1 ≡ ARM 2 — mid-drain listener mutation lands post-drain. "
               "arm-1=" arm-1 " arm-2=" arm-2))
      ;; The controls below keep the equality honest: they pin what the shared
      ;; value actually IS, so a regression that made every arm agree on some
      ;; degenerate reading could not pass.
      (is (false? (:pinned? arm-1))
          "the install saw an INTACT registrar and kept the revertible keyword — the unregister had not happened yet")
      (is (true? (:installed? arm-1))
          "the admitted child installed regardless (rf2-v4oqd)")
      (is (= :ready (:state arm-1))
          "the child bootstrapped, then the post-drain unregister stranded its later :go — the accepted cost of the revertible keyword")
      (is (pos? (:no-such-handler arm-1))
          "the stranded later event raised :rf.error/no-such-handler — an OUTCOME, not an exception")
      (is (= {:pinned? false :installed? true :state :working :no-such-handler 0}
             arm-3)
          "the undisturbed control is unaffected: keyword reference, live actor, no resolver failure"))))

;; ===========================================================================
;; RE-REGISTER — the other leg. A listener that swaps the TYPE to a successor
;; definition is likewise a post-drain mutation.
;; ===========================================================================

(deftest mid-drain-listener-reregister-is-equivalent-to-reregistering-after-the-drain
  (testing "a listener that RE-REGISTERS the child TYPE to a successor definition
            is equally post-drain: the live child tracks the successor exactly as
            it would had the re-registration been written after dispatch-sync —
            ordinary hot-reload semantics, reached at the ordinary time."
    (let [v2    (assoc-in booting-child [:states :ready :on :go] :hot-reloaded)
          v2'   (assoc-in v2 [:states :hot-reloaded] {})
          arm-1 (run-arm :pd/b1 :pd/sup-b1
                         {:during #(rf/reg-machine :pd/b1 v2')})
          arm-2 (run-arm :pd/b2 :pd/sup-b2
                         {:after  #(rf/reg-machine :pd/b2 v2')})]
      (is (= arm-1 arm-2)
          (str "ARM 1 ≡ ARM 2 — the re-registration lands post-drain either way. "
               "arm-1=" arm-1 " arm-2=" arm-2))
      (is (false? (:pinned? arm-1))
          "the install kept the revertible keyword — the registrar was intact at commit")
      (is (= :hot-reloaded (:state arm-1))
          "the live child followed the re-registered definition — hot-reload semantics preserved")
      (is (zero? (:no-such-handler arm-1))
          "no resolver failure — the successor definition resolves"))))
