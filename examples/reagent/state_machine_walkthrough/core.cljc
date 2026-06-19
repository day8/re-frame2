(ns state-machine-walkthrough.core
  "Runnable companion to docs/guide/concepts/machines.md.

  This is the login-flow chapter as code. Every prose snippet in the
  machines chapter appears here in the order the chapter introduces it; each section
  ends with a smoke-test fn that drives the machine through the
  scenario the chapter describes.

  Why .cljc: the chapter promises 'runs in microseconds on the JVM, no
  browser, no network.' The same code runs under shadow-cljs node-test
  for the CLJS surface. The HTTP side is exercised via the framework-
  shipped `:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure`
  stubs (Spec 014 §Testing) so no real network traffic happens.

  Read alongside docs/guide/concepts/machines.md."
  (:require [re-frame.core :as rf]
            ;; The Spec 005 state-machine ns lives in the
            ;; day8/re-frame2-machines artefact. Loading the ns here
            ;; registers its late-bind hooks so rf/reg-machine and
            ;; rf/machine-transition resolve.
            [re-frame.machines]
            ;; Managed-HTTP ships in day8/re-frame2-http.
            ;; The login flow's `:issue-request` action dispatches
            ;; `:rf.http/managed` (overridden in tests via `:fx-overrides`
            ;; to the framework-shipped canned stubs). Loading the ns here
            ;; registers the `:rf.http/managed` fx family so the override
            ;; mechanism can target a real fx-id.
            [re-frame.http.managed]
            ;; :fx-overrides into :rf.http/managed-canned-* relies on
            ;; those fx ids being registered. Registration lives in
            ;; re-frame.http.test-support.
            [re-frame.http.test-support]
            ;; The canned-failure / canned-success stubs below delegate
            ;; to the framework-shipped `:rf.http/managed-canned-*` fxs
            ;; via the registrar so the example demo (views.cljs) and
            ;; the headless tests (the `state-machine-walkthrough-runs-headless`
            ;; deftest in implementation/core/test/re_frame/examples_test.clj)
            ;; can share one registration point.
            [re-frame.registrar :as registrar]))

;; ============================================================================
;; THE TRANSITION TABLE — chapter §The same flow as a machine
;; ============================================================================
;;
;; Pure data. `:guards` and `:actions` live with the spec — there is no
;; global registry. References inside `:states` resolve against this
;; map; cross-machine reuse is via Clojure vars (define a fn, name it
;; locally in each machine's :guards / :actions).
;;
;; This is a near-twin of the login machine in the `login` example
;; (examples/reagent/login/core.cljs) — same states, guards, actions, and
;; transitions. The one deliberate divergence: this variant tags `:locked-out`
;; with `:auth/locked` because the walkthrough's root-view renders a dedicated
;; lockout panel that branches on that tag (views.cljs); the `login` example
;; omits the tag since its view never distinguishes lockout. The two examples
;; register the id `:auth.login/flow` independently — a machine id is a
;; per-frame registry key, not a global handle, and the two never co-load
;; (this walkthrough runs JVM-headless with `remove-ns` between runs; login
;; builds standalone), so the shared name is parallel, not a collision.
;; The two examples also differ in what they teach AROUND the machine: `login`
;; wires it into a live Reagent view; this walkthrough drives it HEADLESSLY
;; (the sibling core-test ns) to show the pure machine-transition + drain
;; testing story from docs/guide/concepts/machines.md. Read `login` first for the
;; UI wiring; read this for the testing progression.

(def login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   :guards
   {:under-retry-limit
    ;; 2-arity is canonical: (fn [{data :data event :event}] ...). `data` is the
    ;; snapshot's :data slot directly — pulling it from a snapshot
    ;; wrapper is the runtime's job.
    (fn [{data :data}]
      (< (:attempts data) 3))}

   :actions
   {:clear-error
    (fn [_] {:data {:error nil}})

    :issue-request
    ;; Returns effects, not side-effects. The `:rf.http/managed` fx
    ;; (Spec 014) issues the request; the framework dispatches the
    ;; explicit `:on-success` / `:on-failure` events with the reply
    ;; payload appended as the last arg, so the inner sub-event lands
    ;; back in this machine via :auth.login/flow's machine-id routing.
    (fn [{[_ creds] :event}]
      {:fx [[:rf.http/managed
             {:request    {:method :post
                           :url    "/api/login"
                           :body   creds
                           :request-content-type :json}
              :decode     :json
              :on-success [:auth.login/flow [:auth.login/success]]
              :on-failure [:auth.login/flow [:auth.login/failure]]}]]})

    :record-error
    (fn [{data :data [_ {:keys [failure]}] :event}]
      {:data (-> data
                 (update :attempts inc)
                 (assoc :error (or (:message failure) "Login failed.")))})

    :lock-account
    (fn [_]
      {:fx [[:rf.http/managed
             {:request {:method :post :url "/api/auth/lock"}}]]})

    :store-session
    (fn [{[_ {:keys [value]}] :event}]
      {:fx [[:auth.session/store {:token (:token value)}]]})}

   :states
   {:idle
    {:on {:auth.login/submit {:target :submitting
                              :action :clear-error}}}

    :submitting
    ;; :auth/busy tag — views query (rf/machine-has-tag? :auth.login/flow
    ;; :auth/busy) to disable inputs and re-label the submit button
    ;; while the request is in flight (ch.12 §State tags).
    {:tags  #{:auth/busy}
     :entry :issue-request
     :on    {:auth.login/success {:target :authed
                                  :action :store-session}
             :auth.login/failure [{:target :error-shown
                                   :guard  :under-retry-limit
                                   :action :record-error}
                                  {:target :locked-out
                                   :action :lock-account}]}}

    :error-shown
    {:on {:auth.login/dismiss {:target :idle}
          :auth.login/submit  {:target :submitting}}}

    :authed
    ;; :auth/authenticated tag — views query
    ;; (rf/machine-has-tag? :auth.login/flow :auth/authenticated) once the
    ;; flow reaches this terminal state.
    {:tags #{:auth/authenticated}
     :meta {:terminal? true}}

    :locked-out
    ;; :auth/locked tag — root-view swaps the form for the locked-out
    ;; panel when (rf/machine-has-tag? :auth.login/flow :auth/locked) is true.
    {:tags #{:auth/locked}
     :meta {:terminal? true}}}})

