(ns re-frame.owned-frame-lifecycle-cljs-test
  "Node-runnable unit tests for the substrate-agnostic core of the
  UI-OWNED frame-provider (EP-0024) and the fail-loud guards shared by
  the `frame-provider` name family — `re-frame.views.owned-frame`.

  These exercise the parts that need NO React mount: the
  create-on-mount / deferred-cancellable-destroy registry
  (`acquire-owned-frame!` / `release-owned-frame!` / `pending-destroy?`)
  and the two fail-loud guards (`require-owned-frame-id!` for the OWNED
  provider, `reject-lifecycle-opts!` for the SCOPE-only
  `frame-provider-existing`). The React function component (`owned-frame-fc`)
  + the StrictMode double-invoke behaviour are exercised under a real DOM in
  the adapter DOM-test target; this suite locks the lifecycle ALGEBRA the
  component drives."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.views.owned-frame :as owned-frame]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- create-on-mount + idempotent re-mount --------------------------------

(deftest acquire-creates-and-returns-id
  (testing "acquire-owned-frame! runs make-frame and returns the resolved id"
    (let [id (owned-frame/acquire-owned-frame! {:id :owned/alpha :initial-events [[:rf/set-db {:n 1}]]})]
      (is (= :owned/alpha id) "returns the frame id off the constructed frame value")
      (is (some? (frame/frame :owned/alpha)) "the frame is live in the registry")
      (is (= {:n 1} (rf/app-db-value :owned/alpha)) ":rf/set-db seeded app-db"))))

(deftest re-acquire-is-idempotent-and-preserves-durable-state
  (testing "re-acquiring the same id preserves durable app-db (EP-0024 idempotent replacement)"
    (owned-frame/acquire-owned-frame! {:id :owned/beta :initial-events [[:rf/set-db {:count 0}]]})
    ;; Mutate durable state through a dispatch path.
    (rf/reg-event :owned/bump (fn [{:keys [db]} _] {:db (update db :count inc)}))
    (rf/dispatch-sync [:owned/bump] {:frame :owned/beta})
    (is (= {:count 1} (rf/app-db-value :owned/beta)) "durable state advanced")
    ;; A re-acquire under the SAME id (the hot-reload / StrictMode remount
    ;; shape) must NOT reset durable state.
    (let [id2 (owned-frame/acquire-owned-frame! {:id :owned/beta :initial-events [[:rf/set-db {:count 99}]]})]
      (is (= :owned/beta id2))
      (is (= {:count 1} (rf/app-db-value :owned/beta))
          "re-acquire preserved durable state (idempotent replacement, not reset)"))))

(deftest acquire-setup-escaping-throw-rethrows-and-leaves-no-frame
  (testing "rf2-83fwld: an owned-provider acquire whose :initial-events step
            THROWS out of dispatch-sync rethrows out of acquire-owned-frame! AND
            leaves NO frame registered (the EP frame-provider acceptance
            criterion: a setup throw destroys the just-created frame, then
            rethrows). Transitively: acquire -> make-frame -> reg-frame ->
            run-setup-events! tears down the partial frame and rethrows
            :rf.error/initial-events-step-failed, which propagates through
            acquire (the cancel-pending-destroy! after make-frame never runs).
            This is the ESCAPING-throw detection route."
    ;; A step that requires an UNREGISTERED cofx: cofx resolution throws OUT of
    ;; dispatch-sync (:rf.error/unregistered-cofx via throw-error!), so this is
    ;; the ESCAPING-throw case — run-setup-events! tears down the partial frame
    ;; (via its try/catch) and rethrows :rf.error/initial-events-step-failed.
    (rf/reg-event :owned/needs-missing-cofx
      {:rf.cofx/requires [:owned/unregistered-cofx]}
      (fn [{:keys [db]} _] {:db (assoc db :ran true)}))
    (let [thrown (atom nil)]
      (try
        (owned-frame/acquire-owned-frame!
          {:id :owned/boom
           :initial-events [[:rf/set-db {:seeded true}]
                            [:owned/needs-missing-cofx]]})
        (catch :default e (reset! thrown e)))
      (is (some? @thrown)
          "the setup throw RETHROWS out of acquire-owned-frame! (not swallowed)")
      (is (= :rf.error/initial-events-step-failed
             (:rf.error/id (ex-data @thrown)))
          "the rethrown error is the setup-step failure naming the throwing step")
      (is (nil? (frame/frame :owned/boom))
          "no half-created frame is left registered — the partial frame was torn down"))))

(deftest acquire-setup-in-band-handler-throw-rethrows-and-leaves-no-frame
  (testing "rf2-vw5h1r: the EP frame-provider guarantee — 'a throwing acquire
            destroys the just-created frame, then rethrows' — now holds for an
            IN-BAND handler-body throw too, not just an escaping throw. A
            [:rf/set-db :not-a-map] bad-arg step raises :rf.error/set-db-bad-value
            from inside the handler; the chain catches it (in-band
            :rf.error/handler-exception) and dispatch-sync returns nil NORMALLY.
            Under strict construction run-setup-events! detects that captured
            in-band failure, tears the partial frame down, and rethrows
            :rf.error/initial-events-step-failed — which propagates out of
            acquire-owned-frame!. Before the fix the frame was LEFT ALIVE and
            acquire returned the id with no signal (the owned-provider bug)."
    (let [thrown (atom nil)]
      (try
        (owned-frame/acquire-owned-frame!
          {:id :owned/setdb-boom
           :initial-events [[:rf/set-db {:seeded true}]
                            [:rf/set-db :not-a-map]]})
        (catch :default e (reset! thrown e)))
      (is (some? @thrown)
          "the in-band handler-body throw RETHROWS out of acquire-owned-frame!")
      (is (= :rf.error/initial-events-step-failed
             (:rf.error/id (ex-data @thrown)))
          "the rethrown error is the setup-step failure naming the failing step")
      (is (nil? (frame/frame :owned/setdb-boom))
          "no half-created frame is left registered — the partial frame was torn down
           (the bug left it ALIVE wrongly-seeded and acquire returned the id)"))))

;; ---- deferred + cancellable destroy (StrictMode tolerance) ----------------
;;
;; The registry ALGEBRA is tested synchronously here: release schedules a
;; DEFERRED destroy (the frame stays live in the same task), and a re-acquire
;; before the timer fires CANCELS it. The actual macrotask FIRE (and the real
;; React unmount → destroy timing) is exercised under a real DOM in the
;; adapter DOM-test target; node-side we lock that release is deferred (not
;; synchronous) and that a re-acquire cancels the pending destroy.

(deftest release-defers-destroy-not-synchronous
  (testing "release-owned-frame! schedules a DEFERRED destroy — frame stays live synchronously"
    (owned-frame/acquire-owned-frame! {:id :owned/gamma})
    (is (some? (frame/frame :owned/gamma)) "frame live before release")
    (owned-frame/release-owned-frame! :owned/gamma)
    (is (owned-frame/pending-destroy? :owned/gamma)
        "a destroy is scheduled (deferred), not run synchronously")
    (is (some? (frame/frame :owned/gamma))
        "frame still LIVE synchronously after release — destroy is deferred")
    ;; Tidy: cancel the pending destroy so the fixture's reset is clean (a
    ;; re-acquire is the cancel path).
    (owned-frame/acquire-owned-frame! {:id :owned/gamma})
    (is (not (owned-frame/pending-destroy? :owned/gamma)) "tidy: destroy cancelled")))

(deftest re-acquire-cancels-pending-destroy
  (testing "a re-acquire before the deferred destroy fires CANCELS it (StrictMode remount)"
    (owned-frame/acquire-owned-frame! {:id :owned/delta :initial-events [[:rf/set-db {:v :keep}]]})
    ;; Simulate the StrictMode unmount: schedule the deferred destroy ...
    (owned-frame/release-owned-frame! :owned/delta)
    (is (owned-frame/pending-destroy? :owned/delta) "destroy pending after release")
    ;; ... then the immediate remount re-acquires the SAME id, which must
    ;; cancel the pending destroy synchronously.
    (owned-frame/acquire-owned-frame! {:id :owned/delta})
    (is (not (owned-frame/pending-destroy? :owned/delta))
        "the re-acquire cancelled the pending destroy")
    (is (some? (frame/frame :owned/delta))
        "frame survives the would-be destroy window (StrictMode tolerant)")
    (is (= {:v :keep} (rf/app-db-value :owned/delta))
        "durable state preserved across the unmount→remount cycle")))

;; ---- fail-loud: OWNED provider needs a keyword :id ------------------------

(deftest require-owned-frame-id-fails-loud-on-missing-id
  (testing "the owned provider requires a keyword :id"
    (is (= :owned/ok (owned-frame/require-owned-frame-id! :owned/ok 'rf/frame-provider))
        "a keyword :id passes through unchanged")
    (is (thrown-with-msg? :default #":rf.error/owned-frame-provider-missing-id"
          (owned-frame/require-owned-frame-id! nil 'rf/frame-provider))
        "a missing/nil :id fails loud")
    (is (thrown-with-msg? :default #":rf.error/owned-frame-provider-missing-id"
          (owned-frame/require-owned-frame-id! "owned/str" 'rf/frame-provider))
        "a non-keyword :id fails loud")))

;; ---- fail-loud: SCOPE-only provider rejects lifecycle opts ----------------

(deftest reject-lifecycle-opts-passes-frame-only
  (testing "frame-provider-existing accepts a :frame-only prop map"
    (is (nil? (owned-frame/reject-lifecycle-opts!
                {:frame :scope/x}
                'rf/frame-provider-existing))
        "a clean {:frame …} map is accepted (returns nil)")))

(deftest reject-lifecycle-opts-fails-loud-on-construction-opts
  (testing "frame-provider-existing rejects each frame-construction / lifecycle opt"
    (doseq [bad [{:frame :scope/x :id :scope/x}
                 {:frame :scope/x :images []}
                 {:frame :scope/x :initial-events [[:rf/set-db {}]]}
                 ;; the retired construction keys are still rejected here (kept in
                 ;; lifecycle-opt-keys) so a stale caller fails loud, not silently:
                 {:frame :scope/x :initial-db {}}
                 {:frame :scope/x :on-create (fn [_])}]]
      (is (thrown-with-msg? :default #":rf.error/frame-provider-existing-lifecycle-opt"
            (owned-frame/reject-lifecycle-opts! bad 'rf/frame-provider-existing))
          (str "rejects lifecycle opt in " (pr-str (dissoc bad :frame)))))))

;; ---- end-to-end: the Reagent user-facing surfaces validate ----------------
;;
;; These don't mount (node has no DOM); they call the public Reagent
;; component fns directly to confirm the surface-level validation wiring
;; (the hiccup-emission level, like runtime_cljs_test does).

(deftest frame-provider-existing-rejects-lifecycle-opt-at-the-surface
  (testing "rf/frame-provider-existing fails loud when handed an owned-style :id"
    (is (thrown-with-msg? :default #":rf.error/frame-provider-existing-lifecycle-opt"
          (rf/frame-provider-existing {:frame :scope/y :id :scope/y} [:span]))
        "a lifecycle opt on the scope-only surface fails loud")))

(deftest frame-provider-existing-scopes-frame-only
  (testing "rf/frame-provider-existing with a clean :frame composes to a Provider"
    (let [tree (rf/frame-provider-existing {:frame :scope/z} [:span "child"])]
      (is (vector? tree) "produces a hiccup vector")
      (is (= :scope/z (second tree)) "the frame keyword threads through to the scope tier"))))
