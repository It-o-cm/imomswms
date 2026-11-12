# imomswms — Traiteur & réceptions : fabrication, approvisionnement et remise

Spécification additionnelle v1 · 10 novembre 2026 · récepteur : imomswms · émetteur des commandes : immcomm.

Ce document spécifie ce qu'imomswms doit savoir faire pour exécuter les commandes du parcours « Traiteur & réceptions » d'immcomm. Il complète, sans les modifier, les documents déjà réalisés.

## 1. Objet, prérequis et périmètre

### 1.1 Ce que ce document suppose réalisé

- **immcomm / storecomm** : la spécification `immcomm/docs/traiteur/spec-traiteur.pdf` (v1, 905 points du plan terminés) : catalogue traiteur, variantes, menus à composer, gâteaux personnalisés, réception, délais en jours ouvrés, capacité commerciale, créneaux, paiement au retrait, annulation partielle, demandes de modification, liste de fabrication et file du magasin dans `/admin`.
- **imomswms** : réception `POST /orders` (schéma RetailOrder), `OrderRecorder`, `FulfillmentEngine` et ses fulfillers, ordres de manutention et contrat opérateur v2, stock daté et engagements (`StockCommitment`, `ExpectedSupply`), traçabilité, consommables d'emballage, impression d'étiquettes, buffers, planning contraint, supervision de l'activité, contrat d'arrivée Drive (`/commerce/v1`, `PickupContract`), annulation avec devenir des produits (`CancellationDispositionService`).

### 1.2 Documents de référence

| Document | Usage ici |
|---|---|
| `immcomm/docs/traiteur/spec-traiteur.pdf` | Source des données transmises (§ 3.2 à 3.8), des règles R2, R3, R10 à R13, R19, R20. Traçabilité en annexe A. |
| `immcomm/docs/spec-imomswms-arrivee-drive-v1.md` | Conventions du préfixe `/commerce/v1` : identifiants, authentification, idempotence, erreurs. Reprises telles quelles. |
| `imomswms/docs/ecommerce-order-feedback.md` | Besoin de suivi côté e-commerce ; le § 8.3 en réalise la partie traiteur. |
| `imomswms/docs/operator-contract.md` | Contrat des commandes opérateur étendu au § 7 (F12). |
| `imomswms/docs/store-operations.md` | Supervision de l'activité étendue au § 7 (F22). |

### 1.3 Périmètre

- Reconnaissance des lignes traiteur d'une commande et conservation de leur composition.
- Référentiel traiteur d'imomswms : mode d'obtention, nomenclatures, postes de fabrication, calendrier de fabrication.
- Calcul des besoins en composants, engagements de stock, besoins d'achat.
- Ordres de fabrication : proposition, validation par le manager, exécution par l'opérateur, étiquetage, traçabilité, date limite.
- Mise en attente des produits finis, regroupement avec les courses, état de la commande, remise et paiement au retrait.
- Modification et annulation (entière ou traiteur seul) après transmission.
- Suivi publié vers immcomm, charge et calendrier de fabrication exposés.
- Situations de supervision, notifications, données de démonstration, tests.

### 1.4 Hors périmètre

- Catalogue, prix, délai commercial, capacité commerciale, créneaux, devis, partage, avis : ils restent dans immcomm.
- Commande réelle auprès d'un fournisseur (EDI) : le besoin d'achat est calculé et édité, pas transmis.
- Plan de maîtrise sanitaire (relevés de température, nettoyage) et gestion des contaminations croisées.
- Réalisation des écrans Storepilot et Storepicker : le § 9 fixe ce que le serveur leur sert.
- Toute mise en production : périmètre de démonstration, base régénérée à chaque `launch.sh`.

## 2. Constat sur l'existant

Sources lues le 10 novembre 2026.

| # | Constat | Source | Conséquence aujourd'hui |
|---|---|---|---|
| C1 | immcomm transmet une ligne traiteur comme une ligne ordinaire : `ean`, nom « produit · variante », quantité, prix unitaire. Ni variante, ni composition, ni texte du gâteau, ni allergènes, ni `lineId`. | `immcomm/…/OmsOrderAssembler.java`, `CateringOrderSnapshot.java` | Le magasin ne peut pas fabriquer à partir de ce qu'il reçoit : il doit rouvrir `/admin` d'immcomm. |
| C2 | Toute ligne doit correspondre à un `Product` connu ; sinon `NoSuchElementException` à l'enregistrement. | `engine/OrderRecorder.createFulfillmentLine` | Une commande traiteur n'est pas enregistrée. |
| C3 | `products.ean` est limité à 13 caractères ; 9 des 10 EAN traiteur de démonstration en ont de 17 à 23. | `V1__initial_schema.sql`, `immcomm/…/catering-seed.json` | Le référentiel ne peut pas accueillir ces articles. |
| C4 | Les fulfillers répartissent des produits présents en stock à un emplacement de prélèvement. Un produit fabriqué à la commande n'en a pas. | `engine/FulfillmentEngine`, `engine/fulfillers` | Lot `PENDING`, événement d'urgence haute, situations `ROUTING` ou `STOCK_UNKNOWN`. |
| C5 | Aucune notion de nomenclature, de poste ni d'ordre de fabrication. Les types d'ordres sont collecte, emballage, regroupement, protection de stock, réapprovisionnement, inventaire, devenir d'annulation. | `domain/wms/handling` | Rien ne planifie ni ne trace la fabrication. |
| C6 | L'état publié d'une commande (`RECEIVED`, `PREPARING`, `READY`, `COMPLETED`, `CANCELLED`) est déduit de la seule manutention. | `engine/commerce/PickupContract.orderState` | Une commande mixte serait `READY` dès les courses prêtes. |
| C7 | immcomm relaie l'annulation par `POST /orders/{orderRef}/cancellation`. imomswms n'expose que `POST /orders`. L'annulation n'existe que côté manager. | `immcomm/…/OmsGateway.cancel`, `engine/OrderResource`, `engine/fieldops/FieldOperationsResource` | Le relais d'annulation n'a pas de récepteur ; l'annulation du traiteur seul et le changement de créneau n'ont aucun contrat. |
| C8 | Le paiement au retrait (`payment.method = AT_PICKUP`, `state = DUE`) n'est pas transmis. | `OmsOrderAssembler` | L'opérateur qui remet la commande ignore qu'un montant est dû. |
| C9 | immcomm tient seul le jour ouvré de fabrication (`productionWeekdays`, `productionClosedDates`) et la capacité (`dailyCapacity` par variante). imomswms ne connaît ni l'un ni l'autre. | spec traiteur § 4, R2, R3 | La promesse commerciale n'est confrontée à aucune charge réelle. |
| C10 | Le schéma RetailOrder déclare les zones `AMBIENT`, `FRESH`, `FROZEN`, `HOT` ; le référentiel utilise `AMBIENT`, `CHILLED`, `FROZEN`, `HOUSEHOLD`. | `OrderResource.RETAIL_ORDER_SCHEMA`, `domain/store/TemperatureZone` | Défaut à corriger avec l'extension du schéma (I8). |

## 3. Principes

| Id | Principe | Conséquence pour la réalisation |
|---|---|---|
| P1 | immcomm promet, imomswms exécute. | immcomm fait autorité sur le catalogue, les prix, le délai, la capacité commerciale, le créneau, le paiement et la décision de modifier ou d'annuler. imomswms fait autorité sur la nomenclature, les besoins, la fabrication, la traçabilité, la mise en attente, le regroupement et la remise. Aucun des deux ne recalcule ce qui relève de l'autre. |
| P2 | Toute valeur métier est un paramètre. | Chaque paramètre a une portée, un type, des bornes validées par le serveur et une valeur de démonstration (§ 6). La valeur la plus spécifique l'emporte. |
| P3 | Le serveur calcule, les clients affichent. | Nomenclature résolue, besoins, fenêtres, charges, propositions, dates limites et situations sont calculés en Java. Storepilot et Storepicker ne font que présenter et appeler les actions. |
| P4 | Rien ne se planifie sans le manager. | Les ordres de fabrication, leurs affectations et les besoins d'achat sont des propositions ; ils ne deviennent effectifs qu'après validation (F8, F10). Seul le paramètre `autoValidateProposals` peut déléguer au serveur la validation des ordres de fabrication ; les besoins d'achat sont toujours validés par le manager. |
| P5 | Les commandes de courses ne changent pas. | Une charge RetailOrder sans aucun des blocs ajoutés suit le traitement actuel, à deux exceptions voulues : la soumission devient idempotente (I4) et les zones de température sont alignées (I8). Les tests existants passent sans modification. |
| P6 | Aucune identité de client dans la fabrication. | Les ordres, missions et étiquettes de fabrication portent la référence de commande, le créneau et la composition, jamais le nom, l'adresse ni le téléphone. L'identité n'apparaît qu'à la remise, par les écrans existants. |
| P7 | Le traiteur réutilise l'existant. | Stock daté, engagements, traçabilité, consommables, étiquettes, buffers, planning contraint, incidents, devenir d'annulation et supervision sont étendus, jamais doublés. |
| P8 | Règles de code du projet. | Code et commentaires en anglais ; Javadoc sur toutes les méthodes Java sans exception ; chaque jambe de chaque condition composée testée ; classes nouvelles dans `domain/catering` et `engine/catering`, sauf celles qui rejoignent une famille existante dans son paquet (ordre de manutention, fulfiller, initialiseur de démonstration) ; aucun paquet à classe unique ; schéma par migration Flyway. |

### 3.1 Vocabulaire

