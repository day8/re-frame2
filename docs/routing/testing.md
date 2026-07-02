# Testing routes

Routing is a registration, an event, and a subscription — so it tests the way each of those tests: pure functions where the logic is pure, a dispatch into a test [frame](../core/glossary.md#frame) where the wiring matters. No browser anywhere. A test frame never declares `:url-bound? true`, so nothing here touches an address bar or calls `pushState` — the route slice is just state you assert on.

> **The URL codec is a pure function you call; a navigation is a dispatch you drive; the slice is state you read.**

The setup is the same as the [core testing pages](../core/testing/index.md): a JVM test namespace that requires the app namespaces (loading them performs the `reg-route` calls) plus the reset fixture, since routes live in the process-global registrar and the runtime keeps per-process routing state the fixture knows how to reset:

```clojure
(ns my-app.routing-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            [re-frame.test-support :as ts]
            [my-app.routes]))    ;; loading the ns registers the routes

(use-fixtures :each (ts/make-reset-runtime-fixture {}))
```

## 1. The URL codec: two pure functions

`route-url` and `match-url` are pure, JVM-runnable, and exact inverses — so the URL grammar of your whole route table unit-tests as plain function calls:

```clojure
(deftest article-urls-round-trip
  ;; route → URL
  (is (= "/articles/intro" (routing/route-url :app/article {:id "intro"})))
  (is (= "/search?q=clojure&page=2#results"
         (routing/route-url :app/search {} {:q "clojure" :page 2} "results")))
  ;; URL → route — schemas validate AND coerce, so :page comes back an int
  (let [m (routing/match-url "/search?q=clojure&page=2")]
    (is (= :app/search (:route-id m)))
    (is (= 2 (get-in m [:query :page]))))
  ;; and the misses are values, not exceptions
  (is (nil? (routing/match-url "/no/such/page")))
  (is (:validation-failed? (routing/match-url "/search?q=x&page=abc"))))
```

!!! warning "Gotcha — the nil-policy asymmetry is worth a test of its own"

    A `nil` **path** param throws `:rf.error/route-url-validation` territory (`:rf.error/missing-route-param` — there's no URL without the segment); a `nil` **query** param is *silently elided* (`{:page nil}` just omits the key). If your app leans on the elision — "only add `?sort=` when chosen" — pin it: `(is (= "/search?q=x" (routing/route-url :app/search {} {:q "x" :sort nil})))`.

## 2. Navigation through a test frame

The wiring — navigate event in, slice out — is a [cascade test](../core/testing/cascades.md): dispatch into a fresh frame, read the route subs. Inside `with-new-frame` the plain subs resolve against the test frame:

```clojure
(deftest navigate-writes-the-slice
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:rf.route/navigate :app/article {:id "intro"}])
    (is (= :app/article @(rf/subscribe [:rf.route/id])))
    (is (= {:id "intro"} @(rf/subscribe [:rf.route/params])))))
```

If the route declares `:on-match` loaders, they dispatch inside the same drain — so a loader that fires a [managed HTTP](../async/http.md) request wants the same canned-reply stubs a cascade test uses, and `@(rf/subscribe [:rf.route/transition])` gives you the `:idle` / `:loading` / `:error` fact to assert. A loader failure lands the structured error in `@(rf/subscribe [:rf.route/error])` — assert on its category, [never its prose](../core/concepts/errors.md#test-the-structure-not-the-string).

## 3. Deep links, and the 404

The URL-entry path — a pasted link, a reload, back/forward, and the SSR request — all funnel through one event, `:rf.route/handle-url-change`, and you can drive it directly:

```clojure
(deftest deep-link-resolves
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/intro"])
    (is (= :app/article @(rf/subscribe [:rf.route/id])))))

(deftest garbage-lands-on-not-found
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:rf.route/handle-url-change "/no/such/page"])
    (is (= :rf.route/not-found @(rf/subscribe [:rf.route/id])))
    ;; the params carry the offending URL — and a :reason for the other misses
    (is (= "/no/such/page" (:url @(rf/subscribe [:rf.route/params]))))))
```

The `:reason` discriminator distinguishes a plain miss from a schema failure (`:validation`) from malformed percent-encoding (`:malformed-url`) — each worth one assertion if your not-found view branches on it.

## 4. The leave guard, with zero DOM

The `:can-leave` flow is deliberately testable without a browser: the guard is a subscription, the blocked navigation is *state*, and the user's choice is a dispatch. So the whole are-you-sure flow is four asserts:

```clojure
(deftest leave-guard-parks-and-continues
  (rf/with-new-frame [f (rf/make-frame {})]
    ;; land on the guarded editor with unsaved changes (the guard sub reads app state)
    (rf/dispatch-sync [:rf.route/navigate :app/article-editor {:id "intro"}])
    (rf/dispatch-sync [:editor/typed "draft text"])

    ;; try to leave: the navigation parks, the slice doesn't move
    (rf/dispatch-sync [:rf.route/navigate :app/home])
    (is (some? @(routing/sub-pending-navigation)))
    (is (= :app/article-editor @(rf/subscribe [:rf.route/id])))

    ;; the user chooses: continue releases the parked navigation
    (rf/dispatch-sync [:rf.route/continue])
    (is (nil? @(routing/sub-pending-navigation)))
    (is (= :app/home @(rf/subscribe [:rf.route/id])))))
```

Dispatch `:rf.route/cancel` instead and the pending slot clears with the slice unmoved — one more test, same shape. A `{:bypass-leave-guard? true}` navigate opt skips the park entirely; if a "save then leave" button relies on it, pin that too.

## What lives elsewhere

- **Auth-guard interceptors** over the navigation events are ordinary [interceptors — tested like any other](../core/concepts/interceptors.md#testing-an-interceptor); [Require sign-in on a route](how-to/require-sign-in-on-a-route.md) is the recipe they guard.
- **Route-declared `:resources`** are the resources artefact's territory — [Testing resources](../resources/testing.md) covers ensuring, stubbing, and reading them; the route is just the cause.
- **The server side** needs no separate route tests: the same `handle-url-change` event you drove above is what SSR feeds the request URL to — [Testing SSR](../ssr/testing.md).
