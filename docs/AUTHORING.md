# Guide authoring

This document governs pages under `/docs`.

The goal is simple: help a competent developer understand re-frame2 quickly,
accurately, and with as little friction as possible.

The guide is not the spec. It is not a design diary. It is not a catalogue of
everything the framework knows.

It is the thing a developer reads when they want to build something.

Readers never land here (it is not in the site nav). They start learning at
[the guide](core/introduction.md).

---

## The reader

Assume the reader:

* is a working developer
* understands ordinary programming concepts
* probably knows React, Redux, re-frame, or another frontend framework
* does not yet know re-frame2
* is reading because they need to do something

Do not talk down to them.

Do not make them decode framework internals before they can use the framework.

Introduce re-frame2 vocabulary when the vocabulary earns its keep.

---

## The basic rule

**Teach the smallest useful model first.**

A page should answer, in roughly this order:

1. What problem does this solve?
2. What do I write?
3. What happens when I write it?
4. What can go wrong?
5. What else matters once I understand the normal case?

If the implementation detail does not help answer one of those questions, it
probably does not belong yet.

A page that teaches *how to do a job*, *how a concept works*, *why a design
exists*, or *how to find the right page* still obeys that order — it should not
mix those jobs on one page. If you need a label: tutorials show a path,
how-tos complete a task, explanations argue a model, indexes route with
judgment. The label is optional; the one-job rule is not.

---

# Writing style

## Write like a developer explaining code to another developer

Use ordinary technical English.

Prefer:

> Call `h/sub` where you need the value.

over:

> Reads live at the point of use.

Prefer:

> A plain `defn` does not create a re-render boundary.

over:

> Helpers surrender their reads upward to the enclosing boundary.

Prefer:

> Hicasso creates the callback and dispatches the event vector for you.

over:

> The runtime lowers the intent into a callback carrying the frame.

Use specialised terminology when the distinction matters. Do not use
specialised terminology merely because the implementation has a name for
something.

---

## Do not perform a voice

The documentation should have personality, but the writer should mostly
disappear.

Avoid trying to make every paragraph:

* witty
* punchy
* memorable
* philosophical
* opinionated
* quotable

One good sentence is better than three clever ones.

Do not write slogans around ordinary mechanics.

Avoid lines like:

> The attractions are few, and they compound.

> The two spellings do not cross.

> Those are deliberate prices.

> Data survives all the way to the leaf.

They may sound polished in isolation. Across a guide they make the prose feel
written rather than useful.

Say what happens.

---

## Let the example do the work

If code demonstrates the idea clearly, do not explain the same idea three more
times.

Bad:

> Event handlers are normally where a data-oriented system loses its
> data-oriented nature. This is an important boundary because closures are
> opaque...

Then a code sample.

Then another paragraph restating that the vector is inspectable.

Better:

```clojure
[:button {:on-click [:todo/toggle id]} "Toggle"]
```

Hicasso accepts an event vector directly. It creates the callback and
dispatches the vector when the button is clicked.

Because the tree still contains `[:todo/toggle id]`, tests and tools can
inspect it as ordinary data.

Three sentences. Done.

---

## Prefer concrete verbs

Good verbs:

* calls
* returns
* reads
* writes
* dispatches
* renders
* compares
* creates
* stores
* retries
* cancels
* warns
* throws
* subscribes
* re-renders

Be suspicious of unnecessarily literary verbs:

* mints
* carries
* narrates
* owns, when "stores" or "tracks" is clearer
* flows, unless an actual data flow is being described
* crosses a boundary, unless the boundary itself matters

When the runtime signals failure, prefer **throws**, **raises**, or **returns
an error**, and name the `:rf.error/*` or `:rf.warning/*` id when one exists.
"Refuses" is fine only when you immediately say how that surfaces to the
caller.

Precision beats flavour.

---

## Prefer one explanation over a chain of abstractions

Do not write:

> A boundary owns the collector which records the reads produced while lowering
> the view.

If what the reader needs is:

> Each `defview` tracks the subscriptions read while it renders. When one of
> those values changes, that view re-renders.

