# The Todo-class witness — authoring report

What it was like to write the *other* ordinary application on the Hicasso public door, and nothing else. Produced by **rf2-hic-086**; raw material for the ordinary-facade freeze at **rf2-hic-026**, which governs what is done about any of it.

The application is `re-frame.hicasso.examples.todo.*` under `implementation/hicasso/test/`. Six namespaces, four suites. Add, toggle, toggle-all, delete, clear-completed, edit-in-place with Enter and Escape, a keyed list, chrome that comes and goes, and a filter that is three URLs rather than a key in `app-db`.

It is deliberately the second reading of one instrument. `rf2-hic-025`'s slice is the RealWorld-class arm and [its report](authoring-report-slice.md) records nine findings; **this report's first job is to say which of them a different application confirms, bounds or never meets**, and only then to add its own. A finding two independent applications hit is a fact about the surface; a finding one application hit is a fact about that application until a second one says otherwise.

> **This report records what the surface was like to use.** It proposes no renames and takes no decisions. Where a finding has an obvious remedy the remedy is named as a *candidate*, for the freeze to accept or reject.

## What the application actually needed

| Reached | Used for |
|---|---|
| `defview` | every boundary — five of them |
| `sub` | every read in all five bodies |
| `route-link` | the three filter tabs |
| `reg-state` | the per-row edit draft |
| `root!` / `render!` | the entry point and its `^:dev/after-load` hook |
| `::h/value` · `::h/checked` | the new-to-do box, the edit box, two checkboxes |
| `::h/clear` | cancelling an edit — from the view on Escape, and from the commit handler's `:fx` |
| the `:on-key-down` **key map** | Enter and Escape, as data; see finding N1 |

**Never reached:** `use-subs`, `boundary`, `unmount!`, `hfn`, `hframe`, `portal`, `as-element`, `as-component`, `defhost`, `::h/revision`, `::h/prevent`, `motion/presence`, and the whole native tier.

The load-bearing observation for the freeze is the comparison, not the list: **the Todo-class application reached a strict SUBSET of the names the slice reached.** Two independent ordinary applications, chosen to be as unlike each other as the ordinary gets, reached nine names between them and neither reached `hfn`, `hframe`, `portal`, `as-element`, `as-component` or `defhost`. The Todo app adds exactly one thing to the union that the slice did not use, and it is not a name: it is the key-map event shape.

Import discipline is asserted mechanically rather than reviewed. `surface-cljs-test` reads each application namespace's `:requires` / `:require-macros` / `:uses` / `:use-macros` off the ClojureScript analyzer — through `re-frame.hicasso.examples.require-graph`, which is now **one** macro serving both witnesses rather than the copy each bead wrote in parallel (rf2-urgk) — and pins a roster of five, with a sabotage row proving each fence predicate fires. The fifth entry against the slice's four is `clojure.string` — `trim` on a submitted title and one `join` of two class names. Worth saying out loud: the difference between the two witness applications' foreign dependencies is a standard-library namespace, not a door.

## The slice's nine, read from a second application

### 1. `::h/value` and the canonical event-vector shape — CONFIRMED, and it reaches further than the slice could see

Every write this application makes from a controlled element is positional, for the slice's reason exactly: the materializer substitutes at the intent vector's **top level**, so a marker inside the canonical `[<id> {<k> <v>}]` payload is not substituted, not refused and not linted.

What the Todo class adds is that **the door's own sugar mints an event that can never take the canonical shape.** `h/reg-state` registers its setter as `[::concern ikey v]` — positional by construction, in `impl.state`, with no map form — and that setter is what a controlled widget writes to:

```clojure
;; The edit box. There is no map spelling of this event to move toward.
:on-input [db/draft id ::h/value]
```

So the collision is not only between a convention and a materializer; for `reg-state`-backed state it is between a convention and a **registration the framework performs on the author's behalf**. Any lint or lowering-time refusal the freeze considers has to be able to say what it wants here.

### 2. `route-link` is called; everything else that makes markup is a head — CONFIRMED

Three filter tabs, three calls, inside a `for` over a table of tab data. The one-time cost was paid once and the mistake never happened. Nothing to add.

### 3. Top-level `reg-route` does not survive the supported test fixture — CONFIRMED, unchanged

