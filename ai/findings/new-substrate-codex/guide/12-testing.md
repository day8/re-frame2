# Testing

## Test below React when React is not the subject

Events, subscription functions, machines, resource transitions, and pure helpers should use re-frame2's existing headless tests. The UI substrate does not make React a prerequisite for state logic.

Use component/DOM tests for:

- template/conditional output;
- DOM event extraction;
- effects and foreign component lifecycle;
- frame/context integration;
- accessibility behavior;
- hydration;
- ViewCell update/disposal behavior.

## Event handler test

Test a registered event transition with explicit input/state using the normal re-frame2 harness. Do not click a button merely to test pure update logic.

```clojure
(deftest increment-transition
  (rf/with-new-frame
    [f (rf/make-frame
         {:initial-events [[:rf/set-db {:counter/value 1}]]})]
    (rf/dispatch-sync [::increment] {:frame f})
    (is (= {:counter/value 2}
           (rf/app-db-value f)))))
```

This uses the current headless frame harness and no React. For a handler whose transition function is deliberately public and pure, call that function directly instead.

## Subscription test

```clojure
(deftest visible-orders-projection
  (is (= [2]
         (rf/compute-sub [::visible-order-ids] test-db))))
```

Use current explicit-frame/snapshot computation APIs. Test memo/cascade integration separately only when that integration is the subject.

## JVM view test

On JVM a `defview` is a plain function producing the canonical render tree. Bind a test frame and call it with a logical props map:

```clojure
(deftest counter-view-intent
  (rf/with-new-frame
    [f (rf/make-frame
         {:id :test/counter
          :initial-events [[:rf/set-db {:counter/value 3}]]})]
    (let [tree (counter {:label "Count"})]
      (is (= :button.counter (first tree)))
      (is (= [::increment] (get-in tree [1 :on-click])))
      (is (some #{"Count: "} tree)))))
```

The key point is that UI structure and event intent are data on JVM. Do not call the generated React component function directly in CLJS.

Prefer semantic tree queries over brittle full-tree equality when unrelated attrs/source markers could evolve.

## DOM component test

```clojure
(deftest click-updates-counter
  (let [host (.createElement js/document "div")
        root (ui/create-root host)]
    (try
      (ui/flush!
        #(ui/render! root counter-app {}
           {:frame {:id :test/counter
                    :initial-events [[::initialize]]}}))

      (let [button (.querySelector host "button")]
        (ui/flush! #(.click button))
        (is (= "1" (.-textContent (.querySelector host "[aria-live]")))))
      (finally
        (ui/unmount! root)
        (rf/destroy-frame! :test/counter)))))
```

`ui/flush!` wraps React `act` and is test-only. Use the repository's DOM/testing-library conventions rather than raw selectors when available.

## Test event extraction

For input events, a DOM test verifies the browser/React boundary:

```clojure
(set! (.-value input) "mike@example.com")
(ui/flush!
  #(.dispatchEvent input (js/Event. "input" #js {:bubbles true})))

(is (= "mike@example.com"
       (rf/compute-sub [::email]
         (rf/frame-state-value :test/form))))
```

Keep the pure handler transition test too. The DOM test covers currentTarget extraction and dispatch/frame wiring; the pure test covers state logic.

## Conditional dependency test

Render a view with a branch off, inspect the test/tool dependency projection, turn it on, flush, and assert attach after commit:

```clojure
(defn active-queries [render-key]
  (into #{}
    (keep #(when (:owned? %) (:query %)))
    (ui.tool/view-dependencies render-key)))

(is (= #{[::summary id]}
       (active-queries render-key)))

(ui/flush!
  #(rf/dispatch-sync [::expanded id true]
     {:frame :test/frame}))

(is (= #{[::summary id] [::history id]}
       (active-queries render-key)))
```

The test namespace requires `[re-frame.ui.tool :as ui.tool]`. This is a read-only tooling projection, not private ViewCell storage.

Then turn it off/unmount and assert the cache owner/ref count releases synchronously. This is a substrate integration test; application tests usually assert visible behavior instead of internal ownership.

## Frame routing test

