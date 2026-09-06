(ns re-frame.write-after-destroy-cljs-test
  "Integration-tier pin for the rf2-ft2b defense-in-depth nil-container
  guard, exercised under the Reagent adapter (rf2-9od6t).

  The guard sits at `re-frame.substrate.adapter/replace-container!` —
  every frame app-db write flows through that single choke point, so a
  scheduled drain that races frame destruction (router :db commit, flow
  recompute, epoch restore, SSR write …) cannot NPE on a background
  thread once its frame has been torn down. Instead the call no-ops and
  fires the always-on `:rf.error/write-after-destroy` with
  `:recovery :ignored` (EP-0008 / rf2-500ech promoted this from the DCE'd
  `:rf.warning/write-after-destroy` onto the production-survivable axis —
  the same destroy-race the dispatch/subscribe paths already surfaced as
  `:rf.error/frame-destroyed`).

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
       `frame/app-db-container` on a destroyed frame), then attempt the
       write — the exact shape router.cljc's per-event :db commit
       traces when racing destroy.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(def ^:private write-after-destroy-pred
  (fn [ev]
    (and (= :error (:op-type ev))
         (= :rf.error/write-after-destroy (:operation ev)))))

;; ---- 1. direct nil-container call -----------------------------------------

(deftest reagent-replace-container-no-ops-on-nil-container
  (testing "Under the Reagent adapter, replace-container! with a nil
            container is a documented no-op + :rf.error/write-after-destroy
            (rf2-ft2b, rf2-9od6t, rf2-500ech)"
    (with-trace-recorder! [errs {:pred write-after-destroy-pred}]
      (is (nil? (rf.substrate.adapter/replace-container! nil {:any :value}))
          "nil container must NOT throw (background-thread NPE was the original bug)")
      (is (= 1 (count @errs))
          "exactly one :rf.error/write-after-destroy fires per nil-write")
      (is (= :ignored (:recovery (first @errs)))
          "error carries :recovery :ignored (write dropped, frame gone — mirrors frame-destroyed)"))))

;; ---- 2. live-destroy → captured-container-write ---------------------------

(deftest reagent-replace-container-on-destroyed-frame-does-not-npe
  (testing "frame/app-db-container on a destroyed frame returns nil; feeding
            that nil into replace-container! must no-op + warn (rf2-9od6t).
            This is the exact shape router.cljc's per-event :db commit
            takes when a scheduled drain reaches the write AFTER destroy."
    (let [frame-id :rf-9od6t/race]
      (with-trace-recorder! [errs {:pred write-after-destroy-pred}]
        (rf/make-frame {:id frame-id :doc "rf2-9od6t race reproducer"})
        (rf/destroy-frame! frame-id)
        (let [container (rf.frame/app-db-container frame-id)]
          (is (nil? container)
              "app-db-container on a destroyed frame returns nil — the rf2-ft2b precondition")
          (is (nil? (rf.substrate.adapter/replace-container! container {:would :have :npe'd true}))
              "writing through the nil container is a documented no-op"))
        (is (pos? (count @errs))
            ":rf.error/write-after-destroy fired for the post-destroy write")
        (is (every? #(= :ignored (:recovery %)) @errs)
            "every fired error carries :recovery :ignored")))))
