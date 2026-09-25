(ns re-frame.fixture
  "THE ORACLE FIXTURE for the where-sym rule.

  Not a live namespace. It exists so the self-test can grade where-syms
  against a public-var set it CONTROLS, rather than against the real tree
  — where a legitimate rename would move the fixtures' answers.

  Its file path is the MUNGED namespace (`re_frame/fixture.cljc`), because
  `PublicVarIndex` resolves a namespace by that munge and by nothing else.
  It sits under the canonical alias dialect too, so `'rf.fixture/x` and
  `'re-frame.fixture/x` are the SAME symbol spelled two ways — which is what
  the negative fixture uses to pin that the rule is resolvability, never a
  spelling.

  Every definition shape below is load-bearing: the public ones must be
  reachable and the private ones must NOT be, or the rule reports the wrong
  answer in one of its two directions."
  (:require [re-frame.core :as rf]))

;; ---- PUBLIC: the ordinary shapes -----------------------------------------

(def known-var 1)

(defn known-public [x] x)

(defmacro known-macro [x] x)

(defmulti known-multi :kind)

;; ---- PUBLIC: `^:no-doc`, WHICH IS THE WHOLE MANIFEST-ORACLE REFUTATION ----
;;
;; `spec/api-manifest.edn` rows DOCUMENTED publics, so a `^:no-doc` var is
;; fully resolvable and carries no row. `re-frame.core` ships such vars
;; (`reset-frame!` and `reload-images!` among them), and a
;; manifest-only oracle would report them as dead doors. Pinned here so a
;; manifest-only oracle cannot pass unnoticed.
(def ^:no-doc no-doc-public 2)

;; ---- PUBLIC on ONE PLATFORM ONLY -----------------------------------------
;;
;; The façade ships `rf/frame-provider` and `rf/frame-root` exactly like this.
;; They are absent from the JVM's `ns-publics` and present in every CLJS build,
;; so the rule asserts about the UNION of the arms; a JVM-only oracle reports
;; both as dead doors.
#?(:cljs (def cljs-only-public 3))
#?(:clj  (def clj-only-public 4))

;; ---- PUBLIC through a var-defining macro that does not begin `def` --------
;;
;; `_EXTRA_DEF_HEADS`. Both of these are real shapes: `reg-view` defines the
;; component var (Xray namespaces use it), and `import-fn` interns a
;; re-export (`re-frame.ssr.ring` uses it). A parser that knows only `def`
;; heads misses both.
(rf/reg-view Panel
  [:div "panel"])

(import-fn re-frame.other/imported-fn)

;; ---- PRIVATE: must NOT be reachable --------------------------------------
;;
;; A private var does not resolve for a reader either, so a where-sym naming
;; one is a dead door even when fully qualified — which is the live
;; `'re-frame.router/build-envelope` shape, a `defn-`.

(defn- private-fn [x] x)

(def ^:private private-var 5)

(def ^{:private true} private-meta-var 6)

;; ---- DEFINES NOTHING: `defmethod` extends, it does not define -------------
;;
;; `foreign-multi` is deliberately NOT defined here — it stands for a
;; multimethod owned elsewhere and extended from this namespace. That is what
;; makes it a DISCRIMINATOR: drop the `defmethod` carve-out from
;; `_public_names_defined_by` and this name becomes "public", which greens the
;; positive fixture's `'rf.fixture/foreign-multi` site. Extending
;; `known-multi` instead would prove nothing, because `defmulti` already
;; defines that name.

(defmethod known-multi :a [_] :a)

(defmethod foreign-multi :b [_] :b)

;; ---- INACTIVE DEFINITIONS: must NOT be reachable -------------------------
;;
;; None of the four forms below interns anything — `(comment ...)` evaluates
;; to nil, `#_` discards, and a quoted or syntax-quoted list is data — so
;; none may contribute its name to the derived public set. A walker that
;; treated `comment` as transparent like `do`, or walked straight through
;; `#_` and `'`, would resolve a where-sym naming a var that exists
;; only inside one as a live door.
;;
;; THE MANIFEST CONTROL CANNOT REACH THIS. `oracle_problems` is a SUBSET test:
;; it detects public names MISSING from the derived set, never EXTRA fictitious
;; ones. Only the exact-set assertion in the self-test does.

(comment
  (defn ghost-in-comment [] nil))

#_(defn ghost-discarded [] nil)

'(defn ghost-quoted [] nil)

