# Require sign-in on a route

One job: **a logged-out visitor cannot reach a protected route through any door**,
and after signing in they land exactly where they were headed.

The tool is [`:can-enter`](../concepts.md#guarding-entry--can-enter) — a boolean
subscription declared on the route itself. The runtime consults it in the one
planning pipeline, so it fails *closed* through every door (programmatic navigate,
`route-link` click, URL bar / reload, Back/Forward, initial load, SSR) with no
per-door plumbing.

Full auth system (form, token, logout): [Add authentication](../../core/how-to/add-auth.md).

## 1. Declare the guard on the route

```clojure
(rf/reg-route :app/login    {} "/login")

(rf/reg-route :app/settings
  {:can-enter [:auth/signed-in?]}
  "/settings")

(rf/reg-sub :auth/signed-in?
  :<- [:auth/user]
  (fn [user _] (some? user)))          ;; true → OK to enter
```

`true` allows entry; `false` refuses it. The contract is closed — a non-boolean
return refuses *and* raises `:rf.error/can-enter-non-boolean`, so write
`(boolean …)` or `(some? …)` rather than leaning on truthiness.

The guard sub also receives the resolved target as a second argument
(`(fn [inputs [_ target] …])`), so one shared guard can branch on where the visitor
was headed — `(:route-id target)`, `(:params target)`, or the target route's
`:tags` via `rf/handler-meta`.

## 2. Understand what a refusal does

Entry refusal is **terminal**. Nothing commits: no route slice, no URL push, no
scroll, no `:on-match`, no resource load — and, unlike a `:can-leave` block, **no
pending-navigation value is parked**. There is no paused transition to resume,
because there is nothing half-done to resume *into*.

The runtime dispatches `:rf.route/entry-denied` exactly once with:

```clojure
{:destination   {:to :app/settings}          ;; replayable — this is the useful bit
 :target        {:route-id :app/settings :params {} :query {} :fragment nil :url "/settings"}
 :cause         :link                        ;; which door
 :requested-url "/settings"
 :guard         :auth/signed-in?}
```

**You do not have to handle it.** The framework ships a no-op default handler, so
with steps 1–2 alone a logged-out click on `/settings` simply does nothing: the
visitor stays where they are, the URL never moves, and nothing protected runs.
(Under SSR the same refusal renders the shell under a `403`.) That is already a
correct, safe app. Step 3 is the *friendlier* version.

## 3. The fresh-return recipe

Three ordinary steps: **stash the destination, replace-navigate to login, navigate
freshly back.**

```clojure
(rf/reg-event :rf.route/entry-denied
  {:doc "Send a logged-out visitor to login, remembering where they were headed."}
  (fn [{:keys [db]} [_ {:keys [destination]}]]
    {:db (assoc-in db [:auth :return-to] destination)
     :fx [[:dispatch [:rf.route/navigate {:to :app/login :replace? true}]]]}))

;; …later, when the sign-in succeeds:
(rf/reg-event :auth/signed-in
  (fn [{:keys [db]} [_ user]]
    (let [return-to (get-in db [:auth :return-to])]
      {:db (-> db (assoc-in [:auth :user] user) (update :auth dissoc :return-to))
       :fx [[:dispatch [:rf.route/navigate
                        (assoc (or return-to {:to :app/home}) :replace? true)]]]})))
```

Why each piece:

- **`:destination` is already the answer.** It is a
  [`:rf/route-destination`](../../../spec/Spec-Schemas.md#rfroute-destination) — the
  canonical named address the target resolved to, carrying path params, query, and
  `#fragment`. It is a valid `:rf.route/navigate` request as it stands, so a
  deep-link to `/editor/my-post?draft=1#preview` returns to *that*, not to a bare
  `/editor/my-post`. Don't re-derive it from `:requested-url`.
- **`:replace? true` on the way to login** keeps the refused URL off the back stack,
  so Back from `/login` doesn't bounce the visitor straight into the guard again.
- **The return is a plain navigation.** No resume, no bypass, no special-casing: the
  guard runs again on that fresh attempt and — now that a user is present — allows
  it. If the sign-in silently failed, the guard refuses again, which is exactly what
  you want. Nothing can loop, because nothing was left pending.
- **Read *and* clear `:return-to` in one step**, so a later ordinary login can't be
  hijacked by a stale crumb.

Store `:return-to` wherever suits you — it is plain data, so it survives in
`app-db`, in `localStorage`, or through a page reload.

## 4. Protecting a group of routes

The same sub-id on every protected registration. An ordinary map helper is enough —
routing deliberately does not add a middleware chain to avoid one:

```clojure
(def ^:private protected {:can-enter [:auth/signed-in?]})

(rf/reg-route :app/settings (merge protected {:doc "Account settings."}) "/settings")
(rf/reg-route :app/admin    (merge protected {:doc "Admin console."})    "/admin")
```

Because the guard sub receives the target, one sub can serve them all and still
answer differently per route.

## Appendix — when the policy is not about routes

Reach for a frame interceptor only when the rule genuinely spans many routes and is
*not* expressible as route metadata: a maintenance-mode lockout, an analytics-driven
redirect, a feature flag gating a whole section by tag.

An interceptor must cover all three navigation entry events itself, or it fails
**open**:

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

Then redirect with skip-and-dispatch. One interceptor, registered once, `:before`
every event. Toward a `:requires-auth`-tagged route with no signed-in user: **skip**
the original handler (the protected route never commits; loaders never fire) and
dispatch login:

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
                         [[:dispatch [:rf.route/navigate {:to :app/login :replace? true}]]]))
           ctx))
       ctx))})
```

Two pure reads: `rf/handler-meta` for `:tags`, `:db` in coeffects for the session.
`:rf/skip-handler?` is the public short-circuit — set it in `:before` and the
original handler is skipped.

!!! warning "Redirect, don't rewrite"

    The runtime picks the handler from the *original* event id. Editing the event in
    `:before` runs the wrong handler. Skip and dispatch a fresh navigation.

Attach it to the frame:

```clojure
(rf/make-frame
  {:id :rf/default
   :doc          "The app frame."
   :url-bound?   true
   :interceptors [:app/auth-guard]})
```

Non-navigation events short-circuit in one `case`, so the cost on ordinary traffic is
negligible. Rest of the auth flow:
[Add authentication](../../core/how-to/add-auth.md).
