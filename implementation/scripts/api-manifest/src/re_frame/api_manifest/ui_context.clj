(ns re-frame.api-manifest.ui-context
  "Generator + drift check for the re-frame.ui AI context sheet (rf2-u53yy.8).

  THE PROBLEM. UIx's single largest soft advantage over re-frame.ui is
  prior familiarity: every model has already seen UIx's React idioms, and
  UIx ships a compact LLM doc. re-frame.ui's counter is a DETERMINISTIC,
  machine-first sheet of its closed grammar, its compile rejections, and
  the direct fix for each — because this repo's thesis is that
  deterministic didactic errors plus machine-readable manifests beat prior
  familiarity in an agentic loop.

  A hand-maintained roster would DRIFT: the compiler already writes a
  didactic message + the fix at every rejection site, so a parallel prose
  list is a second home for the same fact that silently falls behind. This
  generator makes the compiler's own messages the single source of truth.

  THE ARTEFACT. `skills/re-frame2-ui-context/SKILL.md` — one compact,
  loadable context sheet. Its authored sections (valid `defview` forms,
  the state/sub/event/effect decision table, the foreign-boundary + ref
  rules, the forbidden-idioms list, the build contract) are stable
  template prose emitted verbatim. Its COMPILE-REJECTION ROSTER is
  GENERATED: this namespace reads the compiler's analyzer source
  (`implementation/ui/src/re_frame/ui/compiler/analyze.cljc`), extracts
  every `:rf.ui.compile/*` diagnostic from its `env/fail!` (reject) and
  `env/warn!` (dev-warning) call sites together with the didactic message
  each one carries, and renders that as the roster. The message names the
  fix; nothing is transcribed.

  HOW THE EXTRACTION WORKS. The analyzer source is read with the Clojure
  reader (`:read-cond :preserve`, UTF-8), then tree-walked. A diagnostic
  call is a list whose head is `env/fail!` / `env/warn!` (or the bare
  `fail!` / `warn!`); `fail!` is positional `(fail! env :id msg data?)`
  and `warn!` takes a map `(warn! env {:id … :msg …})`. The message form
  is a string literal, a `(str …)` of literals + runtime interpolations,
  or an `(if … (str …) (str …))`; the didactic PROSE is exactly its string
  literals, so we collect every string literal in the message form's
  subtree in document order, join, and collapse whitespace (the dropped
  interpolations are runtime specifics — a prop name, a form — not part of
  the rule or its fix). Reading the forms (not grepping text) means a
  renamed id, a reworded message, or a new/removed rejection changes the
  extracted data, and `--check` then reds until the sheet is regenerated.

  DRIFT-CHECK. `--check` regenerates the sheet in memory and compares it
  to the committed file (LF-normalised, so a CRLF Windows checkout does
  not trip a spurious drift). It is wired into the same api-manifest gate
  family as `gen --check` and `skills-check` (see .github/workflows/lint.yml).

  Run from implementation/scripts/api-manifest/:
    clojure -M -m re-frame.api-manifest.ui-context           ; regenerate
    clojure -M -m re-frame.api-manifest.ui-context --check    ; drift-check (CI)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackReader]
           [clojure.lang ReaderConditional]))

;; ---------------------------------------------------------------------------
;; Repo-root + artefact paths (mirrors re-frame.api-manifest.gen).
;;
;; The generator runs from implementation/scripts/api-manifest/; the repo
;; root is three dirs up. Resolving relative to `user.dir` (the deps.edn
;; dir the clojure CLI sets) makes it work from any CWD on any platform.
;; ---------------------------------------------------------------------------

(def ^:private here (io/file (System/getProperty "user.dir")))

(def repo-root
  "Repo root = three dirs above implementation/scripts/api-manifest/
   (api-manifest → scripts → implementation → <repo-root>)."
  (-> here .getParentFile .getParentFile .getParentFile))

(def analyzer-source
  "The compiler's analyzer source — the single source of truth for the
   compile-rejection roster."
  (delay (io/file repo-root "implementation" "ui" "src" "re_frame" "ui"
                  "compiler" "analyze.cljc")))

(def sheet-file
  "The generated context sheet."
  (delay (io/file repo-root "skills" "re-frame2-ui-context" "SKILL.md")))

