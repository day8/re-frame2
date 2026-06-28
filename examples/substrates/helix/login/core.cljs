(ns helix.login.core
  "The login example, rendered through Helix.

   A login form, a state machine for the submit/auth lifecycle, schemas
   guarding the boundaries, and a stubbed HTTP call. Same dataflow,
   schemas, machine, and HTTP stub as examples/core/login and
   examples/substrates/uix/login — the machine, the schemas, the managed-HTTP
   surface are all substrate-agnostic, and the view layer is the only
   thing that changes between substrates. Here the views are Helix
   `defnc` components that read subs through the adapter's `use-subscribe`
   hook.

   The machine's states wear tags — `:auth/busy`, `:auth/authenticated`,
   `:auth/locked` — and the views ask about them with the
   `:rf/machine-has-tag?` framework sub. Keep guessing the password and the
   fourth wrong try trips the retry guard: the flow lands in the terminal
   `:locked-out` state, and the form is replaced by a dead-end,
   non-interactive locked-account panel.

   The machines guide (docs/machines/concepts.md) and the form recipe
   (docs/core/how-to/build-a-form.md) cover the two big ideas at
   length."
  (:require ["react-dom/client" :as react-dom-client]
            [helix.core         :refer [$ defnc]]
            [helix.dom          :as d]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.schemas]
            [re-frame.machines]
            [re-frame.http.managed]
            ;; Brings in the canned-success / canned-failure HTTP fx ids the
            ;; demo stub below leans on to fake a server.
            [re-frame.http.test-support]
            [re-frame.adapter.helix :as helix-adapter]))

;; ============================================================================
;; SUBSTRATE-AGNOSTIC ARTEFACT LAYER  (schemas + fx + machine + subs)
;; ============================================================================
;;
;; Everything from here down to the SUBSTRATE BOUNDARY divider — the schemas,
;; the HTTP stub, the `:auth.login/flow` machine, the named subs — is identical
;; across the Reagent, UIx, and Helix login examples. Same ids, same machine,
;; same schemas, same stub, character for character. That's the point: the
;; logic of a login flow doesn't care which view library draws it.
;;
;; You might expect this shared half to live in one file the three examples
;; require. It deliberately doesn't. Each example is its own self-contained
;; `:browser` build, and the bundle-isolation gate proves a Helix bundle
;; carries no Reagent or UIx code — a shared file pulled into all three would
;; blow that apart. So the lesson here is "one dataflow, three view layers,"
;; not "factor out the common bit." The duplication is on purpose.

;; ============================================================================
;; SCHEMAS
;; ============================================================================

;; What a login submit carries: an email and a password.
;;
;; Notice where the password is allowed to go. It rides this event-arg schema
;; and the HTTP body, and that's it — it never lands in app-db. So there's no
;; app-db path to tag `:sensitive`; the only thing to scrub is the request on
;; the wire, which the HTTP call below handles with `:sensitive? true`. See
;; docs/core/how-to/keep-secrets-out-of-traces.md.
(def Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

;; The shape of the whole `:auth.login/flow` event vector, checked at the event
;; boundary before the machine ever sees it. A `:submit` must carry valid
;; `Credentials`; the flow's own internal sub-events (`:dismiss`, `:success`,
;; `:failure`) get a looser tail since the framework, not the user, builds them.
;;
;; Two details here are load-bearing and worth a moment.
;;
;; First, the `:submit` branch is a `:tuple`, not a `:cat`. The outer `:cat`
;; hands the whole nested sub-event over as one element, so this branch has to
;; match that element *as a vector*. Reach for `:cat` instead and you get
;; sequence-regex matching, which can quietly wave through a `:submit` whose
;; credentials were garbage. The strict `:tuple` makes a short password or a
;; bad email bounce at the door — before the machine transitions, before the
;; login request fires.
;;
;; Second, that trailing `[:? :any]` is the slot for the HTTP reply. When the
;; managed-HTTP call comes back, the framework appends the payload —
;; `{:kind ... :value ...}` on success, `{:kind ... :failure ...}` on failure —
;; as the last arg of the `:on-success` / `:on-failure` event vector, so a
;; delivered reply reads `[:auth.login/flow [:auth.login/success] <payload>]` —
;; three top-level elements, not two. Leave the optional slot out and the
;; `:cat` rejects every reply, validation fails, and the flow sits in
;; `:submitting` forever, wondering where its answer went. See
;; docs/core/how-to/validate-with-schemas.md.
(def AuthLoginEvent
  [:cat [:= :auth.login/flow]
   [:or
    [:tuple [:= :auth.login/submit] Credentials]
    [:cat [:enum :auth.login/dismiss :auth.login/success :auth.login/failure]
     [:* :any]]]
   [:? :any]])

