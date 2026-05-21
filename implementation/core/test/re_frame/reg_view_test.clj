(ns re-frame.reg-view-test
  "Per Spec 004 §reg-view: the defn-shape macro, the auto-id derivation
  rule, the `^{:rf/id ...}` metadata override, the lexical
  `dispatch`/`subscribe` injection, the Form-2 closure case, and the
  compile-error contract for non-defn-shape bodies. Per rf2-d0pi.

  These tests run on the JVM. CLJS-specific Reagent rendering lives in
  the runtime / hot-reload CLJS test files; the macro logic here lives
  in re-frame.core (JVM-loadable). Per rf2-4lc9o the legacy
  `re-frame.views-macros` import path was cut; the expander helpers
  moved to `re-frame.core`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (rf/init! plain-atom/adapter)
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
    ;; which for this test file is `re-frame.reg-view-test` —
    ;; matched literally here rather than via runtime *ns* (the test
    ;; runner's *ns* is the test-runner ns, not the test file's ns).
    (is (some? (rf/view :re-frame.reg-view-test/widget-a))
        "the view is registered under (keyword 'this-ns' 'sym)")))

;; ---- auto-id derivation --------------------------------------------------

(deftest reg-view-auto-id-derives-from-ns-and-sym
  (testing "the registered id is (keyword (str *ns*) (str sym)) when no
            metadata override is present"
    (rf/reg-view widget-b [_n] [:p "b"])
    (is (some? (rf/view :re-frame.reg-view-test/widget-b))
        "the registered id matches (keyword *ns* sym)")))

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

