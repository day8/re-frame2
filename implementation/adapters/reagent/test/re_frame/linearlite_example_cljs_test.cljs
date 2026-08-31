(ns re-frame.linearlite-example-cljs-test
  "Integration test: drives the Linearlite optimistic-board example
   (`examples/capabilities/resources/linearlite/`) through the EP-0019 OPTIMISTIC MUTATION +
   ROLLBACK composition it teaches — route entry ensures the `:linearlite/board`
   resource, the view reads it passively via `[:rf.resource/data …]`, and every
   write is a `reg-mutation` with an `:optimistic` exact-target patch over the
   board entry that COMMITS on `:ok` (`:populates`) and ROLLS BACK on `:error`
   (the runtime-recorded snapshot inverse). Closes the false-green gap the
   test-free examples policy (rf2-8cevm) leaves: `test:examples-compile` catches
   a missing namespace / init-fn and the resources artefact suites catch the
   generic optimistic-mutation runtime contract, but neither pins the
   EXAMPLE-SPECIFIC composition — its board resource + route ensure, its three
   `:optimistic` forward patches (create / edit-title / change-status), the
   `:populates` commit, and the failure rollback as the view reads them.

   The fixture fns + the deterministic transport stub live HERE (the adapter
   test tree), not under examples/capabilities/resources/linearlite/ — the example source stays
   test-free per the locked policy. The ns requires the example's production
   source (`linearlite.core`) so its resource, mutations, routes, events, subs,
   and views register at ns-load, then exercises them against a per-test
   `:rf/default` frame.

   DETERMINISM. The example's demo backend defers each canned reply 220 ms via
   `:after-ms` (`:dispatch-later`) so the optimistic value is observable in the
   browser. A unit test wants synchronous settle, so each test installs its own
   `:rf.http/managed` override: a capturing no-op whose reply the test replays
   explicitly via the transport's real 3-element reply-event-append shape
   (`(conj on-success {:status :ok :value …})`) — the genuine shape the live
   managed-HTTP transport produces (Spec 014 §Reply addressing). This bypasses
   the example's own fail-next-write seam (the test drives success vs failure by
   choosing which reply to replay), so the optimistic-apply / commit / rollback
   arcs run synchronously and deterministically. Routing's URL push is stubbed
   so navigation is deterministic without a browser.

   Mirrors `re-frame.infinite-feed-example-cljs-test` (its read-side sibling)
   for the ns-load snapshot/restore + trace-bus isolation discipline, snapshotting
   the `:mutation` registrar kind alongside `:resource` (the write counterpart)."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            ;; production HTTP + resources surfaces (so the resource runtime,
            ;; the mutation runtime, managed-HTTP lowering, and the late-bound
            ;; routing integration resolve); the actual fetch is overridden by
            ;; the capturing stub.
            [re-frame.http.managed]
            [re-frame.http.test-support]
            [re-frame.resources]
            [re-frame.resources.route :as resources-route]
            [re-frame.resources.state :as state]
            [re-frame.resources.test-support]
            [re-frame.routing :as routing]
            ;; the framework trace-ring buffer (Spec 009) — cleared around each
            ;; test so this resource/mutation-registering suite leaves no trace
            ;; residue for later cross-cutting tooling tests.
            [re-frame.trace.tooling :as trace-tooling]
            ;; the example's production source — registers its :linearlite/board
            ;; resource, the three optimistic mutations, routes, events, subs,
            ;; and views at ns-load.
            [linearlite.core]))

;; ============================================================================
;; FIXTURE
;; ============================================================================

(def ^:private last-managed-args (atom nil))

;; The shared `make-reset-runtime-fixture`'s post-dispose
;; `:resources/reset-resources!` hook CLEARS the `:resource` + `:mutation`
;; registrar kinds between tests. CLJS has no `(require … :reload)`, so snapshot
;; the example's ns-load registrations ONCE here (right after the
;; `linearlite.core` require above ran them) and re-install them in `init!`
;; (which runs AFTER the post-dispose hooks). This re-installs the EXACT example
;; registrations — not test-local copies. See the infinite-feed / realworld-
;; resources siblings for the full rationale.
(def ^:private resource-kind-snapshots
  (select-keys @registrar/kind->id->metadata [:resource :mutation]))

