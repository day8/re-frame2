(ns re-frame.api-manifest.api-md-cells-test
  "Tests for spec/API.md's generated cells: the rewrite writes the four
  manifest-held cells of a marked table and nothing else, is a fixed point on
  its own output, keeps each line's ending, reports a wrong cell as a change,
  and refuses a marked table it cannot classify."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [re-frame.api-manifest.api-md-cells :as rf.api-manifest.api-md-cells]))

(def ^:private rows
  [{:namespace "re-frame.adapter.uix" :var "use-sub" :kind :fn :tier :adapter :owner "006" :status "v1"}
   {:namespace "re-frame.adapter.uix" :var "adapter" :kind :var :tier :adapter :owner "006" :status "v1"}
   {:namespace "re-frame.core" :var "reg-event" :kind :macro :tier :front-porch :owner "001" :status "EP-0018"}])

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
  ([lines eol] (rf.api-manifest.api-md-cells/rewrite (str/join eol lines) rows)))

(defn- refusal
  "The message `rewrite` throws for `lines`, or nil when it rewrites them."
  [lines]
  (try (rewrite lines) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

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
    (is (re-find #"L5: the generated table has no rows"
                 (str (refusal (subvec marked-table 0 6)))))
    ;; Prose naming the marker is not a marker.
    (is (nil? (fault 0 "A table under an `<!-- api-manifest:generated <namespace> -->` comment.")))))
