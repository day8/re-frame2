(ns re-frame.routing-nav-counters-test
  "rf2-oosjmh — host-side nav-token / pending-nav counter reconciliation.

  The two monotonic routing ALLOCATORS (`:nav-token-counter` /
  `:pending-nav-counter`) moved OUT of the `[:rf.runtime/routing ...]`
  runtime-db partition into a host-side per-frame transient cache
  (`re-frame.routing.nav-counters`), mirroring the rf2-1hncp2 scroll
  cache. The driving correctness reason: an epoch restore replaces the
  runtime-db partition WHOLESALE, which — when the counter lived in
  runtime-db — rewound it, recycling a token an in-flight async
  continuation might still carry (the recycle events.cljc's invariant
  forbids). Held host-side the counter is a high-water mark untouched by
  restore, so a post-restore allocation always exceeds any pre-restore
  in-flight token.

  This namespace pins:
    - restore-then-navigate: after an epoch restore that rewinds the
      runtime-db route slice, the NEXT navigate mints a FRESH token that
      does NOT recycle a pre-restore value;
    - the counters are NOT in runtime-db after the move;
    - `:pending-navigation` STAYS in runtime-db (subscribable) but is
      stripped from the SSR hydration payload;
    - the routing classification table + SSR allowlist share one source
      of truth."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.routing.nav-counters :as nav-counters]
            [re-frame.ssr.payload-policy :as payload-policy]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

;; ---- the move: counters are NOT runtime-db state -------------------------

(deftest counters-not-in-runtime-db-after-navigation
  (testing "rf2-oosjmh: a navigation mints a nav-token but leaves NO
            nav-token-counter / pending-nav-counter in the runtime-db
            routing partition — the counters live host-side"
    (rf/reg-route :route/a {} "/a")
    (rf/reg-route :route/b {} "/b")
    (rf/dispatch-sync [:rf.route/transitioned "/a"])
    (rf/dispatch-sync [:rf.route/transitioned "/b"])
    (let [routing-rt (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                             [:rf.runtime/routing])]
      (is (= "nav-2" (get-in routing-rt [:current :nav-token]))
          "the active token still rides the durable route slice")
      (is (not (contains? routing-rt :nav-token-counter))
          "the nav-token-counter is NOT a runtime-db key (it is host-side)")
      (is (not (contains? routing-rt :pending-nav-counter))
          "the pending-nav-counter is NOT a runtime-db key (it is host-side)"))
    (is (= 2 (:nav-token-counter (nav-counters/counter-snapshot :rf/default)))
        "the nav-token high-water mark lives in the host-side cache")))

;; ---- restore-then-navigate: no token recycle across an epoch restore -----

(deftest restore-then-navigate-allocates-fresh-token-from-host-cache
  (testing "rf2-oosjmh: an epoch restore rewinds the runtime-db route slice
            (the active nav-token goes back to its restored value), but the
            host-side counter is a high-water mark untouched by restore — so
            the NEXT navigation mints a token that exceeds any pre-restore
            in-flight token and CANNOT recycle one"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")

    ;; Three navigations advance the host counter to its high-water mark.
    (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])  ;; nav-1
    (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])  ;; nav-2
    (rf/dispatch-sync [:rf.route/transitioned "/articles/C"])  ;; nav-3
    (is (= "nav-3" (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                           [:rf.runtime/routing :current :nav-token]))
        "third navigation is the live nav-3")
    (is (= 3 (:nav-token-counter (nav-counters/counter-snapshot :rf/default)))
        "host high-water mark reached 3")

    ;; Capture the runtime-db AS IT WAS at nav-1 — the snapshot an epoch
    ;; restore replays. A slow in-flight continuation issued back at nav-1
    ;; (network already on the wire, uncancellable) still carries "nav-1".
    (let [restored-runtime-db
          {:rf.runtime/routing {:current {:route-id        :route/article
                                          :params    {:id "A"}
                                          :query     {}
                                          :fragment  nil
                                          :transition :idle
                                          :error     nil
                                          :nav-token "nav-1"}}}]

      ;; Epoch restore: replace the runtime-db partition WHOLESALE (the
      ;; mechanism `restore-epoch!` / time-travel uses — frame/replace-
      ;; runtime-db!). This REWINDS the runtime-db route slice to nav-1.
      (frame/replace-runtime-db! :rf/default restored-runtime-db)
      (is (= "nav-1" (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                             [:rf.runtime/routing :current :nav-token]))
          "the restore rewound the runtime-db slice's active token to nav-1")
      ;; The host counter is UNTOUCHED by the runtime-db replace — that is
      ;; the whole point of the move.
      (is (= 3 (:nav-token-counter (nav-counters/counter-snapshot :rf/default)))
          "the host-side high-water mark survived the restore (NOT rewound)")

      ;; Navigate again. The fresh token must EXCEED every pre-restore token
      ;; — i.e. nav-4, NOT a recycled nav-1 / nav-2 / nav-3 that could
      ;; collide with the pre-restore in-flight "nav-1" continuation.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/D"])
      (let [fresh (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                          [:rf.runtime/routing :current :nav-token])]
        (is (= "nav-4" fresh)
            "post-restore navigation mints nav-4 — monotone past the high-water mark")
        (is (not (contains? #{"nav-1" "nav-2" "nav-3"} fresh))
            "the fresh token does NOT recycle any pre-restore value (the invariant the move protects)")))))

