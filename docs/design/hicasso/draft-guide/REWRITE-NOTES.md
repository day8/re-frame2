# Guide rewrite notes — the six-stage audit trail

# Stage-A writer flags — the review worklist

Accumulated from all eleven writers' reports. Reviewers: resolve what your stage owns; leave the rest; record resolutions inline here (append a `RESOLVED (stage X):` line under the item).

## Cross-chapter conflicts (Stage C owns)

1. **Direct-call refusal id**: ch01 used substrate id `:rf.error/view-called-directly`; ch02 found no attested Hicasso id and described the refusal without one. Pick ONE treatment for both pages.
   - RESOLVED (stage C): no id on either page. `:rf.error/view-called-directly` is **Freehand's** (attested only in `implementation/freehand/src/re_frame/freehand.cljc`), and Hicasso's direct call does not route through that substrate — the prototype's minted component falls into the frame-context path, with no dedicated refusal id of its own. ch01's row now describes the refusal by mechanism (fires at the call, names the view), matching ch02; ch14's "refuses the same way" clause tightened to "refuses at the call itself, naming the view". While there, ch01's neighbouring read-escape row was corrected from Freehand's `:rf.error/view-read-outside-render` to the Hicasso-attested `:rf.error/hicasso-sub-outside-render` (ch02/14/15's spelling). Direct-call refusal added to the catalogue gaps (Stage C output, gap 1).
2. **`ht/shadow!` vs the ch14 kit surface**: ch19 minted `ht/shadow!` with `{:reference :candidate :seed :script}`; ch14 minted the kit facade (`ht/tree`, `ht/mount!`, handle-first calls, `ht/canonical-dom`). Reconcile into one coherent `re-frame.hicasso.test` surface across both chapters.
   - RESOLVED (stage C): one kit. `ht/shadow!` stays in `re-frame.hicasso.test` beside the mounted facade; ch19's `:seed` respelled **`:initial-events`** — the one seeding vocabulary `h/mount!` and `ht/mount!` already use; ch14 gained a one-sentence roster entry naming `ht/shadow!` with ch19 as owner page. Handle-first calls untouched: `ht/shadow!` is a one-shot config-map entry (no handle exists before it runs), which does not conflict with the mounted facade's handle-first style. No other spelling conflicts found between the two chapters.
3. **Keys taught twice**: ch02 carries a full keys section; ch06 is the mapped owner. Substance agrees. Stage D/E trims ch02 to a pointer.
   - RESOLVED (stage D): ch06 owns the keys law. ch02's `Keys go in the props map` trimmed to its own share — the props-map spelling (`{:key id :id id}`), the metadata-not-read note (the Reagent `??? info` folded into prose), the missing-key warning name — closing with an explicit hand-off to ch06 for key *quality* (stable identity, never index, never entity). ch02's entity-key troubleshooting row removed (ch06 carries it verbatim); the missing-key row stays because its fix is the spelling ch02 teaches, now with a ch06 pointer. ch06 unchanged — verified it already carries everything ch02 dropped (double-warning dedupe rationale, entity-key coercion/remount, allowed key types, Reagent delta).
4. **Working loop listed in ch10 and ch18**: both wrote it (ch18 numbered as the performance method; ch10 in rung terms). Stage D/E picks one owner and makes the other a pointer.
   - RESOLVED (stage D): ch18 owns the numbered method (untouched). ch10's `## The working loop` (7 near-duplicate steps) replaced by `## Every crossing runs the loop` — a one-paragraph pointer at Performance plus the rung mechanics that are ch10's own: which attribution verdict enters which rung, and the fence follow-through (re-run the contracts you can no longer see). The Xray-honesty paragraph (names/times the native boundary, `n/use-sub` reads, honest `opaque`) stays — it is fence-specific, not the loop. The escape-benefit rule remains stated on both pages deliberately: it is a brief-listed core fact each page uses (ch10's rung-3 worked example clears it; ch18 owns its definition section), not the duplicated loop.
5. **`:server` policy spelling**: chapters 09, 10, 17 all committed to `{:server :render|:client-only}` + `:fallback` only-under-Client-only. Consistent — verify ch06's virtualizer host (omission = default) matches and no page still implies three-value `:ssr`.
   - RESOLVED (stage C): verified across all 22 files. `:server` two-policy spelling uniform in ch09/10/17/19; `:fallback` taught only under Client-only, with Render+`:fallback` named as a refusal (`:rf.error/hicasso-host-bad-ssr-policy`, attested) in ch09/ch17; ch06's virtualizer host omits the option and states the Client-only default in prose; zero `:ssr` option spellings anywhere (the prototype's three-value `:ssr {:fallback …}` never leaked in). Note the `:server` key itself is a respell of the prototype's `:ssr` — added to the minted register (Stage C output, row 4).

## Premise calls to verify once (Stage C)

6. `h/event` used everywhere per the brief; spec §4 + ergonomics lane still spell `h/handler`. Correct under the brief; confirm zero stray `h/handler`/`h/fn` in any chapter.
   - RESOLVED (stage C): grep clean over all 22 files — zero `h/handler`, `h/fn`, `hfn`. (ch09's `:handler` is the `defhost` callback-contract VALUE from the prototype's `:callbacks` roster `:event`/`:handler`/`:render` — a different word; stands.)
