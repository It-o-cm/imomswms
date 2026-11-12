# Spec — Contrat de retour e-commerce (suivi, substitutions, poids, arrivée)

**Statut :** proposition (demande du consommateur e-commerce / immcomm).
**Demandeur :** immcomm (vitrine storecomm) — orchestrateur ; il soumet la commande et doit pouvoir en **suivre** l'exécution sans accéder au backoffice.
**Dépend de / réutilise :** `OrderResource` (`POST /orders`), `OrderRequest` (`order_number`, `ReservationRecord.status`), `Order.status` (VALIDATED/IN_PROGRESS/SHIPPED), `ProductSubstitutionResource` / `PickedItem` (substitution au picking), `ParcelService` (poids réel `weightKg`), `BaseHandlingOrder.HandlingOrderStatus`.

## 1. Besoin et manque constaté

immcomm soumet déjà une RetailOrder à `POST /orders` (202, `OrderRequest` PENDING). Mais **rien n'est exposé au e-commerce** pour, à partir du seul `orderId` :
- suivre l'**avancement** (reçue → en préparation → préparée → prête/arrivée → retirée/expédiée/livrée, ou annulée/refusée) ;
- connaître les **substitutions réellement décidées** au picking (produit demandé → produit servi, motif) ;
- connaître les **quantités ajustées et poids réels** (produits au poids pesés en préparation) qui déterminent le **montant final** ;
- être notifié de l'**arrivée / mise à disposition** (retrait) ou de l'**expédition** (livraison).

Tout cela existe **en interne** (statuts `OrderRequest`/`Order`/handling, `PickedItem`, `ParcelService.weightKg`) mais n'est ni requêtable ni poussé vers l'e-commerce.

## 2. Deux canaux complémentaires

### 2.1 Requête de suivi (pull) — `GET /orders/{orderId}`
Rend l'état consolidé d'une commande e-commerce, réservé au rôle `SERVICE_CALLER`, en lecture seule.

```
GET /orders/{orderId}  ->  200 OrderTracking | 404 si inconnue
```

**OrderTracking :**
| Champ | Type | Sémantique |
|---|---|---|
| `orderId` | String | Clé e-commerce (= `OrderRequest.order_number`). |
| `storeCode` | String | |
| `status` | enum | Cycle **normalisé e-commerce** (§3), dérivé des statuts internes. |
| `statusAt` | DateTime | Horodatage du dernier changement. |
| `lines` | [TrackedLine] | Une entrée par ligne de la commande soumise. |
| `parcels` | [Parcel] | Colis préparés : `code`, `weightKg` réel, dimensions si renseignées, état. |
| `events` | [TrackingEvent] | Journal ordonné et horodaté des transitions (pour rapprochement). |

**TrackedLine :**
| Champ | Type | Sémantique |
|---|---|---|
| `lineNumber` | Integer | Repris de la commande soumise. |
| `requestedEan` | String | Produit commandé. |
| `requestedQuantity` | Number | Qté commandée. |
| `preparedQuantity` | Number | Qté réellement préparée (0 si non servie / rupture). |
| `substitution` | Substitution? | Présent si le produit servi diffère : `servedEan`, `servedName`, `reason` (`OUT_OF_STOCK`, …), `unitPrice` servi, `customerDecision` si une acceptation client est attendue (§4). |
| `measuredWeight` | Weight? | Pour un produit au poids : poids réel pesé (et unité), source de l'ajustement de montant. |
| `lineStatus` | enum | `PENDING` / `PICKED` / `SUBSTITUTED` / `OUT_OF_STOCK`. |

### 2.2 Notification (push) — webhook sortant vers immcomm
À chaque transition significative (préparation démarrée/terminée, substitution décidée, poids pesé, arrivée/mise à disposition, expédition/livraison, annulation), imomswms **POST** un évènement signé à l'URL configurée pour le canal e-commerce.

```
POST {ecommerce.webhook.url}   body: TrackingEvent  (signé HMAC, en-tête de signature)
```

**TrackingEvent :** `orderId`, `type`, `at`, `status` résultant, `payload` (ligne/substitution/poids/colis concerné), `eventId` (idempotent), `sequence` (monotone par commande).

**Garanties :** évènements **idempotents** par `eventId`, **ordonnés** par `sequence` (immcomm ignore un évènement de séquence antérieure), **rejouables** ; le `GET /orders/{orderId}` sert de **source de vérité de réconciliation** si un webhook est perdu. Pas d'exigence de livraison exacte-une-fois sur le webhook puisque le pull réconcilie.

## 3. Cycle de vie normalisé e-commerce (mapping)
`RECEIVED` (OrderRequest PENDING) → `ACCEPTED`/`VALIDATED` → `PREPARING` (IN_PROGRESS / handling en cours) → `PREPARED` (préparée/colis clos) → `READY_FOR_PICKUP` *(retrait)* **ou** `OUT_FOR_DELIVERY` → `COLLECTED` / `DELIVERED` (≈ SHIPPED) ; branches `CANCELLED`, `REJECTED`. imomswms publie ce cycle **normalisé** ; le mapping depuis `ReservationStatus`/`Order.status`/`HandlingOrderStatus` reste interne.

## 4. Point d'arbitrage à trancher par imomswms/métier
La **politique de substitution côté client** (le client accepte/refuse une substitution avant ou après préparation ; remboursement si refus) est une règle métier. La spec expose le champ `customerDecision` et son état, mais **le moment** (avant préparation vs à la livraison) et le **flux de remboursement** doivent être arbitrés. immcomm ne décide pas — il relaie.

## 5. Idempotence de la soumission (`POST /orders`)
`OrderRequest` autorise l'historique (« history allowed ») → **`POST /orders` n'est pas idempotent par `orderId`** : un rejeu (réponse perdue) crée une 2ᵉ demande. **Demande :** rendre l'intake **idempotent par `orderId`** (un même `orderId` rejoué renvoie 202 sans créer de doublon de préparation), **ou** documenter explicitement qu'immcomm doit garantir une soumission-unique durable côté e-commerce. À défaut d'idempotence amont, immcomm implémentera un verrou « soumis-une-fois » (état durable + reprise), mais l'idempotence amont est préférable (défense en profondeur).

## 6. Hors périmètre
- Montant/valorisation et remise → imvaluation (la commande porte déjà le `pricing` ; l'ajustement au poids réel repart en valorisation côté immcomm avec le `measuredWeight` fourni ici).
- Cagnotte/fidélité, ingestion fiscale du ticket → imfid.
- Référentiel produit (caractéristiques UNIT/WEIGHT/VOLUME) → chaque appli est alimentée séparément ; non échangé via ce contrat.

## 7. Questions ouvertes pour l'équipe imomswms
1. Pull + push, ou pull seul dans un premier temps ?
2. Forme de signature du webhook (HMAC partagé, cohérent avec les autres intégrations ?).
3. Périmètre exact de `status` normalisé (faut-il distinguer `AT_STORE`/`IN_TRANSIT` pour la livraison ?).
4. Idempotence `POST /orders` : amont (préférée) ou déléguée à immcomm ?
