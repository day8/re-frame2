(ns re-frame.freehand.root-multi-dom-cljs-test
  "FH-ROOT-003 in a real browser — two roots of ONE view on one page.

  Independence is the whole claim, and it is not provable from text. Two
  roots rendering the same declaration produce the same markup whether
  they are genuinely two roots or one root drawn twice, so this file
  proves it with state that CANNOT be shared by accident: each panel's
  UNCONTROLLED input. Type into the left one and the right one must not
  move. A shared host root, a shared boundary, or a shared identity would
  show up here immediately and nowhere else.

  The other half is the three admission claims. Each is asserted BEFORE
  the offending root renders anything, so the interesting assertion is not
  that the throw happened — it is that the page is untouched afterwards:
  the incumbent roots still registered, still rendering, and the registry
  still holding exactly what it held.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix. It also matches the node suites' broader regex, where it has no
  DOM to mount and says so rather than passing quietly."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.root-views :as views]))

(def root-003 (conf/fixture :FH-ROOT-003))

(use-fixtures :each
  {:before (fn [] (root/reset-registry!) (fr/reset-boundaries!))
   :after  (fn [] (root/reset-registry!) (fr/reset-boundaries!))})

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- act
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- host-node! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    container))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- error-id [thunk]
  (try (thunk) ::no-throw
       (catch :default e (:rf.error/id (ex-data e)))))

(defn- panel-form [side]
  [views/panel {:label (:label (get root-003 side))
                :seed  (:seed (get root-003 side))}])

;; ===========================================================================
;; FH-ROOT-003 — two roots, two identities, two occurrences
;; ===========================================================================