The second explanation may be less implementation-complete. It is much more
useful.

Introduce `boundary`, `collector`, `lowering`, and similar terms only when the
reader needs those distinctions later.

---

# Page structure

## One page, one job

Every page should have one clear reason to exist.

Examples:

* teach subscriptions
* show how controlled inputs work
* explain routing
* show how to test an event handler
* document the `reg-view` API

If the page is trying to be both a tutorial and a reference catalogue, split it.

If the page cannot be described in one sentence, reconsider the scope.

**Index pages** (corpus home pages) state prerequisites, when not to use the
artefact, and the page's own job. They do not restate the left-hand nav as a
roster or a "start here" reading-order list. MkDocs already lists the pages.

---

## Start with the problem, not the architecture

Bad:

> Hicasso's rendering model consists of boundaries, collectors and an
> interpreted Hiccup lowering phase.

Better:

> A view often needs several subscription values, including values needed only
> inside a helper or conditional. In Hicasso, you can call `h/sub` exactly
> where you need the value.

Start with what the developer is trying to accomplish.

Explain the machinery afterward, if it helps.

---

## Show working code early

Readers should see the normal form quickly.

Do not make them read several paragraphs before seeing the API.

Typical shape:

````markdown
# Subscriptions

Use `h/sub` to read a registered subscription from a Hicasso view.

```clojure
(h/defview todo-count [_]
  [:span (h/sub [:todo/count])])
```

`h/sub` records the subscription as a dependency of this view. If the value
changes, the view re-renders.
````

Then expand.

---

## Teach the normal path before edge cases

The main body should teach what most applications should do.

Move the following later:

* alternative forms
* migration details
* obscure configuration
* unusual performance cases
* implementation internals
* rarely used escape hatches
* exhaustive option tables

A reader should be able to stop halfway through a page and still know the
normal solution.

---

## Advanced material belongs after the useful model

Use:

```markdown
## Advanced
```

when there is genuinely optional depth.

Do not create artificial categories such as:

* Basics
* Day one
* Essential
* Going further
* Expert mode

The page itself is the normal material. `Advanced` is optional material.

---

# Concepts and terminology

## Introduce terms only when they buy precision

A term earns a place when ordinary language becomes ambiguous without it.

For example, `frame` matters because it names a real re-frame2 concept with
observable behaviour.

`collector` may not matter on the first subscriptions page if the reader only
needs to understand that a view tracks what it reads.

Prefer:

> `defview` creates an independently re-rendering view.

Then, if the distinction becomes important:

> The guide calls this independently re-rendering unit a **boundary**.

Definition follows usefulness, not the other way around.

---

## Define a term once, briefly

Do not repeatedly parenthesise definitions throughout the guide.

Bad:

> the boundary (the independently re-rendering view which owns the frame...)

every time `boundary` appears.

Define it clearly once. Link to the glossary when needed.

---

## Use one term for one thing

Do not casually rename framework concepts for variety.

If the guide calls it an **event pipeline**, keep calling it an event pipeline.

Do not rotate through:

* pipeline
* loop
* cycle
* machine
* flow

unless those are genuinely different things.

Prefer **event pipeline** (and *pipeline run*) for the fixed stage sequence of
one dispatched event. Do not call that structure "the loop" — a pipeline is
linear per event. Legitimate "loop" uses remain: drain loop, dispatch cycle,
`for`/loop index, "loop the render" as a bug.

Technical prose does not benefit from synonym variety.

---

# Examples

## Examples must be real

Every example should:

* use the actual API
* be valid ClojureScript
* demonstrate the recommended approach
* contain enough surrounding code to make sense
* avoid hidden prerequisites where practical

Never invent an API because it makes the example easier to explain.

Verify unfamiliar or recently changed API shapes — and every taught
`:rf.error/*` / `:rf.warning/*` id — against:

1. the implementation
2. the spec (while authoring; do not link readers into `spec/`)
3. tests or examples where useful

The guide must teach what ships, not what sounds plausible.

Async callbacks carry a frame. A bare `dispatch` from a timeout or promise
raises `:rf.error/no-frame-context`. Examples that leave the view body must
show capture or an effect that carries the frame.

