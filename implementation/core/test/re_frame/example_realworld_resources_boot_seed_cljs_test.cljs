(ns re-frame.example-realworld-resources-boot-seed-cljs-test
  "rf2-ghxt — THE BOOT WINDOW, RealWorld-on-resources: an app whose slices are
   seeded by SEPARATE events must still boot under its own app-db schema
   registry, and a slice that is absent BY DESIGN must not veto every commit the
   application will ever make.

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

     [:auth]                 seeded by `:auth/initialise` — its own
                             `:initial-events` step, since it consumes a
                             recordable token coeffect
     [:auth :login-form]     seeded by `:auth.login-form/initialise`
     [:auth :register-form]  seeded by `:auth.register-form/initialise`
     [:settings-form]        NOT SEEDED AT BOOT AT ALL

   The first three are a boot WINDOW: each is its own dispatch (the two form
   initialisers are fanned out from `:app/initialise` in core.cljs), so each seed
   is its own commit, and each was rejected by the siblings still absent.

   The fourth is worse, and is why this example failed harder than its
   managed-HTTP twin (rf2-2xzc). `[:settings-form]` is seeded by `:settings/load`
   on settings-ROUTE ENTRY, behind an auth guard — so for an anonymous visitor,
   and for a signed-in one who never opens Settings, it reads `nil` for the whole
   life of the app. Registered bare it rejected EVERY `:db` commit in the
   application, permanently, not only during boot. And because a rejected
   candidate does not walk `:fx` either, nothing downstream of a `:db`-bearing
   handler fired.

   THE FIX. Every entry wears a `:maybe`, and `schema.cljs` names the registry as
   the VALUE `app-db-schemas` so a harness can install it on its own frame. For
   the first three the `:maybe` buys exactly the window before that slice's seed
   lands; for `[:settings-form]` it is permanent, because absence there is the
   design rather than a window — seeding it at boot would only manufacture an
   empty draft from a user who is not signed in yet, to be overwritten on entry.

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

   WHY THE SEEDS BELOW ARE LOCAL EVENTS RATHER THAN THE APP'S OWN. This ns
   requires the example's `schema` ns and NOTHING else from the app, because
   `reg-app-schemas` writes no registrar or source-store row — so this ns cannot
   collide with anything. Requiring the app's EVENT nses would, and does:
   cljs.test loads every test ns into one bundle, the two RealWorld apps
   deliberately share id vocabulary (`:settings/load`, `:auth/initialise`,
   `:auth.login-form/initialise`, … are registered by BOTH with different
   implementations), and this ns sorts early. Measured on this tree: requiring
   `realworld-resources.auth` / `.settings` here put a second provenance row for
   those ids into the shared source store from this ns's load onward, and every
   alphabetically-later suite's baseline then failed default-image assembly with
   `:rf.error/image-duplicate-id` — dozens of unrelated tests, in nine
   namespaces. Nor is hiding the sibling tree from HERE the fix. The fixture's
   `:app-ns` option exists for exactly this collision, but its invariant is
   SELF-HIDING — a suite names its OWN app, so the app is removed the moment its
   requires bring it live and no later baseline can hold it. A suite that named
   an app it does not own would be claiming rows before that app had finished
   loading, which is the incomplete-capture shape measured against the memoized
   predecessor of that option (`:home/show-global-feed` left live, failing the
   sibling resources suite's own frame creation). This ns owns neither app.

   So the seeds below are local events that reproduce the app's own writes —
   same paths, same slice shapes, one dispatch each. What is under test is the
   REGISTRY, which is the artefact that was wrong and which is imported from the
   example verbatim; the seeds are the boot fan-out's shape, and the shape is
   what the registry has to tolerate. The app's own handlers, driven against a
   hand-registered `[:auth]`, are covered by the example's integration suite in
   the adapter tree."
  (:require [cljs.test :refer-macros [deftest testing is use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.schemas]
            ;; Activate the default Malli validator (rf2-t0hq). The CLJS default
            ;; validator SOFT-PASSES without this require, and a soft pass would
            ;; make the whole regression below unobservable — the bare registry
            ;; would boot just as happily as the shipped one. This is also the
            ;; canonical app-boot opt-in for Malli app-schema validation.
            [re-frame.schemas.malli]
            ;; The example's app-db schema registry, imported verbatim. This ns
            ;; registers no events / subs / fx / resources — see the ns doc.
            [realworld-resources.schema :as app-schema])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---------------------------------------------------------------------------
;; The example's boot fan-out, reproduced as three separate commits.
;;
;; Paths and slice shapes are the app's (auth.cljs `:auth/initialise`,
;; `:auth.login-form/initialise`, `:auth.register-form/initialise`). The ids are
;; local so this ns stays collision-free in the shared bundle. What matters is
;; that each seed is its OWN event — separate events mean separate commits, and
;; that is the whole of the regression.
;; ---------------------------------------------------------------------------

(rf/reg-event :rf2-ghxt.boot/seed-auth
  (fn [{:keys [db]} _]
    {:db (assoc db :auth {:user nil :token nil})}))

(rf/reg-event :rf2-ghxt.boot/seed-login-form
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:auth :login-form]
                   {:draft {:email "" :password ""} :touched #{}})}))

(rf/reg-event :rf2-ghxt.boot/seed-register-form
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:auth :register-form]
                   {:draft {:username "" :email "" :password ""} :touched #{}})}))

;; An ordinary post-boot edit — the shape of every `:*-form/edit-field` handler.
(rf/reg-event :rf2-ghxt.boot/edit-login-email
  (fn [{:keys [db]} [_ value]]
    {:db (-> db
             (assoc-in [:auth :login-form :draft :email] value)
             (update-in [:auth :login-form :touched] (fnil conj #{}) :email))}))

;; `:settings/load`'s write: seeded from the authenticated user, on route entry,
;; behind the auth guard (settings.cljs). `:touched` is seeded alongside `:draft`
;; because FormSlice requires both.
(rf/reg-event :rf2-ghxt.boot/load-settings-form
  (fn [{:keys [db]} [_ user]]
    {:db (assoc-in db [:settings-form]
                   {:draft   {:image    (or (:image user) "")
                              :username (or (:username user) "")
                              :bio      (or (:bio user) "")
                              :email    (or (:email user) "")
                              :password ""}
                    :touched #{}})}))

(defn- boot!
  "Drive the three boot seeds into frame `f`, one dispatch each, in the app's
  order — `[:auth]` is created in the AuthSlice shape before the two form
  initialisers write inside it."
  [f]
  (rf/dispatch-sync [:rf2-ghxt.boot/seed-auth] {:frame f})
  (rf/dispatch-sync [:rf2-ghxt.boot/seed-login-form] {:frame f})
  (rf/dispatch-sync [:rf2-ghxt.boot/seed-register-form] {:frame f}))

(def ^:private pre-fix-bare-registry
  "The four registrations EXACTLY as `schema.cljs` carried them before rf2-ghxt —
  bare, no `:maybe`. Kept as the negative control: the shipped registry has to
  permit a boot this one refuses."
  {[:auth]                app-schema/AuthSlice
   [:auth :login-form]    app-schema/FormSlice
   [:auth :register-form] app-schema/FormSlice
   [:settings-form]       app-schema/FormSlice})

;; ---------------------------------------------------------------------------
;; The regression.
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
  (testing "the bare registry this example used to carry REJECTS the very first
            seed — the regression, and the proof that the validator is live here
            (a soft-passing validator would let this boot too)"
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
      ;; Pre-fix this commit was rejected forever, because [:settings-form] read
      ;; nil at every commit for the whole life of the application.
      (rf/dispatch-sync [:rf2-ghxt.boot/edit-login-email "alice@example.com"]
                        {:frame f})
      (is (= "alice@example.com"
             (get-in (rf/app-db-value f) [:auth :login-form :draft :email]))
          "an ordinary post-boot :db commit lands"))))

(deftest the-settings-slice-still-validates-once-route-entry-seeds-it
  (testing "the settings draft, seeded from the authenticated user on route
            entry, commits under the shipped registry — the `:maybe` widens the
            schema, it does not retire it"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf/reg-app-schemas app-schema/app-db-schemas {:frame f})
      (boot! f)
      (rf/dispatch-sync [:rf2-ghxt.boot/load-settings-form
                         {:username "alice" :email "alice@example.com"
                          :bio nil :image nil}]
                        {:frame f})
      (let [slice (get (rf/app-db-value f) :settings-form)]
        (is (some? slice)
            "the settings draft is seeded on route entry")
        (is (= "alice" (get-in slice [:draft :username]))
            "the draft is seeded FROM the authenticated user")
        (is (true? (m/validate app-schema/FormSlice slice))
            "and it satisfies the UNWRAPPED FormSlice — the `:maybe` only added
             tolerance of absence; a present-but-malformed slice is still
             rejected")))))

;; ---------------------------------------------------------------------------
;; The shape of the fix, pinned structurally.
;; ---------------------------------------------------------------------------

(deftest every-shipped-registration-tolerates-absence
  (testing "each entry in the example's registry is nilable — unwrap any one of
            them and the boot window (or, for [:settings-form], the whole life of
            the application) reopens"
    (doseq [[path schema] app-schema/app-db-schemas]
      (is (and (vector? schema) (= :maybe (first schema)))
          (str "the registration at " path " tolerates absence"))
      (is (true? (m/validate schema nil))
          (str "the registration at " path " really does admit nil")))
    (is (= #{[:auth] [:auth :login-form] [:auth :register-form] [:settings-form]}
           (set (keys app-schema/app-db-schemas)))
        "the registry still covers exactly the four app-db paths this app owns")))
