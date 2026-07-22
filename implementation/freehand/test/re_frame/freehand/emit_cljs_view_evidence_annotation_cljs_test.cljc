(ns re-frame.freehand.emit-cljs-view-evidence-annotation-cljs-test
  "98.1 slice (c) — cross-host golden for the host-root view-evidence annotation
  wiring (rf2-hac8p). The emitter is `.cljc`, so the exact CLJS expansion is data
  on BOTH hosts: a host-root-marked element lowers to the DEV-gated
  `data-rf2-source-coord` / `data-rf-view` stamp on its props object, and an
  unmarked element lowers unchanged."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.emit-cljs :as emit]
            [re-frame.freehand.compiler.env :as env]))

(defn- forms-of [form] (tree-seq coll? seq form))

(defn- emitted
  "Analyze `form`, optionally stamp the host-root annotation marker, and lower it
  to a single inline CLJS form (data on this host)."
  ([form] (emitted form nil))
  ([form annotate]
   (let [e   (env/make-env {:host :clj :ns-sym 'app.test})
         ast (ana/analyze e form)]
     (emit/emit-inline (cond-> ast annotate (assoc :rf.ui/annotate annotate))
                       'annotation-golden))))

(defn- data-attr-set [form attr]
  (some (fn [x]
          (when (and (seq? x)
                     (= 'cljs.core/unchecked-set (first x))
                     (= attr (nth x 2 nil)))
            (nth x 3 nil)))
        (forms-of form)))

(defn- annotation-gate
  "The `(when <gate> …)` form wrapping the source-coord stamp, or nil. Matches the
  `when` by NAME — the emitter's syntax-quote resolves it against the host core
  (`clojure.core/when` on the JVM runner, `cljs.core/when` on the node runner)."
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

(deftest marked-host-root-lowers-to-a-dev-gated-props-stamp
  (let [form (emitted '[:div {:title t} "x"]
                      {:source-coord "app.test:widget:12:3"
                       :view-tag ":app.test/widget"})
        gate (annotation-gate form)]
    (is (some? gate) "the marked host root gains the annotation stamp")
    (is (= 'js/goog.DEBUG (second gate))
        "goog.DEBUG-gated so :advanced folds the stamp — and its literal
         attribute strings — out of the production bundle")
    (is (= "app.test:widget:12:3" (data-attr-set form "data-rf2-source-coord")))
    (is (= ":app.test/widget" (data-attr-set form "data-rf-view")))
    (is (= 2 (count (filter #(and (seq? %)
                                  (= 'cljs.core/unchecked-set (first %))
                                  (#{"data-rf2-source-coord" "data-rf-view"}
                                   (nth % 2 nil)))
                            (forms-of form))))
        "exactly the two annotation attributes, nothing more")))

(deftest an-unmarked-element-is-not-annotated
  (let [form (emitted '[:div {:title t} "x"])]
    (is (nil? (annotation-gate form))
        "no host-root marker → the emitter stamps nothing")
    (is (nil? (data-attr-set form "data-rf2-source-coord")))
    (is (nil? (data-attr-set form "data-rf-view")))))

(deftest the-stamp-mutates-the-props-object-before-the-jsx-runtime
  ;; the annotated props are a single-evaluation `let` whose body returns the
  ;; SAME object it mutated — mirroring the trusted-markup spread branch — so the
  ;; JSX runtime receives the already-stamped object.
  (let [form (emitted '[:div "x"]
                      {:source-coord "app.test:c:1:1" :view-tag ":app.test/c"})
        the-let (first (filter #(and (seq? %)
                                     (= "let" (name (first %)))
                                     (some (fn [x]
                                             (and (seq? x)
                                                  (= 'cljs.core/unchecked-set (first x))))
                                           (forms-of %)))
                               (forms-of form)))]
    (is (some? the-let))
    (let [[_ bindings & body] the-let
          bound-sym (first bindings)]
      (is (= bound-sym (last body))
          "the let returns the same props temporary it mutated"))))
