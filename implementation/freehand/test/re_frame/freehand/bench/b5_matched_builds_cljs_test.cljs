(ns re-frame.freehand.bench.b5-matched-builds-cljs-test
  "B5's BUILD LANE: the three release shapes and the per-promotion byte delta
  across the matched pair.

  The evidence half of B5 ([[re-frame.freehand.bench.b5-bundle-cljs-test]])
  measures the ONE mixed `:freehand-release` bundle. This file measures all
  three shapes — `:freehand-release-interpreted`, `:freehand-release`,
  `:freehand-release-compiled` in `implementation/shadow-cljs.edn`, same
  `:advanced` and same `goog.DEBUG` false — and publishes the BYTE DELTA
  between the two MATCHED arms as a whole-app total and a per-promoted-view
  mean.

  Lowering is the only variable across the pair, and this file is where that
  is proved rather than asserted in prose. The two entries
  (`re-frame.freehand.release-app-lowered-none` and `…-lowered-full`) are one
  source in two states: drop the three `{:compiled true}` markers, rename the
  equal-length arm segment, and the sources are identical character for
  character. The census below checks exactly that on every run, no bundle
  required — because the confound the #6887 audit found was source drift
  between two hand-copied entries, and prose in a docstring cannot catch it
  coming back.

  Three things keep it honest, and the first two need no bundle on disk:

    - the matched-entry CENSUS proves the two arms differ only in lowering,
      and that neither carries a namespace docstring — `:advanced` does not
      strip one and the view manifest ships it once per declared view, which
      is how +339 bytes of prose used to ride the delta;
    - [[re-frame.freehand.bench.b5/promotion-delta]] is exercised over
      synthetic measured artefacts, so the delta arithmetic has coverage on
      every `npm run test:freehand` run, built bundles or not;
    - the three artefacts route through the SAME probe the mixed lane uses —
      [[re-frame.freehand.bench.b5/measure-bundle]] and
      [[re-frame.freehand.bench.b5/workload]] — so no new bench framework is
      introduced; the three shapes are just three inputs to it.

  The `:mixed` shape is the SHIPPED-COST artefact, built from its own entry
  with its own ids and prose. It is measured here for context; no delta is
  taken against it.

  The real bundles are measured only when all three have been built. A delta
  taken against a shape that was never built is a number about nothing, so
  that test SKIPS unless every one of the three is on disk. To publish the
  real figures:

      shadow-cljs release freehand-release-interpreted \\
                          freehand-release \\
                          freehand-release-compiled
      RF2_REVISION=$(git rev-parse HEAD) npm run test:freehand

  It states no pass/fail about any BUNDLE FIGURE. Every reading is a byte
  count or a byte delta, which the harness can only publish — D021's NON-GOALS
  bar a byte-size threshold, so no matched-build figure has a route to a
  verdict however far it moves. The census reds, but its subject is the
  fixture's construction, not its numbers: it asks whether the two entries are
  still one source in two states, which is the premise the numbers are read
  under. The gates that red on the ARTEFACT are different lanes over the same
  release build, each moving one variable and holding the rest:

    `scripts/check-freehand-reachability.cjs`      moves the APP — an unused
                                                   runtime module is absent
    `scripts/check-freehand-evidence-elision.cjs`  moves `goog.DEBUG` — the
                                                   dev-gated seam is absent
    this file                                      moves LOWERING — and
                                                   asserts nothing about the
                                                   bytes that result

  Complementary, none of them the same claim.

  Node-only (`fs`/`zlib`/`vm`). The census reads the two entry sources
  relative to `implementation/`, which is where the Node lane runs from and
  where b5 resolves its bundle paths. Like the mixed bundle test the bundle
  measurement is driven by a test rather than registered into the standing
  suite, because its subject is an artefact a standing run has no reason to
  have built.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["fs" :as fs]
            [clojure.string :as string]
            [cljs.test :refer-macros [deftest is testing]]
            [re-frame.freehand.bench :as bench]
            [re-frame.freehand.bench.b5 :as b5]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.bench.provenance :as prov]))

(def ^:private sampling {:warmup 2 :samples 8})

(def ^:private fixture-provenance
  "The revision a TEST supplies, and the artefacts' own `:advanced`,
  uninstrumented build — not the `:none` test host's. Identical posture to
  the mixed bundle test."
  {:revision "test-fixture-revision"
   :build    b5/release-build})

;; ---------------------------------------------------------------------------
;; The matched-entry census — the premise the delta is read under, checked on
;; every run against the SOURCES, no bundle required
;; ---------------------------------------------------------------------------

(def ^:private arm-entries
  "The two arms' entry sources, relative to `implementation/`."
  {:interpreted "freehand/test/re_frame/freehand/release_app_lowered_none.cljs"
   :compiled    "freehand/test/re_frame/freehand/release_app_lowered_full.cljs"})

