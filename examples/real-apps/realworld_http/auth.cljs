(ns realworld-http.auth
  "Authentication for the RealWorld (Conduit) example.

   Auth is one of those things that looks like a pile of unrelated buttons —
   sign in, sign up, restore my session, log out — but is really a single
   state machine wearing four hats. So that's how it's modelled here: login,
   register, session restore, and logout are all sub-events fed into one
   machine through one handler. The machine itself is CREDENTIAL-FREE, though
   — the credential-owning form/restore events fire the HTTP request
   themselves and only ever nudge the machine with a bare signal:

     (rf/dispatch [:auth/flow [:auth/login]])

   The login and register draft forms live in app-db under [:auth ...]; the
   machine's own snapshot lives over in runtime-db at
   [:rf.runtime/machines :snapshots :auth/flow]. See the AUTH STATE MACHINE
   section below for why the machine stays credential-free."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; State machines live in their own artefact. We require it purely
            ;; for the side effect of loading it: that registers the hooks that
            ;; make `rf/reg-machine` (below) and the `:rf/machine` sub resolve
            ;; to something. No alias needed — we just want it in the room. See
            ;; the machines guide: ../../../docs/machines/index.md
            [re-frame.machines]
            ;; Wire contract (UserResponse) from the shared ns; the machine's
            ;; own snapshot `:data` schema (AuthFlowData) is app-local.
            [realworld-shared.schema :as schema]
            [realworld-http.schema :as app-schema]
            [realworld-http.http :as rh])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; FX / COFX
;; ============================================================================
;;
;; Talking to localStorage is a side effect, so it gets its own fx — one
;; little doorway between our pure handlers and the browser's storage. The arg
;; is `{:token <token-or-nil>}`: a truthy token writes, nil removes. Keeping it
;; to a single seam means there's exactly one thing to stub in tests; the
;; classified `:auth/session-established` / `:auth/session-restored` events
;; and the machine's `:clear-session` action all call it, just with different
;; args.
;;
;; One detail that isn't ours to choose: the official RealWorld E2E suite reads
;; the session straight out of `localStorage["jwtToken"]`, so we use that exact
;; key, un-namespaced, verbatim. The contract assumes one app per origin. The
;; repo's dev orchestrator serves both RealWorld variants from one origin, so
;; they share this key there; standalone serving gives each app its own origin.

