# Require sign-in on a route

Keep signed-out readers off a route, send them to sign in, and bring them back to the
exact page they asked for once they have.

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]))

(rf/reg-route :app/login {} "/login")

(rf/reg-route :app/settings
  {:can-enter [:auth/signed-in?]}
  "/settings")

(rf/reg-sub :auth/user (fn [db _] (:auth/user db)))

(rf/reg-sub :auth/signed-in? {:inputs [[:auth/user]]}
  (fn [[user] _] (some? user)))

;; A refused entry: remember where the reader was going, then go to login.
(rf/reg-event :rf.route/entry-denied
  (fn [{:keys [db]} [_ {:keys [destination]}]]
    {:db (assoc db :auth/return-to destination)
     :fx [[:dispatch [:rf.route/navigate {:to :app/login :replace? true}]]]}))

;; A successful sign-in: go back there, or to the articles list.
(rf/reg-event :auth/sign-in
  (fn [{:keys [db]} [_ user]]
    (let [return-to (:auth/return-to db)]
      {:db (-> db (assoc :auth/user user) (dissoc :auth/return-to))
       :fx [[:dispatch [:rf.route/navigate
                        (assoc (or return-to {:to :app/articles}) :replace? true)]]]})))
```

The [tutorial](../tutorial.md#step-10--keep-signed-out-readers-out) introduces the
guard and a plain redirect to login; this version adds the return trip.

## How the guard works

`:can-enter` names a subscription. The runtime checks it on every way into the route —
a link, `:rf.route/navigate`, a typed URL, a refresh, Back/Forward, the first load,
and server rendering — so there is nothing to wire per entry point.

The sub must return `true` (enter) or `false` (refuse). Any other value refuses and
raises `:rf.error/can-enter-non-boolean`, which is why `:auth/signed-in?` wraps the
user in `some?`. The sub also receives the resolved target as the last element of its
query vector, `(fn [inputs [_ target]] …)`, so one sub can answer differently by
`(:route-id target)` or `(:params target)`.

A refusal commits nothing: no route change, no URL change, no scroll, no `:on-match`,
no resource load. Unlike a `:can-leave` block, nothing is parked to resume later. The
runtime dispatches `:rf.route/entry-denied` once, with:

```clojure
{:destination   {:to :app/settings}
 :target        {:route-id :app/settings :params {} :query {} :fragment nil :url "/settings"}
 :cause         :link              ;; how the reader tried to get in
 :requested-url "/settings"
 :guard         :auth/signed-in?}
```

The built-in handler does nothing, so without your own a refused click is ignored. Under server rendering the same refusal answers `403`, and navigating to
login from your handler doesn't change that. To answer with a redirect instead, add
`[:rf.server/redirect {:location "/login"}]` to the handler's `:fx`; the client skips
that effect ([the entry-denial `403`](../../ssr/response.md#a-status-the-framework-writes-for-you-the-entry-denial-403)).

## Why the return trip works

- **`:destination` is a complete navigate request.** It is the
  [destination](../glossary.md#destination) the URL resolved to, including path
  params, query and fragment, so a refused `/settings#privacy` returns to exactly
  that. Use it rather than rebuilding an address from `:requested-url`.
- **`:replace? true` on the way to login** keeps the refused URL out of the history,
  so Back from `/login` does not hit the guard again.
- **The return is a new navigation.** The guard runs again and, with a user present,
  allows it. If sign-in did not actually store a user, the guard refuses again. No
  navigation is left waiting, so nothing can loop.
- **Read and clear `:auth/return-to` in the same event**, so a later sign-in cannot
  send the reader somewhere stale.
- **The payload is already redacted in traces.** The framework marks
  `:requested-url`, `:destination` and `:target` as sensitive, and that still applies
  when your handler replaces the built-in one. Your handler receives the real values.

`:auth/return-to` is plain data, so it can equally live in `localStorage` to survive a
page reload.

## Protecting several routes

Put the same guard on each route. A shared map is enough:

```clojure
(def signed-in-only {:can-enter [:auth/signed-in?]})

(rf/reg-route :app/settings signed-in-only "/settings")

(rf/reg-route :app/article-editor
  (merge signed-in-only
         {:params    [:map [:slug :string]]
          :on-match  [[:editor/open]]
          :can-leave [:editor/can-leave?]})
  "/articles/:slug/edit")
```

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| A signed-out click on a protected link does nothing | No `:rf.route/entry-denied` handler, so the built-in one ignores it | Register the handler above |
| Entry is refused for a signed-in reader; `:rf.error/can-enter-non-boolean` is raised | The sub returned the user map, `nil`, or another non-boolean | Return `(some? user)` or `(boolean …)` |
| Back from `/login` returns to the refused page and bounces to login again | The redirect to login pushed a history entry | Navigate to login with `:replace? true` |
| After signing in, the reader lands on the articles list instead of the page they asked for | `:auth/return-to` was not stored, or was cleared before sign-in read it | Store `:destination` in the denial handler; read and clear it in the sign-in event |
| Navigation loops between the denial handler and `/login` | The login route has the `:can-enter` guard too | Leave the login route unguarded |
| Reloading a protected page while signed in lands on `/login` | The session is restored asynchronously and was not back when the URL was checked | Defer the redirect, as below |

## Advanced

### Deep links while a saved session is loading

If the app restores a session at boot by fetching the user with a saved token, the
first URL is checked before that reply arrives. A signed-in reader who reloads
`/settings` is judged with no user, and the handler above sends them to login.

The guard should still return `false`, because nothing protected may run while the
reader's identity is unknown. Make the denial handler tell "signed out" from "not
known yet": store the destination either way, and only redirect when no restore is in
progress. When the restore finishes, one event resolves the stored destination:

