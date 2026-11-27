# CEO — Autonomous Multi-Sandbox Company App (100% On-Device Android)

**CEO** est une application **Android native autonome (zéro VPS requis)** reproduisant fidèlement l'UI de la vidéo :

- **Zéro VPS / Zéro serveur externe requis** : toute l'orchestration, la base SQLite WAL locale, les sandboxes isolées (`filesDir/sandboxes/<company_id>/`), la génération réelle de PDF/ZIP/fichiers, le routeur Multi-LLM direct et la vérification QA tournent **directement sur ton téléphone Android**.
- **1 Sandbox = 1 Entreprise (zéro métier hardcodé)** : tu décris librement n'importe quelle activité, le CEO conçoit et déploie dynamiquement l'organigramme exact de bots nécessaires (avec un bot **Quality Auditor** indépendant obligatoire).
- **Human-in-the-Loop** : chaque livrable vérifié ou appel API mutatif génère une carte `Release` (`Ship` / `Not yet`) et une notification Android actionnable.

## Utilisation rapide

1. Télécharge et installe l'APK depuis **GitHub Releases** (`Anisghd-lab/ceo-app`).
2. Ouvre l'icône **Settings** (en haut à droite) et ajoute ta clé API LLM (**OpenRouter**, **Gemini**, **xAI Grok**, **OpenAI**, **Anthropic**, **DeepSeek** ou **Groq**) puis clique **Save & Verify LLM Key**.
3. Crée une entreprise librement (`+` ou **Create a company**), valide l'équipe proposée par le CEO, et lance ta première mission dans le chat !
4. Ouvre ou partage les fichiers générés (PDF, ZIP, Markdown) directement depuis les cartes livrables via **File**.

> Le dossier `backend/` reste disponible en option pour ceux qui souhaitent aussi héberger un noyau Python déporté, mais l'app Android `CEO` fonctionne désormais à 100% de manière autonome sans aucun serveur.
