(ns state-machine-walkthrough.core
  "The machines guide's login flow, as code.

  Read alongside docs/machines/concepts.md — the transition table below is
  laid out in the order that page introduces it.

  The one idea this file makes tangible: a machine is a PURE transition
  table, so a transition is a pure function call — table in, snapshot in,
  event in; result out. That is what lets the four scenarios run headless,
  on the JVM, in microseconds. Those tests live in the framework test tree
  (see the SUBSCRIPTIONS note at the bottom).

  This is a .cljc so the same source compiles under shadow-cljs for the
  browser demo and loads on the JVM for the headless tests. The login HTTP
  request never hits the wire: :fx-overrides redirects it to the canned
  stubs defined below."
  (:require [re-frame.core :as rf]
            ;; Loading this ns makes `rf/reg-machine` and the `:rf/machine`
            ;; / `:rf/machine-has-tag?` subs resolve, and the pure
            ;; `machine-transition` fn the headless tests call. See
            ;; docs/machines/glossary.md#machine.
            [re-frame.machines]
            ;; Registers the `:rf.http/managed` fx the login flow's
            ;; `:issue-request` action dispatches. Tests override it via
            ;; :fx-overrides; see docs/guide/glossary.md#effect.
            [re-frame.http.managed]
            ;; Registers the canned `:rf.http/managed-canned-*` fxs that the
            ;; stubs below redirect to.
            [re-frame.http.test-support]
            ;; The stubs below look up the canned fxs through the registrar.
            [re-frame.registrar :as registrar]))

;; ============================================================================
;; THE TRANSITION TABLE — guide §The same flow as a transition table
;; ============================================================================
;;
;; Pure data. `:guards` and `:actions` live right here in the table, and the
;; references inside `:states` resolve against this map — there is no global
;; guard/action registry. To reuse a guard or action across machines, define a
;; fn and name it locally in each machine's :guards / :actions.
;;
;; The `login` example (examples/reagent/login/core.cljs) builds a near-twin of
;; this machine — same states, guards, actions, transitions. The two examples
;; differ in what they teach AROUND the machine. `login` is the full feature
;; scaffold (Malli schemas, a sensitive-request flag, a password-routing stub)
;; wired into a live Reagent feature. This walkthrough strips that down to drive
;; the machine HEADLESSLY, foregrounding the pure-transition testing story. Read
;; `login` for the UI wiring; read this for the testing progression.

(def login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   :guards
   {:under-retry-limit
    ;; A guard takes one map: {:data ... :event ...}. `data` is the snapshot's
    ;; :data slot directly. See docs/machines/glossary.md#guard.
    (fn [{data :data}]
      (< (:attempts data) 3))}

   :actions
   {:clear-error
    (fn [_] {:data {:error nil}})

    :issue-request
    ;; An action returns effects, not side-effects (docs/guide/glossary.md#effect).
    ;; The `:rf.http/managed` fx issues the request, then dispatches the
    ;; `:on-success` / `:on-failure` events with the reply appended as the
    ;; last arg — routing the result back into this machine.
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
    ;; :auth/busy tag — the view asks (rf/machine-has-tag? :auth.login/flow
    ;; :auth/busy) to disable inputs and re-label the submit button while the
    ;; request is in flight. See docs/machines/glossary.md#state-tag.
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
    ;; :auth/authenticated tag — set once the flow reaches this terminal state.
    {:tags #{:auth/authenticated}
     :meta {:terminal? true}}

    :locked-out
    ;; :auth/locked tag — root-view swaps the form for the locked-out panel
    ;; when this tag is set.
    {:tags #{:auth/locked}
     :meta {:terminal? true}}}})

;; ============================================================================
;; FX — guide §Composing with async effects
;; ============================================================================
;;
;; `:auth.session/store` is a stub: it shows the shape, not real localStorage.
;; The login HTTP request runs through the canned-success / canned-failure
;; stubs below, swapped in at frame creation via :fx-overrides.

(rf/reg-fx :auth.session/store
  {:doc "Stub: a real implementation would write localStorage."}
  (fn [_m _args] nil))

;; The framework ships `:rf.http/managed-canned-success` and
;; `:rf.http/managed-canned-failure` fxs that synthesise the canonical reply
;; shape. The wrappers below pin this example's specific payloads. Both the
;; browser demo and the headless tests redirect `:rf.http/managed` to them.

