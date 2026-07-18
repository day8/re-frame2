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
  `reactive-slice-memo-incarnation-cljs-test` (the CLJS module holder).

  This namespace carries a SECOND census, generalised out of the first. The
  retired-wording arm (rf2-cpalh) pins a supersession marker on the two named
  documents that state the retired slice-memo wording; rf2-5jbev found that the
  mechanism which let those two go stale is broader than those two files, and
  the marker-PRESENCE half of that arm generalises to the directory. See
  §The synthesis standing-marker census below."
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

;; ---------------------------------------------------------------------------
;; The synthesis standing-marker census (rf2-5jbev)
;; ---------------------------------------------------------------------------
;;
;; The arm above pins TWO named files. The mechanism that let them go stale is
;; wider than those two: `drafts/`, `spikes/` and `reviews/` hold authority-
;; SHAPED, git-tracked documents that NO census enumerates AS A CLASS. The #6310
;; compiler-model-authorities census (`guide-truth-jvm-test`) names eight
;; specific paths and none of these directories; `check_doc_slugs.py
;; --synthesis-only` reaches `drafts/` for LINK VALIDITY only and does not reach
;; `spikes/` or `reviews/` at all; `check_synthesis_plan_authority.py` is a
;; stage-token gate; and mkdocs does not build `ai/`. So a document could state a
;; retired model indefinitely and nothing objected — measured three times in one
;; day: `drafts/spec-004-interim-amendment.md` sat at "final draft / merge
;; condition: immediately" carrying wording Spec 004 had retired (rf2-3b931);
;; two more were found by rf2-cpalh; and rf2-rzzlw repaired six broken references
;; in `reviews/09-review-disposition.md` that no gate would have caught — in a
;; relocation that itself passed green.
;;
;; THE ROSTER IS THE DIRECTORY LISTING, never a maintained file list. A named-file
;; roster is precisely the shape that let these directories fall out of coverage
;; in the first place: it cannot enumerate a document nobody remembered to add.
;; A file landing tomorrow is censused the day it lands.
;;
;; WHAT IT PINS IS PRESENCE, NOT CONTENT. The dispositions here are genuinely not
;; uniform, and a guard demanding one marker vocabulary would force a document
;; into the wrong shape. Three real ones, all of which must pass:
;;
;;   1. superseded, anchors GONE — `drafts/spec-004-interim-amendment.md` targeted
;;      a spec revision that no longer exists (rf2-3b931);
;;   2. superseded, anchors PRESENT but edits ALREADY LANDED —
;;      `drafts/spec-006-observation-port-amendment.md`, where re-applying would
;;      duplicate them (rf2-cpalh). Opposite reason, same disposition;
;;   3. NOT superseded — `spikes/s3-ownership-report.md` is a dated experimental
;;      record whose §5 model is still the binding shape source cited by live
;;      Spec 006. Retiring it would kill a document the spec depends on;
;;      rewording it would falsify the experimental record. It carries a scoped
;;      CORRECTION instead (rf2-cpalh).
;;
;; So the census asks one question — "does this document declare its standing
;; where a reader meets it?" — and answers it from a small union of the marker
;; vocabularies actually in use. It never reads what the marker CLAIMS; a wrong
;; status is out of scope by ruling, and belongs to whoever edits the document.
;;
;; The accepted vocabulary is per-directory, because the directories differ in
;; kind. `drafts/` documents are pending DIRECTION — an undated draft is
;; actionable, so a bare date does not declare standing there and a status or
;; supersession marker is required. `spikes/` and `reviews/` are dated RECORDS by
;; nature: "written on date D by reviewer R" is the whole of their standing, and
;; demanding `**Status:**` of a 2026-07-11 adversarial review would be the
;; wrong-shape failure above. They accept a record marker as well.

(def ^:private synthesis-root "ai/findings/new-substrate-synthesis")

