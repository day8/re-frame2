# Add authentication

You're adding auth, which really means four things: a login form, requests that carry the user's token, routes only signed-in users can reach, and a logout that doesn't leak one user's data into the next session. Each piece turns out to be an event, an interceptor, or an effect you already know — an event is a record of "something happened" that you dispatch, an interceptor wraps handlers to run code before and after them, and an effect is a description of a side effect the framework carries out for you. None of these are new for auth.

The ecosystem anchor here is the **axios auth interceptor**, plus a `<RequireAuth>` route wrapper, plus a logout that clears whatever you remember to clear. re-frame2 keeps all three ideas, with some deliberate differences. Request decoration is a registered HTTP interceptor that reads the token straight from the frame's `app-db` — `app-db` being your application's single state map, scoped per frame. That gives you one seam, no token threading, and no module-level mutable token sitting around. The route guard is an ordinary event [interceptor](../concepts/interceptors.md) attached frame-wide. And logout is *causal*: clearing the departing user's cached server data is one named, traced event, not a checklist you have to maintain by hand.

> **Auth is a slice, a guard, and a teardown — not a library.**

You'll need [routing](../concepts/routing.md) (`day8/re-frame2-routing`) and [managed HTTP](../concepts/http.md) (`day8/re-frame2-http`). Step 6 also touches [resources](../concepts/server-state.md) if you cache server state. Step 2 builds on [Build a form](build-a-form.md) and doesn't repeat its mechanics.

## 1. The token slice — and its one persistence seam

Session state lives in two `app-db` paths. `[:auth :user]` holds the signed-in user, and it's `nil` when nobody's logged in. `[:auth :token]` holds the credential requests carry. The guard checks `:user`, the request decorator reads `:token`, and logout clears both. That's the whole slice.

The token is a secret, so the slice ships with two protections. First, classify the path `[:auth :token]` as `:sensitive` — by returning a classification effect from an init event the frame runs at creation (step 4 wires it up). A frame is an isolated runtime context with its own `app-db`; classifying the *path* means the raw token never leaves the box in traces, Xray captures, or SSR payloads, while your handlers keep seeing the real value ([Keep secrets and large things out of traces](keep-secrets-out-of-traces.md)). Second, give persistence exactly **one seam**: a single effect that writes on a truthy token and removes on `nil`, so login, logout, and your tests all hit the same edge rather than scattering localStorage calls everywhere.

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

There are two honest caveats worth pausing on here, because this trips people up.

> **This effect is client-only.** Under SSR there's no `localStorage`, so a session rides an http-only cookie instead. The effect is declared `:platforms #{:client}` for exactly this reason — on the server it simply isn't registered, and any `:auth.session/persist` row is a no-op rather than a crash.

> **A localStorage token is readable by any script on your page.** If XSS is in your threat model, use the http-only cookie and drop this effect — the rest of the recipe stands unchanged. The token slice, the guard, and the teardown don't care *how* the credential was persisted; they only read it back from `app-db`.

Reading the saved token *back* at boot is a world read — a [coeffect](../concepts/effects-and-coeffects.md), which is an input the framework gathers and hands to your handler so the handler itself stays pure. That part is wired end to end in [Part 3 of the tutorial](../tutorial/03-auth-and-forms.md).

## 2. Wire the login form

The form is exactly [Build a form](build-a-form.md) — same slice shape, same seven events, same error-visibility rule. That recipe's running example is already this login form at `[:auth :login]`. Auth changes just one thing: **success establishes a session**. So upgrade that page's `:form.login/submit-success` to an fx handler — a handler is the function that runs when an event is dispatched — that stores the user and token, persists, and bounces the user onward:

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

The failure handler is unchanged from the form recipe. Notice that login deliberately does **not** retry — one submission per click. A transient 5xx surfaces as an error and the user clicks again, which is the behaviour you want, because silently re-firing a credential submission is a good way to lock an account or double-charge a flow.

Register is the same wiring with a different URL and draft. The two flows are the same shape; only the endpoint and the initial draft change.

> **When the states start sharing transitions, reach for a machine.** Once login, register, and session restore start coordinating ("can't submit while restoring"), graduate to a five-state [machine](../concepts/machines.md) — `idle → submitting/restoring → authed | error` — as the RealWorld example does ([auth.cljs](../../../examples/reagent/realworld/)). The tell is when an `if` over a `:status` keyword grows into a nest of "but only if not also…" conditions; that's a state machine wearing a trench coat.

## 3. Decorate requests once, at the frame seam

Don't thread the token through your request builders. Instead, one HTTP interceptor's `:before` reads the token from the frame's `app-db` and stamps every outbound managed request crossing that frame. One write site, not dozens:

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

This is the production shape, for three reasons worth spelling out. It reads `(:frame ctx)` — the frame the cascade actually runs under, never a hard-coded id — so it survives renamed and multi-frame mounts. It returns the ctx unchanged when there's no token, which is why login and public reads are untouched. And `Authorization` sits on the framework's built-in redaction denylist ([Spec 014 — privacy](../../../spec/014-HTTPRequests.md)), so the live request carries it while off-box traces never do.

> **Coming from re-frame v1?** This used to be a wrapper fn around every `http-xhrio` map — and one forgotten call site meant one unauthenticated request. Here the frame seam is the single write site, so there's no call site to forget. It's the same shift you make when you move from passing an `axios` config object everywhere to registering one request interceptor: the decoration becomes structural, not something each caller has to remember.

