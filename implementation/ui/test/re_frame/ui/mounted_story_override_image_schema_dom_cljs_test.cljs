(ns re-frame.ui.mounted-story-override-image-schema-dom-cljs-test
  "Mounted conformance for Story's first-party compiled-view override rung.

  This is deliberately one end-to-end Tier-3 fixture: a real React context,
  frame provider, inline image registration, compiled lexical `ui/sub` site,
  ViewCell capture/commit, static handles, view HMR, and root teardown.  It
  complements the resolver/schema unit tests by making token and ownership
  behaviour observable at the mounted boundary.

  Browser-only bodies — the `-dom-cljs-test` suffix enrols the namespace in
  `:browser-test`; the node runner loads it and skips the DOM body."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.observation :as obs]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.client :as client]
            [re-frame.ui.eq :as eq]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.runtime :as runtime]
            [re-frame.ui.sub-overrides :as sub-overrides]
            [re-frame.ui.test :as uit]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn reactive/reset-scheduler!}))

(defn- reject-unexpectedly!
  [done label e]
  (is false (str label ": " e))
  (done))

(defn- mounted-snapshot
  []
  {:live-roots    (client/live-root-ids)
   :root-cells    (reactive/root-cell-count)
   :live-cells    (count (reactive/current-live-cells))
   :pending-cells (reactive/pending-cell-count)
   :containers    (.-length
                   (.querySelectorAll js/document
                                      "[data-rf-ui-test-root]"))})

(defn- cache-owner-count
  [frame-id]
  (reduce + 0
          (map (comp obs/active-owner-count :reaction)
               (vals @(:sub-cache (frame/frame frame-id))))))

(defn- only-site
  [cell]
  (let [sites (reactive/committed-sites cell)]
    (is (= 1 (count sites)) "the probe view has exactly one lexical sub site")
    (first sites)))

(defn- cell-with-committed-value
  "Find the one single-site cell whose committed value is `wanted`."
  [wanted]
  (some (fn [cell]
          (when (some #(= wanted (:value %))
                      (vals (reactive/committed-sites cell)))
            cell))
        (reactive/current-live-cells)))

