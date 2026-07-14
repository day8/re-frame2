# Require sign-in on a route

You know routes are [queryable data](../concepts.md#routes-are-queryable-data). This
page is one job: **one interceptor that bounces logged-out readers** off every
route tagged `:requires-auth`.

**Prefer `:can-enter` first.** A single protected page should declare
`{:can-enter [:auth/signed-in?]}` and handle `:rf.route/entry-blocked` — that is
what [realworld_http](../../../examples/real-apps/realworld_http) does, and it covers
all three entry doors without an interceptor. Use **this recipe** when one policy
must span *many* routes uniformly (a whole admin section, a maintenance lockout).

Full auth system (form, token, logout): [Add authentication](../../core/how-to/add-auth.md).

!!! warning "Three doors — gate all of them"

    Navigate, `route-link`, and URL-bar/reload/Back are three events. Guard only
    `:rf.route/navigate` and a logged-out paste of `/settings` walks in. Steps 2–3
    normalise all three. (`:can-enter` already does that for free.)

## 1. Tag the protected routes

Mark routes that need a session with **`:tags`**. Free-form — the framework attaches
no meaning to `:requires-auth`; *you* do, in the guard:

```clojure
(rf/reg-route :app/login    {} "/login")
(rf/reg-route :app/settings {:tags #{:requires-auth}} "/settings")
(rf/reg-route :app/admin    {:tags #{:requires-auth}} "/admin")
```

Readable anywhere: `(rf/handler-meta :route :app/settings)` → metadata including
`:tags`.

## 2. Know the three navigation entry points

A reader reaches a route three different ways, each a different event:

| Event | How it's triggered |
|---|---|
| `:rf.route/navigate` | A programmatic push — `(dispatch [:rf.route/navigate …])`. |
| `:rf.route/url-requested` | A `route-link` click. |
| `:rf.route/handle-url-change` | The URL bar, a reload, Back/Forward (popstate). |

Guard `:rf.route/navigate` alone and the third row defeats you: a logged-out reader pasting `/settings` into the address bar, or reloading a protected page, never goes through `navigate`. So the guard normalises **all three** to one "where are we headed?" target, then decides once:

```clojure
(:require [re-frame.routing :as routing])   ;; match-url lives here, not on rf/

(defn- nav-target
  "Normalise any navigation event to {:id <route-id> :params <map>}, or nil."
  [[ev-id a b]]
  (case ev-id
    :rf.route/navigate
    {:id a :params (or b {})}

    :rf.route/url-requested
    (let [{:keys [to params url]} a]
      (cond
        to  {:id to :params (or params {})}
        url (when-let [{:keys [route-id params]} (routing/match-url url)]
              {:id route-id :params (or params {})})))

    :rf.route/handle-url-change
    (when-let [{:keys [route-id params]} (routing/match-url a)]
      {:id route-id :params (or params {})})

    nil))
```

`match-url` is pure: URL string → match map, or `nil` when nothing resolves (garbage
URLs short-circuit the guard rather than crash it).

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
(rf/make-frame
  {:id :rf/default
   :doc          "The app frame."
   :url-bound?   true
   :interceptors [:app/auth-guard]})    ;; reference the registered guard by id
```

That's the routing half done: protected routes are tagged, all three entry points are gated, and a logged-out reader is bounced to `/login`. The rest of the auth flow — the login form, the token, logout — is [Add authentication](../../core/how-to/add-auth.md), whose route guard is exactly this one.
