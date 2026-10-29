(ns re-frame.adapter.uix-view-conditional-root-dom-cljs-test
  "A registered view whose root toggles between an element and nil is one
  instance for its whole mounted life: no `:rf.view/unmounted` until its real
  teardown, one render key, and only its first render is a mount (Spec 009
  §`:rf.view/unmounted`). The wrap-view pairs every output of a React render
  with the unmount sentinel, nil included, so a `(when …)` root never deletes
  the sentinel."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            ["react-dom/client" :as react-dom-client]
            [uix.core :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]
            [re-frame.adapter.react-test-support :refer [with-browser-act]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.adapter.uix/adapter}))

(def ^:private frame-id :rf.uix-conditional-root/frame)
(def ^:private view-id :rf.uix-conditional-root/banner)

(defui banner [{:keys [show?]}]
  (when show? ($ :p {:data-testid "banner"} "hi")))

(deftest a-root-toggling-between-an-element-and-nil-stays-one-instance
  (with-browser-act
    (fn [act]
      (let [traces   (atom [])
            listener ::recorder]
        (rf/make-frame {:id frame-id})
        (rf/reg-view* view-id banner)
        (rf.trace.tooling/register-listener! listener
          (fn [ev]
            (when (and (#{:rf.view/rendered :rf.view/unmounted} (:operation ev))
                       (= view-id (:rf.view/id (:tags ev))))
              (swap! traces conj ev))))
        (let [node    (.createElement js/document "div")
              root    (react-dom-client/createRoot node)
              ;; The fixture's ambient frame is cleared, as a scheduled render
              ;; meets it, so the provider is what the render's frame read finds.
              render! (fn [show?]
                        (binding [rf.frame/*current-frame* nil]
                          (act (fn []
                                 (.render root
                                   ($ rf.adapter.uix/frame-provider {:frame frame-id}
                                      ($ (rf/view view-id) {:show? show?})))))))
              shown?  (fn [] (some? (.querySelector node "[data-testid='banner']")))
              of      (fn [op] (filterv #(= op (:operation %)) @traces))]
          (try
            (let [seen (mapv (fn [show?] (render! show?) (shown?)) [true false true false])]
              (is (= [true false true false] seen)
                  "the root rendered, vanished, came back and vanished again"))
            (is (empty? (of :rf.view/unmounted))
                "no :rf.view/unmounted while the instance stays mounted")
            (let [rendered (of :rf.view/rendered)
                  keys     (map #(:rf.view/render-key (:tags %)) rendered)]
              (is (= [true false false false] (map #(:rf.view/mount? (:tags %)) rendered))
                  "only the first render is a mount")
              (is (= 1 (count (distinct keys)))
                  "every render carries the instance's one render key")
              ;; The control: a real teardown, taken while the view renders nil.
              (act (fn [] (.unmount root)))
              (is (= [(first keys)] (map #(:rf.view/render-key (:tags %)) (of :rf.view/unmounted)))
                  "the real teardown fires exactly one :rf.view/unmounted, naming that render key"))
            (finally
              (rf.trace.tooling/unregister-listener! listener)
              (try (.unmount root) (catch :default _ nil))
              (rf/destroy-frame! frame-id))))))))
