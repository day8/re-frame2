# D005 — What should `sub` do outside a view render?

Status: **Ruled**
Ruling: **`v/sub` is render-only; `rf/subscribe-once` is the named one-shot
read outside render.**

Horizon: **Immediate**

## Decision

The decision is whether `v/sub` is a render-only operation or also performs an
ownership-free, one-shot read when no Freehand view is rendering.

This is a small surface decision with an outsized effect on mistakes. It decides
whether the same expression means “declare a reactive dependency owned by this
view” in one context and “read once, with no owner” in another.

## Why this is a real problem

Inside a declared view, the meaning is settled:

```clojure
(v/defview basket-total [_]
  [:output (v/sub [:basket/total])])
```

The read is captured by that mounted occurrence. The selected commit publishes
the dependency, changes invalidate the occurrence, and disconnect releases it.

Outside a render, two uses look deceptively similar:

```clojure
;; Deliberate exploratory read at the REPL.
(v/sub [:basket/total])

;; Accidental read in a callback created during render.
(v/event [_]
  [:basket/save (v/sub [:basket/total])])
```

The first is convenient if it performs a one-shot read. The second is almost
certainly a bug: the programmer may believe the callback retained a reactive read,
but it has no render owner and can observe a different frame or an unexpectedly
late value. The dual meaning also makes generated code and static explanations
less reliable.

## Settled constraints

- A reactive `sub` read belongs only to an active declared-view render.
- Capture is same-thread only. Conveying the binding to another thread must not
  create ownership there.
- One-shot reads must not install a watch, retain a subscription handle, or appear
  as a committed view dependency.
- Frame resolution must remain explicit and deterministic; there must be no
  process-global “current app” fallback.
- Callbacks that need a decision based on changing application state should emit
  an intent and let its re-frame handler consult committed state.
- Interpreted and compiled Freehand must have the same rule.

## Options

### A. Keep `sub` dual-mode

Inside render, `v/sub` captures a dependency. Outside render, it resolves, probes,
returns the current value, and immediately releases any temporary resource.

Consequences:

- REPL and simple `.cljc` tests are pleasantly terse.
- It preserves the donor runtime's shipped `sub-read` split.
- Existing exploratory code is less likely to need changes during absorption.
- The same form has different ownership semantics based on ambient dynamic state.
- A `sub` that escapes into an event callback silently becomes non-reactive instead
  of identifying a phase error at its source.
- Tools and AI must explain context before they can explain the expression.

### B. Make `v/sub` render-only; use an explicitly named one-shot operation

Outside an active declared render, `v/sub` raises a typed error that identifies the
query and suggests the recovery. Exploratory and test code uses the existing
one-shot operation, provisionally written here as:

```clojure
(rf/subscribe-once frame [:basket/total])
```

The final call shape should follow the re-frame2 frame API already in use; the
important part is the distinct name and explicit lack of ownership.

Consequences:

- Accidental callback reads fail where they are authored.
- “`sub` means a reactive read owned by this render” becomes a complete rule.
- REPL and test code pays a few extra characters for an uncommon but legitimate
  operation.
- Migration can mechanically rewrite intentional out-of-render uses.
- The one-shot function needs first-class documentation and equally good override
  and frame behavior, or users will reach for internal APIs.

### C. Overload `v/sub` with an explicit frame outside render

For example, `(v/sub frame query)` would be one-shot while `(v/sub query)` would be
render-only.

Consequences:

- The arity communicates some of the distinction.
- It keeps related reads under one name.
- It still combines ownership and probing in one operation and makes call-site
  recognition, documentation, and diagnostics less crisp.
- It spends public API complexity to save one small, explicit function.

## Recommendation

Choose **B: make `v/sub` render-only and retain a separately named one-shot read**.

The render-only law is more valuable than the REPL abbreviation. It turns a common
stale-closure mistake into a precise error, gives people and AI one sentence that
fully explains `v/sub`, and preserves the consult-committed-state event law. The
one-shot operation is still available for REPL exploration, tests, tooling, and
non-reactive server-side calculations; it simply says what it does.

Absorption should reuse the donor's proven resolution, override, probing, and
stabilization machinery underneath both operations. It should not carry forward
the donor's ambient semantic overload merely because the implementation already
has it.

## Consequences to verify

- The error must distinguish “outside render” from “wrong thread during render”
  and from “no frame context.”
- Structural tests that render a view continue to use `v/sub`; test setup and REPL
  probes use the explicit one-shot operation.
- `v/event`, `v/handler`, timers, promises, and host callbacks must all trigger the
  same out-of-render diagnostic if they call `v/sub`.
- A migration census must identify intentional donor-era out-of-render calls before
  the old artifact is deleted.

## Dependencies and what this unlocks

This decision depends on the final public name and frame argument convention of the
one-shot read. It unlocks a precise phase checker, better callback diagnostics,
unambiguous documentation, and a simpler compiler rule: every legal `v/sub` has a
view owner and a lexical or runtime read site.

## Design sources

- [Codex design, §4 “Subscription law”](../codex-design.md#subscription-law)
  recommends a loud failure outside an active declared render.
- [Fable design, §2.2 “The reactor — what `sub` means”](../fable-design.md#22-the-reactor--what-sub-means)
  recommends the donor's dual-mode one-shot behavior and records this as operator
  question Q5.
