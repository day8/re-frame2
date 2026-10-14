(ns re-frame.event-frame-isolation-conformance-cljs-test
  "Conformance for live dispatch through image-loaded frames.

  Frame-targeted read tests can pass even if live dispatch incorrectly falls
  back to the default registrar. This suite therefore uses public
  `rf/dispatch-sync`, same-id global sentinels, and committed state to prove
  that handler resolution and effects stay inside the target frame: same-id
  runtime-db partition isolation with an untouched sibling. Absence-is-default,
  a child `:fx` dispatch retaining the frame's image generation, and two frames
  sharing one image belong to `re-frame.live-run-frame-resolution-cljs-test`.
  `:ambient-frame nil` keeps every target explicit so ambient resolution cannot
  conceal a routing error."
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

;; Image resolution stores the registration descriptor plus its selection keys.
(defn- event-desc
  [provenance-ns id handler-fn]
  (merge (rf.events/event-handler-meta handler-fn)
         {:rf.provenance/ns provenance-ns
          :kind             :event
          :id               id}))

(defn- make-image-frame!
  "Make frame `id` whose one image selects the `provenance-ns` registrations."
  [id provenance-ns registrations]
  (rf.live-frame/make-frame {:id id :images [(rf.image/image {:id id :select-ns {:include [provenance-ns]}})]}
                            registrations))

(defn- runtime-writer
  "A framework-authority handler recording the runtime-db seed it observed and
  its own writer marker."
  [writer]
  (fn [{runtime-db :rf.db/runtime} _]
    {:rf.db/runtime (assoc runtime-db
                           :rf.runtime/conf-observed (:rf.runtime/conf-seed runtime-db)
                           :rf.runtime/conf-writer   writer)}))

;; App-db isolation is `re-frame.live-run-frame-resolution-cljs-test`'s; a
;; regression routing app-db correctly while reading or committing runtime-db
;; through another frame would leave those tests green. Every handler is
;; framework-authority, so a fallback takes the real runtime-effect path.
(deftest two-image-frames-same-id-isolate-the-runtime-db-partition
  (rf/reg-event :boot/rt-init {:rf/framework-authority? true} (runtime-writer :global))
  (doseq [writer [:todo :counter :sibling]
          :let [frame-id (keyword "conf.rt" (name writer))
                ns-name  (str "conf.rt." (name writer))]]
    (make-image-frame! frame-id ns-name
                       [(assoc (event-desc ns-name :boot/rt-init (runtime-writer writer))
                               :rf/framework-authority? true)])
    (rf.frame/replace-runtime-db! frame-id {:rf.runtime/conf-seed (keyword (str (name writer) "-seed"))}))
  ;; The sibling carries its own same-id handler but is never dispatched.
  (rf/dispatch-sync [:boot/rt-init] {:frame :conf.rt/todo})
  (rf/dispatch-sync [:boot/rt-init] {:frame :conf.rt/counter})
  (is (= [{:rf.runtime/conf-seed :todo-seed :rf.runtime/conf-observed :todo-seed :rf.runtime/conf-writer :todo}
          {:rf.runtime/conf-seed :counter-seed :rf.runtime/conf-observed :counter-seed :rf.runtime/conf-writer :counter}
          {:rf.runtime/conf-seed :sibling-seed}]
         (map rf.frame/frame-runtime-db-value [:conf.rt/todo :conf.rt/counter :conf.rt/sibling]))
      "each handler read and wrote only its own frame's runtime-db; the sibling is untouched"))
