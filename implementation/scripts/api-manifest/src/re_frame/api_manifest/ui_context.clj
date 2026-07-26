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

  THE ARTEFACT. `skills/re-frame2-ui/references/ui-context.md` — the
  compact context sheet behind the authored `re-frame2-ui` skill (the
  skill's SKILL.md carries the teaching prose and the one trigger surface;
  this sheet is its on-demand reference). Its authored sections (valid
  `defview` forms,
  the state/sub/event/effect decision table, the foreign-boundary + ref
  rules, the forbidden-idioms list, the build contract) are stable
  template prose emitted verbatim. Two slices are GENERATED from live
  repo data so they cannot drift:

    1. THE COMPILE-REJECTION ROSTER, from the compiler's own front-door
       analyzers. This namespace reads a BOUNDED, EXPLICIT roster of
       compiler source files (see `compiler-source-files`), extracts every
       `:rf.ui.compile/*` diagnostic from their raise sites together with
       the didactic message each carries, and renders that as the roster.
       The message names the fix; nothing is transcribed.

    2. THE AUTHORING SURFACE, from the public API manifest
       (`spec/api-manifest.edn`). Every public `re-frame.ui` var is
       classified `:taught` (covered by the prose) or `[:omitted reason]`
       in `authoring-disposition`; the build asserts that classification
       covers the manifest EXACTLY, so a var added to / removed from the
       public API reds the context check until it is consciously handled.

  RAISE-SITE CALL SHAPES (the bounded set — no generic diagnostics
  framework). A diagnostic is raised through one of a few call heads:
    - `env/fail!` / `fail!` (analyzer/env)  — `(fail! env :id msg data?)`
    - `fail` (compiler / header / root)      — `(fail :id msg data?)`
    - `env/compile-error` (ui.test)          — `(compile-error :id msg data)`
    - `env/warn!` / `warn!` (analyzer)       — `(warn! env {:id … :msg …})`
    - `report!` (a11y)                       — `(report! env {:id … :msg …})`
  The `fail`-family (id-first OR env-first) and `compile-error` are
  REJECTS; the `warn`-family and a11y's `report!` are dev-time WARNINGS.
  `:rf.ui.compile/error` is NOT a diagnostic id — it is the ex-data
  ENVELOPE key every compile error carries (`{:rf.ui.compile/error <id>}`),
  so it is classified out (see `envelope-only-ids`), never a roster row.

  HOW THE EXTRACTION WORKS. Each source file is read with the Clojure
  reader (`:read-cond :preserve`, UTF-8, and a permissive default
  data-reader so a CLJS-only `#js` literal in a `.cljc` file does not
  break the read), then tree-walked. The message form is a string literal,
  a `(str …)`, an `(if/when/case/cond …)`, or a runtime interpolation
  (a prop keyword, an offending form). `render-msg` reconstructs the prose
  HONOURING that structure — concatenating a `(str …)`, showing both arms
  of a conditional, and rendering each runtime interpolation as a TYPED
  PLACEHOLDER (`‹prop›`, `‹bindings›`, …) so every advertised fix stays a
  complete, actionable sentence rather than a truncated fragment. Reading
  the forms (not grepping text) means a renamed id, a reworded message, or
  a new/removed rejection changes the extracted data, and `--check` then
  reds until the sheet is regenerated.

  DRIFT-CHECK. `--check` regenerates the sheet in memory and compares it
  to the committed file (LF-normalised, so a CRLF Windows checkout does
  not trip a spurious drift). It is wired into the same api-manifest gate
  family as `gen --check` and `skills-check` (see .github/workflows/lint.yml).

  Run from implementation/scripts/api-manifest/:
    clojure -M -m re-frame.api-manifest.ui-context           ; regenerate
    clojure -M -m re-frame.api-manifest.ui-context --check    ; drift-check (CI)"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.set :as set]
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

