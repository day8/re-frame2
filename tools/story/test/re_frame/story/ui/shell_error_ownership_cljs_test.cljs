(ns re-frame.story.ui.shell-error-ownership-cljs-test
  "A mounted Story shell OWNS the refusals its own frames produce, so
  `re-frame.error-emit`'s untooled-dev console fallback stays quiet while
  Story runs its deliberately-failing variants — the Story feature-load
  browser gate treats a console error as fatal.

  Every Story-allocated frame declares `{:sink :rf.story/errors}` on its
  `[:observability :errors]` policy, and the mounted shell registers that
  sink. So these rows pin the SINK REGISTRY (`re-frame.observability/sinks`)
  and the frame policy — the precise things the fallback consults. A no-op
  on the corpus-wide `:errors` LISTENER registry would also quiet the
  console, but for every frame on the page, the host app's included.

  Whether a console error actually stops appearing is the browser gate's
  job (`npm run test:story-feature-load`)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.observability :as rf.observability]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.frames :as rf.story.frames]
            [re-frame.story.ui.shell :as rf.story.ui.shell]))

(def ^:private register-sink!   @#'rf.story.ui.shell/register-error-sink!)
(def ^:private unregister-sink! @#'rf.story.ui.shell/unregister-error-sink!)

;; Private in `observability` because they are not app-facing surfaces;
;; read here because they are what the fallback consults.
(def ^:private sinks @#'rf.observability/sinks)
(def ^:private listeners @#'rf.error-emit/listeners)

(def ^:private variant-frame-config @#'rf.story.frames/variant-frame-config)
(def ^:private inline-frame-config  @#'rf.story.frames/inline-frame-config)

(use-fixtures :each
  {:before (fn []
             (rf.error-emit/clear-error-listeners!)
             (rf.observability/clear-observability-sinks!))
   :after  (fn []
             (rf.error-emit/clear-error-listeners!)
             (rf.observability/clear-observability-sinks!))})

(deftest the-shell-registers-its-sink-and-never-a-listener
  (testing "mounting registers ONE sink under the Story id and leaves a host
            app's own `:errors` listener alone; unmounting removes the sink,
            handing the console fallback back to whatever runs next"
    (rf.error-emit/register-error-listener! ::host-app (fn [_record] nil))
    (register-sink!)
    (is (= [rf.story.config/error-sink-id] (vec (keys @sinks))))
    (is (= [::host-app] (vec (keys @listeners)))
        "a sink, not a listener: the host app's listener stands alone")
    (unregister-sink!)
    (is (nil? (get @sinks rf.story.config/error-sink-id)))
    (is (= [::host-app] (vec (keys @listeners))))))

(deftest every-story-allocated-frame-declares-the-sink
  (testing "an inline-plan frame names the documented `:rf.story/errors`
            sink, as a variant frame does (the routing row below)"
    (is (= [{:sink :rf.story/errors}]
           (get-in (inline-frame-config :story.demo/inline nil) [:observability :errors])))))

(deftest a-real-story-frame-routes-its-refusal-to-the-sink
  (testing "the two halves composed through the real framework: a frame
            built from Story's own variant config, a refusal dispatched into
            it, and the record arriving at the registered sink"
    (let [seen (atom [])]
      (rf/register-observability-sink! rf.story.config/error-sink-id
                                       (fn [r] (swap! seen conj r)))
      (rf/make-frame (assoc (variant-frame-config :story.owner/variant nil {})
                            :id :story.owner/variant))
      (rf/reg-event :story.owner/throws
                    {:frame :story.owner/variant}
                    (fn [_ _] (throw (ex-info "variant kaboom" {}))))
      (rf/dispatch-sync [:story.owner/throws] {:frame :story.owner/variant})
      (is (= [{:kind :rf.observe/error :frame :story.owner/variant}]
             (mapv #(select-keys % [:kind :frame]) @seen))))))
