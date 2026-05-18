(ns re-frame-pair2-mcp.diff-encode-epochs-test
  "Unit tests for the diff-encoded epoch slice (rf2-1wdzp).

  Per `tools/pair2-mcp/spec/Principles.md` mechanism (Diff-encoded
  epoch slice), every `:rf/epoch-record` shipped over the wire has
  its `:db-after` replaced with a path-keyed structural diff against
  its own `:db-before` by default. The transform lives in
  `re-frame.mcp-base.diff-encode` at the cross-MCP boundary; the
  pair2-mcp aliases (`tools.dedup/diff-encode-epochs`,
  `tools.dedup/parse-epochs-mode`) re-export the surface and the
  snapshot pipeline composes them via
  `tools.snapshot-pipeline/diff-encode-epochs-in-snapshot`.

  Tests pin the public surfaces directly:
  `re-frame.mcp-base.diff-encode/collect-patches`,
  `re-frame.mcp-base.diff-encode/apply-patches`,
  `re-frame.mcp-base.diff-encode/diff-encode-db-after`,
  `re-frame.mcp-base.diff-encode/decode-db-after`,
  `re-frame.mcp-base.diff-encode/diff-encode-epochs`,
  `tools.snapshot-pipeline/diff-encode-epochs-in-snapshot`,
  `tools.dedup/parse-epochs-mode`. A rename or signature change
  surfaces as a failing test rather than silent contract drift.

  Live end-to-end coverage runs against a real shadow-cljs build
  with a populated `epoch-history`; this file pins the pure CLJS
  transforms and the round-trip property."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.mcp-base.diff-encode :as diff]
            [re-frame-pair2-mcp.tools.dedup :as dedup]
            [re-frame-pair2-mcp.tools.snapshot-pipeline :as pipeline]))

;; ---------------------------------------------------------------------------
;; collect-patches — the encoder's path-keyed diff factory.
;; ---------------------------------------------------------------------------

(deftest collect-patches-equal-maps-no-patches
  (is (= [] (diff/collect-patches {:a 1} {:a 1} []))))

(deftest collect-patches-added-key-emits-assoc
  (is (= [[[:b] :assoc 2]]
         (diff/collect-patches {:a 1} {:a 1 :b 2} []))))

(deftest collect-patches-removed-key-emits-dissoc
  (is (= [[[:b] :dissoc]]
         (diff/collect-patches {:a 1 :b 2} {:a 1} []))))

(deftest collect-patches-changed-leaf-emits-assoc
  (is (= [[[:a] :assoc 99]]
         (diff/collect-patches {:a 1} {:a 99} []))))

(deftest collect-patches-nested-change-emits-deep-path
  (let [patches (diff/collect-patches {:user {:auth {:token "abc"}}}
                                       {:user {:auth {:token "xyz"}}}
                                       [])]
    (is (= [[[:user :auth :token] :assoc "xyz"]] patches))))

(deftest collect-patches-non-map-replacement-at-root
  ;; Root-level swap from one shape to another wholly different shape.
  (is (= [[[] :assoc :replaced]]
         (diff/collect-patches {:a 1} :replaced []))))

(deftest collect-patches-vector-leaf-replaces-wholesale
  ;; Vectors are treated as leaves — element-wise diff doesn't help
  ;; for typical app-db vector values which are short.
  (is (= [[[:items] :assoc [3 2 1]]]
         (diff/collect-patches {:items [1 2 3]} {:items [3 2 1]} []))))

;; ---------------------------------------------------------------------------
;; apply-patches — the decoder.
;; ---------------------------------------------------------------------------

(deftest apply-patches-empty-list-is-identity
  (is (= {:a 1 :b 2} (diff/apply-patches {:a 1 :b 2} []))))

(deftest apply-patches-assoc-adds-or-changes-value
  (is (= {:a 1 :b 99}
         (diff/apply-patches {:a 1 :b 2} [[[:b] :assoc 99]]))))

