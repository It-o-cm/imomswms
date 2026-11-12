# Opérations magasin

Les nouvelles API sont authentifiées et limitées au magasin de l’acteur MANAGER. ADMIN peut sélectionner un magasin. Les formulaires ne renvoient jamais les mots de passe ni leurs empreintes.

Les lectures GraphQL des données propres à un magasin appliquent la même règle (`GraphQLTrait.readScope`) : un MANAGER ne lit que son magasin, un SERVICE_CALLER le magasin unique de ses habilitations actives, ADMIN lit tous les magasins. Sont concernés les listes et les lectures par identifiant ou par code des préparations (`allFulfillments`, `fulfillment`, `fulfillmentByOrderId`), lots (`allLots`, `lot`, `lotsByChunkId`), commandes (`allOrders`, `order`, `orderByOrderId`), demandes de commande (`orderRequest`, `ordersByNumber`), ordres de manutention (`handlingOrder`), événements (`eventsByStoreCode`), employés (`allEmployees`, `employee`, `employeeByBadgeId`), bacs (`allTotes`, `tote`, `toteByCode`, `totesByStoreCode`) et buffers (`allBuffers`, `buffer`, `bufferByBuildingId`). Un élément d'un autre magasin est répondu « introuvable », comme un identifiant inexistant ; un code de magasin hors périmètre est refusé (`eventsByStoreCode`) ou donne une liste vide (`totesByStoreCode`). Un MANAGER sans magasin est refusé. La topologie suit la même règle : magasins (`allStores`, `store`, `storeByCode`), allées, nœuds et zones du plan (listes, lectures par identifiant, par code et par bâtiment) ; un élément d’un autre magasin est refusé.

Les écritures restent dans le magasin de l’acteur : une demande de commande (`createOrderRequest`, `updateOrderRequest`) ne peut viser ni être déplacée vers un autre magasin, et sa spécification doit désigner le même magasin ; une tranche de préparation (`updateFulfillmentChunk`) ne reçoit qu’un bâtiment ou un emplacement du magasin de sa commande ; le plan de prélèvement (`/handling-orders/planning`) n’accepte que des ordres du magasin de l’employé ; `POST /orders` n’accepte une commande que pour le magasin habilité du compte d’intégration (403 sinon). Un manager ne modifie que sa propre fiche de responsable : la fiche d’un autre responsable relève de l’administration.

## Comptes, connexion et accès

- Connexion (`POST /auth/login`) : la réponse d’échec est toujours `{"error": "Identifiants invalides"}`, que le compte existe, soit désactivé ou que le mot de passe soit faux. Après 5 échecs en 15 minutes, l’identifiant est refusé (429) même avec le bon mot de passe ; une adresse cliente est refusée après 50 échecs, tous identifiants confondus. Une connexion réussie efface les échecs de l’identifiant. Réglages : `imomswms.login.*`.
- Réinitialisation : la demande répond toujours 200 avec le même message ; au-delà de 3 demandes par identifiant (20 par adresse) dans l’heure, aucun mail n’est envoyé (`imomswms.password-reset.*`). Seule l’empreinte SHA-256 du jeton envoyé par mail est stockée ; le jeton expire au bout de 15 minutes, ne sert qu’une fois et ferme les sessions ouvertes avant le changement. Le nom inséré dans le mail est échappé.
- Mots de passe : de 4 à 100 caractères (les préparateurs utilisent un code à 4 chiffres), non vides ; 400 avec la règle sinon. Les comptes d’amorçage `admin` et `service` sont conservés tels quels.
- Clés des jetons : chaque installation génère sa propre paire RSA au premier démarrage dans `data/jwt/` (ignoré par git, comme la base). La supprimer invalide toutes les sessions. Les clés de `src/test/resources` ne servent qu’aux tests.
- Secrets : le mot de passe d’application Gmail se lit dans `.env` (`MAILER_PASSWORD`), jamais dans les sources.
- GraphQL (schéma compris) et OpenAPI exigent un jeton ; seule la Dev UI locale reste ouverte. Les requêtes GraphQL sont bornées en profondeur (15) et en complexité (2000).
- Imprimantes : une imprimante réelle doit avoir une adresse privée du réseau du magasin et un port d’impression (9100 à 9103, 515, 631) ; boucle locale et adresses de liaison locale sont refusées, et la connexion se fait vers l’adresse vérifiée, sans nouvelle résolution du nom. Les tests seuls acceptent la boucle locale (`imomswms.printing.allow-loopback`).
- Plan de fond : PNG, JPEG, GIF ou WebP en base64, 8 Mo au plus une fois décodé.
- Brouillons de règles par IA : 20 par utilisateur et par heure (`imomswms.rules.draft.*`), 429 au-delà.
- Imports CSV : la réponse est un JSON valide ; une ligne refusée par la base est signalée sans détail technique, le détail reste dans le journal.

## Lieux et équipe

