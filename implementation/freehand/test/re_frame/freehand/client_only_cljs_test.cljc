(ns re-frame.freehand.client-only-cljs-test
  "FH-ROOT-008, the host-neutral half — the `v/client-only` boundary as the
  STRUCTURAL render sees it, on the JVM and in ClojureScript.

  The structural render is the `:server`-phase render on both hosts, so the
  claim proved here is the one a server makes: the fallback is what stands in
  the region, the client subtree is never entered, and the marker on the node
  is what lets a reader tell a stand-in from the real thing.

  The browser half — the phase flip itself — is
  `re-frame.freehand.client-only-dom-cljs-test`. Neither file proves the row
  alone: this one says what `:server` phase renders, that one says how a root
  leaves it."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.freehand :as v]
            [re-frame.freehand.client-only-views :as views]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.tree :as tree]))

(def root-008 (conf/fixture :FH-ROOT-008))

(defn- rendered
  "`form`'s structural tree without the version stamp — the tree the fixture
  states, and nothing about the envelope."
  [form]
  (dissoc (tree/render form) :rf.ui/tree-version))

(defn- error-id [thunk]
  (try (thunk) :accepted
       (catch #?(:clj Exception :cljs :default) e
         (:rf.error/id (ex-data e)))))

;; ---------------------------------------------------------------------------
;; The `:server`-phase render — the fallback, and only the fallback
;; ---------------------------------------------------------------------------

(deftest fh-root-008-the-structural-render-produces-the-fallback
  (testing "Per FH-ROOT-008: the structural render is `:server` phase on both
            hosts, so each client-only site renders its capability-free
            fallback wrapped in the `:rf.ui/boundary :client-only` marker. The
            heading outside both boundaries renders identically either way,
            which is what makes the fallback assertions mean something rather
            than merely proving the view rendered at all."
    (let [{:keys [props tree]} (:structural root-008)]
      (is (= tree (rendered [views/two-sites props]))))))

(deftest fh-root-008-the-client-subtree-is-never-entered
  (testing "The client subtree is a value the structural walk deliberately
            does not walk — that is the whole of what the boundary buys a
            server. Proven by CONSTRUCTION rather than by absence in the
            tree: the client arm here is markup the structural walk REFUSES
            (a map is not a legal child), so a walk that entered it would
            throw. A tree assertion alone could not tell 'not walked' from
            'walked and dropped'."
    (is (= {:rf.ui/boundary :client-only
            :children [{:tag :i :children ["stand in"]}]}
           (rendered (v/client-only {:fallback [:i "stand in"]}
                       [:div {} {:not "markup"}])))
        "the fallback renders and the refusing client arm is never reached")))

(deftest fh-root-008-a-site-rooted-view-keeps-its-marker
  (testing "A view whose whole body IS a client-only site is a boundary
            WRAPPING the marked node, not a boundary that adopted the
            fallback's children. Fragment adoption is the one shape in which
            the marker could be silently dropped, and dropping it would leave
            a fallback indistinguishable from ordinary markup."
    (is (= (:tree (:site-rooted root-008))
           (rendered [views/site-rooted {}])))))

;; ---------------------------------------------------------------------------
;; The refusals — the fallback is mandatory, the roster is closed
;; ---------------------------------------------------------------------------

(deftest fh-root-008-the-fallback-is-mandatory-and-the-roster-closed
  (testing "Per FH-ROOT-008: a browser-only subtree with no fallback is a
            hole in the server's output, so the boundary has no arity that
            omits one and no default to supply. An explicit nil IS a
            fallback — presence, not truth — and renders nothing, which is a
            stated answer rather than an omission."
    (is (seq (:refusals root-008)) "the fixture's refusal table loaded")
    (doseq [{:keys [note opts error]} (:refusals root-008)]
      (is (= error (error-id #(v/client-only opts [:b "live"]))) note))))
