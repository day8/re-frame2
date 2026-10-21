(ns re-frame.api-manifest.api-md-cells
  "Generated cells in spec/API.md tables.

  A table opts in with a marker comment on its own line above it, naming the
  namespace its bare rows belong to:

      <!-- api-manifest:generated re-frame.core -->

  ROWS. A row whose first cell is one back-ticked symbol is a var row. A
  bare symbol (`reg-event`) names a var of the marker's namespace. A
  qualified one names a var of its qualifier's namespace: a documented alias
  (`rf.adapter.uix/use-sub`, `adapter-aliases`) or a namespace spelled in
  full (`re-frame.resources/resource-state`). So one table can row a facade
  macro beside the helpers its feature namespace owns.

  Every other row stays authored and is written back untouched: a keyword,
  list or vector first cell, a `—` cross-reference row, and a bare var the
  manifest knowingly does not carry (`:api-md-known-unmanifested`, the
  planned surfaces). This is `re-frame.api-manifest.api-md-check`'s var-row
  rule, so the two read a table alike.

  CELLS. Four cells of a var row restate facts the manifest already holds,
  so the generator writes them from that var's manifest row:

      M/Fn   — the kind marker (`Fn` / `M` / `Var`) from `:kind`. A
               parenthetical after it (`Fn (UIx hook)`) stays authored. A
               table that also rows fx-ids may head the column `Kind`.
      Status — `:status`.
      Tier   — `:tier`.
      Spec   — `:owner`. A second spec is a cross-reference and goes in
               Notes (spec/API.md §Conventions, the Spec column). A var
               named in `:api-md-authored-spec` keeps its authored Spec
               cell, because spec/Ownership.md does not settle its owner.

  Every other cell — the first, Signature, Notes — stays authored and is
  written back byte for byte. `rewrite` is pure text in, text out, and keeps
  each line's own ending, so a CRLF checkout regenerates identically to an LF
  one. `re-frame.api-manifest.gen` calls it from `generate!` (write) and
  `check!` (fail on any change).

  A marked table refuses what it cannot classify, naming the line: a
  malformed marker, a marker with no table under it, a table missing one of
  the four columns or holding no var row, a row whose cell count differs
  from its header's, a row whose M/Fn marker claims a var its first cell
  does not name, a var the manifest does not carry in the namespace it
  resolves to, and an M/Fn cell holding anything but a marker and a
  parenthetical. An `:api-md-authored-spec` entry naming no generated row
  is refused too. A skipped row would be authored text the generator had
  silently stopped checking."
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

(def ^:private header-aliases
  "Other header spellings of a generated column. `Kind` heads the M/Fn
   column of a table that also rows fx-ids (spec/API.md §HTTP requests)."
  {"Kind" "M/Fn"})

(def ^:private kind-markers {:fn "Fn" :macro "M" :var "Var"})

(def adapter-aliases
  "The documented `:require [<ns> :as <alias>]` adapter aliases spec/API.md
   qualifies var rows with, each resolved to the EXACT namespace named here.
   A qualifier that is not an alias is read as a namespace spelled in full.
   Never resolve by bare var name: the `adapter` / `client-root` / … vars
   exist in every adapter namespace, so a bare match would not tell them
   apart. spec/API.md teaches the Conventions require-alias dialect —
   `rf.adapter.<x>` for `re-frame.adapter.<x>`, per spec/Conventions.md
   §Require-alias dialect; the table is spelled out so a new adapter alias
   is an intentional one-line addition."
  {"rf.adapter.reagent" "re-frame.adapter.reagent"
   "rf.adapter.uix"     "re-frame.adapter.uix"
   ;; The non-dialect spellings. spec/ does not teach them (a row using one
   ;; is a defect, not an exemption), but they stay resolvable so a row
   ;; carrying one is graded rather than silently unresolved.
   "reagent-adapter"    "re-frame.adapter.reagent"
   "uix-adapter"        "re-frame.adapter.uix"})

(def ^:private var-ident-re
  "A first cell naming one var: a single back-ticked symbol, bare or
   qualified. Its first character rules out the other shapes a first cell
   takes: a keyword (`:`), a list (`(`), a vector (`[`), a map, a string or
   a tagged form."
  #"`([^\s`:(\[{\"#\d][^\s`]*)`")

(def ^:private kind-cell-re
  "An M/Fn cell the generator can rewrite: an optional marker, then an
   optional authored parenthetical."
  #"^(?:(?:Fn|M|Var)\b)?\s*(\(.*\))?$")

(defn- refuse [line message data]
  (throw (ex-info (format "spec/API.md L%d: %s" line message)
                  (assoc data :line line))))

(defn ident-namespace
  "`[namespace var-name qualifier]` for a first-cell identifier in a table
   whose bare rows belong to `default-ns`. A bare ident is a var of
   `default-ns` with a nil qualifier; a qualified one is a var of its
   qualifier's `adapter-aliases` namespace, else of the qualifier read as a
   namespace."
  [default-ns ident]
  (if-let [slash (str/last-index-of ident "/")]
    (let [qualifier (subs ident 0 slash)]
      [(get adapter-aliases qualifier qualifier) (subs ident (inc slash)) qualifier])
    [default-ns ident nil]))

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

(defn- var-row
  "The manifest row a body row names, or nil for a row that stays authored.
   Refuses a row it cannot classify (see ns docstring, ROWS)."
  [{:keys [namespace column-index]} line cells by-ns+var known-unmanifested]
  (let [[_ ident] (re-matches var-ident-re (str/trim (first cells)))
        marker    (re-find #"^(?:Fn|M|Var)\b" (str/trim (nth cells (column-index "M/Fn"))))]
    (cond
      ident
      (let [[ns var-name qualifier] (ident-namespace namespace ident)]
        (or (get by-ns+var [ns var-name])
            (when-not (and (nil? qualifier) (contains? known-unmanifested var-name))
              (refuse line (format "`%s` has no manifest row in %s, the namespace %s"
                                   ident ns (if qualifier
                                              (str "its qualifier `" qualifier "` names")
                                              "the table's marker names"))
                      {:raw ident :namespace ns}))))

      marker
      (refuse line (str "the first cell " (pr-str (str/trim (first cells)))
                        " does not name one var, yet the M/Fn cell marks the row as one")
              {:raw (first cells)}))))

(defn- rewrite-row
  "Rewrite one var row's generated cells. Returns `[text changes]`."
  [{:keys [column-index]} line text cells row authored-spec]
  (let [values  (generated-values line (nth cells (column-index "M/Fn")) row)
        ident   (str/trim (first cells))
        columns (cond->> generated-columns
                  (contains? authored-spec [(:namespace row) (:var row)]) (remove #{"Spec"}))
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
                columns)
        parts (split-cells text)]
    [(str/join "|" (concat [(first parts)] cells' [(peek parts)])) changes]))

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
          index  (into {} (keep-indexed (fn [k h] [(get header-aliases h h) k])) header)
          [sep-line sep-text] (nth lines (inc i) nil)]
      (when-let [missing (seq (remove index generated-columns))]
        (refuse line (str "the generated table has no " (str/join ", " missing) " column")
                {:header header}))
      (when-not (and sep-text (table-line? sep-text)
                     (separator-cells? (row-cells sep-line sep-text)))
        (refuse (or sep-line line) "the generated table's header has no separator row under it" {}))
      {:namespace namespace :header header :column-index index :line line})))

(defn- rewrite-table
  "Rewrite the body rows `[body-i body-end)` of a marked table. Returns
   `[out-lines changes var-rows]`, `var-rows` the `[namespace var]` of each
   generated row."
  [{:keys [header] :as table} lines body-i body-end by-ns+var {:keys [known-unmanifested authored-spec]}]
  (reduce (fn [[out changes var-rows] j]
            (let [[l t cr :as entry] (nth lines j)
                  cells (row-cells l t)]
              (when (not= (count cells) (count header))
                (refuse l (format "the row has %d cells and its header %d — an unescaped `|` inside a cell splits it"
                                  (count cells) (count header))
                        {:raw t}))
              (if-let [row (var-row table l cells by-ns+var known-unmanifested)]
                (let [[t' row-changes] (rewrite-row table l t cells row authored-spec)]
                  [(conj out [l t' cr]) (into changes row-changes)
                   (conj var-rows [(:namespace row) (:var row)])])
                [(conj out entry) changes var-rows])))
          [[] [] []]
          (range body-i body-end)))

(defn rewrite
  "Rewrite every marked table's generated cells in `text` (spec/API.md's
   contents) from `rows` (manifest rows). `opts` carries the sidecar's
   `:known-unmanifested` bare var names and `:authored-spec` `[namespace
   var]` pairs (see ns docstring). Returns

     {:text    the rewritten contents
      :changes [{:line :raw :column :from :to} ...] one per cell that moved
      :tables  [{:namespace :line :rows} ...]       one per marked table,
                                                     :rows its var rows}

   Running it on its own output changes nothing. Throws `ex-info` naming the
   line on anything in a marked table it cannot classify (see ns docstring)."
  ([text rows] (rewrite text rows {}))
  ([text rows {:keys [authored-spec] :as opts}]
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
     (loop [i 0 out [] changes [] tables [] generated #{}]
       (if (>= i n)
         (do (when-let [unused (seq (remove generated (sort authored-spec)))]
               (throw (ex-info (str "spec/API.md: :api-md-authored-spec names "
                                    (str/join ", " (map #(str/join "/" %) unused))
                                    ", which no generated table rows")
                               {:unused (vec unused)})))
             {:text    (str/join "\n" (map (fn [[_ t cr]] (str t cr)) out))
              :changes changes
              :tables  tables})
         (let [[line text :as entry] (nth lines i)
               marker (re-matches marker-re text)]
           (cond
             marker
             (let [header-i (skip str/blank? (inc i))
                   table    (table-header (second marker) lines header-i)
                   body-i   (+ header-i 2)
                   body-end (skip table-line? body-i)
                   [body table-changes var-rows] (rewrite-table table lines body-i body-end by-ns+var opts)]
               (when (empty? var-rows)
                 (refuse (:line table) "the generated table has no var row to generate" {}))
               (recur body-end
                      (into (into out (subvec lines i body-i)) body)
                      (into changes table-changes)
                      (conj tables {:namespace (:namespace table)
                                    :line      (:line table)
                                    :rows      (count var-rows)})
                      (into generated var-rows)))

             ;; A comment line naming the token that is not a well-formed
             ;; marker. Prose that mentions the marker is not a comment line.
             (and (str/starts-with? (str/triml text) "<!--")
                  (str/includes? text marker-token))
             (refuse line (str "malformed generated-table marker " (pr-str (str/trim text))
                               " — the form is <!-- api-manifest:generated <namespace> -->")
                     {:raw text})

             :else
             (recur (inc i) (conj out entry) changes tables generated))))))))