| Terme | Définition |
|---|---|
| Article traiteur | `Product` d'imomswms qui possède une fiche `CateringItem` (§ 5). |
| Mode d'obtention | `FABRICATED` : fabriqué par le magasin à un poste. `SUPPLIED` : commandé à un fournisseur pour la commande, reçu, mis en support à un poste, puis remis. `STOCKED` : prélevé dans le stock du magasin. |
| Nomenclature | Liste des composants (ingrédients et emballages de vente, tous produits référencés) et temps de travail nécessaires pour une unité d'une variante ; complétée par les choix de composition ou le parfum. |
| Poste de fabrication | Lieu et équipe où l'on fabrique (laboratoire traiteur, pâtisserie), avec son calendrier et sa capacité. |
| Unité traiteur | Une ligne traiteur d'une commande, suivie de la transmission à la remise. |
| Ordre de fabrication | Ordre de manutention de type `FABRICATION` : un poste, un jour, une ou plusieurs lignes de fabrication. |
| Ligne de fabrication | Quantité à fabriquer d'une même variante et d'une même composition, avec les unités traiteur qu'elle sert. |
| Mise à disposition | Début du créneau de la commande (`fulfillment.slot.start`). |
| Date limite | Instant jusqu'auquel le produit fini peut être consommé, calculé à la fabrication (F13). |

## 4. Contrat de réception de commande

`POST /orders` et le schéma RetailOrder restent ceux d'aujourd'hui. Trois blocs facultatifs sont ajoutés ; une charge qui n'en contient aucun est traitée comme aujourd'hui, aux deux exceptions de P5 près.

### 4.1 Champs ajoutés

```json
{
  "catering": {
    "contractVersion": "1",
    "reception": { "date": "2026-11-21", "guests": 12 },
    "deadlines": { "change": "2026-11-19T10:00:00+01:00", "cancel": "2026-11-19T10:00:00+01:00" },
    "pickupPoint": "Accueil drive piéton, à l'entrée du magasin"
  },
  "payment": { "method": "AT_PICKUP", "state": "DUE", "amount": 148.12 },
  "lines": [
    { "lineNumber": 5,
      "product": { "ean": "DEMO-TRA-GATEAU-CHIFFRE", "name": "Gâteau chiffre · 15 parts", "productType": "UNIT" },
      "requestedQuantity": 1,
      "pricing": { "basePrice": 39.90, "unitPrice": 39.90, "totalPrice": 39.90 },
      "catering": {
        "lineId": "6f1c2b7e-1d0a-4f3e-9a51-0c2d6f8e9b11",
        "variant": { "id": "15", "label": "15 parts", "persons": 15, "parts": 15 },
        "configurationKey": "9b1f…e2",
        "configuration": { "shapeId": "NUMBER", "text": "30", "flavourId": "chocolate" },
        "choiceLabels": ["Chiffre", "Chocolat"],
        "allergens": "gluten, lait, œuf, soja",
        "ingredients": "…",
        "storage": "Conserver au réfrigérateur entre 0 et +4 °C.",
        "servingAdvice": "Sortir environ 20 minutes avant dégustation."
      } }
  ]
}
```

| Champ | Type et bornes | Origine dans immcomm |
|---|---|---|
| `catering.contractVersion` | `"1"` ; le bloc `catering` racine est obligatoire dès qu'une ligne porte `catering` | constante |
| `catering.reception.date` | date ISO, facultative | `cart.reception.date` |
| `catering.reception.guests` | entier 1 à 1 000, facultatif | adultes + enfants |
| `catering.deadlines.change`, `.cancel` | instants ISO, facultatifs | `order.deadlines` (R11) |
| `catering.pickupPoint` | texte ≤ 200, facultatif | `stores[].catering.pickupPoint` |
| `payment.method` | `ONLINE` ou `AT_PICKUP` ; absent = `ONLINE` | `order.payment.method` (R10) |
| `payment.state`, `payment.amount` | `DUE` ou `COLLECTED` ; décimal ≥ 0 ; obligatoires si `method = AT_PICKUP` | `order.payment` |
| `lines[].catering.lineId` | UUID, unique dans la commande | `cateringLines[].lineId` |
| `lines[].catering.variant` | `id` `[A-Za-z0-9-]{1,20}`, `label` ≤ 60, `persons`, `pieces`, `parts`, `weightGrams` facultatifs | instantané figé (spec traiteur § 3.8) |
| `lines[].catering.configurationKey` | 64 caractères hexadécimaux, obligatoire | `CateringConfiguration.key` (SHA-256 de `{ean, variantId, configuration}`) |
| `lines[].catering.configuration` | absent ; composition `{"choices": {"<étape>": ["<choix>", …]}}` ; gâteau `{"shapeId", "text", "flavourId"}`, `text` facultatif ≤ 2 (formats de la spec traiteur § 3.4) | `cateringLines[].configuration` |
| `lines[].catering.choiceLabels` | 0 à 40 libellés ≤ 60, dans l'ordre d'affichage | `cateringLines[].choices` |
| `lines[].catering.allergens`, `.ingredients`, `.storage`, `.servingAdvice` | textes ≤ 4 000, 4 000, 1 000, 1 000 | instantané figé |

### 4.2 Règles de réception

| Id | Règle |
|---|---|
| I1 | Une ligne est traiteur si et seulement si elle porte un objet `catering`. Le nom du produit n'est jamais interprété. |
| I2 | Le schéma embarqué est étendu des trois blocs, tous facultatifs. Bloc présent et mal formé : 400 avec le chemin du champ, événement « Commande rejetée (schéma invalide) » existant. |
| I3 | `lineId` en double dans une commande, `configurationKey` absente, `configuration` qui n'est ni une composition ni un gâteau, ou ligne `catering` sans bloc `catering` racine : 400 `CATERING_LINE_INVALID`. |
| I4 | La soumission devient idempotente par `(storeCode, orderId)` : un rejeu d'une charge identique rend 202 sans créer de seconde `OrderRequest` ; une charge différente sous le même `orderId` rend 409 `ORDER_ALREADY_SUBMITTED`. (Demande du § 5 de `ecommerce-order-feedback.md` et du § 12 du contrat d'arrivée Drive ; indispensable ici, car un doublon déclencherait deux fabrications.) |
| I5 | EAN d'une ligne traiteur inconnu du référentiel : selon `autoReferenceUnknownItems`. Vrai : création d'un `Product` (nom et type de la ligne, zone `defaultTemperatureZone`) et d'un `CateringItem` en mode `defaultSourcingMode`, sans poste ni nomenclature. Faux : rien n'est créé ; l'unité traiteur garde l'EAN reçu, sans article ni ligne de lot, et passe à l'état `BLOCKED`. Dans les deux cas la commande est enregistrée et la situation `CATERING_UNREFERENCED` est produite : la commande n'est jamais perdue (C2). |
| I6 | Pour chaque ligne traiteur, `OrderRecorder` crée l'`OrderLine` existante (la ligne reste visible partout où les lignes le sont) et une `CateringUnit` qui conserve la composition reçue. |
| I7 | Une ligne traiteur n'est jamais confiée telle quelle aux fulfillers de collecte : elle est répartie par un fulfiller nouveau `CateringFulfillerFactory`, toujours exécuté avant ceux de la configuration `fulfillers` du magasin, sans y être déclaré. Unité `FABRICATED` ou `SUPPLIED`, et toute unité `BLOCKED` dont le produit est connu : la ligne passe dans un chunk `READY` marqué traiteur (colonne nouvelle `catering` de `FulfillmentChunk`), sans emplacement de prélèvement. Son lot est créé par le mécanisme existant et sert de support au suivi (F16), mais il n'est préparé que par la fabrication ou la réception fournisseur. Unité `STOCKED` : la ligne est remplacée par ses produits à prélever (F11), laissés aux fulfillers suivants. Une ligne traiteur ne retient donc jamais le reste de la commande à l'état `PENDING` ; les lignes de courses suivent leur chemin actuel (C4). |
| I8 | Les zones de température du schéma sont alignées sur `TemperatureZone` (`AMBIENT`, `CHILLED`, `FROZEN`, `HOUSEHOLD`) ; `FRESH` reste accepté comme synonyme de `CHILLED` en entrée (C10). |
| I9 | `products.ean` passe à 40 caractères (C3). Les lectures d'EAN existantes ne changent pas. |

### 4.3 Changement côté émetteur

Seul changement demandé à immcomm pour ce contrat : `OmsOrderAssembler.assemble` ajoute le bloc `payment` à toute commande réglée au retrait, avec ou sans traiteur (C8), le bloc `catering` racine quand la commande a des lignes traiteur, et l'objet `catering` de chaque entrée de `lines` qui porte `cateringLineId`, à partir de `order.cateringLines`. Aucune valeur métier n'est calculée par l'assembleur ; `configurationKey` vient de `CateringConfiguration.key`, qui regroupe déjà la liste de fabrication d'immcomm. Les autres changements d'immcomm sont listés au § 12.

## 5. Modèle de données

Tables nouvelles, créées par `V30__catering.sql`. Entités dans `domain/catering`, toutes héritées de `BaseEntity`.