;; ---------------------------------------------------------------------------
;; Reading + tree-walking the analyzer source.
;; ---------------------------------------------------------------------------

(defn read-forms
  "Every top-level form of `file`, read with the Clojure reader. UTF-8 so
   the em-dashes / arrows in the compiler's messages survive verbatim, and
   `:read-cond :preserve` so reader-conditional branches are kept (not
   collapsed to one platform) for the tree walk to descend into."
  [file]
  (with-open [r (PushbackReader. (io/reader file :encoding "UTF-8"))]
    (let [eof (Object.)]
      (loop [acc []]
        (let [form (read {:read-cond :preserve :eof eof} r)]
          (if (identical? form eof) acc (recur (conj acc form))))))))

(defn- branch-children
  "The children of a node for the diagnostic tree walk. A ReaderConditional
   descends into ALL of its branch forms (`:form` is the `(:clj … :cljs …)`
   list) so a diagnostic in either platform branch is still seen."
  [x]
  (if (instance? ReaderConditional x)
    (let [frm (.-form ^ReaderConditional x)]
      (if (seq? frm) frm (seq frm)))
    (seq x)))

(defn walk-all
  "Depth-first seq of every subform of `x`, descending seqs/vectors/maps/
   sets and reader-conditional branches."
  [x]
  (tree-seq #(or (seq? %) (vector? %) (map? %) (set? %)
                 (instance? ReaderConditional %))
            branch-children
            x))

