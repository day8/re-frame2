(ns day8.re-frame2-xray.diff.engine-cljs-test
  "Tests for the Editscript-backed diff projection engine
  (`day8.re-frame2-xray.diff.engine`, rf2-n2jig).

  Each test pins one rule from §5.1 of
  `ai/findings/diff-mode-3-key-and-triangle-grammar-2026-05-27.md`
  (as revised in §7 by Mike's pair-debug answers). Pure data → data,
  `.cljc` so the JVM target picks them up too."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-xray.diff.engine :as engine]))

;; ---- R1 modified scalar -------------------------------------------------

(deftest r1-modified-scalar
  (testing "scalar bump produces :modified at the leaf path"
    (let [p (engine/project {:counter 5} {:counter 6})]
      (is (= :modified (engine/op-at p [:counter])))
      (is (= {:op :modified :before 5 :after 6}
             (engine/entry-at p [:counter])))
      ;; Root container should appear with `:children` op since one
      ;; descendant differs.
      (is (= :children (engine/op-at p [])))
      (is (= 1 (engine/change-count-at p []))))))

;; ---- R2 new map key -----------------------------------------------------

(deftest r2-new-map-key
  (testing "wholly new key surfaces as :added at the leaf path"
    (let [p (engine/project {:a 1} {:a 1 :b 2})]
      (is (= :added (engine/op-at p [:b])))
      (is (= 2 (:after (engine/entry-at p [:b]))))
      (is (= :same (engine/op-at p [:a]))))))

(deftest r2-removed-map-key
  (testing "removed key surfaces as :removed at the leaf path"
    (let [p (engine/project {:a 1 :b 2} {:a 1})]
      (is (= :removed (engine/op-at p [:b])))
      (is (= 2 (:before (engine/entry-at p [:b])))))))

;; ---- R5 wholly-new subtree ---------------------------------------------

(deftest r5-wholly-new-subtree
  (testing "subtree where every descendant is :added → parent root logged"
    (let [p (engine/project {:a 1}
                            {:a 1 :flash {:level :ok :text "hi"}})]
      (is (contains? (:wholly-changed-roots p) [:flash])
          "[:flash] should be the wholly-changed root")
      ;; The shallowest wholly-changed ancestor of every descendant of
      ;; [:flash] should be [:flash] itself.
      (is (= [:flash] (engine/wholly-changed-ancestor p [:flash :level])))
      (is (= [:flash] (engine/wholly-changed-ancestor p [:flash :text]))))))

(deftest r5-wholly-removed-subtree
  (testing "subtree where every descendant is :removed → parent root logged"
    (let [p (engine/project {:a 1 :flash {:level :ok :text "hi"}}
                            {:a 1})]
      (is (contains? (:wholly-changed-roots p) [:flash])))))

(deftest r5-mixed-subtree-not-wholly-changed
  (testing "subtree with both :added and :same leaves does NOT reclassify"
    ;; :user has :id (same) AND :name (modified) — not wholly-anything.
    (let [p (engine/project {:user {:id 7 :name "Ada"}}
                            {:user {:id 7 :name "Ada Lovelace"}})]
      (is (not (contains? (:wholly-changed-roots p) [:user]))))))

;; ---- R6 vector shift ----------------------------------------------------

(deftest r6-vector-insert-with-shift
  (testing "vector insert at index 1 — shifted indices get :same-shifted"
    (let [before [:a :b :c :d]
          after  [:a :NEW :b :c :d]
          p (engine/project before after)]
      ;; New element at after-index 1
      (is (= :added (engine/op-at p [1])))
      ;; Shifted elements at after-indices 2,3,4 carry :same-shifted
      ;; with `:was-index` 1,2,3 respectively.
      (is (= :same-shifted (engine/op-at p [2])))
      (is (= 1 (engine/shifted-was-index p [2])))
      (is (= :same-shifted (engine/op-at p [3])))
      (is (= 2 (engine/shifted-was-index p [3])))
      (is (= :same-shifted (engine/op-at p [4])))
      (is (= 3 (engine/shifted-was-index p [4]))))))

(deftest r6-vector-remove-with-shift
  (testing "vector remove — surviving after-indices carry :same-shifted, removed slot lives in :vector-removals"
    (let [before [:a :b :c :d]
          after  [:a :c :d]
          p (engine/project before after)]
      ;; After-index 1 = :c (was :b at before-idx 2). After-index 2 =
      ;; :d (was :c at before-idx 3). Both shifted up by 1.
      (is (= :same-shifted (engine/op-at p [1])))
      (is (= 2 (engine/shifted-was-index p [1])))
      (is (= :same-shifted (engine/op-at p [2])))
      (is (= 3 (engine/shifted-was-index p [2])))
      ;; The removed element :b lives on the :vector-removals channel
      ;; keyed by parent path, NOT on path-ops (the surviving after-
      ;; tree has no stable path identity for it).
      (let [removals (get-in p [:vector-removals []])]
        (is (= 1 (count removals)))
        (is (= {:before-index 1 :before-value :b} (first removals)))))))

;; ---- R7 type change -----------------------------------------------------

(deftest r7-scalar-to-container
  (testing "scalar → map at the same path classifies as :modified"
    (let [p (engine/project {:flash "hi"} {:flash {:level :ok}})]
      (is (= :modified (engine/op-at p [:flash])))
      (is (engine/type-change? p [:flash])))))

(deftest r7-container-to-scalar
  (testing "map → scalar at the same path classifies as :modified"
    (let [p (engine/project {:flash {:level :ok}} {:flash "hi"})]
      (is (= :modified (engine/op-at p [:flash])))
      (is (engine/type-change? p [:flash])))))

(deftest r7-map-to-vector
  (testing "map → vector at the same path classifies as :modified"
    (let [p (engine/project {:items {:a 1}} {:items [1 2 3]})]
      (is (= :modified (engine/op-at p [:items])))
      (is (engine/type-change? p [:items])))))

;; ---- R8 sensitive redaction ---------------------------------------------

(deftest r8-was-redacted-now-visible
  (testing "before sentinel + after real value → :modified with :before side"
    (let [p (engine/project {:secret :rf/redacted}
                            {:secret "real-value"})]
      (is (= :modified (engine/op-at p [:secret])))
      (is (= :before (engine/redaction-side p [:secret]))))))

(deftest r8-was-visible-now-redacted
  (testing "before real value + after sentinel → :modified with :after side"
    (let [p (engine/project {:secret "real-value"}
                            {:secret :rf/redacted})]
      (is (= :modified (engine/op-at p [:secret])))
      (is (= :after (engine/redaction-side p [:secret]))))))

(deftest r8-two-sided-redacted-is-same
  (testing "both sides redacted (v1 scope) → :same (no false-positive change)"
    (let [p (engine/project {:secret :rf/redacted}
                            {:secret :rf/redacted})]
      (is (= :same (engine/op-at p [:secret]))))))

;; ---- Container ops + change count chip (R3 input) ----------------------

(deftest r3-change-count-chip-input
  (testing "[N∆] chip count reflects total non-same descendants"
    (let [p (engine/project {:user {:id 7 :name "Ada" :role "engineer"}}
                            {:user {:id 7 :name "Ada Lovelace" :role "lead"}})]
      ;; Two leaves modified under :user → count 2.
      ;; Including the [:user] ancestor itself counted via the
      ;; container-ancestors walk: each non-same leaf increments every
      ;; ancestor. With 2 leaves the [:user] container reaches 2.
      (is (= 2 (engine/change-count-at p [:user]))))))

;; ---- Flat-rows shape ----------------------------------------------------

(deftest flat-rows-feeds-pure-diff-mode
  (testing "flat-rows projection feeds the :diff (pure list) mode"
    (let [p (engine/project {:counter 5
                             :user {:id 7 :name "Ada"}
                             :legacy-flag true}
                            {:counter 6
                             :user {:id 7 :name "Ada Lovelace"}
                             :flash {:level :ok}})
          rows (:flat-rows p)
          paths (set (map :path rows))
          op-at-path (fn [path]
                       (some (fn [r] (when (= path (:path r)) (:op r)))
                             rows))]
      ;; Four canonical changes: counter modified + user/name modified
      ;; + legacy-flag removed + flash added (wholly-new → per-leaf
      ;; rows expand to flash/level).
      (is (contains? paths [:counter]))
      (is (contains? paths [:user :name]))
      (is (contains? paths [:legacy-flag]))
      (is (contains? paths [:flash :level]))
      (is (= :modified (op-at-path [:counter])))
      (is (= :removed (op-at-path [:legacy-flag]))))))

;; ---- Empty diff edge-case ----------------------------------------------

(deftest empty-diff-when-before-equals-after
  (testing "identical before+after produces empty projection"
    (let [v {:counter 5 :user {:id 7}}
          p (engine/project v v)]
      (is (= {} (:path-ops p)))
      (is (= {} (:container-ops p)))
      (is (= [] (:flat-rows p)))
      (is (= #{} (:wholly-changed-roots p))))))

;; ---- Deep nested change-------------------------------------------------

(deftest deep-nested-change
  (testing "change at depth 5+ marks every ancestor as :children"
    (let [deep-path [:a :b :c :d :e]
          before    (assoc-in {} deep-path 1)
          after     (assoc-in {} deep-path 2)
          p         (engine/project before after)]
      (is (= :modified (engine/op-at p deep-path)))
      (is (= :children (engine/op-at p [:a])))
      (is (= :children (engine/op-at p [:a :b])))
      (is (= :children (engine/op-at p [:a :b :c])))
      (is (= :children (engine/op-at p [:a :b :c :d]))))))

;; ---- Sanity — Editscript imports cleanly under the build ---------------

(deftest editscript-import-sanity
  (testing "engine/project handles the §2 canonical example without throwing"
    (let [before {:counter 5
                  :user {:id 7 :name "Ada"}
                  :legacy-flag true}
          after  {:counter 6
                  :user {:id 7 :name "Ada Lovelace"}
                  :flash {:level :ok :text "Order placed"}}
          p (engine/project before after)]
      (is (map? p))
      (is (vector? (:flat-rows p)))
      (is (= :modified (engine/op-at p [:counter])))
      (is (= :modified (engine/op-at p [:user :name])))
      (is (= :removed  (engine/op-at p [:legacy-flag])))
      (is (contains? (:wholly-changed-roots p) [:flash])))))

;; ---- empty↔populated map expansion (rf2-5j7ch / rf2-9d4j8) -------------
;;
;; The engine pre-expands `{} ↔ {populated}` Editscript :r edits into
;; per-key :+ / :- edits BEFORE classification, so every downstream
;; lens (`:path-ops`, `:container-ops`, `:flat-rows`,
;; `:wholly-changed-roots`) sees per-key granularity. rf2-5j7ch first
;; surfaced the issue on the `:diff` (pure-list) lens; rf2-9d4j8
;; surfaced the same root cause on the FULL+DIFF lens, where `op-at`
;; was returning `:same` for every per-key path because the engine's
;; `path-ops` only held a single root-level `:modified` op. The fix
;; moved the expansion inside `project` itself (pre-alpha clean swap;
;; the old `expand-empty-root-replacement` flat-rows post-processor is
;; gone).

(deftest empty-to-populated-root-expands-to-per-key-added
  (testing "rf2-9d4j8 — `{} → {:counter 1 :user {:id 7}}` produces
            per-key `:added` ops at every lens, not a single root
            `:modified` op."
    (let [p (engine/project {} {:counter 1 :user {:id 7}})]
      ;; path-ops carry per-key :added entries (the FULL+DIFF lens
      ;; reads from here).
      (is (= :added (engine/op-at p [:counter])))
      (is (= :added (engine/op-at p [:user :id])))
      (is (= 1 (:after (engine/entry-at p [:counter]))))
      ;; The wholly-new container [:user] reclassifies as wholly-
      ;; changed-added (R5).
      (is (= :added (engine/op-at p [:user])))
      (is (contains? (:wholly-changed-roots p) [:user]))
      ;; The root `[]` does NOT enter wholly-changed-roots — cold-
      ;; boot epochs want per-top-level-key chrome, not a single
      ;; root-level reclassification.
      (is (not (contains? (:wholly-changed-roots p) [])))
      ;; Root `[]` carries `:children` (descendants changed) since
      ;; the engine no longer emits a root `:modified` for this case.
      (is (= :children (engine/op-at p [])))
      ;; flat-rows carry per-key :added rows (the :diff lens reads
      ;; from here; rf2-5j7ch's user-visible expectation).
      (let [rows  (:flat-rows p)
            paths (set (map :path rows))
            ops   (set (map :op rows))]
        (is (contains? paths [:counter]))
        (is (contains? paths [:user :id]))
        (is (= #{:added} ops))))))

(deftest populated-to-empty-root-expands-to-per-key-removed
  (testing "rf2-9d4j8 — symmetric removal: `{:counter 1 :user {:id 7}}
            → {}` produces per-key `:removed` ops at every lens."
    (let [p (engine/project {:counter 1 :user {:id 7}} {})]
      (is (= :removed (engine/op-at p [:counter])))
      (is (= :removed (engine/op-at p [:user :id])))
      (is (= 1 (:before (engine/entry-at p [:counter]))))
      (is (= :removed (engine/op-at p [:user])))
      (is (contains? (:wholly-changed-roots p) [:user]))
      (is (not (contains? (:wholly-changed-roots p) [])))
      (let [rows  (:flat-rows p)
            paths (set (map :path rows))
            ops   (set (map :op rows))]
        (is (contains? paths [:counter]))
        (is (contains? paths [:user :id]))
        (is (= #{:removed} ops))))))

(deftest empty-to-populated-mid-tree-expands
  (testing "rf2-9d4j8 — mid-tree empty-to-populated also expands:
            `{:user {}} → {:user {:name 'Ada'}}` yields :added at
            `[:user :name]`, not :modified at `[:user]`."
    (let [p (engine/project {:user {}} {:user {:name "Ada"}})]
      (is (= :added (engine/op-at p [:user :name])))
      ;; [:user] is wholly-changed-added (every descendant is :added).
      (is (= :added (engine/op-at p [:user])))
      (is (contains? (:wholly-changed-roots p) [:user])))))

(deftest empty-to-populated-flat-scalar-keys
  (testing "rf2-9d4j8 — flat scalar top-level keys (no nested
            containers) still yield per-key :added rows."
    (let [p (engine/project {} {:a 1 :b 2})]
      (is (= :added (engine/op-at p [:a])))
      (is (= :added (engine/op-at p [:b])))
      (is (= 1 (:after (engine/entry-at p [:a]))))
      (is (= 2 (:after (engine/entry-at p [:b]))))
      ;; No nested container to mark wholly-changed; the per-key ops
      ;; carry the chrome directly via path-ops.
      (is (empty? (:wholly-changed-roots p)))
      (is (= :children (engine/op-at p []))))))

(deftest non-empty-populated-disjoint-passes-through
  (testing "rf2-9d4j8 — two populated maps swapping wholesale (e.g.
            `{:a 1 :b 2} → {:c 3 :d 4}`) is NOT an empty-side
            replacement and should NOT expand to spurious per-key
            ops. Editscript emits a single root `:r` for this case
            (A* chooses root replace over 4 per-key edits); the
            engine leaves it classified as `:modified` at `[]`."
    (let [p (engine/project {:a 1 :b 2} {:c 3 :d 4})]
      ;; The wholesale root replacement classifies as :modified at []
      ;; (one map swapped for another with disjoint keys). Per-key
      ;; paths return :same — the operator sees one wholesale row.
      (is (= :modified (engine/op-at p [])))
      (is (= :same (engine/op-at p [:a])))
      (is (= :same (engine/op-at p [:c]))))))

(deftest type-change-still-modified
  (testing "rf2-9d4j8 — `{} → {}` style detection must NOT catch
            type changes (nil↔map, scalar↔map). R7's :modified +
            :type-change? branch still fires."
    ;; nil → map at a nested path (the most likely confusion):
    (let [p (engine/project {:user nil} {:user {:a 1}})]
      (is (= :modified (engine/op-at p [:user])))
      (is (engine/type-change? p [:user])))))

;; ---- rf2-n83r8 — :flat-rows :path sort is mixed-type safe ---------------
;;
;; Clojure's default vector comparator compares vectors element-wise via
;; each element's natural `compare`. Two `:path` vectors that share a
;; prefix and diverge into segments of different types at the same
;; index (e.g. `[:flow :phases 2]` vs `[:flow :phases :foo]`) would
;; crash `(sort-by :path …)` with `ClassCastException`. The engine
;; sorts at TWO sites:
;;
;;   1. `flat-rows-from-path-ops` final `(sort-by :path …)` (per-row
;;      assembly inside `project`)
;;   2. `project`'s final `(vec (sort-by :path …))` over the combined
;;      path-ops rows + vector-removals rows
;;
;; Both must use the mixed-type-safe `compare-path` comparator (length
;; first, then per-segment `pr-str`). These tests guard the contract:
;; (a) the bead's suggested fixture diffs cleanly, (b) directly sorting
;; a mixed-type set of flat-row maps via `engine/project`'s output
;; never throws, even when constructed shapes carry kw+int siblings.

(deftest n83r8-mixed-keyword-integer-path-segments-do-not-throw
  (testing "rf2-n83r8 — bead's suggested fixture: vector-append under
            a keyword-keyed parent produces flat-row paths with mixed
            keyword+integer segments inside ONE path. The sort over a
            single-row collection cannot CCE; this pins the engine's
            shape so a future regression that broadens this fixture
            keeps holding."
    (let [p (engine/project {:flow {:phases [:a :b]}}
                            {:flow {:phases [:a :b :c]}})]
      (is (vector? (:flat-rows p)))
      (is (= [{:path [:flow :phases 2] :op :added :before nil :after :c}]
             (:flat-rows p))))))

(deftest n83r8-many-mixed-type-rows-sort-cleanly
  (testing "rf2-n83r8 — a diff producing many flat-rows of mixed
            keyword + integer path shapes sorts without throwing. The
            engine currently short-circuits type-change reclassification
            (R7) before per-leaf rows can mix at a shared prefix, but
            sibling-rows under keyword-keyed branches that include
            vector indices in their tails are common — confirm sort
            holds."
    (let [before {:state {:list [1 2 3]
                          :meta {:title "old"}
                          :counter 5}
                  :other {:flag true}}
          after  {:state {:list [1 2 3 4]
                          :meta {:title "new"}
                          :counter 5}
                  :other {:flag true
                          :new-key 1}}
          p (engine/project before after)
          paths (mapv :path (:flat-rows p))]
      (is (vector? (:flat-rows p)))
      ;; Concrete paths Editscript should produce here: the new vector
      ;; entry at [:state :list 3], the title modification at
      ;; [:state :meta :title], and the new key at [:other :new-key].
      (is (some #{[:state :list 3]} paths))
      (is (some #{[:state :meta :title]} paths))
      (is (some #{[:other :new-key]} paths)))))

(deftest n83r8-direct-comparator-tolerates-shared-prefix-mixed-types
  (testing "rf2-n83r8 — the comparator itself is total over mixed-type
            siblings: `[:flow :phases 2]` vs `[:flow :phases :foo]`
            compares without CCE. This guards the defensive
            hardening even if a future engine evolution surfaces such
            sibling rows."
    ;; We exercise the comparator via `sort-by :path` over a manually-
    ;; constructed flat-rows-shaped collection. The comparator is
    ;; private — sorting via the same call shape the engine uses is the
    ;; cleanest behavioural test.
    (let [rows [{:path [:flow :phases 2]    :op :added :after :c}
                {:path [:flow :phases :foo] :op :added :after 1}
                {:path [:flow :phases]      :op :modified}]
          ;; The engine's two sort sites call `(sort-by :path compare-path ...)`;
          ;; round-trip a fixture that would CCE under the default
          ;; comparator through `project` to confirm the engine's
          ;; public surface is robust. Construct it directly via the
          ;; same call shape.
          ]
      ;; Direct invocation via `engine/project` won't currently surface
      ;; this exact row mix (R7 short-circuits), so we assert against
      ;; the documented contract: sorting flat-rows by their `:path`
      ;; using the engine's sort cannot CCE on any well-formed row
      ;; collection. The simplest behavioural pin is: confirm that
      ;; `engine/project` returns sorted `:flat-rows` for a fixture
      ;; that mixes keyword-keyed and integer-indexed paths in the
      ;; SAME tree (sibling branches), and that the order is stable.
      (let [p (engine/project {:list [10 20] :map {:a 1}}
                              {:list [10 20 30] :map {:a 1 :b 2}})
            paths (mapv :path (:flat-rows p))]
        ;; The fixture produces flat-rows at [:list 2] (int) and
        ;; [:map :b] (kw). Resolve at pos-0 (different keywords) →
        ;; safe under either comparator. The key guarantee is the
        ;; sort completed, the output is a vector, and the result is
        ;; in length-then-pr-str order under `compare-path`.
        (is (vector? (:flat-rows p)))
        (is (some #{[:list 2]} paths))
        (is (some #{[:map :b]} paths))
        ;; Lexicographic-by-pr-str: ":list" < ":map" → [:list 2] sorts
        ;; before [:map :b] (same length 2).
        (is (= [[:list 2] [:map :b]] paths)))
      ;; And explicitly: feed the manual mixed-type rows through the
      ;; SAME sort-by + comparator the engine uses. Define a local
      ;; reflection-free path comparator inline (kept literal so a
      ;; reader sees the contract without chasing the private fn):
      (let [compare-path
            (fn [a b]
              (let [la (count a) lb (count b)]
                (if (not= la lb)
                  (compare la lb)
                  (loop [i 0]
                    (if (>= i la) 0
                        (let [c (compare (pr-str (nth a i))
                                         (pr-str (nth b i)))]
                          (if (zero? c) (recur (inc i)) c)))))))
            sorted (sort-by :path compare-path rows)]
        (is (= [[:flow :phases]
                [:flow :phases 2]
                [:flow :phases :foo]]
               (mapv :path sorted)))))))

;; ---- rf2-l0us2 — member-level set diffs --------------------------------
;;
;; A changed SET must project as a member-level diff (KEY INTACT, per-
;; member `:removed` / `:added`), NOT as a whole-key removal. The bug:
;; Editscript keys set members by VALUE, so a member-swap puts each
;; side's members at DISJOINT paths. The before-side wholly-changed
;; uniformity walk then saw the set (and every ancestor) as 'all members
;; removed' and the after-side walk as 'all members added' — BOTH falsely
;; promoting the container to wholly-changed `:removed` / `:added`, which
;; the renderer paints as a struck-through whole key (the bead's 'sea of
;; red'). The engine's `path-ops` were already member-level; the fix is
;; the uniformity walk (`mark-wholly-changed` now collects the UNION of
;; both sides' set members so a swap fails uniformity at the set AND every
;; ancestor) plus per-member expansion of the empty↔populated `:r` set
;; replace (`expand-collection-replacement` / `expand-set-replacement`;
;; rf2-4vp8c later generalised the set branch to multi-member swaps too).

(deftest l0us2-set-member-swap-is-member-level-not-whole-key
  (testing "rf2-l0us2 — RED repro: `#{:a} → #{:b}` member swap projects
            as member-level `-:a +:b`, NOT a wholly-changed whole-set.
            (Before the fix the set container was promoted to wholly-
            changed-removed AND -added — the 'sea of red'.)"
    (let [p (engine/project #{:a} #{:b})]
      ;; Member-level ops at the value-keyed member paths.
      (is (= :removed (engine/op-at p [:a])))
      (is (= :added   (engine/op-at p [:b])))
      ;; The set is NOT wholly-changed — root `[]` is the set itself,
      ;; excluded from promotion regardless, but the key invariant is
      ;; that no wholly-changed root is logged for a member swap.
      (is (= #{} (:wholly-changed-roots p))))))

(deftest l0us2-nested-set-swap-keeps-key-intact
  (testing "rf2-l0us2 — the live repro path: a set nested under a map key
            (`{:tags #{:a}} → {:tags #{:b}}`) keeps `:tags` INTACT
            (`:children`, NOT `:removed`) and diffs at the member level."
    (let [p (engine/project {:tags #{:a}} {:tags #{:b}})]
      ;; The :tags KEY is intact — :children, never :removed/:added.
      (is (= :children (engine/op-at p [:tags])))
      (is (not (contains? (:wholly-changed-roots p) [:tags])))
      ;; Member-level diff under the intact key.
      (is (= :removed (engine/op-at p [:tags :a])))
      (is (= :added   (engine/op-at p [:tags :b]))))))

(deftest l0us2-door-machine-tags-repro
  (testing "rf2-l0us2 — the exact bead repro: the door machine's `:tags`
            went `#{:door/locked}` → `#{:door/closed}`. The :tags key
            must read as `-:door/locked +:door/closed`, not 'tags went
            away'."
    (let [p (engine/project {:tags #{:door/locked}}
                            {:tags #{:door/closed}})]
      (is (= :children (engine/op-at p [:tags])))
      (is (= :removed (engine/op-at p [:tags :door/locked])))
      (is (= :added   (engine/op-at p [:tags :door/closed])))
      (is (= :door/locked (:before (engine/entry-at p [:tags :door/locked]))))
      (is (= :door/closed (:after  (engine/entry-at p [:tags :door/closed])))))))

(deftest l0us2-set-add-only
  (testing "rf2-l0us2 — add-only `#{:a} → #{:a :b}`: only `:b` is added,
            `:a` is :same, key intact."
    (let [p (engine/project {:tags #{:a}} {:tags #{:a :b}})]
      (is (= :children (engine/op-at p [:tags])))
      (is (= :added (engine/op-at p [:tags :b])))
      ;; The surviving member carries no op (returns :same).
      (is (= :same (engine/op-at p [:tags :a])))
      (is (not (contains? (:wholly-changed-roots p) [:tags]))))))

(deftest l0us2-set-remove-only
  (testing "rf2-l0us2 — remove-only `#{:a :b} → #{:a}`: only `:b` is
            removed, `:a` is :same, key intact."
    (let [p (engine/project {:tags #{:a :b}} {:tags #{:a}})]
      (is (= :children (engine/op-at p [:tags])))
      (is (= :removed (engine/op-at p [:tags :b])))
      (is (= :same (engine/op-at p [:tags :a])))
      (is (not (contains? (:wholly-changed-roots p) [:tags]))))))

(deftest l0us2-set-partial-swap-keeps-shared-member
  (testing "rf2-l0us2 — partial swap `#{:a :b} → #{:a :c}`: `:a` survives
            (:same), `:b` removed, `:c` added — NOT wholly-changed."
    (let [p (engine/project {:tags #{:a :b}} {:tags #{:a :c}})]
      (is (= :children (engine/op-at p [:tags])))
      (is (= :removed (engine/op-at p [:tags :b])))
      (is (= :added (engine/op-at p [:tags :c])))
      (is (= :same (engine/op-at p [:tags :a])))
      (is (not (contains? (:wholly-changed-roots p) [:tags]))))))

(deftest l0us2-empty-to-nonempty-set-expands-member-level
  (testing "rf2-l0us2 — `{:tags #{}} → {:tags #{:a}}` (the empty↔populated
            edge Editscript emits as a whole-value `:r`): expands to a
            per-member `:added`, with the set legitimately wholly-changed
            (no surviving member to anchor against). Mirrors the empty-map
            expansion (rf2-9d4j8)."
    (let [p (engine/project {:tags #{}} {:tags #{:a}})]
      (is (= :added (engine/op-at p [:tags :a])))
      ;; Genuine cold-boot of the set → wholly-changed-added, key intact.
      (is (= :added (engine/op-at p [:tags])))
      (is (contains? (:wholly-changed-roots p) [:tags])))))

(deftest l0us2-nonempty-to-empty-set-expands-member-level
  (testing "rf2-l0us2 — symmetric clear `{:tags #{:a}} → {:tags #{}}`
            expands to a per-member `:removed`, wholly-changed-removed."
    (let [p (engine/project {:tags #{:a}} {:tags #{}})]
      (is (= :removed (engine/op-at p [:tags :a])))
      (is (= :removed (engine/op-at p [:tags])))
      (is (contains? (:wholly-changed-roots p) [:tags])))))

(deftest l0us2-wholly-new-set-promotes
  (testing "rf2-l0us2 — a set that appears wholesale under a NEW key
            (`{} → {:tags #{:a :b}}`) is still wholly-changed-added
            (opposite side absent → no surviving member)."
    (let [p (engine/project {} {:tags #{:a :b}})]
      (is (= :added (engine/op-at p [:tags])))
      (is (contains? (:wholly-changed-roots p) [:tags]))
      (is (= :added (engine/op-at p [:tags :a])))
      (is (= :added (engine/op-at p [:tags :b]))))))

(deftest l0us2-set-swap-does-not-promote-ancestor-map
  (testing "rf2-l0us2 — a swapped set must not falsely promote an ANCESTOR
            map. `{:m {:tags #{:a}}} → {:m {:tags #{:b}}}` keeps BOTH `:m`
            and `:m :tags` intact (the deeper trap a set-only gate missed:
            the ancestor map's only changed descendant is the swapped
            set)."
    (let [p (engine/project {:m {:tags #{:a}}} {:m {:tags #{:b}}})]
      (is (= :children (engine/op-at p [:m])))
      (is (= :children (engine/op-at p [:m :tags])))
      (is (= #{} (:wholly-changed-roots p)))
      (is (= :removed (engine/op-at p [:m :tags :a])))
      (is (= :added (engine/op-at p [:m :tags :b]))))))

(deftest l0us2-set-swap-does-not-promote-ancestor-vector
  (testing "rf2-l0us2 — a swapped set inside a VECTOR must not promote the
            vector. `{:v [#{:a}]} → {:v [#{:b}]}` keeps `:v` and `:v 0`
            intact (the index-aligned opposite-side walk for vectors)."
    (let [p (engine/project {:v [#{:a}]} {:v [#{:b}]})]
      (is (= :children (engine/op-at p [:v])))
      (is (= :children (engine/op-at p [:v 0])))
      (is (= #{} (:wholly-changed-roots p)))
      (is (= :removed (engine/op-at p [:v 0 :a])))
      (is (= :added (engine/op-at p [:v 0 :b]))))))

(deftest l0us2-set-of-non-keyword-members
  (testing "rf2-l0us2 — members match BY VALUE regardless of type. A set
            of strings + a set of numbers diff member-level just like
            keywords."
    (let [ps (engine/project {:t #{"a"}} {:t #{"b"}})]
      (is (= :children (engine/op-at ps [:t])))
      (is (= :removed (engine/op-at ps [:t "a"])))
      (is (= :added (engine/op-at ps [:t "b"]))))
    (let [pn (engine/project {:t #{1 2}} {:t #{2 3}})]
      (is (= :children (engine/op-at pn [:t])))
      (is (= :removed (engine/op-at pn [:t 1])))
      (is (= :added (engine/op-at pn [:t 3])))
      (is (= :same (engine/op-at pn [:t 2]))))))

(deftest l0us2-set-of-maps-recurses
  (testing "rf2-l0us2 — a set of MAPS (members value-keyed by their whole
            map) recurses into per-member structure sensibly: a swapped
            element-map surfaces as a removed old-map + added new-map,
            key intact."
    (let [p (engine/project {:items #{{:id 1}}} {:items #{{:id 2}}})]
      ;; The :items key stays intact.
      (is (= :children (engine/op-at p [:items])))
      (is (not (contains? (:wholly-changed-roots p) [:items])))
      ;; Each element-map is keyed by its whole value; the removed map
      ;; and added map appear at distinct member paths.
      (is (= :removed (engine/op-at p [:items {:id 1} :id])))
      (is (= :added (engine/op-at p [:items {:id 2} :id]))))))

(deftest l0us2-set-diff-feeds-flat-rows
  (testing "rf2-l0us2 — the member-level set diff also surfaces on the
            `:diff` (pure-list) flat-rows lens, not just FULL+DIFF."
    (let [p (engine/project {:tags #{:door/locked}}
                            {:tags #{:door/closed}})
          rows (:flat-rows p)
          paths (set (map :path rows))
          op-at-path (fn [path]
                       (some (fn [r] (when (= path (:path r)) (:op r))) rows))]
      (is (contains? paths [:tags :door/locked]))
      (is (contains? paths [:tags :door/closed]))
      (is (= :removed (op-at-path [:tags :door/locked])))
      (is (= :added (op-at-path [:tags :door/closed])))
      ;; The :tags key itself is NOT a flat-row (it's intact :children).
      (is (not (contains? paths [:tags]))))))

(deftest l0us2-map-and-vector-diffs-unchanged
  (testing "rf2-l0us2 — regression guard: the set-aware union walk must
            leave MAP + VECTOR wholly-changed promotion untouched."
    ;; Wholly-new map subtree still promotes (R5).
    (let [p (engine/project {:a 1} {:a 1 :flash {:level :ok :text "hi"}})]
      (is (contains? (:wholly-changed-roots p) [:flash]))
      (is (= :added (engine/op-at p [:flash]))))
    ;; Wholly-removed map subtree still promotes.
    (let [p (engine/project {:user {:id 7}} {})]
      (is (contains? (:wholly-changed-roots p) [:user]))
      (is (= :removed (engine/op-at p [:user]))))
    ;; Mixed map subtree still does NOT promote.
    (let [p (engine/project {:user {:id 7 :name "Ada"}}
                            {:user {:id 7 :name "Ada Lovelace"}})]
      (is (not (contains? (:wholly-changed-roots p) [:user])))
      (is (= :modified (engine/op-at p [:user :name]))))
    ;; Vector insert shift (R6) still works.
    (let [p (engine/project [:a :b :c :d] [:a :NEW :b :c :d])]
      (is (= :added (engine/op-at p [1])))
      (is (= :same-shifted (engine/op-at p [2]))))))

;; ---- rf2-4vp8c — MULTI-MEMBER simultaneous set swaps -------------------
;;
;; Residual of rf2-l0us2. l0us2 made SINGLE-member swaps member-level: for
;; `#{:a} → #{:b}` (and the 1-in/1-out partial swap `#{:a :b} → #{:a :c}`)
;; Editscript's A* emits per-member `:-` / `:+` edits, so the engine's
;; member-keyed path-ops + the union-walk in `mark-wholly-changed` already
;; render `-:a +:b` with the key intact.
;;
;; But when MULTIPLE members change at once (e.g. a machine `:tags` set
;; dropping two tags + adding two on a state transition) Editscript's A*
;; crosses its cost threshold and emits a WHOLE-SET `:r` (replace) at the
;; set's path instead of per-member edits:
;;
;;   `#{:a :b :c} → #{:a :d :e}` ⇒ `[[[] :r #{:a :d :e}]]`
;;
;; Left alone `project`'s `:r` branch classifies that as a single
;; `:modified` at the set's path and every per-member path returns `:same`
;; — the renderer paints a whole-set removal/add ('sea of red'), exactly
;; the regression l0us2 fixed for the single-member case.
;;
;; The fix extends `expand-collection-replacement` (via
;; `expand-set-replacement`) to the populated↔populated set case: a `:r`
;; at a SET-valued path where BOTH
;; sides are sets synthesizes the membership delta (members only-in-before
;; ⇒ `:-`, only-in-after ⇒ `:+`, in-both ⇒ no edit) regardless of how many
;; members changed. Members match BY VALUE (sets unordered). The engine's
;; output shape is unchanged — the renderer needs no edit.

(deftest multimember-set-swap-is-member-level-not-whole-key
  (testing "rf2-4vp8c — RED repro: `#{:a :b :c} → #{:a :d :e}` (drop :b,:c
            add :d,:e, keep :a) projects KEY-INTACT member-level
            `-:b -:c +:d +:e` with `:a` unchanged — NOT a whole-set
            replace. (Before the fix Editscript's whole-set `:r` made the
            set read as a single `:modified` with every member `:same`.)"
    (let [p (engine/project {:tags #{:a :b :c}} {:tags #{:a :d :e}})]
      ;; The :tags KEY is intact — :children, never :modified/:removed.
      (is (= :children (engine/op-at p [:tags])))
      (is (not (contains? (:wholly-changed-roots p) [:tags])))
      ;; Dropped members are :removed.
      (is (= :removed (engine/op-at p [:tags :b])))
      (is (= :removed (engine/op-at p [:tags :c])))
      ;; Added members are :added.
      (is (= :added (engine/op-at p [:tags :d])))
      (is (= :added (engine/op-at p [:tags :e])))
      ;; The surviving member carries no op (returns :same).
      (is (= :same (engine/op-at p [:tags :a])))
      ;; The dropped/added members carry their values for the renderer's
      ;; per-member suffix.
      (is (= :b (:before (engine/entry-at p [:tags :b]))))
      (is (= :d (:after (engine/entry-at p [:tags :d])))))))

(deftest multimember-set-swap-no-overlap
  (testing "rf2-4vp8c — a set whose members are ALL replaced (no overlap):
            `#{:a :b :c} → #{:d :e :f}` projects every before-member
            :removed + every after-member :added, key intact. (Distinct
            from the empty↔populated path — both sides are populated.)"
    (let [p (engine/project {:tags #{:a :b :c}} {:tags #{:d :e :f}})]
      (is (= :children (engine/op-at p [:tags])))
      (is (not (contains? (:wholly-changed-roots p) [:tags])))
      (is (= :removed (engine/op-at p [:tags :a])))
      (is (= :removed (engine/op-at p [:tags :b])))
      (is (= :removed (engine/op-at p [:tags :c])))
      (is (= :added (engine/op-at p [:tags :d])))
      (is (= :added (engine/op-at p [:tags :e])))
      (is (= :added (engine/op-at p [:tags :f]))))))

(deftest multimember-set-swap-at-root
  (testing "rf2-4vp8c — the same delta at the ROOT (a bare set, not nested):
            `#{:a :b :c} → #{:a :d :e}` projects member-level at the root.
            The set root `[]` is excluded from wholly-changed promotion
            regardless; the invariant is no whole-set replace."
    (let [p (engine/project #{:a :b :c} #{:a :d :e})]
      (is (= :removed (engine/op-at p [:b])))
      (is (= :removed (engine/op-at p [:c])))
      (is (= :added (engine/op-at p [:d])))
      (is (= :added (engine/op-at p [:e])))
      (is (= :same (engine/op-at p [:a])))
      (is (= #{} (:wholly-changed-roots p)))
      ;; The root set is NOT a single :modified.
      (is (not= :modified (engine/op-at p []))))))

(deftest multimember-machine-tags-transition-repro
  (testing "rf2-4vp8c — the live repro path: a machine `:tags` set on a
            state transition drops two tags + adds one
            (`#{:door/locked :door/secure} → #{:door/open}`). The :tags key
            stays intact and reads `-:door/locked -:door/secure +:door/open`."
    (let [p (engine/project {:tags #{:door/locked :door/secure}}
                            {:tags #{:door/open}})]
      (is (= :children (engine/op-at p [:tags])))
      (is (= :removed (engine/op-at p [:tags :door/locked])))
      (is (= :removed (engine/op-at p [:tags :door/secure])))
      (is (= :added (engine/op-at p [:tags :door/open])))
      (is (not (contains? (:wholly-changed-roots p) [:tags]))))))

(deftest multimember-set-swap-feeds-flat-rows
  (testing "rf2-4vp8c — the multi-member member-level delta also surfaces on
            the `:diff` (pure-list) flat-rows lens, with the key intact."
    (let [p (engine/project {:tags #{:a :b :c}} {:tags #{:a :d :e}})
          rows (:flat-rows p)
          paths (set (map :path rows))
          op-at-path (fn [path]
                       (some (fn [r] (when (= path (:path r)) (:op r))) rows))]
      (is (contains? paths [:tags :b]))
      (is (contains? paths [:tags :c]))
      (is (contains? paths [:tags :d]))
      (is (contains? paths [:tags :e]))
      (is (= :removed (op-at-path [:tags :b])))
      (is (= :added (op-at-path [:tags :d])))
      ;; The surviving member + the intact key are NOT flat-rows.
      (is (not (contains? paths [:tags :a])))
      (is (not (contains? paths [:tags]))))))

(deftest multimember-set-swap-does-not-promote-ancestor-map
  (testing "rf2-4vp8c — a multi-member-swapped set must not falsely promote
            an ANCESTOR map (the deeper trap). `{:m {:tags #{:a :b :c}}} →
            {:m {:tags #{:a :d :e}}}` keeps both `:m` and `:m :tags` intact."
    (let [p (engine/project {:m {:tags #{:a :b :c}}}
                            {:m {:tags #{:a :d :e}}})]
      (is (= :children (engine/op-at p [:m])))
      (is (= :children (engine/op-at p [:m :tags])))
      (is (= #{} (:wholly-changed-roots p)))
      (is (= :removed (engine/op-at p [:m :tags :b])))
      (is (= :added (engine/op-at p [:m :tags :d])))
      (is (= :same (engine/op-at p [:m :tags :a]))))))

(deftest multimember-set-of-non-keyword-members
  (testing "rf2-4vp8c — multi-member match BY VALUE regardless of type. A
            set of numbers swapping multiple members diffs member-level."
    (let [p (engine/project {:t #{1 2 3}} {:t #{1 4 5}})]
      (is (= :children (engine/op-at p [:t])))
      (is (= :removed (engine/op-at p [:t 2])))
      (is (= :removed (engine/op-at p [:t 3])))
      (is (= :added (engine/op-at p [:t 4])))
      (is (= :added (engine/op-at p [:t 5])))
      (is (= :same (engine/op-at p [:t 1]))))))

(deftest multimember-set-branch-leaves-type-changes-alone
  (testing "rf2-4vp8c — regression guard: the broadened SET↔SET `:r`
            branch must only fire when BOTH sides are sets. A set↔non-set
            flip (set→vector, scalar→set) stays an R7 `:modified`
            type-change at the container path, NOT a member-delta."
    ;; set → vector — R7 type change, key reads :modified, type-change? set.
    (let [p (engine/project {:t #{:a :b}} {:t [:a :b]})]
      (is (= :modified (engine/op-at p [:t])))
      (is (engine/type-change? p [:t])))
    ;; scalar → set — R7 type change.
    (let [p (engine/project {:t 5} {:t #{:a}})]
      (is (= :modified (engine/op-at p [:t])))
      (is (engine/type-change? p [:t])))))

