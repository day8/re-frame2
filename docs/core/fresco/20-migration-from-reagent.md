# Migrating from Reagent

This page covers moving an application's views from Reagent to Fresco:
component definitions, Hiccup differences, local state, and React interop. It
assumes the application already uses re-frame2 events and subscriptions. If it
still uses re-frame v1 shapes, migrate those first.

The process has four steps:

1. **Generate the report.** A reporter lists every place the code needs a
   decision or a rewrite.
2. **Port one screen by hand.**
3. **Prove the port.** Shadow comparison runs the Reagent original and the
   Fresco port side by side and compares their DOM and dispatched events.
4. **Apply the codemod.** It makes only the rewrites it can prove safe from
   the source text, then you re-run the proof.

Do not start with the codemod.

## 1. Generate the migration report

The reporter runs on a JVM without loading the application. Its default mode
changes no files:

```bash
clojure -Srepro \
  -Sdeps '{:deps {day8/re-frame2-fresco-codemod
                  {:git/url   "https://github.com/day8/re-frame2.git"
                   :git/sha   "8b17cc53d517de9359f5174a0d2fcfa4748091ab"
                   :deps/root "migration/reagent-to-fresco/codemod"}}}' \
  -M -m re-frame.migration.fresco.codemod path/to/your/src/
```

Run it from your own project; it needs no checkout of re-frame2. The tool
ships only as this git dependency, not in the published Fresco artefact. The
`:git/sha` above is a known-good commit; `git ls-remote
https://github.com/day8/re-frame2.git refs/heads/main` prints the current head
if you want a newer one.

Every run writes one EDN report. `--report out.edn` chooses the path; without
it the report goes to `reagent-to-fresco-report.edn` in the parent of the first
path scanned (scan `<repo>/src/` and it lands in `<repo>/`). The run prints the
absolute path it used.

The report's first half covers `[:>]` crossings into React components.
Reagent converted props on the way through: it camel-cased nested keys, turned
keyword values into strings, wrapped `r/partial`, and read metadata keys.
Fresco does none of that, so a `[:>]` form can keep rendering while sending
different values to the component. The reporter classifies every crossing:

| Category | Named classes | Meaning |
| --- | --- | --- |
| Mechanical | W1–W6, described below | The codemod can preserve the previous behaviour from source text alone |
| Human decision | `:computed-props`, `:computed-value`, `:computed-nested-key`, `:adapt-def-site`, `:cljc-site`, `:parse-error`, `:event-carrier-goes-live`, `:key-conflict`, `:string-tag-unparseable`, `:normalized-key-collision`, `:css-var-repair`, `:named-ref`, `:amp-key` | The source does not contain enough information for a safe rewrite, or the change repairs previously broken behaviour that must be reviewed |
| Runtime blocker | `:intent-needs-a-declaration`, `:dangerous-html`, `:r>-site`, `:f>-site`, `:as-element-island`, `:reagent-api-residue` | The site will raise or silently misrender until someone chooses the correct Fresco shape |

Each entry gives the file, line and column, the source form, its
classification, a sentence on how to fix it, and the component name where it
can be found statically. The report also counts untouched sites, so a site
missing from it was seen and needed nothing.

The final suggestions block drafts `h/defhost` declarations and callback
contracts. Check each one against the component library's documentation. A
prop named like an event may be a render prop whose return value the library
uses; declaring it as `:event` replaces that return value and can blank the UI
with no useful error.

