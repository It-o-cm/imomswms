# imomswms — Traiteur & réceptions : points de réalisation

Plan de réalisation v1 · 11 novembre 2026 · 453 points, chacun rattaché à la spécification `imomswms/docs/spec-traiteur-v1.md`, regroupés en 9 lots ordonnés par dépendance · démonstration uniquement.

## Lecture

Chaque ligne est un point à réaliser, numéroté par lot (`T3.042`), avec sa nature et sa référence dans la spécification : section (§), principe (P), constat (C), règle de réception (I) ou règle serveur (F). Un lot ne commence qu'une fois ses dépendances réalisées ; dans un lot, les points sont dans l'ordre de réalisation et les tests suivent les règles qu'ils couvrent.

Ce plan couvre imomswms et les changements demandés à immcomm. Les écrans de Storepilot et de Storepicker n'y figurent pas : la spécification fixe ce que le serveur leur sert (lot T6), pas leur conception.

| Lot | Contenu | Dépend de | Points |
|---|---|---|---|
| T0 | Socle, modèle de données et paramètres | — | 81 |
| T1 | Réception de la commande et référentiel | T0 | 61 |
| T2 | Planification : unités, nomenclature, fenêtre, proposition, besoins | T1 | 73 |
| T3 | Exécution : articles fournis et en stock, fabrication, traçabilité, étiquette, mise en attente | T2 | 71 |
| T4 | Commande : état publié, remise et paiement au retrait | T3 | 31 |
| T5 | Modification, annulation et suivi publié | T3 ; l'accusé d'encaissement publié suit T4 | 51 |
| T6 | Supervision, notifications et contenus servis aux écrans | T2 à T5 | 36 |
| T7 | Données de démonstration et recette | tous | 34 |
| T8 | Changements dans immcomm | contrats de T1 et de T5 | 15 |
| | **Total** | | **453** |

| Nature | Sens | Points |
|---|---|---|
| Règle | règle serveur à implémenter | 168 |
| Test | cas de test à écrire | 93 |
| API | contrat d'échange ou contenu servi | 45 |
| Paramètre | paramètre, sa validation et sa résolution | 37 |
| Donnée | table, colonne ou entité | 31 |
| Adaptation | modification d'un mécanisme existant d'imomswms | 28 |
| Démo | donnée de démonstration | 22 |
| Émetteur | changement dans immcomm | 12 |
| Recette | étape du scénario de recette | 10 |
| Socle | règle transverse | 7 |

## T0 — Socle, modèle de données et paramètres

81 points · dépend de : —.

### Règles de code et socle technique

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T0.001 | Code et commentaires en anglais ; Javadoc sur chaque méthode Java nouvelle ou modifiée, constructeurs, accesseurs, méthodes privées et méthodes redéfinies compris. | Socle | P8 |
| T0.002 | Classes nouvelles dans `domain/catering` et `engine/catering` ; `FabricationOrder` rejoint `domain/wms/handling`, `FabricationExecution` `domain/executing`, `CateringFulfillerFactory` `engine/fulfillers`, le constructeur d'ordres de fabrication `engine/handling`, `DemoCateringInitializer` `auth` ; aucun paquet à classe unique. | Socle | P8 |
| T0.003 | Toute règle lit l'heure par `DateTimeProvider` ; aucun appel direct à `LocalDateTime.now()` dans le code traiteur. | Socle | § 7 intro |
| T0.004 | Erreurs des contrats opérateur par `OperatorFailure`, erreurs commerce par `CommerceFailure` (`code`, `retryable`), `Cache-Control: no-store` sur `/commerce/v1`. | Socle | § 8 intro |
| T0.005 | Non-régression : une charge sans aucun des blocs ajoutés suit le traitement actuel, aux seules exceptions I4 et I8 ; les suites existantes passent sans modification. | Socle | P5 |
| T0.006 | Chaque jambe de chaque condition composée du code traiteur est couverte par un cas de test. | Socle | P8 |
| T0.007 | Aucune identité de client (nom, adresse, téléphone) dans les ordres, missions, feuilles et étiquettes de fabrication : contrôle par test sur chaque lecture servie. | Socle | P6 |

### Migration `V30__catering.sql`

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T0.008 | Créer `V30__catering.sql` ; ne modifier aucune migration déjà livrée. | Donnée | § 5 |
| T0.009 | `products.ean` passe à 40 caractères ; longueur de `Product.ean` alignée dans l'entité. | Donnée | I9 ; C3 |
| T0.010 | Table `catering_items` : `product` unique, mode, poste, durées, fournisseur, devenirs, `fragile`, `active`. | Donnée | § 5 |
| T0.011 | Table `catering_item_stores` : surcharge par magasin, unicité `(item, store)`. | Donnée | § 5 |
| T0.012 | Table `catering_recipes` : unicité `(item, variantId, stepId, choiceId, flavourId, revision)`. | Donnée | § 5 |
| T0.013 | Table `catering_recipe_lines` : composant, quantité (19,6), `kind`. | Donnée | § 5 |
| T0.014 | Table `fabrication_stations` : `code` unique par magasin, bâtiment, buffer d'attente, imprimante, calendrier, capacité. | Donnée | § 5 |
| T0.015 | Table `fabrication_station_employees` : unicité `(station, employee)`. | Donnée | § 5 |
| T0.016 | Table `catering_units` : `lineId` unique par commande, composition reçue, quantités, état, révision. | Donnée | § 5 |
| T0.017 | Table `fabrication_lines` : quantités prévue, fabriquée, perdue, non servie, `producedAt`, `useBy`, `lotCode`. | Donnée | § 5 |
| T0.018 | Table `fabrication_line_units` : part d'une ligne destinée à une unité, quantités fabriquée et non servie. | Donnée | § 5 |
| T0.019 | Table `fabrication_consumptions` : ligne, composant, segment, lot fabricant, quantité, `loss`. | Donnée | § 5 ; F13 |
| T0.020 | Table `catering_purchase_needs` : unité, fournisseur, quantité, `orderBy`, `dueAt`, état, approvisionnement attendu. | Donnée | § 5 ; F10 |
| T0.021 | Table `catering_amendments` : `requestId` unique par principal, empreinte, nature, charge, issue, code. | Donnée | § 5 ; F18, F19 |
| T0.022 | `handling_orders` : type `FABRICATION` ajouté à la contrainte `type in (…)` ; colonnes `station`, `productionDate`, `proposalState`, `proposedEmployee`, `plannedMinutes`. | Donnée | § 5 |
| T0.023 | `handling_executions` : type nouveau pour `FabricationExecution`, avec ses bacs chargés. | Donnée | § 5 |
| T0.024 | `orders` : colonnes `payment_method`, `payment_state`, `payment_amount`, `payment_acknowledged_at`, `payment_acknowledged_by`. | Donnée | § 5 ; F17 |
| T0.025 | `fulfillment_chunks` : colonne `catering`, fausse par défaut pour les lignes existantes. | Donnée | § 5 ; I7 |
| T0.026 | `picking_shortages` : l'ordre de manutention devient facultatif. | Donnée | § 5 ; F3 |
| T0.027 | `CateringMigrationTest` : `V30` s'applique sur une base vide. | Test | § 11.1 |
| T0.028 | `CateringMigrationTest` : `V30` s'applique sur une base peuplée en V29 ; produits, ordres, chunks et manquants existants sont conservés avec leurs valeurs. | Test | § 11.1 |

### Entités

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T0.029 | `CateringItem` et `CateringItemStore` ; durées, délai fournisseur et devenirs nuls = valeur de la politique. | Donnée | § 5 |
| T0.030 | `CateringRecipe` et `CateringRecipeLine` ; `kind` `INGREDIENT` ou `PICKED`. | Donnée | § 5 |
| T0.031 | `FabricationStation` et `FabricationStationEmployee` ; `stagingLeadMinutes` nul = valeur de la politique ; imprimante facultative. | Donnée | § 5 |
| T0.032 | `CateringUnit` avec l'énumération de ses neuf états ; `item` nul si l'EAN est inconnu ; `fulfillmentLine` nulle si l'EAN est inconnu ou si l'unité est `STOCKED`. | Donnée | § 5 ; F3 |
| T0.033 | `FabricationOrder`, sous-classe de `BaseHandlingOrder` : préfixe de code `FAB`, `proposalState` `PROPOSED` ou `VALIDATED`, `createExecution` rend une `FabricationExecution`. | Donnée | § 5 |
| T0.034 | `FabricationLine`, `FabricationLineUnit` et `FabricationConsumption`. | Donnée | § 5 |
| T0.035 | `FabricationExecution`, sous-classe de `HandlingExecution`, qui porte ses `LoadedTote` et ses `PickedItem` comme une exécution de prélèvement. | Donnée | § 5 ; F16 |
| T0.036 | `CateringPurchaseNeed` avec ses états `PROPOSED`, `VALIDATED`, `RECEIVED`, `CANCELLED`. | Donnée | § 5 |
| T0.037 | `CateringAmendment`, reçu idempotent des modifications et des annulations. | Donnée | § 5 |
| T0.038 | `Order` : champs de paiement et d'accusé d'encaissement ; `FulfillmentChunk.catering` ; `PickingShortage.handlingOrder` facultatif. | Donnée | § 5 |
| T0.039 | Références d'engagement : `StockCommitment.reference` = `FAB:<orderId>:<lineId>:<ean composant>` ; `ExpectedSupply.reference` = `CAT:<orderId>:<lineId>`. | Donnée | § 5 |
| T0.040 | `getChecksum` défini sur chaque entité nouvelle, comme pour les entités existantes. | Donnée | § 5 |

