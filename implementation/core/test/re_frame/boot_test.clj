(ns re-frame.boot-test
  "Targeted JVM coverage for the framework boot lifecycle.

  Boot is exercised transitively in every other test via the reset-runtime
  fixture (which always calls rf/init!), but no dedicated coverage exists
  for the four entry points themselves:

    * init!                 — idempotent boot; explicit-adapter contract.
                              Per Spec 002 §`:rf/default` is an ordinary id
                              (EP-0002) init! does NOT create a :rf/default
                              frame — the runtime never synthesises a default.
    * install-adapter!      — single-adapter-per-process invariant
    * dispose-adapter!      — tear down + clear the slot
    * ensure-default-frame! — TEST-ONLY fixture helper that registers the
                              ordinary :rf/default frame on demand (NOT a
                              runtime path; init! no longer calls it).

  Per rf2-agql `(rf/init! ...)` requires an explicit adapter spec map.
  The no-arg form and the keyword form are both errors; the only
  legal call shape is `(rf/init! adapter-map)`.

  These tests deliberately install / dispose the adapter explicitly per
  test; they do NOT rely on rf/init! from a shared fixture, because the
  unit under test IS the boot lifecycle. The fixture below clears the
  registrar, frames, flows, AND the adapter slot to guarantee each test
  starts from a known cold state."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; ---- fixture --------------------------------------------------------------
;; Cold-start each test: clear all framework state INCLUDING the installed
;; adapter, so every deftest exercises the boot path from zero. We do NOT
;; call rf/init! here — that is the unit under test.

(defn cold-start [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  ;; Wipe both the install slot AND the disposed breadcrumb so each test
  ;; starts from a never-installed cold state (rf2-6wxys). A plain
  ;; `dispose-adapter!` would leave the breadcrumb true after the first
  ;; test that installed, biasing every subsequent throw assertion toward
  ;; `:rf.error/adapter-disposed` rather than `:rf.error/no-adapter-installed`.
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (test-fn)
  ;; Leave the world in a state the next namespace's fixture can reset
  ;; from cleanly.
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!))

(use-fixtures :each cold-start)

;; ---- helpers --------------------------------------------------------------

(defn- count-frames []
  (count @rf.frame/frames))

(defn- default-frame-count []
  (count (filter #(= :rf/default %) (keys @rf.frame/frames))))

;; ---- tests ----------------------------------------------------------------

(deftest init-is-idempotent
  (testing "init! is idempotent — calling twice does not double-install the adapter; it creates NO :rf/default frame"
    (is (nil? (rf.substrate.adapter/current-adapter))
        "precondition: no adapter installed at the start of the test")
    (is (zero? (count-frames))
        "precondition: no frames registered at the start of the test")
    ;; First boot.
    (rf/init! rf.substrate.plain-atom/adapter)
    (is (some? (rf.substrate.adapter/current-adapter))
        "init! installs the supplied adapter")
    (is (zero? (default-frame-count))
        "init! creates NO :rf/default frame (EP-0002: the runtime never synthesises a default)")
    (is (zero? (count-frames))
        "init! registers no frames at all")
    (let [adapter-after-first (rf.substrate.adapter/current-adapter-spec)
          frames-after-first  @rf.frame/frames]
      ;; Second boot — should be a no-op.
      (rf/init! rf.substrate.plain-atom/adapter)
      (is (identical? adapter-after-first (rf.substrate.adapter/current-adapter-spec))
          "the second init! does NOT re-install the adapter (same identity)")
      (is (= frames-after-first @rf.frame/frames)
          "the second init! does NOT mutate the frames registry"))
    (is (zero? (default-frame-count))
        ":rf/default is still absent after two init! calls")))

(deftest install-adapter-rejects-double-install
  (testing "install-adapter! raises :rf.error/adapter-already-installed on a second call"
    (is (nil? (rf.substrate.adapter/current-adapter))
        "precondition: cold start, no adapter installed")
    ;; First install — succeeds.
    (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter)
    (is (identical? rf.substrate.plain-atom/adapter (rf.substrate.adapter/current-adapter-spec))
        "first install-adapter! seats the plain-atom adapter")
    ;; Second install (without dispose) — must throw with the spec'd error.
    (let [thrown (try
                   (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown)
          "a second install-adapter! call without an intervening dispose throws")
      ;; rf2-vvixub — the message is now a human sentence carrying the
      ;; trailing [:rf.error/<id>] greppability token (Spec 009 §The
      ;; thrown-error shape); assert the token substring, NOT exact
      ;; equality. The canonical discriminator is :rf.error/id below.
      (is (re-find #"\[:rf\.error/adapter-already-installed\]"
                   (str (some-> thrown ex-message)))
          "the thrown message carries the [:rf.error/adapter-already-installed] token")
      (let [data (ex-data thrown)]
        (is (= :rf.error/adapter-already-installed (:rf.error/id data))
            "ex-data carries the canonical :rf.error/id discriminator (per Spec 009 §The thrown-error shape)")
        (is (some? (:installed data))
            "ex-data carries the currently :installed adapter")
        (is (some? (:attempted data))
            "ex-data carries the :attempted (rejected) adapter")))
    ;; Sanity: the originally-installed adapter is still seated.
    (is (identical? rf.substrate.plain-atom/adapter (rf.substrate.adapter/current-adapter-spec))
        "the rejected install does NOT replace or unseat the existing adapter")))

(deftest dispose-adapter-clears-slot
  (testing "dispose-adapter! tears down + clears the slot; subsequent install! succeeds"
    (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter)
    (is (identical? rf.substrate.plain-atom/adapter (rf.substrate.adapter/current-adapter-spec))
        "precondition: adapter installed")
    ;; Dispose — clears the slot.
    (rf.substrate.adapter/dispose-adapter!)
    (is (nil? (rf.substrate.adapter/current-adapter))
        "after dispose-adapter! the slot is nil")
    ;; Re-install — works now without throwing.
    (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter)
    (is (identical? rf.substrate.plain-atom/adapter (rf.substrate.adapter/current-adapter-spec))
        "install-adapter! succeeds after a prior dispose-adapter!"))
  (testing "dispose-adapter! on an empty slot is a no-op (no throw)"
    (rf.substrate.adapter/dispose-adapter!)
    (is (nil? (rf.substrate.adapter/current-adapter))
        "calling dispose-adapter! again is harmless")
    (rf.substrate.adapter/dispose-adapter!)
    (is (nil? (rf.substrate.adapter/current-adapter))
        "and a third call is still harmless"))
  (testing "dispose-adapter! invokes the adapter's :dispose-adapter! callback"
    (let [called? (atom false)
          fake    (assoc rf.substrate.plain-atom/adapter
                         :dispose-adapter! (fn [] (reset! called? true)))]
      (rf.substrate.adapter/install-adapter! fake)
      (rf.substrate.adapter/dispose-adapter!)
      (is @called?
          "the adapter's :dispose-adapter! fn was invoked during teardown")
      (is (nil? (rf.substrate.adapter/current-adapter))
          "the slot is cleared even when the callback runs"))))

(deftest throwing-adapter-cleanup-still-finalizes-the-process-lifecycle
  (let [boom (ex-info "adapter host cleanup failed" {:kind ::cleanup-failed})
        bad  (assoc rf.substrate.plain-atom/adapter
                    :dispose-adapter! (fn [] (throw boom)))]
    (rf.substrate.adapter/install-adapter! bad)
    (is (identical? boom
                    (try (rf.substrate.adapter/dispose-adapter!) nil
                         (catch clojure.lang.ExceptionInfo e e)))
        "the adapter cleanup error remains the primary throw")
    (is (nil? (rf.substrate.adapter/current-adapter-spec))
        "a cleanup throw cannot leave the one-adapter install slot seated")
    (is (true? (rf.substrate.adapter/adapter-disposed?))
        "the lifecycle breadcrumb records the attempted installed teardown")
    (is (= :rf.error/adapter-disposed
           (:rf.error/id
            (ex-data
             (try
               (rf.substrate.adapter/make-state-container {})
               nil
               (catch clojure.lang.ExceptionInfo e e)))))
        "delegation after a throwing cleanup sees terminal disposal, not a half-live adapter")
    (is (identical? rf.substrate.plain-atom/adapter
                    (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter))
        "a fresh adapter can install immediately after the failed cleanup")))

(deftest adapter-cleanup-claim-is-one-way-and-generation-safe
  (testing "a re-entrant destroy cannot invoke one generation's cleanup twice"
    (let [calls (atom 0)
          old   (assoc rf.substrate.plain-atom/adapter
                       :dispose-adapter!
                       (fn []
                         (swap! calls inc)
                         (rf.substrate.adapter/dispose-adapter!)))]
      (rf.substrate.adapter/install-adapter! old)
      (rf.substrate.adapter/dispose-adapter!)
      (is (= 1 @calls)
          "the terminal claim makes a nested destroy an idempotent no-op")
      (is (true? (rf.substrate.adapter/adapter-disposed?)))))
  (testing "a stale finalizer never clears a replacement generation"
    (let [replacement (assoc rf.substrate.plain-atom/adapter :kind ::replacement)
          old         (assoc rf.substrate.plain-atom/adapter
                             :dispose-adapter!
                             (fn []
                               ;; Adversarial test bypass: simulate a replacement
                               ;; generation appearing before the old cleanup's
                               ;; finally boundary. Public install cannot race into
                               ;; an occupied slot; this seam proves the stale
                               ;; generation guard itself rather than timing.
                               (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
                               (rf.substrate.adapter/install-adapter! replacement)))]
      (rf.substrate.adapter/install-adapter! old)
      (rf.substrate.adapter/dispose-adapter!)
      (is (identical? replacement (rf.substrate.adapter/current-adapter-spec))
          "the old generation's finally leaves the replacement seated")
      (is (= ::replacement (rf.substrate.adapter/current-adapter)))
      (is (false? (rf.substrate.adapter/adapter-disposed?))
          "the replacement generation's successful install owns the breadcrumb"))))

(deftest concurrent-adapter-destroy-has-one-cleanup-owner-and-atomic-state
  (let [entered (promise)
        release (promise)
        calls   (atom 0)
        old     (assoc rf.substrate.plain-atom/adapter
                       :dispose-adapter!
                       (fn []
                         (swap! calls inc)
                         (deliver entered :entered)
                         (when (= ::timeout (deref release 5000 ::timeout))
                           (throw (ex-info "cleanup release timed out" {})))))
        first   (do
                  (rf.substrate.adapter/install-adapter! old)
                  (future
                    (try
                      (rf.substrate.adapter/dispose-adapter!)
                      :destroyed
                      (catch Throwable e e))))]
    (try
      (is (= :entered (deref entered 5000 ::timeout))
          "the first destroy owns cleanup and reaches the held phase")
      (let [second (future (rf.substrate.adapter/dispose-adapter!))]
        (is (nil? (deref second 5000 ::timeout))
            "a concurrent destroy observes the claimed generation and does no cleanup"))
      (is (= 1 @calls) "exactly one cleanup owner ran")
      (is (nil? (rf.substrate.adapter/current-adapter-spec))
          "the terminal claim removes the generation from public introspection")
      (is (true? (rf.substrate.adapter/adapter-disposed?))
          "the disposed breadcrumb flips atomically with the terminal claim")
      (let [delegation (try
                         (rf.substrate.adapter/make-state-container {})
                         nil
                         (catch clojure.lang.ExceptionInfo e e))]
        (is (= :rf.error/adapter-disposed
               (:rf.error/id (ex-data delegation)))
            "delegation cannot enter cleanup's partly torn-down generation")
        (is (= :install-a-fresh-adapter
               (:recovery (ex-data delegation)))))
      (is (= :rf.error/adapter-already-installed
             (:rf.error/id
              (ex-data
               (try
                 (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter)
                 nil
                 (catch clojure.lang.ExceptionInfo e e)))))
          "the internal exact-generation claim blocks replacement until cleanup settles")
      (finally
        (deliver release :release)))
    (is (= :destroyed (deref first 5000 ::timeout)))
    (is (nil? (rf.substrate.adapter/current-adapter-spec)))
    (is (true? (rf.substrate.adapter/adapter-disposed?)))
    (is (identical? rf.substrate.plain-atom/adapter
                    (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter))
        "a fresh install succeeds after the exact cleanup owner settles")
    (is (false? (rf.substrate.adapter/adapter-disposed?)))))

(deftest ensure-default-frame-is-idempotent
  (testing "ensure-default-frame! creates :rf/default if absent; no-op if present"
    ;; Frame creation needs an adapter to allocate the app-db container.
    (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter)
    (is (zero? (count-frames))
        "precondition: no frames registered")
    ;; First call — creates :rf/default.
    (rf.frame/ensure-default-frame!)
    (is (= 1 (default-frame-count))
        ":rf/default is registered after the first call")
    (let [first-frame (get @rf.frame/frames :rf/default)
          frames-snap @rf.frame/frames]
      (is (some? first-frame)
          "the :rf/default frame is present in the frames registry")
      ;; Second call — no-op; identity preserved.
      (rf.frame/ensure-default-frame!)
      (is (identical? first-frame (get @rf.frame/frames :rf/default))
          "a second call does NOT replace the :rf/default frame (identity preserved)")
      (is (= frames-snap @rf.frame/frames)
          "a second call does NOT mutate the frames registry at all"))
    (is (= 1 (default-frame-count))
        ":rf/default still appears exactly once after two ensure! calls"))
  (testing "ensure-default-frame! does not disturb other frames"
    ;; Register a sibling frame BEFORE the (possibly redundant) ensure!.
    (rf/make-frame {:id :tenant-x :doc "tenant"})
    (let [tenant-before (get @rf.frame/frames :tenant-x)]
      (rf.frame/ensure-default-frame!)
      (is (identical? tenant-before (get @rf.frame/frames :tenant-x))
          "ensure-default-frame! leaves unrelated frames untouched"))))

;; ---- (rf/init! ...) explicit-adapter contract (rf2-agql, rf2-3ubmv) ------
;;
;; Per rf2-agql `(rf/init! ...)` requires an explicit adapter spec map.
;; Per rf2-3ubmv the no-arg arity was cut from the fn defn entirely so
;; calling `(rf/init!)` raises a language-level ArityException at the
;; call site rather than a runtime ex-info — earlier diagnosis, clearer
;; stack trace, IDE-flaggable. The nil and keyword forms still raise
;; :rf.error/no-adapter-specified at runtime (there is no default-
;; adapter registry to fall back to and no keyword-to-adapter lookup
;; table).

(deftest init-no-arg-raises-arity-exception
  (testing "(rf/init!) with no args raises ArityException (rf2-3ubmv — the no-arg arity was cut)"
    (is (nil? (rf.substrate.adapter/current-adapter))
        "precondition: no adapter installed")
    (let [thrown (try
                   #_:clj-kondo/ignore
                   (rf/init!)
                   nil
                   (catch clojure.lang.ArityException e e))]
      (is (some? thrown)
          "rf/init! with no args raises ArityException — ArityException is more discoverable than runtime ex-info")
      (is (re-find #"init!" (str (.getMessage ^clojure.lang.ArityException thrown)))
          "the ArityException message identifies init! as the offending fn"))
    (is (nil? (rf.substrate.adapter/current-adapter))
        "the failed init! did NOT install any adapter")))

(deftest init-nil-arg-raises-no-adapter-specified
  (testing "(rf/init! nil) raises :rf.error/no-adapter-specified"
    (let [thrown (try
                   (rf/init! nil)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown)
          "rf/init! with nil raises")
      (is (= :rf.error/no-adapter-specified
             (:rf.error/id (ex-data thrown)))
          "ex-data carries the :rf.error/no-adapter-specified tag"))
    (is (nil? (rf.substrate.adapter/current-adapter))
        "the failed init! did NOT install any adapter")))

(deftest init-keyword-arg-raises-no-adapter-specified
  (testing "(rf/init! :reagent) raises :rf.error/no-adapter-specified — no registry, no keyword form"
    (let [thrown (try
                   (rf/init! :reagent)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown)
          "rf/init! with a keyword raises — keyword form is not supported")
      (is (= :rf.error/no-adapter-specified
             (:rf.error/id (ex-data thrown)))
          "the thrown exception carries the :rf.error/no-adapter-specified tag")
      (let [data (ex-data thrown)]
        (is (= :reagent (:received data))
            "ex-data echoes the offending keyword")
        (is (= "adapter spec map" (:expected data))
            "ex-data names the expected shape")
        (is (string? (:reason data))
            "ex-data carries a :reason string pointing at the explicit-map pattern")))
    (is (nil? (rf.substrate.adapter/current-adapter))
        "the failed init! did NOT install any adapter")))

(deftest init-map-form-installs-literal-spec
  (testing "(rf/init! adapter-map) installs the literal adapter — only legal form"
    (rf/init! rf.substrate.plain-atom/adapter)
    (is (identical? rf.substrate.plain-atom/adapter (rf.substrate.adapter/current-adapter-spec))
        "init! with a literal adapter map installs that exact spec")
    (is (zero? (default-frame-count))
        "no :rf/default frame is created by init! (EP-0002 — the runtime never synthesises a default)")))

;; ---- current-adapter vs current-adapter-spec (rf2-ivx3a) -----------------
;;
;; Per Spec 006 §Adapter introspection: `current-adapter` returns the
;; `:kind` discriminator keyword from the installed adapter spec map;
;; `current-adapter-spec` returns the full map. The two questions are
;; genuinely different — predicate / branch code vs tools that need fn
;; handles — and each accessor answers exactly one.

(deftest current-adapter-returns-discriminator-keyword
  (testing "current-adapter returns the :kind keyword per Spec 006 §Adapter introspection"
    (is (nil? (rf.substrate.adapter/current-adapter))
        "no adapter installed → current-adapter is nil")
    (rf/init! rf.substrate.plain-atom/adapter)
    (is (= :rf.adapter/plain-atom (rf.substrate.adapter/current-adapter))
        "current-adapter projects the :kind slot of the installed adapter")
    (is (keyword? (rf.substrate.adapter/current-adapter))
        "current-adapter returns a keyword, NOT the adapter spec map")
    (is (= :rf.adapter/plain-atom (:kind rf.substrate.plain-atom/adapter))
        "the plain-atom adapter spec map carries :kind :rf.adapter/plain-atom directly")))

(deftest current-adapter-spec-returns-the-installed-map
  (testing "current-adapter-spec returns the spec map passed to install"
    (is (nil? (rf.substrate.adapter/current-adapter-spec))
        "no adapter installed → current-adapter-spec is nil")
    (rf/init! rf.substrate.plain-atom/adapter)
    (is (identical? rf.substrate.plain-atom/adapter (rf.substrate.adapter/current-adapter-spec))
        "current-adapter-spec returns the exact map identity passed to init!")
    (is (map? (rf.substrate.adapter/current-adapter-spec))
        "current-adapter-spec returns a map, NOT the discriminator keyword")
    (is (fn? (:make-state-container (rf.substrate.adapter/current-adapter-spec)))
        "the spec map carries the adapter contract fns")
    (is (fn? (:replace-container! (rf.substrate.adapter/current-adapter-spec)))
        "the spec map carries the adapter contract fns")
    (is (fn? (:make-derived-value (rf.substrate.adapter/current-adapter-spec)))
        "the spec map carries the adapter contract fns")))

(deftest current-adapter-falls-back-to-custom-when-kind-missing
  (testing "an installed adapter lacking :kind reports as :custom per Spec 006"
    (let [kindless (dissoc rf.substrate.plain-atom/adapter :kind)]
      (rf.substrate.adapter/install-adapter! kindless)
      (is (= :custom (rf.substrate.adapter/current-adapter))
          "current-adapter falls back to :custom when the spec map omits :kind")
      (is (identical? kindless (rf.substrate.adapter/current-adapter-spec))
          "current-adapter-spec still returns the literal installed map"))))

(deftest adapter-swap-resets-substrate-state-keeps-registrar
  (testing "dispose then install a different adapter — registrar survives, substrate state resets"
    ;; Boot under adapter A (plain-atom), register a handler, register a
    ;; non-default frame, and seed the default frame's app-db. Per EP-0002
    ;; the runtime no longer synthesises :rf/default — this test declares it
    ;; explicitly (an ordinary id) and runs ambient ops inside an explicit
    ;; :rf/default scope, exactly as a single-frame app would.
    (rf/init! rf.substrate.plain-atom/adapter)
    (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    (rf/reg-sub      :n    (fn [db _] (:n db)))
    (rf/make-frame {:id :rf/default :doc "explicit app frame"})
    (rf/make-frame {:id :tenant-a :doc "tenant-a"})
    (binding [rf.frame/*current-frame* :rf/default]
      (rf/dispatch-sync [:seed 7]))
    (is (= 7 (rf/subscribe-once [:n] {:frame :rf/default}))
        "before swap: the seeded value is visible via the layer-1 sub")
    (let [registrar-before @rf.registrar/kind->id->metadata]
      ;; Build a distinct second adapter — same shape as plain-atom but a
      ;; different identity, with wrapping fns that prove the runtime is
      ;; routing through B (not the disposed A) after the swap.
      (let [make-calls    (atom 0)
            replace-calls (atom 0)
            base-make     (:make-state-container rf.substrate.plain-atom/adapter)
            base-replace  (:replace-container! rf.substrate.plain-atom/adapter)
            adapter-b (assoc rf.substrate.plain-atom/adapter
                             :make-state-container
                             (fn [v]
                               (swap! make-calls inc)
                               (base-make v))
                             :replace-container!
                             (fn [c v]
                               (swap! replace-calls inc)
                               (base-replace c v)))]
        ;; Swap: dispose A, install B.
        (rf.substrate.adapter/dispose-adapter!)
        (is (nil? (rf.substrate.adapter/current-adapter))
            "between swap steps the slot is empty")
        (rf.substrate.adapter/install-adapter! adapter-b)
        (is (identical? adapter-b (rf.substrate.adapter/current-adapter-spec))
            "adapter B is now installed")
        ;; The registrar (events / subs / handlers) survives the swap.
        (is (= registrar-before @rf.registrar/kind->id->metadata)
            "registrar contents are unchanged across the adapter swap")
        ;; Substrate-held state (frame app-db containers) does NOT survive
        ;; — the old plain-atom containers are not connected to adapter B.
        ;; Recreate the :rf/default frame's containers via re-registration
        ;; so that subsequent dispatches use B's :make-state-container.
        (reset! rf.frame/frames {})
        (rf.frame/ensure-default-frame!)
        (is (= 1 (default-frame-count))
            ":rf/default frame is recreated cleanly under adapter B")
        (is (pos? @make-calls)
            "adapter B's :make-state-container was invoked when the new :rf/default frame was created (proves frame creation routes through B)")
        ;; Handlers from before the swap are still callable — registrar
        ;; preserved them. Issue a fresh dispatch and observe via B.
        (let [replace-pre @replace-calls]
          (binding [rf.frame/*current-frame* :rf/default]
            (rf/dispatch-sync [:seed 99]))
          (is (> @replace-calls replace-pre)
              "adapter B's :replace-container! was invoked by dispatch-sync (proves event commit routes through B, not the disposed A)"))
        (is (= 99 (rf/subscribe-once [:n] {:frame :rf/default}))
            "registered :seed event + :n sub still work end-to-end under adapter B")))))

;; ---- substrate delegation: uniform no-adapter-installed throw (rf2-zdfi1) -
;;
;; Per rf2-zdfi1 every substrate-delegation fn in
;; `re-frame.substrate.adapter` throws ONE shape when no adapter is
;; installed:
;;
;;   :rf.error/no-adapter-installed
;;   {:where    'rf/<fn>            ;; the offending public-surface symbol
;;    :recovery :no-recovery
;;    :reason   "<where> was called before (rf/init! ...); ..."}
;;
;; Before rf2-zdfi1 only `make-state-container` threw structured ex-info;
;; the other five required delegation fns (`read-container`,
;; `replace-container!`, `make-derived-value`, `render`, `render-to-string`)
;; plus the two optional fns (`subscribe-container`,
;; `register-context-provider`) silently NPE'd on a nil adapter — strictly
;; worse than a structured throw because background-thread NPEs are hard
;; to diagnose and the ex-info shape did not match the documented
;; missing-fn contract used elsewhere in core (rf2-h824v + rf2-uchhp).

(defn- catch-no-adapter
  "Invoke `thunk` with no adapter installed; return the caught
  ExceptionInfo (or nil if nothing threw)."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e e)))

(deftest substrate-delegation-uniform-no-adapter-throw
  (testing "every substrate-delegation fn throws :rf.error/no-adapter-installed before (rf/init! ...)"
    (is (nil? (rf.substrate.adapter/current-adapter))
        "precondition: cold start — no adapter installed")
    (let [cases [['rf/make-state-container        #(rf.substrate.adapter/make-state-container         {:k :v})]
                 ['rf/read-container              #(rf.substrate.adapter/read-container               ::dummy-container)]
                 ['rf/replace-container!          #(rf.substrate.adapter/replace-container!           ::dummy-container {:new :value})]
                 ['rf/make-derived-value          #(rf.substrate.adapter/make-derived-value           [::source]        (constantly 42))]
                 ['rf/render                      #(rf.substrate.adapter/render                       [:div]            ::mount-point {})]
                 ['rf/render-to-string            #(rf.substrate.adapter/render-to-string             [:div]            {})]
                 ['rf/subscribe-container         #(rf.substrate.adapter/subscribe-container          ::dummy-container (fn [_]))]
                 ['rf/register-context-provider   #(rf.substrate.adapter/register-context-provider    :rf/default)]]]
      (doseq [[where-sym thunk] cases]
        (let [thrown (catch-no-adapter thunk)]
          (is (some? thrown)
              (str where-sym " throws when called before (rf/init! ...)"))
          ;; rf2-vvixub — message is a human sentence + the trailing
          ;; [:rf.error/<id>] token; assert the token substring, not
          ;; exact keyword-equality. Canonical discriminator is :rf.error/id.
          (is (re-find #"\[:rf\.error/no-adapter-installed\]"
                       (str (some-> thrown ex-message)))
              (str where-sym " message carries the [:rf.error/no-adapter-installed] token"))
          (let [data (ex-data thrown)]
            ;; Per Spec 009 §The thrown-error shape: canonical
            ;; discriminator slot is `:rf.error/id` (require-adapter!
            ;; now stamps it).
            (is (= :rf.error/no-adapter-installed (:rf.error/id data))
                (str where-sym " ex-data carries the canonical :rf.error/id discriminator"))
            (is (= where-sym (:where data))
                (str where-sym " ex-data :where echoes the offending public surface symbol"))
            (is (= :no-recovery (:recovery data))
                (str where-sym " ex-data :recovery is :no-recovery"))
            (is (string? (:reason data))
                (str where-sym " ex-data :reason is a string pointing at (rf/init! ...)"))
            (is (re-find #"rf/init!" (str (:reason data)))
                (str where-sym " ex-data :reason names rf/init! as the recovery action"))))))))

(deftest replace-container-nil-container-skips-adapter-check
  (testing "replace-container! with a nil container short-circuits via the rf2-ft2b error path and does NOT consult the adapter slot"
    ;; Defense-in-depth nil-container guard is checked BEFORE the
    ;; adapter lookup so a scheduled drain hitting a destroyed frame
    ;; does not produce a misleading 'no-adapter-installed' throw — it
    ;; correctly emits :rf.error/write-after-destroy (EP-0008 / rf2-500ech)
    ;; regardless of whether an adapter is installed.
    (is (nil? (rf.substrate.adapter/current-adapter))
        "precondition: no adapter installed")
    (is (nil? (rf.substrate.adapter/replace-container! nil {:any :value}))
        "replace-container! on nil container returns nil silently (no throw)")
    (is (nil? (rf.substrate.adapter/current-adapter))
        "the nil-container path did not consult or modify the adapter slot")))

;; ---- disposed-vs-never-installed (rf2-6wxys) ------------------------------
;;
;; Post-dispose runtime calls now raise `:rf.error/adapter-disposed`,
;; distinct from `:rf.error/no-adapter-installed` (the fresh-process
;; case). Both states leave the install slot nil so a subsequent
;; install-adapter! works.

(deftest adapter-disposed-predicate-tracks-lifecycle
  (testing "adapter-disposed? reflects the dispose/install lifecycle"
    (is (false? (rf.substrate.adapter/adapter-disposed?))
        "fresh cold start — no install, no dispose; breadcrumb is false")
    (rf/init! rf.substrate.plain-atom/adapter)
    (is (false? (rf.substrate.adapter/adapter-disposed?))
        "install clears the breadcrumb (and was already false)")
    (rf.substrate.adapter/dispose-adapter!)
    (is (true? (rf.substrate.adapter/adapter-disposed?))
        "after dispose-adapter!, the breadcrumb is true")
    (rf/init! rf.substrate.plain-atom/adapter)
    (is (false? (rf.substrate.adapter/adapter-disposed?))
        "fresh install clears the breadcrumb")))

(deftest dispose-with-no-install-does-not-set-breadcrumb
  (testing "dispose-adapter! is a no-op when no adapter is installed; the breadcrumb stays false"
    (is (nil? (rf.substrate.adapter/current-adapter))
        "precondition: no adapter installed")
    (is (false? (rf.substrate.adapter/adapter-disposed?))
        "precondition: breadcrumb is false")
    (rf.substrate.adapter/dispose-adapter!)
    (is (false? (rf.substrate.adapter/adapter-disposed?))
        "dispose with no adapter does not pretend a fresh process is post-dispose")))

(deftest substrate-delegation-after-dispose-throws-adapter-disposed
  (testing "every substrate-delegation fn throws :rf.error/adapter-disposed after dispose-adapter!"
    (rf/init! rf.substrate.plain-atom/adapter)
    (rf.substrate.adapter/dispose-adapter!)
    (is (nil? (rf.substrate.adapter/current-adapter))
        "precondition: adapter slot is empty after dispose")
    (is (true? (rf.substrate.adapter/adapter-disposed?))
        "precondition: disposed breadcrumb is true")
    (let [cases [['rf/make-state-container        #(rf.substrate.adapter/make-state-container         {:k :v})]
                 ['rf/read-container              #(rf.substrate.adapter/read-container               ::dummy-container)]
                 ['rf/replace-container!          #(rf.substrate.adapter/replace-container!           ::dummy-container {:new :value})]
                 ['rf/make-derived-value          #(rf.substrate.adapter/make-derived-value           [::source]        (constantly 42))]
                 ['rf/render                      #(rf.substrate.adapter/render                       [:div]            ::mount-point {})]
                 ['rf/render-to-string            #(rf.substrate.adapter/render-to-string             [:div]            {})]
                 ['rf/subscribe-container         #(rf.substrate.adapter/subscribe-container          ::dummy-container (fn [_]))]
                 ['rf/register-context-provider   #(rf.substrate.adapter/register-context-provider    :rf/default)]]]
      (doseq [[where-sym thunk] cases]
        (let [thrown (catch-no-adapter thunk)]
          (is (some? thrown)
              (str where-sym " throws when called after dispose-adapter!"))
          ;; rf2-vvixub — message is a human sentence + the trailing
          ;; [:rf.error/<id>] token; assert the token substring, not
          ;; exact keyword-equality. Canonical discriminator is :rf.error/id.
          (is (re-find #"\[:rf\.error/adapter-disposed\]"
                       (str (some-> thrown ex-message)))
              (str where-sym " message carries the [:rf.error/adapter-disposed] token (not :no-adapter-installed)"))
          (let [data (ex-data thrown)]
            (is (= :rf.error/adapter-disposed (:rf.error/id data))
                (str where-sym " ex-data carries the canonical :rf.error/id discriminator"))
            (is (= where-sym (:where data))
                (str where-sym " ex-data :where echoes the offending public surface symbol"))
            (is (= :install-a-fresh-adapter (:recovery data))
                (str where-sym " ex-data :recovery names the fresh-install remedy"))
            (is (re-find #"destroy-adapter!" (str (:reason data)))
                (str where-sym " ex-data :reason mentions the prior destroy-adapter!"))))))))
