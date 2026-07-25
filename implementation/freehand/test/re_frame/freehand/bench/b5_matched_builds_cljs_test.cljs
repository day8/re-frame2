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

  Five things keep it honest, and only the last needs a bundle on disk:

    - the matched-entry CENSUS proves the two arms differ only in lowering,
      and that neither carries a namespace docstring — `:advanced` does not
      strip one and the view manifest ships it once per declared view, which
      is how +339 bytes of prose used to ride the delta;
    - the BUILD-MAP census proves the other half of the same premise. The two
      arms' shadow build maps are hand-copied neighbours, and moving one arm's
      `:target`, `:optimizations`, `:infer-externs` or `goog.DEBUG` would leave
      the source census green while turning the delta into a reading of a
      compiler flag. One structural equality over the two maps, excluding only
      `:output-dir` and the module `:init-fn`, closes that (#6909 audit);
    - [[re-frame.freehand.bench.b5/promotion-delta]] is exercised over
      synthetic measured artefacts, so the delta arithmetic has coverage on
      every `npm run test:freehand` run, built bundles or not;
    - the three artefacts route through the SAME probe the mixed lane uses —
      [[re-frame.freehand.bench.b5/measure-bundle]] and
      [[re-frame.freehand.bench.b5/workload]] — so no new bench framework is
      introduced; the three shapes are just three inputs to it;
    - the IDENTITY CONTROL proves the raw delta owes nothing to the fixture's
      own naming: rename the arms' identity token to any other of the same
      length in both artefacts and the raw delta comes back bit-identical
      ([[re-frame.freehand.bench.b5/canonicalise-arm-identity]]). Asserted for
      RAW only, because for the compressed encodings it is measurably false —
      `b5/causal-isolation` carries how false, per encoding.

  ## Two matched arms, and one representative mixed bundle

  The `:mixed` shape is the SHIPPED-COST artefact, built from its own entry
  with its own ids and prose. It is measured here for context; no delta is
  taken against it, and it is deliberately NOT byte-matched to the pair.

  D021's B5 row asks for `representative interpreted, compiled, and mixed
  production bundles` and a `per-promoted-view delta`. A delta needs two arms
  held still against each other; it never needed three. So the pair carries the
  delta and the mixed shape carries the headline shipped cost — which is the
  thing a consumer actually pays, and it is a real small app rather than a
  fixture built to be subtractable. Folding the mixed entry into the matched
  shape would buy a middle reading nothing asks for at the cost of rewriting
  the four surfaces that name `re-frame.freehand.release-app` by sentinel
  (`check-freehand-reachability.cjs`, `check-freehand-evidence-elision.cjs`,
  `b5-reachability-control-app`, `_changed-surfaces.test.cjs`). Recorded on
  `rf2-rhg4q`; `interpreted < mixed < compiled` is NOT a lowering series and
  nothing here reads it as one.

  The real bundles are measured only when all three have been built. A delta
  taken against a shape that was never built is a number about nothing, so
  that test SKIPS unless every one of the three is on disk. To publish the
  real figures:

      npm run build:freehand-matched
      RF2_REVISION=$(git rev-parse HEAD) npm run test:freehand

  The npm script rather than a bare `shadow-cljs release` over the three ids,
  because it clears the three shapes' build caches and output dirs first. That
  is not housekeeping: a build that compiles fresh alongside a CACHE HIT gets
  different Closure renaming and lands thousands of bytes from where the same
  build lands with the caches cleared, so an unpinned posture publishes two
  different deltas at one revision. See
  [[re-frame.freehand.bench.b5/matched-build-posture]], which rides the
  published fixture so a delta is never cited without its posture.

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
            [clojure.edn :as edn]
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

(defn- code-lines
  "An entry source's CODE: `;;` comment lines and blank lines dropped.
  Comments are not compiled, so each arm's prose is free to describe its own
  arm — including quoting the `{:compiled true}` marker — while the census's
  subject stays what reaches the artefact."
  [src]
  (->> (string/split-lines src)
       (remove #(string/starts-with? (string/triml %) ";;"))
       (remove string/blank?)))

(defn- census-normalise
  "An entry source's code with the two arms' distinguishing marks folded away:

    - the `{:compiled true}` markers dropped — the ONE difference the delta is
      a reading of;
    - the arm segment folded to a single token, in both the hyphen spelling
      (the namespace and every id derived from it) and the underscore one (the
      file path in the compiled tier's source coordinates).

  Two sources that normalise to the same string differ in lowering and in
  nothing else."
  [src]
  (->> (code-lines src)
       (remove #(= compiled-marker (string/trim %)))
       (map #(string/replace % #"release[-_]app[-_]lowered[-_](?:none|full)"
                             "release-app-lowered-ARM"))
       (string/join "\n")))

(defn- marker-count
  "How many view declarations in this source carry `{:compiled true}` — in the
  CODE, not in the prose describing it."
  [src]
  (count (filter #(= compiled-marker (string/trim %)) (code-lines src))))

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
        (is (= 3 (marker-count compiled)))
        (is (= 0 (marker-count interp))))
      (testing "neither arm carries a namespace DOCSTRING. :advanced does not
                strip one and the view manifest ships it once per declared
                view, so a docstring here lands three times inside the artefact
                under the probe — worth +339 bytes of the 17,905-byte delta
                read at d5794fded8 on the entries this pair replaced, whose
                docstrings described different things. That denominator is a
                past reading at a named revision, not a live figure."
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
        (let [left-over (get-in record [:fixture :not-matched-on])]
          (is (re-find #"differ in CONTENT" left-over)
              "and so does what is left over — a caveat in a reviewer's memory is not evidence")
          (is (re-find #"causal-isolation states by how much" left-over)
              "and it hands the reader on to the per-encoding measurement rather than waving at it")))
      (testing "and it says WHICH encodings the lowering attribution holds for.
                The #6909 audit's finding was that the source claim said `only
                lowering` while the compressed deltas measurably carried the
                arms' identity content; the repair is not a softer adjective but
                a per-encoding statement that rides every record."
        (let [ci (get-in record [:fixture :causal-isolation])]
          (is (re-find #"EXACTLY for the raw delta" ci)
              "raw is the one figure presented as lowering and nothing else")
          (is (re-find #"identity-sensitive" ci)
              "and brotli is named for what it is")
          (is (re-find #"babc7fb540" ci)
              "with the revision the percentages were read at — an unstamped absolute goes stale silently")))
      (testing "and it names the BUILD POSTURE the two artefacts were produced
                under. :advanced is held still as a setting by the two build
                definitions; the posture is what holds it still as an outcome,
                because a build compiling fresh beside a cache HIT gets
                different Closure renaming and lands thousands of bytes away.
                Without this the same revision publishes two different deltas
                and nothing in the record tells them apart."
        (let [posture (get-in record [:fixture :build-posture])]
          (is (re-find #"build:freehand-matched" posture)
              "the posture names the one entry point that produces it")
          (is (re-find #"\.shadow-cljs/builds/" posture)
              "and the cache it clears to produce it")
          (is (re-find #"4,075 raw bytes" posture)
              "and what deviating from it was measured to cost"))))))

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
;; The identity control — no bundle required for the substitution's own law
;; ---------------------------------------------------------------------------

(defn- utf8-bytes
  "How many BYTES `s` occupies as UTF-8 — the unit `b5/measure-bundle`'s
  `:raw-bytes` is in.

  Not `count`, which answers UTF-16 code units. The bundles carry non-ASCII
  characters in their string literals, so the two differ: at `babc7fb540` the
  raw delta is 17,373 bytes and 17,363 code units. Comparing an invariance in
  one unit against a delta in the other fails by exactly that gap, which reads
  like a broken invariant rather than a mismatched ruler."
  [s]
  (.byteLength js/Buffer s "utf8"))

(deftest canonicalising-the-arm-identity-is-byte-neutral-per-arm
  (testing "The substitution the raw delta's causal isolation rests on. Each
            arm's identity token is twelve characters in both spellings, and
            the probe tokens are four, so replacing one with another cannot
            change a single arm's byte count however many times it occurs — and
            therefore cannot change the DELTA between two arms either. Asserted
            here on strings so the law is covered on every run, and asserted on
            the real artefacts below when they exist."
    (doseq [token b5/arm-identity-probe-tokens]
      (is (= 4 (count token)) "every probe token is the length of the none/full it replaces")
      (doseq [original ["lowered-none" "lowered_none" "lowered-full" "lowered_full"]]
        (let [s (str "a:" original "/bump b:" original ".cljs c:" original)
              c (b5/canonicalise-arm-identity s token)]
          (is (= (utf8-bytes s) (utf8-bytes c))
              (str "substituting " token " into " original " preserves byte length"))
          (is (not (string/includes? c original))
              "and leaves no occurrence of the arm's own token behind")
          (is (= 3 (dec (count (.split c (str "lowered" (subs original 7 8) token)))))
              "every occurrence is substituted, not just the first"))))
    (testing "and the two arms canonicalise to the SAME string — which is the
              whole point: a reading taken over the result cannot tell which
              arm produced it"
      (let [i (b5/canonicalise-arm-identity "id:lowered-none/bump src:lowered_none.cljs" "arm0")
            c (b5/canonicalise-arm-identity "id:lowered-full/bump src:lowered_full.cljs" "arm0")]
        (is (= i c))))))

;; ---------------------------------------------------------------------------
;; The BUILD MAPS — the other half of the matched premise, censused
;; ---------------------------------------------------------------------------

(defn- shadow-config
  "`implementation/shadow-cljs.edn` as data, read from the directory the Node
  lane runs in.

  The file carries build-time reader tags (`#shadow/env`), which are shadow's
  to resolve and none of this census's business — `:default` folds each to its
  literal argument so the read succeeds without this test knowing what any tag
  means. Through the `opts` arity deliberately, NOT
  `cljs.reader/register-default-tag-parser!`: that mutates a global the whole
  suite shares, and a sibling bench test asserts that an unregistered tag still
  THROWS for a plain reader. Registering one here made that test pass
  vacuously."
  []
  (edn/read-string {:default (fn [_tag value] value)}
                   (.readFileSync fs "shadow-cljs.edn" "utf8")))

