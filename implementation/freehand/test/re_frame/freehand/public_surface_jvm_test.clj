(ns re-frame.freehand.public-surface-jvm-test
  "The public boundary of `re-frame.freehand` — that the supported
  surface cannot mint a descriptor, and that a declaration still can.

  Spec 004 and D002 make `v/defview` the ONLY way to create an internal
  mounted boundary. Before the descriptor machinery moved behind
  `re-frame.freehand.descriptor`, the `deftype`'s generated
  `->ViewDescriptor` sat on the public surface: `(v/->ViewDescriptor {})`
  produced a value that passed `v/view?`, classified as `:view`, and
  described itself with nil view-id, nil source and nil lowering. The
  one-declaration rule was reachable-around by autocomplete.

  The check below is deliberately NAME-INDEPENDENT. Asserting that
  `->ViewDescriptor` is absent would pin the mistake we already made;
  what the boundary actually promises is that NO supported var mints a
  descriptor, whatever it is called. So every published one-argument
  function is APPLIED to an arbitrary value and the result classified.
  The `descriptor/declare-view` control proves the probe can see a
  constructor when there is one — without it, an empty result would be
  green for the wrong reason.

  JVM-only because `ns-publics` is a JVM reflection API. The CLJS half of
  the same boundary is reconciled by the api-manifest CLJS probe, which
  carries `re-frame.freehand` as a fully-rowed surface in BOTH directions
  (`implementation/scripts/api-manifest/probe/`)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.descriptor :as descriptor]))

(v/defview declared
  "A declaration-created descriptor — the one legal way to get one."
  [{:keys [title]}]
  [:section title])

(defn- mints-a-descriptor?
  "True when `f`, applied to one arbitrary argument, yields a value the
  runtime would classify as an internal view boundary. Anything that
  throws, or answers with something else, is not a constructor."
  [f]
  (try
    (descriptor/view? (f {:view-id :arbitrary/entry}))
    (catch Throwable _ false)))

(defn- supported-one-arg-fns
  "Every var on the SUPPORTED `re-frame.freehand` surface that could be a
  constructor: published, not a macro, not one of the documented
  `^:no-doc` internal carve-outs, and callable with one argument."
  []
  (->> (ns-publics 're-frame.freehand)
       (remove (fn [[_ var']] (or (:macro (meta var')) (:no-doc (meta var')))))
       (map (fn [[sym var']] [sym @var']))
       (filter (fn [[_ value]] (ifn? value)))))

(deftest the-supported-surface-mints-no-descriptor
  (testing "No published Freehand var is a descriptor constructor. A value
            minted outside a declaration carries no view-id, no source and
            no lowering, yet passes `view?` and classifies as `:view` — so
            a reachable constructor is the one-declaration rule with a
            hole in it."
    (let [candidates (supported-one-arg-fns)]
      (is (seq candidates)
          "non-vacuous: the door publishes callable vars to examine")
      (is (empty? (->> candidates
                       (filter (fn [[_ value]] (mints-a-descriptor? value)))
                       (map first)))
          "no supported var mints a descriptor from an arbitrary value"))))

(deftest the-probe-can-see-a-constructor
  (testing "The control for the assertion above: the internal
            `declare-view` IS a constructor and the probe detects it. An
            empty result there must mean 'nothing mints one', never 'the
            probe cannot tell'."
    (is (mints-a-descriptor? descriptor/declare-view))))

(deftest a-declaration-created-descriptor-is-accepted
  (testing "Closing the boundary must not close the door: a view declared
            with `v/defview` is still a first-class descriptor — it passes
            the public predicate, classifies as an internal boundary, and
            describes itself completely."
    (is (v/view? declared))
    (is (= :view (descriptor/classify-head declared)))
    (let [projection (v/describe declared)]
      (is (= :re-frame.freehand.public-surface-jvm-test/declared (:view-id projection)))
      (is (= :interpreted (:lowering projection)))
      (is (= :optional (:children-policy projection)))
      (is (map? (:source projection))
          "a declaration carries source coordinates; a minted value would not"))))
