# Migrating from Reagent

This page covers the view-layer migration from Reagent to Hicasso: component
definitions, Hiccup differences, local state, and React interop.

It assumes the application already uses re-frame2 events and subscriptions. If
it still uses re-frame v1 shapes, complete that migration first.

Use three migration tools in this order:

1. **Reporter** — classify every foreign React crossing, and census every
   rostered view-substrate API call site, as mechanical rewrites, human
   decisions, and runtime blockers.
2. **Shadow comparison** — run the Reagent original and Hicasso port side by
   side and compare canonical DOM and intent streams.
3. **Codemod** — apply only source transformations whose behaviour is
   decidable from the code.

Do not start with the codemod. Read the report, port and prove a screen, then
apply mechanical edits and run the proof again.

## 1. Generate the migration report

The reporter runs on a JVM without loading the application. Its default mode
changes no files:

```bash
cd re-frame2/migration/reagent-to-hicasso/codemod

clojure -M:run path/to/your/src/
clojure -M:run --report out.edn src/
```

Every run writes one EDN report, a scan that changes no source included.
Without `--report` it goes to `reagent-to-hicasso-report.edn` beside the first
path scanned — point the tool at `<repo>/src/` and the report lands at
`<repo>/` — and the run prints the absolute path it used.

Reagent converted props crossing through `[:>]`. Among other behaviours, it
camel-cased nested keys, converted keyword values to names, wrapped
`r/partial`, and read metadata keys. Hicasso does not perform that conversion.
A `[:>]` form may therefore continue rendering while sending different values
to the component.

The reporter classifies every crossing:

| Category | Named classes | Meaning |
| --- | --- | --- |
| Mechanical | W1–W6, described below | The codemod can preserve the previous behaviour from source text alone |
| Human decision | `:computed-props`, `:computed-value`, `:computed-nested-key`, `:adapt-def-site`, `:cljc-site`, `:parse-error`, `:event-carrier-goes-live`, `:key-conflict`, `:string-tag-unparseable`, `:normalized-key-collision`, `:css-var-repair`, `:named-ref`, `:amp-key` | The source does not contain enough information for a safe rewrite, or the change repairs previously broken behaviour that must be reviewed |
| Runtime blocker | `:intent-needs-a-declaration`, `:dangerous-html`, `:r>-site`, `:f>-site`, `:as-element-island`, `:reagent-api-residue` | The site will raise or silently misrender until someone chooses the correct Hicasso shape |

The report is deterministic EDN. Each entry includes:

- file, line, and column;
- the source form as text;
- its classification;
- a recovery sentence;
- the component name where it can be recovered statically.

It is written even when no problematic sites exist and includes the count of
untouched sites, so absence from the report is meaningful.

The final suggestions block contains possible `h/defhost` declarations and
possible callback contracts. Treat them as drafts. A prop named like an event
may actually be a render prop whose return value the library consumes. For
example, declaring a render prop as `:event` can replace its return value with
a dispatch path and blank the UI without a useful runtime error. Confirm every
contract against the component library's documentation.

### The report's second half: the census

Everything above describes the **fixer**, and its population — the `[:>]`
family — is not what a Reagent codebase is mostly made of. Run over this
repository's own 88-file example corpus the fixer reports **zero entries**,
because the corpus crosses into React nowhere. A migrator reading only that
sees 88 files the report never mentions.

So the same run also emits a **census**, under `:census`, whose population is
the view-substrate API **call site**: `r/atom`, `r/with-let`, `r/create-class`,
`r/as-element`, `r/cursor`, `r/reactify-component`, root mounting, and the rest
of the two rosters. On that same corpus it reports many, in files the fixer half
never named. No count is quoted here on purpose: the census figure moves whenever
the corpus grows *or* the rosters widen, and a figure pinned to a commit goes
stale on a change neither of those announces. Run it and read your own.

There are two rosters, because a re-frame2 application on the Reagent adapter
calls no Reagent API of its own and a Reagent-only census scored it at zero.
The first is Reagent's API — stock Reagent's namespaces, and the `reagent2.*`
ones the reagent-slim adapter ships. The second is re-frame2's own substrate
adapters: everything under `re-frame.adapter.`, matched as a prefix anchored at
the start of the namespace, because the adapter set is open and a list of
today's adapters goes stale into the same silent zero tomorrow.

