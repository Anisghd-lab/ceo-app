# PROJECT_STATE.md — CEO (100% On-Device Android OS)

## 1. Objectifs Principaux & Vision
- Application Android native nommée **CEO** avec l'interface exacte de la vidéo (rail latéral rétractable + tiroir overlay animé, barre supérieure sans chevauchement, mascottes géométriques à badges, cartes `Company Org`, `Deliverable QA`, `Release Ship / Not yet`, `Board meeting`, notifications actionnables).
- **Zéro VPS requis** : tout le moteur multi-entreprises (`1 Sandbox = 1 Entreprise`, zéro métier hardcodé) tourne nativement sur Android via **Antigravity CLI embarqué (`agy`)** et/ou le routeur Multi-LLM direct.
- **Store de Plugins pré-configurés (façon ChatGPT Apps)** : Notion, Slack, GitHub, Gmail, Google Drive, Figma, Canva, Browser, Kittl, Product Design, TinyFish, Scite — prêts à être connectés dans le navigateur interne `CEO` et immédiatement utilisables par le CEO et tous les bots spécialisés.
- **Agents autonomes agentic orientés objectifs** : boucle multi-étapes Plan → Outils Plugins/Sandbox → Observation → Raisonnement → QA → Auto-correction → Approval Gate (`Ship / Not yet`).
- Compilation exclusivement sur **GitHub Actions** et distribution exclusive de l'APK sur **GitHub Releases** (`Anisghd-lab/ceo-app`).

## 2. État d'Avancement Réel
- [x] **Antigravity CLI Native Engine (`android/app/.../agy/`)** : binaire ARM64 vérifié SHA-512, superviseur `agy --hub`, parsing exact des événements `--output-format stream-json`, multi-comptes Google sans révocation, quotas `5h`/`Weekly` & par modèle.
- [x] **Store de Plugins (`14 plugins` dont Instagram & TikTok, `android/app/.../plugins/`)** :
  - `PluginRegistry.kt` : 14 plugins pré-configurés (`browser`, `notion`, `slack`, `github`, `gmail`, `gdrive`, `figma`, `canva`, `kittl`, `product_design`, `tinyfish`, `scite`, `instagram`, `tiktok`).
  - `PluginAuthManager.kt` : OAuth 2.0 PKCE + loopback local `127.0.0.1`, MCP Dynamic Client Registration + PKCE, détection automatique des sessions cookies dans le navigateur interne `CEO`, tokens/clés optionnels.
  - `PluginToolExecutor.kt` : exécution directe des outils recherche/lecture/prototypage/réseaux sociaux, et verrou d'approbation humaine (`Ship / Not yet`) pour toute action d'écriture externe (Slack, Gmail, GitHub Issue, Notion Page, Drive Upload, Instagram reply/publish, TikTok publish).
- [x] **Pipeline Multi-Agents à 5 Étapes (`3 à 8 spécialistes`) & Graphiques Vectoriels (`AutonomousEngine.kt`)** :
  - Découpage automatique par étapes selon le rôle réel : **Étape 1** Recherche/Tendances/Data → **Étape 2** Production PDF/Code + Graphiques SVG (`create_svg_graphic`) → **Étape 3** Audit QA (+ révision si `< 70`) → **Étape 4** Studio Marketing/Vidéo HTML (`product_design_prototype`) + Réseaux Sociaux/Ventes → **Étape 5** Mémoire + Gate `Ship`.
- [x] **Agents Hyper-Agentic (`AutonomousEngine.kt`)** :
  - Raisonnement réel du CEO via `agy CLI` dès qu'on clique sur CEO (`runCeoReasoningTurn`) + boucle multi-étapes ReAct à 6 pas sur le CEO et tous les bots (`runDirectBotChat`, `runFullMission`), avec cycle de révision automatique QA → Creator si score `< 70`.
