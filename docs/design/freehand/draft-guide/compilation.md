# Compilation

Most Freehand views should stay **interpreted**. Compilation is a power tool for
hot boundaries and library leaves — not a second product you live in from day one.

High `:stable-renders` means fix subscriptions or props, not compile. Measure
first; compile second.

Freehand remains **one** view layer. Compiled mode is an option on the same
`v/defview`. Call sites stay `[view props]`. Structural tests do not change. There
is no second compiler and no hidden interpreter inside a compiled template.

> **Measure → `v/check` → then `{:compiled true}`. Never the reverse.**

## Day-one: usually do not compile

You can ship entire apps interpreted. Promote a boundary only when:

1. measurement shows interpretation cost on a hot template, **or**  
2. a library leaf needs static proof / manifests, **and**  
3. `v/check` is clean for that declaration.

### Promotion recipe

```clojure
(v/check people-list)          ; fix findings first
(v/defview people-list
  {:compiled true}
  [props]
  …)
```

Call sites and structural tests stay the same. Demote by removing `{:compiled true}`
when debugging.

## What compilation buys — and what it does not

When a view body is inside the finite subset, compilation can give you:

- direct React lowering and a JVM structural emitter  
- finite manifests for subscriptions, events, key maps, slots, host edges,
  presence, and top-layer forms (static “what *can* happen,” not only what one
  render observed)  
- generated prop comparators and prebound handlers where the compiler can prove
  them  
- static diagnostics with source coordinates  
- static asset / SSR evidence where the emitter supports it  
- **capability elision** — unused machinery drops out; a proved **sub-free** view
  can omit the reactive ViewCell shell entirely  

Compilation does **not** buy:

- faster subscription *derivation* (the sub graph still runs)  
- free React reconciliation in dynamic regions  
- automatic promotion or a second authoring language  
- a silent interpreter for “just this one dynamic hole”  
- omniscience for *interpreted* siblings (they still only report realized reads)  

Think of compilation as a capability you turn on when evidence says you need it —
not a quota or a badge.

## Selecting compiled mode

```clojure
(v/defview todo-row
  {:compiled true}
  [{:keys [id]}]
  (let [{:keys [title completed]} (v/sub [:todo/by-id id])]
    [:li {:class {:completed completed}}
     [:input {:type :checkbox
              :checked completed
              :on-change [:todo/toggle id]}]
     title]))
```

Call sites stay `[todo-row props]`. Structural tests do not change. The public var
remains a **descriptor** (not `IFn`) — you never `(todo-row {})`.

The grammar version is artifact-wide (`:re-frame.freehand/v1`), carried on findings
and manifests. It is **not** a per-view compatibility profile between products, and
not a second namespace or macro.

There is **no author-facing `:reads` declaration in v1**. Reads are inline
`(v/sub …)` only. Tools distinguish evidence with scope / basis / completeness /
loss, not a second read language.

## Checker-first workflow

Before adding `{:compiled true}`, run the read-only checker against the
**interpreted** declaration:

```clojure
(v/check people-list)
;; ⇒ stable EDN — never auto-edits your source
```

Illustrative finding envelope (fields may grow; shape is stable):

```clojure
{:view-id           :app.people/people-list
 :source            {:file "…" :line 42 :column 1}
 :current-lowering  :interpreted
 :target-grammar    :re-frame.freehand/v1
 :compile-eligible? false
 :findings
 [{:id       :re-frame.freehand.compile/opaque-markup-call
   :source   {:line 47 :column 5}
   :form     '(render-person person)
   :reason   :markup-hidden-from-analyzer
   :recovery [:extract-declared-child
              :make-template-visible
              :keep-interpreted]}]}
```

Each finding’s **recovery** list *is* the work order. Prefer:

- **keep interpreted** — helper-heavy app pages and composition
- **extract declared child** — hot leaves / rows you still want compiled
- **make template visible** — finite `for`, literal heads, visible `sub`

Changing the declaration (`{:compiled true}`) is the **last** step, not the
discovery mechanism. Freehand never promotes from a percentage threshold or silent
rewrite.

### Mini promotion transcript