A **call site is source that runs**. `#_(r/atom 0)`, `'(r/atom 0)` and
`(comment (r/atom 0))` parse into the same nodes a live call does, and the
census prunes all three rather than counting them. A syntax-quote is not
pruned: a macro's template emits real call sites at every expansion.

| Half | Population | Addressed at | Verdicts |
| --- | --- | --- | --- |
| fixer (`:entries`) | `[:>]`-family crossing sites | the site | rewrote, or refused in the classes above |
| census (`:census`) | view-substrate API call sites | the call | `:mechanical`, `:human-decision`, `:runtime-blocker` |

The two halves measure different things and neither is a denominator for the
other. §2's translation tables teach some of these shapes and not others; the
census carries a recovery note for every class it emits:

| Verdict | Named classes | Meaning |
| --- | --- | --- |
| Human decision | `:with-let`, `:cell-disposal`, `:outward-bridge`, `:adapt-react-class`, `:react-create-element`, `:props-helper`, `:reagent-partial`, `:render-control`, `:root-mount`, `:static-markup`, `:substrate-read-hook`, `:substrate-view-seam`, `:substrate-test-seam`, `:substrate-test-harness` | A Hicasso translation exists, but which one depends on intent the source does not carry |
| Runtime blocker | `:local-reactive-cell`, `:derived-cell`, `:reactive-graph-control`, `:lifecycle-class`, `:as-element`, `:component-introspection` | Hicasso has no equivalent tier, so the site raises or silently misrenders until someone chooses the shape |
| Mechanical | none | The bucket is always emitted, at `:mechanical 0`. Every mechanical rewrite this tool family knows is a W-rule and every W-rule sits at a crossing, so the zero is a measurement rather than an omission |

Two further classes report a resolution failure rather than a translation, both
as runtime blockers. A namespace that spells a Reagent name without being
Reagent's — a vendored inlined copy, for instance — is
`:unresolved-reagent-require` at the `ns` form, and every roster-named call in
that file is `:unresolved-alias`. The tool does not guess that such a copy is
`reagent.core`, because a wrong binding rewrites working code.

Every legal way of binding the Reagent name is read: an alias, a `:refer` list,
`:refer :all`, a `:rename`, and any of them behind a reader conditional. A
`:rename` reports under the roster name rather than the local spelling, and it
releases the original — after `:refer [atom] :rename {atom ratom}`, `(ratom 0)`
is Reagent's and a bare `(atom 0)` is `clojure.core`'s.

What it cannot name it does not count, and says so. A Form-2 component is a
`defn` returning a `fn` with nothing else marking it, so the census counts the
`r/atom` that component closes over and reports nothing about the shape itself.

## 2. Port one screen by hand

Migrate a complete screen rather than changing all component declarations,
then all handlers, then all crossings across the repository. A screen-level
port gives shadow comparison a useful unit.

### Two starting points

"Reagent" names two codebases here, and they do not migrate the same way.

A **Reagent application** writes views as `defn`s returning Hiccup, reads with
`@(rf/subscribe [:q])`, and hands `#(rf/dispatch [:x])` closures to callbacks.
That is the left-hand column of the first table below.

A **re-frame2 application on the Reagent adapter** writes views as `rf/reg-view`
forms under a `[rf/frame-root {...}]` wrapper, and reads and dispatches through
the lexical `subscribe` and `dispatch` that `reg-view` injects into every body.
It may call no Reagent API anywhere. The second table below is its column, and
it is the easier of the two ports: the reads and the dispatches are already
data, and only the spellings move.

§1's "absence from the report is meaningful" is a statement about the
populations the report counts. The census reads re-frame2's own
`re-frame.adapter.` surface as well as Reagent's, but `rf/reg-view` is
re-frame.core's and sits on neither roster, so a screen written entirely in
`reg-view` forms can still go unmentioned. Count your own `reg-view` forms and
work the second table.

### The half-migrated tree

Porting a screen at a time means the two view layers share one page for the
length of the migration. Three facts make that work, and none of them asks for
a second root.

