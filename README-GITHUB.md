# Décibelmètre V2 — compilation automatique

Ce projet est configuré pour être compilé automatiquement avec GitHub Actions.

## Utilisation

1. Crée un nouveau dépôt GitHub.
2. Envoie tout le contenu de ce dossier dans le dépôt.
3. Va dans **Actions**.
4. Sélectionne **Build Android APK**.
5. Clique sur **Run workflow** si aucun push n'a déjà lancé le workflow.
6. Quand le workflow est terminé, ouvre le run correspondant.
7. Dans **Artifacts**, télécharge `DecibelMeterV2-debug-apk`.
8. Décompresse l'archive téléchargée et installe `app-debug.apk` sur le téléphone.

Le workflow est également déclenché automatiquement à chaque `push` sur `main` ou `master`.

## Pourquoi un APK debug ?

Pour les tests sur ton téléphone, un APK debug est suffisant et ne nécessite pas de clé de signature personnelle.
Pour publier l'application sur Google Play, il faudra ensuite mettre en place une signature release.

## Configuration

Le workflow utilise Java 17 et Gradle 8.7. Le projet utilise Android Gradle Plugin 8.6.1 et compile avec Android API 35.
