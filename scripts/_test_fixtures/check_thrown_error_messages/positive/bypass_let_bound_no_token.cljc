(ns fixture.bypass-let-bound-no-token)

;; POSITIVE — the let-binding resolution must not become a way to
;; PASS. Every throw below is a genuine builder bypass and must still be
;; reported. Three findings expected.

;; 1. A `let`-bound message with NO token at all. Resolution finds the bound
;;    form and re-tests it — the bound form fails the rule on its own text, so
;;    the site fires exactly as an inline bare message does.
(defn bound-but-bare
  [what]
  (let [msg (str "something went wrong with " what)]
    (throw (ex-info msg {:rf.error/id :rf.error/bound-but-bare
                         :where       'rf/bound-but-bare}))))

;; 2. The COMPUTED discriminator, deliberately NOT resolved: the runtime
;;    message carries the token, the source text cannot, and accepting a lone
;;    symbol between the brackets would let any symbol through.
(defn computed-discriminator
  [id reason]
  (throw (ex-info (str reason " [" id "]")
                  {:rf.error/id id
                   :where       'rf/computed-discriminator
                   :reason      reason})))

;; 3. A SIBLING `let` binds a perfectly conformant `msg`, but it does not
;;    enclose the throw below it. Resolution must not reach across scopes.
(defn sibling-scope
  [reason]
  (let [msg (str reason " [:rf.error/sibling-conformant]")]
    (prn msg))
  (throw (ex-info msg {:rf.error/id :rf.error/sibling-scope
                       :where       'rf/sibling-scope})))
