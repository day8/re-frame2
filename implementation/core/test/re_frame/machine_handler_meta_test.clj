(ns re-frame.machine-handler-meta-test
  "rf2-ypu5i — `:rf/guard-id` + `:rf/action-id` markers + reg-machine
  handler-meta source capture. Per Spec 005 §Trace events — guard
  evaluations and action runs + Xray Spec 003 §Focused-transition
  lens (rf2-99rhe).

  The `reg-machine` macro walks the literal machine spec at expansion
  time and captures `pr-str` of every guard / action fn-form. The
  runtime (`core-machines/reg-machine-impl`) then writes per-(machine-
  id, id) entries into the registrar under `:machine-guard` /
  `:machine-action` kinds so tools can read

      (rf/handler-meta :machine-guard  [<machine-id> <guard-id>])
      (rf/handler-meta :machine-action [<machine-id> <action-id>])

  Each entry carries `:rf/guard-id` / `:rf/action-id` (the bare id),
  `:rf/machine-id` (the scoping machine), `:rf.handler/source` (the
  `pr-str` of the literal fn-form), `:handler-fn` (the actual
  function), and `:ns` / `:line` / (`:column`) / `:file` from the
  per-element coord walker.

  Production-elision: the whole capture-and-write path is gated on
  `re-frame.interop/debug-enabled?`; under `:advanced` +
  `goog.DEBUG=false` the macro emission elides the
  `:rf.machine/handler-source` stamp AND the runtime's registrar
  writes no-op."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.core-machines :as core-machines]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            ;; Boot the optional machines artefact's late-bind hooks so
            ;; `reg-machine` resolves through the spec-005 implementation
            ;; rather than throwing `:rf.error/machines-artefact-missing`.
            ;; Mirrors smoke_test.clj's `(require 're-frame.machines :reload)`
            ;; — the core artefact's test classpath carries machines, but
            ;; the late-bind hooks are only installed when the namespace
            ;; loads, so each test ns that exercises machines must
            ;; require it explicitly.
            [re-frame.machines]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- the new registrar kinds are well-formed -----------------------------

(deftest registrar-kinds-include-machine-guard-and-action
  (testing "rf2-ypu5i: `:machine-guard` and `:machine-action` are valid registrar kinds"
    (is (contains? registrar/kinds :machine-guard))
    (is (contains? registrar/kinds :machine-action))
    (is (registrar/valid-kind? :machine-guard))
    (is (registrar/valid-kind? :machine-action))))

;; ---- single guard / single action capture --------------------------------

(deftest reg-machine-captures-guard-source-into-handler-meta
  (testing "rf2-ypu5i: a literal guard fn-form is captured under handler-meta"
    (rf/reg-machine :rf2-ypu5i/has-guard
      {:initial :idle
       :guards  {:token? (fn [{data :data}] (get-in data [:session :token]))}
       :states  {:idle {:on {:check {:target :idle :guard :token?}}}}})
    (let [m (rf/handler-meta :machine-guard [:rf2-ypu5i/has-guard :token?])]
      (is (some? m) "handler-meta should be present")
      (is (= :token? (:rf/guard-id m))
          ":rf/guard-id marker should carry the guard id")
      (is (= :rf2-ypu5i/has-guard (:rf/machine-id m))
          ":rf/machine-id should carry the scoping machine id")
      (is (string? (:rf.handler/source m))
          ":rf.handler/source should be a string")
      (is (str/includes? (:rf.handler/source m) "get-in")
          ":rf.handler/source should carry the fn body")
      (is (fn? (:handler-fn m))
          ":handler-fn should be the actual fn")
      (is (some? (:ns m))
          ":ns coord should be merged in from the per-element coord walker")
      (is (some? (:line m))
          ":line coord should be merged in from the per-element coord walker"))))

(deftest reg-machine-captures-action-source-into-handler-meta
  (testing "rf2-ypu5i: a literal action fn-form is captured under handler-meta"
    (rf/reg-machine :rf2-ypu5i/has-action
      {:initial :idle
       :actions {:fetch! (fn [_ctx] {:fx [[:dispatch [:loading/complete]]]})}
       :states  {:idle {:on {:start {:target :idle :action :fetch!}}}}})
    (let [m (rf/handler-meta :machine-action [:rf2-ypu5i/has-action :fetch!])]
      (is (some? m) "handler-meta should be present")
      (is (= :fetch! (:rf/action-id m))
          ":rf/action-id marker should carry the action id")
      (is (= :rf2-ypu5i/has-action (:rf/machine-id m))
          ":rf/machine-id should carry the scoping machine id")
      (is (string? (:rf.handler/source m))
          ":rf.handler/source should be a string")
      (is (str/includes? (:rf.handler/source m) ":dispatch")
          ":rf.handler/source should carry the fn body")
      (is (fn? (:handler-fn m))
          ":handler-fn should be the actual fn"))))

;; ---- multiple guards / actions in one machine ----------------------------

(deftest reg-machine-captures-many-guards
  (testing "rf2-ypu5i: every guard in :guards gets its own handler-meta entry"
    (rf/reg-machine :rf2-ypu5i/many-guards
      {:initial :idle
       :guards  {:a? (fn [_] true)
                 :b? (fn [_] false)
                 :c? (fn [{data :data}] (pos? (:n data 0)))}
       :states  {:idle {:on {:probe [{:target :idle :guard :a?}
                                     {:target :idle :guard :b?}
                                     {:target :idle :guard :c?}]}}}})
    (let [ma (rf/handler-meta :machine-guard [:rf2-ypu5i/many-guards :a?])
          mb (rf/handler-meta :machine-guard [:rf2-ypu5i/many-guards :b?])
          mc (rf/handler-meta :machine-guard [:rf2-ypu5i/many-guards :c?])]
      (is (= :a? (:rf/guard-id ma)))
      (is (= :b? (:rf/guard-id mb)))
      (is (= :c? (:rf/guard-id mc)))
      (is (str/includes? (:rf.handler/source ma) "true"))
      (is (str/includes? (:rf.handler/source mb) "false"))
      (is (str/includes? (:rf.handler/source mc) "pos?")))))

(deftest reg-machine-captures-many-actions
  (testing "rf2-ypu5i: every action in :actions gets its own handler-meta entry"
    (rf/reg-machine :rf2-ypu5i/many-actions
      {:initial :idle
       :actions {:inc! (fn [{data :data}] {:data (update data :n inc)})
                 :dec! (fn [{data :data}] {:data (update data :n dec)})
                 :emit! (fn [_] {:fx [[:dispatch [:emitted]]]})}
       :states  {:idle {:on {:bump  {:target :idle :action :inc!}
                             :nudge {:target :idle :action :dec!}
                             :send  {:target :idle :action :emit!}}}}})
    (let [m-inc  (rf/handler-meta :machine-action [:rf2-ypu5i/many-actions :inc!])
          m-dec  (rf/handler-meta :machine-action [:rf2-ypu5i/many-actions :dec!])
          m-emit (rf/handler-meta :machine-action [:rf2-ypu5i/many-actions :emit!])]
      (is (= :inc!  (:rf/action-id m-inc)))
      (is (= :dec!  (:rf/action-id m-dec)))
      (is (= :emit! (:rf/action-id m-emit)))
      (is (str/includes? (:rf.handler/source m-inc)  "inc"))
      (is (str/includes? (:rf.handler/source m-dec)  "dec"))
      (is (str/includes? (:rf.handler/source m-emit) ":dispatch")))))

;; ---- guards-and-actions in one spec --------------------------------------

(deftest reg-machine-captures-both-guards-and-actions-together
  (testing "rf2-ypu5i: a single machine carrying both surfaces gets both kinds populated"
    (rf/reg-machine :rf2-ypu5i/mixed
      {:initial :idle
       :guards  {:ready? (fn [{data :data}] (:ready? data))}
       :actions {:start! (fn [_] {:fx [[:dispatch [:started]]]})}
       :states  {:idle {:on {:go {:target :idle :guard :ready? :action :start!}}}}})
    (let [mg (rf/handler-meta :machine-guard  [:rf2-ypu5i/mixed :ready?])
          ma (rf/handler-meta :machine-action [:rf2-ypu5i/mixed :start!])]
      (is (= :ready? (:rf/guard-id  mg)))
      (is (= :start! (:rf/action-id ma)))
      (is (str/includes? (:rf.handler/source mg) ":ready?"))
      (is (str/includes? (:rf.handler/source ma) ":dispatch")))))

;; ---- registrations enumeration ------------------------------------------

(deftest registrations-enumerates-machine-guard-and-action-entries
  (testing "rf2-ypu5i: `(rf/registrations :machine-guard)` returns the per-machine entries"
    (rf/reg-machine :rf2-ypu5i/enum
      {:initial :idle
       :guards  {:ok? (fn [_] true)}
       :actions {:go! (fn [_] nil)}
       :states  {:idle {:on {:e {:target :idle :guard :ok? :action :go!}}}}})
    (let [guards  (rf/registrations :machine-guard)
          actions (rf/registrations :machine-action)]
      (is (contains? guards  [:rf2-ypu5i/enum :ok?]))
      (is (contains? actions [:rf2-ypu5i/enum :go!])))))

;; ---- re-registration clears stale entries --------------------------------

(deftest re-registration-clears-stale-handler-metas
  (testing "rf2-ypu5i: re-registering a machine with a renamed guard drops the old slot"
    (rf/reg-machine :rf2-ypu5i/reload
      {:initial :idle
       :guards  {:old? (fn [_] true)}
       :states  {:idle {:on {:e {:target :idle :guard :old?}}}}})
    (is (some? (rf/handler-meta :machine-guard [:rf2-ypu5i/reload :old?]))
        "old guard registered first time round")
    (rf/reg-machine :rf2-ypu5i/reload
      {:initial :idle
       :guards  {:new? (fn [_] true)}
       :states  {:idle {:on {:e {:target :idle :guard :new?}}}}})
    (is (nil? (rf/handler-meta :machine-guard [:rf2-ypu5i/reload :old?]))
        "old guard cleared on re-registration (hot-reload hygiene)")
    (is (some? (rf/handler-meta :machine-guard [:rf2-ypu5i/reload :new?]))
        "new guard slot populated")))

;; ---- programmatic path (reg-machine*) — no form-source -------------------

(deftest reg-machine-plain-fn-surface-skips-form-source
  (testing "rf2-ypu5i: `reg-machine*` registers no `:rf.handler/source` —
  the macro walker is the only source of fn form-strings, and the plain-fn
  surface accepts opaque spec data the walker never saw"
    (rf/reg-machine* :rf2-ypu5i/programmatic
      {:initial :idle
       :guards  {:any? (fn [_] true)}
       :actions {:noop! (fn [_] nil)}
       :states  {:idle {:on {:e {:target :idle :guard :any? :action :noop!}}}}})
    ;; The runtime side that writes per-(machine-id, id) entries only fires
    ;; when the macro stamp `:rf.machine/handler-source` is present. The
    ;; plain-fn surface carries opaque spec data with no stamp, so no
    ;; registrar entries are written. Tools fall back to call-site
    ;; coords on the top-level handler-meta (which is the existing
    ;; reg-machine* contract per Spec 005 §reg-machine vs reg-machine*).
    (is (nil? (rf/handler-meta :machine-guard  [:rf2-ypu5i/programmatic :any?]))
        ":machine-guard slot absent on the plain-fn surface")
    (is (nil? (rf/handler-meta :machine-action [:rf2-ypu5i/programmatic :noop!]))
        ":machine-action slot absent on the plain-fn surface")))

;; ---- production elision --------------------------------------------------

(deftest production-elision-suppresses-handler-meta-writes
  (testing "rf2-ypu5i: with `interop/debug-enabled?` stubbed false, the
  runtime path no-ops — mirrors the elision contract that protects fn
  body bytes from shipping in CLJS production bundles. The JVM path
  here is the structural sentinel; the actual CLJS bundle elision is
  asserted by `npm run test:elision`"
    (with-redefs [interop/debug-enabled? false]
      ;; `reg-machine*` skips the macro stamp entirely; call it directly
      ;; with a pre-stamped spec (simulating the macro emission under
      ;; dev) to isolate the runtime gate.
      (core-machines/reg-machine* :rf2-ypu5i/elided
        {:initial :idle
         :guards  {:g? (fn [_] true)}
         :actions {:a! (fn [_] nil)}
         :rf.machine/handler-source
         {:guards  {:g? "(fn [_] true)"}
          :actions {:a! "(fn [_] nil)"}}
         :states  {:idle {:on {:e {:target :idle :guard :g? :action :a!}}}}}))
    ;; Under prod elision the runtime writes nothing.
    (is (nil? (rf/handler-meta :machine-guard  [:rf2-ypu5i/elided :g?]))
        "production-elided: no :machine-guard slot")
    (is (nil? (rf/handler-meta :machine-action [:rf2-ypu5i/elided :a!]))
        "production-elided: no :machine-action slot")))
