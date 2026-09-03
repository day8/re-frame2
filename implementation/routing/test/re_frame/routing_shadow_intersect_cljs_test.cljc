(ns re-frame.routing-shadow-intersect-cljs-test
  "Co-matchability tests for `re-frame.routing.match/patterns-intersect?` —
  the Spec 012 §Route ranking algorithm rule-6 'same URL family' predicate
  behind `:rf.warning/route-shadowed-by-equal-score` (rf2-6gzobp).

  Two suites:

    1. OVERLAP TABLE — hand-picked pattern pairs pinning the intersecting
       and non-intersecting cases the ruling enumerates: same-family param
       pairs, cross-position params, splats, optional groups including the
       shifted witness (`/a{/x}?/b` vs `/a/x{/b}?` share NO literal column
       yet both match `/a/x/b` — the case that falsifies any naive
       corresponding-literals comparison), the `/*` root quirk, and the
       conservative fallback for degenerate non-segment-aligned patterns.

    2. PROPERTY — the predicate agrees with BRUTE FORCE: for generated
       pattern pairs, enumerate every candidate URL over the patterns'
       literal alphabet (plus one fresh symbol) up to a sufficient length
       bound and check both compiled regexes; the predicate must equal
       'some URL matches both'. A witness, when one exists, always exists
       over that alphabet (params/splats are free, literal positions are
       forced), and its minimal length is bounded by the larger pattern's
       maximal consumption, so the enumeration is exhaustive for the
       decision.

  Pure pattern-domain tests — no registrar / runtime fixture needed.

  Mirrors the foundation tests' hand-rolled 32-bit LCG (no test.check /
  Malli-generator dependency on the routing test classpath); the seed is
  fixed so a failure is a stable repro.

  Named `*-cljs-test.cljc` so BOTH the cognitect JVM runner (`.*-test$`)
  and the shadow-cljs `:node-test` build (`cljs-test$`) discover it — the
  ruling requires the overlap table on both hosts."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [clojure.string :as str]
   [re-frame.routing.match :as rf.routing.match]))

;; ---- 1. the overlap table --------------------------------------------------

