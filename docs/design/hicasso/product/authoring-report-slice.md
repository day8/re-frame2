# The vertical slice — authoring report

What it was like to write a small complete application on the Hicasso public door, and nothing else. Produced by **rf2-hic-025**; raw material for the ordinary-facade freeze at **rf2-hic-026**, which governs what is done about any of it.

The application is `re-frame.hicasso.examples.slice.*` under `implementation/hicasso/test/`. Seven namespaces, five suites. Two routes, a keyed list, an article editor with controlled fields, an async mutation with a real server-side refusal, an error region, a reset, and a runtime locale and theme switch.

> **This report records what the surface was like to use.** It proposes no renames and takes no decisions — an empty report would have been suspect, and so would one that fixed things. Where a finding has an obvious remedy the remedy is named as a *candidate*, for the freeze to accept or reject.

## What the application actually needed

The most useful single fact for a facade freeze is the list of doors an ordinary application reaches for. This one reaches four namespaces and, within `re-frame.hicasso`, **nine names and two keywords**:

| Reached | Used for |
|---|---|
| `defview` | every boundary — six of them |
| `sub` | every read in five of the six bodies |
| `use-subs` | the sixth body's two reads, and see finding 4 |
| `boundary` | one, around the routed pane |
| `route-link` | two link sites |
| `reg-state` | one per-row disclosure flag |
| `root!` / `render!` / `unmount!` | the entry point and its `^:dev/after-load` hook |
| `::h/value` · `::h/checked` | the two controlled text fields, and the checkbox |

**Never reached, in an application deliberately chosen to be broad:** `portal`, `as-element`, `as-component`, `defhost`, `hfn`, `hframe`, `motion/presence`, and the whole native tier. `::h/revision` belongs on this list too, and the story of how it got there is finding 5: it was reached for, written into two fields and two handlers, and then measured to be doing nothing. Each has a real use case named in the specification; none of them is *ordinary*. `hfn` is the sharpest of these — it is the one callback form, and an application with two text fields, a checkbox, a select and five buttons never needed one, because an intent vector said everything.

The import discipline is asserted mechanically rather than reviewed: `surface-cljs-test` reads each application namespace's `:requires` / `:require-macros` / `:uses` / `:use-macros` off the ClojureScript analyzer and pins that roster of four, so a fifth door cannot arrive quietly.

## Findings

### 1. `::h/value` and the canonical event-vector shape are incompatible, silently

**The sharpest thing found, and the only one with a correctness consequence.**

`spec/Conventions.md` §Canonical event-vector shape asks for `[<id> {<k> <v>}]`, says the linter nudges new code toward it, and gives four reasons. Hicasso's marker substitution is `mapv` over the intent vector's **top level** — `re-frame.hicasso.impl.intent/materialize`, and `markers?` beside it decides the static/dynamic split the same way. Both are deliberate and the reason is stated at the source: a deep walk would be paid on every keystroke of every controlled field.

So the shape the convention asks for cannot carry a marker:

```clojure
;; What the convention asks for. NOT substituted, NOT refused, NOT linted:
;; :value arrives as the keyword :re-frame.hicasso/value and renders as text.
[::events/edit {:slug slug :field :title :value ::h/value}]

;; The only spelling that works — the one the linter nudges away from.
[::events/edit slug :title ::h/value]
```

Nothing catches it. The six checks in the artefact's clj-kondo export are `deferred-read`, `parked-read`, `unkeyed-mapped-child`, `nameless-interactive-element`, `function-in-head-position` and `direct-view-call`; a nested marker is none of them.

The escape hatch exists — `(h/fn [e] [::events/edit {… :value (.. e -target -value)}])` restores the map payload — and it costs a closure per field per render plus the property route-links are sold on, that two renders of one intent are `=`. It also reintroduces the hand-written `.. e -target -value` that `::h/value` exists to delete.

There is a consequence beyond taste, and Conventions names it in its own list: a **positional argument is not path-addressable**, so trace/error redaction cannot classify it. Under the fail-open EP-0025 model a controlled field carrying a secret ships its value raw into every trace sink, and the marker is precisely what forces that field's event into the positional shape.

The slice writes the positional form and says so at both the events namespace and the L2 assertion.

