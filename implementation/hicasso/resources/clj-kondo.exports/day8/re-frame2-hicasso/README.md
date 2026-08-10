# Hicasso's clj-kondo export

Six checks and three macro shapes, shipped from the artefact so a consumer's
clj-kondo picks them up with no configuration of their own.

This file is mostly about **what the checks refuse to know**. That is the more
useful half: clj-kondo sees one form at a time and does not know your program,
so a check written to catch every instance of a mistake would have to guess,
and a linter that guesses is one people learn to ignore. Everything here is
written to be *always right about a narrow thing* rather than *usually right
about a broad one*, and the narrowness is deliberate everywhere it shows.

## Installing it

```bash
clj-kondo --lint "$(clojure -Spath)" --dependencies --parallel --copy-configs
```

That copies this directory to `.clj-kondo/day8/re-frame2-hicasso/` and adds it
to your `:config-paths`. Nothing else is needed; re-run it when you upgrade.

Every level below is yours to change, and turning one off is a supported
answer:

```clojure
;; .clj-kondo/config.edn
{:linters {:re-frame.hicasso/unkeyed-mapped-child {:level :off}}}
```

## What it does for the three macros

`defview`, `hfn` and `defhost` are rewritten to their `defn`, `fn` and `def`
shapes, so kondo's ordinary analysis applies: a view's name resolves as a var,
a destructured prop is neither unresolved nor unused, arities are checked, and
a host's `opts` map is scanned for references. Without this every view name and
every prop reads as `Unresolved symbol`.

## The checks

### `:re-frame.hicasso/merge-not-a-map` — error

`{:& …}` given a literal the runtime will refuse
(`:rf.error/hicasso-merge-not-a-map`): a vector, set, string, keyword, number,
boolean or character.

**Refuses to know:** what any *expression* evaluates to. `{:& attrs}`,
`{:& (merge a b)}` and `{:& (get props :attrs)}` are the ordinary spellings and
are always silent — which means the common runtime failure, forwarding
something that turns out not to be a map, is still the runtime's to catch.
`{:& nil}` is legal and silent.

### `:re-frame.hicasso/deferred-read` — error

`h/sub` or `h/use-subs` written inside an `hfn` body. `hfn` **is** the callback
form: its whole contract is that it runs after the body that wrote it, so a
read there is deferred by construction rather than by circumstance, and the
runtime refuses it with `:rf.error/hicasso-sub-outside-render`.

**Refuses to know:** whether any *other* function value is deferred. A read
inside a `(fn …)` handed to `mapv`, `for`, `keep` or an inlined helper runs
*during* the body and is completely legal — a helper may donate reads to the
boundary that called it. "An `fn` literal inside a body" is therefore not
evidence of deferral, and this check does not treat it as any. Proving read
extent in general is the runtime's law, not lint's.

### `:re-frame.hicasso/function-in-head-position` — error

A `(fn …)`, `#(…)` or `(hfn …)` written as the head of a vector that sits in a
**children position of a literal hiccup vector**. A function is never a legal
head (`:rf.error/hicasso-bad-head`).

**Refuses to know:** whether a *symbol* head names a function, a view or a
host — that is what the symbol resolves to at runtime. It also refuses any
vector that is not in a definite children position: `[(fn [] :a) (fn [] :b)]`
bound in a `let` is an ordinary vector of functions and none of this check's
business.

### `:re-frame.hicasso/parked-read` — warning

`(reset! r (delay … (h/sub …)))` / `(vreset! r (fn [] … (h/sub …)))` —
a read parked in a mutable reference, with the thunk written out in full at the
`reset!`.

Per [rf2-djxr] the runtime does **not** chase deferred reads through mutable
references: `realize-deep` walks the structure a body returns, and a reference
is not in it. Forcing such a thunk inside another body is *undefined conduct*
rather than an error, which is why this is a warning and why nothing anywhere
enforces it. It is assistance, and it is the whole of the assistance.