The same run also counts every call to a view-library API, such as `r/atom`,
`r/with-let`, `r/create-class` or `r/as-element`, under `:census` in the
report. That half matters because most Reagent code never crosses into React
through `[:>]`, so a codebase can have no crossing entries and still need a lot
of work. [The census](#the-census) under Advanced describes it.

## 2. Port one screen by hand

Port a complete screen, rather than changing every component declaration,
then every handler, then every crossing across the repository. A whole screen
is what shadow comparison can check.

### Two starting points

"Reagent" can mean two kinds of codebase, and they migrate differently.

A **Reagent application** writes views as `defn`s returning Hiccup, reads with
`@(rf/subscribe [:q])`, and hands `#(rf/dispatch [:x])` closures to callbacks.
That is the left-hand column of the first table below.

A **re-frame2 application on the Reagent adapter** writes views as `rf/reg-view`
forms under a `[rf/frame-root {...}]` wrapper, and reads and dispatches through
the lexical `subscribe` and `dispatch` that `reg-view` injects into every body.
It may call no Reagent API anywhere. The second table below is its column, and
it is the easier port: reads and dispatches are already data, and only the
spellings change.

The report does not count `rf/reg-view` forms, so a screen written entirely
with them may not appear in it. Find your own `reg-view` forms and work
through the second table.

### The half-migrated tree

While you port one screen at a time, Reagent and Fresco views share the same
page. That needs no second root.

**One frame serves both.** Every re-frame2 React adapter publishes the frame
through one shared React context, and Fresco views read the same context. A
shell mounted under `[rf/frame-root {:id ...}]` or
`[rf/frame-provider {:frame ...}]` supplies the frame to any Fresco view
beneath it. A ported screen needs no `h/client-root`, second frame, or second
React root.

**Keep the adapter you have.** `(rf/init! reagent-adapter/adapter)` stays as it
is; a Reagent, reagent-slim or UIx adapter can sit under a Fresco tree.
`re-frame.fresco.substrate/adapter` is only needed once the application is
finished and you want to drop the view-library dependency.

**The Reagent shell renders the ported screen through a bridge.** There are
two, and the choice depends on who owns the mount:

```clojure
(ns app.views.shell
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as h]
            [app.views.todos :as todos]))       ;; the ported screen

;; 1. h/as-element, called inside the Reagent body. The props stay
;;    ClojureScript values because they never pass through React.
(defn root-view []
  (case @(rf/subscribe [:app/page])
    :todos    (h/as-element [todos/todo-page {}])
    :settings [settings-page]                   ;; still Reagent
    [not-found]))

;; 2. h/as-component, created once at top level. Use it when the Reagent
;;    parent must key, mount and re-render the screen as a component.
(def todo-page-component (h/as-component todos/todo-page))

(defn root-view-2 []
  [:> todo-page-component {}])
```

Calling `h/as-component` inside a render creates a new component type on every
pass, so React remounts the subtree each time.

`h/client-root` and `h/render!` ([Installation](00-installation.md)) mount a
whole Fresco application. That is where the migration ends: when the last
screen is ported, the Reagent root is replaced by one Fresco client root, using
the same `client-root` / `render!` / `unmount!` names the Reagent adapter
already has, and the `frame-root` wrapper becomes `h/frame-root` in the same
place.

### Common translations

| Reagent | Fresco |
| --- | --- |
| `defn` component returning Hiccup | `h/defview`, mounted as a Hiccup head |
| `@(rf/subscribe [:q])` | `(h/sub [:q])`, including in branches, loops, and helpers |
| `#(rf/dispatch [:x])` | the event vector itself; use `h/event` when callback arguments matter |
| `r/atom` inside a Form-2 closure | app-db or the forms module; Fresco has no local-state tier |
| `r/with-let` | `h/reg-state` in app-db, or a named native component for DOM-owned state — not a body-local `let`, which re-runs every render and holds nothing across them |
| Form-3 or `r/create-class` lifecycle | callback refs or a named native component |
| `r/track`, `reaction`, `r/cursor` | layered subscriptions |
| `^{:key k}` metadata | `:key` in the props map |
| `[:> Component ...]` | remains legal; repair its prop dialect and declare repeated crossings with `h/defhost` |
| `r/adapt-react-class` | direct `[:>]` or a declared host |
| `r/as-element` inside a render prop | `h/as-element` inside an `h/event` at the render prop |
| `r/reactify-component` | `h/as-component`, the outward bridge |

The same table for the second starting point. Every row is a spelling change:

| re-frame2 on the Reagent adapter | Fresco |
| --- | --- |
| `rf/reg-view` | `h/defview`. The view is no longer registered under an id; the var is the head |
| `subscribe`, injected into a `reg-view` body | `h/sub`. A `h/defview` body binds nothing you did not write |
| `dispatch`, injected into a `reg-view` body | the event vector itself, or `h/event` when the event matters |
| `#(do (.preventDefault %) (dispatch [:e]))` | `[::h/prevent [:e]]` at the same prop |
| `[rf/route-link {...}]`, a Hiccup head | `(h/route-link {...})`, a plain call |
| `[rf/frame-root {:id :app ...}]` around the tree | `[h/frame-root {:id :app ...}]`, with the same options in the same place |

`h/frame-root` accepts every `rf/make-frame` option, as `rf/frame-root` does:
`:url-bound?`, `:fx-overrides`, `:images`, `:preset` and the rest. Frame options
belong on the head, not on `h/render!`; passing `:frame` or `:initial-events` to
`h/render!` raises `:rf.error/fresco-frame-config-misplaced`.
[Installation](00-installation.md#a-frame-that-needs-more-than-a-seed) shows the
shape, and a routed application needs `:url-bound? true`
([Routing and navigation](07-routing-and-navigation.md#boot-a-routed-application)).

`reg-view` binds `subscribe` and `dispatch` inside its body; `h/defview` binds
nothing, and a bare `rf/subscribe` in a Fresco body throws. Translate every
read and dispatch in a body you move, not only the ones the compiler flags.

Two common mistakes fail loudly:

- A Reagent-style `#(rf/dispatch ...)` callback has no captured frame when the
  browser invokes it later, so ambient dispatch raises
  `:rf.error/no-frame-context`. Use an event vector or `h/event`.
- An event vector at an `on*` prop of a `[:>]` crossing now dispatches. Under
  Reagent it crossed as an inert JavaScript array and never produced a working
  handler, so the migration turns a dead handler live; decide whether it was
  ever meant to run, and whether the prop is an event position at all rather
  than a vendor's on*-named render prop, which needs a `:callbacks` override on
  a declared host.

### Views shared across the boundary

Some views are rendered by both a ported screen and an unported one: a card,
a paginator, a todo item. Do not duplicate them; shadow comparison
([step 3](#3-prove-the-port-with-shadow-comparison)) relies on one
implementation per view.

Port the shared view once, with the first screen that needs it, and bridge it
back to the callers that are still Reagent:

```clojure
;; app.views.todo-item, now Fresco
(h/defview todo-item [{:keys [id]}]
  (let [todo (h/sub [:todo/by-id id])]
    [:li
     [:input {:type      :checkbox
              :checked   (:done? todo)
              :on-change [:todo/toggle id]}]
     (:title todo)]))

;; Created once, beside the view, for callers that have not moved yet.
(def todo-item-component (h/as-component todo-item))
```

```clojure
;; app.views.archive, still Reagent
(defn archive-page []
  [:ul.archive
   (for [{:keys [id]} @(rf/subscribe [:todo/all])]
     ^{:key id} [:> todo-item-component {:id id}])])
```

The unported caller changes by one line, and changes back to an ordinary
Hiccup head when its own screen is ported.

**Pass an id, not a value.** Props on the `h/as-component` route go through
React, so the Reagent parent converts them like any other `[:>]` crossing: a
keyword becomes its name, a map becomes a camel-cased JavaScript object, other
collections go through `clj->js`, and strings, numbers, booleans, `nil` and
functions pass unchanged. Prop names survive the round trip (`:todo-id` comes
back as `:todo-id`), but values do not. A ported view that takes an id and
reads the rest with `h/sub` never meets the conversion.

`h/as-element` does no conversion, because the props stay in ClojureScript.
Prefer it wherever the Reagent caller is an ordinary body rather than
something that must own the mount.

## 3. Prove the port with shadow comparison

`hm/shadow!` mounts the original and the port against separate copies of the
same seeded frame, drives both with one interaction script, and compares their
DOM and dispatched events at each checkpoint.

It lives in `re-frame.fresco.test.mounted`, so it needs the test kit and a
build with real React and a real DOM (level L3 in [Testing](15-testing.md)). A
project without such a test build has to set one up first. If that costs more
than the screen is worth, see
[When not to use the full process](#when-not-to-use-the-full-process).

### Keep the original alongside the port

Shadow comparison needs the Reagent original to keep compiling. For one screen
that means three namespaces:

| Namespace | Holds | Rendered by |
| --- | --- | --- |
| `app.views.todo-row-reagent` | the original, moved unchanged | the shadow test, as `:reference` |
| `app.views.todo-row` | the Fresco port | the shell, and the shadow test as `:candidate` |
| `app.views.shell` | the unported shell | the Reagent root |

Move the original into its own namespace rather than giving the port a second
name. Every caller then points at the port, including unported callers
through a bridge ([Views shared across the boundary](#views-shared-across-the-boundary)),
and removing the original at the end means deleting one file. A caller left
pointing at the original is a screen that never migrates.

### The comparison

```clojure
(ns app.migration.todo-row-shadow
  (:require [reagent.core :as r]
            [re-frame.fresco.test.mounted :as hm]
            [app.views.todo-row-reagent :as old]
            [app.views.todo-row :as new]))

;; Create once, at top level: a component created per render is a new
;; element type and remounts the subtree.
(def old-todo-row (r/reactify-component old/todo-row))

(hm/shadow!
 {:reference      [:> old-todo-row {:id 1}]
  :candidate      [new/todo-row {:id 1}]
  :initial-events [[:todo/initialise]]
  :script         [{:click "button.edit"}
                   {:type  ["input.title" "Buy oat milk"]}
                   {:click "button.save"}]})
;; => {:status :green :checkpoints 4}
```

Fresco mounts both sides, so the original enters the way any foreign React
component does: through `[:>]` or a declared `h/defhost`. A Reagent `defn` in
head position raises an error instead of rendering, which is why `:reference`
is a `[:>]` crossing and `:candidate` is a plain Hiccup head.

That crossing shapes how you write the pair:

- **Use single-word props.** Fresco camel-cases prop names on the way out, so
  `:todo-id` reaches a reactified Reagent component as `:todoId`. An `:id`
  both sides agree on, plus a seeded frame both sides read, avoids the
  question.
- **Give the original its callbacks as event vectors.** A declared callback
  contract turns them into functions bound to that mount's frame, which is how
  the original reaches its frame at all. Its own `#(rf/dispatch ...)` closures
  capture no frame, as step 2 describes.

Each side has its own frame copy, so writes cannot leak between them. If the
two dispatch different events at the first checkpoint, their state and DOM
diverge from there, which points at the original cause.

A red result names the checkpoint and the DOM node or event that differs. When
the difference comes from a declared policy, such as a Client-only region, the
report names the policy.

A green result covers only the flows in the script, so script the screen's
real behaviour rather than one happy click. Before trusting the comparator,
break the candidate on purpose and confirm the run turns red at the expected
checkpoint.

Omit `:script` during interactive development. The call returns a handle, both
mounts stay live, and nothing is compared until you ask: drive both by hand,
call `:checkpoint!` at each point you want compared, and `:stop!` when done.

```clojure
(let [s (hm/shadow! {:reference [:> old-todo-row {:id 1}]
                     :candidate [new/todo-row {:id 1}]})]
  ;; drive the page by hand, then take a reading
  ((:checkpoint! s))   ;; => {:status :green :checkpoints 1}
  ((:stop! s)))
```

Each `:checkpoint!` call settles both mounts, compares them, and numbers the
reading; `:stop!` takes both mounts down.

Shadow comparison checks DOM and dispatched events. It does not check focus,
caret, IME, layout, or paint; use the browser levels in
[Testing](15-testing.md) for those.

When the screen is green and its browser tests pass, delete
`app.views.todo-row-reagent` and the shadow test together. Check first that no
caller still points at the original.

## 4. Apply the mechanical codemod

```bash
clojure -Srepro \
  -Sdeps '{:deps {day8/re-frame2-fresco-codemod
                  {:git/url   "https://github.com/day8/re-frame2.git"
                   :git/sha   "8b17cc53d517de9359f5174a0d2fcfa4748091ab"
                   :deps/root "migration/reagent-to-fresco/codemod"}}}' \
  -M -m re-frame.migration.fresco.codemod --rewrite src/
```

This is the command from [step 1](#1-generate-the-migration-report) with
`--rewrite` added. As written it is a dry run; add `--write` after `--rewrite`
to change the files.

The codemod preserves formatting, comments, and line endings, including CRLF.
A completed run exits 0 even when the report lists human decisions; it is a
migration assistant, not a build lint.

It applies six rewrites:

| Rewrite | Input | Output | Behaviour preserved |
| --- | --- | --- | --- |
| W1 | `^{:key k}` metadata on a vector | `:key k` in the props map | Reagent read metadata; Fresco reads props |
| W2 | Literal nested prop maps | The same map with literal keys camel-cased | Reagent deep-camel-cased nested keys; Fresco passes them by identity |
| W3 | Literal keyword or quoted-symbol prop value | Its `name` as a string | Reagent named these values; namespaced keywords lost their namespace there too |
| W4 | Literal `(r/partial f a ...)` prop | Hygienic `let` capture plus function wrapper | Reagent evaluated the callee and captured args once at construction |
| W5 | `[(r/adapt-react-class X) ...]` | `[:> X ...]` | Same native React element path in Fresco syntax |
| W6 | `[:> "tag" ...]` for a plain HTML tag | `[:tag ...]` | Moves the native element onto Fresco's normal, controlled-element path |

A rewrite runs only when both the old and new behaviour can be read from the
literal source. A prop that merely looks like an event never triggers a
callback rewrite. Everything else stays in the report.

A second run leaves the files byte-for-byte unchanged. Re-run shadow
comparison on the screens the diff touched.

## What remains manual

### Host declarations and callback contracts

Only the component library knows whether a prop is an event, a plain
handler, a render callback, or a ReactNode slot, so the codemod cannot decide
it. Review every `h/defhost` declaration yourself.

### Runtime blockers

Examples:

- `:intent-needs-a-declaration`: decide whether the event-shaped prop is an
  event position, or a render prop the vendor named `on*`;
- `:dangerous-html`: Reagent may have discarded the prop while Fresco will
  pass it through, turning dead behaviour live;
- `:r>-site` and `:f>-site`: Fresco can interpret these as unknown tag
  keywords, so port them explicitly;
- `:as-element-island`: a callback runs outside the original render window,
  so replacing `r/as-element` is not a text substitution.

### Local state and lifecycle

`r/atom`, cursors, Form-2, and Form-3 components need you to decide where the
state lives: app-db, a forms address, or native widget state. See
[Ephemeral state](11-ephemeral-state.md).

### Computed values

A map built with `merge`, a prop value held in a symbol, or a key computed at
runtime hides the real prop shape from a source-only tool. The reporter
records the site instead of guessing.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| A `[:>]` site renders but behaves differently | Reagent converted the prop dialect and Fresco passes values by identity | Run the reporter and apply the safe codemod rewrites |
| A former Reagent crossing starts dispatching at an `on*` prop | An event vector that crossed as inert data under Reagent now dispatches, as it does on a native tag | Decide whether the handler was ever meant to run; if the prop is a vendor's on*-named render prop, declare the host with `{:callbacks {… :render}}` |
| Callback runs and raises `:rf.error/no-frame-context` | A hand-written dispatch closure did not capture a frame | Replace it with an event vector or `h/event` |
| A keyed list remounts once immediately after migration | A key collision that Reagent normalised now becomes two distinct values | Accept the one-time transition when the new stable key is correct |
| Codemod refuses a nested map with `:normalized-key-collision` | Keys such as `:foo-bar` and `:fooBar` collapsed onto one Reagent output property | Remove the unintended duplicate and rerun |
| W2 camel-cases keys in what looks like application data | Reagent already sent that library a camel-cased object | Do not revert unless you intentionally want different library input |
| Shadow comparison is red only in a Client-only region | The difference follows a declared server/client policy | Choose `{:server :render}`, provide a fallback, or accept the classified difference |
| Shadow is green but focus or IME differs | Shadow comparison does not test browser-only behaviour | Run L4 browser tests |
| A second codemod run changes files | The input changed between runs or another tool edited the output; the codemod itself is idempotent | Compare against the report coordinates and rerun the reporter |

## When not to use the full process

- For a very small application, run the reporter and port by hand. A shadow
  harness may cost more than reviewing a handful of screens.
- Keep a React-first screen in raw React or UIx instead of converting it to
  Hiccup on principle ([Islands](10-native-tier.md)).
- When a screen is being redesigned, shadow comparison cannot check the
  intended change. Spend that effort on screens that must behave the same.

## Advanced

### The census

The report's `:entries` cover `[:>]` crossings. Its `:census` counts every
call to a view-library API: `r/atom`, `r/with-let`, `r/create-class`,
`r/as-element`, `r/cursor`, `r/reactify-component`, root mounting, and the
rest. The two halves measure different things, and neither is a denominator
for the other.

The census reads two lists of namespaces. The first is Reagent's: stock
Reagent's namespaces and the `reagent2.*` ones the reagent-slim adapter
ships. The second is re-frame2's own adapters, every namespace starting with
`re-frame.adapter.`, which catches code on the Reagent adapter that calls no
Reagent API itself.

Only code that runs is counted. `#_(r/atom 0)`, `'(r/atom 0)` and
`(comment (r/atom 0))` are skipped. A syntax-quote is counted, because a
macro's template produces a real call at every expansion.

Each census class carries a recovery note in the report:

| Verdict | Named classes | Meaning |
| --- | --- | --- |
| Human decision | `:with-let`, `:cell-disposal`, `:outward-bridge`, `:adapt-react-class`, `:react-create-element`, `:props-helper`, `:reagent-partial`, `:render-control`, `:root-mount`, `:static-markup`, `:substrate-read-hook`, `:substrate-view-seam`, `:substrate-test-seam`, `:substrate-test-harness` | A Fresco translation exists, but which one depends on intent the source does not carry |
| Runtime blocker | `:local-reactive-cell`, `:derived-cell`, `:reactive-graph-control`, `:lifecycle-class`, `:as-element`, `:component-introspection` | Fresco has no equivalent tier, so the site raises or silently misrenders until someone chooses the shape |
| Mechanical | none | Always emitted as `:mechanical 0`. Every mechanical rewrite is a W-rule, and W-rules apply only at crossings |

Two more runtime-blocker classes report a namespace the tool could not
resolve. A namespace that uses a Reagent name without being Reagent, such as a
vendored copy, is reported as `:unresolved-reagent-require` at its `ns` form,
and each call through it as `:unresolved-alias`. The tool does not assume the
copy is `reagent.core`, because a wrong guess would rewrite working code.

The census follows every way of binding a Reagent name: an alias, `:refer`,
`:refer :all`, `:rename`, and any of them inside a reader conditional. A
renamed var is reported under its Reagent name. After
`:refer [atom] :rename {atom ratom}`, `(ratom 0)` is Reagent's and a bare
`(atom 0)` is `clojure.core`'s.

The census cannot see shapes that have no marker. A Form-2 component is a
`defn` returning a `fn`, so the census counts the `r/atom` it closes over but
reports nothing about the Form-2 shape itself.

### Report entry shape

```clojure
{:file   "src/app/views.cljs"
 :line   42
 :col    5
 :form   "[:> Btn {:variant :contained} \"Save\"]"
 :head   "Btn"
 :action :rewrote
 :detail {:prop :variant
          :was  :contained
          :now  "contained"}
 :note   "Reagent named every keyword prop value; Fresco keeps the keyword
          except at HTML-attribute slots."}
```

Coordinates always refer to the input file, in report and rewrite modes.

### Why W4 captures with `let`

This rewrite is wrong:

```clojure
(fn [& args]
  (apply f @snapshot args))
```

It re-evaluates `f` and `@snapshot` on each callback invocation. Reagent's
`r/partial` evaluated the callee and arguments once when the prop was built.
The codemod therefore emits a capture:

```clojure
[:> Btn {:on-pick (r/partial handler @selection)}]
;; =>
[:> Btn
 {:on-pick
  (let [f__rf2  handler
        a0__rf2 @selection]
    (fn [& args__rf2]
      (apply f__rf2 a0__rf2 args__rf2)))}]
```

Names are deterministic and checked against symbols already present at the
site. A collision increments the generated suffix for the complete family.

### Deliberate divergences left in place

The migration tools do not try to remove every semantic difference:

- class collections now coerce and compose under every accepted spelling;
- nested class collections flatten;
- unsafe object keys such as `__proto__`, `prototype`, and `constructor` are
  dropped instead of written;
- a literal `nil` in a child position remains a child value and React renders
  nothing for it.
