(ns re-frame.freehand.custom-element-ssr-jvm-test
  "FH-STRUCT-011, the SERVER half — a declared property is omitted from
  markup and applied at hydration.

  A server cannot set a property. The only thing it can put on an element
  is an attribute, and an attribute is precisely what a property-accepting
  web component ignores — so markup carrying `accent-color=\"blue\"` would
  hydrate without complaint and leave the component inert with the value
  visibly present in the HTML. The serialiser therefore OMITS the props the
  node's `:rf.ui/property-props` set names, and the client applies them at
  hydration.

  That reserved key is why the omission can happen at all: it is SEMANTIC
  conversion input rather than a diagnostic ([004B §Reserved `:rf.ui/*`
  keys]), so the serialiser reads it before normalization drops it. Before
  the classification reached Freehand's structural output the set was never
  emitted, and the omission — fully implemented, fully specified — simply
  never fired here.

  The markup is pinned as WHOLE HTML STRINGS because the claim is about
  ABSENCE. A present-and-wrong attribute is the failure, and an assertion
  shaped as a list of expected keys cannot see one arrive.

  JVM-only, and not for want of a `.cljc`: for Freehand the JVM render IS
  the server shell (`v/render-static` / `re-frame.ssr`), so the JVM lane is
  the lane that proves the `ssr` cell. `re-frame.ssr` is a TEST-ONLY
  dependency of this artefact — the production door takes no compile-time
  require on it (Spec 011 §the wall).

  Replaces the donor family's server assertions
  (`custom_element_{classification,order,spread-parity}_jvm_test`, each
  reaching for `semantic/normalize` on its own views)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.custom-element-views :as views]
            [re-frame.freehand.tree :as tree]
            [re-frame.ssr :as ssr]))

(def struct-011 (conf/fixture :FH-STRUCT-011))

(defn- html [view]
  (ssr/emit-ui-tree (tree/render [(get views/by-name view) {}])))

(deftest fh-struct-011-a-declared-property-is-absent-from-server-markup
  (testing "Per FH-STRUCT-011: the serialiser omits every prop the node's
            `:rf.ui/property-props` set names, under EVERY spelling, and
            leaves the attributes beside them exactly as they were. An
            undeclared element omits nothing, because nothing on it is a
            property."
    (is (seq (:markup struct-011)) "the fixture's markup table loaded")
    (doseq [{:keys [note view] :as row} (:markup struct-011)]
      (is (= (:html row) (html view)) note))))

(deftest fh-struct-011-every-lowering-path-emits-the-same-server-markup
  (testing "Per FH-STRUCT-011: the rows above pin each path's markup against
            the contract; this pins the paths against EACH OTHER. It is the
            claim an SSR-then-hydrate page depends on — that the same
            declaration serialises the same way whether the props were a
            compiled literal map or a `v/spread`, because a divergence here
            is a hydration mismatch on a page that renders correctly in
            development."
    (let [paths [:panel-literal :panel-literal-compiled
                 :panel-spread :panel-spread-compiled]
          emitted (mapv html paths)]
      (is (apply = emitted)
          (str "the four paths emit one markup string: " (pr-str (zipmap paths emitted))))
      (is (not (.contains ^String (first emitted) "accent"))
          "and the string they agree on carries the property under no spelling"))))

(deftest fh-struct-011-the-reserved-marker-never-reaches-the-markup
  (testing "Per FH-STRUCT-011: `:rf.ui/property-props` is conversion INPUT.
            It is consumed and then dropped, so no reserved key — and no
            trace of the classification's own vocabulary — appears in the
            HTML a browser receives."
    (doseq [view [:panel-literal :panel-literal-compiled :attrish :below-literal]]
      (let [out (html view)]
        (is (not (.contains ^String out "property-props")) (str view ": no marker in markup"))
        (is (not (.contains ^String out "rf.ui")) (str view ": no reserved namespace in markup"))))))
