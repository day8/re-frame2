# 19 - Routing

You want URLs to behave like part of the application instead of an unrelated browser superstition bolted on afterward. This chapter teaches re-frame2 routing: routes are registered data, URL changes become events, route state lives in `app-db`, and navigation is another named effect.

A route is a registry entry.

```clojure
(rf/reg-route :route/article
  {:path "/articles/:id"
   :params [:map [:id :string]]
   :on-match [[:article/load]]})
```

The route definition says how to match the URL, how to validate params, and what event should happen when the route becomes current.

## URL to state

When the browser URL changes, the router dispatches an event. That event updates the routing slice under `:rf/runtime` and runs the route's `:on-match` work. Views then read route state through subscriptions.

This means route transitions are visible in the same trace as every other event. A broken navigation is not a ghost in the address bar. It is a failed or unexpected event in the cascade.

## State to URL

Navigation is an effect. A handler can return a request to move to a route, and the runtime performs the browser work.

```clojure
{:fx [[:rf.route/navigate {:route-id :route/article
                           :params {:id "intro"}}]]}
```

The handler describes navigation. It does not call `history.pushState` from a random corner of the codebase.

## Guards are interceptors

Auth checks, unsaved-form prompts, and route preconditions compose naturally as interceptors or route metadata. The handler pipeline already has a place for "before this happens, decide whether it may happen."

## Blocking navigation: the can-leave protocol

Unsaved work is the canonical blocking case. A route can declare a leave policy that asks the current page whether navigation may proceed. The answer should be data: allow, block with a reason, or dispatch a confirmation flow.

```clojure
(rf/reg-route :route/editor
  {:path "/editor/:id"
   :can-leave [:editor/can-leave?]
   :on-match [[:editor/load]]})
```

The guard belongs in the routing pipeline, not in a component's `onClick`. That keeps browser back, programmatic navigation, and link clicks under the same rule.

## The stale result problem and the nav token

Navigation creates a familiar race: the user goes to article A, request A starts, the user jumps to article B, request B starts, and request A replies last. Without a guard, old data can paint the new page.

Carry a navigation token or route identity through the request and check it on reply.

```clojure
(rf/reg-event-db :article/load-ok
  (fn [db [_ {:keys [route-token article]}]]
    (if (= route-token (get-in db [:rf/runtime :routing :current :token]))
      (assoc db :article/current article)
      db)))
```

The exact token shape is less important than the invariant: late replies must prove they still belong to the current route before they change state.

## Pitfall: duplicating route state

Do not keep a second hand-rolled `:current-page` flag unless it is a derived view of the actual route. Two route states will drift. They always drift. The URL is not a suggestion; it is one of the public inputs to your app.
