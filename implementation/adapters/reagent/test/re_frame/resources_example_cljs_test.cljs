(ns re-frame.resources-example-cljs-test
  "Integration test: drives the resources example (`examples/capabilities/resources/resources/`)
   through the FOUR causal patterns it teaches — route-driven page load,
   event-driven lease ensure/release, manual refresh as a cause, and a
   machine-owned resource. Closes the false-green gap rf2-3slxrk named:
   `test:examples-compile` catches a missing namespace/init-fn and the generic
   resource artefact tests catch the runtime contract, but neither pinned the
   EXAMPLE-SPECIFIC composition — its route `:resources` metadata + owner
   lifetimes, its `[:lease …]` ensure/release pair, its manual-refresh `:cause`,
   and its `[:machine …]`-owned ensure released on actor destroy. Those could
   drift while every gate stayed green.

   The fixture fns + the deterministic transport stub live HERE (the adapter
   test tree), not under examples/capabilities/resources/resources/ — the example source stays
   test-free per the locked test-free-examples policy (rf2-8cevm). The ns
   requires the example's production source (`resources.core`) so its resources,
   routes, events, subs, and the reader machine register at ns-load, then
   exercises them directly against per-test frames.

   DETERMINISM. The example's demo backend defers each canned reply 120 ms via
   `:after-ms` (`:dispatch-later`) so the loading skeleton is observable in the
   browser. A unit test wants synchronous settle, so each test installs its own
   `:rf.http/managed` override: a capturing no-op whose reply the test replays
   explicitly via the transport's real 3-element reply-event-append shape
   (`(conj on-success {:status :ok :value …})`) — the genuine shape the live
   managed-HTTP transport produces. Routing's URL push is stubbed so navigation
   is deterministic without a browser.

   Per rf2-am9d this ns uses snapshot/restore via re-frame.test-support so the
   contract is uniform across CLJS fixtures: the snapshot captures the example's
   ns-load registrations, and the restore on the way out leaves them intact for
   any subsequent test ns."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            ;; production HTTP + resources surfaces (so the resource runtime,
            ;; managed-HTTP lowering, and the late-bound routing integration
            ;; resolve); the actual fetch is overridden by the capturing stub.
            [re-frame.http.managed]
            [re-frame.http.test-support]
            [re-frame.resources]
            [re-frame.resources.route :as resources-route]
            [re-frame.resources.state :as state]
            [re-frame.resources.test-support]
            [re-frame.routing :as routing]
            ;; the framework trace-ring buffer (Spec 009). The shared
            ;; `make-reset-runtime-fixture` resets `frame/frames` but NOT the
            ;; process-global trace rings, so a dispatching test's trace residue
            ;; survives into later cross-cutting tooling tests that read the
            ;; rings. We clear the rings around each test body (below) so this
            ;; suite leaves no trace residue.
            [re-frame.trace.tooling :as trace-tooling]
            ;; the example's production source — registers its resources, routes,
            ;; events, subs, and the reader machine at ns-load.
            [resources.core])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

;; ============================================================================
;; FIXTURE
;; ============================================================================

(def ^:private last-managed-args (atom nil))

