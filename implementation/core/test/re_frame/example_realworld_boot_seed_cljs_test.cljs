(ns re-frame.example-realworld-boot-seed-cljs-test
  "THE BOOT WINDOW: an app whose slices are seeded by SEPARATE events must
   boot under its own app-db schema registry.

   THE MECHANISM. `examples/real-apps/realworld_http/schema.cljs` registers
   the app's app-db path schemas against `:rf/default` at ns-load. app-db
   starts `{}`, and each slice is seeded by its OWN feature's `:*/initialise`,
   fanned out from `:app/initialise` — so each seed is a SEPARATE commit. The
   candidate validator walks EVERY registered path over the WHOLE candidate
   app-db at EVERY `:db` commit (`(get-in db path)`, Spec 010 §Per-step
   recovery row 4) — there is no exemption for a path nothing has written
   yet, and an unwritten path reads `nil`. So a registry whose slice schemas
   refused `nil` would reject the FIRST seed on its still-absent siblings,
   and every later seed the same way, and app-db would never leave `{}`.
   Because a rejected candidate also does NOT walk `:fx`, the
   `[:rf.http/managed …]` effects would never fire either — presenting as
   \"the demo backend's replies never settle\" when no request was ever
   issued. Every screen would render empty (the Global Feed's \"No articles
   are here... yet.\", article detail's \"No article loaded.\", profile's \"No
   profile loaded.\", sign-in anonymous) with no page error, no console error
   and no failed request.

   WHY DEV ONLY. `re-frame.schemas.validate/validate-app-schema!` puts its whole
   body inside `(if interop/debug-enabled? … true)`, so a production build
   returns `true` without walking anything and every candidate installs. Dev and
   release therefore disagree about whether such an app can boot at all.

   WHY THE POPULAR-TAGS SIDEBAR IS UNAFFECTED. The tags lifecycle lives
   ENTIRELY in the `:realworld/tags` state machine, whose snapshot is runtime-db
   and is validated by the machine-data boundary against the machine's own
   `[:schemas :data]` — a different partition, a different validator, and one
   whose `:data` the machine spec seeds itself. `:tags/load` / `:tags/loaded`
   return only `:fx`, never `:db`, so the app-db validator never runs for them.
   The one panel on the page that never touches app-db is the one a rejected
   boot would leave working.

   WHY THE HARNESS INSTALLS THE REGISTRY. `reg-app-schemas` is FRAME-LOCAL,
   and the example registers against `:rf/default`. A test driving the
   example in an anonymous frame carries no app-db schemas at all — so the
   registry that governs the real app would be inert in the harness. This ns
   registers the example's OWN registry against the frame under test.

   Feature nses only, never `realworld-http.core` — requiring core would pull
   `routing.cljs` and register routes into the shared node-test registrar (the
   same rule the sibling password-classification ns states). `:app/initialise`
   lives in core, so the boot fan-out it performs is spelled out below."
  (:require [cljs.test :refer-macros [deftest testing is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.schemas]
            [re-frame.machines]
            [re-frame.http.managed]
            [re-frame.http.test-support]
            ;; managed-HTTP app FEATURE nses (no core -> no routes)
            [realworld-http.http]
            [realworld-http.schema :as app-schema]
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
    {:adapter rf.adapter.reagent/adapter}))

;; ---------------------------------------------------------------------------
;; The example's own boot fan-out, spelled out.
;;
;; `:app/initialise` (core.cljs) dispatches these; `:auth/initialise` runs ahead
;; of it as its own `:initial-events` step, because it consumes the recordable
;; `:auth.session/token` coeffect. The ORDER is the app's, and it matters: it is
;; `:auth/initialise` that creates `[:auth]` in the AuthSlice shape, before the
;; two form initialisers write inside it.
;; ---------------------------------------------------------------------------

(defn- boot!
  "Drive the example's boot sequence into frame `f`, one dispatch per event
  exactly as the app does — so each `:db`-bearing initialiser is its own
  commit, which is the whole point of the test."
  [f]
  (rf/dispatch-sync [:auth/initialise] {:frame   f
                                        :rf.cofx {:auth.session/token nil}})
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
  (testing "every slice the example seeds is present after boot, with the
            example's OWN app-db schema registry installed on the frame"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      ;; The registry the real app installs on `:rf/default`. Registering it
      ;; here is what makes this frame behave like the running application
      ;; rather than like a bare harness frame.
      (rf/reg-app-schemas app-schema/app-db-schemas {:frame f})
      (boot! f)
      (let [db (rf/app-db-value f)]
        (is (seq db)
            "app-db is not empty — the first seed was not rejected by its
             not-yet-seeded siblings (the boot-window rollback loop)")
        (doseq [k [:auth :articles :article :comments :comment-form
                   :profile :profile.articles :profile.favorites :feed :editor]]
          (is (contains? db k)
              (str "app-db carries the " k " slice after boot")))
        (is (contains? (get db :auth) :login-form)
            "app-db carries the login-form slice after boot")
        (is (contains? (get db :auth) :register-form)
            "app-db carries the register-form slice after boot")
        (is (= :idle (get-in db [:articles :status]))
            "the articles slice really is the seeded idle shape")))))

(deftest a-load-after-boot-commits-its-db
  (testing ":articles/load commits its :db — the half a rejected candidate
            loses along with its :fx, so a rejected boot issues no request"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf/reg-app-schemas app-schema/app-db-schemas {:frame f})
      (boot! f)
      (rf/dispatch-sync [:articles/load] {:frame f})
      (is (= :loading (get-in (rf/app-db-value f) [:articles :status]))
          "the in-flight status committed (a rejected candidate would leave
           :idle, and would never have walked :fx to issue the request)"))))

;; ---------------------------------------------------------------------------
;; The framework contract underneath the boot window, pinned on its own.
;;
;; DEV-ONLY BEHAVIOUR. `validate-app-schema!` is gated on
;; `re-frame.interop/debug-enabled?`, so under `:advanced` + `goog.DEBUG=false`
;; the candidate installs instead and neither assertion below would hold. The
;; node-test build is a development build — which is the build a developer
;; meets, and the build in which a rejected boot shows.
;; ---------------------------------------------------------------------------

(def ^:private Slot [:map [:n :int]])

(rf/reg-event :test.boot-window/seed-a
  (fn [{:keys [db]} _] {:db (assoc db :slot-a {:n 1})}))

(deftest a-sibling-unseeded-path-rejects-the-whole-candidate
  (testing "an unwritten registered path reads nil and rejects a commit that
            never touched it — the boot-window mechanism"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf/reg-app-schemas {[:slot-a] Slot
                           [:slot-b] Slot}
                          {:frame f})
      (rf/dispatch-sync [:test.boot-window/seed-a] {:frame f})
      (is (nil? (get (rf/app-db-value f) :slot-a))
          "the :slot-a write was REJECTED because :slot-b, which this event
           never touched, is still absent (and validation IS live here — a
           soft-passing validator would have let the write land)")))
  (testing "the same write lands once the unseeded sibling tolerates absence"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf/reg-app-schemas {[:slot-a] Slot
                           [:slot-b] [:maybe Slot]}
                          {:frame f})
      (rf/dispatch-sync [:test.boot-window/seed-a] {:frame f})
      (is (= {:n 1} (get (rf/app-db-value f) :slot-a))
          "with :maybe on the not-yet-seeded sibling the candidate installs"))))
