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

  So the census walks the same files for the **Reagent API surface**, and
  the two halves partition the question rather than the population:

  | Half | Estimand | Addressed by |
  |---|---|---|
  | fixer (`:entries`) | `[:>]`-family crossing SITES | site line/col |
  | census (`:census`) | Reagent API CALL SITES | call line/col |

  *(3 columns; 2 body rows.)*

  They are different estimands and both are named, so neither is a
  denominator for the other. A `(r/atom …)` inside a crossing's props is
  one crossing site to the fixer and one call site here; the numbers are
  not double-counted because they are not the same count.

  ## The law this file exists to obey

  **What cannot be resolved is REPORTED, never skipped.** [[ns-context]]
  answers `#{}` for a require the reader cannot read, and the common one
  is not exotic — `#?(:cljs [reagent.core :as r])` is the ONLY legal way
  to require Reagent from a `.cljc` file. Before this namespace, such a
  file was silently a file with no Reagent in it: every `r/atom`,
  `r/as-element` and `(r/partial …)` in it invisible, W4 and W5 dead, and
  the report non-empty enough (the `:>` head needs no alias) that nobody
  would suspect a hole. That is a census that cannot fail, which is a
  census that cannot be believed.

  It is now `:unresolved-reagent-require`, at the `ns` form's own line, and
  every roster-named call in such a file is `:unresolved-alias` with the
  symbol it could not bind. The FIXER's blindness is unchanged and
  deliberate — a fixer that starts rewriting `.cljc` files it previously
  could not see is a behaviour change, and this is a reporter — so the
  census's job is to make the hole loud, not to paper over it.

  ## What it does not guess

  A Form-2 component is a `defn` returning a `fn`, and nothing else marks
  it. There is no roster to consult, and a structural test would report
  every higher-order function in the corpus as a migration blocker. The
  census names what it can name: the `r/atom` a Form-2 almost always
  closes over is on the roster, and the shape it cannot name it does not
  count. A confident wrong number is worse than a stated silence."
  (:require [clojure.string :as str]
            [re-frame.migration.hicasso.rewrite :as rw]
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

   :unresolved-reagent-require
   (str "This file's `ns` form NAMES a Reagent namespace, and the reader could not bind a "
        "single symbol to it — a reader-conditional require (`#?(:cljs [reagent.core :as r])`), "
        "the only legal way to require Reagent from a `.cljc` file, is the usual cause. EVERY "
        "TOOL IN THIS FAMILY IS PARTIALLY BLIND IN THIS FILE: `r/partial` is not wrapped (W4), "
        "`(r/adapt-react-class …)` is not respelled (W5), and Reagent API inside a crossing is "
        "not named. Read this file by hand, or move the require out of the reader conditional "
        "and re-run.")

   :unresolved-alias
   (str "A call whose NAME is on the Reagent roster, in a file whose Reagent require the reader "
        "could not bind. It is reported rather than classified because the tool cannot tell "
        "whether this symbol is Reagent's or something else's — which is exactly why it is "
        "here and not silently absent.")})

;; ---------------------------------------------------------------------------
;; The walk
;; ---------------------------------------------------------------------------

(defn- head-symbol
  "This node's head, when it is a call through a symbol."
  [nd]
  (when (rw/list-node? nd)
    (let [h (rw/sexpr-safe (rw/element-at nd 0))]
      (when (symbol? h) h))))

(defn- roster-name
  "The roster key this node's head spells, whether or not it resolves."
  [nd]
  (when-let [h (head-symbol nd)]
    (let [k (symbol (name h))]
      (when (contains? surface k) k))))

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
  "Every Reagent API call site in one source string.

  Returns `{:entries [...] :reagent? bool :unresolved? bool}`.
  `:reagent?` is whether the file names a Reagent namespace at all, which
  is what lets the summary distinguish *scanned and clean* from *scanned
  and irrelevant* — the fixer's report already carries `:sites-left-alone`
  for the same reason."
  [source file]
  (let [root        (p/parse-string-all source)
        ctx         (rw/ns-context root)
        reagent?    (rw/names-reagent? root)
        unresolved? (and reagent? (empty? (into (:aliases ctx) (:referred ctx))))
        excerpt     (fn [loc] (let [s (z/string loc)]
                                (if (> (count s) 200) (str (subs s 0 200) " …") s)))
        ns-node?    rw/ns-form?]
    (loop [loc     (z/of-string source {:track-position? true})
           entries []]
      (if (z/end? loc)
        {:entries entries :reagent? reagent? :unresolved? unresolved?}
        (let [nd         (z/node loc)
              k          (roster-name nd)
              [line col] (when (or k (and unresolved? (ns-node? nd))) (z/position loc))]
          (recur
           (z/next loc)
           (cond
             ;; The `ns` form of a file that names Reagent and binds
             ;; nothing to it. Reported at the form itself, so the fix is
             ;; where the line number points.
             (and unresolved? (ns-node? nd))
             (conj entries (entry {:class   :unresolved-reagent-require
                                   :verdict :runtime-blocker}
                                  file line col (excerpt loc) nil))

             (nil? k) entries

             ;; Resolved: this really is Reagent's, through a symbol the
             ;; `ns` form binds.
             (rw/reagent-call? nd k ctx)
             (conj entries (entry (get surface k) file line col (excerpt loc) {:api (str k)}))

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
             (and unresolved? (namespace (head-symbol nd)))
             (conj entries (entry {:class   :unresolved-alias
                                   :verdict :runtime-blocker}
                                  file line col (excerpt loc)
                                  {:api    (str k)
                                   :symbol (str (head-symbol nd))}))

             :else entries)))))))

;; ---------------------------------------------------------------------------
;; The summary
;; ---------------------------------------------------------------------------

(def verdicts
  "Every bucket, always emitted. A count that can only appear when it is
  non-zero is a count nobody can tell from a bug."
  [:mechanical :human-decision :runtime-blocker])

(defn summarise
  "The census half of the report artefact."
  [{:keys [entries files-scanned files-with-reagent files-unresolved]}]
  {:estimand "Reagent API call sites, addressed at the CALL. The fixer's `:entries` count `[:>]`-family crossing SITES; the two are different populations and neither is a denominator for the other."
   :files-scanned      files-scanned
   :files-with-reagent files-with-reagent
   :files-unresolved   files-unresolved
   :files-clean        (- files-with-reagent
                          (count (into #{} (map :file) entries)))
   :entries            (count entries)
   :by-verdict         (into (sorted-map)
                             (for [v verdicts]
                               [v (count (filter #(= v (:verdict %)) entries))]))
   :by-class           (into (sorted-map)
                             (for [[k v] (group-by :class entries)] [k (count v)]))})

(defn build
  "Assemble the census section from the per-file scans."
  [scans]
  (let [entries (vec (mapcat :entries scans))]
    {:summary (summarise {:entries            entries
                          :files-scanned      (count scans)
                          :files-with-reagent (count (filter :reagent? scans))
                          :files-unresolved   (count (filter :unresolved? scans))})
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
  "A one-line human summary, for the CLI tail."
  [{:keys [summary]}]
  (str (:entries summary) " Reagent API call site(s) across "
       (:files-with-reagent summary) " file(s) that name Reagent"
       (when (pos? (:files-unresolved summary))
         (str "; " (:files-unresolved summary) " file(s) UNRESOLVED"))
       " — " (str/join ", " (for [[k v] (:by-verdict summary)] (str v " " (name k))))))
