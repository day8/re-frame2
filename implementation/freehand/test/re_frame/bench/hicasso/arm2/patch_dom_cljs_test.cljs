(ns re-frame.bench.hicasso.arm2.patch-dom-cljs-test
  "THE DIFFER AGAINST A REAL DOM (rf2-2rtt6.10).

  [[re-frame.bench.hicasso.arm2.reconcile-cljs-test]] proves the keyed
  decisions as values; this file proves that the applier does what the
  plan says — and that the two other tiers do what they claim.

  The assertions are mostly about **node identity**: the interesting
  claims of a renderer are not what the markup reads afterwards (any
  renderer that rebuilds everything gets that right) but which nodes
  survived. So `identical?` on captured node references is the tool
  throughout.

  No boundaries here. This file exercises the differ on plain elements,
  with no runtime, no frame and no subscriptions — which is the layering
  the arm is built on: the differ knows nothing about them."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.hicasso.arm2.controlled :as controlled]
            [re-frame.bench.hicasso.arm2.dom :as dom]
            [re-frame.bench.hicasso.arm2.patch :as patch]
            [re-frame.bench.hicasso.arm2.template :as template]))

(def ^:private off-browser
  "no DOM on this runtime — every claim here is a claim about nodes")

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

(defn- render!
  "Render a sequence of hiccup trees into a fresh container, returning
  `[container render-fn]` where `render-fn` patches the next one."
  [initial]
  (let [c    (container!)
        !old (atom initial)]
    (patch/render-root! c nil initial)
    [c (fn [next] (patch/render-root! c @!old next) (reset! !old next) nil)]))

(defn- kids [node]
  (let [cs (.-childNodes node) n (.-length cs)]
    (mapv (fn [i] (.item cs i)) (range n))))

