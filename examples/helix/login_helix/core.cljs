(ns login-helix.core
  "Helix variant of the login example.

   Same dataflow, schemas, machine, and HTTP stub as
   examples/reagent/login and examples/uix/login_uix; only the views
   differ. They are Helix `defnc` components that read subs via the
   adapter's `use-subscribe` hook. The state machine, schemas, and
   managed-HTTP surfaces are substrate-agnostic — the view layer is the
   only thing that changes between substrates.

   The machine's states carry state tags (`:auth/busy`,
   `:auth/authenticated`, `:auth/locked`); views read them via the
   `:rf/machine-has-tag?` framework sub. The terminal `:locked-out`
   state shows a non-interactive locked-account panel.

   See the machines guide (docs/machines/concepts.md) and the form
   recipe (docs/guide/how-to/build-a-form.md)."
  (:require ["react-dom/client" :as react-dom-client]
            [helix.core         :refer [$ defnc]]
            [helix.dom          :as d]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.schemas]
            [re-frame.machines]
            [re-frame.http.managed]
            ;; Registers the canned-success / canned-failure HTTP fx ids the
            ;; demo stub below delegates to.
            [re-frame.http.test-support]
            [re-frame.adapter.helix :as helix-adapter]))

;; ============================================================================
;; SUBSTRATE-AGNOSTIC ARTEFACT LAYER  (schemas + fx + machine + subs)
;; ============================================================================
;;
;; Everything from here down to the SUBSTRATE BOUNDARY divider — schemas, the
;; managed-HTTP stub fx, the `:auth.login/flow` machine, and the named subs —
;; is identical across the Reagent, UIx, and Helix login examples: same ids,
;; same machine spec, same schemas, same HTTP stub. That sameness is the
;; cross-substrate parity demonstration. Only the views below the SUBSTRATE
;; BOUNDARY differ.
;;
;; It is deliberately NOT extracted into a shared namespace. Each substrate
;; example is a self-contained `:browser` build, and the bundle-isolation gate
;; proves a Helix bundle carries no Reagent/UIx code. A shared model required
;; into all three builds would defeat that isolation. The boundary to learn
;; here is one dataflow with three view layers — not a file-extraction
;; boundary.

;; ============================================================================
;; SCHEMAS
;; ============================================================================