;; The shared `make-reset-runtime-fixture`'s post-dispose
;; `:resources/reset-resources!` hook CLEARS the `:resource` registrar kind
;; between tests (it is host-side transient state from the fixture's point of
;; view). CLJS has no `(require … :reload)`, so we snapshot the example's
;; ns-load `:resource` registrations ONCE here (right after the `resources.core`
;; require above ran them) and re-install them in `init!` (which runs AFTER the
;; post-dispose hooks). This re-installs the EXACT example registrations — not a
;; test-local copy — so each test exercises the example's own `reg-resource`s.
;; (Routes / the reader machine live under kinds the reset does NOT clear, so
;; they survive via the fixture's ns-load baseline.)
(def ^:private resource-kind-snapshot
  (get @registrar/kind->id->metadata :resource))

;; AT NS LOAD, immediately remove THIS example's `:resource` registrations (by id)
;; from the SHARED live registrar — after snapshotting them above. They are
;; reinstated per-test by `init!`. Why: cljs.test loads every test ns into ONE
;; bundle before running ANY test, so without this our ns-load `reg-resource`s sit
;; in the global registrar until some OTHER suite's reset clears them — and a
;; cross-cutting tooling test (the Xray/Story panel e2e, which registers a trace
;; collector) whose post-dispose `:resources/reset-resources!` clears them would
;; mirror the resulting frameless `:rf.registry/handler-cleared` burst into its
;; cascade seed (a false-positive `:rf.xray/cascades`). We remove only OUR ids
;; (not the whole kind) so a sibling suite's ns-load resources are untouched.
(swap! registrar/kind->id->metadata update :resource
       (fn [m] (apply dissoc m (keys resource-kind-snapshot))))

(defn- init!
  "Per-test setup (after adapter install, registrar live). The resources
   example owns the URL through `:rf/default` (`:url-bound? true`), so re-register
   it that way, re-install the example's `:resource` registrations the reset hook
   wiped, reset the routing counters, re-publish the late-bound routing
   integration, and stub the managed-HTTP + url-push fx so route entry's ensure
   + navigation are deterministic without a fetch / browser."
  []
  (reset! last-managed-args nil)
  (rf/reg-frame :rf/default {:url-bound? true
                             :doc "resources-example default app frame."})
  ;; Re-install the example's ns-load resource registrations (wiped post-dispose).
  (swap! registrar/kind->id->metadata assoc :resource resource-kind-snapshot)
  (routing/reset-counters!)
  (resources-route/install-routing-integration!)
  ;; Capturing no-op: the reply is replayed explicitly by the test so the
  ;; 3-element internal reply event matches what the live transport produces.
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil)))

(defn- isolate-trace-bus-fixture
  "OUTER fixture: keep this resource-registering suite from leaking trace
   residue into later cross-cutting tooling tests (the Xray/Story panel e2e seeds
   read the process-global trace bus). The leak this closes:

   The shared `make-reset-runtime-fixture` runs `:resources/reset-resources!`
   (post-dispose) BEFORE it clears trace listeners. That hook `clear-kind!`s the
   `:resource` registrar kind, emitting one FRAMELESS `:rf.registry/handler-
   cleared` trace per id (Spec 009 §:op-type vocabulary). With this suite's
   example resources re-installed every test, that is a recurring burst of
   frameless traces — and if an EARLIER e2e test left the Xray trace-collector
   LISTENER registered (its sentinel-guarded re-register survives our reset), the
   listener mirrors that burst into Xray's process-global frameless ring, which
   the e2e cascade seeds later read as spurious `:rf.xray/cascades`.

   Listed FIRST in `use-fixtures` so it is the OUTERMOST wrapper: its setup runs
   BEFORE the core fixture's post-dispose burst, clearing the trace listeners +
   the framework trace rings so no collector is active to capture any burst, and
   its teardown clears them again so this suite leaves a clean trace bus.

   (The registrar side is handled separately: this suite's resources are removed
   from the SHARED registrar at NS LOAD — see the top-level `swap!` above — and
   captured in the per-test snapshot baseline as ABSENT, so they live only inside
   this suite's own test bodies via `init!`, never persisting for another suite's
   reset to clear.) A later e2e test re-registers its own collector (its helper
   calls `reset-sentinels!` + `register-trace-collector!`), so clearing here is
   safe."
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

(defn- entry [scoped-key]
  (get-in (runtime-db) (state/entry-path scoped-key)))

(defn- list-key []
  (state/scoped-resource-key :rf.scope/global :articles/list {}))

(defn- detail-key [slug]
  (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug slug}))

(defn- reply-success!
  "Replay the captured `:on-success` with the transport's success result
   appended as the LAST arg — the exact shape the live managed-HTTP transport
   produces (Spec 014 §Reply addressing)."
  [args data]
  (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data})))

