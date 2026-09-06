(ns login.model
  "The login feature's SUBSTRATE-FREE model — the one owner of the shared
   `auth.login` dataflow.

   A login flow has a life. It sits idle, then it's submitting, then it's an
   error or a success or (after too many tries) a lockout. The tempting way to
   track that is a fistful of booleans: `submitting?`, `error?`, `locked?`.
   Don't. The moment you have three booleans you have eight combinations, most
   of them nonsense — `submitting? AND locked?` should never happen, yet nothing
   stops it. So instead we model the whole lifecycle as one state machine. Five
   states, named transitions, and a tiny `:data` slot for the attempt counter.
   You are always in exactly one state, and every legal move is right there in
   the transition table below — no illegal combination is even expressible. See
   the machines guide (docs/machines/concepts.md) and its glossary
   (docs/machines/glossary.md).

   With that idea in hand, here's how the pieces fit:

   - State machine — the login flow as a transition table. Its live value is
     read like any other derived state: through a subscription on the machine
     id.
   - State tags — `:auth/busy`, `:auth/authenticated`, `:auth/locked`. Views
     ask a question — `@(subscribe [:rf.machine/has-tag? :auth.login/flow ...])` —
     rather than memorising exact state names
     (docs/machines/glossary.md#state-tag). The terminal `:locked-out` state
     becomes a non-interactive panel, not a form that's enabled but secretly
     dead.
   - Form slice — the email/password draft is an app-db slice at
     [:auth :login-form]. The slice owns the draft (controlled inputs, no
     view-local atom); the machine owns submit/auth status. That division of
     labour is the form recipe in docs/core/how-to/build-a-form.md.
   - Managed HTTP — `:rf.http/managed`, plus a small per-app demo stub that
     answers the request locally so the example runs with no backend to point it
     at (docs/async/http.md).
   - Schemas, events, subscriptions — the everyday building blocks
     (docs/core/glossary.md).

   THE ONE OWNER. This namespace is the single source of truth for every shared
   `auth.login` registration — the schemas, the demo fx, the `:auth.login/flow`
   machine, the form-slice events, and the named subs — plus the initial form
   slice and the shared frame config. It names NO substrate: no view library, no
   adapter. The three login examples — Reagent (`login.core`), UIx
   (`uix.login.core`) and Hicasso (`hicasso.login.core`) — each `:require` this
   namespace for its side-effecting
   registrations and its `frame-config` / schema Vars, and add ONLY their own
   substrate-specific views, root, adapter init, and visible provider mount.
   One model, three view layers.

   Why one owner and not three copies? Because the logic of a login flow doesn't
   care which view library draws it, so duplicating it three times is 38
   redundant behavioural copies begging to drift — and it already had (the
   sub docstrings and machine key order diverged file to file). The three-way
   view comparison the examples teach lives in the views, roots, and mounts
   below the substrate boundary in each `core.cljs`; the model that comparison
   holds constant lives here, once. The bundle-isolation gate
   (`npm run test:bundle-isolation`) proves this namespace stays substrate-free:
   because all three builds import it, any adapter/view library it dragged in
   would leak into the two login bundles it doesn't belong in.

   SUBSTRATE-FREE AND PLATFORM-NEUTRAL. It is `.cljc`, not `.cljs`, and the
   second half of that is load-bearing rather than tidy: the Hicasso arm
   server-renders through a JVM Ring host
   (`examples/substrates/hicasso/login/host.clj`), and a JVM host has to hold
   the application's state — so every `auth.login` schema, fx, machine, event
   and sub has to be loadable from Clojure. One handler body needs a platform,
   the `localStorage` write in `:auth.session/store`, and it says so in place.

   In a real codebase you'd split this across login/schema.cljc, events.cljc,
   subs.cljc, and machines.cljc. It lives in one namespace here so you can read
   the whole model top to bottom in one sitting."
  ;; re-frame2 ships its bigger features opt-in. You pay for what you use, and
  ;; you say so by requiring it. Each `re-frame.*` require here is a feature
  ;; switching itself on — and note the absence: no adapter, no view library.
  ;; This namespace is substrate-free by construction.
  (:require [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]
            ;; Malli directly — for the pre-submit form validator below
            ;; (`m/explain` + `me/humanize`), the same pure validator the form
            ;; recipe builds (docs/core/how-to/build-a-form.md, "Validation is a
            ;; pure function").
            [malli.core :as m]
            [malli.error :as me]
            ;; Turns on Malli validation, so the machine's `[:schemas :data]`
            ;; checks have something to run. (No app-db schema in this example —
            ;; machine snapshots live in runtime-db, not app-db.)
            [re-frame.schemas]
            ;; Turns on state machines — the hooks behind `rf/reg-machine` and
            ;; the `:rf/machine` subscription.
            [re-frame.machines]
            ;; Turns on managed HTTP. Skip this require and the first
            ;; `:rf.http/managed` dispatch fails loud rather than silently doing
            ;; nothing — which is exactly what you want.
            [re-frame.http.managed]
            ;; Turns on the canned-success / canned-failure stub fxs our demo
            ;; stub leans on (docs/core/testing/pipeline-runs.md).
            [re-frame.http.test-support]))

;; ============================================================================
;; SCHEMAS
;; ============================================================================
;;
;; Schemas describe the shape of data. re-frame2's are open by default: they say
;; what must be present, not what must be absent, so extra keys are fine. See
;; docs/core/how-to/validate-with-schemas.md.

;; The credentials the form collects. Note where they go, because the natural
;; guess is wrong: they are NOT the payload of the submit event. The
;; `:auth.login/flow` machine's events are credential-free by construction (see
;; `AuthLoginEvent` below), so this schema never rides one.
;;
;; The regex and min-length aren't decoration either, and they are not enforced
;; by a schema boundary. `:auth.login/submit-form` runs the draft through this
;; schema in its own handler body and branches on the result — an ordinary
;; function call, present in every build. That is deliberate: a credential
;; check has to survive a release build, and it has to be able to *answer* by
;; putting field errors under the inputs. A registration schema can do neither.
;;
;; A word on the password, because a secret has to be classified at *every*
;; boundary it crosses, and this one crosses three
;; (docs/core/how-to/keep-secrets-out-of-traces.md):
;;
;;   1. It lives in the app-db draft while you type — redacted at egress by the
;;      `:sensitive` classification `:auth.login/initialise-form` installs.
;;   2. It rides the `:auth.login/edit-password` event in a map payload whose
;;      registration declares `:sensitive [[:value]]`, so the edit trace redacts it.
;;   3. It reaches the wire only through the managed-HTTP request body, scrubbed
;;      by `:sensitive? true`.
;;
;; It never enters the machine at all — `submit-form` issues the request and
;; hands the machine a credential-free signal. Three owners, one per boundary;
;; the live value stays readable to the handlers that need it.
(def Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

;; The shape of every event vector dispatched at the :auth.login/flow machine.
;; Get it wrong in a DEVELOPMENT build and the event is rejected at the
;; `:where :event` boundary — the handler simply never runs. Note the
;; qualification: this is a schema *this app* declares over its own
;; registration, so a release build eliminates it and runs no such check
;; ([Spec 010 §Production builds]). Read it as a tripwire that catches a wiring
;; mistake while you work, not as a gate standing between bad input and your
;; machine.
;;
;; Every sub-event here is credential-free: the machine tracks the
;; login lifecycle but never touches the password. The form validates the draft
;; against `Credentials` and issues the request itself (see submit-form below),
;; then nudges the machine with a bare `:submit` signal. So all four sub-events —
;; :submit, :dismiss, :success, :failure — take the same relaxed tail.
;;
;; The trailing `[:? :any]` is the slot for the HTTP reply. When a managed-HTTP
;; call resolves, the framework appends its canonical reply envelope
;; (`{:status :ok :value …}` or `{:status :error :error …}`) as the last arg of
;; the `:on-success` / `:on-failure` event — so a delivered reply arrives with
;; three top-level elements. Forget the optional slot and every reply gets
;; rejected, leaving the flow marooned in `:submitting` forever. (Ask me how I
;; know.)
(def AuthLoginEvent
  [:cat [:= :auth.login/flow]
   ;; The `[:schema …]` wrapper is load-bearing: without it, a `:cat` nested
   ;; directly inside a `:cat` *composes* (flattens) into the parent regex, so
   ;; the schema would match `[:auth.login/flow :auth.login/submit …]` instead of
   ;; the real `[:auth.login/flow [:auth.login/submit] …]`. `[:schema …]` resets
   ;; the regex context so this branch matches the nested sub-event vector.
   [:schema [:cat [:enum :auth.login/submit :auth.login/dismiss
                   :auth.login/success :auth.login/failure]
             [:* :any]]]
   [:? :any]])

;; A machine's live value is a *snapshot*: its current state plus a small
;; `:data` map, kept in runtime-db rather than app-db
;; (docs/machines/glossary.md#snapshot). The `[:schemas :data]` slot validates
;; that `:data` map and nothing else — not the whole snapshot. So this schema
;; describes exactly that map: the `:attempts` counter and the last `:error`,
;; which the machine seeds and its actions nudge along. We hand it to the machine
;; via the `[:schemas :data]` slot in the spec below.
(def AuthLoginData
  [:map
   [:attempts {:default 0} :int]
   [:error    [:maybe :string]]])

;; Worth saying plainly, since it trips people up: because snapshots live in
;; runtime-db, app-db schemas (`reg-app-schema`) never see them. If you want a
;; snapshot validated, the machine's own `[:schemas :data]` is the place — there
;; is no other.

;; ============================================================================
;; FX  (managed HTTP + a per-app demo stub)
;; ============================================================================
;;
;; HTTP goes through `:rf.http/managed` (docs/async/http.md). In a real app this
;; flow would POST `/api/login` and wait for a server. This example has no
;; server — so we fake one, carefully.
;;
;; The plan: register a demo stub at `:auth.login.demo/managed-stub`, then each
;; substrate mount overrides `:rf.http/managed` to point at it for its frame
;; (via the shared `frame-config` below). The stub peeks at the request body's
;; `:password` and conjures a success or failure reply using the framework's own
;; canned-success / canned-failure fxs. The point of going through those fxs
;; rather than hand-rolling a map is fidelity: the reply has the exact same shape
;; it would coming off a real wire, so the rest of the flow can't tell the
;; difference.

(def good-password "correct-horse")

(rf/reg-fx :auth.session/store
  {:doc       "Stash the session token in localStorage so the login
                survives a refresh. Client-only — localStorage is a
                browser thing, so `:platforms` keeps it off the server.

                This is the ONE handler body in this namespace that is not
                platform-neutral, and it is why the file is `.cljc` rather
                than `.cljs`. `:platforms #{:client}` already keeps the
                effect from RUNNING on a server; a reader conditional is
                what keeps `js/globalThis` from being READ there, so a JVM
                host can load the shared model at all. The two are
                different boundaries — one runtime, one compile-time — and
                a server-side host needs both."
   ;; The token is a credential, and fx args are a transient payload with their
   ;; OWN egress owner — separate from the event that produced them. So this
   ;; registration classifies its `:token` arg `:sensitive`: the per-effect
   ;; `:rf.fx/handled` trace redacts `[:token]` to `:rf/redacted` while the
   ;; handler body still receives the real token to write. (Classification is
   ;; egress-only; the value stays live for the side effect.) See owner 2 of
   ;; the success-token note above `:auth.login/succeeded`, and
   ;; docs/core/how-to/keep-secrets-out-of-traces.md.
   :sensitive [[:token]]
   :platforms #{:client}}
  (fn fx-auth-session-store [_m {:keys [token]}]
    #?(:cljs (when-let [ls (.-localStorage js/globalThis)]
               (.setItem ls "auth/token" token))
       ;; Unreachable on the JVM — `:platforms #{:client}` refuses the
       ;; effect before a handler body runs. Present so the namespace
       ;; READS on a Clojure classpath, which is what a JVM SSR host needs.
       :clj  (throw (ex-info "auth.session/store is client-only"
                             {:token-present? (some? token)})))))

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
               travels home via `:on-success` / `:on-failure` — success to the
               classified `:auth.login/succeeded` event (which owns the session
               token), failure to the machine's `:auth.login/failure`
               sub-event."
   ;; This stub is the CLASSIFIED OWNER of the request body it receives. The
   ;; login request carries the plaintext password at [:request :body :password],
   ;; and `submit-form` marks the request `:sensitive? true` — but that scrub
   ;; lives inside the REAL `:rf.http/managed` handler, which the `:fx-overrides`
   ;; remap to this stub BYPASSES. When the override fires, `handle-one-fx`
   ;; stamps the always-emitted `:rf.fx/handled` trace with THIS fx's id and its
   ;; RAW args, and the classification projector redacts `:rf.fx/args` off the
   ;; RESOLVED fx's own `:sensitive`. So the stub must declare its own — without
   ;; it the password would ride raw on the one wire every tool reads
   ;; (docs/core/how-to/keep-secrets-out-of-traces.md).
   :sensitive [[:request :body :password]]
   :platforms #{:server :client}}
  (fn fx-managed-login-demo [frame-ctx args-map]
    (let [{:keys [url body]} (:request args-map)
          login? (= "/api/login" url)]
      (cond
        (and login? (= good-password (:password body)))
        (let [stub (rf.registrar/handler :fx :rf.http/managed-canned-success)]
          (stub frame-ctx (assoc args-map
                                 :after-ms 50
                                 :value {:user  {:id    (random-uuid)
                                                 :email (:email body)}
                                         :token "demo-token-123"})))

        login?
        (let [stub (rf.registrar/handler :fx :rf.http/managed-canned-failure)]
          (stub frame-ctx (assoc args-map
                                 :after-ms 50
                                 :kind :rf.http/http-4xx
                                 :tags {:status  401
                                        :message "Invalid credentials."})))

        :else
        (let [stub (rf.registrar/handler :fx :rf.http/managed-canned-success)]
          (stub frame-ctx (assoc args-map :after-ms 50 :value {})))))))

;; ============================================================================
;; STATE MACHINE
;; ============================================================================
;;
;; Here's the heart of it. The login flow is a finite state machine: five
;; states, transitions with names. The spec map below is just *data* — inert, a
;; description of which moves are legal, nothing more. It doesn't run anything by
;; itself. Its live value is the snapshot ({:state … :data …}) sitting in
;; runtime-db, which you read like any other derived state.
;;
;; A small but lovely property of this layout: guards and actions live in their
;; own `:guards` / `:actions` maps and the transition table refers to them by
;; keyword. So the table reads as pure intent — "on failure, if
;; under-retry-limit, record-error" — and the *how* is looked up separately. The
;; keywords resolve machine-locally, so two machines can both have a
;; `:clear-error` and never collide. See docs/machines/concepts.md.

;; The spec itself. Notice it carries no `:id` — a spec is reusable data, and the
;; id is bestowed when we register it, just below.
(def auth-login-machine
  {:initial :idle
   ;; This validates the snapshot's `:data` slot on every step. Slip a
   ;; non-string into `:error` and the run fails loud rather than limping on with
   ;; corrupt data.
   :schemas {:data AuthLoginData}
   :data    {:attempts 0 :error nil}

   :guards
   {:under-retry-limit
    ;; The gatekeeper for retries: true while we've had fewer than 3 failures.
    ;; When it goes false, the next failure locks the account.
    (fn [{data :data}]
      (< (:attempts data) 3))}

   :actions
   {:clear-error
    ;; Wipe any stale error before a fresh attempt.
    (fn [_]
      {:data {:error nil}})

    :record-error
    ;; Remember what went wrong (for the UI) and tick the attempt counter up by
    ;; one (for the retry guard). The classified failure map rides under :error.
    (fn [{data :data [_ {:keys [error]}] :event}]
      {:data (-> data
                 (update :attempts inc)
                 (assoc :error (or (:message error) "Login failed.")))})

    :lock-account
    ;; Slam the door after too many tries. This one is fire-and-forget: we tell
    ;; the server to lock the account but don't care to hear back, so
    ;; `:on-success nil` / `:on-failure nil` mute both reply branches. (Leave
    ;; them off and the default would dispatch a reply event that
    ;; `AuthLoginEvent` then rejects — a self-inflicted wound.)
    (fn [_]
      {:fx [[:rf.http/managed
             {:request    {:method :post :url "/api/auth/lock"}
              :on-success nil
              :on-failure nil}]]})}
   ;; No `:store-session` action here — and that absence is the point. The
   ;; success reply carries the session TOKEN (a credential), and the machine
   ;; must never see a credential (same rule the password follows on the way
   ;; IN). So the token-bearing reply lands on `:auth.login/succeeded` — the
   ;; classified owner of the response credential — which persists the token and
   ;; then nudges the machine with a bare, credential-free `:success` signal. The
   ;; machine's `:success` transition below therefore takes NO action: it only
   ;; flips the flow to `:authed`. See `:auth.login/succeeded` below.

   :states
   {:idle
    {:on {:auth.login/submit {:target :submitting
                              :action :clear-error}}}

    :submitting
    ;; The `:auth/busy` tag is the "ask, don't tell" trick in action. The view
    ;; asks @(subscribe [:rf.machine/has-tag? :auth.login/flow :auth/busy]) to
    ;; disable inputs and relabel the button — it never asks "are we exactly
    ;; :submitting?". The reward comes later: add a second busy state, tag it
    ;; :auth/busy too, and the view needs zero changes.
    ;;
    ;; No `:entry` action here: the request was already fired by submit-form (the
    ;; credential owner). This state just waits for the reply and reacts.
    {:tags  #{:auth/busy}
     ;; Read the failure branch top to bottom: it's an *ordered* list of
     ;; candidates, and the first whose `:guard` passes wins. Still under the
     ;; retry limit? Show the error and let them try again. Out of tries (the
     ;; second entry has no guard, so it's the fallthrough)? Lock the account.
     ;; The entire retry-then-lockout policy lives in these four lines.
     :on    {:auth.login/success {:target :authed}
             :auth.login/failure [{:target :error-shown
                                   :guard  :under-retry-limit
                                   :action :record-error}
                                  {:target :locked-out
                                   :action :lock-account}]}}

    :error-shown
    ;; Retrying straight from here re-enters :submitting, and it has to clear the
    ;; old `:error` on the way in. Skip that and the stale failure message
    ;; lingers on screen (the view shows `:auth.login/error` whenever it's
    ;; non-nil), looking for all the world like it belongs to the request that's
    ;; now in flight. Same `:clear-error` we used leaving :idle.
    {:on {:auth.login/dismiss {:target :idle}
          :auth.login/submit  {:target :submitting
                               :action :clear-error}}}

    :authed
    ;; The happy ending. `:terminal?` says the flow stops here; the
    ;; `:auth/authenticated` tag is what flips the banner to "Welcome!".
    {:tags #{:auth/authenticated}
     :meta {:terminal? true}}

    :locked-out
    ;; The unhappy ending. Once the retry guard gives out, the next failure lands
    ;; here for good. The `:auth/locked` tag lets the view swap the form for a
    ;; locked-account panel and stop taking submits. A lockout should be plainly
    ;; visible and inert — never a form that's alive but quietly refuses to do
    ;; anything.
    {:tags #{:auth/locked}
     :meta {:terminal? true}}}})

;; And now the spec becomes a machine, registered under :auth.login/flow. That id
;; is also an event handler id — dispatching at it drives the machine. Because we
;; also want the incoming event vectors checked, the opts map carries `:schema`
;; (our `AuthLoginEvent` boundary) right alongside the spec.
(rf/reg-machine :auth.login/flow
  {:doc    "Login flow: idle → submitting → authed / error-shown / locked-out."
   :schema AuthLoginEvent}
  auth-login-machine)

;; ============================================================================
;; EVENTS
;; ============================================================================
;;
;; First, a freebie: the machine needs no `:initialise` event. It's self-seeding
;; — the first time it runs, its `:initial` state and `:data` populate the
;; snapshot for you.
;;
;; Everything below is the *other* half of the "machine + slice" division of
;; labour. The split is the thing to internalise: the slice owns the draft — the
;; half-typed email and password the user is fiddling with right now, parked at
;; app-db [:auth :login-form] — while the machine owns the submit/auth status.
;; Why keep the draft in app-db instead of a tidy little view-local atom? Because
;; a draft is application state like any other, and re-frame2 likes its state in
;; one place where subs can read it and events can write it. So the inputs are
;; *controlled*: `:value` reads the draft sub, `:on-change` dispatches an edit
;; event (`:auth.login/edit-field` for the email, `:auth.login/edit-password`
;; for the secret), and the round trip through app-db is the only way a character
;; gets on screen. That's the form recipe in docs/core/how-to/build-a-form.md.

;; The empty draft we start from. Its shape is `Credentials` (email regex, 8-char
;; password) — the schema `:auth.login/submit-form` holds the finished draft to
;; before it will issue the request. That handler is the only party that ever
;; checks it: the machine's events are credential-free by construction, so a
;; draft never reaches the machine at all.
(def login-form-defaults {:email "" :password ""})

;; The pre-submit validator — and the *only* thing that ever holds a draft to
;; `Credentials`. There is no second layer behind it to catch what it lets
;; through; the machine never sees a password, so there is nobody downstream to
;; agree or disagree with.
;;
;; Notice what KIND of check it is, because that is the whole point: an
;; ordinary function call in a handler body, so it runs in every build. The
;; schemas this app attaches to its own registrations (`AuthLoginEvent` above,
;; and app-db schemas generally) are development-build assertions, compile-time
;; eliminated from a release — a credential check cannot be one of those.
;;
;; `m/explain` + `me/humanize` turn a schema miss into the form pattern's
;; `{<field> ["msg" ...]}` shape — exactly what `:auth.login/field-error`
;; renders — and a clean draft yields `{}`. It's an ordinary pure function you
;; can call from a REPL; the slice doesn't care that it wraps Malli. See
;; docs/core/how-to/build-a-form.md, "Validation is a pure function".
(defn- validate
  [schema value]
  (or (some-> (m/explain schema value) me/humanize) {}))

;; Lay down the slice in its shape for this MACHINE-DRIVEN variant: the draft
;; plus the validation bookkeeping the form recipe needs (`:submit-attempted?`,
;; `:errors`, `:touched`). Notice what is ABSENT — no `:status` / `:submitted` /
;; `:submit-error` mirror. The generic form recipe carries those to track the
;; submit/auth lifecycle (docs/core/how-to/build-a-form.md), but here the
;; `:auth.login/flow` machine and its state tags ARE that lifecycle, so a
;; parallel slice mirror would be a second source of truth no view reads —
;; write-only state that could only drift out of step with the machine.
;; Dispatched once at boot (from each substrate mount's `:initial-events`) so the
;; very first render reads a real draft.
;;
;; This is also OWNER 1 of the password's three egress boundaries (see the
;; `Credentials` note above). Alongside the `:db` write we return a `:sensitive`
;; classification effect that marks the draft-password app-db path — installed
;; here, in the *first* durable write that creates the slice, so no keystroke can
;; ever race the classification. Classification is value-independent and
;; egress-only: whatever value later comes to rest at
;; `[:auth :login-form :draft :password]` reads `:rf/redacted` in every app-db
;; snapshot, epoch, and off-box record, while handlers still see the real value
;; (docs/core/how-to/keep-secrets-out-of-traces.md, "Classify a durable secret
;; in app-db").
(rf/reg-event :auth.login/initialise-form
  {:doc "Seed the login-form slice at [:auth :login-form] for this machine-driven
         variant (empty draft + validation bookkeeping; the machine owns the
         submit/auth lifecycle, so the slice carries no :status mirror), and
         classify the draft-password path :sensitive so it is redacted at every
         egress."}
  (fn handler-login-form-initialise [{:keys [db]} _]
    {:db (assoc-in db [:auth :login-form]
                   {:draft             login-form-defaults
                    :submit-attempted? false
                    :errors            {}
                    :touched           #{}})
     :sensitive [[:auth :login-form :draft :password]]}))

;; A keystroke landed on a NON-SECRET field (the email). Write the new value into
;; `:draft` and remember the user has now touched this field (that `:touched` set
;; decides, later, when it's fair to show a field's error). This single event is
;; the *entire* job of a non-secret input's `:on-change` — it sets no view-local
;; state, because there is none. The `:schema` swats away any malformed
;; edit-field vector at the `:where :event` boundary.
;;
;; Note this event carries its value *positionally* — fine for an email, which
;; you wouldn't mind seeing in a trace. A positional secret, though, ships raw:
;; redaction is path-based and only the arg-map is addressable, so there's no way
;; to classify a positional arg. That's exactly why the password gets its own
;; map-shaped, classified event just below, rather than riding here
;; (docs/core/how-to/keep-secrets-out-of-traces.md, "a positional secret ships
;; raw"). Never route a secret through this event.
(rf/reg-event :auth.login/edit-field
  {:doc    "Controlled-input edit for a NON-SECRET field (email): write it into
            the login-form draft and mark it touched. Secrets use
            :auth.login/edit-password instead."
   :schema [:cat [:= :auth.login/edit-field] :keyword :string]}
  (fn handler-login-form-edit-field [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in  [:auth :login-form :draft field] value)
             (update-in [:auth :login-form :touched] (fnil conj #{}) field))}))

;; OWNER 2 of the password's three egress boundaries. The password's keystrokes
;; get their own event, and — the whole point — a MAP payload `{:value …}` rather
;; than a positional arg, so the registration can name a path into it. The
;; `:sensitive [[:value]]` declaration classifies that `:value` key: the
;; dispatched-event trace (and any error record carrying this event) redacts it
;; to `:rf/redacted`, while the handler body still receives the real keystroke to
;; write into the draft. Splitting the secret field off keeps the email edit
;; visible — only the password's value is redacted, not the email's.
(rf/reg-event :auth.login/edit-password
  {:doc       "Controlled-input edit for the PASSWORD field: write the value
               into the login-form draft and mark it touched. The value rides a
               map payload classified :sensitive so the edit trace redacts it."
   :sensitive [[:value]]
   :schema    [:cat [:= :auth.login/edit-password] [:map [:value :string]]]}
  (fn handler-login-form-edit-password [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in  [:auth :login-form :draft :password] value)
             (update-in [:auth :login-form :touched] (fnil conj #{}) :password))}))

;; Submit. First it *validates* the draft against `Credentials` — this handler
;; body is where that check happens, and the only place it happens — and latches
;; `:submit-attempted?`
;; either way, which is the whole trick behind the visibility rule: the moment
;; the user first presses submit, every invalid field is allowed to speak
;; (docs/core/how-to/build-a-form.md, step 3).
;;
;; If the draft is clean, this is the handoff point. It clears any stale field
;; errors, issues the login request itself, and nudges the machine with a
;; credential-free `:submit` signal. From here on the *machine* owns the whole
;; in-flight / authed / error / locked story — read through its state tags — so
;; the slice keeps no parallel `:status` of its own to fall out of step.
;;
;; If the draft is *invalid*, we stop right here: the field errors land in
;; `:errors` (rendered under each input) and nothing is dispatched. The
;; alternative — wave the bad draft through and let some schema refuse it
;; downstream — fails for a reason worth understanding, and it is not that a
;; schema would miss it. A schema rejection's entire recovery is "skip the
;; handler", so it is *silent*: nothing lands in `:errors`, nothing renders
;; under the inputs, and the user is left with a form that quietly does
;; nothing. So the form catches it first, keeps the password for the fixup, and
;; shows the user exactly what's wrong.
;;
;; And the secret-field hygiene. The draft password is already classified
;; `:sensitive` (owner 1), so it reads redacted at every egress even while it
;; lives in app-db — but a password is transient, so once the request is in
;; flight we also blank `[:draft :password]` to keep its *live* lifetime short.
;; Classification covers the observable shadow; blanking retires the live value.
;; The password never touches the machine — only the HTTP body carries it
;; off-box, scrubbed by `:sensitive? true`. See
;; docs/core/how-to/keep-secrets-out-of-traces.md.
(rf/reg-event :auth.login/submit-form
  {:doc "Submit the login form: validate the draft against Credentials. If
         clean, issue the (sensitive) managed-HTTP login request, nudge the
         :auth.login/flow machine with a credential-free :submit signal, and
         blank the password (secret-field hygiene); if not, surface the field
         errors and don't submit. Latches :submit-attempted? either way."}
  (fn handler-login-form-submit [{:keys [db]} _]
    (let [draft  (get-in db [:auth :login-form :draft])
          errors (validate Credentials draft)
          db'    (assoc-in db [:auth :login-form :submit-attempted?] true)]
      (if (empty? errors)
        {:db (-> db'
                 (assoc-in [:auth :login-form :errors] {})
                 (assoc-in [:auth :login-form :draft :password] ""))
         :fx [;; Advance the machine — a credential-free signal. The machine
              ;; owns the lifecycle; it never sees the password.
              [:dispatch [:auth.login/flow [:auth.login/submit]]]
              ;; OWNER 3/4 — submit-form holds the validated draft, so it is the
              ;; classified owner that sends the credential. The request body
              ;; carries the real password; `:sensitive? true` scrubs it (and
              ;; every param) from every `:rf.http/*` trace event, so the wire
              ;; never sees it.
              ;;
              ;; The reply comes home NOT to the machine, but to
              ;; `:auth.login/succeeded` — a classified, map-payload event that
              ;; owns the response credential (the session token). Why not
              ;; straight to the machine? Because a managed-HTTP reply is
              ;; appended as the LAST positional arg of `:on-success`, and a
              ;; positional arg ships raw (redaction is path-based, only a map
              ;; payload is addressable). Routing the reply to the machine would
              ;; make the token an unclassifiable positional arg on the machine
              ;; event; routing it to a one-element event makes the reply the
              ;; addressable arg-map instead. The failure reply still rides the
              ;; machine (it carries an error message, not a credential).
              [:rf.http/managed
               {:request    {:method :post
                             :url    "/api/login"
                             :body   draft
                             :request-content-type :json
                             :sensitive? true}
                :decode     :json
                :on-success [:auth.login/succeeded]
                :on-failure [:auth.login/flow [:auth.login/failure]]}]]}
        {:db (assoc-in db' [:auth :login-form :errors] errors)}))))

;; The success reply comes home here — and this event exists FOR the session
;; token's egress hygiene, exactly the way `:auth.login/edit-password` exists for
;; the password's. On the way IN, the password crosses three boundaries and each
;; gets its own classified owner; on the way BACK, the token the server hands us
;; is a credential too, and it crosses two transient boundaries — so it gets two
;; owners here.
;;
;;   OWNER 1 — THE REPLY EVENT (this registration). Managed HTTP appends its
;;   canonical reply as the LAST positional arg of the `:on-success` event. Point
;;   it at a one-element `[:auth.login/succeeded]` and the reply lands in the
;;   SECOND slot — the arg-map — which IS path-addressable, so
;;   `:sensitive [[:value :token]]` redacts the token to `:rf/redacted` in the
;;   dispatched-event trace (and any error record carrying this event) while the
;;   handler still reads the real token. (Had we appended it to the two-element
;;   machine event `[:auth.login/flow [:auth.login/success]]`, the reply would be
;;   a THIRD positional arg — unaddressable, shipping raw. That's the whole
;;   reason this event exists rather than routing success straight to the
;;   machine.)
;;
;;   OWNER 2 — THE STORAGE FX. `:auth.session/store` declares `:sensitive
;;   [[:token]]` on its own registration, because fx args are a separate
;;   transient owner from the event that produced them (see its reg-fx above).
;;
;; The handler then nudges the machine with a bare, credential-free
;; `[:auth.login/flow [:auth.login/success]]` — the machine flips to `:authed`
;; and never sees the token. One credential, two egress owners, and a machine
;; kept credential-free end to end. See
;; docs/core/how-to/keep-secrets-out-of-traces.md.
;;
;; FRAMEWORK COVERAGE (rf2-6h3c02): the `:rf.event/fx` slot on the
;; `:rf.fx/do-fx` trace stamps this handler's WHOLE returned effect vector, and
;; the central classification projector now walks each `[fx-id args]` entry
;; through that fx's own registration — so `:auth.session/store`'s `:sensitive
;; [[:token]]` redacts the `:rf.event/fx` aggregate too, mirroring the sibling
;; `:rf.event/db` walk (Spec 009 §Canonical per-event trace sequence). The same
;; registration-owned redaction now also covers the always-on fx error traces
;; (`:rf.error/fx-handler-exception` + siblings), keyed off the slot SHAPE rather
;; than op `:rf.fx/handled`. No app-side classification is needed for these
;; framework slots — the `:auth.session/store` reg-fx above is their single owner.
(rf/reg-event :auth.login/succeeded
  {:doc       "Managed-HTTP login succeeded: the reply carries the session
               token. Persist it (via the classified :auth.session/store fx) and
               nudge the :auth.login/flow machine with a credential-free :success
               signal. The reply rides a map payload classified :sensitive so the
               token is redacted at event egress."
   :sensitive [[:value :token]]
   :schema    [:cat [:= :auth.login/succeeded] [:map [:value [:map [:token :string]]]]]}
  (fn handler-login-succeeded [_ [_ {:keys [value]}]]
    {:fx [;; Owner 2 persists the real token; its reg-fx classification redacts
          ;; the per-effect trace.
          [:auth.session/store {:token (:token value)}]
          ;; The machine gets a bare success fact — no credential.
          [:dispatch [:auth.login/flow [:auth.login/success]]]]}))

;; Wipe the slate — slice back to empty defaults.
(rf/reg-event :auth.login/reset-form
  {:doc "Clear the login-form slice back to empty defaults."}
  (fn handler-login-form-reset [{:keys [db]} _]
    {:db (assoc-in db [:auth :login-form]
                   {:draft             login-form-defaults
                    :submit-attempted? false
                    :errors            {}
                    :touched           #{}})}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

;; These subs pull the handy bits out of the machine snapshot so views don't have
;; to. You'll notice there's no "in :submitting?" or "in :authed?" sub here —
;; those questions are asked with the `[:rf.machine/has-tag? …]` sub in the views
;; instead, which keeps the views from caring about exact state names
;; (docs/machines/glossary.md#state-tag).

;; The snapshot's current state, reached through the framework's `:rf/machine`
;; sub — the public doorway to a machine's live value
;; (docs/machines/glossary.md#snapshot).
(rf/reg-sub :auth.login/state
  {:doc "Current state of the login flow."
   :inputs [[:rf/machine :auth.login/flow]]}
  (fn sub-auth-login-state [[snapshot] _]
    (:state snapshot)))

(rf/reg-sub :auth.login/error
  {:doc "Current error message, if any."
   :inputs [[:rf/machine :auth.login/flow]]}
  (fn sub-auth-login-error [[snapshot] _]
    (get-in snapshot [:data :error])))

;; --- Form-slice subs --------------------------------------------------------
;;
;; The slice lives at app-db [:auth :login-form], and these three subs are how
;; the view gets at it. The draft sub feeds every input's `:value` (the
;; controlled-input loop), and the field-error sub bakes in the one rule that
;; matters for forms: don't shout an error at a field the user hasn't touched yet
;; (or before they've tried to submit).

(rf/reg-sub :auth.login/form-slice
  {:doc "The whole login-form slice at [:auth :login-form]."}
  (fn sub-auth-login-form-slice [db _]
    (get-in db [:auth :login-form])))

(rf/reg-sub :auth.login/draft
  {:doc "The login-form draft — what the user has currently typed. Each input
         binds its :value to a field of this map (controlled inputs)."
   :inputs [[:auth.login/form-slice]]}
  (fn sub-auth-login-draft [[slice] _]
    (:draft slice)))

(rf/reg-sub :auth.login/field-error
  {:doc "Per-field validation error for the login form. Reveal a field's
         error once it is :touched OR once the form has had its first
         submit click (docs/core/how-to/build-a-form.md)."
   :inputs [[:auth.login/form-slice]]}
  (fn sub-auth-login-field-error [[slice] [_ field]]
    (when (or (:submit-attempted? slice)
              (contains? (:touched slice) field))
      (first (get-in slice [:errors field])))))

;; ============================================================================
;; FRAME CONFIG  (substrate-free)
;; ============================================================================
;;
;; The one piece of frame config the three mounts share. The Reagent and UIx
;; entries merge it into their `frame-root` props alongside a
;; substrate-specific `:id` / `:doc`; the Hicasso entry merges it into the
;; `rf/make-frame` call its root then joins, because `h/mount!`'s config carries
;; no `:fx-overrides` key. Same map either way:
;;
;;   - `:fx-overrides` swaps in our fake backend: it points `:rf.http/managed` at
;;     the in-process demo stub above, so the example stands on its own with
;;     nothing to connect to.
;;   - `:initial-events` seeds the login-form slice *before* the first render.
;;     Skip it and the inputs would read `nil` for their `:value` on that first
;;     paint — and React quietly demotes a `nil`-valued input to an uncontrolled
;;     one, which is a mess to debug later. The machine seeds itself; the slice
;;     is app-db, so we seed it here.
;;
;; Both values name only `auth.login` ids — no substrate. See docs/core/frames.md.
(def frame-config
  {:fx-overrides   {:rf.http/managed :auth.login.demo/managed-stub}
   :initial-events [[:auth.login/initialise-form]]})
