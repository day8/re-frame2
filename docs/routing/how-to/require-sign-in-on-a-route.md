# Require sign-in on a route

Some pages — a settings screen, an admin dashboard — should open only for signed-in users. In re-frame2 there's no special "protected route" primitive: a route is [queryable data](../concepts.md#routes-are-queryable-data), so you *tag* the routes that need a session and let an ordinary [interceptor](../../core/concepts/interceptors.md) read the tag and redirect. Data plus a step that reads it — no router machinery.

This page is the **routing half** of auth: tag a route, gate it, bounce the reader to login. The surrounding system — the login form, the token that requests carry, logout — is [Add authentication](../../core/how-to/add-auth.md), which embeds this same guard in the full picture. Start here for the route mechanics; go there for the whole flow.

> **The one mistake to avoid.** There are *three* ways into a route, and a guard that gates only one fails **open** on the most common path — a logged-out reader who types the URL or reloads the page walks straight in. Steps 2–3 are about gating all three.

## 1. Tag the protected routes

Mark the routes that need a session with a `:tag`. Tags are free-form data — the framework attaches no meaning to `:requires-auth`; *you* do, in the guard:

```clojure
(rf/reg-route :app/login    {} "/login")
(rf/reg-route :app/settings {:tags #{:requires-auth}} "/settings")
(rf/reg-route :app/admin    {:tags #{:requires-auth}} "/admin")
```

The tag is now readable off the route table from anywhere: `(rf/handler-meta :route :app/settings)` returns the metadata, `:tags` and all. That's the whole "which routes are protected?" query — no registry to hand-maintain.

## 2. Know the three navigation entry points

A reader reaches a route three different ways, each a different event:

| Event | How it's triggered |
|---|---|
| `:rf.route/navigate` | A programmatic push — `(dispatch [:rf.route/navigate …])`. |
| `:rf/url-requested` | A `route-link` click. |
| `:rf.route/handle-url-change` | The URL bar, a reload, Back/Forward (popstate). |

Guard `:rf.route/navigate` alone and the third row defeats you: a logged-out reader pasting `/settings` into the address bar, or reloading a protected page, never goes through `navigate`. So the guard normalises **all three** to one "where are we headed?" target, then decides once:

```clojure
;; Adapted from examples/real-apps/realworld_http/routing.cljs
(defn- nav-target
  "Normalise any navigation event to {:id <route-id> :params <map>}, or nil for
   a non-navigation event (the guard then short-circuits)."
  [[ev-id a b]]
  (case ev-id
    :rf.route/navigate          {:id a :params (or b {})}
    :rf/url-requested           (let [{:keys [to params url]} a]
                                  (cond
                                    to  {:id to :params (or params {})}
                                    url (when-let [{:keys [route-id params]} (routing/match-url url)]
                                          {:id route-id :params (or params {})})))
    :rf.route/handle-url-change (when-let [{:keys [route-id params]} (routing/match-url a)]
                                  {:id route-id :params (or params {})})
    nil))
```

`routing/match-url` is pure (it lives in `re-frame.routing`, not on the `rf/` facade) — it turns a URL string into a match map, or `nil` when nothing resolves, so a garbage URL short-circuits the guard rather than crashing it.

## 3. Redirect with skip-and-dispatch

Now the guard itself: one interceptor, registered once, that runs `:before` every event. For a navigation toward a `:requires-auth` route with no signed-in user, it **skips** the original handler (so the protected route never commits and its loaders never fire) and dispatches the login navigation instead:

```clojure
(rf/reg-interceptor :app/auth-guard
  {:doc "Redirect logged-out readers away from :requires-auth routes."}
  {:before
   (fn [ctx]
     (if-let [{:keys [id]} (nav-target (get-in ctx [:coeffects :event]))]
       (let [needs-auth? (contains? (:tags (rf/handler-meta :route id)) :requires-auth)
             signed-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
         (if (and needs-auth? (not signed-in?))
           (-> ctx
               (assoc :rf/skip-handler? true)                    ;; protected route never commits
               (assoc-in [:effects :fx]
                         [[:dispatch [:rf.route/navigate :app/login]]]))
           ctx))
       ctx))})              ;; not a navigation ⇒ pass through untouched
```

Two reads do the work, both pure: `rf/handler-meta` pulls the route's `:tags`, and the `:db` in `:coeffects` says whether anyone's signed in. `:rf/skip-handler?` is the public short-circuit primitive — set it in a `:before` and the original handler is skipped.

> **Gotcha — redirect, don't rewrite.** The runtime picks the handler from the *original* event id, so editing the event in `:before` would just run the wrong handler. Skip the original and dispatch a fresh navigation, as above.

## 4. Attach the guard to the frame

Register the guard once, then name it by id in the frame's `:interceptors`. It short-circuits in a single `case` for every non-navigation event, so the cost on ordinary traffic is negligible:

```clojure
(rf/reg-frame :rf/default
  {:doc          "The app frame."
   :url-bound?   true
   :interceptors [:app/auth-guard]})    ;; reference the registered guard by id
```

That's the routing half done: protected routes are tagged, all three entry points are gated, and a logged-out reader is bounced to `/login`.

## Where to go from here

This page deliberately stops at the route boundary. The full recipe — the login form, persisting the token, decorating requests with it, **bouncing the reader back to where they were headed** after login, and a logout that clears the departing session's cached data — is [Add authentication](../../core/how-to/add-auth.md) (its step 4 is this guard, with the return-to bounce-back wired in). For the auth state machine once login/restore start coordinating, see [Machines](../../machines/concepts.md).

## See also

- [Concepts → Routes are queryable data](../concepts.md#routes-are-queryable-data) — the tag-and-read pattern this recipe is built on.
- [Guard against unsaved changes](guard-unsaved-changes.md) — the other navigation-control recipe: blocking the way *out* of a route.
- [Add authentication](../../core/how-to/add-auth.md) — the complete auth flow this guard slots into.
