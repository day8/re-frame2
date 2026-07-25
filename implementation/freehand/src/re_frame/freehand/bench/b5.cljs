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
  runtime modules are absent from the production bundle, proven by the F3d
  control-build technique. That gate needs a control build and a validated
  oracle, so it is not a measurement and does not live here: it is
  `implementation/scripts/check-freehand-reachability.cjs`, run by `npm run
  test:freehand-reachability`, which holds `goog.DEBUG` still and moves the
  APP. (Its sibling `check-freehand-evidence-elision.cjs` does the reverse —
  holds the app and moves the flag — and proves a different thing: that the
  dev-gated evidence seam elides.)

  This namespace is the EVIDENCE half only, and states no pass/fail about a
  bundle at all: every measurement it declares is `:bytes` or
  `:duration-ms`, which the harness can only publish.

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
            [clojure.string :as string]
            [re-frame.freehand.bench.measure :as m]))

;; ---------------------------------------------------------------------------
;; The artefact
;; ---------------------------------------------------------------------------

(def default-bundle-path
  "Where `npm run build:freehand-release` lands the artefact, relative to
  `implementation/` — the directory the Node lane runs from. This is the
  MIXED shape — one interpreted leaf and its compiled twin under one root."
  "out/freehand-release/main.js")

(def interpreted-bundle-path
  "The arm of the MATCHED PAIR with no view lowered
  (`:freehand-release-interpreted`, entry
  `re-frame.freehand.release-app-lowered-none`). Built by `npm run
  build:freehand-matched` — see [[matched-build-posture]] for why the pair is
  built by that script and not by a bare `shadow-cljs release`."
  "out/freehand-release-interpreted/main.js")

(def compiled-bundle-path
  "The arm of the MATCHED PAIR with every view lowered
  (`:freehand-release-compiled`, entry
  `re-frame.freehand.release-app-lowered-full`) — no interpreted view reaches
  this bundle. Built by `npm run build:freehand-matched`, same posture as its
  twin; see [[matched-build-posture]]."
  "out/freehand-release-compiled/main.js")

