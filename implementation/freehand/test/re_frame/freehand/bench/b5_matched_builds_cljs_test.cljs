(ns re-frame.freehand.bench.b5-matched-builds-cljs-test
  "B5's BUILD LANE: the three matched release shapes and the per-promotion
  byte delta between them.

  The evidence half of B5 ([[re-frame.freehand.bench.b5-bundle-cljs-test]])
  measures the ONE mixed `:freehand-release` bundle. This file measures all
  THREE matched shapes — interpreted-only, mixed, compiled-only — that differ
  ONLY in view lowering (`:freehand-release-interpreted`, `:freehand-release`,
  `:freehand-release-compiled` in `implementation/shadow-cljs.edn`: same entry
  app, same `:advanced`, same `goog.DEBUG` false), and publishes the
  per-promotion BYTE DELTA between the interpreted and compiled shapes.

  Two things keep it honest, and both are tested here without any bundle on
  disk:

    - [[re-frame.freehand.bench.b5/promotion-delta]] is exercised over
      synthetic measured artefacts, so the delta arithmetic has coverage on
      every `npm run test:freehand` run, built bundles or not;
    - the three artefacts route through the SAME probe the mixed lane uses —
      [[re-frame.freehand.bench.b5/measure-bundle]] and
      [[re-frame.freehand.bench.b5/workload]] — so no new bench framework is
      introduced; the matched shapes are just three inputs to it.

  The real bundles are measured only when all three have been built. A delta
  taken against a shape that was never built is a number about nothing, so
  that test SKIPS unless every one of the three is on disk. To publish the
  real figures:

      shadow-cljs release freehand-release-interpreted \\
                          freehand-release \\
                          freehand-release-compiled
      RF2_REVISION=$(git rev-parse HEAD) npm run test:freehand

  It states no pass/fail about any bundle. Every reading is a byte count or a
  byte delta, which the harness can only publish — D021's NON-GOALS bar a
  byte-size threshold, so no matched-build figure has a route to a verdict.
  The DETERMINISTIC reachability gate that DOES red is a different lane
  (`scripts/check-freehand-evidence-elision.cjs`, over the goog.DEBUG control
  pair): it flips the flag and holds lowering; this file flips lowering and
  holds the flag. Complementary, not the same.

  Node-only (`fs`/`zlib`/`vm` via b5). Like the mixed bundle test it is
  driven by a test rather than registered into the standing suite, because
  its subject is an artefact a standing run has no reason to have built.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.freehand.bench :as bench]
            [re-frame.freehand.bench.b5 :as b5]
            [re-frame.freehand.bench.provenance :as prov]))

(def ^:private sampling {:warmup 2 :samples 8})

(def ^:private fixture-provenance
  "The revision a TEST supplies, and the artefacts' own `:advanced`,
  uninstrumented build — not the `:none` test host's. Identical posture to
  the mixed bundle test."
  {:revision "test-fixture-revision"
   :build    b5/release-build})

;; ---------------------------------------------------------------------------
;; The delta arithmetic — covered on every run, no bundle required
;; ---------------------------------------------------------------------------

(defn- synthetic-measured
  "A [[b5/measure-bundle]]-shaped map with the byte figures pinned, so the
  delta arithmetic can be asserted against numbers a reader can add up."
  [tag raw g6 g9 br]
  {:path         tag
   :sha256       (str tag "-sha")
   :raw-bytes    raw
   :gzip6-bytes  g6
   :gzip9-bytes  g9
   :brotli-bytes br
   :source       ""})

(deftest promotion-delta-is-compiled-minus-interpreted-per-encoding
  (testing "The per-promotion delta subtracts the interpreted shape's bytes
            from the compiled shape's, encoding by encoding — a positive
            number means the compiled bundle is larger on the wire. It ties
            each side to its digest so the delta is a fact about the exact two
            artefacts."
    (let [interp   (synthetic-measured "interp" 1000 400 380 300)
          compiled (synthetic-measured "compiled" 1150 460 430 350)
          d        (b5/promotion-delta interp compiled)]
      (is (= 150 (:raw-bytes d))    "raw delta is compiled minus interpreted")
      (is (= 60  (:gzip6-bytes d))  "gzip-6 delta")
      (is (= 50  (:gzip9-bytes d))  "gzip-9 delta")
      (is (= 50  (:brotli-bytes d)) "brotli delta")
      (is (= "interp-sha"   (:interpreted-sha256 d)) "the interpreted side's digest rides the delta")
      (is (= "compiled-sha" (:compiled-sha256 d))    "and the compiled side's"))))

(deftest promotion-delta-can-be-negative-when-promotion-shrinks
  (testing "The delta is evidence, not a threshold: when the compiled shape
            is SMALLER, the delta is negative and nothing red fires. The
            arithmetic simply reports which way it fell."
    (let [interp   (synthetic-measured "i" 2000 800 760 600)
          compiled (synthetic-measured "c" 1900 770 740 590)
          d        (b5/promotion-delta interp compiled)]
      (is (= -100 (:raw-bytes d)) "a shrinking promotion answers a negative raw delta")
      (is (neg? (:gzip6-bytes d)) "and a negative gzip delta")
      (is (neg? (:brotli-bytes d)) "and a negative brotli delta"))))

