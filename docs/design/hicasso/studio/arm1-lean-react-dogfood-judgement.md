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

A boundary's edge set is **replaced wholesale, once, at commit** — the shared
front half's `record-reads` set difference — and only when the read set actually
changed, because an unchanged set leaves `subscribe` identical and React does not
call it again. **The unchanged case is detected without building anything:** the
scratch array is compared pairwise against the cached entry's key array, so
steady-state allocation for edge maintenance is zero bytes.

The ordered compare is a **false-negative device, never a wrong answer** — two
renders reading the same keys in a different order miss the compare, take a
second entry with the same set, and React replaces a set with itself. It is
deliberately not repaired with a content hash, whose failure mode would be a
silently missing edge.

Against the forbidden list: no per-read object; one scratch and never two;
nothing keyed by render, attempt, lane or generation; no registry of in-flight
renders; **no commit-phase re-read of subscription values**.

Witnesses: `an-unchanged-read-set-is-detected-without-building-anything`,
`a-changed-read-set-takes-a-different-subscribe-identity`,
`a-boundary-holds-exactly-the-edges-its-latest-commit-installed`,
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
witnessed now — `arm1_runtime_cljs_test` asserts `{:cells 0 :cell-refs 0
:boundaries 0 :edges 0 :entries 0}` after release, and every DOM suite asserts
the same after its mount. **The steady-state allocation slope across warm
1/3/7/20 reads** needs the bench and is not taken here.

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
folds one into a vector, and a seq at a prop position goes through `clj->js`. A
lazy read is therefore forced inside the window by the same pass that turns
hiccup into elements. No `doall`, no `vec`, no widened window, and so no cost:
the realisation was already going to happen, at the same moment, for the same
reason. The ≤2-hook budget is untouched.

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

One residual case the guard cannot see, named rather than left to be discovered:
a deferred read forced *inside another boundary's* render is attributed to that
boundary. It is not a missing edge — the reader does re-render — but it is the
wrong reader, and catching it would need per-boundary render identity the shell
deliberately does not hold.

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
mechanism makes that cheap (an identity change and one replacement) and the
witnesses prove it is correct, but it is a real difference from a surface whose
edges are static, and it is the thing to watch on the bulk rows when they are
taken.

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
