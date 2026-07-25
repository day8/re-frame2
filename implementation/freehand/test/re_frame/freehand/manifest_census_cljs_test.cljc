(ns re-frame.freehand.manifest-census-cljs-test
  "WHAT COMPILATION BUYS, MADE COUNTABLE — the static manifest and the
  capability-elision verdict, proven over a census of known cardinality on
  BOTH hosts.

  A compiled declaration carries a manifest: the finite rosters its
  analysis makes statically knowable — its subscription, event, slot,
  frame-op and crossing sites, and the capability set they union to. From
  those rosters follows the one thing compilation buys that interpretation
  cannot: a view proven to carry no reactive site OMITS the reactive
  ViewCell shell. The interpreted shell always observes context; compiled
  elision is earned by proof, and the proof is the manifest.

  Three claims, on both hosts:

  1. **The rosters match the sites** — `FH-STRUCT-010`. Each census view's
     manifest reports exactly the sites its body carries; a manifest that
     over-reported would be claiming evidence it lacks, and one that
     under-reported would hide a reactive site.
  2. **Every entry is source-locatable** — `FH-STRUCT-010` again. A count
     of three subscriptions that cannot say which three lines is a fact
     nothing can act on, so each entry carries the required keys and a
     whole `:source-coord`. The coordinate is TOTAL — a site the reader
     anchored nowhere inherits its declaration's position — which is why
     this half is host-neutral even though the exact positions are not
     (`re-frame.freehand.manifest-source-coord-jvm-test` pins those).
     Only two of the six rosters are non-empty over this census, so the
     row asserted here would pass over nothing on the other four:
     `re-frame.freehand.manifest-roster-coverage-cljs-test` owns the
     non-vacuity table that covers all six, and the entry counts that
     were once hard-coded here are declared in the fixture it reads.
  3. **The omitted count is an integer** — `FH-DIAG-002`. Over the census,
     the number of views that omit the ViewCell is exactly four — asserted
     as an integer, never a threshold (EP-0036 D021: elision is a
     deterministic gate, not timing evidence)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.manifest-views :as mv]))

(def struct-010 (conf/fixture :FH-STRUCT-010))
(def diag-002 (conf/fixture :FH-DIAG-002))

(defn- census-view
  "The declared census view a fixture names by keyword, or a failing
  assertion — a fixture that names a view the census does not hold would
  otherwise pass vacuously."
  [k]
  (get mv/by-name k))

(deftest the-fixtures-cover-the-whole-census
  (testing "Neither fixture asserts over a subset: the worst failure a
            table-driven gate has is an empty or partial table that passes
            confidently. Both fixtures name exactly the census."
    (is (= (set (keys mv/by-name)) (set (keys (:views struct-010))))
        "FH-STRUCT-010 names every census view and no other")
    (is (= (set (keys mv/by-name)) (set (keys (:views diag-002))))
        "FH-DIAG-002 names every census view and no other")))

;; ---------------------------------------------------------------------------
;; FH-STRUCT-010 — the manifest rosters match the declared sites
;; ---------------------------------------------------------------------------

(deftest every-compiled-view-carries-a-manifest-and-an-interpreted-one-does-not
  (testing "A compiled declaration MUST carry a manifest — it has an
            analysis to report — and it is grammar-stamped so a reader
            knows which grammar produced it."
    (doseq [[nm view] mv/by-name]
      (let [m (v/manifest view)]
        (is (some? m) (str nm " carries a manifest"))
        (is (= :re-frame.freehand/v1 (:grammar m))
            (str nm " names the grammar that produced it"))
        (is (= (:view-id (v/describe view)) (:view-id m))
            (str nm " manifest view-id matches the descriptor"))))))

(deftest manifest-rosters-match-the-declared-sites
  (testing "Per FH-STRUCT-010: each roster's cardinality, the capability
            set, and the elision verdict are a function of the body's
            lexical sites — pinned against hand-countable declarations."
    (doseq [[k expected] (:views struct-010)]
      (let [view (census-view k)
            m    (v/manifest view)]
        (is (some? view) (str k " — the fixture names a declared census view"))
        (is (= (:events expected) (count (:events m)))
            (str k " — event site count"))
        (is (= (:subscriptions expected) (count (:subscriptions m)))
            (str k " — subscription site count"))
        (is (= (:slots expected) (count (:slots m)))
            (str k " — slot site count"))
        (is (= (:frame-ops expected) (count (:frame-ops m)))
            (str k " — frame-op site count"))
        (is (= (:crossings expected) (count (:crossings m)))
            (str k " — crossing site count"))
        (is (= (:capabilities expected) (:capabilities m))
            (str k " — capability set"))
        (is (= (:view-cell expected) (:view-cell m))
            (str k " — ViewCell verdict"))
        (is (= (:reactive? expected) (:reactive? m))
            (str k " — reactive? flag"))
        (when-let [crossing (:crossing expected)]
          (is (= crossing (dissoc (first (:crossings m)) :path :source-coord))
              (str k " — the one crossing is marked with the mode it enters")))))))

