(ns re-frame.ui.emit-cljs-view-evidence-annotation-jvm-test
  "98.1 slice (c) — the compiler emits the view-evidence DOM annotation onto its
  compiler-owned host root (rf2-hac8p).

  Per Spec 004 §View identity (Source ↔ DOM navigation) the compiled substrate
  stamps `data-rf2-source-coord` + `data-rf-view` (today's attribute vocabulary)
  on the view's root DOM element, DEV-gated so `:advanced` + goog.DEBUG=false
  carries none of it. These are end-to-end pins on the REAL cljs `defview*` emit
  path (menv carries `:ns`, so `emit-cljs/emit-defview` runs and returns the
  expansion as data on this JVM host)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.source-coords :as source-coord]
            [re-frame.ui.compiler :as compiler]))

(defn- cljs-emit
  "Run the REAL cljs `defview*` emit path for `template` under `ns-sym`/`vname`
  with source-coord reader meta `{:line :column}`; returns the emitted form as
  data (the cljs env marker `{:ns …}` selects `emit-cljs/emit-defview`)."
  [ns-sym vname template line col]
  (compiler/defview*
   (with-meta (list 'defview vname [] template) {:line line :column col})
   {:ns {:name ns-sym}}
   vname
   (list [] template)))

(defn- forms-of [form] (tree-seq coll? seq form))

(defn- data-attr-set
  "The `(cljs.core/unchecked-set _ attr value)` value stamped for `attr`, or nil."
  [form attr]
  (some (fn [x]
          (when (and (seq? x)
                     (= 'cljs.core/unchecked-set (first x))
                     (= attr (nth x 2 nil)))
            (nth x 3 nil)))
        (forms-of form)))

(defn- stamp-count
  "How many `(cljs.core/unchecked-set _ attr _)` stamps `form` carries for `attr`
  — one per annotated host element (a conditional root stamps one per concrete
  DOM arm)."
  [form attr]
  (count (filter (fn [x]
                   (and (seq? x)
                        (= 'cljs.core/unchecked-set (first x))
                        (= attr (nth x 2 nil))))
                 (forms-of form))))

(defn- annotation-gate
  "The `(when <gate> …)` form wrapping the source-coord stamp, or nil. Matches the
  `when` by NAME — the emitter's syntax-quote resolves it against the host core
  (`clojure.core/when` on this JVM host)."
  [form]
  (first (filter #(and (seq? %)
                       (symbol? (first %))
                       (= "when" (name (first %)))
                       (some (fn [x]
                               (and (seq? x)
                                    (= 'cljs.core/unchecked-set (first x))
                                    (= "data-rf2-source-coord" (nth x 2 nil))))
                             (forms-of %)))
                 (forms-of form))))

(deftest element-root-carries-the-dev-gated-source-coord-and-view-id
  (let [form (cljs-emit 'app.probe 'widget '[:div "x"] 12 3)
        gate (annotation-gate form)]
    (is (some? gate)
        "the compiler-owned host root gains the view-evidence annotation")
    (is (= 'js/goog.DEBUG (second gate))
        "the annotation is goog.DEBUG-gated so :advanced elides it (I-12)")
    (is (= "app.probe:widget:12:3" (data-attr-set form "data-rf2-source-coord"))
        "data-rf2-source-coord is the canonical <ns>:<sym>:<line>:<col> coord")
    (is (= ":app.probe/widget" (data-attr-set form "data-rf-view"))
        "data-rf-view is the view id — the record identity Xray matches on")))

(deftest annotation-values-come-from-the-cross-host-source-coords-projection
  ;; byte-identical to the adapter walks by construction (single .cljc owner)
  (let [form (cljs-emit 'my.app 'panel '[:section "body"] 5 1)]
    (is (= (source-coord/format-source-coord :my.app/panel {:line 5 :column 1})
           (data-attr-set form "data-rf2-source-coord")))
    (is (= (source-coord/format-view-id :my.app/panel)
           (data-attr-set form "data-rf-view")))))

(deftest missing-source-column-degrades-not-crashes
  ;; a macro-generated template with no :column still annotates (coord "?")
  (let [form (cljs-emit 'app.gen 'made '[:span] 7 nil)]
    (is (= "app.gen:made:7:?" (data-attr-set form "data-rf2-source-coord")))))

(deftest unconditional-wrappers-are-transparent-to-the-host-root-mark
  (testing "an authored root let unwraps to the element it wraps"
    (let [form (cljs-emit 'app.wrap 'boxed '(let [y "z"] [:div y]) 9 2)]
      (is (some? (annotation-gate form))
          "the annotation rides through the let to the inner host element")
      (is (= "app.wrap:boxed:9:2" (data-attr-set form "data-rf2-source-coord")))
      (is (= ":app.wrap/boxed" (data-attr-set form "data-rf-view"))))))

(deftest common-conditional-roots-annotate-each-concrete-dom-arm
  ;; rf2-hac8p audit obligation #1: the walk descends common conditional roots so
  ;; whichever arm renders is tagged (Spec 006 exempts only the render that yields
  ;; nil). Every branch carries the SAME view identity — the annotation is the
  ;; view's, not the branch's.
  (testing "an (if …) root stamps BOTH element arms"
    (let [form (cljs-emit 'app.cond 'branchy '(if x [:a] [:b]) 3 4)]
      (is (some? (annotation-gate form)))
      (is (= 2 (stamp-count form "data-rf2-source-coord"))
          "both arms of the conditional host root carry the source coord")
      (is (= 2 (stamp-count form "data-rf-view")))
      (is (= "app.cond:branchy:3:4" (data-attr-set form "data-rf2-source-coord")))
      (is (= ":app.cond/branchy" (data-attr-set form "data-rf-view")))))
  (testing "a one-arm (when …) tags the concrete arm; the absent nil arm is exempt"
    (let [form (cljs-emit 'app.cond 'maybe '(when x [:p "y"]) 5 1)]
      (is (= 1 (stamp-count form "data-rf-view"))
          "the element arm is tagged; the implicit nil else carries none")))
  (testing "a (cond …) — nested (if …) — tags every reachable element arm"
    (let [form (cljs-emit 'app.cond 'picker
                          '(cond (= k :a) [:a "A"] (= k :b) [:b "B"] :else [:c "C"]) 2 2)]
      (is (= 3 (stamp-count form "data-rf-view")))))
  (testing "a (case …) tags every clause branch AND the default"
    (let [form (cljs-emit 'app.cond 'chooser '(case k :a [:a] :b [:b] [:c]) 6 3)]
      (is (= 3 (stamp-count form "data-rf-view"))
          "two clause branches + the default, each a concrete host element")))
  (testing "the if-let family (let over if) descends to both arms"
    (let [form (cljs-emit 'app.cond 'bound '(if-let [v x] [:a v] [:b]) 7 3)]
      (is (= 2 (stamp-count form "data-rf-view")))))
  (testing "the whole if-let family emits through the real cljs path (rf2-u53yy.4 repair)"
    ;; if-some / when-some exercise the generated host-qualified `(not= temp nil)`
    ;; test; when-let / when-some exercise the `if … nil` (never generated `when`)
    ;; single-body shape. Two-arm forms stamp both arms; one-arm forms stamp the
    ;; concrete arm only (the implicit nil else is exempt).
    (is (= 2 (stamp-count (cljs-emit 'app.cond 'som '(if-some [v x] [:a v] [:b]) 7 3)
                          "data-rf-view"))
        "if-some descends to both arms")
    (is (= 1 (stamp-count (cljs-emit 'app.cond 'wl '(when-let [v x] [:a v]) 7 3)
                          "data-rf-view"))
        "when-let stamps the one concrete arm; the nil else is exempt")
    (is (= 1 (stamp-count (cljs-emit 'app.cond 'ws '(when-some [v x] [:a v]) 7 3)
                          "data-rf-view"))
        "when-some stamps the one concrete arm; the nil else is exempt")))

(deftest non-host-roots-carry-no-annotation
  (testing "a fragment root is exempt (no positional host node)"
    (let [form (cljs-emit 'app.frag 'grouped '[:<> [:p] [:p]] 1 1)]
      (is (nil? (annotation-gate form)))
      (is (zero? (stamp-count form "data-rf2-source-coord")))))
  (testing "per-branch exemption survives the conditional descent: an element arm
            is tagged, a fragment arm is not"
    (let [form (cljs-emit 'app.mix 'mixed '(if x [:<> [:p] [:p]] [:div "d"]) 4 4)]
      (is (= 1 (stamp-count form "data-rf-view"))
          "only the [:div] element arm is tagged; the fragment arm carries none"))))

(deftest render-key-is-not-stamped-on-the-dom-at-render-time
  ;; render-key is minted at connected commit (reactive/commit*), AFTER this
  ;; child host element commits — no honest value exists at render time, so the
  ;; compiler emit annotates identity only (source-coord + view id), never a
  ;; stale render-key. A commit-time DOM stamp is a separate substrate concern.
  (let [form (cljs-emit 'app.rk 'no-rk '[:div "x"] 2 2)]
    (is (not (str/includes? (pr-str form) "data-rf-render-key"))
        "no render-key attribute is stamped by the compiler emit")))
