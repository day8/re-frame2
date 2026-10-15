(ns login-form.stories-cljs-test
  "Login variants project runtime-db snapshots and supply Controls schemas."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.source-store :as rf.source-store]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.async :as rf.story.async]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.test-support :as rf.test-support]
            [malli.core :as m]
            [re-frame.story.view-args :as rf.story.view-args]
            [re-frame.story.ui.controls :as rf.story.ui.controls]
            [re-frame.story.ui.schema-validation :as rf.story.ui.schema-validation]
            [login-form.events]
            [login-form.subs]
            [login-form.stories :as lf-stories]
            [login.stories :as login-stories]))

(def ^:private registrar-snapshot (atom nil))

;; Scoped images need these app descriptors even after another suite resets the store.
(def ^:private source-store-baseline @rf.source-store/kind->id->ns->descriptor)

(defn- before! []
  (reset! registrar-snapshot (rf.test-support/snapshot-registrar))
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.frame/ensure-default-frame!)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story/clear-all!)
  (reset! rf.source-store/kind->id->ns->descriptor source-store-baseline)
  (rf.machines/install-machine-runtime!)
  (lf-stories/register-all!))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (rf.test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! rf.frame/frames {}))

;; Async story bodies require map fixtures.
(use-fixtures :each {:before before! :after after!})

(defn- run-then [done variant-id check]
  (-> (rf.story/run-variant variant-id)
      (rf.story.async/then
        (fn [result]
          (is (rf.story/assertions-passing? result)
              (str variant-id " play assertions: " (pr-str (:assertions result))))
          (check (rf/frame-state-value variant-id))
          (rf.story/destroy-variant! variant-id)
          (done)))))

(deftest idle-variant-state-sub-resolves
  (async done
    (run-then done :story.login-form/idle
      (fn [fs]
        (is (= :idle (rf/compute-sub [:login/state] fs)))))))

(deftest submitting-variant-state-sub-resolves
  (async done
    (run-then done :story.login-form/submitting
      (fn [fs]
        (is (= :submitting (rf/compute-sub [:login/state] fs)))))))

(deftest error-variant-error-sub-resolves
  (async done
    (run-then done :story.login-form/error
      (fn [fs]
        (is (= [:error "Invalid credentials."]
               [(rf/compute-sub [:login/state] fs)
                (rf/compute-sub [:login/error] fs)]))))))

(deftest submitting-retry-variant-attempts-sub-resolves
  (async done
    (run-then done :story.login-form/submitting-retry
      (fn [fs]
        (is (= [:submitting-retry 1]
               [(rf/compute-sub [:login/state] fs)
                (rf/compute-sub [:login/attempts] fs)]))))))

(deftest authenticated-variant-email-sub-resolves
  (async done
    (run-then done :story.login-form/authenticated
      (fn [fs]
        (is (= [:authenticated "ada@example.com"]
               [(rf/compute-sub [:login/state] fs)
                (rf/compute-sub [:login/email] fs)]))))))

(def ^:private example-variant-ids
  #{:story.login/empty
    :story.login/filled
    :story.login/submitting
    :story.login/invalid-credentials
    :story.login/auth-error
    :story.login/locked-out
    :story.login/success})

(def ^:private testbed-variant-ids
  #{:story.login-form/idle
    :story.login-form/submitting
    :story.login-form/error
    :story.login-form/submitting-retry
    :story.login-form/authenticated})

(deftest login-decks-register-under-distinct-parents
  (login-stories/register-all!)
  (lf-stories/register-all!)
  (is (= testbed-variant-ids (rf.story/variants-of :story.login-form)))
  (is (= example-variant-ids (rf.story/variants-of :story.login))))

;; Capture at load so sibling fixture resets cannot erase the component schemas.
(def ^:private view-meta-at-load
  {:login-form.views/login-card (rf.registrar/handler-meta :view :login-form.views/login-card)
   :login.core/root-view (rf.registrar/handler-meta :view :login.core/root-view)})

(def ^:private malli-validator {:validate m/validate :explain m/explain})

(defn- heading-violations [schema args]
  (mapv :key (rf.story.ui.schema-validation/args-violations args schema malli-validator)))

(defn- check-heading-props-schema [variant-id view-id]
  (rf.registrar/register! :view view-id (get view-meta-at-load view-id))
  (let [schema (rf.story.view-args/compiled-view-args-schema variant-id)]
    (is (= {:widget :text}
           (:heading (rf.story.ui.controls/resolve-argtypes variant-id))))
    (is (= [] (heading-violations schema {:heading "Sign in"})))
    (is (= [:heading] (heading-violations schema {:heading ""})))))

(deftest login-views-carry-a-props-schema
  (testing ":story.login-form/idle"
    (check-heading-props-schema :story.login-form/idle :login-form.views/login-card))
  (testing ":story.login/empty"
    (login-stories/register-all!)
    (check-heading-props-schema :story.login/empty :login.core/root-view)))
