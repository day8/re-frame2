(ns fixture.bypass-let-bound-guarded)

;; NEGATIVE (rf2-u3otj, the #7045 audit) — the scope proof must not become a
;; blanket refusal. A conformant `let`-bound message reaches its `throw` through
;; ordinary control flow in real code (`re-frame.story/configure!` guards both
;; of its throws with a `when`), and crossing a form that introduces nothing
;; cannot change what the symbol means. Zero findings expected.

;; A `when` guard — the shape `re-frame.story/configure!` actually writes.
(defn guarded
  [unknown]
  (let [msg (str "configure! got unknown key(s): "
                 (pr-str (vec unknown))
                 ". Fix the call site. [:rf.error/guarded-when]")]
    (when (seq unknown)
      (throw (ex-info msg {:rf.error/id :rf.error/guarded-when
                           :where       'rf/guarded
                           :reason      msg})))))

;; An `if` / `do` / `cond` chain is equally transparent.
(defn branched
  [ok? reason]
  (let [msg (str reason " [:rf.error/branched]")]
    (if ok?
      nil
      (do
        (prn :about-to-throw)
        (cond
          :else (throw (ex-info msg {:rf.error/id :rf.error/branched
                                     :where       'rf/branched})))))))

;; A nested `let` that binds a DIFFERENT name does not shadow the message.
(defn nested-other-binding
  [reason]
  (let [msg (str reason " [:rf.error/nested-other]")]
    (let [detail (pr-str reason)]
      (throw (ex-info msg {:rf.error/id :rf.error/nested-other
                           :where       'rf/nested-other
                           :detail      detail})))))

;; An `fn` carrying a SELF-REFERENCE NAME that is NOT the message symbol, with
;; clean parameters. Reading the run from the head to the binding vector must
;; not turn every named `fn` into a refusal — only one that names the symbol
;; shadows it (rf2-u3otj, the #7064 audit).
(defn self-named-other
  [reason]
  (let [msg (str reason " [:rf.error/self-named-other]")]
    (fn raise [x]
      (throw (ex-info msg {:rf.error/id :rf.error/self-named-other
                           :where       'rf/self-named-other
                           :extra       x})))))

;; A `try` body, and a reader conditional selecting the platform branch.
(defn platform
  [reason]
  (let [msg (str reason " [:rf.error/platform]")]
    (try
      #?(:clj  (throw (ex-info msg {:rf.error/id :rf.error/platform
                                    :where       'rf/platform}))
         :cljs (throw (ex-info msg {:rf.error/id :rf.error/platform
                                    :where       'rf/platform})))
      (finally (prn :done)))))