- [x] **Signature Persistante & Auto-Updater In-App (`AppUpdateManager.kt`, CI Secrets)** :
  - Keystore RSA 2048-bit persistant stocké dans les Secrets GitHub (`ANDROID_KEYSTORE_BASE64`) pour que toutes les futures versions gardent exactement la même signature Android (mises à jour sur place sans désinstaller ni perdre les sandboxes/comptes).
  - Auto-vérification des Releases GitHub + téléchargement APK signé + lancement natif de l'installeur Android depuis Settings → App Updates.
- [x] **Agents 100% Autonomes, Raisonnement Live, File d'attente + Stop & Télémétrie (`BotInspectorDialog.kt`)** :
  - Chaque bot utilise ses plugins/tools/LLMs de façon autonome, rapporte ses résultats au CEO (`[Bot → CEO]`) et enregistre ses traces (plugin, tool, site URL, raisonnement).
  - Raisonnement du CEO affiché en temps réel dans le chat (pendant l'exécution + résumé déroulable juste après).
  - Composer avec **Stop immédiat (■)** et **File d'attente FIFO (`+ Queue`)** quand une tâche est déjà en cours.
  - Clic sur un bot → bannière télémétrie en direct + inspecteur détaillé (site visité cliquable, outil actif, raisonnement et historique chronologique).
- [x] **UI Corrigée, Sections Collapsables & Navigateur In-App** (`MainApp.kt`, `Sheets.kt`, `InAppBrowserDialog.kt`).
- [x] **Backend Python (`backend/`)** : 5/5 tests `pytest` verts.
- [x] **FreeLLMAPI (`freellmapi`) + Pool Modèles Gratuits (`v0.7.0`)** :
  - Fournisseur natif `freellmapi` avec URL de passerelle configurable (défaut `http://127.0.0.1:3001`), jeton Bearer optionnel en local, filtre automatique sur `available == true`, badge `FREE`, épingle `auto` / `fusion` et activation automatique du routeur gratuit.
- [x] **Usine Multi-Formats de Produits Numériques Vendables & Fix OpenRouter `null` (`v0.10.0`)** :
  - `DigitalProductFactory.kt` + `DigitalProductSkills.kt` : compétences natives 100 % on-device pour tous les formats commerciaux porteurs du marché (Gumroad, Etsy, Notion, LemonSqueezy, Creative Market, Whop) — `.xlsx` (Excel/Google Sheets avec formules), `.pptx` (PowerPoint/Slides 16:9), `.docx` (Word/Google Docs), `.png` (couvertures & mockups haute résolution), packs Notion importables (`.md` + `.csv`), `.html` (micro-apps & calculateurs interactifs), `.csv` UTF-8 BOM, `.pdf` livres illustrés, `.svg` vectoriels et `.zip` clients avec `00_START_HERE.txt`.
  - `OnDeviceSandbox.verifyArtifactProgrammatically` : audit QA natif de tous les formats (`.xlsx`, `.pptx`, `.docx`, `.png`, `.csv`, `.html`, `.pdf`, `.svg`, `.zip`).
  - `OnDeviceLLMClient.kt` : correction du bug `Réponse IA non structurée: null` sur `openrouter/free` (détection réelle de `JSONObject.NULL`, récupération `reasoning`/`reasoning_content`, désactivation de `response_format: json_object` sur les modèles gratuits incompatibles, retry automatique sans `response_format`).
- [x] **Moteur PDF Livre Éditorial Illustré & Rédaction Complète Anti-Fallback (`v0.9.1`)** :
  - `PdfBookRenderer.kt` + `OnDeviceSandbox.kt` : rendu natif Android `PdfDocument`/`Canvas` de livres & workbooks complets (couverture pleine page thématisée avec visuels vectoriels, Sommaire automatique, en-têtes de chapitres numérotés, Markdown complet avec gras/tableaux/citations/cases à cocher imprimables `- [ ]`/lignes d'exercice, diagrammes et graphiques vectoriels intégrés `bar_chart`, `roadmap`, `habit_tracker`, `kpi_grid`, `framework`, `checklist_card`, en-têtes/pieds de page numérotés).
  - `AutonomousEngine.kt` + `AgyRuntimeManager.kt` : timeout Producteur porté à `240s`, `maxTokens = 8192`, interdiction stricte de recopier le brief de mission, récolte systématique des fichiers créés sur disque, conversion automatique du Markdown brut en livre illustré, et synthèse IA directe multi-chapitres (`synthesizeAiBookPdf`) en cas de timeout au lieu du PDF de secours générique.
- [x] **Choix Interactifs en 1 Clic, Réponses Concises & Contrôles ChatGPT (`v0.9.0`)** :
  - `ChoiceExtractor.kt` + `AutonomousEngine.kt` : réponses normales brèves et directes (2–4 phrases) + protocole structuré `{"action":"choices","reply":"...","choices":[...]}` (et détection automatique sécurisée sur les questions à options numérotées/lettrées).
  - `InteractiveChoicesCard` (`VideoCards.kt`) : cartes d'options cliquables en un tap (cibles tactiles Android ≥ 48dp, sélection persistée en SQLite avec badge `Choisi`, envoi automatique du choix au CEO).
  - Contrôles par message (`MainApp.kt` + `LocalDatabase.kt`) : **Copier** (presse-papiers + retour visuel/haptique) et **Modifier & renvoyer** (édition inline avec recalcul propre de la suite) sur les messages utilisateur ; **Copier** et **Régénérer** sur les réponses IA.
- [x] **Exécution Arrière-Plan Permanente & Continuité (`v0.8.0`)** :
  - `AgentExecutionService.kt` : Foreground Service `dataSync` avec `PARTIAL_WAKE_LOCK`, notification persistante et bouton `Stop`, protégeant l'exécution des bots lorsque l'application passe en arrière-plan ou que l'écran est verrouillé.
  - Bouton **Allow 24/7 / Exempt Battery** dans `Sheets.kt` (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) + rafraîchissement automatique du chat pendant l'exécution en arrière-plan (`MainApp.kt`).
- [x] **Mémoire Long-Terme Multi-Semaines & Page Notion Dédiée par Entreprise (`v0.8.0`)** :
  - Chaque entreprise maintient son journal cumulatif `workspace/company_journal.md` partagé entre toutes les missions (aujourd'hui, la semaine suivante, etc.).
  - Dès que Notion est connecté, `AutonomousEngine.syncCompanyNotionLedger` crée automatiquement une page Notion dédiée **`[CEO] <Entreprise> — Suivi & Mémoire`** (`POST /v1/pages`) puis y ajoute en continu chaque nouvelle mission, score QA et livrables (`PATCH /v1/blocks/{id}/children`), avec rattrapage automatique de l'historique si Notion est connecté après coup.
- [x] **Correction Erreurs 404 & Sessions Cookies In-App Browser (`v0.8.0`)** :
  - `BrowserSessionReader.kt` : lecteur WebView hors-écran partageant exactement `CookieManager`, DOM Storage et rendu JS/SPA du navigateur interne CEO.
  - `PluginToolExecutor.kt` : collecte multi-domaines des cookies (`apex` + `www` + `sub`), bascule automatique sur WebView sur HTTP `404`/`403`/`401` ou HTML vide, récupération sur l'origine du site authentifié si une sous-route renvoie `404`, et repli transparent vers les sessions cookies pour GitHub/Notion/Figma/Canva.
- [x] **Watchdog Anti-Blocage, Interrogation CEO & Reprise (`v0.7.1`)** :
  - `AgyRuntimeManager.runPrompt` inclut un watchdog processus (`120s`) pour détruire tout processus `agy` bloqué.
  - `AutonomousEngine.runFullMission` exécute chaque bot sous `botStepTimeoutMs` (`75s`), déclenche automatiquement **`ceoInterrogateBlockedBot`** (`[CEO ⇄ Bot]`) en cas de timeout/blocage/arrêt et passe au bot suivant ou reprend exactement au bot N+1 sur commande **`continue` / `reprends` / `suivant`** grâce au checkpoint persistant (`MissionCheckpoint`).
  - Journal partagé **`workspace/team_feed.md`** + `buildSharedTeamContext` + récolte automatique des fichiers générés sur disque par `agy` pour que tous les bots voient en temps réel chaque artefact et action API.
  - Batch multi-outils (`{"action":"tools","calls":[...]}`) + bouton **« ⚡ Activate Free Auto »** FreeLLMAPI + repli automatique vers `"auto"` si un slot FreeLLMAPI renvoie `404`/`503`.
- [x] **UX Mobile Entreprises & Avatars Animés (`v0.7.0`)** :
  - **Glisser-déposer sur les entreprises (`SwipeableCompanyRow`)** : glisser à gauche pour supprimer l'entreprise + sa sandbox, glisser à droite pour archiver / restaurer dans la section repliable `Archivées`.
  - **Mascottes animées au travail réel (`BotMascot`)** : animation fluide (pulsation scale, micro-balancement, aura radar active et mouvement des yeux) déclenchée uniquement lorsque le bot est réellement en statut `working`.
- [x] **Audit Agents & Suite de Tests Automatisés Android (`v0.7.0`)** :
  - Correction `execBrowserSearch` (migration vers APIs JSON structurées Wikipédia OpenSearch + DuckDuckGo Instant Answer + HackerNews Algolia au lieu du scraping HTML bloqué).
  - Correction `AutonomousEngine.normalizeProposal` pour conserver les noms d'outils des plugins sur les bots spécialisés et enrichir automatiquement le `role` et le `system_prompt` individuel de chaque bot selon l'objectif de l'entreprise.
  - Ajout de la gouvernance **CEO Rôles & Prompts (`assign_bot_prompt` + `bot_mandates` + affichage dans `BotInspectorDialog`)** et exécution autonome de tous les spécialistes dans `runFullMission` (`Strategist`, `Producer`, `Publisher`/`Designer`, `Memory`, `Quality Auditor`) avec boucle ReAct à 6 étapes sur `agy` et LLM, et rapports `[Bot → CEO]`.
  - Ajout de 3 suites de tests Robolectric + MockWebServer (`AgyAndAccountManagerTest`, `PluginSystemTest`, `AutonomousBotsEndToEndTest`) exécutées automatiquement dans CI (`gradle testDebugUnitTest`).

## 3. Lacunes, Dettes Techniques & Points de Vigilance
- Certains services (Gmail, Google Drive, Slack, Canva, GitHub) nécessitent un `client_id` OAuth ou un token/PAT (saisissable via l'icône clé 🔑 de chaque plugin) si l'accès via session navigateur WebView seule ne suffit pas pour les appels REST directs.
- L'installation binaire `agy` cible les smartphones Android ARM64 (`arm64-v8a`).
- L'environnement ARM64 local ne peut pas lancer `aapt2` x86_64 pour `testDebugUnitTest` localement : la compilation APK et l'exécution de `testDebugUnitTest` s'effectuent sur **GitHub Actions (`ubuntu-latest`)**.
- Tests d'instrumentation UI Compose sur appareil Android physique / émulateur non encore inclus dans le pipeline CI.

## 4. Prochaines Étapes
1. Ajouter un planificateur `WorkManager` / Service Android en arrière-plan pour la veille et les réponses clients 24/7 même écran verrouillé (en utilisant les tokens OAuth Instagram/TikTok/Gmail).
2. Ajouter la sélection individuelle de modèle (`agy` / FreeLLMAPI / LLM) par bot depuis l'organigramme `Company Org`.
3. Ajouter des tests d'instrumentation Compose UI sur émulateur Android dans CI.
