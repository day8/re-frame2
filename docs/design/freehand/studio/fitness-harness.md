# The Fitness Harness — requirements + baselines for the better-ui studio

Seat: TEST HARNESS (requirements side; built independently of any candidate design).
Written: 2026-07-21 23:14:04 AUSEST.
Scope fence honoured: no sibling studio docs read; no candidate designs proposed here.

Everything below is verified at source unless marked **[knowledge]** (from training,
honestly labelled) or **[speculation]**. Paths: `examples/…` = this repo's examples
tree; `re-com …` = a local re-com checkout, sibling of this repo (present, verified).
Repo rulings cited: rf2-efxb1h (overlay strategy), rf2-nzst23 (reset-key/buffered
inputs), rf2-y4mgw (realworld editor lifecycle history), spec/004-Views.md:448-461
(controlled-input synchrony law).

How to use this harness: Part 1 gives three hard cases — today's real Reagent
spelling, then a pass/fail requirements checklist any candidate substrate design
either meets or doesn't, then the verified traps that killed today's code. Part 2 is
the corpus census that arbitrates "let frequent shapes justify syntax". Part 3 is the
re-com problem inventory: the requirement stated substrate-neutrally, the two donor
answers as forcing functions, and the better-than-all-donors seeds. A design is not
judged on prose; it is judged cell-by-cell against these checklists.

---

## PART 1 — THE THREE HARD CASES

### CASE A — controlled input with validation

#### A.1 Today's Reagent spellings (the realest existing code)

