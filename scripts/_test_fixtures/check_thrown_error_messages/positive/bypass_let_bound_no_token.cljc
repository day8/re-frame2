(ns fixture.bypass-let-bound-no-token)

;; POSITIVE (rf2-u3otj) — the let-binding resolution must not become a way to
;; PASS. Every throw below is a genuine builder bypass and must still be
;; reported. Six findings expected.

;; 1. A `let`-bound message with NO token at all. Resolution finds the bound
;;    form and re-tests it — the bound form fails the rule on its own text, so
;;    the site fires exactly as it did before.
(defn bound-but-bare
  [what]
  (let [msg (str "something went wrong with " what)]
    (throw (ex-info msg {:rf.error/id :rf.error/bound-but-bare
                         :where       'rf/bound-but-bare}))))

;; 2. A bare symbol that is a FUNCTION PARAMETER, not a local binding. Nothing
;;    encloses it in a `let`, so nothing resolves and the site fires.
(defn param-message
  [msg]
  (throw (ex-info msg {:rf.error/id :rf.error/param-message
                       :where       'rf/param-message})))

;; 3. The COMPUTED discriminator, deliberately NOT resolved: the runtime
;;    message carries the token, the source text cannot, and accepting a lone
;;    symbol between the brackets would let any symbol through.
(defn computed-discriminator
  [id reason]
  (throw (ex-info (str reason " [" id "]")
                  {:rf.error/id id
                   :where       'rf/computed-discriminator
                   :reason      reason})))

;; 4. A SIBLING `let` binds a perfectly conformant `msg`, but it does not
;;    enclose the throw below it. Resolution must not reach across scopes.
(defn sibling-scope
  [reason]
  (let [msg (str reason " [:rf.error/sibling-conformant]")]
    (prn msg))
  (throw (ex-info msg {:rf.error/id :rf.error/sibling-scope
                       :where       'rf/sibling-scope})))

;; 5. Shadowing in the other direction: the OUTER binding carries a token, the
;;    inner one does not, and the inner one is what is thrown.
(defn shadowed-away
  [reason]
  (let [msg (str reason " [:rf.error/outer-had-a-token]")]
    (prn msg)
    (let [msg (str "the inner sentence dropped the token")]
      (throw (ex-info msg {:rf.error/id :rf.error/shadowed-away
                           :where       'rf/shadowed-away})))))

;; 6. A DESTRUCTURED binding is not a binding NAME the resolver accepts, so
;;    nothing resolves and the site fires.
(defn destructured
  [ctx]
  (let [{:keys [msg]} ctx]
    (throw (ex-info msg {:rf.error/id :rf.error/destructured
                         :where       'rf/destructured}))))
