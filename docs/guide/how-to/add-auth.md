# Add authentication

Auth feels like a big feature, but it's really four small ones: a login form, requests that carry the user's token, routes only signed-in users can reach, and a logout that doesn't leak one user's data into the next session. Here's the good news — every one of those pieces is something you already know. They're all built from the three parts you've already met:

- an **event** — a record of "something happened" (a click, a server reply) that you *dispatch* into the framework;
- an **interceptor** — a wrapper that can run code before and after an event's handler, like middleware around a request;
- an **effect** — a *description* of a side effect that you hand back, which the framework then carries out for you.

There is no auth machinery to learn; there's just auth-shaped uses of the parts you have.

We'll build it up one step at a time. First a tiny token slice in `app-db`, then login, then the request decorator, then the route guard, then the bounce-back, then logout. Each step is a few lines and stands on its own. The full recipe is wired end to end, with running views, in [Part 3 of the tutorial](../tutorial/03-auth-and-forms.md) — this page is the reference shape behind it.

> **Auth is a slice, a guard, and a teardown — not a library.**

Two add-on libraries do the heavy lifting: [routing](../concepts/routing.md) (the `day8/re-frame2-routing` dependency) and [managed HTTP](../concepts/http.md) (`day8/re-frame2-http`). The last step also reaches for [resources](../concepts/server-state.md) — re-frame2's cached server reads — if you keep server state around, and the login step builds directly on [Build a form](build-a-form.md).

## 1. The token slice

Start with the smallest possible thing: where does session state live? Two `app-db` paths, and that's the whole slice.

- `[:auth :user]` — the signed-in user, or `nil` when nobody's logged in.
- `[:auth :token]` — the credential that requests carry.

The guard checks `:user`, the request decorator reads `:token`, and logout clears both. Everything else on this page reads from or writes to these two paths.

These two paths live in `app-db` — your app's single state map. To be precise, each [frame](../concepts/frames.md) has its own `app-db`: a frame is one isolated, running instance of your app (think one mounted app instance), and most apps run a single one. That per-frame isolation is what lets a second logged-in tab coexist with this one without their tokens crossing wires — a property that comes up again in the request decorator and the route guard below.

### Persist the token through one seam

A page reload throws away `app-db`, so to stay logged in across refreshes the token needs to live somewhere durable — `localStorage`. The temptation is to sprinkle `localStorage` calls through login and logout and your tests. Don't. Give persistence exactly **one seam**: a single effect that writes on a truthy token and removes on `nil`. Login, logout, and tests all hit the same edge.

```clojure
;; Adapted from examples/reagent/realworld/auth.cljs
;; Requires: [re-frame.core :as rf] [re-frame.http.managed] [re-frame.routing :as routing]
;; — pulling in each namespace registers its events/effects as a load-time side effect.
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

But there's a rule in the way: an event handler in re-frame2 must be a *pure function* — same inputs, same outputs, no reaching out to the world — because that's what lets the framework replay, test, and trace it. Reading `localStorage` *is* reaching out to the world, so a handler can't do it directly.

The escape hatch is a [coeffect](../concepts/effects-and-coeffects.md): a declared input the framework hands into a handler beside `db`. Any handler that wants the saved token declares `:rf.cofx/requires`; the runtime supplies the value *before* the handler runs, so the handler itself stays pure.

The saved token gets folded into durable `[:auth :token]`, so it can't enter as an *ambient* read (re-run live on replay) — replay or epoch-restore would re-read whatever `localStorage` holds *then*, not the token recorded with the boot. A fact that feeds durable state must arrive as **recorded data**. So register `:auth.session/token` as a **recordable, provided** coeffect: it carries no supplier function — its value is stamped onto the boot dispatch by the host boundary (the same shape as the framework's built-in `:rf/time-ms` clock), recorded once, and re-presented verbatim under replay.

```clojure
;; Adapted from examples/reagent/realworld/auth.cljs
;; Recordable + provided: no generator — the value is stamped onto the boot
;; dispatch by the host (see "Read the host once at the boundary" below).
(rf/reg-cofx :auth.session/token
  {:recordable? true
   :provided?   true
   :doc "The saved JWT (or nil); stamped onto the boot dispatch from localStorage."})
