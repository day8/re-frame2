(ns re-frame.image-cljs-test
  "The image foundation (EP-0023, EP-0026): the glob grammar, the `rf/image`
  constructor and its normalized value, inline `:registrations`, and the pure
  `select-descriptors` selector over synthetic descriptors. The image value
  accepts only `:id`, `:select-ns` and `:registrations`; selection is by
  `:rf.provenance/ns`, never by the registration id's namespace."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.image :as rf.image]))

(defn- error-data [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (ex-data e))))

(deftest ns-matches?-glob-grammar
  ;; [pattern namespace expected]
  (let [rows [;; `*` is exactly one segment: never zero, never two
              ["docs.*.counter" "docs.foo.counter" true]
              ["docs.*.counter" "docs.counter" false]
              ["docs.*.counter" "docs.a.b.counter" false]
              ["docs.shared.widgets.*" "docs.shared.widgets" false]
              ;; `**` is zero or more segments, anchored at both ends
              ["docs.shared.**" "docs.shared" true]
              ["docs.shared.**" "docs.shared.widgets.forms.input" true]
              ["docs.shared.**" "docs" false]
              ["docs.shared.**" "docs.other" false]
              ["**" "" true]
              ["shop.**.http" "shop.http" true]
              ["shop.**.http" "shop.a.b.c.http" true]
              ["shop.**.http" "shop.cart.https" false]
              ;; a run of `**` collapses to one, so a pathological pattern
              ;; cannot backtrack exponentially
              ["shop.**.**.http" "shop.http" true]
              ["**.**.**.**.**.**.**.**.x" "a.b.c.d.e.f.g.h.i.j.k.l.m.n" false]
              ["**.**.**.**.**.**.**.**.x" "a.b.c.d.e.f.g.h.i.j.k.l.m.x" true]
              ;; whole-namespace, case-sensitive literals
              ["Docs.counter" "docs.counter" false]
              ["a.b.c.d.e" "a.b.c.d.e.f" false]
              ["*" "core.sub" false]
              ;; an intra-segment `*` is zero or more chars within one segment
              ["*-cljs-test" "mount-cljs-test" true]
              ["*-cljs-test" "-cljs-test" true]
              ["*-cljs-test" "mount-cljs-tests" false]
              ["foo*" "xfoo" false]
              ["docs.*-test" "docs.foo.bar-test" false]
              ["day8.re-frame2-xray.**.*-cljs-test" "day8.re-frame2-xray.mount-cljs-test" true]
              ["day8.re-frame2-xray.**.*-cljs-test" "day8.re-frame2-xray.panels.app-db-diff-cljs-test" true]
              ["day8.re-frame2-xray.**.*-cljs-test" "day8.re-frame2-xray.panels.app-db-diff" false]
              ;; regex metacharacters in a segment are literal
              ["a+*" "aaa" false]
              ["a(b)*" "a(b)c" true]]]
    (is (= [] (remove (fn [[p n expected]] (= expected (rf.image/ns-matches? p n))) rows))
        "rows whose match result is wrong")))

(deftest image-normalizes-the-spec
  (is (= [{:rf.image/id         :tool/img
           :rf.image/include-ns ["day8.re-frame2-xray.**"]
           :rf.image/exclude-ns ["day8.re-frame2-xray.**-cljs-test"]
           :rf.image/inline     []}
          ;; anonymous: no :rf.image/id
          {:rf.image/include-ns ["docs.counter.**"]
           :rf.image/exclude-ns []
           :rf.image/inline     []}
          {:rf.image/include-ns [] :rf.image/exclude-ns [] :rf.image/inline []}]
         (mapv rf.image/image
               [{:id :tool/img
                 :select-ns {:include ["day8.re-frame2-xray.**"]
                             :exclude ["day8.re-frame2-xray.**-cljs-test"]}}
                {:select-ns {:include ["docs.counter.**"]}}
                {}]))))