| Entité (table) | Champs | Règle |
|---|---|---|
| `CateringItem` (`catering_items`) | `product` (unique), `sourcingMode`, `station`, `shelfLifeHours`, `maxAdvanceHours`, `supplierName`, `supplierLeadDays`, `cancelDispositions`, `fragile`, `active` | Fiche traiteur d'un produit. `station` est exigé à l'enregistrement d'une fiche `FABRICATED` ou `SUPPLIED` par le référentiel ; seule une fiche créée par I5 peut en être dépourvue. `shelfLifeHours`, `maxAdvanceHours`, `supplierLeadDays` et `cancelDispositions` nuls : valeur de la politique. Surcharge par magasin : `CateringItemStore` (`catering_item_stores` : `item`, `store`, `sourcingMode`, `station`, `active`). |
| `CateringRecipe` (`catering_recipes`) | `item`, `variantId`, `stepId`, `choiceId`, `flavourId`, `laborSeconds`, `revision`, `active` | Une nomenclature de base par variante (`stepId`, `choiceId`, `flavourId` nuls) ; un complément par choix de composition ou par parfum. Unicité `(item, variantId, stepId, choiceId, flavourId, revision)`. |
| `CateringRecipeLine` (`catering_recipe_lines`) | `recipe`, `component` (`Product`), `quantity` (19,6), `kind` | `kind` : `INGREDIENT` (produit consommé du stock à la fabrication : ingrédient ou emballage de vente), `PICKED` (produit remis tel quel, mode `STOCKED`). Quantité pour une unité de la variante. |
| `FabricationStation` (`fabrication_stations`) | `store`, `code` (unique par magasin), `name`, `building`, `stagingBuffer`, `printer`, `weekdays`, `closedDates`, `opensAt`, `closesAt`, `dailyMinutes`, `stagingLeadMinutes`, `maxOrderMinutes`, `active` | Poste de fabrication. `stagingLeadMinutes` nul : valeur de la politique. `printer` : imprimante (`StorePrinter`) des étiquettes du poste, facultative. `stagingBuffer` : buffer de but `STAGING` où attendent ses produits finis. |
| `FabricationStationEmployee` (`fabrication_station_employees`) | `station`, `employee` | Employés habilités au poste. |
| `CateringUnit` (`catering_units`) | `order`, `orderLine`, `lineId` (unique par commande), `ean`, `item` (nul si l'EAN est inconnu), `sourcingMode`, `variantId`, `variantLabel`, `configurationKey`, `configuration` (JSON), `choiceLabels`, `allergens`, `ingredients`, `storage`, `servingAdvice`, `quantity`, `producedQuantity`, `shortQuantity`, `availableAt`, `fulfillmentLine` (ligne du chunk traiteur, nulle si l'EAN est inconnu ou si l'unité est `STOCKED`), `state`, `revision` | Une par ligne traiteur reçue. États au § 7, F3. |
| `FabricationOrder` (`handling_orders`, discriminant `FABRICATION`) | champs de `BaseHandlingOrder` + `station`, `productionDate`, `proposalState`, `proposedEmployee`, `plannedMinutes` | Préfixe de code `FAB`. La contrainte `type in (…)` de `handling_orders` est étendue. |
| `FabricationLine` (`fabrication_lines`) | `order`, `item`, `variantId`, `configurationKey`, `plannedQuantity`, `producedQuantity`, `lostQuantity`, `shortQuantity`, `state`, `producedAt`, `useBy`, `lotCode` | Regroupe les unités de même variante et même composition (F6). |
| `FabricationConsumption` (`fabrication_consumptions`) | `line`, `component`, `segment`, `manufacturerLot`, `quantity`, `loss` | Sortie de composant d'une ligne de fabrication (F13) ; `loss` vrai pour une perte déclarée (F12). |
| `FabricationLineUnit` (`fabrication_line_units`) | `line`, `unit`, `quantity`, `producedQuantity`, `shortQuantity` | Part d'une ligne de fabrication destinée à une unité traiteur : c'est elle que l'opérateur confirme (F12). |
| `FabricationExecution` (`handling_executions`, type nouveau) | champs de `HandlingExecution` | Exécution d'un ordre de fabrication ; elle porte ses `LoadedTote` et ses `PickedItem` comme une exécution de prélèvement. |
| `CateringPurchaseNeed` (`catering_purchase_needs`) | `store`, `unit`, `supplierName`, `quantity`, `orderBy`, `dueAt`, `state`, `expectedSupply` | Besoin d'achat d'une unité `SUPPLIED` (F10). États `PROPOSED`, `VALIDATED`, `RECEIVED`, `CANCELLED`. |
| `CateringAmendment` (`catering_amendments`) | `store`, `order`, `principal`, `requestId` (unique par principal), `fingerprint`, `kind`, `payload` (JSON), `outcome`, `code`, `appliedAt` | Reçu idempotent des modifications et annulations reçues d'immcomm (F18, F19). |

Extensions de l'existant : `products.ean` à 40 caractères ; `orders` reçoit `payment_method`, `payment_state`, `payment_amount`, `payment_acknowledged_at`, `payment_acknowledged_by` ; `StockCommitment.reference` porte `FAB:<orderId>:<lineId>:<ean composant>` pour les composants ; `ExpectedSupply.reference` porte `CAT:<orderId>:<lineId>` pour les articles fournis ; `FulfillmentChunk` reçoit `catering` (I7) ; `PickingShortage.handlingOrder` devient facultatif, pour un manquant déclaré par le manager hors ordre (F3) ; le type de politique `CateringConstraints` est ajouté à `OperationalPolicySchemas.TYPES`.

## 6. Paramètres

Portées : **E** enseigne (valeurs par défaut du schéma de la politique `CateringConstraints`, servies par `OperationalPolicySchemas.defaults` comme pour les politiques existantes), **M** magasin (version active de cette politique pour le magasin), **A** poste de fabrication, **P** article, **V** variante (nomenclature). Les valeurs par défaut du schéma sont celles de la colonne « Démo ». Les paramètres M sont versionnés et validés par le schéma de la politique ; A, P et V sont validés à l'enregistrement du référentiel (400 avec le libellé du paramètre). Un paramètre de portée « E, M, P » ou « E, M, A » a sa valeur dans la politique ; l'article ou le poste la surcharge.

| Paramètre | Portée | Type et bornes | Démo | Règle |
|---|---|---|---|---|
| `enabled` | E, M | booléen | vrai | Faux : une ligne traiteur reçue passe `BLOCKED` avec situation `CATERING_DISABLED`. |
| `autoReferenceUnknownItems` | E, M | booléen | vrai | I5 |
| `defaultSourcingMode` | E, M | `FABRICATED`, `SUPPLIED`, `STOCKED` | `FABRICATED` | I5 |
| `defaultTemperatureZone` | E, M | zone | `CHILLED` | I5 |
| `sourcingMode` | P, P×M | mode | § 10 | F2 |
| `station` | P, P×M | poste actif du magasin | § 10 | F2 |
| `recipeRequired` | E, M | booléen | faux | F4 |
| `shelfLifeHours` | E, M, P | entier 1 à 720 | 48 ; gâteau 72 | F5, F13 |
| `maxAdvanceHours` | E, M, P | entier 0 à 720, ≤ `shelfLifeHours` | 24 | F5 |
| `consumptionMarginHours` | E, M | entier 0 à 72 | 6 | F5 |
| `stagingLeadMinutes` | E, M, A | entier 0 à 1 440 | 60 | F5 |
| `weekdays`, `closedDates` | A | jours 1 à 7 ; dates | tous les jours ; aucune date (§ 10) | F5, F7 |
| `opensAt`, `closesAt` | A | heures, ouverture < fermeture | 00:00, 23:59 (§ 10) | F5, F7 |
| `dailyMinutes` | A | entier 0 à 10 000 ; 0 = non limité | 480 | F7 |
| `laborSeconds` | V | entier 0 à 86 400 | § 10 | F7 |
| `defaultLaborSeconds` | E, M | entier 0 à 86 400 | 600 | F7 : unité sans nomenclature |
| `maxOrderMinutes` | A | entier 15 à 600 | 120 | F6 |
| `batchAcrossOrders` | E, M | booléen | vrai | F6 |
| `proposalHorizonDays` | E, M | entier 1 à 60 | 14 | F8 |
| `autoValidateProposals` | E, M | booléen | faux | F8 |
| `planningLeadHours` | E, M | entier 1 à 240 | 24 | F22 |
| `componentShortagePolicy` | E, M | `BLOCK`, `WARN` | `WARN` | F9 |
| `supplierLeadDays` | E, M, P | entier 0 à 30 | 2 ; pain pour le buffet 1 | F10 |
| `supplierOrderCutoff` | E, M | heure ou nul | nul | F10 |
| `labelTemplate` | E, M | code d'un `LabelTemplate` du magasin, ou nul | `CATERING_UNIT` | F14 |
| `labelCopies` | E, M | entier 1 à 5 | 1 | F14 |
| `handoverRequiresPaymentAck` | E, M | booléen | vrai | F17 |
| `amendmentAfterStart` | E, M | `REFUSE`, `ACCEPT_DECREASE` | `REFUSE` | F18 |
| `cancelAfterStart` | E, M | `REFUSE`, `ACCEPT_WITH_DISPOSITION` | `ACCEPT_WITH_DISPOSITION` | F19 |
| `cancelDispositions` | E, M, P | sous-ensemble non vide de `REALLOCATE`, `ISOLATE` | `REALLOCATE`, `ISOLATE` ; gâteau : `ISOLATE` | F19 |
| `notificationRoles` | E, M | rôles destinataires | `MANAGER` | F23 |

## 7. Règles serveur

Les règles sont implémentées dans `engine/catering`, sauf mention d'une extension d'une classe existante. L'heure de référence est celle de `DateTimeProvider` ; aucune règle n'appelle `LocalDateTime.now()` directement.

### F1 — Article traiteur

Un produit est un article traiteur s'il possède un `CateringItem` actif. La fiche est créée par le référentiel (§ 8.1) ou par I5. Un article traiteur désactivé n'accepte plus de planification nouvelle ; ses unités en cours vont à leur terme.

### F2 — Mode d'obtention

Le mode d'une unité est résolu à la réception : `CateringItemStore` du magasin, à défaut `CateringItem`, à défaut `defaultSourcingMode`. Il est figé sur l'unité : un changement de référentiel ne déplace pas une unité déjà planifiée. Une unité `FABRICATED` ou `SUPPLIED` dont aucun poste n'est résolu (fiche créée par I5) reste `RECEIVED` sous la situation `CATERING_UNREFERENCED` jusqu'à ce que la fiche soit complétée.

| Mode | Chemin de l'unité |
|---|---|
| `FABRICATED` | F4 à F9, F12 à F15 |
| `SUPPLIED` | F10, puis F6 à F8 et F12 à F15 pour la mise en support |
| `STOCKED` | F11 |

### F3 — Unité traiteur et états

| État | Entrée |
|---|---|
| `RECEIVED` | création par I6 |
| `BLOCKED` | I5 (paramètre faux), `enabled` faux, nomenclature exigée et absente (F4) |
| `PLANNED` | ordre de fabrication (fabrication, ou mise en support d'un article fourni dont le besoin d'achat est validé) ou collecte validé pour toute la quantité |
| `IN_PROGRESS` | première quantité fabriquée, mise en support ou prélevée |
| `PRODUCED` | toute la quantité fabriquée, mise en support ou prélevée |
| `STAGED` | toute la quantité déposée en attente (F15) |
| `HANDED_OVER` | commande remise ou expédiée |
| `SHORT` | toute la quantité définitivement non servie, déclarée par l'opérateur ou le manager ; une unité servie en partie garde son état et porte `shortQuantity` |
| `CANCELLED` | F19 |

Chaque changement d'état augmente `revision` de l'unité et la révision publiée de la commande (`CommercePickupState.revision`). Une commande dont une unité est `BLOCKED` produit la situation correspondante (F22) ; les autres unités avancent. Une unité `BLOCKED` revient à `RECEIVED` au premier calcul (F8) qui suit la disparition de sa cause ; si elle est `STOCKED`, ses produits à prélever rejoignent alors un chunk `PENDING` de la commande (I7).

### F4 — Nomenclature résolue

1. Base : `CateringRecipe` active de plus haute révision pour `(item, variantId)` sans choix.
2. `COMPOSITION` : pour chaque choix reçu, ajout du complément `(stepId, choiceId)` s'il existe.
3. `CAKE` : ajout du complément `flavourId` s'il existe. Forme et texte ne changent pas les composants ; ils sont servis comme consigne de fabrication.
4. Les quantités et `laborSeconds` s'additionnent, puis se multiplient par la quantité de la ligne de fabrication.
5. Aucune base : si `recipeRequired` est vrai, l'unité passe `BLOCKED` et produit `CATERING_RECIPE_MISSING` ; sinon elle se planifie avec `defaultLaborSeconds`, sans composant, et produit la même situation en nature « information manquante ».
6. La nomenclature résolue est figée sur la ligne de fabrication à la validation de l'ordre (F8) : une révision ultérieure de la recette ne change pas un ordre validé.

### F5 — Fenêtre de fabrication

Pour une unité `FABRICATED` de mise à disposition `S` :

- Fin au plus tard : `S − stagingLeadMinutes` du poste.
- Début au plus tôt : `S − maxAdvanceHours` de l'article, jamais avant l'instant présent.
- Jours admis : jours du poste (`weekdays`, hors `closedDates`), entre `opensAt` et `closesAt`.
- Contrôle de date limite : `fin de fabrication + shelfLifeHours` doit atteindre la fin du jour de `reception.date` si elle est transmise, sinon la fin du créneau + `consumptionMarginHours`. Le début au plus tôt est repoussé d'autant que nécessaire.
- Fenêtre vide (aucune plage ouverte du poste entre le début au plus tôt et la fin au plus tard) : l'unité reste `RECEIVED` et produit `FABRICATION_WINDOW` ; le serveur ne déplace jamais la mise à disposition (P1).

| Exemple | Valeurs | Fenêtre |
|---|---|---|
| Pain surprise, créneau samedi 21 novembre 10:00 | avance 24 h, attente 60 min, poste ouvert du lundi au samedi de 06:00 à 14:00 | vendredi 20 de 10:00 à 14:00, samedi 21 de 06:00 à 09:00 |
| Gâteau chiffre, même créneau | avance 24 h, limite 72 h | identique ; date limite ≥ samedi 21 à 23:59 vérifiée |

### F6 — Regroupement en ordres de fabrication

1. Candidats : unités `FABRICATED` à l'état `RECEIVED` et unités `SUPPLIED` dont le besoin d'achat est validé (F10.4), dont le poste est résolu, de mise à disposition dans `proposalHorizonDays`.
2. Jour de fabrication retenu : le dernier jour admis de la fenêtre dont la charge restante du poste suffit (F7) ; à défaut le précédent, jusqu'au début au plus tôt.
3. Ligne de fabrication : une par `(poste, jour, article, variantId, configurationKey)`. Si `batchAcrossOrders` est vrai, les unités de commandes différentes qui partagent cette clé sont additionnées ; sinon une ligne par unité. Deux gâteaux de textes différents n'ont jamais la même clé.
4. Ordre de fabrication : les lignes d'un poste et d'un jour, triées par mise à disposition la plus proche, découpées en ordres d'au plus `maxOrderMinutes` de temps prévu. Une ligne plus longue que ce maximum forme un ordre à elle seule.
5. `requestedStart` et `requestedEnd` de l'ordre : dans la fenêtre commune de ses lignes ; `plannedMinutes` : somme des temps prévus, arrondie à la minute supérieure.

### F7 — Charge et capacité du poste

Charge d'un jour = somme des `plannedMinutes` des ordres non annulés du poste ce jour-là. Capacité = `dailyMinutes` (0 : non limitée), plafonnée par le temps de présence planifié des employés habilités quand le planning existe. Charge > capacité : les unités en excès ne sont pas placées ce jour-là (F6.2) ; si aucun jour de la fenêtre ne convient, situation `FABRICATION_CAPACITY` avec les unités et les minutes manquantes.

### F8 — Proposition et validation

- Le calcul F4 à F7 s'exécute à chaque commande reçue, modification, annulation, changement de référentiel ou de politique, à chaque cycle du moteur d'activité du magasin (`StoreActivityEngine` ; l'horizon et l'instant présent avancent) et à la demande. Il produit des ordres à `proposalState = PROPOSED`, statut `OPEN`, `assignee` nul, avec un `proposedEmployee` quand un employé convient.
- Un ordre `PROPOSED` est recalculé librement. Il n'est visible d'aucun opérateur et n'engage aucun stock : les lectures opérateur, la supervision de la manutention et le planning l'ignorent.
- Validation par le manager (`POST /catering/fabrication/proposals/validate`, § 8.2) : `proposalState = VALIDATED`, unités `PLANNED`, nomenclature figée (F4.6), engagements créés (F9). Le manager peut modifier le jour, la plage et l'employé avant de valider ; le serveur revérifie F5 et F7 et refuse (409, raison servie) une valeur hors fenêtre ou hors capacité, ou un employé non habilité au poste. La lecture des propositions sert aussi `deadlines.cancel` de chaque commande : le manager voit si le client peut encore l'annuler.
- Affectation : `proposedEmployee` est un employé habilité au poste, présent sur la plage ; à la validation, `assignee` reçoit l'employé retenu par le manager, à défaut l'employé proposé ; le planning contraint existant (`ConstrainedPlanningSolver`) reçoit l'ordre validé comme toute manutention. Aucune affectation n'est appliquée sans validation (P4).
- `autoValidateProposals` vrai : la validation est appliquée par le serveur, tracée avec l'auteur `SYSTEM` ; les mêmes contrôles s'appliquent. Les besoins d'achat (F10) ne sont jamais validés automatiquement.
- Un ordre validé n'est plus touché par ce calcul ; seules une modification ou une annulation reçues (F18, F19) le changent. Une unité ajoutée ensuite forme une proposition nouvelle.

### F9 — Besoins en composants

- À la validation, chaque composant `INGREDIENT` de la nomenclature figée crée un `StockCommitment` (référence `FAB:…`, bâtiment du poste, `preparationAt` = début de l'ordre). Le stock daté existant (`DatedStockAvailability`) le prend en compte comme un engagement de commande.
- Quantité indisponible à la date de l'ordre, proposé ou validé : situation `CATERING_COMPONENT_SHORTAGE` avec le composant, la quantité manquante et les ordres touchés. `componentShortagePolicy = BLOCK` : la validation est refusée (409 `COMPONENT_SHORTAGE`) ; `WARN` : elle est acceptée.
- Les règles de réapprovisionnement existantes voient ces engagements : aucune règle nouvelle de réapprovisionnement n'est créée.
- `GET /catering/requirements` (§ 8.2) sert, par jour et par composant, le besoin, le disponible daté et l'écart.
- Annulation ou baisse d'une unité : les engagements correspondants passent `CANCELLED` ou sont réduits dans la même transaction.

### F10 — Articles fournis

1. Unité `SUPPLIED` reçue : un besoin d'achat `PROPOSED` pour l'unité ; `dueAt` = début au plus tôt de sa fenêtre de F5 (mise à disposition − `maxAdvanceHours`) ; `orderBy` = le jour de `dueAt` − `supplierLeadDays`, à l'heure `supplierOrderCutoff` (à défaut à la fin de ce jour).
2. La lecture et le bon de commande regroupent les besoins par fournisseur, article, variante et jour dû ; la validation porte sur les besoins choisis.
3. Validation par le manager : besoin `VALIDATED` et création d'un `ExpectedSupply` (`EXPECTED`, référence `CAT:…`, destination : emplacement de réception du magasin). Le bon de commande fournisseur est servi en lecture imprimable ; il n'est pas transmis (§ 1.4).
4. Mise en support : une fois son besoin validé, l'unité est candidate à la proposition (F6 à F8) comme une unité fabriquée, au poste de sa fiche, dans la fenêtre de F5 ; sa ligne de fabrication n'a pour composant que l'article reçu, et pour temps le `laborSeconds` de sa nomenclature de base, à défaut `defaultLaborSeconds`. F4.5 ne s'applique pas à une unité fournie. L'unité est `PLANNED` quand cet ordre est validé.
5. Réception par le flux existant de réception, qui reconnaît l'approvisionnement à sa référence `CAT:…` : besoin `RECEIVED` ; la quantité entre dans le stock avec le lot du fournisseur et est aussitôt engagée pour l'unité.
6. Exécution (F12) : `FABRICATE` sur la ligne place l'article reçu dans son support (`PickedItem` de la ligne de lot traiteur, source = l'emplacement de réception, lot du fournisseur), imprime l'étiquette (F14) ; il est refusé tant que l'approvisionnement n'est pas reçu (`SUPPLY_NOT_RECEIVED`). `producedAt` = heure de réception ; `useBy` = `producedAt + shelfLifeHours`, ramené à la date limite du lot du fournisseur si elle est plus proche.
7. `orderBy` dépassé sans validation, ou `dueAt` dépassé sans réception : situation `CATERING_SUPPLY_LATE`, en risque ; avérée quand la fin au plus tard de la mise en support est dépassée.

### F11 — Articles en stock

Une unité `STOCKED` est prélevée dans le stock. Avec nomenclature : ses lignes `PICKED` sont les produits à prélever (assortiment de boissons, arts de la table). Sans nomenclature : le produit lui-même. Le fulfiller `Catering` les remet aux fulfillers suivants (I7) comme des lignes ordinaires, datées de la mise à disposition, que les ordres de collecte existants préparent ; l'unité suit leur avancement (`PLANNED` à la planification de la collecte, `PRODUCED` au prélèvement complet, `STAGED` au dépôt en buffer).

### F12 — Exécution d'un ordre de fabrication

L'ordre validé et affecté apparaît dans les missions de l'opérateur par les lectures existantes (`/handling-orders/operator/orders…`). Sa lecture sert, par ligne : article, variante, quantité, libellés figés (`choiceLabels`), texte du gâteau, allergènes, composants avec quantités et emplacements, temps prévu, et, par unité servie, la référence de commande, son créneau et sa quantité (P6).

Actions nouvelles du contrat opérateur v2 (`POST /handling-orders/operator/commands`), avec les mêmes garanties de révision, de rejeu et de reçu :

| Action | Champs | Effet |
|---|---|---|
| `FABRICATE` | `itemId` (part de ligne destinée à une unité, `FabricationLineUnit`), `quantity`, `toteCode`, `componentLots` (F13) | Ajoute la quantité fabriquée pour cette unité et la place dans le support `toteCode` (règles de F15) ; consomme les composants et entre le produit fini (F13) ; à la première quantité de la ligne, fixe `producedAt`, `useBy`, `lotCode` ; imprime les étiquettes (F14). Quantité totale > prévue pour l'unité : refus `QUANTITY_EXCEEDED`. La première action passe l'ordre `PROCESSING`. |
| `FABRICATION_LOSS` | `itemId`, `quantity`, `reason` | Déclare une perte (raté, casse) : les composants de la quantité perdue sont consommés, le reste à fabriquer ne change pas, incident créé par `FieldOperationsService`. |
| `FABRICATION_SHORT` | `itemId`, `quantity`, `reason` | Déclare l'impossibilité de fabriquer pour cette unité : la quantité s'ajoute à son `shortQuantity` et à celui de la ligne, et le manquant est consigné sur la ligne de lot par le mécanisme existant des manquants de prélèvement (`PickingShortage`) ; une unité entièrement non servie passe `SHORT` ; incident, situation `CATERING_SHORT`. |
| `DEPOSIT_TOTE` (existant) | support, cellule | Dépôt du support en attente (F15). |
| `CONCLUDE` (existant) | — | Refusé tant qu'une ligne a un reste ni fabriqué ni déclaré `SHORT`, ou qu'une quantité fabriquée n'est pas déposée. |

La lecture de la mission présente, sous chaque ligne de fabrication, les unités à servir dans l'ordre de leur mise à disposition. Une unité se confirme en une action : la quantité prévue est proposée, l'étiquette sort sans action supplémentaire, le dépôt est un scan. Aucune fiche récapitulative ni double confirmation n'est imposée par le contrat.

### F13 — Traçabilité et date limite

- `useBy = producedAt + shelfLifeHours`. `producedAt` est l'heure serveur de la première quantité fabriquée de la ligne. Article fourni : F10.6.
- `lotCode` : `<code de l'ordre>-<rang de la ligne>` (exemple `2011-FAB-0003-2`). Article fourni : `lotCode` est le lot du fournisseur saisi à la réception, à défaut la référence de l'approvisionnement attendu.
- `FABRICATE` produit les mouvements de stock de la fabrication, dans la transaction de l'action : sortie de chaque composant et libération de son engagement ; entrée du produit fini, sous un lot `ManufacturerLot` (code `lotCode`, `manufacturedOn`, `useBy` = date de la date limite), directement à l'adresse interne de son support. Il y est détenu pour l'unité servie comme un produit prélevé (`PickedItem` de la ligne de lot traiteur, source = le poste), et l'expédition le débite (mécanismes existants).
- Composants : sans choix de l'opérateur, le serveur alloue les segments autorisés en FIFO ou FEFO, comme pour un prélèvement. L'opérateur peut préciser un lot dans la même action (`componentLots`), jamais dans un écran séparé ; il y est obligé là où la politique de traçabilité existante du composant exige un lot explicite (refus `LOT_REQUIRED`).
- Chaque sortie de composant est liée à la ligne de fabrication (`FabricationConsumption`, § 5). `/quality/trace` remonte ainsi d'un lot fini à ses composants et descend d'un lot de composant aux lots finis, aux commandes et aux unités servies.
- `useBy` dépassé pour un produit non remis : situation `CATERING_EXPIRED`, remise refusée (F17).

### F14 — Étiquette

À chaque `FABRICATE`, un `PrintJob` est créé par exemplaire fabriqué ou mis en support × `labelCopies`, par la file d'impression existante, sur l'imprimante du poste. `PrintingService` reçoit un type de sujet nouveau `CATERING_LOT` : code-barres = `lotCode`, gabarit `labelTemplate` du magasin s'il existe, sinon mise en page standard. Lignes servies : nom et variante, libellés figés et texte du gâteau, allergènes, conservation, `producedAt`, `useBy`, `lotCode`, référence de commande et créneau de l'unité servie, point de retrait. Aucune identité de client (P6). Aucune imprimante ou échec d'impression : l'action reste confirmée, la réimpression passe par l'écran d'impression existant.

### F15 — Mise en attente

- Le support est choisi à `FABRICATE` (`toteCode`). Destination de dépôt proposée : cellule libre du `stagingBuffer` du poste, dans la zone de température de l'article ; à défaut un autre buffer `STAGING` du magasin de même zone. La proposition est calculée par `BufferOccupancyService`.
- Un support (bac, carton, chariot) contient les produits finis d'une seule commande, et jamais un article non alimentaire (zone `HOUSEHOLD`) avec un produit alimentaire ; un article `fragile` occupe seul son support.
- Aucun emplacement compatible : situation `CATERING_STAGING_FULL` ; le dépôt reste à faire, l'ordre ne peut pas être conclu.
- Dépôt confirmé : unité `STAGED`.

### F16 — Regroupement et état de la commande

- Un produit fini ou reçu est un `PickedItem` de la ligne de lot traiteur, dans un `LoadedTote`. Les mécanismes existants qui partent des produits prélevés d'une commande (avancement, buffers, annulation, remise, chargement, expédition) voient donc les supports traiteur comme les bacs des courses, sans règle propre.
- Adaptations de l'existant : les quatre fabriques d'ordres existantes (`ZoneCollectingOrderManagerFactory`, `ProductCollectingOrderManagerFactory`, `GroupingOrderManagerFactory`, `PackingOrderManagerFactory`) excluent les lots d'un chunk traiteur. Dans `ActivitySnapshot`, un lot traiteur est planifié quand toutes ses unités sont `PLANNED` ou au-delà, et un ordre de fabrication non conclu compte parmi les préparations actives ; une ligne `SHORT` ou annulée n'est plus à préparer.
- L'étape existante (`ActivitySupervisionService.orderStage`) se calcule alors sur les lignes de courses et les lignes traiteur ensemble. État publié par `PickupContract.orderState`, inchangé dans sa correspondance :

| Condition | État publié |
|---|---|
| commande annulée | `CANCELLED` |
| commande remise ou expédiée | `COMPLETED` |
| partie courses prête (règle actuelle, ou absente) **et** toute unité traiteur `STAGED`, `SHORT` ou `CANCELLED`, au moins une `STAGED` ou une ligne de courses prête | `READY` |
| au moins une préparation de courses commencée **ou** une unité `IN_PROGRESS`, `PRODUCED` ou `STAGED` | `PREPARING` |
| sinon | `RECEIVED` |

- Une commande sans ligne de courses dont toutes les unités sont `SHORT` n'est jamais `READY` : la situation `CATERING_SHORT` la signale.
- Une commande sans unité traiteur garde exactement la règle actuelle (P5).

### F17 — Remise et paiement au retrait

- L'arrivée Drive et la remise restent celles du contrat v1. L'écran de remise reçoit en plus : le point de retrait, les supports traiteur et leur emplacement, les unités `SHORT`, et, si `payment_method = AT_PICKUP` et `payment_state = DUE`, « À régler au magasin : 148,12 € » (montant de la dernière charge reçue : commande, F18 ou F19 ; règle valable avec ou sans traiteur).
- `handoverRequiresPaymentAck` vrai : `complete` est refusé (409 `PAYMENT_ACK_REQUIRED`) tant que l'opérateur n'a pas confirmé l'encaissement dans la même action (`paymentAcknowledged: true`). L'accusé (`payment_acknowledged_at`, auteur) est publié dans le suivi (§ 8.3). imomswms n'encaisse pas et ne change pas l'état du paiement : immcomm reste l'autorité (P1).
- Produit de date limite dépassée : remise refusée (409 `CATERING_EXPIRED`) tant que le manager n'a pas déclaré l'unité non servie (`POST /catering/units/{id}/short`, § 8.2) ; un ordre de devenir `ISOLATE` retire alors le produit de son support.
- Livraison à domicile : les supports traiteur entrent dans la tournée par les mécanismes existants ; aucune règle nouvelle.

### F18 — Modification d'une commande

`PUT /commerce/v1/stores/{storeCode}/orders/{orderReference}/catering` (§ 8.3) : nouveau créneau, nouvelles quantités par `lineId`, ou les deux. Le créneau est celui de la commande entière : l'échéance des lots de courses non encore préparés suit ; une préparation de courses commencée n'est pas un motif de refus. La charge peut porter `deadlines` et `payment` recalculés par immcomm ; ils remplacent les valeurs conservées.

1. Reçu idempotent par `requestId` (`CateringAmendment`) : rejeu identique → même réponse ; même clé, autre contenu → 409 `IDEMPOTENCY_CONFLICT` (code existant du contrat d'arrivée Drive).
2. `expectedRevision` différente de la révision publiée : 409 `REVISION_CONFLICT`.
3. Unité sans fabrication commencée (`RECEIVED`, `PLANNED`, `BLOCKED`) : la modification s'applique ; ses lignes de fabrication, engagements et besoins d'achat sont réduits, déplacés ou annulés dans la même transaction. Dans un ordre validé, une baisse réduit la ligne et l'ordre reste validé ; une hausse ou un nouveau créneau retire de l'ordre les quantités concernées, qui forment une proposition nouvelle ; le manager est notifié (F23).
4. Unité commencée (`IN_PROGRESS` et au-delà) : selon `amendmentAfterStart`. `REFUSE` : 409 `FABRICATION_STARTED`, rien n'est appliqué à aucune unité. `ACCEPT_DECREASE` : une baisse est acceptée, la quantité déjà fabriquée en excès suit F19.4 ; une hausse et un changement de créneau qui sort le produit de sa date limite sont refusés (409 `FABRICATION_STARTED`, 409 `USE_BY_EXCEEDED`).
5. Nouveau créneau : F5 est recalculée ; fenêtre vide → la modification est appliquée (P1) et la situation `FABRICATION_WINDOW` est produite ; la réponse porte l'avertissement `FABRICATION_WINDOW_EMPTY`.
6. `dryRun=true` : mêmes contrôles et même réponse, aucune écriture, aucun reçu.
7. Un besoin d'achat `VALIDATED` touché n'est pas modifié seul : situation `CATERING_SUPPLY_CHANGE` pour décision du manager.
8. Quantité 0 : retrait de la ligne ; l'unité est annulée selon F19.4.

### F19 — Annulation

`POST /orders/{orderRef}/cancellation` reçoit le contrat que relaie déjà `OmsGateway.cancel` : `{orderRef, reason}`, complété de `requestId` (UUID), de `scope` (`ALL` par défaut, ou `CATERING`) et, s'il a changé, du `payment` recalculé par immcomm. Ce récepteur vaut pour toute commande, avec ou sans traiteur (C7).

1. Idempotent par `requestId` ; sans `requestId`, par `(orderRef, scope)`.
2. `scope = ALL` : annulation de la commande par `CancellationDispositionService` (flux et politiques existants pour les courses) et des unités traiteur.
3. `scope = CATERING` : seules les unités traiteur sont annulées ; lignes de courses, créneau et bacs sont inchangés ; la commande reste ouverte.
4. Unité non commencée : `CANCELLED`, lignes de fabrication réduites, engagements et besoins `PROPOSED` annulés. Unité commencée : selon `cancelAfterStart`. `REFUSE` : 409 `FABRICATION_STARTED`, rien n'est annulé, courses comprises. `ACCEPT_WITH_DISPOSITION` : unité `CANCELLED`, et un ordre de devenir existant (`CancellationDispositionOrder`) est créé pour les quantités fabriquées, avec les politiques existantes que `cancelDispositions` autorise pour l'article : `REALLOCATE` (le produit sert une autre unité non encore fabriquée de même article, variante et `configurationKey`, si sa date limite le permet ; la fabrication de cette unité est réduite d'autant) ou, à défaut, `ISOLATE` (support distinct déposé en buffer). `RETURN` ne s'applique jamais à un produit fabriqué ou fourni. Le sort final d'un produit isolé (destruction, don) est décidé par le manager hors du système. Unité `STOCKED` : ses lignes de lot suivent le flux d'annulation existant des courses.
5. Besoin d'achat `VALIDATED` : situation `CATERING_SUPPLY_CHANGE`. Article reçu et pas encore mis en support : son engagement est libéré, il reste en stock. Article mis en support : point 4.
6. Réponse 202 : `{orderRef, scope, outcome: CANCELLED, revision, units: [{lineId, state, disposition}]}`.
7. Commande déjà annulée : 202 avec l'état courant, sans effet. Commande déjà remise : 409 `PICKUP_COMPLETED` ; commande inconnue ou magasin non autorisé : 404 sans détail.
8. L'annulation par le manager (`POST /field-operations/customer-orders/{id}/cancel` existant, lignes choisies) applique les points 4 et 5 aux lignes traiteur.

### F20 — Suivi publié

`GET /commerce/v1/stores/{storeCode}/orders/{orderReference}/catering` (§ 8.3) sert l'état de chaque unité. Le serveur ne publie ni nom d'employé, ni emplacement, ni lot de composant. La lecture existante `…/pickup` reste inchangée ; sa révision est la même.

### F21 — Charge et calendrier exposés

`GET /commerce/v1/stores/{storeCode}/catering/production?from=&to=` sert, par jour et par poste : ouvert ou fermé, minutes de capacité, minutes engagées (ordres validés) et proposées, et, par article et variante, les quantités engagées. immcomm garde sa capacité commerciale (R3) ; cette lecture lui permet de la confronter à la charge réelle sans qu'imomswms ne refuse jamais une commande (P1).

### F22 — Supervision

Situations ajoutées à `ActivitySupervisionService`, avec la même structure que les situations existantes (identité stable, nature, gravité, cause, conséquences, commandes concernées, propositions, actions). La gravité suit les règles existantes.

| Situation | Détection | Nature | Action proposée |
|---|---|---|---|
| `CATERING_UNREFERENCED:<ean>` | I5, F2 | information manquante ; avéré si `BLOCKED` | compléter la fiche |
| `CATERING_DISABLED` | unité reçue, `enabled` faux | avéré | activer ou annuler |
| `CATERING_RECIPE_MISSING:<article>:<variante>` | F4.5 | information manquante ; avéré si `BLOCKED` | saisir la nomenclature |
| `FABRICATION_UNPLANNED` | unités `RECEIVED` à moins de `planningLeadHours` de leur fin au plus tard, hors unités déjà couvertes par `CATERING_UNREFERENCED`, `FABRICATION_WINDOW` ou `FABRICATION_CAPACITY` | risque ; avéré si dépassée | valider la proposition |
| `FABRICATION_WINDOW:<poste>` | F5, F18.5 | avéré | ouvrir le poste ce jour-là, changer l'avance, contacter le commerce |
| `FABRICATION_CAPACITY:<poste>:<jour>` | F7 | risque ; avéré le jour même | déplacer dans la fenêtre, habiliter un employé |
| `FABRICATION_LATE:<ordre>` | ordre validé non conclu après sa fin au plus tard | avéré | réaffecter |
| `CATERING_COMPONENT_SHORTAGE:<composant>` | F9 | risque ; avéré si l'ordre a commencé | réapprovisionnement existant |
| `CATERING_SUPPLY_LATE:<fournisseur>` | F10.7 | risque ; avéré après la fin au plus tard | valider, relancer |
| `CATERING_SUPPLY_CHANGE:<besoin>` | F18.7, F19.5 | avéré | décision du manager |
| `CATERING_STAGING_FULL:<buffer>` | F15 | avéré | libérer, autre buffer |
| `CATERING_SHORT:<commande>` | F12 | avéré | prévenir le commerce |
| `CATERING_EXPIRED:<lot>` | F13 | avéré | déclarer l'unité non servie, isoler le produit |

Les situations existantes (`OVERDUE`, `PLANNING`, `ROUTING`, `STOCK_UNKNOWN`) ne se déclenchent plus pour les lignes traiteur `FABRICATED` et `SUPPLIED`, que couvrent les situations ci-dessus. Les actions sur un ordre validé (réaffecter, déplacer) passent par `POST /store-operations/supervision/apply` existant, qui revérifie F5, F7 et l'habilitation au poste. Indicateurs ajoutés au rapport : unités traiteur à fabriquer dans l'horizon, charge des postes par jour, unités en attente de remise.

### F23 — Notifications

Par `OperatorSupportService.systemNotice`, vers les employés actifs du magasin qui ont un des rôles `notificationRoles` : commande traiteur reçue (« Commande traiteur DEMO-TRA-001 pour le samedi 21 novembre, 10:00 – 11:00 »), proposition à valider, modification ou annulation reçue touchant un ordre validé, unité `SHORT`. Une notice par fait, dédoublonnée par sa clé. Un échec de notification n'empêche jamais l'opération.

## 8. Contrats API

Conventions existantes : JSON, rôles par annotation, magasin du compte vérifié, erreurs opérateur de `OperatorFailure`, erreurs commerce de `CommerceFailure` (`code`, `retryable`), `Cache-Control: no-store` sur `/commerce/v1`.

### 8.1 Référentiel — `/catering` (lectures ADMIN, MANAGER)

| Méthode et chemin | Entrée | Sortie | Erreurs |
|---|---|---|---|
| `GET /catering/items?storeId=&q=&mode=` | — | fiches avec mode résolu, poste, nomenclatures présentes ou manquantes par variante connue | 403 |
| `PUT /catering/items` | fiche `CateringItem` (ADMIN) | fiche | 400 libellé du paramètre |
| `PUT /catering/items/{id}/stores/{storeId}` | surcharge `CateringItemStore` (ADMIN, MANAGER du magasin) | surcharge | 400, 403 |
| `GET /catering/items/{id}/recipes` | — | nomenclatures et lignes, révisions | 404 |
| `PUT /catering/items/{id}/recipes` | nomenclature, base ou complément (ADMIN) | révision nouvelle | 400 composant inconnu, quantité ≤ 0 |
| `GET /catering/stations?storeId=` | — | postes, calendrier, employés habilités | 403 |
| `PUT /catering/stations` | poste (ADMIN, MANAGER du magasin) | poste | 400, 403 |
| `PUT /catering/stations/{id}/employees` | identifiants d'employés du magasin (ADMIN, MANAGER du magasin) | liste | 400 employé d'un autre magasin |

La fiche et ses nomenclatures sont communes à l'enseigne : seul un administrateur les écrit. La surcharge, les postes et les habilitations sont propres à un magasin. Les paramètres de portée M passent par les lectures et écritures existantes des politiques opérationnelles, type `CateringConstraints`.

### 8.2 Fabrication — `/catering` (ADMIN, MANAGER ; lectures aussi PICKER)

| Méthode et chemin | Entrée | Sortie | Erreurs |
|---|---|---|---|
| `GET /catering/units?storeId=&from=&to=&state=` | — | unités : commande, créneau, article, variante, composition, quantité, mode, état, ordre ou besoin lié | 403 |
| `POST /catering/units/{id}/short` | `storeId`, `quantity`, `reason`, révision (MANAGER : le manquant porte l'identité d'un employé) | unité | 409 `STALE_REVISION`, `QUANTITY_EXCEEDED` |
| `GET /catering/fabrication/proposals?storeId=` | — | ordres `PROPOSED` : poste, jour, plage, lignes, minutes, employé proposé, charge et capacité du jour, composants manquants, limite d'annulation des commandes | 403 |
| `POST /catering/fabrication/proposals/recompute` | `storeId` | même sortie | 403 |
| `POST /catering/fabrication/proposals/validate` | `storeId`, `orders[]` : `id`, `revision`, `productionDate`, `start`, `end`, `employeeId` facultatifs | ordres validés | 409 `STALE_REVISION`, `OUT_OF_WINDOW`, `OVER_CAPACITY`, `COMPONENT_SHORTAGE`, `EMPLOYEE_NOT_QUALIFIED` |
| `GET /catering/fabrication/sheet?storeId=&date=&stationId=` | — | feuille de fabrication imprimable du jour : lignes regroupées, compositions, allergènes, commandes et créneaux, date de réception et nombre de convives | 403 |
| `GET /catering/requirements?storeId=&from=&to=` | — | par jour et composant : besoin, disponible daté, écart, ordres | 403 |
| `GET /catering/purchase-needs?storeId=&state=` | — | besoins d'achat regroupés par fournisseur | 403 |
| `POST /catering/purchase-needs/validate` | `storeId`, `ids[]`, révisions | besoins validés, approvisionnements attendus | 409 `STALE_REVISION` |
| `GET /catering/purchase-needs/order-form?storeId=&supplier=&dueDate=` | — | bon de commande fournisseur imprimable | 403 |

### 8.3 Commerce — `/commerce/v1` (SERVICE_CALLER, magasin autorisé)

`GET /commerce/v1/stores/{storeCode}/orders/{orderReference}/catering`

```json
{
  "contractVersion": "1", "sourceSystem": "IMMCOMM",
  "storeCode": "MAGASIN_RECETTE", "orderReference": "COMMANDE_RECETTE_001",
  "revision": 9, "orderState": "PREPARING", "updatedAt": "2026-11-20T11:42:00Z",
  "payment": { "method": "AT_PICKUP", "state": "DUE", "acknowledgedAt": null },
  "units": [
    { "lineId": "6f1c2b7e-1d0a-4f3e-9a51-0c2d6f8e9b11", "state": "PRODUCED",
      "requestedQuantity": 1, "producedQuantity": 1, "shortQuantity": 0,
      "plannedFor": "2026-11-20", "producedAt": "2026-11-20T11:40:00Z",
      "useBy": "2026-11-23T11:40:00Z" } ]
}
```

`PUT /commerce/v1/stores/{storeCode}/orders/{orderReference}/catering[?dryRun=true]`

```json
{ "contractVersion": "1", "sourceSystem": "IMMCOMM",
  "requestId": "81c9ad6a-9c2e-4b5c-a1e5-694f48d150f3", "expectedRevision": 9,
  "slot": { "start": "2026-11-21T12:00:00+01:00", "end": "2026-11-21T14:00:00+01:00" },
  "lines": [ { "lineId": "6f1c2b7e-1d0a-4f3e-9a51-0c2d6f8e9b11", "quantity": 2 } ] }
```

Réponse 200 : le suivi ci-dessus, complété de `outcome` (`APPLIED`, `PREVIEW`) et de `warnings`. `slot` et `lines` sont facultatifs, l'un des deux exigé. `deadlines` et `payment` sont facultatifs. Une quantité 0 retire la ligne (F18.8).

`GET /commerce/v1/stores/{storeCode}/catering/production?from=&to=` (F21), fenêtre d'au plus 62 jours (400 au-delà).

| Code | HTTP | Sens |
|---|---|---|
| `INVALID_REQUEST` | 400 | charge mal formée, version ou source inattendue |
| `ORDER_NOT_FOUND` | 404 | commande inconnue de la source et du magasin autorisés |
| `NO_CATERING_UNIT` | 409 | commande sans unité traiteur |
| `REVISION_CONFLICT` | 409 | révision attendue dépassée |
| `IDEMPOTENCY_CONFLICT` | 409 | même `requestId`, autre contenu |
| `FABRICATION_STARTED` | 409 | F18.4, F19.4 |
| `USE_BY_EXCEEDED` | 409 | F18.4 |
| `ORDER_CANCELLED`, `PICKUP_COMPLETED` | 409 | états terminaux |

### 8.4 Extensions de contrats existants

| Contrat | Extension | Erreurs ajoutées |
|---|---|---|
| `POST /orders` (SERVICE_CALLER) | blocs `catering`, `payment` et `lines[].catering` (§ 4) ; idempotence (I4) | 400 `CATERING_LINE_INVALID`, 409 `ORDER_ALREADY_SUBMITTED` |
| `POST /orders/{orderRef}/cancellation` (SERVICE_CALLER, nouveau) | `{orderRef, reason, requestId, scope, payment}` ; réponse 202 (F19.6) | 404, 409 `FABRICATION_STARTED`, `PICKUP_COMPLETED`, `IDEMPOTENCY_CONFLICT` |
| `POST /field-operations/customer-orders/{id}/cancel` | lignes traiteur annulées selon F19.4 et F19.5 (F19.8) | 409 `FABRICATION_STARTED` |
| `POST /handling-orders/operator/commands` | actions `FABRICATE`, `FABRICATION_LOSS`, `FABRICATION_SHORT` ; `DEPOSIT_TOTE` et `CONCLUDE` sur un ordre de fabrication (F12) | `QUANTITY_EXCEEDED`, `LOT_REQUIRED`, `SUPPLY_NOT_RECEIVED` |
| lectures `/handling-orders/operator/orders…` | ordre de fabrication et ses lignes (F12) ; ordres `PROPOSED` exclus (F8) | — |
| `POST /drive-arrivals/complete` | entrée `paymentAcknowledged` ; la file `GET /drive-arrivals` sert les supports traiteur et le montant dû (F17) | 409 `PAYMENT_ACK_REQUIRED`, `CATERING_EXPIRED` |
| `GET /store-operations/activity-supervision` | situations et indicateurs (F22) | — |

## 9. Ce que le serveur sert aux écrans

La réalisation des écrans n'est pas l'objet de ce document ; le serveur doit leur fournir tout le contenu, déjà calculé et libellé (P3).

| Écran | Client | Lectures et actions |
|---|---|---|
| Traiteur › À valider | Storepilot | propositions, charge et capacité, composants manquants ; valider, recalculer (§ 8.2) |
| Traiteur › Fabrication du jour | Storepilot | feuille de fabrication, avancement des ordres et des unités, impression ; déclarer une unité non servie |
| Traiteur › Besoins | Storepilot | besoins en composants, besoins d'achat, bon de commande |
| Traiteur › Référentiel | Storepilot | fiches, nomenclatures, postes, habilitations (§ 8.1) |
| Supervision | Storepilot | situations F22, indicateurs |
| Mission Fabrication | Storepicker | lecture de l'ordre, actions F12, étiquette, dépôt |
| Remise | Storepilot, Storepicker | supports traiteur, montant dû, accusé d'encaissement (F17) |

## 10. Données de démonstration

Ajoutées par un initialiseur nouveau `DemoCateringInitializer` (paquet `auth`, avec les autres initialiseurs de démonstration), exécuté après `DemoStockInitializer` et avant `DemoMarieMissions`. La base étant régénérée à chaque `launch.sh`, toutes les dates sont relatives au jour du lancement.

| Élément | Contenu |
|---|---|
| Politique | `CateringConstraints` aux valeurs « Démo » du § 6, liée au magasin de démonstration. |
| Postes | `LAB` « Laboratoire traiteur » et `PAT` « Pâtisserie », bâtiment du magasin, un buffer `STAGING` réfrigéré et une imprimante simulée chacun, ouverts tous les jours de 00:00 à 23:59 pour que le scénario fonctionne quels que soient le jour et l'heure du lancement, 480 minutes. |
| Habilitations | Marie Martin (100235) et un second employé au poste `LAB` ; un employé au poste `PAT`. |
| Articles | Les dix EAN `DEMO-TRA-…` du jeu d'immcomm, mêmes noms. |
| Étiquette | Gabarit `CATERING_UNIT` (F14). |

| EAN | Mode | Poste | Nomenclature de démonstration |
|---|---|---|---|
| `DEMO-TRA-PAIN-SURPRISE` | `FABRICATED` | `LAB` | base par variante 16, 32, 48 (pain, garnitures, boîte) ; 10, 15, 20 min |
| `DEMO-TRA-PLATEAU-VEGE` | `FABRICATED` | `LAB` | base 24, 48 ; 15, 25 min |
| `DEMO-TRA-VERRINES` | `FABRICATED` | `LAB` | base 24 ; 30 min |
| `DEMO-TRA-BUFFET-FROID` | `FABRICATED` | `LAB` | base 12 + un complément par choix ; 45 min |
| `DEMO-TRA-MENU` | `FABRICATED` | `LAB` | base + un complément par choix des quatre étapes ; 8 min |
| `DEMO-TRA-GATEAU-CHIFFRE` | `FABRICATED` | `PAT` | base 8, 15 + un complément par parfum ; 40, 60 min ; `fragile` |
| `DEMO-TRA-TARTE-POMMES` | `SUPPLIED` | `LAB` | fournisseur « Fournisseur de démonstration », 2 jours |
| `DEMO-TRA-PAIN-BUFFET` | `SUPPLIED` | `LAB` | même fournisseur, 1 jour |
| `DEMO-TRA-BOISSONS` | `STOCKED` | — | lignes `PICKED` : produits de boissons du stock de démonstration |
| `DEMO-TRA-ARTS-TABLE` | `STOCKED` | — | lignes `PICKED` : produits non alimentaires du stock de démonstration |

| Commande de démonstration | Contenu | État au lancement |
|---|---|---|
| `DEMO-TRA-001`, retrait J+2 10:00, paiement au retrait | courses (4 lignes) + 2 pains surprises 32, 1 buffet froid, 1 gâteau chiffre 15 parts « 30 » chocolat, 1 boissons | propositions à valider |
| `DEMO-TRA-002`, retrait J+1 à l'heure du lancement, payée en ligne | 12 menus (même composition), 1 tartes aux pommes | besoin d'achat validé ; ordre `FAB` validé (menus et mise en support des tartes), affecté à Marie Martin, à faire aujourd'hui |
| `DEMO-TRA-003`, retrait 2 heures après le lancement | 1 plateau végétarien 24, 1 verrines | fabriqué, étiqueté, en attente : commande `READY` |
| `DEMO-TRA-004`, retrait J+3 | 1 gâteau chiffre 15 parts cœur, composant manquant | proposition avec composant manquant : situation `CATERING_COMPONENT_SHORTAGE` |

`DemoMarieMissions` compte `FabricationOrder` dans sa règle « une mission par type » et retient l'ordre de `DEMO-TRA-002` : Marie Martin a toujours un ordre de fabrication qui lui est affecté.

## 11. Tests et recette

### 11.1 Tests

| Classe | Couverture |
|---|---|
| `CateringIntakeTest` | I1 à I9 : charge sans bloc inchangée ; bloc mal formé ; `lineId` en double ; rejeu identique et différent ; EAN inconnu, paramètre vrai et faux ; EAN de 23 caractères ; `FRESH` accepté. |
| `CateringUnitLifecycleTest` | F1 à F3, F11 : fiche active et désactivée ; mode résolu par magasin, article, politique ; mode figé ; poste non résolu ; chaque état et son retour depuis `BLOCKED` ; article en stock avec et sans nomenclature. |
| `CateringRecipeTest` | F4 : base seule, compléments de choix, parfum, révision la plus haute, nomenclature absente avec `recipeRequired` vrai et faux, figeage à la validation. |
| `FabricationWindowTest` | F5 : chaque borne, début ramené à l'instant présent, jour fermé, date fermée, heure d'ouverture, date limite avec et sans `reception.date`, fenêtre vide. |
| `FabricationPlanningTest` | F6 à F8 : regroupement avec `batchAcrossOrders` vrai et faux, découpage par `maxOrderMinutes`, capacité 0 et atteinte, report au jour précédent, validation avec chaque refus, validation automatique, ordre validé non recalculé. |
| `CateringRequirementsTest` | F9, F10 : engagements créés, réduits, annulés ; `BLOCK` et `WARN` ; besoins d'achat regroupés, heure limite, validation, mise en support proposée et validée, réception, `FABRICATE` avant et après réception, date limite du lot du fournisseur, retard. |
| `FabricationExecutionTest` | F12 à F15 : chaque action, rejeu et révision ; dépassement ; perte ; impossibilité ; composants alloués par le serveur et lot choisi par l'opérateur, lot exigé par la politique absent ; étiquette, poste sans imprimante et échec d'impression ; dépôt, buffer plein, séparation alimentaire, une action par unité servie d'une ligne regroupée. |
| `CateringOrderStateTest` | F16 : chaque ligne de la table d'états, commande sans traiteur inchangée, lot traiteur jamais pris par une fabrique de collecte, d'emballage ou de regroupement. |
| `CateringHandoverTest` | F17 : montant dû servi avec et sans traiteur, accusé exigé ou non, date limite dépassée puis unité déclarée non servie. |
| `CateringAmendmentTest` | F18 : chaque point, `dryRun`, chaque valeur de `amendmentAfterStart`. |
| `CateringCancellationTest` | F19 : `ALL`, `CATERING`, commande sans traiteur, rejeu, commande déjà annulée, chaque valeur de `cancelAfterStart`, réaffectation et isolement, annulation par le manager, besoin validé, commande remise. |
| `CateringCommerceContractTest` | § 8.3 : droits, magasin étranger, chaque code d'erreur, fenêtre de 62 jours. |
| `CateringSupervisionTest` | F22, F23 : chaque notice, son dédoublonnage et son échec sans effet ; chaque situation dans chacune de ses natures ; situations existantes non déclenchées pour le traiteur. |
| `CateringMigrationTest` | `V30` sur base vide et sur base peuplée en V29. |

Les suites existantes passent sans modification (P5).

### 11.2 Scénario de recette

1. `launch.sh` : la base est régénérée ; Supervision affiche `CATERING_COMPONENT_SHORTAGE` pour `DEMO-TRA-004` et `CATERING_SUPPLY_LATE`, en risque, pour les tartes aux pommes de `DEMO-TRA-002`, attendues.
2. Storepilot › Traiteur › À valider : propositions de `DEMO-TRA-001` — un ordre `LAB` (2 pains surprises, 1 buffet froid) et un ordre `PAT` (gâteau « 30 ») le dernier jour ouvert de leur fenêtre ; charge 75 / 480 et 60 / 480 minutes.
3. Valider : ordres visibles de leurs employés ; besoins en composants mis à jour.
4. Storepicker, Marie Martin : mission `FAB` de `DEMO-TRA-002` ; « Fabriqué » sur les 12 menus ; 12 étiquettes sortent ; la ligne des tartes aux pommes attend sa réception.
5. Traiteur › Besoins : tartes aux pommes de `DEMO-TRA-002` attendues du fournisseur ; réception. Marie Martin confirme leur mise en support : étiquette ; dépôt du support dans le buffer du laboratoire ; mission conclue.
6. Lecture `…/orders/DEMO-TRA-002/catering` : toutes les unités `STAGED` ; `…/pickup` : `READY`.
7. `PUT …/orders/DEMO-TRA-001/catering?dryRun=true` avec un créneau qui commence dans 30 minutes : avertissement `FABRICATION_WINDOW_EMPTY`.
8. `POST /orders/DEMO-TRA-001/cancellation` avec `scope: CATERING` et le `payment` recalculé : unités annulées, engagements libérés, courses inchangées.
9. Annuler `DEMO-TRA-003` (déjà fabriquée) : ordre de devenir en isolement (`ISOLATE`), aucune autre unité ne pouvant reprendre ces produits.
10. Remise de `DEMO-TRA-002`, payée en ligne : aucun accusé demandé. Remise des courses de `DEMO-TRA-001` une fois préparées : « À régler au magasin » au montant reçu avec l'annulation de l'étape 8, accusé exigé.

## 12. Changements hors imomswms

| Application | Changement | Lien |
|---|---|---|
| immcomm | `OmsOrderAssembler` : blocs `catering` et `payment` (§ 4.3). | I1 à I6 |
| immcomm | `OmsGateway.cancel` : ajout de `requestId`, de `scope` et du `payment` recalculé à la charge d'annulation ; prise en compte des refus 409 ; annulation partielle (R12) relayée avec `scope: CATERING`. | F19 |
| immcomm | `APPLY_ORDER_CHANGE` (R13) : appel de `PUT …/catering` en `dryRun` pour l'aperçu, puis réel avant d'appliquer, avec `deadlines` et `payment` recalculés ; refus d'imomswms présenté à l'équipe. | F18 |
| immcomm | Lecture du suivi `…/catering` pour afficher l'avancement et reprendre l'accusé d'encaissement dans « Encaissé au retrait » (R10). | F17, F20 |
| immcomm | `/admin` › Traiteur › Fabrication : lecture de `…/catering/production` à côté de la capacité commerciale. | F21 |
| Storepilot | Tuile Traiteur et écrans du § 9. | § 9 |
| Storepicker | Mission Fabrication, sur spécification de conception séparée. | F12 |

Dépendance déjà connue et inchangée : la correspondance des codes de magasin entre immcomm et imomswms (§ 12 du contrat d'arrivée Drive).

## Annexe A — Traçabilité vers la spécification traiteur d'immcomm

| Spec traiteur immcomm | Sujet | Ici |
|---|---|---|
| § 3.2, § 3.3 | Produit et variante | § 4.1, § 5 `CateringItem`, F1, F2 |
| § 3.4 | Configurations `COMPOSITION`, `CAKE` ; clé de configuration | § 4.1, F4, F6 |
| § 3.8 | Commande : `cateringLines`, `lines`, `payment`, `deadlines` | § 4, I6 |
| R2 | Jours ouvrés de fabrication | F5, F21, paramètres `weekdays`, `closedDates` |
| R3 | Capacité de fabrication | F7, F21 |
| R10 | Paiement au retrait | § 4.1 `payment`, F17 |
| R11 | Dates limites de modification et d'annulation | § 4.1 `deadlines`, F8 (limite servie au manager), F18 |
| R12 | Annulation entière et partielle | F19 |
| R13 | Demandes de modification | F18 |
| R19 | Fabrication et file du magasin | F6, F8, F12, § 8.2 feuille de fabrication, P6 |
| R20 | Notification au magasin | F23 |
| § 1.4 « connecteurs réels (OMS, stock magasin, fabrication) » | Hors périmètre d'immcomm | objet de ce document |