(deftest fh-root-003-two-roots-of-one-view-are-independent
  (testing "Per FH-ROOT-003 (browser): one declaration mounted twice on one
            page holds two identities — one derived with :disambiguator, one
            authored outright — two host roots, and two occurrences. The
            occurrences are proven with UNCONTROLLED input state, which is
            per-occurrence by construction: typing into one must leave the
            other exactly as its own body seeded it."
    (if-not (browser?)
      (skip! "the browser job runs the multi-root assertions")
      (async done
        (let [left-node  (host-node!)
              right-node (host-node!)
              left       (:left root-003)
              right      (:right root-003)]
          (-> (act #(vector (v/mount (panel-form :left) left-node (:opts left))
                            (v/mount (panel-form :right) right-node (:opts right))))
              (.then
                (fn [[l r]]
                  (is (= (:root-id left) (:root-id (root/root-descriptor l)))
                      ":disambiguator appends a scalar to the DERIVED id")
                  (is (= (:provenance left)
                         (:root-id-provenance (root/root-descriptor l))))
                  (is (= (:root-id right) (:root-id (root/root-descriptor r)))
                      "an authored :root-id is identity verbatim")
                  (is (= (:provenance right)
                         (:root-id-provenance (root/root-descriptor r))))
                  (is (= :re-frame.freehand.root-views/panel
                         (:view-id (root/root-descriptor l)))
                      "the derived id's :view-id is the view it derived from")
                  (is (= :re-frame.freehand.root-views/panel
                         (:view-id (root/root-descriptor r)))
                      "and an authored root still records WHICH view was mounted")

                  (is (= (:right-identifier-prefix root-003)
                         (root/root-identifier-prefix r))
                      "the derived identifierPrefix is the slug, wrapped")
                  (when (:prefixes-distinct root-003)
                    (is (not= (root/root-identifier-prefix l)
                              (root/root-identifier-prefix r))
                        "distinct root-ids give distinct prefixes — the injectivity
                         claim, which is why the framework's own derivation can
                         never trip the prefix check"))

                  (is (not (identical? (.-react-root ^root/Root l)
                                       (.-react-root ^root/Root r)))
                      "two roots, two host roots")
                  (is (= #{(:root-id left) (:root-id right)} (root/live-root-ids)))

                  ;; The occurrence proof. Each field was seeded once by its
                  ;; own body and React never writes to it again, so what is
                  ;; typed into one exists in that occurrence and nowhere else.
                  (let [lf (.querySelector left-node ".field")
                        rf* (.querySelector right-node ".field")]
                    (is (= (:seed left) (.-value lf)))
                    (is (= (:seed right) (.-value rf*)))
                    (set! (.-value lf) (:typed left))
                    (is (= (:typed left) (.-value lf)))
                    (is (= (:seed right) (.-value rf*))
                        "the right panel did not move — a shared host root, a shared
                         boundary or a shared identity would have moved it"))
                  (is (= (:label left)
                         (.-textContent (.querySelector left-node ".label"))))
                  (is (= (:label right)
                         (.-textContent (.querySelector right-node ".label"))))

                  (act #(do (v/unmount! l) (v/unmount! r)))))
              (.then (fn [_]
                       (.remove left-node)
                       (.remove right-node)
                       (done))
                     (fn [e]
                       (is false (str "multi-root mount rejected: " e))
                       (.remove left-node)
                       (.remove right-node)
                       (done)))))))))

;; ===========================================================================
;; FH-ROOT-003 — the three admission claims
;; ===========================================================================

(deftest fh-root-003-each-admission-claim-fails-before-any-render
  (testing "Per FH-ROOT-003 (browser): a root claims its id, its container and
            its identifierPrefix before it renders. The throw is the easy
            half; the claim that matters is what is true AFTER — the roots
            already on the page still registered and still rendering, and the
            registry holding exactly what it held. A mount that has written
            nothing has nothing to roll back, and that is the property under
            test."
    (if-not (browser?)
      (skip! "the browser job runs the admission assertions")
      (async done
        (let [left-node  (host-node!)
              right-node (host-node!)
              spare      (host-node!)
              left       (:left root-003)
              right      (:right root-003)
              by-claim   (into {} (map (juxt :claim identity)) (:claims root-003))]
          (-> (act #(vector (v/mount (panel-form :left) left-node (:opts left))
                            (v/mount (panel-form :right) right-node (:opts right))))
              (.then
                (fn [[l r]]
                  ;; 1 — the same root-id, in a DIFFERENT container.
                  (is (= (:error (by-claim :root-id))
                         (error-id #(v/mount (panel-form :left) spare (:opts left)))))
                  ;; 2 — a different root-id, into a container another root owns.
                  (is (= (:error (by-claim :container))
                         (error-id #(v/mount (panel-form :left) left-node
                                             {:root-id :fh.root/interloper}))))
                  ;; 3 — an AUTHORED prefix that aliases a live root's.
                  (is (= (:error (by-claim :identifier-prefix))
                         (error-id #(v/mount (panel-form :left) spare
                                             {:root-id           :fh.root/aliaser
                                              :identifier-prefix (root/root-identifier-prefix l)}))))

                  ;; Nothing was written by any of the three.
                  (is (= (:live-roots-after-rejection root-003)
                         (count (root/live-root-ids)))
                      "the registry holds exactly the roots it held")
                  (is (= #{(:root-id left) (:root-id right)} (root/live-root-ids)))
                  (is (zero? (.-childElementCount spare))
                      "the rejected mounts put nothing on the page")
                  (is (= (:label left)
                         (.-textContent (.querySelector left-node ".label")))
                      "and the incumbent root is still rendering")
                  (let [field (.querySelector left-node ".field")]
                    (set! (.-value field) (:typed left))
                    (is (= (:typed left) (.-value field))
                        "still the same live occurrence, still writable"))
                  (act #(do (v/unmount! l) (v/unmount! r)))))
              (.then (fn [_]
                       (.remove left-node) (.remove right-node) (.remove spare)
                       (done))
                     (fn [e]
                       (is false (str "admission suite rejected: " e))
                       (.remove left-node) (.remove right-node) (.remove spare)
                       (done)))))))))
