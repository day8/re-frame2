(ns re-frame.realworld-resources-cljs-test
  "Integration test: drives the RealWorld-on-resources example
   (`examples/real-apps/realworld_resources/`) through the example-SPECIFIC wiring
   the bead rf2-3slxrk named as the false-green gap — the composition `test:
   examples-compile` + the generic resource/mutation artefact tests do NOT pin:

     1. SESSION-SCOPE RESOLVER — the named `reg-resource-scope :realworld/session`
        resolves the per-user feed scope from `[:auth :user :username]`, nil when
        logged out (fail-closed), and re-keys across login/logout;
     2. BEARER-HEADER DECORATION — the frame-wide `bearer-auth-interceptor`
        injects `Authorization: Token <jwt>` from the auth slice onto an outbound
        request, and is a no-op when logged out;
     3. MUTATION :populates / :invalidates / :reply-to — favorite seeds the detail
        entry from its own reply (authoritative load), invalidates the global
        article tags AND the session feed in one per-target descriptor set, and
        fires its `:reply-to` continuation once on settle;
     4. THE EDITOR FLOW + :can-leave — `:editor/can-submit?` materialises
        valid-AND-dirty into app-db; `:editor/submit` reads it as plain data and
        executes the save mutation with a `:reply-to [:editor/replied]` that
        navigates to the saved article; the `:can-leave?` guard blocks a dirty
        draft and frees a clean / just-saved one;
     5. LOGOUT teardown — `:auth/clear-session` clears the auth slice, drops the
        departing principal's scoped caches via `:rf.resource/clear-scope`
        (the mandatory teardown path);
     6. THE AUTH MACHINE — login drives :idle → :submitting → :authed via managed
        HTTP and stores the session;
     7. THE PRODUCTION-SEAM RECEIPT (§11, rf2-k5lbd) — with managed HTTP wired to
        the app's OWN demo backend, a comment posted through the comment form
        survives the refetch its invalidation causes: the runtime refetches the
        route-owned comments read on its own, and the refetch settles on the
        backend's current state.

   The fixture fns + the deterministic transport stub live HERE (the adapter test
   tree), not under examples/real-apps/realworld_resources/ — the example source
   stays test-free per the locked test-free-examples policy (rf2-8cevm). The ns
   requires the example's production source (`realworld-resources.core`, which
   chains in every feature ns — resources / mutations / scope / routing / auth /
   settings / article-editor / http / schema / views) so their resources /
   mutations / events / subs / machine / scope-resolver / flow register at
   ns-load, then exercises them directly against per-test frames.

   DETERMINISM. Each test installs its own capturing `:rf.http/managed` override
   and replays the reply explicitly via the transport's real 3-element reply-
   event-append shape (`(conj on-success {:status :ok :value …})`). Routing's
   url-push is stubbed so navigation is deterministic without a browser. The one
   exception is §11, which answers nothing by hand: it drives the production demo
   backend and awaits its deferred replies (`:after-ms` → `:dispatch-later`) with
   `test-support/poll-until`, which is why the suite runs under the MAP-FORM
   (`:async? true`) reset fixture.

   Per rf2-am9d this ns uses snapshot/restore via re-frame.test-support so the
   contract is uniform across CLJS fixtures."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest testing use-fixtures is async]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            ;; Activate the default Malli validator (rf2-t0hq): the CLJS default
            ;; validator soft-passes without this require, so the durable
            ;; AuthSlice regression below could never observe a rollback. The
            ;; canonical app-boot opt-in for Malli app-schema validation.
            [re-frame.schemas.malli]
            [malli.core :as m]
            [re-frame.views]
            [re-frame.http.managed]
            [re-frame.http.test-support]
            [re-frame.resources]
            [re-frame.resources.route :as rf.resources.route]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.test-support]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.link :as rf.routing.link]
            ;; the framework trace-ring buffer (Spec 009) — cleared around each
            ;; test body so this dispatching suite leaves no trace residue for a
            ;; later cross-cutting tooling test (e.g. the Xray/Story panel e2e
            ;; seeds, which read the rings). See `clear-trace-rings-fixture`.
            [re-frame.trace.tooling :as rf.trace.tooling]
            ;; the example's production source — chains in every feature ns.
            [realworld-resources.core :as core]
            [realworld-resources.scope :as scope]
            ;; the example's shared route table + the two ENTRY-SPECIFIC url
            ;; strategies (already loaded via core; aliased for the ui-arm
            ;; url-strategy pins — rf2-nn5s8 audit rider).
            [realworld-resources.routing :as app-routing]
            ;; The app's HTTP surface — its `defonce`d demo-backend world
            ;; (`demo-state`, the documented reset boundary) and `full-url` — plus
            ;; the shared demo backend itself, for the pure "server truth" read the
            ;; §11 receipt compares the settled entry against.
            [realworld-resources.http :as app-http]
            [realworld-shared.demo-backend :as demo]
            ;; The shared WIRE contract (User / UserResponse) + this app's durable
            ;; app-db schemas (AuthSlice), for the default-frame validator
            ;; regression (rf2-3fc89f.32).
            [realworld-shared.schema :as ws]
            [realworld-resources.schema :as app-schema])
  (:require-macros [re-frame.core :refer [with-new-frame]]
                   [re-frame.test-support :refer [with-trace-recorder!]]))

;; ============================================================================
;; FIXTURE
;; ============================================================================

(def ^:private last-managed-args (atom nil))

;; Every managed-HTTP request lowered during a test, in order. `last-managed-args`
;; is enough while one read is in flight at a time, which is every test that
;; ensures by hand — but a ROUTE ACTIVATION lowers a whole resource plan in one
;; dispatch (a parent-chain route lowers the ancestor's shell read AND the leaf's
;; own), so the profile-branch tests below need to address ONE of them by its
;; endpoint. See `managed-request-for`.
(def ^:private managed-args-log (atom []))

;; The shared `make-reset-runtime-fixture`'s post-dispose
;; `:resources/reset-resources!` hook CLEARS the `:resource`, `:mutation`, and
;; `:resource-scope` registrar kinds between tests. CLJS has no
;; `(require … :reload)`, so snapshot the example's ns-load registrations ONCE
;; here (right after the `realworld-resources.core` require above ran them) and
;; re-install them in `init!` (which runs AFTER the post-dispose hooks). This
;; re-installs the EXACT example registrations — not a test-local copy — so each
;; test exercises the example's own `reg-resource` / `reg-mutation` /
;; `reg-resource-scope` declarations. (Routes / the auth machine live under kinds
;; the reset does NOT clear, so they survive via the fixture's ns-load baseline.)
(def ^:private resource-kind-snapshots
  (select-keys @rf.registrar/kind->id->metadata
               [:resource :mutation :resource-scope]))

;; AT NS LOAD, immediately remove THIS example's `:resource` / `:mutation` /
;; `:resource-scope` registrations (by id) from the SHARED live registrar — after
;; snapshotting them above. They are reinstated per-test by `init!`. Why:
;; cljs.test loads every test ns into ONE bundle before running ANY test, so
;; without this our ~21 ns-load `reg-resource` / `reg-mutation` /
;; `reg-resource-scope` registrations sit in the global registrar until some
;; OTHER suite's reset clears them — and a cross-cutting tooling test (the
;; Xray/Story panel e2e, which registers a trace collector) whose post-dispose
;; `:resources/reset-resources!` clears them would mirror the resulting frameless
;; `:rf.registry/handler-cleared` burst into its cascade seed (a false-positive
;; `:rf.xray/cascades`). We remove only OUR ids (not the whole kind) so a sibling
;; suite's ns-load registrations are untouched; ours re-install via `init!`.
(swap! rf.registrar/kind->id->metadata
       (fn [reg]
         (reduce (fn [r [kind id->meta]]
                   (update r kind (fn [m] (apply dissoc m (keys id->meta)))))
                 reg
                 resource-kind-snapshots)))

(defn- init!
  "Per-test setup. The example owns the URL through `:rf/default`
   (`:url-bound? true`); re-register it that way, re-install the example's
   resource / mutation / scope registrations the reset hook wiped, reset routing
   counters, re-publish the late-bound routing integration, and stub managed-HTTP
   + url-push so ensure / navigation are deterministic without a fetch / browser.

   THE ORDER MATTERS, AND THE FRAME IS MADE LAST (rf2-djqm, prophylactic —
   the shape rf2-k4oe repaired in the LinearLite suite). A `:url-bound?` frame
   performs a synchronous initial URL sync AT CONSTRUCTION — `make-frame` ->
   `frame/upsert-frame!`'s post-create hook -> routing's
   `:routing/on-frame-registered!` -> `reconcile-url-listener!` — and under
   Node that URL is `\"/\"`. So a route registered at `\"/\"` has its route-entry
   resource plan run INSIDE `make-frame`. Construct the frame before the
   `registrar/register!` loop below and that plan sees the `:resource` /
   `:mutation` kinds still EMPTY (the shared reset hook cleared them), records
   `:transition :error` / `:rf.error/resource-route-plan` on the routing slice,
   and — because the suite's own navigation never re-plans — the error is
   STICKY.

   This suite is green either way TODAY only because its `\"/\"` route declares
   no BLOCKING resource; add one and it reproduces rf2-k4oe exactly, silently.
   Registering everything first and making the frame last removes the
   dependence entirely, and matches the committed pilot baseline
   (`docs/design/hicasso/product/pilots/baseline/linearlite/baseline_test.cljs`,
   which documents \"the frame is made last\")."
  []
  (reset! last-managed-args nil)
  (reset! managed-args-log [])
  ;; Re-install the example's ns-load resource/mutation/scope registrations.
  ;; rf2-h1vqa4: reinstate through `registrar/register!` — NOT a raw
  ;; registrar-atom swap. Image-loaded frames resolve through the SOURCE
  ;; STORE (the default image is assembled from it), and the reset hook's
  ;; clear-kind! forgot the store rows too; register! writes registrar +
  ;; store in lockstep and marks the live-frame projection dirty, so the
  ;; frame's next resolution sees the reinstated registrations.
  (doseq [[kind id->meta] resource-kind-snapshots
          [id meta] id->meta]
    (rf.registrar/register! kind id meta))
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args]
                                (reset! last-managed-args args)
                                (swap! managed-args-log conj args)
                                nil))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  ;; LAST — see the ordering note in the docstring. Everything the frame's
  ;; construction-time URL sync needs (the reinstated `:resource` / `:mutation`
  ;; rows, the routing integration, the stubbed fx) is registered above.
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "realworld-resources default app frame."}))

(def ^:private isolate-trace-bus-fixture
  "OUTER fixture: keep this resource/mutation-registering suite from leaking
   trace residue into later cross-cutting tooling tests (the Xray/Story panel e2e
   seeds read the process-global trace bus). The leak this closes:

   The shared `make-reset-runtime-fixture` runs `:resources/reset-resources!`
   (post-dispose) BEFORE it clears trace listeners. That hook `clear-kind!`s the
   `:resource` / `:mutation` / `:resource-scope` registrar kinds, emitting one
   FRAMELESS `:rf.registry/handler-cleared` trace per id (Spec 009 §:op-type
   vocabulary). With this suite's ~21 example resources/mutations/scopes
   re-installed every test, that is a recurring burst of frameless traces — and
   if an EARLIER e2e test left the Xray trace-collector LISTENER registered (its
   sentinel-guarded re-register survives our reset), the listener mirrors that
   burst into Xray's process-global frameless ring, which the e2e cascade seeds
   later read as spurious `:rf.xray/cascades`.

   Listed FIRST in `use-fixtures` so it is the OUTERMOST wrapper: its setup runs
   BEFORE the core fixture's post-dispose burst, clearing the trace listeners +
   the framework trace rings so no collector is active to capture any burst, and
   its teardown clears them again so this suite leaves a clean trace bus.

   MAP-FORM (`{:before :after}`), like the reset fixture beside it (`:async?
   true`): `cljs.test` runs an `(async done …)` row — §11's production-seam
   receipt — only when EVERY `:each` fixture is a map, and it runs map
   `:before`s in listing order and `:after`s in reverse, so listing this one
   first still makes it the outermost wrapper.

   (The registrar side is handled separately: this suite's resources / mutations /
   scopes are removed from the SHARED registrar at NS LOAD — see the top-level
   `swap!` above — and captured in the per-test snapshot baseline as ABSENT, so
   they live only inside this suite's own test bodies via `init!`, never
   persisting for another suite's reset to clear.) A later e2e test re-registers
   its own collector (its helper calls `reset-sentinels!` +
   `register-trace-collector!`), so clearing here is safe."
  {:before (fn []
             (rf.trace.tooling/clear-listeners!)
             (rf.trace.tooling/clear-trace-rings!))
   :after  (fn []
             (rf.trace.tooling/clear-listeners!)
             (rf.trace.tooling/clear-trace-rings!))})

(use-fixtures :each
  isolate-trace-bus-fixture
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn init!
     ;; Map-form (rf2-k5lbd): §11's production-seam receipt is an
     ;; `(async done …)` row, and cljs.test runs one only under map fixtures.
     ;; Sync rows are served identically (re-frame.async-reset-fixture-cljs-test
     ;; pins that).
     :async?  true
     ;; BUNDLE CO-LOAD HYGIENE (rf2-kuky.27): the RealWorld twins share id
     ;; vocabulary (`:settings/load`, `:auth/initialise`, …) and both register
     ;; the reserved per-app `:rf.route/not-found` route. `:app-ns` names OUR
     ;; OWN app's whole tree: the fixture removes those rows before it takes
     ;; this suite's baseline — so no suite's baseline ever carries both
     ;; provenances for one id — and reinstates them, registrar + source store
     ;; in lockstep, before each of this suite's tests. The sibling hides
     ;; ITSELF the same way, which is why nothing here names `realworld-http.`
     ;; and why the per-test re-scrub this suite used to carry is gone.
     :app-ns  "realworld-resources."}))

;; ============================================================================
;; HELPERS
;; ============================================================================

(defn- runtime-db
  ([] (runtime-db :rf/default))
  ([frame-id] (:rf.db/runtime (rf/frame-state-value frame-id))))

(defn- entry
  ([scoped-key] (entry :rf/default scoped-key))
  ([frame-id scoped-key] (get-in (runtime-db frame-id) (rf.resources.state/entry-path scoped-key))))

