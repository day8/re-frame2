# Testing routes

Routing tests need no browser. `route-url` and `match-url` are pure functions you
call directly; a navigation is an event you dispatch into a test
[frame](../core/glossary.md#frame); the active route is a subscription you read. A
test frame is not `:url-bound?`, so nothing touches the address bar.

The setup is the one the [core testing pages](../core/testing/index.md) use: a JVM
test namespace that loads the app's route registrations, plus the reset fixture,
which restores the registrar and the routing state around each test:

```clojure
(ns app.routing-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as rf.routing]
            [re-frame.schemas]                               ;; turns on route schema validation
            [re-frame.substrate.plain-atom :as plain-atom]   ;; the JVM substrate a frame needs
            [re-frame.test-support :as ts]
            [app.core]))                                     ;; registers the routes

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter       plain-atom/adapter
                                  :ambient-frame nil}))   ;; each test makes its own frame
```

The tests below use the routes of the [tutorial](tutorial.md#the-complete-app)'s
articles app.

## URLs

`route-url` builds a URL from an address, and `match-url` turns a URL back into one:

```clojure
(deftest article-urls-round-trip
  (is (= "/articles/intro"
         (rf.routing/route-url {:to :app/article :params {:slug "intro"}})))
  (is (= "/articles?tag=ssr#top"
         (rf.routing/route-url {:to :app/articles :query {:tag "ssr"} :fragment "top"})))

  (let [m (rf.routing/match-url "/articles?tag=ssr")]
    (is (= :app/articles (:route-id m)))
    (is (= "ssr" (get-in m [:query :tag]))))   ;; a declared key arrives as a keyword

  (is (nil? (rf.routing/match-url "/no/such/page"))))   ;; a miss is nil, not an exception
```

A missing or `nil` path param makes `route-url` throw. Here that is
`:rf.error/route-url-validation`, because the route's `:params` schema rejects it
first; on a route with no `:params` schema it is `:rf.error/missing-route-param`. A
`nil` query value is dropped instead, which is what lets a filter link leave `?tag=`
out when no tag is chosen. If the app relies on that, pin it:

```clojure
(is (= "/articles" (rf.routing/route-url {:to :app/articles :query {:tag nil}})))
```

Schemas always coerce: an `:int` param arrives as a number. They validate only when
`re-frame.schemas` is loaded, which is why the test namespace requires it; then a URL
whose values fail the schema comes back from `match-url` with
`:validation-failed? true`.

## Navigation

Dispatch a navigation into a fresh frame and read the route subs. Inside
`with-new-frame`, `rf/subscribe` reads from that frame:

```clojure
(deftest navigate-changes-the-route
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:rf.route/navigate {:to :app/article :params {:slug "intro"}}])
    (is (= :app/article @(rf/subscribe [:rf.route/id])))
    (is (= {:slug "intro"} @(rf/subscribe [:rf.route/params])))))
```

A route's `:on-match` events run inside the same `dispatch-sync`, so assert on what
they did — here, `@(rf/subscribe [:article/current])`. If one starts
[managed HTTP](../async/http.md), stub it as any pipeline-run test would. `:on-match`
never changes `:rf.route/transition`, so do not watch the transition to prove it ran.

`:rf.route/transition` and `:rf.route/error` report the route's blocking
`:resources`. Test those by stubbing the resource read, as
[Testing resources](../resources/testing.md) describes: a blocking read still on its
first load gives `:loading`, and a failed one puts a structured error on
`:rf.route/error`. Assert on the error's category,
[not its message](../core/errors.md#test-the-structure-not-the-string).

## Deep links and not-found

A pasted link, a reload, Back/Forward and a server-rendered request all arrive as
`:rf.route/handle-url-change` with the URL. Dispatch it directly:

```clojure
(deftest deep-link-opens-the-article
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/intro"])
    (is (= :app/article @(rf/subscribe [:rf.route/id])))))

(deftest unknown-url-lands-on-not-found
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:rf.route/handle-url-change "/no/such/page"])
    (is (= :rf.route/not-found @(rf/subscribe [:rf.route/id])))
    (is (= "/no/such/page" (:url @(rf/subscribe [:rf.route/params]))))))
```

Not-found params also carry a `:reason`: absent for a plain miss, `:validation` when
the params failed their schema, `:malformed-url` for bad percent-encoding, and
`:match-error` when matching the URL threw. Add a case for each one your not-found
page treats differently.

Server rendering uses the same event on a server frame,
`(rf/make-frame {:platform :server})`, so it needs no separate route tests; see
[Testing SSR](../ssr/testing.md).

### Simulating a link click or Back/Forward

The runtime records how a navigation arrived, its cause, and uses it to pick the
default scroll: `:top` for a link click or a `:rf.route/navigate`, `:restore` for Back,
Forward and the first load. The cause
also appears in entry denials and blocked navigations. A bare dispatch on a client
frame is recorded as `:initial`, the cause for a deep link or reload. To stand in for
a link click or Back/Forward, pass the cause the framework would have attached:

```clojure
;; a link click
(rf/dispatch-sync [:rf.route/handle-url-change "/articles/intro" {:rf.route/cause :link}])

;; Back/Forward
(rf/dispatch-sync [:rf.route/handle-url-change "/articles/intro" {:rf.route/cause :popstate}])
```

In a real app the framework adds these itself, so pass one only when simulating that
entry point, and do not install a listener of your own to get it. Without it, a test
named for a link click checks the `:restore` scroll default instead of `:top`.

## Guards

A `:can-leave` block is state you can read, and the reader's answer is a dispatch.
The frame below starts on the editor with unsaved changes, set up through
`:initial-events`:

```clojure
(deftest leaving-the-editor-asks-first
  (rf/with-new-frame [f (rf/make-frame
                          {:initial-events
                           [[:rf.route/navigate {:to :app/article-editor :params {:slug "intro"}}]
                            [:editor/edit "A new title"]]})]
    (rf/dispatch-sync [:rf.route/navigate {:to :app/home}])
    (is (some? @(rf/subscribe [:rf/pending-navigation])))
    (is (= :app/article-editor @(rf/subscribe [:rf.route/id])))

    (rf/dispatch-sync [:rf.route/continue
                       (:id @(rf/subscribe [:rf/pending-navigation]))])
    (is (nil? @(rf/subscribe [:rf/pending-navigation])))
    (is (= :app/home @(rf/subscribe [:rf.route/id])))))
```

Dispatching `[:rf.route/cancel <id>]` instead clears the pending navigation and leaves
the route unchanged. Navigating with `:bypass-leave? true` is never blocked.

A `:can-enter` refusal parks nothing, so there is no pending value to check. Assert on
what your `:rf.route/entry-denied` handler does instead. The tutorial's sends the
reader to `/login`:

```clojure
(deftest settings-sends-a-signed-out-reader-to-login
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:rf.route/navigate {:to :app/settings}])
    (is (= :app/login @(rf/subscribe [:rf.route/id])))
    (is (nil? @(rf/subscribe [:rf/pending-navigation])))

    (rf/dispatch-sync [:auth/sign-in {:name "Ada"}])
    (rf/dispatch-sync [:rf.route/navigate {:to :app/settings}])
    (is (= :app/settings @(rf/subscribe [:rf.route/id])))))
```

`dispatch-sync` also runs the events the handler dispatches, so the redirect to login
has happened by the first assertion. The second navigation is an ordinary new one,
and with a user present the guard allows it.

A spy registered under `:rf.route/entry-denied` doesn't work here: the app registers
that id too, and `make-frame` refuses one id registered by two namespaces with
`:rf.error/image-duplicate-id`. If your handler stores the denied `:destination`, as
the [sign-in recipe](how-to/require-sign-in-on-a-route.md) does, read it from app-db.
It leaves out an empty `:params` and `:query` and a `nil` `:fragment`, so a refused
`/settings` stores `{:to :app/settings}`.

A frame interceptor that guards navigations, as in
[Require sign-in on a route](how-to/require-sign-in-on-a-route.md#a-policy-that-is-not-about-routes),
is tested like [any other interceptor](../core/interceptors.md#testing-an-interceptor).

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `@(rf/subscribe [:rf.route/id])` is still the old route after a navigate | The request was rejected (a bad request map raises `:rf.error/navigate-bad-request`; a URL that cannot be built — a missing path param, or with `re-frame.schemas` loaded a value the schema rejects — raises `:rf.error/schema-validation-failure`), or a `:can-enter` guard refused it | Fix the request, or for a guarded route assert on the denial |
| `make-frame` throws `:rf.error/image-duplicate-id` | The test registers an id the app already registers, such as `:rf.route/entry-denied` | Assert on what the app's handler does, or register the test's handler under an id of its own |
| A spy registered in the test is never called | It was registered inside `with-new-frame`, after the frame was made | Register it before `make-frame` |
| A link-click or Back/Forward test sees the `:restore` scroll default | The dispatch has no `:rf.route/cause`, so it counts as `:initial` | Pass the cause, as in [Simulating a link click or Back/Forward](#simulating-a-link-click-or-backforward) |
| Handlers registered in one test are visible in the next | The reset fixture is missing | Add `ts/make-reset-runtime-fixture`; it rolls back registrations made during each test |
| There is no browser URL to assert on | Test frames are not `:url-bound?`, so nothing writes the address bar | Assert on the route subs, or on `route-url` of the expected address |

## Advanced

### A cold boot that restores the session

If the app fetches the signed-in user at boot instead of reading a cached one, the
first URL is checked before the reply arrives, so a protected deep link is judged
with no user. [Require sign-in on a route](how-to/require-sign-in-on-a-route.md#deep-links-while-a-saved-session-is-loading)
shows the fix. To test it, use a `:url-bound?` frame whose `:url-strategy` `:decode`
reports the protected URL, the app's real `:initial-events`, and a managed-HTTP stub
that records the request and does not answer until the test says so. A stub that
answers at once, or a test that navigates to a public page first, cannot see the
problem. `realworld-cold-boot-deep-link-race` in
`implementation/adapters/reagent/test/re_frame/realworld_cljs_test.cljs` is a worked
example.
