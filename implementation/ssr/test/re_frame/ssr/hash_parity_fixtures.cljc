(ns re-frame.ssr.hash-parity-fixtures
  "Shared fixtures for the JVM↔CLJS render-tree-hash byte-identity
  parity tests (rf2-1q9de).

  Spec 011 §Hydration-mismatch detection pins the hash as byte-identical
  across CLJS and JVM runtimes — `'FNV-1a 32-bit over a canonical EDN
  serialisation of the render-tree (depth-first traversal; attribute
  maps in sorted-key order; nil pruned)'`. The whole hydration-mismatch
  detection mechanism rests on this promise: the server hashes the
  render-tree at SSR time and the client recomputes the hash on first
  render. A divergence between the two pipelines produces spurious
  `:rf.ssr/hydration-mismatch` traces; worse, a UTF-8-vs-UTF-16 byte-
  stream divergence (the rf2-t7ktb hazard) silently breaks hydration
  for any non-ASCII content.

  Before this file the only JVM↔CLJS hash literal pinned anywhere was
  one ASCII smoke at `re-frame.hash-check-cljs-test` (`9d7457ef` for
  `[:div {:class \"x\"} [:p \"hi\"]]`, plus a 4-character non-ASCII
  pin `a82b5049` for the BARE STRING `\"café\"` added at rf2-t7ktb).
  Neither pin covered: nested attribute-map sort, namespaced-keyword
  attribute keys, multi-byte UTF-8 inside an attribute value, UTF-16
  surrogate-pair codepoints (>0xFFFF), set ordering, list-as-sequence
  serialisation, or large flat collections that exercise the FNV
  multiply-accumulate loop for many bytes. The audit at rf2-asmj1
  §A2 called this out as the under-appreciated win; this file pins
  the corpus.

  Pattern mirrors `re-frame.schemas.digest-parity-fixtures` (rf2-xssfv,
  #915) — both runtimes consume the SAME fixture map (loaded from a
  shared `.cljc` namespace) and pin the SAME canonical literal. The
  literal IS the cross-host byte-comparison point.")

;; ---- the canonical corpus -------------------------------------------------
;;
;; Each fixture is `{:label, :input, :expected, :rationale}`. The
;; input is fed to `render-tree-hash`; the `:expected` hex string is
;; the canonical literal both runtimes MUST produce. The corpus
;; spans the representative shapes Spec 011's traversal rule must
;; survive: empty containers, scalar leaves, hiccup trees of growing
;; depth, namespaced-keyword keys/values, multi-byte UTF-8 (Latin-1
;; supplement, Cyrillic, CJK), UTF-16 surrogate-pair codepoints,
;; set-deterministic-order, list-vs-vector branching, and a 20-child
;; tree exercising the FNV multiply-accumulate loop.

(def empty-map
  {:label "empty-map" :input {} :expected "5465b825"
   :rationale "Smallest map. A divergence in `{}` marker emission fails here first."})

(def empty-vector
  {:label "empty-vector" :input [] :expected "741638a5"
   :rationale "Smallest vector. Distinct from empty-list `()` separates the two markers."})

(def empty-list
  {:label "empty-list" :input (list) :expected "28d9f59a"
   :rationale "Empty sequential-but-not-vector — emits `()` per canonical-edn's sequential branch."})

(def empty-set
  {:label "empty-set" :input #{} :expected "3c458d02"
   :rationale "Smallest set. Pins the `#{}` marker; sets get `(sort (keep ...))` deterministic order."})

(def scalar-string
  {:label "scalar-string" :input "hello" :expected "df47ee8b"
   :rationale "Bare string scalar — exercises `pr-str` quoting."})

(def scalar-keyword
  {:label "scalar-keyword" :input :div :expected "f7b6adb0"
   :rationale "Bare keyword scalar."})

(def scalar-int
  {:label "scalar-int" :input 42 :expected "87e38583"
   :rationale "Bare integer — both JVM (Long) and CLJS (Number) MUST stringify to `42`."})

(def scalar-true
  {:label "scalar-true" :input true :expected "4db211e5"
   :rationale "Boolean true."})

(def scalar-false
  {:label "scalar-false" :input false :expected "0b069958"
   :rationale "Boolean false — MUST NOT be pruned the way nil is."})

(def minimal-hiccup
  {:label "minimal-hiccup" :input [:p "hi"] :expected "3d16d3e4"
   :rationale "Smallest realistic render tree — keyword + string, no attribute map."})

(def div-class
  {:label "div-class" :input [:div {:class "x"} [:p "hi"]]
   :expected "9d7457ef"
   :rationale "Pre-existing parity pin from `re-frame.hash-check-cljs-test`
              (rf2-t7ktb). Carrying it here gives one place for the
              byte-comparison point."})

(def namespaced-kw-attr
  {:label    "namespaced-kw-attr"
   :input    [:input {:type "text"
                      :data-rf2-source-coord "rf.app:view:42:7"
                      :on-change :rf.parity-test/handler}]
   :expected "a97ed733"
   :rationale "Attribute map mixing a data-attribute keyword, a
              namespaced-keyword value, and a plain `:type`. The
              canonical form sorts keys lexicographically via
              `(comp str key)`: data-... before on-change before
              type. Pins the sort step + namespaced-keyword
              serialisation."})

(def nested-deep
  {:label    "nested-deep"
   :input    [:section {:class "wrap"}
              [:header {:role "banner"}
               [:h1 "Title"]
               [:nav
                [:a {:href "/" :class "active"} "Home"]
                [:a {:href "/about"} "About"]]]
              [:main
               [:article {:class "post"}
                [:h2 "Subtitle"]
                [:p "Body 1"]
                [:p "Body 2"]]]
              [:footer [:p "(C) 2026"]]]
   :expected "02eed3c5"
   :rationale "Five-level tree with attribute maps at three levels and
              one out-of-order map `{:href \"/\" :class \"active\"}` the
              canonical-edn sort MUST reorder to `{:class \"active\"
              :href \"/\"}`. A drift in the recursive sort at any level
              changes the hash."})

(def unicode-cafe
  {:label "unicode-cafe" :input [:p "café"] :expected "2379e33d"
   :rationale "Two-byte UTF-8 (`é` → `c3 a9`) inside a hiccup body.
              Pre-rf2-t7ktb a CLJS UTF-16 path produced a different
              byte stream — this fixture catches the regression at
              the first non-ASCII character. Distinct from rf2-t7ktb's
              bare-string pin (`a82b5049`) — this is the hash of the
              hiccup-WRAPPED canonical-EDN string the production path
              actually computes."})

(def unicode-emoji
  {:label "unicode-emoji" :input [:span "👋 world"] :expected "b0c50846"
   :rationale "Four-byte UTF-8 (`👋` → `f0 9f 91 8b`) which on CLJS is
              a UTF-16 SURROGATE PAIR. A CLJS `fnv-1a-32` that walked
              `.charCodeAt` per UTF-16 code unit would produce a hash
              offset by one byte (the two surrogate halves) relative
              to the JVM UTF-8 byte stream. The strongest cross-runtime
              trap the corpus closes."})

(def unicode-mixed
  {:label    "unicode-mixed"
   :input    [:div {:title "Привет"} "你好" :hello]
   :expected "66e28ab6"
   :rationale "Cyrillic (`Привет`, 2-byte UTF-8), CJK (`你好`, 3-byte
              UTF-8), and a keyword in one fixture — mixed multi-byte
              sequences inside a sorted attribute map and as
              positional children."})

(def set-with-strings
  {:label "set-with-strings" :input #{"b" "a" "c"} :expected "978929dc"
   :rationale "Set members are sorted via `(sort (keep ...))` so the
              shuffled input MUST emit alphabetic order. A divergent
              sort comparator would shift the canonical bytes and
              fail here."})

(def list-children
  {:label    "list-children"
   :input    [:ul (list :li1 :li2 :li3)]
   :expected "81d3674a"
   :rationale "List children emit `()` not `[]` — pins the
              vector-vs-list branch. Hiccup commonly produces lists
              from `for` / `map` inside a render fn."})

(def large-flat
  {:label "large-flat"
   :input (into [:ul]
                (for [i (range 20)]
                  [:li {:id (str "item-" i)} (str "Item " i)]))
   :expected "4770186a"
   :rationale "20-child render tree (~700 canonical bytes). Exercises
              the FNV multiply-accumulate loop for many iterations; a
              drift in the `Math.imul`-vs-long-multiply equivalence
              would compound over the byte stream and surface here."})

(def namespaced-kw-key
  {:label    "namespaced-kw-key"
   :input    {:rf/id :rf.test/view :rf/path [:a :b :c]}
   :expected "ba4f72c1"
   :rationale "Map with `:rf/*` namespaced-keyword keys (per Spec
              Conventions' reserved-namespace scheme). Pins the
              canonical form of the framework's `:rf/*` shapes."})

(def all-fixtures
  "All canonical fixtures in declaration order. Test files iterate this
  list so adding a fixture requires no edits to per-runtime test code."
  [empty-map empty-vector empty-list empty-set scalar-string scalar-keyword
   scalar-int scalar-true scalar-false minimal-hiccup div-class
   namespaced-kw-attr nested-deep unicode-cafe unicode-emoji unicode-mixed
   set-with-strings list-children large-flat namespaced-kw-key])

;; ---- nil-pruning equivalence pairs ---------------------------------------
;;
;; Per Spec 011 §Hydration-mismatch detection and rf2-6djjl: nil
;; values are pruned from attribute maps and nil children are pruned
;; from sequences. Two trees that differ only in nil presence MUST
;; hash identically. The pinned literal is the hash of the no-nil
;; side; the parity assertion is that the with-nil tree produces the
;; same hash on both runtimes. The pre-existing JVM-only
;; `re-frame.ssr-hash-test` asserts the equivalence but pins no
;; literal; carrying a pinned literal here closes the cross-runtime gap.

(def nil-prune-attr
  {:label             "nil-prune-attr"
   :input-with-nil    [:div {:class nil :id "x"} [:p "hi"]]
   :input-without-nil [:div {:id "x"}            [:p "hi"]]
   :expected          "11d3589e"
   :rationale "The common `{:class (when condition? :selected)}` shape
              emits `:class nil` on one side and absent on the other;
              pruning makes them equivalent. Pins the no-nil hash."})

(def nil-prune-child
  {:label             "nil-prune-child"
   :input-with-nil    [:div [:p "a"] nil [:p "b"]]
   :input-without-nil [:div [:p "a"]     [:p "b"]]
   :expected          "e4cf2667"
   :rationale "Conditional children — `(when cond? [:p ...])` — emit
              nil when false; pruning makes them positionally
              equivalent to the absent case. Pins the no-nil hash."})

(def nil-prune-pairs
  "Fixtures whose with-nil / without-nil inputs MUST hash to the same
  pinned literal across both runtimes."
  [nil-prune-attr nil-prune-child])

;; ---- structural-invariant pairs ------------------------------------------
;;
;; Two distinct inputs that MUST hash identically by Spec 011 — attribute-
;; map key-order independence (the canonical form sorts keys). The pin
;; isn't a specific literal; it's agreement between the two inputs,
;; which MUST hold on both runtimes.

(def attr-key-order-pair
  {:label   "attr-key-order-independent"
   :input-a [:a {:href "/" :class "active" :id "home"} "Home"]
   :input-b [:a {:class "active" :id "home" :href "/"} "Home"]
   :rationale "Spec 011 §Hydration-mismatch detection — attribute maps
              emit in sorted-key order. The two inputs differ only in
              literal key order; both runtimes MUST produce the same
              canonical string and therefore the same hash."})

(def equality-pairs
  "Input pairs whose hashes MUST agree (the agreed value isn't pinned
  to a specific literal here — see `nil-prune-pairs` for the variant
  that does pin a literal)."
  [attr-key-order-pair])