(defn- viewer-scope
  "The concrete `[:rf.scope/viewer {:username …}]` scope for a signed-in reader —
   the identity the `:realworld/viewer` resolver derives for the optional-auth
   reads (rf2-j538f7.29)."
  [username]
  [:rf.scope/viewer {:username username}])

(def ^:private anon-viewer-scope
  "The confirmed-anonymous viewer scope (no user, no token)."
  [:rf.scope/viewer :anonymous])

;; The optional-auth reads (article / profile / comments) are now VIEWER-scoped:
;; each carries the reader's own `favorited` / `following` flags, so its cache
;; identity is the viewer (rf2-j538f7.29). The mutation / populate / seed tests
;; below act as the logged-in user "alice", so their reads land under alice's
;; viewer scope — the 1-arity helpers default to that; the 2-arity form names an
;; explicit viewer for the cross-viewer leak tests.
(defn- article-key
  ([slug] (article-key "alice" slug))
  ([viewer slug] (rf.resources.state/scoped-resource-key (viewer-scope viewer) :realworld/article {:slug slug})))

(defn- feed-key
  "The session-scoped :realworld/feed key for username + page."
  [username page]
  (rf.resources.state/scoped-resource-key [:rf.scope/session {:username username}] :realworld/feed {:page page}))

(defn- profile-key
  ([subject] (profile-key "alice" subject))
  ([viewer subject] (rf.resources.state/scoped-resource-key (viewer-scope viewer) :realworld/profile {:username subject})))

(defn- comments-key
  ([slug] (comments-key "alice" slug))
  ([viewer slug] (rf.resources.state/scoped-resource-key (viewer-scope viewer) :realworld/comments {:slug slug})))

;; The two profile tabs' own paginated lists. Both are viewer-scoped like the
;; banner, and both take the route's `{:username :page}` params — `:page` defaults
;; to 1 on the bare tab URL (the route's `(or (:page q) 1)`), so page 1 is the key
;; a plain tab activation owns.
(defn- author-articles-key
  ([subject page] (author-articles-key "alice" subject page))
  ([viewer subject page]
   (rf.resources.state/scoped-resource-key (viewer-scope viewer) :realworld/author-articles
                              {:username subject :page page})))

(defn- favorited-articles-key
  ([subject page] (favorited-articles-key "alice" subject page))
  ([viewer subject page]
   (rf.resources.state/scoped-resource-key (viewer-scope viewer) :realworld/favorited-articles
                              {:username subject :page page})))

(defn- tags-key
  "The truly-invariant global-scope :realworld/tags key (the one read still global)."
  []
  (rf.resources.state/scoped-resource-key :rf.scope/global :realworld/tags {}))

(defn- reply-success!
  "Replay the captured `:on-success` with the transport's success result
   appended as the LAST arg — the exact shape the live managed-HTTP transport
   produces (Spec 014 §Reply addressing). The reply is dispatched on the SAME
   frame the request ran on (default `:rf/default`) so the reply trace is FRAMED
   (a frameless reply would leak into the global trace ring)."
  ([args data] (reply-success! args data :rf/default))
  ([args data frame]
   (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data})
                     {:frame frame})))

(defn- state-value [frame] (rf/frame-state-value frame))

(defn- route-id [frame] (rf/compute-sub [:rf.route/id] (state-value frame)))
(defn- route-params [frame] (rf/compute-sub [:rf.route/params] (state-value frame)))
(defn- route-query [frame] (rf/compute-sub [:rf.route/query] (state-value frame)))
(defn- route-fragment [frame] (rf/compute-sub [:rf.route/fragment] (state-value frame)))
(defn- return-to [frame] (get-in (rf/app-db-value frame) [:auth :return-to]))

(defn- guarded-frame!
  "A fresh anon frame carrying NO `:interceptors` chain — which is the point.
   Route auth is `:can-enter` metadata on the protected routes themselves
   (routing.cljs), registered at ns load, so a frame needs no auth wiring at all
   for the guard to run: the runtime consults it on the one navigation planning
   pipeline. This helper used to install the retired
   `:realworld-resources.routing/auth-guard` interceptor here, mirroring the
   frame config core.cljs used to carry (rf2-k85nd).

   `:url-bound? true` lets the route slice track the current route (needed so an
   in-place request resolves against it); url-push is a no-op so navigation is
   deterministic without a browser."
  []
  (rf.frame/make-anon-frame-record! {:url-bound?   true
                                  :fx-overrides {:rf.nav/push-url :rf/no-op}}))

(defn- gc-recheck!
  "Fire the GC re-check for a scoped key on `frame` (the timer-fired event).
   An owner-free, work-free entry is collected; a still-pinned one survives."
  [frame scoped-key]
  (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key scoped-key}]
                    {:frame frame}))

(defn- route-owner?
  "True iff `entry` carries an active `[:route route-id* _]` owner. The runtime
   mints ONE owner per activation, keyed by the ACTIVE route's id — for a
   parent-chain route that one owner covers the ancestor-contributed entries too
   — and releases it on route leave. The nav-token is opaque, so match on the
   route id, not the token."
  [entry route-id*]
  (boolean (some (fn [o] (and (vector? o)
                              (= :route (first o))
                              (= route-id* (second o))))
                 (:active-owners entry))))

(defn- editor-route-owner?
  "True iff `entry` carries an active `[:route :realworld.editor/edit _]` owner —
   the route-owned owner the editor's article read holds while the edit route is
   live (rf2-y4mgw: the read is a route `:resource`, owned under
   `[:route route-id nav-token]` and released by the runtime on route leave)."
  [entry]
  (route-owner? entry :realworld.editor/edit))

(defn- managed-request-for
  "The logged managed-HTTP request whose URL contains `url-fragment`, or nil.
   `last-managed-args` remembers only the LAST request, which is ambiguous after
   a route activation lowered a whole plan at once; this addresses one read of
   that plan by the endpoint it was supposed to hit — so a mis-declared resource
   fails on the lookup rather than quietly settling the wrong entry."
  [url-fragment]
  (first (filter (fn [args] (str/includes? (str (get-in args [:request :url])) url-fragment))
                 @managed-args-log)))

;; ============================================================================
;; 1. SESSION-SCOPE RESOLVER — :realworld/session (EP-0016 D3)
;; ============================================================================

(deftest session-scope-resolves-from-auth-username-and-fails-closed-logged-out
  (testing "examples/real-apps/realworld_resources — the named :realworld/session
            resolver derives [:rf.scope/session {:username …}] from
            [:auth :user :username], and resolves nil when logged out (fail-closed)"
    ;; logged out → nil (the fail-closed unresolved condition)
    (is (nil? (rf/resolve-resource-scope {} :realworld/session))
        "no user → nil (never a silent shared scope)")
    (is (nil? (rf/resolve-resource-scope {:auth {:user nil}} :realworld/session)))
    ;; logged in → the concrete per-user scope
    (let [db {:auth {:user {:username "alice"}}}]
      (is (= [:rf.scope/session {:username "alice"}]
             (rf/resolve-resource-scope db :realworld/session))
          "logged in → the per-user session scope")
      ;; the example's convenience helper agrees with the named resolver
      (is (= (rf/resolve-resource-scope db :realworld/session)
             (scope/session-scope {:username "alice"}))
          "scope/session-scope matches the named resolver's value"))))

(deftest resource-scope-policies-viewer-vs-global-vs-session
  (testing "examples/real-apps/realworld_resources — the SIX optional-auth reads
            (whose payloads carry the viewer's favorited/following flags) declare
            :scope {:from-db :realworld/viewer}; only the truly-invariant popular
            tags stays :rf.scope/global; the private feed stays {:from-db
            :realworld/session} (rf2-j538f7.29)"
    ;; the feed is the private, session-scoped read (unchanged).
    (is (= {:from-db :realworld/session}
           (:scope (rf/resource-meta :realworld/feed)))
        "the feed resource's scope is the session resolver reference")
    ;; every optional-auth read is now VIEWER-scoped, not global — 'public' is an
    ;; access policy, not a cache-identity proof.
    (doseq [rid [:realworld/articles :realworld/article :realworld/comments
                 :realworld/profile :realworld/author-articles :realworld/favorited-articles]]
      (is (= {:from-db :realworld/viewer} (:scope (rf/resource-meta rid)))
          (str rid " is viewer-scoped (viewer-relative payload)")))
    ;; only the tags sidebar (bare list of strings, no viewer-relative field) is
    ;; the explicit auditable global claim.
    (is (= :rf.scope/global (:scope (rf/resource-meta :realworld/tags)))
        "the popular-tags read alone is the truly-invariant global claim")))

(deftest viewer-scope-resolver-distinguishes-anon-authed-and-unresolved
  (testing "examples/real-apps/realworld_resources — the :realworld/viewer resolver
            keys a signed-in reader by username, a CONFIRMED anonymous reader by
            :anonymous, and FAILS CLOSED (nil) while a saved token is present but
            the user has not restored yet — so each viewer's representation gets a
            distinct cache identity and the token-authenticated restore window
            never labels a read shareable-anonymous (rf2-j538f7.29)"
    ;; signed in → per-user viewer scope
    (is (= [:rf.scope/viewer {:username "alice"}]
           (rf/resolve-resource-scope {:auth {:user {:username "alice"} :token "jwt"}} :realworld/viewer)))
    (is (= [:rf.scope/viewer {:username "bob"}]
           (rf/resolve-resource-scope {:auth {:user {:username "bob"} :token "jwt"}} :realworld/viewer)))
    ;; confirmed anonymous (no user, no token) → the shareable anonymous identity
    (is (= [:rf.scope/viewer :anonymous]
           (rf/resolve-resource-scope {:auth {:user nil :token nil}} :realworld/viewer)))
    (is (= [:rf.scope/viewer :anonymous]
           (rf/resolve-resource-scope {} :realworld/viewer)))
    ;; UNRESOLVED — token present but user not restored yet → nil, FAIL-CLOSED
    (is (nil? (rf/resolve-resource-scope {:auth {:user nil :token "jwt-restore"}} :realworld/viewer))
        "token present + user unresolved → nil (never an anonymous/global read of an authenticated payload)")
    ;; alice and bob resolve DISTINCT scopes → distinct cache keys for the same read
    (is (not= (rf/resolve-resource-scope {:auth {:user {:username "alice"}}} :realworld/viewer)
              (rf/resolve-resource-scope {:auth {:user {:username "bob"}}} :realworld/viewer)))
    (is (not= (rf/resolve-resource-scope {:auth {:user {:username "alice"}}} :realworld/viewer)
              anon-viewer-scope))))

;; ============================================================================
;; 2. BEARER-HEADER DECORATION — the frame-wide HTTP interceptor
;; ============================================================================

(deftest bearer-interceptor-injects-token-when-authed-and-noops-when-logged-out
  (testing "examples/real-apps/realworld_resources — bearer-auth-interceptor reads
            [:auth :token] from the cascade's frame and injects
            `Authorization: Token <jwt>`; it is a no-op when logged out"
    ;; LOGGED OUT — the public reads must not carry an auth header.
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (let [ctx {:frame f :request {:url "/articles"}}]
        (is (nil? (get-in (core/bearer-auth-interceptor ctx)
                          [:request :headers "Authorization"]))
            "no token → no Authorization header (logged-out reads unaffected)")))
    ;; AUTHED — the token in the frame's app-db is injected as a Bearer header.
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt-xyz"}] {:frame f})
      (let [ctx {:frame f :request {:url "/articles/feed"}}]
        (is (= "Token jwt-xyz"
               (get-in (core/bearer-auth-interceptor ctx)
                       [:request :headers "Authorization"]))
            "the JWT from the auth slice rides every outbound request as a Bearer header")))))

;; ============================================================================
;; 2b. WIRE User vs token-free durable session-user (rf2-3fc89f.32)
;; ============================================================================
;;
;; Same defect as the http variant: the durable AuthSlice validated its :user
;; against the WIRE `ws/User`, which REQUIRES the sensitive :token, while
;; `:auth/store-session` stores `(dissoc user :token)`. Under the active
;; post-commit validator the token-free commit is rejected and the login rolls
;; back. The suite's other auth tests run on anonymous frames (no registered app
;; schema), so the validator never runs — falsely green (the rf2-lo28u lesson).
;; This registers the REAL production `AuthSlice` on the test frame so the
;; genuine validator participates on the genuine store-session commit.

(defn- vec-map-slot-props
  "The properties map of one slot in a vector-form Malli `[:map ...]`, or nil —
   reads the wire User's per-slot `:sensitive?` flag off the pure-data schema."
  [map-schema slot-key]
  (some (fn [entry]
          (when (and (vector? entry) (= slot-key (first entry)) (map? (second entry)))
            (second entry)))
        (rest map-schema)))

(deftest durable-auth-user-validates-token-free-wire-user-still-requires-token
  (testing "examples/real-apps/realworld_resources — the durable AuthSlice user
            validates token-free against the real post-commit validator, while
            the wire User still requires the sensitive :token (rf2-3fc89f.32)"
    ;; WIRE contract UNCHANGED — token-less reply rejected, token slot sensitive.
    (is (true? (m/validate ws/UserResponse
                           {:user {:email "alice@example.com" :username "alice"
                                   :token "jwt-abc" :bio nil :image nil}}))
        "a complete login/register/restore/settings reply (with :token) still decodes")
    (is (false? (m/validate ws/UserResponse
                            {:user {:email "alice@example.com" :username "alice"
                                    :bio nil :image nil}}))
        "a token-LESS reply is STILL rejected by the wire schema — :token stays required")
    (is (true? (:sensitive? (vec-map-slot-props ws/User :token)))
        "the wire User's :token slot stays classified :sensitive? true")
    ;; DURABLE contract — the token-free session user commits and validates.
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      ;; The REAL production AuthSlice on THIS frame → the real validator runs.
      (rf/reg-app-schema [:auth] {:frame f} app-schema/AuthSlice)
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:auth/store-session {:email "alice@example.com"
                                                :username "alice"
                                                :token "jwt-abc"
                                                :bio nil :image nil}]
                          {:frame f})
        ;; RED on old code: wire `ws/User` requires :token, the durable user is
        ;; `(dissoc user :token)` → :app-db schema-validation-failure + rollback.
        (let [violations (filter #(and (= :rf.error/schema-validation-failure (:operation %))
                                       (= :app-db (-> % :tags :where)))
                                 @traces)]
          (is (empty? violations)
              "the token-free durable AuthSlice validates — no :app-db schema-validation-failure"))
        (let [db (rf/app-db-value f)]
          (is (= "alice" (get-in db [:auth :user :username]))
              "the durable session user is committed (no rollback)")
          (is (not (contains? (get-in db [:auth :user]) :token))
              "no token persists under [:auth :user] — the unclassified duplicate is avoided")
          (is (= "jwt-abc" (get-in db [:auth :token]))
              "the JWT rides its one classified durable home at [:auth :token]"))))))

