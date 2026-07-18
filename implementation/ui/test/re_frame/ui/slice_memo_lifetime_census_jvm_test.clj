(ns re-frame.ui.slice-memo-lifetime-census-jvm-test
  "rf2-2mop6r — the slice-memo LIFETIME census.

  The slice-scoped probe memo reaches ONE law by TWO host mechanisms, and the
  authoritative prose must say so. Spec 006 §The slice-scoped probe memo once
  stated the table was `cleared by queueMicrotask` UNCONDITIONALLY, applying the
  CLJS module-holder mechanism to a JVM that has no microtask at all — there the
  table is a thread-local render scope discarded synchronously when the render
  thunk returns. The prose was reconciled per-host in 718a8dea16; this census is
  the regression pin that commit did not carry.

  The law, and why the mechanisms legitimately differ: probes may share WITHIN
  one slice, but no holder or table survives PAST that host boundary into the
  next slice. What BOUNDS a slice is per-host because the hosts' scheduling
  models genuinely differ. On the JVM sharing ends with the synchronous render
  thunk (no microtask queue, a thread-local scope). On CLJS the whole
  host-microtask window is ONE slice: because `queueMicrotask` is FIFO, a
  genuinely-later render interposed before the checkpoint REUSES the still-
  installed holder — a bounded within-window economy the inverse-FIFO fixture
  proves (rf2-2g7pxq) — so the obsolete framing of the CLJS slice as one
  'synchronous execution slice' (nothing surviving into a 'later render slice')
  is false and this census now REJECTS it. Both reach the same law.

  This census pins, in BOTH authoritative documents:

    - each distinguishes the JVM thread-local render scope (sharing ends with
      the synchronous render thunk) from the CLJS module holder whose sharing
      MAY span later callbacks within the bounded host-microtask window;
    - neither applies microtask lifetime to the JVM — the JVM span may name a
      microtask ONLY to deny it;
    - neither carries the obsolete synchronous-slice / later-render-slice claim,
      and CLJS AFFIRMS the within-window reuse;
    - each states the shared host-boundary law, attributed to BOTH hosts.

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

(def ^:private both-hosts-law-re
  "The reconciliation's substance: ONE law, attributed to BOTH hosts."
  #"(?i)both hosts[\s\S]{0,240}?enforce the (?:same|one) law")

(def ^:private within-slice-sharing-re
  #"(?i)(?:shar\w+[^.]{0,48}within (?:one|a) slice|within (?:one|a) slice[^.]{0,48}shar\w+)")

(def ^:private next-slice-boundary-re
  "The CORRECTED boundary law: sharing ends at the HOST boundary — the JVM
  thunk's return, the CLJS microtask checkpoint — with nothing surviving into
  the NEXT slice. This REPLACES the obsolete unconditional 'no ... survives
  into a later render slice' claim, which a bounded within-window reuse
  (the inverse-FIFO fixture, rf2-2g7pxq) contradicts."
  #"(?i)no (?:holder|table)[^.]{0,80}?survives? past[^.]{0,80}?into the next slice")

(def ^:private obsolete-synchronous-slice-re
  "The obsolete claim this bead retires: the CLJS memo scoped to the 'current
  synchronous execution slice', or the universal 'no ... survives into a later
  render slice'. Both frame the CLJS slice as one SYNCHRONOUS call and so deny
  the within-window reuse the inverse-FIFO fixture proves (rf2-2g7pxq) — a
  genuinely-later render interposed before the checkpoint reuses the holder.
  Its PRESENCE (not absence) is the violation."
  #"(?i)synchronous execution slice|into a later render slice")

(def ^:private cljs-window-reuse-re
  "CLJS must AFFIRM the bounded within-window later-render reuse — the substance
  the inverse-FIFO fixture proves, not merely the checkpoint marker: the whole
  host-microtask window is one slice, so a genuinely-later render interposed
  before the checkpoint REUSES the still-installed holder."
  #"(?i)host-microtask window[\s\S]{0,240}?reuse")

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

      ;; CLJS must AFFIRM the bounded within-window later-render reuse — the
      ;; substance the inverse-FIFO fixture proves (rf2-2g7pxq), not merely the
      ;; presence of the checkpoint marker. The old census false-greened by
      ;; requiring the checkpoint word while its LAW still denied the reuse.
      (and cljs (not (re-find cljs-window-reuse-re cljs)))
      (conj :cljs/window-reuse-missing)

      ;; The shared law, in both halves, attributed to both hosts.
      (not (re-find both-hosts-law-re section))
      (conj :law/not-attributed-to-both-hosts)

      (not (re-find within-slice-sharing-re section))
      (conj :law/within-slice-sharing-missing)

      ;; THE claim this bead retires: the obsolete synchronous-slice / later-
      ;; render-slice framing. Its PRESENCE is the violation — it contradicts
      ;; the executable within-window reuse proof.
      (re-find obsolete-synchronous-slice-re section)
      (conj :law/obsolete-synchronous-slice-claim)

      ;; The corrected host-boundary law, in place of the old universal.
      (not (re-find next-slice-boundary-re section))
      (conj :law/next-slice-boundary-missing))))

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
  (testing "the pre-718a8dea16 Spec 006 text names no host, generalises the
            CLJS microtask, AND carries the obsolete synchronous-slice claim, so
            the census must reject it"
    (is (= #{:jvm/span-missing
             :cljs/span-missing
             :law/not-attributed-to-both-hosts
             :law/obsolete-synchronous-slice-claim
             :law/next-slice-boundary-missing}
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
  [;; THE drift this bead exists for: restore the obsolete synchronous-slice /
   ;; later-render-slice claim while KEEPING every checkpoint marker
   ;; (thread-local, queueMicrotask, the discard phrase). The old census could
   ;; not catch this — it demanded the checkpoint markers but not their
   ;; compatible meaning, so this exact edit false-greened.
   {:label   "the obsolete synchronous-execution-slice claim is restored, checkpoint markers retained"
    :match   "The whole host-microtask window is therefore one CLJS slice"
    :replace "The table is scoped to the current synchronous execution slice"
    :expect  :law/obsolete-synchronous-slice-claim}
   {:label   "the CLJS within-window reuse economy is dropped"
    :match   "finds the holder still installed and reuses it"
    :replace "opens a fresh table"
    :expect  :cljs/window-reuse-missing}
   {:label   "the corrected host-boundary law is dropped"
    :match   "no holder or table survives past that boundary into the next slice"
    :replace "the table stays reusable"
    :expect  :law/next-slice-boundary-missing}
   {:label   "the JVM span gains microtask lifetime"
    :match   jvm-microtask-denial
    :replace "the table is cleared by `queueMicrotask` at the microtask checkpoint"
    :expect  :jvm/microtask-lifetime-applied}
   {:label   "the JVM span loses its synchronous discard"
    :match   jvm-synchronous-discard
    :replace "eventually"
    :expect  :jvm/synchronous-discard-missing}
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

;; ---------------------------------------------------------------------------
;; The retired-wording sources (rf2-cpalh)
;; ---------------------------------------------------------------------------
;;
;; Two authority-SHAPED documents still state the retired universal wording. They
;; are deliberately NOT censused by `slice-memo-lifetime-violations` above: they
;; quote the retired claim verbatim as design history, and correcting it in place
;; would rewrite archaeology as though it had always said something else.
;;
;;   - `drafts/spec-006-observation-port-amendment.md` — all three of its edits
;;     LANDED in Spec 006 (§The internal observation port et al), and the merged
;;     section was then amended per-host by rf2-er64a. Its merge condition was
;;     satisfied and then overtaken, so it is superseded, not pending.
;;   - `spikes/s3-ownership-report.md` — a dated experimental record whose 55/55
;;     fixtures stand and whose §5 model remains the port's binding shape source.
;;     Exactly ONE inference in it was disproven by the inverse-FIFO proof; the
;;     report is corrected at that claim, not superseded wholesale.
;;
;; What they must carry instead is an unmistakable marker that reaches a reader
;; BEFORE the retired prose does — including one who deep-links straight to the
;; carrying section. That is what this arm pins.

(def ^:private retired-wording-sources
  [{:label   "ai/findings/new-substrate-synthesis/drafts/spec-006-observation-port-amendment.md"
    :path    "ai/findings/new-substrate-synthesis/drafts/spec-006-observation-port-amendment.md"
    :section "### The slice-scoped probe memo"}
   {:label   "ai/findings/new-substrate-synthesis/spikes/s3-ownership-report.md"
    :path    "ai/findings/new-substrate-synthesis/spikes/s3-ownership-report.md"
    :section "### The pass token"}])

(def ^:private retired-marker-re
  "The supersession / correction marker. Either spelling is unmistakable at a
  glance; both must precede the retired prose they guard."
  #"(?i)\*\*Status:\s*SUPERSEDED|⚠\s*(?:CORRECTION|SUPERSEDED)|—\s*SUPERSEDED")

(defn retired-source-violations
  "Census ONE retired-wording source; return the SET of violations (empty =
  conformant). Pure over text, so the negative arm can prove it has teeth."
  [text section-heading]
  (let [marker-at   (some-> (re-find retired-marker-re text) (->> (str/index-of text)))
        claim-at    (some-> (re-find obsolete-synchronous-slice-re text)
                            (->> (str/index-of text)))
        section-at  (str/index-of text section-heading)
        after-head  (when section-at (subs text section-at))
        sec-marker  (some-> after-head (->> (re-find retired-marker-re))
                            (->> (str/index-of after-head)))
        sec-claim   (some-> after-head (->> (re-find obsolete-synchronous-slice-re))
                            (->> (str/index-of after-head)))]
    (cond-> #{}
      ;; It must be marked at all, and prominently — within the opening of the
      ;; document, not buried where a scanning reader never reaches it.
      (nil? marker-at)
      (conj :marker/missing)

      (and marker-at (> marker-at 400))
      (conj :marker/not-prominent)

      ;; The marker must not be vacuous: it states the law that replaced the
      ;; retired claim, in the same words the live authorities use.
      (not (re-find cljs-window-reuse-re text))
      (conj :marker/host-window-law-missing)

      ;; ... and points at the document that now carries the contract.
      (not (str/includes? text "spec/006-ReactiveSubstrate.md"))
      (conj :marker/current-authority-unlinked)

      ;; The retired claim must never lead. A reader meets the marker first.
      (and claim-at marker-at (< claim-at marker-at))
      (conj :claim/precedes-marker)

      ;; Deep-link guard: the carrying section must be marked in its own right,
      ;; before its retired prose — the document header is invisible to a reader
      ;; who lands on the anchor.
      (nil? section-at)
      (conj :section/heading-missing)

      (and section-at (nil? sec-marker))
      (conj :section/unmarked)

      (and sec-claim sec-marker (< sec-claim sec-marker))
      (conj :section/claim-precedes-marker))))

(deftest retired-wording-sources-read-as-history-not-direction
  (doseq [{:keys [label path section]} retired-wording-sources]
    (testing label
      (is (= #{} (retired-source-violations (slurp (io/file @root path)) section))
          (str label " preserves the retired slice-memo wording as design "
               "history, so it must carry a prominent supersession/correction "
               "marker — document-level AND on the carrying section — that "
               "states the host-window law and points at Spec 006")))))

(def ^:private pre-cpalh-amendment-draft
  "The ACTUAL opening and carrying section of the amendment draft before this
  bead — the state in which it read as PENDING design direction while stating
  the retired universal wording. The census must reject it."
  "# DRAFT — Spec 006 amendment (R-2): the internal observation port

> **Status: DRAFT — not merged · 2026-07-12.** Target: `spec/006-ReactiveSubstrate.md`.
> Semantics **and shapes final**: spike S-3 has run.

### The slice-scoped probe memo

Probes are ownership-free, so N sibling sites probing the same query during one render
pass would recompute shared derivation parents N times. The port permits one mitigation:
a **slice-scoped pure memo table**. Within one slice, probes share computed derivation
parents; the table dies with the slice. No entry survives into cache state, ownership
state, or a later slice.

**Lifetime (S-3-settled).** There is no public React render-pass token; the table is
scoped to the **current synchronous execution slice**: created lazily on first probe,
cleared by `queueMicrotask`, and belt-and-braces tagged with
`(frame, frame-epoch, registry-epoch)` — invalidated on any mismatch.")

(deftest retired-source-census-rejects-the-unmarked-pre-cpalh-state
  (testing "an unmarked draft stating the retired wording as live direction is
            rejected: no marker at all, no host-window law, and a carrying
            section a deep-link reader would meet unguarded"
    (is (= #{:marker/missing
             :marker/host-window-law-missing
             :section/unmarked}
           (retired-source-violations pre-cpalh-amendment-draft
                                      "### The slice-scoped probe memo"))))
  (testing "dropping the pointer to the current authority is caught on the live text"
    (let [{:keys [path section]} (first retired-wording-sources)
          text (slurp (io/file @root path))]
      (is (contains? (retired-source-violations
                      (drift text "spec/006-ReactiveSubstrate.md" "some other document")
                      section)
                     :marker/current-authority-unlinked)))))
