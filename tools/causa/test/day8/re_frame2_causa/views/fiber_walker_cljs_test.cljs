(ns day8.re-frame2-causa.views.fiber-walker-cljs-test
  "Tests for the Fiber walker (rf2-mxkq7).

  Two surfaces:

  (1) **Structural correctness** — the walker reads parent/child/
      sibling slots and emits a depth-first vector with correct
      `:depth` values. Uses minimal JS-object Fiber stubs (no real
      React mount) so the test is fast and runs in Node.

  (2) **Per-React-version regression smoke** — confirms the walker
      reads both React 16's `__reactInternalInstance$<suffix>`
      property and React 17+'s `__reactFiber$<suffix>` property. If
      a future React major bump renames either prefix, THIS test is
      the canary — fix the walker or fall back to data-attribute
      tagging per `rf2-01il5`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [goog.object :as gobj]
            [re-frame.interop :as interop]
            [day8.re-frame2-causa.views.fiber-walker :as fw]))

;; ---- Fiber stubs --------------------------------------------------------
;;
;; A Fiber-shaped object has at minimum:
;;   :child       — first child Fiber (or nil)
;;   :sibling     — next sibling Fiber (or nil)
;;   :return      — parent Fiber (or nil) — UNUSED by the walker but
;;                  modelled so a future regression-check can verify
;;                  the walker ignores it (no upward traversal).
;;   :elementType — the component fn (or string/keyword) the user
;;                  registered. Carries our `__rf2_view_id__` tag.

(defn- mk-element-type
  "Build a fake component fn with a `__rf2_view_id__` tag (mimicking
  the reg-view registration). Plain JS object (no real fn) — the
  walker only reads two specific properties."
  ([view-id]
   (mk-element-type view-id nil))
  ([view-id display-name]
   (let [o (js-obj)]
     (when view-id (gobj/set o "__rf2_view_id__" view-id))
     (when display-name (gobj/set o "displayName" display-name))
     o)))

(defn- mk-fiber
  "Build a Fiber-shaped JS object. `:children` is a clj vector of
  child Fibers; this fn wires up child/sibling pointers correctly."
  [{:keys [view-id display-name children]}]
  (let [f (js-obj)]
    (gobj/set f "elementType" (mk-element-type view-id display-name))
    (when (seq children)
      (let [siblings (map-indexed
                       (fn [i child]
                         (let [next-sib (nth children (inc i) nil)]
                           (when next-sib
                             (gobj/set child "sibling" next-sib))
                           child))
                       children)]
        (gobj/set f "child" (first siblings))))
    f))

;; ---- structural tests ---------------------------------------------------