**One frame serves both halves.** Every React-shaped adapter in re-frame2
publishes the frame through one shared React context, and a Hicasso boundary
reads that same context. A shell already mounted under `[rf/frame-root {:id
...}]` — or under `[rf/frame-provider {:frame ...}]` — therefore supplies the
frame to any Hicasso subtree beneath it. A ported screen needs no `h/mount!`,
no second frame, and no second React root.

**Keep the adapter you have.** `(rf/init! reagent-adapter/adapter)` stays as it
is: installing a Reagent, reagent-slim or UIx adapter under a Hicasso tree is
supported. `re-frame.hicasso.substrate/adapter` is what lets a *finished*
application drop its view-library dependency; it is not a prerequisite for
rendering a Hicasso view.

**The unported shell reaches the ported screen through a bridge.** There are
two doors, and the choice is about who owns the mount:

```clojure
(ns app.views.shell
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [app.views.feed :as feed]))         ;; the ported screen

;; Door 1 — h/as-element, called inside the Reagent body. The props never
;; cross React's prop channel, so they stay ClojureScript values.
(defn root-view []
  (case @(rf/subscribe [:app/page])
    :feed    (h/as-element [feed/feed {:page 0}])
    :profile [profile-page]                     ;; still Reagent
    [not-found]))

;; Door 2 — h/as-component, minted ONCE at top level, beside the view it
;; bridges. Reach for it when the Reagent parent must key, mount and
;; re-render the screen as a component.
(def feed-component (h/as-component feed/feed))

(defn root-view-2 []
  [:> feed-component {:page 0}])
```

Minting the component inside a render would allocate a fresh element type on
every pass and remount the subtree, which is `React.memo`'s own law rather than
a Hicasso rule.

`h/mount!` ([Installation](00-installation.md)) is the whole-application door.
It is where the migration ends rather than where it starts: when the last
screen is ported, the Reagent root and its `frame-root` wrapper give way to one
`h/mount!` naming the same frame.

### Common translations

| Reagent | Hicasso |
| --- | --- |
| `defn` component returning Hiccup | `h/defview`, mounted as a Hiccup head |
| `@(rf/subscribe [:q])` | `(h/sub [:q])`, including in branches, loops, and helpers |
| `#(rf/dispatch [:x])` | the event vector itself; use `h/event` when callback arguments matter |
| `r/atom` inside a Form-2 closure | app-db or the forms module; Hicasso has no local-state tier |
| `r/with-let` | ordinary `let`; durable state belongs outside render |
| Form-3 or `r/create-class` lifecycle | callback refs or a named native component |
| `r/track`, `reaction`, `r/cursor` | layered subscriptions |
| `^{:key k}` metadata | `:key` in the props map |
| `[:> Component ...]` | remains legal; repair its prop dialect and declare repeated crossings with `h/defhost` |
| `r/adapt-react-class` | direct `[:>]` or a declared host |
| `r/as-element` inside a render prop | `h/as-element` inside an `h/event` at the render prop |
| `r/reactify-component` | `h/as-component`, the outward bridge |

The same table for the second starting point. Every row but the last is a
spelling change; none of those is a change of shape:

| re-frame2 on the Reagent adapter | Hicasso |
| --- | --- |
| `rf/reg-view` | `h/defview`. The view is no longer registered under an id; the var is the head |
| `subscribe`, injected into a `reg-view` body | `h/sub`. A `h/defview` body binds nothing you did not write |
| `dispatch`, injected into a `reg-view` body | the intent vector itself, or `h/event` when the event matters |
| `#(do (.preventDefault %) (dispatch [:e]))` | `[::h/prevent [:e]]` at the same prop |
| `[rf/route-link {...}]`, a Hiccup head | `(h/route-link {...})`, a plain call |
| `[rf/frame-root {:id :app ...}]` around the tree | `h/mount!`'s `{:frame :app ...}` root configuration, once the whole application is ported. Not a rename when the `frame-root` carries frame options — see below |