(def ^:private standing-marker-classes
  "The marker vocabularies actually in use across the censused tree, as a
  presence test each. Line-anchored (a blockquote `> ` prefix allowed) so a
  passing mention of the word `draft` in running prose cannot satisfy the
  census — a marker is a document's own leading declaration, not a word in it.

    :status       `**Status:** DRAFT`, `**Status: SUPERSEDED — …**`, `**Status:**
                  DIRECTED`, `**Status:** prep batch`, `**Status:** contract draft`
    :supersession a leading banner retiring or qualifying the document:
                  `**[SUPERSEDED NOTE — …]**`, `**HISTORICAL — EXECUTED …**`,
                  `**⚠ CORRECTION (…)**`, `**[DELTA BANNER — …]**`,
                  `**DRAFT — merges at S5.**`
    :record       the dated-record header of a spike or review: `**Date:** …`,
                  `**Written:** …`, `**When:** …`, optionally preceded on its line
                  by one other bold field label (`**Worker:** … · **Date:** …`)."
  {:status       #"(?m)^>?[ \t]*\*\*\[?Status:"
   :supersession #"(?m)^>?[ \t]*\*\*[ \t]*\[?[ \t]*(?:⚠[ \t]*)?(?:SUPERSEDED|HISTORICAL|RETIRED|CORRECTION|DELTA BANNER|DRAFT)\b"
   :record       #"(?m)^>?[ \t]*(?:\*\*[^*\n]{1,40}\*\*[^*\n]{0,40})?\*\*(?:Date|Written|When):\*\*"})

(def ^:private censused-directories
  "The censused directories and the marker classes each accepts.

  Post-S7 `drafts/` is deleted under `rf2-vxgfnd.99.1` while `reviews/` and
  `spikes/` survive — they become the only synthesis paths left, and so the only
  ones still needing this. A vanished directory therefore REDS (`:roster/empty`)
  rather than silently censusing nothing: dropping a row here must be a
  deliberate, visible edit in the wave that deletes it, never a vacuous pass."
  [{:dir "drafts"  :accepts #{:status :supersession}}
   {:dir "spikes"  :accepts #{:status :supersession :record}}
   {:dir "reviews" :accepts #{:status :supersession :record}}])

(defn- first-section-index
  "Index of the document's first `##` section heading, or nil. Matched with an
  explicit matcher rather than `index-of` on the matched text, so an inline
  `## ` earlier in the prose cannot report a false position."
  [text]
  (let [m (re-matcher #"(?m)^##[^#]" text)]
    (when (.find m) (.start m))))

(defn- preamble
  "The document's opening — everything BEFORE its first `##` section heading.

  This is the directory-level analogue of the retired-wording arm's deep-link
  guard. That arm can name the section carrying the retired prose; a directory
  census cannot, and inferring it would mean validating content. What generalises
  is the reader-meets-it-first property: a marker in the preamble sits ahead of
  every anchor-linkable section in the document, so no deep link can land past
  it. A marker below the first heading is one a scanning or deep-linking reader
  can miss entirely — which is how an authority-shaped document gets acted on."
  [text]
  (if-let [at (first-section-index text)]
    (subs text 0 at)
    text))

(defn standing-marker-violations
  "Census ONE document's text against the classes its directory `accepts`;
  return the SET of violations (empty = conformant). Pure over text, so the
  negative arm can prove it has teeth."
  [text accepts]
  (let [declared? (fn [region]
                    (boolean (some (fn [[class re]]
                                     (and (contains? accepts class)
                                          (re-find re region)))
                                   standing-marker-classes)))
        anywhere  (declared? text)]
    (cond-> #{}
      (not anywhere)
      (conj :marker/missing)

      (and anywhere (not (declared? (preamble text))))
      (conj :marker/below-first-section))))

;; ---------------------------------------------------------------------------
;; Positive arm — every tracked document in the censused directories
;; ---------------------------------------------------------------------------

(defn- directory-roster
  "The roster: the DIRECTORY LISTING of `dir`'s markdown files, sorted for a
  stable failure order. Never a maintained list of names."
  [dir]
  (let [d (io/file @root synthesis-root dir)]
    (->> (.listFiles d)
         (filter #(.isFile ^java.io.File %))
         (filter #(str/ends-with? (.getName ^java.io.File %) ".md"))
         (sort-by #(.getName ^java.io.File %)))))

(deftest every-authority-shaped-synthesis-document-declares-its-standing
  (doseq [{:keys [dir accepts]} censused-directories]
    (testing (str synthesis-root "/" dir "/")
      (let [roster (directory-roster dir)]
        (is (seq roster)
            (str synthesis-root "/" dir "/ censuses no documents — the directory "
                 "is missing or empty. If the S7 removal wave (rf2-vxgfnd.99.1) "
                 "deleted it, drop its row from `censused-directories` in the "
                 "same change; do not let the census pass vacuously"))
        (doseq [^java.io.File f roster]
          (testing (.getName f)
            (is (= #{} (standing-marker-violations (slurp f) accepts))
                (str synthesis-root "/" dir "/" (.getName f) " is authority-shaped "
                     "and tracked, so it must declare its standing — a status, a "
                     "supersession/correction banner, or (in spikes/ and reviews/) "
                     "a dated-record header — in its opening, before the first "
                     "`##` heading. What the marker SAYS is not censused"))))))))

(deftest the-three-real-dispositions-pass-the-standing-census
  (testing "the census admits every disposition actually found, so no document is
            forced into the wrong shape"
    (doseq [[path accepts disposition]
            [["drafts/spec-004-interim-amendment.md" #{:status :supersession}
              "superseded, anchors GONE (rf2-3b931)"]
             ["drafts/spec-006-observation-port-amendment.md" #{:status :supersession}
              "superseded, anchors PRESENT but edits already landed (rf2-cpalh)"]
             ["spikes/s3-ownership-report.md" #{:status :supersession :record}
              "NOT superseded — a dated experimental record still cited as the
               binding shape source by live Spec 006 (rf2-cpalh)"]]]
      (testing (str path " — " disposition)
        (is (= #{} (standing-marker-violations
                    (slurp (io/file @root synthesis-root path))
                    accepts)))))))

;; ---------------------------------------------------------------------------
;; Negative arm — the census must REJECT an undeclared document
;; ---------------------------------------------------------------------------

(defn- strip-standing-markers
  "Remove every recognised standing marker from `text`, asserting the strip
  actually landed. A no-op would leave the live (green) document in place and
  make the negative arm vacuously pass — the same trap `drift` guards above."
  [text]
  (let [stripped (reduce (fn [acc re] (str/replace acc re "Note "))
                         text
                         (vals standing-marker-classes))]
    (when (= stripped text)
      (throw (ex-info "marker strip did not mutate the live document — the
                       standing-marker vocabulary no longer matches the tree"
                      {:head (subs text 0 (min 200 (count text)))})))
    stripped))

(deftest standing-census-rejects-an-undeclared-document-in-every-directory
  (testing "strip the marker off a LIVE document of each censused directory and
            the census must reject it — proving the arm has teeth in all three,
            not only where a marker happens to be easy to find"
    (doseq [{:keys [dir accepts]} censused-directories
            ^java.io.File f (directory-roster dir)
            :let [text (slurp f)]
            ;; Only CONFORMANT documents are strippable, and only they make the
            ;; no-op guard in `strip-standing-markers` meaningful. An already-
            ;; undeclared document is the positive arm's business, and would
            ;; make this one throw on a strip that could not land.
            :when (= #{} (standing-marker-violations text accepts))]
      (testing (str dir "/" (.getName f))
        (is (contains? (standing-marker-violations
                        (strip-standing-markers text)
                        accepts)
                       :marker/missing))))))

(deftest standing-census-rejects-a-brand-new-undeclared-document
  (testing "a document added to any censused directory with no standing
            declaration is rejected — the roster is the directory listing, so it
            is censused the day it lands"
    (doseq [{:keys [accepts]} censused-directories]
      (is (= #{:marker/missing}
             (standing-marker-violations
              "# Spec 004 — interim broadening amendment (R-1)\n\nTarget: `spec/004-Views.md`.\n\n## The portability law\n\nA portable view has one deterministic representation.\n"
              accepts))))))

(deftest standing-census-rejects-a-marker-a-deep-link-reader-would-miss
  (testing "a marker BELOW the first `##` heading sits past an anchor-linkable
            section, so a deep-linking reader can meet the body first"
    (is (= #{:marker/below-first-section}
           (standing-marker-violations
            "# Spec 004 — interim broadening amendment (R-1)\n\n## The portability law\n\nA portable view has one deterministic representation.\n\n**Status: SUPERSEDED — historical staging material. Do not apply.**\n"
            #{:status :supersession})))))

(deftest standing-census-does-not-accept-a-bare-date-as-draft-direction
  (testing "a dated header declares the standing of a RECORD, not of pending
            direction: `drafts/` must still carry a status or supersession marker"
    (let [dated "# DRAFT — G-10 bundle-baseline methodology\n\n**Date:** 2026-07-12\n\n## Method\n\nAttribute shared chunks.\n"]
      (is (= #{:marker/missing} (standing-marker-violations dated #{:status :supersession})))
      (is (= #{} (standing-marker-violations dated #{:status :supersession :record}))))))

(deftest every-standing-marker-class-is-recognised
  (testing "each accepted vocabulary is pinned by a real example from the tree,
            so a future edit cannot silently drop a class and leave the
            documents that rely on it uncensused"
    (doseq [[class sample]
            [[:status       "**Status:** DIRECTED · 2026-07-16 20:38 AUSEST"]
             [:status       "> **Status: DRAFT — not merged · 2026-07-12.** Closes the 09 item."]
             [:supersession "> **[SUPERSEDED NOTE — 2026-07-16.]** The S3 children were filed as"]
             [:supersession "> **HISTORICAL — EXECUTED (non-operative).** This is a dated audit;"]
             [:supersession "> **⚠ CORRECTION (⟨rf2-cpalh⟩) — one inference in §5 was disproven.**"]
             [:supersession "> **[DELTA BANNER — 2026-07-16, component-library readiness.]**"]
             [:supersession "> **DRAFT — merges at S5.** Drafted 2026-07-12 09:22 AUSEST,"]
             [:record       "**Date:** 2026-07-11"]
             [:record       "**Worker:** S-1/S-4 spike worker · **Date:** 2026-07-11 23:31:52 AUSEST"]
             [:record       "**Written:** 2026-07-11 20:32 AUSEST · Reviewer: independent"]
             [:record       "**When:** 2026-07-14 17:27 AUSEST"]]]
      (testing (str class " — " sample)
        (is (re-find (get standing-marker-classes class) sample))
        (is (= #{} (standing-marker-violations sample #{class})))))))
