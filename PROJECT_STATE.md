# PROJECT_STATE.md — CEO (100% On-Device Android OS)

## 1. Objectifs Principaux & Vision
- Application Android native nommée **CEO** avec l'interface exacte de la vidéo (rail latéral rétractable + tiroir overlay animé, barre supérieure sans chevauchement, mascottes géométriques à badges, cartes `Company Org`, `Deliverable QA`, `Release Ship / Not yet`, `Board meeting`, notifications actionnables).
- **Zéro VPS requis** : tout le moteur multi-entreprises (`1 Sandbox = 1 Entreprise`, zéro métier hardcodé) tourne nativement sur Android via **Antigravity CLI embarqué (`agy`)** et/ou le routeur Multi-LLM direct.
- **Store de Plugins pré-configurés (façon ChatGPT Apps)** : Notion, Slack, GitHub, Gmail, Google Drive, Figma, Canva, Browser, Kittl, Product Design, TinyFish, Scite — prêts à être connectés dans le navigateur interne `CEO` et immédiatement utilisables par le CEO et tous les bots spécialisés.
- **Agents autonomes agentic orientés objectifs** : boucle multi-étapes Plan → Outils Plugins/Sandbox → Observation → Raisonnement → QA → Auto-correction → Approval Gate (`Ship / Not yet`).
- Compilation exclusivement sur **GitHub Actions** et distribution exclusive de l'APK sur **GitHub Releases** (`Anisghd-lab/ceo-app`).

## 2. État d'Avancement Réel
- [x] **Antigravity CLI Native Engine (`android/app/.../agy/`)** : binaire ARM64 vérifié SHA-512, superviseur `agy --hub`, parsing exact des événements `--output-format stream-json`, multi-comptes Google sans révocation, quotas `5h`/`Weekly` & par modèle.
- [x] **Store de Plugins & Exécuteur Unifié (`android/app/.../plugins/`)** :
  - `PluginRegistry.kt` : 12 plugins pré-configurés (`browser`, `notion`, `slack`, `github`, `gmail`, `gdrive`, `figma`, `canva`, `kittl`, `product_design`, `tinyfish`, `scite`).
  - `PluginAuthManager.kt` : OAuth 2.0 PKCE + loopback local `127.0.0.1`, MCP Dynamic Client Registration + PKCE, détection automatique des sessions cookies dans le navigateur interne `CEO`, tokens/clés optionnels.
  - `PluginToolExecutor.kt` : exécution directe des outils lecture/recherche/prototypage HTML, et verrou d'approbation humaine (`Ship / Not yet`) pour toute action d'écriture externe (Slack, Gmail, GitHub Issue, Notion Page, Drive Upload).
- [x] **Agents Hyper-Agentic (`AutonomousEngine.kt`)** :
  - Raisonnement réel du CEO via `agy CLI` dès qu'on clique sur CEO (`runCeoReasoningTurn`) + boucle multi-étapes ReAct à 6 pas sur le CEO et tous les bots (`runDirectBotChat`, `runFullMission`), avec cycle de révision automatique QA → Creator si score `< 70`.
- [x] **Signature Persistante & Auto-Updater In-App (`AppUpdateManager.kt`, CI Secrets)** :
  - Keystore RSA 2048-bit persistant stocké dans les Secrets GitHub (`ANDROID_KEYSTORE_BASE64`) pour que toutes les futures versions gardent exactement la même signature Android (mises à jour sur place sans désinstaller ni perdre les sandboxes/comptes).
  - Auto-vérification des Releases GitHub + téléchargement APK signé + lancement natif de l'installeur Android depuis Settings → App Updates.
- [x] **UI Corrigée, Sections Collapsables & Navigateur In-App** (`MainApp.kt`, `Sheets.kt`, `InAppBrowserDialog.kt`).
- [x] **Backend Python (`backend/`)** : 5/5 tests `pytest` verts sur CI.

## 3. Lacunes, Dettes Techniques & Points de Vigilance
- Certains services (Gmail, Google Drive, Slack, Canva, GitHub) nécessitent un `client_id` OAuth ou un token/PAT (saisissable via l'icône clé 🔑 de chaque plugin) si l'accès via session navigateur WebView seule ne suffit pas pour les appels REST directs.
- L'installation binaire `agy` cible les smartphones Android ARM64 (`arm64-v8a`).

## 4. Prochaines Étapes
1. Ajouter la sélection individuelle de modèle (`agy` / LLM) par bot depuis l'organigramme `Company Org`.
2. Ajouter la capture d'écran WebView directe vers `sandbox/output/` pour l'outil `browser_read_url`.
3. Tester les flux OAuth PKCE & MCP end-to-end sur appareil réel.