That last row is the exception, and it fails silently. `rf/frame-root` passes
every `rf/make-frame` option through — `:url-bound?`, `:fx-overrides`, `:images`,
`:preset` and the rest of the record config — while `h/mount!`'s config is closed
at `:frame`, `:initial-events` and `:identifier-prefix`, and ensures the frame as
an id plus that seed. Every other key in the mount config is ignored without
complaint, so a `frame-root` carrying more than an id and a seed does not survive
being respelled as one. Create the frame with `rf/make-frame` and its full
options first, and let the mount join it — that is what mounting does whenever
the frame is already live.
[Installation](00-installation.md#a-frame-that-needs-more-than-a-seed) shows the
shape; a routed application needs `:url-bound? true`, so this is the common path
rather than an edge
([Routing and navigation](07-routing-and-navigation.md#boot-a-routed-application)).

A `reg-view` body's `subscribe` and `dispatch` are lexical bindings the macro
installs. `h/defview` installs none, so the same source text means something
different under it: a bare `rf/subscribe` in a Hicasso body throws rather than
resolving. Translate every read and every dispatch in a body you move, not only
the ones the compiler complains about.

Two common mistakes fail loudly:

- A Reagent-style `#(rf/dispatch ...)` callback has no captured frame when the
  browser invokes it later, so ambient dispatch raises
  `:rf.error/no-frame-context`. Use an intent vector or `h/event`.
- An event vector at an `on*` prop of a `[:>]` crossing now dispatches. Under
  Reagent it crossed as an inert JavaScript array and never produced a working
  handler, so the migration turns a dead handler live; decide whether it was
  ever meant to run, and whether the prop is an event position at all rather
  than a vendor's on*-named render prop, which needs a `:callbacks` override on
  a declared host.

### Views shared across the boundary

In a real application at least one view is rendered by both a ported screen and
an unported one — a card, a paginator, an avatar. "Port a complete screen"
does not say what happens to it, and duplicating it is the one answer
[§3](#3-prove-the-port-with-shadow-comparison) rules out.

Port the shared view once, with the first screen that needs it, and bridge it
back out to the callers that are still Reagent:

```clojure
;; app.views.article-preview — now Hicasso
(h/defview article-preview [{:keys [id]}]
  (let [article (h/sub [:article id])]
    [:article.preview
     [:h2 (:title article)]
     [:button {:on-click [:article/favourite id]} "Favourite"]]))

;; Minted once, beside the view, for the callers that have not moved.
(def article-preview-component (h/as-component article-preview))
```

```clojure
;; app.views.profile — still Reagent, still a defn
(defn profile-page []
  [:div.profile
   (for [id @(rf/subscribe [:profile/article-ids])]
     ^{:key id} [:> article-preview-component {:id id}])])
```

The boundary lands on the **view**, so there is one implementation and nothing
to keep in step. The unported caller changes by one line, and it changes again
— back to an ordinary Hiccup head — on the day that screen is ported.

**Cross an id, not a value.** Props on the `h/as-component` route travel through
React, so the Reagent parent converts them on the way in exactly as it converts
any other `[:>]` crossing: a keyword becomes its name, a map becomes a
camel-cased JavaScript object, any other collection is deeply `clj->js`'d, and
strings, numbers, booleans, `nil` and functions cross unchanged. Prop *names*
survive the round trip — `:article-id` is camel-cased on the way out and read
back as `:article-id` — but values do not. A ported view handed an id, reading
the rest with `h/sub`, never meets the conversion at all, and that is the shape
re-frame2 wants anyway.

`h/as-element` performs no such conversion, because the props stay inside
ClojureScript. Prefer it wherever the Reagent caller is an ordinary body rather
than something that must own the mount.

## 3. Prove the port with shadow comparison

`hm/shadow!` mounts the original and candidate against isolated copies of the
same seeded frame. One interaction script drives both implementations. At
each checkpoint it compares canonical DOM and the intent stream.

### What this step needs first

`hm/shadow!` lives in `re-frame.hicasso.test.mounted`, so it is an L3 door: it
needs the test kit on the classpath and a build target that gives it real React
and a real DOM. [Testing](15-testing.md) carries the kit setup and the level
ladder. A project with no L3 lane has to stand one up before this step, not as
part of it — and if that is more than the screen is worth,
[When not to use the full process](#when-not-to-use-the-full-process) says so.

### The intermediate state

Shadow comparison needs the Reagent original still compiling and still
mountable, which is a state the rest of this page does not show. For one screen
it is three namespaces:

| Namespace | What it holds | Who renders it |
| --- | --- | --- |
| `app.views.article-row-reagent` | the original, moved verbatim and otherwise untouched | the shadow test, as `:reference` |
| `app.views.article-row` | the Hicasso port | the shell, and the shadow test as `:candidate` |
| `app.views.shell` | the unported shell | the Reagent root |

Move the original into a namespace of its own rather than putting the port
beside it under a second name. Every caller then points at one name, the port,
and deleting the original at the end is deleting a file.

Point the callers at the **port**, including the ones that have not been ported
themselves — bridging them out is what
[Views shared across the boundary](#views-shared-across-the-boundary) is for.
The original exists for the comparator and for nothing else; a caller left
pointing at it is a screen that never migrates.

### The comparison

```clojure
(ns app.migration.article-row-shadow
  (:require [reagent.core :as r]
            [re-frame.hicasso.test.mounted :as hm]
            [app.views.article-row-reagent :as old]
            [app.views.article-row :as new]))

;; Once, at top level, for the same reason h/as-component is: a component
;; allocated per render is a new element type and remounts the subtree.
(def old-article-row (r/reactify-component old/article-row))

(hm/shadow!
 {:reference      [:> old-article-row {:id 7}]
  :candidate      [new/article-row {:id 7}]
  :initial-events [[:demo/install-fixture]]
  :script         [{:click "button.edit"}
                   {:type  ["input.title" "Better title"]}
                   {:click "button.save"}]})
;; => {:status :green :checkpoints 4}
```

Both sides are mounted by Hicasso, so the original arrives the way every
foreign component arrives: through a `[:>]` crossing or a declared `h/defhost`,
the same door the translation table above already sends it through. A Reagent
`defn` written directly in head position is a loud refusal rather than a
Reagent render, which is why `:reference` is a crossing and `:candidate` is a
plain Hiccup head.

Two consequences of that crossing decide how the pair is written.

- **Cross single-word props.** Hicasso camel-cases the key on the way out and
  a reactified Reagent component reads back the name React actually carried, so
  `:article-id` reaches the original as `:articleId`. An id both sides agree on
  — and a seeded frame both sides read — avoids the question and makes the
  comparison worth taking.
- **Hand the original its callbacks as intent vectors.** A declared callback
  contract lowers them into functions closed over that mount's frame, which is
  how a foreign original reaches the frame at all. The original's own
  `#(rf/dispatch ...)` closures capture no frame, exactly as §2 says.

Each side receives its own frame copy, so writes cannot leak between the two
implementations. A different intent at the first checkpoint causes the states
and later DOM to diverge independently, which makes the original cause visible.

A red result identifies the checkpoint and the exact DOM node or intent that
differs. When the difference follows a declared policy, such as a Client-only
region, the report identifies the policy instead of presenting it as an
unexplained DOM mismatch.

A green result means the implementations matched for the flows in the script.
It does not prove untested paths. Script the real screen behaviour rather than
a single happy click.

Add a sabotage control before trusting the comparator: deliberately change a
candidate prop and confirm the run turns red at the expected checkpoint.

Omit `:script` for interactive development. Both mounts stay live and the call
answers a handle rather than a verdict. **No checkpoint is taken
automatically.** A reading per committed render is not built, because the
runtime publishes no commit callback, so nothing is compared until you ask for
it. Retain the handle, drive both mounts by hand, call `:checkpoint!` at each
point you want compared, and `:stop!` when you are finished.

```clojure
(let [s (hm/shadow! {:reference [:> old-article-row {:id 7}]
                     :candidate [new/article-row {:id 7}]})]
  ;; drive the page by hand, then take a reading
  ((:checkpoint! s))   ;; => {:status :green :checkpoints 1}
  ((:stop! s)))
```

Each `:checkpoint!` call settles both mounts, compares them, and numbers the
reading; `:stop!` takes both mounts down.

Shadow comparison covers canonical DOM and intent streams. It does not prove
focus, caret, IME, layout, or paint behaviour. Use the browser levels from
[Testing](15-testing.md) for those claims.

When the screen is green and its browser tests pass, delete
`app.views.article-row-reagent` and the shadow test together: the comparator is
the only thing the original was still for, and keeping both copies invites
future divergence. Check first that no caller still points at the original —
a shared view reaches its unported callers through the bridge, not through the
Reagent copy.

## 4. Apply the mechanical codemod

```bash
clojure -M:run --rewrite src/
clojure -M:run --rewrite --write src/
```

The first command is a dry run. The second writes files.

The codemod uses a lossless parser and preserves formatting, comments, and line
endings, including CRLF. A completed run exits 0 even when the report contains
human decisions; the tool is a migration assistant, not a permanent build
lint.

It applies six rewrite families:

| Rewrite | Input | Output | Behaviour preserved |
| --- | --- | --- | --- |
| W1 | `^{:key k}` metadata on a vector | `:key k` in the props map | Reagent read metadata; Hicasso reads props |
| W2 | Literal nested prop maps | The same map with literal keys camel-cased | Reagent deep-camel-cased nested keys; Hicasso passes them by identity |
| W3 | Literal keyword or quoted-symbol prop value | Its `name` as a string | Reagent named these values; namespaced keywords lost their namespace there too |
| W4 | Literal `(r/partial f a ...)` prop | Hygienic `let` capture plus function wrapper | Reagent evaluated the callee and captured args once at construction |
| W5 | `[(r/adapt-react-class X) ...]` | `[:> X ...]` | Same native React element path in Hicasso syntax |
| W6 | `[:> "tag" ...]` for a plain HTML tag | `[:tag ...]` | Moves the native element onto Hicasso's normal, controlled-element path |

A transformation runs only when both old and new behaviour can be determined
from the literal source. Event-like prop spelling never authorises a callback
rewrite; it can only make the tool more conservative. Everything else remains
in the report.

The output of each rewrite is outside that rewrite's input language, so a
second run should be byte-for-byte unchanged. Re-run shadow comparison on the
screens touched by the diff.

## What remains manual

### Host declarations and callback contracts

Only the component library defines whether a prop is an event, plain handler,
render callback, or ReactNode slot. The codemod cannot infer that semantic
contract safely. Review and approve every `h/defhost` declaration.

### Runtime blockers

Examples:

- `:intent-needs-a-declaration`: decide whether the event-shaped prop is an
  event position, or a render prop the vendor named `on*`;
- `:dangerous-html`: Reagent may have discarded the prop while Hicasso will
  pass it through, turning dead behaviour live;
- `:r>-site` and `:f>-site`: Hicasso can interpret these as unknown tag
  keywords, so port them explicitly;
- `:as-element-island`: a callback runs outside the original render window,
  so replacing `r/as-element` is not a text substitution.

### Local state and lifecycle

`r/atom`, cursors, Form-2, and Form-3 structures require a state-ownership
decision. Use the homes in [Ephemeral state](11-ephemeral-state.md). No codemod
should decide whether a fact belongs in app-db, a forms address, or native
widget state.

### Computed values

A map produced by `merge`, a prop value reached through a symbol, or a key
computed at runtime does not reveal the actual crossing shape to a source-only
tool. The reporter records the site rather than guessing.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| A `[:>]` site renders but behaves differently | Reagent converted the prop dialect and Hicasso passes values by identity | Run the reporter and apply the safe codemod rewrites |
| A former Reagent crossing starts dispatching at an `on*` prop | An intent vector that crossed as inert data under Reagent is lowered by Hicasso, exactly as on a native tag | Decide whether the handler was ever meant to run; if the prop is a vendor's on*-named render prop, declare the host with `{:callbacks {… :render}}` |
| Callback runs and raises `:rf.error/no-frame-context` | A hand-written dispatch closure did not capture a frame | Replace it with an intent vector or `h/event` |
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
- When a screen is being redesigned, shadow comparison cannot prove intended
  behavioural change. Spend identity-proof effort on screens that must remain
  unchanged.

## Advanced

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
 :note   "Reagent named every keyword prop value; Hicasso keeps the keyword
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
[:> Btn {:on-pick (r/partial handler @cart)}]
;; =>
[:> Btn
 {:on-pick
  (let [f__rf2  handler
        a0__rf2 @cart]
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
