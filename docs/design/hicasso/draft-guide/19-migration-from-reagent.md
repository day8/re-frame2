# Migrating from Reagent

This page covers the view-layer migration from Reagent to Hicasso: component
definitions, Hiccup differences, local state, and React interop.

It assumes the application already uses re-frame2 events and subscriptions. If
it still uses re-frame v1 shapes, complete that migration first.

Use three migration tools in this order:

1. **Reporter** — classify every foreign React crossing and identify mechanical
   rewrites, human decisions, and runtime blockers.
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

## 2. Port one screen by hand

Migrate a complete screen rather than changing all component declarations,
then all handlers, then all crossings across the repository. A screen-level
port gives shadow comparison a useful unit.

Common translations:

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
| `r/as-element` inside a render prop | `h/as-element` under a declared `:render` callback contract |
| `r/reactify-component` | `h/as-component`, the outward bridge |

Two common mistakes fail loudly:

- A Reagent-style `#(rf/dispatch ...)` callback has no captured frame when the
  browser invokes it later, so ambient dispatch raises
  `:rf.error/no-frame-context`. Use an intent vector or `h/event`.
- An event vector at an undeclared `[:>]` prop raises instead of crossing as an
  inert JavaScript array. Under Reagent that inert value did not produce a
  working handler either; the migration forces you to decide the callback's
  contract.

## 3. Prove the port with shadow comparison

`ht/shadow!` mounts the original and candidate against isolated copies of the
same seeded frame. One interaction script drives both implementations. At
each checkpoint it compares canonical DOM and the intent stream.

```clojure
(ns app.migration.article-row-shadow
  (:require [re-frame.hicasso.test :as ht]
            [app.views.article-row-reagent :as old]
            [app.views.article-row :as new]))

(ht/shadow!
 {:reference      [old/article-row {:article-id 7}]
  :candidate      [new/article-row {:article-id 7}]
  :initial-events [[:demo/install-fixture]]
  :script         [{:click "button.edit"}
                   {:type  ["input.title" "Better title"]}
                   {:click "button.save"}]})
;; => {:status :green :checkpoints 4}
```

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

Omit `:script` for interactive development. Both mounts remain live and each
committed render becomes a checkpoint as you use the screen manually.

Shadow comparison covers canonical DOM and intent streams. It does not prove
focus, caret, IME, layout, or paint behaviour. Use the browser levels from
[Testing](14-testing.md) for those claims.

When the screen is green and its browser tests pass, remove the Reagent
original. Keeping both copies invites future divergence.

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

- `:intent-needs-a-declaration`: decide what the undeclared event-shaped prop
  means;
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
| Render raises `:rf.error/hicasso-host-undeclared-callback` at a former Reagent crossing | An intent vector reached an undeclared prop; under Reagent it crossed as inert data | Declare the host and callback contract, or supply the actual plain function the library expects |
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
- Keep a React-first screen native or UIx instead of converting it to Hiccup on
  principle ([The native tier](10-native-tier.md)).
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
