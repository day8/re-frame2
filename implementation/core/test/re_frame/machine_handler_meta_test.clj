(ns re-frame.machine-handler-meta-test
  "rf2-ftrcv (supersedes rf2-ypu5i / rf2-npvsx) — machine guard/action
  fn-source handler-meta is a GENERAL source-meta surface DERIVED from the
  machine's `:event` registration spec, NOT a registrar kind. Per Spec 005
  §Trace events — guard evaluations and action runs + Xray Spec 003
  §Focused-transition lens (rf2-99rhe).

  The `reg-machine` macro walks the literal machine spec at expansion
  time and captures `pr-str` of every guard / action fn-form, co-locating
  `{:fn .. :source-coords .. :source-code ..}` onto each `:guards` /
  `:actions` entry. `reg-machine*` stores the whole stamped spec under
  `:rf/machine` in the machine's `:event` registration. Tools read

      (rf/handler-meta :machine-guard  [<machine-id> <guard-id>])
      (rf/handler-meta :machine-action [<machine-id> <action-id>])

  which DERIVES the meta on demand from that `:event` spec — there is NO
  `:machine-guard` / `:machine-action` registrar kind (the addressing is
  unchanged; only the storage moved). Each derived meta carries
  `:rf/guard-id` / `:rf/action-id` (the bare id), `:rf/machine-id` (the
  scoping machine), `:rf.handler/source` (the `pr-str` of the literal
  fn-form), `:handler-fn` (the actual function), and `:ns` / `:line` /
  (`:column`) / `:file` from the per-element coord walker.

  Production-elision (rf2-ftrcv): the derivation is gated on
  `re-frame.interop/debug-enabled?`; under `:advanced` + `goog.DEBUG=false`
  the macro emits co-located `:guards` / `:actions` entries with NO
  `:source-code` slot, so the derivation returns nil."
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

;; ---- the registrar kind set is the clean ten -----------------------------

(deftest registrar-kinds-are-the-clean-ten
  (testing "rf2-ftrcv: `:machine-guard` / `:machine-action` are NOT registrar kinds"
    (is (= #{:event :sub :fx :cofx :view :frame :route :head
             :error-projector :flow}
           registrar/kinds)
        "registrar/kinds is the canonical ten — no machine *registration* kinds")
    (is (not (registrar/valid-kind? :machine-guard))
        "(valid-kind? :machine-guard) = false")
    (is (not (registrar/valid-kind? :machine-action))
        "(valid-kind? :machine-action) = false"))
  (testing "rf2-ftrcv: registering a machine writes NO :machine-guard / :machine-action registrar entry"
    (rf/reg-machine :rf2-ftrcv/no-side-table
      {:initial :idle
       :guards  {:ok? (fn [_] true)}
       :actions {:go! (fn [_] nil)}
       :states  {:idle {:on {:e {:target :idle :guard :ok? :action :go!}}}}})
    (is (= {} (registrar/registrations :machine-guard))
        "no :machine-guard registrar side-table is created")
    (is (= {} (registrar/registrations :machine-action))
        "no :machine-action registrar side-table is created")
    (is (nil? (registrar/lookup :machine-guard [:rf2-ftrcv/no-side-table :ok?]))
        "registrar/lookup of the derived kind is nil — the storage moved")
    ;; The DERIVED handler-meta addressing is unchanged — Xray + pair-MCP
    ;; source-jump call sites still resolve through (rf/handler-meta ...).
    (is (some? (rf/handler-meta :machine-guard [:rf2-ftrcv/no-side-table :ok?]))
        "the (rf/handler-meta :machine-guard ...) addressing still resolves")))

;; ---- the dev handler-meta addressing is unchanged ------------------------

(deftest dev-handler-meta-addressing-unchanged
  (testing "rf2-ftrcv acceptance: (rf/handler-meta :machine-guard [mid gid]) returns
  :rf.handler/source in dev — the UNCHANGED surface Xray's GUARDS RUN lens +
  pair-MCP source-jump read. The two machine kinds dispatch to the
  machine-spec-derived source; the ten registrar kinds fall through to the
  registrar lookup."
    (rf/reg-machine :rf2-ftrcv/addressing
      {:initial :idle
       :guards  {:token? (fn [{data :data}] (get-in data [:session :token]))}
       :actions {:fetch! (fn [_ctx] {:fx [[:dispatch [:loading/complete]]]})}
       :states  {:idle {:on {:go {:target :idle :guard :token? :action :fetch!}}}}})
    (let [g (rf/handler-meta :machine-guard  [:rf2-ftrcv/addressing :token?])
          a (rf/handler-meta :machine-action [:rf2-ftrcv/addressing :fetch!])]
      (is (string? (:rf.handler/source g))
          "dev: :machine-guard handler-meta carries :rf.handler/source")
      (is (string? (:rf.handler/source a))
          "dev: :machine-action handler-meta carries :rf.handler/source"))
    (testing "the ten registrar kinds still fall through to the registrar lookup"
      (rf/reg-event-db :rf2-ftrcv/plain-event (fn [db _] db))
      (is (= (registrar/lookup :event :rf2-ftrcv/plain-event)
             (rf/handler-meta :event :rf2-ftrcv/plain-event))
          ":event handler-meta is the registrar lookup, unchanged"))
    (testing "an unknown (machine-id, id) addresses to nil, not a throw"
      (is (nil? (rf/handler-meta :machine-guard [:rf2-ftrcv/no-such :nope]))
          "no :event registration → nil")
      (is (nil? (rf/handler-meta :machine-guard [:rf2-ftrcv/addressing :no-such-guard]))
          "absent guard id → nil")
      (is (nil? (rf/handler-meta :machine-guard :not-a-vector))
          "non-vector id → nil (no throw)"))))

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

