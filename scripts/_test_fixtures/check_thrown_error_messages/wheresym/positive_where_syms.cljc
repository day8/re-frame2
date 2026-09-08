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

;; ---- (3) A WHERE-SYM NAMING A VAR THAT EXISTS ONLY IN AN INACTIVE FORM ----
;;
;; Audit #9501. The oracle fixture defines each of these names inside a
;; `comment`, a `#_` discard, a quote, a syntax-quote or a discard nested in a
;; live `do` — and nowhere else. So if the oracle goes generous again, these
;; five go GREEN and the gate is back to blessing doors that are not there.
;;
;; A subset control against the manifest cannot catch that: it detects public
;; names MISSING from the derived set, never EXTRA fictitious ones.

(defn ghost-in-a-comment-form []
  (rf.error/throw-error!
    :rf.error/ghost-nine
    'rf.fixture/ghost-in-comment
    "`(comment ...)` evaluates to nil and interns nothing"))

(defn ghost-in-a-reader-discard []
  (rf.error/throw-error!
    :rf.error/ghost-ten
    'rf.fixture/ghost-discarded
    "`#_` discards the form the reader has just read"))

(defn ghost-in-a-quoted-form []
  (rf.error/throw-error!
    :rf.error/ghost-eleven
    'rf.fixture/ghost-quoted
    "a quoted `(defn ...)` is a LIST, not a definition"))

