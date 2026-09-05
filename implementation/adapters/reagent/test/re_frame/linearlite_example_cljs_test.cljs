(ns re-frame.linearlite-example-cljs-test
  "Integration test: drives the Linearlite optimistic-board example
   (`examples/capabilities/resources/linearlite/`) through the EP-0019 OPTIMISTIC MUTATION +
   ROLLBACK composition it teaches — route entry ensures the `:linearlite/board`
   resource, the view reads it passively via `[:rf.resource/data …]`, and every
   write is a `reg-mutation` with an `:optimistic` exact-target patch over the
   board entry that COMMITS on `:ok` (`:patches`) and ROLLS BACK on `:error`
   (the runtime-recorded snapshot inverse). Closes the false-green gap the
   test-free examples policy (rf2-8cevm) leaves: `test:examples-compile` catches
   a missing namespace / init-fn and the resources artefact suites catch the
   generic optimistic-mutation runtime contract, but neither pins the
   EXAMPLE-SPECIFIC composition — its board resource + route ensure, its three
   `:optimistic` forward patches (create / edit-title / change-status), the
   `:patches` commit, and the failure rollback as the view reads them.

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
            [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.registrar :as registrar]
            ;; the provenance store behind the registrar — section 0's control
            ;; reads it to find WHICH namespace owns each rival "/" route row,
            ;; so it can drop them through the ownership-guarded two-leg pairing
            ;; below rather than by a raw registrar write.
            [re-frame.source-store :as source-store]
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

(defn- init!
  "Per-test setup (after adapter install, registrar live). The linearlite
   example owns the URL through `:rf/default` (`:url-bound? true`), so
   re-register it that way, re-install the example's `:resource` / `:mutation`
   registrations the reset hook wiped, reset routing counters, re-publish the
   late-bound routing integration, and stub the managed-HTTP + url-push fx so
   route entry's ensure + navigation are deterministic without a fetch / browser.
   The capturing managed-HTTP override replaces the example's own demo stub, so
   the test drives success-vs-failure by choosing which reply to replay (rather
   than via the example's fail-next-write app-db seam).

   THE ORDER MATTERS, AND THE FRAME IS MADE LAST (rf2-k4oe). A `:url-bound?`
   frame performs a synchronous initial URL sync AT CONSTRUCTION — `make-frame`
   -> `frame/upsert-frame!`'s post-create hook -> routing's
   `:routing/on-frame-registered!` -> `reconcile-url-listener!` — and under Node
   that URL is `\"/\"`, which is where this example registers
   `:linearlite.app/board` with a BLOCKING `:linearlite/board` route resource.
   So the route-entry resource plan runs INSIDE `make-frame`. Construct the
   frame before the `registrar/register!` loop below and that plan finds the
   `:resource` kind still empty (the reset hook cleared it), records
   `:transition :error` / `:rf.error/resource-route-plan` on the routing slice,
   and — because the suite's own `navigate` never re-plans — the error is
   STICKY: 16 of these 51 assertions read nil, across 7 of the 10 tests.

   In the consolidated `:node-test` bundle that failure is INVISIBLE, which is
   why it stood: seven in-tree example apps register a route at `\"/\"`, so `\"/\"`
   resolves to a co-loaded SIBLING's route and this example's board resource is
   never planned at construction. The suite was therefore green on account of
   who it sat beside rather than on account of its own subject. Registering
   everything first and making the frame last removes that dependence entirely,
   and matches the committed pilot baseline
   (`docs/design/hicasso/product/pilots/baseline/linearlite/baseline_test.cljs`).
   Verified by building this suite in a single-app bundle: 16 failures before,
   0 after."
  []
  (reset! last-managed-args nil)
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
  (fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  ;; LAST — see the ordering note in the docstring. Everything the frame's
  ;; construction-time URL sync needs (the reinstated `:resource` / `:mutation`
  ;; rows, the routing integration, the stubbed fx) is registered above.
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "linearlite-example default app frame."}))

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
    ;; BUNDLE CO-LOAD HYGIENE: this app registers the reserved per-app
    ;; `:rf.route/not-found` route at ns load, and every co-loaded example app
    ;; does the same — two provenance rows for one id fail default-image
    ;; assembly loud for any suite whose baseline is captured after the second
    ;; app loads. `:app-ns` names OUR OWN app (never a sibling's): the fixture
    ;; keeps its rows out of every suite's baseline and reinstates them for
    ;; this suite's own tests (rf2-kuky.27).
    {:adapter reagent-adapter/adapter
     :app-ns  "linearlite."
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

(defn- issue-by-title
  "The card carrying `title`. A newly created card is found by title rather
   than by id, because its id changes underneath it — the client-minted `tmp-N`
   placeholder gives way to the server's on commit — while the title is the one
   thing that survives the round trip unchanged."
  [title]
  (some #(when (= title (:title %)) %) (issues)))

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
;; 0. CO-LOAD ISOLATION CONTROL (rf2-k4oe) — the witness this suite lacked
;; ============================================================================

;; EVERY test below section 0 is green in this bundle whether or not `init!`
;; makes the frame last. Seven co-loaded example apps also register a route at
;; "/", so one of THEM answers the URL sync `make-frame` performs at
;; construction and this app's blocking board resource is never planned there.
;; That is the false green rf2-k4oe was filed for, and it survived the fix:
;; measured on the pre-#9027 ordering in the consolidated `:node-test` bundle,
;; sections 1-9 still read 10 tests / 51 assertions / 0 failures. Moving
;; `make-frame` back up left CI green.
;;
;; This test is the missing witness, and it does NOT build a second bundle —
;; the fifteen single-app builds that found the defect were audit evidence, too
;; expensive to keep. It reproduces IN PROCESS the two facts a single-app bundle
;; would have supplied:
;;
;;   (1) "/" is answered by THIS app's board route ALONE. Every rival row is
;;       dropped through the local, ownership-guarded two-leg pairing below
;;       (`drop-route-row!`), and restored in a `finally`.
;;   (2) The `:resource` / `:mutation` kinds are EMPTY when `init!` starts —
;;       the state the shared fixture's post-dispose reset hook really leaves,
;;       and which refilling is `init!`'s whole job.
;;
;; It then runs the REAL `init!` against a cleared frame registry, so
;; `make-frame` genuinely CONSTRUCTS and its post-create URL sync really runs.
;; Register-then-make-frame lands `:linearlite.app/board` with its board
;; resource planned; make-frame-then-register lands `:transition :error` /
;; `:rf.error/resource-route-plan`. Calling `init!` itself rather than a copy of
;; it is the point: a refactor that puts the frame back in front of the
;; registrar reinstatement, by any route, fails HERE.
;;
;; The first assertion is the control's own positive control. If the rival
;; removal ever silently stops working — a route row that moves, a `:path`
;; key that is renamed — the URL sync lands on a sibling's route id and this
;; test fails LOUD, rather than passing while checking nothing.

(defn- root-path-rivals
  "The `[route-id provenance-ns]` source slots of every OTHER app that also owns
   \"/\" in this consolidated bundle."
  []
  (for [[id metadata] (registrar/registrations :route)
        :when         (and (= "/" (:path metadata))
                           (not= :linearlite.app/board id))
        provenance-ns (keys (source-store/descriptors-for :route id))]
    [id provenance-ns]))

(defn- drop-route-row!
  "Remove ONE rival `:route` row for `(id, provenance-ns)` from the live
   registrar AND the provenance source store, returning the captured
   source-store descriptor (or nil when the slot is absent).

   LOCAL TO THIS TEST, deliberately (rf2-kuky.27). Removing a RIVAL app's
   registrations to reproduce a single-app bundle in process is a different job
   from the fixture's `:app-ns`, which hides a suite's OWN app so no sibling's
   baseline sees it. Only this one control needs the rival-removal shape, so it
   stays a private helper here rather than a public verb every conforming port
   would have to carry.

   Both legs, and the registrar leg ownership-guarded, for the same reasons the
   fixture's own remover has them: an ABSENT source row is a true no-op (so a
   requested slot that never registered cannot clobber whichever namespace DID),
   and the registrar's single `(id)` slot is the LAST writer's, so it is dropped
   only when that writer IS `provenance-ns`. `registrar/unregister!` would be
   wrong here — it forgets EVERY provenance slot for the id."
  [id provenance-ns]
  (when-let [row (get-in @source-store/kind->id->ns->descriptor
                         [:route id provenance-ns])]
    (swap! registrar/kind->id->metadata update :route
           (fn [m]
             (let [cur    (get m id)
                   cur-ns (or (get cur source-store/provenance-ns-key)
                              (some-> (:ns cur) str))]
               (if (and cur (= cur-ns (str provenance-ns)))
                 (dissoc m id)
                 m))))
    (source-store/forget-descriptor! :route id provenance-ns)
    row))

(defn- restore-route-row!
  "Put a descriptor captured by [[drop-route-row!]] back through
   `registrar/register!` — registrar + source store in lockstep, because an
   image-loaded frame resolves through the STORE and a raw registrar-atom write
   would be invisible to its generation. No-op on nil."
  [descriptor]
  (when descriptor
    (registrar/register! (:kind descriptor) (:id descriptor) descriptor))
  nil)

(deftest init-refills-the-registrar-before-it-makes-the-url-bound-frame
  (testing "examples/capabilities/resources/linearlite — with \"/\" owned by this app alone and
            the :resource/:mutation kinds cleared (a single-app bundle's two
            conditions, reproduced in process), init!'s construction-time URL
            sync plans the blocking :linearlite/board resource cleanly. This is
            the assertion the consolidated bundle cannot make for itself"
    (let [removed (atom [])]
      (try
        (doseq [[id provenance-ns] (root-path-rivals)]
          (when-let [row (drop-route-row! id provenance-ns)]
            (swap! removed conj row)))
        (registrar/clear-kind! :resource)
        (registrar/clear-kind! :mutation)
        (reset! frame/frames {})
        (init!)
        (let [slice (get-in (runtime-db) [:rf.runtime/routing :current])]
          (is (= :linearlite.app/board (:route-id slice))
              "POSITIVE CONTROL: the rival \"/\" rows really are gone, so the
               frame's construction-time URL sync lands on THIS app's board
               route — the single-app condition the rest of this test needs")
          (is (not= :error (:transition slice))
              "the construction-time route-entry resource plan SUCCEEDED: init!
               refilled the :resource registrar before it made the :url-bound?
               frame (rf2-k4oe). A :transition :error here means make-frame ran
               first and the plan found the kind empty")
          (is (nil? (:rf.error/id (:error slice)))
              "and no :rf.error/resource-route-plan is stuck on the slice — the
               error is STICKY, which is why the suite's own navigate never
               clears it"))
        (finally
          (run! restore-route-row! @removed)
          (reset! frame/frames {}))))))

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
;; 3. SUCCESS COMMIT — the :ok reply folds in the value the server saved
;; ============================================================================

(deftest successful-create-commits-the-servers-row-via-patches
  (testing "examples/capabilities/resources/linearlite — an accepted :ok reply COMMITS: the
            mutation's `:patches` folds the server's own row over the
            optimistic card (the temp id is replaced by the server
            id, the optimistic marker clears), and the instance settles :success"
    (load-board!)
    (rf/dispatch-sync [:linearlite/create-issue "Gamma"])
    (let [tmp-id   (:id (some #(when (= "Gamma" (:title %)) %) (issues)))
          ;; the server's authoritative board: the new issue carries a SERVER id.
          srv-board {:issues (conj (:issues demo-board)
                                   {:id "srv-3" :title "Gamma" :status :backlog})}]
      (is (string? tmp-id))
      ;; settle the write with the server board (the :patches payload).
      (reply-success! srv-board)
      (is (= srv-board (board-data))
          "the server's row is folded into the board (commit)")
      (is (nil? (issue-by-id tmp-id)) "the temporary optimistic id is gone after commit")
      (is (some? (issue-by-id "srv-3")) "the committed issue carries the server id")
      (is (not-any? :optimistic? (issues)) "no card carries the optimistic marker after commit")
      (let [ms (mutation-state [:create tmp-id])]
        (is (true? (:success? ms)) "the instance settled :success")
        (is (false? (:optimistic? ms)) ":optimistic? false once settled (no longer pending)")))))

(deftest successful-edit-title-commits-the-new-title
  (testing "examples/capabilities/resources/linearlite — a successful :linearlite/edit-title
            commits the new title via :patches (the optimistic title is
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
;; Everything above BYPASSES the example's own demo backend: the capturing
;; `:rf.http/managed` override replays hand-built replies, so those tests prove
;; the generic mutation arcs (a `{:kind :rf.http/http-5xx :status 503}` failure
;; rolls back exactly and lands in the instance's `:error`) — but NOT that the
;; runnable backend users actually execute ever PRODUCES such a failure. That
;; gap is where rf2-pqt5f lived: the armed "Fail the next write" branch built
;; its 503 under a `:failure` key `:rf.http/managed-canned-failure` does not
;; read, so the demo's advertised 503 silently settled as the contract's
;; default `{:kind :rf.http/transport}`. Rollback still worked, which is
;; exactly why nothing caught it.
;;
;; These two tests close that gap at the seam the bug lived on: they drive the
;; ACTUAL registered `:linearlite.demo/http-stub` and pin WHICH framework
;; canned-reply fx it selects and WITH WHAT ARGS. The stub's whole observable
;; act is that selection plus those args — it resolves the canned fx from the
;; registrar at call time and hands off — so capturing them captures the
;; classification decision itself: top-level `:kind :rf.http/http-5xx` +
;; `:tags {:status 503 :message …}`, the shape the canned-failure contract
;; (Spec 014 §Testing) actually reads, never the pre-fix `:failure` spelling.
;;
;; SCOPE BOUNDARY, stated rather than papered over. These tests stop at the
;; stub's emitted args; they do not carry the reply on into the mutation
;; runtime. The demo stub defers every reply via `:after-ms`, whose hop rides
;; an ASYNC router dispatch (`with-after-ms` → `:rf.http/deliver-canned-reply`
;; → `:dispatch-later`), and that deferral is not reachable from a synchronous
;; test body: `:dispatch-later` is an overridable-tier RESERVED fx-id, so a
;; keyword redirect (the wiring style the example's own mount uses for
;; `:rf.http/managed`) is inert against it — reserved ids resolve through
;; core's reserved table, never the registrar — and the fn-value override that
;; DOES pre-empt the reserved body still leaves the deliverer's first hop on
;; the async queue, which a `dispatch-sync` body never drains. Settling it
;; would need an `(async done …)` suite built on `test-support/poll-until`,
;; which buys nothing here: the two halves of the claim are each already
;; pinned — the runtime's handling of a classified 503 by tests 3-4 above, and
;; the stub's PRODUCTION of one by the tests below. Their composition is the
;; example's own runnable behaviour, exercised in the browser.
;;
;; The parkers are installed by per-test re-registration of the two canned fx
;; ids — the same mechanism `init!` already uses for `:rf.http/managed` — and
;; the runtime-reset fixture restores the ns-load registrar baseline after
;; every test.

(def ^:private parked-replies (atom []))

(def ^:private canned-fx-ids
  [:rf.http/managed-canned-success :rf.http/managed-canned-failure])

(defn- install-canned-parkers!
  "Re-register the two framework canned-reply fx ids with parkers that RECORD
   the demo stub's answer — which fx it chose, the deferral it asked for, and
   the args it built — and deliver nothing."
  []
  (doseq [fx-id canned-fx-ids]
    (fx/reg-fx fx-id
      {:doc "linearlite armed-demo-backend test parker (per-test; section 5)."}
      (fn [_ctx args]
        (swap! parked-replies conj {:fx-id    fx-id
                                    :after-ms (:after-ms args)
                                    :args     (dissoc args :after-ms)})
        nil))))

(defn- ask-the-demo-stub!
  "Call the example's REAL registered demo-backend fx exactly as the framework
   would — `(handler frame-ctx args-map)` with the frame stamp the fx contract
   carries — and return what it parked. `request` is the lowered request map
   the example's own mutation produces."
  [request]
  (reset! parked-replies [])
  ((registrar/handler :fx :linearlite.demo/http-stub)
   {:frame :rf/default}
   {:request  request
    :decode   :json
    :on-success [:linearlite.test/reply]
    :on-failure [:linearlite.test/reply]})
  @parked-replies)

(defn- change-status-request
  "The request `:linearlite/change-status` lowers to — PUT /api/issues/:id with
   a `{:status …}` body — read off the mutation's own request fn."
  [id status]
  {:method :put :url (str "/api/issues/" id) :body {:status status}})

(defn- server-board
  "The example's own canonical server board, read the only way a client can:
   ask the demo backend for it. (The example's `demo-board` atom is private to
   `linearlite.core`, and this suite's same-named constant is an unrelated
   canned read used by the sections above.)"
  []
  (:value (:args (first (ask-the-demo-stub! {:method :get :url "/api/board"})))))

(defn- server-issue [board id]
  (some #(when (= id (:id %)) %) (:issues board)))

(defn- fail-next-write? []
  (rf/compute-sub [:linearlite/fail-next-write?]
                  (rf/frame-state-value :rf/default)))

(deftest armed-demo-backend-answers-a-classified-503
  (testing "examples/capabilities/resources/linearlite — with 'Fail the next
            write' armed, the example's REAL demo backend answers a write
            through the framework's canned-FAILURE fx carrying the promised
            503 in the shape that contract reads: top-level
            :kind :rf.http/http-5xx plus :tags {:status 503 :message …}.
            Pre-fix the 503 rode a `:failure` key the contract ignores, so the
            demo's advertised 503 silently classified as the default
            :rf.http/transport (rf2-pqt5f)."
    (install-canned-parkers!)
    (rf/dispatch-sync [:linearlite/set-fail-next-write true])
    (is (true? (fail-next-write?)) "armed")
    (let [before (server-board)
          parked (ask-the-demo-stub! (change-status-request "srv-1" :done))
          {:keys [fx-id after-ms args]} (first parked)]
      (is (= 1 (count parked)) "the stub answered exactly once")
      (is (= :rf.http/managed-canned-failure fx-id)
          "the ARMED branch was selected — the write met the canned-FAILURE fx")
      (is (pos? after-ms)
          "…deferred, as the demo does so the optimistic value paints first")
      ;; THE REGRESSION. The canned-failure contract reads a TOP-LEVEL :kind
      ;; and merges :tags into the classified failure map. Pre-fix both were
      ;; absent (the 503 rode under :failure), so `emit-canned-failure!`
      ;; defaulted :kind to :rf.http/transport.
      (is (= :rf.http/http-5xx (:kind args))
          "the failure is classified :rf.http/http-5xx — the http-5xx branch of
           the closed taxonomy, not the contract's default :rf.http/transport")
      (is (= 503 (get-in args [:tags :status])) "…carrying the promised HTTP 503")
      (is (= "Simulated server failure (the demo's rollback seam)."
             (get-in args [:tags :message]))
          "…and the demo's own message")
      (is (nil? (:failure args))
          "no vestigial `:failure` key — the pre-fix spelling the contract
           never read is gone, not merely shadowed")
      ;; The armed branch answers BEFORE touching the canonical board, so the
      ;; value the next read returns is untouched — the server-side half of the
      ;; rollback the mutation runtime performs client-side.
      (is (= before (server-board))
          "the canonical server board is unchanged by the refused write"))))

(deftest unarmed-demo-backend-answers-success-and-commits
  (testing "examples/capabilities/resources/linearlite — positive control: an
            otherwise-identical UNARMED write through the same real demo
            backend selects the canned-SUCCESS fx and returns the mutated
            authoritative board, proving the armed test above selects the
            armed branch rather than passing because every request fails"
    (install-canned-parkers!)
    (is (false? (fail-next-write?)) "unarmed (the fixture's fresh app-db)")
    (let [original (:status (server-issue (server-board) "srv-1"))
          target   (if (= :done original) :backlog :done)
          parked   (ask-the-demo-stub! (change-status-request "srv-1" target))
          {:keys [fx-id after-ms args]} (first parked)]
      (is (= 1 (count parked)) "the stub answered exactly once")
      (is (= :rf.http/managed-canned-success fx-id)
          "the UNARMED branch was selected — the write met the canned-SUCCESS fx")
      (is (pos? after-ms) "…deferred, like every reply this demo makes")
      (is (nil? (:kind args))
          "a success reply carries no failure classification")
      (is (= target (:status (server-issue (:value args) "srv-1")))
          "the authoritative board it returns carries the write applied — the
           value that becomes the mutation's :patches payload")
      ;; Compensating write: the demo stub *is* the server and its board is a
      ;; module-level `defonce` shared across the bundle, so put the issue back
      ;; the way this test found it.
      (ask-the-demo-stub! (change-status-request "srv-1" original))
      (is (= original (:status (server-issue (server-board) "srv-1")))
          "the compensating write restored the canonical board"))))


;; ============================================================================
;; 6. OVERLAPPING WRITES (rf2-9man) — independent instances must not clobber
;; ============================================================================
;;
;; The board's three writes run under SEPARATE mutation instances
;; (`[:create tmp-id]`, `[:edit id]`, `[:status id]`) and the controls stay live
;; while a write is in flight, so two of them are routinely outstanding over the
;; ONE board entry at once. The runtime's stale-reply suppression does not order
;; them: it is scoped to a single instance's re-execute, which is a different
;; question from two instances writing the same entry. Nothing on the client can
;; order those, so keeping their consequences disjoint is the example's job.
;;
;; THE DEFECT THESE PIN (rf2-9man). All three mutations used to commit with
;; `:populates`, seeding the whole board entry from that write's reply. A reply
;; is a snapshot of the server as it stood when THAT write landed and knows
;; nothing of a write that started a moment later, so committing it threw the
;; other write's change away — and the board settled on whichever reply arrived
;; last, which is exactly what the example's README promises it does not do.
;;
;; DETERMINISM — THERE IS NO TIMING IN THESE TESTS. The example's demo backend
;; defers every reply 220 ms (`:after-ms` -> `:dispatch-later`), which would make
;; an overlap a race. The seam that removes it is the one `init!` already
;; installs for the whole suite: `:rf.http/managed` is overridden with a
;; CAPTURING NO-OP that records the lowered args and delivers nothing. So a write
;; can never settle itself. Both writes are dispatched first and park their
;; requests; the test then replays each reply itself through `reply-success!`, a
;; synchronous `dispatch-sync`, in whichever order it is testing. The overlap is
;; structural rather than temporal — no timers, no `async`, no polling, and the
;; settle order is chosen by the test rather than observed. `last-managed-args`
;; is read and cleared between the two dispatches so each captured request is
;; unambiguously its own write's.
;;
;; The replies are deliberately WHOLE-BOARD envelopes — the coarse shape a server
;; that only answers with snapshots returns, and the shape the pre-fix example
;; seeded the entry from. A commit that patches only what it wrote picks its own
;; row out of that envelope and leaves every other card alone; a commit that
;; seeds from it swallows the whole stale snapshot. That difference is what these
;; two tests measure, and they measure it through the example's own passive
;; `:rf.resource/data` board — the value the view renders — not through the
;; mutation's registration shape, so they stay honest across a re-spelling.

(deftest settling-one-write-leaves-another-instances-change-standing
  (testing "examples/capabilities/resources/linearlite — two writes under
            DISTINCT instances are outstanding over the one board entry, and the
            first to settle carries a reply built BEFORE the second was
            dispatched. Committing it must not erase the second write's
            still-pending optimistic change, and once both have settled both
            accepted changes must stand (rf2-9man)"
    (load-board!)
    ;; A — retitle srv-1. Lowered, captured, and left unsettled.
    (rf/dispatch-sync [:linearlite/commit-edit "srv-1" "Alpha!"])
    (let [args-a @last-managed-args]
      (reset! last-managed-args nil)
      ;; B — move srv-2, dispatched while A is still in flight.
      (rf/dispatch-sync [:linearlite/change-status "srv-2" :done])
      (let [args-b  @last-managed-args
            ;; A's reply: the server as it stood when A landed. B had not reached
            ;; it, so srv-2 is still :in-progress in this snapshot.
            reply-a {:issues [{:id "srv-1" :title "Alpha!" :status :backlog}
                              {:id "srv-2" :title "Beta"   :status :in-progress}]}
            ;; B's reply: the server with both writes applied.
            reply-b {:issues [{:id "srv-1" :title "Alpha!" :status :backlog}
                              {:id "srv-2" :title "Beta"   :status :done}]}]
        (is (some? args-a) "A lowered a request")
        (is (some? args-b) "B lowered a request")
        ;; POSITIVE CONTROL: the overlap is REAL. Both instances are pending with
        ;; their optimistic values showing, so the assertion below is about a
        ;; genuine cross-instance overlap rather than about a board on which B
        ;; had already settled independently. Without this the test would still
        ;; pass if a refactor made a write settle at dispatch — and would then be
        ;; pinning nothing.
        (is (true? (:optimistic? (mutation-state [:edit "srv-1"])))
            "CONTROL: A is still in flight, its optimistic title on screen")
        (is (true? (:optimistic? (mutation-state [:status "srv-2"])))
            "CONTROL: B is still in flight, its optimistic move on screen")
        (reply-success! args-a reply-a)
        ;; THE REGRESSION. Pre-fix, committing A seeded the whole board entry
        ;; from A's snapshot, reverting srv-2 to :in-progress on screen while B's
        ;; instance was still pending and still deriving :optimistic? — the
        ;; visible optimistic regression rf2-9man describes.
        (is (= :done (:status (issue-by-id "srv-2")))
            "settling A must not erase B's still-pending optimistic move: A's
             reply predates B, so committing the whole of it reverts srv-2")
        (is (= "Alpha!" (:title (issue-by-id "srv-1")))
            "…while A's own accepted title is committed")
        (reply-success! args-b reply-b)
        (is (= "Alpha!" (:title (issue-by-id "srv-1"))) "A's accepted title survives")
        (is (= :done (:status (issue-by-id "srv-2"))) "B's accepted move survives")))))

(deftest an-older-reply-landing-last-does-not-revert-an-accepted-change
  (testing "examples/capabilities/resources/linearlite — the same two independent
            writes, but their replies arrive in the REVERSE of the order the
            server applied them (an ordinary transport reordering). Both
            successes are accepted, so the older snapshot lands LAST; it must not
            permanently revert the change the other write already committed —
            the last-reply-wins cache state rf2-9man was filed for"
    (load-board!)
    (rf/dispatch-sync [:linearlite/commit-edit "srv-1" "Alpha!"])
    (let [args-a @last-managed-args]
      (reset! last-managed-args nil)
      (rf/dispatch-sync [:linearlite/change-status "srv-2" :done])
      (let [args-b  @last-managed-args
            reply-a {:issues [{:id "srv-1" :title "Alpha!" :status :backlog}
                              {:id "srv-2" :title "Beta"   :status :in-progress}]}
            reply-b {:issues [{:id "srv-1" :title "Alpha!" :status :backlog}
                              {:id "srv-2" :title "Beta"   :status :done}]}]
        (is (some? args-a) "A lowered a request")
        (is (some? args-b) "B lowered a request")
        ;; POSITIVE CONTROL, as above: both are genuinely in flight, so the
        ;; reordering below is a reordering of two live replies.
        (is (true? (:optimistic? (mutation-state [:edit "srv-1"])))
            "CONTROL: A is still in flight")
        (is (true? (:optimistic? (mutation-state [:status "srv-2"])))
            "CONTROL: B is still in flight")
        ;; B settles first and is ACCEPTED — srv-2's move is committed, not
        ;; merely optimistic.
        (reply-success! args-b reply-b)
        (is (= :done (:status (issue-by-id "srv-2")))
            "B's move is committed by its own accepted reply")
        (is (false? (:optimistic? (mutation-state [:status "srv-2"])))
            "CONTROL: B has SETTLED — what follows can only revert an accepted
             change, never a merely-optimistic one")
        ;; …then A's older snapshot lands. It is accepted too, and it predates B.
        (reply-success! args-a reply-a)
        (is (= :done (:status (issue-by-id "srv-2")))
            "A's older snapshot landing last must not revert B's ACCEPTED move —
             pre-fix this was permanent last-reply-wins in the client cache")
        (is (= "Alpha!" (:title (issue-by-id "srv-1"))) "A's accepted title survives")))))


;; ============================================================================
;; 7. EVERY ARM TAKES ITS TURN AS THE OLDER WRITE (rf2-chl2)
;; ============================================================================
;;
;; Section 6 pins the defect through ONE pairing: `edit-title` is always the
;; older write and `change-status` always the newer one, and only the older
;; one's reply ever lands behind a change it does not know about. That leaves
;; two of the three commit arms without a tooth, in two different ways:
;;
;;   * Restore `change-status` ALONE to `:populates` and section 6 stays GREEN.
;;     Its reply is the snapshot taken AFTER the retitle, so seeding the whole
;;     entry from it happens to reinstate the very change that seeding would
;;     otherwise clobber. An arm can be reverted with nothing on screen.
;;   * `create-issue` is never one of the two writes at all, and its commit is
;;     not the same shape as the other two — it reconciles a temporary row
;;     against the server's, rather than lifting one field off it. Nothing in
;;     section 6 reaches that code.
;;
;; So here each arm takes its turn as A, the OLDER write, against a DIFFERENT
;; instance's later change B on a different card. One table, one driver, run in
;; both settle orders: B still merely optimistic when A's older reply lands, and
;; B already ACCEPTED when it lands. Reverting any single arm to `:populates`
;; reds the row that names it.
;;
;; THE REPLY SHAPE IS THE EXAMPLE'S OWN, and deliberately not section 6's.
;; `apply-write!` answers a write with just the row it changed, in the board's
;; `{:issues [...]}` envelope, so that is what each row here replays. Section 6's
;; coarse whole-board envelopes are the sharper probe for `edit-title` and
;; `change-status`, whose commits pick their own row out of whatever they are
;; handed — and they are the WRONG probe for `create-issue`, whose commit upserts
;; every row the reply carries and is contracted to receive only the one it
;; created. Feeding it a whole board would test the test's envelope rather than
;; the example. Under `:patches` a single-row reply says nothing about any other
;; card and every arm leaves the rest of the board alone; under `:populates` that
;; same reply BECOMES the whole board and the other card vanishes outright.

(def ^:private stale-writer-cases
  "One row per commit arm. `A` is the arm under test — the older write, whose
   reply is built before `B` is dispatched and speaks only for A's own row. `B`
   is a different mutation on a different card, so a row stays red for exactly
   the arm it names when that arm alone is reverted.

   `dispatch-a!` / `dispatch-b!` dispatch their write and RETURN its mutation
   instance, because a create's instance carries the temporary id it minted."
  [{:writer            "create-issue"
    :peer              "change-status"
    :dispatch-a!       (fn []
                         (rf/dispatch-sync [:linearlite/create-issue "Gamma"])
                         [:create (:id (issue-by-title "Gamma"))])
    :reply-a           {:issues [{:id "srv-3" :title "Gamma" :status :backlog}]}
    :dispatch-b!       (fn []
                         (rf/dispatch-sync [:linearlite/change-status "srv-2" :done])
                         [:status "srv-2"])
    :reply-b           {:issues [{:id "srv-2" :title "Beta" :status :done}]}
    :peer-desc         "srv-2's move to :done"
    :peer-holds?       (fn [] (= :done (:status (issue-by-id "srv-2"))))
    :writer-desc       "the server's row replaces the temporary card"
    :writer-committed? (fn [] (= {:id "srv-3" :title "Gamma" :status :backlog}
                                 (issue-by-id "srv-3")))
    :final             #{{:id "srv-1" :title "Alpha" :status :backlog}
                         {:id "srv-2" :title "Beta"  :status :done}
                         {:id "srv-3" :title "Gamma" :status :backlog}}}

   {:writer            "edit-title"
    :peer              "create-issue"
    :dispatch-a!       (fn []
                         (rf/dispatch-sync [:linearlite/commit-edit "srv-1" "Alpha!"])
                         [:edit "srv-1"])
    :reply-a           {:issues [{:id "srv-1" :title "Alpha!" :status :backlog}]}
    :dispatch-b!       (fn []
                         (rf/dispatch-sync [:linearlite/create-issue "Gamma"])
                         [:create (:id (issue-by-title "Gamma"))])
    :reply-b           {:issues [{:id "srv-3" :title "Gamma" :status :backlog}]}
    ;; the created card is watched by TITLE: its id changes on commit.
    :peer-desc         "the newly created Gamma card"
    :peer-holds?       (fn [] (some? (issue-by-title "Gamma")))
    :writer-desc       "srv-1's accepted title"
    :writer-committed? (fn [] (= "Alpha!" (:title (issue-by-id "srv-1"))))
    :final             #{{:id "srv-1" :title "Alpha!" :status :backlog}
                         {:id "srv-2" :title "Beta"   :status :in-progress}
                         {:id "srv-3" :title "Gamma"  :status :backlog}}}

   {:writer            "change-status"
    :peer              "edit-title"
    :dispatch-a!       (fn []
                         (rf/dispatch-sync [:linearlite/change-status "srv-2" :done])
                         [:status "srv-2"])
    :reply-a           {:issues [{:id "srv-2" :title "Beta" :status :done}]}
    :dispatch-b!       (fn []
                         (rf/dispatch-sync [:linearlite/commit-edit "srv-1" "Alpha!"])
                         [:edit "srv-1"])
    :reply-b           {:issues [{:id "srv-1" :title "Alpha!" :status :backlog}]}
    :peer-desc         "srv-1's retitle to \"Alpha!\""
    :peer-holds?       (fn [] (= "Alpha!" (:title (issue-by-id "srv-1"))))
    :writer-desc       "srv-2's accepted move"
    :writer-committed? (fn [] (= :done (:status (issue-by-id "srv-2"))))
    :final             #{{:id "srv-1" :title "Alpha!" :status :backlog}
                         {:id "srv-2" :title "Beta"   :status :done}}}])

(defn- run-stale-writer-case!
  "Drive one row of `stale-writer-cases`. `order` picks what B's change is worth
   when A's older reply lands: `:peer-optimistic` leaves B in flight, so the
   reply can only erase an OPTIMISTIC value (the visible regression);
   `:peer-accepted` settles B first, so it can only revert a COMMITTED one (the
   permanent last-reply-wins cache state). Both were rf2-9man's symptoms.

   EVERY ROW STARTS FROM A FRESH RUNTIME, and the reset belongs HERE rather
   than in the fixture: `use-fixtures :each` runs once per `deftest`, so the
   three rows of one `doseq` would otherwise share a single board, and each
   row's `:final` is written against the canned two-card board. `load-board!`
   cannot stand in for the reset — re-entering a route already entered plans no
   second read, so its `reply-success!` finds no board request in
   `last-managed-args` and reseeds nothing. The leak is not only arithmetic on
   `:final`: `dispatch-b!` identifies a created card BY TITLE, so a Gamma left
   committed by an earlier row is the card it returns, and the row then watches
   an instance that settled before it began. `frames`-reset-then-`init!` is
   section 0's pairing, and calling the example's own `init!` keeps the
   registrar refilled before the url-bound frame is made (rf2-k4oe)."
  [{:keys [writer peer dispatch-a! reply-a dispatch-b! reply-b
           peer-desc peer-holds? writer-desc writer-committed? final]}
   order]
  (reset! frame/frames {})
  (init!)
  (load-board!)
  (let [inst-a (dispatch-a!)
        args-a @last-managed-args]
    (reset! last-managed-args nil)
    (let [inst-b (dispatch-b!)
          args-b @last-managed-args]
      (is (some? args-a) (str writer " (A, the older write) lowered a request"))
      (is (some? args-b) (str peer " (B) lowered a request"))
      ;; POSITIVE CONTROLS: the overlap is REAL — two instances in flight over
      ;; the one board entry, each with its optimistic value showing. Without
      ;; these the row would still pass if a refactor made a write settle at
      ;; dispatch, and would then be pinning nothing.
      (is (true? (:optimistic? (mutation-state inst-a)))
          (str "CONTROL: " writer " (A) is still in flight"))
      (is (true? (:optimistic? (mutation-state inst-b)))
          (str "CONTROL: " peer " (B) is still in flight"))
      (is (peer-holds?)
          (str "CONTROL: " peer-desc " is on the board before A settles"))
      (when (= :peer-accepted order)
        (reply-success! args-b reply-b)
        (is (true? (:success? (mutation-state inst-b)))
            (str "CONTROL: " peer " has SETTLED :success"))
        (is (false? (:optimistic? (mutation-state inst-b)))
            (str "CONTROL: " peer-desc " is ACCEPTED — what follows can only "
                 "revert a committed change, never a merely-optimistic one")))
      ;; A's reply lands. It was built before B was dispatched and speaks only
      ;; for the row A wrote.
      (reply-success! args-a reply-a)
      (is (peer-holds?)
          (str "settling " writer " must not erase " peer-desc
               " — A's reply predates B and says nothing about B's card"))
      (is (writer-committed?)
          (str "…while A's own accepted change commits: " writer-desc))
      (when (= :peer-optimistic order)
        (reply-success! args-b reply-b))
      (is (peer-holds?) (str peer-desc " survives once both writes have settled"))
      (is (writer-committed?) (str writer-desc " survives once both writes have settled"))
      (is (= final (set (issues)))
          "the board is exactly both accepted changes and nothing else — no card
           lost, no stale value reinstated, no optimistic marker left behind"))))

(deftest each-arm-in-turn-settles-behind-a-still-optimistic-peer
  (doseq [{:keys [writer peer] :as row} stale-writer-cases]
    (testing (str "examples/capabilities/resources/linearlite — " writer
                  " is the OLDER write and its reply lands while " peer
                  " is still optimistic over another card. Committing it must "
                  "not erase what the other write has on screen (rf2-chl2)")
      (run-stale-writer-case! row :peer-optimistic))))

(deftest each-arm-in-turn-settles-behind-an-already-accepted-peer
  (doseq [{:keys [writer peer] :as row} stale-writer-cases]
    (testing (str "examples/capabilities/resources/linearlite — the same pairing "
                  "with the replies REORDERED: " peer " settles and is ACCEPTED "
                  "first, then " writer "'s older reply lands last. It must not "
                  "permanently revert a change already committed (rf2-chl2)")
      (run-stale-writer-case! row :peer-accepted))))
