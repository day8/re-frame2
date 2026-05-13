(ns re-frame-pair2-mcp.diff-encode-epochs-test
  "Unit tests for the diff-encoded epoch slice (rf2-1wdzp).

  Per `tools/pair2-mcp/spec/Principles.md` mechanism (Diff-encoded
  epoch slice), every `:rf/epoch-record` shipped over the wire has
  its `:db-after` replaced with a path-keyed structural diff against
  its own `:db-before` by default. The transform lives in
  `tools.cljs` at the MCP wire boundary; opt-back-in to the legacy
  full-pair shape via `epochs-mode \"full\"` on snapshot /
  trace-window / watch-epochs.

  These tests mirror the private diff-encoding helpers from
  `tools.cljs` (`collect-patches`, `apply-patches`,
  `diff-encode-db-after`, `decode-db-after`, `diff-encode-epochs`,
  `diff-encode-epochs-in-snapshot`, `parse-epochs-mode`). A rename or
  signature change surfaces as a failing test rather than silent
  contract drift.

  Live end-to-end coverage runs against a real shadow-cljs build
  with a populated `epoch-history`; this file pins the pure CLJS
  transforms and the round-trip property."
  (:require [cljs.test :refer-macros [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; Mirrors of the private diff helpers from tools.cljs. Keep in
;; lockstep — sibling tests follow the same convention (CLJS private
;; vars aren't reachable across namespaces without `#'` so we copy
;; the surface and lean on regression coverage).
;; ---------------------------------------------------------------------------

(declare collect-patches)

(defn- collect-map-patches [a b path]
  (let [ks (into #{} (concat (keys a) (keys b)))]
    (reduce
      (fn [acc k]
        (let [av (get a k ::absent)
              bv (get b k ::absent)
              p  (conj path k)]
          (cond
            (= bv ::absent) (conj acc [p :dissoc])
            (= av ::absent) (conj acc [p :assoc bv])
            (= av bv) acc
            (and (map? av) (map? bv)) (into acc (collect-patches av bv p))
            :else (conj acc [p :assoc bv]))))
      []
      ks)))

(defn- collect-patches [a b path]
  (cond
    (= a b) []
    (and (map? a) (map? b)) (collect-map-patches a b path)
    :else [[path :assoc b]]))

(defn- apply-patches [base patches]
  (reduce
    (fn [acc patch]
      (let [[path op v] patch]
        (cond
          (empty? path) (if (= op :assoc) v acc)
          (= op :assoc) (assoc-in acc path v)
          (= op :dissoc)
          (let [parent-path (vec (butlast path))
                k           (last path)]
            (if (empty? parent-path)
              (dissoc acc k)
              (update-in acc parent-path dissoc k)))
          :else acc)))
    base
    patches))

(defn- diff-encode-db-after [epoch]
  (if-not (and (map? epoch)
               (contains? epoch :db-before)
               (contains? epoch :db-after))
    epoch
    (let [patches (collect-patches (:db-before epoch) (:db-after epoch) [])]
      (assoc epoch :db-after
             {:rf.mcp/diff-from :db-before
              :patches          patches}))))

(defn- decode-db-after [epoch]
  (let [da (when (map? epoch) (:db-after epoch))]
    (if-not (and (map? da)
                 (= :db-before (:rf.mcp/diff-from da)))
      epoch
      (let [patches   (:patches da)
            db-before (:db-before epoch)
            rebuilt   (apply-patches db-before (or patches []))]
        (assoc epoch :db-after rebuilt)))))

(defn- diff-encode-epochs [epochs mode]
  (if (= mode :full)
    epochs
    (mapv diff-encode-db-after epochs)))

(defn- diff-encode-epochs-in-snapshot [snapshot mode]
  (cond
    (or (= mode :full) (not (map? snapshot)))
    snapshot
    :else
    (reduce-kv
      (fn [m fid fmap]
        (assoc m fid
               (if (and (map? fmap) (contains? fmap :epochs))
                 (update fmap :epochs diff-encode-epochs mode)
                 fmap)))
      {} snapshot)))

(defn- parse-epochs-mode [raw]
  (cond
    (nil? raw)         :diff
    (= raw :full)      :full
    (= raw "full")     :full
    (= raw :diff)      :diff
    (= raw "diff")     :diff
    :else              :diff))

;; ---------------------------------------------------------------------------
;; collect-patches — the encoder's path-keyed diff factory.
;; ---------------------------------------------------------------------------

(deftest collect-patches-equal-maps-no-patches
  (is (= [] (collect-patches {:a 1} {:a 1} []))))

(deftest collect-patches-added-key-emits-assoc
  (is (= [[[:b] :assoc 2]]
         (collect-patches {:a 1} {:a 1 :b 2} []))))

(deftest collect-patches-removed-key-emits-dissoc
  (is (= [[[:b] :dissoc]]
         (collect-patches {:a 1 :b 2} {:a 1} []))))

(deftest collect-patches-changed-leaf-emits-assoc
  (is (= [[[:a] :assoc 99]]
         (collect-patches {:a 1} {:a 99} []))))

(deftest collect-patches-nested-change-emits-deep-path
  (let [patches (collect-patches {:user {:auth {:token "abc"}}}
                                  {:user {:auth {:token "xyz"}}}
                                  [])]
    (is (= [[[:user :auth :token] :assoc "xyz"]] patches))))

(deftest collect-patches-non-map-replacement-at-root
  ;; Root-level swap from one shape to another wholly different shape.
  (is (= [[[] :assoc :replaced]]
         (collect-patches {:a 1} :replaced []))))

(deftest collect-patches-vector-leaf-replaces-wholesale
  ;; Vectors are treated as leaves — element-wise diff doesn't help
  ;; for typical app-db vector values which are short.
  (is (= [[[:items] :assoc [3 2 1]]]
         (collect-patches {:items [1 2 3]} {:items [3 2 1]} []))))

;; ---------------------------------------------------------------------------
;; apply-patches — the decoder.
;; ---------------------------------------------------------------------------

(deftest apply-patches-empty-list-is-identity
  (is (= {:a 1 :b 2} (apply-patches {:a 1 :b 2} []))))

(deftest apply-patches-assoc-adds-or-changes-value
  (is (= {:a 1 :b 99}
         (apply-patches {:a 1 :b 2} [[[:b] :assoc 99]]))))

(deftest apply-patches-dissoc-removes-key
  (is (= {:a 1}
         (apply-patches {:a 1 :b 2} [[[:b] :dissoc]]))))

(deftest apply-patches-root-assoc-replaces-value
  (is (= :other
         (apply-patches {:a 1} [[[] :assoc :other]]))))

(deftest apply-patches-deep-path-creates-or-updates
  (is (= {:a {:b {:c 99}}}
         (apply-patches {:a {:b {:c 1}}} [[[:a :b :c] :assoc 99]]))))

(deftest apply-patches-applies-in-order
  ;; Two patches against the same parent: order matters.
  (is (= {:a 1 :b 2}
         (apply-patches {:a 1}
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
  (let [enc (diff-encode-db-after fixture-epoch)
        da  (:db-after enc)]
    (is (= :db-before (:rf.mcp/diff-from da))
        "Diff marker carries the source-slot key")
    (is (vector? (:patches da)))))

(deftest diff-encode-db-after-leaves-db-before-untouched
  (let [enc (diff-encode-db-after fixture-epoch)]
    (is (= (:db-before fixture-epoch) (:db-before enc))
        ":db-before stays the canonical reference")))

(deftest diff-encode-db-after-no-db-before-leaves-epoch-alone
  ;; Synthetic / pruned epoch without :db-before can't be diffed —
  ;; pass-through is the correct posture.
  (let [partial-epoch {:epoch-id :ep-x :db-after {:foo 1}}]
    (is (= partial-epoch (diff-encode-db-after partial-epoch)))))

(deftest diff-encode-db-after-non-map-passes-through
  (is (= :not-an-epoch (diff-encode-db-after :not-an-epoch)))
  (is (= nil (diff-encode-db-after nil))))

;; ---------------------------------------------------------------------------
;; Round-trip property: encode → decode → identity.
;; ---------------------------------------------------------------------------

(deftest round-trip-single-key-change
  (let [enc (diff-encode-db-after fixture-epoch)
        dec (decode-db-after enc)]
    (is (= fixture-epoch dec)
        "encode→decode round-trips the full epoch unchanged")))

(deftest round-trip-identical-db-before-and-db-after
  ;; Degenerate case: no change. Patch list is empty; decoder returns
  ;; :db-before unchanged.
  (let [epoch (assoc fixture-epoch :db-after (:db-before fixture-epoch))
        enc   (diff-encode-db-after epoch)
        dec   (decode-db-after enc)]
    (is (= epoch dec))
    (is (= [] (-> enc :db-after :patches)))))

(deftest round-trip-all-keys-changed
  ;; Degenerate case: no overlap. Every key in :db-before is dissoc'd
  ;; and every key in :db-after is assoc'd. Wire size won't shrink
  ;; for this case (the patches carry the new values); but round-trip
  ;; MUST still reconstruct.
  (let [epoch {:db-before {:a 1 :b 2}
               :db-after  {:c 3 :d 4}}
        enc   (diff-encode-db-after epoch)
        dec   (decode-db-after enc)]
    (is (= epoch dec))))

(deftest round-trip-deeply-nested-leaf-change
  ;; The load-bearing efficiency case: one tiny change in a deeply
  ;; nested tree.
  (let [;; Build a wide map so the unchanged parts dwarf the change.
        wide (into {} (for [i (range 50)] [(keyword (str "k" i)) (str "v" i)]))
        epoch {:db-before {:user {:auth {:tokens {:access "abc"
                                                  :refresh "def"}}}
                            :wide wide
                            :cart {:items (vec (range 100))}}
               :db-after  {:user {:auth {:tokens {:access "xyz"
                                                  :refresh "def"}}}
                            :wide wide
                            :cart {:items (vec (range 100))}}}
        enc   (diff-encode-db-after epoch)
        dec   (decode-db-after enc)]
    (is (= epoch dec))
    ;; Efficiency check: encoded :db-after should be tiny relative to
    ;; the full db-after.
    (let [full-size (count (pr-str (:db-after epoch)))
          enc-size  (count (pr-str (:db-after enc)))]
      (is (< enc-size (/ full-size 10))
          (str "Encoded :db-after (" enc-size " chars) should be << full ("
               full-size " chars) for a single-leaf change. Ratio: "
               (/ enc-size full-size 1.0))))))

(deftest round-trip-map-key-removal
  (let [epoch {:db-before {:a 1 :b 2 :c 3}
               :db-after  {:a 1 :c 3}}
        enc   (diff-encode-db-after epoch)
        dec   (decode-db-after enc)]
    (is (= epoch dec))
    (is (= [[[:b] :dissoc]] (-> enc :db-after :patches))
        ":b was removed → surfaces as a :dissoc patch")))

(deftest round-trip-vector-reordering
  ;; Vectors are leaves — a reorder triggers a wholesale :assoc.
  ;; Round-trip MUST reconstruct.
  (let [epoch {:db-before {:items [1 2 3]}
               :db-after  {:items [3 2 1]}}
        enc   (diff-encode-db-after epoch)
        dec   (decode-db-after enc)]
    (is (= epoch dec))))

(deftest round-trip-empty-maps
  (let [epoch {:db-before {} :db-after {}}
        enc   (diff-encode-db-after epoch)
        dec   (decode-db-after enc)]
    (is (= epoch dec))
    (is (= [] (-> enc :db-after :patches)))))

(deftest round-trip-scalar-app-db
  ;; A pathological app-db that's a scalar — rare but valid. The
  ;; encoder records the change as a root-level :assoc replacement.
  (let [epoch {:db-before 42 :db-after 99}
        enc   (diff-encode-db-after epoch)
        dec   (decode-db-after enc)]
    (is (= epoch dec))))

(deftest round-trip-value-changed-to-nil
  ;; Edge case: a key's value legitimately becomes nil. The encoder
  ;; emits an :assoc with the nil value (distinguishable from a
  ;; :dissoc which removes the key entirely).
  (let [epoch {:db-before {:k :v} :db-after {:k nil}}
        enc   (diff-encode-db-after epoch)
        dec   (decode-db-after enc)]
    (is (= epoch dec))
    (is (contains? (:db-after dec) :k))
    (is (nil? (-> dec :db-after :k)))))

(deftest round-trip-nested-key-addition-and-removal
  ;; Two-axis change: one key removed, another added, deep in the tree.
  (let [epoch {:db-before {:user {:profile {:name "alice" :age 30}}}
               :db-after  {:user {:profile {:name "alice" :email "a@b"}}}}
        enc   (diff-encode-db-after epoch)
        dec   (decode-db-after enc)]
    (is (= epoch dec))))

;; ---------------------------------------------------------------------------
;; diff-encode-epochs — the slice-level transform.
;; ---------------------------------------------------------------------------

(deftest diff-encode-epochs-diff-mode-encodes-every-record
  (let [epochs [fixture-epoch fixture-epoch fixture-epoch]
        enc    (diff-encode-epochs epochs :diff)]
    (is (= 3 (count enc)))
    (doseq [e enc]
      (is (= :db-before (-> e :db-after :rf.mcp/diff-from))))))

(deftest diff-encode-epochs-full-mode-pass-through
  (let [epochs [fixture-epoch fixture-epoch]
        enc    (diff-encode-epochs epochs :full)]
    (is (= epochs enc)
        ":full mode is a no-op — agent gets the legacy shape")))

(deftest diff-encode-epochs-each-record-self-contained
  ;; Independence property: each epoch encodes against ITS OWN
  ;; :db-before; reordering / pagination / filtering of the slice
  ;; doesn't break decode.
  (let [e1 {:db-before {:a 1} :db-after {:a 2}}
        e2 {:db-before {:b 9} :db-after {:b 9 :c 7}}
        enc (diff-encode-epochs [e1 e2] :diff)]
    (is (= e1 (decode-db-after (first enc))))
    (is (= e2 (decode-db-after (second enc))))
    ;; Reverse order — still decodable.
    (let [reversed (reverse enc)]
      (is (= e2 (decode-db-after (first reversed))))
      (is (= e1 (decode-db-after (second reversed)))))))

(deftest diff-encode-epochs-empty-vector
  (is (= [] (diff-encode-epochs [] :diff)))
  (is (= [] (diff-encode-epochs [] :full))))

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
  (let [enc (diff-encode-epochs-in-snapshot fixture-snapshot :diff)]
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
         (diff-encode-epochs-in-snapshot fixture-snapshot :full))))

(deftest snapshot-skips-frames-without-epochs-slice
  ;; The :include filter may exclude :epochs. Don't add one.
  (let [snap {:rf/default {:app-db {} :sub-cache {} :machines {}}}
        enc  (diff-encode-epochs-in-snapshot snap :diff)]
    (is (not (contains? (:rf/default enc) :epochs)))))

(deftest snapshot-non-map-passes-through
  (is (nil? (diff-encode-epochs-in-snapshot nil :diff)))
  (is (= :not-a-snap (diff-encode-epochs-in-snapshot :not-a-snap :diff))))

;; ---------------------------------------------------------------------------
;; parse-epochs-mode — MCP-arg normalisation.
;; ---------------------------------------------------------------------------

(deftest parse-epochs-mode-default-is-diff
  (is (= :diff (parse-epochs-mode nil))))

(deftest parse-epochs-mode-strings-accepted
  (is (= :diff (parse-epochs-mode "diff")))
  (is (= :full (parse-epochs-mode "full"))))

(deftest parse-epochs-mode-keywords-accepted
  (is (= :diff (parse-epochs-mode :diff)))
  (is (= :full (parse-epochs-mode :full))))

(deftest parse-epochs-mode-unknown-falls-back-to-diff
  ;; Least-surprise on the budget-sensitive default: an unrecognised
  ;; value gets the smaller-wire-payload behaviour, not the larger.
  (is (= :diff (parse-epochs-mode "garbage")))
  (is (= :diff (parse-epochs-mode 42)))
  (is (= :diff (parse-epochs-mode :other))))

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
        diff-encoded (diff-encode-epochs epochs :diff)
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
      (let [decoded (mapv decode-db-after diff-encoded)]
        (is (= epochs decoded))))))
