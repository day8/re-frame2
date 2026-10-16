# Testing SSR

Server tests are ordinary JVM tests. Your event handlers, subscriptions and views
run on the JVM against a frame, which your
[handler](../core/testing/event-handlers.md),
[subscription](../core/testing/subscriptions.md) and
[pipeline-run](../core/testing/pipeline-runs.md) tests already exercise. This page
covers the part SSR adds: the rendered string, the Ring response, the payload,
hydration, and the error projector.

The tests below exercise the tutorial's app: `app.core` as it stands at the end of
the [tutorial](tutorial.md), with `:articles/seed`, the `:articles/slice`
subscription, the `:app/root` view and an `:app/server-init` that seeds
`sample-articles` up to the request's `limit` and sets a `Cache-Control` header.

## Render a view to a string

`render-to-string` is a pure function from hiccup to an HTML string. Make a frame,
seed it through `:initial-events`, call the root view inside the frame's scope, and
assert on the markup:

```clojure
(ns app.ssr-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring :as ssr.ring]
            [re-frame.test-support :as ts]
            [app.core]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter       ssr/adapter   ;; the server-side substrate
                                  :ambient-frame nil}))        ;; each test makes its own frame

(deftest the-root-view-renders-the-articles
  (rf/with-new-frame [f (rf/make-frame
                          {:initial-events [[:articles/seed [{:id "1" :title "Hello, server"}]]]})]
    (let [html (ssr/render-to-string ((rf/view :app/root)) {})]   ;; the view runs under f
      (is (str/includes? html "Hello, server")))))
```

`:initial-events` is the key `ssr-handler` uses to boot each per-request frame, so
the test frame boots the way a request's frame does.

When the assertion is about structure rather than the serialised string, walk the
hiccup as in [Test a view](../core/testing/views.md). Use `render-to-string` when
the string itself matters: markup a crawler reads, attribute serialisation, the
head.

## Call the Ring handler

`ssr-handler` returns a plain Ring handler, so an end-to-end server test builds one
with the tutorial's `app.server` options and calls it with a request map, with no
Jetty and no port:

```clojure
(defn boot-events [request]                        ;; app.server's :initial-events fn
  [[:app/server-init {:limit (app.core/request-limit request)}]])

(def handler
  (ssr.ring/ssr-handler {:initial-events boot-events
                         :root-view      (fn [] ((rf/view :app/root)))
                         :payload        [:articles]}))

(def request {:request-method :get :uri "/" :query-params {"limit" "1"}})

(deftest the-server-answers
  (let [response (handler request)]
    (is (= 200 (:status response)))
    (is (= "public, max-age=60" (get-in response [:headers "Cache-Control"])))
    (is (str/includes? (:body response) "Hello, server"))
    (is (not (str/includes? (:body response) "Hydration, verified")))))   ;; limit 1
```

The request map reaches `boot-events` as given. In production `wrap-params`
supplies `:query-params`; here the test writes it directly.

### Read the payload

The body carries the payload as EDN inside
`<script id="__rf_payload" type="application/edn">`. Read it back and the
allowlist becomes an assertion on data:

```clojure
(defn read-payload [body]
  (some->> body
           (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>")
           second
           edn/read-string))

(deftest only-the-allowlist-ships
  (let [payload (read-payload (:body (handler request)))]
    (is (= #{:articles} (set (keys (:rf/app-db payload)))))))  ;; :articles/limit stays on the server
```

The payload escaper rewrites `<` inside strings as `\u003c`, which the EDN reader
decodes, so the round trip is exact.

### Redirects and cookies

The `:rf.server/*` effects become an ordinary Ring response. A redirect answers
with its status, a `Location` header and an empty body. Each cookie becomes one
`Set-Cookie` header: a string for one cookie, a vector of strings for several.

```clojure
(deftest a-moved-page-redirects
  (rf/reg-event :test/moved
    {:platforms #{:server}}
    (fn [_ _]
      {:fx [[:rf.server/set-cookie {:name "flash" :value "moved" :path "/"}]
            [:rf.server/redirect   {:location "/articles" :status 301}]]}))
  (let [response ((ssr.ring/ssr-handler {:initial-events [[:test/moved]]
                                         :root-view      (fn [] ((rf/view :app/root)))
                                         :payload        [:articles]})
                  {:request-method :get :uri "/old-articles"})]
    (is (= 301 (:status response)))
    (is (= "/articles" (get-in response [:headers "Location"])))
    (is (str/starts-with? (get-in response [:headers "Set-Cookie"]) "flash=moved"))
    (is (= "" (:body response)))))
```