(def ^:private arm-build-ids
  {:interpreted :freehand-release-interpreted
   :compiled    :freehand-release-compiled})

(deftest the-two-arm-build-maps-differ-only-in-output-dir-and-entry
  (testing "The census above proves the two ENTRY SOURCES differ only in
            lowering. This proves the other half of the same premise, which
            that census cannot see: that the two BUILD MAPS agree. They are
            hand-copied neighbours in shadow-cljs.edn, and the #6909 audit
            noted that moving one arm's :target, :optimizations,
            :infer-externs or goog.DEBUG would leave every other check in this
            file green while destroying acceptance #1 — the delta would then
            be a reading of a compiler flag wearing lowering's name.

            One structural equality, excluding exactly the two slots that MUST
            differ: :output-dir (each arm needs its own) and the module's
            :init-fn (which names the arm's entry, the variable itself)."
    (let [builds (:builds (shadow-config))
          arm    #(get builds (get arm-build-ids %))
          [i c]  [(arm :interpreted) (arm :compiled)]]
      (is (map? i) "the interpreted arm's build is declared")
      (is (map? c) "the compiled arm's build is declared")
      (is (= (dissoc i :output-dir :modules) (dissoc c :output-dir :modules))
          (str "the two arm builds agree on everything but :output-dir and the "
               "entry; they differ in " (pr-str (->> (dissoc i :output-dir :modules)
                                                     (remove (fn [[k v]] (= v (get c k))))
                                                     (map first)))))
      (is (= [:main] (keys (:modules i))) "one module each")
      (is (= (keys (:modules i)) (keys (:modules c))))
      (is (= (dissoc (get-in i [:modules :main]) :init-fn)
             (dissoc (get-in c [:modules :main]) :init-fn))
          "and the module maps agree on everything but the entry fn")
      (testing "and the equality is not vacuous — the settings the delta's
                comparability actually rests on are present and are the ones a
                release build is meant to have"
        (is (= :browser (:target i)))
        (is (= :advanced (get-in i [:compiler-options :optimizations])))
        (is (false? (get-in i [:compiler-options :closure-defines 'goog.DEBUG]))
            "goog.DEBUG is pinned false rather than left to release's default")
        (is (not= (:output-dir i) (:output-dir c)) "each arm has its own output dir")
        (is (not= (get-in i [:modules :main :init-fn])
                  (get-in c [:modules :main :init-fn]))
            "and its own entry — the one variable")))))

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
          ;; And the RAW delta's causal isolation, proved on the artefacts
          ;; rather than inferred from the sources. The two arms are two
          ;; namespaces, so their ids, manifests and source coordinates differ
          ;; in identity CONTENT. Rename that token to any other of the same
          ;; length in BOTH bundles and the raw delta must come back
          ;; bit-identical — which is what earns the raw figure the right to be
          ;; presented as lowering and nothing else. It is not asserted for the
          ;; compressed encodings, because it is measurably false there:
          ;; b5/causal-isolation carries the measured size per encoding.
          (doseq [token b5/arm-identity-probe-tokens]
            (let [ci (b5/canonicalise-arm-identity (:source interp) token)
                  cc (b5/canonicalise-arm-identity (:source compiled) token)]
              (is (= (:raw-bytes delta) (- (utf8-bytes cc) (utf8-bytes ci)))
                  (str "the raw delta is invariant under renaming the arm identity to "
                       token " — so no part of it is the fixture's own naming"))))
          ;; Non-vacuous: the token really is in both artefacts. The counts are
          ;; deliberately NOT compared — the compiled arm ships more, and the
          ;; extra are its own source coordinates, which exist because the
          ;; views were lowered and so belong in the delta.
          (doseq [[arm src] {:interpreted (:source interp) :compiled (:source compiled)}]
            (is (pos? (count (re-seq b5/arm-identity-pattern src)))
                (str "the " (name arm) " bundle really does carry its arm identity — "
                     "an invariance over zero occurrences would prove nothing")))
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