;; AT NS LOAD, remove THIS example's `:resource` / `:mutation` registrations (by
;; id) from the SHARED live registrar — after snapshotting them above. Reinstated
;; per-test by `init!`. Why: cljs.test loads every test ns into ONE bundle before
;; running ANY test, so without this our ns-load registrations sit in the global
;; registrar until some OTHER suite's reset clears them, mirroring a frameless
;; `:rf.registry/handler-cleared` burst into a cross-cutting tooling test's
;; cascade seed. We remove only OUR ids (not the whole kind).
(swap! registrar/kind->id->metadata
       (fn [reg]
         (reduce (fn [r [kind id->meta]]
                   (update r kind (fn [m] (apply dissoc m (keys id->meta)))))
                 reg
                 resource-kind-snapshots)))


;; rf2-h1vqa4 BUNDLE CO-LOAD HYGIENE: this app registers the reserved
;; per-app `:rf.route/not-found` route at ns load. Co-loaded example apps
;; each do the same, and two provenance rows for the id fail default-image
;; assembly loud for every suite whose fixture baseline is captured after
;; the second app loads. Sequester OUR app's row at ns load; `init!`
;; reinstates it (registrar + source store in lockstep) for this suite's
;; own tests.
(def ^:private not-found-route-row
  (test-support/sequester-app-registration!
    :route :rf.route/not-found "linearlite.core"))

(defn- init!
  "Per-test setup (after adapter install, registrar live). The linearlite
   example owns the URL through `:rf/default` (`:url-bound? true`), so
   re-register it that way, re-install the example's `:resource` / `:mutation`
   registrations the reset hook wiped, reset routing counters, re-publish the
   late-bound routing integration, and stub the managed-HTTP + url-push fx so
   route entry's ensure + navigation are deterministic without a fetch / browser.
   The capturing managed-HTTP override replaces the example's own demo stub, so
   the test drives success-vs-failure by choosing which reply to replay (rather
   than via the example's fail-next-write app-db seam)."
  []
  (test-support/reinstate-app-registration! not-found-route-row)
  (reset! last-managed-args nil)
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "linearlite-example default app frame."})
  ;; rf2-h1vqa4: reinstate through `registrar/register!` — NOT a raw
  ;; registrar-atom swap. Image-loaded frames resolve through the SOURCE
  ;; STORE (the default image is assembled from it), and the reset hook's
  ;; clear-kind! forgot the store rows too; register! writes registrar +
  ;; store in lockstep and marks the live-frame projection dirty, so the
  ;; frame's next resolution sees the reinstated registrations.
  (doseq [[kind id->meta] resource-kind-snapshots
          [id meta] id->meta]
    (registrar/register! kind id meta))
  (routing/reset-counters!)
  (resources-route/install-routing-integration!)
  (fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil)))

(defn- isolate-trace-bus-fixture
  "OUTER fixture: keep this resource/mutation-registering suite from leaking
   trace residue into later cross-cutting tooling tests (see the infinite-feed /
   realworld-resources siblings for the frameless-handler-cleared burst)."
  [f]
  (trace-tooling/clear-listeners!)
  (trace-tooling/clear-trace-rings!)
  (f)
  (trace-tooling/clear-listeners!)
  (trace-tooling/clear-trace-rings!))

(use-fixtures :each
  isolate-trace-bus-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn init!}))

;; ============================================================================
;; HELPERS
;; ============================================================================

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(def ^:private board-query
  {:resource :linearlite/board :scope :rf.scope/global :params {}})

(defn- board-key []
  (state/scoped-resource-key :rf.scope/global :linearlite/board {}))

(defn- entry [] (get-in (runtime-db) (state/entry-path (board-key))))

(defn- board-data
  "The passive board `:data` the example's view reads (the `:rf.resource/data`
   sub), computed against :rf/default's frame-state."
  []
  (rf/compute-sub [:rf.resource/data board-query]
                  (rf/frame-state-value :rf/default)))

(defn- issues [] (:issues (board-data)))

