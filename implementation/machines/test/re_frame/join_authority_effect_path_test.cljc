(ns re-frame.join-authority-effect-path-test
  "rf2-2lzk8a — keep `:spawn-all` join authority inside an AUTHENTICATED,
  NON-REDIRECTABLE effect path.

  #5908 (rf2-ulsbgr) routed canonical join-completion overrides through the
  public effect engine but gated them with the COARSE `re-frame.fx/real-override?`
  — true for ANY non-nil, non-false override value. That conflated a genuinely-
  APPLIED override (a fn-value or a keyword redirect to a registered fx) with an
  override that merely FALLS THROUGH (an unregistered keyword, a malformed value)
  or ESCALATES (a redirect to a private/protected id). The consequences were one
  authority-boundary defect:

    - LEAK / HANG. An unregistered-keyword or malformed `:dispatch` /
      `:dispatch-later` override made `real-override?` true, so `join-dispatch-fx`
      routed the completion through `handle-one-fx`, whose fall-through
      re-dispatched an UNAUTHENTICATED `:dispatch`. The parent's exact-authority
      gate then suppressed it `:attempt-unverified` and the join hung forever.

    - CAPTURE. The private `:rf.machine/join-dispatch` transport was globally
      registered and NOT in the reserved reject set, so a direct
      `{:fx-overrides {:rf.machine/join-dispatch capture-fn}}` ran IN PLACE of the
      transport and captured / suppressed its authenticated payload (the auth
      tuple).

    - RECURSION. Redirecting `{:fx-overrides {:dispatch :rf.machine/join-dispatch}}`
      bypassed the reserved-key check and recursively re-entered override
      resolution to a StackOverflowError, leaving the join unresolved.

  The fix (rf2-2lzk8a):
    - `join-dispatch-fx` gates the canonical override on the STRICTER
      `re-frame.fx/override-applies?` (fn-value OR registered NON-protected
      keyword). Every other disposition — noop, unregistered keyword, malformed
      value, protected-target redirect — falls through to the authenticated
      recordable transport, so the join still folds EXACTLY ONCE and authority
      never leaves the authenticated path. The canonical
      `:rf.error/override-fallthrough` is still surfaced through the ONE override
      engine (`re-frame.fx/resolve-fx-with-overrides`).
    - `:rf.machine/join-dispatch` joins core's `rejected-reserved-fx-ids`, so a
      DIRECT override of it is rejected (`:rf.error/reserved-fx-override`, the
      real transport runs) and a REDIRECT whose target is it is refused (no
      privilege escalation, no self-recursion).

  MUTATION TEETH: reverting the `override-applies?` gate to `real-override?`
  fails the fallthrough folds; removing `:rf.machine/join-dispatch` from the
  reject set fails the capture + recursion guards."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.interop :as interop]
   [re-frame.late-bind :as late-bind]
   [re-frame.machines]
   [re-frame.machines.test-support :as mtest]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  mtest/trace-capture-fixture)

;; ---------------------------------------------------------------------------
;; helpers (mirroring the sibling join transport tests)
;; ---------------------------------------------------------------------------

(defn- join-state [parent-id]
  (get-in (mtest/runtime-db) [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- stale-reasons []
  (mapv (comp :rf.reply/stale-reason :tags)
        (mtest/events-of :rf.machine.spawn-all/stale-completion)))

(defn- mk-child
  "A dispatching child: on `:go` transitions to a plain terminal and dispatches
  its completion back to `parent-id` via `:dispatch` (through its OWN handler
  boundary, so the runtime attaches the recordable `:rf.machine/join-auth`)."
  [parent-id]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch [parent-id [:child/done (:id data)]]]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :dispatch-done}}}
             :done {}}})

(defn- mk-child-delayed
  "Like `mk-child` but completes through a `:dispatch-later` with delay `ms`."
  [parent-id ms]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch-later {:ms    ms
                                                      :event [parent-id [:child/done (:id data)]]}]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :dispatch-done}}}
             :done {}}})

(defn- reg-parent!
  "A two-child `:all` join parent (children `child-a-kw` / `child-b-kw`)."
  [parent-kw child-a-kw child-b-kw]
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        {:children        [{:id :a :machine-id child-a-kw :start [:set-id :a]}
                                           {:id :b :machine-id child-b-kw :start [:set-id :b]}]
                         :join            :all
                         :on-child-done   :child/done
                         :on-child-error  :child/failed
                         :on-all-complete [:all/done]}
                        :on {:abort :idle}}}}))

(defn- with-error-listener
  "Run `body-fn` with an `:errors` always-on listener recording every
  error-record into `sink` (an atom vector); always unregister in a finally."
  [sink body-fn]
  (rf/register-listener! :errors ::err (fn [r] (swap! sink conj r)))
  (try (body-fn)
       (finally (rf/unregister-listener! :errors ::err))))