(rf/reg-fx :auth.login/canned-success
  {:doc "Example stub: every `:rf.http/managed` call resolves :success with a
         canned user/token payload. Delegates to the framework-shipped
         `:rf.http/managed-canned-success`."}
  (fn [frame-ctx args-map]
    (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
      (stub frame-ctx (assoc args-map :value {:user  {:id "test-user"}
                                              :token "test-token"})))))

(rf/reg-fx :auth.login/canned-failure
  {:doc "Example stub: every `:rf.http/managed` call resolves :failure.
         Delegates to the framework-shipped `:rf.http/managed-canned-failure`."}
  (fn [frame-ctx args-map]
    (let [stub (registrar/handler :fx :rf.http/managed-canned-failure)]
      (stub frame-ctx (assoc args-map
                             :kind :rf.http/http-4xx
                             :tags {:message "bad creds" :status 401})))))

;; ============================================================================
;; REGISTRATION — guide §Registering and running it
;; ============================================================================
;;
;; One line. A machine IS an event handler: `reg-machine` is sugar over a
;; `reg-event` whose body interprets the table. Reach for the longer form
;; `(reg-event machine-id (make-machine-handler m))` when you need registration
;; metadata (`:doc`, `:interceptors`, ...).

(rf/reg-machine :auth.login/flow login-flow)

;; ============================================================================
;; FORM DRAFT SLICE — docs/guide/how-to/build-a-form.md
;; ============================================================================
;;
;; The machine owns submit/auth STATUS; the slice owns the DRAFT. Form drafts
;; are application state, so the email/password the user types lives in app-db
;; — read via a sub, written via an event — never in a view-local atom. The
;; login form's inputs are CONTROLLED off `:auth.login/draft`; `:on-change`
;; dispatches `:auth.login/edit-field`, and submit reads the draft back out of
;; the slice rather than out of the view.
;;
;; This slice carries just its single teaching point — the draft. The fuller
;; seven-key form slice (touched / errors / submit-attempted? / …) is built up
;; in docs/guide/how-to/build-a-form.md and in examples/reagent/realworld/auth.cljs.

(def login-form-defaults {:email "" :password ""})

(rf/reg-event :auth.login/initialise-form
  {:doc "Seed the login draft to empty defaults. This owns only the draft slice;
         the machine spawns itself on its first event."}
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:auth :login-form :draft] login-form-defaults)}))

(rf/reg-event :auth.login/edit-field
  {:doc "User changed a single login field. Writes the draft slot in app-db —
         the controlled input's `:on-change` dispatches this; it NEVER sets
         view-local state."}
  (fn [{:keys [db]} [_ field value]]
    {:db (assoc-in db [:auth :login-form :draft field] value)}))

;; ============================================================================
;; SUBSCRIPTIONS — guide §Registering and running it
;; ============================================================================

;; The framework ships `:rf/machine` as the canonical sub onto the machine's
;; snapshot. The two named subs below chain off it to pull out the handy
;; pieces: current state and last error. For the "is it busy / locked?"
;; questions, the view asks the machine for a tag directly with
;; `rf/machine-has-tag?` — reading the `:tags` set keeps the view off
;; individual state keywords (docs/machines/glossary.md#state-tag).

(rf/reg-sub :auth.login/draft
  {:doc "The login form draft — what the user has currently typed. The view's
         controlled inputs read `:value` off this; submit reads it to hand the
         creds to the machine."}
  (fn [db _] (get-in db [:auth :login-form :draft])))

(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [machine _] (:state machine)))

(rf/reg-sub :auth.login/error
  :<- [:rf/machine :auth.login/flow]
  (fn [machine _] (get-in machine [:data :error])))

;; The payoff. Because the table is pure data and `machines/machine-transition`
;; is a pure fn over (table, snapshot, event), the four scenarios (pure
;; happy-path, pure lockout, drain happy-path, drain retry-then-lockout) need no
;; frame and no browser — they run on the JVM in microseconds. They live in the
;; framework test tree as the `state-machine-walkthrough-runs-headless` deftest,
;; so this example source stays test-free.
;; See docs/machines/concepts.md#testing-transitions-are-pure-function-calls.