(defn- issue-by-id [id] (some #(when (= id (:id %)) %) (issues)))

(defn- mutation-state
  "The passive `[:rf/mutation {:instance …}]` view-model the example's
   card view reads (`{:pending? :success? :error? :settled? :optimistic? …}`)."
  [instance]
  (rf/compute-sub [:rf/mutation {:instance instance}]
                  (rf/frame-state-value :rf/default)))

(defn- reply-success!
  "Replay the captured `:on-success` with the result appended as the LAST arg —
   the exact shape the live managed-HTTP transport produces for an accepted
   mutation/resource reply (Spec 014 §Reply addressing)."
  ([data] (reply-success! @last-managed-args data))
  ([args data]
   (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data})
                     {:frame :rf/default})))

(defn- reply-failure!
  "Replay the captured `:on-failure` with the failure appended — the shape a
   503 produces, the example's fail-next-write seam exhibits in the browser."
  ([failure] (reply-failure! @last-managed-args failure))
  ([args failure]
   (rf/dispatch-sync (conj (:on-failure args) {:status :error :error failure})
                     {:frame :rf/default})))

(def ^:private demo-board
  "A canned board reply, the shape the example's stub returns for the read."
  {:issues [{:id "srv-1" :title "Alpha" :status :backlog}
            {:id "srv-2" :title "Beta"  :status :in-progress}]})

(defn- load-board!
  "Enter the board route and settle its first load with the canned board, so
   the optimistic writes have a live board entry to patch."
  []
  (rf/dispatch-sync [:rf.route/navigate {:to :linearlite.app/board}])
  (reply-success! demo-board)
  (reset! last-managed-args nil))

;; ============================================================================
;; 1. ROUTE ENTRY ensures the board resource (the route OWNS the read)
;; ============================================================================

(deftest board-route-entry-ensures-the-board-under-the-route-owner
  (testing "examples/capabilities/resources/linearlite — entering :linearlite.app/board ensures
            the :linearlite/board resource under the route nav-token owner; the
            view reads the passive board :data and settles to the issues on the
            reply (the route CAUSES the load; the view never asks)"
    (rf/dispatch-sync [:rf.route/navigate {:to :linearlite.app/board}])
    (let [slice     (get-in (runtime-db) [:rf.runtime/routing :current])
          nav-token (:nav-token slice)
          e         (entry)]
      (is (= :loading (:status e)) "first load → :loading")
      (is (contains? (:active-owners e) [:route :linearlite.app/board nav-token])
          "owned by the route nav-token owner [:route route-id nav-token]")
      (reply-success! demo-board)
      (is (= :loaded (:status (entry))) "settles :loaded on the reply")
      (is (= demo-board (board-data)) "the view reads the merged board :data"))))

;; ============================================================================
;; 2. OPTIMISTIC APPLY — the write shows on the board BEFORE the reply (phase 1.5)
;; ============================================================================

