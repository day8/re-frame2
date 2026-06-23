# Add authentication

Auth feels like a big feature, but it's really four small ones: a login form, requests that carry the user's token, routes only signed-in users can reach, and a logout that doesn't leak one user's data into the next session. Here's the good news — every one of those pieces is something you already know. Each is an *event* (a record of "something happened" that you dispatch), an *interceptor* (a wrapper that runs code before and after handlers), or an *effect* (a description of a side effect the framework carries out for you). There is no auth machinery to learn; there's just auth-shaped uses of the parts you have.

We'll build it up one step at a time. First a tiny token slice in `app-db`, then login, then the request decorator, then the route guard, then the bounce-back, then logout. Each step is a few lines and stands on its own. The full recipe is wired end to end, with running views, in [Part 3 of the tutorial](../tutorial/03-auth-and-forms.md) — this page is the reference shape behind it.

> **Auth is a slice, a guard, and a teardown — not a library.**

You'll need [routing](../concepts/routing.md) (`day8/re-frame2-routing`) and [managed HTTP](../concepts/http.md) (`day8/re-frame2-http`). Step 6 also touches [resources](../concepts/server-state.md) if you cache server state, and step 2 builds on [Build a form](build-a-form.md).

## 1. The token slice

Start with the smallest possible thing: where does session state live? Two `app-db` paths, and that's the whole slice.

- `[:auth :user]` — the signed-in user, or `nil` when nobody's logged in.
- `[:auth :token]` — the credential that requests carry.

