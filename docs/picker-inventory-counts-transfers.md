# Inventaires, transferts et écarts de livraison

## Comptage

Une mission d'inventaire porte sur une baie ou une cellule. Le démarrage crée une session avec une référence immuable, des lignes par produit/lot/série et une politique figée. Le picker distingue explicitement quantité comptée (dont zéro), inconnue et non comptée. Chaque saisie conserve son observation, l'auteur et les preuves de scan/mesure. Le comptage ne modifie pas le stock.

Le responsable paramètre les tolérances absolue/relative, le nombre de passages et l'obligation éventuelle d'un autre compteur. Les mouvements physiques intercalaires servent à comparer les observations à date cohérente ; les écritures de reclassification ne deviennent pas une consommation. Un gel de cellule est toujours ciblé ; la politique de baie peut autoriser les mouvements avec rapprochement. Annulation ou application libère le périmètre.

Les passages contradictoires restent distincts. Le responsable choisit le passage retenu, justifie l'approbation et, pour un bac commercial, confirme les allocations conservées. Un manque crée une résolution de picking récupérable. Un surplus reste du stock libre, sans augmenter une commande. L'application vérifie de nouveau la base approuvée et écrit des corrections append-only. Toute modification de stock depuis l'approbation demande une nouvelle comparaison. Une relance de la même commande retourne son reçu.

Une observation de bac physiquement présent ailleurs est conservée mais ne déplace pas silencieusement le bac : annuler la session, corriger son adresse dans Buffers puis relancer un inventaire.

## Transferts

Depuis Produits et stocks, sélectionner les produits puis Transférer le stock. Les quantités et adresses se précisent séparément par produit. Une mission utilise les phases prélèvement, transit et réception du moteur de réapprovisionnement. Une réception partielle conserve son reliquat en transit. Le geste unique est réservé à un déplacement dans le même bâtiment. Le déplacement d'un support entier reste accessible par drag/drop dans Buffers ; il transporte les segments et allocations sans réécrire les adresses des mouvements historiques.

`POST /stock-transfers` accepte une clé de requête et le mode `MISSION` ou `SINGLE_GESTURE`. L'opération apparaît sous Transfert de stock dans Manutention. Le total physique magasin et les identités lot/série sont conservés.

## Écarts de réception

Le déchargement conserve les quantités attendues originales. Un excédent ou article inattendu crée un dossier avec quantité, document et commentaire, sans crédit de stock immédiat. Le responsable accepte avec une adresse et les preuves lot/date/série requises, ou refuse avec motif. L'acceptation crée une ligne d'arrivée distincte ; le refus n'écrit aucun mouvement. Le picker peut signaler l'écart depuis sa mission ; la décision reste habilitée côté Java.

## Recette logicielle

Les preuves du lot T36–T40 se trouvent dans `docs/picker-suivi/preuves-t36-t40`. Les tests automatisés couvrent replay, droits, changements de base, mouvements intercalaires, lots, unités, allocations commerciales, surplus et transferts partiels. L'émulateur Android et Storepilot vérifient un cycle réel de comptage vide puis approbation/application. La validation du matériel réel reste distincte.
