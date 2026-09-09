# Sizing a Hicasso-shaped adapter (`rf2-hvr5h`)

> **RULED AND BUILT.** The hold this page was written under is discharged: the
> operator ruled *build it* on 2026-08-19 01:05 AUSEST, on this page's own
> finding that the sizing came back small, and `re-frame.hicasso.substrate`
> shipped the same night. The page stays as the sizing it was, with the build's
> corrections recorded in [§What the build found](#what-the-build-found) rather
> than folded back into the prose — a prediction is only worth keeping if what
> happened next is beside it.
>
> **A sizing. No adapter was built, no contract changed, and no ruling is taken
> here.** Whether to *ship* a Hicasso-shaped adapter is public surface with a
> maintenance tail, and it is held for the operator. What is released — because
> it is true under either answer — is the measurement below: what such an
> adapter would have to implement, what already exists, and what it would cost
> in bundle bytes and test surface.
>
> Written 2026-08-18 22:27 AUSEST. Everything under **Read at source** was read
> in the tree; everything under **Reasoned** is argued and says so.

## The defect being sized

A reader following the Hicasso install chapter writes two dependency
coordinates, and the second exists only to supply an **adapter**:

```clojure
{:deps {day8/re-frame2-hicasso {:local/root "..."}
        day8/re-frame2-uix     {:local/root ".../adapters/uix"}}}
```

Hicasso mounts through `react-dom` itself and interprets its own Hiccup, but
`h/mount!` ensures a frame, `rf/make-frame` asks the **adapter** for a state
container, and there is no adapter unless the consumer installs one. So an app
built on the view layer described as *re-frame-native* takes a dependency on
`com.pitch/uix.core`, whose notation it never writes. Reagent and reagent-slim
are the same trade in a different currency.

This is not a bug. Everything works and is documented. It is a packaging and
first-impression question, and the audience that meets it first is the pilots.

## The contract: six required functions

**Read at source** — `spec/006-ReactiveSubstrate.md`, §The adapter API contract,
*Normative contract*. The surface is ten functions: six required, three
optional, one lifecycle, plus the `:kind` discriminator.

| # | Required fn | Purpose |
|---|---|---|
| 1 | `make-state-container` | Create a reactive container holding an `app-db` value |
| 2 | `read-container` | Read the current value (pure) |
| 3 | `replace-container!` | Mutate the container — the only mutation primitive |
| 4 | `make-derived-value` | Construct a derived (memoised) container from one or more sources |
| 5 | `render` | Render a render-tree onto the substrate's surface; return an unmount fn |
| 6 | `render-to-string` | Pure render to an HTML string |

Note two things the bead's framing does not: `subscribe-container` is
**optional**, not one of the six (the core falls back to inline invalidation
inside `replace-container!`), and `render-to-string` **is** one of the six.
`register-context-provider` and `flush-render!` are the other two optional
entries; `dispose-adapter!` is the lifecycle one.

## What the headless plain-atom adapter already satisfies

**Read at source** — `implementation/core/src/re_frame/substrate/plain_atom.cljc`,
whose adapter map is 10 entries wide, and
`implementation/core/src/re_frame/substrate/atom_container.cljc` (35 lines),
which supplies the container quartet it shares with the test-react adapter.

| # | Required fn | Plain-atom status |
|---|---|---|
| 1 | `make-state-container` | **Satisfied** — `atom-container/make-state-container`, a `clojure.core/atom` |
| 2 | `read-container` | **Satisfied** — `atom-container/read-container` |
| 3 | `replace-container!` | **Satisfied** — `atom-container/replace-container!` |
| 4 | `make-derived-value` | **Shape only** — this is the crux; see below |
| 5 | `render` | **Refused by design** — throws `:rf.error/render-on-headless-adapter`, telling the caller to use `render-to-string` |
| 6 | `render-to-string` | **Satisfied** — delegates to the late-bound hiccup emitter |

So four of six are already there, one is deliberately absent because the adapter
is headless, and one is present in shape but not in behaviour.

### The crux, stated precisely

The plain-atom `make-derived-value` reifies `IDeref` and the re-frame-owned
`IDisposable` — and nothing else. Its own comment says why: *"No caching:
derived values recompute on every deref."* It registers no watch on its
sources, so it notifies nobody, which is exactly right for SSR (a sub runs a
handful of times per request) and exactly wrong under a live view. That is why
the Hicasso witnesses and the editor example both open with
`(rf/init! uix-adapter/adapter)`.

The bead frames the question as *what would an `IWatchable` plain reactive
container cost?* — and that framing, taken literally, points at the expensive
path: writing a reactive graph with dependency tracking, watch registration,
glitch-free propagation, memoisation and disposal. That is genuinely real
machinery, and if it had to be written the honest answer would be *document the
dependency and move on*.

**It does not have to be written. It already exists, in core.**

## The finding that reframes the bead

**Read at source** — `implementation/core/src/re_frame/substrate/spine.cljs`
(3762 lines), whose own docstring opens:

> Shared substrate-spine helpers for React-shaped adapters that lack a native
> reactive-atom primitive (UIx and any future minimal-React-wrapper substrate).

Hicasso *is* a minimal React-wrapper substrate. The spine was written for this
case. It provides the plain-`atom` container quartet, a `make-derived-value`
that wires **one watch per source** and coalesces through a per-adapter epoch
scheduler so a multi-input derived value recomputes glitch-free and notifies
once per coherent input epoch, the React 18+ root renderer (`createRoot` plus
`hydrateRoot`), the hiccup-emitter cell and `render-to-string`, `flush-views!`,
and the source-coord wrapper.

`spec/006-ReactiveSubstrate.md` records the consequence directly: the
React-hook spine's derived value *"is live the moment it exists"* and needs no
activation hook, unlike the demand-driven ratom family.

So the reactive container the bead asks about is already written, already
push-based, already in core, and already the thing the UIx adapter uses.

### What a substrate actually supplies

**Read at source** — `spine/make-react-spine` takes exactly seven inputs:

- `:substrate-name` — a string, used only in warning text
- `:gensym-prefix-sub`, `:gensym-prefix-derived`, `:gensym-prefix-use-sub`
- `:use-memo`, `:use-callback`, `:use-context` — three hook functions

and returns a map already containing all six required contract fns.
`spine/make-react-adapter` then takes that map plus `:kind` and
`:frame-provider` and assembles the adapter. Its docstring states the rule:

> every React-hook adapter calls this with the same shape — the only inputs are
> their already-substrate-specific `spine-fns` map, `:kind`, and native
> `:frame-provider`.

The whole UIx adapter is **211 lines**, and most of that is docstrings. Its
substrate-specific content is three React hooks routed in from
`uix.hooks.alpha`, two native `defui` components, and one `set!` pinning UIx's
controlled-input behaviour. The `defui` components are native *only* so that
UIx's own prop and trailing-child marshalling is preserved — a
notation concern, not a reactivity one.

Hicasso already imports `react` directly, already calls React hooks
(`useSyncExternalStore` is the whole of its client-only gate), and already
renders a frame provider over the shared context object every React-shaped
adapter reads (`re-frame.adapter.context/frame-context`, written by
`re-frame.hicasso.impl.mount/provider`). Every ingredient
`make-react-spine` asks for is something Hicasso has in hand.

**Reasoned, not built:** on that evidence a Hicasso adapter is a small
assembly namespace in the shape of the UIx one — a spine call supplying
React's own `useMemo` / `useCallback` / `useContext`, a `:kind`, and the
frame-provider Hicasso already has. It is a wrapper, not machinery. The
sizing does not extend to *proving* it compiles; nothing was built.

## Bundle size

**Read at source** — `re-frame.core` does not require `re-frame.substrate.spine`;
the spine is pulled in by whichever adapter requires it. Package boundaries do
the isolation, per the Conventions packaging argument: the wrong substrate is
structurally absent rather than dead-code-eliminated.

So the comparison is:

| Today | With a Hicasso adapter |
|---|---|
| core + hicasso + `day8/re-frame2-uix` + `com.pitch/uix.core` | core + hicasso |
| spine (via the UIx adapter) **and** the UIx runtime | spine only |

The spine is present in both columns, because it is what supplies the reactive
container either way. What leaves is `com.pitch/uix.core` — a compiler, hook
wrappers and element marshalling that a Hicasso app never invokes. Bundle size
therefore moves **down**, and no new machinery is added to move it back up.

**Not measured.** No release build was run for this sizing (a bench-class
measurement held the box). The directional claim rests on the dependency graph,
which is read at source; the magnitude is not established here.

## Test surface

**Read at source** — the React-adapter suite is already parameterised.
`implementation/core/test/re_frame/adapter/react_shared_suite.cljs` is 6740
lines in **core**, and UIx enrolls through a single 94-line entry file whose
docstring states the contract:

> any future React-hook adapter picks up the whole surface by adding one entry
> file like this one

That entry file reduces to an adapter `:require`, a fixture, a `cfg` map and one
macro call, from which roughly fifty `deftest` forwarders are generated —
dispose contracts, source-coord stamping, warn-once behaviour, the
write-after-destroy guard, `make-derived-value` per-arity and watch-baseline,
the late-bind hook publication set, and the public-surface guard.

The UIx adapter carries nine test files in total. The eight beyond the shared
entry are substrate-notation specific — controlled-input defaults, `defui`-shaped
provider and hook tests — and Hicasso's own suite already covers the
equivalent ground for Hicasso's notation.

**Reasoned:** the marginal test surface is one enrolment file plus whatever
Hicasso-notation provider tests the review wants. The expensive suite is
already written and already in core.

## Verdict

The sizing comes back **small**, and it comes back small for a reason worth
stating plainly: the question the bead asks — *what does an `IWatchable` plain
reactive container cost?* — has the answer *nothing, because core already ships
one*. The plain-atom adapter's non-reactive derived value is a deliberate
property of a headless SSR/JVM adapter, not the base a Hicasso adapter would
build on. The base is the spine, which exists, is push-based, and was written
for "any future minimal-React-wrapper substrate".

On the mayor's own stated test — *build it, unless the sizing comes back large*
— this sizing does not trigger the fallback. The condition that would have
flipped the recommendation to "document the dependency clearly and move on" is
an `IWatchable` container dragging in real machinery, and that condition does
not hold.

Two honest cautions against it:

1. **It is still new shipped surface.** A published coordinate has a
   maintenance tail, a release-DAG slot and a version-lockstep entry, none of
   which the sizing above prices.
2. **Nothing was compiled.** The assembly is argued from two adapter factories'
   stated contracts and from what Hicasso already imports. It is a strong
   argument, not a green build.

The decision remains the operator's. Its cost, if taken, is a small assembly
namespace and one test-enrolment file — not a reactive engine.

## What the build found

`re-frame.hicasso.substrate` shipped on 2026-08-19 under the ruling above. Four
things the sizing said held, and one it did not price.

**The assembly is a wrapper, as predicted.** The namespace is a
`make-react-spine` call supplying React's own `useMemo` / `useCallback` /
`useContext`, a plain React function component for the contract's
frame-provider slot, and a `make-react-adapter` call with
`:kind :rf.adapter/hicasso`. Nothing else. It COMPILES, which the sizing said
explicitly it had not proved.

**The test surface is one enrolment file, as predicted.** Ninety-two generated
forwarders — the `:test` rows in the macro namespace's `test-specs` literal —
run against the Hicasso adapter from a single entry file. The focused Hicasso
CLJS lane measures 1326 tests / 5407 assertions, 0 failures, with the
enrolment in. That the forwarders genuinely target THIS adapter was proved by
sabotage rather than by reading the count: mis-declaring the entry file's
`:substrate-kw` reddened one generated test, `actual: (not (= :rf.adapter/uix
:rf.adapter/hicasso))`.

**The public-surface rows read the spine map rather than re-exported Vars.**
The shared suite's guard asserts seven Vars every other React-shaped adapter
re-exports; Hicasso re-exports none, deliberately, because a body reads through
`h/sub` and a second read path would be a second commit discipline. The guard's
load-bearing half survives the difference — the spine produced all seven, each
is fn-shaped, no two are the same object — and that is what catches a
cross-wired spine key.

**It is an OPTIONAL module, which the sizing did not consider.** Nothing under
`src/` requires it, so an application that deliberately installs Reagent,
reagent-slim or UIx under a Hicasso tree pays no spine bytes. That is why the
adapter is not re-exported from the public door, and
`check_optional_module_reachability.py` carries the row that keeps it true.

**WHAT THE SIZING DID NOT PRICE: the registration tail.** A shipped adapter
kind is REGISTERED SURFACE in rosters the sizing never looked at, and enrolling
in the shared suite reaches one of them. Measured while building:

| Roster | What it needs | Landed with the adapter? |
|---|---|---|
| `re-frame.late-bind.directory/hooks` (core) | `re-frame.hicasso.substrate` on eight hook entries' `:producer-ns`; the suite's directory cross-check asserts it | yes |
| `implementation/hicasso/scripts/check_naming_census.py` + the naming ledger | the eleventh shipped namespace, and a row for its two public names | yes |
| `check_optional_module_reachability.py` | the module row above | yes |
| `check_guide_samples.py` | four re-pinned digests in `00-installation.md` | yes |
| Spec — Conventions §Reserved namespaces, 006 §CLJS reference scope, 006 §Adapter introspection, API.md's `current-adapter` row | `:rf.adapter/hicasso` as a canonical `:rf.adapter/*` member | **NO — spec is one-toucher and was fenced off this work** |
| `tools/xray`'s `react-element-render-kinds` | the new kind, so Xray refuses a Hicasso host cleanly instead of mounting a hiccup shell into an element-shaped `:render` | **NO — outside the fence** |
| `tools/story`'s `schemas.cljc` docstring | it states *there is no `:rf.adapter/hicasso` anywhere in the repository, deliberately*, which the adapter falsifies | **NO — outside the fence** |

The two `tools/` rows are the ones that matter, and the Xray one is functional
rather than editorial: its refusal set is keyed on the kind, and its own
docstring reasons from Hicasso minting none. Until it carries an entry, a page
that installs this adapter and runs Xray gets an uncaught React child error
where it used to get a clean diagnostic. Nothing regresses for a page that
keeps installing UIx.

None of this makes the sizing's verdict wrong — the tail is a dozen lines
spread over seven files, against an assembly that is genuinely a wrapper. It
makes the sizing's *scope* narrow: it priced the code and not the registration.

### The bundle delta is STILL NOT MEASURED

The ruling asked for the number and this build did not produce it. A
`shadow-cljs release` build contends for the machine exactly as a bench run
does, a bench-class measurement held the machine for the whole of this work,
and two heavyweight runs wedge rather than fail. So the honest state is
unchanged from the sizing: the DIRECTION is read off the dependency graph and
`com.pitch/uix.core` leaving is what moves it down; the MAGNITUDE has never
been measured, here or above, and no figure should be quoted for it. It is
tracked as its own measurement, to run when the machine is free.

## See also

- [decisions.md](decisions.md) — the normative decision record for the programme
- [charter.md](charter.md) — product identity and constraints