(deftest image-rejects-malformed-specs
  (is (= (repeat 5 :rf.error/invalid-image)
         (mapv #(:rf.error/id (error-data (fn [] (rf.image/image %))))
               [[:not :a :map]
                {:id :x :selectNS ["a"]}
                {:select-ns {:include ['docs.counter]}}
                {:select-ns {:include ["docs.counter"] :exclude ['docs.counter]}}
                {:registrations {:reg-bogus [[:x {}]]}}]))))

(deftest retired-ep0023-image-keys-fail-loud
  (is (= {:rf.error/id :rf.error/invalid-image :retired-key :include-ns :image :y}
         (select-keys (error-data #(rf.image/image {:id :y :include-ns ["a.b"]}))
                      [:rf.error/id :retired-key :image]))))

(deftest inline-registrations-lower-to-descriptors
  ;; the body goes under :impl, metadata under :metadata, with an inline source
  ;; coordinate and no :rf.provenance/ns, so a glob can never select it
  (let [body-inc (fn [_ _] {})
        body-val (fn [_ _] 0)]
    (is (= #{{:kind                 :event
              :id                   :counter/inc
              :impl                 body-inc
              :metadata             {:doc "Increment."}
              :rf.provenance/image  :test/small
              :rf.provenance/inline [:reg-event :counter/inc]}
             {:kind                 :sub
              :id                   :counter/value
              :impl                 body-val
              :metadata             {:doc "Value."}
              :rf.provenance/image  :test/small
              :rf.provenance/inline [:reg-sub :counter/value]}}
           (set (:rf.image/inline
                  (rf.image/image
                    {:id :test/small
                     :registrations
                     {:reg-event [[:counter/inc {:doc "Increment."} body-inc]]
                      :reg-sub   [[:counter/value {:doc "Value."} body-val]]}})))))))

(deftest malformed-inline-entries-fail-loud-naming-the-entry
  ;; an entry is [id body] or [id metadata-map body]; a map in the body slot of
  ;; a 2-tuple is the retired metadata-only form
  (let [f (fn [_ _] {})]
    (doseq [[section entry] [[:reg-fx [:my/fx {:doc "meta only"}]]
                             [:reg-event :not-a-tuple]
                             [:reg-event [:counter/inc]]
                             [:reg-event []]
                             [:reg-event [:counter/inc {} f :extra]]
                             [:reg-event [:counter/inc 42 f]]
                             [:reg-event [:counter/inc "doc" f]]]]
      (is (= {:rf.error/id :rf.error/invalid-image :image :my/image :section section :entry entry}
             (select-keys (error-data #(rf.image/image {:id :my/image
                                                        :registrations {section [entry]}}))
                          [:rf.error/id :image :section :entry]))
          (pr-str entry)))))

(deftest anonymous-image-inline-omits-image-coordinate
  (let [body (fn [_ _] {})]
    (is (= [{:kind :event :id :x :impl body :rf.provenance/inline [:reg-event :x]}]
           (:rf.image/inline (rf.image/image {:registrations {:reg-event [[:x {} body]]}}))))))

(defn- desc
  "A synthetic registered descriptor; only :rf.provenance/ns is read by selection."
  [provenance-ns kind id]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :impl             (fn [_ _] ::stub)})

;; :counter/inc is registered from two namespaces, so selection by id namespace
;; would be visible
(def ^:private synthetic-store
  [(desc "docs.quickstart.counter.v2"  :event :counter/inc)
   (desc "docs.quickstart.counter.v2"  :sub   :counter/value)
   (desc "docs.quickstart.counter.v3"  :event :counter/inc)
   (desc "docs.shared.widgets"         :view  :widgets/button)
   (desc "docs.shared.widgets.button"  :view  :button/root)
   (desc "shop.cart"                   :event :cart/add)
   (desc "shop.auth"                   :event :auth/set-user)])

(defn- select [spec]
  (rf.image/select-descriptors (rf.image/image (assoc spec :id :i)) synthetic-store))

(deftest select-by-provenance-ns-not-id-ns
  (is (= [["docs.quickstart.counter.v2" :counter/inc]
          ["docs.quickstart.counter.v2" :counter/value]]
         (mapv (juxt :rf.provenance/ns :id)
               (select {:select-ns {:include ["docs.quickstart.counter.v2"]}})))))

(deftest select-multiple-patterns-deduped-and-ordered
  (is (= ["docs.shared.widgets" "docs.shared.widgets.button"]
         (map :rf.provenance/ns
              (select {:select-ns {:include ["docs.shared.**" "docs.shared.widgets.button"]}})))
      "a descriptor matched by two patterns is included once")
  (is (= [:cart/add :auth/set-user]
         (map :id (select {:select-ns {:include ["shop.**"]}})))
      "selection keeps the store's order"))

(deftest select-zero-match-fails-loud
  (let [data (error-data #(rf.image/select-descriptors
                            (rf.image/image {:id :my/image :select-ns {:include ["nope.*"]}})
                            synthetic-store))]
    (is (= [{:rf.error/id :rf.error/image-zero-match :image :my/image :pattern "nope.*"} true]
           [(select-keys data [:rf.error/id :image :pattern])
            (boolean (some #{"shop.cart"} (:loaded-ns data)))])
        "the diagnostic names the image, the pattern and the loaded namespaces"))
  (is (= :rf.error/image-zero-match
         (:rf.error/id (error-data #(select {:select-ns {:include ["shop.**" "ghost.*"]}}))))
      "every pattern must match, not just one"))

(deftest select-includes-inline-descriptors-unconditionally
  (let [img (rf.image/image {:id :test/small
                             :registrations {:reg-event [[:counter/inc {} (fn [_ _] {})]]}})]
    (is (= (:rf.image/inline img) (rf.image/select-descriptors img synthetic-store))
        "selected with no :select-ns globs at all"))
  (is (= [:auth/set-user :extra/evt]
         (mapv :id (select {:select-ns     {:include ["shop.auth"]}
                            :registrations {:reg-event [[:extra/evt {} (fn [_ _] {})]]}})))
      "appended after the glob-selected descriptors"))

(deftest select-ignores-descriptors-without-provenance-ns
  (is (= synthetic-store
         (rf.image/select-descriptors
           (rf.image/image {:id :i :select-ns {:include ["**"]}})
           (conj synthetic-store {:kind :fx :id :standard/fx :impl (fn [_] nil)})))))

(deftest exclude-ns-is-not-zero-match-fail-loud
  (is (= ["docs.shared.widgets" "docs.shared.widgets.button"]
         (map :rf.provenance/ns
              (select {:select-ns {:include ["docs.shared.**"]
                                   :exclude ["does.not.exist.**" "*-cljs-test"]}})))))

(deftest exclude-ns-never-drops-inline-descriptors
  ;; inline descriptors are selected by image membership, not provenance
  (let [img (rf.image/image {:id            :combo
                             :select-ns     {:include ["shop.**"] :exclude ["**"]}
                             :registrations {:reg-event [[:inline/evt {} (fn [_ _] {})]]}})]
    (is (= (:rf.image/inline img) (rf.image/select-descriptors img synthetic-store)))))

(deftest exclude-by-ns-drops-the-matched-provenance-in-order
  (let [d (fn [ns id] {:rf.provenance/ns ns :kind :event :id id})]
    (is (= [(d "a.b" :x) (d "a.d" :z)]
           (rf.image/exclude-by-ns ["a.c"] [(d "a.b" :x) (d "a.c" :y) (d "a.d" :z)])))))
