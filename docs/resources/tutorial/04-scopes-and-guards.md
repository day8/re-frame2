# Part 4: whose cache is it? Scopes, and guarding a route

[Part 3](03-auth-and-forms.md) gave Conduit a session: a sign-in, a token on every request, and a restore on reload. That has two consequences. The server now answers *for the reader*, so Part 2's reads need a cache per reader. And some pages — settings, the editor — should refuse to open while nobody is signed in.

This part adds one namespace, `src/conduit/scope.cljc`, and grows `resources.cljc`, `auth.cljc` and `core.cljs`.

## Whose cache is it? Scope reads by viewer

Part 3's bearer interceptor changed what Part 2's reads mean. Signed in, the server answers *for you*: each article carries `favorited`, and its author `following`, relative to the token. With both reads registered `:rf.scope/global`, the cache would hand the first reader's copy — flags and all — to the next reader asking for the same params.

The fix is a named **[scope resolver](../glossary.md#scope-resolver)** that answers "who is reading?" from app-db, with both registrations pointing at it:

```clojure
;; src/conduit/scope.cljc
;; cf. examples/real-apps/realworld_resources/scope.cljs
(ns conduit.scope
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.resources]))

(rf/reg-resource-scope :conduit/viewer
  {:doc    "Whose copy of a public read is this? The signed-in username, a
            confirmed anonymous reader, or nil (fail-closed) while a saved
            token has not been checked yet."
   :inputs {:username [:db [:auth :user :username]]
            :token    [:db [:auth :token]]}}
  (fn [{:keys [username token]} _ctx]
    (cond
      username           [:rf.scope/viewer {:username username}]
      (str/blank? token) [:rf.scope/viewer :anonymous]
      :else              nil)))
```

A signed-in reader gets a scope of their own; readers with no token share one anonymous copy. Between boot and the `GET /user` reply there's a token but no user yet — a request sent then isn't anonymous, but it doesn't belong to anyone the app can name. The resolver returns `nil`, and `nil` **fails closed**: a subscription raises `:rf.error/resource-sub-unresolved-scope` and a route plan refuses, rather than guessing.

Now point Part 2's reads at it. In `src/conduit/resources.cljc`, add `[conduit.scope]` to the requires and change both `:scope` lines to:

```clojure
   :scope          {:from-db :conduit/viewer}
```

Nothing else in Part 2 changes: routes and `:rf/resource` subscriptions inherit the registration's scope. Three places now have to respect the new identity.

**The shell waits out a restore.** While the resolver says `nil`, subscribing to either read is an error, so the root view shows a holding line instead. Add a sub to `auth.cljc` that knows when the viewer is unknown:

```clojure
(rf/reg-sub :auth/restoring?
  {:doc "A saved token is in hand but GET /user hasn't answered yet: the viewer is unknown."}
  (fn [db _]
    (and (nil? (get-in db [:auth :user]))
         (not (str/blank? (get-in db [:auth :token]))))))
```

and wrap the root view's `case` in it:

```clojure
(reg-view root-view []
  [:div.app
   [header]
   (if @(subscribe [:auth/restoring?])
     [:div.container.page [:p "Restoring your session…"]]
     (case @(subscribe [:rf.route/id])
       :conduit/home          [articles/home-page]
       :conduit.article/show  [articles/article-page]
       :conduit.auth/login    [auth/login-page]
       :rf.route/not-found    [not-found-page]
       [not-found-page]))])
```

**A restore replans the page it's on.** The first URL's reads failed closed while the viewer was unknown. When the reply lands, the subscription re-keys to the new scope, but re-keying never fetches, and navigating to the current page is a no-op. `:rf.route/replan-resources` reruns the current route's reads under the current identity without navigating. Replace the two restore handlers so both outcomes dispatch it:

```clojure
(rf/reg-event :auth/session-restored
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (assoc-in db [:auth :user] (dissoc (:user value) :token))
     :fx [[:dispatch [:rf.route/replan-resources {:cause [:session-restore]}]]]}))

(rf/reg-event :auth/session-expired
  (fn [{:keys [db]} _]
    {:db (update db :auth assoc :user nil :token nil)  ;; targeted: form slices survive
     :fx [[:auth.session/persist {:token nil}]
          [:dispatch [:rf.route/replan-resources {:cause [:session-restore-failed]}]]]}))
```

**Signing in and out change the reader.** Signing in needs nothing extra: login ends by navigating, and a newly entered route plans its reads under the new reader. Signing out needs two more steps.

## Sign-out clears the reader's cache

Part 3's `:auth/logout` ends the session. Now it also leaves a reader's cache behind. Replace it in `auth.cljc`, and add `[conduit.scope]` to that file's requires, because logout now names the viewer resolver:

```clojure
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    (let [old-viewer (rf/resolve-resource-scope db :conduit/viewer)]
      {:db (assoc db :auth {:user nil :token nil})
       :fx (cond-> [[:auth.session/persist {:token nil}]]
             old-viewer (conj [:dispatch [:rf.resource/clear-scope {:scope old-viewer :cause :logout}]])
             true       (conj [:dispatch [:rf.route/navigate {:to :conduit/home}]]
                              [:dispatch [:rf.route/replan-resources {:cause [:logout]}]]))})))
```

The two new steps are about the cache. `rf/resolve-resource-scope` runs the viewer resolver against the handler's `db` — the value *before* this event, which still knows who is leaving — and `:rf.resource/clear-scope` evicts that reader's entries and aborts anything of theirs in flight. The replan comes after the navigate: signed out *on* the home page, the navigate is a no-op, and the replan is what fetches the anonymous list.

Scope already keeps readers apart — the next reader's entries are different cache keys — so clearing is cleanup, not the protection.

## The guard

Settings and the editor should refuse to open while signed out. Each protected route names a `:can-enter` guard in its metadata, and the runtime consults it for every navigation, however it started. (`:tags` is free-form classification the framework attaches no meaning to — handy when a navbar wants to ask "is this page protected?")

```clojure
;; add to src/conduit/auth.cljc
(rf/reg-route :conduit.user/settings
  {:tags      #{:requires-auth}
   :can-enter [:conduit/signed-in?]
   :on-match  [[:settings/load]]}
  "/settings")

(rf/reg-sub :conduit/signed-in?
  {:doc "The :can-enter auth guard: true when a user is signed in."
   :inputs [[:auth/user]]}
  (fn [[user] _] (some? user)))               ;; true → OK to enter
```

That's the whole gate. Sign-out needs nothing extra for it: once `:auth/user` is `nil`, the guard refuses again.

!!! note "Why a route guard rather than an event interceptor"

    Navigations arrive by several doors — `:rf.route/navigate` (in several shapes, including `{:url …}` and in-place query edits that name no route id), link clicks, and the URL bar or Back button. An interceptor on one navigation event has to handle every shape itself, and the one it misses lets a signed-out visitor in. `:can-enter` runs in the single planning step all of them pass through, so it can't be bypassed. A frame interceptor is still right for policies that aren't about routes, such as a maintenance-mode lockout — see [Require sign-in on a route](../../routing/how-to/require-sign-in-on-a-route.md#a-policy-that-is-not-about-routes).

### What a refusal does, and the login bounce

A refusal is **terminal**. Nothing commits — no route slice, no URL push, no `:on-match`, so `[:settings/load]` never fires — and nothing is parked waiting to resume.

The runtime dispatches `:rf.route/entry-denied` once. Its default handler does nothing, so a signed-out click on *Settings* currently has no visible effect. Your replacement sends the visitor to login, with one exception that comes from Part 3's boot:

!!! warning "Gotcha — the token lands in time, the *identity* does not"

    `:initial-events` settles synchronous work; it doesn't wait for requests. So when the first URL is resolved, `[:auth :token]` is set but `[:auth :user]` is still `nil` — `GET /user` hasn't answered. A protected deep link is therefore refused on that first resolution, and the handler below treats that refusal as "we don't know yet" rather than "you're not signed in". If your API lets you store the user alongside the token, restore both at boot and there's no window at all — [Add authentication](../../core/how-to/add-auth.md#read-the-saved-session-back-at-boot) shows that simpler shape.

```clojure
(rf/reg-event :rf.route/entry-denied
  {:doc "Steer a signed-out visitor to login, remembering where they were headed.
         While a cold-boot restore is still in flight, defer the bounce instead:
         we don't yet know that they're signed out."}
  (fn [{:keys [db]} [_ {:keys [destination]}]]
    (let [restoring? (and (nil?        (get-in db [:auth :user]))     ;; identity unknown…
                          (some?       (get-in db [:auth :token])))]  ;; …but a token is in hand
      (cond-> {:db (assoc-in db [:auth :return-to] destination)}
        (not restoring?)
        (assoc :fx [[:dispatch [:rf.route/navigate {:to       :conduit.auth/login
                                                    :replace? true}]]])))))

;; Once the restore settles, resolve whatever was deferred:
;;   signed in  → navigate to the stash again; the guard now allows it
;;   signed out → go to login, keeping the stash for after sign-in
;;   no stash   → it was a public deep link; nothing to do
(rf/reg-event :auth/settle-deferred-entry
  (fn [{:keys [db]} _]
    (let [return-to (get-in db [:auth :return-to])]
      (cond
        (nil? return-to)                     {}
        (some? (get-in db [:auth :user]))    {:db (update db :auth dissoc :return-to)
                                              :fx [[:dispatch [:rf.route/navigate
                                                               (assoc return-to :replace? true)]]]}
        :else                                {:fx [[:dispatch [:rf.route/navigate
                                                               {:to :conduit.auth/login :replace? true}]]]}))))
```

Add `[:dispatch [:auth/settle-deferred-entry]]` to the `:fx` of **both** restore handlers, `:auth/session-restored` and `:auth/session-expired`. It reads the settled slice rather than being told which outcome happened, so there's one code path.

Why branch in the handler rather than make the guard smarter? `:can-enter` returns a strict boolean, and mid-restore the true answer to "is this visitor signed in?" is `false`. What that refusal *means* is policy, and policy belongs in the handler. Waiting is safe because the refusal committed nothing, so no protected page is on screen meanwhile. A deferred entry commits no route either, so the shell's `:auth/restoring?` branch shows the holding line instead of the not-found page.

Three details:

- **`:destination` is the resolved address** the visitor was heading to — path params, query and fragment — and is itself a valid `:rf.route/navigate` request. Stashing it at `[:auth :return-to]`, where Part 3's `submit-success` already looks, returns the user to the exact URL.
- **`:replace? true` on the hop to login** keeps the refused URL off the back stack, so Back from `/login` doesn't run into the guard again.
- **Register the handler without a `:sensitive` map.** The framework's classification of the payload's URLs still applies to your replacement handler, and your handler sees the real values. ([Require sign-in on a route](../../routing/how-to/require-sign-in-on-a-route.md) covers the handler in full.)

The return trip is an ordinary navigation: the guard runs again and, now that a user is present, allows it.

??? info "Coming from Axios?"

    A redirect-on-401 response interceptor does two jobs. The **gating** job moves to the route: `:can-enter` stops the navigation before any request exists. The **session-expiry** job stays on the response side, because `:can-enter` can't notice a token that expires after entry was allowed — [Part 3's second trigger](03-auth-and-forms.md#the-second-trigger-the-server-signs-you-out) is that hook.

Watch it fire. Signed out, click *Settings*. In Xray the navigation row is followed by `:rf.route/entry-denied` and your redirect to login, and there's no `[:settings/load]` row, because the route never committed. Sign in, and the ledger shows the bounce back to `/settings`.

Then reload directly on `/settings` while signed in. The ledger reads: `:auth/initialise`, the initial `:rf.route/handle-url-change`, one `:rf.route/entry-denied` with **no** redirect after it, the `/user` reply, `:auth/settle-deferred-entry`, and finally the navigate that commits `/settings`. A redirect-to-login row in the middle of that sequence means the deferral has broken.

!!! note "A guard must return a boolean — strictly"

    `true` allows, `false` refuses, and any other value refuses *and* raises `:rf.error/can-enter-non-boolean`. A sub that returns `nil` because its slice isn't seeded yet doesn't quietly half-work; guard against an absent slice in the sub itself. ([Part 5](05-mutations-and-invalidation.md#guard-the-half-written-draft) adds the mirror-image guard, `:can-leave`, which stops you *leaving* a page with unsaved work.)
