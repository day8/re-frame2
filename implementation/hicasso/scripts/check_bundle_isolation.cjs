#!/usr/bin/env node
/*
 * THE ZERO-RENT PROOF FOR EVERY ISOLATED SURFACE, read off a real
 * production bundle (rf2-hic-034; the forms module added by rf2-sh56,
 * motion and overlay by rf2-ot28g, the server module by rf2-fn62g).
 *
 * Five surfaces are measured here and the law each answers to is its
 * own: the NATIVE TIER, whose clause is quoted next, and the optional
 * MOTION, OVERLAY, FORMS and SERVER modules, whose clause is
 * `invariants.md` §1 — *optional libraries: named consumer required;
 * zero reachable production code when absent*. They share this file
 * because they share one instrument and one bundle; each surface's own
 * reasoning is with its rows.
 *
 * THE HEADING MEANS WHAT IT SAYS OF THE MODULE ROSTER, and it now says
 * it of all five. Every module `check_optional_module_reachability.py`
 * rows is measured here. The last to arrive was
 * `re-frame.hicasso.server`, which this file carried for one bead as a
 * named gap on the ground that it is a Node module and the one bundle
 * this gate reads is a browser build — see the server section below,
 * where that objection is answered rather than inherited.
 *
 * > 6. The native namespace is separately reachable. An interpreted-only
 * >    production dependency graph and bundle contain neither native-tier
 * >    runtime nor UIx code.
 * >
 * >   — the native-boundary law,
 * >     `docs/design/hicasso/product/lanes/design-laws.md`
 *
 * Clause 6 has TWO halves and they are checked in two places, because
 * neither instrument can answer the other's question:
 *
 *   - the DEPENDENCY GRAPH is a property of the source, and
 *     `check_optional_module_reachability.py` decides it over `:require`
 *     forms — exhaustively, including the shapes that leave no string in
 *     a bundle at all (see "What a string scan cannot see" below);
 *   - the BUNDLE is this file. It reads the one build in the repo that
 *     compiles the package the way a consumer ships it — `:hicasso-release`,
 *     `:advanced` + `goog.DEBUG=false`, entry `re-frame.hicasso.consumer-app`
 *     — and asks whether any native-tier runtime survived into it.
 *
 * The source-side gate is the stronger of the two and is not made
 * redundant by this one. What this one adds is confidence about the
 * COMPILER and the LINKER: that an unreferenced namespace really does
 * leave nothing behind after Closure has run, on the real graph, at the
 * real optimisation level.
 *
 * ## Unique sentinels, never a public-name grep
 *
 * Grepping the bundle for `native` proves nothing — the word occurs in
 * React's own event plumbing and in the codec's prose. Each row below is
 * a string that ONE surface emits and nothing else does, so a hit names
 * WHICH surface leaked rather than merely that something did.
 *
 * A sentinel is only worth scanning for if it is LOAD-BEARING. A marker
 * planted beside the code would be dropped by Closure whether or not the
 * isolation held, and the gate would pass for the wrong reason. Both
 * sentinels here are strings their surface cannot function without:
 *
 *   - `rf2:hicasso-native-tier` is the own-property NAME
 *     `native/component` writes with `unchecked-set` and `native/marker`
 *     reads back with `unchecked-get`. Closure cannot rename a string
 *     used as a computed property key and cannot delete it while the
 *     code that writes it is reachable. `native.cljc` says so at
 *     [[tier-sentinel]], where it also names this bead as the proof.
 *   - `rf.error/hicasso-native-` is the refusal-id FAMILY the tier mints
 *     — all seven of its ids share it and no other surface uses it.
 *
 * ## Two sentinels because there are two reachability shapes
 *
 * They are not belt-and-braces. Closure's dead-code elimination works
 * per function, so which strings survive depends on WHICH of the tier a
 * consumer touched:
 *
 *   - a consumer who writes `n/defcomponent` (or `n/memo` / `n/lazy`)
 *     drags `component` in, and with it the marker string — while the
 *     refusal ids may fold away entirely, since `checked`'s per-child
 *     fence is `debug-enabled?`-gated;
 *   - a consumer who writes `(n/props …)` drags `props*` and therefore
 *     `prop-slots` in, whose last three refusals are UNGATED and hold on
 *     both paths — while `component` is never called and the marker
 *     string is absent.
 *
 * Either sentinel alone is green against the other's leak. Both together
 * cover every part of the tier that has a production footprint.
 *
 * ## What a string scan cannot see, stated rather than left to be found
 *
 * There is a third shape and it leaves NOTHING to find: a consumer who
 * writes only `n/$` with literal props. The macro lowers the props at
 * expansion, `checked` folds away under `goog.DEBUG=false`, and what
 * remains is `react/createElement` — React's own function, which every
 * bundle already has. Past DCE, `n/$` IS `createElement`.
 *
 * That is not a hole in the zero-rent claim; it is the claim at its
 * strongest, and it is why the source-side gate exists and why this file
 * does not pretend to subsume it. A scan that reported green there would
 * be right by accident, so the sentence is written here instead of a row
 * nobody can falsify.
 *
 * ## Reachable positive controls
 *
 * An absence check over a bundle that never compiled anything is a green
 * that means nothing, and it is the failure this class of gate is most
 * exposed to. So each sentinel is PAIRED with a control that must be
 * PRESENT, chosen so the pair differs by REACHABILITY and by nothing
 * else:
 *
 *   - `hicassoBoundary` (present) against `rf2:hicasso-native-tier`
 *     (absent) — two own-property marker names, both written with
 *     `unchecked-set` onto a freshly minted component, one in a
 *     namespace the public door leads to and one in a namespace it never
 *     names. That pairing is what makes the absence a statement about
 *     reachability rather than about compilation.
 *   - `rf.error/hicasso-empty-vector` (present) against
 *     `rf.error/hicasso-native-` (absent) — a shipped refusal id minted
 *     by `impl.error/fail!` against the tier's own family minted by the
 *     same `fail!`. If `fail!` had been dropped whole, both would be
 *     absent and the first is what says so.
 *   - `rf-uix-sub-` (present) — and this one is the interesting control.
 *     See the next section.
 *
 * ## `zero UIx` does not hold, and the reason is worth a control
 *
 * Clause 6 says an interpreted-only bundle contains no UIx code. THE
 * MEASURED BUNDLE CONTAINS UIx, and it is not a defect: Hicasso ships no
 * reactive adapter of its own — `consumer_app.cljs`'s docstring says so
 * where the consumer is standing — and the three adapters a consumer may
 * pick from are Reagent, reagent-slim and UIx. Every one of them is a
 * view library. The exemplar picks UIx, so `re-frame.adapter.uix` is on
 * the entry's require list and `uix.core` comes with it.
 *
 * A row asserting `no uix` here would be red on a correct build, and a
 * row asserting it against some other fixture would be asserting a fact
 * about which adapter the fixture happened to choose. So the string is
 * carried as a CONTROL instead, where it earns its place twice: it
 * proves a whole React view library and a whole reactive substrate
 * really compiled into this bundle, which makes the native tier's
 * absence a much sharper reading than it would be from a bundle that
 * compiled almost nothing.
 *
 * The clause's UIx half is therefore a claim about a consumer's
 * dependency graph — *nothing Hicasso makes you take drags UIx in* —
 * and that is the source-side gate's kind of question, not a bundle's.
 * It is reported rather than quietly re-scoped.
 *
 * ## The forms module's two sentinels, and why they are NOT two shapes
 *
 * rf2-sh56 shipped `re-frame.hicasso.forms` — the optional module whose
 * `buffered-field` the guide's chapter 5 documents — and it is measured
 * here beside the native tier. The rows are two, and the reason is
 * DIFFERENT from the native tier's, so the sentence above is not
 * borrowed:
 *
 *   - the tier has two reachability shapes because `n/defcomponent` and
 *     `(n/props …)` drag different code, and either sentinel alone is
 *     green against the other's leak;
 *   - the forms module has ONE. It registers its events at namespace
 *     load, so nothing about it is conditionally reachable: require it
 *     and both strings are present, do not and neither is.
 *
 * What the second row buys is therefore not coverage of a second path.
 * It is that a hit NAMES WHICH HALF leaked, and that the two halves can
 * be moved independently. The module is a view over a protocol, and the
 * live way for it to leak is for the protocol to be pulled down into
 * `impl.*` to be shared with something — at which point the concern's
 * keyword reaches a bundle the view's name does not. One row would
 * report that as a clean bundle.
 *
 * Each is paired with an EXISTING control of the same idiom, so the pair
 * differs by reachability and by nothing else:
 *
 *   - `re-frame.hicasso.forms/buffered-field` (absent) against
 *     `re-frame.hicasso.consumer-app/app` (present) — two `h/defview`
 *     view names, both stamped by `mint-view!` unconditionally, one in a
 *     namespace the release entry requires and one in a namespace
 *     nothing does;
 *   - `re-frame.hicasso.forms/drafts` (absent) against
 *     `rf.error/hicasso-empty-vector` (present) — two namespaced keyword
 *     literals whose fully-qualified names survive `:advanced` because a
 *     keyword carries its own name at runtime. The concern is
 *     load-bearing three times over: `reg-state` makes it a sub id, an
 *     event id and an `app-db` key at once, so the module cannot read or
 *     write a draft without it.
 *
 * ## Motion and overlay: ONE shape and TWO, decided rather than assumed
 *
 * rf2-ot28g closed the gap this file had carried since rf2-sh56: the
 * source-side gate covered four optional surfaces and this one covered
 * two, so a green here was reported under a heading reading "every
 * isolated surface" while saying nothing about motion or overlay. The
 * file already knew both modules existed — `motion/presence` sat in the
 * near-miss list below as a string the scan is asserted NOT to fire on —
 * which made the silence a choice rather than an oversight, and the
 * wrong one.
 *
 * All three new rows are stamped `displayName`s, which is not a new
 * idiom but the one the `hicassoBoundary` control is already read
 * through: `codec/mark-boundary!` writes its marker with `unchecked-set`
 * onto a freshly minted component, and each of these modules writes a
 * NAME onto that same component in the same expression. The pair
 * therefore differs by reachability and by nothing else — same function,
 * same bundle, same moment in the namespace's load — which is the
 * property that makes an absence a statement about the linker.
 *
 * The shape question was settled per module rather than answered once:
 *
 *   - MOTION HAS ONE. The module's whole product is `presence`, a single
 *     component minted at `impl.presence-react`'s namespace load. There
 *     is no second door to reach it by, so one row covers every way in.
 *   - OVERLAY HAS TWO, and it is the NATIVE TIER's situation rather than
 *     the forms module's. `popover` and `modal` are two independent
 *     top-level components in one namespace; Closure's elimination is
 *     per-var, so a consumer who re-exports `overlay/popover` beside
 *     `h/portal` drags the anchor machinery in and leaves the dialog's
 *     inert-backdrop and focus-wrap code behind. Either sentinel alone
 *     is green against the other's leak.
 *
 * A NOTE ON WHAT WAS NOT CHOSEN, because it is the more obvious string
 * and it is weaker. `--rf-overlay-` — the CSS dashed-ident prefix
 * `next-anchor-ident` mints — is genuinely load-bearing, and the
 * reachability gate's own header already records it measuring ZERO on
 * this bundle. But it is minted inside a ref callback on the ANCHORED
 * path only, so it is absent from a bundle that carries the whole of
 * `modal` and an unanchored `popover`. It is a narrower instrument than
 * the two names above and would have been green on a real leak.
 *
 * ## The server module: a Node module IS a browser-bundle question
 *
 * rf2-fn62g inherited an objection worth answering rather than
 * repeating. `:hicasso-release` is a BROWSER build and
 * `re-frame.hicasso.server` is a Node one, so — the reasoning ran — its
 * row here asks a different question from the other four's, and might
 * be category-confused.
 *
 * IT IS NOT, and the roster comment in
 * `check_optional_module_reachability.py` says why in the course of
 * saying something else: *a `:require` of this namespace drags
 * `react-dom/server` into the bundle of every application that ever
 * aliased `h`, for a code path no browser will ever run.* This gate
 * does not ask whether a module works in a browser. It asks whether any
 * byte of it REACHED one — a question that is meaningful for any
 * namespace in the package, and sharpest for a Node-only one, because
 * nothing about this module has any business in a browser bundle at
 * all. It is the most expensive leak on the roster, not the least
 * checkable one.
 *
 * THE SENTINEL IS `hicasso.ssr`, the keyword NAMESPACE `fresh-frame-id`
 * mints the per-request frame id under. A string handed to `keyword` as
 * a runtime argument is a value: `:advanced` can neither rename it nor
 * drop it while the code that passes it is reachable, and inlining
 * `fresh-frame-id` into its one caller moves the literal rather than
 * removing it.
 *
 * It is CO-REACHABLE WITH THE EXPENSIVE HALF BY CONSTRUCTION, which is
 * the property that made it worth taking over the more obvious strings.
 * `react-dom/server` enters this bundle by exactly one route — `render`
 * — and `render`'s first binding is `(fresh-frame-id)`. There is no
 * path on which `renderToString` is in the bundle and this string is
 * not.
 *
 * WHAT IT DOES NOT COVER, named rather than left to be discovered.
 * `document` and `payload-script` are independently reachable public
 * vars (naming-ledger row 50 rules all five helpers public), and
 * Closure's elimination is per-var, so a leak of those ALONE is green
 * here. That is a deliberate limit rather than an oversight: their rent
 * is string concatenation over the framework's own escapers with no npm
 * dependency behind it, and the source-side gate decides the door
 * exhaustively either way. A second row would buy a sentinel for the
 * cheapest leak this module can have, which is the direction the
 * `--rf-overlay-` note above already rules out.
 *
 * AND THERE IS A THIRD SHAPE THAT LEAVES NOTHING TO FIND, exactly as
 * `n/$` has one — the paragraph above is the pattern this one follows
 * rather than an excuse borrowed for the occasion. Every name in this
 * module is a `defn` and the namespace has NO top-level side effect, so
 * unlike `hicasso/presence` and the forms rows — both stamped at
 * namespace load — a bare `:require` that never calls anything can be
 * DCE'd to nothing on the CLJS side. That is not a leak this gate is
 * missing; it is the source-side gate's question, and it decides it
 * exhaustively by forbidding any `:require` of the module from `src/`
 * at all. What this row adds is the case that gate cannot see: the
 * module reachable AND USED, which is the only way `react-dom/server`
 * earns its bytes, and the only way anyone would author the leak in the
 * first place — a door does not require a namespace in order to ignore
 * it.
 *
 * WHAT A HIT DOES NOT DISTINGUISH, stated for the same reason. The
 * prototype this module is the product form of —
 * `bench/hicasso/src/re_frame/bench/hicasso/ssr/entry.cljs` — mints the identical
 * keyword namespace, so a red here names one of two surfaces rather
 * than one. Both are leaks: a bench fixture in the release bundle is
 * its own defect and a worse one. The remedy below says to check both,
 * which is the honest reading of a sentinel two surfaces emit.
 *
 * THE CONTROL IS `rf-uix-sub-`, ALREADY ON THE ROSTER, and the pairing
 * is exact rather than convenient: both are string literals handed to a
 * runtime name-minting call — one a `gensym` prefix in the UIx adapter
 * the release entry installs, one a keyword namespace in a namespace
 * the public door never names. The pair differs by reachability and by
 * nothing else, which is what this file asks of a pairing. No new
 * control is minted for this row.
 *
 * ## The live half is a test, not a comment
 *
 * A sentinel that has quietly stopped being emitted is absent from every
 * bundle for a reason that has nothing to do with isolation. The roster
 * premise below is checked against the source before the bundle is
 * opened, so a rename fails the gate rather than reporting a green
 * absence for a string nobody emits any more; and
 * `re-frame.hicasso.native-fence-cljs-test`'s
 * `the-tier-sentinel-is-reachable-rather-than-a-dead-literal` asserts in
 * the ordinary `goog.DEBUG` node lane that the marker is what a LIVE
 * `n/defcomponent` really stamps. Surface on, string present; surface
 * unreachable, string gone — and neither half is evidence alone.
 */

