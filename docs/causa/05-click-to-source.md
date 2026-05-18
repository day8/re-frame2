# 5. Click-to-source

The hero feature.

In practice, this is the surface you reach for when a tester drops a screenshot on your desk and says "the wrong number is showing here." You don't grep. You don't binary-search the view tree. You point Causa at the rendered element, read the coord off the DOM node, and you're inside the function that produced it.

## Every element knows where it came from

Open any re-frame2 app in dev mode and inspect any rendered element:

![A counter button with data-rf2-source-coord highlighted](../images/causa/05-dom-attribute.png)

```html
<button data-rf2-source-coord="counter.core:counter:48:5" ...>+</button>
```

Four colon-separated segments: `<ns>:<sym>:<line>:<col>`. Public, parseable, forward-compatible. Tools split on the colon and recover the four pieces directly. Every `reg-view`-rendered DOM element has one. **Click any pixel in your app, walk back to the line of code that put it there.**

This isn't a Causa feature. It's a framework feature. Causa is just one of several tools that consume the attribute — `re-frame-pair2` reads it through nREPL to scope a `dom/source-at` query; Playwright specs in test runs use it to assert "this rendered element came from view `:cart/total`"; future tools nobody's built yet can do the same.

## The Causa gesture

Inside Causa, the gesture is one click. Three places it shows up:

1. **In the Event-detail panel's *Renders* list** — every view that re-rendered in the cascade has a click-target.
2. **In any panel that names a registered id** — Subscriptions, Effects, Machines, Flows, Routes. Click the id, jump to its registration.
3. **From the host page directly** — switch Causa into *pick mode* (`Ctrl+Shift+S`), hover any DOM element, and the panel highlights the coord. Click to commit the gesture.

The jump uses your editor's URL handler — VS Code (`vscode://`), IntelliJ (`idea://`), Emacs (`emacs://`), or a generic `file://` fallback. Configure the handler in Causa's settings panel; the default tries VS Code first.

## The contract — `data-rf2-source-coord`

The four colon-separated segments are `<ns>:<sym>:<line>:<col>`:

- `<ns>` — the registration's namespace (`counter.core`)
- `<sym>` — the **registered handler-id** (the symbol passed to `reg-view`, here `counter`). Not a file path.
- `<line>` — source line at `reg-view` macro-expansion time
- `<col>` — source column

The format is a **public, parseable contract**. Tools split on the colon and recover the four pieces directly.

To recover the file path too, follow the parsed handler-id back to the registration metadata via `:rf/source-coord-meta`:

```clojure
(:rf/source-coord-meta (rf/handler-meta :view :counter.core/counter))
;; → {:ns "counter.core", :file "counter/core.cljs", :line 48, :column 5}
```

The DOM attribute is the cheap-on-the-wire form; the registration metadata is the rich form.

The annotation is **dev-only** — gated on the universal `re-frame.interop/debug-enabled?`. Production builds elide via DCE; the rendered HTML in production carries no `data-rf2-source-coord` bytes.

Documented exemption: components whose outermost return is a React Fragment, a `:>` host-component head, or another non-DOM root are exempt. Pair tools fall back to `(rf/handler-meta :view id)` for those nodes.

## Beyond views: state machines

The same idea generalises to state machines. `reg-machine` is a macro that walks its literal spec form at expansion time and attaches a flat coord index under `:rf.machine/source-coords`, keyed by spec-path tuples:

```clojure
(:rf.machine/source-coords (rf/machine-meta :auth/login))
;; {[:guards :form-valid?]                {:ns ... :line ... :column ... :file ...}
;;  [:actions :commit]                    {...}
;;  [:states :form :on :submit]           {...}}
```

A pair-tool or a state-diagram visualiser reads this index for two distinct gestures: **jump to definition** (a click on `:form-valid?` in the diagram reads `[:guards :form-valid?]`) and **jump to call site** (a click on a transition arrow reads `[:states :form :on :submit]`).

The framework commits to **the index shape and the keyword-reference rule**:

- **Definition-site stamping for keyword references.** A keyword reference (`{:guard :form-valid?}`) is stamped at its definition site (`[:guards :form-valid?]`), not at the call site — the call site is the keyword itself, which is identity-free.
- **Reference-site stamping for inline-fn literals.** An inline `(fn [...] ...)` is stamped where it appears.

The keyword-reference rule means call-site clicks on a keyword-named slot (`{:guard :form-valid?}`) fall back to the enclosing transition's coord, which IS stamped.

Like `data-rf2-source-coord`, the stamping is gated on debug-enabled and elides under production build flags.