(defn ghost-in-a-syntax-quoted-form []
  (rf.error/throw-error!
    :rf.error/ghost-twelve
    'rf.fixture/ghost-syntax-quoted
    "a syntax-quoted `(defn ...)` resolves its symbols at read time and still
     defines nothing"))

(defn ghost-discarded-inside-a-live-do []
  (rf.error/throw-error!
    :rf.error/ghost-thirteen
    'rf.fixture/ghost-discarded-inside-do
    "the walk DOES descend into `do`, and must carry the reader prefixes down
     with it. Its sibling `do-defined-public` resolves; this one is discarded."))

;; ---- (4) COMMAS, WHICH ARE READER WHITESPACE -----------------------------
;;
;; Audit #9501, and the same class as (1): a reader-equivalent formatting the
;; detector could not see. JVM execution of the three spellings captured
;; IDENTICAL builder arguments, so this is valid source rather than
;; obfuscation. Every site here fires on its SYMBOL AND LINE, and its
;; resolving twin sits in the negative fixture — because a total count cannot
;; tell `found the right ones` from `traded a real hit for a false positive`.
;; `:min-where-syms` sat at 173 against 193 observed, so twenty calls could
;; have gone quiet underneath it without a sound.

(defn ghost-comma-tight-on-the-head []
  (,rf.error/throw-error!
    :rf.error/ghost-fourteen
    'rf.fixture/ghost-comma-one
    "a comma between the paren and the callee"))

(defn ghost-comma-after-the-symbol []
  (rf.error/throw-error!
    :rf.error/ghost-fifteen
    'rf.fixture/ghost-comma-two,
    "a trailing comma on the quoted symbol"))

(defn ghost-comma-everywhere []
  (, rf.error/throw-error!,
    :rf.error/ghost-sixteen,
    'rf.fixture/ghost-comma-three,
    "a comma in every reader-whitespace position at once"))

(defn ghost-comma-in-the-where-slot []
  (throw (ex-info "hand-built payload [:rf.error/ghost-seventeen]"
                  {:rf.error/id :rf.error/ghost-seventeen,
                   :where,      'rf.fixture/ghost-comma-four,
                   :reason      "commas around the slot's key and its value"})))

;; ---- (5) THE `:where` SLOT AS THE MAP'S LAST ENTRY ------------------------
;;
;; NOT a comma bug, and found by the control for one: `{... :where 'ns/sym}`
;; vanished exactly as `{... :where, 'ns/sym}` did, while BOTH non-final
;; spellings resolved. The argument splitter ran past the map's own `}` into
;; depth -1 and handed the matcher `'ns/sym}`, which is no symbol at all. The
;; slot fixture in (1c) above cannot catch it — it writes `:reason` AFTER
;; `:where`, which is the shape the splitter already handled, and a fixture
;; that only writes the shape its pattern handles is how both this gate and
;; its sibling shipped blind.

(defn ghost-where-slot-as-last-entry []
  (throw (ex-info "hand-built payload [:rf.error/ghost-eighteen]"
                  {:rf.error/id :rf.error/ghost-eighteen
                   :where       'rf.fixture/ghost-slot-last})))

;; ---- (6) COMMAS AS THE SOLE SEPARATOR ------------------------------------
;;
;; THIS SECTION EXISTS BECAUSE A SABOTAGE PLANT CAME BACK GREEN. Reverting
;; `_is_clj_ws` to Python whitespace left every case in (4) still passing:
;; each of those writes a comma BESIDE a space or a newline, and `_clj_strip`
;; alone recovers those, so the splitter's own notion of reader whitespace was
;; never under test. With commas as the ONLY separator there is no whitespace
;; to fall back on — the whole call collapses into one argument and the
;; where-sym vanishes. The fixtures had the blind spot the pattern had, which
;; is the failure this gate's own audit was about.

(defn ghost-commas-as-sole-separator []
  (rf.error/throw-error! :rf.error/ghost-nineteen,'rf.fixture/ghost-comma-five,"no whitespace anywhere in the argument list"))

(defn ghost-commas-as-sole-separator-in-the-slot []
  (throw (ex-info "hand-built payload [:rf.error/ghost-twenty]"
                  {:rf.error/id :rf.error/ghost-twenty,:where,'rf.fixture/ghost-comma-six,:reason "commas alone between the slot's key and its value"})))

;; ---- (7) COMPOSED READER PREFIXES ----------------------------------------
;;
;; Audit #9511's residual. Each name below is defined in the oracle fixture
;; behind a COMPOSED reader prefix and NOWHERE ELSE, so a walker that decides
;; inertness from the single character before the `(` greens every one of them:
;; `#_#?(...)` and `'#?(...)` reach that `(` after a `?`, and a stacked
;; `#_#_ a b` has no prefix behind `b` at all.
;;
;; THE PREFIX IS THE ONLY THING DOING THE WORK in every one — strip it and the
;; oracle form defines its var for real. Its live twin
;; `rf.fixture/conditional-defined-public` sits in the negative fixture with a
;; byte-identical body, pinned as OBSERVED; the PAIR is the assertion, because
;; a resolving site is green when seen and green again when it has vanished.

(defn ghost-in-a-discarded-conditional []
  (rf.error/throw-error!
    :rf.error/ghost-twentyone
    'rf.fixture/ghost-discarded-conditional
    "`#_` applied to `#?` discards the conditional, arms and all"))

(defn ghost-in-a-quoted-conditional []
  (rf.error/throw-error!
    :rf.error/ghost-twentytwo
    'rf.fixture/ghost-quoted-conditional
    "a quoted reader-conditional is data once the reader has resolved it"))

(defn ghost-in-a-syntax-quoted-conditional []
  (rf.error/throw-error!
    :rf.error/ghost-twentythree
    'rf.fixture/ghost-syntax-quoted-conditional
    "syntax-quote over a conditional is data too"))

(defn ghost-in-a-discarded-splice []
  (rf.error/throw-error!
    :rf.error/ghost-twentyfour
    'rf.fixture/ghost-discarded-splice
    "`#_#?@(...)` is discarded whole, and top-level splicing interns nothing
     in any case"))

(defn ghost-first-of-a-stacked-discard []
  (rf.error/throw-error!
    :rf.error/ghost-twentyfive
    'rf.fixture/ghost-stacked-first
    "the FIRST form a stacked `#_#_` discards, which look-back already saw"))

(defn ghost-second-of-a-stacked-discard []
  (rf.error/throw-error!
    :rf.error/ghost-twentysix
    'rf.fixture/ghost-stacked-second
    "the SECOND form, which look-back cannot reach in principle"))
