(ns re-frame.ssr.root-manifest-cljs-test
  "Root Manifest v1 (Spec 011 §Root Manifest v1; schema family Spec 004C §2):
  validation (`problems`), assembly (`manifest`), locators, the render-time
  prop screen, the wire form (`script-html` / `read-manifest`) and CLJS
  discovery, all driven over hand-built manifests. Runs on the JVM and Node.

  No substrate emits a Root Descriptor, so the subset property (an unmodified
  Root Descriptor v1 is a valid Root Manifest v1) is checked over hand-built
  manifests, not against a real emitter's output."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.manifest :as rf.ssr.manifest]
            #?(:clj  [clojure.edn]
               :cljs [cljs.reader])
            #?(:cljs [re-frame.ssr.constants :as rf.ssr.constants])))

;; The smallest value whose `pr-str` carries a tag the safe reader cannot
;; construct, on both hosts.
(defrecord WireProbeRecord [x])

(defn- invalid-data
  "The ex-data `thunk` throws, or nil when it returns."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(deftest schema-version-is-the-only-required-key
  (is (nil? (rf.ssr.manifest/problems {:rf.root/schema-version 1}))
      "the minimal valid manifest is the version alone")
  (is (= {:missing :schema-version} (rf.ssr.manifest/problems {:root-id :page/shop})))
  (is (= {:invalid :schema-version :got 2 :expected 1}
         (rf.ssr.manifest/problems {:rf.root/schema-version 2})))
  (is (= {:invalid :not-a-map :got (type "nope")}
         (rf.ssr.manifest/problems "nope"))))

(deftest unknown-keys-are-ignored-by-readers
  ;; 004C §2 rule 2 — including the dev-only :root-id-provenance a descriptor
  ;; still carries: stripping it is an EMIT duty, not a read-side rejection.
  (is (nil? (rf.ssr.manifest/problems
             {:rf.root/schema-version 1
              :root-id-provenance     :derived
              :some.future/key        [:whatever]}))))

;; ---------------------------------------------------------------------------
;; Assembly and locators
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

(deftest host-authored-container-needs-an-id
  (is (= {:id "shop-root"} (rf.ssr.manifest/host-locator 'test "shop-root")))
  ;; The emitter never synthesises an id onto host-owned markup.
  (doseq [bad [nil "   "]]
    (is (= {:rf.error/id :rf.error/root-manifest-invalid :missing :container-id}
           (select-keys (invalid-data #(rf.ssr.manifest/host-locator 'test bad))
                        [:rf.error/id :missing]))
        (str "host container id " (pr-str bad)))))

(deftest synthesised-locator-inherits-slug-injectivity
  (is (= {:id "rf2-root-page_Sshop"} (rf.ssr.manifest/synthesised-locator "page_Sshop"))))

;; ---------------------------------------------------------------------------
;; Render-time props (004C §5) — fail loud, never truncate
;; ---------------------------------------------------------------------------

(deftest unserialisable-prop-fails-the-render-for-that-root
  (is (= {:rf.error/id          :rf.error/root-manifest-invalid
          :unserialisable-prop  :chart-fn
          :unserialisable-half  :value}
         (select-keys (invalid-data #(rf.ssr.manifest/manifest
                                       'test {:rf.root/schema-version 1 :root-id :page/shop}
                                       {:props {:promo :spring :chart-fn (fn [_] 1)}}))
                      [:rf.error/id :unserialisable-prop :unserialisable-half])))
  (is (not (rf.ssr.manifest/edn-carryable? {:a [1 {:b (fn [])}]}))
      "opaqueness is detected through nesting"))

(deftest record-valued-prop-fails-before-emission
  ;; A record satisfies `map?` but prints as a tag the reader cannot construct.
  (is (= {:rf.error/id          :rf.error/root-manifest-invalid
          :unserialisable-prop  :row
          :unserialisable-half  :value}
         (select-keys (invalid-data #(rf.ssr.manifest/manifest
                                       'test {:rf.root/schema-version 1 :root-id :page/shop}
                                       {:props {:promo :spring :row (->WireProbeRecord 1)}}))
                      [:rf.error/id :unserialisable-prop :unserialisable-half]))))

(deftest opaque-prop-KEY-fails-before-emission
  ;; Both halves of a prop entry ride the wire, so both are screened.
  (is (= {:rf.error/id :rf.error/root-manifest-invalid :unserialisable-half :key}
         (select-keys (invalid-data #(rf.ssr.manifest/manifest
                                       'test {:rf.root/schema-version 1 :root-id :page/shop}
                                       {:props {(fn [] 1) 1}}))
                      [:rf.error/id :unserialisable-half])))
  (is (not (rf.ssr.manifest/edn-carryable? {(->WireProbeRecord 1) :v}))
      "a record used as a map KEY is refused too"))

;; ---------------------------------------------------------------------------
;; The wire form
;; ---------------------------------------------------------------------------

(deftest wire-element-carries-a-bare-marker
  ;; Identity lives in the content, spelled once; the marker carries no value.
  (is (re-find #"^<script type=\"application/edn\" data-rf-root>.*</script>$"
               (rf.ssr.manifest/script-html
                 (rf.ssr.manifest/manifest {:rf.root/schema-version 1 :root-id :page/shop}
                                           {:element-locator   {:id "shop-root"}
                                            :identifier-prefix "rf2-page_Sshop-"})))))

(deftest wire-body-cannot-break-out-of-the-script
  (let [m (rf.ssr.manifest/manifest {:rf.root/schema-version 1 :root-id :page/shop}
                             {:props {:bio "</script><img onerror=x>"}})
        html (rf.ssr.manifest/script-html m)]
    (is (not (re-find #"(?i)</script" (subs html 0 (- (count html) 9))))
        "the shared EDN script-body escape neutralises the breakout")))

(deftest unreadable-or-foreign-body-fails-loud
  ;; A hydrating root never guesses.
  (is (= {:rf.error/id :rf.error/root-manifest-invalid :invalid :unreadable}
         (select-keys (invalid-data #(rf.ssr.manifest/read-manifest 'test "{:rf.root/schema-version 1"))
                      [:rf.error/id :invalid]))))

;; The property is `read(write(m)) = m` through the SHIPPED `script-html` /
;; `read-manifest`, so the script-body escape is part of what is proven.

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
  "One value per `edn-carryable?` clause, plus the escape's two contexts — a
  `<` inside a string and inside a keyword token — spelled with literals that
  mean the same thing on both hosts."
  {:nil            nil
   :false          false
   :empty-string   ""
   :unicode        "héllo — ☃"
   :breakout       "</script><img onerror=x>"
   :lt-in-keyword  :x<y
   :symbol         'sym
   :negative       -1
   :whole-double   9.0
   :list           '(1 2 3)
   :set            #{1 2 3}
   :mixed-keys     {:a 1 "b" 2 'c 3}
   :nested         {:a {:b [1 #{:c} {"d" :e}]}}})

(deftest every-emitted-manifest-round-trips-exactly
  (let [d {:rf.root/schema-version 1 :root-id :page/shop}]
    (is (round-trips? (rf.ssr.manifest/manifest d {:element-locator    {:id "shop-root"}
                                                    :props              {:promo :spring}
                                                    :frame-payload-ids  [:shop :frame/session]
                                                    :render-fingerprint "rf1-abc"
                                                    :identifier-prefix  "rf2-page_Sshop-"}))
        "a fully-extended manifest survives the wire exactly")
    (is (round-trips? (rf.ssr.manifest/manifest d))
        "…and so does the bare one, so `nil` vs ABSENT is covered in both directions")
    (doseq [[label v] wire-values]
      (is (round-trips? (rf.ssr.manifest/manifest d {:props {:v v}}))
          (str "prop value " label " => " (pr-str v))))
    (doseq [k ["str" 1 nil]]
      (is (round-trips? (rf.ssr.manifest/manifest d {:props {k :v}}))
          (str "prop key " (pr-str k))))))

;; ---------------------------------------------------------------------------
;; Numbers: each host reads back only what the OTHER host prints identically
;; ---------------------------------------------------------------------------

(deftest only-cross-host-numbers-ride-the-wire
  (testing "admitted — the integer bound is about representability, so a large
            double rides"
    (doseq [v [rf.ssr.manifest/max-safe-integer
               (- rf.ssr.manifest/max-safe-integer)
               1.5
               #?(:clj 1.0E308 :cljs 1e308)]]
      (is (rf.ssr.manifest/edn-carryable? v) (str "must still carry " (pr-str v))))
    (is (rf.ssr.manifest/edn-carryable? {:a [1 {:b #{2 3.5}}]})))

  (testing "refused"
    (is (not (rf.ssr.manifest/edn-carryable? #?(:clj Double/NaN :cljs js/NaN)))
        "NaN is not `=` to itself, so it cannot read back EQUAL on any host")
    #?(:clj
       (doseq [v [9007199254740993N
                  (inc rf.ssr.manifest/max-safe-integer)
                  (- (inc rf.ssr.manifest/max-safe-integer))
                  (float 0.1)]]
         (is (not (rf.ssr.manifest/edn-carryable? v)) (pr-str v))))))

(deftest infinities-ride-only-nan-is-excluded
  (is (round-trips? (rf.ssr.manifest/manifest {:rf.root/schema-version 1 :root-id :page/shop}
                                              {:props {:v ##Inf}}))))

;; ---------------------------------------------------------------------------
;; Cross-host numeric key / set collisions
;; ---------------------------------------------------------------------------
;;
;; On the JVM `1` and `1.0` (and `0` and `-0.0`) are distinct keys that each
;; pass `edn-carryable?`; the browser reads every number as one double, so the
;; emitted body reads back with a DUPLICATE key. `script-html` refuses it.

(def ^:private shop {:rf.root/schema-version 1 :root-id :page/shop})

#?(:clj
   (defn- collision [m]
     (some-> (invalid-data #(rf.ssr.manifest/script-html m))
             (select-keys [:rf.error/id :invalid :collision :path :collapses :browser-number])
             (update :collapses set))))

#?(:clj
   (deftest cross-host-numeric-collision-fails-before-emission
     (testing "map keys, naming the collection, its colliding keys and the one
               browser double they share"
       (is (= {:rf.error/id    :rf.error/root-manifest-invalid
               :invalid        :cross-host-numeric-collision
               :collision      :map-keys
               :path           [:props :lookup]
               :collapses      #{1 1.0}
               :browser-number 1.0}
              (collision (assoc shop :props {:lookup {1 :integer 1.0 :double}})))))
     (testing "set elements"
       (is (= {:collision :set-elements :path [:props :tags] :collapses #{1 1.0}}
              (select-keys (collision (assoc shop :props {:tags #{1 1.0}}))
                           [:collision :path :collapses]))))
     (testing "the browser holds ONE zero"
       (is (= 0.0 (:browser-number (collision (assoc shop :props {:lookup (into {} [[0 :a] [-0.0 :b]])}))))))
     (testing "a descriptor key is gated too: `script-html` is the one door onto the wire"
       (is (= [:static-props :m]
              (:path (collision (assoc shop :static-props {:m {1 :a 1.0 :b}})))))
       (is (= {:collision :set-elements :path [:props :xs 1]}
              (select-keys (collision (assoc shop :props {:xs [{:a 1} #{2 2.0}]}))
                           [:collision :path]))
           "the path indexes into a vector"))
     (testing "COMPOSITE keys: neither `[1]` nor `[1.0]` is a number, but the
               browser reads both as `[1.0]`"
       (is (= {:rf.error/id    :rf.error/root-manifest-invalid
               :invalid        :cross-host-numeric-collision
               :collision      :map-keys
               :path           [:props :lookup]
               :collapses      #{[1] [1.0]}
               :browser-number [1.0]}
              (collision (assoc shop :props {:lookup {[1] :i [1.0] :d}})))))))

(deftest a-mutation-that-resolves-the-collision-emits-fine
  ;; Each rejection above is the COLLISION, not the shape: move one number so
  ;; the two no longer share a browser double and the same shape round-trips.
  (doseq [[label props] {:map-mutated  {:lookup {1 :integer 2.0 :double}}
                         :set-mutated  {:tags #{1.0 2}}
                         :zero-mutated {:lookup {0 :a 1.0 :b}}
                         :vec-map-ok   {:lookup {[1] :integer-vector [2.0] :double-vector}}
                         :nested-ok    {:a [1 {:b #{2 3.5}}]}}]
    (is (round-trips? (assoc shop :props props)) (str label))))

#?(:clj
   (deftest non-carryable-number-fails-before-emission
     ;; The same `edn-carryable?` at assembly (naming the prop) and at the
     ;; emission gate (naming the manifest key).
     (is (= {:rf.error/id          :rf.error/root-manifest-invalid
             :unserialisable-prop  :ratio
             :unserialisable-half  :value}
            (select-keys (invalid-data #(rf.ssr.manifest/manifest
                                          'test shop {:props {:promo :spring :ratio 1/3}}))
                         [:rf.error/id :unserialisable-prop :unserialisable-half])))
     (is (= {:rf.error/id :rf.error/root-manifest-invalid :unserialisable-manifest-key :static-props}
            (select-keys (invalid-data #(rf.ssr.manifest/script-html
                                          (assoc shop :static-props {:limit 9007199254740993N})))
                         [:rf.error/id :unserialisable-manifest-key])))))

(deftest script-emission-gates-the-whole-manifest
  ;; `serialise-props` screens `:props` at assembly; descriptor keys reach the
  ;; wire only through `script-html`.
  (is (= {:rf.error/id :rf.error/root-manifest-invalid :unserialisable-manifest-key :static-props}
         (select-keys (invalid-data #(rf.ssr.manifest/script-html
                                       (assoc shop :static-props {:x (->WireProbeRecord 1)})))
                      [:rf.error/id :unserialisable-manifest-key]))))

(deftest body-must-hold-exactly-one-edn-form
  ;; `read-string` returns the FIRST form and drops the rest, so a truncated
  ;; render or an injected suffix would otherwise hydrate silently.
  (doseq [body ["{:rf.root/schema-version 1} trailing" ""]]
    (is (= {:rf.error/id :rf.error/root-manifest-invalid :invalid :form-count}
           (select-keys (invalid-data #(rf.ssr.manifest/read-manifest 'test body))
                        [:rf.error/id :invalid]))
        (pr-str body)))
  (is (= {:rf.root/schema-version 1}
         (rf.ssr.manifest/read-manifest 'test "\n\t{:rf.root/schema-version 1}\n"))
      "whitespace around the one form is fine"))

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
     ;; An AUTHORED `:identifier-prefix` passes through `discover` verbatim, so
     ;; this test stays about adjacency.
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

;; React 19.2's hydrateRoot canonicalizes an omitted `identifierPrefix` to "",
;; so a manifest that OMITS :identifier-prefix means the server rendered under
;; the empty prefix. Discovery resolves it to "".

(deftest canonicalize-effective-prefix-resolves-omitted-to-react-empty
  (is (= {:rf.root/schema-version 1 :root-id :page/shop :identifier-prefix ""}
         (rf.ssr.manifest/canonicalize-effective-prefix shop)))
  (is (= {:rf.root/schema-version 1 :root-id :page/shop :identifier-prefix "shop-"}
         (rf.ssr.manifest/canonicalize-effective-prefix (assoc shop :identifier-prefix "shop-")))
      "an AUTHORED prefix passes through untouched"))

#?(:cljs
   (deftest discover-stamps-react-empty-prefix-on-a-bare-manifest
     ;; A `discover` that returned the bare manifest verbatim would hand the
     ;; hydrating root a NIL prefix rather than React's effective empty one.
     (let [bare   (rf.ssr.manifest/manifest {:rf.root/schema-version 1 :root-id :page/a})
           script (stub-el {:attrs {"type" "application/edn"
                                    rf.ssr.constants/root-manifest-marker-attribute ""}
                            :text  (pr-str bare)})
           found  (rf.ssr.manifest/discover (stub-el {:tag "DIV" :next script}))]
       (testing "the bare manifest omits :identifier-prefix on the wire"
         (is (not (contains? bare :identifier-prefix))
             "manifest optionality is preserved — the extension key is absent"))
       (testing "discovery resolves it to React's effective empty prefix"
         (is (= (assoc bare :identifier-prefix "") found)
             "identity is the manifest plus the resolved effective prefix")))))
