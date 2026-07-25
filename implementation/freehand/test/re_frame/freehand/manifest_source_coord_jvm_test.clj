(ns re-frame.freehand.manifest-source-coord-jvm-test
  "SOURCE COORDINATES RIDE EACH SITE — asserted through the public
  `v/manifest`, on two site kinds at two known positions.

  A roster's cardinality says a view has three subscriptions; a roster's
  coordinates say WHICH THREE LINES. Only the second is actionable: a
  diagnostic that can name the site a capability came from is a thing a
  reader or an agent can act on, and one that can only name the view is
  not. So the manifest's promise (Spec 004D §The finite manifest —
  \"source coordinates ride each site so a manifest fact and a build-log
  line name the same lexical position\") is proven here on the shape the
  public read actually returns, not on the analyzer's internal index.

  ## The two halves of a TOTAL coordinate

  Clojure's reader anchors LISTS and not vectors, so the two sites below
  are deliberately of different reader provenance:

    - the `(v/event …)` handler is a list, so it locates ITSELF and the
      manifest reports its own line and column;
    - the `[coord-child …]` crossing is a vector the reader anchored
      nowhere, so the site INHERITS the enclosing declaration's position.

  Both entries carry a coordinate, and neither carries an invented one.
  That is the whole discipline: the worst answer a manifest gives is
  \"somewhere in this declaration\", which is a place the site really is —
  never a line the source would not agree with.

  ## Why JVM-only, and what the cross-host suite asserts instead

  The reader is the variable. `clojure.lang.LispReader` anchors lists
  alone; ClojureScript reads the same `.cljc` through `tools.reader`,
  which anchors every collection — so a vector-formed site's coordinate is
  narrower under a browser build than under a JVM one. That is CORRECT,
  not drift: the manifest names the position its own compile read, and the
  build-log line it must agree with came from the same reader. A single
  cross-host table of exact coordinates would therefore have to assert
  something false on one host. The host-neutral half of the contract —
  that every roster entry of an ANCHORED declaration carries a well-formed
  `:source-coord` at all — is `FH-STRUCT-010`, asserted on both hosts in
  `re-frame.freehand.manifest-census-cljs-test`; the declaration nothing
  anchored, whose entries omit the field, is
  `re-frame.freehand.manifest-total-or-absent-coord-jvm-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]))

(v/defview coord-child
  "The crossing's target. Nothing about it is under test; it exists so the
  subject below has an internal boundary to mount."
  {:compiled true}
  [_]
  [:span.child])

;; The subject. Its own declaration form is the anchor every expectation
;; below is expressed against — offsets, not absolute lines, because a
;; test that pinned line 74 would be pinning this FILE rather than the
;; contract, and would go red the day someone adds a paragraph above it.
(v/defview coord-probe                                    ; the declaration anchor
  "Two manifest sites of different reader provenance."
  {:compiled true}
  [{:keys [caption]}]
  [:div.probe
   [:button.go {:on-click (v/event [_] [:probe/pressed])} ; a LIST: locates itself
    caption]
   [coord-child {}]])                                     ; a VECTOR: inherits

(def ^:private declaration
  "The subject's own line and column, read from the public descriptor.

  Not its `:file`: the descriptor resolves the declaring file to an
  absolute path for an editor to open, while a manifest site names the
  classpath-relative path the compiler read — two true answers to two
  different questions, so [[source-file]] is stated rather than borrowed."
  (select-keys (:source (v/describe coord-probe)) [:line :column]))

(def ^:private source-file
  "This file, as the compiler names it."
  "re_frame/freehand/manifest_source_coord_jvm_test.clj")

(def ^:private manifest (v/manifest coord-probe))

;; Offsets from the declaration's line, counted in the source above. The
;; event handler is the sixth line of the declaration form; the crossing
;; inherits, so its offset is zero by contract rather than by counting.
(def ^:private event-line-offset 5)
(def ^:private event-column 27)

(deftest a-list-anchored-site-reports-its-own-position
  (testing "the (v/event …) handler is a list, so the reader anchored it and
            the manifest reports the handler's OWN line and column — the
            narrowest true position available, which is what makes the fact
            actionable"
    (let [[entry :as roster] (:events manifest)]
      (is (= 1 (count roster)) "one event site, so the row below is unambiguous")
      (is (= {:file   source-file
              :line   (+ (:line declaration) event-line-offset)
              :column event-column}
             (:source-coord entry))
          "the handler's own line and column, in the declaring file"))))

(deftest an-unanchored-site-inherits-the-declaration
  (testing "the [coord-child …] crossing is a vector, which this host's reader
            anchors nowhere. The site inherits the enclosing declaration's
            position rather than inventing one or omitting the field: a
            coordinate is TOTAL, and the widest true statement beats a
            precise false one"
    (let [[entry :as roster] (:crossings manifest)]
      (is (= 1 (count roster)) "one crossing, so the row below is unambiguous")
      (is (= (assoc declaration :file source-file)
             (:source-coord entry))
          "exactly the declaration's coordinates — inherited, not fabricated")
      (is (= :re-frame.freehand.manifest-source-coord-jvm-test/coord-child
             (:view-id entry))
          "and the crossing's existing facts are untouched by the addition"))))

(deftest the-two-site-kinds-are-distinguishable
  (testing "the point of per-site coordinates: two sites in ONE declaration
            name two different positions. A manifest whose entries all
            reported the declaration would pass every totality check and
            still be unable to say where anything is"
    (let [event-coord    (:source-coord (first (:events manifest)))
          crossing-coord (:source-coord (first (:crossings manifest)))]
      (is (not= event-coord crossing-coord))
      (is (< (:line crossing-coord) (:line event-coord))
          "and the anchored site really is further into the declaration"))))
