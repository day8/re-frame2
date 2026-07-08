(ns uix.login.core
  "A login flow, rendered through UIx. The same feature lives on Reagent and
   Helix too, and that's exactly the point of this file: watch how little has
   to change to move between them.

   Everything below the views — the state machine, the schemas, the
   managed-HTTP effect — is substrate-agnostic and byte-for-byte the same as
   examples/core/login. Only the views differ. Here a view is a UIx `defui`
   that reads subscriptions through the `use-subscribe` hook; the Reagent twin
   reaches for `reg-view`. Same data, different doorway.

   The machine tags a few of its states — `:auth/busy`, `:auth/authenticated`,
   `:auth/locked` — and views ask about them through the `:rf.machine/has-tag?`
   framework sub. When the flow finally gives up, the terminal `:locked-out`
   state swaps the form for a dead-end locked-account panel.

   For the boundary mechanics — `use-subscribe`, `capture-frame`,
   `frame-provider`, and what stays put across React wrappers — see
   docs/core/how-to/use-uix-helix-or-slim.md."
  (:require [uix.core :as uix :refer [$ defui]]
            [uix.dom  :as uix-dom]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            ;; Malli directly — for the pre-submit form validator below
            ;; (`m/explain` + `me/humanize`), the same pure validator the form
            ;; recipe builds (docs/core/how-to/build-a-form.md).
            [malli.core :as m]
            [malli.error :as me]
            [re-frame.schemas]
            [re-frame.machines]
            [re-frame.http.managed]
            ;; Required for its side effect: it registers the canned-success /
            ;; canned-failure fx ids our demo stub leans on. Without this here,
            ;; those ids wouldn't exist and the stub would have nothing to call.
            [re-frame.http.test-support]
            [re-frame.adapter.uix :as uix-adapter]))

;; ============================================================================
;; SCHEMAS
;; ============================================================================

