(ns linearlite.baseline-test
  "Behavioural baseline for the board — the screen under migration.

   The board is a routed resource with optimistic writes: entering the route
   ensures the `:linearlite/board` resource, the view reads it passively, and
   every write (create, retitle, change status) applies an optimistic patch to
   the board that COMMITS on `:ok` and ROLLS BACK on `:error`. These tests pin
   that composition through the app's own events and subscriptions, without
   rendering anything, so a view-layer migration that preserves behaviour
   keeps them green untouched.

   The demo backend defers each reply so the optimistic value is visible in
   the browser. A unit test wants a synchronous settle, so each test installs
   its own `:rf.http/managed` override: a capturing no-op whose reply the test
   replays explicitly in the shape the live transport produces. The test
   chooses success or failure by choosing which reply to replay. The URL push
   is stubbed so navigation is deterministic without a browser.

   Run with `npm test` from the app directory. The suite compiles to Node and
   needs no browser."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; Production HTTP + resources surfaces, so the resource runtime,
            ;; the mutation runtime, managed-HTTP lowering and the routing
            ;; integration resolve; the actual fetch is overridden per test.
            [re-frame.http.managed]
            [re-frame.http.test-support]
            [re-frame.resources]
            [re-frame.resources.route :as resources-route]
            [re-frame.resources.state :as state]
            [re-frame.resources.test-support]
            [re-frame.routing :as routing]
            ;; The app's production source — registers the :linearlite/board
            ;; resource, the three optimistic mutations, routes, events, subs
            ;; and views at load.
            [linearlite.core]))

;; ============================================================================
;; Fixture
;; ============================================================================

(def ^:private last-managed-args (atom nil))

;; The runtime-reset fixture clears the `:resource` and `:mutation` registry
;; kinds between tests, and ClojureScript has no `(require … :reload)`. So
;; snapshot the app's load-time registrations ONCE here — right after the
;; `linearlite.core` require above ran them — and re-install them per test in
;; `init!`. What gets re-installed is the app's own registrations, not copies.
(def ^:private resource-kind-snapshots
  (select-keys @registrar/kind->id->metadata [:resource :mutation]))

;; Take those two kinds out of the live registry at load; `init!` puts them
;; back before each test, so every test starts from the same registrations.
(swap! registrar/kind->id->metadata
       (fn [reg]
         (reduce (fn [r [kind id->meta]]
                   (update r kind (fn [m] (apply dissoc m (keys id->meta)))))
                 reg
                 resource-kind-snapshots)))

(defn- init!
  "Per-test setup, after the adapter is installed and the registry is live.
   Re-install the `:resource` / `:mutation` registrations the reset wiped;
   reset routing's counters; re-publish the routing integration; stub managed
   HTTP and the URL push so route entry's ensure and navigation are
   deterministic without a fetch or a browser; and only THEN create the app's
   frame. The app owns the URL through `:rf/default`, so it is re-registered
   the same way.

   The order matters. A frame resolves handlers through a generation sealed
   when it is created, so everything a test needs the frame to see — the
   re-installed registrations and the stubbed effects — is registered first,
   and the frame is made last."
  []
  (reset! last-managed-args nil)
  ;; Re-install through `registrar/register!`, which writes the registry and
  ;; the source store in lockstep.
  (doseq [[kind id->meta] resource-kind-snapshots
          [id meta] id->meta]
    (registrar/register! kind id meta))
  (routing/reset-counters!)
  (resources-route/install-routing-integration!)
  (fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "linearlite default app frame."}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn init!}))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(def ^:private board-query
  {:resource :linearlite/board :scope :rf.scope/global :params {}})

(defn- board-key []
  (state/scoped-resource-key :rf.scope/global :linearlite/board {}))

(defn- entry [] (get-in (runtime-db) (state/entry-path (board-key))))

(defn- board-data
  "The passive board `:data` the view reads (the `:rf.resource/data` sub),
   computed against :rf/default's frame-state."
  []
  (rf/compute-sub [:rf.resource/data board-query]
                  (rf/frame-state-value :rf/default)))

(defn- issues [] (:issues (board-data)))

