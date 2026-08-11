# Migration from Reagent

You have a working Reagent codebase and want it on
[Hicasso](glossary.md#hicasso) without an unverified rewrite. This page covers
the view layer: components, Hiccup dialect, local state, and interop sites. If
the app is still on re-frame v1, migrate events and subscriptions first. This
page assumes re-frame2 underneath.

Three tools, in this order:

1. **The reporter** classifies every foreign crossing — converts mechanically,
   needs a person, or will raise at runtime — and puts a named class and a
   recovery sentence on each line.
2. **[Shadow comparison](glossary.md#shadow-comparison)** (also called shadow
   mode) proves that a ported screen matches the original. It is a dev-only dual
   mount that diffs [canonical DOM](glossary.md#canonical-dom) and
   [intent](glossary.md#intent) streams.
3. **The codemod** repairs the `[:>]` props dialect where the rewrite is
   decidable from source text. It is idempotent.

Do not run the codemod first. Verify with the reporter and shadow comparison,
then let a tool change source with the proof re-run afterward.

## Step 1 — run the reporter

The migration tool ships with Hicasso. It runs on a bare JVM against any Reagent
corpus. It does not load your app, and in its default mode it changes nothing:

```bash
cd re-frame2/migration/reagent-to-hicasso/codemod

clojure -M:run path/to/your/src/          # classify; touch nothing
clojure -M:run --report out.edn src/      # choose where the report goes
```

Reagent *converted* props on the way across a `[:>]` crossing — deep-camelCase
nested keys, keyword values to string names, `r/partial` wrapping, metadata
keys. Hicasso does not convert. `[:>]` stays legal, so a migrated site can keep
rendering while meaning something different. The reporter reads every `[:>]`
site and puts each in one of three buckets:

| Bucket | Named classes | What it means |
|---|---|---|
| Converts mechanically | the six rewrite families W1–W6 (step 4) | The codemod repairs these sites and preserves their behaviour |
| Needs your hands | `:computed-props`, `:computed-value`, `:computed-nested-key`, `:adapt-def-site`, `:cljc-site`, `:parse-error`, `:event-carrier-goes-live`, `:key-conflict`, `:string-tag-unparseable`, `:normalized-key-collision`, `:css-var-repair`, `:named-ref`, `:amp-key` | Either the source text does not say what crosses, or a rewrite would change behaviour. Some classes mark places where Reagent was already broken and the move *repairs* behaviour; check that the repaired behaviour is what you want |
| Will refuse at runtime | `:intent-needs-a-declaration`, `:dangerous-html`, `:r>-site`, `:f>-site`, `:as-element-island`, `:reagent-api-residue` | Migration blockers — these pages raise (or quietly misrender) under Hicasso until a person decides |

Treat the report as the migration plan. It is one EDN file with deterministic
ordering. The tool writes it on a clean run too, and the file includes the count
of sites left untouched — so "not in the report" is never ambiguous. Every entry
has file, line, and column against your input, the source form as text, and a
recovery sentence. The report also carries each component's *name*, which the
runtime cannot: a `[:>]` refusal at runtime prints the constant `"[:>]"`.

The report ends with a **suggestions block** — candidate
[`h/defhost`](glossary.md#defhost) declarations with guessed `:callbacks` maps.
The block is labelled as guesses. Fluent's `onRenderCell` and Ant's `onRow` are
event-*spelled* render props whose return value the caller reads: pasting an
`:event` contract onto one blanks the cell renderer, and the runtime raises
nothing. You choose every contract. The tool never synthesizes one.

Read the report before you write any code. It tells you which screens are
mechanical, which need decisions, and which pages raise on a branch that smoke
tests never reach.

## Step 2 — port a screen by hand

Migrate screen by screen, not file type by file type. Much of the port is
deletion — closures become data, and state machinery becomes addresses:

| Reagent habit | [Hicasso](glossary.md#hicasso) |
|---|---|
| `defn` component returning Hiccup | [`h/defview`](glossary.md#defview) — a re-render [boundary](glossary.md#boundary), used as a head |
| `@(rf/subscribe [:q])` in the body | `(h/sub [:q])` — legal in branches, loops, and helpers |
| `#(rf/dispatch [:x])` handlers | the event vector itself; [`h/event`](glossary.md#hevent) when the callback's arguments matter |
| `r/atom` in a form-2 closure | a forms-module draft or an explicit app-db address — there is no local-state tier |
| `r/with-let` | ordinary `let`; state that must survive a re-render is application state |
| form-3 / `r/create-class` lifecycle | a callback ref at the host edge, or a named native component ([Native tier](10-native-tier.md)) |
| `r/track`, `reaction`, `r/cursor` | layered subscriptions |
| `^{:key k}` metadata | `:key` in the props map |
| `[:> Component …]` | legal as-is; the codemod repairs the dialect; declare with [`h/defhost`](glossary.md#defhost) what you keep ([Interop](09-interop.md)) |
| `r/adapt-react-class` | nothing — the codemod rewrites it to `[:>]`; declare it if it stays |
| `r/as-element` in a render prop | [`h/as-element`](glossary.md#as-element), at a declared `:render` position |
| `r/reactify-component` | [`h/as-component`](glossary.md#outward-bridge) — the [outward bridge](glossary.md#outward-bridge) |

Two of these ports fail loudly, which helps. A Reagent-style
`#(rf/dispatch …)` closure still *runs* when the library or DOM calls it, but
the bare ambient dispatch inside it raises `:rf.error/no-frame-context` — the
recovery names the event-vector and [`h/event`](glossary.md#hevent) spellings.
An [intent](glossary.md#intent) vector at an undeclared `[:>]` prop raises at
render instead of crossing as an inert array. The inert array is what Reagent
did, and the handler never fired there either.

## Step 3 — prove it with shadow comparison

A port that "looks right" is not proof. [Shadow comparison](glossary.md#shadow-comparison)
mounts the Reagent original and the Hicasso port together against isolated
copies of the same seeded frame; drives both with one script; and diffs the
[canonical DOM](glossary.md#canonical-dom) plus the [intent](glossary.md#intent)
streams at every checkpoint.

```clojure
(ns app.migration.article-row-shadow
  (:require [re-frame.hicasso.test :as ht]
            [app.views.article-row-reagent :as old]   ;; the original, untouched
            [app.views.article-row :as new]))         ;; the Hicasso port

(ht/shadow! {:reference      [old/article-row {:article-id 7}]
             :candidate      [new/article-row {:article-id 7}]
             :initial-events [[:demo/install-fixture]]
             :script         [{:click "button.edit"}
                              {:type  ["input.title" "Better title"]}
                              {:click "button.save"}]})
;; => {:status :green :checkpoints 4}
```

Each side gets its own copy of the seeded frame, so writes never cross, and a
divergence compounds: when the port dispatches a different intent at step one,
its state — and therefore its DOM — drifts further at every later step. At each
checkpoint the harness compares the canonical DOM of both mounts and the intents
that each side's handlers dispatched. A red names the checkpoint and the exact
node or intent that differed. When the harness can attribute a difference to a
*declared* policy — a Client-only crossing region, a named refusal — it reports
that classification, not bare drift.

**Green means behaviourally identical over the flows the script drives.** Script
the screen's real flows, not one click. Trust the comparator only after you have
watched it fail: flip one prop in the port and confirm the run goes red at the
correct node.

Omit `:script`, and the dual mount stays up in your dev build: you drive the app
by hand, and every committed render is a checkpoint, live-diffed. That mode suits
exploratory porting — leave the shadow mounted while you work.

Shadow comparison is dev-only. The harness, the dual mount, and the Reagent
dependency itself are development scope; when the last shadow goes green, Reagent
leaves your `deps.edn`. The scope is limited: shadow comparison compares
canonical DOM and intent streams, not focus, caret, IME, or paint. Those are
browser facts; the browser tier of [Testing](14-testing.md) covers them.

When the shadow is green, delete the original. Two copies of one screen can
diverge.

## Step 4 — run the codemod

With the report read and the proof in place, let the tool repair the mechanical
dialect:

```bash
# from the tool directory, as in step 1
clojure -M:run --rewrite src/             # dry run: what would change
clojure -M:run --rewrite --write src/     # apply it
```

The codemod writes whole files through a lossless parser, so formatting,
comments, and line endings survive — a CRLF file comes back CRLF. Exit is `0`
for any run that completed: a migration tool that fails your build over a
decision only you can make is a tool you run once. There are six rewrite
families:

| | At | Written | Preserving |
|---|---|---|---|
| W1 | `^{:key k}` on the vector | `:key k` inside the props map | Reagent read the metadata key; Hicasso reads `(:key props)` — left alone the key goes dead |
| W2 | a literal map reached from the props map through map values | the same map, its literal keys respelled camelCase | Reagent deep-camelCased nested keys; Hicasso does not |
| W3 | a literal keyword, or a quoted symbol, at a prop value | its `name`, as a string | Reagent's `(name x)` arm — a namespaced keyword loses its namespace here, exactly as it already did |
| W4 | a literal `(r/partial f a …)` at a prop value | a hygienic `let` capture, then Reagent's own wrapper | Reagent evaluated the callee and arguments **once**, at construction — the `let` keeps that |
| W5 | `[(r/adapt-react-class X) …]` | `[:> X …]` | the same native-element path, spelled the way Hicasso accepts |
| W6 | `[:> "tag" …]` with a plain tag string | `[:tag …]` | Reagent's input wrapper becomes the [controlled door](04-controlled-inputs.md) |

Two rules govern every rewrite. A rewrite fires only where both sides' behaviour
is computable from the source text and differs. No rewrite introduces a function
whose return differs from what Reagent's conversion produced. A prop's
*spelling* can only make the tool do less — an event-spelled name is a reason to
skip or to refuse, never a reason to wrap — and that keeps the tool from
blanking a render prop. The tool refuses everything else into the report:
silent behaviour change is the failure mode to avoid. A refused site still
works, and the report line turns the refusal into an instruction.

Every output is outside its own rewrite's input language, so a second run
changes nothing — byte for byte, in both line-ending conventions. Then re-run
shadow comparison on the screens the diff touched.

## What stays manual, and why

- **Every [`h/defhost`](glossary.md#defhost) declaration and every `:callbacks`
  contract.** A contract states what a prop *means*, and the meaning is in the
  library's documentation, not your source text. The suggestions block drafts
  the declaration; you approve it.
- **The blockers.** An [intent](glossary.md#intent) vector at a `[:>]` prop
  (`:intent-needs-a-declaration`) needs a decision about what it was for.
  `dangerouslySetInnerHTML` (`:dangerous-html`) needs an explicit yes: Reagent
  deleted it unless wrapped, Hicasso passes it through, so the migration turns a
  dead prop into a live one. Port `[:r> …]` and `[:f> …]` sites by hand —
  Hicasso reads those as tag keywords and quietly renders an unknown element.
  Restructure `r/as-element` inside a callback (`:as-element-island`) by hand:
  that closure runs outside the owner's render window, so its
  [lowering](glossary.md#lowering) is not a text substitution.
- **Local state and lifecycle** (`:reagent-api-residue`): `r/atom`, cursors,
  form-2/form-3 shapes. Where a piece of state lives is a design decision —
  [Ephemeral state](11-ephemeral-state.md) covers the homes — and no tool makes
  that decision for you.
- **Everything computed.** Props built by `merge`, values reached through a
  symbol, keys computed at runtime: the source text does not say what crosses,
  so the tool reports instead of guessing.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| A `[:>]` site renders but behaves differently than under Reagent | Props dialect drift — Reagent converted on the way across; Hicasso does not | Run the reporter; let the codemod repair the decidable sites |
| A page raises at render at a `[:>]` site that "worked" before | `:rf.error/hicasso-host-undeclared-callback` — an [intent](glossary.md#intent) vector at an escape prop; under Reagent it crossed as an inert array and never fired | Declare the host and name the prop in `:callbacks`, or hand a plain function — and decide what the dead handler was for |
| A handler runs, then `:rf.error/no-frame-context` | A Reagent-style `#(rf/dispatch …)` closure — it carries no frame | Make it an event vector, or [`h/event`](glossary.md#hevent) |
| A keyed list remounts once right after migration | The `:key` slot is reported, never rewritten: `:foo` and `"foo"` keys collided under Reagent and are distinct now, so the key value changes once | Accept the one-time remount; keys are stable after |
| The codemod refused a whole nested map | `:normalized-key-collision` — sibling keys like `:foo-bar`/`:fooBar` collapsed onto one JS property under Reagent, with an iteration-order winner | Delete the key you did not mean; re-run |
| A W2 diff camelCased keys in what is clearly a *data* map | Behaviour preserved: Reagent already sent the library camelCase | Do **not** revert the keys — a revert changes what the library receives. If the map was data, notice it now |
| Shadow run red on a region the port renders client-only | A declared Client-only crossing, classified as a policy difference, not drift | Choose the [server policy](glossary.md#server-policy) (`{:server :render}` or a fallback) or accept the classification |
| Shadow green, but focus/IME behaves differently in a real browser | Shadow comparison covers canonical DOM and intents; browser laws are out of its scope | Run the browser tier ([Testing](14-testing.md)) |
| Second codemod run produced a diff | It cannot — outputs are outside the input language. If files changed, something else edited them between runs | Diff against the report's coordinates; re-run the reporter |

## When not to migrate this way

- **A small app.** Below a handful of screens, the reporter plus a hand port is
  the whole job; shadow scripts cost more than reading the diff. Keep the
  reporter — it costs nothing — and skip the harness.
- **A React-first screen.** Do not convert a screen that is mostly vendor
  components and hooks to Hiccup on principle: port it to a named native island,
  or keep it UIx, under the same root and frame
  ([Native tier](10-native-tier.md)).
- **A screen that you plan to redesign.** Shadow comparison proves behavioural
  *identity*; when a planned redesign discards the behaviour, port the screen
  loosely, and spend the proof budget on screens that must not change.

## Advanced

### Reading a report entry

```clojure
{:file   "src/app/views.cljs"
 :line   42
 :col    5
 :form   "[:> Btn {:variant :contained} \"Save\"]"
 :head   "Btn"
 :action :rewrote          ;; or :refused, :skipped
 :detail {:prop :variant :was :contained :now "contained"}
 :note   "Reagent named every keyword prop value; Hicasso keeps the keyword
          except at HTML-attribute slots."}
```

Coordinates address the *input* file for the scan and for the rewrite, so a line
in the report is the line that you grep for in either mode.

### Why W4 writes a `let`

The obvious rewrite of `(r/partial f @snapshot)` is a bare
`(fn [& args] (apply f @snapshot args))`, and that rewrite is wrong. `r/partial`
evaluated its callee and its arguments **once**, when the prop was built. The
bare `fn` re-evaluates them on every call, so `@snapshot` stops being a
snapshot, and `(next-id!)` allocates a fresh id on every click. The codemod
writes a `let` that captures once:

```clojure
[:> Btn {:on-pick (r/partial handler @cart)}]
;; =>
[:> Btn {:on-pick (let [f__rf2 handler a0__rf2 @cart]
                    (fn [& args__rf2] (apply f__rf2 a0__rf2 args__rf2)))}]
```

The names are deterministic, not gensym'd, so what lands in your diff is
readable. The tool checks the names against every symbol that the site spells,
and a collision bumps the whole family (`f__rf2__1`, …) until the names are
fresh.

### Divergences the tool leaves alone

These divergences are named so you do not file them as gaps. Each is a place
where Hicasso differs from the donor in a way that is better, or invisible:

- Class collections in any spelling now coerce and compose (Reagent read only
  the literal `:class` key).
- Nested class collections flatten.
- Hicasso drops `__proto__`/`prototype`/`constructor` instead of writing them.
- A literal `nil` at the props slot counts as a child, and React renders nothing
  for it.
