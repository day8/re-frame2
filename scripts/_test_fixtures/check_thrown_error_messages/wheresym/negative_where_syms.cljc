(ns wheresym.negative-where-syms
  "NEGATIVE where-sym fixtures (rf2-z5lv): every one of these must stay GREEN.

  A gate that only proves it FIRES is half a gate. These pin the other half —
  and specifically the three ways this rule could go wrong in the expensive
  direction, by reporting correct code:

    * demanding a SPELLING rather than resolvability. `rf.fixture/known-public`
      and `re-frame.fixture/known-public` are the same var written two ways and
      both are right, exactly as `re-frame.core`'s own `not-queryable-kinds`
      map writes `re-frame.flows/flows` beside `rf/frame-ids`;
    * grading against DOCUMENTED publics rather than public vars — the
      `^:no-doc` case, which is the whole reason the manifest is not the
      oracle;
    * grading against ONE runtime — the `#?(:cljs …)` / `#?(:clj …)` case,
      which is how `rf/frame-provider` and `rf/frame-root` ship."
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

;; ---- resolvable across every public definition shape ---------------------

(defn plain-var []
  (rf.error/throw-error! :rf.error/ok-three 'rf.fixture/known-var "a `def`"))

(defn macro []
  (rf.error/throw-error! :rf.error/ok-four 'rf.fixture/known-macro "a `defmacro`"))

(defn multi []
  (rf.error/throw-error! :rf.error/ok-five 'rf.fixture/known-multi "a `defmulti`"))

(defn no-doc []
  (rf.error/throw-error!
    :rf.error/ok-six
    'rf.fixture/no-doc-public
    "`^:no-doc` is a DOCUMENTATION flag, not a privacy one. It resolves, and it
     carries no manifest row — which is why a manifest-only oracle reported
     `reset-frame!` and `reload-images!` as dead doors."))

(defn cljs-only []
  (rf.error/throw-error!
    :rf.error/ok-seven
    'rf.fixture/cljs-only-public
    "public under `:cljs` only. A JVM-only oracle reports this as a dead door."))

(defn clj-only []
  (rf.error/throw-error!
    :rf.error/ok-eight
    'rf.fixture/clj-only-public
    "public under `:clj` only."))

(defn var-defining-macros []
  (rf.error/throw-error! :rf.error/ok-nine 'rf.fixture/Panel "defined by `reg-view`")
  (rf.error/throw-error! :rf.error/ok-ten 'rf.fixture/imported-fn "interned by `import-fn`"))

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

(defn char-literal-then-a-real-call [c]
  ;; REGRESSION PIN. `\"` outside a string is the character `"`, not a
  ;; delimiter. Read as a delimiter it swallows the rest of the file — measured
  ;; on `re-frame.ssr.html-helpers`, where it took both real where-syms with it
  ;; and the scan reported zero. The call below must still be SEEN (and must
  ;; resolve, so this fixture stays green): if the mask desynchronises here, the
  ;; positive fixture's later sites are the ones that go quiet.
  (when (= c \")
    (rf.error/throw-error!
      :rf.error/ok-thirteen
      'rf.fixture/known-public
      "reached past a character literal")))

(defn no-error-id-is-not-a-framework-error []
  ;; `:where` in a map WITHOUT `:rf.error/id` is not a framework thrown error,
  ;; so the slot is not read. rf2:builder-bypass-ok
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

;; ---- `do` IS transparent: the valid exit-0 control for the oracle repair --
;;
;; Audit #9501 removed `comment` from the transparent-wrapper set. `do` really
;; does evaluate its body, so the `def` inside interns and this door is live.
;; Green here is what says the repair cut only the inactive forms.

(defn do-defined-var-resolves []
  (rf.error/throw-error!
    :rf.error/ok-fifteen
    'rf.fixture/do-defined-public
    "`(do (defn do-defined-public ...))` interns the var"))

;; ---- commas are reader whitespace, and correct code uses them ------------
;;
;; The resolving twins of the positive fixture's comma sites. GREEN HERE IS
;; ONLY HALF THE EVIDENCE — a detector blind to commas is green too, which is
;; exactly how the defect shipped — so the self-test pins these as OBSERVED at
;; their lines rather than merely unreported.

(defn comma-tight-on-the-head-resolves []
  (,rf.error/throw-error!
    :rf.error/ok-sixteen
    'rf.fixture/known-public
    "a comma between the paren and the callee, naming a live var"))

(defn comma-after-the-symbol-resolves []
  (rf.error/throw-error!
    :rf.error/ok-seventeen
    'rf.fixture/known-public,
    "a trailing comma on the quoted symbol, naming a live var"))

(defn comma-in-the-where-slot-resolves []
  (throw (ex-info "hand-built payload [:rf.error/ok-eighteen]"
                  {:rf.error/id :rf.error/ok-eighteen,
                   :where,      'rf.fixture/known-public,
                   :reason      "commas around the slot's key and its value"})))

(defn where-slot-as-last-entry-resolves []
  (throw (ex-info "hand-built payload [:rf.error/ok-nineteen]"
                  {:rf.error/id :rf.error/ok-nineteen
                   :where       'rf.fixture/known-public})))

;; ---- commas as the SOLE separator, naming live vars ----------------------
;;
;; The resolving twins of positive section (6). Pinned as OBSERVED, because
;; the spelling they exercise is the one a green sabotage plant proved the
;; earlier comma fixtures did not reach.

(defn commas-as-sole-separator-resolves []
  (rf.error/throw-error! :rf.error/ok-twenty,'rf.fixture/known-public,"no whitespace anywhere in the argument list"))

(defn commas-as-sole-separator-in-the-slot-resolves []
  (throw (ex-info "hand-built payload [:rf.error/ok-twentyone]"
                  {:rf.error/id :rf.error/ok-twentyone,:where,'rf.fixture/known-public,:reason "commas alone around the slot"})))

;; ---- a LIVE reader-conditional, the twin of the discarded one ------------
;;
;; The oracle fixture writes `#_#?(:clj (defn ghost-discarded-conditional ...))`
;; and `#?(:clj (defn conditional-defined-public ...))` with the same body, so
;; the PAIR isolates the prefix as the only cause of inertness. This half is
;; pinned as OBSERVED at its line: green here is also what a detector that has
;; gone blind to the whole site looks like.

(defn conditional-defined-var-resolves []
  (rf.error/throw-error!
    :rf.error/ok-twentytwo
    'rf.fixture/conditional-defined-public
    "`#?(:clj (defn conditional-defined-public ...))` interns the var"))
