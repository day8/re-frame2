(ns re-frame.sub-cache-test
  "Tests for the per-frame sub-cache disposal contract (Spec 006
  §Reference counting and disposal, rf2-cmfln).

  The cache uses **synchronous ref-count disposal**: when the last
  subscriber drops (`unsubscribe` drives the 1 → 0 transition), the
  cache slot is evicted IN-TICK. The reaction is disposed, the on-
  dispose callback releases input refs (cascading layer-2+), and the
  slot is dissoc'd. No deferred-grace timer, no batched dispose.

  Pre-rf2-cmfln these tests covered both `grace=0` (synchronous) and
  `grace>0` (deferred) paths; the deferred-grace mechanism has been
  retired (clean swap per pre-alpha) so the surface under test is the
  single sync path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.live-frame :as live-frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-jue6sp): `init!` no longer synthesises `:rf/default`,
  ;; and ambient subscribe / unsubscribe / clear-sub-cache! now require a
  ;; carried frame stamp (no `:rf/default` floor). These cache tests
  ;; exercise the ambient read surface against a single conventional app
  ;; frame, so the fixture registers `:rf/default` explicitly and pins it
  ;; as the established scope for the whole test body via `with-frame` —
  ;; the standard "small app chooses :rf/default as its explicit id"
  ;; shape. The frameless-read failure path is covered separately by
  ;; `subscribe-frameless-read-raises-no-frame-context` below.
  (frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- cache-keys
  []
  (set (keys @(:sub-cache (frame/frame :rf/default)))))

(defn- entry-ref-count
  [query-v]
  (get-in @(:sub-cache (frame/frame :rf/default)) [query-v :ref-count]))

(defn- entry
  [query-v]
  (get-in @(:sub-cache (frame/frame :rf/default)) [query-v]))

;; ---- cache-entry shape pins Spec 006 §Cache shape (rf2-spnfk) --------------
;;
;; Spec 006 §Cache shape advertises EXACTLY {:reaction :inputs :ref-count}.
;; This test pins that key-set so a phantom slot (:value / :on-dispose /
;; :pending-dispose / :registered-at / :derived-container) re-introduced in
;; `compute-and-cache!` is caught and forces a matching spec edit. The
;; cached VALUE is never a stored slot — it lives on the reaction, read via
;; deref — so the entry MUST NOT carry a :value key.

(deftest cache-entry-shape-matches-spec-006
  (testing "a freshly-built cache entry stores exactly :reaction :inputs :ref-count"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 2 :b 3}}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    (let [r (rf/subscribe [:sum])]
      (is (= 5 @r))
      ;; Layer-2 entry: inputs resolved to the :<- chain.
      (let [e (entry [:sum])]
        (is (= #{:reaction :inputs :ref-count} (set (keys e)))
            "entry key-set is exactly {:reaction :inputs :ref-count} — no
             :value / :on-dispose / :pending-dispose / :registered-at slot")
        (is (= [[:a] [:b]] (:inputs e)) ":inputs holds the resolved :<- chain")
        (is (= 1 (:ref-count e)))
        (is (some? (:reaction e)) "the reaction (derived container) is stored")
        (is (not (contains? e :value))
            "the value lives on the reaction (deref), never a stored slot"))
      ;; Layer-1 entry: empty :<- chain.
      (let [e (entry [:a])]
        (is (= #{:reaction :inputs :ref-count} (set (keys e)))
            "layer-1 entry key-set is identical")
        (is (= [] (:inputs e)) "layer-1 sub has no :<- inputs"))
      (rf/unsubscribe [:sum]))))

;; ---- synchronous disposal --------------------------------------------------

(deftest sync-disposal-on-last-unsubscribe
  (testing "ref-count → 0 disposes the cache slot synchronously (rf2-cmfln)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:n])
    (is (contains? (cache-keys) [:n]))
    (is (= 1 (entry-ref-count [:n])))

    (rf/unsubscribe [:n])
    ;; The slot is gone immediately — no scheduling, no timer.
    (is (not (contains? (cache-keys) [:n]))
        "cache slot disposed in-tick on the 1 → 0 transition")))

(deftest sync-disposal-respects-multiple-subscribers
  (testing "only the LAST subscriber dropping disposes"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:n])
    (rf/subscribe [:n])
    (is (= 2 (entry-ref-count [:n])))

    (rf/unsubscribe [:n])
    (is (= 1 (entry-ref-count [:n])))
    (is (contains? (cache-keys) [:n]))

    (rf/unsubscribe [:n])
    (is (not (contains? (cache-keys) [:n])))))

;; ---- rf2-cmfln: no recompute between ref-count → 0 and dispose -----------
;;
;; The bead's acceptance #2/#3 require that no wasted sub-runs fire between
;; the moment ref-count drops to zero and the moment the reaction is
;; disposed. With sync dispose the two events happen in the same tick on
;; the same call site — there is no observable gap. This test pins the
;; absence: a state change AFTER the last unsubscribe MUST NOT re-invoke
;; the sub's compute fn (because the reaction has been disposed, its
;; watch on app-db unwound).

