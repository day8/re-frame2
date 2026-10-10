(ns fixture.bypass-let-bound-shadowed)

;; POSITIVE — an OUTER `let` binds a perfectly
;; conformant `msg`, and a binder BETWEEN it and the `ex-info` shadows the name.
;; The symbol thrown is the SHADOW, which carries no token, so every site below
;; is a genuine builder bypass. A resolver that searches enclosing `let`s only
;; finds the outer binding and greens all four — the false GREEN this gate
;; must never emit. One site per way the scope proof refuses a crossing: a
;; binding vector naming the symbol, an unrecognised head, a binder with no
;; binding vector, and an `fn` SELF-REFERENCE NAME.

;; 1. A nested `fn` PARAMETER shadows the outer binding.
(defn make-thrower
  []
  (let [msg (str "outer conformant [:rf.error/outer]")]
    (fn [msg]
      (throw (ex-info msg {:rf.error/id :rf.error/fn-param-shadow
                           :where       'rf/fn-param-shadow})))))

;; 2. `catch` binds its exception name with no binding vector at all.
(defn caught
  [f]
  (let [msg (str "outer conformant [:rf.error/outer]")]
    (try
      (f)
      (catch #?(:clj Exception :cljs js/Error) msg
        (throw (ex-info msg {:rf.error/id :rf.error/catch-shadow
                             :where       'rf/catch-shadow}))))))

;; 3. An `fn` written as ARITY LISTS — the parameter vector is not a direct
;;    child of the `fn`, so the binder is the arity list itself.
(defn arities
  []
  (let [msg (str "outer conformant [:rf.error/outer]")]
    (fn
      ([msg]
       (throw (ex-info msg {:rf.error/id :rf.error/arity-shadow
                            :where       'rf/arity-shadow}))))))

;; 4. An `fn` SELF-REFERENCE NAME. The parameter vector `[x]` is spotless;
;;    the shadow is the name `fn` binds to the function itself, which sits
;;    BEFORE the vector. A proof that reads only the vector greens this.
(defn self-named
  []
  (let [msg (str "outer conformant [:rf.error/outer]")]
    (fn msg [x]
      (throw (ex-info msg {:rf.error/id :rf.error/fn-name-shadow
                           :where       'rf/fn-name-shadow
                           :extra       x})))))
