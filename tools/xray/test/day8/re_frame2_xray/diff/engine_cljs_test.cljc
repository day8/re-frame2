(ns day8.re-frame2-xray.diff.engine-cljs-test
  "Tests for the Editscript-backed diff projection engine
  (`day8.re-frame2-xray.diff.engine`) against the R1–R8 diff grammar in
  `tools/xray/spec/021-Dynamic-Panel-Designs.md`."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-xray.diff.engine :as engine]))

(defn- ops-at
  "Each path of `expected`, mapped to the op `engine/op-at` reads there."
  [p expected]
  (into {} (map (fn [path] [path (engine/op-at p path)])) (keys expected)))

(defn- after-slots
  "Each index of the `n`-long after-side vector at `parent`, as its op, or as
  `[:was i]` for a survivor that moved from before-index `i`."
  [p parent n]
  (mapv (fn [i]
          (let [path (conj parent i)]
            (if-let [was (engine/shifted-was-index p path)]
              [:was was]
              (engine/op-at p path))))
        (range n)))

;; ---- R1 modified scalar -------------------------------------------------

(deftest r1-modified-scalar
  (let [p (engine/project {:counter 5} {:counter 6})]
    (is (= {:op :modified :before 5 :after 6}
           (engine/entry-at p [:counter])))
    (is (= :children (engine/op-at p [])))
    (is (= 1 (engine/change-count-at p [])))))

(deftest yucxn-nil-is-a-value-not-an-absent-slot
  (is (= {:op :modified :before nil :after 5}
         (engine/entry-at (engine/project {:n nil} {:n 5}) [:n])))
  (is (= {:op :modified :before 5 :after nil}
         (engine/entry-at (engine/project {:n 5} {:n nil}) [:n]))))

(deftest yucxn-number-to-string-is-not-type-change
  ;; R7 fires on a container-kind flip only; two scalars of different types are R1.
  (let [p (engine/project {:n 5} {:n "5"})]
    (is (= :modified (engine/op-at p [:n])))
    (is (not (engine/type-change? p [:n])))))

;; ---- R5 wholly-changed subtree -----------------------------------------

(deftest r5-wholly-new-subtree
  (doseq [[before after root leaf]
          [[{:a 1} {:a 1 :flash {:level :ok :text "hi"}} [:flash] [:flash :level]]
           [{}     {:tags #{:a :b}}                     [:tags]  [:tags :a]]]]
    (let [p (engine/project before after)]
      (is (= #{root} (:wholly-changed-roots p)))
      (is (= :added (engine/op-at p root)))
      (is (= root (engine/wholly-changed-ancestor p leaf))))))

;; ---- R6 vector shifts and removals --------------------------------------
;;
;; Editscript applies `:+` / `:-` against the EVOLVING sequence, so a `:-`
;; index is a position after every earlier edit at that parent. Removals and
;; `(was N)` suffixes both come from replaying the script in order; a `:-`
;; replay against the pristine indices names the wrong element, strikes a
;; survivor, or drops an index past the before length.

(deftest r6-vector-shifts-and-removals-replay-the-edit-script
  (doseq [[before after removed slots]
          [[[:a :b :c :d] [:a :NEW :b :c :d] []              [:same :added [:was 1] [:was 2] [:was 3]]]
           [[:a :b :c :d] [:a :c]            [[1 :b] [3 :d]] [:same [:was 2]]]
           ;; `[[0] :+ :X] [[2] :-]` removes :b, not :c
           [[:a :b :c]    [:X :a :c]         [[1 :b]]        [:added [:was 0] :same]]
           ;; `[[1] :+ :X] [[4] :-]`: index 4 is past the pristine length
           [[:a :b :c :d] [:a :X :b :c]      [[3 :d]]        [:same :added [:was 1] [:was 2]]]]]
    (let [p (engine/project before after)]
      (is (= (mapv (fn [[i v]] {:before-index i :before-value v}) removed)
             (vec (engine/vector-removals-at p [])))
          (pr-str before '-> after))
      (is (= slots (after-slots p [] (count after)))
          (pr-str before '-> after)))))

(deftest gwye-10-multi-element-sequential-emptied-reports-every-removal
  ;; An emptied collection's `:-` edits replay against the SHRINKING sequence,
  ;; so they must descend; ascending ones name the wrong elements.
  (doseq [coll [[:one :two] '(:one :two :three)]]
    (let [p (engine/project {:a coll} {:a (empty coll)})]
      (is (= (vec (map-indexed (fn [i v] {:before-index i :before-value v}) coll))
             (engine/vector-removals-at p [:a]))
          (pr-str coll)))))

;; ---- R7 type change -----------------------------------------------------

(deftest r7-a-container-kind-flip-is-a-modified-type-change
  (doseq [[label before after path]
          [["scalar → map" {:flash "hi"}        {:flash {:level :ok}} [:flash]]
           ["map → scalar" {:flash {:level :ok}} {:flash "hi"}         [:flash]]
           ["map → vector" {:items {:a 1}}       {:items [1 2 3]}      [:items]]
           ;; Pairs the member-level expansion must leave alone: nil is
           ;; `empty?`, and a vector and a list are both sequential.
           ["nil → map"     {:user nil}    {:user {:a 1}} [:user]]
           ["set → vector"  {:t #{:a :b}}  {:t [:a :b]}   [:t]]
           ["[] → map"      {:a []}        {:a {:k 1}}    [:a]]
           ["vector → list" {:v [1 2 3]}   {:v '(4 5 6)}  [:v]]]]
    (testing (str label " at the same path classifies as :modified")
      (let [p (engine/project before after)]
        (is (= :modified (engine/op-at p path)))
        (is (engine/type-change? p path))))))

;; ---- R8 sensitive redaction ---------------------------------------------

(deftest r8-was-redacted-now-visible
  (let [p (engine/project {:secret :rf/redacted} {:secret "real-value"})]
    (is (= :modified (engine/op-at p [:secret])))
    (is (= :before (engine/redaction-side p [:secret])))))

(deftest r8-was-visible-now-redacted
  (let [p (engine/project {:secret "real-value"} {:secret :rf/redacted})]
    (is (= :modified (engine/op-at p [:secret])))
    (is (= :after (engine/redaction-side p [:secret])))))

(deftest r8-two-sided-redacted-is-same
  (is (= :same (engine/op-at (engine/project {:secret :rf/redacted}
                                             {:secret :rf/redacted})
                             [:secret]))))

;; ---- R3 `[N∆]` chip -----------------------------------------------------
;;
;; The chip counts the changes under a collapsed container. A positional shift
;; is not one (the element carries its own `(was N)` suffix); a vector removal
;; is, though it rides `:vector-removals` rather than `:path-ops`, and it
;; counts at its parent and every ancestor.

(deftest y8doi25-chip-counts-the-change-not-the-positional-shift
  (doseq [[before after chips]
          [[[:a :b :c :d]    [:a :NEW :b :c :d] {[] 1}]
           [{:xs [:a :b :c]} {:xs [:a :b]}      {[:xs] 1 [] 1}]
           [{:a [1 2 3]}     {:a [1]}           {[:a] 2 [] 2}]]]
    (let [p (engine/project before after)]
      (doseq [[path n] chips]
        (is (= {:op :children :change-count n} (engine/entry-at p path))
            (pr-str before '-> after path))))))

;; ---- No-op epoch: no walk at all ----------------------------------------

(deftest y8doi25-no-op-epoch-reclassifies-without-walking-the-db
  ;; Two `=` but not `identical?` sides — the ordinary case once the egress
  ;; seam has rebuilt the value — give an empty edit script, and the R5 walk
  ;; must not then traverse the db. Editscript compares `:big` by identity and
  ;; never descends, so any realisation is that walk.
  (let [realised (atom 0)
        big      (map (fn [i] (swap! realised inc) i) (range 5000))
        p        (engine/project {:big big :n 1} {:big big :n 1})]
    (is (= [{} {} #{}] (map p [:path-ops :container-ops :wholly-changed-roots])))
    (is (zero? @realised))))

;; ---- Flat-rows shape ----------------------------------------------------

(deftest flat-rows-feeds-pure-diff-mode
  (let [p (engine/project {:counter 5
                           :user {:id 7 :name "Ada"}
                           :legacy-flag true}
                          {:counter 6
                           :user {:id 7 :name "Ada Lovelace"}
                           :flash {:level :ok}})]
    (is (= [{:path [:counter]      :op :modified :before 5     :after 6}
            {:path [:legacy-flag]  :op :removed  :before true  :after nil}
            {:path [:flash :level] :op :added    :before nil   :after :ok}
            {:path [:user :name]   :op :modified :before "Ada" :after "Ada Lovelace"}]
           (:flat-rows p)))))

;; `:flat-rows` sorts with `compare-path`, which orders paths of mixed segment
;; types; `l0us2-set-of-maps-recurses` throws if a sort site loses it.
(deftest n83r8-vector-append-under-a-keyword-parent-is-one-flat-row
  (let [p (engine/project {:flow {:phases [:a :b]}}
                          {:flow {:phases [:a :b :c]}})]
    (is (vector? (:flat-rows p)))
    (is (= [{:path [:flow :phases 2] :op :added :before nil :after :c}]
           (:flat-rows p)))))

;; ---- set diffs are member-level -----------------------------------------
;;
;; Editscript keys set members by value, so a swap puts each side's members at
;; disjoint paths. The R5 walk takes the union of both sides, so a swapped set
;; and its ancestors keep their key instead of reading as one wholly removed or
;; added container.

(deftest l0us2-door-machine-tags-repro
  (let [p (engine/project {:tags #{:door/locked}} {:tags #{:door/closed}})]
    (is (= :children (engine/op-at p [:tags])))
    (is (= {:op :removed :before :door/locked}
           (engine/entry-at p [:tags :door/locked])))
    (is (= {:op :added :after :door/closed}
           (engine/entry-at p [:tags :door/closed])))))

(deftest multimember-set-swap-is-member-level-not-whole-key
  ;; A multi-member swap reaches the engine as one whole-set `:r`.
  (let [p   (engine/project {:tags #{:a :b :c}} {:tags #{:a :d :e}})
        exp {[:tags] :children [:tags :a] :same}]
    (is (= exp (ops-at p exp)))
    (is (= {:op :removed :before :b} (engine/entry-at p [:tags :b])))
    (is (= {:op :added :after :d} (engine/entry-at p [:tags :d])))))

(deftest l0us2-set-of-non-keyword-members
  ;; Integer members must not be read as vector indices.
  (let [p   (engine/project {:t #{1 2}} {:t #{2 3}})
        exp {[:t] :children [:t 1] :removed [:t 3] :added [:t 2] :same}]
    (is (= exp (ops-at p exp)))))

(deftest l0us2-set-of-maps-recurses
  (let [p   (engine/project {:items #{{:id 1}}} {:items #{{:id 2}}})
        exp {[:items] :children
             [:items {:id 1} :id] :removed
             [:items {:id 2} :id] :added}]
    (is (= exp (ops-at p exp)))))

(deftest yucxn-deep-mixed-type-nesting-no-ancestor-promotion
  ;; map → map → vector → set: the swap stays member-level, no ancestor promotes.
  (let [p   (engine/project {:a {:b [#{:x}]}} {:a {:b [#{:y}]}})
        exp {[:a] :children [:a :b] :children [:a :b 0] :children
             [:a :b 0 :x] :removed [:a :b 0 :y] :added}]
    (is (= exp (ops-at p exp)))))

;; ---- the empty edge -----------------------------------------------------
;;
;; Editscript replaces a collection filled from, or emptied to, empty as one
;; whole value. The engine expands it member by member, and with nothing on the
;; other side to anchor against, the collection is a wholly-changed root — the
;; db root `[]` excepted, so each top-level key keeps its own chrome.

(deftest empty-edge-expands-member-level
  (doseq [[before after exp]
          [[{:tags #{}}             {:tags #{:a}}           {[:tags :a] :added [:tags] :added}]
           [{:tags #{:a}}           {:tags #{}}             {[:tags :a] :removed [:tags] :removed}]
           [{:a []}                 {:a [1]}                {[:a 0] :added [:a] :added}]
           [{:user {}}              {:user {:prefs {:c 3}}} {[:user :prefs :c] :added [:user] :added}]
           [{:user {:prefs {:a 1}}} {:user {}}              {[:user :prefs :a] :removed [:user] :removed}]]]
    (is (= exp (ops-at (engine/project before after) exp))
        (pr-str before '-> after))))

(deftest yucxn-key-removed-distinct-from-emptied-all-kinds
  ;; A removed key is a wholly-removed root; an emptied vector keeps its key,
  ;; its removal riding `:vector-removals`.
  (doseq [coll [#{:x} {:k 1} [1]]]
    (is (= :removed (engine/op-at (engine/project {:a coll} {}) [:a]))
        (pr-str coll)))
  (is (= {:op :children :change-count 1}
         (engine/entry-at (engine/project {:a [1]} {:a []}) [:a]))))

;; ---- before-side reads translate shifted vector indices -----------------
;;
;; Every vector index on an Editscript path is an AFTER index, including one
;; the path only descends through, so each before-side read maps every such
;; segment through its parent's replay.

(deftest x7nj-26-2-toggle-after-prepend-is-modified-not-added
  ;; `[[:todos 0] :+ …] [[:todos 2 :done?] :r true]`: the toggled todo was at
  ;; before-index 1; an untranslated read falls out of range and reads `:added`.
  (let [p (engine/project
            {:todos [{:id 1 :done? false} {:id 2 :done? false}]}
            {:todos [{:id 0 :done? false} {:id 1 :done? false} {:id 2 :done? true}]})]
    (is (= {:op :modified :before false :after true}
           (engine/entry-at p [:todos 2 :done?])))))

(deftest x7nj-26-2-nested-removal-after-prepend-carries-its-value
  ;; `[[0] :+ {:new 0}] [[2 :c] :-]`
  (let [p (engine/project [{:a 1} {:b 2 :c 3}] [{:new 0} {:a 1} {:b 2}])]
    (is (= {:op :removed :before 3} (engine/entry-at p [2 :c])))))

(deftest x7nj-26-2-set-swap-after-prepend-is-member-level
  ;; `[[0] :+ :new] [[1] :r #{:a :d :e}]`: the expansion compares against
  ;; before-index 0; reading index 1 finds no set and expands nothing.
  (let [p   (engine/project [#{:a :b :c}] [:new #{:a :d :e}])
        exp {[1 :b] :removed [1 :d] :added [1 :a] :same}]
    (is (= exp (ops-at p exp)))))

(deftest x7nj-26-2-swapped-set-after-prepend-is-not-promoted
  ;; `[[0] :+ :new] [[1] :r #{:d :e}]`: the R5 walk pairs after-element 1
  ;; with before-element 0, not with the equal index.
  (let [p (engine/project [#{:b :c}] [:new #{:d :e}])]
    (is (= :removed (engine/op-at p [1 :b])))
    (is (= #{} (:wholly-changed-roots p)))))

(deftest x7nj-26-2-nested-vector-removal-under-a-shift-is-reported
  ;; `[[:rows 0] :+ [0 0]] [[:rows 2 1] :-]`: the nested replay runs against
  ;; its own before counterpart, `[:rows 1]`.
  (let [p (engine/project {:rows [[1 2] [3 4 5]]} {:rows [[0 0] [1 2] [3 5]]})]
    (is (= [{:before-index 1 :before-value 4}]
           (engine/vector-removals-at p [:rows 2])))
    (is (= 2 (engine/shifted-was-index p [:rows 2 1])))))

;; ---- a populated collection's whole-value `:r` expands ------------------
;;
;; A* collapses a populated vector or map into one `:r` once enough of it
;; differs. Unexpanded, the container would read `:modified` with every member
;; `:same` and `[N∆]` reading 0.

(deftest x7nj-26-4-every-element-changed-is-per-index
  (let [p (engine/project {:scores [10 20 30]} {:scores [11 21 31]})]
    (is (= {:op :modified :before 10 :after 11} (engine/entry-at p [:scores 0])))
    (is (= {:op :children :change-count 3} (engine/entry-at p [:scores])))))

(deftest x7nj-26-4-map-with-every-key-replaced-is-per-key
  (let [p   (engine/project {:user {:prefs {:a 1 :b 2}}}
                            {:user {:prefs {:c 3 :d 4}}})
        exp {[:user :prefs :a] :removed [:user :prefs :c] :added}]
    (is (= exp (ops-at p exp)))
    (is (= {:op :children :change-count 4} (engine/entry-at p [:user :prefs])))
    (is (= #{} (:wholly-changed-roots p)))))

(deftest x7nj-26-4-length-change-under-a-whole-replace
  ;; The overlap is per-index; the tail is removed (through `:vector-removals`)
  ;; or added.
  (let [p (engine/project {:v [1 2 3 4]} {:v [9 8]})]
    (is (= {:op :modified :before 2 :after 8} (engine/entry-at p [:v 1])))
    (is (= [{:before-index 2 :before-value 3} {:before-index 3 :before-value 4}]
           (engine/vector-removals-at p [:v]))))
  (let [p   (engine/project {:v [1 2]} {:v [9 8 7 6]})
        exp {[:v 2] :added [:v 3] :added}]
    (is (= {:op :modified :before 1 :after 9} (engine/entry-at p [:v 0])))
    (is (= exp (ops-at p exp)))))

(deftest x7nj-26-4-a-changed-element-collection-expands-too
  ;; `[[[:v] :r [5 6 7 {:a 2}]]]`: the map element diffs key by key.
  (let [p (engine/project {:v [1 2 3 {:a 1}]} {:v [5 6 7 {:a 2}]})]
    (is (= {:op :modified :before 1 :after 2} (engine/entry-at p [:v 3 :a])))))