(def ^:private diagnostic-heads
  "Call heads that raise a `:rf.ui.compile/*` diagnostic."
  #{"env/fail!" "env/warn!" "fail!" "warn!"})

(defn- diagnostic-call?
  [x]
  (and (seq? x)
       (symbol? (first x))
       (contains? diagnostic-heads (name (first x)))))

;; ---------------------------------------------------------------------------
;; Message extraction.
;;
;; The didactic PROSE of a diagnostic is exactly the string literals in its
;; message form — a bare string, a `(str …)` of literals + interpolations,
;; or an `(if … (str …) (str …))`. We collect every string literal in the
;; message form's subtree in document order and collapse whitespace; the
;; dropped interpolations (a prop keyword, an offending form) are runtime
;; specifics, not part of the rule or its fix.
;; ---------------------------------------------------------------------------

(defn- collapse-ws
  "Whitespace/seam-normalise a reconstructed message. A dropped
   interpolation leaves seams the compiler never rendered; these
   transforms are GENERIC (not per-message) and only tidy those seams,
   never the rule or its fix:
     1. collapse whitespace runs to one space;
     2. drop a dangling `got` verb the compiler wrote as `…; got <value>`
        — the offending value is the interpolation we dropped, so `got`
        with nothing after it is a seam (word-boundaried and only when
        followed by punctuation or end, so real words are untouched);
     3. drop the space a dropped interpolation left before a
        comma/semicolon/period (e.g. a nested `(str/join \", \" …)`
        separator whose items were interpolations);
     4. strip a trailing lone separator; trim."
  [s]
  (-> s
      (str/replace #"\s+" " ")
      (str/replace #"[;,]?\s*\bgot\b\s*(?=[.,;)\]]|$)" " ")
      (str/replace #"\s+" " ")
      (str/replace #"\s+([,;.)\]])" "$1")
      (str/replace #"[;,]\s*$" "")
      str/trim))

(def ^:private branch-sep
  "Separator between the mutually-exclusive branches of an `(if …)` /
   `(case …)` message — the compiler renders exactly one at runtime; the
   sheet shows both as alternatives."
  " / ")

(defn- render-msg
  "Reconstruct a message form's didactic prose HONOURING its structure:
     - a string literal renders as itself;
     - `(str …)` CONCATENATES its rendered args (so a `(when … \"s\")`
       plural suffix merges: `\"option\" \"s\"` → `\"options\"`);
     - `(if …)` / `(when …)` / `(case …)` / `(cond …)` render their branch
       bodies as `branch-sep`-separated ALTERNATIVES (both arms of a
       conditional message are shown; the test is dropped);
     - a `(str/join sep …)` separator, and every other call / interpolation
       (a prop keyword, an offending form), render as nothing — runtime
       specifics, not part of the rule or its fix.
   This structural walk is what keeps two `(if … (str A) (str B))` arms
   from running together and drops orphaned `str/join` separators, without
   any per-message special-casing."
  [form]
  (cond
    (string? form)
    form

    (and (seq? form) (symbol? (first form)))
    (let [args (rest form)]
      (case (name (first form))
        "str"                (apply str (map render-msg args))
        ("when" "when-not")  (render-msg (last args))
        ("if" "if-not")      (str/join branch-sep
                                       (remove str/blank?
                                               (map render-msg (rest args))))
        ("case" "cond" "condp") (str/join branch-sep
                                          (remove str/blank?
                                                  (map render-msg args)))
        ""))

    :else ""))

(defn- message-text
  "The didactic prose of a message form (see `render-msg`), whitespace/
   seam-normalised. nil when the form carries no string literal at all."
  [msg-form]
  (let [t (render-msg msg-form)]
    (when-not (str/blank? t)
      (collapse-ws t))))

(defn- fail-diagnostic
  "Extract {:id :tier :message} from a positional `(env/fail! env :id msg data?)`
   call. id is the first keyword arg; the message form is the arg right
   after it."
  [call]
  (let [args     (rest call)
        id       (first (filter keyword? args))
        msg-form (second (drop-while (complement keyword?) args))]
    {:id id :tier :reject :message (message-text msg-form)}))

(defn- warn-diagnostic
  "Extract {:id :tier :message} from a map-arg `(env/warn! env {:id … :msg …})`
   call."
  [call]
  (let [m (first (filter map? (rest call)))]
    {:id (:id m) :tier :warning :message (message-text (:msg m))}))

(defn- diagnostic-of [call]
  (if (str/includes? (name (first call)) "warn")
    (warn-diagnostic call)
    (fail-diagnostic call)))

(defn compile-ns?
  "Is `id` a `:rf.ui.compile/*` diagnostic keyword?"
  [id]
  (and (keyword? id) (= "rf.ui.compile" (namespace id))))

(defn extract-diagnostics
  "Every `{:id :tier :message}` diagnostic raised in the analyzer source
   `file` — read from the compiler's own `env/fail!` / `env/warn!` call
   sites. Order is source order; ids/messages are otherwise unmassaged."
  [file]
  (->> (read-forms file)
       (mapcat walk-all)
       (filter diagnostic-call?)
       (map diagnostic-of)
       (filter #(compile-ns? (:id %)))))

(defn roster
  "The compile diagnostics grouped for rendering: a vector of
   `{:id :tier :messages [distinct-in-source-order]}`, one entry per id,
   sorted by id name. `:tier` is `:reject` when any of the id's sites is a
   `fail!` (an id raised by both `fail!` and `warn!` classifies as a
   reject), else `:warning`."
  [diagnostics]
  (->> (group-by :id diagnostics)
       (map (fn [[id ds]]
              {:id       id
               :tier     (if (some #(= :reject (:tier %)) ds) :reject :warning)
               :messages (->> (map :message ds)
                              (remove nil?)
                              distinct
                              vec)}))
       (sort-by (comp name :id))
       vec))

;; ---------------------------------------------------------------------------
;; Non-vacuity floor.
;;
;; A broken extraction (the analyzer source moved / renamed, the reader
;; walk stopped matching `env/fail!`) must FAIL loudly rather than ship an
;; empty roster that silently green-lights. The floor sits well below the
;; live count (~70 ids) so it trips only on a near-total collapse, never on
;; ordinary churn — the same discipline as skills-check's min-references.
;; ---------------------------------------------------------------------------

(def ^:private min-ids 50)

(defn- assert-non-vacuous! [roster*]
  (when (< (count roster*) min-ids)
    (throw (ex-info
            (str "ui-context extraction is vacuous: only " (count roster*)
                 " :rf.ui.compile/* diagnostics found in the analyzer source "
                 "(floor " min-ids "). The reader walk likely stopped matching "
                 "env/fail! / env/warn! — check "
                 "implementation/ui/src/re_frame/ui/compiler/analyze.cljc.")
            {:found (count roster*) :floor min-ids})))
  roster*)

;; ---------------------------------------------------------------------------
;; Rendering.
;;
;; The authored sections are stable template prose. The roster is the
;; GENERATED slice. Keep the two clearly separated so a reader (human or
;; model) knows which lines carry the compiler's own words.
;; ---------------------------------------------------------------------------

(def ^:private front-matter
  "---
name: re-frame2-ui-context
description: >-
  Compact, machine-first reference for authoring re-frame.ui `defview`
  templates — the closed grammar's valid forms and call shapes, the
  state/subscription/event/effect decision table, the foreign-boundary and
  ref rules, the forbidden React/UIx idioms, the build contract, and the
  full compile-rejection roster (every `:rf.ui.compile/*` diagnostic with
  its direct fix). Load when writing, reviewing, or debugging a compiled
  re-frame.ui view. The compile-rejection roster is GENERATED from the
  compiler's own didactic messages; the deep references are the docs
  (docs/core/re-frame.ui/) and the spec (spec/004-Views.md).
---")

(def ^:private generated-banner
  ";; GENERATED by implementation/scripts/api-manifest — do NOT hand-edit.
;; Regenerate: clojure -M -m re-frame.api-manifest.ui-context (from
;; implementation/scripts/api-manifest/). The compile-rejection roster is
;; extracted from the compiler's own env/fail! / env/warn! messages in
;; implementation/ui/src/re_frame/ui/compiler/analyze.cljc; the surrounding
;; prose is authored template. A drift check (ui-context --check) reds in CI
;; until this file is regenerated. Keystone rf2-u53yy.8.")

(def ^:private prose-body
  "The authored, machine-first prose — everything except the generated
   compile-rejection roster. Grounded in the re-frame.ui public surface
   (implementation/ui/src/re_frame/ui.cljc) and the docs
   (docs/core/re-frame.ui/)."
  "# re-frame.ui context sheet

`re-frame.ui` is re-frame2's **compiled-view substrate**. You write views with
one macro, `defview`, in a **closed template grammar** the compiler reads at
*compile* time and lowers to direct React construction in the browser and a
versioned structural tree on the JVM. There is **no runtime hiccup interpreter**
in the production bundle, so anything the compiler cannot prove is a **compile
error with a didactic message and a fix** — the roster at the end of this sheet
is the complete list, generated from the compiler itself.

Everything upstream of the view — events, app-db, subscriptions, effects,
frames — is ordinary re-frame2 (`rf/reg-event`, `rf/reg-sub`, `rf/dispatch`,
`rf/subscribe`). `re-frame.ui` changes only the *view layer*. Require it as
`[re-frame.ui :as ui]` and `[re-frame.core :as rf]`.

## The one component form

```clojure
(ui/defview name docstring? opts? [props] template)
```

- A view is a **pure function of ONE props map** to a template. Zero or one
  argument (the props map). Header destructuring lowers to direct slot reads;
  `:as` opts into materialisation + generic comparison.
- Options (closed): `:props` (a Malli schema), `:id` (registry-id override),
  `:display-name`.
- Every view is **memoised on its props** by a generated per-slot `rf=`
  comparator (`Object.is` OR `=`). No manual `React.memo`, no deps arrays.

```clojure
(ui/defview greeting
  {:props [:map [:who :string]]}
  [{:keys [who]}]
  [:p.hello \"Hello, \" who])

;; call it like any component — a literal vector with the view var as head:
[greeting {:who \"world\"}]
```

Heads must be **literal**: a keyword (DOM/custom element), a `defview` var, or a
foreign-component var. A runtime-chosen head is `:rf.ui.compile/dynamic-head`
(use `ui/raw` for a runtime React element).

### Mounting (client)

```clojure
(ui/mount [ui/frame-provider {:frame :app} [app-root]] dom-node)
```

`ui/mount` = create-root + frame preflight + render, idempotent per root. The
root form is **literal** (the compiler extracts static frame plans). Server
paths: `ui/render-static` (inert HTML string) and `ui/hydrate-root` +
`re-frame.ssr/hydrate!` (SSR-then-hydrate).

## State: where each value belongs

The view's inputs are **subscriptions, props, and view-local state** — plus the
ambient **frame**. Pick by ownership:

| Input | Form | Owns | Reverts on epoch restore? | Use for |
|---|---|---|---|---|
| **subscription** | `(ui/sub [:query …])` | app-db (derived) | yes | any value derived from app state |
| **props** | the `defview` arg map | the parent | n/a | values the caller passes down |
| **local** | `(let [[v set! update!] (ui/local init)] …)` | this component instance | **no** | keystroke-latency ephemera (open/closed, hover, uncommitted field text) |
| **frame** | `(ui/frame)` → `{:dispatch :dispatch-sync :subscribe :frame}` | the committed frame | n/a | an imperative dispatch/subscribe bundle inside the body |

Doctrine: **product-meaning state lives in app-db behind events**; `local` is
for ephemera only. When *every keystroke* is product state, dispatch a
placeholder (`:rf.ui/value`) instead of holding it in `local`.

- `(ui/sub q)` is a **lexical view form**, not a callable helper — calling it
  from a helper the compiler cannot inspect fails loud. Pass the read *value*
  into a helper, or make the helper a `defview`.
- `(ui/local init)` returns `[value set! update!]`, bound in the view's
  **top-region `let`**. `set!` stores its argument **exactly** (a stored fn is a
  value — there is **no `useState` fn-overload**). `update!` applies
  `(f current & args)` to the latest host state, so multiple same-turn writers
  compose. Setter/updater are **host-only** — calling them during render fails
  loud.

## Events & handlers — handlers are data

Prefer a **literal event vector** at an `:on-*` site; the compiler gives it a
per-site-stable identity that reads committed values and dispatches to the
committed frame.

```clojure
[:button {:on-click [:save-clicked]} \"Save\"]                  ; literal vector
[:input  {:value (ui/sub [:draft])
          :on-input (ui/event [e] [:draft-changed (.. e -target -value)])}] ; needs the native event
```

- `(ui/event [e] body…)` — body runs on the event; its **result is the event
  vector to dispatch**, or `nil` to dispatch nothing (a filter).
- `(ui/handler [x] body…)` — imperative sibling; its **return is ignored**. Does
  imperative work (`(ui/dispatch-fn)`, driving a foreign widget, measuring).
  Legal at `:on-*` AND at a foreign-component / internal-view prop, where it
  gives a bare fn prop the stable identity a fresh closure lacks.
- Placeholders splice authored values into a literal vector at **top-level
  positions only** (e.g. `:rf.ui/value`); nested placeholders are ordinary data.
- Controlled inputs: a literal `:value`/`:checked` co-present with a literal
  vector or synchronous `ui/event` handler on `:on-input`/`:on-change`/
  `:on-before-input` rides the one synchronous door.

## Effects — synchronise with the host world

```clojure
(ui/defview chart [{:keys [data]}]
  (let [[el set-el!] (ui/local nil)]
    (ui/effect [data]                 ; re-runs (rf=) when data changes
      (let [c (make-chart @el data)]
        (fn [] (destroy-chart c))))    ; returned fn = cleanup
    [:div {:ref (ui/raw-fn set-el!)}]))
```

- `(ui/effect [deps…] body…)` — a **leading statement** in the top region,
  before the final template. Runs after commit when the literal `deps` change,
  compared by **`rf=`** (keep deps narrow — broad values walk). A returned fn is
  the cleanup.
- `(ui/effect :connect body…)` runs at each connect (mount / reveal) with
  cleanup at each disconnect. There is deliberately **no `\"once\"`/`\"mount\"`**
  name; StrictMode dev replay is expected and cleanup must make it idempotent.
- `sub` / `frame` **inside an effect body are compile errors** (a deferred
  callback owns no render-time site). Dispatch from an effect with
  `(ui/dispatch-fn)` — a per-view-stable dispatcher captured in the body.
- Effects do not run on the JVM structural render (recorded as capability
  metadata only).

## Foreign boundary & refs

- **Foreign React element:** `(ui/raw react-element)` in child position (SSR
  needs a `ui/client-only` sibling fallback). **Foreign component head:** a
  literal foreign-component var as the head — its props are passed through; a
  **bare fn** on a foreign prop is `:rf.ui.compile/bare-fn-prop`, so wrap it in
  `(ui/handler …)` or `(ui/raw-fn …)`.
- **Refs:** `:ref` takes an object ref (preferred) or a `(ui/raw-fn f)` callback
  ref (identity-as-protocol); a **bare fn** at `:ref` is
  `:rf.ui.compile/bare-fn-ref`. An internal **view forwards `:ref` only by
  declaring it** in its header (React 19 ref-as-prop) — passing `:ref` to a view
  carries it on the props object, and the callee reads it via the declared slot.
- **Spreading props:** `(ui/spread base overrides)` in a **DOM/custom element's**
  props position is the one generic runtime prop-map merge (rule-table
  conversion, later-arg-wins). At a **foreign component** call site
  `(ui/spread literal-part runtime-map)` is the wrapper idiom — the literal part
  is analysed normally (compiled handlers/props), the forwarded runtime map is
  opaque and passes through unconverted, and the literal props win a collision;
  an **internal view** still requires a literal props map
  (`:rf.ui.compile/spread-internal-view`). `(ui/spread-safe owned caller)` is the
  literal safe-forward for a component library (structural/controlled keys `:key`
  `:ref` `:value` `:checked` and owned `:on-*` are denied in the caller map).
- **Trusted markup:** `(ui/html s)` as the **sole child** of a DOM element.
- **Custom elements:** declare with `(ui/custom-element :my-el {:properties #{…}})`
  — declared names become JS properties (`:help-text` → `helpText`), undeclared
  names are attributes.
- **Library seams:** `(ui/render-fn [args…] template)` is a pure render callback
  (no `sub`/`frame`/`local`/`effect` inside), invoked by `(ui/slot rf-value arg…)`.
  A *stateful* replacement part is a pure slot body that **mounts a static
  `defview`** (the view owns the state; the slot body stays pure).

## Forbidden React/UIx idioms (and the re-frame.ui form instead)

These have no place in a `defview` — each is a compile rejection; the roster
below carries the exact id + fix:

- **`useState` / `useReducer`** → `(ui/local …)` for ephemera; app-db + events
  for product state. No fn-overloaded setter.
- **`useMemo` / `useCallback`** → not authored: props are memo-compared by `rf=`
  automatically, and committed handlers (`ui/event` / `ui/handler`) already have
  stable identity. (`use-memo` returns as a demand-gated future, never opt-out.)
- **`useEffect` with a manual deps array** → `(ui/effect [deps…] …)` with `rf=`
  deps; a raw React hook is `:rf.ui.compile/react-hook-misplaced` /
  `react-hook-bad-deps`.
- **Custom hooks inside a `defview`** → not a mechanism here
  (`:rf.ui.compile/hook-misplaced`); factor shared logic into subs/events or a
  child `defview`.
- **Bare fns on foreign / element props** → `ui/handler` or `ui/raw-fn`
  (`bare-fn-prop`, `bare-fn-ref`, `bare-fn-in-loop`).
- **Dynamic/runtime component heads** → literal heads only; `ui/raw` for a
  runtime element (`dynamic-head`).
- **`.map` producing unkeyed children / a lazy seq of children** → use `:for`
  with a stable `:key` (`lazy-seq-child`, `unkeyed-list-item`,
  `constant-list-key`, `sub-in-loop`, `frame-in-loop`).
- **Fragments / raw text where the grammar forbids** → see the roster
  (`bad-fragment-props`, `raw-text-children`, `void-children`, …).

## Build contract

- **Require** `[re-frame.ui :as ui]` and install its adapter at boot; nothing
  pulls it in for you. It is **opt-in and additional** — the Reagent/UIx/Helix
  adapters remain first-class.
- **Closed grammar, no runtime walker.** What ships is React construction the
  compiler wrote; there is no hiccup interpreter in the production bundle, so
  unprovable forms are compile errors (never a runtime fallback).
- **JVM parity + headless tests.** A compiled view renders to a structural tree
  on the JVM; test it without a browser via `re-frame.ui.test`
  (`render`/`find`/`text`/`attrs`/`flush!`). Host-only ops (`ui/raw`, `local`
  `set!`/`update!`, `dispatch-fn` invocation) raise `:rf.error/jvm-host-op` on
  the JVM.
- **DCE / elision.** The closed AST is what lets advanced builds elide the
  compiler and tooling; the substrate's non-negotiables (closed grammar, JVM
  parity, DCE/elision, manifests) are fixed.

## Deep references

- Docs: `docs/core/re-frame.ui/` (mental model, build-a-view, state,
  events-and-handlers, interop-and-limits, testing, ssr, presence).
- Spec: `spec/004-Views.md` (the normative grammar).
- API: the `re-frame.ui` / `re-frame.ui.test` API references.

---

## Compile-rejection roster (GENERATED)

Every `:rf.ui.compile/*` diagnostic the compiler raises, extracted from its own
`env/fail!` (reject) and `env/warn!` (dev-warning) messages in
`implementation/ui/src/re_frame/ui/compiler/analyze.cljc`. Each bullet is the
compiler's own message — it names the fix. This section is machine-generated; do
not edit it by hand.")

(defn- render-roster [roster*]
  (let [rejects  (filter #(= :reject (:tier %)) roster*)
        warnings (filter #(= :warning (:tier %)) roster*)
        render-group
        (fn [{:keys [id messages]}]
          (str "- **`" id "`**"
               (case (count messages)
                 0 ""
                 1 (str " — " (first messages))
                 (str "\n"
                      (str/join "\n" (map #(str "  - " %) messages))))))]
    (str "### Rejections (`env/fail!` — compile errors)\n\n"
         (str/join "\n" (map render-group rejects))
         "\n\n### Dev-time warnings (`env/warn!`)\n\n"
         (str/join "\n" (map render-group warnings))
         "\n")))

(defn render-sheet
  "Render the full context sheet from the extracted roster. Deterministic
   and LF-normalised so the committed file is byte-identical across a
   Windows regeneration and a Linux CI check."
  [roster*]
  (let [raw (str front-matter "\n\n"
                 "<!--\n" generated-banner "\n-->\n\n"
                 prose-body "\n\n"
                 (render-roster roster*))]
    (str/replace raw "\r\n" "\n")))

;; ---------------------------------------------------------------------------
;; Entry points.
;; ---------------------------------------------------------------------------

(defn build-sheet
  "Extract the roster from the analyzer source and render the sheet string."
  []
  (-> (extract-diagnostics @analyzer-source)
      roster
      assert-non-vacuous!
      render-sheet))

(defn generate!
  "Regenerate skills/re-frame2-ui-context/SKILL.md from the compiler's
   didactic messages + the authored prose. Returns the sheet string."
  []
  (let [sheet (build-sheet)]
    (io/make-parents @sheet-file)
    (spit @sheet-file sheet :encoding "UTF-8")
    (println (format "Wrote %s (%d compile-diagnostic ids)."
                     (.getPath ^java.io.File @sheet-file)
                     (count (roster (extract-diagnostics @analyzer-source)))))
    sheet))

(defn check!
  "Regenerate in memory and compare to the committed sheet (LF-normalised).
   Returns true when in sync, false (with a diff summary) when drifted."
  []
  (let [generated (build-sheet)
        committed (when (.exists ^java.io.File @sheet-file)
                    (str/replace (slurp @sheet-file :encoding "UTF-8") "\r\n" "\n"))]
    (cond
      (nil? committed)
      (do (binding [*out* *err*]
            (println "DRIFT: skills/re-frame2-ui-context/SKILL.md does not exist."
                     "Run the generator."))
          false)

      (= generated committed)
      (do (println (format "OK: skills/re-frame2-ui-context/SKILL.md in sync (%d ids)."
                           (count (roster (extract-diagnostics @analyzer-source)))))
          true)

      :else
      (do (binding [*out* *err*]
            (println "DRIFT: generated context sheet differs from"
                     "skills/re-frame2-ui-context/SKILL.md.")
            (println "A compiler message (or the authored prose) changed."
                     "Regenerate with:")
            (println "  clojure -M -m re-frame.api-manifest.ui-context"))
          false))))

(defn -main [& args]
  (try
    (if (some #{"--check"} args)
      (System/exit (if (check!) 0 1))
      (do (generate!) (System/exit 0)))
    (catch Throwable t
      (binding [*out* *err*]
        (println "ui-context generator FAILED:")
        (println (.getMessage t)))
      (System/exit 2))))