(rf/reg-fx :auth.session/persist
  {:doc       "Save or clear the JWT in localStorage, under the contract key
               `jwtToken`. `{:token t}` writes a truthy token; nil removes the
               key entirely."
   ;; fx args are a transient payload with their OWN egress owner, separate
   ;; from the event that produced them — so this registration classifies its
   ;; own `:token` arg `:sensitive`. The per-effect `:rf.fx/handled` trace
   ;; redacts `[:token]` while the handler body still receives the real token
   ;; to write. Mirrors examples/core/login's `:auth.session/store` fx. See
   ;; ../../../docs/core/how-to/keep-secrets-out-of-traces.md
   :sensitive [[:token]]
   :platforms #{:client}}
  (fn fx-auth-session-persist [_m {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (if token
        (.setItem    ls "jwtToken" token)
        (.removeItem ls "jwtToken")))))

;; At boot we read the saved JWT out of localStorage and `:auth/initialise`
;; folds it into durable app-db at [:auth :token]. Here's the subtlety: a
;; durable write has to fold a RECORDED fact, not a live localStorage read
;; taken in the handler — otherwise replay and epoch-restore, which don't have
;; your localStorage, can't reproduce it. So the JWT arrives as a recordable
;; coeffect. `:auth.session/token` is a `:recordable? true` registration whose
;; supplier reads localStorage; that supplier runs once, its value is recorded
;; onto the causal token, and from then on replay hands back the captured token
;; verbatim. See the coeffects guide on the two grades:
;; ../../../docs/core/coeffects.md#two-grades-ambient-and-recordable
;;
;; It's also why `ssr.cljc` can happily redact [:auth :token] from the
;; hydration payload: the client doesn't need the server to ship it the token,
;; it re-derives it through this recorded boot coeffect.
(defn read-jwt-from-storage
  "Read the saved JWT out of localStorage, or nil if there isn't one — the
   supplier behind the `:auth.session/token` recordable coeffect. nil when
   the key is absent or storage isn't there at all (node, or a logged-out
   first run)."
  []
  (some-> (.-localStorage js/globalThis)
          (.getItem "jwtToken")))

(rf/reg-cofx :auth.session/token
  {:recordable? true
   :doc "Recordable coeffect: the saved JWT (or nil), read from localStorage.
         The supplier fires once, at the start of the boot dispatch, and its
         value is recorded onto the causal token — so the durable write that
         folds it (`:auth/initialise` → [:auth :token]) replays the captured
         token rather than re-reading localStorage on every replay. A handler
         opts in by declaring `:rf.cofx/requires [:auth.session/token]` and
         reading it flat. Live dispatches carry no cofx — the supplier is the
         source of truth; tests pin an exact value through the dispatch-site
         `:rf.cofx` stub (`{:rf.cofx {:auth.session/token \"…\"}}`)."}
  (fn [] (read-jwt-from-storage)))

;; ============================================================================
;; SUPPORT EVENTS
;; ============================================================================

;; The shared `:db` write both `:auth/store-session` (below) and the
;; classified reply events (`:auth/session-established` /
;; `:auth/session-restored` further down, plus settings.cljs's
;; `:settings/submit-success`) need. It is a PLAIN FUNCTION, not a second
;; event, and that is load-bearing: the token-bearing `user` is only ever
;; SAFE to move between handlers as an already-classified event's OWN
;; arg-map (path-addressable, `:sensitive` reaches it). Handing it to a
;; SECOND event via a nested `[:dispatch [:auth/store-session user]]` fx
;; would not be — `:dispatch`'s own fx registration carries no
;; `:sensitive`, so the classification on the event it TARGETS does not
;; reach the DISPATCHING handler's own `:rf.fx/handled` / `:rf.event/fx`
;; trace (a framework-projector gap, tracked separately; see the reply
;; events' docs). So every credential-classified caller below computes this
;; `:db` write DIRECTLY and INLINE, never by dispatching a second event with
;; the raw token still in tow.
(defn store-session-db
  "Public (not `defn-`) so settings.cljs's own classified reply handler
   (`:settings/submit-success`) can call it directly too, for the identical
   reason — its PUT /user reply also carries a fresh User+token."
  [db user]
  (-> db
      (assoc-in [:auth :user] (dissoc user :token))
      (assoc-in [:auth :token] (:token user))))

(rf/reg-event :auth/store-session
  {:doc "Stash the authenticated session — the standalone, directly-dispatchable
         form of `store-session-db` (test fixtures and any external caller
         dispatch this by name). The JWT gets exactly one durable home: the
         classified `[:auth :token]` path (marked sensitive by
         `:auth/classify-token` in core.cljs). The User payload goes to
         `[:auth :user]` — but with its `:token` field `dissoc`'d off first,
         so the JWT doesn't end up quietly duplicated at the unclassified
         `[:auth :user :token]`. That second copy would ship raw off-box,
         because classification doesn't propagate down the tree — each path
         declares its own sensitivity, so two copies means two declarations,
         and we'd have forgotten one. Views and subs read `:auth/user` for
         who-you-are (username, bio, image); the bearer-auth interceptor reads
         the token from `[:auth :token]`. The incoming `user` arg is itself the
         positional second element of THIS event's own dispatched-event trace,
         and it still carries the token the durable write is about to strip —
         so `:sensitive [[1 :token]]` redacts that positional slot too. Note
         this event is for DIRECT/top-level dispatch only — a classified
         caller inlines `store-session-db` instead of routing through here via
         a nested `:dispatch` (see that fn's doc). See the keep-secrets
         how-to: ../../../docs/core/how-to/keep-secrets-out-of-traces.md"
   :sensitive [[1 :token]]}
  (fn [{:keys [db]} [_ user]]
    {:db (store-session-db db user)}))

(rf/reg-event :auth/clear-session
  (fn [{:keys [db]} _]
    {:db (-> db
        (assoc-in [:auth :user] nil)
        (assoc-in [:auth :token] nil))}))

;; ONE definition of "we don't know who this is yet", used from two places that
;; must not be allowed to drift: the `:auth/restoring-session?` sub (views) and
;; the `:rf.route/entry-denied` handler (routing.cljs), which needs the same
;; answer from inside an ordinary event handler where subs aren't available. Two
;; copies of this predicate is how you get a deep link that is deferred by one
;; site and never settled by the other.
;;
;; It reads the two DURABLE paths rather than the machine's `:restoring` state
;; on purpose: the machine snapshot lives in runtime-db and is not reachable
;; from a `:db` value, and the durable slice is the thing the guard judges.
(defn restoring-session?
  "True when a saved JWT is present but the user it stands for has not been
   restored yet — the cold-boot window in which identity is genuinely unknown.
   PURE: takes an app-db value, reads nothing ambient."
  [db]
  (and (nil? (get-in db [:auth :user]))
       (not (str/blank? (get-in db [:auth :token])))))

(rf/reg-event :auth/post-login-redirect
  {:doc "Drop the freshly-signed-in user back where they were headed before
         the auth guard sent them to login (`[:auth :return-to]`, stashed in
         routing.cljs), or home if there's no such crumb. Dispatched only by
         `:auth/session-established` (interactive login/register success) —
         never by `:auth/session-restored`, since cold-boot restore must keep
         you where you are, not force a navigation. It reads and clears the
         slot in the same step, so a later ordinary login can't get bounced
         to a stale target left lying around."}
  (fn [{:keys [db]} _]
    (let [return-to (get-in db [:auth :return-to])]
      {:db (update db :auth dissoc :return-to)
       ;; The stash IS the resolved address (path + params + query + #fragment)
       ;; — a valid :rf.route/navigate request in its own right — so return
       ;; there wholesale. A partial {:to :params} would drop the query string
       ;; and #fragment and land the user somewhere subtly wrong. :replace? true
       ;; keeps /login off the back stack.
       :fx [[:dispatch (if return-to
                         [:rf.route/navigate (assoc return-to :replace? true)]
                         [:rf.route/navigate {:to :realworld/home}])]]})))

;; ----------------------------------------------------------------------------
;; THE COLD-BOOT DEEP-LINK WINDOW  (the one genuinely subtle bit of this app)
;; ----------------------------------------------------------------------------
;;
;; Here is the sequence that has to work, and it is worth spelling out because
;; getting it wrong is invisible until someone bookmarks `/settings`.
;;
;; The frame runs its `:initial-events` first, so `:auth/initialise` has put the
;; saved JWT in app-db before the URL-bound frame's first URL→slice sync (core.cljs
;; §MOUNT). But the IDENTITY that token stands for arrives from `GET /user`, and
;; frame setup settles only SYNCHRONOUS work — it does not await an in-flight
;; request (EP-0027 §Construction). So the first URL resolution judges a reader
;; whose token is known and whose user is not yet.
;;
;; `:can-enter` is a closed boolean — there is no third "ask me again later"
;; answer, and there should not be: a guard that could return `:pending` would
;; put a tri-state in every app's auth sub. Instead the guard says the honest
;; `false` (no user, no entry), entry denial does what it always does — commits
;; NOTHING, parks nothing, terminal — and the DENIAL HANDLER decides what that
;; refusal means. During the restore window it means "wait", not "go to login":
;; routing.cljs stashes the destination and stays put. Then whichever way restore
;; settles, this event resolves the stash:
;;
;;   restore succeeds → a FRESH navigate at the stashed destination. Denial was
;;                      terminal, so this is an ordinary new attempt the guard
;;                      re-evaluates from scratch — no bypass flag, no resume.
;;   restore fails    → the reader is parked on a URL nothing committed, so land
;;                      them on login and KEEP the stash for the return trip.
;;
;; A PUBLIC deep link never enters this window at all: its route commits on the
;; first sync, no denial fires, nothing is stashed, and this event is a no-op.
(rf/reg-event :auth/settle-deferred-entry
  {:doc "Resolve the protected deep link `:rf.route/entry-denied` DEFERRED while
         cold-boot identity was unknown. Dispatched once restore has settled, by
         BOTH outcomes — `:auth/session-restored` (success) and the machine's
         `:abandon-restore` action (failure) — and it reads the settled auth slice
         rather than being told which happened. No stash → nothing to settle, and
         the deep link the reader cold-booted on stays exactly where it is."}
  (fn handler-auth-settle-deferred-entry [{:keys [db]} _]
    (let [return-to (get-in db [:auth :return-to])
          restored? (some? (get-in db [:auth :user]))]
      (cond
        ;; No deferred entry: a public deep link, or no deep link at all.
        (nil? return-to)
        {}

        ;; Identity restored — take the fresh attempt. Read AND clear the stash
        ;; in one step, exactly as `:auth/post-login-redirect` does, so a later
        ;; interactive login can't get bounced to a stale target.
        restored?
        {:db (update db :auth dissoc :return-to)
         :fx [[:dispatch [:rf.route/navigate (assoc return-to :replace? true)]]]}

        ;; Restore failed (expired / revoked / unreachable). The stash SURVIVES:
        ;; a successful interactive sign-in returns the reader to the exact
        ;; destination via `:auth/post-login-redirect`.
        :else
        {:fx [[:dispatch [:rf.route/navigate {:to       :realworld.auth/login
                                              :replace? true}]]]}))))

;; ============================================================================
;; AUTH STATE MACHINE
;; ============================================================================

;; The machine is deliberately CREDENTIAL-FREE, the same split
;; `examples/core/login` teaches: a password is not path-addressable once it
;; rides a machine dispatch as a POSITIONAL sub-event ([:auth/flow
;; [:auth/login {:password ...}]] — no `:sensitive` mark can reach it there),
;; so rather than lean on that, the credential-bearing forms (below) validate
;; their own draft, fire `:rf.http/managed` THEMSELVES, and only ever nudge
;; this machine with a bare, credential-free signal (`:auth/login`,
;; `:auth/register` with no args). The token-bearing success reply is the
;; mirror image on the way back: it's routed through the classified ordinary
;; events `:auth/session-established` / `:auth/session-restored` (below) —
;; never straight into the machine — so the reply lands in a path-addressable
;; arg-map their own `:sensitive` can redact, and only a bare `:success`
;; signal reaches the machine. See the keep-secrets how-to:
;; ../../../docs/core/how-to/keep-secrets-out-of-traces.md
;;
;; The machine carries a `[:schemas :data]` that validates its snapshot's
;; `:data` slot, and registering it here is what brings that schema to life
;; instead of leaving it as decoration. This one only validates its `:data`
;; (there's no outer event-vector `:schema`), so the opts map is short: just
;; `:doc` and `:rf.http/decode-schemas`.
(rf/reg-machine :auth/flow
  {:doc "The auth flow in one line: idle → submitting/restoring → authed |
         error. Requests go out via `:rf.http/managed`, fired by the
         credential-owning form/restore events, never by this machine — it
         only ever sees bare, credential-free signals. Login, register, and
         restore don't retry — it's one submission per click; a transient
         failure parks a message in `:error` and the user takes another swing."
   :rf.http/decode-schemas [schema/UserResponse]}
  ;; No :id in the spec map — the id is just the `reg-machine` id above.
  {:initial :idle
   :data    {:error nil}
   ;; The snapshot lives in runtime-db (at
   ;; [:rf.runtime/machines :snapshots :auth/flow]), not app-db. App-schemas
   ;; only police the app-db partition, so the snapshot's :data shape is
   ;; validated right here via [:schemas :data] instead.
   :schemas {:data app-schema/AuthFlowData}
   :guards
   ;; `:auth/initialise` below now passes a plain boolean, not the JWT itself
   ;; — the guard only ever needed the presence/absence question, and routing
   ;; the raw token through as a positional sub-event arg would ship it raw
   ;; in the dispatched-event + machine trace slots for no reason: the
   ;; `:begin-restore` action doesn't read the token either (the bearer-auth
   ;; interceptor already stamps it from the classified `[:auth :token]` path).
   {:has-token?
    (fn [{[_ has-token?] :event}]
      (boolean has-token?))}
   :actions
   {:clear-error
    (fn [_]
      {:data {:error nil}})

    ;; No :begin-login / :begin-register actions here — see the namespace
    ;; note above the machine. `:auth.login-form/submit` and
    ;; `:auth.register-form/submit` (below) issue the request themselves and
    ;; nudge :idle → :submitting with a bare signal; :clear-error is all the
    ;; transition needs to do.

    :begin-restore
    ;; Cold-boot restore carries no typed credential (GET /user, Bearer
    ;; header only) — it's fine for this one action to keep firing its own
    ;; request. Its success reply DOES carry the token, though, so it's
    ;; routed through the classified `:auth/session-restored` event rather
    ;; than straight back into the machine.
    (fn [_]
      {:data {:error nil}
       :fx [[:rf.http/managed
             (rh/request {:method     :get
                          :path       "/user"
                          :decode     schema/UserResponse
                          :on-success [:auth/session-restored]
                          :on-failure [:auth/flow [:auth/restore-failed]]})]]})

    :record-error
    (fn [{[_ {:keys [error]}] :event}]
      {:data {:error (rh/failure->message error)}})

    :abandon-restore
    ;; Cold-boot restore FAILED — the saved JWT was rejected (expired, revoked)
    ;; or the server was unreachable. Clear the now-invalid session and the
    ;; persisted token, then STAY PUT: a deep link to a PUBLIC page must remain
    ;; readable as an anonymous visitor. Being yanked home is the price of a
    ;; deliberate LOGOUT (`:clear-session` below), not of a failed restore.
    ;; `:auth/settle-deferred-entry` is what handles the one case where staying
    ;; put would strand the reader on a blank page — a PROTECTED deep link the
    ;; guard deferred, for which nothing was ever committed. It runs after
    ;; `:auth/clear-session` has committed, so it sees a confirmed-anonymous
    ;; slice and takes its login branch.
    (fn [_]
      {:data {:error nil}
       :fx [[:dispatch [:auth/clear-session]]
            [:auth.session/persist {:token nil}]
            [:dispatch [:auth/settle-deferred-entry]]]})

    :clear-session
    ;; Interactive LOGOUT (from :authed): clear the session and the persisted
    ;; token, then navigate home — signing out deliberately lands you on the
    ;; public home page. A FAILED RESTORE uses `:abandon-restore` above instead.
    (fn [_]
      {:data {:error nil}
       :fx [[:dispatch [:auth/clear-session]]
            [:auth.session/persist {:token nil}]
            [:dispatch [:rf.route/navigate {:to :realworld/home}]]]})}
   :states
   {:idle
    {:on {:auth/login    {:target :submitting :action :clear-error}
          :auth/register {:target :submitting :action :clear-error}
          :auth/restore  [{:target :restoring :guard :has-token? :action :begin-restore}
                          {:target :idle}]}}

    :submitting
    ;; No action on :auth/success — the credential-owning form event
    ;; (`:auth/session-established`) already stored the session and
    ;; redirected BEFORE nudging the machine here; this transition is purely
    ;; the state flip.
    {:on {:auth/success {:target :authed}
          :auth/failure {:target :error  :action :record-error}}}

    :restoring
    {:on {:auth/success        {:target :authed}
          :auth/restore-failed {:target :idle   :action :abandon-restore}}}

    :authed
    {:on {:auth/logout {:target :idle :action :clear-session}
          :auth/login  {:target :submitting :action :clear-error}}}

    :error
    {:on {:auth/login    {:target :submitting :action :clear-error}
          :auth/register {:target :submitting :action :clear-error}
          :auth/dismiss  {:target :idle       :action :clear-error}}}}})

;; ============================================================================
;; SESSION-ESTABLISHING REPLY EVENTS  (the token's two egress boundaries)
;; ============================================================================
;;
;; These exist FOR the session token's egress hygiene, exactly the way
;; `:auth.login-form/edit-password` below exists for the password's. Managed
;; HTTP appends its canonical reply as the SECOND positional arg of a
;; one-element `:on-success` target — landing in the arg-map, which IS
;; path-addressable — so `:sensitive [[:value :user :token]]` redacts the
;; token in the dispatched-event trace (and any error record carrying this
;; event) while the handler still reads the real token. Had the reply instead
;; targeted the machine directly (`[:auth/flow [:auth/success]]`), it would
;; land as an unaddressable THIRD positional element of that vector — exactly
;; the trap the keep-secrets how-to warns about. That's the whole reason these
;; events exist rather than routing success straight to the machine.
;;
;; Two variants because interactive login/register and cold-boot restore
;; disagree about navigation: an interactive submit bounces to `:return-to`
;; (or home); restore must never yank a deep-linked visitor away from the
;; page they cold-booted on. Both persist the session via the classified
;; `:auth.session/persist` fx (`:sensitive [[:token]]`, see above) and then
;; nudge the machine with a bare, credential-free `:success` signal — the
;; machine flips state and never sees the token. See
;; ../../../docs/core/how-to/keep-secrets-out-of-traces.md
;;
;; Both call `store-session-db` DIRECTLY (returning `:db` themselves) rather
;; than dispatching `:auth/store-session` — see that helper's doc for why a
;; nested nested `[:dispatch [:auth/store-session user]]` would leak the raw
;; token at THIS handler's own `:rf.fx/handled` / `:rf.event/fx` trace
;; regardless of `:auth/store-session`'s own classification.

(rf/reg-event :auth/session-established
  {:doc       "Interactive login/register succeeded: store the session,
               persist the JWT, and bounce to `:return-to` (or home). The
               reply rides a map payload classified :sensitive so the token
               is redacted at event egress."
   :sensitive [[:value :user :token]]}
  (fn handler-auth-session-established [{:keys [db]} [_ {:keys [value]}]]
    (let [user (:user value)]
      {:db (store-session-db db user)
       :fx [[:auth.session/persist {:token (:token user)}]
            [:dispatch [:auth/post-login-redirect]]
            [:dispatch [:auth/flow [:auth/success]]]]})))

(rf/reg-event :auth/session-restored
  {:doc       "Cold-boot session restore succeeded (a saved JWT was present
               and accepted): store the session, re-persist the JWT
               defensively (in case the server rotated it), and settle any
               protected deep link the guard DEFERRED while identity was
               unknown. It never navigates on its own account — a public deep
               link must stay put. The reply rides a map payload classified
               :sensitive so the token is redacted at event egress."
   :sensitive [[:value :user :token]]}
  (fn handler-auth-session-restored [{:keys [db]} [_ {:keys [value]}]]
    (let [user (:user value)]
      {:db (store-session-db db user)
       ;; `:db` commits before `:fx` runs, so `:auth/settle-deferred-entry`
       ;; below reads the RESTORED user and takes its "let them in" branch.
       :fx [[:auth.session/persist {:token (:token user)}]
            [:dispatch [:auth/settle-deferred-entry]]
            [:dispatch [:auth/flow [:auth/success]]]]})))

;; ============================================================================
;; INITIALISATION + SESSION RESTORE
;; ============================================================================

(rf/reg-event :auth/initialise
  {:rf.cofx/requires [:auth.session/token]}
  (fn handler-auth-initialise [{:keys [db auth.session/token]} _]
    ;; Always fire `:auth/flow [:auth/restore has-token?]`, even when
    ;; `has-token?` is false. Why bother restoring nothing? Because that first
    ;; event is also what spawns the machine's snapshot (a machine
    ;; materialises at `:idle` on its very first event), so the navbar's
    ;; `:auth/state` sub reads `:idle` from cold boot instead of an awkward
    ;; `nil`. The actual do-we-have-a-token? question then belongs to one
    ;; place — the `:idle` state's `:has-token?` guard: false takes the no-op
    ;; `{:target :idle}` branch, true kicks off `:begin-restore`. Guarding the
    ;; dispatch up here would skip the machine spawn on a no-token boot AND
    ;; split that one decision across two sites, which is how subtle bugs are
    ;; born.
    ;;
    ;; The dispatch carries a plain BOOLEAN, not the raw token — the guard
    ;; only ever needed presence/absence, and the token itself already has a
    ;; durable home at the classified `[:auth :token]` path this same `:db`
    ;; write creates. Passing the token positionally here instead would ship
    ;; the JWT raw on this event's own dispatched-event trace for no reason:
    ;; `:begin-restore` reads the token back off `[:auth :token]` via the
    ;; bearer-auth interceptor, never off this event's args.
    {:db (assoc db :auth {:user nil
                          :token token})
     :fx [[:dispatch [:auth/flow [:auth/restore (not (str/blank? token))]]]]}))

;; ============================================================================
;; FORMS
;; ============================================================================

(def login-form-defaults    {:email "" :password ""})
(def register-form-defaults {:username "" :email "" :password ""})

(rf/reg-event :auth.login-form/initialise
  {:doc "Seed the login-form slice AND classify its draft-password path
         :sensitive, in the first durable write that creates the slice — so the
         raw password is redacted at every app-db egress (snapshot, epoch,
         off-box shipper, SSR) from the very first keystroke, while handlers
         still read the live value. Classification is egress-only + path-based,
         mirroring :auth/classify-token for the JWT (core.cljs). See the
         keep-secrets how-to:
         ../../../docs/core/how-to/keep-secrets-out-of-traces.md"}
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:auth :login-form]
              {:draft             login-form-defaults
               :submitted         nil
               :status            :idle
               :errors            {}
               :touched           #{}
               :submit-attempted? false
               :submit-error      nil})
     :sensitive [[:auth :login-form :draft :password]]}))

(rf/reg-event :auth.login-form/edit-field
  {:doc    "Controlled-input edit for a NON-SECRET login field (email). A
            positional arg is fine here — an email is nothing you'd mind
            seeing in a trace. The password uses
            :auth.login-form/edit-password instead; never route a secret
            through this event (docs/core/how-to/keep-secrets-out-of-traces.md,
            \"a positional secret ships raw\")."
   :schema [:cat [:= :auth.login-form/edit-field] :keyword :string]}
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
        (assoc-in [:auth :login-form :draft field] value)
        (update-in [:auth :login-form :touched] (fnil conj #{}) field))}))

;; The password's keystrokes get their OWN event and a MAP payload — the
;; whole point being that a map is path-addressable and a positional arg
;; isn't. `:sensitive [[:value]]` classifies that `:value` key, so the
;; dispatched-event trace redacts it while the handler still writes the real
;; keystroke into the draft. Mirrors examples/core/login's
;; `:auth.login/edit-password`.
(rf/reg-event :auth.login-form/edit-password
  {:doc       "Controlled-input edit for the PASSWORD field: write the value
               into the login-form draft and mark it touched. The value rides
               a map payload classified :sensitive so the edit trace redacts
               it."
   :sensitive [[:value]]
   :schema    [:cat [:= :auth.login-form/edit-password] [:map [:value :string]]]}
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
        (assoc-in [:auth :login-form :draft :password] value)
        (update-in [:auth :login-form :touched] (fnil conj #{}) :password))}))

(rf/reg-event :auth.login-form/submit
  {:doc "Submit the login form. This is the credential-owning handoff point:
         it reads the validated draft, issues the (sensitive) managed-HTTP
         login request itself, blanks the password (secret-field hygiene, the
         draft's classification already covers the observable shadow), and
         nudges the :auth/flow machine with a bare, credential-free :auth/login
         signal. The success reply routes to the classified
         :auth/session-established event, never straight to the machine — see
         the machine's namespace note above."
   :rf.http/decode-schemas [schema/UserResponse]}
  (fn [{:keys [db]} _]
    (let [draft (get-in db [:auth :login-form :draft])]
      {:db (-> db
               (assoc-in [:auth :login-form :submit-attempted?] true)
               (assoc-in [:auth :login-form :status] :submitting)
               (assoc-in [:auth :login-form :draft :password] ""))
       :fx [[:dispatch [:auth/flow [:auth/login]]]
            [:rf.http/managed
             (rh/request {:method     :post
                          :path       "/users/login"
                          :body       {:user draft}
                          :sensitive? true
                          :decode     schema/UserResponse
                          :on-success [:auth/session-established]
                          :on-failure [:auth/flow [:auth/failure]]})]]})))

(rf/reg-event :auth.register-form/initialise
  {:doc "Seed the register-form slice AND classify its draft-password path
         :sensitive, in the first durable write that creates the slice — same
         egress-only, path-based discipline as the login form above."}
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:auth :register-form]
              {:draft             register-form-defaults
               :submitted         nil
               :status            :idle
               :errors            {}
               :touched           #{}
               :submit-attempted? false
               :submit-error      nil})
     :sensitive [[:auth :register-form :draft :password]]}))

(rf/reg-event :auth.register-form/edit-field
  {:doc "Controlled-input edit for a NON-SECRET register field (username,
         email). The password uses :auth.register-form/edit-password
         instead; never route a secret through this event."}
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
        (assoc-in [:auth :register-form :draft field] value)
        (update-in [:auth :register-form :touched] (fnil conj #{}) field))}))

;; Same map-payload / :sensitive [[:value]] split as the login form's
;; password event above.
(rf/reg-event :auth.register-form/edit-password
  {:doc       "Controlled-input edit for the PASSWORD field: write the value
               into the register-form draft and mark it touched. The value
               rides a map payload classified :sensitive so the edit trace
               redacts it."
   :sensitive [[:value]]
   :schema    [:cat [:= :auth.register-form/edit-password] [:map [:value :string]]]}
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
        (assoc-in [:auth :register-form :draft :password] value)
        (update-in [:auth :register-form :touched] (fnil conj #{}) :password))}))

(rf/reg-event :auth.register-form/submit
  {:doc "Submit the register form. Same credential-owning handoff as the login
         form above: fires the (sensitive) managed-HTTP register request
         itself, blanks the password, and nudges the machine with a bare
         :auth/register signal. Shares :auth/session-established with login —
         both successes store the session and bounce the same way."
   :rf.http/decode-schemas [schema/UserResponse]}
  (fn [{:keys [db]} _]
    (let [draft (get-in db [:auth :register-form :draft])]
      {:db (-> db
               (assoc-in [:auth :register-form :submit-attempted?] true)
               (assoc-in [:auth :register-form :status] :submitting)
               (assoc-in [:auth :register-form :draft :password] ""))
       :fx [[:dispatch [:auth/flow [:auth/register]]]
            [:rf.http/managed
             (rh/request {:method     :post
                          :path       "/users"
                          :body       {:user draft}
                          :sensitive? true
                          :decode     schema/UserResponse
                          :on-success [:auth/session-established]
                          :on-failure [:auth/flow [:auth/failure]]})]]})))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :auth/user
  (fn [db _] (get-in db [:auth :user])))

(rf/reg-sub :auth/token
  (fn [db _] (get-in db [:auth :token])))

(rf/reg-sub :auth/restoring-session?
  {:doc "True for the ONE window where the reader's identity is genuinely
         unknown: a saved JWT is in app-db but the user it stands for has not
         come back from `GET /user` yet. The view layer's door onto
         `restoring-session?`; the `:rf.route/entry-denied` handler
         (routing.cljs) calls the same predicate directly, because an event
         handler has a db value but no subs."}
  (fn [db _] (restoring-session? db)))

;; Snapshots live in runtime-db, but you don't go digging for them by path
;; — `:rf/machine` is the front door. Read through it.
(rf/reg-sub :auth/flow-state
  {:doc "The current auth machine snapshot."
   :inputs [[:rf/machine :auth/flow]]}
  (fn [[snapshot] _] snapshot))

(rf/reg-sub :auth/state
  {:inputs [[:auth/flow-state]]}
  (fn [[snapshot] _]
    (:state snapshot)))

(rf/reg-sub :auth/error
  {:inputs [[:auth/flow-state]]}
  (fn [[snapshot] _]
    (get-in snapshot [:data :error])))

(rf/reg-sub :auth/authenticated?
  {:inputs [[:auth/state]]}
  (fn [[state] _]
    (= state :authed)))

(rf/reg-sub :auth/submitting?
  {:inputs [[:auth/state]]}
  (fn [[state] _]
    (or (= state :submitting) (= state :restoring))))

(rf/reg-sub :auth.login-form/draft
  (fn [db _] (get-in db [:auth :login-form :draft])))

(rf/reg-sub :auth.login-form/slice
  (fn [db _] (get-in db [:auth :login-form])))

(rf/reg-sub :auth.login-form/field-error
  {:doc "The validation error for one login-form field — or nil while we're
         keeping quiet. The rule of politeness: don't nag about a field until
         the user has either touched it or hit submit at least once. See the
         forms how-to: ../../../docs/core/how-to/build-a-form.md"
   :inputs [[:auth.login-form/slice]]}
  (fn [[form] [_ field]]
    (when (or (:submit-attempted? form)
              (contains? (:touched form) field))
      (get-in form [:errors field]))))

(rf/reg-sub :auth.register-form/draft
  (fn [db _] (get-in db [:auth :register-form :draft])))

(rf/reg-sub :auth.register-form/slice
  (fn [db _] (get-in db [:auth :register-form])))

(rf/reg-sub :auth.register-form/field-error
  {:doc "The validation error for one register-form field, or nil while we
         hold our tongue. Same rule as the login form: stay quiet until the
         field is touched or the user has tried to submit. See the forms
         how-to: ../../../docs/core/how-to/build-a-form.md"
   :inputs [[:auth.register-form/slice]]}
  (fn [[form] [_ field]]
    (when (or (:submit-attempted? form)
              (contains? (:touched form) field))
      (get-in form [:errors field]))))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ^{:doc "Login page."}
          login-page []
  (let [draft       @(subscribe [:auth.login-form/draft])
        submitting? @(subscribe [:auth/submitting?])
        err         @(subscribe [:auth/error])]
    [:div.auth-page {:data-testid "login-page"}
     [:h1 "Sign in"]
     [rf/route-link {:to :realworld.auth/register} "Need an account?"]
     (when err [:ul.error-messages [:li err]])
     [:form
      {:data-testid "login-form"
       :on-submit (fn [e]
                    (.preventDefault e)
                    (dispatch [:auth.login-form/submit]))}
      [:fieldset
       [:fieldset.form-group
        [:input {:type        "email"
                 :name        "email"
                 :data-testid "login-email"
                 :placeholder "Email"
                 :value       (:email draft)
                 :disabled    submitting?
                 :on-change   #(dispatch [:auth.login-form/edit-field :email (.. % -target -value)])}]]
       [:fieldset.form-group
        [:input {:type        "password"
                 :name        "password"
                 :data-testid "login-password"
                 :placeholder "Password"
                 :value       (:password draft)
                 :disabled    submitting?
                 :on-change   #(dispatch [:auth.login-form/edit-password {:value (.. % -target -value)}])}]]
       [:button {:type "submit"
                 :data-testid "login-submit"
                 :disabled submitting?}
        (if submitting? "Signing in…" "Sign in")]]]]))

(reg-view ^{:doc "Register page."}
          register-page []
  (let [draft       @(subscribe [:auth.register-form/draft])
        submitting? @(subscribe [:auth/submitting?])
        err         @(subscribe [:auth/error])]
    [:div.auth-page
     [:h1 "Sign up"]
     [rf/route-link {:to :realworld.auth/login} "Have an account?"]
     (when err [:ul.error-messages [:li err]])
     [:form
      {:on-submit (fn [e]
                    (.preventDefault e)
                    (dispatch [:auth.register-form/submit]))}
      [:fieldset
       [:fieldset.form-group
        [:input {:type        "text"
                 :name        "username"
                 :placeholder "Username"
                 :value       (:username draft)
                 :disabled    submitting?
                 :on-change   #(dispatch [:auth.register-form/edit-field :username (.. % -target -value)])}]]
       [:fieldset.form-group
        [:input {:type        "email"
                 :name        "email"
                 :placeholder "Email"
                 :value       (:email draft)
                 :disabled    submitting?
                 :on-change   #(dispatch [:auth.register-form/edit-field :email (.. % -target -value)])}]]
       [:fieldset.form-group
        [:input {:type        "password"
                 :name        "password"
                 :placeholder "Password"
                 :value       (:password draft)
                 :disabled    submitting?
                 :on-change   #(dispatch [:auth.register-form/edit-password {:value (.. % -target -value)}])}]]
       [:button {:type "submit" :disabled submitting?}
        (if submitting? "Signing up…" "Sign up")]]]]))

