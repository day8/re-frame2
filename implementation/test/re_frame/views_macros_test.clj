(ns re-frame.views-macros-test
  "Per Spec 004 §reg-view: the defn-shape macro, the auto-id derivation
  rule, the `^{:rf/id ...}` metadata override, the lexical
  `dispatch`/`subscribe` injection, the Form-2 closure case, and the
  compile-error contract for non-defn-shape bodies. Per rf2-d0pi.

  These tests run on the JVM. CLJS-specific Reagent rendering lives in
  the runtime / hot-reload CLJS test files; the macro logic here is
  shared via re-frame.views-macros which is JVM-loadable."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.flows :as flows]
            [re-frame.views-macros :as vm]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (rf/init!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- shape: defn-shape macro auto-defs the symbol ------------------------

(deftest reg-view-auto-defs-the-symbol
  (testing "(reg-view sym [args] body) defs sym to the registered render fn"
    (rf/reg-view widget-a [n]
      [:span "w-" n])
    ;; The Var was defined.
    (let [resolved (resolve `widget-a)]
      (is (some? resolved)
          "the macro defs a Var named after the supplied symbol")
      (is (fn? @resolved)
          "the Var holds a callable render fn"))
    ;; And the registry slot is populated under the auto-derived id.
    ;; The id is taken from (ns-name *ns*) at macro-expansion time,
    ;; which for this test file is `re-frame.views-macros-test` —
    ;; matched literally here rather than via runtime *ns* (the test
    ;; runner's *ns* is the test-runner ns, not the test file's ns).
    (is (some? (rf/get-view :re-frame.views-macros-test/widget-a))
        "the view is registered under (keyword 'this-ns' 'sym)")))

;; ---- auto-id derivation --------------------------------------------------

(deftest reg-view-auto-id-derives-from-ns-and-sym
  (testing "the registered id is (keyword (str *ns*) (str sym)) when no
            metadata override is present"
    (rf/reg-view widget-b [_n] [:p "b"])
    (is (some? (rf/get-view :re-frame.views-macros-test/widget-b))
        "the registered id matches (keyword *ns* sym)")))

;; ---- ^{:rf/id ...} metadata override -------------------------------------

(deftest reg-view-metadata-override-takes-precedence
  (testing "^{:rf/id :explicit/id} on the symbol wins over the auto-derived id"
    (rf/reg-view ^{:rf/id :explicit/widget} widget-c [_n] [:p "c"])
    (is (some? (rf/get-view :explicit/widget))
        "the registered id is the metadata override")
    (is (nil? (rf/get-view :re-frame.views-macros-test/widget-c))
        "the auto-derived id is NOT registered when the override is present")))

;; ---- compile-error contract ----------------------------------------------

(deftest reg-view-rejects-var-ref-body
  (testing "(reg-view sym some-fn) — a symbol where the args vector should be
            — throws at macroexpand"
    (let [exp-fn (fn []
                   (eval `(rf/reg-view bad-var ~'some-fn-ref)))
          err    (try (exp-fn) nil
                      (catch Exception e
                        (or (some-> e .getCause .getMessage)
                            (.getMessage e))))]
      (is (some? err)
          "macroexpand throws when the second arg is a symbol")
      (is (re-find #"args vector" err)
          "the message points at the missing args vector")
      (is (re-find #"reg-view\*" err)
          "the message points the user at the reg-view* escape hatch"))))

(deftest reg-view-rejects-create-class-body
  (testing "(reg-view sym (reagent.core/create-class …)) — a list where the
            args vector should be — throws at macroexpand"
    (let [exp-fn (fn []
                   (eval `(rf/reg-view bad-cc (reagent.core/create-class {}))))
          err    (try (exp-fn) nil
                      (catch Exception e
                        (or (some-> e .getCause .getMessage)
                            (.getMessage e))))]
      (is (some? err)
          "macroexpand throws when the second arg is a create-class call")
      (is (re-find #"args vector" err)
          "the message points at the missing args vector")
      (is (re-find #"reg-view\*" err)
          "the message points the user at the reg-view* escape hatch"))))

(deftest reg-view-rejects-computed-body
  (testing "(reg-view sym (some-fn-returning-a-fn)) — a non-fn call where
            the args vector should be — throws at macroexpand"
    (let [exp-fn (fn []
                   (eval `(rf/reg-view bad-comp (~'compute-render-fn))))
          err    (try (exp-fn) nil
                      (catch Exception e
                        (or (some-> e .getCause .getMessage)
                            (.getMessage e))))]
      (is (some? err)
          "macroexpand throws when the second arg is a non-fn call")
      (is (re-find #"args vector" err)
          "the message points at the missing args vector"))))

;; ---- error message template ----------------------------------------------

(deftest reg-view-error-message-matches-template
  (testing "the compile-error message follows the documented template"
    (let [err (try (eval `(rf/reg-view broken ~'naked-symbol))
                   nil
                   (catch Exception e
                     (or (some-> e .getCause .getMessage)
                         (.getMessage e))))]
      (is (some? err))
      (is (re-find #"reg-view second argument must be an args vector" err)
          "leading clause matches the template")
      (is (re-find #"defn-shape" err)
          "mentions defn-shape")
      (is (re-find #"For runtime registration, use" err)
          "directs the user to the escape hatch")
      (is (re-find #"reg-view\*" err)
          "names reg-view* explicitly"))))

;; ---- docstring slot ------------------------------------------------------

(deftest reg-view-accepts-docstring
  (testing "(reg-view sym \"doc\" [args] body) accepts a docstring slot,
            defn-style"
    (rf/reg-view docced "the doc" [n] [:p n])
    (let [v (resolve `docced)]
      (is (some? v))
      (is (fn? @v)))))

;; ---- expansion under the legacy import path ------------------------------
;;
;; Existing examples use `(:require-macros [re-frame.views-macros :refer
;; [reg-view]])`. That surface forwards to the canonical expander; the
;; expansion shape and behaviour are identical. Cover the legacy path here
;; so we don't lose it without noticing.

(deftest reg-view-via-views-macros-import
  (testing "(re-frame.views-macros/reg-view sym [args] body) emits the same
            expansion shape as re-frame.core/reg-view"
    (let [exp-core (macroexpand `(rf/reg-view ~'expand-test [] [:p]))
          exp-vm   (macroexpand `(re-frame.views-macros/reg-view
                                   ~'expand-test [] [:p]))]
      ;; Both expansions share their structural skeleton.
      (is (= 'do (first exp-core)))
      (is (= 'do (first exp-vm)))
      (is (= 'def (first (last exp-core))))
      (is (= 'def (first (last exp-vm)))))))

;; ---- expander helpers expose stable shape --------------------------------

(deftest expand-reg-view-helper-shape
  (testing "vm/expand-reg-view returns a (do binding+def) form"
    (let [exp (vm/expand-reg-view {:line 1 :column 1}
                                  'my.ns "my_ns.cljc" 'my-widget '([] [:p]))]
      (is (= 'do (first exp)))
      (is (= 'def (first (last exp))))
      (is (= 'my-widget (second (last exp))))))

  (testing "vm/parse-reg-view-args parses the three accepted shapes"
    (is (= {:docstring nil :args '[] :body nil}
           (vm/parse-reg-view-args '([]))))
    (is (= {:docstring "doc" :args '[a] :body '([:p a])}
           (vm/parse-reg-view-args '("doc" [a] [:p a]))))
    (is (nil? (vm/parse-reg-view-args '(some-symbol)))
        "a single non-vector arg is invalid")
    (is (nil? (vm/parse-reg-view-args '((reagent.core/create-class {}))))
        "a list where the args vector should be is invalid")))
