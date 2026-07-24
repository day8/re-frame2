(ns re-frame.freehand.hydration-matrix-dom-cljs-test
  "F6b matrix 8/8 — hydration ADOPTION and the FALLBACK, in a real browser,
  across BOTH execution modes (EP-0036 §6, gate row \"browser
  correctness\").

  Server bytes already in the document — with the Root Manifest the server
  emits beside them — are ADOPTED: the server's own DOM nodes survive the
  mount rather than being re-created. A container with nothing to adopt and
  no manifest FALLS BACK to a client mount, coming up correct without a
  pile of adoption errors. Adoption is proven by node IDENTITY, not text,
  because a root that discarded the server render and client-rendered would
  produce byte-identical markup; the fallback is proven by the mount KIND
  (`root/hydrated?`), because its output is identical too. Neither is a
  structural fact.

  The mode dimension: the greeting is a static, host-neutral declaration
  both emitters lower to the same markup, so the SAME server bytes adopt
  under an interpreted form and a compiled one — the manifest supplies the
  identity, so promotion (which changes the derived id) does not change what
  adopts. Each claim — matching markup adopted by identity, an empty
  container falling back, and a clean teardown — is asserted in each mode,
  and the two adopt the same DOM.

  Server bytes and the manifest come from the shipped FH-ROOT-006/007
  conformance fixtures, so the matrix carries no dependency on the SSR
  artefact and its expectations track the normative fixture.

  Rides the browser lane through its `-dom-cljs-test` suffix; under node it
  has no DOM and says so."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.matrix-support :as ms]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.root-views :as views]
            [re-frame.ssr.manifest :as ssr-manifest]
            [re-frame.trace.tooling :as trace-tooling]))

(use-fixtures :each
  {:before (fn [] (root/reset-registry!) (fr/reset-boundaries!))
   :after  (fn [] (root/reset-registry!) (fr/reset-boundaries!))})

(def ^:private root-006 (conf/fixture :FH-ROOT-006))
(def ^:private root-007 (conf/fixture :FH-ROOT-007))
(def ^:private props (:props root-006))