## 4. Guard the protected routes

Route-level auth is an interceptor over the navigation events, not a special routing mechanism ([Spec 012 — redirects and guards](../../../spec/012-Routing.md)). Start by tagging the routes that need a session:

```clojure
(rf/reg-route :app/login    {} "/login")
(rf/reg-route :app/settings {:tags #{:requires-auth}} "/settings")
```

Then comes the guard. There's one thing it absolutely must get right: **gate every navigation entry point**. Guarding `:rf.route/navigate` alone fails *open* on the most common path — a logged-out user who types `/settings` into the URL bar, reloads a protected page, or clicks a link gets in anyway. The fix is to normalise all three entry points to one target and redirect identically:

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

The redirect works by *skip-and-dispatch*. `:rf/skip-handler?` stops the original handler, so the protected slice never commits and its `:on-match` loads never fire. The guard then dispatches the login navigation itself.

> **Don't rewrite the event in place.** The runtime picks the handler from the *original* event id, so editing the event would run the wrong handler. Use skip-and-dispatch instead. And stash the target in `app-db`, not on the navigate opts — the navigate handler drops unknown opts, so a target smuggled onto the options map would simply vanish.

Now wire it frame-wide — by reference. The guard is registered once under `:my-app/auth-guard`; the frame's `:interceptors` chain names that id, never the interceptor value. It short-circuits in a single `case` lookup for every non-navigation event, so the cost on ordinary traffic is negligible. The same frame's `:initial-events` runs the init event that classifies the token path — step 1's egress protection, in place before any off-box egress:

```clojure
;; Adapted from examples/reagent/realworld/core.cljs
;; The init event classifies the durable token path :sensitive via the
;; commit-plane classification effect (EP-0025) — returned alongside :db.
(rf/reg-event :auth/init
  (fn [{:keys [db]} _]
    {:db        (assoc db :auth {:user nil :token nil})
     :sensitive [[:auth :token]]}))             ;; step 1's egress protection

(rf/reg-frame :rf/default
  {:doc            "The app frame."
   :url-bound?     true                         ;; this frame owns the browser URL
   :initial-events [[:auth/init]]               ;; classify [:auth :token] at creation
   :interceptors   [:my-app/auth-guard]})       ;; reference the registered guard by id
```

## 5. Bounce back after login

An auth guard's headline feature is returning the user to exactly where they were headed. Step 2's success handler already dispatches the bounce; here's the handler it calls. It reads **and clears** the stash in one step, so a later plain login can't accidentally bounce someone to a stale target:

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

> **Why read-and-clear, not just read?** A stash that lingers is a footgun: the user logs in directly from `/login` an hour later, and a `:return-to` left over from this morning silently teleports them somewhere they never asked to go. Consuming the stash in the same handler that reads it means the bounce target lives exactly as long as one login round-trip.

## 6. Logout is a teardown

Logout has to clear three things: the session slice, the persisted token, *and* the departing user's cached server reads. Skip that last one and the next account sees the previous account's feed — which is the kind of bug you really don't want in an auth flow. With [resources](../concepts/server-state.md) — a resource being a managed, cached read of server state — that last part is one causal event, `:rf.resource/clear-scope`. There's one subtlety worth flagging, because the ordering matters: resolve the *old* scope from the coeffect `db` **before** you clear the auth slice. After the clear, the identity the scope derives from is gone.

```clojure
;; Scope resolution per Spec 016; :my-app/session is your reg-resource-scope resolver.
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    (let [old-scope (rf/resolve-resource-scope db :my-app/session)]
      {:db (-> db
               (assoc-in [:auth :user]  nil)
               (assoc-in [:auth :token] nil))
       :fx [[:auth.session/persist {:token nil}]
            [:dispatch [:rf.resource/clear-scope {:scope old-scope :cause :logout}]]
            [:dispatch [:rf.route/navigate :app/home]]]})))
```

`clear-scope` removes that scope's cache entries, releases their owners, aborts in-flight requests nothing else owns, and suppresses late replies. Every other scope stays intact, and there's no hand-maintained list of keys to forget ([Spec 016 — Resources](../../../spec/016-Resources.md)). If you don't use resources, drop that one `:fx` row and the rest stands.

> **Coming from TanStack Query?** This is `queryClient.clear()` with a scalpel instead of a sledgehammer. Rather than nuking the entire cache on logout, `clear-scope` evicts exactly the entries owned by the departing session's scope — derived from the *old* identity you captured before the clear — and leaves everything else (app-level config, public reads, a second logged-in frame) untouched.

## Observe it in Xray

- Logged out, click a link to a guarded route: the navigation event's row shows the guard skipping the handler and a follow-on dispatch to login — the protected route never reaches the route slice.
- Logged in, find an authenticated request: the live request carried `Authorization`, the captured trace shows it redacted — as is `[:auth :token]` in the app-db view.
- Dispatch `:auth/logout`: one clear-scope row lists what was removed, what was aborted, and what was left alone.

---

You can now:

- hold a session as two `app-db` paths — one persistence seam, the token path classified `:sensitive` from an init event
- wire login success into store-persist-redirect, with no retry it shouldn't have
- stamp the auth header once, at the frame's HTTP seam, instead of per call site
- guard all three navigation entry points, with a bounce-back that can't go stale
- tear down a session causally — slice, persisted token, and cached reads
