# PROJECT_STATE.md — CEO (100% On-Device Android OS)

## 1. Objectifs Principaux & Vision
- Application Android native nommée **CEO** avec l'interface exacte de la vidéo (rail latéral rétractable avec mascottes géométriques à badges, cartes `Company Org`, `Deliverable QA`, `Release Ship / Not yet`, `Board meeting`, notifications actionnables).
- **Zéro VPS requis** : tout le moteur multi-entreprises (`1 Sandbox = 1 Entreprise`, zéro métier hardcodé) tourne nativement sur Android via **Antigravity CLI embarqué (`agy`)** et/ou le routeur Multi-LLM direct (`OpenRouter`, `Gemini`, `Anthropic`, `OpenAI`, `xAI`, `DeepSeek`, `Groq` + fournisseurs custom OpenAI/Anthropic-compatible).
- **Multi-comptes Google + Quotas en direct** : bascule instantanée entre comptes Google (`jetski-standalone-oauth-token`) sans révoquer les refresh tokens sauvegardés, avec affichage des fenêtres de quota `5h`/`Weekly` et du pourcentage restant par modèle.
- **Navigateur In-App intégré** : WebView complète intégrée dans `CEO` pour la connexion OAuth Google (callback loopback `127.0.0.1`) et la navigation web générale (avec fallback Chrome Custom Tabs).
- Compilation exclusivement sur **GitHub Actions** et distribution exclusive de l'APK sur **GitHub Releases** (`Anisghd-lab/ceo-app`).

## 2. État d'Avancement Réel
- [x] **Antigravity CLI Native Engine (`android/app/.../agy/`)** :
  - `AgyCliInstaller.kt` : téléchargement manifeste officiel `android_arm64.json`, vérification SHA-512, extraction binaire `agy` vers `filesDir/agy/bin/agy`, `targetSdk = 28` compatible SELinux `execve()`.
  - `AgyAccountManager.kt` : sauvegarde/switch/ajout multi-comptes Google (`$HOME/.gemini/ceo-accounts/*.json` <-> `jetski-standalone-oauth-token`) sans révocation.
  - `AgyRuntimeManager.kt` : superviseur `agy --hub`, interception URL OAuth, ConnectRPC HTTP/JSON (`Login`, `GetAuthStatus`, `GetUserStatus`, `GetAvailableModels`, `RetrieveUserQuotaSummary`), repli `agy models`, exécution autonome `agy -p --output-format stream-json --mode auto --dangerously-skip-permissions` dans la sandbox de chaque entreprise.
- [x] **Moteur 100% On-Device Android (`android/app/.../engine/`)** :
  - `LocalDatabase.kt` : persistance SQLite WAL complète.
  - `OnDeviceSandbox.kt` : sandboxes isolées par entreprise, génération PDF multi-pages `PdfDocument`, archives `.zip`, import fichiers locaux (`+`), découverte automatique des fichiers générés dans `output/`, audit QA programmatique.
  - `OnDeviceLLMClient.kt` : routeur Multi-LLM direct avec découverte des modèles (`/models`), fournisseurs custom configurables et repli transparent.
  - `AutonomousEngine.kt` : orchestration unifiée via Antigravity CLI ou clés API externes (Strategist → Producer → QA → Approval Gate → Connectors).
- [x] **UI Intacte, Boutons Responsives & Navigateur In-App** (`MainApp.kt`, `Sheets.kt`, `InAppBrowserDialog.kt`, `VideoCards.kt`, `BotMascot.kt`, `ApprovalNotifier.kt`).
- [x] **Backend Python (`backend/`)** : 5/5 tests `pytest` verts.

## 3. Lacunes, Dettes Techniques & Points de Vigilance
- Les comptes Google ajoutés via WebView peuvent nécessiter le bouton "Chrome" intégré si le compte impose une clé de sécurité matérielle / Passkey Google FIDO.
- L'installation binaire `agy` cible les smartphones Android ARM64 (`arm64-v8a`), qui représentent quasi 100 % des téléphones Android modernes.

## 4. Prochaines Étapes
1. Ajouter la sélection de modèle Antigravity ou LLM spécifique directement par bot depuis la carte `Company Org`.
2. Ajouter la saisie vocale sur le compositeur de message.
3. Exporter le dossier complet d'une entreprise en `.zip` partageable en un clic.