Prefer adapting from `examples/` with a source comment
(`;; cf. examples/...`) when a shipped example already teaches the shape.

---

## Prefer one continuing example

Where practical, use the same domain across related pages.

For Core, a small todo or counter application is usually enough.

Do not invent:

* a shopping cart on one page
* a spaceship on the next
* a chat app on the next
* a banking dashboard on the next

Novel examples make the reader repeatedly reconstruct irrelevant context.

Domain corpora (machines, resources, async, routing, SSR) should pick a stable
domain noun for their track the same way Core uses counter/todo.

---

## Do not teach anti-patterns accidentally

If showing incorrect code, mark it unmistakably:

```clojure
;; Don't do this
```

Then show the correct form immediately afterward.

The copy-pastable code on a page should overwhelmingly be good code.

---

# Explanation depth

## Explain the useful why

Good:

> Put the key in the props map. Hicasso passes it to React but removes it
> before calling your view body, matching React's component semantics.

Too little:

> Keys go in props.

Too much:

> During lowering, the interpreter identifies the reserved identity slot
> before the component ABI conversion phase...

Explain enough that the behaviour stops feeling arbitrary.

Stop there.

---

## Do not re-argue the framework on every page

The guide does not need to repeatedly persuade the reader that:

* data is good
* views should be derivative
* effects as data are useful
* deterministic systems are easier to reason about
* re-frame2 differs from React

Those ideas belong in the introduction and relevant explanation pages.

Once established, later pages can simply use them.

---

## Distinguish behaviour from rationale

First say what happens.

Then, if useful, say why.

Example:

> Hicasso compares view props with ClojureScript `=`. If the props are equal
> and none of the view's own subscriptions changed, the body does not run
> again.
>
> This makes structural values cheap to pass between views without requiring
> the caller to manage memoization.

Mechanism first. Rationale second.

---

# Errors and troubleshooting

## Name concrete failures

Do not say:

> This can behave unexpectedly.

Say:

> Calling a `defview` directly raises `:rf.error/...`.

When re-frame2 provides a named error or warning, use it.

---

## Keep troubleshooting operational

Use a table when several failures belong together:

| Symptom | Cause | Fix |
| --- | --- | --- |
| Page reloads on form submit | Browser default was not prevented | Wrap the event with `::h/prevent` |
| View does not re-render | Value was read outside the view's tracked subscription context | Read it with `h/sub` inside the view |
| React warns about list keys | Child has no stable `:key` | Put a stable domain id in the props map |

Prefer the heading **Troubleshooting**. Do not turn troubleshooting into an
essay.

---

# How much personality?

A little is good.

A sentence can occasionally have teeth:

> An array index is not an identity. It merely happens to be standing where an
> identity should be.

But use that kind of line rarely.

The test is simple:

**If removing the joke improves the page, remove the joke.**

Humour should make a technical point easier to remember.

It should not prove that the author can write.

---

# Things to remove during editing

On the final pass, search for these problems.

## Empty emphasis

Words such as:

* deeply
* fundamentally
* importantly
* deliberately
* powerful
* elegant
* clean
* first-class
* naturally
* simply

are often unnecessary.

Keep them only when they distinguish one behaviour from another.

---

## Repeated framing

Delete sentences that merely announce what the next sentence will explain.

Bad:

> There are two important things to understand about this.

Then two bullets.

Just write the bullets.

---

## Duplicate conclusions

If the paragraph says:

> `h/sub` can be called from helpers.

do not end it with:

> The important point is that helpers can read subscriptions.

The reader got it.

---

## Unnecessary metaphors

Do not describe architecture as:

* a spine
* a seam
* a contract boundary
* a bridge
* a highway
* a surveillance state
* a skyscraper
* an escape hatch

unless the metaphor materially makes the mechanism clearer.

One literal sentence is usually better.

---

## AI-shaped prose

Watch for:

