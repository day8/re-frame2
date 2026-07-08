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
            [re-frame.views]
            [re-frame.http.test-support]
            [realworld-http.core]
            ;; Loaded for its ns-load side effects: registers the routes (with
            ;; the `:can-enter [:realworld.routing/authed?]` auth gate on the
            ;; `:requires-auth` routes), the `:realworld.routing/authed?` guard
            ;; sub, and the `:rf.route/entry-blocked` redirect handler the
            ;; auth-gate tests exercise (rf2-p69yaz).
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
  (:require-macros [re-frame.core :refer [with-new-frame]]))

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
     :ambient-frame nil}))

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
    ;; EP-0017 (rf2-16ck78): `:auth/initialise` consumes the RECORDABLE+PROVIDED
    ;; `:auth.session/token` coeffect — its value rides the dispatch token,
    ;; supplied here exactly as `realworld.core/run` supplies it at the boundary
    ;; (node-side localStorage is absent, so the token is nil). Dispatched
    ;; explicitly (rather than via `:initial-events`).
    (rf/dispatch-sync [:auth/initialise]
                      {:frame f :rf.cofx {:auth.session/token nil}})
    (is (= :idle (rf/compute-sub [:auth/state] (rf/frame-state-value f))))

    (rf/dispatch-sync [:auth/flow [:auth/login {:email "alice@example.com"
                                                :password "correct-horse"}]]
                      {:frame f})
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
    ;; EP-0017 (rf2-16ck78): supply the RECORDABLE+PROVIDED `:auth.session/token`
    ;; on the boot dispatch token (nil node-side), mirroring `realworld.core/run`.
    (rf/dispatch-sync [:auth/initialise]
                      {:frame f :rf.cofx {:auth.session/token nil}})
    (rf/dispatch-sync [:auth/flow [:auth/login {:email "x@y.z" :password "wrong"}]]
                      {:frame f})
    (is (= :error (rf/compute-sub [:auth/state] (rf/frame-state-value f))))
    (is (some? (rf/compute-sub [:auth/error] (rf/frame-state-value f))))

    (rf/dispatch-sync [:auth/flow [:auth/dismiss]] {:frame f})
    (is (= :idle (rf/compute-sub [:auth/state] (rf/frame-state-value f))))))

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
    (rf/dispatch-sync [:rf.route/navigate :realworld.article/show {:slug "hello"}] {:frame f})
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
  ;; The auth gate is the framework's `:can-enter` guard (rf2-p69yaz) — each
  ;; `:requires-auth` route declares `:can-enter [:realworld.routing/authed?]`
  ;; and an `:rf.route/entry-blocked` handler redirects to login + stashes the
  ;; bounce-back (routing.cljs). No frame `:interceptors` — the one navigation
  ;; gate runs the guard on every door. The route + handler + guard sub are all
  ;; registered at `realworld.routing` / `realworld.auth` ns-load (required
  ;; below).
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    ;; Unauthenticated: navigating to a :requires-auth route
    ;; (:realworld.user/settings) is refused by :can-enter → :rf.route/entry-blocked
    ;; redirects to :realworld.auth/login.
    (rf/dispatch-sync [:rf.route/navigate :realworld.user/settings {}] {:frame f})
    (is (= :realworld.auth/login (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "unauthenticated nav to a :requires-auth route redirects to login")

    ;; A non-guarded route is unaffected.
    (rf/dispatch-sync [:rf.route/navigate :realworld/home {}] {:frame f})
    (is (= :realworld/home (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "unguarded route navigates normally with the gate active")

    ;; Bounce-back stash: the entry-blocked redirect records the original target
    ;; at [:auth :return-to].
    (rf/dispatch-sync [:rf.route/navigate :realworld.user/settings {}] {:frame f})
    (is (= {:id :realworld.user/settings :params {}}
           (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        "the redirect stashes the original target for post-login bounce-back")

    ;; Authenticated: the same guarded nav now proceeds (:can-enter passes).
    (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
    (rf/dispatch-sync [:rf.route/navigate :realworld.user/settings {}] {:frame f})
    (is (= :realworld.user/settings (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "authenticated nav to a :requires-auth route proceeds")

    ;; The post-login bounce-back event consumes the stashed target and
    ;; clears the slot.
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
    (is (= {:id :realworld.user/settings :params {}}
           (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        "the direct-URL redirect stashes the original target for bounce-back")

    ;; Editor route via direct URL with path params is also gated, and
    ;; the stash carries the params (resolved off the requested URL).
    (rf/dispatch-sync [:rf.route/handle-url-change "/editor/my-slug"] {:frame f})
    (is (= :realworld.auth/login (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "logged-out direct-URL to a :requires-auth editor route redirects to login")
    (is (= {:id :realworld.editor/edit :params {:slug "my-slug"}}
           (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        "the editor direct-URL redirect stashes id + params for bounce-back")

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
    (is (= {:id :realworld.user/settings :params {}}
           (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        "the anchor redirect stashes the original target for bounce-back")

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

(defn- pagination-helpers-test []
  ;; page->offset: 1-indexed UI page → 0-based wire offset, sub-1 clamped to 1.
  (is (= 0  (rh/page->offset nil)) "nil page clamps to page 1 → offset 0")
  (is (= 0  (rh/page->offset 0))   "page 0 is not a thing → clamps to offset 0")
  (is (= 0  (rh/page->offset -5))  "a negative page clamps up to page 1")
  (is (= 0  (rh/page->offset 1))   "page 1 → offset 0")
  (is (= 10 (rh/page->offset 2))   "page 2 → offset one page-size in")
  (is (= 20 (rh/page->offset 3))   "page 3 → offset two page-sizes in")

  ;; page-count: ceil(count / page-size), floored at 1 (an empty list is 1 page).
  (is (= 1 (rh/page-count nil)) "nil count → 1 page")
  (is (= 1 (rh/page-count 0))   "empty list is still one (empty) page")
  (is (= 1 (rh/page-count 10))  "exactly one full page → 1")
  (is (= 2 (rh/page-count 11))  "one over a full page → 2 pages")
  (is (= 3 (rh/page-count 25))  "25 items at page-size 10 → 3 pages")

  ;; query-string: nils dropped, empty → "" (never a bare "?"), values encoded.
  (is (= "" (rh/query-string {})) "empty map yields \"\", not \"?\"")
  (is (= "" (rh/query-string {:tag nil})) "an all-nil map still yields \"\"")
  (is (= "?author=jake" (rh/query-string {:tag nil :author "jake"}))
      "nil-valued params are dropped; the surviving one is emitted")
  (is (= "?tag=a%20b" (rh/query-string {:tag "a b"}))
      "a space is URL-encoded (not left raw)")
  (is (= "?tag=a%26b%3Dc%23d" (rh/query-string {:tag "a&b=c#d"}))
      "reserved query characters (& = #) are percent-encoded so they can't corrupt the query")

  ;; paginate-path: path + optional filters + the limit/offset window for a page.
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
    (rf/dispatch-sync [:rf.route/navigate :realworld.profile/show {:username "eve"}] {:frame f})
    (rf/dispatch-sync [:profile/show-page 2] {:frame f})
    (is (= :realworld.profile/show (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "profile page-nav stays on the same (authored) tab")
    (is (= "eve" (:username (rf/compute-sub [:rf.route/params] (rf/frame-state-value f))))
        "the profile username is unchanged")
    (is (= 2 (:page (rf/compute-sub [:rf.route/query] (rf/frame-state-value f)))))

    ;; The favorites tab pages independently, still on its own route.
    (rf/dispatch-sync [:rf.route/navigate :realworld.profile/favorites {:username "eve"}] {:frame f})
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
    (rf/dispatch-sync [:auth/flow [:auth/login {:email "alice@example.com" :password "x"}]]
                      {:frame f})
    (is (= :authed (rf/compute-sub [:auth/state] (rf/frame-state-value f))))
    (is (= :realworld/home (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "interactive login bounces home via :store-session → :auth/post-login-redirect")))

;; ============================================================================
;; core — top-level smoke: boots the app, checks per-feature initialisers
;; populate the expected slices.
;; ============================================================================

(defn- app-smoke-test []
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed      :realworld.test/canned-success-empty
                                                :auth.session/persist :rf/no-op}})]
    ;; EP-0017 (rf2-16ck78): `:auth/initialise` is no longer in the
    ;; `:app/initialise` fan-out — it consumes the RECORDABLE+PROVIDED
    ;; `:auth.session/token` coeffect, which `realworld.core/run` supplies on a
    ;; dedicated boundary dispatch (the `:dispatch` fx does not forward
    ;; `:rf.cofx`). Mirror that boundary dispatch here so the :auth slice is
    ;; seeded (node-side token is nil).
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
    (session-token-cofx-shape-test)))

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
    (auth-guard-all-access-paths-test)))

(deftest realworld-ssr
  (testing "hydration-payload selects the SSR-safe slice keys"
    (hydration-payload-test)))

(deftest realworld-pagination
  (testing "pure helpers: page->offset / page-count / query-string / paginate-path edges (rf2-yt7ay6)"
    (pagination-helpers-test))
  (testing "page-nav events carry the active feed / tag / route forward (rf2-yt7ay6)"
    (pagination-nav-events-test)))

(deftest realworld-session-restore
  (testing "restore-with-token reaches :authed, stores the session, and does NOT navigate (rf2-svj926)"
    (session-restore-with-token-test)))

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
    (editor-invalid-submit-test)))

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
;; http — failure->message multi-branch projection (rf2-xm57ne)
;; ============================================================================
;;
;; The failure projector was untested in BOTH realworld apps. This pins the
;; realworld_http side (the realworld_resources sibling is pinned in
;; realworld_resources_cljs_test.cljs): the {:errors {:body [...]}} extraction,
;; the {:errors {field [...]}} projection, a raw string body, and every
;; category fallback keyed off the closed :rf.http/* taxonomy.

(defn- http-failure->message-test []
  (is (= "email or password is invalid"
         (rh/failure->message {:kind :rf.http/http-4xx :status 422
                               :body {:errors {:body ["email or password is invalid"]}}}))
      "{:errors {:body [...]}} surfaces the server's first body message")
  (is (= "email: is taken"
         (rh/failure->message {:kind :rf.http/http-4xx :status 422
                               :body {:errors {:email ["is taken"]}}}))
      "a keyed {:errors {field [...]}} projects as \"field: message\"")
  (is (= "raw upstream text"
         (rh/failure->message {:kind :rf.http/http-5xx :status 502 :body "raw upstream text"}))
      "a string body is surfaced verbatim")
  (is (= "Network error — please try again." (rh/failure->message {:kind :rf.http/transport})))
  (is (= "Request timed out." (rh/failure->message {:kind :rf.http/timeout})))
  (is (= "Request rejected (status 404)." (rh/failure->message {:kind :rf.http/http-4xx :status 404})))
  (is (= "Server error (status 503)." (rh/failure->message {:kind :rf.http/http-5xx :status 503})))
  (is (= "Couldn't parse server response." (rh/failure->message {:kind :rf.http/decode-failure})))
  (is (= "custom detail" (rh/failure->message {:kind :rf.http/accept-failure :detail {:message "custom detail"}})))
  (is (= "Unexpected response shape." (rh/failure->message {:kind :rf.http/accept-failure})))
  (is (= "Request cancelled." (rh/failure->message {:kind :rf.http/aborted})))
  (is (= "spelled-out message" (rh/failure->message {:kind :some/unknown :message "spelled-out message"}))
      "an unknown kind falls back to the failure's own :message")
  (is (= "Request failed." (rh/failure->message {}))
      "a shapeless failure falls back to the final catch-all"))

(deftest realworld-http-failure-projection
  (testing "failure->message multi-branch projection (rf2-xm57ne)"
    (http-failure->message-test)))
