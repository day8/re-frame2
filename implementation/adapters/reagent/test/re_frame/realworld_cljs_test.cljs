(ns re-frame.realworld-cljs-test
  "Integration test: drives the realworld (Conduit) example's headless
   test fixtures (rf2-4v73). Each fixture spins a fresh frame via
   `make-frame`, drives a feature flow with a canned :http stub, and
   asserts the resulting app-db / sub state.

   The fixtures live under examples/reagent/realworld/test/realworld/ — extracted
   from the production realworld source under rf2-4v73 so the example's
   .cljs files are now test-free.

   Per rf2-am9d this ns uses snapshot/restore via re-frame.test-support
   so the contract is uniform across CLJS fixtures: the snapshot captures
   the realworld example's ns-load registrations, and the restore on the
   way out leaves them intact for any subsequent test ns."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures]]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            [realworld.core]
            [realworld.auth-test :as auth-t]
            [realworld.articles-test :as articles-t]
            [realworld.article-editor-test :as editor-t]
            [realworld.comments-test :as comments-t]
            [realworld.favorites-test :as favorites-t]
            [realworld.profile-test :as profile-t]
            [realworld.settings-test :as settings-t]
            [realworld.tags-test :as tags-t]
            [realworld.routing-test :as routing-t]
            [realworld.ssr-test :as ssr-t]
            [realworld.core-test :as core-t]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; Each fixture is a plain `defn` (preserves original signature; the
;; example used to call them directly from the REPL). Failures throw via
;; `assert`, which surfaces as a test failure under cljs.test.

(deftest realworld-auth-flow
  (testing "login happy path drives the auth machine to :authed and back"
    (auth-t/login-happy-path-test))
  (testing "login failure surfaces error and dismiss returns to :idle"
    (auth-t/login-failure-test)))

(deftest realworld-articles-feed
  (testing "global feed loads and re-loads bumping :attempt"
    (articles-t/articles-load-test))
  (testing "global feed surfaces :error on http failure"
    (articles-t/articles-load-failure-test)))

(deftest realworld-article-editor
  (testing "editor create flow saves and clears :dirty?"
    (editor-t/editor-create-test))
  (testing "editor :can-leave? blocks once the draft diverges"
    (editor-t/editor-can-leave-test)))

(deftest realworld-comments
  (testing "article + comments load on route change"
    (comments-t/comments-load-test))
  (testing "comment submit clears the form and appends to the list"
    (comments-t/comment-submit-test)))

(deftest realworld-favorites
  (testing "favorite toggle rolls back on :http failure"
    (favorites-t/favorite-toggle-test)))

(deftest realworld-profile
  (testing "profile + authored-articles populate from canned stub"
    (profile-t/profile-load-test)))

(deftest realworld-settings
  (testing "settings save propagates the new bio into auth/user"
    (settings-t/settings-test)))

(deftest realworld-tags
  (testing "tag filter and feed-kind round-trip via :rf.route/query"
    (tags-t/tag-query-test))
  (testing ":realworld/tags machine — load happy path (rf2-0i4y)"
    (tags-t/tags-machine-load-test))
  (testing ":realworld/tags machine — failure path lands in :error (rf2-0i4y)"
    (tags-t/tags-machine-failure-test)))

(deftest realworld-routing
  (testing "navigate, handle-url-change, query, and not-found all resolve"
    (routing-t/routing-tests)))

(deftest realworld-ssr
  (testing "hydration-payload selects the SSR-safe slice keys"
    (ssr-t/hydration-payload-test)))

(deftest realworld-core-smoke
  (testing "app boot populates :auth, :articles, and :tags slices"
    (core-t/app-smoke-test)))
