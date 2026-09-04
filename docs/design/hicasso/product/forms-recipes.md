# The forms recipes, and the helper that was not extracted

> **Overruled on different ground, 2026-08-12 — operator ruling (Mike, in session; `rf2-sh56`). The helper was extracted after all, and `re-frame.hicasso.forms/buffered-field` has shipped in V0.** The refusal below is left standing as the record, because its arithmetic was sound on its own ground: the caller count was one, and `h/reg-state` already is the addressed-draft door. The operator overruled it on a different ground — a guide chapter teaching a namespace the code lacks is the defect — and ruled the module V0 scope with [`core/hicasso/05-forms.md`](../../../core/hicasso/05-forms.md) standing as its draft spec, so the code caught up to the chapter. For this feature only that overrides the [specification.md](specification.md#7-complete-use-case-coverage) §7 second-caller extraction gate this page reasons from; **the gate stands everywhere else.** Two sentences below are therefore superseded rather than merely dated: *the helper is not extracted* (it is — the module was extracted from exactly these recipes, which stay, because they teach the doors it is written on), and *[naming-ledger.md](naming-ledger.md) row 16 stays exactly as it is* (row 16 is settled: `re-frame.hicasso.forms`, shipped). Nothing else on this page is amended by the ruling, and none of it is re-measured (`rf2-2aag`). One later correction is not the ruling's and is recorded here so the two are not confused: the vendor-screen bullet below was rewritten on **fact** — that screen has since landed and its bead is closed — without disturbing the arithmetic it feeds (`rf2-9yjjc`).

The evidence for [specification.md](specification.md) §7's *Forms and controlled fields* and *Validation and async normalization* rows, and for §4.2's closing sentence — *buffered drafts, touched/submit-attempt validation, and mutation status belong in an optional forms layer **or recipe**, not the boundary shell.*

`rf2-hic-051` **changes no runtime and adds no namespace to the artefact.** It is three recipes, written as one small application on the shipped public doors, plus the suites that hold them.

## Recipes first, and what that ordering turned out to mean

The bead lists its deliverables in an order: the recipes are unconditional, the `draft-field` helper is extracted **when a second consumer appears**. §7 says the same thing in one line — *forms recipes; buffered-draft helper after a second caller.*

The gate is not met, and it is not close. The bead names the two candidate callers as *the slice app + the vendor screen*:

- The **vendor screen** has since landed as `implementation/hicasso/test/re_frame/hicasso/examples/ledger/`, and `rf2-hic-047` is closed (PR #8043). **Corrected on fact 2026-08-14 (`rf2-9yjjc`): when this page was written the bead was open and the application was not in the tree, and this bullet said so.** Its arrival does not move the count. The ledger is ten thousand rows through a foreign virtualizer; it opens no session and reaches for no buffered protocol at all — no `reg-state` concern, no commit, no cancel, no blur, and no `:require` of `re-frame.hicasso.forms` anywhere beneath it. It is a second application, not a second caller.
- The **slice** does not use the buffered protocol at all. Its editor writes every keystroke straight into `:drafts` and commits with a Save button; there is no per-field commit, no cancel, and no blur protocol. It is a consumer of *draft versus canonical*, which needs no helper — it is app-db.

The one existing consumer of commit/cancel/blur is the **Todo witness**'s edit-in-place row, and it is written on `h/reg-state`, `::h/clear`, `:on-blur` and the `:on-key-down` key map — all shipped, all public. Its whole widget-state surface is one `reg-state` declaration.

So the honest count of callers for a `buffered-field` component today is **one**, and a witness written to justify its own extraction would be a gate reading back its own write. **The helper is not extracted.** [naming-ledger.md](naming-ledger.md) row 16 — `re-frame.hicasso.forms`, provisional, open — stays exactly as it is.

What that leaves is worth stating plainly, because it is the finding rather than a hedge: **`h/reg-state` already is the addressed-draft door.** It mints the sub and the setter under one keyword, keys by instance, refuses an unqualified concern, and shares one framework `::h/clear`. A `forms/buffered-field` view on top of it would add a public export, a boundary body per field, and a second place for the commit protocol to be written down — against a minimal-API stance, and against `rf2-hic-045`'s published per-keystroke arithmetic.

## What was built, and where it runs

Four application namespaces under `implementation/hicasso/test/re_frame/hicasso/examples/forms/` — `db`, `events`, `subs`, `views` — beside the slice, the Todo witness, the four-field editor, the grid and the typeahead, where `hicasso/test` is already on the `:source-paths` both test lanes compile from. No `:source-paths` entry was added and no route is registered.

The application reaches **four** foreign namespaces: `clojure.string`, `re-frame.core`, `re-frame.hicasso` and `re-frame.resources`. The last is the one no other Hicasso witness names, and it is the whole of the mutation-status recipe. That roster was pinned and read off the ClojureScript analyzer's own dependency graph rather than off the `ns` forms.

**[2026-09-04, `rf2-60jv`: the roster is now a reviewed claim.]** `rf2-6c12m.10` deleted `forms.surface-cljs-test` with the rest of the per-package `*surface-cljs-test*` suites. Its successor, `re-frame.hicasso.examples.fence-cljs-test`, derives its population from the `examples/` directory on every run and reads each `ns` form with `cljs.tools.reader`; it is a **four-family blocklist** — no Hicasso internal, no benchmark tree, no development tool, no test kit — so it holds *nothing here reaches past the public door* and holds neither the count of four nor the absent routing edge. Both are true as at this page's date and neither is enforced; the positive roster is deliberately not re-asserted ([`specification.md` §13](specification.md#13-definition-of-done)).

| suite | lane | what it owns |
|---|---|---|
| `forms.l0-cljs-test` | `:node-test` | the rules as pure functions, and every transition through a real frame |
| `forms.l2-cljs-test` | `:node-test` | the bodies as semantic trees on the kit, and the two structural traps |
| `examples.fence-cljs-test` | `:node-test` | the import discipline for this package and every other, as a four-family blocklist — **not** the door roster, and **not** the absent routing edge |
| `forms.flow-dom-cljs-test` | `:browser-test` | the mounted rows — node identity, focus, real blur ordering, foreign drift |

## The three recipes, and the door each is written on

### 1. The buffered draft — commit, cancel, blur

The draft is an `h/reg-state` concern keyed by the ticket. Absent means *no session*, which is what makes the commit handler idempotent: Enter then blur, double Enter, or a blur arriving after Escape all find no session the second time and do nothing. *Cancel beats the late blur* is answered by the model rather than by ordering, and no handler knows what order it ran in.

The field carries `::h/revision`, and unlike the Todo row it **stays mounted** across a cancel — which is the arrangement the reset law exists for.

The commit and cancel handlers write `h/reg-state`'s documented `[:ui <concern> <ikey>]` path directly rather than dispatching `[::h/clear …]`, and that is the recipe's one sharp edge. Ending a session is two facts at once — the draft is gone, and the field must re-baseline — and `::h/clear` is an event, so a handler that used it would land the removal and the revision bump in **different turns**. The turn between them renders a field whose revision has already moved while its draft has not: a reset spent on the text it was meant to discard. `reg-state` states that the `:ui` tier is app-space and that an ordinary handler may read and write it; this is the case that needs the second half, and it is the only place these recipes reach for that permission.

### 2. Touched and submit-attempt gating

The display gate is `touched OR attempted?`, per field, and the two halves are kept apart: touching the assignee may not reveal the note's problem, while a submit attempt reveals every one at once. The problem region is **absent** rather than empty while the gate is shut, so `aria-describedby` never points at a node that is not there.

The submit gate is one `db/can-submit?` function called by both `::subs/can-submit?` and the handler that refuses. R-A6's failure is two recomputations drifting apart, so the fix is one *definition* rather than one cached value. A materialised flow is the other honest answer, buys a value a tool can see, and costs a registration and a boot event; it is not paid for here and the reason is written in the namespace.

**The submit button is enabled while the form is invalid.** A disabled submit leaves the tab order, announces nothing, and — the mechanical half — makes `:attempted?` unreachable, which makes the whole submit-attempt gate dead code. So the button stays operable and the refusal happens in the handler.

**And `aria-disabled` is not the softer spelling of that.** [WAI-ARIA 1.2](https://www.w3.org/TR/wai-aria/#aria-disabled) defines `aria-disabled="true"` as perceivable but disabled — the element "is not editable or otherwise operable" — which is the same claim `disabled` makes, minus the tab-order loss. Pressing Save is the only route this form has to its own errors, so a button wearing that attribute tells assistive technology the one available instruction will not work. Validity is therefore not a button state at all: the save button reads nothing about the draft, and the errors carry it, on the fields, as `aria-invalid` plus an `aria-describedby` problem region that is `role="alert"` and absent until the gate opens. `aria-disabled` is reserved for a control the handler really does refuse — the slice application's end-of-list pager arrow is the repo's example.

> **This rule now stands in the published guide, 2026-08-21 (`rf2-ps7ia` step (b)), corrected 2026-08-22 (`rf2-hic-051`).** [`core/hicasso/05-forms.md`](../../../core/hicasso/05-forms.md#materialise-the-submit-gate-once) taught the opposite until the first of those dates — its worked form disabled the button on `(not can-submit?)`, which made that chapter's own `:submit-attempted?` unreachable by exactly the arithmetic above — and the operator's destination split ruled teaching content into the guide by merging it into the chapters rather than by moving any file. The chapter now disables only on `:pending?` and carries the two-kinds-of-unavailable distinction. What the first pass got wrong, and what the second fixed everywhere at once, was the *marking*: `aria-disabled` was promoted as the way to say "invalid", which contradicted the activation the same page required. The guide, this page, the example view and its DOM/L2 witnesses moved together. **Nothing on this page is superseded**; it stays as the evidence for [specification.md](specification.md#7-complete-use-case-coverage) §7's *Forms and controlled fields* row.

### 3. Mutation status

The write is a `reg-mutation` run under a stable **instance** id, and the form's busy flag, its disabled fields and its error region all read `[:rf/mutation {:instance …}]`. There is no `:saving?` key in this application for a failure branch to forget to clear, and there is no completion callback: completion arrives at a named event because `:reply-to` said so. The stale-reply fence is the runtime's — a superseded reply never reaches `:reply-to` — so the reply handler contains no generation check and needs none.

The button IS disabled while the write is in flight, and that is the only unavailability it ever claims. *In flight* and *invalid* are not two grades of the same thing: the first is an action the handler really suppresses, the second is an action that does something, and the recipe spells only the first.

## The five trap classes, and where each is decided

| trap | decided by | how it reds |
|---|---|---|
| twin-atom stack | `l2-cljs-test`, structurally | the kit installs no React dispatcher, so a body holding local state would not run — and every body in the application runs |
| same-value blindness | `l0` for the model, `flow-dom` for the screen | remove the revision bump and the browser row reds with the autofilled text still in the box |
| commit flicker | `l0` | two writes under one instance; the older reply lands last and changes nothing |
| arity-sniffed done-fn | `l2` for the absence, `l0` for the reply | the whole rendering is walked and no handler site anywhere carries a function |
| re-minted ephemeral state | `l0` for addressing, `flow-dom` for survival | the field leaves the page and comes back holding its draft |

Every one of those has the second direction too — a control that shows the instrument spares a legal near-miss. The a11y walk reports an unnamed button and **not** the labelled field beside it; the fence predicates catch a private namespace and **not** the public door; a note exactly at the length limit is legal; touching one field does **not** reveal its neighbour's problem; a refused submission on a blank form reveals the one problem it has and not two.

## The `::h/revision` row is an experiment, and it survived its refutation

In an application whose draft lives in `app-db`, ending a session usually moves the value the field reads — and a moved value re-renders the boundary, re-commits the element and re-asserts the model for free. A revision would be decoration.

So the mounted row builds the one arrangement in which that is not true: a session whose draft ends **equal** to the committed subject, so that ending it moves nothing the field reads except the revision, plus a foreign write React never saw. Run with the bump deleted, the row reds holding `"autofilled@example.com"`. The prop is load-bearing.

Keeping it that way cost a design change. `views/subject-hint` is its own boundary because it is the only body reading *is a session open*; left inside the field, that read changing at every session end would re-commit the element and the revision would have tested green while doing nothing.

## What did not hold at source

1. **[core/hicasso/05-forms.md](../../../core/hicasso/05-forms.md) documents a module that does not exist.** It teaches `forms/buffered-field` with a `:control` address, a fixed interaction protocol and a props roster, in the present tense. There is no `re-frame.hicasso.forms` namespace, and after the reasoning above there should not be one yet. A reader following that chapter gets a namespace-not-found. The chapter is not edited here — the guide is under active authoring — and the gap is filed as `rf2-1u09`.

    **Closed the other way, 2026-08-12 (operator ruling — see the note at the head of this page).** `rf2-1u09` was answered by building the module rather than by editing the chapter: the ruling made `05-forms.md` the draft spec and `rf2-sh56` shipped `re-frame.hicasso.forms`, so the namespace exists and the chapter's `:require` resolves. The reasoning above is the record of why it was refused first, not a live statement that it should not exist.

2. **`:rf.mutation/execute` is a silent no-op when the mutation id is not registered.** No instance, no request, no error; the instance reads `:idle` afterwards, byte-identical to never having written. Four browser-lane runs went into proving in turn that it was not the `:fx [[:dispatch …]]` turn, not the mounted facade's frame and not `with-new-frame`, before `rf/mutation-meta` answered `false`. Filed as `rf2-06lp`. The recipes work around it the way the slice's routes do — a named `register-save!` called from each suite's fixture — and the workaround is documented where it lives rather than here.

3. **`::h/clear` cannot end a session in one turn.** See recipe 1. This is a real limit of the `reg-state` door rather than a defect in it: clear is an event by an explicit ruling, for a fail-silent hazard that matters more. It is recorded because the workaround — writing `[:ui <concern> <ikey>]` by hand — is the kind of thing a reader needs a reason for.

## What this page does not claim

It re-measures nothing. The per-keystroke mechanics of a controlled field belong to `rf2-hic-045`; the debounce, supersession and cancellation figures belong to [the typeahead witness](resource-demand-witness.md); the composition carve-out and the caret laws belong to the controlled-input suites and the IME witnesses. A second source for one number is a second thing to drift.
