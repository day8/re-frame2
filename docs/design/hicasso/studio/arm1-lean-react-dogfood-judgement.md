# Arm 1 — lean-React: the mechanism, and the three-rendering dogfood judgement

**Bead:** `rf2-2rtt6.9` · **Arm:** Hicasso lean-React
([architecture.md](../architecture.md) Arm 1) · **Branch:** `worker/arm1-2rtt6-9`

> **Status, 2026-07-31: this is no longer a tournament arm — it is the product
> line.** The operator ruled that Hicasso is "an adaptor for React that is
> optimised for re-frame2, user ergonomics and performance", and dropped Arm 2
> (PATCH) on product direction rather than on measurement — it had met its hard
> gate. The competitive framing below (parity gates against a rival, the
> like-for-like fences) was written before that ruling and is left standing
> because the evidence is unaffected: a canonical-DOM parity gate against the
> raw-UIx control is worth exactly as much when the control is a comparator as
> when it was a rival.
>
> Two consequences the ruling makes sharper rather than softer. **Surface B is
> the only acceptable read surface**, so if the collector cannot be made correct
> the answer is NULL — grouped is not a fallback. And **HD-002(a)'s ownership
> state machine is fully live here**: React owns the render phase in this
> architecture, so every read is a candidate until a commit adopts it, and the
> candidate-ledger tripwire is a real kill signal rather than a formality. §2
> below is that clause discharged, and §2's closing paragraph names the nearest
> approach to the fence.

> **This page publishes no bar row.** The clock gate lines are being re-taken
> (`rf2-b0tz5`), and this arm's candidate rows are scored against the restated
> bar when it is ready. Everything below is either a *mechanism* description, a
> *correctness* witness, or an *ergonomics* judgement — none of it is a clock or
> a retained-heap figure quoted against a red-zone.

The runtime skeleton stays **off main**, on the spike branch, per HD-017. What is
on this page is the record the P2 ruling reads.

---

## 1. The shell, and how the ≤2-hook budget was actually reached

HD-020(b) makes the budget a hard line: two hooks, and `useRef` banned in the
shell. The obstacle is not the third hook — it is that React offers a function
component **no per-instance storage except a hook cell**, so a shell that wants
one has already spent a third hook before it starts. Every real
`useSyncExternalStore` wrapper in the wild, including this repository's own
spine, holds three `useRef`s.

The way out is to notice what the two closures need to close over.

| Closure | Closes over | Consequence |
|---|---|---|
| `subscribe` | the render's sub-key **set**, and nothing else | a pure function of a value, so it is cached by that value and **shared** by every boundary reading the same set |
| `getSnapshot` | the same set | returns the sum of those keys' epochs — monotone, so `Object.is` on one number is a correct change test with no memo |

Because `subscribe` is identity-stable exactly while the read set is unchanged,
React's own re-subscribe condition (`prevSubscribe !== subscribe`) becomes
"the read set changed" — which is the *only* thing that should trigger edge
maintenance. React then calls that shared closure once per **fiber**, handing it
that fiber's own `onStoreChange`; the registration minted inside is therefore
per-boundary, durable for the mount, and commit-owned. It is the boundary id the
index keys on, and a render that never commits registers nothing.

So the shell is:

```clojure
(let [frame-kw (react/useContext adapter-context/frame-context)   ; hook 1
      element  (render-body frame-kw body-fn props)               ; not a hook
      entry    (.-entry rstate)]
  (react/useSyncExternalStore (.-subscribe entry) (.-snapshot entry) …) ; hook 2
  element)
```

The body runs *between* the hooks. That is legal because what React fixes is
hook **order and count**, not the position of ordinary code around them — and it
is what lets the subscription hook close over the reads the body just made.

### The budget is measured, not declared

`arm1_hook_ledger_dom_cljs_test` replaces React's shared-internals dispatcher
slot with a counting `Proxy` and reads back what React was actually asked for.

| Boundary | Reads | Hooks React was asked for |
|---|---:|---|
| Hicasso shell | 1 | `["useContext" "useSyncExternalStore"]` |
| Hicasso shell | 7 | 2 |
| Hicasso shell | 20 | 2 |
| Raw UIx (`use-subscribe` ×1) | 1 | strictly more than the whole Hicasso shell |
| Raw UIx (`use-subscribe` ×3) | 3 | more again — the count rises **with** the read count |

