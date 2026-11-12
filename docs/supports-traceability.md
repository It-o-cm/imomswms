# Supports, identité fabricant et segmentation — T26 à T30

## Chariots et compartiments

La tuile Buffers ouvre à la demande le catalogue des supports, avec recherche paginée, définition des types et création des supports physiques. Un chariot reçoit une grille plate de compartiments. Depuis une mission, le responsable ou son préparateur choisit le chariot, un compartiment libre, le support de la mission, puis la commande ou la destination. Aucun identifiant technique n’est demandé à l’utilisateur.

L’association porte sur les identités de mission, commande et support. Elle ne recopie pas le nom du préparateur : une transmission acceptée conserve donc la grille. Un chariot contenant plusieurs missions ne peut pas être transmis partiellement ; ses supports doivent d’abord être déposés. Le verrou magasin, la révision du compartiment et l’unicité de son occupant empêchent les doubles affectations. Une modification de grille est refusée tant qu’un compartiment est affecté. Les capacités cumulées connues du chariot sont vérifiées en plus des capacités individuelles des supports.

## Support plein

Déclarer un support plein ne le livre pas et ne supprime aucun contenu. Le support source reste dans la mission, avec son dépôt à effectuer. Son remplacement est distinct et relié à l’original. Le préparateur peut le choisir immédiatement ou après la déclaration. La compatibilité thermique et alimentaire, le poids, le volume connu et la taille des produits unitaires sont contrôlés en Java. Les dimensions inconnues restent signalées ; une estimation incomplète n’est pas présentée comme une garantie. Les futurs prélèvements et rapports ne peuvent augmenter le contenu d’un support plein. Une correction peut retirer un produit.

L’estimation indique le nombre minimal de supports si le reliquat n’entre pas dans un seul. Elle ne prétend pas résoudre un problème de placement géométrique des produits : les dimensions tridimensionnelles ne sont pas présentes dans le catalogue.

## Buffers

Les zones et armoires, leur séparateur redimensionnable, le glisser-déposer et la modale commune de contenu sont conservés. Les objets portent leur type réel : bac, carton, palette ou chariot. La liste des supports se charge uniquement à son ouverture. Les cellules occupées restent protégées côté serveur. Une palette ou un chariot se dépose dans un buffer, hors cellule d’armoire. La grille du chariot permet d’ouvrir le contenu de son support occupant. Un support déposé peut être libéré du compartiment sans changer sa position en buffer.

## Lots, séries et dates

Un `ManufacturerLot` est une identité de marchandise, indépendante des `Lot` de préparation. Son code est unique par produit et magasin. Fabrication, DLC et DDM sont facultatives ; un code déjà connu avec d’autres dates déclenche un conflit. Une série est unique par produit et magasin et liée à une référence d’attribution immuable. Le rejeu de cette attribution est sans effet ; une autre attribution est refusée.

La fiche produit propose la définition des lots, les exigences par contexte (réception, picking, expédition, retour) et la lecture des segments. Aucune exigence n’est activée sur l’historique par défaut. T31 raccorde ces exigences aux gestes physiques ; T32 ajoute leur effet sur les promesses et l’allocation FEFO.

## Un seul journal de stock

`StockSegment` qualifie compte, emplacement, support, lot, série et qualité. Son solde est exclusivement la somme des `StockMovement` : il n’existe pas de quantité mutable concurrente du journal temporel.

La migration V7 ajoute un segment non évalué par compte/emplacement historique, rattache chaque écriture et conserve ses quantités, dates et références. Elle ne fabrique ni lot ni série. Les réservations et les identités historiques restent inchangées.

Une qualification produit deux écritures opposées à la même date, avec motif et reçu. Le total physique reste identique. Un mouvement négatif peut consommer plusieurs segments et son rejeu vérifie l’ensemble de l’opération. Une correction antidatée ne peut rendre négatif un segment dans son historique ultérieur. Un prélèvement physiquement constaté malgré un inventaire importé incomplet conserve toutefois l’écart dans le segment non qualifié ; le système n’invente pas un lot pour le couvrir.

Le changement de support n’est pas une qualification : il doit passer par un mouvement physique. T31 propage les identités dans ces mouvements ; T32/T33 appliquent péremption, FEFO et interdictions qualité à la disponibilité et aux opérations.
