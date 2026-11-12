# API HTTP des réapprovisionnements

Base locale : `http://localhost:8040`. Toutes les requêtes nécessitent un JWT valide dans `Authorization: Bearer <token>` et les écritures utilisent `Content-Type: application/json`.

La création et le catalogue acceptent ADMIN, MANAGER et SERVICE_CALLER. Un manager est limité à son magasin. Un compte technique SERVICE_CALLER est limité, comme un manager, au magasin unique de ses habilitations actives (`commerce_store_grants`) : sans habilitation, ou avec des habilitations sur plusieurs magasins, il est refusé (un compte d’intégration par magasin). Seul ADMIN a un périmètre global. La création ne nécessite pas une session de navigateur.

## Identifier produits et emplacements

`GET /replenishments/catalog?storeId=1` retourne les bâtiments (avec leurs points du plan), l'occupation des buffers et les bacs libres du magasin. Les produits et les emplacements se chargent à la demande, sans jamais transférer tout le catalogue :

- `GET /replenishments/catalog/products?storeId=1&q=farine&limit=50` : recherche par nom ou EAN, sans tenir compte de la casse (`%` et `_` sont cherchés littéralement) ; 50 produits par défaut, 100 au plus ; `more` indique que la recherche a d'autres résultats.
- `GET /replenishments/catalog/products?storeId=1&ids=3,4` ou `&eans=3017620422003,…` : produits donnés (100 au plus), prioritaires sur la recherche.
- `GET /replenishments/catalog/locations?storeId=1&buildingId=2&productIds=3,4` : adresses d'un bâtiment du magasin pour ces produits, plus les adresses sans produit attribué ; les adresses d'attente de réapprovisionnement, de bacs de préparation et de bacs en buffer en sont exclues.

Utiliser les identifiants retournés pour les lignes ci-dessous. Les quantités sont exprimées en pièces pour UNIT, kg pour WEIGHT et litres pour VOLUME ; jusqu’à six décimales, pièces entières uniquement.

## Planifier une livraison camion

`POST /replenishments`

```json
{
  "storeId": 1,
  "requestKey": "ERP-LIVRAISON-20261020-001",
  "operation": "TRUCK_UNLOADING",
  "truckReference": "CAMION-42",
  "start": "2026-10-21T08:00:00",
  "end": "2026-10-21T08:45:00",
  "lines": [
    {"productId": 101, "quantity": 36, "destinationLocationId": 501},
    {"productId": 102, "quantity": 7.25, "destinationLocationId": 502}
  ]
}
```

Identifiants ci-dessus illustratifs : les remplacer par ceux du catalogue. Dates locales du magasin, sans suffixe de fuseau ; la fin doit être postérieure au début et ne doit pas être passée.

## Planifier un réachalandage entrepôt → magasin

Même endpoint, avec `operation: "SHELF_REPLENISHMENT"`, sans `truckReference`, et un `sourceLocationId` pour chaque ligne. Tous les départs doivent appartenir au même entrepôt et toutes les arrivées au même magasin :

```json
{
  "storeId": 1,
  "requestKey": "ERP-REACHALANDAGE-20261020-001",
  "operation": "SHELF_REPLENISHMENT",
  "start": "2026-10-21T09:00:00",
  "end": "2026-10-21T09:30:00",
  "lines": [
    {"productId": 101, "quantity": 9, "sourceLocationId": 501, "destinationLocationId": 601}
  ]
}
```

```sh
curl --fail-with-body http://localhost:8040/replenishments \
  -H "Authorization: Bearer $STOREPILOT_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @replenishment.json
```

La réponse JSON contient `id`, `code`, `status`, `operation` et `lines`. La création planifie le stock futur et crée automatiquement l’ordre de manutention visible dans Storepilot ; elle ne crédite pas le stock physique. Limite : 100 produits distincts par demande.

Réutiliser la même `requestKey` et exactement le même contenu lors d’une relance réseau : le même ordre est retourné. Un contenu différent avec la même clé renvoie HTTP 409. Une erreur métier renvoie HTTP 400 avec `{ "code": "REPLENISHMENT_VALIDATION", "message": "…" }`. HTTP 401/403 indique un défaut d’authentification ou de périmètre.

## Exécution et corrections dans Storepilot

Les opérateurs démarrent l’ordre puis confirment les prélèvements (`POST /replenishments/{id}/picks`) et les rangements (`POST /replenishments/{id}/receipts`). Le picking passe le stock en transit ; le rangement le rend disponible à destination.

`POST /replenishments/{id}/reopen` accepte `{ "storeId": 1, "supplyId": 123, "phase": "DESTINATION", "eventKey": "correction-unique-001" }`. SOURCE réouvre le prélèvement, DESTINATION le rangement. La correction porte sur l’intégralité de la quantité confirmée de cette ligne dans cette phase. Elle écrit des mouvements compensatoires, sans supprimer l’historique. Réouvrir le rangement avant un prélèvement déjà rangé. Une correction rendant un stock négatif est refusée. Ces actions opérateur restent réservées aux managers et administrateurs.