;; ============================================================================
;; FX — chapter §Wiring a machine into the rest of re-frame
;; ============================================================================
;;
;; The `:auth.session/store` fx is a stub for the example: it shows the
;; shape, not real localStorage. The smoke tests below exercise the
;; managed-HTTP path via the framework-shipped canned-success /
;; canned-failure stubs (Spec 014 §Testing), routed in via the
;; `:fx-overrides` seam at frame creation.

(rf/reg-fx :auth.session/store
  {:doc "Stub: a real implementation would write localStorage."}
  (fn [_m _args] nil))

;; Per Spec 014 §Testing, the framework ships `:rf.http/managed-canned-success`
;; and `:rf.http/managed-canned-failure` fxs that synthesise the canonical reply
;; shape. The wrappers below pin the example's specific payloads — both the
;; browser demo (views.cljs installs the canned-failure override on the
;; default frame) and the headless tests (core-test.cljc reads them via
;; `:fx-overrides`) consume them.

(rf/reg-fx :auth.login/canned-success
  {:doc "Example stub: every `:rf.http/managed` call resolves :success with a
         canned user/token payload. Delegates to the framework-shipped
         `:rf.http/managed-canned-success` per Spec 014 §Testing."}
  (fn [frame-ctx args-map]
    (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
      (stub frame-ctx (assoc args-map :value {:user  {:id "test-user"}
                                              :token "test-token"})))))

(rf/reg-fx :auth.login/canned-failure
  {:doc "Example stub: every `:rf.http/managed` call resolves :failure.
         Delegates to the framework-shipped `:rf.http/managed-canned-failure`
         per Spec 014 §Testing."}
  (fn [frame-ctx args-map]
    (let [stub (registrar/handler :fx :rf.http/managed-canned-failure)]
      (stub frame-ctx (assoc args-map
                             :kind :rf.http/http-4xx
                             :tags {:message "bad creds" :status 401})))))

;; ============================================================================
;; REGISTRATION — chapter §Wiring a machine into the rest of re-frame
;; ============================================================================
;;
;; Two equivalent forms; we use the convenience `reg-machine`. The
;; longer form `(reg-event machine-id (make-machine-handler m))`
;; is what reg-machine wraps, and is the form to use when you need
;; registration metadata (`:doc`, `:interceptors`, ...).

(rf/reg-machine :auth.login/flow login-flow)

;; ============================================================================
;; SUBSCRIPTIONS — chapter §Reading a machine: sub-machine
;; ============================================================================

;; The machine snapshot lives in runtime-db at
;; [:rf.runtime/machines :snapshots :auth.login/flow] (per Spec 005).
;; The framework ships `:rf/machine` as the canonical layer-3 entry
;; point onto that path (see re-frame.machines §framework-shipped subs);
;; the named subs below chain off it to project out the convenient
;; pieces. The "in :submitting?" / "in :authed?" / "in :locked-out?"
;; predicates moved to the `rf/machine-has-tag?` queries in views.cljs
;; (ch.12 §State tags) — discriminating on the machine's runtime-projected
;; `:tags` set decouples view code from individual state-keyword identity.

(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [machine _] (:state machine)))

(rf/reg-sub :auth.login/error
  :<- [:rf/machine :auth.login/flow]
  (fn [machine _] (get-in machine [:data :error])))

;; The chapter's headless tests (pure machine-transition + full-drain
;; scenarios) live in re-frame.examples-test (implementation/core/test/),
;; folded inline as the `state-machine-walkthrough-runs-headless` deftest
;; (rf2-cd2zo), keeping this example source pure demonstrative code (the
;; example tree is test-free, rf2-8cevm). They run on the JVM.
