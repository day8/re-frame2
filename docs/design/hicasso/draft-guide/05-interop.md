# Interop

> **Pre-implementation draft — Hicasso does not exist yet.** This page describes the
> *designed* surface so it can be read before it is built. Spellings marked
> **[unfrozen]** are placeholders that will change. The whole tree is disposable: it
> is rewritten after the P2 fork ruling, against a real implementation. Normative
> source: [decisions.md](../decisions.md) (HD-001…HD-021).

You want a date picker from npm. In Reagent you write `[:> DatePicker {...}]` and
move on. Hicasso asks you to write one line first:

```clojure
(ns app.hosts.date-picker
  (:require [re-frame.hicasso :as h]
            ["react-datepicker" :default DatePicker]))

(h/defhost date-picker DatePicker)     ;; defhost is [unfrozen]
```

Then use it anywhere, and it is indistinguishable from a native view:

```clojure
(ns app.views
  (:require [app.hosts.date-picker :refer [date-picker]]))

[date-picker {:selected  due-date
              :on-change [:task/set-due ::h/value]}]
```

## Why a declaration instead of `[:>]`

`[:> DatePicker {...}]` puts a raw JavaScript value inside your data tree. Three
things break at that node, all of them quietly:

- **`.cljc` purity.** The namespace can no longer load on the JVM, so it can no
  longer be tested headlessly.
- **Structural testing.** The node holds an opaque JS object, so you can't assert
  the tree with `=`.
- **Tooling identity.** There is nothing for a tool to name — the crossing has no
  identity, just a value.

`defhost` gives the crossing a name, keeps the JS require quarantined in one `.cljs`
host namespace, and leaves the rest of your view tree pure data.

The honest counter-argument is that a declaration is friction, and the counter to
*that* is arithmetic: it amortizes to zero over every use site, and a codemod exists
for migration. The expensive half of leaving Reagent was never `[:>]` — it was
`r/atom`.

## The defaults

`defhost` with no options gives you:

| Default | Behaviour |
|---|---|
| Props | shallow camelCase conversion — `:on-change` becomes `onChange` |
| Children | hiccup children are converted to React elements |
| Functions | pass through unconverted |
| SSR | a placeholder (declared policy; inert in v0, since SSR is out of v0 per HD-020) |

Policy overrides live on the declaration rather than at the call site, which is the
whole point: one place decides how this component is crossed, and every use site
inherits it. The option keys are not in the record; see **Not settled yet**.

## The escape: `[:>]` is legal

`[:> Component props & children]` lowers through the same foreign path with the same
default conversions. It is `.cljs`-only at that node and has reduced structural
identity — exactly the costs listed above, accepted knowingly.

Use it when a static declaration genuinely cannot express the case:

- the component is **selected at runtime** from a map or a prop;
- it is a `memo` or `lazy` value;
- it arrives from a **render prop**;
- it is a **provider an ecosystem library hands you**, not one you named;
- it is a **one-off migration site** you have not got to yet.

**The rule is: declare what you use twice.** First use, take the escape if it's
faster. Second use, write the `defhost`. That is not a moral position; it is where
the amortization crosses over.

One thing stays rejected: **bare-head auto-hosting**. Putting a raw JS component in
head position — `[DatePicker {...}]` — and having the runtime identity-key it is
*not* legal. One sentinel, not two shortcuts. If `[:>]` and a bare head both worked,
every codebase would have both, and the reader would have to know which node is
which by looking somewhere else.

## Migrating from Reagent

A codemod handles the mechanical part: collect the `[:> X …]` sites, emit the
`defhost` block, rewrite the call sites. It runs incrementally, so a large app
converts a namespace at a time rather than in one commit. The codemod's name and
invocation are not in the record.

## Imperative SDKs

A mapping SDK, a chart library that wants a DOM node, anything that hands you a
handle and expects you to feed it — that is **not** an interop-door problem. It is
ordinary host-edge React (HD-003): a callback ref, an effect, and a cleanup, written
in a `.cljs` namespace at the edge of your app.

```clojure
;; Sketch only — host-edge React inside a defview body, in a .cljs namespace.
(ns app.hosts.map-panel
  (:require [re-frame.hicasso :as h :refer [defview]]
            ["react" :as react]
            ["some-map-sdk" :as sdk]))

(defview map-panel [_]
  (let [attach (react/useCallback
                 (fn [node] (when node (sdk/mount node)))
                 #js [])]
    [:div.map {:ref attach}]))
```

There is no Hicasso concept for this, deliberately. "Behaviors" — a predecessor
product concept for imperative host ownership — is **not v0 vocabulary**. A richer
host-ownership pattern is a product-phase question; the v0 answer is React, used
honestly, at the edge.

Note the two hook-budget consequences: this body now has hooks in it, so it is
outside the headless testing scope ([Testing](08-testing.md)) and you have taken on
React's hook rules yourself. Both are fine. Both are on you.

## Troubleshooting

No Hicasso error ids exist yet; this table names mechanisms.

| Symptom | What went wrong | Fix |
|---|---|---|
| Prop arrives as `on-change` and the library ignores it | Deep conversion expected; the default is shallow | Convert the nested map yourself, or set a policy on the declaration |
| A namespace stops loading on the JVM | A JS require reached a `.cljc` file | Quarantine the require in a `.cljs` host namespace |
| Structural test can't match a node | It's a `[:>]` node — reduced structural identity, by design | Declare it with `defhost`, or assert around it |
| Provider from a library needs to wrap the tree | Hosted like anything else | `defhost` the provider; it is a component |
| The SDK mounts twice in dev | StrictMode double-invoke of effects | Make attach and cleanup idempotent — the same discipline React requires of any JS app |

## When not to reach for the door

If the library is a thin wrapper over DOM you could write yourself in twenty lines
of hiccup, write the twenty lines. Every hosted component is a node your tools can
see less of and your tests can assert less about. The census found **zero** foreign
components across 85 idiomatic files, which is why this is an escape hatch and not
tier-1 syntax — most applications never need it, and the ones that do need it for
two or three genuinely hard widgets.

## Not settled yet

| Question | Status |
|---|---|
| `defhost`'s option keys | **Not addressed.** "Policy overrides live on the declaration" is as far as the record goes |
| The codemod's name and invocation | **Not addressed** |
| Whether `::h/value` works across a host crossing | **Not addressed.** The example above assumes it does; a foreign `onChange` may hand you something other than a DOM event |
| The SSR placeholder's shape | Declared policy, inert in v0 |
| Whether a hosted component can be a `defview`'s child via `(:children props)` | The ABI says an existing React element is a legal child anywhere, which implies yes; not stated for the host case specifically |
| Embedding Hicasso *inside* a React-primary app | Named in the charter's use-case roster (item 11); no surface designed |
