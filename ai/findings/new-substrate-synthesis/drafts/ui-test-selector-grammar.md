# DRAFT — `ui.test` selector grammar (the one page)

> **Status: DRAFT · 2026-07-11 · reconciled 2026-07-12** to the public JVM tree
> contract ([jvm-tree-and-conversion-contract.md](jvm-tree-and-conversion-contract.md))
> per the codex2 Finding-2 disposition ([09 §codex2](../09-review-disposition.md),
> row 2): nodes are plain maps whose attrs/events live under their own keys — the
> earlier direct-keyword-lookup promise is removed; view-id selectors match the tree's
> view-boundary node (OPEN-1 resolved). The one page for the `ui.test/find`/`query`
> selector language, required before Stage 1. Contract context:
> [07-testing §2](../07-testing.md), [guide/09-testing.md](../guide/09-testing.md).
> Demand bar per [08-delivery §3](../08-delivery.md): every form below names its
> consumer; no speculative combinators.

## Scope

`(ui.test/find tree selector)` and `(ui.test/find-all tree selector)` are **structural
queries over Tier-1 trees** — the value `ui.test/render` returns on the JVM (or node),
per 07 §2. `tree` is any structural **map node** per the tree contract (element,
fragment, view-boundary, or trusted-HTML; text content is a string, not a node —
results compose; see §What `find` returns).
Passing a Tier-3 mounted root to `find`/`find-all` is a typed error pointing at
`ui.test/query` (same typed-error style as the JVM-subset errors, 06 §1). The Tier-3
contract is stated by contrast in the final section.

## The grammar (closed)

```
selector := tag-kw        ; unqualified keyword — element tag
          | view-sel      ; qualified keyword (registered view id) or the defview Var
          | attr-map      ; map of attr-key → expected value, matched by rf=
          | pred-fn       ; (fn [node] boolean) — the escape
          | [selector+]   ; path composition (descendant steps)
```

No other form is a selector. Deliberately absent: sibling/nth/positional combinators,
wildcard, text-content selectors, attribute-presence-without-value — no guide example or
07 §3 fixture needs them; `pred-fn` covers the residue.

- **`tag-kw`** — an **unqualified** keyword matches a node whose element tag is that
  keyword, exactly (`:button` matches a `:button` element node; no substring, no
  case-folding). *Consumer:* guide 09 Tier-1 example (`(ui.test/find :button)`).
- **`view-sel`** — a **qualified** keyword matches the **view-boundary node** whose
  `:view-id` is that keyword (the tree contract's explicit node variant for each
  internal-view expansion); the defview Var is accepted and resolves to its registered
  id. Because the boundary node exists even when no single root element does,
  **fragment-rooted and nil-rooted views are matchable** — the match is the boundary
  marker, not a root element. *Consumer:* 07 §2's contract row ("tag, view id, attr
  predicates"); Story scenes mount by view id (guide 09 §Story). The
  unqualified/qualified split is what disambiguates tags from view ids; a hypothetical
  unqualified view id is not selectable by keyword — use the Var.
- **`attr-map`** — matches a node iff for **every** entry `k → v` in the map, `k` is
  present in the node's **projected attribute map** — `(ui.test/attrs node)`, the tree
  contract's merged projection: attrs + events on elements, props on view-boundary
  nodes — and its value is `rf=` to `v` (the value-equality relation of
  [spec/006 §Host value model]; so `{:on-click [:cart/add 42]}` matches by event-vector
  value via the `:events` slot, and map/vector-valued attrs compare structurally). A
  key absent from the projection never matches; emitter-produced canonical trees carry
  no present-nil attrs (nil-valued entries are dropped at tree build), so the
  present-nil arm of `rf=` rule 5 governs only hand-built maps. `{}` matches every node
  (vacuous truth; harmless, not useful). *Consumers:* 07 §2's "attr predicates";
  `{:data-testid "x"}` for stable test ids; event-vector matching serves guide 09's
  intent-assertion style ("what does this button do" as an equality check); prop
  matching on view-boundary nodes rides the same rule at zero extra grammar.
- **`pred-fn`** — any fn; matches a node iff `(pred node)` is truthy. The node argument
  is always a **map node** (text content is never visited), and supports the read
  surface in §What `find` returns. This is the closed grammar's escape
  hatch (escapes advertise their cost, I-14): reach for it only when the three data
  forms cannot express the match. *Consumer:* harness completeness; keeps the data
  grammar closed instead of growing combinators.