(deftest reg-view-rejects-var-ref-body
  (testing "(reg-view sym some-fn) — a symbol where the args vector should be
            — throws at macroexpand"
    (let [reason (reg-view-error-reason
                   (fn [] (eval `(rf/reg-view bad-var ~'some-fn-ref))))]
      (is (some? reason)
          "macroexpand throws when the second arg is a symbol")
      (is (re-find #"args vector" reason)
          ":reason points at the missing args vector")
      (is (re-find #"reg-view\*" reason)
          ":reason points the user at the reg-view* escape hatch"))))

(deftest reg-view-rejects-create-class-body
  (testing "(reg-view sym (reagent.core/create-class …)) — a list where the
            args vector should be — throws at macroexpand"
    (let [reason (reg-view-error-reason
                   (fn [] (eval `(rf/reg-view bad-cc (reagent.core/create-class {})))))]
      (is (some? reason)
          "macroexpand throws when the second arg is a create-class call")
      (is (re-find #"args vector" reason)
          ":reason points at the missing args vector")
      (is (re-find #"reg-view\*" reason)
          ":reason points the user at the reg-view* escape hatch"))))

(deftest reg-view-rejects-computed-body
  (testing "(reg-view sym (some-fn-returning-a-fn)) — a non-fn call where
            the args vector should be — throws at macroexpand"
    (let [err (reg-view-error-reason
                (fn [] (eval `(rf/reg-view bad-comp (~'compute-render-fn)))))]
      (is (some? err)
          "macroexpand throws when the second arg is a non-fn call")
      (is (re-find #"args vector" err)
          "the message points at the missing args vector"))))

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
      (is (some? v))
      (is (fn? @v)))))

;; ---- return-value contract (rf2-hzos) ------------------------------------
;;
;; Per Conventions §`reg-*` return-value convention: every `reg-*` macro
;; returns its primary id — the keyword the caller registered with. The
;; auto-def of the Var is a side effect; the macro's terminal value is
;; the id.

(deftest reg-view-returns-auto-derived-id
  (testing "(reg-view sym [args] body) returns the auto-derived id"
    (let [ret (rf/reg-view ret-auto [n] [:p n])]
      (is (= :re-frame.reg-view-test/ret-auto ret)
          "the macro returns (keyword *ns* sym), not the auto-defed Var"))))

(deftest reg-view-returns-metadata-override-id
  (testing "(reg-view ^{:rf/id :explicit/id} sym [args] body) returns the
            override id"
    (let [ret (rf/reg-view ^{:rf/id :explicit/ret-meta} ret-meta [n] [:p n])]
      (is (= :explicit/ret-meta ret)
          "the macro returns the :rf/id override, not the auto-derived id
           and not the auto-defed Var"))))

(deftest reg-view-returns-id-with-docstring
  (testing "(reg-view sym \"doc\" [args] body) returns the id (docstring
            does not change the return value)"
    (let [ret (rf/reg-view ret-doc "the doc" [n] [:p n])]
      (is (= :re-frame.reg-view-test/ret-doc ret)))))

;; ---- expander helpers expose stable shape --------------------------------

(deftest expand-reg-view-helper-shape
  (testing "rf/expand-reg-view returns a (do binding+def+id) form. Per
            rf2-hzos: the terminal expression is the id (so the macro's
            return value is the id, matching the reg-* return-value
            contract). The penultimate form is the auto-def of the Var."
    (let [exp     (rf/expand-reg-view {:line 1 :column 1}
                                      'my.ns "my_ns.cljc" 'my-widget '([] [:p]))
          def-form (last (butlast exp))]
      (is (= 'do (first exp)))
      (is (= :my.ns/my-widget (last exp))
          "the terminal expression is the registered id")
      (is (= 'def (first def-form)))
      (is (= 'my-widget (second def-form)))))

  ;; rf2-atsv regression guard. The bug shape was an outer
  ;; (def x (reg-view :id ...)) wrapper that double-def'd against the
  ;; macro's own internal def. rf2-d0pi removed the substrate by making
  ;; reg-view defn-shape — the macro itself emits the (single) def, and
  ;; the legacy outer-def wrapper no longer compiles. Pin: exactly ONE
  ;; def in the full expansion, no matter the input shape.
  (testing "rf2-atsv: expansion contains exactly one def form"
    (letfn [(count-defs [form]
              (cond
                (and (seq? form) (= 'def (first form)))
                (+ 1 (apply + (map count-defs (rest form))))
                (coll? form)
                (apply + (map count-defs form))
                :else 0))]
      (let [exp-plain   (rf/expand-reg-view {} 'my.ns "my_ns.cljc"
                                            'plain-view '([] [:p]))
            exp-doc     (rf/expand-reg-view {} 'my.ns "my_ns.cljc"
                                            'docced-view '("a doc" [] [:p]))
            exp-id-meta (rf/expand-reg-view {} 'my.ns "my_ns.cljc"
                                            (with-meta 'meta-view
                                              {:rf/id :explicit/id})
                                            '([] [:p]))]
        (is (= 1 (count-defs exp-plain))
            "plain (reg-view sym [args] body) emits exactly one def")
        (is (= 1 (count-defs exp-doc))
            "(reg-view sym docstring [args] body) emits exactly one def")
        (is (= 1 (count-defs exp-id-meta))
            "(reg-view ^{:rf/id ...} sym [args] body) emits exactly one def"))))

  (testing "rf/parse-reg-view-args parses the three accepted shapes"
    (is (= {:docstring nil :args '[] :body nil}
           (rf/parse-reg-view-args '([]))))
    (is (= {:docstring "doc" :args '[a] :body '([:p a])}
           (rf/parse-reg-view-args '("doc" [a] [:p a]))))
    (is (nil? (rf/parse-reg-view-args '(some-symbol)))
        "a single non-vector arg is invalid")
    (is (nil? (rf/parse-reg-view-args '((reagent.core/create-class {}))))
        "a list where the args vector should be is invalid")))

;; ---- compile-time component-shape fold (rf2-yfbx, Stage 4-C, rf2-6hyy) ---
;;
;; reagent-slim's `reagent2.impl.component` ships a `classify-form-body`
;; helper consumed by `expand-reg-view` via `requiring-resolve`. The
;; integration test for end-to-end fold (helper on classpath → form-tag
;; lands in the registry slot meta) lives in
;; implementation/adapters/reagent-slim/test/. This file exercises the
;; absence-graceful path: when the helper is NOT on the classpath, the
;; macro emits an unstamped expansion (UIx- or Helix-only builds).
;;
;; Per the rf2-yfbx decision: NO separate `defview` macro. The fold is
;; in `reg-view`'s expansion — that is the canonical view-registration
;; surface.

(deftest reg-view-fold-graceful-without-reagent-slim
  (testing "expand-reg-view emits a usable form-tag-free expansion when
            reagent2.impl.component is absent from the classpath"
    ;; The core test classpath does NOT include reagent-slim, so
    ;; expand-reg-view's requiring-resolve returns nil and the
    ;; expansion stamps no :reagent2/form meta.
    (rf/reg-view fold-no-rs [n] [:p n])
    (let [slot-meta (registrar/lookup :view
                      :re-frame.reg-view-test/fold-no-rs)]
      (is (some? slot-meta) "the view is registered")
      (is (nil? (:reagent2/form slot-meta))
          "no form tag stamped — UIx/Helix-only build path"))))