### Politique `CateringConstraints` et résolution des paramètres

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T0.041 | Ajouter `CateringConstraints` à `OperationalPolicySchemas.TYPES` : schéma, libellé, valeurs par défaut égales à la colonne « Démo ». | Paramètre | § 6 |
| T0.042 | Lecture et écriture par les routes existantes des politiques opérationnelles : versions par magasin, validation par le schéma. | Paramètre | § 6 ; § 8.1 |
| T0.043 | Service unique de résolution : valeur par défaut du schéma, puis version active du magasin. | Paramètre | § 6 ; P2 |
| T0.044 | Résolution des portées « E, M, P » et « E, M, A » : la valeur de l'article ou du poste, si elle est renseignée, l'emporte sur celle de la politique. | Paramètre | § 6 ; P2 |
| T0.045 | Paramètres A, P et V validés à l'enregistrement du référentiel : 400 avec le libellé du paramètre. | Paramètre | § 6 |
| T0.046 | Paramètre `enabled` (E, M) : booléen ; défaut vrai. | Paramètre | § 6 ; F3 |
| T0.047 | Paramètre `autoReferenceUnknownItems` (E, M) : booléen ; défaut vrai. | Paramètre | § 6 ; I5 |
| T0.048 | Paramètre `defaultSourcingMode` (E, M) : `FABRICATED`, `SUPPLIED` ou `STOCKED` ; défaut `FABRICATED`. | Paramètre | § 6 ; I5 |
| T0.049 | Paramètre `defaultTemperatureZone` (E, M) : zone de température ; défaut `CHILLED`. | Paramètre | § 6 ; I5 |
| T0.050 | Paramètre `sourcingMode` (P, P×M) : mode d'obtention ; défaut aucun (§ 10). | Paramètre | § 6 ; F2 |
| T0.051 | Paramètre `station` (P, P×M) : poste actif du magasin ; défaut aucun (§ 10). | Paramètre | § 6 ; F2 |
| T0.052 | Paramètre `recipeRequired` (E, M) : booléen ; défaut faux. | Paramètre | § 6 ; F4 |
| T0.053 | Paramètre `shelfLifeHours` (E, M, P) : entier 1 à 720 ; défaut 48. | Paramètre | § 6 ; F5, F13 |
| T0.054 | Paramètre `maxAdvanceHours` (E, M, P) : entier 0 à 720, inférieur ou égal à `shelfLifeHours` ; défaut 24. | Paramètre | § 6 ; F5 |
| T0.055 | Paramètre `consumptionMarginHours` (E, M) : entier 0 à 72 ; défaut 6. | Paramètre | § 6 ; F5 |
| T0.056 | Paramètre `stagingLeadMinutes` (E, M, A) : entier 0 à 1 440 ; défaut 60. | Paramètre | § 6 ; F5 |
| T0.057 | Paramètre `weekdays` (A) : jours 1 à 7 ; défaut tous les jours. | Paramètre | § 6 ; F5, F7 |
| T0.058 | Paramètre `closedDates` (A) : liste de dates ; défaut aucune. | Paramètre | § 6 ; F5, F7 |
| T0.059 | Paramètre `opensAt` et `closesAt` (A) : heures, ouverture strictement avant fermeture ; défaut 00:00 et 23:59. | Paramètre | § 6 ; F5, F7 |
| T0.060 | Paramètre `dailyMinutes` (A) : entier 0 à 10 000, 0 = non limité ; défaut 480. | Paramètre | § 6 ; F7 |
| T0.061 | Paramètre `laborSeconds` (V) : entier 0 à 86 400 ; défaut aucun (§ 10). | Paramètre | § 6 ; F7 |
| T0.062 | Paramètre `defaultLaborSeconds` (E, M) : entier 0 à 86 400 ; défaut 600. | Paramètre | § 6 ; F7 |
| T0.063 | Paramètre `maxOrderMinutes` (A) : entier 15 à 600 ; défaut 120. | Paramètre | § 6 ; F6 |
| T0.064 | Paramètre `batchAcrossOrders` (E, M) : booléen ; défaut vrai. | Paramètre | § 6 ; F6 |
| T0.065 | Paramètre `proposalHorizonDays` (E, M) : entier 1 à 60 ; défaut 14. | Paramètre | § 6 ; F8 |
| T0.066 | Paramètre `autoValidateProposals` (E, M) : booléen ; défaut faux. | Paramètre | § 6 ; F8 |
| T0.067 | Paramètre `planningLeadHours` (E, M) : entier 1 à 240 ; défaut 24. | Paramètre | § 6 ; F22 |
| T0.068 | Paramètre `componentShortagePolicy` (E, M) : `BLOCK` ou `WARN` ; défaut `WARN`. | Paramètre | § 6 ; F9 |
| T0.069 | Paramètre `supplierLeadDays` (E, M, P) : entier 0 à 30 ; défaut 2. | Paramètre | § 6 ; F10 |
| T0.070 | Paramètre `supplierOrderCutoff` (E, M) : heure ou nul ; défaut nul. | Paramètre | § 6 ; F10 |
| T0.071 | Paramètre `labelTemplate` (E, M) : code d'un `LabelTemplate` du magasin, ou nul ; défaut `CATERING_UNIT`. | Paramètre | § 6 ; F14 |
| T0.072 | Paramètre `labelCopies` (E, M) : entier 1 à 5 ; défaut 1. | Paramètre | § 6 ; F14 |
| T0.073 | Paramètre `handoverRequiresPaymentAck` (E, M) : booléen ; défaut vrai. | Paramètre | § 6 ; F17 |
| T0.074 | Paramètre `amendmentAfterStart` (E, M) : `REFUSE` ou `ACCEPT_DECREASE` ; défaut `REFUSE`. | Paramètre | § 6 ; F18 |
| T0.075 | Paramètre `cancelAfterStart` (E, M) : `REFUSE` ou `ACCEPT_WITH_DISPOSITION` ; défaut `ACCEPT_WITH_DISPOSITION`. | Paramètre | § 6 ; F19 |
| T0.076 | Paramètre `cancelDispositions` (E, M, P) : sous-ensemble non vide de `REALLOCATE`, `ISOLATE` ; défaut les deux. | Paramètre | § 6 ; F19 |
| T0.077 | Paramètre `notificationRoles` (E, M) : liste de rôles ; défaut `MANAGER`. | Paramètre | § 6 ; F23 |
| T0.078 | Test : chaque paramètre numérique est accepté à ses deux bornes et refusé juste en dehors, avec son libellé. | Test | § 6 |
| T0.079 | Test : chaque paramètre énuméré refuse une valeur inconnue ; `cancelDispositions` refuse la liste vide. | Test | § 6 |
| T0.080 | Test : `maxAdvanceHours` supérieur à `shelfLifeHours` refusé ; `opensAt` égal ou postérieur à `closesAt` refusé. | Test | § 6 |
| T0.081 | Test de résolution : valeur par défaut du schéma sans version ; version du magasin ; surcharge de l'article ; surcharge du poste ; retour à la politique quand la surcharge est nulle. | Test | § 6 ; P2 |

## T1 — Réception de la commande et référentiel

61 points · dépend de : T0.

### Schéma RetailOrder étendu

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T1.001 | Bloc racine `catering` : `contractVersion` « 1 », `reception.date`, `reception.guests` (1 à 1 000), `deadlines.change` et `.cancel`, `pickupPoint` (≤ 200), tous facultatifs sauf la version. | API | § 4.1 ; I2 |
| T1.002 | Bloc `payment` : `method` `ONLINE` ou `AT_PICKUP` (absent = `ONLINE`) ; `state` (`DUE`, `COLLECTED`) et `amount` (≥ 0) obligatoires si `AT_PICKUP`. | API | § 4.1 |
| T1.003 | Objet `lines[].catering` : `lineId` UUID ; `variant` (`id` au motif `[A-Za-z0-9-]{1,20}`, `label` ≤ 60, `persons`, `pieces`, `parts`, `weightGrams` facultatifs). | API | § 4.1 |
| T1.004 | `lines[].catering.configurationKey` : 64 caractères hexadécimaux, obligatoire. | API | § 4.1 |
| T1.005 | `lines[].catering.configuration` : absente, composition `{choices: {étape: [choix…]}}` ou gâteau `{shapeId, text, flavourId}` avec `text` facultatif ≤ 2. | API | § 4.1 |
| T1.006 | `lines[].catering.choiceLabels` (0 à 40 libellés ≤ 60), `allergens` et `ingredients` (≤ 4 000), `storage` et `servingAdvice` (≤ 1 000). | API | § 4.1 |
| T1.007 | Bloc présent et mal formé : 400 avec le chemin du champ et l'événement existant « Commande rejetée (schéma invalide) ». | Règle | I2 |
| T1.008 | Zones de température du schéma alignées sur `TemperatureZone` ; `FRESH` accepté en entrée comme synonyme de `CHILLED`. | Adaptation | I8 ; C10 |

### Règles de réception

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T1.009 | Une ligne est traiteur si et seulement si elle porte l'objet `catering` ; le nom du produit n'est jamais interprété. | Règle | I1 |
| T1.010 | `lineId` en double dans une commande : 400 `CATERING_LINE_INVALID`. | Règle | I3 |
| T1.011 | `configuration` qui n'est ni une composition ni un gâteau : 400 `CATERING_LINE_INVALID`. | Règle | I3 |
| T1.012 | Ligne `catering` dans une charge sans bloc `catering` racine : 400 `CATERING_LINE_INVALID`. | Règle | I3 |
| T1.013 | Soumission idempotente par `(storeCode, orderId)` : empreinte canonique de la charge conservée avec la demande. | Règle | I4 |
| T1.014 | Rejeu d'une charge identique : 202, aucune seconde `OrderRequest`. | Règle | I4 |
| T1.015 | Charge différente sous le même `orderId` : 409 `ORDER_ALREADY_SUBMITTED`, aucun effet. | Règle | I4 |
| T1.016 | `OrderRecorder` enregistre `payment` sur la commande (mode, état, montant), avec ou sans traiteur. | Règle | § 4.1 ; F17 |
| T1.017 | `OrderRecorder` crée, pour chaque ligne traiteur, l'`OrderLine` existante et une `CateringUnit` : variante, clé et configuration, libellés, allergènes, ingrédients, conservation, conseils, quantité, `availableAt` = début du créneau. | Règle | I6 |
| T1.018 | EAN inconnu, `autoReferenceUnknownItems` vrai : création du `Product` (nom et type de la ligne, zone `defaultTemperatureZone`) et d'un `CateringItem` en mode `defaultSourcingMode`, sans poste ni nomenclature. | Règle | I5 |
| T1.019 | EAN inconnu, paramètre faux : rien n'est créé ; l'unité garde l'EAN reçu, sans article ni ligne de lot, à l'état `BLOCKED`. | Règle | I5 |
| T1.020 | Dans les deux cas d'EAN inconnu : commande enregistrée, situation `CATERING_UNREFERENCED`. | Règle | I5 ; C2 |
| T1.021 | `enabled` faux : toute unité reçue passe `BLOCKED` sous `CATERING_DISABLED`. | Règle | § 6 ; F3 |

