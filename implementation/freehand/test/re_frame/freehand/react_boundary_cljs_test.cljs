(ns re-frame.freehand.react-boundary-cljs-test
  "FH-ROOT-002, the emitter's half — what the interpreted React emitter
  hands React across a redefinition.

  The browser sibling (`root-mount-dom-cljs-test`) proves the CONSEQUENCE:
  a compatible reload leaves the mounted occurrence — the same input node,
  the value typed into it, its focus — untouched. That consequence has
  exactly one cause, and it is decided here, before any DOM exists: React
  unmounts a boundary and mounts a fresh one when it is handed a different
  component TYPE, so whether a reload preserves an occurrence is entirely
  the question of whether the emitter answers the same component for the
  redefined view.

  Three claims, none of which needs a browser:

    - a COMPATIBLE redefinition — same qualified view id, same hook
      skeleton — is answered with the boundary React already mounted, and
      the new body is published through it;
    - the body REVISION advances at the reload seam and only there, so a
      candidate rendered from the old body is stale at commit while an
      ordinary re-walk of an unchanged tree invalidates nothing;
    - an INCOMPATIBLE redefinition is not served that boundary at all.

  The third is the one that keeps the fix honest. Keying the cache on the
  qualified view id is what preserves the occurrence, and the naive
  version of that — key on the id, return whatever is cached — would
  silently render a stale interpreted body for a view its author has since
  promoted to `{:compiled true}`. So the emitter compares the SHELL
  SIGNATURE too, and a moved signature never reaches the cached boundary."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.react :as fr]))

(def root-002 (conf/fixture :FH-ROOT-002))

(use-fixtures :each
  {:before (fn [] (fr/reset-boundaries!))
   :after  (fn [] (fr/reset-boundaries!))})

(def ^:private view-id (:root-id root-002))

(defn- interpreted-generation
  "One generation of an INTERPRETED redefinition of `view-id` — a fresh
  descriptor object carrying a fresh body closure, which is exactly what a
  reload presents."
  [label]
  (descriptor/declare-view
    {:view-id         view-id
     :source          {:ns "re-frame.freehand.root-hmr" :file "root_hmr.cljc" :line 0}
     :lowering        :interpreted
     :children-policy :optional
     :render          (fn [_] [:main#app label])}))

(defn- promoted-generation
  "The same qualified view id, redeclared `{:compiled true}` — the reachable
  INCOMPATIBLE redefinition. A compiled declaration renders through the
  compiled tier's own shell, whose capability-elision verdict may omit the
  ViewCell and every hook above it, so its hook skeleton is not the
  interpreted one and its boundary is not the interpreted boundary."
  []
  (descriptor/declare-view
    {:view-id         view-id
     :source          {:ns "re-frame.freehand.root-hmr" :file "root_hmr.cljc" :line 0}
     :lowering        :compiled
     :children-policy :optional
     :structural      (fn [_] {:tag :main})
     :manifest        {:view-id view-id :view-cell :present}}))

(defn- boundary-of
  "The React component type the emitter mounts `view` through — read off a
  real element, which is the only thing React itself reconciles on."
  [view]
  (.-type (fr/element [view {}])))

(defn- revision
  "The emitter's current body revision for the view under test."
  []
  (:revision (get (fr/boundary-cache) view-id)))

;; ===========================================================================
;; The compatible arm — one boundary, a republished body
;; ===========================================================================

(deftest fh-root-002-a-compatible-redefinition-keeps-the-boundary
  (testing "Per FH-ROOT-002 (emitter): two generations of one qualified view
            id are two descriptor OBJECTS carrying two body closures, and the
            emitter answers both with ONE React component. That identity is
            what React reconciles on, so the boundary — and the Fiber, the
            ViewCell and the DOM beneath it — is re-rendered rather than
            replaced. Keying on the descriptor object instead would mint a
            component per generation and remount on every reload."
    (let [gen-1 (interpreted-generation (:before root-002))
          gen-2 (interpreted-generation (:after root-002))
          c-1   (boundary-of gen-1)]
      (is (not (identical? gen-1 gen-2))
          "the two generations really are distinct descriptor values — a
           reload mints a new object, and this is the fact that used to
           decide the boundary")
      (is (identical? c-1 (boundary-of gen-2))
          "and the emitter answers ONE component for both — the compatible
           redefinition is served the boundary React already mounted")
      (is (identical? c-1 (boundary-of gen-1))
          "including when an earlier generation is walked again")
      (is (= (:entries (:boundary root-002)) (count (fr/boundary-cache)))
          "one cache entry for the view id, not one per generation"))))

(deftest fh-root-002-the-body-revision-advances-at-the-reload-seam
  (testing "Per FH-ROOT-002 (emitter): the stable boundary publishes the new
            body through a slot whose REVISION is the hot-reload fence. It
            advances when a genuinely new body is published — making any
            candidate still holding the old body stale at commit — and NOT
            when an unchanged tree is walked again, which happens on every
            ordinary render and must invalidate nothing."
    (let [gen-1 (interpreted-generation (:before root-002))
          gen-2 (interpreted-generation (:after root-002))]
      (boundary-of gen-1)
      (is (zero? (revision)) "the first publication is revision zero")
      (boundary-of gen-2)
      (is (= (:revision-after-reload (:boundary root-002)) (revision))
          "the reload advanced the revision exactly once")
      (boundary-of gen-2)
      (boundary-of gen-2)
      (is (= (:revision-after-rewalk (:boundary root-002)) (revision))
          "re-walking the SAME generation publishes nothing and advances
           nothing — a render pass that reaches one boundary at several call
           sites cannot make its own in-flight candidates stale"))))

;; ===========================================================================
;; The incompatible arm — a moved shell signature is not served the boundary
;; ===========================================================================

(deftest fh-root-002-an-incompatible-redefinition-is-not-served-the-boundary
  (testing "Per FH-ROOT-002 (emitter): a redefinition whose SHELL SIGNATURE
            moved — here the reachable case, the same qualified id promoted
            to {:compiled true} — is not answered with the cached interpreted
            boundary. Today the compiled React lowering answers that with its
            own loud refusal; when it lands, the same rule spends one clean
            remount instead. Either way the interpreted boundary must not
            render it: a cache that returned the entry for the id alone would
            silently render the pre-promotion body, forever, with nothing on
            screen or in the console to say so."
    (let [gen-1 (interpreted-generation (:before root-002))
          c-1   (boundary-of gen-1)]
      (is (= (:error (:incompatible root-002))
             (conf/caught-id #(boundary-of (promoted-generation))))
          "the promoted generation reached the mint path, not the cached
           boundary — and the mint path refuses a compiled declaration")
      (is (identical? c-1 (boundary-of gen-1))
          "and the refusal left the interpreted boundary intact")
      (is (= (:entries (:boundary root-002)) (count (fr/boundary-cache)))
          "with nothing accumulated for the generation that was refused"))))
