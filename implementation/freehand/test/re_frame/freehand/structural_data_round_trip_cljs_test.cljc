(ns re-frame.freehand.structural-data-round-trip-cljs-test
  "The structural tree's DATA rule, held to the round trip it promises.

  Spec 004B: the tree is plain, serialisable data, and EDN print/read
  round-trips it losslessly. Two slots record a value the tree did not
  build — a view boundary's `:props` and an element's `:events` — and the
  rule they share is that the opaque marker occupies a SITE, never a value
  inside one: a prop or handler that IS a function records as
  `{:rf.ui/opaque :fn}`, and a non-data value NESTED inside a recorded
  prop, event vector or options map is refused.

  What that rule turns on is the definition of DATA, and the definition is
  the EDN value grammar exactly — not `coll?` and not `number?`, which are
  questions about a host interface and a host numeric tower:

  - a `defrecord` value answers `map?` and prints `#user.R{…}`, which no
    EDN reader has a tag for. It was accepted, printed, and failed to
    read back.
  - a persistent QUEUE answers a collection predicate on both hosts and
    prints outside EDN on both — `#object[…]` on the JVM, `#queue […]` in
    ClojureScript — and NEITHER host's reader takes the other's. Accepting
    it where it happens to read back would make one declaration answer a
    tree on one host and a refusal on the other.
  - a JVM character IS ordinary EDN — `\\a` prints and reads back — and was
    refused.
  - `##NaN` prints and reads back and is STILL not equal to itself, so a
    tree carrying one is not equal to itself. The tree is an equality
    input: a fingerprint compares one, a structural test asserts on one.
    Print/read alone is not the promise; print/read EQUAL is.

  Every row runs through the public renderer, on whichever host is running
  it, and the refusals are asserted over a COMPILED boundary as well —
  `re-frame.freehand.node/boundary` is the one canonicalizer both front
  ends record props through, so a value one mode refused and the other
  recorded would be a tree that existed only when compiled."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.tree :as tree]))

(v/defview sink
  "A boundary that records whatever prop it is handed."
  [{:keys [label]}]
  [:span label])

(v/defview sink-compiled
  "Its promoted twin — same body, one option map apart."
  {:compiled true}
  [{:keys [label]}]
  [:span label])

(defrecord Box [v])

(def ^:private a-queue
  "A persistent queue: a first-class collection on both hosts, and the
  canonical example of one EDN cannot spell."
  (conj #?(:clj clojure.lang.PersistentQueue/EMPTY
           :cljs (.-EMPTY cljs.core/PersistentQueue))
        :job))

;; ---------------------------------------------------------------------------
;; What the tree accepts, and what accepting it promises
;; ---------------------------------------------------------------------------

