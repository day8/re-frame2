(ns re-frame.example-realworld-resources-boot-seed-cljs-test
  "rf2-ghxt — THE BOOT WINDOW, RealWorld-on-resources: an app whose slices are
   seeded by SEPARATE events must still boot under its own app-db schema
   registry, and a slice that is absent BY DESIGN must not veto every commit
   the app will ever make.

   THE DEFECT. A development build of `:examples/realworld-resources` booted to
   nothing: `app-db` still `{}`, zero article cards, `#app` a bare shell. No page
   error, no console error, no failed request. The same bundle in a RELEASE build
   rendered correctly.

   THE MECHANISM. `examples/real-apps/realworld_resources/schema.cljs` registers
   four app-db path schemas against `:rf/default` at ns-load. app-db starts `{}`,
   and the candidate validator walks EVERY registered path over the WHOLE
   candidate app-db at EVERY `:db` commit (`(get-in db registered-path)` in
   `re-frame.schemas.validate/validate-app-schema!`, Spec 010 §Per-step recovery
   row 4). There is no exemption for a path nothing has written yet, and an
   unwritten path reads `nil` — which is not a `[:map …]`. Registered bare, the
   four paths therefore veto each other:

     [:auth]                seeded by `:auth/initialise` (its own `:initial-events`
                            step — it consumes a recordable token coeffect)
     [:auth :login-form]     seeded by `:auth.login-form/initialise`
     [:auth :register-form]  seeded by `:auth.register-form/initialise`
     [:settings-form]        NOT SEEDED AT BOOT AT ALL

   The first three are a boot WINDOW: each is its own dispatch (the two form
   initialisers are fanned out from `:app/initialise` in core.cljs), so each seed
   is its own commit and each is rejected by the siblings still absent.

   The fourth is worse, and is why this example failed harder than its
   managed-HTTP twin (rf2-2xzc). `[:settings-form]` is seeded by `:settings/load`
   on settings-ROUTE ENTRY, behind an auth guard — so for an anonymous visitor,
   and for a signed-in one who never opens Settings, it reads `nil` for the whole
   life of the app. Registered bare it rejects EVERY `:db` commit in the
   application, permanently, not only during boot. And because a rejected
   candidate does not walk `:fx` either, nothing downstream of a `:db`-bearing
   handler fires.

   THE FIX. Every entry wears a `:maybe`, and `schema.cljs` names the registry as
   the VALUE `app-db-schemas` so a harness can install it on its own frame. For
   the first three the `:maybe` buys exactly the window before that slice's seed
   lands; for `[:settings-form]` it is permanent, because absence there is the
   design rather than a window (seeding it at boot would only manufacture an
   empty draft from a user who is not signed in yet, to be overwritten on entry).

   WHY DEV ONLY. `validate-app-schema!` puts its whole body inside
   `(if interop/debug-enabled? … true)`, so a production build returns `true`
   without walking anything and every candidate installs. Dev and release
   therefore disagreed about whether this app could boot at all — and dev is the
   build every consumer develops against. The node-test build is a development
   build.

   WHY NOTHING IN THE SUITE SAW IT. `reg-app-schemas` is FRAME-LOCAL, and the
   example registers against `:rf/default`. The example's existing integration
   suite drives anonymous frames, which carry no app-db schemas at all, and
   re-registers `[:auth]` against its own frame BY HAND for the one regression
   that needs a live validator — so the registry that broke the real app was
   inert in every harness. This ns closes that gap by installing the example's
   OWN registry, by name, on the frame under test.

   THE VALIDATOR IS LIVE HERE, and that is load-bearing: a soft-passing validator
   would make every green below vacuous. `[[boot-under-the-shipped-registry]]` is
   paired with `[[boot-under-the-pre-fix-bare-registry]]`, which installs the
   bare four-entry map this file used to register and asserts the boot is
   REJECTED. The pair is differential — the shipped registry has to permit what
   the bare one refuses — so a validator that had gone quiet fails the second
   test rather than silently passing the first.

   Feature nses only, never `realworld-resources.core` — requiring core would
   pull `routing.cljs` and register the app's routes (including the reserved
   per-app `:rf.route/not-found`) into the shared node-test registrar.
   `:app/initialise` and `:auth/classify-token` live in core, so the boot
   fan-out they perform is spelled out in `boot!` below."
  (:require [cljs.test :refer-macros [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.schemas]
            ;; Activate the default Malli validator (rf2-t0hq). The CLJS default
            ;; validator SOFT-PASSES without this require, and a soft pass would
            ;; make the whole regression below unobservable — the bare registry
            ;; would boot just as happily as the shipped one. This is also the
            ;; canonical app-boot opt-in for Malli app-schema validation.
            [re-frame.schemas.malli]
            [re-frame.machines]
            ;; resources app FEATURE nses (no core -> no routes). `settings`
            ;; chains in `auth`, which chains in `http` / `scope` / `schema`.
            [realworld-resources.schema :as app-schema]
            [realworld-resources.auth]
            [realworld-resources.settings])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

;; rf2-h1vqa4 BUNDLE CO-LOAD HYGIENE. The two RealWorld apps deliberately share
;; id vocabulary — both register `:auth/initialise`, `:auth.login-form/initialise`,
;; `:auth.register-form/initialise` and `:settings/load` with DIFFERENT
;; implementations — and they are built and run as separate bundles, never
;; co-loaded. cljs.test loads every test ns into ONE bundle, so both apps' rows
;; sit in the shared source store, and two provenance rows for one id fail
;; default-image assembly loud (`:rf.error/image-duplicate-id`) for every suite
;; whose baseline is captured after the second app loads. So the sibling app's
;; tree is sequestered below, per test, and the realworld-http suites reinstate
;; their own tree in their fixture init.
;;
;; THE SEQUESTER CALL BELONGS IN `:init-fn`, NOT AT NS LOAD, and the difference
;; is not cosmetic. `sequester-app-namespaces!` captures the prefix's rows ONCE
;; and MEMOIZES them, scrubbing only that captured set on every later call. A
;; call at THIS ns's load runs while the bundle is still loading — this ns sorts
;; ahead of the two RealWorld integration suites, so `realworld-http.tags` (and
;; the rest of the app beyond the few feature nses loaded ahead of us) is not
;; there yet — and the incomplete capture is then the memo EVERY suite gets
;; afterwards. Measured: an ns-load call here left `:home/show-global-feed`
;; unsequestered and failed the sibling resources suite's own frame creation
;; with `:rf.error/image-duplicate-id`. By `:init-fn` time every test ns has
;; loaded, so the capture is complete whether it is ours or a sibling's.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn [] (rf.test-support/sequester-app-namespaces! "realworld-http."))}))

;; ---------------------------------------------------------------------------
;; The example's own boot fan-out, spelled out.
;;
;; The real app runs `:auth/classify-token`, `:auth/initialise` and
;; `:app/initialise` as `:initial-events` (core.cljs); `:app/initialise` then
;; fans out to the two form initialisers as SEPARATE dispatches. The two events
;; that live in core are not driveable from here (requiring core would register
;; the app's routes into the shared bundle), so what is driven below is the
;; three `:db`-bearing seeds, each its own dispatch — which is the whole point
;; of the regression: separate events mean separate commits.
;; ---------------------------------------------------------------------------

(defn- boot!
  "Drive the example's boot seeds into frame `f`, one dispatch per event exactly
  as the app does. The ORDER is the app's and it matters: `:auth/initialise`
  creates `[:auth]` in the AuthSlice shape before the two form initialisers
  write inside it."
  [f]
  (rf/dispatch-sync [:auth/initialise]
                    {:frame f :rf.cofx {:realworld-resources.session/token nil}})
  (rf/dispatch-sync [:auth.login-form/initialise] {:frame f})
  (rf/dispatch-sync [:auth.register-form/initialise] {:frame f}))

(def ^:private pre-fix-bare-registry
  "The four registrations EXACTLY as `schema.cljs` carried them before rf2-ghxt —
  bare, no `:maybe`. Kept here as the negative control: the shipped registry has
  to permit a boot this one refuses."
  {[:auth]                app-schema/AuthSlice
   [:auth :login-form]    app-schema/FormSlice
   [:auth :register-form] app-schema/FormSlice
   [:settings-form]       app-schema/FormSlice})

;; ---------------------------------------------------------------------------
;; Provenance guard.
;;
;; Every id this suite drives is registered by BOTH RealWorld apps. If the
;; sequester above ever stopped biting, these dispatches would silently exercise
;; the managed-HTTP app's handlers instead — which seed the same shaped slices,
;; so the assertions below would still pass while testing the wrong application.
;; That is a fail-OPEN, so it gets an assertion of its own rather than a comment.
;; ---------------------------------------------------------------------------

(deftest the-resources-app-owns-the-ids-this-suite-drives
  (testing "the live handlers for the ids both RealWorld apps register resolve to
            the RESOURCES app — otherwise every green below is about the wrong app"
    (doseq [id [:auth/initialise
                :auth.login-form/initialise
                :auth.register-form/initialise
                :auth/store-session
                :settings/load]]
      (let [provenance (some-> (rf.registrar/handler-meta :event id) :ns str)]
        (is (and (string? provenance)
                 (str/starts-with? provenance "realworld-resources."))
            (str "the live " id " handler comes from the resources app, not the "
                 "managed-HTTP twin (got " (pr-str provenance) ")"))))))

;; ---------------------------------------------------------------------------
;; The regression itself.
;; ---------------------------------------------------------------------------

(deftest boot-under-the-shipped-registry
  (testing "every slice the example seeds at boot is present afterwards, with the
            example's OWN app-db schema registry installed on the frame"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      ;; The registry the real app installs on `:rf/default`, by name. Installing
      ;; it here is what makes this frame behave like the running application
      ;; rather than like every previous harness frame.
      (rf/reg-app-schemas app-schema/app-db-schemas {:frame f})
      (boot! f)
      (let [db (rf/app-db-value f)]
        (is (seq db)
            "app-db is not empty — the first seed was not rejected by its
             not-yet-seeded siblings (the rf2-ghxt rollback loop)")
        (is (contains? db :auth)
            "app-db carries the auth slice after boot")
        (is (contains? (get db :auth) :login-form)
            "app-db carries the login-form draft after boot")
        (is (contains? (get db :auth) :register-form)
            "app-db carries the register-form draft after boot")
        (is (= {:email "" :password ""} (get-in db [:auth :login-form :draft]))
            "the login-form slice really is the seeded draft shape")))))

(deftest boot-under-the-pre-fix-bare-registry
  (testing "the bare registry this file used to carry REJECTS the very first seed
            — the regression, and the proof that the validator is live here (a
            soft-passing validator would let this boot too)"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf/reg-app-schemas pre-fix-bare-registry {:frame f})
      (boot! f)
      (is (= {} (rf/app-db-value f))
          "app-db never left {} — each seed was rolled back by the siblings still
           absent, which is exactly what the dev build did on screen"))))

(deftest a-commit-after-boot-still-lands-with-settings-form-never-seeded
  (testing "[:settings-form] is absent by design until the settings route is
            entered, and under the shipped registry that absence does not veto
            later commits — the way in which this example failed harder than its
            managed-HTTP twin"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf/reg-app-schemas app-schema/app-db-schemas {:frame f})
      (boot! f)
      (is (nil? (get (rf/app-db-value f) :settings-form))
          "nothing seeded [:settings-form] at boot, and nothing should have —
           :settings/load seeds it from the authenticated user on route entry")
      ;; An ordinary post-boot edit. Pre-fix this was rejected forever, because
      ;; [:settings-form] read nil at every commit for the whole life of the app.
      (rf/dispatch-sync [:auth.login-form/edit-field :email "alice@example.com"]
                        {:frame f})
      (is (= "alice@example.com" (get-in (rf/app-db-value f) [:auth :login-form :draft :email]))
          "an ordinary post-boot :db commit lands"))))

(deftest settings-load-seeds-a-slice-that-validates
  (testing ":settings/load seeds [:settings-form] from the authenticated user on
            route entry, and the seeded slice satisfies the FormSlice the shipped
            registry wraps — the `:maybe` widens the schema, it does not retire it"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf/reg-app-schemas app-schema/app-db-schemas {:frame f})
      (boot! f)
      ;; The settings route is auth-guarded, so there is always a user by the
      ;; time :settings/load runs. Establish one the way the app does.
      (rf/dispatch-sync [:auth/store-session {:email "alice@example.com"
                                              :username "alice"
                                              :token "jwt-abc"
                                              :bio nil :image nil}]
                        {:frame f})
      (rf/dispatch-sync [:settings/load] {:frame f})
      (let [slice (get (rf/app-db-value f) :settings-form)]
        (is (some? slice)
            "the settings draft is seeded on route entry")
        (is (= "alice" (get-in slice [:draft :username]))
            "the draft is seeded FROM the authenticated user")
        (is (contains? slice :touched)
            "`:touched` is seeded alongside `:draft` — FormSlice requires both")))))

;; ---------------------------------------------------------------------------
;; The shape of the fix, pinned structurally.
;; ---------------------------------------------------------------------------

(deftest every-shipped-registration-tolerates-absence
  (testing "each entry in the example's registry is nilable — unwrap any one of
            them and the boot window (or, for [:settings-form], the whole life of
            the app) reopens"
    (doseq [[path schema] app-schema/app-db-schemas]
      (is (and (vector? schema) (= :maybe (first schema)))
          (str "the registration at " path " tolerates absence")))
    (is (= #{[:auth] [:auth :login-form] [:auth :register-form] [:settings-form]}
           (set (keys app-schema/app-db-schemas)))
        "the registry still covers exactly the four app-db paths this app owns")))