*Candidate remedies, for the freeze:* a lint check for a marker keyword appearing below the top level of an intent at an event position; or a refusal at **lowering** time — `markers?` already walks the vector once per render rather than once per event, so a deeper walk there is paid at the same rate the existing check is.

### 2. `route-link` is called; everything else that makes markup is a head

```clojure
[article-row {:key slug :slug slug}]                     ;; a head
(h/route-link {:to routes/article :params {…}} title)    ;; a call
```

`route-link` is a plain function on purpose, and the reason is good: a link is not a unit of re-render, and a boundary would cost two hooks and a row in the page's boundary count at every one of the corpus's 106 link sites. But nothing at the call site says which grammar applies, and these are the only two things in the application that both produce markup and are written differently.

It is a **one-time** cost and the mistake is loud — a function in head position is `:rf.error/hicasso-function-in-head-position`, and the clj-kondo export flags it before the build. Recorded because a facade freeze should know it exists, not because it needs fixing.

### 3. Top-level `reg-route` does not survive the supported test fixture

`re-frame.test-support/make-reset-runtime-fixture` restores the registrar to a baseline captured when the `use-fixtures` **form is evaluated**. A namespace whose registrations run after that snapshot is taken has them rolled back before the first `deftest`.

For `reg-sub` and `reg-event` this is invisible, because a test namespace requires the application's `subs` and `events` and they load first. For routes it is not: the slice's `routes` namespace had to expose a `register!` function purely so that each test's `:init-fn` could put the routes back.

Nothing in the application changed. What changed is that the shape a consumer copies from a guide — `reg-route` at the top level, once — needs a second door opened for the test's sake, and they meet this on the first test row they write. This is a **testing-surface** finding rather than an authoring one; it belongs to Spec 008 / `re-frame.test-support` rather than to the Hicasso facade.

### 4. `use-subs` reads well at two reads and badly at four

The slice uses it once, in `article-row`, where the group is two reads and the alias map is shorter than the reads would be. It was tried in `editor` and taken back out: that body reads four model values and nine strings, and the alias map would have been longer than the body it fed.

The other half is that `use-subs` **declares** its edges — a branch not taken still costs its edge. `editor` reads its problem string only when there is a problem, and its "Saving…" label only while a save is in flight; declaring those would re-render the editor on a locale change for a message it is not showing. That is the real difference between the two doors, and it argues for the ambient one in every body that branches.

Mixing the two in one body is legal and unremarked: `use-subs` sets a `grouped` flag that nothing gates on, so a body can have a half-declared, half-ambient edge set with no diagnostic. Nothing in the slice needed to, and it is not obvious that anything should.

Consistent with the operator's standing ruling that grouped `use-subs` sits below the ergonomics bar.

### 5. The counter this report asked for was not needed — WITHDRAWN AND REPLACED (rf2-36bd)

**This finding said the opposite, and it was wrong.** It read:

> The reset law is right and the slice depends on it. What the slice discovered is that the **most ordinary** use of it — a *Discard changes* button — does not work without bookkeeping the application has to add […] So an `app-db` key, a `(fnil inc 0)` in two handlers and a subscription exist for no reason a reader of the application can see […] The mechanism should stay; what is missing is that nothing on the door says *you will need a counter*. A worked discard in the guide would probably close it.

It rested on a row that could not see its own subject. `flow-dom-cljs-test`'s reset row asserted the field's value after a discard and said, in its own failure message, that *the changed `::h/revision` is what re-baselines it*. rf2-hic-026's slice review deleted the bump from `::discard` — nothing else touched — and the browser lane came back at **1474 tests, 9154 assertions, 0 failures, captured exit 0**, byte-identical to the control. Ten files recompiled, so the plant was in the bundle. The bookkeeping this finding called load-bearing had never been carried by anything.

**Why it is inert, which is the part a guide row actually needs.** `impl/codec`'s `revision-key` states the whole delivery: *the revision is a value the body reads, and its change **re-runs the body***; the re-run re-commits the element and the commit re-asserts the model over whatever the DOM holds. React marks that host update on props-object **identity** (HD-004 refuses prop-object caching), so **any** re-render of the boundary does the same re-assert for free. A discard already moves three of this editor's reads — the draft, the dirty flag, the save status — so the body re-runs whatever the counter does.