(def ^:private accepted
  "Prop values the grammar admits. Each must survive `pr-str` ->
  `read-string` as an EQUAL value on the host running the row."
  (into [["EDN at depth"        {:a [1 2 #{:c}] :b 'sym :c nil :d 1.5}]
         ["a list"              (list 1 2 3)]
         ["a lazy seq"          (map inc [1 2 3])]
         ["a sorted map"        (sorted-map :b 2 :a 1)]
         ["a sorted set"        (sorted-set 3 1 2)]
         ["EDN's tagged literals"
          {:id #uuid "00000000-0000-0000-0000-000000000001"
           :when #inst "1970-01-01T00:00:00.000-00:00"}]
         ["the infinities"      [##Inf ##-Inf]]]
        ;; A JVM character is EDN and reads back; the JVM's own numeric
        ;; tower prints and reads back too. ClojureScript reads `\a` as the
        ;; one-character string it already accepts, so the row has no
        ;; separate CLJS spelling — the host's reader, not the tree's rule.
        #?(:clj [["a character" \a]
                 ["the JVM numeric tower" [1/3 10N 1.5M]]]
           :cljs [])))

(deftest an-accepted-prop-round-trips-through-print-and-read
  (testing "The promise is not that the tree PRINTS. It is that the whole
            tree prints and reads back EQUAL, because every consumer
            downstream compares one: a fingerprint, a structural
            assertion, a cross-host conformance row."
    (is (<= 7 (count accepted)) "the table is not vacuously small")
    (doseq [[note value] accepted]
      (let [t (tree/render [sink {:payload value :label "d"}])]
        (is (= t (edn/read-string (pr-str t)))
            (str note " — the tree prints and reads back equal"))))))

(deftest an-accepted-event-intent-round-trips-too
  (testing "An event vector is recorded VERBATIM and is compared as data by
            a test and dispatched as data at run time, so it carries the
            same obligation a recorded prop does."
    (doseq [[note value] accepted]
      (let [t (tree/render [:div {:on-click [:app/go value]}])]
        (is (= t (edn/read-string (pr-str t)))
            (str note " — in an event intent"))))))

;; ---------------------------------------------------------------------------
;; What it refuses, and why each one is not data
;; ---------------------------------------------------------------------------

(def ^:private refused
  "Values that may not be recorded, each with the reason it is not data.
  Every one is refused with the walk's EXISTING malformed-tree
  diagnostic — no new id, and no second tree format."
  [["a queue — a collection whose EDN status differs by host" a-queue]
   ["a queue nested in a map"                   {:jobs a-queue}]
   ["a queue nested in a vector"                [:a [:b a-queue]]]
   ["a record — map?, and no EDN spelling"      (->Box 1)]
   ["a record nested at depth"                  {:a {:b (->Box 1)}}]
   ["##NaN — reads back, never equal to itself" ##NaN]
   ["##NaN nested at depth"                     {:ratio {:value ##NaN}}]
   ["##NaN inside a vector"                     [1 ##NaN]]
   ["a host object with no EDN spelling"        (atom 1)]])

(deftest a-value-that-cannot-round-trip-is-refused-at-the-site-that-records-it
  (testing "Refuse, not represent: the opaque marker occupies a SITE — a
            prop that IS a callback — because a site is what the grammar
            names. Below the prop key there is no site vocabulary, so a
            value written there that cannot survive the round trip is
            refused where it was recorded rather than silently replaced."
    (doseq [[note value] refused]
      (is (= :rf.error/ui-tree-malformed
             (conf/caught-id #(tree/render [sink {:payload value :label "d"}])))
          (str note " — refused at a boundary prop")))))

(deftest the-same-values-are-refused-in-an-event-intent-and-its-options
  (testing "The rule is one rule across the two slots that record a value
            the tree did not build. An event vector and an options map are
            both recorded verbatim, so both carry it."
    (doseq [[note value] refused]
      (is (= :rf.error/ui-tree-malformed
             (conf/caught-id #(tree/render [:div {:on-click [:app/go value]}])))
          (str note " — refused inside an event intent"))
      (is (= :rf.error/ui-tree-malformed
             (conf/caught-id #(tree/render [:div {:on-click {:event [:app/go value]}}])))
          (str note " — refused inside an options map")))))

(deftest a-compiled-boundary-refuses-exactly-what-an-interpreted-one-does
  (testing "`node/boundary` is the ONE canonicalizer both front ends record
            props through, so the two modes cannot hold different opinions
            about what data is. A value accepted only when compiled would
            be a tree that existed in one mode."
    (doseq [[note value] refused]
      (is (= :rf.error/ui-tree-malformed
             (conf/caught-id #(tree/render [sink-compiled {:payload value :label "d"}])))
          (str note " — refused at a COMPILED boundary")))
    (doseq [[note value] accepted]
      (let [t (tree/render [sink-compiled {:payload value :label "d"}])]
        (is (= t (edn/read-string (pr-str t)))
            (str note " — and accepted, and round-tripping, at a COMPILED boundary"))))))

;; ---------------------------------------------------------------------------
;; Non-vacuity
;; ---------------------------------------------------------------------------

(deftest the-round-trip-assertion-and-the-nan-assertion-can-both-fail
  (testing "Both assertions above are proven able to fail, because an
            assertion that cannot is worth nothing. The controls are
            asserted DIRECTLY on hand-built values — no boundary will
            build either shape any more.

            The host-object control shows print/read really does lose a
            value the tree used to accept; the NaN control shows that
            print/read alone is the WRONG promise, since the value survives
            the trip and the comparison still fails."
    (let [host-tree {:view-id ::control :props {:cell (atom 1)} :rf.ui/tree-version 1}]
      (is (not= host-tree
                (try (edn/read-string (pr-str host-tree))
                     (catch #?(:clj Exception :cljs :default) _ ::unreadable)))
          "a host object in a tree does not survive print/read"))
    (let [nan-tree {:view-id ::control :props {:ratio ##NaN} :rf.ui/tree-version 1}
          read-back (edn/read-string (pr-str nan-tree))]
      (is (= "##NaN" (pr-str (get-in read-back [:props :ratio])))
          "##NaN really does print and read back")
      (is (not= nan-tree read-back)
          "and the tree is STILL not equal to itself — which is the promise print/read alone misses"))
    (is (= :rf.error/ui-tree-malformed
           (conf/caught-id #(tree/render [sink {:payload ##NaN :label "d"}])))
        "so the renderer refuses it")))

;; ---------------------------------------------------------------------------
;; The scope fence
;; ---------------------------------------------------------------------------

(deftest the-site-level-marker-and-the-ordinary-shapes-are-unchanged
  (testing "The repair is about which values may be RECORDED. A callback AT
            a prop site still records as the opaque marker, an ordinary
            data prop is still recorded verbatim, and the error boundary's
            template option is still not recorded at all."
    (let [t (tree/render [sink {:on-pick (fn [_] nil) :title "Details" :label "d"}])]
      (is (= {:rf.ui/opaque :fn} (get-in t [:props :on-pick]))
          "a callback AT the site is the marker, not a refusal")
      (is (= "Details" (get-in t [:props :title])) "and an ordinary prop is verbatim")
      (is (= t (edn/read-string (pr-str t))) "and the whole tree round-trips"))
    (let [t (tree/render [:div {:on-click (fn [_] nil)}])]
      (is (= {:rf.ui/opaque :fn} (get-in t [:events :on-click]))
          "the same at a handler site")
      (is (= t (edn/read-string (pr-str t))) "and that tree round-trips too"))))
