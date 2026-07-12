# Draft — `re-frame.ui.react` interop contract: the seven wrappers

**Status:** draft · 2026-07-12 09:08 AUSEST · the S1 contract item the blessed table
names (⟨12 §2b⟩ ".react/* … call-shape spec is an S1 contract item"; the tier itself
lands S3 with the events/debugging consumer work). Stage 1 closed without this document;
it is the deliverable of the S1 contract-item bead filed 2026-07-12. Answers codex2
**Q54** (exact signatures, consumers, hook-signature hashing, JVM use, SSR, HMR) and
codex2 **F5**'s `lazy`-vs-Suspense question. Genuinely open items are marked
**[S3-CONFIRM]** — recommendations to confirm when the tier lands, never silent
inventions. House rules: the blessed §2 table owns WHAT exists; 08 §2 owns WHEN; this
draft owns the call shapes and host behaviour of exactly these seven names.

Namespace `re-frame.ui.react` (alias `react/` below), artifact `day8/re-frame2-ui`
⟨12 §1, rewrite §R-3⟩. Anything not named here does not exist in this tier.

## 1. What this tier is, and is not

The seven wrappers are the **interop tier of the foreign boundary**: they exist for
views that must participate in a *foreign* React world — an exported `defview` living
inside a legacy/foreign parent (`ui/->react`, doc 10's per-subtree migration), or a
foreign widget embedded inside a `defview` whose API demands refs, contexts, ids, or
code-splitting. They are **not** a second state or reactivity model, and the roster of
absences is deliberate ⟨rewrite §Removed forms⟩:

| Absent | Because |
|---|---|
| `use-state` | `local` is the one ephemeral-state spelling ⟨02 §5⟩ |
| `use-memo` / `use-callback` | unnecessary under `rf=` value semantics + the handler law (per-site stable identity is the compiler's job, not the author's) ⟨02 §3, rewrite §Memo-by-default⟩ |
| `use-reducer` / `use-sync-external-store` | a second state model — normative absence ⟨rewrite §Removed forms⟩ |
| `use-transition` / `use-deferred-value` | `startTransition` over app-db is a non-goal ⟨rewrite §Removed forms⟩ |
| Suspense-as-authoring-surface | non-goal; see §6 for why `lazy` is nevertheless in the surface |

Grammatically the wrappers are ordinary fns (the §2 table's kind column), but the
compiler treats their call sites exactly as it treats `sub`/`lease` sites: recognised by
resolved head symbol against a closed FQN set inside the S1 expression walk, indexed
into the manifest, and position-checked at compile time ⟨analyze.cljc `walk-expr`,
`sub-fqns`/`lease-fqns` mechanism⟩. Six of the seven (`use-ref`, `use-effect`,
`use-layout-effect`, `use-effect-event`, `use-context`, `use-id`) are **host hooks** and
obey the position law of §3. `lazy` is **not a hook** — it is a def-level constructor
(§2.7) and is exempt from the position law but subject to its own (§3, last paragraph).

## 2. Per-wrapper call shapes

All wrappers follow React 19's hook semantics (the S-3 spike's validated release line,
React 19.2 ⟨03 §3⟩) with exactly the divergences stated here. Argument order mirrors
React deliberately — this tier anchors to the foreign mental model, where the internal
body forms (`effect` etc.) anchor to re-frame2's.

### 2.0 The deps law — `rf=` per slot, and why that is safe

Everywhere this tier takes a deps vector, deps are a **CLJS vector compared `rf=` per
slot** against the previous render's vector (arity change ⇒ re-run). `rf=` is the ruled
per-slot equality `Object.is(a,b) OR (= a b)` ⟨rewrite §`ui/defview` Memo-by-default,
RULED 2026-07-12⟩. This diverges from React's `Object.is`-only deps comparison in one
direction only: **`rf=` is a strict refinement**. Every pair `Object.is` calls equal,
`rf=` calls equal — so the wrappers never re-run *more* often than React would. The
extra equalities are exactly value-equal CLJS data (fresh-but-`=` vectors, maps,
records, `js/Date`): immutable values, so a skipped re-run is unobservable except as an
economy — no stale closure can hide in a value that cannot change. Host/foreign values
(plain JS objects, arrays, fns, React elements) fall through to identity — precisely
`Object.is` behaviour, so where mutation matters there is no divergence at all. One
equality doctrine across memo, `effect`, and this tier; a second per-tier equality
regime would itself be the trap. Consequence, stated: because comparison is internal
(kernel-held previous-deps slot, not React's native deps array), every wrapper occupies
a **fixed-shape host hook call** — deps arity may vary between renders without touching
React's hook order (§3 relies on this).

### 2.1 `use-ref`

```clojure
(react/use-ref)          ;; => ref (initial current: nil)
(react/use-ref initial)  ;; => ref (initial current: initial)
```

Wraps `useRef`. Returns the host ref object — a foreign value; read/write via
`(.-current ref)`. Assignment never re-renders (contrast `local`, which does — that
difference is `use-ref`'s reason to exist next to `local`). The returned object is the
**preferred object ref** for `:ref` positions ⟨02 §3: object refs preferred; callback
refs explicit `ui/raw-fn`⟩ and for foreign imperative APIs that want a mutable
container. No deps vector.

### 2.2 `use-effect`

```clojure
(react/use-effect setup)       ;; runs after every commit
(react/use-effect setup deps)  ;; deps: CLJS vector, rf= per slot; [] ⇒ connect-only
;; => nil
```

Wraps `useEffect` (passive, after paint). `setup` is a zero-arg fn returning a cleanup
fn or nil. Cleanup honoured on dep change, disconnect, and unmount; StrictMode dev
replay is expected and MUST be idempotent-safe — identical contract to the internal
`effect` ⟨03 §6⟩, differing only in spelling (React argument order, explicit fn value)
and audience (foreign-boundary sync). Deps per §2.0.

### 2.3 `use-layout-effect`

```clojure
(react/use-layout-effect setup)
(react/use-layout-effect setup deps)  ;; => nil
```

Wraps `useLayoutEffect`: fires after DOM mutation, **before paint** — for foreign
measure-then-mutate work that would flicker under passive timing. Same setup/cleanup/
deps contract as §2.2. Layout timing sits *after* the commit reconciler's
publish-before-interaction step ⟨03 §3 commit algorithm step 6⟩ — a layout effect
observes the committed frame, never a mid-commit state.

### 2.4 `use-effect-event`

```clojure
(react/use-effect-event f)  ;; => stable fn
```

Wraps `useEffectEvent` (stabilised in React 19.2 — the spike's release line). Returns a
fn with **stable identity across renders** whose body always sees the latest render's
values. **The no-deps contract:** `use-effect-event` takes *no* deps vector — that is
the point of the primitive — and the returned fn **MUST NOT appear in any deps vector**
(its stability makes it a lie there) and **MUST NOT be called during render** (host
throws; it is effect-phase machinery). The compiler emits a dev diagnostic where a
returned effect-event binding is statically visible inside a deps vector
**[S3-CONFIRM — best-effort static detection scope]**. Boundary note: this wrapper does
not change the event-boundary law — app intent still goes through event vectors /
`ui/event`; `use-effect-event` is for the *foreign* pattern of "latest-values callback
invoked from an effect" ⟨02 §3⟩.

### 2.5 `use-context`

```clojure
(react/use-context ctx)  ;; => the context's current value (uncoerced JS value)
```

Wraps `useContext`. `ctx` is a **Context object created at the foreign boundary**
(`React.createContext` in foreign code, arriving as an opaque foreign value) — that is
the *only* thing it takes. Internal re-frame2 state never rides React context: frames
have `frame-root`/`frame-provider`, app state has `sub` ⟨03 §8⟩; `use-context` exists so
a `defview` embedded in a foreign tree (via `ui/->react`) or wrapping foreign widgets
can read the foreign world's providers (theme, i18n, router contexts). The returned
value is handed through uncoerced — converting it is the caller's boundary job ⟨02 §2:
foreign interop hands raw JS values through⟩.

### 2.6 `use-id`

```clojure
(react/use-id)  ;; => string
```

Wraps `useId`: a host-generated, tree-positional identifier token, prefixed by the
root's `identifierPrefix`. Determinism under SSR rides the root contract: the prefix
default is **`"rf2-" + root-id-slug + "-"`** (`:page/shop` → `"rf2-page-shop-"`), the
per-page prefix-uniqueness check makes cross-root collision impossible, and hydrating
roots MUST take the server's prefix from the manifest — client opts carrying
`:identifier-prefix` into `hydrate-root` are an error precisely because a divergent
prefix breaks `use-id` hydration ⟨root-identity-and-mount.md §3, §7 Layer 2⟩. For what
`use-id` yields on the JVM emitter — which is not React and does not run React's
tree-positional algorithm — see §4.

### 2.7 `lazy`

```clojure
(def HeavyChart (react/lazy load-thunk))
(def HeavyChart (react/lazy load-thunk {:fallback tpl}))  ;; opt grammar [S3-CONFIRM]
```

Wraps `React.lazy` over a **foreign component-loading thunk**: `load-thunk` is a
zero-arg fn returning a JS Promise resolving to a foreign React component (or a module
object with a component default export, per the host contract). The result is a foreign
component reference, legal exactly where foreign heads are legal:
`[HeavyChart {…}]` ⟨02 §2 template grammar, foreign heads⟩. **`lazy` is def-level, not
a body form**: like `React.lazy`, calling it inside a render body mints a new component
type per render and remount-loops — see §3 for the enforcement split.

Loading door, recommended shape **[S3-CONFIRM]**: the `{:fallback tpl}` option declares
the per-site loading content; the fallback MUST be capability-free (the same
compiler-checked rule as `client-only` fallbacks ⟨06 §3⟩), and the emitter contains the
suspension by wrapping *that one foreign element* in a minimal host Suspense element —
single-site containment, never an orchestration surface. Using a `lazy` component with
no `:fallback` and no foreign-owned boundary above it is a compile error rather than a
silent suspend-to-unknown-ancestor **[S3-CONFIRM — error vs. dev diagnostic]**. This
keeps 06 §5 intact: `:rf/suspense-boundary` remains the low-level marker and no general
authoring sugar ships.

## 3. Position law and the hook-signature hash

**The position law (the six hooks).** Wrapper sites are body forms in `defview` in the
same grammatical sense as `sub`/`local`/`effect` — but with a *stricter* placement rule,
because each one is a genuine host hook call and React's hook order must be static.
A wrapper site is legal only where it **evaluates unconditionally, exactly once per
render**: the straight-line top level of the view body (outer `let` bindings and
positions reachable without crossing a control form, a fn form, or a loop).
`sub`'s conditional reads are legal because `sub` is not a hook ⟨03 §2⟩; the wrappers do
not get that licence. Compile-time rejection reuses the S1 analyzer's finite-sites
machinery — the same `walk-expr` FQN-set recognition and `:in-loop?` `fail!` path that
rejects `sub`-in-loop today ⟨analyze.cljc⟩ — extended at S3 with conditional-position
and inside-fn flags on the same env walk. Rejections are didactic, with the standard
escapes ("hoist to the top of the view body"; "extract a keyed child view").
Proposed roster ids: `:rf.ui.compile/react-hook-in-loop`, `-in-branch`, `-in-fn`
**[S3-CONFIRM — exact roster split (three ids vs. one)]**. These are compile-time
diagnostic ids: they join the S1e compile-error roster and get **no Spec 009 catalogue
rows** — RULED (shepherd, rf2-kvtn97 NOTES, 2026-07-12; the S1 analyzer-id precedent),
superseding this draft's earlier "catalogue rows at promotion" phrasing.

**`lazy`'s position law.** Def-level only: a `react/lazy` call inside a `defview`
body/template is rejected where statically recognisable (same FQN-set walk), and the
kernel dev-diagnoses a `lazy` invocation observed during a render pass for the dynamic
remainder **[S3-CONFIRM — dev-diagnostic mechanics]**.

**Hash contribution — `fingerprint.cljc` is ground truth for the mechanism.** The
hook-signature hash is today `"hs1-"` over the version-1 input
`[1 {:locals [...] :effects [...]}]` — the **ordered host-hook plan**, with `sub` sites
deliberately excluded because they reconcile through the single ViewCell binding, not
React hook order ⟨fingerprint.cljc; 03 §10⟩. The six hook wrappers are additional real
host hook calls, so they MUST contribute. Contract:

- Each of the six contributes **one entry, in source order, to the ordered plan**:
  its kind keyword (`:ref` / `:effect` / `:layout-effect` / `:effect-event` /
  `:context` / `:id`). Kind + order only — **never** deps contents, deps arity, context
  identity, or argument forms: §2.0's fixed-shape lowering means none of those touch
  host hook order, so editing a deps vector or a setup body is a same-signature edit
  (state preserved), while adding, removing, or reordering any wrapper changes the plan.
- Recommended input extension: the plan map gains a `:react [...]` vector and the
  leading shape-version integer bumps — `[2 {:locals [...] :effects [...]
  :react [...]}]`, prefix `"hs1-"` unchanged (the prefix versions the algorithm, the
  integer versions the input shape). Any extension changes every existing hash
  (canonical-EDN maps re-serialise), so the bump is taken honestly as a **one-time
  global signature change → one clean remount wave when S3 lands** — pre-alpha, no
  shim **[S3-CONFIRM — integer bump vs. alternative encoding]**.
- `lazy` contributes **nothing** to the hook-signature hash (not a hook, not in the
  plan). Its effect on identity is ordinary code identity: see §5.
- Ripple, stated: the hook signature is a component of the build-digest triples
  (`[view-id template-fingerprint hook-signature]` ⟨fingerprint.cljc⟩), so adding a
  wrapper to any view changes `build-digest`, and a stale SSR page's manifest then fails
  digest validation → that root takes the loud client-fresh path ⟨06 §2⟩. Correct and
  intended.
- Honest asymmetry, documented for authors: dev's fixed full hook skeleton makes adding
  your first `sub` a same-signature edit (I-15); no such pre-allocation is possible for
  the wrappers (their count is unbounded and hook counts must be static), so **adding
  your first `use-ref` is a remount edit** — dev and prod alike, and the console says
  why ⟨03 §10⟩.

**Manifest.** Every wrapper site (all seven names) is recorded in the compiler manifest
with kind + source coordinates + template path, like effect/lease sites — consumed by
Xray/Story/agents before mount ⟨rewrite §View identity and the instrumentation
surface⟩. Site identity follows the standard anchor + structural path + generation
scheme (I-8).

## 4. Host behaviour: JVM structural render, SSR, `render-static`

The JVM subset table ⟨06 §1⟩ is silent on this tier by name; the rows below extend it
in its own idiom — aligned with its existing `local`/`effect`/refs rows — and every row
the subset table does not already imply is marked. SSR and `render-static` are the same
story: one JVM emitter, one contract ⟨06 §1⟩.

| Wrapper | JVM structural render |
|---|---|
| `use-ref` | yields an **inert ref** (`current` nil, stays nil) — aligned with "refs: absent"; passing it to `:ref` positions is fine (refs never appear in JVM tree output) |
| `use-effect` / `use-layout-effect` | **do not run; recorded as capability metadata** — exactly the existing `effect` row |
| `use-effect-event` | metadata-only; returns a fn that raises `:rf.error/jvm-host-op` if invoked — aligned with the `local` setter's typed error ⟨06 §1, 03 §11⟩ **[S3-CONFIRM — id reuse vs. dedicated id]** |
| `use-context` | returns the **JVM-provided test value** (`ui.test/render` option, recommended `{:react-context-values {token value}}`) **or fails loud** with `:rf.error/jvm-host-op` — never silently nil. Keying **[S3-CONFIRM]**: the JS Context object has no JVM identity; the JVM-side argument (typically a reader-conditional token) keys the lookup |
| `use-id` | yields a **deterministic inert string**: resolved identifier-prefix + a per-render site counter over the view's occurrence path **[S3-CONFIRM — exact derivation]**. Stated honestly: this does NOT reproduce React's tree-positional algorithm, so a `use-id` value serialised into a **hydrating** root's markup is a mismatch risk — build-time diagnostic on hydrating paths **[S3-CONFIRM — diagnostic vs. error]**; static roots are safe (ids need only page-uniqueness there) |
| `lazy` | **never invokes the thunk** (no foreign execution on the JVM ⟨06 §3⟩); renders its declared `:fallback`, else `:nothing` |

Capability bits ⟨06 §3 `requires-client-runtime?`⟩: the transitive capability already
names effects, refs, context, and foreign components — `use-effect`/`use-layout-effect`/
`use-effect-event` fold into *effects*, `use-ref` into *refs*, `use-context` into
*context*, `lazy` into *foreign components*; a view using any of these five cannot be
part of a proven-static root. `use-id` is recommended **exempt** (inert deterministic
ids are static-safe; the hydrating-path diagnostic above carries the real risk)
**[S3-CONFIRM — capability-bit mapping against the 05 §1 sixteen-bit vocabulary]**.

## 5. HMR and Fast Refresh

The 03 §10 contract applies unmodified; this section states only what the tier adds.

- **Add / remove / reorder any of the six hooks ⇒ hook-signature change ⇒ deliberate
  clean remount** — never a corrupted hook order ⟨03 §10⟩. The remount runs full effect
  cleanup (unmount semantics), then a fresh mount.
- **Same-signature edits preserve state**, and Fast Refresh's own contract applies to
  effect wrappers: the host **re-runs effects on every refresh of the component —
  cleanup then setup — even when deps are unchanged**. Implementation pin: because deps
  comparison is kernel-internal (§2.0), a naive implementation would wrongly suppress
  that re-run ("deps `rf=`-equal ⇒ skip"); therefore the **view generation participates
  in the internal comparison** — a generation bump forces cleanup + re-run regardless of
  deps. The `rf=` economy must never out-vote Fast Refresh semantics.
- `use-effect-event`: identity stays stable across same-signature refreshes; the
  latest-body slot is swapped, so the stable fn sees the edited body immediately.
- `use-ref`: `current` survives same-signature refreshes (host state preservation);
  reset on remount.
- `use-context`: host-managed; re-reads the provider on the refresh render.
- `lazy`: standard React behaviour, stated so nobody is surprised — re-evaluating the
  defining module re-runs `react/lazy` and mints a **new component identity**, so the
  foreign subtree below it remounts on that module's reload. That is the foreign
  boundary's cost, not a `ui` HMR defect; the stable-shell machinery covers `defview`s,
  not foreign component objects ⟨03 §10; reagent-compat-boundary.md §HMR outward⟩.

## 6. Why `lazy` is in the frozen surface while Suspense-as-loading is a non-goal (F5)

The two live at different layers, and conflating them was F5's fair challenge. The
non-goal is **Suspense as loading orchestration for internal views** — throwing
promises to model app loading states is rejected because loading state in re-frame2 is
*explicit application state*: resources and subscriptions carry it, views read it, and
the guide's canonical loading UI is a `sub` over resource state, replayable and
tool-inspectable like everything else ⟨guide 03; rewrite §Loading state is explicit;
06 §5⟩. `lazy` claims none of that territory: it is **code-splitting interop for
foreign components** — a loading *door* at the foreign boundary, where a heavy foreign
widget (editor, chart library) arrives as a chunk whose arrival is a host-platform
fact, not application state. Its suspension is contained at the single foreign site
(§2.7), its fallback is capability-free markup rather than orchestrated loading UI, and
internal views never suspend. Dropping `lazy` would not defend the non-goal; it would
only force every code-split foreign widget through a hand-rolled `ui/raw` + foreign
Suspense sandwich — the same door, minus the contract.

## 7. Consumers, honestly — and the demand-bar escape

The blessed table's consumer citation for this row was **corrected 2026-07-12** (codex2
F5): guide 03's chart example uses `local`/`effect`/`ui/raw-fn`, **not** these hooks —
there is **no current guide consumer** ⟨12 §2 row `.react/*`⟩. The intended consumers,
named:

- **Migration bridges** — a `ui/->react`-exported view mounted inside a legacy/foreign
  parent (doc 10's per-subtree migration; reagent-compat-boundary.md): reading the host
  application's foreign contexts (`use-context`), aria id-pairing inside the host page
  (`use-id`), latest-values callbacks demanded by the host's effect-driven APIs
  (`use-effect-event`).
- **Foreign-widget embedding** — foreign heads inside a `defview` whose APIs demand a
  mutable ref container without re-render-on-write (`use-ref`, vs. `local`'s
  re-rendering semantics), measure-before-paint sync (`use-layout-effect`), or arrive
  code-split (`lazy`).

The escape stays armed exactly as blessed: **the S1 demand-bar audit confirms a
concrete consumer, or this row returns to Mike as a row-level delta** — and since S1
closed without one materialising in the suite, the audit obligation rides forward: if
none materialises by S3 dispatch, the row goes back as a Mike delta before the tier's
beads cut ⟨12 §2; 12 §4 blessing protocol⟩.

## The [S3-CONFIRM] roster

1. Hook-signature input extension mechanics: `:react` vector + leading shape-version
   integer `1 → 2` (prefix `"hs1-"` unchanged); one-time global remount wave at the S3
   upgrade (§3).
2. Position-law compile-error roster split (`react-hook-in-loop` / `-in-branch` /
   `-in-fn` vs. one id) + S1e roster keys (§3). The catalogue-row half is RESOLVED, not
   open: no Spec 009 rows for compile-time ids (shepherd ruling, rf2-kvtn97 NOTES,
   2026-07-12; the 009 prep batch drafted none).
3. `lazy`-in-render dev-diagnostic mechanics for dynamically-constructed cases (§3).
4. `lazy` option grammar: `{:fallback tpl}` capability-free per-site containment;
   no-fallback = compile error vs. dev diagnostic (§2.7).
5. `use-effect-event`-in-deps static detection scope (§2.4).
6. JVM `use-context` keying mechanism (JVM-side token) and whether the fail-loud path
   reuses `:rf.error/jvm-host-op` or earns a dedicated id (§4).
7. JVM `use-id` derivation (prefix + occurrence-path counter) and the hydrating-root
   mismatch rule: diagnostic vs. error (§4).
8. Capability-bit mapping of the seven onto the 05 §1 sixteen-bit vocabulary, including
   `use-id`'s recommended static-root exemption (§4).

---

*Sources:* ⟨12 §2/§2b⟩ blessed surface + stage matrix · ⟨02 §3/§5/§6⟩ handler/refs/
interop law · ⟨03 §2/§3/§6/§10/§11⟩ reactivity, effects, HMR, error taxonomy ·
⟨rewrite draft⟩ §Reactive reads, §Removed forms, §Hot reload, the ruled `rf=` ·
⟨06 §1/§3/§5⟩ JVM subset, capability/static policy, streaming posture ·
⟨root-identity-and-mount.md §3/§7⟩ identifier-prefix contract ·
⟨implementation/ui/src/re_frame/ui/fingerprint.cljc⟩ hook-signature-hash ground truth ·
⟨implementation/ui/src/re_frame/ui/compiler/analyze.cljc⟩ site recognition + finite-sites
mechanism · ⟨reviews/codex2.md Q54/F5⟩ · ⟨09 §codex2 disposition⟩ · React 19.2 hook
semantics. All spellings serialisable-British per house style.
