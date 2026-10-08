(ns re-frame.image-framework-base-cljs-test
  "The framework base beneath an explicit `:images` composition, over
  synthetic descriptor pools. The framework's feature handlers register with no
  `:rf.provenance/ns`, so no `:select-ns` can reach them; assembly layers every
  framework-owned registration (nil provenance, id under the reserved `:rf`
  root, plus the `:route/link` view) beneath the app images as the pseudo-image
  `:rf/framework`, minus the protected standards.

  The inline rows need the event and fx lowering publishers, so their
  namespaces are required. The producer-derived half, over the real feature
  registrations, is `re-frame.image-framework-base-features-cljs-test`."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.image          :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.events]
            [re-frame.fx]))

;; put the load-time standards back so later namespaces still see them
(use-fixtures :each
  (fn [t]
    (let [standards @rf.image-assembly/standard-registry]
      (rf.image-assembly/clear-standards!)
      (rf.image-assembly/clear-generation-cache!)
      (try
        (t)
        (finally
          (rf.image-assembly/clear-standards!)
          (doseq [[[kind id] d] standards]
            (rf.image-assembly/register-standard! kind id d))
          (rf.image-assembly/clear-generation-cache!))))))

(defn- fw-desc
  "A descriptor as the framework's fn-alias registration path records it: NO
  `:rf.provenance/ns`."
  ([kind id impl] (fw-desc kind id impl nil))
  ([kind id impl extra]
   (merge {:kind kind :id id :handler-fn impl} extra)))

(defn- reg-desc
  "A descriptor authored in `provenance-ns` (the public `reg-*` macro path)."
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns :kind kind :id id :handler-fn impl})

(defn- err-id
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- handler-of [gen kind id]
  (:handler-fn (rf.image-assembly/resolve-descriptor gen kind id)))

(def ^:private app-image
  (rf.image/image {:id :app/main :select-ns {:include ["app.**"]}}))

(def ^:private pool
  [(reg-desc "app.core" :event :app/boot ::app-boot)
   ;; framework-owned: nil provenance, id under the reserved :rf root
   (fw-desc :event :rf.route/navigate ::navigate)
   (fw-desc :cofx  :rf/time-ms        ::time-ms)
   ;; the one framework id outside the root — as a :view only
   (fw-desc :view  :route/link        ::route-link)
   ;; NOT framework-owned
   (fw-desc :event :app/programmatic  ::app-programmatic)
   (fw-desc :fx    :rfx/effect        ::rfx)
   (fw-desc :fx    :rf2.tools/effect  ::rf2-tools)
   (fw-desc :event :route/link        ::event-route-link)
   (reg-desc "other.lib" :fx :rf.lib/provenanced ::provenanced)])

(deftest explicit-composition-resolves-the-framework-base
  (let [gen (rf.image-assembly/assemble [app-image] pool)
        resolve-all (fn [rows] (mapv (fn [[kind id]] (handler-of gen kind id)) rows))]
    (is (= [::navigate ::time-ms ::route-link ::app-boot]
           (resolve-all [[:event :rf.route/navigate] [:cofx :rf/time-ms]
                         [:view :route/link] [:event :app/boot]]))
        ":rf.<x>/* and :rf/* ids, the :route/link view, and the app's own selection")
    ;; an app's nil-provenance id outside the root, the :rfx and :rf2.* prefix
    ;; traps, :route/link as an event, and a provenanced reserved-root id
    (is (= [nil nil nil nil nil]
           (resolve-all [[:event :app/programmatic] [:fx :rfx/effect]
                         [:fx :rf2.tools/effect] [:event :route/link]
                         [:fx :rf.lib/provenanced]])))
    (is (= [[app-image] []] [(:rf.gen/images gen) (:rf.gen/shadows gen)])
        "the base is not an image, and nothing was shadowed")))

(deftest the-default-image-is-unchanged
  (let [gen (rf.image-assembly/assemble-default pool)]
    (is (= [::navigate ::app-programmatic []]
           [(handler-of gen :event :rf.route/navigate)
            (handler-of gen :event :app/programmatic)
            (:rf.gen/shadows gen)])
        "the whole pool, with no :rf/framework layer to shadow")))

(def ^:private stub-push (fn [_ctx _url] :stubbed))

