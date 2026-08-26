# DB-Meter — compilation automatique

Ce projet est configuré pour être compilé automatiquement avec GitHub Actions.

## Utilisation

1. Crée un nouveau dépôt GitHub.
2. Envoie tout le contenu de ce dossier dans le dépôt.
3. Va dans **Actions**.
4. Sélectionne **Build Android APK**.
5. Clique sur **Run workflow** si aucun push n'a déjà lancé le workflow.
6. Quand le workflow est terminé, ouvre le run correspondant.
7. Dans **Artifacts**, télécharge `DB-Meter-debug-apk`.
8. Décompresse l'archive téléchargée et installe `app-debug.apk` sur le téléphone.

Le workflow est également déclenché automatiquement à chaque `push` sur `main` ou `master`.

## Pourquoi un APK debug ?

Pour les tests sur ton téléphone, un APK debug est suffisant et ne nécessite pas de clé de signature personnelle.
Pour publier l'application sur Google Play, il faudra ensuite mettre en place une signature release.

## Configuration

Le workflow utilise Java 17 et Gradle 8.7. Le projet utilise Android Gradle Plugin 8.6.1 et compile avec Android API 35.


## Data
The app stores daily minimum, average, maximum, sample count and accumulated measurement duration locally.
Location permission is optional. Location collection is prepared in the permission flow; associating GPS coordinates with each measurement/session is the next step.


## Historique et carte
L'application enregistre des échantillons locaux avec date/heure, niveau sonore et, si autorisé/disponible, latitude/longitude. L'écran Historique permet de sélectionner un jour; l'écran Carte affiche les points GPS de ce jour avec leur niveau dBA et leur heure.
La carte utilise OpenStreetMap via Leaflet et nécessite une connexion Internet pour les tuiles cartographiques.


## Mesure en arrière-plan
DB-Meter utilise un Android Foreground Service de type microphone/location. Après DÉMARRER, l'utilisateur peut quitter l'application ou éteindre l'écran : la mesure continue avec une notification persistante. La notification affiche le niveau courant et permet d'arrêter la mesure.


## Android 14+ foreground-service configuration
The service explicitly declares and starts with microphone + location foreground-service types.
The manifest requests the corresponding foreground-service permissions.
The service is started only from a visible user action in the Activity.