'use strict';

const fs = require('fs');
const path = require('path');

const HERE = path.dirname(path.resolve(__filename));
const PACKAGE_ROOT = path.dirname(HERE);
const IMPL_ROOT = path.dirname(PACKAGE_ROOT);

const BUNDLE = path.join(IMPL_ROOT, 'out', 'hicasso-release', 'main.js');

// ---------------------------------------------------------------------------
// The roster
//
// `sentinel` is scanned for in the bundle and must be ABSENT. `source` is
// where it is emitted from, and `premise` is the text that must still be
// found there — usually the sentinel itself, spelled the way the source
// spells it.
// ---------------------------------------------------------------------------

const SENTINELS = [
  {
    surface: 'native tier — the marker every minted component carries (rf2-hic-030)',
    sentinel: 'rf2:hicasso-native-tier',
    source: 'src/re_frame/hicasso/native.cljc',
    premise: '"rf2:hicasso-native-tier"',
    why:
      'The own-property name `native/component` writes with `unchecked-set` ' +
      'onto the author\'s own function, and `native/marker` reads back with ' +
      '`unchecked-get`. It is in this bundle if and only if `defcomponent`, ' +
      '`n/memo` or `n/lazy` is reachable from the entry.',
    remedy:
      'Find the `:require` of `re-frame.hicasso.native` that made the tier ' +
      'reachable from the public door. Nothing under implementation/hicasso/src/ ' +
      'may name it; an application requires it directly, in the region that ' +
      'wants it.',
  },
  {
    surface: 'native tier — the refusal-id family the fence mints (rf2-hic-030)',
    sentinel: 'rf.error/hicasso-native-',
    source: 'src/re_frame/hicasso/native.cljc',
    premise: ':rf.error/hicasso-native-slot-collision',
    why:
      'The prefix all seven of the tier\'s refusal ids share and no other ' +
      'surface uses. It reaches a bundle by a DIFFERENT route from the marker ' +
      'above: `prop-slots`\'s last three refusals are ungated, so a consumer ' +
      'who writes `(n/props …)` and never a `defcomponent` leaks these and ' +
      'not the marker. Either sentinel alone is green against the other\'s ' +
      'leak, which is why there are two.',
    remedy:
      'Same as above — the tier became reachable. `props*` is the runtime ' +
      'half of `n/$`\'s props rule and only a dynamic props operand calls it.',
  },
  {
    surface: 'forms module — the view the chapter documents (rf2-sh56)',
    sentinel: 're-frame.hicasso.forms/buffered-field',
    source: 'src/re_frame/hicasso/forms.cljs',
    premise: '(h/defview buffered-field',
    why:
      'The view NAME `h/defview` computes and `mint-view!` stamps as the ' +
      'boundary\'s `displayName`, unconditionally — the same idiom the ' +
      'release entry\'s own control is read through. It is in this bundle ' +
      'if and only if the module is reachable from the entry.',
    remedy:
      'Find the `:require` of `re-frame.hicasso.forms` that made the module ' +
      'reachable. Nothing under implementation/hicasso/src/ may name it and ' +
      'the release entry must not either — an application requires it in the ' +
      'region that wants a buffered field, and ' +
      '`check_optional_module_reachability.py` decides the source half of ' +
      'that exhaustively.',
  },
  {
    surface: 'forms module — the app-db concern every draft lives under (rf2-sh56)',
    sentinel: 're-frame.hicasso.forms/drafts',
    source: 'src/re_frame/hicasso/forms.cljs',
    premise: ':re-frame.hicasso.forms/drafts',
    why:
      'The `h/reg-state` concern, which is a sub id, an event id and an ' +
      '`app-db` key at once — the module cannot read or write a draft ' +
      'without it, and a keyword carries its own fully-qualified name at ' +
      'runtime, so `:advanced` can neither rename nor drop it while the ' +
      'code that uses it is reachable. It is the PROTOCOL half of the ' +
      'module, and it can outlive the view above: pull the protocol down ' +
      'into `impl.*` to share it and this string reaches a bundle the view ' +
      'name does not.',
    remedy:
      'Same as above for a whole-module leak. If this row is red ALONE, the ' +
      'draft protocol has moved somewhere the public door reaches — which ' +
      'is an architectural change, not a convenience.',
  },
  {
    surface: 'motion module — the presence boundary\'s stamped name (rf2-ot28g)',
    sentinel: 'hicasso/presence',
    source: 'src/re_frame/hicasso/impl/presence_react.cljs',
    premise: '(aset "displayName" "hicasso/presence")',
    why:
      'The `displayName` `impl.presence-react` `aset`s onto the presence ' +
      'component as it mints it, inside the same `codec/mark-boundary!` call ' +
      'the `hicassoBoundary` control below is read through. It is written at ' +
      'namespace load, unconditionally and under no `goog.DEBUG` guard, so ' +
      'the module cannot be reachable and this string be absent.',
    remedy:
      'Find the `:require` of `re-frame.hicasso.motion` — or of ' +
      '`impl.presence` / `impl.presence-react` directly — that made the ' +
      'module reachable. Nothing under implementation/hicasso/src/ may name ' +
      'it and the release entry must not either; an application requires it ' +
      'in the region that wants presence, and ' +
      '`check_optional_module_reachability.py` decides the source half of ' +
      'that exhaustively.',
  },
  {
    surface: 'overlay module — the popover boundary\'s stamped name (rf2-ot28g)',
    sentinel: 'hicasso/popover',
    source: 'src/re_frame/hicasso/impl/overlay.cljs',
    premise: '(unchecked-set "displayName" "hicasso/popover")',
    why:
      'The `displayName` stamped onto `impl.overlay/popover` at namespace ' +
      'load, by the same idiom as the two rows above. THE OVERLAY MODULE ' +
      'HAS TWO REACHABILITY SHAPES, the native tier\'s situation rather ' +
      'than the forms module\'s: `popover` and `modal` are two independent ' +
      'top-level components, so a consumer who re-exports one drags that ' +
      'one in and Closure drops the other. Either sentinel alone is green ' +
      'against the other\'s leak, which is why there are two.',
    remedy:
      'Find the `:require` of `re-frame.hicasso.overlay` — or of ' +
      '`impl.overlay` directly — that made the module reachable. If this ' +
      'row is red alone, only the anchored-panel half was re-exported.',
  },
  {
    surface: 'overlay module — the modal boundary\'s stamped name (rf2-ot28g)',
    sentinel: 'hicasso/modal',
    source: 'src/re_frame/hicasso/impl/overlay.cljs',
    premise: '(unchecked-set "displayName" "hicasso/modal")',
    why:
      'The second of the overlay module\'s two shapes — the blocking ' +
      'dialog, stamped at namespace load exactly as `popover` is. It ' +
      'reaches a bundle by a different route: `modal` is what a consumer ' +
      'who wants `showModal` and the inert-backdrop machinery drags in, ' +
      'and none of `popover`\'s anchor-positioning code comes with it.',
    remedy:
      'Same as above — the module became reachable. If this row is red ' +
      'alone, only the dialog half was re-exported.',
  },
  {
    surface: 'server module — the keyword namespace every request frame is minted under (rf2-fn62g)',
    sentinel: 'hicasso.ssr',
    source: 'src/re_frame/hicasso/server.cljs',
    premise: '(keyword "hicasso.ssr"',
    why:
      'The namespace `fresh-frame-id` hands `keyword` to mint the ' +
      'per-request frame id. A string passed as a runtime argument is a ' +
      'VALUE, so `:advanced` can neither rename nor drop it while the ' +
      'code that passes it is reachable, and inlining moves the literal ' +
      'rather than deleting it. It is co-reachable with the expensive ' +
      'half of this module BY CONSTRUCTION: `react-dom/server` enters a ' +
      'bundle by exactly one route — `render` — and `render`\'s first ' +
      'binding is `(fresh-frame-id)`. See the header for what this row ' +
      'deliberately does not cover.',
    remedy:
      'Find the `:require` of `re-frame.hicasso.server` that made the ' +
      'module reachable. Nothing under implementation/hicasso/src/ may ' +
      'name it and the release entry must not either — a Node host ' +
      'requires it, and `check_optional_module_reachability.py` decides ' +
      'the source half exhaustively. IF NOTHING REQUIRES THE MODULE, ' +
      'look at `bench/hicasso/src/re_frame/bench/hicasso/ssr/entry.cljs`: the ' +
      'prototype mints the identical keyword namespace, and a bench ' +
      'fixture in the release bundle is its own defect.',
  },
];

