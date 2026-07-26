(ns fixture.bypass-let-bound-shadowed)

;; POSITIVE (rf2-u3otj, the #7045 audit) — an OUTER `let` binds a perfectly
;; conformant `msg`, and a binder BETWEEN it and the `ex-info` shadows the name.
;; The symbol thrown is the SHADOW, which carries no token, so every site below
;; is a genuine builder bypass. A resolver that searches enclosing `let`s only
;; finds the outer binding and greens all nine — the false GREEN this gate must
;; never emit. Nine findings expected.
;;
;; The point of the family is that it has no end: `fn`, `loop`, `if-let`,
;; `doseq`, `catch`, a DESTRUCTURED inner `let`, `letfn`, `as->`, an `fn` arity
;; list — plus whatever binding macro is written next. So the resolution proves
;; its scope by crossing only forms it recognises as introducing nothing, and
;; fails closed on the rest.

;; 1. THE AUDIT WITNESS — a nested `fn` PARAMETER shadows the outer binding.
(defn make-thrower
  []
  (let [msg (str "outer conformant [:rf.error/outer]")]
    (fn [msg]
      (throw (ex-info msg {:rf.error/id :rf.error/fn-param-shadow
                           :where       'rf/fn-param-shadow})))))

;; 2. `loop` rebinds the name for the body (and again through `recur`).
(defn looped
  [xs]
  (let [msg (str "outer conformant [:rf.error/outer]")]
    (loop [msg (first xs)]
      (throw (ex-info msg {:rf.error/id :rf.error/loop-shadow
                           :where       'rf/loop-shadow})))))

;; 3. `if-let` binds the name for its THEN branch only.
(defn conditional
  [ctx]
  (let [msg (str "outer conformant [:rf.error/outer]")]
    (if-let [msg (:message ctx)]
      (throw (ex-info msg {:rf.error/id :rf.error/if-let-shadow
                           :where       'rf/if-let-shadow}))
      nil)))

;; 4. A sequence binder — `doseq` (and `for`, and their `:let` modifiers).
(defn each
  [xs]
  (let [msg (str "outer conformant [:rf.error/outer]")]
    (doseq [msg xs]
      (throw (ex-info msg {:rf.error/id :rf.error/doseq-shadow
                           :where       'rf/doseq-shadow})))))

;; 5. `catch` binds its exception name with no binding vector at all.
(defn caught
  [f]
  (let [msg (str "outer conformant [:rf.error/outer]")]
    (try
      (f)
      (catch #?(:clj Exception :cljs js/Error) msg
        (throw (ex-info msg {:rf.error/id :rf.error/catch-shadow
                             :where       'rf/catch-shadow}))))))

;; 6. A DESTRUCTURED inner `let`. The binding NAME is `{:keys [msg]}`, not
;;    `msg`, so the binding-name match cannot see it — yet it shadows.
(defn destructured-inner
  [ctx]
  (let [msg (str "outer conformant [:rf.error/outer]")]
    (let [{:keys [msg]} ctx]
      (throw (ex-info msg {:rf.error/id :rf.error/destructured-shadow
                           :where       'rf/destructured-shadow})))))

;; 7. `letfn` — the shadow is a parameter of a local function.
(defn local-fn
  []
  (let [msg (str "outer conformant [:rf.error/outer]")]
    (letfn [(raise [msg]
              (throw (ex-info msg {:rf.error/id :rf.error/letfn-shadow
                                   :where       'rf/letfn-shadow})))]
      (raise "no token here"))))

;; 8. `as->` binds a bare symbol in the threading position.
(defn threaded
  [x]
  (let [msg (str "outer conformant [:rf.error/outer]")]
    (as-> x msg
      (throw (ex-info msg {:rf.error/id :rf.error/as-shadow
                           :where       'rf/as-shadow})))))

;; 9. An `fn` written as ARITY LISTS — the parameter vector is not a direct
;;    child of the `fn`, so the binder is the arity list itself.
(defn arities
  []
  (let [msg (str "outer conformant [:rf.error/outer]")]
    (fn
      ([msg]
       (throw (ex-info msg {:rf.error/id :rf.error/arity-shadow
                            :where       'rf/arity-shadow}))))))
