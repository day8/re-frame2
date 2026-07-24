(ns re-frame.freehand.bench.b5-bundle-cljs-test
  "B5's shipped-cost readings, and the proof they are readings of the
  artefact rather than of the process taking them.

  [[re-frame.freehand.bench.b5]] measures the release bundle's bytes and
  its parse/compile time. Two things make that honest, and both are tested
  here without needing the bundle on disk:

    - the byte and digest functions are exercised over a synthetic source,
      so the measurement machinery has coverage on every `npm run
      test:freehand` run, built bundle or not;
    - the workload it produces routes through the standing harness, and
      the record's `:build` is the ARTEFACT's (`:advanced`, uninstrumented)
      supplied as an override — not the Node test process's detected
      `:none`, which would be a provenance lie about what was measured.

  The real artefact is measured only when it has been built. A B5 number
  taken against a bundle that does not exist is a number about nothing, so
  that test SKIPS when `out/freehand-release/main.js` is absent — the same
  posture the DOM benches keep when there is no browser. To publish the
  real figures:

      npm run build:freehand-release
      RF2_REVISION=$(git rev-parse HEAD) npm run test:freehand

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.freehand.bench :as bench]
            [re-frame.freehand.bench.b5 :as b5]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.bench.provenance :as prov]))

;; ---------------------------------------------------------------------------
;; A synthetic artefact — enough repeated, valid JS to be genuinely
;; compressible, so the encodings really do come out smaller than raw
;; ---------------------------------------------------------------------------

(def ^:private synthetic-source
  (str/join "\n"
            (repeat 400
                    "function bench_leaf(a, b){ return (a + b) * 2 - 1; } bench_leaf;")))

(def ^:private synthetic-buf (js/Buffer.from synthetic-source "utf8"))

(def ^:private sampling {:warmup 2 :samples 8})

(def ^:private fixture-provenance
  "The revision a TEST supplies. A ClojureScript test process is not the
  build pipeline that produced the artefact, so it cannot truthfully
  detect the revision the bundle was built from; a published run sets
  `RF2_REVISION`. The `:build` is the artefact's own, because the thing
  measured is the `:advanced` release bundle, not this `:none` test host."
  {:revision "test-fixture-revision"
   :build    b5/release-build})

;; ---------------------------------------------------------------------------
;; The byte and digest readings
;; ---------------------------------------------------------------------------

(deftest byte-readings-are-real-and-ordered
  (testing "The three encodings measure the synthetic artefact, and a
            compressible source really does shrink: gzip and brotli come
            out under raw, maximum gzip is no larger than default gzip, and
            brotli is the smallest — the ordering a bundle this shape has."
    (let [raw    (b5/raw-bytes synthetic-buf)
          gzip6  (b5/gzip-bytes synthetic-buf 6)
          gzip9  (b5/gzip-bytes synthetic-buf 9)
          brotli (b5/brotli-bytes synthetic-buf)]
      (is (= (.-length synthetic-buf) raw) "raw bytes are the artefact's byte length")
      (is (pos? raw) "the synthetic artefact is non-empty")
      (is (< gzip6 raw) "gzip level 6 shrinks a compressible artefact")
      (is (<= gzip9 gzip6) "maximum gzip is no larger than default gzip")
      (is (< brotli raw) "brotli shrinks it too")
      (is (<= brotli gzip9) "and brotli is the smallest of the three, for a bundle this shape"))))