;; The compiled twin of the static hydration view — same body, marker only.
(v/defview greeting-compiled
  {:compiled true}
  [{:keys [name]}]
  [:section#greeting
   [:h1#title "Hello"]
   [:p#who name]])

(def ^:private modes
  [["interpreted" views/greeting] ["compiled" greeting-compiled]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- server-node!
  "A container carrying `html` — the server's bytes — and, when `manifest`
  is given, that root's Root Manifest as the container's IMMEDIATELY
  FOLLOWING element sibling, the wire form the shipped emitter produces.
  Answers the container a hydrating page presents."
  ([html] (server-node! html nil))
  ([html manifest]
   (let [host (.createElement js/document "div")]
     (set! (.-innerHTML host)
           (str "<div>" html "</div>"
                (when manifest (ssr-manifest/script-html manifest))))
     (.appendChild (.-body js/document) host)
     (.-firstElementChild host))))

(defn- remove-node! [container]
  (some-> container .-parentElement .remove))

(defn- listen-mismatches! [a]
  (let [k (keyword (gensym "matrix-hydration-"))]
    (trace-tooling/register-listener!
      k
      (fn [ev] (when (= :rf.ssr/hydration-mismatch (:operation ev)) (swap! a conj ev))))
    k))

(defn- settle
  "Give React's adoption window a moment to close, then resolve."
  []
  (js/Promise. (fn [res] (js/setTimeout #(res nil) 200))))

;; ===========================================================================
;; Row 1 — matching markup is adopted, by node identity, both modes
;; ===========================================================================

(deftest hydration-matrix-matching-markup-is-adopted-by-identity-in-both-modes
  (testing "The server's own DOM nodes survive the mount — node IDENTITY is
            the assertion, because a root that discarded the server render
            would produce byte-identical markup. Nothing is reported, the
            root is a hydration (not a fallback), and the page carries the
            server's text. In each mode, against the FH-ROOT-006 fixture's
            bytes and manifest."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the adoption assertions")
      (async done
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (ms/each-mode
          modes
          (fn [[label view]]
            (let [node       (server-node! (:server-html root-006) (:manifest root-006))
                  match      (:match root-006)
                  before     (into {} (map (juxt identity #(ms/q node %))) (:same-nodes match))
                  mismatches (atom [])
                  k          (listen-mismatches! mismatches)
                  mounted    (v/hydrate-root node [view props])]
              (-> (settle)
                  (.then (fn [_]
                           (trace-tooling/unregister-listener! k)
                           (doseq [[selector node*] before]
                             (is (some? node*) (str label ": the server rendered " selector))
                             (is (identical? node* (ms/q node selector))
                                 (str label ": " selector " is the SAME node object — adopted, not re-created")))
                           (is (= (:adopted match) (root/hydrated? mounted))
                               (str label ": the root adopted — it is a hydration"))
                           (is (= (:name props) (ms/text-of node "#who"))
                               (str label ": and the page carries the server's text"))
                           (is (= (:mismatches match) (count @mismatches))
                               (str label ": a clean adoption reports nothing. Saw: " (pr-str @mismatches)))
                           (v/unmount! mounted)
                           (remove-node! node)
                           nil)))))
          done)))))

;; ===========================================================================
;; Row 2 — an empty container with no manifest falls back, both modes
;; ===========================================================================

(deftest hydration-matrix-an-empty-container-falls-back-in-both-modes
  (testing "A container with nothing to adopt and no manifest beside it is
            the client-only first load, not a degraded adoption: the root
            recognises the empty input before React is involved, mounts
            client-side (`hydrated?` is false), comes up correct anyway,
            reports NO adoption errors, and claims and releases its
            live-root id like any other root. In each mode, per FH-ROOT-007."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the fallback assertions")
      (async done
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (ms/each-mode
          modes
          (fn [[label view]]
            (let [empty*     (:empty-container root-007)
                  node       (server-node! "")
                  mismatches (atom [])
                  k          (listen-mismatches! mismatches)
                  mounted    (v/hydrate-root node [view (:props root-007)])]
              (is (= 1 (count (root/live-root-ids)))
                  (str label ": a fallback root claims its id like any other"))
              (-> (settle)
                  (.then (fn [_]
                           (trace-tooling/unregister-listener! k)
                           (is (= (:hydrated empty*) (root/hydrated? mounted))
                               (str label ": an empty container did not hydrate — it fell back"))
                           (is (= (:text empty*) (ms/text-of node "#who"))
                               (str label ": and the root came up correct anyway"))
                           (is (= (:mismatches empty*) (count @mismatches))
                               (str label ": with no adoption errors at all. Saw: " (pr-str @mismatches)))
                           (v/unmount! mounted)
                           (is (= 0 (count (root/live-root-ids)))
                               (str label ": a fallback root tears down the same way"))
                           (remove-node! node)
                           nil)))))
          done)))))

;; ===========================================================================
;; Row 3 — both modes adopt the same DOM
;; ===========================================================================

(deftest hydration-matrix-both-modes-adopt-the-same-dom
  (testing "The same server bytes adopt to the SAME real DOM under an
            interpreted form and a compiled one. The greeting is a static
            declaration; promotion must not change the page hydration
            produces. The two are hydrated in SEQUENCE — the fixture
            manifest names one page-unique root-id, so both cannot be live
            at once — and their adopted outlines compared."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the parity assertion")
      (async done
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (ms/run-async
          (fn []
            (let [ni (server-node! (:server-html root-006) (:manifest root-006))
                  mi (v/hydrate-root ni [views/greeting props])]
              (-> (settle)
                  (.then (fn [_]
                           (let [outline-i (ms/outline (ms/q ni "#greeting"))]
                             (is (true? (root/hydrated? mi)) "the interpreted root adopted")
                             (v/unmount! mi)
                             (remove-node! ni)
                             outline-i)))
                  (.then (fn [outline-i]
                           (let [nc (server-node! (:server-html root-006) (:manifest root-006))
                                 mc (v/hydrate-root nc [greeting-compiled props])]
                             (-> (settle)
                                 (.then (fn [_]
                                          (is (= outline-i (ms/outline (ms/q nc "#greeting")))
                                              "interpreted and compiled adopt the same real DOM")
                                          (is (true? (root/hydrated? mc)) "and the compiled root adopted")
                                          (v/unmount! mc)
                                          (remove-node! nc)
                                          nil)))))))))
          done)))))
