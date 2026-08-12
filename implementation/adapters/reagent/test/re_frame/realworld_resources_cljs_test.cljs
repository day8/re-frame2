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
        session-scoped cache via `:rf.resource/clear-scope`, and releases the
        principal-switch owner (the mandatory release path);
     6. THE AUTH MACHINE — login drives :idle → :submitting → :authed via managed
        HTTP and stores the session.

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
   url-push is stubbed so navigation is deterministic without a browser.

   Per rf2-am9d this ns uses snapshot/restore via re-frame.test-support so the
   contract is uniform across CLJS fixtures."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
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
            [re-frame.resources.route :as resources-route]
            [re-frame.resources.state :as state]
            [re-frame.resources.test-support]
            [re-frame.routing :as routing]
            ;; the shared node window/history/location stub (rf2-y6e2zb) — the
            ;; existing browser seam the ui-arm url-strategy pins below drive
            ;; the ingress `:decode` leg through (rf2-nn5s8 audit rider).
            [re-frame.routing-browser-test-support
             :refer [with-window-stub-fixture]]
            ;; the framework trace-ring buffer (Spec 009) — cleared around each
            ;; test body so this dispatching suite leaves no trace residue for a
            ;; later cross-cutting tooling test (e.g. the Xray/Story panel e2e
            ;; seeds, which read the rings). See `clear-trace-rings-fixture`.
            [re-frame.trace.tooling :as trace-tooling]
            ;; the example's production source — chains in every feature ns.
            [realworld-resources.core :as core]
            [realworld-resources.scope :as scope]
            ;; the example's shared route table + the two ENTRY-SPECIFIC url
            ;; strategies (already loaded via core; aliased for the ui-arm
            ;; url-strategy pins — rf2-nn5s8 audit rider).
            [realworld-resources.routing :as app-routing]
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
  (select-keys @registrar/kind->id->metadata
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
(swap! registrar/kind->id->metadata
       (fn [reg]
         (reduce (fn [r [kind id->meta]]
                   (update r kind (fn [m] (apply dissoc m (keys id->meta)))))
                 reg
                 resource-kind-snapshots)))


;; rf2-h1vqa4: the RealWorld twins share id vocabulary (`:settings/load`,
;; …) — sequester the SIBLING app's whole namespace tree so this suite's
;; fixture baseline never carries both provenances for one id (the
;; realworld-http suites' baselines were captured before this ns loaded,
;; and their fixtures restore those baselines per test, so they are
;; unaffected; later-loading realworld-http consumers reinstate via
;; test-support/reinstate-app-namespaces!).
(test-support/sequester-app-namespaces! "realworld-http.")

;; rf2-h1vqa4 BUNDLE CO-LOAD HYGIENE: this app registers the reserved
;; per-app `:rf.route/not-found` route at ns load. Co-loaded example apps
;; each do the same, and two provenance rows for the id fail default-image
;; assembly loud for every suite whose fixture baseline is captured after
;; the second app loads. Sequester OUR app's row at ns load; the fixture
;; init reinstates it (registrar + source store in lockstep) for this
;; suite's own tests.
(def ^:private not-found-route-row
  (test-support/sequester-app-registration!
    :route :rf.route/not-found "realworld-resources.routing"))

(defn- init!
  "Per-test setup. The example owns the URL through `:rf/default`
   (`:url-bound? true`); re-register it that way, re-install the example's
   resource / mutation / scope registrations the reset hook wiped, reset routing
   counters, re-publish the late-bound routing integration, and stub managed-HTTP
   + url-push so ensure / navigation are deterministic without a fetch / browser."
  []
  ;; rf2-h1vqa4: re-scrub the sibling app's rows per test — the merge-form
  ;; store restore preserves slots this suite's baseline does not know
  ;; about, so an earlier realworld-http suite's restored rows would
  ;; otherwise collide with ours at default-image assembly.
  (test-support/sequester-app-namespaces! "realworld-http.")
  (test-support/reinstate-app-registration! not-found-route-row)
  (reset! last-managed-args nil)
  (reset! managed-args-log [])
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "realworld-resources default app frame."})
  ;; Re-install the example's ns-load resource/mutation/scope registrations.
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
  (fx/reg-fx :rf.http/managed (fn [_ctx args]
                                (reset! last-managed-args args)
                                (swap! managed-args-log conj args)
                                nil))
  (fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil)))

