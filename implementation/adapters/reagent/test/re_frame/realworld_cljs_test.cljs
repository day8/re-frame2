(ns re-frame.realworld-cljs-test
  "Integration test: drives the realworld (Conduit) example (rf2-4v73)
   feature by feature. Each helper spins a fresh frame via `make-frame`,
   drives a feature flow with a canned :rf.http/managed stub, and asserts
   the resulting app-db / sub state. The one row that uses NO canned stub is
   the production-seam receipt at the bottom (rf2-k5lbd): managed HTTP wired
   to the app's own demo backend, replies awaited rather than injected.

   The fixture fns + the canned-stub helpers live HERE (the adapter test
   tree), not under examples/real-apps/realworld_http/ — the example source stays
   test-free per the locked test-free-examples policy (rf2-8cevm). The ns
   requires the example's production source (`realworld.core`, which
   chains in every feature ns — auth / articles / article-editor /
   comments / favorites / profile / settings / tags / routing — plus
   `realworld.ssr`), so their handlers / subs / views / machines register
   at ns-load, then exercises them directly. (rf2-cd2zo folded the former
   `realworld.test-helpers` + the nine `realworld.*-test` fixture nses in
   here and retired the example test/ dir.)

   Per rf2-am9d this ns uses snapshot/restore via re-frame.test-support
   so the contract is uniform across CLJS fixtures: the snapshot captures
   the realworld example's ns-load registrations (and the
   `:realworld.test/canned-success-empty` stub registered at this ns's
   load), and the restore on the way out leaves them intact for any
   subsequent test ns."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest testing use-fixtures is async]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; Activate the default Malli validator (rf2-t0hq): without this
            ;; require the CLJS default validator soft-passes and the durable
            ;; AuthSlice regression below could never observe a rollback. This is
            ;; the canonical app-boot opt-in for Malli app-schema validation.
            [re-frame.schemas.malli]
            [malli.core :as m]
            [re-frame.views]
            [re-frame.http.test-support]
            ;; The shared WIRE contract (User / UserResponse) + this app's
            ;; durable app-db schemas (AuthSlice), for the default-frame
            ;; validator regression (rf2-3fc89f.32).
            [realworld-shared.schema :as ws]
            [realworld-http.schema :as app-schema]
            [realworld-http.core]
            ;; Loaded for its ns-load side effects: registers the routes (with
            ;; the `:can-enter [:realworld.routing/authed?]` auth gate on the
            ;; `:requires-auth` routes), the `:realworld.routing/authed?` guard
            ;; sub, and the `:rf.route/entry-denied` redirect handler the
            ;; auth-gate tests exercise (EP-0037 R4).
            [realworld-http.routing]
            ;; Pagination pure helpers (page->offset / page-count / query-string /
            ;; paginate-path) exercised directly by the pagination-helpers test
            ;; (rf2-yt7ay6). The example source stays test-free; the assertions
            ;; live here.
            [realworld-http.http :as rh]
            ;; Article-editor pure helpers (validate-draft / parse-tag-list /
            ;; draft-from-article / article-body) exercised directly by
            ;; editor-pure-helpers-test (rf2-54eebb). Example source stays
            ;; test-free; assertions live here.
            [realworld-http.article-editor :as editor]
            ;; The `home-context` pure flattener (tags.cljs) exercised directly by
            ;; home-context-test (rf2-rq65wv).
            [realworld-http.tags :as tags]
            [realworld-http.ssr :as ssr]
            ;; The shared demo backend the app's production `:rf.http/managed`
            ;; override (`:realworld.demo/http-stub`, realworld-http.http) steps —
            ;; for the pure "server truth" read the production-seam receipt at
            ;; the bottom compares the settled slice against.
            [realworld-shared.demo-backend :as demo])
  (:require-macros [re-frame.core :refer [with-new-frame]]
                   [re-frame.test-support :refer [with-trace-recorder!]]))

;; ============================================================================
;; CANNED-STUB HELPERS
;; ============================================================================
;;
;; Per Spec 014 §Testing, the framework ships canned-stub fxs
;; (`:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure`)
;; that synthesise the canonical reply shape. The realworld fixtures use
;; per-test wrappers that delegate to these stubs while supplying the
;; test-specific `:value` (success) or `:kind` + `:tags` (failure). The
;; `:rf.http/managed-canned-*` fx ids register from
;; re-frame.http.test-support (required above), NOT re-frame.http.managed.

(defn- reg-canned-success!
  "Register an fx-id that delegates to :rf.http/managed-canned-success
   with a fixed `:value`. Use as a per-test stub via :fx-overrides."
  [fx-id value]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
        (stub frame-ctx (assoc args :value value))))))

(defn- reg-canned-success-by-url!
  "Register an fx-id that delegates to :rf.http/managed-canned-success,
   choosing `:value` per the request URL (and optionally method). `f`
   receives the URL string (1-arity) or [method url] (2-arity from a
   3-arity f); always returns the synthesised `:value` payload."
  [fx-id f]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [stub   (registrar/handler :fx :rf.http/managed-canned-success)
            req    (:request args)
            method (or (:method req) :get)
            url    (:url req)
            arity  (try
                     (.-length f)
                     (catch :default _ 1))
            value  (if (>= arity 2)
                     (f method url)
                     (f url))]
        (stub frame-ctx (assoc args :value value))))))

(defn- reg-canned-failure!
  "Register an fx-id that delegates to :rf.http/managed-canned-failure
   with a fixed `:kind` and `:tags` failure category per Spec 014."
  [fx-id kind tags]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [stub (registrar/handler :fx :rf.http/managed-canned-failure)]
        (stub frame-ctx (assoc args :kind kind :tags tags))))))

;; A generic success stub: every :rf.http/managed call resolves :success
;; with an empty map. Used by the core smoke test; richer per-test stubs
;; are registered inside the helpers that need specific payloads. This
;; top-level registration is captured by the :each fixture's snapshot
;; (the fixture snapshots the registrar AFTER this ns loads), so it
;; survives the per-test reset.
(reg-canned-success! :realworld.test/canned-success-empty {})

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    ;; EP-0002 (rf2-9o48ih): each helper spins its OWN top-level frame via
    ;; `make-frame`; opt out of the ambient `:rf/default` scope so the new
    ;; frame's `:initial-events` drain synchronously (top-level boot) rather than
    ;; being treated as a mid-cascade child-frame creation. In-body dispatches
    ;; carry explicit `{:frame f}` or run inside the `with-new-frame` scope.
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil
     ;; Map-form (rf2-k5lbd): the production-seam receipt at the bottom is an
     ;; `(async done …)` row — it awaits the demo backend's deferred replies —
     ;; and cljs.test runs one only under a map fixture. Sync rows are served
     ;; identically (re-frame.async-reset-fixture-cljs-test pins that).
     :async?        true
     ;; BUNDLE CO-LOAD HYGIENE (rf2-kuky.27): the RealWorld twins share id
     ;; vocabulary (`:settings/load`, `:auth/initialise`, …) and both register
     ;; the reserved per-app `:rf.route/not-found` route, so two provenance
     ;; rows for one id fail default-image assembly loud for any suite whose
     ;; baseline is captured after the second app loads. `:app-ns` names OUR
     ;; OWN app's whole tree — never the sibling's: the fixture keeps these
     ;; rows out of every suite's baseline (this one included) and reinstates
     ;; them, registrar + source store in lockstep, for this suite's tests.
     :app-ns        "realworld-http."}))

;; ============================================================================
;; auth — the auth state machine
;; ============================================================================

(defn- login-happy-path-test []
  (reg-canned-success! :realworld.test/login-success
                       {:user {:email    "alice@example.com"
                               :username "alice"
                               :token    "jwt-abc"
                               :bio      nil
                               :image    nil}})

  (with-new-frame [f (frame/make-anon-frame-record! {:fx-overrides {:rf.http/managed      :realworld.test/login-success
                                                    :auth.session/persist :rf/no-op}})]
    ;; EP-0017 (rf2-16ck78): `:auth/initialise` consumes the recordable
    ;; `:auth.session/token` coeffect. Live, its registered supplier reads
    ;; localStorage; a test pins an exact value through the dispatch-site
    ;; `:rf.cofx` stub, which is the seam the registration itself documents.
    ;; Node has no localStorage, so nil is also what the supplier would return.
    (rf/dispatch-sync [:auth/initialise]
                      {:frame f :rf.cofx {:auth.session/token nil}})
    (is (= :idle (rf/compute-sub [:auth/state] (rf/frame-state-value f))))

    ;; rf2-agb5jk (item 1): the machine is credential-free — login goes through
    ;; the credential-owning form-submit event, exactly the way the real app's
    ;; view dispatches it, not a direct password-bearing machine dispatch.
    (rf/dispatch-sync [:auth.login-form/initialise] {:frame f})
    (rf/dispatch-sync [:auth.login-form/edit-field :email "alice@example.com"] {:frame f})
    (rf/dispatch-sync [:auth.login-form/edit-password {:value "correct-horse"}] {:frame f})
    (rf/dispatch-sync [:auth.login-form/submit] {:frame f})
    (is (= :authed (rf/compute-sub [:auth/state] (rf/frame-state-value f))))
    (is (= "alice" (:username (rf/compute-sub [:auth/user] (rf/frame-state-value f)))))

    (rf/dispatch-sync [:auth/flow [:auth/logout]] {:frame f})
    (is (= :idle (rf/compute-sub [:auth/state] (rf/frame-state-value f))))
    (is (nil? (rf/compute-sub [:auth/user] (rf/frame-state-value f))))))

(defn- session-token-cofx-shape-test []
  ;; EP-0017 cofx contract — the :auth.session/token cofx is a RECORDABLE
  ;; GENERATOR: the saved JWT is an APP-OWNED world-read that feeds durable
  ;; state, so the app registers a value-returning supplier (reading localStorage)
  ;; rather than stamping the value at the dispatch site (cofx.md §Decision tree).
  ;; The generator runs at processing-start on the boot dispatch, its value is
  ;; recorded onto the causal token, and replay re-presents the captured value
  ;; verbatim. The contract under test: the registration is recordable, is NOT
  ;; provided (it has a generator), and carries a supplier fn (the localStorage
  ;; read). A declaring handler receives the generated value FLAT under
  ;; `:auth.session/token` via `:rf.cofx/requires`.
  (let [cofx-meta (registrar/handler-meta :cofx :auth.session/token)]
    (is (true? (:recordable? cofx-meta))
        "the cofx is recordable — its value rides the recorded token")
    (is (not (:provided? cofx-meta))
        "the cofx is NOT provided — it is generator-backed (the app supplies it)")
    (is (fn? (:handler-fn cofx-meta))
        "a recordable generator carries a value-returning supplier fn")))

(defn- login-failure-test []
  (reg-canned-failure! :realworld.test/login-failure
                       :rf.http/http-4xx
                       {:status 422
                        :body   {:errors {:body ["email or password is invalid"]}}})

  (with-new-frame [f (frame/make-anon-frame-record! {:fx-overrides {:rf.http/managed :realworld.test/login-failure}})]
    ;; EP-0017 (rf2-16ck78): pin the recordable `:auth.session/token` through the
    ;; dispatch-site `:rf.cofx` stub (nil node-side, as the supplier would give).
    (rf/dispatch-sync [:auth/initialise]
                      {:frame f :rf.cofx {:auth.session/token nil}})
    ;; rf2-agb5jk (item 1): drive login through the credential-owning form-submit
    ;; event — the machine itself is credential-free.
    (rf/dispatch-sync [:auth.login-form/initialise] {:frame f})
    (rf/dispatch-sync [:auth.login-form/edit-field :email "x@y.z"] {:frame f})
    (rf/dispatch-sync [:auth.login-form/edit-password {:value "wrong"}] {:frame f})
    (rf/dispatch-sync [:auth.login-form/submit] {:frame f})
    (is (= :error (rf/compute-sub [:auth/state] (rf/frame-state-value f))))
    (is (some? (rf/compute-sub [:auth/error] (rf/frame-state-value f))))

    (rf/dispatch-sync [:auth/flow [:auth/dismiss]] {:frame f})
    (is (= :idle (rf/compute-sub [:auth/state] (rf/frame-state-value f))))))

;; ============================================================================
;; auth — wire User vs token-free durable session-user (rf2-3fc89f.32)
;; ============================================================================
;;
;; The correctness review found the durable AuthSlice validated its :user
;; against the WIRE `ws/User`, which REQUIRES the sensitive :token — but
;; `:auth/store-session` stores `(dissoc user :token)`, so the durable user is
;; token-free. In the dev/default build where app schemas are active, the real
;; post-commit validator rejects that commit and rolls the whole login back.
;;
;; Every OTHER login/session test above runs on an anonymous frame
;; (`make-anon-frame-record!`), whose gensym'd id carries no registered app
;; schema, so the validator never runs there — those tests stayed falsely green
;; (the rf2-lo28u lesson: an acceptance test must hit the ACTUAL validated path).
;; This regression registers the REAL production `AuthSlice` var on the test
;; frame — the same var `reg-app-schemas` binds to `:rf/default` at ns-load — so
;; the genuine post-commit validator participates on the genuine
;; `:auth/store-session` commit.

(defn- vec-map-slot-props
  "The properties map of one slot in a vector-form Malli `[:map ...]`, or nil.
   Reads the wire User's per-slot `:sensitive?` flag straight off the pure-data
   schema, no Malli introspection needed."
  [map-schema slot-key]
  (some (fn [entry]
          (when (and (vector? entry) (= slot-key (first entry)) (map? (second entry)))
            (second entry)))
        (rest map-schema)))

(defn- durable-session-user-schema-test []
  ;; --- WIRE contract UNCHANGED: a token-less reply is still REJECTED and the
  ;;     token slot stays sensitive (the fix must not weaken decode) ---
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

  ;; --- DURABLE contract: the token-free session user is stored AND validates
  ;;     against the real AuthSlice under the real post-commit validator ---
  (with-new-frame [f (frame/make-anon-frame-record! {})]
    ;; Register the ACTUAL production AuthSlice on THIS frame so the post-commit
    ;; validator consults it — the wiring the anon-frame tests route around.
    (rf/reg-app-schema [:auth] {:frame f} app-schema/AuthSlice)
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:auth/store-session {:email "alice@example.com"
                                              :username "alice"
                                              :token "jwt-abc"
                                              :bio nil :image nil}]
                        {:frame f})
      ;; RED on old code: AuthSlice embedded the wire `ws/User` (requires
      ;; :token), the durable user is `(dissoc user :token)` → post-commit
      ;; validation fails :where :app-db and the login rolls back.
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
            "the JWT rides its one classified durable home at [:auth :token]")))))

;; ============================================================================
;; articles — global feed loading + failure paths
;; ============================================================================

(defn- articles-load-test []
  (reg-canned-success! :realworld.test/canned-articles
                       {:articles [{:slug "hello-world"
                                    :title "Hello, world"
                                    :description "An intro"
                                    :body "..."
                                    :tagList ["intro"]
                                    :createdAt "2026-05-01T00:00:00Z"
                                    :updatedAt "2026-05-01T00:00:00Z"
                                    :favorited false
                                    :favoritesCount 0
                                    :author {:username "alice" :bio nil :image nil
                                             :following false}}]
                        :articlesCount 1})

  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-articles}})]
    (is (= :idle (:status (rf/compute-sub [:articles/slice] (rf/frame-state-value f)))))
    (rf/dispatch-sync [:articles/load] {:frame f})
    (let [slice (rf/compute-sub [:articles/slice] (rf/frame-state-value f))]
      (is (= :loaded (:status slice)))
      (is (= 1 (count (:data slice))))
      (is (= "hello-world" (-> slice :data first :slug))))
    (rf/dispatch-sync [:articles/load] {:frame f})
    (let [slice (rf/compute-sub [:articles/slice] (rf/frame-state-value f))]
      (is (= :loaded (:status slice)))
      (is (= 2 (:attempt slice))))))

(defn- articles-load-failure-test []
  (reg-canned-failure! :realworld.test/canned-articles-failure
                       :rf.http/http-5xx
                       {:status 500
                        :body   "server error"})

  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-articles-failure}})]
    (rf/dispatch-sync [:articles/load] {:frame f})
    (is (= :error (:status (rf/compute-sub [:articles/slice] (rf/frame-state-value f)))))
    (is (some? (rf/compute-sub [:articles/error] (rf/frame-state-value f))))))

;; ============================================================================
;; article-editor — create flow and navigation guard
;; ============================================================================

(defn- editor-create-test []
  (reg-canned-success! :realworld.test/canned-editor-save
                       {:article {:slug "hello-world"
                                  :title "Hello"
                                  :description "Short"
                                  :body "Body"
                                  :tagList ["demo"]
                                  :createdAt "2026-05-01"
                                  :updatedAt "2026-05-01"
                                  :favorited false
                                  :favoritesCount 0
                                  :author {:username "alice" :bio nil :image nil :following false}}})

  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-editor-save}})]
    (rf/dispatch-sync [:editor/initialise] {:frame f})
    ;; The :mode region starts at :create; the :lifecycle region starts
    ;; at :idle.
    (is (true? (rf/compute-sub [:rf.machine/has-tag? :ui/article-editor :mode/create] (rf/frame-state-value f))))
    (is (true? (rf/compute-sub [:rf.machine/has-tag? :ui/article-editor :lifecycle/idle] (rf/frame-state-value f))))
    ;; The :editor/can-submit? FLOW (Spec 013) starts false — the draft is
    ;; blank (invalid) and unchanged.
    (is (false? (rf/compute-sub [:editor/can-submit?] (rf/frame-state-value f))))
    (rf/dispatch-sync [:editor/edit-field :title "Hello"] {:frame f})
    (rf/dispatch-sync [:editor/edit-field :description "Short"] {:frame f})
    (rf/dispatch-sync [:editor/edit-field :body "Body"] {:frame f})
    ;; Now valid AND dirty → the flow materialised true into app-db at
    ;; [:editor :can-submit?] on the edit drains' post-walk.
    (is (true? (rf/compute-sub [:editor/can-submit?] (rf/frame-state-value f))))
    (rf/dispatch-sync [:editor/submit] {:frame f})
    ;; A successful submit advances :mode → :edit and :lifecycle → :saved.
    (is (true? (rf/compute-sub [:rf.machine/has-tag? :ui/article-editor :lifecycle/saved] (rf/frame-state-value f))))
    (is (true? (rf/compute-sub [:rf.machine/has-tag? :ui/article-editor :mode/edit] (rf/frame-state-value f))))
    (is (false? (rf/compute-sub [:editor/dirty?] (rf/frame-state-value f))))))

(defn- editor-can-leave-test []
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    (rf/dispatch-sync [:editor/initialise] {:frame f})
    (is (true? (rf/compute-sub [:editor/can-leave?] (rf/frame-state-value f))))
    (rf/dispatch-sync [:editor/edit-field :title "Changed"] {:frame f})
    (is (false? (rf/compute-sub [:editor/can-leave?] (rf/frame-state-value f))))))

;; ============================================================================
;; comments — article-detail load and comment-post happy path
;; ============================================================================

(defn- comments-load-test []
  ;; URL-routed stub: the article-detail page issues two requests
  ;; (`/articles/:slug` and `/articles/:slug/comments`); pick the canned
  ;; payload from the URL.
  (reg-canned-success-by-url! :realworld.test/canned-article-and-comments
                              (fn [url]
                                (cond
                                  (str/ends-with? url "/comments")
                                  {:comments [{:id 1
                                               :createdAt "2026-05-01"
                                               :updatedAt "2026-05-01"
                                               :body "First!"
                                               :author {:username "eve" :bio nil :image nil :following false}}]}

                                  :else
                                  {:article {:slug "hello"
                                             :title "Hello"
                                             :description "Short"
                                             :body "Body"
                                             :tagList ["demo"]
                                             :createdAt "2026-05-01"
                                             :updatedAt "2026-05-01"
                                             :favorited false
                                             :favoritesCount 0
                                             :author {:username "alice" :bio nil :image nil :following false}}})))

  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-article-and-comments}})]
    (rf/dispatch-sync [:article/initialise] {:frame f})
    (rf/dispatch-sync [:comments/initialise] {:frame f})
    (rf/dispatch-sync [:comment-form/initialise] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/article/hello"] {:frame f})
    (is (= "hello" (:slug (rf/compute-sub [:article/data] (rf/frame-state-value f)))))
    (is (= 1 (count (rf/compute-sub [:comments/data] (rf/frame-state-value f)))))))

(defn- comment-submit-test []
  (reg-canned-success-by-url! :realworld.test/canned-comment-post
                              (fn [method url]
                                (cond
                                  ;; POST /articles/:slug/comments → returns the saved comment.
                                  (and (= :post method) (str/ends-with? url "/comments"))
                                  {:comment {:id 2
                                             :createdAt "2026-05-02"
                                             :updatedAt "2026-05-02"
                                             :body "Nice article."
                                             :author {:username "alice" :bio nil :image nil :following false}}}

                                  ;; GET /articles/:slug/comments → empty initial list.
                                  (and (= :get method) (str/ends-with? url "/comments"))
                                  {:comments []}

                                  :else
                                  ;; The route-driven :article/load also fires; return
                                  ;; an article so the page renders.
                                  {:article {:slug "hello"
                                             :title "Hello"
                                             :description "Short"
                                             :body "Body"
                                             :tagList []
                                             :createdAt "2026-05-01"
                                             :updatedAt "2026-05-01"
                                             :favorited false
                                             :favoritesCount 0
                                             :author {:username "alice" :bio nil :image nil :following false}}})))

  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-comment-post}})]
    (rf/dispatch-sync [:article/initialise] {:frame f})
    (rf/dispatch-sync [:comments/initialise] {:frame f})
    (rf/dispatch-sync [:comment-form/initialise] {:frame f})
    (rf/dispatch-sync [:auth/store-session {:username "alice" :email "a@b.c" :token "jwt" :bio nil :image nil}] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/article/hello"] {:frame f})
    (rf/dispatch-sync [:comment-form/edit-field :body "Nice article."] {:frame f})
    (rf/dispatch-sync [:comment-form/submit] {:frame f})
    (is (= "" (:body (rf/compute-sub [:comment-form/draft] (rf/frame-state-value f)))))
    ;; Initial GET returned [] (no existing comments); POST returned 1
    ;; saved comment → exactly 1 comment in the slice after submit.
    (is (= 1 (count (rf/compute-sub [:comments/data] (rf/frame-state-value f)))))))

(defn- comment-delete-rollback-stale-index-test []
  ;; rf2-mzqd4.2 — :comment/delete-rollback re-inserts at an index
  ;; captured at optimistic-delete time. If the comments list SHRANK
  ;; before the DELETE's failure reply lands (a :comments/loaded re-fetch
  ;; or a concurrent delete), a stale index can point past the current
  ;; vector. The rollback's `subvec` must NOT throw IndexOutOfBounds — it
  ;; clamps the index to the current length and re-inserts at the tail.
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    (rf/dispatch-sync [:comments/initialise] {:frame f})
    ;; Seed a single comment (the list is now length 1). :comments/loaded
    ;; carries the slug it was requested for (rf2-iy3d6); the initialised
    ;; slice targets nil, so a nil-slug reply is the matching identity here.
    (rf/dispatch-sync
      [:comments/loaded nil
       {:value {:comments [{:id 7 :body "survivor"
                            :author {:username "eve"}}]}}]
      {:frame f})
    (is (= 1 (count (rf/compute-sub [:comments/data] (rf/frame-state-value f)))))

    ;; A DELETE for a comment that WAS at index 3 in a since-shrunk list
    ;; fails. The captured prior carries the stale index 3 against the
    ;; current length-1 list. Before the clamp this threw on `subvec`.
    ;; The rollback carries the slug it was deleting from ahead of the
    ;; captured prior (rf2-84iek); the initialised slice targets nil, so a
    ;; nil-slug rollback is the matching identity here — same convention as
    ;; the `:comments/loaded nil` seed above.
    (rf/dispatch-sync
      [:comment/delete-rollback nil {:index 3 :comment {:id 9 :body "rolled-back"
                                                        :author {:username "mallory"}}}]
      {:frame f})

    (let [data (rf/compute-sub [:comments/data] (rf/frame-state-value f))]
      ;; No throw, and the rolled-back comment was re-inserted (clamped to
      ;; the tail) rather than lost.
      (is (= 2 (count data))
          "stale-index rollback re-inserts without throwing")
      (is (some #(= 9 (:id %)) data)
          "the rolled-back comment is restored")
      (is (some #(= 7 (:id %)) data)
          "the surviving comment is untouched"))))