That was measured too, and against the hardest case rather than the easy one. A probe drifted a field's DOM by an **eventless value write** — the shape a password manager, an autofill or a translation extension has, and the shape the controlled-input testbed's `revision` arm uses deliberately — and then discarded, with the bump deleted. The drift was repaired. There is no DOM state in this application that can tell the counter's presence from its absence.

So the counter came out: the `:revision` key, `db/revision`, the two `(fnil inc 0)`s, `::subs/revision` and both `::h/revision` props. The slice reaches **two** of the three reserved keyword markers, not three.

**What replaces the finding.** `::h/revision` is the door for a reset that leaves **every other read the body makes `=`**. Two populations get there and the slice is in neither:

- a **normalising or refusing** field whose typed value lands back on the value the model already holds, so nothing moves at all — `examples/editor`'s slug field is written for exactly this;
- a DOM that **drifted by a route React never saw** — autofill, an extension, a live IME composition — re-asserted by a control that changes no other state. The testbed's `revision` / `revision-strict` arms drive precisely that, with a bare *bump revision* button, and they are the only witnesses in the corpus measured to red on a deleted trigger.

**So the guide row this finding asked for should be inverted.** Not *you will need a counter* — most authors will not, and a row teaching one to the ordinary-discard population would have every reader adding an `app-db` key, a subscription and two `fnil`s that do nothing. The row worth writing gives them the **test**: *if your reset leaves every value your body reads equal, you need `::h/revision`; if it moves any of them, React's own commit has already done it.*

> A caution the corpus earns: `examples/editor`'s `what-the-revision-bump-is-actually-load-bearing-FOR` presents itself as the measured counterpart to this finding, and it stayed green on a deleted bump too (**rf2-5h9k**). Do not write the guide row from it until that is settled.

### 6. Two clicks on one page settle differently, and nothing says which

**Found by a red gate, not by reading.** The first mounted run failed twelve assertions across four rows, and every one of them has this single cause.

A **Hicasso intent** dispatches through the runtime's own synchronous frame-locked door. After a real click on `.save` or `.discard` the handlers have run, `app-db` has moved and React has committed; the next line reads the repainted page, and `hm/settle!` is all that is owed.

A **route-link** does not. `re-frame.routing/activate-link!` ends in `router/dispatch!` — the async door — so the click returns with the navigation merely enqueued, and the router drains it on `interop/next-tick`, a next-turn *task*. `hm/settle!` is an empty `flushSync` and cannot help: nothing is scheduled in React yet.

Both are written the same way in the view, one as an intent vector and one as a `route-link` call, and neither call site carries the distinction. A witness that clicks a link and asserts on the next line reads the page as it was.

The same is true of any **async mutation reply**, and there it is the application being ordinary rather than routing being special: an fx that replies with `rf/dispatch` — as `day8/re-frame2-http` does — is enqueued, so *every* async reply in *every* re-frame2 application arrives through a router drain.

Neither of those is a defect on its own. What is missing is a door: **the L3 facade has `settle!` for React's work and `dispatch-and-settle!` for a dispatch it makes itself, and nothing for "work is enqueued in the router; let it land."** The slice uses `re-frame.test-support/poll-until`, which is the supported condition-poll and composes with `cljs.test/async`, and states the rule in its own namespace docstring.

*Candidate for the freeze, and it belongs to rf2-hic-027 rather than to the facade:* an `hm/drain!` or a `:until` option on `settle!`, so the L3 vocabulary covers the async half of the runtime it is a facade for.

### 7. The virtual clock and `poll-until` cannot be used together

A consequence of finding 6, and worth its own row because it is a trap with no diagnostic.

`hm/mount!`'s `{:clock true}` was the first thing tried for the stand-in server's `setTimeout`, and it cannot work for an async mutation. Two reasons, and either alone is enough:

- The clock **replaces the global `setTimeout`**, and `poll-until`'s CLJS arm schedules its own interval with `js/setTimeout`. Under a virtual clock the poll gets its first probe and never a second — it simply never resolves, and times out at its deadline with nothing to say about why.
- Firing the server's timer only *enqueues* the reply. The drain that delivers it is `interop/next-tick`, a macrotask the clock deliberately (and correctly) does not drive.

