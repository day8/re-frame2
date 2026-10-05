(ns re-frame.routing-nav-allocation-record-replay-test
  "Nav-token / pending-nav-id as RECORDABLE allocation coeffects.

  WHY RECORDABLE: routing mints `:nav-token` (committed route correlation) and
  the pending-nav `:id` (can-leave block) and writes them DURABLY to
  runtime-db. Minted from an AMBIENT host-cache read that is never recorded,
  replay would re-run that read against the live host cache and re-mint
  DIFFERENT ids, so recorded events that reference the originals would
  mismatch replayed state. Two concrete failures:

    1. **pending-nav continue no-op on re-mint.** A live block mints \"pn-1\",
       writes it to the pending-navigation slot; a recorded
       `[:rf.route/continue \"pn-1\"]` resolves it later. On replay a
       re-mint produces \"pn-2\", so `[:rf.route/continue \"pn-1\"]` no-ops and
       the navigation stays blocked forever.
    2. **nav-token stale-suppression flip.** A live commit writes :nav-token
       \"nav-1\"; an async continuation carries \"nav-1\". On replay the
       re-mint produces a different token, so the stale-suppression gate
       (carried vs current by value) flips the recorded continuation from
       current → stale (or vice versa) — committing a result that should be
       suppressed, or suppressing one that should commit.

  THE MECHANISM: the ids ride two RECORDABLE, generator-backed allocation coeffects
  (one per allocator) —
    `:rf.route/nav-allocation         {:token \"nav-N\" :counter N}`
    `:rf.route/pending-nav-allocation {:id    \"pn-N\"  :counter N}`
  whose generator mints from the host snapshot at processing-start and whose
  value is RECORDED onto the causal token; strict replay re-presents the SAME
  id verbatim (and FAILS on a missing recorded allocation). The commit fx
  advances the host high-water with `max` so a restore/replay cannot rewind the
  allocator (the never-recycle invariant).

  This namespace is the ADVERSARIAL acceptance: it reproduces both failures
  under the re-mint scenario (no recorded allocation) and shows them ABSENT
  when the recorded allocation is re-presented under strict replay.

  ## Posture split

  Both failures and both replayed cases are production-real and carry no posture
  guard — the minted ids land in runtime-db, the `:rf.route/continue` no-op,
  the stale-suppression flip and its repair are all readable off runtime-db
  and app-db. They run in the ordinary `clojure -M:test` suite AND in
  `scripts/test-routing-prod-gate.sh` (the `-Dre-frame.debug=false` lane).

  The single exception is the `:rf.route.nav-token/stale-suppressed` TRACE,
  dev instrumentation behind `rf.interop/debug-enabled?`; it sits
  inside a `(when rf.interop/debug-enabled? …)` arm marked \"Dev-instrumentation
  arm\", beside the app-db assertion that proves the same suppression happened."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.cofx :as rf.cofx]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.nav-counters :as rf.routing.nav-counters]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- block-fixture!
  "Register an editor route with a blocking `:can-leave`, a home target, and
  stub the history fxs (so the block's `:rf.nav/replace-url` runs on JVM).
  Land on the editor (the active route the block guards)."
  []
  (rf/reg-route :route/editor {:can-leave [:editor/can-leave?]} "/editor")
  (rf/reg-route :route/home   {} "/home")
  (rf/reg-sub :editor/can-leave? (fn [_ _] false))           ;; dirty → block
  (rf.fx/reg-fx :rf.nav/push-url    {:platforms #{:server :client}} (fn [_ _] nil))
  (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/dispatch-sync [:rf.route/handle-url-change "/editor" {:rf.route/cause :link}]))

(defn- pending-id []
  (:id (rf/subscribe-once [:rf/pending-navigation] {:frame :rf/default})))

(defn- blocked? []
  (some? (rf/subscribe-once [:rf/pending-navigation] {:frame :rf/default})))

;; ===========================================================================
;; FAILURE 1 — pending-nav continue no-op on re-mint, absent under replay.
;; ===========================================================================

(deftest failure-1-fixed-recorded-allocation-replays-same-pending-nav-id
  (testing "REPLAY: replaying the block with the RECORDED
            `:rf.route/pending-nav-allocation` re-presents the SAME pn-1 even
            though the host counter advanced — so [:rf.route/continue \"pn-1\"]
            matches and the navigation proceeds"
    (block-fixture!)
    ;; Same advanced host counter as the failing case — a re-mint WOULD give pn-2.
    (rf.routing.nav-counters/commit-counter! :rf/default :pending-nav-counter 1)
    ;; REPLAY: the recorded allocation rides the causal token; strict mode
    ;; (replay) re-presents it verbatim — the generator does NOT run.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/home"}]
                      {:rf.cofx {:rf.route/pending-nav-allocation {:id "pn-1" :counter 1}}
                       :rf.cofx/mint-policy :strict})
    (is (= "pn-1" (pending-id))
        "strict replay re-presents the recorded pn-1 (NOT a re-minted pn-2)")
    ;; The recorded continue matches the re-presented id.
    (rf/dispatch-sync [:rf.route/continue "pn-1"])
    (is (not (blocked?))
        "[:rf.route/continue \"pn-1\"] matches the replayed pn-1 — navigation PROCEEDS")))

;; ===========================================================================
;; FAILURE 2 — nav-token stale-suppression flip on re-mint, absent under replay.
;; ===========================================================================

(deftest failure-2-fixed-recorded-allocation-replays-same-nav-token
  (testing "REPLAY: replaying the commit with the RECORDED
            `:rf.route/nav-allocation` re-presents the SAME nav-1 even though
            the host counter advanced — so a continuation carrying nav-1
            matches current and the result commits (the gate decision is
            preserved across record→replay)"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-event :article/loaded (fn [{:keys [db]} [_ id payload]] {:db (assoc db :article {:id id :payload payload})}))
    ;; Same advanced host counter as the failing case — a re-mint WOULD give nav-6.
    (rf.routing.nav-counters/commit-counter! :rf/default :nav-token-counter 5)
    ;; REPLAY: the recorded token carries BOTH allocations (a `handle-url-change`
    ;; event records both — both generate live; only nav-allocation is used on
    ;; the commit branch, but strict replay re-presents the full record).
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/A" {:rf.route/cause :link}]
                      {:rf.cofx {:rf.route/nav-allocation         {:token "nav-1" :counter 1}
                                 :rf.route/pending-nav-allocation {:id "pn-1" :counter 1}}
                       :rf.cofx/mint-policy :strict})
    (is (= "nav-1" (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                           [:rf.runtime/routing :current :nav-token]))
        "strict replay re-presents the recorded nav-1 (NOT a re-minted nav-6)")
    ;; The recorded continuation carrying nav-1 MATCHES current → commits.
    (rf/dispatch-sync [:rf.test/simulate-http-resolution
                       {:on-success-event  [:article/loaded "A" "A-payload"]
                        :carried-nav-token "nav-1"
                        :carried-route-id  :route/article}])
    (is (= {:id "A" :payload "A-payload"} (:article (rf/app-db-value :rf/default)))
        "the continuation (carried nav-1) matched the replayed nav-1 — committed (gate decision preserved)")))

;; ===========================================================================
;; The commit fx advances the host high-water with MAX: replay/restore
;; can re-establish the allocator from the recorded :counter but never rewind it.
;; ===========================================================================

(deftest commit-advances-host-high-water-with-max-from-recorded-counter
  (testing "the commit fx advances the host high-water with
            `max` from the recorded allocation's :counter, so a replayed
            allocation re-establishes the allocator and a later live navigation
            mints strictly past it (no recycle)"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    ;; Replay an allocation whose recorded :counter is 9 (the host starts at 0).
    ;; A `handle-url-change` record carries both allocations (both generate live).
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/A" {:rf.route/cause :link}]
                      {:rf.cofx {:rf.route/nav-allocation         {:token "nav-9" :counter 9}
                                 :rf.route/pending-nav-allocation {:id "pn-1" :counter 1}}
                       :rf.cofx/mint-policy :strict})
    (is (= 9 (:nav-token-counter (rf.routing.nav-counters/counter-snapshot :rf/default)))
        "the commit fx advanced the host high-water to the recorded :counter (9) via max")
    ;; A subsequent LIVE navigation mints strictly past the re-established mark.
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/B" {:rf.route/cause :link}])
    (is (= "nav-10" (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                            [:rf.runtime/routing :current :nav-token]))
        "the next live token is nav-10 — monotone past the replayed high-water, no recycle")))

