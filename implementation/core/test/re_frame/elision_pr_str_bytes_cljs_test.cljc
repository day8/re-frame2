(ns re-frame.elision-pr-str-bytes-cljs-test
  "`re-frame.elision/pr-str-bytes` counts UTF-8 BYTES on both hosts. The figure
  is published (every `:rf.size/large-elided` marker's `:bytes`, Spec-Schemas
  §`:rf/elision-marker`) and read as the auto-detect threshold. The obvious CLJS
  spelling, `(count (pr-str v))`, counts UTF-16 code units, which agree with
  bytes only for ASCII — so an ASCII-only test cannot see the difference.

  The three fixtures share one code-unit length (40; 42 printed) and differ in
  bytes: ASCII 42, em-dash 122, astral 82. A code-unit count answers 42 for all
  three, and the astral fixture also separates code points from code units.

  Dual-runtime (`*_cljs_test.cljc`): the JVM and `:node-test` runs together hold
  both host arms to one ruler."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

;; The non-ASCII fixtures are built from explicit code points: a literal astral
;; character in source is a surrogate pair that an editor or re-encoding pass
;; can silently split into two lone halves.
;;
;;   U+2014  EM DASH            1 code unit  / 1 code point / 3 UTF-8 bytes
;;   U+1D11E MUSICAL SYMBOL G CLEF, the surrogate pair U+D834 U+DD1E:
;;                              2 code units / 1 code point / 4 UTF-8 bytes

(def ^:private em-dash (str (char 0x2014)))
(def ^:private g-clef (str (char 0xD834) (char 0xDD1E)))

(def ^:private ascii-40   (apply str (repeat 40 "x")))
(def ^:private em-dash-40 (apply str (repeat 40 em-dash)))
(def ^:private astral-20  (apply str (repeat 20 g-clef)))

(deftest pr-str-bytes-counts-utf8-bytes-not-code-units
  ;; 82 also proves the surrogate pairs reach the encoder intact: lone
  ;; surrogates encode as 3-byte replacement characters and would read 122.
  (is (= [42 122 82]
         (mapv rf.elision/pr-str-bytes [ascii-40 em-dash-40 astral-20]))
      "a code-unit count would read [42 42 42]"))

(defn- install-large!
  "Classify `large` paths on the default frame the way a `reg-event` returning
  `:large` alongside `:db` does."
  [large]
  (rf.frame/swap-runtime-db! :rf/default
    (fn [rt] (rf.elision/apply-classification-effects rt {:large (mapv vec large)}))))

(deftest walker-published-marker-carries-utf8-bytes
  (install-large! [[:user :bio]])
  (is (= 122 (get-in (rf.elision/elide-wire-value {:user {:bio em-dash-40}})
                     [:user :bio :rf.size/large-elided :bytes]))
      "the marker an off-box agent reads publishes bytes on both hosts"))

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- unschema'd-warnings [traces]
  (filterv #(= :rf.warning/large-value-unschema'd (:operation %)) @traces))

(deftest threshold-compares-utf8-bytes-on-both-hosts
  ;; 42 code units sit under a threshold of 50 while 122 bytes sit over it. The
  ;; threshold is advisory (Privacy.md): the value ships unchanged either way,
  ;; and the dev-only warning is its only observable.
  (rf.elision/clear-warning-cache!)
  (let [traces (collect-traces! ::over)
        out    (rf.elision/elide-wire-value {:user {:bio em-dash-40}}
                                            {:rf.egress/threshold-bytes 50})]
    (is (= {:user {:bio em-dash-40}} out))
    (when rf.interop/debug-enabled?
      (is (= [{:path [:user :bio] :bytes 122}]
             (mapv #(select-keys (:tags %) [:path :bytes]) (unschema'd-warnings traces)))
          "a code-unit count would measure 42 and stay silent"))
    (rf/unregister-listener! :trace ::over)))
