(ns re-frame.migration.fresco.report
  "The report (§7), which the design calls the first-class HALF of the tool
  rather than its residue.

  Six properties make it so, and each is a thing the tool would otherwise
  be free to do casually.

  1. **It is an artefact, not console output.** One EDN file, deterministic
     ordering, written on every run including a clean one. The golden
     corpus asserts it byte-for-byte, so **a change to what the tool SAYS
     is gated exactly as hard as a change to what it WRITES**. That is the
     property that makes the other five worth having.
  2. **It is exhaustive over the sites it touched or refused, and honest
     about the rest.** The summary carries the count of sites left alone,
     so \"not in the report\" is never ambiguous.
  3. **Every entry carries the source form as text, with file, line and
     column**, addressed against the INPUT file — so a scan and a rewrite
     of the same tree report the same coordinates.
  4. **Every refusal names the fix in the migrator's vocabulary.** The
     `:note` is a sentence a person can act on, not an error code.
  5. **It carries the component name, and it is the only thing in the
     system that can.** A `[:>]` refusal at runtime prints
     `raw-crossing`'s `displayName`, which is the constant `\"[:>]\"`,
     beside an empty `:declared` set — the runtime cannot say WHICH
     component refused. The report can, because it read the head.
  6. **It carries the suggestions block, labelled as guesses.**

  Exit code is 0 for any run that completed, and 1 only when a file could
  not be read or written. A migration tool that fails a build because a
  consumer's code needs a human decision is a tool that gets run once."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(def ^:private class-order
  "Report ordering within one site. Blockers first, then the refusals that
  need a decision, then the rewrites — so the top of a site's block is the
  part a migrator must act on."
  (let [ks [;; §5.3 — the destination refuses at runtime; these need a person
            :intent-needs-a-declaration :dangerous-html :r>-site :f>-site
            :as-element-island :reagent-api-residue
            ;; §5.2 — decidable, but the rewrite would itself change behaviour
            :event-carrier-goes-live :key-conflict :string-tag-unparseable
            :normalized-key-collision :css-var-repair :named-ref :amp-key
            ;; §5.1 — undecidable from the source text
            :computed-props :computed-value :computed-nested-key
            :adapt-def-site :cljc-site :parse-error
            ;; skipped-by-design
            :key-slot-named-value
            ;; the rewrites
            :nested-map-keys :namespaced-named-value :named-value
            :partial-wrapper :metadata-key
            :adapt-react-class-head :string-tag-head]]
    (zipmap ks (range))))