(defn- route-state
  "The passive resource view-model the example's views read, via the
   `[:rf/resource …]` sub, computed against :rf/default's frame-state."
  [query]
  (rf/compute-sub [:rf/resource query] (rf/frame-state-value :rf/default)))

;; ============================================================================
;; 1. ROUTE-DRIVEN PAGE LOAD — `:resources` metadata owns the read
;; ============================================================================

(deftest articles-route-entry-causes-the-list-load-under-the-route-owner
  (testing "examples/capabilities/resources/resources — entering :resources.app/articles ensures
            the :articles/list resource under the route nav-token owner, the view
            reads it passively, and it settles :loaded on the reply (the route
            CAUSES the fetch; the view never asks)"
    (rf/dispatch-sync [:rf.route/navigate :resources.app/articles])
    (let [slice     (get-in (runtime-db) [:rf.runtime/routing :current])
          nav-token (:nav-token slice)
          lkey      (list-key)
          e         (entry lkey)]
      (is (= :loading (:status e)) "first load → :loading")
      (is (contains? (:active-owners e) [:route :resources.app/articles nav-token])
          "owned by the route nav-token owner [:route route-id nav-token]")
      ;; The view reads the passive view-model — first load shows the skeleton.
      (is (:loading? (route-state {:resource :articles/list :params {}}))
          ":loading? true while first load is in flight")
      ;; Settle the reply with the canned list shape the demo stub would synthesise.
      (reply-success! @last-managed-args
                      [{:slug "a" :title "Article A"}
                       {:slug "b" :title "Article B"}])
      (let [vm (route-state {:resource :articles/list :params {}})]
        (is (= :loaded (:status (entry lkey))) "settles :loaded on the reply")
        (is (false? (:loading? vm)) "no longer loading")
        (is (true? (:has-data? vm)) "has usable data")
        (is (= 2 (count (:data vm))) "the view-model carries the two articles")))))

(deftest article-detail-route-threads-the-url-slug-into-resource-params
  (testing "examples/capabilities/resources/resources — :resources.app/article-detail maps the
            URL slug into the :article/by-slug resource params, so the detail
            read is a per-slug cache entry"
    (rf/dispatch-sync [:rf.route/navigate :resources.app/article-detail {:slug "owners-vs-causes"}])
    (let [dkey (detail-key "owners-vs-causes")]
      (is (= :loading (:status (entry dkey)))
          "the slug-keyed detail read is ensured on entry")
      (reply-success! @last-managed-args {:slug "owners-vs-causes" :title "Owners vs Causes"
                                          :body "..."})
      (is (= :loaded (:status (entry dkey))))
      (is (= "Owners vs Causes"
             (:title (:data (route-state {:resource :article/by-slug
                                          :params  {:slug "owners-vs-causes"}}))))
          "the per-slug detail view-model carries the right article"))))

;; ============================================================================
;; 2. EVENT-DRIVEN LEASE — ensure under [:lease …], release on close
;; ============================================================================

(deftest preview-opens-an-app-lease-and-close-releases-it
  (testing "examples/capabilities/resources/resources — :resources.app/preview-opened ensures the
            detail under an app `[:lease …]` owner (and records the open slug);
            :resources.app/preview-closed releases the lease so the entry can GC
            (the mandatory release path for an app-minted lease, Spec 016 §Active
            owners)"
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (let [lease-owner [:lease :resources.app/preview "fresh-skip"]
            dkey        (detail-key "fresh-skip")]
        (rf/dispatch-sync [:resources.app/preview-opened "fresh-skip"] {:frame f})
        ;; the open slug is recorded in app-db for the preview panel to render
        (is (= "fresh-skip"
               (rf/compute-sub [:resources.app/preview-slug] (rf/frame-state-value f)))
            "the open preview slug is in app-db")
        (let [e (get-in (:rf.db/runtime (rf/frame-state-value f)) (state/entry-path dkey))]
          (is (= :loading (:status e)) "the lease ensured a first load")
          (is (contains? (:active-owners e) lease-owner)
              "owned by the app lease [:lease :resources.app/preview slug]"))
        ;; Close → the lease releases; the slug clears.
        (rf/dispatch-sync [:resources.app/preview-closed "fresh-skip"] {:frame f})
        (is (nil? (rf/compute-sub [:resources.app/preview-slug] (rf/frame-state-value f)))
            "closing clears the open slug")
        (let [e (get-in (:rf.db/runtime (rf/frame-state-value f)) (state/entry-path dkey))]
          (is (not (contains? (:active-owners e) lease-owner))
              "the lease owner was released — no dangling lease pins the entry"))))))