(deftest no-recompute-between-zero-ref-count-and-dispose
  (testing "after the last unsubscribe, a state change does NOT re-invoke
            the sub's compute fn — the reaction has been disposed
            synchronously, its watch on app-db unwound (rf2-cmfln #2/#3)"
    (let [recompute-count (atom 0)]
      (rf/reg-event :seed   (fn [{:keys [db]} _]      {:db {:n 0}}))
      (rf/reg-event :update (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
      (rf/reg-sub :n (fn [db _]
                       (swap! recompute-count inc)
                       (:n db)))
      (rf/dispatch-sync [:seed])

      ;; Subscribe + read forces compute.
      (let [r (rf/subscribe [:n])]
        (is (= 0 @r))
        (is (= 1 @recompute-count) "compute fn ran once for the initial read")

        ;; A change recomputes (one derefer still holds the slot).
        (rf/dispatch-sync [:update 1])
        (is (= 1 @r))
        (is (= 2 @recompute-count) "compute fn ran for the change"))

      ;; Last unsubscribe — synchronous dispose. The slot is gone; the
      ;; reaction's watch on app-db is unwound IN THIS CALL.
      (rf/unsubscribe [:n])
      (is (not (contains? (cache-keys) [:n]))
          "cache slot disposed synchronously")
      (let [count-after-dispose @recompute-count]

        ;; Subsequent app-db changes MUST NOT recompute. Pre-rf2-cmfln
        ;; the deferred-grace mechanism kept the slot alive for ~50ms;
        ;; any state change in that window would recompute the sub
        ;; (wasted work) before the timer fired. Sync dispose eliminates
        ;; that window: the reaction is dead before this call returns.
        (rf/dispatch-sync [:update 2])
        (is (= count-after-dispose @recompute-count)
            "compute fn did NOT run after the last unsubscribe — there
             is no live cache entry to recompute, and the watch the
             reaction held on app-db was removed at dispose-time")

        (rf/dispatch-sync [:update 3])
        (rf/dispatch-sync [:update 4])
        (is (= count-after-dispose @recompute-count)
            "subsequent state changes still do not recompute — sync
             dispose closed the window entirely")))))

;; ---- clear-sub-cache! ----------------------------------------------------

(deftest clear-subscription-cache-empties-the-cache
  (testing "clear-sub-cache! disposes every cached entry"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:n])
    (is (contains? (cache-keys) [:n]))

    (rf/clear-sub-cache! :rf/default)
    (is (empty? (cache-keys)))))

;; ---- hot-reload re-registration disposes cached entries ------------------

(deftest hot-reload-evicts-cached-entries
  (testing "re-registering a sub disposes the cached slot"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:n])
    (is (contains? (cache-keys) [:n]))

    ;; Re-register with a different body — invalidate-sub-on-replace! fires.
    (rf/reg-sub :n (fn [db _] (* 2 (:n db))))
    (is (not (contains? (cache-keys) [:n]))
        "hot-reload evicts the slot regardless of ref-count")))

;; ---- subscribe-once teardown is synchronous ------------------------------
;;
;; subscribe-once's whole lifetime — subscribe, deref, dispose — completes
;; in the calling tick. Per rf2-cmfln this is the same path every
;; unsubscribe drives (sync), so the test asserts the user-visible shape:
;; the slot is gone when subscribe-once returns.

(deftest subscribe-once-disposes-synchronously
  (testing "subscribe-once's teardown is synchronous"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (is (not (contains? (cache-keys) [:n]))
        "no cache slot before subscribe-once")
    (is (= 7 (rf/subscribe-once [:n])))
    (is (not (contains? (cache-keys) [:n]))
        "slot disposed synchronously inside subscribe-once")))

(deftest subscribe-once-respects-concurrent-subscriber
  (testing "subscribe-once's teardown only disposes when it drove the 1 → 0"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    ;; Pin the slot with a reactive subscribe — ref-count = 1.
    (rf/subscribe [:n])
    (is (= 1 (entry-ref-count [:n])))

    ;; subscribe-once runs: subscribe (ref-count → 2), deref, unsubscribe
    ;; (ref-count → 1). The decrement does NOT drive 1 → 0 — the pinning
    ;; subscribe is still live — so the slot MUST survive untouched.
    (is (= 7 (rf/subscribe-once [:n])))
    (is (contains? (cache-keys) [:n])
        "slot survives — the pinning subscriber kept ref-count > 0")
    (is (= 1 (entry-ref-count [:n]))
        "ref-count back to 1 after subscribe-once's paired inc/dec")

    ;; Release the pin so the cache is clean for sibling tests.
    (rf/unsubscribe [:n])
    (is (not (contains? (cache-keys) [:n])))))

;; ---- subscribe-once {:frame} opts-map call-shape (rf2-bfadc6) -------------
;;
;; Per rf2-bfadc6 (Mike ruled OPTION A): `subscribe-once` has the public
;; `{:frame}` opts-map call-shape `(subscribe-once query-v {:frame f})`,
;; parallel to `subscribe` (EP-0024). API-shrink #1 (rf2-csbbwu) DELETED the
;; frame-first positional form entirely — every sig is `[query-v]` /
;; `[query-v opts]`, no `vector?` shape-discrimination. This closes the
;; misbinding footgun EP-0024 closed for `subscribe`: an author who learned
;; `(subscribe [:x] {:frame f})` writes the SAME shape here and the runtime
;; binds `f` as the frame (not `[:x]` as a frame-id and `{:frame f}` as a
;; query-v). `unsubscribe` deliberately does NOT gain this form (guard test
;; below) — it stays frame-first-only.

(defn- frame-cache-keys
  "The set of query-vectors currently cached in frame `frame-id`."
  [frame-id]
  (set (keys @(:sub-cache (frame/frame frame-id)))))

(deftest subscribe-once-opts-map-binds-the-named-frame
  (testing "(subscribe-once query-v {:frame f}) reads frame f from OUTSIDE its
            scope — the opts-map call-shape parallel to subscribe (rf2-bfadc6)"
    (rf/reg-frame :bfadc6/left  {:doc "left frame"})
    (rf/reg-frame :bfadc6/right {:doc "right frame"})
    (rf/reg-event :seed (fn [{:keys [db]} [_ v]] {:db {:v v}}))
    (rf/reg-sub :v (fn [db _] (:v db)))
    (rf/dispatch-sync [:seed :left-value]  {:frame :bfadc6/left})
    (rf/dispatch-sync [:seed :right-value] {:frame :bfadc6/right})
    ;; Unwind the fixture's :rf/default scope so the read has NO ambient
    ;; frame — the opts-map {:frame …} is the only thing naming the target.
    (binding [frame/*current-frame* nil]
      (is (= :left-value (rf/subscribe-once [:v] {:frame :bfadc6/left}))
          "the opts-map {:frame :bfadc6/left} binds LEFT — not [:v] as a frame-id")
      (is (= :right-value (rf/subscribe-once [:v] {:frame :bfadc6/right}))
          "the opts-map {:frame :bfadc6/right} binds RIGHT")
      ;; One-shot: no reference retained on either frame.
      (is (not (contains? (frame-cache-keys :bfadc6/left)  [:v]))
          "opts-map subscribe-once disposes its slot in-tick (left)")
      (is (not (contains? (frame-cache-keys :bfadc6/right) [:v]))
          "opts-map subscribe-once disposes its slot in-tick (right)"))))

(deftest subscribe-once-former-frame-first-no-longer-resolves
  (testing "the DELETED frame-first positional form — (subscribe-once
            frame-id query-v) — no longer targets the named frame
            (API-shrink #1, rf2-csbbwu). `frame-id` (a keyword) is now read
            as `query-v` and `query-v` (a vector) as `opts`; `(:frame opts)`
            on a vector is nil, so it falls to the 1-arity ambient path,
            where `(first query-v)` on the keyword throws — it fails LOUDLY
            rather than silently misrouting."
    (rf/reg-frame :bfadc6/f {:doc "frame f"})
    (rf/reg-event :seed (fn [{:keys [db]} [_ v]] {:db {:v v}}))
    (rf/reg-sub :v (fn [db _] (:v db)))
    (rf/dispatch-sync [:seed :the-value] {:frame :bfadc6/f})
    (binding [frame/*current-frame* nil]
      ;; Public opts-map form still resolves.
      (is (= :the-value (rf/subscribe-once [:v] {:frame :bfadc6/f}))
          "opts-map (subscribe-once query-v {:frame f}) resolves the value")
      ;; The former frame-first shape now throws rather than resolving.
      (is (thrown? IllegalArgumentException
                   (rf/subscribe-once :bfadc6/f [:v]))
          "the deleted frame-first shape fails loudly, never silently"))))

(deftest subscribe-once-opts-map-with-empty-opts-uses-ambient
  (testing "(subscribe-once query-v {}) with no :frame key falls through to the
            ambient scope — the opts-map is optional, mirroring subscribe"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])
    ;; Under the fixture's :rf/default with-frame scope, an opts map without
    ;; :frame resolves the ambient frame — same as the 1-arity form.
    (is (= 7 (rf/subscribe-once [:n] {}))
        "opts map with no :frame reads the ambient (established-scope) frame")
    (is (not (contains? (cache-keys) [:n]))
        "still one-shot — the slot is disposed in-tick")))

(deftest unsubscribe-did-NOT-gain-an-opts-map-form
  (testing "unsubscribe stays FRAME-FIRST (rf2-bfadc6 Option A) — it does NOT
            accept (unsubscribe query-v {:frame f}). A map second-arg is NOT a
            frame target: the 2-arity treats arg1 as the frame-first target and
            arg2 (the {:frame f} MAP) as the query-v, which keys nothing in the
            cache — so it does NOT tear down the entry a real subscribe made.
            This is the negative guard that no opts-map overload was added."
    (rf/reg-frame :bfadc6/u {:doc "frame u"})
    (rf/reg-event :seed (fn [{:keys [db]} [_ v]] {:db {:v v}}))
    (rf/reg-sub :v (fn [db _] (:v db)))
    (rf/dispatch-sync [:seed :v-value] {:frame :bfadc6/u})
    ;; Take out a real, live subscription against frame :bfadc6/u.
    (rf/subscribe [:v] {:frame :bfadc6/u})
    (is (contains? (frame-cache-keys :bfadc6/u) [:v])
        "the entry is cached under frame :bfadc6/u")
    ;; If unsubscribe HAD an opts-map form, this would decrement/dispose the
    ;; [:v] slot. Because it does NOT, arg1 `[:v]` is read as the FRAME target
    ;; (a vector frame-id → resolves nothing) and the {:frame …} MAP is read as
    ;; the query-v → the cache lookup misses. The live [:v] entry survives.
    (rf/unsubscribe [:v] {:frame :bfadc6/u})
    (is (contains? (frame-cache-keys :bfadc6/u) [:v])
        "the {:frame …}-as-second-arg call did NOT tear down the entry —
         unsubscribe has NO opts-map form (frame-first only, by ruling)")
    ;; The real, frame-first teardown DOES evict it.
    (rf/unsubscribe :bfadc6/u [:v])
    (is (not (contains? (frame-cache-keys :bfadc6/u) [:v]))
        "the frame-first (unsubscribe frame-id query-v) form still tears down")))

;; ---- subscribe-before-register does NOT cache (rf2-l9u5) -----------------
;;
;; Per Spec 006 §What happens when a sub references an unknown sub:
;; subscribing to an unregistered sub-id emits :rf.error/no-such-sub and
;; returns a nil-yielding reaction. Crucially, that miss is NOT cached —
;; the cache slot stays empty so that a later registration (boot order,
;; lazy-loaded namespace) is observed by the next subscribe, which builds
;; a fresh reaction against the real body.

(deftest subscribe-before-register-does-not-cache
  (testing "subscribing before reg-sub does not cache; later registration is observed"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/dispatch-sync [:init])

    ;; Subscribe BEFORE the sub is registered.
    (let [r1 (rf/subscribe [:my-sub])]
      (is (some? r1)
          "subscribe still returns a (nil-yielding) reaction so callers don't deref nil")
      (is (nil? @r1)
          "the reaction yields nil per :replaced-with-default recovery")
      (is (not (contains? (cache-keys) [:my-sub]))
          "the no-such-sub miss MUST NOT be cached (rf2-l9u5)"))

    ;; Now register the sub. First-time registration does NOT fire the
    ;; replacement hook — the boot-order fix is "don't cache on miss",
    ;; not "invalidate on first register".
    (rf/reg-sub :my-sub (fn [db _] (:n db)))
    (is (not (contains? (cache-keys) [:my-sub]))
        "first registration leaves the cache untouched")

    ;; Next subscribe builds a fresh reaction against the real body.
    (let [r2 (rf/subscribe [:my-sub])]
      (is (= 7 @r2)
          "post-registration subscribe yields the real value")
      (is (contains? (cache-keys) [:my-sub])
          "the post-registration reaction IS cached"))))

(deftest subscribe-before-register-survives-multiple-misses
  (testing "repeated subscribe-before-register calls do not poison the cache"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 11}}))
    (rf/dispatch-sync [:init])

    (dotimes [_ 3]
      (let [r (rf/subscribe [:lazy-sub])]
        (is (nil? @r))
        (is (not (contains? (cache-keys) [:lazy-sub])))))

    (rf/reg-sub :lazy-sub (fn [db _] (:n db)))
    (is (= 11 @(rf/subscribe [:lazy-sub]))
        "after registration, the cache is fresh and the real body runs")))

;; ---- clear-sub does NOT clear the per-frame cache (rf2-79tl) -------------
;;
;; v2 preserves v1's contract: clear-sub removes the registration but
;; leaves cached reactions in place. Cache eviction is a separate
;; concern, owned by clear-sub-cache!, hot-reload (re-register)
;; and frame disposal.

(deftest clear-sub-id-leaves-cache-intact
  (testing "(clear-sub id) removes the registration but does not evict cache slots"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (let [r1 (rf/subscribe [:n])]
      (is (= 7 @r1))
      (is (contains? (cache-keys) [:n])))

    (rf/clear-sub :n)
    ;; Cache slot survives clear-sub — this is the documented v1 contract.
    (is (contains? (cache-keys) [:n])
        "clear-sub leaves the cache slot in place; use clear-sub-cache! to evict")
    (is (nil? (registrar/lookup :sub :n))
        "the registration is gone")

    ;; Subsequent subscribe reuses the cached reaction (reading the
    ;; still-derived value), even though the registration is gone.
    (let [r2 (rf/subscribe [:n])]
      (is (= 7 @r2)
          "cache hit serves the previously-derived value"))

    ;; clear-sub-cache! is the explicit follow-up that fully resets.
    (rf/clear-sub-cache! :rf/default)
    (is (empty? (cache-keys))
        "clear-sub-cache! is the cache-eviction half of the pair")))

(deftest clear-sub-no-arg-leaves-cache-intact
  (testing "(clear-sub) clears every registration but leaves the cache untouched"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :a (fn [db _] (:n db)))
    (rf/reg-sub :b (fn [db _] (* 2 (:n db))))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:a])
    (rf/subscribe [:b])
    (is (= #{[:a] [:b]} (cache-keys)))

    (rf/clear-sub)
    (is (= #{[:a] [:b]} (cache-keys))
        "clear-sub with no args wipes registrations but not cache slots")
    (is (= {} (registrar/registrations :sub))
        "every :sub registration is gone")

    (rf/clear-sub-cache! :rf/default)
    (is (empty? (cache-keys)))))

;; ---- idempotent unsubscribe (rf2-zikr) ------------------------------------
;;
;; Per Spec 006 §Reference counting and disposal: `unsubscribe` is
;; ref-count-based. Calling it past zero (cleanup in both a teardown hook
;; and a `finally`) must be idempotent — the ref-count clamps at zero
;; and a second call against an already-disposed slot is a no-op (the
;; slot is gone, the underlying entry-lookup misses).

(deftest unsubscribe-past-zero-is-idempotent
  (testing "calling unsubscribe more than once for the same sub-id does not throw or schedule extra work (rf2-zikr / rf2-cmfln)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:n])
    (is (= 1 (entry-ref-count [:n])))

    ;; First unsubscribe — ref-count 1 → 0, slot disposed synchronously.
    (rf/unsubscribe [:n])
    (is (not (contains? (cache-keys) [:n]))
        "first unsubscribe disposes the slot in-tick")

    ;; Second / third unsubscribe — slot is gone; the entry-lookup
    ;; misses and the call is a no-op. Idempotent.
    (rf/unsubscribe [:n])
    (rf/unsubscribe [:n])
    (is (not (contains? (cache-keys) [:n]))
        "repeated unsubscribe against a disposed slot is a no-op"))

  (testing "extra unsubscribe before any subscribe is a complete no-op"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])
    ;; No subscribe — the entry doesn't exist; unsubscribe must not
    ;; throw and must not create an entry.
    (rf/unsubscribe [:n])
    (is (not (contains? (cache-keys) [:n]))
        "unsubscribe against a missing entry leaves the cache untouched")))

;; ---- rf2-f3rd: layer-2+ disposal decrements input ref-counts --------------
;;
;; Per Spec 006 §Reference counting and disposal — disposal cascades clause:
;; when a layer-2 sub disposes, its layer-1 inputs lose one reader each;
;; if they were held only by that layer-2 sub, they cascade to disposal in
;; the same tick (sync). Pre-fix, `compute-and-cache!`'s `add-on-dispose!`
;; callback only dissoc'd the parent slot and never decremented the input
;; ref-counts that `subscribe` incremented during construction — so input
;; ref-counts leaked. The fix walks `:input-signals` and calls
;; `unsubscribe` on each input symmetrically with the construction-time
;; `subscribe` calls. These tests pin the symmetric invariant.

(deftest layer-2-disposal-decrements-input-ref-counts
  (testing "disposing a layer-2 sub decrements ref-counts on every :<- input"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 2 :b 3}}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum
      :<- [:a]
      :<- [:b]
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    ;; Subscribe to the parent layer-2 sub. This recursively subscribes
    ;; to both inputs, bumping each to ref-count 1.
    (let [r (rf/subscribe [:sum])]
      (is (= 5 @r))
      (is (= 1 (entry-ref-count [:sum])) "parent ref-count = 1")
      (is (= 1 (entry-ref-count [:a])) "input :a ref-count = 1 after layer-2 build")
      (is (= 1 (entry-ref-count [:b])) "input :b ref-count = 1 after layer-2 build"))

    ;; Dispose the parent (sole subscriber drops → sync dispose).
    (rf/unsubscribe [:sum])

    ;; Parent slot is gone; inputs cascaded to ref-count 0 and were
    ;; themselves disposed (no other holders).
    (is (not (contains? (cache-keys) [:sum])) "parent disposed")
    (is (not (contains? (cache-keys) [:a]))
        "input :a disposed via cascade (ref-count → 0)")
    (is (not (contains? (cache-keys) [:b]))
        "input :b disposed via cascade (ref-count → 0)")))

(deftest layer-2-disposal-respects-shared-inputs
  (testing "disposing one layer-2 sub decrements shared input only by one"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 2 :b 3 :c 4}}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :c (fn [db _] (:c db)))
    ;; Two layer-2 subs that share input :a.
    (rf/reg-sub :ab :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/reg-sub :ac :<- [:a] :<- [:c] (fn [[a c] _] (+ a c)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:ab])
    (rf/subscribe [:ac])

    ;; :a is held by both layer-2 subs → ref-count 2.
    (is (= 2 (entry-ref-count [:a])) "shared input :a has ref-count 2")
    (is (= 1 (entry-ref-count [:b])))
    (is (= 1 (entry-ref-count [:c])))

    ;; Dispose one layer-2 sub.
    (rf/unsubscribe [:ab])

    ;; :a's ref-count drops to 1 (not 0) — the other layer-2 still holds it.
    (is (not (contains? (cache-keys) [:ab])) ":ab disposed")
    (is (contains? (cache-keys) [:a])
        ":a survives — still referenced by :ac")
    (is (= 1 (entry-ref-count [:a]))
        "shared input ref-count dropped by exactly 1 (now 1, not 0, not 2)")
    (is (not (contains? (cache-keys) [:b]))
        ":b was held only by :ab — disposed via cascade")
    (is (contains? (cache-keys) [:c])
        ":c untouched — still held by :ac")
    (is (= 1 (entry-ref-count [:c])))

    ;; Dispose the other layer-2 sub. Now :a's ref-count hits 0.
    (rf/unsubscribe [:ac])
    (is (not (contains? (cache-keys) [:ac])))
    (is (not (contains? (cache-keys) [:a]))
        ":a finally disposed after the last layer-2 holder dropped")
    (is (not (contains? (cache-keys) [:c]))
        ":c disposed via cascade")))

(deftest layer-2-disposal-respects-externally-held-input
  (testing "an input also held by a direct subscribe is not over-disposed"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 2 :b 3}}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    ;; External subscriber to :a, parallel to the layer-2 sub that also uses :a.
    (rf/subscribe [:a])
    (rf/subscribe [:sum])

    ;; :a has TWO holders: the direct subscribe AND :sum's construction.
    (is (= 2 (entry-ref-count [:a]))
        ":a ref-count is 2 — one direct subscribe, one layer-2 dependency")
    (is (= 1 (entry-ref-count [:b])))
    (is (= 1 (entry-ref-count [:sum])))

    ;; Dispose the layer-2 sub. :a's count drops from 2 → 1 (NOT 0).
    (rf/unsubscribe [:sum])
    (is (not (contains? (cache-keys) [:sum])))
    (is (contains? (cache-keys) [:a]) ":a NOT disposed — external holder remains")
    (is (= 1 (entry-ref-count [:a]))
        "decrement was exactly one — external subscribe still holds :a")
    (is (not (contains? (cache-keys) [:b]))
        ":b had only the layer-2 holder — disposed via cascade")

    ;; External unsubscribe finishes the job.
    (rf/unsubscribe [:a])
    (is (not (contains? (cache-keys) [:a]))
        ":a disposed when external holder drops")))

(deftest layer-3-disposal-cascades-through-chain
  (testing "disposal cascades recursively through a layer-3 chain"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 2}}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :a*2 :<- [:a]   (fn [a _] (* 2 a)))
    (rf/reg-sub :a*4 :<- [:a*2] (fn [a2 _] (* 2 a2)))
    (rf/dispatch-sync [:init])

    (let [r (rf/subscribe [:a*4])]
      (is (= 8 @r))
      (is (= 1 (entry-ref-count [:a*4])))
      (is (= 1 (entry-ref-count [:a*2])))
      (is (= 1 (entry-ref-count [:a]))))

    (rf/unsubscribe [:a*4])

    ;; Whole chain cascaded to disposal.
    (is (not (contains? (cache-keys) [:a*4])))
    (is (not (contains? (cache-keys) [:a*2])))
    (is (not (contains? (cache-keys) [:a])))))

(deftest layer-2-multi-subscriber-disposal-keeps-inputs-alive
  (testing "with two parent subscribers, dropping one keeps inputs at ref-count 1"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 2 :b 3}}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    ;; Two subscribers to the same parent — parent ref-count goes to 2,
    ;; inputs are constructed once (only the FIRST subscribe runs the
    ;; cache-miss path that subscribes to inputs).
    (rf/subscribe [:sum])
    (rf/subscribe [:sum])
    (is (= 2 (entry-ref-count [:sum])))
    (is (= 1 (entry-ref-count [:a]))
        "inputs only acquired on the cache-miss path — ref-count 1")
    (is (= 1 (entry-ref-count [:b])))

    ;; First unsubscribe: parent drops to 1, inputs untouched
    ;; (the parent slot didn't dispose, so its on-dispose didn't fire).
    (rf/unsubscribe [:sum])
    (is (= 1 (entry-ref-count [:sum])))
    (is (= 1 (entry-ref-count [:a]))
        "inputs untouched — parent slot still live")
    (is (= 1 (entry-ref-count [:b])))

    ;; Second unsubscribe: parent disposes, cascade fires.
    (rf/unsubscribe [:sum])
    (is (not (contains? (cache-keys) [:sum])))
    (is (not (contains? (cache-keys) [:a])))
    (is (not (contains? (cache-keys) [:b])))))

;; ---- rf2-agpv2.2: input ref-count must not leak on the not-cached path ----
;;
;; `compute-and-cache!` subscribes each layer-2+ `:<-` input (bumping its
;; ref-count) BEFORE it re-resolves the frame to read `:sub-cache`. The
;; on-dispose that SYMMETRICALLY releases those inputs is wired only inside
;; `(when (and cache sub-meta) …)`. If the frame is destroyed (or its
;; container torn down) BETWEEN `subscribe`'s frame-record resolution and
;; `compute-and-cache!`'s re-resolution, `cache` is nil: the parent
;; reaction is built and returned but never cached and never dispose-wired,
;; so without the compensating release the just-subscribed inputs leak
;; forever. We reproduce the narrow seam deterministically by redefining
;; `frame/frame` to return the live record while the inputs are resolved,
;; then nil on the parent's cache-read re-resolution (= "frame destroyed at
;; the seam"). The fix releases the inputs on that not-cached path.

(deftest layer-2-input-refs-released-when-frame-destroyed-mid-build
  (testing "a layer-2 build whose parent cache-read sees a destroyed frame
            still releases the inputs it subscribed (no ref-count leak)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 2 :b 3}}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    (let [real-frame  frame/frame
          ;; Trip the parent's cache-read re-resolution (and only that one)
          ;; to nil: once BOTH inputs are present in the real cache, the
          ;; next `frame/frame` call for :rf/default is the parent's
          ;; `(:sub-cache (frame/frame …))` read inside `compute-and-cache!`
          ;; for [:sum]. Returning nil there is exactly "the frame was
          ;; destroyed between line-500 resolution and line-269 re-read".
          tripped?    (atom false)
          inputs-cached? (fn []
                           (let [c @(:sub-cache (real-frame :rf/default))]
                             (and (contains? c [:a]) (contains? c [:b]))))]
      (with-redefs [frame/frame
                    (fn [id]
                      (if (and (= id :rf/default)
                               (not @tripped?)
                               (inputs-cached?))
                        (do (reset! tripped? true) nil) ;; the parent cache-read
                        (real-frame id)))]
        (let [r (rf/subscribe [:sum])]
          ;; The reaction is still built+returned (callers don't explode);
          ;; under :replaced-with-default the parent simply isn't cached.
          (is (some? r) "a reaction is still returned on the not-cached path")
          (is (true? @tripped?)
              "the parent cache-read re-resolution was tripped to nil")))

      ;; The parent [:sum] was NOT cached (cache was nil at the read).
      (is (not (contains? (cache-keys) [:sum]))
          "[:sum] was not cached — the destroyed-frame seam")
      ;; THE FIX: the inputs subscribed during the layer-2 build were
      ;; released on the not-cached path, so they cascaded to ref-count 0
      ;; and disposed. Pre-fix they would sit orphaned at ref-count 1.
      (is (not (contains? (cache-keys) [:a]))
          "input :a released (no orphaned ref) — rf2-agpv2.2")
      (is (not (contains? (cache-keys) [:b]))
          "input :b released (no orphaned ref) — rf2-agpv2.2"))))

;; ---- rf2-agpv2.3: no-such-sub trace tag-shape matches Spec 009 -------------
;;
;; Spec 009 §Error catalogue documents `:rf.error/no-such-sub` tags as
;; `:rf.sub/id` / `:unresolved-input` / `:resolved-inputs`. Spec/Ownership
;; names 009 as the canonical owner of the trace-event model; the
;; `NoSuchSubTags` projection in Spec-Schemas is non-canonical. This pins
;; the emit to the canonical shape so tooling keying off the documented tag
;; names finds them. Recovery (nil-yielding reaction, not cached) is
;; unchanged and covered by `subscribe-before-register-does-not-cache`.

(deftest no-such-sub-trace-tags-match-spec-009
  (testing "subscribing an unregistered sub emits Spec-009 tag-shape"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/dispatch-sync [:init])
    (let [traces (atom [])]
      (rf/register-listener! :trace ::no-such (fn [ev] (swap! traces conj ev)))
      ;; [:missing/sub] is not registered → :rf.error/no-such-sub.
      (let [r (rf/subscribe [:missing/sub])]
        (is (nil? @r) ":replaced-with-default — the reaction yields nil"))
      (rf/unregister-listener! :trace ::no-such)
      (let [hit (some (fn [ev]
                        (when (= :rf.error/no-such-sub (:operation ev)) ev))
                      @traces)]
        (is (some? hit) "no-such-sub trace fired")
        (when hit
          (let [tags (:tags hit)]
            ;; Canonical Spec-009 shape.
            (is (= :missing/sub (:rf.sub/id tags))
                ":rf.sub/id is the unregistered sub's id")
            (is (= [:missing/sub] (:unresolved-input tags))
                ":unresolved-input is the full query-vector that failed to resolve")
            (is (= [] (:resolved-inputs tags))
                ":resolved-inputs is empty — the miss is detected before input resolution")
            (is (contains? tags :frame) ":frame tag retained for routing")
            ;; The pre-fix shape MUST be gone.
            (is (not (contains? tags :rf.sub/query-v))
                "the pre-fix :rf.sub/query-v tag is gone (aligned to Spec 009)")))))))

;; ---- ref-counting and hot-reload smoke (relocated from smoke_test.clj, rf2-zqar3) ----

(deftest sub-cache-ref-counting
  (testing "subscribe / unsubscribe pair tracks ref-count and disposes on zero"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [cache (:sub-cache (frame/frame :rf/default))]
      ;; Two subscriptions to the same query share a single cache slot.
      (let [r1 (rf/subscribe [:n])
            r2 (rf/subscribe [:n])]
        (is (identical? r1 r2) "cache hit returns the same reaction")
        (is (contains? @cache [:n]))
        (is (= 2 (get-in @cache [[:n] :ref-count]))))
      ;; First unsubscribe drops to 1, slot still present.
      (rf/unsubscribe [:n])
      (is (contains? @cache [:n]))
      (is (= 1 (get-in @cache [[:n] :ref-count])))
      ;; Second unsubscribe drops to 0; slot is evicted synchronously.
      (rf/unsubscribe [:n])
      (is (not (contains? @cache [:n]))
          "cache slot is removed when ref-count reaches zero"))))

(deftest sub-hot-reload-invalidates-cache
  (testing "re-registering a :sub disposes cached reactions and emits a trace"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/dispatch-sync [:seed])
    ;; v1 of :answer returns the value as-is.
    (rf/reg-sub :answer (fn [db _] (:n db)))
    (is (= 7 (rf/subscribe-once [:answer])))
    ;; Force the cache to retain the entry by holding a ref via subscribe.
    (let [_pinned (rf/subscribe [:answer])
          traces  (atom [])]
      (rf/register-listener! :trace ::hot-reload (fn [ev] (swap! traces conj ev)))
      ;; Re-register with a transformed body.
      (rf/reg-sub :answer (fn [db _] (* 10 (:n db))))
      (rf/unregister-listener! :trace ::hot-reload)
      ;; After re-registration, the next subscribe-once sees the new fn.
      (is (= 70 (rf/subscribe-once [:answer]))
          "after re-registration the new sub body is used")
      (is (some (fn [ev]
                  (and (= :rf.registry/handler-replaced (:operation ev))
                       (= :rf.registry (:op-type ev))
                       (= :sub (:kind (:tags ev)))
                       (= :answer (:id (:tags ev)))))
                @traces)
          "expected :rf.registry/handler-replaced trace"))))

;; ===========================================================================
;; rf2-jue6sp (EP-0002 §Subscriptions And Read Helpers) — ambient read
;; surfaces require a carried frame; absence is :rf.error/no-frame-context,
;; NOT a silent read of an invented :rf/default.
;;
;; The reset-runtime fixture pins `with-frame :rf/default` for the whole
;; body, so these absence tests explicitly UNWIND that scope by rebinding
;; `*current-frame*` to nil — modelling a read issued with no established
;; scope and no carried stamp (the async-callback / tool / SSR case).
;; ===========================================================================

(defn- no-frame-context-ex?
  "True when `thrown` is the EP-0002 absence error."
  [thrown]
  (and (some? thrown)
       (= :rf.error/no-frame-context (:rf.error/id (ex-data thrown)))))

(deftest subscribe-frameless-read-raises-no-frame-context
  (testing "1-arity subscribe / subscribe-once / unsubscribe / clear-sub-cache!
            under NO established scope raise :rf.error/no-frame-context
            instead of falling through to :rf/default (rf2-jue6sp)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    ;; Unwind the fixture's with-frame :rf/default scope → genuinely no
    ;; carried stamp and no React-context provider (JVM).
    (binding [frame/*current-frame* nil]
      (let [e (try (rf/subscribe [:n]) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (no-frame-context-ex? e)
            "1-arity subscribe outside any scope raises :rf.error/no-frame-context")
        (is (= :subscribe (:operation (ex-data e)))
            ":operation attributes the failure to subscribe")
        (is (= :n (:event-id (ex-data e)))
            ":event-id carries the attempted sub-id")
        (is (= :supply-frame (:recovery (ex-data e)))
            ":recovery points the caller at supplying a frame"))
      (is (no-frame-context-ex?
            (try (rf/subscribe-once [:n]) nil
                 (catch clojure.lang.ExceptionInfo e e)))
          "1-arity subscribe-once outside any scope raises")
      (is (no-frame-context-ex?
            (try (rf/unsubscribe [:n]) nil
                 (catch clojure.lang.ExceptionInfo e e)))
          "1-arity unsubscribe outside any scope raises")
      (is (no-frame-context-ex?
            (try (rf/clear-sub-cache!) nil
                 (catch clojure.lang.ExceptionInfo e e)))
          "zero-arity clear-sub-cache! outside any scope raises"))))

(deftest subscribe-under-with-frame-resolves-the-scope
  (testing "1-arity subscribe under with-frame routes to the scope's frame —
            the ambient form still works INSIDE a real scope (rf2-jue6sp)"
    (rf/reg-frame :jue/scoped {:doc "explicit non-default scope"})
    (rf/reg-event :seed (fn [{:keys [db]} [_ v]] {:db {:v v}}))
    (rf/reg-sub :v (fn [db _] (:v db)))
    (rf/dispatch-sync [:seed :scoped-value] {:frame :jue/scoped})
    ;; Unwind the fixture scope first, then establish :jue/scoped.
    (binding [frame/*current-frame* nil]
      (rf/with-frame :jue/scoped
        (is (= :scoped-value @(rf/subscribe [:v]))
            "ambient subscribe resolves the with-frame scope, not :rf/default")))))

(deftest subscribe-wrong-frame-prevention-for-reads
  (testing "ambient reads land on the ESTABLISHED scope's frame, never bleed
            into a sibling frame — the carried-invariant read isolation
            (rf2-jue6sp; wrong-frame prevention for READS as well as writes)"
    (rf/reg-frame :jue/left  {:doc "left frame"})
    (rf/reg-frame :jue/right {:doc "right frame"})
    (rf/reg-event :seed (fn [{:keys [db]} [_ v]] {:db {:v v}}))
    (rf/reg-sub :v (fn [db _] (:v db)))
    (rf/dispatch-sync [:seed :left-value]  {:frame :jue/left})
    (rf/dispatch-sync [:seed :right-value] {:frame :jue/right})
    (binding [frame/*current-frame* nil]
      ;; Reading under the :jue/left scope sees ONLY :jue/left's app-db.
      (rf/with-frame :jue/left
        (is (= :left-value @(rf/subscribe [:v]))
            "ambient read under :jue/left resolves :jue/left")
        (is (not= :right-value @(rf/subscribe [:v]))
            "the read did NOT bleed into the sibling :jue/right frame"))
      ;; And under :jue/right it sees ONLY :jue/right's app-db.
      (rf/with-frame :jue/right
        (is (= :right-value @(rf/subscribe [:v]))
            "ambient read under :jue/right resolves :jue/right")))))

;; ===========================================================================
;; rf2-ts3fuk — unsubscribe frame-target normalization SYMMETRY
;; ===========================================================================
;;
;; EP-0023 (rf2-32siq3.32) made the 2-arity SUBSCRIBE target accept either a
;; frame-id KEYWORD or a live frame OBJECT (`rf/make-frame`'s return value),
;; normalizing an object to its runnable-id ADDRESS via
;; `frame/frame-target->id` before keying the sub-cache. UNSUBSCRIBE (and the
;; `subscribe-once` teardown that delegates to it) did NOT route its target
;; through the same normalizer: it called `(frame/frame frame-id)` on the raw
;; target. A frame OBJECT (a map) is not a key in `frame/frames`, so
;; `(frame/frame <object>)` returned nil, `unsubscribe` short-circuited at its
;; `when-let`, and the live cache entry SUBSCRIBE created (keyed by the
;; runnable-id) was never torn down — an asymmetric-targeting ref-count leak.
;;
;; The fix normalizes unsubscribe's target through the SAME
;; `frame/frame-target->id` path, so a subscribe-then-unsubscribe pair targets
;; the same frame for EVERY supported spelling (object OR keyword, in either
;; position). These tests subscribe in a frame and unsubscribe with an
;; equivalent-but-differently-spelled target, asserting the entry IS evicted.

(defn- object-cache-keys
  "The set of query-vectors currently cached in the runnable record backing
  frame OBJECT `frame-obj` (keyed by its runnable-id ADDRESS)."
  [frame-obj]
  (let [rid (frame/frame-target->id frame-obj)]
    (set (keys @(:sub-cache (frame/frame rid))))))

(defn- make-n-frame
  "A live frame OBJECT running an INLINE image that registers the layer-1 `:n`
  sub (reading `(:n db)`) plus a local `:ts3fuk/seed` setup event, seeded with
  `seed-db`. The inline image keeps the frame off the default whole-store
  projection (which would collide with the framework standards in this fixture),
  so the frame is self-contained. EP-0027: app-db seeding is a setup EVENT — the
  image registers a local `:ts3fuk/seed` ({:db new-db}) and the seed rides
  `:initial-events`. (`:rf/set-db` — the framework-standard seed — lands in a
  parallel image-registry bead; an inline-image local seed exercises the same
  `:initial-events` path without that dependency.) `opts` may carry `:id` and
  `:seed-db`."
  [{:keys [seed-db] :as opts}]
  (live-frame/make-frame
    (merge {:images [(rf/image {:id :ts3fuk/img
                                :registrations
                                {:reg-sub   [[:n {:doc "n"} (fn [db _] (:n db))]]
                                 :reg-event [[:ts3fuk/seed {:doc "seed"} (fn [_ [_ new-db]] {:db new-db})]]}})]
            :initial-events (when (some? seed-db) [[:ts3fuk/seed seed-db]])}
           (dissoc opts :seed-db))))

(deftest unsubscribe-object-target-tears-down-the-entry
  (testing "subscribe with a frame OBJECT then unsubscribe with the SAME object
            evicts the cache entry — the object target normalizes through the
            same path on BOTH operations (rf2-ts3fuk). A no-id make-frame object
            carries a gensym runnable-id, so the object map and its address are
            genuinely different spellings of the same frame."
    ;; No-id (direct) frame object — its runnable-id is a gensym, distinct from
    ;; the object map. Seed app-db so the read resolves.
    (let [frame-obj (make-n-frame {:seed-db {:n 7}})]
      ;; Subscribe via the OBJECT target → keyed by the runnable-id.
      (let [r (rf/subscribe [:n] {:frame frame-obj})]
        (is (= 7 @r) "object-target subscribe reads the frame's seeded app-db")
        (is (contains? (object-cache-keys frame-obj) [:n])
            "the entry is cached in the frame's runnable record"))
      ;; Unsubscribe via the SAME OBJECT target. Pre-fix this hit
      ;; (frame/frame <object>) → nil and was a silent no-op, leaking the entry.
      (rf/unsubscribe frame-obj [:n])
      (is (not (contains? (object-cache-keys frame-obj) [:n]))
          "the entry is torn down — unsubscribe normalized the object target
           symmetrically with subscribe (the rf2-ts3fuk fix)")
      (rf/destroy-frame! frame-obj))))

(deftest unsubscribe-mixed-spelling-targets-are-symmetric
  (testing "subscribe and unsubscribe accept the SAME frame in different
            spellings (object vs. runnable-id keyword) interchangeably — the
            entry is evicted whichever spelling teardown uses (rf2-ts3fuk)"
    (testing "subscribe by KEYWORD id, unsubscribe by the equivalent OBJECT"
      (let [frame-obj (make-n-frame {:id :ts3fuk/a :seed-db {:n 1}})
            rid       (frame/frame-target->id frame-obj)]
        (is (= :ts3fuk/a rid) "an :id-bearing object's runnable-id IS the public id")
        (rf/subscribe [:n] {:frame rid})
        (is (contains? (object-cache-keys frame-obj) [:n]))
        ;; Differently-spelled teardown target (the object, not the keyword).
        (rf/unsubscribe frame-obj [:n])
        (is (not (contains? (object-cache-keys frame-obj) [:n]))
            "object-target unsubscribe evicts the entry the keyword subscribe made")
        (rf/destroy-frame! frame-obj)))
    (testing "subscribe by OBJECT, unsubscribe by the equivalent runnable-id KEYWORD"
      (let [frame-obj (make-n-frame {:id :ts3fuk/b :seed-db {:n 2}})
            rid       (frame/frame-target->id frame-obj)]
        (rf/subscribe [:n] {:frame frame-obj})
        (is (contains? (object-cache-keys frame-obj) [:n]))
        ;; Differently-spelled teardown target (the keyword, not the object).
        (rf/unsubscribe rid [:n])
        (is (not (contains? (object-cache-keys frame-obj) [:n]))
            "keyword-target unsubscribe evicts the entry the object subscribe made")
        (rf/destroy-frame! frame-obj)))))

(deftest subscribe-once-object-target-disposes-synchronously
  (testing "subscribe-once with a frame OBJECT target leaves NO live cache entry
            — its internal teardown unsubscribe now normalizes the object target
            symmetrically, so the one-shot read's slot is disposed in-tick
            instead of leaking (rf2-ts3fuk)"
    (let [frame-obj (make-n-frame {:seed-db {:n 9}})]
      (is (= 9 (rf/subscribe-once [:n] {:frame frame-obj}))
          "object-target subscribe-once returns the seeded value")
      (is (not (contains? (object-cache-keys frame-obj) [:n]))
          "no orphaned entry — subscribe-once's object-target teardown evicted it")
      (rf/destroy-frame! frame-obj))))