;; ---- enumeration via the :event registration spec (no side-table) --------

(deftest guards-and-actions-enumerable-via-event-registration-spec
  (testing "rf2-ftrcv: the guard/action source lives on the machine's :event
  registration spec — tools enumerate it from there, NOT from a
  `(rf/registrations :machine-guard)` side-table (which no longer exists)"
    (rf/reg-machine :rf2-ftrcv/enum
      {:initial :idle
       :guards  {:ok? (fn [_] true)}
       :actions {:go! (fn [_] nil)}
       :states  {:idle {:on {:e {:target :idle :guard :ok? :action :go!}}}}})
    ;; No registrar side-table.
    (is (= {} (rf/registrations :machine-guard)))
    (is (= {} (rf/registrations :machine-action)))
    ;; The co-located source rides on `:rf/machine` in the :event registration.
    (let [spec (:rf/machine (registrar/lookup :event :rf2-ftrcv/enum))]
      (is (string? (get-in spec [:guards  :ok? :source-code]))
          "guard source co-located on the :event registration's spec")
      (is (string? (get-in spec [:actions :go! :source-code]))
          "action source co-located on the :event registration's spec"))
    ;; And the derived handler-meta surface resolves both.
    (is (= :ok? (:rf/guard-id  (rf/handler-meta :machine-guard  [:rf2-ftrcv/enum :ok?]))))
    (is (= :go! (:rf/action-id (rf/handler-meta :machine-action [:rf2-ftrcv/enum :go!]))))))

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
    ;; for co-located entries carrying `:source-code`. The plain-fn surface
    ;; carries opaque spec data (bare fns, no co-location), so no
    ;; registrar entries are written. Tools fall back to call-site
    ;; coords on the top-level handler-meta (which is the existing
    ;; reg-machine* contract per Spec 005 §reg-machine vs reg-machine*).
    (is (nil? (rf/handler-meta :machine-guard  [:rf2-ypu5i/programmatic :any?]))
        ":machine-guard slot absent on the plain-fn surface")
    (is (nil? (rf/handler-meta :machine-action [:rf2-ypu5i/programmatic :noop!]))
        ":machine-action slot absent on the plain-fn surface")))

;; ---- production elision --------------------------------------------------

(deftest production-elision-suppresses-handler-meta-derivation
  (testing "rf2-ftrcv: with `interop/debug-enabled?` stubbed false, the
  derivation returns nil — mirrors the elision contract that protects fn
  body bytes from shipping in CLJS production bundles. The JVM path
  here is the structural sentinel; the actual CLJS bundle elision is
  asserted by `npm run test:elision`"
    (with-redefs [interop/debug-enabled? false]
      ;; `reg-machine*` skips the macro stamp entirely; call it directly
      ;; with a pre-stamped (co-located) spec simulating the macro emission
      ;; under dev to isolate the derivation gate. Under `debug-enabled?
      ;; false` the derivation must return nil even though the registered
      ;; `:event` spec DOES carry `:source-code` on its entries (the
      ;; production CLJS bundle never ships those — that absence is asserted
      ;; by `npm run test:elision`).
      (core-machines/reg-machine* :rf2-ftrcv/elided
        {:initial :idle
         :guards  {:g? {:fn (fn [_] true) :source-code "(fn [_] true)"}}
         :actions {:a! {:fn (fn [_] nil)  :source-code "(fn [_] nil)"}}
         :states  {:idle {:on {:e {:target :idle :guard :g? :action :a!}}}}})
      ;; Under prod elision the derivation no-ops (gated inside the call).
      (is (nil? (rf/handler-meta :machine-guard  [:rf2-ftrcv/elided :g?]))
          "production-elided: :machine-guard derivation returns nil")
      (is (nil? (rf/handler-meta :machine-action [:rf2-ftrcv/elided :a!]))
          "production-elided: :machine-action derivation returns nil"))))
