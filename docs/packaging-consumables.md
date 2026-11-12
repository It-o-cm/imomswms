# Cartons et palettes consommables

La tuile **Bacs et chariots** présente uniquement le matériel réutilisable (filtre serveur `kind=REUSABLE`). La nouvelle tuile **Emballages et consommables** gère les cartons et palettes par type, pour le magasin de l'acteur connecté.

Chaque type possède une quantité disponible, un seuil d'alerte et un stock cible. Le seuil est atteint lorsque le disponible est inférieur ou égal au seuil. La quantité proposée à commander est alors `max(0, cible - disponible)`. Cela ne passe aucune commande fournisseur automatiquement.

## Actions

- Réceptionner : entrée de quantité entière avec référence ou motif.
- Corriger le stock : écart signé, motivé, refusé s'il rend le stock négatif.
- Seuil et cible : cible strictement supérieure au seuil.
- Utiliser : choix d’une commande préparée, puis ouverture de la constitution du colis existante avec le type de carton/palette préselectionné. Identifiant et étiquette du colis sont générés par le système ; le stock est débité à la fermeture.
- Déclarer une perte : retire une quantité entière avec motif, sans créer de colis ni de support individuel.
- Historique : les 50 derniers mouvements, avec quantité, solde, date, acteur et motif.

Les réceptions, corrections et paramètres sont réservés au manager/admin. Un préparateur peut déclarer une consommation. Toutes les opérations sont limitées au magasin et journalisées. Les commandes de mutation utilisent une clé d'idempotence. Un verrou de magasin et des verrous de type empêchent de dépenser simultanément la dernière unité.

Un carton ou une palette déjà enregistré par l'API historique est décompté à sa première ouverture de session `LoadedTote`. Un emballage consommé ne peut pas servir à une nouvelle session après sa première utilisation. Les bacs et chariots conservent leur cycle réutilisable.

La fermeture d'un nouveau colis carton/palette impose de choisir son type de consommable et consomme une unité. Si tout le contenu est déjà dans un même carton/palette consommé, cet emballage est conservé sans second décompte. La réouverture/refermeture ne consomme pas de deuxième emballage. Une annulation ne remet pas automatiquement un carton usagé dans le stock neuf.

La migration V28 conserve les données antérieures : les supports ayant déjà une session sont considérés déjà utilisés ; les colis déjà fermés ne sont pas débités rétroactivement. Le stock neuf historique commence à zéro, à renseigner par réception ou correction (aucune quantité n'est inventée pour les magasins réels).

## API HTTP

Sous `/field-operations`, avec authentification habituelle :

- `GET /consumables?storeId=…`
- `GET /consumables/{typeId}/movements?storeId=…`
- `POST /consumables/receive` : `storeId`, `typeId`, `requestKey`, `quantity`, `reason`.
- `POST /consumables/adjust` : mêmes champs, quantité signée.
- `POST /consumables/settings` : `storeId`, `typeId`, `requestKey`, `threshold`, `target`.
- `POST /consumables/loss` : `storeId`, `typeId`, `requestKey`, `quantity`, `reason` (aucun identifiant d’emballage).

La création d'un type utilise `PUT /support-types`, `kind=CARTON|PALLET`, avec ses capacités, conditionnements autorisés et éventuellement `reorderThreshold` / `reorderTarget`. La fermeture `/parcels/close` reçoit `consumableTypeId`.

## Démonstration

Le seed ajoute sans remettre à zéro les stocks existants :

| Type | Disponible | Seuil | Cible |
|---|---:|---:|---:|
| Carton petit 15 L | 120 | 30 | 200 |
| Carton grand 50 L | 12 | 20 | 100 |
| Palette à usage unique | 0 | 5 | 25 |
| Palette Europe | 18 | 5 | 30 |

Le rejeu du seed préserve les consommations et leurs mouvements.

## Supports remplis pour tester l’affichage

Le seed ajoute trois bacs de préparation en cours : `AFF-VS1-1` et `AFF-VS2-1` dans CH-01, `AFF-VS3-1` dans CH-02 (premiers compartiments libres). Chaque bac contient un produit prélevé et reste lié à sa commande et à sa mission. Les affectations ne sont jamais recréées si l’opérateur déplace ensuite ces bacs.

`CARTON-DEMO-PLEIN` (deux unités) et `PAL-DEMO-PLEINE` (trois unités) sont des supports physiques remplis et déposés au buffer du magasin. Ils apparaissent dans **Emballages et consommables → Cartons et palettes en service**, et dans le buffer, avec leur contenu consultable dans la modale commune. Leur mise en service consomme une unité du type correspondant : le seed neuf présente donc 119 petits cartons et 17 palettes Europe encore disponibles.

`GET /field-operations/consumables/in-use?storeId=…` renvoie les 100 premiers cartons/palettes avec une session active contenant des produits, sans charger les contenus avant sélection.

## Vérification complète du 30 octobre 2026

Deux scénarios HTTP réels et isolés, un carton et une palette, couvrent la création idempotente du colis, l’affectation de deux unités prélevées, la fermeture avec contrôle, la mise en file et la transmission d’étiquette au simulateur, la réouverture par le manager, la nouvelle fermeture et la réimpression. Le stock d’emballages reste à 3 pendant la création/affectation, passe à 2 à la première fermeture, puis reste à 2 jusqu’après la réimpression. Un seul mouvement de consommation de -1 existe ; la marchandise conserve son stock physique et le colis conserve ses identifiants.

Validation : deux cas du test `PackagingFullJourneyTest`, cinq tests de rendu/code-barres/transport `LabelRendererTest`, deux parcours StorePilot `PackagingJourney.test.jsx`. Aucun test sur imprimante physique ; les travaux sont transmis au simulateur et confirmés `SENT`. Le test de transport TCP utilise uniquement un serveur local éphémère. Résultats détaillés : `docs/picker-suivi/preuves-parcours-emballages-20261030/verification.json`.
