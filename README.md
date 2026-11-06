# ma-cam

Application caméra Android qui améliore la photo et la vidéo par traitement logiciel.

## Étape 2 : caméra vidéo (v0.3)

L'écran principal est une caméra vidéo qui ouvre directement chaque objectif physique :

- choix de l'objectif (ultra grand-angle, principal, téléobjectif, avant) ;
- modes 4K30 et 4K60 en HDR (HEVC 10 bits HLG) ou en SDR, 4K120 et 1080p240 quand l'objectif les permet ;
- exposition auto, verrouillée ou manuelle (ISO, obturation), correction EV, mise au point auto ou manuelle,
  balance des blancs auto, verrouillée ou en préréglage, stabilisation électronique activable, débit 50/75/100 Mb/s ;
- son AAC 48 kHz stéréo ;
- journal gyroscope au format Gyroflow (`.gcsv`).

Les vidéos sont enregistrées dans `DCIM/MaCam/` et les journaux gyroscope dans `Download/MaCam/`, avec le même nom.
Pour Gyroflow, filmez avec « Stab. OFF » : la stabilisation électronique du téléphone fausse la correction.

## Étape 1 : diagnostic (bouton « Diagnostic »)

L'application de diagnostic vérifie ce que le téléphone donne réellement aux applications tierces :

1. l'inventaire de toutes les caméras, y compris les objectifs physiques cachés derrière la caméra logique (focales, capteur, 50 Mpx, RAW, profils 10 bits, extensions du fabricant, encodeurs) ;
2. l'accès séparé à chaque objectif (ultra grand-angle, principal, téléobjectif) ;
3. la vidéo haute vitesse (120 i/s et plus), avec la fréquence réellement mesurée ;
4. l'enregistrement 4K HEVC à 30 et 60 i/s, en 10 bits HLG et en 8 bits, puis la vérification des fichiers produits.

Le rapport s'affiche à l'écran, se partage avec le bouton « Partager le rapport » et s'enregistre dans
`Android/data/com.macam.diag/files/rapport_diagnostic.txt`. Les vidéos de test sont dans `files/Movies/`.

## Compiler

```bash
./gradlew assembleDebug
```

L'APK est produit dans `app/build/outputs/apk/debug/app-debug.apk`.
