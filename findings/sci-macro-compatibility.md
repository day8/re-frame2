# SCI macro-compatibility audit for the Scittle playground (rf2-97zo)

Investigation bead. Output is this findings document; **no source-code
fixes** at this stage. The downstream implementation work belongs to
v1.1+ once the Scittle playground spike (rf2-mjxg) is greenlit.

Companion to `findings/svelte-style-playground-feasibility.md`
(rf2-mjxg) which selected **Scittle (SCI in the browser) + real
re-frame2 + Reagent v2** as the recommended playground substrate. That
finding flagged a single caveat: re-frame2 ships more macros than
re-frame v1 (notably the source-coord-stamping registration macros and
the `reg-machine` literal-spec walker), and "macro" plus "SCI" is a
known soft spot. This audit enumerates each macro and assesses it
without running it under SCI.

---

## Executive summary

**Total macros audited: 21.**

| Classification    | Count | Notes |
|-------------------|-------|-------|
| **PASS**          | 17    | Pure quasiquote splicing; rely only on `&form` metadata + hygienic gensyms. Standard SCI fare. |
| **NEEDS-CHANGE**  | 2     | Hit `requiring-resolve` at expansion time — won't resolve under SCI. Both have safe fallback paths already in the code; the fix is a one-line guard, not a redesign. |
| **UNKNOWN**       | 2     | Depend on how Scittle exposes `goog/DEBUG` and `js/performance` to SCI-evaluated bodies. Almost certainly PASS but flagged for the playground spike to confirm. |

The good news: **every one of the 17 PASS macros is a structural twin
of the macros sci.configs.re-frame already proves SCI-compatible
against re-frame v1.** They quasiquote a registration call, splice
`&form` metadata onto a `binding` form, and hand off to a runtime fn.
SCI parses-and-walks quasiquote forms exactly as Clojure does; the
only places re-frame2 deviates from re-frame v1 are the two flagged
macros and the four borderline cases.

### What an absolute upper-bound break looks like

If we did nothing, the **only** macro that would hard-fail under SCI
is `re-frame.core/reg-view`, because its body invokes
`requiring-resolve` at expansion time. That's the canonical
view-registration surface — without it, no Reagent component
registration works in the playground. The fix is mechanical (described
in the per-macro section below) and the underlying expander
(`expand-reg-view`) is itself SCI-compatible, so the work is a
one-namespace shim, not a rewrite of the registration story.

### What the playground spike must verify (not block on)

The two UNKNOWN macros both depend on **Scittle's runtime exposure of
host globals** rather than on the macro body itself:

- `re-frame.performance/mark-and-measure` — needs `js/performance`
  exposed to SCI-evaluated code. Standard Scittle plugin terrain;
  expected to work but verify.
- `reagent2.ratom/reaction` — needs `reagent2.ratom/make-reaction`
  exposed via the SCI namespace mapping. Same `sci/copy-ns` pattern
  Scittle uses for `reagent.ratom/make-reaction` against Reagent v1;
  the v2 export sits on the rf2-pfez bead (Reagent v2 SCI export
  upstream-vs-vendor).

---

## Methodology

For each macro I read the body and assessed against these axes:

1. **Does it touch `&env` / `*compile-files*` / `cljs.env` / `cljs.analyzer`?** — SCI does not run the CLJS analyzer pass; macros that gate on `(:ns &env)` to fork CLJ vs CLJS expansion need attention.
2. **Does it `requiring-resolve` / `eval` at expansion time?** — SCI's classpath is the SCI-exposed namespace map, not the JVM classpath; `requiring-resolve` of a non-exposed namespace returns `nil`.
3. **Does it only splice `binding` / `let` / `fn` forms with `&form` metadata + hygienic gensym?** — Standard SCI-friendly shape.
4. **Does it generate Java interop (`Bar.`, `set!`, type hints)?** — JVM-only macros; CLJS macros don't emit these.
5. **Does it emit CLJS-only forms (`js/...`, `.foo`)?** — SCI's reader and evaluator handle these natively when the host symbol is exposed via the Scittle plugin's `sci/init` config.
6. **Does it walk literal forms at expansion time and emit a literal data structure?** — SCI supports this provided the walker uses only standard collection ops.