;; The credentials a login submit carries: an email and a password.
;;
;; The password never lands in app-db — it rides the machine's event-arg
;; schema and the HTTP body only — so there is no app-db path to classify
;; `:sensitive`. The wire scrub happens instead on the HTTP request below,
;; via its `:sensitive? true` flag. See
;; docs/guide/how-to/keep-secrets-out-of-traces.md.
(def Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

;; Schema for the whole `:auth.login/flow` event vector — validated at the
;; event boundary before the machine handler runs. The `:submit` sub-event is
;; validated strictly against `Credentials`; the framework-internal sub-events
;; (`:dismiss`, `:success`, `:failure`) admit a framework-controlled tail.
;;
;; The `:submit` branch is a `:tuple`, not a `:cat`: the outer `:cat` consumes
;; the nested sub-event vector as a SINGLE element, so the branch must match
;; that one element AS a vector. A `:cat` branch would apply sequence-regex
;; semantics, which can silently re-admit a `:submit` whose `Credentials`
;; failed. With the strict `:tuple`, a short-password or bad-email submit is
;; rejected at the boundary before the machine transitions or issues the login
;; HTTP effect.
;;
;; The trailing `[:? :any]` admits the managed-HTTP reply payload. The
;; framework appends `{:kind ... :value ...}` / `{:kind ... :failure ...}` as
;; the LAST arg of the `:on-success` / `:on-failure` event vector, so the
;; delivered reply is `[:auth.login/flow [:auth.login/success] <payload>]` —
;; three top-level elements. Without the optional trailing slot the `:cat`
;; rejects every reply, validation fails before the machine handler runs, and
;; the flow is stranded in `:submitting`. See
;; docs/guide/how-to/validate-with-schemas.md.
(def AuthLoginEvent
  [:cat [:= :auth.login/flow]
   [:or
    [:tuple [:= :auth.login/submit] Credentials]
    [:cat [:enum :auth.login/dismiss :auth.login/success :auth.login/failure]
     [:* :any]]]
   [:? :any]])

;; Schema for the machine's `:data` slot — the extended state
;; `{:attempts ... :error ...}`, NOT the whole `{:state ... :data ...}`
;; snapshot. It sits under the machine spec's `:schemas` map (attached below)
;; and the framework validates it at every transition commit and at bootstrap.
;; See "Validating a machine's `:data`" in docs/machines/concepts.md.
(def AuthLoginData
  [:map
   [:attempts {:default 0} :int]
   [:error    [:maybe :string]]])

;; A machine snapshot lives in runtime-db, not app-db, and app schemas validate
;; the app-db partition only. So no `reg-app-schema` applies to the snapshot;
;; the machine's own `:data` schema (attached below) validates it instead.

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
               framework's canned-success / canned-failure fxs with
               `:after-ms`, so the reply is deferred via `:dispatch-later`
               (50 ms) rather than a raw `js/setTimeout` — it shows up in the
               trace and is time-travel-safe."
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

;; The login flow's machine spec. `:schemas` is a top-level key beside `:data`;
;; its `:data` entry validates the `:data` slot (`{:attempts ... :error ...}`),
;; not the whole snapshot. It is a named `def` so it can be passed to
;; `reg-machine` below — the same shape the Reagent and UIx login siblings use.
(def auth-login-machine
  {:initial :idle
   ;; `:schemas :data` validates the `:data` slot at every transition commit.
   ;; This `:data` (`{:attempts :error}`) holds no secret, so the machine
   ;; declares no `:sensitive` / `:large`.
   :schemas {:data AuthLoginData}
   :data    {:attempts 0 :error nil}

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
             ;; `:password`) from every HTTP trace event — the per-request wire
             ;; scrub. See docs/guide/how-to/keep-secrets-out-of-traces.md.
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
    ;; Fire-and-forget lockout beacon: this POST wants no reply folded back
    ;; into the machine. `:on-success nil` / `:on-failure nil` silence both
    ;; reply branches. Omit them and the default would dispatch
    ;; `[:auth.login/flow {:rf/reply ...}]` — a map in the sub-event slot that
    ;; `AuthLoginEvent` rejects, stranding noise after lockout.
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
    ;; :auth/locked tag — the flow lands in this terminal state once the retry
    ;; limit is reached. Views query
    ;; [:rf/machine-has-tag? :auth.login/flow :auth/locked] to swap the form
    ;; for a locked-account panel and refuse further submits. A terminal
    ;; lockout should be visible and non-interactive, not a live form.
    {:tags #{:auth/locked}
     :meta {:terminal? true}}}})

;; Register the machine. The three-argument arity carries an event `:schema`
;; in the middle map — the boundary check on the dispatched outer event vector
;; — alongside the machine spec (the `auth-login-machine` def above). See
;; "Validating a machine's `:data`" in docs/machines/concepts.md.
(rf/reg-machine :auth.login/flow
  {:doc    "Login flow: idle → submitting → authed / error-shown / locked-out."
   :schema AuthLoginEvent}
  auth-login-machine)

;; ============================================================================
;; FORM SLICE  (substrate-agnostic)
;; ============================================================================
;;
;; The form-slice events below are the other half of the "machine + slice"
;; composition. The SLICE owns the DRAFT — what the user is currently typing —
;; at the app-db path [:auth :login-form]; the MACHINE owns submit/auth STATUS.
;; A form's draft is application state, so it lives in app-db (read via subs,
;; written via events), not in a view-local hook. Inputs are CONTROLLED:
;; `:value` reads the draft sub, `:on-change` dispatches
;; `:auth.login/edit-field`. See docs/guide/how-to/build-a-form.md.
;;
;; This whole block — slice defaults, form events, and (below) the draft/slice
;; subs — is part of the substrate-agnostic layer shared identically across
;; the three login examples. Only the view syntax varies.

