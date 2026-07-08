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
        principal-switch lease (the mandatory release path);
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
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            [re-frame.http.managed]
            [re-frame.http.test-support]
            [re-frame.resources]
            [re-frame.resources.route :as resources-route]
            [re-frame.resources.state :as state]
            [re-frame.resources.test-support]
            [re-frame.routing :as routing]
            ;; the framework trace-ring buffer (Spec 009) — cleared around each
            ;; test body so this dispatching suite leaves no trace residue for a
            ;; later cross-cutting tooling test (e.g. the Xray/Story panel e2e
            ;; seeds, which read the rings). See `clear-trace-rings-fixture`.
            [re-frame.trace.tooling :as trace-tooling]
            ;; the example's production source — chains in every feature ns.
            [realworld-resources.core :as core]
            [realworld-resources.scope :as scope]
            ;; Pagination pure helpers (page->limit-offset / query-string),
            ;; exercised directly by the pagination-helpers test (rf2-yt7ay6).
            [realworld-resources.http :as rrh]
            [realworld-resources.resources :as rres])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

;; ============================================================================
;; FIXTURE
;; ============================================================================

(def ^:private last-managed-args (atom nil))

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

(defn- init!
  "Per-test setup. The example owns the URL through `:rf/default`
   (`:url-bound? true`); re-register it that way, re-install the example's
   resource / mutation / scope registrations the reset hook wiped, reset routing
   counters, re-publish the late-bound routing integration, and stub managed-HTTP
   + url-push so ensure / navigation are deterministic without a fetch / browser."
  []
  (reset! last-managed-args nil)
  (rf/reg-frame :rf/default {:url-bound? true
                             :doc "realworld-resources default app frame."})
  ;; Re-install the example's ns-load resource/mutation/scope registrations.
  (swap! registrar/kind->id->metadata merge resource-kind-snapshots)
  (routing/reset-counters!)
  (resources-route/install-routing-integration!)
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil)))

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

(defn- article-key
  "The global-scope :realworld/article detail key for a slug."
  [slug]
  (state/scoped-resource-key :rf.scope/global :realworld/article {:slug slug}))

(defn- feed-key
  "The session-scoped :realworld/feed key for username + page."
  [username page]
  (state/scoped-resource-key [:rf.scope/session {:username username}] :realworld/feed {:page page}))

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

(defn- owner-index-for
  "Map the entry-liveness owner-index (keyed on opaque byte key-ids) back to
   scoped-key vectors, so lease-teardown assertions can read owners in the same
   `[:lease …]` shape the app mints them. Mirrors the resources-machine-owner-
   release suite's owner-index helper."
  [frame]
  (let [rdb    (runtime-db frame)
        es     (get-in rdb (state/entries-path))
        id->sk (into {} (map (fn [[k-id e]] [k-id (:resource/key e)])) es)]
    (into {} (map (fn [[owner members]]
                    [owner (into #{} (map #(get id->sk % %)) members)]))
          (get-in rdb (state/owner-index-path)))))

(defn- gc-recheck!
  "Fire the GC re-check for a scoped key on `frame` (the timer-fired event).
   An owner-free, work-free entry is collected; a still-pinned one survives."
  [frame scoped-key]
  (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key scoped-key}]
                    {:frame frame}))

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

(deftest feed-resource-spec-scope-is-the-named-from-db-reference
  (testing "examples/real-apps/realworld_resources — the :realworld/feed resource
            declares :scope {:from-db :realworld/session} (a derived-scope
            REFERENCE the runtime resolves at every site)"
    (is (= {:from-db :realworld/session}
           (:scope (rf/resource-meta :realworld/feed)))
        "the feed resource's scope is the named resolver reference")
    ;; the public reads are the explicit auditable global claim
    (is (= :rf.scope/global (:scope (rf/resource-meta :realworld/articles))))
    (is (= :rf.scope/global (:scope (rf/resource-meta :realworld/article))))))

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
                          :owner [:lease :test/feed]}]
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
;; 5. LOGOUT TEARDOWN — clear-scope + lease release
;; ============================================================================