(def ^:private compiler-src-dir
  (io/file repo-root "implementation" "ui" "src" "re_frame" "ui"))

(def compiler-source-files
  "The BOUNDED, EXPLICIT roster of compiler FRONT-DOOR source files whose
   raise sites mint the `:rf.ui.compile/*` diagnostics an author meets.
   Reading only `analyze.cljc` (the original scope) silently omitted the
   defview/options rejections (compiler.cljc, header.cljc), the root /
   mount / static rejections (root.cljc), the unresolved-head rejections
   (env.cljc), the four a11y warnings + suppression reject (a11y.cljc),
   and the ui.test front-door rejections (test.cljc). This list IS the
   scope; the exact-id inventory test pins that the extraction sees every
   diagnostic these files raise, so none can disappear silently."
  ["compiler.cljc"
   "compiler/analyze.cljc"
   "compiler/header.cljc"
   "compiler/root.cljc"
   "compiler/env.cljc"
   "compiler/a11y.cljc"
   "test.cljc"])

(defn source-files
  "The bounded compiler-source roster as absolute Files, in declared order."
  []
  (mapv #(io/file compiler-src-dir %) compiler-source-files))

(def sheet-file
  "The generated context sheet — the on-demand reference leaf of the
   authored `skills/re-frame2-ui` skill."
  (delay (io/file repo-root "skills" "re-frame2-ui" "references" "ui-context.md")))

(def manifest-file
  "The public-API manifest — the source of truth for the authoring surface."
  (delay (io/file repo-root "spec" "api-manifest.edn")))

;; ---------------------------------------------------------------------------
;; Reading + tree-walking the compiler sources.
;; ---------------------------------------------------------------------------

(defn read-forms
  "Every top-level form of `file`, read with the Clojure reader. UTF-8 so
   the em-dashes / arrows in the compiler's messages survive verbatim;
   `:read-cond :preserve` so reader-conditional branches are kept (not
   collapsed to one platform) for the tree walk to descend into; and a
   permissive `*default-data-reader-fn*` so a CLJS-only literal such as
   `#js {…}` inside a `.cljc` file reads through instead of throwing
   (these sit outside diagnostic messages — we only need to not crash)."
  [file]
  (binding [*default-data-reader-fn* (fn [_tag val] val)]
    (with-open [r (PushbackReader. (io/reader file :encoding "UTF-8"))]
      (let [eof (Object.)]
        (loop [acc []]
          (let [form (read {:read-cond :preserve :eof eof} r)]
            (if (identical? form eof) acc (recur (conj acc form)))))))))

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

;; ---------------------------------------------------------------------------
;; Raise-site classification (the bounded call-shape set).
;; ---------------------------------------------------------------------------

(def ^:private reject-heads
  "Call heads that raise a REJECT (compile error). Positional, id-first
   (`fail` / `compile-error`) OR env-first (`fail!` / `env/fail!`); either
   way the id is the sole keyword arg and the message is the form right
   after it."
  #{"fail" "fail!" "env/fail!" "compile-error" "env/compile-error"})

(def ^:private warn-heads
  "Call heads that raise a dev-time WARNING. Map-arg shape
   `(head env {:id … :msg …})` — analyzer `warn!`/`env/warn!` and a11y's
   `report!` (which forwards to `env/warn!` internally; that forwarder
   carries a symbol :id and so is naturally filtered out)."
  #{"warn!" "env/warn!" "report!"})

(def ^:private diagnostic-heads (set/union reject-heads warn-heads))

(defn- diagnostic-call?
  [x]
  (and (seq? x)
       (symbol? (first x))
       (contains? diagnostic-heads (name (first x)))))

;; ---------------------------------------------------------------------------
;; Message reconstruction.
;;
;; A diagnostic's message form is a string literal, a `(str …)`, an
;; `(if/when/case/cond …)`, or a runtime interpolation (a prop keyword, an
;; offending form). We reconstruct the prose HONOURING that structure, and
;; render each dropped interpolation as a TYPED PLACEHOLDER so no fix is
;; ever truncated (the original design deleted interpolations, which left
;; fragments like "…; got" and "is not a prop … Use").
;; ---------------------------------------------------------------------------

(def ^:private structural-heads
  "Message-form heads `render-msg` walks STRUCTURALLY — `str` concatenates,
   the conditionals show their branch bodies as alternatives. Every other
   head (str/join, name, pr-str, an arbitrary call) is a runtime
   interpolation rendered as a single placeholder, which is exactly how a
   formatting-only `(str/join sep coll)` collapses to one `‹coll›` token
   (its separator dropped) rather than leaking a literal separator."
  #{"str" "when" "when-not" "if" "if-not" "cond" "condp" "case"})

(def ^:private branch-sep
  "Separator between the mutually-exclusive branches of a conditional
   message — the compiler renders exactly one at runtime; the sheet shows
   both as alternatives."
  " / ")

(defn- collect-value-syms
  "Symbols of `form` in ARGUMENT (non-call-head) position, in document
   order — the candidate labels for a placeholder. A seq's head is its call
   operator and is dropped; its args recurse. Collections recurse fully.
   Because heads are always dropped, a wrapper like `pr-str` / `name` in
   HEAD position never becomes a label; the value it wraps does."
  [form]
  (cond
    (symbol? form) [form]
    (seq? form)    (mapcat collect-value-syms (rest form))
    (vector? form) (mapcat collect-value-syms form)
    (map? form)    (mapcat collect-value-syms (interleave (keys form) (vals form)))
    (set? form)    (mapcat collect-value-syms form)
    :else          nil))

(defn- placeholder
  "A dropped runtime interpolation shown by NAME so the fix stays a complete
   sentence — \"…; got ‹bindings›\" not a truncated \"…; got\". The label is
   the LAST value symbol of the interpolation — the datum the expression
   formats: `(str/join \", \" (map pr-str unknown))` labels `‹unknown›`, not
   the `pr-str` fn passed to `map`. A bare marker when none reads cleanly."
  [form]
  (let [syms (collect-value-syms form)]
    (str "‹" (if (seq syms) (name (last syms)) "…") "›")))

(defn- branch-join [rendered]
  (str/join branch-sep (distinct (remove str/blank? rendered))))

(defn- render-msg
  "Reconstruct a message form's didactic prose:
     - a string literal renders as itself; a literal number/char/keyword as
       its printed form (a `:fallback` written into the message survives);
     - `(str …)` CONCATENATES its rendered args (so a `(when … \"s\")` plural
       suffix merges: `\"option\" \"s\"` → `\"options\"`);
     - `(if/when/case/cond …)` render their branch bodies as `branch-sep`-
       separated ALTERNATIVES (the test is dropped);
     - every OTHER form (str/join, name, pr-str, an arbitrary call, a bare
       value symbol) is a runtime interpolation → a typed `‹label›`
       placeholder."
  [form]
  (cond
    (string? form)                   form
    (number? form)                   (str form)
    (char? form)                     (str form)
    (keyword? form)                  (str form)
    (or (nil? form) (boolean? form)) ""

    (and (seq? form) (symbol? (first form))
         (contains? structural-heads (name (first form))))
    (let [args (rest form)]
      (case (name (first form))
        "str"                   (apply str (map render-msg args))
        ("when" "when-not")     (render-msg (last args))
        ("if" "if-not")         (branch-join (map render-msg (rest args)))
        ("case" "cond" "condp") (branch-join (map render-msg args))))

    :else (placeholder form)))

(defn- collapse-ws
  "Whitespace-normalise a reconstructed message: collapse runs to one space
   and trim. With interpolations rendered as visible placeholders there are
   no dropped-value seams left to repair, so this is deliberately minimal."
  [s]
  (-> s (str/replace #"\s+" " ") str/trim))

(defn- message-text
  "The didactic prose of a message form (see `render-msg`), whitespace-
   normalised. nil when the form carries no renderable prose at all."
  [msg-form]
  (let [t (render-msg msg-form)]
    (when-not (str/blank? t)
      (collapse-ws t))))

(defn- reject-diagnostic
  "Extract {:id :tier :message} from a positional reject call — id-first
   `(fail :id msg data?)` OR env-first `(fail! env :id msg data?)`. id is
   the sole keyword arg; the message form is the form right after it."
  [call]
  (let [args     (rest call)
        id       (first (filter keyword? args))
        msg-form (second (drop-while (complement keyword?) args))]
    {:id id :tier :reject :message (message-text msg-form)}))

(defn- warn-diagnostic
  "Extract {:id :tier :message} from a map-arg warn call
   `(warn! env {:id … :msg …})` / `(report! env {:id … :msg …})`."
  [call]
  (let [m (first (filter map? (rest call)))]
    {:id (:id m) :tier :warning :message (message-text (:msg m))}))

(defn- diagnostic-of [call]
  (if (contains? warn-heads (name (first call)))
    (warn-diagnostic call)
    (reject-diagnostic call)))

(def ^:private envelope-only-ids
  "Compile-namespaced keywords that are NOT diagnostics — infrastructure the
   roster must never claim. `:rf.ui.compile/error` is the ex-data ENVELOPE
   key every compile error carries (`{:rf.ui.compile/error <id>}`); it is a
   map key, never the id argument to a raise call, so extraction excludes it
   both structurally (it is never a call-id) and explicitly (here)."
  #{:rf.ui.compile/error})

(defn compile-ns?
  "Is `id` an in-scope `:rf.ui.compile/*` diagnostic keyword (i.e. a real
   diagnostic, not an envelope-only key)?"
  [id]
  (and (keyword? id)
       (= "rf.ui.compile" (namespace id))
       (not (contains? envelope-only-ids id))))

(defn extract-diagnostics-from
  "Every `{:id :tier :message}` diagnostic raised in a single source `file`,
   in source order; ids/messages otherwise unmassaged."
  [file]
  (->> (read-forms file)
       (mapcat walk-all)
       (filter diagnostic-call?)
       (map diagnostic-of)
       (filter #(compile-ns? (:id %)))))

(defn extract-diagnostics
  "Every diagnostic across the bounded compiler-source roster, in
   file-declared then source order."
  ([] (extract-diagnostics (source-files)))
  ([files] (mapcat extract-diagnostics-from files)))

(defn roster
  "The compile diagnostics grouped for rendering: a vector of
   `{:id :tier :messages [distinct-in-source-order]}`, one entry per id,
   sorted by id name. `:tier` is `:reject` when any of the id's sites is a
   reject (an id raised as both classifies as a reject), else `:warning`."
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

(defn roster-ids
  "The sorted set of diagnostic ids the extraction currently sees — the
   inventory the exact-id test pins."
  ([] (roster-ids (roster (extract-diagnostics))))
  ([roster*] (into (sorted-set) (map :id) roster*)))

;; ---------------------------------------------------------------------------
;; Non-vacuity floor.
;;
;; A broken extraction (a source file moved / renamed, the reader walk
;; stopped matching a raise head) must FAIL loudly rather than ship a thin
;; roster that silently green-lights. The floor sits well below the live
;; count (~90 ids across the bounded roster) so it trips only on a near-
;; total collapse, never on ordinary churn.
;; ---------------------------------------------------------------------------

(def ^:private min-ids 70)

(defn- assert-non-vacuous! [roster*]
  (when (< (count roster*) min-ids)
    (throw (ex-info
            (str "ui-context extraction is vacuous: only " (count roster*)
                 " :rf.ui.compile/* diagnostics found across the bounded "
                 "compiler-source roster (floor " min-ids "). The reader walk "
                 "likely stopped matching a raise head — check the sources in "
                 "compiler-source-files.")
            {:found (count roster*) :floor min-ids})))
  roster*)

;; ---------------------------------------------------------------------------
;; The authoring surface — driven by the public API manifest.
;;
;; Every public `re-frame.ui` var is classified here as `:taught` (covered
;; by the prose above) or `[:omitted reason]` (deliberately out of this
;; compact sheet's scope, with a pointer). The build asserts this map's key
;; set EQUALS the manifest's `re-frame.ui` var set, so a var added to /
;; removed from the public API reds the context check until it is
;; consciously classified — the API-drift ownership the sheet promises.
;; ---------------------------------------------------------------------------

(defn manifest-ui-vars
  "The public `re-frame.ui` authoring vars from `spec/api-manifest.edn` — a
   set of var-name strings."
  []
  (->> (:vars (edn/read-string (slurp @manifest-file :encoding "UTF-8")))
       (filter #(= "re-frame.ui" (:namespace %)))
       (map :var)
       set))

(def authoring-disposition
  "Disposition for EVERY public `re-frame.ui` var: `:taught` (named + taught
   in the prose) or `[:omitted reason]` (out of this compact sheet's scope;
   reason points onward). The build asserts this covers the manifest exactly."
  {"defview"        :taught
   "sub"            :taught
   "local"          :taught
   "ref"            :taught
   "frame"          :taught
   "event"          :taught
   "handler"        :taught
   "dispatch-fn"    :taught
   "effect"         :taught
   "raw"            :taught
   "raw-fn"         :taught
   "->react"        :taught
   "spread"         :taught
   "spread-safe"    :taught
   "html"           :taught
   "custom-element" :taught
   "render-fn"      :taught
   "slot"           :taught
   "mount"          :taught
   "frame-provider" :taught
   "frame-root"     :taught
   "render-static"  :taught
   "hydrate-root"   :taught
   "client-only"    :taught
   "adapter"        :taught
   "create-root"    [:omitted "low-level root primitive; author mounts with ui/mount"]
   "render!"        [:omitted "imperative render under ui/mount; author uses ui/mount"]
   "unmount!"       [:omitted "teardown primitive paired with ui/mount"]
   "route-link"     [:omitted "router integration; see the routing docs + the route-link roster entry"]
   "presence"       [:omitted "enter/exit presence choreography; see docs/core/freehand/host/presence.md + the presence-* diagnostics"]
   "presence-phase" [:omitted "presence phase accessor; see docs/core/freehand/host/presence.md"]
   "error-boundary" [:omitted "error-boundary form; see docs/core/freehand/host/host-boundaries.md + the bad-error-boundary diagnostic"]})

(defn- assert-disposition-covers-manifest!
  "Reds when `authoring-disposition` and the manifest's re-frame.ui var set
   disagree — a var added to the public API with no classification, or a
   classification left behind after a var was removed. This is the API-drift
   ownership: a new authoring var cannot ship silently un-taught."
  []
  (let [manifest (manifest-ui-vars)
        declared (set (keys authoring-disposition))
        missing  (set/difference manifest declared)
        stale    (set/difference declared manifest)]
    (when (or (seq missing) (seq stale))
      (throw (ex-info
              (str "ui-context authoring-surface disposition is out of sync "
                   "with spec/api-manifest.edn.\n"
                   (when (seq missing)
                     (str "  UNCLASSIFIED public re-frame.ui var(s) — classify "
                          ":taught or [:omitted reason] in authoring-disposition: "
                          (str/join ", " (sort missing)) "\n"))
                   (when (seq stale)
                     (str "  STALE classification(s) for var(s) no longer public "
                          "— remove from authoring-disposition: "
                          (str/join ", " (sort stale)) "\n"))
                   "Then regenerate the sheet.")
              {:missing missing :stale stale})))
    manifest))

;; ---------------------------------------------------------------------------
;; Rendering.
;;
;; The authored sections are stable template prose. The roster and the
;; authoring surface are the GENERATED slices. Keep them clearly separated
;; so a reader (human or model) knows which lines carry live repo data.
;; ---------------------------------------------------------------------------

(def ^:private generated-banner
  ";; GENERATED by implementation/scripts/api-manifest — do NOT hand-edit.
;; This file is the on-demand REFERENCE leaf of the authored re-frame2-ui
;; skill (skills/re-frame2-ui/SKILL.md carries the teaching prose and the
;; one trigger surface; it routes here for the exact call shapes, the
;; authoring-surface disposition, and the compile-rejection roster).
;; Regenerate: clojure -M -m re-frame.api-manifest.ui-context (from
;; implementation/scripts/api-manifest/). Two slices are generated from live
;; repo data: the compile-rejection roster from the compiler's own env/fail!
;; / fail / env/warn! / report! messages across the bounded source roster in
;; the generator's compiler-source-files, and the authoring surface from the
;; public API manifest (spec/api-manifest.edn); the surrounding prose is
;; authored template. A drift check (ui-context --check) reds in CI until this
;; file is regenerated. Keystone rf2-u53yy.8.")

(def ^:private prose-body
  "The authored, machine-first prose — everything except the two generated
   slices (the compile-rejection roster and the authoring surface).
   Grounded in the re-frame.ui public surface
   (implementation/ui/src/re_frame/ui.cljc) and the normative grammar
   (spec/004-Views.md). The donor guide it was also drawn from is gone;
   the Freehand guide at docs/core/freehand/ replaced it."
  "# re-frame.ui context sheet

`re-frame.ui` is re-frame2's **compiled-view substrate**. You write views with
one macro, `defview`, in a **closed template grammar** the compiler reads at
*compile* time and lowers to direct React construction in the browser and a
versioned structural tree on the JVM. There is **no runtime hiccup interpreter**
in the production bundle, so anything the compiler cannot prove is a **compile
error with a didactic message and a fix** — the roster at the end of this sheet
is the list, generated from the compiler itself.

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
root form is **literal** (the compiler extracts static frame plans).
`ui/frame-provider` **scopes** an existing frame for a subtree; `ui/frame-root`
is the static **ensure-plan** root wrapper (its `:id` a literal keyword) that
mount/`ui/render!`/`ui/hydrate-root` turn into the frame the root owns. Server
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
  (let [node (ui/ref)]                     ; the DOM-node ref (object ref)
    (ui/effect [node data]                 ; re-runs (rf=) when node or data change
      (when-let [el (.-current node)]      ; guard: read the node from .current
        (let [c (make-chart el data)]
          (fn [] (destroy-chart c)))))     ; returned fn = cleanup
    [:canvas {:ref node}]))
```

- `(ui/effect [deps…] body…)` — a **leading statement** in the top region,
  before the final template. Runs after commit when the literal `deps` change,
  compared by **`rf=`** (keep deps narrow — broad values walk). A returned fn is
  the cleanup.
- `(ui/ref)` is the everyday **DOM-node** primitive — the object ref
  bound in the top-region `let`, passed to `:ref`, and read via `(.-current node)`
  from the effect (it attaches at commit, before the effect fires). Assignment
  never re-renders (contrast `ui/local`). Callback refs via `(ui/raw-fn f)` are
  the expert seam for when the node's *identity* change must itself trigger work.
- `(ui/effect :connect body…)` runs at each connect (mount / reveal) with
  cleanup at each disconnect. There is deliberately **no `\"once\"`/`\"mount\"`**
  name; StrictMode dev replay is expected and cleanup must make it idempotent.
- `sub` / `frame` **inside an effect body are compile errors** (a deferred
  callback owns no render-time site). Dispatch from an effect with
  `(ui/dispatch-fn)` — a per-view-stable dispatcher captured in the body.
- Effects do not run on the JVM structural render (recorded as capability
  metadata only).

## Foreign boundary & refs

- **Foreign React element (inward):** `(ui/raw react-element)` in child position
  (SSR needs a `ui/client-only` sibling fallback). **Foreign component head:** a
  literal foreign-component var as the head — its props are passed through; a
  **bare fn** on a foreign prop is `:rf.ui.compile/bare-fn-prop`, so wrap it in
  `(ui/handler …)` or `(ui/raw-fn …)`.
- **Export a view (outward):** `(ui/->react view)` turns a compiled `defview`
  into a **React component** a foreign React/UIx tree can render
  (`(def CartRow (ui/->react cart-row))` → `<CartRow …/>` anywhere) — the reverse
  of `ui/raw` and the incremental-adoption bridge. It is **stable/memoised per
  view identity**, creates **no root** and runs no preflight (it renders inside
  the host's root), and scopes — never creates — a frame: the one reserved prop
  `frame` (a frame-id or live frame) scopes the subtree, else it resolves the
  ambient frame or fails loud. A JVM call raises `:rf.error/jvm-host-op`.
- **Refs:** `:ref` takes an object ref — `(ui/ref)`, the substrate DOM-node
  primitive, is the common case (bind it in the top-region `let`, read
  `(.-current node)` from an `effect`) — or a `(ui/raw-fn f)` callback ref
  (identity-as-protocol) for the expert case; a **bare fn** at `:ref` is
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
  pulls it in for you. It is **opt-in and additional** — the Reagent/UIx
  adapters remain first-class.
- **Closed grammar, no runtime walker.** What ships is React construction the
  compiler wrote; there is no hiccup interpreter in the production bundle, so
  unprovable forms are compile errors (never a runtime fallback).
- **JVM parity + headless tests.** A compiled view renders to a structural tree
  on the JVM; test it without a browser via `re-frame.ui.test` — **six names
  across two hosts**. JVM structural host: `render` (ONE grammar — the literal
  view form, props carried in it; ONE option `:sub-overrides`) plus the `attrs`
  / `text` read projections, traversed with ordinary `(tree-seq map? :children
  tree)` and driven by `rf/dispatch-sync` under `rf/with-new-frame`. CLJS
  mounted host: `with-root` + `flush!` + `flush-presence!`, querying the
  container with native `.querySelector`. Host-only ops (`ui/raw`, `local`
  `set!`/`update!`, `dispatch-fn` invocation) raise `:rf.error/jvm-host-op` on
  the JVM.
- **DCE / elision.** The closed AST is what lets advanced builds elide the
  compiler and tooling; the substrate's non-negotiables (closed grammar, JVM
  parity, DCE/elision, manifests) are fixed.

## Deep references

- Docs: `docs/core/freehand/` — the Freehand guide, which replaced the donor
  chapters (mental-model, build-a-view, state, events-and-handlers,
  host-boundaries, limits-and-escapes, testing, ssr, presence). Freehand is the
  view layer new work is written against; this sheet describes what an app
  already on `re-frame.ui` has today.
- Spec: `spec/004-Views.md` (the normative grammar).
- API: the `re-frame.ui` / `re-frame.ui.test` API references.")

(defn- render-authoring-surface
  "The GENERATED authoring-surface slice — the public re-frame.ui var set
   from the manifest, split into taught-here vs deliberately-out-of-scope
   (each with a pointer). Drives the sheet off the API manifest so a var
   added to / removed from the surface changes this section (and the
   disposition assertion reds until it is classified)."
  [manifest-vars]
  (let [taught  (->> authoring-disposition (filter #(= :taught (val %))) (map key) sort)
        omitted (->> authoring-disposition (filter #(vector? (val %))) (sort-by key))]
    (str "## Authoring surface (GENERATED from the API manifest)\n\n"
         "The public `re-frame.ui` authoring API is **" (count manifest-vars)
         " vars** (`spec/api-manifest.edn`). Every one is either taught above or "
         "deliberately outside this compact sheet's scope; a var added to or "
         "removed from the public surface reds the context check until it is "
         "classified.\n\n"
         "**Taught here:** " (str/join ", " (map #(str "`ui/" % "`") taught)) ".\n\n"
         "**Out of scope** (use the API reference): "
         (str/join "; " (map (fn [[v [_ reason]]] (str "`ui/" v "` — " reason)) omitted))
         ".\n")))

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
    (str "## Compile-rejection roster (GENERATED)\n\n"
         "Every `:rf.ui.compile/*` diagnostic the compiler's front-door analyzers "
         "raise, extracted from their own raise sites (`env/fail!` / `fail` / "
         "`env/compile-error` rejects and `env/warn!` / a11y `report!` warnings) "
         "across a bounded source roster: `compiler.cljc`, `compiler/analyze.cljc`, "
         "`compiler/header.cljc`, `compiler/root.cljc`, `compiler/env.cljc`, "
         "`compiler/a11y.cljc`, `test.cljc`. Each bullet is the compiler's own "
         "message — it names the fix; `‹…›` marks a runtime value the compiler "
         "fills in. (`:rf.ui.compile/error` is the ex-data envelope key every "
         "compile error carries, not a diagnostic.) This section is "
         "machine-generated; do not edit it by hand.\n\n"
         "### Rejections (compile errors)\n\n"
         (str/join "\n" (map render-group rejects))
         "\n\n### Dev-time warnings\n\n"
         (str/join "\n" (map render-group warnings))
         "\n")))

(defn render-sheet
  "Render the full context sheet from the extracted roster + the manifest
   authoring surface. Deterministic and LF-normalised so the committed file
   is byte-identical across a Windows regeneration and a Linux CI check."
  [roster* manifest-vars]
  (let [raw (str "<!--\n" generated-banner "\n-->\n\n"
                 prose-body "\n\n"
                 (render-authoring-surface manifest-vars) "\n"
                 (render-roster roster*))]
    (str/replace raw "\r\n" "\n")))

;; ---------------------------------------------------------------------------
;; Entry points.
;; ---------------------------------------------------------------------------

(defn build-sheet
  "Extract the roster from the bounded compiler sources + the authoring
   surface from the manifest, assert both are healthy, and render the sheet."
  []
  (let [roster* (-> (extract-diagnostics) roster assert-non-vacuous!)
        manifest-vars (assert-disposition-covers-manifest!)]
    (render-sheet roster* manifest-vars)))

(defn generate!
  "Regenerate skills/re-frame2-ui/references/ui-context.md. Returns the
   sheet string."
  []
  (let [sheet   (build-sheet)
        roster* (roster (extract-diagnostics))]
    (io/make-parents @sheet-file)
    (spit @sheet-file sheet :encoding "UTF-8")
    (println (format "Wrote %s (%d compile-diagnostic ids, %d authoring vars)."
                     (.getPath ^java.io.File @sheet-file)
                     (count roster*)
                     (count (manifest-ui-vars))))
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
            (println "DRIFT: skills/re-frame2-ui/references/ui-context.md does not exist."
                     "Run the generator."))
          false)

      (= generated committed)
      (do (println (format "OK: skills/re-frame2-ui/references/ui-context.md in sync (%d ids, %d authoring vars)."
                           (count (roster (extract-diagnostics)))
                           (count (manifest-ui-vars))))
          true)

      :else
      (do (binding [*out* *err*]
            (println "DRIFT: generated context sheet differs from"
                     "skills/re-frame2-ui/references/ui-context.md.")
            (println "A compiler message, the API surface, or the authored prose"
                     "changed. Regenerate with:")
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