(defn- isolate-trace-bus-fixture
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

   (The registrar side is handled separately: this suite's resources / mutations /
   scopes are removed from the SHARED registrar at NS LOAD — see the top-level
   `swap!` above — and captured in the per-test snapshot baseline as ABSENT, so
   they live only inside this suite's own test bodies via `init!`, never
   persisting for another suite's reset to clear.) A later e2e test re-registers
   its own collector (its helper calls `reset-sentinels!` +
   `register-trace-collector!`), so clearing here is safe."
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

(defn- runtime-db
  ([] (runtime-db :rf/default))
  ([frame-id] (:rf.db/runtime (rf/frame-state-value frame-id))))

(defn- entry
  ([scoped-key] (entry :rf/default scoped-key))
  ([frame-id scoped-key] (get-in (runtime-db frame-id) (state/entry-path scoped-key))))

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
  ([viewer slug] (state/scoped-resource-key (viewer-scope viewer) :realworld/article {:slug slug})))

(defn- feed-key
  "The session-scoped :realworld/feed key for username + page."
  [username page]
  (state/scoped-resource-key [:rf.scope/session {:username username}] :realworld/feed {:page page}))

(defn- profile-key
  ([subject] (profile-key "alice" subject))
  ([viewer subject] (state/scoped-resource-key (viewer-scope viewer) :realworld/profile {:username subject})))

(defn- comments-key
  ([slug] (comments-key "alice" slug))
  ([viewer slug] (state/scoped-resource-key (viewer-scope viewer) :realworld/comments {:slug slug})))

;; The two profile tabs' own paginated lists. Both are viewer-scoped like the
;; banner, and both take the route's `{:username :page}` params — `:page` defaults
;; to 1 on the bare tab URL (the route's `(or (:page q) 1)`), so page 1 is the key
;; a plain tab activation owns.
(defn- author-articles-key
  ([subject page] (author-articles-key "alice" subject page))
  ([viewer subject page]
   (state/scoped-resource-key (viewer-scope viewer) :realworld/author-articles
                              {:username subject :page page})))

(defn- favorited-articles-key
  ([subject page] (favorited-articles-key "alice" subject page))
  ([viewer subject page]
   (state/scoped-resource-key (viewer-scope viewer) :realworld/favorited-articles
                              {:username subject :page page})))

(defn- tags-key
  "The truly-invariant global-scope :realworld/tags key (the one read still global)."
  []
  (state/scoped-resource-key :rf.scope/global :realworld/tags {}))

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
  (frame/make-anon-frame-record! {:url-bound?   true
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
    (with-new-frame [f (frame/make-anon-frame-record! {})]
      (let [ctx {:frame f :request {:url "/articles"}}]
        (is (nil? (get-in (core/bearer-auth-interceptor ctx)
                          [:request :headers "Authorization"]))
            "no token → no Authorization header (logged-out reads unaffected)")))
    ;; AUTHED — the token in the frame's app-db is injected as a Bearer header.
    (with-new-frame [f (frame/make-anon-frame-record! {})]
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
    (with-new-frame [f (frame/make-anon-frame-record! {})]
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (let [cofx-meta (registrar/handler-meta :cofx :realworld-resources.session/token)]
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
            until now. The seed is LEAFWISE instead, the same law `FH-CTRL-013`
            states for freehand forms: a TOUCHED field keeps its own draft AND
            its own baseline, so the typing survives and stays dirty; every
            untouched field takes the loaded article's value in both, so the
            dirty-check still compares against what the server holds."
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
;; 10. SESSION-RESTORE-WITH-TOKEN — the documented "restore stays put" invariant,
;;     plus the passive-re-key feed re-ensure (rf2-svj926)
;; ============================================================================

(defn- articles-list-key
  "The :realworld/articles home-list key for a viewer scope + page."
  ([scope] (articles-list-key scope 1))
  ([scope page] (state/scoped-resource-key scope :realworld/articles {:tag nil :page page})))

(deftest session-restore-success-stays-put-and-re-ensures-route-under-the-viewer
  (testing "examples/real-apps/realworld_resources — cold boot with a saved token:
            during the token-present/user-unresolved window the viewer scope is
            fail-closed (nil), so a deep-link home route's viewer-scoped reads are
            NOT stored under any viewer/anonymous/global identity (no
            authenticated-response leak). Once GET /user settles, :restore-session
            stores the session, re-ensures the CURRENT route's reads under the
            resolved viewer via :auth/ensure-viewer-route, and does NOT navigate
            (the deep link survives) (rf2-j538f7.29)"
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op
                                                      :realworld-resources.session/persist :rf/no-op}})]
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
        ;; The URL syncs to a HOME deep link (stand-in). Its viewer-scoped reads
        ;; fail closed — no articles entry is stored under ANY identity.
        (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home}] {:frame f})
        (is (= :realworld/home (route-id f)) "cold boot lands on the home deep link")
        (is (nil? (entry f (articles-list-key (viewer-scope "alice"))))
            "no articles stored under a signed-in viewer during restore")
        (is (nil? (entry f (articles-list-key anon-viewer-scope)))
            "no articles stored under the anonymous viewer during restore")
        (is (nil? (entry f (state/scoped-resource-key :rf.scope/global :realworld/articles {:tag nil :page 1})))
            "no articles stored under :rf.scope/global during restore — the leak the fix closes")
        ;; GET /user settles → :restore-session stores alice + re-ensures the route.
        (reply-success! restore-req
                        {:user {:username "alice" :email "alice@example.com" :token "jwt-restore"}}
                        f)
        (is (= :authed (rf/compute-sub [:auth/state] (state-value f))))
        (is (= "alice" (:username (rf/compute-sub [:auth/user] (state-value f)))))
        (is (false? (rf/compute-sub [:auth/viewer-resolving?] (state-value f)))
            "the gate lifts once the viewer resolves")
        ;; restore stays put — it does NOT navigate (unlike interactive login).
        (is (= :realworld/home (route-id f))
            "restore stays put on the deep link — no post-login redirect")
        ;; the home route's reads are NOW ensured under alice's viewer + session,
        ;; WITHOUT any navigation (via :auth/ensure-viewer-route).
        (is (some? (entry f (articles-list-key (viewer-scope "alice"))))
            "the article list is re-ensured under alice's viewer scope")
        (is (some? (entry f (feed-key "alice" 1)))
            "the session feed is re-ensured under alice's session scope")))

    ;; CONTRAST — an interactive login DOES bounce home, proving navigation is
    ;; observable in this harness (so the non-navigation above is a real signal).
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op
                                                      :realworld-resources.session/persist :rf/no-op}})]
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