- GET /store-operations/places?storeId=… ; POST /store-operations/buildings ; POST /store-operations/buffers.
- GET /store-operations/employees?storeId=… ; POST /store-operations/employees.
- GET /store-operations/planning?storeId=…&start=2026-10-19T00:00:00&end=2026-10-26T00:00:00.
- POST /store-operations/planning : storeId, employeeId, kind (SHIFT, ABSENCE, TASK), title, start, end ; id pour modifier.
- POST /store-operations/planning/{id}/cancel?storeId=… : annulation conservée dans l’historique.
- POST /store-operations/planning/copy-week : storeId, weekStart et employeeId facultatif. Recopie les horaires sept jours plus tard sans créer deux fois le même créneau.
- POST /store-operations/handling/{id}/schedule : storeId, employeeId (nullable pour désaffecter), start et end. Les ordres terminés ou annulés sont verrouillés.

Le planning hebdomadaire permet la création par clic, le déplacement vers un autre jour/employé et le redimensionnement par poignée (ou flèches du clavier). Les chevauchements entre absences, tâches et manutention et les tâches hors horaires sont signalés. Ces alertes laissent le manager arbitrer ; elles ne déplacent pas automatiquement les autres opérations.

Un buffer peut être désactivé. Son changement de bâtiment est refusé lorsqu’il est occupé ou déjà affecté à un rangement. Son point du plan est mémorisé par code afin de survivre à l’enregistrement de la topologie.

## Bacs et expéditions

Chaque bac d’un rapport de collecte porte son propre bufferId. Le serveur vérifie l’appartenance au magasin, l’activité du buffer, la température et sa capacité en nombre de bacs. La tuile Buffers affiche les sessions IN_USE physiquement déposées, avec les places par température.

