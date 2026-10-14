(ns wheresym.negative-where-syms
  "NEGATIVE where-sym fixtures: every one of these must stay GREEN.

  A gate that only proves it FIRES is half a gate. These pin the other half:
  correct code the rule must not report. Above all, the rule demands
  resolvability, never a SPELLING. `rf.fixture/known-public` and
  `re-frame.fixture/known-public` are the same var written two ways and both
  are right, exactly as `re-frame.core`'s own `not-queryable-kinds` map writes
  `re-frame.flows/flows` beside `rf/frame-ids`. Which definition shapes make a
  var public is the oracle's question, pinned by the self-test's exact public
  set."
  (:require [re-frame.error :as rf.error]))

;; ---- resolvable, both spellings ------------------------------------------

(defn alias-spelling []
  (rf.error/throw-error!
    :rf.error/ok-one
    'rf.fixture/known-public
    "the canonical alias dialect"))

(defn fully-qualified-spelling []
  (rf.error/throw-error!
    :rf.error/ok-two
    're-frame.fixture/known-public
    "THE SAME VAR, fully qualified. Both are correct."))

;; ---- not checkable, and saying so is honest ------------------------------

(defn third-party []
  (rf.error/throw-error!
    :rf.error/ok-eleven
    'thirdparty.lib/whatever
    "a namespace this tree does not define and does not own. Nothing here can
     say whether it resolves, so it is counted and skipped rather than guessed
     at in either direction."))

(defn unqualified []
  (rf.error/throw-error!
    :rf.error/ok-twelve
    'some-local-macro
    "no namespace to check it against. Two of these live on trunk."))

;; ---- text that LOOKS like a site but is not ------------------------------

(defn quoted-in-a-docstring
  "A docstring may quote the shape it documents:
     (rf.error/throw-error! :rf.error/x 'rf.fixture/ghost-in-a-docstring reason)
   `re-frame.error`'s own docstrings do exactly this, so a scan that left
   strings visible would report the documentation of this contract as a
   violation of it."
  []
  :ok)

;; A comment may quote it too:
;;   (rf.error/throw-error! :rf.error/x 'rf.fixture/ghost-in-a-comment reason)

(defn no-error-id-is-not-a-framework-error []
  ;; `:where` in a map WITHOUT `:rf.error/id` is not a framework thrown error,
  ;; so the slot is not read.
  (throw (ex-info "an application's own error"
                  {:where 'rf.fixture/ghost-not-a-framework-error})))

(defn nested-quoted-symbol-is-not-a-where-sym []
  ;; Only a TOP-LEVEL argument that is EXACTLY a quoted symbol is a where-sym.
  ;; A quoted symbol riding inside `:extra` is data the site chose to carry.
  (rf.error/throw-error!
    :rf.error/ok-fourteen
    'rf.fixture/known-public
    "the where-sym is the top-level one"
    {:extra {:sym 'rf.fixture/ghost-nested}}))

;; ---- FORM 3 AND FORM 2: THE TWO NON-VAR SPELLINGS THAT ARE STILL PLACES ---
;;
;; A where-sym does not have to name a VAR; it has to name a place a
;; reader can land on. Two further spellings do, and both must stay green:
;;
;;   * a REAL NAMESPACE SPELLED IN FULL. `re-frame.fixture` exists, so it is a
;;     place; its require-alias spelling `rf.fixture` FIRES in the positive
;;     fixture.
;;   * a RESERVED PUBLIC EVENT ID. These are not vars and never will be, so the
;;     public-var oracle would call them dead doors for ever. The sanctioned
;;     set is closed at two members, each citing the Conventions row that
;;     reserves it; a third spelling under the same namespace fires opposite.

(defn namespace-spelled-in-full-resolves []
  (rf.error/throw-error!
    :rf.error/ok-twentysix
    're-frame.fixture
    "a real namespace is a place a reader can land on"))

(defn sanctioned-resource-event-id-resolves []
  (rf.error/throw-error!
    :rf.error/ok-twentyseven
    'rf.resource/invalidate-tags
    "a reserved public event id (Conventions, single-root reserved set)"))

;; A THIRD-PARTY namespace cannot be checked from this tree at all, and saying
;; so is honest where a green would not be. This one stays quiet for a
;; DIFFERENT reason from the two above - not "it resolves" but "the oracle
;; has no opinion" - so a widening that began grading foreign namespaces would
;; redden correct code here.

(defn third-party-namespace-is-not-graded []
  (rf.error/throw-error!
    :rf.error/ok-twentynine
    'reagent2.impl.component
    "outside the framework family; unknowable from this tree"))