(deftest restore-rewinds-runtime-db-counter-would-recycle-without-the-move
  (testing "rf2-oosjmh control: demonstrate the bug class the move fixes —
            had the counter been IN the restored runtime-db, the restore
            would have rewound it and the next allocation would recycle a
            token. The host-side counter is consulted instead, so even a
            restored runtime-db carrying a STALE counter cannot drive a
            recycle"
    (rf/reg-route :route/x {:params [:map [:id :string]]} "/x/:id")
    (rf/dispatch-sync [:rf.route/transitioned "/x/1"])  ;; nav-1
    (rf/dispatch-sync [:rf.route/transitioned "/x/2"])  ;; nav-2

    ;; A maliciously-stale restore even plants an old counter value in the
    ;; runtime-db (a v1-shaped snapshot). The handler ignores it — it reads
    ;; the HOST counter — so no recycle.
    (frame/replace-runtime-db!
      :rf/default
      {:rf.runtime/routing {:current           {:route-id :route/x :params {:id "1"}
                                                :query {} :fragment nil
                                                :transition :idle :error nil
                                                :nav-token "nav-1"}
                            ;; stale counter planted in the restored slice
                            :nav-token-counter 1}})

    (rf/dispatch-sync [:rf.route/transitioned "/x/3"])
    ;; The planted runtime-db `:nav-token-counter 1` would have driven the
    ;; next alloc to "nav-2" if the allocator read runtime-db. It does NOT —
    ;; it reads the HOST high-water mark (2), so the next token is "nav-3".
    ;; This is the structural fix: the runtime-db counter is irrelevant.
    (is (= "nav-3" (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                           [:rf.runtime/routing :current :nav-token]))
        "the host high-water mark (2) drives the next alloc to nav-3, ignoring the planted stale runtime-db counter")
    (is (not= "nav-2" (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                              [:rf.runtime/routing :current :nav-token]))
        "the allocator did NOT consult the planted runtime-db counter (which would have minted nav-2)")
    ;; The commit handler never WRITES the counter to runtime-db — the only
    ;; runtime-db routing key it touches is :current (the slice). A stale
    ;; planted key is left as-is (the handler is not responsible for
    ;; scrubbing a malformed restore), but it carries no authority.
    (is (= 3 (:nav-token-counter (nav-counters/counter-snapshot :rf/default)))
        "the host high-water mark advanced 2 → 3 (the planted runtime-db value never fed it)")))

;; ---- :pending-navigation stays subscribable but is SSR-stripped ----------