### Répartition par le fulfiller traiteur

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T1.022 | `CateringFulfillerFactory` : toujours exécuté par `FulfillmentEngine` avant les fulfillers de la configuration du magasin, sans y être déclaré. | Adaptation | I7 |
| T1.023 | Unité `FABRICATED` ou `SUPPLIED` : sa ligne passe dans un chunk `READY` marqué `catering`, sans emplacement de prélèvement ; `CateringUnit.fulfillmentLine` la référence. | Règle | I7 |
| T1.024 | Unité `BLOCKED` dont le produit est connu : même chunk traiteur, quel que soit son mode. | Règle | I7 |
| T1.025 | Unité `STOCKED` non bloquée : sa ligne est remplacée par ses produits à prélever, laissés aux fulfillers suivants. | Règle | I7 ; F11 |
| T1.026 | Le lot du chunk traiteur est créé par le mécanisme existant ; aucune ligne traiteur ne laisse la commande à l'état `PENDING`. | Règle | I7 ; C4 |

### Tests de réception (`CateringIntakeTest`)

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T1.027 | Charge sans aucun bloc ajouté : mêmes entités et mêmes événements qu'avant. | Test | § 11.1 ; P5 |
| T1.028 | Charge avec `payment` seul, sans traiteur : commande au retrait enregistrée, aucune unité. | Test | § 11.1 |
| T1.029 | `payment.method = AT_PICKUP` sans `state` ou sans `amount` : 400. | Test | § 4.1 |
| T1.030 | Bloc racine mal formé (version absente, date invalide, convives hors bornes) : 400 avec le chemin. | Test | I2 |
| T1.031 | Chaque champ de `lines[].catering` hors borne ou au mauvais motif : 400 avec le chemin. | Test | § 4.1 |
| T1.032 | `lineId` en double ; `configurationKey` absente ; configuration inconnue ; ligne traiteur sans bloc racine : un cas par jambe. | Test | I3 |
| T1.033 | Rejeu identique : 202 et une seule `OrderRequest` ; même charge avec les clés JSON dans un autre ordre : toujours identique. | Test | I4 |
| T1.034 | Rejeu différent : 409 `ORDER_ALREADY_SUBMITTED`, commande d'origine intacte. | Test | I4 |
| T1.035 | EAN inconnu, paramètre vrai : produit et fiche créés, unité `RECEIVED`, situation produite. | Test | I5 |
| T1.036 | EAN inconnu, paramètre faux : unité `BLOCKED`, `OrderLine` sans produit, aucune ligne de lot. | Test | I5 |
| T1.037 | EAN de 23 caractères accepté et relu. | Test | I9 |
| T1.038 | Zone `FRESH` acceptée et lue comme `CHILLED`. | Test | I8 |
| T1.039 | `enabled` faux : unité `BLOCKED`, situation `CATERING_DISABLED`. | Test | § 6 |
| T1.040 | Commande mixte avec une unité bloquée : les lots de courses sont créés et la commande n'est pas `PENDING`. | Test | I7 |
| T1.041 | Unité `STOCKED` : ses produits à prélever sont répartis par les fulfillers existants. | Test | I7 ; F11 |
| T1.042 | Magasin dont la configuration `fulfillers` ne cite pas le traiteur : les lignes traiteur sont quand même réparties. | Test | I7 |

### Référentiel traiteur

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T1.043 | Un produit est un article traiteur s'il possède un `CateringItem` actif. | Règle | F1 |
| T1.044 | Article désactivé : aucune planification nouvelle ; ses unités en cours vont à leur terme. | Règle | F1 |
| T1.045 | Mode d'une unité résolu à la réception : surcharge du magasin, à défaut fiche, à défaut `defaultSourcingMode` ; figé sur l'unité. | Règle | F2 |
| T1.046 | Poste résolu de la même façon ; unité `FABRICATED` ou `SUPPLIED` sans poste : reste `RECEIVED` sous `CATERING_UNREFERENCED`. | Règle | F2 |
| T1.047 | `GET /catering/items?storeId=&q=&mode=` : fiches avec mode résolu, poste, nomenclatures présentes ou manquantes par variante connue. | API | § 8.1 |
| T1.048 | Variantes connues d'un article : celles de ses nomenclatures et celles des unités reçues. | Règle | § 8.1 |
| T1.049 | `PUT /catering/items` (ADMIN) : `station` exigé pour `FABRICATED` et `SUPPLIED` ; 400 avec le libellé du paramètre. | API | § 8.1 ; § 5 |
| T1.050 | `PUT /catering/items/{id}/stores/{storeId}` (ADMIN, MANAGER du magasin) : surcharge ; 403 pour un autre magasin. | API | § 8.1 |
| T1.051 | `GET /catering/items/{id}/recipes` : nomenclatures, lignes et révisions ; 404 si l'article est inconnu. | API | § 8.1 |
| T1.052 | `PUT /catering/items/{id}/recipes` (ADMIN) : crée une révision nouvelle, base ou complément ; 400 pour un composant inconnu ou une quantité ≤ 0. | API | § 8.1 |
| T1.053 | `GET /catering/stations?storeId=` : postes, calendrier, employés habilités. | API | § 8.1 |
| T1.054 | `PUT /catering/stations` (ADMIN, MANAGER du magasin) : code unique, bâtiment et buffer `STAGING` du magasin, imprimante du magasin, horaires cohérents ; 400, 403. | API | § 8.1 |
| T1.055 | `PUT /catering/stations/{id}/employees` : 400 pour un employé d'un autre magasin. | API | § 8.1 |
| T1.056 | Droits : lectures ADMIN et MANAGER ; un manager est limité à son magasin ; fiches et nomenclatures communes écrites par l'administrateur seul. | API | § 8.1 |
| T1.057 | `CateringUnitLifecycleTest` : fiche active, désactivée ; article désactivé non planifié, unités en cours poursuivies. | Test | § 11.1 ; F1 |
| T1.058 | `CateringUnitLifecycleTest` : mode résolu par la surcharge, par la fiche, par la politique ; mode figé après changement de fiche. | Test | § 11.1 ; F2 |
| T1.059 | `CateringUnitLifecycleTest` : poste non résolu pour `FABRICATED` et pour `SUPPLIED`. | Test | § 11.1 ; F2 |
| T1.060 | Test : chaque écriture du référentiel avec chaque rôle ; manager d'un autre magasin refusé. | Test | § 8.1 |
| T1.061 | Test : chaque refus de validation des fiches, nomenclatures, postes et habilitations. | Test | § 8.1 |

## T2 — Planification : unités, nomenclature, fenêtre, proposition, besoins

73 points · dépend de : T1.

### Unité traiteur et états

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T2.001 | Transitions des neuf états de l'unité, chacune à son événement d'entrée. | Règle | F3 |
| T2.002 | Chaque changement d'état augmente `revision` de l'unité et `CommercePickupState.revision` de la commande. | Règle | F3 |
| T2.003 | Une unité `BLOCKED` n'arrête pas les autres unités de la commande. | Règle | F3 |
| T2.004 | Retour de `BLOCKED` à `RECEIVED` au premier calcul qui suit la disparition de la cause. | Règle | F3 |
| T2.005 | Unité `STOCKED` débloquée : ses produits à prélever rejoignent un chunk `PENDING` de la commande. | Règle | F3 ; I7 |
| T2.006 | Unité servie en partie : elle garde son état et porte `shortQuantity` ; `SHORT` seulement si toute la quantité est non servie. | Règle | F3 |
| T2.007 | `GET /catering/units?storeId=&from=&to=&state=` : commande, créneau, article, variante, composition, quantité, mode, état, ordre ou besoin lié. | API | § 8.2 |
| T2.008 | `CateringUnitLifecycleTest` : chaque état atteint par son événement ; révisions incrémentées ; retour depuis `BLOCKED` pour chacune des trois causes. | Test | § 11.1 ; F3 |

### Nomenclature résolue

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T2.009 | Base : nomenclature active de plus haute révision pour `(item, variantId)`, sans choix. | Règle | F4.1 |
| T2.010 | Composition : ajout du complément `(stepId, choiceId)` de chaque choix reçu, s'il existe. | Règle | F4.2 |
| T2.011 | Gâteau : ajout du complément `flavourId`, s'il existe ; forme et texte servis comme consigne. | Règle | F4.3 |
| T2.012 | Quantités et `laborSeconds` additionnés, puis multipliés par la quantité de la ligne. | Règle | F4.4 |
| T2.013 | Aucune base, `recipeRequired` vrai : unité `BLOCKED`, situation `CATERING_RECIPE_MISSING`. | Règle | F4.5 |
| T2.014 | Aucune base, `recipeRequired` faux : planification avec `defaultLaborSeconds`, sans composant, même situation en « information manquante ». | Règle | F4.5 |
| T2.015 | Nomenclature figée sur la ligne de fabrication à la validation ; une révision ultérieure ne change pas un ordre validé. | Règle | F4.6 |
| T2.016 | `CateringRecipeTest` : base seule ; un complément de choix ; plusieurs ; complément absent ; parfum ; révision la plus haute ; révision inactive ignorée. | Test | § 11.1 |
| T2.017 | `CateringRecipeTest` : nomenclature absente avec `recipeRequired` vrai, puis faux ; figeage à la validation. | Test | § 11.1 |

