(ns re-frame.write-after-destroy-cljs-test
  "Integration-tier pin for the rf2-ft2b defense-in-depth nil-container
  guard, exercised under the Reagent adapter (rf2-9od6t).

  The guard sits at `re-frame.substrate.adapter/replace-container!` —
  every frame app-db write flows through that single choke point, so a
  scheduled drain that races frame destruction (router :db commit, flow
  recompute, epoch restore, SSR write …) cannot NPE on a background
  thread once its frame has been torn down. Instead the call no-ops and
  fires `:rf.warning/write-after-destroy` with `:recovery :no-recovery`.

  Unit-level coverage of that contract lives at
  `re-frame.frame-lifecycle-test/replace-container-no-ops-on-nil-container`
  and `.../replace-container-on-destroyed-frame-does-not-npe` in the core
  artefact. Those run against the plain-atom JVM adapter. The original
  rf2-ft2b bug was reported against the Reagent integration path, so
  this ns re-pins the contract with the Reagent adapter installed —
  proving substrate-agnosticism (the guard sits ABOVE the adapter's
  `:replace-container!` slot, so it is not routed through the adapter,
  but the integration shape must still hold under every adapter).

  Two scenarios:

    1. `replace-container! nil` directly — the smallest reproducer.
    2. Live frame, destroyed, read its container (now nil per
       `frame/get-frame-db` on a destroyed frame), then attempt the
       write — the exact shape router.cljc's per-event :db commit
       traces when racing destroy.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(defn- collect-traces! []
  (let [traces (atom [])
        cb-id  (keyword (gensym "rf-wad-cb-"))]
    (trace-tooling/register-trace-listener! cb-id (fn [ev] (swap! traces conj ev)))
    {:traces traces
     :stop!  (fn [] (trace-tooling/unregister-trace-listener! cb-id))}))

(defn- write-after-destroy-warnings [traces]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= :rf.warning/write-after-destroy (:operation ev))))
           traces))

;; ---- 1. direct nil-container call -----------------------------------------

(deftest reagent-replace-container-no-ops-on-nil-container
  (testing "Under the Reagent adapter, replace-container! with a nil
            container is a documented no-op + :rf.warning/write-after-destroy
            (rf2-ft2b, rf2-9od6t)"
    (let [{:keys [traces stop!]} (collect-traces!)]
      (try
        (is (nil? (adapter/replace-container! nil {:any :value}))
            "nil container must NOT throw (background-thread NPE was the original bug)")
        (let [warns (write-after-destroy-warnings @traces)]
          (is (= 1 (count warns))
              "exactly one :rf.warning/write-after-destroy fires per nil-write")
          (is (= :no-recovery (:recovery (first warns)))
              "warning carries :recovery :no-recovery per the rf2-ft2b contract"))
        (finally (stop!))))))

;; ---- 2. live-destroy → captured-container-write ---------------------------

(deftest reagent-replace-container-on-destroyed-frame-does-not-npe
  (testing "frame/get-frame-db on a destroyed frame returns nil; feeding
            that nil into replace-container! must no-op + warn (rf2-9od6t).
            This is the exact shape router.cljc's per-event :db commit
            takes when a scheduled drain reaches the write AFTER destroy."
    (let [frame-id :rf-9od6t/race
          {:keys [traces stop!]} (collect-traces!)]
      (try
        (rf/reg-frame frame-id {:doc "rf2-9od6t race reproducer"})
        (rf/destroy-frame! frame-id)
        (let [container (frame/get-frame-db frame-id)]
          (is (nil? container)
              "get-frame-db on a destroyed frame returns nil — the rf2-ft2b precondition")
          (is (nil? (adapter/replace-container! container {:would :have :npe'd true}))
              "writing through the nil container is a documented no-op"))
        (let [warns (write-after-destroy-warnings @traces)]
          (is (pos? (count warns))
              ":rf.warning/write-after-destroy fired for the post-destroy write")
          (is (every? #(= :no-recovery (:recovery %)) warns)
              "every fired warning carries :recovery :no-recovery"))
        (finally (stop!))))))
