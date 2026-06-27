(ns login-uix.core
  "UIx variant of the login example — the same feature on a different substrate.

   Everything below the views is substrate-agnostic and identical to
   examples/reagent/login: the login state machine, the schemas, and the
   managed-HTTP effect. Only the view layer differs. Here views are UIx
   `defui` components that read subscriptions with the `use-subscribe` hook;
   the Reagent twin uses `reg-view`. The file shows how narrow the substrate
   boundary is — the machine, schemas, and effects don't change at all.

   The machine's states carry tags (`:auth/busy`, `:auth/authenticated`,
   `:auth/locked`), and views read them via the `:rf/machine-has-tag?`
   framework sub. The terminal `:locked-out` state renders a non-interactive
   locked-account panel.

   For the substrate-boundary mechanics — `use-subscribe`, `frame-handle`,
   `frame-provider`, and what stays the same across React wrappers — see
   docs/guide/how-to/use-uix-helix-or-slim.md."
  (:require [uix.core :as uix :refer [$ defui]]
            [uix.dom  :as uix-dom]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.schemas]
            [re-frame.machines]
            [re-frame.http.managed]
            ;; :fx-overrides redirect :rf.http/managed to :rf.http/managed-canned-*;
            ;; the canned-stub fx ids register from re-frame.http.test-support.
            [re-frame.http.test-support]
            [re-frame.adapter.uix :as uix-adapter]))

;; ============================================================================
;; SCHEMAS
;; ============================================================================

