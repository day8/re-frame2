(ns re-frame.freehand.root-mount-jvm-test
  "FH-ROOT-001 on the JVM — the structural half of the minimal one-root
  spelling.

  `v/mount` is the browser half (a DOM node is), and its `-dom-cljs-test`
  sibling proves what React does with it. This one proves that the
  IDENTICAL root form — the same `[app {…}]` a browser mounts — answers a
  structural tree on the host with no DOM, so the minimal one-root spelling
  is one spelling on both hosts rather than a browser verb with a separate
  JVM story. It also pins the identity DERIVATION host-neutrally: the root's
  derived id is exactly the mounted view's registered id, which is what the
  browser half keys idempotent re-mount on."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.root-views :as views]
            [re-frame.freehand.tree :as tree]))

(def root-001 (conf/fixture :FH-ROOT-001))

(deftest fh-root-001-the-identical-root-form-renders-structurally
  (testing "Per FH-ROOT-001: the minimal one-root form that v/mount puts on
            a browser page renders structurally on the JVM — the same
            declared view, the same spelling, one versioned tree. The
            browser half (real DOM + the derived Root Descriptor read off the
            root handle) runs in the -dom-cljs-test sibling."
    (is (map? (:tree root-001)) "the fixture's structural tree loaded")
    (is (= (:tree root-001)
           (tree/render [views/app (:props root-001)]))
        "the mounted view's structural tree is the fixture's — the same
         [app {…}] spelling the browser mounts")))

(deftest fh-root-001-the-root-identity-is-the-mounted-views-id
  (testing "Per FH-ROOT-001: the minimal spelling authors no identity — the
            root-id DERIVES from the mounted view's registered id (Spec 004C
            §1.2). Proven host-neutrally here: the fixture's derived root-id
            is exactly the view's own qualified id, which is what the browser
            half keys idempotent re-mount on across a reload."
    (let [descriptor (:root-descriptor root-001)
          view-id    (:view-id (v/describe views/app))]
      (is (= view-id (:root-id descriptor))
          "the derived root-id is the mounted view's registered id")
      (is (= view-id (:view-id descriptor)))
      (is (= :derived (:root-id-provenance descriptor))
          "the minimal spelling records that the id was derived, not authored")
      (is (= 1 (:rf.root/schema-version descriptor))))))
