(ns re-frame.ssr.root-manifest-cljs-test
  "Root Manifest v1 (S5-A) — the SUBSET PROPERTY and the wire/discovery
  contract (Spec 011 §Root Manifest v1; schema family Spec 004C §2).

  What this namespace covers is the manifest surface itself: validation
  (`problems`), assembly (`manifest`), locators, the render-time prop
  screen, the wire form (`script-html` / `read-manifest`) and CLJS
  discovery. Every assertion drives `re-frame.ssr.manifest` directly over
  hand-built manifests.

  WHAT USED TO BE HERE AND IS NOT, so the gap is legible rather than
  mysterious: a SUBSET-PROPERTY suite that drove the shipped S1 descriptor
  emitter (`re-frame.ui.compiler.root/root-descriptor`) and fed its output
  byte-for-byte to the validator, proving Root Manifest v1 a strict
  superset of Root Descriptor v1 against the real compiler rather than
  against a transcribed fixture. `re-frame.ui` has been retired
  (rf2-0yp7w) and no other substrate emits a Root Descriptor, so that
  property has no producer left to check it — it was deleted rather than
  re-expressed over a hand-written descriptor, because a hand-written one
  would assert the transcription and not the compiler, which is precisely
  what the original suite existed to avoid.

  Runs on BOTH hosts (`.cljc`, `-cljs-test` ns): `clojure -M:test` from
  `implementation/ssr` and the node runner via `npm run test:cljs`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.constants :as rf.ssr.constants]
            [re-frame.ssr.manifest :as rf.ssr.manifest]
            #?(:clj  [clojure.edn]
               :cljs [cljs.reader])))

;; A record defined HERE rather than reached for in some production ns:
;; the rf2-v4foc defect is about any value whose `pr-str` carries a tag
;; the safe reader cannot construct, and a locally-defined record is the
;; smallest honest instance of that class on both hosts.
(defrecord WireProbeRecord [x])

