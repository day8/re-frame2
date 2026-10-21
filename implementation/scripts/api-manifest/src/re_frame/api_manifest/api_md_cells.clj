(ns re-frame.api-manifest.api-md-cells
  "Generated cells in spec/API.md tables.

  A table opts in with a marker comment on its own line above it, naming the
  namespace its rows belong to:

      <!-- api-manifest:generated re-frame.adapter.uix -->

  Each row of a marked table names one var of that namespace in its first
  cell, and four of its cells restate facts the manifest already holds, so
  the generator writes them from that var's manifest row:

      M/Fn   — the kind marker (`Fn` / `M` / `Var`) from `:kind`. A
               parenthetical after it (`Fn (UIx hook)`) stays authored.
      Status — `:status`.
      Tier   — `:tier`.
      Spec   — `:owner`. A second spec is a cross-reference and goes in
               Notes (spec/API.md §Conventions, the Spec column).

  Every other cell — the first, Signature, Notes — stays authored and is
  written back byte for byte. `rewrite` is pure text in, text out, and keeps
  each line's own ending, so a CRLF checkout regenerates identically to an LF
  one. `re-frame.api-manifest.gen` calls it from `generate!` (write) and
  `check!` (fail on any change).

  A marked table refuses what it cannot classify, naming the line: a
  malformed marker, a marker with no table under it, a table missing one of
  the four columns or holding
  no rows, a row whose cell count differs from its header's, a row naming no
  single var or a var the namespace does not carry, and an M/Fn cell holding
  anything but a marker and a parenthetical. A skipped row would be authored
  text the generator had silently stopped checking."
  (:require [clojure.string :as str]))

