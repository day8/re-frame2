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

### `:re-frame.hicasso/direct-view-call` — error

A view calling **itself** like a function — `(todo-row {…})` written inside
`(defview todo-row …)`, where `[todo-row {…}]` was meant. A view is a minted
component and a hiccup head. Called directly it returns the runtime's component
object instead of rendering, and no boundary is minted, so its reads belong to
whichever body called it.

**Refuses to know:** that any *other* symbol names a view. `(some-other-view …)`
needs to resolve a var minted by `defview` in another namespace, and a hook sees
one form — see the table at the end for why the cache-backed version of that is
worse than nothing.

It also refuses any call whose name has been **rebound**. A parameter, a
destructured prop, or a `let`, `for`, `fn`, `hfn`, `letfn` or `catch` binding
spelled like the view introduces an ordinary local, and calling a local is
ordinary Clojure:

```clojure
(h/defview card [card]                    ; the parameter, not the view
  [:div.card (card)])
```

A `letfn` fnspec is read as the `fn` tail it is — `letfn` expands each one to
`(fn f …)` — so a local function's name and **every parameter of every arity**
are locals, the multi-arity spelling included.

Shadowing is read off the form in hand, because that is the only kind of fact
available here: `api/resolve` answers `nil` for the view's own name — not yet a
var when the hook runs — and never sees locals at all. A binding silences the
entire form it heads, initialisers included, so a genuine self-call written in
the initialiser of a `let` that goes on to bind the view's name is missed too.
Missing one is the only way this check is allowed to be wrong.

**Refuses to know** — and this direction is the one that costs you something —
that a form *outside its roster* binds anything. Having no scope table, the
hook recognises binding forms by **name**, and the roster is:

* the **`let`-shaped** forms, whose first argument is a binding vector —
  `let`, `loop`, `when-let`, `if-let`, `when-some`, `if-some`, `when-first`,
  `doseq`, `dotimes`, `for`, `with-open`, `with-local-vars`, `binding`,
  `with-redefs`;
* the **`fn` tails** — `fn`, `fn*`, `hfn`, every fnspec of a `letfn`, every
  method of a `reify`, `specify!`, `extend-type` or `extend-protocol`, and a
  `defmethod`'s tail. Every parameter of every arity, in each case, and a
  local function's or method's own name;
* the **single names** bound by `catch`, `as->` and `this-as`.

A local introduced by anything else is not seen, so calling one *does* report.
The notable absentees are `deftype`, `defrecord`, `proxy` and `specify` — the
last because clj-kondo does not read it as a spec-bearing form either, so its
method parameters are not locals to kondo's own analysis, and the first three
because a hook fires only at registered call sites: it never sees anything but
a `defview`, `defhost` or `hfn` form, and a type defined inside a view body is
not a program anyone has. If you have one, name your local something other
than the view, or switch the check off.

### `:re-frame.hicasso/deferred-read` — warning

`h/sub` or `h/use-subs` read where nothing is going to call the surrounding
function during this body. Two shapes:

* inside an `hfn` body. `hfn` **is** the callback form: its whole contract is
  that it runs after the body that wrote it, so a read there is deferred by
  construction rather than by circumstance, and the runtime refuses it with
  `:rf.error/hicasso-sub-outside-render`.
* inside a plain `(fn …)` or `#(…)` that is *not* handed straight to `map`,
  `mapv`, `for`, `keep`, `filter`, `reduce`, `run!`, `doseq` or another core
  form that calls it synchronously — a callback, a timer, a promise, a thunk
  stashed for later.

**Refuses to know:** whether the second shape is really deferred, which is why
it is a warning and why nothing here blocks a build. A read inside a `(fn …)`
handed to `mapv` or `for` runs *during* the body and is completely legal — a
helper may donate reads to the boundary that called it — so those are named and
exempt. `(some-helper (fn [] (h/sub …)))` may well call its argument during the
body too, and this cannot know; if it does, ignore the warning. Proving read
extent in general is the runtime's law, not lint's.

It also refuses a read whose **door has been rebound**, and so does
`parked-read`. `:refer [sub]` leaves an ordinary simple symbol behind, and a
local may take that name like any other; `api/resolve` answers with the var
either way, because it reads your `ns` form and never sees locals. Both read
checks therefore consult the same shadowing roster the self-call check above
uses — bluntly, though: a body that binds `sub` anywhere silences the bare
spelling for that **whole body**, rather than for the form that binds it. The
blunt reading costs a missed warning and nothing else, which is the only
direction these are allowed to be wrong. A qualified `h/sub` is untouched,
because nothing is ever named `h/sub`.

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
`(reset! r (make-thunk))`, and so is a `swap!` that assoc's a thunk into a map;
and so, per the note under `deferred-read` above, is any read written through a
door spelling some local in the same body has taken.
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
| **Direct invocation of *another* view** — `(todo-row {…})` where `todo-row` is defined elsewhere | Needs to know that a symbol resolves to a var minted by `defview`, usually in another namespace. A hook sees one form. `clj-kondo.hooks-api/ns-analysis` can answer it from the analysis cache, which makes the check fire in a full CI run and stay silent in an editor linting one file — a rule that fires *sometimes* is worse than one that never does. The self-call slice above is the part a hook can settle on its own. |
| **A plain function in head position, by symbol** — `[helper {…}]` where `helper` is a `defn` | Same problem, same answer. The literal-function slice above is the part that is decidable. |
| **A deferred read in general** — a `sub` reachable from any callback, timer, promise or lazy escape | Read extent is a property of *execution*, and the runtime owns it. Lint can see only the `hfn` position, whose deferral is syntactic, and the fn literal nothing standard is about to call — and the second of those is advice, not a verdict. |
| **A `sub` naming an unregistered query id** | Registration is a whole-program fact, and ids are frequently built rather than written. |
| **`:&` forwarding something that is not a map** | The `:&` grammar key is retired from the end-state design in favour of the owned-wins merge recipe. A lint layer must not police a ghost. |
| **An `:input` without a label** | The name comes from a sibling or ancestor. That is tree knowledge, not form knowledge. |
| **Hiccup written in an ordinary `defn` helper** | Not a decision — a limit. Hooks fire only at registered call sites, so every check here sees hiccup inside `defview`, `defhost` and `hfn` forms and nowhere else. A helper that returns hiccup is unlinted. |

[rf2-djxr]: the ruling that the runtime ratifies this limit rather than chasing
it — trust the programmer, assistance and guardrails, no enforcement machinery.
