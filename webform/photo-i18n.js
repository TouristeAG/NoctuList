// Strings for the public profile-photo page. Shares the language choice with the guest-list form.
const TRANSLATIONS = {
  en: {
    title: "Profile photo",
    loading: "Loading…",
    closedTitle: "This link has expired",
    closedBody: "This photo request is no longer open. Ask the team for a new link.",
    doneTitle: "Thank you",
    doneBody: "Your photo has been sent and will appear on your profile in a moment. You can close this page.",
    intro: "Choose a clear photo of your face. It will appear on your profile.",
    forName: "For {name}",
    choose: "Choose a photo",
    change: "Choose another photo",
    upload: "Upload",
    uploading: "Uploading…",
    notImage: "This file is not an image.",
    unreadable: "This photo could not be read. Try another one.",
    sendError: "The photo could not be sent. Try again.",
    hostingError: "Firebase Hosting is not serving this site yet.",
    lang: "Language",
  },
  fr: {
    title: "Photo de profil",
    loading: "Chargement…",
    closedTitle: "Ce lien a expiré",
    closedBody: "Cette demande de photo n'est plus ouverte. Demandez un nouveau lien à l'équipe.",
    doneTitle: "Merci",
    doneBody: "Votre photo a été envoyée et apparaîtra sur votre profil dans un instant. Vous pouvez fermer cette page.",
    intro: "Choisissez une photo nette de votre visage. Elle apparaîtra sur votre profil.",
    forName: "Pour {name}",
    choose: "Choisir une photo",
    change: "Choisir une autre photo",
    upload: "Envoyer",
    uploading: "Envoi en cours…",
    notImage: "Ce fichier n'est pas une image.",
    unreadable: "Impossible de lire cette photo. Essayez-en une autre.",
    sendError: "La photo n'a pas pu être envoyée. Réessayez.",
    hostingError: "Firebase Hosting ne sert pas encore ce site.",
    lang: "Langue",
  },
  es: {
    title: "Foto de perfil",
    loading: "Cargando…",
    closedTitle: "Este enlace ha caducado",
    closedBody: "Esta solicitud de foto ya no está abierta. Pide un nuevo enlace al equipo.",
    doneTitle: "Gracias",
    doneBody: "Tu foto se ha enviado y aparecerá en tu perfil en un momento. Puedes cerrar esta página.",
    intro: "Elige una foto nítida de tu cara. Aparecerá en tu perfil.",
    forName: "Para {name}",
    choose: "Elegir una foto",
    change: "Elegir otra foto",
    upload: "Enviar",
    uploading: "Enviando…",
    notImage: "Este archivo no es una imagen.",
    unreadable: "No se pudo leer esta foto. Prueba con otra.",
    sendError: "No se pudo enviar la foto. Inténtalo de nuevo.",
    hostingError: "Firebase Hosting todavía no sirve este sitio.",
    lang: "Idioma",
  },
  de: {
    title: "Profilfoto",
    loading: "Wird geladen…",
    closedTitle: "Dieser Link ist abgelaufen",
    closedBody: "Diese Fotoanfrage ist nicht mehr offen. Bitte das Team um einen neuen Link.",
    doneTitle: "Danke",
    doneBody: "Dein Foto wurde gesendet und erscheint gleich auf deinem Profil. Du kannst diese Seite schließen.",
    intro: "Wähle ein deutliches Foto deines Gesichts. Es erscheint auf deinem Profil.",
    forName: "Für {name}",
    choose: "Foto auswählen",
    change: "Anderes Foto auswählen",
    upload: "Hochladen",
    uploading: "Wird hochgeladen…",
    notImage: "Diese Datei ist kein Bild.",
    unreadable: "Dieses Foto konnte nicht gelesen werden. Versuche ein anderes.",
    sendError: "Das Foto konnte nicht gesendet werden. Bitte erneut versuchen.",
    hostingError: "Firebase Hosting stellt diese Seite noch nicht bereit.",
    lang: "Sprache",
  },
  it: {
    title: "Foto profilo",
    loading: "Caricamento…",
    closedTitle: "Questo link è scaduto",
    closedBody: "Questa richiesta di foto non è più aperta. Chiedi un nuovo link al team.",
    doneTitle: "Grazie",
    doneBody: "La tua foto è stata inviata e apparirà a breve sul tuo profilo. Puoi chiudere questa pagina.",
    intro: "Scegli una foto nitida del tuo viso. Apparirà sul tuo profilo.",
    forName: "Per {name}",
    choose: "Scegli una foto",
    change: "Scegli un'altra foto",
    upload: "Invia",
    uploading: "Invio in corso…",
    notImage: "Questo file non è un'immagine.",
    unreadable: "Impossibile leggere questa foto. Provane un'altra.",
    sendError: "Impossibile inviare la foto. Riprova.",
    hostingError: "Firebase Hosting non sta ancora servendo questo sito.",
    lang: "Lingua",
  },
};

const SUPPORTED = ["en", "fr", "es", "de", "it"];
const STORAGE_KEY = "noctulist-form-lang";

function readStored() {
  try {
    return (localStorage.getItem(STORAGE_KEY) || "").toLowerCase();
  } catch {
    return "";
  }
}

function browserLang() {
  const raw = (navigator.language || "en").slice(0, 2).toLowerCase();
  return SUPPORTED.includes(raw) ? raw : "en";
}

export function currentLang() {
  const stored = readStored();
  return SUPPORTED.includes(stored) ? stored : browserLang();
}

export function setLang(lang) {
  const next = SUPPORTED.includes(lang) ? lang : "en";
  try {
    localStorage.setItem(STORAGE_KEY, next);
  } catch {
    /* private mode: keep the choice for this visit only */
  }
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
  document.querySelectorAll("[data-i18n-aria]").forEach((node) => {
    node.setAttribute("aria-label", t(node.dataset.i18nAria));
  });
  document.title = t("title");
  if (window.NoctuTheme) window.NoctuTheme.refreshLabels();
}