;; What valid credentials look like: an email and a password of at least 8
;; characters. The password is a careful little traveller — it rides only this
;; schema and the HTTP body, and is never written to app-db. The request body
;; that carries it gets scrubbed from traces by `:sensitive? true` on the
;; managed-HTTP request below (docs/core/how-to/keep-secrets-out-of-traces.md).
;; The same schema guards reagent/login and helix.
(def Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

;; The schema for the whole event vector the :auth.login/flow machine handles.
;; It earns its keep by validating credentials at the door: a `:submit` is
;; checked strictly against `Credentials`, while the framework's own sub-events
;; (:dismiss, :success, :failure) get a more relaxed welcome.
;;
;; One subtlety worth pausing on: the :submit branch is a `:tuple`, not a
;; `:cat`. The outer `:cat` swallows the nested sub-event as a single element,
;; so the branch has to match that element *as a vector*. A `:cat` here would
;; apply sequence-regex semantics and quietly wave through a `:submit` whose
;; `Credentials` had already failed — exactly the leak we're trying to plug.
;; With the strict `:tuple`, a short password or a bad email bounces off the
;; `:where :event` boundary before the machine transitions or fires off the
;; login request (docs/core/how-to/validate-with-schemas.md, §"Put a schema
;; on the event too").
;;
;; The trailing `[:? :any]` is there to catch the managed-HTTP reply. The
;; framework tacks the reply map (`{:kind ... :value ...}` or
;; `{:kind ... :failure ...}`) onto the end of the `:on-success` / `:on-failure`
;; vector, so what actually arrives is
;; `[:auth.login/flow [:auth.login/success] <payload>]` — three top-level
;; elements, not two. Leave off that optional slot and the `:cat` rejects every
;; reply, failing validation before the handler ever runs. See
;; docs/core/glossary.md#the-uniform-reply.
(def AuthLoginEvent
  [:cat [:= :auth.login/flow]
   [:or
    [:tuple [:= :auth.login/submit] Credentials]
    [:cat [:enum :auth.login/dismiss :auth.login/success :auth.login/failure]
     [:* :any]]]
   [:? :any]])

;; This schema watches the machine's `:data` slot — the user-domain extended
;; state `{:attempts ... :error ...}` — and nothing else. It does not see the
;; whole `{:state ... :data ...}` snapshot; just the `:data` half. Hang it on
;; the machine spec map (as `[:schemas :data]`, below) and the framework
;; re-checks it after every transition and at boot. A violation raises
;; `:rf.error/schema-validation-failure :where :machine-data` and rolls the
;; macrostep back, so a bad `:data` value never sticks. The `:state` slot looks
;; after itself — an unknown target state fails at registration — so it's not
;; this schema's concern. See docs/core/how-to/validate-with-schemas.md (the
;; machine `:data` schema).
(def AuthLoginData
  [:map
   [:attempts {:default 0} :int]
   [:error    [:maybe :string]]])

;; Why a dedicated machine schema and not a `reg-app-schema`? Because machine
;; snapshots live in runtime-db, not app-db, and app schemas only ever look at
;; the app-db partition. A `reg-app-schema` would simply never see this data.

;; ============================================================================
;; FX
;; ============================================================================

;; The one password the fake server accepts. Anything else gets a 401.
(def good-password "correct-horse")

(rf/reg-fx :auth.session/store
  {:doc       "Persist session token in localStorage. Client only."
   :platforms #{:client}}
  (fn fx-auth-session-store [_m {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (.setItem ls "auth/token" token))))

(rf/reg-fx :auth.login.demo/managed-stub
  {:doc       "A fake login server, so the demo runs with no backend. Stands in
               for `:rf.http/managed` and hands off to the framework's own
               canned-success / canned-failure fxs with `:after-ms` set, so the
               reply comes back through `:dispatch-later` after 50 ms. That
               little delay is deliberate: a `:dispatch-later` shows up in the
               tape and survives time-travel, where a raw `js/setTimeout` would
               do neither. Same stub the Reagent and Helix examples use."
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

;; The login flow itself, as a state machine. `[:schemas :data]` keeps an eye
;; on the `:data` slot (`{:attempts ... :error ...}`) — just that slot, not the
;; whole snapshot — as the note on `AuthLoginData` above explains.
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
             ;; `:sensitive? true` is the per-request wire scrub: it redacts
             ;; this request body — which is carrying the `:password` — from
             ;; every `:rf.http/*` trace event, so the secret never lands in the
             ;; tape. See docs/core/how-to/keep-secrets-out-of-traces.md.
             {:request    {:method :post
                           :url    "/api/login"
                           :body   creds
                           :request-content-type :json
                           :sensitive? true}
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
    ;; A fire-and-forget telemetry beacon: we POST the lockout and don't care
    ;; what comes back. `:on-success nil` / `:on-failure nil` hush both reply
    ;; branches. Leave them out and the default reply would dispatch
    ;; `[:auth.login/flow {:rf/reply ...}]` — a map where `AuthLoginEvent`
    ;; expects a sub-event, so it gets rejected, leaving stray noise rattling
    ;; around after lockout. See docs/resources/glossary.md#managed-http.
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
    ;; The :auth/busy tag is how the view knows a request is in flight. It asks
    ;; [:rf.machine/has-tag? :auth.login/flow :auth/busy] and, while that's
    ;; true, disables the inputs and relabels the submit button.
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
    ;; Retrying straight from :error-shown drops us back into :submitting, and
    ;; we have to wipe the old `:error` on the way through. If we don't, the
    ;; stale failure message hangs around (the view shows `:auth.login/error`
    ;; whenever it's non-nil) looking for all the world like it belongs to the
    ;; request now in flight. Same `:clear-error` action the :idle → :submitting
    ;; transition uses.
    {:on {:auth.login/dismiss {:target :idle}
          :auth.login/submit  {:target :submitting
                               :action :clear-error}}}

    :authed
    ;; Journey's end, the happy way. The :auth/authenticated tag tells the
    ;; banner to swap over to "Welcome!" once the flow lands here.
    {:tags #{:auth/authenticated}
     :meta {:terminal? true}}

    :locked-out
    ;; Journey's end, the unhappy way. After one failed submit too many the
    ;; flow comes to rest here, tagged :auth/locked. The view asks
    ;; [:rf.machine/has-tag? :auth.login/flow :auth/locked] and, when it's
    ;; true, retires the form for a locked-account panel that takes no more
    ;; submits. A dead end should look like one — visible and inert, not a form
    ;; that's quietly stopped working.
    {:tags #{:auth/locked}
     :meta {:terminal? true}}}})

;; Register the machine. Notice it's guarded on two fronts: `[:schemas :data]`
;; watches the `:data` slot from the inside, and `:schema` (our
;; `AuthLoginEvent`) checks every dispatched event vector at the `:where :event`
;; boundary from the outside. The opts map carries that event `:schema`
;; alongside the spec. See docs/machines/glossary.md#machine.
(rf/reg-machine :auth.login/flow
  {:doc    "Login flow: idle → submitting → authed / error-shown / locked-out."
   :schema AuthLoginEvent}
  auth-login-machine)

;; ============================================================================
;; FORM SLICE  (the form pattern — substrate-agnostic)
;; ============================================================================
;;
;; A login form is really two jobs wearing one coat, and we split them
;; cleanly. The slice owns the draft — whatever the user is typing right now —
;; at the app-db path [:auth :login-form]. The machine (above) owns the status:
;; are we submitting, did it work, are we locked out. That's the "machine +
;; slice" form pattern.
;;
;; The draft is application state like any other, so it lives in app-db — read
;; through subs, changed through events — never squirrelled away in a view-local
;; atom or hook. The inputs are controlled: each `:value` reads the draft sub,
;; each `:on-change` dispatches `:auth.login/edit-field`. The full recipe (slice
;; shape, the seven standard form events, the touched-or-submitted rule for when
;; to show errors) lives in docs/core/how-to/build-a-form.md.
;;
;; Everything from here down to the subs — slice defaults, form events, the
;; draft/slice subs — is the substrate-agnostic layer. It's identical across
;; examples/core/login, examples/substrates/uix/login, and examples/substrates/helix/login.
;; Only the view syntax tells the three apart.

;; An empty draft to start from. Its shape is `Credentials` (email regex +
;; min-8 password) — the very same schema the machine's `:submit` boundary
;; enforces, so the form and the machine agree on what "valid" means.
(def login-form-defaults {:email "" :password ""})

;; The pre-submit validator. It checks the draft against the very same
;; `Credentials` schema the machine's `:submit` boundary enforces, so the form
;; and the machine agree on "valid" down to the last character. `m/explain` +
;; `me/humanize` turn a schema miss into the form pattern's
;; `{<field> ["msg" ...]}` shape — exactly what `:auth.login/field-error`
;; renders — and a clean draft yields `{}`. See docs/core/how-to/build-a-form.md,
;; "Validation is a pure function".
(defn- validate
  [schema value]
  (or (some-> (m/explain schema value) me/humanize) {}))

;; Lay down the slice in its starting shape: empty `:draft`, `:status :idle`,
;; and the rest. Fires once, via the frame-provider's `:initial-events`.
(rf/reg-event :auth.login/initialise-form
  {:doc "Seed the login-form slice at [:auth :login-form] to its starting shape
         (empty draft, :idle status)."}
  (fn handler-login-form-initialise [{:keys [db]} _]
    {:db (assoc-in db [:auth :login-form]
                   {:draft             login-form-defaults
                    :submitted         nil
                    :submit-attempted? false
                    :status            :idle
                    :errors            {}
                    :touched           #{}
                    :submit-error      nil})}))

;; The user touched a field. Write the new value into `:draft` and remember the
;; field is now `:touched`. This is the whole job of an input's `:on-change` —
;; it never reaches for view-local state. The `:schema` turns away any
;; malformed edit-field vector at the `:where :event` boundary.
(rf/reg-event :auth.login/edit-field
  {:doc    "Controlled-input edit: write one field into the login-form draft
            and mark it touched."
   :schema [:cat [:= :auth.login/edit-field] :keyword :string]}
  (fn handler-login-form-edit-field [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in  [:auth :login-form :draft field] value)
             (update-in [:auth :login-form :touched] (fnil conj #{}) field))}))

;; Submit. First it *validates* the draft against `Credentials` — the same
;; schema the machine's `:submit` boundary enforces — and latches
;; `:submit-attempted?` either way, which is what lets every invalid field
;; speak up the moment the user first presses submit (the form pattern's
;; visibility rule, docs/core/how-to/build-a-form.md).
;;
;; On a clean draft this is the hand-off: flip `:status` to `:submitting`,
;; clear any stale field errors, and dispatch the draft into the machine. That
;; hand-off is the single place the draft ever crosses into the machine. From
;; there the machine — not the slice — owns the real story: in flight, authed,
;; errored, locked. The slice's `:status` is just a mirror.
;;
;; On an *invalid* draft we stop here: the field errors land in `:errors`
;; (rendered under each input) and nothing is dispatched. Handing a bad draft
;; to the machine would only bounce off its `:submit` schema boundary — and
;; because the view renders machine errors, that rejection would be *silent*,
;; the password cleared and the form quietly inert. So the form catches it
;; first, keeps the password for the fixup, and shows what's wrong.
;;
;; And a word on the password. On the clean branch we read it off the draft,
;; give it to the machine, then clear `[:draft :password]` in the very same
;; commit. Once the request is on its way the secret has done its job, and
;; there's no reason to leave it sitting in app-db where the next snapshot or
;; recording would scoop it up. Its only trip off the box is inside the HTTP
;; request body (scrubbed by that `:sensitive? true` flag), and it's never
;; written to `:submitted` or the machine's `:data`. See
;; docs/core/how-to/keep-secrets-out-of-traces.md.
(rf/reg-event :auth.login/submit-form
  {:doc "Submit the login form: validate the draft against Credentials. If
         clean, hand it to the :auth.login/flow machine's :submit sub-event and
         clear the password (secret-field hygiene); if not, surface the field
         errors and don't submit. Latches :submit-attempted? either way."}
  (fn handler-login-form-submit [{:keys [db]} _]
    (let [draft  (get-in db [:auth :login-form :draft])
          errors (validate Credentials draft)
          db'    (assoc-in db [:auth :login-form :submit-attempted?] true)]
      (if (empty? errors)
        {:db (-> db'
                 (assoc-in [:auth :login-form :status] :submitting)
                 (assoc-in [:auth :login-form :errors] {})
                 (assoc-in [:auth :login-form :draft :password] ""))
         :fx [[:dispatch [:auth.login/flow [:auth.login/submit draft]]]]}
        {:db (assoc-in db' [:auth :login-form :errors] errors)}))))

;; Wipe the slate: put the slice back to its empty, :idle starting shape.
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
;; The machine keeps its snapshot in runtime-db; these named subs reach in and
;; pick out the pieces a view actually wants. The "are we busy?" / "are we in?"
;; style questions don't need their own subs — views ask the
;; `:rf.machine/has-tag?` framework sub directly, further down. See
;; docs/machines/glossary.md#state-tag.

;; The whole snapshot is durable runtime-db state; the framework `:rf/machine`
;; sub is the way in. We layer on top of it to read just the current state.
(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [snapshot _] (:state snapshot)))

(rf/reg-sub :auth.login/error
  :<- [:rf/machine :auth.login/flow]
  (fn [snapshot _] (get-in snapshot [:data :error])))

;; --- Form-slice subs (substrate-agnostic) ----------------------------------
;;
;; The slice sits at app-db [:auth :login-form]. The view reads the draft
;; through `:auth.login/draft` and pins each input's `:value` to it — that's
;; what makes them controlled. The per-field-error sub follows the form
;; pattern's rule for when an error is allowed to show: once the field is
;; touched, or once the user has tried to submit. Like the events above, these
;; subs are shared verbatim across the three substrate examples.

(rf/reg-sub :auth.login/form-slice
  {:doc "The whole login-form slice at [:auth :login-form] — the root the other
         form subs branch off."}
  (fn sub-auth-login-form-slice [db _]
    (get-in db [:auth :login-form])))

(rf/reg-sub :auth.login/draft
  {:doc "The draft — what the user has typed so far. Each input binds its :value
         to a field of this map, which is what makes the inputs controlled."}
  :<- [:auth.login/form-slice]
  (fn sub-auth-login-draft [slice _]
    (:draft slice)))

(rf/reg-sub :auth.login/field-error
  {:doc "A field's validation error — but only when it's polite to show one:
         once the field has been touched, or once the user has taken their first
         run at submitting. See docs/core/how-to/build-a-form.md, step 3."}
  :<- [:auth.login/form-slice]
  (fn sub-auth-login-field-error [slice [_ field]]
    (when (or (:submit-attempted? slice)
              (contains? (:touched slice) field))
      (first (get-in slice [:errors field])))))

;; ============================================================================
;; VIEWS  (UIx — defui + use-subscribe)
;; ============================================================================
;;
;; Here, at last, is the substrate seam — and it's a thin one. A UIx view is
;; just a `defui`: it reads each subscription through the `use-subscribe` hook
;; and gets `dispatch` off `(rf/capture-frame)`. The Reagent twin registers the
;; same views with `reg-view` and is simply handed `dispatch`/`subscribe`. The
;; subscription vectors and event vectors don't change one character between
;; them; all that differs is how a React component reaches the wires. See
;; docs/core/how-to/use-uix-helix-or-slim.md.
;;
;; The inputs are controlled: each `:value` reads the draft from
;; `:auth.login/draft`, and `:on-change` dispatches `:auth.login/edit-field`.
;; The draft lives in app-db, which is exactly why you won't find a
;; `uix/use-state` anywhere in here.
(defui login-form []
  (let [draft     (uix-adapter/use-subscribe [:auth.login/draft])
        busy?     (uix-adapter/use-subscribe [:rf.machine/has-tag?
                                              :auth.login/flow :auth/busy])
        err       (uix-adapter/use-subscribe [:auth.login/error])
        email-err (uix-adapter/use-subscribe [:auth.login/field-error :email])
        pw-err    (uix-adapter/use-subscribe [:auth.login/field-error :password])
        dispatch  (:dispatch (rf/capture-frame))]
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
       (when email-err ($ :p.error {:data-testid "login-email-error"} email-err))
       ($ :input  {:type        "password"
                   :placeholder "Password"
                   :disabled    busy?
                   :data-testid "login-password"
                   :value       (:password draft)
                   :on-change   #(dispatch [:auth.login/edit-field :password (.. % -target -value)])})
       (when pw-err ($ :p.error {:data-testid "login-password-error"} pw-err))
       ($ :button {:type "submit" :disabled busy?
                   :data-testid "login-submit"}
          (if busy? "Signing in…" "Sign in"))
       (when err ($ :p.error {:data-testid "login-error"} err)))))

;; The dead-end panel, shown once the flow reaches :locked-out (tagged
;; :auth/locked). That state has nowhere left to go, so we swap the form out
;; for this rather than leave a form on screen that no longer does anything.
(defui locked-panel []
  ($ :div.locked {:data-testid "locked-panel"}
     ($ :h2 "Account locked")
     ($ :p "Too many failed attempts. Contact support to unlock.")))

(defui login-banner []
  (let [authed? (uix-adapter/use-subscribe [:rf.machine/has-tag?
                                            :auth.login/flow :auth/authenticated])
        locked? (uix-adapter/use-subscribe [:rf.machine/has-tag?
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

;; We stash the React root in an atom and only build it lazily inside `run`,
;; never at namespace load. The reason (examples/TESTING.md §Example
;; mount-isolation convention): loading a namespace must touch no DOM, so two
;; example namespaces loaded side by side can't both race to call `create-root`
;; on the shared `#app`.
(defonce react-root (atom nil))

(defn run []
  ;; Tell the runtime to render through UIx. (This installs the adapter; it does
  ;; not create a frame — the frame-provider below does that.)
  (rf/init! uix-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (uix-dom/create-root (js/document.getElementById "app"))))
    ;; Frame setup, all in one spot. The `frame-provider` at the render root
    ;; owns the frame: on the first mount it creates the `:rf/default` frame,
    ;; applies the config (`:fx-overrides` points `:rf.http/managed` at our demo
    ;; stub), and runs `:initial-events` once. On a hot reload it finds the frame
    ;; already there, reuses it, and skips the events. The `:id :rf/default`
    ;; names the frame that `use-subscribe` and the `(rf/capture-frame)` inside
    ;; `login-form` resolve against — which is why those calls need a provider
    ;; somewhere above them in the tree.
    ;;
    ;; `:initial-events` seeds the form slice ([:auth.login/initialise-form]) to
    ;; its empty shape, so the controlled inputs read an empty draft — not nil —
    ;; on that first render. The machine asks for nothing here: its `:initial`
    ;; and `:data` seed the snapshot in runtime-db the first time the flow runs
    ;; (see docs/machines/glossary.md#snapshot).
    (uix-dom/render-root
      ($ uix-adapter/frame-provider {:id              :rf/default
                                     :doc             "Login (UIx) demo frame."
                                     :fx-overrides    {:rf.http/managed :auth.login.demo/managed-stub}
                                     :initial-events  [[:auth.login/initialise-form]]}
         ($ root-view))
      @react-root)))