All three of this application's suites that touch a route carry `:init-fn routes/register!`, and `routes.cljs` exposes `register!` for no reason an application reader can see. Met on the first row of the first suite written, exactly as the slice reported. This is a Spec 008 / `re-frame.test-support` finding rather than a Hicasso one and it now has two independent reports.

### 4. `use-subs` reads well at two reads and badly at four — NOT MET; the door was never reached for

No body here has a group worth declaring: the largest read set is three, and `todo-row`'s single read is conditional on nothing. The grouped control was never the shorter spelling and was never tried.

That is a datum rather than a finding, and it is the same datum twice: two ordinary applications, one grouped read between them. Consistent with the operator's standing ruling that grouped `use-subs` sits below the ergonomics bar.

### 5. Neither application needed a counter — the bound was right, the boundary was not (rf2-36bd)

**The datum this report contributed stands; the explanation it gave for it does not.** The Todo class contains the most ordinary reset there is, **Escape cancels an edit**, and it needs no counter and no revision at all:

```clojure
;; Escape removes the draft; a nil draft is what makes the editor absent,
;; so the field UNMOUNTS. There is nothing left to re-baseline.
:on-key-down {"Escape" [::h/clear db/draft id]}
```

What this report then concluded was that the difference is **whether the field survives its own reset** — that the slice's editor is always on the page, so its discard hands a still-mounted field the value it is already showing and the revision is what re-baselines it. It closed: *`::h/revision` is the reset door for a field that outlives the reset*.

That boundary was measured and it is in the wrong place. The slice's counter was deleted and its browser lane did not move (1474 tests, 9154 assertions, captured exit 0, identical to the control), so the slice's field **outlives its reset and still needs nothing**. The counter has since come out of the slice and its own finding 5 is withdrawn.

The line that survives the measurement is drawn elsewhere. `impl/codec`'s `revision-key` states that the whole of a revision's delivery is that its change **re-runs the body**, and React marks the resulting host update on props-object identity — so **any** re-render re-asserts the model over the DOM, drifted or not. Unmounting is therefore just one member of a larger class: every reset that moves *something the body reads* gets the re-assert for free, and an unmount is only the most emphatic way to move it.

**`::h/revision` is the door for a reset that leaves every other read the body makes `=`.** That is a much smaller population than either report supposed — a normalising or refusing field whose typed value lands back on the value the model already holds, or a DOM drifted by a route React never saw and re-asserted by a control that changes no other state.

So the two reports agree after all, and they agree on the opposite of what the slice's report first asked for: **neither of these two ordinary applications needed a counter.** The guide row should give an author the test rather than the counter — *if your reset leaves every value your body reads equal, you need `::h/revision`; if it moves any of them, React's own commit has already done it* — because on the evidence of both applications, most authors reading it will not need one.

### 6. Two clicks on one page settle differently — CONFIRMED, and it is not about servers

The slice found this on a save and a discard against a stand-in server, and its report frames the async half around "every async mutation reply in every re-frame2 application". This application has **no server, no async mutation and no `reg-fx` of its own**, and it meets the same wall: a click on a filter tab returns with the navigation merely enqueued, and `hm/settle!` — an empty `flushSync` — cannot help.

So the finding is wider than the slice could show: the missing L3 door is needed by an application that never talks to anything. The mounted suite here uses `re-frame.test-support/poll-until` through one helper, `drained`, for the same reason.

*The candidate remedy stands unchanged and belongs to rf2-hic-027:* an `hm/drain!`, or a `:until` option on `settle!`.

### 7. The virtual clock and `poll-until` cannot be used together — CONFIRMED by construction

Never reached for the clock, because nothing here waits on a duration. Recording the negative because it is the useful half: the clock's docstring is about retention deadlines, debounces and `:dispatch-later`, and an ordinary Todo application has none of the three. The two waiting mechanisms did not have to be reconciled because only one of them was ever the right instrument.

### 8. Route paths are global and route ids are not — CONFIRMED **before** it could bite

This is the finding that paid for itself. The three paths a Todo application writes are `/`, `/active` and `/completed`; all three are already registered, in this repository's node test bundle, by `examples/core/todomvc`. Writing the natural thing would have reproduced the slice's failure exactly — silently, because the ranks differ and `:rf.warning/route-shadowed-by-equal-score` has nothing to say.

