# Testing your views — the view-tree axis

Load when the question is "does the screen show the right thing?" or "does the button dispatch the right event?". The **runtime-state** axis — fixtures, `dispatch-sync`, `compute-sub`, machine snapshots, fx stubs, gates — is [`testing.md`](testing.md).

`re-frame.test-support` covers the **runtime-state** axis (events / fx / subs / machines). The sibling namespace `re-frame.test-helpers` covers the **view-tree** axis — call the view-fn, walk the returned hiccup by `:data-testid`, assert on rendered content. Reach for it when "does the screen show the right thing?" or "does the button dispatch the right event?" is the question. A test doing both `:require`s both.

```clojure
(:require [re-frame.core         :as rf]
          [re-frame.test-helpers :as h])
```

## The single-frame e2e shape — compose the recipe

This is the dominant shape for an app-developer e2e view test: one frame, one install hook, one root view, and an assertion that the rendered text matches after dispatching. There is **no bespoke fixture macro** — compose it from primitives that already exist and are adopted at scale:

1. **`ts/make-reset-runtime-fixture`** (`re-frame.test-support`) — an `:adapter` plus an `:init-fn` (your app's setup fn that registers the events / subs / views) seats the ambient `:rf/default` frame and rolls the registrar back between tests. Install it once with `(use-fixtures :each …)`.
2. **The `re-frame.test-helpers` hiccup walkers** (`h/find-by-testid` + `h/text-content`) — call the root view fn directly and walk the returned tree; `h/invoke-handler` drives a click.
3. **`ts/poll-until`** (`re-frame.test-support`) — for the async case (a queued `dispatch`, an HTTP reply, a machine `:after`) whose settled outcome is observable in the re-rendered view.

```clojure
(:require [re-frame.core         :as rf]
          [re-frame.test-support :as ts]
          [re-frame.test-helpers :as h]
          [my-app.counter        :as counter])   ;; your app: counter/setup! registers, counter/main is the root view

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter counter/test-adapter   ;; your substrate adapter
                                  :init-fn counter/setup!}))       ;; reg-event / reg-sub / reg-view

;; Synchronous — dispatch-sync drains before the assertion, so walk the
;; re-rendered view directly.
(deftest counter-e2e
  (rf/dispatch-sync [:counter/inc])
  (is (= "1" (h/text-content (h/find-by-testid (counter/main) "n")))))
```

`make-reset-runtime-fixture` installs the `:adapter`, seats `:rf/default` as the ambient frame for each test (so `dispatch-sync` / `subscribe` resolve to it without a `{:frame …}` opt), and runs `:init-fn` inside that scope. Its registrations land in the global registrar and roll back around each test. `h/testid` is the **authoring** helper — standardise the `:data-testid` fragment at the view call site (`[:span (h/testid "n") @(rf/subscribe [:counter/n])]`); `find-by-testid` locates it, `text-content` reads its text, `invoke-handler` fires an attached handler.

For an async settle, poll the re-rendered view with `ts/poll-until` until it matches:

```clojure
;; Async — a queued dispatch (HTTP reply, scheduled event, machine :after)
;; settles past dispatch-sync; poll the re-rendered view. (JVM shown; on CLJS
;; poll-until returns a Promise — use the cljs.test/async form below.)
(deftest status-eventually-ready
  (rf/dispatch [:cart/fetch])                              ;; plain dispatch — queues
  (is (ts/poll-until
        #(= "ready" (h/text-content (h/find-by-testid (cart-view) "status")))
        {:timeout-ms 5000 :label "status ready"})))
```

On CLJS that `is` would assert the **Promise object**, which is truthy the moment it
is created — green before the predicate settles, and green again when it later
rejects. Await it instead:

```clojure
;; CLJS — compose the Promise under cljs.test/async; assert inside .then.
(deftest status-eventually-ready-cljs
  (async done
    (-> (ts/poll-until
          #(= "ready" (h/text-content (h/find-by-testid (cart-view) "status")))
          {:timeout-ms 5000 :label "status ready"})
        (.then (fn [_]
                 (is (= "ready" (h/text-content
                                  (h/find-by-testid (cart-view) "status"))))
                 (done)))
        (.catch (fn [e]
                  (is false (str "poll-until timed out: " (.-message e)))
                  (done))))))
```

`ts/poll-until` opts: `:timeout-ms` (default 2000), `:interval-ms` (default 5), `:label` (timeout-message tag). **Per-platform shape**: **JVM** synchronous — returns the truthy value, throws `ex-info` (`:rf.error/id :rf.error/poll-until-timeout`) on timeout; **CLJS** returns a `js/Promise` — resolves with the truthy value, rejects on timeout, compose with `cljs.test/async`. For sync runs, a `find-by-testid` / `text-content` walk after `dispatch-sync` suffices — reach for `poll-until` only when the run is genuinely async. Not a substitute for timer-semantics sleeps (grace-period elapse, throttle/debounce window).

## Lower-level walk helpers — the hiccup-walk pattern

When a fixture didn't stash the tree, or you need the `:on-click`-fires-the-right-event assertion rather than a text check, walk the hiccup directly. The view-fn returns hiccup; that's just data. Dispatch via `dispatch-sync` into the test frame, call the view-fn, then walk the returned tree by `:data-testid`:

```clojure
(deftest counter-view-shows-and-fires
  (rf/with-new-frame [f (rf/make-frame {:initial-events [[:counter/init]]})]
    (let [tree (counter-view {:n 0})
          btn  (h/find-by-testid tree "counter-inc")]
      (h/invoke-handler btn :on-click nil)              ;; fire the handler as the DOM would
      (is (= 1 (:n (rf/app-db-value f)))))))
```

- `find-by-testid` / `find-all-by-testid` — locate node(s) by `:data-testid`.
- `text-content` — the rendered text under a node.
- `invoke-handler` — call an attr handler (`:on-click`, `:on-change`, …) with an event arg, as the DOM would.
- `testid` — the **authoring** helper that standardises the attrs fragment at view call sites; use it whenever you write a new view that wants a test handle:

```clojure
(rf/reg-view counter-inc-button []
  [:button (h/testid "counter-inc" {:on-click #(dispatch [:counter/inc])}) "+"])
```

`dispatch` is the local `rf/reg-view` injects — that lexical binding is what the deferred `:on-click` closes over. A bare `rf/dispatch` there runs after the render scope has unwound and raises `:rf.error/no-frame-context` (EP-0002 — no `:rf/default` floor).

**Why walk the view, not just assert state?** State-only assertions (`(is (= 2 (:n db)))`) catch handler bugs but miss two classes the hiccup-walk catches — *state-correct, view-broken* (handler updated db, view reads the wrong path / forgets a branch) and *wrong-frame dispatch* (`:on-click` dispatches into the wrong frame; host-frame state never changes). Both surface on JVM and Node-CLJS with no browser.

**Single-frame discipline.** Application view tests use ONE frame — the host frame. Views, events, subs, and asserts all reference the same frame. A multi-frame harness — a test where an **observer / tool** frame watches another frame's app-db or trace stream (as a dev tool would) — is the only shape that legitimately spans frames, and it is never a regular application view. Full walkthrough at [`docs/core/testing/pipeline-runs.md`](../../../../docs/core/testing/pipeline-runs.md).

---

*Derived from `implementation/core/src/re_frame/test_helpers.cljc` (view-tree assertion surface — hiccup walkers + `testid`) @ main. Re-verify after test-helpers surface changes.*
