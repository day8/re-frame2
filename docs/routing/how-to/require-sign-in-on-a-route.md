# Require sign-in on a route

You know routes are [queryable data](../concepts.md#routes-are-queryable-data). This
page is one job: **one interceptor that bounces logged-out readers** off every route
tagged `:requires-auth`.

**Prefer `:can-enter` first.** A single protected page should declare
`{:can-enter [:auth/signed-in?]}` and handle `:rf.route/entry-blocked` — that is what
[realworld_http](../../../examples/real-apps/realworld_http) does, and it covers all
three entry doors without an interceptor. Use **this recipe** when one policy must
span *many* routes (a whole admin section, a maintenance lockout).

Full auth system (form, token, logout): [Add authentication](../../core/how-to/add-auth.md).

!!! warning "Three doors — gate all of them"

    Navigate, `route-link`, and URL-bar/reload/Back are three events. Guard only
    `:rf.route/navigate` and a logged-out paste of `/settings` walks in. Steps 2–3
    normalise all three. (`:can-enter` already does that.)

## 1. Tag the protected routes

```clojure
(rf/reg-route :app/login    {} "/login")
(rf/reg-route :app/settings {:tags #{:requires-auth}} "/settings")
(rf/reg-route :app/admin    {:tags #{:requires-auth}} "/admin")
```

Free-form tags — the framework attaches no meaning to `:requires-auth`; *you* do in
the guard. Readable anywhere: `(rf/handler-meta :route :app/settings)`.

## 2. Normalise the three navigation entry points

| Event | Trigger |
|---|---|
| `:rf.route/navigate` | Programmatic push — `(dispatch [:rf.route/navigate …])` |
| `:rf.route/url-requested` | A `route-link` click |
| `:rf.route/handle-url-change` | URL bar, reload, Back/Forward (popstate) |

Normalise all three to one `{:id <route-id> :params <map>}` target (or `nil`), then
decide once:

```clojure
(:require [re-frame.routing :as rf.routing])   ;; match-url lives here, not on rf/

(defn- matched-id
  "match-url → {:id :params}, or nil for non-match / schema-invalid match
   (:validation-failed? true — runtime sends those to not-found)."
  [url]
  (when-let [{:keys [route-id params validation-failed?]} (rf.routing/match-url url)]
    (when-not validation-failed?
      {:id route-id :params (or params {})})))

(defn- nav-target
  "Normalise any navigation event to {:id :params}, or nil.
   `current` is [:rf.runtime/routing :current] — needed for in-place navigate
   (no :to / :url): target is the route you are already on."
  [[ev-id a] current]
  (case ev-id
    :rf.route/navigate
    (let [{:keys [to url params]} a]
      (cond
        to  {:id to :params (or params {})}

        url (matched-id url)

        ;; in-place: query/fragment edit only — stay on current route
        (and (nil? params)
             (or (contains? a :query) (contains? a :query-merge) (contains? a :fragment)))
        {:id (:route-id current) :params (or (:params current) {})}

        :else nil))   ;; malformed → router rejects with :rf.error/navigate-bad-request

    :rf.route/url-requested
    (let [{:keys [to params]} a]
      (cond
        to  {:id to :params (or params {})}
        (:url a) (matched-id (:url a))))

    :rf.route/handle-url-change (matched-id a)

    nil))
```

Misses that matter:

- **`{:url …}` navigate** — resolve through `match-url` or a logged-out
  `[:rf.route/navigate {:url "/settings"}]` walks in.
- **In-place navigate** — resolve against `current` or an expired session on a
  protected page can change `?page=` and the guard fails open.
- **Neither destination nor in-place edit** — leave as `nil`; the runtime rejects
  with `:rf.error/navigate-bad-request`. Do not reclassify as "current route."
- **Schema-invalid URL** — treat as non-match (`nil`); not-found handles it.

## 3. Redirect with skip-and-dispatch

One interceptor, registered once, `:before` every event. Toward a `:requires-auth`
route with no signed-in user: **skip** the original handler (protected route never
commits; loaders never fire) and dispatch login:

```clojure
(rf/reg-interceptor :app/auth-guard
  {:doc "Redirect logged-out readers away from :requires-auth routes."}
  {:before
   (fn [ctx]
     (if-let [{:keys [id]} (nav-target (get-in ctx [:coeffects :event])
                                       (get-in ctx [:coeffects :rf.db/runtime
                                                    :rf.runtime/routing :current]))]
       (let [needs-auth? (contains? (:tags (rf/handler-meta :route id)) :requires-auth)
             signed-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
         (if (and needs-auth? (not signed-in?))
           (-> ctx
               (assoc :rf/skip-handler? true)
               (assoc-in [:effects :fx]
                         [[:dispatch [:rf.route/navigate {:to :app/login}]]]))
           ctx))
       ctx))})
```

Two pure reads: `rf/handler-meta` for `:tags`, `:db` in coeffects for the session.
`:rf/skip-handler?` is the public short-circuit — set it in `:before` and the
original handler is skipped.

!!! warning "Redirect, don't rewrite"

    The runtime picks the handler from the *original* event id. Editing the event in
    `:before` runs the wrong handler. Skip and dispatch a fresh navigation.

## 4. Attach the guard to the frame

```clojure
(rf/make-frame
  {:id :rf/default
   :doc          "The app frame."
   :url-bound?   true
   :interceptors [:app/auth-guard]})
```

Non-navigation events short-circuit in one `case` — cost on ordinary traffic is
negligible. Protected routes tagged, all three doors gated, logged-out reader bounced
to `/login`. Rest of the auth flow: [Add authentication](../../core/how-to/add-auth.md).