## What re-frame2 does not ship

The framework commits to the attribute format and the index shape — both are parseable public contracts. The framework does **not** ship `dom-source-at` / `find-by-src` / `fire-click-at-src` helpers; those depend on host-specific DOM access that re-frame2 the framework doesn't assume.

Causa ships its own pick-mode helper, its own coord-to-editor handler, its own batch "highlight every node from this view" toggle. `re-frame-pair2` ships its own helpers over the same attribute. They could diverge in implementation; they can't diverge in contract — the attribute is the wire.

## Two recent landmarks

Click-to-source has been on the spec roadmap since before re-frame2 was named. Two recent shipments matter:

- **PR #1106 (rf2-g5q8d)** — open-in-editor wired through the panel's allowlist. The shipped tool *opens a file in your editor when you click* without prompting you for confirmation on every click. The allowlist is a security gate, not a UX gate; once configured, the gesture is friction-free.
- **The `data-rf2-source-coord` contract was rolled into the spec proper** — `<ns>:<sym>:<line>:<col>` is locked. Future tools that grew up after Causa can rely on it.

Causa's click-to-source is what closed the loop between "the framework knows where every line came from" and "the developer's first gesture in a debugging session is a *click*, not a grep."

## Walking the 3pm scenario on the shop testbed

The tutorial's [index page](index.md#a-scenario-before-the-tour) opened with a five-step debugging cascade for a wrong total. The shop testbed at [`tools/causa/testbeds/shop/`](https://github.com/day8/re-frame2/tree/main/tools/causa/testbeds/shop) is the runnable version — every step on that page maps to a click in the testbed.

The bug, in code:

```clojure
;; The display sub — chains through the WRONG upstream subtotal.
(rf/reg-sub :total-due
  :<- [:cart/subtotal-WRONG]                       ;; ← the bug
  :<- [:cart/tax-rate]
  :<- [:cart/shipping]
  (fn [[subtotal tax-rate shipping] _]
    (let [tax (long (* subtotal tax-rate))]
      (+ subtotal tax shipping))))

;; The WRONG subtotal reads :cart/snapshot ([:checkout :snapshot] on
;; cart-frame's app-db) — the snapshot the cart's :cart/send-to-checkout
;; handler froze. After Send-to-checkout, the snapshot drifts from the
;; live basket and the displayed total locks to the snapshot.
(rf/reg-sub :cart/subtotal-WRONG
  :<- [:cart/snapshot]                             ;; ← wrong slot
  ...)
```

The five clicks:

1. **Reproduce.** Open `http://127.0.0.1:8030/shop/`. Click `+ Apple` once. The cart visibly carries Apple ×1 ($1.50). The displayed total reads **$5.00** (the empty-snapshot edge: subtotal=0 + tax=0 + shipping=$5.00). The screenshot you'd send the dev. *Wrong number is showing — total doesn't reflect the cart.*

2. **The coord on the wire.** Right-click the total span, *Copy element*. The HTML carries `data-rf2-source-coord="shop.core:cart-panel:624:4"`. The coord routes you to the `cart-panel` reg-view in `tools/causa/testbeds/shop/core.cljs`. The view subscribes to `:total-due`.

3. **Views panel.** `Ctrl+Shift+C`, click *Views*, type `total-due`. Causa shows it as a node in the sub-graph. Its upstream edge points at `:cart/subtotal-WRONG`. The id is the symptom-name — production wouldn't name the wrong sub `-WRONG`, but the dependency edge itself would point at the wrong upstream regardless.

4. **The dependency edge.** Click the edge. `:cart/subtotal-WRONG` reads its input off `:cart/snapshot` (i.e. `[:checkout :snapshot]` on cart-frame's app-db). That slot is empty until *Send to checkout* runs — once it does (try it: click +Apple → Send to checkout → click +Apple again), the snapshot carries Apple ×1 forever while the live cart accumulates further items. The displayed total locks to the snapshot.

5. **App-DB diff.** Open the App-DB panel. The `[:cart :items]` and `[:checkout :snapshot]` slots hold *different* vectors after a *Send to checkout* click — the snapshot froze, the live cart has whatever the user added since. Diff confirms the projection drift.

The fix is a one-token edit: change `:<- [:cart/subtotal-WRONG]` to `:<- [:cart/subtotal-CORRECT]` in the `:total-due` reg-sub. Save. The hot-reload preserves app-db; the displayed total catches up to the live basket on the next paint. Issues ribbon clears.

You just walked the 3pm scenario in five minutes.

Next: [the schema-violation timeline](06-schema-timeline.md).