(defn sort-entries
  "Deterministic ordering: file, then line, then column, then the class
  order above. Two runs of the tool over the same tree emit byte-identical
  reports, which is what lets the corpus assert one."
  [entries]
  (vec (sort-by (juxt #(str (:file %)) #(or (:line %) 0) #(or (:col %) 0)
                      #(get class-order (:class %) 999)
                      #(str (:class %)))
                entries)))

(def ^:private suggestion-caution
  (str "A DECLARATION NEEDS NO CALLBACK OPTION — a callback's meaning is its CARRIER at the call "
       "site, never its prop's name. A function the library calls for MARKUP (a virtualizer's "
       "`itemContent`, Fluent's `onRenderCell`) becomes `h/render-fn`; a function whose returned "
       "intent should dispatch becomes `h/event`; anything else stays a plain function and "
       "crosses untouched. The props listed under each sketch are there because their NAMES look "
       "like event positions or because they carry a function, and a name is not a meaning: "
       "Fluent's `onRenderCell` and Ant's `onRow` are event-SPELLED props whose RETURN VALUE the "
       "caller reads. CHECK EVERY ROW AGAINST THE LIBRARY'S OWN DOCUMENTATION, which is why this "
       "tool never picks a carrier for you. PASTE A DECLARATION ONLY AFTER `--rewrite --write` HAS "
       "RUN ON ITS FILE: this tool reads `[:>]`-family vectors only, so a site already moved onto "
       "a declared host gets no entry, and its props cross unconverted — a keyword value as a "
       "Keyword, a nested map's keys as written."))

(def ^:private simple-name
  "A name `def` will take: lowercase-initial, and none of the `/` or `.`
  that a qualified or dotted symbol carries."
  #"[a-z][a-z0-9*+!?<>=_'-]*")

(defn- host-name-for
  "A `def`-able name for the sketch, derived from the head.

  The final segment of a symbol head, lower-cased — `Btn` → `btn`,
  `js/Foo.Bar` → `bar`, `some.ns/Widget` → `widget`. A NAME is the one
  thing in a declaration that nobody's behaviour rides on, so deriving one
  is free where deriving a contract is not, and a migrator renames it
  without consequence.

  A head that is an EXPRESSION rather than a symbol — `(.-Provider ctx)`,
  `@registry` — yields no segment that `def` would take, and gets the
  placeholder. That is the whole rule: anything that does not reduce to a
  clean lowercase name is not guessed at."
  [head]
  (let [seg (some-> head str/trim (str/split #"[/.]") last str/lower-case)]
    (if (and seg (re-matches simple-name seg)) seg "your-host")))

(defn- defhost-sketch
  "A ready-to-paste declaration for one component. It is TEXT in a report,
  never a form the tool writes into a file, and the hoist that would mint
  these is demand-gated and out of scope.

  **The declaration carries nothing about callbacks**, because a
  callback's meaning is its carrier at the call site, which the tool does
  not choose. The props worth checking follow the form as comments, so
  the sketch is acceptable to the door verbatim."
  [{:keys [head event-slots fn-slots]}]
  (let [slots (distinct (concat event-slots fn-slots))]
    (str "(h/defhost " (host-name-for head) " " (or head "Component") ")"
         (when (seq slots)
           (str "\n;; check the carrier at each call site — see :caution"
                (str/join (for [s slots] (str "\n;;   " s))))))))

(defn build
  "Assemble the report artefact from the per-file results.

  `:census` is a SECOND SECTION, not more `:entries`. The fixer's entries
  count `[:>]`-family crossing sites; the census counts view-substrate API
  call sites. Merging them would produce one number that answers neither
  question, and the golden corpus — which asserts `:entries` — would stop
  being a statement about the fixer.

  `:recognition` is the one thing lifted OUT of the census and repeated at
  the top level, because it is the only key that can contradict the zeros
  beside it."
  [{:keys [entries suggestions census files-scanned sites-total sites-left-alone
           files-changed dry-run?]}]
  (let [entries (sort-entries entries)
        by-class (->> entries (group-by :class)
                      (map (fn [[k v]] [k (count v)]))
                      (sort-by first)
                      (into (sorted-map)))]
    {:tool    "reagent-to-fresco/codemod (fixer)"
     :design  "docs/design/fresco/studio/reagent-codemod-against-the-landed-escape.md"

     ;; THE RECOGNITION VERDICT, HOISTED. It is the census's measurement and
     ;; it is repeated here on purpose: a migrator who reads the top of this
     ;; file and stops must not be able to take a zero for a clean bill of
     ;; health. A tool that recognised nothing and a tool that found nothing
     ;; print the same counts, and only this key separates them.
     :recognition (select-keys (:summary census) [:recognition :caveat])

     :summary {:files-scanned     files-scanned
               :files-changed     files-changed
               :dry-run?          (boolean dry-run?)
               :sites-total       sites-total
               :sites-rewritten   (count (into #{} (comp (filter #(= :rewrote (:action %)))
                                                         (map (juxt :file :line :col)))
                                               entries))
               :sites-left-alone  sites-left-alone
               :entries           (count entries)
               :by-class          by-class}
     :entries entries
     :census  census
     :suggestions
     (when (seq suggestions)
       {:caution suggestion-caution
        :components (vec (for [s (sort-by :head (distinct suggestions))]
                           (assoc s :defhost (defhost-sketch s))))})}))

(defn write!
  "Write the report to `path` as EDN. Pretty-printed with a stable key
  order so a diff of two reports is readable."
  [report path]
  (io/make-parents (io/file path))
  (spit path (with-out-str (pp/pprint report))))

(defn print-lines
  "The human-facing one-line-per-entry rendering. The EDN artefact is the
  contract; this is the thing you read while you work."
  [entries]
  (doseq [{:keys [file line col class action head]} entries]
    (println (format "%s:%s:%s  %-26s %-9s %s"
                     (or file "-") (or line "-") (or col "-")
                     (name class) (name action) (or head "")))))