(deftest logout-clears-the-session-scoped-feed-and-releases-the-lease
  (testing "examples/real-apps/realworld_resources — :auth/clear-session clears the
            auth slice, drops the session-scoped cache via :rf.resource/clear-scope
            (so the next user never reads the prior user's feed), and releases the
            principal-switch lease. Public global reads are untouched."
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      ;; ensure the session feed under the principal-switch lease the app uses
      (rf/dispatch-sync [:auth/ensure-session-feed] {:frame f})
      (reply-success! @last-managed-args {:articles [{:slug "x"}] :articlesCount 1} f)
      ;; :auth/ensure-session-feed defaults `:page` to 1 (rf2-01jbr9) so it hits
      ;; the SAME canonical `{:page 1}` key the home route + feed subscription
      ;; use on the no-`?page=` URL — assert against that key, not `{:page nil}`.
      (let [fk (feed-key "alice" 1)]
        (is (some? (entry f fk)) "the session feed entry exists for alice")
        ;; also seed a PUBLIC global read so we can prove logout leaves it alone
        (rf/dispatch-sync [:rf.resource/ensure
                           {:resource :realworld/article :scope :rf.scope/global
                            :params {:slug "hello-conduit"} :owner [:lease :test/pub]}]
                          {:frame f})
        (reply-success! @last-managed-args {:article {:slug "hello-conduit" :title "Public"}} f)
        (is (some? (entry f (article-key "hello-conduit"))) "the public read exists")
        ;; LOGOUT
        (rf/dispatch-sync [:auth/clear-session] {:frame f})
        (is (nil? (get-in (rf/app-db-value f) [:auth :user])) "auth user cleared")
        (is (nil? (entry f fk))
            "the session-scoped feed was dropped (clear-scope) — no cross-user leak")
        (is (some? (entry f (article-key "hello-conduit")))
            "the public :rf.scope/global read is untouched by logout")))))

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
      ;; submit a login → :submitting (the machine issues a managed POST)
      (rf/dispatch-sync [:auth/flow [:auth/login {:email "alice@example.com" :password "x"}]]
                        {:frame f})
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
;; 8. PAGINATION — pure limit/offset helpers + the page-nav semantics (rf2-yt7ay6)
;; ============================================================================

(deftest pagination-helpers-are-pure-limit-offset-and-encoded-query
  (testing "examples/real-apps/realworld_resources — page->limit-offset clamps a
            nil/sub-1 page to page 1 (the one place page arithmetic lives), and
            query-string drops nil params, URL-encodes reserved characters, and
            never emits a bare \"?\""
    ;; page->limit-offset: 1-indexed page → {:limit :offset}, clamped at page 1.
    (is (= {:limit 10 :offset 0}  (rres/page->limit-offset nil)) "nil → page 1")
    (is (= {:limit 10 :offset 0}  (rres/page->limit-offset 0))   "page 0 clamps to page 1")
    (is (= {:limit 10 :offset 0}  (rres/page->limit-offset -3))  "a negative page clamps to page 1")
    (is (= {:limit 10 :offset 0}  (rres/page->limit-offset 1))   "page 1 → offset 0")
    (is (= {:limit 10 :offset 10} (rres/page->limit-offset 2))   "page 2 → offset one page in")
    (is (= {:limit 10 :offset 20} (rres/page->limit-offset 3))   "page 3 → offset two pages in")
    ;; query-string: parity with the http sibling — drop nils, encode reserved,
    ;; empty (or all-nil) → "".
    (is (= "" (rrh/query-string {})) "empty map yields \"\", not \"?\"")
    (is (= "" (rrh/query-string {:page nil})) "an all-nil map still yields \"\"")
    (is (= "?author=jake" (rrh/query-string {:author "jake" :tag nil}))
        "nil-valued params are dropped")
    (is (= "?tag=a%20b" (rrh/query-string {:tag "a b"}))
        "a space is URL-encoded")
    (is (= "?tag=a%26b%3Dc%23d" (rrh/query-string {:tag "a&b=c#d"}))
        "reserved query characters (& = #) are percent-encoded")))

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
      (rf/dispatch-sync [:rf.route/navigate :realworld.profile/show {:username "eve"}] {:frame f})
      (rf/dispatch-sync [:profile/go-to-page 3] {:frame f})
      (is (= :realworld.profile/show (route-id f)) "profile page-nav stays on the same tab")
      (is (= "eve" (:username (route-params f))) "the username is unchanged")
      (is (= 3 (:page (route-query f))))
      (rf/dispatch-sync [:profile/go-to-page 1] {:frame f})
      (is (nil? (:page (route-query f))) "profile page 1 drops ?page="))))

