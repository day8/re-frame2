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
  ;; route → URL — query keys print in canonical order, not the order you wrote them
  (is (= "/articles/intro" (rf.routing/route-url {:to :app/article :params {:id "intro"}})))
  (is (= "/search?page=2&q=clojure#results"
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

If the route declares `:on-match` events, they dispatch inside the same drain — one
that fires [managed HTTP](../async/http.md) wants the same canned-reply stubs a
pipeline-run test uses. Assert on what those events *did*: they never move
`@(rf/subscribe [:rf.route/transition])`, so a test that watches the transition to
prove an `:on-match` handler ran is watching the wrong thing.

`:rf.route/transition` and `:rf.route/error` report the route's **blocking
`:resources`**. Drive them by stubbing the resource read — a blocking read still on
its first load holds `:loading`, and a failed one lands a structured error in
`@(rf/subscribe [:rf.route/error])`. Assert on its category,
[never its prose](../core/errors.md#test-the-structure-not-the-string). Route
resources ensure, stub, and read exactly as they do anywhere else:
[Testing resources](../resources/testing.md).

## 3. Deep links, and the 404

Pasted link, reload, Back/Forward, and the SSR request all funnel through
`:rf.route/handle-url-change` — drive it directly. A bare dispatch on a test
frame is the deep-link and initial-load door:

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

### Say which door you meant

The route outcome is the same through all three, but the **cause** the runtime
records is not — and the cause is what a `:rf.route/planned` projection, an
entry denial and a blocked navigation all report. One dispatch shape per door:

```clojure
;; deep link, reload, initial load — a bare dispatch on a client frame
(rf/dispatch-sync [:rf.route/handle-url-change "/articles/intro"])

;; Back/Forward — stand in for the framework's own listener by carrying its rider
(rf/dispatch-sync [:rf.route/handle-url-change "/articles/intro"
                   {:rf.route/cause :popstate}])

;; SSR — the same bare dispatch, on a server frame
(rf/with-new-frame [f (rf/make-frame {:platform :server})]
  (rf/dispatch-sync [:rf.route/handle-url-change "/articles/intro"]))
```

Only the `:url-bound?` frame's listener stamps that rider in a real app, so
spell it only when you are standing in for that listener — and never install a
listener of your own to obtain it, because the rider *is* the simulation.
Without it a client dispatch resolves as `:initial`, so a test that calls itself
the Back/Forward case proves the right outcome and misnames the door it came
through.

## 4. The guards, with zero DOM

`:can-leave` is a subscription; the blocked navigation is *state*; the user's choice
is a dispatch. Whole flow, four asserts:

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
unmoved. `{:bypass-leave? true}` skips the park entirely.

The **entry** guard asserts differently, because a refusal is terminal — it parks
nothing, so there is no pending value to look for. Register a spy handler for
`:rf.route/entry-denied`, attempt the navigation signed out, and check that the
slice did not move and the denial fired exactly once:

```clojure
(deftest entry-denied-commits-nothing
  (rf/with-new-frame [f (rf/make-frame {})]
    (let [denials (atom [])]
      (rf/reg-event :rf.route/entry-denied
        (fn [_ [_ denial]] (swap! denials conj denial) {}))
      (rf/dispatch-sync [:rf.route/navigate {:to :app/settings}])
      (is (not= :app/settings @(rf/subscribe [:rf.route/id])))
      (is (nil? @(rf/subscribe [:rf/pending-navigation])))   ;; terminal: nothing parked
      (is (= 1 (count @denials)))
      (is (= {:to :app/settings} (:destination (first @denials)))))))
```

`:destination` is the replayable address, so the return-after-sign-in path is
another `dispatch-sync` of `[:rf.route/navigate destination]` with the guard's sub
flipped — an ordinary fresh attempt, not a resume. Note the shape: the destination
omits an empty `:params` / `:query` and a `nil` `:fragment`, so compare it against
`{:to :app/settings}`, not a fully-spelled address map.

## What lives elsewhere

- **Route auth** is the `:can-enter` guard plus a `:rf.route/entry-denied` handler,
  both of which you have just tested above — a guard sub is an ordinary sub and the
  handler an ordinary event. [Require sign-in on a route](how-to/require-sign-in-on-a-route.md)
  is the recipe. An interceptor over the navigation events is a *different* thing,
  reserved for a policy that genuinely is not about routes (a maintenance-mode
  lockout, a feature flag over a whole section); those are ordinary
  [interceptors, tested like any other](../core/interceptors.md#testing-an-interceptor).
- **A cold boot whose identity arrives asynchronously** deserves its own test, and it
  is the one people skip. If your boot fetches the signed-in user rather than reading
  a cached one, the first URL resolution runs *before* that reply lands, so a
  protected deep link is judged with no user. Drive the real thing — a `:url-bound?`
  frame whose `:url-strategy` `:decode` reports the protected URL, the app's real
  `:initial-events`, and a managed-HTTP stub that **captures the request and answers
  nothing** until you choose to. A canned stub that replies synchronously, or a test
  that navigates somewhere public first, cannot see the bug. The worked example is
  `realworld-cold-boot-deep-link-race` in `realworld_cljs_test.cljs`.
- **Route-declared `:resources`** — [Testing resources](../resources/testing.md)
  covers ensuring, stubbing, and reading; the route is just the cause.
- **Server side** needs no separate route tests: the same `handle-url-change` event
  is what SSR feeds the request URL to — [Testing SSR](../ssr/testing.md).