The reset fixture rolls back the registration made inside the test.
[Control the response](response.md) lists every effect and its arguments.

### Keep the network out

`:fx-overrides` on the handler is passed to every per-request frame, so it stubs an
effect for the whole server render. Redirect `:rf.http/managed` to a function and
the test sees each request the render fires, without sending it:

```clojure
(deftest the-server-render-sends-no-requests
  (let [sent    (atom [])
        handler (ssr.ring/ssr-handler
                  {:initial-events boot-events
                   :root-view      (fn [] ((rf/view :app/root)))
                   :payload        [:articles]
                   :fx-overrides   {:rf.http/managed (fn [_frame-ctx args] (swap! sent conj args))}})]
    (handler request)
    (is (empty? @sent))))
```

An override value is a function `(fn [frame-ctx args] …)` or another registered fx
id, as in
[Redirect any effect](../core/testing/pipeline-runs.md#redirect-any-effect-fx-overrides).
The handler does not wait for a fetch started from `:initial-events`; data the
first render needs belongs in a route resource declared `:blocking? true`, which
it does wait for.

## Check hydration

Neither check below is complete hydration verification. The browser check sees
the recoveries React makes on a real page; the JVM check sees whether the payload
rebuilds the server's page. An attribute-only divergence escapes both
([What is not reported](concepts.md#what-is-not-reported)), so read React's
development console warning as well.

### In the browser: watch React's recoveries

In a development or CI browser run, pass the root an `:on-recoverable-error` that
collects what React reports while it adopts the server's markup:

```clojure
(defonce hydration-errors (atom []))

(let [payload (ssr/hydrate! {:frame :rf/default})]
  (reagent-adapter/render!
    app-root
    [rf/frame-provider {:frame :rf/default} [(rf/view :app/root)]]
    (js/document.getElementById "app")
    {:hydrate?             (some? payload)
     :on-recoverable-error (fn [e _] (swap! hydration-errors conj e))}))
```

Once the hydration commit has landed, assert `(empty? @hydration-errors)`: a
browser test reads the atom, or the CI page fails its run on the first entry. The
root still emits its `:rf.ssr/hydration-mismatch` report before calling your
function, so the trace and the always-on record are unchanged.

The callback alone cannot prove the attributes agree, because React calls it only
for divergences it recovers from. In development, also watch `console.error` for
React's attribute warning, whose message says the attributes "didn't match":

```clojure
(defonce attribute-warnings (atom []))

(let [console-error js/console.error]
  (set! js/console.error
        (fn [& args]
          (when (str/includes? (str (first args)) "didn't match")
            (swap! attribute-warnings conj (first args)))
          (.apply console-error js/console (to-array args)))))
```

Production builds of React do not log the warning, so this part of the check
needs a development build.

### On the JVM: replay the payload

`hydrate!` also runs on the JVM when you pass the payload explicitly. Feed it the
payload the handler shipped, render the root view against the hydrated frame, and
compare the markup with what the server put inside `<div id="app">`. If the payload
is not enough to reproduce the server's render (the root reads a key the allowlist
left out, or reads the clock), the two strings differ and the test fails.

```clojure
(defn app-element-html [html]                     ;; the markup inside <div id="app">
  (second (re-find #"(?s)<div id=\"app\">(.*)</div><script id=\"__rf_payload\"" html)))

(deftest the-payload-rebuilds-the-page
  (let [body    (:body (handler request))
        payload (read-payload body)]
    (rf/make-frame {:id :app :platform :client})
    (is (= payload (ssr/hydrate! {:frame :app :payload payload})))
    (is (= (app-element-html body)
           (rf/with-frame :app (ssr/render-to-string [(rf/view :app/root)]))))))
```

This checks payload reconstruction and deterministic server output: both renders
run on the JVM, with the JVM's emitter, so it cannot establish that the browser
renders the same markup. That is the browser check's job.

## Test the boot guards and the error projector

A missing `:payload` policy throws when the handler is constructed, not on the
first request. Thrown framework errors carry their id in `ex-data`, so
[assert on the id rather than the message](../core/errors.md#the-errors-that-throw-not-trace):

```clojure
(deftest payload-policy-is-mandatory
  (is (= :rf.error/ssr-missing-payload-policy
         (try (ssr.ring/ssr-handler {:initial-events [[:app/server-init]]  ;; every required opt but :payload
                                     :root-view      (fn [] ((rf/view :app/root)))})
              (catch Exception e (:rf.error/id (ex-data e)))))))
```

The error projector and the head function are pure functions.
`rf/reg-error-projector` and `rf/reg-head` return the id they registered, not the
function, so give the function a name and test that. A projector test passes a
trace-event map and asserts the public shape, or `nil` for an error it leaves to
the default projector; a head test passes `(db, route)` and asserts the head model
(`:title`, the `og:` meta rows). Neither needs a frame:

```clojure
(defn public-error [trace-event]
  (when (and (= :rf.error/no-such-handler (:operation trace-event))
             (= :route (get-in trace-event [:tags :kind])))
    {:status 404 :code :not-found :message "We couldn't find that page." :retryable? false}))

(rf/reg-error-projector :app/public-error public-error)

(deftest a-route-miss-is-a-404
  (is (= 404 (:status (public-error {:operation :rf.error/no-such-handler
                                     :op-type   :error
                                     :tags      {:kind :route}}))))
  (is (nil? (public-error {:operation :rf.error/no-such-handler   ;; an unregistered event:
                           :op-type   :error                     ;; the default projector
                           :tags      {:kind :event}}))))        ;; answers it with a 500
```

## Platform gating needs no test of its own

A JVM test drain runs as a server-side drain. When a handler under test returns a
`#{:client}` effect, such as a `localStorage` write, the resolver skips it and
emits a `:rf.fx/skipped-on-platform` trace, as it will in a server render. So your
existing pipeline-run tests already show your handlers are safe to run on the
server: a handler that would crash a server render crashes the JVM test first.

## What stays in the browser

The JVM replay renders both sides with the JVM's emitter, so it cannot see anything
that differs between the JVM and the browser. Keep a browser run, with the
[browser check](#in-the-browser-watch-reacts-recoveries), for:

- **Adopting the DOM**: the adapter's `render!` with `{:hydrate? true}` reconciling
  against the server markup, and React reporting what it recovers from.
- **Cross-runtime drift**: a view whose `#?(:clj …)` and `#?(:cljs …)` branches
  render differently, or host-dependent output such as number and date formatting.

The [tutorial's Step 5](tutorial.md#step-5--break-it-on-purpose) trips a
[hydration mismatch](concepts.md#when-the-renders-disagree) on purpose.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| The JVM replay passes but the browser reports a mismatch | The JVM and the browser render the view differently; the replay compares two JVM renders | Find the `#?(:clj …)` / `#?(:cljs …)` or host-formatting difference, and keep the [browser check](#in-the-browser-watch-reacts-recoveries) in CI |
| The rendered body lacks data that a fetch loads | The fetch was started from `:initial-events`, and the handler does not wait for it | Declare the data as a route resource with `:blocking? true`, or stub the fetch with `:fx-overrides` |
| Constructing the handler throws `:rf.error/ssr-missing-payload-policy` | No `:payload` option | Name the app-db keys that may ship, or `:rf.ssr.payload/whole-app-db` |

## Advanced

### The Node renderer

If you [render on Node](concepts.md#render-on-node), the renderer is one handler
option holding a function, so most of it tests on the JVM without a second process.

- **Renderer selection.** `:renderer` takes any
  `(fn [{:keys [frame-id request opts]}] → {:body-html …})`. Pass a
  stub that returns a fixed string, and assert that the JVM's half of the page (the
  `<head>`, `__rf_payload`, the shell, the status and the cookies) is assembled
  around it correctly.
- **Construction validation.** `re-frame.ssr.ring.node/renderer` validates its
  options at construction, so a malformed endpoint, entry, build id or timeout
  throws `:rf.error/ssr-node-renderer-opt-invalid` at boot. Assert on the id as in
  `payload-policy-is-mandatory`.

The JVM-to-Node call itself is covered by re-frame2's own test suite, which runs the
real sidecar against a fixture render module through the success, refusal and
deadline paths. Your suite is better spent on the stub.