`(defn ghost-syntax-quoted [] nil)

;; ---- LIVE `do`: THE VALID EXIT-0 CONTROL FOR THE INERT SET ----------------
;;
;; `do` IS transparent — it evaluates its body, so the `def` inside really does
;; intern, and `rf/frame-root`-style exports depend on the walk descending.
;; Excluding `comment` must not cost this, and the walk must carry the reader
;; prefixes DOWN with it: the discarded sibling below is defined nowhere else.

(do
  (defn do-defined-public [] nil)
  #_(defn ghost-discarded-inside-do [] nil))

;; ---- COMPOSED READER PREFIXES: THE PREFIX IS THE ONLY THING DOING THE WORK -
;;
;; A predicate deciding inertness from the ONE character before the `(`
;; would walk `#_#?(...)` and `'#?(...)` as LIVE, because they reach that
;; `(` after a `?`, and would skip only the first form of a STACKED
;; `#_#_ a b` though the reader discards both. Reader-conditionals are why
;; that matters: `#?` is ordinary `.cljc` and this tree is full of it, so
;; every composed prefix below must read as inert.
;;
;; EVERY SHAPE HERE IS ADVERSARIAL AGAINST THE PREDICATE THAT READS IT. Strip
;; the prefix and each form defines its var for real, so nothing BUT the prefix
;; can be making it inert — `conditional-defined-public` below is that live
;; twin, body for body. A fixture whose inertness has a second cause proves
;; nothing about the predicate, because it stays green even when the
;; predicate is broken.

#_#?(:clj  (defn ghost-discarded-conditional [] nil)
     :cljs (defn ghost-discarded-conditional [] nil))

'#?(:clj  (defn ghost-quoted-conditional [] nil)
    :cljs (defn ghost-quoted-conditional [] nil))

`#?(:clj (defn ghost-syntax-quoted-conditional [] nil))

#_#?@(:clj [(defn ghost-discarded-splice [] nil)])

;; A STACKED DISCARD NEUTRALISES BOTH FORMS. The first is unreachable
;; by look-back; the SECOND is the hard case — nothing stands between it and the
;; form the first discard consumed, so there is no prefix behind it to see.

#_#_ (def ghost-stacked-first 1)
     (defn ghost-stacked-second [] nil)

;; ---- LIVE reader-conditional: the discriminating twin ---------------------
;;
;; Identical, body for body, to the discarded conditional above. It must stay
;; PUBLIC: widening the inert set until it swallows this is the failure the
;; `do` control guards against one shape along, and a `#?(:cljs (def
;; frame-root ...))` export is how the real facade ships.

#?(:clj  (defn conditional-defined-public [] nil)
   :cljs (defn conditional-defined-public [] nil))

;; ---- SPLICING READER-CONDITIONALS: THE POSITION IS THE WHOLE RULE ---------
;;
;; This case points the OTHER WAY from the composed prefixes above.
;; `#?@` means opposite things in the two places it can stand: at file top
;; level there is no collection to splice into, so it is a reader ERROR and
;; interns nothing; inside a collection it is ordinary legal Clojure. A
;; prefix pass that consumed it in BOTH would discard the legal splice below
;; as though it were the illegal one, and `splice-in-do-public` would go
;; MISSING from the derived set — a FALSE RED, where a where-sym naming a var
;; that genuinely exists reds a correct PR. Every other case in this file
;; tests green-should-be-red, so none of them can see it.
;;
;; THE ENCLOSING COLLECTION IS THE ONLY THING MAKING THESE LEGAL, which is the
;; adversarial property the composed-prefix cases have one direction along:
;; strip the `do` from the first and what is left is a top-level `#?@` that
;; interns nothing. So a walker that loses the top-level/inside-a-collection
;; distinction cannot satisfy this file and the positive one at the same time.
;;
;; THE TWO RUNTIMES DISAGREE ON ONE ROW, ON PURPOSE. JVM
;; `load-file` + `ns-publics` interns the first two; a `:cljs`-feature reader
;; oracle interns all three, and it is the ONLY one that sees
;; `splice-cljs-only-public`. A JVM-only oracle would call that door dead —
;; the same false red one runtime along, and the reason the derived set here
;; is deliberately feature-agnostic.

(do #?@(:clj  [(defn splice-in-do-public [] :live)]
        :cljs [(defn splice-in-do-public [] :live)]))

#?(:clj  (do #?@(:clj  [(defn splice-in-conditional-do-public [] :live)]))
   :cljs (do #?@(:cljs [(defn splice-in-conditional-do-public [] :live)])))

(do #?@(:cljs [(defn splice-cljs-only-public [] :live)]))

;; The DISCARDED twin, body for body — `#_` neutralises the whole `do`, splice
;; and all, so this name must stay absent however deep the splice walk goes.

#_(do #?@(:clj  [(defn ghost-splice-discarded [] nil)]
          :cljs [(defn ghost-splice-discarded [] nil)]))
