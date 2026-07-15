(ns re-frame.ui.slice-memo-lifetime-census-jvm-test
  "rf2-2mop6r — the slice-memo LIFETIME census.

  The slice-scoped probe memo reaches ONE law by TWO host mechanisms, and the
  authoritative prose must say so. Spec 006 §The slice-scoped probe memo once
  stated the table was `cleared by queueMicrotask` UNCONDITIONALLY, applying the
  CLJS module-holder mechanism to a JVM that has no microtask at all — there the
  table is a thread-local render scope discarded synchronously when the render
  thunk returns. The prose was reconciled per-host in 718a8dea16; this census is
  the regression pin that commit did not carry.

  The law, and why the mechanisms legitimately differ: the table dies with its
  slice, so probes may share WITHIN one slice but no table or handle survives
  into a LATER render slice. How a slice is BOUNDED is per-host because the
  hosts' scheduling models genuinely differ — the JVM has no microtask queue, and
  the single-threaded CLJS host needs no thread-local. Both reach the same law.

  This census pins, in BOTH authoritative documents:

    - each distinguishes the JVM thread-local render scope from the CLJS module
      holder released at the microtask checkpoint;
    - neither applies microtask lifetime to the JVM — the JVM span may name a
      microtask ONLY to deny it;
    - each states the shared law, attributed to BOTH hosts.

  The census is a pure predicate over document text
  (`slice-memo-lifetime-violations`), so the negative arm can prove it has teeth:
  the ACTUAL pre-reconciliation Spec 006 wording (718a8dea16^) and single-fact
  drift mutations of each live document must each be REJECTED, with the drift
  helper asserting every mutation actually landed. A census that only ever sees
  green prose proves nothing.

  JVM-only by nature: it reads repository files, and the documents it gates are
  host-agnostic. The RUNTIME lifetime contract is proven per-host by
  `reactive-slice-memo-render-boundary-jvm-test` (the JVM thread-local scope) and
  `reactive-slice-memo-incarnation-cljs-test` (the CLJS module holder)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; Locating the authoritative documents
;; ---------------------------------------------------------------------------

(defn- repo-root
  []
  (or (some (fn [candidate]
              (let [root (.getCanonicalFile (io/file candidate))]
                (when (.isFile (io/file root "AGENTS.md"))
                  root)))
            (take 6 (iterate #(io/file % "..") (io/file "."))))
      (throw (ex-info "Could not locate repository root" {}))))

(def ^:private root (delay (repo-root)))

(def ^:private documents
  "The two authoritative texts for the slice-memo lifetime, and the markers
  bounding the passage that carries it. Synthesis 03 is the ratified re-frame.ui
  program plan (tracked via the force-added `new-substrate-synthesis` subtree);
  Spec 006 is its promoted spec home."
  [{:label "spec/006-ReactiveSubstrate.md"
    :path  "spec/006-ReactiveSubstrate.md"
    :from  "### The slice-scoped probe memo"
    :to    "### Epoch finalization"}
   {:label "ai/findings/new-substrate-synthesis/03-reactivity-and-ownership.md"
    :path  "ai/findings/new-substrate-synthesis/03-reactivity-and-ownership.md"
    :from  "**First-mount fan-out mitigation:**"
    :to    "### The commit algorithm"}])

(defn- normalize
  "Collapse markdown line wrapping so a phrase wrapped across source lines still
  matches as one string."
  [text]
  (str/trim (str/replace text #"\s+" " ")))

(defn- slice-memo-section
  "The slice-memo lifetime passage of `label`'s document, whitespace-normalized.
  A missing marker throws rather than silently censusing an empty string — an
  empty section would vacuously pass every `includes?` negation."
  [{:keys [label path from to]}]
  (let [text  (slurp (io/file @root path))
        start (or (str/index-of text from)
                  (throw (ex-info "slice-memo section start marker missing"
                                  {:document label :marker from})))
        tail  (subs text start)
        end   (or (str/index-of tail to)
                  (throw (ex-info "slice-memo section end marker missing"
                                  {:document label :marker to})))]
    (normalize (subs tail 0 end))))

;; ---------------------------------------------------------------------------
;; The census
;; ---------------------------------------------------------------------------

(def ^:private jvm-marker "**JVM")
(def ^:private cljs-marker "**CLJS")

(def ^:private jvm-microtask-denial
  "The ONLY licensed mention of a microtask inside the JVM span."
  "there is no microtask on the JVM")

(def ^:private jvm-synchronous-discard
  "synchronously when the render thunk returns")

(def ^:private later-slice-law
  "no table or handle survives into a later render slice")

(def ^:private both-hosts-law-re
  "The reconciliation's substance: ONE law, attributed to BOTH hosts."
  #"(?i)both hosts[\s\S]{0,240}?enforce the (?:same|one) law")

(def ^:private within-slice-sharing-re
  #"(?i)(?:shar\w+[^.]{0,48}within (?:one|a) slice|within (?:one|a) slice[^.]{0,48}shar\w+)")

(def ^:private microtask-re #"(?i)microtask")

(defn slice-memo-lifetime-violations
  "Census ONE slice-memo lifetime passage; return the SET of violations (empty =
  conformant). Pure over text so the negative arm can prove the census rejects
  real drift."
  [section]
  (let [jvm-at  (str/index-of section jvm-marker)
        cljs-at (str/index-of section cljs-marker)
        jvm     (when (and jvm-at cljs-at (< jvm-at cljs-at))
                  (subs section jvm-at cljs-at))
        cljs    (when cljs-at
                  (let [tail (subs section cljs-at)
                        end  (str/index-of tail "Both hosts")]
                    (if end (subs tail 0 end) tail)))]
    (cond-> #{}
      ;; The passage must name the hosts at all. The pre-reconciliation text
      ;; named neither, and so described ONE mechanism as if universal.
      (nil? jvm)
      (conj :jvm/span-missing)

      (nil? cljs)
      (conj :cljs/span-missing)

      ;; JVM: a thread-local scope, discarded synchronously on return.
      (and jvm (not (str/includes? jvm "thread-local")))
      (conj :jvm/scope-not-thread-local)

      (and jvm (not (str/includes? jvm jvm-synchronous-discard)))
      (conj :jvm/synchronous-discard-missing)

      (and jvm (not (str/includes? jvm jvm-microtask-denial)))
      (conj :jvm/microtask-not-denied)

      ;; THE drift this bead exists for: microtask LIFETIME attributed to a host
      ;; that has no microtask. The span may name one only inside the denial.
      (and jvm (re-find microtask-re (str/replace jvm jvm-microtask-denial "")))
      (conj :jvm/microtask-lifetime-applied)

      ;; CLJS keeps the microtask checkpoint it genuinely has.
      (and cljs (not (str/includes? cljs "queueMicrotask")))
      (conj :cljs/microtask-checkpoint-missing)

      ;; The shared law, in both halves, attributed to both hosts.
      (not (re-find both-hosts-law-re section))
      (conj :law/not-attributed-to-both-hosts)

      (not (re-find within-slice-sharing-re section))
      (conj :law/within-slice-sharing-missing)

      (not (str/includes? section later-slice-law))
      (conj :law/later-slice-reuse-missing))))

;; ---------------------------------------------------------------------------
;; Positive arm — the live documents
;; ---------------------------------------------------------------------------

(deftest both-authoritative-documents-reconcile-slice-memo-lifetime
  (doseq [{:keys [label] :as document} documents]
    (testing label
      (is (= #{} (slice-memo-lifetime-violations (slice-memo-section document)))
          (str label " must distinguish the JVM thread-local render scope from "
               "the CLJS microtask checkpoint, and state the one law both hosts "
               "enforce")))))

;; ---------------------------------------------------------------------------
;; Negative arm — the census must REJECT drift
;; ---------------------------------------------------------------------------

(def ^:private pre-reconciliation-spec-006
  "The ACTUAL Spec 006 lifetime passage before 718a8dea16 — the drift this bead
  exists to prevent recurring. It applies `queueMicrotask` to every host and
  names neither, so a reader on the JVM (which has no microtask) is told the
  table is cleared by a mechanism that does not exist there."
  "### The slice-scoped probe memo

Probes are ownership-free, so N sibling sites probing the same query during one render
pass (first-mount fan-out: N rows probing `[:orders/by-id id]`) would recompute shared
derivation parents N times. The port permits one mitigation: a **slice-scoped pure memo
table** — the optional `?slice-memo` argument to `probe`. Within one slice, probes
share computed derivation parents; the table dies with the slice. No entry survives
into cache state, ownership state, or a later slice.

**Lifetime (S-3-settled).** There is no public React render-pass token; the table is
scoped to the **current synchronous execution slice**: created lazily on first probe,
cleared by `queueMicrotask`, and belt-and-braces tagged with
`(frame, frame-epoch, registry-epoch)` — invalidated on any mismatch. A time-sliced
pass spanning k slices builds k tables, so the economy is **once-per-slice, not
once-per-pass** — bounded, allocation-trivial, and requiring zero React internals; an
interrupted or abandoned slice's table becomes unreachable garbage.")

(deftest census-rejects-the-historical-pre-reconciliation-wording
  (testing "the pre-718a8dea16 Spec 006 text names no host and generalises the
            CLJS microtask, so the census must reject it"
    (is (= #{:jvm/span-missing
             :cljs/span-missing
             :law/not-attributed-to-both-hosts
             :law/later-slice-reuse-missing}
           (slice-memo-lifetime-violations (normalize pre-reconciliation-spec-006))))))

(defn- drift
  "Apply a single-fact drift to `section`, asserting the mutation actually
  landed. A no-op replacement would leave the live (green) text in place and
  make the negative arm vacuously pass."
  [section match replacement]
  (let [drifted (str/replace section match replacement)]
    (when (= drifted section)
      (throw (ex-info "drift fixture did not mutate the live text — the census
                       anchors it targets have moved"
                      {:match match})))
    drifted))

(def ^:private drifts
  "Single-fact mutations of the LIVE text. Each is one edit away from green, so
  the census is proven to catch MINIMAL drift, not merely wholly-other prose."
  [{:label   "the JVM span gains microtask lifetime (the historical drift)"
    :match   jvm-microtask-denial
    :replace "the table is cleared by `queueMicrotask` at the microtask checkpoint"
    :expect  :jvm/microtask-lifetime-applied}
   {:label   "the JVM span loses its synchronous discard"
    :match   jvm-synchronous-discard
    :replace "eventually"
    :expect  :jvm/synchronous-discard-missing}
   {:label   "the later-slice reuse law is dropped"
    :match   later-slice-law
    :replace "the table stays reusable"
    :expect  :law/later-slice-reuse-missing}
   {:label   "the law is no longer attributed to both hosts"
    :match   "Both hosts"
    :replace "The CLJS host"
    :expect  :law/not-attributed-to-both-hosts}
   {:label   "the CLJS span loses its microtask checkpoint"
    :match   "queueMicrotask"
    :replace "a host callback"
    :expect  :cljs/microtask-checkpoint-missing}])

(deftest census-rejects-single-fact-drift-in-either-document
  (doseq [{:keys [label] :as document} documents
          :let [section (slice-memo-section document)]
          {drift-label :label :keys [match replace expect]} drifts]
    (testing (str label " — " drift-label)
      (is (contains? (slice-memo-lifetime-violations (drift section match replace))
                     expect)
          (str "census must reject " label " when " drift-label)))))
