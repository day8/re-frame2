(ns re-frame.ui.spread-safe-grammar-cljs-test
  "rf2-izep3 — the ONE validated author-space caller-key grammar for
  `ui/spread-safe`, exercised DIRECTLY (host-agnostic). `assert-safe-caller!`,
  `spread-safe-denied-key?`, and `caller-key-name` are pure `.cljc`, so the
  canonicalization + validation + deny law are proven IDENTICAL on the JVM and
  CLJS hosts: this suite runs under both `clojure -M:test` and the node
  `-cljs-test` build. The render-level integration (the deny reached through the
  actual host converters) lives in react-render-cljs-test / parity-corpus-jvm-
  test; the advanced-production reachability rides the -deny-elision-prod-test."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui.rules :as rules]))

(defn- deny-data [caller owned]
  (try (rules/assert-safe-caller! caller owned)
       nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e))))

(defn- denied? [caller owned]
  (= :rf.error/ui-tree-malformed (:rf.error/id (deny-data caller owned))))

(deftest caller-key-name-canonicalizes
  (testing "nameable keys canonicalize to (name k); namespace dropped"
    (is (= "ref" (rules/caller-key-name :ref)))
    (is (= "ref" (rules/caller-key-name :caller/ref)))
    (is (= "ref" (rules/caller-key-name "ref")))
    (is (= "ref" (rules/caller-key-name 'ref)))
    (is (= "on-change" (rules/caller-key-name :x/on-change))))
  (testing "non-nameable keys have no canonical name"
    (is (nil? (rules/caller-key-name nil)))
    (is (nil? (rules/caller-key-name false)))
    (is (nil? (rules/caller-key-name 5)))))

(deftest spread-safe-denied-key?-compares-canonical-names
  (testing "structural/controlled aliases are denied by canonical name"
    (doseq [k [:key :ref :value :checked
               :caller/ref "ref" 'ref :some/value "value" "checked" :ns/key]]
      (is (rules/spread-safe-denied-key? k #{})
          (str "denied by canonical name: " (pr-str k)))))
  (testing "owned-handler aliases are denied by canonical name"
    (doseq [k [:on-change "on-change" :x/on-change]]
      (is (rules/spread-safe-denied-key? k #{:on-change})
          (str "owned-handler alias denied: " (pr-str k)))))
  (testing "allowed + non-nameable keys are not 'denied' here"
    (doseq [k [:aria-label "data-x" :title :class :style nil false 5]]
      (is (not (rules/spread-safe-denied-key? k #{:on-change}))))))

(deftest assert-safe-caller!-rejects-alternate-spellings
  (testing "structural/controlled aliases (namespaced/string/symbol) are rejected"
    (doseq [k [:key :ref :value :checked
               :caller/ref "ref" 'ref :some/value "value" "checked" :ns/key 'checked]]
      (let [data (deny-data {k "x"} #{})]
        (is (= :rf.error/ui-tree-malformed (:rf.error/id data))
            (str "alias rejected: " (pr-str k)))
        (is (= 're-frame.ui/spread-safe (:where data)))
        (is (= k (:key data)) "the offending author key is carried through"))))
  (testing "owned-handler aliases are rejected against canonical owned names"
    (doseq [k [:on-change "on-change" :x/on-change]]
      (is (denied? {k [:e]} #{:on-change}) (str "owned alias rejected: " (pr-str k))))))

(deftest assert-safe-caller!-rejects-non-map-and-non-nameable
  (testing "a non-map caller (a pair sequence / vector / string) is rejected"
    (is (denied? [[:aria-label "x"]] #{}))
    (is (denied? '([:aria-label "x"]) #{}))
    (is (denied? "not-a-map" #{})))
  (testing "non-nameable keys are rejected"
    (doseq [k [nil false 5 3.0]]
      (is (denied? {k "x"} #{}) (str "non-nameable key rejected: " (pr-str k))))))

(deftest assert-safe-caller!-passes-allowed-callers
  (testing "exact allowed keys pass; the caller is returned unchanged"
    (is (= {:aria-label "n" :data-x "d" :title "t" :class "c" :style {:color "red"}}
           (rules/assert-safe-caller!
            {:aria-label "n" :data-x "d" :title "t" :class "c" :style {:color "red"}}
            #{:on-change}))))
  (testing "nil caller = the empty caller (returns nil, no throw)"
    (is (nil? (rules/assert-safe-caller! nil #{:on-change}))))
  (testing "an empty map passes"
    (is (= {} (rules/assert-safe-caller! {} #{:on-change}))))
  (testing "a caller may name an owned handler that is NOT among owned-handler-keys"
    (is (= {:on-click [:e]} (rules/assert-safe-caller! {:on-click [:e]} #{:on-change})))))

;; ---------------------------------------------------------------------------
;; rf2-xdvob — the deny + canonicalization now key on the EMITTED SLOT, not the
;; raw author name, so a spelling that lands in an owned/structural slot cannot
;; bypass the law, and every accepted key is normalized to the slot conversion
;; actually emits (host-identical, proven here on JVM + CLJS).
;; ---------------------------------------------------------------------------

(deftest caller-key-slot-is-the-emitted-slot
  (testing "handler spellings all resolve to the React handler slot"
    (is (= "onChange" (rules/caller-key-slot :on-change)))
    (is (= "onChange" (rules/caller-key-slot "on-change")))
    (is (= "onChange" (rules/caller-key-slot 'on-change)))
    (is (= "onChange" (rules/caller-key-slot :x/on-change)))
    (is (= "onChange" (rules/caller-key-slot "onChange")))
    (is (= "onChange" (rules/caller-key-slot :onChange)))
    (is (= "onChangeCapture" (rules/caller-key-slot "onChangeCapture")))
    (is (= "onChangeCapture" (rules/caller-key-slot :on-change-capture))))
  (testing "class / style / structural slots via the react-dom table"
    (is (= "className" (rules/caller-key-slot :class)))
    (is (= "className" (rules/caller-key-slot "class")))
    (is (= "className" (rules/caller-key-slot :ns/class)))
    (is (= "style" (rules/caller-key-slot :style)))
    (is (= "value" (rules/caller-key-slot :value)))
    (is (= "ref"   (rules/caller-key-slot :ref)))
    (is (= "aria-label" (rules/caller-key-slot :aria-label))))
  (testing "non-nameable keys have no slot"
    (is (nil? (rules/caller-key-slot nil)))
    (is (nil? (rules/caller-key-slot 5)))))

(deftest spread-safe-denied-key?-denies-the-whole-owned-handler-family
  ;; The adversarial slot-divergence case: owned :on-change emits React slot
  ;; onChange, so a caller spelling the SAME family — already-camel, capture,
  ;; kebab-capture, namespaced — is denied. Pre-fix the name-only compare denied
  ;; only the exact kebab, so `"onChange"`/`"onChangeCapture"` slipped in and
  ;; occupied the owned event slot.
  (testing "kebab, already-camel, capture, string, symbol, namespaced aliases denied"
    (doseq [k [:on-change "on-change" 'on-change :x/on-change
               :onChange "onChange" :onChangeCapture "onChangeCapture"
               :on-change-capture "on-change-capture"]]
      (is (rules/spread-safe-denied-key? k #{:on-change})
          (str "owned handler family denies: " (pr-str k)))))
  (testing "a DIFFERENT event family is not denied"
    (doseq [k [:on-focus "onFocus" :on-input "onInputCapture" :x/on-blur]]
      (is (not (rules/spread-safe-denied-key? k #{:on-change}))
          (str "unrelated event allowed: " (pr-str k))))))

(deftest assert-safe-caller!-denies-owned-handler-family-and-canonicalizes
  (testing "an already-camel / capture / namespaced owned-family key throws"
    (doseq [k [:onChange "onChange" :onChangeCapture "onChangeCapture"
               :on-change-capture :x/on-change]]
      (is (denied? {k [:hijack]} #{:on-change})
          (str "owned handler family rejected: " (pr-str k)))
      (is (= k (:key (deny-data {k [:hijack]} #{:on-change})))
          "the offending author key is carried through")))
  (testing "accepted keys are rewritten to their author-canonical keyword"
    (is (= {:class "c"}    (rules/assert-safe-caller! {:ns/class "c"} #{})))
    (is (= {:class "c"}    (rules/assert-safe-caller! {"class" "c"} #{})))
    (is (= {:class "c"}    (rules/assert-safe-caller! {'class "c"} #{})))
    (is (= {:disabled true} (rules/assert-safe-caller! {"disabled" true} #{})))
    (is (= {:aria-label "n" :data-x "d" :title "t"}
           (rules/assert-safe-caller! {:aria-label "n" "data-x" "d" 'title "t"} #{})))
    (is (= {:on-click [:e]} (rules/assert-safe-caller! {:x/on-click [:e]} #{:on-change}))
        "an accepted (non-owned) handler canonicalizes too"))
  (testing "nil / empty unchanged"
    (is (nil? (rules/assert-safe-caller! nil #{})))
    (is (= {} (rules/assert-safe-caller! {} #{})))))
