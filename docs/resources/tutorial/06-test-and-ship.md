# Part 6: test it, ship it

This part tests Conduit's auth flow and view wiring on the JVM, then cuts the production bundle and explains what ships. Cache-specific tests — reads, invalidation, request counts — are in [Testing resources](../testing.md).

Test pure handlers directly. For a pipeline, drive real dispatches and replace
the effects that read storage or use the network with controlled replies. Assert
the state and view that the application itself reads.

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

Part 3's storage reply is another pure handler. Supply its classified map
payload, including the current generation, then inspect the request it describes:

```clojure
(deftest saved-token-starts-session-verification
  (let [handler (:handler-fn (rf/handler-meta
                              {:source :store :kind :event :id :auth/session-read}))
        result (handler {:db {:auth-generation 1 :auth {:user nil :token nil}}}
                        [:auth/session-read {:generation 1 :token "jwt-fixture"}])]
    (is (= "jwt-fixture" (get-in result [:db :auth :token])))
    (is (= [:auth/restore 1] (get-in result [:fx 0 1 :request-id])))
    (is (= {:method :get :url (str api/api-base "/user")}
           (get-in result [:fx 0 1 :request])))))
```

The last assertion checks a description of a request. Calling the handler sends
nothing, so this test needs no network stub. The credential is in the classified
event payload, never a recordable coeffect.

Pin the stale-reply guard the same way. These replies belong to an earlier
generation and must produce no effects against the current session:

```clojure
(deftest old-session-replies-cannot-change-the-current-session
  (let [cofx {:db {:auth-generation 4
                   :auth {:user {:username "bea"} :token "new-token"}}}
        old-reply {:status :ok :value {:user {:username "ada" :token "old-token"}}
                   :correlation {:request-id [:auth/restore 3]}}]
    (doseq [id [:auth/session-restored :auth/session-expired
                :auth.login-form/submit-success :auth.login-form/submit-failed]]
      (let [handler (:handler-fn (rf/handler-meta {:source :store :kind :event :id id}))]
        (is (= {} (handler cofx [id old-reply])))))
    (let [logout (:handler-fn (rf/handler-meta
                               {:source :store :kind :event :id :auth/logout}))]
      (is (= {} (logout cofx [:auth/logout {:generation 3}]))))))
```

For non-secret external facts such as a clock or fresh id, a handler declares
`:rf.cofx/requires`; a unit test supplies those keys in the coeffects map.
[Testing handlers](../../core/testing/event-handlers.md) covers that separate case.

## 3. Test the pipeline run: one dispatch, end to end

Boot crosses two effect boundaries: storage returns a saved token, then HTTP
verifies it. Use an effect override for each. The HTTP reply helper preserves
the request's correlation id, which the generation guard reads:

```clojure
(defn reply-with [reply]
  (fn [{:keys [frame]} {:keys [request-id on-success on-failure]}]
    (let [target (if (= :ok (:status reply)) on-success on-failure)]
      (rf/dispatch (conj target (assoc reply :correlation {:request-id request-id}))
                   {:frame frame}))))

(deftest cold-boot-with-saved-token-restores-the-user
  (rf/with-new-frame
    [f (rf/make-frame
         {:preset :test
          :fx-overrides
          {:auth.session/load
           (fn [{:keys [frame]} {:keys [generation]}]
             (rf/dispatch [:auth/session-read {:generation generation :token "jwt-fixture"}]
                          {:frame frame}))
           :rf.http/managed
           (reply-with {:status :ok
                        :value {:user {:username "ada" :email "ada@example.com"
                                        :token "jwt-fixture"}}})}
          :initial-events [[:auth/initialise]]})]
    (is (= "ada" (get-in (rf/app-db-value f) [:auth :user :username])))
    (is (true? (rf/compute-sub [:conduit/signed-in?] (rf/app-db-value f))))))
```

`with-new-frame` destroys the frame even if an assertion fails. The frame's
`:initial-events` drains the storage reply and our synchronous HTTP reply before
returning. A real HTTP request remains asynchronous.

