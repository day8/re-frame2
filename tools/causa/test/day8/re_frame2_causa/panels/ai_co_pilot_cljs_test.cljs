(ns day8.re-frame2-causa.panels.ai-co-pilot-cljs-test
  "CLJS-side wiring tests for Causa's AI Co-Pilot rail panel
  (Phase 5, rf2-rccf3).

  ## Contracts under test (in addition to the pure-data tests in
  `ai_co_pilot_helpers_cljs_test.cljc`)

  1. **Registry wires the subs / events / fxs** under the
     `:rf.causa/*` namespace. The `:rf.causa/copilot-*` surface is
     present after `register-causa-handlers!`.

  2. **Pull-only model.** Per spec §Pull-only model the panel never
     dispatches `:rf.causa.fx/llm-stream` on mount; the only path
     that fires it is `:rf.causa/copilot-submit-question`.

  3. **Defaults.** Per spec §Default state + §Redaction defaults the
     rail is collapsed (`:rf.causa/copilot-open?` returns false) and
     the redaction settings are privacy-by-default.

  4. **Toggle.** `:rf.causa/copilot-toggle` flips the open / closed
     state and marks the cue as no-longer-pulsing.

  5. **Submit + stream + clear.** The conversation buffer accepts a
     question + streamed tokens + end, then `/clear` drops it.

  6. **No persistence.** Per Lock 12 the conversation buffer is
     in-memory only — no localStorage writes happen on submit /
     stream / clear.

  7. **No voice / STT.** Per Lock 13 the rail renders no `🎙` button.

  8. **Chip click routes the panel-jump.** `:rf.causa/copilot-chip-clicked`
     dispatches the chip's target with the chip's value as arg."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.ai-co-pilot :as copilot]
            [day8.re-frame2-causa.panels.ai-co-pilot-helpers :as h]))

;; ---- fixture ------------------------------------------------------------

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- effect capture -----------------------------------------------------
;;
;; The pull-only contract asserts that no outbound LLM call fires on
;; mount; only the user-submit path fires :rf.causa.fx/llm-stream. We
;; replace the registry's no-op stub with a capture fn so we can assert
;; "fired N times" + "fired with these args".

(defonce ^:private captured-llm-calls (atom []))

(defn- install-llm-capture-fx! []
  (reset! captured-llm-calls [])
  (rf/reg-fx :rf.causa.fx/llm-stream
    (fn [_ctx args]
      (swap! captured-llm-calls conj args))))

(defn- captured [] @captured-llm-calls)

;; ---- chip-target capture ------------------------------------------------
;;
;; Chip clicks dispatch their target via :rf.causa/copilot-chip-clicked
;; which conjures `[:dispatch [target value]]`. To assert "the right
;; target fired", we register no-op event-fx handlers under the chip
;; targets and capture dispatches.

(defonce ^:private captured-chip-dispatches (atom []))

(defn- install-chip-capture! []
  (reset! captured-chip-dispatches [])
  (doseq [target [:rf.causa/select-dispatch-id
                  :rf.causa/select-epoch
                  :rf.causa.copilot/open-path
                  :rf.causa.copilot/open-handler]]
    (rf/reg-event-fx target
      (fn [_cofx ev]
        (swap! captured-chip-dispatches conj ev)
        nil))))

(defn- chip-dispatches [] @captured-chip-dispatches)

;; ---- hiccup walker ------------------------------------------------------
;;
;; The Causa view tree uses both keyword-headed hiccup (`[:div ...]`)
;; and fn-component hiccup (`[my-fn arg1 arg2]`). The walker fully
;; expands fn-component vectors (recursively, so a fn that returns
;; another fn-component vector keeps expanding) before walking — so
;; `data-testid` lookups inside the expanded sub-tree are reachable.

(declare expand-tree)

(defn- expand-fn-component-vector
  "If `node` is `[fn-component arg ...]` invoke the fn with args and
  recurse on the result; otherwise return the node unchanged."
  [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-tree (apply (first node) (rest node)))
    node))

