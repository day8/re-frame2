(ns day8.re-frame2-xray.panels.image-view-live-store-cljs-test
  "The Frames tab lists a frame made without `:images`, which seals no image
  generation and resolves the registration store live (Spec 002 §Image
  resolution and composition). Its row is marked as resolving the live store
  and lists the registrations the public `{:frame f}` registrar query answers
  for it. An id that more than one namespace registers is one that frame
  cannot run, so the query omits it; the row names it as ambiguous, with
  those namespaces, rather than dropping it.

  Every frame here is made through the public `rf/make-frame`, and each rival
  registration names its namespace through `:ns` metadata, which is the
  provenance a `reg-*` written in that namespace records.

  Named `*-cljs-test.cljc` so both cognitect.test-runner (JVM) and
  shadow-cljs's `cljs-test$` build discover it."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.image-view-reads :as reads]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- frame-row
  "The image view's row for `frame-id`, or nil when the view does not list it."
  [frame-id]
  (first (filter #(= frame-id (:frame-id %)) (:frames (reads/image-view-data)))))

(defn- descriptor
  "The descriptor row a frame row lists for `[kind id]`, or nil."
  [row kind id]
  (first (filter #(= [kind id] [(:kind %) (:id %)]) (:descriptors (:image row)))))

(deftest a-frame-made-without-images-is-listed-as-resolving-the-live-store
  (rf/reg-event :app/inc {:ns 'app.counter} (fn [db _] db))
  (rf/reg-fx :app/beep {:ns 'app.counter} (fn [_ _] nil))
  (rf/make-frame {:id :app/main})
  (let [row (frame-row :app/main)]
    (is (some? row) "the frame is listed")
    (is (true? (:live-store? row)) "and marked as resolving the live store")
    (is (= {:kind :ns :ns "app.counter"} (:provenance (descriptor row :event :app/inc)))
        "its event is listed with the namespace that registers it")
    (is (some? (descriptor row :fx :app/beep)) "and so is its fx")
    (is (true? (:images? (reads/image-view-data)))
        "so the section shows frames rather than the empty caption")))

(deftest an-ambiguous-id-is-named-with-its-namespaces
  (rf/reg-event :app/save {:ns 'app.a} (fn [db _] db))
  (rf/reg-event :app/save {:ns 'app.b} (fn [db _] db))
  (rf/reg-event :app/load {:ns 'app.a} (fn [db _] db))
  (rf/make-frame {:id :app/main})
  (let [row (frame-row :app/main)]
    (is (= [{:kind :event :id :app/save :namespaces ["app.a" "app.b"]}]
           (:ambiguous (:image row)))
        "the ambiguous id is listed, naming both namespaces")
    (is (nil? (descriptor row :event :app/save))
        "and is not listed as a registration the frame runs")
    (is (some? (descriptor row :event :app/load))
        "while every other id still is")))

(deftest an-image-loaded-frame-keeps-its-image-row
  (rf/reg-event :app/inc {:ns 'app.counter} (fn [db _] db))
  (rf/make-frame {:id :app/sealed
                  :images [(rf/image {:select-ns {:include ["app.counter"]}})]})
  (let [row (frame-row :app/sealed)]
    (is (false? (:live-store? row)) "a frame made with :images is not marked live")
    (is (some? (descriptor row :event :app/inc))
        "and lists its image's registrations as before")))

(deftest a-frame-the-registrar-query-refuses-is-not-listed
  (testing "the fixture's ambient frame neither seals an image nor resolves the
            store live, so the frame-targeted query refuses it"
    (rf/make-frame {:id :app/main})
    (is (some? (frame-row :app/main)) "control: a live-store frame is listed")
    (is (nil? (frame-row :rf/default)))))
