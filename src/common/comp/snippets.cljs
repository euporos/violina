(ns comp.snippets
  (:require
   [psite-i18n.core :refer [defsnips]]
   [comp.localization :as loc]))

(defsnips loc/fallback
  [[datenschutzerklärung
    {:de "Datenschutzerklärung"
     :en "privacy policy"
     :uk "Захист данних "}]
   [telefonnummer
    {:de "Telefonnummer"
     :en "Phone"
     :uk "Номер телефону"}]
   [name-snip
    {:de "Name"
     :en "Name"
     :uk "Ім'я "}]
   [ich-akzeptiere
    {:de "Ich akzeptiere die"
     :en "I accept the"
     :uk "Погодитися"}]
   [bitte-nicht-ausfuellen
    {:de "Bitte füllen Sie dieses Feld nicht aus."
     :en "Please do not fill out this field."
     :uk "Заповніть будь ласка це поле"}]
   [bitte-email
    {:de "Bitte geben Sie ein gültige Emailadresse ein."
     :en "Please enter a valid email address."
     :uk "вкажіть свій адрес електронної пошти"}]
   [bitte-name
    {:de "Bitte geben Sie Ihren Namen ein."
     :en "Please Enter your name."
     :uk "Вкажіть своє ім'я"}]
   [additional-message
    {:de "Zusätzliche Nachricht"
     :en "Additional message"
     :uk "Додаткове повідомлення"}]
   [datenschutzregelung
    {:de "Bitte akzeptieren Sie die Datenschutzregelung."
     :en "Please accept the privacy policy."
     :uk "Будь ласка дайте згоду з правилами безпеки сайту"}]
   [mehrerfahren
    {:de "mehr erfahren"
     :en "learn more"
     :uk "дізнатися більше"}]
   [impressum
    {:de "Impressum"
     :en "Imprint"
     :uk "Технічна інформація "}]
   [datenschutz
    {:de "Datenschutz"
     :en "Privacy"
     :uk "Захист даних"}]
   [kontakt
    {:de "Kontakt"
     :en "Contact"
     :uk "Контакт"
     :it "Contatto"}]
   [youtube-consent-text
    {:de "Dieses Video wird von YouTube gehostet. Durch Klicken auf \"Video laden\" akzeptieren Sie die "
     :en "This video is hosted by YouTube. By clicking \"Load video\" you accept the "
     :uk "Це відео розміщено на YouTube. Натискаючи \"Завантажити відео\", ви приймаєте "}]
   [google-privacy-policy
    {:de "Datenschutzerklärung von Google"
     :en "Google Privacy Policy"
     :uk "Політику конфіденційності Google"}]
   [load-video
    {:de "Video laden"
     :en "Load video"
     :uk "Завантажити відео"}]])

(defsnips loc/fallback
  [[home
    {:en "Home"
     :de "Startseite"
     :uk "Головна"
     :it "Pagina iniziale"}]

   [termine
    {:en "Calendar"
     :de "Termine"
     :uk "Календар"
     :it "Calendario"}]

   [konzertliste
    {:en "Concerts"
     :de "Konzertliste"
     :uk "Список концертів"
     :it "Concerti"}]

   [kuenstler
    {:en "Musicians"
     :de "Musiker"
     :uk "Музиканти"
     :it "Musicisti"}]

   [programme
    {:en "Programs"
     :de "Programme"
     :uk "Програми"
     :it "Programmi"}]

   [cds
    {:en "CDs"
     :de "CDs"
     :uk "Диски"
     :it "CD"}]

   [galerie
    {:en "Gallery"
     :de "Galerie"
     :uk "Галерея"
     :it "Galleria"}]

   [presse
    {:en "Press"
     :de "Presse"
     :uk "Преса"
     :it "Pressa"}]

   [paedagogik
    {:en "Teaching"
     :de "Pädagogik"
     :uk "Педагогіка"
     :it "Insegnamento"}]])

(defsnips loc/fallback
  [[violina
    {:de "Violina" :en "Violina" :uk "Віоліна"}]
   [petrychenko
    {:de "Petrychenko" :en "Petrychenko" :uk "Петриченко"}]
   [mehr
    {:de "mehr" :en "more" :uk "більше" :it "più"}]
   [konzert
    {:de "Konzert" :en "concert" :uk "концерт" :it "concerto"}]
   [programm
    {:de "Programm" :en "Program" :uk "Програма" :it "Programma"}]
   [eintritt
    {:de "Eintritt" :en "Fee" :uk "Вхід" :it "Entrata"}]
   [veranstalterlink
    {:de "Infos/Karten beim Veranstalter"
     :en "More info/Tickets"
     :uk "Більше інформації та квитки"
     :it "un più informazione"}]
   [abgesagt
    {:de "fällt aus" :en "cancelled" :uk "відмінено" :it "annullato"}]
   [aeltere-termine
    {:de "Ältere Termine anzeigen"
     :en "Show older concerts"
     :uk "Показати старіші концерти"
     :it "Mostra concerti più vecchi"}]
   [kein-konzert-mehr-verpassen
    {:de "Kein Konzert mehr verpassen?"
     :en "Don't want to miss another concert?"
     :uk "Не пропустити концерт?"}]
   [subscribe-to-ical
    {:de "Abonnieren Sie hier meinen Kalender"
     :en "Subscribe to my calendar here"
     :uk "Підпишіться на мій календар"}]
   [home-konzerte
    {:de "Die nächsten Konzerte"
     :en "Upcoming Concerts"
     :uk "Наступні концерти"
     :it "Prossimi eventi"}]
   [home-presse
    {:de "Aktuelle Pressestimmen"
     :en "The Current Press"
     :uk "Актуальна преса"
     :it "La pressa attuale"}]
   [meine-neue-cd
    {:de "Meine Neue CD – jetzt bestellen"
     :en "My new Disc – order here"
     :uk "Новий диск – замовити зараз"
     :it "La mia nuova CD – prenotare ora"}]
   [zdf
    {:de "Violina Petrychenko im ZDF Morgenmagazin"
     :en "Violina on German State TV"
     :uk "Віоліна Петриченко на центральному телебаченні Німеччини"
     :it "Violina Petrychenko su ZDF"}]
   [termine-programm
    {:de "Erleben Sie dieses Programm an folgenden Terminen:"
     :en "Experience this program during these concerts:"
     :uk "Почути цю програму можна:"
     :it "Sentire questo programma nelle seguenti date"}]
   [nochkeinetermine-programm
    {:de "Es gibt noch keine Konzerte mit diesem Programm"
     :en "There are no concerts featuring this program"
     :uk "Ще немає концертів з цією програмою"
     :it "Non ci sono concerti con questo programma"}]
   [beiinteresse
    {:de "Bei Interesse an diesem Programm "
     :en "If you're interested in this program "
     :uk "Якщо ви цікавитися цією програмою, "
     :it "Se sa interessato a questo programma "}]
   [kontaktierensiemich
    {:de "kontaktieren Sie mich"
     :en "feel free to contact me"
     :uk "зверніться будь ласка до мене"
     :it "contattami"}]])
