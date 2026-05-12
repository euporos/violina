(ns seiten.werbe-email
  "Renders the `werbe_email` Directus singleton into a downloadable .eml.
   wrap-directus-user middleware on the parent /admin route attaches
   the Directus user when a session cookie is present; we still gate
   here because the middleware doesn't redirect on its own."
  (:require [cljstache.core :as tache]
            [clojure.string :as str]
            [db.schema :as s]
            [db.setup :as db]
            [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [macchiato.fs :as fs]
            [macchiato.util.response :as r]
            [psite-routing.core :as routing]
            [setup.directus-auth :as directus-auth]))

(def ^:private de-locale :de)

(defn- escape-html [s]
  (when s
    (-> s
        (str/replace "&" "&amp;")
        (str/replace "<" "&lt;")
        (str/replace ">" "&gt;")
        (str/replace "\"" "&quot;"))))

(defn- absolute-image
  "Builds an absolute image URL. `directus-url` setting is `/directus/`
   (a relative proxy path), which mail clients can't resolve — so we
   prepend the request's scheme+host."
  [req preset file-id]
  (let [path (d/image-by-preset preset file-id)]
    (if (re-find #"^https?://" path)
      path
      (routing/make-path-absolute req path))))

(defn- programme-url [req {:keys [id slug]}]
  (let [path (routing/reverse-match req :programm
                                    {:locale     (name de-locale)
                                     :programm-id   id
                                     :programm-slug (or slug (str id))})]
    (routing/make-path-absolute req path)))

(defn- program-block-html
  "One icon_block row in the body: programme image + linked title.
   Mirrors the festival template's icons_block structure so Outlook
   keeps its VML fallback happy."
  [req {:keys [bild titel] :as programme}]
  (let [img-src (absolute-image req "w600" bild)
        href    (programme-url req programme)
        title   (escape-html titel)]
    (str
     "<table class=\"icons_block\" role=\"presentation\""
     " style=\"mso-table-lspace:0;mso-table-rspace:0\" width=\"100%\""
     " cellspacing=\"0\" cellpadding=\"0\" border=\"0\"><tbody><tr>"
     "<td class=\"pad\" style=\"vertical-align:middle;color:#fff;"
     "font-family:Montserrat,'Trebuchet MS','Lucida Grande','Lucida Sans Unicode',"
     "'Lucida Sans',Tahoma,sans-serif;font-size:16px;text-align:left\">"
     "<table role=\"presentation\" style=\"mso-table-lspace:0;mso-table-rspace:0\""
     " width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"><tbody><tr>"
     "<td class=\"alignment\" style=\"vertical-align:middle;text-align:left\">"
     "<table class=\"icons-inner\""
     " style=\"mso-table-lspace:0;mso-table-rspace:0;display:inline-block;"
     "margin-right:-4px;padding-left:0;padding-right:0\""
     " role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\"><tbody><tr>"
     "<td style=\"vertical-align:middle;text-align:center;"
     "padding-top:5px;padding-bottom:5px;padding-left:25px;padding-right:25px\">"
     "<a href=\"" href "\">"
     "<img src=\"" img-src "\" alt=\"\" width=\"64\" height=\"64\""
     " style=\"display:block;height:64px;width:64px;border:0;margin:0 auto\""
     " moz-do-not-send=\"true\"></a></td>"
     "<td style=\"font-family:Montserrat,'Trebuchet MS','Lucida Grande',"
     "'Lucida Sans Unicode','Lucida Sans',Tahoma,sans-serif;font-size:16px;"
     "color:#fff;vertical-align:middle;text-align:left\">"
     "<a href=\"" href "\" style=\"color:#fff;text-decoration:none\">"
     title "</a></td>"
     "</tr></tbody></table></td></tr></tbody></table></td></tr></tbody></table>")))

(defn- mustache-context [req singleton programmes]
  {:hero_image      (absolute-image req "w1600" (:hero_image singleton))
   :thank_you       (:thank_you singleton)
   :salutation      (:salutation singleton)
   ;; main_text is a Rich-Text-HTML field in Directus — already valid
   ;; HTML, including paragraph tags and entities. Pass through raw.
   :main_text       (:main_text singleton)
   :details_heading (:details_heading singleton)
   :program_blocks  (str/join "\n" (map #(program-block-html req %) programmes))})

(defn- generate-eml
  "Builds an RFC822 message as a string. No `To:`/`Bcc:` — the user
   adds recipients in their mail client after opening the file."
  [subject html-body]
  (let [encoded-subject (str "=?UTF-8?B?"
                             (.toString (js/Buffer.from subject "utf8") "base64")
                             "?=")]
    (str "MIME-Version: 1.0\r\n"
         "From: info@violina-petrychenko.de\r\n"
         "Subject: " encoded-subject "\r\n"
         "Content-Type: text/html; charset=UTF-8\r\n"
         "Content-Transfer-Encoding: 8bit\r\n"
         "\r\n"
         html-body)))

(defn- singleton-query []
  {:select [s/werbe_email-id
            s/werbe_email-subject
            s/werbe_email-hero_image
            s/werbe_email-thank_you
            s/werbe_email-salutation
            s/werbe_email-main_text
            s/werbe_email-details_heading]
   :from   [s/werbe_email]
   :limit  1})

(defn- programmes-query [locale]
  {:select   [s/programme-id
              s/programme-slug
              s/programme-bild
              (db/localized s/programme-titel locale)]
   :from     [[:werbe_email_programme :wep]]
   :join     [[s/programme_t :programme] [:= :wep.programme_id s/programme-id]]
   :order-by [[:wep.sort :asc]]})

(defhandler handler [req]
  (if-not (:directus-user req)
    (directus-auth/directus-login-redirect req)
    (p/let [rows       (db/query (singleton-query))
          singleton  (first rows)
          programmes (db/query (programmes-query de-locale))
          html       (tache/render
                      (fs/slurp "resources/email-templates/werbe-email.html")
                      (mustache-context req singleton programmes))
          eml        (generate-eml (or (:subject singleton) "") html)]
      (-> (r/ok eml)
          (r/header "Content-Disposition" "attachment; filename=\"werbe-email.eml\"")
          (r/header "Cache-Control" "no-store")
          (r/content-type "message/rfc822")))))
