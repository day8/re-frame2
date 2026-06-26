(ns state-machine-walkthrough.core
  "Runnable companion to docs/machines/concepts.md — the machines chapter as code.

  The chapter's login flow, laid out so you can read the transition table
  here in the order the chapter introduces it. The one idea this file makes
  tangible: a machine is a PURE transition table, so a transition is a pure
  function call — table in, snapshot in, event in; result out. That is what
  lets the chapter's four scenarios run headless (see the §SUBSCRIPTIONS note
  at the bottom for where those tests live).

  Why .cljc: the chapter promises 'runs in microseconds on the JVM, no
  browser, no network.' The same source compiles under shadow-cljs for the
  CLJS surface; the :clj branch is what the JVM test (require ...)s. The HTTP
  side never hits the wire — it is redirected via :fx-overrides to this
  example's `:auth.login/canned-success` / `:auth.login/canned-failure`
  wrapper fxs below, which delegate to the framework-shipped
  `:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure`
  stubs (Spec 014 §Testing).

  Read alongside docs/machines/concepts.md."
  (:require [re-frame.core :as rf]
            ;; The Spec 005 state-machine ns lives in the
            ;; day8/re-frame2-machines artefact. Loading the ns here
            ;; registers its late-bind hooks so `rf/reg-machine` and the
            ;; framework `:rf/machine` / `:rf/machine-has-tag?` subs resolve.
            ;; (The pure `machine-transition` fn the headless tests call is
            ;; owned by re-frame.machines, not the rf/ facade — by design.)
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
;; THE TRANSITION TABLE — chapter §The same flow as a transition table
;; ============================================================================
;;
;; Pure data. `:guards` and `:actions` live with the spec — there is no
;; global registry. References inside `:states` resolve against this
;; map; cross-machine reuse is via Clojure vars (define a fn, name it
;; locally in each machine's :guards / :actions).
;;
;; This is a near-twin of the login machine in the `login` example
;; (examples/reagent/login/core.cljs) — same states, guards, actions, and
;; transitions, and both tag `:locked-out` with `:auth/locked` and render a
;; dedicated lockout panel that branches on that tag (here the root-view in
;; views.cljs; there the login-banner). The two register the id
;; `:auth.login/flow` independently — a machine id is a per-frame registry key,
;; not a global handle, and the two never co-load (this walkthrough runs
;; JVM-headless with `remove-ns` between runs; login builds standalone), so the
;; shared name is parallel, not a collision.
;; Where the two examples diverge is what they teach AROUND the machine:
;; `login` is the full feature scaffold (Malli event/`:data` schemas, a
;; `:sensitive?` request flag, a password-routing demo stub) wired into a live
;; Reagent feature; this walkthrough strips that down to drive the machine
;; HEADLESSLY, foregrounding the pure machine-transition + drain testing story
;; from docs/machines/concepts.md. Read `login` first for the UI wiring; read
;; this for the testing progression.

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
    ;; while the request is in flight (chapter §State tags).
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
;; FX — chapter §Composing with async effects
;; ============================================================================
;;
;; The `:auth.session/store` fx is a stub for the example: it shows the
;; shape, not real localStorage. The headless tests exercise the
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
;; default frame) and the headless tests (the framework JVM deftest reads them
;; via `:fx-overrides`) consume them, sharing one registration point.

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
;; REGISTRATION — chapter §Registering and running it
;; ============================================================================
;;
;; One line. A machine IS an event handler: `reg-machine` is sugar over
;; `reg-event` whose body interprets the table. The longer form
;; `(reg-event machine-id (make-machine-handler m))` is what reg-machine
;; wraps, and is the form to reach for when you need registration metadata
;; (`:doc`, `:interceptors`, ...).

(rf/reg-machine :auth.login/flow login-flow)

;; ============================================================================
;; SUBSCRIPTIONS — chapter §Registering and running it
;; ============================================================================

;; The machine snapshot lives in runtime-db at
;; [:rf.runtime/machines :snapshots :auth.login/flow] (per Spec 005).
;; The framework ships `:rf/machine` as the canonical entry point onto
;; that path; the two named subs below chain off it to project out the
;; convenient pieces (current state, last error). The "is it busy / locked?"
;; questions the view actually asks are NOT here — they live as the
;; `rf/machine-has-tag?` queries in views.cljs (chapter §State tags),
;; because discriminating on the snapshot's runtime-projected `:tags` set
;; decouples view code from individual state-keyword identity.

(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [machine _] (:state machine)))

(rf/reg-sub :auth.login/error
  :<- [:rf/machine :auth.login/flow]
  (fn [machine _] (get-in machine [:data :error])))

;; The payoff — see the chapter's §Testing. Because the table is pure data
;; and `machines/machine-transition` is a pure fn over (table, snapshot,
;; event), the four scenarios (pure happy-path, pure lockout, drain
;; happy-path, drain retry-then-lockout) need no frame and no browser. They
;; live in re-frame.examples-test (implementation/core/test/) as the
;; `state-machine-walkthrough-runs-headless` deftest — folded there so this
;; example source stays test-free. JVM, microseconds.
