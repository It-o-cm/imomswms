# Fiche produit et captures physiques

Les informations de base et les photos restent celles du catalogue. Les consignes, codes complémentaires et conditionnements appartiennent au magasin. Accès ADMIN/MANAGER/PICKER ; seuls ADMIN/MANAGER du magasin modifient la configuration. Une révision optimiste protège chaque modification ; les consignes conservent leur auteur et leur version.

Storepilot ouvre la même modale depuis la liste Produits et stocks, un détail de stock et le panneau de substitutions. Les substitutions restent définies dans leur modale. Une image absente affiche un repli explicite, jamais l’image d’un autre produit. Le picker ouvre la fiche depuis le menu du produit et depuis une ligne de réapprovisionnement.

## HTTP

Préfixe `/product-information` :

- GET `/{id}?storeId=…` : fiche commune, `revision`, droits d’édition.
- PUT `/{id}/instructions` : `storeId`, `expectedRevision`, `text` ; crée une version.
- PUT `/{id}/identifiers` : mêmes champs de portée/révision ; `kind` GTIN/INTERNAL/SUPPLIER, `value`, `context` pour SUPPLIER, `packaging` (BASE par défaut), `identifierId` pour modifier.
- POST `/{id}/identifiers/{identifierId}/retire` : retrait réversible en ajoutant de nouveau le code sur son produit.
- PUT `/{id}/packagings` : `code`, `label`, `level`, `parentCode`, `unit`, `factor` exact. BASE est réservé ; UNIT/PACK/CASE/PALLET sont les niveaux disponibles.
- POST `/{id}/convert` : `storeId`, `packaging`, `quantity` décimale ; retourne quantité et facteur sous forme de chaînes décimales exactes.
- POST `/resolve` : `storeId`, `scanId` UUID, `code`, éventuellement `kind`, `context`, `productId` de mission ; `identifierId` confirme une ambiguïté.
- POST `/{id}/measure` : `storeId`, `measurement` ; vérifie brut/net/tare/unité/origine/appareil et produit un reçu physique réutilisable par les confirmations. Aucun mouvement de stock.

Exemple : `measurement={"gross":"400","tare":"25","unit":"G","origin":"MANUAL"}` retourne `quantity="0.375", unit="KG"` pour un produit au poids. SCALE exige `deviceId`. Un net seul se transmet avec une tare nulle. Les unités acceptées sont PCS, KG/G et L/ML, dans la dimension du produit. Les pièces restent entières, les kg/L ont trois décimales au maximum.

## Confirmation

Les commandes opérateur existantes PICK, PICK_SUPPLIES et RECEIVE_SUPPLIES acceptent `scanId` et `measurement`. Pour les approvisionnements, une capture concerne une seule ligne de réception. Le serveur contrôle produit, quantité, révision et consommation du reçu dans la même transaction que le geste métier. Les lignes regroupées d’un prélèvement peuvent consommer des portions d’un reçu quantifié ; leur somme ne dépasse jamais le total vérifié. Le reçu de commande existant absorbe les reprises réseau. Un échec métier restitue la portion réservée.

La saisie manuelle existante reste utilisable. Une quantité saisie à la main invalide la capture précédente dans le terminal ; une mesure explicitement vérifiée conserve son origine. Aucun poids n’est inféré du libellé commercial ou du poids de référence. Les adaptateurs physiques de balance restent l’objet de T55.

La décision GS1 et ses limites sont dans [D02](picker-suivi/D02-GS1-v1.md). La migration additive V4 et les scénarios de démo ne modifient pas les quantités de stock existantes. Le seed complète les produits démo avec consignes, codes SKU-/BOX- et cartons (12 pièces ou 5 kg/L), sans écraser la configuration du magasin.
