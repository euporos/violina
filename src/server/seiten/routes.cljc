(ns seiten.routes
  {:clj-kondo/config {:linters {:unresolved-namespace {:level :off}}}}
  #?(:node (:require
             [seiten.admin :as admin]
             [seiten.cds :as cds]
             [seiten.galerie :as galerie]
             [seiten.home :as home]
             [seiten.konzertliste :as konzertliste]
             [seiten.kuenstler :as kuenstler]
             [seiten.kontakt :as kontakt]
             [seiten.paedagogik :as paedagogik]
             [seiten.page :as page]
             #_[seiten.permanent :as permanent]
             [seiten.presse :as presse]
             [seiten.programme :as programme]
             [seiten.termine :as termine]
             [seiten.translate :as translate]
             [seiten.werbe-email :as werbe-email]
             [setup.directus-auth :as directus-auth]))
  #?(:clj  (:require      [psite-routing.macros :as prm])
     :cljs (:require-macros [psite-routing.macros :as prm])))

(def routes
  (#?(:node identity :default prm/routes-reduced-for-matching)
   [["/" {:name    :home
          :handler home/handler}]

    #_["/p/{id}" {:name       :permanent
                  :handler    permanent/handler
                  :parameters {:path [[:id permanent/param-spec]]}}]

    ["/termine"
     ["" {:name    :termine
          :handler termine/handler}]
     ["/{termin-id}" {:name       :termin-einzelansicht
                      :handler    termine/handler-einzel
                      :parameters {:path [[:termin-id :int]]}}]]

    ["/konzertliste" {:name    :konzertliste
                      :handler konzertliste/handler}]

    ["/programme"
     ["" {:name    :programme
          :handler programme/handler}]
     ["/{programm-id}-{programm-slug}" {:name       :programm
                                        :handler    programme/single-handler
                                        :directus   {:collection    "programme"
                                                     :params        {:programm-id "id"}
                                                     :static-params {:programm-slug "view"}}
                                        :parameters {:path [[:programm-id   :int]
                                                            [:programm-slug :string]]}}]]

    ["/kuenstler"
     ["" {:name    :kuenstler
          :handler kuenstler/handler}]
     ["/{kuenstler-id}-{kuenstler-slug}" {:name       :kuenstler-single
                                          :handler    kuenstler/single-handler
                                          :directus   {:collection    "kuenstler"
                                                       :params        {:kuenstler-id "id"}
                                                       :static-params {:kuenstler-slug "view"}}
                                          :parameters {:path [[:kuenstler-id   :int]
                                                              [:kuenstler-slug :string]]}}]]

    ["/discs"
     ["" {:name    :cds
          :handler cds/handler}]
     ["/{cd-id}" {:name       :cd
                  :handler    cds/single-handler
                  :directus   {:collection "cds"
                               :params     {:cd-id "id"}}
                  :parameters {:path [[:cd-id :int]]}}]]

    ["/galerie" {:name    :galerie
                 :handler galerie/handler}]

    ["/presse"
     ["" {:name    :presse
          :handler presse/handler}]
     ["/{artikel-id}" {:name       :presse-artikel
                       :handler    presse/single-handler
                       :directus   {:collection "presse"
                                    :params     {:artikel-id "id"}}
                       :parameters {:path [[:artikel-id :int]]}}]]

    ["/klavierunterricht-koeln" {:name    :paedagogik
                                 :handler paedagogik/handler}]

    ["/kontakt" {:name    :kontakt
                 :handler kontakt/handler}]

    ["/pg"
     ["/{page-id}-{page-slug}" {:handler    page/handler
                                :name       :single-page
                                :directus   {:collection    "sonderseiten"
                                             :params        {:page-id "id"}
                                             :static-params {:page-slug "view"}}
                                :parameters {:path [[:page-id  :int]
                                                    [:page-slug :string]]}}]]
    ["/admin" {:middleware [directus-auth/wrap-directus-user]}
     ["" {:handler admin/handler
          :name    :admin}]
     ["/werbe-email.eml" {:handler werbe-email/handler
                          :name    :werbe-email-eml}]
     ["/api/translation/gaps" {:handler translate/gaps-handler
                               :name    :admin-translation-gaps}]
     ["/api/translation/start" {:handler translate/start-handler
                                :name    :admin-translation-start}]
     ["/api/translation/job" {:handler translate/job-handler
                              :name    :admin-translation-job}]]]))
