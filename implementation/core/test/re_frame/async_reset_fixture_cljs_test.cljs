(ns re-frame.async-reset-fixture-cljs-test
  "Contract tests for the async map-form of `make-reset-runtime-fixture`
  (`{:async? true}`), per Spec 008 §Test-support.

  ## Why the async map-form exists

  The blessed reset fixture is fn-form by default — `(fn [test-fn] …)` — and it
  establishes the body's ambient frame scope with a dynamic `binding` around
  `(test-fn)`. That is correct for a SYNCHRONOUS test (the whole body runs
  inside the binding) but structurally wrong for an `(async done …)` test: the
  test fn returns immediately and the body resumes later, on a fresh tick, by
  which point the binding has unwound. `cljs.test` enforces this by HARD-
  ERRORING on a fn-form fixture for an async test (\"Async tests require
  fixtures to be specified as maps\").

  The `:async? true` shape returns a `{:before :after}` map instead. `:before`
  runs the same reset AND establishes the ambient scope with a PERSISTENT
  `set!` of `re-frame.frame/*current-frame*` (not a binding) — so a BARE
  `dispatch-sync` (no explicit `{:frame …}`) in the async body resolves
  `:rf/default` and drains across the async boundary. `:after` tears the scope
  down and restores the baselines after the test's `done`.

  These tests prove: (1) a bare dispatch-sync drains in an async body; (2) the
  map fixture also serves synchronous bodies; (3) the two return shapes; (4) the
  fn-form still drains a bare dispatch-sync; (5) the fn/map mixing hazard the
  async `:before`'s per-test re-ensure guards against."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; The whole ns runs under the async map-form fixture (plain-atom, default
;; `:rf/default` ambient frame). A sync test under a map fixture is allowed;
;; an async test REQUIRES one.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :async?  true}))

;; ---- 1. the headline: a bare dispatch-sync drains in an async body --------

(deftest bare-dispatch-sync-drains-in-async-body
  (testing "a BARE dispatch-sync inside an (async) body drains + lands — the persistent ambient scope survives the async boundary"
    (async done
      (rf/reg-event :ar/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (js/setTimeout
        (fn []
          ;; This callback runs on a FRESH tick: a dynamic binding established
          ;; in :before would be long gone. The set! ambient scope persists, so
          ;; these bare dispatch-syncs (no {:frame …}) resolve :rf/default and
          ;; drain — the exact thing a naive map fixture silently fails.
          (rf/dispatch-sync [:ar/inc])
          (rf/dispatch-sync [:ar/inc])
          (is (= 2 (:n (rf/app-db-value :rf/default)))
              "two bare dispatch-syncs landed in :rf/default app-db across the async boundary")
          (done))
        0))))

(deftest bare-dispatch-sync-drains-after-promise-await
  (testing "the persistent scope also survives a Promise microtask hop"
    (async done
      (rf/reg-event :ar/set (fn [_ [_ v]] {:db {:n v}}))
      (-> (js/Promise.resolve 41)
          (.then (fn [v]
                   (rf/dispatch-sync [:ar/set (inc v)])
                   (is (= 42 (:n (rf/app-db-value :rf/default)))
                       "bare dispatch-sync landed inside a .then continuation")
                   (done)))))))

;; ---- 2. the map fixture also serves a synchronous body --------------------

(deftest bare-dispatch-sync-lands-synchronously-under-map-fixture
  (testing "the map fixture serves a synchronous body too: a bare dispatch-sync lands"
    (rf/reg-event :ar/set (fn [_ [_ v]] {:db {:n v}}))
    (rf/dispatch-sync [:ar/set 7])
    (is (= 7 (:n (rf/app-db-value :rf/default)))
        "a synchronous bare dispatch-sync landed under the :async? map fixture")))

;; ---- 3. the two return shapes ---------------------------------------------

(deftest fixture-return-shapes
  (testing ":async? false → a fn-form fixture; :async? true → a {:before :after} map"
    (is (fn? (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))
        "the default is the synchronous fn-form fixture")
    (let [m (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                                      :async?  true})]
      (is (map? m) ":async? true returns a cljs.test map fixture")
      (is (fn? (:before m)) "…with a zero-arg :before")
      (is (fn? (:after m))  "…and a zero-arg :after"))))

;; ---- 4. the fn-form still drains a bare dispatch-sync ---------------------
;;
;; Belt-and-braces alongside the whole existing suite (dozens of fn-form
;; users): drive a fn-form fixture MANUALLY (not via use-fixtures) and prove a
;; bare dispatch-sync inside its synchronous body still drains.

(deftest fn-form-still-drains-a-bare-dispatch-sync
  (testing "the unchanged fn-form fixture establishes an ambient scope for a sync body"
    (let [fix (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})]
      (fix (fn []
             (rf/reg-event :ar/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
             (rf/dispatch-sync [:ar/inc])
             (rf/dispatch-sync [:ar/inc])
             (rf/dispatch-sync [:ar/inc])
             (is (= 3 (:n (rf/app-db-value :rf/default)))
                 "three bare dispatch-syncs landed under the fn-form's binding scope"))))))

;; ---- 5. the fn/map mixing teardown hazard ---------------------------------
;;
;; A fn-form fixture's teardown resets frames to {}. In a shared cljs.test
;; bundle that destroys :rf/default for whatever ns runs next. The :async?
;; fixture is robust to this because its :before re-installs the adapter and
;; re-ensures :rf/default EVERY test (rather than trusting a sibling-left
;; frame). This test reproduces the hazard concretely: after a manually-driven
;; fn-form fixture, :rf/default is gone, and a bare dispatch-sync against the
;; missing frame silently no-ops — the exact silent non-drain the re-ensure
;; guards against.

(deftest fn-map-mixing-teardown-hazard
  (testing "fn-form teardown clears :rf/default; a bare dispatch-sync against the gone frame silently no-ops"
    (let [fix (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})]
      (fix (fn []
             (rf/reg-event :ar/seed (fn [_ _] {:db {:n 1}}))
             (rf/dispatch-sync [:ar/seed])
             (is (some? (frame/frame :rf/default))
                 "inside the fn-form: the :rf/default frame is live")
             (is (= 1 (:n (rf/app-db-value :rf/default)))
                 "inside the fn-form: the bare dispatch-sync drained"))))
    ;; The fn-form fixture's `finally` reset frames to {} — :rf/default is gone.
    (is (nil? (frame/frame :rf/default))
        "fn-form teardown cleared the frames registry — :rf/default destroyed (the mixing hazard)")
    ;; *current-frame* is still :rf/default (this ns's :async? ambient scope),
    ;; so a bare dispatch-sync RESOLVES :rf/default but finds NO frame record:
    ;; it silently no-ops and never synthesises a frame (no :rf/default floor).
    (rf/reg-event :ar/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:ar/inc])
    (is (nil? (frame/frame :rf/default))
        "the bare dispatch-sync did NOT drain or synthesise a frame — the silent non-drain a naive map fixture leaves, and the :async? :before re-ensure prevents")))