**A.1.1 The app-db-owned validated input** — `examples/core/seven_guis/flight_booker/core.cljs`.
Raw text lives in app-db (deliberately raw: "someone mid-keystroke at `2026-05-` hasn't
typed a date yet", :37-40); validity is a `:<-` sub chain (:157-191); the view binds
value + dispatch and styles invalidity inline (:214-229):

```clojure
;; flight_booker/core.cljs:214-229 (verbatim)
[:input {:type      "text"
         :value     start-text
         :data-testid "flight-start"
         :style     (when-not start-valid? invalid-style)
         :on-change #(dispatch [:flight/set-start (.. % -target -value)])}]
...
[:button {:disabled (not book-enabled?)
          :data-testid "flight-book"
          :on-click #(dispatch [:flight/book])}
 "Book"]
```

The gate is a sub pyramid: `:flight/start-valid?` / `:flight/return-valid?` /
`:flight/dates-coherent?` AND-ed by `:flight/book-enabled?` (:183-191). No handler
ever recomputes button state.

**A.1.2 The form-with-field-errors idiom** — `examples/core/login/core.cljs:54-84` and
`examples/real-apps/realworld_resources/article_editor.cljs:470-533`. Draft map in a
slice, per-field touched/error bookkeeping in events (:323-333), error display gated on
touched-or-submit-attempted (sub `:editor/field-error`, :428-434), busy-disable off the
mutation-instance sub (:483-485, `busy? (:pending? save)`):

```clojure
;; article_editor.cljs:496-502 (verbatim, one field of four)
[:input.form-control.form-control-lg
 {:type "text" :name "title" :placeholder "Article Title" :data-testid "editor-title"
  :value (:title draft) :disabled busy?
  :on-blur #(dispatch [:editor/blur-field :title])
  :on-change #(dispatch [:editor/edit-field :title (.. % -target -value)])}]
(when title-err [:div.error-messages title-err])
```

**A.1.3 The typing-echo discipline** — `examples/core/seven_guis/temperature/core.cljs`.
The canonical value, the raw keystrokes, and which field is active are all state
(:45-49); each input's display sub echoes raw typing for the active field and the
derived value for the other (:136-156) — the documented reason "typing `1.` doesn't
reformat itself out from under you mid-keystroke" (:118-122). This is the corpus's
answer to reformat-under-the-caret without any local state.

**A.1.4 The reusable controlled-input helper** — `examples/core/todomvc/views.cljs:45-62`.
A plain `defn` taking `{:keys [draft on-change on-commit on-cancel autofocus?]}`:
Enter commits, Escape cancels, blur commits, and the file notes the unmount subtlety —
"cancelling unmounts the input, so the blur that follows finds nothing left to save"
(:37-39). Focus-on-mount is a `:ref` callback `.focus` (:60-62) — the corpus's ONE ref.

**A.1.5 The uncontrolled dodge** — `examples/core/seven_guis/cells/core.cljs:455-462`:
edit-in-place uses `:default-value raw` + `:auto-focus true` + commit on blur/Enter —
deliberately uncontrolled so a spreadsheet doesn't write app-db per keystroke. A second
edit-in-place idiom, distinct from todomvc's controlled draft; its cost is that app-db
cannot see mid-edit text.

**A.1.6 The component-library buffered variant** — `re-com src/re_com/input_text.cljs`
(the quarry the nzst23 ruling dissected; all verified in this checkout):

```clojure
;; input_text.cljs:78-79 — form-2 closure over twin ratoms
(let [external-model (reagent/atom (deref-or-value model))
      internal-model (reagent/atom (if (nil? @external-model) "" @external-model))]
...
;; :111-113 — render-phase double reset! when the external model diverges
(when (not= @external-model latest-ext-model)
  (reset! external-model latest-ext-model)
  (reset! internal-model latest-ext-model))
```

with the documented failure cascade: same-value rejection is invisible to the `not=`
check so the user's typed value stays displayed (:91-94); the workaround forces
`(reset! external-model @internal-model)` on every commit (:102-110, the `reset-fn` at
:105); that workaround creates a flicker under async `on-change` (:96-101); which
spawned the arity-sniffed 2-arity `on-change` done-fn (`(.-length ^js/Function
on-change)`, :104). `input-password`/`input-textarea` share this base (:213-226).
`re-com src/re_com/input_time.cljs:190-191` (`text-model`/`previous-model` ratoms) is
an independent second buffered consumer — the problem class recurs; it is not one
component's quirk.

#### A.2 Requirements — what CASE A imposes on any substrate (pass/fail per design)

| # | Requirement | Evidence anchor |
|---|---|---|
| R-A1 | **Same-tick echo.** A keystroke on a controlled element must flow event → state commit → re-render such that the DOM value never lags the accepted keystroke: no dropped/reordered characters under the substrate's scheduling. If the substrate batches/defers renders, it must state its input-path exception mechanically. | spec/004-Views.md:448-461 (the synchrony law: async lag "drops fast keystrokes, jumps the caret, or breaks IME"); Reagent pays this with a dedicated caret-heuristic module — `reagent/impl/input.cljs` in reagent-2.0.1.jar tracks `cljsDOMValue` and repositions the cursor ("Setting value moves the cursor position to the end which gives the user a jarring experience") |
| R-A2 | **Caret/selection preservation** when the rendered value differs from what was typed (reject, transform, reformat) — mid-string edits included. Remount-as-reset is disqualified (destroys focus/selection/IME). Under an IME the bar has two halves: a composing Enter must commit nothing, and a live composition must survive a model that disagrees with it — nothing writes the field while the composition runs, neither the substrate's own convergence nor the host framework's end-of-event restore, and the refused or normalised value lands once, whole, at `compositionend`. That second half binds the writer rather than the browser, because there is no gentle way to reassert into a composing field: any write aborts the exchange, no `compositionend` follows, and the IME composes its next attempt on top of what the abort left behind. A substrate meets this by not writing, not by writing carefully. | rf2-nzst23 ruling (effect-based reset disqualified — "commits a stale-value frame first, clobbering caret/IME"; key-remount disqualified). Both IME halves are measured against three real input implementations by `implementation/freehand/test/re_frame/bench/hicasso/ime_run.cjs` (rf2-o27h3, rf2-digtt): the commit fence holds on all three; of those three the survival half is met **only** by the Hicasso lean-React arm's converge, which declines mid-composition and holds React's own restore off with a composition shadow (HD-019's 2026-08-03 addendum in `docs/design/hicasso/decisions.md`). Plain React and the UIx port abort the exchange, and on a normalising field the aborted drafts accumulate into what commits — model `"SSHSH"` where the arm commits the `"SH"` that was typed. **Witness scope: Chromium.** `Input.imeSetComposition` is a CDP method and CDP is Chromium's protocol, so WebKit is unmeasured here rather than passing; misconduct observed there is a new bug, not a known limit of this bar. |
| R-A3 | **Same-value rejection visibility.** A parent reasserting the SAME model value to reject a draft must visibly restore the input. Value-equality is provably blind to this; the reset signal must be an explicit caller revision, never model-value comparison. | re-com input_text.cljs:91-94 (documented blindness); rf2-nzst23 NON-NEGOTIABLE ("(local model model) silently keeps a rejected draft") |
| R-A4 | **Draft vs canonical distinction expressible without reformat-jitter.** Raw typed text and parsed canonical value coexist; the field being edited echoes keystrokes verbatim; other readers see the derived value. | temperature/core.cljs:45-49, :136-156; flight_booker :37-40 |
| R-A5 | **Validation-display gating**: errors appear only after touch or submit-attempt, per field, per instance. | article_editor.cljs:428-434; login/core.cljs:59-60,73,80 |
| R-A6 | **Derived gates that cannot go stale**: submit-enabled as derived state readable by BOTH the view and the submit handler (no recompute-in-every-handler path). | flight_booker :183-191; article_editor can-submit flow :135-144 read at :343 and :527 |
| R-A7 | **Commit/cancel/blur key protocol** (Enter commit, Escape revert, blur commit) spellable without a component-local state machine — and the cancel-unmounts-then-blur-fires ordering must not resurrect the cancelled draft. | todomvc/views.cljs:45-62 (incl. the :37-39 unmount/blur note); re-com input_text.cljs:163-174 |
| R-A8 | **N instances, zero collisions, zero coordination**: the same reusable input used for new-entry and per-row edit on one page with distinct drafts. | todomvc :92-96 vs :103-112 (drafts `:edit`/`:new`); cells per-cell edit by id |
| R-A9 | **Async-transform race safety**: an asynchronous validation/normalisation of the typed value must not flicker between stale and new values and must not require caller arity tricks. | re-com input_text.cljs:96-110 (the flicker + done-fn stack a substrate must make impossible or explicit) |
| R-A10 | **Busy discipline from async status, not local flags**: inputs disable off the in-flight write's own state. | article_editor.cljs:483-485,499 |
| R-A11 | **Headless assertability**: draft → validity → gate assertable without a browser; the browser owed only caret/IME/focus proofs. | flight_booker subs are pure fns; the corpus's 364 `data-testid`s (census, Part 2) measure today's browser-test dependence |
| R-A12 | **Per-keystroke cost stated mechanically**: what runs per keystroke (which subs recompute, which views re-render, what the write amplification into state is) — for a 4-field form and for a 100-cell grid. | cells' deliberate uncontrolled dodge (:455-462) exists BECAUSE per-keystroke app-db writes were judged too hot; a candidate must either price this or dissolve it |

#### A.3 Known traps (all verified)

| Trap | Site | Mechanism |
|---|---|---|
| Twin-atom sync stack | input_text.cljs:78-79, 111-113 | form-2 closure atoms + render-phase double `reset!` — the exact move spec/004 bans for user code |
| Same-value blindness | input_text.cljs:91-94 | rejection by reasserting the prior value is invisible to `not=` |
| Commit-forces-reset flicker | input_text.cljs:96-110 | forced external-model reset misfires "changed"; async on-change flickers; escape hatch = arity-sniffed done-fn (:104) |
| Ephemeral state re-minted per render | input_text.cljs:90 | `showing? (reagent/atom false)` created INSIDE the render fn — the tooltip's open state dies on any re-render (typing a char while a status tooltip shows closes it). Even the library's own authors misplace ephemeral state |
| Substrate-level caret heuristics | reagent/impl/input.cljs (2.0.1 jar) | async batched rendering breaks React's same-tick caret preservation; Reagent ships shadow-DOM-aware active-element walking + selection restore as permanent machinery |
| External-store input hazard | spec/004-Views.md:448-461 and the prior verified UIx review | ANY design where the input value round-trips through an external store must name its synchronous door |
| Uncontrolled dodge trade | cells/core.cljs:455-462 | `:default-value` avoids per-keystroke writes but state cannot see mid-edit text; seeding only applies at mount |

---

### CASE B — popup/dropdown: focus, measurement, outside-interaction, top-layer

#### B.1 Today's spelling

**The examples corpus contains ZERO floating overlays.** Verified: no portals, no
`position:fixed`, no z-index anywhere under `examples/{core,capabilities,patterns,real-apps}`.
The two "dialogs" are in-flow conditional renders off app-db state:
`examples/core/seven_guis/circle_drawer/core.cljs:284-294` (the resize dialog — a
`(when dialog …)` div; open/close are events `:drawer/open-dialog`/`:drawer/close-dialog`)
and the pending-navigation confirm at
`examples/real-apps/realworld_resources/core.cljs:129-133` (`(when-let [pending
@(subscribe [:rf/pending-navigation])] …)`). So the real anchored-overlay baseline is
re-com; the corpus proves only that app-level modals reduce to state + conditional render.

**re-com dropdown (v2)** — `re-com src/re_com/dropdown.cljs`, the openable
anchor-plus-floating-body base:

- Open state: `:model` prop with a local-ratom default — `:or {model (reagent/atom nil)}`
  (:323); plus `focused?`, `anchor-ref`, `body-ref`, `anchor-position` ratoms (:325) and
  a `transitionable` animation-state ratom (:327-328).
- Outside-interaction: a **document-level** click listener installed on open and removed
  on close — `(.addEventListener js/document "click" on-document-click)` at :350 and
  :354, removed at :358 and :362 — with containment math `click-outside?` (:317-319)
  checking both anchor and body (:379-384).
- Placement: `body-wrapper` (:279-315) is a `create-class` that measures
  `getBoundingClientRect` and — the load-bearing wart — runs a
  **`requestAnimationFrame` loop every frame while open** to track the anchor
  (:287-296: `start-loop!` re-arms itself unconditionally), rendering the body
  `position:fixed` at measured viewport coordinates (:302-315) with `z-index 30`
  (dropdown/theme.cljs:17-24; anchor gets z-index 20 when open per the rf2-efxb1h
  verified-facts block).
- Transitions: `:entering`/`:in`/`:exiting`/`:out` driven by 100 ms `js/setTimeout`
  (:373-378).
- Keyboard: **none** in this base component (no Escape, no arrows — verified by grep).
  The richer `single_dropdown.cljs` hand-rolls Enter/Escape/arrows/PageUp/PageDown
  (:355-379) over ELEVEN+ local ratoms (:201-225).

**re-com popover** — `re-com src/re_com/popover.cljs`:
two-pass measured geometry — body parked at `{:top "-10000px" :left "-10000px"}` until
measured (:335-337), `margin-right "-2000px"` wrap-prevention hack (:345-347),
clip-detection + reposition in `component-did-update` reaching the anchor via a
`.-parentNode .-parentNode .-parentNode` walk (:301-309), and the `no-clip?` escape
that switches to `position:fixed` + measured coordinates — defaulting TRUE for tooltip
(:685), datepicker-dropdown (datepicker.cljs:679), daterange (daterange.cljs:513) —
with re-com's own arg-doc conceding it is "slightly inferior because the popover can't
track the anchor if it is repositioned" (datepicker.cljs:664). Anchor z-index 4
(popover/theme.cljs:68-70).

**re-com modal** — `re-com src/re_com/modal_panel.cljs`: inline `position:fixed`
full-viewport wrapper `z-index 1020`, backdrop z-index 1, child z-index 2
(modal_panel/theme.cljs:7-27); backdrop-click dismiss with
preventDefault/stopPropagation (modal_panel.cljs, `::mp/backdrop` `:on-click` handler);
**no focus trap, no inert, no Esc, no focus return** (verified: no key handling in the
file; rf2-efxb1h confirms none exists anywhere in re-com).

#### B.2 Requirements — what CASE B imposes on any substrate

| # | Requirement | Evidence anchor |
|---|---|---|
| R-B1 | **Measure-then-place without a visible wrong-position frame.** If placement needs DOM measurement, the design must name the phase where measurement happens and prove no user-visible paint at a wrong position (today's baseline: park at -10000px + opacity 0 until measured — popover.cljs:335-337,350-352). | popover.cljs:335-352 |
| R-B2 | **Escape ancestor clipping and stacking without emulation warts.** The overlay body must escape `overflow:hidden` ancestors and stacking contexts; `position:fixed` emulation is defeated by any ancestor transform/filter/will-change (containing block) and demands z-index ladders (4/20/30/1020 verified across the four families). The harness accepts only mechanisms immune to ancestor transforms. | rf2-efxb1h RATIONALE + verified-facts; popover/theme.cljs:68-70, dropdown/theme.cljs:24, modal_panel/theme.cljs:7-27 |
| R-B3 | **Outside-interaction dismiss with provably bounded listener lifetime.** Dismiss on outside click; any global listener's lifetime must be ≤ the overlay's mounted lifetime BY CONSTRUCTION. Today's baseline fails: the document listener is added in `open!` and removed only in `close!` (:350-362), and the outer dropdown is a form-2 with **no unmount hook** (verified: zero lifecycle between :321-456) — unmount-while-open leaks the `js/document` click listener permanently. | dropdown.cljs:350-362 + absence of will-unmount |
| R-B4 | **Focus contract per overlay class**: modal = focus entry, containment, background inert, Esc cancel, focus return; anchored non-modal = anchor keeps focus, Esc closes and returns focus, tab-out closes-or-contains. re-com's parity bar is ZERO on all six (efxb1h: "NO focus trap, NO inert, NO Esc handling, NO focus return anywhere in re-com") — the harness bar is the native `<dialog>`/popover behaviour set, which the ruling adopted as the repo's floor. | rf2-efxb1h ruling + IMPLEMENTATION GUIDANCE |
| R-B5 | **Keyboard navigation for list bodies**: Enter open, Escape cancel-restore, arrows/PageUp/PageDown selection movement — the single_dropdown parity set. | single_dropdown.cljs:355-379, :78-79 (`:enter-drop?`, `:cancelable?` args) |
| R-B6 | **Anchor tracking honesty.** If the overlay tracks its anchor across scroll/resize/reflow, the mechanism must be stated and priced (today: an unconditional rAF loop every frame while open, dropdown.cljs:287-296). "Positions once on open" is an acceptable contract if declared; a silent every-frame loop is not. | dropdown.cljs:287-296; datepicker.cljs:664 (the can't-track concession) |
| R-B7 | **Open/close state has ONE owner, per instance, collision-free.** Today's dual contract — caller-supplied `:model` OR internal default ratom (:323-324) — is the two-ways-to-do-one-thing trap. Under re-frame2's one-state-system pin the open flag must be scoped per instance without global-key collision across N dropdowns. | dropdown.cljs:323-328 |
| R-B8 | **Transition states without orphaned timers**: entering/in/exiting/out expressible; unmount mid-transition cancels cleanly (today's 100 ms setTimeouts are uncancelled, :373-378). | dropdown.cljs:327,:373-378 |
| R-B9 | **Nesting determinism**: popover-in-dropdown-in-modal stacks and dismisses LIFO; outside-click on an inner overlay must not dismiss the outer. (Native top layer gives LIFO by construction — efxb1h.) | rf2-efxb1h |
| R-B10 | **Host-boundary hygiene**: closed overlay = zero DOM cost; render path free of `js/document`/`js/window` so JVM/SSR renders the closed (or in-flow open) form; measurement/listeners live only in host-fenced code. | dropdown handlers touch js/document only inside letfns (:350-362); body-wrapper hooks touch window — the shape a design must fence |
| R-B11 | **Frame/context continuity**: the overlay body resolves the same frame/subscriptions as its anchor. In-tree structure gets this free; any relocation mechanism must prove it (the efxb1h ruling's stated reason to prefer top-layer-in-place over portals: "no boundary to propagate frame context across, SSR just emits the element, hydration relocates nothing"). | rf2-efxb1h |
| R-B12 | **Total teardown**: unmount-while-open cleans listeners, rAF loops, timers — StrictMode/remount-safe. Baseline fails (R-B3; the rAF loop IS cleaned by body-wrapper's will-unmount :300-301, the document listener is not). | dropdown.cljs:294-301 vs :350-362 |
| R-B13 | **Honest test split**: open/close logic, dismissal policy, and placement INPUTS assertable headlessly as data; actual geometry/focus owed to mounted tests. | corpus precedent: circle_drawer dialog tested via app-db state; geometry untestable headlessly today |

#### B.3 Known traps (verified)

- The **document-listener leak** on unmount-while-open (R-B3) — today's real behaviour.
- The **rAF-every-frame** anchor tracker (R-B6) — mechanically expensive and invisible.
- The **parentNode×3 anchor walk** (popover.cljs:304) — placement coupled to private DOM
  structure; any wrapper insertion breaks it.
- The **-10000px park + -2000px margin hack** (popover.cljs:337,345-347) — measurement
  workarounds that leak into layout.
- The **z-index ladder** (4/20/30/1020) — cross-family coordination by folklore.
- The **fixed-position default that can't track** (no-clip? TRUE for tooltip :685,
  datepicker :679) — the escape hatch became the default and shipped its warts.
- **Modal a11y absent** (no trap/inert/Esc/return) — the parity bar is that low; the
  efxb1h ruling's native `<dialog>`/popover answer EXCEEDS it for free.

---

### CASE C — a composed stateful control with asynchronous re-frame data

#### C.1 Today's spelling

**App-level baseline (canonical): the RealWorld editor** —
`examples/real-apps/realworld_resources/article_editor.cljs` (+ routing.cljs). The
busiest honest page in the repo; its pieces:

- **Slice**: `{:slug :draft :baseline :errors :touched :submit-attempted?}` (:83-94);
  dirty = `(not= draft baseline)` (:444-446).
- **Read lifecycle owned by the ROUTE**: `:realworld.editor/edit` declares the article
  read in `:resources`; the runtime releases `[:route :realworld.editor/edit nav-token]`
  on every leave. "There is deliberately NO `:editor/release-article` event … route
  leave is the causal end event" (:316-321).
- **Seed-on-load as a correlated reply**: the `:on-match` fires an OWNERLESS ensure that
  JOINS the route-owned read with `:reply-to [:editor/article-loaded slug]` (:276-280);
  the continuation seeds ONLY while the current route still targets that slug
  (:304-314) — because slug A and slug B are distinct cache entries with independent
  generations and cancellation is best-effort, so a late A settle can arrive after
  navigation (:282-303, the docstring states the race precisely).
- **Derived gate as a flow**: `:editor/can-submit?` materialised at
  `[:editor :can-submit?]` (:135-144), read as plain data by the submit handler (:343)
  and via a sub by the button (:436-442, :527).
- **Write as a mutation instance**: save/delete share instance `:editor/save`
  (:108-110); the form watches `[:rf/mutation {:instance save-instance}]` (:483);
  `busy? (:pending? save)` disables fields (:485,499); completion is
  `:reply-to [:editor/replied]` branching save-vs-delete on the reply (:374-405);
  `:invalidates` re-fetches lists/feeds declaratively (:180-187).
- **Navigation guard**: route `:can-leave [:editor/can-leave?]` off dirty? (:448-454);
  the app shell renders the confirm off `:rf/pending-navigation` (core.cljs:129-133).

**Component-level baseline: re-com's async controls.**
`single_dropdown.cljs`: `:choices` may be "a callback `(opts, done, fail)`" (:57);
loading state in a local ratom `choices-state (reagent/atom {:loading? choices-fn?})`
(:206) among 11+ instance ratoms (:201-225). `typeahead.cljs`: a core.async
channel + `debounce` over `c-input` → `c-search` (:26-45) with a hand-rolled
suggestions state machine keyed off `change-on-blur?`/`rigid?`/
`immediate-model-update?` (:55-67). Per-row write status in the corpus:
`@(subscribe [:rf/mutation {:instance [:favorite slug]}])` with optimistic tags and an
`:optimistic?` styling cue (`realworld_resources/views.cljs:249-271`); linearlite keys
instances `[:edit id]`/`[:status id]`/`[:create id]` (`linearlite/core.cljs:504-506`).

#### C.2 Requirements — what CASE C imposes on any substrate

| # | Requirement | Evidence anchor |
|---|---|---|
| R-C1 | **Late async arrival must not clobber user input.** A settle that seeds form state must merge against fields the user already touched. The baseline FAILED this for the same-slug case — entering edit A and typing before A settles, the accepted same-slug reply replaced the entire slice (`(assoc db :editor (editor-slice …))`, no `:touched` consultation), discarding keystrokes. The rf2-y4mgw audit named it ("MATERIAL P2 RUNTIME OMISSION — SAME-SLUG INITIAL LOAD CAN STILL CLOBBER TYPING") and rf2-czvc closed it: both editors now seed LEAFWISE through a hand-written `seed-slice`, and both suites cover it. **What the baseline cost to get there is the requirement**: the guarantee is 30 lines of hand-rolled merge per app, invisible to tooling, and it was got wrong twice. A candidate design must make typed-field survival across settle either automatic or one obvious spelling. | article_editor.cljs `seed-slice` + `:editor/article-loaded`; bd rf2-y4mgw, rf2-czvc |
| R-C2 | **Reply correlation**: every async completion names WHICH request it answers (the slug rides in the reply target, :280); stale/cross-key replies are droppable by the receiver. The generation gate suppresses same-entry supersession only — cross-entry lateness is the app's to guard (:282-303). | article_editor.cljs:246-314; rf2-y4mgw PR #6628 note |
| R-C3 | **Lifetime has a causal owner that covers EVERY exit path.** Whoever mints a data lifetime must have an end event for every leave (ordinary navigation included — the exact gap that stranded the owner in the native editor, per y4mgw: the Reagent Form-3 unmount was removed and no route-owned release replaced it). "Naming hypothetical future end events is not completion." | bd rf2-y4mgw description; article_editor.cljs:316-321 |
| R-C4 | **Cancellation is best-effort; the design must survive its failure.** A cancelled request's settle may still arrive and must be inert (guard, not assumption). | article_editor.cljs:282-303 |
| R-C5 | **Per-instance async status, collision-free at N instances**: loading/error/optimistic readable per control instance (mutation instances as data — `[:favorite slug]`, `[:edit id]`); a list of 50 rows = 50 independent statuses with no registry ceremony. | views.cljs:249; linearlite:504-506; 11 `[:rf/mutation` reads + 14 `[:rf/resource` reads corpus-wide |
| R-C6 | **Optimistic update + rollback expressible**: the UI shows the intended value pre-settle, marks it unconfirmed, and rolls back on failure — without disabling the control. | views.cljs:249-271 (the deliberate "don't disable on :pending?" comment) |
| R-C7 | **Debounced remote search has a home**: typeahead-class controls need debounce + in-flight supersession; the design must say where that lives (event layer / fx / control) — the baseline hides it in core.async closures inside the component, invisible to tooling. | typeahead.cljs:26-45 |
| R-C8 | **Composed identity**: a control assembled from input + list + status shares ONE instance identity across its parts; two instances on one page never share state. (The re-com baseline: 11 coordinated ratoms per instance, single_dropdown.cljs:201-225.) | single_dropdown.cljs:201-225 |
| R-C9 | **Dirty-state navigation guard integration**: leaving with unsaved edits blocks, the confirm UI is ordinary state-driven view code, and a just-saved draft leaves freely (baseline re-seeds to clean on save reply, :392-396). | article_editor.cljs:448-454, :374-405; core.cljs:129-133 |
| R-C10 | **The whole loop is headlessly provable**: edit-field → can-submit flips; settle reply → seed (or preserved-typing merge); late cross-key reply → dropped. Today these ARE provable because every step is events/subs — the regression suite drives "the REAL reply path" (y4mgw PR #6628 note: RED before guard, GREEN after). A candidate must not regress below this bar while adding view-layer machinery. | rf2-y4mgw notes; article_editor purity claims (:457-468) |

#### C.3 Known traps (verified)

- **Slug-correlated replies were retrofitted three times** (y4mgw: PR #6569 reopen →
  #6628 fix; then rf2-czvc's second pass, for the HTTP twin): uncorrelated
  `:reply-to [:editor/article-loaded]` accepted ANY successful article and re-slugged
  the editor — and the managed-HTTP twin's `:on-success [:editor/loaded]` was still
  doing exactly that after its leafwise seed landed. Correlation must be the paved
  path, not a lesson.
- **The same-slug typing clobber** went uncaught for two rounds of this exact bug
  (R-C1): the cross-slug regression settles B before anyone edits B, so it could
  never reach the same-slug half, and no other suite looked. It is fixed and
  covered now (rf2-czvc), but the *shape* is the trap worth keeping — a
  correlation guard reads like the whole answer to "late reply clobbers the
  form", and it is only half of it. **The converse bit too** (rf2-czvc, audit of
  PR #8055): the leafwise merge reads as the whole answer in ITS turn, and it is
  untouched-field logic — a reply for a different article finds every field
  untouched relative to the slice it lands on and takes the lot. Two gates, two
  questions: does this reply belong to this screen, and which fields may it write.
- **Vacuous teardown assertions**: the browser fixture asserted `(is true)` instead of
  inspecting `:active-owners` (y4mgw) — the harness demands owner-state assertions on a
  real leave path.
- **Lifecycle-hook deletion without re-homing**: removing the Form-3 unmount dispatch
  without a route-owned release stranded the owner until frame teardown (y4mgw).
- **Component-buried async machinery**: typeahead's chan/debounce and single_dropdown's
  done/fail callbacks are invisible to traces/tooling and untestable headlessly —
  the component-library shape of the same disease the editor cured with events.

---

## PART 2 — THE EXAMPLES-CORPUS CENSUS (M-corpus-mining)

**Method.** Scope per the brief: `examples/core` (incl. `seven_guis`),
`examples/capabilities`, `examples/patterns`, `examples/real-apps` — 85 `.cljs`/`.cljc`
files. The `ui_*.cljc/cljs` files under `realworld_resources` are compiled-substrate
(re-frame.ui) renditions and are excluded from Reagent-shape counts (noted where
relevant); `examples/substrates/` (helix/uix/reagent-slim ports) and
`examples/ui/` are out of census scope. Counts are `grep -c` over the scope (method
noted where a pattern is approximate). This corpus is idiomatic-by-decree (testbeds
must be idiomatic re-frame2), so it measures the INTENDED grain of the framework —
exactly what "let frequent shapes justify syntax" should judge against.

### The frequency table

| # | Shape | Count | Exemplars (file:line) |
|---|---|---|---|
| 1 | Subscription reads `@(subscribe [...])` | **231** (core 55 / capabilities 31 / patterns 46 / real-apps 99) | flight_booker/core.cljs:198-204 (7 reads in one let); websocket/views.cljs:44-50 (7 boolean machine tags); article_editor.cljs:477-483 |
| 1a | — of which FRAMEWORK subs | **~63 (27%)**: `:rf.machine/has-tag?` 25, `[:rf/resource` 14, `[:rf/mutation` 11, `:rf.route/id`+`params` 10, `:rf/pending-navigation` 2, `:rf.resource/infinite-state` 1 | login/core.cljs:57; infinite_feed/core.cljs:267; views.cljs:249 |
| 1b | — of which parameterised (args after the id) | **52 (23%)** | todomvc/views.cljs:68 (`[:todo.ui/editing? id]`); article_editor.cljs:480 (`[:editor/field-error :title]`) |
| 2 | View definitions `reg-view` | **~140** across 38 files (largest: nine_states 13, realworld views.cljs 8, linearlite 8) | todomvc/views.cljs:7-16 (the house rule: state-touching = reg-view; helpers = plain defn — exactly 2 plain helpers in that file) |
| 3 | Event-handler closures `#(dispatch …)` | **147** total → **93 (63%) pure intent** (no `%` — the closure exists only to delay a constant vector) vs **54 (37%) event-derived** | pure: todomvc/views.cljs:82,86; drawer :266-268. derived: flight_booker :218 |
| 3a | — event-value extraction `(.. % -target -value)` | **58** (the event-derived set is ~96% this ONE extraction; `-target -checked` appears ONCE) | login/core.cljs:72; article_editor.cljs:501 |
| 3b | — `(fn [e] …)` handlers with `preventDefault` | **36** sites, dominated by form `:on-submit` (21) + Enter/Escape keydown | article_editor.cljs:494; todomvc/views.cljs:49-50 |
| 3c | — keyboard-condition handlers (`.-key` case/when) | **3** | todomvc/views.cljs:48; cells:461 |
| 3d | — `stopPropagation` | **0** | — |
| 4 | Controlled form controls | `[:input` **63**, `[:textarea` **10**, `[:select` **4** (= 77); `:checked` 4; uncontrolled `:default-value` **1** | flight_booker :214-225; todomvc :78-82; cells :455-462 (the one uncontrolled) |
| 5 | Local-looking interaction state in views | **0** ratoms, **0** useState-alikes. ALL interaction state (drafts, editing flags, dialog open, selection, undo stacks) is app-db + events. The only atoms in scope are non-view infrastructure: a module id-counter (linearlite:167), mock-server state (websocket/messages.cljs:43,74,132) | login/core.cljs:46 comment: "no `reagent.core/atom`, no local state hiding anywhere"; circle_drawer dialog slice :57 |
| 6 | Lifecycle reach | **1** `with-let` finally-dispatch (unmount → `[:work/flow [:cancel]]`) | long_running_work/views.cljs:143-159 |
| 7 | Refs / DOM access | **1** `:ref` (focus-on-mount), **1** `:auto-focus` attr, **1** `getBoundingClientRect` (event-target coords, not a ref) | todomvc/views.cljs:60-62; cells:456; circle_drawer:272-274 |
| 8 | Lists + keys | `(for [` **48**; `^{:key …}` **35**; per-row subscribe-by-id and per-row mutation instances are the row idiom | todomvc :122-127; cells :465-474 (nested keyed grid); views.cljs:249 |
| 9 | Portals / overlays / fixed / z-index | **0 / 0 / 0 / 0**; 2 in-flow app-db dialogs | circle_drawer:284-294; realworld core.cljs:129-133 |
| 10 | Third-party components | **0** foreign React components. The one JS library (markdown-it via nextjournal/markdown) enters as HICCUP DATA, never a component or `dangerouslySetInnerHTML` | realworld_shared/markdown.cljs:1-30 |
| 11 | Routing links | `route-link` **106** — the second-most-frequent interactive element after buttons | todomvc/views.cljs:135-137; views.cljs:252-256 |
| 12 | Status-driven attributes | `:disabled` **100**; dynamic `:class` (cond->/str/when) **19**; dynamic `:style` **2** | flight_booker :227; views.cljs:268-270; editor :499 |
| 13 | Async-status branching in views | machine-tag booleans (25), resource-state maps (14), mutation-instance maps (11); nine_states maps tag→render as a DATA table | nine_states/core.cljs:630-631; infinite_feed:267-293 (three error channels) |
| 14 | Test hooks `:data-testid` | **364** — the single most frequent view attribute in the corpus | everywhere; e.g. flight_booker :216 |
| 15 | SSR/hydration touchpoints | 4 entrypoints (`ssr/core.cljc`, `ssr_streaming`, `resources_ssr`, `realworld_http/ssr.cljc`): views shared via cljc + `^{:rf/id}` stable identity + `rf/render-to-string`; client `#?(:cljs …)` mount split | ssr/core.cljc:233,250,308,399-426 |
| 16 | Mount/boot ceremony | per-example `defonce react-root` + `frame-root {:id … :initial-events […]}` + `^:dev/after-load mount!` (~24 `with-frame` sites for ns-load registration) | flight_booker :240-270 — the canonical block, ~30 lines/app |
| 17 | Measurement/scroll observation | **0** IntersectionObserver/ResizeObserver; infinite scroll is a load-more BUTTON dispatching an event | infinite_feed/core.cljs:21-24,248-293 |

### Census findings that bind the studio

1. **The one-state-system pin is already load-tested.** 85 files, ~140 views, and not
   one view-local reactive cell — drafts, dialogs, undo stacks, selection all flow
   through events/subs, including the two hardest classic cases (edit-in-place, modal
   dialog). The pin is not aspirational; it is the corpus's demonstrated grain. What it
   COSTS today is visible too: per-instance sub/event id ceremony (`:todo.ui/draft
   :edit` vs `:new`) and per-keystroke event traffic (which cells dodged with an
   uncontrolled input).
2. **Handlers are one placeholder away from full data.** 63% of handler closures carry
   zero event data; of the rest, 96% extract exactly `event.target.value`. A value
   placeholder + a prevent-default marker + a key-condition form would convert ~97% of
   all 183 handler sites (147 closures + 36 fn-handlers) to pure data. The residue
   (coords math in circle_drawer :272-279, file inputs: none in corpus) is escape-hatch
   sized.
3. **Framework state IS view vocabulary.** 27% of all sub reads are framework-owned
   surfaces (machine tags, resource/mutation state, route identity). A substrate that
   only optimises app-db reads misses a quarter of the real read traffic.
4. **Testability is bought retail today.** 364 data-testids — one per ~2 interactive
   elements — exist to let browser tests FIND things. A tree that carries event vectors
   and sub ids as data makes intent assertions equality checks and would let most of
   those hooks (and the Playwright layer over them) shrink to the mounted-proof core.
5. **Overlay machinery is a component-library problem, not an app problem.** Zero
   floating overlays in 85 files of idiomatic apps; both dialogs are conditional
   renders. The harness therefore weights Case B toward the LIBRARY author (Part 3),
   and weights app-tier syntax toward shapes 1-4.

### The FIVE shapes a tier-1 design MUST make beautiful

1. **The sub-read view body**: N plain-value reads → let → hiccup (231 reads; the
   flight-booker 7-read let is the archetype). Zero ceremony per read; conditional
   reads legal; framework subs (machine tags, mutation/resource state, route id) read
   identically to app subs.
2. **Intent dispatch from attributes**: the pure event vector on click/submit (93
   sites) AND the value-carrying edit event (58 sites) — the latter must be as data-able
   as the former (placeholder or equivalent), with prevent-default/key-condition covered
   (36 + 3 sites).
3. **The controlled draft input**: value-from-state + edit-event + touched/error
   gating + busy-disable (77 controls, 64 on-change, 100 disabled) — including the
   typing-echo and same-value-rejection disciplines (R-A3/R-A4) without twin-atom
   machinery.
4. **The keyed row**: `for` + key + per-row identity reaching subs/instances
   (`[:todo.ui/editing? id]`, `[:favorite slug]`) — 48 fors / 35 keys / per-row async
   status — with stable identity semantics a design states, not inherits.
5. **The status-driven attribute + route link**: disabled/class off async state maps
   and `route-link`-as-data (106 links; navigation is already an event system — link
   rendering must stay declarative, href-real, modifier-click-correct).

### Shapes rare enough to deserve ONLY an escape hatch

- Focus imperatives (1 ref + 1 autofocus in 85 files); DOM measurement (1, and it used
  the event object); scroll/size observers (0); portals (0); foreign React components
  (0); stopPropagation (0); lifecycle reach (1 unmount-dispatch — and the editor shows
  the causal re-homing that deletes even that). A design that mints tier-1 syntax for
  any of these has read the corpus wrong; a design with NO honest hatch for them fails
  the library tier instead (Part 3).

---

## PART 3 — THE RE-COM PROBLEM INVENTORY

Consolidated per the brief's 10b list; verified against the re-com checkout. Facade
scale, verified: **72 bare aliases** (`core.cljs`, `grep -c '^(def [a-z-]* *[a-z-]*/'`);
support machinery: validate.cljs 561 lines + 69 `validate-args-macro` call sites,
part.cljs 182, box.cljs 549, debug.cljs 375 (+ 144 manual `(at)` source-coordinate
sites, 100 `handler-fn` wraps, 235 `deref-or-value` sites — every prop doc reads
"boolean | r/atom"); 14 files hold form-2 local-ratom state; 6 use `create-class`.

Donor sketches: **Replicant answers are [knowledge]** (no replicant findings doc in
this checkout; claims kept to its documented core: pure hiccup from state, handlers as
data to one global dispatcher, lifecycle/aliases as data, top-down root re-render,
library-managed DOM state). **UIx answers** draw on the verified prior review
(the prior verified UIx review) plus [knowledge] for API shapes. "Seeds" are
better-than-all-donors leads — repo precedents first, then what native event/sub
understanding could unlock (marked [seed] = design-space lead, not a requirement).

| # | Problem class | The requirement, substrate-neutral | Replicant-style answer | UIx-style answer | Better-than-all-donors seeds |
|---|---|---|---|---|---|
| P1 | **Reusable stateful controls** (dropdown, datepicker, typeahead… 14 files of form-2 ratom state; single_dropdown's 11 atoms :201-225) | A library control owns interaction state per instance with zero caller ceremony and zero cross-instance collision; state is inspectable and survives the caller's re-render. | State lives OUTSIDE; control = pure fn of (state-slice, handlers); caller threads a path/prefix for the instance. Honest but pushes bookkeeping to every caller. [knowledge] | Component owns it via hooks (`use-state`); free ergonomics, but state is invisible to tooling/tests and violates the one-state-system pin. | The corpus already scopes per-instance state through id-bearing subs/events (`[:todo.ui/draft :edit]`, `[:favorite slug]`) with zero collisions in 85 files. [seed] make instance-scoped event/sub identity a NAMED, mechanical convention (the control mints ids from one instance key) so the caller pays one key, not a path-threading protocol — and every instance's state shows up in app-db/traces/time-travel free. |
| P2 | **Controlled values + change events** | Value-in/intent-out for every control; change carries the value; no dual "value or atom" contract (235 `deref-or-value` sites is the tax of not deciding). | Fully controlled, top-down; handler-as-data receives the DOM event; new state re-renders root. [knowledge] | React-standard controlled props + `on-change` fn; same-tick `useState` path preserves caret natively. | Event-vector handlers + a value placeholder (census: 96% of event-derived sites are ONE extraction) make change-intent DATA — assertable by equality, serialisable, traceable. Repo precedent: the compiled substrate already classifies literal vector handlers and a synchrony door at controlled sites (spec/004:448-461, `:rf.ui.compile/controlled-input-async-handler` :322). No donor has intent-as-data at the change site. |
| P3 | **Validation + status display** (`:status`/`:status-icon?`/`:status-tooltip`, input_text args :44-46) | Controls display external validity states (warning/error/validating) without owning validation logic; error visibility gated by touch/attempt. | Status is just data in props; rendering branches. Fine, but each library reinvents the gating logic. [knowledge] | Same, via props; or a form library. | The corpus's touched/attempted gating is a proven 10-line pattern (article_editor :323-334, :428-434). [seed] a substrate-blessed *form-slice shape* (draft/baseline/touched/errors) + derived-gate convention makes validation state machine-checkable (schemas registered per slice — `reg-app-schema` already exists, flight_booker :52-53) and reusable across controls — donors have nothing at this layer. |
| P4 | **Ephemeral interaction state** (hover `over?`, tooltip `showing?`, focused `focused?` — and the :90 bug where `showing?` is re-minted every render) | Hover/focus-visible styling costs ZERO state; genuinely stateful ephemera (open dropdown, active suggestion) are per-instance, inspectable, and survive unrelated re-renders. | CSS for hover; real ephemera go to the store like everything else. [knowledge] | `use-state`; cheap but invisible; the :90 class of bug becomes impossible only by hook discipline. | CSS-first is the floor (`:hover`/`:focus-visible`; the corpus has zero hover state). For true ephemera, P1's instance-scoped events/subs + [seed] a declared *ephemeral tier* in app-db (excluded from persistence/replay noise by convention, visible to Xray) — the input_text:90 bug is impossible when state cannot live inside a render closure at all. |
| P5 | **Layout primitives** (box family: h-box/v-box/gap/box — 549-line box.cljs; every re-com screen composes them) | Compose rows/columns/gaps/scrolling declaratively without a parallel layout language. | Plain hiccup + CSS. [knowledge] | Plain JSX + CSS (or any CSS-in-JS). | The census verdict: 85 files, ZERO box-family usage — plain hiccup + flex/grid/gap classes suffice in 2026 CSS. Requirement collapses to "don't ship a layout DSL"; a style-map helper vocabulary as pure data functions is the most any design should mint. The box family is a 2015 flexbox-compat artefact, not a problem class to inherit. |
| P6 | **Overlays with clipping/stacking/focus** (popover/tooltip/dropdown/modal) | The R-B checklist, wholesale (measure-before-paint, transform-immune escape, bounded listeners, focus contract, LIFO nesting, SSR-safe). | Body renders in-tree from state; no portal machinery in core; positioning is userland. [knowledge — and top-down purity gives no answer to top-layer/focus at all] | `react-dom/createPortal` + a positioning lib (floating-ui) + hand-rolled or library focus traps. [knowledge] | The ruled repo answer BEATS both donors: native `<dialog>.showModal()` + the `popover` attribute — in-tree structure, browser-hoisted top layer, transform-immune, backdrop/inert/Esc/focus-return FREE, light-dismiss FREE, LIFO stack native; anchored placement = measured geometry now, CSS anchor positioning later (rf2-efxb1h, incl. its graduation triggers for the one genuinely portal-only capability: foreign DOM nodes). [seed] open/close as instance-scoped state + `popovertoggle` events mapped to event vectors makes dismissal DATA — no document listeners for a design to leak. |
| P7 | **Buffered / commit-draft inputs** (external-vs-internal model, same-value rejection) | R-A1..A4, A9: draft distinct from committed; commit on blur/Enter; Escape revert; same-value rejection VISIBLE; async normalisation without flicker; caret/IME intact. | Top-down controlled only; a reassert-same-value rejection produces an unchanged tree — [speculation] a pure vdom-diffing renderer skips the DOM write and inherits input_text's :91-94 blindness unless it force-syncs value every render. | The React adjust-during-render idiom, hand-maintained (prev-key slot + guarded same-render set), or key-remount (disqualified: kills caret/IME). | The ruled repo answer beats both: a compiler/substrate-GUARANTEED adjust-during-render reset keyed by an EXPLICIT CALLER REVISION — never value equality (rf2-nzst23: zero stale paints, loop-impossible, retry-correct; `initial` re-evaluates at reset). The harness adopts nzst23's pin list (caret/IME through reject-restore, multi-slot independence, HMR, JVM "reset never fires" row, misuse lint for value-as-key) as THE acceptance suite for this class. |
| P8 | **Focus + measurement** | Focus as one-shot imperative intent (mount-focus, restore-on-close) and measurement (anchor rects) with declared timing (before paint), fenced from JVM/SSR. | Lifecycle-as-data hooks (`:replicant/on-mount` receiving the node) [knowledge]; measurement userland. | Refs + `use-layout-effect`; correct timing, imperative, host-locked. | The corpus needed exactly ONE focus imperative in 85 files; native `<dialog>`/popover carry initial-focus + focus-return themselves (efxb1h). [seed] focus-request as an EFFECT (data: target by stable id/ref-token, cause-carrying, visible in traces, no-op on JVM) — an fx no donor represents as data; measurement stays a host-fenced hatch with a named before-paint phase (R-B1). |
| P9 | **Named-content / multi-slot props** (`:child`, `:label`, `:anchor`, `:body`, `:status-tooltip`… — the brief's verified count: 23 direct hiccup-value props, ~18 in popover/tooltip/label families; my grep: 34 `string-or-hiccup?` mentions incl. defs) | A control accepts caller-supplied content regions as values; slots compose; slot content is testable. | Hiccup IS data — slots are trivially values; aliases expand data at render. [knowledge] | `:children` + render-props/`:slot` props as elements or fns. | Hiccup-as-data gets slots free (both hiccup donors tie here). [seed] the differentiator is TESTABILITY + identity: slot content that carries event vectors remains intent-assertable by equality after composition, and keyed slots can hold stable per-slot identity across parent re-renders (the part-system's real job, without its 182-line registry — P13). |
| P10 | **Asynchronous-data controls** (single_dropdown `(opts, done, fail)` :57; typeahead chan/debounce :26-45) | R-C5..C8: per-instance load status, correlation, supersession, debounce with a visible home, optimistic-update capable. | Nothing framework-level; userland fetch → store → re-render. [knowledge] | Suspense/`use` + `use-optimistic`/`use-transition` [knowledge] — powerful but host-locked and state invisible to any tool. | Resources/mutations ALREADY beat both donors at the app tier (declarative identity, scope, staleness, invalidation, optimistic tags, replies — the whole Case C baseline). [seed] extend the same contract INTO library controls: a control declares its read as a resource parameterised by instance key; debounce/supersession become resource policy (data), not core.async plumbing — the typeahead becomes ~a resource + a draft + a list. No donor has a story here at all. |
| P11 | **Theming / parts** (per-component part-structure + theme multimethod registry; part.cljs 182 + theme.cljs 44 + per-component theme files) | Restyle/replace named regions of a control per app and per instance without forking it; discoverable part names. | Aliases + data attributes; overrides = data transforms. [knowledge] | Class/context-based theming; render-prop part overrides. | When the emitted tree is DATA, a theme is a pure tree→tree transform and a "part" is an addressable node (keyword-tagged), unit-testable by equality. [seed] parts as data addresses + theme-as-pure-fn kills the multimethod registry AND makes themes verifiable headlessly — donors theme by side-channel (CSS/context), invisible to structural tests. |
| P12 | **Accessibility** | Semantic roles/labels/keyboard parity for composite widgets (listbox, combobox, dialog, tabs); a11y assertable in tests. | Data-first markup makes attrs plain; no widget-level a11y machinery. [knowledge] | React ecosystem ARIA libs (radix-class) [knowledge]. | Verified baseline: re-com ships ONE `:role` attr (progress_bar.cljs:82) and zero focus management — the parity bar is near-zero, so the harness sets the bar at NATIVE-ELEMENT-FIRST (dialog/popover/select carry semantics; efxb1h's "platform primitive over custom contract"), plus [seed] compile/registry-time a11y checks on the data tree (the compiled substrate already has provable-only a11y diagnostics per the Uix review — a data tree makes "every interactive node has an accessible name" a structural assertion no donor can express). |
| P13 | **Lifecycle-sensitive behaviour** (listeners, rAF loops, timers, unmount cleanup — the R-B3/R-B12 leaks; the y4mgw stranded owner) | Every acquired thing (listener, timer, loop, data owner) has an owner whose end covers EVERY exit path; teardown provable by inspecting owner state. | Lifecycle hooks as data on nodes [knowledge] — declarative but still per-node imperative cleanup discipline. | `use-effect` cleanup fns — correct when hand-maintained, invisible when not. | The corpus's causal re-homing beats both: route-owned resources released on leave, unmount reduced to at most one dispatch (long_running_work), and the editor needing NO lifecycle at all (article_editor :457-468). [seed] make acquisition-with-owner the only spelling for host resources (cause-carrying, Xray-visible `:active-owners`), so "leaked listener" becomes an assertable state, not a memory bug — plus non-vacuous teardown tests per y4mgw ("a test observes the owner's active/inactive state across a real leave"). |
| P14 | **Facade organisation** (72 bare aliases re-exported in core.cljs) | Consumers get one obvious import surface; discoverability without alias sprawl; tree-shakeable. | Small API + user components as plain fns; no facade problem to solve. [knowledge] | ES-module-style per-ns imports; no central facade. | re-frame2's registry model beats a def-facade: controls registered by id are ENUMERABLE — `list-handlers`/Xray-class tooling can answer "what controls exist, what props do they take" mechanically (the Tool-Pair surface already does this for handlers). [seed] the component catalogue as a QUERYABLE registry with per-entry schema/docs, not 72 vars — self-documentation becomes tooling output. |
| P15 | **Args validation + self-documentation** (69 validate-args-macro sites over 561-line validate.cljs; args-desc tables carrying hiccup docs; 144 manual `(at)` src sites; 100 `handler-fn` wraps) | Bad props fail fast with didactic, source-located messages in dev; docs derive from the same declaration; zero production cost. | `assert`s; no systemic answer. [knowledge] | PropTypes-class runtime checks or TS types [knowledge]; UIx itself lints hooks/keys at compile time. | The repo already exceeds re-com's runtime-only model: schemas on events (`:schema`, `:params-schema` corpus-wide) and Malli-checked props at compiled call sites with didactic ids (the 004 model, per the Uix review's "closed props maps with Malli at literal call sites"). [seed] one declaration = validation + generated docs + registry metadata + tool completion; source coordinates come from the compiler/registry — deleting the 144 hand-written `(at)`s and the 561-line parallel validator. |

**The best-of-hunt, ranked (what native event/sub understanding uniquely unlocks):**

1. **P10 async controls on resources/mutations** — the largest donor gap: neither
   Replicant nor UIx has ANY framework answer to correlation/supersession/optimistic
   state, and the repo's contract already exists and is corpus-proven (Case C).
2. **P6 overlays on the native top layer** — already ruled (efxb1h), beats every donor
   AND re-com's emulation, and deletes the whole R-B3/R-B6 leak class.
3. **P2/P3 intent-as-data at the change site** — placeholder + gating conventions turn
   97% of handler sites and the whole validation display layer into equality-testable
   data (the census makes this quantitative, not aspirational).
4. **P7 the guaranteed reset-key** — nzst23's compiler-guaranteed adjust-during-render
   with explicit-revision keys retires the twin-atom stack with proofs no donor's
   hand-maintained idiom can give.
5. **P13 causal ownership over lifecycle** — "every teardown has a proven owner" as an
   assertable property is this repo's own invention (y4mgw doctrine) and no donor has
   an equivalent.

---

## Appendix — verification ledger

- **re-com checkout** read directly: input_text.cljs (full), dropdown.cljs
  (:240-456 + greps), popover.cljs (:195-360, :450-465, :680-690), modal_panel.cljs
  (full head), modal_panel/theme.cljs (:1-30), popover/theme.cljs (:60-75),
  dropdown/theme.cljs (:15-30), single_dropdown.cljs (greps :57-79, :201-225,
  :355-379), typeahead.cljs (:26-67), input_time.cljs (:190-191), v_table.cljs
  (:29-60), progress_bar.cljs (:82), core.cljs (alias count). Counts by grep:
  72 aliases; 69 validate-args-macro; 100 handler-fn; 235 deref-or-value; 144 (at);
  14 ratom files; 6 create-class files; aria = validator whitelists only.
- **Examples census** over `examples/{core,capabilities,patterns,real-apps}` (85
  files): all counts reproduced in Part 2's table; whole-file reads of flight_booker,
  temperature, todomvc/views, realworld_resources/article_editor; targeted reads of
  cells, circle_drawer, login, crud, nine_states, infinite_feed, websocket/views,
  long_running_work/views, realworld views.cljs, ssr/core.cljc, markdown.cljs.
- **Beads**: `bd show rf2-y4mgw` (full lifecycle history incl. the open same-slug
  clobber finding — confirmed still present at article_editor.cljs:304-314),
  `bd show rf2-efxb1h` (ruling + verified re-com facts), `bd show rf2-nzst23`
  (ruling + ten pins).
- **Reagent caret machinery**: extracted `reagent/impl/input.cljs` from
  `~/.m2/repository/reagent/reagent/2.0.1/reagent-2.0.1.jar`.
- **Spec**: spec/004-Views.md:448-461 (synchrony law), :322 (controlled-input-async-
  handler diagnostic id).
- **UIx claims**: the prior verified UIx review, with the findings restated here;
  API shapes beyond them are [knowledge].
- **Replicant claims**: [knowledge] throughout (no replicant findings doc in this
  checkout; no separate Replicant findings dossier was available).
- **Numbers that differ from the brief's**: brief says "23 direct hiccup-value props" —
  my grep counts 34 `string-or-hiccup?` mentions including definitions/requires; the
  brief's 23 counts actual prop rows and is retained as the canonical figure with
  method noted (P9).
