(ns re-frame.reg-view-test
  "Per Spec 001 §Allowed forms of the middle slot and Conventions
  §`reg-view` auto-id derivation rule: the defn-shape macro, the
  auto-id derivation
  rule, the `^{:rf/id ...}` metadata override, the lexical
  `dispatch`/`subscribe` injection, the Form-2 closure case, and the
  compile-error contract for non-defn-shape bodies.

  These tests run on the JVM. CLJS-specific Reagent rendering lives in
  the runtime / hot-reload CLJS test files; the macro logic here lives
  in re-frame.core (JVM-loadable). There is no
  `re-frame.views-macros` namespace; the expander helpers live in
  `re-frame.core`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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

;; ---- shape: defn-shape macro auto-defs the symbol ------------------------

(deftest reg-view-auto-defs-the-symbol
  (testing "(reg-view sym [args] body) defs sym to the registered render fn"
    (rf/reg-view widget-a [n]
      [:span "w-" n])
    ;; The Var was defined.
    (let [resolved (resolve `widget-a)]
      (is (fn? @resolved)
          "the macro defs a Var named after the supplied symbol, holding a
           callable render fn"))
    ;; And the registry slot is populated under the auto-derived id.
    ;; The id is taken from (ns-name *ns*) at macro-expansion time,
    ;; which for this test file is `re-frame.reg-view-test` —
    ;; matched literally here rather than via runtime *ns* (the test
    ;; runner's *ns* is the test-runner ns, not the test file's ns).
    (is (some? (rf/view :re-frame.reg-view-test/widget-a))
        "the view is registered under (keyword 'this-ns' 'sym)")))

;; ---- ^{:rf/id ...} metadata override -------------------------------------

(deftest reg-view-metadata-override-takes-precedence
  (testing "^{:rf/id :explicit/id} on the symbol wins over the auto-derived id"
    (rf/reg-view ^{:rf/id :explicit/widget} widget-c [_n] [:p "c"])
    (is (some? (rf/view :explicit/widget))
        "the registered id is the metadata override")
    (is (nil? (rf/view :re-frame.reg-view-test/widget-c))
        "the auto-derived id is NOT registered when the override is present")))

;; ---- compile-error contract ----------------------------------------------

;; The macro throw conforms to the canonical thrown-error shape
;; (Spec 009): the message is the stringified discriminator kw and the
;; human-readable prose rides on the `:reason` ex-data slot. A
;; macroexpansion error wraps the original ex-info as its cause, so the
;; ex-data is read off the cause when present.

(defn- ex-data-in-chain
  "Walk the cause chain of `e` and return the first ex-data carrying the
  canonical `:rf.error/id` slot — the compiler wraps macro-side
  ex-infos in a CompilerException whose own ex-data is a
  `:clojure.error/*` map, so we skip past it to the real throw."
  [e]
  (loop [t e]
    (when t
      (let [d (ex-data t)]
        (if (contains? d :rf.error/id)
          d
          (recur (.getCause ^Throwable t)))))))

(defn- reg-view-error-reason
  "Run `thunk` (which must throw a reg-view macro error) and return the
  `:reason` from the canonical ex-data (walking the compiler's cause
  chain)."
  [thunk]
  (try (thunk) nil
       (catch Throwable e
         (:reason (ex-data-in-chain e)))))

;; A bare symbol where the args vector should be — `(reg-view sym some-fn)` —
;; is the input `reg-view-error-message-matches-template` below throws on.

(deftest reg-view-rejects-create-class-body
  (testing "(reg-view sym (reagent.core/create-class …)) — a list where the
            args vector should be — throws at macroexpand"
    (let [reason (reg-view-error-reason
                   (fn [] (eval `(rf/reg-view bad-cc (reagent.core/create-class {})))))]
      (is (re-find #"args vector" reason)
          "macroexpand throws when the second arg is a create-class call, and
           its :reason points at the missing args vector")
      (is (re-find #"reg-view\*" reason)
          ":reason points the user at the reg-view* escape hatch"))))

;; ---- error message template ----------------------------------------------

(deftest reg-view-error-message-matches-template
  (testing "the canonical :reason follows the documented template; the
            message string is the stringified discriminator kw"
    (let [{:keys [reason id]}
          (try (eval `(rf/reg-view broken ~'naked-symbol))
               nil
               (catch Throwable e
                 (let [d (ex-data-in-chain e)]
                   {:reason (:reason d)
                    :id     (:rf.error/id d)})))]
      (is (= :rf.error/reg-view-bad-args id)
          ":rf.error/id is the canonical discriminator")
      (is (re-find #"reg-view's second argument must be an args vector" reason)
          "leading clause matches the template")
      (is (re-find #"defn-shape" reason)
          "mentions defn-shape")
      (is (re-find #"For runtime registration, use" reason)
          "directs the user to the escape hatch")
      (is (re-find #"reg-view\*" reason)
          "names reg-view* explicitly"))))

;; ---- docstring slot ------------------------------------------------------

(deftest reg-view-accepts-docstring
  (testing "(reg-view sym \"doc\" [args] body) accepts a docstring slot,
            defn-style"
    (rf/reg-view docced "the doc" [n] [:p n])
    (let [v (resolve `docced)]
      (is (fn? @v)))))

;; ---- return-value contract -----------------------------------------------
;;
;; Per Conventions §`reg-*` return-value convention: every `reg-*` macro
;; returns its primary id — the keyword the caller registered with. The
;; auto-def of the Var is a side effect; the macro's terminal value is
;; the id.

(deftest reg-view-returns-the-registered-id-in-every-accepted-shape
  (testing "the macro returns the registered id — never the auto-defed Var —
            whichever accepted shape it is called in"
    (doseq [[shape expected ret]
            [["(reg-view sym [args] body) returns the auto-derived (keyword *ns* sym)"
              :re-frame.reg-view-test/ret-auto
              (rf/reg-view ret-auto [n] [:p n])]
             ["(reg-view ^{:rf/id :explicit/id} sym [args] body) returns the override,
               not the auto-derived id"
              :explicit/ret-meta
              (rf/reg-view ^{:rf/id :explicit/ret-meta} ret-meta [n] [:p n])]
             ["(reg-view sym \"doc\" [args] body) returns the id — the docstring
               does not change the return value"
              :re-frame.reg-view-test/ret-doc
              (rf/reg-view ret-doc "the doc" [n] [:p n])]]]
      (is (= expected ret) shape))))

;; ---- the component shape is detected at render time ----------------------
;;
;; `reg-view`'s expansion carries no component-shape tag, whatever is on the
;; classpath: the registry slot's metadata holds what the author attached, and
;; the render fn is a bare `(fn …)` form. A Reagent-family substrate detects
;; Form-1 vs Form-2 when it renders the view, and metadata on a `(fn …)` form
;; would make the ClojureScript analyzer wrap it in `with-meta`, a
;; `cljs.core/MetaFn`.

(deftest reg-view-expansion-carries-no-form-tag
  (testing "the registered slot carries no :reagent2/form key"
    (rf/reg-view plain-shape [n] [:p n])
    (let [slot-meta (rf.registrar/lookup :view
                      :re-frame.reg-view-test/plain-shape)]
      (is (some? slot-meta) "the view is registered")
      (is (not (contains? slot-meta :reagent2/form))
          "no form tag in the registry slot's metadata")))
  (testing "the expansion's reg-view* call carries no form tag and a bare (fn …) form"
    (let [exp (rf/expand-reg-view {:line 1 :column 1} 'my.ns "my_ns.cljc"
                                  'plain '([n] [:p n]))
          [_ id slot-meta fn-form]
          (some #(when (and (seq? %) (= 're-frame.core/reg-view* (first %))) %)
                (tree-seq coll? seq exp))]
      (is (= :my.ns/plain id)
          "precondition: the expansion's reg-view* call was found")
      (is (not (contains? slot-meta :reagent2/form))
          "no form tag in the slot metadata the expansion passes")
      (is (= 'clojure.core/fn (first fn-form))
          "the render fn is emitted as a (fn …) form")
      (is (nil? (meta fn-form))
          "the (fn …) form carries no metadata, so it compiles to a plain JS
           function rather than a MetaFn"))))
