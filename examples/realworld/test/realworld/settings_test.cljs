(ns realworld.settings-test
  "Headless test for realworld.settings — settings save propagates new
   user data into the auth slice. Extracted from realworld/settings.cljs
   under rf2-4v73."
  (:require [re-frame.core :as rf]
            [realworld.settings])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn settings-test []
  (rf/reg-fx :http.canned-settings-save
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [on-success]}]
      (when on-success
        (rf/dispatch
          (conj on-success {:user {:email "alice@example.com"
                                   :token "jwt-2"
                                   :username "alice"
                                   :bio "New bio"
                                   :image nil}})
          {:frame frame}))))

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:http :http.canned-settings-save}})]
    (rf/dispatch-sync [:auth/store-session {:email "alice@example.com"
                                            :token "jwt-1"
                                            :username "alice"
                                            :bio nil
                                            :image nil}]
                      {:frame f})
    (rf/dispatch-sync [:settings/load] {:frame f})
    (rf/dispatch-sync [:settings/edit-field :bio "New bio"] {:frame f})
    (rf/dispatch-sync [:settings/submit] {:frame f})
    (assert (= "New bio" (get-in (rf/get-frame-db f) [:auth :user :bio])))))