(def ^:private marker-re
  #"^\s*<!--\s*api-manifest:generated\s+(\S+)\s*-->\s*$")

(def ^:private marker-token
  "Any comment line carrying this token must be a well-formed marker, so a
   misspelt one fails rather than leaving its table authored."
  "api-manifest:generated")

(def ^:private generated-columns
  "The header names of the cells the generator writes, in table order."
  ["M/Fn" "Status" "Tier" "Spec"])

(def ^:private kind-markers {:fn "Fn" :macro "M" :var "Var"})

(def ^:private var-ident-re
  "A first cell naming one var: a single back-ticked symbol, bare or
   qualified. The qualifier is display (`rf.adapter.uix/`); the marker names
   the namespace the var is looked up in."
  #"`([^\s`:(\[{\"#\d][^\s`]*)`")

(def ^:private kind-cell-re
  "An M/Fn cell the generator can rewrite: an optional marker, then an
   optional authored parenthetical."
  #"^(?:(?:Fn|M|Var)\b)?\s*(\(.*\))?$")

(defn- refuse [line message data]
  (throw (ex-info (format "spec/API.md L%d: %s" line message)
                  (assoc data :line line))))

(defn- table-line?
  [text]
  (str/starts-with? (str/triml text) "|"))

(defn- split-cells
  "Split a table row into `[prefix cell ... cell suffix]`, untrimmed, on the
   unescaped pipes, so `(str/join \"|\" parts)` gives the row back exactly."
  [text]
  (str/split text #"(?<!\\)\|" -1))

(defn- row-cells
  "The untrimmed cells of a table row that starts and ends with a pipe."
  [line text]
  (let [parts (split-cells text)]
    (when-not (and (str/blank? (first parts)) (str/blank? (peek parts)) (> (count parts) 2))
      (refuse line "a generated table row must start and end with `|`" {:raw text}))
    (subvec parts 1 (dec (count parts)))))

(defn- separator-cells? [cells]
  (every? #(re-matches #":?-{2,}:?" (str/trim %)) cells))

(defn- render-cell [value]
  (str " " (str/replace value "|" "\\|") " "))

(defn- generated-values
  "The four generated cell values for one row, keyed by column name."
  [line kind-cell row]
  (let [[match parenthetical] (re-matches kind-cell-re (str/trim kind-cell))]
    (when-not match
      (refuse line (str "the M/Fn cell holds " (pr-str (str/trim kind-cell))
                        " — a generated cell is a kind marker, optionally"
                        " followed by an authored parenthetical")
              {:kind-cell kind-cell}))
    {"M/Fn"   (str/join " " (remove nil? [(kind-markers (:kind row)) parenthetical]))
     "Status" (:status row)
     "Tier"   (name (:tier row))
     "Spec"   (:owner row)}))

(defn- rewrite-row
  "Rewrite one data row's generated cells. Returns `[text changes]`."
  [{:keys [namespace header column-index]} line text by-ns+var]
  (let [cells (row-cells line text)]
    (when (not= (count cells) (count header))
      (refuse line (format "the row has %d cells and its header %d — an unescaped `|` inside a cell splits it"
                           (count cells) (count header))
              {:raw text}))
    (let [[_ ident] (re-matches var-ident-re (str/trim (first cells)))
          var-name  (when ident (subs ident (inc (or (str/last-index-of ident "/") -1))))
          row       (get by-ns+var [namespace var-name])]
      (when-not ident
        (refuse line (str "the first cell " (pr-str (str/trim (first cells)))
                          " does not name one var")
                {:raw text}))
      (when-not row
        (refuse line (format "`%s` has no manifest row in %s, the namespace the table's marker names"
                             ident namespace)
                {:raw text :namespace namespace}))
      (let [values (generated-values line (nth cells (column-index "M/Fn")) row)
            [cells' changes]
            (reduce (fn [[cells changes] column]
                      (let [i    (column-index column)
                            from (nth cells i)
                            to   (render-cell (values column))]
                        (if (= from to)
                          [cells changes]
                          [(assoc cells i to)
                           (conj changes {:line line :raw ident :column column
                                          :from (str/trim from) :to (values column)})])))
                    [cells []]
                    generated-columns)
            parts (split-cells text)]
        [(str/join "|" (concat [(first parts)] cells' [(peek parts)])) changes]))))

(defn- table-header
  "Read the marked table's header and separator at line-index `i` (the first
   non-blank line after the marker). Returns the table context."
  [namespace lines i]
  (let [[line text] (nth lines i nil)]
    (when-not (and text (table-line? text))
      (refuse (or line (count lines)) (str "the generated-table marker for " namespace
                                            " has no table under it")
              {:namespace namespace}))
    (let [header (mapv str/trim (row-cells line text))
          index  (into {} (keep-indexed (fn [k h] [h k])) header)
          [sep-line sep-text] (nth lines (inc i) nil)]
      (when-let [missing (seq (remove index generated-columns))]
        (refuse line (str "the generated table has no " (str/join ", " missing) " column")
                {:header header}))
      (when-not (and sep-text (table-line? sep-text)
                     (separator-cells? (row-cells sep-line sep-text)))
        (refuse (or sep-line line) "the generated table's header has no separator row under it" {}))
      {:namespace namespace :header header :column-index index :line line})))

(defn rewrite
  "Rewrite every marked table's generated cells in `text` (spec/API.md's
   contents) from `rows` (manifest rows). Returns

     {:text    the rewritten contents
      :changes [{:line :raw :column :from :to} ...] one per cell that moved
      :tables  [{:namespace :line :rows} ...]       one per marked table}

   Running it on its own output changes nothing. Throws `ex-info` naming the
   line on anything in a marked table it cannot classify (see ns docstring)."
  [text rows]
  (let [by-ns+var (into {} (map (juxt (juxt :namespace :var) identity)) rows)
        ;; [line-number text-without-CR CR-or-empty]
        lines     (mapv (fn [k raw]
                          (if (str/ends-with? raw "\r")
                            [(inc k) (subs raw 0 (dec (count raw))) "\r"]
                            [(inc k) raw ""]))
                        (range)
                        (str/split text #"\n" -1))
        n         (count lines)
        skip      (fn [pred j] (if (and (< j n) (pred (second (nth lines j)))) (recur pred (inc j)) j))]
    (loop [i 0 out [] changes [] tables []]
      (if (>= i n)
        {:text    (str/join "\n" (map (fn [[_ t cr]] (str t cr)) out))
         :changes changes
         :tables  tables}
        (let [[line text :as entry] (nth lines i)
              marker (re-matches marker-re text)]
          (cond
            marker
            (let [header-i (skip str/blank? (inc i))
                  table    (table-header (second marker) lines header-i)
                  body-i   (+ header-i 2)
                  body-end (skip table-line? body-i)]
              (when (= body-i body-end)
                (refuse (:line table) "the generated table has no rows" {}))
              (let [[out changes]
                    (reduce (fn [[out changes] j]
                              (let [[l t cr] (nth lines j)
                                    [t' row-changes] (rewrite-row table l t by-ns+var)]
                                [(conj out [l t' cr]) (into changes row-changes)]))
                            [(into out (subvec lines i body-i)) changes]
                            (range body-i body-end))]
                (recur body-end out changes
                       (conj tables {:namespace (:namespace table)
                                     :line      (:line table)
                                     :rows      (- body-end body-i)}))))

            ;; A comment line naming the token that is not a well-formed
            ;; marker. Prose that mentions the marker is not a comment line.
            (and (str/starts-with? (str/triml text) "<!--")
                 (str/includes? text marker-token))
            (refuse line (str "malformed generated-table marker " (pr-str (str/trim text))
                              " — the form is <!-- api-manifest:generated <namespace> -->")
                    {:raw text})

            :else
            (recur (inc i) (conj out entry) changes tables)))))))
