(ns re-frame2-pair-mcp.wire-pipeline-test
  "Correctness unit tests for `wire-pipeline/run-wire-pipeline`
  indicator counting on the `:epoch-vector` arm (rf2-mb17rj).

  The `:epoch-vector` arm (trace-window / watch-epochs) walks the
  payload locally for the `:elided-large` indicator — the count is NOT
  pre-shipped from the runtime (unlike `:snapshot-map` / `:scalar-value`,
  which carry `:server-elided`).

  THE BUG (rf2-mb17rj, LOW): the arm counted markers over the
  POST-dedup payload. `day8/de-dupe` pools N identical
  `:rf.size/large-elided` maps into ONE structural-sharing cache entry,
  so a payload with N identical markers reported `:elided-large == 1`
  instead of N. The markers themselves rode the wire intact (no
  data-loss / leak) — only the scalar indicator undercounted.

  THE FIX: count over the PRE-dedup (`encoded`) payload. The marker
  SET is identical pre/post dedup (dedup only re-shapes structural
  references; it never drops a marker), so the pre-dedup count is
  exact.

  These pin `run-wire-pipeline` directly — the pure transform — rather
  than through the live nREPL eval boundary (covered by the
  stdio-roundtrip harness)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.mcp-base.elision :as base-elision]
            [re-frame2-pair-mcp.tools.dedup :as dedup]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]))

(defn- large-marker
  "A `:rf.size/large-elided` marker as `rf/elide-wire-value` emits it
  (Spec 009 §Size elision in traces). `path` distinguishes the handle;
  pass the SAME path to all records to make the marker maps EQUAL so
  dedup pools them — the exact pre-fix undercount trigger."
  [path]
  {:rf.size/large-elided
   {:path path :bytes 102400 :type :string
    :handle [:rf.elision/at path]}})

(defn- epoch-with-marker
  "An epoch record carrying an IDENTICAL large-elided marker in its
  `:db-before` (which diff-encode preserves verbatim). `:db-after`
  equals `:db-before`, so the intra-record diff is empty and the
  marker survives only on `:db-before`. Three such records share one
  marker map by equality."
  [n marker]
  {:epoch-id  n
   :event-id  (keyword (str "ev" n))
   :db-before {:slot marker}
   :db-after  {:slot marker}})

;; ---------------------------------------------------------------------------
;; rf2-mb17rj — :elided-large counts EVERY marker, not the deduped count.
;; ---------------------------------------------------------------------------

(deftest epoch-vector-counts-all-elided-markers-pre-dedup
  (testing "3 records each carrying an identical large-elided marker → :elided-large == 3 (dedup on)"
    (let [marker (large-marker [:slot])
          epochs (vec (for [n (range 3)] (epoch-with-marker n marker)))
          {:keys [indicators]}
          (wp/run-wire-pipeline epochs {:kind   :epoch-vector
                                        :incl?  false
                                        :mode   :diff
                                        :dedup? true})]
      (is (= 3 (:elided indicators))
          "every one of the 3 identical markers is counted, NOT pooled to 1 by dedup")
      ;; Guard against a regression that re-walks the deduped payload:
      ;; that path collapses the 3 equal markers to a single cache
      ;; entry, so the walker would see 1.
      (is (= 3 (count epochs)) "fixture sanity: 3 records"))))

(deftest epoch-vector-dedup-of-equal-markers-would-undercount-without-fix
  (testing "the deduped payload genuinely pools the 3 equal markers — proving the fix is load-bearing"
    (let [marker  (large-marker [:slot])
          epochs  (vec (for [n (range 3)] (epoch-with-marker n marker)))
          encoded (dedup/diff-encode-epochs epochs :diff)
          deduped (dedup/dedup-value encoded true)]
      ;; The pre-fix code walked `deduped` and would have returned 1
      ;; here; the fix walks `encoded` and returns 3.
      (is (= 1 (base-elision/count-elided-markers deduped))
          "dedup pools the 3 equal markers → walking the deduped payload undercounts to 1 (the bug)")
      (is (= 3 (base-elision/count-elided-markers encoded))
          "walking the pre-dedup payload counts all 3 (the fix)"))))

(deftest epoch-vector-elided-count-stable-with-dedup-off
  (testing "dedup off → no pooling → :elided-large == 3 either way"
    (let [marker (large-marker [:slot])
          epochs (vec (for [n (range 3)] (epoch-with-marker n marker)))
          {:keys [indicators]}
          (wp/run-wire-pipeline epochs {:kind   :epoch-vector
                                        :incl?  false
                                        :mode   :diff
                                        :dedup? false})]
      (is (= 3 (:elided indicators))
          "with dedup off the count is unchanged — the fix is dedup-invariant"))))

(deftest epoch-vector-no-markers-counts-zero
  (testing "marker-free payload → :elided-large == 0 (the common path)"
    (let [epochs [{:epoch-id 1 :event-id :ev1 :db-before {:a 1} :db-after {:a 2}}
                  {:epoch-id 2 :event-id :ev2 :db-before {:a 2} :db-after {:a 3}}]
          {:keys [indicators]}
          (wp/run-wire-pipeline epochs {:kind   :epoch-vector
                                        :incl?  false
                                        :mode   :diff
                                        :dedup? true})]
      (is (= 0 (:elided indicators))))))
