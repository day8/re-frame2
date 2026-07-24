(ns re-frame.freehand.key-condition-branch-structural-cljs-test
  "A key-condition map carrying a `v/event` BRANCH renders on the structural
  host, in both modes, with the callback branch recorded OPAQUE rather than
  walked as data.

  Spec 004B §The opaque marker: a fn-carried SITE records the mode-neutral
  `{:rf.ui/opaque :fn}`, and the rule reaches every position the grammar
  NAMES a site at. A key-condition map names each exact-key branch as a site
  of its own (Spec 004 §Key-condition event maps), and a branch admits the
  dispatching roster — an event vector, an options map carrying `:event`, or
  a `v/event`. So a `v/event` branch is a fn-carried site like a whole
  handler, and it records the same marker.

  The defect this pins closed: `node/classify-event`'s map arm walked a
  key-condition map's branches as ORDINARY DATA. A `v/event` lowers to a
  `Callback` record, which is not data, so the walk raised
  `:rf.error/ui-tree-malformed` — \"carries a scalar at [\\\"Enter\\\"]\" —
  for a form PR #6769 deliberately admits and dispatches. It fired in BOTH
  modes, because both reach `node/classify-event` at render, so a
  perfectly ordinary `[:input {:on-key-down {\"Enter\" (v/event [e] …)}}]`
  had NO SSR and NO headless render. The whole-handler `v/event` was already
  recorded opaque (rf2-berc2 / #6780); this is the same treatment for a
  branch value.

  Runs on both hosts: the structural tree is `.cljc` and its recorded value
  is asserted against one expected map in two runtimes, exactly as the rest
  of the structural corpus is. The JVM structural path is the one the bead
  requires proved — `clojure -M:test` runs this file — and the compiled twin
  reaches the SAME `node/classify-event` at render, so the marker is settled
  in one place for both front ends."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.tree :as tree]))

;; ---------------------------------------------------------------------------
;; The two front ends, one body
;; ---------------------------------------------------------------------------
;;
;; Written the same characters in each declaration — a callback branch beside
;; a DATA branch — so the callback records opaque while the vector stays
;; verbatim, and both facts are asserted in both modes from one shape.

(v/defview key-map-interpreted
  [_]
  [:input {:on-key-down {"Enter"  (v/event [e] [:app/accept e])
                         "Escape" [:app/close]}}])

(v/defview key-map-compiled
  {:compiled true}
  [_]
  [:input {:on-key-down {"Enter"  (v/event [e] [:app/accept e])
                         "Escape" [:app/close]}}])

(def ^:private expected
  "The ONE structural tree both declarations answer for their `:input`, with
  the root's version stripped so the boundary wrappers compare. The `\"Enter\"`
  branch is a `v/event`, a fn-carried site, so it records the mode-neutral
  opaque marker; the `\"Escape\"` branch is an event vector, data, so it is
  recorded verbatim."
  {:tag :input
   :events {:on-key-down {"Enter"  {:rf.ui/opaque :fn}
                          "Escape" [:app/close]}}})

(defn- rendered-input
  "The `:input` node a declaration produced — the sole child of its view
  boundary — with nothing about the boundary itself in view."
  [view]
  (first (:children (tree/render [view {}]))))

(deftest a-v-event-branch-renders-on-the-structural-host-in-both-modes
  (testing "The acceptance case, pinned as the ACTUAL recorded tree rather
            than an absence of a throw. A test asserting `no error` would be
            green against a version that dropped the whole `:on-key-down`
            entry; asserting the events map proves the branch is RECORDED,
            opaque, beside its data sibling."
    (is (= expected (rendered-input key-map-interpreted)) "interpreted")
    (is (= expected (rendered-input key-map-compiled)) "compiled — the same tree")
    (is (= (rendered-input key-map-interpreted) (rendered-input key-map-compiled))
        "and the two modes agree node-for-node, which is the whole claim")))

(deftest the-callback-branch-is-opaque-and-the-data-branch-is-verbatim
  (testing "The two branch verdicts, stated on their own so neither can be
            lost inside the whole-tree comparison. A `v/event` branch is a
            site whose spelling is testable and whose behaviour is not, so it
            records the marker; an event vector is data and is recorded as it
            was written, placeholders and all."
    (doseq [[mode view] [["interpreted" key-map-interpreted]
                         ["compiled"    key-map-compiled]]]
      (let [branches (get-in (rendered-input view) [:events :on-key-down])]
        (is (= {:rf.ui/opaque :fn} (get branches "Enter"))
            (str mode " — the v/event branch is opaque, not walked as data"))
        (is (= [:app/close] (get branches "Escape"))
            (str mode " — the data branch is recorded verbatim"))))))

;; ---------------------------------------------------------------------------
;; The site rule still reaches INSIDE a branch
;; ---------------------------------------------------------------------------
;;
;; Recording a callback branch opaque must not turn a branch into a
;; free-for-all: the marker occupies the SITE, and a non-data value NESTED
;; inside a branch's own data — an event vector or an options map — is still
;; refused, at the branch that recorded it (Spec 004B §Data). These run the
;; INTERPRETED structural walk over a raw form, because the offending value
;; is a runtime host object no compiled build would carry as a literal.

(def ^:private a-host-object
  "An atom: a value with no EDN spelling, the canonical non-data leaf."
  (atom 1))

(def ^:private refused-branches
  "One key-condition branch per way a non-data value can hide inside one,
  each still refused with the walk's EXISTING malformed-tree id."
  [["a non-data value inside a branch's event vector"
    [:input {:on-key-down {"Enter" [:app/go a-host-object]}}]]
   ["a non-data value inside a branch's options map"
    [:input {:on-key-down {"Enter" {:event [:app/go a-host-object]}}}]]
   ["a non-data value nested at depth in a branch"
    [:input {:on-key-down {"Enter" [:app/go {:cell a-host-object}]}}]]])

(deftest a-non-data-value-inside-a-branch-is-still-refused
  (testing "The marker is for the SITE, never for a value inside it. A
            branch is recorded verbatim wherever it is data, so a host
            object riding inside a branch's intent breaks the round trip the
            tree promises and is refused at the branch — the same rule, and
            the same id, the whole-handler options map is held to."
    (doseq [[note form] refused-branches]
      (is (= :rf.error/ui-tree-malformed
             (conf/caught-id #(tree/render form)))
          (str note " — refused, not silently recorded")))))

(deftest the-refusal-assertion-is-not-vacuous
  (testing "The rows above are proven able to PASS as well as fail: a branch
            whose vector is data all the way down renders, so the refusal is
            about the non-data value and not about the shape of a branch."
    (is (= conf/no-throw
           (conf/caught-id #(tree/render [:input {:on-key-down {"Enter" [:app/go 1 "ok"]}}])))
        "a data branch renders — the refusal above is not refusing every branch")
    (is (= {:on-key-down {"Enter" [:app/go 1 "ok"]}}
           (:events (tree/render [:input {:on-key-down {"Enter" [:app/go 1 "ok"]}}])))
        "and it is recorded as the data it is")))
