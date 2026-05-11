(ns realworld.tags-test
  "Headless tests for realworld.tags.

   Covers two things:
   - tag filter and feed-kind round-trip via the route slice (the
     home-page query helpers);
   - the `:realworld/tags` machine (the :data-region machine variant
     of Pattern-RemoteData) — load happy path + failure path
     + tag-shaped queries replacing the slice's `:loading?` /
     `:fetching?` booleans."
  (:require [re-frame.core :as rf]
            [realworld.tags]
            [realworld.test-helpers :as th])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn tag-query-test []
  (with-frame [f (rf/make-frame {:on-create [:app/initialise]})]
    (rf/dispatch-sync [:tags/apply-filter "clojure"] {:frame f})
    (assert (= "clojure" (:tag (rf/compute-sub [:rf.route/query] (rf/get-frame-db f)))))
    (rf/dispatch-sync [:home/show-your-feed] {:frame f})
    (assert (= "your" (:feed (rf/compute-sub [:rf.route/query] (rf/get-frame-db f)))))))

(defn- snapshot [db]
  (get-in db [:rf/machines :realworld/tags]))

(defn- has-tag?
  "Read the machine's :tags union against a frame's app-db (browserless
   form of `rf/has-tag?` — uses `compute-sub` instead of a reactive
   deref so the test runs in any CLJS host)."
  [frame tag]
  (rf/compute-sub [:rf/machine-has-tag? :realworld/tags tag]
                  (rf/get-frame-db frame)))

(defn tags-machine-load-test []
  ;; The :tags lifecycle — load happy path through the machine. The
  ;; assertions below are the SAME questions a slice-form reader would
  ;; ask, but each answer comes from a different surface:
  ;;
  ;;     SLICE FORM                              MACHINE FORM
  ;;     ----------                              ------------
  ;;     (:status slice) = :loaded               (:state snap)  = :loaded
  ;;     (:data slice)                           (-> snap :data :tags)
  ;;     :loading? (a derived boolean sub)       (has-tag? :tags/loading)
  (th/reg-canned-success! :rf.http/managed.canned-tags
                          {:tags ["intro" "demo" "clojure"]})
  (with-frame [f (rf/make-frame {:on-create    [:app/initialise]
                                 :fx-overrides {:rf.http/managed :rf.http/managed.canned-tags}})]
    ;; After :app/initialise → :tags/initialise → [:reset], the machine
    ;; sits at :idle with empty :data.
    (let [snap (snapshot (rf/get-frame-db f))]
      (assert (= :idle (:state snap)))
      (assert (= []    (get-in snap [:data :tags])))
      (assert (= 0     (get-in snap [:data :attempt]))))

    ;; First fetch: the canned-success stub resolves synchronously, so
    ;; we observe the machine in :loaded (not :loading) after the
    ;; dispatch returns. The slice's `:status :loaded` and `:data
    ;; ["intro" "demo" "clojure"]` are now the machine's `:state
    ;; :loaded` + `:data :tags`.
    (rf/dispatch-sync [:tags/load] {:frame f})
    (let [db   (rf/get-frame-db f)
          snap (snapshot db)]
      (assert (= :loaded (:state snap)))
      (assert (= ["intro" "demo" "clojure"]
                 (get-in snap [:data :tags])))
      (assert (= 1 (get-in snap [:data :attempt])))
      ;; tag-shaped queries — these replace the slice's `:tags/loading?`
      ;; / `:tags/fetching?` derived boolean subs.
      (assert (true?  (has-tag? f :tags/loaded)))
      (assert (false? (has-tag? f :tags/loading)))
      (assert (false? (has-tag? f :tags/in-flight)))
      ;; the `:tags/data` sub returns the items, same name a slice-form
      ;; reader would use; only the source changed.
      (assert (= ["intro" "demo" "clojure"]
                 (rf/compute-sub [:tags/data] db))))

    ;; Second fetch with prior data present: the region picks :fetching
    ;; (not :loading) so the sidebar doesn't blank out. The
    ;; canned-success stub resolves synchronously so we see :loaded
    ;; again at the end with the attempt counter bumped.
    (rf/dispatch-sync [:tags/load] {:frame f})
    (let [snap (snapshot (rf/get-frame-db f))]
      (assert (= :loaded (:state snap)))
      (assert (= 2 (get-in snap [:data :attempt]))))))

(defn tags-machine-failure-test []
  ;; Failure path — the :tags region lands in :error with the projected
  ;; failure message in :data, and the `:tags/error` derived sub picks
  ;; it up. Prior :data (if any) is preserved across the transition.
  (th/reg-canned-failure! :rf.http/managed.canned-tags-failure
                          :rf.http/http-5xx
                          {:status 500 :body "server error"})
  (with-frame [f (rf/make-frame {:on-create    [:app/initialise]
                                 :fx-overrides {:rf.http/managed :rf.http/managed.canned-tags-failure}})]
    (rf/dispatch-sync [:tags/load] {:frame f})
    (let [db   (rf/get-frame-db f)
          snap (snapshot db)]
      (assert (= :error (:state snap)))
      (assert (some? (get-in snap [:data :error])))
      (assert (some? (rf/compute-sub [:tags/error] db)))
      (assert (true?  (has-tag? f :tags/error)))
      (assert (false? (has-tag? f :tags/in-flight))))))