;; ===========================================================================
;; A malformed-but-PRESENT recorded allocation fails LOUDLY.
;;
;; `validate-recordable-value!` (cofx.cljc) is a no-op when a registration
;; declares no `:schema`, so without one a supplied/replayed value that is
;; structurally EDN but semantically wrong (`{:token nil :counter "bad"}`,
;; `{:id nil :counter nil}`) would NOT be missing → strict replay would
;; neither re-mint nor throw. The handler would fold the nil/wrong token /
;; pending-nav id into DURABLE runtime-db and the host counter bump would
;; silently no-op, corrupting stale-suppression / continue-cancel instead of
;; surfacing `:rf.error/cofx-value-invalid`.
;;
;; So each registration carries a concrete `:schema` —
;;   :rf.route/nav-allocation         [:map [:token :string] [:counter :int]]
;;   :rf.route/pending-nav-allocation [:map [:id    :string] [:counter :int]]
;; so the supplied/replayed branch validates the present value and throws
;; `:rf.error/cofx-value-invalid` BEFORE the handler writes the route slice /
;; pending-navigation slot. (The schemas Malli validator is LIVE on this test
;; classpath — `routing-test-support` requires `re-frame.schemas`, whose
;; facade wires the default Malli hooks on load — so these assert
;; the REAL validation path, not a vacuous no-validator pass.)
;; ===========================================================================

