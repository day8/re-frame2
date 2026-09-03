(ns re-frame.migration.hicasso.census
  "The corpus census — the REPORTER's second half, and the one that
  answers *what is in this codebase* rather than *what can I fix*.

  ## Why there are two halves at all

  The fixer next door has a report, and it is exhaustive over its own
  population: every `[:>]`, `[:r>]`, `[:f>]` and `(r/adapt-react-class …)`
  head the tool reached. That population is a **crossing**. It is not a
  Reagent codebase.

  A file whose whole Reagent surface is

      (defn counter []
        (let [n (r/atom 0)]
          (fn [] [:div {:on-click #(swap! n inc)} @n])))

  contains no crossing, so the fixer walks it and reports NOTHING — not
  \"clean\", not \"nothing to do\": nothing. A migrator reading that report
  sees a file that is not mentioned, and a file that is not mentioned is
  the one shape a report must never make ambiguous. Reagent's local
  reactive cell is the single most common thing a Hicasso migration has to
  answer for, and it was invisible.

  So the census walks the same files for the **view-substrate API
  surface**, and the two halves partition the question rather than the
  population:

  | Half | Estimand | Addressed by |
  |---|---|---|
  | fixer (`:entries`) | `[:>]`-family crossing SITES | site line/col |
  | census (`:census`) | view-substrate API CALL SITES | call line/col |

  *(3 columns; 2 body rows.)*

  They are different estimands and both are named, so neither is a
  denominator for the other. A `(r/atom …)` inside a crossing's props is
  one crossing site to the fixer and one call site here; the numbers are
  not double-counted because they are not the same count.

  **A CALL site is source that RUNS**, so `#_(r/atom 0)`, `'(r/atom 0)`
  and `(comment (r/atom 0))` are not among them and
  [[re-frame.migration.hicasso.rewrite/inert?]] prunes them. An advertised
  estimand a reader can construct a counterexample to in one line is worse
  than a vaguer one honestly stated.

  ## Two rosters, because `reagent.core` is not the only way in

  The census reads its population off a NAMESPACE, and for most of its
  life it knew one family of them. That made it score a real re-frame2
  application at ZERO — not wrongly, but uselessly. A re-frame2 app on the
  Reagent adapter renders through Reagent and never names it: views are
  declared with `reg-view`, reads are `@(subscribe […])`, and the
  substrate arrives through `re-frame.adapter.reagent`, which is not
  `reagent.core`. Not one `reagent.core` name anywhere. The report's zero
  was a true statement about a population that was not that application's
  migration surface, and the surrounding prose — *absence from the report
  is meaningful* — invited exactly the wrong conclusion. It was found by a
  blinded pilot migrating a real application, which is the only kind of
  witness that finds this class of defect (rf2-xoal).

  So there are two rosters, and the SECOND one is
  [[substrate-surface]]: re-frame2's own `re-frame.adapter.*` adapters,
  bound by a PREFIX RULE rather than a list, because the adapter set is
  open and a list goes stale into the same silent zero one adapter later.
  [[surface]] widened too, by the same principle in the other family:
  the slim adapter's `reagent2.*` IS Reagent's API, authored a second time
  in this repository.

  **The rosters are kept apart rather than merged**, because
  `:files-with-reagent` is read by the migration skill to decide whether a
  Reagent COORDINATE can be dropped — a question about Reagent, not about
  the substrate — and folding adapter files into that count would answer
  it wrongly.

  ## A tool that recognises nothing must not sound like one that found nothing

  **No roster is ever wide enough**, and that is the deeper half of the
  same defect: whatever this census knows, some codebase renders through a
  surface it does not, and over that corpus every count comes back zero in
  precisely the voice of a clean bill of health. Widening the roster
  postpones that; it cannot prevent it.

  So the tool is made unable to report the confident zero. Every scan
  answers `:recognised?`, [[summarise]] carries `:recognition` and a
  `:caveat` sentence, the file counts PARTITION the corpus so an
  unrecognised file is a bucket rather than a gap, and
  [[re-frame.migration.hicasso.report/build]] hoists the verdict to the
  TOP of the artefact — because the reader who is going to be misled is
  the one who reads the first summary and stops.

  ## The law this file exists to obey

  **What cannot be resolved is REPORTED, never skipped.** [[ns-context]]
  answers `#{}` for a require it cannot resolve. Such a file would
  otherwise be silently a file with no Reagent in it: every `r/atom`,
  `r/as-element` and `(r/partial …)` in it invisible, W4 and W5 dead, and
  the report non-empty enough (the `:>` head needs no alias) that nobody
  would suspect a hole. That is a census that cannot fail, which is a
  census that cannot be believed.

  It is `:unresolved-reagent-require`, at the `ns` form's own line, and
  every roster-named call in such a file is `:unresolved-alias` with the
  symbol it could not bind.

  **The population that class names has SHRUNK, and deliberately.** The
  original one was `#?(:cljs [reagent.core :as r])` — the only legal way
  to require Reagent from a `.cljc` file, and so the commonest unbindable
  require there was. rf2-m4hm taught [[ns-context]] to read require
  clauses structurally, through the reader-conditional node, so that shape
  now BINDS: its call sites get their real classes and the fixer sees the
  file. What remains is the genuinely undecidable — chiefly a namespace
  that spells a Reagent name without being Reagent's, such as
  re-frame-10x's vendored
  `day8.re-frame-10x.inlined-deps.reagent.v1v2v0.reagent.core`. The tool
  reports those and does not guess at them; guessing would be a worse
  tool than the blind one.

  ## What it does not guess

  A Form-2 component is a `defn` returning a `fn`, and nothing else marks
  it. There is no roster to consult, and a structural test would report
  every higher-order function in the corpus as a migration blocker. The
  census names what it can name: the `r/atom` a Form-2 almost always
  closes over is on the roster, and the shape it cannot name it does not
  count. A confident wrong number is worse than a stated silence."
  (:require [clojure.string :as str]
            [re-frame.migration.hicasso.rewrite :as rw]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(def surface
  "Reagent's public API, and what a Hicasso migration owes each entry.

  One row per callable. The `:class` is what the report says; the
  `:verdict` is the migrator's triage bucket, and the three buckets are
  the ones the migration page already teaches — **mechanical**, **human
  decision**, **runtime blocker**.

  Nothing here is `:mechanical`. That is a FINDING, not an omission: every
  mechanical rewrite the tool family knows lives in the fixer's W1–W6, and
  those all sit at a crossing. Outside a crossing, Reagent's API asks for a
  state-ownership or lifecycle decision that no source reader can make.
  [[summarise]] therefore emits `:mechanical 0` explicitly rather than
  leaving the key absent, so an empty bucket reads as a measurement."
  '{atom                      {:class :local-reactive-cell     :verdict :runtime-blocker}
    cursor                    {:class :derived-cell            :verdict :runtime-blocker}
    track                     {:class :derived-cell            :verdict :runtime-blocker}
    track!                    {:class :derived-cell            :verdict :runtime-blocker}
    reaction                  {:class :derived-cell            :verdict :runtime-blocker}
    make-reaction             {:class :derived-cell            :verdict :runtime-blocker}
    run!                      {:class :derived-cell            :verdict :runtime-blocker}
    with-let                  {:class :with-let                :verdict :human-decision}
    create-class              {:class :lifecycle-class         :verdict :runtime-blocker}
    as-element                {:class :as-element              :verdict :runtime-blocker}
    reactify-component        {:class :outward-bridge          :verdict :human-decision}
    adapt-react-class         {:class :adapt-react-class       :verdict :human-decision}
    create-element            {:class :react-create-element    :verdict :human-decision}
    merge-props               {:class :props-helper            :verdict :human-decision}
    partial                   {:class :reagent-partial         :verdict :human-decision}
    dom-node                  {:class :component-introspection :verdict :runtime-blocker}
    current-component         {:class :component-introspection :verdict :runtime-blocker}
    props                     {:class :component-introspection :verdict :runtime-blocker}
    children                  {:class :component-introspection :verdict :runtime-blocker}
    argv                      {:class :component-introspection :verdict :runtime-blocker}
    state                     {:class :component-introspection :verdict :runtime-blocker}
    state-atom                {:class :component-introspection :verdict :runtime-blocker}
    set-state                 {:class :component-introspection :verdict :runtime-blocker}
    replace-state             {:class :component-introspection :verdict :runtime-blocker}
    force-update              {:class :render-control          :verdict :human-decision}
    force-update-all          {:class :render-control          :verdict :human-decision}
    flush                     {:class :render-control          :verdict :human-decision}
    next-tick                 {:class :render-control          :verdict :human-decision}
    after-render              {:class :render-control          :verdict :human-decision}
    render                    {:class :root-mount              :verdict :human-decision}
    unmount-component-at-node {:class :root-mount              :verdict :human-decision}
    create-root               {:class :root-mount              :verdict :human-decision}
    hydrate-root              {:class :root-mount              :verdict :human-decision}})

(def substrate-surface
  "re-frame2's OWN substrate-adapter API — the SECOND roster, and the one
  whose absence made this census score a real re-frame2 application at
  zero.

  ## Why a second roster and not a wider first one

  The reported failure was a re-frame2 application on the Reagent adapter:
  it renders through Reagent, and not one `reagent.core` name appears
  anywhere in it. It reaches the substrate through
  `re-frame.adapter.reagent`, which is not `reagent.core` — so the first
  roster, which classifies by namespace, had no population in that
  codebase and reported a confident zero over it.

  The obvious repair — bolt `re-frame.adapter.reagent` onto
  `reagent-namespaces` — is the wrong one twice over. It would make
  `:files-with-reagent` (a documented half of this report, which the
  migration skill reads to decide whether a Reagent COORDINATE can be
  dropped) count files with no Reagent name in them; and it would leave
  the roster an enumeration, so the next adapter along reproduces the same
  silent zero. Two rosters, two counts, and a PREFIX RULE for this one:
  see [[re-frame.migration.hicasso.rewrite/substrate-ns-prefix]].

  ## What is on it

  **The adapters' DOCUMENTED public surface**, which is `docs/api/`'s two
  adapter pages, and nothing invented beside it. Three shapes are
  deliberately absent and each absence is a decision:

  * `adapter` — the substrate value passed to `rf/init!`. It is the single
    most diagnostic line in a re-frame2 boot, and it is not a CALL: it
    sits in argument position, where this walk (which reads call HEADS)
    cannot see it. The file it lives in is still RECOGNISED, because its
    `ns` form names the adapter, so nothing rides on catching it.
  * `frame-provider` / `frame-root` — hiccup HEADS rather than calls, and
    frame plumbing that survives the migration rather than migration work.
  * `re-frame.adapter.test-react`'s `mount!` / `trigger-update!` — the
    prefix rule binds that namespace, but the roster is the documented
    consumer surface and the test adapter has no `docs/api/` page.

  Nothing here is `:mechanical` either, for the same reason nothing in
  [[surface]] is: no rewrite in this tool family reaches any of it.

  **The two rosters must not share a NAME.** [[scan]] tries Reagent first,
  so an overlap would silently classify a substrate call under a Reagent
  class and a Reagent recovery sentence — a wrong answer wearing the right
  shape, which is the one failure this file's whole design is against.
  `census_test` asserts the intersection is empty rather than leaving it to
  whoever adds the next row."
  '{client-root         {:class :root-mount            :verdict :human-decision}
    render!             {:class :root-mount            :verdict :human-decision}
    unmount!            {:class :root-mount            :verdict :human-decision}
    use-subscribe       {:class :substrate-read-hook   :verdict :human-decision}
    use-frame           {:class :substrate-read-hook   :verdict :human-decision}
    use-current-frame   {:class :substrate-read-hook   :verdict :human-decision}
    wrap-view           {:class :substrate-view-seam   :verdict :human-decision}
    set-hiccup-emitter! {:class :substrate-view-seam   :verdict :human-decision}
    flush-views!        {:class :substrate-test-seam   :verdict :human-decision}})

(def ^:private notes
  "One recovery sentence per class, in the migrator's vocabulary. The
  report's fourth property (§7.4) is that a refusal names the fix, and a
  census entry is a refusal with a wider fence than the fixer's."
  {:local-reactive-cell
   (str "Reagent's local reactive cell. Hicasso has NO view-local state tier at all, so this "
        "is not a syntax change: decide where the fact lives — an app-db address, the forms "
        "module's draft, or inside a declared native host if it is genuinely widget mechanics "
        "and not an application fact.")

   :derived-cell
   (str "A Reagent derived cell — `cursor`, `track`, `reaction`, `run!`. In Hicasso a derived "
        "value is a layered subscription, registered once and read at the point of use. Port "
        "the derivation to a `sub`; a cell created inside render has no home here.")

   :with-let
   (str "`r/with-let` gives a render body a once-per-mount binding and a `finally` teardown. "
        "The binding half is an ordinary `let`. The TEARDOWN half is the part that needs a "
        "decision — durable state belongs outside render, so the cleanup becomes an unmount "
        "event or a declared host's release path, and it will not survive a mechanical edit.")

   :lifecycle-class
   (str "A form-3 component. React lifecycle is React's, not Hicasso's: port it to callback "
        "refs, or to a named native component that owns its own lifecycle behind a declared "
        "host.")

   :as-element
   (str "`r/as-element` lowers Hiccup for a foreign caller. Hicasso's counterpart is "
        "`h/as-element` under a declared `:render` callback contract — and the closure usually "
        "runs OUTSIDE the owner's render window, when the library calls it, so this is not a "
        "text substitution.")

   :outward-bridge
   (str "`r/reactify-component` hands a Reagent component to React. The counterpart is "
        "`h/as-component`, the outward bridge. Check what the foreign side does with the props "
        "it passes: the two bridges do not convert them alike.")

   :adapt-react-class
   (str "`r/adapt-react-class` outside a Hiccup head position — bound to a name, or passed on. "
        "W5 can only take the INLINE head form; a bound one changes the conversion regime of "
        "every call site at once, including call sites this run never saw.")

   :react-create-element
   (str "`r/create-element` builds a React element directly. Hicasso's raw crossing is `[:> …]`, "
        "and a repeated one graduates to a declared host — but the props dialect differs, so "
        "read the crossing's rules before respelling this.")

   :props-helper
   (str "`r/merge-props` merges under Reagent's own class/style rules. Hicasso composes classes "
        "under its own accepted spellings; check the merged result rather than assuming the two "
        "rules agree.")

   :reagent-partial
   (str "`r/partial` is `clojure.core/partial` plus the marker that made Reagent treat the "
        "result as a value rather than a callback. Outside a crossing that marker buys nothing, "
        "so plain `partial` is usually right — but confirm nothing downstream tests for it.")

   :component-introspection
   (str "This reads or writes the CURRENTLY-RENDERING Reagent component — its props, children, "
        "argv, replaceable state, or its DOM node. Hicasso has no such ambient component "
        "object. Pass the value in as data, or own the node from a declared native host.")

   :render-control
   (str "This drives Reagent's own render scheduler. Hicasso commits on its own clock and "
        "exposes no equivalent lever; a migration that needed one is usually a migration with a "
        "read still living outside the frame.")

   :root-mount
   (str "Root mounting. Hicasso mounts its own root; this call is boot ceremony and is replaced "
        "wholesale rather than edited.")

   :substrate-read-hook
   (str "A read through the substrate adapter's own hook tier - the UIx adapter's "
        "`use-subscribe` / `use-frame` / `use-current-frame`. Hicasso reads with `h/sub` at the "
        "point of use inside a declared view, so this is not a hook swap: the value stops "
        "arriving through React's hook order and starts arriving through the view's own "
        "reactive read, and a component whose hooks were conditional has to be re-shaped.")

   :substrate-view-seam
   (str "The substrate adapter's own view seam - `wrap-view`, or the hiccup emitter the adapter "
        "was told to lower through. Hicasso owns both ends of that seam itself: views are "
        "declared with `h/defview` and lowered by Hicasso's own compiler, so an explicit seam "
        "call has no counterpart to be respelled into. Code-gen and library scaffolding that "
        "minted these needs a Hicasso-side equivalent rather than an edit.")

   :substrate-test-seam
   (str "`flush-views!` wraps React's `act()` so a test can settle pending effects before "
        "reading the DOM. It is a REAGENT/UIX-substrate seam, per Spec 008's per-adapter-require "
        "rule, so it does not follow the views across: settle a Hicasso tree the way the "
        "Hicasso testing chapter directs, and delete the adapter require the call came through.")

   :unresolved-reagent-require
   (str "This file's `ns` form NAMES a Reagent namespace, and the reader could not bind a "
        "single symbol to it. The usual cause is a namespace that SPELLS a Reagent name "
        "without being Reagent's — a vendored or inlined copy such as "
        "`day8.re-frame-10x.inlined-deps.reagent.v1v2v0.reagent.core`, or a submodule outside "
        "the roster. EVERY TOOL IN THIS FAMILY IS PARTIALLY BLIND IN THIS FILE: `r/partial` is "
        "not wrapped (W4), `(r/adapt-react-class …)` is not respelled (W5), and Reagent API "
        "inside a crossing is not named. Read this file by hand. The tool will not guess that "
        "such a copy is `reagent.core`: a wrong binding rewrites working code, which is worse "
        "than naming what it cannot resolve.")

   :unresolved-alias
   (str "A call whose NAME is on the Reagent roster, in a file whose Reagent require the reader "
        "could not bind. It is reported rather than classified because the tool cannot tell "
        "whether this symbol is Reagent's or something else's — which is exactly why it is "
        "here and not silently absent.")})

;; ---------------------------------------------------------------------------
;; The walk
;; ---------------------------------------------------------------------------

(defn- roster-name
  "The key `roster` holds for this node's head, whether or not it resolves.

  Usually the head's own name — `r/atom` and a bare `atom` both spell
  `atom`. The exception is a `:rename`d referral, where the file bound
  `reagent.core/atom` to some other spelling and the head is `ratom`; the
  roster key is then the ORIGINAL name, which is the only one the roster
  has a class and a recovery sentence for."
  [nd ctx roster]
  (when-let [h (rw/head-symbol nd)]
    (let [k (or (when-not (namespace h) (get (:renamed ctx) h))
                (symbol (name h)))]
      (when (contains? roster k) k))))

(defn- entry
  [{:keys [class verdict]} file line col form detail]
  (cond-> {:class   class
           :verdict verdict
           :file    file
           :line    line
           :col     col
           :form    form
           :note    (get notes class)}
    detail (assoc :detail detail)))

(defn scan
  "Every rostered view-substrate API call site in one source string.

  Returns `{:entries [...] :reagent? bool :substrate? bool :unresolved?
  bool :recognised? bool}`.

  **TWO ROSTERS, TWO CONTEXTS, ONE WALK.** [[surface]] is Reagent's API and
  [[substrate-surface]] is re-frame2's own adapter API; the file's `ns`
  form is read once per roster (`ns-context` takes the roster predicate)
  and every node is offered to both. Reagent is tried first, and
  [[roster-overlap]] is empty so the order decides nothing.

  **The two recognition flags are asymmetric, deliberately.**

  * `:reagent?` is whether the file NAMES a Reagent namespace at all, text
    and all, even one nothing could be bound to. That is the right question
    for Reagent because the FIXER rides on the same bindings: a name it
    cannot bind means W4 and W5 are dead in this file, which is a hole
    worth reporting (`:unresolved-reagent-require`).
  * `:substrate?` is whether the `ns` form actually BINDS a
    `re-frame.adapter.*` namespace. Nothing in the fixer rides on it, so a
    spelling the reader cannot bind is not evidence of anything and gets no
    class of its own. It also keeps the prefix rule honest from the other
    end: `my.vendored.re-frame.adapter.reagent` binds nothing and so
    counts for nothing.

  `:recognised?` is their disjunction, and it is the flag that stops this
  census reporting a confident zero over a corpus it never had a population
  in — see [[summarise]].

  **CALL sites, so inert subtrees are pruned rather than walked.**
  The population is what the program runs, and a discard, a quote and a
  `(comment …)` body are none of it."
  [source file]
  (let [root        (p/parse-string-all source)
        rctx        (rw/ns-context root rw/reagent-namespace?)
        sctx        (rw/ns-context root rw/substrate-namespace?)
        reagent?    (rw/names-reagent? root)
        substrate?  (boolean (seq (into (:aliases sctx) (:referred sctx))))
        unresolved? (and reagent? (empty? (into (:aliases rctx) (:referred rctx))))
        excerpt     (fn [loc] (let [s (z/string loc)]
                                (if (> (count s) 200) (str (subs s 0 200) " …") s)))
        ns-node?    rw/ns-form?]
    (loop [loc      (z/of-string source {:track-position? true})
           entries  []
           ns-said? false]
      (if (or (nil? loc) (z/end? loc))
        {:entries     entries
         :reagent?    reagent?
         :substrate?  substrate?
         :unresolved? unresolved?
         :recognised? (or reagent? substrate?)}
        (if (rw/inert? (z/node loc))
          (recur (rw/past-subtree loc) entries ns-said?)
          (let [nd         (z/node loc)
                rk         (roster-name nd rctx surface)
                sk         (roster-name nd sctx substrate-surface)
                ns-here?   (and unresolved? (not ns-said?) (ns-node? nd))
                [line col] (when (or rk sk ns-here?) (z/position loc))]
            (recur
             (z/next loc)
             (cond
               ;; The `ns` form of a file that names Reagent and binds
               ;; nothing to it. Reported at the form itself, so the fix is
               ;; where the line number points. Once per file: `ns-form?`
               ;; sees through metadata, so `^:cljstyle/ignore (ns …)`
               ;; matches at the meta node AND at the list inside it.
               ns-here?
               (conj entries (entry {:class   :unresolved-reagent-require
                                     :verdict :runtime-blocker}
                                    file line col (excerpt loc) nil))

               ;; Resolved: this really is Reagent's, through a symbol the
               ;; `ns` form binds.
               (and rk (rw/bound-call? nd rk rctx))
               (conj entries (entry (get surface rk) file line col (excerpt loc)
                                    {:api (str rk)}))

               ;; Resolved against the SECOND roster: re-frame2's own
               ;; substrate adapter, through a symbol the `ns` form binds.
               ;; This is the arm the reported defect was missing — a
               ;; re-frame2 application on the Reagent adapter has every one
               ;; of its substrate calls here and none in the arm above.
               (and sk (rw/bound-call? nd sk sctx))
               (conj entries (entry (get substrate-surface sk) file line col (excerpt loc)
                                    {:api (str sk)}))

               ;; Unresolvable, in a file we KNOW reaches for Reagent.
               ;; Reported, never skipped — the tool cannot tell whose symbol
               ;; this is, and saying so is the whole point.
               ;;
               ;; QUALIFIED HEADS ONLY. The thing that could not be bound is
               ;; an ALIAS, so a call with no alias to bind is not evidence
               ;; of it: `(atom nil)` is `clojure.core/atom`, and the SSR
               ;; examples in this repository have one on the line above the
               ;; `rdc/render` that IS the finding. Reporting both makes the
               ;; real one harder to see, which is the only thing a census
               ;; owes anybody.
               (and rk unresolved? (namespace (rw/head-symbol nd)))
               (conj entries (entry {:class   :unresolved-alias
                                     :verdict :runtime-blocker}
                                    file line col (excerpt loc)
                                    {:api    (str rk)
                                     :symbol (str (rw/head-symbol nd))}))

               :else entries)
             (or ns-said? ns-here?))))))))

;; ---------------------------------------------------------------------------
;; The summary
;; ---------------------------------------------------------------------------

(def verdicts
  "Every bucket, always emitted. A count that can only appear when it is
  non-zero is a count nobody can tell from a bug."
  [:mechanical :human-decision :runtime-blocker])

(defn recognition
  "Did this census have a POPULATION in the corpus it was pointed at?

  Four states, and the middle two are the whole reason this exists.

  * `:full` — every scanned file names Reagent or a re-frame2 substrate
    adapter. A zero here means the tool looked and found nothing.
  * `:partial` — some files named one and some named neither.
  * `:none` — files were scanned and NOT ONE named either. The tool had
    nothing to count, and it says so rather than passing for a clean bill
    of health.
  * `:no-files` — nothing was scanned at all.

  **This is the defect the roster widening only half closes.** A
  classifier that recognises nothing answers `0` in exactly the voice of
  one that recognised everything and found nothing wrong, and no roster is
  ever wide enough to make that impossible — the next view surface along
  reproduces it. So the tool is made unable to report the confident zero
  instead: recognition is measured, named, and carried at the TOP of the
  artefact as well as here."
  [files-scanned files-recognised]
  (cond (zero? files-scanned)              :no-files
        (zero? files-recognised)           :none
        (= files-scanned files-recognised) :full
        :else                              :partial))

(defn caveat
  "The recognition verdict as a sentence a migrator can act on. Always
  present, `:full` included — a warning that appears only when something
  is wrong is a warning nobody can tell from a missing key, which is the
  rule [[verdicts]] already states for the counts."
  [{:keys [files-scanned files-recognised files-unrecognised files-with-substrate]}]
  (case (recognition files-scanned files-recognised)
    :no-files
    (str "NO SOURCE FILE WAS SCANNED. Every count here is zero because there was nothing to "
         "count — check the paths given to the tool.")

    :none
    (str "NOTHING WAS RECOGNISED. " files-scanned " file(s) were scanned and NOT ONE of them "
         "names Reagent or a re-frame2 substrate adapter, so this census had no population in "
         "this corpus at all. THIS ZERO IS NOT A CLEAN BILL OF HEALTH: it is the tool saying it "
         "does not know what it is looking at. Check the paths you pointed it at first; then "
         "read the migration chapter's translation table, because a codebase that renders "
         "through some other view surface has migration work this tool cannot see.")

    :partial
    (str files-unrecognised " of " files-scanned " scanned file(s) name neither Reagent nor a "
         "re-frame2 substrate adapter, and this census has NO POPULATION in them: their absence "
         "from the entries below means they were not recognised, not that they are clean."
         (when (pos? files-with-substrate)
           (str " This corpus DOES name a re-frame2 substrate adapter, so it is a re-frame2 "
                "application — the case where the distinction bites hardest. Its view files "
                "declare views with `reg-view`, read with `@(subscribe …)` and dispatch with "
                "`#(dispatch …)`, and NONE of that is Reagent API, so they are absent from this "
                "census AND full of migration work. Port those shapes off the migration "
                "chapter's translation table.")))

    :full
    (str "Every one of the " files-scanned " scanned file(s) names Reagent or a re-frame2 "
         "substrate adapter, so this census had a population throughout. A zero below is a "
         "measurement.")))

(defn summarise
  "The census half of the report artefact.

  **The file counts PARTITION the corpus, which they did not before.** The
  reported defect came with a bookkeeping tell beside it — 18 files
  scanned, with `:files-with-reagent`, `:files-unresolved` and
  `:files-clean` all zero, three sub-counts summing to 0 rather than 18 —
  and the missing bucket was the very thing the zero was hiding: the files
  the census had no population in. The arithmetic now closes twice over:

      files-scanned    = files-recognised + files-unrecognised
      files-recognised = files-clean      + (files carrying an entry)

  `:files-with-reagent` and `:files-with-substrate` are overlapping DETAIL
  views of the recognised half rather than a partition of it — one file
  can name both — and `:files-unresolved` is a subset of the first.
  `:files-with-reagent` keeps its exact meaning through the widening,
  because the migration skill reads it to decide whether a Reagent
  COORDINATE may be dropped, and that question is about Reagent rather
  than about the substrate."
  [{:keys [entries files-scanned files-with-reagent files-with-substrate
           files-unresolved files-recognised]}]
  (let [files-unrecognised (- files-scanned files-recognised)]
    (array-map
     :estimand "Rostered view-substrate API call sites, addressed at the CALL: Reagent's API (`reagent.*`, and the slim adapter's `reagent2.*`) and re-frame2's own substrate adapters (`re-frame.adapter.*`). The fixer's `:entries` count `[:>]`-family crossing SITES; the two are different populations and neither is a denominator for the other. Read `:recognition` before reading any zero here."
     :files-scanned        files-scanned
     :files-recognised     files-recognised
     :files-unrecognised   files-unrecognised
     :files-with-reagent   files-with-reagent
     :files-with-substrate files-with-substrate
     :files-unresolved     files-unresolved
     :files-clean          (- files-recognised
                              (count (into #{} (map :file) entries)))
     :entries              (count entries)
     :recognition          (recognition files-scanned files-recognised)
     :caveat               (caveat {:files-scanned        files-scanned
                                    :files-recognised     files-recognised
                                    :files-unrecognised   files-unrecognised
                                    :files-with-substrate files-with-substrate})
     :by-verdict           (into (sorted-map)
                                 (for [v verdicts]
                                   [v (count (filter #(= v (:verdict %)) entries))]))
     :by-class             (into (sorted-map)
                                 (for [[k v] (group-by :class entries)] [k (count v)])))))

(defn build
  "Assemble the census section from the per-file scans."
  [scans]
  (let [entries (vec (mapcat :entries scans))]
    {:summary (summarise {:entries              entries
                          :files-scanned        (count scans)
                          :files-recognised     (count (filter :recognised? scans))
                          :files-with-reagent   (count (filter :reagent? scans))
                          :files-with-substrate (count (filter :substrate? scans))
                          :files-unresolved     (count (filter :unresolved? scans))})
     :entries (vec (sort-by (juxt #(str (:file %)) #(or (:line %) 0) #(or (:col %) 0)
                                  #(str (:class %)))
                            entries))}))

(defn print-lines
  "One line per entry, the shape the fixer's report already prints."
  [entries]
  (doseq [{:keys [file line col class verdict detail]} entries]
    (println (format "%s:%s:%s  %-26s %-16s %s"
                     (or file "-") (or line "-") (or col "-")
                     (name class) (name verdict)
                     (or (:api detail) (:symbol detail) "")))))

(defn scan-string
  "Programmatic entry point over one source string."
  ([s] (scan-string s nil))
  ([s file] (scan s (some-> file str))))

(defn describe
  "A one-line human summary, for the CLI tail.

  **It leads with the recognition verdict, not with the count**, because
  the count is the thing that misleads: `0 Reagent API call site(s)` is
  the same sentence whether the tool searched a Reagent codebase and found
  nothing or searched a codebase it could not classify at all. Whoever
  reads only the last line of the run has to be told which."
  [{:keys [summary]}]
  (let [{:keys [entries files-scanned files-recognised files-unrecognised
                files-with-reagent files-with-substrate files-unresolved
                by-verdict]} summary]
    (case (recognition files-scanned files-recognised)
      :no-files
      "no source file scanned — nothing to census"

      :none
      (str "NOTHING RECOGNISED — " files-scanned " file(s) scanned, none of them naming Reagent "
           "or a re-frame2 substrate adapter. This zero is NOT a clean bill of health; read "
           ":census :summary :caveat.")

      (str entries " view-substrate API call site(s) across " files-with-reagent
           " file(s) that name Reagent and " files-with-substrate " that name a re-frame2 adapter"
           (when (pos? files-unresolved)
             (str "; " files-unresolved " file(s) UNRESOLVED"))
           " — " (str/join ", " (for [[k v] by-verdict] (str v " " (name k))))
           (when (pos? files-unrecognised)
             (str "; " files-unrecognised " of " files-scanned
                  " file(s) NOT RECOGNISED — read :census :summary :caveat"))))))