;; ============================================================================
;; 3. MUTATION :populates / cross-scope :invalidates / :reply-to
;; ============================================================================

(deftest favorite-populates-detail-invalidates-both-scopes-and-replies-once
  (testing "examples/real-apps/realworld_resources — :realworld/favorite seeds the
            detail entry from its reply (authoritative load), invalidates the
            global article tags AND the session feed in one set of per-target
            descriptors, and fires no extra wiring; the call-site :reply-to
            continuation fires exactly once on settle"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      ;; log in so the session feed scope resolves
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      ;; own + load the session feed so the invalidation has a live owner to refetch
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :realworld/feed :params {:page nil}
                          :owner [:app :test/feed]}]
                        {:frame f})
      (reply-success! @last-managed-args {:articles [{:slug "hello-conduit"}] :articlesCount 1} f)
      (reset! last-managed-args nil)
      ;; capture the :reply-to continuation
      (let [replied (atom [])]
        (rf/reg-event :test/favorited
          (fn [_ ev] (swap! replied conj ev) {}))
        (rf/dispatch-sync [:rf.mutation/execute
                           {:mutation :realworld/favorite
                            :params   {:slug "hello-conduit"}
                            :instance :test/fav
                            :reply-to [:test/favorited]
                            :cause    [:test :fav]}]
                          {:frame f})
        ;; the write replies with the full Article envelope (the demo stub shape)
        (reply-success! @last-managed-args
                        {:article {:slug "hello-conduit" :title "Hello, Conduit"
                                   :favorited true :favoritesCount 1}}
                        f)
        (testing ":populates seeded the detail entry from the reply (authoritative load)"
          (let [e (entry f (article-key "hello-conduit"))]
            (is (some? e) "the detail entry exists (seeded by :populates)")
            (is (true? (-> e :data :article :favorited))
                "the populated detail reads the favorited flag immediately")))
        (testing "the session feed (session scope) was invalidated by the cross-scope descriptor"
          (let [fe (entry f (feed-key "alice" nil))]
            ;; the owned feed refetches (back in flight) OR is marked stale —
            ;; either way the cross-scope descriptor REACHED the session scope.
            (is (or (contains? #{:loading :fetching} (:status fe))
                    (some? (:invalidated-at fe)))
                "the global-scope mutation reached the session feed (EP-0016 D2)")))
        (testing "the :reply-to continuation fired exactly once with :ok"
          (is (= 1 (count @replied)) "continuation fired once on settle")
          (is (= :ok (:status (last (first @replied))))
              "the appended reply map carries :status :ok"))))))

;; ============================================================================
;; 4. THE EDITOR FLOW (:editor/can-submit?) + :reply-to navigate + :can-leave
;; ============================================================================

(deftest editor-flow-gates-submit-and-reply-to-navigates-to-the-saved-article
  (testing "examples/real-apps/realworld_resources — :editor/can-submit? materialises
            valid-AND-dirty into app-db; a blank draft is invalid; a valid+dirty
            draft submits the save mutation; the :reply-to [:editor/replied]
            continuation re-seeds a clean draft and navigates to the saved article"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      ;; Boot registers the :editor/can-submit? flow ONCE against the frame
      ;; (`:app/initialise` -> `:editor/register-flow`, rf2-xugvye); the create-route
      ;; `:on-match` (`:editor/initialise`) then only resets the slice. Mirror that
      ;; boot ordering here so the flow materialises `[:editor :can-submit?]`.
      (rf/dispatch-sync [:editor/register-flow] {:frame f})
      (rf/dispatch-sync [:editor/initialise] {:frame f})
      ;; blank draft → the flow output is false (invalid + clean)
      (is (false? (rf/compute-sub [:editor/can-submit?] (state-value f)))
          "a blank create draft cannot submit")
      ;; fill the required fields → valid AND dirty → can-submit? true
      (rf/dispatch-sync [:editor/edit-field :title "New Title"] {:frame f})
      (rf/dispatch-sync [:editor/edit-field :description "A desc"] {:frame f})
      (rf/dispatch-sync [:editor/edit-field :body "Some body"] {:frame f})
      (is (true? (rf/compute-sub [:editor/can-submit?] (state-value f)))
          "valid + dirty → the flow materialised true; the submit button enables")
      ;; a dirty draft blocks navigation (:can-leave? false)
      (is (false? (rf/compute-sub [:editor/can-leave?] (state-value f)))
          "a dirty draft blocks navigate-away (the :can-leave guard)")
      ;; submit → the save mutation lowers; capture-then-reply
      (rf/dispatch-sync [:editor/submit] {:frame f})
      (is (some? @last-managed-args) "the save mutation lowered a write")
      ;; the save reply carries the saved Article (the create stub echoes a slug)
      (reply-success! @last-managed-args {:article {:slug "new-title" :title "New Title"
                                                    :description "A desc" :body "Some body"
                                                    :tagList []}}
                      f)
      (testing "the :reply-to continuation re-seeded a clean draft (can leave now)"
        (is (true? (rf/compute-sub [:editor/can-leave?] (state-value f)))
            "after the save reply re-seeds the baseline, the draft is clean → can leave")
        (is (false? (rf/compute-sub [:editor/dirty?] (state-value f)))
            "the saved draft is no longer dirty")))))

;; ============================================================================
;; 5. LOGOUT TEARDOWN — clear-scope + owner release
;; ============================================================================

(deftest logout-clears-both-principal-scopes-and-leaves-global-tags
  (testing "examples/real-apps/realworld_resources — :auth/clear-session clears the
            auth slice and drops BOTH the departing user's session feed AND their
            viewer-scoped reads (articles / profiles carrying that user's
            favorited/following flags) via :rf.resource/clear-scope, so the next
            user can never read a stale entry of theirs. Only the truly-invariant
            global tags read is left alone (rf2-j538f7.29)."
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      ;; alice's SESSION feed
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :realworld/feed :params {:page 1} :owner [:app :test/feed]}]
                        {:frame f})
      (reply-success! @last-managed-args {:articles [{:slug "x"}] :articlesCount 1} f)
      ;; alice's VIEWER-scoped article (carries her favorited flag)
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :realworld/article :params {:slug "hello-conduit"}
                          :owner [:app :test/detail]}]
                        {:frame f})
      (reply-success! @last-managed-args {:article {:slug "hello-conduit" :title "Hi" :favorited true}} f)
      ;; the truly-invariant GLOBAL tags read
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :realworld/tags :scope :rf.scope/global :params {}
                          :owner [:app :test/tags]}]
                        {:frame f})
      (reply-success! @last-managed-args {:tags ["clojure" "conduit"]} f)
      (let [fk (feed-key "alice" 1)]
        (is (some? (entry f fk)) "alice's session feed exists")
        (is (true? (-> (entry f (article-key "hello-conduit")) :data :article :favorited))
            "alice's viewer-scoped article carries her favorited=true")
        (is (some? (entry f (tags-key))) "the global tags read exists")
        ;; LOGOUT
        (rf/dispatch-sync [:auth/clear-session] {:frame f})
        (is (nil? (get-in (rf/app-db-value f) [:auth :user])) "auth user cleared")
        (is (nil? (entry f fk))
            "the session-scoped feed was dropped (clear-scope) — no cross-user leak")
        (is (nil? (entry f (article-key "hello-conduit")))
            "alice's viewer-scoped article was dropped — her favorited=true can't leak")
        (is (some? (entry f (tags-key)))
            "the truly-invariant :rf.scope/global tags read is untouched by logout")))))

;; ============================================================================
;; 6. THE AUTH MACHINE — login drives :idle → :submitting → :authed
;; ============================================================================

(deftest auth-machine-login-stores-the-session
  (testing "examples/real-apps/realworld_resources — the :auth/flow machine drives
            :idle → :submitting → :authed on a login success and stores the
            session (auth is a command/machine, deliberately NOT a read-resource)"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op
                                                      :realworld-resources.session/persist :rf/no-op}})]
      ;; boot the machine at :idle (token nil → the has-token? guard routes to no-op)
      (rf/dispatch-sync [:auth/initialise]
                        {:frame f :rf.cofx {:realworld-resources.session/token nil}})
      (is (= :idle (rf/compute-sub [:auth/state] (state-value f))))
      ;; submit a login → :submitting. rf2-agb5jk (item 1): the machine is
      ;; credential-free — the credential-owning form-submit event issues the
      ;; managed POST itself, exactly as the real app's view dispatches it.
      (rf/dispatch-sync [:auth.login-form/initialise] {:frame f})
      (rf/dispatch-sync [:auth.login-form/edit-field :email "alice@example.com"] {:frame f})
      (rf/dispatch-sync [:auth.login-form/edit-password {:value "x"}] {:frame f})
      (rf/dispatch-sync [:auth.login-form/submit] {:frame f})
      (is (= :submitting (rf/compute-sub [:auth/state] (state-value f))))
      ;; reply success with a Conduit User envelope → :authed + session stored
      (reply-success! @last-managed-args
                      {:user {:username "alice" :email "alice@example.com" :token "jwt-abc"}}
                      f)
      (is (= :authed (rf/compute-sub [:auth/state] (state-value f)))
          "login success → :authed")
      (is (= "alice" (:username (rf/compute-sub [:auth/user] (state-value f))))
          "the session user is stored")
      (is (true? (rf/compute-sub [:auth/authenticated?] (state-value f)))))))

;; ============================================================================
;; 7. SESSION-TOKEN COFX SHAPE — recordable generator (not provided-at-dispatch)
;; ============================================================================

(deftest session-token-cofx-is-a-recordable-generator
  (testing "examples/real-apps/realworld_resources — the saved JWT is an app-owned
            world-read that feeds durable [:auth :token], so it is a recordable
            GENERATOR (a `:recordable? true` reg-cofx whose supplier reads
            localStorage), NOT a provided fact stamped at the dispatch site
            (cofx.md §Decision tree). The generator runs at processing-start, is
            recorded onto the causal token, and replay re-presents the captured
            value verbatim."
    (let [cofx-meta (rf.registrar/handler-meta :cofx :realworld-resources.session/token)]
      (is (true? (:recordable? cofx-meta))
          "the cofx is recordable — its value rides the recorded token")
      (is (not (:provided? cofx-meta))
          "the cofx is NOT provided — it is generator-backed (the app supplies it)")
      (is (fn? (:handler-fn cofx-meta))
          "a recordable generator carries a value-returning supplier fn"))))

;; ============================================================================
;; 8. PAGINATION — the page-nav semantics (rf2-yt7ay6)
;; ============================================================================
;;
;; The PURE page arithmetic (`page->limit-offset`) + query encoding are
;; transport-neutral Conduit contract, extracted to `realworld-shared.http`
;; (rf2-fhxwhj) and pinned ONCE in realworld_shared_contract_cljs_test.cljs.
;; What stays here is the app-SPECIFIC page-nav events integration.

(deftest pagination-nav-events-carry-feed-tag-and-drop-page-1
  (testing "examples/real-apps/realworld_resources — :home/go-to-page and
            :profile/go-to-page keep the active feed / tag / route + username and
            swap only ?page=, and page 1 drops the ?page= param (the canonical
            first-page URL) so page N and N+1 share a filter under distinct keys"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      ;; global feed, page 2 → ?page=2 on the home route
      (rf/dispatch-sync [:home/show-global-feed] {:frame f})
      (rf/dispatch-sync [:home/go-to-page 2] {:frame f})
      (is (= :realworld/home (route-id f)))
      (is (= 2 (:page (route-query f))) "global feed page 2 sets ?page=2")
      ;; page 1 drops the param entirely
      (rf/dispatch-sync [:home/go-to-page 1] {:frame f})
      (is (nil? (:page (route-query f))) "page 1 drops ?page= (canonical first-page URL)")
      ;; the following feed is carried forward across a page change
      (rf/dispatch-sync [:home/show-your-feed] {:frame f})
      (rf/dispatch-sync [:home/go-to-page 2] {:frame f})
      (is (= 2 (:page (route-query f))))
      (is (= "following" (:feed (route-query f)))
          "paging the following feed carries ?feed=following forward")
      ;; the tag is carried forward (re-aims at the /tag/:tag PATH route)
      (rf/dispatch-sync [:home/apply-tag "clojure"] {:frame f})
      (rf/dispatch-sync [:home/go-to-page 2] {:frame f})
      (is (= :realworld/home-tag (route-id f)) "paging a tag list re-aims at /tag/:tag")
      (is (= "clojure" (:tag (route-params f))) "the tag param is preserved")
      (is (= 2 (:page (route-query f))))
      (rf/dispatch-sync [:home/go-to-page 1] {:frame f})
      (is (= :realworld/home-tag (route-id f)) "still on the tag route")
      (is (nil? (:page (route-query f))) "tag page 1 drops ?page= too")
      ;; the profile tab pages independently, on its own route + username
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/show :params {:username "eve"}}] {:frame f})
      (rf/dispatch-sync [:profile/go-to-page 3] {:frame f})
      (is (= :realworld.profile/show (route-id f)) "profile page-nav stays on the same tab")
      (is (= "eve" (:username (route-params f))) "the username is unchanged")
      (is (= 3 (:page (route-query f))))
      (rf/dispatch-sync [:profile/go-to-page 1] {:frame f})
      (is (nil? (:page (route-query f))) "profile page 1 drops ?page="))))