(def matched-bundle-paths
  "The three release shapes B5's build lane measures, keyed by lowering.

  `:interpreted` and `:compiled` are the MATCHED PAIR, and the delta is taken
  over those two alone. Their entries are one source in two states —
  identical character for character once the three `{:compiled true}` markers
  are dropped and the equal-length `lowered-none`/`lowered-full` segment is
  renamed, which `b5-matched-builds-cljs-test` asserts on every run — under
  the same `:advanced` and the same `goog.DEBUG` false. A RAW byte that
  differs between them differs because of lowering; the compressed encodings
  carry a small residue of the arm identity itself, and [[causal-isolation]]
  states which claim holds for which encoding.

  `:mixed` is the SHIPPED-COST artefact (`:freehand-release`, one interpreted
  leaf and its compiled twin under one root) — D021's `mixed production
  bundle`, and a REPRESENTATIVE one rather than a third matched arm. It is
  built from its own entry with its own registered ids and its own prose, so
  it is measured here for context and is NOT byte-matched to the pair: no
  delta is taken against it, because the delta only ever needed two arms."
  {:interpreted interpreted-bundle-path
   :mixed       default-bundle-path
   :compiled    compiled-bundle-path})

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
;; The per-promotion delta — evidence, never a threshold
;; ---------------------------------------------------------------------------

(defn promotion-delta
  "The WHOLE-APP BYTE delta between an interpreted-shape and a
  compiled-shape [[measure-bundle]]d artefact: `compiled` minus
  `interpreted`, per encoding.

  A positive number means the compiled shape is LARGER on the wire — the
  compiled emitter's per-view output outweighing the interpreter body it
  replaces — and a negative one means promotion shrank the bundle. Which way
  it falls, and how far, is a fact about the two artefacts, published as
  evidence: D021's NON-GOALS bar a byte-size threshold, so a delta has no
  route to a verdict however large it is.

  ## What this delta is, and is not, attributable to

  It is a WHOLE-APP figure over every view the two entries promote together,
  not a per-view one. [[promotion-delta-workload]] publishes both, and names
  the per-view figure for the mean it is.

  And it is attributable to lowering EXACTLY in raw bytes and only
  APPROXIMATELY under the compressed encodings — [[causal-isolation]] carries
  the measured difference, and rides the fixture so the distinction travels
  with the numbers instead of living in this docstring.

  Lowering is the only variable across the pair. The two entries are one
  source in two states: identical character for character once the three
  `{:compiled true}` markers are dropped and the `lowered-none`/`lowered-full`
  segment is renamed, which `b5-matched-builds-cljs-test` asserts on every
  run. Two fixture properties earn that, both of them consequences of what
  `:advanced` does NOT strip:

    - the two namespace segments are the SAME LENGTH, because a namespace's
      own name rides into the bundle inside the registered event, sub and
      frame ids, the view manifest and the compiled tier's source
      coordinates;
    - neither entry carries a namespace DOCSTRING — the view manifest ships
      one once per declared view, so a docstring lands three times in the
      artefact under the probe.

  Both were confounds until `rf2-x4jda`: the entries this pair replaced had
  differently-worded docstrings and namespace names of different lengths,
  together worth +252 raw bytes of the 17,905-byte raw delta READ AT
  `d5794fded8` (1.4%). That denominator is a past reading at a named
  revision, not this record's own figure — the record carries its own, and a
  caveat quoting a stale one beside a live measurement reads as an
  inconsistency in the evidence (`rf2-hce4p`). The pair now measures 0 in RAW
  bytes, and `b5-matched-builds-cljs-test` proves that on the artefacts
  themselves every run rather than inferring it from the sources: rename the
  arm token to any other token of the same length and the raw delta comes back
  bit-identical ([[canonicalise-arm-identity]]).

  ## The bytes are only comparable under one build posture

  Lowering is the only variable in the SOURCES, but the artefacts are Closure
  output, and Closure's renaming depends on how the build was invoked as well
  as on what it compiled. [[matched-build-posture]] states the posture the
  pair is built under and what deviating from it costs; it rides the fixture
  so no delta is ever cited without it.

  Both maps must be from the same [[measure-bundle]] shape; the raw, gzip
  (6 and 9) and brotli deltas are answered, plus each side's digest so the
  delta is tied to the exact two bundles it was taken over."
  [interpreted compiled]
  {:raw-bytes    (- (:raw-bytes compiled)    (:raw-bytes interpreted))
   :gzip6-bytes  (- (:gzip6-bytes compiled)  (:gzip6-bytes interpreted))
   :gzip9-bytes  (- (:gzip9-bytes compiled)  (:gzip9-bytes interpreted))
   :brotli-bytes (- (:brotli-bytes compiled) (:brotli-bytes interpreted))
   :interpreted-sha256 (:sha256 interpreted)
   :compiled-sha256    (:sha256 compiled)})

(def matched-on
  "What the two arms genuinely hold still — the claim the delta rests on."
  (str "the two entries are ONE SOURCE IN TWO STATES: identical character for "
       "character once the three {:compiled true} markers are dropped and the "
       "equal-length lowered-none/lowered-full segment is renamed, asserted on "
       "every run by b5-matched-builds-cljs-test. Same handlers, same view "
       "bodies, same root DOM id, no namespace docstring on either (:advanced "
       "does not strip one and the manifest ships it per declared view), same "
       ":advanced optimisation, same goog.DEBUG false, same output shape, and "
       "the same cleared-cache build posture — stated in full by :build-posture"))

(def matched-build-posture
  "The BUILD POSTURE both arms are compiled under, published in the fixture
  because the bytes are only comparable within one posture.

  `:advanced` is held still as a SETTING by the two build definitions; this is
  what holds it still as an OUTCOME. Closure's identifier and property
  renaming for a build that compiles fresh is not the same when a SIBLING
  build in the same process was served from the shadow-cljs cache — same
  functions, different names, thousands of bytes apart. Nothing in the
  artefacts records which way they were built, so two honest runs at one
  revision could publish two different deltas under the same `:revision`
  (`rf2-hce4p`). `npm run build:freehand-matched` removes the three shapes'
  `out/` and `.shadow-cljs/builds/` directories before compiling them, which
  pins the cleared-cache posture; this string is how the record says so."
  (str "both arms compiled from a CLEARED build cache by `npm run "
       "build:freehand-matched`, which removes out/ and .shadow-cljs/builds/ "
       "for all three shapes before compiling them in one invocation. The "
       "clearing is part of the method, not housekeeping: an arm that "
       "compiles fresh in a process where a SIBLING build was served from "
       "cache gets different Closure identifier and property renaming. "
       "Measured at c8f0ae6ddb the compiled arm came out 742,512 bytes from a "
       "cleared cache and 738,437 with its siblings warm — the same 4,312 "
       "functions, 4,075 raw bytes apart, 23% of that run's 17,758-byte "
       "delta. Both figures reproduce exactly on repeat, so a delta taken "
       "under any other posture is a different measurement and not comparable "
       "with this one"))

