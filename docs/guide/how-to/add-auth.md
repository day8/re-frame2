# Add authentication

Auth has a reputation. It sounds like a subsystem — sessions, tokens, redirects, the works — and frameworks happily sell you one. re-frame2 doesn't have an auth subsystem, and you won't miss it, because auth turns out to be four small features you can already build: a login form, requests that carry the user's token, routes only signed-in users can reach, and a logout that doesn't leak one user's data into the next session.

Every one of those is auth-*shaped* use of parts you already met:

- an [**event**](../glossary.md#event) — an inert vector recording that *something happened* (a click, a server reply), which you [dispatch](../glossary.md#dispatch) into the framework;
- an [**interceptor**](../glossary.md#interceptor) — a named `:before`/`:after` wrapper around a handler, the place cross-cutting concerns live;
- an [**effect**](../glossary.md#effect) — a *description* of a side effect you hand back as data, which the runtime then performs for you.

There is no auth machinery to learn. There's a token [slice](../glossary.md#app-db), a route guard, and a teardown — and they're all built from the loop you already know.

We'll grow it one step at a time: a tiny token slice in [app-db](../glossary.md#app-db), then login, then the request decorator, then the route guard, then the bounce-back, then logout. Each step is a few lines and stands on its own. The full recipe runs end to end, with live views, in [Part 3 of the tutorial](../tutorial/03-auth-and-forms.md) — this page is the reference shape behind it.

> **Auth is a slice, a guard, and a teardown — not a library.**

Two add-on artefacts do the heavy lifting: [routing](../concepts/routing.md) (the `day8/re-frame2-routing` dependency) and [managed HTTP](../concepts/http.md) (`day8/re-frame2-http`). The last step also reaches for [resources](../concepts/server-state.md) — re-frame2's cached server reads — if you keep server state around, and the login step builds directly on [Build a form](build-a-form.md).

## 1. The token slice

Start with the smallest possible question: where does session state live? Two app-db paths, and that's the whole slice.

- `[:auth :user]` — the signed-in user, or `nil` when nobody's logged in.
- `[:auth :token]` — the credential that requests carry.

The guard checks `:user`, the request decorator reads `:token`, and logout clears both. Everything else on this page reads from or writes to these two paths.

These paths live in [app-db](../glossary.md#app-db) — your app's single state map. To be precise, each [frame](../glossary.md#frame) has its own app-db: a frame is one isolated, running instance of your app (think one mounted app instance), and most apps run exactly one. That per-frame isolation is what lets a second logged-in tab coexist with this one without their tokens crossing wires — a property that pays off again in the request decorator and the route guard below.

### Persist the token through one seam

A page reload throws away app-db, so to stay logged in across refreshes the token has to live somewhere durable — `localStorage`. The tempting move is to sprinkle `localStorage` calls through login, logout, and your tests. Don't. Give persistence exactly **one seam**: a single [effect](../glossary.md#effect) that writes on a truthy token and removes on `nil`. Login, logout, and tests all hit the same edge.

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

One [effect handler](../glossary.md#effect-handler), two behaviours, called from exactly one place each. That `:platforms #{:client}` is doing real work — see the next callout.

> **Gotcha — this effect is client-only.** Under [SSR](../glossary.md#ssr) there is no `localStorage`, so a session rides an http-only cookie instead. The effect is declared `:platforms #{:client}` for exactly this reason — on the server it simply isn't registered, and an `:auth.session/persist` row is a no-op rather than a crash.

> **Why this matters — a localStorage token is readable by any script on your page.** If XSS is in your threat model, use the http-only cookie and drop this effect; the rest of the recipe stands unchanged. The slice, the guard, and the teardown never care *how* the credential was persisted — they only read it back from app-db.

### Read the saved token back at boot

Persisting is half the seam; reading the value *back* when the app reboots is the other half. Skip the boot read and every refresh silently logs the user out.

But there's a rule in the way. An [event handler](../glossary.md#event-handler) in re-frame2 must be a *pure function* — same inputs, same outputs, no reaching out to the world — because purity is what lets the framework replay, test, and trace it. Reading `localStorage` *is* reaching out to the world, so a handler can't do it directly.

The escape hatch is a [coeffect](../glossary.md#coeffect): a declared input the framework hands into a handler beside `:db`. A handler that wants the saved token declares it under `:rf.cofx/requires`; the runtime supplies the value *before* the handler runs, so the handler itself stays pure. The world flows in as coeffects, out as effects, and the handler in the middle stays a clean function.

Now the wrinkle that decides *which kind* of coeffect this is. Coeffects come in [two grades](../glossary.md#recordable-vs-ambient-coeffects). An *ambient* one is read live and re-read on every replay — fine for a display hint, fatal here. The saved token folds into durable `[:auth :token]`, and anything that feeds a durable write must arrive as **recorded data**: captured once, re-presented verbatim under replay. An ambient read would let an epoch-restore land *whatever `localStorage` holds now*, not the token recorded with the boot.

So register `:auth.session/token` as a **recordable, provided** coeffect. It carries no supplier function — its value is stamped onto the boot dispatch by the host boundary (the same shape as the framework's built-in `:rf/time-ms` clock), recorded once, and replayed faithfully:

```clojure
;; Adapted from examples/reagent/realworld/auth.cljs
;; Recordable + provided: no generator — the value is stamped onto the boot
;; dispatch by the host (see "Read the host once at the boundary" below).
(rf/reg-cofx :auth.session/token
  {:recordable? true
   :provided?   true
   :doc "The saved JWT (or nil); stamped onto the boot dispatch from localStorage."})
```

The init event in step 4 declares this coeffect and folds the saved token into the slice. That's the whole of "stay logged in across reloads": the host reads the world once, the value rides the boot dispatch, and the handler asks for it by name.

### Read the host once at the boundary

A provided coeffect needs an owner to stamp its value. For session restore that owner is your boot code: read `localStorage` *once*, at the host boundary, and hand the value to the init dispatch on the `:rf.cofx` envelope. The handler never touches `localStorage` — it reads the recorded fact flat.

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

> **Going deeper — why recordable, not ambient.** The choice is decided by one question: does a *durable* write depend on the value? If yes, it must be [**recordable**](../glossary.md#recordable-vs-ambient-coeffects) (recorded with the event, re-presented on replay), never ambient (re-read live) — full treatment in [Effects and coeffects](../concepts/effects-and-coeffects.md#two-grades-ambient-and-recordable). The session token folds into durable `[:auth :token]`, so recordable it is. And because the value comes from the *host* rather than a registered function, it's the **provided** flavour of recordable: no supplier, stamped at the boundary. Tests and replay feed it the same way every time — as data on the dispatch (`{:rf.cofx {:auth.session/token "…"}}`), never by re-registering a supplier ([Part 3 of the tutorial](../tutorial/03-auth-and-forms.md)).

### Keep the secret out of traces

The token is a credential, so classify its path `:sensitive`. You do this by returning a [classification](../glossary.md#data-classification) effect from the init event (step 4 wires it up). Classifying the *path* means the raw token never leaves the box — not in traces, not in [Xray](../glossary.md#xray) captures, not in SSR payloads — while your handlers keep seeing the real value ([Keep secrets and large things out of traces](keep-secrets-out-of-traces.md)).

That's the complete slice: two paths, one persistence seam, one boot-read coeffect, one classification. Everything below stands on it.

## 2. Wire the login form

The login form is just [Build a form](build-a-form.md) — same slice shape, same seven events, same error-visibility rule. That recipe's running example *is* this login form, at `[:auth :login]`. So we don't rebuild the form; we change exactly one thing: **success establishes a session.**

Upgrade the form's `:form.login/submit-success` to an fx handler that stores the user and token, persists, and bounces the user onward:

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

One token, one home. The classified `[:auth :token]` path holds it; the user map ships with `:token` stripped, so the JWT isn't *also* sitting unclassified at `[:auth :user :token]`, ready to leak to every off-box record. The decorator in step 3 always reads from `[:auth :token]`, never from the user.

The failure handler is unchanged from the form recipe. Notice login deliberately does **not** retry — one submission per click. A transient 5xx surfaces as an error and the user clicks again, which is the behaviour you want; silently re-firing a credential submission is a fine way to lock an account or double-charge a flow. Register is the same wiring with a different URL and draft.

> **Gotcha — keep the credential out of the trace on the way *in*, too.** The slice protects the token *after* commit, but the submitted password rides the *transient event payload* on its way in. If `:form.login/submit` carries the password in its event vector, classify that argument on the registration so it never lands in a trace row: `(rf/reg-event :form.login/submit {:sensitive [[1 :password]]} …)` — registration-metadata classification redacts the payload at trace egress while the handler body still sees the real value ([Spec 015 — Data classification](../../../spec/015-Data-Classification.md)).

> **Going deeper — when to reach for a machine.** Once login, register, and session restore start coordinating ("can't submit while restoring"), graduate to a five-state [machine](../glossary.md#machine) — `idle → submitting/restoring → authed | error` — as the RealWorld example does ([auth.cljs](../../../examples/reagent/realworld/)). The tell: when an `if` over a `:status` keyword grows into a nest of "but only if not also…" conditions. That's a state machine wearing a trench coat.

## 3. Decorate requests once, at the frame seam

Now the user has a token. Every authenticated request needs to carry it in an `Authorization` header. The naive approach threads the token through every request builder — and one forgotten call site is one unauthenticated request shipped to production.

Instead, write it **once**, as an HTTP interceptor. An HTTP interceptor's `:before` is a function that receives a *context* map — call it `ctx` — holding the in-flight request, edits it, and returns it. Ours reads the token from the frame's app-db and stamps the `Authorization` header onto every outbound managed request crossing that frame:

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

This is the production shape, and three small choices make it so:

- It reads `(:frame ctx)` — the frame this request is *actually* running under, never a hard-coded id — so it survives renamed frames and multi-frame mounts. ([Frame identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found) reaches all the way out here.)
- It returns `ctx` unchanged when there's no token, which is why login and public reads stay untouched.
- `Authorization` sits on the framework's built-in redaction denylist ([Spec 014 — privacy](../../../spec/014-HTTPRequests.md)), so the live request carries it while off-box traces never do.

That's all step 3 needs. But the same seam has a *response* side, and it's where you catch an expired token:

```clojure
;; A response-side hook on the SAME seam: catch a 401 and force logout.
(rf/reg-http-interceptor :my-app/expired-session
  {:after (fn [ctx response]
            (when (= 401 (:status response))
              (rf/dispatch [:auth/logout]))     ;; token went stale server-side
            response)})                          ;; :after MUST return the response
```

An interceptor map carries **`:before`**, **`:after`**, or both — supply at least one, or registration is rejected fail-loud with `:rf.error/http-bad-interceptor`. (A no-op interceptor is almost always a typo, so the framework refuses to register it rather than letting it sit there doing nothing.)

> **Going deeper — how the chain composes.** HTTP interceptors compose with the same onion shape as event [interceptors](../concepts/interceptors.md#the-sandwich-how-a-chain-runs): `:before` runs in registration order, `:after` in reverse, so the outermost registration wraps the innermost on both legs, and a `:before`-only (or `:after`-only) interceptor is transparent on the other leg. The auth-specific wrinkle is failure. If a `:before` or `:after` *throws*, the runtime classifies it `:rf.error/http-interceptor-failed` (carrying `:frame`, `:interceptor-id`, `:url`, and `:phase`) and fails the request rather than silently dropping the decoration — there's no recovery cofx in the chain, so wrap any recoverable logic inside the interceptor itself.

> **Gotcha — hot-reloading the interceptor.** Re-evaluating `reg-http-interceptor` with the same id replaces the slot **in place** — its position in the chain is preserved, which is exactly what you want on a file save. `clear-http-interceptor` removes the slot entirely; a later re-registration then **appends to the end** of the chain. So don't clear-then-reg in hot-reload paths unless you genuinely want a fresh end-of-chain slot.

> **From re-frame v1.** This decorator used to be a wrapper fn around every `http-xhrio` map — and one forgotten call site meant one unauthenticated request. Here the frame seam is the single write site, so there's no call site to forget. It's the same shift you make moving from passing an `axios` config object around everywhere to registering one request interceptor: the decoration becomes structural, not something each caller has to remember.

## 4. Guard the protected routes

Authenticated requests work; now some *routes* should open only for signed-in users. Route-level auth is an ordinary interceptor over the navigation events — not a special routing mechanism ([Spec 012 — redirects and guards](../../../spec/012-Routing.md)).

Start by tagging the routes that need a session:

```clojure
(rf/reg-route :app/login    {} "/login")
(rf/reg-route :app/settings {:tags #{:requires-auth}} "/settings")
```

Then the guard. It has one job it must get exactly right: **gate every navigation entry point.** Guarding `:rf.route/navigate` alone fails *open* on the most common path — a logged-out user who types `/settings` into the URL bar, reloads a protected page, or clicks a link gets straight in. There are three distinct navigation events:

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

Now the guard itself. An *event* interceptor's `ctx` splits into two halves: `:coeffects` holds the inputs (the read-only `:db` and the `:event` being handled), and `:effects` holds the outputs being assembled (the new `:db`, the queued `:fx`). The guard reads the event out of `:coeffects`, and when it decides to bounce, it writes the redirect into `:effects`:

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

Two helpers do the reading, and both are *pure* — they compute from their arguments without touching app-db, which is exactly why the guard can call them inline:

- **`routing/match-url`** parses a URL string into a map — `{:route-id :params :query :fragment :validation-failed?}` — or `nil` when nothing matches. (Its inverse, `routing/route-url`, builds a URL from a route id and params.) Note the namespace: it lives in `re-frame.routing`, **not** on the `rf/` facade.
- **`rf/handler-meta`** reads back the metadata you stamped at registration. `(rf/handler-meta :route id)` returns that route's registration map; the guard pulls its `:tags` and checks for `:requires-auth`.

The redirect works by *skip-and-dispatch*. `:rf/skip-handler?` — the public short-circuit primitive an interceptor's `:before` sets on its ctx — stops the original handler, so the protected slice never commits and its `:on-match` loads never fire. The guard then dispatches the login navigation itself.

> **Gotcha — don't rewrite the event in place.** The runtime picks the handler from the *original* event id, so editing the event in `:before` would just run the wrong handler. Use skip-and-dispatch instead. And stash the target in app-db, not on the navigate opts — the navigate handler drops unknown opts, so a target smuggled onto the options map would simply vanish.

> **Gotcha — a bad URL is a non-match, not a crash.** If `match-url` can't resolve a path — an unknown route, or path params that fail the route's `:params` schema (`:validation-failed? true`) — it returns a non-match (`nil`), and `nav-target` short-circuits the guard for that event. The runtime's own URL-change handler routes the same non-match to [`:rf.route/not-found`](../glossary.md#not-found). So a logged-out user pasting a garbage protected-looking URL lands on not-found, never inside a guarded slice.

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

> **From re-frame v1 (and a frame gotcha) — `reg-frame` is a create-and-register, atomically.** There's no `:db` config key — a frame always starts with `app-db = {}`, and you build the initial state through dispatched events (the same [event cascade](../glossary.md#event-cascade) that handles every later change). Events that need nothing from the world can ride the frame's `:initial-events`; one that consumes a *provided* coeffect (like `:auth/init`'s host-read token) is dispatched at the boundary instead, where its `:rf.cofx` can be supplied. If you need to seed raw state ahead of the auth read, make `[:rf/set-db {…}]` the first step; events dispatch synchronously, in order. Editing `:initial-events` after the fact doesn't re-run them on a hot save — call `reset-frame!` to replay the setup. (Per [EP-0027](../../../spec/002-Frames.md).)

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

> **Why this matters — read-and-clear, not just read.** A stash that lingers is a footgun. The user logs in directly from `/login` an hour later, and a `:return-to` left over from this morning silently teleports them somewhere they never asked to go. Consuming the stash in the same handler that reads it means the bounce target lives exactly as long as one login round-trip — no longer.

## 6. Logout is a teardown

Logout has to clear three things: the session slice, the persisted token, *and* the departing user's cached server reads. Skip that last one and the next account sees the previous account's feed — precisely the bug you don't want anywhere near an auth flow.

The first two are easy — they're the slice and the persistence seam you already built. The third, clearing a user's cached server reads, is one causal event when you use [resources](../glossary.md#resource) (a *resource* being a managed, cached read of server state): `:rf.resource/clear-scope`.

To clear *a user's* cached reads you need a way to name "this user's scope." That's a **named resource-scope resolver**: a pure function, registered once, that derives a canonical [scope](../glossary.md#scope) value from app-db. The same resolver is used by your resources, your route loads, and logout — one scope currency, no per-call-site seams:

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

Now logout itself. There's one subtlety, and the ordering is the whole game: resolve the *old* scope from the coeffect `db` **before** you clear the auth slice. After the clear, the identity the scope derives from is gone.

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

`clear-scope` earns its keep: it removes that scope's cache entries, releases their owners, aborts in-flight requests nothing else owns, suppresses late replies (by scope-plus-generation checks), and emits a trace row explaining what was removed, aborted, and left alone. Every other scope stays intact, and there's no hand-maintained list of keys to forget ([Spec 016 — Resources](../../../spec/016-Resources.md)). Don't use resources? Drop that one `:fx` row and the rest stands.

> **Going deeper — `resolve-resource-scope` is pure, and that's load-bearing.** It reads the resolver registry against a `db` value with no dispatch, no app-state mutation, and no trace. That's exactly why you can call it *inline* in the handler to capture `old-scope` before the clear. Note the `:cause :logout` on the payload: `clear-scope` records it in resource history so Xray can attribute the eviction. And there's deliberately **no `:snapshot-db` key** — a whole-db snapshot riding an event vector would be an egress-bearing record on traces, which EP-0015 forbids.

> **Gotcha — a nil scope is loud, not silent.** If the resolver returns `nil` at a `clear-scope` site — say the user was already gone — the runtime emits a loud diagnostic (`:rf.warning/resource-clear-scope-unresolved`) rather than a silent no-op. So a logout that quietly *fails* to clear a session's cache shows up in the trace, instead of leaking the previous user's feed and looking fine.

> **Coming from TanStack Query?** This is `queryClient.clear()` with a scalpel instead of a sledgehammer. Rather than nuking the entire cache on logout, `clear-scope` evicts exactly the entries owned by the departing session's scope — derived from the *old* identity you captured before the clear — and leaves everything else (app-level config, public reads, a second logged-in frame) untouched.

## Observe it in Xray

With all six steps wired, watch the whole flow in [Xray](debug-with-xray.md):

- Logged out, click a link to a guarded route: the navigation event's row shows the guard skipping the handler and a follow-on dispatch to login — the protected route never reaches the route slice.
- Logged in, find an authenticated request: the live request carried `Authorization`, the captured trace shows it redacted — as is `[:auth :token]` in the app-db view.
- Reload the page while signed in: the `:auth/init` row shows the saved token folded in from the coeffect, and the slice comes back classified — no re-login.
- Dispatch `:auth/logout`: one clear-scope row lists what was removed, what was aborted, and what was left alone.