- GET /shipments/orders?storeId=… : commandes à expédier — celles dont des bacs préparés ne sont pas encore partis, quelle que soit leur ancienneté —, de l'échéance la plus proche à la plus lointaine, avec lignes de fulfillment, emplacements prévus, quantités en bacs, état d’expédition, échéance et `shippable`. Leur nombre est borné par les bacs en service, un bac ne contenant qu'une commande. Avec `&code=…`, retourne cette commande du magasin quel que soit son état (liste vide si elle n'existe pas) : la tuile Expéditions l'utilise pour présélectionner une commande ouverte depuis une autre vue.
- GET /shipments?storeId=… : les 200 dernières expéditions.
- POST /shipments : storeId, orderId, requestKey, mode (CARRIER ou CUSTOMER), recipient, transportReference, toteIds (identifiants des sessions LoadedTote).
- POST /shipments/{id}/returns : storeId et toteId ; retour physique du bac vide.

Le départ est enregistré avec les produits/quantités réellement préparés, les codes de bacs et leurs buffers de départ. Le stock est débité une seule fois à l’emplacement de prélèvement enregistré. Un stock insuffisant, un bac non déposé, déjà expédié ou contenant une autre commande sont refusés. La confirmation est idempotente et conserve le lien vers PickedItem, LotLine et FulfillmentLine. Le statut commande devient PARTIALLY_SHIPPED ou SHIPPED selon les quantités expédiées.

Les bacs partis quittent l’occupation des buffers mais restent IN_USE, donc non réutilisables. Leur retour vide les libère (DELIVERED) sans réintroduire des produits en stock. Ce module enregistre le départ et la remise au client ; il ne fournit pas de géolocalisation transporteur ni de preuve de livraison externe.

## Événements

Les transitions de stock sont évaluées côté Java, par compte produit/magasin, avec la même distinction que le stock : solde actuel épuisé = indisponible ; solde positif avec épuisement dans l’horizon = tension. L’horizon d’alerte est configurable par storepilot.stock.alert-horizon-hours (24 par défaut).

Les mutations de stock marquent le compte à réévaluer. Le contrôle traite au maximum 200 comptes par passage (toutes les secondes), puis programme le prochain contrôle aux frontières temporelles connues. L’état précédent est persisté avec l’événement : pas de répétition au même état, mais une nouvelle alerte après rétablissement puis nouvel incident.

Le contrôle des manutentions en retard inclut OPEN, SCHEDULED, PROCESSING et FAILED après requestedEnd. Les ordres clos et les exécutions toutes terminées n’alertent pas. Le contrôle indépendant passe toutes les 15 secondes, en plus du cycle magasin existant. Une alerte par ordre est conservée. La tuile Événements se rafraîchit sans faire varier la hauteur de ses cartouches.


### Armoires et cellules des buffers

La tuile Buffers regroupe les dépôts par bâtiment. Chaque buffer peut contenir plusieurs armoires, configurables par code, température, nombre de colonnes et de rangées (1 à 30 chacune). Les coordonnées commencent à 1 : rangée–colonne. Pour une température équipée d’armoires, la capacité est la somme de leurs cellules ; sinon la capacité du buffer reste utilisée. Les bacs sans cellule restent visibles.

`POST /store-operations/buffer-cabinets` : `storeId`, `bufferId`, `id` facultatif, `code`, `zone` (`AMBIENT`, `CHILLED`, `FROZEN`), `columns`, `rows`. Un manager reste limité à son magasin. Un redimensionnement ou changement de température ne peut exclure un bac présent.

Les rapports de collecte par bac et les lignes de réception de réapprovisionnement acceptent les champs facultatifs `cabinetId`, `cellRow`, `cellColumn`. La cellule doit appartenir au buffer, avoir la température adaptée et être libre. Le verrou du buffer sérialise les dépôts concurrents. Sans ces champs, le bac est déposé sans cellule.

`POST /store-operations/buffer-deposits` permet de préciser ou corriger la cellule d’un bac déjà déposé : `storeId`, `bufferId`, `toteId` (session LoadedTote), `cabinetId`, `cellRow`, `cellColumn`. Les trois derniers champs nuls libèrent sa cellule sans retirer le bac du buffer. Cette action ne remplace pas la confirmation de manutention.

Le départ en expédition libère la cellule. La vue GraphQL `bufferOccupancy` expose `cabinets { id code zone columns rows }` et les coordonnées courantes des bacs.

La correction depuis Buffers accepte aussi `physicalToteId` : un bac vide peut ainsi être placé sans session préalable. Les bacs actifs peuvent être déplacés par glisser-déposer entre cellules et buffers du même magasin. Un bac expédié attend son retour physique. Les adresses de stock contenues dans un bac suivent la correction de bâtiment et de buffer sans changer les quantités. `GET /store-operations/buffer-totes?storeId=…&search=…` recherche au plus 50 bacs ; `GET /store-operations/buffer-totes/{physicalToteId}?storeId=…` retourne leur contenu courant.

### Suivi d’une ligne de commande

`GET /shipments/line-tracking?orderCode=…&lineNumber=…&ean=…` (avec `storeId` ou `storeCode` pour un administrateur) retourne l’état de la ligne, les quantités prélevées/expédiées/restant à prélever, les adresses prévues et les bacs, avec bâtiment, buffer, armoire et cellule. Le lien repose sur fulfillment → lot line → picked item → loaded tote / shipment line ; des produits identiques sur deux lignes ne sont pas confondus. Les anciennes commandes déclarées expédiées sans historique sont explicitement signalées. Le manager reste limité à son magasin.

Les filtres de situation de Stock sont cumulables : `situation` accepte plusieurs états séparés par une virgule (`UNAVAILABLE,TENSION`), `ALL` ou `NONE`. Le filtre s’applique côté Java avant la pagination. Le plan de sélection s’ouvre en modale ; zone, allée, baie et buffer sont directement cliquables sans mode préalable, avec zoom à la molette et déplacement du fond à la souris.

Le filtre « Bacs non vides » est actif par défaut dans Buffers. La recherche `/store-operations/buffer-totes` accepte `nonEmptyOnly` (valeur par défaut `true`) et filtre avant la limite de 50 résultats, à partir des prélèvements confirmés et des soldes physiques des adresses de bac (réapprovisionnement ou mise en sécurité). La grille des armoires conserve tous les bacs positionnés. Le contenu d’un bac s’ouvre dans une modale ; la collecte, les cellules et la modale partagent le composant visuel ToteCard.

### Correction du contenu d’un bac

La modale permet de modifier les quantités existantes et de retirer un produit (quantité zéro), avec un motif obligatoire. `POST /store-operations/buffer-totes/{physicalToteId}/contents` reçoit `storeId`, `sessionId`, `requestKey` (40 caractères maximum), `reason`, et `lines [{ id, kind: "PICKED" | "STOCK", expectedQuantity, quantity }]`. Le GET de contenu fournit ces identifiants, le type produit et les quantités de référence.

La transaction vérifie le magasin, la session active, l’absence d’expédition, la quantité attendue et les quantités entières pour les produits à la pièce. Les corrections de prélèvement conservent les liens aux lignes de préparation et ne peuvent dépasser leurs quantités demandées. Le stock de prélèvement est débité lors de l’expédition comme auparavant. Les corrections de stock situé dans les bacs de réapprovisionnement ou de mise en sécurité écrivent un mouvement de différence dans le registre de stock. Une trace ToteContentCorrection conserve les valeurs avant/après, le motif et l’auteur ; la référence rend les réessais idempotents.

L’ajout de produit est disponible dans la modale : recherche par nom ou EAN à partir de 2 caractères, via `GET /store-operations/buffer-products?storeId=…&search=…` (30 résultats maximum). Les corrections acceptent `kind: "ADD"`, `id` égal à l’identifiant produit, `expectedQuantity: 0` et une quantité strictement positive. Un bac actif déjà déposé dans un buffer est requis. Le produit doit être actif et compatible avec la température et le conditionnement du bac. L’ajout crée ou réutilise une adresse de stock dans ce bac et inscrit le mouvement et la trace de correction de manière idempotente. Les produits déjà présents se corrigent sur leur ligne existante. L’ajout de stock ne crée pas une affectation à une commande ; un bac contenant du stock non rattaché ne peut pas être expédié comme bac de commande sans répartition préalable.

La tuile Lieux présente les magasins et entrepôts comme des lieux distincts, avec une façade, leurs zones de dépôt rattachées et un accès direct au plan. Les formulaires d’ajout et de configuration s’ouvrent dans des modales ciblées. Le catalogue des plans utilise le champ Java `Building.buildingType` pour distinguer « Plan du magasin » et « Plan de l’entrepôt », y compris lorsque GraphQL expose le type de base Building. Les identifiants des tuiles et les dispositions sauvegardées restent compatibles.

Depuis Lieux, les boutons « Plan du magasin » et « Plan de l’entrepôt » ouvrent désormais l’éditeur dans une modale temporaire, sans ajouter, déplacer ou agrandir une tuile du tableau de bord. L’ajout permanent d’un plan reste une action distincte depuis la bibliothèque.

### Propositions d’affectation internes

`POST /store-operations/planning/assignment-options` reçoit un PlanningInput (`storeId`, `kind` TASK/HANDLING, `start`, `end`, `id` de créneau ou `orderId` éventuels, `requiredRole` optionnel PICKER/CASHIER). Le moteur Java maison PlanningAssignmentService, sans solveur ni service externe, retourne des candidats ordonnés par minutes déjà affectées puis par nom. Il exige une couverture complète par les horaires de présence (créneaux adjacents acceptés), aucune absence/tâche/manutention concurrente, un employé actif et un rôle compatible. La manutention accepte préparateurs et managers ; une compétence explicitement requise restreint les rôles.

L’éditeur propose automatiquement un employé valide et permet de parcourir les alternatives par précédent/suivant. Toute modification du créneau ou du rôle relance le calcul. L’interface transmet `validatedAssignment=true` lors de l’enregistrement et des déplacements de tâches/manutentions ; le Java revalide sous verrou magasin et refuse une affectation devenue invalide. Les API historiques restent compatibles avec les affectations manuelles signalées dans les conflits. Ce moteur traite les créneaux fixés ; il ne décale pas encore les autres tâches pour libérer une place.

## Réordonnancement global et transits

Le moteur est écrit en Java dans `ConstrainedPlanningSolver`, sans solveur externe. Il construit les domaines employé/horaire des tâches et ordres ouverts, choisit à chaque étape l'opération ayant le moins d'options restantes, puis explore les combinaisons par retour arrière et vérification anticipée des conflits. Les durées sont conservées, les départs sont explorés par pas de 15 minutes ainsi qu'à l'horaire initial. Présences contiguës, absences, compétences, chevauchements, dépendances explicites de réapprovisionnement, deadline packing et disponibilité promise des approvisionnements sont des contraintes dures. Les opérations commencées, exécutées, closes ou hors période restent fixes. Les opérations voisines hors période servent d'ancrage spatial sans exiger de ressaisir leurs présences historiques.

Les solutions distinctes sont classées par durée totale de transit croissante, nombre de modifications puis décalage cumulé. Jusqu'à 5 propositions sont conservées. La recherche est limitée à 7 jours, 80 opérations (ancrages compris), ±240 minutes de flexibilité, 150 000 nœuds et environ 2 secondes d'exploration. Une recherche bornée n'est pas une preuve d'optimalité ; l'interface le signale. L'absence de solution ne provoque aucune affectation forcée.

- `GET /store-operations/transits?storeId=…` : durées dirigées configurées.
- `POST /store-operations/transits` : `{storeId,originId,destinationId,minutes}` ; bâtiments du magasin, 0 à 1440 minutes, un sens à la fois. L'absence d'une liaison interdit ce transit dans le solveur. L'aller et le retour sont indépendants.
- `POST /store-operations/planning/solutions` : `{storeId,start,end,flexibilityMinutes}`. Renvoie `previewId`, les solutions (affectations avant/après et transits), les avertissements et les limites de recherche. Aucun changement de planning.
- `POST /store-operations/planning/solutions/apply` : `{storeId,previewId,solution}` ; indice de solution à partir de zéro. Prévisualisation valable 15 minutes en mémoire du serveur. Sous verrou magasin, l'empreinte des contraintes est recomparée et les créneaux ne doivent pas avoir commencé. Toutes les affectations et les blocs `TRANSIT` sont enregistrés dans une même transaction. `PlanningRevision` conserve acteur, empreinte et solution appliquée. Réessayer la même validation est idempotent ; choisir une autre solution après application nécessite une nouvelle recherche.
- `GET /store-operations/employees/{id}/position?storeId=…` : bâtiment déclaré ou bâtiment de la présence en cours, disponibilité et état de transit.
- `POST /store-operations/employees/{id}/position` : `{storeId,buildingId}`. Première déclaration de position ; les changements suivants calculent une arrivée avec la durée dirigée configurée. Un déplacement en cours ne peut pas être court-circuité. Le planning prévisionnel ne déplace jamais cette position réelle automatiquement.

Dans Storepilot : configuration dans **Lieux → Déplacements entre bâtiments**, graphe SVG avec flèches valuées et édition de chaque sens ; comparaison dans **Équipe → Réordonnancer et comparer** ; déclaration de déplacement via **Équipe → Position / transit**. Les présences et tâches ont un champ bâtiment. Les ordres déduisent leurs bâtiments de leurs lieux opérationnels ; un bâtiment inconnu ne devient pas un trajet nul.

Pour préserver l'exécution des installations existantes sans configuration spatiale, le premier transit enregistré active le contrôle de position au démarrage des manutentions. Il contrôle aussi l'arrivée lors du rangement d'un réapprovisionnement. Le préparateur doit correspondre à l'affectation prévue et son arrivée doit être effective. Les positions sont déclaratives ; aucun suivi GPS n'est supposé. Les heures et bâtiments du jeu de démonstration existant ne sont pas inventés ou écrasés pour produire artificiellement une solution.

### Données de démonstration du réordonnancement

`DemoPlanningInitializer` complète le magasin LYO-042, sans supprimer les données existantes :

- Magasin `LYO-042-SHOP`, entrepôt principal `LYO-042-WH`, annexe `LYO-042-ANNEXE`. L'annexe possède un plan connecté, des emplacements pour tous les produits et deux buffers. Les identifiants des éléments de topologie de l'annexe portent le préfixe `AN_`, car ces codes sont uniques dans la base.
- Six transits dirigés : magasin → principal 8 min, retour 9 min ; magasin → annexe 12 min, retour 14 min ; principal → annexe 5 min, retour 6 min.
- Alice Moreau (`DEMO-P01`) et Karim Petit (`DEMO-P02`), préparateurs dédiés au scénario. Présences de 08:00 à 18:00, respectivement au magasin et à l'entrepôt principal ; pauses de 13:30 à 14:00 et de 14:00 à 14:30.
- Quatre tâches de contrôle/inventaire sur les trois bâtiments, avec des affectations croisées pour illustrer le regroupement des interventions.
- `OPT-ANN-CAM` : déchargement à l'annexe de 11:45 à 12:15 ; `OPT-ANN-RAY` : annexe → magasin de 12:30 à 13:15, avec le camion comme prédécesseur. Quatre produits par ordre, dont un au poids, quantités différentes, approvisionnements prévisionnels et stock physique de départ dans l'annexe.

Au premier chargement, le scénario se situe **le lendemain**. Sa date est ensuite conservée, ainsi que les modifications des transits, employés, créneaux et stocks ; les rechargements ne décalent pas les dates et ne rechargent pas les quantités. Les horaires des employés existants ne sont pas remplacés. Le Nutella du scénario de disponibilité est exclu des nouveaux exemples de stock et de réapprovisionnement pour conserver ses hypothèses.

Pour essayer : ouvrir **Équipe → Réordonnancer et comparer**, choisir la date des présences de démonstration, **08:00–18:00**, décalage maximal **60 minutes**. La recherche propose jusqu'à cinq solutions ; les comparer avec Précédente/Suivante. La prévisualisation ne modifie aucune affectation. Les six trajets sont visibles dans **Lieux → Déplacements entre bâtiments**.

## Supervision de la manutention

La tuile **Manutention** propose un mode **Supervision**, destiné au manager : il présente les situations qui demandent une décision, sans jamais guider le picking, le scan ou le dépôt des bacs. Toute l'analyse est calculée en Java (`engine/supervision`) ; Storepilot n'affiche que les résultats et appelle les actions proposées. La période est celle des pastilles de la tuile (Aujourd'hui, Demain, Semaine, calendrier) ; les ordres en retard sont inclus tant que la période contient l'instant présent.

- `GET /store-operations/supervision?storeId=…&start=…&end=…` : anomalies, compteurs, valeurs des filtres et hypothèses de l'analyse (31 jours maximum, 300 ordres ouverts analysés au plus). Un manager est limité à son magasin ; un administrateur choisit le magasin.
- `POST /store-operations/supervision/apply` : `{storeId, anomalyId, proposalId, basis, start, end}`. Sous le verrou du magasin, le serveur compare l'ordre à l'état (`basis`) sur lequel la proposition a été calculée, recalcule l'anomalie avec les mêmes moteurs et vérifie que la proposition est toujours offerte. Sinon il répond **409** avec les raisons précises (créneau déplacé, nouvel employé affecté, absence apparue, buffer désactivé ou plein…) et rien n'est écrit. Les affectations passent par `POST /store-operations/handling/{id}/schedule` avec `validatedAssignment=true` ; les changements de destination ne concernent que les regroupements et mises en sécurité non commencés.

Chaque anomalie a une identité stable (`type:cause`), un type, une **certitude** — blocage avéré, risque prévisionnel ou information manquante —, une gravité (critique, élevée, modérée, faible), une explication en français, son échéance menacée, les ordres, produits, buffers, bâtiments et employé concernés, les propositions et les actions disponibles, ainsi que le contexte transmis aux vues réutilisées (période, bâtiments, comptes de stock, buffer).

| Type | Détection | Certitude |
|---|---|---|
| Retard | Ordre ouvert, planifié, en cours ou en échec après la fin de son créneau, sauf exécutions toutes terminées (même règle que l'événement de retard). | Avéré ; critique au-delà d'une heure, en échec, échéance client dépassée ou ordres bloqués en aval. |
| Sans employé | Ordre ouvert ou planifié, non commencé, sans affectation. | Avéré si le créneau a commencé, sinon risque. |
| Affectation | `PlanningAssignmentService` : compte inactif, compétence, présence partielle, absence, tâche, transit ou autre manutention concurrente. Regroupée par cause (une absence couvrant trois ordres = une carte). | Risque ou avéré ; **information manquante** si aucun horaire n'est saisi ce jour-là. |
| Position / transit | Uniquement quand des trajets sont configurés : position déclarée (opérations imminentes) ou bâtiment de l'opération précédente / de la présence, puis durée dirigée vers le bâtiment de l'opération ; trajet absent, durée de l'ordre inférieure au trajet entrepôt → magasin. | Avéré si la position réelle rend l'arrivée impossible ; position ou bâtiment inconnu = information manquante. |
| Stock | Quantités restant à prélever, par emplacement, dans l'ordre chronologique des ordres (un ordre antérieur consomme d'abord) : solde physique, entrées prévues avant le début, approvisionnements en retard. | Manque actuel (avéré), manque prévu ou approvisionnement en retard (risque), emplacement sans aucun inventaire = stock **inconnu**, jamais présenté comme nul. |
| Dépendance | Réachalandage après son déchargement camion (`predecessor`), collecte → packing → regroupement partageant un lot ; mise en rayon en attente quand le picking entrepôt n'est pas terminé à mi-créneau. Un préalable lui-même en retard est rattaché à sa carte de retard comme conséquence. | Avéré (préalable annulé, en échec ou début atteint) ou risque. |
| Buffer | Buffer de regroupement, de mise en sécurité ou bac de réapprovisionnement à déposer : désactivé, sans place pour la température, plein **actuellement**. Aucune libération future n'est supposée. Buffer non renseigné = information manquante. | Avéré si le dépôt est dû, sinon risque. |

Le réachalandage est suivi en deux étapes : le stock est vérifié pour le picking dans l'entrepôt (les quantités déjà prélevées ne sont plus contrôlées), la position attendue est l'entrepôt puis le magasin une fois le picking complet, et la carte de retard indique l'étape en cours.

Propositions : affectations valides du même créneau, ou replanification au prochain quart d'heure pour un ordre en retard non commencé (moteur d'affectation, filtré par la faisabilité du trajet quand le contrôle des positions est actif) ; destinations compatibles d'après l'occupation actuelle, bâtiment courant d'abord. Une proposition se valide en deux clics (Valider… puis Confirmer).

Les cartes ont une hauteur propre, en colonnes selon la largeur, avec compteurs par certitude et filtres par gravité, type de problème, type d'ordre, bâtiment et employé. Les actions rouvrent les vues existantes dans leur contexte : ordre et opération préalable (fenêtre de l'ordre), comparaison des solutions du moteur de réordonnancement (période pré-remplie), position / transit de l'employé, détail de stock des produits concernés (bâtiment de l'anomalie), tuile Buffers ouverte sur le bâtiment, planning de l'équipe.