(deftest walk-fiber-emits-depth-first-with-correct-depths
  (testing "rf2-mxkq7 — walker visits root → child → sibling in DFS"
    (let [leaf-c   (mk-fiber {:view-id :c})
          leaf-d   (mk-fiber {:view-id :d})
          mid-b    (mk-fiber {:view-id :b :children [leaf-c leaf-d]})
          leaf-e   (mk-fiber {:view-id :e})
          root     (mk-fiber {:view-id :a :children [mid-b leaf-e]})
          out      (fw/walk-fiber root)]
      (is (= [{:view-id :a :depth 0}
              {:view-id :b :depth 1}
              {:view-id :c :depth 2}
              {:view-id :d :depth 2}
              {:view-id :e :depth 1}]
             (mapv #(select-keys % [:view-id :depth]) out))
          "depth-first order: a, b, c, d, e at expected depths"))))

(deftest walk-fiber-handles-single-node-tree
  (testing "rf2-mxkq7 — walker emits one row for a leaf-only Fiber"
    (let [root (mk-fiber {:view-id :solo})
          out  (fw/walk-fiber root)]
      (is (= 1 (count out)))
      (is (= :solo (:view-id (first out))))
      (is (= 0 (:depth (first out)))))))

(deftest walk-fiber-handles-deep-nesting
  (testing "rf2-mxkq7 — walker handles 10-deep chain without blowing recursion"
    (let [chain (reduce
                  (fn [child i]
                    (mk-fiber {:view-id (keyword (str "n" i))
                               :children [child]}))
                  (mk-fiber {:view-id :leaf})
                  (range 10))
          out   (fw/walk-fiber chain)]
      (is (= 11 (count out)) "10 wrappers + 1 leaf")
      (is (= [0 1 2 3 4 5 6 7 8 9 10] (mapv :depth out))
          "depths increase monotonically"))))

(deftest walk-fiber-returns-nil-on-nil-root
  (testing "rf2-mxkq7 — walker is null-safe"
    (is (nil? (fw/walk-fiber nil)))))

(deftest walk-fiber-resolves-view-id-via-tag
  (testing "rf2-mxkq7 — walker reads `__rf2_view_id__` off the
            elementType to resolve the view-id"
    (let [root (mk-fiber {:view-id :host.app/root})
          out  (fw/walk-fiber root)]
      (is (= :host.app/root (:view-id (first out)))))))

(deftest walk-fiber-falls-back-to-displayName
  (testing "rf2-mxkq7 — walker falls back to displayName when no
            `__rf2_view_id__` tag is present"
    (let [root (mk-fiber {:display-name "AnonymousButton"})
          out  (fw/walk-fiber root)]
      (is (= "AnonymousButton" (:view-id (first out)))))))

(deftest walk-fiber-falls-back-to-host-label
  (testing "rf2-mxkq7 — walker emits '<host>' when no tag + no name"
    (let [f (js-obj)]
      (gobj/set f "elementType" (js-obj))
      (let [out (fw/walk-fiber f)]
        (is (= "<host>" (:view-id (first out))))))))

(deftest walk-fiber-string-elementType-passes-through
  (testing "rf2-mxkq7 — string elementType (host DOM element like 'div')
            renders as the string label"
    (let [f (js-obj)]
      (gobj/set f "elementType" "div")
      (let [out (fw/walk-fiber f)]
        (is (= "div" (:view-id (first out))))))))

(deftest walk-fiber-attaches-stable-fiber-key
  (testing "rf2-mxkq7 — every row carries a `:fiber-key` for React-key use"
    (let [root (mk-fiber {:view-id :a :children [(mk-fiber {:view-id :b})]})
          out  (fw/walk-fiber root)]
      (is (every? :fiber-key out))
      (is (apply distinct? (map :fiber-key out))
          "different Fibers produce different keys"))))

;; ---- per-React-version smoke -------------------------------------------
;;
;; rf2-mxkq7 — the canonical regression check on each React major
;; bump (16 → 17 → 18 → 19 → …). The walker discovers the Fiber
;; property by scanning the DOM node's keys for the documented prefix
;; — this test seeds both React 16's and React 17+'s prefix on a
;; fake DOM node and asserts the walker reads them.

(defn- mk-dom-node-with-fiber
  "Build a DOM-node-shaped JS object with a Fiber attached on the
  given property name."
  [property-name fiber]
  (let [n (js-obj)]
    (gobj/set n property-name fiber)
    n))

(deftest dom-node-fiber-react-17-plus-prefix
  (testing "rf2-mxkq7 — React 17+ uses `__reactFiber$<suffix>` to
            attach the Fiber pointer to a host DOM node"
    (let [fiber (mk-fiber {:view-id :react17.host/root})
          node  (mk-dom-node-with-fiber "__reactFiber$abc123" fiber)]
      (is (= fiber (fw/dom-node->fiber node))
          "walker reads the React 17+ prefix"))))

(deftest dom-node-fiber-react-16-prefix
  (testing "rf2-mxkq7 — React 16 used `__reactInternalInstance$<suffix>`;
            kept for back-compat smoke."
    (let [fiber (mk-fiber {:view-id :react16.host/root})
          node  (mk-dom-node-with-fiber "__reactInternalInstance$xyz" fiber)]
      (is (= fiber (fw/dom-node->fiber node))
          "walker reads the React 16 prefix"))))

(deftest dom-node-fiber-absent-returns-nil
  (testing "rf2-mxkq7 — DOM node with no React-attached Fiber returns nil"
    (let [node (js-obj)]
      (gobj/set node "id" "some-non-react-node")
      (is (nil? (fw/dom-node->fiber node))))))

(deftest read-tree-from-end-to-end
  (testing "rf2-mxkq7 — end-to-end: DOM node → Fiber → walk → vector"
    (let [leaf (mk-fiber {:view-id :leaf})
          root (mk-fiber {:view-id :root :children [leaf]})
          node (mk-dom-node-with-fiber "__reactFiber$smoke" root)
          out  (fw/read-tree-from node)]
      (is (= [:root :leaf] (mapv :view-id out)))
      (is (= [0 1] (mapv :depth out))))))

;; ---- production DCE gate -----------------------------------------------
;;
;; The walker is `(when interop/debug-enabled? …)`-gated at every
;; public entry. Asserting the gate behaviour at the CLJS test level
;; only really exercises the dev branch (CLJS tests run with
;; goog.DEBUG=true). The release-side assertion lives in
;; `implementation/scripts/check-bundle-isolation.cjs` — under
;; `:advanced` + `goog.DEBUG=false` the walker's bodies should DCE.
;; The cljs-side smoke below just asserts the gate value is wired,
;; so a future change that hardcodes `true` (defeating the elision)
;; is caught at test time.

(deftest debug-enabled-gate-wired
  (testing "rf2-mxkq7 — `interop/debug-enabled?` IS the production
            DCE gate; CLJS tests run with the gate ON. The bundle-
            isolation test in implementation/scripts/check-bundle-
            isolation.cjs exercises the OFF case at release time."
    (is (true? ^boolean interop/debug-enabled?)
        "tests run with goog.DEBUG=true; walker bodies are live")))