The standard `with-request-stubs` helper is enough for tests that branch on
`:status` and `:value`. Its [minimal replies](../../api/re-frame.http.md#testing-without-a-network)
omit transport correlation, so this test uses an override to supply the additional
fact the session guard needs. These overrides test the event pipeline; they do
not test HTTP decoding, retry or transport cancellation.

The unhappy path uses the same helper with a failure reply:

```clojure
(deftest wrong-password-shows-the-servers-words
  (rf/with-new-frame [f (rf/make-frame {:preset :test})]
    (rf/dispatch-sync [:auth.login-form/initialise])
    (rf/dispatch-sync [:auth.login-form/edit-field :email "ada@example.com"])
    (rf/dispatch-sync [:auth.login-form/edit-password {:value "wrong"}])
    (rf/dispatch-sync
      [:auth.login-form/submit]
      {:fx-overrides
       {:rf.http/managed
        (reply-with {:status :error
                     :error {:kind :rf.http/http-4xx :status 422
                             :body "{\"errors\":{\"email or password\":[\"is invalid\"]}}"}})}})
    (is (= :error (get-in (rf/app-db-value f) [:auth :login-form :status])))
    (is (= ["email or password is invalid"]
           (rf/compute-sub [:auth.login-form/form-errors] (rf/app-db-value f))))))
```

`compute-sub` evaluates the real subscription headlessly. The failure body also
exercises `failure->form-errors`' JVM JSON decoder.
[Test a pipeline run](../../core/testing/pipeline-runs.md) explains effect overrides
and their scope. Client-only persistence skips on the JVM; assert the app-db
session here, and test the storage adapter separately when needed.

## 4. Test a subscription: compute it against a db

A subscription is a pure derivation — app-db value in, derived value out — so `compute-sub` tests it the same way. Here is Part 3's `:auth.login-form/can-submit?`:

```clojure
(deftest can-submit-once-the-draft-is-filled
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:auth.login-form/initialise])      ;; seed the empty form
    (rf/dispatch-sync [:auth.login-form/edit-field :email    "ada@example.com"])
    (rf/dispatch-sync [:auth.login-form/edit-password {:value "hunter2"}])
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
            [conduit.resources]                          ;; registers the populate target
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

!!! warning "Gotcha — a wrong testid fails loud, not soft"

    `find-by-testid` returns `nil` when nothing carries that id, so `invoke-handler` on the result throws `:rf.error/invoke-handler-bad-node`; invoking a node without that handler throws `:rf.error/invoke-handler-missing`. (`text-content` of `nil` is `""`, so a bad testid there shows up as a string mismatch.)

!!! note "Two kinds of view test"

    Walk the hiccup (above) when you care about structure or handlers. Use `ssr/render-to-string` when you care about the rendered markup — "is the `<button>` disabled?" — also on the JVM with no DOM. Only tests that need real DOM listeners or scrolling need a CLJS runtime.

## 6. Run the suite

```bash
clojure -M:test
# Ran 7 tests containing 16 assertions.
# 0 failures, 0 errors.
```

After dependency resolution and JVM startup, the tests run without a network service. **Try it:** break `:auth/session-read` — store the token under the wrong key — and run again. The pure test fails pointing at the exact map entry.

## 7. Ship it: the release build

Now cut the production bundle — same build id you've been running with `watch`:

```bash
npx shadow-cljs release app
```

`release` compiles with `:advanced` optimizations and sets `goog.DEBUG` to `false` — a compile-time constant from the Closure compiler. re-frame2's diagnostics sit behind `goog.DEBUG` checks, so in a release build the compiler removes them as dead code; that's what the guide means by [elide](../../core/glossary.md#elide). **What's gone from the file you just built:**

- **The schema checks that are advice about your own code** — the `reg-app-schemas` paths from Part 3, a plain event `:schema`. They compile out completely, which is why they cost nothing to write. (Not every schema check goes — see below.)
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