L'analyse charge les données du magasin par requêtes groupées (ordres, exécutions, dépendances, lignes, soldes par emplacement, comptes des seuls produits concernés, occupation des buffers, planning de la fenêtre) : pas de requête par ordre ni de chargement du catalogue produit.

### Données de démonstration de la supervision

`DemoSupervisionInitializer` complète LYO-042 une seule fois (dès qu'un ordre `SUP-` existe, rien n'est recréé ni décalé), à partir de l'heure du premier chargement. Il crée Sofia Lambert, Hugo Martin et Léa Garnier (`DEMO-S01` à `DEMO-S03`), le buffer désactivé `SUP-BUF-HS` et dix ordres : `SUP-CAM-RETARD` (camion en retard qui bloque `SUP-RAY-ATTENTE`), `SUP-RAY-RETARD`, `SUP-RAY-SANS`, `SUP-RAY-ABSENT`, `SUP-RAY-NOHOR`, `SUP-RAY-TRANSIT`, `SUP-RAY-MANQUE` (picking en cours en manque), `SUP-RAY-PREVU` (manque prévu et stock inconnu) et `SUP-GRP-BUFFER`. Le stock n'est écrit que sur des emplacements d'entrepôt sans historique : les scénarios Nutella, réapprovisionnement, buffers et planning gardent leurs hypothèses.