(defn- texts [node] (mapv #(.-textContent %) (kids node)))

;; ---------------------------------------------------------------------------
;; Mounting
;; ---------------------------------------------------------------------------

(deftest a-cold-mount-builds-the-markup-the-hiccup-describes
  (if-not (browser?)
    (is true off-browser)
    (let [[c] (render! [:ul.grid {:role "list"}
                        [:li.row {:data-i 0} [:span.lbl "cell "] [:span.cell "7"]]])]
      (let [ul (.-firstChild c)]
        (is (= "UL" (.-tagName ul)))
        (is (= "grid" (.getAttribute ul "class")) "the tag shorthand became the class")
        (is (= "list" (.getAttribute ul "role")))
        (is (= "<li class=\"row\" data-i=\"0\"><span class=\"lbl\">cell </span><span class=\"cell\">7</span></li>"
               (.-innerHTML ul))))
      (.remove c))))

(deftest attribute-names-follow-the-doms-rule-not-reacts
  (if-not (browser?)
    (is true off-browser)
    (let [[c] (render! [:input {:read-only true :tab-index 3 :data-i 9
                                :aria-label "Cell" :for "x" :className "k"}])]
      (let [n (.-firstChild c)]
        (is (true? (.-readOnly n)) "read-only reached the element as readonly")
        (is (= "3" (.getAttribute n "tabindex")))
        (is (= "9" (.getAttribute n "data-i")) "data- keeps its dash")
        (is (= "Cell" (.getAttribute n "aria-label")) "aria- keeps its dash")
        (is (= "x" (.getAttribute n "for")))
        (is (= "k" (.getAttribute n "class")) "className is the seeded alias for class"))
      (.remove c))))

(deftest the-controlled-pair-is-a-property-not-an-attribute
  (if-not (browser?)
    (is true off-browser)
    (let [[c] (render! [:input {:type "text" :value "hello"}])]
      (let [n (.-firstChild c)]
        (is (= "hello" (.-value n)) "the live value moved")
        (is (nil? (.getAttribute n "value"))
            "and no value ATTRIBUTE was written — that would only set a default")
        (is (controlled/controlled? n) "the node is known to the restore path"))
      (.remove c))))

;; ---------------------------------------------------------------------------
;; The 1:1 law
;; ---------------------------------------------------------------------------

(deftest a-nil-child-occupies-one-comment-anchor
  (if-not (browser?)
    (is true off-browser)
    (let [[c patch!] (render! [:div [:b "a"] nil [:i "c"]])]
      (let [d (.-firstChild c)]
        (is (= 3 (count (kids d))) "three slots, one of them absent")
        (is (dom/anchor? (nth (kids d) 1)))
        (testing "the absent child can appear without disturbing its neighbours"
          (let [before (nth (kids d) 2)]
            (patch! [:div [:b "a"] [:em "B"] [:i "c"]])
            (is (= 3 (count (kids d))))
            (is (= "EM" (.-tagName (nth (kids d) 1))))
            (is (identical? before (nth (kids d) 2))
                "the following sibling is the same node — no index shifted"))))
      (.remove c))))

(deftest a-seq-child-splices-into-the-parents-children
  (if-not (browser?)
    (is true off-browser)
    (let [[c] (render! [:ul (for [i (range 3)] [:li {:key i} (str i)])])]
      (is (= ["0" "1" "2"] (texts (.-firstChild c))))
      (.remove c))))

(deftest a-fragment-child-splices-too
  (if-not (browser?)
    (is true off-browser)
    (let [[c] (render! [:div [:b "a"] [:<> [:i "b"] [:em "c"]]])]
      (is (= ["B" "I" "EM"] (mapv #(.-tagName %) (kids (.-firstChild c)))))
      (.remove c))))

;; ---------------------------------------------------------------------------
;; Tier 2 — the equality cutoff
;; ---------------------------------------------------------------------------

(deftest an-identical-subtree-is-not-walked
  (if-not (browser?)
    (is true off-browser)
    (let [row        [:li.row {:data-i 0} "unchanged"]
          [c patch!] (render! [:ul row [:li "x"]])
          ul         (.-firstChild c)
          first-li   (nth (kids ul) 0)
          text-node  (.-firstChild first-li)]
      (patch! [:ul row [:li "y"]])
      (is (identical? first-li (nth (kids ul) 0)))
      (is (identical? text-node (.-firstChild first-li))
          "the identical row's own text node was never replaced")
      (is (= "y" (.-textContent (nth (kids ul) 1))) "and the changed sibling did change")
      (.remove c))))

(deftest an-equal-but-fresh-subtree-is-also-cut
  (testing "structural sharing is a bonus, not the mechanism — a rebuilt but
           `=` subtree bails just the same"
    (if-not (browser?)
      (is true off-browser)
      (let [[c patch!] (render! [:div [:span {:class "a"} "same"]])
            span       (.-firstChild (.-firstChild c))
            text       (.-firstChild span)]
        (patch! [:div [:span {:class (str "a")} (str "same")]])
        (is (identical? span (.-firstChild (.-firstChild c))))
        (is (identical? text (.-firstChild span)))
        (.remove c)))))

;; ---------------------------------------------------------------------------
;; Tier 1 — the hole plan
;; ---------------------------------------------------------------------------

(deftest one-plan-serves-every-instance-of-a-shape
  (if-not (browser?)
    (is true off-browser)
    (do
      (template/reset-plans!)
      (let [[c] (render! [:ul (for [i (range 50)]
                                [:li.row {:key i :data-i i} [:span.cell (str i)]])])]
        (is (= 2 (template/plan-count))
            "one plan for the row and one for the cell — not one per instance")
        (is (= 50 (count (kids (.-firstChild c)))))
        (.remove c)))))

(deftest a-templated-patch-writes-only-the-hole
  (if-not (browser?)
    (is true off-browser)
    (let [[c patch!] (render! [:li.row {:data-i 0} [:span.lbl "cell "] [:span.cell "7"]])
          li         (.-firstChild c)
          lbl        (.-firstChild li)
          cell       (.-lastChild li)
          cell-text  (.-firstChild cell)]
      (patch! [:li.row {:data-i 0} [:span.lbl "cell "] [:span.cell "8"]])
      (is (identical? lbl (.-firstChild li)) "the untouched sibling is the same node")
      (is (identical? cell-text (.-firstChild cell))
          "and the changed text is the SAME Text node with a new value")
      (is (= "8" (.-textContent cell)))
      (.remove c))))

(deftest a-shape-change-falls-out-of-tier-1-and-back-in
  (if-not (browser?)
    (is true off-browser)
    (let [[c patch!] (render! [:div [:span "a"]])
          div        (.-firstChild c)]
      (patch! [:div [:span "a"] [:span "b"]])
      (is (= 2 (count (kids div))) "the slow path handled the shape change")
      (patch! [:div [:span "a"] [:span "c"]])
      (is (= ["a" "c"] (texts div)) "and the next render is templated again")
      (.remove c))))

;; ---------------------------------------------------------------------------
;; Tier 3 — keyed children against real nodes
;; ---------------------------------------------------------------------------

(defn- keyed [ks] (into [:ul] (map (fn [k] [:li {:key k} (name k)])) ks))

(deftest a-reorder-recreates-no-node
  (if-not (browser?)
    (is true off-browser)
    (let [[c patch!] (render! (keyed [:a :b :c]))
          ul         (.-firstChild c)
          [a b d]    (kids ul)]
      (patch! (keyed [:c :a :b]))
      (is (= ["c" "a" "b"] (texts ul)) "the order followed the keys")
      (is (identical? d (nth (kids ul) 0)))
      (is (identical? a (nth (kids ul) 1)))
      (is (identical? b (nth (kids ul) 2)))
      (.remove c))))

(deftest an-insert-at-the-head-moves-nothing
  (if-not (browser?)
    (is true off-browser)
    (let [[c patch!] (render! (keyed [:a :b :c]))
          ul         (.-firstChild c)
          before     (kids ul)]
      (patch! (keyed [:z :a :b :c]))
      (is (= ["z" "a" "b" "c"] (texts ul)))
      (is (= before (subvec (kids ul) 1)) "the three survivors are the same nodes")
      (.remove c))))

(deftest a-delete-removes-exactly-one-node
  (if-not (browser?)
    (is true off-browser)
    (let [[c patch!] (render! (keyed [:a :b :c]))
          ul         (.-firstChild c)
          [a _ d]    (kids ul)]
      (patch! (keyed [:a :c]))
      (is (= ["a" "c"] (texts ul)))
      (is (identical? a (nth (kids ul) 0)))
      (is (identical? d (nth (kids ul) 1)))
      (.remove c))))

(deftest a-hundred-row-rotation-keeps-every-node
  (if-not (browser?)
    (is true off-browser)
    (let [ks         (mapv #(keyword (str "k" %)) (range 100))
          [c patch!] (render! (keyed ks))
          ul         (.-firstChild c)
          before     (into #{} (kids ul))]
      (patch! (keyed (into [(last ks)] (butlast ks))))
      (is (= 100 (count (kids ul))))
      (is (= before (into #{} (kids ul))) "every node survived the rotation")
      (is (= "k99" (.-textContent (nth (kids ul) 0))))
      (.remove c))))

;; ---------------------------------------------------------------------------
;; Props and the event trampoline
;; ---------------------------------------------------------------------------

(deftest a-changed-prop-is-written-and-a-vanished-one-cleared
  (if-not (browser?)
    (is true off-browser)
    (let [[c patch!] (render! [:div {:title "a" :data-x "1"}])
          n          (.-firstChild c)]
      (patch! [:div {:title "b"}])
      (is (= "b" (.getAttribute n "title")))
      (is (nil? (.getAttribute n "data-x")) "the vanished prop was cleared")
      (.remove c))))

(deftest a-handler-is-replaced-without-touching-the-listener
  (if-not (browser?)
    (is true off-browser)
    (let [seen       (atom [])
          [c patch!] (render! [:button {:on-click (fn [_] (swap! seen conj :first))} "go"])
          n          (.-firstChild c)]
      (.click n)
      (patch! [:button {:on-click (fn [_] (swap! seen conj :second))} "go"])
      (.click n)
      (is (= [:first :second] @seen) "the latest handler runs, and only it")
      (is (some? (dom/handler-at n "click")) "the register holds the current handler")
      (.remove c))))

(deftest a-style-map-patches-declaration-by-declaration
  (if-not (browser?)
    (is true off-browser)
    (let [[c patch!] (render! [:div {:style {:color "red" :font-size "12px"}}])
          n          (.-firstChild c)]
      (is (= "red" (.-color (.-style n))))
      (is (= "12px" (.-fontSize (.-style n))))
      (patch! [:div {:style {:color "blue"}}])
      (is (= "blue" (.-color (.-style n))))
      (is (= "" (.-fontSize (.-style n))) "the dropped declaration was removed")
      (.remove c))))

(deftest a-changed-head-replaces-the-node
  (if-not (browser?)
    (is true off-browser)
    (let [[c patch!] (render! [:div [:span "x"]])
          span       (.-firstChild (.-firstChild c))]
      (patch! [:div [:b "x"]])
      (is (= "B" (.-tagName (.-firstChild (.-firstChild c)))))
      (is (not (identical? span (.-firstChild (.-firstChild c)))))
      (.remove c))))