(deftest strict-replay-rejects-a-missing-or-malformed-pending-nav-allocation
  (testing "strict replay FAILS LOUDLY when the recorded pending-nav allocation is
            MISSING (`:rf.error/missing-required-cofx`) or PRESENT but malformed
            (`:rf.error/cofx-value-invalid`), BEFORE the handler writes
            pending-nav state: an incomplete record must not silently re-read
            the host, and a corrupt one must not fold in as a trusted value"
    (block-fixture!)
    (doseq [[recorded error-id]
            [[nil :rf.error/missing-required-cofx]
             [{:rf.route/pending-nav-allocation {:id nil :counter "bad"}}
              :rf.error/cofx-value-invalid]]]
      (let [ex (try (rf/dispatch-sync [:rf.route/url-requested {:url "/home"}]
                                      (cond-> {:rf.cofx/mint-policy :strict}
                                        recorded (assoc :rf.cofx recorded)))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (str error-id ": strict replay throws"))
        (is (= error-id (:rf.error/id (ex-data ex))))
        (is (= :rf.route/pending-nav-allocation (:rf.cofx/id (ex-data ex)))
            "the error names the allocation cofx")
        ;; The block handler never ran: the throw aborted the dispatch before
        ;; the can-leave block could fold a nil/corrupt pending-nav id into the
        ;; durable runtime-db slot.
        (is (nil? (pending-id))
            "no pending-nav id was folded into durable runtime-db")))))

(deftest strict-replay-rejects-a-missing-or-malformed-nav-allocation
  (testing "strict replay FAILS LOUDLY when the recorded nav-token allocation is
            MISSING (`:rf.error/missing-required-cofx`) or PRESENT but malformed
            (`:rf.error/cofx-value-invalid`), BEFORE the commit handler writes
            the :nav-token into the durable route slice"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (doseq [[recorded error-id]
            [[nil :rf.error/missing-required-cofx]
             [{:rf.route/nav-allocation {:token nil :counter "bad"}}
              :rf.error/cofx-value-invalid]]]
      (let [ex (try (rf/dispatch-sync [:rf.route/handle-url-change "/articles/A" {:rf.route/cause :link}]
                                      (cond-> {:rf.cofx/mint-policy :strict}
                                        recorded (assoc :rf.cofx recorded)))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (str error-id ": strict replay throws"))
        (is (= error-id (:rf.error/id (ex-data ex))))
        (is (= :rf.route/nav-allocation (:rf.cofx/id (ex-data ex)))
            "the error names the allocation cofx")
        ;; The commit handler never ran: no :nav-token (let alone a nil one)
        ;; was folded into the durable route slice.
        (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                          [:rf.runtime/routing :current :nav-token]))
            "no nav-token was folded into the durable route slice")))))

