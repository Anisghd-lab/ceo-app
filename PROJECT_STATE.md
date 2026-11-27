# PROJECT_STATE.md — CEO (Universal Autonomous Company OS)

## 1. Objectifs Principaux & Vision
- Fournir une application Android native nommée **CEO** reproduisant l'interface épurée de la vidéo (rail latéral rétractable avec mascottes géométriques à badges, cartes Company Org, Deliverable QA, Release `Ship / Not yet`, et `Board meeting`).
- Architecture **1 Sandbox = 1 Entreprise** avec **zéro métier hardcodé** : après toute description libre de l'utilisateur, le CEO conçoit et déploie dynamiquement l'équipe de bots et leurs outils atomiques.
- Gouvernance stricte : vérification QA par un bot indépendant, approbation humaine idempotente avant toute action externe/release, et publication exclusive des APKs sur **GitHub Releases**.

## 2. État d'Avancement Réel
- [x] **Kernel Backend (`backend/`)** : FastAPI, SQLite WAL, `SandboxManager` (isolation + nettoyage env + E2B optionnel), `MemoryEngine` (Core blocks + FTS5), `MultiLLMRouter`, `ToolRegistry` (7 primitives atomiques), `GenericAgentRunner`, `CEOArchitect` (spawner dynamique), `ApprovalGate` (verrouillage optimiste idempotent).
- [x] **Validation Backend (`pytest`)** : 5/5 tests passés (isolation inter-sandboxes, filtrage secrets hôtes, génération PDF + preview PNG + QA, stress test concurrence 10x idempotence, Kill Switch & REST API).
- [x] **Application Android Native (`android/`)** : Kotlin + Jetpack Compose + Material 3, mascottes géométriques Canvas, rail rétractable, 4 cartes fidèles à la vidéo, dialogs Création libre / Livrables / Réglages Multi-LLM & Connecteurs, notifications actionnables `Ship` / `Not yet`.
- [x] **Compilation APK & GitHub Release** : compilé exclusivement sur GitHub Actions (`run 45931352601`, `ubuntu-latest`) et publié sous le tag `v0.1.0` (`Anisghd-lab/ceo-app`).

## 3. Lacunes, Dettes Techniques & Points de Vigilance
- Le mode `sandbox_exec` local utilise un sous-processus à environnement blanchi (pas de conteneur Docker sur PRoot ARM64) ; activer une clé **E2B** est recommandé pour exécuter du code tiers non fiable en production.
- Les plateformes de vente sans API vendeur officielle (ou protégées par CAPTCHA) fonctionnent via génération de Listing Pack vérifié téléchargeable sur Android.
- Pas encore de tests UI instrumentés Compose (`androidTest`) sur émulateur.

## 4. Prochaines Étapes (Backlog Priorisé)
1. Ajouter la prévisualisation native inline des pages PDF (PdfRenderer Android) dans la carte Deliverable.
2. Ajouter la saisie vocale native Android sur le bouton compositeur.
3. Ajouter un écran dédié d'édition fine du modèle LLM par bot directement depuis la carte `Company Org`.
4. Ajouter la synchronisation push `ntfy` en arrière-plan via Foreground Service optionnel.
