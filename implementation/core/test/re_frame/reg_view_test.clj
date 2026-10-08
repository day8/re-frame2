(ns re-frame.reg-view-test
  "The `reg-view` defn-shape macro on the JVM: the auto-def, the auto-derived
  id and its `^{:rf/id ...}` override, the return value in each accepted shape,
  the bad-args compile error, and an expansion whose render fn carries no
  metadata."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.core-reg-view-macro]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.flows/reset-last-inputs!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(deftest reg-view-auto-defs-the-symbol
  (rf/reg-view widget-a [n]
    [:span "w-" n])
  (is (fn? @(resolve `widget-a)))
  ;; the id comes from the ns at expansion time, so it is spelled literally
  (is (some? (rf/view :re-frame.reg-view-test/widget-a))))

(deftest reg-view-metadata-override-takes-precedence
  (rf/reg-view ^{:rf/id :explicit/widget} widget-c [_n] [:p "c"])
  (is (some? (rf/view :explicit/widget)))
  (is (nil? (rf/view :re-frame.reg-view-test/widget-c))))

(defn- ex-data-in-chain
  "The first ex-data in `e`'s cause chain carrying `:rf.error/id` (the
  compiler wraps a macro's ex-info in a CompilerException)."
  [e]
  (loop [t e]
    (when t
      (let [d (ex-data t)]
        (if (contains? d :rf.error/id)
          d
          (recur (.getCause ^Throwable t)))))))

(deftest reg-view-error-message-matches-template
  ;; a bare symbol where the args vector should be
  (is (= :rf.error/reg-view-bad-args
         (try (eval `(rf/reg-view broken ~'naked-symbol))
              nil
              (catch Throwable e
                (:rf.error/id (ex-data-in-chain e)))))))

(deftest reg-view-returns-the-registered-id-in-every-accepted-shape
  ;; the macro returns the id, never the auto-defed Var (Conventions
  ;; §reg-* return-value convention)
  (doseq [[shape expected ret]
          [["auto-derived id"
            :re-frame.reg-view-test/ret-auto
            (rf/reg-view ret-auto [n] [:p n])]
           ["metadata override"
            :explicit/ret-meta
            (rf/reg-view ^{:rf/id :explicit/ret-meta} ret-meta [n] [:p n])]
           ["with a docstring"
            :re-frame.reg-view-test/ret-doc
            (rf/reg-view ret-doc "the doc" [n] [:p n])]]]
    (is (= expected ret) shape)))

(deftest reg-view-expansion-carries-no-form-tag
  ;; A substrate detects Form-1 vs Form-2 at render time. Metadata on the
  ;; render (fn ...) form would make the CLJS analyzer wrap it in with-meta, a
  ;; MetaFn rather than a plain JS function.
  (let [exp (rf/expand-reg-view {:line 1 :column 1} 'my.ns "my_ns.cljc"
                                'plain '([n] [:p n]))
        [_ id _slot-meta fn-form]
        (some #(when (and (seq? %) (= 're-frame.core/reg-view* (first %))) %)
              (tree-seq coll? seq exp))]
    (is (= :my.ns/plain id) "precondition: the reg-view* call was found")
    (is (= 'clojure.core/fn (first fn-form)))
    (is (nil? (meta fn-form)))))
