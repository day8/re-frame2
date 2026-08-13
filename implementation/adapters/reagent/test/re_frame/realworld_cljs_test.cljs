(ns re-frame.realworld-cljs-test
  "Integration test: drives the realworld (Conduit) example (rf2-4v73)
   feature by feature. Each helper spins a fresh frame via `make-frame`,
   drives a feature flow with a canned :rf.http/managed stub, and asserts
   the resulting app-db / sub state.

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
            [cljs.test :refer-macros [deftest testing use-fixtures is]]
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
            [realworld-http.ssr :as ssr])
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


;; rf2-h1vqa4 BUNDLE CO-LOAD HYGIENE: this app registers the reserved
;; per-app `:rf.route/not-found` route at ns load. Co-loaded example apps
;; each do the same, and two provenance rows for the id fail default-image
;; assembly loud for every suite whose fixture baseline is captured after
;; the second app loads. Sequester OUR app's row at ns load; the fixture
;; init reinstates it (registrar + source store in lockstep) for this
;; suite's own tests.
(def ^:private not-found-route-row
  (test-support/sequester-app-registration!
    :route :rf.route/not-found "realworld-http.routing"))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    ;; EP-0002 (rf2-9o48ih): each helper spins its OWN top-level frame via
    ;; `make-frame`; opt out of the ambient `:rf/default` scope so the new
    ;; frame's `:initial-events` drain synchronously (top-level boot) rather than
    ;; being treated as a mid-cascade child-frame creation. In-body dispatches
    ;; carry explicit `{:frame f}` or run inside the `with-new-frame` scope.
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil
     ;; rf2-h1vqa4: reinstate the sequestered per-app not-found route for
     ;; this suite's own tests (see the sequester def above).
     :init-fn       (fn []
                      ;; rf2-h1vqa4: the realworld-http tree is sequestered at
                      ;; the sibling resources suite's ns load (bundle co-load
                      ;; hygiene), which runs before ANY test — reinstate the
                      ;; whole app tree for THIS suite's tests (registrar +
                      ;; source store in lockstep).
                      (test-support/reinstate-app-namespaces! "realworld-http.")
                      (test-support/reinstate-app-registration!
                        not-found-route-row))}))

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
    ;; Seed a single comment (the list is now length 1).
    (rf/dispatch-sync
      [:comments/loaded
       {:value {:comments [{:id 7 :body "survivor"
                            :author {:username "eve"}}]}}]
      {:frame f})
    (is (= 1 (count (rf/compute-sub [:comments/data] (rf/frame-state-value f)))))

    ;; A DELETE for a comment that WAS at index 3 in a since-shrunk list
    ;; fails. The captured prior carries the stale index 3 against the
    ;; current length-1 list. Before the clamp this threw on `subvec`.
    (rf/dispatch-sync
      [:comment/delete-rollback {:index 3 :comment {:id 9 :body "rolled-back"
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
    (rf/dispatch-sync [:article/author-follow-rollback false {:kind :rf.http/http-4xx}] {:frame f})
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
    (rf/dispatch-sync [:article/delete] {:frame f})
    (is (= :realworld/home (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "a successful detail-page delete navigates home")
    ;; The failure branch (driven directly): a readable error lands on the slice.
    (rf/dispatch-sync [:article/delete-failed {:error {:kind :rf.http/http-5xx :status 500}}] {:frame f})
    (is (some? (rf/compute-sub [:article/error] (rf/frame-state-value f)))
        ":article/delete-failed surfaces a readable error message")))

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
    (rf/dispatch-sync [:profile/follow-rollback true {:kind :rf.http/http-4xx}] {:frame f})
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
;; http — failure->message + pure pagination/query helpers
;; ============================================================================
;;
;; The failure projector, query encoding, and page arithmetic are transport-
;; neutral Conduit contract, extracted to `realworld-shared.http` (rf2-fhxwhj)
;; and pinned ONCE in realworld_shared_contract_cljs_test.cljs — no longer
;; duplicated per app. This app retains only the integration assertion above
;; (`paginate-path-integration-test`) proving its request builder threads that
;; shared contract through.