Every path here is under `/hicasso-todo`, and `routes.cljs`'s docstring states why at the registration. One further consequence the slice did not have to face: **`:rf.route/not-found` is a process-global route ID**, and `examples/core/todomvc` holds it. A Todo application is told (by the routing guide, correctly) that registering a not-found route is not optional, and this one deliberately does not — an unmatched URL leaves the route id nil, which the `showing` subscription already coerces to `:all` because a URL is user input. The witness therefore cannot demonstrate the not-found registration a real application would write. That is the shared-registry hazard reaching one step past paths, and it belongs with rf2-wqnl.

### 9. Small things

- **`reg-sub`'s two-fn adjacency** — avoided by using the `:<-` chain throughout, which is what the slice recommends. Confirmed as the comfortable spelling; nothing here wanted the parametric form.
- **The `h/boundary` fallback can only be asserted as data** — not met, because the Todo class has no error region to put one in. A second application that never reached for `boundary` is itself the datum.
- **`.class` sugar folds into `:class` in the L2 attribute projection**, in that order: `[:li.todo-row {:class "completed"}]` reads back as `"todo-row completed"`. Found by a red row, cost one minute, and worth one line in `ht/attrs`'s docstring beside the `false`/`nil` note the slice asked for.

## New findings

### N1. The key map is the reason this application contains no callback, and the door never mentions it

`{"Enter" […] "Escape" […]}` at an `:on-*` prop is a first-class lowered shape — built once per render into a plain string→handler map, one `.key` lookup per event, and composition-gated centrally so an IME's Enter commits nothing. It is exactly the Todo class's shape, and it is why this application's `views.cljs` contains no `h/fn`, no `.-key` test and no `.preventDefault`.