```clojure
;; cf. examples/real-apps/realworld_http/routing.cljs and auth.cljs
(defn restoring-session?
  "A saved token is present, but the user it belongs to has not arrived yet."
  [db]
  (and (nil? (:auth/user db)) (some? (:auth/token db))))

(rf/reg-event :rf.route/entry-denied
  (fn [{:keys [db]} [_ {:keys [destination]}]]
    (cond-> {:db (assoc db :auth/return-to destination)}
      (not (restoring-session? db))
      (assoc :fx [[:dispatch [:rf.route/navigate {:to :app/login :replace? true}]]]))))

;; Dispatch once the restore has finished, whether it succeeded or failed.
(rf/reg-event :auth/settle-deferred-entry
  (fn [{:keys [db]} _]
    (let [return-to (:auth/return-to db)]
      (cond
        (nil? return-to)       {}      ;; no protected deep link was waiting
        (some? (:auth/user db)) {:db (dissoc db :auth/return-to)
                                 :fx [[:dispatch [:rf.route/navigate
                                                  (assoc return-to :replace? true)]]]}
        :else                   {:fx [[:dispatch [:rf.route/navigate
                                                  {:to :app/login :replace? true}]]]}))))
```

`restoring-session?` only sees the token if it is in app-db when the first URL is
checked, so put it there from the url-bound frame's `:initial-events`, which run
first.

On success the stored destination becomes an ordinary navigation, which the guard now
allows. On failure the reader lands on login with the destination still stored, so
`:auth/sign-in` returns them to it.

While the restore is in progress no route has been entered, so
`@(subscribe [:rf.route/id])` is `nil`. Render a "restoring your session" state for
that case in the root view instead of a not-found page.

### A policy that is not about routes

Use a frame interceptor only for a rule that spans many routes and does not belong in
route metadata: a maintenance-mode lockout, or a feature flag that closes a whole
section. The example below closes every route tagged `:requires-auth`
(`{:tags #{:requires-auth}}` in its metadata) to signed-out readers.

An interceptor has to cover all three navigation events itself, or it lets
navigations through:

| Event | Sent by | Payload |
|---|---|---|
| `:rf.route/navigate` | `(dispatch [:rf.route/navigate …])` | The request map: `:to`, `:url`, or an in-place edit |
| `:rf.route/url-requested` | A `route-link` click | `{:url …}` |
| `:rf.route/handle-url-change` | The address bar, a reload, Back/Forward | The URL string |

Reduce each to one `{:id <route-id> :params <map>}` target, or `nil`, and decide once:

```clojure
(ns app.auth-guard
  (:require [re-frame.core :as rf]
            [re-frame.routing :as rf.routing]))   ;; match-url lives here

(defn- matched-id
  "The route a URL matches as {:id :params}, or nil when nothing matches or the
   params fail their schema (the runtime sends those to not-found)."
  [url]
  (when-let [{:keys [route-id params validation-failed?]} (rf.routing/match-url url)]
    (when-not validation-failed?
      {:id route-id :params (or params {})})))

(defn- nav-target
  "Reduce a navigation event to {:id :params}, or nil. `current` is the route
   slice, needed for an in-place navigate, whose target is the current route."
  [[ev-id a] current]
  (case ev-id
    :rf.route/navigate
    (let [{:keys [to url params]} a]
      (cond
        to  {:id to :params (or params {})}
        url (matched-id url)
        ;; in-place: a query or fragment edit on the current route
        (and (nil? params)
             (or (contains? a :query) (contains? a :query-merge) (contains? a :fragment)))
        {:id (:route-id current) :params (or (:params current) {})}
        :else nil))     ;; malformed: the runtime rejects it with :rf.error/navigate-bad-request

    :rf.route/url-requested     (matched-id (:url a))
    :rf.route/handle-url-change (matched-id a)
    nil))

(rf/reg-interceptor :app/auth-guard
  {:doc "Redirect signed-out readers away from :requires-auth routes."}
  {:before
   (fn [ctx]
     (if-let [{:keys [id]} (nav-target (get-in ctx [:coeffects :event])
                                       (get-in ctx [:coeffects :rf.db/runtime
                                                    :rf.runtime/routing :current]))]
       (let [route-meta  (rf/handler-meta {:source :store :kind :route :id id})
             needs-auth? (contains? (:tags route-meta) :requires-auth)
             signed-in?  (some? (get-in ctx [:coeffects :db :auth/user]))]
         (if (and needs-auth? (not signed-in?))
           (-> ctx
               (assoc :rf/skip-handler? true)
               (assoc-in [:effects :fx]
                         [[:dispatch [:rf.route/navigate {:to :app/login :replace? true}]]]))
           ctx))
       ctx))})
```

Each branch closes a way in:

- **`{:url …}` navigate** goes through `match-url`; otherwise
  `[:rf.route/navigate {:url "/settings"}]` gets past.
- **In-place navigate** resolves to the current route; otherwise a reader whose
  session expired on a protected page could still change its query.
- **A request with neither** stays `nil`, because the runtime rejects it anyway.
- **A URL whose params fail their schema** stays `nil`; it goes to not-found.

Setting `:rf/skip-handler?` in `:before` stops the original navigation, so the
protected route never commits and its `:on-match` never runs; the `:fx` then starts a
new navigation to login. Do not edit the event in `:before` instead: the runtime has
already chosen the handler from the original event id.

Attach the interceptor to the url-bound frame, and mount that frame with
`frame-provider`:

```clojure
(rf/make-frame
  {:id           :app
   :url-bound?   true
   :interceptors [:app/auth-guard]})
```

Events that are not navigations fall through the `case` at once. The rest of the
sign-in flow is in [Add authentication](../../core/how-to/add-auth.md).