* repeated `X, not Y` constructions
* repeated three-item rhetorical lists
* dramatic em dashes
* one-sentence paragraphs used for fake emphasis
* constant bolded mini-slogans
* every section ending with a neat thesis
* unnecessarily symmetrical sentences
* phrases such as "the key insight", "the important thing", "at its core"

These patterns are not forbidden. Repetition makes the prose feel synthetic.

Vary structure according to the material, not according to a writing formula.

---

# Guide versus reference

The guide teaches how to think and work.

The API reference records exact forms.

If a guide page starts accumulating:

* every option
* every map key
* every overload
* every error code
* every return shape
* every precedence rule

move that information to the API reference and keep the useful subset in the
guide.

A guide page should answer:

> What should I normally do?

The reference should answer:

> What exactly is possible?

Definitions live in the [glossary](core/glossary.md) when the term needs a
standalone home; exact public symbols live under [API reference](api/README.md).

---

# Guide versus spec

The spec is the semantic authority.

The guide is standalone user documentation.

Do not send readers into `spec/` to understand how to use re-frame2.

Read the spec while authoring. Rewrite the relevant idea for a user.

The guide should explain the public model in the language of the
implementation.

---

# Navigation and MkDocs

The guide is rendered with **MkDocs Material** (config in repo-root
`mkdocs.yml`).

MkDocs already provides:

* the left-hand navigation
* section hierarchy
* page table of contents
* previous/next navigation
* breadcrumbs and site-level orientation

Do not recreate those things inside the page.

Avoid:

* `## Start here` sections that restate the sidebar
* manual reading-order lists
* mini tables of contents
* "Next, read…" footers
* "What's next" sections
* lists of neighbouring chapters purely for navigation

Use inline links when they help explain the current topic:

> Managed retries are covered in [HTTP](async/http.md).

That is useful context.

This is not:

> Next:
>
> 1. HTTP
> 2. Resources
> 3. Routing

Let MkDocs handle navigation. The page should spend its words teaching.

---

# Editing test

Before a page is finished, ask:

### Can a developer find the normal code quickly?

If not, move code earlier.

### Does the page introduce machinery before the reader needs it?

If yes, delay the machinery.

### Is every paragraph teaching something?

If not, cut it.

### Is the same point made twice?

Keep the better version.

### Is a framework term being used because it helps the reader?

If not, use ordinary English.

### Could this sentence appear in generic AI-generated framework documentation?

If yes, make it more concrete or delete it.

### Does the prose sound impressive when the underlying idea is simple?

Make the prose simple too.

### Can the page lose 20–30% of its prose without losing information?

Try it.

Most first drafts can.

---

# Default page shape

This is a useful starting structure, not a template that every page must
visibly follow.

````markdown
# Topic

One or two sentences describing the problem.

```clojure
;; normal working example
```

Explain what the code does.

## Important behaviour

Explain the few rules the reader needs to use the feature correctly.

## Troubleshooting

| Symptom | Cause | Fix |
| ------- | ----- | --- |

## When not to use it

Briefly explain when another re-frame2 feature or another approach is more
appropriate.

## Advanced

Optional details that are genuinely useful after the normal model is
understood.
````

Delete headings that do not earn their place.

---

# How pages get written

Rules without a short process lose to volume. Three habits:

1. **Bullet spine first.** Do not draft full prose until the page's problem,
   normal code, and one-job sentence are clear.
2. **Content, then cut.** After the facts are right, do a pass that only
   removes slogans, duplication, and AI-shaped cadence — ideally with a
   different editor or model than the first draft.
3. **Gold samples.** Match the tone of pages the project already trusts (for
   Core, start from a strong existing teaching page). Do not match the tone of
   a long first-draft dump.

---

# Final principle

The reader should come away thinking:

> That was straightforward.

Not:

> That was beautifully written.

And definitely not:

> I can tell an LLM wrote this.

---

# What CI enforces

On docs PRs: `mkdocs build --strict`, the link/anchor validator
(`scripts/check_doc_slugs.py`), and residue scans in
`.github/workflows/docs.yml`.

Everything else is human judgment: feature PRs update the affected guide page
(or say why not); click live cells you touched; prefer cited `examples/`
sources so the examples compile gate covers them.