It is also **absent from the public door.** `re-frame.hicasso`'s own docstrings enumerate the markers, `defview`, `defhost`'s `:slots` and `:callbacks`, `portal`, `as-element`, `as-component` and the root lifecycle — and never state that a map at an event position means anything at all. The four event-value shapes are written out in exactly one place a reader can reach them, `re-frame.hicasso.impl.intent`'s namespace docstring, in the namespace the door's first paragraph tells authors they never need to open. (The draft guide's `03-events-as-data.md` and `docs/design/hicasso/authoring.md` both teach it well; neither is the door, and neither is what an editor shows on `h/`.)

An author who reads only the door and needs Enter-and-Escape reaches for the one callback form, hand-writes a `.key` test, loses the composition gate, and pays a closure per row per render for what is one map lookup. The failure is a working application that is subtly wrong for every user who composes.

*Candidate remedy:* one paragraph on the door — most naturally in `defview`'s docstring beside the intent-vector example — stating the four shapes a value at an `on-*` prop may take. Nothing about the runtime changes.

### N2. A `reg-state` concern belongs to neither `subs` nor `events`, and the door does not say where to put it

`h/reg-state` mints a subscription **and** an event under one keyword, so the keyword has no home in the two-namespace split every ordinary re-frame2 application uses. Declare it in `subs` and every write reads `[::subs/draft id text]`; declare it in `events` and every read reads `(h/sub [::events/draft id])`. Half the call sites are wrong either way, and the slice's `tags-open?` shows the first form.

This application declares it in `db`, the namespace that owns the shape, because `db/draft` names a **place** rather than an action and therefore reads correctly on both sides. That is a one-sentence convention with no mechanism attached, and it is the kind of thing a facade should state once rather than let two applications answer differently.

### N3. An event handler is on neither side of `reg-state`'s pair

`reg-state` gives a widget a subscription and a setter. **An event handler has neither.** This application's commit-an-edit handler has to know two things the sugar owns, and the door offers a different-shaped answer for each:

```clojure
(rf/reg-event ::commit-edit
  (fn [{:keys [db]} [_ id]]
    ;; (a) the READ. `impl.state`'s docstring says an ordinary handler may
    ;; read `[:ui <concern> <ikey>]` — but the door exports no name for the
    ;; `:ui` root, so the literal is written out here.
    (if-some [text (get-in db [:ui db/draft id])]
      {:db …
       ;; (b) the CLEAR. The only public clear is an EVENT, so one
       ;; transition is expressed as a `:db` and a `:dispatch` rather
       ;; than as one `:db`.
       :fx [[:dispatch [::h/clear db/draft id]]]}
      {})))
```

Neither is a reach past the door — `:ui` is documented app-space and `::h/clear` is the door's own spelling — and both are places where the sugar stops one step short of the call site that needed it.

**What the clear does NOT cost, checked rather than assumed.** The obvious worry is a turn: `:dispatch` is the queued form, so the editor would close one router tick after the model moved. It does not. A Hicasso intent runs through the frame's `dispatch-sync`, and `re-frame.router/dispatch-sync!` drains synchronously-enqueued events **to fixed point** — a `:dispatch` from the seed handler's `:fx` is picked up by the same drain. Both tiers agree: the L0 row reads the draft as gone on the next line, and the mounted row finds the editor absent after one `hm/settle!`. Recorded because the cost was expected and is not there, which is worth as much as a cost that is.

So the finding is about SHAPE, not latency: one state transition is written in two effect kinds because the door publishes no `db`-level clear.

Worth noting what this bought: reading the draft from `app-db` rather than off the DOM event is what makes the handler safe to fire twice, so **Escape beats a late blur by construction** — the blur dispatches the same commit, finds no draft, and returns `{}`. The reference TodoMVC solves the same race by relying on the cancelled input unmounting before its blur can land. Modelling it was strictly better, and the only reason it took thought is that the read had to be hand-rolled.

*Candidate remedies:* export the path constructor (`h/state-path`), or a `db`-level pair — a pure reader and a pure `clear` over a `db` value — so a handler can end a widget interaction inside its own `:db`.

### N4. A `:ref` must be a stable function, and nothing on the door says so

The edit box focuses itself when it opens, which is one line of React's own `:ref` contract. Written inline in the body it is a bug with no error: a fresh closure every render is a different identity, so React detaches the old ref (calling it with `nil`) and attaches the new one **on every commit** — which for a controlled field is every keystroke. "Focus on mount" silently becomes "run on every render", and a ref that does real work — an observer, a measurement, a subscription — tears itself down and sets itself up again each time. (Focus is the forgiving case: re-focusing the already-focused element is a no-op, which is exactly why this one would have shipped.)

```clojure
;; Top-level, because its IDENTITY is what React uses to decide whether to
;; re-attach. Closing over nothing is what makes hoisting possible at all.
(def ^:private focus-on-mount
  (fn [node] (when node (.focus node))))
```

`impl.intent` states the mechanism correctly (`:ref` is excluded from lowering and keeps React's contract) and the consequence is left to be derived. There is no door-side statement, no lint check, and the symptom — a caret that jumps — names neither the ref nor the render.

A ref that closes over something per-instance cannot be hoisted at all, and this application did not need one, so it does not know what the answer there is.

### N5. `:on-submit`'s auto-prevent is exactly right, and it is why this application writes no `::h/prevent`

*Enter adds a to-do* is not key handling. It is a `<form>`, and `:on-submit` auto-prevents, so the whole of it is `{:on-submit [::events/add]}` with no key test, no `preventDefault` and no callback. Recorded as a positive: the census-weighted default did the census-weighted thing, and the marker an author would otherwise have reached for (`::h/prevent`) never appears in an ordinary Todo application at all.

### N6. What the mounted tier cannot drive, stated rather than papered over

A synthetic `KeyboardEvent` is untrusted, so it triggers no default action, so **implicit form submission cannot be driven from a test**. The flow suite calls `HTMLFormElement.requestSubmit`, which fires exactly the submit event the browser's implicit submission fires: the application's half of N5's claim is driven, and the browser's half is stated in the suite's own docstring. `:on-key-down` is a React handler and *is* driven, by a real synthetic key event, which is what makes the key-map rows above real.

This is not a Hicasso finding. It is recorded because a reader of the flow suite should know which of its rows is a proof and which is a statement.

## Where this witness is gated

The application and its four suites live under `implementation/hicasso/test/`, which is an existing shadow-cljs `:source-paths` entry (the rf2-hic-025 placement ruling, binding on this bead).

| Suite | Namespace suffix | Lane |
|---|---|---|
| surface, L0, L2 | `-cljs-test` | `:node-test` (`npm run test:cljs`) and `:node-test-hicasso` |
| flow | `-dom-cljs-test` | `:browser-test` (`npm run test:browser`), plus a stated skip on the node lane |

Both lanes arm on `implementation/hicasso/**` — `cljs_node_test` and `cljs_browser` — so every suite runs on a diff that touches this tree. `examples/require_graph.clj` is JVM-only and matches no test `ns-regexp`; it is compiled as its two consumers' macro dependency and run nowhere.