(def ^:private intersecting-pairs
  ;; [pattern-a pattern-b witness-note]
  [["/a/:x"          "/a/:y"          "the true rule-6 conflict — /a/anything"]
   ["/foo"           "/foo"           "identical literals"]
   ["/x/:id"         "/:kind/y"       "cross-position params — witness /x/y"]
   ["/:a/:b"         "/x/y"           "all-param vs all-literal — witness /x/y"]
   ["/a{/x}?/b"      "/a/x{/b}?"      "SHIFTED optional-group witness /a/x/b — no
                                       literal column agrees, so naive
                                       corresponding-literals comparison
                                       false-negatives this pair"]
   ["/a{/x}?/b"      "/a/b"           "elided group — witness /a/b"]
   ["/docs{/guide}?" "/docs"          "elided trailing group — witness /docs"]
   ["/docs{/guide}?" "/docs/:page"    "taken group vs param — witness /docs/guide"]
   ["{/:base}?/about" "/about"        "elided leading group — witness /about"]
   ["/a/*r"          "/a/:x"          "splat consuming one segment — witness /a/z"]
   ["/a/*r"          "/a/b/*s"        "two splats, nested prefixes — witness /a/b/z"]
   ["/files/*rest"   "/files/*other"  "identical splat families"]
   ["/*"             "/*rest"         "bare and named catch-all"]
   ["/*"             "/"              "the rf2-1ugs5u root quirk — /* also
                                       matches the zero-segment root URL /"]])

(def ^:private disjoint-pairs
  [["/home"           "/about"          "distinct statics — the every-app case"]
   ["/x/:id"          "/y/:slug"        "the pair the old over-broad scan
                                         false-flagged (equal rank, disjoint
                                         URL families)"]
   ["/a/b"            "/a/c"            "shared prefix, distinct tail literal"]
   ["/a/:x"           "/b/:y"           "distinct prefix, params after"]
   ["/a"              "/a/b"            "different lengths never co-match"]
   ["/a/*r"           "/b/:x"           "splat cannot rescue a disjoint prefix"]
   ["/a{/x}?/b"       "/a/y{/b}?"       "{a/b, a/x/b} vs {a/y, a/y/b} — disjoint"]
   ["{/:base}?/about" "/docs{/guide}?"  "{about, X/about} vs {docs, docs/guide}"]
   ["/"               "/a"              "root vs one segment"]
   ["/About"          "/about"          "literals are case-sensitive, exactly as
                                         the compiled regex is"]])

(deftest patterns-intersect-overlap-table
  (testing "intersecting pairs — some URL matches both patterns"
    (doseq [[pa pb note] intersecting-pairs]
      (is (true? (rf.routing.match/patterns-intersect? pa pb))
          (str pa " ∩ " pb " expected NON-empty — " note))
      (is (true? (rf.routing.match/patterns-intersect? pb pa))
          (str "symmetric: " pb " ∩ " pa " — " note))))

  (testing "disjoint pairs — no URL matches both patterns, so an equal
            structural rank alone MUST NOT warn"
    (doseq [[pa pb note] disjoint-pairs]
      (is (false? (rf.routing.match/patterns-intersect? pa pb))
          (str pa " ∩ " pb " expected EMPTY — " note))
      (is (false? (rf.routing.match/patterns-intersect? pb pa))
          (str "symmetric: " pb " ∩ " pa " — " note))))

  (testing "degenerate non-segment-aligned patterns fall back to
            conservatively co-matchable (the Spec 012 MUST-warn is never
            lost on a grammar-permitted pathological pattern)"
    ;; `/a/{/x}?` — the group opens right after a top-level `/`, so its
    ;; elided branch leaves an empty segment; the language is not a union
    ;; of whole segments and the tokenizer refuses it.
    (is (true? (rf.routing.match/patterns-intersect? "/a/{/x}?" "/a/y")))
    (is (true? (rf.routing.match/patterns-intersect? "/a/y" "/a/{/x}?")))))

;; ---- 2. property: predicate vs brute-force URL enumeration ------------------

;; The SAME 32-bit linear-congruential generator the foundation tests use;
;; Numerical-Recipes constants, every op in the int32 range, identical draw
;; stream on CLJ and CLJS.

(defn- lcg-next [state]
  (-> (unchecked-multiply (long state) 1664525)
      (unchecked-add 1013904223)
      (bit-and 0x7fffffff)))

(defn- rnd [state n] (mod (lcg-next state) n))

(def ^:private lit-pool ["a" "b"])

(defn- gen-inner-token
  "Draw one optional-group inner atom — `:param` or `[:lit text]`.
  Returns `[token next-state]`."
  [s]
  (if (zero? (rnd s 3))
    [:param (lcg-next s)]
    (let [s' (lcg-next s)]
      [[:lit (nth lit-pool (rnd s' (count lit-pool)))] (lcg-next s')])))

(defn- gen-tokens
  "Draw a small token vector: 0..2 body atoms (literal / param / optional
  group of 1..2 inner atoms), plus a final splat one draw in four. An
  empty draw is the root pattern. Returns `[tokens next-state]`."
  [s]
  (let [n-body (rnd s 3)
        s      (lcg-next s)]
    (loop [i 0, s s, toks []]
      (if (= i n-body)
        (if (zero? (rnd s 4))
          [(conj toks :splat) (lcg-next s)]
          [toks (lcg-next s)])
        (let [kind (rnd s 4)
              s    (lcg-next s)]
          (case (int kind)
            (0 1) (let [t [:lit (nth lit-pool (rnd s (count lit-pool)))]]
                    (recur (inc i) (lcg-next s) (conj toks t)))
            2     (recur (inc i) s (conj toks :param))
            3     (let [n-inner (inc (rnd s 2))
                        s       (lcg-next s)
                        [inner s]
                        (loop [j 0, s s, acc []]
                          (if (= j n-inner)
                            [acc s]
                            (let [[t s'] (gen-inner-token s)]
                              (recur (inc j) s' (conj acc t)))))]
                    (recur (inc i) s (conj toks [:opt inner])))))))))

(defn- render-pattern
  "Render a token vector as a canonical Spec 012 path-pattern string.
  Param / splat names are synthesized (names never affect the language)."
  [tokens]
  (if (empty? tokens)
    "/"
    (let [!i  (atom 0)
          nm  (fn [prefix] (str prefix (swap! !i inc)))
          seg (fn [t]
                (cond
                  (= :param t)       (str "/:" (nm "p"))
                  (= :splat t)       (str "/*" (nm "r"))
                  (= :lit (first t)) (str "/" (second t))
                  :else              (str "{"
                                          (apply str
                                                 (map (fn [it]
                                                        (if (= :param it)
                                                          (str "/:" (nm "q"))
                                                          (str "/" (second it))))
                                                      (second t)))
                                          "}?")))]
      (apply str (map seg tokens)))))

(defn- token-lits
  "Every literal segment text appearing in `tokens` (top level + inside
  optional groups)."
  [tokens]
  (mapcat (fn [t]
            (cond
              (= :param t)       nil
              (= :splat t)       nil
              (= :lit (first t)) [(second t)]
              :else              (keep (fn [it]
                                         (when (and (vector? it) (= :lit (first it)))
                                           (second it)))
                                       (second t))))
          tokens))

(defn- max-consume
  "The maximal number of segments `tokens` can consume with the splat
  taking exactly one — the witness-length bound (see the ns docstring)."
  [tokens]
  (reduce + 0 (map (fn [t]
                     (cond
                       (= :param t)       1
                       (= :splat t)       1
                       (= :lit (first t)) 1
                       :else              (count (second t))))
                   tokens)))

(defn- paths-upto
  "Every segment vector of length 0..max-len over `alphabet`."
  [alphabet max-len]
  (loop [l 0, layer [[]], acc [[]]]
    (if (= l max-len)
      acc
      (let [next-layer (vec (for [p layer, a alphabet] (conj p a)))]
        (recur (inc l) next-layer (into acc next-layer))))))

(defn- url-of [segs]
  (if (empty? segs) "/" (str "/" (str/join "/" segs))))

(deftest patterns-intersect-agrees-with-brute-force
  (testing "patterns-intersect? equals exhaustive URL enumeration over the
            pair's literal alphabet + one fresh symbol (seeded draws,
            identical stream on CLJ and CLJS)"
    (loop [iter 0
           s    20260712]
      (when (< iter 120)
        (let [[ta s1]  (gen-tokens s)
              [tb s2]  (gen-tokens s1)
              pa       (render-pattern ta)
              pb       (render-pattern tb)
              alphabet (-> (set (concat (token-lits ta) (token-lits tb)))
                           (conj "zz")
                           vec)
              max-len  (inc (max (max-consume ta) (max-consume tb)))
              ra       (:regex (rf.routing.match/parse-pattern pa))
              rb       (:regex (rf.routing.match/parse-pattern pb))
              brute    (boolean
                         (some (fn [segs]
                                 (let [url (url-of segs)]
                                   (and (re-matches ra url)
                                        (re-matches rb url))))
                               (paths-upto alphabet max-len)))
              pred     (rf.routing.match/patterns-intersect? pa pb)]
          (is (= brute pred)
              (str "iter " iter ": predicate disagrees with brute force on "
                   pa " vs " pb " (brute=" brute " predicate=" pred ")"))
          (recur (inc iter) (lcg-next s2)))))))
