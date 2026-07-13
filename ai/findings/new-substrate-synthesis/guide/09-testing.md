# 09 — Testing

Views here are pure functions whose interaction surface is data — so most UI testing needs
no DOM, no browser, and no flake. Work down this list; stop at the first tier that answers
your question.

## Tier 1 — headless view tests (your daily driver)

`ui.test/render` runs the real view against a real frame on the JVM — real subscriptions,
real registrations, no React:

```clojure
(deftest add-button-carries-intent
  (let [frame (ui.test/frame {:app-db {:cart #{} :catalog fixture-catalog}})
        tree  (ui.test/render [product-card {:product (product 42)}] {:frame frame})]
    (is (= [:cart/add 42] (-> tree (ui.test/find :button) ui.test/attrs :on-click)))
    (is (= "Add to cart"  (-> tree (ui.test/find :button) ui.test/text)))))
```

- Assert **structure and intent**, not pixels: the rendered tree is data, and because
  handlers are event vectors, "what does this button do" is an equality check — no click
  simulation, no event mocking.
- **Selectors are a small closed grammar, not CSS:**

```clojure
(ui.test/find tree :button)                 ; unqualified keyword — element tag
(ui.test/find tree :shop/product-card)      ; qualified keyword — a view id (or pass the Var)
(ui.test/find tree {:data-testid "save"})   ; attr map — every entry matches, by value
(ui.test/find-all tree :li)                 ; all matches, in document order
```

  View-id selectors match the view's boundary marker, so fragment-rooted (even
  nil-rooted) views are findable. Attr-map values compare by `rf=`, and handler slots
  hold event vectors as data — so `{:on-click [:cart/add 42]}` finds a button by its
  *intent*, which is the idiom this tier exists for. A predicate fn is the escape for
  anything the data forms can't say. `find` returns the node (itself queryable) or
  `nil` — a miss threads through to a clean `nil ≠ expected` failure.
- **Reads go through the projections** — `(ui.test/attrs node)` and
  `(ui.test/text node)`. Never keyword-look-up an attribute on a node: attrs and events
  live behind the projection, so `(:on-click node)` reads a node *field* that isn't
  there and silently misses.
- Drive state with real events: `(ui.test/dispatch! frame [:cart/add 42])` — real
  dispatch plus a drain to fixed point — then re-render and assert the button now reads
  "Remove". The whole loop — the dispatch, the drain to fixed point, and the re-rendered
  view's `sub` read of the moved app-db — runs on main today. Loading and error
  states are just app-db values you install or events you dispatch. Stubbing a sub is
  the explicit option:
  `(ui.test/render [view] {:frame frame :sub-overrides {[:cart/locked?] true}})`.
- `render` also takes a **literal root form** — the same literal top-region grammar
  `mount` accepts, deliberately tightened in one way: a test root mounts exactly *one*
  view, because root identity is that view's id. `mount` will take a multi-view form
  when you author a `:root-id`; `ui.test/render` won't — a two-view root fails at
  expansion with `:rf.ui.compile/bad-test-root`. Need a multi-view composition under
  test? Wrap it in one `defview` and render that.
  `(ui.test/render [ui/frame-root {:id :shop :initial-events [[:shop/boot]]} [app]] {})`
  runs the form's frame plans as preflight ENSURE against the test registrar, minting
  the frames it declares. With a plan-bearing root form, `{:frame …}`/`{:app-db …}` are
  rejected (the root form owns its frames — pass a bare view to control the frame), and
  `{:props …}` belongs to the bare-view form only.
- **Two Tier-1 ground rules.** The events/subs your view touches must be `.cljc` (they run
  on the JVM here — the standard re-frame discipline anyway). And Tier 1 renders the
  *structural subset*: `local` shows its initial value (calling its setter is a typed
  error pointing at Tier 3), effects don't run, refs are absent — state transitions and
  host behavior are Tier-3 subjects by design. *(The `local` and `effect` forms
  themselves land S3; the subset rule here is their ruled Tier-1 contract.)*
- These run in your JVM watch loop in milliseconds and never flake on timing. They're
  also exactly how the library tests itself, so the shapes are first-class.
- *(Stage note: the Tier-1 core — `render`, `find`, `find-all`, `text`, `attrs`,
  `frame` — shipped at Stage 1. `dispatch!`, Tier-1 `sub` snapshots, and the mounted
  Tier-3 `with-root` / native-CSS `query` / Promise-backed `flush!` surface shipped at
  Stage 2. `flush-presence!` lands at Stage 4.)*

