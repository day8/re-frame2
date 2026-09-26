# Add authentication

This recipe adds login to an app: a session that survives a reload, requests that carry the user's token, routes only signed-in users can reach, a return to the page the user was headed for, and a logout that doesn't leak one user's data into the next session. re-frame2 has no auth subsystem; each piece is an ordinary event, effect, coeffect, or route declaration.

It uses two add-on artefacts, [routing](../../routing/concepts.md) (`day8/re-frame2-routing`) and [managed HTTP](../../async/http.md) (`day8/re-frame2-http`), and optionally [resources](../../resources/concepts.md) for the logout step. The login form itself is [Build a form](build-a-form.md).

[Part 3 of the tutorial](../../resources/tutorial/03-auth-and-forms.md) builds a variant against an API that hands out only a token, so its boot *fetches* the user where this page *restores* one (see the note at the end of step 1).

## 1. The session slice

The session is two [app-db](../glossary.md#app-db) paths:

- `[:auth :user]`: the signed-in user, or `nil` when nobody's logged in.
- `[:auth :token]`: the credential that requests carry.

The route guard checks `:user`, the request decorator reads `:token`, and logout clears both. Both have to survive a reload (the tip below explains why). Each [frame](../glossary.md#frame) has its own app-db, so a second frame on the page keeps its own session.

### Persist the session through one effect

A page reload throws away app-db, so the session also lives in `localStorage`. Give persistence exactly one [effect](../glossary.md#effect): it writes on a truthy token and removes on `nil`, so login, logout, and tests all go through the same code.

```clojure
;; The recipe's namespaces require [re-frame.core :as rf], [re-frame.http.managed],
;; [re-frame.routing], and [re-frame.resources] (step 6 only). Requiring the
;; add-on namespaces registers their events and effects.
(rf/reg-fx :auth.session/persist
  {:doc       "Persist the session — a truthy :token and the identity it stands for
               — or clear it (nil :token)."
   :platforms #{:client}}
  (fn [_frame-ctx {:keys [token user]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (if token
        (.setItem    ls "auth-session"
                     (js/JSON.stringify (clj->js {:token token :user user})))
        (.removeItem ls "auth-session")))))
```

`:platforms #{:client}` matters under [SSR](../../ssr/glossary.md#ssr), where there is no `localStorage` (a server session uses an http-only cookie). On a server the runtime skips the effect and records a `:rf.fx/skipped-on-platform` trace instead of crashing.

!!! tip "Store the identity as well as the token"

    The route guard in step 4 reads `[:auth :user]`. A boot that restores only the token has a valid credential and no signed-in user, so a reader who bookmarked a protected page is sent to login. Persisting the small user map beside the token puts the identity in app-db before the first URL is resolved. Cache display identity only (username, avatar), never a second copy of the token.

    The server still decides. The token may have expired since the last visit; step 3's 401 response hook turns that into a clean logout, which is what makes the optimistic restore safe.

!!! note "A `localStorage` token is readable by any script on your page"

    If XSS is in your threat model, use an http-only cookie and drop this effect. The rest of the recipe is unchanged, because the slice, the guard, and the teardown only read the credential back from app-db.

### Read the saved session back at boot

Without a boot read, every refresh logs the user out. An [event handler](../glossary.md#event-handler) is pure, so it can't read `localStorage` itself. It declares the saved session as a [coeffect](../glossary.md#coeffect) under `:rf.cofx/requires`, and the framework supplies the value before the handler runs ([Coeffects](../coeffects.md)).

Make it a [recordable](../glossary.md#recordable-vs-ambient-coeffects) coeffect: the saved session feeds durable `[:auth …]` state, so its value must be captured once and replayed verbatim, rather than re-read from whatever `localStorage` holds when an epoch is restored. The supplier is a plain function that does the storage read; it runs once, at the start of the boot dispatch, and its value is recorded:

```clojure
(rf/reg-cofx :auth.session/saved
  {:recordable? true
   :doc "The saved session {:token … :user …}, or nil when nobody is signed in
         or the host has no localStorage."}
  (fn []
    (some-> (.-localStorage js/globalThis)
            (.getItem "auth-session")
            js/JSON.parse
            (js->clj :keywordize-keys true))))
```

The init event in step 4 declares this coeffect and folds the saved session into the slice. In a test, a dispatch-site `{:rf.cofx {:auth.session/saved {…}}}` overrides the supplier, so you can pin an exact session without touching storage.

??? note "Why a supplier rather than a provided value"

    A recordable coeffect can instead be registered `{:recordable? true :provided? true}` with no supplier, its value stamped onto the dispatch by an owner (the built-in `:rf/time-ms` clock works this way). That doesn't fit here. The restore has to run from the frame's `:initial-events` so it finishes before the first URL is resolved (step 4 explains why), and `:initial-events` is frame configuration written before the frame exists, so there is nowhere to stamp a value. A supplier needs nothing threaded through: the handler declares the fact and the framework runs the supplier. Use *provided* when the fact's owner is someone else, such as a subsystem or a server request. [Coeffects](../coeffects.md#two-grades-ambient-and-recordable) covers the two grades in full.

### Keep the secret out of traces

The token is a credential, so the init event in step 4 also returns a `:sensitive` [classification](../glossary.md#data-classification) effect for `[:auth :token]`. The raw token then never appears in traces, [Xray](../glossary.md#xray) captures, or SSR payloads, while your handlers still see the real value ([Keep secrets and large things out of traces](keep-secrets-out-of-traces.md)).

!!! note "If your API hands out a token and nothing else"

    Some APIs expect you to exchange a stored bearer token for the current user with a `GET /me` at boot. The identity then arrives asynchronously, after the first URL has been resolved, so the guard in step 4 has to cope with a window where the token is known and the user is not. That costs a branch in the denial handler and a "restoring…" state in your shell. Both RealWorld examples take this shape (`examples/real-apps/realworld_http/auth.cljs`, under *the cold-boot deep-link window*), and [Part 4 of the tutorial](../../resources/tutorial/04-scopes-and-guards.md) walks through it. Use it only when the API leaves you no choice.

## 2. Wire the login form

The login form is the one from [Build a form](build-a-form.md), whose running example lives at `[:auth :login]`. The only change is that a successful submit establishes a session. Replace the form's `:form.login/submit-success` with this version, which stores the user and token, persists them, and sends the user on:

```clojure
(rf/reg-event :form.login/submit-success
  {:sensitive [[:value :user :token]]}  ;; the reply's token, redacted in the event trace
  (fn [{:keys [db]} [_ {:keys [value]}]]
    (let [user (:user value)]            ;; server reply: {:user {... :token "..."}}
      {:db (-> db
               (assoc-in [:auth :login :status]    :submitted)
               (assoc-in [:auth :login :submitted] (get-in db [:auth :login :draft]))
               (assoc-in [:auth :user]  (dissoc user :token))
               (assoc-in [:auth :token] (:token user)))
       :fx [[:auth.session/persist {:token (:token user)
                                    :user  (dissoc user :token)}]
            [:dispatch [:auth/post-login-redirect]]]})))
```

The token has one durable home, the classified `[:auth :token]` path. The user map is stored with `:token` stripped, so no unclassified copy sits at `[:auth :user :token]` to leak into off-box records. Both halves are persisted, so the next cold boot restores a session the route guard can see. The reply event itself carries the token too; the `:sensitive [[:value :user :token]]` metadata covers it, because paths in a registration's metadata are rooted at the event's arg-map, here the reply envelope.

The failure handler is unchanged from the form recipe. Don't add a `:retry` block to the login request: silently re-sending a credential submission can lock an account. Without one, [managed HTTP](../../resources/glossary.md#managed-http) delivers a 5xx or network drop as a failure reply and the user clicks again. A register form is the same wiring with a different URL and draft.

!!! warning "Gotcha: keep the password out of the trace on the way in"

    The form recipe edits the password through `:form.login/edit-password`, whose map payload is marked `{:sensitive [[:value]]}`. Keep it that way. A secret passed as a positional argument (`[:auth/login "user" "secret"]`) has no path to classify and appears unredacted in traces.

??? note "When to reach for a machine"

    Once login, register, and session restore start coordinating ("can't submit while restoring"), move the flow into a [machine](../../machines/glossary.md#machine) with states like `idle → submitting/restoring → authed | error`, as the RealWorld example's `auth.cljs` does ([realworld_http](../../../examples/real-apps/realworld_http)). The sign is an `if` over a `:status` keyword growing into nested "but only if not also…" conditions.

## 3. Decorate requests once, at the frame boundary

Every authenticated request needs the token in an `Authorization` header. Threading it through each request builder means one forgotten call site ships an unauthenticated request.

Write it once, as an HTTP interceptor. These belong to [managed HTTP](../../async/http.md) and are separate from event interceptors. Its `:before` receives a context map (`ctx`) holding the in-flight request and returns it, edited. This one reads the token from the frame's app-db and adds the header to every managed request that frame sends:

```clojure
;; cf. examples/real-apps/realworld_http/core.cljs
(defn- bearer-auth [ctx]
  (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
    (cond-> ctx
      token (assoc-in [:request :headers "Authorization"]
                      (str "Token " token)))))  ;; "Token" is RealWorld's scheme; yours may be "Bearer"

;; Register at boot, before the first authenticated request. Registration is
;; per frame; a bare top-level call raises :rf.error/no-frame-context.
(rf/with-frame :app
  (rf/reg-http-interceptor :my-app/bearer-auth
    {:before bearer-auth}))
```

- It reads `(:frame ctx)`, the frame this request runs under, so it keeps working on multi-frame pages ([frame identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found)).
- It returns `ctx` unchanged when there's no token, so login and public reads are untouched. Each slot must return a map: written as `(when token …)`, it would return `nil` for a logged-out request, which raises `:rf.error/http-interceptor-bad-return` and runs neither reply event.
- `Authorization` is on the framework's built-in header denylist, so the live request carries it while traces show it redacted.
- It never fires for another frame's requests.

This is the same move as registering one `axios` request interceptor instead of passing a config object to every call.

The same chain has a response side, which is where you catch an expired token:

```clojure
;; Catch a 401 and log out. `:after` receives the reply envelope:
;;   {:status :ok :value …}
;;   {:status :error :error {:kind :rf.http/http-4xx :status 401 …}}
(rf/with-frame :app
  (rf/reg-http-interceptor :my-app/expired-session
    {:after (fn [ctx response]
              (when (and (= :error (:status response))
                         (= :rf.http/http-4xx (get-in response [:error :kind]))
                         (= 401 (get-in response [:error :status])))
                ;; The reply runs in a transport callback with no frame in
                ;; scope, so a bare (rf/dispatch …) would raise
                ;; :rf.error/no-frame-context. Dispatch into this request's frame.
                (rf/dispatch [:auth/logout] {:frame (:frame ctx)}))
              response)}))                       ;; :after must return the response
```

Note the two `:status` levels. The reply's `:status` is `:ok`, `:error`, or `:cancelled`; the HTTP status code of a 4xx/5xx is at `(get-in response [:error :status])`, beside the failure `:kind`. Branch on the `:kind` keywords ([Managed HTTP](../../async/http.md) lists them), never on a message string.

To refresh the token and retry instead of logging out, drive the request from a [state machine](../../machines/concepts.md). Transport `:retry` decides from the failure category alone, so it can't wait on a second request ([Build a form](build-a-form.md#let-transport-retry-ride-out-the-flaky-network)).

An interceptor map needs `:before`, `:after`, or both; with neither, registration throws `:rf.error/http-bad-interceptor`.

??? note "How the chain composes"

    HTTP interceptors run like event [interceptors](../interceptors.md#the-sandwich-how-a-chain-runs): `:before` in registration order, `:after` in reverse, and an interceptor with only one of the two is skipped on the other leg. If a `:before` or `:after` throws, the request fails with `:rf.error/http-interceptor-failed` (carrying `:frame`, `:interceptor-id`, `:url`, and `:phase`) rather than going out undecorated. Handle anything recoverable inside the interceptor itself.

!!! warning "Gotcha: hot-reloading the interceptor"

    Re-evaluating `reg-http-interceptor` with the same id replaces it in place and keeps its position in the chain. `(rf/clear :http-interceptor id)` removes it, and a later re-registration appends it to the end of the chain. Don't clear-then-register in hot-reload code unless you want that.

## 4. Guard the protected routes

Some routes should open only for signed-in users. Declare a [`:can-enter`](../../routing/concepts.md#guarding-entry--can-enter) guard on each protected route. The runtime checks it for every way a navigation can start (programmatic navigate, a `route-link` click, the URL bar, a reload, Back/Forward, the initial load, and SSR), so there is no per-entry-point code to write.

```clojure
;; cf. examples/real-apps/realworld_http/routing.cljs
(rf/reg-route :app/home  {:doc "Home page."}    "/")
(rf/reg-route :app/login {:doc "Sign-in page."} "/login")

(rf/reg-route :app/settings
  {:doc       "Account settings."
   :tags      #{:requires-auth}
   :can-enter [:my-app/signed-in?]}
  "/settings")

(rf/reg-sub :auth/user
  (fn [db _] (get-in db [:auth :user])))

(rf/reg-sub :my-app/signed-in?
  {:doc "The :can-enter auth guard: true when a user is signed in."
   :inputs [[:auth/user]]}
  (fn [[user] _] (some? user)))               ;; true → OK to enter
```

- **The guard must return a boolean.** `true` allows entry and `false` refuses it. Anything else also refuses and raises `:rf.error/can-enter-non-boolean`, so write `(some? …)` or `(boolean …)` rather than relying on truthiness.
- **It reads step 1's `[:auth :user]`**, the durable slice a reload rebuilds, rather than a separate "logged in" flag or a machine's state. That is why step 1 persists the identity as well as the token.
- **`:tags #{:requires-auth}` is optional.** The framework attaches no meaning to it; keep it if a nav bar or a tool asks "is this page protected?". A `:can-enter` sub also receives the resolved target as its second argument, so one guard can serve every protected route and still branch on where the visitor was headed.

### What a refusal does

A refused entry commits nothing: no route slice, no URL push, no scroll, no `:on-match`, no resource load. Unlike a `:can-leave` block, it leaves no pending navigation to resume. The runtime dispatches `:rf.route/entry-denied` once, with this payload:

```clojure
{:destination   {:to :app/settings}          ;; a valid navigate request
 :target        {:route-id :app/settings :params {} :query {} :fragment nil :url "/settings"}
 :cause         :link                        ;; :link | :navigate | :popstate | :initial | :ssr
 :requested-url "/settings"
 :guard         :my-app/signed-in?}
```

The framework's default handler does nothing, so with only the declarations above a logged-out click on `/settings` leaves the visitor where they are and the URL unchanged. Under [SSR](../../ssr/glossary.md#ssr) the same refusal renders the shell with a `403` ([the entry-denial status](../../ssr/response.md#a-status-the-framework-writes-for-you-the-entry-denial-403)). To send the visitor to login instead, replace the handler.

### Bounce to login, remembering where they were headed

`:destination` carries the path params, query, and `#fragment`, and is itself a valid `:rf.route/navigate` request, so a deep link to `/editor/my-post?draft=1#preview` can come back to exactly that address. Stash it and redirect:

```clojure
;; cf. examples/real-apps/realworld_http/routing.cljs
(rf/reg-event :rf.route/entry-denied
  {:doc "Send a logged-out visitor to login, remembering where they were headed."}
  (fn [{:keys [db]} [_ {:keys [destination]}]]
    {:db (assoc-in db [:auth :return-to] destination)
     :fx [[:dispatch [:rf.route/navigate {:to :app/login :replace? true}]]]}))
```

- **`:replace? true`** keeps the refused URL off the back stack, so Back from `/login` doesn't run into the guard again.
- **Use `:destination`, not `:requested-url`.** Re-parsing the URL string is how a query and a `#fragment` get lost.
- **No `{:sensitive …}` map is needed.** The framework classifies the payload's URL fields as sensitive, and that carries over to your replacement handler, which still receives the real values. [Require sign-in on a route](../../routing/how-to/require-sign-in-on-a-route.md) has the details.

!!! note "Why not an interceptor over the navigation events?"

    A navigation reaches the runtime through three events (`:rf.route/navigate`, `:rf.route/url-requested` for a `route-link` click, and `:rf.route/handle-url-change` for the URL bar, reload, and Back/Forward), plus a `{:url …}` form and an in-place query edit that names no route id. An interceptor has to handle every one of those itself, and the one it misses lets a logged-out visitor in. `:can-enter` runs at the point all of them pass through. A frame interceptor is still right when the policy isn't about routes, such as a maintenance-mode lockout; see the [appendix](#appendix--when-the-policy-is-not-about-routes).

### Wire the frame and restore the session

The guard needs no wiring: it is route metadata, and requiring the routing artefact makes the runtime check it. What remains is the frame that owns the URL, and the order of its boot.

Restore the session from the frame's `:initial-events`. A `:url-bound? true` frame runs every `:initial-events` step first and only then resolves the current URL, so the session is in app-db before any guard runs. A restore dispatched after `make-frame` returns is too late: the first URL has already been checked against an empty auth slice.

```clojure
;; Frames start with app-db = {}; this event builds the auth slice from the
;; saved session and classifies the token path (step 1).
(rf/reg-event :auth/init
  {:rf.cofx/requires [:auth.session/saved]}        ;; ask for the saved session by name
  (fn [{:keys [db auth.session/saved]} _]
    {:db        (assoc db :auth {:user  (:user saved)   ;; the IDENTITY the guard reads
                                 :token (:token saved)}) ;; the credential requests carry
     :sensitive [[:auth :token]]}))                ;; step 1's egress protection

(rf/make-frame
  {:id             :app
   :doc            "The app frame."
   :url-bound?     true                            ;; this frame owns the browser URL
   :initial-events [[:auth/init]]})                ;; runs before the first URL is resolved
```

If you mount with `frame-root` as in [Boot and mount an app](boot-and-mount-an-app.md), put the same `:url-bound? true` and `:initial-events [[:auth/init]]` on its options instead of calling `make-frame`.

!!! warning "Gotcha: restoring the session after the frame exists"

    ```clojure
    ;; Don't do this
    (rf/make-frame {:id :app :url-bound? true})   ;; first URL resolved here
    (rf/with-frame :app
      (rf/dispatch-sync [:auth/init]))            ;; session arrives too late
    ```

    Tests that navigate somewhere first pass. Then a signed-in reader opens `/settings` directly, the guard runs against an empty auth slice, and they land on the login page holding a valid session. `:initial-events` steps run synchronously and in order, so if you need other state seeded before the auth read, make `[:rf/set-db {…}]` the first step.

!!! warning "Gotcha: exactly one frame owns the URL"

    `:url-bound? true` ([`url-bound?`](../../routing/glossary.md#url-bound)) makes this frame's navigation drive the browser address bar and Back/Forward. Only one frame may declare it; a second raises `:rf.error/duplicate-url-binding`. Leave any other frame on the page, such as [Xray](../glossary.md#xray), a story, or a second app instance, URL-unbound so it routes in memory.

!!! tip "When the identity arrives after the first route has committed"

    If your restore is asynchronous (a `GET /user` that validates the token), resources on a public deep link were planned while the viewer was unknown, and a `{:from-db …}` scope that resolved `nil` failed the route plan closed. Once the reply's handler has stored the user, re-plan the current route instead of navigating: `[:rf.route/replan-resources {:cause [:session-restore]}]` reruns the active route's resource plan under the resolved viewer and clears the planning error. The failure branch does the same after clearing the stale token (`{:cause [:session-restore-failed]}`). `examples/real-apps/realworld_resources/auth.cljs` shows both.

## 5. Bounce back after login

Step 2's success handler dispatches `:auth/post-login-redirect`. It navigates to the stashed destination with `:replace? true` (so `/login` stays off the back stack), and clears the stash in the same step:

```clojure
;; cf. examples/real-apps/realworld_http/auth.cljs
(rf/reg-event :auth/post-login-redirect
  (fn [{:keys [db]} _]
    (let [return-to (get-in db [:auth :return-to])]
      {:db (update db :auth dissoc :return-to)
       ;; Navigate with the whole stash: a partial {:to :params} would drop
       ;; the query string and #fragment.
       :fx [[:dispatch (if return-to
                         [:rf.route/navigate (assoc return-to :replace? true)]
                         [:rf.route/navigate {:to :app/home}])]]})))
```

Clearing the stash matters. Otherwise a user who later logs in directly from `/login` is sent to a destination left over from an earlier refusal.

## 6. Logout is a teardown

Logout clears the session slice, the persisted session, and the departing user's cached server reads. Skip the last one and the next account sees the previous account's data.

If you use [resources](../../resources/glossary.md#resource) (managed, cached server reads), clearing one user's cache is one event, `:rf.resource/clear-scope`. It needs a name for "this user's [scope](../../resources/glossary.md#scope)", which is a named resource-scope resolver: a pure function, registered once, that derives the scope from app-db. Your resources, route loads, and logout all use the same resolver:

```clojure
;; Pure: derives a scope from db; never fetches, dispatches, or reads ambient state.
(rf/reg-resource-scope :my-app/session
  {:inputs {:username [:db [:auth :user :username]]}}
  (fn [{:keys [username]} _ctx]
    (when username
      [:rf.scope/session {:username username}])))   ;; nil when logged out
```

In the logout handler, resolve the old scope from the handler's `db` *before* clearing the auth slice, because the scope derives from the identity you are about to remove:

```clojure
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    (let [old-scope (rf/resolve-resource-scope db :my-app/session)]   ;; pure; reads the pre-logout db
      {:db (-> db
               (assoc-in [:auth :user]  nil)
               (assoc-in [:auth :token] nil))
       ;; A nil :token removes the whole persisted session, identity included,
       ;; so the next boot can't restore a user with no credential.
       :fx [[:auth.session/persist {:token nil}]
            [:dispatch [:rf.resource/clear-scope {:scope old-scope :cause :logout}]]
            [:dispatch [:rf.route/navigate {:to :app/home}]]]})))
```

`clear-scope` removes that scope's cache entries, releases their owners, aborts in-flight requests nothing else owns, ignores late replies for the cleared scope, and records a trace row listing what it removed, aborted, and left alone. Other scopes, such as public reads or a second signed-in frame, are untouched ([Server state: resources](../../resources/concepts.md)). If you don't use resources, drop that `:fx` entry and skip the resolver.

`clear-scope` takes a concrete scope. Passing `{:from-db :my-app/session}` raises `:rf.error/resource-invalid-scope`, and it couldn't work anyway: the `:dispatch` runs as a later event, against the db you have just cleared, so a resolver run there would find no user.

## Check it in Xray

With all six steps wired, open [Xray](../../xray/index.md):

- Logged out, click a link to a guarded route. The next row is the `:rf.route/entry-denied` dispatch, then your redirect to login; no `:on-match` or resource row appears for the protected route.
- Logged in, open an authenticated request. The `Authorization` header shows as redacted, as does `[:auth :token]` in the app-db view.
- Reload the page while signed in on a protected URL. The `:auth/init` row, carrying the saved session from the coeffect, sits above the initial `:rf.route/handle-url-change` row, and the guarded route commits without an `:rf.route/entry-denied`. If the URL row ever comes first, the restore is no longer in `:initial-events`.
- Dispatch `:auth/logout`. One clear-scope row lists what was removed, aborted, and left alone.

## Appendix — when the policy is not about routes

Use a frame [interceptor](../glossary.md#interceptor) over the navigation events only for a rule that spans many routes and can't be expressed as route metadata: a maintenance-mode lockout, an analytics-driven redirect, a feature flag gating a whole section by tag. For "is this visitor signed in?", use `:can-enter` (step 4).

The interceptor must handle every navigation entry event itself, or a missed one lets the visitor through:

| Event | Trigger |
|---|---|
| `:rf.route/navigate` | Programmatic push — `(dispatch [:rf.route/navigate …])`, including the `{:url "/settings"}` escape hatch and the in-place query/fragment edit that names no route id |
| `:rf.route/url-requested` | A `route-link` click |
| `:rf.route/handle-url-change` | URL bar, reload, Back/Forward (popstate) |

The full recipe (the three-event normaliser, the `match-url` and in-place resolution each branch needs, and the redirect) is in [Require sign-in on a route → A policy that is not about routes](../../routing/how-to/require-sign-in-on-a-route.md#a-policy-that-is-not-about-routes).