(defn- expand-tree
  "Walk `tree` and replace every fn-component vector with its rendered
  result (recursively). Pure keyword-headed hiccup passes through; nil
  / strings / numbers / maps pass through."
  [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (expand-tree (apply (first tree) (rest tree)))

    (vector? tree)
    (mapv expand-tree tree)

    (seq? tree)
    (map expand-tree tree)

    :else
    tree))

(defn- hiccup-seq [tree]
  (let [expanded (expand-tree tree)]
    (tree-seq (some-fn vector? seq?) seq expanded)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filterv (fn [node]
             (and (vector? node)
                  (map? (second node))
                  (when-let [tid (:data-testid (second node))]
                    (= 0 (.indexOf tid prefix)))))
           (hiccup-seq tree)))

;; ---- (1) registry installs the subs / events / fxs ---------------------

(deftest registry-installs-copilot-subs
  (registry/register-causa-handlers!)
  (is (some? (registrar/handler :sub :rf.causa/copilot-open?)))
  (is (some? (registrar/handler :sub :rf.causa/copilot-conversation)))
  (is (some? (registrar/handler :sub :rf.causa/copilot-provider)))
  (is (some? (registrar/handler :sub :rf.causa/copilot-cue-active?)))
  (is (some? (registrar/handler :sub :rf.causa/copilot-redaction-settings)))
  (is (some? (registrar/handler :sub :rf.causa/copilot-streaming-token-count))))

(deftest registry-installs-copilot-events
  (registry/register-causa-handlers!)
  (is (some? (registrar/handler :event :rf.causa/copilot-toggle)))
  (is (some? (registrar/handler :event :rf.causa/copilot-submit-question)))
  (is (some? (registrar/handler :event :rf.causa/copilot-stream-token)))
  (is (some? (registrar/handler :event :rf.causa/copilot-stream-end)))
  (is (some? (registrar/handler :event :rf.causa/copilot-clear-conversation)))
  (is (some? (registrar/handler :event :rf.causa/copilot-mark-first-use)))
  (is (some? (registrar/handler :event :rf.causa/copilot-set-provider)))
  (is (some? (registrar/handler :event :rf.causa/copilot-cycle-provider)))
  (is (some? (registrar/handler :event :rf.causa/copilot-set-redaction)))
  (is (some? (registrar/handler :event :rf.causa/copilot-chip-clicked))))

(deftest registry-installs-llm-stream-fx
  (registry/register-causa-handlers!)
  (is (some? (registrar/handler :fx :rf.causa.fx/llm-stream))))

;; ---- (2) pull-only model -----------------------------------------------

(deftest no-llm-call-on-mount
  (testing "rendering the rail makes NO outbound :rf.causa.fx/llm-stream
            call (per spec §Pull-only model + Lock 10)"
    (registry/register-causa-handlers!)
    (install-llm-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      ;; Render both the rail view and the panel-style view. Neither
      ;; should fire an LLM call.
      (copilot/ai-co-pilot-rail)
      (copilot/ai-co-pilot-view)
      (copilot/ai-co-pilot-cue))
    (is (= 0 (count (captured)))
        "no outbound LLM call fired by render")))

(deftest llm-call-fires-only-on-user-submit
  (testing "the only path that fires :rf.causa.fx/llm-stream is
            :rf.causa/copilot-submit-question"
    (registry/register-causa-handlers!)
    (install-llm-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      ;; Toggle, mark-first-use, set-provider — none of these fire LLM.
      (rf/dispatch-sync [:rf.causa/copilot-toggle])
      (rf/dispatch-sync [:rf.causa/copilot-mark-first-use])
      (rf/dispatch-sync [:rf.causa/copilot-set-provider :openai])
      (is (= 0 (count (captured)))
          "toggle / mark-first-use / set-provider don't fire LLM")
      ;; Submit a question — exactly one call.
      (rf/dispatch-sync [:rf.causa/copilot-submit-question
                         {:text "Why did this fire?" :parsed nil}])
      (is (= 1 (count (captured)))
          "submit fires exactly one :rf.causa.fx/llm-stream"))))