- **`[selector+]`** — path composition: `[:form :button]` finds a `:button` **descendant**
  of a `:form` match. Pure composition — zero new matcher semantics (see below).
  *Consumer:* row-scoped assertions in keyed-list fixtures (Stage 1 "keyed lists" +
  G-9-style Tier-1 intent checks: the button *inside* row 42's node). *(See [OPEN-2].)*

## Matching semantics

- **Traversal order** is depth-first pre-order — document order — over the tree's
  **map nodes** (elements, fragments, view boundaries, trusted-HTML), descending
  through fragment and view-boundary children alike; trusted-HTML nodes are leaves
  (their markup is unparsed) and text content is not visited. A single (non-vector)
  selector is tested against the given node itself **and** its descendants, in that
  order.
- **`find`** returns the **first** match in document order, or misses (§Failure).
  **`find-all`** returns a vector of **all** matches in document order (possibly empty).
- **Path steps** — for `[s1 s2 … sn]`: match `s1` per the rule above; each subsequent
  step `sk+1` is matched against the **strict descendants** (excluding the step-`k` node
  itself) of each step-`k` match. The result set is the union of final-step matches,
  de-duplicated, in document order of the whole tree. Consequences: `[s]` ≡ `s`;
  `[:form :form]` finds a *nested* form, never the same node twice; steps are the
  descendant axis, not direct-child (no child combinator — unearned).

## What `find` returns

A **structural node** of the Tier-1 tree — a plain map per the tree contract
([jvm-tree-and-conversion-contract.md](jvm-tree-and-conversion-contract.md)), whose
field names (`:tag` / `:attrs` / `:events` / `:children` / `:view-id` / …) are that
contract's versioned public ABI. Its read surface:

- **Queryable.** A node is itself a valid `tree` argument: `(find (find tree :form)
  :button)` composes (this is what the vector form folds over).
- **Keyword lookup reads the node's *fields*, never its attributes.** Attrs and events
  live under their own keys (`(:events node)`, `(:attrs node)`), so `(:on-click node)`
  is a field miss — the ruled node-reading contract (09 §codex2 row 2). The attribute
  read is the projection below.
- **`(ui.test/attrs node)`** — the **merged projection**: attribute map + handler map
  on elements (collision-free — the compiler routes `:on-*` to `:events`), the props
  map on view-boundary nodes, `{}` on fragments/trusted-HTML, `nil` on `nil`
  (nil-punning). Handler slots carry **event vectors as data** — so intent is an
  equality check:
  `(is (= [:cart/add 42] (:on-click (ui.test/attrs (ui.test/find tree :button)))))`.
  *(guide 09's example predates this reconciliation and needs the same one-line
  respell — a fold-in ripple owned by the guide pass.)*
- **`(ui.test/text node)`** — the concatenation of the node's text descendants in
  document order, e.g. `"Add to cart"`; trusted-HTML nodes contribute nothing (their
  content is unparsed markup). No whitespace normalization beyond what the tree
  carries.

Children are traversed only via `find`/`find-all` (selectors are the query surface —
`(:children node)` is a public field read, but the selector grammar is the supported
query idiom); the node shape, canonical form, and view-boundary variant are owned by
the tree contract, which this grammar consumes.

## Failure behavior

- **`find` → `nil`** on no match. Idiomatic nil-punning: `(-> tree (ui.test/find
  :button) ui.test/attrs :on-click)` yields `nil` through the thread —
  `(ui.test/attrs nil)` is `nil` per the tree contract's projection rules — so
  assertions fail with `nil ≠ expected`.
- **`find-all` → `[]`** on no match.
- **`ui.test/find!`** — the strict variant: identical selector semantics; **throws** a
  typed `ex-info` on zero matches, carrying the selector, the search root's tag/view-id,
  and a bounded tree digest (the miss diagnostics `nil` cannot give). It does **not**
  police uniqueness — first match wins even when several exist; a getBy-style
  uniqueness-asserting variant is unearned (no consumer). Recommended error id:
  `:rf.error/ui-test-find-miss` — needs its Spec 009 catalogue row and a 07 §2 contract
  row on promotion. *(See [OPEN-3].)*

## Tier-3 by contrast — `(ui.test/query root css-selector)`

`query` is the **live-DOM counterpart** of `find`, and shares nothing with the grammar
above:

| | `find` / `find-all` (Tier 1) | `query` (Tier 3) |
|---|---|---|
| Input | structural tree (data) | mounted root (`with-root`) |
| Selector | the closed grammar above | **native CSS selector string**, verbatim |
| Engine | harness matcher, `rf=` semantics | the host DOM's `querySelector` (jsdom or real browser) |
| Returns | structural node / `nil` | live DOM element / `nil` |
| Reads | `attrs` / `text` projections + node field reads; event vectors as data | host interop (`(.-value el)` — guide 09); real listeners, not vectors |
| Runs on | JVM + node, ms, no flake | browser/jsdom CI |

No `rf=` matching, no structural projections, no view-id selectors on the Tier-3 side —
CSS is the whole contract (`data-testid` attributes work via ordinary CSS
`[data-testid=x]`). There is no `query-all`: no consumer needs it (demand bar); add it
only when a fixture does. `attrs`/`text` on a DOM element, or a CSS string handed to
`find`, are typed errors pointing at the other tier.

## [OPEN] items

- ~~**[OPEN-1] View-boundary annotation slot.**~~ — **RESOLVED (2026-07-12)** by the
  tree contract's view-boundary node variant
  ([jvm-tree-and-conversion-contract.md](jvm-tree-and-conversion-contract.md) §Node
  schema): each internal-view expansion is wrapped in a real `{:view-id …}` node (the
  structural analogue of the DOM `data-rf-view` contract, spec/006 §View tagging), so a
  view-id selector matches the boundary marker itself. Fragment-rooted and nil-rooted
  views **are matchable** — the boundary node exists (with several children, or none)
  even when no single root element does.
- **[OPEN-2] Path-form demand.** The vector form's named consumer (row-scoped keyed-list
  assertions) is projected from Stage-1 fixtures, not yet a written guide example. If no
  Stage-1 fixture or guide example materialises the need, drop the form at the demand-bar
  audit — it is pure sugar over node-composed `find`, so dropping it costs nothing.
  *(Premise unchanged by the tree contract.)*
- **[OPEN-3] `find!` inclusion.** Recommended in (miss diagnostics; every Tier-1 test is
  the 08 §3 consumer for `ui.test/*`), but it is the one name here beyond 07 §2's table —
  confirm at the demand-bar audit and add the 07 §2 row + 009 catalogue row together.
  *(Premise unchanged; the "bounded tree digest" it carries can now be defined as a
  truncated canonical-EDN print per the tree contract's canonical form.)*
