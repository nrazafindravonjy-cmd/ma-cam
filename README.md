# ma-cam

Application caméra Android qui améliore la photo et la vidéo par traitement logiciel.

## Atelier vidéo (v0.10)

Post-traitement sur le téléphone (`app/src/main/java/com/macam/studio/`) : décodage, trois passes GPU
(OpenGL ES 3), réencodage HEVC, son recopié sans perte.

- **Stabilisation gyroscopique** (principe de Gyroflow) : intégration du journal `.gcsv`, lissage aller-retour
  sans déphasage, limitation à la marge de recadrage, homographie par image interpolée entre le haut et le bas
  de l'image (correction de l'obturateur roulant) ;
- **débruitage temporel** récursif, adaptatif au mouvement ;
- **looks** : LUT 3D `.cube` importées ou intégrées (Film chaud, Cinéma, Log → standard).

Accès : bouton « Atelier » des aides au tournage, ou appui long sur la vignette d'une vidéo.

## Photo multi-images (v0.5)

Le moteur natif `app/src/main/cpp/engine.cpp` réimplémente les publications de Google (aucun code GCam) :

- **HDR+** (Hasinoff et al., 2016) : rafale RAW sous-exposée d'1 IL, alignement hiérarchique par tuiles
  (pyramide à 4 niveaux, 3 candidats, sous-pixel), fusion robuste de type Wiener, puis dématriçage
  Malvar-He-Cutler, couleurs du capteur, tonalité locale, débruitage chroma et netteté ;
- **Nuit et Astro** (Liba et al., 2019) : temps de pose calculé d'après le tremblement mesuré au gyroscope
  (flou ≤ 1,5 px), jusqu'à 1 s sur appui stable et 15 × 8 s en astrophotographie ;
- **Zoom super-résolution 2×** (Wronski et al., 2019, version simplifiée) : accumulation sous-pixel des
  photosites par noyaux gaussiens, sans dématriçage ;
- **Portrait** : extension Bokeh du fabricant ;
- **DNG fusionné** en option ;
- **adaptation à l'appareil** (`DeviceProfile`) : nombre d'images, RAW ou YUV et résolution selon la mémoire
  et le capteur, pour les téléphones d'entrée de gamme.

Sur des rafales synthétiques (8 images, texture naturelle, bruit réaliste), la fusion divise le bruit
par 2,56 (maximum théorique 2,83) et n'introduit pas de fantôme sur un objet en mouvement.

## Étape 2 : caméra vidéo (v0.3)

L'écran principal est une caméra vidéo qui ouvre directement chaque objectif physique :

- choix de l'objectif (ultra grand-angle, principal, téléobjectif, avant) ;
- modes 4K30 et 4K60 en HDR (HEVC 10 bits HLG) ou en SDR, 4K120 et 1080p240 quand l'objectif les permet ;
- exposition auto, verrouillée ou manuelle (ISO, obturation), correction EV, mise au point auto ou manuelle,
  balance des blancs auto, verrouillée ou en préréglage, stabilisation électronique activable, débit 50/75/100 Mb/s ;
- son AAC 48 kHz stéréo ;
- journal gyroscope au format Gyroflow (`.gcsv`) ;
- outils pro : zébras, focus peaking, fausses couleurs et profil plat (courbe log, modes SDR).

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