The guard checks `:user`, the request decorator reads `:token`, and logout clears both. (`app-db` is your application's single state map, scoped per frame — a *frame* being an isolated runtime context with its own `app-db`.) Everything else in this page reads from or writes to these two paths.

### Persist the token through one seam

A page reload throws away `app-db`, so to stay logged in across refreshes the token needs to live somewhere durable — `localStorage`. The temptation is to sprinkle `localStorage` calls through login and logout and your tests. Don't. Give persistence exactly **one seam**: a single effect that writes on a truthy token and removes on `nil`. Login, logout, and tests all hit the same edge.

```clojure
;; Adapted from examples/reagent/realworld/auth.cljs
;; Requires: [re-frame.core :as rf] [re-frame.http.managed] [re-frame.routing :as routing]
;; — requiring each artefact namespace registers its surface at load.
(rf/reg-fx :auth.session/persist
  {:doc       "Persist (truthy :token) or clear (nil) the session token in localStorage."
   :platforms #{:client}}
  (fn [_frame-ctx {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (if token
        (.setItem    ls "auth-token" token)
        (.removeItem ls "auth-token")))))
```

One effect, two behaviours, called from exactly one place each. That `:platforms #{:client}` matters — see the first callout.

> **Gotcha — this effect is client-only.** Under SSR there's no `localStorage`, so a session rides an http-only cookie instead. The effect is declared `:platforms #{:client}` for exactly this reason — on the server it simply isn't registered, and any `:auth.session/persist` row is a no-op rather than a crash.

> **Why this matters — a localStorage token is readable by any script on your page.** If XSS is in your threat model, use the http-only cookie and drop this effect — the rest of the recipe stands unchanged. The token slice, the guard, and the teardown don't care *how* the credential was persisted; they only read it back from `app-db`.

### Read the saved token back at boot

Persisting is half the seam; reading the value *back* when the app reboots is the other half. Without a boot read, every refresh logs the user out.

But `localStorage` is the outside world, and a handler must stay a pure function. So the read is a [coeffect](../concepts/effects-and-coeffects.md) — an input the framework gathers and hands you. Register a `reg-cofx` supplier, and any handler that wants the saved token declares `:rf.cofx/requires`; the runtime assembles the value *before* the handler runs.

```clojure
;; Adapted from examples/reagent/realworld/auth.cljs
(rf/reg-cofx :auth.session/token
  {:doc "The saved JWT (or nil), read from localStorage."}
  (fn []
    (some-> (.-localStorage js/globalThis) (.getItem "auth-token"))))
```

The init event in step 4 will declare this coeffect and fold the saved token into the slice. That's all "stay logged in across reloads" takes — the supplier reads the world, the handler asks for it by name.

> **Going deeper — ambient vs. recordable coeffects.** This token read registers as an *ambient* coeffect — re-read live, never written into the epoch tape. That's safe precisely because it runs *once at boot*, before any epoch you'd replay. A fact you fold into durable state mid-session would instead want `:recordable? true`, so replay re-presents the recorded value rather than re-reading the world. Tests stub the boot read by re-registering the supplier (see [Part 3 of the tutorial](../tutorial/03-auth-and-forms.md)).

### Keep the secret out of traces

The token is a credential, so classify its path as `:sensitive`. You do this by returning a classification effect from the init event (step 4 wires it up). Classifying the *path* means the raw token never leaves the box in traces, Xray captures, or SSR payloads — while your handlers keep seeing the real value ([Keep secrets and large things out of traces](keep-secrets-out-of-traces.md)).

That's the complete slice: two paths, one persistence seam, one boot-read coeffect, and one classification. Everything below builds on it.

## 2. Wire the login form

The login form is just [Build a form](build-a-form.md) — same slice shape, same seven events, same error-visibility rule. That recipe's running example *is* this login form, at `[:auth :login]`. So we don't rebuild the form; we change exactly one thing: **success establishes a session.**

Upgrade the form's `:form.login/submit-success` to an fx handler that stores the user and token, persists, and bounces the user onward. (A *handler* is the function that runs when an event is dispatched.)

```clojure
(rf/reg-event :form.login/submit-success
  (fn [{:keys [db]} [_ {:keys [value]}]]
    (let [user (:user value)]            ;; server reply: {:user {... :token "..."}}
      {:db (-> db
               (assoc-in [:auth :login :status]    :submitted)
               (assoc-in [:auth :login :submitted] ;; keep the form recipe's dirty-check snapshot
                         (get-in db [:auth :login :draft]))
               ;; The token has ONE durable home — the classified [:auth :token]
               ;; path. Store the user with :token stripped, so the JWT is not
               ;; also sitting (unclassified) at [:auth :user :token]; that copy
               ;; would ship raw to every off-box record. The request decorator
               ;; reads the token from [:auth :token], never from the user map.
               (assoc-in [:auth :user]  (dissoc user :token))
               (assoc-in [:auth :token] (:token user)))
       :fx [[:auth.session/persist {:token (:token user)}]
            [:dispatch [:auth/post-login-redirect]]]})))
```

The failure handler is unchanged from the form recipe. Notice login deliberately does **not** retry — one submission per click. A transient 5xx surfaces as an error and the user clicks again, which is the behaviour you want; silently re-firing a credential submission is a good way to lock an account or double-charge a flow.

Register is the same wiring with a different URL and draft — same shape, only the endpoint and the initial draft change.

> **Gotcha — keep the credential out of the trace too.** The slice protects the token *after* commit, but the submitted password rides the *transient event payload* on its way in. If `:form.login/submit` carries the password in its event vector, classify that argument on the registration so it never lands in a trace row: `(rf/reg-event :form.login/submit {:sensitive [[1 :password]]} …)` — registration-metadata classification redacts the payload at trace egress while the handler body still sees the real value ([Spec 015 — Data classification](../../../spec/015-Data-Classification.md)).

> **Going deeper — when to reach for a machine.** Once login, register, and session restore start coordinating ("can't submit while restoring"), graduate to a five-state [machine](../concepts/machines.md) — `idle → submitting/restoring → authed | error` — as the RealWorld example does ([auth.cljs](../../../examples/reagent/realworld/)). The tell is when an `if` over a `:status` keyword grows into a nest of "but only if not also…" conditions; that's a state machine wearing a trench coat.

## 3. Decorate requests once, at the frame seam

Now the user has a token. Every authenticated request needs to carry it in an `Authorization` header. The naive approach threads the token through every request builder — and one forgotten call site means one unauthenticated request.

Instead, write it **once**. An HTTP interceptor's `:before` reads the token from the frame's `app-db` and stamps every outbound managed request crossing that frame:

```clojure
;; Adapted from examples/reagent/realworld/core.cljs
(defn- bearer-auth [ctx]
  (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
    (cond-> ctx
      token (assoc-in [:request :headers "Authorization"]
                      (str "Token " token)))))  ;; "Token" is RealWorld's scheme; yours may be "Bearer"

;; Register at app boot, before the first authenticated request can fire.
(rf/reg-http-interceptor :my-app/bearer-auth
  {:before bearer-auth})
```

This is the production shape, and three small choices make it so. It reads `(:frame ctx)` — the frame the cascade actually runs under, never a hard-coded id — so it survives renamed and multi-frame mounts. It returns the ctx unchanged when there's no token, which is why login and public reads are untouched. And `Authorization` sits on the framework's built-in redaction denylist ([Spec 014 — privacy](../../../spec/014-HTTPRequests.md)), so the live request carries it while off-box traces never do.

That's all step 3 needs. The same seam, though, has a response side — and it's where you catch an expired token:

```clojure
;; A response-side hook on the SAME seam: catch a 401 and force logout.
(rf/reg-http-interceptor :my-app/expired-session
  {:after (fn [ctx response]
            (when (= 401 (:status response))
              (rf/dispatch [:auth/logout]))     ;; token went stale server-side
            response)})                          ;; :after MUST return the response
```

An interceptor map carries **`:before`**, **`:after`**, or both — at least one is required, or registration is rejected fail-loud with `:rf.error/http-bad-interceptor`.

> **Going deeper — how the chain composes.** `:before` runs in **registration order**; `:after` runs in the **reverse** of registration order — the same onion shape as event interceptors, so the outermost registration wraps the innermost on both request and response sides. An interceptor with only `:before` is transparent on the way back (and vice versa), so they compose cleanly. If a `:before` or `:after` *throws*, the runtime classifies it `:rf.error/http-interceptor-failed` (carrying `:frame`, `:interceptor-id`, `:url`, and `:phase`) and fails the request rather than silently dropping the decoration — there's no recovery cofx in the chain, so wrap recoverable logic inside the interceptor itself.

> **Gotcha — hot-reloading the interceptor.** Re-evaluating `reg-http-interceptor` with the same id replaces the slot **in place** — its position in the chain is preserved, which is exactly what you want on a file save. `clear-http-interceptor` removes the slot entirely; a later re-registration then **appends to the end** of the chain. So don't clear-then-reg in hot-reload paths unless you actually want a fresh end-of-chain slot.

> **From re-frame v1.** This decorator used to be a wrapper fn around every `http-xhrio` map — and one forgotten call site meant one unauthenticated request. Here the frame seam is the single write site, so there's no call site to forget. It's the same shift you make moving from passing an `axios` config object everywhere to registering one request interceptor: the decoration becomes structural, not something each caller has to remember.

## 4. Guard the protected routes

Authenticated requests work; now some *routes* should only open for signed-in users. Route-level auth is an ordinary interceptor over the navigation events — not a special routing mechanism ([Spec 012 — redirects and guards](../../../spec/012-Routing.md)).

Start by tagging the routes that need a session:

```clojure
(rf/reg-route :app/login    {} "/login")
(rf/reg-route :app/settings {:tags #{:requires-auth}} "/settings")
```

Then the guard. It has one job it must get exactly right: **gate every navigation entry point.** Guarding `:rf.route/navigate` alone fails *open* on the most common path — a logged-out user who types `/settings` into the URL bar, reloads a protected page, or clicks a link gets in anyway. There are three distinct navigation events:

- `:rf.route/navigate` — a programmatic push.
- `:rf/url-requested` — a `route-link` click.
- `:rf.route/handle-url-change` — the URL bar, reload, and Back/Forward.

The fix is to normalise all three to one target, then redirect identically:

```clojure
;; Adapted from examples/reagent/realworld/routing.cljs
(defn- nav-target
  "Normalise a navigation event to {:id <route-id> :params <map>}; nil for
   non-navigation events (the guard short-circuits)."
  [[ev-id a b]]
  (case ev-id
    :rf.route/navigate          {:id a :params (or b {})}            ;; programmatic nav
    :rf/url-requested           (let [{:keys [to params url]} a]     ;; route-link click
                                  (cond
                                    to  {:id to :params (or params {})}
                                    url (when-let [{:keys [route-id params]} (routing/match-url url)]
                                          {:id route-id :params (or params {})})))
    :rf.route/handle-url-change (when-let [{:keys [route-id params]} (routing/match-url a)]
                                  {:id route-id :params (or params {})})  ;; URL bar / reload / back-forward
    nil))

(rf/reg-interceptor :my-app/auth-guard
  {:doc "Redirect logged-out users away from :requires-auth routes; stash the target."}
  {:before
   (fn [ctx]
     (if-let [{:keys [id params]} (nav-target (get-in ctx [:coeffects :event]))]
       (let [needs-auth? (contains? (:tags (rf/handler-meta :route id)) :requires-auth)
             logged-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
         (if (and needs-auth? (not logged-in?))
           (-> ctx
               (assoc :rf/skip-handler? true)        ;; the protected route never commits
               (assoc-in [:effects :db]              ;; stash the target for the bounce-back
                         (assoc-in (get-in ctx [:coeffects :db])
                                   [:auth :return-to] {:id id :params params}))
               (assoc-in [:effects :fx]
                         [[:dispatch [:rf.route/navigate :app/login]]]))
           ctx))
       ctx))})
```

Two helpers do the reading. `routing/match-url` is the URL codec — it parses a URL string into `{:route-id :params :query :fragment :validation-failed?}` (or `nil` when nothing matches), and lives in `re-frame.routing`, **not** on the `rf/` facade. (`routing/route-url` is its inverse.) Both are pure, so the guard can call `match-url` without touching app-db. `rf/handler-meta` is the read for registration metadata: `(rf/handler-meta :route id)` returns the route's registration map, whose `:tags` you check against `:requires-auth`.

The redirect works by *skip-and-dispatch*. `:rf/skip-handler?` — the public short-circuit primitive an interceptor's `:before` sets on its ctx — stops the original handler, so the protected slice never commits and its `:on-match` loads never fire. The guard then dispatches the login navigation itself.

> **Gotcha — don't rewrite the event in place.** The runtime picks the handler from the *original* event id, so editing the event would run the wrong handler. Use skip-and-dispatch instead. And stash the target in `app-db`, not on the navigate opts — the navigate handler drops unknown opts, so a target smuggled onto the options map would simply vanish.

> **Gotcha — a bad URL is a non-match, not a crash.** If `match-url` can't resolve a path — an unknown route, or path params that fail the route's `:params` schema (`:validation-failed? true`) — it returns `nil`/a non-match, and `nav-target` short-circuits the guard for that event. The runtime's own URL-change handler routes the same non-match to `:rf.route/not-found`. So a logged-out user pasting a garbage protected-looking URL lands on not-found, never inside a guarded slice.

### Wire the guard frame-wide

Now attach the guard — by reference. It's registered once under `:my-app/auth-guard`; the frame's `:interceptors` chain names that id, never the interceptor value. It short-circuits in a single `case` lookup for every non-navigation event, so the cost on ordinary traffic is negligible.

The same frame's `:initial-events` runs the init event from step 1 — it restores the saved session and classifies the token path, with the egress protection in place before any off-box egress:

```clojure
;; Adapted from examples/reagent/realworld/core.cljs
;; The init event reads the saved token (step 1's cofx), seeds the slice, and
;; classifies the durable token path :sensitive via the commit-plane
;; classification effect (EP-0025) — returned alongside :db. Frames always start
;; with app-db = {}, so the slice is built by this event, not a :db config key.
(rf/reg-event :auth/init
  {:rf.cofx/requires [:auth.session/token]}        ;; ask for the saved JWT by name
  (fn [{:keys [db auth.session/token]} _]
    {:db        (assoc db :auth {:user nil :token token})
     :sensitive [[:auth :token]]}))                ;; step 1's egress protection

(rf/reg-frame :rf/default
  {:doc            "The app frame."
   :url-bound?     true                            ;; this frame owns the browser URL
   :initial-events [[:auth/init]]                  ;; restore session + classify [:auth :token] at creation
   :interceptors   [:my-app/auth-guard]})          ;; reference the registered guard by id
```

> **From re-frame v1 (and a frame gotcha) — `reg-frame` is a create-and-register, atomically.** There's no `:db` config key — a frame always starts with `app-db = {}`, and you build the initial state through `:initial-events` (the same dispatch pipeline that handles every later change). If you need to seed raw state ahead of the auth read, make `[:rf/set-db {…}]` the first step and `[:auth/init]` the second; the steps dispatch synchronously, in order, at creation. Editing `:initial-events` after the fact doesn't re-run them on a hot save — call `reset-frame!` to replay the setup. (Per [EP-0027](../../../spec/002-Frames.md).)

## 5. Bounce back after login

A guard's headline feature is returning the user to exactly where they were headed. Step 4's guard stashed that target at `[:auth :return-to]`; step 2's success handler already dispatches `:auth/post-login-redirect`. Here's the handler it calls — it reads **and clears** the stash in one step:

```clojure
;; Adapted from examples/reagent/realworld/auth.cljs
(rf/reg-event :auth/post-login-redirect
  (fn [{:keys [db]} _]
    (let [return-to (get-in db [:auth :return-to])]
      {:db (update db :auth dissoc :return-to)
       :fx [[:dispatch (if return-to
                         [:rf.route/navigate (:id return-to) (:params return-to)]
                         [:rf.route/navigate :app/home])]]})))
```

> **Why this matters — read-and-clear, not just read.** A stash that lingers is a footgun: the user logs in directly from `/login` an hour later, and a `:return-to` left over from this morning silently teleports them somewhere they never asked to go. Consuming the stash in the same handler that reads it means the bounce target lives exactly as long as one login round-trip.

## 6. Logout is a teardown

Logout has to clear three things: the session slice, the persisted token, *and* the departing user's cached server reads. Skip that last one and the next account sees the previous account's feed — the kind of bug you really don't want in an auth flow.

The first two are easy — they're just the slice and the persistence seam you already built. The third, clearing a user's cached server reads, is one causal event when you use [resources](../concepts/server-state.md) (a *resource* being a managed, cached read of server state): `:rf.resource/clear-scope`.

To clear *a user's* cached reads you need a way to name "this user's scope." That's a **named resource-scope resolver**: a pure function, registered once, that derives a canonical scope value from `app-db`. The same resolver is used by your resources, your route loads, and logout — one scope currency, no per-call-site seams:

```clojure
;; Register once at boot. The resolver is PURE — it derives a scope from db,
;; it does not fetch, dispatch, or read ambient state.
(rf/reg-resource-scope :my-app/session
  {:inputs {:username [:db [:auth :user :username]]}
   :resolve
   (fn [{:keys [username]} _ctx]
     (when username
       [:rf.scope/session {:username username}]))})   ;; nil when logged out — fail-closed
```

Now logout itself. There's one subtlety, and the ordering matters: resolve the *old* scope from the coeffect `db` **before** you clear the auth slice. After the clear, the identity the scope derives from is gone.

```clojure
;; Scope resolution per Spec 016; :my-app/session is the resolver registered above.
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    (let [old-scope (rf/resolve-resource-scope db :my-app/session)]   ;; pure helper, resolved against cofx db
      {:db (-> db
               (assoc-in [:auth :user]  nil)
               (assoc-in [:auth :token] nil))
       :fx [[:auth.session/persist {:token nil}]
            [:dispatch [:rf.resource/clear-scope {:scope old-scope :cause :logout}]]
            [:dispatch [:rf.route/navigate :app/home]]]})))
```

`clear-scope` removes that scope's cache entries, releases their owners, aborts in-flight requests nothing else owns, suppresses late replies (by scope-plus-generation checks), and emits a trace row explaining what was removed, aborted, and left alone. Every other scope stays intact, and there's no hand-maintained list of keys to forget ([Spec 016 — Resources](../../../spec/016-Resources.md)). If you don't use resources, drop that one `:fx` row and the rest stands.

> **Going deeper — `resolve-resource-scope` is pure, and that's load-bearing.** It reads the resolver registry against a `db` value with no dispatch, no app-state mutation, and no trace. That's why you can call it *inline* in the handler to capture `old-scope` before the clear. Note the `:cause :logout` on the payload: `clear-scope` records it in resource history so Xray can attribute the eviction. And there's deliberately **no `:snapshot-db` key** — a whole-db snapshot riding an event vector would be an egress-bearing record on traces, which EP-0015 forbids.

> **Gotcha — a nil scope is loud, not silent.** If the resolver returns `nil` at a `clear-scope` site — say the user was already gone — the runtime emits a loud diagnostic (`:rf.warning/resource-clear-scope-unresolved`) rather than a silent no-op. So a logout that quietly fails to clear a session's cache shows up in the trace instead of leaking the previous user's feed.

> **Coming from TanStack Query?** This is `queryClient.clear()` with a scalpel instead of a sledgehammer. Rather than nuking the entire cache on logout, `clear-scope` evicts exactly the entries owned by the departing session's scope — derived from the *old* identity you captured before the clear — and leaves everything else (app-level config, public reads, a second logged-in frame) untouched.

## Observe it in Xray

With all six steps wired, you can watch the whole flow in [Xray](debug-with-xray.md):

- Logged out, click a link to a guarded route: the navigation event's row shows the guard skipping the handler and a follow-on dispatch to login — the protected route never reaches the route slice.
- Logged in, find an authenticated request: the live request carried `Authorization`, the captured trace shows it redacted — as is `[:auth :token]` in the app-db view.
- Reload the page while signed in: the `:auth/init` row shows the saved token folded in from the coeffect, and the slice comes back classified — no re-login.
- Dispatch `:auth/logout`: one clear-scope row lists what was removed, what was aborted, and what was left alone.