## Définition séparée de la manutention

Le parcours Storepilot utilise désormais deux ressources distinctes :

- `POST /replenishments/definitions` : produits et quantités annoncés, camion ou bâtiment source, bâtiment d’arrivée, début et fin prévus. Cette définition alimente le stock prévisionnel sans choisir de baie.
- `GET /replenishments/definitions?storeId=…` et `GET /replenishments/definitions/{id}?storeId=…` : consultation des définitions et de leur manutention liée.
- `POST /replenishments/definitions/{id}/handling-orders` : création explicite de la manutention avec ventilation des quantités.

Exemple de définition :

```json
{"storeId":1,"requestKey":"delivery-external-001","operation":"TRUCK_UNLOADING","truckReference":"CAM-001","destinationBuildingId":2,"start":"2026-10-22T08:00:00","end":"2026-10-22T09:00:00","lines":[{"productId":12,"quantity":"24"}]}
```

Exemple de ventilation de ce produit entre rayon et bac :

```json
{"storeId":1,"requestKey":"handling-external-001","allocations":[{"productId":12,"quantity":"18","destinationLocationId":100},{"productId":12,"quantity":"6","bufferId":3,"toteCode":"BAC-001"}]}
```

Pour un réachalandage, chaque affectation comporte également `sourceLocationId` dans l’entrepôt. Les sommes doivent correspondre exactement aux quantités de la définition. Un bac peut contenir plusieurs produits compatibles avec sa température ; il ne peut viser plusieurs buffers. Le choix facultatif `bufferNodeId` positionne un buffer encore non positionné sur un point de son bâtiment.

Les prévisions sans adresse sont remplacées atomiquement par les prévisions ventilées lors de la création de la manutention : elles ne sont pas additionnées. Les requêtes sont idempotentes à contenu identique ; une même référence réutilisée avec un autre contenu est refusée. L’ancien POST atomique décrit plus haut reste disponible pour les intégrations existantes.

## Déchargement camion par chariots

Une livraison camion (`TRUCK_UNLOADING` sans bâtiment source) peut être rangée chariot par chariot. Les chariots
organisent le travail ; le stock reste reçu ligne par ligne par la réception existante (écarts, traçabilité et
contrôles de transit inchangés).

- `GET /replenishments/{id}/carts?storeId=…` : proposition fraîche pour le reliquat à charger, et chariots déjà chargés.
  Le reliquat d’une ligne est la quantité attendue moins la quantité reçue moins ce qui est encore sur un chariot.
  - Seuls les chariots `AVAILABLE` sont proposés, s’ils ne sont ni chargés d’une autre livraison, ni occupés par des bacs
    ou une mission de préparation. Les plus grands passent en premier.
  - Un chariot ne porte qu’un usage : ambiant, entretien, frais ou surgelé, parmi les usages autorisés par son type.
  - Le volume et le poids connus restent dans la capacité du type (volume du support à défaut). Un produit à la pièce
    peut être réparti sur plusieurs chariots ; un produit au poids ou au volume ne l’est pas.
  - Une dimension inconnue n’est jamais comptée comme nulle : `estimateComplete: false` demande un contrôle visuel.
  - Un produit qu’aucun chariot libre compatible ne peut prendre figure dans `unassigned` avec son motif.
  - Chaque chariot proposé ou chargé porte un résumé de tournée : nombre d’arrêts, distance estimée par le planificateur
    du magasin et adresses dans l’ordre du parcours.
- `POST /replenishments/{id}/carts` : le picker confirme les chariots réellement chargés.

```json
{"storeId":1,"requestKey":"cart-load-0001","loads":[{"cartId":45,"lines":[{"supplyId":12,"quantity":10}]}]}
```

  Sont refusés (400) : chariot inconnu, d’un autre type, bloqué ou déjà utilisé ; produit incompatible avec les usages du
  chariot ; mélange d’usages sur un chariot ; quantité nulle, fractionnaire pour un produit à la pièce ou supérieure au
  reliquat ; ligne en double ou hors de la livraison. Un dépassement de capacité estimé n’est qu’un avertissement
  (`warnings`), le picker voyant le chargement réel. Rejouer la même référence rend le même résultat ; la réutiliser
  avec un autre contenu est refusé (409 `UNLOADING_CART_REQUEST_REUSED`).
- `GET /replenishments/{id}/route?storeId=…&cartLoadId=…` : phase unique « Ranger le chariot … », tournée optimisée sur
  les seules lignes du chariot, quantités limitées à ce qui reste dessus.
- Rangement : `POST /replenishments/{id}/receipts` avec `cartLineId` sur la ligne. La quantité ne peut dépasser ce qui
  reste sur le chariot ; le chariot passe à `DONE` et redevient libre lorsqu’il est vide.
- `POST /replenishments/{id}/carts/{loadId}/release?storeId=…` : libère un chariot (`RELEASED`) dont le contenu a été
  rangé autrement ; ce qui restait dessus revient dans la proposition. Libérer un chariot déjà fermé ne change rien.
