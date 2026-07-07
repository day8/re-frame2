(ns login.core
  "A login feature, end to end.

   Here's the one idea worth carrying away. A login flow has a life — it
   sits idle, then it's submitting, then it's an error or a success or
   (after too many tries) a lockout. The tempting way to track that is a
   fistful of booleans: `submitting?`, `error?`, `locked?`. Don't. The
   moment you have three booleans you have eight combinations, most of
   them nonsense — `submitting? AND locked?` should never happen, yet
   nothing stops it. So instead we model the whole lifecycle as one state
   machine. Five states, named transitions, and a tiny `:data` slot for
   the attempt counter. You are always in exactly one state, and every
   legal move is right there in the transition table below — no illegal
   combination is even expressible. See the machines guide
   (docs/machines/concepts.md) and its glossary (docs/machines/glossary.md).

   With that idea in hand, here's how the pieces fit:

   - State machine — the login flow as a transition table. Its live value
     is read like any other derived state: through a subscription on the
     machine id.
   - State tags — `:auth/busy`, `:auth/authenticated`, `:auth/locked`.
     Views ask a question — `(rf/machine-has-tag? :auth.login/flow ...)` —
     rather than memorising exact state names
     (docs/machines/glossary.md#state-tag). The terminal `:locked-out`
     state becomes a non-interactive panel, not a form that's enabled but
     secretly dead.
   - Form slice — the email/password draft is an app-db slice at
     [:auth :login-form]. The slice owns the draft (controlled inputs, no
     view-local atom); the machine owns submit/auth status. That division
     of labour is the form recipe in docs/core/how-to/build-a-form.md.
   - Managed HTTP — `:rf.http/managed`, plus a small per-app demo stub
     that answers the request locally so the example runs with no backend
     to point it at (docs/async/http.md).
   - Schemas, events, subscriptions, registered views — the everyday
     building blocks (docs/core/glossary.md).

   In a real codebase you'd split this across login/schema.cljc,
   events.cljs, subs.cljs, views.cljs, and machines.cljs. It lives in one
   file here so you can read it top to bottom in one sitting.

   Examples are test-free: login's behaviour is covered by the substrate
   contract suite (`npm run test:cljs`) and the framework gates, not by a
   test alongside this file."
  ;; This example runs on stock Reagent. It's also the cross-substrate
  ;; reference base — mirrored 1:1 as `login-uix` and `login-helix` — so
  ;; staying on Reagent keeps the three an honest apples-to-apples
  ;; comparison.
  ;;
  ;; A note on the requires below: re-frame2 ships its bigger features
  ;; opt-in. You pay for what you use, and you say so by requiring it.
  ;; Each `re-frame.*` require here is a feature switching itself on.
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            ;; Turns on Malli validation, so the machine's `[:schemas :data]`
            ;; checks have something to run. (No app-db schema in this
            ;; example — machine snapshots live in runtime-db, not app-db.)
            [re-frame.schemas]
            ;; Turns on state machines — the hooks behind `rf/reg-machine`
            ;; and the `:rf/machine` subscription.
            [re-frame.machines]
            ;; Turns on managed HTTP. Skip this require and the first
            ;; `:rf.http/managed` dispatch fails loud rather than silently
            ;; doing nothing — which is exactly what you want.
            [re-frame.http.managed]
            ;; Turns on the canned-success / canned-failure stub fxs our
            ;; demo stub leans on (docs/core/testing/pipeline-runs.md).
            [re-frame.http.test-support]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; ============================================================================
;; SCHEMAS
;; ============================================================================
;;
;; Schemas describe the shape of data. re-frame2's are open by default:
;; they say what must be present, not what must be absent, so extra keys
;; are fine. See docs/core/how-to/validate-with-schemas.md.

;; The credentials the form collects — and the payload the submit event
;; carries. The regex and min-length aren't decoration: they're the
;; promise the machine's submit handler is allowed to rely on, enforced at
;; the boundary so a bad email or short password never reaches it.
;;
;; Note what's NOT promised here: that the password goes anywhere it
;; shouldn't. It's never written to app-db or the machine `:data`. Its one
;; and only trip off the box is the HTTP request body, and even that is
;; redacted from the trace by `:sensitive? true` on the managed-HTTP call
;; in `:issue-request` below (docs/async/http.md, "Keeping secrets out
;; of the trace").
(def Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

;; The shape of every event vector dispatched at the :auth.login/flow
;; machine. Get it wrong and the event is rejected at the boundary — the
;; handler simply never runs. The trick is that not all sub-events deserve
;; equal suspicion. The :submit comes straight from a human typing into a
;; form, so we check its payload strictly against `Credentials`. The
;; others (:dismiss, :success, :failure) originate inside the machine, so
;; we trust their tails and wave them through.
;;
;; Two details earn a word, because they're the kind of thing that's
;; baffling until someone points at them:
;;
;; - The :submit branch is a `:tuple`, not a `:cat`. The outer `:cat`
;;   treats the nested sub-event vector as one element, so the branch has
;;   to match that single element *as a vector*. The payoff: a short
;;   password or bad email is caught here, before the machine moves or
;;   fires off the login request.
;;
;; - The trailing `[:? :any]` is the slot for the HTTP reply. When a
;;   managed-HTTP call resolves, the framework appends its canonical reply
;;   envelope (`{:status :ok :value …}` or `{:status :error :error …}`) as
;;   the last arg of the `:on-success` / `:on-failure` event — so a delivered reply
;;   arrives with three top-level elements. Forget the optional slot and
;;   every reply gets rejected, leaving the flow marooned in `:submitting`
;;   forever. (Ask me how I know.)
(def AuthLoginEvent
  [:cat [:= :auth.login/flow]
   [:or
    [:tuple [:= :auth.login/submit] Credentials]
    [:cat [:enum :auth.login/dismiss :auth.login/success :auth.login/failure]
     [:* :any]]]
   [:? :any]])

;; A machine's live value is a *snapshot*: its current state plus a small
;; `:data` map, kept in runtime-db rather than app-db
;; (docs/machines/glossary.md#snapshot). The `[:schemas :data]` slot
;; validates that `:data` map and nothing else — not the whole snapshot.
;; So this schema describes exactly that map: the `:attempts` counter and
;; the last `:error`, which the machine seeds and its actions nudge along.
;; We hand it to the machine via the `[:schemas :data]` slot in the spec
;; below.
(def AuthLoginData
  [:map
   [:attempts {:default 0} :int]
   [:error    [:maybe :string]]])

;; Worth saying plainly, since it trips people up: because snapshots live
;; in runtime-db, app-db schemas (`reg-app-schema`) never see them. If you
;; want a snapshot validated, the machine's own `[:schemas :data]` is the
;; place — there is no other.

;; ============================================================================
;; FX  (managed HTTP + a per-app demo stub)
;; ============================================================================
;;
;; HTTP goes through `:rf.http/managed` (docs/async/http.md). In a real
;; app this flow would POST `/api/login` and wait for a server. This
;; example has no server — so we fake one, carefully.
;;
;; The plan: register a demo stub at `:auth.login.demo/managed-stub`, then
;; in `run` override `:rf.http/managed` to point at it for this frame. The
;; stub peeks at the request body's `:password` and conjures a success or
;; failure reply using the framework's own canned-success / canned-failure
;; fxs. The point of going through those fxs rather than hand-rolling a map
;; is fidelity: the reply has the exact same shape it would coming off a
;; real wire, so the rest of the flow can't tell the difference.

(def good-password "correct-horse")

(rf/reg-fx :auth.session/store
  {:doc       "Stash the session token in localStorage so the login
                survives a refresh. Client-only — localStorage is a
                browser thing, so `:platforms` keeps it off the server."
   :platforms #{:client}}
  (fn fx-auth-session-store [_m {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (.setItem ls "auth/token" token))))

(rf/reg-fx :auth.login.demo/managed-stub
  {:doc       "Our stand-in backend. It reads the URL and request body and
               decides, by hand, what the server would have said:

               POST /api/login with `:password good-password` → success,
                 carrying `{:user {...} :token \"demo-token-123\"}`.
               POST /api/login with anything else            → 401 failure.
               Anything else (e.g. /api/auth/lock)           → empty success.

               It doesn't build the reply itself; it delegates to the
               framework's canned-success / canned-failure fxs so the reply
               is shaped like the real thing. `:after-ms 50` holds the
               reply back by 50 ms — and crucially does so via
               `:dispatch-later`, not a raw `js/setTimeout`, so the delay
               stays on the tape and survives time-travel. Those 50 ms are
               a courtesy to the reader: just long enough to actually see
               the `:submitting` state before it resolves. The reply then
               travels home to the `:auth.login/success` /
               `:auth.login/failure` sub-events via `:on-success` /
               `:on-failure`."
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
;; Here's the heart of it. The login flow is a finite state machine: five
;; states, transitions with names. The spec map below is just *data* —
;; inert, a description of which moves are legal, nothing more. It doesn't
;; run anything by itself. Its live value is the snapshot
;; ({:state … :data …}) sitting in runtime-db, which you read like any
;; other derived state.
;;
;; A small but lovely property of this layout: guards and actions live in
;; their own `:guards` / `:actions` maps and the transition table refers to
;; them by keyword. So the table reads as pure intent — "on failure, if
;; under-retry-limit, record-error" — and the *how* is looked up
;; separately. The keywords resolve machine-locally, so two machines can
;; both have a `:clear-error` and never collide. See
;; docs/machines/concepts.md.

;; The spec itself. Notice it carries no `:id` — a spec is reusable data,
;; and the id is bestowed when we register it, just below.
(def auth-login-machine
  {:initial :idle
   ;; This validates the snapshot's `:data` slot on every step. Slip a
   ;; non-string into `:error` and the run fails loud rather than limping
   ;; on with corrupt data.
   :schemas {:data AuthLoginData}
   :data    {:attempts 0 :error nil}

     :guards
     {:under-retry-limit
      ;; The gatekeeper for retries: true while we've had fewer than 3
      ;; failures. When it goes false, the next failure locks the account.
      (fn [{data :data}]
        (< (:attempts data) 3))}

     :actions
     {:clear-error
      ;; Wipe any stale error before a fresh attempt.
      (fn [_]
        {:data {:error nil}})

      :issue-request
      ;; Fire off the login request. Note it *returns* effects rather than
      ;; performing them — the action stays pure; the runtime does the
      ;; dirty work. When the reply lands, the runtime tacks it onto the
      ;; end of the :on-success / :on-failure event
      ;; (`{:status :ok :value …}` / `{:status :error :error …}`).
      (fn [{[_ creds] :event}]
        {:fx [[:rf.http/managed
               ;; `:sensitive? true` is the secret-keeper. It scrubs the
               ;; request body (which is carrying the password) and all
               ;; params from every `:rf.http/*` trace event, so the
               ;; password never shows up in Xray or a recording
               ;; (docs/async/http.md, "Keeping secrets out of the
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
      ;; Remember what went wrong (for the UI) and tick the attempt
      ;; counter up by one (for the retry guard).
      ;; rf2-ibksxg — the classified failure map rides under :error.
      (fn [{data :data [_ {:keys [error]}] :event}]
        {:data (-> data
                   (update :attempts inc)
                   (assoc :error (or (:message error) "Login failed.")))})

      :lock-account
      ;; Slam the door after too many tries. This one is fire-and-forget:
      ;; we tell the server to lock the account but don't care to hear back,
      ;; so `:on-success nil` / `:on-failure nil` mute both reply branches.
      ;; (Leave them off and the default would dispatch a reply event that
      ;; `AuthLoginEvent` then rejects — a self-inflicted wound.)
      (fn [_]
        {:fx [[:rf.http/managed
               {:request    {:method :post :url "/api/auth/lock"}
                :on-success nil
                :on-failure nil}]]})

      :store-session
      ;; Login worked — squirrel away the token the server handed back.
      (fn [{[_ {:keys [value]}] :event}]
        {:fx [[:auth.session/store {:token (:token value)}]]})}

     :states
     {:idle
      {:on {:auth.login/submit {:target :submitting
                                :action :clear-error}}}

      :submitting
      ;; The `:auth/busy` tag is the "ask, don't tell" trick in action. The
      ;; view asks (rf/machine-has-tag? :auth.login/flow :auth/busy) to
      ;; disable inputs and relabel the button — it never asks "are we
      ;; exactly :submitting?". The reward comes later: add a second busy
      ;; state, tag it :auth/busy too, and the view needs zero changes.
      {:tags  #{:auth/busy}
       :entry :issue-request
       ;; Read the failure branch top to bottom: it's an *ordered* list of
       ;; candidates, and the first whose `:guard` passes wins. Still under
       ;; the retry limit? Show the error and let them try again. Out of
       ;; tries (the second entry has no guard, so it's the fallthrough)?
       ;; Lock the account. The entire retry-then-lockout policy lives in
       ;; these four lines.
       :on    {:auth.login/success {:target :authed
                                    :action :store-session}
               :auth.login/failure [{:target :error-shown
                                     :guard  :under-retry-limit
                                     :action :record-error}
                                    {:target :locked-out
                                     :action :lock-account}]}}

      :error-shown
      ;; Retrying straight from here re-enters :submitting, and it has to
      ;; clear the old `:error` on the way in. Skip that and the stale
      ;; failure message lingers on screen (the view shows
      ;; `:auth.login/error` whenever it's non-nil), looking for all the
      ;; world like it belongs to the request that's now in flight. Same
      ;; `:clear-error` we used leaving :idle.
      {:on {:auth.login/dismiss {:target :idle}
            :auth.login/submit  {:target :submitting
                                 :action :clear-error}}}

      :authed
      ;; The happy ending. `:terminal?` says the flow stops here; the
      ;; `:auth/authenticated` tag is what flips the banner to "Welcome!".
      {:tags #{:auth/authenticated}
       :meta {:terminal? true}}

      :locked-out
      ;; The unhappy ending. Once the retry guard gives out, the next
      ;; failure lands here for good. The `:auth/locked` tag lets the view
      ;; swap the form for a locked-account panel and stop taking submits.
      ;; A lockout should be plainly visible and inert — never a form
      ;; that's alive but quietly refuses to do anything.
      {:tags #{:auth/locked}
       :meta {:terminal? true}}}})

;; And now the spec becomes a machine, registered under :auth.login/flow.
;; That id is also an event handler id — dispatching at it drives the
;; machine. Because we also want the incoming event vectors checked, the
;; opts map carries `:schema` (our `AuthLoginEvent` boundary) right
;; alongside the spec.
(rf/reg-machine :auth.login/flow
  {:doc    "Login flow: idle → submitting → authed / error-shown / locked-out."
   :schema AuthLoginEvent}
  auth-login-machine)

;; ============================================================================
;; EVENTS
;; ============================================================================
;;
;; First, a freebie: the machine needs no `:initialise` event. It's
;; self-seeding — the first time it runs, its `:initial` state and `:data`
;; populate the snapshot for you.
;;
;; Everything below is the *other* half of the "machine + slice" division
;; of labour. The split is the thing to internalise: the slice owns the
;; draft — the half-typed email and password the user is fiddling with
;; right now, parked at app-db [:auth :login-form] — while the machine owns
;; the submit/auth status. Why keep the draft in app-db instead of a tidy
;; little view-local atom? Because a draft is application state like any
;; other, and re-frame2 likes its state in one place where subs can read it
;; and events can write it. So the inputs are *controlled*: `:value` reads
;; the draft sub, `:on-change` dispatches `:auth.login/edit-field`, and the
;; round trip through app-db is the only way a character gets on screen.
;; That's the form recipe in docs/core/how-to/build-a-form.md.
;;
;; One nice consequence: this whole block — slice defaults, the form
;; events, and the draft/slice subs further down — is substrate-agnostic.
;; It's identical across the reagent, uix, and helix login examples. Only
;; the views speak a different dialect.

;; The empty draft we start from. Its shape is `Credentials` (email regex,
;; 8-char password) — the very schema the machine's `:submit` boundary will
;; later hold it to.
(def login-form-defaults {:email "" :password ""})

;; Lay down the slice in its standard form-recipe shape — empty draft,
;; `:idle` status, all the bookkeeping fields blank. Dispatched once at
;; boot (from `run`) so the very first render reads a real draft.
(rf/reg-event :auth.login/initialise-form
  {:doc "Seed the login-form slice at [:auth :login-form] to its standard
         form-recipe shape (empty draft, :idle status)."}
  (fn handler-login-form-initialise [{:keys [db]} _]
    {:db (assoc-in db [:auth :login-form]
                   {:draft             login-form-defaults
                    :submitted         nil
                    :submit-attempted? false
                    :status            :idle
                    :errors            {}
                    :touched           #{}
                    :submit-error      nil})}))

;; A keystroke landed. Write the new value into `:draft` and remember the
;; user has now touched this field (that `:touched` set decides, later, when
;; it's fair to show a field's error). This single event is the *entire* job
;; of an input's `:on-change` — it sets no view-local state, because there
;; is none. The `:schema` swats away any malformed edit-field vector at the
;; `:where :event` boundary before the handler sees it.
(rf/reg-event :auth.login/edit-field
  {:doc    "Controlled-input edit: write one field into the login-form draft
            and mark it touched."
   :schema [:cat [:= :auth.login/edit-field] :keyword :string]}
  (fn handler-login-form-edit-field [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in  [:auth :login-form :draft field] value)
             (update-in [:auth :login-form :touched] (fnil conj #{}) field))}))

;; Submit. This is the handoff point — the one and only place the draft
;; crosses from the slice into the machine (and gets checked against
;; `Credentials` on the way). It reads the draft, latches
;; `:submit-attempted?`, nudges the slice `:status` to `:submitting`, and
;; dispatches the draft at the machine. From here on the *machine* owns the
;; in-flight / authed / error / locked story; the slice `:status` is just a
;; mirror tagging along.
;;
;; Now the careful bit — secret-field hygiene. In the same commit that hands
;; the password to the machine, we blank `[:draft :password]`. The thinking:
;; once the request is in flight the password has done its one job, and a
;; secret that lingers in app-db is a secret waiting to be caught in the
;; next snapshot or recording. So we don't let it linger. Its only sanctioned
;; trip off the box is the HTTP request body (and `:sensitive? true` scrubs
;; even that from the trace); it never touches `:submitted` or the machine
;; `:data`. See docs/core/how-to/add-auth.md.
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

;; Wipe the slate — slice back to empty, status back to `:idle`.
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

;; These subs pull the handy bits out of the machine snapshot so views
;; don't have to. You'll notice there's no "in :submitting?" or "in
;; :authed?" sub here — those questions are asked with `rf/machine-has-tag?`
;; in the views instead, which keeps the views from caring about exact state
;; names (docs/machines/glossary.md#state-tag).

;; The snapshot's current state, reached through the framework's
;; `:rf/machine` sub — the public doorway to a machine's live value
;; (docs/machines/glossary.md#snapshot).
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
;; The slice lives at app-db [:auth :login-form], and these three subs are
;; how the view gets at it. The draft sub feeds every input's `:value` (the
;; controlled-input loop), and the field-error sub bakes in the one rule
;; that matters for forms: don't shout an error at a field the user hasn't
;; touched yet (or before they've tried to submit). Like the form events,
;; these subs are byte-identical across the reagent, uix, and helix
;; examples.

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
         submit click (docs/core/how-to/build-a-form.md)."}
  :<- [:auth.login/form-slice]
  (fn sub-auth-login-field-error [slice [_ field]]
    (when (or (:submit-attempted? slice)
              (contains? (:touched slice) field))
      (first (get-in slice [:errors field])))))

;; ============================================================================
;; VIEWS
;; ============================================================================
;;
;; `reg-view` is `defn` with a superpower: inside the body, `dispatch` and
;; `subscribe` are already in scope, pre-bound to whichever frame the view
;; is rendering under. No threading a frame argument through every call.
;; That's also what lets the very same view mount in several isolated frames
;; at once (docs/core/views.md).

;; The login form. Read it and notice the absence — there is no
;; `reagent.core/atom`, no local state hiding anywhere. Each input's
;; `:value` comes from the `:auth.login/draft` sub; each `:on-change`
;; dispatches `:auth.login/edit-field`. The submit button dispatches
;; `:auth.login/submit-form`, which fetches the draft from app-db and feeds
;; the machine. And "are we busy?" / "what went wrong?" are answered by the
;; machine — the `:auth/busy` tag and the `:auth.login/error` sub. Slice
;; owns the draft; machine owns the status; the view just renders them.
(rf/reg-view ^{:doc "The login form view: email + password + submit button + error display."}
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

;; What you see once the flow hits :locked-out (tagged :auth/locked). That
;; state has no way out, so we replace the form wholesale instead of leaving
;; a zombie form on screen that takes input and ignores it.
(rf/reg-view ^{:doc "Locked-account panel shown when the login flow reaches :locked-out."}
          locked-panel []
  [:div.locked {:data-testid "locked-panel"}
   [:h2 "Account locked"]
   [:p "Too many failed attempts. Contact support to unlock."]])

;; The top-level switch. It reads two tags off the machine and shows one of
;; three faces: a welcome when authed, the locked panel when locked, and the
;; form the rest of the time.
(rf/reg-view ^{:doc "Picks what to show by login state: welcome / locked panel / the form."}
          login-banner []
  (let [authed? @(rf/machine-has-tag? :auth.login/flow :auth/authenticated)
        locked? @(rf/machine-has-tag? :auth.login/flow :auth/locked)]
    [:div.banner {:data-testid "login-banner"}
     (cond
       authed? [:span "Welcome!"]
       locked? [locked-panel]
       :else   [login-form])]))

(rf/reg-view root-view []
  [:div.app
   [:h1 "Sign in"]
   [login-banner]])

;; ============================================================================
;; MOUNT  (CLJS reference; client-only)
;; ============================================================================

;; We park the React root in an atom and create it lazily, inside `run` —
;; never at namespace load. The rule is: requiring a namespace should touch
;; the DOM exactly zero times. Otherwise, the moment another example
;; co-requires this one, two `create-root` calls race for `#app` and you get
;; the kind of bug that only shows up on Tuesdays.
(defonce react-root (atom nil))

(defn run []
  ;; Tell re-frame2 to render through Reagent. The adapter is just a spec
  ;; map; hand it straight to `init!`.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; Frame setup, all in one breath. Handing `frame-provider` an `{:id …}`
    ;; map is the "make sure this frame exists" shape: on first mount it
    ;; creates `:rf/default`, applies the config below, runs
    ;; `:initial-events` once, and scopes the frame into React. On hot reload
    ;; it finds the frame already there and leaves it alone — no double-seed.
    ;; See docs/core/frames.md.
    ;;
    ;; - `:fx-overrides` is where we swap in our fake backend: it points
    ;;   `:rf.http/managed` at the in-process stub above, so the example
    ;;   stands on its own with nothing to connect to.
    ;; - `:initial-events` seeds the login-form slice *before* the first
    ;;   render. Skip it and the inputs would read `nil` for their `:value`
    ;;   on that first paint — and React quietly demotes a `nil`-valued input
    ;;   to an uncontrolled one, which is a mess to debug later. The machine
    ;;   seeds itself; the slice is app-db, so we seed it here.
    ;;
    ;; And the provider isn't optional scenery: its scope is the reason the
    ;; views' injected `dispatch`/`subscribe` (and the machine reads) know to
    ;; talk to `:rf/default`. Render a `reg-view` outside any provider and it
    ;; fails loud rather than guessing.
    (rdc/render @react-root
                [rf/frame-provider {:id             :rf/default
                                    :doc            "Login demo frame."
                                    :fx-overrides   {:rf.http/managed :auth.login.demo/managed-stub}
                                    :initial-events [[:auth.login/initialise-form]]}
                 [root-view]])))