;; Shape of valid login credentials: an email and a min-8 password. The
;; password only ever rides this event-arg schema and the HTTP body — it is
;; never written to app-db. The request body that carries it is scrubbed from
;; traces by `:sensitive? true` on the managed-HTTP request below
;; (docs/guide/how-to/keep-secrets-out-of-traces.md). Same schema across
;; reagent/login + helix.
(def Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

;; Outer event-vector schema for the :auth.login/flow machine handler. The
;; :submit sub-event is validated STRICTLY against `Credentials`;
;; framework-internal sub-events (:dismiss, :success, :failure) admit a
;; framework-controlled tail.
;;
;; The :submit branch is a `:tuple` (NOT `:cat`): the outer `:cat` consumes
;; the nested sub-event vector as a SINGLE element, so the branch must match
;; that one element AS a vector. A `:cat` branch applies sequence-regex
;; semantics and would re-admit a `:submit` whose `Credentials` failed. With
;; the strict `:tuple`, a short-password or bad-email submit is rejected at
;; the `:where :event` boundary BEFORE the machine transitions or issues the
;; login HTTP effect (docs/guide/how-to/validate-with-schemas.md, §"Put a
;; schema on the event too").
;;
;; The trailing `[:? :any]` admits the managed-HTTP reply payload. The
;; framework appends the reply map (`{:kind ... :value ...}` /
;; `{:kind ... :failure ...}`) as the LAST arg of the explicit `:on-success`
;; / `:on-failure` event vector, so the delivered reply is
;; `[:auth.login/flow [:auth.login/success] <payload>]` — three top-level
;; elements. Without the optional trailing slot the `:cat` rejects every
;; reply and the boundary validation fails before the machine handler runs.
;; See docs/guide/glossary.md#the-uniform-reply.
(def AuthLoginEvent
  [:cat [:= :auth.login/flow]
   [:or
    [:tuple [:= :auth.login/submit] Credentials]
    [:cat [:enum :auth.login/dismiss :auth.login/success :auth.login/failure]
     [:* :any]]]
   [:? :any]])

;; The machine's `[:schemas :data]` validates the machine's `:data` slot ONLY —
;; the user-domain extended state `{:attempts ... :error ...}` — NOT the whole
;; `{:state ... :data ...}` snapshot. It sits as a top-level key on the machine
;; spec map beside `:data`, and the framework checks it after every transition
;; and at boot, emitting `:rf.error/schema-validation-failure :where
;; :machine-data` and rolling the macrostep back on a violation. The `:state`
;; slot is validated structurally at registration (an unknown target fails
;; registration), so it is not this schema's job. See
;; docs/guide/how-to/validate-with-schemas.md (the machine `:data` schema).
(def AuthLoginData
  [:map
   [:attempts {:default 0} :int]
   [:error    [:maybe :string]]])

;; The machine's `:data` is validated by its own `[:schemas :data]` (attached to
;; the spec map below). Machine snapshots live in runtime-db, not app-db, so a
;; `reg-app-schema` would not see them — app schemas validate the app-db
;; partition only.

;; ============================================================================
;; FX
;; ============================================================================

(def good-password "correct-horse")

(rf/reg-fx :auth.session/store
  {:doc       "Persist session token in localStorage. Client only."
   :platforms #{:client}}
  (fn fx-auth-session-store [_m {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (.setItem ls "auth/token" token))))

(rf/reg-fx :auth.login.demo/managed-stub
  {:doc       "Demo override for `:rf.http/managed`. Delegates to the
               framework-shipped canned-success / canned-failure fxs with
               `:after-ms`, so the framework defers the reply via
               `:dispatch-later` (50 ms): observable in the tape and
               time-travel-safe, NOT a raw `js/setTimeout`. Same stub as the
               Reagent and Helix examples."
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

;; The login flow's machine spec. `[:schemas :data]` validates the `:data`
;; slot (`{:attempts ... :error ...}`), NOT the whole snapshot — see the note
;; on `AuthLoginData` above.
(def auth-login-machine
  {:initial :idle
   :data    {:attempts 0 :error nil}
   :schemas {:data AuthLoginData}

   :guards
   {:under-retry-limit
    (fn [{data :data}] (< (:attempts data) 3))}

   :actions
   {:clear-error
    (fn [_] {:data {:error nil}})

    :issue-request
    (fn [{[_ creds] :event}]
      {:fx [[:rf.http/managed
             ;; `:sensitive? true` redacts the request body (which carries the
             ;; `:password`) from every `:rf.http/*` trace event — the
             ;; per-request wire scrub. See
             ;; docs/guide/how-to/keep-secrets-out-of-traces.md.
             {:request    {:method :post
                           :url    "/api/login"
                           :body   creds
                           :request-content-type :json
                           :sensitive? true}
              :decode     :json
              :on-success [:auth.login/flow [:auth.login/success]]
              :on-failure [:auth.login/flow [:auth.login/failure]]}]]})

    :record-error
    (fn [{data :data [_ {:keys [failure]}] :event}]
      {:data (-> data
                 (update :attempts inc)
                 (assoc :error (or (:message failure) "Login failed.")))})

    :lock-account
    ;; Fire-and-forget telemetry beacon: the lockout POST wants no reply
    ;; folded back into the machine. `:on-success nil` / `:on-failure nil`
    ;; silence both reply branches. Without them the default reply target
    ;; would dispatch `[:auth.login/flow {:rf/reply ...}]` — a map in the
    ;; sub-event slot that `AuthLoginEvent` rejects, stranding noise after
    ;; lockout. See docs/resources/glossary.md#managed-http.
    (fn [_]
      {:fx [[:rf.http/managed
             {:request    {:method :post :url "/api/auth/lock"}
              :on-success nil
              :on-failure nil}]]})

    :store-session
    (fn [{[_ {:keys [value]}] :event}]
      {:fx [[:auth.session/store {:token (:token value)}]]})}

   :states
   {:idle
    {:on {:auth.login/submit {:target :submitting
                              :action :clear-error}}}

    :submitting
    ;; :auth/busy tag — views query
    ;; [:rf/machine-has-tag? :auth.login/flow :auth/busy] to disable
    ;; inputs and re-label the submit button while the request is in
    ;; flight.
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
    ;; Direct retry from :error-shown re-enters :submitting and must clear the
    ;; prior `:error` first — otherwise the obsolete failure message stays
    ;; visible (the view renders `:auth.login/error` whenever non-nil) as if it
    ;; still applied to the request now in flight. Same `:clear-error` action as
    ;; the :idle entry transition.
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
    ;; [:rf/machine-has-tag? :auth.login/flow :auth/locked] to swap the
    ;; form for a locked-account panel and refuse further submits. A
    ;; terminal lockout must be visible and non-interactive, not a live
    ;; form.
    {:tags #{:auth/locked}
     :meta {:terminal? true}}}})

;; Register the machine. This flow validates on two surfaces: the machine's
;; `:data` slot via `[:schemas :data]`, and the dispatched event vector via
;; `:schema` (`AuthLoginEvent`, the `:where :event` boundary). The opts map
;; carries the event `:schema` alongside the machine spec. See
;; docs/machines/glossary.md#machine.
(rf/reg-machine :auth.login/flow
  {:doc    "Login flow: idle → submitting → authed / error-shown / locked-out."
   :schema AuthLoginEvent}
  auth-login-machine)

;; ============================================================================
;; FORM SLICE  (the form pattern — substrate-agnostic)
;; ============================================================================
;;
;; The FORM-SLICE events below are the other half of the "machine + slice"
;; form pattern. The SLICE owns the DRAFT — what the user is currently typing —
;; at the app-db path [:auth :login-form]; the MACHINE owns submit/auth STATUS.
;; A form's draft is application state, so it lives in app-db (projected via
;; subs, mutated via events), not in a view-local atom or hook. Inputs are
;; CONTROLLED: `:value` reads the draft sub, `:on-change` dispatches
;; `:auth.login/edit-field`. The recipe — slice shape, the seven standard form
;; events, the touched-or-submitted error-visibility rule — is in
;; docs/guide/how-to/build-a-form.md.
;;
;; This whole block — slice defaults + form events + (below) the draft/slice
;; subs — is the SUBSTRATE-AGNOSTIC layer: it is identical across
;; examples/reagent/login, examples/uix/login_uix, and
;; examples/helix/login_helix. Only the view syntax varies across the three.

;; The login form's default (empty) draft. The draft's value shape is
;; `Credentials` (email regex + min-8 password) — the same schema the
;; machine's `:submit` boundary enforces.
(def login-form-defaults {:email "" :password ""})

;; Seed the slice to its standard shape: empty `:draft`, `:status :idle`.
;; Runs once via the frame-provider's `:initial-events`.
(rf/reg-event :auth.login/initialise-form
  {:doc "Seed the login-form slice at [:auth :login-form] to its standard
         shape (empty draft, :idle status)."}
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
;; flips the slice `:status` to `:submitting`, and dispatches the draft INTO
;; the machine — the only point the draft crosses into the machine (and is
;; validated against `Credentials` at the machine's `:where :event` boundary).
;; The machine, not the slice, then owns the in-flight / authed / error /
;; locked status; the slice `:status` mirror keeps the slice a conformant
;; form-pattern shape.
;;
;; Secret-field hygiene: the handler reads the password off the draft and hands
;; it to the machine, then CLEARS `[:draft :password]` in the same commit —
;; once the request is in flight the secret has done its job, so it does not
;; sit in durable app-db waiting for the next snapshot / recorder capture. The
;; password's only off-box path is the HTTP request body (scrubbed by the
;; per-request `:sensitive? true` flag); it is never written to `:submitted` or
;; the machine `:data`. See docs/guide/how-to/keep-secrets-out-of-traces.md.
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
;;
;; The machine snapshot lives in runtime-db. These named subs project out the
;; convenient pieces. The "in :submitting?" / "in :authed?" predicates live as
;; the `:rf/machine-has-tag?` framework sub in views below — see
;; docs/machines/glossary.md#state-tag.

;; Machine snapshots are durable runtime-db state — read them through the
;; framework `:rf/machine` sub.
(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [snapshot _] (:state snapshot)))

(rf/reg-sub :auth.login/error
  :<- [:rf/machine :auth.login/flow]
  (fn [snapshot _] (get-in snapshot [:data :error])))

;; --- Form-slice subs (substrate-agnostic) ----------------------------------
;;
;; The login-form slice lives at app-db [:auth :login-form]. The view reads
;; the DRAFT through `:auth.login/draft` and binds each input's `:value` to it
;; — controlled inputs. The per-field-error sub follows the form-pattern
;; error-visibility rule (touched OR submit-attempted?). These subs are part of
;; the layer shared identically across the three substrate examples.

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
  {:doc "Per-field validation error for the login form. Reveal a field's error
         once it is :touched OR once the form has had its first submit click.
         See docs/guide/how-to/build-a-form.md, step 3."}
  :<- [:auth.login/form-slice]
  (fn sub-auth-login-field-error [slice [_ field]]
    (when (or (:submit-attempted? slice)
              (contains? (:touched slice) field))
      (first (get-in slice [:errors field])))))

;; ============================================================================
;; VIEWS  (UIx — defui + use-subscribe)
;; ============================================================================
;;
;; This is the whole substrate seam. A UIx view is a plain `defui`: it reads
;; each subscription through the `use-subscribe` hook and gets `dispatch` off
;; `(rf/frame-handle)`. The Reagent twin instead registers these with `reg-view`
;; and is handed `dispatch`/`subscribe`. The subscription vectors and event
;; vectors are identical — only the way a React component plugs into them differs.
;; See docs/guide/how-to/use-uix-helix-or-slim.md.
;;
;; The inputs are controlled: each `:value` reads the draft from the
;; `:auth.login/draft` sub, and `:on-change` dispatches `:auth.login/edit-field`.
;; The draft lives in app-db, so there is no `uix/use-state` here.
(defui login-form []
  (let [draft    (uix-adapter/use-subscribe [:auth.login/draft])
        busy?    (uix-adapter/use-subscribe [:rf/machine-has-tag?
                                             :auth.login/flow :auth/busy])
        err      (uix-adapter/use-subscribe [:auth.login/error])
        dispatch (:dispatch (rf/frame-handle))]
    ($ :form.login-form
       {:data-testid "login-form"
        :on-submit (fn [e]
                     (.preventDefault e)
                     (when-not busy?
                       (dispatch [:auth.login/submit-form])))}
       ($ :input  {:type        "email"
                   :placeholder "Email"
                   :disabled    busy?
                   :data-testid "login-email"
                   :value       (:email draft)
                   :on-change   #(dispatch [:auth.login/edit-field :email (.. % -target -value)])})
       ($ :input  {:type        "password"
                   :placeholder "Password"
                   :disabled    busy?
                   :data-testid "login-password"
                   :value       (:password draft)
                   :on-change   #(dispatch [:auth.login/edit-field :password (.. % -target -value)])})
       ($ :button {:type "submit" :disabled busy?
                   :data-testid "login-submit"}
          (if busy? "Signing in…" "Sign in"))
       (when err ($ :p.error {:data-testid "login-error"} err)))))

;; Terminal lockout panel — rendered once the flow reaches :locked-out
;; (tagged :auth/locked). The state has no transitions, so the form is
;; swapped out entirely rather than left enabled-but-dead.
(defui locked-panel []
  ($ :div.locked {:data-testid "locked-panel"}
     ($ :h2 "Account locked")
     ($ :p "Three failed attempts. Contact support to unlock.")))

(defui login-banner []
  (let [authed? (uix-adapter/use-subscribe [:rf/machine-has-tag?
                                            :auth.login/flow :auth/authenticated])
        locked? (uix-adapter/use-subscribe [:rf/machine-has-tag?
                                            :auth.login/flow :auth/locked])]
    ($ :div.banner {:data-testid "login-banner"}
       (cond
         authed? ($ :span "Welcome!")
         locked? ($ locked-panel)
         :else   ($ login-form)))))

(defui root-view []
  ($ :div.app
     ($ :h1 "Sign in")
     ($ login-banner)))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
(defonce react-root (atom nil))

(defn run []
  ;; Install the UIx adapter.
  (rf/init! uix-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (uix-dom/create-root (js/document.getElementById "app"))))
    ;; One-spot frame setup. The `frame-provider` at the render root owns the
    ;; frame: on the first mount it creates the `:rf/default` frame, applies the
    ;; config (`:fx-overrides` redirects `:rf.http/managed` to the demo stub),
    ;; and runs `:initial-events` once. On hot reload it reuses the existing
    ;; frame and skips the events. The `:id :rf/default` names the frame that the
    ;; `use-subscribe` hook and the `(rf/frame-handle)` in `login-form` resolve
    ;; to — those reads need a provider above them in the tree.
    ;;
    ;; `:initial-events` seeds the form SLICE ([:auth.login/initialise-form]) to
    ;; its empty shape, so the controlled inputs read an empty draft (not nil) on
    ;; the first render. The MACHINE needs no seeding: its `:initial`/`:data`
    ;; seed the snapshot in runtime-db the first time the flow runs (see
    ;; docs/machines/glossary.md#snapshot).
    (uix-dom/render-root
      ($ uix-adapter/frame-provider {:id              :rf/default
                                     :doc             "Login (UIx) demo frame."
                                     :fx-overrides    {:rf.http/managed :auth.login.demo/managed-stub}
                                     :initial-events  [[:auth.login/initialise-form]]}
         ($ root-view))
      @react-root)))
