(ns re-frame.freehand.bench.b5
  "B5 — the substrate's SHIPPED cost, measured off the release artefact.

  D021's fifth required workload: what the Freehand substrate costs a page
  that ships it — bundle bytes and the engine time to parse and compile
  the script before any of it runs. Unlike B1–B4, whose subject is code
  executing in the measuring process, B5's subject is a FILE on disk: the
  `:advanced` release bundle `out/freehand-release/main.js`, built the way
  a consumer ships it (`npm run build:freehand-release`,
  `re-frame.freehand.release-app` — a real small app, not a require-only
  stub that would DCE to a cost no consumer pays).

  ## What this measures, and what it deliberately does not

    - **bytes** — the artefact's on-disk size and its size under gzip
      (levels 6 and 9) and brotli. Deterministic facts about the file,
      published as evidence: D021's NON-GOALS bar a byte-size threshold,
      so a byte reading has no route to a verdict, however far it moves.
    - **parse + top-level compile time** — the wall time V8 spends in
      `new vm.Script(source)`: it PARSES the whole bundle and compiles the
      top level. It is EVIDENCE, host-dependent, and never gated.

  It measures neither of two things it would be easy to claim:

    - It is NOT eager function-body compilation. `new vm.Script` compiles
      the top level; the bodies of functions compile lazily on first call.
      `produceCachedData:true` does not change that — a prior B5 figure was
      labelled \"eager\" on that assumption and was wrong (the inner body
      stayed lazy after construction). So the reading is named for what it
      is — parse plus top-level compilation — and `produceCachedData` is
      deliberately unused.
    - It is NOT eval/run time. The bundle is a `:browser` artefact that
      references `window`, `self` and `document` at its top level, so it
      cannot be executed in a Node sandbox without throwing. Running it is
      not measured rather than measured wrongly.

  ## The deterministic gate is elsewhere, on purpose

  D021's B5 row also names a DETERMINISTIC reachability gate — that unused
  runtime modules are absent from the production bundle, proven by the
  F3d control-build technique. That gate needs a control build (the debug
  flag flipped, everything else held) and lives in the build lane; it is
  `rf2-drpa3.166`'s. This namespace is the EVIDENCE half only, and states
  no pass/fail about a bundle at all: every measurement it declares is
  `:bytes` or `:duration-ms`, which the harness can only publish.

  ## Provenance ties each number to the artefact

  A byte figure is only evidence about the file it was read from, so the
  fixture carries the artefact path and its SHA-256: a rebuild that
  changes the digest withdraws these specific numbers (D021 acceptance 3).
  And the record's `:build` is NOT detected — `detect-build` would report
  the MEASURING Node process (`:none`, instrumented), which is not the
  `:advanced` artefact under the probe. The caller supplies the artefact's
  own build via [[run-workload]]'s override, exactly as a build pipeline
  that knows what it shipped is meant to.

  Node-only: the readings are `fs`, `zlib` and `vm`, so this arm — like
  [[re-frame.freehand.bench.b1-react]] — runs on the ClojureScript host,
  not in the JVM `clojure -M:bench` suite. And like B4, it is driven by a
  test rather than registered into the standing suite, because its subject
  is an artefact a standing run has no reason to have built.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["fs" :as fs]
            ["zlib" :as zlib]
            ["vm" :as vm]
            ["crypto" :as crypto]
            [re-frame.freehand.bench.measure :as m]))

;; ---------------------------------------------------------------------------
;; The artefact
;; ---------------------------------------------------------------------------

(def default-bundle-path
  "Where `npm run build:freehand-release` lands the artefact, relative to
  `implementation/` — the directory the Node lane runs from."
  "out/freehand-release/main.js")

(defn bundle-present?
  "True when the release artefact is on disk. A B5 measurement of a bundle
  that was never built is a number about nothing, so the driving test
  skips rather than fabricates when this is false."
  [path]
  (.existsSync fs path))

(defn read-bundle
  "The artefact's bytes, as a Node Buffer — the on-disk bytes verbatim, so
  the byte and digest readings are of the file a consumer would serve, not
  of a re-encoding of it."
  [path]
  (.readFileSync fs path))

;; ---------------------------------------------------------------------------
;; The byte readings — deterministic facts about the file
;; ---------------------------------------------------------------------------

(defn raw-bytes
  "The artefact's uncompressed size in bytes."
  [^js buf]
  (.-length buf))

