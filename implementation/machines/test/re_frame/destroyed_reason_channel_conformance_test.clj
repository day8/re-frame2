(ns re-frame.destroyed-reason-channel-conformance-test
  "rf2-hh1jhc — the destroyed-reason CHANNEL/REASON MATRIX pin.

  The runtime emits machine-destroy traces on two parallel channels
  (Spec 009 §Two-channel teardown):

    - `:rf.machine.lifecycle/destroyed` — the registrar-substrate
      observation. Frame-exit reaping is its SOLE trigger; the only
      `:reason` it carries is `:parent-frame-destroyed`.
    - `:rf.machine/destroyed` — the fx-substrate observation. Carries
      every non-frame-exit reason: `:rf.machine/finished`,
      `:rf.machine/join-reaped`, `:explicit`, and the reserved
      `:parent-unmount-cascade` (declared by 005 D6, no current emit
      site — parent-cascade teardowns stamp `:explicit`).

  That matrix is documented on FOUR surfaces which historically drifted
  apart (the rf2-hh1jhc contradiction: Spec-Schemas advertised four
  reasons on the lifecycle channel while Spec 009 and the runtime carry
  one). This test parses each surface and pins them against each other
  and against the emit sites, so a future reason added to one channel's
  docs while another surface silently drifts goes RED:

    1. Spec 009 §`:op-type` vocabulary — the canonical matrix table
       (`| :reason | Channel | Emitted by | Meaning |`).
    2. Spec-Schemas §`:rf/trace-event` — the `:machine` family row's
       fx-reason enumeration and the `:rf.machine.lifecycle/destroyed`
       row's sole-reason contract.
    3. Spec 005 §Final states D6 — the fx-channel enrichment vocabulary.
    4. The emit sites themselves — a conservative literal source scan
       (same style as `re-frame.error-catalogue-channel-conformance-test`):
       every literal `:reason` stamped near a lifecycle-channel emit must
       be a lifecycle reason; every literal fx-channel reason must be in
       the documented fx set; a reason the matrix marks *reserved* must
       not be stamped anywhere (implementing it forces the matrix co-edit).

  JVM-only (`.clj`): it `slurp`s repo markdown + source files, which only
  the JVM `clojure -M:test` runner can do. The runtime BEHAVIOUR of the
  fx-channel reasons is already pinned live by
  `re-frame.destroyed-trace-shape-test` (\":reason is one of :explicit /
  :rf.machine/finished / :rf.machine/join-reaped\") and the lifecycle
  channel by `frame_lifecycle_test/destroy-frame-cascade-emits-per-active-
  machine`; this file pins the DOCS against those facts."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; File resolution (JVM test CWD is `implementation/machines/`)
;; ---------------------------------------------------------------------------

(defn- resolve-repo-file
  "Resolve `rel` (repo-root-relative) from the machines-artefact test CWD,
  with a fallback for a transitional REPL run from `implementation/`.
  Mirrors `re-frame.error-catalogue-channel-conformance-test`."
  [rel]
  (let [nested (io/file (str "../../" rel))
        legacy (io/file (str "../" rel))]
    (if (.exists nested) nested legacy)))

(def ^:private spec-009-file          (resolve-repo-file "spec/009-Instrumentation.md"))
(def ^:private spec-schemas-file      (resolve-repo-file "spec/Spec-Schemas.md"))
(def ^:private spec-005-file          (resolve-repo-file "spec/005-StateMachines.md"))
(def ^:private cross-spec-file        (resolve-repo-file "spec/Cross-Spec-Interactions.md"))

(def ^:private src-roots
  "The two source trees that carry destroy emit sites: the machines
  artefact (fx channel + the frame-destroy orchestrator) and core (the
  no-machines lifecycle fallback in `frame.cljc`)."
  (->> [(io/file "src") (io/file "../core/src")
        ;; transitional REPL-from-`implementation/` fallbacks
        (io/file "machines/src") (io/file "core/src")]
       (filter #(.isDirectory %))
       vec))

(defn- source-files []
  (->> src-roots
       (mapcat file-seq)
       (filter #(.isFile %))
       (filter (fn [f] (re-find #"\.clj[cs]?$" (.getName f))))))

;; ---------------------------------------------------------------------------
;; Surface parsers
;; ---------------------------------------------------------------------------

(def ^:private matrix-row-re
  "One row of the Spec 009 canonical matrix table:
     | `:reason` | `:channel` | emitted-by | meaning |
  Group 1 = the reason keyword, group 2 = the channel keyword,
  group 3 = the raw Emitted-by cell (whole cell, so the *reserved*
  marker is machine-readable). Leading whitespace allowed — the table
  is indented inside a list item."
  #"^\s*\|\s*`(:[\w./-]+)`\s*\|\s*`(:rf\.machine[\w./-]*)`\s*\|([^|]*)\|")

(defn- parse-009-matrix
  "Parse the canonical channel/reason matrix out of Spec 009 into
  `[{:reason kw :channel kw :emitted-by str} ...]`. Scoped to the table
  following the matrix heading sentence so no other 009 table matches."
  []
  (->> (slurp spec-009-file)
       str/split-lines
       (drop-while #(not (str/includes? % "the canonical channel/reason matrix")))
       (take-while #(not (str/includes? % "The enum is open")))
       (keep (fn [line]
               (when-let [[_ reason channel emitted-by] (re-find matrix-row-re line)]
                 {:reason     (keyword (subs reason 1))
                  :channel    (keyword (subs channel 1))
                  :emitted-by (str/trim emitted-by)})))
       vec))

(defn- backticked-keywords
  "Every backticked keyword in `s`, as keywords."
  [s]
  (->> (re-seq #"`(:[\w./-]+)`" s)
       (map (fn [[_ k]] (keyword (subs k 1))))))

(defn- find-line
  "The first line of `file` matching `pred`, or nil."
  [file pred]
  (->> (slurp file) str/split-lines (filter pred) first))

(defn- spec-schemas-fx-reasons
  "The fx-reason enumeration in Spec-Schemas' `:machine` family row —
  the span `carries \\`:reason\\` — one of <kws> — `. Returns a set."
  []
  (let [row (find-line spec-schemas-file #(str/starts-with? % "| `:machine` |"))
        [_ span] (when row (re-find #"carries `:reason` — one of (.*?) —" row))]
    (set (some-> span backticked-keywords))))

(defn- spec-schemas-lifecycle-row []
  (find-line spec-schemas-file
             #(str/starts-with? % "| `:rf.machine.lifecycle/destroyed` |")))

(defn- spec-005-d6-reasons
  "The D6 enrichment vocabulary — the span `\\`:reason\\` tag — one of
  <kws>.` in the D6 sub-decision row. Returns a set."
  []
  (let [row (find-line spec-005-file #(str/starts-with? % "| D6 |"))
        ;; the span ends at the backtick-then-period closing the sentence —
        ;; a bare `\.` stop would cut inside `:rf.machine/finished`
        [_ span] (when row (re-find #"`:reason` tag — one of (.*?`)\. " row))]
    (set (some-> span backticked-keywords))))

;; ---------------------------------------------------------------------------
;; The ruling (rf2-hh1jhc) — the two channel vocabularies
;; ---------------------------------------------------------------------------

(def ^:private lifecycle-channel :rf.machine.lifecycle/destroyed)
(def ^:private fx-channel        :rf.machine/destroyed)

(def ^:private expected-lifecycle-reasons
  "The registrar channel's SOLE reason — frame-exit reaping."
  #{:parent-frame-destroyed})

(def ^:private expected-fx-reasons
  "The fx channel's complete vocabulary (005 D6). `:parent-unmount-cascade`
  is reserved (no emit site); the emitted subset is pinned separately.
  Adding a reason means updating the 009 matrix, the Spec-Schemas rows,
  005 D6, AND this literal — the co-edit is the point."
  #{:rf.machine/finished :rf.machine/join-reaped :explicit :parent-unmount-cascade})

;; ---------------------------------------------------------------------------
;; Doc ↔ doc conformance
;; ---------------------------------------------------------------------------

(deftest matrix-parses-and-assigns-each-reason-to-exactly-one-channel
  (let [rows (parse-009-matrix)]
    (testing "sanity: the 009 matrix table parses"
      (is (.exists spec-009-file) (str "missing " spec-009-file))
      (is (>= (count rows) 5) (str "matrix rows parsed: " (pr-str rows))))
    (testing "each reason appears exactly once (one channel per reason)"
      (let [dups (->> rows (map :reason) frequencies
                      (filter (fn [[_ n]] (> n 1))) (map first))]
        (is (empty? dups) (str "reasons on more than one matrix row: " (pr-str dups)))))
    (testing "only the two teardown channels appear"
      (is (= #{lifecycle-channel fx-channel} (set (map :channel rows)))))))

(deftest matrix-carries-the-ruled-channel-vocabularies
  (let [rows (parse-009-matrix)
        by-channel (fn [ch] (->> rows (filter #(= ch (:channel %))) (map :reason) set))]
    (testing "lifecycle channel = frame-exit only (rf2-hh1jhc ruling)"
      (is (= expected-lifecycle-reasons (by-channel lifecycle-channel))))
    (testing "fx channel = the four non-frame-exit reasons (005 D6)"
      (is (= expected-fx-reasons (by-channel fx-channel))))))

(deftest spec-schemas-fx-row-matches-the-matrix
  (testing "Spec-Schemas `:machine` family row enumerates exactly the fx set"
    (is (= expected-fx-reasons (spec-schemas-fx-reasons))
        "the `carries `:reason` — one of …` span in Spec-Schemas' `:machine`
         row must equal the 009 matrix's fx-channel vocabulary")))

(deftest spec-schemas-lifecycle-row-is-single-reason
  (let [row (spec-schemas-lifecycle-row)]
    (is (some? row) "Spec-Schemas carries the `:rf.machine.lifecycle/destroyed` row")
    (testing "the tags schema pins the sole reason"
      (is (str/includes? row ":reason :parent-frame-destroyed}")
          "the row's `:tags` map must close with `:reason :parent-frame-destroyed`"))
    (testing "the row states the sole-reason contract"
      (is (str/includes? row "`:reason` is always `:parent-frame-destroyed`")))
    (testing "no fx-only reason leaks into the lifecycle row"
      (let [fx-only (disj expected-fx-reasons :explicit)
            leaked  (set/intersection (set (backticked-keywords row)) fx-only)]
        (is (empty? leaked)
            (str "fx-channel reasons named in the lifecycle row: " (pr-str leaked)))
        (is (not (str/includes? row "`:explicit`"))
            "the lifecycle row must not name `:explicit` either")))))

(deftest spec-005-d6-matches-the-matrix
  (testing "005 D6's enrichment vocabulary equals the fx set"
    (is (= expected-fx-reasons (spec-005-d6-reasons)))))

(deftest cross-spec-interactions-does-not-reassign-reasons
  (testing "Cross-Spec-Interactions no longer pairs `:parent-unmount-cascade`
            with the lifecycle channel (the rf2-hh1jhc contradiction); the
            reserved reason should not appear there at all — teardown-on-
            route-change is documented as the fx channel's `:explicit`"
    (is (not (str/includes? (slurp cross-spec-file) ":parent-unmount-cascade"))
        "reintroducing the reserved reason into Cross-Spec-Interactions
         requires the 009 matrix (and this test) to change first")))

;; ---------------------------------------------------------------------------
;; Doc ↔ emit-site conformance (conservative literal scan)
;; ---------------------------------------------------------------------------

(def ^:private lifecycle-reason-near-emit-re
  "A literal `:reason` keyword within a bounded window after a lifecycle-
  channel mention (emit site or its contract docstring). Non-greedy: the
  FIRST `:reason` after each mention. Conservative — a miss under-reports,
  never false-positives."
  ;; the capture must END on a word char so a docstring's sentence-final
  ;; period is not swallowed into the keyword (`:parent-frame-destroyed.`)
  #"(?s):rf\.machine\.lifecycle/destroyed.{0,800}?:reason\s+(:[\w./-]*[\w-])")

(def ^:private fx-literal-reason-res
  "The fx channel's literal reason chokepoints:
    - a literal `:reason` inside an `emit-destroyed!` argument map
      (finalize's `:rf.machine/finished`),
    - the literal reason arg of `destroy-resolved!` (`:explicit` /
      `:rf.machine/join-reaped`),
    - `emit-destroyed!`'s `:or {reason :explicit}` default."
  [#"(?s)emit-destroyed!\s+\{.{0,400}?:reason\s+(:[\w./-]+)"
   #"destroy-resolved!\s+frame-id\s+\S+\s+(:[\w./-]+)"
   #":or\s+\{reason\s+(:[\w./-]+)\}"])

(defn- scan-sources [res]
  (->> (source-files)
       (mapcat (fn [f]
                 (let [src (slurp f)]
                   (mapcat #(map second (re-seq % src)) res))))
       (map #(keyword (subs % 1)))
       set))

(deftest lifecycle-emit-sites-stamp-only-the-lifecycle-reason
  (let [found (scan-sources [lifecycle-reason-near-emit-re])]
    (is (seq src-roots) "source roots resolved from the test CWD")
    (is (seq found) "the scan reached at least one lifecycle emit/contract site")
    (is (set/subset? found expected-lifecycle-reasons)
        (str "reasons stamped/documented at lifecycle-channel sites beyond "
             expected-lifecycle-reasons ": "
             (pr-str (set/difference found expected-lifecycle-reasons))))))

(deftest fx-emit-sites-stamp-only-documented-fx-reasons
  (let [found (scan-sources fx-literal-reason-res)]
    (is (seq found) "the scan reached the fx-channel literal chokepoints")
    (testing "every literal fx reason is documented on the fx channel"
      (is (set/subset? found expected-fx-reasons)
          (str "undocumented fx-channel reasons: "
               (pr-str (set/difference found expected-fx-reasons)))))
    (testing "the frame-exit reason never rides the fx channel"
      (is (not (contains? found :parent-frame-destroyed))))
    (testing "the three documented-as-emitted reasons are real"
      (doseq [r [:explicit :rf.machine/finished :rf.machine/join-reaped]]
        (is (contains? found r) (str r " is stamped at an fx emit site"))))))

(deftest reserved-reasons-are-not-emitted
  (testing "a matrix row marked *reserved* must have NO emit site; stamping
            it in source forces the matrix co-edit (flip the row to its
            emit site) and a rethink of this pin"
    (let [reserved (->> (parse-009-matrix)
                        (filter #(str/includes? (:emitted-by %) "reserved"))
                        (map :reason)
                        set)]
      (is (contains? reserved :parent-unmount-cascade)
          "the 009 matrix marks :parent-unmount-cascade reserved")
      (doseq [r reserved
              f (source-files)]
        (is (not (str/includes? (slurp f) (str r)))
            (str r " (reserved in the 009 matrix) appears in " (.getPath f)))))))
