(ns re-frame.routing-nav-allocation-record-replay-test
  "rf2-vcop6y — nav-token / pending-nav-id as RECORDABLE allocation coeffects
  (d8mvke.6 Finding 3 fix).

  THE HOLE (pre-rf2-vcop6y): routing minted `:nav-token` (committed route
  correlation) and the pending-nav `:id` (can-leave block) from the AMBIENT
  `:rf.route/nav-counters` cofx — a host-cache read that was NEVER recorded —
  and wrote them DURABLY to runtime-db. On replay the ambient read re-ran
  against the live host cache and re-minted DIFFERENT ids, so recorded events
  that reference the originals mismatched replayed state. Two concrete failures:

    1. **pending-nav continue no-op on re-mint.** A live block mints \"pn-1\",
       writes it to the pending-navigation slot; a recorded
       `[:rf.route/continue \"pn-1\"]` resolves it later. On replay the ambient
       re-mint produces \"pn-2\", so `[:rf.route/continue \"pn-1\"]` no-ops and
       the navigation stays blocked forever.
    2. **nav-token stale-suppression flip.** A live commit writes :nav-token
       \"nav-1\"; an async continuation carries \"nav-1\". On replay the
       re-mint produces a different token, so the stale-suppression gate
       (carried vs current by value) flips the recorded continuation from
       current → stale (or vice versa) — committing a result that should be
       suppressed, or suppressing one that should commit.

  THE FIX: the ids ride two RECORDABLE, generator-backed allocation coeffects
  (one per allocator, rf2-oosjmh) —
    `:rf.route/nav-allocation         {:token \"nav-N\" :counter N}`
    `:rf.route/pending-nav-allocation {:id    \"pn-N\"  :counter N}`
  whose generator mints from the host snapshot at processing-start and whose
  value is RECORDED onto the causal token; strict replay re-presents the SAME
  id verbatim (and FAILS on a missing recorded allocation). The commit fx
  advances the host high-water with `max` so a restore/replay cannot rewind the
  allocator (the never-recycle invariant, kept from rf2-oosjmh).

  This namespace is the ADVERSARIAL acceptance: it reproduces both failures
  under the re-mint scenario (the hole) and shows them FIXED when the recorded
  allocation is re-presented under strict replay."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.cofx :as cofx]
            [re-frame.routing :as routing]
            [re-frame.routing.nav-counters :as nav-counters]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

(defn- block-fixture!
  "Register an editor route with a blocking `:can-leave`, a home target, and
  stub the history fxs (so the block's `:rf.nav/replace-url` runs on JVM).
  Land on the editor (the active route the block guards)."
  []
  (rf/reg-route :route/editor {:can-leave [:editor/can-leave?]} "/editor")
  (rf/reg-route :route/home   {} "/home")
  (rf/reg-sub :editor/can-leave? (fn [_ _] false))           ;; dirty → block
  (rf/reg-fx :rf.nav/push-url    {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/dispatch-sync [:rf.route/transitioned "/editor"]))

(defn- pending-id []
  (:id (rf/subscribe-once [:rf/pending-navigation] {:frame :rf/default})))

(defn- blocked? []
  (some? (rf/subscribe-once [:rf/pending-navigation] {:frame :rf/default})))

;; ===========================================================================
;; The allocation-cofx SHAPE (rf2-vcop6y step 3): two RECORDABLE,
;; generator-backed facts carrying both the id AND the allocator :counter.
;; ===========================================================================

(deftest allocation-cofx-are-recordable-generator-backed-and-split
  (testing "rf2-vcop6y step 3: TWO distinct recordable allocation coeffects —
            `:rf.route/nav-allocation` and `:rf.route/pending-nav-allocation`
            — each generator-backed (recordable, NOT provided) and carrying
            `{:token/:id .. :counter ..}` (the id + the allocator high-water)"
    (let [nav (rf/handler-meta :cofx :rf.route/nav-allocation)
          pn  (rf/handler-meta :cofx :rf.route/pending-nav-allocation)]
      (is (true? (:recordable? nav)) ":rf.route/nav-allocation is recordable")
      (is (true? (:recordable? pn))  ":rf.route/pending-nav-allocation is recordable")
      (is (not (:provided? nav))     "nav-allocation is generator-backed, NOT provided")
      (is (not (:provided? pn))      "pending-nav-allocation is generator-backed, NOT provided"))
    ;; The OLD ambient counter-snapshot cofx is GONE (step 2: do NOT record it).
    (is (nil? (rf/handler-meta :cofx :rf.route/nav-counters))
        "the ambient :rf.route/nav-counters cofx is retired (step 2)"))

  (testing "the generators mint {:token/:id .. :counter ..} from the host snapshot"
    (let [nav-gen (:handler-fn (rf/handler-meta :cofx :rf.route/nav-allocation))
          pn-gen  (:handler-fn (rf/handler-meta :cofx :rf.route/pending-nav-allocation))]
      ;; Bind the active frame the generators read (`frame/*current-frame*`).
      (rf/with-frame :rf/default
        (is (= {:token "nav-1" :counter 1} (nav-gen))
            "nav-allocation mints {:token \"nav-1\" :counter 1} from an empty host snapshot")
        (is (= {:id "pn-1" :counter 1} (pn-gen))
            "pending-nav-allocation mints {:id \"pn-1\" :counter 1} — a DISTINCT allocator")))))

;; ===========================================================================
;; FAILURE 1 — pending-nav continue no-op on re-mint, now FIXED under replay.
;; ===========================================================================

(deftest failure-1-pending-nav-continue-no-op-on-remint
  (testing "rf2-vcop6y FAILURE 1 (the hole): a re-mint produces a DIFFERENT
            pending-nav id, so a recorded [:rf.route/continue \"pn-1\"] no-ops
            and the navigation stays blocked"
    (block-fixture!)
    ;; A PRIOR block already advanced the host pending-nav high-water to 1, so a
    ;; fresh ambient re-mint (the retired behaviour) yields \"pn-2\", NOT the
    ;; recorded \"pn-1\".
    (nav-counters/commit-counter! :rf/default :pending-nav-counter 1)
    ;; LIVE re-mint (mirrors the ambient hole — no recorded allocation supplied).
    (rf/dispatch-sync [:rf/url-requested {:url "/home"}])
    (is (= "pn-2" (pending-id))
        "the re-mint produced pn-2 (NOT the recorded pn-1) — the hole")
    ;; The recorded continue carries the ORIGINAL id \"pn-1\".
    (rf/dispatch-sync [:rf.route/continue "pn-1"])
    (is (blocked?)
        "[:rf.route/continue \"pn-1\"] no-ops against the re-minted pn-2 — navigation STAYS BLOCKED (the bug)")))

(deftest failure-1-fixed-recorded-allocation-replays-same-pending-nav-id
  (testing "rf2-vcop6y FIX: replaying the block with the RECORDED
            `:rf.route/pending-nav-allocation` re-presents the SAME pn-1 even
            though the host counter advanced — so [:rf.route/continue \"pn-1\"]
            matches and the navigation proceeds"
    (block-fixture!)
    ;; Same advanced host counter as the failing case — a re-mint WOULD give pn-2.
    (nav-counters/commit-counter! :rf/default :pending-nav-counter 1)
    ;; REPLAY: the recorded allocation rides the causal token; strict mode
    ;; (replay) re-presents it verbatim — the generator does NOT run.
    (rf/dispatch-sync [:rf/url-requested {:url "/home"}]
                      {:rf.cofx {:rf.route/pending-nav-allocation {:id "pn-1" :counter 1}}
                       :rf.cofx/mint-policy :strict})
    (is (= "pn-1" (pending-id))
        "strict replay re-presents the recorded pn-1 (NOT a re-minted pn-2)")
    ;; The recorded continue now matches the re-presented id.
    (rf/dispatch-sync [:rf.route/continue "pn-1"])
    (is (not (blocked?))
        "[:rf.route/continue \"pn-1\"] matches the replayed pn-1 — navigation PROCEEDS (fixed)")))

(deftest strict-replay-fails-on-missing-pending-nav-allocation
  (testing "rf2-vcop6y step 6: strict replay FAILS LOUDLY when the recorded
            pending-nav allocation is missing (`:rf.error/missing-required-cofx`)
            — an incomplete record must not silently re-read the host"
    (block-fixture!)
    (let [ex (try (rf/dispatch-sync [:rf/url-requested {:url "/home"}]
                                    {:rf.cofx/mint-policy :strict})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "strict mode with no recorded allocation throws")
      (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data ex)))
          "the throw is :rf.error/missing-required-cofx")
      (is (= :rf.route/pending-nav-allocation (:rf.cofx/id (ex-data ex)))
          "the error names the missing recordable allocation"))))

;; ===========================================================================
;; FAILURE 2 — nav-token stale-suppression flip on re-mint, now FIXED.
;; ===========================================================================

(deftest failure-2-nav-token-stale-suppression-flip-on-remint
  (testing "rf2-vcop6y FAILURE 2 (the hole): a re-mint writes a DIFFERENT
            :nav-token into the committed slice, so an async continuation
            carrying the RECORDED token mismatches the re-minted current — the
            stale-suppression gate flips (suppresses a result that should
            commit)"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-event :article/loaded (fn [{:keys [db]} [_ id payload]] {:db (assoc db :article {:id id :payload payload})}))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::flip (fn [ev] (swap! traces conj ev)))
      ;; A PRIOR navigation advanced the host nav-token high-water to 5, so a
      ;; fresh ambient re-mint (the retired behaviour) yields \"nav-6\".
      (nav-counters/commit-counter! :rf/default :nav-token-counter 5)
      ;; LIVE re-mint (the hole) — the slice gets nav-6, NOT the recorded nav-1.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (is (= "nav-6" (get-in (rf/runtime-db-value :rf/default)
                             [:rf.runtime/routing :current :nav-token]))
          "the re-mint wrote nav-6 to the slice (NOT the recorded nav-1)")
      ;; The recorded async continuation carries the ORIGINAL token \"nav-1\".
      ;; with-nav-token (via the test fixture) gates carried vs current: nav-1
      ;; vs nav-6 → MISMATCH → the result is SUPPRESSED — but on the original
      ;; live run nav-1 WAS current and the result committed. The flip.
      (rf/dispatch-sync [:rf.test/simulate-http-resolution
                         {:on-success-event  [:article/loaded "A" "A-payload"]
                          :carried-nav-token "nav-1"
                          :carried-route-id  :route/article}])
      (rf/unregister-listener! :trace ::flip)
      (is (nil? (:article (rf/app-db-value :rf/default)))
          "the recorded continuation (carried nav-1) was SUPPRESSED against the re-minted nav-6 — the bug (should have committed)")
      (is (some #(= :rf.route.nav-token/stale-suppressed (:operation %)) @traces)
          "a stale-suppressed trace fired — the flipped decision"))))

(deftest failure-2-fixed-recorded-allocation-replays-same-nav-token
  (testing "rf2-vcop6y FIX: replaying the commit with the RECORDED
            `:rf.route/nav-allocation` re-presents the SAME nav-1 even though
            the host counter advanced — so a continuation carrying nav-1
            matches current and the result commits (the gate decision is
            preserved across record→replay)"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-event :article/loaded (fn [{:keys [db]} [_ id payload]] {:db (assoc db :article {:id id :payload payload})}))
    ;; Same advanced host counter as the failing case — a re-mint WOULD give nav-6.
    (nav-counters/commit-counter! :rf/default :nav-token-counter 5)
    ;; REPLAY: the recorded token carries BOTH allocations (a `transitioned`
    ;; event records both — both generate live; only nav-allocation is used on
    ;; the commit branch, but strict replay re-presents the full record).
    (rf/dispatch-sync [:rf.route/transitioned "/articles/A"]
                      {:rf.cofx {:rf.route/nav-allocation         {:token "nav-1" :counter 1}
                                 :rf.route/pending-nav-allocation {:id "pn-1" :counter 1}}
                       :rf.cofx/mint-policy :strict})
    (is (= "nav-1" (get-in (rf/runtime-db-value :rf/default)
                           [:rf.runtime/routing :current :nav-token]))
        "strict replay re-presents the recorded nav-1 (NOT a re-minted nav-6)")
    ;; The recorded continuation carrying nav-1 now MATCHES current → commits.
    (rf/dispatch-sync [:rf.test/simulate-http-resolution
                       {:on-success-event  [:article/loaded "A" "A-payload"]
                        :carried-nav-token "nav-1"
                        :carried-route-id  :route/article}])
    (is (= {:id "A" :payload "A-payload"} (:article (rf/app-db-value :rf/default)))
        "the continuation (carried nav-1) matched the replayed nav-1 — committed (gate decision preserved, fixed)")))

(deftest strict-replay-fails-on-missing-nav-allocation
  (testing "rf2-vcop6y step 6: strict replay FAILS LOUDLY when the recorded
            nav-token allocation is missing (`:rf.error/missing-required-cofx`)"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (let [ex (try (rf/dispatch-sync [:rf.route/transitioned "/articles/A"]
                                    {:rf.cofx/mint-policy :strict})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "strict mode with no recorded allocation throws")
      (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data ex)))
          "the throw is :rf.error/missing-required-cofx")
      (is (= :rf.route/nav-allocation (:rf.cofx/id (ex-data ex)))
          "the error names the missing recordable allocation"))))

