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
- Drive state with real events: `(ui.test/dispatch! frame [:cart/add 42])` — shipped
  today: real dispatch plus a drain to fixed point — then re-render and
  assert the button now reads "Remove" *(a re-render that reads `sub` sites rides S2's
  snapshot path)*. Loading and error states are just app-db values
  you install or events you dispatch. Stubbing a sub is the explicit option:
  `(ui.test/render [view] {:frame frame :sub-overrides {[:cart/locked?] true}})`.
- **Two Tier-1 ground rules.** The events/subs your view touches must be `.cljc` (they run
  on the JVM here — the standard re-frame discipline anyway). And Tier 1 renders the
  *structural subset*: `local` shows its initial value (calling its setter is a typed
  error pointing at Tier 3), effects don't run, refs are absent — state transitions and
  host behavior are Tier-3 subjects by design.
- These run in your JVM watch loop in milliseconds and never flake on timing. They're
  also exactly how the library tests itself, so the shapes are first-class.
- *(Stage note: the Tier-1 core — `render`, `find`, `find-all`, `text`, `attrs`,
  `frame`, `dispatch!` — is Stage 1, shipped. `query` exists today only as the enforced
  Tier-3 counterpart — every call raises the typed tier error until mounted roots
  arrive. A Tier-1 render that crosses a `sub` site rides S2's snapshot path; the
  Tier-3 mount surface — `with-root`, `flush!`, `simulate!` — lands S2;
  `flush-presence!` lands S4.)*

## Tier 2 — dataflow tests (unchanged re-frame2)

Handlers, subs (`compute-sub`), machines, fx: test them as the pure functions they are.
The view tier above assumes this tier exists — don't test business logic through views.

## Tier 3 — mounted tests, when the DOM is the point

For focus, IME, foreign widgets — the things only a real mount exercises *(this mounted
surface — `with-root`, `flush!`, `simulate!` — lands S2)*:

```clojure
(ui.test/with-root [root [ui/frame-root {:id :t} [search-box]]]
  (ui.test/flush!)                            ; act + epoch drain + commit, deterministic
  (ui.test/simulate! root {:input "hats"})
  (ui.test/flush!)
  (is (= "hats" (.-value (ui.test/query root "input")))))
```

`ui.test/flush!` is **the** flush: it settles framework work, then commits React,
synchronously. There is no second flush idiom, no `setTimeout` in tests, no "wait for the
next tick" folklore. Mount teardown between cases is total — the substrate can be reset
with zero retained instances (that's a library CI gate, so you can rely on it).

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

The library's own CI pins, per release: ownership correctness under concurrent React
(abandoned renders retain nothing; StrictMode settles to one owner; Activity hide/reveal
releases and reacquires), JVM/browser emitter parity (generative, from your props
schemas), dev/prod behavioral equivalence, bundle absence rosters, and the performance
gates. Your tests get to assume the substrate; they only need to cover your app.

## Rules of thumb

- A test that needs `flush!` twice is telling you it's really two tests.
- If you're simulating a click to check a dispatch, assert the vector on the tree instead
  (tier 1) — simulate clicks only when the *DOM mechanics* are under test.
- If a view is hard to set up, it's reading too much — narrow its subs (that's a design
  smell surfacing in the test, which is the point of tests).
