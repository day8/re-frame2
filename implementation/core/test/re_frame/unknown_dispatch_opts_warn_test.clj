(ns re-frame.unknown-dispatch-opts-warn-test
  "Emit `:rf.warning/unknown-dispatch-opt` when a `dispatch` / `dispatch-sync`
  opts map carries a key outside `re-frame.router.diagnostics/known-dispatch-opts`.

  `build-envelope` reads only the known keys, so a typo (`:fram` for `:frame`)
  would otherwise change nothing and give no signal — a quietness the
  no-silent-swallow principle forbids. The warning is computed as the opts
  keys the known set does not contain, so `known-set-matches-build-envelope-reads`
  pinning that set exactly is what says every recognised key stays quiet.

  The warning is OBSERVATIONAL (`:recovery :no-recovery`): the dispatch
  proceeds unchanged. It is emitted in `build-envelope` before frame
  resolution, and the fixture binds a `:rf/default` scope so the ambient
  dispatches complete after it fires.

  ## Posture split

  The warning is dev-only (gated on `rf.interop/debug-enabled?`), so every
  assertion about it sits in a `(when rf.interop/debug-enabled? …)` arm. What
  survives the gate is the observational claim: each case lands a marker in
  app-db and asserts it arrived, in both postures."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.router.diagnostics :as rf.router.diagnostics]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (binding [rf.frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- record-traces! [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- unknown-opt-warnings [recorded]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= :rf.warning/unknown-dispatch-opt (:operation ev))))
           @recorded))

(defn- reg-marker-event!
  "Register `id` as a handler that stamps `[:landed id]` into app-db — the
  always-on witness that the dispatch still reached its handler."
  [id]
  (rf/reg-event id (fn [{:keys [db]} _] {:db (assoc db :landed id)})))

(defn- landed? [frame-id id]
  (= id (:landed (rf/app-db-value frame-id))))

(deftest fires-on-unknown-opts-key
  (testing "an unrecognised opts key emits exactly one warning naming the bad
            key and the known set, and the dispatch still lands"
    (reg-marker-event! :app/noop)
    (let [recorded (record-traces! ::unknown)]
      (rf/dispatch-sync [:app/noop] {:fram :rf/default})
      (is (landed? :rf/default :app/noop))
      (when rf.interop/debug-enabled?
        (let [warns (unknown-opt-warnings recorded)
              w     (first warns)
              t     (:tags w)]
          (is (= [1 [:app/noop] :app/noop [:fram] true true :no-recovery]
                 [(count warns) (:event t) (:event-id t) (:unknown-keys t)
                  (contains? (set (:known-keys t)) :frame)
                  ;; the reason lists the known keys, the likely intended
                  ;; :frame among them
                  (boolean (re-find #":frame" (:reason t)))
                  (:recovery w)])))))))

(deftest names-every-unknown-key-in-one-warning
  (testing "multiple unknown keys give ONE warning naming them all, and the
            legitimate :origin beside them is not flagged"
    (reg-marker-event! :app/noop)
    (let [recorded (record-traces! ::multi)]
      (rf/dispatch-sync [:app/noop] {:fram :rf/default :srce :ui :origin :app})
      (is (landed? :rf/default :app/noop))
      (when rf.interop/debug-enabled?
        (let [warns (unknown-opt-warnings recorded)]
          (is (= [1 #{:fram :srce}] [(count warns) (set (:unknown-keys (:tags (first warns))))])))))))

(deftest async-dispatch-path-also-warns
  (testing "the queued `dispatch` path warns too — build-envelope, where the
            check lives, runs at enqueue time on the caller's thread"
    (reg-marker-event! :app/noop)
    (let [recorded (record-traces! ::async)]
      (rf/dispatch [:app/noop] {:fram :rf/default})
      ;; the drain runs on the next-tick executor with no return-before-start
      ;; guarantee, so the marker is polled
      (is (rf.test-support/poll-until #(landed? :rf/default :app/noop)
                                      {:label "queued dispatch drains despite the unknown opt"}))
      (when rf.interop/debug-enabled?
        (is (= 1 (count (unknown-opt-warnings recorded))))))))

(deftest known-set-matches-build-envelope-reads
  ;; the set moves with build-envelope's reads. Beside the app-facing opts it
  ;; carries the internal ones build-envelope threads through:
  ;; :rf.cofx/mint-policy (EP-0017 §6), :step-index (the :initial-events
  ;; setup-step index run-setup-events! stamps), :rf.frame/expected-incarnation
  ;; (the capture-frame token) and :rf.flow/settle? (Spec 013 §Sequencing)
  (is (= #{:frame :fx-overrides :interceptor-overrides :trace-id :source
           :source-detail :origin :rf.cofx :rf.cofx/mint-policy
           :rf.trace/call-site :rf.flow/settle?
           :step-index :rf.frame/expected-incarnation}
         rf.router.diagnostics/known-dispatch-opts)))
