(ns re-frame.ep0026-retirements-cljs-test
  "EP-0026 §Image Keys / §Backwards Compatibility — the EP-0023 image source
  keys `:include-ns`, `:exclude-ns`, `:replace`, `:replace-standard` and
  `:rf.image/requires` are RETIRED and fail loud at `rf/image`
  (`:rf.error/invalid-image`) naming the retired key, so stale examples do not
  keep working by accident. The image value's and the sealed generation's
  exact shapes, which carry no capability slot, are pinned by `image-cljs-test`
  and `facade-frame-read-cljs-test`."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [re-frame.image :as rf.image]))

(deftest each-retired-image-key-fails-loud
  (is (= (for [k [:include-ns :exclude-ns :replace :replace-standard :rf.image/requires]]
           {:rf.error/id :rf.error/invalid-image :retired-key k})
         (for [[k v] [;; the spelling is now :select-ns :include / :exclude
                      [:include-ns ["docs.counter.v2"]]
                      [:exclude-ns ["docs.counter.dev.**"]]
                      ;; composition resolves by image order
                      [:replace {[:event :counter/inc] {:ns "docs.counter.v3"}}]
                      ;; standards are protected, with no public opt-in
                      [:replace-standard {[:interceptor :rf.interceptor/path] {:standard true}}]
                      ;; images declare no host capabilities
                      [:rf.image/requires #{:rf.capability/http}]]]
           (try (rf.image/image {:id :x k v}) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                  (select-keys (ex-data e) [:rf.error/id :retired-key])))))))