(deftest sha256-is-a-stable-digest-of-the-bytes
  (testing "The digest that ties a figure to its artefact is a 64-hex
            SHA-256, the same on the same bytes and different on different
            bytes — so a rebuild that changed the bundle changes the digest
            the numbers were filed under."
    (let [d (b5/sha256 synthetic-buf)]
      (is (= 64 (count d)) "SHA-256 is 64 hex characters")
      (is (re-matches #"[0-9a-f]{64}" d) "lower-case hex")
      (is (= d (b5/sha256 synthetic-buf)) "stable on the same bytes")
      (is (not= d (b5/sha256 (js/Buffer.from (str synthetic-source "x") "utf8")))
          "and different on different bytes"))))

(deftest parse-compile-is-a-nonnegative-duration
  (testing "The parse/compile reading is a wall-time duration — a
            non-negative number, taken with a fresh vm.Script so nothing a
            previous compile cached is billed to it. It gates nothing; it
            is only asserted to have been observable."
    (let [ms (b5/parse-compile-ms synthetic-source)]
      (is (number? ms) "parse/compile answered a number")
      (is (not (neg? ms)) "and a non-negative one"))))

;; ---------------------------------------------------------------------------
;; The workload routes through the standing harness
;; ---------------------------------------------------------------------------

(defn- synthetic-measured []
  {:path         "synthetic"
   :sha256       (b5/sha256 synthetic-buf)
   :raw-bytes    (b5/raw-bytes synthetic-buf)
   :gzip6-bytes  (b5/gzip-bytes synthetic-buf 6)
   :gzip9-bytes  (b5/gzip-bytes synthetic-buf 9)
   :brotli-bytes (b5/brotli-bytes synthetic-buf)
   :source       synthetic-source})

(deftest the-workload-is-all-evidence-no-gate
  (testing "B5 states no pass/fail about a bundle — the deterministic
            reachability gate is the build lane's. Every measurement it
            declares observes bytes or a duration, so every one classes as
            evidence and none can gate; the harness decides that from the
            observable, and a byte figure dressed as a gate would be
            refused at construction rather than here."
    (let [w  (bench/workload (b5/workload {:id       :B5/synthetic
                                           :doc      "a synthetic compressible artefact"
                                           :sampling sampling}
                                          (synthetic-measured)))
          by (into {} (map (juxt :id identity)) (:measurements w))]
      (doseq [mid [:B5/raw-bytes :B5/gzip6-bytes :B5/gzip9-bytes
                   :B5/brotli-bytes :B5/parse-compile-ms]]
        (is (m/evidence? (get by mid)) (str mid " is evidence")))
      (is (empty? (filter m/gate? (:measurements w)))
          "the workload declares no gate at all"))))

(deftest the-record-names-the-artefacts-build-not-the-measurers
  (testing "A B5 record must describe the `:advanced` artefact it measured,
            not the `:none` instrumented Node process that measured it. The
            build override carries the artefact's own build mode through to
            the record; the digest and byte facts ride the fixture so each
            figure is tied to the exact bytes; and the run reds nothing,
            because it declared nothing that could."
    (let [w      (b5/workload {:id       :B5/synthetic
                               :doc      "a synthetic compressible artefact"
                               :sampling sampling}
                              (synthetic-measured))
          record (bench/run-workload w fixture-provenance)]
      (is (empty? (:gate-failures record)) "no deterministic property was violated")
      (is (= :advanced (get-in record [:build :optimizations]))
          "the record carries the ARTEFACT's optimization level, not the measurer's")
      (is (false? (get-in record [:build :instrumentation?]))
          "and the artefact is uninstrumented, as a shipped bundle is")
      (is (= "test-fixture-revision" (:revision record)) "the supplied revision rides the record")
      (is (= (b5/sha256 synthetic-buf) (get-in record [:fixture :sha256]))
          "the digest ties the figures to the exact bytes measured")
      (testing "the byte figures are published as degenerate distributions — a
                file's size does not vary across samples — and parse/compile as
                a real one"
        (doseq [mid [:B5/raw-bytes :B5/gzip6-bytes :B5/gzip9-bytes :B5/brotli-bytes]]
          (let [d (get-in record [:distribution mid])]
            (is (some? d) (str mid " was published"))
            (is (= (:min d) (:max d)) (str mid " is constant across samples"))
            (is (not (contains? d :pass?)) (str mid " carries no verdict — it is evidence"))))
        (let [d (get-in record [:distribution :B5/parse-compile-ms])]
          (is (= (:samples sampling) (:n d)) "parse/compile summarises every measured sample")
          (doseq [k [:p50 :p95 :p99 :min :max :mean]]
            (is (number? (get d k)) (str "parse/compile carries " k))))))))

;; ---------------------------------------------------------------------------
;; The real artefact, when it has been built
;; ---------------------------------------------------------------------------

(defn- publish!
  "Emit the real B5 record. A citable record — one that named every
  provenance field — goes through `provenance/result`, the ONE documented
  emission door, which validates it; an unattributable one is worth
  reading and not worth citing, and the line says which."
  [record]
  (let [ds (prov/defects record)]
    (if (seq ds)
      (js/console.log (str ";; B5 shipped-cost evidence — NOT CITABLE ("
                           (count ds) " provenance field(s) unnamed by this run)\n"
                           (pr-str record)))
      (js/console.log (str ";; B5 shipped-cost evidence — citable\n"
                           (pr-str (prov/result record)))))))

(deftest the-release-bundle-is-measured-when-it-has-been-built
  (testing "The real artefact's shipped cost, measured off
            out/freehand-release/main.js when it is on disk. The encodings
            shrink it, the digest is a fact about it, and the record is
            emitted through the provenance door — the same reading the
            build lane publishes as the substrate's shipped cost. When the
            bundle has not been built, this SKIPS rather than fabricate a
            measurement of a file that is not there."
    (if-not (b5/bundle-present? b5/default-bundle-path)
      (is true (str "no release bundle on disk — run `npm run build:freehand-release` first; "
                    "skipping the real-artefact measurement"))
      (let [measured (b5/measure-bundle b5/default-bundle-path)
            w        (b5/workload {:id       :B5/freehand-release
                                   :doc      "the shipped Freehand release bundle"
                                   :sampling sampling}
                                  measured)
            record   (bench/run-workload w (assoc fixture-provenance
                                                  :revision (or (prov/detect-revision)
                                                                "unattributed-local-build")))]
        (is (< (:gzip6-bytes measured) (:raw-bytes measured))
            "the shipped bundle compresses under gzip")
        (is (<= (:brotli-bytes measured) (:gzip9-bytes measured))
            "and brotli is at least as small as maximum gzip")
        (is (= 64 (count (:sha256 measured))) "the artefact has a digest")
        (is (empty? (:gate-failures record)) "the measurement reds nothing — it is all evidence")
        (is (= :advanced (get-in record [:build :optimizations]))
            "the record describes the advanced artefact")
        ;; Method correctness, NOT a performance budget: a genuine cold parse
        ;; of a multi-hundred-KB bundle is milliseconds; a V8 compilation-cache
        ;; HIT is microseconds. This loose floor sits ~an order of magnitude
        ;; below the real reading and ~25x above a cache hit, so it reds only
        ;; if the nonce that defeats the cache were removed and the figure
        ;; collapsed to a lookup — the exact regression the audit named. It
        ;; does not, and cannot, red a release for being slow.
        (is (> (get-in record [:distribution :B5/parse-compile-ms :p50]) 1.0)
            "parse/compile is a real cold compile, not a compilation-cache lookup")
        (publish! record)))))