(deftest schema-version-is-the-only-required-key
  (is (nil? (rf.ssr.manifest/problems {:rf.root/schema-version 1}))
      "the minimal valid manifest is the version alone")
  (is (= {:missing :schema-version} (rf.ssr.manifest/problems {:root-id :page/shop})))
  (is (= {:invalid :schema-version :got 2 :expected 1}
         (rf.ssr.manifest/problems {:rf.root/schema-version 2}))
      "a foreign version is rejected — but there is no negotiation or
       upgrade path; v1 is the first version, not a compatibility layer")
  (is (= {:invalid :not-a-map :got (type "nope")}
         (rf.ssr.manifest/problems "nope"))))

(deftest unknown-keys-are-ignored-by-readers
  (testing "004C §2 rule 2 — including the dev-only :root-id-provenance an
            S1 descriptor still carries; stripping it is an EMIT duty, not
            a read-side rejection (or a descriptor could not validate)"
    (is (nil? (rf.ssr.manifest/problems
               {:rf.root/schema-version 1
                :root-id-provenance     :derived
                :some.future/key        [:whatever]})))))

;; ---------------------------------------------------------------------------
;; Assembly — the manifest EXTENDS, it does not replace
;; ---------------------------------------------------------------------------

(deftest assembly-validates-its-extension-facts
  (let [d {:rf.root/schema-version 1 :root-id :page/shop}]
    (doseq [bad [{:element-locator {:id ""}}
                 {:element-locator {:id 42}}
                 {:element-locator {:selector "#shop"}}
                 {:render-fingerprint 42}
                 {:identifier-prefix :not-a-string}
                 {:frame-payload-ids ["shop"]}]]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                   (rf.ssr.manifest/manifest 'test d bad))
          (str "ill-formed extension fact fails at ASSEMBLY: " (pr-str bad))))))

;; ---------------------------------------------------------------------------
;; Locators (004C §4) — the closed `{:id string}` vocabulary
;; ---------------------------------------------------------------------------

(deftest host-authored-container-needs-an-id
  (is (= {:id "shop-root"} (rf.ssr.manifest/host-locator 'test "shop-root")))
  (doseq [bad [nil "" "   "]]
    (let [data (try (rf.ssr.manifest/host-locator 'test bad) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e
                      (ex-data e)))]
      (is (= :rf.error/root-manifest-invalid (:rf.error/id data))
          (str "host container id " (pr-str bad)))
      (is (= :container-id (get-in data [:missing]))
          "the emitter NEVER synthesises an id onto host-owned markup"))))

(deftest synthesised-locator-inherits-slug-injectivity
  (is (= {:id "rf2-root-page_Sshop"} (rf.ssr.manifest/synthesised-locator "page_Sshop")))
  (is (not= (rf.ssr.manifest/synthesised-locator "page_Sshop")
            (rf.ssr.manifest/synthesised-locator "_V_Kshop_Sapp_Kleft"))
      "distinct slugs ⇒ distinct locators; the slug is injective (004C §1)"))

;; ---------------------------------------------------------------------------
;; Render-time props (004C §5) — fail loud, never truncate
;; ---------------------------------------------------------------------------

(deftest unserialisable-prop-fails-the-render-for-that-root
  (let [d    {:rf.root/schema-version 1 :root-id :page/shop}
        data (try (rf.ssr.manifest/manifest 'test d {:props {:promo :spring
                                                      :chart-fn (fn [_] 1)}})
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo
                            :cljs cljs.core/ExceptionInfo) e
                    (ex-data e)))]
    (is (= :rf.error/root-manifest-invalid (:rf.error/id data)))
    (is (= :chart-fn (:unserialisable-prop data))
        "the offending prop is NAMED — a silently truncated manifest would
         hydrate a different tree, not a smaller one"))
  (is (rf.ssr.manifest/edn-carryable? {:a [1 "x" :k {:b #{1 2}}]}))
  (is (not (rf.ssr.manifest/edn-carryable? {:a [1 {:b (fn [])}]}))
      "opaqueness is detected through nesting"))

;; ---------------------------------------------------------------------------
;; The wire form
;; ---------------------------------------------------------------------------

(deftest wire-form-round-trips
  (let [d {:rf.root/schema-version 1 :root-id :page/shop}
        m (rf.ssr.manifest/manifest d {:element-locator   {:id "shop-root"}
                                :identifier-prefix "rf2-page_Sshop-"})
        html (rf.ssr.manifest/script-html m)]
    (testing "the element carries both marks and no identity in attributes"
      (is (re-find #"^<script type=\"application/edn\" data-rf-root>" html))
      (is (re-find #"</script>$" html))
      (is (not (re-find #"data-rf-root=" html))
          "the marker is BARE — identity lives in the content, spelled once"))
    (testing "the body reads back to the same manifest"
      (let [body (-> html
                     (subs (count "<script type=\"application/edn\" data-rf-root>"))
                     (as-> s (subs s 0 (- (count s) (count "</script>")))))]
        (is (= m (rf.ssr.manifest/read-manifest 'test body)))))))

(deftest wire-body-cannot-break-out-of-the-script
  (let [m (rf.ssr.manifest/manifest {:rf.root/schema-version 1 :root-id :page/shop}
                             {:props {:bio "</script><img onerror=x>"}})
        html (rf.ssr.manifest/script-html m)]
    (is (not (re-find #"(?i)</script" (subs html 0 (- (count html) 9))))
        "the shared EDN script-body escape neutralises the breakout")
    (is (= m (rf.ssr.manifest/read-manifest
              'test
              (-> html
                  (subs (count "<script type=\"application/edn\" data-rf-root>"))
                  (as-> s (subs s 0 (- (count s) 9)))))))))

(deftest unreadable-or-foreign-body-fails-loud
  (doseq [body ["{:rf.root/schema-version 1" "@@@" ""]]
    (let [data (try (rf.ssr.manifest/read-manifest 'test body) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e
                      (ex-data e)))]
      (is (= :rf.error/root-manifest-invalid (:rf.error/id data))
          (str "body " (pr-str body) " — a hydrating root never guesses")))))

;; ---------------------------------------------------------------------------
;; THE EXACT ROUND TRIP (rf2-v4foc)
;; ---------------------------------------------------------------------------
;;
;; #6383 shipped a wire whose ACCEPTANCE predicate was not equivalent to
;; its READ. Three ways apart, each measured on both hosts before the fix:
;;
;;   1. a record-valued prop satisfied `map?`, so `edn-carryable?` walked
;;      its entries and said yes — but `pr-str` emits `#my.ns.R{…}` and
;;      the safe reader has no constructor for that tag;
;;   2. `serialise-props` tested only the VALUE half of each entry, so an
;;      opaque KEY (a fn, a host object) emitted `#object[…]`;
;;   3. `read-manifest` took the first form and silently discarded any
;;      trailing text or second form.
;;
;; All three turn an EMIT-time authoring mistake into a CLIENT hydration
;; failure, which is the one thing a fail-loud wire exists to prevent.
;;
;; The property below is `read(write(m)) = m` driven through the SHIPPED
;; `script-html` / `read-manifest` — not a hand-transcribed sample, and
;; not `pr-str` alone: it goes through the real script-body escape, so
;; the escape is part of what is proven.

(def ^:private script-prefix
  "<script type=\"application/edn\" data-rf-root>")

(defn- script-body
  "-> the exact bytes a browser hands back as the script's `.textContent`."
  [html]
  (-> html
      (subs (count script-prefix))
      (as-> s (subs s 0 (- (count s) (count "</script>"))))))

(defn- round-trips?
  "The property, through the shipped emitter and the shipped reader."
  [m]
  (= m (rf.ssr.manifest/read-manifest 'test (script-body (rf.ssr.manifest/script-html m)))))

(def ^:private wire-values
  "Every value class the manifest wire PROMISES to carry, spelled with
  literals that mean the same thing on both hosts. `nil` appears as a
  VALUE (distinct from an absent key, which the shapes below cover) and
  the string/keyword cases carry the `<` and `</script` sequences the
  body escape has to survive."
  {:nil            nil
   :true           true
   :false          false
   :string         "plain"
   :empty-string   ""
   :unicode        "héllo — ☃"
   :breakout       "</script><img onerror=x>"
   :lt-in-string   "a < b"
   :keyword        :k
   :ns-keyword     :a/b
   :lt-in-keyword  :x<y
   :symbol         'sym
   :ns-symbol      'a/b
   :zero           0
   :negative       -1
   :decimal        1.5
   :whole-double   9.0
   :vector         [1 2 3]
   :list           '(1 2 3)
   :set            #{1 2 3}
   :empty-map      {}
   :empty-vector   []
   :string-keys    {"a" 1 "b" 2}
   :mixed-keys     {:a 1 "b" 2 'c 3}
   :nil-value      {:a nil}
   :nested         {:a {:b [1 #{:c} {"d" :e}]}}})

(deftest every-emitted-manifest-round-trips-exactly
  (testing "rf2-v4foc — every manifest that reaches the wire reads back
            EQUAL, not merely readable, with and without extension facts"
    (let [d {:rf.root/schema-version 1 :root-id :page/shop}]
      (is (round-trips? (rf.ssr.manifest/manifest d {:element-locator    {:id "shop-root"}
                                              :props              {:promo :spring}
                                              :frame-payload-ids  [:shop :frame/session]
                                              :render-fingerprint "rf1-abc"
                                              :identifier-prefix  "rf2-page_Sshop-"}))
          "a fully-extended manifest survives the wire exactly")
      (is (round-trips? (rf.ssr.manifest/manifest d))
          "…and so does the bare one, so `nil` vs ABSENT is covered in both
           directions")))

  (testing "every value class the wire promises to carry, as a prop value
            and again as a prop KEY where the class can be a key"
    (doseq [[label v] wire-values]
      (let [m (rf.ssr.manifest/manifest {:rf.root/schema-version 1 :root-id :page/shop}
                                 {:props {:v v}})]
        (is (round-trips? m) (str "prop value " label " => " (pr-str v))))))

  (testing "a manifest whose props map is keyed by each carryable scalar —
            the KEY half of the entry rides the same wire as the value"
    (doseq [k [:k :a/b 'sym "str" 1 true nil]]
      (let [m (rf.ssr.manifest/manifest {:rf.root/schema-version 1 :root-id :page/shop}
                                 {:props {k :v}})]
        (is (round-trips? m) (str "prop key " (pr-str k)))))))

(deftest round-trip-guard-is-load-bearing
  (testing "THE RED-BEFORE for rf2-v4foc, kept executable: re-create the
            defective predicate (the one that tested `map?` before
            `record?`) and prove it ACCEPTS a value whose printed form the
            reader then REJECTS. That gap is the whole bug; the shipped
            predicate must never reproduce it, and this test fails if the
            `record?` arm is ever removed."
    (let [r (->WireProbeRecord 1)
          ;; The PRE-FIX predicate, verbatim in the one respect that
          ;; mattered: `map?` reached before any record test.
          lenient-carryable?
          (fn lenient? [v]
            (cond
              (nil? v) true (boolean? v) true (number? v) true
              (string? v) true (keyword? v) true (symbol? v) true
              (map? v) (every? (fn [[k v']] (and (lenient? k) (lenient? v'))) v)
              (coll? v) (every? lenient? v)
              :else false))]

      (testing "the defective predicate waves the record through"
        (is (true? (lenient-carryable? {:x r}))
            "if this ever goes false the lever is gone and the guard below
             proves nothing"))

      (testing "…yet its printed form does NOT read back — the exact
                asymmetry #6383 shipped"
        (is (not= (pr-str {:x r}) (pr-str (into {} r)))
            "a record does not PRINT as a map, which is why map?-shape was
             the wrong question")
        (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                        :cljs cljs.core/ExceptionInfo)
                     (rf.ssr.manifest/read-manifest
                      'test (pr-str {:rf.root/schema-version 1 :props {:x r}})))
            "the safe reader has no constructor for the record's tag"))

      (testing "and the SHIPPED predicate closes it"
        (is (not (rf.ssr.manifest/edn-carryable? r)))
        (is (not (rf.ssr.manifest/edn-carryable? {:x r})))
        (is (not (rf.ssr.manifest/edn-carryable? {:x [1 {:y r}]}))
            "detected through nesting, like every other opaque value")))))

(deftest record-valued-prop-fails-before-emission
  (testing "rf2-v4foc — a record prop is rejected at ASSEMBLY, naming the
            prop, rather than emitting a body the client cannot read"
    (let [data (try (rf.ssr.manifest/manifest
                     'test {:rf.root/schema-version 1 :root-id :page/shop}
                     {:props {:promo :spring :row (->WireProbeRecord 1)}})
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e
                      (ex-data e)))]
      (is (= :rf.error/root-manifest-invalid (:rf.error/id data)))
      (is (= :row (:unserialisable-prop data)) "the offending prop is NAMED")
      (is (= :value (:unserialisable-half data))))))

(deftest opaque-prop-KEY-fails-before-emission
  (testing "rf2-v4foc — `serialise-props` checked only the value half, so
            an opaque KEY reached `pr-str` and emitted `#object[…]`. Both
            halves are carried, so both halves are validated."
    (let [k    (fn [] 1)
          data (try (rf.ssr.manifest/manifest
                     'test {:rf.root/schema-version 1 :root-id :page/shop}
                     {:props {k 1}})
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e
                      (ex-data e)))]
      (is (= :rf.error/root-manifest-invalid (:rf.error/id data)))
      (is (= :key (:unserialisable-half data))
          "the diagnostic says WHICH half — a fn key and a fn value are
           different authoring mistakes")))

  (testing "a record used as a prop KEY is caught by the same arm"
    (is (not (rf.ssr.manifest/edn-carryable? {(->WireProbeRecord 1) :v})))))

;; ---------------------------------------------------------------------------
;; THE NUMERIC HALF OF THE ROUND TRIP (rf2-v4foc, Codex audit of #6407)
;; ---------------------------------------------------------------------------
;;
;; The round-trip property above is driven by ONE host at a time: the JVM
;; suite emits and reads with `clojure.edn`, the CLJS suite with
;; `cljs.reader`. Every JVM-only numeric form passes THAT test — it is
;; the CROSSING that loses, and no same-host suite can see it. This is
;; why SSR 419/1911, UI 928/16132 and CLJS 10099/49944 were all green
;; while the wire silently changed application-visible hydration props.
;;
;; So the proof below is joined by BYTES rather than by a shared host.
;; `jvm-emitted-body` is the exact script body the shipped emitter
;; produced for `{:props {:x 9007199254740993N}}`. Each host then asserts
;; its OWN half against those same bytes, with the shipped reader:
;;
;;   JVM  reads them back as 9007199254740993N  — the value rendered
;;   CLJS reads them back as 9007199254740992   — a DIFFERENT number
;;
;; One wire, two hosts, two values, no error on either side. That is the
;; defect stated as an executable fact, and it stays executable after the
;; fix: it is *why* the emitter must now refuse the value.

(def ^:private jvm-emitted-body
  "The exact `<script>` body the JVM emitter produced for a bigint prop
  before this fix. Pinned as BYTES because bytes are what actually cross
  — reconstructing it per-host would beg the question the test asks.

  The JVM half below pins the one token that matters
  (`9007199254740993N`) against the live printer, so this literal cannot
  quietly go stale."
  "{:rf.root/schema-version 1, :root-id :page/shop, :phase :server, :props {:x 9007199254740993N}}")

(deftest jvm-emitted-number-reads-back-differently-on-cljs
  (testing "rf2-v4foc — the SAME wire bytes, read by the SHIPPED
            `read-manifest` on each host, yield DIFFERENT hydration props.
            Asserted as an observable VALUE: nothing throws, which is the
            entire danger."
    (let [x (get-in (rf.ssr.manifest/read-manifest 'test jvm-emitted-body) [:props :x])]
      #?(:clj
         (do
           (is (= (pr-str 9007199254740993N) "9007199254740993N")
               "the JVM printer really does emit the bigint token pinned in
                `jvm-emitted-body` — if this reds, that literal is stale")
           (is (= 9007199254740993N x)
               "the SERVER reads its own wire back exactly")
           (is (= "9007199254740993N" (pr-str x))))

         :cljs
         (do
           (is (= "9007199254740992" (pr-str x))
               "THE DEFECT: the browser's reader silently rounds the
                server's 9007199254740993 down to 9007199254740992. The app
                hydrates with a number the server never rendered.")
           (is (not= "9007199254740993" (pr-str x))
               "stated the other way round, so the assertion cannot pass by
                naming the value it is supposed to reject"))))))

(deftest numeric-wire-guard-is-load-bearing
  (testing "THE RED-BEFORE for the numeric arm, kept executable in the same
            shape as the `record?` lever above: re-create the predicate
            that said `(number? v) true` and prove it ACCEPTS the very
            value the test above shows the far host mangles."
    (let [lenient-number? (fn [v] (number? v))]
      #?(:clj
         (doseq [v [9007199254740993N 1.5M 1/3 9007199254740993 (float 0.1)]]
           (is (true? (lenient-number? v))
               (str "the defective predicate waves " (pr-str v) " through — if
                     this ever goes false the lever is gone"))
           (is (not (rf.ssr.manifest/edn-carryable? v))
               (str "…and the SHIPPED predicate refuses it: " (pr-str v))))
         :cljs
         (let [nan js/NaN]
           (is (true? (lenient-number? nan)))
           (is (not (rf.ssr.manifest/edn-carryable? nan))))))))

(deftest only-cross-host-numbers-ride-the-wire
  (testing "rf2-v4foc — what the numeric subset ADMITS. These must keep
            working: narrowing the wire must not narrow ordinary props."
    (doseq [v [0 -1 1 1.5 -1.5 9.0 0.1
               rf.ssr.manifest/max-safe-integer
               (- rf.ssr.manifest/max-safe-integer)
               ##Inf ##-Inf
               #?(:clj 1.0E308 :cljs 1e308)]]
      (is (rf.ssr.manifest/edn-carryable? v) (str "must still carry " (pr-str v))))

    (testing "…including through nesting and as a prop KEY"
      (is (rf.ssr.manifest/edn-carryable? {:a [1 {:b #{2 3.5}}]}))
      (is (rf.ssr.manifest/edn-carryable? {1 :v}))))

  (testing "what it REFUSES, and why each one is not merely pedantic"
    (is (not (rf.ssr.manifest/edn-carryable? #?(:clj Double/NaN :cljs js/NaN)))
        "NaN is excluded by the round-trip property itself — it is not `=`
         to itself, so it cannot read back EQUAL on any host")

    #?(:clj
       (do
         (is (not (rf.ssr.manifest/edn-carryable? 9007199254740993N))
             "bigint: prints an `N` token the browser reproduces as a
              different number")
         (is (not (rf.ssr.manifest/edn-carryable? 1.5M))
             "bigdec: prints an `M` token the browser reads as a plain
              number, changing the prop's TYPE")
         (is (not (rf.ssr.manifest/edn-carryable? 1/3))
             "ratio: the browser reads `1/3` as 0.3333333333333333 — an
              APPROXIMATION, silently")
         (is (not (rf.ssr.manifest/edn-carryable? (inc rf.ssr.manifest/max-safe-integer)))
             "a Long one past 2^53-1 carries precision no double holds")
         (is (not (rf.ssr.manifest/edn-carryable? (- (inc rf.ssr.manifest/max-safe-integer))))
             "…and the bound is symmetric about zero")
         (is (not (rf.ssr.manifest/edn-carryable? (float 0.1)))
             "float: fails the property on the JVM ALONE — the printed
              shortest-decimal names the DOUBLE 0.1, so it does not read
              back equal even here")))

    (testing "a large DOUBLE is admitted though it dwarfs the integer
              bound — the bound is about representability, not magnitude"
      (is (rf.ssr.manifest/edn-carryable? #?(:clj 1.0E308 :cljs 1e308))))))

;; ---------------------------------------------------------------------------
;; ±INFINITY RIDES; ONLY NaN IS EXCLUDED (rf2-pdcoh — Spec 011 same-contract)
;; ---------------------------------------------------------------------------
;;
;; Spec 011 once read "finite doubles", but `wire-number?` admits ±Infinity and
;; always has: EDN prints `##Inf` / `##-Inf` and reads them back EXACTLY, so
;; they satisfy the round-trip property. `##NaN` alone is excluded — it is not
;; `=` to itself. The predicate, the spec prose, and these pins now agree, on
;; BOTH hosts.

(deftest infinities-ride-only-nan-is-excluded
  (testing "±Infinity is admitted on both hosts — EDN round-trips it exactly"
    (is (rf.ssr.manifest/edn-carryable? ##Inf))
    (is (rf.ssr.manifest/edn-carryable? ##-Inf)))
  (testing "…and NaN alone is refused, by the round-trip property on its own terms"
    (is (not (rf.ssr.manifest/edn-carryable? #?(:clj Double/NaN :cljs js/NaN)))))
  (testing "±Infinity survives the shipped wire, both hosts — the accepted pin"
    (doseq [inf [##Inf ##-Inf]]
      (let [m (rf.ssr.manifest/manifest {:rf.root/schema-version 1 :root-id :page/shop}
                                 {:props {:v inf}})]
        (is (round-trips? m) (str "±Inf prop " (pr-str inf) " round-trips"))
        (is (= inf (get-in (rf.ssr.manifest/read-manifest
                            'test (script-body (rf.ssr.manifest/script-html m)))
                           [:props :v]))
            "the exact infinity the server rendered is what the reader returns")))))

;; ---------------------------------------------------------------------------
;; CROSS-HOST NUMERIC KEY / SET COLLISIONS (rf2-pdcoh)
;; ---------------------------------------------------------------------------
;;
;; PR #6437 made `wire-number?` decide, PER VALUE, whether a number crosses the
;; JVM→CLJS wire. Per-value acceptance is not enough for a whole map or set:
;; the server and the browser do not share numeric key identity. On the JVM `1`
;; and `1.0` (and `0` and `-0.0`) are two DISTINCT keys that BOTH pass
;; `edn-carryable?`; the browser reads every number as one double, so the
;; emitted body reads back with a DUPLICATE key — the accepted manifest does
;; not round-trip, and its cardinality changes across the wire. As with the
;; numeric-scalar arm above, no same-host suite can see it: the server reads its
;; own two-key body back perfectly. So this proof, too, is joined by BYTES.

(def ^:private collision-bodies
  "Exact `<script>` bodies a PRE-fix server emitted for colliding manifests,
  pinned as BYTES because bytes are what cross. Map-key, set-element, and
  signed-zero arms, plus the rf2-pdcoh COMPOSITE arms — a VECTOR key and a
  vector set element, where each key/element is a collection whose numeric
  leaves (not the key itself) collapse. The JVM half pins the tokens against
  the live printer so these literals cannot go stale."
  {:map     "{:rf.root/schema-version 1, :root-id :page/shop, :phase :server, :props {:lookup {1 :integer, 1.0 :double}}}"
   :set     "{:rf.root/schema-version 1, :root-id :page/shop, :phase :server, :props {:tags #{1.0 1}}}"
   :zero    "{:rf.root/schema-version 1, :root-id :page/shop, :phase :server, :props {:lookup {0 :a, -0.0 :b}}}"
   :vec-map "{:rf.root/schema-version 1, :root-id :page/shop, :phase :server, :props {:lookup {[1] :integer-vector, [1.0] :double-vector}}}"
   :vec-set "{:rf.root/schema-version 1, :root-id :page/shop, :phase :server, :props {:tags #{[1.0] [1]}}}"})

(deftest colliding-wire-bytes-part-across-hosts
  (testing "rf2-pdcoh — the SAME wire bytes, read by the shipped `read-manifest`
            on each host, PART: the server reads its own body back with BOTH
            keys; the browser rejects it as a duplicate. One wire, two
            cardinalities — the defect as an executable fact, and WHY emission
            must now refuse the value."
    #?(:clj
       (do
         (is (= "{1 :integer, 1.0 :double}" (pr-str {1 :integer 1.0 :double}))
             "the map tokens pinned in `collision-bodies` match the live printer")
         (is (= "#{1.0 1}" (pr-str #{1 1.0}))
             "…and the set token — if either reds, the pinned bytes are stale")
         (is (= "{[1] :integer-vector, [1.0] :double-vector}"
                (pr-str {[1] :integer-vector [1.0] :double-vector}))
             "the COMPOSITE map-key tokens — the residual #6489's direct walk missed")
         (is (= "#{[1.0] [1]}" (pr-str #{[1] [1.0]}))
             "…and the composite set-element token")
         (is (= 2 (count (get-in (rf.ssr.manifest/read-manifest 'test (:map collision-bodies))
                                 [:props :lookup])))
             "the server round-trips two distinct keys — same-host is blind")
         (is (= 2 (count (get-in (rf.ssr.manifest/read-manifest 'test (:set collision-bodies))
                                 [:props :tags]))))
         (is (= 2 (count (get-in (rf.ssr.manifest/read-manifest 'test (:vec-map collision-bodies))
                                 [:props :lookup])))
             "…and two distinct VECTOR keys — same-host is blind to composites too")
         (is (= 2 (count (get-in (rf.ssr.manifest/read-manifest 'test (:vec-set collision-bodies))
                                 [:props :tags])))))
       :cljs
       (doseq [[label body] collision-bodies]
         (let [data (try (rf.ssr.manifest/read-manifest 'test body) nil
                         (catch cljs.core/ExceptionInfo e (ex-data e)))]
           (is (= :rf.error/root-manifest-invalid (:rf.error/id data))
               (str label " — the browser rejects the colliding body"))
           (is (= :unreadable (:invalid data))
               (str label " — as an unreadable body: the CLJS reader flags the "
                    "duplicate the two server numbers collapsed into")))))))

#?(:clj
   (deftest cross-host-numeric-collision-fails-before-emission
     (testing "rf2-pdcoh — the server holds two distinct keys the browser cannot
               tell apart; `script-html` refuses the manifest BEFORE emission,
               naming the offending collection, its colliding keys, and the one
               browser double they share."
       ;; map-key collision, nested under :props
       (let [collide {1 :integer 1.0 :double}
             m       {:rf.root/schema-version 1 :root-id :page/shop
                      :props {:lookup collide}}]
         (is (= 2 (count collide)) "the server really does hold two distinct keys")
         (is (rf.ssr.manifest/edn-carryable? collide)
             "THE LEVER: each key and value rides the wire ALONE — exactly what
              PR #6437 checked. Per-value carryability is not the property.")
         (let [data (try (rf.ssr.manifest/script-html m) nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))]
           (is (= :rf.error/root-manifest-invalid (:rf.error/id data))
               "the EXISTING error id — a colliding manifest is a malformed
                manifest, not a new failure kind, so no new catalogue row")
           (is (= :cross-host-numeric-collision (:invalid data)))
           (is (= :map-keys (:collision data)))
           (is (= [:props :lookup] (:path data)) "the offending collection is NAMED")
           (is (= #{1 1.0} (set (:collapses data))) "and its colliding keys")
           (is (= 1.0 (:browser-number data)) "and the one browser double they share")))

       ;; set-element collision
       (let [m    {:rf.root/schema-version 1 :root-id :page/shop
                   :props {:tags #{1 1.0}}}
             data (try (rf.ssr.manifest/script-html m) nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
         (is (= :cross-host-numeric-collision (:invalid data)))
         (is (= :set-elements (:collision data)))
         (is (= [:props :tags] (:path data)))
         (is (= #{1 1.0} (set (:collapses data)))))

       ;; signed zero — the JVM keeps Long 0 and double -0.0 apart; the browser
       ;; holds ONE zero (verified: `(= 0 -0.0)` is true on CLJS)
       (let [collide (into {} [[0 :a] [-0.0 :b]])
             m       {:rf.root/schema-version 1 :root-id :page/shop
                      :props {:lookup collide}}]
         (is (= 2 (count collide)) "0 (Long) and -0.0 (double) are two keys on the JVM")
         (let [data (try (rf.ssr.manifest/script-html m) nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))]
           (is (= :cross-host-numeric-collision (:invalid data)))
           (is (= 0.0 (:browser-number data)) "0 and -0.0 share the browser's one zero")))

       ;; the gate is TOTAL: a DESCRIPTOR key `serialise-props` never sees is
       ;; gated too, because `script-html` is the one door onto the wire
       (let [m    {:rf.root/schema-version 1 :root-id :page/shop
                   :static-props {:m {1 :a 1.0 :b}}}
             data (try (rf.ssr.manifest/script-html m) nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
         (is (= :cross-host-numeric-collision (:invalid data)))
         (is (= [:static-props :m] (:path data))
             "a descriptor key collides too — the check is at emission, not props-only"))

       ;; nested through a vector — collisions are found at any depth
       (let [m    {:rf.root/schema-version 1 :root-id :page/shop
                   :props {:xs [{:a 1} #{2 2.0}]}}
             data (try (rf.ssr.manifest/script-html m) nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
         (is (= :cross-host-numeric-collision (:invalid data)))
         (is (= :set-elements (:collision data)))
         (is (= [:props :xs 1] (:path data)) "the path indexes into the vector"))

       ;; rf2-pdcoh RESIDUAL — COMPOSITE sibling KEYS. `[1]` and `[1.0]` are two
       ;; distinct vectors on the server, and NEITHER is a number, so #6489's
       ;; directly-numeric grouping accepted them and emitted the body; the
       ;; browser reads both as `[1.0]` and rejects the duplicate. The walk now
       ;; projects the WHOLE key before comparing siblings.
       (let [collide {[1] :integer-vector [1.0] :double-vector}
             m       {:rf.root/schema-version 1 :root-id :page/shop
                      :props {:lookup collide}}]
         (is (= 2 (count collide)) "two distinct VECTOR keys on the server")
         (is (rf.ssr.manifest/edn-carryable? collide)
             "THE LEVER: each composite key rides the wire ALONE, and each is a
              vector, not a number — so the directly-numeric group had nothing to
              grab. Per-key carryability is not the property.")
         (let [data (try (rf.ssr.manifest/script-html m) nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))]
           (is (= :rf.error/root-manifest-invalid (:rf.error/id data))
               "the EXISTING id — still a malformed manifest, no new catalogue row")
           (is (= :cross-host-numeric-collision (:invalid data)))
           (is (= :map-keys (:collision data)))
           (is (= [:props :lookup] (:path data)) "the offending collection is NAMED")
           (is (= #{[1] [1.0]} (set (:collapses data))) "and its colliding vector keys")
           (is (= [1.0] (:browser-number data))
               "and the one browser value they collapse to")))

       ;; composite SET elements — same class, one collection out
       (let [m    {:rf.root/schema-version 1 :root-id :page/shop
                   :props {:tags #{[1] [1.0]}}}
             data (try (rf.ssr.manifest/script-html m) nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
         (is (= :cross-host-numeric-collision (:invalid data)))
         (is (= :set-elements (:collision data)))
         (is (= [:props :tags] (:path data)))
         (is (= #{[1] [1.0]} (set (:collapses data)))))

       ;; DEEP composite collision — a set inside a vector inside a map: the
       ;; residual is closed at ARBITRARY depth, not just one collection down.
       (let [m    {:rf.root/schema-version 1 :root-id :page/shop
                   :props {:xs [{:k #{[1] [1.0]}}]}}
             data (try (rf.ssr.manifest/script-html m) nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
         (is (= :cross-host-numeric-collision (:invalid data)))
         (is (= :set-elements (:collision data)))
         (is (= [:props :xs 0 :k] (:path data))
             "the path threads map -> vector -> map -> set to the colliding set")))))

(deftest a-mutation-that-resolves-the-collision-emits-fine
  (testing "rf2-pdcoh — THE RED-BEFORE MUTATION: change one colliding number so
            the two keys/elements no longer share a browser double, and the very
            same shape emits and round-trips. This proves each rejection above is
            the COLLISION, not the shape — and that the gate does not narrow
            ordinary numeric props."
    (doseq [[label m]
            {:map-mutated  {:rf.root/schema-version 1 :root-id :page/shop
                            :props {:lookup {1 :integer 2.0 :double}}}
             :set-mutated  {:rf.root/schema-version 1 :root-id :page/shop
                            :props {:tags #{1.0 2}}}
             :zero-mutated {:rf.root/schema-version 1 :root-id :page/shop
                            :props {:lookup {0 :a 1.0 :b}}}
             :single-key   {:rf.root/schema-version 1 :root-id :page/shop
                            :props {:lookup {1 :only}}}
             :distinct-set {:rf.root/schema-version 1 :root-id :page/shop
                            :props {:tags #{1 2 3}}}
             :nested-ok    {:rf.root/schema-version 1 :root-id :page/shop
                            :props {:a [1 {:b #{2 3.5}}]}}
             ;; rf2-pdcoh — the COMPOSITE mutations: change one leaf so the two
             ;; vector keys/elements no longer project to one browser value, and
             ;; the same composite shape emits and round-trips. Proves the
             ;; residual rejection is the COLLISION, not the composite shape.
             :vec-map-ok   {:rf.root/schema-version 1 :root-id :page/shop
                            :props {:lookup {[1] :integer-vector [2.0] :double-vector}}}
             :vec-set-ok   {:rf.root/schema-version 1 :root-id :page/shop
                            :props {:tags #{[1] [2.0]}}}
             :deep-vec-ok  {:rf.root/schema-version 1 :root-id :page/shop
                            :props {:xs [{:k #{[1] [2.0]}}]}}
             ;; distinct composite keys that are NOT a collision must still emit
             :two-vec-ok   {:rf.root/schema-version 1 :root-id :page/shop
                            :props {:lookup {[1 2] :a [1 3] :b}}}}]
      (is (string? (rf.ssr.manifest/script-html m))
          (str label " — a non-colliding numeric collection still emits"))
      (is (round-trips? m)
          (str label " — …and round-trips exactly")))))

#?(:clj
   (deftest non-carryable-number-fails-before-emission
     (testing "rf2-v4foc — a JVM-only number is refused at ASSEMBLY naming
               the prop, and at the EMISSION gate naming the manifest key,
               exactly as an opaque value is. Not a second mechanism —
               the same `edn-carryable?`, told the truth about numbers."
       (let [data (try (rf.ssr.manifest/manifest
                        'test {:rf.root/schema-version 1 :root-id :page/shop}
                        {:props {:promo :spring :ratio 1/3}})
                       nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
         (is (= :rf.error/root-manifest-invalid (:rf.error/id data))
             "the EXISTING error id — a non-carryable number is not a new
              failure kind, so it needs no new catalogue row")
         (is (= :ratio (:unserialisable-prop data)) "the offending prop is NAMED")
         (is (= :value (:unserialisable-half data))))

       (testing "and the emission gate covers a DESCRIPTOR key too, which
                 `serialise-props` never sees"
         (let [data (try (rf.ssr.manifest/script-html
                          {:rf.root/schema-version 1
                           :root-id                :page/shop
                           :static-props           {:limit 9007199254740993N}})
                         nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))]
           (is (= :rf.error/root-manifest-invalid (:rf.error/id data)))
           (is (= :static-props (:unserialisable-manifest-key data)))))

       (testing "the bigint that started this: `script-html` no longer
                 emits the body the CLJS reader mangles"
         (is (thrown? clojure.lang.ExceptionInfo
                      (rf.ssr.manifest/script-html
                       (assoc {:rf.root/schema-version 1 :root-id :page/shop}
                              :props {:x 9007199254740993N}))))))))

(deftest script-emission-gates-the-whole-manifest
  (testing "rf2-v4foc — `serialise-props` screens `:props` at assembly, but
            `script-html` is the only door onto the wire and DESCRIPTOR
            keys ride through it too. A non-carryable `:static-props` was
            emitted happily before this gate; the property `every manifest
            accepted for script emission round-trips` is only true if the
            gate is at emission."
    (doseq [k [:static-props :props-shape :frame-plans :root-id]]
      (let [m    (assoc {:rf.root/schema-version 1 :root-id :page/shop}
                        k {:x (->WireProbeRecord 1)})
            data (try (rf.ssr.manifest/script-html m) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo
                                :cljs cljs.core/ExceptionInfo) e
                        (ex-data e)))]
        (is (= :rf.error/root-manifest-invalid (:rf.error/id data))
            (str "descriptor key " k " must be gated too"))
        (is (= k (:unserialisable-manifest-key data))))))

  (testing "the gate does NOT narrow what a valid manifest may hold —
            ordinary data still emits"
    (is (string? (rf.ssr.manifest/script-html
                  (rf.ssr.manifest/manifest {:rf.root/schema-version 1
                                      :root-id                :page/shop
                                      :static-props           {:limit 10}})))
        "the gate rejects unwireable values, not ordinary data")))

(deftest body-must-hold-exactly-one-edn-form
  (testing "rf2-v4foc — `read-string` returns the FIRST form and discards
            the rest, so a truncated render, two concatenated manifests, or
            an injected suffix all hydrated silently against the first map.
            A manifest is ONE value."
    (doseq [[label body] {:trailing-text  "{:rf.root/schema-version 1} trailing"
                          :second-form    "{:rf.root/schema-version 1} {:second true}"
                          :second-scalar  "{:rf.root/schema-version 1} 42"
                          :duplicate      (str "{:rf.root/schema-version 1} "
                                               "{:rf.root/schema-version 1}")
                          :empty          ""}]
      (let [data (try (rf.ssr.manifest/read-manifest 'test body) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo
                                :cljs cljs.core/ExceptionInfo) e
                        (ex-data e)))]
        (is (= :rf.error/root-manifest-invalid (:rf.error/id data))
            (str label " must be rejected, not silently truncated to its "
                 "first form"))
        (is (= :form-count (:invalid data)) (str label)))))

  (testing "…while whitespace around the one form is FINE — the rule is one
            FORM, not one line"
    (doseq [body ["  {:rf.root/schema-version 1}  "
                  "\n\t{:rf.root/schema-version 1}\n"
                  "{:rf.root/schema-version 1}\n"]]
      (is (= {:rf.root/schema-version 1} (rf.ssr.manifest/read-manifest 'test body))
          (str "whitespace-padded body " (pr-str body)))))

  (testing "a trailing `;` comment does not swallow the wrapper's closing
            bracket — the reader wraps in `[ … \\n]` precisely so it cannot"
    (is (= {:rf.root/schema-version 1}
           (rf.ssr.manifest/read-manifest 'test "{:rf.root/schema-version 1} ; note"))))

  (testing "an EDN discard is not a second form"
    (is (= {:rf.root/schema-version 1}
           (rf.ssr.manifest/read-manifest 'test "{:rf.root/schema-version 1} #_{:x 1}")))))

(deftest one-form-guard-is-load-bearing
  (testing "THE RED-BEFORE for the one-form rule, kept executable: the
            bundled reader really does discard the suffix, so without the
            count check the bodies above are accepted. If a future reader
            starts throwing on trailing content this test goes red and the
            explicit check can be reconsidered — it never rots into a
            tautology."
    (doseq [body ["{:rf.root/schema-version 1} trailing"
                  "{:rf.root/schema-version 1} {:second true}"]]
      (is (= {:rf.root/schema-version 1}
             #?(:clj  (clojure.edn/read-string body)
                :cljs (cljs.reader/read-string body)))
          (str "the raw reader silently accepts " (pr-str body)
               " — that is the hole `read-manifest` now closes")))))

(deftest marker-attribute-is-pinned-in-one-place
  (is (= "data-rf-root" rf.ssr.constants/root-manifest-marker-attribute))
  (is (= "application/edn" rf.ssr.manifest/manifest-script-type)))

;; ---------------------------------------------------------------------------
;; Discovery (CLJS) — adjacency, and nothing else
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn stub-el
     "A structural stand-in for a DOM element — `discover` reaches for
     exactly three things, so the contract is testable in the node runner
     without a browser."
     [{:keys [tag attrs text next]}]
     #js {:tagName            (or tag "SCRIPT")
          :textContent        (or text "")
          :nextElementSibling next
          :getAttribute       (fn [k] (get attrs k))
          :hasAttribute       (fn [k] (contains? attrs k))}))

#?(:cljs
   (deftest discovery-is-adjacency-and-content
     ;; `m` carries an AUTHORED `:identifier-prefix`, so `discover` passes it
     ;; through verbatim and this test stays about ADJACENCY (the omitted-prefix
     ;; canonicalization has its own test below, rf2-y3swx).
     (let [m    (rf.ssr.manifest/manifest {:rf.root/schema-version 1
                                    :root-id :page/shop}
                                   {:element-locator   {:id "shop-root"}
                                    :identifier-prefix "shop-"})
           body (pr-str m)
           script (stub-el {:attrs {"type" "application/edn"
                                    rf.ssr.constants/root-manifest-marker-attribute ""}
                            :text  body})]
       (testing "the immediately following element sibling IS the manifest"
         (is (= m (rf.ssr.manifest/discover (stub-el {:tag "DIV" :next script})))))

       (testing "identity comes from the CONTENT, never the element"
         (is (= :page/shop
                (:root-id (rf.ssr.manifest/discover (stub-el {:tag "DIV" :next script}))))))

       (testing "no sibling ⇒ nil; the caller decides what absence means"
         (is (nil? (rf.ssr.manifest/discover (stub-el {:tag "DIV" :next nil})))))

       (testing "an ordinary application/edn script is NOT a manifest —
                 the bare marker is what distinguishes the hydration
                 payload and data islands from a root manifest"
         (is (nil? (rf.ssr.manifest/discover
                    (stub-el {:tag "DIV"
                              :next (stub-el {:attrs {"type" "application/edn"}
                                              :text  body})})))))

       (testing "a non-script sibling is not searched through"
         (is (nil? (rf.ssr.manifest/discover
                    (stub-el {:tag "DIV"
                              :next (stub-el {:tag "DIV" :next script})})))
             "discovery does not walk — adjacency is the whole rule")))))

;; ---------------------------------------------------------------------------
;; Omitted identifier-prefix -> React's effective empty prefix (rf2-y3swx)
;;
;; React 19.2's hydrateRoot has no distinct "no prefix" state: it canonicalizes
;; an omitted `identifierPrefix` option to the empty string "", and useId
;; derives its ids from that empty prefix (server renderToString with no prefix
;; does the same). So a bare Root Manifest that OMITS :identifier-prefix means
;; the server rendered under React's effective EMPTY prefix — not "no effective
;; prefix". Discovery, the hydration identity boundary, resolves that to "".
;; ---------------------------------------------------------------------------

(deftest canonicalize-effective-prefix-resolves-omitted-to-react-empty
  (testing "an OMITTED :identifier-prefix resolves to React's effective \"\""
    (is (= {:rf.root/schema-version 1 :root-id :page/shop :identifier-prefix ""}
           (rf.ssr.manifest/canonicalize-effective-prefix
            {:rf.root/schema-version 1 :root-id :page/shop})))
    (is (= "" (:identifier-prefix
               (rf.ssr.manifest/canonicalize-effective-prefix
                {:rf.root/schema-version 1 :root-id :page/shop})))
        "the resolved effective prefix is the empty string, not nil"))
  (testing "an AUTHORED :identifier-prefix is passed through UNTOUCHED"
    (is (= {:rf.root/schema-version 1 :root-id :page/shop :identifier-prefix "shop-"}
           (rf.ssr.manifest/canonicalize-effective-prefix
            {:rf.root/schema-version 1 :root-id :page/shop :identifier-prefix "shop-"})))
    (is (= {:rf.root/schema-version 1 :root-id :page/shop :identifier-prefix ""}
           (rf.ssr.manifest/canonicalize-effective-prefix
            {:rf.root/schema-version 1 :root-id :page/shop :identifier-prefix ""}))
        "an explicitly-empty authored prefix is left as-is (already \"\")")))

#?(:cljs
   (deftest discover-stamps-react-empty-prefix-on-a-bare-manifest
     ;; RED-BEFORE: the shipped `discover` returned the bare manifest verbatim,
     ;; so `hydrate-root*` read a NIL prefix and the Layer-3 prefix-uniqueness
     ;; arm (a no-op on nil) admitted every omitted-prefix root.
     (let [bare   (rf.ssr.manifest/manifest {:rf.root/schema-version 1 :root-id :page/a})
           script (stub-el {:attrs {"type" "application/edn"
                                    rf.ssr.constants/root-manifest-marker-attribute ""}
                            :text  (pr-str bare)})
           found  (rf.ssr.manifest/discover (stub-el {:tag "DIV" :next script}))]
       (testing "the bare manifest omits :identifier-prefix on the wire"
         (is (not (contains? bare :identifier-prefix))
             "manifest optionality is preserved — the extension key is absent"))
       (testing "discovery resolves it to React's effective empty prefix"
         (is (= "" (:identifier-prefix found)))
         (is (= (assoc bare :identifier-prefix "") found)
             "identity is the manifest plus the resolved effective prefix")))))