No `useRef`, no `useState`, no `useMemo`, no `useCallback` in the shell. The
probe counts what the *body* calls: `useSyncExternalStore` is one dispatcher
call, and React's internal machinery for it does not go back through the slot.
When React's internals slot cannot be found the witness records **UNWITNESSED
and fails** — it never reads as a pass.

---

## 2. The collector, against HD-002's four clauses

The operator ruled on 2026-07-31 that the ambient collector (Surface B) is the
only read surface acceptable on ergonomics, and that grouped `use-subs` is below
the usability bar. The collector is therefore the surface this arm engineers for.
That inverts which tier is *defended*; it waives none of the correctness gates,
and [the adjudication](../hd-002-adjudication.md)'s tripwire still overrides the
clock.

### (a) The ownership state machine

One sentence carries it, and it is the positive form of the tripwire:

> **No render-phase code mutates the index or a subscription ref-count. The only
> global mutation is the commit's.**

| State | What holds |
|---|---|
| `RENDERING` | reads append to **one** module-level scratch array, reset by overwrite at the top of every body |
| `PENDING` | the body returned; the scratch has resolved to a cached read-set entry; the index is untouched |
| `COMMITTED` | React called the entry's `subscribe`: the registration exists, edges are installed, cells acquired |
| `UNMOUNTED` | React called the cleanup; edges and references released |

An abandoned render is `PENDING → RENDERING` and needs **no cleanup**, because it
never did anything that would need cleaning. StrictMode's double-invoke is
correct for the same reason: the reset is unconditional, so two runs replace
rather than concatenate.

Witnesses: `a-render-mutates-neither-the-index-nor-a-reference`,
`a-re-render-before-the-commit-destroys-the-previous-candidate-by-overwrite`,
`a-body-that-throws-leaves-nothing-behind`,
`a-render-that-never-commits-leaves-nothing-behind`.

### (b) The allowed edge-diff operation

A boundary's edge set is **replaced wholesale, once, at commit** — and only when
the read set actually changed, because an unchanged set leaves `subscribe`
identical and React does not call it again. **The unchanged case is detected
without building anything:** the scratch array is compared pairwise against the
cached entry's key array, so steady-state allocation for edge maintenance is zero
bytes.

**The replacement is `unmount-all` + `mount-all`, and this page used to say it
was `record-reads`'s set difference.** That was wrong about the running path
(rf2-2rtt6.47). A boundary id here is the registration object React mints inside
`subscribe`, so a changed read set means a new entry, a new `subscribe` and a new
id; the index installs `#{}` for it, and the `record-reads` that follows sees
`held = #{}` — `added` is the whole read set and `dropped` is always empty. The
narrowing is the *previous* registration's cleanup calling `unmount`, which drops
every edge outright. The difference is proved in
`front/sub_index_laws_cljs_test`, and on this wiring it is delivered by the
`unmount`/`mount` pair; `the-wired-path-never-takes-the-diffs-dropping-half`
pins that, so the two stop diverging.

The cost follows and is not the "identity change and one replacement" §4 of this
page priced it as. For an `n`-read boundary with one key changed: `n` releases
(including the `n-1` that did not change), up to `n` armed reapers, an entry miss
that builds a key array, a key set and two closures, `n` re-acquisitions, and two
whole-map rebuilds of `:sub->bs`. **There is no cheap route for "19 of 20 keys
unchanged."**

A durable per-boundary id would make the difference live. It is unavailable at
this arm's fences rather than merely unbuilt: it must survive a re-subscribe, so
it cannot live on the registration; the shell has no per-instance storage that is
not a hook, and a third hook breaks the HD-020 budget the ledger witness
enforces; and threading one into `subscribe` would end `subscribe`'s property of
closing over the read set and nothing else, which is what makes it shared and
identity-stable. Buying the diff costs the two properties the arm exists to
demonstrate, so it is not bought — and the finding is recorded here rather than
left as an unexplained absence.