## Supervision de l'activité

La tuile **Supervision** (bibliothèque, icône écran de contrôle ; elle n'est pas ajoutée d'office aux tableaux de bord) donne au manager une lecture transversale de l'activité du magasin : commandes et engagements, stock dans le temps, réapprovisionnements et réachalandages, manutention, équipe et planning, buffers et bacs, expéditions. Toute l'analyse — diagnostics, liens de cause à effet, agrégats, projections, priorités — est calculée en Java (`engine/supervision/ActivitySupervisionService`) ; Storepilot ne fait qu'afficher et appeler les actions.

Elle **compose la supervision de la manutention** au lieu de la concurrencer : `HandlingSupervisionService.analyze` est appelé sur la même fenêtre et ses anomalies deviennent des situations de **même identité** (`LATE:20`, `ASSIGNMENT:…`), avec les mêmes propositions et le même état ; une anomalie de manutention absorbée par une situation plus large (déficit de capacité, buffer saturé) y est rattachée et n'apparaît pas deux fois. La disponibilité séquentielle par emplacement est partagée par les deux vues (`LocationAvailability`) : un ordre de manutention puis une commande à préparer consomment le même solde, dans l'ordre chronologique.

- `GET /store-operations/activity-supervision?storeId=…&horizonHours=…` : rapport complet (horizon de 1 h à l'horizon maximal configuré ; **400** au-delà). Un manager est limité à son magasin ; un administrateur choisit le magasin.
- `GET /store-operations/activity-supervision/orders/{id}?storeId=…` : avancement d'une commande, chargé à la demande (lignes, quantités préparées, bacs, préparations, parties à préparer) ; **404** pour une commande d'un autre magasin.
- Les propositions s'appliquent par `POST /store-operations/supervision/apply` avec la fenêtre `handling.start`/`handling.end` du rapport : mêmes revalidations et même réponse **409** que la supervision de la manutention.

Le rapport contient la date de calcul, l'horizon et les seuils appliqués, les indicateurs, les séries des graphiques, les situations priorisées, les valeurs des filtres, l'état de chaque source de données (`OK`, `PARTIAL`, `STALE`, `UNAVAILABLE`, `NO_DATA`), les limites de l'analyse et les anomalies de manutention composées.

### Situations

Chaque situation a une identité stable, un ou plusieurs domaines, une **nature** — blocage avéré, risque prévisionnel, information manquante, donnée périmée —, une gravité, un rang, la cause, les conséquences, les incertitudes, l'échéance menacée, les commandes concernées (dont en retard), les opérations, produits, buffers, bâtiments et employé, les propositions, les actions, les situations liées et un graphe de dépendances. Les états normaux (commande dans les temps, préparée en attente normale de départ, buffer non saturé) ne produisent aucune situation.

| Situation | Détection | Nature |
|---|---|---|
| `OVERDUE` | Commandes d'échéance dépassée sans autre cause identifiée. | Avéré |
| `PLANNING` | Commandes sans préparation planifiée à moins de `planningLeadMinutes` de leur échéance. | Risque ; avéré si l'échéance est passée |
| `ROUTING` | Commandes dont les produits n'ont pas pu être répartis. | Avéré |
| `LATE_PLAN:<ordre>` | Préparation planifiée après l'échéance de ses commandes. | Risque ; avéré si l'échéance est passée |
| `STOCK_SHORTAGE:<bâtiment>` | Quantité insuffisante à l'emplacement de prélèvement à l'échéance d'une commande non planifiée, après les consommations antérieures. | Risque |
| `STOCK_UNKNOWN:<bâtiment>` | Emplacement de prélèvement inconnu, hors magasin ou sans aucun inventaire : jamais présenté comme un stock nul. | Information manquante |
| `SUPPLY_LATE` / `DEFINITION:<id>` | Approvisionnement en retard, ou réapprovisionnement sans ordre de manutention, sans lequel la quantité ne couvre pas le besoin. | Avéré / risque |
| `CAPACITY:<tranche>` | Charge planifiée (manutentions affectées et sans employé, tâches, transits) supérieure aux présences saisies, absences déduites. | Risque ; avéré sur la tranche en cours |
| `HOURS_MISSING` | Employés affectés sans horaire saisi ce jour-là : leur charge est « non suivie », jamais comptée comme un déficit. | Information manquante |
| `BUFFER_LOAD:<buffer>` | Buffer plein ou au-delà de `bufferSaturationPercent` **et** recevant des dépôts planifiés. | Risque ; avéré si un dépôt dû est bloqué |
| `SHIPPING_LATE` / `SHIPPING_DEPOSIT` | Commandes préparées non expédiées après leur échéance (avec immobilisation des bacs au-delà de `immobilizationHours`) ; bacs préparés hors buffer à l'approche du départ. | Avéré / risque |
| `DEADLINE_MISSING` | Commandes ouvertes sans échéance. | Information manquante |
| `STOCK_STALE` | États de stock non recalculés depuis plus de `staleStockMinutes`. | Donnée périmée |
| `HANDLING_UNAVAILABLE` | La supervision de la manutention n'a pas pu être calculée : l'analyse est partielle et le signale. | Donnée périmée |
| anomalies de manutention | Retard, sans employé, affectation, transit, stock, dépendance, buffer (voir la section précédente). | Celle de l'anomalie |

**Liens de cause à effet.** Une commande est rattachée à une situation par un lien **établi** (lot préparé par l'ordre en cause, approvisionnement sans lequel la quantité ne suffit pas), **estimé** (même produit et même bâtiment, sans preuve d'affectation) ou une **concomitance** (même tranche, sans lien démontré), toujours affiché comme tel. L'indicateur « Commandes menacées » compte chaque commande **une seule fois**, même touchée par plusieurs situations, et précise combien le sont par plusieurs.

**Priorité.** Tri par gravité, puis échéance, nombre de commandes concernées, existence d'une solution, identité. La gravité vient de la nature : avéré → critique si une commande est en retard, si au moins `criticalOrderCount` commandes sont concernées, sinon élevée ; risque → élevée sous `urgentMinutes` ou au-delà de `criticalOrderCount` commandes, modérée dans l'horizon, faible au-delà ; information manquante → modérée sous deux fois `urgentMinutes`, sinon faible ; jamais inférieure à la gravité de l'anomalie de manutention composée. Chaque situation porte en clair les raisons de son rang.

### Indicateurs et graphiques

| Indicateur | Définition |
|---|---|
| Commandes menacées | Commandes non expédiées, d'échéance dépassée ou dans l'horizon, compromises par au moins un problème avéré ou un risque ; comptées une fois. |
| Échéances dépassées | Échéance client passée sans expédition complète (les plus anciennes que `overdueLookbackHours` sont comptées à part). |
| Opérations bloquées | Ordres de manutention visés par un problème avéré de la supervision de la manutention. |
| Déficit de capacité | Somme, sur l'horizon, de la charge planifiée au-delà des présences, par tranche de `capacitySlotMinutes`. |
| Stock à risque | Comptes de stock indisponibles ou en tension d'après les alertes de stock. |
| Buffers saturés | Buffers actifs dont une température est pleine maintenant. |
| Prêtes au départ | Commandes préparées, bacs déposés, en attente de la confirmation du départ. |

Un indicateur sélectionné filtre les situations qui l'expliquent ; une donnée indisponible s'affiche « — » et n'est jamais remplacée par zéro. Graphiques : **frise des échéances** (dépassées puis par tranche : menacée, à vérifier, prête, dans les temps), **charge et capacité** par tranche (planifié, distinct du réalisé ; charge non suivie hachurée), **commandes par étape** (étapes comptées indépendamment, pas un entonnoir, avec ancienneté et retard maximal), **occupation des buffers** (constatée, avec le seuil), **disponibilité du stock** (courbe dans le temps d'un produit au clic, issue de la projection de stock existante) et **graphe de dépendances** de chaque situation. Chacun a un titre, une question, une légende, des unités, une vue tableau, des valeurs au survol et au focus clavier ; un clic filtre les situations ou ouvre la vue concernée.