(defn- error-kinds [sink] (mapv :error @sink))

(defn- with-dispatch-stub
  "Run `body-fn` with `:router/dispatch!` replaced by a RECORDING stub that
  captures each `[event opts]` into `sink` WITHOUT draining — so a deferred
  completion's re-dispatch is observed deterministically on both platforms.
  Restores the real hook in a finally."
  [sink body-fn]
  (let [real (late-bind/get-fn :router/dispatch!)]
    (try
      (late-bind/set-fn! :router/dispatch! (fn [event opts] (swap! sink conj [event opts])))
      (body-fn)
      (finally
        (late-bind/set-fn! :router/dispatch! real)))))

(defn- completion-opts
  "The opts of the FIRST recorded re-dispatch whose event is the completion
  carrier `[parent-id [:child/done child-id]]`, or nil."
  [sink parent-id child-id]
  (some (fn [[event opts]]
          (when (= [parent-id [:child/done child-id]] event) opts))
        @sink))

;; ---------------------------------------------------------------------------
;; (1) LEAK / HANG — an unregistered-keyword override on the canonical id
;;     falls through, then the AUTHENTICATED transport folds the join.
;; ---------------------------------------------------------------------------

(deftest unregistered-keyword-dispatch-override-folds-authenticated
  (testing "rf2-2lzk8a — an UNREGISTERED-keyword `:dispatch` override on a join
            child's completion FALLS THROUGH (canonical override-fallthrough),
            then the authenticated recordable transport runs and the join folds.
            Pre-fix `real-override?` routed this to `handle-one-fx`, whose
            fall-through re-dispatched an UNAUTHENTICATED `:dispatch` the parent
            suppressed `:attempt-unverified` — hanging the join (`:done` #{})."
    (rf/reg-machine :jae.uk/ca (mk-child :jae.uk/rp))
    (rf/reg-machine :jae.uk/cb (mk-child :jae.uk/rp))
    (reg-parent! :jae.uk/rp :jae.uk/ca :jae.uk/cb)
    (rf/dispatch-sync [:jae.uk/rp [:start]])
    (let [errs (atom [])
          a    (get-in (join-state :jae.uk/rp) [:children :a])]
      (with-error-listener errs
        (fn [] (rf/dispatch-sync [a [:go]]
                                 {:fx-overrides {:dispatch :jae.uk/nonexistent}})))
      (is (= #{:a} (:done (join-state :jae.uk/rp)))
          "the completion folded through the AUTHENTICATED transport despite the fallthrough override")
      (is (some #{:rf.error/override-fallthrough} (error-kinds errs))
          "the canonical override-fallthrough diagnostic was surfaced")
      (is (empty? (stale-reasons))
          "no :attempt-unverified — authority never left the authenticated path"))))

;; ---------------------------------------------------------------------------
;; (2) LEAK / HANG — a MALFORMED override value falls through, same authenticated
;;     fold.
;; ---------------------------------------------------------------------------

(deftest malformed-dispatch-override-folds-authenticated
  (testing "rf2-2lzk8a — a MALFORMED `:dispatch` override value (a number, not an
            fx-id keyword / fn / nil-false) FALLS THROUGH (canonical override-
            fallthrough) and the authenticated transport still folds the join."
    (rf/reg-machine :jae.mf/ca (mk-child :jae.mf/rp))
    (rf/reg-machine :jae.mf/cb (mk-child :jae.mf/rp))
    (reg-parent! :jae.mf/rp :jae.mf/ca :jae.mf/cb)
    (rf/dispatch-sync [:jae.mf/rp [:start]])
    (let [errs (atom [])
          a    (get-in (join-state :jae.mf/rp) [:children :a])]
      (with-error-listener errs
        (fn [] (rf/dispatch-sync [a [:go]] {:fx-overrides {:dispatch 42}})))
      (is (= #{:a} (:done (join-state :jae.mf/rp)))
          "the malformed-override completion folded through the authenticated transport")
      (is (some #{:rf.error/override-fallthrough} (error-kinds errs))
          "the canonical override-fallthrough diagnostic was surfaced")
      (is (empty? (stale-reasons))
          "no stale suppression — the join folded exactly once"))))

;; ---------------------------------------------------------------------------
;; (3) APPLIES PATH INTACT — a genuine fn-value override still pre-empts.
;; ---------------------------------------------------------------------------

(deftest genuine-fn-value-override-still-preempts
  (testing "rf2-2lzk8a — the stricter `override-applies?` gate still lets a
            GENUINE fn-value `:dispatch` override pre-empt the completion
            (captures the UNCHANGED public event, folds nothing, mints no
            authority). The applies path and the new fallthrough path coexist."
    (rf/reg-machine :jae.fn/ca (mk-child :jae.fn/rp))
    (rf/reg-machine :jae.fn/cb (mk-child :jae.fn/rp))
    (reg-parent! :jae.fn/rp :jae.fn/ca :jae.fn/cb)
    (rf/dispatch-sync [:jae.fn/rp [:start]])
    (let [captured (atom [])
          a        (get-in (join-state :jae.fn/rp) [:children :a])]
      (rf/dispatch-sync [a [:go]]
                        {:fx-overrides {:dispatch (fn [_ args] (swap! captured conj args))}})
      (is (= [[:jae.fn/rp [:child/done :a]]] @captured)
          "the fn-value override captured the completion exactly once")
      (is (= #{} (:done (join-state :jae.fn/rp)))
          "the applied override pre-empted the transport — nothing folded")
      (is (not (true? (:resolved? (join-state :jae.fn/rp))))
          "the join did not resolve on the captured (un-folded) completion"))))

;; ---------------------------------------------------------------------------
;; (4) CAPTURE — a direct override of the PRIVATE transport is rejected; the
;;     capture-fn never sees the authenticated payload.
;; ---------------------------------------------------------------------------

(deftest direct-override-of-private-transport-cannot-capture
  (testing "rf2-2lzk8a — a DIRECT `{:fx-overrides {:rf.machine/join-dispatch
            capture-fn}}` cannot capture / suppress / forge / replay the
            authenticated payload: `:rf.machine/join-dispatch` is a reject-tier
            reserved fx, so the override is IGNORED
            (`:rf.error/reserved-fx-override`), the real transport runs, the
            capture-fn NEVER sees the join-auth tuple, and the join folds."
    (rf/reg-machine :jae.pc/ca (mk-child :jae.pc/rp))
    (rf/reg-machine :jae.pc/cb (mk-child :jae.pc/rp))
    (reg-parent! :jae.pc/rp :jae.pc/ca :jae.pc/cb)
    (rf/dispatch-sync [:jae.pc/rp [:start]])
    (let [captured (atom [])
          errs     (atom [])
          a        (get-in (join-state :jae.pc/rp) [:children :a])]
      (with-error-listener errs
        (fn [] (rf/dispatch-sync
                 [a [:go]]
                 {:fx-overrides {:rf.machine/join-dispatch
                                 (fn [_ args] (swap! captured conj args))}})))
      (is (empty? @captured)
          "the capture-fn never ran — the private transport is NON-overridable")
      (is (= #{:a} (:done (join-state :jae.pc/rp)))
          "the real authenticated transport folded the join")
      (is (some #{:rf.error/reserved-fx-override} (error-kinds errs))
          "the direct override of the private transport was rejected loudly"))))

;; ---------------------------------------------------------------------------
;; (5) RECURSION — redirecting the canonical id to the private transport fails
;;     safely, with NO StackOverflowError, and the join still resolves.
;; ---------------------------------------------------------------------------

(deftest redirect-canonical-to-private-transport-fails-safely-no-recursion
  (testing "rf2-2lzk8a — `{:fx-overrides {:dispatch :rf.machine/join-dispatch}}`
            FAILS SAFELY: the protected redirect target is refused (canonical
            override-fallthrough), each completion authenticates + folds, driving
            the join to resolution does NOT recurse into a StackOverflowError."
    (rf/reg-machine :jae.rd/ca (mk-child :jae.rd/rp))
    (rf/reg-machine :jae.rd/cb (mk-child :jae.rd/rp))
    (reg-parent! :jae.rd/rp :jae.rd/ca :jae.rd/cb)
    (rf/dispatch-sync [:jae.rd/rp [:start]])
    (let [errs   (atom [])
          thrown (atom nil)
          js     (join-state :jae.rd/rp)
          a      (get-in js [:children :a])
          b      (get-in js [:children :b])]
      (with-error-listener errs
        (fn []
          (try
            (rf/dispatch-sync [a [:go]] {:fx-overrides {:dispatch :rf.machine/join-dispatch}})
            (rf/dispatch-sync [b [:go]] {:fx-overrides {:dispatch :rf.machine/join-dispatch}})
            #?(:clj  (catch Throwable e (reset! thrown e))
               :cljs (catch :default e (reset! thrown e))))))
      (is (nil? @thrown)
          "no StackOverflowError — the self-recursive redirect was refused, not re-entered")
      (is (= #{:a :b} (:done (join-state :jae.rd/rp)))
          "both completions authenticated + folded despite the protected-target redirect")
      (is (true? (:resolved? (join-state :jae.rd/rp)))
          "the :all join resolved")
      (is (some #{:rf.error/override-fallthrough} (error-kinds errs))
          "the protected-target redirect surfaced the canonical override-fallthrough"))))

;; ---------------------------------------------------------------------------
;; (6) HAND-AUTHORED private transport with FORGED authority is fail-closed.
;; ---------------------------------------------------------------------------

(deftest hand-authored-private-transport-with-forged-auth-is-fail-closed
  (testing "rf2-2lzk8a — a hand-authored `[:rf.machine/join-dispatch {:event …
            :rf/join-auth <forged>}]` emitted from an ordinary app event handler
            cannot forge / replay join authority: the forged auth is validated
            against live join state downstream and fail-closed suppressed
            (`:attempt-superseded`), folding nothing."
    (rf/reg-machine :jae.ha/ca (mk-child :jae.ha/rp))
    (rf/reg-machine :jae.ha/cb (mk-child :jae.ha/rp))
    (reg-parent! :jae.ha/rp :jae.ha/ca :jae.ha/cb)
    (rf/dispatch-sync [:jae.ha/rp [:start]])
    (rf/reg-event :jae.ha/forge
      (fn [_ [_ ev auth]]
        {:fx [[:rf.machine/join-dispatch {:event ev :rf/join-auth auth}]]}))
    (let [j      (join-state :jae.ha/rp)
          a      (get-in j [:children :a])
          forged {:parent-id :jae.ha/rp :invoke-id [:racing]
                  :child-id  :a :spawned-id a :attempt -999}]
      (mtest/reset-captured!)
      (rf/dispatch-sync [:jae.ha/forge [:jae.ha/rp [:child/done :a]] forged])
      (is (= #{} (:done (join-state :jae.ha/rp)))
          "the forged hand-authored transport folded nothing (fail-closed)")
      (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons))
          "the forged authority was suppressed as stale, never accepted"))))

;; ---------------------------------------------------------------------------
;; (7) DELAYED (0 ms) fallthrough authenticates and folds — cross-host boundary.
;; ---------------------------------------------------------------------------

(deftest zero-delay-dispatch-later-fallthrough-authenticates-and-folds
  (testing "rf2-2lzk8a — a zero-delay `:dispatch-later` completion whose
            `:dispatch-later` override FALLS THROUGH (unregistered keyword) is
            DEFERRED on the host clock, then the re-dispatch carries the
            recordable `:rf.machine/join-auth` (the AUTHENTICATED transport, not
            the unauthenticated override fall-through), and folds the join
            exactly once. Covers the 0 ms boundary on CLJ and CLJS."
    (rf/reg-machine :jae.dl/ca (mk-child-delayed :jae.dl/rp 0))
    (rf/reg-machine :jae.dl/cb (mk-child-delayed :jae.dl/rp 0))
    (reg-parent! :jae.dl/rp :jae.dl/ca :jae.dl/cb)
    (rf/dispatch-sync [:jae.dl/rp [:start]])
    (let [a           (get-in (join-state :jae.dl/rp) [:children :a])
          captured-cb (atom nil)
          sink        (atom [])
          errs        (atom [])]
      (with-error-listener errs
        (fn []
          (with-dispatch-stub sink
            (fn []
              (with-redefs [interop/set-timeout!   (fn [f _ms] (reset! captured-cb f) ::handle)
                            interop/clear-timeout! (fn [_] nil)]
                (rf/dispatch-sync [a [:go]] {:fx-overrides {:dispatch-later :jae.dl/nonexistent}})
                (is (= #{} (:done (join-state :jae.dl/rp)))
                    "the zero-delay completion deferred (did not fold synchronously)")
                (is (some? @captured-cb) "the completion was armed on the host clock (deferred)")
                (is (empty? @sink) "nothing re-dispatched yet — pending on the host clock")
                ;; Fire the controlled host-clock callback → the deferred re-dispatch.
                (@captured-cb))))))
      (let [opts (completion-opts sink :jae.dl/rp :a)]
        (is (some? opts) "the host-clock callback re-dispatched the completion carrier")
        (is (some? (get-in opts [:rf.cofx :rf.machine/join-auth]))
            "the deferred fall-through completion carries the recordable join authority (AUTHENTICATED)")
        (is (some #{:rf.error/override-fallthrough} (error-kinds errs))
            "the canonical override-fallthrough diagnostic was surfaced")
        ;; Deliver the deferred completion (its recorded event + causal cofx).
        (rf/dispatch-sync [:jae.dl/rp [:child/done :a]] {:rf.cofx (:rf.cofx opts)})
        (is (= #{:a} (:done (join-state :jae.dl/rp)))
            "delivering the deferred authenticated completion folds :a exactly once")
        (is (empty? (stale-reasons)) "no stale suppression — authenticated end to end")))))