**Refuses to know:** anything that needs a binding followed.
`(let [d (delay (h/sub …))] (reset! r d))` is invisible, and so is
`(reset! r (make-thunk))`, and so is a `swap!` that assoc's a thunk into a map.
Catching those means following bindings and resolving symbols across forms,
which is whole-program analysis wearing a lint hat — and the ruling that asked
for this check forbids building it.

### `:re-frame.hicasso/unkeyed-mapped-child` — warning

A `for` / `map` / `mapv` / `keep` / `map-indexed` sitting **directly in a
children position** of a literal hiccup vector, whose element expression is a
literal keyword-headed vector that provably writes no `:key`.

Provably: either its props are a map literal without `:key`, or position 1
holds a literal that cannot be a props map at all. A `:&` remainder cannot
supply the key — `key` is a structural slot no merge may reach — so a props map
carrying only `:&` still counts as missing it.

**Refuses to know:** what a symbol at position 1 evaluates to. `[:li item]` may
be an element with dynamic props carrying the key, or an element with one
child; the codec decides at runtime with `map?`. That is the *commonest*
missing-key spelling and it is deliberately silent, because the alternative is
firing on correct code. Also silent: an element whose head is a symbol (a view
or host, indistinguishable here from an ordinary data vector), a mapping form
behind a `let`, and `#(…)` as the mapping function, whose element expression is
not at a fixed position.

### `:re-frame.hicasso/nameless-interactive-element` — warning

`[:button {…}]`, or `[:a {…}]` **that carries an `href`**, with **no children
at all** and none of `:aria-label`, `:aria-labelledby` or `:title`. Such an
element has no accessible name and a screen reader announces it as an
unlabelled control.

The tag set and the `href` condition are taken from this project's compiled
substrates rather than invented here — `re-frame.ui.compiler.a11y`'s
`:rf.ui.compile/a11y-missing-accessible-name` names a `<button>` always and an
`<a>` only when it is a real link — so the two agree about what a nameless
control is. An `<a>` without `href` is not focusable and not a link.

**Refuses to know:** what any child renders. One child of any kind — even a
symbol that turns out to be an icon with no text — makes this silent, because
it may well render text. Dynamic props answer the same way: the name may be in
there. `:input`, `:select` and `:textarea` are in that compiler pass's set and
deliberately absent here: their name usually comes from a sibling
`<label for=…>`, which is a fact about the tree rather than about the element.
The real accessibility pass is a separate piece of work; this is one always-
right corner of it.

## Checks that are NOT here, and why

Each of these looks obviously worth adding, and each needs knowledge a
clj-kondo hook does not have. They are listed so the next person can skip the
same afternoon.

| Wanted check | Why it is not here |
|---|---|
| **Direct invocation of a `defview`** — `(todo-row {…})` instead of `[todo-row {…}]` | Needs to know that a symbol resolves to a var minted by `defview`, usually in another namespace. A hook sees one form. `clj-kondo.hooks-api/ns-analysis` can answer it from the analysis cache, which makes the check fire in a full CI run and stay silent in an editor linting one file — a rule that fires *sometimes* is worse than one that never does. |
| **A plain function in head position, by symbol** — `[helper {…}]` where `helper` is a `defn` | Same problem, same answer. The literal-function slice above is the part that is decidable. |
| **A deferred read in general** — a `sub` reachable from any callback, timer, promise or lazy escape | Read extent is a property of *execution*, and the runtime owns it. Lint can only see the one position (`hfn`) whose deferral is syntactic. |
| **A `sub` naming an unregistered query id** | Registration is a whole-program fact, and ids are frequently built rather than written. |
| **`:&` forwarding something that is not a map** | Only literals are decidable. See the check above. |
| **An `:input` without a label** | The name comes from a sibling or ancestor. That is tree knowledge, not form knowledge. |
| **Hiccup written in an ordinary `defn` helper** | Not a decision — a limit. Hooks fire only at registered call sites, so every check here sees hiccup inside `defview`, `defhost` and `hfn` forms and nowhere else. A helper that returns hiccup is unlinted. |

[rf2-djxr]: the ruling that the runtime ratifies this limit rather than chasing
it — trust the programmer, assistance and guardrails, no enforcement machinery.