const CONTROLS = [
  {
    control: 're-frame.hicasso.consumer-app/app',
    source: 'test/re_frame/hicasso/consumer_app.cljs',
    premise: '(h/defview app',
    proves:
      'the release entry\'s `h/defview` really compiled — `mint-view!` stamps ' +
      'this name as the boundary\'s `displayName`, unconditionally. Without ' +
      'it the absences above would be the absences of a bundle that compiled ' +
      'no declaration at all.',
  },
  {
    control: 'hicassoBoundary',
    source: 'src/re_frame/hicasso/impl/codec.cljs',
    premise: '(unchecked-set f "hicassoBoundary" true)',
    proves:
      'the interpreted tier\'s own marker machinery compiled. It is the ' +
      'REACHABLE counterpart of `rf2:hicasso-native-tier`: same idiom, same ' +
      '`unchecked-set`, same freshly minted component, and the only ' +
      'difference between the two is whether the public door\'s require ' +
      'graph leads to the namespace that writes it. That is what makes "no ' +
      'native marker" a statement about reachability rather than about ' +
      'compilation.',
  },
  {
    control: 'rf.error/hicasso-empty-vector',
    source: 'src/re_frame/hicasso/impl/codec.cljs',
    premise: ':rf.error/hicasso-empty-vector',
    proves:
      '`impl.error/fail!` and a shipped refusal id compiled. The tier\'s ' +
      'family above is minted by the same `fail!`; if it had been dropped ' +
      'entirely both would be absent, and this is what says so.',
  },
  {
    control: 'rf-uix-sub-',
    source: '../adapters/uix/src/re_frame/adapter/uix.cljs',
    premise: ':gensym-prefix-sub     "rf-uix-sub-"',
    proves:
      'a whole React view library and a whole reactive substrate really ' +
      'compiled into this bundle — the UIx adapter the exemplar installs, ' +
      'which Hicasso deliberately ships none of its own. It is the reason ' +
      'the absences above are worth reading: a bundle carrying UIx, React, ' +
      'core and the interpreted codec, and STILL carrying no native-tier ' +
      'runtime, has isolated the tier rather than compiled nothing. It is ' +
      'also the honest record that clause 6\'s "nor UIx code" does not hold ' +
      'of this fixture and why — see the header.',
  },
];