;; The login form's default (empty) draft. The draft's value shape is
;; `Credentials` (email regex + min-8 password) — the same schema the
;; machine's `:submit` boundary enforces.
(def login-form-defaults {:email "" :password ""})

;; Seed the form slice to its standard shape: empty `:draft`, `:status :idle`.
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

;; The user changed a single field. Update `:draft` and mark the field
;; `:touched`. This is all an input's `:on-change` does — the draft lives in
;; app-db, not view-local state. The `:schema` rejects a malformed edit-field
;; vector at the event boundary.
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
;; the machine — the only point the draft crosses into the machine (where it
;; is validated against `Credentials` at the event boundary). The machine, not
;; the slice, then owns the in-flight / authed / error / locked status; the
;; slice `:status` is just a mirror.
;;
;; Secret-field hygiene: the handler reads the password off the draft, hands it
;; to the machine, and CLEARS `[:draft :password]` in the same commit. Once the
;; request is in flight the secret has done its job, so it does not sit in
;; durable app-db waiting for the next snapshot or recording. The password's
;; only off-box path is the HTTP request body (scrubbed by the per-request
;; `:sensitive? true` flag); it is never written to `:submitted` or the machine
;; `:data`. See docs/guide/how-to/keep-secrets-out-of-traces.md.
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
;; convenient pieces by chaining off the framework `:rf/machine` sub. The
;; "is it busy / authed / locked?" predicates are the `:rf/machine-has-tag?`
;; framework sub, read directly in the views below.

;; Read the machine's snapshot through the framework `:rf/machine` sub.
(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [snapshot _] (:state snapshot)))

(rf/reg-sub :auth.login/error
  :<- [:rf/machine :auth.login/flow]
  (fn [snapshot _] (get-in snapshot [:data :error])))

;; --- Form-slice subs (substrate-agnostic) ----------------------------------
;;
;; The login-form slice lives at app-db [:auth :login-form]. The view reads the
;; DRAFT through `:auth.login/draft` and binds each input's `:value` to it —
;; controlled inputs. The per-field-error sub follows the form visibility rule:
;; show a field's error once it is touched OR a submit has been attempted. See
;; docs/guide/how-to/build-a-form.md.

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
         once it is :touched OR once the form has had its first submit click."}
  :<- [:auth.login/form-slice]
  (fn sub-auth-login-field-error [slice [_ field]]
    (when (or (:submit-attempted? slice)
              (contains? (:touched slice) field))
      (first (get-in slice [:errors field])))))

;; ============================================================================
;; ──────────────────────────  SUBSTRATE BOUNDARY  ──────────────────────────
;; ============================================================================
;;
;; Below this line is the only substrate-specific code in this example: the
;; Helix views + the mount. The Reagent and UIx login examples share every
;; line ABOVE this divider and differ only in what sits BELOW it (Reagent
;; `reg-view`, UIx `defui` + `use-subscribe`, Helix `defnc` + `use-subscribe`).

;; ============================================================================
;; VIEWS  (Helix — defnc + use-subscribe)
;; ============================================================================
;;
;; The Helix view idiom, and the only place this example differs from the
;; Reagent reference. A view is a plain `defnc`. It reads a subscription
;; through the adapter's `use-subscribe` hook, and takes `dispatch` off a
;; `frame-handle`. `:rf/machine-has-tag?` reads are *ask, don't tell* state-tag
;; queries; `:auth.login/error` is a named sub. To the call site they're
;; identical — both are just subscriptions.