### Fenêtre de fabrication

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T2.018 | Fin au plus tard : mise à disposition − `stagingLeadMinutes`. | Règle | F5 |
| T2.019 | Début au plus tôt : mise à disposition − `maxAdvanceHours`, jamais avant l'instant présent. | Règle | F5 |
| T2.020 | Jours admis : `weekdays` du poste, hors `closedDates`. | Règle | F5 |
| T2.021 | Plages admises : entre `opensAt` et `closesAt`. | Règle | F5 |
| T2.022 | Contrôle de date limite avec `reception.date` : la fin de fabrication + `shelfLifeHours` atteint la fin du jour de réception. | Règle | F5 |
| T2.023 | Contrôle de date limite sans `reception.date` : elle atteint la fin du créneau + `consumptionMarginHours`. | Règle | F5 |
| T2.024 | Le début au plus tôt est repoussé autant que le contrôle de date limite l'exige. | Règle | F5 |
| T2.025 | Fenêtre vide : l'unité reste `RECEIVED`, situation `FABRICATION_WINDOW` ; la mise à disposition n'est jamais déplacée. | Règle | F5 ; P1 |
| T2.026 | `FabricationWindowTest` : chaque borne ; début ramené à l'instant présent ; jour fermé ; date fermée ; heure d'ouverture et de fermeture. | Test | § 11.1 |
| T2.027 | `FabricationWindowTest` : date limite avec et sans `reception.date` ; début repoussé ; fenêtre vide. | Test | § 11.1 |
| T2.028 | `FabricationWindowTest` : les deux exemples chiffrés de la spécification (pain surprise et gâteau chiffre, créneau du samedi 10:00). | Test | F5 |

### Regroupement et charge

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T2.029 | Candidats : unités `FABRICATED` à l'état `RECEIVED` et unités `SUPPLIED` au besoin d'achat validé, poste résolu, mise à disposition dans `proposalHorizonDays`. | Règle | F6.1 |
| T2.030 | Jour retenu : le dernier jour admis de la fenêtre dont la charge restante suffit ; à défaut le précédent, jusqu'au début au plus tôt. | Règle | F6.2 |
| T2.031 | Ligne de fabrication : une par `(poste, jour, article, variantId, configurationKey)`. | Règle | F6.3 |
| T2.032 | `batchAcrossOrders` vrai : les unités de commandes différentes de même clé sont réunies ; faux : une ligne par unité. | Règle | F6.3 |
| T2.033 | Une `FabricationLineUnit` par unité servie, avec sa quantité. | Règle | F6.3 ; § 5 |
| T2.034 | Ordres : lignes d'un poste et d'un jour triées par mise à disposition, découpées à `maxOrderMinutes` ; une ligne plus longue forme un ordre seule. | Règle | F6.4 |
| T2.035 | `requestedStart` et `requestedEnd` dans la fenêtre commune des lignes ; `plannedMinutes` arrondi à la minute supérieure. | Règle | F6.5 |
| T2.036 | Charge d'un jour : somme des `plannedMinutes` des ordres non annulés du poste, proposés et validés. | Règle | F7 |
| T2.037 | Capacité : `dailyMinutes`, 0 = non limitée. | Règle | F7 |
| T2.038 | Capacité plafonnée par le temps de présence planifié des employés habilités, quand le planning existe. | Règle | F7 |
| T2.039 | Aucun jour de la fenêtre ne convient : situation `FABRICATION_CAPACITY` avec les unités et les minutes manquantes. | Règle | F7 |
| T2.040 | `FabricationPlanningTest` : regroupement avec `batchAcrossOrders` vrai et faux ; deux gâteaux de textes différents jamais réunis. | Test | § 11.1 |
| T2.041 | `FabricationPlanningTest` : découpage à `maxOrderMinutes` ; ligne plus longue que le maximum. | Test | § 11.1 |
| T2.042 | `FabricationPlanningTest` : capacité 0 ; capacité atteinte et report au jour précédent ; plafond de présence ; aucun jour possible. | Test | § 11.1 |

### Proposition et validation

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T2.043 | Constructeur d'ordres de fabrication exécuté à chaque cycle de `StoreActivityEngine`. | Adaptation | F8 |
| T2.044 | Recalcul à chaque commande reçue, modification, annulation, changement de référentiel ou de politique, et à la demande. | Règle | F8 |
| T2.045 | Ordres produits : `proposalState = PROPOSED`, statut `OPEN`, `assignee` nul, `proposedEmployee` quand un employé habilité et présent convient. | Règle | F8 |
| T2.046 | Un ordre `PROPOSED` est recalculé librement et n'engage aucun stock. | Règle | F8 |
| T2.047 | Ordres `PROPOSED` exclus des lectures opérateur. | Adaptation | F8 ; § 8.4 |
| T2.048 | Ordres `PROPOSED` exclus de la supervision de la manutention. | Adaptation | F8 |
| T2.049 | Ordres `PROPOSED` exclus du planning contraint. | Adaptation | F8 |
| T2.050 | `GET /catering/fabrication/proposals?storeId=` : poste, jour, plage, lignes, minutes, employé proposé, charge et capacité du jour, composants manquants, limite d'annulation des commandes. | API | § 8.2 ; F8 |
| T2.051 | `POST /catering/fabrication/proposals/recompute` : relance le calcul et rend la même lecture. | API | § 8.2 |
| T2.052 | `POST /catering/fabrication/proposals/validate` : `proposalState = VALIDATED`, unités `PLANNED`, nomenclature figée, engagements créés. | API | § 8.2 ; F8 |
| T2.053 | Validation avec jour, plage ou employé modifiés : F5, F7 et l'habilitation sont revérifiées. | Règle | F8 |
| T2.054 | Refus de validation : 409 `STALE_REVISION`, `OUT_OF_WINDOW`, `OVER_CAPACITY`, `COMPONENT_SHORTAGE`, `EMPLOYEE_NOT_QUALIFIED`, raison servie. | Règle | § 8.2 |
| T2.055 | À la validation, `assignee` reçoit l'employé retenu par le manager, à défaut l'employé proposé. | Règle | F8 |
| T2.056 | L'ordre validé entre dans le planning contraint existant comme toute manutention. | Adaptation | F8 |
| T2.057 | `autoValidateProposals` vrai : validation appliquée par le serveur, auteur `SYSTEM`, mêmes contrôles ; jamais pour les besoins d'achat. | Règle | F8 |
| T2.058 | Un ordre validé n'est plus touché par le calcul ; une unité ajoutée ensuite forme une proposition nouvelle. | Règle | F8 |
| T2.059 | `GET /catering/fabrication/sheet?storeId=&date=&stationId=` : feuille imprimable du jour, lignes regroupées, compositions, allergènes, commandes et créneaux, date de réception et convives. | API | § 8.2 |
| T2.060 | `FabricationPlanningTest` : proposition invisible de l'opérateur, de la supervision et du planning. | Test | § 11.1 |
| T2.061 | `FabricationPlanningTest` : validation simple ; avec chaque modification ; chacun des cinq refus. | Test | § 11.1 |
| T2.062 | `FabricationPlanningTest` : validation automatique ; ordre validé non recalculé ; unité ajoutée après validation. | Test | § 11.1 |
| T2.063 | `FabricationPlanningTest` : chaque déclencheur du recalcul produit la proposition attendue. | Test | F8 |

### Besoins en composants

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T2.064 | À la validation, un `StockCommitment` par composant `INGREDIENT` : référence `FAB:…`, bâtiment du poste, `preparationAt` = début de l'ordre. | Règle | F9 |
| T2.065 | Le stock daté existant prend ces engagements en compte comme des engagements de commande. | Adaptation | F9 |
| T2.066 | Quantité indisponible à la date de l'ordre, proposé ou validé : situation `CATERING_COMPONENT_SHORTAGE` avec composant, quantité manquante et ordres. | Règle | F9 |
| T2.067 | `componentShortagePolicy = BLOCK` : validation refusée ; `WARN` : acceptée. | Règle | F9 |
| T2.068 | Les règles de réapprovisionnement existantes voient ces engagements ; aucune règle nouvelle. | Adaptation | F9 |
| T2.069 | `GET /catering/requirements?storeId=&from=&to=` : par jour et composant, besoin, disponible daté, écart, ordres. | API | § 8.2 ; F9 |
| T2.070 | Annulation ou baisse d'une unité : engagements annulés ou réduits dans la même transaction. | Règle | F9 |
| T2.071 | `CateringRequirementsTest` : engagements créés à la validation, réduits, annulés. | Test | § 11.1 |
| T2.072 | `CateringRequirementsTest` : manque sur ordre proposé, sur ordre validé ; `BLOCK` et `WARN`. | Test | § 11.1 |
| T2.073 | `CateringRequirementsTest` : le réapprovisionnement existant réagit à un engagement de fabrication. | Test | F9 |

## T3 — Exécution : articles fournis et en stock, fabrication, traçabilité, étiquette, mise en attente

71 points · dépend de : T2.

### Articles fournis

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T3.001 | Unité `SUPPLIED` reçue : besoin d'achat `PROPOSED` ; `dueAt` = mise à disposition − `maxAdvanceHours`. | Règle | F10.1 |
| T3.002 | `orderBy` = le jour de `dueAt` − `supplierLeadDays`, à l'heure `supplierOrderCutoff`, à défaut à la fin de ce jour. | Règle | F10.1 |
| T3.003 | `GET /catering/purchase-needs?storeId=&state=` : besoins regroupés par fournisseur, article, variante et jour dû. | API | § 8.2 ; F10.2 |
| T3.004 | `POST /catering/purchase-needs/validate` : besoins choisis `VALIDATED`, `ExpectedSupply` `EXPECTED` de référence `CAT:…` vers l'emplacement de réception ; 409 `STALE_REVISION`. | API | § 8.2 ; F10.3 |
| T3.005 | `GET /catering/purchase-needs/order-form?storeId=&supplier=&dueDate=` : bon de commande imprimable, jamais transmis. | API | § 8.2 ; F10.3 |
| T3.006 | Besoin validé : l'unité devient candidate à la proposition, au poste de sa fiche, dans la fenêtre de F5. | Règle | F10.4 |
| T3.007 | Ligne de mise en support : seul composant l'article reçu ; temps = `laborSeconds` de la nomenclature de base, à défaut `defaultLaborSeconds` ; F4.5 non appliquée. | Règle | F10.4 |
| T3.008 | L'unité fournie est `PLANNED` quand son ordre de mise en support est validé. | Règle | F10.4 ; F3 |
| T3.009 | Réception par le flux existant, reconnue à la référence `CAT:…` : besoin `RECEIVED`, entrée en stock avec le lot du fournisseur, engagement immédiat pour l'unité. | Adaptation | F10.5 |
| T3.010 | `FABRICATE` sur une ligne de mise en support : `PickedItem` de la ligne de lot traiteur, source = emplacement de réception, lot du fournisseur ; étiquette. | Règle | F10.6 |
| T3.011 | `FABRICATE` refusé tant que l'approvisionnement n'est pas reçu : `SUPPLY_NOT_RECEIVED`. | Règle | F10.6 |
| T3.012 | Article fourni : `producedAt` = heure de réception ; `useBy` = `producedAt + shelfLifeHours`, ramené à la date limite du lot du fournisseur si elle est plus proche. | Règle | F10.6 |
| T3.013 | `orderBy` dépassé sans validation, ou `dueAt` dépassé sans réception : `CATERING_SUPPLY_LATE` en risque ; avérée après la fin au plus tard. | Règle | F10.7 |
| T3.014 | `CateringRequirementsTest` : `dueAt` et `orderBy` avec et sans heure limite ; regroupement de la lecture. | Test | § 11.1 |
| T3.015 | `CateringRequirementsTest` : validation ; mise en support proposée puis validée ; réception. | Test | § 11.1 |
| T3.016 | `CateringRequirementsTest` : `FABRICATE` avant réception refusé, après réception accepté ; date limite du lot du fournisseur plus proche et plus lointaine. | Test | § 11.1 |
| T3.017 | `CateringRequirementsTest` : retard de commande, retard de livraison, passage de risque à avéré. | Test | § 11.1 |

