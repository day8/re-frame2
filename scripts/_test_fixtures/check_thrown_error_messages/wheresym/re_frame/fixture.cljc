(ns re-frame.fixture
  "THE ORACLE FIXTURE for the where-sym rule (rf2-z5lv).

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
;; fully resolvable and carries no row. On trunk `re-frame.core` ships eleven
;; of them (`reset-frame!` and `reload-images!` among them), and a
;; manifest-only oracle reported both as dead doors. Pinned here so the
;; refutation cannot be quietly undone.
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
;; component var (eleven Xray namespaces row one), and `import-fn` interns a
;; re-export (`re-frame.ssr.ring` ships five). The manifest control caught the
;; parser missing both.
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

;; ---- INACTIVE DEFINITIONS: must NOT be reachable (audit #9501) ------------
;;
;; `comment` shipped in `_TRANSPARENT_DEF_WRAPPERS` beside `do`, and
;; `_top_level_forms` walked straight through `#_` and `'`. None of the four
;; forms below interns anything — `(comment ...)` evaluates to nil, `#_`
;; discards, and a quoted or syntax-quoted list is data — yet each contributed
;; its name to the derived public set, so a where-sym naming a var that exists
;; only inside one resolved as a live door.
;;
;; THE MANIFEST CONTROL CANNOT REACH THIS. `oracle_problems` is a SUBSET test:
;; it detects public names MISSING from the derived set, never EXTRA fictitious
;; ones. Only the exact-set assertion in the self-test does.

(comment
  (defn ghost-in-comment [] nil))

#_(defn ghost-discarded [] nil)

'(defn ghost-quoted [] nil)

`(defn ghost-syntax-quoted [] nil)

;; ---- LIVE `do`: THE VALID EXIT-0 CONTROL FOR THAT REPAIR ------------------
;;
;; `do` IS transparent — it evaluates its body, so the `def` inside really does
;; intern, and `rf/frame-root`-style exports depend on the walk descending.
;; Dropping `comment` must not cost this, and the walk must carry the reader
;; prefixes DOWN with it: the discarded sibling below is defined nowhere else.

(do
  (defn do-defined-public [] nil)
  #_(defn ghost-discarded-inside-do [] nil))