(def ^:private arm-namespaces
  {:interpreted "re-frame.freehand.release-app-lowered-none"
   :compiled    "re-frame.freehand.release-app-lowered-full"})

(def ^:private compiled-marker "{:compiled true}")

(defn- arm-entry-file
  "One arm's entry FILE NAME. The compiled tier emits source coordinates, and
  they carry this — so it is a second string by which one arm's bundle could
  name the other."
  [arm]
  (last (string/split (get arm-entries arm) #"/")))

(defn- entry-source
  "One arm's entry source as text. Unlike a bundle, this is a checked-in file:
  it is always there, so its absence is a fault to red rather than a reason to
  skip."
  [arm]
  (.readFileSync fs (get arm-entries arm) "utf8"))

(defn- occurrences
  "How many times `needle` appears in `s` — plain string search, no pattern
  escaping to get wrong."
  [s needle]
  (dec (count (.split s needle))))

(defn- census-normalise
  "An entry source reduced to what reaches the artefact, with the two arms'
  distinguishing marks folded away:

    - `;;` comment lines and blank lines dropped. Comments are not compiled,
      so the arms' prose is free to differ and the census's subject stays the
      code;
    - the `{:compiled true}` markers dropped — the ONE difference the delta is
      a reading of;
    - the arm segment folded to a single token, in both the hyphen spelling
      (the namespace and every id derived from it) and the underscore one (the
      file path in the compiled tier's source coordinates).

  Two sources that normalise to the same string differ in lowering and in
  nothing else."
  [src]
  (->> (string/split-lines src)
       (remove #(string/starts-with? (string/triml %) ";;"))
       (remove #(= compiled-marker (string/trim %)))
       (remove string/blank?)
       (map #(string/replace % #"release[-_]app[-_]lowered[-_](?:none|full)"
                             "release-app-lowered-ARM"))
       (string/join "\n")))

(deftest the-two-arms-are-one-source-in-two-states
  (testing "The premise every figure in this file is read under. The #6887
            audit found two hand-copied 85-line entries that had already
            drifted in the variables the evidence held constant, so this is
            checked rather than promised: normalise both sources and they are
            identical character for character."
    (doseq [[arm path] arm-entries]
      (is (.existsSync fs path)
          (str "the " (name arm) " arm's entry source must be at " path
               " — the Node lane runs from implementation/ (cwd "
               (.cwd js/process) ")")))
    (let [interp   (entry-source :interpreted)
          compiled (entry-source :compiled)]
      (is (= (census-normalise interp) (census-normalise compiled))
          "the two entries differ only in lowering")
      (testing "and the equality is not vacuous: the markers really are there,
                on all three view declarations of the compiled arm and on none
                of the interpreted arm's"
        (is (= 3 (occurrences compiled compiled-marker)))
        (is (= 0 (occurrences interp compiled-marker))))
      (testing "neither arm carries a namespace DOCSTRING. :advanced does not
                strip one and the view manifest ships it once per declared
                view, so a docstring here lands three times inside the artefact
                under the probe — worth +339 bytes of a 17,905-byte delta on
                the entries this pair replaced, whose docstrings described
                different things."
        (doseq [[arm src] {:interpreted interp :compiled compiled}]
          (is (re-find #"\(ns\s+\S+\s*\r?\n\s*\(:require" src)
              (str "the " (name arm) " arm's ns form goes straight to :require"))))
      (testing "and the two namespace names are the same LENGTH, because a
                namespace's own name rides into the bundle through the ids the
                registrar keys on, the view manifest and the source
                coordinates — a longer name on one arm would weigh into the
                delta as though it were lowering"
        (let [[a b] (map #(second (re-find #"\(ns\s+(\S+)" %)) [interp compiled])]
          (is (= (get arm-namespaces :interpreted) a))
          (is (= (get arm-namespaces :compiled) b))
          (is (= (count a) (count b))))))))

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

(deftest the-delta-is-published-as-a-record-not-a-bare-map
  (testing "The delta routes through the SAME door every other B5 figure
            does. It used to be printed straight to the console as a map:
            no `:host`, no `:build`, no `:sampling`, no `:baseline`, and no
            visit to `provenance/result` — published under weaker discipline
            than the readings it was derived from. As a workload it carries
            the whole record shape, gets validated at the one door, and
            still states no verdict: every measurement it declares observes
            bytes, which the harness can only publish."
    (let [interp   (synthetic-measured "interp" 1000 400 380 300)
          compiled (synthetic-measured "compiled" 1150 460 430 350)
          w        (bench/workload
                     (b5/promotion-delta-workload
                       {:id :B5/synthetic-promotion-delta
                        :doc "the delta over two synthetic artefacts"
                        :sampling {:warmup 0 :samples 1}
                        :promoted-views 3}
                       interp compiled))
          record   (bench/run-workload w (assoc fixture-provenance :revision "r"))]
      (is (empty? (filter m/gate? (:measurements w)))
          "the delta declares no gate at all — a byte figure has no route to a verdict")
      (is (empty? (:gate-failures record)) "and reds nothing")
      (is (= :interpreted-vs-compiled (get-in record [:baseline :kind]))
          "the delta names D021's interpreted-versus-compiled comparator")
      (is (= :interpreted (get-in record [:baseline :reference :arm]))
          "measured against the interpreted arm")
      (is (= 150 (get-in record [:distribution :B5/promotion-delta-raw-bytes :p50]))
          "the whole-app raw delta rides the record")
      (is (= 50 (get-in record [:distribution :B5/promotion-delta-raw-bytes-mean-per-view :p50]))
          "and the per-view figure is that total over the promoted view count")
      (testing "the fixture states what the two artefacts hold still and what
                they do not, so the delta's attribution travels with it"
        (is (= 3 (get-in record [:fixture :promoted-views])))
        (is (= "interp" (get-in record [:fixture :interpreted-artefact])))
        (is (= "compiled" (get-in record [:fixture :compiled-artefact])))
        (is (re-find #"character for character" (get-in record [:fixture :matched-on]))
            "the matched claim travels with the record, in the terms the census checks")
        (is (re-find #"gzip and brotli" (get-in record [:fixture :not-matched-on]))
            "and so does what is left over — a caveat in a reviewer's memory is not evidence")))))

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

(deftest the-three-paths-are-distinct-and-named
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

(def ^:private promoted-views
  "How many view declarations the two arms promote together — `counter-a`,
  `counter-b` and the `app` root
  (`re-frame.freehand.release-app-lowered-none` against `…-lowered-full`,
  which adds `{:compiled true}` to all three).

  A FIXTURE PARAMETER, stated here because this is where the fixture is
  known; `b5` measures files and does not read the entries that produced
  them. It is what makes the per-view figure mean anything, and the figure
  is published as the MEAN it is. The census above pins the count from the
  other side: three markers on the compiled arm, none on the interpreted."
  3)

(deftest the-three-shapes-and-their-delta-are-measured-when-built
  (testing "The matched build lane's real reading, published when the three
            bundles are on disk. Each shape's bytes and parse/compile are
            measured off its own artefact through the shared probe, and the
            byte delta between the interpreted and compiled shapes is
            published as evidence — the cost of promoting the app's views,
            as a whole-app total and a per-view mean, with the fixture naming
            both what the two arms hold still and what they do not. When any
            shape is unbuilt this SKIPS rather than fabricate a delta between
            files that are not there."
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
          ;; The census is a claim about the SOURCES; this is the same claim
          ;; read off the artefacts. Neither arm's bundle may carry a string
          ;; naming the other arm — that is how the replaced entries' prose,
          ;; each docstring citing its sibling, used to ride the delta.
          (doseq [[arm other] [[:interpreted :compiled] [:compiled :interpreted]]]
            (let [source (get-in results [arm :measured :source])]
              (is (zero? (occurrences source (get arm-namespaces other)))
                  (str "the " (name arm) " bundle names nothing of the " (name other) " arm"))
              (is (zero? (occurrences source (arm-entry-file other)))
                  (str "nor cites its source file in a coordinate"))))
          ;; The delta is a fact about the two artefacts; it gates nothing.
          (is (number? (:raw-bytes delta)) "the per-promotion raw delta is a number")
          (is (= (:raw-bytes delta) (- (:raw-bytes compiled) (:raw-bytes interp)))
              "and it is exactly compiled-minus-interpreted over the measured bytes")
          (is (= (:sha256 interp) (:interpreted-sha256 delta))
              "the delta is tied to the interpreted bundle it was taken over")
          (is (= (:sha256 compiled) (:compiled-sha256 delta))
              "and to the compiled bundle")
          ;; And the delta is published the way every other B5 figure is —
          ;; through the workload harness and out the ONE provenance door,
          ;; not console-logged as a bare map beside the records it derives
          ;; from. Without a revision there is nothing to publish and nothing
          ;; is invented; the three shapes' bytes above are still true.
          (if-not revision
            (js/console.log
              (str ";; B5 per-promotion byte delta — NOT CITABLE (this run cannot name "
                   "the source revision; set RF2_REVISION, or run in CI). Byte facts "
                   "only: no result record was built.\n"
                   (pr-str (assoc delta :promoted-views promoted-views
                                        :mixed-sha256 (:sha256 mixed)))))
            (publish! "B5 per-promotion byte delta (compiled minus interpreted)"
                      (bench/run-workload
                        (b5/promotion-delta-workload
                          {:id             :B5/promotion-delta
                           :doc            (str "the byte cost of promoting the release app's "
                                                promoted-views " views, interpreted shape to "
                                                "compiled shape")
                           :sampling       {:warmup 0 :samples 1}
                           :promoted-views promoted-views}
                          interp compiled)
                        (assoc fixture-provenance :revision revision)))))))))