(defn- issue-by-id [id] (some #(when (= id (:id %)) %) (issues)))

(defn- mutation-state
  "The passive `[:rf/mutation {:instance …}]` view-model the card view reads
   (`{:pending? :success? :error? :settled? :optimistic? …}`)."
  [instance]
  (rf/compute-sub [:rf/mutation {:instance instance}]
                  (rf/frame-state-value :rf/default)))

(defn- reply-success!
  "Replay the captured `:on-success` with the result appended as the LAST
   argument — the shape the live managed-HTTP transport produces for an
   accepted reply."
  ([data] (reply-success! @last-managed-args data))
  ([args data]
   (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data})
                     {:frame :rf/default})))

(defn- reply-failure!
  "Replay the captured `:on-failure` with the failure appended — the shape a
   503 produces, and what the app's fail-next-write toggle exhibits in the
   browser."
  ([failure] (reply-failure! @last-managed-args failure))
  ([args failure]
   (rf/dispatch-sync (conj (:on-failure args) {:status :error :error failure})
                     {:frame :rf/default})))

(def ^:private demo-board
  "A canned board reply, the shape the backend returns for the read."
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
;; 1. Route entry ensures the board resource (the route owns the read)
;; ============================================================================

(deftest board-route-entry-ensures-the-board-under-the-route-owner
  (testing "entering :linearlite.app/board ensures the :linearlite/board
            resource under the route nav-token owner; the view reads the
            passive board :data and settles to the issues on the reply (the
            route CAUSES the load; the view never asks)"
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
;; 2. Optimistic apply — the write shows on the board BEFORE the reply
;; ============================================================================

(deftest create-issue-applies-optimistically-before-the-reply
  (testing ":linearlite/create-issue applies its optimistic patch before the
            request lowers: the new Backlog card appears immediately and the
            watched mutation instance is :optimistic? while pending"
    (load-board!)
    (rf/dispatch-sync [:linearlite/create-issue "Gamma"])
    ;; the optimistic apply ran before the request was sent.
    (let [tmp (some #(when (= "Gamma" (:title %)) %) (issues))]
      (is (some? tmp) "the new card appears in the board IMMEDIATELY (optimistic apply)")
      (is (= :backlog (:status tmp)) "the optimistic card lands in Backlog")
      (is (= 3 (count (issues))) "the board grew by one before any reply")
      (let [ms (mutation-state [:create (:id tmp)])]
        (is (true? (:pending? ms)) "the write is pending")
        (is (true? (:optimistic? ms)) ":optimistic? true while the apply is showing"))
      (is (some? @last-managed-args) "the write lowered a request to the transport"))))

(deftest change-status-applies-optimistically-before-the-reply
  (testing ":linearlite/change-status moves the card to the new column
            IMMEDIATELY (optimistic apply), before the request settles"
    (load-board!)
    (rf/dispatch-sync [:linearlite/change-status "srv-1" :done])
    (is (= :done (:status (issue-by-id "srv-1")))
        "srv-1 jumped to :done the instant the move was dispatched (optimistic)")
    (is (true? (:optimistic? (mutation-state [:status "srv-1"])))
        "the change-status instance is :optimistic? while pending")))

;; ============================================================================
;; 3. Success commit — the :ok reply re-seeds the board with the server's value
;; ============================================================================

(deftest successful-write-commits-the-server-board-via-populates
  (testing "an accepted :ok reply COMMITS: the mutation's `:populates`
            overwrites the optimistic board with the server's authoritative
            value (the temp id is replaced by the server id, the optimistic
            marker clears), and the instance settles :success"
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
  (testing "a successful :linearlite/edit-title commits the new title via
            :populates (the optimistic title is confirmed by the server's
            authoritative board)"
    (load-board!)
    (rf/dispatch-sync [:linearlite/commit-edit "srv-2" "Beta!"])
    (is (= "Beta!" (:title (issue-by-id "srv-2"))) "optimistic title shows immediately")
    (let [srv-board {:issues [{:id "srv-1" :title "Alpha" :status :backlog}
                              {:id "srv-2" :title "Beta!" :status :in-progress}]}]
      (reply-success! srv-board)
      (is (= "Beta!" (:title (issue-by-id "srv-2"))) "the committed title persists")
      (is (true? (:success? (mutation-state [:edit "srv-2"]))) "the edit instance settled :success"))))

;; ============================================================================
;; 4. Failure rollback — the :error reply reverts the optimistic change
;; ============================================================================

(deftest failed-create-rolls-the-optimistic-card-back-out
  (testing "an accepted :error reply ROLLS BACK the optimistic apply: the
            runtime restores the recorded snapshot inverse, so the
            optimistically-added card VANISHES from the board and the
            instance settles :error (no manual undo)"
    (load-board!)
    (rf/dispatch-sync [:linearlite/create-issue "Doomed"])
    (let [tmp-id (:id (some #(when (= "Doomed" (:title %)) %) (issues)))]
      (is (= 3 (count (issues))) "the optimistic card was added (board grew by one)")
      ;; the request FAILS (a 503 — what the fail-next-write toggle produces).
      (reply-failure! {:kind :rf.http/http-5xx :status 503})
      (is (= 2 (count (issues))) "the optimistic card was rolled back OUT (board restored)")
      (is (nil? (issue-by-id tmp-id)) "the never-committed card is gone")
      (is (= demo-board (board-data)) "the board is restored to exactly its pre-write value")
      (let [ms (mutation-state [:create tmp-id])]
        (is (true? (:error? ms)) "the instance settled :error")
        (is (false? (:optimistic? ms)) ":optimistic? false after rollback (no live apply)")))))

(deftest failed-change-status-snaps-the-card-back-to-its-prior-column
  (testing "a failed :linearlite/change-status rolls back: the card snaps
            back to its prior column (the recorded `:before` is restored
            verbatim)"
    (load-board!)
    (rf/dispatch-sync [:linearlite/change-status "srv-1" :done])
    (is (= :done (:status (issue-by-id "srv-1"))) "optimistic move applied")
    (reply-failure! {:kind :rf.http/http-5xx :status 503})
    (is (= :backlog (:status (issue-by-id "srv-1")))
        "the card snapped back to its prior :backlog column on failure (rollback)")
    (is (= demo-board (board-data)) "the whole board is restored to its pre-move value")
    (is (true? (:error? (mutation-state [:status "srv-1"]))) "the instance settled :error")))

(deftest failed-edit-title-reverts-to-the-prior-title
  (testing "a failed :linearlite/edit-title reverts the optimistic title to
            the prior value on rollback"
    (load-board!)
    (rf/dispatch-sync [:linearlite/commit-edit "srv-2" "Wrong"])
    (is (= "Wrong" (:title (issue-by-id "srv-2"))) "optimistic title applied")
    (reply-failure! {:kind :rf.http/http-5xx :status 503})
    (is (= "Beta" (:title (issue-by-id "srv-2"))) "the title reverted to its prior value (rollback)")
    (is (= demo-board (board-data)) "the board is restored to its pre-edit value")))

;; ============================================================================
;; 5. The armed demo backend — "Fail the next write" answers a real 503
;; ============================================================================
;;
;; Everything above BYPASSES the app's own demo backend: the capturing
;; `:rf.http/managed` override replays hand-built replies, so those tests prove
;; the mutation arcs but not that the backend the board actually runs against
;; ever PRODUCES such a failure. These two tests drive the registered
;; `:linearlite.demo/http-stub` directly and pin WHICH framework canned-reply
;; effect it selects and WITH WHAT ARGUMENTS — the whole of the stub's
;; observable act, since it resolves the canned effect from the registry at
;; call time and hands off. The stub defers every reply, so the tests stop at
;; its emitted arguments rather than carrying the reply on into the runtime:
;; the runtime's handling of a classified 503 is pinned by section 4 above.

(def ^:private parked-replies (atom []))

(def ^:private canned-fx-ids
  [:rf.http/managed-canned-success :rf.http/managed-canned-failure])

(defn- install-canned-parkers!
  "Re-register the two framework canned-reply effects with parkers that
   RECORD the demo stub's answer — which effect it chose, the deferral it
   asked for, and the arguments it built — and deliver nothing."
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
  "Call the app's registered demo-backend effect exactly as the framework
   would — `(handler frame-ctx args-map)` — and return what it parked.
   `request` is the lowered request map the app's own mutation produces."
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
  "The request `:linearlite/change-status` lowers to — PUT /api/issues/:id
   with a `{:status …}` body — read off the mutation's own request fn."
  [id status]
  {:method :put :url (str "/api/issues/" id) :body {:status status}})

(defn- server-board
  "The backend's canonical server board, read the only way a client can: ask
   the demo backend for it."
  []
  (:value (:args (first (ask-the-demo-stub! {:method :get :url "/api/board"})))))

(defn- server-issue [board id]
  (some #(when (= id (:id %)) %) (:issues board)))

(defn- fail-next-write? []
  (rf/compute-sub [:linearlite/fail-next-write?]
                  (rf/frame-state-value :rf/default)))

(deftest armed-demo-backend-answers-a-classified-503
  (testing "with 'Fail the next write' armed, the demo backend answers a
            write through the framework's canned-FAILURE effect carrying a
            503 in the shape that contract reads: top-level
            :kind :rf.http/http-5xx plus :tags {:status 503 :message …}"
    (install-canned-parkers!)
    (rf/dispatch-sync [:linearlite/set-fail-next-write true])
    (is (true? (fail-next-write?)) "armed")
    (let [before (server-board)
          parked (ask-the-demo-stub! (change-status-request "srv-1" :done))
          {:keys [fx-id after-ms args]} (first parked)]
      (is (= 1 (count parked)) "the stub answered exactly once")
      (is (= :rf.http/managed-canned-failure fx-id)
          "the ARMED branch was selected — the write met the canned-FAILURE effect")
      (is (pos? after-ms)
          "…deferred, as the demo does so the optimistic value paints first")
      (is (= :rf.http/http-5xx (:kind args))
          "the failure is classified :rf.http/http-5xx, not the default :rf.http/transport")
      (is (= 503 (get-in args [:tags :status])) "…carrying the promised HTTP 503")
      (is (= "Simulated server failure (the demo's rollback seam)."
             (get-in args [:tags :message]))
          "…and the demo's own message")
      (is (nil? (:failure args))
          "no vestigial `:failure` key")
      ;; The armed branch answers BEFORE touching the canonical board, so the
      ;; value the next read returns is untouched — the server-side half of
      ;; the rollback the mutation runtime performs client-side.
      (is (= before (server-board))
          "the canonical server board is unchanged by the refused write"))))

(deftest unarmed-demo-backend-answers-success-and-commits
  (testing "positive control: an otherwise-identical UNARMED write through
            the same demo backend selects the canned-SUCCESS effect and
            returns the mutated authoritative board, proving the armed test
            above selects the armed branch rather than passing because every
            request fails"
    (install-canned-parkers!)
    (is (false? (fail-next-write?)) "unarmed (the fixture's fresh app-db)")
    (let [original (:status (server-issue (server-board) "srv-1"))
          target   (if (= :done original) :backlog :done)
          parked   (ask-the-demo-stub! (change-status-request "srv-1" target))
          {:keys [fx-id after-ms args]} (first parked)]
      (is (= 1 (count parked)) "the stub answered exactly once")
      (is (= :rf.http/managed-canned-success fx-id)
          "the UNARMED branch was selected — the write met the canned-SUCCESS effect")
      (is (pos? after-ms) "…deferred, like every reply this demo makes")
      (is (nil? (:kind args))
          "a success reply carries no failure classification")
      (is (= target (:status (server-issue (:value args) "srv-1")))
          "the authoritative board it returns carries the write applied — the
           value that becomes the mutation's :populates payload")
      ;; Compensating write: the demo stub *is* the server and its board is
      ;; shared across the bundle, so put the issue back the way this test
      ;; found it.
      (ask-the-demo-stub! (change-status-request "srv-1" original))
      (is (= original (:status (server-issue (server-board) "srv-1")))
          "the compensating write restored the canonical board"))))
