(ns re-frame.freehand.typeahead-witness-cljs-test
  "FH-CTRL-020, the STRUCTURAL half — everything about the typeahead witness
  that is a fact about a VALUE rather than about a browser.

  The mounted half (`typeahead-witness-dom-cljs-test`) owns the top layer,
  the anchoring measurement, the focus ring and the live keyboard, because
  none of those exist outside a real engine. What lives here is the part a
  browser cannot make more true: the semantic tree the control declares,
  the ARIA wiring over it, the pure keyboard grammar, and the three
  fences — every one of them a comparison over ordinary frame data,
  asserted from one `.cljc` on the JVM and in Node.

  ## Latency is a variable here, not a wait

  The application's search performs no transport. It records the request
  and the reply prefix it was handed, and every row below dispatches the
  reply BY HAND, in the order the race requires — so a reply outrunning a
  keystroke is two lines rather than a timing hope, and the debounce is
  driven by delivering the `:dispatch-later` event the handler really
  scheduled instead of by waiting out a clock.

  ## The anchoring claim that IS structural

  Whether the list ends up under the input is a browser fact. Whether
  Freehand MEASURED anything to put it there is not: the list's whole style
  is lexical constants naming an anchor, so the tree can be asserted to
  carry no computed geometry at all. That is the half of the anchoring
  contract a headless render can prove, and it is the half that would rot
  silently the first time a later change reached for a rectangle."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.test :as t]
            [re-frame.freehand.typeahead-witness :as ta]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(def ctrl-020 (conf/fixture :FH-CTRL-020))

;; ---------------------------------------------------------------------------
;; Seams
;; ---------------------------------------------------------------------------

(def ^:private fid :rf/default)
(def ^:private doc-id :doc-1)
(def ^:private k [ta/typeahead-kind [:doc doc-id :reviewer]])
(def ^:private on-select [:desk/reviewer-chosen doc-id])

(defn- init! []
  (ta/register!)
  (ta/register-app!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :init-fn init!}))

(defn- app-db [] (frame/frame-app-db-value fid))
(defn- record [] (get-in (app-db) [ta/records-root k]))
(defn- shown [] (ta/visible-results (record)))
(defn- send! [ev] (rf/dispatch-sync ev {:frame fid}))
(defn- requests [] (get (app-db) ta/requests-key []))
(defn- selections [] (get (app-db) ta/selections-key []))

(defn- fresh-document!
  "The application state every row starts from: one document with no
  reviewer chosen and generation zero."
  []
  (frame/replace-app-db! fid {:doc {doc-id {:reviewer "" :reviewer-revision 0}}}))

(defn- render!
  "A discardable structural render bound to the frame, so the control's own
  `v/sub` reads resolve. It publishes nothing — the candidate is never
  committed — which is the shell's ordinary abandoned-render path."
  [form]
  (let [cand (cell/candidate (cell/cell :kit/probe) fid)]
    (cell/with-capture cand (fn [] (t/render form)))))

(defn- form-tree []
  (render! [ta/reviewer-form {:id doc-id :field-name (:field-name ctrl-020)}]))