;; ---- (3) defaults -----------------------------------------------------

(deftest rail-is-collapsed-by-default
  (testing "per spec §Default state + Lock 8 — :rf.causa/copilot-open?
            is false until the user toggles"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (is (false? @(rf/subscribe [:rf.causa/copilot-open?]))))))

(deftest cue-is-active-by-default
  (testing "per spec §The AI co-pilot collapsed cue — the pulse is
            active until first use"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (is (true? @(rf/subscribe [:rf.causa/copilot-cue-active?]))))))

(deftest cue-stops-after-first-toggle
  (testing "per spec §The AI co-pilot collapsed cue — the pulse stops
            entirely after the user has used the co-pilot once"
    (registry/register-causa-handlers!)
    (install-llm-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/copilot-toggle])
      (is (false? @(rf/subscribe [:rf.causa/copilot-cue-active?]))
          "cue inactive after first toggle"))))

(deftest redaction-settings-default-to-privacy-by-default
  (testing "per spec §Redaction defaults — values are <redacted>
            by default"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (let [settings @(rf/subscribe [:rf.causa/copilot-redaction-settings])]
        (is (false? (:unmask-event-args settings)))
        (is (false? (:unmask-app-db settings)))))))

(deftest provider-defaults-to-claude
  (testing "per spec §Provider abstraction — Claude is the default"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (is (= :claude @(rf/subscribe [:rf.causa/copilot-provider]))))))

;; ---- (4) toggle --------------------------------------------------------

(deftest toggle-flips-open-state
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (is (false? @(rf/subscribe [:rf.causa/copilot-open?])))
    (rf/dispatch-sync [:rf.causa/copilot-toggle])
    (is (true? @(rf/subscribe [:rf.causa/copilot-open?])))
    (rf/dispatch-sync [:rf.causa/copilot-toggle])
    (is (false? @(rf/subscribe [:rf.causa/copilot-open?])))))

(deftest toggle-writes-to-causa-frame-not-host
  (testing "the rail's open state lands on :rf/causa, not :rf/default
            (per the registry's frame-isolation contract)"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/copilot-toggle]))
    (is (true? (boolean (:copilot-open? (frame/frame-app-db-value :rf/causa)))))
    (is (nil? (:copilot-open? (frame/frame-app-db-value :rf/default))))))

;; ---- (5) submit / stream / clear --------------------------------------

(deftest submit-question-appends-question-and-starts-answer
  (testing ":rf.causa/copilot-submit-question lands a question turn +
            a streaming-answer turn"
    (registry/register-causa-handlers!)
    (install-llm-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/copilot-submit-question
                         {:text "Why?" :parsed nil}])
      (let [conv @(rf/subscribe [:rf.causa/copilot-conversation])]
        (is (= 2 (count conv)))
        (is (= :question (:role (first conv))))
        (is (= "Why?"    (:text (first conv))))
        (is (= :answer   (:role (second conv))))
        (is (= ""        (:text (second conv))))
        (is (true?       (:streaming? (second conv))))))))

(deftest stream-token-extends-trailing-answer
  (registry/register-causa-handlers!)
  (install-llm-capture-fx!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/copilot-submit-question
                       {:text "Why?" :parsed nil}])
    (rf/dispatch-sync [:rf.causa/copilot-stream-token "Be"])
    (rf/dispatch-sync [:rf.causa/copilot-stream-token "cause"])
    (let [conv @(rf/subscribe [:rf.causa/copilot-conversation])]
      (is (= "Because" (:text (second conv))))
      (is (= 2 @(rf/subscribe [:rf.causa/copilot-streaming-token-count]))
          "token count reflects the streamed tokens"))))

