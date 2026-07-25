# State

**Where does a value live?** Shared app truth, parent props, or (rarely) a reusable
field protocol — without a second reactive system.

There is no ratom and no hook-shaped cell in an ordinary Freehand view. Product
state stays in re-frame; host ephemera stay behind explicit host boundaries.

| Question | Answer |
|---|---|
| Shared application state? | `(v/sub [:query …])` |
| Given by my parent? | props (one map) |
| Reusable multi-event protocol? | optional library controller + `:control` (not day one) |

> **Day one is `sub` + props. Controllers are packaging, not a requirement.**

## Shared state: `sub`

```clojure
(v/defview order-summary [{:keys [order-id]}]
  (let [order (v/sub [:orders/by-id order-id])
        total (v/sub [:orders/total order-id])]
    [:div
     [:h3 (:title order)]
     [:strong (str "$" total)]]))
```

A few habits that keep this healthy:

- `(v/sub …)` returns the **current value** and re-renders this boundary when that
  value changes. The mechanism is on
  [Reactivity](reactivity-and-ownership.md).  
- Parametric queries work. When `order-id` changes, Freehand reconciles the old
  and new targets carefully so you do not briefly see the wrong order’s data.  
- Conditional reads are legal. A collapsed panel that reads nothing depends on
  nothing.  
- Keep real business calculation in `rf/reg-sub`. Views should do presentation
  math only.  
- Outside a view render, use `rf/subscribe-once`, not `v/sub`.

### Prefer props-only leaves when you can

Reusable presentation often reads cleaner when the **page** (or a thin boundary)
does the subscriptions and the leaf only receives data:

```clojure
(v/defview app-root [_]
  [app-page {:model (v/sub [:app/view-model])}])

(v/defview app-page [{:keys [model]}]
  [workspace {:model model}])   ; no sub below if the model is complete
```

Put scoped `sub` reads where locality and invalidation pay off. Do not thread the
entire app-db through props just to satisfy a purity slogan.

## Props

Every internal Freehand view receives **one props map**.

Trailing children arrive as the reserved `:children` vector. `:key` chooses
sibling identity and is stripped before your function sees props. You must not put
`:children` inside the props map yourself as a normal field.

```clojure
(v/defview panel [{:keys [title children]}]
  [:section.panel
   [:h2 title]
   children])

[panel {:key panel-id :title "Details"}
 [details {:id panel-id}]]
```

Internal props are compared with re-frame value equality for memoisation. Mutable
host objects do not belong in ordinary props; put them at an explicit host
boundary.

Props schemas are optional for ordinary application views. They become mandatory
by policy for shipped library/catalogue surfaces and for generative parity claims.
See [Compilation — props schemas](compilation.md#props-schemas).

## Interaction state — the A / B / C ladder

This is the section most people need when they first ask “where does my text field
live?”

**Freehand does not require semantic controllers.** Most forms never use them.

| Rung | Pattern | Where the draft lives | When to use it |
|---|---|---|---|
| **A** | Live controlled input | Domain path in app-db | Each keystroke *is* the value |
| **B** | Your draft, then commit | **Your** app-db keys | App-specific commit-on-blur / Escape |
| **C** | Library semantic controller | Library map keyed by `:control` | Same hard protocol on many screens |

**A and B are ordinary Freehand plus re-frame.** **C is optional packaging** for
libraries that share a hard multi-event protocol.

### Rung A — live domain field

```clojure
[:input {:value    (v/sub [:form/email])
         :on-input [:form/set-email ::v/value]}]
```

Every keystroke updates the real email (or filter string, or whatever the domain
is). Simple. Often enough.

### Rung B — draft, then commit, still no controller

```clojure
[:input {:value       (v/sub [:form/email-draft])
         :on-input    [:form/email-drafted ::v/value]
         :on-blur     [:form/email-committed]
         :on-key-down [:form/email-key ::v/key]}]
```

Here the jobs split on purpose:

- **input** updates the draft  
- **blur** commits  
- **`:form/email-key`** branches on the key it was handed: Enter commits, Escape
  cancels, anything else does nothing  

The branch lives in the handler rather than the view because that is where it can
also see application state — see
[Events](events-and-handlers.md#keyboard-branching).

You own the app-db keys and the cleanup. Freehand does not invent a second store
for you, and unmount does not magically clear your draft.

## Day-one checklist

Stop when you can:

- read shared state with `(v/sub …)` inside a view  
- pass one props map between views  
- wire a live field (A) or an app-owned draft (B) without a controller  
- keep DOM nodes and third-party instances off app-db  

## If something feels wrong

| Symptom | Fix |
|---|---|
| Draft vanishes on unmount / route leave | you expected lifecycle cleanup — clear in route/owner events |
| “I need view-local state for this toggle” | it is a re-frame fact plus an event, or a prop the parent owns |
| Mid-edit wiped by async load | seed-merge untouched keys only |
| Two writers on one draft | one owner path; controllers need unique `:control` addresses |

---

## Rung C — library controller (optional)

Use this only when many screens need the same multi-step protocol and you do not
want every team to reimplement stale blur, cancel races, and same-value reject.

It is **not** a Freehand built-in widget. A library (or your design system)
implements something like `buffered-field` as a normal `v/defview` plus library
events. Call sites look roughly like this:

```clojure
[buffered-field
 {:control   [:invoice invoice-id :amount]
  :value     (v/sub [:invoice/amount invoice-id])
  :reset-key (v/sub [:invoice/amount-revision invoice-id])
  :on-commit [:invoice/amount-committed invoice-id]}]
```

| Piece | Meaning |
|---|---|
| Storage | ordinary frame app-db under a **library-chosen** root (no magic Freehand path) |
| Identity | kind + your `:control` address (plain EDN you choose) |
| **`:reset-key`** | **required** prop — external baseline/session generation; no weaker hidden mode |
| Session | created on **first real edit** (not mere focus); cleared on **commit or cancel** |
| Unmount | does **not** clear the controller record — a form/route owner should |

### What Freehand does not provide

| Absent | Use instead |
|---|---|
| A view-local reactive cell | re-frame facts (A/B) or a library controller (C) |
| Tree-derived writable addresses | your domain ids or an explicit `:control` |
| Mount/unmount as domain cleanup | route / resource / machine ownership |
| A built-in `buffered-field`, or a reserved app-db root for controllers | library vocabulary if you need C — Freehand fixes the identity model and no storage path |

## Where state lives — quick map

| Kind of fact | Who owns it | Identity | How it goes away |
|---|---|---|---|
| Domain data | re-frame events/subs | domain ids | domain / route / workflow |
| Draft that *is* product state | re-frame | domain path | domain events |
| Reusable multi-event protocol | library controller | kind + `:control` | commit, cancel, or owner clear |
| DOM node, observer, third-party instance | host boundary | occurrence / behavior instance | host disconnect |
| Enter/exit presentation | `v/presence` | keyed child | timeout / re-entry machine |

## Multi-field forms

When several fields share draft, validation, and submit, use a **form-slice**
shape (draft, baseline, touched, errors, submit-attempted?) and seed-merge rules
so late loads do not clobber keystrokes — not only single-field wiring.