### Articles en stock

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T3.018 | Unité `STOCKED` avec nomenclature : ses lignes `PICKED`, multipliées par la quantité, sont les produits à prélever. | Règle | F11 |
| T3.019 | Unité `STOCKED` sans nomenclature : le produit lui-même est prélevé. | Règle | F11 |
| T3.020 | Lignes remises aux fulfillers suivants comme des lignes ordinaires, datées de la mise à disposition. | Règle | F11 ; I7 |
| T3.021 | L'unité suit leur avancement : `PLANNED` à la planification de la collecte, `PRODUCED` au prélèvement complet, `STAGED` au dépôt en buffer. | Règle | F11 |
| T3.022 | `CateringUnitLifecycleTest` : article en stock avec et sans nomenclature ; chaque état suivi depuis la collecte existante. | Test | § 11.1 |

### Mission de fabrication

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T3.023 | L'ordre validé et affecté apparaît dans les lectures existantes des missions de l'opérateur. | Adaptation | F12 ; § 8.4 |
| T3.024 | Lecture par ligne : article, variante, quantité, libellés figés, texte du gâteau, allergènes, composants avec quantités et emplacements, temps prévu. | API | F12 |
| T3.025 | Sous chaque ligne, les unités à servir dans l'ordre de leur mise à disposition : référence de commande, créneau, quantité. | API | F12 |
| T3.026 | `FABRICATE` (`itemId` = part de ligne destinée à une unité, `quantity`, `toteCode`, `componentLots`) : ajoute la quantité fabriquée pour cette unité. | Règle | F12 |
| T3.027 | `FABRICATE` place le produit dans le support `toteCode`, aux règles de F15. | Règle | F12 ; F15 |
| T3.028 | `FABRICATE` : quantité totale supérieure à la quantité prévue pour l'unité refusée, `QUANTITY_EXCEEDED`. | Règle | F12 |
| T3.029 | La première action passe l'ordre à `PROCESSING` et l'unité à `IN_PROGRESS` ; la quantité complète la passe à `PRODUCED`. | Règle | F12 ; F3 |
| T3.030 | `FABRICATION_LOSS` (`itemId`, `quantity`, `reason`) : composants de la quantité perdue consommés, reste à fabriquer inchangé, incident par `FieldOperationsService`. | Règle | F12 |
| T3.031 | `FABRICATION_SHORT` (`itemId`, `quantity`, `reason`) : `shortQuantity` de l'unité et de la ligne, manquant consigné par `PickingShortage` sur la ligne de lot. | Règle | F12 |
| T3.032 | `FABRICATION_SHORT` : unité entièrement non servie à l'état `SHORT` ; incident ; situation `CATERING_SHORT`. | Règle | F12 |
| T3.033 | `DEPOSIT_TOTE` existant accepté sur une exécution de fabrication. | Adaptation | F12 ; F15 |
| T3.034 | `CONCLUDE` refusé tant qu'une ligne a un reste ni fabriqué ni déclaré non servi. | Règle | F12 |
| T3.035 | `CONCLUDE` refusé tant qu'une quantité fabriquée n'est pas déposée. | Règle | F12 |
| T3.036 | Les trois actions nouvelles gardent les garanties du contrat opérateur v2 : révision attendue, rejeu par `requestKey`, reçu committé avec l'effet. | Règle | F12 |
| T3.037 | Une unité se confirme en une action : quantité prévue proposée, étiquette sans action supplémentaire, dépôt par un scan ; aucune fiche récapitulative ni double confirmation imposée. | Règle | F12 |
| T3.038 | `FabricationExecutionTest` : chaque action nominale ; rejeu de la même clé ; clé réutilisée pour un autre contenu ; révision dépassée. | Test | § 11.1 |
| T3.039 | `FabricationExecutionTest` : dépassement de quantité ; perte ; impossibilité partielle et totale. | Test | § 11.1 |
| T3.040 | `FabricationExecutionTest` : `CONCLUDE` refusé pour chacune de ses deux causes, accepté ensuite. | Test | § 11.1 |
| T3.041 | `FabricationExecutionTest` : ligne regroupée servant deux commandes, une action et un support par unité. | Test | § 11.1 |
| T3.042 | Test : la lecture de la mission ne contient ni nom, ni adresse, ni téléphone du client. | Test | P6 |

### Traçabilité et date limite

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T3.043 | `useBy = producedAt + shelfLifeHours` ; `producedAt` = heure serveur de la première quantité fabriquée de la ligne. | Règle | F13 |
| T3.044 | `lotCode` = `<code de l'ordre>-<rang de la ligne>` ; article fourni : lot du fournisseur, à défaut référence de l'approvisionnement. | Règle | F13 |
| T3.045 | `FABRICATE` sort chaque composant du stock et libère son engagement, dans la transaction de l'action. | Règle | F13 |
| T3.046 | `FABRICATE` entre le produit fini sous un `ManufacturerLot` (code `lotCode`, `manufacturedOn`, `useBy` = date de la date limite), à l'adresse interne de son support. | Règle | F13 |
| T3.047 | Le produit fini est un `PickedItem` de la ligne de lot traiteur, source = le poste ; l'expédition le débite par le mécanisme existant. | Règle | F13 ; F16 |
| T3.048 | Composants sans choix de l'opérateur : segments alloués en FIFO ou FEFO, comme pour un prélèvement. | Adaptation | F13 |
| T3.049 | `componentLots` : lot précisé par l'opérateur dans la même action. | Règle | F13 |
| T3.050 | Lot explicite exigé par la politique de traçabilité du composant et absent : refus `LOT_REQUIRED`. | Règle | F13 |
| T3.051 | Chaque sortie de composant enregistrée en `FabricationConsumption` ; perte marquée `loss`. | Règle | F13 |
| T3.052 | `/quality/trace` remonte d'un lot fini à ses composants et à leurs lots. | Adaptation | F13 |
| T3.053 | `/quality/trace` descend d'un lot de composant aux lots finis, aux commandes et aux unités servies. | Adaptation | F13 |
| T3.054 | `useBy` dépassé pour un produit non remis : situation `CATERING_EXPIRED`. | Règle | F13 |
| T3.055 | `FabricationExecutionTest` : composants alloués par le serveur ; lot choisi par l'opérateur ; lot exigé et absent. | Test | § 11.1 |
| T3.056 | `FabricationExecutionTest` : lot fini créé une seule fois par ligne ; trace ascendante et descendante ; date limite dépassée. | Test | § 11.1 |

### Étiquette

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T3.057 | À chaque `FABRICATE`, un `PrintJob` par exemplaire × `labelCopies`, par la file d'impression existante, sur l'imprimante du poste. | Règle | F14 |
| T3.058 | Type de sujet nouveau `CATERING_LOT` dans `PrintingService` : code-barres = `lotCode`. | Adaptation | F14 |
| T3.059 | Gabarit `labelTemplate` du magasin s'il existe, sinon mise en page standard. | Règle | F14 |
| T3.060 | Lignes servies : nom et variante, libellés figés et texte, allergènes, conservation, `producedAt`, `useBy`, `lotCode`, référence et créneau de l'unité servie, point de retrait. | Règle | F14 |
| T3.061 | Aucune imprimante ou échec d'impression : l'action reste confirmée ; réimpression par l'écran d'impression existant. | Règle | F14 |
| T3.062 | `FabricationExecutionTest` : étiquettes par exemplaire et par copie ; gabarit présent, absent ; poste sans imprimante ; échec d'impression. | Test | § 11.1 |

### Mise en attente

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T3.063 | Destination proposée par `BufferOccupancyService` : cellule libre du `stagingBuffer` du poste, dans la zone de température de l'article. | Règle | F15 |
| T3.064 | À défaut : un autre buffer `STAGING` du magasin, de même zone. | Règle | F15 |
| T3.065 | Un support ne contient que les produits d'une seule commande. | Règle | F15 |
| T3.066 | Un article non alimentaire (zone `HOUSEHOLD`) n'est jamais dans le même support qu'un produit alimentaire. | Règle | F15 |
| T3.067 | Un article `fragile` occupe seul son support. | Règle | F15 |
| T3.068 | Aucun emplacement compatible : situation `CATERING_STAGING_FULL` ; le dépôt reste à faire. | Règle | F15 |
| T3.069 | Dépôt confirmé de toute la quantité : unité `STAGED`. | Règle | F15 ; F3 |
| T3.070 | `FabricationExecutionTest` : dépôt nominal ; buffer du poste plein et repli ; aucun emplacement. | Test | § 11.1 |
| T3.071 | `FabricationExecutionTest` : support d'une autre commande refusé ; mélange alimentaire et non alimentaire refusé ; article fragile seul. | Test | § 11.1 |

## T4 — Commande : état publié, remise et paiement au retrait

31 points · dépend de : T3.