;; ============================================================================
;; favorites — optimistic-update rollback
;; ============================================================================

(defn- favorite-toggle-test []
  (reg-canned-failure! :realworld.test/favorite-rollback
                       :rf.http/http-4xx
                       {:status 400
                        :body   {:errors {:body ["rollback"]}}})

  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/favorite-rollback}})]
    (rf/dispatch-sync [:articles/initialise] {:frame f})
    ;; :article/toggle-favorite is auth-gated (rf2-ygh4m): a logged-out
    ;; click navigates to login instead of issuing a tokenless request.
    ;; Authenticate first so this test exercises the optimistic-rollback
    ;; path it is here to cover.
    (rf/dispatch-sync [:auth/store-session {:username "alice" :email "a@b.c" :token "jwt" :bio nil :image nil}] {:frame f})
    (rf/dispatch-sync [:articles/loaded
                       {:kind :success
                        :value {:articles [{:slug "hello"
                                            :title "Hello"
                                            :description "Short"
                                            :body "Body"
                                            :tagList []
                                            :createdAt "2026-05-01"
                                            :updatedAt "2026-05-01"
                                            :favorited false
                                            :favoritesCount 0
                                            :author {:username "alice" :bio nil :image nil :following false}}]}}]
                      {:frame f})
    (rf/dispatch-sync [:article/toggle-favorite "hello"] {:frame f})
    ;; Optimistic flip + canned 4xx → rollback to original state.
    (is (false? (-> (rf/compute-sub [:articles/data] (rf/frame-state-value f))
                    first
                    :favorited)))
    (is (= 0 (-> (rf/compute-sub [:articles/data] (rf/frame-state-value f))
                 first
                 :favoritesCount)))))

;; ============================================================================
;; profile — profile + authored-articles load
;; ============================================================================

(defn- profile-load-test []
  (reg-canned-success-by-url! :realworld.test/canned-profile
                              (fn [url]
                                (if (str/includes? url "/profiles/")
                                  {:profile {:username "eve" :bio "Writes things" :image nil :following false}}
                                  {:articles [{:slug "one"
                                               :title "One"
                                               :description "Short"
                                               :body "Body"
                                               :tagList []
                                               :createdAt "2026-05-01"
                                               :updatedAt "2026-05-01"
                                               :favorited false
                                               :favoritesCount 0
                                               :author {:username "eve" :bio nil :image nil :following false}}]})))

  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-profile}})]
    (rf/dispatch-sync [:profile/initialise] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/profile/eve"] {:frame f})
    (is (= "eve" (:username (rf/compute-sub [:profile/data] (rf/frame-state-value f)))))
    (is (= 1 (count (rf/compute-sub [:profile.articles/data] (rf/frame-state-value f)))))))

;; ============================================================================
;; settings — the :settings/form machine (form-region variant of Pattern-Forms)
;; ============================================================================

(defn- settings-snapshot [db]
  (get-in db [:rf.db/runtime :rf.runtime/machines :snapshots :settings/form]))

(defn- settings-machine-has-tag?
  "Read the :settings/form machine's :tags union against a frame's app-db
   (browserless form of the `[:rf.machine/has-tag? …]` sub)."
  [frame tag]
  (rf/compute-sub [:rf.machine/has-tag? :settings/form tag]
                  (rf/frame-state-value frame)))

(defn- settings-test []
  ;; Happy-path lifecycle. The assertions below are the SAME questions
  ;; a slice-form reader would ask, but each answer comes from a
  ;; different surface:
  ;;
  ;;     SLICE FORM                              MACHINE FORM
  ;;     ----------                              ------------
  ;;     (:status slice) = :submitted            (:state snap)  = :correct
  ;;     (:draft slice)                          (-> snap :data :draft)
  ;;     :submitting? (a derived boolean sub)    (machine-has-tag? :settings/in-flight)
  (reg-canned-success! :realworld.test/canned-settings-save
                       {:user {:email "alice@example.com"
                               :token "jwt-2"
                               :username "alice"
                               :bio "New bio"
                               :image nil}})

  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed    :realworld.test/canned-settings-save
                                                :auth.session/persist :rf/no-op}})]
    ;; After :app/initialise → :settings/initialise → [:reset], the
    ;; machine sits at :neutral with empty :data.
    (let [snap (settings-snapshot (rf/frame-state-value f))]
      (is (= :neutral (:state snap)))
      (is (= ""       (get-in snap [:data :draft :bio])))
      (is (false?     (settings-machine-has-tag? f :settings/in-flight))))

    ;; Seed the auth slice + load the settings draft from the user.
    (rf/dispatch-sync [:auth/store-session {:email "alice@example.com"
                                            :token "jwt-1"
                                            :username "alice"
                                            :bio nil
                                            :image nil}]
                      {:frame f})
    (rf/dispatch-sync [:settings/load] {:frame f})
    (let [snap (settings-snapshot (rf/frame-state-value f))]
      (is (= :neutral (:state snap)))
      (is (= "alice"  (get-in snap [:data :draft :username]))))

    ;; Edit a field. The :touched set tracks user interaction; the
    ;; region stays at :neutral (a fresh edit doesn't trigger a
    ;; transition out of :correct / :incorrect unless we were there).
    (rf/dispatch-sync [:settings/edit-field :bio "New bio"] {:frame f})
    (let [snap (settings-snapshot (rf/frame-state-value f))]
      (is (= :neutral  (:state snap)))
      (is (= "New bio" (get-in snap [:data :draft :bio])))
      (is (contains?   (get-in snap [:data :touched]) :bio)))

    ;; Submit. The canned-success stub resolves synchronously, so we
    ;; observe the machine in :correct (not :submitting) after the
    ;; dispatch returns. The slice's `:status :submitted` and
    ;; `:submitted draft` are now the machine's `:state :correct` +
    ;; `:data :draft` (re-seeded from the server-returned user).
    (rf/dispatch-sync [:settings/submit] {:frame f})
    (let [db   (rf/frame-state-value f)
          snap (settings-snapshot db)]
      (is (= :correct (:state snap)))
      (is (= "New bio" (get-in snap [:data :draft :bio])))
      (is (nil?        (get-in snap [:data :submit-error])))
      ;; tag-shaped query — this replaces the slice's `:settings/submitting?`
      ;; derived boolean sub. After the synchronous reply, the region
      ;; is in :correct and the in-flight tag has dropped.
      (is (false? (settings-machine-has-tag? f :settings/in-flight)))
      (is (true?  (settings-machine-has-tag? f :form/success)))
      ;; the :auth slice has the new user data (the side-effect the
      ;; original test asserted). EP-0001 (rf2-vzld77): `:auth` is app-db; read
      ;; it off the `:rf.db/app` partition of the frame-state value.
      (is (= "New bio" (get-in db [:rf.db/app :auth :user :bio])))
      ;; the `:settings/submitting?` sub returns false (same name a
      ;; slice-form reader would use; only the source changed).
      (is (false? (rf/compute-sub [:settings/submitting?] db))))))

(defn- settings-failure-test []
  ;; Failure path — the machine lands in :incorrect with the projected
  ;; failure message in :data :submit-error, the in-flight tag drops,
  ;; and the form-level error surface is the same one validation
  ;; would use (per Pattern-Forms — both paths render via :errors /
  ;; :submit-error).
  (reg-canned-failure! :realworld.test/canned-settings-failure
                       :rf.http/http-5xx
                       {:status 500 :body "server error"})
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed    :realworld.test/canned-settings-failure
                                                :auth.session/persist :rf/no-op}})]
    (rf/dispatch-sync [:auth/store-session {:email "alice@example.com"
                                            :token "jwt-1"
                                            :username "alice"
                                            :bio nil
                                            :image nil}]
                      {:frame f})
    (rf/dispatch-sync [:settings/load] {:frame f})
    (rf/dispatch-sync [:settings/edit-field :bio "Doomed bio"] {:frame f})
    (rf/dispatch-sync [:settings/submit] {:frame f})
    (let [db   (rf/frame-state-value f)
          snap (settings-snapshot db)]
      (is (= :incorrect (:state snap)))
      (is (some? (get-in snap [:data :submit-error])))
      (is (some? (rf/compute-sub [:settings/submit-error] db)))
      (is (true?  (settings-machine-has-tag? f :form/invalid)))
      (is (false? (settings-machine-has-tag? f :settings/in-flight)))
      ;; the auth slice was NOT updated; the user's :bio is still nil.
      ;; EP-0001 (rf2-vzld77): `:auth` is app-db — read the `:rf.db/app` partition.
      (is (nil? (get-in db [:rf.db/app :auth :user :bio]))))))

(defn- settings-validation-test []
  ;; Validation path — direct broadcasts exercise the
  ;; :submit-invalid / :edit transitions. The bead's machine spec
  ;; includes a :neutral → :incorrect transition (on :submit-invalid)
  ;; and an :incorrect → :neutral transition (on :edit) so the
  ;; lifecycle is complete; in a production app a client-side Malli
  ;; validate inside :settings/submit would dispatch :submit-invalid
  ;; when the draft failed validation, matching Pattern-Forms'
  ;; §Standard events table.
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed       :realworld.test/canned-success-empty
                                                :auth.session/persist :rf/no-op}})]
    (rf/dispatch-sync [:auth/store-session {:email "alice@example.com"
                                            :token "jwt-1"
                                            :username "alice"
                                            :bio nil
                                            :image nil}]
                      {:frame f})
    (rf/dispatch-sync [:settings/load] {:frame f})

    ;; Broadcast :submit-invalid with a per-field error map. The
    ;; region lands in :incorrect and the error fields are auto-added
    ;; to :touched (per Pattern-Forms §Error visibility — once submit
    ;; has been attempted, every error is shown regardless of
    ;; per-field touched state).
    (rf/dispatch-sync [:settings/form
                       [:submit-invalid {:errors {:email ["Email must contain @."]}}]]
                      {:frame f})
    (let [snap (settings-snapshot (rf/frame-state-value f))]
      (is (= :incorrect (:state snap)))
      (is (= {:email ["Email must contain @."]} (get-in snap [:data :errors])))
      (is (contains? (get-in snap [:data :touched]) :email))
      (is (true? (settings-machine-has-tag? f :form/invalid))))

    ;; The first :edit on the offending field clears that field's
    ;; error entry and returns the region to :neutral.
    (rf/dispatch-sync [:settings/edit-field :email "alice@example.com"] {:frame f})
    (let [snap (settings-snapshot (rf/frame-state-value f))]
      (is (= :neutral (:state snap)))
      (is (false? (settings-machine-has-tag? f :form/invalid)))
      (is (not (contains? (get-in snap [:data :errors]) :email))))))

;; ============================================================================
;; tags — route query helpers + the :realworld/tags machine
;; ============================================================================

(defn- tags-snapshot [db]
  (get-in db [:rf.db/runtime :rf.runtime/machines :snapshots :realworld/tags]))

(defn- tags-machine-has-tag?
  "Read the :realworld/tags machine's :tags union against a frame's app-db
   (browserless form of the `[:rf.machine/has-tag? …]` sub)."
  [frame tag]
  (rf/compute-sub [:rf.machine/has-tag? :realworld/tags tag]
                  (rf/frame-state-value frame)))

(defn- tag-query-test []
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    ;; rf2-e90vfv route-shape conformance: applying a tag navigates to the
    ;; official `/tag/:tag` PATH route, so the active tag is a route PARAM (read
    ;; via `:home/selected-tag`), NOT a `?tag=` query.
    (rf/dispatch-sync [:tags/apply-filter "clojure"] {:frame f})
    (is (= :realworld/home-tag (rf/compute-sub [:rf.route/id] (rf/frame-state-value f))))
    (is (= "clojure" (rf/compute-sub [:home/selected-tag] (rf/frame-state-value f))))
    ;; The following feed uses the official `?feed=following` token (NOT `your`).
    (rf/dispatch-sync [:home/show-your-feed] {:frame f})
    (is (= "following" (:feed (rf/compute-sub [:rf.route/query] (rf/frame-state-value f)))))))

(defn- tags-machine-load-test []
  ;; The :tags lifecycle — load happy path through the machine.
  (reg-canned-success! :realworld.test/canned-tags
                       {:tags ["intro" "demo" "clojure"]})
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-tags}})]
    ;; After :app/initialise → :tags/initialise → [:reset], the machine
    ;; sits at :idle with empty :data.
    (let [snap (tags-snapshot (rf/frame-state-value f))]
      (is (= :idle (:state snap)))
      (is (= []    (get-in snap [:data :tags])))
      (is (= 0     (get-in snap [:data :attempt]))))

    ;; First fetch: the canned-success stub resolves synchronously, so
    ;; we observe the machine in :loaded (not :loading) after the
    ;; dispatch returns.
    (rf/dispatch-sync [:tags/load] {:frame f})
    (let [db   (rf/frame-state-value f)
          snap (tags-snapshot db)]
      (is (= :loaded (:state snap)))
      (is (= ["intro" "demo" "clojure"]
             (get-in snap [:data :tags])))
      (is (= 1 (get-in snap [:data :attempt])))
      ;; Cofx-shape contract — `:loaded-at` carries the
      ;; `:realworld/now` cofx value into `:set-tags`.
      (is (number? (get-in snap [:data :loaded-at])))
      ;; tag-shaped queries — these replace the slice's `:tags/loading?`
      ;; / `:tags/fetching?` derived boolean subs.
      (is (true?  (tags-machine-has-tag? f :tags/loaded)))
      (is (false? (tags-machine-has-tag? f :tags/loading)))
      (is (false? (tags-machine-has-tag? f :tags/in-flight)))
      (is (= ["intro" "demo" "clojure"]
             (rf/compute-sub [:tags/data] db))))

    ;; Second fetch with prior data present: the region picks :fetching
    ;; (not :loading) so the sidebar doesn't blank out.
    (rf/dispatch-sync [:tags/load] {:frame f})
    (let [snap (tags-snapshot (rf/frame-state-value f))]
      (is (= :loaded (:state snap)))
      (is (= 2 (get-in snap [:data :attempt]))))))

(defn- tags-machine-failure-test []
  ;; Failure path — the :tags region lands in :error with the projected
  ;; failure message in :data, and the `:tags/error` derived sub picks
  ;; it up. Prior :data (if any) is preserved across the transition.
  (reg-canned-failure! :realworld.test/canned-tags-failure
                       :rf.http/http-5xx
                       {:status 500 :body "server error"})
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-tags-failure}})]
    (rf/dispatch-sync [:tags/load] {:frame f})
    (let [db   (rf/frame-state-value f)
          snap (tags-snapshot db)]
      (is (= :error (:state snap)))
      (is (some? (get-in snap [:data :error])))
      (is (some? (rf/compute-sub [:tags/error] db)))
      (is (true?  (tags-machine-has-tag? f :tags/error)))
      (is (false? (tags-machine-has-tag? f :tags/in-flight))))))

;; ============================================================================
;; routing — route table coverage + the auth-guard interceptor
;; ============================================================================