## Tier 2 — dataflow tests (unchanged re-frame2)

Handlers, subs (`compute-sub`), machines, fx: test them as the pure functions they are.
The view tier above assumes this tier exists — don't test business logic through views.

## Tier 3 — mounted tests, when the DOM is the point

For focus, IME, foreign widgets — the things only a real mount exercises — use the
Stage-2 mounted surface, `with-root`, `query`, and `flush!`:

```clojure
(deftest search-commits-the-latest-query
  (async done
    (let [frame (ui.test/frame {:app-db {:query ""}})]
      (-> (ui.test/with-root
            [root [ui/frame-provider {:frame frame} [search-box]]]
            ;; with-root already awaited the initial mount. Put the write
            ;; inside flush!'s act boundary; the queued write side reaches
            ;; drain quiescence before the read/render fixed point.
            (-> (ui.test/flush!
                  #(ui.test/dispatch! frame [:search/set-query "hats"]))
                (.then (fn []
                         (is (= "hats"
                                (.-value (ui.test/query root "input"))))
                         :asserted))))
          (.then (fn [body-value]
                   (is (= :asserted body-value))
                   (done))
                 (fn [error]
                   (is false (str "mounted test rejected: " error))
                   (done)))))))
```

On CLJS, both `with-root` and `flush!` are Promise boundaries. `with-root` awaits the
initial mount, invokes and awaits its body, then awaits root/container teardown on
success or failure; it resolves to the body's value. `flush!` has zero- and thunk-arity
forms. Prefer the thunk form for a write: the thunk runs inside React 19 `act`, then the
framework and React alternate until both are quiescent. Always compose/await the
returned Promise. A bare returned Promise is not a portable `cljs.test` async contract;
use `async done` with explicit success and rejection callbacks as above.

There is no second flush idiom, no `setTimeout` settle, and no "wait for the next tick"
folklore. Starting another `with-root` or `flush!` before the prior Promise settles fails
loudly with `:rf.error/ui-test-overlapping-act`; teardown still reclaims every owner.
Mount teardown between cases is total — the substrate can be reset with zero retained
instances (that's a library CI gate, so you can rely on it). On the JVM, `flush!` has no
React boundary: it drains the headless registry synchronously and returns nil.
At S2, drive framework state programmatically with `ui.test/dispatch!` as above. Native DOM
mechanics that are already host-owned — focus, selection, and a foreign component's raw
callback — use ordinary platform APIs; there is no library gesture language to learn or
debug. Compiled event vectors remain inspectable as Tier-1 data, but dispatching a browser
`InputEvent` through one becomes live only with S3's committed-handler wiring.

(Presence transitions advance with `(ui.test/flush-presence!)` — no wall-clock sleeps.
*Lands S4, with presence.*)

## Story *(rides the migration wave — S6)*

Story variants ride the same foundations: variants assert on rendered data and app-db
(CLJS unit-test shape — the repo's ruled default), stub subscriptions through observation
targets (the stub is captured at render and honored at commit — a stubbed view can't
half-see reality, and Xray shows the override honestly as not-owning a real
subscription), enumerate a view's interaction surface as data, and reserve DOM plays for
genuinely DOM-shaped checks. Scenes mount by **view id** from the registry.

## What you can trust without testing it yourself

The library's own CI builds toward a pinned release set, each gate wiring in with the
stage that ships its feature: ownership correctness under concurrent React (abandoned
renders retain nothing; StrictMode settles to one owner; Activity hide/reveal releases
and reacquires), JVM/browser emitter parity (generative, from your props schemas),
dev/prod behavioural equivalence, bundle absence rosters, and the performance gates.
The S1/S2 slices — parity and the ownership fixtures — are wired today; the rest land
with S3–S6. Your tests get to assume the substrate; they only need to cover your app.

## Rules of thumb

- Await every mounted operation; prefer `(flush! #(write))` to a write followed by a
  separate zero-arity flush.
- If you're simulating a click to check a dispatch, assert the vector on the tree instead
  (tier 1) — simulate clicks only when the *DOM mechanics* are under test.
- If a view is hard to set up, it's reading too much — narrow its subs (that's a design
  smell surfacing in the test, which is the point of tests).