;; ===========================================================================
;; The commit fx advances the host high-water with MAX (step 5): replay/restore
;; can re-establish the allocator from the recorded :counter but never rewind it.
;; ===========================================================================

(deftest commit-advances-host-high-water-with-max-from-recorded-counter
  (testing "rf2-vcop6y step 5: the commit fx advances the host high-water with
            `max` from the recorded allocation's :counter, so a replayed
            allocation re-establishes the allocator and a later live navigation
            mints strictly past it (no recycle)"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    ;; Replay an allocation whose recorded :counter is 9 (the host starts at 0).
    ;; A `transitioned` record carries both allocations (both generate live).
    (rf/dispatch-sync [:rf.route/transitioned "/articles/A"]
                      {:rf.cofx {:rf.route/nav-allocation         {:token "nav-9" :counter 9}
                                 :rf.route/pending-nav-allocation {:id "pn-1" :counter 1}}
                       :rf.cofx/mint-policy :strict})
    (is (= 9 (:nav-token-counter (nav-counters/counter-snapshot :rf/default)))
        "the commit fx advanced the host high-water to the recorded :counter (9) via max")
    ;; A subsequent LIVE navigation mints strictly past the re-established mark.
    (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
    (is (= "nav-10" (get-in (rf/runtime-db-value :rf/default)
                            [:rf.runtime/routing :current :nav-token]))
        "the next live token is nav-10 — monotone past the replayed high-water, no recycle")))

;; ===========================================================================
;; Live navigations still allocate monotone non-recycled ids (acceptance:
;; the fix is invisible to ordinary live behaviour).
;; ===========================================================================

(deftest live-navigation-still-monotone-and-non-recycling
  (testing "rf2-vcop6y acceptance: with NO recorded allocation supplied, live
            navigations mint monotone, non-recycled nav-tokens exactly as
            before — the recordable seam is transparent to the live path"
    (rf/reg-route :route/a {} "/a")
    (rf/reg-route :route/b {} "/b")
    (rf/reg-route :route/c {} "/c")
    (rf/dispatch-sync [:rf.route/transitioned "/a"])
    (rf/dispatch-sync [:rf.route/transitioned "/b"])
    (rf/dispatch-sync [:rf.route/transitioned "/c"])
    (is (= "nav-3" (get-in (rf/runtime-db-value :rf/default)
                           [:rf.runtime/routing :current :nav-token]))
        "three live navigations mint nav-1 → nav-2 → nav-3 (monotone)")
    ;; A clean commit (no block) leaves the pending-nav counter untouched — the
    ;; pending-nav generator runs but its commit fx only fires on a block.
    (is (nil? (:pending-nav-counter (nav-counters/counter-snapshot :rf/default)))
        "a clean commit never advances the pending-nav allocator (no block branch taken)")))

;; ===========================================================================
;; rf2-ps05ug — a malformed-but-PRESENT recorded allocation fails LOUDLY.
;;
;; THE HOLE (pre-rf2-ps05ug): both allocation cofx were registered with
;; `:recordable? true` but NO `:schema`. `validate-recordable-value!`
;; (cofx.cljc) is a no-op when the registration declares no `:schema`, so a
;; supplied/replayed value that is structurally EDN but semantically wrong
;; (`{:token nil :counter "bad"}`, `{:id nil :counter nil}`) was NOT missing
;; → strict replay did not re-mint and did not throw. The handler folded the
;; nil/wrong token / pending-nav id into DURABLE runtime-db and the host
;; counter bump silently no-op'd, corrupting stale-suppression / continue-
;; cancel instead of surfacing `:rf.error/cofx-value-invalid`.
;;
;; THE FIX: a concrete `:schema` on each registration —
;;   :rf.route/nav-allocation         [:map [:token :string] [:counter :int]]
;;   :rf.route/pending-nav-allocation [:map [:id    :string] [:counter :int]]
;; so the supplied/replayed branch validates the present value and throws
;; `:rf.error/cofx-value-invalid` BEFORE the handler writes the route slice /
;; pending-navigation slot. (The schemas Malli validator is LIVE on this test
;; classpath — `routing-test-support` requires `re-frame.schemas`, whose
;; facade wires the default Malli hooks on load, rf2-v96fh — so these assert
;; the REAL validation path, not a vacuous no-validator pass.)
;; ===========================================================================

(deftest strict-replay-fails-on-malformed-present-pending-nav-allocation
  (testing "rf2-ps05ug: a PRESENT-but-malformed recorded pending-nav allocation
            fails with :rf.error/cofx-value-invalid BEFORE the handler writes
            pending-nav state (NOT folded in as a trusted value)"
    (block-fixture!)
    (let [ex (try (rf/dispatch-sync [:rf/url-requested {:url "/home"}]
                                    {:rf.cofx {:rf.route/pending-nav-allocation
                                               {:id nil :counter "bad"}}
                                     :rf.cofx/mint-policy :strict})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "a malformed present allocation throws (does not silently fold)")
      (is (= :rf.error/cofx-value-invalid (:rf.error/id (ex-data ex)))
          "the throw is :rf.error/cofx-value-invalid")
      (is (= :rf.route/pending-nav-allocation (:rf.cofx/id (ex-data ex)))
          "the error names the failing allocation cofx")
      ;; The block handler never ran: the malformed `:id` was rejected at the
      ;; cofx boundary BEFORE the can-leave block could fold a nil/corrupt
      ;; pending-nav id into the durable runtime-db slot. No pending-navigation
      ;; was written (the throw aborted the dispatch before the block branch).
      (is (nil? (pending-id))
          "no (corrupt) pending-nav id was folded into durable runtime-db"))))

(deftest strict-replay-fails-on-malformed-present-nav-allocation
  (testing "rf2-ps05ug: a PRESENT-but-malformed recorded nav-token allocation
            fails with :rf.error/cofx-value-invalid BEFORE the commit handler
            writes the :nav-token into the durable route slice"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (let [ex (try (rf/dispatch-sync [:rf.route/transitioned "/articles/A"]
                                    {:rf.cofx {:rf.route/nav-allocation
                                               {:token nil :counter "bad"}}
                                     :rf.cofx/mint-policy :strict})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "a malformed present allocation throws (does not silently fold)")
      (is (= :rf.error/cofx-value-invalid (:rf.error/id (ex-data ex)))
          "the throw is :rf.error/cofx-value-invalid")
      (is (= :rf.route/nav-allocation (:rf.cofx/id (ex-data ex)))
          "the error names the failing allocation cofx")
      ;; The commit handler never ran: no :nav-token (let alone a nil one) was
      ;; folded into the durable route slice.
      (is (nil? (get-in (rf/runtime-db-value :rf/default)
                        [:rf.runtime/routing :current :nav-token]))
          "no nil nav-token was folded into the durable route slice"))))

(deftest live-allocation-still-conforms-to-schema
  (testing "rf2-ps05ug acceptance: the live generator's produced allocation
            conforms to the new :schema, so the schema is transparent to the
            ordinary (non-replay) path"
    (rf/reg-route :route/a {} "/a")
    ;; A live navigation runs the generator (well-formed `{:token \"nav-1\"
    ;; :counter 1}`) and commits cleanly — no cofx-value-invalid throw.
    (rf/dispatch-sync [:rf.route/transitioned "/a"])
    (is (= "nav-1" (get-in (rf/runtime-db-value :rf/default)
                           [:rf.runtime/routing :current :nav-token]))
        "the live generator's well-formed allocation passes the schema and commits")))
