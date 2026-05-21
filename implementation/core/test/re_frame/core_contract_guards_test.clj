(ns re-frame.core-contract-guards-test
  "Per rf2-0kfd4 (testcov-core G3 + G4 + G5 + G6) — guard contracts on
  load-bearing internal seams that previously had no behavioural test.

    G3 — `registrar/register!` unknown-kind throw + `valid-kind?`
         (registrar.cljc). Registering a kind outside the closed v1 set
         throws `:rf.error/unknown-registry-kind` with the documented
         ex-data shape. Spec 001 §Registry model names the closed kind
         set as a contract; this pins the guard that enforces it.

    G4 — registrar registration/replacement hook isolation
         (registrar.cljc). `add-registration-hook!` fires on EVERY
         register! (first-time AND re-registration); `add-replacement-
         hook!` fires only on re-registration. Both run isolated — a
         throwing hook is swallowed (try/catch) so a buggy listener
         can't block the registration.

    G5 — `frame/frame-disposed-for-drain?` (frame.cljc) three branches:
         destroyed-but-present → true, absent → true, live → false.

    G6 — `subs/reg-sub` malformed-form throw `:rf.error/reg-sub-bad-args`
         (subs.cljc). reg-event / reg-view bad-args are pinned elsewhere;
         the reg-sub rejection branch was not.

  Pure JVM unit. A fixture snapshots/restores the (private) registration
  and replacement hook atoms — there is no public clear for them, so a
  test-installed hook must be torn down here to keep sibling tests clean.
  The registrar/frame slots the tests touch are cleared on entry."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.subs :as subs]))

;; ---- fixtures -------------------------------------------------------------
;;
;; `registration-hooks` / `replacement-hooks` are private `defonce`
;; atoms with a public *add* but no public *clear*. Snapshot+restore so a
;; throwing test hook never leaks into a sibling test (which would, e.g.,
;; break every subsequent register! call's hook fan-out). Also clear the
;; registrar + frame slots the tests below write.

(defn isolate-registrar-state [test-fn]
  (let [reg-hooks-before  @(deref #'registrar/registration-hooks)
        repl-hooks-before @(deref #'registrar/replacement-hooks)
        frames-before     @frame/frames]
    (registrar/clear-kind! :sub)
    (registrar/clear-kind! :event)
    (try
      (test-fn)
      (finally
        (reset! (deref #'registrar/registration-hooks) reg-hooks-before)
        (reset! (deref #'registrar/replacement-hooks)  repl-hooks-before)
        (reset! frame/frames frames-before)
        (registrar/clear-kind! :sub)
        (registrar/clear-kind! :event)))))

(use-fixtures :each isolate-registrar-state)

;; =============================================================================
;; G3 — unknown-kind throw + valid-kind?
;; =============================================================================

(deftest valid-kind?-recognises-the-closed-v1-set
  (testing "valid-kind? is true for each member of the closed v1 kind set"
    (doseq [k registrar/kinds]
      (is (registrar/valid-kind? k) (str k " is a valid v1 registry kind"))))
  (testing "valid-kind? is false for kinds outside the closed set"
    (is (not (registrar/valid-kind? :not-a-kind)))
    (is (not (registrar/valid-kind? :machine))
        "machine guards/actions are machine-scoped, not a registry kind (Spec 005)")
    (is (not (registrar/valid-kind? nil)))))

(deftest register!-throws-on-an-unknown-kind
  (testing "register! rejects a kind outside the closed v1 set with the documented error shape"
    (let [ex (try
               (registrar/register! :bogus-kind :some/id {:handler-fn identity})
               (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex)
          "an unknown kind throws an ex-info")
      (is (= ":rf.error/unknown-registry-kind" (.getMessage ^Exception ex))
          "message is the stringified canonical error keyword")
      (let [data (ex-data ex)]
        (is (= :rf.error/unknown-registry-kind (:rf.error/id data))
            ":rf.error/id is the canonical discriminator")
        (is (= 'rf/register-handler (:where data)) ":where names the user-facing seam")
        (is (= :fix-registration (:recovery data)) ":recovery is :fix-registration")
        (is (= :bogus-kind (:kind data)) "ex-data echoes the offending kind")
        (is (= :some/id (:id data)) "ex-data echoes the id")
        (is (string? (:reason data)) ":reason is a human-readable string")))))

(deftest register!-throws-before-writing-the-slot
  (testing "an unknown-kind register! does not mutate the registry"
    (let [before @(deref #'registrar/kind->id->metadata)]
      (try (registrar/register! :bogus-kind :x {:handler-fn identity})
           (catch clojure.lang.ExceptionInfo _ nil))
      (is (= before @(deref #'registrar/kind->id->metadata))
          "the registry map is untouched — the throw precedes the swap!"))))

;; =============================================================================
;; G4 — registration / replacement hook isolation + firing contract
;; =============================================================================

(deftest registration-hook-fires-on-first-time-and-re-registration
  (testing "add-registration-hook! fires on BOTH first-time and re-registration"
    (let [calls (atom [])]
      (registrar/add-registration-hook!
        (fn [m] (swap! calls conj (select-keys m [:kind :id :was]))))
      (registrar/register! :sub :s/one {:handler-fn (fn [] :v1)})
      (registrar/register! :sub :s/one {:handler-fn (fn [] :v2)})
      (is (= 2 (count @calls)) "the hook fired on both registrations")
      (is (nil? (:was (first @calls)))
          ":was is nil on first-time registration")
      (is (some? (:was (second @calls)))
          ":was carries the previous metadata on re-registration"))))

(deftest replacement-hook-fires-only-on-re-registration
  (testing "add-replacement-hook! fires on re-registration, not first-time"
    (let [calls (atom [])]
      (registrar/add-replacement-hook!
        (fn [m] (swap! calls conj (select-keys m [:kind :id :different-fn?]))))
      (registrar/register! :sub :s/two {:handler-fn (fn [] :a)})
      (is (= 0 (count @calls)) "no replacement hook on first-time registration")
      (registrar/register! :sub :s/two {:handler-fn (fn [] :b)})
      (is (= 1 (count @calls)) "replacement hook fired on re-registration")
      (is (true? (:different-fn? (first @calls)))
          ":different-fn? is true when the handler-fn identity changed"))))

(deftest throwing-registration-hook-is-swallowed
  (testing "a throwing registration hook does not break register!"
    (let [reached (atom false)]
      (registrar/add-registration-hook! (fn [_] (throw (ex-info "bad hook" {}))))
      ;; A second hook installed AFTER the throwing one — if the throw
      ;; were not swallowed per-hook, the doseq would abort and this hook
      ;; would never fire.
      (registrar/add-registration-hook! (fn [_] (reset! reached true)))
      (is (= {:was nil :now {:handler-fn :the-fn}}
             (registrar/register! :sub :s/iso {:handler-fn :the-fn}))
          "register! returns its normal {:was :now} result despite the throwing hook")
      (is (true? @reached)
          "the second hook still fired — per-hook throws are isolated")
      (is (= {:handler-fn :the-fn} (registrar/lookup :sub :s/iso))
          "the registration slot was written despite the throwing hook"))))

(deftest throwing-replacement-hook-is-swallowed
  (testing "a throwing replacement hook does not break a re-registration"
    (registrar/add-replacement-hook! (fn [_] (throw (ex-info "bad repl hook" {}))))
    (registrar/register! :sub :s/repl {:handler-fn (fn [] :first)})
    (let [result (registrar/register! :sub :s/repl {:handler-fn (fn [] :second)})]
      (is (some? (:was result)) "re-registration completed despite the throwing hook")
      (is (some? (registrar/lookup :sub :s/repl))
          "the new slot is in place"))))

;; =============================================================================
;; G5 — frame/frame-disposed-for-drain? three branches
;; =============================================================================

(deftest frame-disposed-for-drain?-distinguishes-the-three-branches
  (testing "live frame → false; destroyed-but-present → true; absent → true"
    (reset! frame/frames
            {:f/live      {:lifecycle {:destroyed? false}}
             :f/destroyed {:lifecycle {:destroyed? true}}})
    (is (false? (frame/frame-disposed-for-drain? :f/live))
        "a registered, non-destroyed frame is NOT disposed for drain")
    (is (true? (frame/frame-disposed-for-drain? :f/destroyed))
        "a present-but-:destroyed? frame is disposed (post step-3, pre step-6)")
    (is (true? (frame/frame-disposed-for-drain? :f/never-registered))
        "an absent id returns true — benign for the drain caller (Spec 002)")))

;; =============================================================================
;; G6 — subs/reg-sub malformed-form throw
;; =============================================================================

(deftest reg-sub-rejects-trailing-args-after-the-handler
  (testing "a layer-1 form with an extra trailing arg throws :rf.error/reg-sub-bad-args"
    (let [ex (try
               (subs/reg-sub :sub/malformed (fn [db _] db) :unexpected-extra)
               (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex)
          "a malformed reg-sub tail throws an ex-info")
      (is (= ":rf.error/reg-sub-bad-args" (.getMessage ^Exception ex)))
      (let [data (ex-data ex)]
        (is (= :rf.error/reg-sub-bad-args (:rf.error/id data)))
        (is (= 'rf/reg-sub (:where data)))
        (is (= :fix-registration (:recovery data)))
        (is (= :sub/malformed (:id data)) "ex-data echoes the sub id"))
      (is (nil? (registrar/lookup :sub :sub/malformed))
          "the malformed sub was never registered"))))

(deftest reg-sub-rejects-a-dangling-arrow-without-a-vector
  (testing "a `:<-` not followed by a vector falls through to the bad-args throw"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf.error/reg-sub-bad-args"
          (subs/reg-sub :sub/dangling-arrow :<- :not-a-vector (fn [v _] v)))
        ":<- with a non-vector neighbour is not a valid layer-2 chain")))

(deftest reg-sub-accepts-the-three-valid-shapes
  (testing "the valid layer-1 / layer-2-single / layer-2-multi shapes parse cleanly"
    ;; Layer-1.
    (is (= :sub/l1 (subs/reg-sub :sub/l1 (fn [db _] db))))
    ;; Layer-2 single.
    (is (= :sub/l2-single
           (subs/reg-sub :sub/l2-single :<- [:sub/l1] (fn [up _] up))))
    ;; Layer-2 multi.
    (is (= :sub/l2-multi
           (subs/reg-sub :sub/l2-multi
                         :<- [:sub/l1] :<- [:sub/l2-single]
                         (fn [[a b] _] [a b]))))
    (let [meta (registrar/lookup :sub :sub/l2-multi)]
      (is (= [[:sub/l1] [:sub/l2-single]] (:input-signals meta))
          "the :<- chain is parsed into :input-signals in order"))))
