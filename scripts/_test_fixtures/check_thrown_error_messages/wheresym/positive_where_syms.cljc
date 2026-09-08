(ns wheresym.positive-where-syms
  "POSITIVE where-sym fixtures (rf2-z5lv): every one of these must FIRE.

  Two families are planted here, and they are separate on purpose.

  (1) FORMATTING VARIANTS OF THE CALL HEAD. A sibling gate in this directory
      shipped matching only calls whose callee sat tight against the paren —
      and its self-test stayed green, because all five of its positive
      fixtures wrote it that way. The gate and its own tests shared one blind
      spot and agreed with each other (audit #9491). So the head is written
      here tight, behind a space, behind a newline, behind a comment, and
      unqualified, and the self-test pins LINE NUMBERS rather than a count:
      a count cannot separate `found the right ones` from `traded a real hit
      for a false positive`.

  (2) REASONS A SYMBOL DOES NOT RESOLVE. Not-public is not one thing: the
      namespace may not exist, the var may be private by three different
      spellings, or the name may belong to a `defmethod` that extends a
      multimethod without defining anything.

  `re-frame.fixture` is the oracle fixture beside this file. `rf.fixture/…`
  and `re-frame.fixture/…` are the SAME namespace under the canonical alias
  dialect, which is why a private var fires under BOTH spellings — the rule is
  resolvability, never a spelling."
  (:require [re-frame.error :as rf.error]
            [re-frame.error :refer [throw-error!]]))

;; ---- (1) the call head, five reader-equivalent formattings ---------------

(defn ghost-tight []
  (rf.error/throw-error!
    :rf.error/ghost-one
    'rf.fixture/ghost-one
    "the callee sits tight against the paren"))

(defn ghost-space []
  ( rf.error/throw-error!
    :rf.error/ghost-two
    'rf.fixture/ghost-two
    "a space between the paren and the callee"))

(defn ghost-newline []
  (
    rf.error/throw-error!
    :rf.error/ghost-three
    'rf.fixture/ghost-three
    "a newline between the paren and the callee"))

(defn ghost-comment []
  ( ;; a comment, then a newline, then the callee
    rf.error/throw-error!
    :rf.error/ghost-four
    'rf.fixture/ghost-four
    "a comment between the paren and the callee"))

(defn ghost-unqualified-head []
  (throw-error! :rf.error/ghost-five 'rf.fixture/ghost-five
                "an unqualified callee, and the where-sym INLINE on the head line"))

;; ---- (1b) the other two rostered builders --------------------------------
;;
;; `_WHERE_SYM_FORMS` is a roster, and the self-test holds every entry to
;; owning a case here. `throw-inline-interceptor-removed!` is why the where-sym
;; is taken as THE QUOTED-SYMBOL ARGUMENT rather than by index: its where-sym
;; is the FIRST argument, not the second.

(defn ghost-thrown-ex-info []
  (throw (rf.error/thrown-ex-info
           :rf.error/ghost-six
           'rf.fixture/ghost-six
           "thrown-ex-info builds without throwing; same where-sym position")))

(defn ghost-inline-interceptor []
  (rf.error/throw-inline-interceptor-removed!
    'rf.fixture/ghost-seven
    "this builder takes its where-sym FIRST"
    {:entry :x}))

;; ---- (1c) the `:where` SLOT of a hand-built framework ex-data map ---------
;;
;; Invisible to the builder scan, and not a corner: six of the façade-family
;; findings on trunk are slot sites in `re-frame.conformance`. The message here
;; is deliberately CONFORMANT (it carries the token), so the only thing that
;; can fire is the where-sym rule.

(defn ghost-where-slot []
  (throw (ex-info "hand-built payload [:rf.error/ghost-eight]"
                  {:rf.error/id :rf.error/ghost-eight
                   :where       'rf.fixture/ghost-eight
                   :reason      "hand-built payload"})))

;; ---- (2) the reasons a symbol does not resolve ---------------------------

(defn unknown-namespace []
  (rf.error/throw-error!
    :rf.error/no-such-ns
    'rf.nosuch/thing
    "no namespace `re-frame.nosuch` exists in this tree"))

(defn private-defn- []
  (rf.error/throw-error!
    :rf.error/private-defn
    're-frame.fixture/private-fn
    "FULLY QUALIFIED and still a dead door — the var is `defn-`. This is the
     live `'re-frame.router/build-envelope` shape, and it is why the rule
     cannot be `always fully qualify`."))

(defn private-meta-keyword []
  (rf.error/throw-error!
    :rf.error/private-var
    'rf.fixture/private-var
    "`^:private`"))

(defn private-meta-map []
  (rf.error/throw-error!
    :rf.error/private-meta-var
    'rf.fixture/private-meta-var
    "`^{:private true}`"))

(defn defmethod-defines-nothing []
  (rf.error/throw-error!
    :rf.error/foreign-multi
    'rf.fixture/foreign-multi
    "`defmethod` EXTENDS a multimethod; it defines no var, so this name is not
     public in the fixture namespace. Drop the `defmethod` carve-out from
     `_public_names_defined_by` and this site goes green."))
