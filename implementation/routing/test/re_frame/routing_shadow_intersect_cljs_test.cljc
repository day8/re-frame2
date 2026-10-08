(ns re-frame.routing-shadow-intersect-cljs-test
  "Co-matchability tests for `re-frame.routing.match/patterns-intersect?` —
  the Spec 012 §Route ranking algorithm rule-6 'same URL family' predicate
  behind `:rf.warning/route-shadowed-by-equal-score`.

  Two suites:

    1. OVERLAP TABLE — hand-picked pairs, one per path through the product
       automaton: param and literal labels, an optional group elided at the
       start or the end, the shifted witness (`/a{/x}?/b` vs `/a/x{/b}?` share
       NO literal column yet both match `/a/x/b`, which falsifies any naive
       corresponding-literals comparison) and its disjoint twin, splats, the
       `/*` root quirk, and the conservative fallback for a
       non-segment-aligned pattern. Each pair is asserted in both argument
       orders.

    2. PROPERTY — the predicate agrees with BRUTE FORCE: for generated
       pattern pairs, enumerate every candidate URL over the patterns'
       literal alphabet (plus one fresh symbol) up to a sufficient length
       bound and check both compiled regexes; the predicate must equal
       'some URL matches both'. A witness, when one exists, always exists
       over that alphabet (params/splats are free, literal positions are
       forced), and its minimal length is bounded by the larger pattern's
       maximal consumption, so the enumeration is exhaustive for the
       decision.

  Pure pattern-domain tests — no registrar / runtime fixture needed. The
  hand-rolled 32-bit LCG and fixed seed make a failure a stable repro.
  Named `*-cljs-test.cljc` so the JVM runner and the `:node-test` build both
  run it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [clojure.string :as str]
   [re-frame.routing.match :as rf.routing.match]))

;; ---- 1. the overlap table --------------------------------------------------

(def ^:private intersecting-pairs
  ;; [pattern-a pattern-b witness-note]
  [["/a/:x"           "/a/:y"          "the true rule-6 conflict — /a/anything"]
   ["/x/:id"          "/:kind/y"       "cross-position params — witness /x/y"]
   ["/a{/x}?/b"       "/a/x{/b}?"      "SHIFTED optional-group witness /a/x/b"]
   ["{/:base}?/about" "/about"         "elided leading group — witness /about"]
   ["/docs{/guide}?"  "/docs"          "elided trailing group — witness /docs"]
   ["/a/*r"           "/a/:x"          "splat consuming one segment — witness /a/z"]
   ["/a/*r"           "/a/b/*s"        "two splats, nested prefixes — witness /a/b/z"]
   ["/*"              "/"              "the splat-only root quirk — /* also matches /"]])

(def ^:private disjoint-pairs
  [["/x/:id"    "/y/:slug"  "equal rank, disjoint URL families — what an over-broad scan would flag"]
   ["/a"        "/a/b"      "different lengths never co-match"]
   ["/a{/x}?/b" "/a/y{/b}?" "{a/b, a/x/b} vs {a/y, a/y/b}"]])

(deftest patterns-intersect-overlap-table
  (doseq [[expected pairs] [[true intersecting-pairs] [false disjoint-pairs]]
          [pa pb note]     pairs]
    (is (= [expected expected]
           [(rf.routing.match/patterns-intersect? pa pb)
            (rf.routing.match/patterns-intersect? pb pa)])
        (str pa " vs " pb " — " note)))
  (testing "a non-segment-aligned pattern (`/a/{/x}?` elides to an empty
            segment) falls back to co-matchable, so the Spec 012 MUST-warn
            is never lost"
    (is (= [true true]
           [(rf.routing.match/patterns-intersect? "/a/{/x}?" "/a/y")
            (rf.routing.match/patterns-intersect? "/a/y" "/a/{/x}?")]))))

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