(deftest pending-navigation-subscribable-and-pending-nav-counter-host-side
  (testing "rf2-oosjmh / D2: a blocked navigation writes :pending-navigation
            to runtime-db (subscribable via :rf/pending-navigation) and mints
            a pending-nav id from the HOST counter — leaving NO
            pending-nav-counter in runtime-db"
    (rf/reg-route :route/editor {:can-leave [:editor/can-leave?]} "/editor")
    (rf/reg-route :route/home {} "/home")
    ;; :can-leave returns false → BLOCK (the editor is dirty; the closed
    ;; contract reads literal false as "block").
    (rf/reg-sub :editor/can-leave? (fn [_ _] false))
    (rf/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
    (rf/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))

    ;; Land on the editor (active route with a blocking :can-leave).
    (rf/dispatch-sync [:rf.route/transitioned "/editor"])
    ;; Attempt to leave — the guard blocks → pending-navigation is written.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/home"}])

    (let [routing-rt (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing])
          pending    (rf/subscribe-once [:rf/pending-navigation] {:frame :rf/default})]
      (is (some? pending) ":pending-navigation is subscribable (stays in runtime-db)")
      (is (= "pn-1" (:id pending)) "the pending-nav id is minted from the host counter")
      (is (= :can-leave (:reason pending)) "the pending-nav slot carries the block reason")
      (is (contains? routing-rt :pending-navigation)
          ":pending-navigation IS a runtime-db key (D2 — must stay subscribable)")
      (is (not (contains? routing-rt :pending-nav-counter))
          "the pending-nav-counter is NOT a runtime-db key (it is host-side)"))
    (is (= 1 (:pending-nav-counter (nav-counters/counter-snapshot :rf/default)))
        "the pending-nav high-water mark lives in the host-side cache")))

(deftest pending-navigation-stripped-from-ssr-payload
  (testing "rf2-oosjmh / D2: :pending-navigation is stripped from the SSR
            hydration payload (fail-closed allowlist ships only :current),
            even though it stays in runtime-db for local subscription"
    (let [runtime-db {:rf.runtime/routing
                      {:current            {:route-id :route/editor}
                       :pending-navigation {:id "pn-1" :reason :can-leave}}}
          projected  (payload-policy/project-runtime-db runtime-db)]
      (is (= {:current {:route-id :route/editor}}
             (:rf.runtime/routing projected))
          "only :current rides the SSR wire; :pending-navigation is stripped")
      (is (not (contains? (:rf.runtime/routing projected) :pending-navigation))
          ":pending-navigation does NOT ride the hydration payload"))))

;; ---- one source of truth: classification ⇔ SSR allowlist -----------------

(deftest routing-classification-is-single-source-of-truth-for-ssr-allowlist
  (testing "rf2-oosjmh STRUCTURAL: the SSR durable-routing allowlist equals
            the routing-owned classification's :durable-runtime-db tier — so
            storage / SSR / docs can never silently drift"
    (is (= (vec payload-policy/durable-routing-keys)
           (vec nav-counters/durable-runtime-db-routing-keys))
        "SSR's durable-routing-keys == routing's durable-runtime-db-routing-keys")
    (is (= [:current] (vec nav-counters/durable-runtime-db-routing-keys))
        "the durable tier is exactly the active route slice :current")
    (let [c nav-counters/routing-state-classification]
      (is (= [:pending-navigation]
             (get-in c [:local-subscribable-runtime-db :keys]))
          ":pending-navigation is the local-subscribable runtime-db tier")
      (is (= #{:scroll-positions :nav-token-counter :pending-nav-counter}
             (set (get-in c [:host-transient :keys])))
          "scroll + the two counters are the host-transient tier"))))

;; ---- per-frame teardown drops the host counter ---------------------------

(deftest destroy-frame-releases-host-counter-entry
  (testing "rf2-oosjmh: destroying a frame releases its host-side nav-counter
            entry (the :routing/on-frame-destroyed! teardown, shared with the
            scroll cache) so a long-running per-request-frame process does
            not leak one counter entry per destroyed frame"
    (rf/reg-frame :rf.test/scratch {:url-bound? true})
    (rf/reg-route :route/s {} "/s")
    (rf/with-frame :rf.test/scratch
      (rf/dispatch-sync [:rf.route/transitioned "/s"]))
    (is (= 1 (:nav-token-counter (nav-counters/counter-snapshot :rf.test/scratch)))
        "the scratch frame accrued a host counter entry")
    (frame/destroy-frame! :rf.test/scratch)
    (is (= {} (nav-counters/counter-snapshot :rf.test/scratch))
        "the host counter entry is released on frame destroy")))