(defn- handle-state*
  [handle]
  @(@#'obs/handle-state handle))

(defn- assert-static-handle!
  [handle expected-value]
  (let [state (handle-state* handle)]
    (is (false? (obs/owned? handle))
        "a Story pin reports no subscription ownership")
    (is (eq/rf= expected-value (:value (obs/read handle))))
    (is (= :static (:handle-kind state)))
    (is (= :live (:status state)))
    (is (= #{:handle-kind :target :status} (set (keys state)))
        "the static handle carries no frame, reaction, watch, or callback")))

(defn- image-with-schema
  [image-id schema ordinary-value]
  (rf/image
   {:id image-id
    :registrations
    {:reg-sub
     [[::image-value {:schema schema}
       (fn [_db _query] ordinary-value)]]}}))

(defview story-value [{:keys [role]}]
  (let [value (ui/sub [::image-value])]
    [:output {:data-role role}
     (cond
       (map? value) (:label value)
       (nil? value) "nil"
       :else        (str value))]))

(defn controlled-provider [^js props]
  (let [[overrides set-overrides!] (React/useState (.-initial props))]
    (reset! (.-control props) set-overrides!)
    (sub-overrides/provider-element overrides (.-children props))))

(defn nested-story-providers [^js props]
  (sub-overrides/provider-element
   (.-outer props)
   (React/createElement
    (.-Fragment React) nil
    (React/createElement story-value #js {:role "outer-before"})
    (React/createElement
     controlled-provider
     #js {:initial (.-inner props) :control (.-control props)}
     (React/createElement story-value #js {:role "target"}))
    ;; A sibling after the inner Provider proves React restored the closest
    ;; enclosing outer value rather than leaking the nested scope.
    (React/createElement story-value #js {:role "outer-after"}))))

(defview mounted-story-tree [{:keys [outer inner control]}]
  [:section
   (ui/raw
    (React/createElement
     nested-story-providers
     #js {:outer outer :inner inner :control control}))])

(defn- republish-story-view!
  "Model a same-hook-signature Fast Refresh publication of the compiled view."
  []
  (let [{:keys [render-fn compare-fn manifest]}
        (reactive/view-descriptor ::story-value)]
    (runtime/register-view! ::story-value render-fn compare-fn
                            "story-value" manifest)))

(deftest mounted-story-override-image-schema-and-hmr-conformance
  (when (browser?)
    (let [frame-id      ::story-frame
          ;; Valid under both target-image generations, but invalid under the
          ;; conflicting global :string schema. This keeps the outer LIFO
          ;; controls green while the inner map becomes the sole rejection.
          outer-value  17
          initial-value {:label "inner"}
          equal-value  (hash-map :label "inner")
          changed-value {:label "changed"}
          ordinary-v1  {:label "ordinary-image-v1"}
          ordinary-v2  {:label "ordinary-image-v2"}
          control       (atom nil)
          errors        (atom [])
          trace-id      ::mounted-story-errors
          _             (trace-tooling/register-listener!
                         trace-id
                         (fn [event]
                           (when (= :error (:op-type event))
                             (swap! errors conj event))))
          ;; The global registration deliberately disagrees with the inline
          ;; image. A mounted valid map can survive only if resolution reads
          ;; the target image's flattened runtime metadata.
          _             (rf/reg-sub ::image-value
                                    {:schema :string}
                                    (fn [_db _query] "ordinary-global"))
          f             (rf/make-frame
                         {:id frame-id
                          :adapter ui/adapter
                          :images [(image-with-schema
                                    ::image-v1
                                    [:or :int :double
                                     [:map [:label :string]]]
                                    ordinary-v1)]})
          before        (mounted-snapshot)
          seen          (atom {})]
      (is (not (identical? initial-value equal-value))
          "the equality transition uses a genuinely fresh provider value")
      (async done
        (->
         (uit/with-root
           [root [ui/frame-provider {:frame f}
                  [mounted-story-tree
                   {:outer {[::image-value] outer-value}
                    :inner {[::image-value] initial-value}
                    :control control}]]]
           (testing "the closest provider wins and restores in LIFO order"
             (is (= "17" (.-textContent
                              (.querySelector root "[data-role='outer-before']"))))
             (is (= "inner" (.-textContent
                              (.querySelector root "[data-role='target']"))))
             (is (= "17" (.-textContent
                              (.querySelector root "[data-role='outer-after']")))))
           (let [cell       (cell-with-committed-value initial-value)
                 [sid site] (only-site cell)
                 handle      (:handle site)]
             (is (some? cell))
             (is (= :story-override (get-in site [:target :kind])))
             (is (identical? initial-value (:value site)))
             (assert-static-handle! handle initial-value)
             (is (empty? @(:sub-cache (frame/frame frame-id)))
                 "a static hit materializes no frame cache node")
             (reset! seen {:cell cell
                           :sid sid
                           :query (:query site)
                           :handle handle
                           :revision (reactive/revision cell)
                           :remount (reactive/view-remount-generation
                                     ::story-value)})
             (->
              (uit/flush!
               #(@control {[::image-value] equal-value}))
              (.then
               (fn []
                 (let [{:keys [cell sid query handle revision]} @seen
                       current-cell (cell-with-committed-value initial-value)
                       site         (reactive/committed-site current-cell sid)]
                   (testing "rf=-equal provider replacement is an exact keep"
                     (is (identical? cell current-cell))
                     (is (identical? handle (:handle site)))
                     (is (identical? query (:query site))
                         "the compiler-indexed site keeps its exact query object")
                     (is (identical? initial-value (:value site))
                         "rf=-equal values retain the prior exact object")
                     (is (= revision (reactive/revision current-cell)))
                     (is (= "inner" (.-textContent
                                     (.querySelector root "[data-role='target']")))))
                   (uit/flush!
                    #(@control {[::image-value] js/NaN})))))
              (.then
               (fn []
                 (let [{:keys [cell sid handle]} @seen
                       site       (reactive/committed-site cell sid)
                       nan-handle  (:handle site)
                       nan-target (:target (handle-state* nan-handle))]
                   (testing "moving from a map to NaN retargets exactly once"
                     (is (js/Number.isNaN (:value site)))
                     (is (not (identical? handle nan-handle)))
                     (assert-static-handle! nan-handle js/NaN)
                     (is (= "NaN" (.-textContent
                                    (.querySelector root "[data-role='target']")))))
                   (swap! seen assoc :handle nan-handle
                                     :target nan-target
                                     :revision (reactive/revision cell))
                   (uit/flush! #(@control {[::image-value] js/NaN})))))
              (.then
               (fn []
                 (let [{:keys [cell sid query handle target revision]} @seen
                       site  (reactive/committed-site cell sid)
                       state (handle-state* (:handle site))]
                   (testing "NaN→NaN provider replacement is an exact rf= keep"
                     (is (identical? handle (:handle site)))
                     (is (identical? target (:target state))
                         "the retained handle keeps its exact captured target")
                     (is (obs/current? handle (:target site))
                         "the prior target covers the freshly captured NaN target")
                     (is (identical? query (:query site)))
                     (is (js/Object.is js/NaN (:value site)))
                     (is (= revision (reactive/revision cell)))
                     (is (= "NaN" (.-textContent
                                    (.querySelector root "[data-role='target']")))))
                   (uit/flush!
                    #(@control {[::image-value] changed-value})))))
              (.then
               (fn []
                 (let [{:keys [cell sid handle]} @seen
                       site      (reactive/committed-site cell sid)
                       new-handle (:handle site)]
                   (testing "a changed provider value retargets the same site"
                     (is (= changed-value (:value site)))
                     (is (not (identical? handle new-handle)))
                     (assert-static-handle! new-handle changed-value)
                     (is (= "changed" (.-textContent
                                       (.querySelector root "[data-role='target']")))))
                   (swap! seen assoc :handle new-handle)
                   (uit/flush! #(@control {})))))
              (.then
               (fn []
                 (let [{:keys [cell sid handle]} @seen
                       site       (reactive/committed-site cell sid)
                       node-handle (:handle site)
                       reaction   (:reaction
                                   (get @(:sub-cache (frame/frame frame-id))
                                        [::image-value]))]
                   (testing "removing the override retargets to one ordinary owner"
                     (is (= :subscription (get-in site [:target :kind])))
                     (is (not (identical? handle node-handle)))
                     (is (obs/owned? node-handle))
                     (is (= 1 (cache-owner-count frame-id)))
                     (is (= ordinary-v1 (:value site)))
                     (is (= "ordinary-image-v1"
                            (.-textContent
                             (.querySelector root "[data-role='target']")))))
                   (swap! seen assoc :node-handle node-handle
                                     :node-reaction reaction)
                   (uit/flush!
                    #(@control {[::image-value] changed-value})))))
              (.then
               (fn []
                 (let [{:keys [cell sid node-handle node-reaction]} @seen
                       site       (reactive/committed-site cell sid)
                       new-static (:handle site)]
                   (testing "re-adding the pin releases the ordinary owner"
                     (is (= :story-override (get-in site [:target :kind])))
                     (assert-static-handle! new-static changed-value)
                     (is (false? (obs/current? node-handle (:target site))))
                     (is (= :released (:status (handle-state* node-handle))))
                     (is (zero? (obs/active-owner-count node-reaction)))
                     (is (empty? @(:sub-cache (frame/frame frame-id)))))
                   (swap! seen assoc :handle new-static
                                     :revision (reactive/revision cell)
                                     :generation (reactive/generation cell))

                   ;; A same-schema image replacement is silent to a
                   ;; callback-free handle. The following view HMR is what
                   ;; deliberately re-renders/re-validates the site.
                   (rf/make-frame
                    {:id frame-id
                     :adapter ui/adapter
                     :images [(image-with-schema
                               ::image-v2
                               [:or :int :double
                                [:map [:label :string]]]
                               ordinary-v2)]})
                   (testing "image movement alone cannot wake a static handle"
                     (is (identical?
                          new-static
                          (:handle (reactive/committed-site cell sid))))
                     (is (= (:revision @seen) (reactive/revision cell)))
                     (is (empty? @errors)))
                   (uit/flush! republish-story-view!))))
              (.then
               (fn []
                 (let [{:keys [cell sid query handle revision remount generation]}
                       @seen
                       site (reactive/committed-site cell sid)]
                   (testing "same-schema image replacement plus same-shape HMR keeps ownership"
                     (is (identical? cell
                                     (cell-with-committed-value changed-value)))
                     (is (identical? handle (:handle site)))
                     (is (identical? query (:query site)))
                     (is (= revision (reactive/revision cell)))
                     (is (> (reactive/generation cell) generation))
                     (is (= remount
                            (reactive/view-remount-generation ::story-value))))

                   ;; Swap to a schema that rejects the still-pinned map. The
                   ;; swap itself stays silent; the next HMR render performs
                   ;; the current-image validation and retargets to nil.
                   (rf/make-frame
                    {:id frame-id
                     :adapter ui/adapter
                     :images [(image-with-schema
                               ::image-v3 :int 17)]})
                   (testing "schema movement alone is not a static invalidation channel"
                     (is (identical?
                          handle
                          (:handle (reactive/committed-site cell sid))))
                     (is (= revision (reactive/revision cell)))
                     (is (empty? @errors)))
                   (uit/flush! republish-story-view!))))
              (.then
               (fn []
                 (let [{:keys [cell sid handle remount]} @seen
                       site      (reactive/committed-site cell sid)
                       nil-handle (:handle site)
                       ;; Scope to THIS frame's own failures. `errors` is a
                       ;; PROCESS-GLOBAL trace listener (every `:op-type :error`
                       ;; across every namespace), so an `:operation`-only count
                       ;; can be padded by a sibling suite's schema-validation
                       ;; failure that lands in this fixture's async settle
                       ;; window — measuring the suite, not this frame
                       ;; (rf2-veyfp). The failure carries `:tags :frame`
                       ;; (asserted below), so scope the count to it.
                       failures (filter
                                 #(and (= :rf.error/schema-validation-failure
                                          (:operation %))
                                       (= frame-id (-> % :tags :frame)))
                                 @errors)
                       failure  (first failures)]
                   (testing "HMR revalidates against the new image and installs a nil HIT"
                     (is (contains? (reactive/current-live-cells) cell))
                     (is (not (identical? handle nil-handle)))
                     (is (= :story-override (get-in site [:target :kind]))
                         "invalid override remains a HIT, not an ordinary miss")
                     (assert-static-handle! nil-handle nil)
                     (is (= "nil" (.-textContent
                                   (.querySelector root "[data-role='target']"))))
                     (is (= remount
                            (reactive/view-remount-generation ::story-value))))
                   (testing "the rejection is attributed to the target image schema"
                     (is (= 1 (count failures)))
                     (is (= :sub-override (-> failure :tags :where)))
                     (is (= frame-id (-> failure :tags :frame)))
                     (is (= ::image-value (-> failure :tags :rf.sub/id)))
                     (is (= ::image-value (-> failure :tags :failing-id)))
                     (is (= [::image-value]
                            (-> failure :tags :rf.sub/query-v)))
                     (is (= ::image-value (-> failure :tags :schema-id)))
                     (is (= changed-value (-> failure :tags :received)))
                     (is (some? (-> failure :tags :explain)))
                     (is (re-find #":int" (-> failure :tags :reason))))
                   (swap! seen assoc :handle nil-handle)
                   (uit/flush! #(@control {[::image-value] 7})))))
              (.then
               (fn []
                 (let [{:keys [cell sid handle]} @seen
                       site       (reactive/committed-site cell sid)
                       valid-handle (:handle site)]
                   (testing "a later schema-valid pin retargets normally"
                     (is (identical? cell (cell-with-committed-value 7)))
                     (is (not (identical? handle valid-handle)))
                     (assert-static-handle! valid-handle 7)
                     (is (= "7" (.-textContent
                                 (.querySelector root "[data-role='target']"))))
                     (is (empty? @(:sub-cache (frame/frame frame-id)))))
                   (swap! seen assoc :handle valid-handle
                                     :revision (reactive/revision cell))
                   (uit/flush! #(rf/destroy-frame! f)))))
              (.then
               (fn []
                 (let [{:keys [cell sid handle revision]} @seen]
                   (testing "a static pin owns no frame lifetime or callback"
                     (is (nil? (frame/frame frame-id)))
                     (is (= :connected (reactive/lifecycle cell)))
                     (is (identical?
                          handle
                          (:handle (reactive/committed-site cell sid))))
                     (is (= revision (reactive/revision cell)))
                     (is (zero? (reactive/pending-cell-count)))
                     (is (= "7" (.-textContent
                                 (.querySelector root "[data-role='target']")))))))))))
         (.then
          (fn []
            (let [{:keys [cell]} @seen]
              (testing "root unmount releases the cell and restores baseline"
                (is (= :dead (reactive/lifecycle cell)))
                (is (empty? (reactive/committed-sites cell)))
                (is (= before (mounted-snapshot)))))
            (trace-tooling/unregister-listener! trace-id)
            (done))
          (fn [e]
            (trace-tooling/unregister-listener! trace-id)
            (when (frame/frame frame-id)
              (rf/destroy-frame! f))
            (reject-unexpectedly!
             done "mounted Story override/image-schema fixture rejected" e))))))))
