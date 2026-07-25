(ns fixture.bypass-let-bound-token)

;; NEGATIVE (rf2-u3otj) — a CONFORMANT message that is `let`-bound before it is
;; thrown. The runtime message is a human sentence plus the trailing
;; `[:rf.error/…]` token, exactly as Spec 009 §The thrown-error shape requires;
;; only the `ex-info` call site spells it as the symbol `msg`. This is the shape
;; `re-frame.story/configure!` writes, and the gate must NOT report it.

(defn configure!
  [unknown known-keys]
  (let [msg (str "configure! got unknown key(s): "
                 (pr-str (vec unknown))
                 " — known keys are " (pr-str known-keys) ". "
                 "Fix the call site. [:rf.error/unknown-story-config-key]")]
    (throw (ex-info msg
                    {:rf.error/id :rf.error/unknown-story-config-key
                     :where       'rf.story/configure!
                     :recovery    :fix-call-site
                     :reason      msg
                     :unknown     (vec unknown)}))))

;; The same one hop away through the ASSEMBLED token form — the bracket pair is
;; split across the `(str …)` pieces with a LITERAL keyword between them.
(defn assembled
  [reason]
  (let [msg (str reason " [" :rf.error/assembled-let-bound "]")]
    (throw (ex-info msg {:rf.error/id :rf.error/assembled-let-bound
                         :where       'rf/assembled}))))

;; A `human-message` derivation bound before the throw is equally conformant.
(defn via-builder
  [data]
  (let [msg (human-message :rf.error/via-builder data)]
    (throw (ex-info msg {:rf.error/id :rf.error/via-builder
                         :where       'rf/via-builder}))))

;; INNERMOST binding wins, the way Clojure resolves it: the outer `msg` has no
;; token, the inner one does, and the inner one is what is thrown.
(defn shadowed
  [reason]
  (let [msg (str "outer sentence with no token at all")]
    (let [msg (str reason " [:rf.error/shadowed-inner]")]
      (throw (ex-info msg {:rf.error/id :rf.error/shadowed-inner
                           :where       'rf/shadowed
                           :outer       msg})))))

;; LAST binding of the symbol in one vector wins, likewise.
(defn rebound
  [reason]
  (let [msg (str "no token here")
        msg (str reason " [:rf.error/rebound-last]")]
    (throw (ex-info msg {:rf.error/id :rf.error/rebound-last
                         :where       'rf/rebound}))))
