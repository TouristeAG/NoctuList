const TRANSLATIONS = {
  en: {
    title: "Guest list",
    loading: "Loading…",
    closedTitle: "This form is closed",
    closedBody:
      "The guest list is no longer accepting answers. If you think this is a mistake, ask the production team for a new link.",
    doneTitle: "Thank you",
    doneBody: "Your guest list has been sent. It will be reviewed before the night.",
    email: "Contact email",
    phone: "Emergency phone",
    peopleHeading: "People on the guest list",
    addPerson: "Add a person",
    namePlaceholder: "Name {n}",
    accessLabel: "Access for this person",
    disclaimer:
      "Accesses you tick are a request only. They are never guaranteed — confirmation comes on the night, depending on the show.",
    comments: "Comments",
    commentsPlaceholder: "Optional notes…",
    submit: "Send the guest list",
    needName: "Add at least one name.",
    tooLong: "The list is too long.",
    quotaExceeded: "You cannot request more than {n} “{name}” accesses.",
    sendError: "Could not send the guest list.",
    hostingError: "Firebase Hosting is not serving this site yet.",
    lang: "Language",
  },
  fr: {
    title: "Guestlist",
    loading: "Chargement…",
    closedTitle: "Ce formulaire est fermé",
    closedBody:
      "La guestlist n'accepte plus de réponses. Si vous pensez qu'il s'agit d'une erreur, demandez un nouveau lien à la production.",
    doneTitle: "Merci",
    doneBody: "Votre guestlist a été envoyée. Elle sera validée avant la soirée.",
    email: "E-mail de contact",
    phone: "Téléphone d'urgence",
    peopleHeading: "Personnes sur la guestlist",
    addPerson: "Ajouter une personne",
    namePlaceholder: "Nom {n}",
    accessLabel: "Demande d'accès pour cette personne",
    disclaimer:
      "Les accès cochés sont une demande uniquement. Ils ne sont jamais garantis — une confirmation sera donnée le soir, selon le contexte de la soirée.",
    comments: "Commentaires",
    commentsPlaceholder: "Notes optionnelles…",
    submit: "Envoyer la guestlist",
    needName: "Ajoutez au moins un nom.",
    tooLong: "La liste est trop longue.",
    quotaExceeded: "Impossible de demander plus de {n} accès « {name} ».",
    sendError: "Impossible d'envoyer la guestlist.",
    hostingError: "Firebase Hosting ne sert pas encore ce site.",
    lang: "Langue",
  },
  es: {
    title: "Guestlist",
    loading: "Cargando…",
    closedTitle: "Este formulario está cerrado",
    closedBody:
      "La guestlist ya no acepta respuestas. Si crees que es un error, pide un nuevo enlace a producción.",
    doneTitle: "Gracias",
    doneBody: "Tu guestlist se ha enviado. Se revisará antes de la noche.",
    email: "Correo de contacto",
    phone: "Teléfono de emergencia",
    peopleHeading: "Personas en la guestlist",
    addPerson: "Añadir una persona",
    namePlaceholder: "Nombre {n}",
    accessLabel: "Acceso para esta persona",
    disclaimer:
      "Los accesos que marques son solo una solicitud. Nunca están garantizados: la confirmación llega esa noche, según el contexto.",
    comments: "Comentarios",
    commentsPlaceholder: "Notas opcionales…",
    submit: "Enviar la guestlist",
    needName: "Añade al menos un nombre.",
    tooLong: "La lista es demasiado larga.",
    quotaExceeded: "No puedes solicitar más de {n} accesos « {name} ».",
    sendError: "No se pudo enviar la guestlist.",
    hostingError: "Firebase Hosting aún no está sirviendo este sitio.",
    lang: "Idioma",
  },
  de: {
    title: "Gästeliste",
    loading: "Laden…",
    closedTitle: "Dieses Formular ist geschlossen",
    closedBody:
      "Die Gästeliste nimmt keine Antworten mehr an. Wenn das ein Fehler ist, bitte die Produktion um einen neuen Link.",
    doneTitle: "Danke",
    doneBody: "Deine Gästeliste wurde gesendet. Sie wird vor dem Abend geprüft.",
    email: "Kontakt-E-Mail",
    phone: "Notfalltelefon",
    peopleHeading: "Personen auf der Gästeliste",
    addPerson: "Person hinzufügen",
    namePlaceholder: "Name {n}",
    accessLabel: "Zugang für diese Person",
    disclaimer:
      "Angekreuzte Zugänge sind nur eine Anfrage. Sie sind nie garantiert — die Bestätigung erfolgt am Abend, je nach Situation.",
    comments: "Kommentare",
    commentsPlaceholder: "Optionale Hinweise…",
    submit: "Gästeliste senden",
    needName: "Mindestens einen Namen hinzufügen.",
    tooLong: "Die Liste ist zu lang.",
    quotaExceeded: "Du kannst nicht mehr als {n} „{name}“-Zugänge anfragen.",
    sendError: "Die Gästeliste konnte nicht gesendet werden.",
    hostingError: "Firebase Hosting liefert diese Seite noch nicht.",
    lang: "Sprache",
  },
  it: {
    title: "Guest list",
    loading: "Caricamento…",
    closedTitle: "Questo modulo è chiuso",
    closedBody:
      "La guest list non accetta più risposte. Se pensi sia un errore, chiedi un nuovo link alla produzione.",
    doneTitle: "Grazie",
    doneBody: "La guest list è stata inviata. Sarà esaminata prima della serata.",
    email: "Email di contatto",
    phone: "Telefono di emergenza",
    peopleHeading: "Persone sulla guest list",
    addPerson: "Aggiungi una persona",
    namePlaceholder: "Nome {n}",
    accessLabel: "Accesso per questa persona",
    disclaimer:
      "Gli accessi selezionati sono solo una richiesta. Non sono mai garantiti: la conferma arriva in serata, in base al contesto.",
    comments: "Commenti",
    commentsPlaceholder: "Note opzionali…",
    submit: "Invia la guest list",
    needName: "Aggiungi almeno un nome.",
    tooLong: "L'elenco è troppo lungo.",
    quotaExceeded: "Non puoi richiedere più di {n} accessi « {name} ».",
    sendError: "Impossibile inviare la guest list.",
    hostingError: "Firebase Hosting non sta ancora servendo questo sito.",
    lang: "Lingua",
  },
};