// ---------------------------------------------------------------------------
// The scan
// ---------------------------------------------------------------------------

/** Answer the problems `bundle` has, as a list of strings. */
function scan(bundle) {
  const problems = [];

  for (const c of CONTROLS) {
    if (!bundle.includes(c.control)) {
      problems.push(
        `POSITIVE CONTROL ABSENT: ${c.control}\n` +
        `    It proves ${c.proves}\n` +
        '    Every absence this gate reports below is therefore ' +
        'unfalsifiable. Fix this first.'
      );
    }
  }

  for (const s of SENTINELS) {
    if (bundle.includes(s.sentinel)) {
      problems.push(
        `OPTIONAL SURFACE LEAKED: ${s.surface}\n` +
        `    sentinel: ${JSON.stringify(s.sentinel)}\n` +
        `    ${s.why}\n` +
        `    ${s.remedy}`
      );
    }
  }

  return problems;
}

/** Answer the roster's own broken premises, as a list of strings. */
function checkPremises() {
  const problems = [];
  const rows = [
    ...SENTINELS.map((s) => ({ text: s.premise, source: s.source, of: `sentinel ${JSON.stringify(s.sentinel)}` })),
    ...CONTROLS.map((c) => ({ text: c.premise, source: c.source, of: `positive control ${JSON.stringify(c.control)}` })),
  ];

  for (const row of rows) {
    const file = path.join(PACKAGE_ROOT, row.source);
    if (!fs.existsSync(file)) {
      problems.push(
        `ROSTER PREMISE BROKEN: the source of ${row.of} is gone — ${row.source}\n` +
        '    A scan for a string nobody emits any more is a green that means nothing.'
      );
      continue;
    }
    if (!fs.readFileSync(file, 'utf8').includes(row.text)) {
      problems.push(
        `ROSTER PREMISE BROKEN: ${row.source} no longer contains ${JSON.stringify(row.text)},\n` +
        `    which is where ${row.of} comes from. Either the string was renamed — in ` +
        'which case rename it here too — or the surface was removed, in which case ' +
        'drop the row.'
      );
    }
  }

  return problems;
}