The ordered compare is a **false-negative device, never a wrong answer** — two
renders reading the same keys in a different order miss the compare, take a
second entry with the same set, and React replaces a set with itself. The compare
is deliberately not *replaced* by a content hash, whose failure mode would be a
silently missing edge; a hash does choose which entries the compare runs against
(rf2-2rtt6.46), where a collision costs a second entry and never a wrong one.

Against the forbidden list: no per-read object; one scratch and never two;
nothing keyed by render, attempt, lane or generation; no registry of in-flight
renders; **no commit-phase re-read of subscription values**.

Witnesses: `an-unchanged-read-set-is-detected-without-building-anything`,
`a-changed-read-set-takes-a-different-subscribe-identity`,
`a-boundary-holds-exactly-the-edges-its-latest-commit-installed`,
`the-wired-path-never-takes-the-diffs-dropping-half`,
`the-bucket-scan-does-not-grow-with-the-number-of-boundaries`,
`reading-one-key-twice-is-one-edge`,
`a-warm-read-performs-no-new-attach-or-release`.

### (c) The two pre-registered hypotheses — one consumed as *implemented*, neither yet as *benchmarked*

The counting rule is explicit: a hypothesis is consumed when a commit implements
it **and** a bench row on the standard witness set cites that commit SHA and its
reproduction command. Both halves, or it is not consumed. **No bench row exists
yet**, so on the rule as written **neither swing has been spent.** What is
recorded here is the implementation half, so the later bench row has something to
cite.

- **H1 — commit-side ordered compare.** Implemented, in the form the mechanism
  above describes. It differs from the adjudication's sketch in *where* the
  compare sits: the page imagines a commit-phase pass over the scratch, and this
  arm does the compare when the scratch resolves to its entry, which lets the
  commit do **no work at all** in the unchanged case rather than a cheap pass.
  That is strictly inside the allowed operation and strictly less work; it is
  recorded here because a reader comparing code to page should not have to
  wonder.
- **H2 — render-side positional verification.** Not implemented. Not abandoned
  either: it is untried, and the record says an unrecorded abandonment is how two
  swings quietly become five.

### (d) The survival metric

Two halves. **Zero retained per-occurrence objects after commit/teardown** is
witnessed now, at both seams and in both directions:

- at the **node seam**, `arm1_runtime_cljs_test` drives `commit-boundary!`'s own
  cleanup and asserts `{:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}`
  past the reapers' macrotask horizon;
- at the **React seam**, `arm1_dogfood_dom_cljs_test` and
  `arm1_generation_fence_dom_cljs_test` unmount the root and take the same
  reading — with the runtime untouched, so what they read is what React's own
  cleanup left.

The second bullet is a correction. Until rf2-2rtt6.48 those DOM gates asserted
after `mount/release!`, which resets the runtime *before* the reading: they
answered zero however teardown had gone, and no suite witnessed React-driven
release at all. `release!` now splits into `unmount!` + reset, the gates read
between the two, and `the-residue-reading-can-answer-false` mounts two roots and
unmounts one to show the repaired reading is live at the point the gates take it.

**The steady-state allocation slope across warm 1/3/7/20 reads** needs the bench
and is not taken here.

### The tripwire did not fire

No construct in this arm needs to survive from one render attempt to a different
render attempt of the same boundary, or to be told apart from another attempt's
version of itself. The nearest approach — and it is worth naming, because it is
one line from the fence — is the **read-set entry cache**. It is keyed by read-set
*content*, never by a render or an attempt; it is a cache whose eviction is a
macrotask reaper rather than a record of anything to undo; and dropping an entry
early costs one re-subscribe and no correctness. That is the allowed
"per-boundary reused scratch buffer" generalised to one buffer for the runtime,
not a second candidate slot.

---

### Laziness — a Surface B property, checked and refuted here

Arm 2 hit this in Chromium and flagged it across the tournament: **`for` returns
a lazy sequence**, so every `(sub …)` inside one runs when something walks the
seq, not when the body returns. A collector that closed at the body's return
would register *no edge for any row*, and would look perfectly correct on the
first render — the values are right once realised — while never updating again.
It is the hardest class of bug to attribute, because the first paint is clean.