const SUPPORTED = ["en", "fr", "es", "de", "it"];
const STORAGE_KEY = "noctulist-form-lang";

function browserLang() {
  const raw = (navigator.language || navigator.userLanguage || "en").slice(0, 2).toLowerCase();
  return SUPPORTED.includes(raw) ? raw : "en";
}

export function currentLang() {
  const stored = (localStorage.getItem(STORAGE_KEY) || "").toLowerCase();
  if (SUPPORTED.includes(stored)) return stored;
  return browserLang();
}

export function setLang(lang) {
  const next = SUPPORTED.includes(lang) ? lang : "en";
  localStorage.setItem(STORAGE_KEY, next);
  document.documentElement.lang = next;
  return next;
}

export function t(key, vars = {}) {
  const table = TRANSLATIONS[currentLang()] || TRANSLATIONS.en;
  let text = table[key] || TRANSLATIONS.en[key] || key;
  Object.entries(vars).forEach(([name, value]) => {
    text = text.replaceAll(`{${name}}`, String(value));
  });
  return text;
}

export function applyStaticI18n() {
  document.querySelectorAll("[data-i18n]").forEach((node) => {
    node.textContent = t(node.dataset.i18n);
  });
  document.querySelectorAll("[data-i18n-placeholder]").forEach((node) => {
    node.placeholder = t(node.dataset.i18nPlaceholder);
  });
  const title = document.getElementById("title");
  if (title && !title.dataset.locked) title.textContent = t("title");
  document.title = t("title");
}
