(ns re-frame.api-manifest.api-md-cells-test
  "Tests for spec/API.md's generated cells: the rewrite writes the four
  manifest-held cells of a marked table's var rows and nothing else, is a
  fixed point on its own output, keeps each line's ending, reports a wrong
  cell as a change, resolves each row in the namespace it names, leaves
  authored rows and authored Spec cells alone, and refuses a marked table it
  cannot classify."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [re-frame.api-manifest.api-md-cells :as rf.api-manifest.api-md-cells]))

(def ^:private rows
  [{:namespace "re-frame.adapter.uix" :var "use-sub" :kind :fn :tier :adapter :owner "006" :status "v1"}
   {:namespace "re-frame.adapter.uix" :var "adapter" :kind :var :tier :adapter :owner "006" :status "v1"}
   {:namespace "re-frame.core" :var "reg-event" :kind :macro :tier :front-porch :owner "001" :status "EP-0018"}
   {:namespace "re-frame.core" :var "dispatch" :kind :macro :tier :front-porch :owner "002" :status "v1 (preserved + extended)"}
   {:namespace "re-frame.core" :var "reg-machine" :kind :macro :tier :advanced :owner "005" :status "v1"}
   {:namespace "re-frame.core" :var "init!" :kind :fn :tier :front-porch :owner "002" :status "v1"}
   {:namespace "re-frame.machines" :var "reg-machine*" :kind :fn :tier :advanced :owner "005" :status "v1"}
   {:namespace "re-frame.machines" :var "machine-transition" :kind :fn :tier :advanced :owner "005" :status "v1"}])

(def ^:private marked-table
  ["Prose above."
   ""
   "<!-- api-manifest:generated re-frame.adapter.uix -->"
   ""
   "| API | M/Fn | Signature | Status | Tier | Spec | Notes |"
   "|---|---|---|---|---|---|---|"
   "| `rf.adapter.uix/use-sub` | (UIx hook) | `(use-sub q)` \\| `(use-sub q opts)` |  |  | 002, 006 | Also 002. |"
   "| `rf.adapter.uix/adapter` | Var (map) | the map | v1 | adapter | 006 |  |"
   ""
   "| API | M/Fn | Signature | Status | Tier | Spec |"
   "|---|---|---|---|---|---|"
   "| `reg-event` | M | authored | anything | front-porch | 001, 002 |"])

(defn- rewrite
  ([lines] (rewrite lines "\n"))
  ([lines eol] (rewrite lines eol {}))
  ([lines eol opts] (rf.api-manifest.api-md-cells/rewrite (str/join eol lines) rows opts)))

(defn- refusal
  "The message `rewrite` throws for `lines`, or nil when it rewrites them."
  ([lines] (refusal lines {}))
  ([lines opts]
   (try (rewrite lines "\n" opts) nil
        (catch clojure.lang.ExceptionInfo e (ex-message e)))))

(defn- table
  "A marked `re-frame.core` table holding `body` rows under the usual header."
  [& body]
  (into ["<!-- api-manifest:generated re-frame.core -->"
         ""
         "| API | M/Fn | Signature | Status | Tier | Spec |"
         "|---|---|---|---|---|---|"]
        body))

(defn- out-lines [lines opts]
  (str/split (:text (rewrite lines "\n" opts)) #"\n" -1))

(deftest rewrites-the-four-cells-and-nothing-else
  (let [{:keys [text changes tables]} (rewrite marked-table)
        out (str/split text #"\n" -1)]
    ;; The parenthetical, the signature with its escaped pipe, and Notes stay;
    ;; the second spec in the Spec cell is gone.
    (is (= "| `rf.adapter.uix/use-sub` | Fn (UIx hook) | `(use-sub q)` \\| `(use-sub q opts)` | v1 | adapter | 006 | Also 002. |"
           (nth out 6)))
    (is (= (assoc marked-table 6 (nth out 6)) out)
        "the in-sync row, the prose and the unmarked table are untouched")
    (is (= [[7 "M/Fn" "(UIx hook)" "Fn (UIx hook)"] [7 "Status" "" "v1"]
            [7 "Tier" "" "adapter"] [7 "Spec" "002, 006" "006"]]
           (map (juxt :line :column :from :to) changes)))
    (is (= [{:namespace "re-frame.adapter.uix" :line 5 :rows 2}] tables))))

(deftest the-rewrite-is-a-fixed-point
  (let [once (:text (rewrite marked-table))]
    (is (= {:text once :changes []}
           (select-keys (rf.api-manifest.api-md-cells/rewrite once rows) [:text :changes])))))

(deftest a-wrong-tier-or-status-in-a-generated-cell-is-a-change
  (let [in-sync (str/split (:text (rewrite marked-table)) #"\n" -1)
        planted (assoc in-sync 7 "| `rf.adapter.uix/adapter` | Var (map) | the map | v1 (preserved) | tooling | 006 |  |")]
    (is (= [[8 "Status" "v1 (preserved)" "v1"] [8 "Tier" "tooling" "adapter"]]
           (map (juxt :line :column :from :to) (:changes (rewrite planted)))))))

(deftest line-endings-are-kept
  (let [text (:text (rewrite marked-table "\r\n"))]
    (is (= (count marked-table) (count (re-seq #"\r\n" (str text "\r\n")))))
    (is (not (re-find #"[^\r]\n" text)))
    (is (str/includes? text "| v1 | adapter | 006 | Also 002. |\r\n"))))

(deftest a-marked-table-refuses-what-it-cannot-classify
  (let [fault (fn [i line] (refusal (assoc marked-table i line)))]
    (is (re-find #"^spec/API.md L3: malformed generated-table marker"
                 (str (fault 2 "<!-- api-manifest:generated -->"))))
    (is (re-find #"L5: the generated-table marker for re-frame.adapter.uix has no table under it"
                 (str (fault 4 "Prose where the table should be."))))
    (is (re-find #"L5: the generated table has no Tier column"
                 (str (fault 4 "| API | M/Fn | Signature | Status | Level | Spec | Notes |"))))
    (is (re-find #"L7: the row has 8 cells and its header 7"
                 (str (fault 6 "| `rf.adapter.uix/use-sub` | Fn | `(a|b)` | v1 | adapter | 006 |  |"))))
    (is (re-find #"L7: the first cell .* does not name one var"
                 (str (fault 6 "| `use-sub` / `use-frame` | Fn | sig | v1 | adapter | 006 |  |"))))
    (is (re-find #"L7: `rf.adapter.uix/use-subs` has no manifest row in re-frame.adapter.uix"
                 (str (fault 6 "| `rf.adapter.uix/use-subs` | Fn | sig | v1 | adapter | 006 |  |"))))
    (is (re-find #"L7: the M/Fn cell holds \"M/Fn \(CLJS\)\""
                 (str (fault 6 "| `rf.adapter.uix/use-sub` | M/Fn (CLJS) | sig | v1 | adapter | 006 |  |"))))
    (is (re-find #"L5: the generated table has no var row to generate"
                 (str (refusal (subvec marked-table 0 6)))))
    ;; Prose naming the marker is not a marker.
    (is (nil? (fault 0 "A table under an `<!-- api-manifest:generated <namespace> -->` comment.")))))

;; (1) Mixed-namespace tables: the marker names the namespace of the bare
;; rows, and a qualified row resolves its own qualifier.

(deftest each-row-resolves-in-the-namespace-it-names
  (let [out (out-lines (table "| `reg-machine` | M | sig |  |  |  |"
                              "| `re-frame.machines/reg-machine*` | Fn | sig |  |  |  |"
                              "| `rf.adapter.uix/adapter` | Var (map) | sig |  |  |  |")
                       {})]
    (is (= ["| `reg-machine` | M | sig | v1 | advanced | 005 |"
            "| `re-frame.machines/reg-machine*` | Fn | sig | v1 | advanced | 005 |"
            "| `rf.adapter.uix/adapter` | Var (map) | sig | v1 | adapter | 006 |"]
           (subvec out 4))
        "bare in the marker's namespace; a full namespace and an alias each in their own")))

(deftest a-row-resolving-to-the-wrong-namespace-is-refused
  (is (re-find #"L5: `machine-transition` has no manifest row in re-frame.core, the namespace the table's marker names"
               (str (refusal (table "| `machine-transition` | Fn | sig |  |  |  |"))))
      "a bare row never falls back to another namespace carrying the name")
  (is (re-find #"L5: `re-frame.machine/reg-machine\*` has no manifest row in re-frame.machine, the namespace its qualifier `re-frame.machine` names"
               (str (refusal (table "| `re-frame.machine/reg-machine*` | Fn | sig |  |  |  |"))))
      "a misspelt qualifier is not resolved by the bare var name"))

;; (2) Rows that stay authored: non-var rows and knowingly-unmanifested
;; (planned) vars.

(deftest non-var-and-planned-rows-stay-authored
  (let [body    ["| `reg-machine` | M | sig |  |  |  |"
                 "| — | — | `(rf/clear :machine id)` — a cross-reference | — | — | 005 |"
                 "| `:rf.machine/spawn` (fx) | — | args | v1 | — (fx-id) | 005 |"
                 "| `(rf/configure! {:epoch-history {:depth N}})` | — | see keys | v1 (dev-only) | — (configure key) | Tool-Pair |"
                 "| `machine->mermaid` | Fn | `(machine->mermaid d)` | post-v1 lib | tooling | 005 |"]
        lines   (apply table body)
        {:keys [text tables]} (rewrite lines "\n" {:known-unmanifested #{"machine->mermaid"}})]
    (is (= (assoc (vec lines) 4 "| `reg-machine` | M | sig | v1 | advanced | 005 |")
           (str/split text #"\n" -1)))
    (is (= [{:namespace "re-frame.core" :line 3 :rows 1}] tables)
        "only the var row counts as generated"))
  (is (re-find #"L6: `machine->mermaid` has no manifest row in re-frame.core"
               (str (refusal (table "| `reg-machine` | M | sig |  |  |  |"
                                    "| `machine->mermaid` | Fn | sig | post-v1 lib | tooling | 005 |")
                             {})))
      "an unmanifested var outside the allowlist is refused")
  (is (re-find #"L5: `re-frame.core/machine->mermaid` has no manifest row in re-frame.core"
               (str (refusal (table "| `re-frame.core/machine->mermaid` | Fn | sig | post-v1 lib | tooling | 005 |")
                             {:known-unmanifested #{"machine->mermaid"}})))
      "the allowlist names bare rows only, as api-md-check's does")
  (is (re-find #"L3: the generated table has no var row to generate"
               (str (refusal (table "| — | — | a cross-reference | — | — | 005 |"))))
      "a marker over a table with nothing to generate is refused"))

;; (3) The M/Fn column: headed `Kind` in a table that rows fx-ids, and a
;; macro that is also a fn on CLJS.

(deftest the-kind-column-and-a-dual-kind-parenthetical
  (is (= ["<!-- api-manifest:generated re-frame.core -->"
          ""
          "| API | Kind | Signature | Status | Tier | Spec |"
          "|---|---|---|---|---|---|"
          "| `:rf.http/managed` | fx | args | v1 (optional capability) | — (fx-id) | 014 |"
          "| `dispatch` | M (also Fn on CLJS) | sig | v1 (preserved + extended) | front-porch | 002 |"]
         (out-lines ["<!-- api-manifest:generated re-frame.core -->"
                     ""
                     "| API | Kind | Signature | Status | Tier | Spec |"
                     "|---|---|---|---|---|---|"
                     "| `:rf.http/managed` | fx | args | v1 (optional capability) | — (fx-id) | 014 |"
                     "| `dispatch` | Fn (also Fn on CLJS) | sig | v1 | front-porch | 002 |"]
                    {}))
      "`Kind` heads the M/Fn column, and the dual kind is an M marker plus an authored parenthetical"))

;; (5) A Spec cell whose owner spec/Ownership.md does not settle stays
;; authored; the row's other cells are still generated.

(deftest an-authored-spec-cell-is-left-alone
  (let [opts {:authored-spec #{["re-frame.core" "init!"]}}]
    (is (= ["| `init!` | Fn | sig | v1 | front-porch | 006 |"
            "| `reg-event` | M | sig | EP-0018 | front-porch | 001 |"]
           (subvec (out-lines (table "| `init!` | Fn | sig | v | t | 006 |"
                                     "| `reg-event` | M | sig | v | t | 006 |")
                              opts)
                   4)))
    (is (re-find #"^spec/API.md: :api-md-authored-spec names re-frame.core/init!, which no generated table rows"
                 (str (refusal (table "| `reg-event` | M | sig |  |  |  |") opts)))
        "an entry naming no generated row is stale and refused")))