(defn- part-of [node] (get (t/attrs node) :data-part))
(defn- node-with-part [tree p] (t/find tree #(= p (part-of %))))
(defn- nodes-with-part [tree p] (t/find-all tree #(= p (part-of %))))
(defn- attrs-of [tree p] (t/attrs (node-with-part tree p)))

;; ---------------------------------------------------------------------------
;; Driving the control the way a user and a network do
;; ---------------------------------------------------------------------------

(def ^:private never-fires-ms
  "The quiet period every row types with. `:dispatch-later` is the REAL
  framework effect, so a keystroke really does arm a real host timer — and
  a row that let one fire would have the clock racing its own assertions.
  The quiet period is therefore set past the run and each row delivers the
  delayed event by hand, which drives the production path with the clock as
  a parameter rather than as a wait."
  60000)

(defn- type!
  ([text] (type! text 0))
  ([text revision]
   (send! [:kit.ui.typeahead/typed k revision never-fires-ms
           [:desk/search-requested] text])))

(defn- fire-debounce!
  "The delayed dispatch the keystroke scheduled, delivered by hand — the
  same handler and the same arguments `:dispatch-later` would deliver."
  [token]
  (send! [:kit.ui.typeahead/due k token [:desk/search-requested]]))

(defn- reply!
  "The caller answering a request it was handed, by conj-ing its outcome
  onto the reply prefix the control gave it. Nothing here reaches into the
  control."
  [request outcome]
  (send! (conj (vec (:reply-to request)) outcome)))

(defn- press-at!
  "The intent the control's own `:on-key-down` site would dispatch from a
  render under generation `revision`."
  [revision intent]
  (send! [:kit.ui.typeahead/keyed k revision on-select intent]))

(defn- press! [intent] (press-at! 0 intent))

(defn- settled!
  "Type `query`, let its debounce fire, and answer it — the ordinary path to
  a control holding a visible result set."
  [query results]
  (type! query)
  (fire-debounce! (:token (record)))
  (reply! (last (requests)) {:results results}))

;; ===========================================================================
;; The declared surface — parts, ARIA, and the anchoring that is a constant
;; ===========================================================================

(deftest fh-ctrl-020-the-call-site-declares-one-instance-across-three-regions
  (testing "Per FH-CTRL-020: input, status and listbox are ONE instance.
            The input's `aria-controls` names the listbox's id, the
            listbox's options carry the ids the input's
            `aria-activedescendant` names, and every region is addressed by
            a public `data-part` — so a caller styles, and a tool reads,
            through the declared part contract rather than through the
            markup."
    (let [{:keys [parts component-id listbox-id input-id option-ids
                  results active-index query]} ctrl-020]
      (fresh-document!)
      (settled! query results)
      (press! :next)
      (let [tree  (form-tree)
            input (attrs-of tree "input")
            lst   (attrs-of tree "list")
            opts  (nodes-with-part tree "option")]

        (is (= (set parts)
               (into #{} (keep part-of) (t/find-all tree #(some? (part-of %)))))
            "every declared part is present and no undeclared one is")
        (is (= component-id (:data-component (attrs-of tree "root")))
            "and the root names the component")

        (is (= "combobox" (:role input)) "the input IS the combobox")
        (is (= input-id (:id input)))
        (is (= listbox-id (:aria-controls input)) "`aria-controls` names the listbox")
        (is (= listbox-id (:id lst)) "which really is that element's id")
        (is (= "listbox" (:role lst)))
        (is (= "true" (:aria-expanded input)) "and the input reports it open")

        (is (= option-ids (mapv #(:id (t/attrs %)) opts))
            "non-vacuous: there really are options, at the ids the fixture names")
        (is (= active-index
               (first (keep-indexed
                        (fn [i o] (when (= "true" (:aria-selected (t/attrs o))) i))
                        opts)))
            "exactly one option is selected, and it is the one the arrow moved to")
        (is (= (nth option-ids active-index) (:aria-activedescendant input))
            "and `aria-activedescendant` names that option's id — the whole
             reason focus never has to leave the input")
        (is (not= (nth option-ids 0) (nth option-ids active-index))
            "non-vacuous: the arrow moved off the option a fresh answer starts on")))))

(deftest fh-ctrl-020-the-anchoring-is-declared-and-nothing-is-measured
  (testing "Per FH-CTRL-020: the list is a `popover` whose desired state is
            the reserved `:rf.ui/top-layer` fact, and whose POSITION is CSS
            anchor positioning against the input's own `anchor-name`.

            The load-bearing assertion is the second one, and it is made
            over the style as a WHOLE VALUE. A style asserted key by key
            stays green when a measured offset is added beside the
            constants; asserted whole, the tree can be held to carrying no
            computed geometry at all — which is what makes `no observer to
            release` a structural fact rather than an intention."
    (let [{:keys [anchor-name popover-mode list-style input-style results query]}
          ctrl-020]
      (fresh-document!)
      (settled! query results)
      (let [tree  (form-tree)
            input (attrs-of tree "input")
            node  (node-with-part tree "list")
            lst   (t/attrs node)]

        (is (= input-style (:style input))
            "the input publishes the anchor and nothing else in its style")
        (is (= anchor-name (:anchor-name input-style))
            "non-vacuous: the anchor name really is this instance's")

        (is (= popover-mode (:popover lst)) "the list is a real popover")
        (is (= {:popover-open? true} (:rf.ui/top-layer node))
            "and its OPENNESS is the reserved desired-state fact, off :attrs")

        (is (= list-style (:style lst))
            "the list's whole style is the declared constant set")
        (is (= anchor-name (:position-anchor list-style))
            "which anchors it to THIS instance's input")
        (is (= [:margin]
               (mapv key (filter (fn [[_ value]] (re-find #"[0-9]" (str value)))
                                 (sort list-style))))
            "and the only digit anywhere in it is the UA centring margin
             being retired — a measured rectangle could not survive that")))))

(deftest fh-ctrl-020-a-closed-list-is-still-declared-and-still-anchored
  (testing "Per FH-CTRL-020: the popover element is rendered whether or not
            it is open, and its openness is the desired-state VALUE. That is
            what makes the platform's own dismissal observable through
            `:on-toggle` rather than the element vanishing out from under
            it, and it is why the anchoring does not have to be
            re-established on every open."
    (let [{:keys [results query anchor-name list-style]} ctrl-020]
      (fresh-document!)
      (settled! query results)
      (press! :cancel)
      (let [tree  (form-tree)
            node  (node-with-part tree "list")
            input (attrs-of tree "input")]
        (is (some? node) "the popover element is still there")
        (is (= {:popover-open? false} (:rf.ui/top-layer node))
            "desired CLOSED — false is a declaration, not an absence")
        (is (= list-style (:style (t/attrs node)))
            "still anchored, by the same constants")
        (is (= anchor-name (:position-anchor (:style (t/attrs node)))))
        (is (= "false" (:aria-expanded input)))
        (is (nil? (:aria-activedescendant input))
            "and nothing is active while nothing is shown")
        (is (= query (:value input))
            "while the draft SURVIVES the dismissal — the user is still
             editing; only the suggestions went away")))))

(deftest fh-ctrl-020-a-failed-search-offers-a-retry-and-says-so
  (testing "Per FH-CTRL-020: the status region is DERIVED from the record —
            never a local flag — and the error state is the one that adds a
            part. So `retry` is absent from every row above and present
            here, which is what makes the part roster a contract rather
            than a snapshot."
    (let [{:keys [error-part parts error-message query]} ctrl-020]
      (fresh-document!)
      (type! query)
      (fire-debounce! (:token (record)))
      (reply! (last (requests)) {:results [] :error error-message})
      (let [tree (form-tree)]
        (is (some? (node-with-part tree error-part))
            "the retry is offered")
        (is (not (contains? (set parts) error-part))
            "non-vacuous: it is exactly the part the settled rows do not have")
        (is (= (str "Search failed: " error-message)
               (t/text (node-with-part tree "status")))
            "and the status says what happened")))))

;; ===========================================================================
;; The keyboard grammar, proven by CALLING it
;; ===========================================================================

(deftest fh-ctrl-020-the-keyboard-grammar-is-pure-and-ime-aware
  (testing "Per FH-CTRL-020: the whole grammar is a function of two scalars,
            so it is provable by CALLING it — no browser, no host event and
            no mount. Enter and Escape are DELEGATED to the kit's own
            `c/key-intent` rather than re-decided, and the arrows are
            withheld during a composition for the same reason Enter is:
            while a candidate window is open ArrowDown moves through the
            input method's candidates, and a control that steals it there
            has the same defect one key over."
    (let [{:keys [key-table]} ctrl-020]
      (doseq [{:keys [key composing? intent]} key-table]
        (is (= intent (ta/key-intent key composing?))
            (str "(key-intent " (pr-str key) " " composing? ") is " (pr-str intent))))
      (is (some #(and (:composing? %) (nil? (:intent %))) key-table)
          "non-vacuous: the table really contains a key withheld by a composition")
      (is (some #(and (not (:composing? %)) (some? (:intent %))) key-table)
          "and the same key producing an intent when nothing is composing"))))

;; ===========================================================================
;; The three fences
;; ===========================================================================

(deftest fh-ctrl-020-a-superseded-schedule-asks-nothing
  (testing "Per FH-CTRL-020: two keystrokes inside one quiet period arm two
            delayed dispatches. When the first fires it is no longer
            current, so it asks nothing and leaves nothing behind. There is
            no timer handle, no channel and no cancel call — a superseded
            schedule is inert BY COMPARISON, which is the only kind of
            cancellation that cannot leak."
    (let [{:keys [query]} ctrl-020]
      (fresh-document!)
      (type! (subs query 0 1))
      (let [first-token (:token (record))]
        (type! query)
        (let [second-token (:token (record))]
          (is (not= first-token second-token) "the second keystroke moved the token")
          (fire-debounce! first-token)
          (is (empty? (requests)) "the superseded schedule asked nothing")
          (fire-debounce! second-token)
          (is (= 1 (count (requests))) "and only the current one reached the caller")
          (is (= query (:query (first (requests))))
              "carrying what the user had actually typed"))))))

(deftest fh-ctrl-020-a-superseded-reply-is-inert-in-both-directions
  (testing "Per FH-CTRL-020: a reply lands only when it names the request
            that is in flight, and a settled set is shown only while it
            still answers what the user has typed. Two fences, and the
            second is what makes a late settle unable to rewrite the input:
            the reply has nowhere to write that the typing lives."
    (let [{:keys [results query later-query]} ctrl-020]
      (fresh-document!)
      (type! (subs query 0 1))
      (fire-debounce! (:token (record)))
      (let [slow (last (requests))]
        ;; The user types on. The keystroke REVOKES the in-flight claim the
        ;; slow request named, so no acceptance gap opens at all.
        (type! query)
        (is (nil? (:in-flight (record)))
            "the keystroke revoked the claim its predecessor held")
        (reply! slow {:results results})
        (is (empty? (shown)) "and the slow answer landed nowhere")

        (fire-debounce! (:token (record)))
        (reply! (last (requests)) {:results results})
        (is (= results (shown))
            "non-vacuous: a reply that DOES name the live request lands")

        (type! later-query)
        (is (empty? (shown))
            "a settled set stops being the answer the moment the query moves")
        (is (seq (:results (record)))
            "though it was not erased — the tag it carries is the query it
             answers, which is why a late reply cannot rewrite the input")))))

(deftest fh-ctrl-020-a-retry-supersedes-the-failure-it-retries
  (testing "Per FH-CTRL-020: a retry is a NEW request with a new token,
            which is exactly what makes the failed request's late answer
            inert rather than a resurrection."
    (let [{:keys [results retry-results query error-message]} ctrl-020]
      (fresh-document!)
      (type! query)
      (fire-debounce! (:token (record)))
      (let [failed (last (requests))]
        (reply! failed {:results [] :error error-message})
        (is (= error-message (:error (record))) "the failure is on the record")

        (send! [:kit.ui.typeahead/retried k [:desk/search-requested]])
        (is (nil? (:error (record))) "the retry cleared it")
        (let [retried (last (requests))]
          (is (not= (:reply-to failed) (:reply-to retried))
              "and asked under a NEW correlation")
          (reply! failed {:results results})
          (is (empty? (shown))
              "so the failed request's late answer lands nowhere")
          (reply! retried {:results retry-results})
          (is (= retry-results (shown))
              "while the retry's own answer does"))))))

(deftest fh-ctrl-020-the-generation-fence-makes-a-superseded-draft-invisible
  (testing "Per FH-CTRL-020: the draft belongs to the caller's generation. A
            caller that rejects it by advancing the generation — while
            reasserting a value equal to the one it had, the case
            value-equality is provably blind to — stops seeing the draft at
            once, and work arriving under the old generation speaks for a
            generation nobody is displaying."
    (let [{:keys [results query first-value]} ctrl-020]
      (fresh-document!)
      (settled! query results)
      (is (= query (:query (record))) "non-vacuous: there really is a draft")

      ;; The caller makes a new baseline decision, which advances the
      ;; generation the render is under.
      (send! [:desk/reviewer-chosen doc-id first-value])
      (is (= 1 (get-in (app-db) [:doc doc-id :reviewer-revision]))
          "the generation moved")

      (let [input (attrs-of (form-tree) "input")]
        (is (= first-value (:value input))
            "the input shows the caller's baseline, not the stale draft")
        (is (= "false" (:aria-expanded input))
            "and no list is open under a generation nobody displays"))

      (is (some? (:query (record)))
          "the record was not erased — invisible, not destroyed, which is why
           the rejection costs no write during render")

      (let [before (count (selections))]
        ;; The render is now under generation 1 while the record was born
        ;; under 0, so this is the commit a click on the still-live list
        ;; would produce — and it speaks for a generation nobody displays.
        (press-at! 1 :commit)
        (is (= before (count (selections)))
            "a commit under the superseded generation reaches nobody")
        (is (some? (record))
            "and it moved nothing — a refused commit is not a teardown")

        (testing "the CONTROL for that refusal: the same press under the
                  generation the record WAS born under still commits, so
                  the row above cannot be green for want of anything to
                  commit"
          (press-at! 0 :commit)
          (is (= (inc before) (count (selections)))))))))

(deftest fh-ctrl-020-open-query-and-selection-are-ordinary-frame-data
  (testing "Per FH-CTRL-020: every fact the control holds is readable
            through an ordinary subscription with NOTHING mounted — the
            open flag, the query, the highlight and the settled results.
            There is no view state, no host slot and no second store, which
            is what makes the whole async surface provable headlessly and
            what makes the release below exact."
    (let [{:keys [results active-index query]} ctrl-020]
      (fresh-document!)
      (settled! query results)
      (press! :next)
      (let [status (rf/subscribe-once [:kit.ui.typeahead/status k 0] {:frame fid})
            text   (rf/subscribe-once [:kit.ui.typeahead/text k 0 ""] {:frame fid})]
        (is (true? (:open? status)))
        (is (= active-index (:active status)))
        (is (= results (:results status)))
        (is (= query text))
        (is (false? (:pending? status)) "nothing is in flight")
        (is (false? (:stale? status)) "and the answer answers the question")))))

(deftest fh-ctrl-020-cleanup-follows-the-owner-and-is-total
  (testing "Per FH-CTRL-020: one end event, dispatched from whichever exit
            path actually happened. After it the record is GONE — through
            the kit's own `c/release` rather than a second spelling of
            `dissoc` — a scheduled search finds nothing, a late reply finds
            nothing, and releasing twice is the same as releasing once."
    (let [{:keys [results query]} ctrl-020]
      (fresh-document!)
      (settled! query results)
      (let [live      (last (requests))
            asked     (count (requests))
            stale-tok (:token (record))]
        (is (some? (record)) "non-vacuous: there is something to release")

        (send! [:desk/closed k])
        (is (nil? (record)) "the record is gone")
        (is (empty? (get (app-db) ta/records-root))
            "and the control's root holds nothing")

        (reply! live {:results results})
        (is (nil? (record)) "a reply arriving after the release lands nowhere")
        (fire-debounce! stale-tok)
        (is (nil? (record)) "and so does the schedule it left armed")
        (is (= asked (count (requests)))
            "which asked nothing on its way past")

        (send! [:desk/closed k])
        (is (nil? (record)) "releasing twice is releasing once")))))