(defn- routing-tests []
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.article/show :params {:slug "hello"}}] {:frame f})
    (is (= :realworld.article/show (rf/compute-sub [:rf.route/id] (rf/frame-state-value f))))
    (is (= "hello" (:slug (rf/compute-sub [:rf.route/params] (rf/frame-state-value f)))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/profile/eve"] {:frame f})
    (is (= :realworld.profile/show (rf/compute-sub [:rf.route/id] (rf/frame-state-value f))))

    ;; `/settings` is `:requires-auth` (a `:can-enter` guard, rf2-p69yaz); this
    ;; route-resolution check is about the route TABLE, not the auth gate, so
    ;; sign in first — otherwise the gate correctly refuses the logged-out entry
    ;; and redirects to login (that fail-closed behaviour is covered by
    ;; `auth-guard-all-access-paths-test`).
    (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame f})
    (is (= :realworld.user/settings (rf/compute-sub [:rf.route/id] (rf/frame-state-value f))))

    ;; rf2-e90vfv: the tag filter is the official `/tag/:tag` PATH route — the
    ;; tag is a route PARAM, not a `?tag=` query.
    (rf/dispatch-sync [:rf.route/handle-url-change "/tag/clojure"] {:frame f})
    (is (= :realworld/home-tag (rf/compute-sub [:rf.route/id] (rf/frame-state-value f))))
    (is (= "clojure" (:tag (rf/compute-sub [:rf.route/params] (rf/frame-state-value f)))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/garbage/path"] {:frame f})
    (is (= :rf.route/not-found (rf/compute-sub [:rf.route/id] (rf/frame-state-value f))))))

(defn- auth-guard-test []
  ;; The auth gate is the framework's `:can-enter` guard — each
  ;; `:requires-auth` route declares `:can-enter [:realworld.routing/authed?]`
  ;; and an `:rf.route/entry-denied` handler stashes the denied destination and
  ;; replace-navigates to login (routing.cljs). No frame `:interceptors` — the
  ;; one pipeline runs the guard on every door. Entry denial is TERMINAL: it
  ;; commits nothing and creates NO pending value, so the return after sign-in
  ;; is a FRESH navigate, not a resume.
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    ;; Unauthenticated: navigating to a :requires-auth route
    ;; (:realworld.user/settings) is denied by :can-enter → :rf.route/entry-denied
    ;; redirects to :realworld.auth/login.
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.user/settings}] {:frame f})
    (is (= :realworld.auth/login (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "unauthenticated nav to a :requires-auth route redirects to login")

    ;; A non-guarded route is unaffected.
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home}] {:frame f})
    (is (= :realworld/home (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "unguarded route navigates normally with the gate active")

    ;; Fresh-return stash: the denial handler records the denied :destination
    ;; — a :rf/route-destination — at [:auth :return-to]. NO pending-navigation
    ;; value is created: entry denial is terminal.
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.user/settings}] {:frame f})
    (is (= {:to :realworld.user/settings}
           (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        "the denial stashes the canonical destination for the post-login return")
    (is (nil? (get-in (rf/frame-state-value f) [:rf.db/runtime :rf.runtime/routing
                                                :pending-navigation]))
        "a terminal denial creates NO pending-navigation value")

    ;; Authenticated: the same guarded nav now proceeds (:can-enter passes).
    (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.user/settings}] {:frame f})
    (is (= :realworld.user/settings (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "authenticated nav to a :requires-auth route proceeds")

    ;; The post-login return consumes the stashed destination and clears the
    ;; slot — an ordinary FRESH navigate whose guard re-evaluates.
    (rf/dispatch-sync [:auth/post-login-redirect] {:frame f})
    (is (nil? (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        ":auth/post-login-redirect clears the :return-to slot")))

(defn- auth-guard-all-access-paths-test []
  ;; rf2-mzqd4.3 / rf2-p69yaz — the auth gate must FAIL CLOSED on EVERY
  ;; navigation entry point, not just the programmatic `:rf.route/navigate` the
  ;; navbar uses. The `:can-enter` guard runs on the ONE gate every door shares,
  ;; so a logged-out user reaching a `:requires-auth` route via the MOST common
  ;; access path — a direct URL / reload (`:rf.route/handle-url-change`) or an
  ;; anchor click (`:rf.route/url-requested`) — is refused too. These cases assert all
  ;; three doors redirect to login (no frame interceptor needed).

  ;; --- direct-URL / reload / popstate (`:rf.route/handle-url-change`) ---
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    ;; Logged-out direct URL (or reload) to a :requires-auth route.
    (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame f})
    (is (= :realworld.auth/login (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "logged-out direct-URL/reload to a :requires-auth route redirects to login")
    (is (= {:to :realworld.user/settings}
           (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        "the direct-URL denial stashes the canonical destination")

    ;; Editor route via direct URL with path params is also gated, and
    ;; the stash carries the full address (resolved off the requested URL).
    (rf/dispatch-sync [:rf.route/handle-url-change "/editor/my-slug"] {:frame f})
    (is (= :realworld.auth/login (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "logged-out direct-URL to a :requires-auth editor route redirects to login")
    (is (= {:to :realworld.editor/edit :params {:slug "my-slug"}}
           (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        "the editor direct-URL denial stashes the destination, path params included")

    ;; A non-auth route via direct URL is unaffected.
    (rf/dispatch-sync [:rf.route/handle-url-change "/profile/eve"] {:frame f})
    (is (= :realworld.profile/show (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "logged-out direct-URL to a non-auth route is unaffected"))

  ;; --- anchor click (`:rf.route/url-requested`) ---
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    ;; An anchor whose href targets a :requires-auth route. The
    ;; framework `rf/route-link` dispatches `:rf.route/url-requested` with the
    ;; resolved url; the :can-enter gate refuses the logged-out entry.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/settings"
                                          :to  :realworld.user/settings}]
                      {:frame f})
    (is (= :realworld.auth/login (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "logged-out anchor click to a :requires-auth route redirects to login")
    (is (= {:to :realworld.user/settings}
           (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        "the anchor denial stashes the canonical destination")

    ;; A url-only request still gates via the URL-resolved target.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/editor"}] {:frame f})
    (is (= :realworld.auth/login (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "logged-out url-only anchor to a :requires-auth route redirects to login")

    ;; A non-auth anchor is unaffected.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/profile/eve"
                                          :to  :realworld.profile/show
                                          :params {:username "eve"}}]
                      {:frame f})
    (is (= :realworld.profile/show (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "logged-out anchor to a non-auth route is unaffected"))

  ;; --- authenticated: every entry point now PASSES through ---
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
    ;; direct-URL / reload to a guarded route proceeds when logged in.
    (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame f})
    (is (= :realworld.user/settings (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "authenticated direct-URL to a :requires-auth route proceeds")
    ;; anchor click to a guarded route also proceeds when logged in.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/settings"
                                          :to  :realworld.user/settings}]
                      {:frame f})
    (is (= :realworld.user/settings (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "authenticated anchor click to a :requires-auth route proceeds")))

(defn- auth-guard-return-to-full-address-test []
  ;; rf2-k5zty — the return-to stash is the FULL resolved destination, so the
  ;; post-login return lands on the EXACT URL the visitor was headed for, not a
  ;; bare route. EP-0037 R4 hands the handler that destination directly on the
  ;; `:rf.route/entry-denied` payload (no `match-url` re-derivation), so the
  ;; canonical named address carries path params, query, and #fragment. Drives
  ;; the real example handlers (routing.cljs `:rf.route/entry-denied` +
  ;; auth.cljs `:auth/post-login-redirect`).

  ;; --- 1. destination deep-link carrying BOTH query and fragment ---
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    ;; Logged-out deep-link to the guarded editor with a query AND a fragment.
    ;; (`:tab` is an undeclared query key — the guarded routes declare none — so
    ;; it rides as a string key; the point is it SURVIVES rather than being
    ;; stranded, exactly as the #fragment does.)
    (rf/dispatch-sync [:rf.route/handle-url-change "/editor/my-slug?tab=preview#comments"] {:frame f})
    (is (= :realworld.auth/login (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "deep-link to a guarded route with ?query#fragment is refused → login")
    (is (= {:to       :realworld.editor/edit
            :params   {:slug "my-slug"}
            :query    {"tab" "preview"}
            :fragment "comments"}
           (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        "the stash carries the FULL destination — query and #fragment included, not stranded")

    ;; Sign in and bounce back — the return lands on the EXACT address.
    (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
    (rf/dispatch-sync [:auth/post-login-redirect] {:frame f})
    (is (= :realworld.editor/edit (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "bounce-back landed on the editor route")
    (is (= {:slug "my-slug"} (rf/compute-sub [:rf.route/params] (rf/frame-state-value f)))
        "bounce-back restored the path params")
    (is (= {"tab" "preview"} (rf/compute-sub [:rf.route/query] (rf/frame-state-value f)))
        "bounce-back restored the query — NOT stranded")
    (is (= "comments" (rf/compute-sub [:rf.route/fragment] (rf/frame-state-value f)))
        "bounce-back restored the #fragment — NOT stranded")
    (is (nil? (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        "the crumb was read AND cleared in one step"))

  ;; --- 2. in-place edit under an expired session ---
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    ;; Enter the editor legitimately, then let the session expire.
    (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/editor/my-slug"] {:frame f})
    (is (= :realworld.editor/edit (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "entered the editor while signed in")
    (rf/dispatch-sync [:auth/clear-session] {:frame f})
    ;; An in-place navigation (change only the #fragment) is re-gated by
    ;; :can-enter — the classic in-place fail-open door, closed — and the stash
    ;; carries the resolved in-place address.
    (rf/dispatch-sync [:rf.route/navigate {:fragment "comments"}] {:frame f})
    (is (= :realworld.auth/login (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "the in-place edit under an expired session is refused → login")
    (is (= {:to       :realworld.editor/edit
            :params   {:slug "my-slug"}
            :fragment "comments"}
           (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        "the in-place edit's resolved destination (current route + new #fragment) is stashed whole"))

  ;; --- 3. an unmatchable raw URL stays a RAW destination ---
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    ;; The runtime never rewrites a destination it cannot reify without
    ;; changing the requested URL: an unmatched in-app URL stays `{:url …}`
    ;; (still a valid `:rf.route/navigate` request), so a hand-dispatched
    ;; denial for one round-trips rather than being coerced into a bogus
    ;; named address.
    (rf/dispatch-sync [:rf.route/entry-denied
                       {:destination   {:url "/editor/%zz?bad=%"}
                        :requested-url "/editor/%zz?bad=%"
                        :cause         :navigate
                        :guard         :realworld.routing/authed?}]
                      {:frame f})
    (is (= {:url "/editor/%zz?bad=%"}
           (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        "an unmatched in-app URL stays the RAW destination — replay preserves the URL")))

;; ============================================================================
;; ssr — `hydration-payload` selects the SSR-safe slice keys
;; ============================================================================

(defn- hydration-payload-test []
  ;; EP-0001 (rf2-vzld77): the SSR payload is built from a two-partition
  ;; frame-state value `{:rf.db/app … :rf.db/runtime …}` (the shape
  ;; `rf/frame-state-value` returns), NOT a flat single-map db. Application
  ;; slices live in the `:rf.db/app` partition; the framework-owned
  ;; subsystem trees (routing, machines) live in `:rf.db/runtime`. The
  ;; payload splits them across `:rf/app-db` + `:rf/runtime-db` (the two
  ;; slices the `:rf/hydrate` handler installs into the two partitions).
  (let [frame-state {:rf.db/app     {:auth      {:user {:username "alice"} :token "jwt"}
                                     :articles  {:status :loaded :data [] :error nil :loaded-at 1 :attempt 1}
                                     :transient {:popup true}}
                     :rf.db/runtime {:rf.runtime/routing  {:current {:route-id :realworld/home}}
                                     :rf.runtime/machines {:snapshots {:settings/form {:state :neutral}}}
                                     :rf.runtime/http     {:in-flight {}}}}
        payload (ssr/hydration-payload frame-state [:div "hello"])
        exported-auth (get-in payload [:rf/app-db :auth])]
    ;; The app-db slice carries only the whitelisted application slices —
    ;; `:auth` + `:articles` — and NOT the framework runtime trees (those
    ;; ride `:rf/runtime-db`) nor the non-exported `:transient` slice.
    (is (= #{:auth :articles}
           (set (keys (:rf/app-db payload)))))
    ;; The runtime-db slice carries the durable, serializable runtime
    ;; children — the route slice + machine snapshots — so the client
    ;; resumes from the server's route and any mid-flow machines. Transient
    ;; runtime state (in-flight HTTP) is excluded.
    (is (= #{:rf.runtime/routing :rf.runtime/machines}
           (set (keys (:rf/runtime-db payload)))))
    (is (= {:route-id :realworld/home}
           (get-in payload [:rf/runtime-db :rf.runtime/routing :current]))
        "the server route slice rides the runtime-db partition")
    ;; rf2-ygh4m ITEM 7 — the bearer JWT must NOT cross the SSR seam.
    ;; The :auth slice still rides along (the client needs :user), but
    ;; :token is redacted at the payload boundary (ssr/exportable-app-db);
    ;; the client re-derives it from localStorage on hydrate.
    (is (= {:username "alice"} (:user exported-auth))
        "the :auth :user payload survives hydration")
    (is (not (contains? exported-auth :token))
        "the JWT must be redacted from the SSR hydration payload")))

;; ============================================================================
;; pagination — pure limit/offset helpers + the page-nav semantics (rf2-yt7ay6)
;; ============================================================================
;;
;; Pagination is flagship Conduit behaviour and was entirely untested. Two
;; halves: (1) the pure request-building maths (`page->offset` / `page-count` /
;; `query-string` / `paginate-path` in http.cljs) — clamps and URL-encoding
;; edges that a hand-typed `?page=0` or a tag with a reserved query character
;; would otherwise get wrong; and (2) the page-nav events (`:home/show-page` /
;; `:profile/show-page`) which reset-to-page-1 on a fresh filter but carry the
;; active feed / tag / route forward when only the page changes.

(defn- paginate-path-integration-test []
  ;; The PURE page arithmetic + query encoding now live in the shared Conduit
  ;; contract and are pinned there (realworld_shared_contract_cljs_test —
  ;; rf2-fhxwhj). This app-local assertion proves THIS app's `paginate-path`
  ;; request builder threads the shared contract through correctly: it prepends
  ;; the path, encodes the filter, and appends the shared limit/offset window.
  ;; Multi-key order isn't guaranteed, so assert on the (order-independent) parts.
  (let [p (rh/paginate-path "/articles" nil 1)]
    (is (str/starts-with? p "/articles?"))
    (is (str/includes? p "limit=10"))
    (is (str/includes? p "offset=0")))
  (let [p (rh/paginate-path "/articles" {:tag "clojure"} 3)]
    (is (str/includes? p "tag=clojure") "the filter rides the query")
    (is (str/includes? p "limit=10"))
    (is (str/includes? p "offset=20") "page 3 → offset 20")))

(defn- pagination-nav-events-test []
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    ;; --- :home/show-page carries the active feed forward ---
    ;; Global feed, then page 3: the home route, no ?feed=, ?page=3.
    (rf/dispatch-sync [:home/show-global-feed] {:frame f})
    (rf/dispatch-sync [:home/show-page 3] {:frame f})
    (is (= :realworld/home (rf/compute-sub [:rf.route/id] (rf/frame-state-value f))))
    (is (= 3 (:page (rf/compute-sub [:rf.route/query] (rf/frame-state-value f))))
        "global feed page-nav sets ?page= on the home route")
    (is (nil? (:feed (rf/compute-sub [:rf.route/query] (rf/frame-state-value f))))
        "no feed was active, so ?feed= stays absent")

    ;; Following feed, then page 2: the following token is carried forward.
    (rf/dispatch-sync [:home/show-your-feed] {:frame f})
    (rf/dispatch-sync [:home/show-page 2] {:frame f})
    (is (= :realworld/home (rf/compute-sub [:rf.route/id] (rf/frame-state-value f))))
    (is (= 2 (:page (rf/compute-sub [:rf.route/query] (rf/frame-state-value f)))))
    (is (= "following" (:feed (rf/compute-sub [:rf.route/query] (rf/frame-state-value f))))
        "paging the following feed carries ?feed=following forward (you keep paging the same list)")

    ;; --- :home/show-page carries the active tag forward (re-aims at /tag/:tag) ---
    (rf/dispatch-sync [:tags/apply-filter "clojure"] {:frame f})
    (rf/dispatch-sync [:home/show-page 2] {:frame f})
    (is (= :realworld/home-tag (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "paging a tag-filtered list re-aims at the /tag/:tag PATH route")
    (is (= "clojure" (:tag (rf/compute-sub [:rf.route/params] (rf/frame-state-value f))))
        "the tag param is preserved so paging stays inside the tag")
    (is (= 2 (:page (rf/compute-sub [:rf.route/query] (rf/frame-state-value f)))))

    ;; --- :profile/show-page stays on the same tab + username, swaps only ?page= ---
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/show :params {:username "eve"}}] {:frame f})
    (rf/dispatch-sync [:profile/show-page 2] {:frame f})
    (is (= :realworld.profile/show (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "profile page-nav stays on the same (authored) tab")
    (is (= "eve" (:username (rf/compute-sub [:rf.route/params] (rf/frame-state-value f))))
        "the profile username is unchanged")
    (is (= 2 (:page (rf/compute-sub [:rf.route/query] (rf/frame-state-value f)))))

    ;; The favorites tab pages independently, still on its own route.
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/favorites :params {:username "eve"}}] {:frame f})
    (rf/dispatch-sync [:profile/show-page 3] {:frame f})
    (is (= :realworld.profile/favorites (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "the favorites tab stays on the favorites route when paging")
    (is (= 3 (:page (rf/compute-sub [:rf.route/query] (rf/frame-state-value f)))))))

;; ============================================================================
;; auth — session-restore-with-token (the documented "restore stays put"
;; invariant, previously untested — token-nil tests only hit the :idle no-op)
;; (rf2-svj926)
;; ============================================================================

(defn- session-restore-with-token-test []
  ;; A URL-routed stub: GET /user (and the login POST) return a User envelope;
  ;; everything else (the deep-link article's on-match reads) returns empty.
  ;; "/users/login" and "/users" both contain the substring "/user", so this
  ;; one predicate covers the restore GET and the login POST.
  (reg-canned-success-by-url! :realworld.test/restore-user
                              (fn [url]
                                (if (str/includes? url "/user")
                                  {:user {:username "alice"
                                          :email    "alice@example.com"
                                          :token    "jwt-restore"}}
                                  {})))

  ;; --- RESTORE STAYS PUT: a cold boot with a saved token restores the session
  ;;     without navigating (a deep link must survive a refresh) ---
  (with-new-frame [f (frame/make-anon-frame-record! {:fx-overrides {:rf.http/managed       :realworld.test/restore-user
                                                    :auth.session/persist :rf/no-op}})]
    ;; Land on a deep link (a public article), as a refresh would.
    (rf/dispatch-sync [:rf.route/handle-url-change "/article/some-slug"] {:frame f})
    (is (= :realworld.article/show (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "cold boot lands on the deep-linked article")

    ;; Boot with a saved JWT: the :has-token? guard routes to :begin-restore
    ;; (NOT the token-nil :idle no-op the pre-existing tests exercised). The
    ;; canned stub resolves the GET /user synchronously, so the machine settles.
    (rf/dispatch-sync [:auth/initialise]
                      {:frame f :rf.cofx {:auth.session/token "jwt-restore"}})
    (is (= :authed (rf/compute-sub [:auth/state] (rf/frame-state-value f)))
        "guard true → :begin-restore → :restoring → (GET /user success) → :restore-session → :authed")
    (is (= "alice" (:username (rf/compute-sub [:auth/user] (rf/frame-state-value f))))
        "the restored session is stored")
    (is (= "jwt-restore" (get-in (rf/app-db-value f) [:auth :token]))
        "the token rode :auth/initialise into durable app-db")
    ;; THE INVARIANT: restore must NOT navigate — the deep link survives.
    (is (= :realworld.article/show (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "restore stays put — :restore-session does NOT fire :auth/post-login-redirect"))

  ;; --- CONTRAST: an INTERACTIVE login DOES bounce (proves navigation is
  ;;     observable here, so the restore's non-navigation above is a real
  ;;     signal, not a harness that simply never navigates) ---
  (with-new-frame [f (frame/make-anon-frame-record! {:fx-overrides {:rf.http/managed       :realworld.test/restore-user
                                                    :auth.session/persist :rf/no-op}})]
    (rf/dispatch-sync [:rf.route/handle-url-change "/article/some-slug"] {:frame f})
    (rf/dispatch-sync [:auth/initialise]
                      {:frame f :rf.cofx {:auth.session/token nil}})
    (is (= :idle (rf/compute-sub [:auth/state] (rf/frame-state-value f)))
        "no token → the :idle no-op branch (the only path the old tests hit)")
    ;; rf2-agb5jk (item 1): drive login through the credential-owning
    ;; form-submit event — the machine itself is credential-free.
    (rf/dispatch-sync [:auth.login-form/initialise] {:frame f})
    (rf/dispatch-sync [:auth.login-form/edit-field :email "alice@example.com"] {:frame f})
    (rf/dispatch-sync [:auth.login-form/edit-password {:value "x"}] {:frame f})
    (rf/dispatch-sync [:auth.login-form/submit] {:frame f})
    (is (= :authed (rf/compute-sub [:auth/state] (rf/frame-state-value f))))
    (is (= :realworld/home (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "interactive login bounces home via :auth/session-established → :auth/post-login-redirect")))

;; ============================================================================
;; auth — THE COLD-BOOT DEEP-LINK RACE (rf2-k85nd)
;; ============================================================================
;;
;; `session-restore-with-token-test` above pins "restore stays put", but it cannot
;; see this bug, and it is worth saying why so nobody deletes what follows as a
;; duplicate. That test (a) hand-dispatches `:rf.route/handle-url-change` instead of
;; letting the URL-bound frame do its own initial sync, (b) navigates to a PUBLIC
;; route first, and (c) uses a canned stub that answers `GET /user` SYNCHRONOUSLY —
;; so by the time any route is judged, the user is already restored. Three
;; conveniences, each of which independently hides the race.
;;
;; What actually happens in a browser: the frame runs `:initial-events` (the token
;; lands in app-db), and THEN its POST-CREATE hook does the first URL→slice sync.
;; Frame setup settles only SYNCHRONOUS work — it does not await the in-flight
;; `GET /user` (EP-0027 §Construction). So the very first route decision is made
;; against `[:auth :user]` = nil, and before the fix `:rf.route/entry-denied`
;; replace-navigated a genuinely signed-in reader to `/login`, permanently.
;;
;; So this exercise removes all three conveniences:
;;   - a REAL `:url-bound? true` frame whose own initial sync is the first
;;     navigation, driven by a `:url-strategy` whose `:decode` reports the deep
;;     link (the frame lifecycle, not a hand-rolled `handle-url-change`);
;;   - the deep link is PROTECTED and it is the FIRST route ever seen;
;;   - the restore reply is DEFERRED — the managed-HTTP override captures the
;;     request and answers nothing until the test chooses to.

(defn- decode-to-url-strategy
  "A `:url-strategy` whose `:decode` always reports `url`, so a real
   `:url-bound? true` frame's own initial URL→slice sync lands on the deep link
   this test picked. Node has no `window`, so `history-url-strategy` would decode
   `\"/\"` and the boot would sync to home — which is precisely the case that
   cannot fail. All five CLJS-required legs are callable (Spec 012 §URL
   strategies validates the shape at frame construction); the three browser legs
   are inert because there is no address bar to move."
  [url]
  {:encode            (fn [path] path)
   :decode            (fn [] url)
   :push!             (fn [_href] nil)
   :replace!          (fn [_href] nil)
   :install-listener! (fn [_on-change] (fn teardown [] nil))})

(defn- reg-capturing-managed!
  "Register an `:rf.http/managed` override that CAPTURES each request into `sink`
   and replies to NONE of it. That is what makes a restore genuinely deferred:
   the request is outstanding across the frame's initial URL sync, exactly as a
   real 20ms-plus round trip is."
  [fx-id sink]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [_frame-ctx args]
      (swap! sink conj args)
      nil)))

(defn- settle-managed!
  "Deliver a success reply for a captured managed request, the way the real
   transport does: the canonical reply envelope appended as the second positional
   arg of the one-element `:on-success` target."
  [frame args value]
  (rf/dispatch-sync (conj (:on-success args) {:status :ok :value value})
                    {:frame frame}))

(defn- settle-managed-failure!
  "The failure twin of `settle-managed!` — a rejected JWT (401)."
  [frame args]
  (rf/dispatch-sync (conj (:on-failure args)
                          {:status :error
                           :error  {:kind :rf.http/http-4xx :status 401}})
                    {:frame frame}))

(defn- booting-frame!
  "A frame wired the way `realworld.core/mount!` wires the real one: URL-bound,
   the app's three ordered `:initial-events`, managed HTTP pointed at a capturing
   override. The saved JWT rides the `:auth/initialise` STEP's own `:rf.cofx`
   (EP-0027 §`:initial-events` — a map step may carry ordinary dispatch opts), so
   the recordable coeffect is stubbed at the one seam its own registration
   documents, and node's absent localStorage is not in the way."
  [url token sink]
  (frame/make-anon-frame-record!
    {:url-bound?     true
     :url-strategy   (decode-to-url-strategy url)
     :initial-events [[:auth/classify-token]
                      {:event [:auth/initialise]
                       :opts  {:rf.cofx {:auth.session/token token}}}
                      [:app/initialise]]
     :fx-overrides   {:rf.http/managed      (do (reg-capturing-managed!
                                                  :realworld.test/deferred-managed sink)
                                                :realworld.test/deferred-managed)
                      :auth.session/persist :rf/no-op
                      :rf.nav/push-url      :rf/no-op
                      :rf.nav/replace-url   :rf/no-op}}))

(defn- cold-boot-deep-link-race-test []
  (let [restored-user {:username "alice" :email "alice@example.com" :token "jwt-saved"}]

    ;; --- 1. THE REGRESSION: protected deep link + saved token + deferred reply.
    ;;     Before the fix this ended on /login with the reader's session intact
    ;;     but unreachable. ---
    (let [sink (atom [])]
      (with-new-frame [f (booting-frame! "/settings" "jwt-saved" sink)]
        (let [st #(rf/frame-state-value f)]
          ;; The frame's OWN initial sync has already run by the time make-frame
          ;; returned — no hand-dispatched navigation anywhere in this arm.
          (is (= "jwt-saved" (get-in (rf/app-db-value f) [:auth :token]))
              ":initial-events seeded the saved token before the first URL sync")
          (is (= 1 (count @sink))
              "boot fired exactly one request — the restore GET /user")
          (is (nil? (rf/compute-sub [:auth/user] (st)))
              "and it is still outstanding: identity is genuinely unknown")

          (is (not= :realworld.auth/login (rf/compute-sub [:rf.route/id] (st)))
              "THE BUG: a signed-in reader's protected deep link must NOT be bounced to login")
          (is (nil? (rf/compute-sub [:rf.route/id] (st)))
              "the refusal is still TERMINAL — no route committed, no :on-match, nothing protected ran")
          (is (= {:to :realworld.user/settings}
                 (get-in (rf/app-db-value f) [:auth :return-to]))
              "the destination is stashed, waiting on restore")
          (is (true? (rf/compute-sub [:realworld.routing/deferred-entry?] (st)))
              "the shell shows 'restoring your session', not 'page not found'")

          ;; The reply lands.
          (settle-managed! f (first @sink) {:user restored-user})
          (is (= :realworld.user/settings (rf/compute-sub [:rf.route/id] (st)))
              "restore settled → the fresh attempt enters the ORIGINAL destination")
          (is (= "alice" (:username (rf/compute-sub [:auth/user] (st))))
              "the restored session is stored")
          (is (= :authed (rf/compute-sub [:auth/state] (st))))
          (is (nil? (get-in (rf/app-db-value f) [:auth :return-to]))
              "the stash was read AND cleared in one step")
          (is (false? (rf/compute-sub [:realworld.routing/deferred-entry?] (st)))
              "the deferred window is over"))))

    ;; --- 2. FAIL-CLOSED, part one: the deep link carries the exact address.
    ;;     A protected deep link with params, query and #fragment returns to all
    ;;     of it, not to a bare route. ---
    (let [sink (atom [])]
      (with-new-frame [f (booting-frame! "/editor/my-slug?tab=preview#comments" "jwt-saved" sink)]
        (let [st #(rf/frame-state-value f)]
          (is (= {:to       :realworld.editor/edit
                  :params   {:slug "my-slug"}
                  :query    {"tab" "preview"}
                  :fragment "comments"}
                 (get-in (rf/app-db-value f) [:auth :return-to]))
              "the deferred stash is the FULL destination — query and #fragment included")
          (settle-managed! f (first @sink) {:user restored-user})
          (is (= :realworld.editor/edit (rf/compute-sub [:rf.route/id] (st))))
          (is (= {:slug "my-slug"} (rf/compute-sub [:rf.route/params] (st))))
          (is (= {"tab" "preview"} (rf/compute-sub [:rf.route/query] (st)))
              "the query survived the deferral — NOT stranded")
          (is (= "comments" (rf/compute-sub [:rf.route/fragment] (st)))
              "the #fragment survived the deferral — NOT stranded"))))

    ;; --- 3. FAIL-CLOSED, part two: an EXPIRED token. The saved JWT is rejected,
    ;;     so the reader is anonymous after all and must land on login — with the
    ;;     stash kept for the post-sign-in return. ---
    (let [sink (atom [])]
      (with-new-frame [f (booting-frame! "/settings" "jwt-expired" sink)]
        (let [st #(rf/frame-state-value f)]
          (is (nil? (rf/compute-sub [:rf.route/id] (st)))
              "deferred while the restore is in flight, exactly as in arm 1")
          (settle-managed-failure! f (first @sink))
          (is (= :realworld.auth/login (rf/compute-sub [:rf.route/id] (st)))
              "a rejected JWT is fail-closed: the deferred entry resolves to LOGIN")
          (is (nil? (get-in (rf/app-db-value f) [:auth :token]))
              "the stale credential was cleared")
          (is (= {:to :realworld.user/settings}
                 (get-in (rf/app-db-value f) [:auth :return-to]))
              "the stash SURVIVES a failed restore, for :auth/post-login-redirect")
          ;; And the ordinary interactive sign-in completes the journey.
          (rf/dispatch-sync [:auth/store-session {:username "alice" :token "fresh"}] {:frame f})
          (rf/dispatch-sync [:auth/post-login-redirect] {:frame f})
          (is (= :realworld.user/settings (rf/compute-sub [:rf.route/id] (st)))
              "signing in returns the reader to the page they originally asked for"))))

    ;; --- 4. FAIL-CLOSED, part three: NO saved token. There is nothing to wait
    ;;     for, so the bounce is IMMEDIATE — the deferral must be conditional, or
    ;;     it would be a hole rather than a fix. ---
    (let [sink (atom [])]
      (with-new-frame [f (booting-frame! "/settings" nil sink)]
        (let [st #(rf/frame-state-value f)]
          (is (empty? @sink)
              "no saved token → no restore request at all")
          (is (= :realworld.auth/login (rf/compute-sub [:rf.route/id] (st)))
              "a genuinely logged-out deep link is refused IMMEDIATELY — no deferral")
          (is (false? (rf/compute-sub [:realworld.routing/deferred-entry?] (st)))
              "and the shell renders login, not 'restoring your session'"))))

    ;; --- 5. A PUBLIC deep link is untouched by any of this: its route commits on
    ;;     the first sync, nothing is stashed, and a later restore leaves it
    ;;     exactly where it is. ---
    (let [sink (atom [])]
      (with-new-frame [f (booting-frame! "/article/some-slug" "jwt-saved" sink)]
        (let [st #(rf/frame-state-value f)]
          (is (= :realworld.article/show (rf/compute-sub [:rf.route/id] (st)))
              "a public deep link commits immediately, restore or no restore")
          (is (nil? (get-in (rf/app-db-value f) [:auth :return-to]))
              "nothing was denied, so nothing was stashed")
          (let [restore-req (first (filter #(str/includes? (str (get-in % [:request :url])) "/user")
                                           @sink))]
            (settle-managed! f restore-req {:user restored-user}))
          (is (= :realworld.article/show (rf/compute-sub [:rf.route/id] (st)))
              "restore STAYS PUT — a public deep link is never navigated away from")
          (is (= :authed (rf/compute-sub [:auth/state] (st)))))))))

;; ============================================================================
;; core — top-level smoke: boots the app, checks per-feature initialisers
;; populate the expected slices.
;; ============================================================================

(defn- app-smoke-test []
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed      :realworld.test/canned-success-empty
                                                :auth.session/persist :rf/no-op}})]
    ;; EP-0017 (rf2-16ck78): `:auth/initialise` is no longer in the
    ;; `:app/initialise` fan-out — it consumes the recordable
    ;; `:auth.session/token` coeffect, and the `:dispatch` fx does not forward
    ;; `:rf.cofx`, so it earns its own `:initial-events` step in the real app.
    ;; Dispatch it explicitly here, pinning the token through the dispatch-site
    ;; `:rf.cofx` stub the coeffect's own registration documents (node has no
    ;; localStorage for the supplier to read, so the value would be nil anyway).
    (rf/dispatch-sync [:auth/initialise]
                      {:frame f :rf.cofx {:auth.session/token nil}})
    ;; After init: the :auth + :articles slices and the
    ;; :realworld/tags + :settings/form machine snapshots are present.
    ;; EP-0001 (rf2-vzld77): app data is in app-db; machine snapshots in runtime-db.
    (let [db (rf/app-db-value f)
          rt (:rf.db/runtime (rf/frame-state-value f))]
      (is (contains? db :auth))
      (is (contains? db :articles))
      (is (contains? (get-in rt [:rf.runtime/machines :snapshots]) :realworld/tags))
      (is (contains? (get-in rt [:rf.runtime/machines :snapshots]) :settings/form)))))

;; ============================================================================
;; DEFTESTS
;; ============================================================================

(deftest realworld-auth-flow
  (testing "login happy path drives the auth machine to :authed and back"
    (login-happy-path-test))
  (testing "login failure surfaces error and dismiss returns to :idle"
    (login-failure-test))
  (testing ":auth.session/token is a recordable generator (not provided-at-dispatch)"
    (session-token-cofx-shape-test))
  (testing "durable AuthSlice user validates token-free; wire User still requires :token (rf2-3fc89f.32)"
    (durable-session-user-schema-test)))

(deftest realworld-articles-feed
  (testing "global feed loads and re-loads bumping :attempt"
    (articles-load-test))
  (testing "global feed surfaces :error on http failure"
    (articles-load-failure-test)))

(deftest realworld-article-editor
  (testing "editor create flow saves and clears :dirty?"
    (editor-create-test))
  (testing "editor :can-leave? blocks once the draft diverges"
    (editor-can-leave-test)))

(deftest realworld-comments
  (testing "article + comments load on route change"
    (comments-load-test))
  (testing "comment submit clears the form and appends to the list"
    (comment-submit-test))
  (testing "delete rollback with a stale (shrunk-list) index does not throw (rf2-mzqd4.2)"
    (comment-delete-rollback-stale-index-test)))

(deftest realworld-favorites
  (testing "favorite toggle rolls back on :http failure"
    (favorite-toggle-test)))

(deftest realworld-profile
  (testing "profile + authored-articles populate from canned stub"
    (profile-load-test)))

(deftest realworld-settings
  (testing ":settings/form machine — happy path lands in :correct (rf2-6d3x)"
    (settings-test))
  (testing ":settings/form machine — failure path lands in :incorrect (rf2-6d3x)"
    (settings-failure-test))
  (testing ":settings/form machine — :submit-invalid / :edit cycle (rf2-6d3x)"
    (settings-validation-test)))

(deftest realworld-tags
  (testing "tag filter and feed-kind round-trip via :rf.route/query"
    (tag-query-test))
  (testing ":realworld/tags machine — load happy path (rf2-0i4y)"
    (tags-machine-load-test))
  (testing ":realworld/tags machine — failure path lands in :error (rf2-0i4y)"
    (tags-machine-failure-test)))

(deftest realworld-routing
  (testing "navigate, handle-url-change, query, and not-found all resolve"
    (routing-tests))
  (testing "auth-guard redirects unauthenticated nav to :requires-auth routes (Spec 012)"
    (auth-guard-test))
  (testing "auth-guard fails CLOSED on direct-URL / anchor / reload entry points (rf2-mzqd4.3)"
    (auth-guard-all-access-paths-test))
  (testing "auth-guard return-to preserves the FULL address — query + #fragment (rf2-k5zty)"
    (auth-guard-return-to-full-address-test)))

(deftest realworld-ssr
  (testing "hydration-payload selects the SSR-safe slice keys"
    (hydration-payload-test)))

(deftest realworld-pagination
  (testing "paginate-path threads the shared page arithmetic + query encoding (rf2-yt7ay6, rf2-fhxwhj)"
    (paginate-path-integration-test))
  (testing "page-nav events carry the active feed / tag / route forward (rf2-yt7ay6)"
    (pagination-nav-events-test)))

(deftest realworld-session-restore
  (testing "restore-with-token reaches :authed, stores the session, and does NOT navigate (rf2-svj926)"
    (session-restore-with-token-test)))

(deftest realworld-cold-boot-deep-link-race
  (testing "a URL-bound cold boot at a PROTECTED deep link with a saved token and a
            DEFERRED restore reply resolves to the requested route, never to login
            (rf2-k85nd)"
    (cold-boot-deep-link-race-test)))

(deftest realworld-core-smoke
  (testing "app boot populates :auth, :articles, and :tags slices"
    (app-smoke-test)))

;; ============================================================================
;; article-editor — edit-mode load (PUT) / load-failure / delete / invalid-submit
;; + pure helpers (rf2-54eebb)
;; ============================================================================
;;
;; editor-create-test above covers the CREATE path only. Untested until now: the
;; edit-mode load (draft-from-article seed + :mode/edit + PUT-on-submit), the
;; load-failure render gate, the delete flow, the client-side invalid-submit
;; branch, and the four pure helpers the handlers lean on.

(defn- ed-has-tag? [f tag]
  (rf/compute-sub [:rf.machine/has-tag? :ui/article-editor tag]
                  (rf/frame-state-value f)))

(defn- editor-pure-helpers-test []
  ;; validate-draft — one message per blank required field; empty map when valid.
  (is (= {} (editor/validate-draft {:title "T" :description "D" :body "B"}))
      "a fully-filled draft validates clean (no error map)")
  (is (= #{:title :description :body}
         (set (keys (editor/validate-draft {:title "" :description "" :body ""}))))
      "every blank required field earns its own error")
  (is (= #{:description}
         (set (keys (editor/validate-draft {:title "T" :description "   " :body "B"}))))
      "a whitespace-only field counts as blank (str/blank?)")
  ;; parse-tag-list — split on comma, trim each, drop blanks, vector out.
  (is (= ["a" "b" "c"] (editor/parse-tag-list "a, b ,c"))
      "tags split on comma, each trimmed")
  (is (= [] (editor/parse-tag-list "")) "empty string → no tags")
  (is (= [] (editor/parse-tag-list nil)) "nil → no tags (no NPE on the split)")
  (is (= ["x"] (editor/parse-tag-list " , x , ,")) "blank entries between commas are dropped")
  ;; draft-from-article — joins the tagList vector back into the comma string the
  ;; form edits.
  (is (= {:title "T" :description "D" :body "B" :tagList "a, b"}
         (editor/draft-from-article {:title "T" :description "D" :body "B" :tagList ["a" "b"]}))
      "an article decodes into the editable draft shape (tagList joined)")
  ;; article-body — wraps the draft into the {:article …} request body, parsing tags.
  (is (= {:article {:title "T" :description "D" :body "B" :tagList ["a" "b"]}}
         (editor/article-body {:title "T" :description "D" :body "B" :tagList "a, b"}))
      "the request body parses the tag string back into a vector"))

(defn- editor-edit-load-and-put-test []
  (let [seen (atom [])]
    (reg-canned-success-by-url! :realworld.test/editor-edit
      (fn [method url]
        (swap! seen conj [method url])
        {:article {:slug "hello-world" :title "Hello, world" :description "Intro"
                   :body "Body text" :tagList ["intro" "demo"]
                   :createdAt "2026-05-01" :updatedAt "2026-05-01"
                   :favorited false :favoritesCount 0
                   :author {:username "alice" :bio nil :image nil :following false}}}))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:initial-events [[:app/initialise]]
                          :fx-overrides {:rf.http/managed :realworld.test/editor-edit}})]
      ;; /editor/:slug is :requires-auth — sign in so the :can-enter guard passes and
      ;; the route's :on-match [:editor/load-article] fires (vs a login redirect).
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/hello-world"] {:frame f})
      ;; :editor/load-article → :use-edit + :fetch-started + GET /articles/hello-world;
      ;; the canned reply lands → :editor/loaded seeds the draft, :fetch-succeeded.
      (is (true? (ed-has-tag? f :mode/edit)) "edit-mode entry flips the :mode region to :edit")
      (is (true? (ed-has-tag? f :editor/can-delete)) ":mode/edit lights the :editor/can-delete tag")
      (is (true? (ed-has-tag? f :lifecycle/idle)) "a settled load leaves the lifecycle region at :idle")
      (let [slice (rf/compute-sub [:editor/slice] (rf/frame-state-value f))
            draft (rf/compute-sub [:editor/draft] (rf/frame-state-value f))]
        (is (= "hello-world" (:slug slice)) "the slug is captured for the PUT target")
        (is (= "Hello, world" (:title draft)) "the draft is seeded from the loaded article")
        (is (= "intro, demo" (:tagList draft)) "the tag list is joined into the comma-separated string"))
      (is (false? (rf/compute-sub [:editor/dirty?] (rf/frame-state-value f)))
          "a freshly-seeded edit draft equals its baseline → not dirty")
      (is (true? (rf/compute-sub [:editor/can-leave?] (rf/frame-state-value f)))
          "a clean edit draft may leave freely")
      ;; Edit a field → dirty + valid → the flow enables submit → PUT (not POST).
      (reset! seen [])
      (rf/dispatch-sync [:editor/edit-field :title "Hello, edited"] {:frame f})
      (is (true? (rf/compute-sub [:editor/can-submit?] (rf/frame-state-value f)))
          "an edited, valid draft can submit")
      (rf/dispatch-sync [:editor/submit] {:frame f})
      (is (some (fn [[m u]] (and (= :put m) (str/ends-with? u "/articles/hello-world"))) @seen)
          "edit-mode submit issues a PUT to /articles/:slug (not a POST to /articles)")
      ;; :editor/submit-success → :submit-succeeded (:saved) + :use-edit + navigate.
      (is (true? (ed-has-tag? f :lifecycle/saved)) "a successful save advances the lifecycle → :saved")
      (is (true? (ed-has-tag? f :mode/edit)) "the editor stays in :edit mode after saving"))))

(defn- editor-load-failure-test []
  (reg-canned-failure! :realworld.test/editor-load-fail
                       :rf.http/http-5xx {:status 500 :body "server error"})
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/editor-load-fail}})]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/editor/doomed"] {:frame f})
    ;; :editor/load-article → GET fails → :editor/load-failed → :fetch-failed.
    (is (true? (ed-has-tag? f :lifecycle/error))
        "a load failure lands the lifecycle region in :error (the page-level error gate)")
    (is (some? (rf/compute-sub [:editor/submit-error] (rf/frame-state-value f)))
        "the load-failure message is surfaced via :submit-error")))

(defn- editor-delete-test []
  (let [seen (atom [])]
    (reg-canned-success-by-url! :realworld.test/editor-delete
      (fn [method url]
        (swap! seen conj [method url])
        {:article {:slug "doomed" :title "Doomed" :description "d" :body "b" :tagList []
                   :createdAt "x" :updatedAt "x" :favorited false :favoritesCount 0
                   :author {:username "alice" :bio nil :image nil :following false}}}))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:initial-events [[:app/initialise]]
                          :fx-overrides {:rf.http/managed :realworld.test/editor-delete}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/doomed"] {:frame f})
      (is (= "doomed" (:slug (rf/compute-sub [:editor/slice] (rf/frame-state-value f))))
          "the article is loaded into edit mode")
      (reset! seen [])
      (rf/dispatch-sync [:editor/delete] {:frame f})
      ;; :editor/delete → DELETE /articles/doomed → :editor/delete-success → reset + home.
      (is (some (fn [[m u]] (and (= :delete m) (str/ends-with? u "/articles/doomed"))) @seen)
          ":editor/delete issues a DELETE to /articles/:slug")
      (is (= :realworld/home (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
          "a successful delete navigates home")
      (is (nil? (:slug (rf/compute-sub [:editor/slice] (rf/frame-state-value f))))
          "the editor slice is reset to a blank create draft on delete")
      (is (true? (ed-has-tag? f :mode/create))
          "the :mode region resets to :create after a delete"))))

(defn- editor-invalid-submit-test []
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    (rf/dispatch-sync [:editor/initialise] {:frame f})
    ;; A create draft with only a title → description + body still blank → the
    ;; client-invalid branch fires (per-field errors + :_form, no round trip).
    (rf/dispatch-sync [:editor/edit-field :title "Only a title"] {:frame f})
    (rf/dispatch-sync [:editor/submit] {:frame f})
    (let [errors (rf/compute-sub [:editor/errors] (rf/frame-state-value f))]
      (is (contains? errors :description) "the blank description earns a per-field error")
      (is (contains? errors :body) "the blank body earns a per-field error")
      (is (not (contains? errors :title)) "the filled title has no error")
      (is (= "Please fix the highlighted fields." (:_form errors))
          "the whole-form prompt is set under :_form"))
    (is (some? (rf/compute-sub [:editor/field-error :description] (rf/frame-state-value f)))
        "submit-attempted? makes a per-field error visible even on an untouched field")
    (is (true? (ed-has-tag? f :lifecycle/idle))
        "an invalid submit issues no request — lifecycle stays :idle (no :submit-started)")
    (is (nil? (rf/compute-sub [:editor/submit-error] (rf/frame-state-value f)))
        "the client-validation branch clears :submit-error (that door is for transport failures)")))

(defn- editor-same-slug-seed-preserves-typing-test []
  ;; A stub that CAPTURES the request and never replies, so the GET stays in
  ;; flight for as long as this test wants it to. Every other stub here settles
  ;; on the spot, which is precisely the window this test needs to open.
  (let [in-flight (atom nil)]
    (rf/reg-fx :realworld.test/editor-in-flight
      {:platforms #{:client :server}}
      (fn [_frame-ctx args] (reset! in-flight args) nil))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:initial-events [[:app/initialise]]
                          :fx-overrides {:rf.http/managed :realworld.test/editor-in-flight}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/hello-world"] {:frame f})
      (let [req (deref in-flight)]
        (is (some? req) "entering the edit route lowered the article GET")
        (is (true? (ed-has-tag? f :lifecycle/loading))
            "the lifecycle region sits at :loading while the fetch is out")
        ;; TYPE, while the fetch is still out — the ordinary case, not a race: the
        ;; round trip is slower than the first keystroke.
        (rf/dispatch-sync [:editor/edit-field :title "My unsaved heading"] {:frame f})
        (is (= #{:title} (:touched (rf/compute-sub [:editor/slice] (rf/frame-state-value f))))
            "typing marked :title touched and nothing else")
        ;; THE SETTLE. Replay the captured `:on-success` with the transport's
        ;; success result appended, exactly as managed-HTTP delivers it. RED on the
        ;; whole-slice seed `:editor/loaded` used to do: :title becomes "Hello, world".
        (rf/dispatch-sync (conj (:on-success req)
                                {:status :ok
                                 :value {:article {:slug "hello-world" :title "Hello, world"
                                                   :description "Intro" :body "Body text"
                                                   :tagList ["intro" "demo"]}}})
                          {:frame f})
        (let [slice (rf/compute-sub [:editor/slice] (rf/frame-state-value f))
              draft (rf/compute-sub [:editor/draft] (rf/frame-state-value f))]
          (is (= "My unsaved heading" (:title draft))
              "the touched field keeps the user's text — the settle must not clobber typing")
          (is (= "" (:title (:baseline slice)))
              "the touched field keeps its own baseline too, so the typing reads as UNSAVED")
          (is (= {:title "" :description "Intro" :body "Body text" :tagList "intro, demo"}
                 (:baseline slice))
              "the baseline is seeded leafwise in step with the draft — asserted whole,
               because the bug is never the leaf you looked at")
          (is (= "Intro" (:description draft)) "an untouched field IS seeded from the loaded article")
          (is (= "intro, demo" (:tagList draft)) "…including the joined tag string")
          (is (= "hello-world" (:slug slice)) "the slice still targets the loaded slug")
          (is (true? (rf/compute-sub [:editor/dirty?] (rf/frame-state-value f)))
              "typing that survived a settle leaves the draft DIRTY — the save must send it")
          (is (= #{:title} (:touched slice)) "the seed marks nothing touched of its own"))))))

;; The CROSS-slug half of the same defect, and the reason the leafwise seed above
;; is not the whole answer. `seed-slice` protects a field the USER HAS TOUCHED —
;; but a reply for article A lands on article B's slice with every field
;; untouched relative to B's baseline, so the merge would hand A's values over
;; field by field, all of them. The two gates answer different questions:
;; correlation decides WHETHER the reply belongs to this screen, the leafwise
;; seed decides WHICH FIELDS it may write. These two tests drive the real
;; sequence — A's GET out, navigate to B, A settles late — over both reply
;; branches.

(defn- editor-cross-slug-settle-is-refused-test []
  ;; A stub that CAPTURES every lowered request and never replies, so BOTH the A
  ;; and the B GET can be held open and settled by hand, in the order a slow
  ;; network would pick.
  (let [lowered (atom [])
        article (fn [slug title]
                  {:article {:slug slug :title title
                             :description (str "About " slug)
                             :body        (str "Body of " slug)
                             :tagList     [slug]}})]
    (rf/reg-fx :realworld.test/editor-cross-slug
      {:platforms #{:client :server}}
      (fn [_frame-ctx args] (swap! lowered conj args) nil))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:initial-events [[:app/initialise]]
                          :fx-overrides {:rf.http/managed :realworld.test/editor-cross-slug}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      ;; Enter /editor/alpha — A's GET goes out and stays out.
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/alpha"] {:frame f})
      ;; …and move on to /editor/beta before A replies. A freshly-entered draft is
      ;; clean, so `:can-leave` waves this through: an ordinary navigation, not a
      ;; contrived one.
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/beta"] {:frame f})
      (is (= 2 (count @lowered))
          "each editor entry lowered its own article GET, and nothing else went out")
      (let [[a-req b-req] @lowered]
        (is (= [:editor/load-article "alpha"] (:request-id a-req))
            "the two GETs carry DISTINCT per-slug request-ids, so managed HTTP's
             same-id supersede never fires between them — A's reply is delivered
             in full and correlating it is the app's job")
        (is (= [:editor/load-article "beta"] (:request-id b-req)))
        (is (= [:editor/loaded "alpha"] (:on-success a-req))
            "so the reply target carries the slug it was requested for")
        (is (= [:editor/load-failed "alpha"] (:on-failure a-req))
            "…on the failure branch too")
        ;; B settles normally first: the editor is fully seeded from beta.
        (rf/dispatch-sync (conj (:on-success b-req)
                                {:status :ok :value (article "beta" "Beta")})
                          {:frame f})
        (is (= "Beta" (:title (rf/compute-sub [:editor/draft] (rf/frame-state-value f))))
            "B's own reply seeds B's draft — the ordinary path is untouched")
        ;; THE LATE ARRIVAL. Nothing has been typed, so every field of beta's slice
        ;; is UNTOUCHED — which is precisely why the leafwise seed is no defence
        ;; here. RED without the correlation gate: the whole draft, the baseline
        ;; and the slug all become alpha's.
        (rf/dispatch-sync (conj (:on-success a-req)
                                {:status :ok :value (article "alpha" "Alpha")})
                          {:frame f})
        (let [slice (rf/compute-sub [:editor/slice] (rf/frame-state-value f))
              draft (rf/compute-sub [:editor/draft] (rf/frame-state-value f))]
          (is (= "beta" (:slug slice))
              "a late alpha reply must not re-slug the editor — the PUT target stays beta")
          (is (= {:title "Beta" :description "About beta" :body "Body of beta" :tagList "beta"}
                 draft)
              "…nor rewrite beta's draft, asserted whole because the bug is never the
               leaf you looked at")
          (is (= {:title "Beta" :description "About beta" :body "Body of beta" :tagList "beta"}
                 (:baseline slice))
              "…nor beta's baseline, which is what dirty-detection compares against —
               named whole and INDEPENDENTLY of the draft, because the leafwise seed
               moves the two in lockstep, so a `(= draft baseline)` assertion would
               still pass with both rewritten to alpha's")
          (is (false? (rf/compute-sub [:editor/dirty?] (rf/frame-state-value f)))
              "so the form stays clean and `:can-leave` still lets the reader go")
          (is (empty? (:touched slice))
              "and the refusal marks nothing touched of its own"))))))

(defn- editor-cross-slug-failure-is-refused-test []
  ;; Same sequence, failure branch: beta's GET is still out when alpha's fails.
  (let [lowered (atom [])]
    (rf/reg-fx :realworld.test/editor-cross-slug-fail
      {:platforms #{:client :server}}
      (fn [_frame-ctx args] (swap! lowered conj args) nil))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:initial-events [[:app/initialise]]
                          :fx-overrides {:rf.http/managed :realworld.test/editor-cross-slug-fail}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/alpha"] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/beta"] {:frame f})
      (let [[a-req] @lowered]
        (rf/dispatch-sync (conj (:on-failure a-req)
                                {:status :error
                                 :error  {:kind :rf.http/http-5xx :status 500}})
                          {:frame f})
        (is (nil? (rf/compute-sub [:editor/submit-error] (rf/frame-state-value f)))
            "alpha's late failure raises no error banner over beta's editor")
        (is (true? (ed-has-tag? f :lifecycle/loading))
            "…and leaves the lifecycle region at :loading, where beta's own fetch put it")
        (is (false? (ed-has-tag? f :lifecycle/error))
            "…so the page-level error gate stays shut for a reply that was never beta's")))))

(deftest realworld-article-editor-edit-delete
  (testing "pure helpers: validate-draft / parse-tag-list / draft-from-article / article-body (rf2-54eebb)"
    (editor-pure-helpers-test))
  (testing "edit-mode load seeds the draft, flips :mode/edit, and submit issues a PUT (rf2-54eebb)"
    (editor-edit-load-and-put-test))
  (testing "a load failure lands the lifecycle in :error and surfaces the message (rf2-54eebb)"
    (editor-load-failure-test))
  (testing ":editor/delete issues a DELETE, resets the slice, and navigates home (rf2-54eebb)"
    (editor-delete-test))
  (testing "a client-invalid submit fills per-field errors and fires no request (rf2-54eebb)"
    (editor-invalid-submit-test))
  (testing "a same-slug settle seeds LEAFWISE and does not clobber typing (rf2-czvc, R-C1)"
    (editor-same-slug-seed-preserves-typing-test))
  (testing "a CROSS-slug settle is refused outright — a late A cannot rewrite B's
            draft, baseline or slug (rf2-czvc, R-C2)"
    (editor-cross-slug-settle-is-refused-test))
  (testing "a CROSS-slug FAILURE is refused too — a late A error cannot banner B
            or trip B's lifecycle into :error (rf2-czvc, R-C2)"
    (editor-cross-slug-failure-is-refused-test)))

;; ============================================================================
;; article page — cross-slug detail replies stay owned by the route (rf2-iy3d6)
;; ============================================================================
;;
;; The article page repeats the editor's navigation-staleness law over its TWO
;; route-driven reads. `/article/:slug`'s on-match dispatches :article/load
;; and :comments/load; the requests are keyed per slug ([:article/load slug] /
;; [:comments/load slug]) — DISTINCT ids across a navigation, so managed
;; HTTP's same-id supersede never fires between alpha's and beta's requests
;; and all four stay independently deliverable. Correlation is therefore the
;; app's own boundary: the requested slug rides every reply target (the
;; unified `:reply-to [:article/load slug]`; the split
;; `[:comments/loaded slug]` / `[:comments/load-failed slug]` — both reply
;; styles stay exercised), each slice records the slug it is loading, and the
;; terminal handlers refuse a settle whose slug the slice no longer targets.
;; On a slug CHANGE the request hat resets the slice (retained data is for a
;; SAME-slug refresh only), so the old article is never renderable under the
;; new URL even without an out-of-order settle.

(defn- full-article [slug title]
  {:slug slug :title title
   :description (str "About " slug)
   :body (str "Body of " slug)
   :tagList [slug]
   :createdAt "2026-05-01" :updatedAt "2026-05-01"
   :favorited false :favoritesCount 0
   :author {:username "alice" :bio nil :image nil :following false}})

(defn- full-comment [slug]
  {:id (str "c-" slug) :createdAt "2026-05-01" :updatedAt "2026-05-01"
   :body (str "First on " slug)
   :author {:username "eve" :bio nil :image nil :following false}})

(defn- req-by-id
  "The captured lowered request carrying `:request-id` id, or nil."
  [lowered id]
  (some #(when (= id (:request-id %)) %) lowered))

(defn- article-slice* [f] (rf/compute-sub [:article/slice] (rf/frame-state-value f)))
(defn- comments-slice* [f] (rf/compute-sub [:comments/slice] (rf/frame-state-value f)))
(defn- route-params* [f]
  (get-in (:rf.db/runtime (rf/frame-state-value f))
          [:rf.runtime/routing :current :params]))

(defn- settle-article-ok! [f req slug title]
  (rf/dispatch-sync (conj (:reply-to req)
                          {:status :ok :value {:article (full-article slug title)}})
                    {:frame f}))

(defn- settle-article-fail! [f req]
  (rf/dispatch-sync (conj (:reply-to req)
                          {:status :error :error {:kind :rf.http/http-5xx :status 500}})
                    {:frame f}))

(defn- settle-comments-ok! [f req slug]
  (rf/dispatch-sync (conj (:on-success req)
                          {:status :ok :value {:comments [(full-comment slug)]}})
                    {:frame f}))

(defn- settle-comments-fail! [f req]
  (rf/dispatch-sync (conj (:on-failure req)
                          {:status :error :error {:kind :rf.http/http-5xx :status 500}})
                    {:frame f}))

(defn- article-cross-slug-late-success-is-refused-test []
  ;; A capturing stub holds every article + comments request open so both
  ;; slugs' pairs can be settled by hand, in the order a slow network picks.
  (let [lowered (atom [])]
    (rf/reg-fx :realworld.test/article-cross-slug
      {:platforms #{:client :server}}
      (fn [_frame-ctx args] (swap! lowered conj args) nil))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:initial-events [[:app/initialise]]
                          :fx-overrides {:rf.http/managed :realworld.test/article-cross-slug}})]
      (rf/dispatch-sync [:article/initialise] {:frame f})
      (rf/dispatch-sync [:comments/initialise] {:frame f})
      (rf/dispatch-sync [:comment-form/initialise] {:frame f})
      ;; Enter /article/alpha — its article + comments GETs go out and stay out.
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/alpha"] {:frame f})
      ;; …and follow a link to /article/beta before either settles.
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/beta"] {:frame f})
      (is (= 4 (count @lowered))
          "both entries lowered an article AND a comments GET — four requests")
      (is (= 4 (count (distinct (map :request-id @lowered))))
          "the four carry DISTINCT per-slug request-ids, so same-id supersede
           never fires between them — every one is independently deliverable,
           and correlating each reply is the app's job")
      (let [a-art (req-by-id @lowered [:article/load "alpha"])
            a-com (req-by-id @lowered [:comments/load "alpha"])
            b-art (req-by-id @lowered [:article/load "beta"])
            b-com (req-by-id @lowered [:comments/load "beta"])]
        (is (= [:article/load "alpha"] (:reply-to a-art))
            "the unified :reply-to carries the slug it was requested for")
        (is (= [:comments/loaded "alpha"] (:on-success a-com))
            "…and so do the split comments targets, on the success branch")
        (is (= [:comments/load-failed "alpha"] (:on-failure a-com))
            "…and the failure branch")
        ;; Beta settles normally first — the ordinary path is untouched.
        (settle-article-ok! f b-art "beta" "Beta")
        (settle-comments-ok! f b-com "beta")
        (is (= "Beta" (:title (rf/compute-sub [:article/data] (rf/frame-state-value f))))
            "beta's own article reply is accepted through the public sub")
        (is (= ["First on beta"]
               (mapv :body (rf/compute-sub [:comments/data] (rf/frame-state-value f))))
            "beta's own comments reply is accepted through the public sub")
        ;; THE LATE ARRIVALS. Snapshot beta's slices WHOLE — data, status,
        ;; error, loaded-at, attempt, slug — because the bug is never the one
        ;; leaf you looked at.
        (let [art-before (article-slice* f)
              com-before (comments-slice* f)]
          (settle-article-ok! f a-art "alpha" "Alpha")
          (settle-comments-ok! f a-com "alpha")
          (is (= {:slug "beta"} (route-params* f))
              "the route still says beta")
          (is (= art-before (article-slice* f))
              "a late alpha article success changes NOTHING on the article
               slice — data, status, error, loaded-at, attempt and slug all
               stand")
          (is (= com-before (comments-slice* f))
              "…and the late alpha comments success changes nothing on the
               comments slice"))))))

(defn- article-cross-slug-late-failure-is-refused-test []
  (let [lowered (atom [])]
    (rf/reg-fx :realworld.test/article-cross-slug-fail
      {:platforms #{:client :server}}
      (fn [_frame-ctx args] (swap! lowered conj args) nil))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:initial-events [[:app/initialise]]
                          :fx-overrides {:rf.http/managed :realworld.test/article-cross-slug-fail}})]
      (rf/dispatch-sync [:article/initialise] {:frame f})
      (rf/dispatch-sync [:comments/initialise] {:frame f})
      (rf/dispatch-sync [:comment-form/initialise] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/alpha"] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/beta"] {:frame f})
      (let [a-art (req-by-id @lowered [:article/load "alpha"])
            a-com (req-by-id @lowered [:comments/load "alpha"])
            b-art (req-by-id @lowered [:article/load "beta"])
            b-com (req-by-id @lowered [:comments/load "beta"])]
        ;; Beta loads clean.
        (settle-article-ok! f b-art "beta" "Beta")
        (settle-comments-ok! f b-com "beta")
        (let [art-before (article-slice* f)
              com-before (comments-slice* f)]
          ;; Alpha's requests FAIL, late.
          (settle-article-fail! f a-art)
          (settle-comments-fail! f a-com)
          (is (= art-before (article-slice* f))
              "a late alpha article failure cannot mark beta's article slice
               errored or touch its lifecycle facts")
          (is (= com-before (comments-slice* f))
              "…nor can alpha's comments failure touch beta's comments slice")
          (is (= :loaded (:status (article-slice* f))) "beta stays :loaded")
          (is (nil? (:error (article-slice* f))) "no error banner over beta")))
      ;; NON-VACUITY for the failure gate: a failure for the CURRENT slug is
      ;; still accepted. Enter /article/gamma, hold, and fail gamma's own
      ;; requests — the gate must let its own failures through.
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/gamma"] {:frame f})
      (let [g-art (req-by-id @lowered [:article/load "gamma"])
            g-com (req-by-id @lowered [:comments/load "gamma"])]
        (settle-article-fail! f g-art)
        (settle-comments-fail! f g-com)
        (is (= :error (:status (article-slice* f)))
            "gamma's OWN article failure is accepted — the gate correlates, it
             does not swallow failures")
        (is (some? (:error (article-slice* f))) "…with its message surfaced")
        (is (= :error (:status (comments-slice* f)))
            "gamma's OWN comments failure is accepted too")))))

(defn- article-slug-change-resets-while-same-slug-refresh-retains-test []
  (let [lowered (atom [])]
    (rf/reg-fx :realworld.test/article-transition
      {:platforms #{:client :server}}
      (fn [_frame-ctx args] (swap! lowered conj args) nil))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:initial-events [[:app/initialise]]
                          :fx-overrides {:rf.http/managed :realworld.test/article-transition}})]
      (rf/dispatch-sync [:article/initialise] {:frame f})
      (rf/dispatch-sync [:comments/initialise] {:frame f})
      (rf/dispatch-sync [:comment-form/initialise] {:frame f})
      ;; Load alpha fully.
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/alpha"] {:frame f})
      (settle-article-ok! f (req-by-id @lowered [:article/load "alpha"]) "alpha" "Alpha")
      (settle-comments-ok! f (req-by-id @lowered [:comments/load "alpha"]) "alpha")
      (is (= "Alpha" (:title (rf/compute-sub [:article/data] (rf/frame-state-value f)))))
      ;; SAME-slug refresh control: re-firing the route's loads for the slug
      ;; already on screen (exactly what the route's on-match dispatches)
      ;; keeps the loaded data up while the refresh is out.
      (reset! lowered [])
      (rf/dispatch-sync [:article/load] {:frame f})
      (rf/dispatch-sync [:comments/load] {:frame f})
      (is (= :fetching (:status (article-slice* f)))
          "a same-slug re-load is a REFRESH — :fetching, not :loading")
      (is (= "Alpha" (:title (rf/compute-sub [:article/data] (rf/frame-state-value f))))
          "…and the loaded article stays renderable while it is out")
      (is (= ["First on alpha"]
             (mapv :body (rf/compute-sub [:comments/data] (rf/frame-state-value f))))
          "…as do the loaded comments")
      (settle-article-ok! f (req-by-id @lowered [:article/load "alpha"]) "alpha" "Alpha")
      (settle-comments-ok! f (req-by-id @lowered [:comments/load "alpha"]) "alpha")
      ;; Now NAVIGATE: loaded alpha → /article/beta, beta held. A slug change
      ;; is a new identity — alpha's data must not be renderable under beta's
      ;; URL even though beta hasn't settled yet.
      (reset! lowered [])
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/beta"] {:frame f})
      (is (= {:slug "beta"} (route-params* f)) "the route moved to beta")
      (let [art (article-slice* f)
            com (comments-slice* f)]
        (is (= :loading (:status art))
            "a slug CHANGE is not a refresh — :loading, fresh lifecycle")
        (is (nil? (:data art))
            "alpha's article is NOT exposed under beta's URL while beta loads")
        (is (= "beta" (:slug art)) "the article slice now targets beta")
        (is (= :loading (:status com)) "the comments slice starts over too")
        (is (empty? (:data com))
            "alpha's comments are NOT exposed under beta's URL")
        (is (= "beta" (:slug com)) "the comments slice now targets beta")))))

(deftest realworld-article-page-cross-slug
  (testing "cross-slug article/comments requests are independently deliverable;
            slugs ride both reply styles; a LATE alpha success cannot overwrite
            the active beta page (rf2-iy3d6)"
    (article-cross-slug-late-success-is-refused-test))
  (testing "a LATE alpha failure cannot mark beta errored; a CURRENT slug's own
            failure is still accepted (the failure gate's non-vacuity control)
            (rf2-iy3d6)"
    (article-cross-slug-late-failure-is-refused-test))
  (testing "a slug change resets the article/comments slices (alpha never
            renderable under beta's URL) while a same-slug re-load keeps the
            loaded data up as a refresh (rf2-iy3d6)"
    (article-slug-change-resets-while-same-slug-refresh-retains-test)))

;; ============================================================================
;; article page — optimistic comment MUTATIONS stay owned by the route
;; (rf2-84iek)
;; ============================================================================
;;
;; rf2-iy3d6 (above) correlated the two route-driven READS. The comment
;; WRITES land on the very same shared state — `[:comments :data]` and the
;; single `[:comment-form]` — and were left slug-free, so a POST or DELETE
;; issued on alpha and answered after the reader reached beta wrote into
;; beta: a success reset beta's draft, a failure bannered beta's form, and a
;; failed DELETE spliced ALPHA'S COMMENT into beta's list.
;;
;; The fix carries the issuing slug in the three settle targets and gates
;; each on the same `reply-for-current-slug?` the reads use. What makes
;; DROPPING those writes safe rather than merely quiet is the other half:
;; `:comments/load` now resets `[:comment-form]` whenever it takes on a new
;; article identity. Without that reset the form is a boot-time singleton
;; that rides across the navigation still `:status :submitting` — and since
;; the textarea and the Post button are both `:disabled` while submitting,
;; refusing alpha's settle would have left beta's form permanently locked.
;; The strand control below is what pins that pairing.

(defn- comment-form* [f] (rf/compute-sub [:comment-form/slice] (rf/frame-state-value f)))

(defn- req-by-method+url
  "The captured lowered request with this HTTP method whose URL ends with
   `url-suffix`. The comment POST / DELETE carry no `:request-id` — they are
   one-shot writes, not re-issuable reads — so they are addressed by what
   they are rather than by an id."
  [lowered method url-suffix]
  (some #(when (and (= method (get-in % [:request :method]))
                    (str/ends-with? (get-in % [:request :url]) url-suffix))
           %)
        lowered))

(defn- saved-comment [id body]
  {:id id :createdAt "2026-05-02" :updatedAt "2026-05-02" :body body
   :author {:username "alice" :bio nil :image nil :following false}})

(defn- settle-ok! [f target value]
  (rf/dispatch-sync (conj target {:status :ok :value value}) {:frame f}))

(defn- settle-fail! [f target]
  (rf/dispatch-sync (conj target {:status :error
                                  :error {:kind :rf.http/http-5xx :status 500}})
                    {:frame f}))

(defn- with-held-comment-fx
  "Run `body-fn` against a frame whose `:rf.http/managed` is a capturing stub:
   every request is recorded and NONE settles by itself, so each reply can be
   delivered by hand in the order a slow network would pick. `body-fn` gets
   the frame and the atom of lowered requests."
  [fx-id body-fn]
  (let [lowered (atom [])]
    (rf/reg-fx fx-id
      {:platforms #{:client :server}}
      (fn [_frame-ctx args] (swap! lowered conj args) nil))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:initial-events [[:app/initialise]]
                          :fx-overrides {:rf.http/managed fx-id}})]
      (rf/dispatch-sync [:article/initialise] {:frame f})
      (rf/dispatch-sync [:comments/initialise] {:frame f})
      (rf/dispatch-sync [:comment-form/initialise] {:frame f})
      (rf/dispatch-sync [:auth/store-session {:username "alice" :email "a@b.c"
                                              :token "jwt" :bio nil :image nil}]
                        {:frame f})
      (body-fn f lowered))))

(defn- comment-submit-cross-slug-late-settle-is-refused-test []
  (with-held-comment-fx :realworld.test/comment-submit-cross-slug
    (fn [f lowered]
      ;; Load alpha, then post a comment there. The POST is held open.
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/alpha"] {:frame f})
      (settle-comments-ok! f (req-by-id @lowered [:comments/load "alpha"]) "alpha")
      (rf/dispatch-sync [:comment-form/edit-field :body "Posted on alpha"] {:frame f})
      (rf/dispatch-sync [:comment-form/submit] {:frame f})
      (let [post (req-by-method+url @lowered :post "/articles/alpha/comments")]
        (is (some? post) "alpha's comment POST went out")
        ;; The target vectors carry the issuing slug AHEAD of the temp-id
        ;; (which is a fresh recordable uuid, so only the prefix is pinned).
        (is (= [:comment-form/submit-success "alpha"] (subvec (:on-success post) 0 2))
            "the POST's success target carries the slug it was posted to")
        (is (= [:comment-form/submit-error "alpha"] (subvec (:on-failure post) 0 2))
            "…and so does its failure target")
        (is (= 2 (count (rf/compute-sub [:comments/data] (rf/frame-state-value f))))
            "the optimistic temp card is on alpha's list while the POST is out")
        ;; Navigate to beta and let beta's comments settle.
        (rf/dispatch-sync [:rf.route/handle-url-change "/article/beta"] {:frame f})
        (settle-comments-ok! f (req-by-id @lowered [:comments/load "beta"]) "beta")
        ;; THE LATE ARRIVAL. Snapshot beta's comments slice and its whole form
        ;; — the bug is never the one leaf you looked at.
        (let [com-before  (comments-slice* f)
              form-before (comment-form* f)]
          (settle-ok! f (:on-success post) {:comment (saved-comment "saved-a" "Posted on alpha")})
          (is (= {:slug "beta"} (route-params* f)) "the route still says beta")
          (is (= com-before (comments-slice* f))
              "a late alpha submit SUCCESS changes nothing on beta's comments
               slice — alpha's saved comment is not spliced into beta's list")
          (is (= form-before (comment-form* f))
              "…and nothing on beta's comment form: the draft, errors and
               lifecycle the reader has on screen all stand"))))))

(defn- comment-submit-cross-slug-late-failure-is-refused-test []
  (with-held-comment-fx :realworld.test/comment-submit-cross-slug-fail
    (fn [f lowered]
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/alpha"] {:frame f})
      (settle-comments-ok! f (req-by-id @lowered [:comments/load "alpha"]) "alpha")
      (rf/dispatch-sync [:comment-form/edit-field :body "Posted on alpha"] {:frame f})
      (rf/dispatch-sync [:comment-form/submit] {:frame f})
      (let [post (req-by-method+url @lowered :post "/articles/alpha/comments")]
        (rf/dispatch-sync [:rf.route/handle-url-change "/article/beta"] {:frame f})
        (settle-comments-ok! f (req-by-id @lowered [:comments/load "beta"]) "beta")
        ;; Beta's reader is part-way through their own comment.
        (rf/dispatch-sync [:comment-form/edit-field :body "Typing on beta"] {:frame f})
        (let [com-before  (comments-slice* f)
              form-before (comment-form* f)]
          (settle-fail! f (:on-failure post))
          (is (= com-before (comments-slice* f))
              "a late alpha submit FAILURE cannot touch beta's comments slice")
          (is (= form-before (comment-form* f))
              "…nor beta's form")
          (is (nil? (rf/compute-sub [:comment-form/submit-error] (rf/frame-state-value f)))
              "alpha's error is NOT bannered over beta's comment form")
          (is (= "Typing on beta"
                 (:body (rf/compute-sub [:comment-form/draft] (rf/frame-state-value f))))
              "beta's half-typed draft survives alpha's failure"))))))

(defn- comment-delete-cross-slug-late-rollback-is-refused-test []
  (with-held-comment-fx :realworld.test/comment-delete-cross-slug
    (fn [f lowered]
      ;; Alpha loads with its one comment, and the reader deletes it. The
      ;; DELETE is held open; the card is already off the screen.
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/alpha"] {:frame f})
      (settle-comments-ok! f (req-by-id @lowered [:comments/load "alpha"]) "alpha")
      (rf/dispatch-sync [:comment/delete "c-alpha"] {:frame f})
      (let [del (req-by-method+url @lowered :delete "/articles/alpha/comments/c-alpha")]
        (is (some? del) "alpha's DELETE went out")
        (is (= :comment/delete-rollback (first (:on-failure del)))
            "the rollback target is the DELETE's failure branch")
        (is (= "alpha" (second (:on-failure del)))
            "…and it carries the slug it was deleting from, ahead of the
             captured prior")
        (is (empty? (rf/compute-sub [:comments/data] (rf/frame-state-value f)))
            "the optimistic delete took the card off alpha's list")
        ;; Navigate to beta; beta loads its own single comment.
        (rf/dispatch-sync [:rf.route/handle-url-change "/article/beta"] {:frame f})
        (settle-comments-ok! f (req-by-id @lowered [:comments/load "beta"]) "beta")
        (let [com-before (comments-slice* f)]
          (settle-fail! f (:on-failure del))
          (is (= com-before (comments-slice* f))
              "a late alpha DELETE failure changes nothing on beta's slice")
          (is (= ["First on beta"]
                 (mapv :body (rf/compute-sub [:comments/data] (rf/frame-state-value f))))
              "alpha's comment is NOT re-inserted into beta's list — the
               rollback would otherwise fabricate a comment beta never had"))))))

(defn- comment-mutation-gates-are-not-vacuous-test []
  ;; The controls: on the CURRENT slug every one of the three settles still
  ;; does its ordinary optimistic job. A gate that swallowed them all would
  ;; pass the three cross-slug tests above and break the app.
  (with-held-comment-fx :realworld.test/comment-same-slug
    (fn [f lowered]
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/alpha"] {:frame f})
      (settle-comments-ok! f (req-by-id @lowered [:comments/load "alpha"]) "alpha")

      ;; (1) SUCCESS on the current slug reconciles: the temp card becomes the
      ;; saved comment IN PLACE, and the form resets.
      (rf/dispatch-sync [:comment-form/edit-field :body "Mine"] {:frame f})
      (rf/dispatch-sync [:comment-form/submit] {:frame f})
      (let [post (req-by-method+url @lowered :post "/articles/alpha/comments")]
        (is (true? (rf/compute-sub [:comment-form/submitting?] (rf/frame-state-value f)))
            "the form is submitting while the POST is out")
        (settle-ok! f (:on-success post) {:comment (saved-comment "saved-a" "Mine")})
        (is (= ["First on alpha" "Mine"]
               (mapv :body (rf/compute-sub [:comments/data] (rf/frame-state-value f))))
            "the saved comment replaced the temp card IN PLACE, keeping order")
        (is (= ["c-alpha" "saved-a"]
               (mapv :id (rf/compute-sub [:comments/data] (rf/frame-state-value f))))
            "…by id, so the temp card is gone rather than merely relabelled")
        (is (= "" (:body (rf/compute-sub [:comment-form/draft] (rf/frame-state-value f))))
            "…and the form reset, so the reader can post again")
        (is (false? (rf/compute-sub [:comment-form/submitting?] (rf/frame-state-value f)))
            "…no longer submitting"))

      ;; (2) FAILURE on the current slug rolls the temp card back out and
      ;; surfaces the message.
      (rf/dispatch-sync [:comment-form/edit-field :body "Doomed"] {:frame f})
      (rf/dispatch-sync [:comment-form/submit] {:frame f})
      (let [post2 (last (filter #(= :post (get-in % [:request :method])) @lowered))]
        (is (= 3 (count (rf/compute-sub [:comments/data] (rf/frame-state-value f))))
            "the second temp card is on the list optimistically")
        (settle-fail! f (:on-failure post2))
        (is (= ["c-alpha" "saved-a"]
               (mapv :id (rf/compute-sub [:comments/data] (rf/frame-state-value f))))
            "the failed post's temp card is rolled back out")
        (is (some? (rf/compute-sub [:comment-form/submit-error] (rf/frame-state-value f)))
            "…and the transport error is surfaced on the form it belongs to")
        (is (false? (rf/compute-sub [:comment-form/submitting?] (rf/frame-state-value f)))
            "…with the form released"))

      ;; (3) A DELETE failure on the current slug still restores the comment.
      (rf/dispatch-sync [:comment/delete "saved-a"] {:frame f})
      (is (= ["c-alpha"] (mapv :id (rf/compute-sub [:comments/data] (rf/frame-state-value f))))
          "the optimistic delete removed it")
      (let [del (req-by-method+url @lowered :delete "/articles/alpha/comments/saved-a")]
        (settle-fail! f (:on-failure del))
        (is (= ["c-alpha" "saved-a"]
               (mapv :id (rf/compute-sub [:comments/data] (rf/frame-state-value f))))
            "the rollback restored the comment at its original index — the
             gate correlates, it does not swallow every reply")))))

(defn- comment-form-is-released-on-slug-change-test []
  ;; THE STRAND CONTROL. Refusing a cross-slug settle is only safe because
  ;; navigation has already released the form. Without the reset in
  ;; :comments/load the form would arrive on beta still :submitting, and a
  ;; :submitting form disables both the textarea and the Post button — so
  ;; beta could never be commented on again.
  (with-held-comment-fx :realworld.test/comment-form-release
    (fn [f lowered]
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/alpha"] {:frame f})
      (settle-comments-ok! f (req-by-id @lowered [:comments/load "alpha"]) "alpha")
      (rf/dispatch-sync [:comment-form/edit-field :body "Half-written on alpha"] {:frame f})
      (rf/dispatch-sync [:comment-form/submit] {:frame f})
      (let [post (req-by-method+url @lowered :post "/articles/alpha/comments")]
        (is (true? (rf/compute-sub [:comment-form/submitting?] (rf/frame-state-value f)))
            "alpha's form is mid-submit, its controls disabled")
        ;; Navigate away with the POST still out.
        (rf/dispatch-sync [:rf.route/handle-url-change "/article/beta"] {:frame f})
        (is (false? (rf/compute-sub [:comment-form/submitting?] (rf/frame-state-value f)))
            "beta's form is USABLE on arrival — a new article identity resets
             the form, so alpha's in-flight submit cannot lock beta out")
        (is (= "" (:body (rf/compute-sub [:comment-form/draft] (rf/frame-state-value f))))
            "…and alpha's half-written draft did not follow the reader over")
        (settle-comments-ok! f (req-by-id @lowered [:comments/load "beta"]) "beta")
        ;; Alpha's settle arrives late and is refused — and the form beta is
        ;; holding stays exactly as usable as it was.
        (settle-ok! f (:on-success post) {:comment (saved-comment "saved-a" "Half-written on alpha")})
        (is (false? (rf/compute-sub [:comment-form/submitting?] (rf/frame-state-value f)))
            "the refused settle leaves beta's form released, not stranded")
        ;; And beta can genuinely post its own comment afterwards.
        (rf/dispatch-sync [:comment-form/edit-field :body "Beta's own"] {:frame f})
        (rf/dispatch-sync [:comment-form/submit] {:frame f})
        (let [beta-post (req-by-method+url @lowered :post "/articles/beta/comments")]
          (is (some? beta-post) "beta's own comment POST goes out")
          (settle-ok! f (:on-success beta-post) {:comment (saved-comment "saved-b" "Beta's own")})
          (is (= ["c-beta" "saved-b"]
                 (mapv :id (rf/compute-sub [:comments/data] (rf/frame-state-value f))))
              "…and lands on beta's own list"))))))

(defn- comment-form-survives-same-slug-refresh-test []
  ;; The other side of the reset: a SAME-slug re-load is a refresh, not a new
  ;; identity, so it must not eat what the reader is part-way through typing.
  (with-held-comment-fx :realworld.test/comment-form-refresh
    (fn [f lowered]
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/alpha"] {:frame f})
      (settle-comments-ok! f (req-by-id @lowered [:comments/load "alpha"]) "alpha")
      (rf/dispatch-sync [:comment-form/edit-field :body "Still typing"] {:frame f})
      (rf/dispatch-sync [:comments/load] {:frame f})
      (is (= "Still typing"
             (:body (rf/compute-sub [:comment-form/draft] (rf/frame-state-value f))))
          "a same-slug comments refresh leaves the in-progress draft alone"))))

(deftest realworld-comment-mutations-cross-slug
  (testing "a LATE alpha comment-submit SUCCESS cannot reset beta's form or
            splice alpha's saved comment into beta's list (rf2-84iek)"
    (comment-submit-cross-slug-late-settle-is-refused-test))
  (testing "a LATE alpha comment-submit FAILURE cannot banner beta's form or
            disturb beta's half-typed draft (rf2-84iek)"
    (comment-submit-cross-slug-late-failure-is-refused-test))
  (testing "a LATE alpha DELETE failure cannot re-insert alpha's comment into
            beta's list (rf2-84iek)"
    (comment-delete-cross-slug-late-rollback-is-refused-test))
  (testing "on the CURRENT slug all three settles still do their ordinary
            optimistic job — the gates' non-vacuity control (rf2-84iek)"
    (comment-mutation-gates-are-not-vacuous-test))
  (testing "a new article identity releases the comment form, so refusing a
            cross-slug settle cannot strand beta mid-submit (rf2-84iek)"
    (comment-form-is-released-on-slug-change-test))
  (testing "…while a same-slug refresh leaves an in-progress draft alone
            (rf2-84iek)"
    (comment-form-survives-same-slug-refresh-test)))

;; ============================================================================
;; article page — the SOCIAL settles stay owned by the route too (rf2-amhpk)
;; ============================================================================
;;
;; rf2-84iek correlated the comment mutations and left the article's own
;; social settles alone, naming them in the source as a suspected same-class
;; defect it had not reproduced. It is the same class, and it does reproduce.
;;
;; `:article/toggle-follow-author` flips `[:article :data :author :following]`
;; optimistically and sends a POST/DELETE whose reply targets carried no slug.
;; Follow eve on /article/alpha, walk to /article/beta before the reply lands,
;; and the settle writes into BETA's author:
;;
;;   - a late FAILURE (`:article/author-follow-rollback`) restores ALPHA's
;;     prior flag onto beta's author, so beta's Follow/Unfollow button reads
;;     the opposite of the truth;
;;   - a late SUCCESS (`:article/author-follow-synced`) is worse — it
;;     `assoc-in`s alpha's whole author profile over beta's, so the byline
;;     name, the avatar and the profile link all become the wrong person's.
;;
;; `:article/delete-failed` is the third of the same shape: alpha's failed
;; DELETE banners its error on `[:article :error]`, where beta's page shows it
;; until the next load.
;;
;; `:article/delete-success` is the fourth, and it was excluded from the first
;; pass because it writes no db at all. It navigates, and navigation is the
;; route's own state — the most visible state there is. Delete alpha, walk to
;; beta before the server answers, and the late success took beta's reader home
;; and threw away the route they had chosen. Refusing it strands nothing: the
;; deletion succeeded on the server either way, so there is no retry to lose
;; and nothing left half-done.
;;
;; And it needs a DIFFERENT gate from the other three, which is what the
;; second pass on this bead found. The three writes land in `[:article …]`, so
;; the slice's own slug is the right owner to ask about. The navigation does
;; not — it is the ROUTE's — and the slice's slug outlives a walk to any
;; NON-ARTICLE page, because home, a profile, login and the editor all run
;; their own `:on-match` without touching `[:article]`. Alpha → beta cannot
;; show that (beta's `:article/load` overwrites the cached slug on the way in),
;; which is precisely why the first pass's refusal test passed over the gap;
;; alpha → `/profile/eve` shows it, and that is the test below.
;;
;; The fix is the gate alone, with NO reset half — the difference from
;; rf2-84iek that matters. `[:comment-form]` was a boot-time singleton that
;; rode across the navigation still `:submitting`, so gating it without a
;; reset would have locked beta's form; `[:article]` is rebuilt wholesale by
;; `:article/load` on a slug change, and neither the Follow button nor the
;; Delete button carries any pending or disabled state, so the navigation has
;; already released everything a refused settle would have touched.
;; `beta-slice-is-rebuilt` below is what pins that, and it is why refusing
;; strands nothing.
;;
;; These reuse `with-held-comment-fx` — the shared held-request harness for
;; the article page, comment-flavoured only in its name.

(defn- author* [f] (rf/compute-sub [:article/author] (rf/frame-state-value f)))

(defn- settle-article-with-author!
  "Settle the held article GET for `slug` with an article whose author is
   `username` at `following?`. The two slugs in these tests deliberately carry
   DIFFERENT authors in DIFFERENT follow states, so a write that crosses from
   one to the other shows up instead of being coincidentally equal."
  [f lowered slug username following?]
  (rf/dispatch-sync
    (conj (:reply-to (req-by-id @lowered [:article/load slug]))
          {:status :ok
           :value {:article (assoc (full-article slug (str "Title " slug))
                                   :author {:username username :bio nil
                                            :image nil :following following?})}})
    {:frame f}))

(defn- follow-alpha-then-walk-to-beta!
  "The shared arrangement: read /article/alpha whose author `eve` is NOT
   followed, click Follow (optimistic flip, POST held open), then walk to
   /article/beta whose author `bob` IS followed. Returns the held follow
   request so the caller can settle it however it likes, far too late."
  [f lowered]
  (rf/dispatch-sync [:rf.route/handle-url-change "/article/alpha"] {:frame f})
  (settle-article-with-author! f lowered "alpha" "eve" false)
  (is (false? (:following (author* f))) "alpha's author eve starts unfollowed")
  (rf/dispatch-sync [:article/toggle-follow-author] {:frame f})
  (is (true? (:following (author* f))) "the flip is optimistic — eve reads followed at once")
  (let [follow-req (req-by-method+url @lowered :post "/profiles/eve/follow")]
    (is (some? follow-req) "the follow POST went out and is held open")
    (rf/dispatch-sync [:rf.route/handle-url-change "/article/beta"] {:frame f})
    (settle-article-with-author! f lowered "beta" "bob" true)
    (is (= "bob" (:username (author* f))) "beta's author is bob")
    (is (true? (:following (author* f))) "…whom the reader does follow")
    follow-req))

(defn- follow-cross-slug-late-rollback-is-refused-test []
  (with-held-comment-fx :realworld.test/follow-cross-slug-rollback
    (fn [f lowered]
      (let [follow-req (follow-alpha-then-walk-to-beta! f lowered)]
        ;; The strand control, and the reason a gate needs no reset half here:
        ;; the navigation already rebuilt the slice, so the optimistic flip a
        ;; refused rollback would have undone is long gone.
        (is (= "beta" (:slug (article-slice* f)))
            "beta-slice-is-rebuilt — :article/load reset [:article] on the slug change")
        ;; Alpha's follow POST fails, long after the reader left alpha.
        (rf/dispatch-sync (conj (:on-failure follow-req)
                                {:status :error :error {:kind :rf.http/http-5xx :status 500}})
                          {:frame f})
        (is (= "bob" (:username (author* f)))
            "a LATE alpha follow FAILURE leaves beta's author alone")
        (is (true? (:following (author* f)))
            "…and does not flip beta's Follow button to the wrong state (rf2-amhpk)")))))

(defn- follow-cross-slug-late-sync-is-refused-test []
  (with-held-comment-fx :realworld.test/follow-cross-slug-sync
    (fn [f lowered]
      (let [follow-req (follow-alpha-then-walk-to-beta! f lowered)]
        ;; Alpha's follow POST SUCCEEDS, long after the reader left alpha. The
        ;; synced handler re-seeds the whole author map, so an ungated write
        ;; here swaps beta's byline for alpha's author outright.
        (rf/dispatch-sync (conj (:on-success follow-req)
                                {:status :ok
                                 :value {:profile {:username "eve" :bio "Writer"
                                                   :image nil :following true}}})
                          {:frame f})
        (is (= "bob" (:username (author* f)))
            "a LATE alpha follow SUCCESS does not replace beta's author with alpha's (rf2-amhpk)")
        (is (nil? (:bio (author* f)))
            "…not even partially — alpha's bio never reaches beta's byline")))))

(defn- article-delete-cross-slug-late-failure-is-refused-test []
  (with-held-comment-fx :realworld.test/article-delete-cross-slug
    (fn [f lowered]
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/alpha"] {:frame f})
      (settle-article-with-author! f lowered "alpha" "alice" false)
      (rf/dispatch-sync [:article/delete] {:frame f})
      (let [delete-req (req-by-method+url @lowered :delete "/articles/alpha")]
        (is (some? delete-req) "the article DELETE went out and is held open")
        (rf/dispatch-sync [:rf.route/handle-url-change "/article/beta"] {:frame f})
        (settle-article-with-author! f lowered "beta" "bob" true)
        (rf/dispatch-sync (conj (:on-failure delete-req)
                                {:status :error :error {:kind :rf.http/http-5xx :status 500}})
                          {:frame f})
        (is (nil? (:error (article-slice* f)))
            "a LATE alpha DELETE failure does not banner its error over beta's page (rf2-amhpk)")))))

(defn- article-delete-cross-slug-late-success-is-refused-test []
  ;; The fourth of the shape, and the one the first pass excluded because it
  ;; writes no db. Navigation is state all the same — the reader's own — and a
  ;; late alpha success took it away from them.
  (with-held-comment-fx :realworld.test/article-delete-cross-slug-ok
    (fn [f lowered]
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/alpha"] {:frame f})
      (settle-article-with-author! f lowered "alpha" "alice" false)
      (rf/dispatch-sync [:article/delete] {:frame f})
      (let [delete-req (req-by-method+url @lowered :delete "/articles/alpha")]
        (is (some? delete-req) "the article DELETE went out and is held open")
        (is (= [:article/delete-success "alpha"] (:on-success delete-req))
            "the DELETE's SUCCESS target carries the slug it was issued on, not
             just its failure target")
        ;; The reader gives up waiting and reads another article.
        (rf/dispatch-sync [:rf.route/handle-url-change "/article/beta"] {:frame f})
        (settle-article-with-author! f lowered "beta" "bob" true)
        ;; The strand control, exactly as for the three writes above: the
        ;; navigation already rebuilt the slice, and the delete left no pending
        ;; flag or disabled control behind, so refusing this settle abandons
        ;; nothing. The server deletion stands; alpha is simply gone.
        (is (= "beta" (:slug (article-slice* f)))
            "beta-slice-is-rebuilt — [:article] is beta's before the late settle")
        ;; Alpha's DELETE succeeds, far too late.
        (rf/dispatch-sync (conj (:on-success delete-req) {:status :ok :value nil})
                          {:frame f})
        (is (= :realworld.article/show
               (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
            "a LATE alpha DELETE SUCCESS does not yank beta's reader home (rf2-amhpk)")
        (is (= {:slug "beta"} (route-params* f))
            "…the reader's own newer route choice is the one that stands")
        (is (= "beta" (:slug (article-slice* f)))
            "…and beta's article is still the one on screen")))))

(defn- article-delete-non-article-route-late-success-is-refused-test []
  ;; The same late success, except the reader walks somewhere that is not an
  ;; article at all. This is the case a slug-only gate cannot see, and the
  ;; reason `:article/delete-success` asks the ROUTE rather than the slice:
  ;; `/profile/eve` runs its OWN `:on-match` and never touches `[:article]`,
  ;; so the slice still targets alpha long after alpha has left the screen.
  ;; Ask "is alpha the slug the slice is on?" and the answer is yes; ask "is
  ;; the reader still on alpha's page?" and it is no. Navigation is the
  ;; route's own outcome, so the route is the question that has to be asked.
  ;;
  ;; `/article/beta` cannot expose this, because `:article/load` happens to
  ;; overwrite the cached slug on the way in — which is exactly why the first
  ;; pass's refusal test passed while this gap stayed open.
  (with-held-comment-fx :realworld.test/article-delete-off-article
    (fn [f lowered]
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/alpha"] {:frame f})
      (settle-article-with-author! f lowered "alpha" "eve" false)
      (rf/dispatch-sync [:article/delete] {:frame f})
      (let [delete-req (req-by-method+url @lowered :delete "/articles/alpha")]
        (is (some? delete-req) "the article DELETE went out and is held open")
        ;; The reader gives up waiting and opens the author's profile.
        (rf/dispatch-sync [:rf.route/handle-url-change "/profile/eve"] {:frame f})
        (is (= :realworld.profile/show
               (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
            "the reader is on a NON-ARTICLE route")
        ;; The witness the whole test turns on: a non-article route releases
        ;; nothing, so the slice's own slug is still alpha's.
        (is (= "alpha" (:slug (article-slice* f)))
            "the article slice still carries alpha — the profile route's
             :on-match leaves [:article] alone, so a slug-only gate admits
             what follows")
        ;; And nothing is stranded by refusing it, for the same reason as the
        ;; four settles above: the server deletion stands, the Delete button
        ;; carries no pending state, and returning to alpha later just fails
        ;; and reloads like any other missing article.
        (rf/dispatch-sync (conj (:on-success delete-req) {:status :ok :value nil})
                          {:frame f})
        (is (= :realworld.profile/show
               (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
            "a LATE alpha DELETE SUCCESS does not yank a reader off a
             NON-ARTICLE route (rf2-amhpk)")
        (is (= {:username "eve"} (route-params* f))
            "…the reader's own newer route choice is the one that stands")))))

(defn- article-delete-current-slug-still-navigates-home-test []
  ;; The navigation control for the gate above: refusing a STALE success must
  ;; not cost the ordinary one. Delete the article you are reading, settle it
  ;; while you are still reading it, and you go home as before.
  (with-held-comment-fx :realworld.test/article-delete-current-slug
    (fn [f lowered]
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/alpha"] {:frame f})
      (settle-article-with-author! f lowered "alpha" "alice" false)
      (rf/dispatch-sync [:article/delete] {:frame f})
      (let [delete-req (req-by-method+url @lowered :delete "/articles/alpha")]
        (is (= :realworld.article/show
               (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
            "still on the article while the DELETE is out")
        (rf/dispatch-sync (conj (:on-success delete-req) {:status :ok :value nil})
                          {:frame f})
        (is (= :realworld/home
               (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
            ":article/delete-success still navigates home when its own slug is
             the one on screen — the gate is not vacuous (rf2-amhpk)")))))

(defn- article-social-gates-are-not-vacuous-test []
  ;; The control that keeps the three refusals above honest: a gate wired to
  ;; refuse everything would satisfy all of them and break the app. On the
  ;; CURRENT slug each settle must still do its ordinary job.
  (with-held-comment-fx :realworld.test/article-social-not-vacuous
    (fn [f lowered]
      (rf/dispatch-sync [:rf.route/handle-url-change "/article/alpha"] {:frame f})
      (settle-article-with-author! f lowered "alpha" "eve" false)
      ;; 1. Follow succeeds on the slug it was issued on → author re-seeded
      ;;    from the server's authoritative profile.
      (rf/dispatch-sync [:article/toggle-follow-author] {:frame f})
      (rf/dispatch-sync (conj (:on-success (req-by-method+url @lowered :post "/profiles/eve/follow"))
                              {:status :ok
                               :value {:profile {:username "eve" :bio "Writer"
                                                 :image nil :following true}}})
                        {:frame f})
      (is (= "Writer" (:bio (author* f)))
          ":article/author-follow-synced still re-seeds the author on the current slug")
      ;; 2. Unfollow fails on the slug it was issued on → prior flag restored.
      (rf/dispatch-sync [:article/toggle-follow-author] {:frame f})
      (is (false? (:following (author* f))) "the unfollow flips optimistically")
      (rf/dispatch-sync (conj (:on-failure (req-by-method+url @lowered :delete "/profiles/eve/follow"))
                              {:status :error :error {:kind :rf.http/http-4xx :status 422}})
                        {:frame f})
      (is (true? (:following (author* f)))
          ":article/author-follow-rollback still restores the prior flag on the current slug")
      ;; 3. Delete fails on the slug it was issued on → the error is bannered.
      (rf/dispatch-sync [:article/delete] {:frame f})
      (rf/dispatch-sync (conj (:on-failure (req-by-method+url @lowered :delete "/articles/alpha"))
                              {:status :error :error {:kind :rf.http/http-5xx :status 500}})
                        {:frame f})
      (is (some? (:error (article-slice* f)))
          ":article/delete-failed still surfaces its error on the current slug"))))

(deftest realworld-article-social-cross-slug
  (testing "a LATE alpha follow FAILURE cannot flip beta's author's Follow
            button (rf2-amhpk)"
    (follow-cross-slug-late-rollback-is-refused-test))
  (testing "a LATE alpha follow SUCCESS cannot replace beta's author with
            alpha's (rf2-amhpk)"
    (follow-cross-slug-late-sync-is-refused-test))
  (testing "a LATE alpha DELETE failure cannot banner its error over beta's
            page (rf2-amhpk)"
    (article-delete-cross-slug-late-failure-is-refused-test))
  (testing "a LATE alpha DELETE success cannot navigate beta's reader home
            (rf2-amhpk)"
    (article-delete-cross-slug-late-success-is-refused-test))
  (testing "…nor a reader who walked to a NON-ARTICLE route, which no
            slug-only gate can see (rf2-amhpk)"
    (article-delete-non-article-route-late-success-is-refused-test))
  (testing "…while a delete settled on its own slug still goes home — the
            navigation control (rf2-amhpk)"
    (article-delete-current-slug-still-navigates-home-test))
  (testing "on the CURRENT slug all three settles still do their ordinary job
            — the gates' non-vacuity control (rf2-amhpk)"
    (article-social-gates-are-not-vacuous-test)))

;; ============================================================================
;; favorites / comments / feed / profile — optimistic-success + follow-author +
;; article-delete + blank-comment + feed-load + profile-follow (rf2-rq65wv)
;; ============================================================================
;;
;; favorite-toggle-test above covers the FAILURE rollback only; several
;; demonstrated handlers had no coverage. These pin the success-sync re-seed, the
;; detail-page follow-author + article-delete flows, the comment-form client
;; validation, the user-feed load lifecycle, the profile follow/unfollow/rollback,
;; and the pure home-context flattener.

(defn- favorite-synced-success-test []
  ;; :article/favorite-synced re-seeds the article from the server's authoritative
  ;; reply (via select-keys), overwriting the optimistic guess.
  (reg-canned-success! :realworld.test/favorite-ok
    {:article {:slug "hello" :title "Hello" :description "Short" :body "Body" :tagList []
               :createdAt "x" :updatedAt "x"
               :favorited true :favoritesCount 42
               :author {:username "alice" :bio nil :image nil :following false}}})
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/favorite-ok}})]
    (rf/dispatch-sync [:articles/initialise] {:frame f})
    (rf/dispatch-sync [:auth/store-session {:username "alice" :email "a@b.c" :token "jwt" :bio nil :image nil}] {:frame f})
    (rf/dispatch-sync [:articles/loaded
                       {:kind :success
                        :value {:articles [{:slug "hello" :title "Hello" :description "Short"
                                            :body "Body" :tagList [] :createdAt "x" :updatedAt "x"
                                            :favorited false :favoritesCount 0
                                            :author {:username "alice" :bio nil :image nil :following false}}]}}]
                      {:frame f})
    (rf/dispatch-sync [:article/toggle-favorite "hello"] {:frame f})
    (let [art (first (rf/compute-sub [:articles/data] (rf/frame-state-value f)))]
      (is (true? (:favorited art)) "the article is favorited after the synced reply")
      ;; The count is the SERVER's 42, not the optimistic guess of 1 — proving the
      ;; success handler re-seeded from the reply rather than trusting the optimism.
      (is (= 42 (:favoritesCount art))
          ":article/favorite-synced re-seeds the count from the server reply (select-keys)"))))

(defn- article-follow-author-test []
  ;; :article/toggle-follow-author — optimistic flip, then :article/author-follow-synced
  ;; re-seeds the author from the returned profile; the -rollback handler restores
  ;; the prior flag (driven directly, as comment-delete-rollback-stale-index-test
  ;; drives its rollback).
  (reg-canned-success-by-url! :realworld.test/follow-author
    (fn [url]
      (cond
        (str/includes? url "/follow")    {:profile {:username "eve" :bio "Writer" :image nil :following true}}
        (str/ends-with? url "/comments") {:comments []}
        :else {:article {:slug "hello" :title "Hello" :description "d" :body "b"
                         :tagList [] :createdAt "x" :updatedAt "x"
                         :favorited false :favoritesCount 0
                         :author {:username "eve" :bio nil :image nil :following false}}})))
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/follow-author}})]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/article/hello"] {:frame f})
    (is (false? (:following (rf/compute-sub [:article/author] (rf/frame-state-value f))))
        "eve starts unfollowed")
    (rf/dispatch-sync [:article/toggle-follow-author] {:frame f})
    (let [author (rf/compute-sub [:article/author] (rf/frame-state-value f))]
      (is (true? (:following author)) "the author is followed after the synced reply")
      (is (= "Writer" (:bio author))
          ":article/author-follow-synced re-seeds the author from the returned profile"))
    ;; Rollback handler (driven directly): restores the captured prior flag.
    ;; The leading "hello" is the issuing slug the handler now correlates on
    ;; (rf2-amhpk); the route is /article/hello, so the gate admits it. Pass a
    ;; different slug and this assertion fails — which is exactly what
    ;; follow-cross-slug-late-rollback-is-refused-test pins.
    (rf/dispatch-sync [:article/author-follow-rollback "hello" false {:kind :rf.http/http-4xx}] {:frame f})
    (is (false? (:following (rf/compute-sub [:article/author] (rf/frame-state-value f))))
        ":article/author-follow-rollback restores the captured prior following flag")))

(defn- article-detail-delete-test []
  ;; :article/delete (detail page) → :article/delete-success → navigate home; and
  ;; :article/delete-failed surfaces a readable error.
  (reg-canned-success-by-url! :realworld.test/detail-delete
    (fn [url]
      (if (str/ends-with? url "/comments")
        {:comments []}
        {:article {:slug "hello" :title "Hello" :description "d" :body "b" :tagList []
                   :createdAt "x" :updatedAt "x" :favorited false :favoritesCount 0
                   :author {:username "alice" :bio nil :image nil :following false}}})))
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/detail-delete}})]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/article/hello"] {:frame f})
    (is (= "hello" (:slug (rf/compute-sub [:article/data] (rf/frame-state-value f)))))
    ;; The failure branch (driven directly), taken FIRST so it runs while the
    ;; reader is still on /article/hello — the slug it now correlates against
    ;; (rf2-amhpk). Driving it after the successful delete would leave the
    ;; assertion hostage to whatever the home route does to `[:article :slug]`.
    (rf/dispatch-sync [:article/delete-failed "hello" {:error {:kind :rf.http/http-5xx :status 500}}] {:frame f})
    (is (some? (rf/compute-sub [:article/error] (rf/frame-state-value f)))
        ":article/delete-failed surfaces a readable error message")
    (rf/dispatch-sync [:article/delete] {:frame f})
    (is (= :realworld/home (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "a successful detail-page delete navigates home")))

(defn- comment-blank-body-test []
  ;; :comment-form/submit with a blank body → client-side validation, no round trip.
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    ;; A whitespace-only body is still blank after trim.
    (rf/dispatch-sync [:comment-form/edit-field :body "   "] {:frame f})
    (rf/dispatch-sync [:comment-form/submit] {:frame f})
    (is (= "Comment body is required."
           (rf/compute-sub [:comment-form/field-error :body] (rf/frame-state-value f)))
        "a blank comment body fails on the client with a per-field :body error")
    (is (nil? (rf/compute-sub [:comment-form/submit-error] (rf/frame-state-value f)))
        "the client-validation branch leaves :submit-error alone (that's the transport door)")))

(defn- feed-load-test []
  ;; :feed/load → :feed/loaded populates the user-feed slice + grand count.
  (reg-canned-success! :realworld.test/feed-ok
    {:articles [{:slug "f1" :title "Feed one" :description "d" :body "b" :tagList []
                 :createdAt "x" :updatedAt "x" :favorited false :favoritesCount 0
                 :author {:username "bob" :bio nil :image nil :following true}}]
     :articlesCount 7})
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/feed-ok}})]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:feed/load] {:frame f})
    (is (= 1 (count (rf/compute-sub [:feed/data] (rf/frame-state-value f))))
        ":feed/loaded populates the user-feed slice")
    (is (= 7 (rf/compute-sub [:feed/count] (rf/frame-state-value f)))
        "the grand articles-count is stored for pagination")
    (is (not (rf/compute-sub [:feed/loading?] (rf/frame-state-value f)))
        "a settled feed load is no longer loading")))

(defn- feed-load-failure-test []
  (reg-canned-failure! :realworld.test/feed-fail :rf.http/http-5xx {:status 500 :body "boom"})
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/feed-fail}})]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:feed/load] {:frame f})
    (is (some? (rf/compute-sub [:feed/error] (rf/frame-state-value f)))
        ":feed/load-failed surfaces a readable error on the feed slice")))

(defn- profile-follow-test []
  ;; :profile/follow + :profile/followed (re-seed from reply) + :profile/unfollow +
  ;; :profile/follow-rollback, plus the favorites-tab load path.
  (reg-canned-success-by-url! :realworld.test/profile-follow
    (fn [method url]
      (cond
        (str/includes? url "/follow")
        {:profile {:username "eve" :bio "Bio" :image nil :following (= :post method)}}
        (str/includes? url "/profiles/")
        {:profile {:username "eve" :bio "Bio" :image nil :following false}}
        ;; article list reads (favorited / authored tabs)
        :else {:articles [{:slug "a1" :title "A1" :description "d" :body "b" :tagList []
                           :createdAt "x" :updatedAt "x" :favorited true :favoritesCount 3
                           :author {:username "eve" :bio nil :image nil :following false}}]
               :articlesCount 3})))
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/profile-follow}})]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    ;; Land on the favorites tab so :profile.favorites/load runs too.
    (rf/dispatch-sync [:rf.route/handle-url-change "/profile/eve/favorites"] {:frame f})
    (is (= "eve" (:username (rf/compute-sub [:profile/data] (rf/frame-state-value f))))
        "the profile banner loads")
    (is (= 1 (count (rf/compute-sub [:profile.favorites/data] (rf/frame-state-value f))))
        "the favorites tab list loads (:profile.favorites/load)")
    (is (false? (:following (rf/compute-sub [:profile/data] (rf/frame-state-value f))))
        "eve starts unfollowed")
    ;; Follow → optimistic true → :profile/followed re-seeds from the reply.
    (rf/dispatch-sync [:profile/follow] {:frame f})
    (is (true? (:following (rf/compute-sub [:profile/data] (rf/frame-state-value f))))
        ":profile/followed re-seeds :following true from the returned profile")
    ;; Unfollow → :profile/unfollowed re-seeds false.
    (rf/dispatch-sync [:profile/unfollow] {:frame f})
    (is (false? (:following (rf/compute-sub [:profile/data] (rf/frame-state-value f))))
        ":profile/unfollowed re-seeds :following false")
    ;; Rollback handler (driven directly): restores the captured prior flag.
    ;; The username is the identity the flip was issued on, and the handler is
    ;; gated on it (rf2-8icg) — the cross-username refusal is pinned by
    ;; `realworld-profile-page-cross-username` below.
    (rf/dispatch-sync [:profile/follow-rollback "eve" true {:kind :rf.http/http-4xx}] {:frame f})
    (is (true? (:following (rf/compute-sub [:profile/data] (rf/frame-state-value f))))
        ":profile/follow-rollback restores the captured prior following flag")))

(defn- home-context-test []
  ;; tags/home-context flattens the two home routes into one {:tag :feed :page}.
  (let [rt {:rf.runtime/routing {:current {:params {:tag "clojure"}
                                           :query  {:feed "following" :page 2}}}}]
    (is (= {:tag "clojure" :feed "following" :page 2} (tags/home-context rt))
        "the tag (path param) + feed/page (query) flatten into one context map"))
  (let [rt {:rf.runtime/routing {:current {}}}]
    (is (= {:tag nil :feed nil :page nil} (tags/home-context rt))
        "an empty route yields an all-nil context (no NPE)")))

(deftest realworld-favorites-follow-feed
  (testing ":article/favorite-synced re-seeds the count from the server reply (rf2-rq65wv)"
    (favorite-synced-success-test))
  (testing ":article/toggle-follow-author optimistic + synced + rollback (rf2-rq65wv)"
    (article-follow-author-test))
  (testing ":article/delete navigates home; :article/delete-failed surfaces an error (rf2-rq65wv)"
    (article-detail-delete-test))
  (testing ":comment-form/submit blank body fails on the client, no round trip (rf2-rq65wv)"
    (comment-blank-body-test))
  (testing "user feed :feed/load / :feed/loaded populate the slice (rf2-rq65wv)"
    (feed-load-test))
  (testing "user feed :feed/load-failed surfaces an error (rf2-rq65wv)"
    (feed-load-failure-test))
  (testing "profile follow / unfollow / rollback + favorites-tab load (rf2-rq65wv)"
    (profile-follow-test))
  (testing "home-context flattens the two home routes into {:tag :feed :page} (rf2-rq65wv)"
    (home-context-test)))

;; ============================================================================
;; profile page — cross-username replies stay owned by the route (rf2-8icg)
;; ============================================================================
;;
;; The profile page repeats the article page's navigation-staleness law
;; (`realworld-article-page-cross-slug` above) over its own THREE shared
;; slices. `/profile/:username`'s on-match dispatches :profile/load and
;; :profile.articles/load; `/profile/:username/favorites` dispatches
;; :profile/load and :profile.favorites/load; the Follow button writes the same
;; route-owned `[:profile …]` slice from a POST nobody's navigation
;; supersedes. The reads are keyed per username ([:profile/load "alice"] vs
;; [:profile/load "bob"]) — DISTINCT ids across a navigation, so managed
;; HTTP's same-id supersede never fires between them and all of them stay
;; independently deliverable. Correlation is therefore the app's own boundary,
;; and it has two halves, both witnessed below:
;;
;;   WRITE-TIME — the requested username rides every reply target, each slice
;;     records the username it is loading, and every terminal handler refuses a
;;     settle whose username the slice no longer targets. Nothing gets through:
;;     not data, not status, not the error, not the timestamp, not the machine
;;     broadcast. `pf-slice*` compares the slice WHOLE for exactly that reason.
;;
;;   READ-TIME — the two list subs ask the question again when the view reads
;;     them, because the tabs load on SEPARATE routes. /profile/alice →
;;     /profile/bob/favorites reloads the banner and the favorited list and
;;     never touches the AUTHORED one, which goes on holding alice's articles
;;     quite legitimately. What it must not do is show them under bob's URL.
;;
;; The MACHINE matters here in a way it did not on the article page. An ungated
;; late alice failure broadcasts :fetch-failed, which puts the :data region in
;; :error — a state with no fetch-succeeded edge — so bob's own later success
;; would write app-db and leave the page rendering an error over the top of it.
;; The failure test below is that strand, refused.

(defn- full-profile [username following?]
  {:username username :bio (str "Bio of " username) :image nil :following following?})

(defn- pf-slice*
  "A profile-page slice read straight off the `:rf.db/app` partition, WHOLE —
   status, data, error, loaded-at, attempt and username together — because the
   bug is never the one leaf you looked at."
  [f k]
  (get-in (rf/frame-state-value f) [:rf.db/app k]))

(defn- pf-has-tag? [f tag]
  (rf/compute-sub [:rf.machine/has-tag? :ui/profile tag] (rf/frame-state-value f)))

(defn- pf-render* [f]
  (rf/compute-sub [:profile/render] (rf/frame-state-value f)))

(defn- pf-sub* [f query]
  (rf/compute-sub query (rf/frame-state-value f)))

(defn- with-held-profile-fx
  "Run `body-fn` against a frame whose `:rf.http/managed` is a capturing stub:
   every request is recorded and NONE settles by itself, so each reply can be
   delivered by hand in the order a slow network would pick. The session is a
   third party (`zed`) so no assertion below can be satisfied by accident from
   the logged-in user's own name."
  [fx-id body-fn]
  (let [lowered (atom [])]
    (rf/reg-fx fx-id
      {:platforms #{:client :server}}
      (fn [_frame-ctx args] (swap! lowered conj args) nil))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:initial-events [[:app/initialise]]
                          :fx-overrides {:rf.http/managed fx-id}})]
      (rf/dispatch-sync [:auth/store-session {:username "zed" :email "z@b.c"
                                              :token "jwt" :bio nil :image nil}]
                        {:frame f})
      (body-fn f lowered))))

(defn- profile-cross-username-late-success-is-refused-test []
  (with-held-profile-fx :realworld.test/profile-cross-username
    (fn [f lowered]
      ;; Enter /profile/alice — its banner + authored GETs go out and stay out.
      (rf/dispatch-sync [:rf.route/handle-url-change "/profile/alice"] {:frame f})
      ;; …and follow a link to /profile/bob before either settles.
      (rf/dispatch-sync [:rf.route/handle-url-change "/profile/bob"] {:frame f})
      (is (= 4 (count @lowered))
          "both entries lowered a banner AND an authored-list GET — four requests")
      (is (= 4 (count (distinct (map :request-id @lowered))))
          "the four carry DISTINCT per-username request-ids, so same-id
           supersede never fires between them — every one is independently
           deliverable, and correlating each reply is the app's job")
      (let [a-ban (req-by-id @lowered [:profile/load "alice"])
            a-art (req-by-id @lowered [:profile.articles/load "alice"])
            b-ban (req-by-id @lowered [:profile/load "bob"])
            b-art (req-by-id @lowered [:profile.articles/load "bob"])]
        (is (= [:profile/loaded "alice"] (:on-success a-ban))
            "the banner's success target carries the username it was requested for")
        (is (= [:profile/load-failed "alice"] (:on-failure a-ban))
            "…and so does its failure target")
        (is (= [:profile.articles/loaded "alice"] (:on-success a-art))
            "…as do both of the authored list's targets")
        (is (= [:profile.articles/load-failed "alice"] (:on-failure a-art)))
        ;; Bob settles normally first — the ordinary path is untouched.
        (settle-ok! f (:on-success b-ban) {:profile (full-profile "bob" false)})
        (settle-ok! f (:on-success b-art) {:articles [(full-article "b1" "Bob one")]
                                           :articlesCount 1})
        (is (= "bob" (:username (pf-sub* f [:profile/data])))
            "bob's own banner reply is accepted through the public sub")
        (is (= ["Bob one"] (mapv :title (pf-sub* f [:profile.articles/data])))
            "bob's own authored reply is accepted through the public sub")
        ;; THE LATE ARRIVALS.
        (let [ban-before (pf-slice* f :profile)
              art-before (pf-slice* f :profile.articles)]
          (settle-ok! f (:on-success a-ban) {:profile (full-profile "alice" true)})
          (settle-ok! f (:on-success a-art) {:articles [(full-article "a1" "Alice one")]
                                             :articlesCount 9})
          (is (= {:username "bob"} (route-params* f))
              "the route still says bob")
          (is (= ban-before (pf-slice* f :profile))
              "a late alice banner success changes NOTHING on the profile slice
               — data, status, error, loaded-at, attempt and username all stand")
          (is (= art-before (pf-slice* f :profile.articles))
              "…and the late alice authored success changes nothing on the
               authored-list slice")
          (is (= 1 (pf-sub* f [:profile.articles/count]))
              "…so bob's grand count is not replaced by alice's nine"))))))

(defn- profile-cross-username-late-failure-is-refused-test []
  (with-held-profile-fx :realworld.test/profile-cross-username-fail
    (fn [f lowered]
      (rf/dispatch-sync [:rf.route/handle-url-change "/profile/alice"] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/profile/bob"] {:frame f})
      (let [a-ban (req-by-id @lowered [:profile/load "alice"])
            a-art (req-by-id @lowered [:profile.articles/load "alice"])
            b-ban (req-by-id @lowered [:profile/load "bob"])
            b-art (req-by-id @lowered [:profile.articles/load "bob"])]
        (is (true? (pf-has-tag? f :data/loading))
            "bob's banner fetch is still out — the :data region sits at :loading")
        (is (= :loading (:status (pf-slice* f :profile.articles)))
            "…and so is his authored list")
        ;; ALICE FAILS WHILE BOB IS STILL LOADING. This is the strand the fix
        ;; is about: :error has no fetch-succeeded edge, so an accepted alice
        ;; failure here would outlive bob's own success.
        (settle-fail! f (:on-failure a-ban))
        (settle-fail! f (:on-failure a-art))
        (is (true? (pf-has-tag? f :data/loading))
            "a late alice failure never reaches the machine — the :data region
             is still :loading, not :error")
        (is (false? (pf-has-tag? f :data/error))
            "…so the page-level error presentation stays shut")
        (is (nil? (pf-sub* f [:profile/error]))
            "…and no error banner is raised over bob")
        (is (nil? (:error (pf-slice* f :profile.articles)))
            "…nor over bob's authored list")
        ;; Bob's own replies land and render — the strand refused.
        (settle-ok! f (:on-success b-ban) {:profile (full-profile "bob" false)})
        (settle-ok! f (:on-success b-art) {:articles [(full-article "b1" "Bob one")]
                                           :articlesCount 1})
        (is (= "bob" (:username (pf-sub* f [:profile/data])))
            "bob's own banner success is accepted")
        (is (= :loaded (pf-render* f))
            "…and carries the machine to :loaded, so the page is NOT stranded
             in an error presentation an earlier profile's failure caused"))
      ;; NON-VACUITY for the failure gate: a CURRENT username's own failure is
      ;; still accepted. The gate correlates; it does not swallow failures.
      (rf/dispatch-sync [:rf.route/handle-url-change "/profile/carol"] {:frame f})
      (let [c-ban (req-by-id @lowered [:profile/load "carol"])
            c-art (req-by-id @lowered [:profile.articles/load "carol"])]
        (settle-fail! f (:on-failure c-ban))
        (settle-fail! f (:on-failure c-art))
        (is (= :error (:status (pf-slice* f :profile)))
            "carol's OWN banner failure is accepted")
        (is (some? (pf-sub* f [:profile/error]))
            "…with its message surfaced")
        (is (= :error (pf-render* f))
            "…and the machine does reach the error presentation for its own failure")
        (is (= :error (:status (pf-slice* f :profile.articles)))
            "carol's OWN authored failure is accepted too")))))

(defn- profile-username-change-resets-and-cross-tab-read-guard-test []
  (with-held-profile-fx :realworld.test/profile-transition
    (fn [f lowered]
      ;; Load alice's banner + authored list fully.
      (rf/dispatch-sync [:rf.route/handle-url-change "/profile/alice"] {:frame f})
      (settle-ok! f (:on-success (req-by-id @lowered [:profile/load "alice"]))
                  {:profile (full-profile "alice" false)})
      (settle-ok! f (:on-success (req-by-id @lowered [:profile.articles/load "alice"]))
                  {:articles [(full-article "a1" "Alice one")] :articlesCount 1})
      (is (= ["Alice one"] (mapv :title (pf-sub* f [:profile.articles/data]))))
      ;; SAME-username refresh control: re-firing the route's loads for the
      ;; profile already on screen keeps the loaded data up while they are out.
      (reset! lowered [])
      (rf/dispatch-sync [:profile/load] {:frame f})
      (rf/dispatch-sync [:profile.articles/load] {:frame f})
      (is (= :fetching (:status (pf-slice* f :profile)))
          "a same-username re-load is a REFRESH — :fetching, not :loading")
      (is (= "alice" (:username (pf-sub* f [:profile/data])))
          "…and alice's banner stays renderable while it is out")
      (is (= ["Alice one"] (mapv :title (pf-sub* f [:profile.articles/data])))
          "…as do her authored rows")
      (settle-ok! f (:on-success (req-by-id @lowered [:profile/load "alice"]))
                  {:profile (full-profile "alice" false)})
      (settle-ok! f (:on-success (req-by-id @lowered [:profile.articles/load "alice"]))
                  {:articles [(full-article "a1" "Alice one")] :articlesCount 1})
      ;; Now NAVIGATE across BOTH user and tab: /profile/alice →
      ;; /profile/bob/favorites reloads the banner and the FAVORITED list, and
      ;; never touches the authored one.
      (reset! lowered [])
      (rf/dispatch-sync [:rf.route/handle-url-change "/profile/bob/favorites"] {:frame f})
      (is (= 2 (count @lowered))
          "the favorites route reloads exactly two things: the banner and the
           favorited list")
      (is (some? (req-by-id @lowered [:profile.favorites/load "bob"]))
          "…the favorited list being one of them")
      (is (nil? (req-by-id @lowered [:profile.articles/load "bob"]))
          "…and NO authored-list request for bob is issued at all")
      (is (= :loading (:status (pf-slice* f :profile)))
          "a username CHANGE is not a refresh — the banner slice starts over")
      (is (nil? (pf-sub* f [:profile/data]))
          "…so alice's banner is not exposed under bob's URL while bob loads")
      ;; THE READ-TIME HALF. The authored slice still holds alice's rows, quite
      ;; legitimately — nothing reloaded it. It must not SHOW them under bob.
      (is (= "alice" (:username (pf-slice* f :profile.articles)))
          "the authored slice still targets alice — this route never reloaded it")
      (is (= 1 (count (:data (pf-slice* f :profile.articles))))
          "…and alice's rows are still sitting in it")
      (is (nil? (pf-sub* f [:profile.articles/data]))
          "…but the read-time guard refuses to expose them under bob's URL")
      (is (zero? (pf-sub* f [:profile.articles/count]))
          "…and refuses to count them either")
      (is (= [] (pf-sub* f [:profile/current-articles]))
          "…so the tab the view is rendering reads empty while bob's own
           favorited list is still in flight"))))

(defn- profile-cross-username-follow-settles-are-refused-test []
  (with-held-profile-fx :realworld.test/profile-cross-username-follow
    (fn [f lowered]
      ;; Land on alice and let her banner settle, so Follow has a profile to act on.
      (rf/dispatch-sync [:rf.route/handle-url-change "/profile/alice"] {:frame f})
      (settle-ok! f (:on-success (req-by-id @lowered [:profile/load "alice"]))
                  {:profile (full-profile "alice" false)})
      (rf/dispatch-sync [:profile/follow] {:frame f})
      (is (true? (:following (pf-sub* f [:profile/data])))
          "the optimistic flip lands on alice right away")
      (let [a-follow (req-by-method+url @lowered :post "/profiles/alice/follow")]
        (is (= [:profile/followed "alice"] (:on-success a-follow))
            "the follow POST's success target carries the username the flip was
             issued on — the follow POST has no :request-id, deliberately, so
             this target is the only identity the reply carries back")
        (is (= [:profile/follow-rollback "alice" false] (:on-failure a-follow))
            "…and its failure target carries that username AND the flag to restore")
        ;; The reader gives up waiting and opens bob's profile.
        (rf/dispatch-sync [:rf.route/handle-url-change "/profile/bob"] {:frame f})
        (settle-ok! f (:on-success (req-by-id @lowered [:profile/load "bob"]))
                    {:profile (full-profile "bob" false)})
        (let [before (pf-slice* f :profile)]
          ;; Alice's follow SUCCEEDS, late. :profile/followed re-seeds the WHOLE
          ;; banner map from its reply, so ungated this would put alice's name,
          ;; bio and avatar under bob's URL — not merely flip a flag.
          (settle-ok! f (:on-success a-follow) {:profile (full-profile "alice" true)})
          (is (= before (pf-slice* f :profile))
              "a late alice follow success changes NOTHING on bob's banner slice")
          (is (= "bob" (:username (pf-sub* f [:profile/data])))
              "…so bob's name is still the one on screen")
          ;; The same held request's ROLLBACK branch: either branch is a reply
          ;; the transport could have picked, and both must be refused.
          (settle-fail! f (:on-failure a-follow))
          (is (= before (pf-slice* f :profile))
              "a late alice rollback changes nothing either — bob's :following
               flag is not flipped by a failure that was never his")))
      ;; NON-VACUITY for BOTH branches: bob's own follow settles are accepted.
      (rf/dispatch-sync [:profile/follow] {:frame f})
      (settle-ok! f (:on-success (req-by-method+url @lowered :post "/profiles/bob/follow"))
                  {:profile (full-profile "bob" true)})
      (is (true? (:following (pf-sub* f [:profile/data])))
          "bob's OWN follow success is accepted and re-seeds :following true")
      (rf/dispatch-sync [:profile/unfollow] {:frame f})
      (is (false? (:following (pf-sub* f [:profile/data])))
          "the optimistic unfollow flip lands")
      (settle-fail! f (:on-failure (req-by-method+url @lowered :delete "/profiles/bob/follow")))
      (is (true? (:following (pf-sub* f [:profile/data])))
          "bob's OWN rollback is accepted — it restores the captured prior flag,
           so the gate correlates rather than swallowing rollbacks"))))

;; ---------------------------------------------------------------------------
;; …and the SAME-profile race the username gate cannot see (rf2-8icg residual)
;; ---------------------------------------------------------------------------
;;
;; Every settle above was refused because it named a profile the slice no
;; longer targets. That gate is blind to the other ordering hazard, because
;; both of its replies name the CURRENT profile and both therefore pass:
;; Follow flips :following true and issues a POST; the button now reads
;; "Unfollow", so a second click issues a DELETE; let the DELETE settle first
;; and the older POST settle last, and :profile/followed re-seeds the whole
;; banner :following true — an older settle overwriting a newer accepted
;; intent. Rollback ordering inverts the same way.
;;
;; The app's answer is to serialise rather than supersede (`:request-id` would
;; abort the in-flight write, and an abort is no proof the server declined it).
;; `:profile.follow-pending` holds the profiles with a mutation outstanding,
;; both handlers refuse a second intent for a profile already in it, the button
;; disables on it, and every settle takes its own username back out. So the
;; first witness below is a COUNT: the second intent never becomes a second
;; request, which removes the pair rather than refereeing it.
;;
;; That latch is keyed by username and lives OUTSIDE the banner slice, and the
;; two tests after it are the two halves of what the keying buys. A latch must
;; SURVIVE its profile leaving the screen — alice → bob → alice with the POST
;; still out is the ordering the immediate-toggle test cannot reach, because a
;; latch that died with the banner slice hands the reader a live button on the
;; way back and the pair goes out after all. And it must not LEAK onto a
;; bystander, in either direction: bob's button stays live while alice's
;; mutation is out, and alice's settle releases alice alone.

(defn- follow-requests
  "Every captured follow/unfollow request for `username`, in lowering order."
  [lowered username]
  (filterv #(str/ends-with? (get-in % [:request :url])
                            (str "/profiles/" username "/follow"))
           lowered))

(defn- profile-follow-toggle-is-serialised-test []
  (with-held-profile-fx :realworld.test/profile-follow-serialised
    (fn [f lowered]
      ;; Land on alice, not yet followed, and let her banner settle.
      (rf/dispatch-sync [:rf.route/handle-url-change "/profile/alice"] {:frame f})
      (settle-ok! f (:on-success (req-by-id @lowered [:profile/load "alice"]))
                  {:profile (full-profile "alice" false)})
      (is (false? (:following (pf-sub* f [:profile/data]))) "alice starts unfollowed")
      (is (false? (pf-sub* f [:profile/follow-pending?]))
          "…with nothing in flight, so the button is live")

      ;; ---- FIRST intent: Follow ----
      (rf/dispatch-sync [:profile/follow] {:frame f})
      (is (true? (:following (pf-sub* f [:profile/data])))
          "the optimistic flip lands immediately")
      (is (true? (pf-sub* f [:profile/follow-pending?]))
          "…and latches the toggle, which is what disables the button")
      (is (= 1 (count (follow-requests @lowered "alice")))
          "exactly one mutation out")

      ;; ---- SECOND intent while the first is outstanding: REFUSED ----
      (let [before (pf-slice* f :profile)]
        (rf/dispatch-sync [:profile/unfollow] {:frame f})
        (is (= before (pf-slice* f :profile))
            "the second intent changed NOTHING — not the flag, not the latch")
        (is (= 1 (count (follow-requests @lowered "alice")))
            "and issued NO second request: there is no pair left to reorder,
             which is how the race is removed rather than refereed")
        (is (nil? (req-by-method+url @lowered :delete "/profiles/alice/follow"))
            "specifically, no DELETE exists that could arrive out of order and
             let the older POST re-seed :following true behind it"))

      ;; ---- the first mutation settles, releasing the latch ----
      (settle-ok! f (:on-success (req-by-method+url @lowered :post "/profiles/alice/follow"))
                  {:profile (full-profile "alice" true)})
      (is (true? (:following (pf-sub* f [:profile/data])))
          "the POST's own settle is accepted and seeds the server's answer")
      (is (false? (pf-sub* f [:profile/follow-pending?]))
          "the correlated success released the latch — the button is live again")

      ;; ---- NON-VACUITY: the toggle works normally once nothing is in flight ----
      (rf/dispatch-sync [:profile/unfollow] {:frame f})
      (is (false? (:following (pf-sub* f [:profile/data])))
          "the unfollow the reader wanted is accepted now, optimistically")
      (is (true? (pf-sub* f [:profile/follow-pending?])) "and latches in its turn")
      (is (some? (req-by-method+url @lowered :delete "/profiles/alice/follow"))
          "…issuing the very DELETE that was refused a moment ago")
      (is (= 2 (count (follow-requests @lowered "alice")))
          "two mutations in total, strictly one after the other")

      ;; the block is symmetric: a follow during an in-flight unfollow
      (rf/dispatch-sync [:profile/follow] {:frame f})
      (is (= 2 (count (follow-requests @lowered "alice")))
          "an unfollow in flight blocks a follow exactly as a follow in flight
           blocked an unfollow")

      ;; ---- a correlated ROLLBACK releases the latch too ----
      (settle-fail! f (:on-failure (req-by-method+url @lowered :delete
                                                      "/profiles/alice/follow")))
      (is (true? (:following (pf-sub* f [:profile/data])))
          "the rollback restored the captured prior flag")
      (is (false? (pf-sub* f [:profile/follow-pending?]))
          "a FAILED mutation releases the latch as surely as a successful one —
           otherwise a single 500 would disable the button for good"))))

(defn- profile-follow-latch-survives-a-walk-away-and-back-test []
  (with-held-profile-fx :realworld.test/profile-follow-latch-return
    (fn [f lowered]
      ;; Alice, unfollowed, banner settled. Follow — the POST is held open.
      (rf/dispatch-sync [:rf.route/handle-url-change "/profile/alice"] {:frame f})
      (settle-ok! f (:on-success (req-by-id @lowered [:profile/load "alice"]))
                  {:profile (full-profile "alice" false)})
      (rf/dispatch-sync [:profile/follow] {:frame f})
      (let [a-follow (req-by-method+url @lowered :post "/profiles/alice/follow")]
        (is (some? a-follow) "the follow POST went out and is held open")
        (is (= 1 (count (follow-requests @lowered "alice")))
            "exactly one mutation out")

        ;; ---- away to bob, and straight back, the POST still in flight ----
        (rf/dispatch-sync [:rf.route/handle-url-change "/profile/bob"] {:frame f})
        (settle-ok! f (:on-success (req-by-id @lowered [:profile/load "bob"]))
                    {:profile (full-profile "bob" false)})
        (reset! lowered [])
        (rf/dispatch-sync [:rf.route/handle-url-change "/profile/alice"] {:frame f})
        ;; The RETURN GET reports the server's truth, and the truth is that the
        ;; POST was applied — only its REPLY is still in the air. So the reader
        ;; is looking at a followed alice with a mutation still outstanding,
        ;; which is exactly the state a slice-borne latch could not represent.
        (settle-ok! f (:on-success (req-by-id @lowered [:profile/load "alice"]))
                    {:profile (full-profile "alice" true)})
        (is (true? (:following (pf-sub* f [:profile/data])))
            "the return GET truthfully reports :following true")
        (is (= #{"alice"} (pf-slice* f :profile.follow-pending))
            "…and alice is still latched: the latch outlived the banner slice
             that was rebuilt twice under it, because it belongs to the
             MUTATION and the mutation has not settled")
        (is (true? (pf-sub* f [:profile/follow-pending?]))
            "…which the public sub reports, so the button comes back DISABLED.
             This is the whole fix — a latch that died with the slice would
             hand the reader a live button here")

        ;; ---- so the second intent is refused, and no pair reaches the wire ----
        (let [before (pf-slice* f :profile)]
          (rf/dispatch-sync [:profile/unfollow] {:frame f})
          (is (= before (pf-slice* f :profile))
              "the Unfollow is refused outright — nothing flipped")
          (is (empty? (follow-requests @lowered "alice"))
              "…and issued NO second mutation. That DELETE could have settled
               BEFORE the held POST, leaving the older reply to re-seed
               :following true over the newer accepted intent; it never exists")
          (is (nil? (req-by-method+url @lowered :delete "/profiles/alice/follow"))
              "specifically, no alice DELETE is out at all"))

        ;; ---- the held POST lands, correlated: it seeds AND releases ----
        (settle-ok! f (:on-success a-follow) {:profile (full-profile "alice" true)})
        (is (true? (:following (pf-sub* f [:profile/data])))
            "the reader is back on alice, so the banner write IS accepted")
        (is (false? (pf-sub* f [:profile/follow-pending?]))
            "…and the mutation's own settle released its latch")

        ;; NON-VACUITY: the refusal above was the latch doing its job, not a
        ;; toggle broken for good.
        (rf/dispatch-sync [:profile/unfollow] {:frame f})
        (is (false? (:following (pf-sub* f [:profile/data])))
            "the unfollow the reader wanted is accepted now, optimistically")
        (is (some? (req-by-method+url @lowered :delete "/profiles/alice/follow"))
            "…issuing the very DELETE that was refused a moment ago — strictly
             after the POST it would otherwise have raced")))))

(defn- profile-follow-latch-does-not-leak-to-a-bystander-test []
  (with-held-profile-fx :realworld.test/profile-follow-latch-nav
    (fn [f lowered]
      (rf/dispatch-sync [:rf.route/handle-url-change "/profile/alice"] {:frame f})
      (settle-ok! f (:on-success (req-by-id @lowered [:profile/load "alice"]))
                  {:profile (full-profile "alice" false)})
      (rf/dispatch-sync [:profile/follow] {:frame f})
      (is (true? (pf-sub* f [:profile/follow-pending?])) "alice's toggle is latched")
      (let [a-follow (req-by-method+url @lowered :post "/profiles/alice/follow")]

        ;; The reader walks away while alice's POST is still out. Her latch goes
        ;; on holding ALICE — but it is keyed, so it must not disable BOB.
        (rf/dispatch-sync [:rf.route/handle-url-change "/profile/bob"] {:frame f})
        (settle-ok! f (:on-success (req-by-id @lowered [:profile/load "bob"]))
                    {:profile (full-profile "bob" false)})
        (is (= #{"alice"} (pf-slice* f :profile.follow-pending))
            "alice stays latched across the navigation")
        (is (false? (pf-sub* f [:profile/follow-pending?]))
            "…yet bob's button is LIVE: a latch is taken on a profile, not on
             the page, so it never disables a bystander")

        (rf/dispatch-sync [:profile/follow] {:frame f})
        (is (some? (req-by-method+url @lowered :post "/profiles/bob/follow"))
            "…so bob's own follow issues normally, alongside alice's")
        (is (= #{"alice" "bob"} (pf-slice* f :profile.follow-pending))
            "two mutations outstanding at once, each under its own username")

        ;; ALICE's settle now arrives with BOB's mutation still pending — an
        ;; older reply landing underneath a newer operation. It must release its
        ;; own latch and touch nothing else.
        (let [before (pf-slice* f :profile)]
          (settle-ok! f (:on-success a-follow) {:profile (full-profile "alice" true)})
          (is (= before (pf-slice* f :profile))
              "the off-screen alice success is refused by the correlation gate —
               bob's banner is untouched, name, bio and flag alike")
          (is (= #{"bob"} (pf-slice* f :profile.follow-pending))
              "…and it released ALICE only. Releasing is keyed too, not a side
               effect any settle may cause on whatever is latched")
          (is (true? (pf-sub* f [:profile/follow-pending?]))
              "…so bob's own in-flight mutation is still latched"))

        ;; The ROLLBACK branch releases exactly as narrowly. Walk on to carol,
        ;; latch her, and let bob's failure land underneath.
        (rf/dispatch-sync [:rf.route/handle-url-change "/profile/carol"] {:frame f})
        (settle-ok! f (:on-success (req-by-id @lowered [:profile/load "carol"]))
                    {:profile (full-profile "carol" false)})
        (rf/dispatch-sync [:profile/follow] {:frame f})
        (is (= #{"bob" "carol"} (pf-slice* f :profile.follow-pending))
            "bob's is still out; carol's joins it")
        (settle-fail! f (:on-failure (req-by-method+url @lowered :post
                                                        "/profiles/bob/follow")))
        (is (= #{"carol"} (pf-slice* f :profile.follow-pending))
            "bob's late ROLLBACK releases bob and leaves carol latched — a
             failure frees its own mutation exactly as a success does")
        (is (true? (pf-sub* f [:profile/follow-pending?]))
            "…so carol's button is still correctly disabled")))))

(deftest realworld-profile-page-cross-username
  (testing "cross-username banner/authored requests are independently
            deliverable; usernames ride both reply targets; a LATE alice
            success cannot overwrite the active bob page (rf2-8icg)"
    (profile-cross-username-late-success-is-refused-test))
  (testing "a LATE alice failure cannot strand bob's machine in :error, and
            bob's own success still renders :loaded; a CURRENT username's own
            failure IS accepted (the failure gate's non-vacuity control)
            (rf2-8icg)"
    (profile-cross-username-late-failure-is-refused-test))
  (testing "a username change resets the banner while a same-username re-load
            retains it as a refresh; the cross-TAB read guard keeps alice's
            still-loaded authored rows off bob's URL (rf2-8icg)"
    (profile-username-change-resets-and-cross-tab-read-guard-test))
  (testing "a held alice follow settles — success or rollback — cannot mutate
            bob's banner, while bob's own follow success and rollback are both
            accepted (rf2-8icg)"
    (profile-cross-username-follow-settles-are-refused-test))
  (testing "the SAME-profile Follow→Unfollow ordering hazard, which the
            username gate cannot see: the toggle is serialised, so a second
            intent issues no second request and there is no pair to reorder;
            both a success and a rollback release the latch (rf2-8icg)"
    (profile-follow-toggle-is-serialised-test))
  (testing "the latch OUTLIVES a walk away and back, because it belongs to the
            mutation rather than to the banner slice: alice → bob → alice with
            the POST still in flight comes back disabled, so the newer Unfollow
            the older POST would have overwritten is never issued (rf2-8icg)"
    (profile-follow-latch-survives-a-walk-away-and-back-test))
  (testing "the latch is KEYED, so it never leaks onto a bystander: bob's
            button stays live while alice's mutation is out, two profiles can
            be latched at once, and an older settle — success or rollback —
            releases its own username alone (rf2-8icg)"
    (profile-follow-latch-does-not-leak-to-a-bystander-test)))

;; ============================================================================
;; http — failure->message + pure pagination/query helpers
;; ============================================================================
;;
;; The failure projector, query encoding, and page arithmetic are transport-
;; neutral Conduit contract, extracted to `realworld-shared.http` (rf2-fhxwhj)
;; and pinned ONCE in realworld_shared_contract_cljs_test.cljs — no longer
;; duplicated per app. This app retains only the integration assertion above
;; (`paginate-path-integration-test`) proving its request builder threads that
;; shared contract through.

;; ============================================================================
;; THE PRODUCTION-SEAM RECEIPT — write, then load, against the demo backend the
;; served app runs on (rf2-9n43e part B, rf2-k5lbd)
;; ============================================================================
;;
;; Every stub above is a canned reply the test chose, which is the right tool
;; for pinning what a handler does with a given reply and the wrong one for the
;; claim the README makes: that a comment you post is still there when the page
;; re-reads. rf2-9n43e found the shipped backend answering every later GET out
;; of a frozen seed, and no test here could see it, because no test here let
;; the backend answer.
;;
;; This receipt chooses nothing. The frame is wired the way `realworld.core/
;; mount!` wires the served app (`:fx-overrides {:rf.http/managed
;; :realworld.demo/http-stub}`), so the article page's own public events — the
;; route's `:comments/load`, `:comment-form/submit`, and a later `:comments/load`
;; — all go to the app's own `demo-state` world through the shared demo backend,
;; and each reply comes back through the backend's own deferred path
;; (`:after-ms` → `:dispatch-later`, a real 20 ms later). The test WAITS for the
;; slice to settle rather than settling it.
;;
;; Async, and the fixture above is `:async? true` for this row: `cljs.test` runs
;; an `(async done …)` body only under a map fixture, and the deferred reply is
;; unreachable from a synchronous body (its first hop is an async router
;; dispatch). `with-new-frame` is deliberately NOT used — it destroys the frame
;; when the body RETURNS, before anything has settled — so the frame is a plain
;; anon record and every dispatch names it.

(def ^:private demo-user
  "The demo world's one user, as `POST /users/login` issues them — the identity
   the backend stamps on every write."
  {:email "demo@conduit.dev" :token "stub.demo.jwt" :username "demo"
   :bio "Canned demo user." :image ""})

(defn- backend-comments
  "What the demo backend would answer `GET /articles/<slug>/comments` with RIGHT
   NOW — a pure read of the app's own world, no frame involved. This is the
   server truth the receipt compares the settled slice against."
  [slug]
  (:comments
    (:ok (second (demo/transition @rh/demo-state
                                  {:request {:method :get
                                             :url    (rh/full-url
                                                       (str "/articles/" slug "/comments"))}})))))

(deftest realworld-production-seam-receipt-a-comment-survives-a-later-load
  (testing "examples/real-apps/realworld_http — against the app's PRODUCTION demo
            backend, with no canned reply anywhere: the article route's own
            :comments/load settles from the backend; :comment-form/submit POSTs
            through the same seam and the saved comment replaces the optimistic
            card; a LATER :comments/load through the normal public event still
            has it — the write survived a later normal read"
    (async done
      ;; The documented reset boundary: this receipt's world, and nobody else's.
      (reset! rh/demo-state (demo/fresh-state))
      (let [f        (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides   {:rf.http/managed :realworld.demo/http-stub}})
            slug     "hello-conduit"
            comments #(rf/compute-sub [:comments/data] (rf/frame-state-value f))
            status   #(rf/compute-sub [:comments/status] (rf/frame-state-value f))]
        (rf/dispatch-sync [:article/initialise] {:frame f})
        (rf/dispatch-sync [:comments/initialise] {:frame f})
        (rf/dispatch-sync [:comment-form/initialise] {:frame f})
        (rf/dispatch-sync [:auth/store-session demo-user] {:frame f})
        ;; The article route's `:on-match` fires `:article/load` + `:comments/load`.
        (rf/dispatch-sync [:rf.route/handle-url-change (str "/article/" slug)] {:frame f})
        (is (= :loading (status))
            "the route's own :comments/load is in flight against the demo backend")
        (-> (test-support/poll-until
              #(= :loaded (status))
              {:label "the route's comments load settles from the demo backend"})
            (.then (fn [_]
                     (is (= [1] (mapv :id (comments)))
                         "the first load is the backend's one seeded comment — nothing canned")
                     (is (= (backend-comments slug) (comments))
                         "…and equal to what the backend answers that GET with right now")
                     ;; The page's own form: optimistic card now, POST through the seam.
                     (rf/dispatch-sync [:comment-form/edit-field :body "great read"] {:frame f})
                     (rf/dispatch-sync [:comment-form/submit] {:frame f})
                     (is (= 2 (count (comments)))
                         "the optimistic temp card is on screen before the backend answers")
                     (is (str/starts-with? (str (:id (second (comments)))) "temp-")
                         "…under its recordable temp-id")
                     (test-support/poll-until
                       #(= 1000 (:id (second (comments))))
                       {:label "the POST settles from the demo backend and the saved comment replaces the temp card"})))
            (.then (fn [_]
                     (is (= "" (:body (rf/compute-sub [:comment-form/draft] (rf/frame-state-value f))))
                         "the form reset on save")
                     ;; A LATER LOAD through the normal public event — the page re-reading.
                     (rf/dispatch-sync [:comments/load] {:frame f})
                     (is (= :fetching (status))
                         "a same-slug re-load keeps the list up while it refreshes")
                     (test-support/poll-until
                       #(= :loaded (status))
                       {:label "the later load settles from the demo backend"})))
            (.then (fn [_]
                     (is (= [1 1000] (mapv :id (comments)))
                         "the later load still has the comment just written — the write survived a later normal read, and the id is the backend's deterministic one")
                     (is (= "great read" (:body (second (comments))))
                         "the body is the one the form submitted")
                     (is (= "demo" (-> (comments) second :author :username))
                         "the backend stamped the world's one user as the author")
                     (is (= (backend-comments slug) (comments))
                         "the slice is at the backend's CURRENT truth — the later read consulted the state the write landed in, not a seed")
                     (is (= 2 (count (get-in @rh/demo-state [:comments slug])))
                         "the write landed in this app's own world")))
            ;; Report and release; `done` runs once, in the one trailing step.
            (.catch (fn [e]
                      (is false (str "production-seam receipt did not settle: " (.-message e)))
                      nil))
            (.then (fn [_] (done))))))))
