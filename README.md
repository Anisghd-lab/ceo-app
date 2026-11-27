# CEO — Universal Autonomous Company OS (Android + Multi-Sandbox Kernel)

**CEO** est une application **Android native** couplée à un noyau open-source léger (**1 Sandbox = 1 Entreprise**, **zéro métier hardcodé**) :

- Décris librement n'importe quelle entreprise depuis ton téléphone Android (produits numériques, studio de développement mobile, veille, contenu, etc.).
- Le bot **CEO** conçoit dynamiquement l'organigramme exact de bots nécessaires, leurs prompts, leurs outils autorisés et un bot **Quality Auditor (QA)** indépendant, puis les déploie dans une **sandbox isolée**.
- **Human-in-the-Loop obligatoire** : aucun livrable ni appel externe mutatif ne part sans ton tap **`Ship` / `Not yet`** directement dans l'application ou depuis la notification Android.

## Architecture

1. **Application Android Native (`android/`)** :
   - Kotlin + Jetpack Compose + Material 3 reproduisant fidèlement l'interface minimaliste (rail latéral rétractable avec avatars géométriques et badges, bulles fondateur noires, cartes `Company Org`, `Deliverable + QA`, `Release Ship/Not yet`, `Board Meeting` 2x2 KPIs, compositeur flottant `[+] Message CEO`).
   - Notifications natives Android actionnables (`Ship` / `Not yet`).
   - Mode **Live Kernel** (REST + WebSocket) + mode **Local Interactive Preview** immédiat au premier lancement.
2. **Universal Sandbox Kernel (`backend/`)** :
   - **FastAPI + WebSocket + SQLite WAL** (sans Docker, < 200 Mo RAM).
   - **7 Primitives Universelles** (`sandbox_files`, `sandbox_exec`, `web_research`, `company_memory`, `artifact_publish`, `http_api_call`, `ceo_orchestration`) — zéro logique verticale hardcodée.
   - **Isolation stricte `1 Sandbox = 1 Entreprise`** (`sandboxes/<company_id>/`, variables d'environnement blanchies, support **E2B Cloud Sandbox** optionnel).
   - **Mémoire cloisonnée Mem0/Letta-style** (blocs Core + SQLite FTS5).
   - **Routeur Multi-LLM** (OpenRouter, Gemini, Anthropic, OpenAI, xAI Grok, DeepSeek, Groq) avec fallback automatique et budget tokens par entreprise.
   - **Approval Gate idempotent** (verrouillage optimiste SQLite anti double-shipping) + **Global Kill Switch**.

## Démarrage Rapide

### 1. Installer l'application Android (`CEO`)
Télécharge le dernier APK depuis **GitHub Releases** de ce dépôt.

### 2. Lancer le Kernel Backend (Local / PRoot / VPS — sans Docker)
```bash
chmod +x start_kernel.sh
./start_kernel.sh
```
Récupère ensuite ton `Pairing Token` affiché dans `runtime/data/pairing.token`, ouvre l'icône **Réglages** dans l'app Android **CEO**, renseigne l'URL du serveur (ex. `http://127.0.0.1:8765` ou l'IP de ton VPS) + le token, puis ajoute la clé API du ou des fournisseurs LLM de ton choix.

### 3. Lancer la suite de tests Backend
```bash
cd backend
.venv/bin/pytest -v
```