So the two supported waiting mechanisms are mutually exclusive, and an async mutation witness needs what both offer. The poll is the one that works, and it is the better instrument anyway: what such a test waits for is a reply, not a duration. The clock remains right for what its own docstring is about — a retention deadline, a debounce, a `:dispatch-later` — where the thing being waited on *is* a duration and no dispatch has to drain afterwards.

### 8. Route PATHS are global and route IDS are not, and only one of those is obvious

**Found by the whole-repo node lane, and by nothing else that ran.** The browser lane was green, the artefact's own focused lane was green, and twelve assertions in `re_frame/realworld_cljs_test.cljs` were failing — naming this application's route:

```
expected: (= :realworld.article/show (rf/compute-sub [:rf.route/id] …))
  actual: (not (= :realworld.article/show
                  :re-frame.hicasso.examples.slice.routes/article))
```

The slice's routes were `/` and `/article/:slug` — the two most natural paths an author writes, and the two RealWorld already holds. Route **ids** are namespaced keywords and cannot collide; route **paths** are strings in a process-global registry, and this repository's node test bundle loads a dozen applications into one process. `match-url` began answering this app's route for RealWorld's URLs.

**Nothing warned.** `reg-route` emits `:rf.warning/route-shadowed-by-equal-score` for a co-matchable *equal-rank* pattern, and it never fired: the ranks differ, so the guard had nothing to say while the resolution was wrong anyway. The whole collision was silent.

The fix is a `/slice` prefix on every path, and it is the right fix — a consumer's own registry holds only their routes and they never meet this. It is recorded because of what it says about *the evidence*: this is a class of failure that a per-artefact gate is structurally unable to see, and it took the whole-repo lane, on a diff whose every other gate was already green, to find it.

Filed as **rf2-wqnl**, which made the prefix a written convention for the whole bundle and put a census gate behind it (`implementation/routing/test/re_frame/routing_path_census_test.clj`). Its ruling also corrects one detail above: for the *identical* `/article/:slug` pair the structural ranks are equal, not different, so the guard's condition was satisfiable — what silenced it is that `:rf.warning/route-shadowed-by-equal-score` goes to the instrumentation ring buffer, which nothing in the node lane reads. Ranks genuinely differ only in the general cross-app case (one app's literal under another's capture), where the guard is blind by construction.

### 9. Small things

- **`reg-sub`'s two-fn form puts a one-argument fn beside a two-argument one.** The `input-fn` takes `query-v`; the computation fn takes `[inputs query-v]`. They sit adjacent in the same form and the mistake compiles. The `:<-` chain avoids it and is what the slice uses.
- **A `false` attribute is recorded; a `nil` one is dropped.** Per 004B. So an L2 row asserting "this button is not disabled" wants `(is (false? …))`, not `(is (nil? …))` — worth one line in the kit's `attrs` docstring.
- **The `h/boundary` fallback can only be asserted as data.** Driving it needs something to throw, which a testbed does not carry (testbeds model proper re-frame2 and hold no deliberate bugs). The slice asserts the fallback markup and the `:reset-key` at L2 and states the limit rather than inventing a crash.
- **A residue census is page-wide, and `assert-clean!` says so beautifully.** The two-mount isolation row unmounted one mount and asserted it while its peer was still standing, and the failure read *"1 other facade mount(s) were still standing when this reading was taken, so their cells are inside it. Take every mount down before asserting any of them clean."* That is the whole diagnosis and the whole remedy, in the failure itself. Recorded as the one place the instrument was better than the author.

## Where the slice is gated

The application and its five suites live under `implementation/hicasso/test/`, which is an existing shadow-cljs `:source-paths` entry, so:

| Suite | Namespace suffix | Lane |
|---|---|---|
| surface, L0, L2 | `-cljs-test` | `:node-test` (`npm run test:cljs`) and `:node-test-hicasso` |
| flow, i18n/theme | `-dom-cljs-test` | `:browser-test` (`npm run test:browser`), plus a stated skip on the node lane |

Both lanes are armed for `implementation/hicasso/**` — `cljs_node_test` and `cljs_browser` — so every suite runs on a diff that touches the slice.

**`implementation/hicasso/examples/` — the path rf2-hic-025's bead names — is on no `:source-paths` entry, so a tree there would be compiled by nothing.** That is why the slice is not at that path. Moving it is a `git mv` plus two lines in `implementation/shadow-cljs.edn`, which is hot-zone and was not taken.