;; ============================================================================
;; 3. MANUAL REFRESH — a `:cause`, never an owner
;; ============================================================================

(deftest manual-refresh-is-a-cause-and-forces-a-refetch-keeping-data
  (testing "examples/capabilities/resources/resources — :resources.app/refresh-articles dispatches
            :rf.resource/refetch with a `:cause` and NO `:owner` (a refresh wants
            fresh data but keeps nothing alive — the route owns liveness). It
            refetches an already-loaded list into :fetching while prior data stays
            visible (stale-while-revalidate)"
    (rf/dispatch-sync [:rf.route/navigate :resources.app/articles])
    (let [slice     (get-in (runtime-db) [:rf.runtime/routing :current])
          nav-token (:nav-token slice)
          lkey      (list-key)]
      (reply-success! @last-managed-args [{:slug "a" :title "A"}])
      (is (= :loaded (:status (entry lkey))) "list loaded once")
      (reset! last-managed-args nil)
      ;; Manual refresh.
      (rf/dispatch-sync [:resources.app/refresh-articles])
      (let [e (entry lkey)]
        (is (= :fetching (:status e)) "refresh re-fetches an already-loaded list")
        (is (true? (:fetching? (route-state {:resource :articles/list :params {}})))
            ":fetching? true so the view shows the quiet refresh indicator")
        (is (some? (:data e)) "prior data is kept while the refresh is in flight")
        ;; The refresh attached NO new owner — the route nav-token owner is the
        ;; only liveness owner (the refresh is a cause, not an owner).
        (is (= #{[:route :resources.app/articles nav-token]} (:active-owners e))
            "refresh added no owner; the route remains the sole liveness owner"))
      ;; Settle the refresh — fresh data lands, status back to :loaded.
      (reply-success! @last-managed-args [{:slug "a" :title "A2"} {:slug "c" :title "C"}])
      (is (= :loaded (:status (entry lkey))))
      (is (= 2 (count (:data (route-state {:resource :articles/list :params {}}))))
          "the refreshed data replaced the prior data"))))

;; ============================================================================
;; 4. MACHINE-OWNED RESOURCE — ensure under [:machine …], release on destroy
;; ============================================================================

(deftest reader-start-stop-events-record-and-clear-the-reader-slice
  (testing "examples/capabilities/resources/resources — :resources.app/start-reader records the
            active reader instance in app-db (the view's stop affordance reads it)
            and fires the machine-start marker; :resources.app/stop-reader emits
            the actor-destroy fx and clears the slice. The start/stop event glue
            is the example-specific wiring; the resource owner lifecycle the
            destroy drives is pinned in the dedicated test below."
    (let [slug        "resources-101"
          instance-id (str "reader-" slug)]
      (rf/dispatch-sync [:resources.app/start-reader slug])
      (is (= {:slug slug :instance-id instance-id}
             (rf/compute-sub [:resources.app/reader] (rf/frame-state-value :rf/default)))
          "start-reader records the active reader instance in app-db")
      (rf/dispatch-sync [:resources.app/stop-reader])
      (is (nil? (rf/compute-sub [:resources.app/reader] (rf/frame-state-value :rf/default)))
          "stop-reader clears the reader slice"))))

;; The test above pins the example's start/stop EVENT glue (the app-db slice).
;; The test below pins the MACHINE-OWNED RESOURCE contract the example teaches:
;; the reader ensures its detail under the runtime ACTOR-ID owner
;; `[:machine :resources.app/reader]`, and stop-reader (actor destroy) releases
;; exactly that owner. This is the regression pin for rf2-lbtqw4 — the example
;; previously ensured under a three-part `[:machine machine-id instance-id]`
;; owner while relying on actor-destroy auto-release, which the framework fires
;; ONLY for the two-part `[:machine actor-id]` key (Spec 016 §Release authority
;; is per owner kind, 016:291), so the lease leaked. The generic
;; ensure-under-owner + release-on-owner-drop mechanics are also pinned in the
;; resources artefact suites (`implementation/resources/test/`); this pins the
;; EXAMPLE's specific owner shape + stop-reader cleanup end-to-end against the
;; example's own events.

(deftest reader-owns-its-article-under-the-actor-id-owner-released-on-stop
  (testing "examples/capabilities/resources/resources — start-reader births the
            :resources.app/reader actor and drives :reader/load, whose
            :ensure-article action ensures :article/by-slug under the TWO-PART
            runtime actor-id owner [:machine :resources.app/reader] — the one
            machine owner the framework auto-releases on destroy (Spec 016:291) —
            and NOT a three-part [:machine machine-id instance-id] key (an
            app-authoritative lease the framework would NOT auto-release: the
            leak the old example shape caused). stop-reader destroys the actor,
            releasing that owner so the read is not left pinned (rf2-lbtqw4)."
    (let [slug        "resources-101"
          instance-id (str "reader-" slug)
          actor-owner [:machine :resources.app/reader]
          three-part  [:machine :resources.app/reader instance-id]
          dkey        (detail-key slug)]
      ;; START — births the actor, then :reader/load runs :ensure-article, which
      ;; ensures the detail under the actor-id owner. Managed-HTTP is the
      ;; capturing stub, so the entry sits :loading; the owner attaches at ensure.
      (rf/dispatch-sync [:resources.app/start-reader slug])
      (let [e (entry dkey)]
        (is (some? e)
            "the reader's machine-owned detail entry exists after start-reader")
        (is (contains? (:active-owners e) actor-owner)
            "ensured under the two-part actor-id owner [:machine :resources.app/reader]")
        ;; ADVERSARIAL/NEGATIVE — the three-part [:machine machine-id instance-id]
        ;; key must NOT be an owner. The old (buggy) shape attached it here and
        ;; then leaked, because actor-destroy auto-releases only the two-part key.
        (is (not (contains? (:active-owners e) three-part))
            "the domain instance-id is NOT folded into the owner (that would leak)"))
      ;; STOP — destroy the actor; teardown releases [:machine :resources.app/reader].
      (rf/dispatch-sync [:resources.app/stop-reader])
      (let [e (entry dkey)]
        (is (or (nil? e) (not (contains? (:active-owners e) actor-owner)))
            "stop-reader released the machine owner — the read is not left pinned")))))

;; ============================================================================
;; 5. LAYERED PROJECTION SUB — a projection OVER the resource, not a hook
;; ============================================================================

(deftest first-slug-projection-layers-over-the-passive-list-resource
  (testing "examples/capabilities/resources/resources — :resources.app/first-slug is an ordinary
            EP-0004 sub LAYERED over the passive `[:rf.resource/data …]` input
            (Spec 016 §No :select key — projections are subs, not resource hooks)"
    (rf/dispatch-sync [:rf.route/navigate :resources.app/articles])
    (reply-success! @last-managed-args
                    [{:slug "resources-101" :title "Resources 101"}
                     {:slug "owners-vs-causes" :title "Owners vs Causes"}])
    (is (= "resources-101"
           (rf/compute-sub [:resources.app/first-slug] (rf/frame-state-value :rf/default)))
        "the projection derives the top article's slug from the list resource data")))
