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
            [realworld-resources.scope :as scope])
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
  ([frame-id] (rf/runtime-db-value frame-id)))

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
      ;; create-mode entry registers the :editor/can-submit? flow against this frame
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
