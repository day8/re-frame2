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

(deftest non-element-roots-carry-no-annotation
  (testing "a conditional root is not a single compiler-owned host node"
    (let [form (cljs-emit 'app.cond 'branchy '(if x [:a] [:b]) 3 4)]
      (is (nil? (annotation-gate form)))
      (is (not (str/includes? (pr-str form) "data-rf2-source-coord"))
          "no host-root annotation on a non-element effective root")
      (is (not (str/includes? (pr-str form) "data-rf-view")))))
  (testing "a fragment root is exempt (no positional host node)"
    (let [form (cljs-emit 'app.frag 'grouped '[:<> [:p] [:p]] 1 1)]
      (is (nil? (annotation-gate form)))
      (is (not (str/includes? (pr-str form) "data-rf2-source-coord"))))))

(deftest render-key-is-not-stamped-on-the-dom-at-render-time
  ;; render-key is minted at connected commit (reactive/commit*), AFTER this
  ;; child host element commits — no honest value exists at render time, so the
  ;; compiler emit annotates identity only (source-coord + view id), never a
  ;; stale render-key. A commit-time DOM stamp is a separate substrate concern.
  (let [form (cljs-emit 'app.rk 'no-rk '[:div "x"] 2 2)]
    (is (not (str/includes? (pr-str form) "data-rf-render-key"))
        "no render-key attribute is stamped by the compiler emit")))
