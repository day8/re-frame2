(ns login.core
  "Worked end-to-end example: a login feature.

   The one idea: a feature's whole lifecycle — idle, submitting, error,
   success, lockout — is modelled as a single state machine rather than a
   scatter of boolean flags. Five states, named transitions, a `:data`
   slot for the attempt counter; you are always in exactly one state, and
   every legal move is readable off the transition table below. See the
   machines guide (docs/machines/concepts.md) and its glossary
   (docs/machines/glossary.md).

   It shows how the parts fit together:
   - State machine — the login flow as a transition table, read like any
     derived state through a subscription on the machine id.
   - State tags — `:auth/busy`, `:auth/authenticated`, `:auth/locked`.
     Views ask `(rf/machine-has-tag? :auth.login/flow ...)` instead of
     enumerating exact state names (docs/machines/glossary.md#state-tag).
     The terminal `:locked-out` state shows as a non-interactive
     locked-account panel, not a dead-but-enabled form.
   - Form slice — the email/password draft is an app-db slice at
     [:auth :login-form]. The slice owns the draft (controlled inputs,
     no view-local atom); the machine owns submit/auth status. This is
     the form recipe in docs/guide/how-to/build-a-form.md.
   - Managed HTTP — `:rf.http/managed` plus a per-app demo stub that
     resolves the request locally so the example runs without a backend
     (docs/resources/http.md).
   - Schemas, events, subscriptions, registered views — the everyday
     building blocks (docs/guide/glossary.md).

   In a real codebase this single file would split into
   login/schema.cljc, events.cljs, subs.cljs, views.cljs, machines.cljs.
   It is kept as one file here for brevity.

   Examples are test-free; login's behaviour is covered by the substrate
   contract suite (`npm run test:cljs`) and the framework gates."
  ;; This example runs on stock Reagent (`reagent.dom.client` + the
  ;; `re-frame.adapter.reagent` adapter). login is the cross-substrate
  ;; reference base: it is mirrored 1:1 as `login-uix` and `login-helix`,
  ;; so keeping it on the reference substrate makes the three variants a
  ;; clean apples-to-apples comparison.
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            ;; Installs the Malli validator adapter so the machine's
            ;; `[:schemas :data]` validation resolves. (This example
            ;; attaches no app-db schema; machine snapshots are
            ;; runtime-db, not app-db.)
            [re-frame.schemas]
            ;; Enables state machines: registers the hooks that make
            ;; `rf/reg-machine` and the `:rf/machine` subscription work.
            [re-frame.machines]
            ;; Registers `:rf.http/managed` and family. Without this
            ;; require, dispatching `:rf.http/managed` would fail loud.
            [re-frame.http.managed]
            ;; Registers the canned-success / canned-failure stub fxs the
            ;; demo stub delegates to. This require is the opt-in
            ;; (docs/guide/how-to/test-a-cascade.md).
            [re-frame.http.test-support]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; SCHEMAS
;; ============================================================================
;;
;; Schemas are open by default — they describe a shape without forbidding
;; extra keys. See docs/guide/how-to/validate-with-schemas.md.

;; The submit-event payload — the credentials the view collects from the
;; form. The regex / min-length checks describe the shape the machine's
;; submit handler relies on. The password is never written to app-db or
;; the machine `:data`; its only off-box path is the HTTP request body,
;; redacted by the `:sensitive? true` flag on the managed-HTTP call in
;; `:issue-request` below (docs/resources/http.md, "Keeping secrets out
;; of the trace").
(def Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

;; Event-vector schema for the :auth.login/flow machine. A malformed
;; event vector is rejected at the boundary and the handler never runs.
;; The login form is the user-facing boundary, so the :submit payload is
;; validated strictly against `Credentials`. The other sub-events
;; (:dismiss, :success, :failure) come from the machine itself, so their
;; tail is admitted loosely.
;;
;; The :submit branch is a `:tuple`, not a `:cat`: the outer `:cat`
;; consumes the nested sub-event vector as a single element, so the branch
;; must match that one element as a vector. A short-password or bad-email
;; submit is then rejected at the boundary before the machine transitions
;; or issues the login HTTP request.
;;
;; The trailing `[:? :any]` admits the managed-HTTP reply: the framework
;; appends the reply map (`{:kind ... :value ...}` /
;; `{:kind ... :failure ...}`) as the last arg of the `:on-success` /
;; `:on-failure` event vector, so a delivered reply has three top-level
;; elements. Without the optional slot every reply would be rejected and
;; the flow would strand in `:submitting`.
(def AuthLoginEvent
  [:cat [:= :auth.login/flow]
   [:or
    [:tuple [:= :auth.login/submit] Credentials]
    [:cat [:enum :auth.login/dismiss :auth.login/success :auth.login/failure]
     [:* :any]]]
   [:? :any]])

;; A machine's live value is a snapshot — its current state plus a `:data`
;; map — held in runtime-db, not app-db (docs/machines/glossary.md#snapshot).
;; A machine's `[:schemas :data]` validates the `:data` slot only, not the
;; whole snapshot. So this schema describes the `:data` map (`:attempts` +
;; `:error`) the machine seeds and the actions evolve; it is attached via
;; the `[:schemas :data]` slot on the spec below.
(def AuthLoginData
  [:map
   [:attempts {:default 0} :int]
   [:error    [:maybe :string]]])

;; Snapshots live in runtime-db, so app-db schemas (`reg-app-schema`) do
;; not apply to them. The machine's own `[:schemas :data]` is the
;; snapshot-validation surface.

;; ============================================================================
;; FX  (managed HTTP + a per-app demo stub)
;; ============================================================================
;;
;; HTTP requests go via `:rf.http/managed` (docs/resources/http.md). The
;; login flow would normally POST `/api/login`, but this example ships no
;; backend. So we register a demo stub at `:auth.login.demo/managed-stub`
;; and override `:rf.http/managed` to it on the frame in `run`. The stub
;; inspects the request body's `:password` and synthesises a success or
;; failure reply via the framework's canned-success / canned-failure fxs,
;; so the real reply shape is preserved end to end.
;;
;; `:auth.session/store` is client-only — localStorage is a browser API.

(def good-password "correct-horse")

(rf/reg-fx :auth.session/store
  {:doc       "Persist the session token in localStorage. Client only."
   :platforms #{:client}}
  (fn fx-auth-session-store [_m {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (.setItem ls "auth/token" token))))

(rf/reg-fx :auth.login.demo/managed-stub
  {:doc       "Demo override for `:rf.http/managed`: routes by URL and
               request body to canned login responses so the example
               runs standalone without a backend.

               POST /api/login with `:password good-password` → success
                 with `{:user {...} :token \"demo-token-123\"}`.
               POST /api/login otherwise → 401 failure.
               Anything else (e.g. /api/auth/lock) → empty success.

               Delegates to the framework's canned-success / canned-failure
               fxs. The `:after-ms 50` defers the reply by 50 ms (via
               `:dispatch-later`, so it stays on the tape and is
               time-travel-safe — not a raw `js/setTimeout`). The delay
               lets the `:submitting` UI state be visible. The reply
               reaches the `:auth.login/success` / `:auth.login/failure`
               sub-events through the `:on-success` / `:on-failure` form."
   :platforms #{:server :client}}
  (fn fx-managed-login-demo [frame-ctx args-map]
    (let [{:keys [url body]} (:request args-map)
          login? (= "/api/login" url)]
      (cond
        (and login? (= good-password (:password body)))
        (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
          (stub frame-ctx (assoc args-map
                                 :after-ms 50
                                 :value {:user  {:id    (random-uuid)
                                                 :email (:email body)}
                                         :token "demo-token-123"})))

        login?
        (let [stub (registrar/handler :fx :rf.http/managed-canned-failure)]
          (stub frame-ctx (assoc args-map
                                 :after-ms 50
                                 :kind :rf.http/http-4xx
                                 :tags {:status  401
                                        :message "Invalid credentials."})))

        :else
        (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
          (stub frame-ctx (assoc args-map :after-ms 50 :value {})))))))

;; ============================================================================
;; STATE MACHINE
;; ============================================================================
;;
;; The login flow is a finite state machine: five states, named events.
;; The spec map below is inert data — a description of legal moves. Its
;; live value is the snapshot ({:state … :data …}) in runtime-db, read
;; like any derived state. Guards and actions live in the machine's
;; :guards / :actions maps and are referenced by keyword from the
;; transition table; resolution is machine-local. See
;; docs/machines/concepts.md.

;; The login flow's machine spec. The spec carries no :id — the machine's
;; id is the registration id below.
(def auth-login-machine
  {:initial :idle
   ;; `[:schemas :data]` validates the snapshot's `:data` slot. Malformed
   ;; `:data` (e.g. a non-string `:error`) fails the run loud.
   :schemas {:data AuthLoginData}
   :data    {:attempts 0 :error nil}

     :guards
     {:under-retry-limit
      ;; True if the flow has had fewer than 3 prior failed attempts.
      (fn [{data :data}]
        (< (:attempts data) 3))}

     :actions
     {:clear-error
      ;; Reset error and prepare to submit.
      (fn [_]
        {:data {:error nil}})

      :issue-request
      ;; Issue the login HTTP request. Returns effects, not side-effects.
      ;; The runtime appends the reply (`{:kind :success :value ...}` /
      ;; `{:kind :failure :failure ...}`) as the last arg of the
      ;; :on-success / :on-failure events.
      (fn [{[_ creds] :event}]
        {:fx [[:rf.http/managed
               ;; `:sensitive? true` redacts the request body (carrying
               ;; the password) and all params from every `:rf.http/*`
               ;; trace event — the wire scrub for this request
               ;; (docs/resources/http.md, "Keeping secrets out of the
               ;; trace").
               {:request    {:method :post
                             :url    "/api/login"
                             :body   creds
                             :request-content-type :json
                             :sensitive? true}
                :decode     :json
                :on-success [:auth.login/flow [:auth.login/success]]
                :on-failure [:auth.login/flow [:auth.login/failure]]}]]})

      :record-error
      ;; Record the failure into :data and bump the attempt counter.
      (fn [{data :data [_ {:keys [failure]}] :event}]
        {:data (-> data
                   (update :attempts inc)
                   (assoc :error (or (:message failure) "Login failed.")))})

      :lock-account
      ;; Mark the account as locked after too many failed attempts.
      ;; Fire-and-forget: the lockout POST wants no reply folded back into
      ;; the machine, so `:on-success nil` / `:on-failure nil` silence
      ;; both reply branches. (Without them the default would dispatch a
      ;; reply event that `AuthLoginEvent` rejects.)
      (fn [_]
        {:fx [[:rf.http/managed
               {:request    {:method :post :url "/api/auth/lock"}
                :on-success nil
                :on-failure nil}]]})

      :store-session
      ;; Persist the session token returned by a successful login.
      (fn [{[_ {:keys [value]}] :event}]
        {:fx [[:auth.session/store {:token (:token value)}]]})}

     :states
     {:idle
      {:on {:auth.login/submit {:target :submitting
                                :action :clear-error}}}

      :submitting
      ;; State tag — ask, don't tell. Views query (rf/machine-has-tag?
      ;; :auth.login/flow :auth/busy) to disable inputs and re-label the
      ;; submit button, rather than asking "are we exactly :submitting?".
      ;; Add another busy state tagged :auth/busy and no view changes.
      {:tags  #{:auth/busy}
       :entry :issue-request
       ;; The failure branch is an ORDERED list of candidate transitions:
       ;; the first whose :guard passes fires. Under the retry limit → show
       ;; the error and let them retry; otherwise (no guard = fallthrough)
       ;; → lock the account. The whole retry-then-lockout policy is here.
       :on    {:auth.login/success {:target :authed
                                    :action :store-session}
               :auth.login/failure [{:target :error-shown
                                     :guard  :under-retry-limit
                                     :action :record-error}
                                    {:target :locked-out
                                     :action :lock-account}]}}

      :error-shown
      ;; Direct retry from :error-shown re-enters :submitting and must clear the
      ;; prior `:error` first — otherwise the obsolete failure message stays
      ;; visible (the view renders `:auth.login/error` whenever non-nil) as if it
      ;; still applied to the request now in flight. Same `:clear-error` action
      ;; as the :idle entry transition.
      {:on {:auth.login/dismiss {:target :idle}
            :auth.login/submit  {:target :submitting
                                 :action :clear-error}}}

      :authed
      ;; :auth/authenticated tag — the banner swaps to "Welcome!" once
      ;; the flow reaches this terminal state.
      {:tags #{:auth/authenticated}
       :meta {:terminal? true}}

      :locked-out
      ;; :auth/locked tag — after the fourth failed submit the flow lands
      ;; in this terminal state. Views query
      ;; (rf/machine-has-tag? :auth.login/flow :auth/locked) to swap the
      ;; form for a locked-account panel and refuse further submits. A
      ;; terminal lockout must be visible and non-interactive, not a live
      ;; form.
      {:tags #{:auth/locked}
       :meta {:terminal? true}}}})

;; Register the machine as the `:auth.login/flow` event handler.
;;
;; This machine also validates its dispatched event vector, so the opts
;; map carries the event `:schema` (the boundary on the outer vector)
;; alongside the machine spec.
(rf/reg-machine :auth.login/flow
  {:doc    "Login flow: idle → submitting → authed / error-shown / locked-out."
   :schema AuthLoginEvent}
  auth-login-machine)

;; ============================================================================
;; EVENTS
;; ============================================================================
;;
;; The machine handler is self-initialising: its `:initial` state and
;; `:data` seed the snapshot when the machine first runs. No separate
;; machine :initialise event is needed.
;;
;; The form-slice events below are the other half of the "machine + slice"
;; split. The slice owns the draft — what the user is currently typing —
;; at app-db [:auth :login-form]; the machine owns submit/auth status. A
;; form's draft is application state, so it lives in app-db (read via subs,
;; written via events), not in a view-local atom. Inputs are controlled:
;; `:value` reads the draft sub, `:on-change` dispatches
;; `:auth.login/edit-field`. This is the form recipe in
;; docs/guide/how-to/build-a-form.md.
;;
;; This block — slice defaults, form events, and the draft/slice subs
;; below — is substrate-agnostic: it is identical across the reagent, uix,
;; and helix login examples. Only the view syntax differs.

;; The login form's default (empty) draft. The draft's value shape is
;; `Credentials` (email regex + min-8 password) — the same schema the
;; machine's `:submit` boundary enforces.
(def login-form-defaults {:email "" :password ""})

;; Seed the slice to its standard Pattern-Forms shape. Dispatched once at
;; boot (from `run`). `:draft` to the empty defaults; `:status :idle`.
(rf/reg-event :auth.login/initialise-form
  {:doc "Seed the login-form slice at [:auth :login-form] to its standard
         Pattern-Forms shape (empty draft, :idle status)."}
  (fn handler-login-form-initialise [{:keys [db]} _]
    {:db (assoc-in db [:auth :login-form]
                   {:draft             login-form-defaults
                    :submitted         nil
                    :submit-attempted? false
                    :status            :idle
                    :errors            {}
                    :touched           #{}
                    :submit-error      nil})}))

;; The user changed a single field. Update `:draft` and add the field to
;; `:touched`. This is the ONLY thing an input's `:on-change` does — it never
;; sets view-local state. The `:schema` rejects a malformed edit-field vector
;; at the `:where :event` boundary.
(rf/reg-event :auth.login/edit-field
  {:doc    "Controlled-input edit: write one field into the login-form draft
            and mark it touched."
   :schema [:cat [:= :auth.login/edit-field] :keyword :string]}
  (fn handler-login-form-edit-field [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in  [:auth :login-form :draft field] value)
             (update-in [:auth :login-form :touched] (fnil conj #{}) field))}))

;; Submit. Reads the draft out of the slice, latches `:submit-attempted?`,
;; flips the slice `:status` to `:submitting`, and dispatches the draft into
;; the machine — the only point the draft crosses into the machine (and is
;; validated against `Credentials` at the event boundary). The machine, not
;; the slice, then owns the in-flight / authed / error / locked status; the
;; slice `:status` is a mirror.
;;
;; Secret-field hygiene: the handler reads the password off the draft, hands
;; it to the machine, then clears `[:draft :password]` in the same commit.
;; Once the request is in flight the secret has done its job, so it does not
;; sit in app-db waiting for the next snapshot or recorder capture. The
;; password's only off-box path is the HTTP request body (scrubbed by the
;; `:sensitive? true` flag); it is never written to `:submitted` or the
;; machine `:data`. See docs/guide/how-to/add-auth.md.
(rf/reg-event :auth.login/submit-form
  {:doc "Submit the login form: read the draft from the slice, dispatch it
         into the :auth.login/flow machine's :submit sub-event, and clear the
         password out of the draft (secret-field hygiene)."}
  (fn handler-login-form-submit [{:keys [db]} _]
    (let [draft (get-in db [:auth :login-form :draft])]
      {:db (-> db
               (assoc-in [:auth :login-form :submit-attempted?] true)
               (assoc-in [:auth :login-form :status] :submitting)
               (assoc-in [:auth :login-form :draft :password] ""))
       :fx [[:dispatch [:auth.login/flow [:auth.login/submit draft]]]]})))

;; Reset the slice back to its initial (empty, :idle) shape.
(rf/reg-event :auth.login/reset-form
  {:doc "Clear the login-form slice back to empty defaults / :idle."}
  (fn handler-login-form-reset [{:keys [db]} _]
    {:db (assoc-in db [:auth :login-form]
                   {:draft             login-form-defaults
                    :submitted         nil
                    :submit-attempted? false
                    :status            :idle
                    :errors            {}
                    :touched           #{}
                    :submit-error      nil})}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

;; These named subs project the convenient pieces out of the machine
;; snapshot. The "in :submitting?" / "in :authed?" predicates instead live
;; as `rf/machine-has-tag?` queries in the views below
;; (docs/machines/glossary.md#state-tag).

;; Read the snapshot through the framework `:rf/machine` sub — the public
;; surface for a machine's live value (docs/machines/glossary.md#snapshot).
(rf/reg-sub :auth.login/state
  {:doc "Current state of the login flow."}
  :<- [:rf/machine :auth.login/flow]
  (fn sub-auth-login-state [snapshot _]
    (:state snapshot)))

(rf/reg-sub :auth.login/error
  {:doc "Current error message, if any."}
  :<- [:rf/machine :auth.login/flow]
  (fn sub-auth-login-error [snapshot _]
    (get-in snapshot [:data :error])))

;; --- Form-slice subs (substrate-agnostic) ----------------------------------
;;
;; The login-form slice lives at app-db [:auth :login-form]. The view reads
;; the draft through `:auth.login/draft` and binds each input's `:value` to
;; it — controlled inputs. The per-field-error sub follows the form
;; visibility rule (touched OR submit-attempted?). These subs are shared
;; identically across the three substrate examples.

(rf/reg-sub :auth.login/form-slice
  {:doc "The whole login-form slice at [:auth :login-form]."}
  (fn sub-auth-login-form-slice [db _]
    (get-in db [:auth :login-form])))

(rf/reg-sub :auth.login/draft
  {:doc "The login-form draft — what the user has currently typed. Each input
         binds its :value to a field of this map (controlled inputs)."}
  :<- [:auth.login/form-slice]
  (fn sub-auth-login-draft [slice _]
    (:draft slice)))

(rf/reg-sub :auth.login/field-error
  {:doc "Per-field validation error for the login form. Reveal a field's
         error once it is :touched OR once the form has had its first
         submit click (docs/guide/how-to/build-a-form.md)."}
  :<- [:auth.login/form-slice]
  (fn sub-auth-login-field-error [slice [_ field]]
    (when (or (:submit-attempted? slice)
              (contains? (:touched slice) field))
      (first (get-in slice [:errors field])))))

;; ============================================================================
;; VIEWS
;; ============================================================================
;;
;; `reg-view` injects `dispatch` / `subscribe` as locals inside the view
;; body, bound to the frame the view renders under — so the same view can
;; mount in several isolated frames at once (docs/guide/concepts/views.md).

;; Controlled-input login form. The form holds no view-local state: there
;; is no `reagent.core/atom`. Each input's `:value` reads the draft from the
;; `:auth.login/draft` sub, and `:on-change` dispatches
;; `:auth.login/edit-field`. Submit dispatches `:auth.login/submit-form`,
;; which reads the draft from app-db and hands it to the machine. The
;; in-flight / error state comes from the machine (the `:auth/busy` tag and
;; the `:auth.login/error` sub) — the slice owns the draft, the machine
;; owns submit/auth status.
(reg-view ^{:doc "The login form view: email + password + submit button + error display."}
          login-form []
  (let [draft @(subscribe [:auth.login/draft])
        busy? @(rf/machine-has-tag? :auth.login/flow :auth/busy)
        err   @(subscribe [:auth.login/error])]
    [:form.login-form
     {:data-testid "login-form"
      :on-submit (fn [e]
                   (.preventDefault e)
                   (when-not busy?
                     (dispatch [:auth.login/submit-form])))}
     [:input  {:type        "email"
               :placeholder "Email"
               :disabled    busy?
               :data-testid "login-email"
               :value       (:email draft)
               :on-change   #(dispatch [:auth.login/edit-field :email (.. % -target -value)])}]
     [:input  {:type        "password"
               :placeholder "Password"
               :disabled    busy?
               :data-testid "login-password"
               :value       (:password draft)
               :on-change   #(dispatch [:auth.login/edit-field :password (.. % -target -value)])}]
     [:button {:type "submit" :disabled busy?
               :data-testid "login-submit"}
      (if busy? "Signing in…" "Sign in")]
     (when err [:p.error {:data-testid "login-error"} err])]))

;; Terminal lockout panel — rendered once the flow reaches :locked-out
;; (tagged :auth/locked). The state has no transitions, so the form is
;; swapped out entirely rather than left enabled-but-dead.
(reg-view ^{:doc "Locked-account panel shown when the login flow reaches :locked-out."}
          locked-panel []
  [:div.locked {:data-testid "locked-panel"}
   [:h2 "Account locked"]
   [:p "Three failed attempts. Contact support to unlock."]])

(reg-view ^{:doc "Shows the user's logged-in state and a sign-out button."}
          login-banner []
  (let [authed? @(rf/machine-has-tag? :auth.login/flow :auth/authenticated)
        locked? @(rf/machine-has-tag? :auth.login/flow :auth/locked)]
    [:div.banner {:data-testid "login-banner"}
     (cond
       authed? [:span "Welcome!"]
       locked? [locked-panel]
       :else   [login-form])]))

(reg-view root-view []
  [:div.app
   [:h1 "Sign in"]
   [login-banner]])

;; ============================================================================
;; MOUNT  (CLJS reference; client-only)
;; ============================================================================

;; The React root is held in an atom and created lazily inside `run`, not
;; at ns-load: loading the namespace must produce no DOM side effects, so
;; co-required example namespaces don't race `create-root` onto `#app`.
(defonce react-root (atom nil))

(defn run []
  ;; Install the Reagent adapter. Pass its spec map straight to init!.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; One-spot frame setup. The `frame-provider` ensure shape (`{:id …}`)
    ;; creates `:rf/default` on first mount, applies the config below, runs
    ;; `:initial-events` once, and scopes the frame into React. On hot
    ;; reload it reuses the existing frame and skips re-seeding.
    ;; See docs/guide/concepts/frames.md.
    ;;
    ;; - `:fx-overrides` routes `:rf.http/managed` to the in-process login
    ;;   stub above so the example runs standalone — no backend required.
    ;; - `:initial-events` seeds the login-form slice so the controlled
    ;;   inputs read a real (empty-string) draft from the first render. An
    ;;   uninitialised draft would feed React `nil` :values — uncontrolled
    ;;   inputs. The machine self-initialises; the slice is app-db, so it is
    ;;   seeded here.
    ;;
    ;; The provider scope is what makes the injected `dispatch`/`subscribe`
    ;; (and the machine reads) resolve to `:rf/default`. A `reg-view`
    ;; rendered with no provider fails loud.
    (rdc/render @react-root
                [rf/frame-provider {:id             :rf/default
                                    :doc            "Login demo frame."
                                    :fx-overrides   {:rf.http/managed :auth.login.demo/managed-stub}
                                    :initial-events [[:auth.login/initialise-form]]}
                 [root-view]])))
