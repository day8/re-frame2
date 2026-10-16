(ns re-frame.example-realworld-resources-boot-seed-cljs-test
  "RealWorld-on-resources boots under its own app-db schema registry.

   The candidate validator checks a registered path only where the candidate
   has it (Spec 010 §When schemas are checked). `[:auth]`, `[:auth
   :login-form]` and `[:auth :register-form]` are seeded by separate events
   and `[:settings-form]` only on settings-route entry, so a seed is never
   checked against a sibling it has not written yet, and a registry of bare
   slice schemas boots as the shipped one does. Only a development build
   validates.

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

(rf/reg-event :rf2-ghxt.boot/break-settings-form
  (fn [{:keys [db]} _]
    {:db (assoc db :settings-form :not-a-form)}))

(def ^:private booted-db
  "app-db after `boot!`: every seed landed, and `[:settings-form]` was never
  seeded."
  {:auth {:user          nil
          :token         nil
          :login-form    {:draft {:email "" :password ""} :touched #{}}
          :register-form {:draft {:username "" :email "" :password ""} :touched #{}}}})

(def ^:private bare-registry
  "The four boot-relevant registrations without `:maybe`."
  {[:auth]                app-schema/AuthSlice
   [:auth :login-form]    app-schema/FormSlice
   [:auth :register-form] app-schema/FormSlice
   [:settings-form]       app-schema/FormSlice})

(deftest boot-under-the-shipped-registry
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (rf/reg-app-schemas app-schema/app-db-schemas {:frame f})
    (boot! f)
    (is (= booted-db (rf/app-db-value f))
        "every seed landed, each its own commit, with [:settings-form] never seeded")))

(deftest boot-under-a-bare-registry
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (rf/reg-app-schemas bare-registry {:frame f})
    (boot! f)
    (is (= booted-db (rf/app-db-value f))
        "every seed landed: a sibling the candidate still lacks is not checked")
    (rf/dispatch-sync [:rf2-ghxt.boot/break-settings-form] {:frame f})
    (is (= booted-db (rf/app-db-value f))
        "control: the validator is live, so a present non-conforming slice is rejected")))

(deftest every-shipped-registration-tolerates-absence
  (is (= (zipmap (keys app-schema/app-db-schemas) (repeat true))
         (into {} (map (fn [[path schema]] [path (m/validate schema nil)]))
               app-schema/app-db-schemas))))
