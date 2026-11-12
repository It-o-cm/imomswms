# Base de données : migrations et restauration

Le schéma est désormais géré par Flyway. Hibernate valide les mappings et ne crée,
ne modifie ni ne supprime les tables au démarrage. `clean` et `repair` automatiques
sont désactivés. Les migrations appliquées sont vérifiées par checksum.

## Installation et mises à jour

- Base locale persistante par défaut : `jdbc:h2:file:./data/imomswms`.
- Surcharge : `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`.
- Les tests utilisent une base mémoire isolée ; ne jamais utiliser leur profil sur une base réelle.
- Au démarrage, Flyway applique V1 (60 tables), puis V2 (index des reçus opérateur) et V3 (sessions, événements et diagnostics ; 65 tables au total).
- Un redémarrage sans nouvelle migration ne modifie pas les données.
- Toute évolution suivante doit ajouter `V4__description.sql`, puis V5, etc. Ne jamais
  modifier une migration déjà déployée. Tester installation vide et mise à jour peuplée.
- Une ancienne base sans historique n'est adoptée en V1 qu'après comparaison de ses
  colonnes, types, ENUM, contraintes, clés, index uniques et séquences avec V1.
  Un écart bloque le démarrage : travailler sur une copie et préparer une migration
  spécifique ; ne pas forcer un baseline ni un repair pour contourner l'erreur.
- Avant adoption ou migration d'une base peuplée, un checkpoint privé est créé dans
  `data/backups` (surcharge `DATABASE_BACKUP_DIRECTORY`). Si la sauvegarde échoue,
  la migration est refusée. La mise à jour du schéma H2 peut contenir des DDL non
  transactionnels : conserver le checkpoint jusqu'à validation fonctionnelle.

H2 est fixé à 2.5.252 : la version 2.4.240 du BOM conservait des sessions fermées dans
certaines contraintes CHECK. Le correctif amont permet de conserver les contraintes,
sans réécriture SQL clandestine au démarrage. Source :
https://h2database.com/html/changelog.html (issue #4302).
Les bases H2 antérieures doivent être sauvegardées avec leur pilote d'origine avant
changement de version ; privilégier l'export SQL et la restauration dans un nouveau
fichier, puis migrer cette copie. Ne jamais ouvrir l'unique exemplaire avec un nouveau pilote.

## Maintenance hors ligne (Java 21 et Maven wrapper)

Arrêter l'application et tous les écrivains. Ne pas activer AUTO_SERVER pour contourner
le verrou de fichier. Ces commandes ne démarrent ni Quarkus ni les données de démo.

```sh
export DATABASE_URL=jdbc:h2:file:./data/imomswms
# Fournir DATABASE_USER et DATABASE_PASSWORD dans l'environnement si différents.
scripts/database.sh status
scripts/database.sh backup
scripts/database.sh migrate
```

`backup` retourne le dossier exact à conserver. Il contient `snapshot.sql` et un
manifeste : empreinte SHA-256 du fichier, nombre et empreinte des lignes de chaque
table, positions des séquences. Les fichiers sont privés (0600, dossier 0700 sur
POSIX), ignorés par Git et contiennent des données sensibles. Les conserver sur un
support protégé ; le checksum détecte une altération, ce n'est pas une signature.

## Restauration vérifiée

Restaurer uniquement une sauvegarde de confiance produite par cet outil. Utiliser un
nouveau nom de fichier, sans écraser la source :

```sh
export DATABASE_URL=jdbc:h2:file:./data/imomswms-restored
scripts/database.sh restore /chemin/exact/backup-...
scripts/database.sh status
scripts/database.sh migrate
```

Une base cible déjà remplie est refusée. Une sauvegarde altérée est refusée avant
exécution. Après import, toutes les empreintes de lignes et séquences doivent être
identiques. En cas d'échec, garder la source, jeter la cible et corriger la cause.
Après vérification, configurer DATABASE_URL vers la cible et redémarrer l'application.
Les commandes sont actuellement spécifiques à H2 ; une autre base nécessite ses
migrations et son propre protocole de sauvegarde.

## Vérification reproductible

```sh
./mvnw -Dtest=DatabaseMigrationTest,VersionedSchemaStartupTest test
```

Couvre installation, reprise peuplée, adoption contrôlée, refus d'un schéma inconnu,
modification illégale de migration, restauration, sauvegarde corrompue, cible non
vide et réouverture de fichiers après fermeture de toutes les connexions.

Les tests suivent la dernière migration résolue par Flyway : ils vérifient que
toutes les migrations en attente sont exécutées, qu'il n'en reste aucune et que
la version courante est la dernière disponible. Les assertions de conservation,
les 123 tables attendues et les colonnes V17/V18 restent contrôlées explicitement.
Ajouter une migration ne nécessite donc plus de remplacer un numéro de version
dans ces tests ; une évolution de structure demande toujours ses propres contrôles.