### Regroupement et état de la commande

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T4.001 | Un produit fini ou reçu est un `PickedItem` de la ligne de lot traiteur dans un `LoadedTote` : avancement, buffers, annulation, remise, chargement et expédition existants s'y appliquent sans règle propre. | Règle | F16 |
| T4.002 | `ZoneCollectingOrderManagerFactory` exclut les lots d'un chunk traiteur. | Adaptation | F16 |
| T4.003 | `ProductCollectingOrderManagerFactory` exclut les lots d'un chunk traiteur. | Adaptation | F16 |
| T4.004 | `GroupingOrderManagerFactory` exclut les lots d'un chunk traiteur. | Adaptation | F16 |
| T4.005 | `PackingOrderManagerFactory` exclut les lots d'un chunk traiteur. | Adaptation | F16 |
| T4.006 | `ActivitySnapshot` : un lot traiteur est planifié quand toutes ses unités sont `PLANNED` ou au-delà. | Adaptation | F16 |
| T4.007 | `ActivitySnapshot` : un ordre de fabrication non conclu compte parmi les préparations actives. | Adaptation | F16 |
| T4.008 | `ActivitySnapshot` : une ligne `SHORT` ou annulée n'est plus à préparer. | Adaptation | F16 |
| T4.009 | L'étape existante se calcule sur les lignes de courses et les lignes traiteur ensemble ; la correspondance de `PickupContract.orderState` est inchangée. | Règle | F16 |
| T4.010 | Une commande sans ligne de courses dont toutes les unités sont `SHORT` n'est jamais `READY`. | Règle | F16 |
| T4.011 | Une commande sans unité traiteur garde exactement la règle actuelle. | Règle | F16 ; P5 |
| T4.012 | `CateringOrderStateTest` : commande annulée → `CANCELLED` ; remise ou expédiée → `COMPLETED`. | Test | § 11.1 |
| T4.013 | `CateringOrderStateTest` : `READY` avec courses prêtes et unités `STAGED` ; avec une unité `SHORT` ; avec une unité `CANCELLED` ; traiteur seul. | Test | § 11.1 |
| T4.014 | `CateringOrderStateTest` : non `READY` tant qu'une unité est seulement `PRODUCED` ; tant que les courses ne sont pas prêtes. | Test | § 11.1 |
| T4.015 | `CateringOrderStateTest` : `PREPARING` par une préparation de courses, par une unité `IN_PROGRESS`, `PRODUCED`, `STAGED`. | Test | § 11.1 |
| T4.016 | `CateringOrderStateTest` : `RECEIVED` sinon ; commande sans traiteur inchangée. | Test | § 11.1 |
| T4.017 | `CateringOrderStateTest` : un lot traiteur n'est pris par aucune des quatre fabriques existantes. | Test | § 11.1 |

### Remise et paiement au retrait

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T4.018 | La file `GET /drive-arrivals` sert le point de retrait, les supports traiteur et leur emplacement, les unités `SHORT`. | API | F17 ; § 8.4 |
| T4.019 | Commande `AT_PICKUP` à l'état `DUE` : « À régler au magasin » et le montant de la dernière charge reçue, avec ou sans traiteur. | API | F17 |
| T4.020 | `handoverRequiresPaymentAck` vrai : `POST /drive-arrivals/complete` refusé, 409 `PAYMENT_ACK_REQUIRED`, sans `paymentAcknowledged: true`. | Règle | F17 |
| T4.021 | L'accusé d'encaissement est enregistré (date, auteur) ; l'état du paiement n'est pas changé. | Règle | F17 ; P1 |
| T4.022 | Paramètre faux, paiement en ligne ou état `COLLECTED` : aucun accusé demandé. | Règle | F17 |
| T4.023 | Produit de date limite dépassée : remise refusée, 409 `CATERING_EXPIRED`. | Règle | F17 |
| T4.024 | `POST /catering/units/{id}/short` (MANAGER) : déclare l'unité non servie, avec un `PickingShortage` sans ordre ; 409 `STALE_REVISION`, `QUANTITY_EXCEEDED`. | API | § 8.2 ; F17 ; F3 |
| T4.025 | Unité périmée déclarée non servie : un ordre de devenir `ISOLATE` retire le produit de son support ; la remise redevient possible. | Règle | F17 |
| T4.026 | Remise ou expédition de la commande : unités à l'état `HANDED_OVER`. | Règle | F3 ; F17 |
| T4.027 | Livraison à domicile : les supports traiteur entrent dans la tournée par les mécanismes existants. | Adaptation | F17 |
| T4.028 | `CateringHandoverTest` : montant dû servi avec et sans traiteur ; non servi pour un paiement en ligne. | Test | § 11.1 |
| T4.029 | `CateringHandoverTest` : accusé exigé et absent, exigé et fourni, non exigé ; accusé publié. | Test | § 11.1 |
| T4.030 | `CateringHandoverTest` : date limite dépassée, puis unité déclarée non servie et produit isolé. | Test | § 11.1 |
| T4.031 | `CateringHandoverTest` : déclaration « non servi » par un administrateur refusée ; quantité supérieure refusée. | Test | § 8.2 |

## T5 — Modification, annulation et suivi publié

51 points · dépend de : T3 ; l'accusé d'encaissement publié suit T4.

### Modification d'une commande

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T5.001 | `PUT /commerce/v1/stores/{storeCode}/orders/{orderReference}/catering` (SERVICE_CALLER, magasin autorisé) : `slot`, `lines`, l'un des deux exigé ; `deadlines` et `payment` facultatifs. | API | § 8.3 ; F18 |
| T5.002 | Charge mal formée, version ou source inattendue : 400 `INVALID_REQUEST` ; commande sans unité traiteur : 409 `NO_CATERING_UNIT`. | API | § 8.3 |
| T5.003 | Reçu idempotent par `requestId` (`CateringAmendment`) : rejeu identique → même réponse ; autre contenu → 409 `IDEMPOTENCY_CONFLICT`. | Règle | F18.1 |
| T5.004 | `expectedRevision` différente de la révision publiée : 409 `REVISION_CONFLICT`. | Règle | F18.2 |
| T5.005 | Unité non commencée (`RECEIVED`, `PLANNED`, `BLOCKED`) : modification appliquée ; lignes de fabrication, engagements et besoins d'achat réduits, déplacés ou annulés dans la même transaction. | Règle | F18.3 |
| T5.006 | Ordre validé touché par une baisse : la ligne est réduite, l'ordre reste validé. | Règle | F18.3 |
| T5.007 | Ordre validé touché par une hausse ou un nouveau créneau : les quantités concernées en sortent et forment une proposition nouvelle ; manager notifié. | Règle | F18.3 |
| T5.008 | Unité commencée, `amendmentAfterStart = REFUSE` : 409 `FABRICATION_STARTED`, rien n'est appliqué à aucune unité. | Règle | F18.4 |
| T5.009 | Unité commencée, `ACCEPT_DECREASE` : baisse acceptée, quantité fabriquée en excès traitée selon F19.4. | Règle | F18.4 |
| T5.010 | `ACCEPT_DECREASE` : hausse refusée (409 `FABRICATION_STARTED`) ; créneau qui sort le produit de sa date limite refusé (409 `USE_BY_EXCEEDED`). | Règle | F18.4 |
| T5.011 | Nouveau créneau : celui de la commande entière ; l'échéance des lots de courses non préparés suit ; une préparation de courses commencée n'est pas un motif de refus. | Règle | F18 |
| T5.012 | Nouveau créneau : F5 recalculée ; fenêtre vide → modification appliquée, situation `FABRICATION_WINDOW`, avertissement `FABRICATION_WINDOW_EMPTY`. | Règle | F18.5 |
| T5.013 | `dryRun=true` : mêmes contrôles et même réponse (`outcome: PREVIEW`), aucune écriture, aucun reçu. | Règle | F18.6 |
| T5.014 | Besoin d'achat `VALIDATED` touché : non modifié, situation `CATERING_SUPPLY_CHANGE`. | Règle | F18.7 |
| T5.015 | Quantité 0 : retrait de la ligne, unité annulée selon F19.4. | Règle | F18.8 |
| T5.016 | `deadlines` et `payment` reçus remplacent les valeurs conservées de la commande. | Règle | F18 |
| T5.017 | Réponse 200 : le suivi de la commande, `outcome` (`APPLIED`, `PREVIEW`) et `warnings`. | API | § 8.3 |
| T5.018 | `CateringAmendmentTest` : créneau seul, quantités seules, les deux, aucun des deux refusé. | Test | § 11.1 |
| T5.019 | `CateringAmendmentTest` : rejeu identique, clé réutilisée, révision dépassée. | Test | § 11.1 |
| T5.020 | `CateringAmendmentTest` : unité non commencée dans chacun de ses trois états ; baisse et hausse dans un ordre validé. | Test | § 11.1 |
| T5.021 | `CateringAmendmentTest` : unité commencée avec `REFUSE` (atomicité sur plusieurs unités) ; avec `ACCEPT_DECREASE` : baisse, hausse, date limite dépassée. | Test | § 11.1 |
| T5.022 | `CateringAmendmentTest` : fenêtre vide ; `dryRun` sans écriture ; besoin validé touché ; quantité 0 ; `deadlines` et `payment` remplacés ; courses redatées. | Test | § 11.1 |

