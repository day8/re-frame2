(ns re-frame.adapter.ssr-emitter-replay-atomic-cljs-test
  "rf2-h9szm — the `install-adapter!` SSR-emitter replay is FAILURE-ATOMIC,
  ROUTED, and PRECEDENCE-SAFE.

  PR #6028 (rf2-vxgfnd.204) added install-time replay of the retained SSR
  hiccup emitter, but the lifecycle transaction was neither failure-atomic nor
  cleanly routed:

    * `install-adapter!` SEATED the new generation, THEN called
      `rearm-hiccup-emitter!`, whose `:reagent/set-hiccup-emitter!` BROADCAST
      re-armed every loaded adapter. A throwing setter (for the active adapter,
      OR any loaded inactive adapter) propagated out of the broadcast and made
      the install throw AFTER the target generation was already seated — a
      failed boot that nonetheless left the process installed / partial-armed.
    * The broadcast re-armed the retained default over an already-armed slot,
      silently clobbering a pre-init explicit custom emitter / reset.

  The fix makes seat + re-arm ONE failure-atomic transaction (a throwing re-arm
  rolls the exact generation back and rethrows the re-arm exception as primary,
  leaving a clean never-installed state for an immediate retry), routes the
  re-arm through the `:adapter/arm-hiccup-emitter-if-unarmed!` hook (installed
  adapter ALONE, so an inactive adapter's throwing setter cannot break the
  active boot), and arms only an otherwise-unarmed slot (explicit override
  wins).

  Substrate-agnostic (JVM + the :node-test CLJS gate, via .cljc). It injects the
  durable emitter slot and the arm hook directly, so it pins the
  `install-adapter!` lifecycle seam independently of any one substrate's real
  emitter wiring; the real-adapter end-to-end coverage (custom override survives
  a real ui-adapter install) lives in
  the retired compiled tier's own lifecycle suite."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.adapter :as rf.substrate.adapter]))

;; ---- fixture --------------------------------------------------------------
;; These tests overwrite the durable SSR-emitter slot and the two emitter hooks
;; to drive the `install-adapter!` transaction under fault injection. Snapshot
;; and restore them (and the adapter lifecycle slot) so a real adapter's
;; publications elsewhere in the bundle survive.

(def ^:private touched-hooks
  [:ssr/current-hiccup-emitter
   :adapter/arm-hiccup-emitter-if-unarmed!
   :reagent/set-hiccup-emitter!])

(defn- restore-hook! [k orig]
  (if (some? orig)
    (rf.late-bind/set-fn! k orig)
    (do (swap! rf.late-bind/hooks dissoc k)
        (rf.late-bind/invalidate-cache! k))))

(defn- isolate-emitter-hooks [test-fn]
  (let [saved (into {} (map (fn [k] [k (rf.late-bind/get-fn k)])) touched-hooks)]
    (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
    (try
      (test-fn)
      (finally
        (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
        (doseq [k touched-hooks]
          (restore-hook! k (get saved k)))))))

(use-fixtures :each isolate-emitter-hooks)

(def ^:private fake-adapter {:kind :rf.test/atomic-adapter})

(defn- err-id [e]
  #?(:clj  (:rf.error/id (ex-data e))
     :cljs (:rf.error/id (ex-data e))))

;; ---- 1. failure-atomicity: throwing re-arm cannot leave a partial boot ----

(deftest a-throwing-replay-rolls-the-install-back-atomically
  ;; Durable emitter present + the (active) arm throws → install is a no-op on
  ;; the process slot: it rethrows the arm exception as PRIMARY and leaves no
  ;; generation seated. Before rf2-h9szm the generation stayed seated and
  ;; `current-adapter` reported the "failed" install as installed.
  (rf.late-bind/set-fn! :ssr/current-hiccup-emitter (fn [_ _] "<html/>"))
  (rf.late-bind/set-fn! :adapter/arm-hiccup-emitter-if-unarmed!
                     (fn [_] (throw (ex-info "replay boom" {:marker ::boom}))))

  (let [thrown (atom nil)]
    (try
      (rf.substrate.adapter/install-adapter! fake-adapter)
      (catch #?(:clj Throwable :cljs :default) e
        (reset! thrown e)))

    (testing "the re-arm exception is preserved as the primary throw"
      (is (some? @thrown) "install-adapter! rethrew rather than swallowing")
      (is (= ::boom (:marker (ex-data @thrown)))
          "the ORIGINAL re-arm exception surfaced, not a masking rollback error"))

    (testing "no generation is seated — the failed boot is not installed"
      (is (nil? (rf.substrate.adapter/current-adapter))
          "current-adapter is nil: the seated generation was rolled back")
      (is (nil? (rf.substrate.adapter/current-adapter-spec)))
      (is (false? (rf.substrate.adapter/adapter-disposed?))
          "a failed install disposed nothing — the never-installed diagnosis stands"))

    (testing "delegation surfaces the never-installed throw, not a half-armed state"
      (let [de (try (rf.substrate.adapter/render-to-string [:div] {}) nil
                    (catch #?(:clj Throwable :cljs :default) e e))]
        (is (= :rf.error/no-adapter-installed (err-id de)))))

    (testing "an immediate clean retry installs fresh"
      (rf.late-bind/set-fn! :adapter/arm-hiccup-emitter-if-unarmed! (fn [_] nil))
      (is (= fake-adapter (rf.substrate.adapter/install-adapter! fake-adapter)))
      (is (= :rf.test/atomic-adapter (rf.substrate.adapter/current-adapter))
          "the rollback left a clean slot, so the retry seats normally"))))

(deftest exact-generation-rollback-does-not-erase-a-replacement
  ;; The rollback is bounded to the EXACT generation the failing install seated.
  ;; Simulate a re-entrant install that lands a REPLACEMENT generation from
  ;; inside the throwing arm (the arm runs while the failing generation is
  ;; seated); the failing install's rollback must NOT clear the replacement.
  (rf.late-bind/set-fn! :ssr/current-hiccup-emitter (fn [_ _] "<html/>"))
  (let [replacement {:kind :rf.test/replacement-adapter}]
    (rf.late-bind/set-fn! :adapter/arm-hiccup-emitter-if-unarmed!
                       (fn [_]
                         ;; Land a DIFFERENT generation, then fail the outer install.
                         (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
                         (rf.late-bind/set-fn! :adapter/arm-hiccup-emitter-if-unarmed! (fn [_] nil))
                         (rf.substrate.adapter/install-adapter! replacement)
                         (throw (ex-info "outer boom" {}))))
    (is (thrown? #?(:clj Throwable :cljs :default)
                 (rf.substrate.adapter/install-adapter! fake-adapter)))
    (is (identical? replacement (rf.substrate.adapter/current-adapter-spec))
        "the replacement generation survived — the stale install's rollback is exact-generation-scoped")))

;; ---- 2. routing: an inactive throwing setter cannot break the active boot -

(deftest inactive-throwing-broadcast-setter-cannot-break-the-active-boot
  ;; The replay routes through `:adapter/arm-hiccup-emitter-if-unarmed!`, NOT the
  ;; `:reagent/set-hiccup-emitter!` broadcast a loaded inactive adapter also
  ;; contributes to. So a throwing broadcast setter never runs during install and
  ;; the active adapter boots cleanly. Before rf2-h9szm the replay used the
  ;; broadcast and this throw broke the install.
  (rf.late-bind/set-fn! :ssr/current-hiccup-emitter (fn [_ _] "<html/>"))
  (let [broadcast-ran (atom false)
        arm-ran       (atom false)]
    ;; Stand in for a loaded inactive adapter whose set-hiccup-emitter! throws.
    (rf.late-bind/set-fn! :reagent/set-hiccup-emitter!
                       (fn [_] (reset! broadcast-ran true) (throw (ex-info "inactive setter boom" {}))))
    ;; The installed adapter's routed arm is benign.
    (rf.late-bind/set-fn! :adapter/arm-hiccup-emitter-if-unarmed!
                       (fn [_] (reset! arm-ran true)))

    (is (= fake-adapter (rf.substrate.adapter/install-adapter! fake-adapter))
        "install succeeds — the throwing broadcast setter is not on the replay path")
    (is (= :rf.test/atomic-adapter (rf.substrate.adapter/current-adapter)))
    (is (true? @arm-ran) "the routed install-replay arm ran")
    (is (false? @broadcast-ran)
        "the :reagent/set-hiccup-emitter! broadcast was NOT invoked by install-replay")))

;; ---- 3. precedence: replay arms only an otherwise-unarmed slot ------------

(deftest replay-arms-an-unarmed-slot-but-never-overwrites-an-armed-one
  (rf.late-bind/set-fn! :ssr/current-hiccup-emitter ::retained-default)
  (let [slot (atom nil)]
    ;; The arm hook mirrors the real spine impl: arm the slot ONLY when unarmed.
    (rf.late-bind/set-fn! :adapter/arm-hiccup-emitter-if-unarmed!
                       (fn [f] (when (nil? @slot) (reset! slot f))))

    (testing "an otherwise-unarmed fresh generation receives the retained default"
      (is (= fake-adapter (rf.substrate.adapter/install-adapter! fake-adapter)))
      (is (= ::retained-default @slot)
          "the freshly installed, unarmed slot was armed from the durable emitter"))

    (testing "an explicit pre-init override is NOT clobbered by the replay"
      (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
      (reset! slot ::explicit-custom)          ;; app set a custom emitter pre-init
      (is (= fake-adapter (rf.substrate.adapter/install-adapter! fake-adapter)))
      (is (= ::explicit-custom @slot)
          "install-replay left the explicit override authoritative — the default did not win"))))
