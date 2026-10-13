(ns re-frame.error-record-source-coord-cljs-test
  "The always-on error record's `:source-coord` names the implementation that
  ran. One `[kind id]` can have several live implementations — two namespaces
  each registering it, with each frame's images choosing one — so the
  coordinate comes from the descriptor the failing frame resolved, never from
  whichever namespace registered the id last (Spec 001 §Production elision
  contract, Policy B).

  A namespace's macro registration is modelled the way the `reg-*` macro
  expands it: `*pending-coords*` bound to the captured coord-map around the
  registration fn. Every assertion reads the always-on `:errors` stream, so the
  namespace runs in the production-gate lane too."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.events :as rf.events]
            [re-frame.image :as rf.image]
            [re-frame.interop :as rf.interop]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- coord [ns-sym line]
  {:ns ns-sym :file (str ns-sym ".cljc") :line line})

(defn- throwing-handler [ns-sym]
  (fn [_ _] (throw (ex-info (str "thrown by " ns-sym "'s handler") {}))))

(defn- reg-from!
  "Register `event-id` as namespace `ns-sym`'s macro registration at `line`."
  [ns-sym line event-id]
  (binding [rf.source-coords/*pending-coords* (coord ns-sym line)]
    (rf.events/reg-event event-id (throwing-handler ns-sym))))

(defn- handler-exception-record
  "Dispatch `event-id` into `frame-id`; return the always-on
  `:rf.error/handler-exception` record it fanned as `[message source-coord]`,
  the coord `::absent` when the record carries none."
  [frame-id event-id]
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! seen conj %))
    (try
      (try (rf/dispatch-sync [event-id] {:frame frame-id})
           (catch #?(:clj Throwable :cljs :default) _ nil))
      (finally (rf.error-emit/unregister-error-listener! ::recorder)))
    (let [recs (filterv #(= :rf.error/handler-exception (:error %)) @seen)]
      (is (= 1 (count recs)) (str frame-id " fanned one handler-exception record"))
      [(ex-message (:exception (first recs)))
       (get (first recs) :source-coord ::absent)])))

(defn- handler-exception-coord [frame-id event-id]
  (second (handler-exception-record frame-id event-id)))

(defn- image-of [image-id ns-str]
  (rf.image/image {:id image-id :select-ns {:include [ns-str]}}))

(deftest each-frame-names-the-namespace-its-image-selected
  (doseq [[event-id order] [[:coord/ab [['coord.probe-a 17] ['coord.probe-b 29]]]
                            [:coord/ba [['coord.probe-b 29] ['coord.probe-a 17]]]]]
    (doseq [[ns-sym line] order]
      (reg-from! ns-sym line event-id))
    (let [frame-a (keyword "coord" (str "frame-a-" (name event-id)))
          frame-b (keyword "coord" (str "frame-b-" (name event-id)))]
      (rf/make-frame {:id frame-a :images [(image-of :coord/img-a "coord.probe-a")]})
      (rf/make-frame {:id frame-b :images [(image-of :coord/img-b "coord.probe-b")]})
      (is (= [(coord 'coord.probe-a 17) (coord 'coord.probe-b 29)]
             [(handler-exception-coord frame-a event-id)
              (handler-exception-coord frame-b event-id)])
          (str "registration order " (mapv first order))))))

(deftest a-frame-frozen-by-a-move-names-the-implementation-it-still-runs
  ;; the move leaves the frame on its prior generation, running coord.move-a's
  ;; handler, while coord.move-b is the namespace that registered the id last
  (reg-from! 'coord.move-a 30 :coord/moved)
  (rf/make-frame {:id :coord/frozen})
  (reg-from! 'coord.move-b 40 :coord/moved)
  (is (= (coord 'coord.move-a 30) (handler-exception-coord :coord/frozen :coord/moved))))

(deftest a-replacement-registered-mid-handler-does-not-take-the-throw
  ;; the running implementation registers its replacement, then throws: the
  ;; record names the implementation that threw, and the next dispatch runs
  ;; (and names) the replacement
  (binding [rf.source-coords/*pending-coords* (coord 'coord.replaced 10)]
    (rf.events/reg-event :coord/replaced
      (fn [_ _]
        (reg-from! 'coord.replaced 20 :coord/replaced)
        (throw (ex-info "thrown by the original handler" {})))))
  (rf/make-frame {:id :coord/replaced-frame})
  (is (= ["thrown by the original handler" (coord 'coord.replaced 10)]
         (handler-exception-record :coord/replaced-frame :coord/replaced)))
  (is (= ["thrown by coord.replaced's handler" (coord 'coord.replaced 20)]
         (handler-exception-record :coord/replaced-frame :coord/replaced))))

(defn- cross-frame-sub-errors
  "Frames A and B select different images, and each image registers its own
  throwing implementation of `:coord/cross-sub`. Dispatch an event into A whose
  handler runs `read-b` while A's generation is in scope, and return the
  `:rf.error/sub-exception` records it fanned as `[frame message source-coord]`."
  [read-b]
  (doseq [[ns-sym line] [['coord.cross-a 60] ['coord.cross-b 70]]]
    (binding [rf.source-coords/*pending-coords* (coord ns-sym line)]
      (rf.subs/reg-sub :coord/cross-sub
        (fn [_ _] (throw (ex-info (str "thrown by " ns-sym "'s sub") {}))))))
  (binding [rf.source-coords/*pending-coords* (coord 'coord.cross-a 80)]
    (rf.events/reg-event :coord/cross-read (fn [_ _] (read-b) {})))
  (rf/make-frame {:id :coord/cross-a :images [(image-of :coord/img-cross-a "coord.cross-a")]})
  (rf/make-frame {:id :coord/cross-b :images [(image-of :coord/img-cross-b "coord.cross-b")]})
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! seen conj %))
    (try
      (try (rf/dispatch-sync [:coord/cross-read] {:frame :coord/cross-a})
           (catch #?(:clj Throwable :cljs :default) _ nil))
      (finally (rf.error-emit/unregister-error-listener! ::recorder)))
    (->> @seen
         (filter #(= :rf.error/sub-exception (:error %)))
         (mapv (juxt :frame (comp ex-message :exception) :source-coord)))))

(def ^:private b-threw
  [[:coord/cross-b "thrown by coord.cross-b's sub" (coord 'coord.cross-b 70)]])

(deftest an-error-for-another-frame-names-that-frames-implementation
  ;; the subscribe opts name frame B
  (is (= b-threw
         (cross-frame-sub-errors
           #(deref (rf/subscribe [:coord/cross-sub] {:frame :coord/cross-b}))))))

(deftest an-error-inside-a-with-frame-scope-names-that-frames-implementation
  ;; `with-frame` names frame B but leaves A's generation bound, so the frame
  ;; in scope says nothing about whose generation that is
  (is (= b-threw
         (cross-frame-sub-errors
           #(rf/with-frame :coord/cross-b @(rf/subscribe [:coord/cross-sub]))))))

(deftest a-programmatic-re-registration-reports-no-stale-coordinate
  ;; the re-registration stamps the same :ns, so it replaces the macro
  ;; registration's source slot rather than colliding with it
  (reg-from! 'coord.prog 50 :coord/prog)
  (rf/make-frame {:id :coord/prog-frame})
  (rf.events/reg-event :coord/prog {:ns 'coord.prog} (throwing-handler 'coord.prog))
  (is (= ::absent (handler-exception-coord :coord/prog-frame :coord/prog))))

(deftest a-macro-registration-reports-its-call-site
  ;; the real reg-* macro: {:ns :file :line} in every posture, :column in dev only
  (rf/reg-event :coord/macro (throwing-handler 'macro))
  (rf/make-frame {:id :coord/macro-frame})
  (let [sc (handler-exception-coord :coord/macro-frame :coord/macro)]
    (is (= ['re-frame.error-record-source-coord-cljs-test
            (if rf.interop/debug-enabled? #{:ns :file :line :column} #{:ns :file :line})]
           [(:ns sc) (set (keys sc))]))
    (is (pos-int? (:line sc)))))
