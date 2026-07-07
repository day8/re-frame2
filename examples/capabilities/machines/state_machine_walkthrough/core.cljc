(ns state-machine-walkthrough.core
  "The machines guide's login flow, in working code.

  Read this side by side with docs/machines/concepts.md — the transition
  table below follows the exact order that page introduces it, so you can
  scroll the two together.

  Here's the one idea this file makes you feel in your hands: a machine is
  just a PURE transition table, which means a transition is just a pure
  function call. Table in, snapshot in, event in; next snapshot out. No
  hidden state, no clock, no DOM. That purity is the whole payoff — it lets
  the four login scenarios run headless on the JVM, in microseconds, with
  no browser anywhere in sight. (Those tests live in the framework test
  tree; see the SUBSCRIPTIONS note at the bottom for where and why.)

  This is a .cljc, which is the trick that makes that possible: the very
  same source compiles under shadow-cljs for the browser demo AND loads on
  the JVM for the headless tests. The login request never actually touches
  the network — :fx-overrides quietly swaps it for the canned stubs defined
  below."
  (:require [re-frame.core :as rf]
            ;; Pulls in the machine machinery: `rf/reg-machine`, the
            ;; `:rf/machine` / `:rf/machine-has-tag?` subs, and the pure
            ;; `machine-transition` fn that the headless tests call directly.
            ;; See docs/machines/glossary.md#machine.
            [re-frame.machines]
            ;; Registers `:rf.http/managed`, the fx our `:issue-request`
            ;; action fires to make the login call. Tests intercept it via
            ;; :fx-overrides; see docs/core/glossary.md#effect.
            [re-frame.http.managed]
            ;; Brings in the canned `:rf.http/managed-canned-*` fxs — the
            ;; pre-baked replies our stubs below delegate to.
            [re-frame.http.test-support]
            ;; ...and those stubs find the canned fxs through the registrar.
            [re-frame.registrar :as registrar]))

;; ============================================================================
;; THE TRANSITION TABLE — guide §A machine at a glance
;; ============================================================================
;;
;; It's all just data. The `:guards` and `:actions` sit right here in the table,
;; and the names mentioned inside `:states` resolve against this same map —
;; there's no global guard/action registry off in some other file. Want to reuse
;; a guard or action across machines? Write the fn once and name it locally in
;; each machine's :guards / :actions. Locality over magic.
;;
;; Its near-twin lives next door: the `login` example
;; (examples/core/login/core.cljs) has the same states, guards, actions, and
;; transitions. What differs is what each example teaches AROUND the machine.
;; `login` is the full feature scaffold — Malli schemas, a sensitive-request
;; flag, a password-routing stub — all wired into a living Reagent feature. This
;; walkthrough strips that away to drive the machine HEADLESSLY and put the
;; pure-transition testing story centre stage. Read `login` for the UI wiring;
;; read this one for the testing progression.

(def login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   :guards
   {:under-retry-limit
    ;; A guard is a pure yes/no question asked of one map: {:data ... :event ...}.
    ;; `data` is the snapshot's :data slot, handed to you directly. Say yes here
    ;; and the transition fires; say no and the machine looks at the next option.
    ;; See docs/machines/glossary.md#guard.
    (fn [{data :data}]
      (< (:attempts data) 3))}

   :actions
   {:clear-error
    (fn [_] {:data {:error nil}})

    :issue-request
    ;; An action describes effects; it never performs side-effects itself
    ;; (docs/core/glossary.md#effect). Here it asks for the `:rf.http/managed`
    ;; fx, which makes the request and then dispatches `:on-success` /
    ;; `:on-failure` with the reply appended as the last arg — looping the
    ;; result right back into this machine as the next event.
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
    ;; rf2-ibksxg — the classified failure map rides under :error.
    (fn [{data :data [_ {:keys [error]}] :event}]
      {:data (-> data
                 (update :attempts inc)
                 (assoc :error (or (:message error) "Login failed.")))})

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
    ;; The :auth/busy tag is how the view knows to dim the inputs and re-label
    ;; the button while the request is in flight — it asks
    ;; @(rf/subscribe [:rf/machine-has-tag? :auth.login/flow :auth/busy]) rather than checking for
    ;; this exact state by name. Ask what's true, not where you are.
    ;; See docs/machines/glossary.md#state-tag.
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
    ;; Journey's end, happy path. The :auth/authenticated tag rides along once
    ;; the flow lands here, and :terminal? marks it as a final state.
    {:tags #{:auth/authenticated}
     :meta {:terminal? true}}

    :locked-out
    ;; Journey's end, unhappy path. Seeing the :auth/locked tag is the cue for
    ;; root-view to swap the form out for the locked-out panel.
    {:tags #{:auth/locked}
     :meta {:terminal? true}}}})

;; ============================================================================
;; FX — guide §An action returns effects
;; ============================================================================
;;
;; `:auth.session/store` is a stub — it shows you the SHAPE of the effect without
;; the real localStorage write, which keeps the walkthrough honest and headless.
;; The login request, meanwhile, runs through the canned stubs below, slotted in
;; at frame creation via :fx-overrides so no real HTTP ever happens.

