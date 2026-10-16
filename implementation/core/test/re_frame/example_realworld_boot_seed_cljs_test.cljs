(ns re-frame.example-realworld-boot-seed-cljs-test
  "RealWorld-on-managed-HTTP boots under its own app-db schema registry.

   Each slice is seeded by its own feature's `:*/initialise`, so each seed is a
   separate commit. The candidate validator checks a registered path only
   where the candidate has it (Spec 010 §When schemas are checked), so a seed
   is never checked against a sibling it has not written yet. Only a
   development build validates, since `validate-app-schema!` is gated on
   `debug-enabled?`.

   The example registers its registry at ns-load from `realworld-http.schema`,
   a top-level program registration the frame under test resolves. The suite
   names the app with `:app-ns`, so its own tests run with the app's rows and
   no other suite's baseline carries them. It requires the feature nses only,
   never `realworld-http.core`, which would register routes into the shared
   node-test registrar; the boot fan-out `:app/initialise` performs is spelled
   out below."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.schemas]
            ;; The CLJS default validator soft-passes without this, which would
            ;; make the boot assertion below vacuous.
            [re-frame.schemas.malli]
            [re-frame.machines]
            [re-frame.http.managed]
            [re-frame.http.test-support]
            [realworld-http.http]
            [realworld-http.schema]
            [realworld-http.auth]
            [realworld-http.articles]
            [realworld-http.comments]
            [realworld-http.article-editor]
            [realworld-http.profile]
            [realworld-http.favorites]
            [realworld-http.tags])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :app-ns  "realworld-http."}))

(defn- boot!
  "The app's boot sequence, one dispatch per event. `:auth/initialise` runs
  first (its own `:initial-events` step) and creates `[:auth]` before the two
  form initialisers write inside it."
  [f]
  ;; Node has no localStorage, so the boot read finds no saved session.
  (rf/dispatch-sync [:auth/initialise] {:frame f})
  (doseq [ev [[:articles/initialise]
              [:article/initialise]
              [:comments/initialise]
              [:comment-form/initialise]
              [:editor/initialise]
              [:profile/initialise]
              [:feed/initialise]
              [:tags/initialise]
              [:auth.login-form/initialise]
              [:auth.register-form/initialise]]]
    (rf/dispatch-sync ev {:frame f})))

(deftest realworld-boots-under-its-own-app-db-schemas
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (boot! f)
    (let [db (rf/app-db-value f)]
      (is (= [] (remove #(contains? db %)
                        [:auth :articles :article :comments :comment-form
                         :profile :profile.articles :profile.favorites :feed :editor]))
          "every top-level slice the boot seeds landed")
      (is (= [] (remove #(contains? (:auth db) %) [:login-form :register-form]))
          "both form slices under [:auth] landed"))))
