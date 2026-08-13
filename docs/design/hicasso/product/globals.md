# The mutable-global ledger

Every module-level mutable owner in the Hicasso runtime, enumerated with its disposition. `rf2-hic-017` owns this page; [`invariants.md`](invariants.md) I5 and the [adversarial-risks lane](lanes/adversarial-risks.md)'s *Process-global ownership* row are what it answers to.

The goal the bead states is *no unexplained global survives*, and it allows two ways to satisfy it: scope the owner, or write down why it is correct where it is. **Both are real answers.** Root-scoping something that does not need it buys indirection and spends clarity, so a global that is genuinely process-wide — a build-lifetime intern cache, a monotone counter, a page-wide id namespace — is recorded here rather than wrapped in a root it has no relationship to.

The census below finds **nineteen** mutable owners and **zero** that need migrating. That is a stronger claim than *nobody has complained*, and the two sections that carry it are [How the census is taken](#how-the-census-is-taken), which makes the roster re-derivable, and [Why keying and not scoping](#why-keying-and-not-scoping), which is the argument the three frame-keyed tables rest on.

## Why this page exists at all

The inventory was not missing before this bead — it was **unfindable**. `rf2-hic-012` established the root-scoping pattern and left `rf2-hic-017` the obligation *or each remaining global justified in writing*, and the justification existed only in the ns docstring of `implementation/hicasso/test/re_frame/hicasso/roots_frames_isolation_dom_cljs_test.cljs`, which enumerates the cell table, the entry cache, the scratch buffer, the render-state object, the frame-op memo and the generation counter and says why two roots cannot collide in any of them. A maintainer asking *is this global safe?* does not open a DOM test to find out. Promoting it is most of what this page is; the census is what makes the promotion complete rather than a transcription of whatever the test happened to name.

## How the census is taken

Four searches over `implementation/hicasso/src`, and the roster is their union. They are recorded so the next audit re-runs them rather than re-deriving the method, and so a reviewer can check the roster is closed instead of taking its word.

```sh
# C1 — every mutable constructor, at any nesting. The trailing character class
#      matters: `\b` after `volatile!` or `js/Map.` never matches, because `!`
#      and `.` are not word characters, and an earlier draft of this census
#      silently dropped both generation volatiles and the codec's warn table.
rg -n --sort path "\((atom|volatile!|js/Map\.|js/WeakMap\.|js/Set\.|empty-cache|react/createContext)[ )]" implementation/hicasso/src

# C2 — module-level JS literals. `rstate` and `scratch` are `#js` literals with
#      no constructor call, so C1 cannot see them; the indent bound is what
#      separates a def body from a literal built inside a fn.
rg -n --sort path "^\s{0,7}#js [\{\[]" implementation/hicasso/src

# C3 — every writer. A mutable owner with no writer is not one; an owner this
#      roster missed would surface here as a write to an unlisted name.
rg -o --sort path "(swap!|reset!|vswap!|vreset!) [!a-z-]+" implementation/hicasso/src | sort -u

# C4 — mutation of the module-level JS objects, which no ref-writer search sees.
rg -n --sort path "(unchecked-set|js-delete) (tag-cache|prop-cache|keywarn|rstate|scratch)|set! \(\.-[a-zA-Z]+ (rstate|scratch)\)" implementation/hicasso/src
```

C3 answers with three names that are **not** module state and are listed here so a re-run does not re-investigate them: `counter` and `watchers` occur only inside docstrings, quoting code that was retired, and `armed?` is a local `volatile!` in `impl.intent`'s callback gate, one per lowered callback.

## The dispositions

Three, and the middle one is the common case.

- **Frame-scoped** — the owner is one table, but every entry is qualified by the frame that owns it, so two frames cannot address the same entry. This is scoping achieved by keying; see [below](#why-keying-and-not-scoping).
- **Justified in place** — process-wide is correct, and the row says why. Migrating it would add indirection without buying isolation, and in one case ([`!anchor-seq`](#the-page-wide-id-namespace)) would break the thing it is trying to protect.
- **Left, with the reason** — nothing here today.

### Frame-scoped by keying

| Owner | Site | Key | Why the key is the scope |
|---|---|---|---|
| `impl.collector/!cells` | `collector.cljs:580` | `[frame-kw query-v]` | The sub-key is frame-qualified at mint, so the same query read under two frames occupies two cells with two reader lists and two reactions. A cross-frame read is not a collision that isolation must prevent; it is unaddressable. |
| `impl.collector/!entries` | `collector.cljs:1222` | hash of the read *sequence* | The sequence is a sequence of sub-keys, so it inherits their frame qualification. Two boundaries share an entry only when their read sequences are identical, which across frames they cannot be. The bucket hash selects; `entry-matches?` still compares every key pairwise, so the hash can only ever produce a false negative. |
| `impl.frames/!frame-ops` | `frames.cljs:72` | `frame-kw` + incarnation token | The row carries the incarnation it was minted under and a lookup replaces a row whose incarnation has been superseded, so the table is keyed by *address and identity*, not address alone. `rf2-hic-013` is the bead that made the second half necessary. |

### Justified in place

| Owner | Site | Kind | Justification |
|---|---|---|---|
| `impl.collector/rstate` | `collector.cljs:435` | render-extent slots | One JS object holding the frame, two provenance flags, the resolved entry, the cold-probe box and the body-run counter. Legal because **boundary bodies do not nest** — a body returns Hiccup and the codec turns a child boundary into an *element*, which React runs later, after this body has returned. Every slot is nil outside a render, which is what makes `sub` outside a boundary a loud refusal rather than a silent read of whichever frame was ambient. Its own docstring carries the argument. |
| `impl.collector/scratch` | `collector.cljs:450` | render-extent buffer | One array, reset by overwriting `.-length`, holding the running body's reads in read order. There is deliberately exactly one: a second would mean telling two render attempts apart, and that is the per-render ledger I5 forbids. Same non-nesting argument. |
| `impl.collector/!dirty` | `collector.cljs:581` | flush-extent set | Holds **cells**, not keys. `flush!` unions each dirty cell's own reader list, and a cell's readers are the boundaries that read that frame-qualified key — so a dirty cell in one frame reaches only that frame's boundaries. The set being page-wide unions *scheduling*, never ownership. |
| `impl.collector/!deferred` | `collector.cljs:583` | flush-extent set | The same union, held across a macrotask when a flush lands mid-render (React rejects a render-phase update on another component). Same reasoning: the members are boundaries already selected by cell readership. |
| `impl.collector/!batching` | `collector.cljs:582` | commit-window flag | One page-wide commit window, so one browser turn produces one flush. A concurrent write under another root joins the open window rather than flushing early; what that changes is *when* its boundaries are notified, never *which*. Per-root windows would split one turn into two flushes for no correctness gain. |
| `impl.collector/first-registration-armed` | `collector.cljs:854` | arming latch | A `defonce` that exists for its side effect and is deliberately never read — it installs the substrate's registration hook. Once per process is the whole semantics; a per-root arming would install the hook N times and run it N times per registration. |
| `impl.generation/!generation` | `generation.cljs:25` | monotone counter | The commit generation, bumped once per flush that moved something. It is a number, not ownership state, and its comparability is the point: boundaries under different roots read the same substrate and their epochs are compared against one basis. Per-root counters would make two boundaries' stamps incomparable, which is the failure `commit-basis` exists to prevent. |
| `impl.generation/!registry-epoch` | `generation.cljs:26` | monotone counter | Counts `:sub` registrations — first-time and replacement alike. What it counts is the **substrate's own registry**, which is process-wide; a per-root epoch would be a number about a thing that is not per-root. It is the term the HMR contract (`rf2-hic-015`) rides. |
| `impl.codec/tag-cache` | `codec.cljs:295` | intern cache | Prototype-free JS object, keyed by the literal tag string, valid for the build's life. No ownership semantics: a hit returns a `ParsedTag` describing the *source text*, which cannot differ between frames. |
| `impl.codec/prop-cache` | `codec.cljs:393` | intern cache | The same, keyed by prop name, seeded through `seed-prop-cache!` with the three names whose emitted spelling is a rule rather than a memo. `reset-caches!` re-seeds through that same fn, so a suite fixture cannot leave the cache holding a spelling a cold build would not have. |
| `impl.codec/keywarn` | `codec.cljs:2020` | dev-only dedupe | `nil` in production — every reader sits behind `goog.DEBUG`, so the object, its tables and its message strings fold away under `:advanced`. Per-page dedupe of a console warning is React's own semantics for the same warning, and a full page reload resets it either way. |
| `impl.evidence/!evidence-sink` | `evidence.cljs:56` | instrumentation seam | One sink per page, `nil` until a tool attaches, and read as the outermost form of every tap's guard so a detached runtime builds nothing. The events it carries name their frame and their boundaries, so one sink loses no attribution; a per-root sink would make a tool enumerate roots to hear the page. |
| `impl.error/!sources` | `error.cljc:76` | declaration ledger | `"<ns>/<sym>"` → the coordinate its `defview`/`defhost` captured, written once at namespace load and read only when a refusal is being minted. The key is globally unique by construction, and the map is empty in production. Declaration-time bookkeeping, not runtime state. |
| `impl.error/!origin` | `error.cljc:88` | declaration extent | One slot rather than a stack, for the same non-nesting reason `rstate` is one object. `traced-boundary` saves and restores anyway, on the principle that an invariant cheap to survive should be survived rather than relied upon. Dev-only. |
| `impl.state/!defaults` | `state.cljc:208` | registration ledger | `concern` → the `:default` it was registered with. It exists so a second `reg-state` for one concern with a *different* default is refused rather than silently changing what every un-set instance reads. This is the same class of thing the sub and event registries are, and they are process-wide too; scoping it per root would let two roots disagree about what an un-set instance reads, which is precisely the refusal's subject. |
| `impl.overlay/!anchor-seq` | `overlay.cljs:135` | id namespace | See [below](#the-page-wide-id-namespace) — the one row where scoping would be a defect rather than a cost. |

#### The page-wide id namespace

`!anchor-seq` mints CSS dashed-idents (`--rf-overlay-N`) for overlay *instances*, one per instance rather than one per anchor id, because two overlays may legitimately share a trigger and a shared ident would make each one's teardown erase the other's.

Root-scoping the counter would break it. A CSS anchor name lives in **one namespace per document**, not one per React root: two roots each minting `--rf-overlay-1` would produce two live overlays claiming one name, and the second claim would silently steal the first's positioning. The counter is page-wide because the namespace it allocates into is page-wide, and that is a correctness requirement, not an accident of where the `defonce` was written. A future audit reading *mutable global, therefore migrate* would introduce the bug this row exists to prevent.

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
| `impl.roots/adoption-context` | `roots.cljs:109` | `defonce` | A React context object. `defonce` is load-bearing: fibers that survive a reload hold the old context, and re-minting the identity would leave providers and consumers matching on different objects. Default `nil`, which `adopting?` reads as closed, so every tree with no provider above it gets the right answer with no provider, no object and no branch. |
| `impl.controlled/shadow-input` | `controlled.cljs:657` | `def` | The composition-shadow components. Plain `def`, and the asymmetry with the row above is deliberate rather than an oversight: re-minting these on a reload remounts every controlled field, which is what a framework developer editing *this file* wants to see, and an app-code edit never reaches them because shadow-cljs reloads the namespaces that changed. Same argument `keywarn`'s docstring makes for its own plain `def`. |
| `impl.controlled/shadow-textarea` | `controlled.cljs:658` | `def` | As above. |
| `impl.boundary/boundary` | `boundary.cljs:267` | `def` | The error-boundary React class, built once at load. |
| `impl.portal/portal` | `portal.cljs:131` | `def` | Component identity. |
| `impl.presence-react/presence` | `presence_react.cljs:177` | `def` | Component identity. |
| `impl.codec/raw-gate` | `codec.cljs:3166` | `def` | A `#js` object carrying `displayName`, written at construction and never again. |

## Two things this page does not cover, and why

**SSR request scope is vacuous today.** The adversarial-risks *Process-global ownership* row names *independent roots and SSR requests*, and only the first half has a subject: this package publishes **no server-render door**. `impl.mount/hydrate-root!` is built and witnessed, but its counterpart — `re-frame.hicasso.server` — does not exist, and the public door's own commentary holds the absence deliberately (`rf2-k1mp`). Every owner on this page is therefore a *page* question and not yet a *request* question. The day a server-render entry lands, all nineteen become request-scope questions at once, because a Node process serving two requests concurrently shares every one of them; whoever files that bead should start here.

**The IME row of `rf2-hic-016` is assumed, not observed.** Nothing on this page depends on it — no owner here is touched by composition handling, and `impl.controlled`'s shadow state is per-field React `useState` rather than module state — but the note is recorded so that no future reader treats a citation of hic-016 reached through this page as evidence of native-IME behaviour. The manual native-IME session was attempted, abandoned as impractical, and the bead closed on an operator ruling assuming success; its three-browser deliverable is genuinely on main and verified, and that row is not.

## The one false positive worth naming

`implementation/hicasso/src/re_frame/hicasso/impl/mount.cljs:117` matches every census search with `(defonce ^:private !root (atom nil))`, and it is not a global: it sits **inside the `render!` docstring**, as the consumer's hot-reload example. The `reset! !root` that C3 finds three lines later is the same example. It is called out because it is the only census hit in that file, and an audit that took it at face value would open a collision with whichever bead owns `mount.cljs` over a global that does not exist.