**This arm does not have it, and the reason is structural rather than careful.**
The collector window closes around `codec/as-element`, and the codec is eager
everywhere it walks: `expand-seq` drives a seq to exhaustion, `realize-children`
folds one into a vector, a seq at a native prop position goes through `clj->js`,
and `realize-deep` forces every lazy sequence reachable from a **boundary's**
props before the crossing hands them on. A lazy read is therefore forced inside
the window by the same pass that turns hiccup into elements. No `doall`, no
`vec`, no widened window. The ≤2-hook budget is untouched.

The fourth clause is a repair. When this section was first written the list had
three, and its `clj->js` clause was read as covering the boundary hand-off as
well as the native prop position — it did not, and what escaped through the gap
is [below](#one-residual-case-and-one-that-was-not-residual).

Verified rather than argued, on both halves — because the first half alone is
exactly what a broken implementation also passes:

| Witness | Asserts |
|---|---|
| `a-lazy-for-registers-its-edges-and-its-readers-re-run` | all four edges (the ids query + one per row) are installed by the commit, **and** a write to a row query the `for` produced re-runs the boundary |
| `a-lazy-seq-returned-as-the-body-root-registers-its-edges-too` | the same at the root position, where no enclosing vector forces the walk |
| `reads-inside-a-lazy-for-update-the-dom-on-a-later-write` | through a real React commit: the first paint is right, **and** a later write reaches the DOM while the neighbouring row is untouched |

The tests are what keep the property true: moving the codec call outside
`run-once` fails the first of them.

**And the eager codec is only half of it.** A codec forces the reads it *walks*;
nothing can force a read the author deferred past the render — a handler closure,
a `delay`, a lazy seq stashed rather than returned. Each of those would otherwise
be a **silent** missing edge, which is the worst failure mode the ruled surface
can have: correct on screen, frozen thereafter, attributable to nothing. The
render frame is therefore set in a `try` and cleared in the matching `finally`,
and a read that finds none **fails loudly, naming the query**. Four escapes are
witnessed by `every-read-that-escapes-the-render-is-loud-rather-than-a-missing-edge`
— a stored handler, a handler actually invoked, an author-held `delay`, and a
stashed lazy seq forced after the render.

### One residual case, and one that was not residual

A fifth escape the `try`/`finally` guard cannot see: a deferred read forced
*inside another boundary's* render. `read-key!`'s guard asks whether **any** body
is running, not whether **this** one is — `rstate` is a single module-level
object — so the read does not throw. It lands on the rendering boundary's scratch
instead.

**This page previously called that "not a missing edge — the reader does
re-render — but the wrong reader", and left it there. That was wrong, and it was
wrong in the worst available direction** (`rf2-2rtt6.45`). A `LazySeq` caches what
it realised, so the wrong reader re-renders exactly *once*; on that re-render its
body walks an already-realised seq, `sub` is never called, its read set collapses
to the empty set, React re-subscribes, and every row edge is dropped. The right
reader never re-rendered, so the seq is never rebuilt. One correction, and then a
value that is **correct on screen, frozen thereafter, attributable to nothing** —
which is the class this section calls the worst failure the ruled surface can
have, sitting inside the section that says the arm does not have it.

And the structural argument above had a hole in exactly the place that made the
escape reachable. "A seq at a prop position goes through `clj->js`" is true for a
**native tag** — `convert-prop-value` sends any collection through it — and was
false for a **boundary**: `boundary-element` built `body-props` as a plain
ClojureScript map and handed it across untouched, and the shell read it back as a
map. No conversion, no walk, no realisation. `realize-children` compounded it by
flattening exactly one level, so a nested seq in a boundary's children survived
unrealised too.

Both carriers are closed by one call. `codec/realize-deep`, applied once to
`body-props` in `boundary-element`, forces every lazy sequence reachable from the
map — `:children` included, since it is a key in the same map — and returns the
map **by identity**, because realising a `LazySeq` caches into the seq rather
than rebuilding it. The read is then forced by the same pass that turns hiccup
into elements, inside the window of the body that *wrote* it, which is what the
eager-codec argument claimed all along.

The alternative — giving `read-key!` a per-boundary render token to refuse
against — was rejected and is worth recording as rejected. It is per-boundary
render identity, one line from the candidate-ledger tripwire, and it would turn a
legitimate authoring shape into an error rather than making it work. Realising at
the hand-off needs no such thing: `rstate` is still one object, nothing can tell
one render attempt from another, and the shell still holds two hooks and no
per-instance state.

| Witness | Asserts |
|---|---|
| `boundary-crossing-cljs-test` (8 rows) | at the seam: every row query the parent's `for` produced is the **parent's** edge, nothing lands on the child, and a write to a row re-runs the parent and not the child — plus carrier (b), a nested seq one level below the children splice |
| `boundary-crossing-dom-cljs-test` (2 rows) | in real Chromium: the first paint is right, **and** a later write reaches the DOM, its neighbour is untouched, the edges survive the re-render, and a second write moves it again |

Mutation-proved by removing the one call: node exit 1 (7 failures), browser exit 1
(4 failures — the cell frozen at its original text through two writes, and the
edge count collapsing from 7 to 1 on the first). Restored, both exit 0. The
first-paint assertions passed on the broken runtime, which is the whole reason
the second half is asserted.

**And the deferral no walk may repair is refused instead** (`rf2-2rtt6.32`). The
codec can force *structure* — a seq is data it already walks, and forcing it
changes nothing an author could observe, because the seq was going to be walked
one boundary later regardless. A `delay` is not structure. It is an explicit
deferral whose whole content is *not now*, so forcing it at the hand-off would
change the meaning of the author's program in order to protect a property the
author was never told about — a silent repair of a different kind, and no better
than the silent staleness it replaces. `realize-deep` therefore **refuses** an
unforced `delay` it reaches, and refuses it *inside the render of the body that
wrote the crossing*, so the stack lands on the author's own call site. That is the
attribution a query name would have bought, obtained without forcing anything to
learn the name. Only an unforced one is refused: a `delay` the author already
deref'd in their own body carries a computed value, derefs to it without calling
anything, and passes through untouched. The cost is one `instanceof` on the branch
that already existed for scalars, and the refusal needs no render identity — the
check is on the value's shape, made inside the producing body's own window.

The fault it replaces is asserted rather than described, driven around the codec
because the codec now refuses to build it: the parent's read set is **empty**, the
child's first render holds the row query, and the child's second render holds
nothing. The edge is dropped with no boundary left holding one, and the only
boundary that could rebuild the delay never re-rendered.

A **function** prop is not in this class and is not a defect at all — now verified
rather than argued. The child calls it on every render, so the read repeats, the
edge is kept, and the holder is the child, which is the boundary whose output
depends on it. The render-prop-that-is-not-re-run has only two ends and both were
already settled: called in the render it keeps its edge; called anywhere else it
finds no frame and raises the error `read-key!` has raised from the start. A body
that *stops* calling a render prop simply holds no edge — that is law 4 rather
than a defect, and no framework can tell it from a branch not taken.

**What genuinely remains, and it is a boundary of the mechanism rather than a gap
in it.** The crossing walk descends into data structures; a **mutable reference**
is not one. A deferral an author parks in an atom — at a prop position, or in a
module-level var the codec never sees at all — reaches its reader unrepaired and
unrefused, and behaves exactly like the fault above. Opening it is not an option
in either direction: a walk that deref'd a reactive reference would mint a
dependency it has no business holding, and one that deref'd a `delay` would be
the forcing this whole section declines. The author has routed state around the
ruled surface, and no view framework detects that — React with hooks has the
identical hole. It is asserted rather than described, so that it is a stated
property of Surface B and not a later discovery.

| Witness | Asserts |
|---|---|
| `deferred-read-cljs-test` (13 rows) | the unforced `delay` is refused at the crossing, with the id, the refusing position and the recovery; at every position the walk reaches — bare prop, vector, nested map, list, lazy seq, set, and the children slot; a realised `delay` crosses untouched and the read stays the parent's; the refused render installs nothing |
| the same file, classification rows | the fault the refusal replaces, driven around the codec; a function prop keeping its edge across two renders; a function prop invoked outside a render raising the existing error; a body that stops calling one holding no edge; and the mutable-reference limit, asserted unrepaired |

Mutation-proved by deleting the guard from `realize-deep`: node exit 1, 12
failures and 1 error. Restored, node exit 0. There is no browser row and that is
deliberate — the refusal is a pure codec verdict reached before any element is
built, so a Chromium mount would re-prove what the node rows already prove. The
DOM half was earned for `rf2-2rtt6.45` because *that* claim was about liveness,
which a first paint cannot tell you; this one is not.

#### The refusal checked a map's values and not its keys

The walk it was built into descended into a map's **values** only, and said so
in a comment that gave the reason: hashing a seq realises it, so nothing
unrealised can already be a key. The claim the walk makes is about **reach** —
every unforced `delay` reachable from a boundary's props — and a map entry is
two reachable positions. So an unforced `Delay` written as a map key crossed
untouched (`rf2-2rtt6.32`, the merged-PR audit of #7333).

The comment's reason is wrong twice, and both halves are worth recording because
each is a plausible thing to believe. A `Delay` **hashes by object identity** —
`cljs.core` extends `IHash` on `default` to `goog/getUid` — so hashing a map
containing one never forces it; hashing realises a *seq*, and only a seq. And a
map small enough to be a `PersistentArrayMap`, which every props map is,
compares keys with `=` against the entries it has already accumulated and hashes
nothing at all — so the first key of a one-entry map is not so much as looked
at, and even an unrealised **lazy seq** can sit there. Both are asserted:
`realize-deep-reaches-a-lazy-seq-at-a-key-position-too` checks that the seq is
still unrealised *after construction* before it checks that the walk forces it.

The repair is the one call the position was missing, and no more: `realize-entry`
walks the key half through the same `realize-deep`, so a key-held deferral meets
the same refusal, in the same window, naming the same recovery. No walker, no
render identity, nothing forced.

**It cost something, and it was measured rather than assumed.** Three walks
A/B/C'd in one process on an idle box, rounds interleaved, best of seven per
round, four whole repetitions. Walking keys unconditionally costs **+51% to
+67%** of the walk at the dogfood row's props, **+20% to +28%** with two hiccup
children beside them, and **+40% to +56%** at the 100-row collection prop —
against the whole boundary element build measured in the same process, **+7.6%
to +9.9%**. That is a real cost at the shape that matters most, and one
predicate removes it: a `Keyword` is provably neither a collection nor a
`Delay`, so the key half short-circuits on one `instanceof` rather than paying
`coll?` — which, for anything without the `ICollection` marker, falls through to
`native-satisfies?` and is the dearest predicate on the path. With the
short-circuit the repair costs **0.2% to 2.8%** of the element build. The dear
part was never the traversal; it was proving that a keyword is not a collection.

**One methodological note, because it nearly published a wrong number.** The
first cut wrote the two comparison arms in the measuring namespace and reached
for `codec/realize-deep` itself as the third. That arm reported **9–20% faster
than a walk doing strictly less work** — impossible, and the only reason the
confound was caught: an inline `(throw (ex-info …))` in the local arms against a
call to `refuse-deferred!` in the real one is a difference V8 optimises
differently, and it was being read as a fact about keys. All three arms are now
the same shape in the same namespace. The instrument disagrees with the
published walk figure too and says so rather than reconciling: the boundary
element build reads 1.08–1.16 µs here against the recorded 1,089 ns, while the
walk reads 148–177 ns against the recorded 69 ns. Two instruments agreeing on
the denominator and not on the numerator is why only ratios are quoted.

| Witness | Asserts |
|---|---|
| `a-delay-held-as-a-map-key-is-refused-exactly-as-a-value-is` | six key positions the walk reaches — a key of the props map, of a nested map, inside a *collection* key, and of a map held in a vector, a seq and a set |
| `a-key-held-delay-is-refused-before-the-child-can-cache-it` | the crossing refuses; and on a runtime that does not, the row's second half prints the two child read sets that should have been equal |
| `the-fault-a-key-held-delay-would-restore` | the mechanism, driven around the codec: first render `#{[:dogfood/todo 1]}`, second render `#{}` |
| `realize-deep-walks-map-keys-without-disturbing-the-map` | identity and lookup are untouched — the map, each key, and retrieval by an identical and by an equal key, on an array map and on a hash map |
| `the-keyword-key-short-circuit-skips-only-a-provable-no-op` | the premise the short-circuit rests on, pinned; and that it skips the **key**, never the entry |

Mutation-proved by restoring `(defn- realize-entry [_ _ x] (realize-deep x) nil)`
— the exact text at merge `9d3b423d17`: node exit 1, **9 failures**, among them
the two-render receipt reading
`(not (= #{[…/arm1-deferred-read [:dogfood/todo 1]]} #{}))`. Restored, node exit
0 with `git diff` empty. Every first-paint assertion in the file passed on the
mutated runtime, which is why the decisive row is two renders deep.

## 3. Two places the record did not survive contact with the substrate

Both are findings rather than complaints, and both are recorded because the next
arm will meet them.

### 3.1 "Acquire without deref" is not implementable here

The adjudication sketches commit-phase acquisition as *acquire without deref: the
value is already known from the render*. Against this substrate that is not
implementable, and the failure is silent. A derived value starts at an `unset`
baseline that is never `rf=` a real value, and the render's own read went through
`subscribe-once`, which built a **different** reaction and disposed it. So a
freshly acquired reaction reports movement on the first later commit whatever
that commit did — **every newly mounted boundary re-rendering once for nothing.**

The fix is one baseline deref per *new unique key*, on a path that has to compute
anyway; it never runs again for the life of the cell, and it is not the forbidden
per-read-per-commit tear check. The narrow-write witness is what caught it, which
is the argument for writing the witness before trusting the sketch.

### 3.2 The ambient frame outranks React context, and it is invisible

The three-rendering parity gate initially failed with the raw-UIx rendering
showing an empty app-db while `use-current-frame` reported the *right* frame in
the same component. The frame probe separated the two read forms: the
explicitly-framed `use-subscribe` saw the seeded db, the ambient one did not.

The cause is the test fixture's default ambient frame. The carried-invariant
chain resolves the **dynamic-var tier before React context**, so an ambient read
answers for the ambient frame while `use-current-frame` — a raw context read —
reports the provider's. The two disagree silently, and the symptom presents as a
*rendering* difference. `:ambient-frame nil` is now set in every DOM fixture in
this arm, and the probe asserts the two read forms agree so a regression names
the plumbing rather than the rendering.

Worth knowing for the product: this arm's shell reads the context **directly**
and so is unaffected by an ambient stamp. Whether that is the right resolution
policy for a product boundary is a question for the P2 phase, not this bead.

---

## 4. The dogfood screen, in three renderings

One list, one controlled field per editable thing, on **one** shared state layer
and **one** shared intent set (`front/dogfood`), so the comparison is about the
reading surface and nothing else. All three build the identical canonical DOM
(attribute names sorted), with a control proving the comparison can answer false.

**Mounting this screen started the six-week K7 clock (HD-014).** The operator is
on record accepting that (`rf2-2rtt6`, 2026-07-31). K7 is never extended
silently.

### The diff, at the four sites where it exists

**The row — the conditional read.** A completed row is not editable, so its draft
subscription is not needed.

```clojure
;; collector — the read is where the value is used
(when-not (:done? todo)
  [:input.draft {:value (sub [:dogfood/draft id]) …}])

;; grouped — the query is declared before the body, so the branch that is
;; not taken still costs its edge
(let [{:keys [todo draft]} (use-subs {:todo  [:dogfood/todo id]
                                      :draft [:dogfood/draft id]})]
  … (when-not (:done? todo) [:input.draft {:value draft …}]))

;; raw UIx — same as grouped, and NOT by choice: a hook may not sit in a `when`
(let [todo  (uixa/use-subscribe [:dogfood/todo id])
      draft (uixa/use-subscribe [:dogfood/draft id])] …)
```

This is measured, not asserted: `arm1_dogfood_dom_cljs_test` toggles two rows and
reads the index's edge count, and the collector rendering holds **fewer edges
than the declaration on the same page**. The ruled grouped alternative — a
conditional *child boundary* owning the draft — buys the edge back at the cost of
a second `defview` and a second boundary per editable row; it is not what a
working author writes, which is why the grouped rendering here does not pretend
otherwise.

**The helper — the donated read.** `filter-button` needs the current filter.

```clojure
;; collector — a plain function call inlines into the enclosing boundary,
;; so its read belongs to that boundary and needs no argument
(defn- filter-button [id label]
  [:button.filter {:data-current (str (= id (sub [:dogfood/filter]))) …} label])

;; grouped and raw UIx — the value is threaded down as an argument, because a
;; helper has no fixed hook site for a read to occupy
(defn- filter-button [id label current] …)
```

The adjudication's §5 offers grouped a second move — a helper that contributes
*queries* into the single `use-subs` map rather than reading. It is real and it
composes; it is also a second concept (query-contributing helpers) that the
collector does not need, and on a screen this size it reads as ceremony. Recorded
so the comparison is not run with one hand tied.

**The loop.** Under the collector a read inside `for` is ordinary code. Under the
other two it is illegal, so the read has to move up to a fixed site and the loop
consumes a value it was handed. On this screen that is a one-line difference; on
a screen where the loop's body decides *which* query it needs, it is a
restructuring.

**The event position.** Both Hicasso renderings hand the shared
`front.dogfood/row-intents` vectors straight to the props map. The raw UIx
rendering writes seven closures, each reaching into the event for `.-value` and
each re-implementing the IME composition gate. Every one of them is a place the
wrong event id can be written with nothing to notice.

### The preference

**Surface B (the collector) is preferred, and the reason is not brevity.** Line
counts are close — the collector rendering is a handful of lines shorter than
grouped, which would not on its own decide anything. What decides it is that the
collector is the only surface where **the read is at the value's point of use**,
so the question "what does this boundary depend on?" is answered by reading the
body rather than by cross-referencing a declaration against it. Grouped's
declaration and its body can drift: a query left in the map after its use is
deleted is an edge nobody notices, and the compiler-free runtime has no way to
say so. On the collector that drift is not expressible.

The raw-UIx rendering is the control and it loses on ergonomics decisively — not
because of the `$` spelling, which is a matter of taste, but because it cannot
express the conditional read, cannot let a helper read, and turns every event
position into hand-written imperative code.

The honest cost on the other side: the collector's edge set is a function of what
the body *did*, so a body whose control flow depends on a value read late can
change its edge set from render to render, and each change is a re-subscribe. The
witnesses prove it is correct. **It is not cheap** — §3(b) prices it: a
re-subscribe releases and re-acquires every key the boundary reads, not the one
that changed, and arms a reaper for each. This paragraph read "an identity change
and one replacement" until rf2-2rtt6.47 corrected it. It is a real difference
from a surface whose edges are static, and it is the thing to watch on the bulk
rows when they are taken.

---

## 5. What this arm has not done

Named so the P2 ruling is not misled by silence.

- **No bar row.** No clock, no retained-heap figure, no ratio. Deferred to the
  restated bar (`rf2-b0tz5`).
- **H2 untried.** Untried, not abandoned.
- **The survival metric's allocation-slope half** is unmeasured; its
  zero-retained half is witnessed.
- **The 100-cell controlled grid** (HD-019's full witness — caret, selection, IME
  composition, unchanged-model rejection, async normalisation) is not built. The
  same-turn echo and the explicit-revision reset are witnessed on the dogfood
  screen's own controlled field; the rest of K4's row is outstanding.
- **StrictMode, HMR body swap and a real error boundary** (`h/boundary`,
  HD-020(c)) are not witnessed. The abandoned-render and teardown halves of that
  row are.
- **The foreign-component / `defhost` door** (HD-011) is untouched; the codec
  passes a React element through as a child, which is the whole of the interop
  this arm exercises.

## Reproduction

```bash
cd implementation

# the runtime, the read surfaces, the state machine, the allowed edge-diff
# operation, the fence algebra and the residue — no browser needed
npx shadow-cljs compile node-test && node out/node-test.js

# the hook ledger at React's dispatcher, the staged-stale fence, and the
# dogfood screen's three renderings against a real React DOM
npx shadow-cljs compile browser-test && node scripts/serve-and-run-browser-tests.cjs
```

Both are diagnostic and correctness runs. Neither is a bar measurement, and
neither may be quoted as one.
