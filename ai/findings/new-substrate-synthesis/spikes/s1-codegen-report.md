# Spike report — S-1 codegen + S-4 dual host

**Worker:** S-1/S-4 spike worker · **Date:** 2026-07-11 23:31:52 AUSEST
**Branch (the artifact):** `spike/ui-s1-codegen` (pushed to origin; no PR — throwaway, test-owned)
**Spike code:** `spike/` at the branch root · committed evidence in `spike/results/`
**Design refs:** 02-programming-model §1–§3, 06-ssr-islands §1, 08-delivery §1+§5 (B-lite: this worker carried S-4 as phase 2)

## Verdicts

| Spike | Exit criterion | Verdict |
|---|---|---|
| S-1 codegen | median+p95 render CPU within 10% of hand-written jsx-runtime CLJS; emitted :advanced JS inspected | **PASS** — median ratios 0.92–1.00, p95 ratios 0.69–1.04 across 4 components; compiled is at-or-below hand-written cost everywhere |
| S-4 dual host | structural agreement CLJS emitter (renderToStaticMarkup) vs JVM tree emitter on all fixtures | **PASS** — 11/11 fixtures structurally identical as normalized semantic nodes; zero divergences to document |

## Setup

- Worktree `re-frame2-worktrees/spike-ui-s1-codegen` off origin/main (guard passed; WORKTREE_ROOT reported). All product trees untouched; everything under fresh `spike/` namespaces.
- Toolchain: shadow-cljs 3.4.10 (`:optimizations :advanced`, `:infer-externs :auto`, `:target :node-script`), react + react-dom 19.2.0 (npm `:js-provider :require`, run under `NODE_ENV=production`), node v24.13.0, Temurin JDK 21, Clojure 1.12. Hardware: Intel Core Ultra 9 275HX (Windows 11 laptop — µs-scale p95 carries thermal/GC noise; see methodology).
- Pipeline built (all in `spike/src/spike/ui/`): `rules.cljc` (the ONE conversion/escaping rule table), `ast.cljc` (template → normalized AST, static/dynamic split recorded per node and per prop entry), `emit_cljs.clj` (AST → direct `jsx`/`jsxs` calls), `emit_jvm.clj` (same AST → serializable Clojure render tree), `compiler.cljc` (`defview` macro, per-host emitter dispatch on `&env`).
- Grammar covered: literal DOM heads + `.class`/`#id` sugar, literal `:style` maps (keyword values stringify; React px-rule replicated on JVM), `:class` string/vector/flag-map, branches (`if`/`if-not`/`when`/`when-not`/`cond`) and `let` normalized into the AST, one keyed `for` (missing `:key` = compile failure), internal view call with a props map (deterministic quoted slot names; `:key` extracted to React's key slot), event vectors with `:rf.ui/value`/`:rf.ui/checked` placeholder splicing, void-element and keyword-in-child-position rejections.
- Test components: `static-tree` (fully static), `counter` (props + branches + capture-free and capturing handlers + keyed identical rows), `todo-list`/`todo-row` (20-row keyed `for` over an internal view call), `status-panel` (cond multi-branch). 11 fixtures = 3–4 prop variations per parameterized component incl. escaping torture strings (`<b>&"bold"</b> 'q'`, `boom & <bust> "quoted"`).

## S-1 — benchmark (median/p95 per render, µs)

Method: `renderToStaticMarkup` per render; N = 50,000 renders/round (1,000 samples × batch 50), 6 alternating-order rounds per impl, warmup 3,000; median = best-round median, p95 = min p95 across rounds (noise-only-inflates estimator, applied symmetrically). Hand baseline = same components hand-written against `react/jsx-runtime` with inline `#js` literals, no manual hoisting (what Babel emits without the constant-elements plugin). Full data: `spike/results/bench.json`.

| Component | Compiled med | Hand med | **med ratio** | Compiled p95 | Hand p95 | **p95 ratio** |
|---|---|---|---|---|---|---|
| static-tree | 3.28 | 3.57 | **0.92** | 5.16 | 4.94 | 1.04 |
| counter (n=42) | 4.38 | 4.47 | **0.98** | 6.62 | 6.63 | 1.00 |
| todo-list (20 rows) | 22.66 | 22.57 | **1.00** | 33.52 | 35.18 | 0.95 |
| status-panel (error) | 2.67 | 2.78 | **0.96** | 3.58 | 5.19 | 0.69 |

Everything within the 10% bar; compiled is faster on 3 of 4 medians and ties the 20-row list. Byte-equality cross-check: compiled HTML == hand-written HTML **exactly** (string =) on all 11 fixtures — the emitter's conversion is not just structurally right, it is character-identical to what a careful human writes.

## Emitted-JS observations (`spike/results/emitted-js-excerpts.js`)

- **Hoisting works.** `static-tree` compiles to ONE module constant; its render fn is `return static_tree$el$0`. Static branch arms hoist too (`counter$el$04/05/06` for the Locked/negative/non-negative arms; `todo_row$el$0` for the HIGH flag span).
- **Prebuilt static props objects work.** The keyed identical-row case emits `var counter$props$03 = {className:"star", children:"*"}` once at module level and `shim.jsx("span", counter$props$03, i)` per row — props object shared across rows AND renders (elements/props are immutable to React; legal). Fully-static `:style` objects hoist the same way.
- **Compile-time prop conversion is total.** Props objects are plain JS object literals with quoted, pre-converted keys (`className`, `"data-priority"`, `"aria-hidden"`, `tabIndex`, camelized style keys) — zero runtime name conversion, `#js`-equivalent output, safe under :advanced.
- **Props ABI lowers to direct reads.** `{:keys [n step locked?]}` compiles to `props.n`, `props.step`, `props["locked?"]` — no CLJS map at entry, exactly the 02 §1 claim.
- **Placeholders splice at compile time.** `[:counter/set :rf.ui/value]` becomes a hoisted module-level callback reading `evt.target.value` directly; capture-free vectors dedupe to one callback; capturing vectors (`[:counter/inc step]`) become per-render closures as specified.
- **THE landmine (the one real codegen finding):** binding the imported jsx fn as a CLJS var value — `(def jsx jsxrt/jsx)` — forces IFn-protocol dispatch at EVERY element call site under :advanced (`f.cljs$core$IFn$_invoke$arity$2 ? ... : f.call(null, ...)`). Measured cost ≈5–8% on the 20-row list and it swallowed the hoisting wins elsewhere. Fix: fixed-arity defn wrappers (`(defn jsx2 [t p] (jsxrt/jsx t p))`) compile to direct static calls that Closure inlines into direct `shim.jsx(...)` — after which compiled output is call-for-call identical to hand-written. Stage 1's emitter must pin this pattern (or emit the interop call form directly).
- Minor: dynamic DOM attr values route through the single conversion fn `rules/attr-val` (keyword?/symbol? check, ~30ns/site) even where the expr is provably non-named (`(str n)`, a boolean). A production emitter can elide the wrapper on provable types (analyzer tag or literal-shape inference); cost today is already inside the 10% bar.

## S-4 — dual-host structural parity

- JVM emitter produces plain data from the SAME AST: `{:tag "li" :attrs {"class" "todo-item done" "data-priority" "high" "aria-hidden" false} :events {:on-change [:todo/toggle 1 :rf.ui/checked]} :children [...]}` — **event vectors retained as data**, placeholders still keywords, locals evaluated at render (evidence: `spike/results/tree-samples.edn`).
- HTML serialization shares the rule table: React's px rule (`padding 16` → `16px`, `opacity 0.5` → `0.5`, 0 stays `0`), boolean-attr rule (`checked=""` / omitted; but `aria-hidden="false"` stringifies — matching React), hyphen-collapse attr names (`tab-index` → `tabindex`), void elements, full 5-char escaping (`& < > " '`) in text and attrs; no handler attributes ever emitted into HTML (06 §4).
- Comparison method: both HTML outputs (React `renderToStaticMarkup` vs JVM serializer) parsed into normalized semantic nodes — tag, decoded attrs (entities), `style` parsed to a prop→value map, class as exact string, merged text runs, child order, void/self-closing normalized — then structurally diffed. **11/11 fixtures match**; the harness (`spike/jvm/spike/jvm_main.clj`) prints the first divergent path with both HTML strings when they don't, and exits nonzero.
- Fixtures deliberately exercise the risky rule-table rows: keyed order (20 rows), escaping in text + attr values, boolean attrs true/false, aria-boolean stringification, dynamic keyword attr values (`:data-priority :high`), empty list, all four cond branches, `<hr>` void, input value round-trip.

## Surprises / risks for Stage 1

1. **The var-bound-import dispatch tax** (above) is the kind of silent 5–10% regression that would erode G-1/G-2 without ever failing a test. Recommend: a Stage-1 emitted-JS golden test that greps the advanced output of a fixture view for `.call(null` / `cljs$core$IFn` on the jsx path — cheap and pins the pattern forever.
2. **p95 at µs scale is environment-dominated.** On a laptop, single-run p95 ratios wobbled 0.63–1.71 run-to-run with medians stable at ±2%. The G-1 gate should either use the min-across-rounds estimator (used here) or batch to ≥1ms samples; otherwise the gate will flake exactly like the CI-under-load gates already do.
3. **jsxs/jsx selection and children-in-props shape constrain prebuilt props.** Because jsx-runtime puts children inside the props object, a props object is only reusable when props AND children are static (dynamic key is fine — key rides the 3rd arg). The Object.assign spread alternative for static-props/dynamic-children is a pessimization vs an inline literal; the emitter should keep inline object literals for that case (this spike does).
4. **The rule table really is one table.** Every parity risk that surfaced during development (React's px rule, `aria-*` boolean stringification vs boolean-attr omission, data-* verbatim casing, `&#x27;` escaping) resolved by adding a row consumed by both emitters — none required emitter-specific logic. Good evidence for the 06 §1 architecture; the table rows above should seed the normative table.
5. **Byte-equality with hand-written markup is achievable and worth keeping** as a dev-time invariant (compiled vs a hand-lowered reference for a fixture corpus): it caught ordering/conversion bugs long before the structural comparator would have.
6. Scope honesty: `sub`/lease sites, memoization/`rf=`, dynamic props maps (`ui/spread`), fragments-with-keys, custom elements, and dynamic handler expressions were out of spike scope (S-2/S-3/S-5 or Stage 1+ surface). Nothing in what was built appears to make any of them harder; the AST has clean seams for all of them (prop entries carry `:static?` + context, handlers are entries, control forms are nodes).

## Reproduce

```
cd spike
npx shadow-cljs release spike
NODE_ENV=production node out/spike.js parity   # fixture dump + hand byte-equality
NODE_ENV=production node out/spike.js bench    # benchmark -> out/bench.json
clojure -M:parity                              # JVM render + structural compare (exit 0 = parity)
```