### Lecture, filtres et actions

Premier niveau : indicateurs et graphiques ; second niveau : situations priorisées en cartes compactes sur plusieurs colonnes (onglets en taille normale, vue côte à côte en plein écran). Filtres : horizon, nature, gravité, domaine, bâtiments (multiple), sélection issue d'un indicateur ou d'un graphique. Les actions rouvrent les vues existantes **par-dessus la tuile, sans ajouter de tuile au tableau de bord enregistré**, avec la sélection transmise : ordre de manutention, commande et ses parties, expédition sur la commande, réachalandage pré-rempli (bâtiments source et destination, produits et quantités manquantes), ordre de manutention d'un réapprovisionnement, stock des produits, buffers du bâtiment, planning, comparaison des solutions, règles du magasin, diagnostic de manutention partagé. Aucune décision n'est appliquée automatiquement : une proposition se valide en deux clics, le serveur la revalide ; si la situation a changé, la raison est affichée avec **Actualiser la situation**. L'analyse se rafraîchit chaque minute et après toute modification faite dans l'application ; l'âge des résultats est affiché.

### Seuils par magasin

Les seuils sont des règles de la catégorie **SUPERVISION** (type `ActivitySupervision`), éditées dans **Règles → Règles de supervision** avec le mécanisme de configuration existant (règle de plus haute priorité du magasin). Une valeur absente ou hors bornes reprend le défaut, avec un avertissement dans les limites de l'analyse.