(deftest stream-end-finalises-answer
  (registry/register-causa-handlers!)
  (install-llm-capture-fx!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/copilot-submit-question
                       {:text "Why?" :parsed nil}])
    (rf/dispatch-sync [:rf.causa/copilot-stream-token "Done"])
    (rf/dispatch-sync [:rf.causa/copilot-stream-end])
    (let [conv @(rf/subscribe [:rf.causa/copilot-conversation])]
      (is (false? (:streaming? (second conv))))
      (is (= 0 @(rf/subscribe [:rf.causa/copilot-streaming-token-count]))))))

(deftest clear-conversation-empties-buffer
  (testing "/clear (and :rf.causa/copilot-clear-conversation) drops the
            buffer — per spec §Slash commands + §Ephemeral conversation
            in-session affordance Ctrl+L"
    (registry/register-causa-handlers!)
    (install-llm-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/copilot-submit-question
                         {:text "Why?" :parsed nil}])
      (rf/dispatch-sync [:rf.causa/copilot-stream-token "Because"])
      (rf/dispatch-sync [:rf.causa/copilot-clear-conversation])
      (is (= [] @(rf/subscribe [:rf.causa/copilot-conversation]))))))

;; ---- (6) no persistence -----------------------------------------------

(deftest conversation-buffer-not-persisted-to-localstorage
  (testing "per Lock 12 — :rf.causa/copilot-conversation never writes
            to localStorage. We assert by capturing localStorage.setItem
            and verifying it's never called during a submit + stream +
            clear cycle.

            Node-test runtime has no `window` / `localStorage`, so the
            assertion form here installs the spy *only when the host
            exposes it*. In node-test the absence of `window` IS the
            assertion (a write would crash); in the browser-test
            harness the spy guards against a regression that would
            silently start persisting."
    (let [has-window? (exists? js/window)
          captured-writes (atom [])
          original-setItem (when (and has-window?
                                      (.-localStorage js/window))
                             (.-setItem (.-localStorage js/window)))]
      (when original-setItem
        (set! (.-setItem (.-localStorage js/window))
              (fn [k v] (swap! captured-writes conj [k v]))))
      (try
        (registry/register-causa-handlers!)
        (install-llm-capture-fx!)
        (frame/reg-frame :rf/causa {})
        (rf/with-frame :rf/causa
          (rf/dispatch-sync [:rf.causa/copilot-toggle])
          (rf/dispatch-sync [:rf.causa/copilot-submit-question
                             {:text "Why?" :parsed nil}])
          (rf/dispatch-sync [:rf.causa/copilot-stream-token "Because"])
          (rf/dispatch-sync [:rf.causa/copilot-stream-end])
          (rf/dispatch-sync [:rf.causa/copilot-clear-conversation]))
        ;; Filter for any key that hints at the conversation buffer
        ;; (rf.causa.copilot.* / conversation / chat-history etc).
        (let [conversation-writes
              (filter (fn [[k _]]
                        (or (re-find #"copilot" (str k))
                            (re-find #"conversation" (str k))
                            (re-find #"chat" (str k))))
                      @captured-writes)]
          (is (empty? conversation-writes)
              "no conversation-shaped localStorage write fired"))
        (finally
          (when original-setItem
            (set! (.-setItem (.-localStorage js/window))
                  original-setItem)))))))

;; ---- (7) no voice / STT --------------------------------------------------

(deftest no-microphone-button-rendered
  (testing "per Lock 13 — no `🎙` button anywhere in the rail. We walk
            the rendered hiccup tree and assert no node carries the mic
            glyph"
    (registry/register-causa-handlers!)
    (install-llm-capture-fx!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (let [rail-tree  (copilot/ai-co-pilot-rail)
            panel-tree (copilot/ai-co-pilot-view)
            text-of-tree
            (fn [tree]
              (->> (hiccup-seq tree)
                   (filter string?)
                   (apply str)))]
        (is (not (re-find #"🎙" (text-of-tree rail-tree)))
            "no mic glyph in the rail")
        (is (not (re-find #"🎙" (text-of-tree panel-tree)))
            "no mic glyph in the panel view")))))

;; ---- (8) chip click routes panel-jump ----------------------------------

(deftest chip-click-routes-dispatch-id-target
  (testing ":rf.causa/copilot-chip-clicked dispatches the chip's target
            with the chip's value as arg"
    (registry/register-causa-handlers!)
    (install-llm-capture-fx!)
    (install-chip-capture!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/copilot-chip-clicked
                         {:chip-key :dispatch-id
                          :value    100
                          :target   :rf.causa/select-dispatch-id}]))
    (is (= [[:rf.causa/select-dispatch-id 100]]
           (chip-dispatches)))))

(deftest chip-click-routes-epoch-number-target
  (registry/register-causa-handlers!)
  (install-llm-capture-fx!)
  (install-chip-capture!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/copilot-chip-clicked
                       {:chip-key :epoch-number
                        :value    47
                        :target   :rf.causa/select-epoch}]))
  (is (= [[:rf.causa/select-epoch 47]] (chip-dispatches))))

;; ---- (9) rendered tree contracts ---------------------------------------

(deftest rail-renders-empty-state-when-conversation-empty
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (let [tree (copilot/ai-co-pilot-rail)]
      (is (some? (find-by-testid tree "rf-causa-copilot-rail")))
      (is (some? (find-by-testid tree "rf-causa-copilot-empty"))
          "empty-state surfaced when the buffer is empty")
      (is (nil? (find-by-testid tree "rf-causa-copilot-conversation"))
          "no conversation container when the buffer is empty"))))

(deftest rail-renders-conversation-when-non-empty
  (registry/register-causa-handlers!)
  (install-llm-capture-fx!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/copilot-submit-question
                       {:text "Why?" :parsed nil}])
    (let [tree (copilot/ai-co-pilot-rail)]
      (is (some? (find-by-testid tree "rf-causa-copilot-conversation")))
      (is (some? (find-by-testid tree "rf-causa-copilot-turn-question")))
      (is (some? (find-by-testid tree "rf-causa-copilot-turn-answer")))
      (is (nil? (find-by-testid tree "rf-causa-copilot-empty"))))))

(deftest rail-renders-input-row
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (let [tree (copilot/ai-co-pilot-rail)]
      (is (some? (find-by-testid tree "rf-causa-copilot-input")))
      (is (some? (find-by-testid tree "rf-causa-copilot-submit"))))))

(deftest cue-glyph-renders
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (let [tree (copilot/ai-co-pilot-cue)]
      (is (some? (find-by-testid tree "rf-causa-copilot-cue"))))))

;; ---- (10) tool catalogue is read-only ----------------------------------

(deftest registry-does-not-install-mutation-fx-for-copilot
  (testing "per spec §Tool / function calling — the co-pilot's effect
            surface does NOT include rewind / reset-frame-db / dispatch
            tools. The only fx the co-pilot registers is
            :rf.causa.fx/llm-stream."
    (registry/register-causa-handlers!)
    ;; The Phase 3 fxes :rf.causa.fx/restore-epoch and :rf.causa.fx/
    ;; reset-frame-db! exist (they back the Time Travel panel), but no
    ;; :rf.causa.copilot.fx/* mutation fx exists. The co-pilot's only
    ;; outbound effect is the llm-stream wire.
    (is (nil? (registrar/handler :fx :rf.causa.copilot.fx/restore-epoch)))
    (is (nil? (registrar/handler :fx :rf.causa.copilot.fx/reset-frame-db!)))
    (is (nil? (registrar/handler :fx :rf.causa.copilot.fx/dispatch)))
    (is (some? (registrar/handler :fx :rf.causa.fx/llm-stream))
        "the one outbound effect — llm-stream — is registered")))
