# Interop and the closed grammar

`defview` does not *interpret* hiccup at runtime. It **lowers** the template at
build time — direct React construction in the browser, a versioned structural tree
on the JVM — which is what makes the memoisation, the elision, and the tooling
sound. The price is a **closed template grammar**: anything the compiler cannot see
through is a build failure, with a message that names the fix, rather than a silent
runtime surprise.

This page is the honest one. It catalogues what the compiler forbids, the explicit
doors for when you genuinely need runtime structure, how foreign React fits in, and
where this substrate is the wrong tool.

## The one-sentence rule

> If the compiler cannot prove the structure, the reactive sites, and the keys of a
> piece of markup **at compile time**, that spelling is illegal in a `defview` body.

"Prove" means: after normalising the control forms it understands — `let`, `if`,
`when`, `if-let`, `when-let`, `if-some`, `when-some`, `cond`, `case`, `for`, and
friends — every tag head, every `sub` site, and every list identity is finite
and visible. Ordinary branching and binding are *free*: both arms of an `if`, every
`cond` clause, the bound branch of an `if-let`, the body of a `for` all lower into the
AST, so you are never forced into one linear template.

## Template structure

| You cannot | Why | Do this instead |
|---|---|---|
| Dynamic tag heads — `[(if big? :h1 :h2) title]` | The emitter must know the element constructor at compile time | Write both branches, or vary attributes on a fixed tag |
| Markup-returning `map` — `(map row-view rows)` | Lazy seqs hide keys and reactive sites | `(for [r rows] [row-view {:key …}])`, or a child view |
| Unkeyed list items in a `for` | List identity is a proof obligation, not a warning | `{:key …}` on every list child — missing key is a **build failure** |
| Keywords in child position — `[:div :oops]` | Silent-text footguns | Strings, numbers, `nil`/`false`, or nested markup |
| An app prop named `:key` | `:key` is React's list-identity slot | Rename the prop |
| Children passed to a view that declares no `:children` | Child acceptance is opt-in and compile-checked | Declare the `:children` binding in that view |
| `#js` / hand-rolled camelCase on compiled DOM paths | One conversion table serves both emitters | Keyword props; let the compiler convert |
| Runtime-assembled root forms at `mount` / `render!` / `ui.test/render` | Root identity and frame plans are compile-time contracts | Literal root vectors; wrap multi-view compositions in one `defview` |

## Reactive sites must be finite