(deftest every-roster-entry-is-source-locatable
  (testing "Per FH-STRUCT-010: cardinality is only half the promise. Every
            entry of every roster carries the keys the fixture requires —
            including the `:source-coord` that names the form which produced
            it — so a manifest fact traces back to a line rather than only to
            a view. The coordinate is TOTAL: a site the reader anchored
            nowhere inherits its declaration's position, so the field is
            present on every entry on every host."
    (let [required (:required-keys struct-010)
          coord-ks (:source-coord-keys struct-010)
          findings (:finding-rosters struct-010)]
      ;; The coordinate law below governs the manifest's LEXICAL-SITE rosters —
      ;; the ones whose entries name a form the compiler read, and therefore own
      ;; a source coordinate. Every other manifest key is accounted for, and the
      ;; accounting is the fixture's rather than this file's:
      ;;
      ;;   - `:view-id` / `:grammar` / `:capabilities` / `:reactive?` /
      ;;     `:view-cell` / `:static-facts` are NOT rosters at all — single
      ;;     facts about the declaration. `:static-facts` is the clearest case:
      ;;     the render-static `{:caps :deps}` closure is a summary OVER the
      ;;     sites, and asking it for a per-entry `:source-coord` would be
      ;;     asking a set of capability tokens where its lines are.
      ;;   - `:finding-rosters` names the rosters whose entries are compile-tier
      ;;     FINDINGS rather than sites: a finding is minted from a node, names
      ;;     its `:tag` and `:path`, and carries no `:source-coord` for the law
      ;;     below to govern. They are governed by `FH-DIAG-003`
      ;;     (`re-frame.freehand.manifest-diagnostics-cljs-test`) instead of
      ;;     being excluded here, so the two classes together name every roster
      ;;     key the manifest carries and a new one cannot slip through
      ;;     ungoverned.
      (is (= (into (set (keys required)) findings)
             (set (keys (dissoc (v/manifest (census-view :label))
                                :view-id :grammar :capabilities
                                :reactive? :view-cell :static-facts))))
          "the fixture classifies every roster the manifest carries — site rosters
           under the coordinate law, finding rosters under FH-DIAG-003 — and names
           no roster the manifest does not carry")
      (is (seq findings)
          "the finding class is inhabited, so the classification above is a split
           rather than a restatement")
      (doseq [[nm view] mv/by-name
              [roster ks] required
              entry       (get (v/manifest view) roster)]
        (is (= ks (set (keys entry)))
            (str nm " — every " roster " entry carries exactly the required keys"))
        (is (= coord-ks (set (keys (:source-coord entry))))
            (str nm " — its :source-coord is a whole {:file :line :column}"))
        (is (pos-int? (:line (:source-coord entry)))
            (str nm " — with a real line, never a placeholder"))))))

(deftest reactive-and-elided-are-mutually-exclusive-and-agree
  (testing "The two facets of the verdict cannot disagree: :view-cell
            :elided is exactly :reactive? false, on every view."
    (doseq [[nm view] mv/by-name]
      (let [m (v/manifest view)]
        (is (= (= :elided (:view-cell m)) (not (:reactive? m)))
            (str nm " — :view-cell and :reactive? are one verdict"))))))

;; ---------------------------------------------------------------------------
;; FH-DIAG-002 — the exact omitted-ViewCell count
;; ---------------------------------------------------------------------------

(deftest each-view-earns-the-verdict-the-fixture-declares
  (testing "Per FH-DIAG-002: each census view omits or retains the ViewCell
            exactly as declared — a per-view fact before it is a count."
    (doseq [[k verdict] (:views diag-002)]
      (let [view (census-view k)]
        (is (some? view) (str k " — a declared census view"))
        (is (= verdict (:view-cell (v/manifest view)))
            (str k " — ViewCell verdict"))))))

(deftest the-omitted-view-cell-count-is-exactly-the-declared-integer
  (testing "Per FH-DIAG-002: the number of census views that omit the
            reactive ViewCell shell is an EXACT integer. A threshold would
            pass a census that silently stopped eliding one view; the
            integer does not."
    (let [omitted (count (filter #(= :elided (:view-cell (v/manifest %)))
                                 (vals mv/by-name)))
          present (count (filter #(= :present (:view-cell (v/manifest %)))
                                 (vals mv/by-name)))]
      (is (= (:omitted-view-cell-count diag-002) omitted)
          "exactly four census views omit the ViewCell shell")
      (is (= (count mv/by-name) (+ omitted present))
          "every view is decidable — omitted or present, never neither")
      (is (pos? present)
          "the census is not degenerate: some views DO keep the shell, so
           the omitted count is a discrimination, not a tautology"))))