// ---------------------------------------------------------------------------
// The self-test — because a scanner that stopped seeing reports a clean
// bundle forever, and this one's whole claim is about what it does not find
// ---------------------------------------------------------------------------

function selfTest() {
  const green = CONTROLS.map((c) => c.control).join(' … ');

  const clean = scan(green);
  assert(clean.length === 0, `a bundle with every control and no sentinel is green: ${clean}`);

  // Every sentinel is seen, individually, and names its own surface.
  for (const s of SENTINELS) {
    const problems = scan(`${green} … ${s.sentinel} …`);
    assert(problems.length === 1, `one problem for ${s.sentinel}: ${problems}`);
    assert(problems[0].includes('OPTIONAL SURFACE LEAKED'), problems[0]);
    assert(problems[0].includes(s.surface), problems[0]);
    assert(problems[0].includes(s.sentinel), problems[0]);
  }

  // The scan FAILS CLOSED: a bundle with no controls in it is refused even
  // though every sentinel is absent from it, which is the shape an empty or
  // wrong bundle has.
  const empty = scan('');
  assert(empty.length === CONTROLS.length, `an empty bundle fails on every control: ${empty}`);
  for (const p of empty) assert(p.includes('POSITIVE CONTROL ABSENT'), p);

  // Each control is missed on its own, so a control that quietly stopped
  // being emitted cannot hide behind its siblings.
  for (const c of CONTROLS) {
    const others = CONTROLS.filter((o) => o !== c).map((o) => o.control).join(' … ');
    const problems = scan(others);
    assert(problems.length === 1, `one problem for a missing ${c.control}: ${problems}`);
    assert(problems[0].includes(c.control), problems[0]);
  }

  // The scan is not TOO EAGER, and this is the direction a guard is almost
  // never tested in. Each string below is shape-adjacent to a sentinel and
  // is legal in a consumer bundle; none may be mistaken for one.
  //
  //   - the tier's own name in PROSE reaches no bundle, but the codec's
  //     docstrings name it and a scanner reading source rather than the
  //     artefact would trip on them;
  //   - `hicassoBoundary` is the interpreted marker, one word away from
  //     the native one;
  //   - the other refusal families share `rf.error/hicasso-` with the
  //     tier's, and the tier's suffix is the whole of the difference.
  //
  // The forms rows add a near-miss of their own, and it is the reason
  // both of their sentinels are fully qualified: an application is
  // entirely free to have a `buffered-field` of its own and a `:drafts`
  // key of its own, and a bundle carrying either must stay green.
  const legal = [
    `${green} … re-frame.hicasso.native/declared-server …`,   // a docstring link
    `${green} … hicassoBody hicassoBoundary hicassoOwner …`,   // neighbouring markers
    `${green} … rf.error/hicasso-empty-vector rf.error/hicasso-bad-head …`,
    `${green} … "native" nativeEvent isComposing …`,           // React's own plumbing
    `${green} … re-frame.hicasso/revision re-frame.hicasso/clear …`,
    `${green} … drafts buffered-field myapp.forms/buffered-field :app/drafts …`,
    // rf2-ot28g. This line used to be here because motion was a sibling
    // module the scan did not cover. Motion is now rowed, and the string
    // stays for the OPPOSITE reason: `re-frame.hicasso.motion/presence` is
    // the door's SYMBOL, which `:advanced` renames and which reaches no
    // bundle even when the module leaks — the real sentinel is the
    // `displayName` `hicasso/presence`. A row on the symbol would have been
    // unfalsifiable, and this is what says so.
    `${green} … re-frame.hicasso.motion/presence …`,
    // The same distinction for overlay, plus the near-miss that matters
    // most: an application is entirely free to have a modal and a popover
    // of its own, and a bundle carrying either must stay green. This is
    // why all three new sentinels carry the `hicasso/` prefix the minting
    // namespaces stamp rather than the bare product name.
    `${green} … re-frame.hicasso.overlay/popover myapp.views/modal popoverTargetAction …`,
    // rf2-fn62g, and this one is the near-miss that matters most for the
    // server row: THE FRAMEWORK'S OWN SSR SURFACES ARE LEGAL HERE. A
    // hydrating client carries `re-frame.ssr`'s payload contract by
    // design — the bootstrap reads the payload script and the policy
    // keywords come with it — so a sentinel that fired on `re-frame.ssr`
    // would be red on every correctly hydrating application. The
    // module's own contribution is the `hicasso.` prefix and nothing
    // else, which is why the sentinel carries it. The door SYMBOL is
    // here for the motion row's reason: `:advanced` renames it and it
    // reaches no bundle even when the module leaks.
    `${green} … re-frame.hicasso.server/render re-frame.ssr.payload-policy ` +
      `rf.ssr.payload/whole-app-db rf.ssr/hydration-mismatch myapp.ssr/request …`,
  ];
  for (const bundle of legal) {
    const problems = scan(bundle);
    assert(problems.length === 0, `a legal bundle stays green: ${problems}`);
  }

  // The roster's premises hold against the real tree — a renamed sentinel
  // fails here rather than reporting a green absence.
  const premises = checkPremises();
  assert(premises.length === 0, `roster premises hold:\n${premises.join('\n')}`);

  // No sentinel may be a substring of another, or of a control: a hit has
  // to name one surface.
  const all = [...SENTINELS.map((s) => s.sentinel), ...CONTROLS.map((c) => c.control)];
  for (const a of all) {
    for (const b of all) {
      if (a !== b) assert(!a.includes(b), `${JSON.stringify(a)} contains ${JSON.stringify(b)}`);
    }
  }

  process.stdout.write(
    `hicasso bundle isolation: self-test OK ` +
    `(${SENTINELS.length} sentinels, ${CONTROLS.length} positive controls)\n`
  );
  return 0;
}