;; The shape of the machine's `:data` slot — its extended state,
;; `{:attempts ... :error ...}`. Just the `:data`, mind you, not the whole
;; `{:state ... :data ...}` snapshot. It hangs off the machine's `:schemas` map
;; (attached below), and the framework re-checks it every time the machine
;; commits a transition, plus once at bootstrap. See "Validating a machine's
;; `:data`" in docs/machines/concepts.md.
(def AuthLoginData
  [:map
   [:attempts {:default 0} :int]
   [:error    [:maybe :string]]])

;; Why a machine `:data` schema and not a `reg-app-schema`? Because a machine's
;; snapshot lives in runtime-db, and app schemas only ever look at app-db. The
;; machine guards its own state with the `:data` schema above.

;; ============================================================================
;; FX
;; ============================================================================

(def good-password "correct-horse")

(rf/reg-fx :auth.session/store
  {:doc       "Stash the session token in localStorage. Browser only."
   :platforms #{:client}}
  (fn fx-auth-session-store [_m {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (.setItem ls "auth/token" token))))

(rf/reg-fx :auth.login.demo/managed-stub
  {:doc       "A fake server, standing in for `:rf.http/managed` so the demo
               needs no backend. It hands off to the framework's canned-success
               / canned-failure fxs with `:after-ms`, so the reply comes back
               via `:dispatch-later` (after 50 ms) instead of a raw
               `js/setTimeout`. The win: the round-trip shows up in the trace
               and survives time-travel, which a bare timeout would not."
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

;; The login flow itself, written out as a machine spec. `:schemas` sits at the
;; top level next to `:data`, and its `:data` entry guards the `:data` slot
;; (`{:attempts ... :error ...}`) — not the whole snapshot. It's a named `def`
;; purely so we can hand it to `reg-machine` just below; the Reagent and UIx
;; siblings define theirs the same way.
(def auth-login-machine
  {:initial :idle
   ;; `:schemas :data` re-checks the `:data` slot on every transition commit.
   ;; This `:data` (`{:attempts :error}`) holds nothing secret, so there's no
   ;; `:sensitive` or `:large` to declare.
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
             ;; `:sensitive? true` is the per-request wire scrub: it keeps the
             ;; request body — and the `:password` inside it — out of every HTTP
             ;; trace event. See docs/core/how-to/keep-secrets-out-of-traces.md.
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
    ;; A fire-and-forget lockout beacon: we POST it and don't care what comes
    ;; back. `:on-success nil` / `:on-failure nil` mute both reply branches.
    ;; Skip them and the default reply would dispatch `[:auth.login/flow
    ;; {:rf/reply ...}]` — a map where `AuthLoginEvent` expects a sub-event
    ;; vector, so it gets rejected and leaves stray noise rattling around after
    ;; the account is already locked.
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
    ;; The `:auth/busy` tag. While the request is in flight, the views ask
    ;; [:rf/machine-has-tag? :auth.login/flow :auth/busy] and use the answer to
    ;; grey out the inputs and flip the button to "Signing in…".
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
    ;; Retrying straight from :error-shown drops back into :submitting, and it
    ;; has to wipe the old `:error` on the way. The view shows
    ;; `:auth.login/error` whenever it's non-nil, so a stale message would hang
    ;; around looking like it belongs to the request you just kicked off. Hence
    ;; the same `:clear-error` action the :idle → :submitting edge uses.
    {:on {:auth.login/dismiss {:target :idle}
          :auth.login/submit  {:target :submitting
                               :action :clear-error}}}

    :authed
    ;; The `:auth/authenticated` tag. Reach this terminal state and the banner
    ;; swaps over to "Welcome!". You're in.
    {:tags #{:auth/authenticated}
     :meta {:terminal? true}}

    :locked-out
    ;; The `:auth/locked` tag, reached after one too many failed attempts. The
    ;; views ask [:rf/machine-has-tag? :auth.login/flow :auth/locked] and, when
    ;; it's true, replace the form with a locked-account panel — no more
    ;; submits. A dead end should look like one, not a live form that quietly
    ;; ignores you.
    {:tags #{:auth/locked}
     :meta {:terminal? true}}}})

;; Register the machine. The three-arg form puts an event `:schema` in the
;; middle map — that's the boundary check on the dispatched event vector — next
;; to the spec itself (`auth-login-machine`, above). See "Validating a
;; machine's `:data`" in docs/machines/concepts.md.
(rf/reg-machine :auth.login/flow
  {:doc    "Login flow: idle → submitting → authed / error-shown / locked-out."
   :schema AuthLoginEvent}
  auth-login-machine)

;; ============================================================================
;; FORM SLICE  (substrate-agnostic)
;; ============================================================================
;;
;; Here's the other half of the "machine + slice" pairing. The two split the
;; work cleanly: the slice owns the DRAFT — whatever the user is typing right
;; now — at app-db path [:auth :login-form], while the machine owns the
;; submit/auth STATUS. A half-typed form is still application state, so it lives
;; in app-db (read through subs, written through events), not in a view-local
;; hook. That makes every input CONTROLLED: `:value` reads the draft sub, and
;; `:on-change` dispatches `:auth.login/edit-field`. See
;; docs/core/how-to/build-a-form.md.
;;
;; Like the machine above, this whole block — the defaults, the events, and the
;; draft/slice subs further down — is shared verbatim across the three login
;; examples. Only the view syntax changes.

;; The empty draft a fresh form starts from. Its value shape is `Credentials`
;; (email regex, 8-char-minimum password) — the very schema the machine's
;; `:submit` boundary will hold it to later.
(def login-form-defaults {:email "" :password ""})

;; Lay down a fresh form slice: empty `:draft`, `:status :idle`, and the rest.
(rf/reg-event :auth.login/initialise-form
  {:doc "Seed the login-form slice at [:auth :login-form] to its starting
         shape: empty draft, :idle status."}
  (fn handler-login-form-initialise [{:keys [db]} _]
    {:db (assoc-in db [:auth :login-form]
                   {:draft             login-form-defaults
                    :submitted         nil
                    :submit-attempted? false
                    :status            :idle
                    :errors            {}
                    :touched           #{}
                    :submit-error      nil})}))

;; The user typed in one field. Write that field into `:draft` and remember it's
;; been `:touched`. That's the whole job of an input's `:on-change` — the draft
;; lives in app-db, not in view-local state. The `:schema` turns away any
;; malformed edit-field vector right at the boundary.
(rf/reg-event :auth.login/edit-field
  {:doc    "Controlled-input edit: write one field into the login-form draft
            and mark it touched."
   :schema [:cat [:= :auth.login/edit-field] :keyword :string]}
  (fn handler-login-form-edit-field [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in  [:auth :login-form :draft field] value)
             (update-in [:auth :login-form :touched] (fnil conj #{}) field))}))

;; Submit. This reads the draft out of the slice, latches `:submit-attempted?`,
;; flips the slice `:status` to `:submitting`, and dispatches the draft INTO the
;; machine. That dispatch is the one and only place the draft crosses over —
;; and crossing over means meeting `Credentials` at the machine's boundary. From
;; here on the machine owns the real status (in-flight / authed / error /
;; locked); the slice's `:status` just shadows it.
;;
;; Watch how the password is handled. The handler lifts it off the draft, sends
;; it to the machine, and CLEARS `[:draft :password]` in the very same commit.
;; The moment the request is on its way the secret has done its one job, so we
;; don't leave it lounging in app-db where the next snapshot or recording could
;; scoop it up. Its only trip off the box is inside the HTTP body (scrubbed by
;; `:sensitive? true`); it never touches `:submitted` or the machine's `:data`.
;; See docs/core/how-to/keep-secrets-out-of-traces.md.
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

;; Wipe the slice back to its empty, :idle starting shape.
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
;; The machine's snapshot lives in runtime-db, and these named subs pick out the
;; handy bits by chaining off the framework's `:rf/machine` sub. The yes/no
;; "busy? authed? locked?" questions don't need their own subs at all — the
;; views ask `:rf/machine-has-tag?` directly.

;; Pull the machine's whole snapshot through the framework `:rf/machine` sub.
(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [snapshot _] (:state snapshot)))

(rf/reg-sub :auth.login/error
  :<- [:rf/machine :auth.login/flow]
  (fn [snapshot _] (get-in snapshot [:data :error])))

;; --- Form-slice subs (substrate-agnostic) ----------------------------------
;;
;; The slice sits at app-db [:auth :login-form]. The view reads the DRAFT
;; through `:auth.login/draft` and ties each input's `:value` to it — that's
;; what makes them controlled. The per-field-error sub plays by the usual form
;; rule: don't show a field's error until the user has touched that field or
;; tried to submit at least once. See docs/core/how-to/build-a-form.md.

(rf/reg-sub :auth.login/form-slice
  {:doc "The whole login-form slice at [:auth :login-form]."}
  (fn sub-auth-login-form-slice [db _]
    (get-in db [:auth :login-form])))

(rf/reg-sub :auth.login/draft
  {:doc "The draft — whatever the user has typed so far. Each input binds its
         :value to a field of this map, which is what makes them controlled."}
  :<- [:auth.login/form-slice]
  (fn sub-auth-login-draft [slice _]
    (:draft slice)))

(rf/reg-sub :auth.login/field-error
  {:doc "A field's validation error, but only once the user has earned the
         right to see it: after touching that field, or after the first submit
         click."}
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
;; `capture-frame`. `:rf/machine-has-tag?` reads are *ask, don't tell* state-tag
;; queries; `:auth.login/error` is a named sub. To the call site they're
;; identical — both are just subscriptions.

(defnc login-form []
  (let [draft    (helix-adapter/use-subscribe [:auth.login/draft])
        busy?    (helix-adapter/use-subscribe [:rf/machine-has-tag?
                                               :auth.login/flow :auth/busy])
        err      (helix-adapter/use-subscribe [:auth.login/error])
        ;; Take `dispatch` off the render-time capture-frame. It resolves to
        ;; this frame (`:rf/default`) through the provider in `run`. Every
        ;; dispatch goes to a frame; there is no global dispatch.
        dispatch (:dispatch (rf/capture-frame))]
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
    ;; `use-subscribe` hook and the `(rf/capture-frame)` capture in `login-form`
    ;; resolve to it. The provider is required — without it those reads raise
    ;; `:rf.error/no-frame-context`.
    (.render @react-root
             ($ helix-adapter/frame-provider
                {:id           :rf/default
                 :doc          "Login (Helix) demo frame."
                 :fx-overrides {:rf.http/managed :auth.login.demo/managed-stub}}
                ($ root-view)))))
