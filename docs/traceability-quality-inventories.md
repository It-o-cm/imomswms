# Traçabilité, qualité et inventaires ciblés

Le stock physique est conservé par compte, adresse et segment. Le prélèvement commercial produit deux mouvements opposés : sortie du rayon et entrée dans une adresse interne de bac. Les lignes `PickedItem` restent la présentation commerciale de ce contenu ; `PickingToteLocation.traceOnly` empêche de le compter deux fois dans les armoires. L’expédition débite la marchandise détenue, sans nouveau débit du rayon.

## Identité et confirmations

Un prélèvement peut préciser `stockSegmentId`, `manufacturerLotId`, `serialId` ou un `scanId` confirmé par la session. Les rapports groupés portent ces attributs sur chaque `ToteItemReportDTO`. Une source déjà en bac conserve son identité : le reconditionnement n’est pas une nouvelle déclaration de lot. Les réceptions et prélèvements d’approvisionnement portent les mêmes attributs sur `ReceiptLine`, avec la mesure nette éventuelle.

Sans choix explicite, Java alloue les segments autorisés suivant FIFO ou FEFO. Une série représente une unité. Un scan résolu est contrôlé contre le produit et sa session, et sa quantité consommable est bornée par `ProductInformationService`. Les confirmations physiques et leurs mouvements sont dans la même transaction. Les corrections utilisent des mouvements compensatoires ; elles ne réécrivent pas les anciens mouvements.

Les préparations historiques sont rapprochées de leur emplacement source connu lors du passage en V8. Cette localisation de la détention actuelle est datée du rapprochement, pas d’un prélèvement historique supposé. Aucun lot fabricant n’est inventé. Une provenance introuvable reste signalée et exige une correction explicite avant les opérations qui la requièrent.

## Dates et disponibilité

Une politique de produit/contexte configure `allocationMethod` (`FIFO`/`FEFO`), `minimumRemainingDays` et `bestBeforeStrict`, en plus des exigences de lot, série et date. La DLC est inclusive. La DDM reste informative sauf politique stricte ou durée minimale demandée.

La projection temporelle utilise des lots disponibles entre leur entrée et leur date limite. Un flot continu exact en `BigDecimal` affecte les lots aux engagements selon ces fenêtres. Il évite d’expirer une seconde fois une quantité déjà consommée et permet de réaffecter un engagement futur à une livraison future afin de libérer une quantité utilisable aujourd’hui. Les transferts internes héritent des dates des segments source ; le transit identifié les conserve. Un transit inconnu ou bloqué reste physique, mais n’est pas promis comme un lot identifié.

Les données sans date, politique particulière ou détention commerciale gardent le calcul agrégé rapide. Les métadonnées de segments, politiques et blocages sont chargées en lots pour la page de produits, sans requête par produit. Le physique brut comprend la marchandise périmée, bloquée et déjà préparée ; la disponibilité à promettre les exclut.

## Décisions qualité

`/quality/cases` gère blocages et rappels par magasin, produit et éventuellement lot/série. Un responsable justifie la création et la levée, avec révision et clé de confirmation. La décision ne change aucune quantité. L’inclusion des lots inconnus est explicite, activée par défaut. Les supports chargés concernés reçoivent un incident de sécurité et une consigne. La qualification d’un segment déjà prélevé met à jour ses allocations et signale sa quarantaine à la mission.

`/quality/trace` retrouve positions, supports, lignes commerciales, commandes et expéditions, avec historique des mouvements. La tuile et les fiches produit/commande permettent l’accès. La recherche est bornée : jusqu’à 500 positions/préparations et 300 mouvements récents ; l’interface indique cette limite et invite à préciser lot ou série. Les attributs historiques inconnus restent affichés comme inconnus.

## Missions d’inventaire (T35)

`POST /inventories` crée une mission `InventoryOrder` pour une baie (`area`, `aisle`, `bay`) ou une cellule (`cabinetId`, `cellRow`, `cellColumn`) d’un bâtiment du magasin. L’instant `referenceAt` et les quantités de référence sont enregistrés à la création. Une cellule vide est un périmètre valide. Les autres adresses restent actives.

La mission apparaît dans Inventaires, Manutention, le planning et la liste opérateur. L’affectation utilise le planning existant et le bâtiment de début de mission. Une affectation fournie à la création est contrôlée contre horaires, absences et tâches. Le mode aveugle masque les quantités de référence aux préparateurs. Les résultats de comptage, recomptages et corrections font l’objet de T36–T38 ; créer cette mission ne corrige pas le stock.

## Démonstration et exploitation

V8 ajoute la détention segmentée, les allocations d’approvisionnement, dossiers qualité, reçus de décisions et cibles d’inventaire. V1–V7 restent immuables. `DemoTraceabilityInitializer` introduit explicitement trois identités de démonstration par qualification à total nul, un rappel ciblé et `INV-DEMO-01`. Le seed est idempotent et suit l’activation générale des données de démonstration.
