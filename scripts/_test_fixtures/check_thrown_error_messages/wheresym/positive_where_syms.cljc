(ns wheresym.positive-where-syms
  "POSITIVE where-sym fixtures: every one of these must FIRE.

  Two families are planted here, and they are separate on purpose.

  (1) FORMATTING VARIANTS OF THE CALL HEAD. A gate matching only calls
      whose callee sits tight against the paren, checked only by positive
      fixtures written that way, stays green: the gate and its own tests
      share one blind spot and agree with each other. So the head is
      written here tight, behind a newline, and unqualified, and the
      self-test pins LINE NUMBERS rather than a count:
      a count cannot separate `found the right ones` from `traded a real hit
      for a false positive`.

  (2) REASONS A SYMBOL DOES NOT RESOLVE. The namespace may not exist, or the
      var may be private even when fully qualified. Which definition shapes
      make a var public at all is the oracle's question, pinned by the
      self-test's exact public set.

  `re-frame.fixture` is the oracle fixture beside this file. `rf.fixture/…`
  and `re-frame.fixture/…` are the SAME namespace under the canonical alias
  dialect, which is why a private var fires under BOTH spellings — the rule is
  resolvability, never a spelling."
  (:require [re-frame.error :as rf.error]
            [re-frame.error :refer [throw-error!]]))

;; ---- (1) the call head, three reader-equivalent formattings --------------

(defn ghost-tight []
  (rf.error/throw-error!
    :rf.error/ghost-one
    'rf.fixture/ghost-one
    "the callee sits tight against the paren"))

(defn ghost-newline []
  (
    rf.error/throw-error!
    :rf.error/ghost-three
    'rf.fixture/ghost-three
    "a newline between the paren and the callee"))

(defn ghost-unqualified-head []
  (throw-error! :rf.error/ghost-five 'rf.fixture/ghost-five
                "an unqualified callee, and the where-sym INLINE on the head line"))

;; ---- (1b) the other two rostered builders --------------------------------
;;
;; `throw-inline-interceptor-removed!` is why the where-sym is taken as THE
;; QUOTED-SYMBOL ARGUMENT rather than by index: its where-sym is the FIRST
;; argument, not the second.

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
;; Invisible to the builder scan, and not a corner: hand-built slot sites
;; occur in `re-frame.conformance`. The message here
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

;; ---- (3) COMMAS, WHICH ARE READER WHITESPACE -----------------------------
;;
;; The same class as (1): a reader-equivalent formatting a detector can
;; miss. Executed on the JVM it passes IDENTICAL builder arguments, so this
;; is valid source rather than obfuscation.

(defn ghost-comma-everywhere []
  (, rf.error/throw-error!,
    :rf.error/ghost-sixteen,
    'rf.fixture/ghost-comma-three,
    "a comma in every reader-whitespace position at once"))

;; ---- (4) THE `:where` SLOT AS THE MAP'S LAST ENTRY ------------------------
;;
;; NOT a comma case: `{... :where 'ns/sym}` is the final-entry shape. An
;; argument splitter that runs past the map's own `}` into depth -1 hands
;; the matcher `'ns/sym}`, which is no symbol at all, so the site
;; vanishes while both non-final spellings resolve. The
;; slot fixture in (1c) above cannot catch it — it writes `:reason` AFTER
;; `:where` — and a fixture
;; that only writes the shape its pattern handles leaves the gate blind to
;; every other shape.

(defn ghost-where-slot-as-last-entry []
  (throw (ex-info "hand-built payload [:rf.error/ghost-eighteen]"
                  {:rf.error/id :rf.error/ghost-eighteen
                   :where       'rf.fixture/ghost-slot-last})))

;; ---- (5) COMMAS AS THE SOLE SEPARATOR ------------------------------------
;;
;; THIS SECTION PUTS `_is_clj_ws` UNDER TEST. The case in (3) writes its
;; commas BESIDE a space or a newline, and `_clj_strip` alone recovers those,
;; so (3) stays green even with `_is_clj_ws` reverted to Python whitespace:
;; the splitter's own notion of reader whitespace is not tested there. With
;; commas as the ONLY separator there is no whitespace to fall back on — the
;; whole call collapses into one argument and the where-sym vanishes.
;; Fixtures that share the pattern's blind spot cannot find it, which is
;; why this spelling gets a section of its own.

(defn ghost-commas-as-sole-separator []
  (rf.error/throw-error! :rf.error/ghost-nineteen,'rf.fixture/ghost-comma-five,"no whitespace anywhere in the argument list"))

(defn ghost-commas-as-sole-separator-in-the-slot []
  (throw (ex-info "hand-built payload [:rf.error/ghost-twenty]"
                  {:rf.error/id :rf.error/ghost-twenty,:where,'rf.fixture/ghost-comma-six,:reason "commas alone between the slot's key and its value"})))

;; ---- (6) FORM 3 - A NAMESPACE THAT IS NOT A PLACE ------------------------
;;
;; A slash-free DOTTED where-sym names a NAMESPACE
;; (`'re-frame.ssr.emit`), which is the honest answer to "where did this fail"
;; when no single var owns the failure. A rule that skipped EVERY slash-free
;; symbol before consulting the namespace index would leave both
;; sites below as green as a correct one - the convention would not be
;; self-enforcing, it would be UNCHECKED.
;;
;; The first is the load-bearing one. `rf.fixture` is the require-ALIAS
;; spelling of a namespace that really does exist, as `re-frame.fixture`.
;; Quoting does not expand an alias and `(namespace 'rf.fixture)` is nil, so
;; only the FULL name is a place a reader can land on. Its resolving twin sits
;; in the negative fixture.

(defn ghost-namespace-written-as-an-alias []
  (rf.error/throw-error!
    :rf.error/ghost-twentyeight
    'rf.fixture
    "the alias spelling of a real namespace resolves for nobody"))

(defn ghost-namespace-that-does-not-exist []
  (rf.error/throw-error!
    :rf.error/ghost-twentynine
    're-frame.nosuch.module
    "a full-namespace spelling still has to name a namespace that EXISTS"))

;; The sanctioned event-id set is CLOSED and holds exactly two members. A
;; sibling event in the same reserved namespace is NOT sanctioned by it - were
;; it a wildcard over `rf.resource/*`, every typo under that namespace would be
;; green, so the set names its members rather than a namespace.

(defn ghost-unsanctioned-event-id []
  (rf.error/throw-error!
    :rf.error/ghost-thirty
    'rf.resource/ensure
    "a real reserved event, but not one of the two sanctioned where-syms"))