;; ============================================================================
;; 9. THE EDITOR EDIT-MODE LOAD + ROUTE-OWNED TEARDOWN + DELETE (rf2-y4mgw)
;; ============================================================================
;;
;; The editor's CREATE path is covered above (editor-flow-gates-…). This pins the
;; EDIT path: the read-side `:reply-to` seed-on-load, and the ROUTE-OWNED teardown
;; NO-LEAK property. rf2-y4mgw re-homed the article read's lifecycle onto the ROUTE:
;; `:realworld.editor/edit` declares `:realworld/article` as a `:resources` entry,
;; so the runtime owns it under `[:route :realworld.editor/edit nav-token]` and
;; RELEASES that owner on every route leave. That closes the leak the app-minted
;; `[:app :editor/article slug]` had — the old owner was released only on
;; edit→new / edit A→B / delete and (in the Reagent tier) the component unmount, so
;; ordinary route leave in the native (unmount-free) rendition stranded it. Now
;; EVERY exit (edit→new, edit A→B, save, delete, and edit→any-other-route) releases
;; through the one framework lifecycle. The seed-on-load is the route's `:on-match`
;; OWNERLESS `:reply-to [:editor/article-loaded]` ensure — it mints no owner, it
;; joins the route's own read purely to seed. These tests drive REAL navigations
;; (so the route plan runs) and assert active-owners + GC reclaim.

(deftest editor-edit-load-seeds-baseline-then-edit-to-new-releases-owner-and-reclaims
  (testing "examples/real-apps/realworld_resources — edit-mode entry seeds the draft
            + baseline from the article read via the route's `:on-match` ownerless
            :reply-to [:editor/article-loaded] continuation, while the ROUTE owns
            the read under [:route :realworld.editor/edit nav-token]; navigating
            edit→New Article releases that owner (the route leave) so the article
            entry is reclaimed (rf2-y4mgw)"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      ;; EDIT-MODE ENTRY: navigating to /editor/:slug runs the route plan (owns
      ;; :realworld/article under the route owner) AND fires :on-match
      ;; [[:editor/load-article]] (the ownerless :reply-to [:editor/article-loaded]
      ;; seed ensure, which dedupes onto the route's own read).
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/edit :params {:slug "hello-conduit"}}] {:frame f})
      (is (some? @last-managed-args) "edit entry lowered the article read")
      (is (editor-route-owner? (entry f (article-key "hello-conduit")))
          "the article read is pinned by the ROUTE owner, not an app-minted owner")
      (is (not (contains? (:active-owners (entry f (article-key "hello-conduit")))
                          [:app :editor/article "hello-conduit"]))
          "no app-minted [:app :editor/article slug] owner is created any more")
      ;; the read settles → the ownerless :reply-to continuation seeds the baseline
      (reply-success! @last-managed-args
                      {:article {:slug "hello-conduit" :title "Hello, Conduit"
                                 :description "A desc" :body "Some body" :tagList ["clojure"]}}
                      f)
      (testing "the :reply-to [:editor/article-loaded] continuation seeded draft + baseline"
        (is (= "Hello, Conduit" (:title (rf/compute-sub [:editor/draft] (state-value f))))
            "the draft is seeded from the loaded article")
        (is (= "clojure" (:tagList (rf/compute-sub [:editor/draft] (state-value f))))
            "the tag list is joined into the draft's comma-separated string")
        (is (= "hello-conduit" (rf/compute-sub [:editor/slug] (state-value f))))
        (is (false? (rf/compute-sub [:editor/dirty?] (state-value f)))
            "a freshly-seeded edit draft equals its baseline → not dirty")
        (is (true? (rf/compute-sub [:editor/can-leave?] (state-value f)))
            "a clean edit draft may leave freely (the :can-leave guard passes)"))

      ;; EDIT → NEW ARTICLE: a REAL navigation to :realworld.editor/new. The route
      ;; plan releases the outgoing edit owner (route leave); :editor/initialise
      ;; (the /editor on-match) resets the slice.
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/new}] {:frame f})
      (testing "edit→new released the route-owned read (no orphaned owner)"
        (is (not (editor-route-owner? (entry f (article-key "hello-conduit"))))
            "the outgoing edit route owner is released on edit→new")
        (is (nil? (rf/compute-sub [:editor/slug] (state-value f)))
            "the slice is blanked to a fresh create draft (nil slug)"))
      (testing "the released, settled article entry is GC-reclaimed (leak closed)"
        (is (nil? (:current-work (entry f (article-key "hello-conduit"))))
            "the settled read pins no in-flight work")
        (gc-recheck! f (article-key "hello-conduit"))
        (is (nil? (entry f (article-key "hello-conduit")))
            "an owner-free, work-free entry is reclaimed — edit→new leaks nothing")))))