(deftest session-restore-failure-stays-put-and-re-ensures-under-anonymous
  (testing "examples/real-apps/realworld_resources — a restore that FAILS (the saved
            token was rejected) clears the session and STAYS PUT on the public deep
            link, then re-ensures the current route's reads under the now-confirmed
            ANONYMOUS viewer (:abandon-restore → :auth/ensure-viewer-route), without
            navigating home (rf2-j538f7.29, gate 5 failure branch)"
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op
                                                      :realworld-resources.session/persist :rf/no-op}})]
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
        (is (nil? (entry f (state/scoped-resource-key anon-viewer-scope :realworld/article {:slug "public-post"})))
            "not under the anonymous viewer yet either (viewer still unresolved)")
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
        ;; the article read is re-ensured under the ANONYMOUS viewer, not alice/global.
        (is (some? (entry f (state/scoped-resource-key anon-viewer-scope :realworld/article {:slug "public-post"})))
            "the article is re-ensured under the anonymous viewer")
        (is (nil? (entry f (article-key "alice" "public-post")))
            "never stored under a signed-in viewer")
        (is (nil? (entry f (state/scoped-resource-key :rf.scope/global :realworld/article {:slug "public-post"})))
            "never stored under :rf.scope/global")))))

(deftest optional-auth-representation-is-not-shared-across-viewers
  (testing "examples/real-apps/realworld_resources — THE CORE FIX: viewer A's
            optional-auth representation is NOT served to viewer B or an anonymous
            reader. The same article read resolves a DISTINCT cache key per viewer,
            so alice's favorited=true never surfaces in bob's or an anonymous
            reader's UI — and the favorite verb (POST vs DELETE) is therefore chosen
            from the CURRENT viewer's bytes, not the departing one's (rf2-j538f7.29,
            gates 3 + 4)"
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
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
;; UI-ARM URL STRATEGY — entry-specific deployment base (rf2-nn5s8 audit rider)
;; ============================================================================
;;
;; The re-frame.ui arm (`ui_core.cljs`, build `:examples/realworld-resources-ui`)
;; is served under its OWN mount, `/realworld-resources-ui` — a prefix-sharing
;; SIBLING of the Reagent arm's `/realworld-resources`, not a path under it.
;; PR #6648 shipped the ui entry reusing the Reagent arm's `url-strategy`, so
;; the shell booted into not-found and every generated link targeted the
;; Reagent mount (the audit's browser repro). These pins prove the repaired
;; entry-specific `url-strategy-ui` at the framework's REAL consult points —
;; ingress `:decode` against the shared node window stub feeding the shared
;; route table, and egress `route-link` href synthesis — while the examples
;; tree itself stays test-free (rf2-8cevm).

(deftest ui-arm-url-strategy-decodes-and-links-its-own-mount
  (testing "ingress: the ui strategy decodes its own served mount — the initial
            boot URL resolves home, a deep link resolves its route — and the
            Reagent strategy pins WHY the ui entry may not reuse it"
    (with-window-stub-fixture
      (fn []
        ;; INITIAL DECODE at the ui build's served mount root: the boot URL the
        ;; frame's first URL→route sync feeds is the app root — the home route's
        ;; own `"/"` pattern. (Home ownership of `"/"` is the app's `reg-route`
        ;; declaration; a global `match-url` pin on `"/"` would be test-BUNDLE
        ;; ambiguous — other co-loaded example apps also register `"/"`.)
        (.pushState js/globalThis.window.history nil "" "/realworld-resources-ui/")
        (is (= "/" ((:decode app-routing/url-strategy-ui)))
            "the ui mount root decodes to the app root — the home boot URL")
        ;; DEEP LINK under the ui mount, through to route resolution.
        (.pushState js/globalThis.window.history nil ""
                    "/realworld-resources-ui/article/how-it-works")
        (is (= "/article/how-it-works" ((:decode app-routing/url-strategy-ui)))
            "a deep link under the ui mount decodes to its app-relative path")
        (is (= {:route-id :realworld.article/show :params {:slug "how-it-works"}}
               (-> (routing/match-url ((:decode app-routing/url-strategy-ui)))
                   (select-keys [:route-id :params])))
            "…which resolves the article route with its slug")
        ;; THE AUDIT DEFECT, PINNED. `/realworld-resources` is a prefix-sharing
        ;; SIBLING of `/realworld-resources-ui`, not a segment ancestor — the
        ;; Reagent strategy fails safe (no strip), so the router receives the
        ;; raw mount URL, which matches no app route and boots the shell into
        ;; not-found. This is the Page-not-found repro that reopened rf2-nn5s8.
        (.pushState js/globalThis.window.history nil "" "/realworld-resources-ui/")
        (is (= "/realworld-resources-ui/" ((:decode app-routing/url-strategy)))
            "the Reagent strategy leaves the sibling ui URL unstripped — why the ui entry declares its own"))))
  (testing "egress: a route-link rendered on the ui entry's frame config targets
            the ui mount; the Reagent arm's links still target ITS mount"
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:url-strategy app-routing/url-strategy-ui})]
      (let [[_ attrs] (rf/with-frame f
                        (routing/route-link-render {:to :realworld.auth/login}))]
        (is (= "/realworld-resources-ui/login" (:href attrs))
            "the ui arm's generated link carries the ui mount base")))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:url-strategy app-routing/url-strategy})]
      (let [[_ attrs] (rf/with-frame f
                        (routing/route-link-render {:to :realworld.auth/login}))]
        (is (= "/realworld-resources/login" (:href attrs))
            "the Reagent arm's generated link still carries its own base — untouched")))))