Every `(sub …)` is a compile-indexed site on the view's single
React bridge. A `sub` may sit in a branch — it is not a hook — but no site may
appear inside an unbounded loop. The fix is the same shape as
[State](state.md#when-you-get-it-wrong): extract a keyed child view that
subscribes for one row.

Expression positions — prop values, condition tests, `for` collections — hold
ordinary Clojure *values*, but their lexical *syntax* is audited so every reactive
call stays visible. That closes the door on arbitrary macros in those positions: an
unaudited macro could inject a `sub` the manifest never saw. The accepted set is
the transparent core forms (`or` `and` `when` `when-not` `cond` `->` `->>` `some->`
`some->>` `cond->` `cond->>`), the binder forms (`let` `fn` `loop` `letfn` `try`, and
the conditional binders `if-let` `when-let` `if-some` `when-some`), and ordinary
function calls; everything else is `:rf.ui.compile/unsupported-form`, and the fix is
always to move the computation into a plain function or another `defview`.

## Foreign React

Real apps embed React that someone else wrote. The doors are explicit and narrow:

```clojure
[:div (ui/raw badge-element)]              ; a React element a foreign lib handed you
[DatePicker {:selected date                ; a foreign component as a template head
             :on-change (ui/handler [v] (pick! v))}]
```

- **`ui/raw`** embeds an existing React element in child position. It is a
  boundary, not a template feature.
- **A foreign component** used as a head takes open props — JS values pass through
  untouched. Its callbacks pick a form from the
  [handler decision table](events-and-handlers.md#the-decision-table); a bare fn on
  a foreign callback prop is a compile error precisely because the compiler can't
  know the invoker's phase.
- **`ui/spread`** is the one runtime prop-map form. On a **DOM/custom element**
  (`[:div (ui/spread base attrs)]`) it merges two runtime maps through the same
  conversion rule table as the compiler. On a **foreign component** it is the
  wrapper idiom — accept a map, forward it — shown below. `ui/spread-safe` is its
  component-library sibling: it forwards a consumer's attr map onto an internal
  element while barring the structural/controlled keys (`:key` `:ref` `:value`
  `:checked` and owned handlers) in every build, so a library input keeps its
  controlled guarantee.
- **`ui/html`** is the one escaping bypass — visible at the call site, you vouch
  for the string.
- **React hooks at a genuine foreign boundary** come from `re-frame.ui.react`
  (`use-effect`, `use-context`, `use-layout-effect`, …) — thin wrappers, an
  interop/migration tier. Inside ordinary views you never reach for them: `sub`,
  `local`, `ref`, and `effect` are the component story (the DOM-node ref is the
  substrate-native `ui/ref`, not an interop wrapper). See the
  [`re-frame.ui.react` reference](../../api/re-frame.ui.react.md).

### Forwarding props onto a foreign component: `ui/spread`

A component call site normally takes a **literal props map** — an internal view
needs the literal keys for its per-slot memo comparator, so a wholly-dynamic props
expression there is a compile error. A **foreign** component has no such
invariant: its props are open and pass through untouched. So the standard wrapper
idiom — take a props map, add your own, forward the rest onto the widget — is
admitted at a foreign head through `ui/spread`:

```clojure
(defview date-field [{:keys [date on-pick] :as props}]
  ;; owned :selected/:on-change are compiled here; the rest of `props`
  ;; is forwarded onto the foreign DatePicker, unconverted
  [DatePicker (ui/spread {:selected date
                          :on-change (ui/handler [v] (on-pick v))}
                         (dissoc props :date :on-pick))])
```

The **literal part** (`{:selected … :on-change …}`) is analysed exactly like a
literal call-site map — the `ui/handler` compiles to a committed, per-site-stable
callback. The **forwarded map** is opaque: it passes through verbatim (a foreign
head owns its own prop ABI) and marks the site dynamic. The compiled literal props
**win** any key collision, so the forwarded map can never clobber your committed
callback. `(ui/spread forwarded)` with no literal part forwards a map alone.
`ui/spread` at an *internal view* stays a compile error
(`:rf.ui.compile/spread-internal-view`) — use `ui/spread-safe` when a component
library needs to forward a consumer's attrs onto an internal element.

### Exporting a view outward: `ui/->react`

`ui/raw` embeds foreign React *inward*; `ui/->react` is the reverse — it exports a
compiled view *outward* as a React component a legacy or foreign React/UIx
tree can render. It is the incremental-adoption bridge: migrate a leaf or a panel
to `defview` and drop it into the shell you have not migrated yet.

```clojure
;; Once, at the boundary — `cart-row` is an ordinary defview:
(def CartRow (ui/->react cart-row))

;; Then, in the foreign React/UIx parent, render it like any component:
;;   <CartRow frame={the-frame} item={row} />
```

The bridge is deliberately thin:

- **Memoised per view identity** — repeated `(ui/->react cart-row)` returns the
  *same* component object, so a foreign parent re-render never remounts the
  exported subtree.
- **No new React root, no manifest, no preflight** — the exported subtree renders
  inside the root the foreign parent owns. Frame *creation* stays with your app's
  boot/event code; an exported view only *scopes* and *resolves* frames.
- **The frame** comes from the ambient chain — a `frame-provider`/`frame-root`
  above it in the tree (they share one React context object across substrates) —
  or from a **supplied `frame` prop** (a frame-id keyword or a live frame value),
  which scopes the subtree without owning it. With neither, a frame-scoped read
  fails loud with `:rf.error/no-frame-context` — never a silent default.
- **One shallow props rule** — each prop the parent passes maps to the view's
  prop-ABI slot by exact name (write the slot names directly from a JS codebase);
  `children` and `ref` pass through preserved. Only the reserved `frame` prop is
  consumed by the bridge. There is no camelisation and no deep conversion.

SSR is not supported through the outward bridge in v1 (a compiled component is not
renderable on a legacy server path); render a placeholder container server-side and
let the subtree mount client-side, or migrate that page's whole root to a `ui` root.

### Library render slots

When a reusable view accepts *parameterised markup* — a row renderer, a cell
template — that markup is a `ui/render-fn` (pure, compiled, renders from its
arguments alone) and the library invokes it through `ui/slot`:

```clojure
;; consumer hands the library a pure render callback:
[data-grid {:rows rows :render-row (ui/render-fn [row] [:td (:name row)])}]

;; inside the library, at the seam:
(ui/slot render-row row)
```

This is the middle of the customization taxonomy — data props, then pure render
slots, then registered stateful views (that last tier is not in v1). A `render-fn`
body is pure render phase: `sub`/`frame` are allowed, but dispatch, hooks,
state, and effects inside are compile errors — a stateful part is a pure slot body
that mounts a static `defview` owning its own state.

## Containing a crash: `ui/error-boundary`

A third-party widget or a risky subtree can throw during render. `ui/error-boundary`
catches render/lifecycle throws *below* it — not event-handler or async errors,
which keep their own typed paths — so one crash doesn't take the whole page:

```clojure
[ui/error-boundary {:fallback error-panel
                    :reset-key route-id
                    :on-error [:ui/render-failed]}
 [risky-subtree]]
```

On catch, the `:fallback` **view** renders with `:error` and its props;
`:on-error` (optional) dispatches *after* the failing commit through a live frame —
never during render; and changing `:reset-key` (compared `rf=`) clears the caught
error, so a retry is just a state change that moves the key. On the server there is
no boundary recovery — a throw follows the server failure policy ([SSR](ssr.md)).
Bad opts or a non-view fallback fail loud at compile time
(`:rf.ui.compile/bad-error-boundary`).

## Genuinely data-driven UI

For CMS trees or server-defined form schemas — markup whose *shape* is data, not
source — the answer is an explicit interpreter, opt-in with a visible cost. That
interpreter (`re-frame.ui.data`) is a **planned wave-2 artefact and does not ship in
v1**; until it lands, a runtime-chosen foreign head goes through `ui/raw` of an
element you construct. If an escape starts to look like the main path through your
app, the design is probably wrong for this substrate — you want a foreign React
island behind a thin `ui/raw` boundary, not a `defview` full of runtime structure.

## The doors, at a glance

| Need | Door | Notes |
|---|---|---|
| A React element a foreign lib already built | `ui/raw` | Boundary, not a template feature |
| A runtime prop map on a DOM element | `ui/spread` / `ui/spread-safe` | Runtime conversion on the compiler's own rule table |
| Forwarding props onto a foreign component | `ui/spread` | Literal part compiled, forwarded map opaque (internal views stay literal) |
| Unescaped HTML you vouch for | `ui/html` | Explicit at the call site |
| Host-only leaf under SSR | `ui/client-only` | Fallback is plain markup — [SSR](ssr.md) |
| Parameterised markup for a library | `ui/render-fn` + `ui/slot` | Pure; compiled; no runtime hiccup |
| A hook at a foreign boundary | `re-frame.ui.react/*` | Interop tier — not for ordinary views |
| Logic the template must not see | An ordinary function, or another `defview` | The preferred recovery for an unsupported form |

## How errors show up

- **Build time** for structure and site rules — dynamic heads, loops, missing
  keys, unsupported forms, missing required props on literal call sites. Messages
  name the fix and usually carry file:line.
- **First dev render** for what only a live value can prove — a schema failure on
  dynamic props, an unknown event id, a dynamic child that turns out to be a
  keyword.
- **Production** never re-teaches you: the illegal form never compiled. Greppable
  ids like `:rf.ui.compile/unsupported-form` and `:rf.ui.compile/dynamic-head` are
  part of the contract.

## Planned and absent surfaces

Honesty about what is *not* here yet, so you don't hunt for it:

- **`ui/element`, `ui/view`, `ui/portal`, `re-frame.ui.data/render`** — a
  runtime-chosen head, a registry-addressed component, a portal, the data
  interpreter. **Wave-2: not in v1**, gated behind demand. `ui/raw` covers
  runtime-chosen heads in the meantime.

The authoritative roster of what exists lives in the
[`re-frame.ui` API reference](../../api/re-frame.ui.md).

## When not to use this substrate at all

The closed grammar is the bill for the machinery. Weigh it honestly:

- **You need runtime-shaped markup as the norm** — a CMS renderer, a form engine
  driven by server schemas. The escapes exist, but if they're your main path, an
  interpreter (or a foreign React island) fits better.
- **You have a large existing Reagent or re-com application** and no appetite to
  migrate the view tier now. The dataflow half is already shared; the views can
  stay.
- **You want stable ground today.** `re-frame.ui` is pre-alpha, delivered in
  staged slices, and not on Clojars yet.

In every one of those cases the answer is the same, and it bears repeating as this
guide's standing note: **the retained adapters — Reagent, reagent-slim, UIx — are
the default choice.** They are first-class and actively supported, not a fallback.
Choosing one is a supported decision, and the dataflow you learned in the
[core guide](../introduction.md) works identically behind all of them.
