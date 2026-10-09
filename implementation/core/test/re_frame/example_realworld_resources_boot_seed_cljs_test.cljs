(ns re-frame.example-realworld-resources-boot-seed-cljs-test
  "RealWorld-on-resources boots under its own app-db schema registry.

   The candidate validator walks every registered path over the whole
   candidate app-db at every `:db` commit, reading an unwritten path as `nil`
   (Spec 010 §Per-step recovery). `[:auth]`, `[:auth :login-form]` and
   `[:auth :register-form]` are seeded by separate events, a boot window in
   which each would veto the others; `[:settings-form]` is seeded only on
   settings-route entry, so registered bare it would veto every commit the app
   ever makes. So every entry in `app-db-schemas` wears a `:maybe`. Only a
   development build validates, so only a development build shows the failure.

   This ns requires the example's `schema` ns and nothing else from the app:
   both RealWorld apps register the same event ids with different
   implementations, and loading either app's event nses here would put a
   second provenance row into the shared source store and fail default-image
   assembly for later suites. The seeds below are local events reproducing the
   app's own writes; what is under test is the registry, imported verbatim."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.schemas]
            ;; The CLJS default validator soft-passes without this, which would
            ;; let the bare registry boot as happily as the shipped one.
            [re-frame.schemas.malli]
            [realworld-resources.schema :as app-schema])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; The boot fan-out as three separate commits, with the paths and slice shapes
;; of auth.cljs's `:auth/initialise`, `:auth.login-form/initialise` and
;; `:auth.register-form/initialise`.

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

(defn- boot! [f]
  (rf/dispatch-sync [:rf2-ghxt.boot/seed-auth] {:frame f})
  (rf/dispatch-sync [:rf2-ghxt.boot/seed-login-form] {:frame f})
  (rf/dispatch-sync [:rf2-ghxt.boot/seed-register-form] {:frame f}))

(def ^:private pre-fix-bare-registry
  "The four boot-relevant registrations without `:maybe`."
  {[:auth]                app-schema/AuthSlice
   [:auth :login-form]    app-schema/FormSlice
   [:auth :register-form] app-schema/FormSlice
   [:settings-form]       app-schema/FormSlice})

(deftest boot-under-the-shipped-registry
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (rf/reg-app-schemas app-schema/app-db-schemas {:frame f})
    (boot! f)
    (is (= {:auth {:user          nil
                   :token         nil
                   :login-form    {:draft {:email "" :password ""} :touched #{}}
                   :register-form {:draft {:username "" :email "" :password ""} :touched #{}}}}
           (rf/app-db-value f))
        "every seed landed, each its own commit, with [:settings-form] never seeded")))

(deftest boot-under-the-pre-fix-bare-registry
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (rf/reg-app-schemas pre-fix-bare-registry {:frame f})
    (boot! f)
    (is (= {} (rf/app-db-value f))
        "each seed was rolled back by the siblings still absent; this also proves the validator is live")))

(deftest every-shipped-registration-tolerates-absence
  (doseq [[path schema] app-schema/app-db-schemas]
    (is (true? (m/validate schema nil))
        (str "the registration at " path " admits nil"))))