7. Submit prevention uniform (ch03 teaches the bare-submit-navigates consequence loudly). Confirm no other chapter implies auto-prevent.
   - RESOLVED (stage C): ch05 line 231 was the one violation (bare intent at `:on-submit` + an "auto-prevents" comment — Stage B's item 33); now `[::h/prevent [:editor/submit]]` with a no-auto-prevent comment. All ch03 sites already correct; no other chapter implies auto-prevent.
8. Prefetch shipped (`:prefetch :intent`) — the draft-era decline id `:rf.error/hicasso-route-link-prefetch-declined` retired. Confirm no page still cites the decline.
   - RESOLVED (stage C): grep clean — no page cites the decline id or a declining link. (The id remains attested in the v0 prototype + spec/009 as the draft-era decline; the *shipped* `:prefetch` wrong-value refusal ch07 teaches has NO attested id — catalogue gap 12, and the retired decline id must not be reused for it.)
9. Direct-call semantics: refusal (not inlining) everywhere; plain `defn` inlines. Confirm ch02's Advanced doesn't contradict.
   - RESOLVED (stage C): consistent everywhere. ch02's Advanced (the collector) doesn't touch direct calls; ch02's boundary section states a `defview` never degrades into an inline function; ch01/ch14 now aligned per item 1.
10. Browser-neutral controlled-input law (Phase-1-complete premise). Deliberate; not an oversight.
    - RESOLVED (stage C): ch04 is browser-neutral throughout — the legacy keyCode-229 clause says "some browsers", no Chromium scoping anywhere; matches the premise and the prototype's own gate (isComposing + 229 attested in `controlled.cljs`/`intent.cljs`, spelled browser-neutrally there too).
11. Hooks never in `defview` bodies (spec §5); old draft said "not policed". Confirm all chapters follow spec.
    - RESOLVED (stage C): ch09's Advanced (imperative SDKs) was the one violation — `react/useCallback` inside an `h/defview` body plus "both are fine" prose. Rewritten hook-free: a top-level `defn` attach ref (stable identity with no hook), with the per-instance-closure case explicitly routed to a named native component, matching spec §5 ("Hooks do not belong in the dynamically composed `defview` body") and ch10/ch11's teaching. ch10 (rung-3 fence + islands) and ch11 (valve 3) already conform; ch17's only hook use is inside `n/defcomponent` (legal).

## Minted spellings needing naming-ledger rows (Stage C compiles the list; the sitting rules)

12. `re-frame.hicasso.server` module name + `server/render` option contract; `:identifier-prefix`.
13. Test kit: `ht/tree` (L2 entry), fixture-map shape `{:subs {query-v value}}`, `ht/mount!` handle shape, `ht/rerender!` (vs spec §9 "rerender" / lane "render!"), `ht/canonical-dom`, `ht/shadow!`.
14. Interop: `:slots #{…}`, `h/portal {:target …}`, `h/as-component` (outward bridge).
15. Native: `n/memo`, `n/lazy` (ABI helpers).
16. Overlay options: `:open?` `:on-dismiss` `:anchor` `:placement` `:label` `:light-dismiss?` `:exit-ms`; minted id `:rf.error/hicasso-overlay-anchor-missing`.
17. Motion respellings: `motion/presence`, `::motion/mounting`/`::motion/unmounting`.
18. Forms: `forms/buffered-field` + `:control`/`:value`/`:on-commit`/`:on-cancel`; reset unified on `::h/revision`.
19. Async: demand spelling `:demand true` inside `[:rf/resource …]` sub map + `:keep-previous? true`.
20. Merge helper: recipe taught (plain `merge`, owned-last); no symbol minted anywhere — confirm no page invents one.
21. `::h/navigate` presented as route-link-minted reserved head (not author-written core vocabulary).

## Missing error ids (catalogue gaps — Stage C compiles; do NOT invent on pages)

22. `defview` direct-call refusal (see item 1); the `n/$` refusal family (props misclassification, hiccup child, intent-in-prop, `:children`, slot collision); test-kit refusals (opacity, missing fixture, assert-clean failure); forms-module rows; contenteditable refusal; bare-intent veto at `route-link` `:on-click`; `:prefetch` wrong-value.

## Deliberate omissions (correct under the premise — do not "fix" back)

23. No `h/reg-state`, no `[::h/clear]`, no `h/child-key`, no vector-ref reservation, no parts/theming subsystem, no three-value `:ssr`, no `h/frame` (zero-arity `rf/capture-frame` admitted in-body instead), `subscribe-once` internal, old comparative benchmark figures dropped (budgets only).

## Length

24. ch09 at 383 lines (33 over target) — justified by the writer; Stage D judges.
    - RESOLVED (stage D): judged one job (the foreign-React door seen from every side) with recipe-grade padding, not two jobs — no split, no renumber. 402 → 360 (target ≤360 met) by: compressing the escape's capture-frame recipe to ch03's pointer (the owner — picker-row code block dropped, law and `:rf.error/no-frame-context` kept); compressing the transparent-wrapper warning and provider prose against ch17 (owner of the full SSR story); folding the `No deep conversion` paragraph into the What-crosses table's Prop-values row; moving `The crossing has no memo wrapper` under `## Advanced` (subtlety, not required path); in-place compression of the skeleton-slot recovery (Stage B's restored teaching kept whole — declaration, code, placeholder-not-content close); removing two troubleshooting rows owned elsewhere (`hicasso-intent-needs-the-event` symptom row → ch03 owns it, and the id stays cited in ch09's body; children-missing-from-server-HTML mechanism row → ch17 owns it); and sentence-level tightening (opening rationale, callbacks two-laws merge, portal facts, server-policy section, bridge, SDK Advanced). All taught semantics, names, and error ids preserved — every id previously cited on ch09 still appears on ch09.

## Stage B findings (completeness review)

### (a) Coverage gaps filled (spec §7 walked row by row)

25. **Multiple frames and roots** had mentions only (one sentence each in ch01/ch11/ch13/ch17), no teaching. Added `## More than one root` to ch01: two roots sharing one frame (ensure creates-and-seeds vs joins-without-replay — semantics verified against `docs/core/how-to/boot-and-mount-an-app.md`), two frames as isolated apps (subs never cross), per-root teardown with the frame surviving for other roots; plus one troubleshooting row (joining root's `:initial-events` never run). ch01 now 226 lines.
26. **Code splitting** was untaught (`n/lazy` a one-clause mention in ch10; Suspense hosting one sentence in ch09). New chapter `20-code-splitting.md` (204 lines) + README row: route/module boundary as the default (shadow-cljs `:modules` + `shadow.lazy` + a user-authored load fx + arrival-as-state, route `:on-match` wiring verified against `docs/routing/tutorial.md`), the `n/lazy` bridge + Suspense host (`:slots #{:fallback}`) + `h/error-boundary` retry, declared-outside-render law, HMR/marker conduct, SSR Client-only. Sources: specification §7 row, dispositions HS-22/HS-30, completeness-audit, react-compatibility matrix.
27. **Suspense and Activity conduct** (reader-facing half) was untaught. Covered in ch20 `## While a subtree waits, or hides`: suspended attempts own nothing (commit-owned acquisition), no `sub`-driven promise selection for data (per react-compatibility-notes — external-store updates can swap visible content for the fallback), Activity hosted via `defhost` (no DSL), hide releases committed ownership and demand with app-db untouched, reveal reacquires before visible paint, Xray's hidden-retained label.
28. **Accessibility** had no owner (scattered aria examples in ch07/ch08/ch12/ch11). New chapter `21-accessibility.md` (168 lines) + README row: semantic elements first, names as ordinary attributes with per-instance id discipline, ARIA state derived from the same reads, a focus-owner inventory table (pointers, no re-teaching), L2 structural assertions with a sabotage twin, browser-tier focus walks + axe floor (per completeness-audit "structural accessibility assertions plus browser focus and axe checks").

Rows verified covered with no edit needed: validation/async normalization (ch05 gating + ch04 revision re-baseline + ch08 settle-merge), routing, async/mutations, errors, foreign React + native, large collections, imperative SDKs (ch09 Advanced), overlays, motion (ch11 valves 3/5), SSR, i18n/theming, testing, diagnostics, migration.

### (b) Gaps not filled

None — every reader-facing §7 row now has an owning chapter with real teaching.

### (c) Restored draft material (sweep 2)

29. ch06: the missing-key warning at the seq-as-view-children crossing (React marks flattened members validated and never warns; Hicasso's line is the only signal) — from draft 02.
30. ch09: the `[:>]`-under-SSR recovery — a transparent pass-through host with a `:fallback` wrapping the escape puts placeholder markup at the site (draft 05's `skeleton-slot` recipe, respelled to the current `:fallback` option surface). ch09 now 400 lines — see item 24, Stage D.
31. ch13: the page-chrome echo (document-level copy of the theme fact via a client-only fx under a *different* attribute name) and the `::backdrop`-inherits-from-originating-element note — from draft 06. This also makes ch12's "style the backdrop via [Theming]" pointer deliver something.
32. ch18: `rf:*` User-Timing accuracy — compile-time `:closure-defines {re-frame.performance/enabled? true}`, default off; entries delivered-not-retained (observer/timeline see them, a later `getEntriesByType` poll does not); bail-outs emit nothing; StrictMode emits twice — from draft 11, verified against `implementation/core/src/re_frame/performance.cljc`. This also removes ch18's "development builds emit" wording that contradicted ch15's "gated by its own compile-time flag, off by default".

Checked and deliberately NOT restored (item 23 or superseded by the premise): the `:&` grammar, `h/reg-state`/`[::h/clear]`/`h/child-key`, vector-ref reservation, parts/theming layer, three-value `:ssr`, `h/frame`, `subscribe-once`, draft benchmark figures, submit auto-prevent, prefetch decline, position-dependent `h/fn` meaning table (h/event is position-invariant), Chromium-only IME scope note, pre-React-19 ref shape, object-ref aside, collection-values-`clj->js` claim (superseded by identity crossing), reduced-structural-identity deep-dive (compact form survives in ch09/ch19).

### (d) Noticed for other stages (not fixed here)

33. (Stage C — item 7 evidence) ch05 line 231 comment reads "an intent at `:on-submit` auto-prevents", contradicting ch03's no-auto-prevent law. One-comment fix.
    - RESOLVED (stage C): fixed under item 7 — code and comment both (the bare intent would also have navigated, so the `::h/prevent` head was added, not just the comment).
34. (Stage C — items 13/15/22) ch20 leans on the `n/lazy` mint and adds a loader-contract question (thunk-returning-a-promise, React.lazy shape) — fold into the ledger row. ch20/ch21 mint no error ids and no new module names; the `suspense`/`activity` declarations are user-authored `defhost`s, not product spellings.
35. (Stage D — item 24 family) post-B lengths: ch01 226, ch06 304, ch09 400, ch13 245, ch18 247, ch20 204, ch21 168.
36. (Stage C/D) ch15's complaint-catalogue row for `:rf.error/frame-destroyed` points at ch03, which never teaches frame incarnation — move the pointer or give ch03 Advanced one sentence when compiling item 22.
    - RESOLVED (stage C): ch03's "Callbacks carry their frame" section gained one sentence — a captured handle is good for its frame's incarnation; a stale handle's operation refuses with `:rf.error/frame-destroyed` (attested, 7 hits in the prototype tree). ch15's pointer at ch03 now lands.
37. (Stage E) ch20/ch21 are written to the brief's rules (problem open, required path, troubleshooting, when-not, one warning admonition each) — include both in the E-pass roster.

## Stage C output

Verification basis: attested = found by grep in `implementation/hicasso/src/**`, `implementation/freehand/test/re_frame/bench/hicasso/**`, `migration/reagent-to-hicasso/**`, or the shipped core corpora (`docs/core/**`, `docs/routing/**`, `docs/resources/**`, `docs/api/**`); pinned = named in the brief table, naming-ledger, spec §4/§5/§9, or a lane. Post-edit id census across all 22 chapters: 33 distinct `:rf.error/*` / `:rf.warning/*` / `:rf.ssr/*` ids cited; 32 attested; 1 deliberate flagged mint (`:rf.error/hicasso-overlay-anchor-missing`); 2 Freehand-substrate ids removed/corrected on ch01 (`:rf.error/view-called-directly` dropped, `:rf.error/view-read-outside-render` corrected to `:rf.error/hicasso-sub-outside-render`).

### (a) The minted-spellings register (naming-ledger rows to sit)

Every symbol/option/keyword taught that no normative source pins. "Verified none/attested" rows are included so the sitting sees the whole sweep.

1. `h/event` — the callback macro name, all chapters; the brief rules it over spec §4 / ergonomics-lane `h/handler` and prototype `hfn`; ledger row 1 stands open for the sitting — the guide is committed corpus-wide.
2. Artifact coordinates `io.github.day8/re-frame2-hicasso` (+ core `day8/re-frame2` arriving transitively) — ch01. Ledger row 14 covers only the ns name `re-frame.hicasso`.
3. Root-lifecycle contract shape: `h/mount!`/`h/hydrate!` take `(node config view)` with config keys `:frame`, `:initial-events` (mount) and `:frame`, `:identifier-prefix` (hydrate); `h/render!` takes `(handle view)`; idempotent handle — ch01, ch17. Ledger row 13 pins the four verbs only; the prototype `root!` took a bare frame keyword, and `:initial-events` is borrowed from core's frame-root vocabulary.
4. `:server` policy option key on `defhost` / `n/defcomponent`, values `:render` / `:client-only`, with `:fallback` a sibling option legal only under Client-only — ch06 (default by omission), ch09, ch10, ch17, ch19. The prototype spells `:ssr` with a three-shape contract; the brief pins "two policies" but not the key spelling.
5. `re-frame.hicasso.server` module + `server/render` option contract: `:hiccup` `:snapshot` `:payload` `:client-frame-id` `:identifier-prefix` `:app-element-id` `:script-src` `:title`, returning `:document` — ch17. [item 12]
6. Test kit L2: `ht/tree` entry; fixture-map shape `{:subs {query-v value}}` (lineage: the draft guide's `h/render`); tree helpers `ht/find` / `ht/attrs` / `ht/text` / `ht/intents` — ch14, ch21. [item 13]
7. Mounted facade details: `ht/mount!` handle shape (a map; `:container` a real DOM node — matches the prototype root handle); `ht/rerender!` (spec §9 says "rerender", testing lane says `render!` — the sitting settles which); `ht/canonical-dom` (sorted-attribute serializer) — ch14. The facade verbs themselves (mount!/hydrate!/dispatch-and-settle!/settle!/unmount!/assert-clean!) are lane-pinned, not mints. [item 13]
8. `ht/shadow!` + options `{:reference :candidate :initial-events :script}` (Stage C respelled `:seed` to `:initial-events`), result shape `{:status :checkpoints}`, scriptless live-diff mode — ch19, ch14 roster line. No lane names any shadow surface. [item 13]
9. Interop declaration option `:slots #{…}` (ReactNode positions) — ch09; spec §4.3 pins the concept, not the spelling. [item 14]
10. `h/portal` + `{:target …}` — ch09; spec §4.3 pins "a tiny optional portal helper", not the name/option. [item 14]
11. `h/as-component` — the outward bridge — ch09; spec §4.3 pins the bridge, not the name. [item 14]
12. `n/memo`, `n/lazy` ABI helpers + the `n/lazy` loader contract (thunk returning a promise resolving to the component, `React.lazy` shape, component marker kept) — ch10, ch20. Spec §4 pins "native ABI helpers" generically. [items 15 + 34]
13. Overlay module surface: heads `overlay/popover`, `overlay/modal`; options `:open?` `:on-dismiss` `:anchor` (a DOM id) `:placement` (compass words `:bottom-start` …) `:label` `:light-dismiss?` `:exit-ms`; minted id `:rf.error/hicasso-overlay-anchor-missing` — ch12. [item 16]
14. Motion respellings: `motion/presence` head; `::motion/mounting` / `::motion/unmounting` override keys (the prototype attests the engine under `h/presence` with `::h/mounting`/`::h/unmounting`; `:timeout-ms` and the `:rf/phase` prop with values `:mounting`/`:present`/`:unmounting` carry over attested) — ch11. [item 17]
15. Forms: `forms/buffered-field` + `:control` (draft address) `:value` `::h/revision` `:on-commit` `:on-cancel`; the module-owned draft key at the `:control` address — ch05. [item 18]
16. Async: `:demand true` inside the `[:rf/resource …]` sub map — ch08. (`:keep-previous?` is attested core-resources vocabulary — struck from the item-19 mint list.) [item 19 narrowed]
17. Merge helper — verified NONE minted: plain `merge`, owned-last, recipe-only in ch02/ch04; no page invents a symbol. [item 20 closed]
18. `::h/navigate` reserved head — presented correctly as route-link-minted, never author-written; its grammar `{:frame :payload :native? :veto}` is attested verbatim in the prototype (`route_link.cljs`); needs a reserved-vocabulary ledger row because the brief's reserved-data list does not include it — ch07. [item 21]
19. `:prefetch :intent` accepted on `route-link` — ch07. The prototype declines the key for v0 (that decline is the retired id); routing's own `:rf.route/prefetch` event and `:intent` spelling are attested in docs/routing. The link-side acceptance is the designed end state and needs its row.
20. Xray evidence-envelope keyword spellings: `:cause {:kind :reads|:props|…}`, `:attempt` (`:committed`), `:completeness`, `:loss`, loss labels `:unknown` / `:opaque` / `:no-static-analysis` / `:host-opaque` / `:cap` / `:uncorrelated` — ch15. Spec §10 pins the vocabulary in prose, not the keyword forms.
21. Hydration-mismatch report shape `{:id :root :where :error}` — ch17. The id `:rf.ssr/hydration-mismatch` is attested (re-frame.ssr); the report-map keys are the mint.
22. Migration tool surface — verified ATTESTED, not mints: the CLI (`clojure -M:run`, `--report`, `--rewrite`, `--write`), all 19 needs-your-hands/blocker class keywords, rewrite families W1–W6, and the `__rf2` hygienic-naming convention all match `migration/reagent-to-hicasso/codemod` source exactly. Only the shadow-mode surface (row 8) is minted.

### (b) The catalogue gaps (refusals taught by mechanism with no attested id)

Complaint-catalogue entries to mint. The retired decline id must not be reused (gap 12).

1. `defview` direct-call refusal — fires at the call, names the view — ch01, ch02, ch14. (Freehand's `:rf.error/view-called-directly` belongs to the other substrate; Hicasso needs its own entry.)
2. `n/$` dynamic-map-in-props-position misclassification (map lands as a child; recovery names `n/props`) — ch10.
3. `n/$` hiccup-vector-child refusal (square brackets have no meaning past the fence; recovery names `h/as-element`) — ch10.
4. `n/$` event-vector-in-native-prop refusal (no intent lowering past the fence) — ch10.
5. `n/$` `:children`-in-props refusal (one child channel) — ch10.
6. `n/$` canonical-slot-collision refusal (two source keys, one React slot) — ch10.
7. Test kit: L2 opacity refusal — body or expanded child reaches a hook, raw React element, `n/$` result, or `defhost` crossing; structured, source-located, points at L3 — ch14.
8. Test kit: L2 missing-fixture refusal, naming the query — ch14.
9. Test kit: `ht/assert-clean!` residue failure (post-quiescence residue vs pre-mount baseline) — ch14.
10. Forms-module rows — ch05 deliberately teaches behavioural failure modes with no module ids; the sitting decides whether any (duplicate `:control` address, commit-after-cancel arrival) deserve catalogue ids. [item 22 carry]
11. Contenteditable controlled-binding refusal (`:value` on a contenteditable region refuses at source; recovery names the host/native routes) — ch04.
12. `:prefetch` wrong-value refusal at render (any value other than `:intent`) — ch07. Needs a NEW id; `:rf.error/hicasso-route-link-prefetch-declined` is the retired draft-era decline and stays dead.
13. Second `h/mount!` on a node a live root owns — ch01 troubleshooting, mechanism only.

NOT gaps — attested ids exist that the pages describe by mechanism only (candidates for citation when the catalogue is compiled; Stage C made no page edits for these per the verify-and-compile rule): route-link bare-intent veto → `:rf.error/hicasso-route-link-bad-on-click`; ambient `rf/subscribe`/`rf/dispatch` in a body → `:rf.error/ambient-frame-refused`; the `defhost` declaration-time family ch09 summarises as "fail at the declaration" → `:rf.error/hicasso-unknown-callback-contract`, `:rf.error/hicasso-host-no-component`, `:rf.error/hicasso-host-callback-slot-collision`, `:rf.error/hicasso-host-structural-callback`; the presence rules ch11 states as law → `:rf.error/hicasso-presence-timeout-required`, `:rf.error/hicasso-presence-child-unkeyed`, `:rf.error/hicasso-presence-child-not-hiccup`; route-link outside a boundary → `:rf.error/hicasso-route-link-outside-boundary`.

## Stage D findings (tutorial structure)

Scope: sweeps per the Stage D brief — per-chapter shape, cross-chapter reading path, proportion. Items 3, 4, 24 resolved inline above. Stage C's resolutions untouched; no taught semantics, names, or error ids changed; no files renumbered; docs/ untouched.

### Sweep 1 — per-chapter shape (all 22 files walked)

- Openings: every chapter states the reader's problem in 1–2 sentences before anything else — no edits needed.
- Goal → working code → explanation holds everywhere; no internals-first chapter found. ch04's open (required path as the first code block) is the strongest instance of the pattern.
- Example threads: ch06 (one orders table through four topologies) and ch14 (one todo row up the ladder) are exemplary single-thread chapters. ch03/ch04/ch05 carry several small examples, each serving a distinct API position or module feature; consolidation onto one domain was judged NOT cheap (would contrive features onto the todo/editor threads and touch names Stage C censused) — left as-is. No true example zoo found.
- Required path / Advanced: one violation — ch09's `The crossing has no memo wrapper` sat as a body H2 between Portals and the escape; moved under `## Advanced` (compressed). All other Advanced material was already placed after Troubleshooting/When-not.
- Code-block framing (why-you-care before, what-it-did after): spot-checked across all chapters; no violations worth an edit (ch01's paired build-config blocks share one lead-in, acceptable).
- H2 scannability: fixed one — ch10's `## The working loop` implied a second owner of ch18's method; now `## Every crossing runs the loop` (a pointer-shaped beat). All other chapters' H2 runs retell their chapter.

### Sweep 2 — cross-chapter path

- Order of concepts (01→21): no chapter before 10 leans on native-tier knowledge (ch04/ch05 carry pointers only; ch09's "when not to host" and Advanced route *to* ch10, teaching nothing native). ch06's virtualizer uses `h/defhost`/`h/as-element` ahead of ch09 — as the chapter map itself mandates (virtualization is ch06's job); the page glosses both inline and defers mechanics explicitly to ch09, so the path holds. ch05 uses the mutation-execute/instance-status surface with a one-sentence delegation of registration/supersession/optimistic depth to ch08 — uses, does not assume; holds. ch20 leans on nothing past ch17 (route `:on-match` 07, slots 09, `n/lazy` 10, error-boundary 16, SSR 17 — all earlier). ch07's `::h/navigate` is taught where minted; `::h/prevent` owned by ch03 before every later use.
- Item 3 (keys) and item 4 (working loop): resolved above.
- Other duplicated teaching, one-owner fixes made: ch02's Advanced `The collector` restated body claims 1 and 4 — trimmed to the mechanism the claims imply (abandoned renders, lazy-seq forcing, loud escape); the "design around it in very hot lists" advice dropped there because ch06's `Oscillating read sets` owns it. ch04's Advanced IME rule repeated ch03's gate mechanics (native event, legacy signal) — now a pointer, law kept. ch09's escape re-taught ch03's capture-frame with a second full example — compressed to pointer (item 24). ch09's provider/SSR depth compressed against ch17 (owner).
- Duplication judged deliberate and kept: owned-wins merge in ch02 (general recipe) and ch04 (controlled-slot law) — ch02 explicitly delegates the controlled case to ch04; layered, not duplicated. Ladder table in ch10 (owner) and ch18 (map-sanctioned "recap"). Escape-benefit rule one-liner on ch10 and ch18 (brief-listed fact both use). ch05 form-scoped instance-status usage vs ch08's general law (ch05 delegates explicitly).

### Sweep 3 — proportion

Post-D lengths: 01: 226, 02: 319, 03: 287, 04: 307, 05: 311, 06: 304, 07: 344, 08: 351, 09: **360**, 10: 234, 11: 307, 12: 289, 13: 245, 14: 353, 15: 267, 16: 177, 17: 372, 18: 247, 19: 285, 20: 204, 21: 168, README: 30.

- ch09 (item 24): 402 → 360, resolution above.
- ch17 at 372: judged NOT drag — it owns two halves (server render + hydration) with almost no verbatim repetition, no admonition pileups, and its length is Stage-B-era teaching, not padding. Left alone; flagged here for Stage E's awareness.
- ch08 (351) and ch14 (353): within noise of target, read tight; no treatment.
- No chapter shows three-admonitions-in-a-row or over-explained code.

### Files changed (before → after)

| File | Before | After | What |
|---|---|---|---|
| `v1/02-views-and-reads.md` | 340 | 319 | keys section → essential minimum + ch06 pointer (item 3); entity-key troubleshooting row removed; Advanced collector de-duplicated against body claims |
| `v1/04-controlled-inputs.md` | 308 | 307 | Advanced IME gate mechanics → ch03 pointer |
| `v1/09-interop.md` | 402 | 360 | item 24 resolution (see above) |
| `v1/10-native-tier.md` | 242 | 234 | working-loop steps → ch18 pointer + fence follow-through (item 4) |

All other v1 files unchanged. Nothing committed; `docs/` untouched.

## Stage E findings (AUTHORING.md conformance)

Scope: the four Stage-E sweeps over all 22 v1 files against the full `docs/AUTHORING.md`, with site-only rules (mkdocs nav rows, live `cljs-rf2` cells, `mkdocs build --strict`) out of scope for a `docs/design/hicasso/` landing. No semantics, names, or error ids changed; nothing renumbered; `docs/` untouched; senior-developer voice kept (STE deliberately not applied — Stage F owns it).

### Sweep 1 — non-negotiables (all clean; 1 fix)

- **Standalone corpus: zero violations.** Grep-verified across all 22 files: no links or references into `spec/`, no bead ids, no links into `docs/design/hicasso/product/`, and no markdown links leaving `v1/` at all (no `../`, no absolute, no external URLs).
- **True snippets: zero violations.** Every `;; Don't` block walked (ch02 ×2, ch03, ch10 ×2, ch11 ×2, ch12, ch13, ch16, ch17, ch18, ch21): all labeled, all paired with the correct form or an explicit failure comment, none demoed as success. ch17's deliberate hydration divergence is labeled `;; Don't — deliberate divergence, kept to show the complaint`.
- **One job / one mode: no drift.** Each page's mode is inferable and matches the roster (now explicit in the README's Mode column — Sweep 4). ch01's `More than one root` stays start-mode (it is mount teaching, Stage B's addition); ch11 reads as the explanation page it is.
- **Reader-first order: nothing regressed** post-D; every chapter still opens problem → working code → explanation.
- Fix: ch01's direct-call troubleshooting row said "a `defview` is a boundary" — jargon the reader does not meet until ch02. De-jargoned to "a `defview` is mounted as a Hiccup head, never invoked" (Stage C's by-mechanism wording preserved).

### Sweep 2 — concept five-piece, headings, ceremony (all clean)

- All 11 concept-mode chapters (02, 03, 04, 05, 06, 09, 10, 14, 15, 16, 17) carry all five pieces: problem open, unlabeled required path, `## Troubleshooting` table, a when-not section, and `## Advanced` only where needed (02, 03, 04, 05, 09, 14, 15 have one; 06, 10, 16, 17 correctly have none). The how-to/explanation chapters carry troubleshooting + when-not too.
- Headings: every troubleshooting heading is exactly `## Troubleshooting`, every advanced exactly `## Advanced`. Grep for "When things go wrong", "Going further", "Basics", "Day one", "Start here", "What's next": zero (ch10's ladder cell "Always start here" is a table cell, not a heading).
- No navigation ceremony anywhere: no "What's next" footers, no mini-TOCs, no page restates the README table.

### Sweep 3 — voice (7 fixes; the rest judged fine)

- Status-language grep hits, all fixed: ch09 "each load-bearing" → "each blocking a real defect"; ch06 "seamless scrolling" → "continuous scrolling"; filler "simply" removed at ch03 ("omits it"), ch10 ("a head as-is"), ch11 ("*true during entry*"). Zero hits for foundational/pivotal/powerful.
- "just" audit: 16 hits, every one meaningful (temporal "just made", "not just", or the operative "is just a function call" claim) — no filler, no edits.
- Metaphor budget: one committed metaphor per concept holds (ch10 trap door, ch11 pressure valves, ch05 traps, ch14 ladder+sabotage-twin are product vocabulary). One violation: the clothing metaphor ran four times across ch10/ch18 ("wearing a costume" ×2, "wearing armor", "wearing an optimisation's clothes") — trimmed to one, keeping ch18's "a rewrite wearing an optimisation's clothes"; the others became operational ("almost always a rung-1 or rung-2 problem underneath", "Unattributed \"slow\"", "rung-1 code at rung-3 prices").
- Admonition budget: max two adjacent boxes anywhere (ch10's islands note + UIx info); no three-in-a-row on any page.
- Pull-quotes: exactly one `> **…**` per chapter, zero on the README — no violations.
- Key terms at first use: **frame** defined ch01 ("isolated app-db, queue, and subscription cache"); **boundary** defined ch02 before use (ch01 leak fixed above); **intent** was used from ch02 onward without a definition anywhere — now glossed at its ch02 first mention ("the event vectors in those attributes — *intents*") and defined at ch03 first use ("an event vector sitting at an event position is an **intent** — what the interaction means, stated as data"); **adoption** — every pre-ch17 use carries its meaning in-sentence with a ch17 pointer, ch17 defines it in the open. "seam" kept: used consistently as the corpus's name for the interop crossing (ch09/ch10), a term of art here, not decoration.

### Sweep 4 — housekeeping

- README: rosters all 21 chapters with one-job labels verified accurate against each page; **Mode column added** (Stage B's note): ch20 = concept/how-to, ch21 = how-to; the rest match the brief's chapter map ("(short)" qualifiers dropped as process metadata).
- Tables: scripted column-count check over every table in all 22 files — zero inconsistencies.
- Links: scripted check — every relative link and every anchor (including `#choosing-the-address`, `#troubleshooting`, `#advanced`) resolves within v1/ — zero problems. Zero external links.

### ch17 verdict (Stage D's ch17-at-372)

**Stands at 372 — no cut.** Concur with Stage D: the page owns two halves of one contract (server render + hydration adoption) with no verbatim repetition, no admonition pileup, and every section doing distinct required teaching (per-surface table, request lifecycle, payload policy, per-root verdicts, transparent-wrapper deletion, native tier, Node service). Under "length is a smell, not a rule" I found no real drag — sentence-level trimming would shave lines without removing weight, and splitting would sever the server-render/hydration halves that explain each other.

### Files changed (before → after)

| File | Δ | What |
|---|---|---|
| `v1/README.md` | 30 → 30 | Mode column added; labels verified |
| `v1/01-getting-started.md` | 226 → 226 | de-jargoned "boundary" in troubleshooting row |
| `v1/02-views-and-reads.md` | 319 → 320 | intent glossed at first mention |
| `v1/03-events-as-data.md` | 287 → 289 | intent defined at first use; "simply" dropped |
| `v1/06-lists-and-collections.md` | 304 → 304 | "seamless" → "continuous" |
| `v1/09-interop.md` | 360 → 360 | "load-bearing" → operational |
| `v1/10-native-tier.md` | 234 → 234 | "simply" dropped; costume metaphor → operational |
| `v1/11-ephemeral-state.md` | 307 → 307 | "simply" dropped |
| `v1/18-performance.md` | 247 → 247 | two clothing-metaphor repeats → operational (one kept) |

All other v1 files unchanged. Corpus total 5987 → 5990 lines.

### Left for Stage F

- Apply Simplified Technical English per its own brief. Note: the retained one-jab lines (ch10 "trap door" frame and "complexity drawing a salary", ch14 "a test that cannot fail is a comment", ch18 "a rewrite wearing an optimisation's clothes", ch12 "the triangle is free") are deliberate voice under AUTHORING's Yegge-seasoning rule — each decorates a precise operational claim that must survive any rewording.
- No structural, roster, link, or snippet work remains; Stage F inherits a corpus clean against AUTHORING.md's non-site rules.

## Stage F completion

2026-08-10 14:21:03 AUSEST — completion agent (predecessors killed by spend limit). Scope: the four files the killed agents never STE-processed, plus verification of the six they rewrote without self-checks. Per STE-RULES.md; nothing committed; `docs/` untouched.

### Rewritten (STE pass applied; code blocks and table structure/data byte-identical; per-file self-checks run)

| File | Before → after | Checks |
|---|---|---|
| `v1/06-lists-and-collections.md` | 304 → 313 (+3.0%) | ids identical (`hicasso-bad-head` ×1, `hicasso-entity-key` ×2, `hicasso-missing-key` ×3); fences 10; headings identical except `### Windowed: the DOM stops pretending` → `### Windowed: only the visible rows exist` (meaning-identical de-metaphor; no inbound anchors) |
| `v1/07-routing-and-navigation.md` | 344 → 352 (+2.3%) | ids identical (`can-leave-non-boolean` ×1, `hicasso-malformed-navigate` ×2, `routing-artefact-missing` ×2); fences 22; heading set unchanged; focus recipe converted to a numbered 3-step list per STE rule 9 |
| `v1/10-native-tier.md` | 234 → 234 (0%) | id identical (`no-frame-context` ×1); fences 12; headings identical except `## When not to open the trap door` → `## When not to go native` (meaning-identical); trap-door / salary / rent-free / homework / rhyme metaphors converted to operational statements, matching the completed agents' corpus-wide treatment (ch12/14/18's retained one-jab lines were likewise operationalized) — the benefit rule's three thresholds preserved verbatim |
| `v1/21-accessibility.md` | 168 → 173 (+3.0%) | zero ids before and after; fences 10; heading set unchanged; semantic-vocabulary enumeration converted to a vertical list; the frozen `11-ephemeral-state.md#choosing-the-address` anchor untouched |

Residual table-cell contractions in 07 (×1) and 21 (×5) normalized to full forms under the "MAY simplify prose inside a cell" allowance, matching the contraction-free cells of the ten completed files. Structure and data meaning unchanged.

### Verified (rewritten by killed agents; self-checks run now)

All six: **verified-ok, no repairs needed.** Per-file: (a) id census — every cited id matches the Stage C spellings, cross-consistent between files (`hicasso-revision-not-controlled` identical in 04/05/15; `hicasso-host-bad-ssr-policy`, `hicasso-host-fallback-boundary-head`, `hicasso-host-unknown-option` identical in 09/17); corpus-wide distinct-id count is exactly the Stage C census's 33 (the three extra grep hits in 15 are `:rf.error/*` wildcard prose and the `:rf.error/id` ex-data key, not ids); retired/removed ids (`hicasso-route-link-prefetch-declined`, `view-called-directly`, `view-read-outside-render`) absent; ch09 keeps everything item 24 requires (`no-frame-context`, `hicasso-intent-needs-the-event`, `hicasso-host-bad-ssr-policy`) and still cites the declaration-time family by mechanism only, per the NOT-gaps rule. (b) Headings: exact `## Troubleshooting` / `## Advanced` where present; zero banned variants. (c) No truncation — every file ends with a complete section (04/05/09 end their Advanced subsections whole; 17 ends its full troubleshooting table; 08 ends its closed info admonition; 20 ends its when-not list). (d) Fences even: 12/18/16/22/14/10. (e) Prose spot-reads across openings, middles, and tails: full STE register throughout; zero contractions, zero filler intensifiers in all six.

| File | Verdict | Lines (Stage D/E → now) |
|---|---|---|
| `v1/04-controlled-inputs.md` | verified-ok | 307 → 318 |
| `v1/05-forms.md` | verified-ok | 311 → 322 |
| `v1/08-async-resources.md` | verified-ok | 351 → 381 |
| `v1/09-interop.md` | verified-ok | 360 → 397 |
| `v1/17-ssr-and-hydration.md` | verified-ok | 372 → 380 |
| `v1/20-code-splitting.md` | verified-ok | 204 → 211 |

All ten within the ±20% line-count bound. The other twelve v1 files (completed and self-checked by predecessor agents) were not re-audited beyond the corpus-wide id and heading sweeps above, which they pass.