The audit is **static** — I did not boot a Scittle instance and try
each macro. The follow-up work is to do that in the spike per rf2-mjxg.

---

## Per-macro assessment

### Source-coord registration macros (Spec 001 §Source-coordinate capture)

The first seven macros are structural copies of each other — only the
final runtime call differs. I describe the shape once under
`reg-event-db` and refer back for the rest.

#### 1. `re-frame.core/reg-event-db`

- **Source:** `implementation/core/src/re_frame/core.cljc:167`
- **Purpose:** Register an event-db handler with source-coord stamping.
- **Classification:** **PASS**.
- **Shape of expansion:** Reads `(meta &form)` to capture `:line` /
  `:column`, captures `(ns-name *ns*)` and `*file*` at expansion time,
  emits `(binding [source-coords/*pending-coords* {...}] (events/reg-event-db ~id ~@args))`.
- **Rationale:** SCI provides `(meta &form)` to macros (it's how SCI's
  re-frame plugin works in the first place), exposes `*ns*` and
  `*file*` at expansion time, and `binding` with a dynamic var is one
  of SCI's best-trodden paths. The expansion contains no
  `requiring-resolve`, no `eval`, no `&env` introspection, no Java
  interop. The runtime call lands on `events/reg-event-db`, which is
  pure CLJS — SCI walks it the same as any function call.
- **Spec ref:** API.md row 24 / 001-Registration.md §Source-coordinate
  capture.

#### 2. `re-frame.core/reg-event-fx`

- **Source:** `implementation/core/src/re_frame/core.cljc:187`
- **Classification:** **PASS**. Identical shape to `reg-event-db`;
  dispatches to `events/reg-event-fx` at runtime.
- **Spec ref:** API.md row 25.

#### 3. `re-frame.core/reg-event-ctx`

- **Source:** `implementation/core/src/re_frame/core.cljc:207`
- **Classification:** **PASS**. Identical shape; dispatches to
  `events/reg-event-ctx`.
- **Spec ref:** API.md row 26.

#### 4. `re-frame.core/reg-sub`

- **Source:** `implementation/core/src/re_frame/core.cljc:227`
- **Classification:** **PASS**. Identical shape; dispatches to
  `subs/reg-sub`.
- **Spec ref:** API.md row 27.

#### 5. `re-frame.core/reg-fx`

- **Source:** `implementation/core/src/re_frame/core.cljc:247`
- **Classification:** **PASS**. Identical shape; dispatches to
  `fx/reg-fx`.
- **Spec ref:** API.md row 28.

#### 6. `re-frame.core/reg-cofx`

- **Source:** `implementation/core/src/re_frame/core.cljc:267`
- **Classification:** **PASS**. Identical shape; dispatches to
  `cofx/reg-cofx`.
- **Spec ref:** API.md row 29.

#### 7. `re-frame.core/reg-frame`

- **Source:** `implementation/core/src/re_frame/core.cljc:287`
- **Classification:** **PASS**. Identical shape; dispatches to
  `frame/reg-frame`.
- **Spec ref:** API.md row 30.

### Late-bound optional-artefact macros

These add one wrinkle to the source-coord shape: the runtime call
looks the producing fn up through the `late-bind/get-fn` hook table
and throws a clear error when the artefact isn't on the classpath.
Mechanically the macro expansion is the same shape as above — the
`if-let / throw` lives inside the quasiquote, not at expansion time.

#### 8. `re-frame.core/reg-flow`

- **Source:** `implementation/core/src/re_frame/core.cljc:460`
- **Purpose:** Register a flow (day8/re-frame2-flows artefact). Spec 013.
- **Classification:** **PASS**.
- **Rationale:** Same shape as `reg-event-db`. The emitted form does
  `(if-let [f# (late-bind/get-fn :flows/reg-flow)] (apply f# (list ~@args)) (throw ...))`.
  `apply`, `list`, `if-let`, `throw`, `ex-info` are all standard SCI
  primitives. No expansion-time IO.
- **Spec ref:** API.md row 37.

#### 9. `re-frame.core/reg-route`

- **Source:** `implementation/core/src/re_frame/core.cljc:493`
- **Purpose:** Register a route (day8/re-frame2-routing artefact). Spec 012.
- **Classification:** **PASS**. Same shape as `reg-flow`.
- **Spec ref:** API.md row 38.

