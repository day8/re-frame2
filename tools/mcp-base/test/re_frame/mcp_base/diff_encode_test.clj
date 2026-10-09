(ns re-frame.mcp-base.diff-encode-test
  "Tests for the path-keyed structural diff both MCP servers apply to an
  epoch's :db-after at the wire boundary."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is]]
            [malli.core :as m]
            [re-frame.mcp-base.diff-encode :as rf.mcp-base.diff-encode]))

(defn- collect [a b]
  (rf.mcp-base.diff-encode/collect-patches a b []))

(defn- caught
  "The ExceptionInfo `f` throws, or nil."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(defn- secret-absent?
  "True when `secret` appears nowhere in the thrown ExceptionInfo's message,
  the pr-str of its ex-data, or the pr-str of any ex-data value."
  [^clojure.lang.ExceptionInfo e secret]
  (let [data (ex-data e)]
    (and (not (str/includes? (str (.getMessage e)) secret))
         (not (str/includes? (pr-str data) secret))
         (every? (fn [[_ v]] (not (str/includes? (pr-str v) secret))) data))))

;; ---------------------------------------------------------------------------
;; collect-patches
;; ---------------------------------------------------------------------------

(deftest collect-patches-emits-minimal-path-patches
  (are [a b patches] (= patches (collect a b))
    {:a 1}                        {:a 1}                        []
    {:a 1}                        {:a 1 :b 2}                   [[[:b] :assoc 2]]
    {:a 1 :b 2}                   {:a 1}                        [[[:b] :dissoc]]
    {:a 1}                        {:a 3}                        [[[:a] :assoc 3]]
    {:user {:name "ada" :age 30}} {:user {:name "ada" :age 31}} [[[:user :age] :assoc 31]]
    {:a 1}                        [1 2 3]                       [[[] :assoc [1 2 3]]]
    {:a {:b 1}}                   {:a [1 2 3]}                  [[[:a] :assoc [1 2 3]]]
    ;; Same-length vectors diff element by element, with index paths; a
    ;; length change replaces the whole vector.
    {:items [{:qty 1}]}           {:items [{:qty 2}]}           [[[:items 0 :qty] :assoc 2]]
    {:xs [1 2 3]}                 {:xs [10 2 30]}               [[[:xs 0] :assoc 10] [[:xs 2] :assoc 30]]
    {:grid [[1 2] [3 4]]}         {:grid [[1 2] [9 4]]}         [[[:grid 1 0] :assoc 9]]
    {:xs [1 nil 3]}               {:xs [1 5 3]}                 [[[:xs 1] :assoc 5]]
    {:xs [1 2 3]}                 {:xs [1 nil 3]}               [[[:xs 1] :assoc nil]]
    {:xs [1 2 3]}                 {:xs [1 2 3 4]}               [[[:xs] :assoc [1 2 3 4]]]))

(deftest collect-patches-detects-keys-by-presence-not-value
  ;; Presence is read with `find`, never a sentinel lookup, so a value
  ;; equal to the private sentinel keyword, or a stored nil, is still an
  ;; ordinary value.
  (let [sentinel :re-frame.mcp-base.diff-encode/absent]
    (are [a b patches] (= patches (collect a b))
      {:k sentinel :sibling 1} {:k sentinel :sibling 2} [[[:sibling] :assoc 2]]
      {:k nil :sibling 1}      {:k nil :sibling 2}      [[[:sibling] :assoc 2]]
      {}                       {:k sentinel}            [[[:k] :assoc sentinel]]
      {:k 1}                   {:k sentinel}            [[[:k] :assoc sentinel]])))

;; ---------------------------------------------------------------------------
;; Collection KIND is a change. `(= [1 2] '(1 2))`, so a bare-`=` no-change
;; test would ship no patch for a vector turned into a seq of the same
;; items, and the decoder would rebuild the old kind. `=` erases kind in
;; assertions too, so these compare printed forms or check the kind.
;; ---------------------------------------------------------------------------

(deftest diff-encode-reports-a-vector-turned-seq-with-equal-elements
  ;; `sort-by` over an already-sorted vector hands back a seq of the same items.
  (let [before {:items [{:id 1} {:id 2}] :n 1}
        after  (update before :items #(sort-by :id %))
        dec    (:db-after (rf.mcp-base.diff-encode/decode-db-after
                            (rf.mcp-base.diff-encode/diff-encode-db-after {:db-before before :db-after after})))]
    (is (= (pr-str after) (pr-str dec)))))

(deftest collect-patches-lands-a-nested-kind-change-on-its-own-slot
  (let [patches (collect {:rows [{:tags [:a :b]} {:tags [:c]}] :n 1}
                         {:rows [{:tags (list :a :b)} {:tags [:c]}] :n 1})]
    (is (= [[:rows 0 :tags]] (mapv first patches)) "not the whole of :rows")
    (is (list? (nth (first patches) 2)))))

(deftest diff-encode-reports-a-map-key-respelled-in-another-kind
  (let [before {:by-pair {[1 2] :x} :n 1}
        after  {:by-pair {(list 1 2) :x} :n 1}
        dec    (:db-after (rf.mcp-base.diff-encode/decode-db-after
                            (rf.mcp-base.diff-encode/diff-encode-db-after {:db-before before :db-after after})))]
    (is (= (pr-str after) (pr-str dec)))))

;; ---------------------------------------------------------------------------
;; apply-patches
;; ---------------------------------------------------------------------------

(deftest apply-patches-reverses-collect-patches
  (are [a b] (= b (rf.mcp-base.diff-encode/apply-patches a (collect a b)))
    {:a 1}                                       {:a 1}
    {:user {:name "ada" :age 30} :session :idle} {:user {:name "ada" :age 31 :role :admin}}
    {:a 1}                                       [1 2 3]
    {:items [{:qty 1} {:qty 5}]}                 {:items [{:qty 2} {:qty 5}]}
    {:xs [1 2 3]}                                {:xs [1 2 3 4]}
    [1 2 3]                                      [1 9 3]))

(deftest apply-patches-applies-in-order-for-same-path
  ;; Patches replay in order, so the later of two at one path wins.
  (are [patches expected] (= expected (rf.mcp-base.diff-encode/apply-patches {} patches))
    [[[:a] :assoc 1] [[:a] :assoc 99]] {:a 99}
    [[[:a] :assoc 1] [[:a] :dissoc]]   {}))

(deftest apply-patches-nested-dissoc-missing-or-scalar-parent-is-noop
  ;; `:dissoc` of an absent key is a no-op: a missing parent is not
  ;; manufactured as a nil branch, and a scalar parent does not throw.
  (are [base path expected] (= expected (rf.mcp-base.diff-encode/apply-patches base [[path :dissoc]]))
    {}               [:missing :leaf] {}
    {:a 1}           [:a :b]          {:a 1}
    {:a {:b 1 :c 2}} [:a :b]          {:a {:c 2}}))

(deftest apply-patches-nested-assoc-into-scalar-parent-is-structured-error
  ;; An `:assoc` through a node that cannot hold the next segment is a
  ;; base/patch mismatch, reported as :rf.error/bad-diff-replay rather than
  ;; a raw host exception, a silent no-op or a clobber. A missing parent is
  ;; created instead: a map for a key segment, a vector for index 0.
  (let [d (ex-data (caught #(rf.mcp-base.diff-encode/apply-patches {:a 1} [[[:a :b] :assoc 2]])))]
    (is (= {:rf.error/id :rf.error/bad-diff-replay
            :where       'mcp-base/apply-patches
            :recovery    :no-recovery
            :patch-path  [:a :b]
            :at          [:a]}
           (select-keys d [:rf.error/id :where :recovery :patch-path :at])))
    (is (some? (:parent-type d)) "a value-free type tag, never the parent's value"))
  (are [base path] (thrown-with-msg? clojure.lang.ExceptionInfo #":rf\.error/bad-diff-replay"
                                     (rf.mcp-base.diff-encode/apply-patches base [[path :assoc 9]]))
    {:a [1 2]}     [:a :b]          ; a vector reached by a non-integer key
    {:items [1 2]} [:items 5]       ; an index past the end of a vector
    {}             [:items 5 :qty]) ; a non-zero index into a vector it would create
  (are [base path expected] (= expected (rf.mcp-base.diff-encode/apply-patches base [[path :assoc 2]]))
    {}            [:a :b]         {:a {:b 2}}
    {}            [:items 0 :qty] {:items [{:qty 2}]}
    {:items [10]} [:items 1]      {:items [10 2]}))

(deftest decode-db-after-nested-assoc-mismatched-base-is-structured-error
  ;; The same guard, reached through the section decoder, names its own
  ;; boundary.
  (let [epoch {:db-before {:a 1}
               :db-after  {:rf.mcp/diff-from :db-before
                           :sections [{:section-path [:a]
                                       :section-kind :modified
                                       :patches      [[[:a :b] :assoc 2]]}]}}]
    (is (= {:rf.error/id :rf.error/bad-diff-replay :where 'mcp-base/decode-db-after}
           (select-keys (ex-data (caught #(rf.mcp-base.diff-encode/decode-db-after epoch)))
                        [:rf.error/id :where])))))

;; ---------------------------------------------------------------------------
;; diff-encode-db-after / decode-db-after / diff-encode-epochs
;; ---------------------------------------------------------------------------

(deftest diff-encode-db-after-emits-sections-shape
  (is (= {:db-before {:a 1 :b 2}
          :db-after  {:rf.mcp/diff-from :db-before
                      :sections [{:section-path [:b] :section-kind :modified :patches [[[:b] :assoc 3]]}]}}
         (rf.mcp-base.diff-encode/diff-encode-db-after {:db-before {:a 1 :b 2} :db-after {:a 1 :b 3}}))))

(deftest diff-encode-then-decode-restores-original
  (let [epoch {:db-before {:user {:name "ada" :age 30} :session :idle}
               :db-after  {:user {:name "ada" :age 31 :role :admin}}
               :event     [:user/birthday]}]
    (is (= epoch (rf.mcp-base.diff-encode/decode-db-after (rf.mcp-base.diff-encode/diff-encode-db-after epoch))))))

(deftest diff-encode-db-after-passes-through-when-missing-halves
  (are [epoch] (= epoch (rf.mcp-base.diff-encode/diff-encode-db-after epoch))
    {:db-after {:x 1}}
    {:db-before {:x 1}}
    [1 2 3]))

(deftest decode-db-after-passes-through-when-not-a-diff
  (let [epoch {:db-before {:a 1} :db-after {:a 1 :b 2}}]
    (is (= epoch (rf.mcp-base.diff-encode/decode-db-after epoch)))))

(deftest decode-db-after-explicit-empty-sections-is-valid-no-change
  ;; An unchanged epoch encodes to an explicit empty section vector, which
  ;; decodes back to :db-before.
  (let [epoch   {:db-before {:a 1} :db-after {:a 1}}
        encoded (rf.mcp-base.diff-encode/diff-encode-db-after epoch)]
    (is (= {:db-before {:a 1} :db-after {:rf.mcp/diff-from :db-before :sections []}} encoded))
    (is (= epoch (rf.mcp-base.diff-encode/decode-db-after encoded)))))

(deftest diff-encode-epochs-diff-mode-encodes-each-record
  (let [out (rf.mcp-base.diff-encode/diff-encode-epochs
              [{:db-before {:a 1} :db-after {:a 2}} {:db-before {:b 1} :db-after {:b 2}}] :diff)]
    (is (= [:db-before :db-before] (mapv #(get-in % [:db-after :rf.mcp/diff-from]) out)))))

(deftest diff-encode-epochs-full-mode-is-passthrough
  (let [epochs [{:db-before {:a 1} :db-after {:a 2}}]]
    (is (= epochs (rf.mcp-base.diff-encode/diff-encode-epochs epochs :full)))))

;; ---------------------------------------------------------------------------
;; The patch grammar and the decoder's validation gates. A patch value can
;; be an app-db leaf the egress policy keeps projected, so every
;; diagnostic is value-free.
;; ---------------------------------------------------------------------------

(deftest patch-schema-pins-the-tuple-grammar
  ;; `[path :assoc value]` or `[path :dissoc]`, the path a vector.
  (are [patch valid?] (= valid? (m/validate rf.mcp-base.diff-encode/patch-schema patch))
    [[:a] :assoc 1]              true
    [[] :assoc {:whole :db}]     true
    [[:a :b :c] :dissoc]         true
    [[:a] :replace 1]            false
    [[:a] :assoc]                false
    [[:a] :dissoc :extra]        false
    ['(:a) :assoc 1]             false
    {:path [:a] :op :assoc :v 1} false))

(deftest apply-patches-rejects-malformed-tuples
  ;; Without the gate a tuple with no op would fall through the replay and
  ;; vanish silently.
  (let [secret     "s3cr3t-token-DO-NOT-LEAK"
        leaky      [[[:user :api-key] :dissoc secret]
                    [[:session] :replace {:password secret :token secret}]]
        rejection #(caught (fn [] (rf.mcp-base.diff-encode/apply-patches {} [%])))]
    (are [patch] (= {:rf.error/id :rf.error/bad-diff-patches :where 'mcp-base/apply-patches}
                    (select-keys (ex-data (rejection patch)) [:rf.error/id :where]))
      [[:a]]
      (first leaky)
      (second leaky))
    (is (every? #(secret-absent? (rejection %) secret) leaky))))

(deftest decode-db-after-rejects-malformed-sections
  ;; Symmetric with the encoder: :sections is validated before replay. A
  ;; present but nil slot throws rather than reading as an empty diff that
  ;; would silently erase the epoch's change.
  (let [secret    "section-secret-XYZ-LEAK"
        leaky     [{:section-path :not-a-vector
                    :section-kind :modified
                    :patches      [[[:creds :password] :assoc secret]]}]
        rejection #(caught (fn [] (rf.mcp-base.diff-encode/decode-db-after
                                    {:db-before {:a 1}
                                     :db-after  {:rf.mcp/diff-from :db-before :sections %}})))]
    (are [sections] (= {:rf.error/id :rf.error/bad-diff-sections :where 'mcp-base/decode-db-after}
                       (select-keys (ex-data (rejection sections)) [:rf.error/id :where]))
      nil
      [{:section-path [:a] :section-kind :renamed :patches [[[:a] :assoc 2]]}]
      [{:section-path [:a] :section-kind :modified}]
      leaky)
    (is (secret-absent? (rejection leaky) secret))))

(deftest decode-db-after-rejects-malformed-marker-body
  ;; The marker body is closed: exactly {:rf.mcp/diff-from :db-before
  ;; :sections [...]}. An extra key, or a marker value other than
  ;; :db-before, throws rather than passing through.
  (let [leaky     {:rf.mcp/diff-from :db-before :sections [] :secret "sensitive-payload"}
        rejection #(caught (fn [] (rf.mcp-base.diff-encode/decode-db-after {:db-before {:a 1} :db-after %})))]
    (are [db-after] (= {:rf.error/id :rf.error/bad-diff-marker :where 'mcp-base/decode-db-after}
                       (select-keys (ex-data (rejection db-after)) [:rf.error/id :where]))
      leaky
      {:rf.mcp/diff-from :db-later :sections []})
    (is (secret-absent? (rejection leaky) "sensitive-payload"))))