(defnc login-form []
  (let [draft    (helix-adapter/use-subscribe [:auth.login/draft])
        busy?    (helix-adapter/use-subscribe [:rf/machine-has-tag?
                                               :auth.login/flow :auth/busy])
        err      (helix-adapter/use-subscribe [:auth.login/error])
        ;; Take `dispatch` off the render-time frame-handle. It resolves to
        ;; this frame (`:rf/default`) through the provider in `run`. Every
        ;; dispatch goes to a frame; there is no global dispatch.
        dispatch (:dispatch (rf/frame-handle))]
    ;; Controlled inputs: each input's `:value` reads the draft from the
    ;; `:auth.login/draft` sub, and `:on-change` dispatches
    ;; `:auth.login/edit-field`. The draft lives in app-db, not in a `use-state`
    ;; hook. It crosses into the machine (and is validated) at the :on-submit
    ;; dispatch of `:auth.login/submit-form`.
    (d/form
       {:class "login-form"
        :data-testid "login-form"
        :on-submit (fn [e]
                     (.preventDefault e)
                     (when-not busy?
                       (dispatch [:auth.login/submit-form])))}
       (d/input  {:type        "email"
                  :placeholder "Email"
                  :disabled    busy?
                  :data-testid "login-email"
                  :value       (:email draft)
                  :on-change   #(dispatch [:auth.login/edit-field :email (.. % -target -value)])})
       (d/input  {:type        "password"
                  :placeholder "Password"
                  :disabled    busy?
                  :data-testid "login-password"
                  :value       (:password draft)
                  :on-change   #(dispatch [:auth.login/edit-field :password (.. % -target -value)])})
       (d/button {:type "submit" :disabled busy?
                  :data-testid "login-submit"}
          (if busy? "Signing in…" "Sign in"))
       (when err (d/p {:class "error" :data-testid "login-error"} err)))))

;; Terminal lockout panel — rendered once the flow reaches :locked-out
;; (tagged :auth/locked). The state has no transitions, so the form is
;; swapped out entirely rather than left enabled-but-dead.
(defnc locked-panel []
  (d/div
     {:class "locked"
      :data-testid "locked-panel"}
     (d/h2 "Account locked")
     (d/p "Three failed attempts. Contact support to unlock.")))

(defnc login-banner []
  (let [authed? (helix-adapter/use-subscribe [:rf/machine-has-tag?
                                              :auth.login/flow :auth/authenticated])
        locked? (helix-adapter/use-subscribe [:rf/machine-has-tag?
                                              :auth.login/flow :auth/locked])]
    (d/div
       {:class "banner"
        :data-testid "login-banner"}
       (cond
         authed? (d/span "Welcome!")
         locked? ($ locked-panel)
         :else   ($ login-form)))))

(defnc root-view []
  (d/div
     {:class "app"}
     (d/h1 "Sign in")
     ($ login-banner)))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; Hold the React root in an atom and create it lazily inside `run`, not at
;; ns-load. Loading the namespace must produce no DOM side effects, so that
;; co-loaded example namespaces don't race `createRoot` onto the shared `#app`.
(defonce react-root (atom nil))

(defn run []
  ;; Install the Helix adapter once, before the first render.
  (rf/init! helix-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (react-dom-client/createRoot (js/document.getElementById "app"))))
    ;; Frame setup in one spot. The `frame-provider`'s `{:id …}` shape creates
    ;; the `:rf/default` frame on first mount and applies the config below
    ;; (`:doc`, and `:fx-overrides` routing `:rf.http/managed` to the demo
    ;; stub). On hot reload it reuses the existing frame and its state, so the
    ;; demo survives a reload. The machine snapshot needs no seeding: it
    ;; self-initialises from the spec's `:initial`/`:data` when the flow first
    ;; runs.
    ;;
    ;; The provider also scopes the frame into React context, so the
    ;; `use-subscribe` hook and the `(rf/frame-handle)` capture in `login-form`
    ;; resolve to it. The provider is required — without it those reads raise
    ;; `:rf.error/no-frame-context`.
    (.render @react-root
             ($ helix-adapter/frame-provider
                {:id           :rf/default
                 :doc          "Login (Helix) demo frame."
                 :fx-overrides {:rf.http/managed :auth.login.demo/managed-stub}}
                ($ root-view)))))
