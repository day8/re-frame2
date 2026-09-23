(ns re-frame2-pair-mcp.wire-pipeline-test
  "Correctness unit tests for `wire-pipeline/run-wire-pipeline`
  indicator counting on the `:epoch-vector` and `:snapshot-map` arms.

  The `:epoch-vector` arm (trace-window / watch-epochs) walks the
  payload locally for the `:elided-large` indicator — the count is NOT
  pre-shipped from the runtime (unlike `:scalar-value`, which carries
  `:server-elided`). Since rf2-3x7nj.32.8 the `:snapshot-map` arm walks
  locally too, over what it ships — see the section at the end.

  THE CONTRACT: the arm counts markers over the PRE-dedup (`encoded`)
  payload. `re-frame.mcp-base.dedup` pools N identical `:rf.size/large-elided` maps
  into ONE structural-sharing cache entry, so counting over the
  POST-dedup payload would report `:elided-large == 1` instead of N
  (the markers themselves always ride the wire intact — only the scalar
  indicator is at risk). The marker SET is identical pre/post dedup
  (dedup only re-shapes structural references; it never drops a marker),
  so the pre-dedup count is exact.

  These pin `run-wire-pipeline` directly — the pure transform — rather
  than through the live nREPL eval boundary (covered by the
  stdio-roundtrip harness)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.mcp-base.dedup :as rf.mcp-base.dedup]
            [re-frame.mcp-base.diff-encode :as rf.mcp-base.diff-encode]
            [re-frame.mcp-base.elision :as rf.mcp-base.elision]
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
;; :elided-large counts EVERY marker, not the deduped count.
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
          encoded (rf.mcp-base.diff-encode/diff-encode-epochs epochs :diff)
          deduped (rf.mcp-base.dedup/dedup-value encoded true)]
      ;; Walking `deduped` returns 1 here (the undercount); walking
      ;; `encoded` — the pipeline's choice — returns the correct 3.
      (is (= 1 (rf.mcp-base.elision/count-elided-markers deduped))
          "dedup pools the 3 equal markers → walking the deduped payload undercounts to 1 (the bug)")
      (is (= 3 (rf.mcp-base.elision/count-elided-markers encoded))
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

;; ---------------------------------------------------------------------------
;; rf2-3x7nj.32.8 — snapshot's :elided-large counts the markers it SHIPS.
;;
;; The snapshot eval form counts markers app-side over the WHOLE walked
;; state (every frame's full :app-db, every :sub-cache entry, every epoch)
;; and used to hand that figure straight to the envelope. The path slice
;; and the summary pass both remove markers, so the default `snapshot {}`
;; (summary mode — no app-db values at all) and a path-sliced read reported
;; markers the response did not contain. Each witness passes the
;; `:server-elided` figure the eval form would report, so it is RED on the
;; pre-fix tree, which took that figure verbatim.
;; ---------------------------------------------------------------------------

(def ^:private docs-marker (large-marker [:docs :body]))

(def ^:private declared-db
  "A frame whose `[:docs :body]` came back from the walker as a marker."
  {:docs {:body docs-marker} :user {:id 7 :name "ann"}})

(defn- snapshot-count
  [snap overrides]
  (-> (wp/run-wire-pipeline snap (merge {:kind        :snapshot-map
                                         :incl?       false
                                         :mode        :diff
                                         :dedup?      true
                                         :slice-mode  :summary
                                         :slice-modes {}}
                                        overrides))
      :indicators :elided))

(deftest snapshot-summary-mode-counts-no-marker-it-does-not-ship
  (testing "default `snapshot {}`: :app-db ships as a {:rf.mcp/summary ...}, so :elided-large is 0"
    (is (= 0 (snapshot-count {:rf/default {:app-db declared-db}}
                             {:server-elided 1}))
        "the app-side count (1) described the walked state, not the summary that ships"))
  (testing "a summarised :sub-cache ships no marker either"
    (is (= 0 (snapshot-count {:rf/default {:app-db {} :sub-cache {[:doc] {:value docs-marker}}}}
                             {:server-elided 1})))))

(deftest snapshot-path-slice-counts-only-the-addressed-subtree
  (testing "`snapshot {:path [:user]}` ships the complete [:user] subtree — no marker in it"
    (is (= 0 (snapshot-count {:rf/default {:app-db declared-db}}
                             {:path [:user] :server-elided 1}))))
  (testing "control: a path whose subtree DOES carry the marker counts it"
    (is (= 1 (snapshot-count {:rf/default {:app-db declared-db}}
                             {:path [:docs] :server-elided 1}))))
  (testing "control: a full-mode :app-db ships the marker and counts it"
    (is (= 1 (snapshot-count {:rf/default {:app-db declared-db}}
                             {:slice-mode :full :server-elided 1})))))

(deftest snapshot-full-epochs-are-counted-pre-dedup
  (testing "a :full :epochs slice with dedup on counts every marker, not the pooled one"
    (let [epochs (vec (for [n (range 3)] (epoch-with-marker n (large-marker [:slot]))))]
      (is (= 3 (snapshot-count {:rf/default {:app-db {} :epochs epochs}}
                               {:slice-modes {:epochs :full}}))
          "3 identical markers ride the wire; dedup pools them, the count must not")
      (is (= 0 (snapshot-count {:rf/default {:app-db {} :epochs epochs}} {}))
          "control: a summarised :epochs slice ships no marker"))))
