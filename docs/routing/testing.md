# Testing routes

You can [author](tutorial.md) and [understand](concepts.md) routing. This page is how
you **prove** it.

Routing is a registration, an event, and a subscription — pure codec where the logic
is pure, dispatch into a test [frame](../core/glossary.md#frame) where the wiring
matters. No browser. A test frame never sets `:url-bound? true`, so nothing here
touches the address bar — the route slice is state you assert on.

> **The URL codec is a pure function you call; a navigation is a dispatch you drive; the slice is state you read.**

Same setup as the [core testing pages](../core/testing/index.md): a JVM test namespace
that requires the app namespaces (loading them runs the `reg-route` calls) plus the
reset fixture — routes live in the process-global registrar and the runtime keeps
per-process routing state the fixture knows how to reset:

```clojure
(ns my-app.routing-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as rf.routing]
            [re-frame.test-support :as ts]
            [my-app.routes]))    ;; loading the ns registers the routes

(use-fixtures :each (ts/make-reset-runtime-fixture {}))
```

## 1. The URL codec: two pure functions

`route-url` and `match-url` are pure, JVM-runnable, and exact inverses — the URL
grammar of the whole route table unit-tests as plain function calls:

```clojure
(deftest article-urls-round-trip
  ;; route → URL
  (is (= "/articles/intro" (rf.routing/route-url {:to :app/article :params {:id "intro"}})))
  (is (= "/search?q=clojure&page=2#results"
         (rf.routing/route-url {:to :app/search :query {:q "clojure" :page 2} :fragment "results"})))
  ;; URL → route — schemas validate AND coerce, so :page comes back an int
  (let [m (rf.routing/match-url "/search?q=clojure&page=2")]
    (is (= :app/search (:route-id m)))
    (is (= 2 (get-in m [:query :page]))))
  ;; misses are values, not exceptions
  (is (nil? (rf.routing/match-url "/no/such/page")))
  (is (:validation-failed? (rf.routing/match-url "/search?q=x&page=abc"))))
```

!!! warning "Nil-policy asymmetry"

    A `nil` **path** param is a hard error — `route-url` throws
    `:rf.error/missing-route-param`. A `nil` **query** param is *silently elided*
    (`{:page nil}` omits the key). If the app leans on elision — "only add `?sort=`
    when chosen" — pin it:
    `(is (= "/search?q=x" (rf.routing/route-url {:to :app/search :query {:q "x" :sort nil}})))`.

## 2. Navigation through a test frame

Wiring — navigate event in, slice out — is a
[pipeline-run test](../core/testing/pipeline-runs.md): dispatch into a fresh frame,
read the route subs. Inside `with-new-frame` the plain subs resolve against the test
frame:

```clojure
(deftest navigate-writes-the-slice
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:rf.route/navigate {:to :app/article :params {:id "intro"}}])
    (is (= :app/article @(rf/subscribe [:rf.route/id])))
    (is (= {:id "intro"} @(rf/subscribe [:rf.route/params])))))
```

If the route declares `:on-match` loaders, they dispatch inside the same drain — a
loader that fires [managed HTTP](../async/http.md) wants the same canned-reply stubs
a pipeline-run test uses. `@(rf/subscribe [:rf.route/transition])` is the
`:idle` / `:loading` / `:error` fact; loader failure lands a structured error in
`@(rf/subscribe [:rf.route/error])` — assert on its category,
[never its prose](../core/errors.md#test-the-structure-not-the-string).

## 3. Deep links, and the 404

Pasted link, reload, back/forward, and the SSR request all funnel through
`:rf.route/handle-url-change` — drive it directly:

```clojure
(deftest deep-link-resolves
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/intro"])
    (is (= :app/article @(rf/subscribe [:rf.route/id])))))

(deftest garbage-lands-on-not-found
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:rf.route/handle-url-change "/no/such/page"])
    (is (= :rf.route/not-found @(rf/subscribe [:rf.route/id])))
    ;; params carry the offending URL — and a :reason for other misses
    (is (= "/no/such/page" (:url @(rf/subscribe [:rf.route/params]))))))
```

`:reason` distinguishes plain miss, schema failure (`:validation`), and malformed
percent-encoding (`:malformed-url`) — one assertion each if the not-found view
branches on it.

## 4. The leave guard, with zero DOM

`:can-leave` is a subscription; blocked navigation is *state*; the user's choice is a
dispatch. Whole flow, four asserts:

```clojure
(deftest leave-guard-parks-and-continues
  ;; frame BOOTS on the guarded editor with unsaved changes — setup rides
  ;; :initial-events; body dispatches only the moves under test
  (rf/with-new-frame [f (rf/make-frame
                          {:initial-events
                           [[:rf.route/navigate {:to :app/article-editor :params {:id "intro"}}]
                            [:editor/typed "draft text"]]})]
    ;; try to leave: navigation parks, slice doesn't move
    (rf/dispatch-sync [:rf.route/navigate {:to :app/home}])
    (is (some? @(rf/subscribe [:rf/pending-navigation])))
    (is (= :app/article-editor @(rf/subscribe [:rf.route/id])))

    ;; user chooses: continue takes the pending-nav id
    (rf/dispatch-sync [:rf.route/continue
                       (:id @(rf/subscribe [:rf/pending-navigation]))])
    (is (nil? @(rf/subscribe [:rf/pending-navigation])))
    (is (= :app/home @(rf/subscribe [:rf.route/id])))))
```

Dispatch `[:rf.route/cancel <id>]` instead and the pending slot clears with the slice
unmoved. `{:bypass-guards? #{:leave}}` skips the park. The `:can-enter` mirror tests
the same way — guarded target, signed-out sub, assert pending fills with
`:direction :enter`, then flip the sub and `[:rf.route/continue <id>]` (re-runs
`:can-enter`).

## What lives elsewhere

- **Auth-guard interceptors** over navigation events are ordinary
  [interceptors — tested like any other](../core/interceptors.md#testing-an-interceptor);
  [Require sign-in on a route](how-to/require-sign-in-on-a-route.md) is the recipe.
- **Route-declared `:resources`** — [Testing resources](../resources/testing.md)
  covers ensuring, stubbing, and reading; the route is just the cause.
- **Server side** needs no separate route tests: the same `handle-url-change` event
  is what SSR feeds the request URL to — [Testing SSR](../ssr/testing.md).
