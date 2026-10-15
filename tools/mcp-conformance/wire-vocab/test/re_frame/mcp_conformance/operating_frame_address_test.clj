(ns re-frame.mcp-conformance.operating-frame-address-test
  "Public frame-addressing envelope conformance.
  Fixtures validate the wire shape and pin its keys against production;
  they do not exercise runtime frame selection."
  (:require [clojure.edn    :as edn]
            [clojure.string :as str]
            [clojure.test   :refer [deftest is testing]]
            [malli.core     :as m]
            [malli.error    :as me]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]))

(def OperatingFrameSuccess
  "Public frame enumeration and session-selection slots.
  Open maps allow wire-pipeline decorations to compose."
  [:map
   {:closed false}
   [:ok?        [:= true]]
   [:frames     [:sequential :keyword]]
   [:app-frames [:sequential :keyword]]
   [:selected   [:maybe :keyword]]
   [:operating  [:maybe :keyword]]])

(def OperatingFrameFailure
  "Missing-target and unknown-frame refusals from set-operating-frame."
  [:map
   {:closed false}
   [:ok?    [:= false]]
   [:reason [:enum :no-such-frame :missing-frame]]])

(def ^:private success-fixtures
  {:multi-frame-ambiguous
   {:ok?       true
    :frames     [:rf/default :stories]
    :app-frames [:rf/default :stories]
    :selected   nil
    :operating  nil}

   :multi-frame-pinned
   {:ok?       true
    :frames     [:rf/default :stories]
    :app-frames [:rf/default :stories]
    :selected   :stories
    :operating  :stories}})

(def ^:private failure-fixtures
  {:no-such-frame
   {:ok?    false
    :reason :no-such-frame
    :frame  :nope
    :frames [:rf/default :stories]}

   :missing-frame
   {:ok?    false
    :reason :missing-frame}})

(deftest fixtures-conform-to-schema
  (doseq [[schema fixtures] [[OperatingFrameSuccess success-fixtures]
                             [OperatingFrameFailure failure-fixtures]]
          [fixture-name fixture-value] fixtures]
    (is (m/validate schema fixture-value)
        (str "Fixture " fixture-name " failed operating-frame validation:\n"
             (me/humanize (m/explain schema fixture-value))))))

;; ---------------------------------------------------------------------------
;; Source-text pins — each fixture family against the production source it
;; mirrors.
;; ---------------------------------------------------------------------------

(def ^:private set-operating-frame-descriptor-rel
  "Repo-relative path to the pair-mcp tool-descriptor data — the home of
  the set/reset/get-operating-frame descriptor maps (their inputSchema +
  example envelopes)."
  "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/descriptors_data.cljs")

(def ^:private frames-list-rel
  "Repo-relative path to the runtime preload whose `frames-list` builds the
  success envelope get-operating-frame and reset-operating-frame return."
  "skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs")

(defn- set-operating-frame-block
  "The set-operating-frame descriptor's source text (def-to-def)."
  []
  (-> (rf.mcp-conformance.fixtures/read-source set-operating-frame-descriptor-rel)
      (str/split #"\(def set-operating-frame")
      second
      (str/split #"\(def reset-operating-frame")
      first))

(defn- set-operating-frame-example-reply
  "The reply map of example `n` in set-operating-frame's description — the
  EDN after `->` on its `\"<n>. … -> {…}\"` line — or nil when no such
  example exists."
  [n]
  (some->> (set-operating-frame-block)
           (re-find (re-pattern (str "\"" n "\\. .*?-> (\\{[^}]*\\})")))
           second
           edn/read-string))

(deftest fixtures-mirror-their-production-source
  ;; The fixtures above are hand-written; this compares each family with the
  ;; ONE source it mirrors, so a production envelope that drops a slot the
  ;; fixture carries goes red here rather than leaving every schema test green.
  (testing "every success-fixture key is emitted as data by the runtime's frames-list"
    (let [text (rf.mcp-conformance.fixtures/source-form frames-list-rel "(defn frames-list")]
      (doseq [k (into (sorted-set) (mapcat keys) (vals success-fixtures))]
        (is (re-find (rf.mcp-conformance.fixtures/variant-regex (pr-str k)) text)
            (str "success-fixture key " (pr-str k) " is not emitted as data by "
                 "frames-list in " frames-list-rel)))))
  (testing "every failure-fixture key is in the set-operating-frame example it mirrors"
    (doseq [[fixture-name n] {:no-such-frame 2 :missing-frame 3}
            :let [example (set-operating-frame-example-reply n)]
            k (keys (failure-fixtures fixture-name))]
      (is (contains? example k)
          (str "failure fixture " fixture-name " carries " (pr-str k)
               " but set-operating-frame example " n " replies " (pr-str example))))))
