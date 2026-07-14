# 12 — How it works

The rest of the guide states contracts: `sub` is the value, props memoise by value,
the two emitters cannot drift, production carries nothing it does not use. This page
explains the machinery that keeps those promises — read it when you want to *trust*
the claims, not just use them. Nothing here is required to build apps; everything
here is why the other pages can be short.

## The compiler

`defview` does not produce a function that *interprets* hiccup — it lowers the
template at build time. Element vectors become direct element construction; `:class`,
`:style`, and attribute casing convert at compile time under one rule table; branches
(`if`, `when`, `cond`, `case`, `let`, `for`) normalise into the compiler's AST, so
every analyser and both emitters see through them. Subtrees the compiler can prove
inert hoist to module constants and are built once, at load.

This is also why the template grammar is closed. A dynamic tag head, an unkeyed list,
a `sub` inside a loop, an unaudited macro in expression position — each would put
something into your view the compiler cannot see through, and everything downstream
(the manifest, the memo comparators, the server emitter, Xray's static inspector)
depends on the compiler seeing the whole template. The loud compile errors are not
taste; they are the price of the machinery on this page. The practical catalogue —
what is illegal, what to write instead, and the explicit escapes — is
[14 — What the compiler forbids](14-compile-time-limits.md).

Because the interesting work happens at compile time, your bundle contains no hiccup
walker, no tag parser, no runtime prop converter on compiled paths — there is no
interpreter to pay for, which is most of [10](10-performance.md)'s table.

## The build digest and the registry

Every `defview` registers the view — its source coordinates, template fingerprint,
and capability profile — and the compiler maintains one whole-build summary of all of
them: the *build digest*. Hydration checks it; debugging surfaces trust it.

The two `shadow-cljs.edn` settings from [01](01-getting-started.md) exist to keep the
digest truthful across a build daemon's lifetime: the build hook clears retained
output for macro consumers on a daemon's first pass and carries the candidate digest
through the build; the cache blocker stops a stale disk cache from resurrecting a
pre-restart view of the world. An unsaved REPL evaluation can replace a view's live
body without changing build identity: the body is live immediately; the digest
advances when you save and the next pass completes.

## The reactive core

A view with five `sub` sites does not have five subscriptions in React's eyes. All of
a view's read sites share one **ViewCell** — a single `useSyncExternalStore` bridge
with a single integer revision as its snapshot. That is the "one React bridge per
view" line in [10](10-performance.md), and it is why `sub` may sit in a branch: sites
are compile-indexed slots on the cell, not hooks in a positional list.

The lifecycle of a read is split in two:

- **During render** the site *resolves* what it is reading (frame + query) and
  *probes* it — a pure read of value and version. Rendering acquires nothing and owns
  nothing, so an abandoned render, a StrictMode replay, or a time-sliced pass can
  never leak a subscription.
- **At commit** — when React has decided this render is real — the cell acquires
  ownership of exactly the identities the committed render read, compares what it
  acquired against what the render probed, and if anything moved in the gap, corrects
  *before paint*. New sites are acquired before old ones release, so a shared
  subscription never falls through a zero-owner gap during retargeting.

That render/commit split answers a family of questions the other pages wave at: why
parametric queries switch atomically, why you never see one frame of order A's data
against order B's id, why Activity hide releases everything and reveal reacquires and
corrects, and why frame destruction can mark cells dead loudly.

On the write side, every dispatched event runs the ordinary re-frame2
run-to-completion drain. Only when the drain reaches quiescence does each dirty cell
advance its revision — once, no matter how many of its sites changed — and React gets
one render batch for the views whose values actually moved. Unchanged derivations
return identical references, so child memo comparators short-circuit. One drain, one
notification per affected view, one commit per root: that is
[10](10-performance.md)'s model, mechanically.

## Memoisation that is correct, not heuristic

Every view compiles with a straight-line comparator over its declared prop slots. The
comparator is `rf=`: identity first, value equality for CLJS data, honest fallback to
identity for host values. It can be *correct* because the inputs are values by
construction: props are CLJS data, handlers are vectors, children are realised
elements. This is why the guide keeps insisting on data handlers: a closure prop does
not break memoisation mechanically; it breaks the *idiom* that makes memoisation mean
something.

## Events without closures

A literal vector in `:on-click` compiles to a *site*: the vector is retained as data
(in the manifest, on the JVM tree, in dev tooling), and the client emits one stable
per-site callback that reads its **committed** slot values when the event fires.
Placeholders (`:rf.ui/value` and friends) splice at dispatch time — which is why they
only work in literal vectors: they are compiled, not interpreted.

Controlled inputs add the one deliberate exception to batching. Where the compiler
can prove an element controlled — a literal `:value`/`:checked` beside a vector
handler — dispatches from that site drain *synchronously inside the DOM event*:
event → drain → commit → the new value is back in `:value` before React's re-render.
That ordering is the entire caret/IME story; nothing about it is configurable because
it is a proof obligation, not a preference.

## One template, two emitters

The compiler's AST feeds two emitters: direct element construction for the browser,
and a string emitter for the JVM. They share the single conversion and escaping rule
table — the same code decides what `:style {:cursor :pointer}` means on both sides —
so client and server output are structurally equivalent by construction, and a
fingerprint of the emitted structure rides along so hydration can *check* rather than
hope. This is why [11](11-ssr.md) can say "no dual-emitter drift" flatly: there is no
second implementation to drift.

## Hot reload, mechanically

`defview` exports a stable component shell keyed by the view's id; the registry holds
the current body. Re-evaluating a namespace replaces the body and bumps a generation;
the shell's identity never changes, so React state and cell identity survive. The
compiler hashes each view's ordered hook signature: same hash, the mounted view
renders the new body in place; different hash, that subtree remounts deliberately —
never a corrupted hook order. Frames sit outside all of this: ENSURE runs at host
preflight, finds the frame live on reload, and does not re-seed — which is exactly
the "your state survives edits" behaviour [01](01-getting-started.md) promises. The
Pair's hot-swap is this same mechanism, invoked over nREPL.

## The dev/prod split

Dev and prod run the same semantics with different amounts of evidence. In dev, every
view carries its manifest, every commit its causes, every element its source
coordinates — that is [09](09-debugging.md). In production, each component carries
only the machinery its own source implies: the compiler records a capability profile
per view and specialises the output, so a props-only view is a memoised function
making direct element calls and nothing else. The debug tier is not flagged off; it
is *absent*, and CI proves the absence. The kernel that remains is budgeted at
≤ 4 KB gzipped over React, and the budget is a gate, not a hope.

## Where to go deeper

The design documents one directory up carry the full contracts this page summarises —
written for people changing the library rather than using it. If a claim on this page
seems too good, that is where its proof obligations live.