(deftest create-issue-applies-optimistically-before-the-reply
  (testing "examples/capabilities/resources/linearlite — :linearlite/create-issue applies its
            `:optimistic` forward patch at phase 1.5 (before the request lowers):
            the new Backlog card appears immediately and the watched mutation
            instance is :optimistic? (the derived Rider-1 flag) while pending"
    (load-board!)
    (rf/dispatch-sync [:linearlite/create-issue "Gamma"])
    ;; the optimistic apply ran before the request was sent.
    (let [tmp (some #(when (= "Gamma" (:title %)) %) (issues))]
      (is (some? tmp) "the new card appears in the board IMMEDIATELY (optimistic apply)")
      (is (= :backlog (:status tmp)) "the optimistic card lands in Backlog")
      (is (= 3 (count (issues))) "the board grew by one before any reply")
      (let [ms (mutation-state [:create (:id tmp)])]
        (is (true? (:pending? ms)) "the write is pending")
        (is (true? (:optimistic? ms)) ":optimistic? true while the apply is showing (Rider 1)"))
      (is (some? @last-managed-args) "the write lowered a request to the transport"))))

(deftest change-status-applies-optimistically-before-the-reply
  (testing "examples/capabilities/resources/linearlite — :linearlite/change-status moves the
            card to the new column IMMEDIATELY (optimistic apply), before the
            request settles"
    (load-board!)
    (rf/dispatch-sync [:linearlite/change-status "srv-1" :done])
    (is (= :done (:status (issue-by-id "srv-1")))
        "srv-1 jumped to :done the instant the move was dispatched (optimistic)")
    (is (true? (:optimistic? (mutation-state [:status "srv-1"])))
        "the change-status instance is :optimistic? while pending")))

;; ============================================================================
;; 3. SUCCESS COMMIT — the :ok reply re-seeds the board with the server's value
;; ============================================================================

(deftest successful-write-commits-the-server-board-via-populates
  (testing "examples/capabilities/resources/linearlite — an accepted :ok reply COMMITS: the
            mutation's `:populates` overwrites the optimistic board with the
            server's authoritative value (the temp id is replaced by the server
            id, the optimistic marker clears), and the instance settles :success"
    (load-board!)
    (rf/dispatch-sync [:linearlite/create-issue "Gamma"])
    (let [tmp-id   (:id (some #(when (= "Gamma" (:title %)) %) (issues)))
          ;; the server's authoritative board: the new issue carries a SERVER id.
          srv-board {:issues (conj (:issues demo-board)
                                   {:id "srv-3" :title "Gamma" :status :backlog})}]
      (is (string? tmp-id))
      ;; settle the write with the server board (the :populates payload).
      (reply-success! srv-board)
      (is (= srv-board (board-data))
          "the board is re-seeded with the server's authoritative value (commit)")
      (is (nil? (issue-by-id tmp-id)) "the temporary optimistic id is gone after commit")
      (is (some? (issue-by-id "srv-3")) "the committed issue carries the server id")
      (is (not-any? :optimistic? (issues)) "no card carries the optimistic marker after commit")
      (let [ms (mutation-state [:create tmp-id])]
        (is (true? (:success? ms)) "the instance settled :success")
        (is (false? (:optimistic? ms)) ":optimistic? false once settled (no longer pending)")))))

(deftest successful-edit-title-commits-the-new-title
  (testing "examples/capabilities/resources/linearlite — a successful :linearlite/edit-title
            commits the new title via :populates (the optimistic title is
            confirmed by the server's authoritative board)"
    (load-board!)
    (rf/dispatch-sync [:linearlite/commit-edit "srv-2" "Beta!"])
    (is (= "Beta!" (:title (issue-by-id "srv-2"))) "optimistic title shows immediately")
    (let [srv-board {:issues [{:id "srv-1" :title "Alpha" :status :backlog}
                              {:id "srv-2" :title "Beta!" :status :in-progress}]}]
      (reply-success! srv-board)
      (is (= "Beta!" (:title (issue-by-id "srv-2"))) "the committed title persists")
      (is (true? (:success? (mutation-state [:edit "srv-2"]))) "the edit instance settled :success"))))

;; ============================================================================
;; 4. FAILURE ROLLBACK — the :error reply reverts the optimistic change (the headline)
;; ============================================================================

(deftest failed-create-rolls-the-optimistic-card-back-out
  (testing "examples/capabilities/resources/linearlite — THE DEMO HEADLINE: an accepted :error
            reply ROLLS BACK the optimistic apply. The runtime restores the
            recorded snapshot inverse, so the optimistically-added card VANISHES
            from the board and the instance settles :error (no manual undo)"
    (load-board!)
    (rf/dispatch-sync [:linearlite/create-issue "Doomed"])
    (let [tmp-id (:id (some #(when (= "Doomed" (:title %)) %) (issues)))]
      (is (= 3 (count (issues))) "the optimistic card was added (board grew by one)")
      ;; the request FAILS (a 503 — the demo's fail-next-write seam).
      (reply-failure! {:kind :rf.http/http-5xx :status 503})
      (is (= 2 (count (issues))) "the optimistic card was rolled back OUT (board restored)")
      (is (nil? (issue-by-id tmp-id)) "the never-committed card is gone")
      (is (= demo-board (board-data)) "the board is restored to exactly its pre-write value")
      (let [ms (mutation-state [:create tmp-id])]
        (is (true? (:error? ms)) "the instance settled :error")
        (is (false? (:optimistic? ms)) ":optimistic? false after rollback (no live apply)")))))

(deftest failed-change-status-snaps-the-card-back-to-its-prior-column
  (testing "examples/capabilities/resources/linearlite — a failed :linearlite/change-status
            rolls back: the card snaps back to its prior column (the recorded
            `:before` is restored verbatim)"
    (load-board!)
    (rf/dispatch-sync [:linearlite/change-status "srv-1" :done])
    (is (= :done (:status (issue-by-id "srv-1"))) "optimistic move applied")
    (reply-failure! {:kind :rf.http/http-5xx :status 503})
    (is (= :backlog (:status (issue-by-id "srv-1")))
        "the card snapped back to its prior :backlog column on failure (rollback)")
    (is (= demo-board (board-data)) "the whole board is restored to its pre-move value")
    (is (true? (:error? (mutation-state [:status "srv-1"]))) "the instance settled :error")))

(deftest failed-edit-title-reverts-to-the-prior-title
  (testing "examples/capabilities/resources/linearlite — a failed :linearlite/edit-title reverts
            the optimistic title to the prior value on rollback"
    (load-board!)
    (rf/dispatch-sync [:linearlite/commit-edit "srv-2" "Wrong"])
    (is (= "Wrong" (:title (issue-by-id "srv-2"))) "optimistic title applied")
    (reply-failure! {:kind :rf.http/http-5xx :status 503})
    (is (= "Beta" (:title (issue-by-id "srv-2"))) "the title reverted to its prior value (rollback)")
    (is (= demo-board (board-data)) "the board is restored to its pre-edit value")))

;; ============================================================================
;; 5. THE ARMED DEMO BACKEND — fail-next-write answers a REAL 503 (rf2-pqt5f)
;; ============================================================================
;;
;; Everything above BYPASSES the example's own demo backend (the capturing
;; override replays hand-built replies), so it proves the generic mutation
;; arcs, not the runnable backend users execute. These two tests route
;; `:rf.http/managed` at the ACTUAL registered `:linearlite.demo/http-stub` —
;; the same routing the example's mount `:fx-overrides` does in the browser —
;; and pin the armed path's failure CLASSIFICATION: "Fail the next write"
;; must settle through the canonical 503 envelope the example says it
;; synthesises ({:kind :rf.http/http-5xx :status 503 :message …} via the
;; canned-failure contract's top-level `:kind` + `:tags`), never the
;; contract's silent default {:kind :rf.http/transport}, while keeping the
;; visible optimistic-apply → rollback arc.
;;
;; DETERMINISM. The demo stub defers each reply 220 ms via `:after-ms`, whose
;; deferral rides the framework `:dispatch-later` (the documented override
;; seam). We override `:dispatch-later` to PARK the deferred deliverer event
;; instead of arming a timer — which both keeps the optimistic window
;; observable (the "before settle" assertions) and lets the test settle each
;; reply synchronously, in order, by re-dispatching what was parked.

(def ^:private parked-later (atom []))

(defn- use-real-demo-stub!
  "Point `:rf.http/managed` at the example's REAL registered demo stub
   (capturing each request into `last-managed-args` on the way through), and
   park the stub's deferred replies off the `:dispatch-later` seam."
  []
  (reset! parked-later [])
  (fx/reg-fx :rf.http/managed
    (fn [frame-ctx args]
      (reset! last-managed-args args)
      ((registrar/handler :fx :linearlite.demo/http-stub) frame-ctx args)))
  (fx/reg-fx :dispatch-later
    (fn [frame-ctx {:keys [event]}]
      (swap! parked-later conj {:frame (or (:frame frame-ctx) :rf/default)
                                :event event})
      nil)))

(defn- flush-parked-replies!
  "Settle every parked deferred reply, in the order the demo stub issued
   them (each parked event is the framework's canned-reply deliverer with
   `:after-ms` already stripped, so re-dispatching it synthesises the reply
   immediately)."
  []
  (let [parked @parked-later]
    (reset! parked-later [])
    (doseq [{:keys [frame event]} parked]
      (rf/dispatch-sync event {:frame frame}))))

(defn- load-board-via-real-stub!
  "Enter the board route and settle its first load through the ACTUAL demo
   stub — the board the tests below see is the example's own canonical
   `demo-board`, not this suite's canned one."
  []
  (rf/dispatch-sync [:rf.route/navigate {:to :linearlite.app/board}])
  (flush-parked-replies!)
  (reset! last-managed-args nil))

(defn- fail-next-write? []
  (rf/compute-sub [:linearlite/fail-next-write?]
                  (rf/frame-state-value :rf/default)))

(deftest armed-fail-next-write-settles-a-classified-503-through-the-real-stub
  (testing "examples/capabilities/resources/linearlite — arming 'Fail the next
            write' and executing one real write settles through the demo
            backend's promised 503 envelope: exact rollback, one-shot disarm,
            and a mutation :error of {:kind :rf.http/http-5xx :status 503 …} —
            not the canned default :rf.http/transport"
    (use-real-demo-stub!)
    (load-board-via-real-stub!)
    (is (= :loaded (:status (entry))) "the real stub's deferred read settled the board")
    (let [pre-write (board-data)
          id        (:id (first (:issues pre-write)))
          target    (if (= :done (:status (issue-by-id id))) :backlog :done)]
      (rf/dispatch-sync [:linearlite/set-fail-next-write true])
      (is (true? (fail-next-write?)) "armed")
      ;; One REAL example write — the same event the board's UI dispatches.
      (rf/dispatch-sync [:linearlite/change-status id target])
      ;; BEFORE the deferred reply settles: the write reached the demo stub
      ;; and the optimistic change is showing.
      (is (= :put (get-in @last-managed-args [:request :method]))
          "the write's request lowered through the demo stub")
      (is (= target (:status (issue-by-id id)))
          "the optimistic move is showing while the reply is still parked")
      (is (true? (:optimistic? (mutation-state [:status id])))
          "the instance is :optimistic? while pending")
      (is (seq @parked-later)
          "the ARMED stub parked a deferred reply (it answered, deferred)")
      (is (false? (fail-next-write?))
          "the one-shot flag disarmed at request time")
      ;; SETTLE the parked 503.
      (flush-parked-replies!)
      (is (= pre-write (board-data))
          "the board equals its EXACT pre-write value (rollback)")
      (let [ms (mutation-state [:status id])]
        (is (true? (:error? ms)) "the instance settled terminal :error")
        (is (false? (:optimistic? ms)) "no live optimistic apply remains")
        (is (= :rf.http/http-5xx (get-in ms [:error :kind]))
            "the stored public mutation :error is classified :rf.http/http-5xx —
             the http-5xx branch of the closed failure taxonomy, not the
             canned default :rf.http/transport")
        (is (= 503 (get-in ms [:error :status])) "…carrying the promised HTTP 503")
        (is (= "Simulated server failure (the demo's rollback seam)."
               (get-in ms [:error :message]))
            "…and the demo's message"))
      (is (false? (fail-next-write?)) "still disarmed after settle (one-shot)"))))

(deftest unarmed-write-through-the-real-stub-commits
  (testing "examples/capabilities/resources/linearlite — positive control: an
            otherwise-equivalent UNARMED write through the same actual demo
            stub settles :success and commits, proving the armed test selects
            the armed branch rather than passing because every request fails
            or no reply ran"
    (use-real-demo-stub!)
    (load-board-via-real-stub!)
    (let [id       (:id (first (issues)))
          original (:status (issue-by-id id))
          target   (if (= :done original) :backlog :done)]
      (rf/dispatch-sync [:linearlite/change-status id target])
      (is (= target (:status (issue-by-id id))) "optimistic move applied")
      (is (seq @parked-later) "the stub parked a deferred success reply")
      (flush-parked-replies!)
      (is (= target (:status (issue-by-id id)))
          "the move COMMITTED — the server board confirmed it")
      (let [ms (mutation-state [:status id])]
        (is (true? (:success? ms)) "the instance settled :success")
        (is (nil? (:error ms)) "no failure was stored"))
      ;; Compensating write: put the issue's status back so the example's
      ;; module-level canonical `demo-board` atom leaves this test exactly as
      ;; it entered (the demo stub *is* the server, and its board is defonce'd
      ;; across the bundle).
      (rf/dispatch-sync [:linearlite/change-status id original])
      (flush-parked-replies!)
      (is (= original (:status (issue-by-id id)))
          "the compensating write restored the canonical board"))))
