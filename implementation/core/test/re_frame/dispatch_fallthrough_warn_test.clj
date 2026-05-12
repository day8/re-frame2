(ns re-frame.dispatch-fallthrough-warn-test
  "Per rf2-o8m0 — emit `:rf.warning/dispatch-from-async-callback-fell-through-
  to-default` when:

  1. A dispatch's frame resolution falls through to `:rf/default` purely
     because nothing else was found (`*current-frame*` unbound, no adapter
     React-context value, no explicit `:frame` opt), AND
  2. The target handler is NOT registered on `:rf/default`, AND
  3. At least one non-default frame is registered (single-frame apps
     cannot hit the footgun).

  The canonical trigger is a `dispatch` issued from a `setTimeout` /
  `addEventListener` / `requestAnimationFrame` callback attached inside
  a view body: the surrounding `with-frame` / React-context-Provider
  binding does NOT survive the async escape (per Spec 002 §Dispatches
  issued from inside a handler body). At the JVM-test layer, we
  reproduce the same observable shape by dispatching from a fresh stack
  with no `*current-frame*` binding (the JS callback is identical:
  fresh stack, no dynamic binding)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.router :as router]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-trace-cbs!)
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces!
  "Attach a recording listener and return its atom."
  [listener-id]
  (let [a (atom [])]
    (rf/register-trace-cb! listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- fallthrough-warnings
  [recorded]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= :rf.warning/dispatch-from-async-callback-fell-through-to-default
                     (:operation ev))))
           @recorded))

(defn- no-such-handler-errors
  [recorded]
  (filterv (fn [ev]
             (and (= :error (:op-type ev))
                  (= :rf.error/no-such-handler (:operation ev))))
           @recorded))

;; A simulated async-callback: a fn we invoke on a fresh stack with NO
;; surrounding `with-frame` binding — the same observable shape a
;; `setTimeout` callback presents to the runtime.
(defn- run-as-async-callback
  "Invoke `f` with `*current-frame*` explicitly unbound. Mirrors the
  observable shape a JS setTimeout / addEventListener / RAF callback
  presents — fresh stack, no dynamic binding."
  [f]
  (binding [frame/*current-frame* nil]
    (f)))

;; ---- tests ----------------------------------------------------------------

(deftest fires-on-async-callback-fallthrough
  (testing "dispatch from a fresh stack (no *current-frame* binding) for a handler the user intended for a non-default frame emits the warning"
    ;; A non-default frame exists — single-frame apps cannot hit this
    ;; footgun, so a sibling frame is the precondition.
    (rf/reg-frame :game {:doc "non-default frame the user intended to target"})
    ;; The :game/tick handler is intentionally NOT registered. In the
    ;; canonical 2048 trigger, the user's `addEventListener "animationend"`
    ;; callback dispatches `[:tile/finished-merging ...]` expecting it
    ;; to run against the :game frame — but the async stack lost the
    ;; surrounding `*current-frame*` binding (or React-context value),
    ;; the dispatch fell through to :rf/default, and :rf/default has no
    ;; such handler. That observable shape is what we reproduce here.

    (let [recorded (record-traces! ::async-fall)]
      (run-as-async-callback
        (fn [] (rf/dispatch-sync [:game/tick])))

      (let [warns (fallthrough-warnings recorded)]
        (is (= 1 (count warns))
            (str "expected exactly one fallthrough warning, got "
                 (count warns)))
        (let [w (first warns)
              t (:tags w)]
          (is (= [:game/tick] (:event t)))
          (is (= :game/tick (:event-id t)))
          (is (= :rf/default (:routed-to t)))
          (is (number? (:detected-at t)))
          (is (string? (:reason t)))
          (is (re-find #"setTimeout|addEventListener|async" (:reason t))
              "reason should reference the async-callback trigger")
          (is (= :no-recovery (:recovery w)))))

      (testing "the existing :rf.error/no-such-handler error STILL fires alongside"
        (is (= 1 (count (no-such-handler-errors recorded)))
            "existing error trace contract is preserved")))))

(deftest suppressed-when-no-non-default-frame-registered
  (testing "single-frame apps (only :rf/default) do NOT see the warning — they cannot hit the footgun"
    ;; No reg-frame: only :rf/default exists.
    (let [recorded (record-traces! ::single-frame)]
      (run-as-async-callback
        (fn [] (rf/dispatch-sync [:never-registered])))

      (is (empty? (fallthrough-warnings recorded))
          "warning is noise in single-frame apps; suppressed")
      (is (= 1 (count (no-such-handler-errors recorded)))
          ":rf.error/no-such-handler still fires (existing contract)"))))

(deftest suppressed-when-handler-exists-on-default
  (testing "dispatch falls through to :rf/default but the handler IS registered there — no warning"
    (rf/reg-frame :game {:doc "non-default sibling"})
    (rf/reg-event-db :app/normal (fn [db _] db))

    (let [recorded (record-traces! ::handler-on-default)]
      (run-as-async-callback
        (fn [] (rf/dispatch-sync [:app/normal])))

      (is (empty? (fallthrough-warnings recorded))
          "the dispatch resolved to a real handler; no async-loss diagnostic warranted")
      (is (empty? (no-such-handler-errors recorded))
          "the handler ran successfully"))))

(deftest suppressed-when-explicit-frame-opt-supplied
  (testing "an explicit :frame opt means the user was deliberate — no fallthrough, no warning"
    (rf/reg-frame :game {:doc "explicit frame target"})
    ;; Note: handler is NOT registered anywhere — we want to test that
    ;; even though it's missing, the explicit opt suppresses the
    ;; fallthrough warning (the diagnostic is for *silent* loss, not
    ;; explicit targeting).

    (let [recorded (record-traces! ::explicit-frame)]
      (run-as-async-callback
        (fn [] (rf/dispatch-sync [:game/tick] {:frame :game})))

      (is (empty? (fallthrough-warnings recorded))
          "explicit :frame opt is not a fallthrough — warning suppressed")
      (is (= 1 (count (no-such-handler-errors recorded)))
          "the existing handler-missing error still fires for the actual missing-handler case"))))

(deftest suppressed-when-dispatched-from-within-with-frame
  (testing "synchronous dispatch from inside a with-frame binding picks up the surrounding frame — no fallthrough"
    (rf/reg-frame :game {:doc "frame supplying the dynamic binding"})
    (rf/reg-event-db :game/tick {:frame :game} (fn [db _] db))

    (let [recorded (record-traces! ::with-frame)]
      (binding [frame/*current-frame* :game]
        (rf/dispatch-sync [:game/tick]))

      (is (empty? (fallthrough-warnings recorded))
          "with-frame binding successfully carried the frame — no async loss")
      (is (empty? (no-such-handler-errors recorded))
          "the handler ran"))))