```

The init event in step 4 declares this coeffect and folds the saved token into the slice. That's all "stay logged in across reloads" takes — the host reads the world once, the value rides the boot dispatch, and the handler asks for it by name.

### Read the host once at the boundary

A provided coeffect needs an owner to stamp its value. For session restore that owner is your boot code: read `localStorage` *once*, at the host boundary, and hand the value to the init dispatch on the `:rf.cofx` envelope. The handler never touches `localStorage`; it reads the recorded fact flat.

```clojure
;; Adapted from examples/reagent/realworld/core.cljs — the host read happens
;; ONCE here, at the boundary; its value rides the boot dispatch as a recordable
;; coeffect, so replay / epoch-restore re-presents the captured token verbatim.
(defn read-jwt-from-storage []
  (some-> (.-localStorage js/globalThis) (.getItem "auth-token")))

(rf/with-frame :rf/default
  (rf/dispatch-sync [:auth/init]
                    {:rf.cofx {:auth.session/token (read-jwt-from-storage)}}))
```

> **Going deeper — why recordable, not ambient.** Coeffects come in two grades, and the choice is decided by whether a *durable* write depends on the value — that's the [effects and coeffects](../concepts/effects-and-coeffects.md#two-grades-ambient-and-recordable) home's full treatment. The session token folds into durable `[:auth :token]`, so it must be **recordable** (recorded with the event, re-presented on replay) — not ambient (re-read live), which would let replay land a different token. Because the value comes from the host rather than a registered function, it's the **provided** flavour of recordable: no supplier, stamped at the boundary. Tests and replay supply it the same way — as data on the dispatch (`{:rf.cofx {:auth.session/token "…"}}`), never by re-registering a supplier (see [Part 3 of the tutorial](../tutorial/03-auth-and-forms.md)).

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

Instead, write it **once**, as an HTTP interceptor. An interceptor's `:before` is a function that receives a *context* map — call it `ctx` — holding the in-flight request, edits it, and returns it. Ours reads the token from the frame's `app-db` and stamps the `Authorization` header onto every outbound managed request crossing that frame:

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

This is the production shape, and three small choices make it so. It reads `(:frame ctx)` — the frame this request is actually running under, never a hard-coded id — so it survives renamed frames and multi-frame mounts. It returns `ctx` unchanged when there's no token, which is why login and public reads are untouched. And `Authorization` sits on the framework's built-in redaction denylist ([Spec 014 — privacy](../../../spec/014-HTTPRequests.md)), so the live request carries it while off-box traces never do.

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

> **Going deeper — how the chain composes.** HTTP interceptors compose with the same onion shape as event [interceptors](../concepts/interceptors.md#the-sandwich-how-a-chain-runs): `:before` runs in registration order, `:after` in reverse, so the outermost registration wraps the innermost on both request and response sides, and a `:before`-only (or `:after`-only) interceptor is transparent on the other leg. The auth-specific wrinkle is failure: if a `:before` or `:after` *throws*, the runtime classifies it `:rf.error/http-interceptor-failed` (carrying `:frame`, `:interceptor-id`, `:url`, and `:phase`) and fails the request rather than silently dropping the decoration — there's no recovery cofx in the chain, so wrap recoverable logic inside the interceptor itself.

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
```

Now the guard itself. An *event* interceptor's `ctx` is split into two halves: `:coeffects` holds the inputs (the read-only `:db` and the `:event` being handled), and `:effects` holds the outputs being assembled (the new `:db`, queued `:fx`). The guard reads the event out of `:coeffects`, and when it decides to bounce, it writes the redirect into `:effects`:

```clojure
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

Two helpers do the reading, and both are *pure* — they compute from their arguments without touching app-db, which is exactly why the guard can call them inline.

The first, `routing/match-url`, parses a URL string into a map — `{:route-id :params :query :fragment :validation-failed?}` — or `nil` when nothing matches. (Its inverse, `routing/route-url`, builds a URL from a route id and params.) Note the namespace: it lives in `re-frame.routing`, **not** on the `rf/` facade.

The second, `rf/handler-meta`, reads back the metadata you stamped at registration. `(rf/handler-meta :route id)` returns that route's registration map; the guard pulls its `:tags` and checks for `:requires-auth`.

The redirect works by *skip-and-dispatch*. `:rf/skip-handler?` — the public short-circuit primitive an interceptor's `:before` sets on its ctx — stops the original handler, so the protected slice never commits and its `:on-match` loads never fire. The guard then dispatches the login navigation itself.

> **Gotcha — don't rewrite the event in place.** The runtime picks the handler from the *original* event id, so editing the event would run the wrong handler. Use skip-and-dispatch instead. And stash the target in `app-db`, not on the navigate opts — the navigate handler drops unknown opts, so a target smuggled onto the options map would simply vanish.

> **Gotcha — a bad URL is a non-match, not a crash.** If `match-url` can't resolve a path — an unknown route, or path params that fail the route's `:params` schema (`:validation-failed? true`) — it returns `nil`/a non-match, and `nav-target` short-circuits the guard for that event. The runtime's own URL-change handler routes the same non-match to `:rf.route/not-found`. So a logged-out user pasting a garbage protected-looking URL lands on not-found, never inside a guarded slice.

### Wire the guard frame-wide

Now attach the guard — by reference. It's registered once under `:my-app/auth-guard`; the frame's `:interceptors` chain names that id, never the interceptor value. It short-circuits in a single `case` lookup for every non-navigation event, so the cost on ordinary traffic is negligible.

The init event from step 1 restores the saved session and classifies the token path, with the egress protection in place before any off-box egress. Because its `:auth.session/token` coeffect is *provided* — stamped at the boundary, not computed by a registered supplier — the session restore is dispatched directly at boot (step 1's boundary dispatch), **not** from the frame's `:initial-events`. A `:dispatch` fan-out doesn't forward `:rf.cofx`, so `:initial-events` couldn't supply the host-read token; the boundary dispatch is the one place that owns it:

```clojure
;; Adapted from examples/reagent/realworld/auth.cljs
;; The init event reads the saved token (step 1's provided cofx), seeds the
;; slice, and classifies the durable token path :sensitive via the commit-plane
;; classification effect (EP-0025) — returned alongside :db. Frames always start
;; with app-db = {}, so the slice is built by this event, not a :db config key.
(rf/reg-event :auth/init
  {:rf.cofx/requires [:auth.session/token]}        ;; ask for the saved JWT by name
  (fn [{:keys [db auth.session/token]} _]
    {:db        (assoc db :auth {:user nil :token token})
     :sensitive [[:auth :token]]}))                ;; step 1's egress protection

(rf/reg-frame :rf/default
  {:doc          "The app frame."
   :url-bound?   true                              ;; this frame owns the browser URL
   :interceptors [:my-app/auth-guard]})            ;; reference the registered guard by id

;; Session restore runs at the boundary (step 1), where the host-read token can
;; ride the :rf.cofx envelope — :initial-events can't carry a provided coeffect.
(rf/with-frame :rf/default
  (rf/dispatch-sync [:auth/init]
                    {:rf.cofx {:auth.session/token (read-jwt-from-storage)}}))
```

> **From re-frame v1 (and a frame gotcha) — `reg-frame` is a create-and-register, atomically.** There's no `:db` config key — a frame always starts with `app-db = {}`, and you build the initial state through dispatched events (the same event cascade that handles every later change). Events that need nothing from the world can ride the frame's `:initial-events`; one that consumes a *provided* coeffect (like `:auth/init`'s host-read token) is dispatched at the boundary instead, where its `:rf.cofx` can be supplied. If you need to seed raw state ahead of the auth read, make `[:rf/set-db {…}]` the first step; events dispatch synchronously, in order. Editing `:initial-events` after the fact doesn't re-run them on a hot save — call `reset-frame!` to replay the setup. (Per [EP-0027](../../../spec/002-Frames.md).)

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