(deftest a-provenanced-app-override-wins-over-the-framework-default
  (let [pool      (conj pool
                        (fw-desc :event :rf.route/entry-denied ::fw-denied
                                 {:rf/framework-default? true}))
        bare      (rf.image-assembly/assemble [app-image] pool)
        pool      (conj pool (reg-desc "app.auth" :event :rf.route/entry-denied ::app-denied))
        doubles   (rf.image/image {:id :test/doubles
                                   :registrations {:reg-event [[:rf.route/entry-denied
                                                                (fn [{:keys [db]} _] {:db db})]]}})
        gen       (rf.image-assembly/assemble [app-image] pool)
        chained   (rf.image-assembly/assemble [app-image doubles] pool)]
    (is (= [::fw-denied []] [(handler-of bare :event :rf.route/entry-denied)
                             (:rf.gen/shadows bare)])
        "with no override selected, the framework default resolves")
    (is (= [::app-denied [{:registration [:event :rf.route/entry-denied]
                           :image        :rf/framework
                           :shadowed-by  :app/main}]]
           [(handler-of gen :event :rf.route/entry-denied) (:rf.gen/shadows gen)]))
    (is (= [{:registration [:event :rf.route/entry-denied]
             :image        :rf/framework
             :shadowed-by  :test/doubles}
            {:registration [:event :rf.route/entry-denied]
             :image        :app/main
             :shadowed-by  :test/doubles}]
           (:rf.gen/shadows chained))
        "a chain names the final winner for every loser")))

(deftest a-lone-anonymous-image-overrides-without-a-degenerate-report
  ;; the ordinary stub idiom: one anonymous image inlining a framework effect
  (let [pool (conj pool (fw-desc :fx :rf.nav/push-url ::real-push))
        anon (rf.image/image {:registrations {:reg-fx [[:rf.nav/push-url stub-push]]}})
        gen  (rf.image-assembly/assemble [anon] pool)]
    (is (= [stub-push ::navigate []]
           [(handler-of gen :fx :rf.nav/push-url)
            (handler-of gen :event :rf.route/navigate)
            (:rf.gen/shadows gen)])
        "it wins, the rest of the base stays, and no entry names a nil image")))

(deftest an-inline-override-keeps-the-framework-default-carriers
  (let [fw-default (fw-desc :event :rf.route/entry-denied ::fw-denied
                            {:rf/framework-default? true
                             :sensitive             [[:requested-url]]})
        pool       (conj pool fw-default)
        handler    (fn [{:keys [db]} _] {:db db})
        auth       (rf.image/image {:id :app/auth
                                    :registrations {:reg-event [[:rf.route/entry-denied
                                                                 {:sensitive [[:note]]}
                                                                 handler]]}})
        d          (rf.image-assembly/resolve-descriptor
                     (rf.image-assembly/assemble [app-image auth] pool)
                     :event :rf.route/entry-denied)]
    (is (= [handler [[:requested-url] [:note]]] [(:impl d) (:sensitive d)])
        "the override replaces behaviour; the framework's carriers ride across, then its own")
    (let [app-d (assoc (reg-desc "app.auth" :event :rf.route/entry-denied ::app-denied)
                       :sensitive [[:requested-url]])
          gen   (rf.image-assembly/assemble [app-image] (conj pool app-d))]
      (is (identical? app-d (rf.image-assembly/resolve-descriptor
                              gen :event :rf.route/entry-denied))
          "a provenanced override already carrying the union passes through untouched"))
    (let [pool    (conj pool (fw-desc :fx :rf.nav/push-url ::real-push
                                      {:sensitive [[:url]]}))
          doubles (rf.image/image {:id :test/doubles
                                   :registrations {:reg-fx [[:rf.nav/push-url stub-push]]}})
          gen     (rf.image-assembly/assemble [app-image doubles] pool)]
      (is (nil? (:sensitive (rf.image-assembly/resolve-descriptor
                              gen :fx :rf.nav/push-url)))
          "a shadowed base registration that is not a replaceable default carries nothing"))))

(def ^:private std-set-db (fn [_ _] :standard))

(deftest standards-stay-protected-and-outside-the-base
  (rf.image-assembly/register-standard! :event :rf/set-db {:handler-fn std-set-db})
  ;; the framework also registers a standard into the ordinary registrar, so the
  ;; pool carries its nil-provenance copy
  (let [pool (conj pool (fw-desc :event :rf/set-db ::registrar-copy))
        gen  (rf.image-assembly/assemble [app-image] pool)]
    (is (= [std-set-db []] [(handler-of gen :event :rf/set-db) (:rf.gen/shadows gen)])
        "the registrar copy never enters the base, so no spurious collision")
    (is (= :rf.error/image-standard-replacement-forbidden
           (err-id #(rf.image-assembly/assemble
                      [app-image]
                      (conj pool (reg-desc "app.core" :event :rf/set-db ::app-set-db))))))))
