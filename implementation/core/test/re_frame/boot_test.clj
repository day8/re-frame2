(ns re-frame.boot-test
  "Targeted JVM coverage for the framework boot lifecycle.

  Boot is exercised transitively in every other test via the reset-runtime
  fixture (which always calls rf/init!), but no dedicated coverage exists
  for the four entry points themselves:

    * init!                 — idempotent boot
    * install-adapter!      — single-adapter-per-process invariant
    * dispose-adapter!      — tear down + clear the slot
    * ensure-default-frame! — :rf/default presence guarantee

  These tests deliberately install / dispose the adapter explicitly per
  test; they do NOT rely on rf/init! from a shared fixture, because the
  unit under test IS the boot lifecycle. The fixture below clears the
  registrar, frames, flows, AND the adapter slot to guarantee each test
  starts from a known cold state."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; ---- fixture --------------------------------------------------------------
;; Cold-start each test: clear all framework state INCLUDING the installed
;; adapter, so every deftest exercises the boot path from zero. We do NOT
;; call rf/init! here — that is the unit under test.

(defn cold-start [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (adapter/dispose-adapter!)
  (test-fn)
  ;; Leave the world in a state the next namespace's fixture can reset
  ;; from cleanly.
  (adapter/dispose-adapter!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {}))

(use-fixtures :each cold-start)

;; ---- helpers --------------------------------------------------------------

(defn- count-frames []
  (count @frame/frames))

(defn- default-frame-count []
  (count (filter #(= :rf/default %) (keys @frame/frames))))

;; ---- tests ----------------------------------------------------------------

(deftest init-is-idempotent
  (testing "init! is idempotent — calling twice does not double-install or duplicate :rf/default"
    (is (nil? (adapter/current-adapter))
        "precondition: no adapter installed at the start of the test")
    (is (zero? (count-frames))
        "precondition: no frames registered at the start of the test")
    ;; First boot.
    (rf/init!)
    (is (some? (adapter/current-adapter))
        "init! installs an adapter (the plain-atom default on the JVM)")
    (is (= 1 (default-frame-count))
        "init! registers exactly one :rf/default frame")
    (let [adapter-after-first (adapter/current-adapter)
          frames-after-first  @frame/frames]
      ;; Second boot — should be a no-op.
      (rf/init!)
      (is (identical? adapter-after-first (adapter/current-adapter))
          "the second init! does NOT re-install the adapter (same identity)")
      (is (= frames-after-first @frame/frames)
          "the second init! does NOT mutate the frames registry"))
    (is (= 1 (default-frame-count))
        ":rf/default appears exactly once after two init! calls")))

(deftest install-adapter-rejects-double-install
  (testing "install-adapter! raises :rf.error/adapter-already-installed on a second call"
    (is (nil? (adapter/current-adapter))
        "precondition: cold start, no adapter installed")
    ;; First install — succeeds.
    (adapter/install-adapter! plain-atom/adapter)
    (is (identical? plain-atom/adapter (adapter/current-adapter))
        "first install-adapter! seats the plain-atom adapter")
    ;; Second install (without dispose) — must throw with the spec'd error.
    (let [thrown (try
                   (adapter/install-adapter! plain-atom/adapter)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown)
          "a second install-adapter! call without an intervening dispose throws")
      (is (= ":rf.error/adapter-already-installed"
             (some-> thrown ex-message))
          "the thrown exception carries the :rf.error/adapter-already-installed tag")
      (let [data (ex-data thrown)]
        (is (some? (:installed data))
            "ex-data carries the currently :installed adapter")
        (is (some? (:attempted data))
            "ex-data carries the :attempted (rejected) adapter")))
    ;; Sanity: the originally-installed adapter is still seated.
    (is (identical? plain-atom/adapter (adapter/current-adapter))
        "the rejected install does NOT replace or unseat the existing adapter")))

(deftest dispose-adapter-clears-slot
  (testing "dispose-adapter! tears down + clears the slot; subsequent install! succeeds"
    (adapter/install-adapter! plain-atom/adapter)
    (is (identical? plain-atom/adapter (adapter/current-adapter))
        "precondition: adapter installed")
    ;; Dispose — clears the slot.
    (adapter/dispose-adapter!)
    (is (nil? (adapter/current-adapter))
        "after dispose-adapter! the slot is nil")
    ;; Re-install — works now without throwing.
    (adapter/install-adapter! plain-atom/adapter)
    (is (identical? plain-atom/adapter (adapter/current-adapter))
        "install-adapter! succeeds after a prior dispose-adapter!"))
  (testing "dispose-adapter! on an empty slot is a no-op (no throw)"
    (adapter/dispose-adapter!)
    (is (nil? (adapter/current-adapter))
        "calling dispose-adapter! again is harmless")
    (adapter/dispose-adapter!)
    (is (nil? (adapter/current-adapter))
        "and a third call is still harmless"))
  (testing "dispose-adapter! invokes the adapter's :dispose-adapter! callback"
    (let [called? (atom false)
          fake    (assoc plain-atom/adapter
                         :dispose-adapter! (fn [] (reset! called? true)))]
      (adapter/install-adapter! fake)
      (adapter/dispose-adapter!)
      (is @called?
          "the adapter's :dispose-adapter! fn was invoked during teardown")
      (is (nil? (adapter/current-adapter))
          "the slot is cleared even when the callback runs"))))

(deftest ensure-default-frame-is-idempotent
  (testing "ensure-default-frame! creates :rf/default if absent; no-op if present"
    ;; Frame creation needs an adapter to allocate the app-db container.
    (adapter/install-adapter! plain-atom/adapter)
    (is (zero? (count-frames))
        "precondition: no frames registered")
    ;; First call — creates :rf/default.
    (frame/ensure-default-frame!)
    (is (= 1 (default-frame-count))
        ":rf/default is registered after the first call")
    (let [first-frame (get @frame/frames :rf/default)
          frames-snap @frame/frames]
      (is (some? first-frame)
          "the :rf/default frame is present in the frames registry")
      ;; Second call — no-op; identity preserved.
      (frame/ensure-default-frame!)
      (is (identical? first-frame (get @frame/frames :rf/default))
          "a second call does NOT replace the :rf/default frame (identity preserved)")
      (is (= frames-snap @frame/frames)
          "a second call does NOT mutate the frames registry at all"))
    (is (= 1 (default-frame-count))
        ":rf/default still appears exactly once after two ensure! calls"))
  (testing "ensure-default-frame! does not disturb other frames"
    ;; Register a sibling frame BEFORE the (possibly redundant) ensure!.
    (frame/reg-frame :tenant-x {:doc "tenant"})
    (let [tenant-before (get @frame/frames :tenant-x)]
      (frame/ensure-default-frame!)
      (is (identical? tenant-before (get @frame/frames :tenant-x))
          "ensure-default-frame! leaves unrelated frames untouched"))))

;; ---- (rf/init!) default-adapter resolver (rf2-84po) ----------------------
;;
;; Per rf2-84po (resolves rf2-4cb6), `(rf/init!)` with no args resolves
;; through the default-adapter registry populated by substrate-adapter
;; ns-loads. The resolver has three branches:
;;
;;   1 registered    → install it
;;   0 registered    → :rf.error/no-adapter-registered
;;   N>1 registered  → :rf.error/multiple-default-adapters
;;
;; These tests drive each branch by manipulating the registry directly
;; (the production wiring is the substrate ns's defonce; tests need the
;; cold-start ergonomics that explicit register/unregister provide).

(deftest init-no-arg-resolves-single-registered-default
  (testing "(rf/init!) with no args picks the only registered default"
    ;; Cold start: registry carries plain-atom from JVM ns-load. The
    ;; cold-start fixture cleared the installed-adapter slot so init!
    ;; will install fresh.
    (is (nil? (adapter/current-adapter))
        "precondition: no adapter installed")
    (is (contains? (adapter/registered-default-adapters) :plain-atom)
        "precondition: plain-atom auto-registered as the JVM default")
    ;; No-arg init!: should resolve to plain-atom.
    (rf/init!)
    (is (some? (adapter/current-adapter))
        "init! installed an adapter via the default-adapter registry")
    (is (= 1 (default-frame-count))
        ":rf/default frame is present after the resolved init!")))

(deftest init-zero-registered-raises-no-adapter-registered
  (testing "(rf/init!) with no args + zero registered defaults raises :rf.error/no-adapter-registered"
    ;; Drain the registry so we hit the zero-case branch. We must
    ;; restore plain-atom afterwards so subsequent tests in the suite
    ;; (and the cold-start fixture) start from a known baseline.
    (let [restore-key   :plain-atom
          restore-spec  (get (adapter/registered-default-adapters) restore-key)]
      (try
        (adapter/unregister-default-adapter! restore-key)
        (is (empty? (adapter/registered-default-adapters))
            "precondition: registry is empty after the unregister")
        (let [thrown (try
                       (rf/init!)
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "rf/init! with no args raises when zero adapters registered")
          (is (= ":rf.error/no-adapter-registered"
                 (some-> thrown ex-message))
              "the thrown exception carries the :rf.error/no-adapter-registered tag")
          (let [data (ex-data thrown)]
            (is (= 'init! (:where data))
                "ex-data identifies the calling fn")
            (is (= :no-recovery (:recovery data))
                "ex-data flags :no-recovery — the call must be re-issued with an arg")
            (is (string? (:reason data))
                "ex-data carries a :reason string explaining the recovery path"))
          ;; Slot was untouched by the failed init!.
          (is (nil? (adapter/current-adapter))
              "the failed init! did NOT install any adapter"))
        (finally
          (when restore-spec
            (adapter/register-default-adapter! restore-key restore-spec)))))))

(deftest init-multiple-registered-raises-multiple-default-adapters
  (testing "(rf/init!) with no args + >1 registered defaults raises :rf.error/multiple-default-adapters"
    ;; Simulate a mixed-substrate app: register a second adapter
    ;; alongside plain-atom. The second one is just a copy of plain-atom
    ;; under a different key — the test cares about the resolver's
    ;; arity, not the adapter's behaviour.
    (let [synth-key   :test-fake-substrate
          synth-spec  plain-atom/adapter]
      (try
        (adapter/register-default-adapter! synth-key synth-spec)
        (is (= 2 (count (adapter/registered-default-adapters)))
            "precondition: two adapters registered as defaults")
        (let [thrown (try
                       (rf/init!)
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "rf/init! with no args raises when >1 adapters registered")
          (is (= ":rf.error/multiple-default-adapters"
                 (some-> thrown ex-message))
              "the thrown exception carries the :rf.error/multiple-default-adapters tag")
          (let [data (ex-data thrown)]
            (is (= 'init! (:where data))
                "ex-data identifies the calling fn")
            (is (= :no-recovery (:recovery data))
                "ex-data flags :no-recovery — the call must be re-issued with a key")
            (is (every? keyword? (:keys data))
                "ex-data :keys is a vector of registered adapter keys")
            (is (= #{:plain-atom synth-key} (set (:keys data)))
                "ex-data enumerates exactly the registered keys so the consumer can disambiguate")
            (is (string? (:reason data))
                "ex-data carries a :reason string pointing the consumer at (rf/init! :key)"))
          (is (nil? (adapter/current-adapter))
              "the failed init! did NOT install any adapter"))
        (finally
          (adapter/unregister-default-adapter! synth-key))))))

(deftest init-keyword-form-disambiguates-multi-adapter
  (testing "(rf/init! :keyword) bypasses default-resolution and looks up by key"
    ;; Same setup as the multi-adapter case above: two registered, but
    ;; instead of no-args we pass a key. Should resolve cleanly to the
    ;; named adapter.
    (let [synth-key   :test-fake-substrate-2
          synth-spec  plain-atom/adapter]
      (try
        (adapter/register-default-adapter! synth-key synth-spec)
        (is (= 2 (count (adapter/registered-default-adapters)))
            "precondition: two adapters registered as defaults")
        ;; Keyword form: explicit :plain-atom pick.
        (rf/init! :plain-atom)
        (is (some? (adapter/current-adapter))
            "init! :plain-atom installed an adapter")
        (is (= 1 (default-frame-count))
            ":rf/default frame is present after the keyworded init!")
        (finally
          (adapter/unregister-default-adapter! synth-key))))))

(deftest init-keyword-unknown-raises
  (testing "(rf/init! :unknown-key) raises :rf.error/unknown-adapter-key"
    (let [thrown (try
                   (rf/init! :no-such-adapter)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown)
          "rf/init! with an unknown key raises")
      (is (= ":rf.error/unknown-adapter-key"
             (some-> thrown ex-message))
          "the thrown exception carries the :rf.error/unknown-adapter-key tag")
      (let [data (ex-data thrown)]
        (is (= :no-such-adapter (:key data))
            "ex-data echoes the offending key")
        (is (vector? (:known data))
            "ex-data lists the :known registered keys for diagnostic")))
    (is (nil? (adapter/current-adapter))
        "the failed init! did NOT install any adapter")))

(deftest init-map-form-installs-literal-spec
  (testing "(rf/init! adapter-map) installs the literal adapter — bypasses registry entirely"
    (rf/init! plain-atom/adapter)
    (is (identical? plain-atom/adapter (adapter/current-adapter))
        "init! with a literal adapter map installs that exact spec")
    (is (= 1 (default-frame-count))
        ":rf/default frame is present after the literal-adapter init!")))

(deftest adapter-swap-resets-substrate-state-keeps-registrar
  (testing "dispose then install a different adapter — registrar survives, substrate state resets"
    ;; Boot under adapter A (plain-atom), register a handler, register a
    ;; non-default frame, and seed the default frame's app-db.
    (rf/init!)
    (rf/reg-event-db :seed (fn [_ [_ n]] {:n n}))
    (rf/reg-sub      :n    (fn [db _] (:n db)))
    (rf/reg-frame :tenant-a {:doc "tenant-a"})
    (rf/dispatch-sync [:seed 7])
    (is (= 7 (rf/subscribe-value :rf/default [:n]))
        "before swap: the seeded value is visible via the layer-1 sub")
    (let [registrar-before @registrar/kind->id->metadata]
      ;; Build a distinct second adapter — same shape as plain-atom but a
      ;; different identity, with wrapping fns that prove the runtime is
      ;; routing through B (not the disposed A) after the swap.
      (let [make-calls    (atom 0)
            replace-calls (atom 0)
            base-make     (:make-state-container plain-atom/adapter)
            base-replace  (:replace-container! plain-atom/adapter)
            adapter-b (assoc plain-atom/adapter
                             :make-state-container
                             (fn [v]
                               (swap! make-calls inc)
                               (base-make v))
                             :replace-container!
                             (fn [c v]
                               (swap! replace-calls inc)
                               (base-replace c v)))]
        ;; Swap: dispose A, install B.
        (adapter/dispose-adapter!)
        (is (nil? (adapter/current-adapter))
            "between swap steps the slot is empty")
        (adapter/install-adapter! adapter-b)
        (is (identical? adapter-b (adapter/current-adapter))
            "adapter B is now installed")
        ;; The registrar (events / subs / handlers) survives the swap.
        (is (= registrar-before @registrar/kind->id->metadata)
            "registrar contents are unchanged across the adapter swap")
        ;; Substrate-held state (frame app-db containers) does NOT survive
        ;; — the old plain-atom containers are not connected to adapter B.
        ;; Recreate the :rf/default frame's containers via re-registration
        ;; so that subsequent dispatches use B's :make-state-container.
        (reset! frame/frames {})
        (frame/ensure-default-frame!)
        (is (= 1 (default-frame-count))
            ":rf/default frame is recreated cleanly under adapter B")
        (is (pos? @make-calls)
            "adapter B's :make-state-container was invoked when the new :rf/default frame was created (proves frame creation routes through B)")
        ;; Handlers from before the swap are still callable — registrar
        ;; preserved them. Issue a fresh dispatch and observe via B.
        (let [replace-pre @replace-calls]
          (rf/dispatch-sync [:seed 99])
          (is (> @replace-calls replace-pre)
              "adapter B's :replace-container! was invoked by dispatch-sync (proves event commit routes through B, not the disposed A)"))
        (is (= 99 (rf/subscribe-value :rf/default [:n]))
            "registered :seed event + :n sub still work end-to-end under adapter B")))))