```clojure
;; 1) interpreted, fails check — opaque helper + map markup
(v/defview people-list [_]
  [:ul (map (fn [p] (person-row p)) (v/sub [:people]))])

;; 2) after v/check recoveries — keyed for + declared child
(v/defview person-row
  {:compiled true}
  [{:keys [person]}]
  [:li (:name person)])

(v/defview people-list
  {:compiled true}
  [_]
  [:ul
   (for [p (v/sub [:people])]
     [person-row {:key (:id p) :person p}])])
```

Promotion is **not transitive**. Compiling the list does not force every child
compiled, and compiling a leaf does not require its parent compiled. Nested
`[child props]` works both directions when the child is a **statically named**
descriptor.

## Placement defaults (cold start)

| Surface | Default | Why |
|---|---|---|
| App pages, routes, composition | interpreted | freedom and evidence later |
| Library leaf controls | compile when in-subset | elision; consumers pay your choice N times |
| Keyed row templates at scale | compile when clean; window first | wide-parent churn |
| Flap-prone controlled sites | compile to pin the door | predictability |
| Everything else | interpret until evidence says otherwise | anti-folklore |

Optimization order:

1. narrow subscriptions and choose boundaries
2. window large collections
3. use a qualified React leaf if substrate semantics inside the hot leaf do not matter
4. compile remaining interpretation-dominated or static-evidence needs
5. measure again with the same counters

---

## The closed vocabulary (what compiled mode can see)

Interpreted mode runs **full Clojure**: any helper may return Hiccup, any head may
be chosen at runtime, any `sub` may appear in ordinary control flow (subject to
good style).

Compiled mode is different. The compiler must **prove at compile time**:

- the **structure** of the template (tags, children, keys)
- every **reactive site** (`v/sub`)
- event / host / presence shapes where it claims static evidence

Anything it cannot see through is a **checker finding** (and a build failure once
you mark `{:compiled true}`), with a named recovery — not a silent runtime
fallback.

### One-sentence rule

> If the compiler cannot prove the structure, the reactive sites, and the list
> keys of a piece of markup **at compile time**, that spelling is illegal in a
> compiled `v/defview` body.

Ordinary **branching and binding are free** when they use forms the analyzer
understands (`let`, `if`, `when`, `if-let` / `when-let` / `if-some` / `when-some`,
`cond`, `case`, `for`, …). Both arms of an `if` and every `cond` clause are
visible. You are not forced into one linear template.

### Mode comparison