(def arm-identity-pattern
  "The two arms' distinguishing IDENTITY TOKEN as it reaches the artefacts —
  `lowered-none` / `lowered-full` in the hyphen spelling every id derived from
  the namespace carries, and `lowered_none` / `lowered_full` in the underscore
  spelling the compiled tier's source coordinates carry.

  All four are twelve characters, which is the fixture property the raw delta's
  causal isolation rests on. See [[canonicalise-arm-identity]]."
  #"lowered([-_])(?:none|full)")

(def arm-identity-probe-tokens
  "Four-character replacement tokens the identity probe substitutes for the
  arms' own `none` / `full`, deliberately unalike: a neutral label, a
  single-character run, and a real word both arms could plausibly have shared.

  Several, not one, because the quantity being probed is SENSITIVE — one token
  samples one point of it and reads like a constant. The #6909 audit published
  a single-token brotli figure of −16 bytes; sweeping seven tokens at
  `babc7fb540` found a 227-byte spread, so that figure was one sample of
  something an order of magnitude larger. See [[causal-isolation]]."
  ["arm0" "aaaa" "same"])

(defn canonicalise-arm-identity
  "`source` with the arms' identity token replaced by `token`, in both
  spellings, so a reading taken over the result cannot be told which arm it
  came from.

  This is the control for the delta's one remaining fixture asymmetry: the two
  arms are two NAMESPACES, so every id, manifest entry and source coordinate
  derived from the namespace differs in CONTENT between them. `token` must be
  four characters — the length of the `none` / `full` it stands in for — which
  is what makes the substitution byte-neutral per arm however many times it
  occurs, and therefore what makes the RAW delta invariant under it.

  Occurrence counts are NOT equal between the arms and are not meant to be: at
  `babc7fb540` the interpreted arm shipped 23 and the compiled arm 29, and the
  six extra are compiled-tier source coordinates that exist BECAUSE the views
  were lowered. Those bytes are lowering's own and belong in the delta. What
  this controls for is the token's CONTENT, not its census."
  [source token]
  (string/replace source arm-identity-pattern (str "lowered$1" token)))

(def causal-isolation
  "WHICH of the delta's encodings is attributable to lowering, and how
  exactly — the audit finding this fixture exists to carry (`rf2-x4jda`,
  merged-PR audit #6909).

  The pair holds lowering as the only variable in the SOURCES, but a compressed
  size is not a sum of its input's parts: the same bytes in a different
  arrangement compress differently, so the arms' differing identity CONTENT
  reaches the compressed deltas even though its LENGTH is controlled. Measured
  by substituting equal-length probe tokens into both artefacts
  ([[canonicalise-arm-identity]]) and re-taking the delta:

    - RAW — the contribution is EXACTLY zero, for every probe token, by
      construction. Asserted on the real artefacts on every built-bundle run,
      so this is the one figure the pair may present as lowering and nothing
      else.
    - GZIP (6 and 9) — the delta moved by at most 9 bytes across seven probe
      tokens at `babc7fb540`, against deltas of 4,794 and 4,738: ≤0.2%.
      Attributable to lowering to within a handful of bytes.
    - BROTLI — the delta moved across a 227-byte spread (−198 to +29) at the
      same revision, against a 3,781-byte delta: up to ≈5%. The brotli figure
      is a real fact about the two artefacts, but it may NOT be read as a
      lowering-only number.

  Those percentages are a reading at a NAMED revision, and the revision is
  named because that is the only thing that keeps an absolute honest as the
  bundles move. The stable claim is the ordering — raw exact, gzip within a
  handful, brotli sensitive at the hundred-byte scale — and it is the ordering
  a reader should carry away."
  (str "attribution to lowering holds EXACTLY for the raw delta and only "
       "approximately for the compressed ones. The two arms are two "
       "namespaces, so their ids, manifests and source coordinates differ in "
       "identity CONTENT; the tokens are the same LENGTH, which makes the raw "
       "delta provably invariant under renaming them (asserted every built "
       "run) but does not make a compressed delta invariant, because "
       "compression is not a sum of its input's parts. Substituting "
       "equal-length probe tokens at babc7fb540 moved the gzip-6/gzip-9 "
       "deltas by at most 9 bytes of 4,794/4,738 (≤0.2%) and the brotli delta "
       "across a 227-byte spread of 3,781 (up to ~5%). Read raw as lowering, "
       "gzip as lowering within a handful of bytes, and brotli as "
       "identity-sensitive"))

(def not-matched-on
  "What the two arms do NOT hold still, stated so a reader can discount it.

  Published in the delta's fixture because a caveat that lives only in a
  reviewer's memory is not part of the evidence. See [[promotion-delta]] and
  [[causal-isolation]], which carries the measured size of the residue."
  (str "they remain two NAMESPACES, so the identity strings each ships — the "
       "registered event/sub/frame ids, the view manifest, the compiled tier's "
       "source coordinates — differ in CONTENT: lowered-none against "
       "lowered-full. The two segments are the same length, so the RAW delta "
       "carries none of that (proved on the artefacts every built run, not "
       "inferred); differing content still moves the compressed deltas, and "
       ":causal-isolation states by how much per encoding. The per-view "
       "figures are a MEAN over the promoted view count, not a "
       "per-declaration observation"))

(defn promotion-delta-workload
  "The per-promotion byte delta as a WORKLOAD, so it reaches the SAME door
  every other B5 figure does.

  The delta was previously printed straight to the console as a bare map: it
  bypassed `provenance/result`, carried none of the record shape the ledger
  requires, and so was published under weaker discipline than the readings it
  was derived from. It is a derived byte fact, which is a thing this harness
  already knows how to publish — the byte readings ride
  [[workload]] as constants too — so it needs no new machinery, only the
  existing one.

  `promoted-views` is the number of view declarations the two entries promote
  together, and is a FIXTURE PARAMETER supplied by the caller: this namespace
  measures files and does not read the entries that produced them. It is what
  makes the per-view figure meaningful, and the figure is published as the
  MEAN it is — this app's promoted views are the same body twice under one
  root, so their individual costs are not separately observable from bytes.

  Baseline `:interpreted-vs-compiled`, referencing the interpreted arm: this
  is precisely D021's first named comparator."
  [{:keys [id doc sampling promoted-views]} interpreted compiled]
  (let [d       (promotion-delta interpreted compiled)
        per-view #(/ (get d %) promoted-views)]
    {:id      id
     :doc     doc
     :fixture {:interpreted-artefact (:path interpreted)
               :compiled-artefact    (:path compiled)
               :interpreted-sha256   (:sha256 interpreted)
               :compiled-sha256      (:sha256 compiled)
               :promoted-views       promoted-views
               :measurement-method
               (str "compiled-minus-interpreted on-disk and compressed bytes over "
                    (:path interpreted) " and " (:path compiled)
                    "; the per-view figures are that total divided by the "
                    promoted-views " view declarations the two entries promote "
                    "together — a MEAN, not a per-declaration observation")
               :matched-on       matched-on
               :not-matched-on   not-matched-on
               :causal-isolation causal-isolation
               :build-posture    matched-build-posture}
     :baseline {:kind      :interpreted-vs-compiled
                :reference {:arm  :interpreted
                            :note (str "the interpreted-only twin " (:path interpreted)
                                       ", sha256 " (:sha256 interpreted))}}
     :sampling sampling
     :measurements
     [{:id         :B5/promotion-delta-raw-bytes
       :doc        "whole-app uncompressed byte delta, compiled minus interpreted"
       :observable :bytes}
      {:id         :B5/promotion-delta-gzip6-bytes
       :doc        "whole-app gzip-6 byte delta, compiled minus interpreted"
       :observable :bytes}
      {:id         :B5/promotion-delta-gzip9-bytes
       :doc        "whole-app gzip-9 byte delta, compiled minus interpreted"
       :observable :bytes}
      {:id         :B5/promotion-delta-brotli-bytes
       :doc        "whole-app brotli byte delta, compiled minus interpreted"
       :observable :bytes}
      {:id         :B5/promotion-delta-raw-bytes-mean-per-view
       :doc        "the raw delta divided by the promoted view count — a mean, not a per-declaration reading"
       :observable :bytes}
      {:id         :B5/promotion-delta-gzip6-bytes-mean-per-view
       :doc        "the gzip-6 delta divided by the promoted view count — a mean"
       :observable :bytes}
      {:id         :B5/promotion-delta-brotli-bytes-mean-per-view
       :doc        "the brotli delta divided by the promoted view count — a mean"
       :observable :bytes}]
     :run
     (fn [_]
       {:B5/promotion-delta-raw-bytes                  (:raw-bytes d)
        :B5/promotion-delta-gzip6-bytes                (:gzip6-bytes d)
        :B5/promotion-delta-gzip9-bytes                (:gzip9-bytes d)
        :B5/promotion-delta-brotli-bytes               (:brotli-bytes d)
        :B5/promotion-delta-raw-bytes-mean-per-view    (per-view :raw-bytes)
        :B5/promotion-delta-gzip6-bytes-mean-per-view  (per-view :gzip6-bytes)
        :B5/promotion-delta-brotli-bytes-mean-per-view (per-view :brotli-bytes)})}))

;; ---------------------------------------------------------------------------
;; The workload
;; ---------------------------------------------------------------------------

(defn measurement-method
  "How the readings over `path` were taken, published verbatim in the fixture
  so a reader knows exactly what each number is a number of.

  A function of the artefact, not a constant. The three matched shapes route
  through this same probe, and a method line that named
  `out/freehand-release/main.js` while its own fixture's `:artefact` named a
  different file described a measurement that did not happen — the exact
  drift the #6887 audit found on the two twins' records."
  [path]
  (str "on-disk bytes of " path " and its size under "
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
              :measurement-method (measurement-method path)}
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
