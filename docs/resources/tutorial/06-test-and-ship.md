# Part 6: test it, ship it

Conduit works in the browser. This part proves it with tests that run on the JVM in milliseconds, with no browser, then cuts the production bundle and shows what ships and what doesn't. Cache-specific tests — reads, invalidation, request counts — are in [Testing resources](../testing.md).

The rule for every test here: **supply data, don't swap mechanisms.** You never patch `js/Date`, intercept `fetch`, or replace a module. You hand the runtime the facts a [handler](../../core/glossary.md#event-handler) declared it needs, then read the data it produced.

??? info "Coming from React Testing Library + MSW?"

    In a typical React app, testing a decision means rendering a component in JSDOM, testing a fetch means a mock service worker, and seeing the result means flushing with `act()`. Here an event handler is pure: what it needs arrives as values ([coeffects](../../core/glossary.md#coeffect)), and what it does leaves as data ([effects](../../core/glossary.md#effect)). A test supplies values and asserts on values, so there's nothing to mock.

## 1. Set up the JVM test runner

The JVM loads `.clj` and `.cljc` files, and a `.cljc` file is one source for both targets. That's why Parts 2–5 had you write `api.cljc`, `resources.cljc`, `scope.cljc`, `auth.cljc`, `mutations.cljc` and `views.cljc`: none of them names a browser API outside a `#?(:cljs …)` branch, so the JVM loads them as they are. (Renaming a file isn't enough — an unguarded `js/globalThis` stops the JVM compiler with `No such namespace: js`.) `core.cljs`, `articles.cljs` and `editor.cljs` stay ClojureScript; no test here loads them.

Add a `:test` alias beside `:dev` in `deps.edn`'s `:aliases`:

```clojure
;; deps.edn, inside :aliases
:test {:extra-paths ["test"]
       :extra-deps  {io.github.cognitect-labs/test-runner
                     {:git/tag "v0.5.1" :git/sha "dfb30dd"}}
       :main-opts   ["-m" "cognitect.test-runner"]}
```

Then the test namespace, with one fixture that resets the runtime around every test so nothing bleeds between them:

```clojure
;; test/conduit/auth_test.clj
(ns conduit.auth-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed]                        ;; the production HTTP fx
            [re-frame.http.test-support :as http-test-support]  ;; canned stubs — test-only
            [re-frame.substrate.plain-atom :as plain-atom] ;; the headless JVM substrate
            [re-frame.test-support :as ts]
            [conduit.api :as api]
            [conduit.auth]))                               ;; Parts 3 and 4's registrations load here

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil}))   ;; nil: our tests create their own frames
```

The fixture resets the runtime around each test and installs the headless adapter before it. Each test below makes its own frame with `with-new-frame`, which is why `:ambient-frame` is `nil`. [Set up the test runner](../../core/testing/index.md#set-up-the-test-runner) lists everything the fixture resets.

!!! warning "Gotcha — keep the stubs out of production"

    Require `re-frame.http.test-support` from test namespaces only — never from production or SSR code. That's what keeps the canned stubs out of what ships: they're absent from the production classpath on the JVM, and [elided](../../core/glossary.md#elide) under `:advanced` CLJS.

## 2. Test a handler: it's a function, so call it

An event handler is a pure function: coeffects and an event in, an [effect map](../../core/glossary.md#effect-map) out. So to test it, pull it out of the registry with `handler-meta`, call it, and check what comes back:

```clojure
(deftest edit-field-updates-the-draft
  (let [handler (:handler-fn (rf/handler-meta {:source :store :kind :event :id :auth.login-form/edit-field}))
        result  (handler {:db {:auth {:login-form {:draft {:email "" :password ""}}}}}
                         [:auth.login-form/edit-field :email "ada@example.com"])]
    (is (= "ada@example.com" (get-in result [:db :auth :login-form :draft :email])))))
```

No frame, no dispatch, no runtime.

Now a handler that needs something from the world: Part 3's boot handler, which reads the saved JWT through the recordable `:auth.session/token` [coeffect](../../core/glossary.md#coeffect) and declares that it needs it:

```clojure
;; src/conduit/auth.cljc — from Part 3 (doc string elided)
(rf/reg-cofx :auth.session/token
  {:recordable? true}
  (fn []
    #?(:cljs (some-> (.-localStorage js/globalThis) (.getItem "jwtToken")))))

(rf/reg-event :auth/initialise
  {:rf.cofx/requires [:auth.session/token]}
  (fn [{:keys [db auth.session/token]} _]
    (cond-> {:db        (assoc db :auth {:user nil :token token})
             :sensitive [[:auth :token]]}
      token (assoc :fx [[:rf.http/managed
                         {:request    {:method :get :url (str api/api-base "/user")}
                          :decode     :json
                          :on-success [:auth/session-restored]
                          :on-failure [:auth/session-expired]}]]))))
```

It tests the same way, with the declared fact in the input map. A handler receives `:db`, `:event`, and exactly the facts in `:rf.cofx/requires`, so the fixture is a literal:

```clojure
(deftest initialise-folds-the-token-and-asks-who-it-is
  (let [handler (:handler-fn (rf/handler-meta {:source :store :kind :event :id :auth/initialise}))
        result  (handler {:db                 {}
                          :auth.session/token "jwt-fixture"}  ;; the literal coeffects map
                         [:auth/initialise])]
    (is (= "jwt-fixture" (get-in result [:db :auth :token])))
    (is (= [[:auth :token]] (:sensitive result)))
    (is (= {:method :get :url (str api/api-base "/user")}
           (get-in result [:fx 0 1 :request])))))
```

The last assertion checks a *description* of the request: the handler sent nothing, so no network needed mocking ([effects are data](../../core/glossary.md#effects-are-data)).

The declaration doubles as a **fixture checklist**. Ask the registry what a handler must be fed:

```clojure
(:rf.cofx/requires (rf/handler-meta {:source :store :kind :event :id :auth/initialise}))
;; => [:auth.session/token]
```

Supply whatever appears there, in the literal map or on the dispatch (below). Nothing else is delivered, so nothing else can matter.

!!! warning "Gotcha — freezing the clock"

    Time is a declared fact too. A handler that stamps a timestamp declares `:rf.cofx/requires [:rf/time-ms]`, and a test supplies `{:rf/time-ms 1781078400123}` in the literal map. There's no `js/Date` to patch, because the handler never reads one.

## 3. Test the pipeline run: one dispatch, end to end

Part 3's boot restore is a *flow*: `:auth/initialise` fires a [managed HTTP](../glossary.md#managed-http) request, the reply re-enters as `:auth/session-restored`, and the user lands in app-db. Test it as one piece by driving a real dispatch through a real [frame](../../core/glossary.md#frame), redirecting only the points where it touches the outside world — the **edges**. The happy path, a cold boot that finds a saved token:

```clojure
(deftest cold-boot-with-saved-token-restores-the-user
  ;; :preset :test is one of make-frame's config keys — see the bullets below.
  (rf/with-new-frame [f (rf/make-frame {:preset :test})]
    (http-test-support/with-request-stubs
      {[:get (str api/api-base "/user")]              ;; the URL Part 3's restore requests
       {:reply {:ok {:user {:username "ada"
                            :email    "ada@example.com"
                            :token    "jwt-fixture"}}}}}
      (fn []
        (rf/dispatch-sync [:auth/initialise]
                          {:rf.cofx {:auth.session/token "jwt-fixture"}})))
    (is (= "ada" (get-in (rf/app-db-value f) [:auth :user :username])))
    (is (true?   (rf/compute-sub [:conduit/signed-in?] (rf/app-db-value f))))))
```

Four things do the work, each redirecting a value at a boundary:

- **`with-new-frame`** gives the test its own isolated frame — created for the body, destroyed on the way out, success or exception. `{:preset :test}` sets two test defaults. It answers `:rf.http/managed` with a canned success, so a request you forgot to stub never reaches the network. And it sets a **strict mint policy**: a handler that declares a supplier-backed [coeffect](../../core/glossary.md#coeffect) (a fresh id, say) and isn't *supplied* one raises `:rf.error/missing-required-cofx`, instead of generating a value that won't match production. `:rf/time-ms` is always stamped, so it never trips this.
- **`{:rf.cofx {…}}` on the dispatch** supplies the declared fact, overriding the registered supplier for this one dispatch — no re-registering, no `localStorage`. Under `{:preset :test}`, forgetting the key raises `:rf.error/missing-required-cofx` rather than falling through to a live read.
- **`with-request-stubs`** routes `:rf.http/managed` by method + URL for the thunk's extent and synthesizes a real reply envelope. The exact request data your handler produced arrives at the stub, and the reply re-enters through the same `:on-success` path a live response would.
- **`dispatch-sync` drains to fixed point.** The whole pipeline run settles before the call returns — the stubbed request, the reply event, the session write. The assertions on the next lines read fully-committed state. No `act()`, no awaiting, no sleeps, no flake.

The unhappy path — the one your users will actually hit — is the same shape with a failure reply:

```clojure
(deftest wrong-password-shows-the-servers-words
  (rf/with-new-frame [f (rf/make-frame {:preset :test})]
    (http-test-support/with-request-stubs
      {[:post (str api/api-base "/users/login")]
       {:reply {:failure {:kind   :rf.http/http-4xx
                          :status 422
                          :body   "{\"errors\":{\"email or password\":[\"is invalid\"]}}"}}}}
      (fn []
        (rf/dispatch-sync [:auth.login-form/initialise])
        (rf/dispatch-sync [:auth.login-form/edit-field :email "ada@example.com"])
        (rf/dispatch-sync [:auth.login-form/edit-field :password "wrong"])
        (rf/dispatch-sync [:auth.login-form/submit])))
    (is (= :error (get-in (rf/app-db-value f) [:auth :login-form :status])))
    (is (= ["email or password is invalid"]
           (rf/compute-sub [:auth.login-form/form-errors] (rf/app-db-value f))))))
```

`compute-sub` runs a subscription's derivation as a plain function against a state value, headlessly. The stub replied with Conduit's real 422 body, so the second assertion also covered `failure->form-errors`' `:clj` branch. Every flow in the slice tests this way: stub the edges, drive the dispatches, assert on settled state. ([Test a pipeline run](../../core/testing/pipeline-runs.md) covers each edge, including per-dispatch `:fx-overrides`.)

!!! warning "Gotcha — client-only effects skip on the server"

    An effect declared `:platforms #{:client}`, like Part 3's `localStorage` persist, skips on the JVM and leaves a trace note. That's expected: the assertion targets the session in app-db, not the host write.

## 4. Test a subscription: compute it against a db

A subscription is a pure derivation — app-db value in, derived value out — so `compute-sub` tests it the same way. Here is Part 3's `:auth.login-form/can-submit?`:

```clojure
(deftest can-submit-once-the-draft-is-filled
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:auth.login-form/initialise])      ;; seed the empty form
    (rf/dispatch-sync [:auth.login-form/edit-field :email    "ada@example.com"])
    (rf/dispatch-sync [:auth.login-form/edit-field :password "hunter2"])
    (is (= true (rf/compute-sub [:auth.login-form/can-submit?] (rf/app-db-value f))))))
```

Rather than hand-build an app-db map, the test dispatches the real events that build the state, so it keeps passing when the form's shape moves. (A literal map works for a trivial reader, but it goes stale when the real shape changes.) `can-submit?` reads through `:auth.login-form/slice`; `compute-sub` resolves that chain of inputs for you.

!!! warning "Gotcha — app-db subs vs runtime-db subs"

    A frame holds state in [two partitions](../../core/glossary.md#the-two-partitions): app-db (yours) and [runtime-db](../../core/glossary.md#runtime-db) (the framework's — resource entries, mutation instances, machine snapshots). `app-db-value` returns only app-db, so a sub such as `[:rf.mutation/status …]` finds nothing there. `frame-state-value` returns both partitions, and `compute-sub` reads whichever one each sub needs — so when in doubt, use `frame-state-value`. The view test below does.

## 5. Test the view, not just the state

State can be right while the screen is wrong: the view reads the wrong path, or wires `:on-click` into the wrong frame. You can catch both on the JVM, because a view-fn is a function and what it returns is [hiccup](../../core/glossary.md#hiccup) — data you can walk.

Give the node a stable handle first. The `testid` helper adds a `:data-testid` to an attrs map, and elides from production. Part 5's `favorite-button` grows one attribute:

```clojure
;; src/conduit/views.cljc — require [re-frame.test-helpers :as th], then tag the button:
[:button.btn.btn-outline-primary.btn-sm
 (th/testid "favorite-btn"
            {:type     "button"
             :class    (when favorited "active")
             :disabled (:pending? fav)
             :on-click #(dispatch [:ui/favorite slug favorited])})
 [:i.ion-heart] " " favoritesCount]
```

A second test namespace loads `conduit.views` on the JVM (it's `.cljc`) and walks the tree the view returns:

```clojure
;; test/conduit/views_test.clj
(ns conduit.views-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.http.test-support]                   ;; :preset :test's canned stub
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as ts]
            [conduit.views :refer [favorite-button]]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter :ambient-frame nil}))

(deftest favorite-button-shows-the-count-and-clicks-into-this-frame
  (rf/with-new-frame [f (rf/make-frame {:preset :test :rf.cofx/mint-policy :explicit-live})]
    (rf/dispatch-sync [:rf/set-db {:auth {:user {:username "ada"}}}])   ;; a user is signed in
    (let [article {:slug "x" :favorited false :favoritesCount 7}
          tree    (favorite-button {:article article})]                ;; call the view-fn directly
      ;; class-1 bug: does the button render the count it was handed?
      (is (= " 7" (th/text-content (th/find-by-testid tree "favorite-btn"))))
      ;; class-2 bug: invoking :on-click must dispatch into THIS frame —
      ;; the favorite write settles here only if the click landed here.
      (th/invoke-handler (th/find-by-testid tree "favorite-btn") :on-click)
      (is (ts/poll-until
            #(= :success (rf/compute-sub [:rf.mutation/status {:instance [:favorite "x"]}]
                                         (rf/frame-state-value f))))))))
```

Three helpers from `re-frame.test-helpers` walk the hiccup: `find-by-testid` finds the node carrying that `:data-testid` (expanding nested views on the way down), `text-content` collects the string leaves under it (the heart glyph contributes nothing, hence `" 7"`), and `invoke-handler` calls a wired handler such as `:on-click`. The click's `dispatch` is queued, so `ts/poll-until` waits (two seconds by default) for the write to settle; it settles `:success` because `:preset :test` answers `:rf.http/managed` with a canned success. Had the click gone to another frame, this frame's instance would stay `:idle` and the poll would time out. The frame also sets `:rf.cofx/mint-policy :explicit-live`, which opts back into generated values: a mutation mints a fresh cache generation, and the preset's strict policy won't invent one.

??? info "Coming from React Testing Library?"

    `:data-testid` is the same convention RTL leans on, and the helpers mirror its query/fire shape: `find-by-testid` ≈ `getByTestId`, `text-content` ≈ `textContent`, `invoke-handler` ≈ `fireEvent`. What's underneath differs entirely — RTL queries a rendered DOM that JSDOM had to build; these helpers walk the hiccup *data* the view returned. No DOM, no `render()`, no `act()` to flush.

!!! warning "Gotcha — a wrong testid fails loud, not soft"

    `find-by-testid` returns `nil` when nothing carries that id, so `invoke-handler` on the result throws `:rf.error/invoke-handler-bad-node`; invoking a node without that handler throws `:rf.error/invoke-handler-missing`. (`text-content` of `nil` is `""`, so a bad testid there shows up as a string mismatch.)

!!! note "Two kinds of view test"

    Walk the hiccup (above) when you care about structure or handlers. Use `ssr/render-to-string` when you care about the rendered markup — "is the `<button>` disabled?" — also on the JVM with no DOM. Only tests that need real DOM listeners or scrolling need a CLJS runtime.

## 6. Run the suite

```bash
clojure -M:test
# Ran 6 tests containing 11 assertions.
# 0 failures, 0 errors.
```

The run takes well under a second. **Try it:** break `:auth/initialise` — store the token under the wrong key — and run again. The pure test fails pointing at the exact map entry.

## 7. Ship it: the release build

Now cut the production bundle — same build id you've been running with `watch`:

```bash
npx shadow-cljs release app
```

`release` compiles with `:advanced` optimizations and sets `goog.DEBUG` to `false` — a compile-time constant from the Closure compiler. re-frame2's diagnostics sit behind `goog.DEBUG` checks, so in a release build the compiler removes them as dead code; that's what the guide means by [elide](../../core/glossary.md#elide). **What's gone from the file you just built:**

- **The schema checks that are advice about your own code** — the `reg-app-schema` paths from Part 3, a plain event `:schema`. They compile out completely, which is why they cost nothing to write. (Not every schema check goes — see below.)
- **The entire trace channel** — the epoch ledger you scrolled in [Xray](../../core/glossary.md#xray), the trace ring, every emit site. Open Xray against the release build and there's nothing to attach to.

**What survives — because it isn't diagnostics:**

- **Events, effect maps and the `:rf.cofx` facts** stamped on every dispatch. Recordable coeffects are part of how the app computes its state, so they always ship.
- **The schema checks the framework relies on to keep its own promises**, even when the schema is yours. `:boundary? true` on a handler that receives an HTTP response or websocket frame rejects a malformed payload before the handler runs, in production as in dev. A recordable coeffect's `:schema`, a declared route's shape and a managed-HTTP `:decode` also survive. ([Configure dev and production builds](../../core/how-to/configure-dev-and-prod.md) sorts every check.)
- **The error records.** One structured [error record](../../core/glossary.md#error-record) per production-reachable failure, so a handler exception reaches your error service with its frame and event id attached.

The error records need one piece of wiring before you deploy. Declare a sink in your frame's config and register its function:

```clojure
;; add to the frame's metadata:
{:observability {:errors [{:sink              :conduit.sinks/error-reporter
                           :rf.egress/profile :rf.egress/off-box-observability}]}}

(rf/register-observability-sink! :conduit.sinks/error-reporter
  (fn [record]
    ;; the record arrives already projected — secrets show up as :rf/redacted
    (ship-to-your-error-service! record)))
```

The runtime [redacts](../../core/glossary.md#project-egress) each record according to the frame's [classification](../../core/glossary.md#data-classification) before your sink sees it, so the sink does no redaction of its own. [Report errors in production](../../core/how-to/report-errors-in-production.md) covers choosing a backend and what the records carry.

!!! note "Pre-alpha"

    re-frame2 is pre-alpha and makes no backwards-compatibility promise yet; expect to track changes between releases.
