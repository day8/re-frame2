(ns re-frame.freehand.manifest-diagnostics-cljs-test
  "THE ONE ROSTER THAT IS NOT A SITE ROSTER — `FH-DIAG-003`, the compile-tier
  finding roster the compiled manifest publishes.

  Six of the manifest's seven rosters name a form the compiler READ, and
  `FH-STRUCT-010` makes a whole `{:file :line :column}` source coordinate total
  over their entries. `:diagnostics` is the seventh and it is a different kind of
  thing: its entries are FINDINGS, minted from a node rather than indexed as
  lexical sites. A finding is located by the node's `:tag` and the deterministic
  `:path` that reaches it, under the compiler-minted stable `:sid` its printed
  warning quotes — so it carries no `:source-coord`, and the coordinate law
  cannot govern it. That is not a gap in `FH-STRUCT-010`; it is a second class of
  roster, and this suite is the row that governs it.

  Why the roster exists at all is the SUPPRESSED case. A suppressed finding
  prints nothing — that is what suppression is — so the manifest entry carrying
  the author's `:reason` is its only trace, and what a codebase has waived and
  why is readable from nowhere else (Spec 004D §Compile-tier warnings: *a
  suppressed finding remains a manifest fact carrying its reason*). Before
  rf2-hytu5 published the key, that reason went into the compiler env and
  stopped there.

  Three declarations, because the law distinguishes three cases and a per-entry
  key contract asserted over an empty roster is the loudest green there is: a
  clean declaration (empty roster, never absent), a waived one (a suppressed
  entry, carrying its reason), and a reported one (an unsuppressed entry,
  carrying none). The reported arm prints one `WARNING re-frame.freehand …` line
  at expansion, deliberately: it is the arm that both prints AND records, and
  without it the conditional half of the entry shape would be asserted in one
  direction only.

  Cross-host on purpose. The manifest is built at macro expansion — on the JVM
  for both hosts — but a tool reading `:diagnostics` runs in a browser, so the
  roster has to survive into a ClojureScript build rather than merely exist under
  `clojure -M:test`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]))

(def diag-003 (conf/fixture :FH-DIAG-003))

;; ---------------------------------------------------------------------------
;; The census — one declaration per case the law distinguishes
;; ---------------------------------------------------------------------------

(v/defview clean-panel
  "Nothing the a11y roster has anything to say about."
  {:compiled true}
  [{:keys [title]}]
  [:section.panel [:h2 title]])

(v/defview waived-drag-surface
  "One SUPPRESSED finding. Silent on stderr; the manifest entry carrying the
  author's reason is its only trace."
  {:compiled true}
  [_]
  [:div.canvas
   ^{:rf.ui/suppress
     {:rf.ui.compile/a11y-click-non-interactive
      "drag surface; the keyboard path is the toolbar button"}}
   [:div {:on-click [:canvas/select]} "Canvas"]])

(v/defview unnamed-icon-button
  "One REPORTED finding — a provably nameless icon button. This declaration
  prints its warning at expansion as well as recording it, which is the arm a
  census of suppressed findings alone would never inspect."
  {:compiled true}
  [_]
  [:button {:class "icon"} [:svg {:class "glyph"}]])

(def ^:private by-name
  {:clean-panel         clean-panel
   :waived-drag-surface waived-drag-surface
   :unnamed-icon-button unnamed-icon-button})

;; ---------------------------------------------------------------------------
;; FH-DIAG-003
;; ---------------------------------------------------------------------------

(deftest the-fixture-covers-the-whole-census
  (testing "The table is the guard, so the table is guarded: the fixture names
            exactly the declarations here, and the census carries all three
            cases — an empty roster, a suppressed finding and a reported one —
            so no arm of the law below is asserted over nothing."
    (let [findings (:findings diag-003)
          entries  (mapcat val findings)]
      (is (= (set (keys by-name)) (set (keys findings)))
          "the fixture names every census declaration and no other")
      (is (some (comp empty? val) findings)
          "one declaration is clean, so the empty-rather-than-absent row bites")
      (is (some :suppressed? entries)
          "one finding is suppressed, so the :reason row bites")
      (is (some (complement :suppressed?) entries)
          "one finding is reported, so the absent-:reason row bites too")
      (is (< 1 (count (set (map :id entries))))
          "and the census carries more than one finding id, so the roster is
           read rather than a single constant recognised"))))

(deftest every-declaration-publishes-the-roster-the-fixture-declares
  (testing "Per FH-DIAG-003: the roster is TOTAL over what the compiler
            collected — every finding the declaration produced, in source order,
            with the fields the fixture pins. A roster present but empty is a
            positive claim (`this declaration is clean`); an ABSENT one would be
            silence a reader could not tell from a manifest predating the key."
    (doseq [[nm expected] (:findings diag-003)]
      (let [m     (v/manifest (get by-name nm))
            found (:diagnostics m)]
        (is (contains? m :diagnostics)
            (str nm " — publishes the roster, empty rather than absent"))
        (is (= expected (mapv #(dissoc % :sid) found))
            (str nm " — exactly the declared findings, in source order"))))))

(deftest every-finding-carries-the-keys-a-reader-acts-on
  (testing "Per FH-DIAG-003: the per-entry shape, with the conditional half
            pinned in BOTH directions. `:reason` rides a suppressed entry and
            rides no other — a lost reason and an invented one are the two ways
            this fact can go wrong, and each fails here."
    (let [required (:required-keys diag-003)
          when-sup (:suppressed-keys diag-003)]
      (doseq [[nm view] by-name
              entry     (:diagnostics (v/manifest view))]
        (is (= (cond-> required (:suppressed? entry) (into when-sup))
               (set (keys entry)))
            (str nm " — the entry carries exactly the keys its suppression
                  state requires"))
        (is (and (string? (:sid entry)) (not (str/blank? (:sid entry))))
            (str nm " — under the compiler-minted stable site id its warning
                  quotes"))))))

(deftest a-finding-is-located-by-its-node-not-by-a-source-coordinate
  (testing "The reason this row exists beside FH-STRUCT-010 rather than inside
            it. A finding is minted from a node, so `:tag` and `:path` are what
            locate it; it is not a lexical site and carries no `:source-coord`.
            FH-STRUCT-010's per-entry coordinate law governs the six site
            rosters and excludes this one — and that exclusion is only honest
            while the absence is asserted rather than assumed."
    (doseq [[nm view] by-name
            entry     (:diagnostics (v/manifest view))]
      (is (not (contains? entry :source-coord))
          (str nm " — no :source-coord: a synthetic coordinate would satisfy "
               "FH-STRUCT-010's law and state nothing true"))
      (is (vector? (:path entry))
          (str nm " — the deterministic path that reaches the node"))
      (is (keyword? (:tag entry))
          (str nm " — and the tag of the node the finding was minted from")))))