(deftest apply-patches-dissoc-removes-key
  (is (= {:a 1}
         (diff/apply-patches {:a 1 :b 2} [[[:b] :dissoc]]))))

(deftest apply-patches-root-assoc-replaces-value
  (is (= :other
         (diff/apply-patches {:a 1} [[[] :assoc :other]]))))

(deftest apply-patches-deep-path-creates-or-updates
  (is (= {:a {:b {:c 99}}}
         (diff/apply-patches {:a {:b {:c 1}}} [[[:a :b :c] :assoc 99]]))))

(deftest apply-patches-applies-in-order
  ;; Two patches against the same parent: order matters.
  (is (= {:a 1 :b 2}
         (diff/apply-patches {:a 1}
                              [[[:b] :assoc 99]
                               [[:b] :assoc 2]]))))

;; ---------------------------------------------------------------------------
;; diff-encode-db-after — the encoder shape.
;; ---------------------------------------------------------------------------

(def ^:private fixture-epoch
  {:epoch-id     :ep-1
   :frame        :rf/default
   :committed-at 1234567890
   :event-id     :cart/add
   :trigger-event [:cart/add {:sku "A1"}]
   :db-before    {:cart {:items [] :total 0}
                  :user {:id 7}}
   :db-after     {:cart {:items [{:sku "A1"}] :total 10}
                  :user {:id 7}}})

