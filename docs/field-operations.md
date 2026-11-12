# Incidents, arbitrages et état des missions (T16–T20)

Le dossier terrain porte le signalement, son auteur, le magasin, la mission, le produit, l’adresse observée et, si disponible, le manquant métier existant. Déclarer ou traiter un dossier ne modifie aucune quantité physique. Les motifs sont Manquant, Produit abîmé, Sécurité/qualité, Demande de réapprovisionnement et Autre.

Le préparateur consulte ses dossiers ; les responsables du magasin traitent tous ses dossiers. Les accès depuis Produits et stocks, Manutention, Supervision et les consignes ouvrent les mêmes identifiants de dossier. La nouvelle tuile Incidents et arbitrages propose recherche, filtres, pagination et historique. Sur Android, le menu d’une ligne permet de signaler un problème ou un besoin sans effacer la collecte en cours.

## Signaler et décider

POST `/field-operations/incidents` reçoit une référence stable de confirmation. Une reprise avec le même contenu retrouve le même dossier ; un contenu différent sous la même référence est refusé. Le contexte et les ressources sont contrôlés dans le magasin. Une décision exige la révision observée, un motif et sa propre référence : prise en charge, affectation à un responsable actif, attente, résolution, refus, réouverture ou note. La décision et la consigne adressée au préparateur sont enregistrées ensemble. La consigne contient `fieldCaseId` pour rouvrir directement le dossier.

Les photos sont ajoutées séparément, sans perdre le dossier si leur envoi échoue : JPEG/PNG réellement décodable, 2 Mo maximum, 4096 pixels de côté et 8 mégapixels, cinq photos par dossier. Les données restent dans le magasin, avec empreinte SHA-256. La lecture est authentifiée et servie avec le type réel, `nosniff` et sans cache partagé. Les listes ne chargent jamais les blobs.

## Corrections approuvées

Une demande contient une commande existante et immuable, sa mission et sa révision métier : remise au reliquat, transfert entre bacs, réouverture d’une confirmation ou rectification d’un manquant. Le responsable distinct de l’auteur approuve ou refuse avec motif. L’accord expire après trente minutes et n’accorde aucune permission générale. L’auteur applique ensuite le geste exact ; toute évolution de la mission ou des bacs impose une nouvelle demande. Deux décisions concurrentes s’arbitrent ; deux applications ne créent qu’une compensation grâce à la transaction et au reçu métier existant.

La garde des produits, les droits et les contraintes des commandes existantes restent vérifiés lors de l’application. Après transfert ou expédition, un geste de l’ancienne mission ne contourne pas la garde : les interfaces orientent vers la mission détentrice ou le retour d’expédition. Les règles de recours obligatoire à une validation font partie de la centralisation des politiques prévue en T51 ; ce dossier fournit le parcours de demande et l’autorisation limitée à un geste.

## Demande de réapprovisionnement

Un signalement de type REPLENISHMENT précise produit et quantité. Après contrôle, le responsable réutilise l’éditeur existant pour choisir le flux, les bâtiments et la période. POST `/incidents/{id}/replenishment` crée et lie atomiquement une seule définition de réapprovisionnement existante. Le produit demandé reste fixé ; la quantité peut être arbitrée. Les allocations et l’ordre de manutention restent définis dans l’éditeur habituel. Le préparateur voit le code du réapprovisionnement puis celui de sa manutention lorsqu’elle existe. Le modèle de stock dans le temps reçoit les prévisions par le service déjà utilisé par l’éditeur normal.

## Pause de mission

L’état ACTIVE/PAUSED/WAITING est distinct du statut métier et de la disponibilité personnelle de l’opérateur. PUT `/orders/{id}/state` exige révision métier et révision opérationnelle. Le reçu de changement assure les reprises réseau. La pause garde affectation, exécutions, quantités, bacs, garde et cellules. Les nouveaux gestes sont refusés tant que la mission n’a pas repris ; les reçus de gestes déjà confirmés restent consultables et rejouables sans mutation. Les entrées historiques de collecte et de réapprovisionnement contrôlent aussi l’état sous le verrou du magasin.

Manutention affiche les états suspendus, avec une requête groupée pour les missions affichées. Le picker propose la pause, l’attente et la reprise et bloque l’exécution d’une mission suspendue. L’intégration de ces états au solveur/planning est T24.

## Données et validation

Migration additive V5 : dossiers, historique, photos, approbations, états opérationnels et reçus de changement ; lien de dossier sur les consignes. Aucune migration V1–V4 n’est modifiée. Le seed complète la démo avec deux dossiers explicitement marqués Démo sans créer de manquant ni corriger le stock.

Les preuves sont dans `docs/picker-suivi/preuves-t16-t20` : tests HTTP avec transactions concurrentes, erreurs et permissions, parcours Android réel, captures Storepilot, contrat OpenAPI et conservation après redémarrage. Le test de liste mesure 1/10/30 dossiers de produits différents : six requêtes SQL constantes, sans chargement de leurs graphes. Toute requête JPAQL au-delà de 100 ms reste un point d’attention.
