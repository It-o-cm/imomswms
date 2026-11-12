# Matériel réutilisable : filtres, dépôt et consultation

La liste Bacs et chariots propose un lieu (bâtiment, buffer, dans un chariot, emplacement non renseigné), un type précis et une disponibilité libre/pas libre. Ces filtres sont appliqués côté Java avant la pagination. Un support affecté à un compartiment est occupé, même s'il n'a pas encore de produits. Un support bloqué ou retiré n'est pas libre. Les lieux non suivis ne sont pas déduits du bâtiment prévu de la mission : ils restent explicitement non renseignés.

Dans la modale chariot, « Retirer du chariot » propose le dépôt dans un buffer. Le dépôt et la libération du compartiment sont une transaction unique, avec contrôle de révision, clé d'idempotence et contrôles de magasin/capacité/température. Un échec conserve le compartiment et les produits. Aucun bac ou produit n'est supprimé. Un bac déjà déposé peut aussi être désaffecté par l'API existante ; un bac vide peut être détaché sans dépôt.

La configuration de la géométrie est repliée par défaut. Le chariot courant n'a pas d'ascenseur interne vertical ; un chariot avec beaucoup de rangées peut nécessiter le défilement de la modale entière sur un petit écran.

Le choix d'une commande pour un emballage utilise `/parcels/candidates` : recherche par numéro partiel ou nom/prénom du client, pages de 24, aperçu des produits restant à conditionner. Les commandes annulées ou dont tous les produits préparés sont déjà affectés sont exclues. Les erreurs de chargement proposent une reprise et ne sont pas présentées comme une liste vide.

Le filtre qualité « Tous » omet désormais `state`; le serveur accepte aussi une valeur vide pour compatibilité et conserve la validation des états réellement inconnus.

Vérifications du 30/10/2026 : `docs/picker-suivi/preuves-supports-ergonomie-20261030/verification.json`.

## Accès H2 en développement

Les logs ont identifié `ClosedByInterruptException` puis `ClosedChannelException`. Le profil dev utilise par défaut `jdbc:h2:async:./data/imomswms` pour éviter l'accès fichier interruptible standard. Le fichier est conservé ; `DATABASE_URL` explicite reste prioritaire. Une sauvegarde a été faite avant la relance. Un test isolé vérifie la lecture du même fichier, une requête interrompue et la conservation des lignes. C'est le contournement `async:` documenté par H2, pas une garantie universelle contre toute panne ; H2 recommande le mode client-serveur pour isoler complètement les interruptions des clients : https://h2database.github.io/html/features.html#connection_modes
