(ns re-frame.actor-resource-lease-release-cljs-test
  "Machine-SIDE contract for releasing an actor's resource leases on destroy
  (Spec 016 §Release authority is per owner kind, 016:290).

  COUNT PIN (Spec 005 §What auto-cancels on destroy). Auto-cancel-on-destroy
  covers exactly THREE framework-managed resource kinds — (1) `:rf.http/managed`
  requests, (2) armed `:after` timers, and (3) the `:rf.resource/*` owner lease
  this suite pins. The count is three, not two; the machine × resource seam is
  the one place the summary historically drifted to \"two built-in kinds ...
  only\" (a pre-resource-lease line, since corrected).

  The cross-artefact integration (a real resource entry → released → no
  continued polling) is pinned in the resources artefact
  (`resources_machine_owner_release_cljs_test`, which has both machines +
  resources on its classpath). THIS suite pins the machine-side contract
  WITHOUT a resources dependency — machines depends only on core, so it uses a
  STUB `:rf.resource/release-owner` event handler that records every release:

    1. SHAPE + TRIGGERS — explicit `[:rf.machine/destroy <id>]` (singleton)
       AND the `:final?`-state auto-destroy both fire
       `[:rf.resource/release-owner {:owner [:machine actor-id]}]`, with the
       owner key the runtime owns (`[:machine actor-id]` — the registered
       machine-id for a singleton). Covers BOTH teardown codepaths
       (`teardown-live-actor!` for explicit destroy; `finalize-machine`'s
       appended `:fx` for auto-destroy) so the release fires regardless of
       cause.
    2. NO-RESOURCES GUARD — with NO `:rf.resource/release-owner` handler
       registered (the app uses machines WITHOUT resources), an actor destroy
       must NOT raise `:rf.error/no-such-handler` and must NOT throw — the
       release is silently skipped (`resource-release/release-owner-registered?`
       gates it).

  Named `*-cljs-test.cljc` so both cognitect.test-runner (JVM, plain-atom) and
  shadow-cljs (CLJS, reagent) discover + run it — the `cljs-test$`
  ns-regexp on the `:node-test` build only matches the `-cljs-test` suffix."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as mtest]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

(def ^:private snapshot mtest/snapshot)

;; ---- stub release-owner handler (stand-in for the resources artefact) ------

(def ^:private released (atom []))

(defn- install-release-stub! []
  (reset! released [])
  ;; Register a stub `:rf.resource/release-owner` event handler that records
  ;; the released owner — the machine side dispatches this by NAME (it never
  ;; :requires resources). This stands in for the real resources handler.
  (rf/reg-event :rf.resource/release-owner
    (fn [_ [_ {:keys [owner]}]]
      (swap! released conj owner)
      {})))

;; ===========================================================================
;; 1. Explicit destroy fires release with [:machine actor-id]
;; ===========================================================================

(deftest explicit-destroy-dispatches-release-for-machine-owner
  (testing "rf2-xw5t0y — explicit [:rf.machine/destroy <id>] of a singleton
            fires [:rf.resource/release-owner {:owner [:machine <id>]}]"
    (install-release-stub!)
    (rf/reg-machine :rel/reader
      {:initial :reading
       :data    {}
       :states  {:reading {:on {:stop :idle}}
                 :idle    {}}})
    (rf/dispatch-sync [:rel/reader [:rf.machine/start]])
    (is (some? (snapshot :rel/reader)) "actor live before destroy")
    (rf/reg-event ::destroy (fn [_ _] {:fx [[:rf.machine/destroy :rel/reader]]}))
    (rf/dispatch-sync [::destroy])
    (is (nil? (snapshot :rel/reader)) "actor torn down")
    (is (= [[:machine :rel/reader]] @released)
        "exactly one release fired, for the actor's [:machine actor-id] owner")))

;; ===========================================================================
;; 2. :final?-state auto-destroy ALSO fires the release (the other codepath)
;; ===========================================================================

(deftest final-state-auto-destroy-dispatches-release-for-machine-owner
  (testing "rf2-xw5t0y — a singleton reaching :final? auto-destroys via
            finalize-machine, which appends the release to its :fx; the release
            fires for [:machine <id>] regardless of the (auto) destroy cause"
    (install-release-stub!)
    (rf/reg-machine :rel/finisher
      {:initial :running
       :data    {}
       :states  {:running {:on {:fin :done}}
                 :done    {:final? true}}})
    (rf/dispatch-sync [:rel/finisher [:rf.machine/start]])
    (rf/dispatch-sync [:rel/finisher [:fin]])
    (is (nil? (snapshot :rel/finisher)) "actor auto-destroyed on :final?")
    (is (= [[:machine :rel/finisher]] @released)
        "auto-destroy released the [:machine actor-id] owner too")))

;; ===========================================================================
;; 3. NO-RESOURCES GUARD — destroy is a clean no-op when no release handler
;; ===========================================================================

(deftest destroy-without-resources-is-a-clean-no-op
  (testing "rf2-xw5t0y — with NO :rf.resource/release-owner handler registered
            (machines used WITHOUT resources), an actor destroy must not raise
            :rf.error/no-such-handler and must not throw — the release is
            silently skipped (the no-resources guard)"
    ;; deliberately do NOT install the stub.
    (reset! released [])
    (rf/reg-machine :rel/standalone
      {:initial :running
       :data    {}
       :states  {:running {:on {:end :done}}
                 :done    {:final? true}}})
    (rf/dispatch-sync [:rel/standalone [:rf.machine/start]])
    ;; explicit destroy path
    (rf/reg-event ::destroy (fn [_ _] {:fx [[:rf.machine/destroy :rel/standalone]]}))
    (is (nil? (try (rf/dispatch-sync [::destroy]) nil
                   (catch #?(:clj Throwable :cljs :default) e e)))
        "explicit destroy without resources does not throw")
    (is (empty? @released) "nothing released (handler absent → guarded out)")

    ;; auto-destroy path (finalize)
    (rf/reg-machine :rel/standalone2
      {:initial :running
       :data    {}
       :states  {:running {:on {:end :done}}
                 :done    {:final? true}}})
    (rf/dispatch-sync [:rel/standalone2 [:rf.machine/start]])
    (is (nil? (try (rf/dispatch-sync [:rel/standalone2 [:end]]) nil
                   (catch #?(:clj Throwable :cljs :default) e e)))
        "auto-destroy without resources does not throw")
    (is (empty? @released) "still nothing released (guarded out on both paths)")))