(deftest the-three-matched-paths-are-distinct-and-named
  (testing "The build lane names three shapes keyed by lowering, all
            distinct, each landing in its own advanced output dir."
    (let [paths b5/matched-bundle-paths]
      (is (= #{:interpreted :mixed :compiled} (set (keys paths)))
          "the trio is keyed by lowering")
      (is (= 3 (count (set (vals paths)))) "the three artefacts are distinct files")
      (is (= b5/default-bundle-path (:mixed paths))
          "the mixed shape is the existing :freehand-release bundle"))))

;; ---------------------------------------------------------------------------
;; The real matched artefacts, when all three have been built
;; ---------------------------------------------------------------------------

(defn- publish!
  "Emit an EDN evidence line, marked citable only when every provenance
  field was named — the same door the mixed bundle test publishes through."
  [label record]
  (let [ds (prov/defects record)]
    (js/console.log
      (str ";; " label " — "
           (if (seq ds)
             (str "NOT CITABLE (" (count ds) " provenance field(s) unnamed by this run)")
             "citable")
           "\n" (pr-str (if (seq ds) record (prov/result record)))))))

(defn- measure-and-record
  "Measure one shape's bundle and, when this run can name the revision it
  was built from, run it through the SAME B5 workload the mixed lane uses.

  `revision` may be nil — a local run with no `RF2_REVISION` and no
  `GITHUB_SHA` genuinely does not know which commit produced the bundles on
  disk. No record is built in that case and none is faked: the provenance
  door promises no default that invents a field the run did not observe, and
  a placeholder string reads to `defects` as a named revision, which is
  precisely how an unattributable measurement comes to look citable. The
  bytes are true about the files either way, so they are always answered."
  [shape-key path revision]
  (let [measured (b5/measure-bundle path)]
    (cond-> {:measured measured}
      revision
      (assoc :record
             (bench/run-workload
               (b5/workload {:id       (keyword "B5" (str "freehand-release-" (name shape-key)))
                             :doc      (str "the " (name shape-key)
                                            "-shape Freehand release bundle")
                             :sampling sampling}
                            measured)
               (assoc fixture-provenance :revision revision))))))

(deftest the-three-shapes-and-their-delta-are-measured-when-built
  (testing "The matched build lane's real reading, published when the three
            bundles are on disk. Each shape's bytes and parse/compile are
            measured off its own artefact through the shared probe, and the
            per-promotion delta between the interpreted and compiled shapes is
            published as evidence — the byte cost of promoting the app's
            views, attributable to lowering because that is the only thing
            that differs across the three. When any shape is unbuilt this
            SKIPS rather than fabricate a delta between files that are not
            there."
    (let [present (into {} (filter (comp b5/bundle-present? val)) b5/matched-bundle-paths)]
      (if-not (= 3 (count present))
        (is true (str "not all three matched bundles are on disk — build "
                      "freehand-release-interpreted, freehand-release and "
                      "freehand-release-compiled first; skipping the matched "
                      "build-lane measurement (present: " (keys present) ")"))
        (let [revision (prov/detect-revision)
              results  (into {}
                             (map (fn [[k p]] [k (measure-and-record k p revision)]))
                             b5/matched-bundle-paths)
              interp   (get-in results [:interpreted :measured])
              compiled (get-in results [:compiled :measured])
              mixed    (get-in results [:mixed :measured])
              delta    (b5/promotion-delta interp compiled)]
          ;; Every shape's own readings are honest facts about its file. The
          ;; RECORD claims are made only when the run could name a revision;
          ;; without one there is nothing to publish and nothing is invented.
          (doseq [[k {:keys [measured record]}] results]
            (is (< (:gzip6-bytes measured) (:raw-bytes measured))
                (str (name k) " shape compresses under gzip"))
            (is (= 64 (count (:sha256 measured))) (str (name k) " shape has a digest"))
            (when record
              (is (empty? (:gate-failures record))
                  (str (name k) " shape reds nothing — it is all evidence"))
              (is (= :advanced (get-in record [:build :optimizations]))
                  (str (name k) " shape is an advanced artefact"))
              (publish! (str "B5 matched shape — " (name k)) record)))
          ;; The three digests are distinct: three different bundles.
          (is (= 3 (count (set (map (comp :sha256 :measured val) results))))
              "the three shapes are three distinct artefacts")
          ;; The delta is a fact about the two artefacts; it gates nothing.
          (is (number? (:raw-bytes delta)) "the per-promotion raw delta is a number")
          (is (= (:raw-bytes delta) (- (:raw-bytes compiled) (:raw-bytes interp)))
              "and it is exactly compiled-minus-interpreted over the measured bytes")
          (is (= (:sha256 interp) (:interpreted-sha256 delta))
              "the delta is tied to the interpreted bundle it was taken over")
          (is (= (:sha256 compiled) (:compiled-sha256 delta))
              "and to the compiled bundle")
          (js/console.log
            (str ";; B5 per-promotion byte delta (compiled minus interpreted) — "
                 (if revision
                   "evidence, no threshold"
                   (str "NOT CITABLE (this run cannot name the source revision; set "
                        "RF2_REVISION, or run in CI)"))
                 "\n"
                 (pr-str {:revision      revision
                          :interpreted   {:raw    (:raw-bytes interp)
                                          :gzip6  (:gzip6-bytes interp)
                                          :brotli (:brotli-bytes interp)
                                          :sha256 (:sha256 interp)}
                          :mixed         {:raw    (:raw-bytes mixed)
                                          :gzip6  (:gzip6-bytes mixed)
                                          :brotli (:brotli-bytes mixed)
                                          :sha256 (:sha256 mixed)}
                          :compiled      {:raw    (:raw-bytes compiled)
                                          :gzip6  (:gzip6-bytes compiled)
                                          :brotli (:brotli-bytes compiled)
                                          :sha256 (:sha256 compiled)}
                          :promotion-delta delta}))))))))