;; ============================================================================
;; 9. THE EDITOR EDIT-MODE LOAD + LEASE TEARDOWN + DELETE (rf2-hv7eid)
;; ============================================================================
;;
;; The editor's CREATE path is covered above (editor-flow-gates-…). This pins the
;; recently-changed EDIT path: the read-side `:reply-to` seed-on-load, and the
;; lease-teardown NO-LEAK property (rf2-kkqy6q). The footgun: `:editor/load-article`
;; mints an app-owned `[:lease :editor/article slug]` on the article read; every
;; exit path MUST have a matching release or `N edits → N leaked entries`. edit→save
;; and edit→leave release via the component unmount (slug still set); the two
;; slice-blanking paths — edit→NEW (`:editor/initialise`) and edit→DELETE
;; (`:editor/replied` delete branch) — must release the OUTGOING slug BEFORE they
;; blank the slice to a nil slug, else the lease orphans and the entry stays pinned
;; active forever. Asserted in the shape of the machine-owner-release suite
;; (active-owners empty + owner-index cleared + GC reclaims).

(deftest editor-edit-load-seeds-baseline-then-edit-to-new-releases-lease-and-reclaims
  (testing "examples/real-apps/realworld_resources — edit-mode entry seeds the draft
            + baseline from the article read via the ensure's :reply-to
            [:editor/article-loaded] continuation (rf2-p1yri7), pinning the
            [:lease :editor/article slug] owner; edit→New Article
            (:editor/initialise, same re-used component, no unmount) releases that
            outgoing lease so the article entry is reclaimed (edit→new leaks
            nothing — rf2-kkqy6q FAIL 1)"
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      ;; EDIT-MODE ENTRY: navigating to /editor/:slug fires the route's :on-match
      ;; [[:editor/load-article]], which ensures :realworld/article under the
      ;; releaseable lease with :reply-to [:editor/article-loaded].
      (rf/dispatch-sync [:rf.route/navigate :realworld.editor/edit {:slug "hello-conduit"}] {:frame f})
      (is (some? @last-managed-args) "edit entry lowered the article read")
      (let [lease [:lease :editor/article "hello-conduit"]]
        (is (contains? (:active-owners (entry f (article-key "hello-conduit"))) lease)
            "the article read is pinned by the editor's releaseable lease")
        ;; the read settles → the :reply-to continuation seeds the baseline
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

        ;; EDIT → NEW ARTICLE: the same editor-page component is re-used, so no
        ;; unmount fires; :editor/initialise (the /editor on-match) must release
        ;; the OUTGOING slug's lease before it blanks the slice.
        (rf/dispatch-sync [:editor/initialise] {:frame f})
        (testing "edit→new released the outgoing lease (no orphaned owner)"
          (is (not (contains? (:active-owners (entry f (article-key "hello-conduit"))) lease))
              "the outgoing edit lease is released on edit→new")
          (is (nil? (get (owner-index-for f) lease))
              "the lease is gone from the owner-index (no dangling pin)")
          (is (nil? (rf/compute-sub [:editor/slug] (state-value f)))
              "the slice is blanked to a fresh create draft (nil slug)"))
        (testing "the released, settled article entry is GC-reclaimed (leak closed)"
          (is (nil? (:current-work (entry f (article-key "hello-conduit"))))
              "the settled read pins no in-flight work")
          (gc-recheck! f (article-key "hello-conduit"))
          (is (nil? (entry f (article-key "hello-conduit")))
              "an owner-free, work-free entry is reclaimed — edit→new leaks nothing"))))))

(deftest editor-delete-clears-slice-releases-lease-and-navigates-home
  (testing "examples/real-apps/realworld_resources — :editor/delete fires the delete
            mutation under the shared save instance with :reply-to [:editor/replied];
            the delete branch clears the slice, releases the OUTGOING slug's lease
            BEFORE the navigate-home unmount would read a now-nil slug, and heads
            home (rf2-kkqy6q FAIL 2 — the matching-release invariant)"
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/navigate :realworld.editor/edit {:slug "doomed"}] {:frame f})
      (reply-success! @last-managed-args
                      {:article {:slug "doomed" :title "Doomed" :description "d"
                                 :body "b" :tagList []}}
                      f)
      (let [lease [:lease :editor/article "doomed"]]
        (is (contains? (:active-owners (entry f (article-key "doomed"))) lease)
            "the edit lease is held before delete")
        ;; DELETE → the delete mutation lowers; reply with no :article (the delete
        ;; endpoint returns no body) → the :editor/replied DELETE branch runs.
        (rf/dispatch-sync [:editor/delete] {:frame f})
        (reply-success! @last-managed-args {} f)
        (is (not (contains? (:active-owners (entry f (article-key "doomed"))) lease))
            "the delete flow releases the edit lease before clearing the slice (rf2-kkqy6q)")
        (is (nil? (get (owner-index-for f) lease))
            "the lease is gone from the owner-index after delete (no dangling owner)")
        (is (nil? (rf/compute-sub [:editor/slug] (state-value f)))
            "the editor slice is cleared to a blank create draft on delete")
        (is (= :realworld/home (route-id f))
            "a successful delete navigates home")))))