### Annulation

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T5.023 | `POST /orders/{orderRef}/cancellation` (SERVICE_CALLER, nouveau) : `{orderRef, reason, requestId, scope, payment}` ; `scope` `ALL` par défaut ou `CATERING` ; vaut pour toute commande, avec ou sans traiteur. | API | F19 ; § 8.4 ; C7 |
| T5.024 | Idempotent par `requestId` ; sans `requestId`, par `(orderRef, scope)` ; autre contenu sous la même clé : 409 `IDEMPOTENCY_CONFLICT`. | Règle | F19.1 |
| T5.025 | `scope = ALL` : annulation de la commande par `CancellationDispositionService`, flux et politiques existants pour les courses, et annulation des unités traiteur. | Règle | F19.2 |
| T5.026 | `scope = CATERING` : seules les unités traiteur sont annulées ; lignes de courses, créneau et bacs inchangés ; commande ouverte. | Règle | F19.3 |
| T5.027 | Unité non commencée : `CANCELLED`, lignes de fabrication réduites, engagements et besoins `PROPOSED` annulés. | Règle | F19.4 |
| T5.028 | Unité commencée, `cancelAfterStart = REFUSE` : 409 `FABRICATION_STARTED`, rien n'est annulé, courses comprises. | Règle | F19.4 |
| T5.029 | Unité commencée, `ACCEPT_WITH_DISPOSITION` : unité `CANCELLED` et `CancellationDispositionOrder` pour les quantités fabriquées. | Règle | F19.4 |
| T5.030 | Devenir `REALLOCATE`, s'il est autorisé pour l'article : le produit sert une autre unité non fabriquée de même article, variante et `configurationKey`, si sa date limite le permet ; la fabrication de cette unité est réduite d'autant. | Règle | F19.4 |
| T5.031 | Devenir `ISOLATE` à défaut : support distinct déposé en buffer ; `RETURN` jamais appliqué à un produit fabriqué ou fourni. | Règle | F19.4 |
| T5.032 | Unité `STOCKED` : ses lignes de lot suivent le flux d'annulation existant des courses. | Règle | F19.4 |
| T5.033 | Besoin d'achat `VALIDATED` : situation `CATERING_SUPPLY_CHANGE` ; article reçu non mis en support : engagement libéré, il reste en stock ; article mis en support : devenir. | Règle | F19.5 |
| T5.034 | `payment` reçu remplace le montant conservé de la commande. | Règle | F19 ; F17 |
| T5.035 | Réponse 202 : `{orderRef, scope, outcome: CANCELLED, revision, units: [{lineId, state, disposition}]}`. | API | F19.6 |
| T5.036 | Commande déjà annulée : 202 avec l'état courant, sans effet ; déjà remise : 409 `PICKUP_COMPLETED` ; inconnue ou magasin non autorisé : 404 sans détail. | Règle | F19.7 |
| T5.037 | Annulation par le manager (`POST /field-operations/customer-orders/{id}/cancel` existant) : mêmes règles pour les lignes traiteur ; 409 `FABRICATION_STARTED`. | Adaptation | F19.8 ; § 8.4 |
| T5.038 | `CateringCancellationTest` : `ALL` et `CATERING` sur commande mixte ; commande sans traiteur ; traiteur seul. | Test | § 11.1 |
| T5.039 | `CateringCancellationTest` : rejeu avec et sans `requestId` ; clé réutilisée ; commande déjà annulée ; déjà remise ; inconnue ; magasin non autorisé. | Test | § 11.1 |
| T5.040 | `CateringCancellationTest` : unité non commencée ; commencée avec `REFUSE` (rien n'est annulé) ; avec devenir. | Test | § 11.1 |
| T5.041 | `CateringCancellationTest` : réaffectation possible ; impossible faute d'unité identique ; impossible par date limite ; non autorisée pour l'article ; isolement. | Test | § 11.1 |
| T5.042 | `CateringCancellationTest` : unité en stock ; besoin validé ; article reçu non mis en support ; mis en support ; `payment` remplacé. | Test | § 11.1 |
| T5.043 | `CateringCancellationTest` : annulation par le manager de lignes traiteur non commencées et commencées. | Test | § 11.1 |

### Suivi et production publiés

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T5.044 | `GET /commerce/v1/stores/{storeCode}/orders/{orderReference}/catering` : `revision`, `orderState`, `updatedAt`, `payment` avec `acknowledgedAt`, et par unité `lineId`, `state`, quantités demandée, fabriquée, non servie, `plannedFor`, `producedAt`, `useBy`. | API | § 8.3 ; F20 |
| T5.045 | Aucun nom d'employé, emplacement ni lot de composant publié ; la lecture `…/pickup` est inchangée et partage la même révision. | Règle | F20 |
| T5.046 | `GET /commerce/v1/stores/{storeCode}/catering/production?from=&to=` : par jour et poste, ouvert ou fermé, minutes de capacité, engagées et proposées ; par article et variante, quantités engagées. | API | § 8.3 ; F21 |
| T5.047 | Fenêtre de plus de 62 jours : 400 ; imomswms ne refuse jamais une commande au titre de cette lecture. | Règle | F21 ; P1 |
| T5.048 | Codes commerce : `INVALID_REQUEST`, `ORDER_NOT_FOUND`, `NO_CATERING_UNIT`, `REVISION_CONFLICT`, `IDEMPOTENCY_CONFLICT`, `FABRICATION_STARTED`, `USE_BY_EXCEEDED`, `ORDER_CANCELLED`, `PICKUP_COMPLETED`. | API | § 8.3 |
| T5.049 | `CateringCommerceContractTest` : rôle absent, mauvais rôle, magasin non autorisé, commande d'un autre magasin, sur chacune des trois routes. | Test | § 11.1 |
| T5.050 | `CateringCommerceContractTest` : chaque code d'erreur ; fenêtre de 62 jours acceptée, 63 refusée. | Test | § 11.1 |
| T5.051 | `CateringCommerceContractTest` : contenu du suivi à chaque état d'unité ; absence des données non publiées ; révision commune avec `…/pickup`. | Test | § 11.1 |

## T6 — Supervision, notifications et contenus servis aux écrans

36 points · dépend de : T2 à T5.

### Situations de supervision

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T6.001 | Situations ajoutées à `ActivitySupervisionService` avec la structure existante : identité stable, nature, gravité, cause, conséquences, commandes concernées, propositions, actions. | Adaptation | F22 |
| T6.002 | `CATERING_UNREFERENCED:<ean>` : information manquante ; avérée si l'unité est `BLOCKED` ; action : compléter la fiche. | Règle | F22 ; I5, F2 |
| T6.003 | `CATERING_DISABLED` : avérée ; action : activer ou annuler. | Règle | F22 |
| T6.004 | `CATERING_RECIPE_MISSING:<article>:<variante>` : information manquante ; avérée si `BLOCKED` ; action : saisir la nomenclature. | Règle | F22 ; F4.5 |
| T6.005 | `FABRICATION_UNPLANNED` : unités `RECEIVED` à moins de `planningLeadHours` de leur fin au plus tard, hors unités couvertes par trois autres situations ; risque, avérée si dépassée. | Règle | F22 |
| T6.006 | `FABRICATION_WINDOW:<poste>` : avérée ; actions : ouvrir le poste, changer l'avance, contacter le commerce. | Règle | F22 ; F5, F18.5 |
| T6.007 | `FABRICATION_CAPACITY:<poste>:<jour>` : risque, avérée le jour même ; actions : déplacer dans la fenêtre, habiliter un employé. | Règle | F22 ; F7 |
| T6.008 | `FABRICATION_LATE:<ordre>` : ordre validé non conclu après sa fin au plus tard ; action : réaffecter. | Règle | F22 |
| T6.009 | `CATERING_COMPONENT_SHORTAGE:<composant>` : risque, avérée si l'ordre a commencé ; action : réapprovisionnement existant. | Règle | F22 ; F9 |
| T6.010 | `CATERING_SUPPLY_LATE:<fournisseur>` : risque, avérée après la fin au plus tard ; actions : valider, relancer. | Règle | F22 ; F10.7 |
| T6.011 | `CATERING_SUPPLY_CHANGE:<besoin>` : avérée ; décision du manager. | Règle | F22 ; F18.7, F19.5 |
| T6.012 | `CATERING_STAGING_FULL:<buffer>` : avérée ; actions : libérer, autre buffer. | Règle | F22 ; F15 |
| T6.013 | `CATERING_SHORT:<commande>` : avérée ; action : prévenir le commerce. | Règle | F22 ; F12 |
| T6.014 | `CATERING_EXPIRED:<lot>` : avérée ; actions : déclarer l'unité non servie, isoler le produit. | Règle | F22 ; F13 |
| T6.015 | `OVERDUE`, `PLANNING`, `ROUTING` et `STOCK_UNKNOWN` ne se déclenchent plus pour les lignes traiteur `FABRICATED` et `SUPPLIED`. | Adaptation | F22 |
| T6.016 | Réaffecter ou déplacer un ordre validé par `POST /store-operations/supervision/apply` existant, qui revérifie F5, F7 et l'habilitation. | Adaptation | F22 |
| T6.017 | Indicateurs ajoutés au rapport : unités à fabriquer dans l'horizon, charge des postes par jour, unités en attente de remise. | API | F22 |
| T6.018 | `CateringSupervisionTest` : chaque situation dans chacune de ses natures, avec ses commandes concernées et son action. | Test | § 11.1 |
| T6.019 | `CateringSupervisionTest` : `FABRICATION_UNPLANNED` absente quand l'unité est déjà couverte par chacune des trois autres situations. | Test | § 11.1 |
| T6.020 | `CateringSupervisionTest` : situations existantes non déclenchées pour le traiteur, toujours déclenchées pour les courses de la même commande. | Test | § 11.1 |
| T6.021 | `CateringSupervisionTest` : ordre validé réaffecté et déplacé par `supervision/apply`, avec chaque refus. | Test | F22 |

### Notifications

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T6.022 | Notice « Commande traiteur DEMO-TRA-001 pour le samedi 21 novembre, 10:00 – 11:00 » (référence, jour et créneau de la commande) à chaque commande traiteur reçue. | Règle | F23 |
| T6.023 | Notice de proposition à valider. | Règle | F23 |
| T6.024 | Notice de modification ou d'annulation reçue touchant un ordre validé. | Règle | F23 ; F18.3 |
| T6.025 | Notice d'unité non servie. | Règle | F23 |
| T6.026 | Destinataires : employés actifs du magasin ayant un des rôles `notificationRoles`, par `OperatorSupportService.systemNotice`. | Règle | F23 |
| T6.027 | Une notice par fait, dédoublonnée par sa clé ; un échec de notification n'empêche jamais l'opération. | Règle | F23 |
| T6.028 | `CateringSupervisionTest` : chaque notice ; destinataires selon les rôles ; dédoublonnage ; échec sans effet sur l'opération. | Test | § 11.1 |

### Contenus servis aux écrans

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T6.029 | Traiteur › À valider : propositions, charge, capacité et composants manquants servis déjà calculés et libellés. | API | § 9 ; P3 |
| T6.030 | Traiteur › Fabrication du jour : feuille de fabrication, avancement des ordres et des unités, impression, déclaration « non servi ». | API | § 9 |
| T6.031 | Traiteur › Besoins : besoins en composants, besoins d'achat, bon de commande. | API | § 9 |
| T6.032 | Traiteur › Référentiel : fiches, nomenclatures, postes, habilitations. | API | § 9 |
| T6.033 | Supervision : situations et indicateurs traiteur dans le rapport existant. | API | § 9 |
| T6.034 | Mission Fabrication : lecture de l'ordre, actions permises, étiquette, dépôt. | API | § 9 |
| T6.035 | Remise : supports traiteur, montant dû, accusé d'encaissement. | API | § 9 |
| T6.036 | Test : aucune de ces lectures ne laisse un calcul de date, de durée, de charge ou de montant au client. | Test | P3 |

## T7 — Données de démonstration et recette

34 points · dépend de : tous.

### Données de démonstration

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T7.001 | `DemoCateringInitializer` : exécuté après `DemoStockInitializer` et avant `DemoMarieMissions` ; toutes les dates relatives au lancement. | Démo | § 10 |
| T7.002 | Politique `CateringConstraints` aux valeurs « Démo », liée au magasin de démonstration. | Démo | § 10 |
| T7.003 | Poste `LAB` « Laboratoire traiteur » : buffer `STAGING` réfrigéré, imprimante simulée, ouvert tous les jours de 00:00 à 23:59, 480 minutes. | Démo | § 10 |
| T7.004 | Poste `PAT` « Pâtisserie » : mêmes caractéristiques. | Démo | § 10 |
| T7.005 | Habilitations : Marie Martin (100235) et un second employé au poste `LAB` ; un employé au poste `PAT`. | Démo | § 10 |
| T7.006 | Gabarit d'étiquette `CATERING_UNIT`. | Démo | § 10 ; F14 |
| T7.007 | `DEMO-TRA-PAIN-SURPRISE` : `FABRICATED`, `LAB`, base par variante 16, 32, 48 ; 10, 15, 20 min. | Démo | § 10 |
| T7.008 | `DEMO-TRA-PLATEAU-VEGE` : `FABRICATED`, `LAB`, base 24, 48 ; 15, 25 min. | Démo | § 10 |
| T7.009 | `DEMO-TRA-VERRINES` : `FABRICATED`, `LAB`, base 24 ; 30 min. | Démo | § 10 |
| T7.010 | `DEMO-TRA-BUFFET-FROID` : `FABRICATED`, `LAB`, base 12 et un complément par choix ; 45 min. | Démo | § 10 |
| T7.011 | `DEMO-TRA-MENU` : `FABRICATED`, `LAB`, base et un complément par choix des quatre étapes ; 8 min. | Démo | § 10 |
| T7.012 | `DEMO-TRA-GATEAU-CHIFFRE` : `FABRICATED`, `PAT`, base 8, 15 et un complément par parfum ; 40, 60 min ; `fragile` ; conservation 72 h ; devenir `ISOLATE`. | Démo | § 10 ; § 6 |
| T7.013 | `DEMO-TRA-TARTE-POMMES` : `SUPPLIED`, `LAB`, « Fournisseur de démonstration », 2 jours. | Démo | § 10 |
| T7.014 | `DEMO-TRA-PAIN-BUFFET` : `SUPPLIED`, `LAB`, même fournisseur, 1 jour. | Démo | § 10 |
| T7.015 | `DEMO-TRA-BOISSONS` : `STOCKED`, lignes `PICKED` sur des boissons du stock de démonstration. | Démo | § 10 |
| T7.016 | `DEMO-TRA-ARTS-TABLE` : `STOCKED`, lignes `PICKED` sur des produits non alimentaires du stock de démonstration. | Démo | § 10 |
| T7.017 | Composants des nomenclatures présents au stock de démonstration, sauf celui dont le manque sert `DEMO-TRA-004`. | Démo | § 10 |
| T7.018 | `DEMO-TRA-001` : retrait J+2 10:00, paiement au retrait, courses (4 lignes), 2 pains surprises 32, 1 buffet froid, 1 gâteau chiffre 15 parts « 30 » chocolat, 1 boissons ; propositions à valider. | Démo | § 10 |
| T7.019 | `DEMO-TRA-002` : retrait J+1 à l'heure du lancement, payée en ligne, 12 menus de même composition et 1 tartes aux pommes ; besoin d'achat validé ; ordre `FAB` validé, affecté à Marie Martin. | Démo | § 10 |
| T7.020 | `DEMO-TRA-003` : retrait 2 heures après le lancement, 1 plateau végétarien 24 et 1 verrines ; fabriqué, étiqueté, en attente ; commande `READY`. | Démo | § 10 |
| T7.021 | `DEMO-TRA-004` : retrait J+3, 1 gâteau chiffre 15 parts cœur ; proposition avec composant manquant. | Démo | § 10 |
| T7.022 | `DemoMarieMissions` compte `FabricationOrder` dans sa règle « une mission par type » et retient l'ordre de `DEMO-TRA-002`. | Démo | § 10 |
| T7.023 | Test : après l'initialisation, chaque commande de démonstration est dans l'état annoncé, quels que soient le jour et l'heure du lancement. | Test | § 10 |
| T7.024 | Test : Marie Martin a un ordre de fabrication affecté ; une seconde initialisation ne le réaffecte pas. | Test | § 10 |

### Scénario de recette

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T7.025 | Étape 1 — `launch.sh` : base régénérée ; Supervision affiche `CATERING_COMPONENT_SHORTAGE` pour `DEMO-TRA-004` et `CATERING_SUPPLY_LATE` en risque pour les tartes de `DEMO-TRA-002`. | Recette | § 11.2 |
| T7.026 | Étape 2 — À valider : pour `DEMO-TRA-001`, un ordre `LAB` et un ordre `PAT` le dernier jour ouvert de leur fenêtre ; charge 75 / 480 et 60 / 480 minutes. | Recette | § 11.2 |
| T7.027 | Étape 3 — Valider : ordres visibles de leurs employés ; besoins en composants mis à jour. | Recette | § 11.2 |
| T7.028 | Étape 4 — Storepicker, Marie Martin : « Fabriqué » sur les 12 menus de `DEMO-TRA-002` ; 12 étiquettes ; la ligne des tartes attend sa réception. | Recette | § 11.2 |
| T7.029 | Étape 5 — Besoins : réception des tartes ; mise en support confirmée ; étiquette ; dépôt dans le buffer du laboratoire ; mission conclue. | Recette | § 11.2 |
| T7.030 | Étape 6 — Suivi de `DEMO-TRA-002` : toutes les unités `STAGED` ; `…/pickup` : `READY`. | Recette | § 11.2 |
| T7.031 | Étape 7 — Modification à blanc de `DEMO-TRA-001` avec un créneau dans 30 minutes : avertissement `FABRICATION_WINDOW_EMPTY`. | Recette | § 11.2 |
| T7.032 | Étape 8 — Annulation du traiteur seul de `DEMO-TRA-001` avec le `payment` recalculé : unités annulées, engagements libérés, courses inchangées. | Recette | § 11.2 |
| T7.033 | Étape 9 — Annulation de `DEMO-TRA-003`, déjà fabriquée : ordre de devenir en isolement. | Recette | § 11.2 |
| T7.034 | Étape 10 — Remise de `DEMO-TRA-002` sans accusé ; remise des courses de `DEMO-TRA-001` avec « À régler au magasin » au montant reçu à l'étape 8 et accusé exigé. | Recette | § 11.2 |

## T8 — Changements dans immcomm

15 points · dépend de : contrats de T1 et de T5.

### Émission de la commande

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T8.001 | `OmsOrderAssembler` : bloc `payment` pour toute commande réglée au retrait, avec ou sans traiteur. | Émetteur | § 4.3 ; § 12 |
| T8.002 | `OmsOrderAssembler` : bloc `catering` racine (version, réception, dates limites, point de retrait) quand la commande a des lignes traiteur. | Émetteur | § 4.3 |
| T8.003 | `OmsOrderAssembler` : objet `catering` de chaque ligne portant `cateringLineId`, à partir de `order.cateringLines` : `lineId`, variante, configuration, libellés, allergènes, ingrédients, conservation, conseils. | Émetteur | § 4.3 |
| T8.004 | `configurationKey` obtenue par `CateringConfiguration.key` ; aucune valeur métier calculée par l'assembleur. | Émetteur | § 4.3 |
| T8.005 | Test immcomm : charge d'une commande sans traiteur en ligne inchangée ; au retrait ; mixte ; composition ; gâteau. | Test | § 4.3 |

### Annulation et modification relayées

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T8.006 | `OmsGateway.cancel` : `requestId`, `scope` et `payment` recalculé dans la charge d'annulation. | Émetteur | § 12 ; F19 |
| T8.007 | Annulation partielle (R12) relayée avec `scope: CATERING`. | Émetteur | § 12 ; F19 |
| T8.008 | Refus 409 d'imomswms pris en compte et présentés, sans confirmation feinte. | Émetteur | § 12 ; F19 |
| T8.009 | `APPLY_ORDER_CHANGE` (R13) : appel de `PUT …/catering` en `dryRun` pour l'aperçu. | Émetteur | § 12 ; F18 |
| T8.010 | `APPLY_ORDER_CHANGE` : appel réel avant d'appliquer, avec `deadlines` et `payment` recalculés ; refus présenté à l'équipe. | Émetteur | § 12 ; F18 |
| T8.011 | Test immcomm : annulation entière et partielle relayées ; rejeu ; chaque refus ; aperçu et application d'une modification. | Test | § 12 |

### Suivi et production lus

| N° | Point à réaliser | Nature | Référence spec |
|---|---|---|---|
| T8.012 | Lecture du suivi `…/catering` pour afficher l'avancement des préparations. | Émetteur | § 12 ; F20 |
| T8.013 | Accusé d'encaissement repris dans « Encaissé au retrait » (R10). | Émetteur | § 12 ; F17 |
| T8.014 | `/admin` › Traiteur › Fabrication : lecture de `…/catering/production` à côté de la capacité commerciale. | Émetteur | § 12 ; F21 |
| T8.015 | Test immcomm : suivi indisponible ou incohérent sans effet sur la commande ; accusé repris une seule fois. | Test | § 12 |