function assert(ok, message) {
  if (!ok) {
    process.stderr.write(`hicasso bundle isolation: SELF-TEST FAILED\n  ${message}\n`);
    process.exit(1);
  }
}

// ---------------------------------------------------------------------------

function fail(problems) {
  process.stderr.write('hicasso bundle isolation: FAIL\n');
  for (const p of problems) process.stderr.write(`  ${p}\n`);
  return 1;
}

function main(argv) {
  if (argv.includes('--self-test')) return selfTest();

  const premises = checkPremises();
  if (premises.length > 0) return fail(premises);

  if (!fs.existsSync(BUNDLE)) {
    return fail([
      `MISSING RELEASE BUNDLE: ${path.relative(IMPL_ROOT, BUNDLE)}\n` +
      '    Run `npm run build:hicasso-release`, which compiles it.',
    ]);
  }

  const problems = scan(fs.readFileSync(BUNDLE, 'utf8'));
  if (problems.length > 0) return fail(problems);

  process.stdout.write(
    `OK: no isolated surface reached the interpreted-only :advanced / goog.DEBUG=false ` +
    `bundle (${SENTINELS.length} sentinels absent, ${CONTROLS.length} positive controls present).\n`
  );
  return 0;
}

process.exit(main(process.argv.slice(2)));
