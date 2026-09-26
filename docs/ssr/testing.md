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
subscription, the `:app/root` view and a `:rf/server-init` that seeds
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
(def handler
  (ssr.ring/ssr-handler {:initial-events [[:rf/server-init]]
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

The request map reaches `:rf/server-init` as given. In production `wrap-params`
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
    (is (= #{:articles} (set (keys (:rf/app-db payload)))))   ;; :articles/limit stays on the server
    (is (string? (:rf/render-hash payload)))))
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
                  {:initial-events [[:rf/server-init]]
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

## Replay the hydration on the JVM

`hydrate!` also runs on the JVM when you pass the payload explicitly. Feed it the
payload the handler shipped, into a client frame in strict mode. If the payload is
not enough to reproduce the server's render (the root reads a key the allowlist
left out, or reads the clock), the client render hashes differently and `hydrate!`
throws.

```clojure
(deftest the-payload-rebuilds-the-page
  (let [payload (read-payload (:body (handler request)))]
    (rf/make-frame {:id :app :platform :client :ssr {:on-mismatch :hard-error}})
    (is (= payload (ssr/hydrate! {:frame          :app
                                  :payload        payload
                                  :render-tree-fn (fn [] ((rf/view :app/root)))})))
    (is (= "Hello, server" (get-in (rf/app-db-value :app) [:articles 0 :title])))))
```

The comparison needs the payload's `:rf/render-hash`; without one this test passes
without checking anything, which is why `only-the-allowlist-ships` asserts the hash
is a string. A hash ships only for a fn-form `:root-view` whose root view returns an
element, and it compares only the root's own markup
([what the hash covers](concepts.md#what-the-hash-covers)).

## Test the boot guards and the error projector

A missing `:payload` policy throws when the handler is constructed, not on the
first request. Thrown framework errors carry their id in `ex-data`, so
[assert on the id rather than the message](../core/errors.md#the-errors-that-throw-not-trace):

```clojure
(deftest payload-policy-is-mandatory
  (is (= :rf.error/ssr-missing-payload-policy
         (try (ssr.ring/ssr-handler {:initial-events [[:rf/server-init]]   ;; every required opt but :payload
                                     :root-view      (fn [] ((rf/view :app/root)))})
              (catch Exception e (:rf.error/id (ex-data e)))))))
```

The error projector and the head function are pure functions.
`rf/reg-error-projector` and `rf/reg-head` return the id they registered, not the
function, so give the function a name and test that. A projector test passes a
trace-event map and asserts the public shape; a head test passes `(db, route)` and
asserts the head model (`:title`, the `og:` meta rows). Neither needs a frame:

```clojure
(defn public-error [trace-event]
  (if (and (= :rf.error/no-such-handler (:operation trace-event))
           (= :route (get-in trace-event [:tags :kind])))
    {:status 404 :code :not-found :message "We couldn't find that page." :retryable? false}
    (ssr/default-error-projector-fn trace-event)))

(rf/reg-error-projector :app/public-error public-error)

(deftest a-route-miss-is-a-404
  (is (= 404 (:status (public-error {:operation :rf.error/no-such-handler
                                     :op-type   :error
                                     :tags      {:kind :route}}))))
  (is (= 500 (:status (public-error {:operation :rf.error/no-such-handler   ;; an unregistered event
                                     :op-type   :error                     ;; is a server defect
                                     :tags      {:kind :event}})))))
```

## Platform gating needs no test of its own

A JVM test drain runs as a server-side drain. When a handler under test returns a
`#{:client}` effect, such as a `localStorage` write, the resolver skips it and
emits a `:rf.fx/skipped-on-platform` trace, as it will in a server render. So your
existing pipeline-run tests already show your handlers are safe to run on the
server: a handler that would crash a server render crashes the JVM test first.

## What stays in the browser

The JVM replay renders both sides with the JVM's emitter, so it cannot see anything
that differs between the JVM and the browser. Keep a browser run for:

- **Adopting the DOM**: the adapter's `render!` with `{:hydrate? true}` reconciling
  against the server markup, and React's own hydration check for UIx and Fresco
  roots.
- **Cross-runtime drift**: a view whose `#?(:clj …)` and `#?(:cljs …)` branches
  render differently, or host-dependent output such as number and date formatting.

Turn on strict mode, `{:ssr {:on-mismatch :hard-error}}`, on the client frame in
development and CI browser runs, so a
[hydration mismatch](concepts.md#when-the-renders-disagree) fails the build instead
of logging a warning. The [tutorial's Step 5](tutorial.md#step-5--break-it-on-purpose)
trips one on purpose.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| The hydration replay passes even after you break the view | The payload has no `:rf/render-hash`, so nothing was compared | Use the fn form `:root-view (fn [] ((rf/view :app/root)))`, with a root view that returns an element; assert `(string? (:rf/render-hash payload))` ([what the hash covers](concepts.md#what-the-hash-covers)) |
| The rendered body lacks data that a fetch loads | The fetch was started from `:initial-events`, and the handler does not wait for it | Declare the data as a route resource with `:blocking? true`, or stub the fetch with `:fx-overrides` |
| Constructing the handler throws `:rf.error/ssr-missing-payload-policy` | No `:payload` option | Name the app-db keys that may ship, or `:rf.ssr.payload/whole-app-db` |

## Advanced

### Streaming handlers

`stream-handler`'s response `:body` is a `java.io.InputStream` that a writer thread
fills. `slurp` blocks until the writer closes it, so the whole streamed page arrives
as one string, and the test asserts the order the chunks came in. This uses the
root view with a `:region.comments` boundary from [Streaming](streaming.md):

```clojure
(deftest the-comments-stream-in-after-the-shell
  (let [handler  (ssr.ring/stream-handler {:initial-events [[:rf/server-init]]
                                           :root-view      (fn [] ((rf/view :app/root)))
                                           :payload        [:articles :comments]})
        response (handler request)
        body     (slurp (:body response))
        at       #(str/index-of body %)]
    (is (= 200 (:status response)))
    (is (< (at "data-rf2-suspense-fallback=\"1\"")    ;; the shell, with the fallback in place
           (at "data-rf2-suspense-resolved=\"1\"")    ;; then the resolved region
           (at "__rf_payload")))                       ;; then the final payload
    (is (some? (read-payload body)))))                 ;; which reads like any other
```

A redirect set during the drain still comes back as a plain bodiless response,
before any chunk is written.

### The Node renderer

If you [render on Node](concepts.md#render-on-node), the renderer is one handler
option holding a function, so most of it tests on the JVM without a second process.

- **Renderer selection.** `:renderer` takes any
  `(fn [{:keys [frame-id request opts]}] → {:body-html … :render-hash …})`. Pass a
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