;; ============================================================================
;; 10. SESSION-RESTORE-WITH-TOKEN — the documented "restore stays put" invariant,
;;     plus the passive-re-key feed re-ensure (rf2-svj926)
;; ============================================================================

(deftest session-restore-with-token-stays-put-and-re-ensures-the-feed
  (testing "examples/real-apps/realworld_resources — a cold boot with a saved token
            drives :idle → :restoring → :authed via :restore-session, stores the
            session, re-ensures the session feed under the restored principal (the
            passive-re-key footgun fix), and does NOT navigate (a deep link must
            survive a refresh)"
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op
                                                      :realworld-resources.session/persist :rf/no-op}})]
      ;; Land on a resource-free public route (a deep link stand-in), as a refresh
      ;; would; assert restore leaves us right here.
      (rf/dispatch-sync [:rf.route/navigate :realworld.auth/register] {:frame f})
      (is (= :realworld.auth/register (route-id f)) "cold boot lands on the deep link")
      ;; Boot WITH a saved token → the :has-token? guard routes to :begin-restore
      ;; (the actual restore path; the pre-existing tests only pass token nil, which
      ;; takes the :idle no-op branch).
      (rf/dispatch-sync [:auth/initialise]
                        {:frame f :rf.cofx {:realworld-resources.session/token "jwt-restore"}})
      (is (= :restoring (rf/compute-sub [:auth/state] (state-value f)))
          "a non-blank token → :begin-restore (GET /user in flight)")
      ;; the GET /user reply settles the machine
      (reply-success! @last-managed-args
                      {:user {:username "alice" :email "alice@example.com" :token "jwt-restore"}}
                      f)
      (is (= :authed (rf/compute-sub [:auth/state] (state-value f)))
          ":auth/success in :restoring → :restore-session → :authed")
      (is (= "alice" (:username (rf/compute-sub [:auth/user] (state-value f))))
          "the restored session is stored")
      (is (= "jwt-restore" (get-in (rf/app-db-value f) [:auth :token]))
          "the token rode :auth/initialise into durable app-db")
      ;; THE PASSIVE-RE-KEY FIX: restore re-ensures the feed under the new principal
      ;; (a `{:from-db :realworld/session}` re-key alone is passive — it won't fetch).
      (is (some? (entry f (feed-key "alice" 1)))
          ":restore-session re-ensures the session feed under the restored scope")
      ;; THE INVARIANT: restore stays put — it does NOT fire :auth/post-login-redirect.
      (is (= :realworld.auth/register (route-id f))
          "restore stays put — it does NOT navigate (unlike interactive login)"))

    ;; CONTRAST — an interactive login DOES bounce home, proving navigation is
    ;; observable in this harness (so the non-navigation above is a real signal).
    (with-new-frame [f (frame/make-anon-frame-record! {:url-bound? true
                                       :fx-overrides {:rf.nav/push-url :rf/no-op
                                                      :realworld-resources.session/persist :rf/no-op}})]
      (rf/dispatch-sync [:rf.route/navigate :realworld.auth/login] {:frame f})
      (rf/dispatch-sync [:auth/initialise]
                        {:frame f :rf.cofx {:realworld-resources.session/token nil}})
      (is (= :idle (rf/compute-sub [:auth/state] (state-value f)))
          "no token → the :idle no-op branch")
      (rf/dispatch-sync [:auth/flow [:auth/login {:email "alice@example.com" :password "x"}]] {:frame f})
      (reply-success! @last-managed-args
                      {:user {:username "alice" :email "alice@example.com" :token "jwt"}}
                      f)
      (is (= :authed (rf/compute-sub [:auth/state] (state-value f))))
      (is (= :realworld/home (route-id f))
          "interactive login bounces home via :store-session → :auth/post-login-redirect"))))