(rf/reg-fx :auth.session/store
  {:doc "Stub: the real thing would write the session token to localStorage."}
  (fn [_m _args] nil))

;; The framework already ships `:rf.http/managed-canned-success` and
;; `:rf.http/managed-canned-failure` — fxs that fabricate a reply in the
;; canonical shape. The two thin wrappers below just pin THIS example's
;; payloads on top. Both the browser demo and the headless tests point
;; `:rf.http/managed` at them.

(rf/reg-fx :auth.login/canned-success
  {:doc "Example stub: pretend every login succeeds, handing back a canned
         user/token. Defers the heavy lifting to the framework-shipped
         `:rf.http/managed-canned-success` — we just supply the payload."}
  (fn [frame-ctx args-map]
    (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
      (stub frame-ctx (assoc args-map :value {:user  {:id "test-user"}
                                              :token "test-token"})))))

(rf/reg-fx :auth.login/canned-failure
  {:doc "Example stub: pretend every login fails with a 401. Defers to the
         framework-shipped `:rf.http/managed-canned-failure` — we just supply
         the failure shape. This is the stub the lockout demo wires in."}
  (fn [frame-ctx args-map]
    (let [stub (registrar/handler :fx :rf.http/managed-canned-failure)]
      (stub frame-ctx (assoc args-map
                             :kind :rf.http/http-4xx
                             :tags {:message "bad creds" :status 401})))))

;; ============================================================================
;; REGISTRATION — guide §Registering and running it
;; ============================================================================
;;
;; One line — and that's not a simplification, that's the whole truth. A machine
;; IS an event handler. `reg-machine` is just sugar over a `reg-event` whose body
;; happens to interpret the table above. When you need registration metadata
;; (`:doc`, `:interceptors`, ...), drop down to the longer form
;; `(reg-event machine-id (make-machine-handler m))` — same machine, more knobs.

(rf/reg-machine :auth.login/flow login-flow)

;; ============================================================================
;; FORM DRAFT SLICE — docs/core/how-to/build-a-form.md
;; ============================================================================
;;
;; Notice the clean division of labour: the machine owns the submit/auth STATUS,
;; and this slice owns the DRAFT — the email and password the user is typing.
;; A draft is just application state, so it belongs in app-db: read through a
;; sub, written through an event, never stashed in a view-local atom. That's why
;; the form's inputs are CONTROLLED off `:auth.login/draft` — `:on-change`
;; dispatches `:auth.login/edit-field`, and submit reads the draft back out of
;; the slice instead of reaching into the view.
;;
;; We keep this slice deliberately tiny — one teaching point, the draft itself.
;; The fuller seven-key form slice (touched / errors / submit-attempted? / …) is
;; built up in docs/core/how-to/build-a-form.md and in
;; examples/real-apps/realworld_http/auth.cljs once you want the real thing.

(def login-form-defaults {:email "" :password ""})

(rf/reg-event :auth.login/initialise-form
  {:doc "Seed the login draft with empty defaults before first render. This owns
         only the draft slice — the machine needs no seeding, it spawns itself
         on its very first event."}
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:auth :login-form :draft] login-form-defaults)}))

(rf/reg-event :auth.login/edit-field
  {:doc "The user touched a single field. We write it straight into the draft
         slot in app-db. The controlled input's `:on-change` dispatches this —
         and it NEVER reaches for view-local state, which is rather the point."}
  (fn [{:keys [db]} [_ field value]]
    {:db (assoc-in db [:auth :login-form :draft field] value)}))

;; ============================================================================
;; SUBSCRIPTIONS — guide §Registering and running it
;; ============================================================================

;; `:rf/machine` is the framework's canonical window onto a machine's snapshot —
;; the whole {:state … :data …} value. The two named subs below chain off it to
;; pluck out the handy pieces: the current state, and the last error. For the
;; "busy? locked?" questions the view skips the snapshot and asks the machine for
;; a tag directly with the `[:rf/machine-has-tag? …]` sub — reading the `:tags` set keeps it
;; from hard-coding individual state keywords (docs/machines/glossary.md#state-tag).

(rf/reg-sub :auth.login/draft
  {:doc "The login form draft — whatever the user has typed so far. The view's
         controlled inputs read `:value` off this, and submit reads it one more
         time to hand the creds over to the machine."}
  (fn [db _] (get-in db [:auth :login-form :draft])))

(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [machine _] (:state machine)))

(rf/reg-sub :auth.login/error
  :<- [:rf/machine :auth.login/flow]
  (fn [machine _] (get-in machine [:data :error])))

;; And here's where the purity pays off. Because the table is plain data and
;; `machines/machine-transition` is a pure fn over (table, snapshot, event), the
;; four scenarios — pure happy-path, pure lockout, drain happy-path, drain
;; retry-then-lockout — need neither a frame nor a browser. They run on the bare
;; JVM in microseconds: feed in a snapshot and an event, assert on the snapshot
;; that comes back. They live in the framework test tree as the
;; `state-machine-walkthrough-runs-headless` deftest, which is what lets this
;; example source itself stay blissfully test-free.
;; See docs/machines/concepts.md#testing-transitions-are-pure-function-calls.