#### 10. `re-frame.core/reg-app-schema`

- **Source:** `implementation/core/src/re_frame/core.cljc:527`
- **Purpose:** Register a Malli schema at an app-db path
  (day8/re-frame2-schemas artefact). Spec 010.
- **Classification:** **PASS**. Same shape; the only twist is an
  expansion-time `(or opts {})` to default the third arg, which is
  pure Clojure.
- **Spec ref:** Spec 010 §Per-frame schemas.

#### 11. `re-frame.core/reg-error-projector`

- **Source:** `implementation/core/src/re_frame/core.cljc:823`
- **Purpose:** Register an error projector — SSR error-shape
  customiser. Spec 011 §Server error projection.
- **Classification:** **PASS**. Same shape; dispatches to a runtime
  fn `-reg-error-projector` which itself does the late-bind hop.
- **Spec ref:** Spec 011 §Server error projection.

#### 12. `re-frame.core/with-managed-request-stubs`

- **Source:** `implementation/core/src/re_frame/core.cljc:1341`
- **Purpose:** Test-time helper — install per-call HTTP stubs around a
  body. Spec 014 §Testing.
- **Classification:** **PASS**.
- **Rationale:** Expands to `(if-let [f# (late-bind/get-fn :http/with-managed-request-stubs*)] (f# ~stubs (fn [] ~@body)) (throw ...))`. Just splices `stubs` and `body` into a quasiquote — no `&env`, no `requiring-resolve`. Note: this is the macro the user calls; the same-named macro in `re-frame.http-managed/with-managed-request-stubs` (see #20) is the older direct form, retained for backwards compat.
- **Spec ref:** API.md row 233.

#### 13. `re-frame.http-managed/with-managed-request-stubs`

- **Source:** `implementation/http/src/re_frame/http_managed.cljc:1619`
- **Purpose:** Same as #12 but dispatches directly to the in-namespace
  `with-managed-request-stubs*` fn (no late-bind hop — this macro
  lives in the http artefact, which can statically reach its own fn).
- **Classification:** **PASS**. Trivial body splice.
- **Spec ref:** Spec 014 §Testing.

### `reg-machine`

#### 14. `re-frame.core/reg-machine`

- **Source:** `implementation/core/src/re_frame/core.cljc:634`
- **Purpose:** Register a machine spec. Walks the literal spec at
  expansion time and stamps per-element source coords (rf2-8bp3).
- **Classification:** **PASS**.
- **Rationale:** This is the heaviest macro in the surface — it calls
  `source-coords/walk-machine-spec` at expansion time to build a
  `{<path-tuple> {:ns :line :file :column}, ...}` index, then splices
  that as a literal map into the expansion. **The walker
  (`source-coords/walk-machine-spec`, sibling file
  `source_coords.cljc`) is itself a pure-data tree-walker over the
  spec form** — it uses only `map?`, `get`, `reduce-kv`, `doseq`,
  `assoc!`, `persistent!`, `seq?`, `first`, `rest`, `meta`, and
  symbol/keyword predicates. Every one of those is a SCI primitive.
  The walker emits a plain map literal that the macro splices into the
  expansion; there's no `eval`, no `requiring-resolve`, no
  `cljs.analyzer` dependency.
- **Caveat to validate in the spike:** The expansion contains a
  reference to `interop/debug-enabled?` (the per-element index is
  wrapped in `(if interop/debug-enabled? (assoc ...) machine)`). That
  symbol resolves to `re-frame.interop/debug-enabled?`, which on CLJS
  is `(def ^boolean debug-enabled? ^boolean goog/DEBUG)`. SCI does
  expose `goog/DEBUG` (Scittle pre-defines `goog`), so the gate
  resolves at runtime. This is the same `goog.DEBUG`-aware pattern
  Scittle's re-frame v1 plugin already handles for re-frame v1's
  `trace-enabled?`. Worth a confirm in the spike.
- **Spec ref:** API.md row 34 / Spec 005 §Source-coord stamping (rf2-8bp3).

### Views

#### 15. `re-frame.core/reg-view`

- **Source:** `implementation/core/src/re_frame/core.cljc:353`
- **Purpose:** Defn-shape view registration with auto-id derivation,
  auto-inject of `dispatch` / `subscribe`, optional reagent-slim form
  classification. Spec 004 §reg-view.
- **Classification:** **NEEDS-CHANGE** (highest-priority finding).
- **Rationale:** The macro body invokes
  `((requiring-resolve 're-frame.views-macros/expand-reg-view) ...)`
  at **expansion time**. Under SCI, `requiring-resolve` resolves
  against SCI's namespace map, not the JVM classpath. Unless
  `re-frame.views-macros/expand-reg-view` is explicitly exposed via
  the Scittle plugin's `sci/copy-ns` map, `requiring-resolve` returns
  `nil` and the macro throws `NullPointerException` or
  `IllegalArgumentException: Can't call nil`.
- **Why the indirection exists:** Per the comment on line 380–388,
  `re-frame.core/reg-view` and the legacy
  `re-frame.views-macros/reg-view` (see #17) share an expander so both
  emit identical bytecode without circular load. The `requiring-resolve`
  hop dodges the static `:require [re-frame.views-macros]` that would
  otherwise force core to load a CLJ file at CLJS compile time.
- **Fix path for v1.1 (do not implement here):** Two options:
  1. **Plugin-side fix:** the Scittle `re-frame2` plugin exposes
     `re-frame.views-macros/expand-reg-view` to SCI via
     `sci/copy-ns`. Zero code change in re-frame2; one line in the
     plugin's `init.cljs`.
  2. **Source-side fix:** in `re-frame.core/reg-view`, replace the
     `requiring-resolve` with a static `(re-frame.views-macros/expand-reg-view ...)`
     call and add `[re-frame.views-macros]` to the macro file's
     `:require`. The circular-load concern is JVM-CLJS-bootstrap-specific
     and not relevant to SCI.
- **Prefer option 1.** It keeps re-frame2's source code unchanged and
  follows the existing Scittle pattern (plugin-side namespace
  exposure). Option 2 may make sense if we want SCI compatibility to
  be load-bearing for non-Scittle SCI hosts too (e.g. babashka
  examples), but that's a v1.1+ call.
- **Spec ref:** API.md row 32 / Spec 004 §reg-view.

#### 16. `re-frame.views-macros/with-frame`

- **Source:** `implementation/core/src/re_frame/views_macros.clj:27`
- **Purpose:** Bind `*current-frame*` around `body`. Spec 002 §with-frame.
- **Classification:** **PASS**.
- **Rationale:** Pure quasiquote. Branches on `(vector? bindings)` at
  expansion time, splices `let` + `binding` forms. No expansion-time
  IO. SCI handles `binding` with the dynamic var
  `re-frame.frame/*current-frame*` exactly as Clojure does.
- **Spec ref:** API.md row 74 / Spec 002 §with-frame.

#### 17. `re-frame.views-macros/bound-fn`

- **Source:** `implementation/core/src/re_frame/views_macros.clj:49`
- **Purpose:** Return a fn that captures `*current-frame*` at
  definition time and re-binds it at call time. Spec 002 §bound-fn.
- **Classification:** **PASS**.
- **Rationale:** Pure quasiquote with one gensym. The hygiene is
  fine — `(gensym "frame__")` works under SCI (SCI implements
  `gensym`). Body splices `let` + `fn` + `binding`. No expansion-time
  IO.
- **Spec ref:** API.md row 75 / Spec 002 §bound-fn.

#### 18. `re-frame.views-macros/reg-view`

- **Source:** `implementation/core/src/re_frame/views_macros.clj:188`
- **Purpose:** Legacy import path for `reg-view` (per the docstring on
  line 206-208, new code should `:require-macros [re-frame.core ...]`;
  this surface exists so existing examples that
  `:require-macros [re-frame.views-macros :refer [reg-view]]` keep
  compiling).
- **Classification:** **PASS** — but contingent on `expand-reg-view`
  being SCI-reachable.
- **Rationale:** The macro body is `(expand-reg-view (meta &form) ...)`
  — a **static** call to a same-file fn, no `requiring-resolve`. Under
  SCI it resolves through the normal namespace map.
  `expand-reg-view`'s **own** body **does** call `requiring-resolve`
  (at line 114, looking up
  `reagent2.impl.component/classify-form-body`) — but that call is
  wrapped in a `try/catch` and returns `nil` if the resolution fails,
  in which case the form-tag drops out and the rest of the expansion
  proceeds unchanged. So under SCI, `requiring-resolve` returns `nil`,
  the `when-let` skips, the wrapper fn is emitted without the
  reagent-slim performance hint, and the view registers normally.
- **Caveat:** Confirm SCI's `requiring-resolve` doesn't *throw* on a
  not-found symbol (it should return `nil`, matching JVM behaviour) —
  if it throws, the `try` catches and the result is still `nil`.
  Either way the code falls back cleanly.
- **Cross-ref to #15:** The fix to #15 (option 1: expose
  `expand-reg-view` via `sci/copy-ns`) automatically also fixes any
  future `requiring-resolve` indirection — and exposing
  `expand-reg-view` makes the legacy import path Just Work too. **No
  separate fix needed for #18.**
- **Spec ref:** API.md row 32 (same as #15) / Spec 004 §reg-view.

### Performance

#### 19. `re-frame.performance/mark-and-measure`

- **Source:** `implementation/core/src/re_frame/performance.cljc:104`
- **Purpose:** Compile-time-gated browser Performance API
  instrumentation. Spec 009 §Performance instrumentation.
- **Classification:** **UNKNOWN** (almost certainly PASS).
- **Rationale:** This is the **one macro that branches on `&env`** —
  `(if (:ns &env) <cljs-expansion> <jvm-expansion>)` at line 127. The
  CLJS expansion emits `js/performance.mark` / `.measure` calls
  inside a `(if re-frame.performance/enabled? ...)` gate. The JVM
  expansion is a pure-passthrough `(do ~@body)`. Under SCI,
  `(:ns &env)` returns `nil` for SCI's macro environment — SCI macros
  don't get the same `&env` shape as the CLJS analyzer — so the macro
  would currently take the JVM-passthrough branch.
- **Net effect:** Under SCI, **the performance instrumentation
  silently no-ops** — `(perf/mark-and-measure :event id body)` expands
  to `(do body)`. This is **probably acceptable for the playground**
  (tutorial cells don't need performance instrumentation) but flag
  for the spike: if we want timing in the playground, the macro needs
  a Scittle-aware branch.
- **Open question for Mike:** Is silent-no-op acceptable for v1.1
  playground? Or do we want a `(if (or (:ns &env) <some-scittle-marker>) <cljs-expansion> ...)` shape? My recommendation is **silent-no-op is fine** — the playground is for learning, not benchmarking, and `js/performance` API timing of a 50-line tutorial cell isn't meaningful. But it's a Mike call.
- **Independent issue if we did want it on:** The CLJS expansion does
  `(.mark js/performance ...)` which SCI handles natively (Scittle
  exposes `js/performance`), and `(re-frame.performance/build-name ...)`,
  which is a regular CLJS fn callable from SCI as long as the
  namespace is exposed via `sci/copy-ns`. So the **macro body itself**
  would work if it picked the CLJS branch under SCI. The issue is
  purely the `(:ns &env)` branch selection.
- **Spec ref:** Spec 009 §Performance instrumentation.

### Test support

#### 20. `re-frame.test-support/run-test-sync`

- **Source:** `implementation/core/src/re_frame/test_support.cljc:413`
- **Purpose:** v1 compatibility shim — bracket `body` in a registrar
  snapshot/restore. Spec 008 §`re-frame-test` library compatibility.
- **Classification:** **PASS**.
- **Rationale:** One-line body: `(with-fresh-registrar (fn [] ~@body))`.
  No expansion-time IO. SCI handles this trivially.
- **Caveat:** Playground use is probably nil (tests aren't typically
  run inside a tutorial cell), but the macro itself works.
- **Spec ref:** Spec 008 §`re-frame-test` library compatibility.

### Adapter macros

#### 21. `reagent2.ratom/reaction`

- **Source:** `implementation/adapters/reagent-slim/src/reagent2/ratom.clj:18`
- **Purpose:** Sugar for `(make-reaction (fn [] body))`. The reactive
  value abstraction over a thunk. IMPL-SPEC §2.3.
- **Classification:** **UNKNOWN** (almost certainly PASS).
- **Rationale:** The macro body is the simplest in the surface:
  `` `(reagent2.ratom/make-reaction (fn [] ~@body)) ``. SCI parses
  this fine; the open question is whether
  `reagent2.ratom/make-reaction` will be exposed to SCI via the
  Scittle Reagent v2 plugin. Per the parent finding (rf2-mjxg) and
  the **rf2-pfez** follow-up (Reagent v2 SCI export decision), this
  is a separate workstream from the playground itself. Once the
  Reagent v2 SCI export lands, this macro works. Until then, it
  expands fine but the runtime call fails with "Could not resolve
  symbol reagent2.ratom/make-reaction".
- **Cross-ref:** Tracked under **rf2-pfez** (Reagent v2 SCI namespace
  export — upstream vs vendor). No additional work on this bead.
- **Spec ref:** N/A — reagent-slim is an adapter library, not in the
  re-frame.core surface.

---

## Cross-referencing the spec

The spec's macro inventory is in `spec/API.md`:

- **Registration row block** (rows 24-37): `reg-event-db`, `reg-event-fx`, `reg-event-ctx`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-view`, `reg-machine`, `reg-flow`, `reg-route`. Covered by macros #1–#11 and #14, #15 above.
- **View ergonomics rows** (rows 74-75): `with-frame`, `bound-fn`. Covered by #16, #17.
- **Testing rows** (row 233): `with-managed-request-stubs`. Covered by #12, #13.
- **Machines section** (rows 570-571): `reg-machine`, `reg-machine*`. The fn-form `reg-machine*` is not a macro and isn't audited here; it's the runtime escape hatch when SCI compatibility (or any other macro-bypass need) requires it.

Spec docs **do not currently mention SCI / Scittle compatibility** as
a constraint on macro design. Per Mike's standing directive (keep
spec docs in sync with design decisions, per
`feedback_keep_migration_doc_in_sync.md`), if the playground proceeds
post-1.0 and re-frame2 chooses to call SCI compatibility a supported
runtime, that's a spec-level commitment worth recording — but it's
**not on this bead**. This bead is investigation.

---

## What changes if we pursue the Scittle playground

If rf2-mjxg's recommended path is adopted post-1.0, the workstream
breaks into three pieces:

1. **Scittle plugin authoring (rf2-mjxg's recommended next step).**
   - Build `scittle.re-frame2.js` analogously to the existing
     `scittle.re-frame.js` plugin. The plugin's `sci/init` config
     exposes the re-frame2 namespaces via `sci/copy-ns`.
   - **Include `re-frame.views-macros/expand-reg-view`** in the
     copy-ns map. This single inclusion is what fixes macro #15
     (`re-frame.core/reg-view`) and macro #18 (the legacy
     `re-frame.views-macros/reg-view`) without touching re-frame2's
     source.
   - **Include `re-frame.performance/build-name`** in the copy-ns map
     in case Mike decides he does want performance timing in
     playground cells (re-enables macro #19's CLJS branch via a
     Scittle plugin tweak).
   - This is **plugin-side work, not re-frame2 source work**.

2. **Reagent v2 SCI export (rf2-pfez).**
   - Decide upstream-vs-vendor for the Reagent v2 namespace export.
     Once Reagent v2 namespaces (including `reagent2.ratom`) are
     SCI-exposed, macro #21 (`reaction`) works.
   - Independent of this bead.

3. **Optional: source-level fixes if we want non-Scittle SCI hosts
   (babashka, jet, etc.) to work without a plugin.** This is the
   "source-side option 2" route for `re-frame.core/reg-view` — replace
   the `requiring-resolve` indirection with a static call. Not on the
   critical path; only matters if we want SCI compatibility as a
   first-class re-frame2 runtime (rather than a Scittle-plugin-only
   capability).

---

## Open questions for Mike

1. **Performance instrumentation in playground cells: silent no-op or
   live?** (See #19.) My recommendation: silent no-op for v1
   playground, revisit if user demand emerges. The macro currently
   gates on `(:ns &env)` which SCI doesn't populate; the JVM
   passthrough branch is a clean degradation.

2. **Source-level fix vs plugin-side fix for `reg-view`'s
   `requiring-resolve`.** (See #15.) Plugin-side keeps re-frame2's
   source untouched; source-side makes re-frame2 SCI-compatible
   without a plugin (matters for non-Scittle SCI hosts). My
   recommendation: plugin-side for v1.1 playground, leave source-side
   as a "we may revisit if other SCI hosts emerge" notion.

3. **Is SCI compatibility a spec-level commitment for v1.1+ or a
   one-off playground capability?** If the former, the
   non-`requiring-resolve`-using path through the views surface is
   worth promoting in the spec (and the source-level fix becomes
   load-bearing). If the latter, the plugin-side fix is sufficient.

4. **`reg-machine`'s `interop/debug-enabled?` gate (see #14
   "Caveat").** The macro emits a runtime `(if interop/debug-enabled?
   ...)` gate around the per-element source-coord index. SCI exposes
   `goog/DEBUG` and the def resolves at runtime, but the playground
   spike should verify the gate evaluates the way we expect (and that
   the per-element coords are present when debug=true, omitted when
   debug=false). Probably no action — just a confirm-in-spike note.

---

## Summary table (for the PR body)

| #  | Macro                                                | Classification | Notes |
|----|------------------------------------------------------|----------------|-------|
| 1  | `re-frame.core/reg-event-db`                         | PASS           | Source-coord stamping. |
| 2  | `re-frame.core/reg-event-fx`                         | PASS           | Same shape as #1. |
| 3  | `re-frame.core/reg-event-ctx`                        | PASS           | Same shape as #1. |
| 4  | `re-frame.core/reg-sub`                              | PASS           | Same shape as #1. |
| 5  | `re-frame.core/reg-fx`                               | PASS           | Same shape as #1. |
| 6  | `re-frame.core/reg-cofx`                             | PASS           | Same shape as #1. |
| 7  | `re-frame.core/reg-frame`                            | PASS           | Same shape as #1. |
| 8  | `re-frame.core/reg-flow`                             | PASS           | Late-bound; same shape. |
| 9  | `re-frame.core/reg-route`                            | PASS           | Late-bound; same shape. |
| 10 | `re-frame.core/reg-app-schema`                       | PASS           | Late-bound; same shape. |
| 11 | `re-frame.core/reg-error-projector`                  | PASS           | Late-bound; same shape. |
| 12 | `re-frame.core/with-managed-request-stubs`           | PASS           | Late-bound; same shape. |
| 13 | `re-frame.http-managed/with-managed-request-stubs`   | PASS           | In-namespace dispatch. |
| 14 | `re-frame.core/reg-machine`                          | PASS           | Walks literal spec; pure data ops. |
| 15 | `re-frame.core/reg-view`                             | **NEEDS-CHANGE** | `requiring-resolve` at expansion time. Plugin-side fix recommended. |
| 16 | `re-frame.views-macros/with-frame`                   | PASS           | Pure quasiquote. |
| 17 | `re-frame.views-macros/bound-fn`                     | PASS           | Pure quasiquote + gensym. |
| 18 | `re-frame.views-macros/reg-view`                     | PASS           | Static call to in-namespace expander. |
| 19 | `re-frame.performance/mark-and-measure`              | **UNKNOWN**    | Silent no-op under SCI (`(:ns &env)` is nil). Probably fine. |
| 20 | `re-frame.test-support/run-test-sync`                | PASS           | Trivial body wrapper. |
| 21 | `reagent2.ratom/reaction`                            | **UNKNOWN**    | Depends on Reagent v2 SCI namespace export (rf2-pfez). |

**Final counts: 17 PASS, 1 NEEDS-CHANGE (#15, fixable plugin-side
without a re-frame2 source change), 2 UNKNOWN (#19 — silent-no-op
under SCI, action-needed only if Mike wants playground perf timing;
#21 — depends on Reagent v2 SCI export, tracked under rf2-pfez).**

If the Scittle plugin author exposes `re-frame.views-macros/expand-reg-view`
via `sci/copy-ns` (a one-line plugin change), **the entire re-frame2
public macro surface — 20 of the 21 macros, with #21 gated on the
separate Reagent v2 export — works under SCI without touching
re-frame2's source code.** That's the headline finding of this audit.