(defn gzip-bytes
  "The artefact's size under gzip at `level` — the transfer size a server
  sending `Content-Encoding: gzip` at that level would put on the wire."
  [^js buf level]
  (.-length (.gzipSync zlib buf #js {:level level})))

(defn brotli-bytes
  "The artefact's size under brotli at the library default quality — the
  transfer size a server sending `Content-Encoding: br` would put on the
  wire, and the smallest of the three encodings for a bundle this shape."
  [^js buf]
  (.-length (.brotliCompressSync zlib buf)))

(defn sha256
  "The artefact's SHA-256, lower-case hex. Ties every published figure to
  the exact bytes it was read from: a rebuild that changes this digest
  withdraws the numbers taken against the old one."
  [^js buf]
  (-> (.createHash crypto "sha256") (.update buf) (.digest "hex")))

;; ---------------------------------------------------------------------------
;; The parse/compile reading — evidence, host-dependent, never gated
;; ---------------------------------------------------------------------------

(defonce ^:private compile-nonce (atom 0))

(defn parse-compile-ms
  "Wall time for V8 to PARSE `source` and compile its TOP LEVEL, via
  `new vm.Script`.

  This is the cost the engine pays before the script's own top level runs
  — the relevant slice of a page's script-load budget that the substrate's
  size drives. It is NOT eager whole-program compilation: function bodies
  compile lazily on first call and are not included, and the bundle is not
  executed. `produceCachedData` is deliberately not passed — it does not
  force eager body compilation, so a figure taken with it would carry a
  label its method does not support.

  V8 keeps a per-isolate compilation cache keyed by SOURCE CONTENT, so a
  second `new vm.Script` over the same string returns a cached compilation
  in microseconds — measured, that would report the cache lookup and call
  it a parse. Each call therefore appends a fresh nonce comment, which
  changes the source enough to miss the cache and force a genuine cold
  parse+compile, while adding a few bytes to a 744 KB artefact — a delta
  far below the reading's own noise. Without this the figure collapses to
  near zero after the first sample, the exact kind of mislabelled timing
  D021's evidence discipline exists to prevent."
  [source]
  (let [unique (str source "\n//b5-parse-nonce-" (swap! compile-nonce inc))
        t0     (m/now-ms)]
    (vm/Script. unique)
    (- (m/now-ms) t0)))

;; ---------------------------------------------------------------------------
;; Measuring the artefact once
;; ---------------------------------------------------------------------------

(defn measure-bundle
  "Read the artefact at `path` and answer its byte facts, its digest, and
  the source string the parse/compile reading is taken over.

  The byte readings are taken once here — they are constant across
  samples, so gzipping the artefact once per sample would burn the sample
  budget re-deriving the same three numbers. Only [[parse-compile-ms]] is
  re-measured per sample, because only it varies."
  [path]
  (let [buf    (read-bundle path)
        source (.toString buf "utf8")]
    {:path         path
     :sha256       (sha256 buf)
     :raw-bytes    (raw-bytes buf)
     :gzip6-bytes  (gzip-bytes buf 6)
     :gzip9-bytes  (gzip-bytes buf 9)
     :brotli-bytes (brotli-bytes buf)
     :source       source}))

;; ---------------------------------------------------------------------------
;; The workload
;; ---------------------------------------------------------------------------

(def measurement-method
  "How the readings are taken, published verbatim in the fixture so a
  reader knows exactly what each number is a number of."
  (str "on-disk bytes of out/freehand-release/main.js and its size under "
       "zlib gzip (levels 6 and 9) and brotli at the library default; "
       "parse/compile is the wall time of new vm.Script over the source with "
       "a per-call nonce comment appended to defeat V8's content-keyed "
       "compilation cache — V8 parse plus top-level compilation, function "
       "bodies excluded (lazy), the bundle not executed"))

(defn workload
  "Build the B5 workload from an already-[[measure-bundle]]d artefact,
  under `id`, `doc` and `sampling`.

  The byte readings ride the workload as constants and the `:run` answers
  them unchanged every sample — an honest degenerate distribution, a file
  whose size does not vary — while re-measuring [[parse-compile-ms]] over
  the same source each sample so the parse/compile distribution is real.

  The baseline is `:before-vs-after`: shipped bytes and parse/compile are
  a regression signal tracked revision over revision — a promotion that
  changes what ships moves them — and the reference is the same reading on
  the preceding revision."
  [{:keys [id doc sampling]}
   {:keys [path sha256 raw-bytes gzip6-bytes gzip9-bytes brotli-bytes source]}]
  {:id       id
   :doc      doc
   :fixture  {:artefact           path
              :sha256             sha256
              :raw-bytes          raw-bytes
              :gzip6-bytes        gzip6-bytes
              :gzip9-bytes        gzip9-bytes
              :brotli-bytes       brotli-bytes
              :measurement-method measurement-method}
   :baseline {:kind      :before-vs-after
              :reference {:revision :the-revision-before-this
                          :note     (str "the same bundle's bytes and parse/compile on the "
                                         "preceding revision — equal shape is what makes a "
                                         "movement attributable to a change in what ships")}}
   :sampling sampling
   :measurements
   [{:id         :B5/raw-bytes
     :doc        "the release bundle's uncompressed on-disk size"
     :observable :bytes}
    {:id         :B5/gzip6-bytes
     :doc        "the bundle's transfer size under gzip level 6 — a common server default"
     :observable :bytes}
    {:id         :B5/gzip9-bytes
     :doc        "the bundle's transfer size under gzip level 9 — maximum gzip"
     :observable :bytes}
    {:id         :B5/brotli-bytes
     :doc        "the bundle's transfer size under brotli — the smallest of the three encodings"
     :observable :bytes}
    {:id         :B5/parse-compile-ms
     :doc        "wall time for V8 to parse the bundle and compile its top level (new vm.Script)"
     :observable :duration-ms}]
   :run
   (fn [_]
     {:B5/raw-bytes         raw-bytes
      :B5/gzip6-bytes       gzip6-bytes
      :B5/gzip9-bytes       gzip9-bytes
      :B5/brotli-bytes      brotli-bytes
      :B5/parse-compile-ms  (parse-compile-ms source)})})

(def release-build
  "The build the release artefact was compiled at, as a FACT about the
  artefact rather than a detection of the process measuring it.

  `:freehand-release` is declared `:advanced` with `goog.DEBUG` false in
  `implementation/shadow-cljs.edn`, so an instrumented dev build cannot be
  what is on disk. Passed as [[re-frame.freehand.bench/run-workload]]'s
  `:build` override, because `detect-build` would otherwise report the
  Node test process (`:none`, instrumented) — true of the measurer, false
  of the measured."
  {:optimizations :advanced :instrumentation? false})
