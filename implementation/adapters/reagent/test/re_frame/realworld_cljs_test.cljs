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
    (is (true? (rf/compute-sub [:rf/machine-has-tag? :ui/article-editor :mode/create] (rf/frame-state-value f))))
    (is (true? (rf/compute-sub [:rf/machine-has-tag? :ui/article-editor :lifecycle/idle] (rf/frame-state-value f))))
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
    (is (true? (rf/compute-sub [:rf/machine-has-tag? :ui/article-editor :lifecycle/saved] (rf/frame-state-value f))))
    (is (true? (rf/compute-sub [:rf/machine-has-tag? :ui/article-editor :mode/edit] (rf/frame-state-value f))))
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
   (browserless form of `rf/machine-has-tag?`)."
  [frame tag]
  (rf/compute-sub [:rf/machine-has-tag? :settings/form tag]
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
   (browserless form of `rf/machine-has-tag?`)."
  [frame tag]
  (rf/compute-sub [:rf/machine-has-tag? :realworld/tags tag]
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
  ;; anchor click (`:rf/url-requested`) — is refused too. These cases assert all
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

  ;; --- anchor click (`:rf/url-requested`) ---
  (with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:app/initialise]]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    ;; An anchor whose href targets a :requires-auth route. The
    ;; framework `rf/route-link` dispatches `:rf/url-requested` with the
    ;; resolved url; the :can-enter gate refuses the logged-out entry.
    (rf/dispatch-sync [:rf/url-requested {:url "/settings"
                                          :to  :realworld.user/settings}]
                      {:frame f})
    (is (= :realworld.auth/login (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "logged-out anchor click to a :requires-auth route redirects to login")
    (is (= {:id :realworld.user/settings :params {}}
           (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        "the anchor redirect stashes the original target for bounce-back")

    ;; A url-only request still gates via the URL-resolved target.
    (rf/dispatch-sync [:rf/url-requested {:url "/editor"}] {:frame f})
    (is (= :realworld.auth/login (rf/compute-sub [:rf.route/id] (rf/frame-state-value f)))
        "logged-out url-only anchor to a :requires-auth route redirects to login")

    ;; A non-auth anchor is unaffected.
    (rf/dispatch-sync [:rf/url-requested {:url "/profile/eve"
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
    (rf/dispatch-sync [:rf/url-requested {:url "/settings"
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
          rt (rf/runtime-db-value f)]
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

(deftest realworld-core-smoke
  (testing "app boot populates :auth, :articles, and :tags slices"
    (app-smoke-test)))
