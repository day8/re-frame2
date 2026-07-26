(ns re-frame.frame-upsert-linearization-production-test
  "rf2-7vk3z — frame-upsert linearization, witnessed through PRODUCTION state.

  ## Why this namespace exists

  `re-frame.frame-upsert-linearization-jvm-test` proves a genuinely
  production-real invariant: which writer wins an exclusive-create race, and
  whether a failed re-registration rolls back to the pre-attempt generation.
  Frame construction linearizes identically in a production build; there is
  nothing dev-only about a last-writer clobber or a resurrected zombie record.

  But several of its assertions reach that invariant through
  `re-frame.trace.tooling/trace-rings` — the per-frame retention cap, used as
  a convenient FLAG for \"whose config got published\". The ring is a dev
  artefact and does not exist under `-Dre-frame.debug=false`, so those
  assertions read `nil` and the namespace is red in the `jvm-core-prod-gate`
  lane.

  The tempting rewrite — drop the ring assertions — would make it green while
  silently retiring live coverage of a concurrency invariant. So this namespace
  instead re-proves the SAME invariant through witnesses a production build
  really carries:

    * `frame/frame` — the authoritative registry record: `:config`,
      `:trace-policy-token`, `:construction :revision`;
    * `frame/frame-generation` — the generation slot the rollback contract is
      literally about;
    * `frame/frame-state-container` / `frame/frame-runtime-db-value` — the
      durable state a clobbered loser would have orphaned;
    * `trace/frame-trace-disabled?` — the OTHER frame-scoped policy store.
      That store is written unconditionally (it survives the gate; only the
      retention ring does not), so it still witnesses the rf2-umsyo9 contract
      that auxiliary policy publication linearizes with the registry.

  The one thing genuinely NOT witnessable in production is the retention cap
  itself, because a retain-N trace ring is not a thing production has. That
  arm stays in the dev-posture twin, where it belongs.

  ## Posture-independence

  Every assertion holds in dev AND under `-Dre-frame.debug=false`, so this
  namespace runs in the ordinary `clojure -M:test` suite and joins
  `scripts/test-core-prod-gate.sh` automatically (that lane's roster is an
  EXCLUSION list — a new namespace joins by default).

  Windows are opened DETERMINISTICALLY via the `frame/*upsert-decide-probe*` /
  `frame/*upsert-policy-probe*` JVM linearization seams (nil in production),
  conveyed into the racing thread by `future` binding-conveyance — no sleeps."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-listeners!)
  (trace/clear-frame-no-emit!)
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- err-id [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- window-probe
  "Trip a `reached` latch when the transaction reaches its window for `target`,
  then block on `release` — the deterministic window opener."
  [target ^CountDownLatch reached ^CountDownLatch release]
  (fn [id]
    (when (= id target)
      (.countDown reached)
      (.await release 10 TimeUnit/SECONDS))))

;; ===========================================================================
;; Exclusive create: only the transaction owner may publish.
;; ===========================================================================

(deftest exclusive-create-loser-cannot-publish-over-the-owner
  (testing "rf2-7vk3z / rf2-umsyo9 — a same-id contender that arrives while the
            owner holds an exclusive-create transaction loses at admission with
            `:rf.error/frame-construction-in-progress`, and neither its config
            nor its frame-scoped trace policy reaches any store. Witnessed
            through the registry record and the always-on no-emit policy store,
            not through the dev-only retention ring."
    (let [reached (CountDownLatch. 1)
          release (CountDownLatch. 1)
          owner   (binding [frame/*upsert-decide-probe*
                            (window-probe :prod/race reached release)]
                    (future
                      (frame/upsert-frame! :prod/race
                                           {:rf.frame/must-create?    true
                                            :tags                     #{:owner}
                                            :rf.trace/frame-no-emit?  false})))]
      (is (.await reached 10 TimeUnit/SECONDS)
          "the owner holds the id before publication")
      (is (= :rf.error/frame-construction-in-progress
             (err-id #(frame/upsert-frame! :prod/race
                                           {:tags                    #{:contender}
                                            :rf.trace/frame-no-emit? true})))
          "the contender loses at same-id admission")
      (is (false? (trace/frame-trace-disabled? :prod/race))
          "no suppression policy is published while the owner is paused")
      (.countDown release)
      (is (= :prod/race @owner) "the owner's transaction completes")
      (is (= #{:owner} (get-in (frame/frame :prod/race) [:config :tags]))
          "the authoritative registry record is the OWNER's — not a
           last-writer clobber by the loser")
      (is (false? (trace/frame-trace-disabled? :prod/race))
          "and the owner's frame-scoped policy is final")
      (is (some? (frame/frame-state-container :prod/race))
          "the owner's full record is published — no orphaned container"))))

(deftest disjoint-ids-stay-independent-under-a-held-transaction
  (testing "rf2-7vk3z — the negative control. A held transaction on one id does
            not serialize construction of a DIFFERENT id, so the fail-fast
            above is same-id admission and not a global construction lock."
    (let [reached (CountDownLatch. 1)
          release (CountDownLatch. 1)
          owner   (binding [frame/*upsert-decide-probe*
                            (window-probe :prod/held reached release)]
                    (future
                      (frame/upsert-frame! :prod/held
                                           {:rf.frame/must-create? true})))]
      (is (.await reached 10 TimeUnit/SECONDS) "the owner holds :prod/held")
      (is (= :prod/other
             (frame/upsert-frame! :prod/other {:tags #{:disjoint}}))
          "a disjoint id constructs while :prod/held is mid-transaction")
      (.countDown release)
      (is (= :prod/held @owner))
      (is (= #{:disjoint} (get-in (frame/frame :prod/other) [:config :tags]))))))

;; ===========================================================================
;; Failed re-registration rolls back to the PRE-ATTEMPT generation.
;; ===========================================================================

(deftest failed-reregistration-rolls-back-to-the-intervening-generation
  (testing "rf2-7vk3z / rf2-vxgfnd.197 — the invariant the bead names. A
            staged re-registration pauses provisional; the image-reprojection
            path legitimately swaps only the generation in that window; the
            re-registration's hook then fails. Rollback must restore the
            constructor's config, policy authority and construction revision
            WITHOUT replacing the whole record and erasing the intervening
            generation or the runtime container written during the callback.

            Every witness here is production state: the registry record, the
            generation slot, the runtime-db partition, and the always-on
            no-emit policy store."
    (let [id            :prod/rollback
          hook-key      :routing/on-frame-registered!
          original-hook (late-bind/get-fn hook-key)
          reached       (CountDownLatch. 1)
          release       (CountDownLatch. 1)]
      (frame/upsert-frame! id
                           {:tags                    #{:prior}
                            :rf.frame/generation     :prior-gen
                            :rf.trace/frame-no-emit? true})
      (let [prior-record       (frame/frame id)
            prior-config       (:config prior-record)
            prior-policy-token (:trace-policy-token prior-record)
            prior-revision     (get-in prior-record [:construction :revision])]
        (try
          (late-bind/set-fn!
            hook-key
            (fn [candidate-id]
              (when (= id candidate-id)
                ;; A valid same-owner runtime write during the callback must
                ;; survive rollback — its container is not construction-owned.
                (frame/replace-runtime-db! id {:foreign-runtime true})
                (throw (ex-info "registration hook failed"
                                {:test/outcome :hook-failed})))))
          (let [owner (binding [frame/*upsert-policy-probe*
                                (window-probe id reached release)]
                        (future
                          (try
                            (frame/upsert-frame!
                              id {:tags                    #{:failed}
                                  :rf.frame/generation     :failed-gen
                                  :rf.trace/frame-no-emit? false})
                            :unexpected-success
                            (catch clojure.lang.ExceptionInfo e
                              (:test/outcome (ex-data e))))))]
            (try
              (is (.await reached 10 TimeUnit/SECONDS)
                  "the constructor staged its exact provisional revision")
              (is (= :provisional
                     (get-in @frame/frames [id :construction :state])))
              ;; Mid-transaction the record is provisional, so the resolving
              ;; readers (`frame-generation` and friends) correctly decline it.
              ;; These two reads therefore go straight at `frame/frames` — the
              ;; PRODUCTION registry atom, not a dev instrumentation store.
              (is (= :failed-gen (get-in @frame/frames [id :generation]))
                  "the provisional generation is staged mid-transaction")
              ;; `reproject-live-frame!` resolves outside the registry atom and
              ;; reaches this raw generation-only mutator after resolution.
              (frame/set-generation! id :foreign-gen)
              (is (= :foreign-gen (get-in @frame/frames [id :generation]))
                  "the intervening generation write linearized before rollback")
              (finally
                (.countDown release)))
            (is (= :hook-failed @owner) "the staged re-registration fails")
            (is (= :foreign-gen (frame/frame-generation id))
                "ROLLBACK PRESERVES the valid intervening generation write —
                 it does not restore the whole record wholesale")
            (is (= {:foreign-runtime true} (frame/frame-runtime-db-value id))
                "and preserves runtime updates made during the callback")
            (is (= #{:prior} (get-in (frame/frame id) [:config :tags]))
                "the failed constructor's config is NOT retained")
            (is (= prior-config (:config (frame/frame id)))
                "the complete prior config is restored, not only its tags")
            (is (true? (trace/frame-trace-disabled? id))
                "the pre-attempt frame-scoped policy is restored")
            (is (identical? prior-policy-token
                            (:trace-policy-token (frame/frame id)))
                "as is the record's prior policy authority")
            (is (identical? prior-revision
                            (get-in (frame/frame id) [:construction :revision]))
                "and its prior final construction revision"))
          (finally
            (.countDown release)
            (late-bind/set-fn! hook-key original-hook)))))))