| Concern | Interpreted | Compiled (`:re-frame.freehand/v1`) |
|---|---|---|
| Body language | full Clojure | closed, compiler-visible template/control forms |
| `(v/sub …)` | dynamic capture; helpers may read | **finite, lexically visible** sites; helpers must not hide reads |
| View / element heads | runtime choices among descriptors OK | **statically named** or finite explicit branches |
| Child markup as a value | pass Hiccup around freely | **compiler-owned** structure only |
| Loops | ordinary `map` / seq realization | compiler-owned keyed **`for`**; reactive rows → **child views** |
| Props maps | runtime maps OK at the edge | **literal** shape + `v/spread` / `v/spread-safe` ([composition](composition.md#spreading-props-attribute-forwarding)) |
| Events | runtime-classified common grammar | same grammar; static where knowable |
| Parameterized rows | ordinary pure fn OK | lexically visible `v/render-fn` / `v/slot` |
| Presence / top-layer | runtime plans | statically recognized forms |
| Host leaves / behaviors | qualified descriptors | **statically named** descriptors |
| `local` / neutral hooks | rejected | rejected (host boundary instead) |
| Dynamic markup in template | natural | **no inline valve** — see `v/markup` below |

There is **no** `v/interp` and no “compiled except this unknown subtree.”

### Template structure — illegal forms

| You cannot (compiled) | Why | Do this instead |
|---|---|---|
| Dynamic tag heads — `[(if big? :h1 :h2) title]` | Emitter must know the constructor | Both branches, or one fixed tag + varying attrs/class |
| Markup-returning `map` — `(map row-view rows)` | Hides structure, keys, and `sub` sites | `(for [r rows] [row-view {:key … :row r}])` |
| Unkeyed items in a dynamic list | List identity is a proof obligation | `{:key …}` on every list child |
| Opaque helper returning Hiccup in template position — `(field-help props)` | Analyzer cannot lower the returned value | `[field-help-view props]` (declared child), inline the markup, or stay interpreted |
| `(sub …)` inside an unbounded loop | Reactive sites must be finite | Extract a keyed child view that subscribes for one row |
| Event site capturing a **loop binding** as a closed-over value for many rows | One lexical committed slot cannot mean N instances | Keyed child whose **props** carry `id` / row data |
| Hiding `sub` inside an unaudited macro or deep helper | Manifest would lie | Visible `v/sub` in the view, or value computed in a pure helper and passed in |
| Wholly dynamic props map on an **internal** view | Per-slot memo / analysis need known keys | Literal props; `v/spread-safe` (internal/controlled) or `v/spread` (foreign) — [composition](composition.md#spreading-props-attribute-forwarding) |
| Dynamic head registry — runtime choose among unknown views | Head must be finite | `case`/`cond` over known descriptors, or interpreted parent |
| Bare fn on a **foreign** callback prop | Phase/identity unknown | `v/event` / `v/handler` / `v/render-fn` / `v/raw-fn` |
| Bare React component as an ordinary head | Foreign boundary must be named | `host/component` or wrapper |
| Inline interpreter fallback for “this bit of runtime Hiccup” | Unpredictable cost and false manifests | `[v/markup {:value hiccup}]` or keep parent interpreted |
| `local` / neutral hooks / refs / effects in the view body | One state system; host is explicit | re-frame state or [host boundaries](host-boundaries.md) |
| Author `:reads […]` block (v1) | Not in the grammar | Inline `(v/sub …)` only |

**Also named by the checker (donor-class structural rules):** keywords in child
position (`[:div :oops]`), app props named `:key`, children passed to a view that
did not accept `:children`, `#js` / hand-rolled camelCase on compiled DOM paths
(use keyword props; one conversion table).

### Reactive sites must be finite

Every `(v/sub …)` in a compiled view is a compile-indexed site. Conditional
`sub` in an `if` / `when` is fine (not a hook). **`sub` in a `for` over a
runtime collection is not** — extract:

```clojure
;; illegal when compiled — sub site per loop iteration is not finite in the parent
(for [id ids]
  [:li (v/sub [:item id])])

;; legal — finite site in the child
(v/defview item-row
  {:compiled true}
  [{:keys [id]}]
  [:li (:title (v/sub [:item id]))])

(v/defview item-list
  {:compiled true}
  [_]
  [:ul
   (for [id (v/sub [:item-ids])]
     [item-row {:key id :id id}])])
```

Expression positions (prop values, conditions, `for` collections) hold ordinary
Clojure **values**, but their **syntax** is audited so every reactive call stays
visible. Prefer transparent core forms and plain functions for computation. Move
exotic macros out of the template body.

### Dynamic markup: `v/markup`, not a valve

Interpreted code often does this:

```clojure
(defn field-help [{:keys [error hint]}]
  (if error [:p.error error] [:p.hint hint]))

(v/defview editor [_]
  [:section (field-help {:error (v/sub [:e]) :hint "…"})])  ; fine interpreted
```

Compiled mode **rejects** treating that return value as template syntax. Recoveries:

1. **Declared child** — promote the helper to `v/defview` and call `[field-help …]`.
2. **Inline** the branches in the parent template.
3. **Stay interpreted** — leave `{:compiled true}` off.
4. **Inert markup already in hand** — mount a visible interpreted boundary:

```clojure
[v/markup {:value some-hiccup-value}]
```

`v/markup` is an ordinary **declared interpreted child**. The compiled parent sees
one static descriptor; the child owns the walk. Manifests count those mounts as
`:interp-slots`. It is **not** an invisible “compile this except here” hole.

### What is still allowed (so the grammar is not barren)

Compiled views still use the **same application-facing vocabulary** as
interpreted ones where the form is visible:

- Hiccup tags, classes, props, trailing `:children`
- `(v/sub …)` when sites are finite and visible (including conditional branches)
- Event vectors, options maps, `::v/value` / `::v/checked` / `::v/key`
- `v/event` / `v/handler` / `v/render-fn` / `v/raw-fn` at the right phases
- `v/presence`, top-layer desired-state props, when forms are statically recognized
- Qualified host leaves and behaviors when **statically named**
- `v/spread-safe` / `v/spread` under the common ABI
- Nested `[child props]` when `child` is a known Freehand descriptor
- `v/render-fn` + `v/slot` when the slot body is **pure and lexically visible**
  (parameterized rows); stateful customization is a declared child view, not a
  closure that hides `sub`

The cliff is **dynamic structure and hidden reactivity**, not “you may not use
events” or “you may not branch.”

### Parameterized content (`render-fn` / `slot`)

```clojure
(v/defview data-table
  {:compiled true}
  [{:keys [rows row]}]
  [:table
   [:tbody
    (for [item rows]
      [:tr {:key (:id item)}
       (v/slot row item)])]])

;; caller — pure, visible body
[data-table
 {:rows people
  :row  (v/render-fn [person]
          [:td (:name person)])}]
```

If the row needs a subscription or its own events, extract
`[person-row {:key … :id …}]`. Do not smuggle `sub` inside an opaque function
the compiler cannot see.

Full children taxonomy (trailing children, compound regions, when to promote to a
declared view): **[Composition](composition.md)**.

---

## Crossing modes and seam laws (author-facing)

| Law | Meaning |
|---|---|
| **Call-site invariance** | `[view props]` both ways; promotion edits the definition, not callers |
| **Test invariance** | structural tests cannot tell which mode produced the tree (common subset) |
| **One meaning** | shared props, keys, events, frame, controlled door, tree schema — not parallel promises |
| **Evidence continuity** | counters that justified promotion still flow after; manifests **add** proof |
| **Visible boundary cost** | interpreted children of a compiled parent are explicit (`[child …]` or `v/markup`); no hidden walker |

Mechanically:

- Interpreted parent → compiled child: fine.
- Compiled parent → **statically named** interpreted child: fine (emitted boundary).
- Compiled parent → runtime-chosen head: put the dynamic choice **inside** an
  interpreted child, or stay interpreted.
- Direct call `(view props)`: always wrong (descriptor is not `IFn`).

### Evidence: static vs realized

| Mode | What tools can claim |
|---|---|
| Interpreted | **Realized** reads/events for a committed render; not a full static inventory of every path |
| Compiled | **Possible** sites from the manifest (static) **and** which sites were active this render |

Every evidence projection should state **scope, basis, completeness, and loss**
(D012). Compiled does not make interpreted siblings “proven.”

### HMR note

Compatible HMR preserves the shell and reconciles the next target set. Compiled
views may carry an internal **site-signature generation**: a body change that
alters the finite site set is fenced so an old speculative render cannot publish
into new code. Authors do not manage this. Know that a big grammar change can
remount that shell cleanly.

## Props schemas

Schemas are **optional in the grammar** (including for many compiled app views).
They are **mandatory by policy** for:

- shipped reusable library / catalogue surfaces
- claims of generated coverage or generative parity for a particular view

```clojure
(v/defview todo-row
  {:compiled true
   :props [:map [:id :int]]}   ; vector-form Malli; closed by default for maps
  [{:keys [id]}]
  …)
```

- `:key` is outside the schema; children policy is descriptor metadata.
- Missing schema reports as **absent**, never as implicit `:any`.
- Literal calls may be checked statically; dynamic calls in development.
- Validation is removable from production builds.
- Analyzer-derived prop *slots* are private compiler facts, not a substitute
  public schema.

## If something feels wrong

| Symptom | Fix |
|---|---|
| Checker rejects opaque helper markup | extract declared child, pass values, or stay interpreted |
| Dynamic tag / unkeyed list under compile | finite branches; real `:key`s |
| Need a dynamic hole in a compiled parent | `[v/markup {:value …}]` — visible interpreted child |
| Still slow after compile | host cost or data size — not another Hiccup compiler |
| High `:stable-renders` | fix subs/props — do **not** promote for that alone |

## What not to do

- Do not compile every view "to be safe."
- Do not invent a second authoring API for hot views.
- Do not hide interpretation inside compiled template space.
- Do not treat donor `re-frame.ui` as a parallel product — its useful machinery is
  the compiled implementation source under absorption; Freehand is the public door.
- Do not expect full Clojure markup helpers to “just work” after `{:compiled true}` —
  run `v/check` first.
- Do not promote on a high “stable output” rate alone — that usually means
  **narrower subs**, which helps both modes; promote when **interpretation work**
  (`nodes` × renders / self-time) still dominates after structural fixes.