| Seuil | Défaut | Bornes |
|---|---|---|
| `horizonHours` — horizon de prévision | 24 h | 1–168 |
| `maxHorizonHours` — horizon maximal | 72 h | 1–168 |
| `overdueLookbackHours` — ancienneté des échéances dépassées analysées | 168 h | 1–720 |
| `planningLeadMinutes` — alerte de préparation non planifiée | 240 min | 15–2880 |
| `shippingLeadMinutes` — alerte avant départ | 60 min | 5–1440 |
| `urgentMinutes` — échéance urgente | 60 min | 5–1440 |
| `criticalOrderCount` — commandes rendant un problème critique | 3 | 1–1000 |
| `bufferSaturationPercent` — seuil de saturation | 90 % | 50–100 |
| `immobilizationHours` — immobilisation des bacs après échéance | 12 h | 1–168 |
| `capacitySlotMinutes` — tranche de charge et capacité | 60 min | 15–240 |
| `staleStockMinutes` — diagnostic de stock périmé | 15 min | 1–1440 |

### Volumétrie

L'analyse charge par requêtes groupées les commandes de l'horizon (400 au plus, les plus urgentes ; le dépassement est signalé), leurs parties, lignes, lots, prélèvements, bacs et expéditions, le planning de la fenêtre, les approvisionnements et les soldes des seuls emplacements et produits concernés : pas de requête par commande ni de chargement du catalogue. Les détails (avancement d'une commande, courbe de stock) sont chargés à la demande.

### Données de démonstration de l'activité

`DemoActivityInitializer` complète LYO-042 une seule fois (dès que la commande `ACT-CMD-0101` existe, rien n'est recréé), après la démonstration de la supervision de la manutention, sur des produits ambiants sans historique autre que leur stock d'ouverture : les scénarios de stock existants (Nutella, réapprovisionnement, buffers, planning) gardent leurs hypothèses. Il crée Nina Robert (`DEMO-A01`), le buffer `ACT-BUF-QUAI` et les scénarios : retard d'arrivage (`ACT-CAM-RETARD` qui bloque `ACT-CMD-0201`/`0202`), réachalandage bloqué (`ACT-RAY-BLOQUE`, sans employé), tension de stock (réservation `ACT-RESA-TENSION`, `ACT-CMD-0401`/`0402`, réapprovisionnement `ACT-APP-0001` sans ordre de manutention), manque de capacité (`ACT-COL-SOIR` et `ACT-RAY-SOIR-1` à `3` sans employé), préparation planifiée après l'échéance (`ACT-CMD-0104`), buffer saturé (`ACT-BUF-QUAI`, dépôt de `ACT-GRP-QUAI` à venir), expédition menacée (`ACT-CMD-0602` préparée et non expédiée, `ACT-CMD-0603` hors buffer), commande non répartie (`ACT-CMD-0801`), commande sans préparation planifiée (`ACT-CMD-0901`) et une commande normale (`ACT-CMD-0902`).