Mount the same view under two frames with different state. Click each and assert only its frame changes. Include a nested frame swap and a portal case for library conformance.

Application reusable-view tests can use one non-default frame to catch accidental assumptions.

## Resource lease test

For a lease-owning view:

1. render disabled branch; assert no owner/work;
2. enable and commit; assert one owner/ensure;
3. rerender same descriptor; assert no churn;
4. change descriptor; assert old release/new ensure under the correct site owner;
5. unmount; assert release and polling/GC eligibility behavior;
6. repeat under Strict Mode setup-cleanup-setup.
7. hide/reveal under React Activity; assert zero owners while hidden, one per site after reveal, and preserved local state.

Use resource tool/test projection APIs, not raw runtime-db paths.

## Effect cleanup test

Wrap the foreign API with spies/fakes:

```clojure
(is (= 1 @attached))
(rerender-with-new-dep!)
(is (= 1 @detached))
(is (= 2 @attached))
(ui/unmount! root)
(is (= 2 @detached))
```

Test the exact object/listener passed to cleanup and that async replies after unmount are ignored/aborted by the owning subsystem.

## SSR parity test

For each representative `.cljc` view:

1. create equal server/client frame facts;
2. render JVM HTML/tree;
3. embed hydration payload/template digest;
4. hydrate in a DOM environment;
5. assert no recoverable mismatch callback;
6. assert DOM and event behavior after commit;
7. unmount/destroy both frames.

Corpus cases should include:

- text escaping and dangerous HTML policy;
- boolean/void/SVG attrs;
- fragments and keyed lists;
- forms/select/textarea/checked;
- client-only fallback;
- resource loaded/loading/error/refresh states;
- nested frames and portals where supported;
- source annotations in matching debug modes.

For client-only sites, assert the fallback is the initial hydrated DOM with no recoverable error, then flush the single root phase update and assert the client content replaces it.

## Hot reload test

The compiler/runtime conformance suite should simulate re-registration:

- same Hook signature: local state and render key survive; new markup/sub body appears;
- changed signature: cleanup/remount occurs and token changes;
- removed conditional sub/lease/event site releases correctly;
- subscription body replacement cannot leave a cell reading an old disposed node.

Also hide a mounted tree with React Activity, update state while hidden, and reveal it. The same instance/local state should return, while subscription/resource ownership disconnects to zero and reconnects without duplicates.

Application tests generally do not need HMR assertions.

## Abandoned render test

This is a mandatory library gate. Suspend/interrupt thousands of first mounts before commit, then assert:

- zero subscription ref-count growth;
- zero resource owners;
- zero mounted debug instances;
- zero event callback entries reachable globally;
- no dirty cells.

Run a second variant on an already committed component and verify the visible dependency/handler set remains the old committed set until a later render commits.

## Production bundle test

Build fixtures with advanced optimization and scan reachable output for forbidden runtime/debug families. See [Production performance](10-performance.md).

This test prevents an innocent debugging or compatibility import from silently reintroducing the interpreter, source paths, or server renderer.

## Avoid brittle tests

Do not assert:

- private ViewCell array layout;
- React Hook call implementation details beyond the public one-cell invariant;
- generated JS variable names;
- monotonic instance token values across test/process runs;
- exact debug timing;
- full DOM snapshots for small semantic behavior;
- implementation-specific query site hashes.

Do assert:

- visible semantics and accessibility;
- event intent/frame routing;
- committed dependency/resource ownership;
- cleanup/no leaks;
- causal debug categories;
- SSR equality;
- production absence.

## Cleanup discipline

Every test that creates a root/frame/registration/foreign listener cleans it in `finally` or a fixture. Reset warn-once/debug caches through canonical adapter reset hooks. Shared test state is especially dangerous when testing ref counts and HMR.

## Testing pyramid

```text
many    pure events/subscriptions/helpers
some    JVM view/tree intent tests
some    focused DOM component/frame/effect tests
few     full resource/SSR/hydration/HMR scenarios
gates   concurrency leak + production bundle/performance suites
```

The substrate is designed so excellent debugging and React correctness do not force every application test through a browser.
