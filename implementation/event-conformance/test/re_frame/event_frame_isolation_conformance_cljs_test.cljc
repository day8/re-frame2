(ns re-frame.event-frame-isolation-conformance-cljs-test
  "Explicit image-targeted dispatch must isolate runtime-db reads and writes.
  Same-id global and sibling handlers expose fallback to another registrar."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core           :as rf]
            [re-frame.events         :as rf.events]
            [re-frame.frame          :as rf.frame]
            [re-frame.image          :as rf.image]
            [re-frame.live-frame     :as rf.live-frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support   :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter       rf.substrate.plain-atom/adapter
                                            :ambient-frame nil}))

(defn- event-desc
  [provenance-ns id handler-fn]
  (merge (rf.events/event-handler-meta handler-fn)
         {:rf.provenance/ns provenance-ns
          :kind             :event
          :id               id}))

(defn- make-image-frame!
  [id provenance-ns registrations]
  (rf.live-frame/make-frame {:id id :images [(rf.image/image {:id id :select-ns {:include [provenance-ns]}})]}
                            registrations))

(defn- runtime-writer
  [writer]
  (fn [{runtime-db :rf.db/runtime} _]
    {:rf.db/runtime (assoc runtime-db
                           :rf.runtime/conf-observed (:rf.runtime/conf-seed runtime-db)
                           :rf.runtime/conf-writer   writer)}))

;; App-db isolation cannot catch cross-frame runtime-db reads or commits.
(deftest two-image-frames-same-id-isolate-the-runtime-db-partition
  (rf/reg-event :boot/rt-init {:rf/framework-authority? true} (runtime-writer :global))
  (doseq [writer [:todo :counter :sibling]
          :let [frame-id (keyword "conf.rt" (name writer))
                ns-name  (str "conf.rt." (name writer))]]
    (make-image-frame! frame-id ns-name
                       [(assoc (event-desc ns-name :boot/rt-init (runtime-writer writer))
                               :rf/framework-authority? true)])
    (rf.frame/replace-runtime-db! frame-id {:rf.runtime/conf-seed (keyword (str (name writer) "-seed"))}))
  (rf/dispatch-sync [:boot/rt-init] {:frame :conf.rt/todo})
  (rf/dispatch-sync [:boot/rt-init] {:frame :conf.rt/counter})
  (is (= [{:rf.runtime/conf-seed :todo-seed :rf.runtime/conf-observed :todo-seed :rf.runtime/conf-writer :todo}
          {:rf.runtime/conf-seed :counter-seed :rf.runtime/conf-observed :counter-seed :rf.runtime/conf-writer :counter}
          {:rf.runtime/conf-seed :sibling-seed}]
         (map rf.frame/frame-runtime-db-value [:conf.rt/todo :conf.rt/counter :conf.rt/sibling]))
      "each handler read and wrote only its own frame's runtime-db; the sibling is untouched"))
