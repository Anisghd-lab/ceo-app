# PROJECT_STATE.md — CEO (100% On-Device Android OS)

## 1. Objectifs Principaux & Vision
- Application Android native nommée **CEO** avec l'interface exacte de la vidéo (rail latéral rétractable avec mascottes géométriques à badges, cartes `Company Org`, `Deliverable QA`, `Release Ship / Not yet`, `Board meeting`, notifications actionnables).
- **Zéro VPS requis** : tout le moteur multi-entreprises (`1 Sandbox = 1 Entreprise`, zéro métier hardcodé) tourne nativement sur Android (SQLite local, sandbox fichiers isolée, génération PDF native `PdfDocument` + ZIP, routeur Multi-LLM direct OkHttp, connecteurs REST API, QA indépendant et Approval Gate atomique).
- Compilation exclusivement sur **GitHub Actions** et distribution exclusive de l'APK sur **GitHub Releases** (`Anisghd-lab/ceo-app`).

## 2. État d'Avancement Réel
- [x] **Moteur 100% On-Device Android (`android/app/.../engine/`)** :
  - `LocalDatabase.kt` : persistance SQLite WAL complète (entreprises, bots, messages, missions, artefacts, approbations atomiques, connecteurs, mémoire isolée).
  - `OnDeviceSandbox.kt` : sandboxes isolées par entreprise, génération PDF multi-pages `PdfDocument`, archives `.zip`, vérification QA programmatique.
  - `OnDeviceLLMClient.kt` : routeur Multi-LLM direct (`openrouter`, `gemini`, `xai`, `openai`, `anthropic`, `deepseek`, `groq`) avec vérification de clé (`Save & Verify LLM Key`).
  - `AutonomousEngine.kt` : CEO dynamique zéro-hardcoding, boucle multi-étapes Strategist → Producer → Independent QA → Approval Gate → Live HTTP Connectors.
- [x] **UI Android fidèle à la vidéo + FileProvider** (`MainApp.kt`, `Sheets.kt`, `VideoCards.kt`, `BotMascot.kt`, `ApprovalNotifier.kt`).
- [x] **Backend Python optionnel (`backend/`)** : 5/5 tests `pytest` verts.
- [x] **Release APK `v0.2.0` (100% On-Device, sans VPS)** : compilée sur GitHub Actions (`run 45996599737`, SHA-256 `187b7c09d534149611eed2f0d4ad4a1cdd7dc4e83ad5e98d22239bbbfb41225c`) et publiée sur GitHub Releases (`Anisghd-lab/ceo-app`).

## 3. Lacunes, Dettes Techniques & Points de Vigilance
- L'exécution de code arbitraire Python/bash n'est pas embarquée dans l'APK Android (sans Termux/PRoot), remplacée par le moteur natif de fichiers (`PdfDocument`, `ZipOutputStream`, fichiers texte/code/HTML/CSV) + appels API REST.
- Pas encore de tests UI instrumentés Compose sur émulateur.

## 4. Prochaines Étapes
1. Ajouter la saisie vocale Android sur le bouton micro/compositeur.
2. Ajouter un éditeur par bot dans `Company Org` pour choisir un modèle LLM différent par rôle.
3. Ajouter un export complet du dossier sandbox en `.zip` partageable en 1 tap.