(deftest diff-encode-db-after-replaces-db-after-with-marker
  ;; rf2-qeous: the wire shape is path-headed cluster sections, not a
  ;; flat patch list. The `:rf.mcp/diff-from` marker stays the same;
  ;; the body slot moves from `:patches` to `:sections`.
  (let [enc (diff/diff-encode-db-after fixture-epoch)
        da  (:db-after enc)]
    (is (= :db-before (:rf.mcp/diff-from da))
        "Diff marker carries the source-slot key")
    (is (vector? (:sections da)))
    (is (every? (every-pred map? #(contains? % :section-path)
                            #(contains? % :section-kind)
                            #(contains? % :patches))
                (:sections da))
        "every section carries :section-path + :section-kind + :patches")))

(deftest diff-encode-db-after-leaves-db-before-untouched
  (let [enc (diff/diff-encode-db-after fixture-epoch)]
    (is (= (:db-before fixture-epoch) (:db-before enc))
        ":db-before stays the canonical reference")))

(deftest diff-encode-db-after-no-db-before-leaves-epoch-alone
  ;; Synthetic / pruned epoch without :db-before can't be diffed —
  ;; pass-through is the correct posture.
  (let [partial-epoch {:epoch-id :ep-x :db-after {:foo 1}}]
    (is (= partial-epoch (diff/diff-encode-db-after partial-epoch)))))

(deftest diff-encode-db-after-non-map-passes-through
  (is (= :not-an-epoch (diff/diff-encode-db-after :not-an-epoch)))
  (is (= nil (diff/diff-encode-db-after nil))))

;; ---------------------------------------------------------------------------
;; Round-trip property: encode → decode → identity.
;; ---------------------------------------------------------------------------

(deftest round-trip-single-key-change
  (let [enc (diff/diff-encode-db-after fixture-epoch)
        dec (diff/decode-db-after enc)]
    (is (= fixture-epoch dec)
        "encode→decode round-trips the full epoch unchanged")))

(deftest round-trip-identical-db-before-and-db-after
  ;; Degenerate case: no change. Sections list is empty (per the
  ;; rf2-qeous shape — empty patches → empty sections); decoder
  ;; returns :db-before unchanged.
  (let [epoch (assoc fixture-epoch :db-after (:db-before fixture-epoch))
        enc   (diff/diff-encode-db-after epoch)
        dec   (diff/decode-db-after enc)]
    (is (= epoch dec))
    (is (= [] (-> enc :db-after :sections)))))

(deftest round-trip-all-keys-changed
  ;; Degenerate case: no overlap. Every key in :db-before is dissoc'd
  ;; and every key in :db-after is assoc'd. Wire size won't shrink
  ;; for this case (the patches carry the new values); but round-trip
  ;; MUST still reconstruct.
  (let [epoch {:db-before {:a 1 :b 2}
               :db-after  {:c 3 :d 4}}
        enc   (diff/diff-encode-db-after epoch)
        dec   (diff/decode-db-after enc)]
    (is (= epoch dec))))

(deftest round-trip-deeply-nested-leaf-change
  ;; The load-bearing efficiency case: one tiny change in a deeply
  ;; nested tree. Per rf2-qeous the wire shape is now sections-per-
  ;; cluster — the encoded :db-after carries the section wrapper
  ;; (~60-80 chars constant overhead per cluster) plus the patches
  ;; themselves. The win still scales: when the unchanged parts
  ;; truly dwarf the change, the encoded :db-after is a small
  ;; fraction of the full one.
  (let [;; Build a wide map so the unchanged parts dwarf the change.
        ;; 200 keys × ~30 chars each ≈ 6KB of unchanged context.
        wide (into {} (for [i (range 200)]
                        [(keyword (str "k" i))
                         (apply str (repeat 30 (char (+ 97 (mod i 26)))))]))
        epoch {:db-before {:user {:auth {:tokens {:access "abc"
                                                  :refresh "def"}}}
                            :wide wide
                            :cart {:items (vec (range 200))}}
               :db-after  {:user {:auth {:tokens {:access "xyz"
                                                  :refresh "def"}}}
                            :wide wide
                            :cart {:items (vec (range 200))}}}
        enc   (diff/diff-encode-db-after epoch)
        dec   (diff/decode-db-after enc)]
    (is (= epoch dec))
    ;; Efficiency check: encoded :db-after should be a small fraction
    ;; of the full db-after for a tiny change in a large tree.
    (let [full-size (count (pr-str (:db-after epoch)))
          enc-size  (count (pr-str (:db-after enc)))]
      (is (< enc-size (/ full-size 20))
          (str "Encoded :db-after (" enc-size " chars) should be << full ("
               full-size " chars) for a single-leaf change. Ratio: "
               (/ enc-size full-size 1.0))))))

(deftest round-trip-map-key-removal
  (let [epoch {:db-before {:a 1 :b 2 :c 3}
               :db-after  {:a 1 :c 3}}
        enc   (diff/diff-encode-db-after epoch)
        dec   (diff/decode-db-after enc)
        sections (-> enc :db-after :sections)]
    (is (= epoch dec))
    (is (= 1 (count sections)) "one removed key → one section")
    (let [s (first sections)]
      (is (= [:b] (:section-path s))
          "top-level singleton keeps its full path as the breadcrumb")
      (is (= :removed (:section-kind s))
          "all-:dissoc section → :section-kind :removed")
      (is (= [[[:b] :dissoc]] (:patches s))
          ":b was removed → surfaces as a :dissoc patch inside the section"))))

(deftest round-trip-vector-reordering
  ;; Vectors are leaves — a reorder triggers a wholesale :assoc.
  ;; Round-trip MUST reconstruct.
  (let [epoch {:db-before {:items [1 2 3]}
               :db-after  {:items [3 2 1]}}
        enc   (diff/diff-encode-db-after epoch)
        dec   (diff/decode-db-after enc)]
    (is (= epoch dec))))

(deftest round-trip-empty-maps
  (let [epoch {:db-before {} :db-after {}}
        enc   (diff/diff-encode-db-after epoch)
        dec   (diff/decode-db-after enc)]
    (is (= epoch dec))
    (is (= [] (-> enc :db-after :sections)))))

(deftest round-trip-scalar-app-db
  ;; A pathological app-db that's a scalar — rare but valid. The
  ;; encoder records the change as a root-level :assoc replacement.
  (let [epoch {:db-before 42 :db-after 99}
        enc   (diff/diff-encode-db-after epoch)
        dec   (diff/decode-db-after enc)]
    (is (= epoch dec))))

(deftest round-trip-value-changed-to-nil
  ;; Edge case: a key's value legitimately becomes nil. The encoder
  ;; emits an :assoc with the nil value (distinguishable from a
  ;; :dissoc which removes the key entirely).
  (let [epoch {:db-before {:k :v} :db-after {:k nil}}
        enc   (diff/diff-encode-db-after epoch)
        dec   (diff/decode-db-after enc)]
    (is (= epoch dec))
    (is (contains? (:db-after dec) :k))
    (is (nil? (-> dec :db-after :k)))))

(deftest round-trip-nested-key-addition-and-removal
  ;; Two-axis change: one key removed, another added, deep in the tree.
  (let [epoch {:db-before {:user {:profile {:name "alice" :age 30}}}
               :db-after  {:user {:profile {:name "alice" :email "a@b"}}}}
        enc   (diff/diff-encode-db-after epoch)
        dec   (diff/decode-db-after enc)]
    (is (= epoch dec))))

;; ---------------------------------------------------------------------------
;; diff-encode-epochs — the slice-level transform.
;; ---------------------------------------------------------------------------

(deftest diff-encode-epochs-diff-mode-encodes-every-record
  (let [epochs [fixture-epoch fixture-epoch fixture-epoch]
        enc    (diff/diff-encode-epochs epochs :diff)]
    (is (= 3 (count enc)))
    (doseq [e enc]
      (is (= :db-before (-> e :db-after :rf.mcp/diff-from))))))

(deftest diff-encode-epochs-full-mode-pass-through
  (let [epochs [fixture-epoch fixture-epoch]
        enc    (diff/diff-encode-epochs epochs :full)]
    (is (= epochs enc)
        ":full mode is a no-op — agent gets the legacy shape")))

(deftest diff-encode-epochs-each-record-self-contained
  ;; Independence property: each epoch encodes against ITS OWN
  ;; :db-before; reordering / pagination / filtering of the slice
  ;; doesn't break decode.
  (let [e1 {:db-before {:a 1} :db-after {:a 2}}
        e2 {:db-before {:b 9} :db-after {:b 9 :c 7}}
        enc (diff/diff-encode-epochs [e1 e2] :diff)]
    (is (= e1 (diff/decode-db-after (first enc))))
    (is (= e2 (diff/decode-db-after (second enc))))
    ;; Reverse order — still decodable.
    (let [reversed (reverse enc)]
      (is (= e2 (diff/decode-db-after (first reversed))))
      (is (= e1 (diff/decode-db-after (second reversed)))))))

(deftest diff-encode-epochs-empty-vector
  (is (= [] (diff/diff-encode-epochs [] :diff)))
  (is (= [] (diff/diff-encode-epochs [] :full))))

;; ---------------------------------------------------------------------------
;; diff-encode-epochs-in-snapshot — the snapshot-tool integration.
;; ---------------------------------------------------------------------------

(def ^:private fixture-snapshot
  {:rf/default {:app-db    {:k :v}
                :sub-cache {}
                :machines  {:ids [] :state {}}
                :epochs    [fixture-epoch fixture-epoch]
                :traces    []}
   :stories    {:app-db    {:k2 :v2}
                :sub-cache {}
                :machines  {:ids [] :state {}}
                :epochs    [{:db-before {:foo 1} :db-after {:foo 2}}]
                :traces    []}})

(deftest snapshot-diff-mode-encodes-every-frames-epochs
  (let [enc (pipeline/diff-encode-epochs-in-snapshot fixture-snapshot :diff)]
    (testing ":epochs slice transformed on every frame"
      (doseq [[_fid fmap] enc]
        (doseq [ep (:epochs fmap)]
          (is (= :db-before (-> ep :db-after :rf.mcp/diff-from))))))
    (testing "other slices pass through unchanged"
      (is (= {:k :v} (-> enc :rf/default :app-db)))
      (is (= {} (-> enc :rf/default :sub-cache)))
      (is (= [] (-> enc :rf/default :traces))))))

(deftest snapshot-full-mode-passes-through
  (is (= fixture-snapshot
         (pipeline/diff-encode-epochs-in-snapshot fixture-snapshot :full))))

(deftest snapshot-skips-frames-without-epochs-slice
  ;; The :include filter may exclude :epochs. Don't add one.
  (let [snap {:rf/default {:app-db {} :sub-cache {} :machines {}}}
        enc  (pipeline/diff-encode-epochs-in-snapshot snap :diff)]
    (is (not (contains? (:rf/default enc) :epochs)))))

(deftest snapshot-non-map-passes-through
  (is (nil? (pipeline/diff-encode-epochs-in-snapshot nil :diff)))
  (is (= :not-a-snap (pipeline/diff-encode-epochs-in-snapshot :not-a-snap :diff))))

;; ---------------------------------------------------------------------------
;; parse-epochs-mode — MCP-arg normalisation.
;; ---------------------------------------------------------------------------

(deftest parse-epochs-mode-default-is-diff
  (is (= :diff (dedup/parse-epochs-mode nil))))

(deftest parse-epochs-mode-strings-accepted
  (is (= :diff (dedup/parse-epochs-mode "diff")))
  (is (= :full (dedup/parse-epochs-mode "full"))))

(deftest parse-epochs-mode-keywords-accepted
  (is (= :diff (dedup/parse-epochs-mode :diff)))
  (is (= :full (dedup/parse-epochs-mode :full))))

(deftest parse-epochs-mode-unknown-falls-back-to-diff
  ;; Least-surprise on the budget-sensitive default: an unrecognised
  ;; value gets the smaller-wire-payload behaviour, not the larger.
  (is (= :diff (dedup/parse-epochs-mode "garbage")))
  (is (= :diff (dedup/parse-epochs-mode 42)))
  (is (= :diff (dedup/parse-epochs-mode :other))))

;; ---------------------------------------------------------------------------
;; Wire-size impact: 10-epoch window with a 1MB app-db, single-key
;; change per epoch — the load-bearing scenario rf2-jlq5j flagged.
;; ---------------------------------------------------------------------------

(deftest ten-epoch-window-with-1mb-app-db-shrinks-dramatically
  (let [;; Build a "big" app-db: 1024 keys, each pointing at a 1KB
        ;; string value ⇒ ~1MB pr-str.
        big-db (into {} (for [i (range 1024)]
                          [(keyword (str "k" i))
                           (apply str (repeat 1024 \x))]))
        ;; 10 epochs, each modifying exactly one key.
        epochs (vec (for [i (range 10)]
                      {:epoch-id (str "ep-" i)
                       :frame :rf/default
                       :event-id :touch
                       :db-before big-db
                       :db-after  (assoc big-db
                                         (keyword (str "k" i))
                                         (apply str (repeat 1024 \y)))}))
        full-size (count (pr-str epochs))
        diff-encoded (diff/diff-encode-epochs epochs :diff)
        diff-size (count (pr-str diff-encoded))]
    (testing "diff-encoded slice is much smaller than the full pair"
      ;; Full: 10 × ~2MB = ~20MB on the wire.
      ;; Diff: 10 × ~1MB (the :db-before reference) + tiny patches.
      ;; That's ~50% off — the :db-before is still present per record
      ;; for self-containment, but the duplicate :db-after disappears.
      (is (< diff-size full-size))
      (is (< diff-size (* 0.6 full-size))
          (str "Diff-encoded size (" diff-size
               ") should be << 60% of full (" full-size
               "). Ratio: " (/ diff-size full-size 1.0))))
    (testing "round-trip still reconstructs every epoch"
      (let [decoded (mapv diff/decode-db-after diff-encoded)]
        (is (= epochs decoded))))))
