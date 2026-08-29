# The mutable-global ledger

Every module-level mutable owner in the Hicasso runtime, enumerated with its disposition. `rf2-hic-017` owns this page; [`invariants.md`](../../../../implementation/hicasso/spec/invariants.md) I5 and the [adversarial-risks lane](lanes/adversarial-risks.md)'s *Process-global ownership* row are what it answers to.

The goal the bead states is *no unexplained global survives*, and it allows two ways to satisfy it: scope the owner, or write down why it is correct where it is. **Both are real answers.** Root-scoping something that does not need it buys indirection and spends clarity, so a global that is genuinely process-wide — a build-lifetime intern cache, a monotone counter, a page-wide id namespace — is recorded here rather than wrapped in a root it has no relationship to.

The census below finds **twenty-one** mutable owners and **zero** that need migrating. That is a stronger claim than *nobody has complained*, and the two sections that carry it are [How the census is taken](#how-the-census-is-taken), which makes the roster re-derivable, and [Why keying and not scoping](#why-keying-and-not-scoping), which is the argument the three frame-keyed tables rest on.

The first version of this page claimed nineteen, and the merged-PR audit of #8066 found the count short by two: its four searches could not see a ClojureScript dynamic var, which is mutable through a mechanism none of them modelled. The two owners are on the roster now and the census has [an arm that finds them](#how-the-census-is-taken). The correction is recorded here rather than quietly absorbed, because a roster whose stated derivation cannot regenerate it is the same defect as no derivation at all — and because the *reason* the arm was missing is the useful part: `binding` looks like scoping and is not.

It happened a second time, in the same shape and with the opposite symptom. `rf2-hic-087`'s re-sweep re-ran the six arms and got twenty of the twenty-one rows: `impl.collector/first-registration-armed` has been on this roster since the page's first commit, and no arm has ever found it. The count was never wrong — the derivation was never able to produce it. The blindness is structural for the third time: a `defonce` whose init is a side effect is not a mutable constructor, not a `#js` literal and not a dynamic var, and it is never written, so all six arms miss it and no widening of any of them arrives. [C7](#how-the-census-is-taken) is the arm that says what they were all assuming — that what makes something process-global is the `defonce`, not the shape of the value it holds.

## Why this page exists at all

The inventory was not missing before this bead — it was **unfindable**. `rf2-hic-012` established the root-scoping pattern and left `rf2-hic-017` the obligation *or each remaining global justified in writing*, and the justification existed only in the ns docstring of `implementation/hicasso/test/re_frame/hicasso/roots_frames_isolation_dom_cljs_test.cljs`, which enumerates the cell table, the entry cache, the scratch buffer, the render-state object, the frame-op memo and the generation counter and says why two roots cannot collide in any of them. A maintainer asking *is this global safe?* does not open a DOM test to find out. Promoting it is most of what this page is; the census is what makes the promotion complete rather than a transcription of whatever the test happened to name.

## How the census is taken

Seven searches over `implementation/hicasso/src`, and the roster is their union. They are recorded so the next audit re-runs them rather than re-deriving the method, and so a reviewer can check the roster is closed instead of taking its word.

The **Site** column names a file and not a line, deliberately. Every arm below prints `file:line` against the tree the reader actually has, while a line number written into this page starts decaying the moment it is written: seven of the first roster's twenty-six rows carried a wrong line the day the page merged, and `!anchor-seq` moved seventeen lines two days later. The owner's fully-qualified name is in the first column and is what locates it.

```sh
# C1 — every mutable constructor, at any nesting. The trailing character class
#      matters: `\b` after `volatile!` or `js/Map.` never matches, because `!`
#      and `.` are not word characters, and an earlier draft of this census
#      silently dropped both generation volatiles and the codec's warn table.
rg -n --sort path "\((atom|volatile!|js/Map\.|js/WeakMap\.|js/Set\.|empty-cache|react/createContext)[ )]" implementation/hicasso/src

# C2 — module-level JS literals. `rstate` and `scratch` are `#js` literals with
#      no constructor call, so C1 cannot see them. The indent bound trims the
#      deeply nested cases and nothing more: three hits are literals built
#      inside functions, and they are NAMED below rather than filtered out. An
#      earlier draft of this comment claimed the bound separated a def body
#      from an in-fn literal, and it does not.
rg -n --sort path "^\s{0,7}#js [\{\[]" implementation/hicasso/src

# C3 — every writer. A mutable owner with no writer is not one; an owner this
#      roster missed would surface here as a write to an unlisted name.
rg -o --sort path "(swap!|reset!|vswap!|vreset!) [!a-z-]+" implementation/hicasso/src | sort -u

# C4 — mutation of the module-level JS objects, which no ref-writer search sees.
rg -n --sort path "(unchecked-set|js-delete) (tag-cache|prop-cache|keywarn|rstate|scratch)|set! \(\.-[a-zA-Z]+ (rstate|scratch)\)" implementation/hicasso/src

# C5 — dynamic vars, which NONE of C1-C4 can see. `(def ^:dynamic *v* nil)` has
#      no mutable constructor (C1 blind), is not a `#js` literal (C2 blind),
#      and is written by `binding` rather than by a ref writer or a JS-object
#      set (C3 and C4 blind). The blindness is structural rather than a tuned
#      regex missing a spelling: a dynamic var is mutated through a FOURTH
#      mechanism the searches above do not model, so no amount of widening them
#      reaches it. Loose on purpose — an arm should over-match.
rg -n --sort path ":dynamic" implementation/hicasso/src

# C6 — the writers for C5's owners, and what closes C5. `cljs.analyzer`'s
#      `confirm-bindings` REFUSES a `binding` of a var not marked `^:dynamic`,
#      so every name this arm finds is guaranteed to be declared in some
#      namespace's C5 — this one's, or another package's, and the two are told
#      apart by whether the symbol is namespace-qualified. The `set!` half is
#      the other way a dynamic var is written, and finds nothing today.
rg -n --sort path "\(binding\b|set! [a-zA-Z./-]*\*[a-zA-Z-]+\*" implementation/hicasso/src

# C7 — every top-level `defonce`, whatever it holds. C1-C6 all ask what
#      the VALUE is, and a `defonce` is a process-global commitment
#      whether or not its value is mutable: `first-registration-armed`
#      holds `true`, and its init is a side effect. So it is no mutable
#      constructor (C1 blind), no `#js` literal (C2 blind) and no dynamic
#      var (C5 blind), and nothing ever writes it (C3, C4 and C6 blind).
#      Anchored, because a process-global commitment is by definition a
#      top-level form — the one indented `defonce` in this tree is
#      `mount.cljs`'s docstring example, which has its own section below.
rg -n --sort path "^\(defonce\b" implementation/hicasso/src
```

### What the searches return that is not an owner

A census only closes if every hit is either on a roster or named here, and four arms return hits that are neither.

- **C1** — `codec.cljs`'s `(js/Map.)` and `collector.cljs`'s `(atom {…observation-opts-key…})` are allocated per call: the first *into* `keywarn`, which is itself a roster row, the second handed straight to the substrate's memo and never retained. `armed?` is a local `volatile!` in `impl.intent`'s callback gate, one per lowered callback.
- **C2** — three of its seven hits are `#js` literals built inside functions: `roots.cljs`'s per-root adoption window (one per root, and [reachable only from it](#why-keying-and-not-scoping)), and the hook dependency arrays in `overlay.cljs` and `presence_react.cljs` (one per render).
- **C3** — `counter` and `watchers` occur only inside docstrings, quoting code that was retired.
- **C4** — one hit is `scratch`'s own docstring, quoting `(set! (.-length scratch) 0)` as the whole of its reset.

`mount.cljs`'s `!root`, which C1 and C3 both find, has [its own section](#the-false-positive-worth-its-own-section). C5 and C6 close against each other and are discussed [with the owners they find](#the-render-context-bound-and-restored).

C7 is the one arm with nothing to declare here: all thirteen of its hits are on a roster — twelve on the mutable one above, and `adoption-context` on the [identities roster](#module-level-identities-which-are-not-mutable-owners). That second destination is not a loophole, and the section that owns it says what it cost to read a hit against the wrong row.

## The dispositions

Three, and the middle one is the common case.

- **Frame-scoped** — the owner is one table, but every entry is qualified by the frame that owns it, so two frames cannot address the same entry. This is scoping achieved by keying; see [below](#why-keying-and-not-scoping).
- **Justified in place** — process-wide is correct, and the row says why. Migrating it would add indirection without buying isolation, and in one case ([`!anchor-seq`](#the-page-wide-id-namespace)) would break the thing it is trying to protect.
- **Left, with the reason** — nothing here today.

### Frame-scoped by keying

| Owner | Site | Key | Why the key is the scope |
|---|---|---|---|
| `impl.collector/!cells` | `collector.cljs` | `[frame-kw query-v]` | The sub-key is frame-qualified at mint, so the same query read under two frames occupies two cells with two reader lists and two reactions. A cross-frame read is not a collision that isolation must prevent; it is unaddressable. |
| `impl.collector/!entries` | `collector.cljs` | hash of the read *sequence* | The sequence is a sequence of sub-keys, so it inherits their frame qualification. Two boundaries share an entry only when their read sequences are identical, which across frames they cannot be. The bucket hash selects; `entry-matches?` still compares every key pairwise, so the hash can only ever produce a false negative. |
| `impl.frames/!frame-ops` | `frames.cljs` | `frame-kw` + incarnation token | The row carries the incarnation it was minted under and a lookup replaces a row whose incarnation has been superseded, so the table is keyed by *address and identity*, not address alone. `rf2-hic-013` is the bead that made the second half necessary. |

### Justified in place

| Owner | Site | Kind | Justification |
|---|---|---|---|
| `impl.collector/rstate` | `collector.cljs` | render-extent slots | One JS object holding the frame, two provenance flags, the resolved entry, the cold-probe box and the body-run counter. Legal because **boundary bodies do not nest** — a body returns Hiccup and the codec turns a child boundary into an *element*, which React runs later, after this body has returned. Every slot is nil outside a render, which is what makes `sub` outside a boundary a loud refusal rather than a silent read of whichever frame was ambient. Its own docstring carries the argument. |
| `impl.collector/scratch` | `collector.cljs` | render-extent buffer | One array, reset by overwriting `.-length`, holding the running body's reads in read order. There is deliberately exactly one: a second would mean telling two render attempts apart, and that is the per-render ledger I5 forbids. Same non-nesting argument. |
| `impl.collector/!dirty` | `collector.cljs` | flush-extent set | Holds **cells**, not keys. `flush!` unions each dirty cell's own reader list, and a cell's readers are the boundaries that read that frame-qualified key — so a dirty cell in one frame reaches only that frame's boundaries. The set being page-wide unions *scheduling*, never ownership. |
| `impl.collector/!deferred` | `collector.cljs` | flush-extent set | The same union, held across a macrotask when a flush lands mid-render (React rejects a render-phase update on another component). Same reasoning: the members are boundaries already selected by cell readership. |
| `impl.collector/!batching` | `collector.cljs` | commit-window flag | One page-wide commit window, so one browser turn produces one flush. A concurrent write under another root joins the open window rather than flushing early; what that changes is *when* its boundaries are notified, never *which*. Per-root windows would split one turn into two flushes for no correctness gain. |
| `impl.collector/first-registration-armed` | `collector.cljs` | arming latch | A `defonce` that exists for its side effect and is deliberately never read — it installs the substrate's registration hook. Once per process is the whole semantics; a per-root arming would install the hook N times and run it N times per registration. |
| `impl.generation/!generation` | `generation.cljs` | monotone counter | The commit generation, bumped once per flush that moved something. It is a number, not ownership state, and its comparability is the point: boundaries under different roots read the same substrate and their epochs are compared against one basis. Per-root counters would make two boundaries' stamps incomparable, which is the failure `commit-basis` exists to prevent. |
| `impl.generation/!registry-epoch` | `generation.cljs` | monotone counter | Counts `:sub` registrations — first-time and replacement alike. What it counts is the **substrate's own registry**, which is process-wide; a per-root epoch would be a number about a thing that is not per-root. It is the term the HMR contract (`rf2-hic-015`) rides. |
| `impl.codec/tag-cache` | `codec.cljs` | intern cache | Prototype-free JS object, keyed by the literal tag string, valid for the build's life. No ownership semantics: a hit returns a `ParsedTag` describing the *source text*, which cannot differ between frames. |
| `impl.codec/prop-cache` | `codec.cljs` | intern cache | The same, keyed by prop name, seeded through `seed-prop-cache!` with the three names whose emitted spelling is a rule rather than a memo. `reset-caches!` re-seeds through that same fn, so a suite fixture cannot leave the cache holding a spelling a cold build would not have. |
| `impl.codec/keywarn` | `codec.cljs` | dev-only dedupe | `nil` in production — every reader sits behind `goog.DEBUG`, so the object, its tables and its message strings fold away under `:advanced`. Per-page dedupe of a console warning is React's own semantics for the same warning, and a full page reload resets it either way. |
| ~~`impl.evidence/!evidence-sink`~~ | ~~`evidence.cljs`~~ | ~~instrumentation seam~~ | **Retired 2026-08-29 in PR #8745 (`rf2-6c12m.17`)** — `impl/evidence.cljs` and the collector's two taps are deleted, so this owner no longer exists: nothing in src attached to it, and the Xray projection `re-frame.hicasso.evidence` reads the collector's tables directly. The justification it carried, kept for the record: ~~One sink per page, `nil` until a tool attaches, and read as the outermost form of every tap's guard so a detached runtime builds nothing. The events it carries name their frame and their boundaries, so one sink loses no attribution; a per-root sink would make a tool enumerate roots to hear the page.~~ |
| `impl.error/!sources` | `error.cljc` | declaration ledger | `"<ns>/<sym>"` → the coordinate its `defview`/`defhost` captured, written once at namespace load and read only when a refusal is being minted. The key is globally unique by construction, and the map is empty in production. Declaration-time bookkeeping, not runtime state. |
| `impl.error/!origin` | `error.cljc` | declaration extent | One slot rather than a stack, for the same non-nesting reason `rstate` is one object. `traced-boundary` saves and restores anyway, on the principle that an invariant cheap to survive should be survived rather than relied upon. Dev-only. |
| `impl.state/!defaults` | `state.cljc` | registration ledger | `concern` → the `:default` it was registered with. It exists so a second `reg-state` for one concern with a *different* default is refused rather than silently changing what every un-set instance reads. This is the same class of thing the sub and event registries are, and they are process-wide too; scoping it per root would let two roots disagree about what an un-set instance reads, which is precisely the refusal's subject. |
| `impl.overlay/!anchor-seq` | `overlay.cljs` | id namespace | See [below](#the-page-wide-id-namespace) — the one row where scoping would be a defect rather than a cost. |
| `impl.intent/*dispatch*` | `intent.cljs` | render-extent binding | The rendering boundary's frame-locked `dispatch`, `nil` outside a render, rebound to the supplying boundary's for the extent of a render callback. See [below](#the-render-context-bound-and-restored): the disposition is the same non-nesting-extent argument `rstate` makes, but the mechanism earns its own paragraph because in ClojureScript `binding` is not what its name suggests. |
| `impl.intent/*frame*` | `intent.cljs` | render-extent binding | The frame keyword for that same extent, plus the one door `*dispatch*` does not share: `root-element` binds the frame alone for a hiccup form written outside any body. As above. |

**[Amended 2026-08-29, `rf2-6c12m.17`.]** The roster is twenty owners, not twenty-one: `impl.evidence/!evidence-sink` is struck above because PR #8745 deleted its namespace. The seven arms find it nowhere on the current tree, which is the direction the [gate](architecture-census.md#the-gate) enforces, and the row is kept struck rather than removed so the count this page argued from stays checkable.

#### The page-wide id namespace

`!anchor-seq` mints CSS dashed-idents (`--rf-overlay-N`) for overlay *instances*, one per instance rather than one per anchor id, because two overlays may legitimately share a trigger and a shared ident would make each one's teardown erase the other's.

Root-scoping the counter would break it. A CSS anchor name lives in **one namespace per document**, not one per React root: two roots each minting `--rf-overlay-1` would produce two live overlays claiming one name, and the second claim would silently steal the first's positioning. The counter is page-wide because the namespace it allocates into is page-wide, and that is a correctness requirement, not an accident of where the `defonce` was written. A future audit reading *mutable global, therefore migrate* would introduce the bug this row exists to prevent.

**And it is not the SSR defect it once looked like.** `rf2-9zz0y` measured two `renderToString` calls over one immutable snapshot answering `--rf-overlay-5` and `--rf-overlay-6`, because the panel's `position-anchor` was written declaratively into the `style` attribute and so reached the served bytes — a hydration mismatch on an attribute hydration must match, drifting further from a fresh client's counter with every request a long-lived process served. **The repair left this row exactly as it stands.** The counter is still page-wide, for the reason above; what moved is the panel's half of the claim, into the ref callback beside the trigger's `anchor-name` that the module already claimed there. The ident is a client lifecycle token rather than content, so the answer was to stop serializing it rather than to rescope the thing that mints it. An auditor arriving here from a hydration mismatch should read `impl.overlay/anchor-panel!` before touching anything on this row.

#### The render context, bound and restored

`*dispatch*` and `*frame*` are the rendering boundary's context. Three sites bind them and C6 finds all three: `intent/with-frame` binds both for a body's extent — and for the error boundary's fallback and the presence tray's retained children, which are author-written render-phase code walked under the same call; `intent/lower-prop`'s callback gate rebinds both to the *supplying* boundary's for the extent of one render-callback invocation; and `codec/root-element` binds `*frame*` alone, deliberately without a dispatch, for a hiccup form written outside any body.

**They are on this page because in ClojureScript `binding` is a global assignment.** `cljs.core/binding` expands to `with-redefs`, which saves each var's root value into a `let`, `set!`s the root, and restores it in a `finally`. There is no thread-local frame, because there are no threads. So these are module-level mutable state in exactly the sense the rest of this page means it, and *"`binding` is scoping, therefore not a global"* is the reading that kept them off the first roster. The correction is worth more than the two rows: it says the safety here has to be argued from the extent, not from the form.

Argued from the extent, it holds, and in the terms the mechanism actually has:

- **Restored on every exit.** The `finally` runs on a normal return and on a throw alike, so a refusal raised inside a body — a designed outcome in this runtime, not an edge — leaves both vars as it found them.
- **Correct under nesting, which is more than `rstate` claims.** The saved value is the *enclosing* binding's, so a `with-frame` inside a `with-frame` restores to the outer frame rather than to `nil`. `lower-prop` depends on exactly this: it rebinds to the owner's frame for the callback and the enclosing body's frame survives underneath. The non-nesting argument `rstate` and `scratch` rest on is a premise they need and these two do not.
- **`nil` outside the extent, and that is the whole of the safety property.** The extent is one synchronous call, so anything escaping it — a promise continuation, a timer, a browser click — runs after the restore and reads `nil`. Every reader turns that `nil` into a loud refusal rather than a guess: `require-dispatch` raises `:rf.error/hicasso-intent-outside-boundary`, and `route-link` reads `*frame*` at *render* time precisely because the click fires long after the extent has unwound, so the frame travels as data instead. `intent_cljs_test`'s `an-intent-lowered-outside-a-boundary-is-a-loud-error`, `an-intent-returned-with-no-frame-in-scope-names-the-position` and `a-render-callback-with-no-owner-forwards-to-a-loud-error-never-to-silence` are the standing witnesses for that, and `read_extent_cljs_test`'s `a-promise-continuation-refuses` and `a-timer-callback-refuses` witness the escape from the other side.

**No isolation defect is claimed and none is known.** Two roots on one page render in two separate synchronous React work loops, so one root's extent cannot be open while the other's body runs, and neither var carries state between them. This bead migrated nothing here, and root-wrapping them would be a defect rather than a cost: a per-root `*frame*` is what `binding` already provides, one extent at a time, and a second mechanism doing the same job is the indirection this page's opening declines to buy.

One name `with-frame` binds is **not** a Hicasso owner and is on neither roster. `re-frame.frame/*ambient-frame-refusal*` is core's var, declared there and merely bound here, so it falls outside the census's stated scope of `implementation/hicasso/src` and belongs to whatever record owns `re-frame.frame`. C6 surfaces it, its namespace qualification is what marks it foreign, and this sentence is where the trail stops.

## Why keying and not scoping

Almost everything this runtime holds is one-per-page, and that is deliberate: **isolation between roots is not a property React provides** — React knows nothing about the cell table, the entry cache or the frame-op memo — it is a property of how they are *keyed*. A sub-key is `[frame-kw query-v]`, and that qualification is the entire mechanism.

The consequence for reading a witness is stated in `roots_frames_isolation_dom_cljs_test`'s own opening and is worth repeating here, because it is what makes the claim checkable: a boundary that resolved the wrong frame still renders a value, and React still repairs the tree to something plausible by the end of the event, so a total isolation failure can look like a working page when read at `.-textContent`. The witness therefore reads three observables React cannot forge — **the cell table's key set, the reader count per key, and the monotone body-run counter** — with the DOM read afterwards as corroboration only. That suite is the standing proof for the three frame-scoped rows above, and this bead migrated nothing, so it is green unchanged rather than green again.

### The witness was falsified before it was cited

A green witness cited for a claim nobody tried to break is an assertion with a test-shaped decoration on it, so the keying claim was checked by making it false. `impl.collector/read-key!` mints the sub-key as `[frame-kw query-v]`; the plant replaced `frame-kw` with one constant **for the isolation suite's two frames only**, which is a frame-qualification leak and nothing else. The narrowing was necessary rather than tidy: an unconditional plant crashed `examples.slice.extension` with an uncaught `TypeError`, and shadow.test runs the whole lane inside one `run-block` with no try/catch, so the run aborted at namespace 121 and the isolation suite — later in the alphabet — never executed at all. A truncated run is red for the wrong reason and proves nothing about the witness.

Under the narrow plant the suite failed on exactly the observables its opening says it takes, and on no others by accident:

| Assertion | Expected | Observed under the plant |
|---|---|---|
| the cell table's key set | four frame-qualified keys, two per frame | two keys, both under the planted constant |
| `cell-frames` | `#{frame-a frame-b}` | `#{planted-leak}` |
| readers per key | `[1 1 1 1]` | `[0 0 0 0]` |
| frame-local dispatch and its DOM echo | root A at `"1"`, root B at `"0"` | both at `"0"` |

`npm run test:browser` went from `0 failures, 0 errors` to `22 failures, 0 errors` across 1,467 tests, and the file was restored and verified byte-identical by SHA-256 against its pre-plant digest before any gate result on this page was taken.

The one page-wide door that reaches across roots is `impl.collector/reset-runtime!`, and it is a **fixture door and not root teardown** (`rf2-31xm`): every table it empties is one-per-page, so calling it to tear one root down empties the runtime under every other root on the page. `impl.mount/unmount!` is root teardown and reaches none of it. It also deliberately does not touch the hydration adoption window (`rf2-6tmu`) — that window used to be one page-wide boolean and is now per-root, reachable only from the root that minted it, which is the one migration this class of state has actually needed and it landed before this bead.

## Module-level identities, which are not mutable owners

A second roster, recorded because a `def` of a React artefact *is* a process-global identity even though nothing writes it after load, and because the question a reader asks about these — *what does a hot reload do to it?* — is a real one that the mutable roster does not answer.

| Identity | Site | Form | Note |
|---|---|---|---|
| `impl.roots/adoption-context` | `roots.cljs` | `defonce` | A React context object. `defonce` is load-bearing: fibers that survive a reload hold the old context, and re-minting the identity would leave providers and consumers matching on different objects. Default `nil`, which `adopting?` reads as closed, so every tree with no provider above it gets the right answer with no provider, no object and no branch. |
| `impl.controlled/shadow-input` | `controlled.cljs` | `def` | The composition-shadow components. Plain `def`, and the asymmetry with the row above is deliberate rather than an oversight: re-minting these on a reload remounts every controlled field, which is what a framework developer editing *this file* wants to see, and an app-code edit never reaches them because shadow-cljs reloads the namespaces that changed. Same argument `keywarn`'s docstring makes for its own plain `def`. |
| `impl.controlled/shadow-textarea` | `controlled.cljs` | `def` | As above. |
| `impl.boundary/boundary` | `boundary.cljs` | `def` | The error-boundary React class, built once at load. |
| `impl.portal/portal` | `portal.cljs` | `def` | Component identity. |
| `impl.presence-react/presence` | `presence_react.cljs` | `def` | Component identity. |
| `impl.codec/raw-crossing` | `codec.cljs` | `def` | A `#js` object carrying a `displayName`, an empty callbacks map and empty slots — what `host-entry` reads at a `[:>]` prop in place of a declaration. Built at load and never written; the escape mints nothing per site, so one module-level value is the whole of it. |
| `impl.codec/raw-gate` | `codec.cljs` | `def` | THE ONE `[:>]` gate, shared by every crossing on every page — a one-hook component **function**, not an object, with `displayName` `unchecked-set` onto it once inside the `let` that builds it. Shared rather than minted per component is a ruling and not a saving: a component-keyed cache cannot be built at all, because React's built-in wrapper types are `Symbol.for` values and ES2024 excludes registered symbols as `WeakMap` keys. |

**`raw-crossing` was missing from this roster from the page's first commit, and how it survived is the part worth keeping.** Both identities predate this page — they landed together under `rf2-hic-001` — so this is an omission and not drift. C2 does find `raw-crossing`'s `#js` literal, and the hit was read as belonging to `raw-gate`, fifteen lines below it in the same file, which has never been an object: the row underneath described the literal above it accurately enough that nobody looked. The census's closure test is *every hit is on a roster or named here*, and this is the failure mode that test has — **a hit can satisfy it against the wrong row**, and the roster comes out one short while every arm still appears to close. The two rows above now say which is which, and the discipline the [Site column's own note](#how-the-census-is-taken) states for line numbers holds here for symbols: what locates a row is its name, so the name is the thing to check.

## Request scope, now that there is a server render

The adversarial-risks *Process-global ownership* row names *independent roots and SSR requests*. Until 2026-08-14 only the first half had a subject; `re-frame.hicasso.server` landed that day, publishing `server/render` on `react-dom/server`'s `renderToString`, and the second half has one now.

The previous edition of this section said that when a server-render entry landed, **all twenty-one owners would become request-scope questions at once, because a Node process serving two requests concurrently shares every one of them.** That was the right thing to expect and it is not what happened, for a reason the page could not have known before the door existed: **the door is `renderToString`, and a server render runs strictly less of this runtime than a client mount does.** Sharing an owner between two requests only makes it a request question if a request ever touches it, and most of this roster a request never reaches.

Three properties of that path decide every row below, and each disposition is one of them applied.

- **S1 — nothing subscribes.** Every shell hands `(.-snapshot entry)` as both the client and the server snapshot, so `useSyncExternalStore` calls the server snapshot and never calls `subscribe`. `acquire-cell!` sits inside `subscribe`'s own closure and is called from nowhere else, so a request mints no cell, installs no watch and takes no ref-count.
- **S2 — nothing commits.** The whole render is HD-002's *abandoned render* by design: no flush, no effects, and no ref callback ever fires.
- **S3 — one synchronous call.** `renderToString` returns before the next request's render begins, and nothing on the path awaits.

S1 and S2 are what make most of this roster **silent** during a request. S3 is the only one of the three that is load-bearing for **safety** rather than for silence, and it is a property of today's door and not of the mechanism — `renderToPipeableStream` is out of scope for that module, absent rather than deferred, and adopting it later is the change that would put the S3 rows genuinely at risk. That is the discriminator, and it is worth stating as the one thing to re-check: **an owner is a request question here only if a request writes it, and the only owners a request writes are safe because the render does not await.**

| Class | Owners | The request-scope answer |
|---|---|---|
| Never written during a request | `!cells`, `!dirty`, `!deferred`, `!batching`, `!generation` | S1 and S2 between them close the only paths that write these. The cell table is acquired at commit and the other four are flush-extent; a request neither subscribes nor commits, so all five sit exactly as the process left them. Not request questions. |
| Written at load, never by a request | `first-registration-armed`, `!registry-epoch`, `!defaults`, `!sources` | Registration-time and declaration-time bookkeeping, all of it done by the time the server accepts its first connection. `first-registration-armed` is the clearest row on the page: once per process is not a concession here, it is the semantics, and a per-request arming would install the substrate hook once per request. |
| Written per request, contents request-independent | `tag-cache`, `prop-cache`, `keywarn` | The intern caches are keyed by source text and a hit describes source, which cannot differ between requests — sharing them across requests is the point, and a cold cache per request would re-parse every tag on every page served. `keywarn` is `nil` in production, so on a production server it does not exist; in a dev server it dedupes a console warning across requests rather than within one, which is a difference worth naming rather than waving away. |
| Render-extent, safe on S3 | `rstate`, `scratch`, `!origin` | Written on every body run, including a server one. Their argument is the page's existing non-nesting argument plus S3, and it is narrower than the two vars' below: they do not restore to an enclosing value, they restore to `nil`. |
| Per-request, keyed by the request's own frame | `!entries`, `!frame-ops` | Each request mints a fresh `gensym` frame id, so every key either table takes is unique to that request and two requests cannot address one row. Isolation is therefore not the question for these two — **retention is**, and each answers it by a different mechanism. See [below](#the-one-row-a-server-render-leaves-behind). |
| Monotone across requests, invisible in the output | `!anchor-seq` | `make-cell` runs during render, so a server render *does* advance the counter. Every use of the ident is inside the ref callback, which S2 says never fires, so the value reaches no bytes. This is the row `rf2-9zz0y` measured and repaired; [its own section](#the-page-wide-id-namespace) carries the argument and the repair left it standing. |
| ~~One per process, attribution survives~~ | ~~`!evidence-sink`~~ | **Retired 2026-08-29 in PR #8745 (`rf2-6c12m.17`)** — the sink is deleted with its namespace, so no request writes it and the row has no owner. Its argument, kept for the record: ~~Events from concurrent requests interleave into one sink, and each names its frame. Since each request's frame id is a unique `gensym`, every event is attributable to the request that raised it — the same argument this page already makes for two roots on one page, with a stronger key.~~ |

### The two dynamic vars, argued individually

The previous edition pointed at these as the sharpest row, and it was right to. `binding` is `with-redefs` in ClojureScript — a global `set!` restored in a `finally` — so a `set!` restored at the end of a synchronous call is exactly as safe as that call being synchronous, no more. A Node process interleaving two `renderToString` calls at an `await` would have the second request's `*frame*` visible to the first's continuation, and unlike a keyed table there is no key to tell the two apart.

**`impl.intent/*frame*`** is bound on the server at all three of the sites [above](#the-render-context-bound-and-restored) — `with-frame` around every boundary body, `lower-prop`'s callback gate, and `codec/root-element` for a hiccup form outside any body. It is safe under S3 and under nothing else. What makes it true today is not that the var is protected but that **`renderToString` is synchronous and no door on this path returns a promise** — `server/render` runs the render, builds the payload and returns, all inside one turn. The claim to re-check is that sentence, not the var.

**`impl.intent/*dispatch*`** is bound at two of those three — never by `root-element`, which withholds a dispatch on purpose — and carries the additional fact that it is *reached* on the server: `run-once` binds `(frame-dispatch frame-kw)` unconditionally, so every server-rendered boundary resolves a frame-locked dispatch even though nothing will ever fire it. That is what puts a row in `!frame-ops` — see below. The extent argument is identical to `*frame*`'s, and both are `nil` the moment the render returns, which is what makes a callback that escaped the request a loud refusal rather than a read of another request's frame.

### The one row a server render leaves behind

`!entries` and `!frame-ops` are both written per request and both keyed so that two requests cannot collide. They differ on what happens afterwards.

`!entries` cleans up. `render-body` calls `entry-for` on every body run, so a server render does mint entries — `subscribe` is never called, but the entry carrying it is materialised before React would ever look at it, which is the one place `server.cljs`'s "no cache entry" reads more broadly than the code does. Those entries are never claimed, so their `refs` stay at zero and `arm-entry-reaper!`'s `setTimeout` drops each one at the reap horizon. The footprint is bounded by requests-per-horizon rather than by process lifetime, and needs nothing.

**`!frame-ops` did not, and it is the one row on this page whose answer changed when the server door landed.** `frame-row` stores a row for a live frame, and its only eviction was *the successor's first lookup for the same key* — the mechanism `rf2-hic-013` built, which works because a client frame id is reused across incarnations. **A `gensym` has no successor.** `forget-frame-ops!` was documented as reset-and-hygiene-only, its sole runtime caller was the whole-runtime fixture door, and nothing wired it to `destroy-frame!`. So every request that rendered a boundary left one permanent row holding that request's captured dispatch bundle, keyed by an id that could never recur, for the life of the process.

No isolation defect was claimed and none existed — the rows cannot be addressed by another request. It was a **retention** finding, and the first thing on this roster that a page-scoped reading could not have caught.

`rf2-uejlj` repaired it in `implementation/hicasso/src`, and not by making the lazy replacement cleverer. The row belongs to an *incarnation*, so the eviction is frame DESTRUCTION's rather than the server's: `impl.frames` now publishes `:hicasso/on-frame-destroyed!` on core's late-bind registry — the step-7 door Freehand already releases its own frame-keyed ledger through — and `destroy-frame!` drops the row whoever created the frame and however the id was spelled. The per-request `gensym` is bounded as a consequence rather than as a special case. The safety argument is untouched: `frame-row`'s replace branch is still what makes a reincarnation correct, and a row a destroyed incarnation left behind was already unreachable by every branch of that lookup, so dropping it earlier is hygiene and never safety.

## One thing this page does not cover, and why

**Composition handling is not a page-global question.** No owner listed above is touched by it — `impl.controlled`'s shadow state is per-field React `useState` rather than module state — so the IME row of `rf2-hic-016` governs nothing here, and nothing here is evidence about it. What is worth naming is the neighbouring half that *is* about controlled input: hic-016's three-browser matrix — echo, rejection, caret preservation, selection range and direction, revision reset, and the blur and unmount edges — landed on main under PR #7992 and is verified there by measurement.

## The false positive worth its own section

`implementation/hicasso/src/re_frame/hicasso/impl/mount.cljs` matches C1 with `(defonce ^:private !root (atom nil))`, and it is not a global: it sits **inside the `render!` docstring**, as the consumer's hot-reload example. The `reset! !root` that C3 finds three lines later is the same example. It gets a section rather than a bullet in [the list above](#what-the-searches-return-that-is-not-an-owner) because it is the only census hit in that file, and an audit that took it at face value would open a collision with whichever bead owns `mount.cljs` over a global that does not exist.
