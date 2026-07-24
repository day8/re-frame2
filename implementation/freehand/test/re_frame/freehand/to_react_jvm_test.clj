(ns re-frame.freehand.to-react-jvm-test
  "FH-REACT-005, the JVM half — the outward bridge is a browser verb, and its
  absence here IS the server policy.

  A React component value has no meaning in a structural render, so the only
  thing a JVM arm could do is raise. Freehand deliberately carries no tier of
  host operations that exist purely to raise on the server — the donor's
  hook / ref / effect tier went with them — so the verb is honestly absent on
  the host that cannot support it, exactly as `mount`, `hydrate-root` and
  `unmount!` are.

  An absence is the easiest thing in the world to assert vacuously, so this
  file reads the door's REAL JVM surface and pins BOTH directions against it:
  the verbs that must not be there, and the verbs that must. Without the second
  roster a green here would survive the whole door being deleted. The other
  side of the same non-vacuity is `to-react-cljs-test`, which asserts the verb
  is present and working on the host that has it.

  JVM-only because `ns-publics` is a JVM reflection API."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.test :as t]
            [re-frame.freehand.to-react-views :as views]))

(def react-005 (conf/fixture :FH-REACT-005))

(defn- door-publics []
  (set (map name (keys (ns-publics 're-frame.freehand)))))

(deftest fh-react-005-the-bridge-is-absent-from-the-jvm-surface
  (testing "Per FH-REACT-005: the door does not publish `v/->react` on the JVM,
            and it does not publish it alone — the mount verbs it shares a
            reader conditional with are absent for the same reason, so the
            claim is about a host policy rather than about one omission."
    (let [published (door-publics)]
      (is (seq published) "non-vacuous: the door publishes vars to examine")
      (is (contains? (set (:absent-on-jvm react-005)) (:var react-005))
          "non-vacuous: the fixture's roster really names the verb under test")
      (doseq [absent (:absent-on-jvm react-005)]
        (is (not (contains? published absent))
            (str "re-frame.freehand does not publish " absent " on the JVM"))))))

(deftest fh-react-005-the-jvm-surface-is-not-simply-empty
  (testing "The control that makes the absence above mean something. A JVM
            server verb IS on this surface — `v/render-static` is the Freehand
            server render, a structural fold with no React in it — so 'the
            probe found nothing' can never be the reason the row above is
            green."
    (let [published (door-publics)]
      (is (seq (:present-on-jvm react-005)) "non-vacuous: there is a control roster")
      (doseq [present (:present-on-jvm react-005)]
        (is (contains? published present)
            (str "re-frame.freehand publishes " present " on the JVM"))))))

(deftest fh-react-005-the-structural-host-still-renders-the-exported-view
  (testing "The point of the absence, stated positively: a view is not
            diminished by having been exported somewhere else. What does not
            cross to the JVM is the React COMPONENT VALUE, not the view — the
            declaration the browser hands to React is an ordinary declared view
            here, and a structural test of it is a real test."
    (is (v/view? views/person-cell))
    (let [tree (t/render [views/person-cell {:person-id 7 :column-id "amount"}])
          node (t/find tree #(= :span (:tag %)))]
      (is (some? node) "the declared view rendered structurally")
      (is (= "person 7" (t/text node))
          "the same markup the exported component renders in React-world"))))