(deftest editor-edit-then-navigate-to-unrelated-route-releases-the-route-owned-read
  (testing "examples/real-apps/realworld_resources — THE rf2-y4mgw FIX: leaving the
            edit route for an UNRELATED route (home) releases the editor's article
            owner. This is the exact path the native (unmount-free) rendition
            leaked: no :editor/* event fires on an ordinary route leave, and the
            old app-minted [:app :editor/article slug] was released only on
            new/A→B/delete/unmount — so a plain edit→home stranded it. With the
            read re-homed onto the route `:resources`, route leave IS the release"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/edit :params {:slug "hello-conduit"}}] {:frame f})
      (reply-success! @last-managed-args
                      {:article {:slug "hello-conduit" :title "Hello, Conduit"
                                 :description "A desc" :body "Some body" :tagList []}}
                      f)
      (is (editor-route-owner? (entry f (article-key "hello-conduit")))
          "the article read is owned while the edit route is live")
      (is (false? (rf/compute-sub [:editor/dirty?] (state-value f)))
          "the seeded draft is clean, so the :can-leave guard won't block")
      ;; ORDINARY ROUTE LEAVE — navigate to home, a route that does NOT read this
      ;; article. RED on the pre-fix code: nothing releases the editor's article
      ;; owner on this path, so the entry stays pinned active forever.
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home}] {:frame f})
      (is (= :realworld/home (route-id f)) "left the editor for home")
      (is (not (editor-route-owner? (entry f (article-key "hello-conduit"))))
          "leaving the editor for an unrelated route releases the article owner")
      (testing "the now-unowned, settled article entry is GC-reclaimed"
        (is (nil? (:current-work (entry f (article-key "hello-conduit"))))
            "the settled read pins no in-flight work")
        (gc-recheck! f (article-key "hello-conduit"))
        (is (nil? (entry f (article-key "hello-conduit")))
            "an owner-free, work-free entry is reclaimed — edit→leave leaks nothing")))))

(deftest editor-delete-clears-slice-releases-route-owner-and-navigates-home
  (testing "examples/real-apps/realworld_resources — :editor/delete fires the delete
            mutation under the shared save instance with :reply-to [:editor/replied];
            the delete branch clears the slice and navigates home, and that
            navigate-home leaves the edit route, so the runtime releases the
            route-owned article read on the way out (rf2-y4mgw)"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/edit :params {:slug "doomed"}}] {:frame f})
      (reply-success! @last-managed-args
                      {:article {:slug "doomed" :title "Doomed" :description "d"
                                 :body "b" :tagList []}}
                      f)
      (is (editor-route-owner? (entry f (article-key "doomed")))
          "the edit route owns the article read before delete")
      ;; DELETE → the delete mutation lowers; reply with no :article (the delete
      ;; endpoint returns no body) → the :editor/replied DELETE branch clears the
      ;; slice and navigates home, which leaves the edit route.
      (rf/dispatch-sync [:editor/delete] {:frame f})
      (reply-success! @last-managed-args {} f)
      (is (not (editor-route-owner? (entry f (article-key "doomed"))))
          "navigating home on delete leaves the edit route → the route owner is released")
      (is (nil? (rf/compute-sub [:editor/slug] (state-value f)))
          "the editor slice is cleared to a blank create draft on delete")
      (is (= :realworld/home (route-id f))
          "a successful delete navigates home"))))

(deftest editor-late-cross-slug-reply-does-not-clobber-the-current-draft
  (testing "examples/real-apps/realworld_resources — the ownerless seed-on-load
            continuation is SLUG-CORRELATED (rf2-y4mgw, the #6569 reopen). Slug A
            and slug B are DISTINCT :realworld/article cache entries with
            independent generations, so leaving edit A for edit B releases A's
            route owner and requests an OPPORTUNISTIC abort — but that abort is
            best-effort (stale suppression is by work-id+generation, per
            release-owner-handler), so a late A settle is still ACCEPTED for A's
            own live entry and fans out to A's `:reply-to [:editor/article-loaded
            article-a]` target AFTER the editor has moved to B. Before the slug
            guard that late reply reseeded the editor slice with A, clobbering the
            B draft the user now edits; now `:editor/article-loaded` seeds only
            while the current route still targets the reply's slug, so the late A
            reply is dropped and the B draft survives"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      ;; ENTER edit A. Capture A's in-flight read WITHOUT settling it — A's fetch is
      ;; still outstanding when we navigate away (the leave-before-settle race).
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/edit :params {:slug "article-a"}}] {:frame f})
      (let [a-read @last-managed-args]
        (is (some? a-read) "edit A lowered the article read")
        (is (editor-route-owner? (entry f (article-key "article-a")))
            "edit A owns its article read while the edit-A route is live")
        ;; EDIT A → EDIT B: a real navigation. The route releases A's owner
        ;; (best-effort abort of A's still-in-flight read) and `:editor/load-article`
        ;; resets the slice to slug B and lowers B's DISTINCT read.
        (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/edit :params {:slug "article-b"}}] {:frame f})
        (let [b-read @last-managed-args]
          (is (not= a-read b-read)
              "edit B lowered a distinct read (a different slug → a different cache key)")
          (is (= "article-b" (rf/compute-sub [:editor/slug] (state-value f)))
              "the editor now targets slug B")
          ;; SETTLE B → the [:editor/article-loaded article-b ...] continuation seeds
          ;; the B draft (the current route targets B, so the guard applies it).
          (reply-success! b-read
                          {:article {:slug "article-b" :title "Bee Article"
                                     :description "about b" :body "body b" :tagList ["bee"]}}
                          f)
          (is (= "Bee Article" (:title (rf/compute-sub [:editor/draft] (state-value f))))
              "the B draft is seeded while the editor targets B")
          (is (false? (rf/compute-sub [:editor/dirty?] (state-value f)))
              "the freshly-seeded B draft equals its baseline")
          ;; THE LATE A SETTLE (ineffective cancellation): replay A's captured read
          ;; reply. The resource gate accepts it for A's own live entry and fans out
          ;; [:editor/article-loaded article-a ...] — AFTER the editor moved to B.
          ;; RED on the pre-guard continuation: it reseeds the slice with A,
          ;; clobbering the B draft. GREEN: the slug guard drops the stale A reply.
          (reply-success! a-read
                          {:article {:slug "article-a" :title "Ay Article"
                                     :description "about a" :body "body a" :tagList ["ay"]}}
                          f)
          (is (= "article-b" (rf/compute-sub [:editor/slug] (state-value f)))
              "the late A reply must NOT re-slug the editor back to A")
          (is (= "Bee Article" (:title (rf/compute-sub [:editor/draft] (state-value f))))
              "the late A reply must NOT clobber the current B draft — the seed is slug-correlated"))))))

(deftest editor-same-slug-seed-does-not-clobber-typed-fields
  (testing "examples/real-apps/realworld_resources — the SAME-SLUG half of the
            clobber, which the cross-slug test above cannot reach (it settles B
            before anyone types into B). Entering edit A and typing before A's
            read settles is the ordinary case, not a race: the round trip is
            slower than the first keystroke. The reply is for the current slug,
            so the slug guard passes it, and the seed it then performs used to be
            a whole-slice `(assoc db :editor (editor-slice …))` that threw the
            keystrokes away — the R-C1 harness case (fitness-harness.md §C.2),
            named MATERIAL by the rf2-y4mgw audit and uncovered by any suite
            until now. The seed is LEAFWISE instead. The withdrawn
            `re-frame.freehand` substrate stated that seed law for its forms
            (`FH-CTRL-013`): a TOUCHED field keeps its own draft AND its own
            baseline, so the typing survives and stays dirty; every untouched
            field takes the loaded article's value in both, so the dirty-check
            still compares against what the server holds."
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      ;; ENTER edit A. Capture A's read WITHOUT settling it — the fetch is still
      ;; outstanding, which is exactly when a user starts typing.
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/edit :params {:slug "article-a"}}] {:frame f})
      (let [a-read @last-managed-args]
        (is (some? a-read) "edit A lowered the article read")
        (is (= "" (:title (rf/compute-sub [:editor/draft] (state-value f))))
            "the pre-settle draft is blank — there is nothing to preserve yet")
        ;; TYPE, mid-flight. `:editor/edit-field` marks :title touched.
        (rf/dispatch-sync [:editor/edit-field :title "My unsaved heading"] {:frame f})
        (is (= #{:title} (:touched (rf/compute-sub [:editor/slice] (state-value f))))
            "typing marked :title touched and nothing else")
        ;; A SETTLES, for the slug the editor is still on. The slug guard passes
        ;; it (correctly — this IS the article being edited), so the seed runs.
        ;; RED on the whole-slice seed: :title becomes "Ay Article".
        (reply-success! a-read
                        {:article {:slug "article-a" :title "Ay Article"
                                   :description "about a" :body "body a" :tagList ["ay"]}}
                        f)
        (let [slice (rf/compute-sub [:editor/slice] (state-value f))
              draft (rf/compute-sub [:editor/draft] (state-value f))]
          (is (= "My unsaved heading" (:title draft))
              "the touched field keeps the user's text — the settle must not clobber typing")
          (is (= "" (:title (:baseline slice)))
              "the touched field keeps its own baseline too, so the typing reads as UNSAVED")
          (is (= "about a" (:description draft))
              "an untouched field IS seeded from the loaded article")
          (is (= "body a" (:body draft)) "…and so is every other untouched field")
          (is (= "ay" (:tagList draft)) "…including the joined tag string")
          (is (= {:title "" :description "about a" :body "body a" :tagList "ay"}
                 (:baseline slice))
              "the baseline is seeded leafwise in step with the draft — asserted whole,
               because the bug is never the leaf you looked at")
          (is (= "article-a" (:slug slice))
              "the slice still targets the loaded slug")
          (is (true? (rf/compute-sub [:editor/dirty?] (state-value f)))
              "typing that survived a settle leaves the draft DIRTY — the save must send it")
          (is (= #{:title} (:touched slice))
              "the seed marks nothing touched of its own"))))))

;; ============================================================================
;; 10. SESSION RESTORE + [:rf.route/replan-resources …] — the documented "restore
;;     stays put" invariant over the framework's replan command (rf2-svj926 →
;;     rf2-y8jjk): the composed-route deep link, the confirmed-anonymous twin,
;;     and the logged-out home
;; ============================================================================
;;
;; Cold-boot session restore is the one principal switch with no accompanying
;; route change, so the route's `{:from-db …}` reads fail closed at entry (the
;; viewer is unresolved) and nothing re-plans them for free. The example used to
;; carry its own 39-line partial planner (`:auth/ensure-viewer-route`) here; it
;; read the LEAF route's handler-meta only, so on a composed route it silently
;; omitted the inherited parent read, and it never touched the durable plan /
;; blocking slots or the slice readiness. Both restore outcomes now dispatch the
;; framework's `[:rf.route/replan-resources {:cause …}]`, which reruns the ONE
;; canonical planner over the registered parent-to-leaf branch under the
;; UNCHANGED nav-token — the tests below pin what the app-side copy could not:
;; the inherited parent read, the stored plan membership, the blocking slot, the
;; repaired `:error` / `:transition`, and the absence of any navigation effect.

(defn- articles-list-key
  "The :realworld/articles home-list key for a viewer scope + page."
  ([scope] (articles-list-key scope 1))
  ([scope page] (rf.resources.state/scoped-resource-key scope :realworld/articles {:tag nil :page page})))

(defn- slice
  "The live route slice `{:route-id :params :query :fragment :transition :error :nav-token}`."
  [frame]
  (get-in (runtime-db frame) [:rf.runtime/routing :current]))

(defn- plan-slot
  "The durable plan-identity map routing records under `token`
   (`[:rf.runtime/routing :resource-plan <token>]`, `{<key-id> <scoped-key>}`)."
  [frame token]
  (get-in (runtime-db frame) [:rf.runtime/routing :resource-plan token]))

(defn- blocking-slot
  "The durable blocking map under `token` (`[:rf.runtime/routing :resource-blocking <token>]`)."
  [frame token]
  (get-in (runtime-db frame) [:rf.runtime/routing :resource-blocking token]))

(defn- by-id
  "The byte-keyed `{<key-id> <scoped-key>}` carrier shape the plan / blocking slots
   hold, built from scoped keys — so a slot can be asserted whole."
  [& scoped-keys]
  (into {} (map (juxt rf.resources.state/key-id identity)) scoped-keys))

(defn- entries-for
  "Every cache entry for `resource-id`, under ANY scope."
  [frame resource-id]
  (into [] (comp (map val) (filter #(= resource-id (second (:resource/key %)))))
        (get-in (runtime-db frame) (rf.resources.state/entries-path))))

(defn- restore-frame!
  "A URL-owning anon frame for the restore tests: url-push and the session-persist
   fx are no-op'd so navigation is deterministic and the token never reaches
   localStorage."
  []
  (rf.frame/make-anon-frame-record! {:url-bound? true
                                  :fx-overrides {:rf.nav/push-url :rf/no-op
                                                 :realworld-resources.session/persist :rf/no-op}}))

(def ^:private activation-trace-ops
  "The trace operations an ACTIVATION emits and a replan must not: the nav-token
   allocation, the lifecycle pair, the planned projection, and the fragment-only
   door's own trace. `:rf.resource/route-plan` rides along so the replan's ONE
   planner row can be read beside them."
  #{:rf.route.nav-token/allocated :rf.route/activated :rf.route/deactivated
    :rf.route/planned :rf.route/fragment-changed :rf.resource/route-plan})

(deftest logged-out-home-plans-articles-and-tags-and-not-the-feed
  (testing "examples/real-apps/realworld_resources — a CONFIRMED-ANONYMOUS cold visit
            to / plans the article list (under the anonymous viewer) and the tags, and
            NOT the session feed: the feed occurrence is admitted by ROUTE DATA only on
            the ?feed=following arm, so a nil session scope is never a whole-plan
            failure on the public home page. Before rf2-y8jjk the feed entry carried
            no :when, its {:from-db :realworld/session} scope resolved nil for a
            logged-out visitor, the WHOLE home plan failed closed, and — because a
            no-token boot takes the machine's :idle no-op branch — nothing ever
            rescued the articles or the tags."
    (with-new-frame [f (restore-frame!)]
      (rf/dispatch-sync [:auth/initialise]
                        {:frame f :rf.cofx {:realworld-resources.session/token nil}})
      (is (= :idle (rf/compute-sub [:auth/state] (state-value f)))
          "no token → the :idle no-op branch — no restore will ever replan this route")
      (is (= anon-viewer-scope (rf/resolve-resource-scope (rf/app-db-value f) :realworld/viewer))
          "no user + no token → the CONFIRMED-anonymous viewer")
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home}] {:frame f})
      (let [{:keys [route-id error transition nav-token]} (slice f)]
        (is (= :realworld/home route-id))
        (is (nil? error) "the home plan FORMED — no nil-session planning error on /")
        (is (= :loading transition) "the blocking article list is in flight")
        (is (some? (entry f (articles-list-key anon-viewer-scope)))
            "the article list is planned under the anonymous viewer")
        (is (some? (entry f (tags-key))) "the popular tags are planned")
        (is (empty? (entries-for f :realworld/feed))
            "the session feed is NOT planned on the bare / — under any scope")
        (is (= (by-id (articles-list-key anon-viewer-scope) (tags-key)) (plan-slot f nav-token))
            "the token's plan slot holds exactly the two planned identities"))
      (testing "the following-feed arm logged out IS a whole-plan planning error, by
                design: the arm asks for the session feed and the session scope is nil"
        (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home :query {:feed "following"}}]
                          {:frame f})
        (let [{:keys [error transition query]} (slice f)]
          (is (= "following" (:feed query)))
          (is (= :rf.error/resource-route-plan (:rf.error/id error))
              "the slice carries the planning error — never a silent omission")
          (is (= :realworld/feed (:resource-id error)) "…and it names the feed")
          (is (= :error transition))
          (is (empty? (entries-for f :realworld/feed))
              "no feed entry under any scope (fail-closed, no partial ensure)"))))))

(deftest session-restore-success-replans-the-following-feed-deep-link-under-the-viewer
  (testing "examples/real-apps/realworld_resources — cold boot with a saved token on the
            /?feed=following deep link: during the token-present / user-unresolved
            window the viewer AND session scopes are fail-closed (nil), so route entry
            is a committed failed activation (no entry under ANY identity — the
            authenticated-response leak the nil rule closes) and the slice carries the
            planning error. Once GET /user settles, :auth/session-restored stores the
            session and dispatches [:rf.route/replan-resources {:cause
            [:session-restore]}]: the same route, the same nav-token, the whole plan
            (articles under alice's viewer, tags, the feed under alice's session) now
            ensured and recorded, the error repaired — and NO navigation (the deep
            link survives) (rf2-j538f7.29, rf2-y8jjk)"
    (with-new-frame [f (restore-frame!)]
      ;; Boot WITH a saved token → :begin-restore (GET /user in flight). Capture
      ;; that request now, before the route plan below lowers others.
      (rf/dispatch-sync [:auth/initialise]
                        {:frame f :rf.cofx {:realworld-resources.session/token "jwt-restore"}})
      (is (= :restoring (rf/compute-sub [:auth/state] (state-value f))))
      (let [restore-req @last-managed-args]
        ;; The viewer is UNKNOWN during restore — the shell gate is up and the
        ;; viewer scope is fail-closed.
        (is (true? (rf/compute-sub [:auth/viewer-resolving?] (state-value f)))
            "the shell defers rendering while the viewer is unresolved")
        (is (nil? (rf/resolve-resource-scope (rf/app-db-value f) :realworld/viewer))
            "token present + user unresolved → viewer scope nil (fail-closed)")
        ;; The URL syncs to the following-feed HOME deep link. Every read fails
        ;; closed — no entry is stored under ANY identity.
        (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home :query {:feed "following"}}]
                          {:frame f})
        (let [{:keys [route-id error transition nav-token]} (slice f)]
          (is (= :realworld/home route-id) "cold boot lands on the home deep link")
          (is (= :rf.error/resource-route-plan (:rf.error/id error))
              "route entry is a committed failed activation while the viewer is unresolved")
          (is (= :error transition))
          (is (nil? (plan-slot f nav-token)) "a failed activation writes no plan slot")
          (is (empty? (entries-for f :realworld/articles))
              "no articles stored under ANY identity during restore — the leak the nil rule closes")
          (is (empty? (entries-for f :realworld/feed)) "no feed stored under any identity either")
          ;; GET /user settles → :auth/session-restored stores alice + replans the route.
          (reply-success! restore-req
                          {:user {:username "alice" :email "alice@example.com" :token "jwt-restore"}}
                          f)
          (is (= :authed (rf/compute-sub [:auth/state] (state-value f))))
          (is (= "alice" (:username (rf/compute-sub [:auth/user] (state-value f)))))
          (is (false? (rf/compute-sub [:auth/viewer-resolving?] (state-value f)))
              "the gate lifts once the viewer resolves")
          (let [after         (slice f)
                articles-key  (articles-list-key (viewer-scope "alice"))
                feed-key*     (feed-key "alice" 1)]
            ;; restore stays put — it does NOT navigate (unlike interactive login).
            (is (= :realworld/home (:route-id after))
                "restore stays put on the deep link — no post-login redirect")
            (is (= nav-token (:nav-token after))
                "the SAME activation: the nav-token is unchanged, so the route owner is the same owner")
            (is (= "following" (get-in after [:query :feed])) "the query survives byte-for-byte")
            ;; the whole plan is NOW ensured under alice, WITHOUT any navigation.
            (is (some? (entry f articles-key)) "the article list is ensured under alice's viewer scope")
            (is (some? (entry f (tags-key))) "the tags are ensured")
            (is (some? (entry f feed-key*)) "the session feed is ensured under alice's session scope")
            (is (contains? (:active-owners (entry f feed-key*)) [:route :realworld/home nav-token])
                "…owned by the ACTIVE route owner, so route leave releases it")
            ;; the durable facts the app-side copy never wrote
            (is (= (by-id articles-key (tags-key) feed-key*) (plan-slot f nav-token))
                "the plan slot equals the materialized three-identity map")
            (is (= (by-id articles-key) (blocking-slot f nav-token))
                "the blocking slot names the blocking article list until it settles")
            (is (nil? (:error after)) "the planning error is REPAIRED")
            (is (= :loading (:transition after)) "readiness re-projected from the new plan")))))

    ;; CONTRAST — an interactive login DOES bounce home, proving navigation is
    ;; observable in this harness (so the non-navigation above is a real signal).
    (with-new-frame [f (restore-frame!)]
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.auth/login}] {:frame f})
      (rf/dispatch-sync [:auth/initialise]
                        {:frame f :rf.cofx {:realworld-resources.session/token nil}})
      (is (= :idle (rf/compute-sub [:auth/state] (state-value f)))
          "no token → the :idle no-op branch")
      ;; rf2-agb5jk (item 1): drive login through the credential-owning
      ;; form-submit event — the machine itself is credential-free.
      (rf/dispatch-sync [:auth.login-form/initialise] {:frame f})
      (rf/dispatch-sync [:auth.login-form/edit-field :email "alice@example.com"] {:frame f})
      (rf/dispatch-sync [:auth.login-form/edit-password {:value "x"}] {:frame f})
      (rf/dispatch-sync [:auth.login-form/submit] {:frame f})
      (reply-success! @last-managed-args
                      {:user {:username "alice" :email "alice@example.com" :token "jwt"}}
                      f)
      (is (= :authed (rf/compute-sub [:auth/state] (state-value f))))
      (is (= :realworld/home (route-id f))
          "interactive login bounces home via :auth/session-established → :auth/post-login-redirect"))))

(deftest session-restore-success-replans-a-composed-deep-link-through-the-parent-chain
  (testing "ACCEPTANCE (rf2-y8jjk) — cold boot with a saved token on the favorites
            tab, a route that INHERITS the BLOCKING :realworld/profile banner from its
            :realworld.profile/show :parent. Route entry fails closed (viewer
            unresolved). Once GET /user lands, [:rf.route/replan-resources {:cause
            [:session-restore]}] reruns the ONE planner over the REGISTERED
            parent-to-leaf branch under the UNCHANGED nav-token: (i) route id / params
            / nav-token are byte-for-byte unchanged; (ii) the INHERITED banner AND the
            leaf list are ensured under alice's viewer; (iii) the plan slot equals the
            materialized two-identity map and the blocking slot names the banner until
            it settles; (iv) the slice error is repaired and :transition goes :loading
            → :idle as the banner reply lands; (v) no URL push / replace, no scroll,
            no activation trace, no :on-match. The retired app-side copy read the
            LEAF's handler-meta only, so it never ensured the banner: the slice kept
            the planning error and the token's slots stayed unwritten."
    (let [pushed   (atom [])
          scrolled (atom [])]
      ;; Capture the host nav fxs GLOBALLY (both platforms) so a push / scroll
      ;; would be observable; the frame below therefore does NOT override
      ;; `:rf.nav/push-url`.
      (rf.fx/reg-fx :rf.nav/push-url    {:platforms #{:server :client}} (fn [_ url]  (swap! pushed conj url)))
      (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ url]  (swap! pushed conj url)))
      (rf.fx/reg-fx :rf.nav/scroll      {:platforms #{:server :client}} (fn [_ args] (swap! scrolled conj args)))
      (with-new-frame [f (rf.frame/make-anon-frame-record!
                           {:url-bound?   true
                            :fx-overrides {:realworld-resources.session/persist :rf/no-op}})]
        (rf/dispatch-sync [:auth/initialise]
                          {:frame f :rf.cofx {:realworld-resources.session/token "jwt-restore"}})
        (is (= :restoring (rf/compute-sub [:auth/state] (state-value f))))
        (let [restore-req @last-managed-args]
          (rf/dispatch-sync [:rf.route/navigate {:to     :realworld.profile/favorites
                                                 :params {:username "celeb"}}]
                            {:frame f})
          (let [{:keys [route-id params nav-token] :as before} (slice f)
                banner-key    (profile-key "alice" "celeb")
                fav-key       (favorited-articles-key "alice" "celeb" 1)
                ;; the ACTIVATION above legitimately pushed a URL and scrolled;
                ;; the replan must add nothing to either log.
                pushes-before  (count @pushed)
                scrolls-before (count @scrolled)]
            (is (= :realworld.profile/favorites route-id))
            (is (= {:username "celeb"} params))
            (is (some? nav-token))
            (is (= :rf.error/resource-route-plan (:rf.error/id (:error before)))
                "route entry failed closed — the viewer is unresolved during restore")
            (is (= :error (:transition before)))
            (is (empty? (entries-for f :realworld/profile))
                "no banner entry under ANY scope (fail-closed, no partial ensure)")
            (is (empty? (entries-for f :realworld/favorited-articles)))
            (is (nil? (plan-slot f nav-token)) "a failed activation writes no plan slot")
            (is (nil? (blocking-slot f nav-token)) "…and no blocking slot")
            (with-trace-recorder! [traces {:pred  #(contains? activation-trace-ops (:operation %))
                                           :shape :by-op}]
              ;; GET /user settles → alice → [:rf.route/replan-resources {:cause [:session-restore]}]
              (reply-success! restore-req
                              {:user {:username "alice" :email "alice@example.com" :token "jwt-restore"}}
                              f)
              (let [after (slice f)]
                ;; (i) the address and the token are untouched
                (is (= [route-id params nav-token]
                       [(:route-id after) (:params after) (:nav-token after)])
                    "route id / params / nav-token byte-for-byte unchanged — the SAME activation")
                (is (= (:query before) (:query after)))
                (is (= (:fragment before) (:fragment after)))
                ;; (ii) the INHERITED parent read and the leaf read, under alice
                (is (some? (entry f banner-key))
                    "the banner INHERITED from :realworld.profile/show is ensured under alice's viewer")
                (is (some? (entry f fav-key))
                    "the leaf's own favorited list is ensured under alice's viewer")
                (is (contains? (:active-owners (entry f banner-key))
                               [:route :realworld.profile/favorites nav-token])
                    "the banner is owned by the ACTIVE route owner (leaf id + unchanged token)")
                (is (empty? (filter #(not= (viewer-scope "alice") (first (:resource/key %)))
                                    (entries-for f :realworld/profile)))
                    "…and under no other viewer identity")
                ;; (iii) the durable plan / blocking facts
                (is (= (by-id banner-key fav-key) (plan-slot f nav-token))
                    "the plan slot equals the materialized two-identity map")
                (is (= (by-id banner-key) (blocking-slot f nav-token))
                    "the blocking slot names the blocking banner until it settles")
                ;; (iv) readiness repaired and re-projected
                (is (nil? (:error after)) "the planning error is REPAIRED")
                (is (= :loading (:transition after)) "the blocking banner is in flight")
                ;; (v) no navigation work of any kind
                (is (= pushes-before (count @pushed)) "no :rf.nav/push-url / replace-url")
                (is (= scrolls-before (count @scrolled)) "no :rf.nav/scroll")
                (let [ops @traces]
                  (is (empty? (:rf.route.nav-token/allocated ops)) "no nav-token was minted")
                  (is (empty? (:rf.route/activated ops)) "no activation lifecycle trace")
                  (is (empty? (:rf.route/deactivated ops)))
                  (is (empty? (:rf.route/planned ops)) "no navigation plan projection")
                  (is (empty? (:rf.route/fragment-changed ops)))
                  (is (= 1 (count (:rf.resource/route-plan ops)))
                      "exactly ONE planner row — the replan itself")
                  (let [tags (:tags (first (:rf.resource/route-plan ops)))]
                    (is (= :replan (:plan-cause tags)) "the row is discriminated as a replan")
                    (is (= [:session-restore] (:replan-cause tags)) "…carrying the caller cause")
                    (is (= nav-token (:nav-token tags)) "…under the unchanged token")
                    (is (= [:realworld.profile/show :realworld.profile/favorites] (:branch tags))
                        "…over the COMPOSED parent-to-leaf branch")
                    (is (= 2 (:ensured tags)))))
                ;; the banner reply lands → the route projects :idle through the
                ;; reply-driven half of the readiness table.
                (reply-success! (managed-request-for "/profiles/celeb")
                                {:profile {:username "celeb" :bio "" :image "" :following false}}
                                f)
                (is (= :loaded (:status (entry f banner-key))))
                (is (= :idle (:transition (slice f))) "readiness lands once the banner settles")
                (is (nil? (:error (slice f))))
                (is (empty? (blocking-slot f nav-token))
                    "the settled banner is pruned from the token's blocking slot")))))))))

(deftest session-restore-failure-replans-a-composed-deep-link-under-anonymous
  (testing "examples/real-apps/realworld_resources — the confirmed-anonymous TWIN of
            the acceptance test: the same favorites deep link, but GET /user is
            REJECTED (401). :abandon-restore clears the session (the viewer resolves
            to :anonymous), STAYS PUT, and dispatches [:rf.route/replan-resources
            {:cause [:session-restore-failed]}]: the inherited banner and the leaf list
            are ensured under the ANONYMOUS viewer under the unchanged nav-token, the
            plan slot equals the two-identity map, and the slice error is repaired —
            never a navigate home, never an entry under a signed-in viewer."
    (with-new-frame [f (restore-frame!)]
      (rf/dispatch-sync [:auth/initialise]
                        {:frame f :rf.cofx {:realworld-resources.session/token "jwt-stale"}})
      (is (= :restoring (rf/compute-sub [:auth/state] (state-value f))))
      (let [restore-req @last-managed-args]
        (rf/dispatch-sync [:rf.route/navigate {:to     :realworld.profile/favorites
                                               :params {:username "celeb"}}]
                          {:frame f})
        (let [{:keys [nav-token] :as before} (slice f)
              banner-key (rf.resources.state/scoped-resource-key anon-viewer-scope :realworld/profile {:username "celeb"})
              fav-key    (rf.resources.state/scoped-resource-key anon-viewer-scope :realworld/favorited-articles
                                                    {:username "celeb" :page 1})]
          (is (= :rf.error/resource-route-plan (:rf.error/id (:error before)))
              "route entry failed closed while the viewer was unresolved")
          (is (empty? (entries-for f :realworld/profile)) "nothing stored under any viewer yet")
          ;; GET /user is REJECTED (401) → :auth/restore-failed → :abandon-restore
          (rf/dispatch-sync (conj (:on-failure restore-req) {:status :error :error {:rf.http/status 401}})
                            {:frame f})
          (is (= :idle (rf/compute-sub [:auth/state] (state-value f))))
          (is (nil? (get-in (rf/app-db-value f) [:auth :token])) "the stale token is cleared")
          (is (= anon-viewer-scope (rf/resolve-resource-scope (rf/app-db-value f) :realworld/viewer))
              "the viewer is now CONFIRMED anonymous")
          (let [after (slice f)]
            (is (= :realworld.profile/favorites (:route-id after))
                "restore failure keeps the deep link in place (no navigate home)")
            (is (= nav-token (:nav-token after)) "the nav-token is unchanged — the same activation")
            (is (some? (entry f banner-key)) "the INHERITED banner is ensured under the anonymous viewer")
            (is (some? (entry f fav-key)) "the leaf list is ensured under the anonymous viewer")
            (is (nil? (entry f (profile-key "alice" "celeb"))) "never stored under a signed-in viewer")
            (is (= (by-id banner-key fav-key) (plan-slot f nav-token))
                "the plan slot equals the materialized two-identity map")
            (is (= (by-id banner-key) (blocking-slot f nav-token)))
            (is (nil? (:error after)) "the planning error is REPAIRED")
            (is (= :loading (:transition after)))))))))

(deftest session-restore-failure-stays-put-and-replans-under-anonymous
  (testing "examples/real-apps/realworld_resources — a restore that FAILS (the saved
            token was rejected) clears the session and STAYS PUT on the public deep
            link, then replans the current route's reads under the now-confirmed
            ANONYMOUS viewer (:abandon-restore → [:rf.route/replan-resources {:cause
            [:session-restore-failed]}]), without navigating home (rf2-j538f7.29,
            gate 5 failure branch; rf2-y8jjk)"
    (with-new-frame [f (restore-frame!)]
      (rf/dispatch-sync [:auth/initialise]
                        {:frame f :rf.cofx {:realworld-resources.session/token "jwt-stale"}})
      (is (= :restoring (rf/compute-sub [:auth/state] (state-value f))))
      (let [restore-req @last-managed-args]
        ;; deep link to a public article page
        (rf/dispatch-sync [:rf.route/navigate {:to :realworld.article/show :params {:slug "public-post"}}] {:frame f})
        (is (= :realworld.article/show (route-id f)))
        ;; during restore the viewer read fails closed — nothing stored anywhere
        (is (nil? (entry f (article-key "alice" "public-post"))))
        (is (nil? (entry f (article-key "public-post"))) "not under a signed-in viewer")
        (is (nil? (entry f (rf.resources.state/scoped-resource-key anon-viewer-scope :realworld/article {:slug "public-post"})))
            "not under the anonymous viewer yet either (viewer still unresolved)")
        (let [token-before (:nav-token (slice f))
              anon-article (rf.resources.state/scoped-resource-key anon-viewer-scope :realworld/article {:slug "public-post"})
              anon-comments (rf.resources.state/scoped-resource-key anon-viewer-scope :realworld/comments {:slug "public-post"})]
          ;; GET /user is REJECTED (401) → :auth/restore-failed → :abandon-restore
          (rf/dispatch-sync (conj (:on-failure restore-req) {:status :error :error {:rf.http/status 401}})
                            {:frame f})
          (is (= :idle (rf/compute-sub [:auth/state] (state-value f)))
              "restore failure lands the machine back at :idle")
          (is (nil? (get-in (rf/app-db-value f) [:auth :token])) "the stale token is cleared")
          (is (false? (rf/compute-sub [:auth/viewer-resolving?] (state-value f)))
              "the viewer is now RESOLVED (confirmed anonymous)")
          ;; STAYS PUT — a failed restore does not yank a public deep link home.
          (is (= :realworld.article/show (route-id f))
              "restore failure keeps the public deep link in place (no navigate home)")
          (is (= token-before (:nav-token (slice f))) "the same activation — no nav-token minted")
          ;; the article read is replanned under the ANONYMOUS viewer, not alice/global.
          (is (some? (entry f anon-article))
              "the article is ensured under the anonymous viewer")
          (is (some? (entry f anon-comments))
              "…and so is its :when-admitted comments sub-resource")
          (is (nil? (entry f (article-key "alice" "public-post")))
              "never stored under a signed-in viewer")
          (is (nil? (entry f (rf.resources.state/scoped-resource-key :rf.scope/global :realworld/article {:slug "public-post"})))
              "never stored under :rf.scope/global")
          (is (= (by-id anon-article anon-comments) (plan-slot f token-before))
              "the plan slot records exactly the replanned membership")
          (is (nil? (:error (slice f))) "the planning error is repaired"))))))

(deftest optional-auth-representation-is-not-shared-across-viewers
  (testing "examples/real-apps/realworld_resources — THE CORE FIX: viewer A's
            optional-auth representation is NOT served to viewer B or an anonymous
            reader. The same article read resolves a DISTINCT cache key per viewer,
            so alice's favorited=true never surfaces in bob's or an anonymous
            reader's UI — and the favorite verb (POST vs DELETE) is therefore chosen
            from the CURRENT viewer's bytes, not the departing one's (rf2-j538f7.29,
            gates 3 + 4)"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      ;; ALICE loads the article; her representation carries favorited=true.
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt-a"}] {:frame f})
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :realworld/article :params {:slug "leak-test"}
                          :owner [:app :test/detail]}]
                        {:frame f})
      (reply-success! @last-managed-args
                      {:article {:slug "leak-test" :title "Leak" :favorited true :favoritesCount 9
                                 :author {:username "carol" :following true}}}
                      f)
      (is (true? (-> (entry f (article-key "alice" "leak-test")) :data :article :favorited))
          "alice's viewer-scoped article carries HER favorited=true")
      (let [read (fn [] (rf/compute-sub [:rf/resource {:resource :realworld/article :params {:slug "leak-test"}}]
                                        (state-value f)))]
        ;; account SWITCH to bob (no logout): his read resolves a DIFFERENT key.
        (rf/dispatch-sync [:auth/store-session {:username "bob" :token "jwt-b"}] {:frame f})
        (let [bob-state (read)]
          (is (not (:has-data? bob-state))
              "bob's viewer-scoped read does NOT see alice's cached representation")
          (is (nil? (get-in bob-state [:data :article :favorited]))
              "bob sees no favorited flag from alice — his favorite verb is chosen from his own (empty) read"))
        ;; alice's entry is untouched at HER key — bob didn't clobber it.
        (is (true? (-> (entry f (article-key "alice" "leak-test")) :data :article :favorited)))
        ;; and a CONFIRMED anonymous reader (logged out) is a distinct key too.
        (rf/dispatch-sync [:auth/clear-session] {:frame f})
        (let [anon-state (read)]
          (is (not (:has-data? anon-state))
              "an anonymous reader does not see alice's favorited=true either"))))))

;; ============================================================================
;; 11. NON-FAVORITE MUTATIONS + failure->message (rf2-xm57ne)
;; ============================================================================
;;
;; Section 3 above (favorite-populates-…) pins the FAVORITE mutation only. These
;; pin the rest of the write surface: the unfavorite zero-clamp adversarial edge,
;; the follow/unfollow :populates seed, the post/delete-comment :invalidates
;; refetch, the update-settings mutation + its :settings/replied continuation, and
;; the two detail-page mutation continuations (:ui/follow-author-replied re-stale,
;; :ui/article-deleted navigate-home). The failure->message projector is
;; transport-neutral Conduit contract, pinned once in the shared contract suite
;; (realworld_shared_contract_cljs_test — rf2-fhxwhj), not re-tested here.

(deftest unfavorite-optimistic-patch-clamps-count-at-zero
  (testing "examples/real-apps/realworld_resources — :realworld/unfavorite's optimistic
            patch (toggle-article-fav) clamps :favoritesCount at zero, so an
            over-eager unfavorite of an already-zero article can't go negative
            (mutations.cljs zero-clamp adversarial edge)"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      ;; Seed the article detail with favorited=true, favoritesCount=0 (the edge:
      ;; a zero count that an unfavorite would otherwise push to -1). No :scope on
      ;; the ensure — the read's spec policy {:from-db :realworld/viewer} resolves
      ;; alice's viewer scope, the same key the optimistic patch targets.
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :realworld/article
                          :params {:slug "hello-conduit"} :owner [:app :test/detail]}]
                        {:frame f})
      (reply-success! @last-managed-args
                      {:article {:slug "hello-conduit" :title "Hello, Conduit"
                                 :favorited true :favoritesCount 0}}
                      f)
      (is (= 0 (-> (entry f (article-key "hello-conduit")) :data :article :favoritesCount))
          "seeded at a zero favorites count")
      (reset! last-managed-args nil)
      ;; Unfavorite → the :optimistic-tags apply runs immediately (before the
      ;; request), patching every [:article slug] entry via toggle-article-fav.
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :realworld/unfavorite
                          :params   {:slug "hello-conduit" :username "alice"}
                          :instance [:favorite "hello-conduit"]
                          :cause    [:test :unfav]}]
                        {:frame f})
      (let [art (-> (entry f (article-key "hello-conduit")) :data :article)]
        (is (false? (:favorited art)) "the optimistic apply flips favorited off")
        (is (= 0 (:favoritesCount art))
            "the count clamps at zero — an over-eager unfavorite can't go negative")))))

(deftest follow-and-unfollow-populate-the-profile-banner-from-the-reply
  (testing "examples/real-apps/realworld_resources — :realworld/follow / :realworld/unfollow
            seed the [:profile username] banner from their reply (:populates), so the
            banner's :following flips immediately without waiting on the refetch"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      ;; own + load the profile banner (following=false) so :populates has an
      ;; entry. No :scope — the spec policy lands it under alice's viewer scope.
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :realworld/profile
                          :params {:username "eve"} :owner [:app :test/profile]}]
                        {:frame f})
      (reply-success! @last-managed-args {:profile {:username "eve" :bio "" :image "" :following false}} f)
      (is (false? (-> (entry f (profile-key "eve")) :data :profile :following))
          "the banner starts unfollowed")
      (reset! last-managed-args nil)
      ;; FOLLOW → reply following=true → :populates seeds the banner.
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :realworld/follow :params {:username "eve"}
                          :instance [:follow "eve"] :cause [:test :follow]}]
                        {:frame f})
      (reply-success! @last-managed-args {:profile {:username "eve" :bio "" :image "" :following true}} f)
      (is (true? (-> (entry f (profile-key "eve")) :data :profile :following))
          ":realworld/follow :populates the banner to :following true from its reply")
      (reset! last-managed-args nil)
      ;; UNFOLLOW → reply following=false → :populates seeds it back.
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :realworld/unfollow :params {:username "eve"}
                          :instance [:follow "eve"] :cause [:test :unfollow]}]
                        {:frame f})
      (reply-success! @last-managed-args {:profile {:username "eve" :bio "" :image "" :following false}} f)
      (is (false? (-> (entry f (profile-key "eve")) :data :profile :following))
          ":realworld/unfollow :populates the banner back to :following false"))))

(deftest post-and-delete-comment-invalidate-the-comments-read
  (testing "examples/real-apps/realworld_resources — :realworld/post-comment and
            :realworld/delete-comment invalidate [:comments slug], so the mounted
            article page's owned comments read refetches to truth"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      ;; own + load the comments read so the invalidation has a live owner to
      ;; refetch. No :scope — the spec policy lands it under alice's viewer scope.
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :realworld/comments
                          :params {:slug "hello-conduit"} :owner [:app :test/comments]}]
                        {:frame f})
      (reply-success! @last-managed-args {:comments [{:id 1 :body "hi" :author {:username "eve"}}]} f)
      (is (= :loaded (:status (entry f (comments-key "hello-conduit")))))
      (reset! last-managed-args nil)
      ;; POST comment → invalidates [:comments slug] → the owned read refetches.
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :realworld/post-comment
                          :params   {:slug "hello-conduit" :body "great read"}
                          :instance [:post-comment "hello-conduit"] :cause [:test :post]}]
                        {:frame f})
      (reply-success! @last-managed-args {:comment {:id 2 :body "great read" :author {:username "alice"}}} f)
      (let [ce (entry f (comments-key "hello-conduit"))]
        (is (or (contains? #{:loading :fetching} (:status ce)) (some? (:invalidated-at ce)))
            ":realworld/post-comment reached the comments read (invalidate → refetch)"))
      (reset! last-managed-args nil)
      ;; DELETE comment → invalidates [:comments slug] → refetch again.
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :realworld/delete-comment
                          :params   {:slug "hello-conduit" :id 2}
                          :instance [:delete-comment "hello-conduit" 2] :cause [:test :del]}]
                        {:frame f})
      (reply-success! @last-managed-args {} f)
      (let [ce (entry f (comments-key "hello-conduit"))]
        (is (or (contains? #{:loading :fetching} (:status ce)) (some? (:invalidated-at ce)))
            ":realworld/delete-comment reached the comments read (invalidate → refetch)")))))

(deftest update-settings-mutation-folds-user-into-auth-and-navigates
  (testing "examples/real-apps/realworld_resources — :settings/submit fires the
            :realworld/update-settings mutation; the :reply-to [:settings/replied]
            continuation folds the saved User into the auth slice and navigates to
            the profile on :ok"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op
                                                      :realworld-resources.session/persist :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :email "a@b.c" :token "jwt"
                                              :bio nil :image nil}] {:frame f})
      ;; Seed the draft from the user, then edit the bio.
      (rf/dispatch-sync [:settings/load] {:frame f})
      (rf/dispatch-sync [:settings/edit-field :bio "A brand new bio"] {:frame f})
      (rf/dispatch-sync [:settings/submit] {:frame f})
      (is (some? @last-managed-args) "the settings PUT lowered a write")
      ;; Reply with the saved User → :settings/replied folds it into auth + navigates.
      (reply-success! @last-managed-args
                      {:user {:username "alice" :email "a@b.c" :token "jwt2"
                              :bio "A brand new bio" :image nil}}
                      f)
      (is (= "A brand new bio" (get-in (rf/app-db-value f) [:auth :user :bio]))
          ":settings/replied folded the saved User into the auth slice")
      (is (= :realworld.profile/show (route-id f))
          ":settings/replied navigates to the user's profile on :ok")
      (is (= "alice" (:username (route-params f)))
          "the profile route carries the saved username"))))

(deftest follow-author-continuation-restales-the-detail-article
  (testing "examples/real-apps/realworld_resources — :ui/follow-author fires the follow
            mutation with :reply-to [:ui/follow-author-replied slug]; on settle the
            continuation re-stales [:article slug] so the detail page's embedded
            author flag refetches (the follow mutation itself only invalidates
            [:profile username])"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      ;; own + load the article detail so the re-stale has a live owner to
      ;; refetch. No :scope — the spec policy lands it under alice's viewer scope.
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :realworld/article
                          :params {:slug "hello-conduit"} :owner [:app :test/detail]}]
                        {:frame f})
      (reply-success! @last-managed-args
                      {:article {:slug "hello-conduit" :title "Hello"
                                 :author {:username "eve" :following false}}}
                      f)
      (is (= :loaded (:status (entry f (article-key "hello-conduit")))))
      (reset! last-managed-args nil)
      ;; Follow the author from the detail page.
      (rf/dispatch-sync [:ui/follow-author "hello-conduit" "eve" false] {:frame f})
      (reply-success! @last-managed-args {:profile {:username "eve" :following true}} f)
      (let [ae (entry f (article-key "hello-conduit"))]
        (is (or (contains? #{:loading :fetching} (:status ae)) (some? (:invalidated-at ae)))
            ":ui/follow-author-replied re-staled [:article slug] → the detail refetches")))))

(deftest delete-article-continuation-navigates-home
  (testing "examples/real-apps/realworld_resources — :ui/delete-article fires the
            :realworld/delete-article mutation with :reply-to [:ui/article-deleted];
            the continuation clears the instance and navigates home on :ok"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      ;; Land somewhere other than home so the navigate-home is observable.
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.auth/register}] {:frame f})
      (is (= :realworld.auth/register (route-id f)))
      (reset! last-managed-args nil)
      (rf/dispatch-sync [:ui/delete-article "hello-conduit"] {:frame f})
      (is (some? @last-managed-args) "the delete mutation lowered a write")
      (reply-success! @last-managed-args {} f)
      (is (= :realworld/home (route-id f))
          ":ui/article-deleted navigates home on a successful delete"))))

(deftest auth-guard-return-to-preserves-full-address
  ;; rf2-78x8j (twin of rf2-k5zty in realworld_http) — the return-to stash is the
  ;; FULL resolved address, so a login bounce-back lands on the EXACT URL the
  ;; visitor was headed for, not a bare route. Before the fix the stash was
  ;; {:id :params}, stranding the query string and #fragment.
  ;;
  ;; rf2-k85nd retargeted this onto the `:can-enter` gate: the guard is route
  ;; metadata, the runtime hands the denial handler an already-resolved
  ;; `:destination` (a `:rf/route-destination`), routing.cljs writes THAT to the
  ;; crumb, and :auth/post-login-redirect (auth.cljs) reads it back wholesale via
  ;; [:rf.route/navigate (assoc return-to :replace? true)]. The retired auth-guard
  ;; interceptor re-derived the address itself with `match-url`; the destination is
  ;; the framework's own answer, so the expectations below are the MINIMAL
  ;; destination shape (no `:query {}` / `:fragment nil` padding) rather than the
  ;; interceptor's always-four-keys map.
  (testing "examples/real-apps/realworld_resources — auth return-to preserves query + #fragment (rf2-78x8j)"

    ;; --- 1. destination deep-link carrying BOTH a query and a #fragment ---
    (with-new-frame [f (guarded-frame!)]
      ;; Logged-out deep-link (URL-bar / reload) to the guarded editor with a query
      ;; AND a fragment. (`tab` is an undeclared query key — the guarded routes
      ;; declare none — so it rides as a string key; the point is it SURVIVES rather
      ;; than being stranded, exactly as the #fragment does.)
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/my-slug?tab=preview#comments"] {:frame f})
      (is (= :realworld.auth/login (route-id f))
          "deep-link to a guarded route with ?query#fragment is refused → login")
      (is (= {:to       :realworld.editor/edit
              :params   {:slug "my-slug"}
              :query    {"tab" "preview"}
              :fragment "comments"}
             (return-to f))
          "the stash carries the FULL address — query and #fragment included, not stranded")

      ;; Sign in and bounce back — the return lands on the EXACT address.
      (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
      (rf/dispatch-sync [:auth/post-login-redirect] {:frame f})
      (is (= :realworld.editor/edit (route-id f))
          "bounce-back landed on the editor route")
      (is (= {:slug "my-slug"} (route-params f))
          "bounce-back restored the path params")
      (is (= {"tab" "preview"} (route-query f))
          "bounce-back restored the query — NOT stranded")
      (is (= "comments" (route-fragment f))
          "bounce-back restored the #fragment — NOT stranded")
      (is (nil? (return-to f))
          "the crumb was read AND cleared in one step"))

    ;; --- 2. in-place edit under an expired session ---
    (with-new-frame [f (guarded-frame!)]
      ;; Enter the editor legitimately, then let the session expire.
      (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/my-slug"] {:frame f})
      (is (= :realworld.editor/edit (route-id f))
          "entered the editor while signed in")
      (rf/dispatch-sync [:auth/clear-session] {:frame f})
      ;; An in-place navigation (change only the #fragment) is re-gated by the
      ;; guard — the classic in-place fail-open door, closed — and the stash carries
      ;; the resolved in-place address (current route + params + the new #fragment).
      (rf/dispatch-sync [:rf.route/navigate {:fragment "comments"}] {:frame f})
      (is (= :realworld.auth/login (route-id f))
          "the in-place edit under an expired session is refused → login")
      (is (= {:to       :realworld.editor/edit
              :params   {:slug "my-slug"}
              :fragment "comments"}
             (return-to f))
          "the in-place edit's resolved address (current route + new #fragment) is stashed whole"))

    ;; --- 3. absent query/#fragment and an unmatched URL degrade gracefully ---
    (with-new-frame [f (guarded-frame!)]
      ;; A guarded destination with NO query and NO fragment stashes the MINIMAL
      ;; named address — `{:to id}`, no `:params {}` / `:query {}` / `:fragment nil`
      ;; padding, because `:rf/route-destination`'s address branch omits what is
      ;; empty. This is the commonest denial there is, so it is the one the
      ;; AuthSlice `:return-to` schema has to accept — and demanding all four keys
      ;; is exactly what used to roll this stash back (rf2-k85nd).
      (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame f})
      (is (= :realworld.auth/login (route-id f))
          "logged-out reload of a guarded route with no query/#fragment → login")
      (is (= {:to :realworld.user/settings}
             (return-to f))
          "a bare guarded destination stashes the minimal named address, and it SURVIVES the commit")

      ;; An unmatched URL is not a protected route — no `:can-enter` to consult, so
      ;; the gate is inert (no redirect, no new stash, no crash).
      (let [crumb (return-to f)]
        (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-route-zzzz"] {:frame f})
        (is (not= :realworld.auth/login (route-id f))
            "an unmatched URL is not gated — the gate leaves it alone")
        (is (= crumb (return-to f))
            "an unmatched URL leaves the existing crumb untouched — no spurious re-stash")))

    ;; --- 4. THE DEFERRED WINDOW: a protected deep link mid-restore is NOT
    ;;     bounced to login (rf2-k85nd) ---
    (with-new-frame [f (guarded-frame!)]
      ;; Cold boot with a saved JWT but no restored user yet — exactly what the
      ;; frame's `:initial-events` leave behind while `GET /user` is in flight.
      ;; `:rf.http/managed` is stubbed to CAPTURE and not reply (see `init!`), so
      ;; the restore genuinely stays outstanding, which is the whole point.
      (rf/dispatch-sync [:auth/initialise]
                        {:frame f
                         :rf.cofx {:realworld-resources.session/token "jwt-in-flight"}})
      (is (nil? (get-in (rf/app-db-value f) [:auth :user]))
          "identity has not arrived — the restore GET is still outstanding")
      (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame f})
      (is (not= :realworld.auth/login (route-id f))
          "identity unknown → the login bounce is DEFERRED, not taken")
      (is (= {:to :realworld.user/settings} (return-to f))
          "the deferred destination is stashed for :auth/settle-deferred-entry")
      ;; Restore lands: the stash is consumed by a FRESH navigate that the guard
      ;; now allows.
      (rf/dispatch-sync [:auth/store-session {:username "eve" :token "jwt-in-flight"}] {:frame f})
      (rf/dispatch-sync [:auth/settle-deferred-entry] {:frame f})
      (is (= :realworld.user/settings (route-id f))
          "restore settled → the fresh attempt enters the originally-requested route")
      (is (nil? (return-to f))
          "the stash was consumed"))))

;; ============================================================================
;; 12. THE PROFILE TABS' :parent BRANCH — this table's own EP-0037 R2 wiring
;; ============================================================================
;;
;; `:realworld.profile/favorites` is the one route in the shipped table that
;; declares a `:parent` (`:realworld.profile/show`) AND its own `:resources`, so
;; activating it has to compose the parent's banner read with the leaf's favorited
;; list — Spec 016 §Effective parent-chain resource plans. The framework claim is
;; pinned generically by the resources artefact's own r2-* suite
;; (`resources_route_cljs_test`) against a purpose-built exercise app. What these
;; two pin is THIS route table, which is the thing users copy.
;;
;; Nothing navigated to the favorites tab before (rf2-8vccg): the ~30 tests above
;; reach the profile only through `{:to :realworld.profile/show}`, so the branch
;; was live in the shipped example and unexercised. A broken `:parent` link, a leaf
;; that restated the banner, or a `:when` gate that let the authored list follow
;; the visitor onto the favorites tab would all have shipped silently.

(deftest profile-favorites-tab-composes-the-parent-banner-with-its-own-list
  (testing "examples/real-apps/realworld_resources — activating
            :realworld.profile/favorites ensures BOTH the banner read its
            `:parent :realworld.profile/show` contributes and the leaf's own
            :realworld/favorited-articles, under the one active-route owner, while
            the parent's authored list stays gated off by its `:when` (rf2-8vccg)"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/navigate {:to     :realworld.profile/favorites
                                             :params {:username "eve"}}] {:frame f})
      (is (= :realworld.profile/favorites (route-id f)) "landed on the favorites tab")
      (testing "the parent's banner is composed into the leaf's plan"
        (let [banner (entry f (profile-key "eve"))]
          (is (some? banner)
              "the `:parent`'s :realworld/profile banner is ensured on child activation")
          (is (route-owner? banner :realworld.profile/favorites)
              "…owned by the ACTIVE leaf route's owner, so leaving the tab releases it")))
      (testing "the leaf's own list is ensured, and only the leaf's"
        (is (some? (entry f (favorited-articles-key "eve" 1)))
            "the leaf's :realworld/favorited-articles is ensured at page 1")
        (is (route-owner? (entry f (favorited-articles-key "eve" 1))
                          :realworld.profile/favorites)
            "…under the same one route owner as the inherited banner")
        (is (nil? (entry f (author-articles-key "eve" 1)))
            "the parent's authored list stays gated off — its `:when` names the show leaf"))
      (testing "each composed read reached its own Conduit endpoint"
        (is (some? (managed-request-for "/profiles/eve"))
            "the inherited banner fetched GET /profiles/:username")
        (is (some? (managed-request-for "favorited=eve"))
            "the leaf fetched GET /articles?favorited=:username — not the authored list")))))

(deftest profile-tab-move-keeps-the-inherited-banner-and-swaps-the-lists
  (testing "examples/real-apps/realworld_resources — moving between the two profile
            tabs KEEPS the banner the `:parent` contributes: it is adopted, not
            refetched (generation unchanged, data intact), while the departed tab's
            own list loses the route owner and the arriving tab's list is ensured —
            EP-0037 R2 partial revalidation, on the shipped table (rf2-8vccg)"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/navigate {:to     :realworld.profile/favorites
                                             :params {:username "eve"}}] {:frame f})
      ;; Settle the BANNER specifically. The activation lowered the whole plan in
      ;; one dispatch, so `last-managed-args` now holds the leaf's list request —
      ;; address the banner by its own endpoint instead.
      (reply-success! (managed-request-for "/profiles/eve")
                      {:profile {:username "eve" :bio "" :image "" :following false}} f)
      (is (= :loaded (:status (entry f (profile-key "eve"))))
          "the inherited banner is loaded before the tab move — the genuinely reusable case")
      (let [gen-before (:generation (entry f (profile-key "eve")))]
        ;; THE SIBLING MOVE: favorites → show. Same parent, different leaf.
        (rf/dispatch-sync [:rf.route/navigate {:to     :realworld.profile/show
                                               :params {:username "eve"}}] {:frame f})
        (is (= :realworld.profile/show (route-id f)) "moved to the authored-articles tab")
        (testing "the shared banner is kept across the tab move, not refetched"
          (is (= gen-before (:generation (entry f (profile-key "eve"))))
              "generation unchanged — the banner is adopted, not revalidated")
          (is (= "eve" (-> (entry f (profile-key "eve")) :data :profile :username))
              "…and keeps its loaded data, so the banner never blanks between tabs")
          (is (route-owner? (entry f (profile-key "eve")) :realworld.profile/show)
              "the arriving tab's owner is attached to the kept banner (owner handoff)"))
        (testing "the tab-specific lists swap with the leaf"
          (is (some? (entry f (author-articles-key "eve" 1)))
              "the show leaf's `:when` gate now opens — the authored list is ensured")
          (let [fav (entry f (favorited-articles-key "eve" 1))]
            (is (or (nil? fav) (not (route-owner? fav :realworld.profile/favorites)))
                "the departed favorites tab's list is no longer route-owned")))))))

;; ============================================================================
;; ROUTE-LINK EGRESS BASE — the Reagent arm's own deployment mount
;; ============================================================================
;;
;; TRIMMED, NOT DELETED (rf2-0yp7w.4). This section was the rf2-nn5s8 audit
;; rider: the re-frame.ui arm (`ui_core.cljs`, build
;; `:examples/realworld-resources-ui`) was served under its OWN mount,
;; `/realworld-resources-ui` — a prefix-sharing SIBLING of the Reagent arm's
;; `/realworld-resources` — and PR #6648 had shipped the ui entry reusing the
;; Reagent `url-strategy`, so its shell booted into not-found. The pins proved
;; the repaired entry-specific `url-strategy-ui` on both legs, ingress
;; `:decode` and egress `route-link`.
;;
;; The ui arm, its build id and `url-strategy-ui` are deleted, so the ingress
;; leg and the two-arm comparison have no subject left. The EGRESS pin below
;; survives on its own merit and is kept deliberately: it is the only assertion
;; in this namespace that `route-link` href synthesis carries the Reagent arm's
;; served mount base, and that is live production behaviour. The examples tree
;; itself stays test-free (rf2-8cevm).

(deftest route-link-egress-carries-the-arms-own-mount-base
  (testing "egress: a route-link rendered on the Reagent arm's frame config
            targets the arm's OWN served mount base"
    (with-new-frame [f (rf.frame/make-anon-frame-record!
                         {:url-strategy app-routing/url-strategy})]
      (let [[_ attrs] (rf/with-frame f
                        (rf.routing.link/route-link-render {:to :realworld.auth/login}))]
        (is (= "/realworld-resources/login" (:href attrs))
            "the Reagent arm's generated link carries its own mount base")))))

;; ============================================================================
;; 11. THE PRODUCTION-SEAM RECEIPT — read → write → invalidate → refetch against
;;     the demo backend the served app runs on (rf2-9n43e part B, rf2-k5lbd)
;; ============================================================================
;;
;; Every other test in this file answers managed HTTP by hand: `init!` swaps
;; `:rf.http/managed` for a capture, and each test replays the reply it wants.
;; That pins the app's wiring, but it cannot pin the one claim the example's
;; README makes — that a write is still there after the refetch it causes —
;; because the reply is whatever the test says it is. rf2-9n43e found exactly
;; that gap: the old comment test hand-injected the write reply and asserted
;; only that a refetch BEGAN, while the shipped backend answered the refetch
;; out of a frozen seed and every comment vanished on success.
;;
;; This receipt answers nothing by hand. The frame is wired the way `core.cljs`
;; wires the served app (`:fx-overrides {:rf.http/managed
;; :realworld-resources.demo/http-stub}`), so every read and write goes to the
;; app's own `demo-state` world through the shared demo backend; each reply
;; comes back through the backend's own deferred path (`:after-ms` →
;; `:dispatch-later`, a real 20 ms later); and the test WAITS for the runtime to
;; settle rather than settling it. `:realworld/post-comment` declares
;; `:invalidates` and no `:populates`, so the only way the new comment can
;; reach the entry is the refetch the invalidation causes — which is the
;; example's headline, and the sequence this row proves.
;;
;; Async, and the suite's fixtures are map-form for exactly this row: the
;; deliverer's first hop is an async router dispatch that a `dispatch-sync`
;; body never drains (linearlite_example_cljs_test.cljs §5 records the same
;; boundary), and `cljs.test` runs an `(async done …)` body only under map
;; fixtures. `with-new-frame` is deliberately NOT used — it destroys the frame
;; when the body RETURNS, which for an async body is before anything settles —
;; so the frame is a plain anon record and every dispatch names it.

(def ^:private demo-user
  "The demo world's one user, as `POST /users/login` issues them — the identity
   the backend stamps on every write, and the viewer the reads land under."
  {:email "demo@conduit.dev" :token "stub.demo.jwt" :username "demo"
   :bio "Canned demo user." :image ""})

(defn- production-seam-frame!
  "A frame wired the way `realworld-resources.core/mount!` wires the served app:
   URL-owning, with managed HTTP redirected to this app's PRODUCTION demo-backend
   seam. url-push and the session-persist fx are no-op'd, as in `restore-frame!`."
  []
  (rf.frame/make-anon-frame-record!
    {:url-bound?   true
     :fx-overrides {:rf.http/managed                     :realworld-resources.demo/http-stub
                    :rf.nav/push-url                     :rf/no-op
                    :realworld-resources.session/persist :rf/no-op}}))

(defn- comment-ids [e] (mapv :id (get-in e [:data :comments])))

(defn- backend-comments
  "What the demo backend would answer `GET /articles/<slug>/comments` with RIGHT
   NOW — a pure read of the app's own world, no frame involved. This is the
   server truth the receipt compares the settled entry against."
  [slug]
  (:ok (second (demo/transition @app-http/demo-state
                                {:request {:method :get
                                           :url    (app-http/full-url
                                                     (str "/articles/" slug "/comments"))}}))))

(deftest production-seam-receipt-a-comment-survives-the-refetch-it-causes
  (testing "examples/real-apps/realworld_resources — against the app's PRODUCTION
            demo backend, with no hand-injected reply anywhere: the article route
            plans the comments read; it settles from the backend; the comment
            form's own submit runs :realworld/post-comment through the same seam;
            the write settles, invalidates the route-owned read, the runtime
            refetches it on its own, and the refetch settles on the backend's
            CURRENT state — the comment just written is in it"
    (async done
      ;; The documented reset boundary: this receipt's world, and nobody else's.
      (reset! app-http/demo-state (demo/fresh-state))
      (let [f          (production-seam-frame!)
            slug       "hello-conduit"
            k          (comments-key "demo" slug)
            first-load (atom nil)]
        (rf/dispatch-sync [:auth/store-session demo-user] {:frame f})
        (rf/dispatch-sync [:rf.route/navigate {:to :realworld.article/show :params {:slug slug}}]
                          {:frame f})
        (is (= :loading (:status (entry f k)))
            "route entry planned the comments read under the demo viewer, in flight")
        (-> (rf.test-support/poll-until
              #(= :loaded (:status (entry f k)))
              {:label "the route-owned comments read settles from the demo backend"})
            (.then (fn [_]
                     (let [e (entry f k)]
                       (reset! first-load (select-keys e [:generation :loaded-at]))
                       (is (= [1] (comment-ids e))
                           "the first load is the backend's one seeded comment — nothing hand-injected")
                       (is (= (backend-comments slug) (:data e))
                           "…and equal to what the backend answers that GET with right now"))
                     ;; The app's own comment form. `:comment-form/submit` executes
                     ;; `:realworld/post-comment`, whose reply goes to the mutation
                     ;; runtime, never to the entry: `:invalidates`, no `:populates`.
                     (rf/dispatch-sync [:comment-form/edit "great read"] {:frame f})
                     (rf/dispatch-sync [:comment-form/submit slug] {:frame f})
                     (rf.test-support/poll-until
                       #(let [e (entry f k)
                              m (rf/compute-sub [:rf/mutation {:instance [:post-comment slug]}]
                                                (state-value f))]
                          (and (true? (:success? m))
                               (> (:generation e) (:generation @first-load))
                               (= :loaded (:status e))))
                       {:label "the write settles, the invalidated read refetches, and the refetch settles"})))
            (.then (fn [_]
                     (let [e (entry f k)]
                       (is (= [1 1000] (comment-ids e))
                           "the refetched read carries the seeded comment AND the one just written — the write survived the refetch it caused, and the id is the backend's deterministic one")
                       (is (= "great read" (-> e :data :comments second :body))
                           "the body is the one the form submitted")
                       (is (= "demo" (-> e :data :comments second :author :username))
                           "the backend stamped the world's one user as the author")
                       (is (= (backend-comments slug) (:data e))
                           "the entry is at the backend's CURRENT truth — the refetch consulted the state the write landed in, not a seed")
                       (is (nil? (:invalidated-at e))
                           "the refetch cleared the invalidation it answered")
                       (is (= 2 (count (get-in @app-http/demo-state [:comments slug])))
                           "the write landed in this app's own world"))))
            ;; Report and release; `done` runs once, in the one trailing step.
            (.catch (fn [e]
                      (is false (str "production-seam receipt did not settle: " (.-message e)))
                      nil))
            (.then (fn [_] (done))))))))
